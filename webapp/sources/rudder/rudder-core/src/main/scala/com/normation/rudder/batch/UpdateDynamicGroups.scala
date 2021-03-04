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

import com.normation.rudder.services.queries._
import com.normation.rudder.domain.nodes.NodeGroupId
import net.liftweb.common._
import net.liftweb.actor._
import org.joda.time._
import com.normation.rudder.domain.eventlog.RudderEventActor
import com.normation.rudder.domain.Constants.DYNGROUP_MINIMUM_UPDATE_INTERVAL
import com.normation.eventlog.ModificationId
import com.normation.utils.StringUuidGenerator
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.logger.ScheduledJobLogger
import org.joda.time.format.ISODateTimeFormat
import com.normation.box._
import com.normation.zio._
import com.normation.errors._
//Message to send to the updater manager to start a new update of all dynamic groups or get results
sealed trait GroupUpdateMessage

final object GroupUpdateMessage {
  final case object StartUpdate       extends GroupUpdateMessage
  final case object ManualStartUpdate extends GroupUpdateMessage
  final case object ForceStartUpdate  extends GroupUpdateMessage
  final case object DelayedUpdate     extends GroupUpdateMessage
  final case class  DynamicUpdateResult(id:Long, modId:ModificationId, start: DateTime, end:DateTime, results: Box[ List[(NodeGroupId, Either[RudderError, DynGroupDiff])]]) extends GroupUpdateMessage
}

//a container to hold the list of dynamic group to update
final case class GroupsToUpdate(ids:Seq[NodeGroupId]) extends AnyVal

sealed trait DynamicGroupUpdaterStates //states into wich the updater process can be
//the process is idle
final case object IdleGroupUpdater extends DynamicGroupUpdaterStates
//an update is currently running for the given nodes
final case class StartDynamicUpdate(id:Long, modId:ModificationId, started: DateTime, groupIds:GroupsToUpdate) extends DynamicGroupUpdaterStates

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
  , asyncDeploymentAgent  : AsyncDeploymentActor
  , uuidGen               : StringUuidGenerator
  , updateInterval        : Int // in minutes
) {

  private val propertyName = "rudder.batch.dyngroup.updateInterval"
  val logger = ScheduledJobLogger

  private val laUpdateDyngroupManager = new LAUpdateDyngroupManager
  //start batch
  if(updateInterval < 1) {
    logger.info("Disable dynamic group updates since property %s is 0 or negative".format(propertyName))
  } else {
    logger.trace("***** starting Dynamic Group Update batch *****")
    laUpdateDyngroupManager ! GroupUpdateMessage.StartUpdate
  }

  def startManualUpdate : Unit = {
    laUpdateDyngroupManager ! GroupUpdateMessage.ManualStartUpdate
  }

  def forceStartUpdate : Unit = {
    laUpdateDyngroupManager ! GroupUpdateMessage.ForceStartUpdate
  }

  def isIdle() = laUpdateDyngroupManager.isIdle()

  ////////////////////////////////////////////////////////////////
  //////////////////// implementation details ////////////////////
  ////////////////////////////////////////////////////////////////

  /*
   * Two actor utility class: one that manage the status (respond to ping,
   * to status command, etc)
   * one that actually process update.
   */

  private class LAUpdateDyngroupManager extends SpecializedLiftActor[GroupUpdateMessage] {
    updateManager =>

    val logger = ScheduledJobLogger

    private var updateId = 0L
    private var lastUpdateTime = new DateTime(0)
    private var avoidedUpdate = 0L
    private var currentState: DynamicGroupUpdaterStates = IdleGroupUpdater
    private var onePending = false
    private var needDeployment = false
    private[this] val isAutomatic = updateInterval > 0
    private[this] val realUpdateInterval = {
      if (updateInterval < DYNGROUP_MINIMUM_UPDATE_INTERVAL && isAutomatic) {
        logger.warn(s"Value '${updateInterval}' for ${propertyName} is too small, using '${DYNGROUP_MINIMUM_UPDATE_INTERVAL}'")
        DYNGROUP_MINIMUM_UPDATE_INTERVAL
      } else {
        updateInterval
      }
    }

    def isIdle() = currentState == IdleGroupUpdater

    private[this] def processUpdate(force: Boolean) = {
      val need = dynGroupService.changesSince(lastUpdateTime).getOrElse(true)
      // if there was a delayedUpdate, we need to force recomputation of groups, or
      // if there is one pending (which
      if(need || force || onePending) {
        logger.trace("***** Start a new update")

        currentState match {
          case IdleGroupUpdater =>
            dynGroupService.getAllDynGroups match {
              case Full(groupIds) =>
                updateId = updateId + 1
                LAUpdateDyngroup ! StartDynamicUpdate(updateId, ModificationId(uuidGen.newUuid), DateTime.now, GroupsToUpdate(groupIds.map(_.id)))
              case e:EmptyBox =>
                val error = (e?~! "Error when trying to get the list of dynamic group to update")
                logger.error( error.messageChain )

            }
          case _:StartDynamicUpdate if(!onePending) => onePending = true
          case _ =>
            logger.debug("Ignoring start dynamic group update request because another update is in progress")
        }
      } else {
        avoidedUpdate = avoidedUpdate + 1
        logger.debug(s"No changes that can lead to a dynamic group update happened since ${lastUpdateTime.toString(ISODateTimeFormat.dateTime())} (total ${avoidedUpdate} times avoided)")
      }
    }

    private[this] def displayNodechange (nodes : Set[NodeId]) : String = {
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
      case GroupUpdateMessage.StartUpdate =>
        if (isAutomatic) {
          // schedule next update, in minutes
          LAPinger.schedule(this, GroupUpdateMessage.StartUpdate, realUpdateInterval*1000L*60)
        } // no else part as there is nothing to do (would only be Unit)
        processUpdate(false)

      case GroupUpdateMessage.ManualStartUpdate =>
        processUpdate(true)

      case GroupUpdateMessage.ForceStartUpdate =>
        lastUpdateTime = new DateTime(0)
        processUpdate(true)

      // This case is launched when an update was pending, it only launch the process
      // and it does not schedule a new update.
      case GroupUpdateMessage.DelayedUpdate =>
        onePending = false
        processUpdate(true)

      //
      //Process a dynamic group update response
      //
      case GroupUpdateMessage.DynamicUpdateResult(id, modId, start, end, results) => //TODO: other log ?
        logger.trace(s"***** Get result for process: ${id}")
        lastUpdateTime = start
        currentState = IdleGroupUpdater

        // If one update is pending, immediately start a new group update
        // We should no deploy during this run, but we still need to compute if we need a deployment for this group update
        // Deployment will be launched when the pending group update will be finished (and even if it does not need any deployment)
        val delayDeployment = onePending

        // Maybe should be done at the end, when we know it's ok to start deployment ...
        if(onePending) {
          logger.debug("Immediatly start another dynamic groups update process: pending request")
          this ! GroupUpdateMessage.DelayedUpdate
        }

        //log some information
        val format = ISODateTimeFormat.dateTimeNoMillis()
        logger.debug(s"Dynamic group update in ${new Duration(end.getMillis - start.getMillis).toPeriod().toString} (started at ${start.toString(format)}, ended at ${end.toString(format)})")
        println(s"Dynamic group update in ${new Duration(end.getMillis - start.getMillis).toPeriod().toString} (started at ${start.toString(format)}, ended at ${end.toString(format)})")

        for {
          result <- results
          (id,eitherRes) <- result
        } {
          eitherRes match {
            case Left(e) =>
              val error = (e.fullMsg + s"Error when updating dynamic group '${id.value}'")
              logger.error(error)
             // e.fullMsg.foreach(ex =>
             //   logger.error("Exception was:", ex)
              //)
            case Right(diff) =>
              val addedNodes = displayNodechange(diff.added)
              val removedNodes = displayNodechange(diff.removed)
              logger.debug(s"Group ${id.value}: adding ${addedNodes}, removing ${removedNodes}")
              //if the diff is not empty, start a new deploy
              if(diff.added.nonEmpty || diff.removed.nonEmpty) {
                logger.info(s"Dynamic group ${id.value}: added node with id: ${addedNodes}, removed: ${removedNodes}")
                // we need to trigger a deployment in this case
                needDeployment = true
              }
          }
        }

        // Deploy only if not updating the group
        if (isIdle() && !delayDeployment && needDeployment) {
          asyncDeploymentAgent ! AutomaticStartDeployment(modId, RudderEventActor)
          needDeployment = false
        }

      //
      //Unexpected messages
      //
      case x => logger.debug(s"Dynamic group updater can't process this message: '${x}'")
    }

    private[this] object LAUpdateDyngroup extends SpecializedLiftActor[StartDynamicUpdate] {

      override protected def messageHandler = {
        //
        //Process a dynamic group update
        //
        case StartDynamicUpdate(processId, modId, startTime, GroupsToUpdate(dynGroupIds)) => {
          logger.trace(s"***** Start a new update, id: ${processId}")
          currentState = StartDynamicUpdate(processId, modId, startTime, GroupsToUpdate(dynGroupIds))
          try {
println("started")

            val results : Box[ List[(NodeGroupId, Either[RudderError, DynGroupDiff])]] = dynGroupIds.accumulateParN(2) { case dynGroupId =>
              println("running")
              dynGroupUpdaterService.update(dynGroupId, modId, RudderEventActor, Some("Update group due to batch update of dynamic groups")).toIO.either.map(x => (dynGroupId, x))
            }.toBox

       //     val results = for {
       //       dynGroupId <- dynGroupIds
       //     } yield {
       //       (dynGroupId, dynGroupUpdaterService.update(dynGroupId, modId, RudderEventActor, Some("Update group due to batch update of dynamic groups")))
       //     }
            updateManager ! GroupUpdateMessage.DynamicUpdateResult(processId, modId, startTime, DateTime.now, results)
          } catch {
            case e:Exception => updateManager ! GroupUpdateMessage.DynamicUpdateResult(processId, modId, startTime,DateTime.now,
                 // SystemError(dynGroupIds.toList.map(id => (id,Left(RudderError("Exception caught during update process."))))))
null)
          }
        }
      }
    }

  }
}
