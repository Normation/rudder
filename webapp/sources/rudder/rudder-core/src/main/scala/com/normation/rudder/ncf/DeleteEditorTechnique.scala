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

package com.normation.rudder.ncf

import better.files.File
import com.normation.cfclerk.domain
import com.normation.cfclerk.domain.SectionSpec
import com.normation.cfclerk.domain.TechniqueId
import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.domain.TechniqueVersion
import com.normation.cfclerk.services.TechniqueRepository
import com.normation.cfclerk.services.UpdateTechniqueLibrary
import com.normation.errors.*
import com.normation.eventlog.ModificationId
import com.normation.inventory.domain.Version
import com.normation.rudder.domain.logger.ApplicationLogger
import com.normation.rudder.domain.policies.DeleteDirectiveDiff
import com.normation.rudder.domain.policies.Directive
import com.normation.rudder.domain.workflows.ConfigurationChangeRequest
import com.normation.rudder.repository.RoDirectiveRepository
import com.normation.rudder.repository.WoDirectiveRepository
import com.normation.rudder.repository.xml.TechniqueArchiver
import com.normation.rudder.services.workflows.ChangeRequestService
import com.normation.rudder.services.workflows.WorkflowLevelService
import com.normation.rudder.tenants.ChangeContext
import com.normation.rudder.tenants.QueryContext
import zio.*
import zio.syntax.*

/*
 * Deleting a technique is complicated and needs its own service that will check on directives/etc.
 * It is implemented apart to keep things testable and simplify dealing with each parts.
 */
trait DeleteEditorTechnique {

  def deleteTechnique(
      techniqueName:    String,
      techniqueVersion: String,
      deleteDirective:  Boolean,
      modId:            ModificationId,
      committer:        QueryContext
  ): IOResult[Unit]
}

class DeleteEditorTechniqueImpl(
    archiver:                 TechniqueArchiver,
    techLibUpdate:            UpdateTechniqueLibrary,
    readDirectives:           RoDirectiveRepository,
    writeDirectives:          WoDirectiveRepository,
    techniqueRepository:      TechniqueRepository,
    workflowLevelService:     WorkflowLevelService,
    compilationStatusService: TechniqueCompilationStatusSyncService,
    baseConfigRepoPath:       String // root of config repos
) extends DeleteEditorTechnique {
  // root of technique repository
  val techniquesDir: File = File(baseConfigRepoPath) / "techniques"

  override def deleteTechnique(
      techniqueName:    String,
      techniqueVersion: String,
      deleteDirective:  Boolean,
      modId:            ModificationId,
      qc:               QueryContext
  ): IOResult[Unit] = {

    def createCr(directive: Directive, rootSection: SectionSpec) = {
      val diff = DeleteDirectiveDiff(TechniqueName(techniqueName), directive)
      ChangeRequestService.createChangeRequestFromDirective(
        s"Deleting technique ${techniqueName}/${techniqueVersion}",
        "",
        TechniqueName(techniqueName),
        Some(rootSection),
        directive.id,
        Some(directive),
        diff,
        qc.actor,
        None
      )
    }

    def mergeCrs(cr1: ConfigurationChangeRequest, cr2: ConfigurationChangeRequest) = {
      cr1.copy(directives = cr1.directives ++ cr2.directives)
    }

    def removeTechnique(techniqueId: TechniqueId, technique: domain.Technique): IOResult[Unit] = {
      implicit val cc: ChangeContext = qc.newCC(Some(s"Deleting technique '${techniqueId.serialize}'")).copy(modId = modId)
      for {
        directives <- readDirectives
                        .getFullDirectiveLibrary()
                        .map(
                          _.allActiveTechniques.values
                            .filter(_.techniqueName.value == techniqueId.name.value)
                            .flatMap(_.directives)
                            .filter(_.techniqueVersion == techniqueId.version)
                        )
        categories <- techniqueRepository.getTechniqueCategoriesBreadCrump(techniqueId)
        // Check if we have directives, and either, make an error, if we don't force deletion, or delete them all, creating a change request
        _          <- directives match {
                        case Nil => ZIO.unit
                        case _   =>
                          if (deleteDirective) {
                            val wf = workflowLevelService.getWorkflowService()
                            for {
                              cr <- directives
                                      .map(createCr(_, technique.rootSection))
                                      .reduceOption(mergeCrs)
                                      .notOptional(s"Could not create a change request to delete '${techniqueId.serialize}' directives")
                              _  <- wf.startWorkflow(cr)
                            } yield ()
                          } else {
                            Unexpected(
                              s"${directives.size} directives are defined for '${techniqueId.serialize}': please delete them, or force deletion"
                            ).fail
                          }
                      }
        activeTech <- readDirectives.getActiveTechnique(techniqueId.name)
        _          <- activeTech match {
                        case None                  =>
                          // No active technique found, let's delete it
                          ().succeed
                        case Some(activeTechnique) =>
                          writeDirectives.deleteActiveTechnique(
                            activeTechnique.id,
                            modId,
                            qc.actor,
                            Some(s"Deleting active technique '${techniqueId.name.value}'")
                          )
                      }
        // at some point we will likely want to call rudderc when it was the compiler to clean thing in archiver
        _          <- archiver.deleteTechnique(
                        techniqueId,
                        categories.map(_.id.name.value),
                        modId,
                        qc.actor,
                        s"Deleting technique '${techniqueId}'"
                      )
        _          <- techLibUpdate
                        .update()(using cc.withMsg(s"Update Technique library after deletion of technique '${technique.name}'"))
                        .toIO
                        .chainError(
                          s"An error occurred during technique update after deletion of Technique ${technique.name}"
                        )
      } yield ()
    }

    def removeInvalidTechnique(techniquesDir: File, techniqueId: TechniqueId): IOResult[Unit] = {
      val unknownTechniquesDir =
        techniquesDir.listRecursively.filter(_.isDirectory).filter(_.name == techniqueId.name.value).toList
      unknownTechniquesDir.length match {
        case 0 =>
          ApplicationLogger.debug(s"No technique `${techniqueId.debugString}` found to delete").succeed
        case _ =>
          for {
            _ <- ZIO.foreach(unknownTechniquesDir) { f =>
                   val cat = f.pathAsString
                     .substring((techniquesDir.pathAsString + "/").length)
                     .split("/")
                     .filter(s => s != techniqueName && s != techniqueVersion)
                     .toList
                   for {
                     _ <-
                       archiver
                         .deleteTechnique(
                           techniqueId,
                           cat,
                           modId,
                           qc.actor,
                           s"Deleting invalid technique ${techniqueName}/${techniqueVersion}"
                         )
                         .chainError(
                           s"Error when trying to delete invalids techniques, you can manually delete them by running these commands in " +
                           s"${techniquesDir.pathAsString}: `rm -rf ${f.pathAsString} && git commit -m 'Deleting invalid technique ${f.pathAsString}' && reload-techniques"
                         )
                     _ <- techLibUpdate
                            .update()(using
                              qc.newCC(
                                Some(s"Update Technique library after deletion of invalid Technique ${techniqueName}")
                              )
                            )
                            .toIO
                            .chainError(
                              s"An error occurred during technique update after deletion of Technique ${techniqueName}"
                            )
                   } yield ()
                 }
          } yield ()
      }
    }

    for {
      techVersion <- TechniqueVersion.parse(techniqueVersion).toIO
      techniqueId  = TechniqueId(TechniqueName(techniqueName), techVersion)
      _           <- techniqueRepository.get(techniqueId) match {
                       case Some(technique) => removeTechnique(techniqueId, technique)
                       case None            => removeInvalidTechnique(techniquesDir, techniqueId)
                     }

      _ <- compilationStatusService.unsyncOne(
             BundleName(techniqueName) -> new Version(techniqueVersion)
           ) // to delete the status of the technique
    } yield ()
  }
}
