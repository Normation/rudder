/*
*************************************************************************************
* Copyright 2013 Normation SAS
*************************************************************************************
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Affero General Public License as
* published by the Free Software Foundation, either version 3 of the
* License, or (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU Affero GPL v3, the copyright holders add the following
* Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU Affero GPL v3
* licence, when you create a Related Module, this Related Module is
* not considered as a part of the work and may be distributed under the
* license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Affero General Public License for more details.
*
* You should have received a copy of the GNU Affero General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/agpl.html>.
*
*************************************************************************************
*/

package com.normation.rudder.services.policies

import com.normation.cfclerk.domain.TechniqueId
import com.normation.cfclerk.services.TechniquesLibraryUpdateNotification
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.rudder.repository.RoDirectiveRepository
import com.normation.rudder.repository.WoDirectiveRepository
import com.normation.rudder.web.services.DirectiveEditorService
import com.normation.utils.Control._

import net.liftweb.common.EmptyBox
import net.liftweb.common.Loggable


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
    override val name: String
  , directiveEditorService: DirectiveEditorService
  , roDirectiveRepo       : RoDirectiveRepository
  , woDirectiveRepo       : WoDirectiveRepository
) extends TechniquesLibraryUpdateNotification with Loggable {

  override def updatedTechniques(techniqueIds:Seq[TechniqueId], modId:ModificationId, actor:EventActor, reason: Option[String]) : Unit = {
    val updatedTechniques = techniqueIds.map(x => (x.name, x.version)).toMap

    (for {
      techLib  <- roDirectiveRepo.getFullDirectiveLibrary()
      toUpdate = techLib.allDirectives.values.filter { case (activeTechnique, directive) => updatedTechniques.get(activeTechnique.techniqueName) == Some(directive.techniqueVersion) }
      updated  <- bestEffort(toUpdate.toSeq) { case(inActiveTechnique, directive) =>
                    for {
                      paramEditor  <- directiveEditorService.get(TechniqueId(inActiveTechnique.techniqueName, directive.techniqueVersion), directive.id, directive.parameters)
                      newDirective =  directive.copy(parameters = paramEditor.mapValueSeq)
                      saved        <- woDirectiveRepo.saveDirective(inActiveTechnique.id, newDirective, modId, actor, reason)
                    } yield {
                      logger.debug(s"Technique ${inActiveTechnique.techniqueName.value} changed => saving directive '${directive.name}' [${directive.id.value}]")
                    }
                  }
    } yield {
      ()
    }) match {
      case eb:EmptyBox =>
        val e = eb ?~! "Error when trying to save directive based on updated Techniques"
        logger.error(e.messageChain)
      case _ => ()
    }
  }
}
