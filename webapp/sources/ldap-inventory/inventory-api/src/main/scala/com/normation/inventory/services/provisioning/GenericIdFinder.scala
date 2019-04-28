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

import com.normation.errors._
import com.normation.inventory.domain._
import net.liftweb.common._
import com.normation.utils.HashcodeCaching
import scalaz.zio._
import scalaz.zio.syntax._

/*
 * Implementation of IdFinderAction that is pipelinable
 * for servers
 */
sealed case class NamedNodeInventoryDNFinderAction(val name:String,val action:NodeInventoryDNFinderAction) extends HashcodeCaching

class NodeInventoryDNFinderService(actions: Seq[NamedNodeInventoryDNFinderAction]) extends NodeInventoryDNFinderAction {

  override def tryWith(entity: NodeInventory) : IOResult[Option[(NodeId, InventoryStatus)]] = {
    ZIO.foldLeft(actions)(Option.empty[(NodeId, InventoryStatus)]) {
      case (Some(found), next) => Some(found).succeed
      case (None       , next) =>
        InventoryLogger.debug(s"Processing node id finder '${next.name}'") *>
        next.action.tryWith(entity).flatMap {
          case Some((id, s)) => InventoryLogger.debug(s"Node Id '${id.value}' found in DIT '${s.name}' with id finder '${next.name}'") *> Some((id, s)).succeed
          case None          => InventoryLogger.trace(s"'Node id '${entity.main.id.value}' not found with findder '${next.name}'") *> None.succeed
      }
    }.flatMap {
      case None => InventoryLogger.debug(s"'Node id '${entity.main.id.value}' not found in base") *> None.succeed
      case x    => x.succeed
    }
  }
}

/*
 * Implementation of IdFinderAction that is pipelinable
 * for machines
 */
sealed case class NamedMachineDNFinderAction(val name:String,val action:MachineDNFinderAction) extends HashcodeCaching

class MachineDNFinderService(actions:Seq[NamedMachineDNFinderAction]) extends MachineDNFinderAction with Loggable {

  override def tryWith(entity: MachineInventory) : IOResult[Option[(MachineUuid,InventoryStatus)]] = {
    ZIO.foldLeft(actions)(Option.empty[(MachineUuid, InventoryStatus)]) {
      case (Some(found), next) => Some(found).succeed
      case (None       , next) =>
        InventoryLogger.debug(s"Processing machine id finder '${next.name}'") *>
        next.action.tryWith(entity).flatMap {
          case Some((id, s)) => InventoryLogger.debug(s"Machine Id '${id.value}' found in DIT '${s.name}' with id finder '${next.name}'") *> Some((id, s)).succeed
          case None          => InventoryLogger.trace(s"'Machine id '${entity.id.value}' not found with findder '${next.name}'") *> None.succeed
      }
    }.flatMap {
      case None => InventoryLogger.debug(s"'Machoine id '${entity.id.value}' not found in base") *> None.succeed
      case x    => x.succeed
    }
  }
}

/*
 * No implementation for software, because they must be handle in
 * mass to keep performance OK.
 */
