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
import com.github.ghik.silencer.silent
import com.normation.errors._
import com.normation.inventory.domain._
import com.normation.inventory.ldap.provisioning._
import com.normation.inventory.provisioning.fusion.FusionInventoryParser
import com.normation.inventory.provisioning.fusion.PreInventoryParserCheckConsistency
import com.normation.inventory.services.provisioning.DefaultInventoryParser
import com.normation.inventory.services.provisioning.InventoryDigestServiceV1
import com.normation.inventory.services.provisioning.InventoryParser
import com.normation.rudder.batch.GitGC
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
import cron4s.Cron
import java.security.Security
import org.apache.commons.io.FileUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.joda.time.DateTime
import org.junit.runner._
import org.specs2.mutable._
import org.specs2.runner._
import org.specs2.specification.BeforeAfterAll
import zio._
import zio.concurrent.ReentrantLock

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
@silent("a type was inferred to be `\\w+`; this may indicate a programming error.")
@RunWith(classOf[JUnitRunner])
class TestSaveInventory extends Specification with BeforeAfterAll {

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

  implicit def stringToNodeId(id: String) = NodeId(id)

  val basePath = s"/tmp/test-rudder-inventory/${DateFormaterService.gitTagFormat.print(DateTime.now())}"

  val GIT_PENDING                     = basePath + "/fact-repo/nodes/pending"
  def pendingNodeGitFile(id: String)  = File(GIT_PENDING + "/" + id + ".json")
  val GIT_ACCEPTED                    = basePath + "/fact-repo/nodes/accepted"
  def acceptedNodeGitFile(id: String) = File(GIT_ACCEPTED + "/" + id + ".json")

  val INVENTORY_ROOT_DIR                  = basePath + "/inventories"
  val INVENTORY_DIR_INCOMING              = INVENTORY_ROOT_DIR + "/incoming"
  def incomingInventoryFile(name: String) = File(INVENTORY_DIR_INCOMING + "/" + name)
  val INVENTORY_DIR_FAILED                = INVENTORY_ROOT_DIR + "/failed"
  val INVENTORY_DIR_RECEIVED              = INVENTORY_ROOT_DIR + "/received"
  def receivedInventoryFile(name: String) = File(INVENTORY_DIR_RECEIVED + "/" + name)
  val INVENTORY_DIR_UPDATE                = INVENTORY_ROOT_DIR + "/accepted-nodes-updates"

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

  val cronSchedule        = Cron.parse("0 42 3 * * ?").toOption
  val gitFactRepoProvider = GitRepositoryProviderImpl
    .make(basePath + "/fact-repo")
    .runOrDie(err => new RuntimeException(s"Error when initializing git configuration repository: " + err.fullMsg))
  val gitFactRepoGC       = new GitGC(gitFactRepoProvider, cronSchedule)
  gitFactRepoGC.start()

  val gitFactRepo = new GitNodeFactRepositoryImpl(gitFactRepoProvider, "rudder")
  gitFactRepo.checkInit().runOrDie(err => new RuntimeException(s"Error when checking fact repository init: " + err.fullMsg))

  // TODO WARNING POC: this can't work on a machine with lots of node
  val callbackLog = Ref.make(Chunk.empty[NodeFactChangeEvent]).runNow
  def resetLog    = callbackLog.set(Chunk.empty).runNow
  def getLogName  = callbackLog.get.map(_.map(_.name)).runNow

  val factRepo = {
    for {
      pending   <- Ref.make(Map[NodeId, NodeFact]())
      accepted  <- Ref.make(Map[NodeId, NodeFact]())
      callbacks <- Ref.make(Chunk.empty[NodeFactChangeEventCallback])
      lock      <- ReentrantLock.make()
      r          = new CoreNodeFactRepository(gitFactRepo, pending, accepted, callbacks, lock)
      _         <- r.registerChangeCallbackAction(new NodeFactChangeEventCallback("trail", e => callbackLog.update(_.appended(e.event))))
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
      new InventoryDigestServiceV1(id => factRepo.lookup(id).map(_.map(_.toFullInventory))),
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

  val node2id       = "86d9ec77-9db5-4ba3-bdca-f0baf3a5b477"
  val node2name     = s"node2-${node2id}.ocs"
  val node2resource = s"inventories/7.2/${node2name}"
  val newfqdn       = "node42.fqdn"
  val fqdn          = "node2.rudder.local"

  implicit val cc = ChangeContext.newForRudder()

  "Saving a new, unknown inventory" should {

    "correctly save the node in pending" in {
      resetLog
      val n2     = incomingInventoryFile(node2name)
      n2.write(Resource.getAsString(node2resource))
      val n2sign = incomingInventoryFile(s"${node2name}.sign")
      n2sign.write(Resource.getAsString(s"${node2resource}.sign"))

      (inventoryProcessor.saveInventoryBlocking(InventoryPair(n2, n2sign)).runNow must beEqualTo(
        Saved(node2name, node2id)
      )) and
      (receivedInventoryFile(node2name).exists must beTrue) and
      (pendingNodeGitFile(node2id).exists must beTrue) and
      (factRepo.getPending(node2id).runNow must beSome()) and
      (getLogName === Chunk("newPending"))
    }

    "change in node by repos are reflected in file" in {
      resetLog
      val e = (for {
        n <- factRepo.getPending(node2id).notOptional("node2 should be there for the test")
        e <- factRepo.save(n.modify(_.fqdn).setTo(newfqdn))
      } yield e).runNow

      (pendingNodeGitFile(node2id).contentAsString.contains(newfqdn) must beTrue) and
      (e.event must beAnInstanceOf[NodeFactChangeEvent.UpdatedPending]) and
      (getLogName === Chunk("updatedPending"))

    }

    "update the node that was modified in repo" in {
      resetLog
      val n2     = receivedInventoryFile(node2name).moveTo(incomingInventoryFile(node2name))
      val n2sign = receivedInventoryFile(s"${node2name}.sign").moveTo(incomingInventoryFile(s"${node2name}.sign"))

      (inventoryProcessor.saveInventoryBlocking(InventoryPair(n2, n2sign)).runNow must beEqualTo(
        Saved(node2name, node2id)
      )) and
      (factRepo.getPending(node2id).testRunGet.fqdn must beEqualTo(fqdn)) and
      (pendingNodeGitFile(node2id).contentAsString.contains(fqdn) must beTrue) and
      (getLogName === Chunk("updatedPending"))
    }
  }

  "Accepting a new, unknown inventory" should {

    "correctly update status and move file around" in {
      resetLog
      val e = factRepo.changeStatus(node2id, AcceptedInventory).runNow
      (e.event must beAnInstanceOf[NodeFactChangeEvent.Accepted]) and
      (acceptedNodeGitFile(node2id).exists must beTrue) and
      (factRepo.getAccepted(node2id).testRunGet.rudderSettings.status must beEqualTo(AcceptedInventory)) and
      (getLogName === Chunk("accepted"))
    }
    "change in node by repos are reflected in file" in {
      resetLog
      val e = (
        for {
          n <- factRepo.getAccepted(node2id).notOptional("node2 should be there for the test")
          e <- factRepo.save(n.modify(_.fqdn).setTo(newfqdn))
        } yield e
      ).runNow

      (e.event must beAnInstanceOf[NodeFactChangeEvent.Updated]) and
      (acceptedNodeGitFile(node2id).contentAsString.contains(newfqdn) must beTrue) and
      (getLogName === Chunk("updatedAccepted"))
    }

    "update the node that was modified in repo" in {
      resetLog
      val n2     = receivedInventoryFile(node2name).moveTo(incomingInventoryFile(node2name))
      val n2sign = receivedInventoryFile(s"${node2name}.sign").moveTo(incomingInventoryFile(s"${node2name}.sign"))

      (
        inventoryProcessor.saveInventoryBlocking(InventoryPair(n2, n2sign)).runNow must beEqualTo(
          Saved(node2name, node2id)
        )
      ) and
      (factRepo.getAccepted(node2id).testRunGet.fqdn must beEqualTo(fqdn)) and
      (acceptedNodeGitFile(node2id).contentAsString.contains(fqdn) must beTrue) and
      (getLogName === Chunk("updatedAccepted"))
    }
  }

  "Changing status to deleted" should {

    "correctly delete node and value in repos" in {
      resetLog
      val e = factRepo.changeStatus(node2id, RemovedInventory).runNow

      (e.event must beAnInstanceOf[NodeFactChangeEvent.Deleted]) and
      (pendingNodeGitFile(node2id).exists must beFalse) and
      (acceptedNodeGitFile(node2id).exists must beFalse) and
      (factRepo.lookup(node2id).runNow must beNone) and
      (getLogName === Chunk("deleted"))
    }
  }

}
