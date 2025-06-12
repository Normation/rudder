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

import com.normation.NamedZioLogger
import com.normation.box.*
import com.normation.cfclerk.domain.RootTechniqueCategoryId
import com.normation.cfclerk.domain.SubTechniqueCategoryId
import com.normation.cfclerk.domain.TechniqueCategoryId
import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.services.*
import com.normation.errors.*
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.rudder.domain.logger.ApplicationLoggerPure
import com.normation.rudder.domain.policies.ActiveTechniqueCategory
import com.normation.rudder.domain.policies.ActiveTechniqueCategoryId
import com.normation.rudder.domain.policies.PolicyTypes
import com.normation.rudder.repository.FullActiveTechniqueCategory
import com.normation.rudder.repository.RoDirectiveRepository
import com.normation.rudder.repository.WoDirectiveRepository
import com.normation.utils.StringUuidGenerator
import com.normation.zio.*
import net.liftweb.common.Box
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import zio.*
import zio.syntax.*

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
    override val name:     String,
    override val order:    Int,
    roActiveTechniqueRepo: RoDirectiveRepository,
    rwActiveTechniqueRepo: WoDirectiveRepository,
    techniqueRepo:         TechniqueRepository,
    uuidGen:               StringUuidGenerator
) extends TechniquesLibraryUpdateNotification with NamedZioLogger {

  override def loggerName: String = this.getClass.getName

  override def updatedTechniques(
      gitRev:            String,
      techniqueMods:     Map[TechniqueName, TechniquesLibraryUpdateType],
      updatedCategories: Set[TechniqueCategoryModType],
      modId:             ModificationId,
      actor:             EventActor,
      reason:            Option[String]
  ): Box[Unit] = {

    // id is the directory name, name is the display name in xml
    final case class CategoryInfo(id: String, name: String, description: String)

    /*
     * return the category id in which we want to create the technique
     */
    @scala.annotation.tailrec
    def findCategory(
        sourcesCatNames: List[CategoryInfo],
        existings:       FullActiveTechniqueCategory
    ): (ActiveTechniqueCategoryId, String) = {
      sourcesCatNames match {
        case Nil               => (existings.id, existings.name)
        case child :: children =>
          val catId = child.id.trim
          existings.subCategories.find(cat => cat.id.value == catId) match {
            case None      =>
              // we need to check for name existence before attempting to create it.
              // it used to be possible to have activeTechniqueId != category file name in fs
              // see https://issues.rudder.io/issues/17774
              val catName = child.name.trim
              existings.subCategories.find(cat => cat.name.trim == catName) match {
                case None      =>
                  // create and go deeper
                  createCategories(
                    sourcesCatNames.map(x => CategoryInfo(x.id, x.name, x.description)),
                    (existings.id, existings.name)
                  )
                case Some(cat) =>
                  // continue !
                  findCategory(children, cat)
              }
            case Some(cat) =>
              // continue !
              findCategory(children, cat)
          }
      }
    }

    /*
     * create a chain of categories/subcategories
     * That method never fails: even if we can't create categories,
     * we at least return an id of an existing one.
     */
    @scala.annotation.tailrec
    def createCategories(
        names:          List[CategoryInfo],
        parentCategory: (ActiveTechniqueCategoryId, String)
    ): (ActiveTechniqueCategoryId, String) = {
      names match {
        case Nil          => parentCategory
        case info :: tail =>
          // to maintain sync, active techniques categories have the same ID than the corresponding technique category
          val cat = ActiveTechniqueCategory(ActiveTechniqueCategoryId(info.id), info.name, info.description, List(), List())

          rwActiveTechniqueRepo.addActiveTechniqueCategory(cat, parentCategory._1, modId, actor, reason).either.runNow match {
            case Left(err) =>
              val msg =
                s"Error when trying to create to hierarchy of categories into which the new technique should be added: ${err.fullMsg}"
              logEffect.error(msg)
              // return parent category id as the place to put the technique
              parentCategory

            case Right(_) => createCategories(tail, (cat.id, cat.name))
          }
      }
    }

    def handleCategoriesUpdate(mods: Set[TechniqueCategoryModType]): IOResult[Seq[Unit]] = {
      // we use the same id for category and active category: the directory name,
      // *safe* for root category: it's "/" for technique, and "Active Techniques" in LDAP
      def toActiveCatId(id: TechniqueCategoryId): ActiveTechniqueCategoryId = {
        id match {
          case RootTechniqueCategoryId                => ActiveTechniqueCategoryId("Active Techniques")
          case SubTechniqueCategoryId(name, parentId) => ActiveTechniqueCategoryId(name.value)
        }
      }

      // we need to sort change: first delete, then add, then move, then update
      val sorted = mods.toList.sortWith { (a, b) =>
        (a, b) match {
          case (TechniqueCategoryModType.Deleted(_), _)                                                 => true
          case (_, TechniqueCategoryModType.Deleted(_))                                                 => false
          case (TechniqueCategoryModType.Added(x, parentX), TechniqueCategoryModType.Added(y, parentY)) =>
            parentY == x.id || !(parentX == y.id)
          case (_: TechniqueCategoryModType.Added, _)                                                   => true
          case (_, _: TechniqueCategoryModType.Added)                                                   => false
          case (_: TechniqueCategoryModType.Moved, _)                                                   => true
          case (_, _: TechniqueCategoryModType.Moved)                                                   => false
          case _                                                                                        => true
        }
      }

      sorted.accumulate { mod =>
        mod match {
          case TechniqueCategoryModType.Deleted(cat)         =>
            logPure
              .debug(s"Category '${cat.id.name.value}' deleted in file system") *> (if (
                                                                                      cat.subCategoryIds.isEmpty && cat.techniqueIds.isEmpty
                                                                                    ) {
                                                                                      rwActiveTechniqueRepo
                                                                                        .deleteCategory(
                                                                                          toActiveCatId(cat.id),
                                                                                          modId,
                                                                                          actor,
                                                                                          reason
                                                                                        )
                                                                                        .map(_ => ())
                                                                                    } else {
                                                                                      logPure.info(
                                                                                        s"Not deleting non empty category: '${cat.id.toString}'"
                                                                                      ) *>
                                                                                      ZIO.unit
                                                                                    }) // do nothing
          case TechniqueCategoryModType.Added(cat, parentId) =>
            logPure.debug(s"Category '${cat.id.toString}' added into '${parentId.toString}'") *>
            rwActiveTechniqueRepo
              .addActiveTechniqueCategory(
                ActiveTechniqueCategory(
                  toActiveCatId(cat.id),
                  cat.name,
                  cat.description,
                  Nil,
                  Nil,
                  cat.isSystem
                ),
                toActiveCatId(parentId),
                modId,
                actor,
                reason
              )
              .unit
          case TechniqueCategoryModType.Moved(from, to)      =>
            to match {
              case RootTechniqueCategoryId =>
                Unexpected(
                  s"Category '${from.toString}' is trying to replace root categoy. This is likely a bug, please report it."
                ).fail

              case SubTechniqueCategoryId(name, parentId) =>
                logPure.debug(s"Category '{from.toString}' moved to '${to.toString}'") *> {
                  // if rdn changed, we need to issue a modrdn
                  val newName = if (from.name == to.name) None else Some(ActiveTechniqueCategoryId(to.name.value))
                  rwActiveTechniqueRepo
                    .move(ActiveTechniqueCategoryId(from.name.value), toActiveCatId(parentId), newName, modId, actor, reason)
                    .unit
                }
            }
          case TechniqueCategoryModType.Updated(cat)         =>
            logPure.debug(s"Category '${cat.id.toString}' updated") *>
            roActiveTechniqueRepo.getActiveTechniqueCategory(toActiveCatId(cat.id)).flatMap { opt =>
              opt match {
                case None           =>
                  (cat.id: @unchecked) match {
                    case _: RootTechniqueCategoryId.type => ZIO.unit
                    case i: SubTechniqueCategoryId       =>
                      rwActiveTechniqueRepo
                        .addActiveTechniqueCategory(
                          ActiveTechniqueCategory(
                            toActiveCatId(cat.id),
                            cat.name,
                            cat.description,
                            Nil,
                            Nil,
                            cat.isSystem
                          ),
                          toActiveCatId(i.parentId),
                          modId,
                          actor,
                          reason
                        )
                        .unit
                  }
                case Some(existing) =>
                  val updated = existing.copy(name = cat.name, description = cat.description)
                  rwActiveTechniqueRepo.saveActiveTechniqueCategory(updated, modId, actor, reason).unit
              }
            }
        }
      }
    }

    val acceptationDatetime = DateTime.now(DateTimeZone.UTC)

    (for {
      _               <- handleCategoriesUpdate(updatedCategories)
      techLib         <- roActiveTechniqueRepo.getFullDirectiveLibrary()
      activeTechniques = techLib.allActiveTechniques.map { case (_, at) => (at.techniqueName, at) }
      accepted        <- techniqueMods.accumulate {
                           case (name, mod) =>
                             (mod, activeTechniques.get(name)) match {

                               case (TechniqueDeleted(name, versions), None) =>
                                 // nothing to do
                                 ZIO.unit

                               case (TechniqueDeleted(name, versions), Some(activeTechnique)) =>
                                 // If an active technique, not system, still exists for that technique, disable it.
                                 // In the case of a system one, something is very broken. Don't disable it, as
                                 // It is likely to worsen things, but log an error.
                                 // Avoid writting the log for 'server-roles' and 'distributePolicy' - they are removed in 7.0 and up
                                 if (activeTechnique.policyTypes.isSystem) {
                                   val removedIn7_0 = List("server-roles", "distributePolicy")
                                   if (removedIn7_0.contains(name)) {
                                     ApplicationLoggerPure.info(
                                       s"System technique '${name}' removed from git base according to Rudder 7.0 update"
                                     )
                                   } else {
                                     ApplicationLoggerPure.error(
                                       s"System technique '${name}' (${versions.map(_.debugString).mkString(",")})' is deleted in " +
                                       s"git base. This will likely cause grave problem. You should investigate."
                                     )
                                   }

                                 } else {
                                   ApplicationLoggerPure.warn(
                                     s"Technique '${name}' (${versions.map(_.debugString).mkString(",")})' is deleted " +
                                     s"but an active technique is still present in tree: disabling it."
                                   ) *>
                                   rwActiveTechniqueRepo.changeStatus(
                                     activeTechnique.id,
                                     status = false,
                                     modId = modId,
                                     actor = actor,
                                     reason = reason
                                   )
                                 }

                               case (TechniqueUpdated(name, mods), Some(activeTechnique)) =>
                                 val versionsMap = mods.keySet.map(v => (v, acceptationDatetime)).toMap
                                 logPure.debug("Update acceptation datetime for: " + activeTechnique.techniqueName) *>
                                 rwActiveTechniqueRepo
                                   .setAcceptationDatetimes(activeTechnique.id, versionsMap, modId, actor, reason)
                                   .chainError(
                                     s"Error when saving Active Technique ${activeTechnique.id.value} for technque ${activeTechnique.techniqueName}"
                                   )
                                   .unit

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
                                 mods.find(x => x._2 == VersionAdded || x._2 == VersionUpdated) match {
                                   case None => // do nothing
                                     ZIO.unit

                                   case Some((version, mod)) =>
                                     // get the category for the technique
                                     val techniquesInfo = techniqueRepo.getTechniquesInfo()

                                     techniquesInfo.techniques.get(name) match {
                                       case None    =>
                                         // hum, something changed on the repos since the update. Strange.
                                         // ignore ? => do nothing
                                         ZIO.unit
                                       case Some(t) =>
                                         val referenceId = t.head._2.id

                                         // if the technique is system, we must be careful: the category is not "system" like in fs
                                         // but "Rudder Internal"

                                         val isSystem =
                                           t.exists(_._2.policyTypes.isSystem == true) // isSystem should be consistent, but still
                                         val policyTypes =
                                           t.get(version).map(_.policyTypes).getOrElse(PolicyTypes.compat(isSystem))

                                         val parentCat = if (isSystem && mod == VersionAdded) {
                                           (ActiveTechniqueCategoryId("Rudder Internal"), "Active techniques used by Rudder")
                                         } else {
                                           val referenceCats = techniquesInfo
                                             .techniquesCategory(referenceId)
                                             .getIdPathFromRoot
                                             .map(techniquesInfo.allCategories(_))
                                           // now, for each category in reference library, look if it exists in target library, and recurse on children
                                           // tail of cats, because if root, we want an empty list to return immediatly in findCategory
                                           findCategory(
                                             referenceCats.tail.map(x => CategoryInfo(x.id.name.value, x.name, x.description)),
                                             techLib
                                           )
                                         }
                                         logPure.info(
                                           s"Automatically adding technique '${name.value}' in category '${parentCat._2} (${parentCat._1.value})' of active techniques library"
                                         ) *>
                                         rwActiveTechniqueRepo
                                           .addTechniqueInUserLibrary(
                                             parentCat._1,
                                             name,
                                             mods.keys.toSeq,
                                             policyTypes,
                                             modId,
                                             actor,
                                             reason
                                           )
                                           .chainError(
                                             s"Error when automatically activating technique '${name.value}'"
                                           )
                                           .unit
                                     }

                                 }
                             }
                         }
    } yield {}).toBox
  }
}
