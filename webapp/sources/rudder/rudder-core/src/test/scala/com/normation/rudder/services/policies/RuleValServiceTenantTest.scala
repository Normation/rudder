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
package com.normation.rudder.services.policies

import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.nodes.NodeGroupCategoryId
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.nodes.NodeGroupUid
import com.normation.rudder.domain.policies.FullGroupTarget
import com.normation.rudder.domain.policies.FullRuleTargetInfo
import com.normation.rudder.domain.policies.GroupTarget
import com.normation.rudder.domain.policies.Rule
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.RuleTarget
import com.normation.rudder.domain.policies.RuleUid
import com.normation.rudder.repository.FullNodeGroupCategory
import com.normation.rudder.rule.category.RuleCategoryId
import com.normation.rudder.services.nodes.PropertyEngineServiceImpl
import com.normation.rudder.tenants.SecurityTag
import com.normation.rudder.tenants.TenantId
import org.junit.runner.*
import org.specs2.mutable.*
import org.specs2.runner.*
import zio.Chunk

/**
 * Test that the tenant boundary is enforced when a rule is resolved to its targeted nodes at
 * policy generation: a rule only ever reaches nodes (and simple targets) sharing one of its tenants.
 */
@RunWith(classOf[JUnitRunner])
class RuleValServiceTenantTest extends Specification {

  val ruleValService =
    new RuleValServiceImpl(new InterpolatedValueCompilerImpl(new PropertyEngineServiceImpl(List.empty)))

  // three nodes, one per tenant situation
  val nA:     NodeId = NodeId("nA")     // tenant zoneA
  val nB:     NodeId = NodeId("nB")     // tenant zoneB
  val nAdmin: NodeId = NodeId("nAdmin") // no tenant (admin-only)

  def tenants(ids: String*): Option[SecurityTag] = Some(SecurityTag.ByTenants(Chunk.fromIterable(ids.map(TenantId(_)))))

  // all three nodes with their per-node info (none is a policy server) and tenant tag
  val nodeInfos: Map[NodeId, NodeSecurityInfo] = Map(
    nA     -> NodeSecurityInfo(isPolicyServer = false, tenants("zoneA")),
    nB     -> NodeSecurityInfo(isPolicyServer = false, tenants("zoneB")),
    nAdmin -> NodeSecurityInfo(isPolicyServer = false, None)
  )

  def mkGroup(id: String, security: Option[SecurityTag], nodes: Set[NodeId]): NodeGroup = {
    NodeGroup(
      NodeGroupId(NodeGroupUid(id)),
      name = id,
      description = "",
      properties = Nil,
      query = None,
      isDynamic = false,
      serverList = nodes,
      _isEnabled = true,
      security = security
    )
  }

  def mkGroupLib(groups: List[NodeGroup]): FullNodeGroupCategory = {
    FullNodeGroupCategory(
      NodeGroupCategoryId("root"),
      name = "",
      description = "",
      subCategories = Nil,
      targetInfos = groups.map(g => {
        FullRuleTargetInfo(
          FullGroupTarget(GroupTarget(g.id), g),
          name = "",
          description = "",
          isEnabled = true,
          isSystem = false,
          security = g.security
        )
      }),
      isSystem = false,
      security = None
    )
  }

  def mkRule(security: Option[SecurityTag], targets: Set[RuleTarget]): Rule = {
    Rule(
      RuleId(RuleUid("r")),
      name = "r",
      categoryId = RuleCategoryId("c"),
      targets = targets,
      directiveIds = Set(),
      shortDescription = "",
      longDescription = "",
      isEnabledStatus = true,
      isSystem = false,
      security = security
    )
  }

  // a group holding all three nodes, `Open` so the target-level filter never drops it: the point of these
  // cases is the node-level filter.
  val openGroup: NodeGroup             = mkGroup("all", Some(SecurityTag.Open), Set(nA, nB, nAdmin))
  val libOpen:   FullNodeGroupCategory = mkGroupLib(List(openGroup))
  val openTgt:   Set[RuleTarget]       = Set(GroupTarget(openGroup.id))

  "node-level tenant filter" should {

    "let an untagged (admin) rule reach every node" in {
      ruleValService.getTargetedNodes(mkRule(None, openTgt), libOpen, nodeInfos) ===
      Set(nA, nB, nAdmin)
    }

    "let an `Open` rule reach every node" in {
      ruleValService.getTargetedNodes(mkRule(Some(SecurityTag.Open), openTgt), libOpen, nodeInfos) ===
      Set(nA, nB, nAdmin)
    }

    "restrict a single-tenant rule to its own tenant's nodes" in {
      ruleValService.getTargetedNodes(mkRule(tenants("zoneA"), openTgt), libOpen, nodeInfos) ===
      Set(nA)
    }

    "let a multi-tenant rule reach all of its tenants' nodes but not admin-only nodes" in {
      ruleValService.getTargetedNodes(mkRule(tenants("zoneA", "zoneB"), openTgt), libOpen, nodeInfos) ===
      Set(nA, nB)
    }

    "reach nothing for a tenant the rule does not share with any node" in {
      ruleValService.getTargetedNodes(mkRule(tenants("zoneC"), openTgt), libOpen, nodeInfos) ===
      Set()
    }
  }

  "target-level tenant filter" should {

    // group is zoneB-only; a zoneA rule cannot even see the group, so the target is dropped entirely
    val zoneBGroup = mkGroup("gB", tenants("zoneB"), Set(nA, nB, nAdmin))
    val libB       = mkGroupLib(List(zoneBGroup))
    val bTgt: Set[RuleTarget] = Set(GroupTarget(zoneBGroup.id))

    "drop a group target the rule does not share a tenant with" in {
      ruleValService.getTargetedNodes(mkRule(tenants("zoneA"), bTgt), libB, nodeInfos) ===
      Set()
    }

    "keep a shared group target, then still node-filter its members" in {
      // zoneB rule sees the zoneB group; among its members only the zoneB node survives the node filter
      ruleValService.getTargetedNodes(mkRule(tenants("zoneB"), bTgt), libB, nodeInfos) ===
      Set(nB)
    }
  }
}
