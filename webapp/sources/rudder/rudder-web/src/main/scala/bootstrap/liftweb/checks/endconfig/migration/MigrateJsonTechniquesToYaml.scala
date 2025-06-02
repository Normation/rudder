/*
 *************************************************************************************
 * Copyright 2023 Normation SAS
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

package bootstrap.liftweb.checks.endconfig.migration

import better.files.File
import bootstrap.liftweb.BootstrapChecks
import bootstrap.liftweb.BootstrapLogger
import com.normation.NamedZioLogger
import com.normation.cfclerk.services.UpdateTechniqueLibrary
import com.normation.errors.*
import com.normation.eventlog.ModificationId
import com.normation.rudder.domain.eventlog.RudderEventActor
import com.normation.rudder.ncf.*
import com.normation.rudder.ncf.migration.MigrateJsonTechniquesService
import com.normation.rudder.repository.xml.TechniqueFiles
import com.normation.utils.StringUuidGenerator
import com.normation.zio.*
import zio.*

/**
 * Check at webapp startup if ncf Technique needs to be rewritten by Rudder
 * Controlled by presence of flag file /opt/rudder/etc/force_ncf_technique_update
 */
class MigrateJsonTechniquesToYaml(
    techniqueWriter:                   TechniqueWriter,
    uuidGen:                           StringUuidGenerator,
    techLibUpdate:                     UpdateTechniqueLibrary,
    techniqueCompilationStatusService: ReadEditorTechniqueCompilationResult,
    rootConfigRepoDir:                 String
) extends BootstrapChecks {

  object TechniqueMigrationLogger extends NamedZioLogger {
    final override def loggerName: String = s"${BootstrapLogger.loggerName}.migration.techniques"
  }

  override val description = "Migrate technique.json to technique.yml"

  /*
   * Recursively get all path for `technique.json` files from currentPath.
   */
  def getAllTechniqueFiles(currentPath: File): IOResult[List[File]] = {
    for {
      subdirs      <- IOResult.attempt(s"Error when getting subdirectories of ${currentPath.pathAsString}") {
                        currentPath.children.partition(_.isDirectory)._1.toList
                      }
      checkSubdirs <- ZIO.foreach(subdirs)(getAllTechniqueFiles).map(_.flatten)
      techniqueFilePath: File = currentPath / TechniqueFiles.json
      result <- IOResult.attempt {
                  if (techniqueFilePath.exists) {
                    techniqueFilePath :: checkSubdirs
                  } else {
                    checkSubdirs
                  }
                }
    } yield {
      result
    }
  }

  /*
   * Migrate one techniques. The given path is the path of `technique.json`
   */
  def migrateOne(path: File): IOResult[Unit] = {
    for {
      _        <- TechniqueMigrationLogger.debug(s"Start migrating '${path.pathAsString}")
      techName <- IOResult.attempt(s"Error accessing technique directory for ${path.pathAsString}")(path.parent.parent.name)
      _        <- TechniqueMigrationLogger.info(s"Migrating technique '${techName}' to Rudder 8.0 yaml format")
      json     <- IOResult.attempt(s"Error when reading file '${path}'")(path.contentAsString)
      t        <- MigrateJsonTechniquesService.fromOldJsonTechnique(json).toIO
      _        <- TechniqueMigrationLogger.debug(
                    s"Technique JSON metadata `${path.pathAsString}` correctly read as an editor technique, migrating."
                  )
      // techniqueWriter (techniqueArchiver under the hood) will handle both migration of json file to yaml, and call to compiler
      _        <- techniqueWriter
                    .writeTechnique(t, ModificationId(uuidGen.newUuid), RudderEventActor)
                    .chainError(s"An error occurred while writing technique '${t.id.value}'")
      _        <- TechniqueMigrationLogger.info(s"Migration of technique '${techName}' to YAML format done")
    } yield ()
  }

  def updateNcfTechniques: ZIO[Any, RudderError, Unit] = {
    for {
      _              <- TechniqueMigrationLogger.info("Checking if some techniques need to be migrated to YAML format")
      jsonTechniques <- getAllTechniqueFiles(File(rootConfigRepoDir) / "techniques")
      _              <- ZIO.when(jsonTechniques.nonEmpty) {
                          TechniqueMigrationLogger.info(s"Found ${jsonTechniques.size} to migrate to YAML format")
                        }
      // Actually migrate techniques - don't fail on any migration, tell the user what to do
      _              <- ZIO.foreach(jsonTechniques) { t =>
                          migrateOne(t).catchAll { err =>
                            TechniqueMigrationLogger.warn(
                              s"An error occurred when migrating technique metadata '${t}'. Directives based on that technique may not work anymore and policy generation fail." +
                              s"Some files among technique.json, metadata.xml, technique.cf, technique.ps1 may have been altered. You can revert to pre-migration state using git. " +
                              s"The error was: ${err.fullMsg}"
                            )
                          }
                        }
      // Update technique library once all technique are updated
      libUpdate      <- techLibUpdate
                          .update(
                            ModificationId(uuidGen.newUuid),
                            RudderEventActor,
                            Some(s"Update Technique library after updating all techniques at start up")
                          )
                          .toIO
                          .chainError(s"An error occurred during techniques update after update of all techniques from the editor")
      // Update compilation status after every library change
      _              <- techniqueCompilationStatusService.get().unless(libUpdate.isEmpty)

    } yield ()
  }

  override def checks(): Unit = {

    ZioRuntime.runNowLogError(err => {
      BootstrapLogger.logEffect.error(
        s"An error occurred while migrating techniques based using a json file; error message is: ${err.fullMsg}"
      )
    })(updateNcfTechniques)
  }
}
