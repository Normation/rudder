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

import LDAPConstants._
import com.normation.inventory.domain._
import com.unboundid.ldap.sdk.{Version => UVersion, _ }
import com.normation.ldap.sdk._
import com.normation.ldap.sdk.schema.LDAPObjectClass
import org.joda.time.DateTime
import net.liftweb.common._
import net.liftweb.json._
import Box._
import java.net.InetAddress
import java.net.UnknownHostException
import InetAddressUtils._
import com.normation.utils.Control.sequence


class DateTimeSerializer extends Serializer[DateTime] {
  private val IntervalClass = classOf[DateTime]

  def deserialize(implicit format: Formats): PartialFunction[(TypeInfo, JValue), DateTime] = {
  case (TypeInfo(IntervalClass, _), json) => json match {
    case JObject(JField("datetime", JString(date)) :: Nil) => DateTime.parse(date)
    case x => throw new MappingException("Can't convert " + x + " to DateTime")
  } }

  def serialize(implicit format: Formats): PartialFunction[Any, JValue] = {
  case date: DateTime => JObject(JField("datetime", JString(date.toString())) :: Nil)
  }
}
class InventoryMapper(
    ditService:InventoryDitService
  , pendingDit:InventoryDit
  , acceptedDit:InventoryDit
  , removedDit:InventoryDit
) extends Loggable {

  implicit val formats = Serialization.formats(NoTypeHints) + new DateTimeSerializer

  ////////////////////////////////////////////////////////////
  ///////////////////////// Software /////////////////////////
  ////////////////////////////////////////////////////////////

  //software particularity : always in acceptedDit
  
  def entryFromSoftware(soft:Software) : LDAPEntry = {
    val e = acceptedDit.SOFTWARE.SOFT.model(soft.id)
    e.setOpt(soft.name,        A_NAME,         {x:String => x})
    e.setOpt(soft.description, A_DESCRIPTION,  {x:String => x})
    e.setOpt(soft.version,     A_SOFT_VERSION, {x:Version => x.value})
    e.setOpt(soft.editor,      A_EDITOR,       {x:SoftwareEditor => x.name})
    e.setOpt(soft.releaseDate, A_RELEASE_DATE, {x:DateTime => GeneralizedTime(x).toString})
    soft.license.foreach { lic => 
      e +=! (A_LICENSE_NAME,lic.name) 
      e.setOpt(lic.description,    A_LICENSE_DESC,        {x:String => x})
      e.setOpt(lic.expirationDate, A_LICENSE_EXP,         {x:DateTime => x.toString()})
      e.setOpt(lic.productId,      A_LICENSE_PRODUCT_ID,  {x:String => x})
      e.setOpt(lic.productKey,     A_LICENSE_PRODUCT_KEY, {x:String => x})
      e.setOpt(lic.oem,            A_LICENSE_OEM,         {x:String => x})
    }
    e
  }
  
  def softwareFromEntry(e:LDAPEntry) : Box[Software] = {
    for {
      id <- acceptedDit.SOFTWARE.SOFT.idFromDN(e.dn)
      name        = e(A_NAME)
      desc        = e(A_DESCRIPTION)
      version     = e(A_SOFT_VERSION).map(v => new Version(v))
      releaseDate = e.getAsGTime(A_RELEASE_DATE) map { _.dateTime }
      editor      = e(A_EDITOR) map { x => new SoftwareEditor(x) }
      //licence
      lic = e(A_LICENSE_NAME) map { name =>
        License(
            name
          , description    = e(A_LICENSE_DESC)
          , productId      = e(A_LICENSE_PRODUCT_ID)
          , productKey     = e(A_LICENSE_PRODUCT_KEY)
          , oem            = e(A_LICENSE_OEM)
          , expirationDate = e.getAsGTime(A_LICENSE_EXP) map { _.dateTime }
        )
      }
    } yield {
       Software(id,name,desc,version,editor,releaseDate,lic)
    }
  }

  
  ////////////////////////////////////////////////////////////////////
  ///////////////////////// Machine Elements /////////////////////////
  ////////////////////////////////////////////////////////////////////

  ///////////////////////// Bios /////////////////////////
  
  def entryFromBios(elt:Bios,dit:InventoryDit,machineId:MachineUuid) : LDAPEntry = {
    val e = dit.MACHINES.BIOS.model(machineId,elt.name)
    e.setOpt(elt.description, A_DESCRIPTION, {x:String => x})
    e +=! (A_QUANTITY, elt.quantity.toString)
    e.setOpt(elt.editor,      A_EDITOR,       {x:SoftwareEditor => x.name})
    e.setOpt(elt.releaseDate, A_RELEASE_DATE, {x:DateTime => GeneralizedTime(x).toString})
    e.setOpt(elt.version,     A_SOFT_VERSION, {x:Version => x.value})
    e
  }
  
  def biosFromEntry(e:LDAPEntry) : Box[Bios] = {
    for {
      name <- e(A_BIOS_NAME) ?~! "Missing required attribute %s in entry: %s".format(A_BIOS_NAME, e)
      desc        = e(A_DESCRIPTION)
      quantity    = e.getAsInt(A_QUANTITY).getOrElse(1)
      version     = e(A_SOFT_VERSION).map(v => new Version(v))
      releaseDate = e.getAsGTime(A_RELEASE_DATE) map { _.dateTime }
      editor      = e(A_EDITOR) map { x => new SoftwareEditor(x) }
    } yield {
      Bios( name, desc, version, editor, releaseDate, quantity )
    }
  }
  
  ///////////////////////// Controller /////////////////////////
  
  def entryFromController(elt:Controller,dit:InventoryDit,machineId:MachineUuid) : LDAPEntry = {
    val e = dit.MACHINES.CONTROLLER.model(machineId,elt.name)
    e.setOpt(elt.description, A_DESCRIPTION, {x:String => x})
    e +=! (A_QUANTITY, elt.quantity.toString)
    e.setOpt(elt.manufacturer, A_MANUFACTURER, {x:Manufacturer => x.name})
    e.setOpt(elt.cType, A_SME_TYPE, {x:String => x})
    e
  }
  
  def controllerFromEntry(e:LDAPEntry) : Box[Controller] = {
    for {
      name <- e(A_CONTROLLER_NAME) ?~! "Missing required attribute %s in entry: %s".format(A_CONTROLLER_NAME, e)
      desc = e(A_DESCRIPTION)
      manufacturer = e(A_MANUFACTURER).map(m => new Manufacturer(m))
      smeType = e(A_SME_TYPE)
      quantity = e.getAsInt(A_QUANTITY).getOrElse(1)
    } yield {
      Controller( name, desc, manufacturer, smeType, quantity )
    }
  }
  
  ///////////////////////// MemorySlot /////////////////////////
  
  def entryFromMemorySlot(elt:MemorySlot,dit:InventoryDit,machineId:MachineUuid) : LDAPEntry = {
    val e = dit.MACHINES.MEMORY.model(machineId,elt.slotNumber)
    e.setOpt(elt.description, A_DESCRIPTION, {x:String => x})
    e +=! (A_QUANTITY, elt.quantity.toString)
    e.setOpt(elt.name,A_NAME,{x:String => x})
    e.setOpt(elt.capacity,A_MEMORY_CAPACITY, {x:MemorySize => x.size.toString})
    e.setOpt(elt.caption,A_MEMORY_CAPTION, {x:String => x})
    e.setOpt(elt.speed,A_MEMORY_SPEED, {x:String => x})
    e.setOpt(elt.memType,A_MEMORY_TYPE, {x:String => x})
    e.setOpt(elt.serialNumber,A_SERIAL_NUMBER, {x:String => x})
    e
  }
  
  def memorySlotFromEntry(e:LDAPEntry) : Box[MemorySlot] = {
    for {
      slotNumber <- e(A_MEMORY_SLOT_NUMBER) ?~! "Missing required attribute %s in entry: %s".format(A_MEMORY_SLOT_NUMBER, e)
      name = e(A_NAME)
      desc = e(A_DESCRIPTION)
      quantity = e.getAsInt(A_QUANTITY).getOrElse(1)
      capacity = e(A_MEMORY_CAPACITY).map{x => MemorySize(x)}
      caption = e(A_MEMORY_CAPTION)
      speed = e(A_MEMORY_SPEED)
      memType = e(A_MEMORY_TYPE)
      serial = e(A_SERIAL_NUMBER)
    } yield {
      MemorySlot(
            slotNumber, name, desc, capacity, caption
          , speed, memType, serial, quantity )
    }
  }

  ///////////////////////// Port /////////////////////////
  
  def entryFromPort(elt:Port,dit:InventoryDit,machineId:MachineUuid) : LDAPEntry = {
    val e = dit.MACHINES.PORT.model(machineId,elt.name)
    e.setOpt(elt.description, A_DESCRIPTION, {x:String => x})
    e +=! (A_QUANTITY, elt.quantity.toString)
    e.setOpt(elt.pType, A_SME_TYPE, {x:String => x})
    e
  }
  
  def portFromEntry(e:LDAPEntry) : Box[Port] = {
    for {
      name <- e(A_PORT_NAME) ?~! "Missing required attribute %s in entry: %s".format(A_PORT_NAME, e)
      desc = e(A_DESCRIPTION)
      smeType = e(A_SME_TYPE)
      quantity = e.getAsInt(A_QUANTITY).getOrElse(1)
    } yield {
      Port( name, desc, smeType, quantity )
    }
  }
  
  ///////////////////////// Processor /////////////////////////
  
  def entryFromProcessor(elt:Processor,dit:InventoryDit,machineId:MachineUuid) : LDAPEntry = {
    val e = dit.MACHINES.CPU.model(machineId,elt.name)
    e.setOpt(elt.description, A_DESCRIPTION,    { x:String => x } )
    e +=! (A_QUANTITY, elt.quantity.toString)
    e.setOpt(elt.speed,A_PROCESSOR_SPEED,       { x:Int => x.toString } )
    e.setOpt(elt.stepping,A_PROCESSOR_STEPPING, { x:Int=> x.toString } )
    e.setOpt(elt.family, A_PROCESSOR_FAMILLY,   { x:Int => x.toString() } )
    e.setOpt(elt.model, A_MODEL,                { x:Int => x.toString() } )
    e.setOpt(elt.manufacturer, A_MANUFACTURER,  { x:Manufacturer => x.name } )
    e.setOpt(elt.core, A_CORE,                  { x:Int => x.toString() } )
    e.setOpt(elt.thread, A_THREAD,              { x:Int => x.toString() } )
    e.setOpt(elt.familyName, A_PROCESSOR_FAMILY_NAME , { x:String => x } )
    e.setOpt(elt.arch, A_PROCESSOR_ARCHITECTURE,       { x:String => x } )
    e.setOpt(elt.cpuid, A_CPUID,                { x:String => x } )
    e.setOpt(elt.externalClock, A_EXTERNAL_CLOCK, {x:Float => x.toString()} )
    e
  }
  
  def processorFromEntry(e:LDAPEntry) : Box[Processor] = {
    for {
      name <- e(A_PROCESSOR_NAME) ?~! "Missing required attribute %s in entry: %s".format(A_PROCESSOR_NAME, e)
      desc     = e(A_DESCRIPTION)
      quantity = e.getAsInt(A_QUANTITY).getOrElse(1)
      speed    = e.getAsInt(A_PROCESSOR_SPEED)
      stepping = e.getAsInt(A_PROCESSOR_STEPPING)
      family   = e.getAsInt(A_PROCESSOR_FAMILLY)
      model    = e.getAsInt(A_MODEL)
      manufacturer = e(A_MANUFACTURER).map(m => new Manufacturer(m))
      core     = e.getAsInt(A_CORE)
      thread   = e.getAsInt(A_THREAD)
      familyName   = e(A_PROCESSOR_FAMILY_NAME)
      arch     = e(A_PROCESSOR_ARCHITECTURE)
      externalClock = e.getAsFloat(A_EXTERNAL_CLOCK)
      cpuid    = e(A_CPUID)
    } yield {
      Processor(
            manufacturer, name, arch, desc, speed, externalClock, core
          , thread, cpuid, stepping, family, familyName, model, quantity )
    }
  }
  
  ///////////////////////// Slot /////////////////////////
  
  def entryFromSlot(elt:Slot,dit:InventoryDit,machineId:MachineUuid) : LDAPEntry = {
    val e = dit.MACHINES.PORT.model(machineId,elt.name)
    e.setOpt(elt.description, A_DESCRIPTION, {x:String => x})
    e +=! (A_QUANTITY, elt.quantity.toString)
    e.setOpt(elt.status, A_STATUS, {x:String => x})
    e
  }
  
  def slotFromEntry(e:LDAPEntry) : Box[Slot] = {
    for {
      name <- e(A_SLOT_NAME) ?~! "Missing required attribute %s in entry: %s".format(A_SLOT_NAME, e)
      desc = e(A_DESCRIPTION)
      status = e(A_STATUS)
      quantity = e.getAsInt(A_QUANTITY).getOrElse(1)
    } yield {
      Slot( name, desc, status, quantity )
    }
  }
  
  ///////////////////////// Sound /////////////////////////
  
  def entryFromSound(elt:Sound,dit:InventoryDit,machineId:MachineUuid) : LDAPEntry = {
    val e = dit.MACHINES.SOUND.model(machineId,elt.name)
    e.setOpt(elt.description, A_DESCRIPTION, {x:String => x})
    e +=! (A_QUANTITY, elt.quantity.toString)
    e
  }
  
  def soundFromEntry(e:LDAPEntry) : Box[Sound] = {
    for {
      name <- e(A_SOUND_NAME) ?~! "Missing required attribute %s in entry: %s".format(A_SOUND_NAME, e)
      desc = e(A_DESCRIPTION)
      quantity = e.getAsInt(A_QUANTITY).getOrElse(1)
    } yield {
      Sound( name, desc, quantity )
    }
  }
  
  ///////////////////////// Storage /////////////////////////
  
  def entryFromStorage(elt:Storage,dit:InventoryDit,machineId:MachineUuid) : LDAPEntry = {
    val e = dit.MACHINES.STORAGE.model(machineId,elt.name)
    e.setOpt(elt.description, A_DESCRIPTION, {x:String => x})
    e +=! (A_QUANTITY, elt.quantity.toString)
    e.setOpt(elt.size,A_STORAGE_SIZE, {x:MemorySize => x.size.toString})
    e.setOpt(elt.firmware,A_STORAGE_FIRMWARE, {x:String => x})
    e.setOpt(elt.manufacturer,A_MANUFACTURER, {x:Manufacturer => x.name})
    e.setOpt(elt.model,A_MODEL, {x:String => x})
    e.setOpt(elt.serialNumber,A_SERIAL_NUMBER, {x:String => x})
    e.setOpt(elt.sType,A_SME_TYPE, {x:String => x})
    
    e
  }
  
  def storageFromEntry(e:LDAPEntry) : Box[Storage] = {
    for {
      name <- e(A_STORAGE_NAME) ?~! "Missing required attribute %s in entry: %s".format(A_STORAGE_NAME,e)
      desc = e(A_DESCRIPTION)
      size = e(A_STORAGE_SIZE).map{x => MemorySize(x)}
      firmware = e(A_STORAGE_FIRMWARE)
      manufacturer = e(A_MANUFACTURER).map( m => new Manufacturer(m))
      model = e(A_MODEL)
      serialNumber = e(A_SERIAL_NUMBER)
      smeType = e(A_SME_TYPE)
      quantity = e.getAsInt(A_QUANTITY).getOrElse(1)
    } yield {
      Storage( name, desc, size, firmware, manufacturer
          , model, serialNumber, smeType, quantity )
    }
  }

  ///////////////////////// Video /////////////////////////
  
  
  def entryFromVideo(elt:Video,dit:InventoryDit,machineId:MachineUuid) : LDAPEntry = {
    val e = dit.MACHINES.VIDEO.model(machineId,elt.name)
    e.setOpt(elt.description, A_DESCRIPTION, {x:String => x})
    e +=! (A_QUANTITY, elt.quantity.toString)
    e.setOpt(elt.chipset,A_VIDEO_CHIPSET, {x:String => x})
    e.setOpt(elt.memory,A_MEMORY_CAPACITY, {x:MemorySize => x.size.toString})
    e.setOpt(elt.resolution,A_VIDEO_RESOLUTION, {x:String => x})
    e
  }
  
  def videoFromEntry(e:LDAPEntry) : Box[Video] = {
    for {
      name <- e(A_VIDEO_NAME) ?~! "Missing required attribute %s in entry: %s".format(A_VIDEO_NAME, e)
      desc = e(A_DESCRIPTION)
      quantity = e.getAsInt(A_QUANTITY).getOrElse(1)
      chipset = e(A_VIDEO_CHIPSET)
      memory = e(A_MEMORY_CAPACITY).map{x => MemorySize(x)}
      resolution = e (A_VIDEO_RESOLUTION)
    } yield {
      Video( name, desc, chipset, memory, resolution, quantity )
    }
  }
  
  //////////////////// Machine ////////////////////
  
  //perhaps that should be in DIT ?
  //def machineType2Filter(mt : MachineType) : Filter = BuildFilter.IS(machineType2ObjectClass(mt).name)
  
  private[this] def machineType2ObjectClass(mt : MachineType) : LDAPObjectClass = {
    mt match {
      case VirtualMachineType(UnknownVmType) => OC(OC_VM)
      case VirtualMachineType(VirtualBox) => OC(OC_VM_VIRTUALBOX)
      case VirtualMachineType(Xen) => OC(OC_VM_XEN)
      case VirtualMachineType(VMWare) => OC(OC_VM_VMWARE)
      case VirtualMachineType(SolarisZone) => OC(OC_VM_SOLARIS_ZONE)
      case VirtualMachineType(QEmu) => OC(OC_VM_QEMU)
      case PhysicalMachineType => OC(OC_PM)
    }
  }
  
  private[this] def machineTypeFromObjectClasses(objectClassNames:Set[String]) = {
    def objectClass2MachineType(oc : LDAPObjectClass) : Option[MachineType] = {
      oc match {
        case LDAPObjectClass(OC_VM,_,_,_)              => Some(VirtualMachineType(UnknownVmType))
        case LDAPObjectClass(OC_VM_VIRTUALBOX,_,_,_)   => Some(VirtualMachineType(VirtualBox))
        case LDAPObjectClass(OC_VM_XEN,_,_,_)          => Some(VirtualMachineType(Xen))
        case LDAPObjectClass(OC_VM_VMWARE,_,_,_)       => Some(VirtualMachineType(VMWare))
        case LDAPObjectClass(OC_VM_SOLARIS_ZONE,_,_,_) => Some(VirtualMachineType(SolarisZone))
        case LDAPObjectClass(OC_VM_QEMU,_,_,_)         => Some(VirtualMachineType(QEmu))
        case LDAPObjectClass(OC_PM,_,_,_)              => Some(PhysicalMachineType)
        case _ => None
      }
    }
    val machineTypes = objectClassNames.filter(x => machineTypesNames.exists(y => x.toLowerCase == y.toLowerCase))
    val types = OC.demux(machineTypes.toSeq:_*) - OC(OC_MACHINE)
    if(types.size == 1) objectClass2MachineType(types.head) else None
  }
  
  def treeFromMachine(machine:MachineInventory) : LDAPTree = {
    //the root entry of the tree: the machine inventory
    val dit = ditService.getDit(machine.status)
    val root = dit.MACHINES.MACHINE.model(machine.id)
    root.setOpt(machine.mbUuid, A_MB_UUID, {x:MotherBoardUuid => x.value})
    root += (A_OC, machineType2ObjectClass(machine.machineType).name)
    root.setOpt(machine.inventoryDate,A_INVENTORY_DATE,{x:DateTime => GeneralizedTime(x).toString})
    root.setOpt(machine.receiveDate,A_RECEIVE_DATE,{x:DateTime => GeneralizedTime(x).toString})
    root.setOpt(machine.name,A_NAME,{x:String => x})

    val tree = LDAPTree(root)
    //now, add machine elements as children
    machine.bios.foreach        { x => tree.addChild(entryFromBios(x, dit, machine.id)) }
    machine.controllers.foreach { x => tree.addChild(entryFromController(x, dit, machine.id)) }
    machine.memories.foreach    { x => tree.addChild(entryFromMemorySlot(x, dit, machine.id)) }
    machine.ports.foreach       { x => tree.addChild(entryFromPort(x, dit, machine.id)) }
    machine.processors.foreach  { x => tree.addChild(entryFromProcessor(x, dit, machine.id)) }
    machine.slots.foreach       { x => tree.addChild(entryFromSlot(x, dit, machine.id)) }
    machine.sounds.foreach      { x => tree.addChild(entryFromSound(x, dit, machine.id)) }
    machine.storages.foreach    { x => tree.addChild(entryFromStorage(x, dit, machine.id)) }
    machine.videos.foreach      { x => tree.addChild(entryFromVideo(x, dit, machine.id)) }
    
    //ok !
    tree
  }
  
  /*
   * Utility method that do the lookup between an LDAPEntry
   * and the matching machine elements. 
   * It adds it to a machine, return the modified machine.
   * If the mapping goes bad or the entry type is unknown, the
   * MachineInventory is returned as it was, and the error is logged
   * TODO: make that being a strategy
   */
  private[this] def mapAndAddMachineElement(entry:LDAPEntry, machine:MachineInventory) : MachineInventory = {
    def log(e:EmptyBox, elt:String) : MachineInventory = {
      val error = e ?~! "Error when mapping LDAP entry to a %s. Entry details: %s".format(elt,entry)
      logger.error(error.messageChain)
      machine
    }
    
    entry match {
      case e if(e.isA(OC_MEMORY)) => memorySlotFromEntry(e) match {
        case e:EmptyBox => log(e, "memory slot")
        case Full(x) => machine.copy( memories = x +: machine.memories)
      }
      case e if(e.isA(OC_PORT)) => portFromEntry(e) match {
        case e:EmptyBox => log(e, "port")
        case Full(x) => machine.copy( ports = x +: machine.ports)
      }
      case e if(e.isA(OC_SLOT)) => slotFromEntry(e) match {
        case e:EmptyBox => log(e, "slot")
        case Full(x) => machine.copy( slots = x +: machine.slots)
      }
      case e if(e.isA(OC_SOUND)) => soundFromEntry(e) match {
        case e:EmptyBox => log(e, "sound card")
        case Full(x) => machine.copy( sounds = x +: machine.sounds)
      }
      case e if(e.isA(OC_BIOS)) => biosFromEntry(e) match {
        case e:EmptyBox => log(e, "bios")
        case Full(x) => machine.copy( bios = x +: machine.bios)
      }
      case e if(e.isA(OC_CONTROLLER)) => controllerFromEntry(e) match {
        case e:EmptyBox => log(e, "controller")
        case Full(x) => machine.copy( controllers = x +: machine.controllers)
      }
      case e if(e.isA(OC_PROCESSOR)) => processorFromEntry(e) match {
        case e:EmptyBox => log(e, "processor")
        case Full(x) => machine.copy( processors = x +: machine.processors)
      }
      case e if(e.isA(OC_STORAGE)) => storageFromEntry(e) match {
        case e:EmptyBox => log(e, "storage")
        case Full(x) => machine.copy( storages = x +: machine.storages)
      }
      case e if(e.isA(OC_VIDEO)) => videoFromEntry(e) match {
        case e:EmptyBox => log(e, "video")
        case Full(x) => machine.copy( videos = x +: machine.videos)
      }
      case e => 
        logger.error("Unknown entry type for a machine element, that entry will be ignored: %s".format(e))
        machine
    }
    
  }
  
  def machineFromTree(tree:LDAPTree) : Box[MachineInventory] = {
    for {
      dit <- ditService.getDit(tree.root().dn)
      inventoryStatus = ditService.getInventoryStatus(dit)
      id <- dit.MACHINES.MACHINE.idFromDN(tree.root.dn) ?~! "Missing required ID attribute"
      machineType <- machineTypeFromObjectClasses(tree.root().valuesFor(A_OC).toSet) ?~! "Can not find machine types"
      name = tree.root()(A_NAME)
      mbUuid = tree.root()(A_MB_UUID) map { x => MotherBoardUuid(x) }
      inventoryDate = tree.root.getAsGTime(A_INVENTORY_DATE).map { _.dateTime }
      receiveDate = tree.root.getAsGTime(A_RECEIVE_DATE).map { _.dateTime }
      //now, get all subentries
    } yield {
      val m = MachineInventory(id,inventoryStatus,machineType,name,mbUuid,inventoryDate, receiveDate )
      //map subentries and return result
      (m /: tree.children()) { case (m,(rdn,t)) => mapAndAddMachineElement(t.root(),m) }
    }
  }
  
  ///////////////////////////////////////////////////////////////////////////////////////////////
  /////////////////////////////////// ServerInventory mapping ///////////////////////////////////
  ///////////////////////////////////////////////////////////////////////////////////////////////
  
  ///////////////////////// FileSystem /////////////////////////

  def entryFromFileSystem(elt:FileSystem, dit:InventoryDit, serverId:NodeId) : LDAPEntry = {
    val e = dit.NODES.FILESYSTEM.model(serverId,elt.mountPoint)
    e.setOpt(elt.name,        A_NAME,        {x:String => x})
    e.setOpt(elt.description, A_DESCRIPTION, {x:String => x})
    e.setOpt(elt.fileCount,   A_FILE_COUNT,  {x:Int => x.toString})
    e.setOpt(elt.freeSpace,   A_FREE_SPACE,  {x:MemorySize => x.size.toString})
    e.setOpt(elt.totalSpace,  A_TOTAL_SPACE, {x:MemorySize => x.size.toString})
    e
  }
  
  def fileSystemFromEntry(e:LDAPEntry) : Box[FileSystem] = {
    for {
      mountPoint <- e(A_MOUNT_POINT) ?~! "Missing required attribute %s in entry: %s".format(A_MOUNT_POINT, e)
      name       = e(A_NAME)
      desc       = e(A_DESCRIPTION)
      fileCount  = e.getAsInt(A_FILE_COUNT)
      freeSpace  = e.getAsLong(A_FREE_SPACE).map(new MemorySize(_))
      totalSpace = e.getAsLong(A_TOTAL_SPACE).map(new MemorySize(_))
    } yield {
      FileSystem(mountPoint, name, desc, fileCount, freeSpace, totalSpace)
    }
  }
 
  ///////////////////////// Networks /////////////////////////


  def entryFromNetwork(elt:Network, dit:InventoryDit, serverId:NodeId) : LDAPEntry = {
    val e = dit.NODES.NETWORK.model(serverId,elt.name)
    e.setOpt(elt.description, A_DESCRIPTION, {x:String => x})
    //addresses
    if(elt.ifAddresses.isEmpty) {
      e -= A_NETIF_ADDRESS
    } else {
      e +=!(A_NETIF_ADDRESS,elt.ifAddresses.map(_.getHostAddress):_*)
    }
    e.setOpt(elt.ifDhcp,     A_NETIF_DHCP,     {x:InetAddress => x.getHostAddress})
    e.setOpt(elt.ifGateway,  A_NETIF_GATEWAY,  {x:InetAddress => x.getHostAddress})
    e.setOpt(elt.ifMask,     A_NETIF_MASK,     {x:InetAddress => x.getHostAddress})
    e.setOpt(elt.ifSubnet,   A_NETIF_SUBNET,   {x:InetAddress => x.getHostAddress})
    e.setOpt(elt.macAddress, A_NETIF_MAC,      {x:String => x})
    e.setOpt(elt.status,     A_STATUS,         {x:String => x})
    e.setOpt(elt.ifType,     A_NETIF_TYPE,     {x:String => x})
    e.setOpt(elt.speed,      A_SPEED,          {x:String => x})
    e.setOpt(elt.typeMib,    A_NETIF_TYPE_MIB, {x:String => x})
    e
  }
  
  def networkFromEntry(e:LDAPEntry) : Box[Network] = {
    for {
      name <- e(A_NETWORK_NAME) ?~! "Missing required attribute %s in entry: %s".format(A_NETWORK_NAME, e)
      desc = e(A_DESCRIPTION)
      ifAddresses = for { 
        i <- e.valuesFor(A_NETIF_ADDRESS).toSeq
        a <- getAddressByName(i)
      } yield a
      ifDhcp     = e(A_NETIF_DHCP).flatMap(getAddressByName(_))
      ifGateway  = e(A_NETIF_GATEWAY).flatMap(getAddressByName(_))
      ifMask     = e(A_NETIF_MASK).flatMap(getAddressByName(_))
      ifSubnet   = e(A_NETIF_SUBNET).flatMap(getAddressByName(_))
      macAddress = e(A_NETIF_MAC)
      status     = e(A_STATUS)
      ifType     = e(A_NETIF_TYPE)
      speed      = e(A_SPEED)
      typeMib    = e(A_NETIF_TYPE_MIB)
    } yield {
      Network( name, desc, ifAddresses, ifDhcp, ifGateway
          , ifMask, ifSubnet, macAddress, status, ifType, speed, typeMib )
    }
  }

    ///////////////////////// VM INFO /////////////////////////


  def entryFromVMInfo(elt:VirtualMachine, dit:InventoryDit, serverId:NodeId) : LDAPEntry = {
    val e = dit.NODES.VM.model(serverId,elt.uuid.value)
    e.setOpt(elt.description, A_DESCRIPTION,  {x:String => x})
    e.setOpt(elt.memory,      A_VM_MEMORY,    {x:String => x})
    e.setOpt(elt.name,        A_VM_NAME,      {x:String => x})
    e.setOpt(elt.owner,       A_VM_OWNER,     {x:String => x})
    e.setOpt(elt.status,      A_VM_STATUS,    {x:String => x})
    e.setOpt(elt.subsystem,   A_VM_SUBSYSTEM, {x:String => x})
    e.setOpt(elt.vcpu,        A_VM_CPU,       {x:Int => x.toString()})
    e.setOpt(elt.vmtype,      A_VM_TYPE,      {x:String => x})
    e
  }

  def vmFromEntry(e:LDAPEntry) : Box[VirtualMachine] = {
    for {
      vmid <- e(A_VM_ID) ?~! "Missing required attribute %s in entry: %s".format(A_VM_ID, e)

      memory    = e(A_VM_MEMORY)
      name      = e(A_VM_NAME)
      owner     = e(A_VM_OWNER)
      status    = e(A_VM_STATUS)
      subsystem = e(A_VM_SUBSYSTEM)
      vcpu      = e.getAsInt(A_VM_CPU)
      vmtype    = e(A_VM_TYPE)

    } yield {
      VirtualMachine( vmtype,subsystem,owner,name,status,vcpu,memory,new MachineUuid(vmid) )
    }
  }

  
  //////////////////Node/ NodeInventory /////////////////////////
  
    
  
  // User defined properties : the regexp that the data should abide by
  // {KEY}VALUE
  private[this] val userDefinedPropertyRegex = """\{([^\}]+)\}(.+)""".r
  
  def treeFromNode(server:NodeInventory) : LDAPTree = {
    val dit = ditService.getDit(server.main.status)
    //the root entry of the tree: the machine inventory
    val root = server.main.osDetails match {
      case UnknownOS(osFullName, osVersion, osServicePack, kernelVersion) =>
        dit.NODES.NODE.genericModel(server.main.id)
      
      case Linux(os,osFullName,osVersion,osServicePack,kernelVersion) =>
        val linux = dit.NODES.NODE.linuxModel(server.main.id)
        os match {
          case Debian  => linux += (A_OS_NAME, A_OS_DEBIAN)
          case Ubuntu  => linux += (A_OS_NAME, A_OS_UBUNTU)
          case Redhat  => linux += (A_OS_NAME, A_OS_REDHAT)
          case Centos  => linux += (A_OS_NAME, A_OS_CENTOS)
          case Fedora  => linux += (A_OS_NAME, A_OS_FEDORA)
          case Suse    => linux += (A_OS_NAME, A_OS_SUZE)
          case Android => linux += (A_OS_NAME, A_OS_ANDROID)
          case _       => linux += (A_OS_NAME, A_OS_UNKNOWN_LINUX)
        }
        linux
        
      case Windows(os,osFullName,osVersion,osServicePack,kernelVersion,userDomain,registrationCompany,productKey,productId) =>
        val win = dit.NODES.NODE.windowsModel(server.main.id)
        os match {
          case WindowsXP => win += (A_OS_NAME, A_OS_WIN_XP)
          case WindowsVista => win += (A_OS_NAME, A_OS_WIN_VISTA)
          case WindowsSeven => win += (A_OS_NAME, A_OS_WIN_SEVEN)
          case Windows2000 => win += (A_OS_NAME, A_OS_WIN_2000)
          case Windows2003 => win += (A_OS_NAME, A_OS_WIN_2003)
          case Windows2008 => win += (A_OS_NAME, A_OS_WIN_2008)
          case Windows2008R2 => win += (A_OS_NAME, A_OS_WIN_2008_R2)
          case _ => win += (A_OS_NAME, A_OS_UNKNOWN_WINDOWS)
        }
        win.setOpt(userDomain, A_WIN_USER_DOMAIN, { x: String => x })
        win.setOpt(registrationCompany, A_WIN_COMPANY, { x: String => x })
        win.setOpt(productKey, A_WIN_KEY, { x: String => x })
        win.setOpt(productId, A_WIN_ID, { x: String => x })
        win
    }
    root +=! (A_OS_FULL_NAME, server.main.osDetails.fullName)
    root +=! (A_OS_VERSION, server.main.osDetails.version.value)
    root.setOpt(server.main.osDetails.servicePack, A_OS_SERVICE_PACK, { x:String => x })
    root +=! (A_OS_KERNEL_VERSION, server.main.osDetails.kernelVersion.value)
    root +=! (A_ROOT_USER, server.main.rootUser)
    root +=! (A_HOSTNAME, server.main.hostname)
    root +=! (A_POLICY_SERVER_UUID, server.main.policyServerId.value)
    root.setOpt(server.ram, A_OS_RAM, { m: MemorySize => m.size.toString })
    root.setOpt(server.swap, A_OS_SWAP, { m: MemorySize => m.size.toString })
    root.setOpt(server.archDescription, A_ARCH, { x: String => x })
    root.setOpt(server.lastLoggedUser, A_LAST_LOGGED_USER, { x: String => x })
    root.setOpt(server.lastLoggedUserTime, A_LAST_LOGGED_USER_TIME, { x: DateTime => GeneralizedTime(x).toString })
    root.setOpt(server.inventoryDate, A_INVENTORY_DATE, { x: DateTime => GeneralizedTime(x).toString })
    root.setOpt(server.receiveDate, A_RECEIVE_DATE, { x: DateTime => GeneralizedTime(x).toString })
    root +=! (A_AGENTS_NAME, server.agentNames.map(x => x.toString):_*)
    root +=! (A_PKEYS, server.publicKeys.map(x => x.key):_*)
    root +=! (A_SOFTWARE_DN, server.softwareIds.map(x => dit.SOFTWARE.SOFT.dn(x).toString):_*)
    root +=! (A_EV, server.environmentVariables.map(x => Serialization.write(x)):_*)
    root +=! (A_PROCESS, server.processes.map(x => Serialization.write(x)):_*)
    root +=! (A_LIST_OF_IP, server.serverIps:_*)
    //we don't know their dit...
    root +=! (A_CONTAINER_DN, server.machineId.map { case (id, status) =>
      ditService.getDit(status).MACHINES.MACHINE.dn(id).toString
    }.toSeq:_*)
    root +=! (A_ACCOUNT, server.accounts:_*)
    val tree = LDAPTree(root)
    //now, add machine elements as children
    server.networks.foreach { x => tree.addChild(entryFromNetwork(x, dit, server.main.id)) }
    server.fileSystems.foreach { x => tree.addChild(entryFromFileSystem(x, dit, server.main.id)) }
    server.vms.foreach { x => tree.addChild(entryFromVMInfo(x,dit,server.main.id)) }
    tree
  }

  /*
   * Utility method that do the lookup between an LDAPEntry
   * and the matching server elements. 
   * It adds it to a server, return the modified server.
   * If the mapping goes bad or the entry type is unknown, the
   * NodeInventory is returned as it was, and the error is logged
   */
  private[this] def mapAndAddNodeElement(entry:LDAPEntry, server:NodeInventory) : NodeInventory = {
    def log(e:EmptyBox, elt:String) : NodeInventory = {
      val error = e ?~! "Error when mapping LDAP entry to a %s. Entry details: %s".format(elt,entry)
      logger.error(error.messageChain)
      server
    }
    
    entry match {
      case e if(e.isA(OC_NET_IF)) => networkFromEntry(e) match {
        case e:EmptyBox => log(e, "network interface")
        case Full(x) => server.copy( networks = x +: server.networks)
      }
      case e if(e.isA(OC_FS)) => fileSystemFromEntry(e) match {
        case e:EmptyBox => log(e, "file system")
        case Full(x) => server.copy( fileSystems = x +: server.fileSystems)
      }
      case e if(e.isA(OC_VM_INFO)) => vmFromEntry(e) match {
        case e:EmptyBox => log(e, "virtual machine")
        case Full(x) => server.copy( vms = x +: server.vms)
      }
      case e => 
        logger.error("Unknow entry type for a server element, that entry will be ignored: %s".format(e))
        server
    }
    
  }

  def mapSeqStringToMachineIdAndStatus(set:Set[String]) : Seq[(MachineUuid,InventoryStatus)] = {
    set.toSeq.flatMap { x => 
      (for {
        dn <- try { Full(new DN(x)) } catch { case e:LDAPException => Failure("Can not parse DN %s".format(x), Full(e),Empty) }
        dit <- ditService.getDit(dn) ?~! "Can not find DIT from DN %s".format(x)
        uuid <- dit.MACHINES.MACHINE.idFromDN(dn) ?~! "Can not map DN to machine ID"
        st = ditService.getInventoryStatus(dit)
      } yield (uuid,st) ) match {
        case Full(st) => List(st)
        case e:EmptyBox => 
          logger.error("Error when processing machine DN %s".format(x), e)
          Nil
      }
    }
  }  
  
    def nodeFromEntry(entry:LDAPEntry) : Box[NodeInventory] = {
    def missingAttr(attr:String) : String = "Missing required attribute %s".format(attr)
    def requiredAttr(attr:String) = entry(attr) ?~! missingAttr(attr)

    for {
      dit <- ditService.getDit(entry.dn)
      //server.main info: id, status, rootUser, hostname, osDetails: all mandatories
      inventoryStatus = ditService.getInventoryStatus(dit)
      id <- dit.NODES.NODE.idFromDN(entry.dn) ?~! missingAttr("for server id")
      hostname <- requiredAttr(A_HOSTNAME)
      rootUser <- requiredAttr(A_ROOT_USER)
      policyServerId <- requiredAttr(A_POLICY_SERVER_UUID)
      osName <- requiredAttr(A_OS_NAME)
      osVersion <- requiredAttr(A_OS_VERSION).map(x => new Version(x))
      kernelVersion <- requiredAttr(A_OS_KERNEL_VERSION).map(x => new Version(x))
      osFullName = entry(A_OS_FULL_NAME).getOrElse("")
      osServicePack = entry(A_OS_SERVICE_PACK)
      agentNames <- sequence(entry.valuesFor(A_AGENTS_NAME).toSeq){ x =>
                      AgentType.fromValue(x) ?~! "Error when mapping value '%s' to an agent type. Allowed values are %s".
                          format(x, AgentType.allValues.mkString(", "))
                    }
      //now, look for the OS type
      osDetails <- {
        if(entry.isA(OC_WINDOWS_NODE)) {
          val os = osName match {
            case A_OS_WIN_XP => WindowsXP
            case A_OS_WIN_VISTA => WindowsVista
            case A_OS_WIN_SEVEN => WindowsSeven
            case A_OS_WIN_2000 => Windows2000
            case A_OS_WIN_2003 => Windows2003
            case A_OS_WIN_2008 => Windows2008
            case A_OS_WIN_2008_R2 => Windows2008R2
            case _ => UnknownWindowsType
          }
          val userDomain = entry(A_WIN_USER_DOMAIN)
          val registrationCompany = entry(A_WIN_COMPANY)
          val productKey = entry(A_WIN_KEY)
          val productId = entry(A_WIN_ID)
          Full(Windows(os,osFullName,osVersion,osServicePack,kernelVersion,userDomain,registrationCompany,productKey,productId))
          
        } else if(entry.isA(OC_LINUX_NODE)) {
          val os = osName match {
            case A_OS_DEBIAN  => Debian
            case A_OS_UBUNTU  => Ubuntu
            case A_OS_REDHAT  => Redhat
            case A_OS_CENTOS  => Centos
            case A_OS_FEDORA  => Fedora
            case A_OS_SUZE    => Suse
            case A_OS_ANDROID => Android
            case _            => UnknownLinuxType
          }
          Full(Linux(os,osFullName,osVersion,osServicePack,kernelVersion))
          
        } else if(entry.isA(OC_NODE)) {
          Full(UnknownOS(osFullName,osVersion,osServicePack,kernelVersion))
        } else Failure("Unknow OS type: %s".format(entry.valuesFor(A_OC).mkString(", ")))
      }
      //now, optionnal things
      name = entry(A_NAME)
      description = entry(A_DESCRIPTION)
      ram = entry(A_OS_RAM).map { x => MemorySize(x) }
      swap = entry(A_OS_SWAP).map { x => MemorySize(x) }
      arch = entry(A_ARCH)
      
      lastLoggedUser = entry(A_LAST_LOGGED_USER)
      lastLoggedUserTime = entry.getAsGTime(A_LAST_LOGGED_USER_TIME).map { _.dateTime }
      publicKeys = entry.valuesFor(A_PKEYS).map(k => PublicKey(k))
      ev = entry.valuesFor(A_EV).toSeq.map{Serialization.read[EnvironmentVariable](_)}
      process = entry.valuesFor(A_PROCESS).toSeq.map(Serialization.read[Process](_))
      softwareIds = entry.valuesFor(A_SOFTWARE_DN).toSeq.flatMap(x => dit.SOFTWARE.SOFT.idFromDN(new DN(x)))
      machineId = mapSeqStringToMachineIdAndStatus(entry.valuesFor(A_CONTAINER_DN)).toList match {
        case Nil => None
        case m :: Nil => Some(m)
        case l@( m1 :: m2 :: _) =>
          logger.error("Several machine were registered for a node. That is not supported. " +
              "The first in the following list will be choosen, but you may encouter strange " +
              "results in the future: %s".
                format(l.map{ case (id,status) => "%s [%s]".format(id.value, status.name)}.mkString(" ; "))
              )
          Some(m1)
      }
      inventoryDate = entry.getAsGTime(A_INVENTORY_DATE).map { _.dateTime }
      receiveDate = entry.getAsGTime(A_RECEIVE_DATE).map { _.dateTime }
      accounts = entry.valuesFor(A_ACCOUNT).toSeq
      serverIps = entry.valuesFor(A_LIST_OF_IP).toSeq
      main = NodeSummary(id,inventoryStatus,rootUser,hostname, osDetails, NodeId(policyServerId))
    } yield {
      NodeInventory(
           main
         , name
         , description
         , ram
         , swap
         , inventoryDate
         , receiveDate
         , arch
         , lastLoggedUser
         , lastLoggedUserTime
         , agentNames
         , publicKeys.toSeq
         , serverIps
         , machineId
         , softwareIds
         , accounts
         , ev
         , process
         )
    }
  }

  def nodeFromTree(tree:LDAPTree) : Box[NodeInventory] = {
    for {
      node <- nodeFromEntry(tree.root)
    } yield {
      //map subentries and return result
      (node /: tree.children()) { case (m,(rdn,t)) => mapAndAddNodeElement(t.root(),m) }
    }
  }
  
}
