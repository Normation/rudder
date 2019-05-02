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

import com.normation.inventory.domain._
import com.unboundid.ldap.sdk.DN


/**
 * Example of a DIT service in a configuration where there is
 * three types of inventories: accepted, pending and removed ones (we
 * could imagine that in that configuration scheme, refused
 * inventories are simply erased to save space).
 */
class InventoryDitServiceImpl(
     pending:InventoryDit
   , accepted:InventoryDit
   , removed:InventoryDit) extends InventoryDitService {
  /*
   * Sort dit by their base dn. If two base_dn share a common root,
   * the longer one comes first, for ex:
   * rootDns = [  "dc=test,dc=com" , "dc=test", "dc=bar" ]
   */
  private val baseDns = Seq(pending, accepted, removed).
    map(dit => (dit.BASE_DN,dit)).
    sortWith{ (x,y) => x._1.compareTo(y._1) >= 0 }

  override def getDit(dn:DN) : Option[InventoryDit] = {
    ( (Option.empty[InventoryDit]) /: baseDns ) {
      case (Some(x), _) => Some(x)
      case (_      , (baseDn,dit) ) if(baseDn.isAncestorOf(dn,true)) => Some(dit)
      case _            => None
    }
  }


  def getDit(status:InventoryStatus) : InventoryDit = status match {
    case PendingInventory => pending
    case AcceptedInventory => accepted
    case RemovedInventory => removed
  }

  def getInventoryStatus(dit : InventoryDit) : InventoryStatus = {
    if(dit == pending) PendingInventory
    else if(dit == accepted) AcceptedInventory
    else if(dit == removed) RemovedInventory
    else throw new IllegalArgumentException("DIT with name '%s' is not associated with any inventory status".format(dit.name))
  }

  def getSoftwareBaseDN : DN = accepted.SOFTWARE.dn

}
