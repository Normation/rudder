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

package com.normation.rudder.repository

import com.normation.eventlog.EventActor
import com.normation.inventory.domain.NodeId
import com.normation.rudder.MockGlobalParam
import com.normation.rudder.MockNodeGroups
import com.normation.rudder.MockNodes
import com.normation.rudder.MockTenants
import com.normation.rudder.domain.Constants
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.nodes.NodeGroupUid
import com.normation.rudder.domain.nodes.NodeVisibility
import com.normation.rudder.tenants.*
import com.normation.zio.*
import com.softwaremill.quicklens.*
import org.junit.runner.*
import org.specs2.mutable.*
import org.specs2.runner.*
import zio.Chunk

/*
 * A group write by an actor only changes the members it can see: the members in tenants it can not see are
 * kept from the stored group, and are not added.
 */
@RunWith(classOf[JUnitRunner])
class GroupMembersTenantTest extends Specification {

  sequential

  private val mockTenants = {
    val m = new MockTenants()
    m.tenantRepo.setTenantEnabled(true).runNow
    m
  }
  private val mockNodes   = new MockNodes(mockTenants)
  private val mockGroups  = new MockNodeGroups(mockNodes, new MockGlobalParam(mockTenants), mockTenants)
  private val repo        = mockGroups.groupsRepo

  private val zoneA =
    ChangeContext.newFor(EventActor("zoneA"), TenantAccessGrant.ByTenants(Chunk(TenantAccess(TenantId("zoneA")))))
  private val admin = ChangeContext.newForRudder()

  // the mock nodes carry no tenant: a zoneA actor sees none of them
  private val members = Set(MockNodes.rootId, MockNodes.node1.id, MockNodes.node2.id)

  private def group(id: String): NodeGroup = mockGroups.g0
    .modify(_.id)
    .setTo(NodeGroupId(NodeGroupUid(id)))
    .modify(_.name)
    .setTo(id)
    .modify(_.serverList)
    .setTo(members)
    .modify(_.security)
    .setTo(Some(SecurityTag.ByTenants(Chunk(TenantId("zoneA")))))

  private def stored(id: String): Set[NodeId] = {
    repo.getNodeGroup(NodeGroupId(NodeGroupUid(id)))(using QueryContext.systemQC).runNow._1.serverList
  }

  private def created(id: String): NodeGroup = {
    val g = group(id)
    repo.create(g, Constants.ROOT_GROUP_CATEGORY)(using admin).runNow
    g
  }

  "NodeVisibility" should {
    val n1 = NodeId("n1")
    val n2 = NodeId("n2")
    val n3 = NodeId("n3")

    "replace the visible members and keep the others" in {
      NodeVisibility.Only(Set(n1, n2)).replaceVisible(stored = Set(n1, n3), submitted = Set(n2)) === Set(n2, n3)
    }

    "not add a submitted member that is not visible" in {
      NodeVisibility.Only(Set(n1, n2)).replaceVisible(stored = Set(n1), submitted = Set(n2, n3)) === Set(n2)
    }

    "replace everything for an unrestricted reader" in {
      NodeVisibility.Everything.replaceVisible(stored = Set(n1, n3), submitted = Set(n2)) === Set(n2)
    }
  }

  "A group update" should {
    "keep the members the actor can not see" in {
      val g = created("g-update-zoneA")
      repo.update(g.modify(_.serverList).setTo(Set()))(using zoneA).runNow
      stored(g.id.uid.value) === members
    }

    "replace all members for an administrator" in {
      val g = created("g-update-admin")
      repo.update(g.modify(_.serverList).setTo(Set()))(using admin).runNow
      stored(g.id.uid.value) === Set()
    }

    "not remove a member the actor can not see" in {
      val g = created("g-diff-zoneA")
      repo.updateDiffNodes(g.id, Nil, List(MockNodes.node1.id))(using zoneA).runNow
      stored(g.id.uid.value) === members
    }

    "remove it for an administrator" in {
      val g = created("g-diff-admin")
      repo.updateDiffNodes(g.id, Nil, List(MockNodes.node1.id))(using admin).runNow
      stored(g.id.uid.value) === members - MockNodes.node1.id
    }

    "not add a member the actor can not see" in {
      val g = created("g-diff-add-zoneA")
      repo.update(g.modify(_.serverList).setTo(Set()))(using admin).runNow
      repo.updateDiffNodes(g.id, List(MockNodes.node1.id), Nil)(using zoneA).runNow
      stored(g.id.uid.value) === Set()
    }
  }

  "A group creation" should {
    "not add the members the actor can not see" in {
      val g = group("g-create-zoneA")
      repo.create(g, Constants.ROOT_GROUP_CATEGORY)(using zoneA).runNow
      stored(g.id.uid.value) === Set()
    }

    "add them for an administrator" in {
      created("g-create-admin")
      stored("g-create-admin") === members
    }
  }
}
