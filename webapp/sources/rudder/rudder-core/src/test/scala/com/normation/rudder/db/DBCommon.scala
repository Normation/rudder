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

import com.normation.rudder.repository.jdbc.RudderDatasourceProvider
import doobie.*
import doobie.implicits.*
import java.io.Closeable
import java.util.Properties
import javax.sql.DataSource
import net.liftweb.common.Loggable
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.specs2.mutable.Specification
import org.specs2.specification.BeforeAfterAll
import scala.io.Source
import zio.*
import zio.interop.catz.*

/**
 * Here we manage all the initialisation of services and database
 * state for the full example (after/before class).
 */
trait DBCommon extends Specification with Loggable with BeforeAfterAll {

  lazy val now = DateTime.now(DateTimeZone.UTC)

  lazy val doDatabaseConnection: Boolean = java.lang.System.getProperty("test.postgres", "").toLowerCase match {
    case "true" | "1" => true
    case _            => false
  }
  skipAllIf(!doDatabaseConnection)

  /**
   * By default, init schema with the Rudder schema and tables, safe that
   * everything is temporary
   */
  def sqlInit: String = {
    val is              = this.getClass().getClassLoader().getResourceAsStream("reportsSchema.sql")
    // This is a way to create and use all DDL under a schema, that will be dropped at the end of tests
    val setTestNamepace = {
      """
        |SET search_path TO pg_temp
        |""".stripMargin
    }
    val sqlText         = Source
      .fromInputStream(is)
      .getLines()
      .toSeq
      .filterNot { line =>
        val s = line.trim(); s.isEmpty || s.startsWith("/*") || s.startsWith("*")
      }
      .map(s => {
        // we work on a "test" database which needs to be created, and which is cleaned (see clean methods)
        s.replaceAll("alter database rudder", "alter database test")
      })
      .mkString(s"${setTestNamepace};\n", "\n", "")
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
  def sqlClean: String = ""

  override def beforeAll(): Unit = {
    logger.info(
      """Set JAVA property 'test.postgres' to false to ignore that test, for example from maven with: mvn -DargLine="-Dtest.postgres=false" test"""
    )
    initDb()
  }
  override def afterAll():  Unit = cleanDb()

  def initDb(): Unit = {
    if (sqlInit.trim.size > 0) {
      // Postgres'JDBC driver just accept multiple statement
      // in one query. No need to try to split ";" etc.
      doobie.transactRunEither(xa => Update0(sqlInit, None).run.transact(xa)) match {
        case Right(_) => ()
        case Left(ex) => throw ex
      }
    }
  }

  final def cleanDb(): Unit = {
    doobie.transactRunEither(xa => {
      ZIO.when(sqlClean.trim.size > 0)(
        Update0(sqlClean, None).run.transact(xa)
      )
    }) match {
      case Right(x) => ()
      case Left(ex) => throw ex
    }

    dataSource.close
  }

  //////////////
  // services //
  //////////////

  lazy val properties: Properties = {
    val p  = new Properties()
    val in = this.getClass.getClassLoader.getResourceAsStream("database.properties")
    p.load(in)
    in.close
    p
  }

  // init DB and repositories
  lazy val dataSource: DataSource & Closeable = {
    val config = new RudderDatasourceProvider(
      properties.getProperty("jdbc.driverClassName"),
      properties.getProperty("jdbc.url"),
      properties.getProperty("jdbc.username"),
      properties.getProperty("jdbc.password"),
      // maxPoolSize MUST BE '1', else temp table disappear between the two connections in
      // really funny ways
      1,
      250.millis.asScala
    )
    config.datasource
  }

  final lazy val doobie = new DoobieIO(dataSource)
  def transacRun[T](query: Transactor[Task] => Task[T]): T = {
    doobie.transactRunEither(xa => query(xa)) match {
      case Right(x) => x
      case Left(ex) => throw ex
    }
  }
}
