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

package com.normation.rudder.properties

import cats.data.Chain
import cats.data.Ior
import cats.syntax.functor.*
import cats.syntax.list.*
import cats.syntax.traverse.*
import com.normation.GitVersion
import com.normation.errors.*
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.nodes.NodeGroupUid
import com.normation.rudder.domain.policies.FullGroupTarget
import com.normation.rudder.domain.properties.GenericProperty
import com.normation.rudder.domain.properties.GlobalParameter
import com.normation.rudder.domain.properties.GroupProperty
import com.normation.rudder.domain.properties.InheritMode
import com.normation.rudder.domain.properties.NodeProperty
import com.normation.rudder.domain.properties.NodePropertyError
import com.normation.rudder.domain.properties.NodePropertyHierarchy
import com.normation.rudder.domain.properties.ParentProperty
import com.normation.rudder.domain.properties.PropertyProvider
import com.normation.rudder.domain.properties.ResolvedNodePropertyHierarchy
import com.normation.rudder.domain.queries.*
import com.normation.rudder.facts.nodes.CoreNodeFact
import com.normation.rudder.properties.GroupProp.*
import com.softwaremill.quicklens.*
import com.typesafe.config.ConfigParseOptions
import com.typesafe.config.ConfigRenderOptions
import org.jgrapht.alg.connectivity.ConnectivityInspector
import org.jgrapht.graph.AsSubgraph
import org.jgrapht.graph.DefaultDirectedGraph
import org.jgrapht.graph.DefaultEdge
import org.jgrapht.traverse.TopologicalOrderIterator
import scala.jdk.CollectionConverters.*
import zio.*

/**
 * This file handle how node properties are merged with other (global, groups, etc)
 * properties.
 * Merge happens during policy generation and only spans that policy generation, ie nodes
 * don't really have these properties: if you observe a node (for ex with API), you won't see them.
 *
 * Merge rules are the following:
 * Overriding is authorized for same keys IF AND ONLY IF:
 * - on a group by another group, if the second group is a subgroup of the first,
 *   i.e if it has a "AND" query composition, same nature (static/dynamic), and
 *   a `SubGroupComparator` criterion with value the parent groupid.
 * - on a node, if provider if the same.
 *
 * When overriding happens, we use a merge-override strategy, ie:
 * - if the overriding value is a simple value or an array, it replaces the previous one
 * - if the overriding value is an object and the overridden value is a simple type or an array,
 *   the latter one is replaced by the former
 * - if both overriding/overridden are arrays, overriding values REPLACE the overridden ones
 * - if both overriding/overridden are objects, then each key is overridden recursively as explained,
 *   and new keys are added.
 */

/**
 * Utility that represents a group with just the interesting things for us.
 */
final case class GroupProp(
    properties:     List[GroupProperty],
    groupId:        NodeGroupId,
    condition:      CriterionComposition,
    transformation: ResultTransformation,
    isDynamic:      Boolean,
    parentGroups:   List[String], // group ids - a list because order is important!

    groupName: String // for error
)

object GroupProp {

  /**
   * This provider means that the property is purely inherited, ie it is not
   * overridden for given node or group.
   */
  val INHERITANCE_PROVIDER: PropertyProvider = PropertyProvider("inherited")

  /**
   * This provider means that the property is inherited but also
   * overridden for given node or group.
   */
  val OVERRIDE_PROVIDER: PropertyProvider = PropertyProvider("overridden")

  implicit class ToNodePropertyHierarchy(g: GroupProp) {
    def toNodePropHierarchy(implicit
        globalParameters: Map[String, GlobalParameter]
    ): Map[String, (NodePropertyHierarchy, Option[InheritMode])] = {
      g.properties.map { p =>
        val globalInheritMode = globalParameters.get(p.name).flatMap(g => GenericProperty.getMode(g.config))
        (
          p.name,
          NodePropertyHierarchy(
            NodeProperty(p.config).withProvider(INHERITANCE_PROVIDER).withVisibility(p.visibility),
            ParentProperty.Group(g.groupName, g.groupId, p.value) :: Nil
          ) -> globalInheritMode
        )
      }.toMap
    }
  }

  implicit class FromNodeGroup(group: NodeGroup) {
    def toGroupProp: GroupProp = {
      group.query match {
        case None    =>
          // if group doesn't has a query: not sure. Error ? Default ?
          GroupProp(
            group.properties,
            group.id,
            CriterionComposition.And,
            ResultTransformation.Identity,
            group.isDynamic,
            Nil, // terminal leaf

            group.name
          )
        case Some(q) =>
          GroupProp(
            group.properties,
            group.id,
            q.composition,
            q.transform,
            group.isDynamic,
            q.criteria.flatMap {
              // we are only interested in subgroup criterion with AND, and we want to
              // keep order for overriding priority.
              case CriterionLine(_, a, _, value)
                  if (q.composition == CriterionComposition.And && a.cType.isInstanceOf[SubGroupComparator]) =>
                Some(value)
              case _ => None
            },
            group.name
          )
      }
    }
  }
}

object MergeNodeProperties {

  /**
   * Merge that node properties with properties of groups which contain it.
   * Groups are not sorted, but all groups with that node are present.
   */
  def forNode(
      node:         CoreNodeFact,
      groupTargets: Iterable[FullGroupTarget],
      globalParams: Map[String, GlobalParameter]
  ): ResolvedNodePropertyHierarchy = {
    val groupProps = groupTargets.map(_.nodeGroup.toGroupProp).map(g => (g.groupId, g)).toMap

    val properties = checkPropertyMerge(groupProps, globalParams).map(props => {
      Chunk
        .fromIterable(
          mergeDefault(
            node.id.value,
            node.properties.map(p => (p.name, p)).toMap,
            props.map(p => (p.prop.name, p)).toMap
          ).values
        )
        .sortBy(_.prop.name)
    })
    ResolvedNodePropertyHierarchy.from(properties)
  }

  /*
   * Find parent group for a given group and merge its properties
   */
  def forGroup(
      group:        FullGroupTarget,
      allGroups:    Map[NodeGroupId, FullGroupTarget],
      globalParams: Map[String, GlobalParameter]
  ): ResolvedNodePropertyHierarchy = {
    // get parents till the top, recursively.
    // This can fail if a parent is missing from "all groups"
    def withParents(
        currents:  List[FullGroupTarget],
        allGroups: Map[String, FullGroupTarget], // key: serialized version of NodeGroupId, parent is a String
        acc:       Map[NodeGroupId, FullGroupTarget]
    ): Either[NodePropertyError.MissingParentGroup, Map[NodeGroupId, FullGroupTarget]] = {
      import cats.implicits.*
      currents match {
        case Nil  => Right(acc)
        case list =>
          list.foldLeft(Right(acc): Either[NodePropertyError.MissingParentGroup, Map[NodeGroupId, FullGroupTarget]]) {
            case (optAcc, g) =>
              optAcc match {
                case Left(err)  => Left(err)
                case Right(acc) =>
                  if (acc.isDefinedAt(g.nodeGroup.id)) Right(acc) // already done previously
                  else {
                    val prop = g.nodeGroup.toGroupProp
                    for {
                      parents <- prop.parentGroups.traverse { parentId =>
                                   val parentExists = NodeGroupId.parse(parentId).map(acc.isDefinedAt(_)).getOrElse(false)
                                   if (parentExists) Right(Nil)
                                   else {
                                     allGroups.get(parentId) match {
                                       case None    =>
                                         Left(NodePropertyError.MissingParentGroup(g.nodeGroup, parentId))
                                       case Some(p) =>
                                         Right(p :: Nil)
                                     }
                                   }
                                 }
                      rec     <- withParents(parents.flatten, allGroups, acc + (g.nodeGroup.id -> g))
                    } yield {
                      rec
                    }
                  }
              }
          }
      }
    }
    val resolved = for {
      hierarchy   <-
        // this fails with an empty list of property, maybe we should also ignore group parent errors, as with "sortGroups"
        Ior.fromEither(withParents(group :: Nil, allGroups.map { case (id, group) => (id.serialize, group) }, Map()))
      props        = (hierarchy - group.nodeGroup.id).map { case (groupId, target) => (groupId, target.nodeGroup.toGroupProp) }
      hierarchies <- checkPropertyMerge(props, globalParams)
    } yield {
      val groupProps = group.nodeGroup.properties.map(g => (g.name, g)).toMap
      mergeDefault(group.nodeGroup.id.serialize, groupProps, hierarchies.map(h => (h.prop.name, h)).toMap).values
    }

    ResolvedNodePropertyHierarchy.from(resolved)
  }

  /*
   * Actually merge existing properties with default inherited ones.
   * Fully inherited properties got an "inherited" provider.
   * NodePropertyHierarchy with a non empty parent list means they were inherited and at least
   * partially overridden
   */
  def mergeDefault[A <: GenericProperty[A]](
      objectId:   String,
      properties: Map[String, A],
      defaults:   Map[String, NodePropertyHierarchy]
  ): Map[String, NodePropertyHierarchy] = {
    val fullyInherited = defaults.keySet -- properties.keySet
    val fromNodeOnly   = properties.keySet -- defaults.keySet
    val merged         = defaults.keySet.intersect(properties.keySet)
    val overrided      = merged.map { k =>
      val p          = properties(k)
      val d          = defaults(k)
      val mergedProp =
        NodeProperty(GenericProperty.mergeConfig(d.prop.config, p.config)(d.prop.inheritMode)).withProvider(OVERRIDE_PROVIDER)
      val obj        = p match {
        case x: NodeProperty    => ParentProperty.Node("this node", NodeId(objectId), p.value)
        case x: GroupProperty   => ParentProperty.Group("this group", NodeGroupId(NodeGroupUid(objectId)), p.value)
        case x: GlobalParameter => ParentProperty.Global(p.value)
      }
      (k, NodePropertyHierarchy(mergedProp, obj :: d.hierarchy))
    }.toMap

    defaults.filter(kv => fullyInherited.contains(kv._1))
    ++ overrided
    ++ properties.flatMap {
      case (k, v) => if (fromNodeOnly.contains(k)) Some((k, NodePropertyHierarchy(NodeProperty(v.config), Nil))) else None
    }
  }

  /**
   * Check that the given list of group is a legal set of properties, ie one of:
   * - all properties have different keys,
   * - if n groups share a key, these n groups are in an inheritance relation,
   *   i.e they have a subgroup chain.
   *   In that case, properties are merged according to the merge function.
   *
   * We know that we have all groups/subgroups in which the node is here.
   *
   * The IOR chaining is necessary to recover partially from successful values
   * and still accumulate errors. It will be the responsibility of caller
   * to discard or not the success part.
   */
  def checkPropertyMerge(
      props:        Map[NodeGroupId, GroupProp],
      globalParams: Map[String, GlobalParameter]
  ): Ior[NodePropertyError, List[NodePropertyHierarchy]] = {
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
    def overrideValues(
        overriding: List[Map[String, (NodePropertyHierarchy, Option[InheritMode])]]
    ): Map[String, NodePropertyHierarchy] = {
      overriding.foldLeft(Map[String, NodePropertyHierarchy]()) {
        case (old, newer) =>
          // for each newer value, we look if an older exists. If so, we keep the old value in the list of parents,
          // and merge its value for the next iteration.
          val merged = newer.map {
            case (k, (v, inheritMode)) =>
              old.get(k) match {
                case None          => // ok, no merge needed
                  (k, v)
                case Some(oldProp) => // merge prop and add old to parents
                  val config     = GenericProperty.mergeConfig(oldProp.prop.config, v.prop.config)(inheritMode)
                  val mergedProp = v.modify(_.prop).using(_.fromConfig(config)).modify(_.hierarchy).using(_ ::: oldProp.hierarchy)
                  (k, mergedProp)
              }
          }
          old ++ merged
      }
    }

    /*
     * Last merge: check if any property is defined in at least two groups.
     * If it's the case, report error, else return all properties.
     */
    def mergeAll(
        propByTrees: List[NodePropertyHierarchy]
    ): Ior[NodePropertyError.PropertyInheritanceConflicts, List[NodePropertyHierarchy]] = {
      // work on non empty chain for repeated append operations on the resulting hierarchy (due to cumulating both error and success)
      val grouped = propByTrees.groupByNec(_.prop.name)

      // The right combinator that allows to use the semigroup structure of the error is the
      val validatedProps = grouped.toList.map {
        case (k, c) =>
          val (h, more) = (c.head, c.tail)
          if (more.forall(_.prop == h.prop)) { // if it's exactly the same property everywhere, it's ok
            Right(h)
          } else {
            Left(
              NodePropertyError.PropertyInheritanceConflicts
                .one(
                  h.prop,
                  NonEmptyChunk(h.hierarchy) ++ Chunk.from(more.map(_.hierarchy).iterator)
                )
            )
          }
      }

      // This is guaranteed to be at least a Both, since we start with a right one and we just add potential errors
      val initialValue = Ior.right[NodePropertyError.PropertyInheritanceConflicts, Chain[NodePropertyHierarchy]](Chain.empty)
      validatedProps
        .foldLeft(initialValue) {
          case (acc, Left(c))  => acc.addLeft(c)
          case (acc, Right(n)) => acc.addRight(Chain.one(n))
        }
        .map(_.toList)
    }

    for {
      sorted    <- Ior.fromEither(sortGroups(props.map { case (k, v) => k.serialize -> v })).addRight {
                     // Return an empty list of custom group properties instead of failing with the no success properties at all.
                     // The consequence would be that it would seem like there are no groups at all, so nodes directly inherit global parameters.
                     // (later we could even return a sort by property that for example adds custom properties for resolved sub-dag,
                     // despite a global group DAG error...)
                     List.empty[List[GroupProp]]
                   }
      // add global values as the most default NodePropertyHierarchy
      overridden = sorted.map(groups => overrideValues(groups.map(_.toNodePropHierarchy(globalParams))))
      // now flatten properties from all groups so that we can check for duplicates
      flatten    = overridden.map(_.values).flatten
      merged    <- mergeAll(flatten)
      globals    = globalParams.toList.map {
                     case (n, v) =>
                       // TODO: no version in param for now
                       val config = GenericProperty.toConfig(
                         n,
                         GitVersion.DEFAULT_REV,
                         v.value,
                         v.inheritMode,
                         Some(INHERITANCE_PROVIDER),
                         None,
                         Some(v.visibility),
                         ConfigParseOptions.defaults().setOriginDescription(s"Global parameter '${n}'")
                       )
                       (n, NodePropertyHierarchy(NodeProperty(config), ParentProperty.Global(v.value) :: Nil) -> v.inheritMode)
                   }
    } yield {
      // here, we add global parameters as a first default
      val mergedProps = merged.map(p => (p.prop.name, p -> p.prop.inheritMode)).toMap
      overrideValues(globals.toMap :: mergedProps :: Nil).values.toList
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
  def sortGroups(groups: Map[String, GroupProp]): Either[NodePropertyError.DAGError, List[List[GroupProp]]] = {
    type GRAPH = DefaultDirectedGraph[GroupProp, DefaultEdge]
    // Recursively add groups in the list. The last group is the oldest parent.
    // `addEdge` can throw exception if one of the vertice is missing (it can only be the parent in our case)
    def recAddEdges(graph: GRAPH, child: GroupProp, parents: List[GroupProp]): Either[NodePropertyError.DAGError, GRAPH] = {
      for {
        _ <- parents.traverse { p =>
               // P -> S
               try {
                 Right(graph.addEdge(p, child))
               } catch {
                 case ex: Exception => // At least NPE, IllegalArgEx, UnsupportedException...
                   Left(
                     NodePropertyError.DAGError(
                       s"Error when trying to build group hierarchy of group '${child.groupName}' " +
                       s"(${child.groupId.serialize}) for parent '${p.groupName}' (${p.groupId.serialize}). Cause: ${ex.getMessage}"
                     )
                   )
               }
             }
        g <- parents match {
               case Nil    => Right(graph)
               case h :: t => recAddEdges(graph, h, t)
             }
      } yield g
    }

    // we are in java highly mutable land - this algo can't be parallelized without going to ZIO
    val graph     = new DefaultDirectedGraph[GroupProp, DefaultEdge](classOf[DefaultEdge])
    val groupList = groups.values.toList
    val topoSort  = for {
      // we need to proceed in two pass because all vertices need to be added before edges
      // add vertices
      _      <- groupList.traverse { group =>
                  try {
                    Right(graph.addVertex(group))
                  } catch {
                    case ex: Exception => // At least NPE, IllegalArgEx, UnsupportedException...
                      Left(
                        NodePropertyError.DAGError(
                          s"Error when trying to build group hierarchy of group '${group.groupName}' ${group.groupId.serialize}): ${ex.getMessage}"
                        )
                      )
                  }
                }
      // add edges
      _      <- groupList.traverse { group =>
                  for {
                    parents <- group.parentGroups.traverse { p =>
                                 groups.get(p) match {
                                   // the nominal case is to have the parent in the hierarchy, so groups.get(p) will return
                                   // it and we can continue merging parent properties.
                                   // I can happen that we don't have the parent in the hierarchy. This typically happens when the
                                   // parent is part of an `invert` query (see https://issues.rudder.io/issues/21924). We don't
                                   // want the parent to take part of property inheritance in that case, so we ignore it.
                                   case None    =>
                                     group.transformation match {
                                       case ResultTransformation.Invert => Right(None)
                                       case _                           =>
                                         Left(
                                           NodePropertyError.DAGError(
                                             s"Error when looking for parent group '${p}' of group '${group.groupName}' " +
                                             s"[${group.groupId.serialize}]. Please check criterium for that group."
                                           )
                                         )
                                     }
                                   case Some(g) =>
                                     Right(Some(g))
                                 }
                               }
                    // reverse parents here, because we need to start from child and go up hierarchy
                    _       <- recAddEdges(graph, group, parents.flatten.reverse)
                  } yield ()
                }
      // get connected components
      sets   <- try {
                  Right(new ConnectivityInspector(graph).connectedSets().asScala)
                } catch {
                  case ex: Exception =>
                    Left(NodePropertyError.DAGError(s"Error when looking for groups hierarchies: ${ex.getMessage}"))
                }
      sorted <- sets.toList.traverse { set =>
                  try {
                    val subgraph = new AsSubgraph[GroupProp, DefaultEdge](graph, set, null)
                    val sort     = new TopologicalOrderIterator[GroupProp, DefaultEdge](subgraph)
                    // can throw if there is cycles
                    Right(sort.asScala.toList)
                  } catch {
                    case ex: Exception =>
                      Left(
                        NodePropertyError.DAGError(
                          s"Error when creating a direct acyclic graph of all groups: there is cycles in parent hierarchy or in their" +
                          s" priority order. Please ensure that `group` criteria are always in the same order for all groups. Cause: ${ex.getMessage}"
                        )
                      )
                  }
                }
    } yield sorted

    topoSort
  }

  /**
    * Check that all properties have the same type in all down the hierarchy (comparing valueType of config).
    * If not, report all downward elements that have overrides with a different type :
    * - inheriting groups that override the property
    * - nodes that override the property
    */
  def checkValueTypes(properties: List[NodePropertyHierarchy]): PureResult[Unit] = {
    properties
      .traverse(v => {
        val valueType = v.prop.value.valueType
        val overrides = v.hierarchy.collect {
          case ParentProperty.Group(name, id, value) =>
            s"Group '${name}' (${id.serialize}) with value '${value.render(ConfigRenderOptions.concise())}'"
          case ParentProperty.Node(name, id, value)  =>
            s"Node '${name}' (${id.value}) with value '${value.render(ConfigRenderOptions.concise())}'"
        }
        if (v.hierarchy.exists(_.value.valueType != valueType)) {
          Left(
            Inconsistency(
              s"Property '${v.prop.name}' has different types in the hierarchy. It's not allowed. " +
              s"Downward elements with different types: ${overrides.mkString(", ")}"
            )
          )
        } else {
          Right(())
        }
      })
      .void
  }

}
