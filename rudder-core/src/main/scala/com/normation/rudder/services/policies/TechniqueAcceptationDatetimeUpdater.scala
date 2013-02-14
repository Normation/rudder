/*
*************************************************************************************
* Copyright 2011 Normation SAS
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

import com.normation.cfclerk.services.TechniquesLibraryUpdateNotification
import net.liftweb.common.Loggable
import com.normation.cfclerk.domain.TechniqueId
import com.normation.rudder.repository.WoDirectiveRepository
import net.liftweb.common._
import org.joda.time.DateTime
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.rudder.repository.RoDirectiveRepository

class TechniqueAcceptationDatetimeUpdater(
    override val name:String
  , roActiveTechniqueRepo : RoDirectiveRepository 
  , rwActiveTechniqueRepo : WoDirectiveRepository
) extends TechniquesLibraryUpdateNotification with Loggable {

    override def updatedTechniques(TechniqueIds:Seq[TechniqueId], modId: ModificationId, actor:EventActor, reason: Option[String]) : Unit = {
      val byNames = TechniqueIds.groupBy( _.name ).map { case (name,ids) =>
                      (name, ids.map( _.version ))
                    }.toMap
      val acceptationDatetime = DateTime.now()

      byNames.foreach { case( name, versions ) =>
        roActiveTechniqueRepo.getActiveTechnique(name) match {
          case e:EmptyBox =>
            //OK, that policy package is not in the Active Technique Library, do nothing
            //log in case it was a real problem
            val error = e ?~! ("The Technique with name '%s' has been marked as updated in the Technique Library ".format(name) +
                "but was not found in the Active Technique Library - it's expected if the technique was not added (or was removed) from Active Technique Library")
            logger.debug(error.messageChain)
          case Full(activeTechnique) =>
            logger.debug("Update acceptation datetime for: " + activeTechnique.techniqueName)
            val versionsMap = versions.map( v => (v,acceptationDatetime)).toMap
            rwActiveTechniqueRepo.setAcceptationDatetimes(activeTechnique.id, versionsMap, modId, actor, reason) match {
              case e:EmptyBox =>
                logger.error("Error when saving Active Technique " + activeTechnique.id, (e ?~! "Error was:"))
              case _ => //ok
            }
        }
      }
    }

}