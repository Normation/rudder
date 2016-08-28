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

import java.sql.ResultSet
import java.util.concurrent.TimeUnit

import scala.concurrent.Await
import scala.concurrent.duration.Duration

import com.normation.BoxSpecMatcher

import org.joda.time.DateTime
import org.junit.runner.RunWith
import org.specs2.runner.JUnitRunner
import org.springframework.jdbc.core.RowCallbackHandler

import net.liftweb.common.Failure

/**
 * Test how the migration run with a Database context
 *
 * Prerequise: A postgres database must be available,
 * with parameters defined in src/test/resources/database.properties.
 * That database should be empty to avoid table name collision.
 */
@RunWith(classOf[JUnitRunner])
class TestManageMigration_2_3 extends DBCommon with BoxSpecMatcher {

  case class MigEx102(msg:String) extends Exception(msg)

  lazy val migration = new EventLogsMigration_2_3(
      jdbcTemplate = jdbcTemplate
    , individualMigration = new EventLogMigration_2_3(new XmlMigration_2_3())
    , batchSize = 2
  ) {
    override def errorLogger = (f:Failure) => throw new MigEx102(f.messageChain)
  }

  lazy val migrationManagement = new ControlEventLogsMigration_2_3(
          migrationEventLogRepository = migrationEventLogRepository
        , Seq(migration)
      )

  if(doDatabaseConnection) {
    jdbcTemplate.query("SELECT * FROM pg_catalog.pg_tables ", new RowCallbackHandler(){
        def processRow(rs: ResultSet) = {
            val num = rs.getMetaData().getColumnCount()
            println((for(i <- 1 to num) yield {
              rs.getString(i)
            }).mkString(", "))
        }
    })
  }

  //create the migration request line in DB with the
  //given parameter, and delete it
  def withFileFormatLine[A](
      detectedFileFormat : Long
    , migrationStartTime : Option[DateTime] = None
    , migrationFileFormat: Option[Long] = None
  )(f:() => A) : A = {
    val Some(id) = Await.result(migrationEventLogRepository.createNewStatusLine(detectedFileFormat), Duration(500, TimeUnit.MILLISECONDS)).map( _.id).get
    migrationStartTime.foreach { time =>
      migrationEventLogRepository.setMigrationStartTime(id, time)
    }
    migrationFileFormat.foreach { format =>
      migrationEventLogRepository.setMigrationFileFormat(id, format, now)
    }

    val  res = f()

    //delete line
    withConnection { c =>
      c.createStatement.execute(s"DELETE FROM MigrationEventLog WHERE id=${id}")
    }
    res
  }

  sequential
  //actual tests
  "Migration of event logs from fileformat 2 to 3" should {

    "not be launched if no migration line exists in the DataBase" in {
      migrationManagement.migrate mustFullEq(NoMigrationRequested)
    }

    "not be launched if fileFormat is already 3" in {
      val res = withFileFormatLine(3) {
         migrationManagement.migrate
      }
      res mustFullEq(MigrationVersionNotHandledHere)
    }

    "not be launched if fileFormat is higher than 3" in {
      val res = withFileFormatLine(42) {
         migrationManagement.migrate
      }
      res mustFullEq(MigrationVersionNotHandledHere)
    }

    "not be launched if fileformat is negative" in {
      val res = withFileFormatLine(-1) {
         migrationManagement.migrate
      }
      res mustFullEq(MigrationVersionNotSupported)
    }

    "not be launched if fileformat is 0" in {
      val res = withFileFormatLine(0) {
         migrationManagement.migrate
      }
      res mustFullEq(MigrationVersionNotSupported)
    }

    "not be launched if fileformat is 1" in {
      val res = withFileFormatLine(1) {
         migrationManagement.migrate
      }
      res mustFullEq(MigrationVersionNotSupported)
    }

    "be launched if fileformat is 2, event if marked finished" in {
      val res = withFileFormatLine(2, Some(now), Some(2)) {
         migrationManagement.migrate
      }
      res mustFullEq(MigrationSuccess(0))
    }

    "be launched if fileformat is 2" in {
      val res = withFileFormatLine(2) {
         migrationManagement.migrate
      }
      res mustFullEq(MigrationSuccess(0))
    }

  }


}
