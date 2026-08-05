/*
 *************************************************************************************
 * Copyright 2024 Normation SAS
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

package com.normation.rudder.domain.properties

import com.normation.rudder.MockNodes
import com.normation.rudder.MockTenants
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.nodes.NodeGroupUid
import com.normation.rudder.properties.InMemoryPropertiesRepository
import com.normation.rudder.properties.TenantScopedGroupProps
import com.normation.rudder.tenants.QueryContext
import com.normation.rudder.tenants.SecurityTag
import com.normation.rudder.tenants.TenantAccess
import com.normation.rudder.tenants.TenantAccessGrant
import com.normation.rudder.tenants.TenantId
import com.normation.zio.UnsafeRun
import com.softwaremill.quicklens.*
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import zio.Chunk

@RunWith(classOf[JUnitRunner])
class PropertiesRepositoryTest extends Specification {
  sequential

  private val mockTenants  = new MockTenants()
  private val mockNodes    = new MockNodes(mockTenants)
  private val nodeFactRepo = mockNodes.nodeFactRepo
  private val repo         = InMemoryPropertiesRepository.make(nodeFactRepo, mockTenants.checkTenant).runNow

  implicit private val qc: QueryContext = QueryContext.testQC

  "PropertiesRepository" should {
    // node1 has empty properties
    // node2 has some properties
    val resolvedNode2 = SuccessNodePropertyHierarchy(
      Chunk.from(
        MockNodes.node2Node.properties.map(n =>
          NodePropertyHierarchy(MockNodes.node2.id, ParentProperty.Node(MockNodes.node2.hostname, MockNodes.node2.id, n, None))
        )
      )
    )
    val initialProps  = Map(
      MockNodes.node1.id -> ResolvedNodePropertyHierarchy.empty,
      MockNodes.node2.id -> resolvedNode2
    )
    "save node properties" in {
      (repo.saveNodeProps(initialProps) *> repo.getAllNodeProps()).runNow must containTheSameElementsAs(initialProps.toList)
    }

    "get node properties on several nodes for a property" in {
      // node1 without the property is ommited from the result
      val expected = resolvedNode2.resolved.filter(_.prop.name == "simpleString").map(MockNodes.node2.id -> _)
      repo.getNodesProp(Set(MockNodes.node1.id, MockNodes.node2.id), "simpleString").runNow must containTheSameElementsAs(
        expected
      )
    }

    "get node properties on several nodes for a property with resolution errors" in {
      // if node2 has failed resolution of properties
      val props = initialProps
        .updated(
          MockNodes.node2.id,
          FailedNodePropertyHierarchy(Chunk.empty, PropertyHierarchyError.DAGHierarchyError("A DAGError"))
        )
      (repo.saveNodeProps(props) *> repo.getNodesProp(
        Set(MockNodes.node1.id, MockNodes.node2.id),
        "simpleString"
      )).runNow must beEmpty
    }
  }

  // N3/N5: group properties must be tenant-filtered at the repository level. Each cached entry carries its
  // group's SecurityTag; a reader only sees an entry it can see (a ByTenants reader never sees another
  // tenant's group, nor an untagged/admin-only one).
  "PropertiesRepository group tenant filtering" should {
    val groupA = NodeGroupId(NodeGroupUid("group-zoneA"))
    val groupB = NodeGroupId(NodeGroupUid("group-zoneB"))
    val tagA   = SecurityTag.ByTenants(Chunk(TenantId("zoneA")))
    val tagB   = SecurityTag.ByTenants(Chunk(TenantId("zoneB")))
    val groupProps: Map[NodeGroupId, TenantScopedGroupProps] = Map(
      groupA -> TenantScopedGroupProps(Some(tagA), ResolvedNodePropertyHierarchy.empty),
      groupB -> TenantScopedGroupProps(Some(tagB), ResolvedNodePropertyHierarchy.empty)
    )

    val qcA: QueryContext =
      QueryContext.testQC.modify(_.accessGrant).setTo(TenantAccessGrant.ByTenants(Chunk(TenantAccess(TenantId("zoneA")))))

    "an admin (All) sees every group's properties" in {
      (repo.saveGroupProps(groupProps) *> repo.getAllGroupProps()(using QueryContext.testQC)).runNow.keySet must_=== Set(
        groupA,
        groupB
      )
    }

    "a zoneA reader only sees zoneA group's properties, not zoneB's" in {
      (repo.saveGroupProps(groupProps) *> repo.getAllGroupProps()(using qcA)).runNow.keySet must_=== Set(groupA)
    }

    "a zoneA reader can read the zoneA group but not the zoneB group (no existence oracle)" in {
      val res = (for {
        _ <- repo.saveGroupProps(groupProps)
        a <- repo.getGroupProps(groupA)(using qcA)
        b <- repo.getGroupProps(groupB)(using qcA)
      } yield (a.isDefined, b.isDefined)).runNow
      res must_=== ((true, false))
    }
  }
}
