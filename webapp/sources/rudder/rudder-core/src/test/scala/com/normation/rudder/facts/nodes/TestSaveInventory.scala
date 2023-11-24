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
import com.normation.inventory.domain._
import com.normation.inventory.ldap.core.InventoryDit
import com.normation.inventory.ldap.provisioning._
import com.normation.inventory.provisioning.fusion.FusionInventoryParser
import com.normation.inventory.provisioning.fusion.PreInventoryParserCheckConsistency
import com.normation.inventory.services.provisioning.DefaultInventoryParser
import com.normation.inventory.services.provisioning.InventoryDigestServiceV1
import com.normation.inventory.services.provisioning.InventoryParser
import com.normation.rudder.batch.GitGC
import com.normation.rudder.domain.nodes.NodeState
import com.normation.rudder.domain.policies.PolicyMode
import com.normation.rudder.domain.properties.NodeProperty
import com.normation.rudder.git.GitRepositoryProviderImpl
import com.normation.rudder.inventory.DefaultProcessInventoryService
import com.normation.rudder.inventory.InventoryFailedHook
import com.normation.rudder.inventory.InventoryMover
import com.normation.rudder.inventory.InventoryPair
import com.normation.rudder.inventory.InventoryProcessor
import com.normation.rudder.inventory.InventoryProcessStatus.Saved
import com.normation.utils.DateFormaterService
import com.normation.utils.StringUuidGeneratorImpl
import com.normation.zio._
import com.normation.zio.ZioRuntime
import com.softwaremill.quicklens._
import com.typesafe.config.ConfigValueFactory
import cron4s.Cron
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
import zio.concurrent.ReentrantLock
import zio.syntax._

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
class TestSaveInventoryGit extends TestSaveInventory {

  val GIT_PENDING = basePath + "/fact-repo/nodes/pending"

  def pendingNodeGitFile(id: String) = File(GIT_PENDING + "/" + id + ".json")

  val GIT_ACCEPTED = basePath + "/fact-repo/nodes/accepted"

  def acceptedNodeGitFile(id: String) = File(GIT_ACCEPTED + "/" + id + ".json")

  override def checkPendingNodeExists(id: String):  Boolean = pendingNodeGitFile(id).exists
  override def getPendingNodeAsString(id: String):  String  = pendingNodeGitFile(id).contentAsString
  override def checkAcceptedNodeExists(id: String): Boolean = acceptedNodeGitFile(id).exists
  override def getAcceptedNodeAsString(id: String): String  = acceptedNodeGitFile(id).contentAsString

  override lazy val factStorage = {
    val cronSchedule        = Cron.parse("0 42 3 * * ?").toOption
    val gitFactRepoProvider = GitRepositoryProviderImpl
      .make(basePath + "/fact-repo")
      .runOrDie(err => new RuntimeException(s"Error when initializing git configuration repository: " + err.fullMsg))
    val gitFactRepoGC       = new GitGC(gitFactRepoProvider, cronSchedule)
    gitFactRepoGC.start()
    // we need to use the default group available, not rudder, else CI complains
    val storage             = new GitNodeFactStorageImpl(gitFactRepoProvider, None, true)
    storage.checkInit().runOrDie(err => new RuntimeException(s"Error when checking fact repository init: " + err.fullMsg))

    storage
  }
}

@RunWith(classOf[JUnitRunner])
class TestSaveInventoryLdap extends TestSaveInventory {

  def nodeExists(id: String, dit: InventoryDit): Boolean = {
    MockLdapFactStorage.testServer.entryExists(dit.NODES.NODE.dn(id).toString)
  }

  def nodeAsString(id: String, dit: InventoryDit): String = {
    val sb = new java.lang.StringBuilder()
    // we want to check also for the node entry, not only the inventory part
    MockLdapFactStorage.testServer.getEntry(dit.NODES.NODE.dn(id).toString).toString(sb)
    // we want to check also for the node entry, not only the inventory part
    MockLdapFactStorage.testServer.getEntry(MockLdapFactStorage.nodeDit.NODES.NODE.dn(id).toString).toString(sb)
    sb.toString()
  }

  override def checkPendingNodeExists(id: String):  Boolean = nodeExists(id, MockLdapFactStorage.pendingDIT)
  override def getPendingNodeAsString(id: String):  String  = nodeAsString(id, MockLdapFactStorage.pendingDIT)
  override def checkAcceptedNodeExists(id: String): Boolean = nodeExists(id, MockLdapFactStorage.acceptedDIT)
  override def getAcceptedNodeAsString(id: String): String  = nodeAsString(id, MockLdapFactStorage.acceptedDIT)

  override lazy val factStorage = MockLdapFactStorage.nodeFactStorage
}

@nowarn("msg=a type was inferred to be `\\w+`; this may indicate a programming error.")
trait TestSaveInventory extends Specification with BeforeAfterAll {

  // methods that need to be implemented by children
  def checkPendingNodeExists(id:  String): Boolean
  def getPendingNodeAsString(id:  String): String
  def checkAcceptedNodeExists(id: String): Boolean
  def getAcceptedNodeAsString(id: String): String

  // load bouncyCastle
  Security.addProvider(new BouncyCastleProvider())

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

  implicit def stringToNodeId(id: String): NodeId = NodeId(id)

  val basePath = s"/tmp/test-rudder-inventory/${DateFormaterService.gitTagFormat.print(DateTime.now())}"

  val INVENTORY_ROOT_DIR     = basePath + "/inventories"
  val INVENTORY_DIR_INCOMING = INVENTORY_ROOT_DIR + "/incoming"

  def incomingInventoryFile(name: String) = File(INVENTORY_DIR_INCOMING + "/" + name)

  val INVENTORY_DIR_FAILED   = INVENTORY_ROOT_DIR + "/failed"
  val INVENTORY_DIR_RECEIVED = INVENTORY_ROOT_DIR + "/received"

  def receivedInventoryFile(name: String) = File(INVENTORY_DIR_RECEIVED + "/" + name)

  val INVENTORY_DIR_UPDATE = INVENTORY_ROOT_DIR + "/accepted-nodes-updates"

  override def beforeAll(): Unit = {
    List(basePath, INVENTORY_DIR_INCOMING, INVENTORY_DIR_FAILED, INVENTORY_DIR_RECEIVED, INVENTORY_DIR_UPDATE).foreach(f =>
      File(f).createDirectoryIfNotExists(true)
    )

  }

  override def afterAll(): Unit = {
    if (java.lang.System.getProperty("tests.clean.tmp") != "false") {
      FileUtils.deleteDirectory(File(basePath).toJava)
    }
  }

  def factStorage: NodeFactStorage

  // TODO WARNING POC: this can't work on a machine with lots of node
  val callbackLog = Ref.make(Chunk.empty[NodeFactChangeEvent]).runNow
  def resetLog    = callbackLog.set(Chunk.empty).runNow
  def getLogName  = callbackLog.get.map(_.map(_.name)).runNow

  object noopNodeBySoftwareName extends GetNodesbySofwareName {
    override def apply(softName: String): IOResult[List[(NodeId, Software)]] = {
      Nil.succeed
    }
  }

  val factRepo = {
    for {
      pending   <- Ref.make(Map[NodeId, CoreNodeFact]())
      accepted  <- Ref.make(Map[NodeId, CoreNodeFact]())
      callbacks <- Ref.make(Chunk.empty[NodeFactChangeEventCallback])
      lock      <- ReentrantLock.make()
      r          = new CoreNodeFactRepository(factStorage, noopNodeBySoftwareName, pending, accepted, callbacks, lock)
      _         <- r.registerChangeCallbackAction(CoreNodeFactChangeEventCallback("trail", e => callbackLog.update(_.appended(e.event))))
//      _         <- r.registerChangeCallbackAction(new NodeFactChangeEventCallback("log", e => effectUioUnit(println(s"**** ${e.name}"))))
    } yield {
      r
    }
  }.runNow

  lazy val inventorySaver = new NodeFactInventorySaver(
    factRepo,
    (
      CheckOsType
      :: new LastInventoryDate()
      :: AddIpValues
      :: Nil
    ),
    (
// we don't want post commit hook in tests
//      new PostCommitInventoryHooks[Unit](HOOKS_D, HOOKS_IGNORE_SUFFIXES)
      Nil
    )
  )
  lazy val pipelinedInventoryParser: InventoryParser = {
    val fusionReportParser = {
      new FusionInventoryParser(
        new StringUuidGeneratorImpl(),
        rootParsingExtensions = Nil,
        contentParsingExtensions = Nil,
        ignoreProcesses = false
      )
    }

    new DefaultInventoryParser(
      fusionReportParser,
      Seq(
        new PreInventoryParserCheckConsistency
      )
    )
  }

  lazy val inventoryProcessorInternal = {
    new InventoryProcessor(
      pipelinedInventoryParser,
      inventorySaver,
      4,
      new InventoryDigestServiceV1(id => factRepo.get(id).map(_.map(cnf => NodeFact.fromMinimal(cnf).toFullInventory))),
      () => ZIO.unit
    )
  }

  lazy val inventoryProcessor = {
    val mover = new InventoryMover(
      INVENTORY_DIR_RECEIVED,
      INVENTORY_DIR_FAILED,
      new InventoryFailedHook("/tmp", Nil)
    )
    new DefaultProcessInventoryService(inventoryProcessorInternal, mover)
  }

//  org.slf4j.LoggerFactory
//    .getLogger("inventory-processing")
//    .asInstanceOf[ch.qos.logback.classic.Logger]
//    .setLevel(ch.qos.logback.classic.Level.TRACE)

  sequential

  val nodeId       = "86d9ec77-9db5-4ba3-bdca-f0baf3a5b477"
  val nodeName     = s"node2-${nodeId}.ocs"
  val nodeResource = s"inventories/7.2/${nodeName}"
  val newfqdn      = "node42.fqdn"
  val fqdn         = "node2.rudder.local"

  implicit val cc: ChangeContext = ChangeContext.newForRudder()

  "Saving a new, unknown inventory" should {

    "correctly save the node in pending" in {
      resetLog
      val n2     = incomingInventoryFile(nodeName)
      n2.write(Resource.getAsString(nodeResource))
      val n2sign = incomingInventoryFile(s"${nodeName}.sign")
      n2sign.write(Resource.getAsString(s"${nodeResource}.sign"))

      (inventoryProcessor.saveInventoryBlocking(InventoryPair(n2, n2sign)).runNow must beEqualTo(
        Saved(nodeName, nodeId)
      )) and
      (receivedInventoryFile(nodeName).exists must beTrue) and
      (checkPendingNodeExists(nodeId) must beTrue) and
      (factRepo.get(nodeId)(SelectNodeStatus.Pending).runNow must beSome()) and
      (getLogName must beEqualTo(Chunk("newPending")).eventually(2, 100.millis.asScala))
    }

    "change in node by repos are reflected in cold storage" in {
      implicit val attrs = SelectFacts.none
      resetLog
      val e              = (for {
        n <- factRepo.get(nodeId)(SelectNodeStatus.Pending).notOptional("node2 should be there for the test")
        e <- factRepo.save(NodeFact.fromMinimal(n).modify(_.fqdn).setTo(newfqdn))
      } yield e).runNow

      (getPendingNodeAsString(nodeId).contains(newfqdn) must beTrue) and
      (e.event must beAnInstanceOf[NodeFactChangeEvent.UpdatedPending]) and
      (getLogName must beEqualTo(Chunk("updatedPending")).eventually(2, 100.millis.asScala))

    }

    "update the node that was modified in repo" in {
      resetLog
      val n2     = receivedInventoryFile(nodeName).moveTo(incomingInventoryFile(nodeName))
      val n2sign = receivedInventoryFile(s"${nodeName}.sign").moveTo(incomingInventoryFile(s"${nodeName}.sign"))

      (inventoryProcessor.saveInventoryBlocking(InventoryPair(n2, n2sign)).runNow must beEqualTo(
        Saved(nodeName, nodeId)
      )) and
      (factRepo.get(nodeId)(SelectNodeStatus.Pending).testRunGet.fqdn must beEqualTo(fqdn)) and
      (getPendingNodeAsString(nodeId).contains(fqdn) must beTrue) and
      (getLogName must beEqualTo(Chunk("updatedPending")).eventually(2, 100.millis.asScala))
    }

    "rudder settings and properties can be modified on pending nodes" in {
      val prop           = NodeProperty("test-prop-name", ConfigValueFactory.fromAnyRef("test-prop-value"), None, None)
      implicit val attrs = SelectFacts.none
      val n              = (for {
        cnf   <- factRepo.get(nodeId)(SelectNodeStatus.Pending).notOptional(s"for test - the node was added earlier")
        up     = cnf
                   .modify(_.rudderSettings.state)
                   .setTo(NodeState.Initializing)
                   .modify(_.rudderSettings.policyMode)
                   .setTo(Some(PolicyMode.Audit))
                   .modify(_.properties)
                   .using(_.appended(prop))
        _     <- factRepo.save(NodeFact.fromMinimal(up))
        check <- factRepo.get(nodeId)(SelectNodeStatus.Pending).notOptional(s"for test - update node must be here")
      } yield check).runNow

      val entries = getPendingNodeAsString(nodeId)

      (
        n.properties must contain(prop)
      ) and (
        n.rudderSettings.state === NodeState.Initializing
      ) and (
        n.rudderSettings.policyMode === Some(PolicyMode.Audit)
      ) and (
        entries must contain("test-prop-name")
      ) and (
        entries must contain("test-prop-val")
      )
    }
  }

  "Accepting a new, unknown inventory" should {

    "correctly update status and move file around" in {
      resetLog
      val e = factRepo.changeStatus(nodeId, AcceptedInventory).runNow
      (e.event must beAnInstanceOf[NodeFactChangeEvent.Accepted]) and
      (checkAcceptedNodeExists(nodeId) must beTrue) and
      (factRepo.get(nodeId)(SelectNodeStatus.Accepted).testRunGet.rudderSettings.status must beEqualTo(AcceptedInventory)) and
      (getLogName must beEqualTo(Chunk("accepted")).eventually(2, 100.millis.asScala))
    }
    "change in node by repos are reflected in file" in {
      resetLog
      val e = (
        for {
          n <- factRepo.get(nodeId)(SelectNodeStatus.Accepted).notOptional("node2 should be there for the test")
          e <- factRepo.save(NodeFact.fromMinimal(n).modify(_.fqdn).setTo(newfqdn))
        } yield e
      ).runNow

      (e.event must beAnInstanceOf[NodeFactChangeEvent.Updated]) and
      (getAcceptedNodeAsString(nodeId).contains(newfqdn) must beTrue) and
      (getLogName must beEqualTo(Chunk("updatedAccepted")).eventually(2, 100.millis.asScala))
    }

    "update the node that was modified in repo" in {
      resetLog
      val n2     = receivedInventoryFile(nodeName).moveTo(incomingInventoryFile(nodeName))
      val n2sign = receivedInventoryFile(s"${nodeName}.sign").moveTo(incomingInventoryFile(s"${nodeName}.sign"))

      (
        inventoryProcessor.saveInventoryBlocking(InventoryPair(n2, n2sign)).runNow must beEqualTo(
          Saved(nodeName, nodeId)
        )
      ) and
      (factRepo.get(nodeId)(SelectNodeStatus.Accepted).testRunGet.fqdn must beEqualTo(fqdn)) and
      (getAcceptedNodeAsString(nodeId).contains(fqdn) must beTrue) and
      (getLogName must beEqualTo(Chunk("updatedAccepted")).eventually(2, 100.millis.asScala))
    }
  }

  "Changing status to deleted" should {

    "correctly delete node and value in repos" in {
      resetLog
      val e = factRepo.changeStatus(nodeId, RemovedInventory).runNow

      (e.event must beAnInstanceOf[NodeFactChangeEvent.Deleted]) and
      (checkPendingNodeExists(nodeId) must beFalse) and
      (checkAcceptedNodeExists(nodeId) must beFalse) and
      (factRepo.get(nodeId)(SelectNodeStatus.Any).runNow must beNone) and
      (getLogName must beEqualTo(Chunk("deleted")).eventually(2, 100.millis.asScala))
    }
  }

}
