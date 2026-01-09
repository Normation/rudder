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
import com.normation.errors.*
import com.normation.rudder.domain.logger.TechniqueWriterLoggerPure
import com.normation.rudder.domain.logger.TimingDebugLoggerPure
import com.normation.rudder.ncf.eventlogs.AddEditorTechniqueDiff
import com.normation.rudder.ncf.eventlogs.ModifyEditorTechniqueDiff
import com.normation.rudder.repository.EventLogRepository
import com.normation.rudder.repository.xml.TechniqueArchiver
import com.normation.rudder.repository.xml.TechniqueFiles
import com.normation.rudder.tenants.ChangeContext
import com.normation.utils.FileUtils
import com.normation.zio.currentTimeMillis
import java.nio.charset.StandardCharsets
import zio.*
import zio.json.yaml.*

/*
 * This service is in charge of writing an editor technique and it's related files.
 * This is a higher level api, that works at EditorTechnique level, and is able to generate all the low level stuff (calling rudderc if needed) like
 * metadata.xml, agent related files, etc
 */
trait TechniqueWriter {

  def deleteTechnique(
      techniqueName:    String,
      techniqueVersion: String,
      deleteDirective:  Boolean
  )(implicit cc: ChangeContext): IOResult[Unit]

  def writeTechniqueAndUpdateLib(
      technique: EditorTechnique
  )(implicit cc: ChangeContext): IOResult[EditorTechnique]

  // Write and commit all techniques files
  def writeTechnique(
      technique: EditorTechnique
  )(implicit cc: ChangeContext): IOResult[EditorTechnique]

  def writeTechniques(
      techniques: List[EditorTechnique]
  )(implicit cc: ChangeContext): IOResult[List[EditorTechnique]]
}

/**
  * The implementation of that handles updating or creating the technique YAML file
  * which is kept on the file system. It delegates to other services for update and delete,
  * and archives to keep the file system up-to-date, and synces the global compilation state
  */
class TechniqueWriterImpl(
    archiver:                 TechniqueArchiver,
    techLibUpdate:            UpdateTechniqueLibrary,
    deleteService:            DeleteEditorTechnique,
    compiler:                 TechniqueCompiler,
    compilationStatusService: TechniqueCompilationStatusSyncService,
    techniqueReader:          EditorTechniqueReader,
    actionLogger:             EventLogRepository,
    baseConfigRepoPath:       String // root of config repos
) extends TechniqueWriter {

  def deleteTechnique(
      techniqueName:    String,
      techniqueVersion: String,
      deleteDirective:  Boolean
  )(implicit cc: ChangeContext): IOResult[Unit] = {
    deleteService.deleteTechnique(techniqueName, techniqueVersion, deleteDirective)
  }

  def writeTechniqueAndUpdateLib(
      technique: EditorTechnique
  )(implicit cc: ChangeContext): IOResult[EditorTechnique] = {
    for {
      updated              <-
        compileArchiveTechnique(technique, syncStatus = false) // sync is already done in library update
      (updatedTechnique, _) = updated
      _                    <-
        techLibUpdate
          .update()
          .toIO
          .chainError(s"An error occurred during technique update after files were created for ncf Technique ${technique.name}")
    } yield {
      updatedTechnique
    }
  }

  override def writeTechnique(
      technique: EditorTechnique
  )(implicit cc: ChangeContext): IOResult[EditorTechnique] = {
    compileArchiveTechnique(technique).map { case (t, _) => t }
  }

  override def writeTechniques(
      techniques: List[EditorTechnique]
  )(implicit cc: ChangeContext): IOResult[List[EditorTechnique]] = {
    for {
      updated                     <- ZIO.foreach(techniques)(compileArchiveTechnique(_, syncStatus = false))
      (updatedTechniques, results) = updated.unzip
      _                           <- compilationStatusService.getUpdateAndSync(Some(results))
    } yield {
      updatedTechniques
    }
  }

  ///// utility methods /////

  // Write and commit all techniques files
  private def compileArchiveTechnique(
      technique:  EditorTechnique,
      syncStatus: Boolean = true // should update the compilation status ?
  )(implicit cc: ChangeContext): IOResult[(EditorTechnique, EditorTechniqueCompilationResult)] = {
    for {
      time_0                      <- currentTimeMillis
      existingTechnique           <- techniqueReader.getTechnique(technique.id, technique.version.value)
      time_1                      <- currentTimeMillis
      _                           <-
        TimingDebugLoggerPure.trace(s"writeTechnique: getting existing technique '${technique.name}' took ${time_1 - time_0}ms")
      _                           <- TechniqueWriterLoggerPure.debug(s"Writing technique '${technique.name}'")
      // Before writing down technique, set all resources to Untouched state, and remove Delete resources, was the cause of #17750
      updateResources              = technique.resources.collect {
                                       case r if r.state != ResourceFileState.Deleted => r.copy(state = ResourceFileState.Untouched)
                                     }
      techniqueWithResourceUpdated = technique.copy(resources = updateResources)

      _                <- TechniqueWriterImpl.writeYaml(techniqueWithResourceUpdated)(baseConfigRepoPath)
      time_2           <- currentTimeMillis
      _                <-
        TimingDebugLoggerPure.trace(s"writeTechnique: writing yaml for technique '${technique.name}' took ${time_2 - time_1}ms")
      compiled         <- compiler.compileTechnique(techniqueWithResourceUpdated)
      compilationResult = EditorTechniqueCompilationResult.from(techniqueWithResourceUpdated, compiled)
      _                <- compilationStatusService.syncOne(compilationResult)
      time_3           <- currentTimeMillis
      _                <- TimingDebugLoggerPure.trace(s"writeTechnique: compiling technique '${technique.name}' took ${time_3 - time_2}ms")
      id               <- TechniqueVersion.parse(technique.version.value).toIO.map(v => TechniqueId(TechniqueName(technique.id.value), v))
      // resources files are missing the "resources/" prefix
      resources         = technique.resources.map(r => ResourceFile("resources/" + r.path, r.state))
      _                <- archiver.saveTechnique(
                            id,
                            technique.category.split('/').toIndexedSeq,
                            Chunk.fromIterable(resources),
                            cc.modId,
                            cc.actor,
                            s"Committing technique ${technique.name}"
                          )
      time_4           <- currentTimeMillis
      _                <- TimingDebugLoggerPure.trace(s"writeTechnique: committing technique '${technique.name}' took ${time_4 - time_3}ms")
      _                <- TimingDebugLoggerPure.debug(s"writeTechnique: writing technique '${technique.name}' took ${time_4 - time_0}ms")
      time_5           <- currentTimeMillis
      _                <-
        TimingDebugLoggerPure.trace(s"writeTechnique: writing technique '${technique.name}' in cache took ${time_5 - time_4}ms")
      logged           <- existingTechnique match {
                            case Some(previous) =>
                              val diff = ModifyEditorTechniqueDiff(previous, technique)
                              actionLogger.saveModifyEditorTechnique(
                                cc.modId,
                                principal = cc.actor,
                                modifyDiff = diff,
                                reason = cc.message
                              )
                            case None           =>
                              val diff = AddEditorTechniqueDiff(technique)
                              actionLogger.saveAddEditorTechnique(
                                cc.modId,
                                principal = cc.actor,
                                addDiff = diff,
                                reason = cc.message
                              )
                          }
    } yield {
      (techniqueWithResourceUpdated, compilationResult)
    }
  }
}

object TechniqueWriterImpl {

  /**
    * write the given EditorTechnique as YAML file in ${technique.path}/technique.yml
    * Returns the relative path to the technique YAML file from the baseConfigRepo
    */
  private[ncf] def writeYaml(technique: EditorTechnique)(basePath: String): IOResult[String] = {
    import com.normation.rudder.ncf.yaml.YamlTechniqueSerializer.*

    val metadataPath = s"${technique.path}/${TechniqueFiles.yaml}"

    for {
      path    <- FileUtils.sanitizePath(File(basePath), metadataPath)
      content <- technique.toYaml().toIO
      _       <- IOResult.attempt(s"An error occurred while creating yaml file for Technique '${technique.name}'") {
                   implicit val charSet = StandardCharsets.UTF_8
                   val file             = path.createFileIfNotExists(createParents = true)
                   file.write(content)
                 }
    } yield {
      metadataPath
    }
  }
}
