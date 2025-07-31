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
import com.normation.errors.*
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.policies.FullGroupTarget
import com.normation.rudder.domain.properties.*
import com.normation.rudder.domain.properties.PropertyHierarchyError.*
import com.normation.rudder.domain.properties.PropertyVertex.ParentProperty
import com.normation.rudder.domain.queries.*
import com.normation.rudder.facts.nodes.CoreNodeFact
import com.normation.rudder.properties.GroupProp.*
import com.typesafe.config.ConfigRenderOptions
import com.typesafe.config.ConfigValueType
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
    ): Map[String, PropertyVertex.Group] = {
      g.properties.map { p =>
        val v = globalParameters.get(p.name).map(g => PropertyVertex.Global(PropertyValueKind.SelfValue(g))) match {
          case Some(global) => PropertyValueKind.Overridden(p, global)
          case None         => PropertyValueKind.SelfValue(p)
        }

        (
          p.name,
          PropertyVertex.Group(g.groupName, g.groupId, v)
        )
      }.toMap
    }
  }

  implicit class FromNodeGroup(group: NodeGroup) {
    def toGroupProp: GroupProp = {
      group.query match {
        case None    =>
          // if group doesn't have a query: not sure. Error ? Default ?
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
          mergeDefaultNode(
            node,
            node.properties.map(p => (p.name, p)).toMap,
            props.map(p => (p.value.resolvedValue.name, p)).toMap
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
    val groupProp     = group.nodeGroup.toGroupProp
    val allGroupProps = allGroups.map { case (id, g) => (id.serialize, g.nodeGroup.toGroupProp) }

    // get parents till the top, recursively.
    // This can fail if a parent is missing from "all groups"
    def withParents(
        currents:  List[GroupProp],
        allGroups: Map[String, GroupProp], // key: serialized version of NodeGroupId, as parents of GroupProp is a List[String]
        acc:       Map[NodeGroupId, GroupProp]
    ): Either[PropertyHierarchyError.MissingParentGroup, Map[NodeGroupId, GroupProp]] = {
      currents match {
        case Nil  => Right(acc)
        case list =>
          list.foldLeft(Right(acc): Either[PropertyHierarchyError.MissingParentGroup, Map[NodeGroupId, GroupProp]]) {
            case (optAcc, prop) =>
              optAcc match {
                case Left(err)  => Left(err)
                case Right(acc) =>
                  if (acc.isDefinedAt(prop.groupId)) Right(acc) // already done previously
                  else {
                    for {
                      parents <- prop.parentGroups.traverse { parentId =>
                                   val parentExists = NodeGroupId.parse(parentId).map(acc.isDefinedAt(_)).getOrElse(false)
                                   if (parentExists) Right(None)
                                   else {
                                     allGroups.get(parentId) match {
                                       case None    =>
                                         Left(PropertyHierarchyError.MissingParentGroup(prop, parentId))
                                       case Some(p) =>
                                         Right(Some(p))
                                     }
                                   }
                                 }
                      rec     <- withParents(parents.flatten, allGroups, acc + (prop.groupId -> prop))
                    } yield {
                      rec
                    }
                  }
              }
          }
      }
    }

    // Group may resolve conflicting properties from parents, for some properties
    // so we need to mark those as resolved, with the most prioritized parent (see https://issues.rudder.io/issues/26325)
    def checkMergeGroupParent(
        otherProps: Map[NodeGroupId, GroupProp]
    ): Ior[PropertyHierarchyError, List[ParentProperty[?]]] = {

      def findLastMatchingGroup(p: PropertyVertex[?]): Option[PropertyVertex.Group] = {
        p match {
          // in the case of node, we look for parent. If none, stop here, else recurse
          case n: PropertyVertex.Node   =>
            p.value match {
              case PropertyValueKind.SelfValue(_)          => None
              case PropertyValueKind.Inherited(parent)     => findLastMatchingGroup(parent)
              case PropertyValueKind.Overridden(_, parent) => findLastMatchingGroup(parent)
            }

          // in the case of parent, we stop at the first "self value", ie no inheritance other than global
          case g: PropertyVertex.Group  =>
            p.value match {
              case PropertyValueKind.SelfValue(v)          => Some(g)
              case PropertyValueKind.Inherited(parent)     =>
                if (parent.kind == PropertyVertexKind.Global) Some(g) else findLastMatchingGroup(parent)
              case PropertyValueKind.Overridden(_, parent) =>
                if (parent.kind == PropertyVertexKind.Global) Some(g) else findLastMatchingGroup(parent)
            }

          // that's not a group
          case _: PropertyVertex.Global => None
        }
      }

      checkPropertyMerge(otherProps, globalParams) match {
        case Ior.Right(b) => Ior.Right(b)
        case withErr      =>
          withErr.left match {
            case Some(err: PropertyHierarchyError.PropertyHierarchySpecificInheritanceConflicts) =>
              // we need to remove resolved conflicting properties and transfer them to the success part
              val resolved  = err.conflicts.toList.flatMap {
                case (nodeProp, parentProps: NonEmptyChunk[PropertyVertex[?]]) =>
                  for {
                    // reverse to get the parent which has the highest priority
                    firstParent     <- groupProp.parentGroups.reverse.headOption
                    parent          <- allGroupProps.get(firstParent)
                    // we assume that there is a single property value for the same name (upstream we have List and not a Map)
                    parentProp      <- parent.properties.find(_.name == nodeProp.name)
                    // parentProps probably needs to be a NonEmptyChunk(VerticalPropTree[{node=(1),groups=(*),global=(1)}])
                    // and what we want is a VerticalPropTree that has the parent group as its dominant group i.e. most prioritized groups, the last one
                    // we probably want to have this natural subdivision, such that the Ordering[ParentProperty] will disappear, as it's explicitly : .node, .groups (already sorted), .global
                    parentHierarchy <- parentProps.collectFirst {
                                         case l
                                             if findLastMatchingGroup(l)
                                               .map(_.id)
                                               .contains(parent.groupId.serialize) =>
                                           l
                                       }
                  } yield {
                    parentProp -> parentHierarchy
                  }
              }
              val toResolve = resolved.map { case (parentProp, _) => parentProp.name }.toSet
              val updated   = withErr.bimap(
                {
                  case err: PropertyHierarchyError.PropertyHierarchySpecificInheritanceConflicts => err.resolve(toResolve)
                  case err => err
                },
                _ ++ resolved.map {
                  case (_, hierarchy) =>
                    // we always have a `NodeProperty`, instead of a `GroupProperty`
                    // and the value is "inherited" from the parent
                    hierarchy

                }
              )
              // it may happen that conflicts are empty, in which case it can be simplified to Ior.Right
              updated match {
                case Ior.Both(PropertyHierarchyError.PropertyHierarchySpecificInheritanceConflicts.empty, r) => Ior.Right(r)
                case Ior.Left(PropertyHierarchyError.PropertyHierarchySpecificInheritanceConflicts.empty)    => Ior.Right(List.empty)
                case other                                                                                   => other
              }
            case None | Some(_: PropertyHierarchyError)                                          => withErr
          }
      }
    }

    val resolved = for {
      hierarchy   <-
        // this fails with an empty list of property, missing parents errors are short-circuiting, as they may be the cause of misconfiguration
        Ior
          .fromEither[PropertyHierarchyError, Map[NodeGroupId, GroupProp]](withParents(groupProp :: Nil, allGroupProps, Map()))
      otherProps   = hierarchy - group.nodeGroup.id
      hierarchies <- checkMergeGroupParent(otherProps)

    } yield {
      val groupProps = group.nodeGroup.properties.map(g => (g.name, g)).toMap
      mergeDefaultGroup(
        group.nodeGroup,
        groupProps,
        hierarchies.map(h => (h.value.resolvedValue.name, h)).toMap
      ).values
    }

    ResolvedNodePropertyHierarchy.from(resolved)
  }

  /*
   * Actually merge existing properties with default inherited ones.
   * Fully inherited properties got an "inherited" provider.
   * NodePropertyHierarchy with a non empty parent list means they were inherited and at least
   * partially overridden
   */
  def mergeDefaultNode(
      node:       CoreNodeFact,
      properties: Map[String, NodeProperty],
      parents:    Map[String, ParentProperty[?]]
  ): Map[String, PropertyHierarchy] = {
    val allKeys = parents.keySet ++ properties.keySet
    allKeys.flatMap { k =>
      (properties.get(k), parents.get(k)) match {
        case (None, None)    => None
        case (Some(x), d)    => Some((k, NodePropertyHierarchy.forNodeWithValue(node.fqdn, node.id, x, d)))
        case (None, Some(d)) => Some((k, NodePropertyHierarchy.forInheritedNode(node.fqdn, node.id, d)))
      }
    }.toMap
  }

  def mergeDefaultGroup(
      group:      NodeGroup,
      properties: Map[String, GroupProperty],
      parents:    Map[String, ParentProperty[?]]
  ): Map[String, PropertyHierarchy] = {
    val allKeys = parents.keySet ++ properties.keySet
    allKeys.flatMap { k =>
      (properties.get(k), parents.get(k)) match {
        case (None, None)                => None
        case (Some(x: GroupProperty), d) => Some((k, GroupPropertyHierarchy.forGroupWithValue(group.name, group.id, x, d)))
        case (None, Some(d))             => Some((k, GroupPropertyHierarchy.forInheritedGroup(group.name, group.id, d)))
      }
    }.toMap
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
  ): Ior[PropertyHierarchyError, List[ParentProperty[?]]] = {
    /*
     * General strategy:
     * - build all disjoint hierarchies of groups that contains that node
     *   (a hierarchy is defined by our inheritance rules, so we can perfectly have
     *   n overlapping groups for the set of nodes they contain that are not
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
     *
     * The most prioritary is the last in the list
     */
    def overrideValues(
        overriding: List[Map[String, ParentProperty[?]]]
    ): Map[String, ParentProperty[?]] = {
      overriding.foldLeft(Map[String, ParentProperty[?]]()) {
        case (old, newer) =>
          // for each newer value, we look if an older exists. If so, we keep the old value in the list of parents,
          // and merge its value for the next iteration.
          val merged = newer.map {
            case (k, v) =>
              old.get(k) match {
                case None                                 => // ok, no merge needed
                  (k, v)
                case Some(oldProp: PropertyVertex.Global) => // merge prop and add old to parents
                  v match {
                    case g: PropertyVertex.Group  => (k, PropertyVertex.appendAsRoot(g, oldProp))
                    case g: PropertyVertex.Global => (k, oldProp) // we keep old
                  }
                case Some(oldProp: PropertyVertex.Group)  =>
                  v match {
                    // the old one is kept as the outermost since newer has more priority
                    case g: PropertyVertex.Group  => (k, PropertyVertex.appendAsRoot(g, oldProp))
                    // global is the outermost
                    case g: PropertyVertex.Global => (k, PropertyVertex.appendAsRoot(oldProp, g))
                  }
              }
          }
          old ++ merged // update with newer
      }
    }

    /*
     * Check if any property is defined in at least two groups.
     * If it's the case, report error, else return all properties.
     */
    def checkAll(
        propByTrees: List[ParentProperty[?]]
    ): Ior[PropertyHierarchyError.PropertyHierarchySpecificInheritanceConflicts, List[ParentProperty[?]]] = {
      // work on non empty chain for repeated append operations on the resulting hierarchy (due to cumulating both error and success)
      val grouped = propByTrees.groupByNec(_.value.resolvedValue.name)

      val validatedProps = grouped.toList.map {
        case (k, c) =>
          val (h, more) = c.uncons
          if (more.forall(_.value == h.value)) { // if it's exactly the same property everywhere, it's ok
            Right(h)
          } else {
            Left(
              PropertyHierarchyError.PropertyHierarchySpecificInheritanceConflicts
                .one(
                  h.value.resolvedValue,
                  NonEmptyChunk(h) ++ Chunk.from(more.iterator)
                )
            )
          }
      }

      // This is guaranteed to be at least a Both, since we start with a right one and we just add potential errors
      val initialValue = {
        Ior.right[PropertyHierarchyError.PropertyHierarchySpecificInheritanceConflicts, Chain[ParentProperty[?]]](
          Chain.empty
        )
      }
      // The right combinator that allows to use the semigroup structure of the error is the Ior data type
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
      // and merge each sublist resolving conflicts
      overridden = sorted.map(groups => overrideValues(groups.map(_.toNodePropHierarchy(globalParams))))
      // now flatten properties from all groups so that we can check for duplicates
      flatten    = overridden.map(_.values).flatten
      checked   <- checkAll(flatten)
    } yield {
      // add globals
      val globals      = globalParams.toList.map { case (n, v) => (n, PropertyVertex.Global(PropertyValueKind.SelfValue(v))) }
      // here, we add global parameters for all case where no groups use them
      val checkedProps = checked.map(p => (p.value.resolvedValue.name, p)).toMap
      val res          = overrideValues(globals.toMap :: checkedProps :: Nil).values.toList
      res
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
  def sortGroups(groups: Map[String, GroupProp]): Either[PropertyHierarchyError.DAGHierarchyError, List[List[GroupProp]]] = {
    type GRAPH = DefaultDirectedGraph[GroupProp, DefaultEdge]
    // Recursively add groups in the list. The last group is the oldest parent.
    // `addEdge` can throw exception if one of the vertice is missing (it can only be the parent in our case)
    def recAddEdges(
        graph:   GRAPH,
        child:   GroupProp,
        parents: List[GroupProp]
    ): Either[PropertyHierarchyError.DAGHierarchyError, GRAPH] = {
      for {
        _ <- parents.traverse { p =>
               // P -> S
               try {
                 Right(graph.addEdge(p, child))
               } catch {
                 case ex: Exception => // At least NPE, IllegalArgEx, UnsupportedException...
                   Left(
                     PropertyHierarchyError.DAGHierarchyError(
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
                        PropertyHierarchyError.DAGHierarchyError(
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
                                           PropertyHierarchyError.DAGHierarchyError(
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
                    Left(PropertyHierarchyError.DAGHierarchyError(s"Error when looking for groups hierarchies: ${ex.getMessage}"))
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
                        PropertyHierarchyError.DAGHierarchyError(
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
  def checkValueTypes(properties: PropertyVertex[?]): PureResult[Unit] = {
    def itemMessage(prop: PropertyVertex[?]): String = {
      prop match {
        case g:    PropertyVertex.Group  =>
          s"Group '${g.name}' (${g.id}) with value '${g.value.resolvedValue.value.render(ConfigRenderOptions.concise())}'"
        case n:    PropertyVertex.Node   =>
          s"Node '${n.name}' (${n.id}) with value '${n.value.resolvedValue.value.render(ConfigRenderOptions.concise())}'"
        case glob: PropertyVertex.Global =>
          s"Global property with value '${glob.value.resolvedValue.value.render(ConfigRenderOptions.concise())}'"
      }
    }

    def message(property: PropertyVertex[?]): List[String] = {
      property.value match {
        case PropertyValueKind.SelfValue(_)                  => itemMessage(property) :: Nil
        case PropertyValueKind.Inherited(parentProperty)     => message(parentProperty)
        case PropertyValueKind.Overridden(_, parentProperty) => itemMessage(property) :: message(parentProperty)
      }
    }

    def check(property: PropertyValueKind[?], valueType: ConfigValueType): Boolean = {
      property match {
        case PropertyValueKind.SelfValue(v) =>
          v.value.valueType() == valueType

        case PropertyValueKind.Inherited(parentProperty) =>
          check(parentProperty.value, valueType)

        case PropertyValueKind.Overridden(v, parentProperty) =>
          v.value.valueType() == valueType && check(parentProperty.value, valueType)
      }
    }

    if (!check(properties.value, properties.value.resolvedValue.value.valueType())) {
      val overrides = message(properties)
      Left(
        Inconsistency(
          s"Property '${properties.value.resolvedValue.name}' has different types in the hierarchy. It's not allowed. " +
          s"Downward elements with different types: ${overrides.mkString(", ")}"
        )
      )
    } else {
      Right(())
    }
  }

}
