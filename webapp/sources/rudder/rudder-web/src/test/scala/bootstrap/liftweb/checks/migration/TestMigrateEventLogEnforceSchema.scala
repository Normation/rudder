/*
 *************************************************************************************
 * Copyright 2023 Normation SAS
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

package bootstrap.liftweb.checks.migration

import com.normation.rudder.db.DBCommon
import com.normation.rudder.db.Doobie
import com.normation.zio.UnsafeRun
import doobie.specs2.IOChecker
import doobie.syntax.all.*
import doobie.util.fragment
import doobie.util.fragments.whereAnd
import doobie.util.transactor.Transactor
import org.junit.runner.RunWith
import org.specs2.runner.JUnitRunner
import org.specs2.specification.core.Fragments
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import zio.*
import zio.interop.catz.*

@RunWith(classOf[JUnitRunner])
class TestMigrateEventLogEnforceSchema extends DBCommon with IOChecker {
  import TestMigrateEventLogEnforceSchema.*

  private lazy val migrateEventLogRepository = new MigrateEventLogEnforceSchemaTempTable(doobie)

  override def transactor: Transactor[cats.effect.IO] = doobie.xaio

  override def initDb(): Unit = {
    super.initDb()
    doobie.transactRunEither(previousSchemaDDL.update.run.transact(_)) match {
      case Right(_) => ()
      case Left(ex) => throw ex
    }
  }

  override def cleanDb(): Unit = {
    doobie.transactRunEither(
      sql"""
        DROP TABLE IF EXISTS $tempTable;
        DROP SEQUENCE IF EXISTS eventLogIdSeq_temp;
        DROP INDEX IF EXISTS eventType_idx_temp;
        DROP INDEX IF EXISTS creationDate_idx_temp;
        DROP INDEX IF EXISTS eventlog_fileFormat_idx_temp;
      """.update.run.transact(_)
    ) match {
      case Right(_) => ()
      case Left(ex) => throw ex
    }
  }

  // The previous schema, with the renamed table for this test
  // We need to know for sure the initial state and the final state of the migrated table,
  // so we define and use the previous schema without conflicting with the current one (with all values renamed)
  private val previousSchemaDDL = sql"""
    CREATE SEQUENCE eventLogIdSeq_temp START 1;
    
    CREATE TABLE $tempTable (
      id             integer PRIMARY KEY  DEFAULT nextval('eventLogIdSeq_temp')
    , creationDate   timestamp with time zone NOT NULL DEFAULT 'now'
    , severity       integer
    , causeId        integer
    , modificationId text
    , principal      text
    , reason         text
    , eventType      text
    , data           xml
    );
    
    CREATE INDEX eventType_idx_temp ON $tempTable (eventType);
    CREATE INDEX creationDate_idx_temp ON $tempTable (creationDate);
    CREATE INDEX eventlog_fileFormat_idx_temp ON $tempTable (((((xpath('/entry//@fileFormat',data))[1])::text)));
  """

  sequential

  "MigrateEventLogEnforceSchema" should {

    // we need to also make usage of 'migrateEventLogRepository' lazy (a 'check' evaluates eagerly)
    "check migration queries" in {
      // check that the alter statements are made on existing table and column
      if (doDatabaseConnection) {
        check(migrateEventLogRepository.migrateColumnStatement(fr"eventtype", migrateEventLogRepository.defaultEventType))
        check(migrateEventLogRepository.migrateColumnStatement(fr"principal", migrateEventLogRepository.defaultPrincipal))
        check(migrateEventLogRepository.migrateColumnStatement(fr"severity", migrateEventLogRepository.defaultSeverity))
        check(migrateEventLogRepository.migrateColumnStatement(fr"data", migrateEventLogRepository.defaultData))
      } else Fragments.empty
    }

    "migrate all columns successfully" in {

      migrateEventLogRepository.migrateAsync.runNow.join.runNow // await for the migration before running assertions test

      // check that all migrations are eventually applied even in async mode
      doobie.transactRunEither(
        (sql"""
          SELECT DISTINCT column_name, is_nullable
          FROM INFORMATION_SCHEMA.COLUMNS
        """ ++ whereAnd(
          fr"table_name = $tempTableName",
          fr"column_name in ('eventtype', 'principal', 'severity', 'data')"
        )).query[(String, String)]
          .to[List]
          .transact(_)
      ) match {
        case Right(res) =>
          res must containTheSameElementsAs( // is_nullable is 'NO' for every column
            List("eventtype", "principal", "severity", "data").map((_, "NO"))
          ).eventually(10, 100.millis.asScala)
        case Left(ex)   =>
          ko(
            s"The migration of 'EventLog' table does not add NOT NULL constraint to all columns with error : ${ex.getMessage}"
          )
      }
    }

    "catch all migration errors" in {
      lazy val migrateEventLog =
        new MigrateEventLogEnforceSchemaTempTable(doobie, Some(ZIO.fail(new Exception("some database error"))))

      Try(
        migrateEventLog.migrateAsync.runNow.join // await for the fiber to complete
          .runNow
      ) match {
        case Failure(_)  => ko("The parent program should not fail even if the fiber failed")
        case Success(()) => ok("The parent program continued to run despite the migration fiber error")
      }

    }
  }
}

object TestMigrateEventLogEnforceSchema {
  // this table is setup and tear down for the lifetime of this test execution
  private val tempTableName = "eventlog_migratetest"
  private val tempTable     = fragment.Fragment.const(tempTableName)

  private class MigrateEventLogEnforceSchemaTempTable(doobie: Doobie, overrideEffect: Option[Task[Unit]] = None)
      extends MigrateEventLogEnforceSchema(doobie) {
    override def tableName = tempTableName
    override def migrationEffect(xa: Transactor[Task]): Task[Unit] = {
      val default = super.migrationEffect(xa)
      overrideEffect.getOrElse(default)
    }
  }
}
