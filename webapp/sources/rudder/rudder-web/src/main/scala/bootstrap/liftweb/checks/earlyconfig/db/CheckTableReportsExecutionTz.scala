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
package bootstrap.liftweb.checks.earlyconfig.db

import bootstrap.liftweb.*
import com.normation.errors.IOResult
import com.normation.rudder.db.Doobie
import com.normation.zio.*
import doobie.Update0
import doobie.implicits.*
import doobie.util.fragment.Fragment
import zio.interop.catz.*

/*
 * When we created table ReportsExecution, we didn't use "timestamp with time zone" for column
 * 'insertiondate' and 'compliancecomputationdate'
 */
class CheckTableReportsExecutionTz(
    doobie: Doobie
) extends BootstrapChecks {

  import CheckTableReportsExecutionTz.*
  import doobie.*

  override def description: String =
    "Check if database table ReportsExecution has timestamps with time zone and correct it if not"

  def addTz(table: String, column: String, overrideChange: Option[Fragment] = None): IOResult[Unit] = {

    transactIOResult(s"Error when adding time zone to column ${table}.${column}")(xa =>
      query(table, column, overrideChange).run.transact(xa)
    ).unit
  }

  def migrationProg(table: String, overrideChange: Option[Fragment] = None): IOResult[Unit] = {
    for {
      _ <- addTz(table, insertionDateColumn, overrideChange)
      _ <- addTz(table, complianceComputationDateColumn, overrideChange)
    } yield ()
  }

  override def checks(): Unit = {
    // Actually run the migration async to avoid blocking for that.
    // There is no need to have it sync.
    migrationProg(reportsExecution)
      .catchAll(err => BootstrapLogger.error(s"Error when trying to add time zone to ReportsExecution table: ${err.fullMsg}"))
      .forkDaemon
      .runNow
  }

}

object CheckTableReportsExecutionTz {
  val reportsExecution:                String = "reportsexecution"
  val insertionDateColumn:             String = "insertiondate"
  val complianceComputationDateColumn: String = "compliancecomputationdate"

  // overrideChange is only used for tests
  def query(table: String, column: String, overrideChange: Option[Fragment] = None): Update0 = {
    def toQuotedFr(s: String) = Fragment.const("'" + s + "'")
    def toFr(s:       String) = Fragment.const(s)

    val alter: Fragment = fr"ALTER TABLE " ++ toFr(table) ++ fr" ALTER COLUMN " ++ toFr(
      column
    ) ++ fr" TYPE TIMESTAMP WITH TIME ZONE USING " ++ toFr(column) ++ fr" AT TIME ZONE 'UTC';"

    val sql = {
      fr"""
        DO $$$$ BEGIN
          IF NOT EXISTS (
            select column_name, data_type
            from information_schema.columns
            where table_name = """ ++ toQuotedFr(table) ++ fr" and column_name = " ++ toQuotedFr(
        column
      ) ++ fr""" and data_type = 'timestamp with time zone'
          )
          THEN
          """ ++ overrideChange.getOrElse(alter) ++ fr"""
          end if;
        END $$$$;"""
    }

    sql.update
  }
}
