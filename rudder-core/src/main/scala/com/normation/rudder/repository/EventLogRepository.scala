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

package com.normation.rudder.repository

import net.liftweb.common._
import com.normation.eventlog._
import com.normation.rudder.domain.policies._
import com.normation.rudder.services.eventlog.EventLogFactory
import com.normation.cfclerk.domain.SectionSpec
import com.normation.rudder.domain.nodes.DeleteNodeGroupDiff
import com.normation.rudder.domain.nodes.ModifyNodeGroupDiff
import com.normation.rudder.domain.nodes.AddNodeGroupDiff
import com.normation.eventlog.ModificationId
import scala.collection.mutable.Buffer
import com.normation.rudder.domain.eventlog.ChangeRequestDiff
import com.normation.rudder.domain.workflows.ChangeRequestId
import com.normation.rudder.domain.workflows.WorkflowStepChange

trait EventLogRepository {
  def eventLogFactory : EventLogFactory


  /**
   * Save an eventLog
   * Optionnal : the user. At least one of the eventLog user or user must be defined
   * Return the unspecialized event log with its serialization number
   */
  def saveEventLog(modId: ModificationId, eventLog : EventLog) : Box[EventLog]

  def saveAddRule(modId: ModificationId, principal: EventActor, addDiff: AddRuleDiff, reason:Option[String]) = {
    saveEventLog(
        modId
      , eventLogFactory.getAddRuleFromDiff(
          principal = principal
        , addDiff   = addDiff
        , reason = reason
      )
    )
  }

  def saveDeleteRule(modId: ModificationId, principal: EventActor, deleteDiff: DeleteRuleDiff, reason:Option[String]) = {
    saveEventLog(
        modId
      , eventLogFactory.getDeleteRuleFromDiff(
            principal  = principal
          , deleteDiff = deleteDiff
          , reason = reason
        )
    )
  }

  def saveModifyRule(modId: ModificationId, principal: EventActor, modifyDiff: ModifyRuleDiff, reason:Option[String]) = {
    saveEventLog(
        modId
      , eventLogFactory.getModifyRuleFromDiff(
          principal = principal
        , modifyDiff = modifyDiff
        , reason = reason
      )
    )
  }

  def saveAddDirective(modId: ModificationId, principal: EventActor, addDiff: AddDirectiveDiff, varsRootSectionSpec: SectionSpec, reason:Option[String]) = {
    saveEventLog(
        modId
      , eventLogFactory.getAddDirectiveFromDiff(
          principal           = principal
        , addDiff             = addDiff
        , varsRootSectionSpec = varsRootSectionSpec
        , reason = reason
      )
    )
  }

  def saveDeleteDirective(modId: ModificationId, principal : EventActor, deleteDiff:DeleteDirectiveDiff, varsRootSectionSpec: SectionSpec, reason:Option[String]) = {
    saveEventLog(
        modId
      , eventLogFactory.getDeleteDirectiveFromDiff(
          principal  = principal
        , deleteDiff = deleteDiff
        , varsRootSectionSpec = varsRootSectionSpec
        , reason = reason
      )
    )
  }

  def saveModifyDirective(modId: ModificationId, principal : EventActor, modifyDiff: ModifyDirectiveDiff, reason:Option[String]) = {
    saveEventLog(
        modId
      , eventLogFactory.getModifyDirectiveFromDiff(
          principal = principal
        , modifyDiff = modifyDiff
        , reason = reason
      )
    )
  }

  def saveAddNodeGroup(modId: ModificationId, principal: EventActor, addDiff: AddNodeGroupDiff, reason:Option[String]) = {
    saveEventLog(
        modId
      , eventLogFactory.getAddNodeGroupFromDiff(
          principal           = principal
        , addDiff             = addDiff
        , reason = reason
      )
    )
  }

  def saveDeleteNodeGroup(modId: ModificationId, principal : EventActor, deleteDiff:DeleteNodeGroupDiff, reason:Option[String]) = {
    saveEventLog(
        modId
      , eventLogFactory.getDeleteNodeGroupFromDiff(
          principal  = principal
        , deleteDiff = deleteDiff
        , reason = reason
      )
    )
  }

  def saveModifyNodeGroup(modId: ModificationId, principal : EventActor, modifyDiff: ModifyNodeGroupDiff, reason:Option[String]) = {
    saveEventLog(
        modId
      , eventLogFactory.getModifyNodeGroupFromDiff(
          principal = principal
        , modifyDiff = modifyDiff
        , reason = reason
      )
    )
  }

  def saveModifyTechnique(modId: ModificationId, principal: EventActor, modifyDiff: ModifyTechniqueDiff, reason:Option[String]) = {
    saveEventLog(
        modId
      , eventLogFactory.getModifyTechniqueFromDiff(
          principal = principal
        , modifyDiff = modifyDiff
        , reason = reason
      )
    )
  }

  def saveDeleteTechnique(modId: ModificationId, principal: EventActor, deleteDiff: DeleteTechniqueDiff, reason:Option[String]) = {
    saveEventLog(
        modId
      , eventLogFactory.getDeleteTechniqueFromDiff(
          principal  = principal
        , deleteDiff = deleteDiff
        , reason = reason
      )
    )
  }

  def saveChangeRequest(modId: ModificationId, principal: EventActor, diff: ChangeRequestDiff, reason:Option[String]) = {
    saveEventLog(
        modId
      , eventLogFactory.getChangeRequestFromDiff(
          principal =  principal
        , diff = diff
        , reason = reason
      )
    )
  }

  def saveWorkflowStep(modId: ModificationId, principal: EventActor, step: WorkflowStepChange, reason:Option[String]) = {
    saveEventLog(
        modId
      , eventLogFactory.getWorkFlowEventFromStepChange(
          principal =  principal
        , step = step
        , reason = reason
      )
    )
  }

  /**
   * Get an EventLog by its entry
   */
  def getEventLog(id : Int) : Box[EventLog]

  /**
   * Returns eventlog matching criteria
   * For the moment it only a string, it should be something else in the future
   */
  def getEventLogByCriteria(criteria : Option[String], limit:Option[Int] = None, orderBy:Option[String] = None) : Box[Seq[EventLog]]


  def getEventLogByChangeRequest(changeRequest : ChangeRequestId, xpath:String, optLimit:Option[Int] = None, orderBy:Option[String] = None, eventTypeFilter : Option[Seq[EventLogFilter]] = None) : Box[Seq[EventLog]]

}