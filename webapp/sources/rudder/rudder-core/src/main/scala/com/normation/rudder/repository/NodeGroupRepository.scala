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

import com.normation.rudder.domain.nodes._
import com.normation.inventory.domain.NodeId
import net.liftweb.common._
import com.normation.eventlog.EventActor
import com.normation.utils.HashcodeCaching
import com.normation.eventlog.ModificationId
import com.normation.rudder.domain.nodes._
import net.liftweb.common._
import com.normation.inventory.domain.NodeId
import com.normation.utils.Utils
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import scala.collection.immutable.SortedMap
import com.normation.rudder.domain.policies._

/**
 * Here is the ordering for a List[NodeGroupCategoryId]
 * MUST start by the root !
 */
object GroupCategoryRepositoryOrdering extends Ordering[List[NodeGroupCategoryId]] {
  type ID = NodeGroupCategoryId
  override def compare(x:List[ID],y:List[ID]) = {
    Utils.recTreeStringOrderingCompare(x.map( _.value ), y.map( _.value ))

  }
}

/**
 * Here is the ordering for a List[NodeGroupCategoryId]
 * MUST start by the root !
 */
object NodeGroupCategoryOrdering extends Ordering[List[NodeGroupCategoryId]] {
  type ID = NodeGroupCategoryId
  override def compare(x:List[ID],y:List[ID]) = {
    Utils.recTreeStringOrderingCompare(x.map( _.value ), y.map( _.value ))
  }
}


/**
 * A simple container for a category
 * and its direct children ActiveTechniques
 */
final case class CategoryAndNodeGroup(
    category: NodeGroupCategory
  , groups  : Set[NodeGroup]
) extends HashcodeCaching


final case class FullNodeGroupCategory(
    id           : NodeGroupCategoryId
  , name         : String
  , description  : String
  , subCategories: List[FullNodeGroupCategory]
  , targetInfos  : List[FullRuleTargetInfo]
  , isSystem     : Boolean = false
) extends Loggable with HashcodeCaching {

  def toNodeGroupCategory = NodeGroupCategory(
      id = id
    , name = name
    , description = description
    , children = subCategories.map( _.id )
    , items = targetInfos.map( _.toTargetInfo )
    , isSystem = isSystem
  )

  /**
   * Get the list of categories, starting by that one,
   * and with chlidren sorted with the given ordering.
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
   * exclude with the "exclude" predicat is true.
   */
  def getSortedCategories(
      ordering: (FullNodeGroupCategory, FullNodeGroupCategory) => Boolean
    , exclude: FullNodeGroupCategory => Boolean
  ) : List[(List[NodeGroupCategoryId], FullNodeGroupCategory)] = {

    if(exclude(this)){
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

  val ownGroups = targetInfos.collect {
        case FullRuleTargetInfo(g:FullGroupTarget, _, _, _, _) => (g.nodeGroup.id, g)
      }.toMap

  val allGroups: Map[NodeGroupId, FullGroupTarget] = (
      ownGroups ++ subCategories.flatMap( _.allGroups )
  ).toMap

  val categoryByGroupId: Map[NodeGroupId, NodeGroupCategoryId] = (
      ownGroups.map { case (gid, _) => (gid, id)} ++ subCategories.flatMap( _.categoryByGroupId)
  ).toMap

  val allCategories: Map[NodeGroupCategoryId, FullNodeGroupCategory] = {
      subCategories.flatMap( _.allCategories ) :+ (id -> this)
  }.toMap

  // A Map that allow you to get directly the parent category of a category
  // This will return you the id of the root category for the root category
  val parentCategories: Map[NodeGroupCategoryId, FullNodeGroupCategory] = {
    val map ={
      subCategories.map((_.id -> this)) ++ subCategories.flatMap(_.parentCategories)
    }.toMap
    map.withDefaultValue(this)
  }


  val allTargets: Map[RuleTarget, FullRuleTargetInfo] = (
      targetInfos.map(t => (t.target.target, t)).toMap ++ subCategories.flatMap( _.allTargets)
  )


  /**
   * Return all node ids that match the set of target.
   */
  def getNodeIds(targets: Set[RuleTarget], allNodeInfos: Map[NodeId, NodeInfo]) : Set[NodeId] = {
    val allNodes = allNodeInfos.mapValues { x => (x.isPolicyServer, x.serverRoles) }
    val groups = allGroups.mapValues { _.nodeGroup.serverList.toSet }

    RuleTarget.getNodeIds(targets, allNodes, groups)
  }

  /**
   * Given a nodeId, get all the groups where it belongs to.
   */
  def getTarget(node: NodeInfo): Map[RuleTarget, FullRuleTargetInfo] = {
    allTargets.filter { case(t, info) => info.target match {
      case FullGroupTarget(target, group) => group.serverList.contains(node.id)
      case FullCompositeRuleTarget(t) =>
        //here, on choice but to calculate the list of nodes and see if it is in the result
        //here, we don't need all node info, just the current node
        //It's because we only do set analysis on node info, not things like "find all
        //the node with that policy server" in target.
        getNodeIds(Set(t), Map(node.id -> node)).contains(node.id)
      case FullOtherTarget(t) => t match {
        case AllTarget => true
        case AllTargetExceptPolicyServers => !node.isPolicyServer
        case AllServersWithRole => node.serverRoles.nonEmpty
        case AllNodesWithoutRole => node.serverRoles.isEmpty
        case PolicyServerTarget(id) => id == node.id
      }
    } }
  }

}

trait RoNodeGroupRepository {

  /**
   * Get the full group tree with all information
   * for categories and groups.
   * Returns the objects sorted by name within
   */
  def getFullGroupLibrary(): Box[FullNodeGroupCategory]

  /**
   * Get a server group by its id
   * @param id
   * @return
   */
  def getNodeGroup(id: NodeGroupId) : Box[(NodeGroup,NodeGroupCategoryId)]


  /**
   * Fetch the parent category of the NodeGroup
   * Caution, its a lightweight version of the entry (no children nor item)
   * @param id
   * @return
   */
  def getParentGroupCategory(id: NodeGroupId): Box[NodeGroupCategory]

  /**
   * Get all node groups defined in that repository
   */
  def getAll() : Box[Seq[NodeGroup]]

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
  def getGroupsByCategory(includeSystem:Boolean = false) : Box[SortedMap[List[NodeGroupCategoryId], CategoryAndNodeGroup]]

  /**
   * Retrieve all groups that have at least one of the given
   * node ID in there member list.
   * @param nodeIds
   * @return
   */
  def findGroupWithAnyMember(nodeIds:Seq[NodeId]) : Box[Seq[NodeGroupId]]

  /**
   * Retrieve all groups that have ALL given node ID in their
   * member list.
   * @param nodeIds
   * @return
   */
  def findGroupWithAllMember(nodeIds:Seq[NodeId]) : Box[Seq[NodeGroupId]]


  /**
   * Root group category
   */
  def getRootCategory() : NodeGroupCategory

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
  def getCategoryHierarchy : Box[SortedMap[List[NodeGroupCategoryId], NodeGroupCategory]]

  /**
   * Return all categories
   * @return
   */
  def getAllGroupCategories(includeSystem: Boolean = false) : Box[List[NodeGroupCategory]]

  /**
   * Get a group category by its id
   * @param id
   * @return
   */
  def getGroupCategory(id: NodeGroupCategoryId) : Box[NodeGroupCategory]

  /**
   * Get the direct parent of the given category.
   * Return empty for root of the hierarchy, fails if the category
   * is not in the repository
   */
  def getParentGroupCategory(id:NodeGroupCategoryId) : Box[NodeGroupCategory]

  /**
   * Return the list of parents for that category, the nearest parent
   * first, until the root of the library.
   * The the last parent is not the root of the library, return a Failure.
   * Also return a failure if the path to top is broken in any way.
   */
  def getParents_NodeGroupCategory(id:NodeGroupCategoryId) : Box[List[NodeGroupCategory]]

  /**
   * Returns all non system categories + the root category
   * Caution, they are "lightweight" group categories (no children)
   */
  def getAllNonSystemCategories() : Box[Seq[NodeGroupCategory]]

}

trait WoNodeGroupRepository {
  //// write operations ////


  /**
   * Add a server group into the a parent category
   * Fails if the parent category does not exist
   * The id provided by the nodeGroup will  be used to save it inside the repository
   * return the newly created server group
   */
  def create(group: NodeGroup, into: NodeGroupCategoryId, modId: ModificationId, actor:EventActor, why: Option[String]): Box[AddNodeGroupDiff]


  /**
   * Update the given existing group
   * That method does nothing at the configuration level,
   * so you will have to manage rule deployment
   * if needed
   *
   * System group can not be updated with that method.
   */
  def update(group:NodeGroup, modId: ModificationId, actor:EventActor, whyDescription:Option[String]) : Box[Option[ModifyNodeGroupDiff]]

  /**
   * Update the given existing system group
   */
  def updateSystemGroup(group:NodeGroup, modId: ModificationId, actor:EventActor, reason:Option[String]) : Box[Option[ModifyNodeGroupDiff]]

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
  def updateDynGroupNodes(group:NodeGroup, modId: ModificationId, actor:EventActor, whyDescription:Option[String]) : Box[Option[ModifyNodeGroupDiff]]

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
  def move(group:NodeGroupId, containerId : NodeGroupCategoryId, modId: ModificationId, actor:EventActor, whyDescription:Option[String]) : Box[Option[ModifyNodeGroupDiff]]

  /**
   * Delete the given nodeGroup.
   * If no nodegroup has such id in the directory, return a success.
   * @param id
   * @return
   */
  def delete(id:NodeGroupId, modId: ModificationId, actor:EventActor, whyDescription:Option[String]) : Box[DeleteNodeGroupDiff]

  /**
   * Add that group category into the given parent category
   * Fails if the parent category does not exist or
   * if it already contains that category.
   *
   * return the new category.
   */
  def addGroupCategorytoCategory(
      that:NodeGroupCategory
    , into:NodeGroupCategoryId //parent category
    , modificationId: ModificationId
    , actor:EventActor, reason: Option[String]
  ) : Box[NodeGroupCategory]

  /**
   * Update an existing group category
   */
  def saveGroupCategory(category:NodeGroupCategory, modificationId: ModificationId, actor:EventActor, reason: Option[String]) : Box[NodeGroupCategory]

  /**
    * Update/move an existing group category
    */
   def saveGroupCategory(category: NodeGroupCategory, containerId : NodeGroupCategoryId, modificationId: ModificationId, actor:EventActor, reason: Option[String]): Box[NodeGroupCategory]

  /**
   * Delete the category with the given id.
   * If no category with such id exists, it is a success.
   * If checkEmtpy is set to true, the deletion may be done only if
   * the category is empty (else, category and children are deleted).
   * @param id
   * @param checkEmtpy
   * @return
   *  - Full(category id) for a success
   *  - Failure(with error message) iif an error happened.
   */
  def delete(id:NodeGroupCategoryId, modificationId: ModificationId, actor:EventActor, reason: Option[String], checkEmpty:Boolean = true) : Box[NodeGroupCategoryId]

}
