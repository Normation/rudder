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

import com.normation.inventory.domain._
import net.liftweb.common._
import com.unboundid.ldap.sdk.DN


/**
 * Example of a DIT service in a configuration where there is 
 * two types of inventories: accepted and pending ones (we 
 * could imagine that in that configuration scheme, refused
 * inventories are simply erased to save space).
 */
class InventoryDitServiceImpl(pending:InventoryDit,accepted:InventoryDit) extends InventoryDitService {
  /*
   * Sort dit by their base dn. If two base_dn share a common root, 
   * the longer one comes first, for ex:
   * rootDns = [  "dc=test,dc=com" , "dc=test", "dc=bar" ]
   */
  private val baseDns = Seq(pending, accepted).
    map(dit => (dit.BASE_DN,dit)).
    sortWith{ (x,y) => x._1.compareTo(y._1) >= 0 }

  override def getDit(dn:DN) : Box[InventoryDit] = {
    ( (Empty:Box[InventoryDit]) /: baseDns ) { 
      case (f:Full[_], _) => f
      case (_, (baseDn,dit) ) if(baseDn.isAncestorOf(dn,true)) => Full(dit)
      case _ => Empty
    }
  }

  
  def getDit(status:InventoryStatus) : InventoryDit = status match {
    case PendingInventory => pending
    case AcceptedInventory => accepted
  }
  
  def getInventoryStatus(dit : InventoryDit) : InventoryStatus = {
    if(dit == pending) PendingInventory
    else if(dit == accepted) AcceptedInventory
    else throw new IllegalArgumentException("DIT with name '%s' is not associated with any inventory status".format(dit.name))
  } 
  
  def getSoftwareBaseDN : DN = accepted.SOFTWARE.dn

}
