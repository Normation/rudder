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


import com.normation.BoxSpecMatcher

import org.joda.time.DateTime
import org.junit.runner.RunWith
import org.specs2.runner.JUnitRunner

import com.normation.rudder.db.DBCommon
import doobie.implicits._

import com.normation.BoxSpecMatcher

/**
 * Test how the migration run with a Database context
 *
 * Prerequise: A postgres database must be available,
 * with parameters defined in src/test/resources/database.properties.
 * That database should be empty to avoid table name collision.
 */
@RunWith(classOf[JUnitRunner])
class TestManageMigration_5_6 extends DBCommon with BoxSpecMatcher {

  lazy val migrationManagement = new ControlXmlFileFormatMigration_5_6(
          migrationEventLogRepository = new MigrationEventLogRepository(doobie)
        , doobie
        , None
        , 2
      )

  //create the migration request line in DB with the
  //given parameter, and delete it
  def withFileFormatLine[A](
      detectedFileFormat : Long
    , migrationStartTime : Option[DateTime] = None
    , migrationFileFormat: Option[Long] = None
  )(f:() => A) : A = {
    val id = migrationEventLogRepository.createNewStatusLine(detectedFileFormat).map( _.id).toOption.get //because test
    migrationStartTime.foreach { time =>
      migrationEventLogRepository.setMigrationStartTime(id, time)
    }
    migrationFileFormat.foreach { format =>
      migrationEventLogRepository.setMigrationFileFormat(id, format, now)
    }

    val  res = f()

    //delete line
    doobie.transactRun(xa => sql"DELETE FROM MigrationEventLog WHERE id=${id}".update.run.transact(xa))

    res
  }

  sequential
  //actual tests
  "Migration of event logs from fileformat 5 to 6" should {

    "not be launched if no migration line exists in the DataBase" in {
      migrationManagement.migrate mustFullEq(NoMigrationRequested)
    }

    "not be launched if fileFormat is already 6" in {
      val res = withFileFormatLine(6) {
         () => migrationManagement.migrate
      }
      res mustFullEq(MigrationVersionNotHandledHere)
    }

    "not be launched if fileFormat is higher than 6" in {
      val res = withFileFormatLine(42) {
         () => migrationManagement.migrate
      }
      res mustFullEq(MigrationVersionNotHandledHere)
    }

    "not be launched if fileformat is negative" in {
      val res = withFileFormatLine(-1) {
         () => migrationManagement.migrate
      }
      res mustFullEq(MigrationVersionNotSupported)
    }

    for(i <- 0 to 4) {
      s"not be launched if fileformat is $i" in {
        val res = withFileFormatLine(i.toLong) {
           () => migrationManagement.migrate
        }
        res mustFullEq(MigrationVersionNotSupported)
      }
    }

    "be launched if fileformat is 5, event if marked finished" in {
      val res = withFileFormatLine(5, Some(now), Some(5)) {
         () => migrationManagement.migrate
      }
      res mustFullEq(MigrationSuccess(0))
    }

    "be launched if fileformat is 5" in {
      val res = withFileFormatLine(5) {
         () => migrationManagement.migrate
      }
      res mustFullEq(MigrationSuccess(0))
    }
  }
}
