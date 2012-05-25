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

import java.net.InetAddress
import java.net.UnknownHostException
import InetAddressUtils._
import org.joda.time.DateTime
import com.normation.inventory.domain._
import com.normation.utils.HashcodeCaching

sealed trait NodeElement {
  def description:Option[String]
}

case class FileSystem(
  mountPoint : String, 
  name : Option[String] = None,
  description:Option[String] = None,
  fileCount : Option[Int] = None,
  freeSpace : Option[MemorySize] = None,
  totalSpace : Option[MemorySize] = None
) extends NodeElement with HashcodeCaching


case class Network (
  name : String,
  description:Option[String] = None,
  ifAddresses : Seq[InetAddress] = Seq(),
  ifDhcp : Option[InetAddress] = None,
  ifGateway : Option[InetAddress] = None,
  ifMask : Option[InetAddress] = None,
  ifSubnet : Option[InetAddress] = None,
  macAddress : Option[String] = None,
  status : Option[String] = None,
  ifType : Option[String] = None,
  speed : Option[String] = None,
  typeMib : Option[String] = None
) extends NodeElement with HashcodeCaching {
  def ifAddress : Option[InetAddress] = ifAddresses.headOption
}

case class EnvironmentVariable(
  name:String,
  value:String
) extends HashcodeCaching

object InetAddressUtils {
  
  implicit def getAddressByName(a:String) : Option[InetAddress] = 
    try {
      Some(InetAddress.getByName(a))
    } catch {
      case e:java.net.UnknownHostException => None
    }
}


sealed trait OsType {
  def kernelName : String
  def name : String //name is normalized and not destined to be printed - use localization for that
  override def toString = kernelName
}

object UnknownOSType extends OsType { 
  val kernelName = "N/A"
  val name = "N/A" 
}

sealed abstract class WindowsType extends OsType {
  override val kernelName = "MSWin"
}

case object UnknownWindowsType extends WindowsType                { val name = "Windows" }
case object WindowsXP extends WindowsType with HashcodeCaching    { val name = "WindowsXP" }
case object WindowsVista extends WindowsType with HashcodeCaching { val name = "WindowsVista" }
case object WindowsSeven extends WindowsType with HashcodeCaching { val name = "WindowsSeven" }
case object Windows2000 extends WindowsType with HashcodeCaching  { val name = "Windows2000" }
case object Windows2003 extends WindowsType with HashcodeCaching  { val name = "Windows2003" }
case object Windows2008 extends WindowsType with HashcodeCaching  { val name = "Windows2008" }
case object Windows2008R2 extends WindowsType with HashcodeCaching  { val name = "Windows2008R2" }


/**
 * Specific Linux subtype (distribution)
 */
sealed abstract class LinuxType extends OsType {
  override val kernelName = "Linux"
}

case object UnknownLinuxType extends LinuxType with HashcodeCaching { val name = "Linux" }
case object Debian extends LinuxType with HashcodeCaching           { val name = "Debian" }
case object Ubuntu extends LinuxType with HashcodeCaching           { val name = "Ubuntu" }
case object Redhat extends LinuxType with HashcodeCaching           { val name = "Redhat" }
case object Centos extends LinuxType with HashcodeCaching           { val name = "Centos" }
case object Fedora extends LinuxType with HashcodeCaching           { val name = "Fedora" }
case object Suse extends LinuxType with HashcodeCaching             { val name = "Suse" }

/**
 * The different OS type. For now, we know
 * two of them: 
 * - Linux ;
 * - Windows.
 * And a joker
 * - Unknown
 */
sealed abstract class OsDetails(
    val os:OsType                      // give both type (Linux, Windows, etc) and name ("SuSE", "Windows", etc)
  , val fullName : String            //"SUSE Linux Enterprise Server 11 (x86_64)"
  , val version : Version            // "5.08", "11.04", "N/A" for windows
  , val servicePack : Option[String] // a service pack
  , val kernelVersion : Version        // "2.6.32.12-0.7-default", "N/A" for windows
)

case class UnknownOS( 
    override val fullName : String = "N/A"
  , override val version : Version = new Version("N/A")
  , override val servicePack : Option[String]  = None
  , override val kernelVersion : Version = new Version("N/A")
) extends OsDetails(UnknownOSType, fullName, version, servicePack, kernelVersion) with HashcodeCaching


case class Linux(
    override val os:OsType
  , override val fullName : String
  , override val version : Version
  , override val servicePack : Option[String] 
  , override val kernelVersion : Version
) extends OsDetails(os, fullName, version, servicePack, kernelVersion) with HashcodeCaching

case class Windows(
    override val os:OsType
  , override val fullName : String
  , override val version : Version
  , override val servicePack : Option[String] 
  , override val kernelVersion : Version
  , userDomain : Option[String] = None
  , registrationCompany : Option[String] = None
  , productKey : Option[String] = None
  , productId : Option[String] = None
) extends OsDetails(os, fullName, version, servicePack, kernelVersion) with HashcodeCaching


case class NodeSummary(
  id : NodeId, 
  status:InventoryStatus,
  rootUser : String,
  hostname : String,
  osDetails : OsDetails,
  policyServerId : NodeId
  //agent name
  //ipss
) extends HashcodeCaching

case class NodeInventory(
  main:NodeSummary,
  //not sure we want to keep that
  name :Option[String] = None,
  description:Option[String] = None,  
  ram : Option[MemorySize] = None,
  swap : Option[MemorySize] = None,
  inventoryDate:Option[DateTime] = None,
  archDescription : Option[String] = None,
  lastLoggedUser : Option[String] = None,
  lastLoggedUserTime : Option[DateTime] = None,
  agentNames : Seq[AgentType] = Seq(),
  publicKeys : Seq[PublicKey] = Seq(),
  machineId : Option[(MachineUuid,InventoryStatus)] = None, //if we want several ids, we would have to ass an "alternate machine" field
  hostedVmIds : Seq[(MachineUuid,InventoryStatus)] = Seq(),
  softwareIds : Seq[SoftwareUuid] = Seq(),
  accounts : Seq[String] = Seq(),
  techniques : Seq[String] = Seq(),
  serverIps : Seq[String] = Seq(),
  networks: Seq[Network] = Seq(),
  fileSystems:Seq[FileSystem] = Seq()
  //TODO: environment:Map[String,String]
) extends HashcodeCaching {
  
  /**A copy of the node with the updated main.
   * Use it like:
   * val nodeCopy = node.copyWithMain { m => m.copy(id = newId }
   */
  def copyWithMain(update:NodeSummary => NodeSummary) : NodeInventory = {
    this.copy(main = update(this.main))
  }
  
}
