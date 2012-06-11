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
import com.normation.cfclerk.domain.TechniqueName
import net.liftweb.common._
import com.normation.cfclerk.domain.TechniqueVersion
import org.joda.time.DateTime
import scala.collection.SortedMap
import com.normation.utils.HashcodeCaching
import com.normation.eventlog.EventActor

/**
 * A simple container for a category 
 * and its direct children ActiveTechniques
 */
final case class CategoryWithActiveTechniques(
    category : ActiveTechniqueCategory
  , templates: Set[ActiveTechnique]
) extends HashcodeCaching 


/**
 * Define action on active technique with the
 * back-end : save them, retrieve them, etc. 
 */
trait ActiveTechniqueRepository {

  /**
   * Get all pairs of (category details, Set(active technique))
   * in a map in which keys are the parent category of the
   * the template. The map is sorted by categories:
   * SortedMap {
   *   "/"           -> [/_details, Set(UPT1, UPT2)]
   *   "/cat1"       -> [cat1_details, Set(UPT3)]
   *   "/cat1/cat11" -> [/cat1/cat11_details, Set(UPT4)]
   *   "/cat2"       -> [/cat2_details, Set(UPT5)]
   *   ... 
   */
  def getActiveTechniqueByCategory(includeSystem:Boolean = false) : Box[SortedMap[List[ActiveTechniqueCategoryId], CategoryWithActiveTechniques]]
    
  /**
   * Find back an active technique thanks to its id. 
   * Return Empty if the active technique is not found, 
   * Fails on error.
   */
  def getActiveTechnique(id:ActiveTechniqueId) : Box[ActiveTechnique]
  
  
  /**
   * Find back an active technique thanks to the id of its referenced
   * Technique.
   * Return Empty if the active technique is not found, 
   * Fails on error.
   */
  def getActiveTechnique(techniqueName:TechniqueName) : Box[ActiveTechnique]
  
  
  /**
   * Create a active technique from the parameter WBTechnique
   * and add it in the given ActiveTechniqueCategory
   * 
   * Returned the freshly created ActiveTechnique
   * 
   * Fails if 
   *   - the active technique id refer to no technique, 
   *   - the category id does not exists,
   *   - the technique is already in the active technique 
   *     library
   */
  def addTechniqueInUserLibrary(
      categoryId   : ActiveTechniqueCategoryId
    , techniqueName: TechniqueName
    , versions     : Seq[TechniqueVersion]
    , actor        : EventActor, reason: Option[String]
  ) : Box[ActiveTechnique] 

  
  /**
   * Move an active technique to a new category.
   * Failure if the given active technique or category
   * does not exists. 
   * 
   */
  def move(id:ActiveTechniqueId, newCategoryId:ActiveTechniqueCategoryId, actor: EventActor, reason: Option[String]) : Box[ActiveTechniqueId] 
  
  /**
   * Set the status of the active technique to the new value
   */
  def changeStatus(id:ActiveTechniqueId, status:Boolean, actor: EventActor, reason: Option[String]) : Box[ActiveTechniqueId] 
  
  /**
   * Add new (version,acceptation datetime) to existing 
   * acceptation datetimes by the new one.
   * 
   * Return empty if the active technique not in the repos,
   * Failure if an error happened, 
   * Full(id) when success
   */
  def setAcceptationDatetimes(id:ActiveTechniqueId, datetimes: Map[TechniqueVersion,DateTime], actor: EventActor, reason: Option[String]) : Box[ActiveTechniqueId]
  
  /**
   * Delete the active technique in the active tehcnique library.
   * If no such element exists, it is a success.
   */
  def delete(id:ActiveTechniqueId, actor: EventActor, reason: Option[String]) : Box[ActiveTechniqueId] 
  
  /**
   * Retrieve the list of parents for the given active technique, 
   * till the root of policy library.
   * Return empty if the path can not be build
   * (missing technique, missing category, etc)
   */
  def activeTechniqueBreadCrump(id:ActiveTechniqueId) : Box[List[ActiveTechniqueCategory]]
  
}