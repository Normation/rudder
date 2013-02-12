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

package com.normation.rudder.services.system

import com.normation.rudder.domain.system.{OrchestratorStatus,ButtonActivated,ButtonReleased}

/*
 * Service that know how to interact with the
 * orchestrator
 */


trait StartStopOrchestrator {

  /**
   * Stop the orchestrator.
   * Return the status of the orchestrator after the command
   * (that should be stopped if the stop command succeeded)
   */
  def stop() : OrchestratorStatus

  /**
   * Start the orchestrator.
   * Return the status of the orchestrator after the command
   * (that should be started if the start command succeeded)
   */
  def start() : OrchestratorStatus

  /**
   * Get the current status of the orchestrator
   */
  def status() : OrchestratorStatus

}

//////////////////////// default implementation ////////////////////////

import scala.sys.process._
import SystemStartStopOrchestrator._
import org.slf4j.LoggerFactory
import java.io.File

/**
 * A class that manage orchestrator state thanks to the call
 * of an external command (binary or shell script)
 *
 * The command is expected to be able to take 3 parameters:
 * - status with a return code of 0 when orchestrator running (button released), 1 else
 * - start with a return code of 0 if started successfully, 1 else (TODO: more error code)
 * - stop with a return code of 0 if stopped successfully, 1 else (TODO: more error code)
 */
object SystemStartStopOrchestrator {
  /** start parameter */
  val startCommand = "start"
  /** stop parameter */
  val stopCommand = "stop"
  /** status parameter */
  val statusCommand = "status"

  val logger = LoggerFactory.getLogger(classOf[SystemStartStopOrchestrator])
  val devNull = new ProcessLogger {
    def out(s: => String): Unit = {}
    def err(s: => String): Unit = {}
    def buffer[T](f: => T): T = f
  }
}

class SystemStartStopOrchestrator(commandPath:String) extends StartStopOrchestrator {

  private def exec(command:String) : OrchestratorStatus = {
    ((commandPath + " " + command) ! devNull ) match { //wait for the end of the command, verify the status after
      case 0 =>
        logger.info("Orchestrator service '{}' command: success",command)
        status
      case 1 =>
        logger.error("Orchestrator service '{}' command: error",command) //TODO: error reporting
        status
    }
  }

  def stop() = exec(stopCommand)

  def start() = exec(startCommand)

  def status() : OrchestratorStatus =  (commandPath + " " + statusCommand) ! devNull match {
    case 0 => ButtonReleased
    case _ => ButtonActivated
  }
}
