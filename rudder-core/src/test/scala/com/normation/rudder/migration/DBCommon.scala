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
 * Here we manage all the initialisation of services and database
 * state for the full example (after/before class).
 */
trait DBCommon extends Specification with Loggable with Tags {
  skipAllIf(System.getProperty("test.postgres", "true").toBoolean != true)

  def sqlClean : String
  def sqlInit : String

  override def map(fs: =>Fragments) = (
      Step(initDb)
    ^ fs
    ^ Step(cleanDb)
  )

  def initDb = {
    if(sqlInit.trim.size > 0) jdbcTemplate.execute(sqlInit)
  }

  def cleanDb = {
    if(sqlClean.trim.size > 0) jdbcTemplate.execute(sqlClean)
  }

  def now = new Timestamp(System.currentTimeMillis)


  //execute something on the connection before commiting and closing it
  def withConnection[A](f:Connection => A) : A = {
    val c = dataSource.getConnection
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
    val driver = properties.getProperty("jdbc.driverClassName")
    Class.forName(driver);
    val pool = new BasicDataSource()
    pool.setDriverClassName(driver)
    pool.setUrl(properties.getProperty("jdbc.url"))
    pool.setUsername(properties.getProperty("jdbc.username"))
    pool.setPassword(properties.getProperty("jdbc.password"))

    /* test connection */
    val connection = pool.getConnection()
    connection.close()

    pool
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