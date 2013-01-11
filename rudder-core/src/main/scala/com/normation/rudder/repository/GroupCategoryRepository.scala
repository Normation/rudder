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
import com.normation.rudder.domain.policies.PolicyInstanceTarget

trait GroupCategoryRepository {

	/**
   * Root group category
   */
  def getRootCategory() : NodeGroupCategory

  /**
   * Get the category hierarchy
   * Returns a Seq of NodeGroupCategoryId, String
   * The String are in the form
   * Root of the group and group categories
   * └─Server roles
   *   └─Server role child
   * The Seq if ordered by Category name name at each level
   */
  def getCategoryHierarchy() : Seq[(NodeGroupCategoryId, String)]
  
  /**
   * retrieve the hierarchy of group category/group containing the selected node
   * From a category id (should start from root) return Empty if no children nor items contains the targets, Full(category) otherwise, with both
   * target and children filtered.
   * Probably suboptimal
   */
  def findGroupHierarchy(categoryId : NodeGroupCategoryId, targets : Seq[PolicyInstanceTarget])  : Box[NodeGroupCategory]


  /**
   * Return all categories
   * @return
   */
  def getAllGroupCategories() : Box[List[NodeGroupCategory]]
  
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
      that:NodeGroupCategory,
      into:NodeGroupCategoryId //parent category
  ) : Box[NodeGroupCategory] 
  
  /**
   * Update an existing group category
   */
  def saveGroupCategory(category:NodeGroupCategory) : Box[NodeGroupCategory]  

  /**
    * Update/move an existing group category
    */
   def saveGroupCategory(category: NodeGroupCategory, containerId : NodeGroupCategoryId): Box[NodeGroupCategory]


  /**
   * Get the direct parent of the given category.
   * Return empty for root of the hierarchy, fails if the category
   * is not in the repository
   */
  def getParentGroupCategory(id:NodeGroupCategoryId) : Box[NodeGroupCategory]


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
  def delete(id:NodeGroupCategoryId, checkEmpty:Boolean = true) : Box[NodeGroupCategoryId]
  
}