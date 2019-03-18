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

import LDAPConstants._
import com.normation.inventory.domain._
import com.unboundid.ldap.sdk.{Version => _, _}
import com.normation.ldap.sdk._
import com.normation.ldap.sdk.schema.LDAPObjectClass
import org.joda.time.DateTime
import net.liftweb.json._
import java.net.InetAddress

import InetAddressUtils._
import com.normation.errors.BaseChainError
import com.normation.errors.RudderError
import com.normation.inventory.domain.NodeTimezone
import com.normation.inventory.ldap.core.InventoryMappingRudderError._
import com.normation.inventory.ldap.core.InventoryMappingResult._
import scalaz.zio._
import scalaz.zio.syntax._
import com.softwaremill.quicklens._

sealed trait InventoryMappingRudderError extends RudderError
object InventoryMappingRudderError {
  final case class MissingMandatoryAttribute(attribute: String, entry: LDAPEntry) extends InventoryMappingRudderError {
    def msg = s"Missing required attribute '${attribute}' in entry: ${entry}"
  }
  final case class MalformedDN(msg: String)      extends InventoryMappingRudderError
  final case class MissingMandatory(msg: String) extends InventoryMappingRudderError
  final case class UnknownElement(msg: String)   extends InventoryMappingRudderError
  final case class Chained[E <: RudderError](hint: String, cause: E) extends InventoryMappingRudderError with BaseChainError[E]
}

object InventoryMappingResult {

  type InventoryMappingPure[T] = Either[InventoryMappingRudderError, T]
  type InventoryMappingResult[T] = IO[RudderError, T]


  implicit class RequiredAttrToPure(entry: LDAPEntry) {
    def required(attribute: String): InventoryMappingPure[String] = entry(attribute) match {
      case None    => Left(MissingMandatoryAttribute(attribute, entry))
      case Some(x) => Right(x)
    }
  }

  implicit class RequiredThing[T](opt: Option[T]) {
    def notOptional(msg: String) = opt match {
      case None    => Left(MissingMandatory(msg))
      case Some(x) => Right(x)
    }
  }

  implicit class ToZio[T](res: InventoryMappingPure[T]) {
    def zio = ZIO.fromEither(res)
  }
}

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
    ditService :InventoryDitService
  , pendingDit :InventoryDit
  , acceptedDit:InventoryDit
  , removedDit :InventoryDit
) {

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

  def softwareFromEntry(e:LDAPEntry) : InventoryMappingPure[Software] = {
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

  def biosFromEntry(e:LDAPEntry) : InventoryMappingPure[Bios] = {
    for {
      name        <- e.required(A_BIOS_NAME)
      desc        =  e(A_DESCRIPTION)
      quantity    =  e.getAsInt(A_QUANTITY).getOrElse(1)
      version     =  e(A_SOFT_VERSION).map(v => new Version(v))
      releaseDate =  e.getAsGTime(A_RELEASE_DATE) map { _.dateTime }
      editor      =  e(A_EDITOR) map { x => new SoftwareEditor(x) }

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

  def controllerFromEntry(e:LDAPEntry) : InventoryMappingPure[Controller] = {
    for {
      name         <- e.required(A_CONTROLLER_NAME)
      desc         =  e(A_DESCRIPTION)
      manufacturer =  e(A_MANUFACTURER).map(m => new Manufacturer(m))
      smeType      =  e(A_SME_TYPE)
      quantity     =  e.getAsInt(A_QUANTITY).getOrElse(1)
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

  def memorySlotFromEntry(e:LDAPEntry) : InventoryMappingPure[MemorySlot] = {
    for {
      slotNumber <- e.required(A_MEMORY_SLOT_NUMBER)
      name       =  e(A_NAME)
      desc       =  e(A_DESCRIPTION)
      quantity   =  e.getAsInt(A_QUANTITY).getOrElse(1)
      capacity   =  e(A_MEMORY_CAPACITY).map{x => MemorySize(x)}
      caption    =  e(A_MEMORY_CAPTION)
      speed      =  e(A_MEMORY_SPEED)
      memType    =  e(A_MEMORY_TYPE)
      serial     =  e(A_SERIAL_NUMBER)
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

  def portFromEntry(e:LDAPEntry) : InventoryMappingPure[Port] = {
    for {
      name     <- e.required(A_PORT_NAME)
      desc     =  e(A_DESCRIPTION)
      smeType  =  e(A_SME_TYPE)
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

  def processorFromEntry(e:LDAPEntry) : InventoryMappingPure[Processor] = {
    for {
      name          <- e.required(A_PROCESSOR_NAME)
      desc          =  e(A_DESCRIPTION)
      quantity      =  e.getAsInt(A_QUANTITY).getOrElse(1)
      speed         =  e.getAsInt(A_PROCESSOR_SPEED)
      stepping      =  e.getAsInt(A_PROCESSOR_STEPPING)
      family        =  e.getAsInt(A_PROCESSOR_FAMILLY)
      model         =  e.getAsInt(A_MODEL)
      manufacturer  =  e(A_MANUFACTURER).map(m => new Manufacturer(m))
      core          =  e.getAsInt(A_CORE)
      thread        =  e.getAsInt(A_THREAD)
      familyName    =  e(A_PROCESSOR_FAMILY_NAME)
      arch          =  e(A_PROCESSOR_ARCHITECTURE)
      externalClock =  e.getAsFloat(A_EXTERNAL_CLOCK)
      cpuid         =  e(A_CPUID)
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

  def slotFromEntry(e:LDAPEntry) : InventoryMappingPure[Slot] = {
    for {
      name     <- e.required(A_SLOT_NAME)
      desc     =  e(A_DESCRIPTION)
      status   =  e(A_STATUS)
      quantity =  e.getAsInt(A_QUANTITY).getOrElse(1)
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

  def soundFromEntry(e:LDAPEntry) : InventoryMappingPure[Sound] = {
    for {
      name     <- e.required(A_SOUND_NAME)
      desc     =  e(A_DESCRIPTION)
      quantity =  e.getAsInt(A_QUANTITY).getOrElse(1)
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

  def storageFromEntry(e:LDAPEntry) : InventoryMappingPure[Storage] = {
    for {
      name         <- e.required(A_STORAGE_NAME)
      desc         =  e(A_DESCRIPTION)
      size         =  e(A_STORAGE_SIZE).map{x => MemorySize(x)}
      firmware     =  e(A_STORAGE_FIRMWARE)
      manufacturer =  e(A_MANUFACTURER).map( m => new Manufacturer(m))
      model        =  e(A_MODEL)
      serialNumber =  e(A_SERIAL_NUMBER)
      smeType      =  e(A_SME_TYPE)
      quantity     =  e.getAsInt(A_QUANTITY).getOrElse(1)
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

  def videoFromEntry(e:LDAPEntry) : InventoryMappingPure[Video] = {
    for {
      name       <- e.required(A_VIDEO_NAME)
      desc       =  e(A_DESCRIPTION)
      quantity   =  e.getAsInt(A_QUANTITY).getOrElse(1)
      chipset    =  e(A_VIDEO_CHIPSET)
      memory     =  e(A_MEMORY_CAPACITY).map{x => MemorySize(x)}
      resolution =  e (A_VIDEO_RESOLUTION)
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
      case VirtualMachineType(AixLPAR) => OC(OC_VM_AIX_LPAR)
      case VirtualMachineType(HyperV) => OC(OC_VM_HYPERV)
      case VirtualMachineType(BSDJail) => OC(OC_VM_BSDJAIL)
      case PhysicalMachineType => OC(OC_PM)
    }
  }

  def machineTypeFromObjectClasses(objectClassNames:Set[String]) = {
    def objectClass2MachineType(oc : LDAPObjectClass) : Option[MachineType] = {
      oc match {
        case LDAPObjectClass(OC_VM,_,_,_)              => Some(VirtualMachineType(UnknownVmType))
        case LDAPObjectClass(OC_VM_VIRTUALBOX,_,_,_)   => Some(VirtualMachineType(VirtualBox))
        case LDAPObjectClass(OC_VM_XEN,_,_,_)          => Some(VirtualMachineType(Xen))
        case LDAPObjectClass(OC_VM_VMWARE,_,_,_)       => Some(VirtualMachineType(VMWare))
        case LDAPObjectClass(OC_VM_SOLARIS_ZONE,_,_,_) => Some(VirtualMachineType(SolarisZone))
        case LDAPObjectClass(OC_VM_QEMU,_,_,_)         => Some(VirtualMachineType(QEmu))
        case LDAPObjectClass(OC_VM_AIX_LPAR,_,_,_)     => Some(VirtualMachineType(AixLPAR))
        case LDAPObjectClass(OC_VM_HYPERV,_,_,_)       => Some(VirtualMachineType(HyperV))
        case LDAPObjectClass(OC_VM_BSDJAIL,_,_,_)      => Some(VirtualMachineType(BSDJail))
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
    root.setOpt(machine.manufacturer,A_MANUFACTURER, {x:Manufacturer => x.name})
    root.setOpt(machine.systemSerialNumber,A_SERIAL_NUMBER, {x:String => x})

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
   */
  private[this] def mapAndAddElementGeneric[U, T](from: U, e: LDAPEntry, name: String, f: LDAPEntry => InventoryMappingPure[T], path: U => PathModify[U, Seq[T]]): UIO[U] = {
    f(e) match {
      case Left(error) =>
        InventoryLogger.error(s"Error when mapping LDAP entry to a '${name}'. Entry details: ${e}. Cause: ${e.getClass.getSimpleName}:${error.msg}") *>
        from.succeed
      case Right(value) =>
        path(from).using( value +: _ ).succeed
    }
  }

  private[this] def mapAndAddMachineElement(entry:LDAPEntry, machine:MachineInventory) : UIO[MachineInventory] = {
    def mapAndAdd[T](name: String, f: LDAPEntry => InventoryMappingPure[T], path: MachineInventory => PathModify[MachineInventory, Seq[T]]): UIO[MachineInventory] = mapAndAddElementGeneric[MachineInventory, T](machine, entry, name, f, path)

    import com.softwaremill.quicklens._

    entry match {
      case e if(e.isA(OC_MEMORY))     => mapAndAdd("memory slot", memorySlotFromEntry, _.modify(_.memories)   )
      case e if(e.isA(OC_PORT))       => mapAndAdd("port"       , portFromEntry      , _.modify(_.ports)      )
      case e if(e.isA(OC_SLOT))       => mapAndAdd("slot"       ,  slotFromEntry     , _.modify(_.slots)      )
      case e if(e.isA(OC_SOUND))      => mapAndAdd("sound card" , soundFromEntry     , _.modify(_.sounds)     )
      case e if(e.isA(OC_BIOS))       => mapAndAdd("bios"       , biosFromEntry      , _.modify(_.bios)       )
      case e if(e.isA(OC_CONTROLLER)) => mapAndAdd("controller" , controllerFromEntry, _.modify(_.controllers))
      case e if(e.isA(OC_PROCESSOR))  => mapAndAdd("processor"  , processorFromEntry , _.modify(_.processors) )
      case e if(e.isA(OC_STORAGE))    => mapAndAdd("storage"    , storageFromEntry   , _.modify(_.storages)   )
      case e if(e.isA(OC_VIDEO))      => mapAndAdd("video"      , videoFromEntry     , _.modify(_.videos)     )
      case e                          =>
        InventoryLogger.error(s"Unknown entry type for a machine element, that entry will be ignored: ${e}") *> machine.succeed
    }
  }

  def machineFromTree(tree:LDAPTree) : InventoryMappingResult[MachineInventory] = {
    for {
      dit                <- ZIO.fromEither(ditService.getDit(tree.root().dn).notOptional(s"Impossible to find DIT for entry '${tree.root().dn.toString()}'"))
      inventoryStatus    =  ditService.getInventoryStatus(dit)
      id                 <- ZIO.fromEither(dit.MACHINES.MACHINE.idFromDN(tree.root.dn))
      machineType        <- ZIO.fromEither(machineTypeFromObjectClasses(tree.root().valuesFor(A_OC).toSet).notOptional("Can not find machine types"))
      name               =  tree.root()(A_NAME)
      mbUuid             =  tree.root()(A_MB_UUID) map { x => MotherBoardUuid(x) }
      inventoryDate      =  tree.root.getAsGTime(A_INVENTORY_DATE).map { _.dateTime }
      receiveDate        =  tree.root.getAsGTime(A_RECEIVE_DATE).map { _.dateTime }
      manufacturer       =  tree.root()(A_MANUFACTURER).map(m => new Manufacturer(m))
      systemSerialNumber =  tree.root()(A_SERIAL_NUMBER)
      //now, get all subentries
      m                  = MachineInventory(id,inventoryStatus,machineType,name,mbUuid,inventoryDate
                            , receiveDate, manufacturer, systemSerialNumber)
      //map subentries and return result
      res                <- ZIO.foldLeft(tree.children)(m) { case (m, (rdn,t)) => mapAndAddMachineElement(t.root(), m) }
    } yield {
      res
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

  def fileSystemFromEntry(e:LDAPEntry) : InventoryMappingPure[FileSystem] = {
    for {
      mountPoint <- e.required(A_MOUNT_POINT)
      name       =  e(A_NAME)
      desc       =  e(A_DESCRIPTION)
      fileCount  =  e.getAsInt(A_FILE_COUNT)
      freeSpace  =  e.getAsLong(A_FREE_SPACE).map(new MemorySize(_))
      totalSpace =  e.getAsLong(A_TOTAL_SPACE).map(new MemorySize(_))
    } yield {
      FileSystem(mountPoint, name, desc, fileCount, freeSpace, totalSpace)
    }
  }

  ///////////////////////// Networks /////////////////////////

  def entryFromNetwork(elt:Network, dit:InventoryDit, serverId:NodeId) : LDAPEntry = {
    // mutable, yep
    def setSeqAddress(e: LDAPEntry, attr: String, list: Seq[InetAddress]): Unit = {
      if(list.isEmpty) {
        e -= attr
      } else {
        e +=!(attr, list.map(_.getHostAddress):_*)
      }
    }

    val e = dit.NODES.NETWORK.model(serverId,elt.name)
    e.setOpt(elt.description, A_DESCRIPTION, {x:String => x})
    //addresses
    setSeqAddress(e, A_NETIF_ADDRESS, elt.ifAddresses)
    setSeqAddress(e, A_NETIF_GATEWAY, elt.ifGateway)
    setSeqAddress(e, A_NETIF_MASK   , elt.ifMask)
    setSeqAddress(e, A_NETIF_SUBNET , elt.ifSubnet)
    e.setOpt(elt.ifDhcp,     A_NETIF_DHCP,     {x:InetAddress => x.getHostAddress})
    e.setOpt(elt.macAddress, A_NETIF_MAC,      {x:String => x})
    e.setOpt(elt.status,     A_STATUS,         {x:String => x})
    e.setOpt(elt.ifType,     A_NETIF_TYPE,     {x:String => x})
    e.setOpt(elt.speed,      A_SPEED,          {x:String => x})
    e.setOpt(elt.typeMib,    A_NETIF_TYPE_MIB, {x:String => x})
    e
  }

  def networkFromEntry(e:LDAPEntry) : InventoryMappingPure[Network] = {
    for {
      name       <- e.required(A_NETWORK_NAME)
      desc       =  e(A_DESCRIPTION)
      ifAddresses=  e.valuesFor(A_NETIF_ADDRESS).toSeq.flatMap(getAddressByName)
      ifGateway  =  e.valuesFor(A_NETIF_GATEWAY).toSeq.flatMap(getAddressByName)
      ifMask     =  e.valuesFor(A_NETIF_MASK).toSeq.flatMap(getAddressByName)
      ifSubnet   =  e.valuesFor(A_NETIF_SUBNET).toSeq.flatMap(getAddressByName)
      ifDhcp     =  e(A_NETIF_DHCP).flatMap(getAddressByName(_))
      macAddress =  e(A_NETIF_MAC)
      status     =  e(A_STATUS)
      ifType     =  e(A_NETIF_TYPE)
      speed      =  e(A_SPEED)
      typeMib    =  e(A_NETIF_TYPE_MIB)
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

  def vmFromEntry(e:LDAPEntry) : InventoryMappingPure[VirtualMachine] = {
    for {
      vmid      <- e.required(A_VM_ID)
      memory    =  e(A_VM_MEMORY)
      name      =  e(A_VM_NAME)
      owner     =  e(A_VM_OWNER)
      status    =  e(A_VM_STATUS)
      subsystem =  e(A_VM_SUBSYSTEM)
      vcpu      =  e.getAsInt(A_VM_CPU)
      vmtype    =  e(A_VM_TYPE)

    } yield {
      VirtualMachine( vmtype,subsystem,owner,name,status,vcpu,memory,new MachineUuid(vmid) )
    }
  }

  ////////////////// Node Custom Properties /////////////////////////
  final object CustomPropertiesSerialization {

    import net.liftweb.json._

    /*
     * CustomProperty serialization must follow NodeProperties one:
     * {"name":"propkey","value": JVALUE}
     * with JVALUE either a simple type (string, int, etc) or a valid JSON
     */
    implicit class Serialise(cs: CustomProperty) {
      def toJson: String = {
        Serialization.write(cs)(DefaultFormats)
      }
    }

    implicit class Unserialize(json: String) {
      def toCustomProperty: Either[Throwable, CustomProperty] = {
        implicit val formats = DefaultFormats
        try {
          Right(Serialization.read[CustomProperty](json))
        } catch {
          case ex: Exception => Left(ex)
        }
      }
    }
  }

  //////////////////Node/ NodeInventory /////////////////////////

  def treeFromNode(server:NodeInventory) : LDAPTree = {
    import com.normation.inventory.domain.AgentInfoSerialisation._

    val dit = ditService.getDit(server.main.status)
    //the root entry of the tree: the machine inventory
    val root = server.main.osDetails match {
      case UnknownOS(osFullName, osVersion, osServicePack, kernelVersion) =>
        dit.NODES.NODE.genericModel(server.main.id)

      case Linux(os,osFullName,osVersion,osServicePack,kernelVersion) =>
        val linux = dit.NODES.NODE.linuxModel(server.main.id)
        os match {
          case Debian     => linux += (A_OS_NAME, A_OS_DEBIAN)
          case Ubuntu     => linux += (A_OS_NAME, A_OS_UBUNTU)
          case Redhat     => linux += (A_OS_NAME, A_OS_REDHAT)
          case Centos     => linux += (A_OS_NAME, A_OS_CENTOS)
          case Fedora     => linux += (A_OS_NAME, A_OS_FEDORA)
          case Suse       => linux += (A_OS_NAME, A_OS_SUZE)
          case Android    => linux += (A_OS_NAME, A_OS_ANDROID)
          case Oracle     => linux += (A_OS_NAME, A_OS_ORACLE)
          case Scientific => linux += (A_OS_NAME, A_OS_SCIENTIFIC)
          case Slackware  => linux += (A_OS_NAME, A_OS_SLACKWARE)
          case Mint       => linux += (A_OS_NAME, A_OS_MINT)
          case _          => linux += (A_OS_NAME, A_OS_UNKNOWN_LINUX)
        }
        linux

      case Solaris(_,_,_,_) =>
        val solaris = dit.NODES.NODE.solarisModel(server.main.id)
        solaris += (A_OS_NAME, A_OS_SOLARIS)
        solaris

      case Aix(_,_,_,_) =>
        val aix = dit.NODES.NODE.aixModel(server.main.id)
        aix += (A_OS_NAME, A_OS_AIX)
        aix

      case Bsd(os,_,_,_,_) =>
        val bsd = dit.NODES.NODE.bsdModel(server.main.id)
        os match {
          case FreeBSD => bsd += (A_OS_NAME, A_OS_FREEBSD)
          case _       => bsd += (A_OS_NAME, A_OS_UNKNOWN_BSD)
        }
        bsd

      case Windows(os,osFullName,osVersion,osServicePack,kernelVersion,userDomain,registrationCompany,productKey,productId) =>
        val win = dit.NODES.NODE.windowsModel(server.main.id)
        os match {
          case WindowsXP     => win += (A_OS_NAME, A_OS_WIN_XP)
          case WindowsVista  => win += (A_OS_NAME, A_OS_WIN_VISTA)
          case WindowsSeven  => win += (A_OS_NAME, A_OS_WIN_SEVEN)
          case Windows10     => win += (A_OS_NAME, A_OS_WIN_10)
          case Windows2000   => win += (A_OS_NAME, A_OS_WIN_2000)
          case Windows2003   => win += (A_OS_NAME, A_OS_WIN_2003)
          case Windows2008   => win += (A_OS_NAME, A_OS_WIN_2008)
          case Windows2008R2 => win += (A_OS_NAME, A_OS_WIN_2008_R2)
          case Windows2012   => win += (A_OS_NAME, A_OS_WIN_2012)
          case Windows2012R2 => win += (A_OS_NAME, A_OS_WIN_2012_R2)
          case Windows2016   => win += (A_OS_NAME, A_OS_WIN_2016)
          case Windows2016R2 => win += (A_OS_NAME, A_OS_WIN_2016_R2)
          case Windows2019   => win += (A_OS_NAME, A_OS_WIN_2019)
          case _ => win += (A_OS_NAME, A_OS_UNKNOWN_WINDOWS)
        }
        win.setOpt(userDomain, A_WIN_USER_DOMAIN, { x: String => x })
        win.setOpt(registrationCompany, A_WIN_COMPANY, { x: String => x })
        win.setOpt(productKey, A_WIN_KEY, { x: String => x })
        win.setOpt(productId, A_WIN_ID, { x: String => x })
        win
    }
    root +=! (A_OS_FULL_NAME      , server.main.osDetails.fullName)
    root +=! (A_OS_VERSION        , server.main.osDetails.version.value)
    root.setOpt(server.main.osDetails.servicePack, A_OS_SERVICE_PACK, { x:String => x })
    root +=! (A_OS_KERNEL_VERSION , server.main.osDetails.kernelVersion.value)
    root +=! (A_ROOT_USER         , server.main.rootUser)
    root +=! (A_HOSTNAME          , server.main.hostname)
    root +=! (A_KEY_STATUS        , server.main.keyStatus.value)
    root +=! (A_POLICY_SERVER_UUID, server.main.policyServerId.value)
    root.setOpt(server.ram               , A_OS_RAM, { m: MemorySize => m.size.toString })
    root.setOpt(server.swap              , A_OS_SWAP, { m: MemorySize => m.size.toString })
    root.setOpt(server.archDescription   , A_ARCH, { x: String => x })
    root.setOpt(server.lastLoggedUser    , A_LAST_LOGGED_USER, { x: String => x })
    root.setOpt(server.lastLoggedUserTime, A_LAST_LOGGED_USER_TIME, { x: DateTime => GeneralizedTime(x).toString })
    root.setOpt(server.inventoryDate     , A_INVENTORY_DATE, { x: DateTime => GeneralizedTime(x).toString })
    root.setOpt(server.receiveDate       , A_RECEIVE_DATE, { x: DateTime => GeneralizedTime(x).toString })
    root +=! (A_AGENTS_NAME       , server.agents.map(x => x.toJsonString):_*)
    root +=! (A_SOFTWARE_DN       , server.softwareIds.map(x => dit.SOFTWARE.SOFT.dn(x).toString):_*)
    root +=! (A_EV                , server.environmentVariables.map(x => Serialization.write(x)):_*)
    root +=! (A_PROCESS           , server.processes.map(x => Serialization.write(x)):_*)
    root +=! (A_LIST_OF_IP        , server.serverIps.distinct:_*)
    //we don't know their dit...
    root +=! (A_CONTAINER_DN      , server.machineId.map { case (id, status) =>
      ditService.getDit(status).MACHINES.MACHINE.dn(id).toString
    }.toSeq:_*)
    root +=! (A_ACCOUNT           , server.accounts:_*)
    root +=! (A_SERVER_ROLE       , server.serverRoles.toSeq.map(_.value):_*)
    server.timezone.foreach { timezone =>
      root +=! (A_TIMEZONE_NAME   , timezone.name)
      root +=! (A_TIMEZONE_OFFSET , timezone.offset)
    }
    server.customProperties.foreach { cp =>
      import CustomPropertiesSerialization.Serialise
      root += (A_CUSTOM_PROPERTY, cp.toJson)
    }

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
  private[this] def mapAndAddNodeElement(entry:LDAPEntry, node: NodeInventory) : UIO[NodeInventory] = {
    def mapAndAdd[T](name: String, f: LDAPEntry => InventoryMappingPure[T], path: NodeInventory => PathModify[NodeInventory, Seq[T]]): UIO[NodeInventory] = mapAndAddElementGeneric[NodeInventory, T](node, entry, name, f, path)

    entry match {
      case e if(e.isA(OC_NET_IF))  => mapAndAdd("network interface", networkFromEntry   , _.modify(_.networks))
      case e if(e.isA(OC_FS))      => mapAndAdd("file system"      , fileSystemFromEntry, _.modify(_.fileSystems))
      case e if(e.isA(OC_VM_INFO)) => mapAndAdd("virtual machine"  , vmFromEntry        , _.modify(_.vms))
      case e =>
        InventoryLogger.error(s"Unknow entry type for a server element, that entry will be ignored: ${e}") *>
        node.succeed
    }

  }

  def mapSeqStringToMachineIdAndStatus(set:Set[String]) : Seq[(MachineUuid,InventoryStatus)] = {
    set.toSeq.flatMap { x =>
      (for {
        dn   <- try { Right(new DN(x)) } catch { case e:LDAPException => Left(MalformedDN(s"Can not parse DN: '${x}'. Exception was: ${e.getMessage}")) }
        dit  <- ditService.getDit(dn).notOptional(s"Can not find DIT from DN '${x}'")
        uuid <- dit.MACHINES.MACHINE.idFromDN(dn)
        st   =  ditService.getInventoryStatus(dit)
      } yield (uuid, st) ) match {
        case Right(st) => List(st)
        case Left(err) =>
          InventoryLogger.internalLogger.error(s"Error when processing machine DN '${x}': ${err.msg}")
          Nil
      }
    }
  }

  def mapOsDetailsFromEntry(entry: LDAPEntry) : InventoryMappingPure[OsDetails] = {
    for {
      osName        <- entry.required(A_OS_NAME)
      osVersion     <- entry.required(A_OS_VERSION).map(x => new Version(x))
      kernelVersion <- entry.required(A_OS_KERNEL_VERSION).map(x => new Version(x))
      osFullName    =  entry(A_OS_FULL_NAME).getOrElse("")
      osServicePack =  entry(A_OS_SERVICE_PACK)
      osDetails     <- if(entry.isA(OC_WINDOWS_NODE)) {
                          val os = osName match {
                            case A_OS_WIN_XP      => WindowsXP
                            case A_OS_WIN_VISTA   => WindowsVista
                            case A_OS_WIN_SEVEN   => WindowsSeven
                            case A_OS_WIN_10      => Windows10
                            case A_OS_WIN_2000    => Windows2000
                            case A_OS_WIN_2003    => Windows2003
                            case A_OS_WIN_2008    => Windows2008
                            case A_OS_WIN_2008_R2 => Windows2008R2
                            case A_OS_WIN_2012    => Windows2012
                            case A_OS_WIN_2012_R2 => Windows2012R2
                            case A_OS_WIN_2016    => Windows2016
                            case A_OS_WIN_2016_R2 => Windows2016R2
                            case A_OS_WIN_2019    => Windows2019
                            case _                => UnknownWindowsType
                          }
                          val userDomain          = entry(A_WIN_USER_DOMAIN)
                          val registrationCompany = entry(A_WIN_COMPANY)
                          val productKey          = entry(A_WIN_KEY)
                          val productId           = entry(A_WIN_ID)
                          Right(Windows(os,osFullName,osVersion,osServicePack,kernelVersion,userDomain,registrationCompany,productKey,productId))

                        } else if(entry.isA(OC_LINUX_NODE)) {
                          val os = osName match {
                            case A_OS_DEBIAN     => Debian
                            case A_OS_UBUNTU     => Ubuntu
                            case A_OS_REDHAT     => Redhat
                            case A_OS_CENTOS     => Centos
                            case A_OS_FEDORA     => Fedora
                            case A_OS_SUZE       => Suse
                            case A_OS_ORACLE     => Oracle
                            case A_OS_SCIENTIFIC => Scientific
                            case A_OS_ANDROID    => Android
                            case A_OS_SLACKWARE  => Slackware
                            case A_OS_MINT       => Mint
                            case _               => UnknownLinuxType
                          }
                          Right(Linux(os,osFullName,osVersion,osServicePack,kernelVersion))

                        } else if(entry.isA(OC_SOLARIS_NODE)) {
                          Right(Solaris(osFullName,osVersion,osServicePack,kernelVersion))
                        } else if(entry.isA(OC_AIX_NODE)) {
                          Right(Aix(osFullName,osVersion,osServicePack,kernelVersion))
                        } else if(entry.isA(OC_BSD_NODE)) {
                          val os = osName match {
                            case A_OS_FREEBSD => FreeBSD
                            case _            => UnknownBsdType
                          }
                          Right(Bsd(os,osFullName,osVersion,osServicePack,kernelVersion))
                        } else if(entry.isA(OC_NODE)) {
                          Right(UnknownOS(osFullName,osVersion,osServicePack,kernelVersion))
                        } else Left(UnknownElement(s"Unknow OS type: '${entry.valuesFor(A_OC).mkString(", ")}'"))
    } yield {
      osDetails
    }
  }

  def nodeFromEntry(entry:LDAPEntry) : InventoryMappingResult[NodeInventory] = {
    for {
      dit                <- ditService.getDit(entry.dn).notOptional(s"DIT not found for entry ${entry.dn}").zio
      inventoryStatus    =  ditService.getInventoryStatus(dit)
      id                 <- dit.NODES.NODE.idFromDN(entry.dn).zio
      keyStatus          <- ZIO.fromEither(entry(A_KEY_STATUS).map(KeyStatus(_)).getOrElse(Right(UndefinedKey)))
      hostname           <- entry.required(A_HOSTNAME).zio
      rootUser           <- entry.required(A_ROOT_USER).zio
      policyServerId     <- entry.required(A_POLICY_SERVER_UUID).zio
      publicKeys         =  entry.valuesFor(A_PKEYS).map(Some(_))
      agentNames         <- {
                              val agents = entry.valuesFor(A_AGENTS_NAME).toSeq.map(Some(_))
                              val agentWithKeys = agents.zipAll(publicKeys, None,None).filter(_._1.isDefined)
                              ZIO.foreach(agentWithKeys) {
                                case (Some(agent), key) =>
                                  AgentInfoSerialisation.parseCompatNonJson(agent,key).mapError(err =>
                                    InventoryMappingRudderError.Chained(s"Error when parsing agent security token '${agent}'", err)
                                  )
                                case (None, key)        =>
                                  InventoryMappingRudderError.MissingMandatory("Error when parsing agent security token: agent is undefined").fail
                              }
                            }
      //now, look for the OS type
      osDetails          <- mapOsDetailsFromEntry(entry).zio
      // optional information
      name               =  entry(A_NAME)
      description        =  entry(A_DESCRIPTION)
      ram                =  entry(A_OS_RAM).map  { x => MemorySize(x) }
      swap               =  entry(A_OS_SWAP).map { x => MemorySize(x) }
      arch               =  entry(A_ARCH)

      lastLoggedUser     =  entry(A_LAST_LOGGED_USER)
      lastLoggedUserTime =  entry.getAsGTime(A_LAST_LOGGED_USER_TIME).map { _.dateTime }
      publicKeys         =  entry.valuesFor(A_PKEYS).map(k => PublicKey(k))
      ev                 =  entry.valuesFor(A_EV).toSeq.map{Serialization.read[EnvironmentVariable](_)}
      process            =  entry.valuesFor(A_PROCESS).toSeq.map(Serialization.read[Process](_))
      softwareIds        =  entry.valuesFor(A_SOFTWARE_DN).toSeq.flatMap(x => dit.SOFTWARE.SOFT.idFromDN(new DN(x)).toOption)
      machineId          <- mapSeqStringToMachineIdAndStatus(entry.valuesFor(A_CONTAINER_DN)).toList match {
                              case Nil => None.succeed
                              case m :: Nil => Some(m).succeed
                              case l@( m1 :: m2 :: _) =>
                                InventoryLogger.error("Several machine were registered for a node. That is not supported. " +
                                    "The first in the following list will be choosen, but you may encouter strange " +
                                    "results in the future: %s".
                                      format(l.map{ case (id,status) => "%s [%s]".format(id.value, status.name)}.mkString(" ; "))
                                    ) *>
                                Some(m1).succeed
                            }
      inventoryDate      =  entry.getAsGTime(A_INVENTORY_DATE).map { _.dateTime }
      receiveDate        =  entry.getAsGTime(A_RECEIVE_DATE).map { _.dateTime }
      accounts           =  entry.valuesFor(A_ACCOUNT).toSeq
      serverIps          =  entry.valuesFor(A_LIST_OF_IP).toSeq
      serverRoles        =  entry.valuesFor(A_SERVER_ROLE).map(ServerRole(_)).toSet
      timezone           =  (entry(A_TIMEZONE_NAME), entry(A_TIMEZONE_OFFSET)) match {
                              case (Some(name), Some(offset)) => Some(NodeTimezone(name, offset))
                              case _                          => None
                            }
      customProperties   <-  { import CustomPropertiesSerialization.Unserialize
                              ZIO.foreach(entry.valuesFor(A_CUSTOM_PROPERTY))( a =>
                                ZIO.fromEither(a.toCustomProperty).foldM(ex =>
                                    InventoryLogger.warn(s"Error when deserializing node inventory custom property (ignoring that property): ${ex.getMessage}", ex) *> None.succeed
                                  , p => Some(p).succeed
                                )
                              )
                            }
      main               =  NodeSummary (
                                id
                              , inventoryStatus
                              , rootUser
                              , hostname
                              , osDetails
                              , NodeId(policyServerId)
                              , keyStatus
                            )
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
         , serverIps
         , machineId
         , softwareIds
         , accounts
         , ev
         , process
         , serverRoles = serverRoles
         , timezone = timezone
         , customProperties = customProperties.flatten
       )
    }
  }

  def nodeFromTree(tree:LDAPTree) : InventoryMappingResult[NodeInventory] = {
    for {
      node <- nodeFromEntry(tree.root)
      res  <- ZIO.foldLeft(tree.children)(node) { case (m,(rdn,t)) => mapAndAddNodeElement(t.root(),m) }
    } yield {
      res
    }
  }

}
