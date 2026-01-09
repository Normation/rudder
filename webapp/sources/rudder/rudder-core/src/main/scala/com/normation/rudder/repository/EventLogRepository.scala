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

package com.normation.rudder.repository

import com.normation.cfclerk.domain.SectionSpec
import com.normation.errors.IOResult
import com.normation.eventlog.EventActor
import com.normation.eventlog.EventLog
import com.normation.eventlog.EventLogFilter
import com.normation.eventlog.ModificationId
import com.normation.rudder.api.AddApiAccountDiff
import com.normation.rudder.api.DeleteApiAccountDiff
import com.normation.rudder.api.ModifyApiAccountDiff
import com.normation.rudder.domain.appconfig.RudderWebProperty
import com.normation.rudder.domain.eventlog.ChangeRequestDiff
import com.normation.rudder.domain.eventlog.ModifyGlobalPropertyEventType
import com.normation.rudder.domain.nodes.AddNodeGroupDiff
import com.normation.rudder.domain.nodes.DeleteNodeGroupDiff
import com.normation.rudder.domain.nodes.ModifyNodeDiff
import com.normation.rudder.domain.nodes.ModifyNodeGroupDiff
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.domain.policies.AddDirectiveDiff
import com.normation.rudder.domain.policies.AddRuleDiff
import com.normation.rudder.domain.policies.AddTechniqueDiff
import com.normation.rudder.domain.policies.DeleteDirectiveDiff
import com.normation.rudder.domain.policies.DeleteRuleDiff
import com.normation.rudder.domain.policies.DeleteTechniqueDiff
import com.normation.rudder.domain.policies.ModifyDirectiveDiff
import com.normation.rudder.domain.policies.ModifyRuleDiff
import com.normation.rudder.domain.policies.ModifyTechniqueDiff
import com.normation.rudder.domain.properties.AddGlobalParameterDiff
import com.normation.rudder.domain.properties.DeleteGlobalParameterDiff
import com.normation.rudder.domain.properties.ModifyGlobalParameterDiff
import com.normation.rudder.domain.secret.Secret
import com.normation.rudder.domain.workflows.ChangeRequestId
import com.normation.rudder.domain.workflows.WorkflowStepChange
import com.normation.rudder.ncf.eventlogs.AddEditorTechniqueDiff
import com.normation.rudder.ncf.eventlogs.DeleteEditorTechniqueDiff
import com.normation.rudder.ncf.eventlogs.ModifyEditorTechniqueDiff
import com.normation.rudder.services.eventlog.EventLogFactory
import com.normation.rudder.tenants.ChangeContext
import doobie.*
import java.time.Instant

trait EventLogRepository {
  def eventLogFactory: EventLogFactory

  /**
   * Save an eventLog
   * Optional : the user. At least one of the eventLog user or user must be defined
   * Return the unspecialized event log with its serialization number
   */
  def saveEventLog(modId: ModificationId, eventLog: EventLog): IOResult[EventLog]

  def saveAddRule(
      modId:     ModificationId,
      principal: EventActor,
      addDiff:   AddRuleDiff,
      reason:    Option[String]
  ): IOResult[EventLog] = {
    saveEventLog(
      modId,
      eventLogFactory.getAddRuleFromDiff(
        principal = principal,
        addDiff = addDiff,
        reason = reason
      )
    )
  }

  def saveDeleteRule(
      modId:      ModificationId,
      principal:  EventActor,
      deleteDiff: DeleteRuleDiff,
      reason:     Option[String]
  ): IOResult[EventLog] = {
    saveEventLog(
      modId,
      eventLogFactory.getDeleteRuleFromDiff(
        principal = principal,
        deleteDiff = deleteDiff,
        reason = reason
      )
    )
  }

  def saveModifyRule(
      modId:      ModificationId,
      principal:  EventActor,
      modifyDiff: ModifyRuleDiff,
      reason:     Option[String]
  ): IOResult[EventLog] = {
    saveEventLog(
      modId,
      eventLogFactory.getModifyRuleFromDiff(
        principal = principal,
        modifyDiff = modifyDiff,
        reason = reason
      )
    )
  }

  def saveAddDirective(
      modId:               ModificationId,
      principal:           EventActor,
      addDiff:             AddDirectiveDiff,
      varsRootSectionSpec: SectionSpec,
      reason:              Option[String]
  ): IOResult[EventLog] = {
    saveEventLog(
      modId,
      eventLogFactory.getAddDirectiveFromDiff(
        principal = principal,
        addDiff = addDiff,
        varsRootSectionSpec = varsRootSectionSpec,
        reason = reason
      )
    )
  }

  def saveDeleteDirective(
      modId:               ModificationId,
      principal:           EventActor,
      deleteDiff:          DeleteDirectiveDiff,
      varsRootSectionSpec: SectionSpec,
      reason:              Option[String]
  ): IOResult[EventLog] = {
    saveEventLog(
      modId,
      eventLogFactory.getDeleteDirectiveFromDiff(
        principal = principal,
        deleteDiff = deleteDiff,
        varsRootSectionSpec = varsRootSectionSpec,
        reason = reason
      )
    )
  }

  def saveModifyDirective(
      modId:      ModificationId,
      principal:  EventActor,
      modifyDiff: ModifyDirectiveDiff,
      reason:     Option[String]
  ): IOResult[EventLog] = {
    saveEventLog(
      modId,
      eventLogFactory.getModifyDirectiveFromDiff(
        principal = principal,
        modifyDiff = modifyDiff,
        reason = reason
      )
    )
  }

  def saveAddEditorTechnique(
      modId:     ModificationId,
      principal: EventActor,
      addDiff:   AddEditorTechniqueDiff,
      reason:    Option[String]
  ): IOResult[EventLog] = {
    saveEventLog(
      modId,
      eventLogFactory.getAddEditorTechniqueFromDiff(
        principal = principal,
        addDiff = addDiff,
        reason = reason
      )
    )
  }

  def saveDeleteEditorTechnique(
      modId:      ModificationId,
      principal:  EventActor,
      deleteDiff: DeleteEditorTechniqueDiff,
      reason:     Option[String]
  ): IOResult[EventLog] = {
    saveEventLog(
      modId,
      eventLogFactory.getDeleteEditorTechniqueFromDiff(
        principal = principal,
        deleteDiff = deleteDiff,
        reason = reason
      )
    )
  }

  def saveModifyEditorTechnique(
      modId:      ModificationId,
      principal:  EventActor,
      modifyDiff: ModifyEditorTechniqueDiff,
      reason:     Option[String]
  ): IOResult[EventLog] = {
    saveEventLog(
      modId,
      eventLogFactory.getModifyEditorTechniqueFromDiff(
        principal = principal,
        modifyDiff = modifyDiff,
        reason = reason
      )
    )
  }

  def saveAddNodeGroup(
      addDiff: AddNodeGroupDiff
  )(implicit cc: ChangeContext): IOResult[EventLog] = {
    saveEventLog(
      cc.modId,
      eventLogFactory.getAddNodeGroupFromDiff(
        principal = cc.actor,
        addDiff = addDiff,
        reason = cc.message
      )
    )
  }

  def saveDeleteNodeGroup(
      modId:      ModificationId,
      principal:  EventActor,
      deleteDiff: DeleteNodeGroupDiff,
      reason:     Option[String]
  ): IOResult[EventLog] = {
    saveEventLog(
      modId,
      eventLogFactory.getDeleteNodeGroupFromDiff(
        principal = principal,
        deleteDiff = deleteDiff,
        reason = reason
      )
    )
  }

  def saveModifyNodeGroup(
      modId:      ModificationId,
      principal:  EventActor,
      modifyDiff: ModifyNodeGroupDiff,
      reason:     Option[String]
  ): IOResult[EventLog] = {
    saveEventLog(
      modId,
      eventLogFactory.getModifyNodeGroupFromDiff(
        principal = principal,
        modifyDiff = modifyDiff,
        reason = reason
      )
    )
  }

  def saveAddTechnique(
      modId:     ModificationId,
      principal: EventActor,
      addDiff:   AddTechniqueDiff,
      reason:    Option[String]
  ): IOResult[EventLog] = {
    saveEventLog(
      modId,
      eventLogFactory.getAddTechniqueFromDiff(
        principal = principal,
        addDiff = addDiff,
        reason = reason
      )
    )
  }

  def saveModifyTechnique(
      modId:      ModificationId,
      principal:  EventActor,
      modifyDiff: ModifyTechniqueDiff,
      reason:     Option[String]
  ): IOResult[EventLog] = {
    saveEventLog(
      modId,
      eventLogFactory.getModifyTechniqueFromDiff(
        principal = principal,
        modifyDiff = modifyDiff,
        reason = reason
      )
    )
  }

  def saveDeleteTechnique(
      modId:      ModificationId,
      principal:  EventActor,
      deleteDiff: DeleteTechniqueDiff,
      reason:     Option[String]
  ): IOResult[EventLog] = {
    saveEventLog(
      modId,
      eventLogFactory.getDeleteTechniqueFromDiff(
        principal = principal,
        deleteDiff = deleteDiff,
        reason = reason
      )
    )
  }

  def saveChangeRequest(
      modId:     ModificationId,
      principal: EventActor,
      diff:      ChangeRequestDiff,
      reason:    Option[String]
  ): IOResult[EventLog] = {
    saveEventLog(
      modId,
      eventLogFactory.getChangeRequestFromDiff(
        principal = principal,
        diff = diff,
        reason = reason
      )
    )
  }

  def saveWorkflowStep(
      modId:     ModificationId,
      principal: EventActor,
      step:      WorkflowStepChange,
      reason:    Option[String]
  ): IOResult[EventLog] = {
    saveEventLog(
      modId,
      eventLogFactory.getWorkFlowEventFromStepChange(
        principal = principal,
        step = step,
        reason = reason
      )
    )
  }

  def saveAddGlobalParameter(
      modId:     ModificationId,
      principal: EventActor,
      addDiff:   AddGlobalParameterDiff,
      reason:    Option[String]
  ): IOResult[EventLog] = {
    saveEventLog(
      modId,
      eventLogFactory.getAddGlobalParameterFromDiff(
        principal = principal,
        addDiff = addDiff,
        reason = reason
      )
    )
  }

  def saveDeleteGlobalParameter(
      modId:      ModificationId,
      principal:  EventActor,
      deleteDiff: DeleteGlobalParameterDiff,
      reason:     Option[String]
  ): IOResult[EventLog] = {
    saveEventLog(
      modId,
      eventLogFactory.getDeleteGlobalParameterFromDiff(
        principal = principal,
        deleteDiff = deleteDiff,
        reason = reason
      )
    )
  }

  def saveModifyGlobalParameter(
      modId:      ModificationId,
      principal:  EventActor,
      modifyDiff: ModifyGlobalParameterDiff,
      reason:     Option[String]
  ): IOResult[EventLog] = {
    saveEventLog(
      modId,
      eventLogFactory.getModifyGlobalParameterFromDiff(
        principal = principal,
        modifyDiff = modifyDiff,
        reason = reason
      )
    )
  }

  /**
   * Save an API Account
   */
  def saveCreateApiAccount(
      modId:     ModificationId,
      principal: EventActor,
      addDiff:   AddApiAccountDiff,
      reason:    Option[String]
  ): IOResult[EventLog] = {
    saveEventLog(
      modId,
      eventLogFactory.getCreateApiAccountFromDiff(
        principal = principal,
        addDiff = addDiff,
        reason = reason
      )
    )
  }

  def saveModifyApiAccount(
      modId:      ModificationId,
      principal:  EventActor,
      modifyDiff: ModifyApiAccountDiff,
      reason:     Option[String]
  ): IOResult[EventLog] = {
    saveEventLog(
      modId,
      eventLogFactory.getModifyApiAccountFromDiff(
        principal = principal,
        modifyDiff = modifyDiff,
        reason = reason
      )
    )
  }

  def saveDeleteApiAccount(
      modId:      ModificationId,
      principal:  EventActor,
      deleteDiff: DeleteApiAccountDiff,
      reason:     Option[String]
  ): IOResult[EventLog] = {
    saveEventLog(
      modId,
      eventLogFactory.getDeleteApiAccountFromDiff(
        principal = principal,
        deleteDiff = deleteDiff,
        reason = reason
      )
    )
  }

  def saveModifyGlobalProperty(
      modId:        ModificationId,
      principal:    EventActor,
      oldProperty:  RudderWebProperty,
      newProperty:  RudderWebProperty,
      eventLogType: ModifyGlobalPropertyEventType,
      reason:       Option[String]
  ): IOResult[EventLog] = {
    saveEventLog(
      modId,
      eventLogFactory.getModifyGlobalPropertyFromDiff(
        principal = principal,
        oldProperty = oldProperty,
        newProperty = newProperty,
        eventLogType = eventLogType,
        reason = reason
      )
    )
  }

  /**
   * Node properties: agent run, properties
   */
  def saveModifyNode(
      modId:      ModificationId,
      principal:  EventActor,
      modifyDiff: ModifyNodeDiff,
      reason:     Option[String],
      eventDate:  Instant
  ): IOResult[EventLog] = {
    for {
      e <- saveEventLog(
             modId,
             eventLogFactory.getModifyNodeFromDiff(
               principal = principal,
               modifyDiff = modifyDiff,
               reason = reason,
               creationDate = eventDate
             )
           )
    } yield e
  }

  def savePromoteToRelay(
      modId:        ModificationId,
      principal:    EventActor,
      promotedNode: NodeInfo,
      reason:       Option[String]
  ): IOResult[EventLog] = {
    saveEventLog(
      modId,
      eventLogFactory.getPromoteToRelayFromDiff(
        principal = principal,
        promotedNode = promotedNode,
        reason = reason
      )
    )
  }
  def saveDemoteToNode(
      modId:        ModificationId,
      principal:    EventActor,
      demotedRelay: NodeInfo,
      reason:       Option[String]
  ): IOResult[EventLog] = {
    saveEventLog(
      modId,
      eventLogFactory.getDemoteToNodeFromDiff(
        principal = principal,
        demotedRelay = demotedRelay,
        reason = reason
      )
    )
  }

  def saveModifySecret(
      modId:     ModificationId,
      principal: EventActor,
      oldSecret: Secret,
      newSecret: Secret,
      reason:    Option[String]
  ): IOResult[EventLog] = {
    saveEventLog(
      modId,
      eventLogFactory.getModifySecretFromDiff(
        principal = principal,
        oldSecret = oldSecret,
        newSecret = newSecret,
        reason = reason
      )
    )
  }

  def saveAddSecret(modId: ModificationId, principal: EventActor, secret: Secret, reason: Option[String]): IOResult[EventLog] = {
    saveEventLog(
      modId,
      eventLogFactory.getAddSecretFromDiff(
        principal = principal,
        secret = secret,
        reason = reason
      )
    )
  }

  def saveDeleteSecret(
      modId:     ModificationId,
      principal: EventActor,
      secret:    Secret,
      reason:    Option[String]
  ): IOResult[EventLog] = {
    saveEventLog(
      modId,
      eventLogFactory.getDeleteSecretFromDiff(
        principal = principal,
        secret = secret,
        reason = reason
      )
    )
  }

  /**
   * Returns eventlog matching criteria
   * For the moment it only a string, it should be something else in the future
   */
  def getEventLogByCriteria(
      criteria:       Option[Fragment],
      limit:          Option[Int] = None,
      orderBy:        List[Fragment] = Nil,
      extendedFilter: Option[Fragment] = None
  ): IOResult[Seq[EventLog]]

  def getEventLogById(id: Long): IOResult[EventLog]

  def getEventLogCount(criteria: Option[Fragment], extendedFilter: Option[Fragment] = None): IOResult[Long]

  def getEventLogByChangeRequest(
      changeRequest:   ChangeRequestId,
      xpath:           String,
      optLimit:        Option[Int] = None,
      orderBy:         Option[String] = None,
      eventTypeFilter: List[EventLogFilter] = Nil
  ): IOResult[Vector[EventLog]]

  /**
   * Get an EventLog and ,if it was generated by a Change Request, its Id
   */
  def getEventLogWithChangeRequest(id: Int): IOResult[Option[(EventLog, Option[ChangeRequestId])]]

  def getLastEventByChangeRequest(
      xpath:           String,
      eventTypeFilter: List[EventLogFilter] = Nil
  ): IOResult[Map[ChangeRequestId, EventLog]]
}
