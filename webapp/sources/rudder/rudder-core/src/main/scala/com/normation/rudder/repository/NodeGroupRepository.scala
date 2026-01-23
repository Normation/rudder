/*
 *************************************************************************************
 * Copyright 2011 Normation SAS
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

package com.normation.rudder.repository

import com.normation.errors.*
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.nodes.*
import com.normation.rudder.domain.policies.*
import com.normation.rudder.facts.nodes.CoreNodeFact
import com.normation.rudder.tenants.ChangeContext
import com.normation.rudder.tenants.QueryContext
import com.normation.rudder.tenants.SecurityTag
import com.normation.utils.Utils
import com.unboundid.ldif.LDIFChangeRecord
import scala.collection.MapView
import scala.collection.immutable.SortedMap
import zio.Chunk

/**
 * Here is the ordering for a List[NodeGroupCategoryId]
 * MUST start by the root !
 */
object GroupCategoryRepositoryOrdering extends Ordering[List[NodeGroupCategoryId]] {
  type ID = NodeGroupCategoryId
  override def compare(x: List[ID], y: List[ID]): Int = {
    Utils.recTreeStringOrderingCompare(x.map(_.value), y.map(_.value))

  }
}

/**
 * Here is the ordering for a List[NodeGroupCategoryId]
 * MUST start by the root !
 */
object NodeGroupCategoryOrdering extends Ordering[List[NodeGroupCategoryId]] {
  type ID = NodeGroupCategoryId
  override def compare(x: List[ID], y: List[ID]): Int = {
    Utils.recTreeStringOrderingCompare(x.map(_.value), y.map(_.value))
  }
}

/**
 * A simple container for a category
 * and its direct children ActiveTechniques
 */
final case class CategoryAndNodeGroup(
    category: NodeGroupCategory,
    groups:   Set[NodeGroup]
)

final case class FullNodeGroupCategory(
    id:            NodeGroupCategoryId,
    name:          String,
    description:   String,
    subCategories: List[FullNodeGroupCategory],
    targetInfos:   List[FullRuleTargetInfo],
    isSystem:      Boolean,
    security:      Option[SecurityTag]
) {

  def toNodeGroupCategory: NodeGroupCategory = NodeGroupCategory(
    id = id,
    name = name,
    description = description,
    children = subCategories.map(_.id),
    items = targetInfos.map(_.toTargetInfo),
    isSystem = isSystem,
    security = security
  )

  /**
   * Get the list of categories, starting by that one,
   * and with children sorted with the given ordering.
   * So we get:
   * cat1
   *  - cat1.1
   *     - cat1.1.1
   *     - cat1.1.2
   *  - cat1.2
   *     - cat1.2.1
   *     etc.
   *
   * Some categories AND ALL THERE SUBCATEGORIES can be
   * excluded with the "exclude" predicate is true.
   */
  def getSortedCategories(
      ordering: (FullNodeGroupCategory, FullNodeGroupCategory) => Boolean,
      exclude:  FullNodeGroupCategory => Boolean
  ): List[(List[NodeGroupCategoryId], FullNodeGroupCategory)] = {

    if (exclude(this)) {
      Nil
    } else {
      val subCats = for {
        directSubCat    <- subCategories.sortWith(ordering)
        (subId, subCat) <- directSubCat.getSortedCategories(ordering, exclude)
      } yield {
        (id :: subId, subCat)
      }

      (List(id) -> this) :: subCats
    }
  }

  val ownGroups: Map[NodeGroupId, FullGroupTarget] = targetInfos.collect {
    case FullRuleTargetInfo(g: FullGroupTarget, _, _, _, _, _) => (g.nodeGroup.id, g)
  }.toMap

  val allGroups: Map[NodeGroupId, FullGroupTarget] = (
    ownGroups ++ subCategories.flatMap(_.allGroups)
  ).toMap

  val categoryByGroupId: Map[NodeGroupId, NodeGroupCategoryId] = (
    ownGroups.map { case (gid, _) => (gid, id) } ++ subCategories.flatMap(_.categoryByGroupId)
  ).toMap

  val allCategories: Map[NodeGroupCategoryId, FullNodeGroupCategory] = {
    subCategories.flatMap(_.allCategories) :+ (id -> this)
  }.toMap

  // A Map that allow you to get directly the parent category of a category
  // This will return you the id of the root category for the root category
  val parentCategories: Map[NodeGroupCategoryId, FullNodeGroupCategory] = {
    val map = {
      subCategories.map((_.id -> this)) ++ subCategories.flatMap(_.parentCategories)
    }.toMap
    map.withDefaultValue(this)
  }

  val allTargets: Map[RuleTarget, FullRuleTargetInfo] = (
    targetInfos.map(t => (t.target.target, t)).toMap ++ subCategories.flatMap(_.allTargets)
  )

  /**
   * Return all node ids that match the set of target.
   */
  def getNodeIds(targets: Set[RuleTarget], arePolicyServers: Map[NodeId, Boolean]): Set[NodeId] = {
    val groups = allGroups.view.mapValues(_.nodeGroup.serverList.toSet)

    RuleTarget.getNodeIds(targets, arePolicyServers, groups.toMap)
  }

  /**
   * Given a nodeId, get all the groups where it belongs to.
   */
  def getTarget(node: CoreNodeFact): Map[RuleTarget, FullRuleTargetInfo] = {
    allTargets.filter {
      case (t, info) =>
        info.target match {
          case FullGroupTarget(target, group) => group.serverList.contains(node.id)
          case FullCompositeRuleTarget(t)     =>
            // here, on choice but to calculate the list of nodes and see if it is in the result
            // here, we don't need all node info, just the current node
            // It's because we only do set analysis on node info, not things like "find all
            // the node with that policy server" in target.
            getNodeIds(Set(t), Map(node.id -> node.rudderSettings.isPolicyServer)).contains(node.id)
          case FullOtherTarget(t)             =>
            t match {
              case AllTarget                    => true
              case AllTargetExceptPolicyServers => !node.rudderSettings.isPolicyServer
              case AllPolicyServers             => node.rudderSettings.isPolicyServer
              case PolicyServerTarget(id)       => id == node.id
            }
        }
    }
  }

  /**
   * Given a nodeId, get all the strict groups targets where it belongs to.
   */
  def getGroupTarget(node: CoreNodeFact): Map[RuleTarget, FullGroupTarget] = {
    allTargets.collect {
      case (t, FullRuleTargetInfo(groupTarget: FullGroupTarget, _, _, _, _, _))
          if groupTarget.nodeGroup.serverList.contains(node.id) =>
        t -> groupTarget
    }
  }

}

trait RoNodeGroupRepository {

  /**
   * Get the full group tree with all information
   * for categories and groups.
   * Returns the objects sorted by name within
   */
  def getFullGroupLibrary()(implicit qc: QueryContext): IOResult[FullNodeGroupCategory]

  def categoryExists(id: NodeGroupCategoryId): IOResult[Boolean]

  /**
   * Get a server group by its id. Fail if not present.
   * @param id
   * @return
   */
  def getNodeGroup(id: NodeGroupId)(implicit
      qc: QueryContext
  ): IOResult[(NodeGroup, NodeGroupCategoryId)] = {
    getNodeGroupOpt(id).notOptional(s"Group with id '${id.serialize}' was not found'")
  }

  /**
   * Get the node group corresponding to that ID, or None if none were found.
   */
  def getNodeGroupOpt(id: NodeGroupId)(implicit
      qc: QueryContext
  ): IOResult[Option[(NodeGroup, NodeGroupCategoryId)]]

  /**
   * Fetch the parent category of the NodeGroup
   * Caution, its a lightweight version of the entry (no children nor item)
   * @param id
   * @return
   */
  def getNodeGroupCategory(id: NodeGroupId): IOResult[NodeGroupCategory]

  /**
   * Get all node groups defined in that repository
   */
  // TODO: add QC
  def getAll(): IOResult[Seq[NodeGroup]]

  /**
   * Get all node groups by ids
   */
  def getAllByIds(ids: Seq[NodeGroupId]): IOResult[Seq[NodeGroup]]

  /**
   * Get all the node group id and the set of ndoes within
   * Goal is to be more efficient
   */
  def getAllNodeIds(): IOResult[Map[NodeGroupId, Set[NodeId]]]

  /**
   * Get all the node group id and the set of ndoes within
   * Goal is to be more efficient
   */
  def getAllNodeIdsChunk(): IOResult[Map[NodeGroupId, Chunk[NodeId]]]

  /**
   * Get all pairs of (category details, Set(node groups) )
   * in a map in which keys are the parent category of the groups.
   * The map is sorted by category:
   *
   *   "/"           -> [/_details, Set(G1, G2)]
   *   "/cat1"       -> [cat1_details, Set(G3)]
   *   "/cat1/cat11" -> [/cat1/cat11_details, Set(G4)]
   *   "/cat2"       -> [/cat2_details, Set(G5)]
   *   ...    *
   *
   */
  def getGroupsByCategory(includeSystem: Boolean = false)(implicit
      qc: QueryContext
  ): IOResult[SortedMap[List[NodeGroupCategoryId], CategoryAndNodeGroup]]

  /**
   * Retrieve all groups that have at least one of the given
   * node ID in there member list.
   * @param nodeIds
   * @return
   */
  def findGroupWithAnyMember(nodeIds: Seq[NodeId]): IOResult[Seq[NodeGroupId]]

  /**
   * Retrieve all groups that have ALL given node ID in their
   * member list.
   * @param nodeIds
   * @return
   */
  def findGroupWithAllMember(nodeIds: Seq[NodeId]): IOResult[Seq[NodeGroupId]]

  /**
   * Root group category
   */
  def getRootCategory(): NodeGroupCategory

  def getRootCategoryPure(): IOResult[NodeGroupCategory]

  /**
   * Get all pairs of (categoryid, category)
   * in a map in which keys are the parent category of the
   * the template. The map is sorted by categories:
   * SortedMap {
   *   "/"           -> [root]
   *   "/cat1"       -> [cat1_details]
   *   "/cat1/cat11" -> [/cat1/cat11]
   *   "/cat2"       -> [/cat2_details]
   *   ...
   */
  def getCategoryHierarchy: IOResult[SortedMap[List[NodeGroupCategoryId], NodeGroupCategory]]

  /**
   * Return all categories
   * @return
   */
  def getAllGroupCategories(includeSystem: Boolean = false): IOResult[Seq[NodeGroupCategory]]

  /**
   * Get a group category by its id
   * @param id
   * @return
   */
  def getGroupCategory(id: NodeGroupCategoryId): IOResult[NodeGroupCategory]

  /**
   * Get the direct parent of the given category.
   * Fails if the category is not in the repository or for root category
   */
  def getParentGroupCategory(id: NodeGroupCategoryId): IOResult[NodeGroupCategory]

  /**
   * Return the list of parents for that category, the nearest parent
   * first, until the root of the library.
   * The last parent is not the root of the library, return a Failure.
   * Also return a failure if the path to top is broken in any way.
   */
  def getParents_NodeGroupCategory(id: NodeGroupCategoryId): IOResult[List[NodeGroupCategory]]

  /**
   * Returns all non system categories + the root category
   * Caution, they are "lightweight" group categories (no children)
   */
  def getAllNonSystemCategories(): IOResult[Seq[NodeGroupCategory]]

}

object RoNodeGroupRepository {

  /**
   * Return all node ids that match the set of target.
   */
  def getNodeIds(
      allGroups:    Map[NodeGroupId, Set[NodeId]],
      targets:      Set[RuleTarget],
      allNodeFacts: MapView[NodeId, CoreNodeFact]
  ): Set[NodeId] = {
    val allNodes = allNodeFacts.mapValues(x => (x.rudderSettings.isPolicyServer)).toMap
    RuleTarget.getNodeIds(targets, allNodes, allGroups)
  }

  def getNodeIdsChunk(
      allGroups:        Map[NodeGroupId, Chunk[NodeId]],
      targets:          Set[RuleTarget],
      arePolicyServers: MapView[NodeId, Boolean]
  ): Chunk[NodeId] = {
    RuleTarget.getNodeIdsChunk(targets, arePolicyServers, allGroups)
  }
}

trait WoNodeGroupRepository {
  //// write operations ////

  /**
   * Add a server group into the a parent category
   * Fails if the parent category does not exist
   * The id provided by the nodeGroup will  be used to save it inside the repository
   * return the newly created server group
   */
  def create(group: NodeGroup, into: NodeGroupCategoryId)(implicit cc: ChangeContext): IOResult[AddNodeGroupDiff]

  /**
   * Used in relay-server plugin.
   */
  def createPolicyServerTarget(target: PolicyServerTarget)(implicit cc: ChangeContext): IOResult[LDIFChangeRecord]

  /**
   * Update the given existing group
   * That method does nothing at the configuration level,
   * so you will have to manage rule deployment
   * if needed
   *
   * System group can not be updated with that method.
   */
  def update(group: NodeGroup)(implicit cc: ChangeContext): IOResult[Option[ModifyNodeGroupDiff]]

  /**
   * Only add / remove some nodes in an atomic way from the group
   */
  def updateDiffNodes(group: NodeGroupId, add: List[NodeId], delete: List[NodeId])(implicit
      cc: ChangeContext
  ): IOResult[Option[ModifyNodeGroupDiff]]

  /**
   * Update the given existing system group
   */
  def updateSystemGroup(group: NodeGroup)(implicit cc: ChangeContext): IOResult[Option[ModifyNodeGroupDiff]]

  /**
   * Update the given existing dynamic group, and only the node list
   * part (but the group can be system or not).
   *
   * If anything else than the node list changed compared to the
   * group given in parameter in the backend, an error is raised.
   *
   * That method does nothing at the configuration level,
   * so you will have to manage rule deployment
   * if needed
   */
  def updateDynGroupNodes(group: NodeGroup)(implicit cc: ChangeContext): IOResult[Option[ModifyNodeGroupDiff]]

  /**
   * Move the given existing group to the new container.
   *
   * That *only* move the group, and don't modify anything
   * else. You will have to use udate/updateSystemGroup for
   * modification.
   *
   * That method does nothing at the configuration level,
   * so you will have to manage rule deployment
   * if needed
   */
  def move(group: NodeGroupId, containerId: NodeGroupCategoryId)(implicit
      cc: ChangeContext
  ): IOResult[Option[ModifyNodeGroupDiff]]

  /**
   * Delete the given nodeGroup.
   * If no nodegroup has such id in the directory, return a success.
   * @param id
   * @return
   */
  def delete(id: NodeGroupId)(implicit cc: ChangeContext): IOResult[DeleteNodeGroupDiff]

  /**
   * Delete the given policyServerTarget.
   * If no policyServerTarget has such id in the directory, return a success.
   * Used in ScaleOutRelay plugin.
   */
  def deletePolicyServerTarget(policyServer: PolicyServerTarget)(implicit cc: ChangeContext): IOResult[PolicyServerTarget]

  /**
   * Add that group category into the given parent category
   * Fails if the parent category does not exist or
   * if it already contains that category.
   *
   * return the new category.
   */
  def addGroupCategoryToCategory(that: NodeGroupCategory, into: NodeGroupCategoryId)(implicit
      cc: ChangeContext
  ): IOResult[NodeGroupCategory]

  /**
   * Update an existing group category
   */
  def saveGroupCategory(category: NodeGroupCategory)(implicit cc: ChangeContext): IOResult[NodeGroupCategory]

  /**
    * Update/move an existing group category
    */
  def saveGroupCategory(category: NodeGroupCategory, containerId: NodeGroupCategoryId)(implicit
      cc: ChangeContext
  ): IOResult[NodeGroupCategory]

  /**
   * Delete the category with the given id.
   * If no category with such id exists, it is a success.
   * If `checkEmpty` is set to true, the deletion may be done only if
   * the category is empty.
   * If `checkEmpty` is set to false, category and children are deleted.
   * A category can be deleted only if its security context and the one of sub-items
   * is compatible with the one given in `cc`.
   */
  def delete(id: NodeGroupCategoryId, checkEmpty: Boolean = true)(implicit cc: ChangeContext): IOResult[NodeGroupCategoryId]

}
