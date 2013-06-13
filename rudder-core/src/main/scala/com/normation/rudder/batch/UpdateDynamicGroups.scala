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
import com.normation.rudder.domain.eventlog.RudderEventActor
import com.normation.rudder.domain.Constants.DYNGROUP_MINIMUM_UPDATE_INTERVAL
import com.normation.utils.HashcodeCaching
import com.normation.inventory.domain.NodeId

//Message to send to the updater manager to start a new update of all dynamic groups
case object StartUpdate
case object ManualStartUpdate
case object DelayedUpdate

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
    logger.info("Disable dynamic group updates since property %s is 0 or negative".format(propertyName))
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
    private[this] val isAutomatic = updateInterval > 0
    private[this] val realUpdateInterval = {
      if (updateInterval < DYNGROUP_MINIMUM_UPDATE_INTERVAL && isAutomatic) {
        logger.warn("Value '%s' for %s is too small, using '%s'".format(
           updateInterval, propertyName, DYNGROUP_MINIMUM_UPDATE_INTERVAL
        ) )
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
                val error = (e?~! "Error when trying to get the list of dynamic group to update")

            }
          case _:StartProcessing if(!onePending) => onePending = true
          case _ =>
            logger.error("Ignoring start dynamic group update request because another update is in progress")
        }
    }

    private[this] def displayNodechange (nodes : Seq[NodeId]) : String = {
      if (nodes.nonEmpty) {
        nodes.map(_.value).mkString("[ ",", "," ]")
      } else {
        "nothing"
      }
    }

    override protected def messageHandler = {

      //
      //Ask for a new dynamic group update
      //
      case StartUpdate =>
        if (isAutomatic) {
          // schedule next update, in minutes
          LAPinger.schedule(this, StartUpdate, realUpdateInterval*1000L*60)
        } // no else part as there is nothing to do (would only be Unit)
        processUpdate

      case ManualStartUpdate =>
        processUpdate

      // This case is launched when an update was pending, it only launch the process
      // and it does not schedule a new update.
      case DelayedUpdate =>
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
          logger.debug("Immediatly start another update process: pending request")
          this ! DelayedUpdate
        }

        //log some information
        val format = "yyyy/MM/dd HH:mm:ss"
        logger.debug("Dynamic group update started at %s, ended at %s".format(start.toString(format), end.toString(format)))
        for {
          (id,boxRes) <- results
        } {
          boxRes match {
            case e:EmptyBox =>
              val error = (e ?~! "Error when updating dynamic group %s".format(id.value))
              logger.error(error.messageChain)
            case Full(diff) =>
              val addedNodes = displayNodechange(diff.added)
              val removedNodes = displayNodechange(diff.removed)
              logger.debug("Group %s: adding %s, removing %s".format(id.value, addedNodes, removedNodes))
              //if the diff is not empty, start a new deploy
              if(diff.added.nonEmpty || diff.removed.nonEmpty) {
                logger.info("Dynamic group %s: added node with id: %s, removed: %s".format(id.value, addedNodes, removedNodes))
                asyncDeploymentAgent ! AutomaticStartDeployment(RudderEventActor)
              }
          }
        }

      //
      //Unexpected messages
      //
      case x => logger.debug("Dynamic group updater can't process this message: '%s'".format(x))
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
              (dynGroupId, dynGroupUpdaterService.update(dynGroupId,RudderEventActor, Some("Update group due to batch update of dynamic groups")))
            }
            updateManager ! UpdateResult(processId, startTime, DateTime.now, results.toMap)
          } catch {
            case e:Exception => updateManager ! UpdateResult(processId,startTime,DateTime.now,
                  dynGroupIds.map(id => (id,Failure("Exception caught during update process.",Full(e), Empty))).toMap)
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
