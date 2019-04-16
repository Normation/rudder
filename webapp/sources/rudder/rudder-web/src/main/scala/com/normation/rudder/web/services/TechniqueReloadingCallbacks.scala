/*
*************************************************************************************
* Copyright 2013 Normation SAS
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

package com.normation.rudder.web.services

import com.normation.cfclerk.domain.TechniqueId
import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.services.TechniquesLibraryUpdateNotification
import com.normation.cfclerk.services.TechniquesLibraryUpdateType
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.rudder.repository.RoDirectiveRepository
import com.normation.rudder.repository.WoDirectiveRepository
import com.normation.utils.Control._
import net.liftweb.common.Loggable
import net.liftweb.common.Full
import net.liftweb.common.Box

import com.normation.box._

/**
 * When a technique is updated, we may need to save back
 * the Directive implementing it because the metadata.xml
 * file may have changed.
 * For example, a new optionnal variable may have been added.
 *
 * OF COURSE that should NEVER happen without a version number
 * update, but things happen.
 */
class SaveDirectivesOnTechniqueCallback(
    override val name     : String
  , override val order    : Int
  , directiveEditorService: DirectiveEditorService
  , roDirectiveRepo       : RoDirectiveRepository
  , woDirectiveRepo       : WoDirectiveRepository
) extends TechniquesLibraryUpdateNotification with Loggable {

  override def updatedTechniques(gitRevId: String, techniqueIds: Map[TechniqueName, TechniquesLibraryUpdateType], modId:ModificationId, actor:EventActor, reason: Option[String]) : Box[Unit] = {

    for {
      techLib  <- roDirectiveRepo.getFullDirectiveLibrary().toBox
      updated  <- bestEffort(techLib.allDirectives.values.toSeq) { case(inActiveTechnique, directive) =>
                    techniqueIds.get(inActiveTechnique.techniqueName) match {
                      case Some(mods) =>
                        for {
                          paramEditor  <- directiveEditorService.get(TechniqueId(inActiveTechnique.techniqueName, directive.techniqueVersion), directive.id, directive.parameters)
                          newDirective =  directive.copy(parameters = paramEditor.mapValueSeq)
                          saved        <- if(directive.isSystem) {
                                            woDirectiveRepo.saveSystemDirective(inActiveTechnique.id, newDirective, modId, actor, reason).toBox
                                          } else {
                                            woDirectiveRepo.saveDirective(inActiveTechnique.id, newDirective, modId, actor, reason).toBox
                                          }
                        } yield {
                          logger.debug(s"Technique ${inActiveTechnique.techniqueName.value} changed => saving directive '${directive.name}' [${directive.id.value}]")
                        }
                      case None =>
                        Full("Nothing to do")
                    }
                  }
    } yield {
      ()
    }
  }
}
