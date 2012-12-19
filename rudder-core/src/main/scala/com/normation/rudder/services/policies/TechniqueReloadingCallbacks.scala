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

import com.normation.cfclerk.domain.TechniqueId
import com.normation.cfclerk.services.TechniquesLibraryUpdateNotification
import com.normation.rudder.batch.AsyncDeploymentAgent
import com.normation.rudder.batch.AutomaticStartDeployment
import com.normation.eventlog.EventActor
import com.normation.rudder.domain.eventlog.ReloadTechniqueLibrary
import com.normation.eventlog.EventLogDetails
import net.liftweb.common._
import com.normation.rudder.repository.EventLogRepository
import com.normation.eventlog.ModificationId

class DeployOnTechniqueCallback(
    override val name   : String
  , asyncDeploymentAgent: AsyncDeploymentAgent
) extends TechniquesLibraryUpdateNotification with Loggable {

  override def updatedTechniques(TechniqueIds:Seq[TechniqueId], modId:ModificationId, actor:EventActor, reason: Option[String]) : Unit = {
    reason.foreach( msg => logger.info(msg) )
    logger.debug("Ask for a deployment since technique library was reloaded")
    asyncDeploymentAgent ! AutomaticStartDeployment(modId, actor)
  }
}

class LogEventOnTechniqueReloadCallback(
    override val name: String
  , eventLogRepos    : EventLogRepository
) extends TechniquesLibraryUpdateNotification with Loggable {

  override def updatedTechniques(TechniqueIds:Seq[TechniqueId], modId:ModificationId, actor:EventActor, reason: Option[String]) : Unit = {
    eventLogRepos.saveEventLog(modId, ReloadTechniqueLibrary(EventLogDetails(
        modificationId = None
      , principal   = actor
      , details     = ReloadTechniqueLibrary.buildDetails(TechniqueIds)
      , reason = reason
    ))) match {
      case eb:EmptyBox => 
        val error = eb ?~! "Error when saving log related to technique reloading event"
        logger.error(error)
        logger.debug(error.exceptionChain)
      case Full(x) => //OK
    }
  }
}

