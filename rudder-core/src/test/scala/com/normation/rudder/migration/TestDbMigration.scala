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

package com.normation.rudder.migration

import scala.collection.JavaConverters.asScalaBufferConverter

import org.junit.runner.RunWith
import org.specs2.matcher.XmlMatchers
import org.specs2.runner.JUnitRunner

import net.liftweb.common.EmptyBox
import net.liftweb.common.Failure
import net.liftweb.common.Full
import com.normation.rudder.db.SlickSchema
import com.normation.BoxSpecMatcher

case class MigEx102(msg:String) extends Exception(msg)

/**
 * Test how the migration run with a Database context
 *
 * Prerequise: A postgres database must be available,
 * with parameters defined in src/test/resources/database.properties.
 * That database should be empty to avoid table name collision.
 */

/**
 * Test how the migration run with a Database context from 2 to 3
 *
 * Prerequise: A postgres database must be available,
 * with parameters defined in src/test/resources/database.properties.
 * That database should be empty to avoid table name collision.
 */
@RunWith(classOf[JUnitRunner])
class TestDbMigration_2_3 extends DBCommon with XmlMatchers {

  lazy val migration = new EventLogsMigration_2_3(
      jdbcTemplate = jdbcTemplate
    , individualMigration = new EventLogMigration_2_3(new XmlMigration_2_3())
    , batchSize = 2
  ) {
    override val errorLogger = (f:Failure) => throw new MigEx102(f.messageChain)
  }


  override val sqlInit = """
CREATE TEMP SEQUENCE eventLogIdSeq START 1;

CREATE TEMP TABLE EventLog (
  id integer PRIMARY KEY  DEFAULT nextval('eventLogIdSeq')
, creationDate timestamp with time zone NOT NULL DEFAULT 'now'
, severity integer
, causeId integer
, principal varchar(64)
, eventType varchar(64)
, data xml
);
    """

  var logs2WithId : Map[String,MigrationTestLog] = null //init in initDb
  var logs3WithId : Seq[MigrationTestLog] = null

  override def initDb = {
    super.initDb

    // init datas, get the map of ids
    logs2WithId = withConnection[Map[String,MigrationTestLog]] { c =>
      (Migration_2_DATA_EventLogs.data_2.map { case (k,log) =>
        val id = log.insertSql(c)
        logger.debug("Inserting %s, id: %s".format(k,id))

        (k,log.copy( id = Some(id) ))
      }).toMap
    }

    //also add some bad event log that should not be migrated (bad/more recent file format)
    withConnection[Unit] { c =>
      NoMigrationEventLogs.e1.insertSql(c)
      NoMigrationEventLogs.e2.insertSql(c)
      NoMigrationEventLogs.e3.insertSql(c)

      {}
    }

    logs3WithId = (Migration_3_DATA_EventLogs.data_3.map { case (k,log) =>
      log.copy( id = Some(logs2WithId(k).id.get ) ) //actually get so that an exception is throw if there is no ID set
    }).toSeq
  }

  sequential
  //actual tests
  "Event Logs" should {

    "be all found" in {
      val logs = migration.findBatch.openOrThrowException("For tests")

      logs.size must beEqualTo(migration.batchSize) and
      forallWhen(logs) {
        case MigrationEventLog(id, eventType, data) =>
          val l = logs2WithId.values.find(x => x.id.get == id).get

          l.data must be_==/(data) and
          l.eventType === eventType
      }
    }

    "be correctly migrated" in {

      val MigrationProcessResult(migrated, nbBataches) = migration.process.openOrThrowException("Bad migration in test")

      val logs = jdbcTemplate.query("select * from eventlog", testLogRowMapper).asScala.filter(log =>
                   //only actually migrated file format
                   try {
                     log.data \\ "@fileFormat" exists { _.text.toInt == 3 }
                   } catch {
                     case e:NumberFormatException => false
                   }
                 )

      (logs.size must beEqualTo(logs3WithId.size)) and
      (logs.size must beEqualTo(migrated)) and
      (nbBataches must beEqualTo(logs.size/migration.batchSize)) and
      forallWhen(logs) {
        case MigrationTestLog(Some(id), eventType, timestamp, principal, cause, severity, data) =>
          val l = logs3WithId.find(x => x.id.get == id).get

          (l.eventType === eventType) and
          (l.timestamp === timestamp) and
          (l.principal === principal) and
          (l.cause === cause) and
          (l.severity == severity must beTrue) and
          (l.data must be_==/(data))

        case x => ko("Bad TestLog (no id): " + x)
      }
    }

  }
}


/**
 * Test how the migration run with a Database context from 2 to 3
 * with both database (eventlog and migration event log)
 *
 * Prerequise: A postgres database must be available,
 * with parameters defined in src/test/resources/database.properties.
 * That database should be empty to avoid table name collision.
 */
@RunWith(classOf[JUnitRunner])
class TestDbMigration_2_3b extends DBCommon with XmlMatchers with BoxSpecMatcher {


  lazy val migration = new EventLogsMigration_2_3(
      jdbcTemplate = jdbcTemplate
    , individualMigration = new EventLogMigration_2_3(new XmlMigration_2_3())
    , batchSize = 2
  ) {
    override val errorLogger = (f:Failure) => throw new MigEx102(f.messageChain)
  }


  lazy val migrationManagement = new ControlEventLogsMigration_2_3(
          migrationEventLogRepository = migrationEventLogRepository
        , Seq(migration)
  )


  var logs2WithId : Map[String,MigrationTestLog] = null //init in initDb
  var logs3WithId : Seq[MigrationTestLog] = null

  override def initDb = {
    super.initDb

    // init datas, get the map of ids
    logs2WithId = withConnection[Map[String,MigrationTestLog]] { c =>
      (Migration_2_DATA_EventLogs.data_2.map { case (k,log) =>
        val id = log.insertSql(c)
        logger.debug("Inserting %s, id: %s".format(k,id))
        (k,log.copy( id = Some(id) ))
      }).toMap
    }
    migrationEventLogRepository.createNewStatusLine(2)
    //also add some bad event log that should not be migrated (bad/more recent file format)
    withConnection[Unit] { c =>
      NoMigrationEventLogs.e1.insertSql(c)
      NoMigrationEventLogs.e2.insertSql(c)
      NoMigrationEventLogs.e3.insertSql(c)

      {}
    }

    logs3WithId = (Migration_3_DATA_EventLogs.data_3.map { case (k,log) =>
      log.copy( id = Some(logs2WithId(k).id.get ) ) //actually get so that an exception is throw if there is no ID set
    }).toSeq
  }
  sequential
  //actual tests
  "Event Logs" should {

    "be all found" in {
      val logs = migrationManagement.migrate()
      logs mustFull(MigrationSuccess(logs3WithId.size))
    }

    "be correctly migrated" in {
      val logs = jdbcTemplate.query("select * from eventlog", testLogRowMapper).asScala.filter(log =>
                   //only actually migrated file format
                   try {
                     log.data \\ "@fileFormat" exists { _.text.toInt == 3 }
                   } catch {
                     case e:NumberFormatException => false
                   }
                 )

      logs.size must beEqualTo(logs3WithId.size) and
      forallWhen(logs) {
        case MigrationTestLog(Some(id), eventType, timestamp, principal, cause, severity, data) =>
          val l = logs3WithId.find(x => x.id.get == id).get
          (l.eventType === eventType) and
          (l.timestamp === timestamp) and
          (l.principal === principal) and
          (l.cause === cause) and
          (l.severity == severity must beTrue) and
          (l.data must be_==/(data))

        case x => ko("Bad TestLog (no id): " + x)
      }
    }
  }
}

object NoMigrationEventLogs {

  val e1 = MigrationTestLog(
               eventType = "AcceptNode"
             , data      = <entry><node action="accept" fileFormat="3.42">
                             <id>xxxc8e3d-1bf6-4bc1-9398-f8890b015a50</id>
                             <inventoryVersion>2011-10-13T11:43:52.907+02:00</inventoryVersion>
                             <hostname>centos-5-32</hostname>
                             <fullOsName>Centos</fullOsName>
                             <actorIp>127.0.0.1</actorIp>
                           </node></entry>
  )

  val e2 = MigrationTestLog(
               eventType = "AcceptNode"
             , data      = <entry><node action="accept" fileFormat="**BAD**">
                             <id>xxxx8e3d-1bf6-4bc1-9398-f8890b015a50</id>
                             <inventoryVersion>2011-10-13T11:43:52.907+02:00</inventoryVersion>
                             <hostname>centos-5-32</hostname>
                             <fullOsName>Centos</fullOsName>
                             <actorIp>127.0.0.1</actorIp>
                           </node></entry>
  )

  val e3 = MigrationTestLog(
               eventType = "StartDeployement"
             , data      = <entry><addPendingDeployement alreadyPending="false" fileFormat="2.21"></addPendingDeployement></entry>
  )


}
