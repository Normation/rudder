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

package com.normation.rudder.batch

import net.liftweb.actor._
import org.joda.time._
import com.normation.inventory.domain.NodeId
import com.normation.rudder.services.policies.PromiseGenerationService
import net.liftweb.http.ListenerManager
import com.normation.eventlog.{EventActor, EventLog}
import com.normation.rudder.domain.eventlog._
import com.normation.utils.HashcodeCaching
import com.normation.rudder.services.marshalling.DeploymentStatusSerialisation
import com.normation.rudder.services.eventlog.EventLogDeploymentService
import net.liftweb.common._
import com.normation.eventlog.EventLogDetails

import scala.xml.NodeSeq
import com.normation.eventlog.ModificationId
import java.io.File

import com.normation.rudder.domain.logger.PolicyLogger
import com.normation.zio._

sealed trait StartDeploymentMessage

//ask for a new deployment - automatic deployment !
//actor: the actor who asked for the deployment
final case class AutomaticStartDeployment(modId: ModificationId, actor:EventActor) extends HashcodeCaching with StartDeploymentMessage

//ask for a new deployment - manual deployment (human clicked on "regenerate now"
//actor: the actor who asked for the deployment
final case class ManualStartDeployment(modId: ModificationId, actor:EventActor, reason:String) extends HashcodeCaching with StartDeploymentMessage

/**
 * State of the deployment agent.
 */


sealed trait DeployerState
//not currently doing anything
final case object IdleDeployer extends DeployerState with HashcodeCaching
//a deployment is currently running
final case class Processing(id:Long, started: DateTime) extends DeployerState with HashcodeCaching
//a deployment is currently running and an other is queued
final case class ProcessingAndPendingAuto(asked: DateTime, current:Processing, actor : EventActor, eventLogId : Int) extends DeployerState with HashcodeCaching
//a deployment is currently running and a manual is queued
final case class ProcessingAndPendingManual(asked: DateTime, current:Processing, actor : EventActor, eventLogId : Int, reason:String) extends DeployerState with HashcodeCaching

/**
 * Status of the last deployment process
 */
sealed trait CurrentDeploymentStatus

//noting was done for now
final case object NoStatus extends CurrentDeploymentStatus with HashcodeCaching
//last status - success or error
final case class SuccessStatus(id:Long, started: DateTime, ended:DateTime, configuration: Set[NodeId]) extends CurrentDeploymentStatus with HashcodeCaching
final case class ErrorStatus(id:Long, started: DateTime, ended:DateTime, failure:Failure) extends CurrentDeploymentStatus with HashcodeCaching

final case class DeploymentStatus(
  current: CurrentDeploymentStatus,
  processing: DeployerState
) extends HashcodeCaching

/**
 * Async version of the deployment service.
 */

trait AsyncDeploymentAgent {

  def launchDeployment(msg: StartDeploymentMessage) : Unit

}

final class AsyncDeploymentActor(
    deploymentService: PromiseGenerationService
  , eventLogger:EventLogDeploymentService
  , deploymentStatusSerialisation : DeploymentStatusSerialisation
) extends LiftActor with ListenerManager with AsyncDeploymentAgent {

  deploymentManager =>

  val timeFormat = "yyyy-MM-dd HH:mm:ss"
  val logger = PolicyLogger

  //message from the deployment agent to the manager
  private[this] sealed case class DeploymentResult(
      id     : Long
    , modId  : ModificationId
    , start  : DateTime
    , end    : DateTime
    , results: Box[Set[NodeId]]
    , actor  : EventActor
    , eventLogId: Int) extends HashcodeCaching
  //message from manager to deployment agent
  private[this] sealed case class NewDeployment(id:Long, modId:ModificationId, started: DateTime, actor : EventActor, eventLogId : Int) extends HashcodeCaching

  private[this] var lastFinishedDeployement : CurrentDeploymentStatus = getLastFinishedDeployment
  private[this] var currentDeployerState : DeployerState = IdleDeployer
  private[this] var currentDeploymentId = lastFinishedDeployement match {
    case NoStatus => 0L
    case a : SuccessStatus => a.id
    case a : ErrorStatus => a.id
    case _ => 0L
  }

  def getStatus : CurrentDeploymentStatus = lastFinishedDeployement
  def getCurrentState : DeployerState = currentDeployerState

  private[this] def getLastFinishedDeployment: CurrentDeploymentStatus = {
    eventLogger.getLastDeployement() match {
      case Empty =>
        logger.debug("Could not find a last policy update")
        NoStatus
      case m : Failure =>
        logger.debug(s"Error when fetching the last policy update, reason: ${m.messageChain}")
        NoStatus
      case Full(status) => status
    }
  }

  // this method is here for testing purpose.

  override def launchDeployment(msg: StartDeploymentMessage): Unit = this ! msg

  /**
   * Manage what we send on other listener actors
   */
  override def createUpdate = DeploymentStatus(lastFinishedDeployement, currentDeployerState)

  private[this] def WithDetails(xml:NodeSeq)(implicit actor:EventActor, reason: Option[String] = None) = {
    EventLogDetails(
        modificationId = None
      , principal = actor
      , reason    = reason
      , details   = EventLog.withContent(xml)
    )
  }

  // That flag file is used to determine if a policy update was running when the webapp stopped so we can launch a new one when starting
  val policyUpdateRunningFlagPath = "/opt/rudder/etc/policy-update-running"
  val triggerPolicyUpdateFlagPath = "/opt/rudder/etc/trigger-policy-generation"

  private[this] def createFlagFile = {
    val file =  new File(policyUpdateRunningFlagPath)
    try {
        file.createNewFile()
        logger.debug(s"Flag file '${policyUpdateRunningFlagPath}' created")
    } catch {
      // Exception while checking the file existence
      case e : Exception =>
        logger.error(s"An error occurred while creating flag file '${policyUpdateRunningFlagPath}', cause is: ${e.getMessage}")
    }
  }

  private[this] def deleteFlagFile = {
    val runningFile =  new File(policyUpdateRunningFlagPath)
    val triggerFile = new File(triggerPolicyUpdateFlagPath)

    for (file <- runningFile::triggerFile::Nil) {
      try {
        if (file.exists) {
          // delete the file only if it is present
          if (file.delete) {
            // Deleted, come back to normal
            logger.info(s"Flag file '${file.getPath}' successfully removed")
          } else {
            // File could not be deleted, seek for reason
            if (!file.exists()) {
              logger.warn(s"Flag file '${file.getPath}' could not be removed as it does not exist anymore")
            } else {
              logger.error(s"Flag file '${file.getPath}' could not be removed, you may have to remove it manually, cause is: Permission denied or someone is actually editing the file")
            }
          }
        }
      } catch {
        // Exception while checking the file existence
        case e: Exception =>
          logger.error(s"An error occurred while deleting flag file '${file.getPath}', cause is: ${e.getMessage}")
      }
    }
  }
  override protected def lowPriority = {

    //
    // Start a new deployment
    //
    case AutomaticStartDeployment(modId, actor) => {
      implicit val a = actor
      createFlagFile

      logger.trace("Policy updater: receive new automatic policy update request message")

      currentDeployerState match {
        case IdleDeployer => //ok, start a new deployment
          currentDeploymentId += 1
          val newState = Processing(currentDeploymentId, DateTime.now)
          currentDeployerState = newState
          logger.trace("Policy updater: ask to start updating policies")
          val event = eventLogger.repository.saveEventLog(
            modId, AutomaticStartDeployement(WithDetails(NodeSeq.Empty))
          ).either.runNow.toOption
          DeployerAgent ! NewDeployment(newState.id, modId, newState.started, actor, event.flatMap(_.id).getOrElse(0))

        case p @ Processing(id, startTime) => //ok, add a pending deployment
          logger.trace("Policy updater: currently updating policies, add a pending update request")
          val event = eventLogger.repository.saveEventLog(
            modId, AutomaticStartDeployement(WithDetails(<addPending alreadyPending="false"/>))
          ).either.runNow.toOption
          currentDeployerState = ProcessingAndPendingAuto(DateTime.now, p, actor, event.flatMap(_.id).getOrElse(0))

        case p: ProcessingAndPendingAuto => //drop message, one is already pending
          eventLogger.repository.saveEventLog(
            modId, AutomaticStartDeployement(WithDetails(<addPending alreadyPending="true"/>)))
          logger.info("One automatic policy update process is already pending, ignoring new policy update request")

        case p: ProcessingAndPendingManual => //drop message, one is already pending
          eventLogger.repository.saveEventLog(
            modId, AutomaticStartDeployement(WithDetails(<addPending alreadyPending="true"/>)))
          logger.info("One manual policy update process is already pending, ignoring new policy update request")

      }
      //update listeners
      updateListeners()
    }

    case ManualStartDeployment(modId, actor, reason) => {
      implicit val a = actor
      implicit val r = Some(reason)
      createFlagFile

      logger.trace("Policy updater: receive new manual policy update request message")
      currentDeployerState match {
        case IdleDeployer => //ok, start a new deployment
          currentDeploymentId += 1
          val newState = Processing(currentDeploymentId, DateTime.now)
          currentDeployerState = newState
          logger.trace("Policy updater: ask to start updating policies")
          val event = eventLogger.repository.saveEventLog(
              modId, ManualStartDeployement(WithDetails(NodeSeq.Empty))
            ).either.runNow.toOption
          DeployerAgent ! NewDeployment(newState.id, modId, newState.started, actor, event.flatMap(_.id).getOrElse(0))

        case p@Processing(id, startTime) => //ok, add a pending deployment
          logger.trace("Policy updater: currently updating policies, add a pending update request")
          val event = eventLogger.repository.saveEventLog(
              modId, ManualStartDeployement(WithDetails(<addPending alreadyPending="false"/>))
            ).either.runNow.toOption
          currentDeployerState = ProcessingAndPendingManual(DateTime.now, p, actor, event.flatMap(_.id).getOrElse(0), reason)

        case p:ProcessingAndPendingManual => //drop message, one is already pending
          eventLogger.repository.saveEventLog(
              modId, ManualStartDeployement(WithDetails(<addPending alreadyPending="true"/>))
            )
          logger.info("One policy update process is already pending, ignoring new policy update request")

        case p:ProcessingAndPendingAuto => //replace with manual
          val event = eventLogger.repository.saveEventLog(
              modId, ManualStartDeployement(WithDetails(<addPending alreadyPending="true"/>))
            ).either.runNow.toOption
          currentDeployerState = ProcessingAndPendingManual(DateTime.now, p.current, actor, event.flatMap(_.id).getOrElse(0), reason)
          logger.info("One automatic policy update process is already pending, replacing by a manual request")
      }
      //update listeners
      updateListeners()
    }

    //
    // response from the deployer
    //
    case DeploymentResult(id, modId, startTime, endTime, result, actor, deploymentEventId) => {
      deleteFlagFile
      //process the result
      result match {
        case e:EmptyBox =>
          val m = s"Policy update error for process '${id}' at ${endTime.toString(timeFormat)}"
          logger.error(m, e)
          lastFinishedDeployement = ErrorStatus(id, startTime, endTime, e ?~! m)
          eventLogger.repository.saveEventLog(modId, FailedDeployment(EventLogDetails(
              modificationId = None
            , principal = actor
            , details = EventLog.withContent(deploymentStatusSerialisation.serialise(lastFinishedDeployement))
            , cause = Some(deploymentEventId)
            , creationDate = startTime
            , reason = None
          )))

        case Full(nodeIds) =>
          logger.info(s"Successful policy update '${id}' [started ${startTime.toString(timeFormat)} - ended ${endTime.toString(timeFormat)}]")
          lastFinishedDeployement = SuccessStatus(id, startTime, endTime, nodeIds)
          eventLogger.repository.saveEventLog(modId, SuccessfulDeployment(EventLogDetails(
              modificationId = None
            , principal = actor
            , details = EventLog.withContent(deploymentStatusSerialisation.serialise(lastFinishedDeployement))
            , cause = Some(deploymentEventId)
            , creationDate = startTime
            , reason = None
          )))
      }

      //look if there is another process to start and update current deployer status
      currentDeployerState match {
        case IdleDeployer => //should never happen
          logger.debug("Found an IdleDeployer state for policy updater agent but it just gave me a result. What happened ?")

        case p:Processing => //ok, come back to IdleDeployer
          currentDeployerState = IdleDeployer

        case p:ProcessingAndPendingAuto => //come back to IdleDeployer but immediately ask for another deployment
          currentDeployerState = IdleDeployer
          this ! AutomaticStartDeployment(modId, RudderEventActor)

        case p:ProcessingAndPendingManual => //come back to IdleDeployer but immediately ask for another deployment
          currentDeployerState = IdleDeployer
          this ! ManualStartDeployment(modId, RudderEventActor, p.reason)

      }
      //update listeners
      updateListeners()
    }

    //
    //Unexpected messages
    //
    case x => logger.debug("Policy updater does not know how to process message: '%s'".format(x))
  }

  /**
   * The internal agent that will actually do the deployment
   * Long time running process, I/O consuming.
   */
  private[this] object DeployerAgent extends LiftActor {
    val logger = PolicyLogger

    override protected def messageHandler = {
      //
      // Start a new deployment
      //
      case NewDeployment(id, modId, startTime, actor, eventId) =>
        logger.trace("Policy updater Agent: start to update policies")
        try {
          val result = deploymentService.deploy()

          result match {
            case Full(_) => // nothing to report
            case m: Failure =>
              logger.error(s"Error when updating policy, reason was: ${m.messageChain}")
              m.rootExceptionCause.foreach { ex =>
                logger.error("Root exception was: " + ex.getMessage)
              }
            case Empty => logger.error("Error when updating policy (no reason given)")
          }

          deploymentManager ! DeploymentResult(id, modId, startTime,DateTime.now, result, actor, eventId)
        } catch {
          case e:Exception => deploymentManager ! DeploymentResult(id, modId, startTime, DateTime.now, Failure(s"Exception caught during policy update process: ${e.getMessage}",Full(e), Empty), actor, eventId)
        }

      //
      //Unexpected messages
      //
      case x =>
        val msg = s"Policy updater agent does not know how to process message: '${x}'"
        logger.error(msg)
        deploymentManager ! DeploymentResult(-1, ModificationId.dummy, DateTime.now, DateTime.now, Failure(msg), RudderEventActor, 0)
    }
  }
}
