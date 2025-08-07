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

package bootstrap.liftweb.checks.earlyconfig.db

import bootstrap.liftweb.BootstrapChecks
import bootstrap.liftweb.BootstrapLogger
import com.normation.errors.RudderError
import com.normation.rudder.db.Doobie
import com.normation.zio.UnsafeRun
import doobie.Transactor
import doobie.implicits.*
import doobie.util.fragment.Fragment
import doobie.util.update.Update0
import zio.*
import zio.interop.catz.*

/*
 * Ths migration applies a change in the schema of the change requests and workflow table :
 *  - add not null constraint to id, state columns in the workflow table
 *  - add not null constraint to content column in the change requests table
 */
class MigrateChangeValidationEnforceSchema(
    doobie: Doobie
) extends BootstrapChecks with DbCommonMigration {

  import doobie.*

  val msg: String = "change requests and workflow columns that should be not null (id, state, content)"

  protected def changeRequestTableName: String   = "changerequest"
  private def changeRequestTable:       Fragment = Fragment.const(changeRequestTableName)
  protected def workflowTableName:      String   = "workflow"
  private def workflowTable:            Fragment = Fragment.const(workflowTableName)

  override def description: String =
    "Check if changerequest(content), workflow(id, state) have a not null constraint, otherwise migrate these columns"

  def alterTableStatement(table: Fragment, column: Fragment): Update0 = {
    sql"alter table $table alter column $column set not null".update
  }

  // we need the table fragment to be the class method that can be overridden (e.g. for tests)
  private def transactMigration(table: String, tableFragment: Fragment, column: String)(implicit
      xa: Transactor[Task]
  ): Task[Unit] = {
    val columnFragment = Fragment.const(column)
    for {
      migrate <- isColumnNullable(table, column).transact(xa) // migrate when column is still nullable
      _       <- if (migrate) alterTableStatement(tableFragment, columnFragment).run.transact(xa)
                 else {
                   BootstrapLogger.Early.DB.debug(
                     s"No need to migrate: have already previously migrated table ${table} column ${column} (${msg})"
                   )
                 }

    } yield ()
  }

  def migrationEffect(implicit xa: Transactor[Task]): Task[Unit] = {
    val changeRequestContent = transactMigration(changeRequestTableName, changeRequestTable, "content")
    val workflowId           = transactMigration(workflowTableName, workflowTable, "id")
    val workflowState        = transactMigration(workflowTableName, workflowTable, "state")

    changeRequestContent *> workflowId *> workflowState
  }

  val migrateAsync: URIO[Any, Fiber.Runtime[RudderError, Unit]] = {
    transactIOResult(s"Error with 'EventLog' table migration") { t =>
      migrationEffect(using t)
        .foldZIO(
          err =>
            BootstrapLogger.Early.DB.error(
              s"Non-fatal error when trying to migrate ${msg}." +
              s"\nThis is a data check and it should not alter the behavior of Rudder." +
              s"\nPlease contact the development team with the following information to help resolving the issue : ${err.getMessage}"
            ),
          _ => BootstrapLogger.Early.DB.info(s"Migrated ${msg}")
        )
    }.forkDaemon
  }

  override def checks(): Unit = {
    migrateAsync.runNow
  }

}
