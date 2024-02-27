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
import com.normation.box.IOManaged
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
import com.normation.rudder.inventory.InventoryProcessStatus
import com.normation.rudder.inventory.InventoryProcessStatus.Saved
import com.normation.rudder.inventory.SaveInventoryInfo
import com.normation.rudder.tenants.DefaultTenantService
import com.normation.utils.DateFormaterService
import com.normation.utils.StringUuidGeneratorImpl
import com.normation.zio._
import com.normation.zio.ZioRuntime
import com.softwaremill.quicklens._
import com.typesafe.config.ConfigValueFactory
import cron4s.Cron
import java.io.InputStream
import java.security.Security
import org.apache.commons.io.FileUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.joda.time.DateTime
import org.junit.runner._
import org.specs2.matcher.MatchResult
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

  val GIT_PENDING: String = basePath + "/fact-repo/nodes/pending"

  def pendingNodeGitFile(id: String): File = File(GIT_PENDING + "/" + id + ".json")

  val GIT_ACCEPTED: String = basePath + "/fact-repo/nodes/accepted"

  def acceptedNodeGitFile(id: String): File = File(GIT_ACCEPTED + "/" + id + ".json")

  override def checkPendingNodeExists(id: String):  Boolean = pendingNodeGitFile(id).exists
  override def getPendingNodeAsString(id: String):  String  = pendingNodeGitFile(id).contentAsString
  override def checkAcceptedNodeExists(id: String): Boolean = acceptedNodeGitFile(id).exists
  override def getAcceptedNodeAsString(id: String): String  = acceptedNodeGitFile(id).contentAsString

  override lazy val factStorage: GitNodeFactStorageImpl = {
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

@nowarn("msg=a type was inferred to be `\\w+`; this may indicate a programming error.")
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

  //////// for LDAP reposiotyr, in addition to main acceptation test, we are
  //////// checking properties around certificate validation

  val windows:   NodeId = NodeId("b73ea451-c42a-420d-a540-47b445e58313")
  val linuxKey:  NodeId = NodeId("baded9c8-902e-4404-96c1-278acca64e3a")
  val linuxCert: NodeId = NodeId("67e00959-ccda-430d-b6c2-ad1f3e89276a")

  val exist: IOManaged[Boolean] = IOManaged.make(true)(_ => ())

  def asManagedStream(name: String): IOManaged[InputStream] = IOManaged.make(Resource.getAsStream(name))(_.close())

  // LINUX

  "when a linux node is not in repository, it is ok to have a signature with certificate" in {
    val (res, inv) = (
      for {
        res <- inventoryProcessorInternal
                 .saveInventoryInternal(
                   SaveInventoryInfo(
                     "linux-cfe-sign-cert",
                     asManagedStream("certificates/linux-cfe-sign-cert.ocs"),
                     asManagedStream("certificates/linux-cfe-sign-cert.ocs.sign"),
                     exist
                   )
                 )
        inv <- factRepo.get(linuxCert)(QueryContext.testQC)
      } yield (res, inv)
    ).runNow

    (res must beAnInstanceOf[InventoryProcessStatus.Saved]) and
    (inv must beSome) and
    (SecurityToken.kind(inv.forceGet.rudderAgent.securityToken) === "certificate") and
    (inv.forceGet.rudderAgent.securityToken.key === Cert.CERT_CFE_OK) and
    (inv.forceGet.rudderSettings.keyStatus == CertifiedKey)

  }

  "hostname does not matter and can change with fallback logic" in {
    val (start, res, inv) = (
      for {
        start <- factRepo.get(linuxCert)(QueryContext.testQC)

        res <- inventoryProcessorInternal
                 .saveInventoryInternal(
                   SaveInventoryInfo(
                     "linux-cfe-sign-cert",
                     asManagedStream("certificates/linux-cfe-sign-cert-hostname1.ocs"),
                     asManagedStream("certificates/linux-cfe-sign-cert-hostname1.ocs.sign"),
                     exist
                   )
                 )
        inv <- factRepo.get(linuxCert)(QueryContext.testQC)
      } yield (start, res, inv)
    ).runNow

    (res must beAnInstanceOf[InventoryProcessStatus.Saved]) and
    (inv.forceGet.fqdn === "agent3.FQDN_ATTRIBUTE.rudder.local") and
    (start.forceGet.rudderAgent.securityToken.key === Cert.CERT_CFE_OK) and
    (inv.forceGet.rudderAgent.securityToken.key === Cert.CERT_CFE_OK) and
    (inv.forceGet.rudderSettings.keyStatus == CertifiedKey)

  }

  "hostname does not matter and can change with override logic" in {
    val (start, res, inv) = (
      for {
        start <- factRepo.get(linuxCert)(QueryContext.testQC)

        res <- inventoryProcessorInternal
                 .saveInventoryInternal(
                   SaveInventoryInfo(
                     "linux-cfe-sign-cert",
                     asManagedStream("certificates/linux-cfe-sign-cert-hostname2.ocs"),
                     asManagedStream("certificates/linux-cfe-sign-cert-hostname2.ocs.sign"),
                     exist
                   )
                 )
        inv <- factRepo.get(linuxCert)(QueryContext.testQC)
      } yield (start, res, inv)
    ).runNow

    (res must beAnInstanceOf[InventoryProcessStatus.Saved]) and
    (inv.forceGet.fqdn === "node1-overridden.rudder.local.override") and
    (start.forceGet.rudderAgent.securityToken.key === Cert.CERT_CFE_OK) and
    (inv.forceGet.rudderAgent.securityToken.key === Cert.CERT_CFE_OK) and
    (inv.forceGet.rudderSettings.keyStatus == CertifiedKey)
  }

  "when a node is not in repository, invalid signature is an error" in {
    val (res, inv) = (
      for {
        _   <- factRepo.delete(linuxCert)
        res <- inventoryProcessorInternal
                 .saveInventoryInternal(
                   SaveInventoryInfo(
                     "linux-cfe-sign-cert",
                     asManagedStream("certificates/linux-cfe-sign-cert.ocs"),
                     asManagedStream("certificates/windows-bad-certificate.ocs.sign"),
                     exist
                   )
                 )
        inv <- factRepo.get(linuxCert)(QueryContext.testQC)
      } yield (res, inv)
    ).runNow

    (res must beAnInstanceOf[InventoryProcessStatus.SignatureInvalid]) and
    (inv must beNone)
  }

  // WINDOWS
  "when a node is not in repository, it is ok to have a signature with certificate" in {
    val (res, inv) = (
      for {
        _   <- factRepo.delete(windows)
        res <- inventoryProcessorInternal
                 .saveInventoryInternal(
                   SaveInventoryInfo(
                     "windows-same-certificate",
                     asManagedStream("certificates/windows-same-certificate.ocs"),
                     asManagedStream("certificates/windows-same-certificate.ocs.sign"),
                     exist
                   )
                 )
        inv <- factRepo.get(windows)(QueryContext.testQC)
      } yield (res, inv)
    ).runNow

    (res must beAnInstanceOf[InventoryProcessStatus.Saved]) and
    (inv must beSome) and
    (SecurityToken.kind(inv.forceGet.rudderAgent.securityToken) === "certificate") and
    (inv.forceGet.rudderAgent.securityToken.key === Cert.CERT_WIN_OK) and
    (inv.forceGet.rudderSettings.keyStatus == CertifiedKey)
  }

  "when a node is in repository with a registered key, it is ok to add it again with a signature" in {
    val (start, res, inv) = (
      for {
        start <- factRepo.get(windows)(QueryContext.testQC)

        res <- inventoryProcessorInternal
                 .saveInventoryInternal(
                   SaveInventoryInfo(
                     "windows-same-certificate",
                     asManagedStream("certificates/windows-same-certificate.ocs"),
                     asManagedStream("certificates/windows-same-certificate.ocs.sign"),
                     exist
                   )
                 )
        inv <- factRepo.get(windows)(QueryContext.testQC)
      } yield (start, res, inv)
    ).runNow

    (res must beAnInstanceOf[InventoryProcessStatus.Saved]) and
    (inv.forceGet.fqdn === "WIN-GNGDHPVHVTN") and
    (start.forceGet.rudderAgent.securityToken.key === Cert.CERT_WIN_OK) and
    (inv.forceGet.rudderAgent.securityToken.key === Cert.CERT_WIN_OK) and
    (inv.forceGet.rudderSettings.keyStatus == CertifiedKey)

  }

  // this one will be used to update certificate: sign new inventory with old key
  "when a node is in repository with a registered key; signature must match existing certificate, not the one in inventory" in {
    val (start, res, inv) = (
      for {
        start <- factRepo.get(windows)(QueryContext.testQC)

        res <- inventoryProcessorInternal
                 .saveInventoryInternal(
                   SaveInventoryInfo(
                     "windows-new-certificate",
                     asManagedStream("certificates/windows-new-certificate.ocs"),
                     asManagedStream("certificates/windows-new-certificate.ocs.sign"),
                     exist
                   )
                 )
        inv <- factRepo.get(windows)(QueryContext.testQC)
      } yield (start, res, inv)
    ).runNow

    (res must beAnInstanceOf[InventoryProcessStatus.Saved]) and
    (inv.forceGet.fqdn === "WIN-GNGDHPVHVTN") and
    (start.forceGet.rudderAgent.securityToken.key === Cert.CERT_WIN_OK) and
    (inv.forceGet.rudderAgent.securityToken.key === Cert.CERT_WIN_NEW) and
    (inv.forceGet.rudderSettings.keyStatus == CertifiedKey)
  }

  "when certificate 'subject' doesn't match node ID, we got an error" in {
    val res = (
      for {
        _   <- factRepo.delete(windows)
        res <- inventoryProcessorInternal
                 .saveInventoryInternal(
                   SaveInventoryInfo(
                     "windows-bad-certificate",
                     asManagedStream("certificates/windows-bad-certificate.ocs"),
                     asManagedStream("certificates/windows-bad-certificate.ocs.sign"),
                     exist
                   )
                 )
      } yield {
        res match {
          case InventoryProcessStatus.SaveError(_, _, err) => err.fullMsg
          case x                                           => s"not what was expected: ${x}}"
        }
      }
    ).runNow

    (res must beMatching(".*subject doesn't contain same node ID in 'UID' attribute as inventory node ID.*"))
  }
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
    def isOK: MatchResult[Either[E, T]] = thing.testRun must beRight
  }

  implicit class ForceGetE[E, A](opt: Either[E, A]) {
    def forceGet: A = opt match {
      case Right(x)  => x
      case Left(err) => throw new Exception(s"error in Test: ${err}")
    }
  }

  implicit class ForceGetOpt[A](opt: Option[A]) {
    def forceGet: A = opt match {
      case Some(x) => x
      case None    => throw new Exception(s"error in Test: option is none")
    }
  }

  implicit def stringToNodeId(id: String): NodeId = NodeId(id)

  val basePath: String = s"/tmp/test-rudder-inventory/${DateFormaterService.gitTagFormat.print(DateTime.now())}"

  val INVENTORY_ROOT_DIR:     String = basePath + "/inventories"
  val INVENTORY_DIR_INCOMING: String = INVENTORY_ROOT_DIR + "/incoming"

  def incomingInventoryFile(name: String): File = File(INVENTORY_DIR_INCOMING + "/" + name)

  val INVENTORY_DIR_FAILED:   String = INVENTORY_ROOT_DIR + "/failed"
  val INVENTORY_DIR_RECEIVED: String = INVENTORY_ROOT_DIR + "/received"

  def receivedInventoryFile(name: String): File = File(INVENTORY_DIR_RECEIVED + "/" + name)

  val INVENTORY_DIR_UPDATE: String = INVENTORY_ROOT_DIR + "/accepted-nodes-updates"

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

  implicit val cc: ChangeContext = ChangeContext.newForRudder()
  import QueryContext.testQC

  // TODO WARNING POC: this can't work on a machine with lots of node
  val callbackLog: Ref[Chunk[NodeFactChangeEvent]] = Ref.make(Chunk.empty[NodeFactChangeEvent]).runNow
  def resetLog:    Unit                            = callbackLog.set(Chunk.empty).runNow
  def getLogName:  Chunk[String]                   = callbackLog.get.map(_.map(_.name)).runNow

  object noopNodeBySoftwareName extends GetNodesbySofwareName {
    override def apply(softName: String): IOResult[List[(NodeId, Software)]] = {
      Nil.succeed
    }
  }

  val factRepo: CoreNodeFactRepository = {
    for {
      pending   <- Ref.make(Map[NodeId, CoreNodeFact]())
      accepted  <- Ref.make(Map[NodeId, CoreNodeFact]())
      callbacks <- Ref.make(Chunk.empty[NodeFactChangeEventCallback])
      tenants   <- DefaultTenantService.make(Nil)
      lock      <- ReentrantLock.make()
      r          = new CoreNodeFactRepository(factStorage, noopNodeBySoftwareName, tenants, pending, accepted, callbacks, lock)
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

  lazy val inventoryProcessorInternal: InventoryProcessor = {
    new InventoryProcessor(
      pipelinedInventoryParser,
      inventorySaver,
      4,
      new InventoryDigestServiceV1(id => {
        // this is always Rudder system that is doing these queries, even in tests
        factRepo.get(id)(QueryContext.systemQC)
      }),
      () => ZIO.unit
    )
  }

  lazy val inventoryProcessor: DefaultProcessInventoryService = {
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

  val nodeId = "86d9ec77-9db5-4ba3-bdca-f0baf3a5b477"
  val nodeName:     String = s"node2-${nodeId}.ocs"
  val nodeResource: String = s"inventories/7.2/${nodeName}"
  val newfqdn = "node42.fqdn"
  val fqdn    = "node2.rudder.local"

  "Saving a new, unknown inventory" should {
    implicit val status = SelectNodeStatus.Pending

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
      (factRepo.get(nodeId).runNow must beSome()) and
      (getLogName must beEqualTo(Chunk("newPending")).eventually(2, 100.millis.asScala))
    }

    "change in node by repos are reflected in cold storage" in {
      implicit val attrs = SelectFacts.none
      resetLog
      val e              = (for {
        n <- factRepo.get(nodeId).notOptional("node2 should be there for the test")
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
      (factRepo.get(nodeId).testRunGet.fqdn must beEqualTo(fqdn)) and
      (getPendingNodeAsString(nodeId).contains(fqdn) must beTrue) and
      (getLogName must beEqualTo(Chunk("updatedPending")).eventually(2, 100.millis.asScala))
    }

    "rudder settings and properties can be modified on pending nodes" in {
      val prop           = NodeProperty("test-prop-name", ConfigValueFactory.fromAnyRef("test-prop-value"), None, None)
      implicit val attrs = SelectFacts.none
      val n              = (for {
        cnf   <- factRepo.get(nodeId).notOptional(s"for test - the node was added earlier")
        up     = cnf
                   .modify(_.rudderSettings.state)
                   .setTo(NodeState.Initializing)
                   .modify(_.rudderSettings.policyMode)
                   .setTo(Some(PolicyMode.Audit))
                   .modify(_.properties)
                   .using(_.appended(prop))
        _     <- factRepo.save(NodeFact.fromMinimal(up))
        check <- factRepo.get(nodeId).notOptional(s"for test - update node must be here")
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
    implicit val status = SelectNodeStatus.Accepted

    "correctly update status and move file around" in {
      resetLog
      val e = factRepo.changeStatus(nodeId, AcceptedInventory).runNow
      (e.event must beAnInstanceOf[NodeFactChangeEvent.Accepted]) and
      (checkAcceptedNodeExists(nodeId) must beTrue) and
      (factRepo.get(nodeId).testRunGet.rudderSettings.status must beEqualTo(AcceptedInventory)) and
      (getLogName must beEqualTo(Chunk("accepted")).eventually(2, 100.millis.asScala))
    }
    "change in node by repos are reflected in file" in {
      resetLog
      val e = (
        for {
          n <- factRepo.get(nodeId).notOptional("node2 should be there for the test")
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
      (factRepo.get(nodeId).testRunGet.fqdn must beEqualTo(fqdn)) and
      (getAcceptedNodeAsString(nodeId).contains(fqdn) must beTrue) and
      (getLogName must beEqualTo(Chunk("updatedAccepted")).eventually(2, 100.millis.asScala))
    }
  }

  "Changing status to deleted" should {
    implicit val status = SelectNodeStatus.Any

    "correctly delete node and value in repos" in {
      resetLog
      val e = factRepo.changeStatus(nodeId, RemovedInventory).runNow

      (e.event must beAnInstanceOf[NodeFactChangeEvent.Deleted]) and
      (checkPendingNodeExists(nodeId) must beFalse) and
      (checkAcceptedNodeExists(nodeId) must beFalse) and
      (factRepo.get(nodeId).runNow must beNone) and
      (getLogName must beEqualTo(Chunk("deleted")).eventually(2, 100.millis.asScala))
    }
  }

}

object Cert {

  val KEY_CFE_OK: String = {
    """-----BEGIN RSA PUBLIC KEY-----
      |MIICCgKCAgEAtG20P906HK1MV0nSA2eWKqC+29tX8/TnHd0YGAVgg5+ODr+tbXXj
      |WgtEr0XaLxN12/Usu+DSQWcEAMAn6O3iUmxsIYLWRAU1mqqK0q2rxov3+jg/7sK9
      |sYo9dg9OGOjbFeoJVg66P1zlZt4YWczBP+zM+tLvh+Zn65IcLAc6ASm6imWjStNf
      |9rmpfupsd85y29qpdxbS7acb3WOzelz2EVVU5NBhQ3VB94n3O5+9UjpHSSOkR3bA
      |oB9UsrnbXpDLV0NfEBUtiQsgcARO94QjRaaN2kgvGAIryZJxgDOmNGNzJTNV7p1W
      |geN5L2Bal98C4vbR6dnln2RTOnE8uQb0gd36gHZF/Zyh45Odlx/XZc1P5UtV/BUU
      |PxUhYYxth5P6u2C2YHtyb6ws7lKtT/W9ECiK4i/gyEIVDxu7stR17HvRR0BL1D2u
      |wHl/TkpkTHRIHl7G6zt79s7ujFFWn+GRXPXLFfUy45e0HkGeUVJUoW62NZTp1bFI
      |BaErpPMRwXmvD6v3ox3XGI46wSjfIG0c8W3nYHM1q9JrwE9+0jLBb8bumsgS7KpO
      |0VKjPXX7rzH+2l8AweQR3JzGifUyl/DsHiPSaOc3nAWJy0k7yizhHkebw6rcM/SD
      |lz4F5ONicuBwYgjvmgjA7o2COh7s+dIoYXp01xMb60OUfjKeG8BUKucCAwEAAQ==
      |-----END RSA PUBLIC KEY-----""".stripMargin
  }

  val CERT_CFE_OK: String = {
    """-----BEGIN CERTIFICATE-----
      |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
      |KIrCpwe0wYC3267ZbRQJ
      |-----END CERTIFICATE-----""".stripMargin
  }

  val CERT_WIN_OK: String = {
    """-----BEGIN CERTIFICATE-----
      |MIIFgTCCA2mgAwIBAgIUXpY2lv7l+hkx4mVP324d9O1qJh0wDQYJKoZIhvcNAQEL
      |BQAwUDEYMBYGA1UEAwwPV0lOLUdOR0RIUFZIVlROMTQwMgYKCZImiZPyLGQBAQwk
      |YjczZWE0NTEtYzQyYS00MjBkLWE1NDAtNDdiNDQ1ZTU4MzEzMB4XDTE5MDcxMjE2
      |MTYxMloXDTI3MDkyODE2MTYxMlowUDEYMBYGA1UEAwwPV0lOLUdOR0RIUFZIVlRO
      |MTQwMgYKCZImiZPyLGQBAQwkYjczZWE0NTEtYzQyYS00MjBkLWE1NDAtNDdiNDQ1
      |ZTU4MzEzMIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEApW5up//FLSHr
      |J14iIX7aSTGiVvJ5XTXHXxmx3O1MyFIrNoWoonmR7Wkii+FIcxk8LVajjMaBVP32
      |ZbfEr1BqljV/XULTO4ivQoqJCfoq/2O5O2Apyh1XJmp8q82CZRz/ZzxKmFAeYgYE
      |KPbzr/SeLkNvo9zaYZLMGT1Zle8pu7gBWF8DPFg1r77Y1zfSSRTRMSXQk0BVN5uR
      |2Ru8A53ZI7yDOB73pNXbtV++XdBzbwzBDG24NY80o+bbGSCRgizeDqNBeVjzOzyf
      |wRp6KFuLrwfksnUcWcwMBz3af6d5uh5hrDII63t30u3eVdmGYUb9oi5JjCOtcJta
      |r3EhwoeEoeioAxpJebe0Q0OEbEICh4Z/oxGYaG/rn9UZ3Hhw9sdngihiTx/sQ8yg
      |CGURXr/tQSw1knrmU7Fe1TytfcEhaGhnfjRXhUHXP75ycp4mdp3uRsHSKT7VN95H
      |lCVxZGUMkE9w8CZQTH2RmL6E5r0VqilktViWmuf31h2DPzg9rvBj+rQpBvgQzUiv
      |1TzuFzsuLKBp3KMpxHrnIxEMS2ERj1Kr7mAxW3xZVt3dYrw8SdbfozJ4x/d8ciKu
      |ovN0BBrPIn0wS6v7hT2mMtneEG/xbXZFjL8XqVwIooRCDOhw4UfWb71CdpBNZ8ln
      |tje4Ri0/C7l5ZJGYJNOpZFBlpDXmMTkCAwEAAaNTMFEwHQYDVR0OBBYEFHJaeKBJ
      |FcPOMwPGxt8uNESLRJ2YMB8GA1UdIwQYMBaAFHJaeKBJFcPOMwPGxt8uNESLRJ2Y
      |MA8GA1UdEwEB/wQFMAMBAf8wDQYJKoZIhvcNAQELBQADggIBAAjUW4YmUjYz6K50
      |uuN/WT+vRtPAKjTcKPi397O0sa1EZDq7gJt2gbYBMyqDFyoivKeec2umXzm7n0o5
      |yDJ1jwgl0ORxqtCmjzPuwbY9EL8dBycACsr8KorXct2vseC+uxNWnsLbUVs3iTbI
      |AG5dtXpytZJXioVvR/Hi6DnJ8hP6wQLKJYw3E91jjIdfUBWT1GRzjTHo6VBxlvQd
      |KFS8JeHMaUJjWiXeI8ZYPjLCDL2Fxs6hlgySBaZSbGySraFwt9l4RDVnUxexMloc
      |ZECALfJg4fISgZodHXRxVBKEUv71ebSqYfJt8f8LeyfLVK/MY9rmpdV8DGQieaaV
      |YdhslUYx6vTnk/0Q/LbeHXI2cm2qBP1oyPusydTWWc6TowCLhHqTJ+eAB2X/RjT/
      |MTe/B3GGKgn1lgB37qF2hVDWtrDvNzE4OGQCNBR/iJDHz5+8MV+4FDT0/7ruTP0B
      |iMDtuT7Jrk9O/UhAZyG4uyUm+kpcPIevGy2ZVQUgk/zIqLH+R4QrRebXRLrNsKuP
      |o07htJltXDGDSekSDgK3OnZwLOyTUrz1zMmGqGbqRCwOQAWcZBWLrIjUjM0k9vPy
      |qYUqf4FphVwX4JqDhm8JSS/et/0431MjMfQC/qauAhPBITgRjlDVEVvGB40aiNLk
      |ootapja6lKOaIpqp0kmmYN7gFIhp
      |-----END CERTIFICATE-----""".stripMargin
  }

  val CERT_WIN_NEW: String = {
    """-----BEGIN CERTIFICATE-----
      |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
      |TZEW7+Ri43DsMyRwYiCafuVThL+J
      |-----END CERTIFICATE-----""".stripMargin
  }

  val CERT_WIN_BAD: String = {
    """-----BEGIN CERTIFICATE-----
      |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
      |-----END CERTIFICATE-----""".stripMargin
  }

}
