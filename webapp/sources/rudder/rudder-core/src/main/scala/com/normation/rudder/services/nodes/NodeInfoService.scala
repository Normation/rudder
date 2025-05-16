/*
 *************************************************************************************
 * Copyright 2011 Normation SAS
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

package com.normation.rudder.services.nodes

import com.normation.inventory.ldap.core.LDAPConstants.*
import com.normation.rudder.domain.RudderLDAPConstants.*

object NodeInfoService {

  /*
   * We need to list actual attributes, because we don't want software ids.
   * Really bad idea, these software ids, in node...
   */
  val nodeInfoAttributes: Seq[String] = (
    Set(
      // for all: objectClass and ldap object creation datatime
      A_OC,
      A_OBJECT_CREATION_DATE, // node
      //  id, name, description, isBroken, isSystem
      // , isPolicyServer <- this one is special and decided based on objectClasss rudderPolicyServer
      // , creationDate, nodeReportingConfiguration, properties

      A_NODE_UUID,
      A_NAME,
      A_DESCRIPTION,
      A_STATE,
      A_IS_SYSTEM,
      A_SERIALIZED_AGENT_RUN_INTERVAL,
      A_SERIALIZED_HEARTBEAT_RUN_CONFIGURATION,
      A_NODE_PROPERTY,
      A_POLICY_MODE, // machine inventory
      // MachineUuid
      // , machineType <- special: from objectClass
      // , systemSerial, manufacturer

      A_MACHINE_UUID,
      A_SERIAL_NUMBER,
      A_MANUFACTURER, // node inventory, safe machine and node (above)
      //  hostname, ips, inventoryDate, publicKey
      // , osDetails
      // , agentsName, policyServerId, localAdministratorAccountName
      // , serverRoles, archDescription, ram, timezone
      // , customProperties

      A_NODE_UUID,
      A_HOSTNAME,
      A_LIST_OF_IP,
      A_INVENTORY_DATE,
      A_OS_NAME,
      A_OS_FULL_NAME,
      A_OS_VERSION,
      A_OS_KERNEL_VERSION,
      A_OS_SERVICE_PACK,
      A_WIN_USER_DOMAIN,
      A_WIN_COMPANY,
      A_WIN_KEY,
      A_WIN_ID,
      A_AGENT_NAME,
      A_POLICY_SERVER_UUID,
      A_ROOT_USER,
      A_ARCH,
      A_CONTAINER_DN,
      A_OS_RAM,
      A_KEY_STATUS,
      A_TIMEZONE_NAME,
      A_TIMEZONE_OFFSET,
      A_CUSTOM_PROPERTY,
      A_SECURITY_TAG
    )
  ).toSeq

  val A_MOD_TIMESTAMP = "modifyTimestamp"

}
