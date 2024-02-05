/*
 *************************************************************************************
 * Copyright 2023 Normation SAS
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

import com.normation.errors._
import com.normation.inventory.domain._
import com.normation.inventory.services.core.FullInventoryRepository
import com.normation.rudder.tenants.DefaultTenantService
import com.normation.zio._
import com.normation.zio.ZioRuntime
import com.softwaremill.quicklens._
import org.joda.time.DateTime
import org.junit.runner._
import org.specs2.mutable._
import org.specs2.runner._
import zio._
import zio.concurrent.ReentrantLock
import zio.syntax._

final case class SystemError(cause: Throwable) extends RudderError {
  def msg = "Error in test"
}

/*
 * A class to trace semantic changes between full ldap repo with ldap
 * and with fact backend (see com/normation/inventory/ldap/core/TestInventory.scala
 * for original results)
 */
//@silent("a type was inferred to be `\\w+`; this may indicate a programming error.")
@RunWith(classOf[JUnitRunner])
class TestFullInventoryRepoProxy extends Specification {

  implicit class RunThing[E, T](thing: ZIO[Any, E, T])      {
    def testRun = ZioRuntime.unsafeRun(thing.either)
  }
  implicit class RunOptThing[A](thing: IOResult[Option[A]]) {
    def testRunGet: A = ZioRuntime.unsafeRun(thing.either) match {
      case Right(Some(a)) => a
      case Right(None)    => throw new RuntimeException(s"Error in test: found None, expected Some")
      case Left(err)      => throw new RuntimeException(s"Error in test: ${err}")
    }
  }

  implicit class TestIsOK[E, T](thing: ZIO[Any, E, T]) {
    def isOK = thing.testRun must beRight
  }

  implicit class ForceGetE[E, A](opt: Either[E, A]) {
    def forceGet: A = opt match {
      case Right(x)  => x
      case Left(err) => throw new Exception(s"error in Test: ${err}")
    }
  }

  // TODO WARNING POC: this can't work on a machine with lots of node
  val callbackLog = Ref.make(Chunk.empty[NodeFactChangeEvent]).runNow

  def resetLog = callbackLog.set(Chunk.empty).runNow

  def getLogName = callbackLog.get.map(_.map(_.name)).runNow

  val pendingRef  = (Ref.make(Map[NodeId, CoreNodeFact]())).runNow
  val acceptedRef = (Ref.make(Map[NodeId, CoreNodeFact]())).runNow

  def resetStorage = (for {
    _ <- pendingRef.set(Map())
    _ <- acceptedRef.set(Map())
  } yield ()).runNow

  object noopNodeBySoftwareName extends GetNodesbySofwareName {
    override def apply(softName: String): IOResult[List[(NodeId, Software)]] = {
      Nil.succeed
    }
  }

  val factRepo = {
    for {
      callbacks <- Ref.make(Chunk.empty[NodeFactChangeEventCallback])
      lock      <- ReentrantLock.make()
      tenants   <- DefaultTenantService.make(Nil)
      r          = new CoreNodeFactRepository(NoopFactStorage, noopNodeBySoftwareName, tenants, pendingRef, acceptedRef, callbacks, lock)
      _         <- r.registerChangeCallbackAction(CoreNodeFactChangeEventCallback("trail", e => callbackLog.update(_.appended(e.event))))
      //      _         <- r.registerChangeCallbackAction(new NodeFactChangeEventCallback("log", e => effectUioUnit(println(s"**** ${e.name}"))))
    } yield {
      r
    }
  }.runNow
  // needed because the in memory LDAP server is not used with connection pool
  sequential

  val allStatus = Seq(RemovedInventory, PendingInventory, AcceptedInventory)

  // shortcut to create a machine with the name has ID in the given status
  def machine(name: String, status: InventoryStatus)                                         = MachineInventory(
    MachineUuid(name),
    status,
    PhysicalMachineType,
    None,
    None,
    Some(DateTime.parse("2023-01-11T10:20:30.000Z")), // now, node and inventory always have the same
    Some(DateTime.parse("2023-02-22T15:25:35.000Z")), // inventory and received date
    Some(Manufacturer("manufacturer")),
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
  def node(name: String, status: InventoryStatus, container: (MachineUuid, InventoryStatus)) = NodeInventory(
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
    inventoryDate = Some(DateTime.parse("2023-01-11T10:20:30.000Z")),
    receiveDate = Some(DateTime.parse("2023-02-22T15:25:35.000Z")),
    agents = Seq(NodeFact.defaultRudderAgent("root").toAgentInfo), // always present now
    machineId = Some(container)
  )

  def full(n: NodeInventory, m: MachineInventory) = FullInventory(n, Some(m))

  val repo: FullInventoryRepository[Unit] = new MockNodeFactFullInventoryRepositoryProxy(factRepo)

  "Saving, finding and moving node" should {

    "Save node for machine, whatever the presence or status of the machine" in {
      resetStorage
      val mid = MachineUuid("foo")

      val n1 = node("acceptedNode", AcceptedInventory, (mid, AcceptedInventory))
      val n2 = node("pendingNode", PendingInventory, (mid, AcceptedInventory))
      val n3 = node("removedNode", RemovedInventory, (mid, AcceptedInventory))

      (
        repo.save(FullInventory(n1, None)).isOK
        and repo.save(FullInventory(n2, None)).isOK
        and repo.save(FullInventory(n3, None)).isOK
      )
    }

    "find back the machine after a move" in {
      resetStorage
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
              .modify(_.main.status)
              .setTo(AcceptedInventory)
              .modify(_.machineId)
              .setTo(Some((m.id, AcceptedInventory)))
          )
        }
      )
    }

    "don't accept to have a machine in a different status than the node" in {
      resetStorage
      val m = machine("differentMachine", AcceptedInventory)
      val n = node("differentNode", PendingInventory, (m.id, AcceptedInventory))
      (
        repo.save(full(n, m)).isOK
        and {
          val FullInventory(node, machine) = repo.get(n.main.id, PendingInventory).testRunGet
          (
            (node === n.modify(_.machineId).setTo(Some((m.id, PendingInventory)))) and
            (machine === Some(m.modify(_.status).setTo(PendingInventory)))
          )
        }
      )
    }

    "use the given machine in full inventory whatever the ID given in node" in {
      resetStorage
      val m = machine("invisibleMachine", PendingInventory)
      val n = node("invisibleNode", PendingInventory, (MachineUuid("something else"), AcceptedInventory))
      (
        repo.save(full(n, m)).isOK
        and {
          val FullInventory(node, machine) = repo.get(n.main.id, PendingInventory).testRunGet

          (
            node === n.modify(_.machineId).setTo(Some((m.id, PendingInventory)))
            and machine === Some(m.modify(_.status).setTo(PendingInventory))
          )
        }
      )
    }

    "machine, even with the same 'id', are bound to their node" in {
      resetStorage
      val m  = machine("hardcoreMachine", RemovedInventory)
      val n0 = node("h-n0", PendingInventory, (m.id, PendingInventory))
      val n1 = node("h-n1", PendingInventory, (m.id, PendingInventory))
      val n2 = node("h-n2", AcceptedInventory, (m.id, AcceptedInventory))
      val n3 = node("h-n3", RemovedInventory, (m.id, RemovedInventory))

      (
        repo.save(FullInventory(n0, Some(m))).isOK and repo.save(FullInventory(n1, Some(m))).isOK and
        repo.save(FullInventory(n2, Some(m))).isOK and repo.save(FullInventory(n3, Some(m))).isOK
        and repo.move(n0.main.id, PendingInventory, AcceptedInventory).isOK
        and {
          val FullInventory(node0, m0) = repo.get(n0.main.id, AcceptedInventory).testRunGet
          val FullInventory(node1, m1) = repo.get(n1.main.id, PendingInventory).testRunGet
          val FullInventory(node2, m2) = repo.get(n2.main.id, AcceptedInventory).testRunGet
          val node3                    = repo.get(n3.main.id, RemovedInventory).either.runNow

          // expected machine value
          val n0now                     = n0.modify(_.main.status).setTo(AcceptedInventory)
          // update node's machine info to what is normalized
          def updated(n: NodeInventory) = {
            (
              n.modify(_.machineId).setTo(Some((m.id, n.main.status))),
              m.modify(_.status).setTo(n.main.status)
            )
          }

          val (n0_, m0_) = updated(n0now)
          val (n1_, m1_) = updated(n1)
          val (n2_, m2_) = updated(n2)

          (
            m0 === Some(m0_) and m1 === Some(m1_) and m2 === Some(m2_) and
            node0 === n0_
            and node1 === n1_
            and node2 === n2_
            and (node3 isLeft) // no move to delete
          )
        }
      )
    }

  }

  "Trying to add specific Windows" should {

    "Allow to save and read it back" in {
      resetStorage
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
        inventoryDate = Some(DateTime.parse("2023-01-11T10:20:30.000Z")),
        receiveDate = Some(DateTime.parse("2023-02-22T15:25:35.000Z")),
        agents = Seq(NodeFact.defaultRudderAgent("administrator").toAgentInfo), // always present now
        machineId = None
      )

      repo.save(FullInventory(node, None)).isOK and {
        val FullInventory(n, m) = repo.get(nodeId, AcceptedInventory).testRunGet
        // here since we don't have a machine, one is generated with the uuid pattern "machine-for-${nodeId}"
        n === node.modify(_.machineId).setTo(Some((MachineUuid("machine-for-windows-2012"), AcceptedInventory)))
      }
    }

  }

  "Software updates" should {

    "are correctly serialized" in {
      val d0  = "2022-01-01T00:00:00Z"
      val dt0 = JsonSerializers.parseSoftwareUpdateDateTime(d0).toOption
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

      val jsonString = List(
        s"""{"name":"app1","version":"2.15.6~RC1","arch":"x86_64","from":"yum","kind":"defect","description":"Some explanation","severity":"critical","date":"${d0}","ids":["${id0}","${id1}"]}""",
        s"""{"name":"app2","version":"1-23-RELEASE-1","arch":"x86_64","from":"apt","kind":"none","source":"default-repo"}""",
        s"""{"name":"app2","version":"1-24-RELEASE-64","arch":"x86_64","from":"apt","kind":"security","source":"security-backports","severity":"backport","ids":["${id1}"]}"""
      ).mkString("[", ",", "]")

      import zio.json._
      import JsonSerializers.implicits._

      updates.toJson === jsonString
    }
  }

  step {
    success
  }

}
