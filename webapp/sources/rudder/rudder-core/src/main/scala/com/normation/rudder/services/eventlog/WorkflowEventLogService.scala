/*
*************************************************************************************
* Copyright 2011-2013 Normation SAS
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
package com.normation.rudder.services.eventlog

import com.normation.rudder.domain.workflows._
import com.normation.rudder.domain.eventlog._
import com.normation.eventlog._
import net.liftweb.common._
import com.normation.rudder.repository.EventLogRepository
import com.normation.utils.StringUuidGenerator

import com.normation.box._


trait WorkflowEventLogService {

  def saveEventLog(stepChange:WorkflowStepChange, actor:EventActor, reason:Option[String]) : Box[EventLog]

  def getChangeRequestHistory(id: ChangeRequestId) : Box[Seq[WorkflowStepChanged]]

  def getLastLog(id:ChangeRequestId) : Box[Option[WorkflowStepChanged]]

  /**
   * Get Last Workflow event for each change Request
   */
  def getLastWorkflowEvents() : Box[Map[ChangeRequestId,EventLog]]
}

class WorkflowEventLogServiceImpl (
    eventLogRepository : EventLogRepository
  , uuidGen            : StringUuidGenerator
) extends WorkflowEventLogService with Loggable {
  def saveEventLog(stepChange:WorkflowStepChange, actor:EventActor, reason:Option[String]) : Box[EventLog] = {
    val modId = ModificationId(uuidGen.newUuid)
    eventLogRepository.saveWorkflowStep(modId, actor, stepChange, reason).toBox
  }

  def getChangeRequestHistory(id: ChangeRequestId) : Box[Seq[WorkflowStepChanged]] = {
    eventLogRepository.getEventLogByChangeRequest(id,"/entry/workflowStep/changeRequestId/text()", eventTypeFilter= List(WorkflowStepChanged)).map(_.collect{case w:WorkflowStepChanged => w}).toBox
  }

  def getLastLog(id:ChangeRequestId) : Box[Option[WorkflowStepChanged]] = {
    eventLogRepository.getEventLogByChangeRequest(id,"/entry/workflowStep/changeRequestId/text()",Some(1),Some("creationDate desc"), eventTypeFilter= List(WorkflowStepChanged)).map(_.collect{case w:WorkflowStepChanged => w}.headOption).toBox
  }

  def getLastWorkflowEvents() : Box[Map[ChangeRequestId,EventLog]] = {
    eventLogRepository.getLastEventByChangeRequest("/entry/workflowStep/changeRequestId/text()", List(WorkflowStepChanged)).toBox
  }
  }
