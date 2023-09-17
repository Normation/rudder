package bootstrap.liftweb.checks.migration

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

import bootstrap.liftweb._
import com.normation.errors.IOResult
import com.normation.rudder.db.Doobie
import com.normation.zio._
import doobie.implicits._
import zio.interop.catz._

/*
 * During 7.3 cycle, we added the registration of users and their sessions in base.
 * This is to allows better security logs on user sessions + allows to de-correlate rudder
 * users from the `rudder-user.xml` file.
 */
class CheckTableUsers(
    doobie: Doobie
) extends BootstrapChecks {

  import doobie._

  override def description: String = "Check if database tables Users and UserSessions exist"

  def createUserTables: IOResult[Unit] = {
    val sql1 = sql"""CREATE TABLE IF NOT EXISTS Users (
        id             text PRIMARY KEY NOT NULL CHECK (id <> '')
      , creationDate   timestamp with time zone NOT NULL
      , status         text NOT NULL
      , managedBy   text NOT NULL CHECK (managedBy <> '')
      , name           text
      , email          text
      , lastLogin      timestamp with time zone
      , statusHistory  jsonb
      , otherInfo      jsonb -- general additional user info
    );"""

    val sql2 = sql"""CREATE TABLE IF NOT EXISTS UserSessions (
      userId       text NOT NULL CHECK (userId <> '')
    , sessionId    text NOT NULL CHECK (sessionId <> '')
    , creationDate timestamp with time zone NOT NULL
    , authMethod   text
    , permissions  text[]
    , endDate      timestamp with time zone
    , endCause     text
    );"""

    transactIOResult(s"Error with 'Users' table creation")(xa => sql1.update.run.transact(xa)).unit *>
    transactIOResult(s"Error with 'v' table creation")(xa => sql2.update.run.transact(xa)).unit
  }

  override def checks(): Unit = {
    val prog = {
      for {
        _ <- createUserTables
      } yield ()
    }

    // Actually run the migration async to avoid blocking for that.
    // There is no need to have it sync.
    prog.catchAll(err => BootstrapLogger.error(s"Error when trying to create user tables: ${err.fullMsg}")).forkDaemon.runNow
  }

}
