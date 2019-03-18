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
import com.normation.inventory.ldap.core._
import LDAPConstants._
import com.normation.inventory.domain.InventoryResult._
import com.normation.inventory.domain._
import scalaz.zio._
import scalaz.zio.syntax._
import com.normation.ldap.sdk.LdapResult._


trait NodeInventoryDNFinder extends NodeInventoryDNFinderAction

/**
 * Find the UUID in the whole LDAP and find if the uuid is already use
 *
 */
class UseExistingNodeIdFinder(inventoryDitService:InventoryDitService, ldap:LDAPConnectionProvider[RoLDAPConnection], rootDN:DN) extends NodeInventoryDNFinder {
  override def tryWith(entity:NodeInventory) : InventoryResult[Option[(NodeId,InventoryStatus)]] = {
    for {
      con   <- ldap
      entry <- con.searchSub(rootDN, AND(IS(OC_NODE), EQ(A_NODE_UUID, entity.main.id.value)), "1.1").map(_.headOption) //TODO: error if more than one !! #555
    } yield {
      entry.flatMap(e => inventoryDitService.getDit(e.dn)).map(dit =>
        (entity.main.id, inventoryDitService.getInventoryStatus(dit) )
      )
    }
  }
}
