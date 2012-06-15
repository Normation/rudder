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

package com.normation.inventory.domain

import org.joda.time.DateTime
import com.normation.utils.HashcodeCaching

sealed trait PhysicalElement {
  def description:Option[String]
  def quantity : Int
}

case class Bios(
  name:String,
  description:Option[String] = None,
  version:Option[Version] = None,
  editor:Option[SoftwareEditor] = None,
  releaseDate:Option[DateTime] = None,
  quantity:Int = 1
) extends PhysicalElement with HashcodeCaching

case class Controller(
  name:String,
  description:Option[String] = None,
  manufacturer : Option[Manufacturer] = None,
  cType : Option[String] = None,
  quantity:Int = 1
) extends PhysicalElement with HashcodeCaching

case class MemorySlot (
  slotNumber: String, //string, because sometime it looks like: RAM slot #0
  name:Option[String] = None,
  description:Option[String] = None,
  capacity : Option[MemorySize] = None,
  caption : Option[String] = None,
  speed : Option[String] = None,
  memType : Option[String] = None,
  serialNumber : Option[String] = None,
  quantity:Int = 1
) extends PhysicalElement with HashcodeCaching

case class Port(
  name:String,
  description:Option[String] = None,
  pType : Option[String] = None,
  quantity:Int = 1
) extends PhysicalElement with HashcodeCaching

case class Processor(
  name:String,
  description:Option[String] = None,
  speed : Option[Int] = None,
  stepping : Option[Int] = None,
  model : Option[String] = None, 
  family : Option[String] = None,
  manufacturer : Option[Manufacturer] = None,
  quantity:Int = 1
) extends PhysicalElement with HashcodeCaching

case class Slot(
  name:String,
  description:Option[String] = None,
  status : Option[String] = None,
  quantity:Int = 1
) extends PhysicalElement with HashcodeCaching

case class Sound(
  name:String,
  description:Option[String] = None,
  quantity:Int = 1
) extends PhysicalElement with HashcodeCaching

case class Storage(
  name:String,
  description:Option[String] = None,
  size : Option[MemorySize] = None,
  firmware : Option[String] = None,
  manufacturer : Option[Manufacturer] = None,
  model : Option[String] = None,
  serialNumber : Option[String] = None,
  sType : Option[String] = None,
  quantity:Int = 1
) extends PhysicalElement with HashcodeCaching

case class Video (
  name:String,
  description:Option[String] = None,
  chipset : Option[String] = None,
  memory : Option[MemorySize] = None,
  resolution : Option[String] = None,
  quantity:Int = 1
) extends PhysicalElement with HashcodeCaching


/**
 * Specific VM subtype, like Xen or VirtualBox
 * The name is an identifier used for 
 * (de)serialization to string, of as a key
 * for the VM type. They should be lower case only.
 */
sealed abstract class VmType(val name:String)
case object UnknownVmType extends VmType("unknown") with HashcodeCaching
case object SolarisZone extends VmType("solariszone") with HashcodeCaching
case object VirtualBox extends VmType("vbox") with HashcodeCaching
case object VMWare extends VmType("vmware") with HashcodeCaching
case object QEmu extends VmType("qemu") with HashcodeCaching
case object Xen extends VmType("xen") with HashcodeCaching


/**
 * The different machine type. For now, we know
 * two of them: 
 * - virtual machines ;
 * - physical machines.
 */
sealed abstract class MachineType
case class VirtualMachineType(vm:VmType) extends MachineType with HashcodeCaching
case object PhysicalMachineType extends MachineType with HashcodeCaching


case class MachineInventory(
  id:MachineUuid,
  status:InventoryStatus,
  machineType:MachineType,
  name:Option[String] = None,
  mbUuid : Option[MotherBoardUuid] = None,
  inventoryDate: Option[DateTime] = None,
  bios : Seq[Bios] = Nil,
  controllers : Seq[Controller] = Nil,
  memories : Seq[MemorySlot] = Nil,
  ports : Seq[Port] = Nil,
  processors : Seq[Processor] = Nil,
  slots : Seq[Slot] = Nil,
  sounds : Seq[Sound] = Nil,
  storages : Seq[Storage] = Nil,
  videos : Seq[Video] = Nil
) extends HashcodeCaching
