/*
 *************************************************************************************
 * Copyright 2017 Normation SAS
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

import better.files.File
import com.normation.cfclerk.domain.TechniqueId
import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.domain.TechniqueVersion
import com.normation.cfclerk.services.UpdateTechniqueLibrary
import com.normation.errors._
import com.normation.errors.IOResult
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.rudder.domain.logger.TechniqueWriterLoggerPure
import com.normation.rudder.domain.logger.TimingDebugLoggerPure
import com.normation.rudder.ncf.yaml.YamlTechniqueSerializer
import com.normation.rudder.repository.xml.TechniqueArchiver
import com.normation.zio.currentTimeMillis
import java.nio.charset.StandardCharsets
import zio._

/*
 * This service is in charge of writing an editor technique and it's related files.
 * This is a higher level api, that works at EditorTechnique level, and is able to generate all the low level stuff (calling rudderc if needed) like
 * metadata.xml, agent related files, etc
 */
trait TechniqueWriter {

  def deleteTechnique(
      techniqueName:    String,
      techniqueVersion: String,
      deleteDirective:  Boolean,
      modId:            ModificationId,
      committer:        EventActor
  ): IOResult[Unit]

  def writeTechniqueAndUpdateLib(
      technique: EditorTechnique,
      methods:   Map[BundleName, GenericMethod],
      modId:     ModificationId,
      committer: EventActor
  ): IOResult[EditorTechnique]

  // Write and commit all techniques files
  def writeTechnique(
      technique: EditorTechnique,
      methods:   Map[BundleName, GenericMethod],
      modId:     ModificationId,
      committer: EventActor
  ): IOResult[EditorTechnique]
}

/*
 * There is a configuration file option, `rudder.technique.compiler.app` that let you override
 * the choice for what app write techniques globally (techniqueCompilerApp).
 *
 * Then, you can locally override the choice for one technique with a local file `compilation.yaml`
 * in which you can force to fallback to `rudder` -> `rudder-only-unix` -> `webapp`.
 *
 * Finally, when `rudderc` is used, the webapp will look for its return code and generated files.
 * When the return code is XXXX, or if generated files doesn't pass a sanity check, then
 * the webapp is used to generate things. We also update the `compilation.yaml` to reflect the fact
 * that the last compilation used a fallback app and the error (if any)
 *
 * In that two last cases, then a big red persistent message is displayed in the technique editor
 * with the fact that a fallback was used.
 * In the last case, in addition, we need to display to the user the problem that he needs to report
 * to rudder dev.
 */
class TechniqueWriterImpl(
    archiver:            TechniqueArchiver,
    techLibUpdate:       UpdateTechniqueLibrary,
    deleteService:       DeleteEditorTechnique,
    techniqueSerializer: YamlTechniqueSerializer,
    compiler:            TechniqueCompiler,
    baseConfigRepoPath:  String // root of config repos
) extends TechniqueWriter {

  def deleteTechnique(
      techniqueName:    String,
      techniqueVersion: String,
      deleteDirective:  Boolean,
      modId:            ModificationId,
      committer:        EventActor
  ): IOResult[Unit] = deleteService.deleteTechnique(techniqueName, techniqueVersion, deleteDirective, modId, committer)

  def writeTechniqueAndUpdateLib(
      technique: EditorTechnique,
      methods:   Map[BundleName, GenericMethod],
      modId:     ModificationId,
      committer: EventActor
  ): IOResult[EditorTechnique] = {
    for {
      updatedTechnique <- writeTechnique(technique, methods, modId, committer)
      libUpdate        <-
        techLibUpdate
          .update(modId, committer, Some(s"Update Technique library after creating files for ncf Technique ${technique.name}"))
          .toIO
          .chainError(s"An error occurred during technique update after files were created for ncf Technique ${technique.name}")
    } yield {
      updatedTechnique
    }
  }

  // Write and commit all techniques files
  override def writeTechnique(
      technique: EditorTechnique,
      methods:   Map[BundleName, GenericMethod],
      modId:     ModificationId,
      committer: EventActor
  ): IOResult[EditorTechnique] = {
    for {
      time_0                      <- currentTimeMillis
      _                           <- TechniqueWriterLoggerPure.debug(s"Writing technique ${technique.name}")
      // Before writing down technique, set all resources to Untouched state, and remove Delete resources, was the cause of #17750
      updateResources              = technique.resources.collect {
                                       case r if r.state != ResourceFileState.Deleted => r.copy(state = ResourceFileState.Untouched)
                                     }
      techniqueWithResourceUpdated = technique.copy(resources = updateResources)

      json     <- writeYaml(techniqueWithResourceUpdated, methods)
      time_1   <- currentTimeMillis
      _        <-
        TimingDebugLoggerPure.trace(s"writeTechnique: writing yaml for technique '${technique.name}' took ${time_1 - time_0}ms")
      _        <- compiler.compileTechnique(techniqueWithResourceUpdated, methods)
      time_3   <- currentTimeMillis
      id       <- TechniqueVersion.parse(technique.version.value).toIO.map(v => TechniqueId(TechniqueName(technique.id.value), v))
      // resources files are missing the the "resources/" prefix
      resources = technique.resources.map(r => ResourceFile("resources/" + r.path, r.state))
      commit   <- archiver.saveTechnique(
                    id,
                    technique.category.split('/').toIndexedSeq,
                    Chunk.fromIterable(resources),
                    modId,
                    committer,
                    s"Committing technique ${technique.name}"
                  )
      time_4   <- currentTimeMillis
      _        <- TimingDebugLoggerPure.trace(s"writeTechnique: committing technique '${technique.name}' took ${time_4 - time_3}ms")
      _        <- TimingDebugLoggerPure.debug(s"writeTechnique: writing technique '${technique.name}' took ${time_4 - time_0}ms")

    } yield {
      techniqueWithResourceUpdated
    }
  }

  ///// utility methods /////

  def writeYaml(technique: EditorTechnique, methods: Map[BundleName, GenericMethod]): IOResult[String] = {
    val metadataPath = s"${technique.path}/technique.yml"
    val path         = s"${baseConfigRepoPath}/${metadataPath}"
    for {
      content <- techniqueSerializer.toYml(technique).toIO
      _       <- IOResult.attempt(s"An error occurred while creating yaml file for Technique '${technique.name}'") {
                   implicit val charSet = StandardCharsets.UTF_8
                   val file             = File(path).createFileIfNotExists(true)
                   file.write(content)
                 }
    } yield {
      metadataPath
    }
  }
}
