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

import better.files._
import com.normation.errors._
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.inventory.domain._
import com.normation.inventory.ldap.core.InventoryDit
import com.normation.inventory.ldap.core.ReadOnlySoftwareDAOImpl
import com.normation.rudder.domain.Constants
import com.normation.rudder.domain.nodes.MachineInfo
import com.normation.utils.DateFormaterService
import com.normation.zio._
import com.normation.zio.ZioRuntime
import com.softwaremill.quicklens._
import com.unboundid.ldap.sdk.SearchScope
import java.security.Security
import org.apache.commons.io.FileUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.joda.time.DateTime
import org.junit.runner._
import org.specs2.mutable._
import org.specs2.runner._
import org.specs2.specification.BeforeAfterAll
import scala.annotation.nowarn
import zio._

/**
 *
 * Test the processing of new inventory:
 * - check that new, never seen nodes end into pending
 * - check that new, already pending nodes update pending
 * - check that accepted nodes are updated
 * - check that signature things work.
 *
 * That test does not check for the file observer, only save logic.
 */
@RunWith(classOf[JUnitRunner])
@nowarn("msg=a type was inferred to be `\\w+`; this may indicate a programming error.")
class TestCoreNodeFactInventory extends Specification with BeforeAfterAll {

  // load bouncyCastle
  Security.addProvider(new BouncyCastleProvider())

  def nodeExists(id: String, dit: InventoryDit): Boolean = {
    MockLdapFactStorage.ldap.server.entryExists(dit.NODES.NODE.dn(id).toString)
  }

  def nodeAsString(id: String, dit: InventoryDit): String = {
    val sb = new java.lang.StringBuilder()
    MockLdapFactStorage.ldap.server.getEntry(dit.NODES.NODE.dn(id).toString).toString(sb)
    sb.toString()
  }

  // a fact storage that keeps a trace of all call to it, so that we can debug/set expectation
  object factStorage extends NodeFactStorage {
    val backend   = MockLdapFactStorage.nodeFactStorage
    val callStack = Ref.make(List.empty[String]).runNow

    def clearCallStack: Unit = callStack.set(Nil).runNow

    override def save(nodeFact: NodeFact)(implicit attrs: SelectFacts = SelectFacts.all): IOResult[StorageChangeEventSave] = {
      for {
        _ <- callStack.update(s"save ${nodeFact.id}" :: _)
        r <- backend.save(nodeFact)
      } yield r
    }

    override def changeStatus(nodeId: NodeId, status: InventoryStatus): IOResult[StorageChangeEventStatus] = {
      for {
        _ <- callStack.update(s"changeStatus ${nodeId} to ${status.name}" :: _)
        r <- backend.changeStatus(nodeId, status)
      } yield r
    }

    override def delete(nodeId: NodeId)(implicit attrs: SelectFacts): IOResult[StorageChangeEventDelete] = {
      for {
        _ <- callStack.update(s"delete ${nodeId}" :: _)
        r <- backend.delete(nodeId)
      } yield r
    }

    override def getPending(nodeId: NodeId)(implicit attrs: SelectFacts): IOResult[Option[NodeFact]] = {
      for {
        _ <- callStack.update(s"getPending ${nodeId}" :: _)
        r <- backend.getPending(nodeId)
      } yield r
    }

    override def getAccepted(nodeId: NodeId)(implicit attrs: SelectFacts): IOResult[Option[NodeFact]] = {
      for {
        _ <- callStack.update(s"getAccepted ${nodeId}" :: _)
        r <- backend.getAccepted(nodeId)
      } yield r
    }

    override def getAllPending()(implicit attrs: SelectFacts): IOStream[NodeFact] = {
      callStack.update(s"getAllPending" :: _).runNow // gosh, it's for test only
      backend.getAllPending()
    }

    override def getAllAccepted()(implicit attrs: SelectFacts): IOStream[NodeFact] = {
      callStack.update(s"getAllAccepted" :: _).runNow // gosh, it's for test only
      backend.getAllAccepted()
    }
  }

  implicit class RunThing[E, T](thing: ZIO[Any, E, T]) {
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

  implicit def stringToNodeId(id: String): NodeId = NodeId(id)

  val basePath = s"/tmp/test-rudder-nodefact/${DateFormaterService.gitTagFormat.print(DateTime.now())}"

  override def beforeAll(): Unit = {}

  override def afterAll(): Unit = {
    if (java.lang.System.getProperty("tests.clean.tmp") != "false") {
      FileUtils.deleteDirectory(File(basePath).toJava)
    }
  }

  val callbackLog = Ref.make(Chunk.empty[NodeFactChangeEvent]).runNow
  def resetLog    = callbackLog.set(Chunk.empty).runNow
  def getLogName  = callbackLog.get.map(_.map(_.name)).runNow

  val nodeBySoftwareName = new SoftDaoGetNodesbySofwareName(
    new ReadOnlySoftwareDAOImpl(
      MockLdapFactStorage.inventoryDitService,
      MockLdapFactStorage.ldapRo,
      MockLdapFactStorage.inventoryMapper
    )
  )

  val factRepo = {
    val trailCB = CoreNodeFactChangeEventCallback("trail", e => callbackLog.update(_.appended(e.event)))
//   val logCB = CoreNodeFactChangeEventCallback("log", e => effectUioUnit(println(s"**** ${e.name}"))))
    CoreNodeFactRepository.make(factStorage, nodeBySoftwareName, Chunk(trailCB)).runNow
  }

//  org.slf4j.LoggerFactory
//    .getLogger("inventory-processing")
//    .asInstanceOf[ch.qos.logback.classic.Logger]
//    .setLevel(ch.qos.logback.classic.Level.TRACE)

  sequential

  // node7 has the following inventory info:
  // - one software
  // - one mount point
  // - machine2 (physical)
  // - a bios
  val node7id   = NodeId("node7")
  val machineId = MachineUuid("machine2")

  implicit val cc: ChangeContext = ChangeContext.newForRudder()

  // things that are empty on node7 because not defined
  def node7UndefinedElements(n: NodeFact) = List(
    Chunk.fromIterable(n.swap),
    n.accounts,
    n.controllers,
    n.environmentVariables,
    n.inputs,
    n.localUsers,
    n.localUsers,
    n.logicalVolumes,
    n.memories,
    n.networks,
    n.physicalVolumes,
    n.ports,
    n.processes,
    n.processors,
    n.slots,
    n.softwareUpdate,
    n.sounds,
    n.storages,
    n.videos,
    n.vms
  )

  implicit val testChangeContext: ChangeContext =
    ChangeContext(ModificationId("test-mod-id"), EventActor("test"), DateTime.now(), None, None, QueryContext.testQC.nodePerms)

  "query action" should {

    "allow to get the whole node fact, included inventory and software" in {

      implicit val attrs = SelectFacts.all

      factStorage.clearCallStack
      val node = factRepo.slowGet(node7id).notOptional("node7 must be here").runNow

      (factStorage.callStack.get.runNow.size === 1) and
      (node.bios.size === 1) and
      (
        node.bios.head === Bios(
          "bios1",
          None,
          Some(new Version("6.00")),
          Some(SoftwareEditor("Phoenix Technologies LTD"))
        )
      ) and
      (node.machine === MachineInfo(machineId, PhysicalMachineType, None, None)) and
      (node.fileSystems.size === 1) and
      (
        node.fileSystems.head === FileSystem(
          "/",
          Some("ext3"),
          None,
          None,
          Some(MemorySize(10L)),
          Some(MemorySize(803838361699L))
        )
      ) and
      (node.software.size === 1) and
      (node.software.head === SoftwareFact("Software 0", new Version("1.0.0"))) and
      (node7UndefinedElements(node) must contain((x: Chunk[_]) => x must beEmpty).foreach)

    }

    "allow to get core node fact without touching LDAP" in {
      factStorage.clearCallStack
      val node7 = factRepo.get(node7id).notOptional("node7 must be there").runNow

      (node7.fqdn === "node7.normation.com") and
      (factStorage.callStack.get.runNow match {
        case Nil => ok
        case l   => ko(s"Storage was call: ${l.mkString("\n", "\n", "")}")
      })
    }

    "allow to get software without the other parts of inventory" in { // how to chek that we don't retrieve cpu filesystems etc?
      factStorage.clearCallStack
      implicit val attrs = SelectFacts.softwareOnly
      val node           = factRepo.slowGet(node7id).notOptional("node7 must be here").runNow

      (factStorage.callStack.get.runNow.size === 1) and
      (node.bios.size === 0) and
      (node.machine === MachineInfo(machineId, PhysicalMachineType, None, None)) and // we always get that
      (node.fileSystems.size === 0) and
      (node.software.size === 1) and
      (node.software.head === SoftwareFact("Software 0", new Version("1.0.0"))) and
      (node7UndefinedElements(node) must contain((x: Chunk[_]) => x must beEmpty).foreach)
    }

    // for now, we can't selectively choose what sub element of inventory we retrieve. It could be done if needed
    // for better perf. The use case could be: api requesting only CPU on all nodes or that kind of things.
    "allow to get only a sub-set of inventory without fetching software" in {
      factStorage.clearCallStack
      implicit val attrs = SelectFacts.none.modify(_.bios).using(_.toRetrieve)
      val node           = factRepo.slowGet(node7id).notOptional("node7 must be here").runNow

      (factStorage.callStack.get.runNow.size === 1) and
      (node.bios.size === 1) and
      (
        node.bios.head === Bios(
          "bios1",
          None,
          Some(new Version("6.00")),
          Some(SoftwareEditor("Phoenix Technologies LTD"))
        )
      ) and
      (node.fileSystems.size === 0) and
      (node.software.size === 0) and
      (node7UndefinedElements(node) must contain((x: Chunk[_]) => x must beEmpty).foreach)
    }
  }

  "security tag" should {
    val all = Set("root", "node0", "node1", "node2", "node3", "node4", "node5", "node6", "node7")

    /*
     * node0: zoneA
     * node1: zoneA, zoneB
     * node2: zoneB
     * node3: zoneC
     * other: no zone, always present BUT for people with none right
     */

    "allow to filter all nodes with no access" in {
      val nodes = factRepo
        .getAll()(QueryContext.testQC.modify(_.nodePerms).setTo(NodeSecurityContext.None), SelectNodeStatus.Accepted)
        .runCollect
        .runNow

      nodes must beEmpty
    }

    "allow to get only nodes with one security tag" in {
      val nodes = factRepo
        .getAll()(
          QueryContext.testQC.modify(_.nodePerms).setTo(NodeSecurityContext.ByTags(Chunk("zoneA"))),
          SelectNodeStatus.Accepted
        )
        .runCollect
        .runNow

      nodes.map(_.id.value) must containTheSameElementsAs((all -- List("node2", "node3")).toSeq)
    }

    "have cumulative rights" in {
      val nodes = factRepo
        .getAll()(
          QueryContext.testQC.modify(_.nodePerms).setTo(NodeSecurityContext.ByTags(Chunk("zoneA", "zoneB"))),
          SelectNodeStatus.Accepted
        )
        .runCollect
        .runNow

      nodes.map(_.id.value) must containTheSameElementsAs((all -- List("node3")).toSeq)
    }
  }

  "basic update action" should {
    "we can save a whole inventory and changing everything in storage, included software" >> {
      factStorage.clearCallStack
      val node = factRepo
        .slowGet(node7id)(QueryContext.testQC, SelectNodeStatus.Accepted, SelectFacts.all)
        .notOptional("node7 must be here")
        .runNow

      val updated = node
        .modify(_.software)
        .using(_.appended(SoftwareFact("s2", new Version("1.2"))))
        .modify(_.environmentVariables)
        .using(_.appended(("envVAR", "envVALUE")))
        .modify(_.networks)
        .using(_.appended(Network("eth0")))
        .modify(_.slots)
        .using(_.appended(Slot("slot0")))

      factRepo.save(updated)(testChangeContext, SelectFacts.all).runNow

      // check that ldap entries where modified
      (MockLdapFactStorage.testServer
        .getEntry("nodeId=node7,ou=Nodes,ou=Accepted Inventories,ou=Inventories,cn=rudder-configuration")
        .getAttributeValue("environmentVariable") must beEqualTo("""{"name":"envVAR","value":"envVALUE"}""")) and
      (MockLdapFactStorage.testServer.entryExists(
        "networkInterface=eth0,nodeId=node7,ou=Nodes,ou=Accepted Inventories,ou=Inventories,cn=rudder-configuration"
      ) must beTrue) and
      (MockLdapFactStorage.testServer.entryExists(
        "portName=slot0,machineId=machine2,ou=Machines,ou=Accepted Inventories,ou=Inventories,cn=rudder-configuration"
      ) must beTrue) and
      (
        MockLdapFactStorage.testServer
          .search("ou=Software,ou=Inventories,cn=rudder-configuration", SearchScope.ONE, "(cn=s2)")
          .getSearchEntries
          .size()
        must beEqualTo(1)
      )
    }

    "we can change only one inventory aspect without touching others even if they are not the same in our business object" >> {
      factStorage.clearCallStack
      val node = factRepo
        .slowGet(node7id)(QueryContext.testQC, SelectNodeStatus.Accepted, SelectFacts.all)
        .notOptional("node7 must be here")
        .runNow

      val updated = node
        .modify(_.software)
        .using(_.appended(SoftwareFact("s3", new Version("1.3"))))
        .modify(_.environmentVariables)
        .using(_.appended(("bad", "bad")))
        .modify(_.networks)
        .using(_.appended(Network("eth1")))
        .modify(_.slots)
        .using(_.appended(Slot("slot1")))

      factRepo.save(updated)(testChangeContext, SelectFacts.none.modify(_.networks).using(_.toRetrieve)).runNow

      // check that ONLY network ldap entry was modified
      (MockLdapFactStorage.testServer
        .getEntry("nodeId=node7,ou=Nodes,ou=Accepted Inventories,ou=Inventories,cn=rudder-configuration")
        .getAttribute("environmentVariable")
        .hasValue("""{"name":"bad","value":"bad"}""") must beFalse) and
      (
        MockLdapFactStorage.testServer.entryExists(
          "networkInterface=eth1,nodeId=node7,ou=Nodes,ou=Accepted Inventories,ou=Inventories,cn=rudder-configuration"
        ) must beTrue
      ) and
      (
        MockLdapFactStorage.testServer.entryExists(
          "portName=slot1,machineId=machine2,ou=Machines,ou=Accepted Inventories,ou=Inventories,cn=rudder-configuration"
        ) must beFalse
      ) and
      (
        MockLdapFactStorage.testServer
          .search("ou=Software,ou=Inventories,cn=rudder-configuration", SearchScope.ONE, "(cn=s3)")
          .getSearchEntries
          .size()
        must beEqualTo(0)
      )
    }

    "root status can not be modified" >> {
      val res = (for {
        r <- factRepo.get(Constants.ROOT_POLICY_SERVER_ID).notOptional("root must be here")
        _ <- factRepo.save(NodeFact.fromMinimal(r.modify(_.rudderSettings.status).setTo(PendingInventory)))(
               testChangeContext,
               SelectFacts.none
             )
      } yield ()).either.runNow

      res must beLeft
    }
  }
}
