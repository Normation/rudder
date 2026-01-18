/*
 *************************************************************************************
 * Copyright 2026 Normation SAS
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
package com.normation.rudder.services.policies.fetchinfo

import com.normation.errors.IOResult
import com.normation.inventory.domain.AgentType
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.logger.PolicyGenerationLoggerPure
import com.normation.rudder.domain.nodes.NodeState
import com.normation.rudder.domain.policies.FullGroupTarget
import com.normation.rudder.domain.policies.FullRuleTargetInfo
import com.normation.rudder.facts.nodes.CoreNodeFact
import com.normation.rudder.repository.FullNodeGroupCategory
import com.normation.rudder.services.servers.PolicyServerConfigurationObjects
import com.softwaremill.quicklens.*
import scala.collection.MapView
import zio.ZIO
import zio.syntax.*

// here, we need to make a double consistency check:
// - in a policy server group, we want the real list of node from node facts that have that policy server. Other nodes
//   (in addition or missing in the group list, for ex because there wasn't a dyn group update yet) are unwanted.
// - in groups, we only want nodes that are in all nodes. That can happen for a deleted node, before dyn group
//   are computed back.
// We also filter out node that are not in a state that should not lead to policy generation
// Return a filtered list of valid node facts and corresponding group Lib.
object GetConsistentNodesAndGroups {
  def apply(
      nodeFacts: MapView[NodeId, CoreNodeFact],
      groupLib:  FullNodeGroupCategory
  ): IOResult[(Map[NodeId, CoreNodeFact], FullNodeGroupCategory)] = {
    // when we have a node group, only keep existing nodes in it.
    // We pass both the realized keyIds (to avoid recomputing the set several time, it's costly) and the mapView (for
    // the collect case)
    def cleanFullRuleTarget(nodeFacts: Map[NodeId, CoreNodeFact], ignoredNodes: Set[NodeId])(
        frti: FullRuleTargetInfo
    ): IOResult[FullRuleTargetInfo] = {
      frti.target match {
        case FullGroupTarget(target, nodeGroup) =>
          // get the list for the node group. There is a special case for the "hasPolicyServer-" system group
          val nodeIds = if (nodeGroup.isSystem) {
            PolicyServerConfigurationObjects.extractPolicyServerIdFromHasGroupName(nodeGroup.id) match {
              // retrieve from the list of NodeFact
              case Some(policyServerId) =>
                nodeFacts.collect {
                  case (id, nf)
                      if (nf.rudderSettings.policyServerId == policyServerId && nf.rudderAgent.agentType == AgentType.CfeCommunity) =>
                    id
                }.toSet
              case None                 =>
                nodeGroup.serverList
            }
          } else nodeGroup.serverList

          // restrict result to known node IDs
          val nodes = nodeIds.intersect(nodeFacts.keySet)

          // now, check if the group node was consistent, else update and warn user
          val enabledNodes = nodeGroup.serverList -- ignoredNodes
          if (nodes != enabledNodes) {
            PolicyGenerationLoggerPure.warn(
              s"Group '${nodeGroup.name}' [${nodeGroup.id.serialize}]}' had inconsistent list of nodes compared to current known list of nodes"
            ) *> ZIO.when(PolicyGenerationLoggerPure.logEffect.isDebugEnabled) {
              PolicyGenerationLoggerPure.debug(
                s"  - added nodes: [${(nodes -- enabledNodes).map(_.value).mkString(",")}]"
              ) *>
              PolicyGenerationLoggerPure.debug(
                s"  - removed nodes: [${(enabledNodes -- nodes).map(_.value).mkString(",")}]"
              )
            } *> frti.modify(_.target).setTo(FullGroupTarget(target, nodeGroup.modify(_.serverList).setTo(nodes))).succeed
          } else frti.succeed

        case other => frti.succeed
      }
    }

    // recursively clean all group from the lib
    def recFilterOut(nodeFacts: Map[NodeId, CoreNodeFact], ignoredNodes: Set[NodeId])(
        groupRoot: FullNodeGroupCategory
    ): IOResult[FullNodeGroupCategory] = {
      for {
        targetInfos   <- ZIO.foreach(groupRoot.targetInfos)(cleanFullRuleTarget(nodeFacts, ignoredNodes))
        subCategories <- ZIO.foreach(groupRoot.subCategories)(recFilterOut(nodeFacts, ignoredNodes))
      } yield {
        groupRoot
          .modify(_.targetInfos)
          .setTo(targetInfos)
          .modify(_.subCategories)
          .setTo(subCategories)
      }
    }

    // now, filter out all nodes in groups that are not in the accepted list of nodes
    val (okNodes, others) = nodeFacts.toMap.partition { case (_, n) => n.rudderSettings.state != NodeState.Ignored }

    for {
      _   <- ZIO.foreachDiscard(others.values) { n =>
               PolicyGenerationLoggerPure.debug(
                 s"Skipping node '${n.id.value}' because the node is in state '${n.rudderSettings.state.name}'"
               )
             }
      res <- recFilterOut(okNodes, others.keySet)(groupLib)
    } yield (okNodes, res)
  }
}
