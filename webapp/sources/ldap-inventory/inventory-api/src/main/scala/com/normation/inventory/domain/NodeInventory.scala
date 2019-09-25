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

package com.normation.inventory.domain

import java.net.InetAddress

import org.joda.time.DateTime
import net.liftweb.common._
import net.liftweb.json.JsonAST.JValue

sealed trait NodeElement {
  def description : Option[String]
}

final case class FileSystem(
    mountPoint  : String
  , name        : Option[String] = None
  , description : Option[String] = None
  , fileCount   : Option[Int] = None
  , freeSpace   : Option[MemorySize] = None
  , totalSpace  : Option[MemorySize] = None
) extends NodeElement

/**
 * For network, we *should* have a group for all layer 3 information that
 * are linked together and may be multivalued:
 * - ifAddresses
 * - ifMask
 * - ifSubnet
 * - ifGateway
 *
 * So we should track that linked. But as a first correction,
 * it's better to have correct information that not have them,
 * even if they are a little mixed-up.
 */
final case class Network (
    name        : String
  , description : Option[String] = None
  , ifAddresses : Seq[InetAddress] = Seq()
  , ifDhcp      : Option[InetAddress] = None
  , ifGateway   : Seq[InetAddress] = Seq()
  , ifMask      : Seq[InetAddress] = Seq()
  , ifSubnet    : Seq[InetAddress] = Seq()
  , macAddress  : Option[String] = None
  , status      : Option[String] = None
  , ifType      : Option[String] = None
  , speed       : Option[String] = None
  , typeMib     : Option[String] = None
) extends NodeElement {
  def ifAddress : Option[InetAddress] = ifAddresses.headOption
}

final case class Process (
    pid           : Int
  , commandName   : Option[String]
  , cpuUsage      : Option[Float]    = None
  , memory        : Option[Float]    = None
    //we use a string for the date, as it's more
    //important to have something to show to the user than to
    //to be normalized for that field.
  , started       : Option[String]   = None
  , tty           : Option[String]   = None
  , user          : Option[String]   = None
  , virtualMemory : Option[Double]   = None
  , description   : Option[String]   = None
)extends NodeElement

final case class VirtualMachine (
    vmtype      : Option[String] = None
  , subsystem   : Option[String] = None
  , owner       : Option[String] = None
  , name        : Option[String] = None
  , status      : Option[String] = None
  , vcpu        : Option[Int]    = None
  , memory      : Option[String] = None
  , uuid        : MachineUuid
    // TODO : Maybe add an inventoryStatus field
  , description : Option[String] = None
) extends NodeElement

final case class EnvironmentVariable (
    name        : String
  , value       : Option[String] = None
  , description : Option[String] = None
) extends NodeElement

object InetAddressUtils {

  def getAddressByName(a:String) : Option[InetAddress] =
    try {
      Some(InetAddress.getByName(a))
    } catch {
      case e:java.net.UnknownHostException => None
    }
}

sealed trait OsType {
  def kernelName : String
  def name       : String //name is normalized and not destined to be printed - use localization for that
  override def toString = kernelName
}

object UnknownOSType extends OsType {
  val kernelName = "N/A"
  val name = "N/A"
}

sealed abstract class WindowsType extends OsType {
  override val kernelName = "Windows"
}
object WindowsType {
  val allKnownTypes = (
       WindowsXP
    :: WindowsVista
    :: WindowsSeven
    :: Windows10
    :: Windows2000
    :: Windows2003
    :: Windows2008
    :: Windows2008R2
    :: Windows2012
    :: Windows2012R2
    :: Windows2016
    :: Windows2016R2
    :: Windows2019
    :: Nil
  )
}

final case object UnknownWindowsType extends WindowsType                 { val name = "Windows"       }
final case object WindowsXP     extends WindowsType { val name = "WindowsXP"     }
final case object WindowsVista  extends WindowsType { val name = "WindowsVista"  }
final case object WindowsSeven  extends WindowsType { val name = "WindowsSeven"  }
final case object Windows10     extends WindowsType { val name = "Windows10"  }
final case object Windows2000   extends WindowsType { val name = "Windows2000"   }
final case object Windows2003   extends WindowsType { val name = "Windows2003"   }
final case object Windows2008   extends WindowsType { val name = "Windows2008"   }
final case object Windows2008R2 extends WindowsType { val name = "Windows2008R2" }
final case object Windows2012   extends WindowsType { val name = "Windows2012"   }
final case object Windows2012R2 extends WindowsType { val name = "Windows2012R2" }
final case object Windows2016   extends WindowsType { val name = "Windows2016"   }
final case object Windows2016R2 extends WindowsType { val name = "Windows2016R2" }
final case object Windows2019   extends WindowsType { val name = "Windows2019" }

/**
 * Specific Linux subtype (distribution)
 */
sealed abstract class LinuxType extends OsType {
  override val kernelName = "Linux"
}
object LinuxType {
  val allKnownTypes = (
       Debian
    :: Ubuntu
    :: Redhat
    :: Centos
    :: Fedora
    :: Suse
    :: Android
    :: UnknownLinuxType
    :: Oracle
    :: Scientific
    :: Slackware
    :: Mint
    :: Nil
  )
}

final case object UnknownLinuxType extends LinuxType { val name = "UnknownLinux" }
final case object Debian           extends LinuxType { val name = "Debian"       }
final case object Ubuntu           extends LinuxType { val name = "Ubuntu"       }
final case object Redhat           extends LinuxType { val name = "Redhat"       }
final case object Centos           extends LinuxType { val name = "Centos"       }
final case object Fedora           extends LinuxType { val name = "Fedora"       }
final case object Suse             extends LinuxType { val name = "Suse"         }
final case object Android          extends LinuxType { val name = "Android"      }
final case object Oracle           extends LinuxType { val name = "Oracle"       }
final case object Scientific       extends LinuxType { val name = "Scientific"   }
final case object Slackware        extends LinuxType { val name = "Slackware"    }
final case object Mint             extends LinuxType { val name = "Mint"    }

//solaris has only one flavour for now
//to be updated in the future with OSS verison
object SolarisOS extends OsType {
  val kernelName = "Solaris"
  val name = "Solaris"
}

//AIX has only one flavour
object AixOS extends OsType {
  val kernelName = "AIX"
  val name = "AIX"
}

// The BSD familly - should I make it a subclass of LinuxType? Or Ubuntu ?
sealed abstract class BsdType extends OsType {
  override val kernelName = "BSD"
}

object BsdType {
  val allKnownTypes = (
       FreeBSD
    :: UnknownBsdType
    :: Nil
  )
}

final case object UnknownBsdType extends BsdType { val name = "UnknownBSD" }
final case object FreeBSD        extends BsdType { val name = "FreeBSD"  }

/**
 * The different OS type. For now, we know:
 * - Linux
 * - Solaris
 * - AIX
 * - BSD
 * - Windows.
 * And a joker
 * - Unknown
 */
sealed abstract class OsDetails(
    val os            : OsType         // give both type (Linux, Windows, etc) and name ("SuSE", "Windows", etc)
  , val fullName      : String         //"SUSE Linux Enterprise Server 11 (x86_64)"
  , val version       : Version        // "5.08", "11.04", "N/A" for windows
  , val servicePack   : Option[String] // a service pack
  , val kernelVersion : Version        // "2.6.32.12-0.7-default", "N/A" for windows
)

final case class UnknownOS(
    override val fullName      : String = "N/A"
  , override val version       : Version = new Version("N/A")
  , override val servicePack   : Option[String]  = None
  , override val kernelVersion : Version = new Version("N/A")
) extends OsDetails(UnknownOSType, fullName, version, servicePack, kernelVersion)

final case class Linux(
    override val os            : OsType
  , override val fullName      : String
  , override val version       : Version
  , override val servicePack   : Option[String]
  , override val kernelVersion : Version
) extends OsDetails(os, fullName, version, servicePack, kernelVersion)

final case class Solaris(
    override val fullName      : String
  , override val version       : Version
  , override val servicePack   : Option[String]
  , override val kernelVersion : Version
) extends OsDetails(SolarisOS, fullName, version, servicePack, kernelVersion)

final case class Bsd(
    override val os            : OsType
  , override val fullName      : String
  , override val version       : Version
  , override val servicePack   : Option[String]
  , override val kernelVersion : Version
) extends OsDetails(os, fullName, version, servicePack, kernelVersion)

final case class Aix(
    override val fullName      : String
  , override val version       : Version
  , override val servicePack   : Option[String]
  , override val kernelVersion : Version
) extends OsDetails(AixOS, fullName, version, servicePack, kernelVersion)

final case class Windows(
    override val os            : OsType
  , override val fullName      : String
  , override val version       : Version
  , override val servicePack   : Option[String]
  , override val kernelVersion : Version
  , userDomain                 : Option[String] = None
  , registrationCompany        : Option[String] = None
  , productKey                 : Option[String] = None
  , productId                  : Option[String] = None
) extends OsDetails(os, fullName, version, servicePack, kernelVersion)

final case class NodeSummary(
    id : NodeId
  , status:InventoryStatus
  , rootUser : String
  , hostname : String
  , osDetails : OsDetails
  , policyServerId : NodeId
  , keyStatus : KeyStatus
  //agent name
  //ipss
)

sealed trait KeyStatus {
  val value : String
}
final case object CertifiedKey extends KeyStatus {
  val value = "certified"
}
final case object UndefinedKey extends KeyStatus {
  val value = "undefined"
}

final case class NodeTimezone(
    name  : String
  , offset: String
)

final case class CustomProperty(
     name : String
   , value: JValue
)

object KeyStatus {
  def apply(value : String) : Box[KeyStatus] = {
    value match {
      case CertifiedKey.value => Full(CertifiedKey)
      case UndefinedKey.value => Full(UndefinedKey)
      case _ => Failure(s"${value} is not a valid key status")
    }
  }
}

final case class ServerRole(value: String) extends AnyVal

final case class NodeInventory(
    main                 : NodeSummary
  , name                 : Option[String]     = None
  , description          : Option[String]     = None
  , ram                  : Option[MemorySize] = None
  , swap                 : Option[MemorySize] = None
  , inventoryDate        : Option[DateTime]   = None
  , receiveDate          : Option[DateTime]   = None
  , archDescription      : Option[String]     = None
  , lastLoggedUser       : Option[String]     = None
  , lastLoggedUserTime   : Option[DateTime]   = None
  , agents               : Seq[AgentInfo]     = Seq()
  , serverIps            : Seq[String]        = Seq()
  , machineId            : Option[(MachineUuid,InventoryStatus)] = None //if we want several ids, we would have to ass an "alternate machine" field
  , softwareIds          : Seq[SoftwareUuid]        = Seq()
  , accounts             : Seq[String]              = Seq()
  , environmentVariables : Seq[EnvironmentVariable] = Seq()
  , processes            : Seq[Process]             = Seq()
  , vms                  : Seq[VirtualMachine]      = Seq()
  , networks             : Seq[Network]             = Seq()
  , fileSystems          : Seq[FileSystem]          = Seq()
    /*
     * I'm deeply splited on that. On one hand, I would really like to
     * let anything Rudder specific apart from LDAP inventory.
     * So I would really like to have here a "nodeAttributes" property, with a JSON like
     * content, that could be easily extendable by user (adding their own information in
     * inventory in a semi-structured way) and totally independant from Rudder Logic.
     * The rudder logic ("does the node have a rudder server role ? what is it ? what is implies ?
     * how is it model ? etc etc) will live in the Rudder project, and it would be a compelling
     * argument to have a more interesting Node structure there, independant from the inventory.
     * On the other hand, it is MUCH more simpler for now to just have a Seq of roles.
     * So, let's start little until we know what we want exactly.
     */
  , serverRoles          : Set[ServerRole]          = Set()
  , timezone             : Option[NodeTimezone]     = None
  , customProperties     : List[CustomProperty]     = Nil
) {

  /**A copy of the node with the updated main.
   * Use it like:
   * val nodeCopy = node.copyWithMain { m => m.copy(id = newId }
   */
  def copyWithMain(update:NodeSummary => NodeSummary) : NodeInventory = {
    this.copy(main = update(this.main))
  }

}
