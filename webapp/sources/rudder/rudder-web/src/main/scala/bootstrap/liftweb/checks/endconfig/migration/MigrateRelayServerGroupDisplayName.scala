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
import com.normation.inventory.ldap.core.LDAPConstants.A_NAME
import com.normation.ldap.sdk.BuildFilter
import com.normation.ldap.sdk.LDAPConnectionProvider
import com.normation.ldap.sdk.LDAPEntry
import com.normation.ldap.sdk.RwLDAPConnection
import com.normation.rudder.domain.RudderDit
import com.normation.zio.*
import zio.*

/**
 * We want to change `All nodes managed by 'xxx' policy server` to
 * `All Linux nodes managed by 'xxx' policy server`
 * See https://issues.rudder.io/issues/28720
 *
 * This migration can be deleted in Rudder 9.2.0.
 */
class MigrateRelayServerGroupDisplayName(
    ldap:      LDAPConnectionProvider[RwLDAPConnection],
    rudderDit: RudderDit
) extends BootstrapChecks {

  private val badStart     = "All nodes managed by"
  private val correctStart = "All Linux nodes managed by"

  override val description = "Migrate relay server's group display name"

  def selectGroups(con: RwLDAPConnection): IOResult[Seq[LDAPEntry]] = {
    // all the possible groups are directly under
    // groupCategoryId=SystemGroups,groupCategoryId=GroupRoot,ou=Rudder,cn=rudder-configuration
    con.searchOne(rudderDit.GROUP.SYSTEM.dn, BuildFilter.SUB(A_NAME, badStart, null, null), A_NAME) // yep, null
  }

  // only report on error here, we want to continue processing other entries
  def updateGroupName(con: RwLDAPConnection, e: LDAPEntry): UIO[Unit] = {
    // change display name and only that
    e(A_NAME) match {
      case None     => ZIO.unit
      case Some(cn) =>
        e.resetValuesTo(A_NAME, cn.replaceAll(badStart, correctStart))
        con
          .save(e)
          .unit
          .catchAll(err =>
            BootstrapLogger.error(s"Error when trying to update display name of relay group '${e.dn.toString}': ${err.fullMsg}")
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
                     s"Display name of system group '${e.rdn.getOrElse(e.dn.toString())}' was updated"
                   )
                 })
    } yield ()
  }

  override def checks(): Unit = {

    ZioRuntime.runNowLogError { err =>
      BootstrapLogger.logEffect.error(
        s"An error occurred while migrating relay server group display name: ${err.fullMsg}"
      )
    }(updateGroupNames)
  }
}
