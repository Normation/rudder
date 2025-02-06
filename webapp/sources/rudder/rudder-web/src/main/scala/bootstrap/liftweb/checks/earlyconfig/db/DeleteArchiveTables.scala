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
import com.normation.rudder.db.Doobie
import com.normation.zio.*
import doobie.implicits.*
import doobie.util.fragment.Fragment
import zio.*
import zio.interop.catz.*

/*
 * In 8.2, we are removing a lot of old, unused tables, see: https://issues.rudder.io/issues/24964
 */
class DeleteArchiveTables(
    doobie: Doobie
) extends BootstrapChecks {

  import doobie.*

  val tables: List[String] = List(
    "archivednodecompliance",
    "archivednodeconfigurations",
    "archivedruddersysevents",
    "migrationeventlog"
  )

  val sequences: List[String] = List(
    "migrationeventlogid"
  )

  override def description: String =
    s"Delete unused PostgreSQL table: ${tables.mkString(", ")}"

  def dropTable(table: String) = {
    val sql = sql"""DROP TABLE IF EXISTS """ ++ Fragment.const0(table)

    transactIOResult(s"Error when deleting table '${table}'")(xa => sql.update.run.transact(xa)).unit.catchAll(err =>
      BootstrapLogger.Early.DB.error(err.fullMsg)
    )
  }

  def dropSequence(sequence: String) = {
    val sql = sql"""DROP SEQUENCE IF EXISTS """ ++ Fragment.const0(sequence)

    transactIOResult(s"Error when deleting table '${sequence}'")(xa => sql.update.run.transact(xa)).unit.catchAll(err =>
      BootstrapLogger.Early.DB.error(err.fullMsg)
    )
  }

  override def checks(): Unit = {
    val prog = {
      ZIO.foreachParDiscard(tables)(dropTable) *> ZIO.foreachParDiscard(sequences)(dropSequence)
    }

    // Actually run the migration async to avoid blocking for that.
    // There is no need to have it sync.
    prog.forkDaemon.runNow
  }

}
