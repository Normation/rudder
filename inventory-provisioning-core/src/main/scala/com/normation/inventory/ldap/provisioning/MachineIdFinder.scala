/*
*************************************************************************************
* Copyright 2011 Normation SAS
*************************************************************************************
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Affero General Public License as
* published by the Free Software Foundation, either version 3 of the
* License, or (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU Affero GPL v3, the copyright holders add the following
* Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU Affero GPL v3
* licence, when you create a Related Module, this Related Module is
* not considered as a part of the work and may be distributed under the
* license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Affero General Public License for more details.
*
* You should have received a copy of the GNU Affero General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/agpl.html>.
*
*************************************************************************************
*/

package com.normation.inventory.ldap.provisioning

import com.normation.inventory.services.provisioning._
import com.unboundid.ldap.sdk.DN
import com.normation.ldap.sdk._
import BuildFilter._
import com.normation.utils.StringUuidGenerator
import com.normation.inventory.domain._
import com.normation.inventory.ldap.core._
import LDAPConstants._

import scala.collection.mutable.Buffer

import net.liftweb.common._

/**
 * Retrieve the ID from the LDAP directory
 */
class UseExistingMachineIdFinder(
    inventoryDitService:InventoryDitService,
    ldap:LDAPConnectionProvider,
    rootDN:DN
) extends MachineDNFinderAction {
  override def tryWith(entity:MachineInventory) : Box[(MachineUuid, InventoryStatus)] = {
    for {
      con <- ldap
      entry <- con.searchSub(rootDN, AND(IS(OC_MACHINE),EQ(A_MACHINE_UUID, entity.id.value)), "1.1").headOption //TODO: error if more than one !! #555
      dit <- inventoryDitService.getDit(entry.dn)
    } yield {
      (entity.id, inventoryDitService.getInventoryStatus(dit) )
    }
  }
}


/**
 * Retrieve the uuid from the Mother Board Id
 */
class FromMotherBoardUuidIdFinder(
    ldapConnectionProvider:LDAPConnectionProvider,
    dit:InventoryDit,
    inventoryDitService:InventoryDitService
) extends MachineDNFinderAction with Loggable {

  //the onlyTypes is an AND filter
  override def tryWith(entity:MachineInventory) : Box[(MachineUuid,InventoryStatus)] = {
    entity.mbUuid match {
      case None => Empty
      case Some(uuid) => {
        //build filter
        val uuidFilter = AND(HAS(A_MACHINE_UUID),EQ(A_MB_UUID,uuid.value))

        ldapConnectionProvider.flatMap { con =>
          val entries = con.searchOne(dit.MACHINES.dn, uuidFilter, A_MACHINE_UUID)
          if(entries.size >= 1) {
            if(entries.size > 1) {
            /*
             * that means that several os have the same public key, probably they should be
             * merge. Notify the human merger service for candidate.
             * For that case, take the first one
             */
            //TODO : notify merge
              logger.info("Several ids found with UUID '%s':".format(uuid.value))
              for(e <- entries) {
                logger.info("-> %s".format(e(A_MACHINE_UUID).get))
              }
            }
            Full(MachineUuid(entries(0)(A_MACHINE_UUID).get),inventoryDitService.getInventoryStatus(dit))
          } else Empty
        }
      }
    }
  }
}

