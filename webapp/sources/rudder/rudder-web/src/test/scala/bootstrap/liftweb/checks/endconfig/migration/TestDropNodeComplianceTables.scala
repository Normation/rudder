/*
 *************************************************************************************
 * Copyright 2025 Normation SAS
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

package bootstrap.liftweb.checks.endconfig.migration

import com.normation.rudder.db.DBCommon
import com.normation.zio.UnsafeRun
import doobie.specs2.IOChecker
import doobie.syntax.all.*
import doobie.util.transactor.Transactor
import org.junit.runner.RunWith
import org.specs2.runner.JUnitRunner
import zio.*
import zio.interop.catz.*

@RunWith(classOf[JUnitRunner])
class TestDropNodeComplianceTables extends DBCommon with IOChecker {
  private lazy val dropNodeComplianceTables = new DropNodeComplianceTables(doobie)

  override def transactor: Transactor[cats.effect.IO] = doobie.xaio

  override def initDb(): Unit = {
    super.initDb()
    doobie.transactRunEither(previousSchemaDDL.update.run.transact(_)) match {
      case Right(_) => ()
      case Left(ex) => throw ex
    }
  }

  // The previous schema from Rudder 8.2, with the inserted table for this test
  // We need to know for sure the initial state and the final state of the migrated table,
  // so we define and use the previous schema without conflicting with the current one (with all values renamed)
  private val previousSchemaDDL = sql"""
    /*
     *************************************************************************************
     * The following tables stores "node compliance", i.e all the interesting information
     * about what was the compliance of a node FOR A GIVEN RUN.
     * That table *only* store information for runs, and does not track (non exaustively):
     * - when the node expected configuration is updated - only a new run will check,
     * - node not sending runs - only the fact that we don't have data can be observed
     * - if a node is deleted
     *************************************************************************************
     */

    -- Create the table for the node compliance
    CREATE TABLE nodeCompliance (
      nodeId            text NOT NULL CHECK (nodeId <> '')
    , runTimestamp      timestamp with time zone NOT NULL

    -- endOfList is the date until which the compliance information
    -- are relevant. After that date/time, the node must have sent
    -- a more recent run, this one is not valide anymore.
    , endOfLife         timestamp with time zone

    -- all information about the run and what lead to that compliance:
    -- the run config version, the awaited config version, etc
    -- It's JSON (but in a string, cf explanation in nodeConfigurations table)
    -- and has such, it must not be empty (at least '{}')

    , runAnalysis       text NOT NULL CHECK (runAnalysis <> '' )

    -- node compliance summary (i.e, no details by rule etc), in percent
    -- that JSON, again

    , summary           text NOT NULL CHECK (summary <> '' )

    -- the actual compliance with all details
    -- Again, JSON

    , details  text NOT NULL CHECK (details <> '' )

    -- Primary key is given by a run timestamp and the node id. We could
    -- have duplicate if node clock change, but it would need to have
    -- exact same timestamp down to the millis, quite improbable.

    , PRIMARY KEY (nodeId, runTimestamp)
    );

    CREATE INDEX nodeCompliance_nodeId ON nodeCompliance (nodeId);
    CREATE INDEX nodeCompliance_runTimestamp ON nodeCompliance (runTimestamp);
    CREATE INDEX nodeCompliance_endOfLife ON nodeCompliance (endOfLife);

    ALTER TABLE nodecompliance set (autovacuum_vacuum_threshold = 0);
    ALTER TABLE nodecompliance set (autovacuum_vacuum_scale_factor = 0.1);

    -- Create a table of only (nodeid, ruleid, directiveid) -> complianceLevel
    -- for all runs. That table is amendable to postgresql-side processing,
    -- in particular to allow aggregation of compliance by rule / node / directive,
    -- but with a much more reasonable space until all our supported server versions
    -- have at least PostgreSQL 9.4.
    CREATE TABLE nodecompliancelevels (
      nodeId             text NOT NULL CHECK (nodeId <> '')
    , runTimestamp       timestamp with time zone NOT NULL
    , ruleId             text NOT NULL CHECK (ruleId <> '')
    , directiveId        text NOT NULL CHECK (directiveId <> '')
    , pending            int DEFAULT 0
    , success            int DEFAULT 0
    , repaired           int DEFAULT 0
    , error              int DEFAULT 0
    , unexpected         int DEFAULT 0
    , missing            int DEFAULT 0
    , noAnswer           int DEFAULT 0
    , notApplicable      int DEFAULT 0
    , reportsDisabled    int DEFAULT 0
    , compliant          int DEFAULT 0
    , auditNotApplicable int DEFAULT 0
    , nonCompliant       int DEFAULT 0
    , auditError         int DEFAULT 0
    , badPolicyMode      int DEFAULT 0
    , PRIMARY KEY (nodeId, runTimestamp, ruleId, directiveId)
    );

    CREATE INDEX nodecompliancelevels_nodeId ON nodecompliancelevels (nodeId);
    CREATE INDEX nodecompliancelevels_ruleId_idx ON nodecompliancelevels (ruleId);
    CREATE INDEX nodecompliancelevels_directiveId_idx ON nodecompliancelevels (directiveId);
    CREATE INDEX nodecompliancelevels_runTimestamp ON nodecompliancelevels (runTimestamp);

    ALTER TABLE nodecompliancelevels set (autovacuum_vacuum_scale_factor = 0.05);
  """

  sequential

  "DropNodeComplianceTables" should {

    "delete table and index" in {
      // check that the alter statements are made on existing table and column
      if (doDatabaseConnection) {
        dropNodeComplianceTables.checks()

        // a table that exists just to check that we really check things
        val okTables    = List("nodelastcompliance")
        val okIndexes   = List("nodeconfigurations_nodeid")
        val dropTables  = List("nodecompliance", "nodecompliancelevels")
        val dropIndexes = List(
          "nodecompliance_nodeid",
          "nodecompliance_runtimestamp",
          "nodecompliance_endoflife",
          "nodecompliancelevels_nodeid",
          "nodecompliancelevels_ruleid_idx",
          "nodecompliancelevels_directiveid_idx",
          "nodecompliancelevels_runtimestamp"
        )

        def tableCommand(names: List[String]) = ZIO
          .foreach(names)(n => {
            doobie.transactIOResult(s"error with table '${n}'")(xa => {
              _root_.doobie.Fragment
                .const(s"SELECT EXISTS (SELECT 1 FROM pg_tables WHERE tablename  = '${n}');")
                .query[Boolean]
                .unique
                .transact(xa)
            })
          })
          .runNow

        def indexesCommand(names: List[String]) = ZIO
          .foreach(names)(n => {
            doobie.transactIOResult(s"error with indexes '${n}'")(xa => {
              _root_.doobie.Fragment
                .const(s"SELECT EXISTS (SELECT 1 FROM pg_indexes WHERE indexname  = '${n}');")
                .query[Boolean]
                .unique
                .transact(xa)
            })
          })
          .runNow

        // check if OK table & indexes exists
        okTables.zip(tableCommand(okTables)) === okTables.zip(Iterator.continually(true))
        okIndexes.zip(indexesCommand(okIndexes)) === okIndexes.zip(Iterator.continually(true))

        // and if dropped tables & indexes does not exist
        dropTables.zip(tableCommand(dropTables)) === dropTables.zip(Iterator.continually(false))
        dropIndexes.zip(indexesCommand(dropIndexes)) === dropIndexes.zip(Iterator.continually(false))
      } else ok("ignored")
    }
  }
}
