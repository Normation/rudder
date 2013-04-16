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

package com.normation.rudder.services.workflows

import scala.collection.mutable.Buffer
import scala.collection.mutable.{ Map => MutMap }
import org.joda.time.DateTime
import com.normation.cfclerk.domain.TechniqueName
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.rudder.batch.AsyncDeploymentAgent
import com.normation.rudder.batch.AutomaticStartDeployment
import com.normation.rudder.domain.eventlog.RudderEventActor
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.policies._
import com.normation.rudder.domain.workflows._
import com.normation.rudder.services.policies.DependencyAndDeletionService
import com.normation.utils.Control._
import com.normation.utils.StringUuidGenerator
import net.liftweb.common._
import com.normation.rudder.repository._
import com.normation.rudder.repository.inmemory.InMemoryChangeRequestRepository
import com.normation.rudder.services.eventlog.WorkflowEventLogService
import net.liftweb.http.CometActor
import com.normation.rudder.batch.AsyncWorkflowInfo


case object WorkflowUpdate
/**
 * That service allows to glue Rudder with the
 * workflows engine.
 * It allows to send new ChangeRequest to the engine,
 * and to be notified when one of them reach the end.
 */
trait WorkflowService {

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



  val stepsValue :List[WorkflowNodeId]

  val nextSteps: Map[WorkflowNodeId,WorkflowAction]
  val backSteps: Map[WorkflowNodeId,Seq[(WorkflowNodeId,(ChangeRequestId,EventActor, Option[String]) => Box[WorkflowNodeId])]]

  def findStep(changeRequestId: ChangeRequestId) : Box[WorkflowNodeId]

}

case class WorkflowAction(name:String,actions:Seq[(WorkflowNodeId,(ChangeRequestId,EventActor, Option[String]) => Box[WorkflowNodeId])] )

object NoWorkflowAction extends WorkflowAction("Nothing",Seq())
object WorkflowAction {
  type WorkflowStepFunction = (ChangeRequestId,EventActor, Option[String]) => Box[WorkflowNodeId]
  def apply(name:String,action:(WorkflowNodeId,WorkflowStepFunction)):WorkflowAction = WorkflowAction(name,Seq(action))
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

  val nextSteps: Map[WorkflowNodeId,WorkflowAction] = Map()

  val backSteps: Map[WorkflowNodeId,Seq[(WorkflowNodeId,(ChangeRequestId,EventActor, Option[String]) => Box[WorkflowNodeId])]] = Map()

  def findStep(changeRequestId: ChangeRequestId) : Box[WorkflowNodeId] = Failure("No state when no workflow")

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

}

class TwoValidationStepsWorkflowServiceImpl(
    workflowLogger : WorkflowEventLogService
  , commit         : CommitAndDeployChangeRequestService
  , roWorkflowRepo : RoWorkflowRepository
  , woWorkflowRepo : WoWorkflowRepository
  , workflowComet  : AsyncWorkflowInfo
) extends WorkflowService with Loggable {

  case object Validation extends WorkflowNode {
    val id = WorkflowNodeId("Pending validation")
  }

  case object Deployment extends WorkflowNode {
    val id = WorkflowNodeId("Pending deployment")
  }

  case object Deployed extends WorkflowNode {
    val id = WorkflowNodeId("Deployed")
  }

  case object Cancelled extends WorkflowNode {
    val id = WorkflowNodeId("Cancelled")
  }

  private[this] val steps:List[WorkflowNode] = List(Validation,Deployment,Deployed,Cancelled)

  def getItemsInStep(stepId: WorkflowNodeId) : Box[Seq[ChangeRequestId]] = roWorkflowRepo.getAllByState(stepId)

  val stepsValue = steps.map(_.id)

  val nextSteps: Map[WorkflowNodeId,WorkflowAction] =
    steps.map{
      case Validation => val actions = Seq((Deployment.id,stepValidationToDeployment _),(Deployed.id,stepValidationToDeployed _))
        Validation.id -> WorkflowAction("Validate",actions)
      case Deployment => val action = (Deployed.id,stepDeploymentToDeployed _)
        Deployment.id -> WorkflowAction("Deploy",action)
      case Deployed   => Deployed.id -> NoWorkflowAction
      case Cancelled  => Cancelled.id -> NoWorkflowAction
    }.toMap

  val backSteps: Map[WorkflowNodeId,Seq[(WorkflowNodeId,(ChangeRequestId,EventActor, Option[String]) => Box[WorkflowNodeId])]] =
    steps.map{
      case Validation => Validation.id -> Seq((Cancelled.id,stepValidationToCancelled _))
      case Deployment => Deployment.id -> Seq((Cancelled.id,stepDeploymentToCancelled _))
      case Deployed   => Deployed.id -> Seq()
      case Cancelled  => Cancelled.id -> Seq()
    }.toMap

  def findStep(changeRequestId: ChangeRequestId) : Box[WorkflowNodeId] = {
    roWorkflowRepo.getStateOfChangeRequest(changeRequestId)
  }

  private[this] def changeStep(
      from           : WorkflowNode
    , to             : WorkflowNode
    , changeRequestId: ChangeRequestId
    , actor          : EventActor
    , reason         : Option[String]
  ) : Box[WorkflowNodeId] = {
    (for {
      state <- woWorkflowRepo.updateState(changeRequestId,from.id, to.id)
      workflowStep = WorkflowStepChange(changeRequestId,from.id,to.id)
      log   <- workflowLogger.saveEventLog(workflowStep,actor,reason)
    } yield {
      workflowComet ! WorkflowUpdate
      state
    }) match {
      case Full(state) => Full(state)
      case e:Failure => logger.error(s"Error when changing step in workflow for Change Request ${changeRequestId.value} : ${e.msg}")
                        e
      case Empty => logger.error(s"Error when changing step in workflow for Change Request ${changeRequestId.value} : no reason given")
                    Empty
    }
  }

  private[this] def toFailure(from: WorkflowNode, changeRequestId: ChangeRequestId, actor: EventActor, reason: Option[String]) : Box[WorkflowNodeId] = {
    changeStep(from, Cancelled,changeRequestId,actor,reason)
  }

  def startWorkflow(changeRequestId: ChangeRequestId, actor:EventActor, reason: Option[String]) : Box[WorkflowNodeId] = {
    logger.debug("start workflow")
    for {
      workflow <- woWorkflowRepo.createWorkflow(changeRequestId, Validation.id)
    } yield {
      workflowComet ! WorkflowUpdate
      workflow
    }
  }

  private[this]  def onSuccessWorkflow(from: WorkflowNode, changeRequestId: ChangeRequestId, actor:EventActor, reason: Option[String]) : Box[WorkflowNodeId] = {
    logger.debug("update")
    for {
      save  <- commit.save(changeRequestId, actor, reason)
      state <- changeStep(from,Deployed,changeRequestId,actor,reason)
    } yield {
      state
    }

  }

  //allowed workflow steps


  private[this] def stepValidationToDeployment(changeRequestId:ChangeRequestId, actor:EventActor, reason: Option[String]) : Box[WorkflowNodeId] = {
    changeStep(Validation, Deployment,changeRequestId, actor, reason)
  }


  private[this] def stepValidationToDeployed(changeRequestId:ChangeRequestId, actor:EventActor, reason: Option[String]) : Box[WorkflowNodeId] = {
    onSuccessWorkflow(Validation, changeRequestId, actor, reason)
  }

  private[this] def stepValidationToCancelled(changeRequestId:ChangeRequestId, actor:EventActor, reason: Option[String]) : Box[WorkflowNodeId] = {
    toFailure(Validation, changeRequestId, actor, reason)
  }

  private[this] def stepDeploymentToDeployed(changeRequestId:ChangeRequestId, actor:EventActor, reason: Option[String]) : Box[WorkflowNodeId] = {
    onSuccessWorkflow(Deployment, changeRequestId, actor, reason)
  }


  private[this] def stepDeploymentToCancelled(changeRequestId:ChangeRequestId, actor:EventActor, reason: Option[String]) : Box[WorkflowNodeId] = {
    toFailure(Deployment, changeRequestId, actor, reason)
  }


}
