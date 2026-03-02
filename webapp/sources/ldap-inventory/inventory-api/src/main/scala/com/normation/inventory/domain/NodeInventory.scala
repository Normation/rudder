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

import enumeratum.*
import java.net.InetAddress
import java.time.Instant
import org.apache.commons.lang3.Strings
import zio.json.*
import zio.json.ast.*

sealed trait NodeElement {
  def description: Option[String]
}

final case class FileSystem(
    mountPoint:  String,
    name:        Option[String] = None,
    description: Option[String] = None,
    fileCount:   Option[Int] = None,
    freeSpace:   Option[MemorySize] = None,
    totalSpace:  Option[MemorySize] = None
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
final case class Network(
    name:                                                              String,
    description:                                                       Option[String] = None,
    @jsonField("ipAddresses") @jsonAliases("ifAddresses") ifAddresses: Seq[InetAddress] = Seq(),
    @jsonField("dhcpServer") @jsonAliases("ifDhcp") ifDhcp:            Option[InetAddress] = None,
    @jsonField("gateway") @jsonAliases("ifGateway") ifGateway:         Seq[InetAddress] = Seq(),
    @jsonField("mask") @jsonAliases("ifMask") ifMask:                  Seq[InetAddress] = Seq(),
    @jsonField("subnet") @jsonAliases("ifSubnet") ifSubnet:            Seq[InetAddress] = Seq(),
    macAddress:                                                        Option[String] = None,
    status:                                                            Option[String] = None,
    ifType:                                                            Option[String] = None,
    speed:                                                             Option[String] = None,
    typeMib:                                                           Option[String] = None
) extends NodeElement {
  def ifAddress: Option[InetAddress] = ifAddresses.headOption
}

final case class Process(
    pid:                                                        Int,
    @jsonField("name") @jsonAliases("commandName") commandName: Option[String],
    cpuUsage:                                                   Option[Float] = None,
    memory:                                                     Option[Float] = None,
    // we use a string for the date, as it's more
    // important to have something to show to the user than
    // to be normalized for that field.
    started:                                                    Option[String] = None,
    tty:                                                        Option[String] = None,
    user:                                                       Option[String] = None,
    virtualMemory:                                              Option[Double] = None,
    description:                                                Option[String] = None
) extends NodeElement

object Process {
  implicit val codecProcess: JsonCodec[Process] = DeriveJsonCodec.gen
}

final case class VirtualMachine(
    @jsonField("type") @jsonAliases("vmtype") vmtype: Option[String] = None,
    subsystem:                                        Option[String] = None,
    owner:                                            Option[String] = None,
    name:                                             Option[String] = None,
    status:                                           Option[String] = None,
    vcpu:                                             Option[Int] = None,
    memory:                                           Option[String] = None,
    uuid:                                             MachineUuid, // TODO : Maybe add an inventoryStatus field

    description: Option[String] = None
) extends NodeElement

final case class EnvironmentVariable(
    name:  String,
    value: Option[String] = None
) extends NodeElement {
  // description is never present in Environment Variable, and we don't store it if it was
  val description: Option[String] = None
}

object EnvironmentVariable {
  implicit val codecEnvironmentVariable: JsonCodec[EnvironmentVariable] = DeriveJsonCodec.gen
}

object InetAddressUtils {

  def getAddressByName(a: String): Option[InetAddress] = {
    try {
      Some(InetAddress.getByName(a))
    } catch {
      case e: java.net.UnknownHostException => None
    }
  }
}

sealed trait OsType {
  def kernelName: String
  def name:       String // name is normalized and not destined to be printed - use localization for that
  override def toString = kernelName
  // display name is the brand name used in UI for human
  def displayName: String
}

object UnknownOSType extends OsType {
  override val kernelName  = "N/A"
  override val name        = "N/A"
  override val displayName = "N/A"
}

/*
 * All the case for windows. We have three place where we need to use a "string" name:
 * - for the object "name" - the historic identifier. It's also used for LDAP serialisation
 * - and when we parse an inventory - this must be decidable and is kind of "contains", so parsing
 *   need to take care of order for similar name like 2012/2012 r2.
 * - and for the UI "brand" name.
 */
sealed abstract class WindowsType(override val entryName: String, val inventoryParseKey: String)(
    val displayName: String = entryName
) extends EnumEntry with OsType {
  override val kernelName = "Windows"
  def name: String = entryName // for compat
}

object WindowsType extends Enum[WindowsType] {

  case object WindowsXP     extends WindowsType("WindowsXP", "xp")("Windows XP")
  case object WindowsVista  extends WindowsType("WindowsVista", "vista")("Windows Vista")
  case object WindowsSeven  extends WindowsType("WindowsSeven", "seven")("Windows Seven")
  case object Windows10     extends WindowsType("Windows10", "10")("Windows 10")
  case object Windows11     extends WindowsType("Windows11", "11")("Windows 11")
  case object Windows2000   extends WindowsType("Windows2000", "2000")("Windows 2000")
  case object Windows2003   extends WindowsType("Windows2003", "2003")("Windows 2003")
  case object Windows2008   extends WindowsType("Windows2008", "2008")("Windows 2008")
  case object Windows2008R2 extends WindowsType("Windows2008R2", "2008 r2")("Windows 2008 R2")
  case object Windows2012   extends WindowsType("Windows2012", "2012")("Windows 2012")
  case object Windows2012R2 extends WindowsType("Windows2012R2", "2012 r2")("Windows 2012 R2")
  case object Windows2016   extends WindowsType("Windows2016", "2016")("Windows 2016")
  case object Windows2016R2 extends WindowsType("Windows2016R2", "2016 r2")("Windows 2016 R2")
  case object Windows2019   extends WindowsType("Windows2019", "2019")("Windows 2019")
  case object Windows2022   extends WindowsType("Windows2022", "2022")("Windows 2022")
  case object Windows2025   extends WindowsType("Windows2025", "2025")("Windows 2025")

  case object UnknownWindowsType extends WindowsType("UnknownWindows", "Unknown Windows")("Other Windows version")

  def values: IndexedSeq[WindowsType] = findValues

  def allKnownTypes: List[WindowsType] = values.filterNot(_ == UnknownWindowsType).toList

  /*
   * Given a Windows OS string, how to find the OS ?
   */
  def parseFromInventory(osString: String): OsType = {
    values.collect { case t if Strings.CI.contains(osString, t.inventoryParseKey) => t }
      // if there is several matches, like in 2016/2016 r2, takes the longest key
      .sortBy(-_.inventoryParseKey.length)
      .headOption
      .getOrElse(UnknownWindowsType)
  }

  def parseFromLdap(os: String): OsType = {
    withNameInsensitiveOption(os).getOrElse(LinuxType.UnknownLinuxType)
  }
}

/*
 * Specific Linux subtype (distribution)
 * We have three places where we need to use a "string" name:
 * - for the object "name" - the historic identifier. It's also used for LDAP serialisation
 * - and when we parse an inventory - this must be decidable
 * - and for the UI "brand" name. In a lot of case for Linux, it's the same of the identifier.
 */
sealed abstract class LinuxType(override val entryName: String, val inventoryParseKey: String)(
    val displayName: String = entryName
) extends EnumEntry with OsType {
  override val kernelName = "Linux"
  def name: String = entryName // for compat
}

object LinuxType extends Enum[LinuxType] {

  case object AlmaLinux   extends LinuxType("AlmaLinux", "almalinux")()
  case object AmazonLinux extends LinuxType("AmazonLinux", "amazon linux")("Amazon Linux")
  case object Android     extends LinuxType("Android", "android")()
  case object Centos      extends LinuxType("Centos", "centos")("CentOS")
  case object Debian      extends LinuxType("Debian", "debian")()
  case object Fedora      extends LinuxType("Fedora", "fedora")()
  case object Kali        extends LinuxType("Kali", "kali")()
  case object Mint        extends LinuxType("Mint", "mint")()
  case object Oracle      extends LinuxType("Oracle", "oracle")("Oracle Linux Server")
  case object Raspbian    extends LinuxType("Raspbian", "raspbian")()
  case object Redhat      extends LinuxType("Redhat", "redhat")("Red Hat")
  case object RockyLinux  extends LinuxType("RockyLinux", "rocky")("Rocky Linux")
  case object Scientific  extends LinuxType("Scientific", "scientific")("Scientific Linux")
  case object Slackware   extends LinuxType("Slackware", "slackware")()
  case object Suse        extends LinuxType("Suse", "suse")("SUSE")
  case object Tuxedo      extends LinuxType("Tuxedo", "tuxedo")("TUXEDO OS")
  case object Ubuntu      extends LinuxType("Ubuntu", "ubuntu")()

  case object UnknownLinuxType extends LinuxType("UnknownLinux", "Unknown Linux")("Other Linux")

  def values: IndexedSeq[LinuxType] = findValues

  def allKnownTypes: List[LinuxType] = values.filterNot(_ == UnknownLinuxType).toList

  /*
   * Given a Windows OS string, how to find the OS ?
   */
  def parseFromInventory(osString: String): OsType = {
    values
      .find(t => Strings.CI.contains(osString, t.inventoryParseKey))
      .getOrElse(UnknownLinuxType)
  }

  def parseFromLdap(os: String): OsType = {
    withNameInsensitiveOption(os).getOrElse(WindowsType.UnknownWindowsType)
  }
}

/**
 * The different OS type. For now, we know:
 * - Linux
 * - Windows.
 * And a joker
 * - Unknown
 */
sealed abstract class OsDetails(
    val os: OsType, // give both type (Linux, Windows, etc) and name ("SUSE", "Windows", etc)

    val fullName: String, // "SUSE Linux Enterprise Server 11 (x86_64)"

    val version: Version, // "5.08", "11.04", "N/A" for windows

    val servicePack: Option[String], // a service pack

    val kernelVersion: Version // "2.6.32.12-0.7-default", "N/A" for windows
)

final case class UnknownOS(
    override val fullName:      String = "N/A",
    override val version:       Version = new Version("N/A"),
    override val servicePack:   Option[String] = None,
    override val kernelVersion: Version = new Version("N/A")
) extends OsDetails(UnknownOSType, fullName, version, servicePack, kernelVersion)

final case class Linux(
    override val os:            OsType,
    override val fullName:      String,
    override val version:       Version,
    override val servicePack:   Option[String],
    override val kernelVersion: Version
) extends OsDetails(os, fullName, version, servicePack, kernelVersion)

final case class Windows(
    override val os:            OsType,
    override val fullName:      String,
    override val version:       Version,
    override val servicePack:   Option[String],
    override val kernelVersion: Version,
    userDomain:                 Option[String] = None,
    registrationCompany:        Option[String] = None,
    productKey:                 Option[String] = None,
    productId:                  Option[String] = None
) extends OsDetails(os, fullName, version, servicePack, kernelVersion)

object ParseOSType {

  def getType(osType: String, osName: String, fullName: String): OsType = {
    (osType.toLowerCase, osName.toLowerCase, fullName.toLowerCase) match {
      case ("mswin32", _, x) =>
        // in windows, relevant information are in the fullName string
        WindowsType.parseFromInventory(x)

      case ("linux", x, _) =>
        LinuxType.parseFromInventory(x)

      case _ => UnknownOSType
    }
  }

  def getDetails(
      osType:        OsType,
      fullName:      String,
      version:       Version,
      servicePack:   Option[String],
      kernelVersion: Version
  ): OsDetails = {
    osType match {
      case w: WindowsType =>
        Windows(
          os = w,
          fullName = fullName,
          version = version,
          servicePack = servicePack,
          kernelVersion = kernelVersion,
          userDomain = None,
          registrationCompany = None,
          productId = None,
          productKey = None
        )

      case distrib: LinuxType =>
        Linux(
          os = distrib,
          fullName = fullName,
          version = version,
          servicePack = servicePack,
          kernelVersion = kernelVersion
        )

      case _ => UnknownOS(fullName, version, servicePack, kernelVersion)
    }
  }
}

final case class NodeSummary(
    id:             NodeId,
    status:         InventoryStatus,
    rootUser:       String,
    hostname:       String,
    osDetails:      OsDetails,
    policyServerId: NodeId,
    keyStatus:      KeyStatus
    // agent name
    // ipss
)

sealed trait KeyStatus {
  val value: String
}
case object CertifiedKey extends KeyStatus {
  val value = "certified"
}
case object UndefinedKey extends KeyStatus {
  val value = "undefined"
}

final case class NodeTimezone(
    name:   String,
    offset: String
)

final case class CustomProperty(
    name:  String,
    value: Json
)

object CustomProperty {
  implicit val codecCustomProperty: JsonCodec[CustomProperty] = DeriveJsonCodec.gen
}

sealed abstract class SoftwareUpdateKind(override val entryName: String) extends EnumEntry {
  def name: String = entryName
}

object SoftwareUpdateKind                                                    extends Enum[SoftwareUpdateKind] {
  case object None                       extends SoftwareUpdateKind("none")
  case object Defect                     extends SoftwareUpdateKind("defect")
  case object Security                   extends SoftwareUpdateKind("security")
  case object Enhancement                extends SoftwareUpdateKind("enhancement")
  sealed case class Other(value: String) extends SoftwareUpdateKind("other")

  def values: IndexedSeq[SoftwareUpdateKind] = findValues

  def parse(value: String): Either[String, SoftwareUpdateKind] = {
    withNameInsensitiveOption(value)
      .toRight(
        s"Value '${value}' is not recognized as SoftwareUpdateKind. Accepted values are: '${values.map(_.entryName).mkString("', '")}'"
      )
  }
}
sealed abstract class SoftwareUpdateSeverity(override val entryName: String) extends EnumEntry                {
  def name: String = entryName
}

object SoftwareUpdateSeverity extends Enum[SoftwareUpdateSeverity] {
  case object Low                        extends SoftwareUpdateSeverity("low")
  case object Moderate                   extends SoftwareUpdateSeverity("moderate")
  case object High                       extends SoftwareUpdateSeverity("high")
  case object Critical                   extends SoftwareUpdateSeverity("critical")
  sealed case class Other(value: String) extends SoftwareUpdateSeverity("other")

  def values: IndexedSeq[SoftwareUpdateSeverity] = findValues

  def parse(value: String): Either[String, SoftwareUpdateSeverity] = values
    .find(_.name == value.toLowerCase())
    .toRight(
      s"Value '${value}' is not recognized as SoftwareUpdateSeverity. Accepted values are: '${values.map(_.name).mkString("', '")}'"
    )
}

/*
 * A software update:
 * - name: software name (the same that installed, used as key)
 * - version: target version (only one, different updates for different version)
 * - arch: arch of the package (x86_64, etc)
 * - from: tools that produced that update (yum, apt, etc)
 * - what kind
 * - source: from what source like repo name (for now, they are not specific)
 * #TODO: other information? A Map[String, String] of things ?
 */
final case class SoftwareUpdate(
    name:        String,
    version:     Option[String],
    arch:        Option[String],
    from:        Option[String],
    kind:        SoftwareUpdateKind,
    source:      Option[String],
    description: Option[String],
    severity:    Option[SoftwareUpdateSeverity],
    date:        Option[Instant],
    ids:         Option[List[String]]
)

object KeyStatus {
  def apply(value: String): Either[InventoryError.SecurityToken, KeyStatus] = {
    value match {
      case CertifiedKey.value => Right(CertifiedKey)
      case UndefinedKey.value => Right(UndefinedKey)
      case _                  => Left(InventoryError.SecurityToken(s"${value} is not a valid key status"))
    }
  }
}

final case class AgentCapability(value: String) extends AnyVal

object AgentCapability {
  implicit val codecAgentCapability: JsonCodec[AgentCapability] = JsonCodec.string.transform(AgentCapability.apply, _.value)
}

final case class NodeInventory(
    main:                 NodeSummary,
    name:                 Option[String] = None,
    description:          Option[String] = None,
    ram:                  Option[MemorySize] = None,
    swap:                 Option[MemorySize] = None,
    inventoryDate:        Option[Instant] = None,
    receiveDate:          Option[Instant] = None,
    archDescription:      Option[String] = None,
    lastLoggedUser:       Option[String] = None,
    lastLoggedUserTime:   Option[Instant] = None,
    agents:               Seq[AgentInfo] = Seq(),
    serverIps:            Seq[String] = Seq(),
    machineId:            Option[(MachineUuid, InventoryStatus)] =
      None, // if we want several ids, we would have to ass an "alternate machine" field

    softwareIds:          Seq[SoftwareUuid] = Seq(),
    accounts:             Seq[String] = Seq(),
    environmentVariables: Seq[EnvironmentVariable] = Seq(),
    processes:            Seq[Process] = Seq(),
    vms:                  Seq[VirtualMachine] = Seq(),
    networks:             Seq[Network] = Seq(),
    fileSystems:          Seq[FileSystem] = Seq(), /*
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

    timezone:         Option[NodeTimezone] = None,
    customProperties: List[CustomProperty] = Nil,
    softwareUpdates:  List[SoftwareUpdate] = Nil
) {

  /**A copy of the node with the updated main.
   * Use it like:
   * val nodeCopy = node.copyWithMain { m => m.copy(id = newId }
   */
  def copyWithMain(update: NodeSummary => NodeSummary): NodeInventory = {
    this.copy(main = update(this.main))
  }

}
