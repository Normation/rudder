/*
 *************************************************************************************
 * Copyright 2025 Normation SAS
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

package com.normation.rudder.services.policies

import com.normation.inventory.domain.AgentType
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.nodes.NodeGroupCategoryId
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.nodes.NodeGroupUid
import com.normation.rudder.domain.nodes.NodeState
import com.normation.rudder.domain.policies.FullGroupTarget
import com.normation.rudder.domain.policies.FullRuleTargetInfo
import com.normation.rudder.domain.policies.GroupTarget
import com.normation.rudder.repository.FullNodeGroupCategory
import com.normation.rudder.services.policies.NodeConfigData.fact1
import com.normation.rudder.services.policies.NodeConfigData.fact2
import com.normation.rudder.services.policies.NodeConfigData.factRoot
import com.normation.rudder.services.policies.NodeConfigData.rootId
import com.normation.zio.*
import com.softwaremill.quicklens.*
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

/*
 * Test that we correctly enforce consistency on groups based on what node we know, and their state.
 */

@RunWith(classOf[JUnitRunner])
class TestGetConsistentNodesAndGroups extends Specification {

  private def newGroup(id: String, nodeIds: Set[NodeId], isSystem: Boolean) = NodeGroup(
    NodeGroupId(NodeGroupUid(id)),
    name = id,
    description = "",
    properties = Nil,
    query = None,
    isDynamic = false,
    serverList = nodeIds,
    _isEnabled = true,
    isSystem = isSystem,
    security = None
  )

  /*
   * Context: the current list of nodes is root, node1 and node2.
   * Node2 was added after last dyn group computation.
   * Node3 was deleted after last dyn group computation.
   * Node4 is not enabled and filtered out of list of OK nodes.
   */

  private val node3Id = NodeId("node3")
  private val fact4   = fact1.modify(_.id).setTo(NodeId("node4")).modify(_.rudderSettings.state).setTo(NodeState.Ignored)
  private val fact5   = fact1
    .modify(_.id)
    .setTo(NodeId("node5"))
    .modify(_.rudderAgent)
    .setTo(fact1.rudderAgent.modify(_.agentType).setTo(AgentType.Dsc))

  // our map of currently known nodes
  private val allNodes = List(factRoot, fact1, fact2, fact4, fact5).map(n => n.id -> n).toMap.view

  // for a user group, we can't know that there is an inconsistency without recomputing the dynamic group,
  // so for this one, we don't know if it should have node2 or not.
  private val g0 = newGroup("0", Set(rootId, fact1.id, fact4.id), isSystem = false)

  private val g1 = newGroup("1", Set(fact1.id, fact2.id), isSystem = false)

  // this one, node2 will be added and node3 removed
  private val hasRootPolicyServer = newGroup("hasPolicyServer-root", Set(rootId, fact1.id, node3Id), isSystem = true)

  // now, plumbing to build the group lib
  private val groups              = Set(g0, g1, hasRootPolicyServer).map(g => (g.id, g))
  private val groupTargets        = groups.map { case (id, g) => (GroupTarget(g.id), g) }
  private val fullRuleTargetInfos = groupTargets.map { gt =>
    (
      gt._1.groupId,
      FullRuleTargetInfo(
        FullGroupTarget(gt._1, gt._2),
        name = "",
        description = "",
        isEnabled = true,
        isSystem = false,
        security = None
      )
    )
  }.toMap

  private def category(name: String, subCats: List[FullNodeGroupCategory], targets: List[FullRuleTargetInfo]) =
    FullNodeGroupCategory(NodeGroupCategoryId(name), "", "", subCats, targets, isSystem = false, security = None)

  private val groupLib = category(
    "test_root",
    List(
      category(
        "subCat1",
        List(
          category("subCat1sub1", Nil, List(fullRuleTargetInfos(g1.id))),
          category("subCat1sub2", Nil, Nil)
        ),
        List(fullRuleTargetInfos(g0.id))
      )
    ),
    List(fullRuleTargetInfos(hasRootPolicyServer.id))
  )

  // now actually do some tests
  val (updatedNodes, updatedLib) = GetConsistentNodesAndGroups(allNodes, groupLib).runNow

  "after consistency check, we" should {

    "not have node4 anymore" in {
      updatedNodes.keySet === (allNodes.keySet.toSet - fact4.id)
    }

    "hasPolicyServer-root now contains node2 and no more node3" in {
      updatedLib.allGroups(hasRootPolicyServer.id).nodeGroup.serverList === Set(rootId, fact1.id, fact2.id)
    }

    "group0 still contain fact4 even if disabled because we avoid changing group content for that (we rely of node list)" in {
      updatedLib.allGroups(g0.id).nodeGroup.serverList === g0.serverList
    }

    "group1 doesn't contain fact3 anymore because not in list of current nodes" in {
      updatedLib.allGroups(g1.id).nodeGroup.serverList === (g1.serverList - node3Id)
    }
  }
}
