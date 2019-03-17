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

package com.normation.inventory.ldap.core

import scala.collection.mutable.Buffer
import com.normation.inventory.domain.InventoryResult._
import com.normation.ldap.sdk.LDAPEntry
import com.normation.inventory.domain.FullInventory
import com.normation.ldap.sdk.LDAPTree
import scalaz.zio._
import scalaz.zio.syntax._
import com.normation.errors._

class FullInventoryFromLdapEntriesImpl(
    inventoryDitService: InventoryDitService
  , mapper             : InventoryMapper
) extends FullInventoryFromLdapEntries {


  //a dit without base dn
  override def fromLdapEntries(entries:Seq[LDAPEntry]) : InventoryResult[FullInventory] = {
    val serverElts = Buffer[LDAPEntry]()
    val machineElts = Buffer[LDAPEntry]()

    for {
      entry <- entries
      dit   <- inventoryDitService.getDit(entry.dn)
    } {
      if(entry.dn.getRDNs.contains(dit.NODES.rdn)) {
        serverElts += entry
      } else if(entry.dn.getRDNs.contains(dit.MACHINES.rdn)) {
        machineElts += entry
      } //else ignore, not a server/machine related entry
    }

    (for {
      nodeTree   <- LDAPTree(serverElts)
      node       <- mapper.nodeFromTree(nodeTree)
      optMachine <- if(machineElts.isEmpty) None.succeed
                    else LDAPTree(machineElts).flatMap(t => mapper.machineFromTree(t).map(m => Some(m) ))
    } yield {
      FullInventory(node, optMachine)
    }).bridgeError()
  }
}

