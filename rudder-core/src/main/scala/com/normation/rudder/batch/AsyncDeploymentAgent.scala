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
import com.normation.rudder.domain.log.{RudderEventActor,StartDeployement}

//ask for a new deployment - only message understood by the service !
//actor: the actor who asked for the deployment
final case class StartDeployment(actor:EventActor)


/**
 * State of the deployment agent. 
 */
sealed trait DeployerState
//not currently doing anything
final case object IdleDeployer extends DeployerState
//a deployment is currently running
final case class Processing(id:Long, started: DateTime) extends DeployerState
//a deployment is currently running and an other is queued
final case class ProcessingAndPending(asked: DateTime, current:Processing) extends DeployerState
  
/**
 * Status of the last deployment process
 */
sealed trait CurrentDeploymentStatus
//noting was done for now
final case object NoStatus extends CurrentDeploymentStatus
//last status - success or error
final case class SuccessStatus(id:Long, started: DateTime, ended:DateTime, configuration:Map[NodeId,NodeConfiguration]) extends CurrentDeploymentStatus 
final case class ErrorStatus(id:Long, started: DateTime, ended:DateTime, failure:Failure) extends CurrentDeploymentStatus 


final case class DeploymentStatus(
  current: CurrentDeploymentStatus,
  processing: DeployerState
)

/**
 * Asyn version of the deployment service.
 */
final class AsyncDeploymentAgent(
    deploymentService: DeploymentService
  , eventLogger:EventLogService
) extends LiftActor with Loggable with ListenerManager {

  deploymentManager => 

  val timeFormat = "yyyy/MM/dd HH:mm:ss"

  
  //message from the deployment agent to the manager
  private[this] sealed case class DeploymentResult(id:Long, start: DateTime, end:DateTime, results: Box[Map[NodeId, NodeConfiguration]])
  //message from manager to deployment agent
  private[this] sealed case class NewDeployment(id:Long, started: DateTime)
  
  private[this] var lastFinishedDeployement : CurrentDeploymentStatus = NoStatus
  private[this] var currentDeployerState : DeployerState = IdleDeployer
  private[this] var currentDeploymentId = 0L
  
  
  def getStatus : CurrentDeploymentStatus = lastFinishedDeployement
  def getCurrentState : DeployerState = currentDeployerState
  
  /**
   * Manage what we send on other listener actors
   */
  override def createUpdate = DeploymentStatus(lastFinishedDeployement, currentDeployerState)
  
  
  override protected def lowPriority = {
    //
    // Start a new deployment 
    //
    case StartDeployment(actor) => {
      logger.trace("Deployment manager: receive new deployment request message")
      currentDeployerState match {
        case IdleDeployer => //ok, start a new deployment
          currentDeploymentId += 1 
          val newState = Processing(currentDeploymentId, new DateTime)
          currentDeployerState = newState
          logger.trace("Deployment manager: ask deployer agent to start a deployment")
          eventLogger.saveEventLog(StartDeployement(actor))
          DeployerAgent ! NewDeployment(newState.id, newState.started)
         
        case p@Processing(id, startTime) => //ok, add a pending deployment
          logger.trace("Deployment manager: currently deploying, add a pending deployment request")
          eventLogger.saveEventLog(StartDeployement(actor, details = EventLog.withContent(<addPending alreadyPending="false"/>)))
          currentDeployerState = ProcessingAndPending(new DateTime, p)
          
        case p:ProcessingAndPending => //drop message, one is already pending
          eventLogger.saveEventLog(StartDeployement(actor, details = EventLog.withContent(<addPending alreadyPending="true"/>)))
          logger.info("One deployment process is already pending, ignoring new deployment request")
      }
      //update listeners
      updateListeners()
    }
    
    //
    // response from the deployer
    //
    case DeploymentResult(id, startTime, endTime, result) => {
      
      //process the result
      result match {
        case e:EmptyBox =>
          val m = "Deployment error for process '%s' at %s".format(id, endTime.toString(timeFormat))
          logger.error(m, e)
          lastFinishedDeployement = ErrorStatus(id, startTime, endTime, e ?~! m)
        
        case Full(nodeConfigurations) =>
          logger.info("Successful deployment %s [%s - %s]".format(id, startTime.toString(timeFormat), endTime.toString(timeFormat)))
          lastFinishedDeployement = SuccessStatus(id, startTime, endTime, nodeConfigurations)
      }
      
      //look if there is another process to start and update current deployer status
      currentDeployerState match {
        case IdleDeployer => //should never happen
          logger.debug("Found an IdleDeployer state for deployer agent but it just gave me a result. What happened ?")

        case p:Processing => //ok, come back to IdleDeployer
          currentDeployerState = IdleDeployer
        
        case p:ProcessingAndPending => //come back to IdleDeployer but immediately ask for another deployment
          currentDeployerState = IdleDeployer
          this ! StartDeployment(RudderEventActor)
          
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
      case NewDeployment(id,startTime) => 
        logger.trace("Deployer Agent: start a new deployment")
        try {
          val result = deploymentService.deploy().map { nodeConfs =>
            nodeConfs.map { conf => (NodeId(conf.id), conf) }.toMap
          }
          deploymentManager ! DeploymentResult(id, startTime,new DateTime, result)
        } catch {
          case e:Throwable => e match {
            case x:ThreadDeath => throw x
            case x:InterruptedException => throw x
            case _ => deploymentManager ! DeploymentResult(id,startTime,new DateTime,Failure("Exception caught during deployment process: %s".format(e.getMessage),Full(e), Empty))
          }
        }
          
      //
      //Unexpected messages
      //
      case x => 
        val msg = "Deployment agent does not know how to process message: '%s'".format(x)
        logger.error(msg)
        deploymentManager ! DeploymentResult(-1, new DateTime, new DateTime, Failure(msg))
    }
  }
}
