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

import com.normation.box.*
import com.normation.errors.*
import com.normation.eventlog.ModificationId
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.Constants.DYNGROUP_MINIMUM_UPDATE_INTERVAL
import com.normation.rudder.domain.eventlog.RudderEventActor
import com.normation.rudder.domain.logger.DynamicGroupLoggerPure
import com.normation.rudder.domain.logger.ScheduledJobLogger
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.services.queries.*
import com.normation.rudder.tenants.ChangeContext
import com.normation.rudder.tenants.QueryContext
import com.normation.rudder.utils.ParseMaxParallelism
import com.normation.utils.DateFormaterService
import com.normation.utils.StringUuidGenerator
import com.normation.utils.Utils.DateToIsoString
import com.normation.zio.*
import java.time.Duration
import java.time.Instant
import net.liftweb.actor.*
import net.liftweb.common.*
import org.joda.time.DateTime
import org.joda.time.DateTimeZone

//Message to send to the updater manager to start a new update of all dynamic groups or get results
sealed trait GroupUpdateMessage

object GroupUpdateMessage {
  case object StartUpdate       extends GroupUpdateMessage
  case object ManualStartUpdate extends GroupUpdateMessage
  case object ForceStartUpdate  extends GroupUpdateMessage
  case object DelayedUpdate     extends GroupUpdateMessage
  final case class DynamicUpdateResult(
      id:      Long,
      modId:   ModificationId,
      start:   Instant,
      end:     Instant,
      results: Box[List[(NodeGroupId, Either[RudderError, DynGroupDiff])]]
  ) extends GroupUpdateMessage
}

//a container to hold the list of dynamic group to update
final case class GroupsToUpdate(idsWithoutDependencies: Seq[NodeGroupId], idsWithDependencies: Seq[NodeGroupId])

sealed trait DynamicGroupUpdaterStates //states into which the updater process can be
//the process is idle
case object IdleGroupUpdater extends DynamicGroupUpdaterStates
//an update is currently running for the given nodes
final case class StartDynamicUpdate(id: Long, modId: ModificationId, started: Instant, groupIds: GroupsToUpdate)
    extends DynamicGroupUpdaterStates

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
    dynGroupService:               DynGroupService,
    dynGroupUpdaterService:        DynGroupUpdaterService,
    propertiesSyncService:         NodePropertiesSyncService,
    asyncDeploymentAgent:          AsyncDeploymentActor,
    uuidGen:                       StringUuidGenerator,
    updateInterval:                Int, // in minutes
    getComputeDynGroupParallelism: () => Box[String]
) {

  private val propertyName = "rudder.batch.dyngroup.updateInterval"

  protected lazy val laUpdateDyngroupManager = new LAUpdateDyngroupManager
  // start batch
  if (updateInterval < 1) {
    ScheduledJobLogger.info("Disable dynamic group updates since property %s is 0 or negative".format(propertyName))
  } else {
    ScheduledJobLogger.debug(s"Starting Dynamic Group Update scheduled job with update interval: ${updateInterval} min")
    laUpdateDyngroupManager ! GroupUpdateMessage.StartUpdate
  }

  def startManualUpdate: Unit = {
    laUpdateDyngroupManager ! GroupUpdateMessage.ManualStartUpdate
  }

  def forceStartUpdate: Unit = {
    laUpdateDyngroupManager ! GroupUpdateMessage.ForceStartUpdate
  }

  def isIdle(): Boolean = laUpdateDyngroupManager.isIdle()

  ////////////////////////////////////////////////////////////////
  //////////////////// implementation details ////////////////////
  ////////////////////////////////////////////////////////////////

  /*
   * Two actor utility class: one that manage the status (respond to ping,
   * to status command, etc)
   * one that actually process update.
   */

  class LAUpdateDyngroupManager extends SpecializedLiftActor[GroupUpdateMessage] {
    updateManager =>

    private var updateId       = 0L
    private var lastUpdateTime = new DateTime(0, DateTimeZone.UTC)
    private var avoidedUpdate  = 0L
    private var currentState: DynamicGroupUpdaterStates = IdleGroupUpdater
    private var onePending         = false
    private var needDeployment     = false
    private val isAutomatic        = updateInterval > 0
    private val realUpdateInterval = {
      if (updateInterval < DYNGROUP_MINIMUM_UPDATE_INTERVAL && isAutomatic) {
        ScheduledJobLogger.warn(
          s"Value '${updateInterval}' for ${propertyName} is too small, using '${DYNGROUP_MINIMUM_UPDATE_INTERVAL}'"
        )
        DYNGROUP_MINIMUM_UPDATE_INTERVAL
      } else {
        updateInterval
      }
    }

    def isIdle(): Boolean = currentState == IdleGroupUpdater

    private def processUpdate(force: Boolean) = {
      val need = dynGroupService.changesSince(lastUpdateTime).getOrElse(true)
      // if there was a delayedUpdate, we need to force re-computation of groups, or
      // if there is one pending (which
      if (need || force || onePending) {
        if (DynamicGroupLoggerPure.logEffect.isDebugEnabled) {
          val reason = List(
            if (force) Some("force-reload") else None,
            // last update time is set to EPOCH on force, so avoid displaying that if force is set
            if (need && !force) Some(s"there is change since last update on ${lastUpdateTime}") else None,
            if (onePending) Some("processing pending delayed update") else None
          ).flatten.mkString("; ")
          DynamicGroupLoggerPure.logEffect.debug(s"Start a new dynamic group update (reason: ${reason})")
        }

        currentState match {
          case IdleGroupUpdater =>
            dynGroupService.getAllDynGroupsWithandWithoutDependencies() match {
              case Full(groupIds) =>
                updateId = updateId + 1
                LAUpdateDyngroup ! StartDynamicUpdate(
                  updateId,
                  ModificationId(uuidGen.newUuid),
                  Instant.now(),
                  GroupsToUpdate(groupIds._1, groupIds._2)
                )
              case e: EmptyBox =>
                val error = (e ?~! "Error when trying to get the list of dynamic group to update")
                DynamicGroupLoggerPure.logEffect.error(error.messageChain)

            }
          case _: StartDynamicUpdate if (!onePending) => onePending = true
          case _                =>
            DynamicGroupLoggerPure.logEffect.debug(
              "Ignoring start dynamic group update request because another update is in progress"
            )
        }
      } else {
        avoidedUpdate = avoidedUpdate + 1
        DynamicGroupLoggerPure.logEffect.debug(
          s"No changes that can lead to a dynamic group update happened since ${lastUpdateTime.toIsoStringNoMillis} (total ${avoidedUpdate} times avoided)"
        )
      }
    }

    private def displayNodechange(nodes: Set[NodeId]): String = {
      if (nodes.nonEmpty) {
        nodes.map(_.value).mkString("[ ", ", ", " ]")
      } else {
        "nothing"
      }
    }

    override protected def messageHandler: PartialFunction[GroupUpdateMessage, Unit] = PartialFunction.fromFunction {

      //
      // Ask for a new dynamic group update
      //
      case GroupUpdateMessage.StartUpdate =>
        ScheduledJobLogger.debug("Dynamic group update starts")
        if (isAutomatic) {
          // schedule next update, in minutes
          LAPinger.schedule(this, GroupUpdateMessage.StartUpdate, realUpdateInterval * 1000L * 60)
        } // no else part as there is nothing to do (would only be Unit)
        processUpdate(false)

      case GroupUpdateMessage.ManualStartUpdate =>
        processUpdate(true)

      case GroupUpdateMessage.ForceStartUpdate                                    =>
        lastUpdateTime = new DateTime(0, DateTimeZone.UTC)
        processUpdate(true)

      // This case is launched when an update was pending, it only launch the process
      // and it does not schedule a new update.
      case GroupUpdateMessage.DelayedUpdate                                       =>
        onePending = false
        processUpdate(true)

      //
      // Process a dynamic group update response
      //
      case GroupUpdateMessage.DynamicUpdateResult(id, modId, start, end, results) => // TODO: other log ?
        DynamicGroupLoggerPure.logEffect.trace(s"Get result for process: ${id}")
        lastUpdateTime = DateFormaterService.toDateTime(start)
        currentState = IdleGroupUpdater

        // If one update is pending, immediately start a new group update
        // We should no deploy during this run, but we still need to compute if we need a deployment for this group update
        // Deployment will be launched when the pending group update will be finished (and even if it does not need any deployment)
        val delayDeployment = onePending

        // Maybe should be done at the end, when we know it's ok to start deployment ...
        if (onePending) {
          DynamicGroupLoggerPure.logEffect.debug("Immediately start another dynamic groups update process: pending request")
          this ! GroupUpdateMessage.DelayedUpdate
        }

        // log some information
        DynamicGroupLoggerPure.logEffect.debug(
          s"Dynamic group update in ${Duration.between(start, end)} (started at ${DateFormaterService
              .serializeInstant(start)}, ended at ${DateFormaterService.serializeInstant(end)})"
        )

        for {
          result          <- results
          (id, eitherRes) <- result
        } {
          eitherRes match {
            case Left(e)     =>
              val error = (e.fullMsg + s" Error when updating dynamic group '${id.serialize}'")
              DynamicGroupLoggerPure.logEffect.error(error)
            case Right(diff) =>
              val addedNodes   = displayNodechange(diff.added)
              val removedNodes = displayNodechange(diff.removed)
              DynamicGroupLoggerPure.logEffect.debug(s"Group ${id.serialize}: adding ${addedNodes}, removing ${removedNodes}")
              // if the diff is not empty, start a new deploy
              if (diff.added.nonEmpty || diff.removed.nonEmpty) {
                DynamicGroupLoggerPure.logEffect.info(
                  s"Dynamic group ${id.serialize}: added node with id: ${addedNodes}, removed: ${removedNodes}"
                )
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
    }

    private object LAUpdateDyngroup extends SpecializedLiftActor[StartDynamicUpdate] {

      override protected def messageHandler: PartialFunction[StartDynamicUpdate, Unit] = {
        //
        // Process a dynamic group update
        //
        case StartDynamicUpdate(processId, modId, startTime, GroupsToUpdate(dynGroupIds, dynGroupsWithDependencyIds)) => {
          DynamicGroupLoggerPure.logEffect.trace(s"Start a new dynamic group update, id: ${processId}")
          currentState = StartDynamicUpdate(processId, modId, startTime, GroupsToUpdate(dynGroupIds, dynGroupsWithDependencyIds))
          try {

            // We want to limit the number of parallel execution and threads to the number of core/2 (minimum 1) by default.
            // This is taken from the system environment variable because we really want to be able to change it at runtime.
            val maxParallelism = ParseMaxParallelism(
              getComputeDynGroupParallelism().getOrElse("1"),
              1,
              "rudder_compute_dyngroups_max_parallelism",
              (s: String) => DynamicGroupLoggerPure.logEffect.warn(s)
            )

            val result = (for {
              _                            <-
                DynamicGroupLoggerPure.debug(
                  s"Starting computation of dynamic groups with max ${maxParallelism} threads for computing groups without dependencies"
                )
              initialTime                  <- currentTimeMillis
              results                      <- dynGroupIds.accumulateParN(maxParallelism) { dynGroupId =>
                                                dynGroupUpdaterService
                                                  .update(dynGroupId)(using
                                                    QueryContext.systemQC
                                                      .newCC(Some("Update group due to batch update of dynamic groups"))
                                                      .copy(modId = modId)
                                                  )
                                                  .toIO
                                                  .either
                                                  .map(x => (dynGroupId, x))
                                              }
              t1                           <- currentTimeMillis
              timeComputeNonDependantGroups = (t1 - initialTime)
              _                            <- DynamicGroupLoggerPure.Timing.debug(
                                                s"Computing dynamic groups without dependencies finished in ${timeComputeNonDependantGroups} ms"
                                              )
              preComputeDependantGroups    <- currentTimeMillis
              results2                     <- dynGroupsWithDependencyIds.accumulateParN(1) { dynGroupId =>
                                                dynGroupUpdaterService
                                                  .update(dynGroupId)(using
                                                    QueryContext.systemQC
                                                      .newCC(Some("Update group due to batch update of dynamic groups"))
                                                      .copy(modId = modId)
                                                  )
                                                  .toIO
                                                  .either
                                                  .map(x => (dynGroupId, x))
                                              }
              _                            <-
                DynamicGroupLoggerPure.Timing.debug(
                  s"Computing dynamic groups with dependencies finished in ${System.currentTimeMillis - preComputeDependantGroups} ms"
                )
              // sync properties status
              _                            <- propertiesSyncService
                                                .syncProperties()(using QueryContext.systemQC)
                                                .chainError("Properties cannot be updated when computing new dynamic groups")
            } yield {
              results ++ results2
            }).onError(_.failureOrCause match {
              case Left(err)  => DynamicGroupLoggerPure.error(err.fullMsg)
              case Right(err) =>
                DynamicGroupLoggerPure.error(
                  "An error occurred when computing dynamic groups: " + err.dieOption.fold("unknown cause")(_.getMessage)
                ) *> DynamicGroupLoggerPure.debug(
                  s"Dynamic groups uncaught failure details : ${err}"
                )
            }).toBox

            updateManager ! GroupUpdateMessage.DynamicUpdateResult(
              processId,
              modId,
              startTime,
              Instant.now(),
              result
            )
          } catch {
            case e: Exception =>
              updateManager ! GroupUpdateMessage.DynamicUpdateResult(
                processId,
                modId,
                startTime,
                Instant.now(),
                Failure("Exception caught during update process.", Full(e), Empty)
              )
          }
        }
      }
    }

  }
}
