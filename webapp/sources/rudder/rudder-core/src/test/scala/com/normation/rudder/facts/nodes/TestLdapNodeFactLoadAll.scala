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

package com.normation.rudder.facts.nodes

import com.normation.inventory.domain.NodeId
import com.normation.inventory.ldap.core.LDAPConstants
import com.normation.ldap.sdk.BuildFilter.ALL
import com.normation.ldap.sdk.One
import com.normation.rudder.services.nodes.NodeInfoService
import com.normation.zio.*
import com.unboundid.ldap.sdk.SearchRequest
import com.unboundid.ldap.sdk.SearchScope as UnboundidSearchScope
import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.runner.*
import org.specs2.mutable.*
import org.specs2.runner.*
import zio.*

/*
 * Check that both the old pre-9.1.5
 */
@RunWith(classOf[JUnitRunner])
class TestLdapNodeFactLoadAll extends Specification {
  sequential

  Security.addProvider(new BouncyCastleProvider())

  val mock = new MockLdapFactStorage()

  val bulk:       LdapNodeFactStorage = mock.nodeFactStorage
  val nodeByNode: LdapNodeFactStorage = mock.nodeFactStorageNodeByNode

  def addEntry(lines: String*): Unit = mock.testServer.add(lines*)

  def rudderNodeEntry(id: String): Seq[String] = Seq(
    s"dn: nodeId=${id},ou=Nodes,cn=rudder-configuration",
    "objectClass: top",
    "objectClass: rudderNode",
    s"nodeId: ${id}",
    s"cn: ${id}",
    "isSystem: false",
    "isBroken: false",
    "createTimestamp: 20070101000000Z"
  )

  def inventoryNodeEntry(id: String, status: String, machineDn: Option[String]): Seq[String] = Seq(
    s"dn: nodeId=${id},ou=Nodes,ou=${status} Inventories,ou=Inventories,cn=rudder-configuration",
    "objectClass: top",
    "objectClass: node",
    "objectClass: unixNode",
    "objectClass: linuxNode",
    "osVersion: 12",
    "osName: Debian",
    "osKernelVersion: 6.1.0",
    s"nodeId: ${id}",
    s"cn: ${id}",
    s"nodeHostname: ${id}.normation.com",
    "inventoryDate: 20260101123456.948Z",
    "localAdministratorAccountName: root",
    "policyServerId: root",
    """agentName: {"agentType":"cfengine-community","version":"9.1.0","securityToken":{"value":"-----BEGIN CERTIFICATE-----\nMIIFSzCCAzOgAwIBAgIUUiS87+meuwydJeAcCKI35Ko7kmowDQYJKoZIhvcNAQEL\n-----END CERTIFICATE-----\n","type":"certificate"},"capabilities":[]}"""
  ) ++ machineDn.map(dn => s"container: ${dn}").toSeq

  def machineEntry(id: String, status: String): Seq[String] = Seq(
    s"dn: machineId=${id},ou=Machines,ou=${status} Inventories,ou=Inventories,cn=rudder-configuration",
    "objectClass: top",
    "objectClass: device",
    "objectClass: machine",
    s"machineId: ${id}",
    s"cn: ${id}"
  )

  def loadBoth(): (AllCoreNodeFacts, AllCoreNodeFacts) = {
    (bulk.loadAllCoreNodeFacts().runNow, nodeByNode.loadAllCoreNodeFacts().runNow)
  }

  "loading all node facts" should {

    "give the same facts in bulk and node by node, on the sample directory" in {
      val (AllCoreNodeFacts(bulkPending, bulkAccepted), AllCoreNodeFacts(oneByOnePending, oneByOneAccepted)) = loadBoth()

      // the sample data has more inventory entries than rudder node entries: only the nodes that
      // have both are facts, and that is what both paths must agree on
      (bulkAccepted must not be empty) and
      (bulkAccepted must beEqualTo(oneByOneAccepted)) and
      (bulkPending must beEqualTo(oneByOnePending))
    }

    "not invent a fact for an inventory entry that has no rudder node entry" in {
      addEntry(inventoryNodeEntry("lonely-inventory", "Accepted", None)*)

      val (AllCoreNodeFacts(_, bulkAccepted), AllCoreNodeFacts(_, oneByOneAccepted)) = loadBoth()

      (bulkAccepted.get(NodeId("lonely-inventory")) must beNone) and
      (bulkAccepted must beEqualTo(oneByOneAccepted))
    }

    "not invent a fact for a rudder node entry that has no inventory entry" in {
      addEntry(rudderNodeEntry("lonely-rudder")*)

      val (AllCoreNodeFacts(bulkPending, bulkAccepted), AllCoreNodeFacts(_, oneByOneAccepted)) = loadBoth()

      (bulkAccepted.get(NodeId("lonely-rudder")) must beNone) and
      (bulkPending.get(NodeId("lonely-rudder")) must beNone) and
      (bulkAccepted must beEqualTo(oneByOneAccepted))
    }

    "load pending nodes, which are keyed on the pending inventory subtree" in {
      addEntry(rudderNodeEntry("pending1")*)
      addEntry(machineEntry("machine-pending1", "Pending")*)
      addEntry(
        inventoryNodeEntry(
          "pending1",
          "Pending",
          Some("machineId=machine-pending1,ou=Machines,ou=Pending Inventories,ou=Inventories,cn=rudder-configuration")
        )*
      )

      val (AllCoreNodeFacts(bulkPending, bulkAccepted), AllCoreNodeFacts(oneByOnePending, _)) = loadBoth()

      (bulkPending.get(NodeId("pending1")) must beSome) and
      // a pending node is not an accepted one
      (bulkAccepted.get(NodeId("pending1")) must beNone) and
      (bulkPending must beEqualTo(oneByOnePending))
    }

    "resolve the machine of a node whose machine entry is not in its own status subtree" in {
      // that (used to) happen on servers where an acceptation only moved part of the entries: the node is
      // accepted but its machine stayed in the pending subtree. We should still get it. It will corrected on a
      // following storage save.
      addEntry(rudderNodeEntry("stray-machine-node")*)
      addEntry(machineEntry("machine-stray", "Pending")*)
      addEntry(
        inventoryNodeEntry(
          "stray-machine-node",
          "Accepted",
          Some("machineId=machine-stray,ou=Machines,ou=Pending Inventories,ou=Inventories,cn=rudder-configuration")
        )*
      )

      val (AllCoreNodeFacts(_, bulkAccepted), AllCoreNodeFacts(_, oneByOneAccepted)) = loadBoth()

      val fact = bulkAccepted.get(NodeId("stray-machine-node"))

      (fact must beSome) and
      // the machine was found, so its id is the one of the stray entry and not a placeholder
      (fact.map(_.machine.id.value) must beSome("machine-stray")) and
      (bulkAccepted must beEqualTo(oneByOneAccepted))
    }

    "ignore the child entries of a machine" in {
      // machine entries have children (bios, cpu, memory slots...). The searches are `One` scoped
      // so those must never be taken for machines, whatever their number.
      addEntry(rudderNodeEntry("machine-with-elements")*)
      addEntry(machineEntry("machine-elts", "Accepted")*)
      addEntry(
        "dn: biosName=bios1,machineId=machine-elts,ou=Machines,ou=Accepted Inventories,ou=Inventories,cn=rudder-configuration",
        "objectClass: top",
        "objectClass: physicalElement",
        "objectClass: biosPhysicalElement",
        "biosName: bios1",
        "editor: Phoenix Technologies LTD"
      )
      addEntry(
        inventoryNodeEntry(
          "machine-with-elements",
          "Accepted",
          Some("machineId=machine-elts,ou=Machines,ou=Accepted Inventories,ou=Inventories,cn=rudder-configuration")
        )*
      )

      val (AllCoreNodeFacts(_, bulkAccepted), AllCoreNodeFacts(_, oneByOneAccepted)) = loadBoth()

      (bulkAccepted.get(NodeId("machine-with-elements")).map(_.machine.id.value) must beSome("machine-elts")) and
      (bulkAccepted must beEqualTo(oneByOneAccepted))
    }

    "never ask the directory for the software of a node" in {
      val attrs = NodeInfoService.nodeInfoAttributes :+ LDAPConstants.A_SOFTWARE_UPDATE

      val withSoftwareAttribute = (for {
        con     <- mock.ldap
        entries <- con.searchStreamed(
                     mock.acceptedDIT.NODES.dn,
                     One,
                     ALL,
                     attrs*
                   )(Right.apply)
      } yield entries.results.filter(_(LDAPConstants.A_SOFTWARE_DN).isDefined)).runNow

      (NodeInfoService.nodeInfoAttributes.contains(LDAPConstants.A_SOFTWARE_DN) must beFalse) and
      (withSoftwareAttribute must beEmpty)
    }
  }

  "a streamed search" should {

    "fail when the directory truncates the result, where a plain search reports and truncates" in {
      // check that error on SIZE_LIMIT_EXCEEDED are real errors
      val limited = new SearchRequest(
        mock.acceptedDIT.NODES.dn.toString,
        UnboundidSearchScope.ONE,
        ALL,
        NodeInfoService.nodeInfoAttributes*
      )
      limited.setSizeLimit(1)

      val streamed = (for {
        con <- mock.ldap
        res <- con.searchStreamed(limited)(Right.apply).either
      } yield res).runNow

      val plain = (for {
        con <- mock.ldap
        res <- con.search(limited).either
      } yield res).runNow

      (streamed must beLeft) and
      (plain must beRight)
    }
  }
}
