/*
 *************************************************************************************
 * Copyright 2014 Normation SAS
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

package com.normation.rudder.domain.logger

import better.files.*
import com.normation.RudderLogger
import com.normation.errors.*
import com.normation.inventory.domain.NodeId
import com.normation.rudder.services.policies.NodeConfiguration
import com.normation.utils.DateFormaterService
import java.io.PrintWriter
import java.time.ZonedDateTime
import zio.*
import zio.json.*
import zio.syntax.*

trait NodeConfigurationLogger {

  def log(nodeConfiguration: Seq[NodeConfiguration]): IOResult[Set[NodeId]]

  def isDebugEnabled: Boolean
}

/**
 * A node configuration logger that allows to log node configuration in
 * some place on the FS if a given file is present.
 *
 * Writing node configuration in the FS may be rather long.
 */
class NodeConfigurationLoggerImpl private (
    // where to write node config logs
    dirPath: File,
    logger:  RudderLogger
) extends NodeConfigurationLogger {

  def isDebugEnabled: Boolean = logger.isDebugEnabled()

  def log(nodeConfiguration: Seq[NodeConfiguration]): IOResult[Set[NodeId]] = {
    def writeIn[T](path: File)(f: PrintWriter => IOResult[T]) = {
      ZIO
        .scoped(
          for {
            printer <- IOResult.attempt(java.io.PrintWriter(path.pathAsString))
            p       <- ZIO.fromAutoCloseable(printer.succeed)
            t       <- f(p).mapError(e => Chained(s"Error when writing in ${path.pathAsString}", e))
          } yield {
            t
          }
        )
    }

    if (isDebugEnabled) {
      for {
        date    <- DateFormaterService.serializeZDT(ZonedDateTime.now()).succeed
        logTime <-
          writeIn(dirPath / "lastWritenTime")(printWriter => printWriter.write(date).succeed)
        configs <- ZIO.foreach(nodeConfiguration) { config =>
                     val logFile = dirPath / (config.nodeInfo.fqdn + "_" + config.nodeInfo.id.value)

                     writeIn(logFile) { printWriter =>
                       printWriter
                         .write(config.toJson(using NodeConfiguration.debugFullEncoder))
                         .succeed
                         .as(
                           config.nodeInfo.id
                         )
                     }
                   }
      } yield {
        configs.toSet
      }
    } else {
      Set.empty.succeed
    }
  }

}

object NodeConfigurationLoggerImpl {

  def make(path: String): UIO[NodeConfigurationLoggerImpl] = {
    val logger = RudderLogger("rudder.debug.nodeconfiguration")
    val p      = File(path)
    (for {
      _ <- IOResult.attempt(p.createDirectoryIfNotExists())

      exists   <- IOResult.attempt(p.exists())
      canWrite <- IOResult.attempt(p.isWritable)
      _        <- ZIO.unless(exists && canWrite) {
                    logger.error(
                      s"Error when trying to create the directory where node configurations are saved: '${path}'. Please check that that file is a directory with write permission for Rudder."
                    )
                  }
    } yield {
      NodeConfigurationLoggerImpl(p, logger)
    })
      // still fallback to the logger
      .catchAll(err => {
        logger
          .warn(
            s"Error when checking access to directory '${path}' for rudder.debug.nodeconfiguration logging. " +
            s"Please ensure it exists and is writable."
          )
          .as(NodeConfigurationLoggerImpl(p, logger))
      })
  }
}
