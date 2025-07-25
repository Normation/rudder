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

import com.normation.errors.*
import com.normation.inventory.domain.*
import com.normation.inventory.domain.InetAddressUtils.*
import com.normation.inventory.domain.VmType.*
import com.normation.inventory.ldap.core.InventoryMappingResult.*
import com.normation.inventory.ldap.core.InventoryMappingRudderError.*
import com.normation.inventory.ldap.core.LDAPConstants.*
import com.normation.ldap.sdk.*
import com.normation.ldap.sdk.schema.LDAPObjectClass
import com.normation.utils.DateFormaterService
import com.softwaremill.quicklens.*
import com.unboundid.ldap.sdk.{Version as _, *}
import java.net.InetAddress
import java.time.Instant
import org.joda.time.DateTime
import zio.*
import zio.json.*
import zio.syntax.*

sealed trait InventoryMappingRudderError extends RudderError
object InventoryMappingRudderError {
  final case class MissingMandatoryAttribute(attribute: String, entry: LDAPEntry) extends InventoryMappingRudderError {
    def msg: String = s"Missing required attribute '${attribute}' in entry: ${entry.toLDIFString()}"
  }
  final case class MalformedDN(msg: String)                                       extends InventoryMappingRudderError
  final case class MissingMandatory(msg: String)                                  extends InventoryMappingRudderError
  final case class UnknownElement(msg: String)                                    extends InventoryMappingRudderError
  final case class UnexpectedObject(msg: String)                                  extends InventoryMappingRudderError
}

object InventoryMappingResult {

  type InventoryMappingPure[T] = Either[InventoryMappingRudderError, T]

  implicit class RequiredAttrToPure(entry: LDAPEntry) {
    def required(attribute: String): InventoryMappingPure[String] = entry(attribute) match {
      case None    => Left(MissingMandatoryAttribute(attribute, entry))
      case Some(x) => Right(x)
    }
  }

  implicit class RequiredTypedAttrToPure(entry: LDAPEntry) {
    def requiredAs[T](f: LDAPEntry => String => Option[T], attribute: String): InventoryMappingPure[T] = {
      f(entry)(attribute) match {
        case None    => Left(MissingMandatoryAttribute(attribute, entry))
        case Some(x) => Right(x)
      }
    }
  }

}

object InventoryMapper {
  def getSoftwareUpdate(entry: LDAPEntry): IOResult[Chunk[SoftwareUpdate]] = {
    ZIO
      .foreach(entry.valuesForChunk(A_SOFTWARE_UPDATE)) { a =>
        import JsonSerializers.implicits.*
        import zio.json.*
        a.fromJson[SoftwareUpdate] match {
          case Left(err)    =>
            InventoryProcessingLogger.warn(
              s"Error when deserializing node software update (ignoring that update): ${err}"
            ) *> None.succeed
          case Right(value) =>
            Some(value).succeed
        }
      }
      .map(_.flatten)
  }
}

class InventoryMapper(
    ditService:  InventoryDitService,
    pendingDit:  InventoryDit,
    acceptedDit: InventoryDit,
    removedDit:  InventoryDit
) {

  ////////////////////////////////////////////////////////////
  ///////////////////////// Software /////////////////////////
  ////////////////////////////////////////////////////////////

  // software particularity : always in acceptedDit

  def entryFromSoftware(soft: Software): LDAPEntry = {
    val e = acceptedDit.SOFTWARE.SOFT.model(soft.id)
    e.setOpt(soft.name, A_NAME, (x: String) => x)
    e.setOpt(soft.description, A_DESCRIPTION, (x: String) => x)
    e.setOpt(soft.version, A_SOFT_VERSION, (x: Version) => x.value)
    e.setOpt(soft.editor, A_EDITOR, (x: SoftwareEditor) => x.name)
    e.setOpt(soft.releaseDate, A_RELEASE_DATE, (x: DateTime) => GeneralizedTime(DateFormaterService.toInstant(x)).toString)
    e.setOpt(soft.sourceName, A_SOURCE_NAME, (x: String) => x)
    e.setOpt(soft.sourceVersion, A_SOURCE_VERSION, (x: Version) => x.value)
    soft.license.foreach { lic =>
      e.resetValuesTo(A_LICENSE_NAME, lic.name)
      e.setOpt(lic.description, A_LICENSE_DESC, (x: String) => x)
      e.setOpt(lic.expirationDate, A_LICENSE_EXP, (x: Instant) => DateFormaterService.serializeInstant(x))
      e.setOpt(lic.productId, A_LICENSE_PRODUCT_ID, (x: String) => x)
      e.setOpt(lic.productKey, A_LICENSE_PRODUCT_KEY, (x: String) => x)
      e.setOpt(lic.oem, A_LICENSE_OEM, (x: String) => x)
    }
    e

  }

  def softwareFromEntry(e: LDAPEntry): InventoryMappingPure[Software] = {
    for {
      id            <- acceptedDit.SOFTWARE.SOFT.idFromDN(e.dn)
      name           = e(A_NAME)
      desc           = e(A_DESCRIPTION)
      version        = e(A_SOFT_VERSION).map(v => new Version(v))
      releaseDate    = e.getAsGTime(A_RELEASE_DATE).map(x => DateFormaterService.toDateTime(x.instant))
      editor         = e(A_EDITOR) map { x => new SoftwareEditor(x) }
      source_name    = e(A_SOURCE_NAME)
      source_version = e(A_SOURCE_VERSION).map(v => new Version(v))

      // licence
      lic = e(A_LICENSE_NAME) map { name =>
              License(
                name,
                description = e(A_LICENSE_DESC),
                productId = e(A_LICENSE_PRODUCT_ID),
                productKey = e(A_LICENSE_PRODUCT_KEY),
                oem = e(A_LICENSE_OEM),
                expirationDate = e.getAsGTime(A_LICENSE_EXP).map(_.instant)
              )
            }
    } yield {
      Software(id, name, desc, version, editor, releaseDate, lic, source_name, source_version)
    }
  }

  ////////////////////////////////////////////////////////////////////
  ///////////////////////// Machine Elements /////////////////////////
  ////////////////////////////////////////////////////////////////////

  ///////////////////////// Bios /////////////////////////

  def entryFromBios(elt: Bios, dit: InventoryDit, machineId: MachineUuid): LDAPEntry = {
    val e = dit.MACHINES.BIOS.model(machineId, elt.name)
    e.setOpt(elt.description, A_DESCRIPTION, (x: String) => x)
    e.resetValuesTo(A_QUANTITY, elt.quantity.toString)
    e.setOpt(elt.editor, A_EDITOR, (x: SoftwareEditor) => x.name)
    e.setOpt(elt.releaseDate, A_RELEASE_DATE, (x: Instant) => GeneralizedTime(x).toString)
    e.setOpt(elt.version, A_SOFT_VERSION, (x: Version) => x.value)
    e.setOpt(elt.manufacturer, A_MANUFACTURER, (x: Manufacturer) => x.name)
    e.setOpt(elt.serialNumber, A_SERIAL_NUMBER, (x: String) => x)
    e
  }

  def biosFromEntry(e: LDAPEntry): InventoryMappingPure[Bios] = {
    for {
      name        <- e.required(A_BIOS_NAME)
      desc         = e(A_DESCRIPTION)
      quantity     = e.getAsInt(A_QUANTITY).getOrElse(1)
      version      = e(A_SOFT_VERSION).map(v => new Version(v))
      releaseDate  = e.getAsGTime(A_RELEASE_DATE) map { _.instant }
      editor       = e(A_EDITOR) map { x => new SoftwareEditor(x) }
      manufacturer = e(A_MANUFACTURER).map(m => new Manufacturer(m))
      serialNumber = e(A_SERIAL_NUMBER)
    } yield {
      Bios(name, desc, version, editor, releaseDate, manufacturer, serialNumber, quantity)
    }
  }

  ///////////////////////// Controller /////////////////////////

  def entryFromController(elt: Controller, dit: InventoryDit, machineId: MachineUuid): LDAPEntry = {
    val e = dit.MACHINES.CONTROLLER.model(machineId, elt.name)
    e.setOpt(elt.description, A_DESCRIPTION, (x: String) => x)
    e.resetValuesTo(A_QUANTITY, elt.quantity.toString)
    e.setOpt(elt.manufacturer, A_MANUFACTURER, (x: Manufacturer) => x.name)
    e.setOpt(elt.cType, A_SME_TYPE, (x: String) => x)
    e
  }

  def controllerFromEntry(e: LDAPEntry): InventoryMappingPure[Controller] = {
    for {
      name        <- e.required(A_CONTROLLER_NAME)
      desc         = e(A_DESCRIPTION)
      manufacturer = e(A_MANUFACTURER).map(m => new Manufacturer(m))
      smeType      = e(A_SME_TYPE)
      quantity     = e.getAsInt(A_QUANTITY).getOrElse(1)
    } yield {
      Controller(name, desc, manufacturer, smeType, quantity)
    }
  }

  ///////////////////////// MemorySlot /////////////////////////

  def entryFromMemorySlot(elt: MemorySlot, dit: InventoryDit, machineId: MachineUuid): LDAPEntry = {
    val e = dit.MACHINES.MEMORY.model(machineId, elt.slotNumber)
    e.setOpt(elt.description, A_DESCRIPTION, (x: String) => x)
    e.resetValuesTo(A_QUANTITY, elt.quantity.toString)
    e.setOpt(elt.name, A_NAME, (x: String) => x)
    e.setOpt(elt.capacity, A_MEMORY_CAPACITY, (x: MemorySize) => x.size.toString)
    e.setOpt(elt.caption, A_MEMORY_CAPTION, (x: String) => x)
    e.setOpt(elt.speed, A_MEMORY_SPEED, (x: String) => x)
    e.setOpt(elt.memType, A_MEMORY_TYPE, (x: String) => x)
    e.setOpt(elt.serialNumber, A_SERIAL_NUMBER, (x: String) => x)
    e
  }

  def memorySlotFromEntry(e: LDAPEntry): InventoryMappingPure[MemorySlot] = {
    for {
      slotNumber <- e.required(A_MEMORY_SLOT_NUMBER)
      name        = e(A_NAME)
      desc        = e(A_DESCRIPTION)
      quantity    = e.getAsInt(A_QUANTITY).getOrElse(1)
      capacity    = e(A_MEMORY_CAPACITY).map(x => MemorySize(x))
      caption     = e(A_MEMORY_CAPTION)
      speed       = e(A_MEMORY_SPEED)
      memType     = e(A_MEMORY_TYPE)
      serial      = e(A_SERIAL_NUMBER)
    } yield {
      MemorySlot(slotNumber, name, desc, capacity, caption, speed, memType, serial, quantity)
    }
  }

  ///////////////////////// Port /////////////////////////

  def entryFromPort(elt: Port, dit: InventoryDit, machineId: MachineUuid): LDAPEntry = {
    val e = dit.MACHINES.PORT.model(machineId, elt.name)
    e.setOpt(elt.description, A_DESCRIPTION, (x: String) => x)
    e.resetValuesTo(A_QUANTITY, elt.quantity.toString)
    e.setOpt(elt.pType, A_SME_TYPE, (x: String) => x)
    e
  }

  def portFromEntry(e: LDAPEntry): InventoryMappingPure[Port] = {
    for {
      name    <- e.required(A_PORT_NAME)
      desc     = e(A_DESCRIPTION)
      smeType  = e(A_SME_TYPE)
      quantity = e.getAsInt(A_QUANTITY).getOrElse(1)
    } yield {
      Port(name, desc, smeType, quantity)
    }
  }

  ///////////////////////// Processor /////////////////////////

  def entryFromProcessor(elt: Processor, dit: InventoryDit, machineId: MachineUuid): LDAPEntry = {
    val e = dit.MACHINES.CPU.model(machineId, elt.name)
    e.setOpt(elt.description, A_DESCRIPTION, (x: String) => x)
    e.resetValuesTo(A_QUANTITY, elt.quantity.toString)
    e.setOpt(elt.speed, A_PROCESSOR_SPEED, (x: Int) => x.toString)
    e.setOpt(elt.stepping, A_PROCESSOR_STEPPING, (x: Int) => x.toString)
    e.setOpt(elt.family, A_PROCESSOR_FAMILLY, (x: Int) => x.toString())
    e.setOpt(elt.model, A_MODEL, (x: Int) => x.toString())
    e.setOpt(elt.manufacturer, A_MANUFACTURER, (x: Manufacturer) => x.name)
    e.setOpt(elt.core, A_CORE, (x: Int) => x.toString())
    e.setOpt(elt.thread, A_THREAD, (x: Int) => x.toString())
    e.setOpt(elt.familyName, A_PROCESSOR_FAMILY_NAME, (x: String) => x)
    e.setOpt(elt.arch, A_PROCESSOR_ARCHITECTURE, (x: String) => x)
    e.setOpt(elt.cpuid, A_CPUID, (x: String) => x)
    e.setOpt(elt.externalClock, A_EXTERNAL_CLOCK, (x: Float) => x.toString())
    e
  }

  def processorFromEntry(e: LDAPEntry): InventoryMappingPure[Processor] = {
    for {
      name         <- e.required(A_PROCESSOR_NAME)
      desc          = e(A_DESCRIPTION)
      quantity      = e.getAsInt(A_QUANTITY).getOrElse(1)
      speed         = e.getAsInt(A_PROCESSOR_SPEED)
      stepping      = e.getAsInt(A_PROCESSOR_STEPPING)
      family        = e.getAsInt(A_PROCESSOR_FAMILLY)
      model         = e.getAsInt(A_MODEL)
      manufacturer  = e(A_MANUFACTURER).map(m => new Manufacturer(m))
      core          = e.getAsInt(A_CORE)
      thread        = e.getAsInt(A_THREAD)
      familyName    = e(A_PROCESSOR_FAMILY_NAME)
      arch          = e(A_PROCESSOR_ARCHITECTURE)
      externalClock = e.getAsFloat(A_EXTERNAL_CLOCK)
      cpuid         = e(A_CPUID)
    } yield {
      Processor(
        manufacturer,
        name,
        arch,
        desc,
        speed,
        externalClock,
        core,
        thread,
        cpuid,
        stepping,
        family,
        familyName,
        model,
        quantity
      )
    }
  }

  ///////////////////////// Slot /////////////////////////

  def entryFromSlot(elt: Slot, dit: InventoryDit, machineId: MachineUuid): LDAPEntry = {
    val e = dit.MACHINES.PORT.model(machineId, elt.name)
    e.setOpt(elt.description, A_DESCRIPTION, (x: String) => x)
    e.resetValuesTo(A_QUANTITY, elt.quantity.toString)
    e.setOpt(elt.status, A_STATUS, (x: String) => x)
    e
  }

  def slotFromEntry(e: LDAPEntry): InventoryMappingPure[Slot] = {
    for {
      name    <- e.required(A_SLOT_NAME)
      desc     = e(A_DESCRIPTION)
      status   = e(A_STATUS)
      quantity = e.getAsInt(A_QUANTITY).getOrElse(1)
    } yield {
      Slot(name, desc, status, quantity)
    }
  }

  ///////////////////////// Sound /////////////////////////

  def entryFromSound(elt: Sound, dit: InventoryDit, machineId: MachineUuid): LDAPEntry = {
    val e = dit.MACHINES.SOUND.model(machineId, elt.name)
    e.setOpt(elt.description, A_DESCRIPTION, (x: String) => x)
    e.resetValuesTo(A_QUANTITY, elt.quantity.toString)
    e
  }

  def soundFromEntry(e: LDAPEntry): InventoryMappingPure[Sound] = {
    for {
      name    <- e.required(A_SOUND_NAME)
      desc     = e(A_DESCRIPTION)
      quantity = e.getAsInt(A_QUANTITY).getOrElse(1)
    } yield {
      Sound(name, desc, quantity)
    }
  }

  ///////////////////////// Storage /////////////////////////

  def entryFromStorage(elt: Storage, dit: InventoryDit, machineId: MachineUuid): LDAPEntry = {
    val e = dit.MACHINES.STORAGE.model(machineId, elt.name)
    e.setOpt(elt.description, A_DESCRIPTION, (x: String) => x)
    e.resetValuesTo(A_QUANTITY, elt.quantity.toString)
    e.setOpt(elt.size, A_STORAGE_SIZE, (x: MemorySize) => x.size.toString)
    e.setOpt(elt.firmware, A_STORAGE_FIRMWARE, (x: String) => x)
    e.setOpt(elt.manufacturer, A_MANUFACTURER, (x: Manufacturer) => x.name)
    e.setOpt(elt.model, A_MODEL, (x: String) => x)
    e.setOpt(elt.serialNumber, A_SERIAL_NUMBER, (x: String) => x)
    e.setOpt(elt.sType, A_SME_TYPE, (x: String) => x)

    e
  }

  def storageFromEntry(e: LDAPEntry): InventoryMappingPure[Storage] = {
    for {
      name        <- e.required(A_STORAGE_NAME)
      desc         = e(A_DESCRIPTION)
      size         = e(A_STORAGE_SIZE).map(x => MemorySize(x))
      firmware     = e(A_STORAGE_FIRMWARE)
      manufacturer = e(A_MANUFACTURER).map(m => new Manufacturer(m))
      model        = e(A_MODEL)
      serialNumber = e(A_SERIAL_NUMBER)
      smeType      = e(A_SME_TYPE)
      quantity     = e.getAsInt(A_QUANTITY).getOrElse(1)
    } yield {
      Storage(name, desc, size, firmware, manufacturer, model, serialNumber, smeType, quantity)
    }
  }

  ///////////////////////// Video /////////////////////////

  def entryFromVideo(elt: Video, dit: InventoryDit, machineId: MachineUuid): LDAPEntry = {
    val e = dit.MACHINES.VIDEO.model(machineId, elt.name)
    e.setOpt(elt.description, A_DESCRIPTION, (x: String) => x)
    e.resetValuesTo(A_QUANTITY, elt.quantity.toString)
    e.setOpt(elt.chipset, A_VIDEO_CHIPSET, (x: String) => x)
    e.setOpt(elt.memory, A_MEMORY_CAPACITY, (x: MemorySize) => x.size.toString)
    e.setOpt(elt.resolution, A_VIDEO_RESOLUTION, (x: String) => x)
    e
  }

  def videoFromEntry(e: LDAPEntry): InventoryMappingPure[Video] = {
    for {
      name      <- e.required(A_VIDEO_NAME)
      desc       = e(A_DESCRIPTION)
      quantity   = e.getAsInt(A_QUANTITY).getOrElse(1)
      chipset    = e(A_VIDEO_CHIPSET)
      memory     = e(A_MEMORY_CAPACITY).map(x => MemorySize(x))
      resolution = e(A_VIDEO_RESOLUTION)
    } yield {
      Video(name, desc, chipset, memory, resolution, quantity)
    }
  }

  //////////////////// Machine ////////////////////

  // perhaps that should be in DIT ?
  // def machineType2Filter(mt : MachineType) : Filter = BuildFilter.IS(machineType2ObjectClass(mt).name)

  private def machineType2ObjectClass(mt: MachineType): LDAPObjectClass = {
    mt match {
      case VirtualMachineType(UnknownVmType) => OC_OC_VM
      case VirtualMachineType(VirtualBox)    => OC_OC_VM_VIRTUALBOX
      case VirtualMachineType(Xen)           => OC_OC_VM_XEN
      case VirtualMachineType(VMWare)        => OC_OC_VM_VMWARE
      case VirtualMachineType(SolarisZone)   => OC_OC_VM_SOLARIS_ZONE
      case VirtualMachineType(QEmu)          => OC_OC_VM_QEMU
      case VirtualMachineType(AixLPAR)       => OC_OC_VM_AIX_LPAR
      case VirtualMachineType(HyperV)        => OC_OC_VM_HYPERV
      case VirtualMachineType(BSDJail)       => OC_OC_VM_BSDJAIL
      case VirtualMachineType(OpenVZ)        => OC_OC_VM_OPENVZ
      case VirtualMachineType(Virtuozzo)     => OC_OC_VM_VIRTUOZZO
      case VirtualMachineType(LXC)           => OC_OC_VM_LXC
      case PhysicalMachineType               => OC_OC_PM
      case UnknownMachineType                => OC_OC_MACHINE
    }
  }

  def machineTypeFromObjectClasses(objectClassNames: Set[String]): MachineType = {
    def objectClass2MachineType(oc: LDAPObjectClass): MachineType = {
      oc match {
        case LDAPObjectClass(OC_VM, _, _, _)              => VirtualMachineType(UnknownVmType)
        case LDAPObjectClass(OC_VM_VIRTUALBOX, _, _, _)   => VirtualMachineType(VirtualBox)
        case LDAPObjectClass(OC_VM_XEN, _, _, _)          => VirtualMachineType(Xen)
        case LDAPObjectClass(OC_VM_VMWARE, _, _, _)       => VirtualMachineType(VMWare)
        case LDAPObjectClass(OC_VM_SOLARIS_ZONE, _, _, _) => VirtualMachineType(SolarisZone)
        case LDAPObjectClass(OC_VM_QEMU, _, _, _)         => VirtualMachineType(QEmu)
        case LDAPObjectClass(OC_VM_AIX_LPAR, _, _, _)     => VirtualMachineType(AixLPAR)
        case LDAPObjectClass(OC_VM_HYPERV, _, _, _)       => VirtualMachineType(HyperV)
        case LDAPObjectClass(OC_VM_BSDJAIL, _, _, _)      => VirtualMachineType(BSDJail)
        case LDAPObjectClass(OC_VM_LXC, _, _, _)          => VirtualMachineType(LXC)
        case LDAPObjectClass(OC_VM_VIRTUOZZO, _, _, _)    => VirtualMachineType(Virtuozzo)
        case LDAPObjectClass(OC_VM_OPENVZ, _, _, _)       => VirtualMachineType(OpenVZ)
        case LDAPObjectClass(OC_PM, _, _, _)              => PhysicalMachineType
        case _                                            => UnknownMachineType
      }
    }
    val machineTypes = objectClassNames.filter(x => machineTypesNames.exists(y => x.equalsIgnoreCase(y)))
    val types = OC.demux(machineTypes.toSeq*) - OC(OC_MACHINE)
    if (types.size == 1) objectClass2MachineType(types.head) else UnknownMachineType
  }

  def treeFromMachine(machine: MachineInventory): LDAPTree = {
    // the root entry of the tree: the machine inventory
    val dit  = ditService.getDit(machine.status)
    val root = dit.MACHINES.MACHINE.model(machine.id)
    root.setOpt(machine.mbUuid, A_MB_UUID, (x: MotherBoardUuid) => x.value)
    root.addValues(A_OC, OC.objectClassNames(machineType2ObjectClass(machine.machineType).name)*)
    root.setOpt(machine.inventoryDate, A_INVENTORY_DATE, (x: Instant) => GeneralizedTime(x).toString)
    root.setOpt(machine.receiveDate, A_RECEIVE_DATE, (x: Instant) => GeneralizedTime(x).toString)
    root.setOpt(machine.name.orElse(Some(machine.id.value)), A_NAME, (x: String) => x)
    root.setOpt(machine.manufacturer, A_MANUFACTURER, (x: Manufacturer) => x.name)
    root.setOpt(machine.systemSerialNumber, A_SERIAL_NUMBER, (x: String) => x)

    val tree = LDAPTree(root)
    // now, add machine elements as children
    machine.bios.foreach(x => tree.addChild(entryFromBios(x, dit, machine.id)))
    machine.controllers.foreach(x => tree.addChild(entryFromController(x, dit, machine.id)))
    machine.memories.foreach(x => tree.addChild(entryFromMemorySlot(x, dit, machine.id)))
    machine.ports.foreach(x => tree.addChild(entryFromPort(x, dit, machine.id)))
    machine.processors.foreach(x => tree.addChild(entryFromProcessor(x, dit, machine.id)))
    machine.slots.foreach(x => tree.addChild(entryFromSlot(x, dit, machine.id)))
    machine.sounds.foreach(x => tree.addChild(entryFromSound(x, dit, machine.id)))
    machine.storages.foreach(x => tree.addChild(entryFromStorage(x, dit, machine.id)))
    machine.videos.foreach(x => tree.addChild(entryFromVideo(x, dit, machine.id)))

    // ok !
    tree
  }

  /*
   * Utility method that do the lookup between an LDAPEntry
   * and the matching machine elements.
   * It adds it to a machine, return the modified machine.
   * If the mapping goes bad or the entry type is unknown, the
   * MachineInventory is returned as it was, and the error is logged
   */
  private def mapAndAddElementGeneric[U, T](
      from: U,
      e:    LDAPEntry,
      name: String,
      f:    LDAPEntry => InventoryMappingPure[T],
      path: U => PathModify[U, Seq[T]]
  ): UIO[U] = {
    f(e) match {
      case Left(error)  =>
        InventoryProcessingLogger.error(
          Chained(s"Error when mapping LDAP entry to a '${name}'. Entry details: ${e}.", error).fullMsg
        ) *>
        from.succeed
      case Right(value) =>
        path(from).using(value +: _).succeed
    }
  }

  private def mapAndAddMachineElement(entry: LDAPEntry, machine: MachineInventory): UIO[MachineInventory] = {
    def mapAndAdd[T](
        name: String,
        f:    LDAPEntry => InventoryMappingPure[T],
        path: MachineInventory => PathModify[MachineInventory, Seq[T]]
    ): UIO[MachineInventory] = mapAndAddElementGeneric[MachineInventory, T](machine, entry, name, f, path)

    import com.softwaremill.quicklens.*

    entry match {
      case e if (e.isA(OC_MEMORY))     => mapAndAdd("memory slot", memorySlotFromEntry, _.modify(_.memories))
      case e if (e.isA(OC_PORT))       => mapAndAdd("port", portFromEntry, _.modify(_.ports))
      case e if (e.isA(OC_SLOT))       => mapAndAdd("slot", slotFromEntry, _.modify(_.slots))
      case e if (e.isA(OC_SOUND))      => mapAndAdd("sound card", soundFromEntry, _.modify(_.sounds))
      case e if (e.isA(OC_BIOS))       => mapAndAdd("bios", biosFromEntry, _.modify(_.bios))
      case e if (e.isA(OC_CONTROLLER)) => mapAndAdd("controller", controllerFromEntry, _.modify(_.controllers))
      case e if (e.isA(OC_PROCESSOR))  => mapAndAdd("processor", processorFromEntry, _.modify(_.processors))
      case e if (e.isA(OC_STORAGE))    => mapAndAdd("storage", storageFromEntry, _.modify(_.storages))
      case e if (e.isA(OC_VIDEO))      => mapAndAdd("video", videoFromEntry, _.modify(_.videos))
      case e                           =>
        InventoryProcessingLogger.error(
          s"Unknown entry type for a machine element, that entry will be ignored: ${e}"
        ) *> machine.succeed
    }
  }

  def machineFromTree(tree: LDAPTree): IOResult[MachineInventory] = {
    for {
      dit               <- ditService.getDit(tree.root.dn).notOptional(s"Impossible to find DIT for entry '${tree.root.dn.toString()}'")
      inventoryStatus    = ditService.getInventoryStatus(dit)
      id                <- dit.MACHINES.MACHINE.idFromDN(tree.root.dn).toIO
      machineType        = machineTypeFromObjectClasses(tree.root.valuesFor(A_OC).toSet)
      name               = tree.root(A_NAME)
      mbUuid             = tree.root(A_MB_UUID) map { x => MotherBoardUuid(x) }
      inventoryDate      = tree.root.getAsGTime(A_INVENTORY_DATE).map(_.instant)
      receiveDate        = tree.root.getAsGTime(A_RECEIVE_DATE).map(_.instant)
      manufacturer       = tree.root(A_MANUFACTURER).map(m => new Manufacturer(m))
      systemSerialNumber = tree.root(A_SERIAL_NUMBER)
      // now, get all subentries
      m                  = MachineInventory(
                             id,
                             inventoryStatus,
                             machineType,
                             name,
                             mbUuid,
                             inventoryDate,
                             receiveDate,
                             manufacturer,
                             systemSerialNumber
                           )
      // map subentries and return result
      res               <- ZIO.foldLeft(tree.children)(m) { case (m, (rdn, t)) => mapAndAddMachineElement(t.root, m) }
    } yield {
      res
    }
  }

  ///////////////////////////////////////////////////////////////////////////////////////////////
  /////////////////////////////////// ServerInventory mapping ///////////////////////////////////
  ///////////////////////////////////////////////////////////////////////////////////////////////

  ///////////////////////// FileSystem /////////////////////////

  def entryFromFileSystem(elt: FileSystem, dit: InventoryDit, serverId: NodeId): LDAPEntry = {
    val e = dit.NODES.FILESYSTEM.model(serverId, elt.mountPoint)
    e.setOpt(elt.name, A_NAME, (x: String) => x)
    e.setOpt(elt.description, A_DESCRIPTION, (x: String) => x)
    e.setOpt(elt.fileCount, A_FILE_COUNT, (x: Int) => x.toString)
    e.setOpt(elt.freeSpace, A_FREE_SPACE, (x: MemorySize) => x.size.toString)
    e.setOpt(elt.totalSpace, A_TOTAL_SPACE, (x: MemorySize) => x.size.toString)
    e
  }

  def fileSystemFromEntry(e: LDAPEntry): InventoryMappingPure[FileSystem] = {
    for {
      mountPoint <- e.required(A_MOUNT_POINT)
      name        = e(A_NAME)
      desc        = e(A_DESCRIPTION)
      fileCount   = e.getAsInt(A_FILE_COUNT)
      freeSpace   = e.getAsLong(A_FREE_SPACE).map(new MemorySize(_))
      totalSpace  = e.getAsLong(A_TOTAL_SPACE).map(new MemorySize(_))
    } yield {
      FileSystem(mountPoint, name, desc, fileCount, freeSpace, totalSpace)
    }
  }

  ///////////////////////// Networks /////////////////////////

  def entryFromNetwork(elt: Network, dit: InventoryDit, serverId: NodeId): LDAPEntry = {
    // mutable, yep
    def setSeqAddress(e: LDAPEntry, attr: String, list: Seq[InetAddress]): Unit = {
      if (list.isEmpty) {
        e deleteAttribute attr
      } else {
        e.resetValuesTo(attr, list.map(_.getHostAddress)*)
      }
    }

    val e = dit.NODES.NETWORK.model(serverId, elt.name)
    e.setOpt(elt.description, A_DESCRIPTION, (x: String) => x)
    // addresses
    setSeqAddress(e, A_NETIF_ADDRESS, elt.ifAddresses)
    setSeqAddress(e, A_NETIF_GATEWAY, elt.ifGateway)
    setSeqAddress(e, A_NETIF_MASK, elt.ifMask)
    setSeqAddress(e, A_NETIF_SUBNET, elt.ifSubnet)
    e.setOpt(elt.ifDhcp, A_NETIF_DHCP, (x: InetAddress) => x.getHostAddress)
    e.setOpt(elt.macAddress, A_NETIF_MAC, (x: String) => x)
    e.setOpt(elt.status, A_STATUS, (x: String) => x)
    e.setOpt(elt.ifType, A_NETIF_TYPE, (x: String) => x)
    e.setOpt(elt.speed, A_SPEED, (x: String) => x)
    e.setOpt(elt.typeMib, A_NETIF_TYPE_MIB, (x: String) => x)
    e
  }

  def networkFromEntry(e: LDAPEntry): InventoryMappingPure[Network] = {
    for {
      name       <- e.required(A_NETWORK_NAME)
      desc        = e(A_DESCRIPTION)
      ifAddresses = e.valuesFor(A_NETIF_ADDRESS).toSeq.flatMap(getAddressByName)
      ifGateway   = e.valuesFor(A_NETIF_GATEWAY).toSeq.flatMap(getAddressByName)
      ifMask      = e.valuesFor(A_NETIF_MASK).toSeq.flatMap(getAddressByName)
      ifSubnet    = e.valuesFor(A_NETIF_SUBNET).toSeq.flatMap(getAddressByName)
      ifDhcp      = e(A_NETIF_DHCP).flatMap(getAddressByName(_))
      macAddress  = e(A_NETIF_MAC)
      status      = e(A_STATUS)
      ifType      = e(A_NETIF_TYPE)
      speed       = e(A_SPEED)
      typeMib     = e(A_NETIF_TYPE_MIB)
    } yield {
      Network(name, desc, ifAddresses, ifDhcp, ifGateway, ifMask, ifSubnet, macAddress, status, ifType, speed, typeMib)
    }
  }

  ///////////////////////// VM INFO /////////////////////////

  def entryFromVMInfo(elt: VirtualMachine, dit: InventoryDit, serverId: NodeId): LDAPEntry = {
    val e = dit.NODES.VM.model(serverId, elt.uuid.value)
    e.setOpt(elt.description, A_DESCRIPTION, (x: String) => x)
    e.setOpt(elt.memory, A_VM_MEMORY, (x: String) => x)
    e.setOpt(elt.name, A_VM_NAME, (x: String) => x)
    e.setOpt(elt.owner, A_VM_OWNER, (x: String) => x)
    e.setOpt(elt.status, A_VM_STATUS, (x: String) => x)
    e.setOpt(elt.subsystem, A_VM_SUBSYSTEM, (x: String) => x)
    e.setOpt(elt.vcpu, A_VM_CPU, (x: Int) => x.toString())
    e.setOpt(elt.vmtype, A_VM_TYPE, (x: String) => x)
    e
  }

  def vmFromEntry(e: LDAPEntry): InventoryMappingPure[VirtualMachine] = {
    for {
      vmid     <- e.required(A_VM_ID)
      memory    = e(A_VM_MEMORY)
      name      = e(A_VM_NAME)
      owner     = e(A_VM_OWNER)
      status    = e(A_VM_STATUS)
      subsystem = e(A_VM_SUBSYSTEM)
      vcpu      = e.getAsInt(A_VM_CPU)
      vmtype    = e(A_VM_TYPE)

    } yield {
      VirtualMachine(vmtype, subsystem, owner, name, status, vcpu, memory, new MachineUuid(vmid))
    }
  }

  ////////////////// Node/ NodeInventory /////////////////////////

  private def createNodeModelFromServer(server: NodeInventory): LDAPEntry = {
    val dit  = ditService.getDit(server.main.status)
    // the root entry of the tree: the machine inventory
    val root = server.main.osDetails match {
      case UnknownOS(osFullName, osVersion, osServicePack, kernelVersion) =>
        dit.NODES.NODE.genericModel(server.main.id)

      case Linux(os, osFullName, osVersion, osServicePack, kernelVersion) =>
        val linux = dit.NODES.NODE.linuxModel(server.main.id)
        os match {
          case Debian      => linux.addValues(A_OS_NAME, A_OS_DEBIAN)
          case Kali        => linux.addValues(A_OS_NAME, A_OS_KALI)
          case Ubuntu      => linux.addValues(A_OS_NAME, A_OS_UBUNTU)
          case Redhat      => linux.addValues(A_OS_NAME, A_OS_REDHAT)
          case Centos      => linux.addValues(A_OS_NAME, A_OS_CENTOS)
          case Fedora      => linux.addValues(A_OS_NAME, A_OS_FEDORA)
          case Suse        => linux.addValues(A_OS_NAME, A_OS_SUZE)
          case Android     => linux.addValues(A_OS_NAME, A_OS_ANDROID)
          case Oracle      => linux.addValues(A_OS_NAME, A_OS_ORACLE)
          case Scientific  => linux.addValues(A_OS_NAME, A_OS_SCIENTIFIC)
          case Slackware   => linux.addValues(A_OS_NAME, A_OS_SLACKWARE)
          case Mint        => linux.addValues(A_OS_NAME, A_OS_MINT)
          case AmazonLinux => linux.addValues(A_OS_NAME, A_OS_AMAZON_LINUX)
          case RockyLinux  => linux.addValues(A_OS_NAME, A_OS_ROCKY_LINUX)
          case AlmaLinux   => linux.addValues(A_OS_NAME, A_OS_ALMA_LINUX)
          case Raspbian    => linux.addValues(A_OS_NAME, A_OS_RASPBIAN)
          case Tuxedo      => linux.addValues(A_OS_NAME, A_OS_TUXEDO)
          case _           => linux.addValues(A_OS_NAME, A_OS_UNKNOWN_LINUX)
        }
        linux

      case Solaris(_, _, _, _) =>
        val solaris = dit.NODES.NODE.solarisModel(server.main.id)
        solaris.addValues(A_OS_NAME, A_OS_SOLARIS)
        solaris

      case Aix(_, _, _, _) =>
        val aix = dit.NODES.NODE.aixModel(server.main.id)
        aix.addValues(A_OS_NAME, A_OS_AIX)
        aix

      case Bsd(os, _, _, _, _) =>
        val bsd = dit.NODES.NODE.bsdModel(server.main.id)
        os match {
          case FreeBSD => bsd.addValues(A_OS_NAME, A_OS_FREEBSD)
          case _       => bsd.addValues(A_OS_NAME, A_OS_UNKNOWN_BSD)
        }
        bsd

      case Windows(
            os,
            osFullName,
            osVersion,
            osServicePack,
            kernelVersion,
            userDomain,
            registrationCompany,
            productKey,
            productId
          ) =>
        val win = dit.NODES.NODE.windowsModel(server.main.id)
        os match {
          case WindowsXP     => win.addValues(A_OS_NAME, A_OS_WIN_XP)
          case WindowsVista  => win.addValues(A_OS_NAME, A_OS_WIN_VISTA)
          case WindowsSeven  => win.addValues(A_OS_NAME, A_OS_WIN_SEVEN)
          case Windows10     => win.addValues(A_OS_NAME, A_OS_WIN_10)
          case Windows11     => win.addValues(A_OS_NAME, A_OS_WIN_11)
          case Windows2000   => win.addValues(A_OS_NAME, A_OS_WIN_2000)
          case Windows2003   => win.addValues(A_OS_NAME, A_OS_WIN_2003)
          case Windows2008   => win.addValues(A_OS_NAME, A_OS_WIN_2008)
          case Windows2008R2 => win.addValues(A_OS_NAME, A_OS_WIN_2008_R2)
          case Windows2012   => win.addValues(A_OS_NAME, A_OS_WIN_2012)
          case Windows2012R2 => win.addValues(A_OS_NAME, A_OS_WIN_2012_R2)
          case Windows2016   => win.addValues(A_OS_NAME, A_OS_WIN_2016)
          case Windows2016R2 => win.addValues(A_OS_NAME, A_OS_WIN_2016_R2)
          case Windows2019   => win.addValues(A_OS_NAME, A_OS_WIN_2019)
          case Windows2022   => win.addValues(A_OS_NAME, A_OS_WIN_2022)
          case Windows2025   => win.addValues(A_OS_NAME, A_OS_WIN_2025)
          case _             => win.addValues(A_OS_NAME, A_OS_UNKNOWN_WINDOWS)
        }
        win.setOpt(userDomain, A_WIN_USER_DOMAIN, (x: String) => x)
        win.setOpt(registrationCompany, A_WIN_COMPANY, (x: String) => x)
        win.setOpt(productKey, A_WIN_KEY, (x: String) => x)
        win.setOpt(productId, A_WIN_ID, (x: String) => x)
        win
    }
    root
  }

  // This won't include the Process in it, it needs to be done with method
  // processesFromNode below
  def treeFromNode(server: NodeInventory): LDAPTree = {
    val dit  = ditService.getDit(server.main.status)
    // the root entry of the tree: the machine inventory
    val root = rootEntryFromNode(server)

    root.resetValuesTo(A_OS_FULL_NAME, server.main.osDetails.fullName)
    root.resetValuesTo(A_OS_VERSION, server.main.osDetails.version.value)
    root.setOpt(server.main.osDetails.servicePack, A_OS_SERVICE_PACK, (x: String) => x)
    root.resetValuesTo(A_OS_KERNEL_VERSION, server.main.osDetails.kernelVersion.value)
    root.resetValuesTo(A_ROOT_USER, server.main.rootUser)
    root.resetValuesTo(A_HOSTNAME, server.main.hostname)
    root.resetValuesTo(A_POLICY_SERVER_UUID, server.main.policyServerId.value)
    root.setOpt(server.ram, A_OS_RAM, (m: MemorySize) => m.size.toString)
    root.setOpt(server.swap, A_OS_SWAP, (m: MemorySize) => m.size.toString)
    root.setOpt(server.archDescription, A_ARCH, (x: String) => x)
    root.setOpt(server.lastLoggedUser, A_LAST_LOGGED_USER, (x: String) => x)
    root.setOpt(server.lastLoggedUserTime, A_LAST_LOGGED_USER_TIME, (x: Instant) => GeneralizedTime(x).toString)
    root.setOpt(server.inventoryDate, A_INVENTORY_DATE, (x: Instant) => GeneralizedTime(x).toString)
    root.setOpt(server.receiveDate, A_RECEIVE_DATE, (x: Instant) => GeneralizedTime(x).toString)
    root.resetValuesTo(A_AGENT_NAME, server.agents.map(x => x.toJson)*)
    root.resetValuesTo(A_SOFTWARE_DN, server.softwareIds.map(x => dit.SOFTWARE.SOFT.dn(x).toString)*)
    root.resetValuesTo(A_EV, server.environmentVariables.map(_.toJson)*)
    root.resetValuesTo(A_LIST_OF_IP, server.serverIps.distinct*)
    // we don't know their dit...
    root.resetValuesTo(
      A_CONTAINER_DN,
      server.machineId.map {
        case (id, status) =>
          ditService.getDit(status).MACHINES.MACHINE.dn(id).toString
      }.toSeq*
    )
    root.resetValuesTo(A_ACCOUNT, server.accounts*)
    server.timezone.foreach { timezone =>
      root.resetValuesTo(A_TIMEZONE_NAME, timezone.name)
      root.resetValuesTo(A_TIMEZONE_OFFSET, timezone.offset)
    }
    server.customProperties.foreach(cp => root.addValues(A_CUSTOM_PROPERTY, cp.toJson))
    server.softwareUpdates.foreach { s =>
      import JsonSerializers.implicits.*
      root.addValues(A_SOFTWARE_UPDATE, s.toJson)
    }

    val tree = LDAPTree(root)
    // now, add machine elements as children
    server.networks.foreach(x => tree.addChild(entryFromNetwork(x, dit, server.main.id)))
    server.fileSystems.foreach(x => tree.addChild(entryFromFileSystem(x, dit, server.main.id)))
    server.vms.foreach(x => tree.addChild(entryFromVMInfo(x, dit, server.main.id)))
    tree
  }

  // only things that can be changed by user.
  def rootEntryFromNode(server: NodeInventory): LDAPEntry = {
    // the root entry of the tree: the machine inventory
    val root = createNodeModelFromServer(server)
    root.resetValuesTo(A_KEY_STATUS, server.main.keyStatus.value)

    root
  }

  // map process from node
  def processesFromNode(node: NodeInventory): Seq[String] = {
    // convert the processes
    node.processes.map(_.toJson)
  }

  // Create the entry with only processes
  // we need to have a proper object class to avoid error
  // com.unboundid.ldap.sdk.LDAPException: no structural object class provide
  def entryWithProcessFromNode(node: NodeInventory): LDAPEntry = {
    val entry = createNodeModelFromServer(node)
    // convert the processes
    entry.resetValuesTo(A_PROCESS, processesFromNode(node)*)
    entry
  }

  /*
   * Utility method that do the lookup between an LDAPEntry
   * and the matching server elements.
   * It adds it to a server, return the modified server.
   * If the mapping goes bad or the entry type is unknown, the
   * NodeInventory is returned as it was, and the error is logged
   */
  private def mapAndAddNodeElement(entry: LDAPEntry, node: NodeInventory): UIO[NodeInventory] = {
    def mapAndAdd[T](
        name: String,
        f:    LDAPEntry => InventoryMappingPure[T],
        path: NodeInventory => PathModify[NodeInventory, Seq[T]]
    ): UIO[NodeInventory] = mapAndAddElementGeneric[NodeInventory, T](node, entry, name, f, path)

    entry match {
      case e if (e.isA(OC_NET_IF))  => mapAndAdd("network interface", networkFromEntry, _.modify(_.networks))
      case e if (e.isA(OC_FS))      => mapAndAdd("file system", fileSystemFromEntry, _.modify(_.fileSystems))
      case e if (e.isA(OC_VM_INFO)) => mapAndAdd("virtual machine", vmFromEntry, _.modify(_.vms))
      case e                        =>
        InventoryProcessingLogger.error(s"Unknow entry type for a server element, that entry will be ignored: ${e}") *>
        node.succeed
    }

  }

  def mapSeqStringToMachineIdAndStatus(set: Set[String]): Seq[(MachineUuid, InventoryStatus)] = {
    set.toSeq.flatMap { x =>
      (for {
        dn   <- try { Right(new DN(x)) }
                catch { case e: LDAPException => Left(MalformedDN(s"Can not parse DN: '${x}'. Exception was: ${e.getMessage}")) }
        dit  <- ditService.getDit(dn).toRight(Inconsistency(s"Can not find DIT from DN '${x}'"))
        uuid <- dit.MACHINES.MACHINE.idFromDN(dn)
        st    = ditService.getInventoryStatus(dit)
      } yield (uuid, st)) match {
        case Right(st) => List(st)
        case Left(err) =>
          InventoryProcessingLogger.logEffect.error(s"Error when processing machine DN '${x}': ${err.msg}")
          Nil
      }
    }
  }

  def mapOsDetailsFromEntry(entry: LDAPEntry): InventoryMappingPure[OsDetails] = {
    for {
      osName        <- entry.required(A_OS_NAME)
      osVersion     <- entry.required(A_OS_VERSION).map(x => new Version(x))
      kernelVersion <- entry.required(A_OS_KERNEL_VERSION).map(x => new Version(x))
      osFullName     = entry(A_OS_FULL_NAME).getOrElse("")
      osServicePack  = entry(A_OS_SERVICE_PACK)
      osDetails     <- if (entry.isA(OC_WINDOWS_NODE)) {
                         val os                  = osName match {
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
                           case A_OS_WIN_2022    => Windows2022
                           case A_OS_WIN_2025    => Windows2025
                           case _                => UnknownWindowsType
                         }
                         val userDomain          = entry(A_WIN_USER_DOMAIN)
                         val registrationCompany = entry(A_WIN_COMPANY)
                         val productKey          = entry(A_WIN_KEY)
                         val productId           = entry(A_WIN_ID)
                         Right(
                           Windows(
                             os,
                             osFullName,
                             osVersion,
                             osServicePack,
                             kernelVersion,
                             userDomain,
                             registrationCompany,
                             productKey,
                             productId
                           )
                         )

                       } else if (entry.isA(OC_LINUX_NODE)) {
                         val os = osName match {
                           case A_OS_DEBIAN       => Debian
                           case A_OS_KALI         => Kali
                           case A_OS_UBUNTU       => Ubuntu
                           case A_OS_REDHAT       => Redhat
                           case A_OS_CENTOS       => Centos
                           case A_OS_FEDORA       => Fedora
                           case A_OS_SUZE         => Suse
                           case A_OS_ORACLE       => Oracle
                           case A_OS_SCIENTIFIC   => Scientific
                           case A_OS_ANDROID      => Android
                           case A_OS_SLACKWARE    => Slackware
                           case A_OS_MINT         => Mint
                           case A_OS_AMAZON_LINUX => AmazonLinux
                           case A_OS_ROCKY_LINUX  => RockyLinux
                           case A_OS_ALMA_LINUX   => AlmaLinux
                           case A_OS_RASPBIAN     => Raspbian
                           case A_OS_TUXEDO       => Tuxedo
                           case _                 => UnknownLinuxType
                         }
                         Right(Linux(os, osFullName, osVersion, osServicePack, kernelVersion))

                       } else if (entry.isA(OC_SOLARIS_NODE)) {
                         Right(Solaris(osFullName, osVersion, osServicePack, kernelVersion))
                       } else if (entry.isA(OC_AIX_NODE)) {
                         Right(Aix(osFullName, osVersion, osServicePack, kernelVersion))
                       } else if (entry.isA(OC_BSD_NODE)) {
                         val os = osName match {
                           case A_OS_FREEBSD => FreeBSD
                           case _            => UnknownBsdType
                         }
                         Right(Bsd(os, osFullName, osVersion, osServicePack, kernelVersion))
                       } else if (entry.isA(OC_NODE)) {
                         Right(UnknownOS(osFullName, osVersion, osServicePack, kernelVersion))
                       } else Left(UnknownElement(s"Unknow OS type: '${entry.valuesFor(A_OC).mkString(", ")}'"))
    } yield {
      osDetails
    }
  }

  def nodeFromEntry(entry: LDAPEntry): IOResult[NodeInventory] = {

    for {
      dit            <- ditService.getDit(entry.dn).notOptional(s"DIT not found for entry ${entry.dn.toString}")
      inventoryStatus = ditService.getInventoryStatus(dit)
      id             <- dit.NODES.NODE.idFromDN(entry.dn).toIO
      keyStatus      <- ZIO.fromEither(entry(A_KEY_STATUS).map(KeyStatus(_)).getOrElse(Right(UndefinedKey)))
      hostname       <- entry.required(A_HOSTNAME).toIO
      rootUser       <- entry.required(A_ROOT_USER).toIO
      policyServerId <- entry.required(A_POLICY_SERVER_UUID).toIO
      agentNames     <- ZIO
                          .foreach(entry.valuesFor(A_AGENT_NAME).toList) {
                            case agent =>
                              agent.fromJson[AgentInfo].toIO.chainError(s"Error when parsing agent security token '${agent}'")
                          }
                          .foldZIO(
                            err =>
                              InventoryDataLogger.error(
                                s"Error when parsing agent information for node '${id.value}': that agent will be " +
                                s"ignored for the node, which will likely cause problem like the node being ignored: ${err.fullMsg}"
                              ) *> Nil.succeed,
                            _.distinct match {
                              case Nil      => Nil.succeed
                              case a :: Nil => List(a).succeed
                              // we must have exactly one agent
                              case several  =>
                                InventoryDataLogger.error(
                                  s"Error: inventory for node '${id.value}' has several (${several.size}) different agents defined, which is not supported. Ignoring all agents."
                                ) *> Nil.succeed
                            }
                          )

      // now, look for the OS type
      osDetails      <- mapOsDetailsFromEntry(entry).toIO
      // optional information
      name            = entry(A_NAME)
      description     = entry(A_DESCRIPTION)
      ram             = entry(A_OS_RAM).map(x => MemorySize(x))
      swap            = entry(A_OS_SWAP).map(x => MemorySize(x))
      arch            = entry(A_ARCH)

      lastLoggedUser     = entry(A_LAST_LOGGED_USER)
      lastLoggedUserTime = entry.getAsGTime(A_LAST_LOGGED_USER_TIME).map(_.instant)
      ev                <- ZIO.foldLeft(entry.valuesFor(A_EV))(List.empty[EnvironmentVariable]) {
                             case (l, json) =>
                               json.fromJson[EnvironmentVariable] match {
                                 case Left(err) =>
                                   InventoryProcessingLogger
                                     .warn(s"Error when deserializing environment variable, ignoring it: ${json} ; error: ${err}") *> l.succeed
                                 case Right(ev) => (ev :: l).succeed
                               }
                           }
      process           <- ZIO.foldLeft(entry.valuesFor(A_PROCESS))(List.empty[Process]) {
                             case (l, json) =>
                               json.fromJson[Process] match {
                                 case Left(err) =>
                                   InventoryProcessingLogger
                                     .warn(s"Error when deserializing process, ignoring it: ${json} ; error: ${err}") *> l.succeed
                                 case Right(p)  => (p :: l).succeed
                               }
                           }
      softwareIds       <- ZIO.foldLeft(entry.valuesFor(A_SOFTWARE_DN))(List.empty[SoftwareUuid]) {
                             case (l, x) =>
                               dit.SOFTWARE.SOFT.idFromDN(new DN(x)) match {
                                 case Left(err) =>
                                   InventoryProcessingLogger
                                     .warn(
                                       s"Error when deserializing software, ignoring it: ${x} ; error: ${err.msg}"
                                     ) *> l.succeed
                                 case Right(s)  => (s :: l).succeed
                               }
                           }
      machineId         <- mapSeqStringToMachineIdAndStatus(entry.valuesFor(A_CONTAINER_DN)).toList match {
                             case Nil                 => None.succeed
                             case m :: Nil            => Some(m).succeed
                             case l @ (m1 :: m2 :: _) =>
                               InventoryProcessingLogger.error(
                                 "Several machine were registered for a node. That is not supported. " +
                                 "The first in the following list will be chosen, but you may encounter strange " +
                                 "results in the future: %s".format(
                                   l.map { case (id, status) => "%s [%s]".format(id.value, status.name) }.mkString(" ; ")
                                 )
                               ) *>
                               Some(m1).succeed
                           }
      inventoryDate      = entry.getAsGTime(A_INVENTORY_DATE).map(_.instant)
      receiveDate        = entry.getAsGTime(A_RECEIVE_DATE).map(_.instant)
      accounts           = entry.valuesFor(A_ACCOUNT).toSeq
      serverIps          = entry.valuesFor(A_LIST_OF_IP).toSeq
      timezone           = (entry(A_TIMEZONE_NAME), entry(A_TIMEZONE_OFFSET)) match {
                             case (Some(tzName), Some(offset)) => Some(NodeTimezone(tzName, offset))
                             case _                            => None
                           }
      customProperties  <- ZIO.foreach(entry.valuesFor(A_CUSTOM_PROPERTY)) { a =>
                             a.fromJson[CustomProperty] match {
                               case Left(err) =>
                                 InventoryProcessingLogger.warn(
                                   Unexpected(
                                     s"Error when deserializing node inventory custom property (ignoring that property): ${a}; error: ${err}"
                                   ).fullMsg
                                 ) *> None.succeed
                               case Right(p)  => Some(p).succeed
                             }
                           }

      softwareUpdates <- InventoryMapper.getSoftwareUpdate(entry)
      main             = NodeSummary(
                           id,
                           inventoryStatus,
                           rootUser,
                           hostname,
                           osDetails,
                           NodeId(policyServerId),
                           keyStatus
                         )
    } yield {
      NodeInventory(
        main,
        name,
        description,
        ram,
        swap,
        inventoryDate,
        receiveDate,
        arch,
        lastLoggedUser,
        lastLoggedUserTime,
        agentNames,
        serverIps,
        machineId,
        softwareIds,
        accounts,
        ev,
        process,
        timezone = timezone,
        customProperties = customProperties.toList.flatten,
        softwareUpdates = softwareUpdates.toList
      )
    }
  }

  def nodeFromTree(tree: LDAPTree): IOResult[NodeInventory] = {
    for {
      node <- nodeFromEntry(tree.root)
      res  <- ZIO.foldLeft(tree.children)(node) { case (m, (rdn, t)) => mapAndAddNodeElement(t.root, m) }
    } yield {
      res
    }
  }

}
