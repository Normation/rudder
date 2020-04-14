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

package com.normation.rudder.services.policies.write

import com.normation.errors._
import com.normation.inventory.domain.AgentType.CfeEnterprise
import com.normation.inventory.domain.AgentType.CfeCommunity
import com.normation.inventory.domain.NodeId
import com.normation.inventory.domain.AgentType
import net.liftweb.common.Loggable
import org.apache.commons.io.FilenameUtils
import com.normation.rudder.domain.nodes.NodeInfo

/**
 * Utility tool to compute the path of a server promises (and others information) on the rootMachine

 *
 */
trait PathComputer {

  def computeBaseNodePath(searchedNodeId : NodeId, rootNodeId: NodeId, allNodeConfigs:Map[NodeId, NodeInfo]): PureResult[NodePoliciesPaths]

  def getRootPath(agentType : AgentType) : String
}

/**
 * Utility tool to compute the path of a machine promises (and others information) on the rootMachine
 *
 */
class PathComputerImpl(
      baseFolder: String //  "/var/rudder"
    , relativeShareFolder: String // "share"
    , backupFolder: String // "/var/rudder/backup/"
    , communityAgentRootPath: String // "/var/rudder/cfengine-community/inputs"
    , enterpriseAgentRootPath: String // "/var/cfengine/inputs"
    , chainDepthLimit: Int = 20 // max number of relay, to detect cycles
) extends PathComputer with Loggable {

  private[this] val promisesPrefix = "/rules"
  private[this] val newPostfix = ".new"

  /**
   * Compute
   *  the path of promises for a node, i.e. the full path on the root server to the data
   *  the new folder, i.e. the full path where promises are written and checked before being updated
   *  the backup folder, the path were promises are backuped
   * Finish with no trailing /
   * Ex : /var/rudder/share/uuid-a/share/uuid-b/rules,/var/rudder/share/uuid-a/share/uuid-b/rules.new, /var/rudder/backup/uuid-a/share/uuid-b
   * Caution: when used for root server, the computed path is not valid, however some magic catch it
   * up after to correct the path
   * @param searchedNodeConfiguration : the machine we search
   * @return
   */
  def computeBaseNodePath(searchedNodeId : NodeId, rootNodeId: NodeId, allNodeConfigs: Map[NodeId, NodeInfo]): PureResult[NodePoliciesPaths] = {
    if(searchedNodeId == rootNodeId) {
      Left(Inconsistency("ComputeBaseNodePath can not be used to get the (special) root paths"))
    } else {
      for {
        path <- recurseComputePath(rootNodeId, searchedNodeId, "/"  + searchedNodeId.value, allNodeConfigs, Nil)
      } yield {
        NodePoliciesPaths(
            searchedNodeId
          , FilenameUtils.normalize(baseFolder + relativeShareFolder + "/" + path + promisesPrefix)
          , FilenameUtils.normalize(baseFolder + relativeShareFolder + "/" + path + promisesPrefix + newPostfix)
          , FilenameUtils.normalize(backupFolder + path + promisesPrefix)
        )
      }
    }
  }

  /**
   * Return the path of the promises for the root (we directly write its promises in its path)
   * @param agent
   * @return
   */
  def getRootPath(agentType : AgentType) : String = {
    agentType match {
        case CfeEnterprise => enterpriseAgentRootPath
        case CfeCommunity => communityAgentRootPath
        case x => throw new IllegalArgumentException("Unrecognized agent type: %s".format(x))
    }
  }

  /**
   * Return the path from a machine to another, excluding the top rootNode
   * If we have the hierarchy Root, A, B :
   * recurseComputePath(root, B, path) will return : machineA/share/ + path
   * recurseComputePath(A, B, path) will return : machineA/share/+ path
   * recurseComputePath(root, A, path) will return :  path
   *
   * We want to avoid stackoverflow if the user made an error and did a
   * cycle in parents chain. We also want to provide an interesting
   * information (the faulty chain)
   * So we limit the depth of recursion to arbitrary 20 levels, and display them
   * if they are reached.
   *
   */
  private def recurseComputePath(fromNodeId: NodeId, toNodeId: NodeId, path : String, allNodeConfig: Map[NodeId, NodeInfo], chain: List[NodeId]) : PureResult[String] = {
    if (fromNodeId == toNodeId) {
      Right(path)
    } else {
      if(chain.size >= chainDepthLimit) {
        Left(Unexpected(s"We reach the maximum number of relay depth (${chainDepthLimit}), which is likely to denote a cycle in the chain of policy parents. " +
                        s"The faulty chain of node ID is: ${chain.reverse.map(_.value).mkString(" -> ")}"))
      } else {
        for {
          toNode <- allNodeConfig.get(toNodeId).notOptionalPure(s"Missing node with id ${toNodeId.value} when trying to build the policies files path for node ${fromNodeId.value}")
          pid    =  toNode.policyServerId
          parent <- allNodeConfig.get(pid).notOptionalPure(s"Can not find the parent node with id '${pid.value}' of node '${toNode.hostname}' (${toNodeId.value}) when trying to build the policies files for node ${fromNodeId.value}")
          result <- parent match {
                      case root if root.id == NodeId("root") =>
                          // root is a specific case, it is the root of everything
                          recurseComputePath(fromNodeId, root.id, path, allNodeConfig, root.id :: chain)

                      case policyParent =>
                          recurseComputePath(fromNodeId, policyParent.id, policyParent.id.value + "/" + relativeShareFolder + "/" + path, allNodeConfig, policyParent.id :: chain)
                    }
        } yield {
          result
        }
      }
    }
  }
}
