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

package com.normation.inventory.ldap.provisioning

import com.normation.errors.*
import com.normation.inventory.domain.*
import com.normation.inventory.services.provisioning.*
import java.time.Instant
import zio.syntax.*

/**
 * Check OS Type.
 * We can not handle "UnknownOsType", we just don't know what
 * to do with them
 */
object CheckOsType extends PreCommit {

  override val name = "pre_commit_inventory:check_os_type_is_known"

  override def apply(inventory: Inventory): IOResult[Inventory] = {

    inventory.node.main.osDetails.os match {
      case UnknownOSType =>
        val xml = inventory.sourceFile \\ "OPERATINGSYSTEM"
        InventoryError
          .Inconsistency(
            s"Os Type is not suported (OS Type: '${(xml \\ "KERNEL_NAME").text}'; OS Name: '${(xml \\ "NAME").text}}')"
          )
          .fail
      case _             => inventory.succeed
    }

  }
}

/**
 * Update last inventory date for Server and machine
 */
class LastInventoryDate() extends PreCommit {

  override val name = "pre_commit_inventory:set_last_inventory_date"

  override def apply(inventory: Inventory): IOResult[Inventory] = {
    val now = Instant.now

    inventory
      .copy(
        node = inventory.node.copy(receiveDate = Some(now)),
        machine = inventory.machine.copy(receiveDate = Some(now))
      )
      .succeed
  }
}

/**
 * Set the ip values in the server object, from the networks data
 */
object AddIpValues extends PreCommit {

  override val name = "pre_commit_inventory:add_ip_values"

  override def apply(inventory: Inventory): IOResult[Inventory] = {

    val ips = inventory.node.networks.flatMap(x => x.ifAddresses).map(x => x.getHostAddress())

    inventory.copy(node = inventory.node.copy(serverIps = ips)).succeed
  }
}
