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

import java.sql.Timestamp
import java.util.Calendar

import scala.xml.Elem
import com.normation.rudder.db.SlickSchema
import com.normation.rudder.db.DB


class MigrationEventLogRepository(db: SlickSchema) {

  /**
   * Retrieve the last version of the EventLog fileFormat as seen by the
   * the SQL script.
   * If the database does not exist or no line are present, return none.
   */
  def getLastDetectionLine: Option[DB.MigrationEventLog] = {
    squerylConnectionProvider.ourSession {
      val q = from(MigrationEventLogTable.migrationEventLog)(line =>
        select(line)
        orderBy(line.id.desc)
      )

      q.page(0,1).toList.headOption
    }
  }

  /**
   * Update the corresponding detection line with
   * the starting time of the migration (from Rudder)
   */
  def setMigrationStartTime(id: Long, startTime: Timestamp) : Int = {
    squerylConnectionProvider.ourTransaction {
      update(MigrationEventLogTable.migrationEventLog)(l =>
        where(l.id === id)
        set(l.migrationStartTime := Some(startTime))
      )
    }
  }

  /**
   * Update the corresponding detection line with the new,
   * up-to-date file format.
   */
  def setMigrationFileFormat(id: Long, fileFormat:Long, endTime:Timestamp) : Int = {
    squerylConnectionProvider.ourTransaction {
      update(MigrationEventLogTable.migrationEventLog)(l =>
        where(l.id === id)
        set(
            l.migrationEndTime := Some(endTime)
          , l.migrationFileFormat := Some(fileFormat)
        )
      )
    }
  }

  /**
   * create a new status line with a timestamp of now and the given
   * detectedFileFormat.
   */
  def createNewStatusLine(fileFormat:Long, description:Option[String] = None) : SerializedMigrationEventLog = {
    squerylConnectionProvider.ourTransaction {
      MigrationEventLogTable.migrationEventLog.insert(
         SerializedMigrationEventLog(new Timestamp(Calendar.getInstance.getTime.getTime), fileFormat, None, None, None, description)
      )
    }
  }
}



