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

package bootstrap.liftweb.checks.migration

import better.files.File
import bootstrap.liftweb.BootstrapLogger
import com.normation.errors._
import com.normation.inventory.domain.NodeId
import com.normation.inventory.ldap.core.FullInventoryFromLdapEntriesImpl
import com.normation.inventory.ldap.core.InventoryDit
import com.normation.inventory.ldap.core.InventoryDitService
import com.normation.inventory.ldap.core.InventoryDitServiceImpl
import com.normation.inventory.ldap.core.InventoryMapper
import com.normation.rudder.db.DBCommon
import com.normation.rudder.db.Doobie
import com.normation.rudder.domain.nodes.Node
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.facts.nodes.NodeFact
import com.normation.rudder.services.nodes.NodeInfoService
import com.normation.rudder.services.nodes.history.HistoryLogRepository
import com.normation.rudder.services.nodes.history.impl.FactLog
import com.normation.rudder.services.nodes.history.impl.FullInventoryFileParser
import com.normation.rudder.services.nodes.history.impl.InventoryHistoryJdbcRepository
import com.normation.rudder.services.nodes.history.impl.InventoryHistoryLogRepository
import com.normation.rudder.services.policies.NodeConfigData
import com.normation.zio._
import com.unboundid.ldap.sdk.DN
import org.apache.commons.io.FileUtils
import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat
import org.junit.runner._
import org.specs2.mutable._
import org.specs2.runner._
import org.specs2.specification.AfterAll
import zio._
import zio.syntax._

/**
 * Check that historical inventories are correctly migrated.
 * This file does not need a test postgres.
 */
// this one is file-based and does not need postgres
@RunWith(classOf[JUnitRunner])
class TestMigrateNodeAcceptationInventoriesFile extends TestMigrateNodeAcceptationInventories {
  override def doobie: Doobie = null
}

// this one use postgres and will run only with -Dtest.postgres="true"
@RunWith(classOf[JUnitRunner])
class TestMigrateNodeAcceptationInventoriesJdbc extends TestMigrateNodeAcceptationInventories with DBCommon {
  override def doJdbcTest = doDatabaseConnection

//  org.slf4j.LoggerFactory
//    .getLogger("sql")
//    .asInstanceOf[ch.qos.logback.classic.Logger]
//    .setLevel(ch.qos.logback.classic.Level.TRACE)

  override def afterAll(): Unit = {
    cleanDb()
    cleanTmpFiles()
  }
}

trait TestMigrateNodeAcceptationInventories extends Specification with AfterAll {

  def doJdbcTest = false

  sequential

  // either test with DB if "doobie" service is not null, else in file
  // doobie is only defined in DBCommon
  def doobie: Doobie

  val dateFormat = ISODateTimeFormat.dateTime()

  val testDir = File(s"/tmp/test-rudder-migrate-historical-inventories-${dateFormat.print(DateTime.now())}")

  //////////// set-up auto test cleaning ////////////
  def cleanTmpFiles():     Unit = {
    if (java.lang.System.getProperty("tests.clean.tmp") != "false") {
      BootstrapLogger.info("Deleting directory " + testDir.pathAsString)
      FileUtils.deleteDirectory(testDir.toJava)
    }
  }
  override def afterAll(): Unit = cleanTmpFiles()

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

  val historical = "historical-inventories"
  val srcDir     = File("src/test/resources") / historical

  val fileLog = new InventoryHistoryLogRepository(
    (testDir / historical).pathAsString,
    new FullInventoryFileParser(
      new FullInventoryFromLdapEntriesImpl(inventoryDitService, inventoryMapper),
      inventoryMapper
    )
  )

  /*
   * Store migrated inventories under rootDir/migrated as a nodeid/date.json files
   */
  object fileFactLog extends HistoryLogRepository[NodeId, DateTime, NodeFact, FactLog] {
    import com.normation.rudder.facts.nodes.NodeFactSerialisation._
    import zio.json._

    val root = testDir / "migrated"
    root.createDirectories()

    def nodeDir(nodeId: NodeId):                  File                  = root / nodeId.value
    def factFile(nodeId: NodeId, date: DateTime): File                  = {
      nodeDir(nodeId) / (dateFormat.print(date) + ".json")
    }
    override def getIds:                          IOResult[Seq[NodeId]] = {
      for {
        files <- IOResult.attempt(root.list.toSeq)
      } yield files.toSeq.map(f => NodeId(f.name))
    }

    override def get(id: NodeId, version: DateTime): IOResult[FactLog] = {
      for {
        json <- IOResult.attempt(s"Read json for ${id.value}}")(factFile(id, version).contentAsString)
        fact <- json.fromJson[NodeFact].toIO
      } yield FactLog(id, version, fact)
    }

    override def versions(id: NodeId): IOResult[Seq[DateTime]] = {
      for {
        files <- IOResult.attempt(nodeDir(id).list.toSeq)
        dates <- ZIO.foreach(files)(f => IOResult.attempt(dateFormat.parseDateTime(f.nameWithoutExtension(true))))
      } yield dates.toSeq
    }

    override def save(id: NodeId, data: NodeFact, datetime: DateTime): IOResult[FactLog] = {
      for {
        _   <- IOResult.attempt(nodeDir(id).createDirectoryIfNotExists())
        json = data.toJson
        _   <- IOResult.attempt(factFile(id, datetime).writeText(json))
      } yield FactLog(id, datetime, data)
    }
  }

  // lazy val needed to be able to not init datasource when tests are skipped
  lazy val testFactLog = if (doJdbcTest && doobie != null) new InventoryHistoryJdbcRepository(doobie) else fileFactLog

  // 0afa1d13-d125-4c91-9d71-24c47dc867e9 => deleted, far too old, not supported format
  // 0bd58a1f-3faa-4783-a7a2-52d84021663a => ok, only one file, age ok
  // 1bd58a1f-3faa-4783-a7a2-52d84021663a => ok, only one file, age more than max_keep_deleted (should not matter)
  // 4d3a43bc-8508-46a2-92d7-cfe7320309a5 => ok, 2 files, one too old
  // 59512a56-53e9-41e1-b36f-ca22d3cdfcbc => ok, 2 files, both ok in age
  // fb0096f3-a928-454d-9776-e8079d48cdd8 => deleted, age ok
  // fb0096f4-a928-454d-9776-e8079d48cdd8 => deleted, too old
  object nodeInfoService extends NodeInfoService {
    import com.softwaremill.quicklens._
    val n                       = NodeConfigData.node1
    def success(nodeId: NodeId) = Some(n.modify(_.node.id).setTo(nodeId)).succeed

    override def getAll():                              IOResult[Map[NodeId, NodeInfo]] = ???
    override def getNodeInfo(nodeId: NodeId):           IOResult[Option[NodeInfo]]      = nodeId.value match {
      case "0afa1d13-d125-4c91-9d71-24c47dc867e9" => None.succeed
      case "0bd58a1f-3faa-4783-a7a2-52d84021663a" => success(nodeId)
      case "1bd58a1f-3faa-4783-a7a2-52d84021663a" => success(nodeId)
      case "4d3a43bc-8508-46a2-92d7-cfe7320309a5" => success(nodeId)
      case "59512a56-53e9-41e1-b36f-ca22d3cdfcbc" => success(nodeId)
      case "fb0096f3-a928-454d-9776-e8079d48cdd8" => None.succeed
      case "fb0096f4-a928-454d-9776-e8079d48cdd8" => None.succeed
    }
    override def getNodeInfos(nodeIds: Set[NodeId]):    IOResult[Set[NodeInfo]]         = ???
    override def getNodeInfosSeq(nodesId: Seq[NodeId]): IOResult[Seq[NodeInfo]]         = ???
    override def getNumberOfManagedNodes:               Int                             = ???
    override def getAllNodesIds():                      IOResult[Set[NodeId]]           = ???
    override def getAllNodes():                         IOResult[Map[NodeId, Node]]     = ???
    override def getAllSystemNodeIds():                 IOResult[Seq[NodeId]]           = ???
    override def getPendingNodeInfos():                 IOResult[Map[NodeId, NodeInfo]] = ???
    override def getPendingNodeInfo(nodeId: NodeId):    IOResult[Option[NodeInfo]]      = ???
    override def getDeletedNodeInfos():                 IOResult[Map[NodeId, NodeInfo]] = ???
    override def getDeletedNodeInfo(nodeId: NodeId):    IOResult[Option[NodeInfo]]      = ???
    override def getAllNodeInfos():                     IOResult[Seq[NodeInfo]]         = ???
  }

  lazy val migration = new MigrateNodeAcceptationInventories(nodeInfoService, null, fileLog, testFactLog, 365.days)

  val referenceNow =
    dateFormat.parseDateTime("2023-06-01T04:15:35.000+02:00")

  def migratedAndCanRead(id: String, date: String) = {
    val d      = dateFormat.parseDateTime(date)
    val nodeId = NodeId(id)
    (testFactLog.get(nodeId, d).either.runNow must beRight)
  }

  "A full migration" should {

    // init; copy test data in src/test/resources/historical-inventories to testDir.
    // test: check that after migration, historical dir is empty, that the logRepos says "no more version", and that the
    //       the destination is ok
    "do migration without error" in {
      FileUtils.copyDirectoryToDirectory(srcDir.toJava, testDir.toJava)
      val res = migration.migrateAll(referenceNow).either.runNow
      res must beRight
    }

    "migrate existing nodes, whatever age or number of files - but keep the most recent" in {
      migratedAndCanRead("0bd58a1f-3faa-4783-a7a2-52d84021663a", "2023-05-25T14:31:55.143+02:00") and
      migratedAndCanRead("1bd58a1f-3faa-4783-a7a2-52d84021663a", "2021-05-25T14:31:55.143+02:00") and
      migratedAndCanRead("4d3a43bc-8508-46a2-92d7-cfe7320309a5", "2023-04-18T23:26:09.417+02:00") and
      migratedAndCanRead("59512a56-53e9-41e1-b36f-ca22d3cdfcbc", "2023-04-11T22:44:59.643+02:00")
    }

    "ignore deleted nodes too old, even with old file format, without error" in {
      val ids = testFactLog.getIds.runNow
      (ids must contain(be_!=(NodeId("0afa1d13-d125-4c91-9d71-24c47dc867e9")))) and
      (ids must contain(be_!=(NodeId("fb0096f4-a928-454d-9776-e8079d48cdd8"))))
    }

    "migrate deleted node when its event date is less than MAX_KEEP_DELETED" in {
      migratedAndCanRead("fb0096f3-a928-454d-9776-e8079d48cdd8", "2023-04-11T22:15:53.375+02:00")
    }

    "historical directory is empty" in {
      File(fileLog.rootDir).list.toList === Nil
    }

  }

  "If a new log is provided latter on, for ex after a reboot, then node history is updated" >> {
    FileUtils.copyDirectory(File("src/test/resources/historical-inventories-update").toJava, (testDir / historical).toJava)
    migration.migrateAll(referenceNow).runNow
    migratedAndCanRead("0bd58a1f-3faa-4783-a7a2-52d84021663a", "2023-05-30T12:00:00.000+02:00")
  }

}
