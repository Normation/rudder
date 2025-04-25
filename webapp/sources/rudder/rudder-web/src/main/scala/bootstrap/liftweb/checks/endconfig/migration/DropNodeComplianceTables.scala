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
package bootstrap.liftweb.checks.endconfig.migration

import bootstrap.liftweb.*
import com.normation.errors.IOResult
import com.normation.rudder.db.Doobie
import com.normation.zio.*
import doobie.implicits.*
import doobie.util.fragment.Fragment
import zio.ZIO
import zio.interop.catz.*

/*
 * In Rudder 8.3, we remove support for reporting plugin.
 * We need to delete the corresponding tables and index if exists.
 * Tables:
 * - nodeCompliance
 * - nodecompliancelevels
 *
 * Index:
 * - nodeCompliance_nodeId
 * - nodeCompliance_runTimestamp
 * - nodeCompliance_endOfLife
 * - nodecompliancelevels_nodeId
 * - nodecompliancelevels_ruleId_idx
 * - nodecompliancelevels_directiveId_idx
 * - nodecompliancelevels_runTimestamp
 */
class DropNodeComplianceTables(
    doobie: Doobie
) extends BootstrapChecks {

  import doobie.*

  override def description: String = "Check if score nodecompliance[levels] tables exist and need to be deleted"

  private[migration] def cleanUpComplianceTable: IOResult[Unit] = {

    // (sql, error msg) pair
    def tableAction(table: String) = (
      // https://www.postgresql.org/docs/17/sql-droptable.html
      // table index are automatically deleted
      // Using "CASCADE" would also drop related foreign keys or view, but we don't have any
      // and so if some exists, they are user things and we prefer not touch it
      Fragment.const(s"""DROP TABLE IF EXISTS ${table};"""),
      table
    )

    val actions = List(tableAction("nodecompliance"), tableAction("nodecompliancelevels"))

    ZIO
      .validateDiscard(actions) {
        case (sql, table) =>
          // unfortunately, we can't check if the command did anything since "drop table" never returns anything
          transactIOResult(s"Error when deleting table '${table}'")(xa => sql.update.run.transact(xa))
      }
      .toAccumulated
  }

  override def checks(): Unit = {
    val prog = {
      for {
        _ <- cleanUpComplianceTable
      } yield ()
    }

    // Actually run the migration async to avoid blocking for that.
    // There is no need to have it sync.
    prog
      .catchAll(err => BootstrapLogger.Early.DB.error(s"Error when trying to clean-up compliance related tables: ${err.fullMsg}"))
      .forkDaemon
      .runNow
  }

}
