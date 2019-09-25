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

package com.normation.inventory.services.provisioning

import com.normation.inventory.domain._
import net.liftweb.common._


/*
 * Implementation of IdFinderAction that is pipelinable
 * for servers
 */
sealed case class NamedNodeInventoryDNFinderAction(val name:String,val action:NodeInventoryDNFinderAction)

class NodeInventoryDNFinderService(actions:Seq[NamedNodeInventoryDNFinderAction]) extends NodeInventoryDNFinderAction with Loggable {

  override def tryWith(entity:NodeInventory) : Box[(NodeId, InventoryStatus)] = {
    for(a <- actions) {
      logger.debug("Processing server id finder %s".format(a.name))
      a.action.tryWith(entity).foreach { case x@(id,dit) =>
        logger.debug("Server Id '%s' found in DIT '%s' with id finder '%s'".format(id, dit, a.name))
        return Full(x)
      }
    }
    logger.debug("All server finder executed, no id found")
    Empty
  }
}

/*
 * Implementation of IdFinderAction that is pipelinable
 * for machines
 */
sealed case class NamedMachineDNFinderAction(val name:String,val action:MachineDNFinderAction)

class MachineDNFinderService(actions:Seq[NamedMachineDNFinderAction]) extends MachineDNFinderAction with Loggable {

  override def tryWith(entity:MachineInventory) : Box[(MachineUuid,InventoryStatus)] = {
    for(a <- actions) {
      logger.debug("Processing machine id finder %s".format(a.name))
      a.action.tryWith(entity).foreach { case (id,dit) =>
        logger.debug("Machine Id '%s' found with id finder '%s'".format(id,a.name))
        return Full((id,dit))
      }
    }
    logger.debug("All machine finder executed, no id found")
    Empty
  }
}

/*
 * No implementation for software, because they must be handle in
 * mass to keep performance OK.
 */
