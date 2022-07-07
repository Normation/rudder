/*
*************************************************************************************
* Copyright 2022 Normation SAS
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
import com.normation.errors._
import com.normation.inventory.ldap.core.LDAPConstants._
import com.normation.ldap.sdk.LDAPConnectionProvider
import com.normation.ldap.sdk.RwLDAPConnection
import com.normation.rudder.domain.logger.MigrationLoggerPure
import com.normation.zio._
import com.unboundid.ldap.sdk.DN
import com.unboundid.ldap.sdk.Modification
import com.unboundid.ldap.sdk.ModificationType
import zio._

/*
 * This migration check looks if we need modify name and description
 * of Group by replace "Classic" by "Linux"
 * added in rudder 7.2.0 (https://issues.rudder.io/issues/21238)
 */

class CheckAddSpecialNodeGroupsDescription(
    ldap                 : LDAPConnectionProvider[RwLDAPConnection]
) extends BootstrapChecks {

  val all_nodeGroupDN = new DN(s"nodeGroupId=all-nodes-with-cfengine-agent,groupCategoryId=SystemGroups,groupCategoryId=GroupRoot,ou=Rudder,cn=rudder-configuration")
  val all_nodeGroupPolicyServerDN = new DN(s"nodeGroupId=hasPolicyServer-root,groupCategoryId=SystemGroups,groupCategoryId=GroupRoot,ou=Rudder,cn=rudder-configuration")

  val allNodeGroupNewName = "All Linux Nodes"
  val allNodeGroupNewDescription = "All Linux Nodes known by Rudder (Using a CFEngine based agent)"

  val allNodeGroupPolicyServerNewName = "All Linux Nodes managed by root policy server"
  val allNodeGroupPolicyServerNewDescription = "All Linux Nodes known by Rudder directly connected to the root server. This group exists only as internal purpose and should not be used to configure nodes."


  override def description: String = "Check if system groups hasPolicyServer-root and all-nodes-with-cfengine-agent have the correct description and name"

  override def checks() : Unit = {
    ZIO.whenM(checkMigrationNeeded())(
      createSpecialTarget()
    ).catchAll(err =>
      MigrationLoggerPure.error(s"Error when trying to modify name and description of groups hasPolicyServer-root and all-nodes-with-cfengine-agent")
    ).runNow
  }


  /*
   * Check if name and description of targeted group respect
   * the new standard (https://issues.rudder.io/issues/21238)
   */
  def checkMigrationNeeded(): IOResult[Boolean] = {
    for {
      con                            <- ldap
      allLinux                       <- con.get(all_nodeGroupDN).chainError(s"Error when trying to get entry for ${all_nodeGroupDN} to modified name and description")
      allLinuxPolicyServerModified <- con.get(all_nodeGroupPolicyServerDN).chainError(s"Error when trying to get entry for ${all_nodeGroupPolicyServerDN} to modified name and description")
      isAllLinuxModified             <- IOResult.effect(s"Error when trying to get attributes `${A_NAME}` and `${A_DESCRIPTION}` for ${all_nodeGroupDN.toString}") {
                                          allLinux match {
                                            case Some(entry) =>
                                              entry.getAttribute(A_NAME).getValue == allNodeGroupNewName &&
                                              entry.getAttribute(A_DESCRIPTION).getValue == allNodeGroupNewDescription
                                            case None => false
                                          }
                                        }
      isAllLinuxPolicyServerModified <- IOResult.effect(s"Error when trying to get attributes `${A_NAME}` and `${A_DESCRIPTION}` for ${all_nodeGroupPolicyServerDN.toString}") {
                                          allLinuxPolicyServerModified match {
                                            case Some(entry) =>
                                              entry.getAttribute(A_NAME).getValue == allNodeGroupPolicyServerNewName &&
                                              entry.getAttribute(A_DESCRIPTION).getValue == allNodeGroupPolicyServerNewDescription
                                            case None => false
                                          }
                                        }
    } yield !(isAllLinuxModified && isAllLinuxPolicyServerModified)
  }

  def createSpecialTarget(): IOResult[Unit] = {
    for {
      con <- ldap
      _ <- con.modify(all_nodeGroupDN, new Modification(ModificationType.REPLACE, A_NAME, allNodeGroupNewName))
      _ <- con.modify(all_nodeGroupDN, new Modification(ModificationType.REPLACE, A_DESCRIPTION, allNodeGroupNewDescription))
      _ <- con.modify(all_nodeGroupPolicyServerDN, new Modification(ModificationType.REPLACE, A_NAME, allNodeGroupPolicyServerNewName))
      _ <- con.modify(all_nodeGroupPolicyServerDN, new Modification(ModificationType.REPLACE, A_DESCRIPTION, allNodeGroupPolicyServerNewDescription))
    } yield ()
  }
}
