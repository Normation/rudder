/*
*************************************************************************************
* Copyright 2013 Normation SAS
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

import net.liftweb.actor.LiftActor
import net.liftweb.actor.LAPinger
import net.liftweb.common._
import net.liftweb.util.Helpers
import org.joda.time.DateTime
import com.normation.rudder.domain.logger.ApplicationLogger


// -----------------------------------------------------
// Constants and private objects and classes
// -----------------------------------------------------

//Message to send to the updater manager to start a new update of all dynamic groups
case object StartUpdate

sealed trait UpdaterStates //states into wich the updater process can be
//the process is idle
case object IdleUpdater extends UpdaterStates
//an update is currently running for the given nodes
case class StartProcessing(id:Long, started: DateTime) extends UpdaterStates
//the process gave a result
case class UpdateResult[T](id:Long, start: DateTime, end:DateTime, result: Box[T]) extends UpdaterStates

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
trait AbstractScheduler extends Loggable {

  type T
  val schedulerMinimumIntervalTime = 1
  val schedulerMaximumIntervalTime = 300
  def updateInterval: Int // in seconds
  val executeTask: Long => Box[T]
  def displayName : String
  def propertyName : String

  // -----------------------------------------------------
  // Start batch - only if scheduling insterval > 0
  // -----------------------------------------------------

  if(updateInterval < 1) {
    logger.info(s"Disable [${displayName}] scheduler sinces property ${propertyName} is 0 or negative")
  } else {
    logger.trace(s"***** starting [${displayName}] scheduler *****")
    (new StatusManager) ! StartUpdate
  }

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

  private class StatusManager extends LiftActor with Loggable {
    updateManager =>

    private var updateId = 0L
    private var currentState: UpdaterStates = IdleUpdater
    private var onePending = false
    private var realUpdateInterval = {
      if(updateInterval < schedulerMinimumIntervalTime) {
        logger.warn(s"Value '${updateInterval}' for ${propertyName} is too small for [${displayName}] scheduler interval, using '${schedulerMinimumIntervalTime}'")
        schedulerMinimumIntervalTime
      } else {
        if(updateInterval > schedulerMaximumIntervalTime) {
          logger.warn(s"Value '${updateInterval}' for ${propertyName} is too big for [${displayName}] scheduler interval, using '${schedulerMaximumIntervalTime}'")
          schedulerMaximumIntervalTime
        } else {
          logger.info(s"Starting [${displayName}] scheduler with a period of ${updateInterval} s")
          updateInterval
        }
      }
    }

    override protected def messageHandler = {

      // --------------------------------------------
      // Ask for a new process
      // --------------------------------------------
      case StartUpdate =>

        currentState match {
          case IdleUpdater =>
            logger.debug(s"[${displayName}] Scheduled task starting")
            updateId = updateId + 1
            TaskProcessor ! StartProcessing(updateId, new DateTime)
          case _ : StartProcessing if(!onePending) =>
            logger.trace(s"Add a pending task for [${displayName}] scheduler")
            onePending = true
          case _ =>
            logger.warn(s"[${displayName}] Scheduled task NOT started: another task is still processing, ignoring")
        }

      // --------------------------------------------
      // Process a successful update response
      // --------------------------------------------
      case UpdateResult(id,start,end,result) =>
        logger.trace(s"Get result for [${displayName}] scheduler task's id '${id}'")

        currentState = IdleUpdater
        //if one update is pending, immediatly start one other

        //schedule next update, in minutes
        LAPinger.schedule(this, StartUpdate, realUpdateInterval*1000)

        //log some information
        val format = "yyyy/MM/dd HH:mm:ss"

        result match {
          case e:EmptyBox =>
            val error = (e ?~! s"Error when executing [${displayName}] scheduler task started at ${start.toString(format)}, ended at ${end.toString(format)}.")
            logger.error(error.messageChain)
          case Full(x) =>
            val executionTime = end.getMillis() - start.getMillis()
            logger.debug(s"[${displayName}] Scheduled task finished in ${executionTime} ms (started at ${start.toString(format)}, finished at ${end.toString(format)})")
            if (executionTime >= updateInterval*1000) {
              ApplicationLogger.warn(s"[${displayName}] Task frequency is set too low! Last task took ${executionTime} ms but tasks are scheduled every ${updateInterval*1000} ms. Adjust ${propertyName} if this problem persists.")
            }
        }

      // --------------------------------------------
      // Unexpected messages
      // --------------------------------------------
      case x => logger.debug(s"[${displayName}] scheduler don't know how to process message: '${x}'")
    }


    private[this] object TaskProcessor extends LiftActor {

      override protected def messageHandler = {
        // --------------------------------------------
        // Process a start process
        // --------------------------------------------
        case StartProcessing(processId, startTime) => {
          logger.trace(s"[${displayName}] scheduler: start a new task with id: '${processId}' on date ${startTime}")
          try {
            val result = executeTask(processId)

            if (updateManager!=null)
              updateManager ! UpdateResult(processId, startTime, new DateTime, result)
              else this !  StartProcessing(processId, startTime)
          } catch {
            case e:Throwable => e match {
              case x:ThreadDeath => throw x
              case x:InterruptedException => throw x
              case e => logger.error(e)
                //updateManager ! UpdateResult(processId,startTime,new DateTime, Failure("Exception caught during update process.",Full(e), Empty))
                throw e
            }
          }
        }

        // --------------------------------------------
        // Unexpected messages
        // --------------------------------------------
        case x => logger.debug(s"[${displayName}] Don't know how to process message: '${x}'")
      }
    }
  }
}