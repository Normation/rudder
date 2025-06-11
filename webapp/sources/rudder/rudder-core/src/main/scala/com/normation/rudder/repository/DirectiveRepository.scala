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

import com.normation.cfclerk.domain.Technique
import com.normation.cfclerk.domain.TechniqueCategory
import com.normation.cfclerk.domain.TechniqueCategoryId
import com.normation.cfclerk.domain.TechniqueId
import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.domain.TechniqueVersion
import com.normation.cfclerk.services.TechniqueRepository
import com.normation.errors.*
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.rudder.domain.eventlog.RudderEventActor
import com.normation.rudder.domain.policies.*
import com.normation.utils.StringUuidGenerator
import com.normation.utils.Utils
import com.softwaremill.quicklens.*
import net.liftweb.common.Box
import net.liftweb.common.Full
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import scala.collection.SortedMap

/**
 * Here is the ordering for a List[ActiveTechniqueCategoryId]
 * MUST start by the root !
 */
object ActiveTechniqueCategoryOrdering extends Ordering[List[ActiveTechniqueCategoryId]] {
  type ID = ActiveTechniqueCategoryId
  override def compare(x: List[ID], y: List[ID]): Int = {
    Utils.recTreeStringOrderingCompare(x.map(_.value), y.map(_.value))
  }
}

/**
 * A simple container for a category
 * and its direct children ActiveTechniques
 */
final case class CategoryWithActiveTechniques(
    category:  ActiveTechniqueCategory,
    templates: Set[ActiveTechnique]
)

/**
 * Note that we can have a different set of
 * Technique versions in techniquesDateTime
 * and in "techniques"
 */
final case class FullActiveTechnique(
    id:                   ActiveTechniqueId,
    techniqueName:        TechniqueName,
    acceptationDatetimes: SortedMap[TechniqueVersion, DateTime],
    techniques:           SortedMap[TechniqueVersion, Technique],
    directives:           List[Directive],
    isEnabled:            Boolean = true,
    policyTypes:          PolicyTypes = PolicyTypes.rudderBase
) {
  def toActiveTechnique(): ActiveTechnique = ActiveTechnique(
    id = id,
    techniqueName = techniqueName,
    acceptationDatetimes = AcceptationDateTime(acceptationDatetimes.toMap),
    directives = directives.map(_.id.uid),
    _isEnabled = isEnabled,
    policyTypes = policyTypes
  )

  val newestAvailableTechnique: Option[Technique] = techniques.toSeq.sortBy(_._1).reverse.map(_._2).headOption

  /*
   * Add some direcives for some technique, and only keep directives whose id/revision is in `keep`.
   */
  def addAndFilterDirectives(addDirectives: List[(TechniqueName, Directive)], keep: Set[DirectiveId]): FullActiveTechnique = {
    val ids = directives.map(_.id)
    // don't add a directive if it's already in directive list
    val d2  = addDirectives.collect { case (t, d) if (t == techniqueName && !ids.contains(d.id)) => d } ::: directives
    val d3  = d2.filter(d => keep.contains(d.id))
    val v2  = d3.map(_.techniqueVersion).toSet
    def predicate(pair: (TechniqueVersion, Any)): Boolean = v2.contains(pair._1)
    this.copy(
      directives = d3,
      techniques = techniques.filter(predicate),
      acceptationDatetimes = acceptationDatetimes.filter(predicate)
    )
  }
  def addTechniques(addTechniques: List[Technique]):                                                   FullActiveTechnique = {
    val here = addTechniques.filter(_.id.name == techniqueName)
    // I don't see any reasonable value for acceptation date of past technique. It needs to be in the past and
    // must not change from generation to the next, so => Epoch.
    this.copy(
      techniques = techniques ++ here.map(t => (t.id.version, t)),
      acceptationDatetimes = acceptationDatetimes ++ here.map(t => (t.id.version, new DateTime(0, DateTimeZone.UTC)))
    )
  }

  def saveDirective(directive: Directive): FullActiveTechnique = {
    this.modify(_.directives).using(directives => (directive :: directives.filterNot(_.id == directive.id)))
  }

  def deleteDirective(directiveUid: DirectiveUid): FullActiveTechnique = {
    this.modify(_.directives).using(directives => directives.filterNot(_.id.uid == directiveUid))
  }
}

final case class FullActiveTechniqueCategory(
    id:               ActiveTechniqueCategoryId,
    name:             String,
    description:      String,
    subCategories:    List[FullActiveTechniqueCategory],
    activeTechniques: List[FullActiveTechnique],
    isSystem:         Boolean = false // by default, we can't create system Category
) {

  val allDirectives: Map[DirectiveId, (FullActiveTechnique, Directive)] = (
    subCategories.flatMap(_.allDirectives).toMap
      ++ activeTechniques.flatMap(at => at.directives.map(d => (d.id, (at, d)))).toMap
  )

  val allDirectivesByActiveTechniques: Map[ActiveTechniqueId, List[Directive]] = (
    subCategories.flatMap(_.allDirectivesByActiveTechniques).toMap
      ++ activeTechniques.map(at => (at.id, at.directives)).toMap
  )

  val allActiveTechniques: Map[ActiveTechniqueId, FullActiveTechnique] = (
    subCategories.flatMap(_.allActiveTechniques).toMap
      ++ activeTechniques.map(at => (at.id, at)).toMap
  )

  val allActiveTechniquesByCategories: Map[ActiveTechniqueCategoryId, List[FullActiveTechnique]] = (
    subCategories.flatMap(_.allActiveTechniquesByCategories).toMap
      + (id -> activeTechniques)
  )

  val allTechniques: Map[TechniqueId, (Technique, Option[DateTime])] = {
    allActiveTechniques.flatMap {
      case (_, at) => (
        at.techniques.map {
          case (version, technique) =>
            (TechniqueId(at.techniqueName, version) -> ((technique, at.acceptationDatetimes.get(version))))
        }
      )
    }
  }

  lazy val allCategories: Map[ActiveTechniqueCategoryId, FullActiveTechniqueCategory] = {
    subCategories.foldLeft(Map((id -> this)))(_ ++ _.allCategories)
  }

  def getUpdateDateTime(id: TechniqueId): Option[DateTime] = {
    allTechniques.get(id).flatMap(_._2)
  }

  /*
   * Add given directive (if not already defined) and filter lib to only keep ids
   */
  def addAndFilterDirectives(add: List[(TechniqueName, Directive)], keep: Set[DirectiveId]): FullActiveTechniqueCategory = {
    val subCats = subCategories.flatMap { s =>
      val s2 = s.addAndFilterDirectives(add, keep)
      if (s2.subCategories.isEmpty && s2.activeTechniques.isEmpty) Nil
      else List(s2)
    }
    val ats     = activeTechniques.flatMap { at =>
      val at2 = at.addAndFilterDirectives(add, keep)
      if (at2.directives.isEmpty) Nil
      else List(at2)
    }
    this.copy(subCategories = subCats, activeTechniques = ats)
  }

  def addTechniques(add: List[Technique]): FullActiveTechniqueCategory = {
    val subCats = subCategories.map(_.addTechniques(add))
    val ats     = activeTechniques.map(_.addTechniques(add))
    this.copy(subCategories = subCats, activeTechniques = ats)
  }

  // UPDATE / DELETE directives - primaly used for tests

  // a version of that tree which knows of path
  lazy val fullIndex: SortedMap[List[ActiveTechniqueCategoryId], FullActiveTechniqueCategory] = {
    def recBuild(
        root:    FullActiveTechniqueCategory,
        parents: List[ActiveTechniqueCategoryId]
    ): List[(List[ActiveTechniqueCategoryId], FullActiveTechniqueCategory)] = {
      val path = root.id :: parents
      (path, root) :: (root.subCategories.map(c => recBuild(c, path))).flatten
    }
    implicit val ordering = ActiveTechniqueCategoryOrdering
    SortedMap[List[ActiveTechniqueCategoryId], FullActiveTechniqueCategory]() ++ recBuild(this, Nil)
  }

  def toActiveTechniqueCategory(): ActiveTechniqueCategory = {
    ActiveTechniqueCategory(
      id,
      name,
      description,
      subCategories.map(_.id),
      activeTechniques.map(_.id),
      isSystem
    )
  }

  /**
   * Save directive in given active technique.
   * Returns an error is active technique is missing.
   * Returns the updated version of that full category
   */
  def saveDirective(inActiveTechniqueId: ActiveTechniqueId, directive: Directive): PureResult[FullActiveTechniqueCategory] = {
    val error: PureResult[FullActiveTechniqueCategory] = Left(
      Inconsistency(s"Active technique '${inActiveTechniqueId.value}' not found.'")
    )
    this.activeTechniques.find(_.id == inActiveTechniqueId) match {
      case Some(fat) =>
        Right(this.modify(_.activeTechniques).using(techs => (fat.saveDirective(directive) :: techs.filterNot(_.id == fat.id))))
      case None      =>
        subCategories.foldLeft(error) {
          case (Right(sub), _) =>
            Right(sub)

          case (Left(e), sub) =>
            sub.saveDirective(inActiveTechniqueId, directive) match {
              case Left(e)  =>
                Left(e)
              case Right(s) =>
                Right(this.modify(_.subCategories).using(cats => (s :: cats.filterNot(_.id == s.id))))
            }
        }
    }
  }

  def deleteDirective(directiveId: DirectiveUid): FullActiveTechniqueCategory = {
    this
      .modify(_.activeTechniques)
      .using(techs => techs.map(_.deleteDirective(directiveId)))
      .modify(_.subCategories)
      .using(subs => subs.map(_.deleteDirective(directiveId)))
  }

  def addActiveTechnique(
      categoryId:    ActiveTechniqueCategoryId,
      techniqueName: TechniqueName,
      techniques:    Seq[Technique]
  ): FullActiveTechniqueCategory = {
    if (this.id == categoryId) {
      // only keep technique with the good name
      val techs    = techniques.filter(_.id.name == techniqueName)
      val now      = DateTime.now(DateTimeZone.UTC)
      val newTimes = techs.map(t => (t.id.version, now))
      val newTechs = techs.map(t => (t.id.version, t))
      val updated  = activeTechniques.find(_.id.value == techniqueName.value) match {
        case None      =>
          FullActiveTechnique(
            ActiveTechniqueId(techniqueName.value),
            techniqueName,
            SortedMap[TechniqueVersion, DateTime]() ++ newTimes,
            SortedMap[TechniqueVersion, Technique]() ++ newTechs,
            Nil,
            isEnabled = true,
            policyTypes = PolicyTypes.rudderBase
          )
        case Some(fat) =>
          fat.modify(_.acceptationDatetimes).using(_ ++ newTimes).modify(_.techniques).using(_ ++ newTechs)
      }
      this.modify(_.activeTechniques).using(techs => updated :: techs.filterNot(_.id == updated.id))
    } else {
      this.modify(_.subCategories).using(_.map(_.addActiveTechnique(categoryId, techniqueName, techniques)))
    }
  }
  def addActiveTechniqueCategory(
      categoryId: ActiveTechniqueCategoryId,
      category:   FullActiveTechniqueCategory
  ): FullActiveTechniqueCategory = {
    if (this.id == categoryId) {
      // only keep technique with the good name
      if (this.subCategories.exists(_.id == categoryId)) this
      else {
        this.modify(_.subCategories).using(subs => category :: subs.filterNot(_.id == categoryId))
      }
    } else {
      this.modify(_.subCategories).using(_.map(_.addActiveTechniqueCategory(categoryId, category)))
    }
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
  def getFullDirectiveLibrary(): IOResult[FullActiveTechniqueCategory]

  /**
   * Try to find the directive with the given ID.
   * Empty: no directive with such ID
   * Full((parent,directive)) : found the directive (directive.id == directiveId) in given parent
   * Failure => an error happened.
   */
  def getDirective(directiveId: DirectiveUid): IOResult[Option[Directive]]

  /**
   * retrieve a Directive with its parent Technique and the
   * binding Active Technique
   */
  def getDirectiveWithContext(directiveId: DirectiveUid): IOResult[Option[(Technique, ActiveTechnique, Directive)]]

  /**
   * Find the active technique for which the given directive is an instance.
   *
   * Return empty if no such directive is known,
   * fails if no active technique match the directive.
   */
  def getActiveTechniqueAndDirective(id: DirectiveId): IOResult[Option[(ActiveTechnique, Directive)]]

  /**
   * Get directives for given technique.
   * A not known technique id is a failure.
   */
  def getDirectives(activeTechniqueId: ActiveTechniqueId, includeSystem: Boolean = false): IOResult[Seq[Directive]]

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
  def getActiveTechniqueByCategory(
      includeSystem: Boolean = false
  ): IOResult[SortedMap[List[ActiveTechniqueCategoryId], CategoryWithActiveTechniques]]

  /**
   * Find back an active technique thanks to its id.
   * Return Empty if the active technique is not found,
   * Fails on error.
   */
  def getActiveTechniqueByActiveTechnique(id: ActiveTechniqueId): IOResult[Option[ActiveTechnique]]

  /**
   * Find back an active technique thanks to the id of its referenced
   * Technique.
   * Return Empty if the active technique is not found,
   * Fails on error.
   */
  def getActiveTechnique(techniqueName: TechniqueName): IOResult[Option[ActiveTechnique]]

  /**
   * Retrieve the list of parents for the given active technique,
   * till the root of technique library.
   * Return empty if the path can not be build
   * (missing technique, missing category, etc)
   */
  def activeTechniqueBreadCrump(id: ActiveTechniqueId): IOResult[List[ActiveTechniqueCategory]]

  /**
   * Root user categories
   */
  def getActiveTechniqueLibrary: IOResult[ActiveTechniqueCategory]

  /**
   * Return all categories non system (lightweight version, with no children)
   * @return
   */
  def getAllActiveTechniqueCategories(includeSystem: Boolean = false): IOResult[Seq[ActiveTechniqueCategory]]

  /**
   * Get an active technique by its ID
   */
  def getActiveTechniqueCategory(id: ActiveTechniqueCategoryId): IOResult[Option[ActiveTechniqueCategory]]

  /**
   * Get the direct parent of the given category.
   * Return empty for root of the hierarchy, fails if the category
   * is not in the repository
   */
  def getParentActiveTechniqueCategory(id: ActiveTechniqueCategoryId): IOResult[ActiveTechniqueCategory]

  /**
   * Return the list of parents for that category, the nearest parent
   * first, until the root of the library.
   * The the last parent is not the root of the library, return a Failure.
   * Also return a failure if the path to top is broken in any way.
   */
  def getParentsForActiveTechniqueCategory(id: ActiveTechniqueCategoryId): IOResult[List[ActiveTechniqueCategory]]

  def getParentsForActiveTechnique(id: ActiveTechniqueId): IOResult[ActiveTechniqueCategory]

  /**
   * TODO: Never use, remove in rudder 7.0
   */
  def containsDirective(id: ActiveTechniqueCategoryId): zio.UIO[Boolean]
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
  def saveDirective(
      inActiveTechniqueId: ActiveTechniqueId,
      directive:           Directive,
      modId:               ModificationId,
      actor:               EventActor,
      reason:              Option[String]
  ): IOResult[Option[DirectiveSaveDiff]]

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
  def saveSystemDirective(
      inActiveTechniqueId: ActiveTechniqueId,
      directive:           Directive,
      modId:               ModificationId,
      actor:               EventActor,
      reason:              Option[String]
  ): IOResult[Option[DirectiveSaveDiff]]

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
  def delete(
      id:     DirectiveUid,
      modId:  ModificationId,
      actor:  EventActor,
      reason: Option[String]
  ): IOResult[Option[DeleteDirectiveDiff]]

  /**
   * Delete a directive's system.
   * No dependency check are done, and so you will have to
   * delete dependent rule (or other items) by
   * hand if you want.
   *
   * If no directive has such id, return a success.
   */
  def deleteSystemDirective(
      id:     DirectiveUid,
      modId:  ModificationId,
      actor:  EventActor,
      reason: Option[String]
  ): IOResult[Option[DeleteDirectiveDiff]]

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
      categoryId:    ActiveTechniqueCategoryId,
      techniqueName: TechniqueName,
      versions:      Seq[TechniqueVersion],
      policyTypes:   PolicyTypes,
      modId:         ModificationId,
      actor:         EventActor,
      reason:        Option[String]
  ): IOResult[ActiveTechnique]

  /**
   * Move an active technique to a new category.
   * Failure if the given active technique or category
   * does not exist.
   *
   */
  def move(
      id:            ActiveTechniqueId,
      newCategoryId: ActiveTechniqueCategoryId,
      modId:         ModificationId,
      actor:         EventActor,
      reason:        Option[String]
  ): IOResult[ActiveTechniqueId]

  /**
   * Set the status of the active technique to the new value
   */
  def changeStatus(
      id:     ActiveTechniqueId,
      status: Boolean,
      modId:  ModificationId,
      actor:  EventActor,
      reason: Option[String]
  ): IOResult[ActiveTechniqueId]

  /**
   * Add new (version,acceptation datetime) to existing
   * acceptation datetimes by the new one.
   *
   * Return empty if the active technique not in the repos,
   * Failure if an error happened,
   * Full(id) when success
   */
  def setAcceptationDatetimes(
      id:        ActiveTechniqueId,
      datetimes: Map[TechniqueVersion, DateTime],
      modId:     ModificationId,
      actor:     EventActor,
      reason:    Option[String]
  ): IOResult[ActiveTechniqueId]

  /**
   * Delete the active technique in the active tehcnique library.
   * If no such element exists, it is a success.
   */
  def deleteActiveTechnique(
      id:     ActiveTechniqueId,
      modId:  ModificationId,
      actor:  EventActor,
      reason: Option[String]
  ): IOResult[ActiveTechniqueId]

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
      that: ActiveTechniqueCategory,
      into: ActiveTechniqueCategoryId, // parent category

      modificationId: ModificationId,
      actor:          EventActor,
      reason:         Option[String]
  ): IOResult[ActiveTechniqueCategory]

  /**
   * Update an existing active technique category
   * Fail if the parent already contains a category of the
   * same name (name must be unique for a given level)
   */
  def saveActiveTechniqueCategory(
      category:       ActiveTechniqueCategory,
      modificationId: ModificationId,
      actor:          EventActor,
      reason:         Option[String]
  ): IOResult[ActiveTechniqueCategory]

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
  def deleteCategory(
      id:             ActiveTechniqueCategoryId,
      modificationId: ModificationId,
      actor:          EventActor,
      reason:         Option[String],
      checkEmpty:     Boolean = true
  ): IOResult[ActiveTechniqueCategoryId]

  /**
   * Move an existing category into a new one.
   * Both category to move and destination have to exists, else it is a failure.
   * The destination category can not be a child of the category to move.
   * Fail if the parent already contains a category of the
   * same name (name must be unique for a given level)
   */
  def move(
      categoryId:     ActiveTechniqueCategoryId,
      intoParent:     ActiveTechniqueCategoryId,
      optionNewName:  Option[ActiveTechniqueCategoryId],
      modificationId: ModificationId,
      actor:          EventActor,
      reason:         Option[String]
  ): IOResult[ActiveTechniqueCategoryId]

}

/**
 * A class in charge of initializing the active techniques / directives tree
 */
class InitDirectivesTree(
    techniqueRepository:   TechniqueRepository,
    roDirectiveRepository: RoDirectiveRepository,
    woDirectiveRepository: WoDirectiveRepository,
    uuidGen:               StringUuidGenerator
) {
  import com.normation.box.*
  import com.normation.utils.Control.*

  def copyReferenceLib(includeSystem: Boolean = false): Box[Seq[ActiveTechniqueCategory]] = {
    def genUserCatId(fromCat: TechniqueCategory):                                         ActiveTechniqueCategoryId    = {
      // for the technique ID, use the last part of the path used for the cat id.
      ActiveTechniqueCategoryId(fromCat.id.name.value)
    }
    def recCopyRef(fromCatId: TechniqueCategoryId, toParentCat: ActiveTechniqueCategory): Box[ActiveTechniqueCategory] = {

      for {
        fromCat     <- techniqueRepository.getTechniqueCategory(fromCatId).toBox
        newUserPTCat = ActiveTechniqueCategory(
                         id = genUserCatId(fromCat),
                         name = fromCat.name,
                         description = fromCat.description,
                         children = Nil,
                         items = Nil,
                         isSystem = fromCat.isSystem
                       )
        res         <- if (fromCat.isSystem && !includeSystem) { // Rudder internal Technique category are handle elsewhere
                         Full(newUserPTCat)
                       } else {
                         for {
                           updatedParentCat <- woDirectiveRepository
                                                 .addActiveTechniqueCategory(
                                                   newUserPTCat,
                                                   toParentCat.id,
                                                   ModificationId(uuidGen.newUuid),
                                                   RudderEventActor,
                                                   reason = Some("Initialize active templates library")
                                                 )
                                                 .toBox ?~!
                                               "Error when adding category '%s' to user library parent category '%s'".format(
                                                 newUserPTCat.id.value,
                                                 toParentCat.id.value
                                               )
                           // now, add items and subcategories, in a "try to do the max you can" way
                           fullRes          <- sequence(
                                                 // Techniques
                                                 bestEffort(fromCat.techniqueIds.groupBy(id => id.name).toSeq) {
                                                   case (name, ids) =>
                                                     for {
                                                       activeTechnique <- woDirectiveRepository
                                                                            .addTechniqueInUserLibrary(
                                                                              newUserPTCat.id,
                                                                              name,
                                                                              ids.map(_.version).toSeq,
                                                                              if (newUserPTCat.isSystem) PolicyTypes.rudderSystem
                                                                              else PolicyTypes.rudderBase,
                                                                              ModificationId(uuidGen.newUuid),
                                                                              RudderEventActor,
                                                                              reason = Some("Initialize active templates library")
                                                                            )
                                                                            .toBox ?~!
                                                                          "Error when adding Technique '%s' into user library category '%s'"
                                                                            .format(name.value, newUserPTCat.id.value)
                                                     } yield {
                                                       activeTechnique
                                                     }
                                                 } ::
                                                 // recurse on children categories of reference lib
                                                 bestEffort(fromCat.subCategoryIds.toSeq)(catId => recCopyRef(catId, newUserPTCat)) ::
                                                 Nil
                                               )
                         } yield {
                           fullRes
                         }
                       }
      } yield {
        newUserPTCat
      }
    }

    // apply with root cat children ids
    roDirectiveRepository.getActiveTechniqueLibrary.toBox.flatMap { root =>
      bestEffort(techniqueRepository.getTechniqueLibrary.subCategoryIds.toSeq)(id => recCopyRef(id, root))
    }
  }

}
