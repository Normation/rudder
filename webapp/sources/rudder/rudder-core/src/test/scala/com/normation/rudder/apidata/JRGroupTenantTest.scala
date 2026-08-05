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

package com.normation.rudder.apidata

import com.normation.inventory.domain.NodeId
import com.normation.rudder.apidata.JsonResponseObjects.JRGroup
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.nodes.NodeGroupCategoryId
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.nodes.NodeGroupUid
import com.normation.rudder.tenants.QueryContext
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

/*
 * B1: `JRGroup.fromGroup` must filter the serialized `nodeIds` (the group's `serverList`) to the caller's
 * visible node ids, so a group visible to a tenant does not leak node ids from tenants the caller can not see.
 */
@RunWith(classOf[JUnitRunner])
class JRGroupTenantTest extends Specification {

  private given qc: QueryContext = QueryContext.testQC

  private val nA  = NodeId("nA")
  private val nB  = NodeId("nB")
  private val cat = NodeGroupCategoryId("cat")

  private val group = NodeGroup(
    NodeGroupId(NodeGroupUid("g")),
    name = "g",
    description = "",
    properties = Nil,
    query = None,
    isDynamic = false,
    serverList = Set(nA, nB),
    _isEnabled = true,
    security = None
  )

  "JRGroup.fromGroup nodeIds filtering" should {

    "return every member id when there is no visible-node restriction (admin / None)" in {
      JRGroup.fromGroup(group, cat, None, None).nodeIds === List("nA", "nB")
    }

    "return only the visible member ids when a restriction is given (tenant caller)" in {
      JRGroup.fromGroup(group, cat, None, Some(Set(nA))).nodeIds === List("nA")
    }

    "return no member id when none of the members is visible" in {
      JRGroup.fromGroup(group, cat, None, Some(Set.empty)).nodeIds === Nil
    }
  }
}
