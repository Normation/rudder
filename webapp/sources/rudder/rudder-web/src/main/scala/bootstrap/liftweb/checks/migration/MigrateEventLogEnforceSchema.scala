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

import bootstrap.liftweb.BootstrapChecks
import bootstrap.liftweb.BootstrapLogger
import com.normation.errors.RudderError
import com.normation.rudder.db.Doobie
import com.normation.zio.*
import doobie.ConnectionIO
import doobie.Transactor
import doobie.implicits.*
import doobie.util.fragment.Fragment
import doobie.util.update.Update0
import zio.*
import zio.interop.catz.*

/*
 * Ths migration applies a change in the schema of the event logs table :
 *  - add not null constraint to : eventtype, principal, severity, data
 *  - add default values to recover from potentially missing data, handling that case in the business logic
 */
class MigrateEventLogEnforceSchema(
    doobie: Doobie
) extends BootstrapChecks {

  import doobie.*

  protected def tableName: String = "eventlog"
  private def table = Fragment.const(tableName)

  val msg: String = "eventLog columns that should be not null (eventtype, principal, severity, data)"

  override def description: String =
    "Check if eventtype, principal, severity, data have a not null constraint, otherwise migrate these columns"

  val defaultEventType: Fragment = Fragment.const("''")
  val defaultSeverity:  Fragment = Fragment.const("100")
  val defaultPrincipal: Fragment = Fragment.const("'unknown'")
  val defaultData:      Fragment = Fragment.const("''")

  def migrateColumnStatement(column: Fragment, defaultValue: Fragment): Update0 = {
    sql"""
      -- Alter the EventLog schema for the column
      update ${table} set ${column} = ${defaultValue} where $column is null;
      alter table ${table}
      alter column ${column} set default ${defaultValue},
      alter column ${column} set not null;
    """.update
  }

  // we need to query using 'string' parameters, and not fragments
  private def shouldMigrateColumn(columnName: String): ConnectionIO[Boolean] = {
    sql"""
      select count(*)
      from information_schema.columns
      where table_name = ${tableName}
      and column_name = ${columnName}
      and is_nullable = 'YES'
    """.query[Int].unique.map(_ > 0)
  }

  protected def migrationEffect(xa: Transactor[Task]): Task[Unit] = {
    ZIO
      .foreachDiscard(
        List(
          ("eventtype", defaultEventType),
          ("principal", defaultPrincipal),
          ("severity", defaultSeverity),
          ("data", defaultData)
        )
      ) {
        case (colName, defaultValue) =>
          for {
            migrate <- shouldMigrateColumn(colName).transact(xa)
            _       <- if (migrate) {
                         migrateColumnStatement(Fragment.const(colName), defaultValue).run.transact(xa)
                       } else {
                         BootstrapLogger.debug(
                           s"No need to migrate: have already previously migrated table ${tableName} column ${colName} (${msg})"
                         )
                       }

          } yield ()
      }

  }

  val migrateAsync: URIO[Any, Fiber.Runtime[RudderError, Unit]] = {
    transactIOResult(s"Error with 'EventLog' table migration")(
      migrationEffect(_)
        .foldZIO(
          err =>
            BootstrapLogger.error(
              s"Non-fatal error when trying to migrate ${msg}." +
              s"\nThis is a data check and it should not alter the behavior of Rudder." +
              s"\nPlease contact the development team with the following information to help resolving the issue : ${err.getMessage}"
            ),
          _ => BootstrapLogger.info(s"Migrated ${msg}")
        )
    ).forkDaemon
  }

  override def checks(): Unit = {
    migrateAsync.runNow
  }

}
