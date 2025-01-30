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
import com.normation.errors.IOResult
import com.normation.eventlog.EventActor
import com.normation.rudder.db.Doobie
import com.normation.zio.*
import doobie.implicits.*
import zio.interop.catz.*

/*
 * Before Rudder 8.0, we used to save the state of node when accepted in an LDIF file under
 *   /var/rudder/inventories/historical/${nodeid}/${iso-date-time-of-acceptation}
 * Since 8.0, we store them in the nodefacts tables, which is create in the process.
 * Ths migration is convergent and asynchronous:
 * - it does not block boot
 * - it can be interrupted and restarted afterward
 * During migration, user won't be able to access the history inventory of the node.
 *
 * The migration is also a good time to clean-up that directory, and actually an empty directory will signal
 * end of migration:
 * - for each directory under `/var/node/historical/`,
 * - if the node still exist OR if the event is less than KEEP_REFUSED_DURATION, add it in the base else nothing
 * - delete the directory
 */
class CreateTableNodeFacts(
    doobie: Doobie
) extends BootstrapChecks {

  import doobie.*

  val migrationActor: EventActor = EventActor("rudder-migration")

  override def description: String =
    "Check if table 'NodeFacts' exists or create it"

  def createTableStatement: IOResult[Unit] = {
    val sql = {
      sql"""CREATE TABLE IF NOT EXISTS NodeFacts (
        nodeId           text PRIMARY KEY
      , acceptRefuseEvent jsonb
      , acceptRefuseFact  jsonb
      , deleteEvent       jsonb
    );"""
    }

    // migrate from previous Rudder 8.0 beta  (before beta 2)
    val sqlMigrate = {
      sql"""ALTER TABLE IF EXISTS NodeFacts
           ADD COLUMN IF NOT EXISTS acceptRefuseEvent jsonb,
           ADD COLUMN IF NOT EXISTS deleteEvent json,
           DROP COLUMN IF EXISTS acceptRefuseDate;"""
    }

    transactIOResult(s"Error with 'NodeFacts' table creation")(xa => sql.update.run.transact(xa)).unit *>
    transactIOResult(s"Error with 'NodeFacts' table migration")(xa => sqlMigrate.update.run.transact(xa)).unit
  }

  override def checks(): Unit = {
    val prog = {
      for {
        _ <- createTableStatement
      } yield ()
    }

    // Actually run the migration async to avoid blocking for that.
    // There is no need to have it sync.
    prog.catchAll(err => BootstrapLogger.error(s"Error when trying to create table NodeFacts: ${err.fullMsg}")).forkDaemon.runNow
  }

}
