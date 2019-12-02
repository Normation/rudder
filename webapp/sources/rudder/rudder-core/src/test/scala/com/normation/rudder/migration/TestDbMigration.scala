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


import org.junit.runner.RunWith
import org.specs2.matcher.XmlMatchers
import org.specs2.runner.JUnitRunner

import net.liftweb.common.Failure
import scala.xml.Elem
import java.sql.Timestamp
import java.sql.Connection
import com.normation.rudder.db.DBCommon

import com.normation.rudder.db.Doobie._

import doobie.implicits._
import cats._
import zio.interop.catz._

final case class MigEx102(msg:String) extends Exception(msg)


object MigrationTestLog {
  //get a default TimeStamp value for that run
  val defaultTimestamp = new Timestamp(System.currentTimeMillis)
}

final case class MigrationTestLog(
    id       : Option[Long] = None
  , eventType: String
  , timestamp: Timestamp = MigrationTestLog.defaultTimestamp
  , principal: String = "TestUser"
  , cause    : Option[Int] = None
  , severity : Int = 100
  , data     : Elem
) {

  def insertSql(c: Connection) : Long = {
    //ignore cause id
    val (row, qmark) = cause match {
      case Some(id) => ("causeId", ", ?")
      case None => ("", "")
    }

    val INSERT_SQL = "insert into EventLog (creationDate, principal, eventType, severity, data%s) values (?, ?, ?, ?, ?)".format(row, qmark)
    val ps = c.prepareStatement(INSERT_SQL, Array("id"))
    ps.setTimestamp(1, timestamp)
    ps.setString(2, principal)
    ps.setString(3, eventType)
    ps.setInt(4, severity)
    val sqlXml = c.createSQLXML()
    sqlXml.setString(data.toString)
    ps.setSQLXML(5, sqlXml)
    cause.foreach { id =>
      ps.setInt(6, id)
    }
    ps.executeUpdate
    val rs = ps.getGeneratedKeys
    rs.next
    rs.getLong("id")
  }
}

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
class TestDbMigration_5_6 extends DBCommon with XmlMatchers {

  lazy val migration = new EventLogsMigration_5_6(
      doobie = doobie
    , batchSize = 2
  ) {
    override val errorLogger = (f:Failure) => throw new MigEx102(f.messageChain)
  }

  var logs5WithId : Map[String,MigrationTestLog] = null //init in initDb
  var logs6WithId : Seq[MigrationTestLog] = null

  override def initDb = {
    super.initDb

    def insertLog(log: MigrationTestLog): Int = {
      transacRun(xa => sql"""
          insert into EventLog (creationDate, principal, eventType, severity, data, causeid)
          values (${log.timestamp}, ${log.principal}, ${log.eventType}, ${log.severity}, ${log.data}, ${log.cause})
        """.update.withUniqueGeneratedKeys[Int]("id").transact(xa))
    }

    // init datas, get the map of ids
    logs5WithId = {
      (DATA_5.data_5.map { case (k,log) =>
        val id = insertLog(log)
        logger.debug(s"Inserting ${k}, id: ${id}")

        (k,log.copy( id = Some(id.toLong) ))
      }).toMap
    }

    //also add some bad event log that should not be migrated (bad/more recent file format)
    insertLog(NoMigrationEventLogs.e1)
    insertLog(NoMigrationEventLogs.e2)
    insertLog(NoMigrationEventLogs.e3)

    logs6WithId = (DATA_6.data_6.map { case (k,log) =>
      log.copy( id = Some(logs5WithId(k).id.get ) ) //actually get so that an exception is throw if there is no ID set
    }).toSeq
  }

  sequential
  //actual tests
  "Event Logs" should {

    "be all found" in {
      val logs = transacRun(xa => migration.findBatch.transact(xa))
      logs.size must beEqualTo(migration.batchSize) and
      forallWhen(logs) {
        case MigrationEventLog(id, eventType, data) =>
          val l = logs5WithId.values.find(x => x.id.get == id).get

          ( l.data must be_==/(data)  ) and
          ( l.eventType must beEqualTo(eventType) )
      }
    }

    "be correctly migrated" in {
      val MigrationProcessResult(migrated, nbBataches) = migration.process.openOrThrowException("Bad migration in test")
      val logs = transacRun(xa => sql"""
        select id, eventtype, creationdate, principal, causeid, severity, data
        from eventlog
      """.query[MigrationTestLog].to[Vector].transact(xa)).filter(log =>
                   //only actually migrated file format
                   try {
                     log.data \\ "@fileFormat" exists { _.text.toInt == 6 }
                   } catch {
                     case e:NumberFormatException => false
                   }
                 )

      (logs.size must beEqualTo(logs6WithId.size)) and
      (logs.size must beEqualTo(migrated)) and
      (nbBataches must beEqualTo(logs.size/migration.batchSize)) and
      forallWhen(logs) {
        case MigrationTestLog(Some(id), eventType, timestamp, principal, cause, severity, data) =>
          val l = logs6WithId.find(x => x.id.get == id).get

          (l.eventType must beEqualTo(eventType)) and
          (l.timestamp must beEqualTo(timestamp)) and
          (l.principal must beEqualTo(principal)) and
          (l.cause must beEqualTo(cause)) and
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
