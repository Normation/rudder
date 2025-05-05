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
import doobie.implicits.*
import zio.interop.catz.*

/*
 * During 8.1 cycle, we added a score that is applied to every nodes to give a better understanding
 */
class CheckTableNodeLastCompliance(
    doobie: Doobie
) extends BootstrapChecks {

  import doobie.*

  override def description: String = "Check if table 'NodeLastCompliance' exists"

  def createTable: IOResult[Unit] = {

    val sql1 = sql"""CREATE TABLE IF NOT EXISTS NodeLastCompliance (
      nodeId text NOT NULL CHECK (nodeId <> '') primary key
    , computationDateTime timestamp with time zone NOT NULL
    , details jsonb NOT NULL
    );"""

    transactIOResult(s"Error with 'NodeLastCompliance' table creation")(xa => sql1.update.run.transact(xa)).unit
  }

  override def checks(): Unit = {
    val prog = {
      for {
        _ <- createTable
      } yield ()
    }

    // Actually run the migration. Since we will need in other bootstrap checks
    // that the table exists, do not run it async.
    prog.catchAll(err => BootstrapLogger.error(s"Error when trying to create tables: ${err.fullMsg}")).runNow
  }

}
