/*
 *************************************************************************************
 * Copyright 2013 Normation SAS
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

import com.normation.rudder.domain.logger.ApplicationLogger
import com.normation.rudder.domain.logger.ScheduledJobLogger
import net.liftweb.actor.LAPinger
import net.liftweb.actor.SpecializedLiftActor
import net.liftweb.common.*
import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat
import scala.concurrent.duration.Duration
import scala.concurrent.duration.DurationInt

// -----------------------------------------------------
// Constants and private objects and classes
// -----------------------------------------------------

sealed trait AbstractActorUpdateMessage
object AbstractActorUpdateMessage {
  case object StartUpdate                                                                    extends AbstractActorUpdateMessage
  final case class UpdateResult[T](id: Long, start: DateTime, end: DateTime, result: Box[T]) extends AbstractActorUpdateMessage
}

sealed trait UpdaterStates //states into wich the updater process can be
//the process is idle
case object IdleUpdater                                       extends UpdaterStates
//an update is currently running for the given nodes
final case class StartProcessing(id: Long, started: DateTime) extends UpdaterStates
//the process gave a result

/**
 * An abstract batch scheduler periodically executing some service.
 *
 * It works with three parts so that it can handle long running I/O
 * process:
 * - a pinger used as a scheduler
 * - a StatusManager which keep the state of the task to do: currently running,
 *   idle, etc
 * - a TaskProcessor which actually execute the task (and which can not be
 *   able to answer incoming messages for a long time)
 *
 * Moreover, some initialisation logic is added so that the scheduling
 * interval may be configured externally with some guards.
 */
trait AbstractScheduler {

  type T
  private val schedulerMinimumIntervalTime: Duration = 1.second
  private val schedulerMaximumIntervalTime: Duration = 5.minutes
  def updateInterval:                       Duration
  def executeTask:                          Long => Box[T]
  def displayName:                          String
  def propertyName:                         String

  val logger = ScheduledJobLogger

  // -----------------------------------------------------
  // Start batch
  // -----------------------------------------------------

  logger.trace(s"***** starting [${displayName}] scheduler *****")
  (new StatusManager) ! AbstractActorUpdateMessage.StartUpdate

  ////////////////////////////////////////////////////////////////
  //////////////////// implementation details ////////////////////
  ////////////////////////////////////////////////////////////////

  // -----------------------------------------------------
  /*
   * Two actor utility class: one that manage the status
   * (respond to ping, to status command, etc)
   * one that actually process update.
   */
  // -----------------------------------------------------

  private class StatusManager extends SpecializedLiftActor[AbstractActorUpdateMessage] {
    updateManager =>

    val logger = ScheduledJobLogger

    private var updateId = 0L
    private var currentState: UpdaterStates = IdleUpdater
    private var onePending = false
    private val realUpdateInterval: Duration = {
      if (updateInterval < schedulerMinimumIntervalTime) {
        logger.warn(
          s"Value '${updateInterval.toCoarsest}' for $propertyName is too small for [$displayName] scheduler interval, using '$schedulerMinimumIntervalTime'"
        )
        schedulerMinimumIntervalTime
      } else {
        if (updateInterval > schedulerMaximumIntervalTime) {
          logger.warn(
            s"Value '${updateInterval.toCoarsest}' for $propertyName is too big for [$displayName] scheduler interval, using '$schedulerMaximumIntervalTime'"
          )
          schedulerMaximumIntervalTime
        } else {
          logger.info(s"Starting [$displayName] scheduler with a period of '${updateInterval.toCoarsest}'")
          updateInterval
        }
      }
    }

    override protected def messageHandler: PartialFunction[AbstractActorUpdateMessage, Unit] = PartialFunction.fromFunction {

      // --------------------------------------------
      // Ask for a new process
      // --------------------------------------------
      case AbstractActorUpdateMessage.StartUpdate                          =>
        currentState match {
          case IdleUpdater =>
            logger.debug(s"[${displayName}] Scheduled task starting")
            updateId = updateId + 1
            TaskProcessor ! StartProcessing(updateId, new DateTime)
          case _: StartProcessing if (!onePending) =>
            logger.trace(s"Add a pending task for [${displayName}] scheduler")
            onePending = true
          case _ =>
            logger.warn(s"[${displayName}] Scheduled task NOT started: another task is still processing, ignoring")
        }

      // --------------------------------------------
      // Process a successful update response
      // --------------------------------------------
      case AbstractActorUpdateMessage.UpdateResult(id, start, end, result) =>
        logger.trace(s"Get result for [${displayName}] scheduler task's id '${id}'")

        currentState = IdleUpdater
        // if one update is pending, immediatly start one other

        // schedule next update
        LAPinger.schedule(this, AbstractActorUpdateMessage.StartUpdate, realUpdateInterval.toMillis)

        // log some information
        val format = ISODateTimeFormat.dateTimeNoMillis()

        result match {
          case e: EmptyBox =>
            val error = {
              (e ?~! s"Error when executing [${displayName}] scheduler task started at ${start.toString(format)}, ended at ${end
                  .toString(format)}.")
            }
            logger.error(error.messageChain)
          case Full(x) =>
            val executionTimeInMs = end.getMillis() - start.getMillis()
            logger.debug(s"[${displayName}] Scheduled task finished in ${executionTimeInMs} ms (started at ${start
                .toString(format)}, finished at ${end.toString(format)})")
            if (executionTimeInMs >= updateInterval.toMillis) {
              ApplicationLogger.warn(
                s"[${displayName}] Task frequency is set too low! Last task took ${executionTimeInMs} ms but tasks are scheduled every ${updateInterval.toMillis} ms. Adjust ${propertyName} if this problem persists."
              )
            }
        }

    }

    private object TaskProcessor extends SpecializedLiftActor[StartProcessing] {

      override protected def messageHandler: PartialFunction[StartProcessing, Unit] = {
        // --------------------------------------------
        // Process a start process
        // --------------------------------------------
        case StartProcessing(processId, startTime) => {
          logger.trace(s"[${displayName}] scheduler: start a new task with id: '${processId}' on date ${startTime}")
          try {
            val result = executeTask(processId)

            if (updateManager != null)
              updateManager ! AbstractActorUpdateMessage.UpdateResult(processId, startTime, new DateTime, result)
            else this ! StartProcessing(processId, startTime)
          } catch {
            case e: Throwable =>
              e match {
                case x: ThreadDeath          => throw x
                case x: InterruptedException => throw x
                case e =>
                  logger.error(e)
                  // updateManager ! UpdateResult(processId,startTime,new DateTime, Failure("Exception caught during update process.",Full(e), Empty))
                  throw e
              }
          }
        }
      }
    }
  }
}
