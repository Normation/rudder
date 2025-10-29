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
import com.normation.rudder.facts.nodes.CoreNodeFact
import com.normation.rudder.services.policies.NodeConfiguration
import com.normation.utils.DateFormaterService
import java.io.PrintWriter
import java.time.ZonedDateTime
import org.slf4j.LoggerFactory
import zio.*
import zio.json.*
import zio.syntax.*

trait NodeConfigurationLogger {

  /**
   * Log the configuration as a failable side-effect of writing in files
   */
  def log(nodeConfiguration: Seq[NodeConfiguration]): IOResult[Set[NodeId]]

  def isDebugEnabled: Boolean
}

/**
 * A node configuration logger that allows to log node configuration in
 * some place on the FS if a given file is present.
 *
 * Writing node configuration in the FS may be rather long.
 *
 * This is only done when logger has debug mode enabled !
 */
class NodeConfigurationLoggerImpl private[logger] (
    // where to write node config logs
    dirPath:            File,
    logger:             RudderLogger,
    val isDebugEnabled: Boolean
) extends NodeConfigurationLogger {
  import NodeConfigurationLogger.*

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

    ZIO
      .when(isDebugEnabled) {
        for {
          date    <- DateFormaterService.serializeZDT(ZonedDateTime.now()).succeed
          logTime <-
            writeIn(getDateFile(dirPath))(printWriter => printWriter.write(date).succeed)
          configs <- ZIO.foreach(nodeConfiguration) { config =>
                       val logFile = getLogFile(dirPath, config.nodeInfo)

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
      }
      .someOrElse(Set.empty[NodeId])
  }

}

object NodeConfigurationLogger {

  val loggerName: String = "rudder.debug.nodeconfiguration"

  def make(path: String): UIO[NodeConfigurationLogger] = {
    val underlyingLogger = LoggerFactory.getLogger(loggerName)
    val logger           = RudderLogger(underlyingLogger)
    val isDebugEnabled   = underlyingLogger.isDebugEnabled
    val p                = File(path)
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
      new NodeConfigurationLoggerImpl(p, logger, isDebugEnabled)
    })
      // still fallback to the logger
      .catchAll(err => {
        logger
          .warn(
            s"Error when checking access to directory '${path}' for rudder.debug.nodeconfiguration logging. " +
            s"Please ensure it exists and is writable."
          )
          .as(new NodeConfigurationLoggerImpl(p, logger, isDebugEnabled))
      })
  }

  def getDateFile(dir: File): File = dir / "lastWritenTime"
  def getLogFile(dir:  File, nodeInfo: CoreNodeFact): File = dir / (nodeInfo.fqdn + "_" + nodeInfo.id.value)
}
