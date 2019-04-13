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

package com.normation.cfclerk.services.impl

import com.normation.cfclerk.services._
import com.normation.cfclerk.domain._
import net.liftweb.common._
import Box._
import java.io.{ File, InputStream }
import scala.collection.SortedSet
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.utils.StringUuidGenerator
import com.normation.utils.Control

import com.normation.errors._
import scalaz.zio._
import scalaz.zio.syntax._

class TechniqueRepositoryImpl(
    techniqueReader: TechniqueReader
  , refLibCallbacks: Seq[TechniquesLibraryUpdateNotification]
  , uuidGen        : StringUuidGenerator
) extends TechniqueRepository with UpdateTechniqueLibrary with Loggable {

  /**
   * Callback to call on technique lib update
   */
  private[this] var callbacks = refLibCallbacks.sortBy( _.order )


  /*
   * TechniquesInfo:
   * - techniquesCategory: Map[TechniqueId, TechniqueCategoryId]
   * - techniques: Map[TechniqueName, SortedMap[TechniqueVersion, Technique]]
   * - categories: SortedMap[TechniqueCategoryId, TechniqueCategory]
   */
  private[this] var techniqueInfosCache: TechniquesInfo = {
    /*
     * readTechniques result is updated only on
     * techniqueReader.getModifiedTechniques,
     * so we don't call that method at boot time.
     */
    try {
      techniqueReader.readTechniques
    } catch {
      case e:Exception =>
        val msg = "Error when loading the previously saved policy template library. Trying to update to last library available to overcome the error"
        logger.error(msg)
        this.update(ModificationId(uuidGen.newUuid), CfclerkEventActor, Some(msg))
        techniqueReader.readTechniques
    }
  }

  ////// end constructor /////


  /**
   * Register a new callback with a order.
   * Sort by order after each registration
   */
  override def registerCallback(callback:TechniquesLibraryUpdateNotification) : Unit = {
    callbacks = (callbacks :+ callback).sortBy( _.order )
  }

  override def update(modId: ModificationId, actor:EventActor, reason: Option[String]) : Box[Map[TechniqueName, TechniquesLibraryUpdateType]] = {
    try {
      val modifiedPackages = techniqueReader.getModifiedTechniques
      if (techniqueReader.needReload() || /* first time init */ null == techniqueInfosCache) {
        logger.info("Reloading technique library, " + {
          if (modifiedPackages.isEmpty) "no modified techniques found"
          else {
            val details = modifiedPackages.values.map {
              case TechniqueDeleted(name, versions) => s"['${name}': deleted (${versions.mkString(", ")})]"
              case TechniqueUpdated(name, mods)     => s"['${name}': updated (${mods.map(x => s"${x._1}: ${x._2}").mkString(", ")})]"
            }

            "found modified technique(s): " + details.mkString(", ")
          }
        })
        techniqueInfosCache = techniqueReader.readTechniques

        val res = Control.bestEffort(callbacks) { callback =>
          try {
            callback.updatedTechniques(techniqueInfosCache.gitRevId, modifiedPackages, modId, actor, reason)
          } catch {
            case e: Exception =>
              Failure(s"Error when executing callback '${callback.name}' for updated techniques: '${modifiedPackages.mkString(", ")}'", Full(e), Empty)
          }
        }

        res.map { _ => modifiedPackages }
      } else {
        logger.debug("Not reloading technique library as nothing changed since last reload")
        Full(modifiedPackages)
      }
    } catch {
      case e:Exception => Failure("Error when trying to read technique library", Full(e), Empty)
    }
  }


  override def getMetadataContent[T](techniqueId: TechniqueId)(useIt: Option[InputStream] => T): T =
    techniqueReader.getMetadataContent(techniqueId)(useIt)

  override def getFileContent[T](techniqueResourceId: TechniqueResourceId)(useIt: Option[InputStream] => T): T =
    techniqueReader.getResourceContent(techniqueResourceId, None)(useIt)

  override def getTemplateContent[T](techniqueResourceId: TechniqueResourceId)(useIt: Option[InputStream] => T): T =
    techniqueReader.getResourceContent(techniqueResourceId, Some(TechniqueTemplate.templateExtension))(useIt)

  /**
   * Return all the policies available
   * @return
   */
  override def getAll: Map[TechniqueId, Technique] = {
    (for {
      (id, versions) <- techniqueInfosCache.techniques
      (v, p) <- versions
    } yield {
      (TechniqueId(id, v), p)
    }).toMap
  }

  override def getTechniquesInfo() = techniqueInfosCache

  override def getTechniqueVersions(name: TechniqueName): SortedSet[TechniqueVersion] = {
    SortedSet[TechniqueVersion]() ++ techniqueInfosCache.techniques.get(name).toSeq.flatMap(_.keySet)
  }

  override def getByName(name:TechniqueName) : Map[TechniqueVersion, Technique] = {
    techniqueInfosCache.techniques.get(name).toSeq.flatten.toMap
  }

  /**
   * Retrieve the list of policies corresponding to the ids
   * @param techniqueIds : identifiers of the policies
   * @return : the list of policy objects
   * Throws an error if one policy ID does not match any known policy
   */
  override def getByIds(techniqueIds: Seq[TechniqueId]): Seq[Technique] = {
    techniqueIds.map(x => techniqueInfosCache.techniques(x.name)(x.version))
  }

  /**
   * Return a policy by its name
   * @param policyName
   * @return
   */
  override def get(techniqueId: TechniqueId): Option[Technique] = {
    val result = techniqueInfosCache.techniques.get(techniqueId.name).flatMap(versions => versions.get(techniqueId.version))
    if(!result.isDefined) {
      logger.debug("Required technique '%s' was not found".format(techniqueId))
    }
    result
  }

  override def getLastTechniqueByName(policyName: TechniqueName): Option[Technique] = {
    for {
      versions <- techniqueInfosCache.techniques.get(policyName)
    } yield {
      versions.last._2
    }
  }

  //////////////////////////////////// categories /////////////////////////////

  private def fileBreadCrump(target: File, current: File, stack: List[File]): List[File] = {

    if (current.getParentFile == target) target :: stack
    else fileBreadCrump(target, current.getParentFile, current :: stack)

  }

  override def getTechniqueLibrary: RootTechniqueCategory = techniqueInfosCache.rootCategory

  override def getTechniqueCategory(id: TechniqueCategoryId): IOResult[TechniqueCategory] = {
    id match {
      case RootTechniqueCategoryId     => this.techniqueInfosCache.rootCategory.succeed
      case sid: SubTechniqueCategoryId => this.techniqueInfosCache.subCategories.get(sid).notOptional(s"The category with id '${id.name}' was not found.")
    }
  }

  override def getParentTechniqueCategory_forTechnique(id: TechniqueId): IOResult[TechniqueCategory] = {
    for {
      cid <- this.techniqueInfosCache.techniquesCategory.get(id)
      cat <- cid match {
        case RootTechniqueCategoryId => Some(this.techniqueInfosCache.rootCategory)
        case sid: SubTechniqueCategoryId => this.techniqueInfosCache.subCategories.get(sid)
      }
    } yield {
      cat
    }
  }.notOptional(s"The parent category for '${id.toString}' was not found.")
}
