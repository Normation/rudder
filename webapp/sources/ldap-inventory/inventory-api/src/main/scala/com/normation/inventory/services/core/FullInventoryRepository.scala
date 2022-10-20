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

package com.normation.inventory.services.core

import com.normation.errors._
import com.normation.inventory.domain._

trait ReadOnlyMachineRepository {

  /**
   * Get the machine with the given ID.
   *
   * A machine could exists in several status, but
   * it should not. In that case, return the machine with the most
   * prioritary status (Accepted > Pending > Removed)
   */
  def getMachine(id: MachineUuid): IOResult[Option[MachineInventory]]

}

/**
 * That trait handle write operation on the Machine Repository.
 * The R parameter is the return type of operation, which should be a diff
 * resulting from the modification, but could be the new version of the entity
 * if the store can not give better information.
 *
 *
 * Machine should be unique among all available status, so we don't have to
 * specify there status when searching for them. If they are not,
 * we always consider a priority order Accepted > Pending > Removed
 *
 */
trait WriteOnlyMachineRepository[R] {

  /**
   * Save the given machine. No verification will be made upon an
   * existing machine with same id elsewhere in the repository (so
   * that you will actually get a copy if there is already a machine
   * with a different inventoryStatus).0
   */
  def save(machine: MachineInventory): IOResult[R]

  /**
   * Delete the corresponding machine. Does not fail if the machine does
   * not exists.
   *
   * That method should take all action needed to assure that consistency
   * is kept, especially deleting reference to that machine.
   */
  def delete(id: MachineUuid): IOResult[R]

  /**
   * Change the status of a machine.
   * That method should take ALL action to keep consistency, especially:
   * - moving a machine to a status where the same machine exists is
   *   equivalent to DELETING the old status EVEN if the two machine are different
   * - changing the status of a machine should change all reference to the
   *   machine accordingly
   */
  def move(id: MachineUuid, into: InventoryStatus): IOResult[R]
}

trait MachineRepository[R] extends ReadOnlyMachineRepository with WriteOnlyMachineRepository[R]

trait ReadOnlyFullInventoryRepository {

  /**
   * Retrieve a full ServerAndMachine.
   * TODO: allows to lazy-load some heavy parts, like software, machine elements, etc.
   */
  def get(id:          NodeId, inventoryStatus: InventoryStatus): IOResult[Option[FullInventory]]
  def get(id:          NodeId): IOResult[Option[FullInventory]]
  def getMachineId(id: NodeId, inventoryStatus: InventoryStatus): IOResult[Option[(MachineUuid, InventoryStatus)]]

  def getAllInventories(inventoryStatus: InventoryStatus): IOResult[Map[NodeId, FullInventory]]

  def getAllNodeInventories(inventoryStatus: InventoryStatus): IOResult[Map[NodeId, NodeInventory]]
}

trait WriteOnlyFullInventoryRepository[R] {
  def save(serverAndMachine: FullInventory): IOResult[R]
  def delete(id:             NodeId, inventoryStatus: InventoryStatus): IOResult[R]
  def move(id:               NodeId, from:            InventoryStatus, into: InventoryStatus): IOResult[R]

  def moveNode(id: NodeId, from: InventoryStatus, into: InventoryStatus): IOResult[R]
}

trait FullInventoryRepository[R] extends ReadOnlyFullInventoryRepository with WriteOnlyFullInventoryRepository[R]
