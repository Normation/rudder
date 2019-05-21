/*
*************************************************************************************
* Copyright 2011 Normation SAS
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

package com.normation.rudder.services.policies

import com.normation.cfclerk.services.TechniquesLibraryUpdateNotification
import com.normation.rudder.batch.AsyncDeploymentActor
import com.normation.rudder.batch.AutomaticStartDeployment
import com.normation.eventlog.EventActor
import com.normation.rudder.domain.eventlog.ReloadTechniqueLibrary
import com.normation.eventlog.EventLogDetails
import net.liftweb.common._
import com.normation.eventlog.ModificationId
import com.normation.cfclerk.services.TechniquesLibraryUpdateType
import com.normation.cfclerk.domain.TechniqueName
import com.normation.rudder.repository.EventLogRepository

import com.normation.box._

class DeployOnTechniqueCallback(
    override val name   : String
  , override val order  : Int
  , asyncDeploymentAgent: AsyncDeploymentActor
) extends TechniquesLibraryUpdateNotification with Loggable {

  override def updatedTechniques(gitRev: String, techniqueIds:Map[TechniqueName, TechniquesLibraryUpdateType], modId:ModificationId, actor:EventActor, reason: Option[String]) : Box[Unit] = {
    reason.foreach( msg => logger.info(msg) )
    if(techniqueIds.nonEmpty) {
      logger.debug(s"Ask for a policy update since technique library was reloaded (git revision tree: ${gitRev}")
      asyncDeploymentAgent ! AutomaticStartDeployment(modId, actor)
    }
    Full({})
  }
}

class LogEventOnTechniqueReloadCallback(
    override val name : String
  , override val order: Int
  , eventLogRepos     : EventLogRepository
) extends TechniquesLibraryUpdateNotification with Loggable {

  override def updatedTechniques(gitRev: String, techniqueMods: Map[TechniqueName, TechniquesLibraryUpdateType], modId:ModificationId, actor:EventActor, reason: Option[String]) : Box[Unit] = {
    eventLogRepos.saveEventLog(modId, ReloadTechniqueLibrary(EventLogDetails(
        modificationId = None
      , principal      = actor
      , details        = ReloadTechniqueLibrary.buildDetails(gitRev, techniqueMods)
      , reason = reason
    ))).chainError("Error when saving event log for techniques library reload").unit.toBox
  }
}

