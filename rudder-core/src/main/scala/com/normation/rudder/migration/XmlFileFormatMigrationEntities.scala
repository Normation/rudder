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

import scala.concurrent.Future
import scala.util.Try

import com.normation.rudder.db.DB
import com.normation.rudder.db.SlickSchema

import org.joda.time.DateTime


class MigrationEventLogRepository(val schema: SlickSchema) {

  import schema.api._

  /**
   * Retrieve the last version of the EventLog fileFormat as seen by the
   * the SQL script.
   * If the database does not exist or no line are present, return none.
   */
  def getLastDetectionLine: Future[Try[Option[DB.MigrationEventLog]]] = {
    val query = schema.migrationEventLog.sortBy( _.id.desc ).take(1)
    val action = query.result.headOption
    schema.db.run(action.asTry)
  }

  /**
   * Update the corresponding detection line with
   * the starting time of the migration (from Rudder)
   */
  def setMigrationStartTime(id: Long, startTime: DateTime) : Future[Try[Int]] = {
    val query = for {
      x <- schema.migrationEventLog
      if(x.id === id )
    } yield {
      x.migrationStartTime
    }

    val action = query.update(Some(startTime))
    schema.db.run(action.asTry)
  }

  /**
   * Update the corresponding detection line with the new,
   * up-to-date file format.
   */
  def setMigrationFileFormat(id: Long, fileFormat: Long, endTime: DateTime) : Future[Try[Int]] = {
    val query = for {
      x <- schema.migrationEventLog
      if(x.id === id )
    } yield {
      (x.migrationFileFormat, x.migrationEndTime)
    }

    val action = query.update((Some(fileFormat), Some(endTime)))
    schema.db.run(action.asTry)
  }

  /**
   * create a new status line with a timestamp of now and the given
   * detectedFileFormat.
   */
  def createNewStatusLine(fileFormat: Long, description: Option[String] = None) : Future[Try[DB.MigrationEventLog]] = {
    val migrationEventLog = DB.MigrationEventLog(None, DateTime.now, fileFormat, None, None, None, description)
    val action = (
        schema.migrationEventLog
        returning(schema.migrationEventLog.map( _.id))
        into ((event, id) => event.copy(id=Some(id)))
    ) += migrationEventLog

    schema.db.run(action.asTry)
  }
}



