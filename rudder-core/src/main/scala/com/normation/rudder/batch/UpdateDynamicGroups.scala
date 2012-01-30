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

import com.normation.rudder.services.queries._
import com.normation.rudder.domain.nodes.{NodeGroup,NodeGroupId}
import net.liftweb.common._
import net.liftweb.actor._
import org.joda.time._
import com.normation.rudder.domain.log.RudderEventActor
import com.normation.rudder.domain.Constants.DYNGROUP_MINIMUM_UPDATE_INTERVAL
import com.normation.utils.HashcodeCaching

//Message to send to the updater manager to start a new update of all dynamic groups
case object StartUpdate
case object ManualStartUpdate

//a container to hold the list of dynamic group to update
case class GroupsToUpdate(ids:Seq[NodeGroupId]) extends HashcodeCaching 


sealed trait UpdaterStates //states into wich the updater process can be
//the process is idle
case object IdleUdater extends UpdaterStates
//an update is currently running for the given nodes
case class StartProcessing(id:Long, started: DateTime, groupIds:GroupsToUpdate) extends UpdaterStates with HashcodeCaching 
//the process gave a result
case class UpdateResult(id:Long, start: DateTime, end:DateTime, results: Map[NodeGroupId, Box[DynGroupDiff]]) extends UpdaterStates with HashcodeCaching 

  


/**
 * A class that periodically update all dynamic groups to see if
 * they need some update (add / remove nodes)
 * 
 * updateInterval has a special semantic:
 * - for 0 or a negative value, does not start the updater
 * - for updateInterval between 1 and a minimum value, use the minimum value
 * - else, use the given value. 
 */
class UpdateDynamicGroups(
    dynGroupService       : DynGroupService
  , dynGroupUpdaterService: DynGroupUpdaterService
  , asyncDeploymentAgent  : AsyncDeploymentAgent
  , updateInterval        : Int // in minutes
) extends Loggable {
  
  private val propertyName = "rudder.batch.dyngroup.updateInterval"
    
  private val laUpdateDyngroupManager = new LAUpdateDyngroupManager
  //start batch
  if(updateInterval < 1) {
    logger.info("Disable dynamic group updates sinces property %s is 0 or negative".format(propertyName))
  } else {
    logger.trace("***** starting Dynamic Group Update batch *****")
    laUpdateDyngroupManager ! StartUpdate
  }
  
  
  def startManualUpdate : Unit = {
    laUpdateDyngroupManager ! ManualStartUpdate
  }
  
  ////////////////////////////////////////////////////////////////
  //////////////////// implementation details ////////////////////
  ////////////////////////////////////////////////////////////////

  /*
   * Two actor utility class: one that manage the status (respond to ping,
   * to status command, etc)
   * one that actually process update. 
   */
  
  private class LAUpdateDyngroupManager extends LiftActor with Loggable {
    updateManager => 
    
    private var updateId = 0L
    private var currentState: UpdaterStates = IdleUdater
    private var onePending = false
    private var realUpdateInterval = {
      if(updateInterval < DYNGROUP_MINIMUM_UPDATE_INTERVAL) {
        logger.warn("Value '%s' for %s is too small, using '%s'".format(
            updateInterval, propertyName, DYNGROUP_MINIMUM_UPDATE_INTERVAL
        ))
        DYNGROUP_MINIMUM_UPDATE_INTERVAL
      } else {
        updateInterval
      }
    }
    
    private[this] def processUpdate = {
        logger.trace("***** Start a new update")
        currentState match {
          case IdleUdater => 
            dynGroupService.getAllDynGroups match {
              case Full(groupIds) => 
                updateId = updateId + 1
                LAUpdateDyngroup ! StartProcessing(updateId, DateTime.now, GroupsToUpdate(groupIds))
              case e:EmptyBox => 
                val error = (e?~! "Errro when trying to get the list of dynamic group to update")
                
            }
          case _:StartProcessing if(!onePending) => onePending = true
          case _ => 
            logger.error("Ignoring start update dynamic group request because one other update still processing".format())
        }
    }
    
    override protected def messageHandler = {
     
      //
      //Ask for a new dynamic group update
      //
      case StartUpdate => 
        //schedule next update, in minutes
        LAPinger.schedule(this, StartUpdate, realUpdateInterval*1000*60)
        processUpdate
      
      case ManualStartUpdate => 
        processUpdate
        
      //
      //Process a dynamic group update response
      //
      case UpdateResult(id,start,end,results) => //TODO: other log ?
        logger.trace("***** Get result for process: " + id)

        currentState = IdleUdater
        //if one update is pending, immediatly start one other
        
        if(onePending) {
          onePending = false
          logger.debug("Immediatly start an other update process: pending request")
          this ! StartUpdate
        }
      
        //log some information
        val format = "yyyy/MM/dd HH:mm:ss"
        logger.debug("Dynamic group update started at %s, ended at %s".format(start.toString(format), end.toString(format)))
        for {
          (id,boxRes) <- results
        } {
          boxRes match {
            case e:EmptyBox => 
              val error = (e ?~! "Error when updating dynamic group %s :".format(id))
              logger.error(error.messageChain)
            case Full(diff) => 
              logger.debug("Group %s: adding [%s], removing [%s]".format(id,diff.added.map(_.value), diff.removed.map(_.value)))
              //if the diff is not empty, start a new deploy
              if(diff.added.nonEmpty || diff.removed.nonEmpty) {
                logger.info("Dynamic group %s: added node with id: [%s], removed: [%s]".format(id,diff.added.map(_.value), diff.removed.map(_.value)))
                asyncDeploymentAgent ! AutomaticStartDeployment(RudderEventActor)
              }
          }
        }
        
      //
      //Unexpected messages
      //
      case x => logger.debug("Don't know how to process message: '%s'".format(x))
    }

    
    private[this] object LAUpdateDyngroup extends LiftActor {
      
      override protected def messageHandler = {
        //
        //Process a dynamic group update 
        //
        case StartProcessing(processId, startTime, GroupsToUpdate(dynGroupIds)) => {
          logger.trace("***** Start a new update, id: " + processId)
          try {
            val results = for {
              dynGroupId <- dynGroupIds
            } yield {
              (dynGroupId, dynGroupUpdaterService.update(dynGroupId,RudderEventActor))
            }
            updateManager ! UpdateResult(processId, startTime, DateTime.now, results.toMap)
          } catch {
            case e:Throwable => e match {
              case x:ThreadDeath => throw x
              case x:InterruptedException => throw x
              case _ => updateManager ! UpdateResult(processId,startTime,DateTime.now,
                  dynGroupIds.map(id => (id,Failure("Exception caught during update process.",Full(e), Empty))).toMap)
            }
          }
        }
     
        //
        //Unexpected messages
        //
        case x => logger.debug("Don't know how to process message: '%s'".format(x))
      }
    }     
    
  }
}
