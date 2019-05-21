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

package com.normation.cfclerk.services

import com.normation.cfclerk.domain._
import java.io.InputStream
import scala.collection.SortedSet

import com.normation.errors._

/**
 * A service that provides information about the policy packages
 *
 *
 */
trait TechniqueRepository {

  /**
   * Retrieve the metadata file content
   * (for example,to display it)
   */
  def getMetadataContent[T](techniqueId: TechniqueId)(useIt: Option[InputStream] => T): T

  /**
   * Get the template content for the given id
   */
  def getTemplateContent[T](techniqueResourceId: TechniqueResourceId)(useIt: Option[InputStream] => T): T

  /**
   * Get the file content for the given id
   */
  def getFileContent[T](techniqueResourceId: TechniqueResourceId)(useIt: Option[InputStream] => T): T


  /*
   * Return the full information about technique.
   * That TechniquesInfo data structure is self-consistent by construction,
   * so any category reference as a subcategory of an other is
   * defined in the map, all techniques in categories is also accessible. etc.
   */
  def getTechniquesInfo(): TechniquesInfo

  /**
   * Return all the policies available
   */
  def getAll(): Map[TechniqueId, Technique]

  /**
   * Return a policy by its
   * @param techniqueId
   * @return
   */
  def get(techniqueId: TechniqueId): Option[Technique]

  /**
   * Return a policy found by its name.
   * If several versions of that policy are available,
   * the most recent version is used
   */
  def getLastTechniqueByName(techniqueName: TechniqueName): Option[Technique]

  /**
   * Retrieve a the list of policies corresponding to the names
   * @param techniqueIds : the names of the policies
   * @return : the list of policy objects
   */
  def getByIds(techniqueIds: Seq[TechniqueId]): Seq[Technique]

  /**
   * For the given TechniqueName, retrieve all available
   * versions.
   * If the policyName is unknown, the returned collection will
   * be empty.
   */
  def getTechniqueVersions(name:TechniqueName) : SortedSet[TechniqueVersion]

  /**
   * Get the list of technique (with their version) for that name
   */
  def getByName(name:TechniqueName) : Map[TechniqueVersion, Technique]

  ////////////////// method for categories //////////////////

  def getTechniqueLibrary: RootTechniqueCategory

  def getTechniqueCategory(id: TechniqueCategoryId): IOResult[TechniqueCategory]

  def getParentTechniqueCategory_forTechnique(id: TechniqueId): IOResult[TechniqueCategory]

  final def getTechniqueCategoriesBreadCrump(id: TechniqueId): IOResult[Seq[TechniqueCategory]] = {
    for {
      cat  <- getParentTechniqueCategory_forTechnique(id)
      path <- scalaz.zio.ZIO.foreach(cat.id.getIdPathFromRoot) { currentCatId =>
        getTechniqueCategory(currentCatId).chainError(s"'${currentCatId.name}' category was not found but should be a parent of '${cat.id.name}'. This is likely an error, please report it.")
      }
    } yield {
      path
    }
  }
}
