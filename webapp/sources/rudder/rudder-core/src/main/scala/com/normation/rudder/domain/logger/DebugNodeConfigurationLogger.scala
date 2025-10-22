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

import com.normation.inventory.domain.NodeId
import com.normation.rudder.services.policies.NodeConfiguration
import com.normation.utils.Control.traverse
import java.io.File
import java.io.PrintWriter
import net.liftweb.common.*
import net.liftweb.json.Serialization.writePretty
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.slf4j
import org.slf4j.LoggerFactory

trait NodeConfigurationLogger {

  def log(nodeConfiguration: Seq[NodeConfiguration]): Box[Set[NodeId]]

  def isDebugEnabled: Boolean
}

/**
 * A node configuration logger that allows to log node configuration in
 * some place on the FS if a given file is present.
 *
 * Writing node configuration in the FS may be rather long.
 */
class NodeConfigurationLoggerImpl(
    // where to write node config logs
    path: String
) extends NodeConfigurationLogger {

  val logger:         slf4j.Logger = LoggerFactory.getLogger("rudder.debug.nodeconfiguration")
  def isDebugEnabled: Boolean      = logger.isDebugEnabled

  {
    val p = new File(path)
    p.mkdirs()
    if (p.exists() && p.canWrite()) {
      // ok
    } else {
      logger.error(
        s"Error when trying to create the directory where node configurations are saved: '${path}'. Please check that that file is a directory with write permission for Rudder."
      )
    }
  }

  def log(nodeConfiguration: Seq[NodeConfiguration]): Box[Set[NodeId]] = {
    import net.liftweb.json.*
    implicit val formats                                 = DefaultFormats
    def writeIn[T](path: File)(f: PrintWriter => Box[T]) = {
      val printWriter = new java.io.PrintWriter(path)
      try {
        f(printWriter)
      } catch {
        case e: Exception => Failure(s"Error when writing in ${path.getAbsolutePath}", Full(e), Empty)
      } finally {
        printWriter.close()
      }
    }

    if (logger.isDebugEnabled) {
      for {
        logTime <- writeIn(new File(path, "lastWritenTime"))(printWriter =>
                     Full(printWriter.write(DateTime.now(DateTimeZone.UTC).toString()))
                   )
        configs <- (traverse(nodeConfiguration) { config =>
                     val logFile = new File(path, config.nodeInfo.fqdn + "_" + config.nodeInfo.id.value)

                     writeIn(logFile) { printWriter =>
                       val logText = writePretty(config)
                       printWriter.write(logText)
                       Full(config.nodeInfo.id)
                     }
                   }).map(_.toSet)
      } yield {
        configs
      }
    } else {
      Full(Set())
    }
  }

}
