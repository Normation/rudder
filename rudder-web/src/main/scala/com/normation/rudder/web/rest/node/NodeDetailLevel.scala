/*
*************************************************************************************
* Copyright 2013 Normation SAS
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

package com.normation.rudder.web.rest.node

import net.liftweb.json._
import net.liftweb.common._
import net.liftweb.json.JsonDSL._
import com.normation.inventory.domain._
import com.normation.rudder.web.components.DateFormaterService
import bootstrap.liftweb.RudderConfig
import com.normation.rudder.domain.nodes.Node
import com.normation.rudder.domain.nodes.JsonSerialisation._
import com.normation.rudder.domain.nodes.NodeInfo

sealed trait NodeDetailLevel {
  def fields : Set[String]

  /**
   * Build the JSON object, for the given list of fields.
   * The object is always built with the fields on the same order.
   */
  final def toJson(nodeInfo: NodeInfo, status: InventoryStatus, inventory: Option[FullInventory], software: Seq[Software]): JObject = {

    val jsonFields: List[JField] = NodeDetailLevel.allFields.filter(fields.contains).map { field =>
      //map the field to its value with the correct context
      val json =                      NodeDetailLevel.statusInfoFields.   get(field).map( _(status)).
        orElse(                       NodeDetailLevel.nodeInfoFields.     get(field).map( _(nodeInfo))).
        orElse(inventory.flatMap(i => NodeDetailLevel.fullInventoryFields.get(field).map( _(i)))).
        orElse(NodeDetailLevel.softwareFields.get(field).map( _(software))).
        getOrElse(JNothing)

      //return the pair
      JField(field, json)
    }

    //transform the seq to a json object
    JObject(jsonFields)
  }

  /**
   * Does any of the listed fields need to be looked-up
   * with full inventory ?
   */
  final def needFullInventory() = NodeDetailLevel.fullInventoryFields.keySet.intersect(fields).nonEmpty

  /**
   * Does any of the listed fields need software look-up?
   */
  final def needSoftware() = NodeDetailLevel.softwareFields.keySet.intersect(fields).nonEmpty
}

case object MinimalDetailLevel extends NodeDetailLevel {
  val fields = NodeDetailLevel.minimalFields.toSet
}

case object DefaultDetailLevel extends NodeDetailLevel {
  val fields = NodeDetailLevel.defaultFields.toSet
}

case object FullDetailLevel extends NodeDetailLevel {
  val fields = NodeDetailLevel.allFields.toSet
}

case object API2DetailLevel extends NodeDetailLevel {
  val fields = NodeDetailLevel.minimalFields.toSet ++ Set("os","machine")
}

case class CustomDetailLevel (
    base         : NodeDetailLevel
  , customFields : Set[String]
) extends NodeDetailLevel {
  val fields = base.fields ++ customFields
}

object NodeDetailLevel {

  val otherDefaultFields = List(
        "os"
      , "architectureDescription"
      , "ram"
      , "machine"
      , "ipAddresses"
      , "description"
      , "lastInventoryDate"
      , "policyServerId"
      , "managementTechnology"
      , "properties"
      , "policyMode"
  )

  val otherAllFields = List(
        "accounts"
      , "bios"
      , "controllers"
      , "environmentVariables"
      , "fileSystems"
      , "managementTechnologyDetails"
      , "memories"
      , "networkInterfaces"
      , "processes"
      , "processors"
      , "slots"
      , "software"
      , "sound"
      , "storage"
      , "ports"
      , "videos"
      , "virtualMachines"

  )

  val minimalFields = List("id", "hostname", "status")
  val defaultFields = minimalFields ::: otherDefaultFields
  val allFields = defaultFields ::: otherAllFields

  /**
   * A methods that map fields that only use nodeInfo
   */

  private val statusInfoFields: Map[String, InventoryStatus => JValue] = {
    val status: InventoryStatus => JValue = (x: InventoryStatus) => x.name

    Map (
      ( "status" -> status )
    )
  }

  private val nodeInfoFields: Map[String, NodeInfo => JValue] = {

    val id           : NodeInfo => JValue = (inv: NodeInfo) => inv.id.value
    val hostname     : NodeInfo => JValue = (inv: NodeInfo) => inv.hostname
    val description  : NodeInfo => JValue = (inv: NodeInfo) => inv.description
    val policyServer : NodeInfo => JValue = (inv: NodeInfo) => inv.policyServerId.value
    val ram          : NodeInfo => JValue = (inv: NodeInfo) => inv.ram.map(MemorySize.sizeMb)
    val arch         : NodeInfo => JValue = (inv: NodeInfo) => inv.archDescription
    val inventoryDate: NodeInfo => JValue = (inv: NodeInfo) => DateFormaterService.getFormatedDate(inv.inventoryDate)
    val properties   : NodeInfo => JValue = (inv: NodeInfo) => inv.properties.toApiJson
    val policyMode   : NodeInfo => JValue = (inv: NodeInfo) => inv.policyMode.map(_.name).getOrElse[String]("default")

    val os = {
      ( inv : NodeInfo ) =>
        val osType = inv.osDetails.os match {
          case _:LinuxType   => "Linux"
          case _:WindowsType => "Windows"
          case _:BsdType     => "BSD"
          case AixOS         => "AIX"
          case SolarisOS     => "Solaris"
          case UnknownOSType => "Unknown"
        }
        ( "type" -> osType ) ~
        ( "name" -> inv.osDetails.os.name ) ~
        ( "version"  -> inv.osDetails.version.value ) ~
        ( "fullName" -> inv.osDetails.fullName ) ~
        ( "servicePack"   -> inv.osDetails.servicePack ) ~
        ( "kernelVersion" -> inv.osDetails.kernelVersion.value )
    }

    val machine = {
      ( inv : NodeInfo ) =>
        val (machineType,provider) = inv.machine.map(_.machineType match {
          case PhysicalMachineType => ("Physical",None)
          case VirtualMachineType(kind) => ("Virtual",Some(kind))
        }).getOrElse(("No machine Inventory",None))

        ( "id"   -> inv.machine.map(_.id.value) ) ~
        ( "type" -> machineType ) ~
        ( "provider" -> provider.map(_.name) ) ~
        ( "manufacturer" -> inv.machine.flatMap( _.manufacturer.map(_.name))) ~
        ( "serialNumber" -> inv.machine.flatMap(_.systemSerial))
    }

    val ips = {
      ( inv : NodeInfo ) =>
        val ips =inv.ips.map(ip => JString(ip)).toList
        JArray(ips)
    }

    val management = {
     ( inv : NodeInfo ) =>
       val agents : List[JValue] = inv.agentsName.map{
         agent =>
           ( "name"    -> agent.fullname ) ~
           ( "version" -> JNothing )
       }.toList
       JArray(agents)
    }

    Map (
        ( "id"       -> id )
      , ( "hostname" -> hostname )
      , ( "os"  -> os )
      , ( "ram" -> ram )
      , ( "machine"     -> machine )
      , ( "ipAddresses" -> ips)
      , ( "description" -> description )
      , ( "lastInventoryDate" -> inventoryDate )
      , ( "policyServerId"    -> policyServer )
      , ( "managementTechnology"    -> management)
      , ( "architectureDescription" -> arch )
      , ( "properties" -> properties )
      , ( "policyMode" -> policyMode )
    )
  }

  private val softwareFields : Map[String, Seq[Software] => JValue] = {
    val software: Seq[Software] => JValue = {
      (soft: Seq[Software]) =>

        def licenseJson (license : License) = {
          ( "oem"            -> license.oem ) ~
          ( "name"           -> license.name ) ~
          ( "productId"      -> license.productId ) ~
          ( "productKey"     -> license.productKey ) ~
          ( "description"    -> license.description ) ~
          ( "expirationDate" -> license.expirationDate.map(DateFormaterService.getFormatedDate) )
        }

        if(soft.isEmpty) {
          JNothing
        } else {
          val softwares = soft.map{
            software =>
              ( "name"        -> software.name ) ~
              ( "editor"      -> software.editor.map(_.name) ) ~
              ( "version"     -> software.version.map(_.value) ) ~
              ( "license"     -> software.license.map(licenseJson) ) ~
              ( "description" -> software.description ) ~
              ( "releaseDate" -> software.releaseDate.map(DateFormaterService.getFormatedDate) )
          }.toList
          JArray(softwares)
        }
    }

    Map(
      ( "software" -> software )
    )

  }

  private val fullInventoryFields : Map[String, FullInventory => JValue] = {

    val env = {
      ( inv : FullInventory ) =>
        val variables = inv.node.environmentVariables.map{
          env =>
            val value = JString(env.value.getOrElse(""))
            JField(env.name ,value)
        }.toList
        JObject(variables)
    }

    val network = {
      ( inv : FullInventory ) =>
        val network = inv.node.networks.map(
          network =>
              ( "name"   -> network.name )
            ~ ( "mask"   -> network.ifMask.map(_.getHostAddress) )
            ~ ( "type"   -> network.ifType )
            ~ ( "speed"  -> network.speed )
            ~ ( "status" -> network.status )
            ~ ( "dhcpServer"  -> network.ifDhcp.map(_.getHostAddress) )
            ~ ( "macAddress"  -> network.macAddress )
            ~ ( "ipAddresses" -> JArray(network.ifAddresses.map(ip => JString(ip.getHostAddress)).toList) )
        ).toList
        JArray(network)
    }

    val managementDetails = {
      ( inv : FullInventory ) =>
        val keys = inv.node.publicKeys.map{cfKey =>  JString(cfKey.key)}
        ( "cfengineKeys" -> JArray(keys.toList) ) ~
        ( "cfengineUser" -> inv.node.main.rootUser )
    }

    val fileSystems = {
      ( inv : FullInventory ) =>
        val fs = inv.node.fileSystems.map{
          fs =>
            ( "name" -> fs.name ) ~
            ( "fileCount"   -> fs.fileCount ) ~
            ( "freeSpace"   -> fs.freeSpace.map(MemorySize.sizeMb ) ) ~
            ( "totalSpace"  -> fs.totalSpace.map(MemorySize.sizeMb) ) ~
            ( "mountPoint"  -> fs.mountPoint ) ~
            ( "description" -> fs.description )
        }.toList
        val swap =
          ("name" -> "swap" ) ~
          ("totalSpace" -> inv.node.swap.map(MemorySize.sizeMb) )
        JArray(swap :: fs)
    }

    val memories :FullInventory => JValue = {
      ( inv : FullInventory ) =>
        inv.machine.map {
          machine =>
            if (machine.memories.isEmpty) {
              JNothing
            } else {
              val mem = machine.memories.map{
                mem =>
                  ( "name"  -> mem.name ) ~
                  ( "speed" -> mem.speed ) ~
                  ( "type"  -> mem.memType ) ~
                  ( "caption"  -> mem.caption ) ~
                  ( "quantity" -> mem.quantity ) ~
                  ( "capacity" -> mem.capacity.map(MemorySize.sizeMb) )
                  ( "slotNumber"   -> mem.slotNumber ) ~
                  ( "description"  -> mem.description ) ~
                  ( "serialNumber" -> mem.serialNumber )
              }.toList
              JArray(mem)
            }
        }
    }

    val storages : FullInventory => JValue = {
      ( inv : FullInventory ) =>
        inv.machine.map {
          machine =>
            if (machine.storages.isEmpty) {
              JNothing
            } else {
              val storages = machine.storages.map{
                storage =>
                  ( "name"  -> storage.name ) ~
                  ( "type"  -> storage.sType ) ~
                  ( "size"  -> storage.size.map(MemorySize.sizeMb ) ) ~
                  ( "model" -> storage.model ) ~
                  ( "firmware" -> storage.firmware ) ~
                  ( "quantity" -> storage.quantity ) ~
                  ( "description"  -> storage.description ) ~
                  ( "manufacturer" -> storage.manufacturer.map(_.name) ) ~
                  ( "serialNumber" -> storage.serialNumber )
              }.toList
              JArray(storages)
            }
        }
    }

    val accounts = {
     ( inv : FullInventory ) =>
       val agents = inv.node.accounts.toList.map(JString)
       if (agents.isEmpty){
         JNothing
       } else {
         JArray(agents)
       }
    }

    val processors : FullInventory => JValue = {
      ( inv : FullInventory ) =>
        inv.machine.map {
          machine =>
            if (machine.processors.isEmpty) {
              JNothing
            } else {
              val processors = machine.processors.map{
                processor =>
                  ( "name"   -> processor.name ) ~
                  ( "arch"   -> processor.arch ) ~
                  ( "core"   -> processor.core ) ~
                  ( "speed"  -> processor.speed ) ~
                  ( "cpuid"  -> processor.cpuid ) ~
                  ( "model"  -> processor.model ) ~
                  ( "thread" -> processor.thread ) ~
                  ( "stepping" -> processor.stepping ) ~
                  ( "quantity" -> processor.quantity ) ~
                  ( "familyName"  -> processor.familyName ) ~
                  ( "description" -> processor.description ) ~
                  ( "manufacturer"  -> processor.manufacturer.map(_.name) ) ~
                  ( "externalClock" -> processor.externalClock )
              }.toList
              JArray(processors)
            }
        }
    }

    val ports : FullInventory => JValue = {
      ( inv : FullInventory ) =>
        inv.machine.map {
          machine =>
            if (machine.ports.isEmpty) {
              JNothing
            } else {
              val ports = machine.ports.map{
                port =>
                  ( "name" -> port.name ) ~
                  ( "type" -> port.pType ) ~
                  ( "quantity"    -> port.quantity ) ~
                  ( "description" -> port.description )
              }.toList
              JArray(ports)
            }
        }
    }

    val virtualMachines : FullInventory => JValue = {
      ( inv : FullInventory ) =>
        if (inv.node.vms.isEmpty) {
          JNothing
        } else {
          val virtualMachines = inv.node.vms.map{
            virtualMachine =>
              ( "name" -> virtualMachine.name ) ~
              ( "type" -> virtualMachine.vmtype ) ~
              ( "uuid" -> virtualMachine.uuid.value ) ~
              ( "vcpu" -> virtualMachine.vcpu ) ~
              ( "owner"  -> virtualMachine.owner ) ~
              ( "status" -> virtualMachine.status ) ~
              ( "memory" -> virtualMachine.memory ) ~
              ( "subsystem"   -> virtualMachine.subsystem ) ~
              ( "description" -> virtualMachine.description )
          }.toList
          JArray(virtualMachines)
        }
    }

    val videos : FullInventory => JValue = {
      ( inv : FullInventory ) =>
        inv.machine.map {
          machine =>
            if (machine.videos.isEmpty) {
              JNothing
            } else {
              val videos = machine.videos.map{
                video =>
                  ( "name" -> video.name ) ~
                  ( "memory"   -> video.memory.map(MemorySize.sizeMb) ) ~
                  ( "chipset"  -> video.chipset ) ~
                  ( "quantity" -> video.quantity ) ~
                  ( "resolution"  -> video.resolution ) ~
                  ( "description" -> video.description )
              }.toList
              JArray(videos)
            }
        }
    }

    val bios : FullInventory => JValue = {
      ( inv : FullInventory ) =>
        inv.machine.map {
          machine =>
            if (machine.bios.isEmpty) {
              JNothing
            } else {
              val bios = machine.bios.map{
                bio =>
                  ( "name"   -> bio.name ) ~
                  ( "editor" -> bio.editor.map(_.name) ) ~
                  ( "version"  -> bio.version.map(_.value) ) ~
                  ( "quantity" -> bio.quantity ) ~
                  ( "description" -> bio.description ) ~
                  ( "releaseDate" -> bio.releaseDate.map(DateFormaterService.getFormatedDate) )
              }.toList
              JArray(bios)
            }

        }
    }

    val controllers : FullInventory => JValue = {
      ( inv : FullInventory ) =>
        inv.machine.map {
          machine =>
            if (machine.controllers.isEmpty) {
              JNothing
            } else {
              val controllers = machine.controllers.map{
                controller =>
                  ( "name" -> controller.name ) ~
                  ( "type" -> controller.cType ) ~
                  ( "quantity" -> controller.quantity ) ~
                  ( "description"  -> controller.description ) ~
                  ( "manufacturer" -> controller.manufacturer.map(_.name) )
              }.toList
              JArray(controllers)
            }
        }
    }

    val slots : FullInventory => JValue = {
      ( inv : FullInventory ) =>
        inv.machine.map {
          machine =>
            if (machine.slots.isEmpty) {
              JNothing
            } else {
              val slots = machine.slots.map{
                slot =>
                  ( "name" -> slot.name ) ~
                  ( "status"   -> slot.status ) ~
                  ( "quantity" -> slot.quantity ) ~
                  ( "description" -> slot.description )
              }.toList
              JArray(slots)
            }
        }
    }

    val sounds : FullInventory => JValue = {
      ( inv : FullInventory ) =>
        inv.machine.map {
          machine =>
            if (machine.sounds.isEmpty) {
              JNothing
            } else {
              val sounds = machine.sounds.map{
                sound =>
                  ( "name" -> sound.name ) ~
                  ( "quantity" -> sound.quantity ) ~
                  ( "description" -> sound.description )
              }.toList
              JArray(sounds)
            }
        }
    }

    val processes : FullInventory => JValue = {
      ( inv : FullInventory ) =>
        if (inv.node.processes.isEmpty) {
          JNothing
        } else {
          val processes = inv.node.processes.map{
            process =>
              ( "pid"  -> process.pid ) ~
              ( "tty"  -> process.tty ) ~
              ( "name" -> process.commandName ) ~
              ( "user" -> process.user ) ~
              ( "started"  -> process.started ) ~
              ( "memory"   -> process.memory ) ~
              ( "cpuUsage" -> process.cpuUsage ) ~
              ( "virtualMemory" -> process.virtualMemory ) ~
              ( "description"   -> process.description )
          }.toList
          JArray(processes)
        }
    }

    Map (
        ( "bios"  -> bios )
      , ( "slots" -> slots )
      , ( "ports" -> ports )
      , ( "sound" -> sounds )
      , ( "videos"   -> videos )
      , ( "storage"  -> storages )
      , ( "accounts" -> accounts )
      , ( "memories" -> memories )
      , ( "processes"   -> processes )
      , ( "processors"  -> processors )
      , ( "fileSystems" -> fileSystems )
      , ( "controllers" -> controllers )
      , ( "virtualMachines"   -> virtualMachines )
      , ( "networkInterfaces" -> network )
      , ( "environmentVariables" -> env )
      , ( "managementTechnologyDetails" -> managementDetails )
    )

  }

}
