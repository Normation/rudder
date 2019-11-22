/*
*************************************************************************************
* Copyright 2019 Normation SAS
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

import java.nio.file.FileSystemException

import better.files.File
import better.files.File.root
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.logger.ScheduledJobLogger
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.services.nodes.NodeInfoService
import monix.execution.Scheduler.{global => scheduler}
import net.liftweb.common._

import scala.concurrent.duration._
import scala.util.{Success, Failure => Catch}
import scala.util.Try


/**
 * A naive scheduler which checks every updateInterval if software needs to be deleted
 */
class CleanPoliciesFolder(
     nodeInfoService : NodeInfoService
   , updateInterval  : FiniteDuration
) {

  val logger = ScheduledJobLogger


  if (updateInterval < 1.hour) {
    logger.info(s"Disable automatic unreferenced policies directory (update interval cannot be less than 1 hour)")
  } else {
    logger.debug(s"***** starting batch that purge unreferenced policies directory, every ${updateInterval.toString()} *****")
    scheduler.scheduleWithFixedDelay(1.hour, updateInterval) {


    }
  }

  def launch = {
    (for {
      nodes <- nodeInfoService.getAll()
      d <- Box.tryo{cleanPoliciesFolderOfDeletedNode(nodes)}
      deleted <- d
    } yield {
      deleted
    }) match {
      case eb: EmptyBox =>
        val error = (eb ?~! s"Error when deleting unreferenced policies directory")
        logger.error(error.messageChain)
        error.rootExceptionCause.foreach(ex =>
          logger.error("Exception was:", ex)
        )
        error
      case Full(deleted) =>
        logger.info(s"Deleted ${deleted.length} unreferenced policies folder")
        if (logger.isDebugEnabled && deleted.length > 0)
          logger.debug(s"Deleted policies folder of Nodes: ${deleted.mkString(",")}")
        Full(deleted)
    }
  }

  def cleanPoliciesFolderOfDeletedNode(currentNodes : Map[NodeId,NodeInfo]) : Box[List[String]] = {
    import File._

    def getNodeFolders(file: File, parentId: String): Iterator[String] = {
      file.children.flatMap {
        nodeFolder =>
          val nodeId = NodeId(nodeFolder.name)
          if (
            currentNodes.get(nodeId) match {
              case None => true
              case Some(nodeInfo) =>
                val policyServerId = nodeInfo.policyServerId
                parentId != policyServerId.value
            }
          ) {
            nodeFolder.delete()
            val res = Iterator(nodeFolder.name)
            res
          } else {
            if ((nodeFolder / "share").exists) {
              getNodeFolders(nodeFolder / "share", nodeFolder.name)
            } else {
              Iterator.empty
            }
          }
      }
    }


    try {
      Try(getNodeFolders(root / "var" / "rudder" / "share", "root").toList) match {
        case Success(value) => Full(value)
        case Catch(e) => Failure(e.getMessage, Full(e), Empty)
      }
    } catch {
      case fse : FileSystemException =>
        Failure(fse.getMessage, Full(fse), Empty)
    }
  }

}

