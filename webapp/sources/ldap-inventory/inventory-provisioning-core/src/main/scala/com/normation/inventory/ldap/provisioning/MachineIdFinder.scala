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

import com.normation.inventory.services.provisioning._
import com.unboundid.ldap.sdk.DN
import com.normation.ldap.sdk._
import BuildFilter._
import com.normation.inventory.domain._
import com.normation.inventory.ldap.core._
import LDAPConstants._
import net.liftweb.common._
import com.normation.ldap.sdk.LdapResult._
import scalaz.zio._
import scalaz.zio.syntax._


/////
///// these finders are used for VMs. For the machine linked to a node, the ID is derived from nodeId.
/////

/**
 * Retrieve the ID from the LDAP directory
 */
class UseExistingMachineIdFinder(
    inventoryDitService:InventoryDitService,
    ldap:LDAPConnectionProvider[RoLDAPConnection],
    rootDN:DN
) extends MachineDNFinderAction {
  override def tryWith(entity:MachineInventory) : Task[Option[(MachineUuid, InventoryStatus)]] = {
    (for {
      con   <- ldap
      entry <- con.searchSub(rootDN, AND(IS(OC_MACHINE),EQ(A_MACHINE_UUID, entity.id.value)), "1.1").map(_.headOption).notOptional(s"No machine entry found for id '${entity.id.value}'") //TODO: error if more than one !! #555
      dit   <- inventoryDitService.getDit(entry.dn) match {
                 case None    => s"No DIT found for machine DN ${entry.dn}"
                 case Some(x) => x.succeed
               }
    } yield {
      Some((entity.id, inventoryDitService.getInventoryStatus(dit) ))
    }).mapError(e => new Exception(e.msg))
  }
}


/**
 * Retrieve the uuid from the Mother Board Id
 */
class FromMotherBoardUuidIdFinder(
    ldapConnectionProvider:LDAPConnectionProvider[RoLDAPConnection],
    dit:InventoryDit,
    inventoryDitService:InventoryDitService
) extends MachineDNFinderAction with Loggable {

  //the onlyTypes is an AND filter
  override def tryWith(entity:MachineInventory) : Task[Option[(MachineUuid,InventoryStatus)]] = {
    entity.mbUuid match {
      case None       => None.succeed
      case Some(uuid) =>
        //build filter
        val uuidFilter = AND(HAS(A_MACHINE_UUID), EQ(A_MB_UUID,uuid.value))

        (for {
          con     <- ldapConnectionProvider
          entries <- con.searchOne(dit.MACHINES.dn, uuidFilter, A_MACHINE_UUID)
          res     <- (if(entries.size >= 1) {
                      /*
                       * that means that several os have the same public key, probably they should be
                       * merge. Notify the human merger service for candidate.
                       * For that case, take the first one
                       */
                      InventoryLogger.info("Several ids found with UUID '%s':".format(uuid.value)) *>
                      ZIO.foreach(entries) { e =>
                        InventoryLogger.info(s"-> ${e(A_MACHINE_UUID).get}")
                      } *>
                      Some((MachineUuid(entries(0)(A_MACHINE_UUID).get),inventoryDitService.getInventoryStatus(dit))).succeed
                    } else None.succeed)
        } yield {
          res
        }).mapError(e => new Exception(e.msg))
    }
  }
}

