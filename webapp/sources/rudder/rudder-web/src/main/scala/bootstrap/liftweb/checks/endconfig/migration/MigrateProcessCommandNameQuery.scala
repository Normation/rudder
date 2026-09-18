/*
 *************************************************************************************
 * Copyright 2026 Normation SAS
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

import bootstrap.liftweb.BootstrapChecks
import bootstrap.liftweb.BootstrapLogger
import com.normation.errors.*
import com.normation.ldap.sdk.BuildFilter
import com.normation.ldap.sdk.LDAPConnectionProvider
import com.normation.ldap.sdk.LDAPEntry
import com.normation.ldap.sdk.RwLDAPConnection
import com.normation.rudder.domain.RudderDit
import com.normation.rudder.domain.RudderLDAPConstants.*
import com.normation.zio.*
import java.nio.charset.StandardCharsets
import zio.*

/**
 * See https://issues.rudder.io/issues/29733
 * We need to change `"attribute":"commandName"` into `"attribute":"name"`
 * for `jsonNodeGroupQuery` attribute
 * This migration can be deleted in Rudder 10.0.
 */
class MigrateProcessCommandNameQuery(
    ldap:      LDAPConnectionProvider[RwLDAPConnection],
    rudderDit: RudderDit
) extends BootstrapChecks {

  override def description: String = "Check if some groups use the old commandName attribute for processes"

  val oldCommandName = """"attribute":"commandName""""
  val newName        = """"attribute":"name""""

  def selectGroups(con: RwLDAPConnection): IOResult[Seq[LDAPEntry]] = {
    con.searchSub(
      rudderDit.GROUP.dn,
      BuildFilter.SUB(A_QUERY_NODE_GROUP, null, Array(oldCommandName.getBytes(StandardCharsets.UTF_8)), null),
      A_QUERY_NODE_GROUP
    )
  }

  // only report on error here, we want to continue processing other entries
  def updateGroupName(con: RwLDAPConnection, e: LDAPEntry): UIO[Unit] = {
    // change display name and only that
    e(A_QUERY_NODE_GROUP) match {
      case None      => ZIO.unit
      case Some(old) =>
        e.resetValuesTo(A_QUERY_NODE_GROUP, old.replaceAll(oldCommandName, newName))
        con
          .save(e)
          .unit
          .catchAll(err =>
            BootstrapLogger.error(s"Error when trying to update process command name of group '${e.dn.toString}': ${err.fullMsg}")
          )
    }
  }

  // whole process
  def updateGroupNames: IOResult[Unit] = {
    for {
      con     <- ldap
      entries <- selectGroups(con)
      _       <- ZIO.foreach(entries)(e => {
                   updateGroupName(con, e) *> BootstrapLogger.info(
                     s"Process command name of group '${e.rdn.getOrElse(e.dn.toString())}' was updated"
                   )
                 })
    } yield ()
  }

  override def checks(): Unit = {

    ZioRuntime.runNowLogError { err =>
      BootstrapLogger.logEffect.error(
        s"An error occurred while migrating group with query on process command name. You will need to redo that group by hand. Error was: ${err.fullMsg}"
      )
    }(updateGroupNames)
  }
}
