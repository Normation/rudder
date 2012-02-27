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

import com.normation.rudder.domain.policies._
import net.liftweb.common._
import com.normation.utils.Utils
import com.normation.eventlog.EventActor




/**
 * Here is the ordering for a List[ActiveTechniqueCategoryId]
 * MUST start by the root !
 */
object ActiveTechniqueCategoryOrdering extends Ordering[List[ActiveTechniqueCategoryId]] {
  type ID = ActiveTechniqueCategoryId
  override def compare(x:List[ID],y:List[ID]) = {
    Utils.recTreeStringOrderingCompare(x.map( _.value ), y.map( _.value ))
  }
}

/**
 * 
 * Several repositories, more like simple DAOs, to manage category like
 * structures (in LDAP, things like "ou")
 *
 */
trait ActiveTechniqueCategoryRepository {

  /**
   * Root user categories
   */
  def getActiveTechniqueLibrary : ActiveTechniqueCategory

  /**
   * Return all categories non system (lightweight version, with no children)
   * @return
   */
  def getAllActiveTechniqueCategories(includeSystem:Boolean = false) : Box[Seq[ActiveTechniqueCategory]]


  /**
   * Get an user policy template by its ID
   */
  def getActiveTechniqueCategory(id:ActiveTechniqueCategoryId) : Box[ActiveTechniqueCategory]
  
  
  /**
   * Add the given categoy into the given parent category in the
   * user library. 
   * Fails if the parent category does not exists in user lib or
   * if it already contains that category, or a category of the
   * same name (name must be unique for a given level)
   * 
   * return the modified parent category. 
   */
  def addActiveTechniqueCategory(
      that : ActiveTechniqueCategory
    , into : ActiveTechniqueCategory //parent category
    , actor: EventActor
  ) : Box[ActiveTechniqueCategory] 
  
  /**
   * Update an existing policy template category
   * Fail if the parent already contains a category of the
   * same name (name must be unique for a given level)
   */
  def saveActiveTechniqueCategory(category:ActiveTechniqueCategory, actor: EventActor) : Box[ActiveTechniqueCategory]  
  
  /**
   * Get the direct parent of the given category.
   * Return empty for root of the hierarchy, fails if the category
   * is not in the repository
   */
  def getParentActiveTechniqueCategory(id:ActiveTechniqueCategoryId) : Box[ActiveTechniqueCategory]
  
  /**
   * Return the list of parents for that category, the nearest parent
   * first, until the root of the library.
   * The the last parent is not the root of the library, return a Failure.
   * Also return a failure if the path to top is broken in any way.
   */
  def getParentsForActiveTechniqueCategory(id:ActiveTechniqueCategoryId) : Box[List[ActiveTechniqueCategory]] 
  
  def getParentsForActiveTechnique(id:ActiveTechniqueId) : Box[ActiveTechniqueCategory]
  
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
  def delete(id:ActiveTechniqueCategoryId, actor: EventActor, checkEmpty:Boolean = true) : Box[ActiveTechniqueCategoryId]
  
  /**
   * Move an existing category into a new one.
   * Both category to move and destination have to exists, else it is a failure.
   * The destination category can not be a child of the category to move. 
   * Fail if the parent already contains a category of the
   * same name (name must be unique for a given level)
   */
  def move(categoryId:ActiveTechniqueCategoryId, intoParent:ActiveTechniqueCategoryId, actor: EventActor) : Box[ActiveTechniqueCategoryId]
  
  /**
   * Return true if at least one directive exists in this category (or a sub category 
   * of this category) 
   */
  def containsDirective(id: ActiveTechniqueCategoryId) : Boolean
}