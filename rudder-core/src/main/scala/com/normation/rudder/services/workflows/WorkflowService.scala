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

import com.normation.eventlog.EventActor
import com.normation.rudder.domain.workflows._
import net.liftweb.common._
import com.normation.rudder.repository.inmemory.InMemoryChangeRequestRepository
import com.normation.rudder.domain.logger.ApplicationLogger
import com.normation.rudder.domain.logger.PluginLogger


case object WorkflowUpdate

/*
 * This service allows to know if the change validation service is set
 */
trait WorkflowLevelService {
  def workflowEnabled: Boolean
  def name: String
}

// and default implementation is: no
class DefaultWorkflowLevel() extends WorkflowLevelService {
  // Alternative level provider
  private[this] var level: Option[WorkflowLevelService] = None

  def overrideLevel(l: WorkflowLevelService): Unit = {
    PluginLogger.info(s"Update Validation Workflow level to '${l.name}'")
    level = Some(l)
  }
  override def workflowEnabled: Boolean = level.map( _.workflowEnabled ).getOrElse(false)

  override def name: String = level.map( _.name ).getOrElse("Default implementation (RO/RW authorization)")
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
   * So for now, a workflow process id IS a changeRequestId.
   * That abstraction is likelly to leak.
   *
   */
  def startWorkflow(changeRequestId: ChangeRequestId, actor:EventActor, reason: Option[String]) : Box[WorkflowNodeId]

  def openSteps : List[WorkflowNodeId]
  def closedSteps : List[WorkflowNodeId]

  def stepsValue :List[WorkflowNodeId]

  def findNextSteps(
      currentUserRights : Seq[String]
    , currentStep       : WorkflowNodeId
    , isCreator         : Boolean
  ) : WorkflowAction

  def findBackSteps(
      currentUserRights : Seq[String]
    , currentStep       : WorkflowNodeId
    , isCreator         : Boolean
  ) : Seq[(WorkflowNodeId,(ChangeRequestId,EventActor, Option[String]) => Box[WorkflowNodeId])]

  def findStep(changeRequestId: ChangeRequestId) : Box[WorkflowNodeId]

  /**
   * Get workflow step of each ChangeRequest
   */
  def getAllChangeRequestsStep() : Box[Map[ChangeRequestId,WorkflowNodeId]]

  def isEditable(currentUserRights:Seq[String],currentStep:WorkflowNodeId, isCreator : Boolean): Boolean
  def isPending(currentStep:WorkflowNodeId): Boolean
}

case class WorkflowAction(
    name:String
  , actions:Seq[(WorkflowNodeId,(ChangeRequestId,EventActor, Option[String]) => Box[WorkflowNodeId])]
)

object NoWorkflowAction extends WorkflowAction("Nothing",Seq())
object WorkflowAction {
  type WorkflowStepFunction = (ChangeRequestId,EventActor, Option[String]) => Box[WorkflowNodeId]
  def apply(name:String,action:(WorkflowNodeId,WorkflowStepFunction)):WorkflowAction = WorkflowAction(name,Seq(action))
}

/**
 * A facade, use to hide the real workflow service.
 * Must be initialized with a non-null workflow service.
 */
class FacadeWorkflowService(private var workflowService: WorkflowService) extends WorkflowService {

  // one can change the workflow service, and the action is log
  def updateWorkflowService(wf: WorkflowService): Unit = {
    ApplicationLogger.info(s"Setting a new changes validation workflow: '${wf.name}'")
    this.workflowService = wf
  }

  // allow to recover current workflow server
  def getCurrentWorkflowService = workflowService

  ///// below are all the forwards, nothing interesting at all /////
  override def name: String = //we are totaly tranparent, the name is the name of the backend workflow service
    workflowService.name
  override def startWorkflow(changeRequestId: ChangeRequestId, actor: EventActor, reason: Option[String]): Box[WorkflowNodeId] =
    workflowService.startWorkflow(changeRequestId, actor, reason)
  override def openSteps: List[WorkflowNodeId] =
    workflowService.openSteps
  override def closedSteps: List[WorkflowNodeId] =
    workflowService.closedSteps
  override def stepsValue: List[WorkflowNodeId] =
    workflowService.stepsValue
  override def findNextSteps(currentUserRights: Seq[String], currentStep: WorkflowNodeId, isCreator: Boolean): WorkflowAction =
    workflowService.findNextSteps(currentUserRights, currentStep, isCreator)
  override def findBackSteps(currentUserRights: Seq[String], currentStep: WorkflowNodeId, isCreator: Boolean): Seq[(WorkflowNodeId, (ChangeRequestId, EventActor, Option[String]) => Box[WorkflowNodeId])] =
    workflowService.findBackSteps(currentUserRights, currentStep, isCreator)
  override def findStep(changeRequestId: ChangeRequestId): Box[WorkflowNodeId] =
    workflowService.findStep(changeRequestId)
  override def getAllChangeRequestsStep(): Box[Map[ChangeRequestId, WorkflowNodeId]] =
    workflowService.getAllChangeRequestsStep()
  override def isEditable(currentUserRights: Seq[String], currentStep: WorkflowNodeId, isCreator: Boolean): Boolean =
    workflowService.isEditable(currentUserRights, currentStep, isCreator)
  override def isPending(currentStep: WorkflowNodeId): Boolean =
    workflowService.isPending(currentStep)
}

object FacadeWorkflowService {
  def apply(wf: WorkflowService): FacadeWorkflowService = new FacadeWorkflowService(wf)
}

/**
 * The simplest workflow ever, that doesn't wait for approval
 * It has only one state : Deployed
 */
class NoWorkflowServiceImpl(
    commit : CommitAndDeployChangeRequestService
  , crRepo : InMemoryChangeRequestRepository
) extends WorkflowService with Loggable {

  val noWorfkflow = WorkflowNodeId("No Workflow")

  val name = "no-changes-validation-workflow"

  def findNextSteps(
      currentUserRights : Seq[String]
    , currentStep       : WorkflowNodeId
    , isCreator         : Boolean
  ) : WorkflowAction = NoWorkflowAction

   def findBackSteps(
      currentUserRights : Seq[String]
    , currentStep       : WorkflowNodeId
    , isCreator         : Boolean
  ) : Seq[(WorkflowNodeId,(ChangeRequestId,EventActor, Option[String]) => Box[WorkflowNodeId])] = Seq()

  def findStep(changeRequestId: ChangeRequestId) : Box[WorkflowNodeId] = Failure("No state when no workflow")

  def getAllChangeRequestsStep : Box[Map[ChangeRequestId,WorkflowNodeId]] = Failure("No state when no workflow")

  val openSteps : List[WorkflowNodeId] = List()
  val closedSteps : List[WorkflowNodeId] = List()
  val stepsValue :List[WorkflowNodeId] = List()

  def startWorkflow(changeRequestId: ChangeRequestId, actor:EventActor, reason: Option[String]) : Box[WorkflowNodeId] = {
    logger.debug("Automatically saving change")
    for {
      result <- commit.save(changeRequestId, actor, reason)
    } yield {
      // always delete the CR
      crRepo.deleteChangeRequest(changeRequestId, actor, reason)
      // and return a no workflow
      noWorfkflow
    }

  }

  // should we keep this one or the previous ??
  def onSuccessWorkflow(changeRequestId: ChangeRequestId, actor:EventActor, reason: Option[String]) : Box[ChangeRequestId] = {
    logger.debug("Automatically saving change")
    for {
      result <- commit.save(changeRequestId, actor, reason)
    } yield {
       // always delete the CR
      crRepo.deleteChangeRequest(changeRequestId, actor, reason)
      // and return a no workflow
      changeRequestId
    }
  }

  def isEditable(currentUserRights:Seq[String],currentStep:WorkflowNodeId, isCreator : Boolean): Boolean = false

  def isPending(currentStep:WorkflowNodeId): Boolean = false
}
