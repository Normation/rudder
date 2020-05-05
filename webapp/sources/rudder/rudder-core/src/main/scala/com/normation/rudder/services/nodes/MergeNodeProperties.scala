/*
*************************************************************************************
* Copyright 2020 Normation SAS
*************************************************************************************
*
* This file is part of Rudder.
*
* Rudder is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU General Public License version 3, the copyright holders add
* the following Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU General
* Public License version 3, when you create a Related Module, this
* Related Module is not considered as a part of the work and may be
* distributed under the license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* Rudder is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with Rudder.  If not, see <http://www.gnu.org/licenses/>.

*
*************************************************************************************
*/

package com.normation.rudder.services.nodes

import cats.implicits._
import com.normation.errors._
import com.normation.rudder.domain.nodes.FullParentProperty
import com.normation.rudder.domain.nodes.GenericPropertyUtils
import com.normation.rudder.domain.nodes.GroupProperty
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.domain.nodes.NodeProperty
import com.normation.rudder.domain.nodes.NodePropertyHierarchy
import com.normation.rudder.domain.nodes.NodePropertyProvider
import com.normation.rudder.domain.policies.FullCompositeRuleTarget
import com.normation.rudder.domain.policies.FullGroupTarget
import com.normation.rudder.domain.policies.FullOtherTarget
import com.normation.rudder.domain.policies.FullRuleTargetInfo
import com.normation.rudder.domain.queries._
import com.normation.rudder.services.nodes.GroupProp._
import com.softwaremill.quicklens._
import net.liftweb.json.Diff
import net.liftweb.json.JsonAST.JNothing
import net.liftweb.json.JsonAST.JValue
import org.jgrapht.alg.connectivity.ConnectivityInspector
import org.jgrapht.graph.AsSubgraph
import org.jgrapht.graph.DefaultDirectedGraph
import org.jgrapht.graph.DefaultEdge
import org.jgrapht.traverse.TopologicalOrderIterator

import scala.jdk.CollectionConverters._

/**
 * This file handle how node properties are merged with other (global, groups, etc)
 * properties.
 * Merge happens during policy generation and only spans that policy generation, ie nodes
 * don't really have these properties: if you observe a node (for ex with API), you won't see them.
 *
 * Merge rules are the following:
 * Overriding is authorized for same keys IF AND ONLY IF:
 * - on a group by another group, if the second group is a subgroup of the first,
 *   i.e if he has a "AND" query composition, same nature (static/dynamic), and
 *   a `SubGroupComparator` criterion with value the parent groupid.
 * - on a node, if provider if the same.
 *
 * When overriding happens, we use a merge-override strategy, ie:
 * - if the overriding value is a simple value or an array, it replaces the previous one
 * - if the overriding value is an object and the overridden value is a simple type or an array,
 *   the latter one is replaced by the former
 * - if both overriding/overridden are arrays, overriding values are appended to overridden array
 * - if both overriding/overridden are objects, then each key is overridden recursively as explained,
 *   and new keys are added.
 */


/**
 * Utility that represents a group with just the interesting things for us.
 */
final case class GroupProp(
    properties  : List[GroupProperty]
  , groupId     : NodeGroupId
  , condition   : CriterionComposition
  , isDynamic   : Boolean
  , parentGroups: List[String] // groupd ids - a list because order is important!
  , groupName   : String // for error
)

object GroupProp {

  val INHERITANCE_PROVIDER = NodePropertyProvider("inherited")
  val EMPTY_DIFF = Diff(JNothing, JNothing, JNothing)

  implicit class ToNodePropertyHierarchy(g: GroupProp) {
    def toNodePropHierarchy: Map[String, NodePropertyHierarchy[JValue]] = {
      g.properties.map { p =>
        (
          p.name
        , NodePropertyHierarchy(
              NodeProperty(p.name, p.value, Some(INHERITANCE_PROVIDER))
            , FullParentProperty.Group(g.groupName, g.groupId, p.value) :: Nil
          )
        )
      }.toMap
    }
  }

  /**
   * Utility class to transform RuleTarget (which are the things where we get when
   * we resolve node belongings, for some reason I don't know about) into GroupProp.
   * Parent order is keep so that if P1 is on line 1, P2 is on line 2, then we get:
   * P1 :: P2 :: Nil
   */
  implicit class FromTarget(target: FullRuleTargetInfo) {
    def toGroupProp: PureResult[GroupProp] = {
      target.target match {
        case FullCompositeRuleTarget(t) =>
          Left(Unexpected(s"There is a composite target in group definition, it's likely a dev error: '${target.name}'"))
        case FullOtherTarget(t) => //taget:all nodes, only root, only managed node, etc
          Right(GroupProp(
              Nil   // they don't have properties for now
            , NodeGroupId(t.target)
            , And   // for simplification, since they don't have properties it doesn't matter
            , true  // these special targets behave as dynamic groups
            , Nil   // terminal leaf
            , target.name
          ))
        case FullGroupTarget(t, g) =>
          g.query match {
            case None =>
              // if group doesn't has a query: not sure. Error ? Default ?
              Right(GroupProp(
                  g.properties
                , g.id
                , And
                , g.isDynamic
                , Nil   // terminal leaf
                , target.name
              ))
            case Some(q) =>
              Right(GroupProp(
                  g.properties
                , g.id
                , q.composition
                , g.isDynamic
                , q.criteria.flatMap {
                    // we are only interested in subgroup criterion with AND, and we want to
                    // keep order for overriding priority.
                    case CriterionLine(_, a, _, value) if(q.composition == And && a.cType.isInstanceOf[SubGroupComparator]) => Some(value)
                    case _ => None
                  }
                , target.name
              ))
          }
      }
    }
  }
}

object MergeNodeProperties {

  /**
   * Merge that node properties with properties of groups which contain it.
   * Groups are not sorted, but all groups with that node are present.
   */
  def withDefaults(node: NodeInfo, nodeTargets: List[FullRuleTargetInfo], globalParams: Map[String, JValue]): PureResult[List[NodePropertyHierarchy[JValue]]] = {
    for {
      defaults <- checkPropertyMerge(nodeTargets, globalParams)
    } yield {
      mergeDefault(node.properties.map(p => (p.name, p)).toMap, defaults.map(p => (p.prop.name, p)).toMap).map(_._2).toList.sortBy(_.prop.name)
    }
  }

  /*
   * Actually merge existing properties with default inherited ones.
   * Fully inherited properties got an "inherited" provider.
   * NodePropertyHierarchy with a non empty parent list means they were inherited and at least
   * partially overridden
   */
  def mergeDefault(properties: Map[String, NodeProperty], defaults: Map[String, NodePropertyHierarchy[JValue]]): Map[String, NodePropertyHierarchy[JValue]] = {
    val fullyInherited = defaults.keySet -- properties.keySet
    val fromNodeOnly = properties.keySet -- defaults.keySet
    val merged = defaults.keySet.intersect(properties.keySet)
    val overrided = merged.map { k =>
      val p = properties(k)
      val d = defaults(k)
      // here, we actually do merge/override, the new value being the node value.
      // I'm not sure if we should ONLY override node properties with "default" provider here, or perhaps we should
      // add a notion of provider to nodes and only merge compatible providers?
      (k, d.copy(prop = p.copy(value = GenericPropertyUtils.mergeValues(d.prop.value, p.value))))
    }.toMap
    (
       defaults.filter(kv => fullyInherited.contains(kv._1))
    ++ overrided
    ++ properties.flatMap { case (k, v) => if(fromNodeOnly.contains(k)) Some((k, NodePropertyHierarchy[JValue](v, Nil))) else None }.toMap
    )
  }

  /**
   * Check that the given list of group is a legal set of properties, ie one of:
   * - all properties have different keys,
   * - if n groups share a key, these n groups are in an inheritance relation,
   *   i.e they have a subgroup chain.
   *   In that case, properties are merged according to the merge function.
   *
   * We know that we have all groups/subgroups in which the node is here.
   */
  def checkPropertyMerge(targets: List[FullRuleTargetInfo], globalParams: Map[String, JValue]): PureResult[List[NodePropertyHierarchy[JValue]]] = {
    /*
     * General strategy:
     * - build all disjoint hierarchies of groups that contains that node
     *   (a hierarchy is defined by our inherance rules, so we can perfectly have
     *   n overlapping groups for the set of nodes they contains that are not
     *   in a hierarchy).
     * - for each hierarchy, resolve overriding in properties
     * - then, merge all resulting properties. At that point, two properties with the
     *   same key are in conflict (by our definition of "not in conflict only if
     *   they are in the same hierarchy").
     */

    /*
     * merge overriding properties with overridden one. Here, we don't deal with conflicts,
     * so we just want to merge according to following rules:
     * - if the overriding value is a simple value or an array, it replaces the previous one
     * - if the overriding value is an object and the overridden value is a simple type or an array,
     *   the latter one is replaced by the former
     * - if both overriding/overridden are array, then overriding values are appended to overridden array
     * - if both overriding/overridden are object, then each key is overridden recursively as explained,
     *   and new keys are added.
     * See https://github.com/lift/lift/tree/master/framework/lift-base/lift-json/#merging--diffing
     * for more information.
     * The most prioritary is the last in the list
     */
    def overrideValues(overriding: List[Map[String, NodePropertyHierarchy[JValue]]]): Map[String, NodePropertyHierarchy[JValue]] = {
      overriding.foldLeft(Map[String, NodePropertyHierarchy[JValue]]()){ case (old, newer) =>
        // for each newer value, we look if an older exists. If so, we keep the old value in the list of parents,
        // and merge its value for the next iteration.
        val merged = newer.map { case(k, v) =>
          old.get(k) match {
            case None          => //ok, no merge needed
              (k, v)
            case Some(oldProp) => //merge prop and add old to parents
              val value = GenericPropertyUtils.mergeValues(oldProp.prop.value, v.prop.value)
              val mergedProp = v.modify(_.prop.value).setTo(value).modify(_.parents).using( _ ::: oldProp.parents)
              (k, mergedProp)
          }
        }
        old ++ merged
      }
    }

    /*
     * Last merge: check if any property is defined in at least two groups.
     * If it's the case, report error, else return all properties
     */
    def mergeAll(propByTrees: List[NodePropertyHierarchy[JValue]]): PureResult[List[NodePropertyHierarchy[JValue]]] = {
      val grouped = propByTrees.groupBy(_.prop.name).map {
        case (_, Nil)    => Left(Unexpected(s"A groupBY lead to an empty group. This is a developper bug, please report it."))
        case (_, h::Nil) => Right(h)
        case (k, h::more)   =>
          if(more.forall(_.prop == h.prop)) {
            // if it's exactly the same property everywhere, it's ok
            Right(h)
          } else {
            // faulty groups are the head of parent list of each parent list
            val faulty = (h::more).map { p =>
              p.parents match {
                case Nil        => "" // should not happen
                case g::Nil     =>
                  g.displayName
                case g::parents =>
                  s"${g.displayName} (with inheritance from: ${parents.map(_.displayName).mkString("; ")}"
              }
            }
            Left(Inconsistency(s"Error when trying to find overrides for group property '${k}'. " +
                             s"Several groups which are not in an inheritance relation define it. You will need to define " +
                             s"a new group with all these groups as parent and choose the order on which you want to do " +
                             s"the override by hand. Faulty groups: ${faulty.mkString(", ")}"
            ))
          }
      }
      grouped.accumulatePure(identity)
    }

    for {
      groups    <- targets.traverse (_.toGroupProp.map(g => (g.groupId.value, g))).map(_.toMap)
      sorted    <- sortGroups(groups)
      // add global values as the most default NnodePropertyHierarchy
      overridden =  sorted.map(groups => overrideValues(groups.map(_.toNodePropHierarchy)))
      // now flatten properties from all groups so that we can check for duplicates
      flatten   =  overridden.map(_.values).flatten
      merged    <- mergeAll(flatten)
    } yield {
      // here, we add global parameters as a first default
      val globalParamProps = globalParams.map { case (n, v) =>
        (n, NodePropertyHierarchy[JValue](NodeProperty(n, v, Some(INHERITANCE_PROVIDER)), FullParentProperty.Global(v) :: Nil))
      }.toMap
      val mergedProps = merged.map(p => (p.prop.name, p)).toMap
      overrideValues(globalParamProps :: mergedProps :: Nil).values.toList
    }
  }

  /**
   *
   * Sort groups.
   * 1/ Start by finding all graph (almost tree) of groups. That part can fail if any parent
   * referenced in a group criterium is missing in the parameter list.
   *
   * Edges are defined thanks to two properties:
   * - if S is a subgroup of P, then there's an edge from P to S (P -> S),
   * - if S is subgroup of P1 (line 1) and P2 (line 2), then there's an edge from P1 to P2 (P1 -> p2)
   *
   * 2/ Then, for each graph, return the topological sort of its GroupProp. That part can
   * fail is there is cycle in any graph.
   *
   * The sort returns the oldest parent first.
   *
   */
  def sortGroups(groups: Map[String, GroupProp]): PureResult[List[List[GroupProp]]] = {
    type GRAPH = DefaultDirectedGraph[GroupProp, DefaultEdge]
    // Recursively add groups in the list. The last group is the oldest parent.
    // `addEdge` can throw exception if one of the vertice is missing (it can only be the parent in our case)
    def recAddEdges(graph: GRAPH, child: GroupProp, parents: List[GroupProp]): PureResult[GRAPH] = {
      for {
        _ <- parents.traverse { p =>
               // P -> S
               try {
                 Right(graph.addEdge(p, child))
               } catch {
                 case ex: Exception => // At least NPE, IllegalArgEx, UnsupportedException...
                   Left(SystemError(s"Error when trying to build group hierarchy of group '${child.groupName}' " +
                                    s"(${child.groupId.value}) for parent '${p.groupName}' (${p.groupId.value})", ex))
               }
             }
        g <- parents match {
               case Nil  => Right(graph)
               case h::t => recAddEdges(graph, h, t)
             }
      } yield g
    }

    // we are in java highly mutable land - this algo can't be parallelized without going to ZIO
    val graph = new DefaultDirectedGraph[GroupProp, DefaultEdge](classOf[DefaultEdge])
    val groupList = groups.values.toList
    for {
      // we need to proceed in two pass because all vertices need to be added before edges
      // add veritce
      _      <- groupList.traverse { group =>
                  try {
                    Right(graph.addVertex(group))
                  } catch {
                    case ex: Exception => // At least NPE, IllegalArgEx, UnsupportedException...
                      Left(SystemError(s"Error when trying to build group hierarchy of group '${group.groupName}' ${group.groupId.value})", ex))
                  }
                }
      // add edges
      _      <- groupList.traverse { group =>
                  for {
                    parents <- group.parentGroups.traverse { p =>
                                 groups.get(p) match {
                                   case None    =>
                                     Left(Inconsistency(s"Error when looking for parent group '${p}' of group '${group.groupName}' " +
                                                        s"[${group.groupId.value}]. Please check criterium for that group."))
                                   case Some(g) =>
                                     Right(g)
                                 }
                               }
                               // reverse parents here, because we need to start from child and go up hierarchy
                    _       <- recAddEdges(graph, group, parents.reverse)
                  } yield ()
                }
      // get connected components
      sets   <- try {
                  Right(new ConnectivityInspector(graph).connectedSets().asScala)
                } catch {
                  case ex: Exception => Left(SystemError(s"Error when looking for groups hierarchies", ex))
                }
      sorted <- sets.toList.traverse { set =>
                  try {
                    val subgraph = new AsSubgraph[GroupProp, DefaultEdge](graph, set, null)
                    val sort = new TopologicalOrderIterator[GroupProp, DefaultEdge](subgraph)
                    // can throw if there is cycles
                    Right(sort.asScala.toList)
                  } catch {
                    case ex: Exception =>
                      Left(SystemError(s"Error when sorting group of node: there is cycles in parent hierarchy or in their" +
                                       s" priority order. Please ensure that `group` criteria are always in the same order", ex))
                  }
                }
    } yield sorted
  }

}

