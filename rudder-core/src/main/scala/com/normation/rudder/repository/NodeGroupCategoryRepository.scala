/*
*************************************************************************************
* Copyright 2011 Normation SAS
*************************************************************************************
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Affero General Public License as
* published by the Free Software Foundation, either version 3 of the
* License, or (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU Affero GPL v3, the copyright holders add the following
* Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU Affero GPL v3
* licence, when you create a Related Module, this Related Module is
* not considered as a part of the work and may be distributed under the
* license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Affero General Public License for more details.
*
* You should have received a copy of the GNU Affero General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/agpl.html>.
*
*************************************************************************************
*/

package com.normation.rudder.repository

import com.normation.rudder.domain.nodes._
import net.liftweb.common._
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies.RuleTarget
import com.normation.utils.Utils
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import scala.collection.immutable.SortedMap

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

trait NodeGroupCategoryRepository {

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
   * retrieve the hierarchy of group category/group containing the selected node
   * From a category id (should start from root) return Empty if no children nor items contains the targets, Full(category) otherwise, with both
   * target and children filtered.
   * Probably suboptimal
   */
  def findGroupHierarchy(categoryId : NodeGroupCategoryId, targets : Seq[RuleTarget])  : Box[NodeGroupCategory]


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
   * Add that group categoy into the given parent category
   * Fails if the parent category does not exists or
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