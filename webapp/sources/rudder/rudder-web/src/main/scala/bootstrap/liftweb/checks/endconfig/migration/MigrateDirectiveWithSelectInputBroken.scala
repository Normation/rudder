/*
 *************************************************************************************
 * Copyright 2025 Normation SAS
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

import bootstrap.liftweb.BootstrapChecks
import bootstrap.liftweb.BootstrapLogger
import com.normation.cfclerk.domain.TechniqueName
import com.normation.errors.*
import com.normation.eventlog.ModificationId
import com.normation.rudder.domain.eventlog.RudderEventActor
import com.normation.rudder.domain.policies.ActiveTechniqueId
import com.normation.rudder.ncf.*
import com.normation.rudder.repository.RoDirectiveRepository
import com.normation.rudder.repository.WoDirectiveRepository
import com.normation.utils.StringUuidGenerator
import com.normation.zio.*
import zio.*
import zio.syntax.ToZio

/**
 * Check at webapp startup if ncf Technique needs to be rewritten by Rudder
 * Controlled by presence of flag file /opt/rudder/etc/force_ncf_technique_update
 */
class MigrateDirectiveWithSelectInputBroken(
    techniqueReader:       EditorTechniqueReader,
    roDirectiveRepository: RoDirectiveRepository,
    writeDirectives:       WoDirectiveRepository,
    uuidGenerator:         StringUuidGenerator
) extends BootstrapChecks {

  override val description = "Migrate directives with invalid parameters"

  def updateDirectivesWithSelectInputBroken: ZIO[Any, RudderError, Unit] = {
    val modificationId = ModificationId(uuidGenerator.newUuid)
    for {
      res        <- techniqueReader.readTechniquesMetadataFile
      techniques  = res.techniques.filter(_.parameters.exists(_.constraints.exists(_.select.isDefined)))
      directives <- ZIO.foreach(techniques) { t =>
                      val selectParameters = t.parameters.filter(_.constraints.exists(_.select.isDefined))
                      for {
                        res                 <- roDirectiveRepository
                                                 .getActiveTechnique(TechniqueName(t.id.value))
                                                 .map(_.map(at => (at.id, at.directives)).getOrElse((ActiveTechniqueId("notFound"), Nil)))
                        (atId, directiveIds) = res
                        directives          <- ZIO.foreach(directiveIds)(roDirectiveRepository.getDirective).map(_.flatten)
                        directiveToUpdate    = directives.filter(_.parameters.exists(p => selectParameters.exists(_.name == p._1)))
                        _                   <-
                          BootstrapLogger.info(
                            s"Some directives (${directiveToUpdate.size}) needs to be updated, directive ids: ${directiveToUpdate.map(_.id.serialize).mkString(", ")}"
                          )
                        updatedDirectives    = {
                          directiveToUpdate.map(d => {
                            selectParameters.foldLeft(d) {
                              case (directive, param) =>
                                directive.parameters.get(param.name) match {
                                  case None        => directive
                                  case Some(value) =>
                                    directive.copy(parameters =
                                      directive.parameters.removed(param.name).updated(param.id.value.toUpperCase, value)
                                    )
                                }
                            }
                          })
                        }
                        saved               <- ZIO.foreach(updatedDirectives)(d => {
                                                 writeDirectives
                                                   .saveDirective(
                                                     atId,
                                                     d,
                                                     modificationId,
                                                     RudderEventActor,
                                                     Some(s"Updating invalid parameters in directive ${d.id.serialize}")
                                                   )
                                                   .map(_ => Right(d.id))
                                                   .catchAll(err => Left((d.id, err)).succeed)
                                               })
                        (err, ok)            = saved.partitionMap(identity(_))
                        _                   <- if (err.isEmpty) BootstrapLogger.info("All directives were migrated")
                                               else {
                                                 BootstrapLogger.info(
                                                   s"Some directives (${ok.size}) were migrated, ids : ${ok.map(_.serialize).mkString(", ")}"
                                                 ) *>
                                                 BootstrapLogger.error(
                                                   s"Some directives (${err.size}) were not migrated, errors below, ids : ${err.map(_._1.serialize) mkString (", ")}"
                                                 ) *>
                                                 ZIO.foreach(err) {
                                                   case (id, e) => BootstrapLogger.error(s"For directive ${id.serialize}, error is: ${e.msg}")
                                                 }
                                               }
                      } yield {}
                    }
    } yield ()
  }

  override def checks(): Unit = {

    ZioRuntime.runNowLogError(err => {
      BootstrapLogger.logEffect.error(
        s"An error occurred while migrating directives with wrong select parameters: ${err.fullMsg}"
      )
    })(updateDirectivesWithSelectInputBroken)
  }
}
