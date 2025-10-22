/*
 *************************************************************************************
 * Copyright 2011 Normation SAS
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

package com.normation.inventory.ldap.core

import com.normation.errors.*
import com.normation.inventory.domain.*
import com.normation.ldap.listener.InMemoryDsConnectionProvider
import com.normation.ldap.sdk.RoLDAPConnection
import com.normation.ldap.sdk.RwLDAPConnection
import com.normation.zio.ZioRuntime
import com.softwaremill.quicklens.*
import com.unboundid.ldap.sdk.DN
import com.unboundid.ldap.sdk.Modification
import com.unboundid.ldap.sdk.ModificationType
import org.junit.runner.*
import org.specs2.execute
import org.specs2.mutable.*
import org.specs2.runner.*
import scala.annotation.nowarn
import zio.*

final case class SystemError(cause: Throwable) extends RudderError {
  def msg = "Error in test"
}

/**
 * A simple test class to check that the demo data file is up to date
 * with the schema (there may still be a desynchronization if both
 * demo-data, test data and test schema for UnboundID are not synchronized
 * with OpenLDAP Schema).
 */
@nowarn("msg=a type was inferred to be `\\w+`; this may indicate a programming error.")
@RunWith(classOf[JUnitRunner])
class TestInventory extends Specification {

  implicit class RunThing[E, T](thing: ZIO[Any, E, T])      {
    def testRun: Either[E, T] = ZioRuntime.unsafeRun(thing.either)
  }
  implicit class RunOptThing[A](thing: IOResult[Option[A]]) {
    def testRunGet: A = ZioRuntime.unsafeRun(thing.either) match {
      case Right(Some(a)) => a
      case Right(None)    => throw new RuntimeException(s"Error in test: found None, expected Some")
      case Left(err)      => throw new RuntimeException(s"Error in test: ${err}")
    }
  }

  implicit class TestIsOK[E, T](thing: ZIO[Any, E, T]) {
    def isOK: execute.Result = thing.testRun must beRight
  }

  implicit class ForceGetE[E, A](opt: Either[E, A]) {
    def forceGet: A = opt match {
      case Right(x)  => x
      case Left(err) => throw new Exception(s"error in Test: ${err}")
    }
  }

  // needed because the in memory LDAP server is not used with connection pool
  sequential

  val schemaLDIFs: List[String] = (
    "00-core" ::
      "01-pwpolicy" ::
      "04-rfc2307bis" ::
      "05-rfc4876" ::
      "099-0-inventory" ::
      Nil
  ) map { name =>
    val n = "ldap-data/schema/" + name + ".ldif"
    val r = this.getClass.getClassLoader.getResource(n)
    if (null == r) {
      throw new IllegalArgumentException("Can not find ressources to load: " + n)
    }
    // toURI is needed for https://issues.rudder.io/issues/19186
    r.toURI.getPath()
  }

  val baseDN = "cn=rudder-configuration"

  val bootstrapLDIFs: List[String] = ("ldap-data/bootstrap.ldif" :: "ldap-data/inventory-sample-data.ldif" :: Nil) map { name =>
    // toURI is needed for https://issues.rudder.io/issues/19186
    this.getClass.getClassLoader.getResource(name).toURI.getPath
  }

  val ldap: InMemoryDsConnectionProvider[RwLDAPConnection] = InMemoryDsConnectionProvider[RwLDAPConnection](
    baseDNs = baseDN :: Nil,
    schemaLDIFPaths = schemaLDIFs,
    bootstrapLDIFPaths = bootstrapLDIFs
  )

  val roLdap: InMemoryDsConnectionProvider[RoLDAPConnection] = InMemoryDsConnectionProvider[RoLDAPConnection](
    baseDNs = baseDN :: Nil,
    schemaLDIFPaths = schemaLDIFs,
    bootstrapLDIFPaths = bootstrapLDIFs
  )

  val softwareDN = new DN("ou=Inventories, cn=rudder-configuration")

  val acceptedNodesDitImpl: InventoryDit = new InventoryDit(
    new DN("ou=Accepted Inventories, ou=Inventories, cn=rudder-configuration"),
    softwareDN,
    "Accepted inventories"
  )
  val pendingNodesDitImpl:  InventoryDit = new InventoryDit(
    new DN("ou=Pending Inventories, ou=Inventories, cn=rudder-configuration"),
    softwareDN,
    "Pending inventories"
  )
  val removedNodesDitImpl = new InventoryDit(
    new DN("ou=Removed Inventories, ou=Inventories, cn=rudder-configuration"),
    softwareDN,
    "Removed Servers"
  )
  val inventoryDitService: InventoryDitService =
    new InventoryDitServiceImpl(pendingNodesDitImpl, acceptedNodesDitImpl, removedNodesDitImpl)

  val inventoryMapper: InventoryMapper =
    new InventoryMapper(inventoryDitService, pendingNodesDitImpl, acceptedNodesDitImpl, removedNodesDitImpl)

  val repo = new FullInventoryRepositoryImpl(inventoryDitService, inventoryMapper, ldap, 5)

  val readOnlySoftware = new ReadOnlySoftwareDAOImpl(inventoryDitService, roLdap, inventoryMapper)

  val writeOnlySoftware = new WriteOnlySoftwareDAOImpl(acceptedNodesDitImpl, ldap)

  val softwareService = new SoftwareServiceImpl(readOnlySoftware, writeOnlySoftware, acceptedNodesDitImpl)

  val allStatus: Seq[InventoryStatus] = Seq(RemovedInventory, PendingInventory, AcceptedInventory)

  // shortcut to create a machine with the name has ID in the given status
  def machine(name: String, status: InventoryStatus):                                         MachineInventory = MachineInventory(
    MachineUuid(name),
    status,
    PhysicalMachineType,
    Some(s"name for ${name}"),
    None,
    None,
    None,
    None,
    None,
    Nil,
    Nil,
    Nil,
    Nil,
    Nil,
    Nil,
    Nil,
    Nil,
    Nil
  )
  // shortcut to create a node with the name has ID and the given machine, in the
  // given status, has container.
  def node(name: String, status: InventoryStatus, container: (MachineUuid, InventoryStatus)): NodeInventory    = NodeInventory(
    NodeSummary(
      NodeId(name),
      status,
      "root",
      "localhost",
      Linux(
        Debian,
        "foo",
        new Version("1.0"),
        None,
        new Version("1.0")
      ),
      NodeId("root"),
      CertifiedKey
    ),
    machineId = Some(container),
    agents = Seq(
      AgentInfo(
        AgentType.CfeCommunity,
        None,
        Certificate("""-----BEGIN CERTIFICATE-----
                      |MIIFTjCCAzagAwIBAgIUfa0+S+CyJahRzuwNNOFLNQQjIH8wDQYJKoZIhvcNAQEL
                      |BQAwFzEVMBMGCgmSJomT8ixkAQEMBW5vZGU3MB4XDTI0MDMwMTExMDIxM1oXDTM0
                      |MDIyNzExMDIxM1owFzEVMBMGCgmSJomT8ixkAQEMBW5vZGU3MIICIjANBgkqhkiG
                      |9w0BAQEFAAOCAg8AMIICCgKCAgEAsmBUYI1A5vqkOTCW24m/mQhBaRu4WaAUXDAr
                      |ArdIAAMN0KyHhEXP1/X32Flw90E0VjtUH4P+DYLBozXYWhJrUxdWLyn3TRSbv3Kr
                      |pXoCWhMhMp8OK2s/mG+rfiMpTXIwaMhPnBaJFNV3c+bkijGAMHtFjl3+leXJvNZ7
                      |w3bIg4cA3e77kz7EWMyqxOUvvLMyY6wpd03ahe/By+iLgtOkgUwl9hMqMU8tJeaz
                      |NIeUporsHk5rrk8bSf6Mxxdknm43Sk6oflnueNCIUFdd4rS2JLieMugsTh8n/oH+
                      |k29ZyirE3ikhftmZ3vY8GQ3IcIzaXwiAOGnCKcze79zVx5jmOTGSZitGZaU/cZc6
                      |LjzEp+ZDmE8caVIksiA+hIlaZeBXNHB+YRv/gV1Rbt7kS1am+XUZJSfVFf+99YqE
                      |lZ5p0hqJsczkBb2RuMxWxxsWO3pesPUNCuL/yaeggTIp7eK06tQWi1EfXr40ctrf
                      |oimbzMAKNBpRKWruL7652SlF75Usaq+PaPi1TtqYQjLRZmbpr+IR4uMeKnEMIZPw
                      |3dLDKBV6d71XkTAalCmcJU+fgYrRmgz1dEnaZDIXY+f+fYR15hsFpDrZ0avHgkzJ
                      |ca/nT/rKeX7136BttxVSbZaTU9hnmjAvl+v0BF+JUWPQ3VPTcrmyUNAICt8xdVae
                      |uo1tsvkCAwEAAaOBkTCBjjAMBgNVHRMEBTADAQH/MB0GA1UdDgQWBBSRG0uHQRIA
                      |gl91YqzSjaeaw+F7PDBSBgNVHSMESzBJgBSRG0uHQRIAgl91YqzSjaeaw+F7PKEb
                      |pBkwFzEVMBMGCgmSJomT8ixkAQEMBW5vZGU3ghR9rT5L4LIlqFHO7A004Us1BCMg
                      |fzALBgNVHQ8EBAMCArwwDQYJKoZIhvcNAQELBQADggIBABR2vTH/6WlqjaTZ/hQc
                      |B+crqRlFimqCiVTRdX6qfkt0tLX2dK6scHNTBT04ORLjLTAn0KbcOUz3k6i0mIqB
                      |nG5UjqCFEQR+i2V4Hz7aK6+7LBQuwXWRhDhvO5xMn7MxjvP0LGgNf5iiA4r/N22L
                      |EMc1prDESIOGmWdKg9XlfLxd877R2d8/3hXyT82Y2uJHO25b53skj4pbUOWLsGSw
                      |d4FBNnWqM+7Hbg2v9xFvmtfs2G2Inqk4Xjtjnj8qkVk3ft6KzClUIMXGXQ8QqaRp
                      |YPkTJm5g2UBYDiguD/tlz3VmbsNYU6fkap7DKqhttbaseIx1zDwdAv4jtVOWDyjx
                      |Wq4b7pljYiczfRa84X/9v1x05pT3raELy5udY+Pxmnz+hOXOM+jY5bzSKS24b8rS
                      |5Sklmutm0IdflMKd5vNrXd9yPFLu3QzN50ArzHHXczwBLgjaMlZsPAp1wITlqM7+
                      |WCjy3qM5/KgAjH3L24MPTq23o9PokBVh1NH7lesZqgJPgsj+OG7FMQDvKzg7ytrs
                      |QlIDF1c8Ko+9/RrnRVAS8C4GZOqbmMmfJjMp09GBz2d0ixlTusF6m6iwfIVMf/nI
                      |P/V0D7STRiV62cfnZ3e0w8kIeZwWAgXI7RMHJU3skLyurUu8yxkp635IQyzQsW2A
                      |Yo7t7O7fxjqD9yVI2QfkERZ7
                      |-----END CERTIFICATE-----
                      |""".stripMargin),
        Set()
      )
    )
  )

  def full(n: NodeInventory, m: MachineInventory): FullInventory = FullInventory(n, Some(m))

  // just to validate that things are set up
  "The in memory LDAP directory" should {

    "correctly load and read back test-entries" in {

      val numEntries = bootstrapLDIFs.foldLeft(0) {
        case (x, path) =>
          val reader = new com.unboundid.ldif.LDIFReader(path)
          var i      = 0
          while (reader.readEntry != null) i += 1
          i + x
      }

      ldap.server.countEntries === numEntries
    }

    "can add case-ignore-match equals localaccounts" in {
      val dn = "nodeId=node0,ou=Nodes,ou=Accepted Inventories,ou=Inventories,cn=rudder-configuration"
      // it throws exception if not success
      ldap.server.modify(dn, new Modification(ModificationType.ADD, "localAccountName", "TEST", "test"))

      try {
        ldap.server.assertValueExists(dn, "localAccountName", java.util.Arrays.asList("TEST", "test"))
        ok("success")
      } catch {
        case ae: AssertionError => ko(ae.getMessage)
      }
    }
  }

  "Saving, finding and moving machine around" should {

    "correctly save and find a machine based on it's id (when only on one place)" in {
      forall(allStatus) { status =>
        val m = machine("machine in " + status.name, status)
        repo.save(m).testRun

        val found = repo.getMachine(m.id).testRunGet

        (m === found) and {
          repo.delete(m.id).testRun
          val x = repo.getMachine(m.id).testRun
          x must beEqualTo(Right(None))
          ok
        }
      }
    }

    "correctly find the machine of top priority when on several branches" in {
      allStatus.foreach(status => repo.save(machine("m1", status)).testRun)

      val toFound = machine("m1", AcceptedInventory)
      val found   = repo.getMachine(toFound.id).testRunGet

      toFound === found

    }

    "correctly moved the machine from pending to accepted, then to removed" in {
      val m = machine("movingMachine", PendingInventory)

      repo.save(m).testRun

      (
        repo.move(m.id, AcceptedInventory).isOK
        and (repo.getMachine(m.id).testRunGet must beEqualTo(m.copy(status = AcceptedInventory)))
        and repo.move(m.id, RemovedInventory).isOK
        and (repo.getMachine(m.id).testRunGet must beEqualTo(m.copy(status = RemovedInventory)))
      )
    }

    ", when asked to move machine in removed inventory and a machine with the same id exists there, keep the one in removed and delete the one in accepted" in {
      val m1 = machine("keepingMachine", AcceptedInventory)
      val m2 = m1.copy(status = RemovedInventory, name = Some("modified"))

      (
        (repo.save(m1).isOK)
        and (repo.save(m2).isOK)
        and (repo.move(m1.id, RemovedInventory).isOK)
        and {
          val dn = inventoryDitService.getDit(AcceptedInventory).MACHINES.MACHINE.dn(m1.id)
          repo.getMachine(m1.id).testRunGet === m2 and ldap.server.entryExists(dn.toString) === false
        }
      )
    }
  }

  "Saving, finding and moving node" should {

    "find node for machine, whatever the presence or status of the machine" in {

      val mid = MachineUuid("foo")

      val n1 = node("acceptedNode", AcceptedInventory, (mid, AcceptedInventory))
      val n2 = node("pendingNode", PendingInventory, (mid, AcceptedInventory))
      val n3 = node("removedNode", RemovedInventory, (mid, AcceptedInventory))

      def toDN(n: NodeInventory) = inventoryDitService.getDit(n.main.status).NODES.NODE.dn(n.main.id.value)

      (
        repo.save(FullInventory(n1, None)).isOK
        and repo.save(FullInventory(n2, None)).isOK
        and repo.save(FullInventory(n3, None)).isOK
        and {
          val res = (for {
            con   <- ldap
            nodes <- repo.getNodesForMachine(con, mid)
          } yield {
            nodes.map { case (k, v) => (k, v.map(_.dn)) }
          })
          res.testRun.forceGet must havePairs(
            AcceptedInventory -> Set(toDN(n1)),
            PendingInventory  -> Set(toDN(n2)),
            RemovedInventory  -> Set(toDN(n3))
          )
        }
      )
    }

    "find back the machine after a move" in {
      val m = machine("findBackMachine", PendingInventory)
      val n = node("findBackNode", PendingInventory, (m.id, m.status))

      (
        repo.save(full(n, m)).isOK
        and repo.move(n.main.id, PendingInventory, AcceptedInventory).isOK
        and {
          val FullInventory(node, machine) = repo.get(n.main.id, AcceptedInventory).testRunGet

          (
            machine === Some(m.copy(status = AcceptedInventory)) and
            node === n
              .copyWithMain(main => main.copy(status = AcceptedInventory))
              .copy(machineId = Some((m.id, AcceptedInventory)))
          )
        }
      )
    }

    "accept to have a machine in a different status than the node" in {
      val m = machine("differentMachine", AcceptedInventory)
      val n = node("differentNode", PendingInventory, (m.id, AcceptedInventory))
      (
        repo.save(full(n, m)).isOK
        and {
          val FullInventory(node, machine) = repo.get(n.main.id, PendingInventory).testRunGet

          (
            node === n
            and machine === Some(m)
          )
        }
      )
    }

    "not find a machine if the container information has a bad status" in {
      val m = machine("invisibleMachine", PendingInventory)
      val n = node("invisibleNode", PendingInventory, (m.id, AcceptedInventory))
      (
        repo.save(full(n, m)).isOK
        and {
          val FullInventory(node, machine) = repo.get(n.main.id, PendingInventory).testRunGet

          (
            node === n
            and machine === None
          )
        }
      )
    }

    ", when moving from pending to accepted, moved back a machine from removed to accepted and correct other node container" in {
      val m  = machine("harcoreMachine", RemovedInventory)
      val n0 = node("h-n0", PendingInventory, (m.id, PendingInventory))
      val n1 = node("h-n1", PendingInventory, (m.id, PendingInventory))
      val n2 = node("h-n2", AcceptedInventory, (m.id, AcceptedInventory))
      val n3 = node("h-n3", RemovedInventory, (m.id, RemovedInventory))

      (
        repo.save(m).isOK and repo.save(FullInventory(n0, None)).isOK and repo.save(FullInventory(n1, None)).isOK and
        repo.save(FullInventory(n2, None)).isOK and repo.save(FullInventory(n3, None)).isOK
        and repo.move(n0.main.id, PendingInventory, AcceptedInventory).isOK
        and {
          val FullInventory(node0, m0) = repo.get(n0.main.id, AcceptedInventory).testRunGet
          val FullInventory(node1, m1) = repo.get(n1.main.id, PendingInventory).testRunGet
          val FullInventory(node2, m2) = repo.get(n2.main.id, AcceptedInventory).testRunGet
          val FullInventory(node3, m3) = repo.get(n3.main.id, RemovedInventory).testRunGet

          // expected machine value
          val machine = m.copy(status = AcceptedInventory)
          val ms      = Some((machine.id, machine.status))

          (
            m0 === Some(machine) and m1 === Some(machine) and m2 === Some(machine) and m3 === Some(machine) and
            node0 === n0
              .copyWithMain(main => main.copy(status = AcceptedInventory))
              .copy(machineId = Some((m.id, AcceptedInventory)))
            and node1 === n1.copy(machineId = ms)
            and node2 === n2.copy(machineId = ms)
            and node3 === n3.copy(machineId = ms)
          )
        }
      )
    }

  }

  "Trying to add specific Windows" should {

    "Allow to save and read it back" in {
      val nodeId = NodeId("windows-2012")

      val node = NodeInventory(
        NodeSummary(
          nodeId,
          AcceptedInventory,
          "administrator",
          "localhost",
          Windows(
            Windows2012,
            "foo",
            new Version("1.0"),
            None,
            new Version("1.0")
          ),
          NodeId("root"),
          UndefinedKey
        ),
        machineId = None
      )

      repo.save(FullInventory(node, None)).isOK and {
        val FullInventory(n, m) = repo.get(nodeId, AcceptedInventory).testRunGet
        n === node
      }
    }

  }

  "Softwares" should {
    "Find 3 software referenced by nodes with the repository" in {
      val softwares = readOnlySoftware.getSoftwaresForAllNodes().testRun
      softwares.map(_.size) must beRight(3)
    }

    "Find 4 software in ou=software with the repository" in {
      val softwares = readOnlySoftware.getAllSoftwareIds().testRun
      softwares.map(_.size) must beRight(4)
    }

    "Purge one unreferenced software with the SoftwareService" in {
      val purgedSoftwares = softwareService.deleteUnreferencedSoftware().testRun
      purgedSoftwares must beRight(1)
    }
  }

  "Software updates" should {

    "be correctly saved for a node" in {
      val dn  = "nodeId=node0,ou=Nodes,ou=Accepted Inventories,ou=Inventories,cn=rudder-configuration"
      val inv = repo.get(NodeId("node0")).testRunGet
      val su1 = inv.node.softwareUpdates
      val d0  = "2022-01-01T00:00:00Z"
      val dt0 = JsonSerializers.parseSoftwareUpdateInstant(d0).toOption
      val id0 = "RHSA-2020-4566"
      val id1 = "CVE-2021-4034"

      val updates = List(
        SoftwareUpdate(
          "app1",
          Some("2.15.6~RC1"),
          Some("x86_64"),
          Some("yum"),
          SoftwareUpdateKind.Defect,
          None,
          Some("Some explanation"),
          Some(SoftwareUpdateSeverity.Critical),
          dt0,
          Some(List(id0, id1))
        ),
        SoftwareUpdate(
          "app2",
          Some("1-23-RELEASE-1"),
          Some("x86_64"),
          Some("apt"),
          SoftwareUpdateKind.None,
          Some("default-repo"),
          None,
          None,
          None,
          None
        ), // we can have several time the same app

        SoftwareUpdate(
          "app2",
          Some("1-24-RELEASE-64"),
          Some("x86_64"),
          Some("apt"),
          SoftwareUpdateKind.Security,
          Some("security-backports"),
          None,
          Some(SoftwareUpdateSeverity.Other("backport")),
          None,
          Some(List(id1))
        )
      )

      val ldapValues = List(
        s"""{"name":"app1","version":"2.15.6~RC1","arch":"x86_64","from":"yum","kind":"defect","description":"Some explanation","severity":"critical","date":"${d0}","ids":["${id0}","${id1}"]}""",
        s"""{"name":"app2","version":"1-23-RELEASE-1","arch":"x86_64","from":"apt","kind":"none","source":"default-repo"}""",
        s"""{"name":"app2","version":"1-24-RELEASE-64","arch":"x86_64","from":"apt","kind":"security","source":"security-backports","severity":"backport","ids":["${id1}"]}"""
      )

      (su1 === Nil) and (dt0.isEmpty must beFalse) and
      repo.save(inv.modify(_.node.softwareUpdates).setTo(updates)).isOK and
      (ldap.server.getEntry(dn).getAttribute("softwareUpdate").getValues.toList must containTheSameElementsAs(ldapValues))
    }
  }

  step {
    ldap.close
    success
  }

}
