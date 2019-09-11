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

package com.normation.rudder.services.policies

import com.normation.cfclerk.domain.RootTechniqueCategoryId
import com.normation.cfclerk.domain.SubTechniqueCategoryId
import com.normation.cfclerk.domain.TechniqueCategoryId
import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.services._
import com.normation.eventlog.ModificationId
import com.normation.eventlog.EventActor
import com.normation.rudder.domain.policies.ActiveTechniqueCategoryId
import com.normation.rudder.domain.policies.ActiveTechniqueCategory
import com.normation.rudder.repository.FullActiveTechniqueCategory
import com.normation.rudder.repository.RoDirectiveRepository
import com.normation.rudder.repository.WoDirectiveRepository
import com.normation.utils.StringUuidGenerator
import com.normation.utils.Control
import net.liftweb.common.Loggable
import net.liftweb.common.EmptyBox
import net.liftweb.common.Full
import org.joda.time.DateTime
import net.liftweb.common.Box
import net.liftweb.common.Empty
import net.liftweb.common.Failure


/**
 * This handler is in charge to maintain a correct state
 * between the technique library and the active techniques.
 * Mainly, it:
 * - auto-add new techniques so that they appear in the
 *   active-techniques,
 * - disable active technique for which there is no more techniques
 * - update last-acceptation time for newly accepted and updated
 *   techniques
 *
 * It does nothing on directives, see
 * com.normation.rudder.services.policies.SaveDirectivesOnTechniqueCallback
 * for that.
 */
class TechniqueAcceptationUpdater(
    override val name     : String
  , override val order    : Int
  , roActiveTechniqueRepo : RoDirectiveRepository
  , rwActiveTechniqueRepo : WoDirectiveRepository
  , techniqueRepo         : TechniqueRepository
  , uuidGen               : StringUuidGenerator
) extends TechniquesLibraryUpdateNotification with Loggable {

  override def updatedTechniques(gitRev: String, techniqueMods: Map[TechniqueName, TechniquesLibraryUpdateType], updatedCategories: Set[TechniqueCategoryModType], modId: ModificationId, actor: EventActor, reason: Option[String]) : Box[Unit] = {

    // id is the directory name, name is the display name in xml
    final case class CategoryInfo(id: String, name: String, description: String)

    /*
     * return the category id in which we want to create the technique
     */
    @scala.annotation.tailrec
    def findCategory(sourcesCatNames: List[CategoryInfo], existings: FullActiveTechniqueCategory): (ActiveTechniqueCategoryId, String) = {
      if(sourcesCatNames.isEmpty ) (existings.id, existings.name)
      else {
        val catId = sourcesCatNames.head.id.trim

        existings.subCategories.find { cat => cat.id.value == catId} match {
          case None =>
            //create and go deeper
            createCategories(sourcesCatNames.map(x => CategoryInfo(x.id, x.name, x.description)), (existings.id, existings.name))
          case Some(cat) =>
            // continue !
            findCategory(sourcesCatNames.tail, cat)

        }
      }
    }

    /*
     * create a chain of categories/subcategories
     * That method never fails: even if we can't create categories,
     * we at least return an id of an existing one.
     */
    @scala.annotation.tailrec
    def createCategories(names: List[CategoryInfo], parentCategory: (ActiveTechniqueCategoryId, String)): (ActiveTechniqueCategoryId, String) = {
      names match {
        case Nil => parentCategory
        case head::tail =>
          val info = names.head
          //to maintain sync, active techniques categories have the same ID than the corresponding technique category
          val cat = ActiveTechniqueCategory(ActiveTechniqueCategoryId(info.id), info.name, info.description, List(), List())

          rwActiveTechniqueRepo.addActiveTechniqueCategory(cat, parentCategory._1, modId, actor, reason) match {
            case eb: EmptyBox =>
              val e = eb ?~! "Error when trying to create to hierarchy of categories into which the new technique should be added"
              logger.error(e.messageChain)
              e.rootExceptionCause.foreach { ex =>
                logger.error("Root exception was:", ex)
              }
              //return parent category id as the place to put the technique
              parentCategory
            case Full(_) => createCategories(tail, (cat.id, cat.name))
          }
      }
    }

    def handleCategoriesUpdate(mods: Set[TechniqueCategoryModType]): Box[Seq[Unit]] = {
      // we use the same id for category and active category: the directory name,
      // *safe* for root category: it's "/" for technique, and "Active Techniques" in LDAP
      def toActiveCatId(id: TechniqueCategoryId): ActiveTechniqueCategoryId = {
        id match {
          case RootTechniqueCategoryId =>ActiveTechniqueCategoryId("Active Techniques")
          case SubTechniqueCategoryId(name, parentId) => ActiveTechniqueCategoryId(name.value)
        }
      }

      // we need to sort change: first delete, then add, then move, then update
      val sorted = mods.toList.sortWith { (a, b) => (a, b) match {
        case (TechniqueCategoryModType.Deleted(_), _) => true
        case (_, TechniqueCategoryModType.Deleted(_)) => false
        case (TechniqueCategoryModType.Added(x, parentX), TechniqueCategoryModType.Added(y, parentY)) =>
          parentY == x.id || ! (parentX == y.id)
        case (_:TechniqueCategoryModType.Added, _) => true
        case (_, _:TechniqueCategoryModType.Added) => false
        case (_:TechniqueCategoryModType.Moved, _) => true
        case (_, _:TechniqueCategoryModType.Moved) => false
        case _ => true
      }}

      Control.bestEffort(sorted) { mod => mod match {
        case TechniqueCategoryModType.Deleted(cat) =>
          logger.debug(s"Category '${cat.id.name.value}' deleted in file system")
          if(cat.subCategoryIds.isEmpty && cat.techniqueIds.isEmpty) {
            rwActiveTechniqueRepo.delete(toActiveCatId(cat.id), modId, actor, reason).map(_ => ())
          } else {
            logger.info(s"Not deleting non empty category: '${cat.id.toString}'")
            Full(())
          } // do nothing
        case TechniqueCategoryModType.Added(cat, parentId) =>
          logger.debug(s"Category '${cat.id.toString}' added into '${parentId.toString}'")
          rwActiveTechniqueRepo.addActiveTechniqueCategory(
            ActiveTechniqueCategory(
                toActiveCatId(cat.id)
              , cat.name
              , cat.description
              , Nil
              , Nil
              , cat.isSystem
            ), toActiveCatId(parentId), modId, actor, reason
          ).map(_ => ())
        case TechniqueCategoryModType.Moved(from, to) =>
          to match {
            case RootTechniqueCategoryId =>
              Failure(s"Category '${from.toString}' is trying to replace root categoy. This is likely a bug, please report it.")

            case SubTechniqueCategoryId(name, parentId) =>
              logger.debug(s"Category '${from.toString}' moved to '${to.toString}'")
              // if rdn changed, we need to issue a modrdn
              val newName = if(from.name == to.name) None else Some(ActiveTechniqueCategoryId(to.name.value))
              rwActiveTechniqueRepo.move(ActiveTechniqueCategoryId(from.name.value), toActiveCatId(parentId), newName, modId, actor, reason).map(_ => ())
          }
        case TechniqueCategoryModType.Updated(cat) =>
          logger.debug(s"Category '${cat.id.toString}' updated")
          roActiveTechniqueRepo.getActiveTechniqueCategory(toActiveCatId(cat.id)) match {
            case Empty          =>
              cat.id match {
                case _:RootTechniqueCategoryId.type => Full(())
                case i:SubTechniqueCategoryId =>
                  rwActiveTechniqueRepo.addActiveTechniqueCategory(
                    ActiveTechniqueCategory(
                        toActiveCatId(cat.id)
                      , cat.name
                      , cat.description
                      , Nil
                      , Nil
                      , cat.isSystem
                    ), toActiveCatId(i.parentId), modId, actor, reason
                  ).map(_ => ())
                }
            case Full(existing) =>
              val updated =  existing.copy(name = cat.name, description = cat.description)
              rwActiveTechniqueRepo.saveActiveTechniqueCategory(updated, modId, actor, reason).map(_ => ())
            case f:Failure      => f
          }
      }}
    }

    val acceptationDatetime = DateTime.now()

      for {
        _                <- handleCategoriesUpdate(updatedCategories)
        techLib          <- roActiveTechniqueRepo.getFullDirectiveLibrary
        activeTechniques =  techLib.allActiveTechniques.map { case (_, at) => (at.techniqueName, at) }
        accepted         <- Control.bestEffort(techniqueMods.toSeq) { case (name, mod) =>
                              (mod, activeTechniques.get(name) ) match {

                                case (TechniqueDeleted(name, versions), None) =>
                                  //nothing to do
                                  Full({})

                                case (TechniqueDeleted(name, versions), Some(activeTechnique)) =>
                                  //if an active technique still exists for that technique, disable it
                                  rwActiveTechniqueRepo.changeStatus(activeTechnique.id, false, modId, actor, reason)

                                case (TechniqueUpdated(name, mods), Some(activeTechnique)) =>
                                  logger.debug("Update acceptation datetime for: " + activeTechnique.techniqueName)
                                  val versionsMap = mods.keysIterator.map( v => (v, acceptationDatetime)).toMap
                                  rwActiveTechniqueRepo.setAcceptationDatetimes(activeTechnique.id, versionsMap, modId, actor, reason) match {
                                    case e: EmptyBox =>
                                      e ?~! s"Error when saving Active Technique ${activeTechnique.id.value} for technque ${activeTechnique.techniqueName}"
                                    case _ =>
                                      Full({})
                                  }

                                case (TechniqueUpdated(name, mods), None) =>

                                  /*
                                   * Here, we want to add an "auto-add active technique" feature.
                                   * The addition is done as soon as something is added for the technique,
                                   * meaning that:
                                   * - we don't auto-add on only delete
                                   * - we do auto-add on any version, even if some already exists, what could
                                   *   mean that the user un-added the active technique a previous time. But
                                   *   it also could mean that the auto-add feature was not here yet.
                                   */
                                  mods.find(x => x._2 == VersionAdded || x._2 == VersionUpdated ) match {
                                    case None => //do nothing
                                      Full({})
                                    case Some((version, mod)) =>

                                      //get the category for the technique
                                      val techniquesInfo = techniqueRepo.getTechniquesInfo()

                                      techniquesInfo.techniques.get(name) match {
                                        case None =>
                                          //hum, something changed on the repos since the update. Strange.
                                          //ignore ? => do nothing
                                          Full({})
                                        case Some(t) =>
                                          //if the technique is system, we must not add it to UserTechniqueLib, the process
                                          //must be handled by a dedicated action. Only update system techniques!
                                          val isSystem = t.exists( _._2.isSystem == true) //isSystem should be consistant, but still
                                          if(isSystem && mod == VersionAdded) {
                                            logger.info(s"Not auto-adding system techniques '${name.value}' in user library. You will need to add it explicitly.")
                                            Full({})
                                          } else {
                                            val referenceId = t.head._2.id

                                            val referenceCats = techniquesInfo.techniquesCategory(referenceId).getIdPathFromRoot.map( techniquesInfo.allCategories(_) )

                                            //now, for each category in reference library, look if it exists in target library, and recurse on children
                                            //tail of cats, because if root, we want an empty list to return immediatly in findCategory
                                            val parentCat = findCategory(referenceCats.tail.map(x => CategoryInfo(x.id.name.value, x.name, x.description)), techLib)

                                            logger.info(s"Automatically adding technique '${name}' in category '${parentCat._2} (${parentCat._1.value})' of active techniques library")
                                            rwActiveTechniqueRepo.addTechniqueInUserLibrary(parentCat._1, name, mods.keys.toSeq, modId, actor, reason) match {
                                              case eb: EmptyBox =>
                                                eb ?~! s"Error when automatically activating technique '${name}'"
                                              case Full(_) => //ok, nothing to do
                                                Full({})
                                            }
                                          }
                                      }

                                  }
                              }
                            }
      } yield {
        {}
      }
  }
}
