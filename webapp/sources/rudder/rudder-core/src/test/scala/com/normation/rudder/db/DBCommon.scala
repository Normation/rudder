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

package com.normation.rudder.db

import java.util.Properties

import scala.io.Source
import com.normation.rudder.db.Doobie._
import com.normation.rudder.migration.MigrableEntity
import com.normation.rudder.migration.MigrationEventLogRepository
import com.normation.rudder.repository.jdbc.RudderDatasourceProvider
import org.specs2.mutable.Specification
import org.specs2.specification.BeforeAfterAll
import net.liftweb.common.Loggable
import doobie._
import doobie.implicits._
import cats.implicits._
import doobie.implicits.javasql._
import com.normation.rudder.migration.MigrationTestLog
import org.joda.time.DateTime
import zio.interop.catz._
import zio._

/**
 * Here we manage all the initialisation of services and database
 * state for the full example (after/before class).
 */
trait DBCommon extends Specification with Loggable with BeforeAfterAll {

  val now = DateTime.now

  logger.info("""Set JAVA property 'test.postgres' to false to ignore that test, for example from maven with: mvn -DargLine="-Dtest.postgres=false" test""")

  val doDatabaseConnection = System.getProperty("test.postgres", "").toLowerCase match {
    case "true" | "1" => true
    case _ => false
  }
  skipAllIf(!doDatabaseConnection)

  /**
   * By default, init schema with the Rudder schema and tables, safe that
   * everything is temporary
   */
  def sqlInit : String = {
    val is = this.getClass().getClassLoader().getResourceAsStream("reportsSchema.sql")
    val sqlText = Source.fromInputStream(is).getLines.toSeq
      .filterNot{ line => val s = line.trim(); s.isEmpty || s.startsWith("/*") || s.startsWith("*") }
      .map(s =>
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

  override def beforeAll(): Unit = initDb
  override def afterAll(): Unit = cleanDb

  def initDb() = {
    if(sqlInit.trim.size > 0) {
      // Postgres'JDBC driver just accept multiple statement
      // in one query. No need to try to split ";" etc.
      doobie.transactRunEither(xa => Update0(sqlInit, None).run.transact(xa)) match {
        case Right(x) => x
        case Left(ex) => throw ex
      }
    }
  }

  def cleanDb() = {
    if(sqlClean.trim.size > 0) doobie.transactRunEither(xa => Update0(sqlClean, None).run.transact(xa)) match {
      case Right(x) => x
      case Left(ex) => throw ex
    }

    dataSource.close
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
        //MUST BE '1', else temp table disappear between the two connections in
        //really funny ways
      , 1
    )
    config.datasource
  }


  lazy val successLogger : Seq[MigrableEntity] => Unit = { seq =>
    logger.debug("Log correctly migrated (id,type): " + (seq.map { case l =>
      "(%s, %s)".format(l.id, l.data)
    }).mkString(", ") )
  }

  lazy val doobie = new Doobie(dataSource)
  def transacRun[T](query: Transactor[Task] => Task[T]) = {
    doobie.transactRunEither(xa => query(xa)) match {
        case Right(x) => x
        case Left(ex) => throw ex
      }
  }
  lazy val migrationEventLogRepository = new MigrationEventLogRepository(doobie)

  def insertLog(log: MigrationTestLog): Int = {
  doobie.transactRunEither(xa => sql"""
      insert into EventLog (creationDate, principal, eventType, severity, data, causeid)
      values (${log.timestamp}, ${log.principal}, ${log.eventType}, ${log.severity}, ${log.data}, ${log.cause})
    """.update.withUniqueGeneratedKeys[Int]("id").transact(xa)) match {
        case Right(x) => x
        case Left(ex) => throw ex
      }
  }
}
