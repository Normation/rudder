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

package bootstrap.liftweb.checks.action

import better.files.File
import better.files.File.root
import bootstrap.liftweb.BootstrapChecks
import bootstrap.liftweb.BootstrapLogger
import com.normation.cfclerk.services.UpdateTechniqueLibrary
import com.normation.errors._
import com.normation.errors.IOResult
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.rudder.api.ApiAccount
import com.normation.rudder.ncf._
import com.normation.rudder.ncf.migration.MigrateOldTechniquesService
import com.normation.utils.StringUuidGenerator
import com.normation.zio._
import zio._
import zio.syntax._

/**
 * Check at webapp startup if ncf Technique needs to be rewritten by Rudder
 * Controlled by presence of flag file /opt/rudder/etc/force_ncf_technique_update
 */
class MigrateOldTechniques(
    techniqueWrite:  TechniqueWriter,
    systemApiToken:  ApiAccount,
    uuidGen:         StringUuidGenerator,
    techLibUpdate:   UpdateTechniqueLibrary,
    techniqueReader: EditorTechniqueReader
) extends BootstrapChecks {

  override val description = "Migrate technique.json to technique.yml"

  val conf_repo = root / "var" / "rudder" / "configuration-repository" / "techniques"
  override def checks(): Unit = {

    def getAllTechniqueFiles(currentPath: File): IOResult[List[File]] = {
      import com.normation.errors._
      for {
        subdirs      <- IOResult.attempt(s"error when getting subdirectories of ${currentPath.pathAsString}")(
                          currentPath.children.partition(_.isDirectory)._1.toList
                        )
        checkSubdirs <- ZIO.foreach(subdirs)(getAllTechniqueFiles).map(_.flatten)
        techniqueFilePath: File = currentPath / "technique.json"
        result <- IOResult.attempt(
                    if (techniqueFilePath.exists) {
                      techniqueFilePath :: checkSubdirs
                    } else {
                      checkSubdirs
                    }
                  )
      } yield {
        result
      }
    }

    def readTechniquesMetadataFile: IOResult[(List[EditorTechnique], Map[BundleName, GenericMethod])] = {
      for {
        methods        <- techniqueReader.getMethodsMetadata
        techniqueFiles <- getAllTechniqueFiles(conf_repo)
        techniques     <- ZIO.foreach(techniqueFiles) { file =>
                            for {
                              json <- IOResult.attempt(s"Error when reading file '${file}'")(file.contentAsString)
                              et   <- MigrateOldTechniquesService
                                        .readFromOldJsonTechnique(json)
                                        .toIO
                                        .chainError("An Error occurred while extracting data from techniques API")
                            } yield et
                          }
      } yield {
        (techniques, methods)
      }
    }

    import com.normation.errors._

    def updateNcfTechniques = {
      for {
        _                    <- BootstrapLogger.info("techniques - update")
        res                  <- readTechniquesMetadataFile
        (techniques, methods) = res
        _                    <- BootstrapLogger.info("techniques - read")

        // Actually write techniques
        written   <- ZIO.foreach(techniques) { t =>
                       techniqueWrite
                         .writeTechnique(t, ModificationId(uuidGen.newUuid), EventActor(systemApiToken.name.value))
                         .chainError(s"An error occurred while writing technique ${t.id.value}")
                     }
        // Actually write techniques
        allFiles  <- getAllTechniqueFiles(conf_repo)
        delete    <- ZIO.foreach(allFiles)(t => t.delete().succeed)
        // Update technique library once all technique are updated
        libUpdate <- techLibUpdate
                       .update(
                         ModificationId(uuidGen.newUuid),
                         EventActor(systemApiToken.name.value),
                         Some(s"Update Technique library after updating all techniques at start up")
                       )
                       .toIO
                       .chainError(s"An error occurred during techniques update after update of all techniques from the editor")

      } yield {
        techniques
      }
    }

    ZioRuntime.runNowLogError(err => {
      BootstrapLogger.logEffect.error(
        s"An error occurred while migrating techniques based using a json file; error message is: ${err.fullMsg}"
      )
    })(updateNcfTechniques)
  }
}
