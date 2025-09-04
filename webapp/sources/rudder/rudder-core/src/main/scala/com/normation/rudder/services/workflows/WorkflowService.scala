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

package com.normation.rudder.services.workflows

import com.normation.cfclerk.domain.SectionSpec
import com.normation.cfclerk.domain.TechniqueName
import com.normation.errors.Inconsistency
import com.normation.errors.IOResult
import com.normation.eventlog.EventActor
import com.normation.rudder.domain.logger.PluginLogger
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.nodes.NodeGroupCategoryId
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.policies.ActiveTechniqueId
import com.normation.rudder.domain.policies.Directive
import com.normation.rudder.domain.policies.DirectiveUid
import com.normation.rudder.domain.policies.Rule
import com.normation.rudder.domain.policies.RuleUid
import com.normation.rudder.domain.properties.GlobalParameter
import com.normation.rudder.domain.workflows.*
import com.normation.rudder.facts.nodes.ChangeContext
import com.normation.rudder.users.AuthenticatedUser
import net.liftweb.common.*
import zio.syntax.ToZio

case object WorkflowUpdate

/*
 * For rules, we have dedicated actions
 */
sealed abstract class RuleModAction(val name: String)
object RuleModAction {
  case object Create  extends RuleModAction("create")
  case object Update  extends RuleModAction("update")
  case object Delete  extends RuleModAction("delete")
  case object Enable  extends RuleModAction("enable")
  case object Disable extends RuleModAction("disable")
}

final case class RuleChangeRequest(action: RuleModAction, newRule: Rule, previousRule: Option[Rule])

/*
 * Actions for groups and directives are mixed
 */
sealed abstract class DGModAction(val name: String)
object DGModAction {
  // create the directive without modifying rules (creation only - skip workflows)
  case object CreateSolo        extends DGModAction("create")
  // create the directive and assign it to rules (creation of directive without wf, rules modification with wf)
  case object CreateAndModRules extends DGModAction("create")
  case object Update            extends DGModAction("update")
  case object Delete            extends DGModAction("delete")
  case object Enable            extends DGModAction("enable")
  case object Disable           extends DGModAction("disable")
}

final case class DirectiveChangeRequest(
    action:            DGModAction,
    techniqueName:     TechniqueName,
    activeTechniqueId: ActiveTechniqueId,
    sectionSpec:       SectionSpec,
    newDirective:      Directive,
    previousDirective: Option[Directive],
    baseRules:         List[Rule],
    updatedRules:      List[Rule]
)

final case class NodeGroupChangeRequest(
    action:        DGModAction,
    newGroup:      NodeGroup,
    category:      Option[NodeGroupCategoryId],
    previousGroup: Option[NodeGroup]
)

sealed abstract class GlobalParamModAction(val name: String)
object GlobalParamModAction {
  case object Create extends GlobalParamModAction("create")
  case object Update extends GlobalParamModAction("update")
  case object Delete extends GlobalParamModAction("delete")
}

final case class GlobalParamChangeRequest(
    action:              GlobalParamModAction,
    previousGlobalParam: Option[GlobalParameter]
    // this one is strange, we don't have the new value?
)

/*
 * This service allows to know if the change validation service is set
 */
trait WorkflowLevelService {
  /*
   * Does that level of workflow service allows a workflow to
   * be enabled ?
   */
  def workflowLevelAllowsEnable: Boolean

  /*
   * Is the workflow enable from the user pont of view ?
   * For that to be true, workflow level supporting workflow must be
   * installed AND the user must have chosen workflows.
   * It behaves like property "rudder_workflow_enabled"
   */
  def workflowEnabled: Boolean
  def name:            String

  def getWorkflowService(): WorkflowService

  /*
   * Find the workflow that must follow the change to be validated
   */
  def getForRule(actor:        EventActor, change: RuleChangeRequest):        IOResult[WorkflowService]
  def getForDirective(actor:   EventActor, change: DirectiveChangeRequest):   IOResult[WorkflowService]
  def getForNodeGroup(actor:   EventActor, change: NodeGroupChangeRequest):   IOResult[WorkflowService]
  def getForGlobalParam(actor: EventActor, change: GlobalParamChangeRequest): IOResult[WorkflowService]

  /*
   * These method allow to get change request impacting a rule/directive/etc.
   * Used to display information on them on corresponding update screens.
   */
  def getByDirective(id: DirectiveUid, onlyPending: Boolean): IOResult[Vector[ChangeRequest]]
  def getByNodeGroup(id: NodeGroupId, onlyPending:  Boolean): IOResult[Vector[ChangeRequest]]
  def getByRule(id:      RuleUid, onlyPending:      Boolean): IOResult[Vector[ChangeRequest]]
}

// and default implementation is: no
class DefaultWorkflowLevel(val defaultWorkflowService: WorkflowService) extends WorkflowLevelService {
  // Alternative level provider
  private var level: Option[WorkflowLevelService] = None

  def overrideLevel(l: WorkflowLevelService): Unit = {
    PluginLogger.info(s"Update Validation Workflow level to '${l.name}'")
    level = Some(l)
  }

  override def workflowLevelAllowsEnable: Boolean = level.map(_.workflowLevelAllowsEnable).getOrElse(false)
  override def workflowEnabled:           Boolean = level.map(_.workflowEnabled).getOrElse(false)

  override def name: String = level.map(_.name).getOrElse("Default implementation (no validation workflows)")

  override def getWorkflowService(): WorkflowService = level.map(_.getWorkflowService()).getOrElse(defaultWorkflowService)

  override def getForRule(actor: EventActor, change: RuleChangeRequest):               IOResult[WorkflowService] = {
    this.level.map(_.getForRule(actor, change)).getOrElse(defaultWorkflowService.succeed)
  }
  override def getForDirective(actor: EventActor, change: DirectiveChangeRequest):     IOResult[WorkflowService] = {
    this.level.map(_.getForDirective(actor, change)).getOrElse(defaultWorkflowService.succeed)
  }
  override def getForNodeGroup(actor: EventActor, change: NodeGroupChangeRequest):     IOResult[WorkflowService] = {
    this.level.map(_.getForNodeGroup(actor, change)).getOrElse(defaultWorkflowService.succeed)
  }
  override def getForGlobalParam(actor: EventActor, change: GlobalParamChangeRequest): IOResult[WorkflowService] = {
    this.level.map(_.getForGlobalParam(actor, change)).getOrElse(defaultWorkflowService.succeed)
  }

  override def getByDirective(id: DirectiveUid, onlyPending: Boolean): IOResult[Vector[ChangeRequest]] =
    this.level.map(_.getByDirective(id, onlyPending)).getOrElse(Vector().succeed)

  override def getByNodeGroup(id: NodeGroupId, onlyPending: Boolean): IOResult[Vector[ChangeRequest]] =
    this.level.map(_.getByNodeGroup(id, onlyPending)).getOrElse(Vector().succeed)

  override def getByRule(id: RuleUid, onlyPending: Boolean): IOResult[Vector[ChangeRequest]] =
    this.level.map(_.getByRule(id, onlyPending)).getOrElse(Vector().succeed)
}

/**
 * That service allows to glue Rudder with the
 * workflows engine.
 * It allows to send new ChangeRequest to the engine,
 * and to be notified when one of them reach the end.
 */
trait WorkflowService {

  // each kind of workflow has a name for identification in logs.
  def name: String

  /**
   * Start a new workflow process with the given
   * change request or continue an existing
   * wf for that change request
   * (one change request can not have more than
   * one wf at the same time).
   *
   * Return the updated ChangeRequestId
   */
  def startWorkflow(changeRequest: ChangeRequest)(implicit cc: ChangeContext): IOResult[ChangeRequestId]

  def openSteps:   List[WorkflowNodeId]
  def closedSteps: List[WorkflowNodeId]

  def stepsValue: List[WorkflowNodeId]

  def findNextSteps(currentStep: WorkflowNodeId)(implicit auth: AuthenticatedUser): WorkflowAction

  def findBackSteps(currentStep: WorkflowNodeId)(implicit
      auth: AuthenticatedUser
  ): Seq[(WorkflowNodeId, (ChangeRequestId, EventActor, Option[String]) => IOResult[WorkflowNodeId])]

  def findStep(changeRequestId: ChangeRequestId): IOResult[WorkflowNodeId]

  def findBackStatus(currentStep: WorkflowNodeId): Option[WorkflowNodeId]
  def findNextStatus(currentStep: WorkflowNodeId): Option[WorkflowNodeId]

  /**
   * Get workflow step of each ChangeRequest
   */
  def getAllChangeRequestsStep(): IOResult[Map[ChangeRequestId, WorkflowNodeId]]

  def isEditable(currentUserRights: Seq[String], currentStep: WorkflowNodeId, isCreator: Boolean): Boolean
  def isPending(currentStep:        WorkflowNodeId): Boolean

  /*
   * A method that tells if the workflow requires an external validation.
   * It can be usefull if you want to adapt message and validation redirection if
   * it is the case.
   */
  def needExternalValidation(): Boolean
}

case class WorkflowAction(
    name:    String,
    actions: Seq[(WorkflowNodeId, (ChangeRequestId, EventActor, Option[String]) => IOResult[WorkflowNodeId])]
)

object NoWorkflowAction extends WorkflowAction("Nothing", Seq())
object WorkflowAction {
  type WorkflowStepFunction = (ChangeRequestId, EventActor, Option[String]) => IOResult[WorkflowNodeId]
  def apply(name: String, action: (WorkflowNodeId, WorkflowStepFunction)): WorkflowAction = WorkflowAction(name, Seq(action))
}

/**
 * The simplest workflow ever, that doesn't wait for approval
 * It has only one state : Deployed
 */
class NoWorkflowServiceImpl(
    commit: CommitAndDeployChangeRequestService
) extends WorkflowService with Loggable {

  val noWorfkflow: WorkflowNodeId = WorkflowNodeId("No Workflow")

  val name = "no-changes-validation-workflow"

  def findNextSteps(currentStep: WorkflowNodeId)(implicit authz: AuthenticatedUser): WorkflowAction = NoWorkflowAction

  def findBackSteps(currentStep: WorkflowNodeId)(implicit
      auth: AuthenticatedUser
  ): Seq[(WorkflowNodeId, (ChangeRequestId, EventActor, Option[String]) => IOResult[WorkflowNodeId])] = Seq()

  def findStep(changeRequestId: ChangeRequestId): IOResult[WorkflowNodeId] = Inconsistency("No state when no workflow").fail

  def getAllChangeRequestsStep(): IOResult[Map[ChangeRequestId, WorkflowNodeId]] = Inconsistency("No state when no workflow").fail

  val openSteps:   List[WorkflowNodeId] = List()
  val closedSteps: List[WorkflowNodeId] = List()
  val stepsValue:  List[WorkflowNodeId] = List()

  def startWorkflow(changeRequest: ChangeRequest)(implicit cc: ChangeContext): IOResult[ChangeRequestId] = {
    logger.debug("Automatically saving change")
    for {
      result <- commit.save(changeRequest)
    } yield {
      // and return a no workflow
      result.id
    }
  }

  def isEditable(currentUserRights: Seq[String], currentStep: WorkflowNodeId, isCreator: Boolean): Boolean = false

  def isPending(currentStep: WorkflowNodeId): Boolean = false

  override def needExternalValidation(): Boolean = false

  override def findBackStatus(currentStep: WorkflowNodeId): Option[WorkflowNodeId] = None

  override def findNextStatus(currentStep: WorkflowNodeId): Option[WorkflowNodeId] = None
}
