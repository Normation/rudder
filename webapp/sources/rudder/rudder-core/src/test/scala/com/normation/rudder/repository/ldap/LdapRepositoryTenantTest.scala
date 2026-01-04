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

package com.normation.rudder.repository.ldap

import com.normation.eventlog.EventActor
import com.normation.rudder.tenants.QueryContext
import com.normation.rudder.tenants.TenantAccessGrant
import com.normation.rudder.tenants.TenantId
import com.normation.zio.*
import org.junit.runner.*
import org.specs2.mutable.*
import org.specs2.runner.*
import zio.Chunk

/**
 * Test import of a new technique library
 */
@RunWith(classOf[JUnitRunner])
class LdapRepositoryTenantTest extends Specification with SetupLdapRepositories {

  org.slf4j.LoggerFactory
    .getLogger("application.tenants")
    .asInstanceOf[ch.qos.logback.classic.Logger]
    .setLevel(ch.qos.logback.classic.Level.DEBUG)

  val zoneA = QueryContext(EventActor("zoneA user"), TenantAccessGrant.ByTenants(Chunk(TenantId("zoneA"))))
  val zoneB = QueryContext(EventActor("zoneB user"), TenantAccessGrant.ByTenants(Chunk(TenantId("zoneB"))))

  sequential

  "[Groups] When the tenant plugin is disabled, we" should {

    val targetWithNoTenants = {
      Set(
        "special:all_exceptPolicyServers",
        "special:all_policyServers",
        "special:all",
        "group:all-nodes-with-cfengine-agent",
        "group:test-group-node2",
        "group:test-group-node12",
        "group:test-group-node23",
        "group:AIXSystems"
      )
    }
    val targetWithTenantsA  = Set("group:test-group-node1")

    "have an admin with tenant=* able to see everything" in {
      implicit val qc = QueryContext.systemQC
      roGroupRepo.getFullGroupLibrary().runNow.allTargets.values.map(_.debugId) must containTheSameElementsAs(
        targetWithNoTenants.toList ++ targetWithTenantsA
      )
    }

    "have an user with only tenant ZoneA see only one group and no system groups" in {
      implicit val qc = zoneA
      roGroupRepo.getFullGroupLibrary().runNow.allTargets.values.map(_.debugId) must containTheSameElementsAs(
        targetWithTenantsA.toSeq
      )
    }
    "have an user with only tenant ZoneB see nothing" in {
      implicit val qc = zoneB
      roGroupRepo.getFullGroupLibrary().runNow.allTargets.values.map(_.debugId) must containTheSameElementsAs(Nil)
    }
  }
}
