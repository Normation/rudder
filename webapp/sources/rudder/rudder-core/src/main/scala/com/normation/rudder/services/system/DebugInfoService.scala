/*
*************************************************************************************
* Copyright 2018 Normation SAS
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


package com.normation.rudder.services

import java.nio.file.{Files, Paths}
import java.util.concurrent.TimeUnit

import com.normation.rudder.hooks._
import net.liftweb.common.{Box, Failure, Full, Loggable}
import org.joda.time.DateTime

import scala.collection.JavaConverters._
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.{Success, Try, Failure => TryFailure}

final case class DebugInfoScriptResult (
    serverName : String
  , result     : Array[Byte]
)


trait DebugInfoService {
  def launch() : Box[DebugInfoScriptResult]
}

class DebugInfoServiceImpl extends DebugInfoService with Loggable {

  private[this] def execScript() : Box[CmdResult] = {
    val environment = System.getenv.asScala.toMap
    val timeOut     = Duration(30, TimeUnit.SECONDS)
    val scriptPath  = "/opt/rudder/bin/rudder-debug-info"
    val cmd         = Cmd(scriptPath, Nil, environment)
    // Since the API is blocking, we want to wait for the result.
    logger.debug(s"Launching debug-info script (${scriptPath})")
    try {
      Full(Await.result(RunNuCommand.run(cmd, timeOut), timeOut))
    } catch {
      case e: Throwable => Failure(s"An Error has occured when launching debug-info script: ${e.getMessage}")
    }
  }

  // The debug script generates an archive in the /tmp folder.
  // We want to get its binary representation into an Array of byte
  // In order for the API to build an InMemoryResponse

  private[this] def getScriptResult() : Box[DebugInfoScriptResult] = {

    Try {

      val resultPath = s"/var/rudder/debug/info/debug-info-latest.tar.gz"
      val result = Paths.get(resultPath).toRealPath()
      DebugInfoScriptResult(result.getFileName.toString, Files.readAllBytes(result))

    } match {
      case Success(debugInfo) => Full(debugInfo)
      case TryFailure(exception) => Failure(s"Could not get file debug info result file: cause is ${exception.getMessage}")
    }

  }

  override def launch() : Box[DebugInfoScriptResult] = {

    val start = DateTime.now.getMillis
    for {
      cmdResult <- execScript()

      end       = DateTime.now.getMillis
      duration  = end - start
      _         = logger.debug(s"debug-info script run finished in ${duration} ms")

      zip       <- if (cmdResult.code == 0 || cmdResult.code == 1) {
                     if (logger.isTraceEnabled) {
                       logger.trace(s"stdout: ${cmdResult.stdout}")
                     }
                     getScriptResult()
                   } else {
                     val msg = s"debug-info script exited with an error (code ${cmdResult.code}))."
                     logger.error(s"${msg} Error details below")
                     logger.error(s"stderr: ${cmdResult.stderr}")
                     logger.error(s"stdout: ${cmdResult.stdout}")
                     Failure(msg)

                   }
    } yield {
      zip
    }
  }
}
