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

package com.normation.rudder.batch


import net.liftweb.common._
import net.liftweb.actor._
import org.joda.time._
import com.normation.rudder.domain.servers.NodeConfiguration
import com.normation.inventory.domain.NodeId
import com.normation.rudder.services.policies.DeploymentService
import net.liftweb.http.ListenerManager
import com.normation.eventlog.{EventActor,EventLogService,EventLog}
import com.normation.rudder.domain.eventlog._
import com.normation.utils.HashcodeCaching
import com.normation.rudder.services.marshalling.DeploymentStatusSerialisation
import com.normation.rudder.services.eventlog.EventLogDeploymentService
import net.liftweb.common._
import com.normation.eventlog.EventLogDetails
import scala.xml.NodeSeq

//ask for a new deployment - automatic deployment !
//actor: the actor who asked for the deployment
final case class AutomaticStartDeployment(actor:EventActor) extends HashcodeCaching 

//ask for a new deployment - manual deployment (human clicked on "regenerate now"
//actor: the actor who asked for the deployment
final case class ManualStartDeployment(actor:EventActor, reason:String) extends HashcodeCaching

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
final case class SuccessStatus(id:Long, started: DateTime, ended:DateTime, configuration:Map[NodeId,NodeConfiguration]) extends CurrentDeploymentStatus with HashcodeCaching 
final case class ErrorStatus(id:Long, started: DateTime, ended:DateTime, failure:Failure) extends CurrentDeploymentStatus with HashcodeCaching 


final case class DeploymentStatus(
  current: CurrentDeploymentStatus,
  processing: DeployerState
) extends HashcodeCaching 

/**
 * Asyn version of the deployment service.
 */
final class AsyncDeploymentAgent(
    deploymentService: DeploymentService
  , eventLogger:EventLogDeploymentService with EventLogService
  , autoDeployOnModification : Boolean
  , deploymentStatusSerialisation : DeploymentStatusSerialisation
) extends LiftActor with Loggable with ListenerManager {

  deploymentManager => 

  val timeFormat = "yyyy/MM/dd HH:mm:ss"

  def isAutoDeploy = autoDeployOnModification
  
  //message from the deployment agent to the manager
  private[this] sealed case class DeploymentResult(
      id     : Long
    , start  : DateTime
    , end    : DateTime
    , results: Box[Map[NodeId, NodeConfiguration]]
    , actor  : EventActor
    , eventLogId: Int) extends HashcodeCaching 
  //message from manager to deployment agent
  private[this] sealed case class NewDeployment(id:Long, started: DateTime, actor : EventActor, eventLogId : Int) extends HashcodeCaching 
  
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
  
  /**
   * 
   */
  private[this] def getLastFinishedDeployment: CurrentDeploymentStatus = {
    eventLogger.getLastDeployement() match {
      case Empty =>  
        logger.debug("Could not find a last deployment")
        NoStatus
      case m : Failure => 
        logger.debug("Error when fetching the last deployement, reason %s".format(m.messageChain))
        NoStatus
      case Full(status) => status 
    }
  }
  
  /**
   * Manage what we send on other listener actors
   */
  override def createUpdate = DeploymentStatus(lastFinishedDeployement, currentDeployerState)

  private[this] def WithDetails(xml:NodeSeq)(implicit actor:EventActor, reason: Option[String] = None) = {
    EventLogDetails(
        principal = actor
      , reason    = reason
      , details   = EventLog.withContent(xml)
    )
  }
  
  override protected def lowPriority = {

    
    //
    // Start a new deployment 
    //
    case AutomaticStartDeployment(actor) => {
      implicit val a = actor
      logger.trace("Deployment manager: receive new automatic deployment request message")
      autoDeployOnModification match {
        case false => 
          logger.trace("Deployment manager: the automatic deployment are not permitted")
        case true => 
          currentDeployerState match {
            case IdleDeployer => //ok, start a new deployment
              currentDeploymentId += 1
              val newState = Processing(currentDeploymentId, DateTime.now)
              currentDeployerState = newState
              logger.trace("Deployment manager: ask deployer agent to start a deployment")
              val event = eventLogger.saveEventLog(
                  AutomaticStartDeployement(WithDetails(NodeSeq.Empty))
                )
              DeployerAgent ! NewDeployment(newState.id, newState.started, actor, event.flatMap(_.id).getOrElse(0))
           
            case p@Processing(id, startTime) => //ok, add a pending deployment
              logger.trace("Deployment manager: currently deploying, add a pending deployment request")
              val event = eventLogger.saveEventLog(
                  AutomaticStartDeployement(WithDetails(<addPending alreadyPending="false"/>))
                )
              currentDeployerState = ProcessingAndPendingAuto(DateTime.now, p, actor, event.flatMap(_.id).getOrElse(0))
            
            case p:ProcessingAndPendingAuto => //drop message, one is already pending
              eventLogger.saveEventLog(
                  AutomaticStartDeployement(WithDetails(<addPending alreadyPending="true"/>))
                )
              logger.info("One automatic deployment process is already pending, ignoring new deployment request")
      
            case p:ProcessingAndPendingManual => //drop message, one is already pending
              eventLogger.saveEventLog(
                  AutomaticStartDeployement(WithDetails(<addPending alreadyPending="true"/>))
                )
              logger.info("One manual deployment process is already pending, ignoring new deployment request")

          }
      }
      //update listeners
      updateListeners()
    }
    
    case ManualStartDeployment(actor, reason) => {
      implicit val a = actor
      implicit val r = Some(reason)
      
      logger.trace("Deployment manager: receive new manual deployment request message")
      currentDeployerState match {
        case IdleDeployer => //ok, start a new deployment
          currentDeploymentId += 1 
          val newState = Processing(currentDeploymentId, DateTime.now)
          currentDeployerState = newState
          logger.trace("Deployment manager: ask deployer agent to start a deployment")
          val event = eventLogger.saveEventLog(
              ManualStartDeployement(WithDetails(NodeSeq.Empty))
            )
          DeployerAgent ! NewDeployment(newState.id, newState.started, actor, event.flatMap(_.id).getOrElse(0))
         
        case p@Processing(id, startTime) => //ok, add a pending deployment
          logger.trace("Deployment manager: currently deploying, add a pending deployment request")
          val event = eventLogger.saveEventLog(
              ManualStartDeployement(WithDetails(<addPending alreadyPending="false"/>))
            )
          currentDeployerState = ProcessingAndPendingManual(DateTime.now, p, actor, event.flatMap(_.id).getOrElse(0), reason)
          
        case p:ProcessingAndPendingManual => //drop message, one is already pending
          eventLogger.saveEventLog(
              ManualStartDeployement(WithDetails(<addPending alreadyPending="true"/>))
            )
          logger.info("One deployment process is already pending, ignoring new deployment request")
          
        case p:ProcessingAndPendingAuto => //replace with manual
          val event = eventLogger.saveEventLog(
              ManualStartDeployement(WithDetails(<addPending alreadyPending="true"/>))
            )
          currentDeployerState = ProcessingAndPendingManual(DateTime.now, p.current, actor, event.flatMap(_.id).getOrElse(0), reason)
          logger.info("One automatic deployment process is already pending, replacing by a manual request")
      }
      //update listeners
      updateListeners()
    }
    
    //
    // response from the deployer
    //
    case DeploymentResult(id, startTime, endTime, result, actor, deploymentEventId) => {
      
      //process the result
      result match {
        case e:EmptyBox =>
          val m = "Deployment error for process '%s' at %s".format(id, endTime.toString(timeFormat))
          logger.error(m, e)
          lastFinishedDeployement = ErrorStatus(id, startTime, endTime, e ?~! m)
          eventLogger.saveEventLog(FailedDeployment(EventLogDetails(
              principal = actor
            , details = EventLog.withContent(deploymentStatusSerialisation.serialise(lastFinishedDeployement))
            , cause = Some(deploymentEventId)
            , creationDate = startTime
            , reason = None
          )))
          
        case Full(nodeConfigurations) =>
          logger.info("Successful deployment %s [%s - %s]".format(id, startTime.toString(timeFormat), endTime.toString(timeFormat)))
          lastFinishedDeployement = SuccessStatus(id, startTime, endTime, nodeConfigurations)
          eventLogger.saveEventLog(SuccessfulDeployment(EventLogDetails(
              principal = actor
            , details = EventLog.withContent(deploymentStatusSerialisation.serialise(lastFinishedDeployement))
            , cause = Some(deploymentEventId)
            , creationDate = startTime
            , reason = None
          )))
      }
      
      //look if there is another process to start and update current deployer status
      currentDeployerState match {
        case IdleDeployer => //should never happen
          logger.debug("Found an IdleDeployer state for deployer agent but it just gave me a result. What happened ?")

        case p:Processing => //ok, come back to IdleDeployer
          currentDeployerState = IdleDeployer
        
        case p:ProcessingAndPendingAuto => //come back to IdleDeployer but immediately ask for another deployment
          currentDeployerState = IdleDeployer
          this ! AutomaticStartDeployment(RudderEventActor)
          
        case p:ProcessingAndPendingManual => //come back to IdleDeployer but immediately ask for another deployment
          currentDeployerState = IdleDeployer
          this ! ManualStartDeployment(RudderEventActor, p.reason)
          
      }
      //update listeners
      updateListeners()
    }
    
    //
    //Unexpected messages
    //
    case x => logger.debug("Deployment manager does not know how to process message: '%s'".format(x))
  }
  
  

  /**
   * The internal agent that will actually do the deployment 
   * Long time running process, I/O consuming. 
   */
  private[this] object DeployerAgent extends LiftActor with Loggable {
    override protected def messageHandler = {
      //
      // Start a new deployment 
      //
      case NewDeployment(id,startTime, actor, eventId) => 
        logger.trace("Deployer Agent: start a new deployment")
        try {
          val result = deploymentService.deploy().map { nodeConfs =>
            nodeConfs.map { conf => (NodeId(conf.id), conf) }.toMap
          }
          deploymentManager ! DeploymentResult(id, startTime,DateTime.now, result, actor, eventId)
        } catch {
          case e:Throwable => e match {
            case x:ThreadDeath => throw x
            case x:InterruptedException => throw x
            case _ => deploymentManager ! DeploymentResult(id,startTime,DateTime.now,Failure("Exception caught during deployment process: %s".format(e.getMessage),Full(e), Empty), actor, eventId)
          }
        }
          
      //
      //Unexpected messages
      //
      case x => 
        val msg = "Deployment agent does not know how to process message: '%s'".format(x)
        logger.error(msg)
        deploymentManager ! DeploymentResult(-1, DateTime.now, DateTime.now, Failure(msg), RudderEventActor, 0)
    }
  }
}
