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

import java.sql.ResultSet
import java.sql.Timestamp
import java.util.Calendar

import scala.xml.Elem
import scala.xml.XML

import org.springframework.jdbc.core.RowMapper
import org.squeryl.KeyedEntity
import org.squeryl.PrimitiveTypeMode._
import org.squeryl.Schema
import org.squeryl.annotations.Column

import com.normation.rudder.repository.jdbc.SquerylConnectionProvider


/**
 * ////////////////////////////////////////
 * DataBase
 * ////////////////////////////////////////
 */
object MigrationEventLogTable extends Schema {

  val migrationEventLog = table[SerializedMigrationEventLog]("migrationeventlog")

  on(migrationEventLog)(t => declare(
      t.id.is(autoIncremented("migrationeventlogid"), primaryKey))
  )
}

case class SerializedMigrationEventLog(
    @Column("detectiontime")       detectionTime      : Timestamp
  , @Column("detectedfileformat")  detectedFileFormat : Long
  , @Column("migrationstarttime")  migrationStartTime : Option[Timestamp]
  , @Column("migrationendtime")    migrationEndTime   : Option[Timestamp]
  , @Column("migrationfileformat") migrationFileFormat: Option[Long]
  , @Column("description")         description        : Option[String]
) extends KeyedEntity[Long] {

  @Column("id")
  val id = 0L

  //for squery to know about option type
  def this() = this(new Timestamp(System.currentTimeMillis), 0, Some(new Timestamp(System.currentTimeMillis)), Some(new Timestamp(System.currentTimeMillis)), Some(0L), Some(""))
}

class MigrationEventLogRepository(squerylConnectionProvider : SquerylConnectionProvider) {

  /**
   * Retrieve the last version of the EventLog fileFormat as seen by the
   * the SQL script.
   * If the database does not exist or no line are present, retrun none.
   */
  def getLastDetectionLine: Option[SerializedMigrationEventLog] = {
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

sealed trait MigrationStatus
final case object NoMigrationRequested extends MigrationStatus
final case object MigrationVersionNotHandledHere extends MigrationStatus
final case object MigrationVersionNotSupported extends MigrationStatus
final case class  MigrationSuccess(migrated:Int) extends MigrationStatus


trait MigrableEntity {
  def id: Long
  def data: Elem
}

case class MigrationEventLog(
    id       : Long
  , eventType: String
  , data     : Elem
) extends MigrableEntity

object MigrationEventLogMapper extends RowMapper[MigrationEventLog] {
  override def mapRow(rs : ResultSet, rowNum: Int) : MigrationEventLog = {
    MigrationEventLog(
        id          = rs.getLong("id")
      , eventType   = rs.getString("eventType")
      , data        = XML.load(rs.getSQLXML("data").getBinaryStream)
    )
  }
}


case class MigrationChangeRequest(
    id  : Long
  , name: String
  , data: Elem
) extends MigrableEntity

object MigrationChangeRequestMapper extends RowMapper[MigrationChangeRequest] {
  override def mapRow(rs : ResultSet, rowNum: Int) : MigrationChangeRequest = {
    MigrationChangeRequest(
        id   = rs.getLong("id")
      , name = rs.getString("name")
      , data = XML.load(rs.getSQLXML("content").getBinaryStream)
    )
  }
}

