/*
 *************************************************************************************
 * Copyright 2024 Normation SAS
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
import com.normation.zio.UnsafeRun
import doobie.*
import doobie.specs2.IOChecker
import doobie.syntax.all.*
import doobie.util.fragments.whereAnd
import doobie.util.transactor.Transactor
import org.junit.runner.RunWith
import org.specs2.runner.JUnitRunner
import org.specs2.specification.core.Fragments
import zio.*
import zio.interop.catz.*

@RunWith(classOf[JUnitRunner])
class TestMigrateTableReportsExecution extends DBCommon with IOChecker {
  import CheckTableReportsExecutionTz.*

  val testTable = "reportsexecutiontest"

  private lazy val checkTableReportsExecutionTz = new CheckTableReportsExecutionTz(doobie)

  override def transactor: Transactor[cats.effect.IO] = doobie.xaio

  override def initDb(): Unit = {
    super.initDb()
    doobie.transactRunEither(previousSchemaDDL.update.run.transact(_)) match {
      case Right(_) => ()
      case Left(ex) => throw ex
    }
  }

  // The previous schema, with the renamed table for this test
  // We need to know for sure the initial state and the final state of the migrated table,
  // so we define and use the previous schema without conflicting with the current one (with all values renamed)
  private val previousSchemaDDL = sql"""
CREATE TABLE reportsexecutiontest (
  nodeId       text NOT NULL
, date         timestamp with time zone NOT NULL
, nodeConfigId text
, insertionId  bigint
, insertiondate timestamp default now()
, compliancecomputationdate timestamp
, PRIMARY KEY(nodeId, date)
);

CREATE INDEX reportsexecution_test_date_idx ON reportsexecutiontest (date);
CREATE INDEX reportsexecution_test_nodeid_nodeconfigid_idx ON reportsexecutiontest (nodeId, nodeConfigId);
CREATE INDEX reportsexecution_test_uncomputedrun_idx on reportsexecutiontest (compliancecomputationdate) where compliancecomputationdate IS NULL;
"""

  sequential

  "MigrateEventLogEnforceSchema" should {

    "check migration queries" in {
      // check that the alter statements are made on existing table and column
      if (doDatabaseConnection) {
        check(query(testTable, insertionDateColumn))
        check(query(testTable, complianceComputationDateColumn))
      } else Fragments.empty
    }

    "migrate all columns successfully" in {

      checkTableReportsExecutionTz.migrationProg(testTable).runNow // await for the migration before running assertions test

      val sql = {
        fr"""SELECT DISTINCT column_name, data_type FROM INFORMATION_SCHEMA.COLUMNS
          """ ++ whereAnd(
          fr"table_name = " ++ Fragment.const("'" + testTable + "'"),
          fr"column_name in (" ++ Fragment.const("'" + insertionDateColumn + "'")
        ) ++ fr", " ++ Fragment.const("'" + complianceComputationDateColumn + "'") ++ fr")"
      }

      // check that all migrations are eventually applied even in async mode
      doobie.transactRunEither(
        sql
          .query[(String, String)]
          .to[List]
          .transact(_)
      ) match {
        case Right(res) =>
          res must containTheSameElementsAs( // is_nullable is 'NO' for every column
            List(insertionDateColumn, complianceComputationDateColumn).map((_, "timestamp with time zone"))
          ).eventually(10, 100.millis.asScala)
        case Left(ex)   =>
          ko(
            s"Migration of 'ReportsExecution' table lead to error : ${ex.getMessage}"
          )
      }
    }

    "a second migration doesn't do the 'if' part" in {
      val res = {
        checkTableReportsExecutionTz
          .migrationProg(testTable, Some(fr"RAISE EXCEPTION 'oh no!'; "))
          .either
          .runNow // await for the migration before running assertions test
      }
      res must beRight
    }

  }
}
