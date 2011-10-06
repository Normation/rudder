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

package com.normation.inventory.ldap.core

import scala.collection.mutable.Buffer
import net.liftweb.common._
import com.normation.ldap.sdk.{LDAPTree, LDAPEntry}
import com.normation.inventory.domain.FullInventory

class FullInventoryFromLdapEntriesImpl(
    inventoryDitService:InventoryDitService,
    mapper:InventoryMapper
) extends FullInventoryFromLdapEntries with Loggable {
  
  
  //a dit without base dn
  override def fromLdapEntries(entries:Seq[LDAPEntry]) : Box[FullInventory] = {
    val serverElts = Buffer[LDAPEntry]()
    val machineElts = Buffer[LDAPEntry]()
    
    for {
      entry <- entries
      dit <- inventoryDitService.getDit(entry.dn)
    } {
      if(entry.dn.getRDNs.contains(dit.NODES.rdn)) {
        serverElts += entry
      } else if(entry.dn.getRDNs.contains(dit.MACHINES.rdn)) {
        machineElts += entry
      } //else ignore, not a server/machine related entry
    }

    for {
      nodeTree <- LDAPTree(serverElts) ?~! "Error when building the tree of entries for the node"
      node <- mapper.nodeFromTree(nodeTree)
      optMachine <- LDAPTree(machineElts) match { //can be empty
        case f:Failure => f
        case Empty => Full(None)
        case Full(t) => mapper.machineFromTree(t).map(m => Some(m) )
      }
    } yield {
      FullInventory(node, optMachine)
    }
    
    
  }
}

