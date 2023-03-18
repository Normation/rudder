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

import com.normation.ldap.sdk.schema.LDAPSchema

object LDAPConstants {

  /*
   * Define some attribute names commonly used in our applications
   */
  val A_OC                 = "objectClass"
  val A_UUID               = "uuid"
  val A_NODE_UUID          = "nodeId"
  val A_MACHINE_UUID       = "machineId"
  val A_MB_UUID            = "motherBoardUuid"
  val A_SOFTWARE_UUID      = "softwareId"
  val A_POLICY_SERVER_UUID = "policyServerId"
  val A_NAME               = "cn"
  val A_HOSTNAME           = "nodeHostname"
  val A_ROOT_USER          = "localAdministratorAccountName"
  val A_DEF                = "definition"
  val A_PKEYS              = "publicKey"
  val A_AGENTS_NAME        = "agentName"
  val A_DESCRIPTION        = "description"
  val A_REV_ID             = "revision"
  val A_MODEL              = "model"
  val A_FIRMWARE           = "firmware"
  val A_SERIAL_NUMBER      = "componentSerialNumber"
  val A_SME_TYPE           = "smeType"
  val A_STATUS             = "status"
  val A_QUANTITY           = "quantity"
  val A_MANUFACTURER       = "manufacturer"
  val A_MANUFACTURER_DATE  = "manufacturerDate"
  val A_NODE_DN            = "node"
  val A_MACHINE_DN         = "machine"
  val A_CONTAINER_DN       = "container"
  val A_SOFTWARE_DN        = "software"
  val A_OS_RAM             = "ram"
  val A_OS_SWAP            = "swap"
  val A_ACCOUNT            = "localAccountName"
  val A_INVENTORY_DATE     = "inventoryDate"
  val A_RECEIVE_DATE       = "receiveDate"
  val A_EV                 = "environmentVariable"
  val A_PROCESS            = "process"
  val A_KEY_STATUS         = "keyStatus"
  val A_TIMEZONE_NAME      = "timezoneName"
  val A_TIMEZONE_OFFSET    = "timezoneOffset"
  val A_CUSTOM_PROPERTY    = "customProperty"
  val A_SOFTWARE_UPDATE    = "softwareUpdate"

  val A_LIST_OF_IP            = "ipHostNumber"
  val A_ARCH                  = "osArchitectureType"
  val A_LAST_LOGGED_USER      = "lastLoggedUser"
  val A_LAST_LOGGED_USER_TIME = "lastLoggedUserTime"
  // OS identification
//  val A_WIN_FULL_VERSION ="windowsFullVersion"
//  val A_LINUX_KERNEL_VERSION = "linuxKernelVersion"
//  val A_LINUX_DISTRIB = "linuxDistribution"
//  val A_LINUX_DISTRIB_VERSION = "linuxDistributionVersion"
//  val A_WIN_VERSION ="windowsVersion"
  val A_OS_NAME               = "osName"
  val A_OS_FULL_NAME          = "osFullName"
  val A_OS_VERSION            = "osVersion"
  val A_OS_SERVICE_PACK       = "osServicePack"
  val A_OS_KERNEL_VERSION     = "osKernelVersion"

  // Windows
  val A_WIN_USER_DOMAIN = "windowsUserDomain"
  val A_WIN_COMPANY     = "windowsRegistrationCompany"
  val A_WIN_KEY         = "windowsKey"
  val A_WIN_ID          = "windowsId"
  // Linux

  // bios
  val A_BIOS_NAME              = "biosName"
  // Storage
  val A_STORAGE_NAME           = "storageName"
  val A_STORAGE_SIZE           = "storageSize"
  val A_STORAGE_FIRMWARE       = "firmware"
  // Network
  val A_NETWORK_NAME           = "networkInterface"
  val A_SPEED                  = "speed"
  // filesyste
  val A_MOUNT_POINT            = "mountPoint"
  val A_FILE_COUNT             = "fileCount"
  val A_FREE_SPACE             = "fileSystemFreeSpace"
  val A_TOTAL_SPACE            = "fileSystemTotalSpace"
  // processor
  val A_PROCESSOR_NAME         = "cpuName"
  val A_PROCESSOR_SPEED        = "cpuSpeed"
  val A_PROCESSOR_STEPPING     = "cpuStepping"
  val A_PROCESSOR_FAMILLY      = "cpuFamily"
  val A_CORE                   = "cpuCore"
  val A_THREAD                 = "cpuThread"
  val A_PROCESSOR_FAMILY_NAME  = "cpuFamilyName"
  val A_PROCESSOR_ARCHITECTURE = "cpuArchitecture"
  val A_CPUID                  = "cpuID"
  val A_EXTERNAL_CLOCK         = "cpuExternalClock"
  // memory
  val A_MEMORY_SLOT_NUMBER     = "memorySlotNumber"
  val A_MEMORY_CAPACITY        = "memoryCapacity"
  val A_MEMORY_CAPTION         = "memoryCaption"
  val A_MEMORY_SPEED           = "memorySpeed"
  val A_MEMORY_TYPE            = "memoryType"
  // software
  val A_RELEASE_DATE           = "releaseDate"
  val A_SOURCE_NAME            = "sourceName"
  val A_SOURCE_VERSION         = "sourceVersion"
  val A_EDITOR                 = "editor"
  val A_SOFT_VERSION           = "softwareVersion"
  val A_LICENSE_EXP            = "licenseExpirationDate"
  val A_LICENSE_NAME           = "licenseName"
  val A_LICENSE_OEM            = "licenseIsOem"
  val A_LICENSE_DESC           = "licenseDescription"
  val A_LICENSE_PRODUCT_ID     = "licenseProductId"
  val A_LICENSE_PRODUCT_KEY    = "licenseProductKey"
  // network interface
  val A_NETIF_ADDRESS          = "ipHostNumber"
  val A_NETIF_DHCP             = "networkInterfaceDhcpServer"
  val A_NETIF_GATEWAY          = "networkInterfaceGateway"
  val A_NETIF_MASK             = "networkInterfaceMask"
  val A_NETIF_SUBNET           = "networkSubnet" // use to be "ipNetworkNumber"
  val A_NETIF_MAC              = "networkInterfaceMacAddress"
  val A_NETIF_TYPE             = "networkInterfaceType"
  val A_NETIF_TYPE_MIB         = "networkInterfaceTypeMib"
  // controller
  val A_CONTROLLER_NAME        = "controllerName"
  // port
  val A_PORT_NAME              = "portName"
  // slot
  val A_SLOT_NAME              = "slotName"
  // sound card
  val A_SOUND_NAME             = "soundCardName"
  // video
  val A_VIDEO_NAME             = "videoCardName"
  val A_VIDEO_CHIPSET          = "videoChipset"
  val A_VIDEO_RESOLUTION       = "videoResolution"
  // virtual machine
  val A_VM_ID                  = "virtualMachineUuid"
  val A_VM_TYPE                = "vmType"
  val A_VM_SUBSYSTEM           = "subsystem"
  val A_VM_OWNER               = "vmOwner"
  val A_VM_NAME                = "vmName"
  val A_VM_STATUS              = "vmStatus"
  val A_VM_CPU                 = "vmCpu"
  val A_VM_MEMORY              = "vmMemory"

  val A_MEMBER     = "member"
  val A_MEMBER_URL = "memberUrl"

  // os "distribution" or "version"
  val A_OS_UNKNOWN_WINDOWS = "Unknown Windows version"
  val A_OS_WIN_XP          = "WindowsXp"
  val A_OS_WIN_VISTA       = "WindowsVista"
  val A_OS_WIN_SEVEN       = "WindowsSeven"
  val A_OS_WIN_10          = "Windows10"
  val A_OS_WIN_2000        = "Windows2000"
  val A_OS_WIN_2003        = "Windows2003"
  val A_OS_WIN_2008        = "Windows2008"
  val A_OS_WIN_2008_R2     = "Windows2008R2"
  val A_OS_WIN_2012        = "Windows2012"
  val A_OS_WIN_2012_R2     = "Windows2012R2"
  val A_OS_WIN_2016        = "Windows2016"
  val A_OS_WIN_2016_R2     = "Windows2016R2"
  val A_OS_WIN_2019        = "Windows2019"
  val A_OS_WIN_2022        = "Windows2022"
  val A_OS_UNKNOWN_LINUX   = "Unknown Linux version"
  val A_OS_DEBIAN          = "Debian"
  val A_OS_KALI            = "Kali"
  val A_OS_UBUNTU          = "Ubuntu"
  val A_OS_REDHAT          = "Redhat"
  val A_OS_CENTOS          = "Centos"
  val A_OS_FEDORA          = "Fedora"
  val A_OS_SUZE            = "Suse"
  val A_OS_ANDROID         = "Android"
  val A_OS_UNKNOWN_SOLARIS = "Unknown Solaris version"
  val A_OS_SOLARIS         = "Solaris"
  val A_OS_AIX             = "AIX"
  val A_OS_FREEBSD         = "FreeBSD"
  val A_OS_UNKNOWN_BSD     = "Unknown BSD flavor"
  val A_OS_ORACLE          = "Oracle"
  val A_OS_SCIENTIFIC      = "Scientific"
  val A_OS_SLACKWARE       = "Slackware"
  val A_OS_MINT            = "Mint"
  val A_OS_AMAZON_LINUX    = "AmazonLinux"
  val A_OS_ROCKY_LINUX     = "RockyLinux"
  val A_OS_ALMA_LINUX      = "AlmaLinux"
  val A_OS_RASPBIAN        = "Raspbian"

  /*
   * A bunch of name, just to be sur to use variable
   * and not string all around our code
   */
  val OC_TOP          = "top"
  val OC_DEVICE       = "device"
  val OC_PE           = "physicalElement"
  val OC_LE           = "logicalElement"
  val OC_SOFTWARE     = "software"
  val OC_NODE         = "node"
  val OC_WINDOWS_NODE = "windowsNode"
  val OC_UNIX_NODE    = "unixNode"
  val OC_LINUX_NODE   = "linuxNode"
  val OC_SOLARIS_NODE = "solarisNode"
  val OC_AIX_NODE     = "aixNode"
  val OC_BSD_NODE     = "bsdNode"
  val OC_MACHINE      = "machine"
  val OC_PM           = "physicalMachine"
  val OC_VM           = "virtualMachine"
  val OC_FS           = "fileSystemLogicalElement"
  val OC_NET_IF       = "networkInterfaceLogicalElement"
  val OC_VM_INFO      = "virtualMachineLogicalElement"
  val OC_MEMORY       = "memoryPhysicalElement"
  val OC_STORAGE      = "storagePhysicalElement"
  val OC_BIOS         = "biosPhysicalElement"
  val OC_CONTROLLER   = "controllerPhysicalElement"
  val OC_PORT         = "portPhysicalElement"
  val OC_PROCESSOR    = "processorPhysicalElement"
  val OC_SLOT         = "slotPhysicalElement"
  val OC_SOUND        = "soundCardPhysicalElement"
  val OC_VIDEO        = "videoCardPhysicalElement"
  val OC_OU           = "organizationalUnit"
  val OC_DYN_GROUP    = "dynGroup"

  // vm type
  val OC_VM_VIRTUALBOX   = "virtualBox"
  val OC_VM_XEN          = "xen"
  val OC_VM_VMWARE       = "vmWare"
  val OC_VM_SOLARIS_ZONE = "solarisZone"
  val OC_VM_QEMU         = "qemu"
  val OC_VM_AIX_LPAR     = "aixLpar"
  val OC_VM_HYPERV       = "hyperV"
  val OC_VM_BSDJAIL      = "bsdJail"
  val OC_VM_LXC          = "lxc"
  val OC_VM_VIRTUOZZO    = "virtuozzo"
  val OC_VM_OPENVZ       = "openvz"

  implicit val OC: LDAPSchema = new LDAPSchema()

  /**
   * Machine types
   */
  OC.createObjectClass(
    OC_DEVICE,
    sup = OC(OC_TOP),
    must = Set(A_NAME),
    may = Set(A_DESCRIPTION, A_SERIAL_NUMBER, "seeAlso", "owner", "ou", "o", "l")
  )
  OC.createObjectClass(
    OC_MACHINE,
    sup = OC(OC_DEVICE),
    must = Set(A_MACHINE_UUID),
    may = Set(A_MB_UUID, A_MANUFACTURER, A_SERIAL_NUMBER)
  )

  val OC_OC_PM              = OC.createObjectClass(OC_PM)
  val OC_OC_VM              = OC.createObjectClass(OC_VM)
  val OC_OC_VM_VIRTUALBOX   = OC.createObjectClass(OC_VM_VIRTUALBOX, OC(OC_VM))
  val OC_OC_VM_XEN          = OC.createObjectClass(OC_VM_XEN, OC(OC_VM))
  val OC_OC_VM_VMWARE       = OC.createObjectClass(OC_VM_VMWARE, OC(OC_VM))
  val OC_OC_VM_SOLARIS_ZONE = OC.createObjectClass(OC_VM_SOLARIS_ZONE, OC(OC_VM))
  val OC_OC_VM_QEMU         = OC.createObjectClass(OC_VM_QEMU, OC(OC_VM))
  val OC_OC_VM_AIX_LPAR     = OC.createObjectClass(OC_VM_AIX_LPAR, OC(OC_VM))
  val OC_OC_VM_HYPERV       = OC.createObjectClass(OC_VM_HYPERV, OC(OC_VM))
  val OC_OC_VM_BSDJAIL      = OC.createObjectClass(OC_VM_BSDJAIL, OC(OC_VM))
  val OC_OC_VM_LXC          = OC.createObjectClass(OC_VM_LXC, OC(OC_VM))
  val OC_OC_VM_VIRTUOZZO    = OC.createObjectClass(OC_VM_VIRTUOZZO, OC(OC_VM))
  val OC_OC_VM_OPENVZ       = OC.createObjectClass(OC_VM_OPENVZ, OC(OC_VM))

  OC.createObjectClass(
    OC_PE,
    must = Set(),
    may = Set(A_NAME, A_DESCRIPTION, A_MODEL, A_SERIAL_NUMBER, A_FIRMWARE, A_QUANTITY, A_SME_TYPE, A_STATUS, A_MANUFACTURER)
  )

  val machineTypesNames = Set(
    OC_MACHINE,
    OC_PM,
    OC_VM,
    OC_VM_VIRTUALBOX,
    OC_VM_XEN,
    OC_VM_VMWARE,
    OC_VM_SOLARIS_ZONE,
    OC_VM_QEMU,
    OC_VM_AIX_LPAR,
    OC_VM_HYPERV,
    OC_VM_BSDJAIL,
    OC_VM_LXC,
    OC_VM_VIRTUOZZO,
    OC_VM_OPENVZ
  )

  OC.createObjectClass(
    OC_MEMORY,
    sup = OC(OC_PE),
    must = Set(A_MEMORY_SLOT_NUMBER),
    may = Set(A_MEMORY_CAPACITY, A_MEMORY_CAPTION, A_MEMORY_SPEED, A_MEMORY_TYPE)
  )
  OC.createObjectClass(OC_STORAGE, sup = OC(OC_PE), must = Set(A_STORAGE_NAME), may = Set(A_STORAGE_SIZE, A_STORAGE_FIRMWARE))
  OC.createObjectClass(
    OC_BIOS,
    OC(OC_PE),
    must = Set(A_BIOS_NAME),
    may = Set(
      A_SOFT_VERSION,
      A_EDITOR,
      A_RELEASE_DATE,
      A_LICENSE_EXP,
      A_LICENSE_NAME,
      A_LICENSE_OEM,
      A_LICENSE_DESC,
      A_LICENSE_PRODUCT_ID,
      A_LICENSE_PRODUCT_KEY,
      A_SOURCE_VERSION,
      A_SOURCE_NAME
    )
  )
  OC.createObjectClass(OC_CONTROLLER, OC(OC_PE), must = Set(A_CONTROLLER_NAME))
  OC.createObjectClass(OC_PORT, OC(OC_PE), must = Set(A_PORT_NAME))
  OC.createObjectClass(
    OC_PROCESSOR,
    OC(OC_PE),
    must = Set(A_PROCESSOR_NAME),
    may = Set(A_PROCESSOR_SPEED, A_PROCESSOR_STEPPING, A_PROCESSOR_FAMILLY)
  )
  OC.createObjectClass(OC_SLOT, OC(OC_PE), must = Set(A_SLOT_NAME))
  OC.createObjectClass(OC_SOUND, OC(OC_PE), must = Set(A_SOUND_NAME))
  OC.createObjectClass(
    OC_VIDEO,
    OC(OC_PE),
    must = Set(A_VIDEO_NAME),
    may = Set(A_VIDEO_CHIPSET, A_MEMORY_CAPACITY, A_VIDEO_RESOLUTION)
  )

  OC.createObjectClass(
    OC_SOFTWARE,
    must = Set(A_SOFTWARE_UUID),
    may = Set(
      A_NAME,
      A_SOFT_VERSION,
      A_DESCRIPTION,
      A_EDITOR,
      A_RELEASE_DATE,
      A_LICENSE_EXP,
      A_LICENSE_NAME,
      A_LICENSE_OEM,
      A_LICENSE_DESC,
      A_LICENSE_PRODUCT_ID,
      A_LICENSE_PRODUCT_KEY
    )
  )

  /**
   * Node type (with OS)
   */
  OC.createObjectClass(
    OC_NODE,
    sup = OC(OC_TOP),
    must = Set(A_NODE_UUID, A_OS_NAME, A_OS_FULL_NAME, A_OS_VERSION, A_OS_KERNEL_VERSION),
    may = Set(
      A_NAME,
      A_DESCRIPTION,
      A_PKEYS,
      A_AGENTS_NAME,
      A_CONTAINER_DN,
      A_SOFTWARE_DN,
      A_ACCOUNT,
      A_EV,
      A_PROCESS,
      A_ROOT_USER,
      A_ARCH,
      A_LAST_LOGGED_USER,
      A_LAST_LOGGED_USER_TIME,
      A_HOSTNAME,
      A_OS_RAM,
      A_OS_SWAP,
      A_LIST_OF_IP,
      A_OS_SERVICE_PACK,
      A_KEY_STATUS,
      A_TIMEZONE_NAME,
      A_TIMEZONE_OFFSET,
      A_CUSTOM_PROPERTY
    )
  )

  OC.createObjectClass(OC_WINDOWS_NODE, sup = OC(OC_NODE), may = Set(A_WIN_USER_DOMAIN, A_WIN_COMPANY, A_WIN_KEY, A_WIN_ID))

  OC.createObjectClass(OC_LE, must = Set(), may = Set(A_NAME, A_DESCRIPTION))
  OC.createObjectClass(OC_FS, sup = OC(OC_LE), must = Set(A_MOUNT_POINT), may = Set(A_FILE_COUNT, A_FREE_SPACE, A_TOTAL_SPACE))
  OC.createObjectClass(
    OC_NET_IF,
    sup = OC(OC_LE),
    must = Set(A_NETWORK_NAME),
    may = Set(
      A_SPEED,
      A_NETIF_ADDRESS,
      A_NETIF_DHCP,
      A_NETIF_GATEWAY,
      A_NETIF_MASK,
      A_NETIF_SUBNET,
      A_NETIF_MAC,
      A_NETIF_TYPE,
      A_NETIF_TYPE_MIB
    )
  )
  OC.createObjectClass(
    OC_VM_INFO,
    sup = OC(OC_LE),
    must = Set(A_VM_ID),
    may = Set(A_VM_CPU, A_VM_MEMORY, A_VM_NAME, A_VM_OWNER, A_VM_STATUS, A_VM_SUBSYSTEM, A_VM_TYPE)
  )

  OC.createObjectClass(OC_UNIX_NODE, sup = OC(OC_NODE))
  OC.createObjectClass(OC_LINUX_NODE, sup = OC(OC_UNIX_NODE))
  OC.createObjectClass(OC_SOLARIS_NODE, sup = OC(OC_UNIX_NODE))
  OC.createObjectClass(OC_AIX_NODE, sup = OC(OC_UNIX_NODE))
  OC.createObjectClass(OC_BSD_NODE, sup = OC(OC_UNIX_NODE))

  OC.createObjectClass(
    OC_OU,
    must = Set("ou"),
    may = Set(
      "userPassword",
      "searchGuide",
      "seeAlso",
      "businessCategory",
      "x121Address",
      "registeredAddress",
      "destinationIndicator",
      "preferredDeliveryMethod",
      "telexNumber",
      "teletexTerminalIdentifier",
      "telephoneNumber",
      "internationaliSDNNumber",
      "facsimileTelephoneNumber",
      "street",
      "postOfficeBox",
      "postalCode",
      "postalAddress",
      "physicalDeliveryOfficeName",
      "st",
      "l",
      "description"
    )
  )

  OC.createObjectClass(OC_DYN_GROUP, must = Set(A_MEMBER_URL), may = Set(A_DESCRIPTION))

  // User defined properties : the regexp that the data should abide by
  val userDefinedPropertyRegex = """\{([^\}]+)\}(.+)""".r

  // variable bindings variable regexp : VariableName:FieldName
  val variableBindingRegex = """(.+):(.+)""".r

}
