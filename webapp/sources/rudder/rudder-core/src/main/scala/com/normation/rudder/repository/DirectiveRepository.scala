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

import com.normation.rudder.domain.policies._
import net.liftweb.common._
import com.normation.eventlog.EventActor
import com.normation.cfclerk.domain.Technique
import com.normation.eventlog.ModificationId
import com.normation.rudder.domain.policies._
import com.normation.cfclerk.domain.TechniqueName
import net.liftweb.common._
import com.normation.cfclerk.domain.TechniqueVersion
import org.joda.time.DateTime
import scala.collection.SortedMap
import com.normation.utils.HashcodeCaching
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.rudder.domain.policies._
import net.liftweb.common._
import com.normation.utils.Utils
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.cfclerk.domain.TechniqueId
import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.domain.TechniqueId

import com.normation.errors._


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
 * A simple container for a category
 * and its direct children ActiveTechniques
 */
final case class CategoryWithActiveTechniques(
    category : ActiveTechniqueCategory
  , templates: Set[ActiveTechnique]
) extends HashcodeCaching


/**
 * Note that we can have a different set of
 * Technique versions in techniquesDateTime
 * and in "techniques"
 */
final case class FullActiveTechnique(
    id                  : ActiveTechniqueId
  , techniqueName       : TechniqueName
  , acceptationDatetimes: SortedMap[TechniqueVersion, DateTime]
  , techniques          : SortedMap[TechniqueVersion, Technique]
  , directives          : List[Directive]
  , isEnabled           : Boolean = true
  , isSystem            : Boolean = false
) {
  def toActiveTechnique() = ActiveTechnique(
      id = id
    , techniqueName = techniqueName
    , acceptationDatetimes = acceptationDatetimes.toMap
    , directives = directives.map( _.id )
    , _isEnabled = isEnabled
    , isSystem = isSystem
  )

  val newestAvailableTechnique = techniques.toSeq.sortBy( _._1).reverse.map( _._2 ).headOption
}


final case class FullActiveTechniqueCategory(
    id              : ActiveTechniqueCategoryId
  , name            : String
  , description     : String
  , subCategories   : List[FullActiveTechniqueCategory]
  , activeTechniques: List[FullActiveTechnique]
  , isSystem        : Boolean = false // by default, we can't create system Category
) {

  val allDirectives : Map[DirectiveId, (FullActiveTechnique, Directive)] = (
       subCategories.flatMap( _.allDirectives).toMap
    ++ activeTechniques.flatMap( at => at.directives.map( d => (d.id, (at, d))) ).toMap
  )

  val allDirectivesByActiveTechniques : Map[ActiveTechniqueId, List[Directive]] = (
      subCategories.flatMap( _.allDirectivesByActiveTechniques ).toMap
   ++ activeTechniques.map( at => (at.id, at.directives)).toMap
  )

  val allActiveTechniques : Map[ActiveTechniqueId, FullActiveTechnique] = (
      subCategories.flatMap( _.allActiveTechniques).toMap
   ++ activeTechniques.map( at => (at.id, at)).toMap
  )

  val allActiveTechniquesByCategories : Map[ActiveTechniqueCategoryId, List[FullActiveTechnique]] = (
      subCategories.flatMap( _.allActiveTechniquesByCategories ).toMap
    + (id -> activeTechniques)
  )

  val allTechniques: Map[TechniqueId, (Technique, Option[DateTime])] = {
    allActiveTechniques.flatMap { case (_, at) =>  (at.techniques.map { case(version, technique) =>
      (TechniqueId(at.techniqueName, version) -> ((technique, at.acceptationDatetimes.get(version))))
    }) }
  }

  def getUpdateDateTime(id: TechniqueId): Option[DateTime] = {
    allTechniques.get(id).flatMap( _._2 )
  }
}


/**
 * The directive repository.
 *
 * directive are instance of technique
 * (a technique + values for its parameters)
 *
 */
trait RoDirectiveRepository {

  /**
   * Get the full directive library with all information,
   * from techniques to directives
   */
  def getFullDirectiveLibrary() : IOResult[FullActiveTechniqueCategory]

  /**
   * Try to find the directive with the given ID.
   * Empty: no directive with such ID
   * Full((parent,directive)) : found the directive (directive.id == directiveId) in given parent
   * Failure => an error happened.
   */
  def getDirective(directiveId:DirectiveId) : IOResult[Option[Directive]]

  /**
   * retrieve a Directive with its parent Technique and the
   * binding Active Technique
   */
  def getDirectiveWithContext(directiveId:DirectiveId) : IOResult[Option[(Technique, ActiveTechnique, Directive)]]

  /**
   * Find the active technique for which the given directive is an instance.
   *
   * Return empty if no such directive is known,
   * fails if no active technique match the directive.
   */
  def getActiveTechniqueAndDirective(id:DirectiveId) : IOResult[Option[(ActiveTechnique, Directive)]]

  /**
   * Get directives for given technique.
   * A not known technique id is a failure.
   */
  def getDirectives(activeTechniqueId:ActiveTechniqueId, includeSystem:Boolean = false) : IOResult[Seq[Directive]]

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
  def getActiveTechniqueByCategory(includeSystem:Boolean = false) : IOResult[SortedMap[List[ActiveTechniqueCategoryId], CategoryWithActiveTechniques]]

  /**
   * Find back an active technique thanks to its id.
   * Return Empty if the active technique is not found,
   * Fails on error.
   */
  def getActiveTechnique(id:ActiveTechniqueId) : IOResult[Option[ActiveTechnique]]


  /**
   * Find back an active technique thanks to the id of its referenced
   * Technique.
   * Return Empty if the active technique is not found,
   * Fails on error.
   */
  def getActiveTechnique(techniqueName: TechniqueName) : IOResult[Option[ActiveTechnique]]

  /**
   * Retrieve the list of parents for the given active technique,
   * till the root of technique library.
   * Return empty if the path can not be build
   * (missing technique, missing category, etc)
   */
  def activeTechniqueBreadCrump(id:ActiveTechniqueId) : IOResult[List[ActiveTechniqueCategory]]


  /**
   * Root user categories
   */
  def getActiveTechniqueLibrary : IOResult[ActiveTechniqueCategory]

  /**
   * Return all categories non system (lightweight version, with no children)
   * @return
   */
  def getAllActiveTechniqueCategories(includeSystem:Boolean = false) : IOResult[Seq[ActiveTechniqueCategory]]


  /**
   * Get an active technique by its ID
   */
  def getActiveTechniqueCategory(id:ActiveTechniqueCategoryId) : IOResult[ActiveTechniqueCategory]

  /**
   * Get the direct parent of the given category.
   * Return empty for root of the hierarchy, fails if the category
   * is not in the repository
   */
  def getParentActiveTechniqueCategory(id:ActiveTechniqueCategoryId) : IOResult[ActiveTechniqueCategory]

  /**
   * Return the list of parents for that category, the nearest parent
   * first, until the root of the library.
   * The the last parent is not the root of the library, return a Failure.
   * Also return a failure if the path to top is broken in any way.
   */
  def getParentsForActiveTechniqueCategory(id:ActiveTechniqueCategoryId) : IOResult[List[ActiveTechniqueCategory]]

  def getParentsForActiveTechnique(id:ActiveTechniqueId) : IOResult[ActiveTechniqueCategory]

  /**
   * Return true if at least one directive exists in this category (or a sub category
   * of this category)
   */
  def containsDirective(id: ActiveTechniqueCategoryId) : scalaz.zio.UIO[Boolean]
}

trait WoDirectiveRepository {


  /**
   * Save the given directive into given active technique
   * If the directive is already present in the system but not
   * in the given category, raise an error.
   * If the directive is already in the given technique,
   * update the directive.
   * If the directive is not in the system, add it.
   *
   * Returned the saved UserDirective
   * System policy instance can't be saved with that method.
   *
   */
  def saveDirective(inActiveTechniqueId:ActiveTechniqueId, directive:Directive, modId: ModificationId, actor:EventActor, reason:Option[String]) : IOResult[Option[DirectiveSaveDiff]]

  /**
   * Save the given system directive into given user technique
   * If the directive is already present in the system but not
   * in the given category, raise an error.
   * If the directive is already in the given technique,
   * update the directive.
   * If the directive is not in the system, add it.
   *
   * Non system directive can't be saved with that method.
   *
   * Returned the saved Directive
   */
  def saveSystemDirective(inActiveTechniqueId:ActiveTechniqueId,directive:Directive, modId: ModificationId, actor:EventActor, reason:Option[String]) : IOResult[Option[DirectiveSaveDiff]]

  /**
   * Delete a directive.
   * No dependency check are done, and so you will have to
   * delete dependent rule (or other items) by
   * hand if you want.
   *
   * If the given directiveId does not exist, it leads to a
   * failure.
   *
   * System directive can't be deleted.
   */
  def delete(id:DirectiveId, modId: ModificationId, actor:EventActor, reason:Option[String]) : IOResult[Option[DeleteDirectiveDiff]]


  /**
   * Create an active technique from the parameter WBTechnique
   * and add it in the given ActiveTechniqueCategory
   *
   * Returned the freshly created ActiveTechnique
   *
   * Fails if
   *   - the active technique id refer to no technique,
   *   - the category id does not exist,
   *   - the technique is already in the active technique
   *     library
   */
  def addTechniqueInUserLibrary(
      categoryId   : ActiveTechniqueCategoryId
    , techniqueName: TechniqueName
    , versions     : Seq[TechniqueVersion]
    , modId        : ModificationId
    , actor        : EventActor
    , reason       : Option[String]
  ) : IOResult[ActiveTechnique]


  /**
   * Move an active technique to a new category.
   * Failure if the given active technique or category
   * does not exist.
   *
   */
  def move(id:ActiveTechniqueId, newCategoryId:ActiveTechniqueCategoryId, modId: ModificationId, actor: EventActor, reason: Option[String]) : IOResult[ActiveTechniqueId]

  /**
   * Set the status of the active technique to the new value
   */
  def changeStatus(id:ActiveTechniqueId, status:Boolean, modId: ModificationId, actor: EventActor, reason: Option[String]) : IOResult[ActiveTechniqueId]

  /**
   * Add new (version,acceptation datetime) to existing
   * acceptation datetimes by the new one.
   *
   * Return empty if the active technique not in the repos,
   * Failure if an error happened,
   * Full(id) when success
   */
  def setAcceptationDatetimes(id:ActiveTechniqueId, datetimes: Map[TechniqueVersion,DateTime], modId: ModificationId, actor: EventActor, reason: Option[String]) : IOResult[ActiveTechniqueId]

  /**
   * Delete the active technique in the active tehcnique library.
   * If no such element exists, it is a success.
   */
  def delete(id:ActiveTechniqueId, modId: ModificationId, actor: EventActor, reason: Option[String]) : IOResult[ActiveTechniqueId]


  /**
   * Add the given category into the given parent category in the
   * user library.
   * Fails if the parent category does not exist in active technique library
   * or if it already contains that category, or a category of the
   * same name (name must be unique for a given level)
   *
   * return the modified parent category.
   */
  def addActiveTechniqueCategory(
      that : ActiveTechniqueCategory
    , into : ActiveTechniqueCategoryId //parent category
    , modificationId: ModificationId
    , actor: EventActor
    , reason: Option[String]
  ) : IOResult[ActiveTechniqueCategory]

  /**
   * Update an existing active technique category
   * Fail if the parent already contains a category of the
   * same name (name must be unique for a given level)
   */
  def saveActiveTechniqueCategory(category:ActiveTechniqueCategory, modificationId: ModificationId, actor: EventActor, reason: Option[String]) : IOResult[ActiveTechniqueCategory]


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
  def delete(id:ActiveTechniqueCategoryId, modificationId: ModificationId, actor: EventActor, reason: Option[String], checkEmpty:Boolean = true) : IOResult[ActiveTechniqueCategoryId]

  /**
   * Move an existing category into a new one.
   * Both category to move and destination have to exists, else it is a failure.
   * The destination category can not be a child of the category to move.
   * Fail if the parent already contains a category of the
   * same name (name must be unique for a given level)
   */
  def move(categoryId:ActiveTechniqueCategoryId, intoParent:ActiveTechniqueCategoryId, modificationId: ModificationId, actor: EventActor, reason: Option[String]) : IOResult[ActiveTechniqueCategoryId]

}
