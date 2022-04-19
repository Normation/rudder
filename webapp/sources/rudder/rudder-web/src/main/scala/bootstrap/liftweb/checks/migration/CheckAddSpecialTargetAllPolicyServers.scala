/*
*************************************************************************************
* Copyright 2021 Normation SAS
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

import com.normation.inventory.ldap.core.LDAPConstants._
import com.normation.ldap.sdk.LDAPConnectionProvider
import com.normation.ldap.sdk.LDAPEntry
import com.normation.ldap.sdk.RwLDAPConnection
import com.normation.ldap.sdk.syntax._
import com.normation.rudder.domain.RudderLDAPConstants._
import com.normation.rudder.domain.logger.MigrationLoggerPure

import bootstrap.liftweb.BootstrapChecks
import com.unboundid.ldap.sdk.DN

import zio._
import com.normation.errors._
import com.normation.zio._

/*
 * This migration check looks if we need to add special target "all_policyServers"
 * added in rudder 7.0 (https://issues.rudder.io/issues/20460)
 */

class CheckAddSpecialTargetAllPolicyServers(
    ldap                 : LDAPConnectionProvider[RwLDAPConnection]
) extends BootstrapChecks {

  val all_policyServersDN = new DN(s"ruleTarget=special:all_policyServers,groupCategoryId=SystemGroups,groupCategoryId=GroupRoot,ou=Rudder,cn=rudder-configuration")
  val all_policyServers = {
    val entry = LDAPEntry(all_policyServersDN)
    entry.resetValuesTo(A_OC, OC.objectClassNames(OC_SPECIAL_TARGET).toSeq:_*)
    entry.resetValuesTo(A_RULE_TARGET, "special:all_policyServers")
    entry.resetValuesTo(A_NAME, "All policy servers")
    entry.resetValuesTo(A_DESCRIPTION, "All policy servers (root policy server and relays)")
    entry.resetValuesTo(A_IS_ENABLED, true.toLDAPString)
    entry.resetValuesTo(A_IS_SYSTEM, true.toLDAPString)
    entry
  }

  override def description: String = "Check if special target all_policyServers from Rudder 7.0 is present"

  override def checks() : Unit = {
    ZIO.whenZIO(checkMigrationNeeded())(
      createSpecialTarget()
    ).catchAll(err =>
      MigrationLoggerPure.error(s"Error during addition of new special target 'all_policyServers'. You can restart Rudder to " +
                                s"try again the migration or report it to Rudder project")
    ).runNow
  }


  /*
   * Migration is needed if target does not exists.
   */
  def checkMigrationNeeded(): IOResult[Boolean] = {
    for {
      con <- ldap
      res <- con.exists(all_policyServersDN)
    } yield !res
  }

  def createSpecialTarget(): IOResult[Unit] = {
    for {
      con <- ldap
      res <- con.save(all_policyServers)
    } yield ()
  }
}
