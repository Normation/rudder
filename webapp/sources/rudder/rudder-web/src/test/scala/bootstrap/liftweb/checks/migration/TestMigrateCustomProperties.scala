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

package bootstrap.liftweb.checks.migration

import com.normation.errors
import com.normation.inventory.domain.NodeId
import com.normation.rudder.facts.nodes.CoreNodeFactRepository
import com.normation.rudder.facts.nodes.MockLdapFactStorage
import com.normation.rudder.facts.nodes.NoopGetNodesBySoftwareName
import com.normation.rudder.facts.nodes.QueryContext
import com.normation.rudder.facts.nodes.SelectFacts
import com.normation.rudder.tenants.DefaultTenantService

import com.normation.zio.UnsafeRun
import org.junit.runner.RunWith
import org.specs2.mutable.*
import org.specs2.runner.JUnitRunner

import zio.*

@RunWith(classOf[JUnitRunner])
class TestMigrateCustomProperties extends Specification {

  val mockLdapFactStorage = new MockLdapFactStorage()

  val factRepo: CoreNodeFactRepository = {
    (for {
      tenantService <- DefaultTenantService.make(Nil)
      repo          <- CoreNodeFactRepository.make(mockLdapFactStorage.nodeFactStorage, NoopGetNodesBySoftwareName, tenantService, Chunk())
    } yield repo).runNow
  }

  val migration = new CheckMigrateCustomProperties(mockLdapFactStorage.ldap, factRepo, mockLdapFactStorage.acceptedDIT)

  org.slf4j.LoggerFactory
    .getLogger("nodes")
    .asInstanceOf[ch.qos.logback.classic.Logger]
    .setLevel(ch.qos.logback.classic.Level.TRACE)

  sequential

  "Migrating custom" should {
    /*
     * Node1 contains:
     * - custom properties 'datacenter' and 'from_inv'
     * - node property 'foo'
     */

    "find some properties to migrate" in {
      (migration.findNodeNeedingMigration().runNow must containTheSameElementsAs(List(NodeId("node1")))) and
      (getNode1PropNamesAsSeenByLdap() must containTheSameElementsAs(List("foo", "datacenter", "from_inv"))) and
      (getNode1PropNamesAsSeenByRepo() must containTheSameElementsAs(List("foo", "datacenter", "from_inv")))
    }

    "migrate all nodes" in {
      migration.migrateAll().either.runNow must beRight()
    }

    "not find any nodes to migrate after migration" in {
      (migration.findNodeNeedingMigration().runNow must beEmpty) and
      (getNode1PropNamesAsSeenByLdap() must containTheSameElementsAs(List("foo", "datacenter", "from_inv"))) and
      (getNode1PropNamesAsSeenByRepo() must containTheSameElementsAs(List("foo", "datacenter", "from_inv")))
    }
  }

  def getNode1PropNamesAsSeenByLdap(): Seq[String] = {
    (for {
      n <- mockLdapFactStorage.nodeFactStorage.getAccepted(NodeId("node1"))(SelectFacts.none).notOptional("node1 must be present")
    } yield n.properties.map(_.name)).runNow
  }

  def getNode1PropNamesAsSeenByRepo(): Chunk[String] = {
    (for {
      n <- factRepo.get(NodeId("node1"))(QueryContext.testQC).notOptional("node1 must be present")
    } yield n.properties.map(_.name)).runNow
  }
}
