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

import com.normation.rudder.db.DB

import org.joda.time.DateTime
import com.normation.rudder.db.Doobie

import doobie.implicits._
import com.normation.rudder.db.Doobie._

class MigrationEventLogRepository(val db: Doobie) {

  import db._

  val table = "migrationeventlog"

  /**
   * Retrieve the last version of the EventLog fileFormat as seen by the
   * the SQL script.
   * If the database does not exist or no line are present, return none.
   */
  def getLastDetectionLine: Either[Throwable, Option[DB.MigrationEventLog[Long]]] = {
    val sql = sql"""select id, detectiontime, detectedfileformat, migrationstarttime, migrationendtime, migrationfileformat, description
                    from migrationeventlog order by id desc limit 1""".query[DB.MigrationEventLog[Long]].option
    sql.transact(xa).attempt.unsafeRunSync
  }

  /**
   * Update the corresponding detection line with
   * the starting time of the migration (from Rudder)
   */
  def setMigrationStartTime(id: Long, startTime: DateTime) : Either[Throwable,Int] = {
    val sql = sql"""update migrationeventlog set migrationstarttime = ${startTime} where id=${id}""".update
    sql.run.transact(xa).attempt.unsafeRunSync
  }

  /**
   * Update the corresponding detection line with the new,
   * up-to-date file format.
   */
  def setMigrationFileFormat(id: Long, fileFormat: Long, endTime: DateTime) : Either[Throwable, Int] = {
    val sql = sql"""update migrationeventlog set migrationfileformat=${fileFormat}, migrationendtime=${endTime} where id=${id}""".update
    sql.run.transact(xa).attempt.unsafeRunSync
  }

  /**
   * create a new status line with a timestamp of now and the given
   * detectedFileFormat.
   */
  def createNewStatusLine(fileFormat: Long, description: Option[String] = None) : Either[Throwable, DB.MigrationEventLog[Long]] = {
    val now = DateTime.now
    val sql = sql"""insert into migrationeventlog (detectiontime, detectedfileformat, description) values (${now}, ${fileFormat}, ${description})""".update
    sql.withUniqueGeneratedKeys[Long]("id").transact(xa).attempt.unsafeRunSync.map(id =>
      DB.MigrationEventLog[Long](id, now, fileFormat, None, None, None, description)
    )
  }
}



