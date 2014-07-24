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

import java.sql._
import java.util.Properties
import scala.xml.XML
import org.specs2.mutable.Specification
import org.specs2.mutable.Tags
import org.specs2.specification.Fragments
import org.specs2.specification.Step
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import com.normation.rudder.repository.jdbc.RudderDatasourceProvider
import com.normation.rudder.repository.jdbc.SquerylConnectionProvider
import net.liftweb.common.Loggable
import scala.io.Source



/**
 * Here we manage all the initialisation of services and database
 * state for the full example (after/before class).
 */
trait DBCommon extends Specification with Loggable with Tags {
  skipAllIf(System.getProperty("test.postgres", "true").toBoolean != true)

  /**
   * By default, init schema with the Rudder schema and tables, safe that
   * everything is temporary
   */
  def sqlInit : String = {
    val is = this.getClass().getClassLoader().getResourceAsStream("reportsSchema.sql")
    val sqlText = Source.fromInputStream(is).getLines.toSeq.map(s =>
      s
      //using toLowerCase is safer, it will always replace create table by a temp one,
      //but it also mean that we will not know when we won't be strict with ourselves
         .toLowerCase
         .replaceAll("create table", "create temp table")
         .replaceAll("create sequence", "create temp sequence")
         .replaceAll("alter database rudder", "alter database test")
    ).mkString("\n")
    is.close()

    sqlText
  }


  /**
   * By default, clean does nothing:
   * - cleaning at the end is not that reliable
   * - and everything is temporary by default.
   *
   * Just let the possibility to do fancy things.
   */
  def sqlClean : String = ""

  override def map(fs: =>Fragments) = (
      Step(initDb) ^ fs ^ Step(cleanDb)
  )

  def initDb() = {
    if(sqlInit.trim.size > 0) jdbcTemplate.execute(sqlInit)
  }

  def cleanDb() = {
    if(sqlClean.trim.size > 0) jdbcTemplate.execute(sqlClean)

    dataSource.close
  }

  def now = new Timestamp(System.currentTimeMillis)


  //execute something on the connection before commiting and closing it
  def withConnection[A](f:Connection => A) : A = {
    val c = dataSource.getConnection
    c.setAutoCommit(false)
    val res = f(c)
    c.commit
    c.close
    res
  }

  //////////////
  // services //
  //////////////

  lazy val properties = {
    val p = new Properties()
    val in = this.getClass.getClassLoader.getResourceAsStream("database.properties")
    p.load(in)
    in.close
    p
  }

  // init DB and repositories
  lazy val dataSource = {
    val config = new RudderDatasourceProvider(
        properties.getProperty("jdbc.driverClassName")
      , properties.getProperty("jdbc.url")
      , properties.getProperty("jdbc.username")
      , properties.getProperty("jdbc.password")
        //MUST BE '1', else temp table desapear between the two connections in
        //really funny ways
      , 1
    )
    config.datasource
  }

  //a row mapper for TestLog
  lazy val testLogRowMapper = new RowMapper[MigrationTestLog] {
    override def mapRow(rs:ResultSet, rowNum:Int) : MigrationTestLog = {
      MigrationTestLog(
        id        = Some(rs.getLong("id"))
      , principal = rs.getString("principal")
      , eventType = rs.getString("eventType")
      , timestamp = rs.getTimestamp("creationDate")
      , cause     = if(rs.getInt("causeId")>0) Some(rs.getInt("causeId"))
                    else None
      , severity  = rs.getInt("severity")
      , data      = XML.load(rs.getSQLXML("data").getBinaryStream())
      )
    }
  }

  lazy val successLogger : Seq[MigrableEntity] => Unit = { seq =>
    logger.debug("Log correctly migrated (id,type): " + (seq.map { case l =>
      "(%s, %s)".format(l.id, l.data)
    }).mkString(", ") )
  }

  lazy val squerylConnectionProvider = new SquerylConnectionProvider(dataSource)

  lazy val jdbcTemplate = new JdbcTemplate(dataSource)



  lazy val migrationEventLogRepository = new MigrationEventLogRepository(squerylConnectionProvider)


}