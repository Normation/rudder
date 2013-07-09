/*
*************************************************************************************
* Copyright 2011 Normation SAS
*************************************************************************************
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Affero General Public License as
* published by the Free Software Foundation, either version 3 of the
* License, or (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU Affero GPL v3, the copyright holders add the following
* Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU Affero GPL v3
* licence, when you create a Related Module, this Related Module is
* not considered as a part of the work and may be distributed under the
* license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Affero General Public License for more details.
*
* You should have received a copy of the GNU Affero General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/agpl.html>.
*
*************************************************************************************
*/

package com.normation.rudder.migration

import com.normation.rudder.repository.jdbc.SquerylConnectionProvider
import java.sql.Driver
import java.sql.DriverManager
import java.io.FileInputStream
import java.util.Properties
import java.sql.Connection
import java.sql.ResultSet
import Migration_2_DATA_Other._
import Migration_2_DATA_Group._
import Migration_2_DATA_Directive._
import Migration_2_DATA_Rule._
import Migration_3_DATA_Other._
import Migration_3_DATA_Group._
import Migration_3_DATA_Directive._
import Migration_3_DATA_Rule._
import net.liftweb.common._
import net.liftweb.util.Helpers
import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner
import org.apache.commons.dbcp.BasicDataSource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import scala.xml.XML
import scala.collection.JavaConverters._
import scala.xml.Elem
import org.specs2.specification.Fragments
import org.specs2.specification.Step
import java.sql.Timestamp


/**
 * Test how the migration run with a Database context
 *
 * Prerequise: A postgres database must be available,
 * with parameters defined in src/test/resources/database.properties.
 * That database should be empty to avoid table name collision.
 */
@RunWith(classOf[JUnitRunner])
class TestManageMigration_2_3 extends DBCommon {

  lazy val migration = new EventLogsMigration_2_3(
      jdbcTemplate = jdbcTemplate
    , individualMigration = new EventLogMigration_2_3(new XmlMigration_2_3())
    , batchSize = 2
  ) {
    override def errorLogger = (f:Failure) => throw new MigEx102(f.messageChain)
  }

  lazy val migrationManagement = new ControlEventLogsMigration_2_3(
          migrationEventLogRepository = new MigrationEventLogRepository(squerylConnectionProvider)
        , Seq(migration)
      )
  val sqlClean = "" //no need to clean temp data table.

  val sqlInit = """
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

CREATE TEMP SEQUENCE MigrationEventLogId START 1;

CREATE TEMP TABLE MigrationEventLog(
  id                  integer PRIMARY KEY DEFAULT nextval('MigrationEventLogId')
, detectionTime       timestamp NOT NULL
, detectedFileFormat  integer
, migrationStartTime  timestamp
, migrationEndTime    timestamp
, migrationFileFormat integer
, description         text
);
    """



  //create the migration request line in DB with the
  //given parameter, and delete it
  def withFileFormatLine[A](
      detectedFileFormat : Long
    , migrationStartTime : Option[Timestamp] = None
    , migrationFileFormat: Option[Long] = None
  )(f:() => A) : A = {
    val id = migrationEventLogRepository.createNewStatusLine(detectedFileFormat).id
    migrationStartTime.foreach { time =>
      migrationEventLogRepository.setMigrationStartTime(id, time)
    }
    migrationFileFormat.foreach { format =>
      migrationEventLogRepository.setMigrationFileFormat(id, format, now)
    }

    val  res = f()

    //delete line
    withConnection { c =>
      c.createStatement.execute("DELETE FROM MigrationEventLog WHERE id=%s".format(id))
    }
    res
  }

  sequential
  //actual tests
  "Migration of event logs from fileformat 2 to 3" should {

    "not be launched if no migration line exists in the DataBase" in {
      migrationManagement.migrate ==== Full(NoMigrationRequested)
    }

    "not be launched if fileFormat is already 3" in {
      val res = withFileFormatLine(3) {
         migrationManagement.migrate
      }
      res ==== Full(MigrationVersionNotHandledHere)
    }

    "not be launched if fileFormat is higher than 3" in {
      val res = withFileFormatLine(42) {
         migrationManagement.migrate
      }
      res ==== Full(MigrationVersionNotHandledHere)
    }

    "not be launched if fileformat is negative" in {
      val res = withFileFormatLine(-1) {
         migrationManagement.migrate
      }
      res ==== Full(MigrationVersionNotHandledHere)
    }

    "not be launched if fileformat is 0" in {
      val res = withFileFormatLine(0) {
         migrationManagement.migrate
      }
      res ==== Full(MigrationVersionNotHandledHere)
    }

    "not be launched if fileformat is 1" in {
      val res = withFileFormatLine(1) {
         migrationManagement.migrate
      }
      res ==== Full(MigrationVersionNotHandledHere)
    }

    "be launched if fileformat is 2, event if marked finished" in {
      val res = withFileFormatLine(2, Some(now), Some(2)) {
         migrationManagement.migrate
      }
      res ==== Full(MigrationSuccess(0))
    }

    "be launched if fileformat is 2" in {
      val res = withFileFormatLine(2) {
         migrationManagement.migrate
      }
      res ==== Full(MigrationSuccess(0))
    }

  }



}
