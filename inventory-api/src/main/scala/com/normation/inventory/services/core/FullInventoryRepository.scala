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

package com.normation.inventory.services.core

import com.normation.inventory.domain._
import net.liftweb.common.Box

trait ReadOnlyMachineRepository {
  /**
   * Retrieve a full ServerAndMachine. 
   * TODO: allows to lazy-load some heavy parts, like software, machine elements, etc.
   */
  def get(id:MachineUuid, inventoryStatus : InventoryStatus) : Box[MachineInventory]
  

}

/**
 * That trait handle write operation on the Machine Repository.
 * The R parameter is the return type of operation, which should be a diff
 * resulting from the modification, but could be the new version of the entity
 * if the store can not give better information.
 */
trait WriteOnlyMachineRepository[R] {
  
  /**
   * Save the given machine. No verification will be made upon an
   * existing machine with same id elsewhere in the repository (so
   * that you will actually get a copy if there is already a machine
   * with a different inventoryStatus).0
   */
  def save(machine:MachineInventory) : Box[R]
  def delete(id:MachineUuid, inventoryStatus : InventoryStatus) : Box[R] 
 // def copy(id:MachineUuid, from: InventoryStatus, into : InventoryStatus) : Box[MachineInventory]
  def move(id:MachineUuid, from: InventoryStatus, into : InventoryStatus) : Box[R]
}

trait MachineRepository[R] extends ReadOnlyMachineRepository with WriteOnlyMachineRepository[R]

trait ReadOnlyFullInventoryRepository {
  /**
   * Retrieve a full ServerAndMachine. 
   * TODO: allows to lazy-load some heavy parts, like software, machine elements, etc.
   */
  def get(id:NodeId, inventoryStatus : InventoryStatus) : Box[FullInventory]
  def getMachineId(id:NodeId, inventoryStatus : InventoryStatus) : Box[(MachineUuid, InventoryStatus)]
  
  /**
   * For a given machine, find all the node on it
   */
  def getNodes(id:MachineUuid, inventoryStatus : InventoryStatus) : Box[Seq[NodeId]]
}

trait WriteOnlyFullInventoryRepository[R] {
  def save(serverAndMachine:FullInventory, inventoryStatus : InventoryStatus) : Box[R]
  def delete(id:NodeId, inventoryStatus : InventoryStatus) : Box[R] 
//  def copy(id:NodeId, from: InventoryStatus, into : InventoryStatus) : Box[ServerAndMachine]
  def move(id:NodeId, from: InventoryStatus, into : InventoryStatus) : Box[R]
}

trait FullInventoryRepository[R] extends ReadOnlyFullInventoryRepository with WriteOnlyFullInventoryRepository[R]

