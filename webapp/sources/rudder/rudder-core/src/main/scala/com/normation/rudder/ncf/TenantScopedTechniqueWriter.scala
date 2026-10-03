/*
 *************************************************************************************
 * Copyright 2026 Normation SAS
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

package com.normation.rudder.ncf

import com.normation.cfclerk.domain.Technique
import com.normation.cfclerk.domain.TechniqueCategoryId
import com.normation.cfclerk.domain.TechniqueCategoryMetadata
import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.services.TechniqueRepository
import com.normation.errors.*
import com.normation.rudder.tenants.ChangeContext
import com.normation.rudder.tenants.Container
import com.normation.rudder.tenants.IfAbsent
import com.normation.rudder.tenants.Lookup
import com.normation.rudder.tenants.SecurityTag
import com.normation.rudder.tenants.TenantCheckLogic
import com.softwaremill.quicklens.*
import zio.*

/*
 * The proxy class for techniques on FS, equivalent to TenantFiltering repository for LDAP repositories.
 *
 * System paths (library reload, `TechniqueLibraryTenantSync`, plugin technique installation) use the raw
 * writers, never these.
 */
class TenantScopedTechniqueWriter(
    underlying:    TechniqueWriter,
    techniqueRepo: TechniqueRepository,
    checkTenant:   TenantCheckLogic
) extends TechniqueWriter {

  import com.normation.rudder.ncf.TenantScopedTechniqueWriter.*

  /*
   * A technique name owns one directory on disk, so every version of it declares the same tenants.
   * The lookup joins them as it is done in the active technique
   * (`TechniqueLibraryTenantSync.declaredFor`).
   */
  private def stored(name: String): Lookup[Technique] = {
    ZIO.succeed(joinVersions(techniqueRepo.getByName(TechniqueName(name)).values))
  }

  // the category a technique is written into, as the law needs to see it
  private def into(categoryPath: String): Container[TechniqueCategoryMetadata] = {
    TechniqueCategoryId
      .parse(categoryPath)
      .toIO
      .flatMap(techniqueRepo.getTechniqueCategory)
      .map(c => TechniqueCategoryMetadata(c.name, c.description, c.isSystem, c.security))
  }

  override def deleteTechnique(
      techniqueName:    String,
      techniqueVersion: String,
      deleteDirective:  Boolean
  )(implicit cc: ChangeContext): IOResult[Unit] = {
    checkTenant.manageDelete(stored(techniqueName), IfAbsent(())) { _ =>
      underlying.deleteTechnique(techniqueName, techniqueVersion, deleteDirective)
    }
  }

  override def writeTechniqueAndUpdateLib(technique: EditorTechnique)(implicit cc: ChangeContext): IOResult[EditorTechnique] = {
    save(technique)(underlying.writeTechniqueAndUpdateLib)
  }

  override def writeTechnique(technique: EditorTechnique)(implicit cc: ChangeContext): IOResult[EditorTechnique] = {
    save(technique)(underlying.writeTechnique)
  }

  override def writeTechniques(techniques: List[EditorTechnique])(implicit cc: ChangeContext): IOResult[List[EditorTechnique]] = {
    // each technique is authorized on its own: a batch is not a way to write one the actor may not write
    ZIO.foreach(techniques)(t => save(t)(x => underlying.writeTechniques(x :: Nil).map(_.head)))
  }

  /*
   * Writing a technique creates it when its name is free and updates it otherwise, so it is a save.
   * `manageSave` gives back the object with the tag the actor may actually give it, and that tag is the
   * one written.
   */
  private def save(
      technique: EditorTechnique
  )(action: ChangeContext ?=> EditorTechnique => IOResult[EditorTechnique])(implicit
      cc:        ChangeContext
  ): IOResult[EditorTechnique] = {
    checkTenant.manageSave(technique, stored(technique.id.value), into(technique.category))(action)
  }
}

object TenantScopedTechniqueWriter {

  /*
   * The versions of one technique name as a single object for the law: the last version, carrying the join
   * of the tags every version declares. None when the name is not in the library.
   */
  def joinVersions(versions: Iterable[Technique]): Option[Technique] = {
    if (versions.isEmpty) None
    else {
      val joined = SecurityTag.joinAll(versions)
      versions.lastOption.map(_.modify(_.security).setTo(joined))
    }
  }
}

/*
 * Same law for the categories of the reference library.
 */
class TenantScopedTechniqueCategoryWriter(
    underlying:    TechniqueCategoryWriter,
    techniqueRepo: TechniqueRepository,
    checkTenant:   TenantCheckLogic
) extends TechniqueCategoryWriter {

  private def stored(id: TechniqueCategoryId): Lookup[TechniqueCategoryMetadata] = {
    techniqueRepo
      .getTechniqueCategory(id)
      .map(c => Some(TechniqueCategoryMetadata(c.name, c.description, c.isSystem, c.security)))
      .catchAll(_ => ZIO.none)
  }

  override def createCategory(parent: TechniqueCategoryId, name: String, description: String, security: Option[SecurityTag])(
      implicit cc: ChangeContext
  ): IOResult[TechniqueCategoryInfo] = {
    // we store the tag that `manageCreate` gives back in the metadata
    checkTenant.manageCreate(
      TechniqueCategoryMetadata(name, description, isSystem = false, security),
      stored(parent).notOptional(s"Technique category '${parent.toString}' was not found")
    )(c => underlying.createCategory(parent, name, description, c.security))
  }

  override def updateCategory(id: TechniqueCategoryId, name: Option[String], description: Option[String])(implicit
      cc: ChangeContext
  ): IOResult[TechniqueCategoryInfo] = {
    checkTenant.manageModify(stored(id), IfAbsent.fail(s"Technique category '${id.toString}' was not found")) { _ =>
      underlying.updateCategory(id, name, description)
    }
  }

  override def deleteCategory(id: TechniqueCategoryId)(implicit cc: ChangeContext): IOResult[Unit] = {
    checkTenant.manageDelete(stored(id), IfAbsent(()))(_ => underlying.deleteCategory(id))
  }
}
