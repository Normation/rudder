/*
*************************************************************************************
* Copyright 2011-2013 Normation SAS
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
package com.normation.rudder.services.eventlog

import com.normation.rudder.domain.workflows._
import com.normation.rudder.domain.eventlog._
import com.normation.eventlog._
import net.liftweb.common._
import com.normation.rudder.repository.EventLogRepository
import com.normation.utils.StringUuidGenerator


trait WorkflowEventLogService {

  def saveEventLog(stepChange:WorkflowStepChange, actor:EventActor, reason:Option[String]) : Box[EventLog]

  def getChangeRequestHistory(id: ChangeRequestId) : Box[Seq[WorkflowStepChanged]]

  def getLastLog(id:ChangeRequestId) : Box[Option[WorkflowStepChanged]]
}

class WorkflowEventLogServiceImpl (
    eventLogRepository : EventLogRepository
  , uuidGen            : StringUuidGenerator
) extends WorkflowEventLogService with Loggable {
  def saveEventLog(stepChange:WorkflowStepChange, actor:EventActor, reason:Option[String]) : Box[EventLog] = {
    val modId = ModificationId(uuidGen.newUuid)
    eventLogRepository.saveWorkflowStep(modId, actor, stepChange, reason)
  }

  def getChangeRequestHistory(id: ChangeRequestId) : Box[Seq[WorkflowStepChanged]] = {
    eventLogRepository.getEventLogByChangeRequest(id,"/entry/workflowStep/changeRequestId/text()", eventTypeFilter=Some(Seq(WorkflowStepChanged))).map(_.collect{case w:WorkflowStepChanged => w})
  }

  def getLastLog(id:ChangeRequestId) : Box[Option[WorkflowStepChanged]] = {
    eventLogRepository.getEventLogByChangeRequest(id,"/entry/workflowStep/changeRequestId/text()",Some(1),Some("creationDate desc"), eventTypeFilter=Some(Seq(WorkflowStepChanged))).map(_.collect{case w:WorkflowStepChanged => w}.headOption)
  }
  }
