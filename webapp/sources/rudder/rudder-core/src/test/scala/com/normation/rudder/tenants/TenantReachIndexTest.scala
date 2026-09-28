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

package com.normation.rudder.tenants

import com.normation.GitVersion
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.nodes.NodeGroupUid
import com.normation.rudder.domain.policies.AllTarget
import com.normation.rudder.domain.policies.GroupTarget
import com.normation.rudder.domain.policies.RuleTarget
import com.normation.rudder.domain.properties.GlobalParameter
import com.normation.rudder.domain.properties.Visibility
import com.normation.rudder.facts.nodes.CoreNodeFact
import com.normation.rudder.services.policies.NodeConfigData
import com.normation.rudder.services.policies.fetchinfo.GlobalPropertyDistribution
import com.softwaremill.quicklens.*
import com.typesafe.config.ConfigValueFactory
import org.junit.runner.*
import org.specs2.mutable.*
import org.specs2.runner.*
import zio.Chunk

/*
 * Which global properties reach a node, given the tenants of each (ADR 28945-global-properties-and-tenant-scoping).
 */
@RunWith(classOf[JUnitRunner])
class TenantReachIndexTest extends Specification {

  private val zoneA = SecurityTag.ByTenants(Chunk(TenantId("zoneA")))
  private val zoneB = SecurityTag.ByTenants(Chunk(TenantId("zoneB")))
  private val both  = SecurityTag.ByTenants(Chunk(TenantId("zoneA"), TenantId("zoneB")))

  private def globalProperty(name: String, security: Option[SecurityTag], scope: Option[RuleTarget] = None): GlobalParameter = {
    GlobalParameter(
      name,
      GitVersion.DEFAULT_REV,
      ConfigValueFactory.fromAnyRef(name + "-value"),
      None,
      "",
      None,
      Visibility.Displayed,
      security,
      scope
    )
  }

  private val untagged = globalProperty("untagged", None)
  private val openRo   = globalProperty("open", Some(SecurityTag.OpenRo))
  private val propA    = globalProperty("a", Some(zoneA))
  private val propB    = globalProperty("b", Some(zoneB))

  private def names(security: Option[SecurityTag]): List[String] = {
    TenantReachIndex(List(untagged, openRo, propA, propB).map(p => (p.security, p))).reaching(security).map(_.name).sorted
  }

  "a global property reaches an object" should {

    "when both are untagged" in {
      names(None) must beEqualTo(List("open", "untagged"))
    }

    "when they share a tenant" in {
      names(Some(zoneA)) must beEqualTo(List("a", "open", "untagged"))
    }

    "for each tenant it has" in {
      names(Some(both)) must beEqualTo(List("a", "b", "open", "untagged"))
    }

    // untagged is administrator-only, and a tenant-tagged property does not reach it
    "but a tenant property never reaches an untagged object" in {
      names(None) must not contain ("a")
    }

    "and an untagged property reaches everybody, so an installation without tenants is unchanged" in {
      (names(Some(zoneA)) must contain("untagged")) and (names(Some(zoneB)) must contain("untagged"))
    }

    "and an open property reaches everybody" in {
      (names(Some(zoneA)) must contain("open")) and (names(None) must contain("open"))
    }
  }

  "the tenants of one object" should {
    "not decide for another" in {
      names(Some(zoneB)) must not contain ("a")
    }
  }

  /*
   * At generation the tenant predicate is combined with the scope (ADR 29409). Both are per node,
   * and what comes out of this is `${rudder.param.X}`, `rudder-parameters.json` and the node's
   * property defaults at once.
   */
  "at generation, a global property reaches a node" should {

    val nodeA   = NodeConfigData.fact1.modify(_.rudderSettings.security).setTo(Some(zoneA))
    val nodeB   = NodeConfigData.fact1.modify(_.rudderSettings.security).setTo(Some(zoneB))
    val noGroup = GroupTarget(NodeGroupId(NodeGroupUid("no-such-group")))
    val facts   = Map(nodeA.id -> nodeA)

    def distributed(properties: List[GlobalParameter], node: CoreNodeFact): List[String] = {
      GlobalPropertyDistribution(properties.map(p => (p, ())).toMap, NodeConfigData.groupLib, facts)
        .forNode(node)
        .map(_._1.name)
        .sorted
    }

    "when its tenants and its scope both admit it" in {
      distributed(List(globalProperty("p", Some(zoneA), Some(AllTarget))), nodeA) must beEqualTo(List("p"))
    }

    "but not when its tenants exclude the node, whatever its scope says" in {
      distributed(List(globalProperty("p", Some(zoneA), Some(AllTarget))), nodeB) must beEmpty
    }

    "and not when its scope excludes the node, whatever its tenants say" in {
      distributed(List(globalProperty("p", Some(zoneA), Some(noGroup))), nodeA) must beEmpty
    }

    // the scope was only enforced on the property-cache path until now, so an out-of-scope node
    // still received the property here, as a `${rudder.param}` value and as a property default
    "and not when an untagged property is scoped away from it" in {
      distributed(List(globalProperty("p", None, Some(noGroup))), nodeA) must beEmpty
    }

    "and a scoped property overrides the unscoped one of the same name" in {
      val properties = List(globalProperty("p", None), globalProperty("p", None, Some(AllTarget)))
      GlobalPropertyDistribution(properties.map(p => (p, ())).toMap, NodeConfigData.groupLib, facts)
        .forNode(nodeA)
        .map(_._1.scope) must beEqualTo(List(Some(AllTarget)))
    }
  }
}
