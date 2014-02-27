/*
*************************************************************************************
* Copyright 2014 Normation SAS
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

package com.normation.rudder.services.policies.nodeconfig

import java.io.File
import java.io.PrintWriter

import org.joda.time.DateTime
import org.slf4j.LoggerFactory

import com.normation.inventory.domain.NodeId
import com.normation.utils.Control._

import net.liftweb.common._
import net.liftweb.json.NoTypeHints
import net.liftweb.json.Serialization
import net.liftweb.json.Serialization.writePretty



trait NodeConfigurationLogger {

  def log(nodeConfiguration: Seq[NodeConfiguration]): Box[Set[NodeId]]

}

/**
 * A node configuration logger that allows to log node configuration in
 * some place on the FS if a given file is present.
 *
 * Writing node configuration in the FS may be rather long.
 */
class NodeConfigurationLoggerImpl(
    //where to write node config logs
    path         : String
) extends NodeConfigurationLogger {

  val logger = LoggerFactory.getLogger("rudder.debug.nodeconfiguration")

  import java.io.File

  {
    val p = new File(path)
    p.mkdirs()
    if(p.exists() && p.canWrite()) {
      //ok
    } else {
      logger.error(s"Error when trying to create the directory where node configurations are saved: '${path}'. Please check that that file is a directory with write permission for Rudder.")
    }
  }

  def log(nodeConfiguration: Seq[NodeConfiguration]): Box[Set[NodeId]] = {
    import net.liftweb.json._
    import net.liftweb.json.Serialization.writePretty
    implicit val formats = Serialization.formats(NoTypeHints)
    import java.io.PrintWriter

    def writeIn[T](path:File)(f: PrintWriter => Box[T]) = {
      val printWriter = new java.io.PrintWriter(path)
      try {
        f(printWriter)
      } catch {
        case e: Exception => Failure(s"Error when writing in ${path.getAbsolutePath}", Full(e), Empty)
      } finally {
        printWriter.close()
      }
    }

    if(logger.isDebugEnabled) {
      for {
        logTime <- writeIn(new File(path, "lastWritenTime")) { printWriter =>
                     Full(printWriter.write(DateTime.now.toString()))
                   }
        configs <- (sequence(nodeConfiguration) { config =>
                     val logFile = new File(path, config.nodeInfo.hostname + "_" + config.nodeInfo.id.value)

                     writeIn(logFile) { printWriter =>
                       val logText = writePretty(config)
                       printWriter.write(logText)
                       Full(config.nodeInfo.id)
                     }
                   }).map( _.toSet)
      } yield {
        configs
      }
    } else {
      Full(Set())
    }
  }

}

