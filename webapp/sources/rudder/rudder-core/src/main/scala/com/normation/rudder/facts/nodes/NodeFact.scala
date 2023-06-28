/*
 *************************************************************************************
 * Copyright 2023 Normation SAS
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

package com.normation.rudder.facts.nodes

import com.normation.inventory.domain._
import com.normation.inventory.domain.{Version => SVersion}
import com.normation.rudder.domain.nodes.MachineInfo
import com.normation.rudder.domain.nodes.Node
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.domain.nodes.NodeKind
import com.normation.rudder.domain.nodes.NodeState
import com.normation.rudder.domain.policies.PolicyMode
import com.normation.rudder.domain.properties.GenericProperty
import com.normation.rudder.domain.properties.InheritMode
import com.normation.rudder.domain.properties.NodeProperty
import com.normation.rudder.domain.properties.PropertyProvider
import com.normation.rudder.domain.servers.Srv
import com.normation.rudder.reports._
import com.normation.utils.DateFormaterService
import com.normation.utils.ParseVersion
import com.normation.utils.Version
import com.softwaremill.quicklens._
import com.typesafe.config.ConfigRenderOptions
import com.typesafe.config.ConfigValue
import java.net.InetAddress
import net.liftweb.json.JsonAST
import net.liftweb.json.JsonAST._
import net.liftweb.json.JsonAST.JValue
import org.joda.time.DateTime
import zio.Chunk
import zio.json._
import zio.json.ast.Json
import zio.json.ast.Json._
import zio.json.internal.Write

/**
 * A node fact is the node with all inventory info, settings, properties, etc, in a layout that is similar to what API
 * return about a node.
 * It only contains facts and not computed things about the node:
 * - no compliance scoring
 * - no resolved properties (for ex inherited ones)
 */
final case class IpAddress(inet: String)
final case class ManagementTechnology(
    name:         String,
    version:      Version,
    capabilities: Chunk[String],
    nodeKind:     NodeKind
)
final case class ManagementTechnologyDetails(
    cfengineKeys:               Chunk[String],
    cfengineUser:               String,
    keyStatus:                  KeyStatus,
    nodeReportingConfiguration: ReportingConfiguration
)

final case class RudderAgent(
    @jsonField("type") agentType: AgentType,
    user:                         String,
    version:                      AgentVersion,
    securityToken:                SecurityToken,
    // agent capabilities are lower case string used as tags giving information about what agent can do
    capabilities:                 Chunk[AgentCapability]
) {
  def toAgentInfo = AgentInfo(agentType, Some(version), securityToken, capabilities.toSet)
}

// rudder settings for that node
final case class RudderSettings(
    keyStatus:              KeyStatus,
    reportingConfiguration: ReportingConfiguration,
    kind:                   NodeKind,
    status:                 InventoryStatus,
    state:                  NodeState,
    policyMode:             Option[PolicyMode],
    policyServerId:         NodeId
)

final case class InputDevice(caption: String, description: String, @jsonField("type") tpe: String)
final case class LocalGroup(id: Int, name: String, members: Chunk[String])
final case class LocalUser(id: Int, name: String, login: String, home: String, shell: String)
final case class LogicalVolume(attr: String, lvName: String, lvUUID: String, segCount: String, size: Long, vgUUID: String)
final case class PhysicalVolume(
    attr:      String,
    device:    String,
    format:    String,
    free:      Long,
    peSize:    Long,
    pvPeCount: Long,
    pvUUID:    String,
    size:      Long,
    vgUUID:    String
)
final case class VolumeGroup(
    attr:         String,
    free:         Long,
    lvCount:      Long,
    pvCount:      Long,
    size:         Long,
    vgExtentsize: String,
    vgname:       String,
    vgUUID:       String
)

final case class SoftwareFact(
    name:               String,
    version:            SVersion,
    arch:               Option[String] = None,
    size:               Option[Long] = None,
    from:               Option[String] = None,
    publisher:          Option[String] = None,
    sourceName:         Option[String] = None,
    sourceVersion:      Option[SVersion] = None,
    systemCategory:     Option[String] = None,
    licenseName:        Option[String] = None,
    licenseDescription: Option[String] = None,
    expirationDate:     Option[DateTime] = None,
    productId:          Option[String] = None,
    productKey:         Option[String] = None,
    oem:                Option[String] = None
)

object SoftwareFact {
  implicit class ToSoftware(sf: SoftwareFact) {
    def toSoftware: Software = Software(
      SoftwareUuid(sf.name),
      Some(sf.name),
      None,
      Some(sf.version),
      sf.publisher.map(SoftwareEditor),
      None,
      sf.licenseName.map(l => License(l, sf.licenseDescription, sf.productId, sf.productKey, sf.oem, sf.expirationDate)),
      sf.sourceName,
      sf.sourceVersion
    )
  }
}

object NodeFact {

  /*
   * Check if the node fact are the same.
   * Sameness is not equatity. It does not look for:
   * - inventory date
   * - fact processing time
   * - acceptation time
   * - order in any collection
   *
   * Having a hashcode on node fact would be inefficient,
   * most node fact are never compared for sameness.
   * Same is heavy, don't use it often !
   */
  def same(a: NodeFact, b: NodeFact): Boolean = {
    // compare two chunk sameness
    def compare[A, B: Ordering](accessor: NodeFact => Chunk[A])(orderOn: A => B): Boolean = {
      accessor(a).size == accessor(b).size &&
      accessor(a).sortBy(orderOn) == accessor(b).sortBy(orderOn)
    }

    def eq[A](accessor: NodeFact => A): Boolean = {
      accessor(a) == accessor(b)
    }

    eq(_.id) &&
    eq(_.description) &&
    eq(_.fqdn) &&
    eq(_.os) &&
    eq(_.machine) &&
    eq(_.rudderSettings) &&
    eq(_.rudderAgent) &&
    compare(_.properties)(_.name) &&
    compare(_.ipAddresses)(_.inet) &&
    eq(_.timezone) &&
    eq(_.ram) &&
    eq(_.swap) &&
    eq(_.archDescription) &&
    compare(_.accounts)(identity) &&
    compare(_.bios)(_.name) &&
    compare(_.controllers)(_.name) &&
    compare(_.environmentVariables)(_._1) &&
    compare(_.fileSystems)(_.mountPoint) &&
    compare(_.inputs)(_.tpe) &&
    compare(_.localGroups)(_.id) &&
    compare(_.localUsers)(_.id) &&
    compare(_.logicalVolumes)(_.lvUUID) &&
    compare(_.memories)(_.slotNumber) &&
    compare(_.networks)(_.name) &&
    compare(_.physicalVolumes)(_.pvUUID) &&
    compare(_.ports)(_.name) &&
    compare(_.processes)(_.pid) &&
    compare(_.processors)(_.name) &&
    compare(_.slots)(_.name) &&
    compare(_.software)(_.name) &&
    compare(_.softwareUpdate)(_.name) &&
    compare(_.sounds)(_.name) &&
    compare(_.storages)(_.name) &&
    compare(_.videos)(_.name) &&
    compare(_.vms)(_.uuid.value)
  }

  def sortAttributes(node: NodeFact): NodeFact = {
    node
      .modify(_.properties)
      .using(_.sortBy(_.name))
      .modify(_.ipAddresses)
      .using(_.sortBy(_.inet))
      .modify(_.accounts)
      .using(_.sorted)
      .modify(_.bios)
      .using(_.sortBy(_.name))
      .modify(_.controllers)
      .using(_.sortBy(_.name))
      .modify(_.environmentVariables)
      .using(_.sortBy(_._1))
      .modify(_.inputs)
      .using(_.sortBy(_.tpe))
      .modify(_.fileSystems)
      .using(_.sortBy(_.name))
      .modify(_.localGroups)
      .using(_.sortBy(_.id))
      .modify(_.localUsers)
      .using(_.sortBy(_.id))
      .modify(_.logicalVolumes)
      .using(_.sortBy(_.lvName))
      .modify(_.memories)
      .using(_.sortBy(_.slotNumber))
      .modify(_.networks)
      .using(_.sortBy(_.name))
      .modify(_.ports)
      .using(_.sortBy(_.name))
      .modify(_.processes)
      .using(_.sortBy(_.pid))
      .modify(_.processors)
      .using(_.sortBy(_.name))
      .modify(_.slots)
      .using(_.sortBy(_.name))
      .modify(_.software)
      .using(_.sortBy(_.name))
      .modify(_.softwareUpdate)
      .using(_.sortBy(_.name))
      .modify(_.sounds)
      .using(_.sortBy(_.name))
      .modify(_.storages)
      .using(_.sortBy(_.name))
      .modify(_.videos)
      .using(_.sortBy(_.name))
      .modify(_.vms)
      .using(_.sortBy(_.name))
  }

  def toMachineId(nodeId: NodeId) = MachineUuid("machine-" + nodeId.value)

  implicit class IterableToChunk[A](it: Iterable[A]) {
    def toChunk: Chunk[A] = Chunk.fromIterable(it)
  }

  implicit class EitherToChunk[A](either: Either[_, A]) {
    def toChunk: Chunk[A] = {
      either match {
        case Left(_)  => Chunk.empty
        case Right(v) => Chunk(v)
      }
    }
  }

  // to be able to: inventory.machine.get(_.bios) and get a Chunk
  implicit class MachineEltToChunk[A](opt: Option[MachineInventory]) {
    def chunk(iterable: MachineInventory => Iterable[A]): Chunk[A] = {
      opt match {
        case None    => Chunk()
        case Some(m) => Chunk.fromIterable(iterable(m))
      }
    }
  }
  implicit class SoftwareToFact(s: Software)                         {
    def toFact: Option[SoftwareFact] = for {
      n <- s.name
      v <- s.version
    } yield SoftwareFact(
      n,
      v,
      None,
      None,
      None,
      s.editor.map(_.name),
      s.sourceName,
      s.sourceVersion,
      None,
      s.license.map(_.name),
      s.license.flatMap(_.description),
      s.license.flatMap(_.expirationDate),
      s.license.flatMap(_.productId),
      s.license.flatMap(_.productKey),
      s.license.flatMap(_.oem)
    )
  }

  implicit class ToCompat(node: NodeFact) {

    def toNode: Node = Node(
      node.id,
      node.fqdn,
      "", // description
      node.rudderSettings.state,
      node.isSystem,
      node.isPolicyServer,
      node.creationDate,
      node.rudderSettings.reportingConfiguration,
      node.properties.toList,
      node.rudderSettings.policyMode
    )

    def toNodeInfo: NodeInfo = NodeInfo(
      node.toNode,
      node.fqdn,
      Some(node.machine),
      node.os,
      node.ipAddresses.toList.map(_.inet),
      node.lastInventoryDate.getOrElse(node.factProcessedDate),
      node.rudderSettings.keyStatus,
      Chunk(
        AgentInfo(
          node.rudderAgent.agentType,
          Some(node.rudderAgent.version),
          node.rudderAgent.securityToken,
          node.rudderAgent.capabilities.toSet
        )
      ),
      node.rudderSettings.policyServerId,
      node.rudderAgent.user,
      node.archDescription,
      node.ram,
      node.timezone
    )

    def toSrv: Srv = {
      Srv(
        node.id,
        node.rudderSettings.status,
        node.fqdn,
        node.os.os.kernelName,
        node.os.os.name,
        node.os.fullName,
        node.serverIps,
        node.creationDate,
        node.isPolicyServer
      )
    }

    def toNodeSummary: NodeSummary = {
      NodeSummary(
        node.id,
        node.rudderSettings.status,
        node.rudderAgent.user,
        node.fqdn,
        node.os,
        node.rudderSettings.policyServerId,
        node.rudderSettings.keyStatus
      )
    }

    def toNodeInventory: NodeInventory = NodeInventory(
      node.toNodeSummary,
      name = None,
      description = None,
      node.ram,
      node.swap,
      node.lastInventoryDate,
      Some(node.factProcessedDate),
      node.archDescription,
      lastLoggedUser = None,
      lastLoggedUserTime = None,
      List(node.rudderAgent.toAgentInfo),
      node.serverIps,
      Some((node.machineId, node.rudderSettings.status)),
      // really not sure about what to do here
      softwareIds = List(),
      node.accounts,
      node.environmentVariables.map { case (a, b) => EnvironmentVariable(a, Some(b), None) },
      node.processes,
      node.vms,
      node.networks,
      node.fileSystems,
      node.timezone,
      node.customProperties.toList,
      node.softwareUpdate.toList
    )

    def toMachineInventory: MachineInventory = MachineInventory(
      node.machineId,
      node.rudderSettings.status,
      node.machine.machineType,
      name = None,
      mbUuid = None,
      node.lastInventoryDate,
      Some(node.factProcessedDate),
      node.machine.manufacturer,
      node.machine.systemSerial,
      node.bios.toList,
      node.controllers.toList,
      node.memories.toList,
      node.ports.toList,
      node.processors.toList,
      node.slots.toList,
      node.sounds.toList,
      node.storages.toList,
      node.videos.toList
    )

    def toFullInventory: FullInventory = FullInventory(node.toNodeInventory, Some(node.toMachineInventory))
  }

  /*
   *
   */
  def fromCompat(nodeInfo: NodeInfo, inventory: Either[InventoryStatus, FullInventory], software: Seq[Software]): NodeFact = {
    NodeFact(
      nodeInfo.id,
      nodeInfo.description.strip() match {
        case "" => None
        case x  => Some(nodeInfo.description)
      },
      nodeInfo.hostname,
      nodeInfo.osDetails,
      nodeInfo.machine.getOrElse(MachineInfo(NodeFact.toMachineId(nodeInfo.id), UnknownMachineType, None, None)),
      RudderSettings(
        nodeInfo.keyStatus,
        nodeInfo.nodeReportingConfiguration,
        nodeInfo.nodeKind,
        inventory.fold(identity, _.node.main.status),
        nodeInfo.state,
        nodeInfo.policyMode,
        nodeInfo.policyServerId
      ),
      RudderAgent(
        nodeInfo.agentsName(0).agentType,
        nodeInfo.localAdministratorAccountName,
        nodeInfo.agentsName(0).version.getOrElse(AgentVersion("0.0.0")),
        nodeInfo.agentsName(0).securityToken,
        nodeInfo.agentsName(0).capabilities.toChunk
      ),
      // nodeInfo properties hold both node properties and custom properties with provider "inventory"
      nodeInfo.properties.toChunk,
      nodeInfo.creationDate,
      nodeInfo.inventoryDate,
      Some(nodeInfo.inventoryDate),
      nodeInfo.ips.map(IpAddress(_)).toChunk,
      nodeInfo.timezone,
      nodeInfo.ram,
      inventory.toOption.flatMap(_.node.swap),
      nodeInfo.archDescription,
      inventory.toChunk.flatMap(_.node.accounts),
      inventory.toChunk.flatMap(_.machine.chunk(_.bios)),
      inventory.toChunk.flatMap(_.machine.chunk(_.controllers)),
      inventory.toChunk.flatMap(_.node.environmentVariables.map(ev => (ev.name, ev.value.getOrElse(""))).toChunk),
      inventory.toChunk.flatMap(_.node.fileSystems.toChunk),
      Chunk(), // TODO: missing input devices in inventory
      Chunk(), // TODO: missing local groups in inventory
      Chunk(), // TODO: missing local users in inventory
      Chunk(), // TODO: missing logical volumes in inventory
      inventory.toChunk.flatMap(_.machine.chunk(_.memories)),
      inventory.toChunk.flatMap(_.node.networks.toChunk),
      Chunk(), // TODO: missing physical volumes in inventory
      inventory.toChunk.flatMap(_.machine.chunk(_.ports)),
      inventory.toChunk.flatMap(_.node.processes.toChunk),
      inventory.toChunk.flatMap(_.machine.chunk(_.processors)),
      inventory.toChunk.flatMap(_.machine.chunk(_.slots)),
      software.flatMap(_.toFact).toChunk,
      inventory.toChunk.flatMap(_.node.softwareUpdates.toChunk),
      inventory.toChunk.flatMap(_.machine.chunk(_.sounds)),
      inventory.toChunk.flatMap(_.machine.chunk(_.storages)),
      inventory.toChunk.flatMap(_.machine.chunk(_.videos)),
      inventory.toChunk.flatMap(_.node.vms.toChunk)
    )
  }

  def agentFromInventory(node: NodeInventory): Option[RudderAgent] = {
    node.agents.headOption.flatMap(a =>
      a.version.map(v => RudderAgent(a.agentType, node.main.rootUser, v, a.securityToken, a.capabilities.toChunk))
    )
  }

  def defaultRudderSettings(status: InventoryStatus): RudderSettings = {
    RudderSettings(
      UndefinedKey,
      ReportingConfiguration(None, None, None),
      NodeKind.Node,
      status,
      NodeState.Enabled,
      None,
      NodeId("root")
    )
  }

  def defaultRudderAgent(localAdmin: String): RudderAgent = {
    RudderAgent(AgentType.CfeCommunity, localAdmin, AgentVersion("unknown"), PublicKey("not initialized"), Chunk.empty)
  }

  def newFromFullInventory(inventory: FullInventory, software: Option[Iterable[Software]]): NodeFact = {
    val now  = DateTime.now()
    val fact = NodeFact(
      inventory.node.main.id,
      None,
      inventory.node.main.hostname,
      inventory.node.main.osDetails,
      MachineInfo(
        NodeFact.toMachineId(inventory.node.main.id),
        inventory.machine.map(_.machineType).getOrElse(UnknownMachineType),
        inventory.machine.flatMap(_.systemSerialNumber),
        inventory.machine.flatMap(_.manufacturer)
      ),
      defaultRudderSettings(inventory.node.main.status),
      agentFromInventory(inventory.node).getOrElse(defaultRudderAgent(inventory.node.main.rootUser)),
      Chunk.empty,
      now,
      now
    )
    updateFullInventory(fact, inventory, software)
  }

  def newFromInventory(inventory: Inventory): NodeFact = {
    newFromFullInventory(FullInventory(inventory.node, Some(inventory.machine)), Some(inventory.applications))
  }

  /*
   * Update all inventory parts from that node fact.
   * The inventory parts are overridden, there is no merge
   * NOTICE: status is ignored !
   */
  def updateInventory(node: NodeFact, inventory: Inventory): NodeFact = {
    updateFullInventory(node, FullInventory(inventory.node, Some(inventory.machine)), Some(inventory.applications))
  }

  // FullInventory does keep the software, but only their IDs, which is not a concept we still have.
  // So the caller can say "I don't know what software" with a None, or "there's no software" with a Some(Nil)
  // Also, we don't update status here, use move or similar methods to change node status.
  def updateFullInventory(node: NodeFact, inventory: FullInventory, software: Option[Iterable[Software]]): NodeFact = {

    def chunkOpt[A](getter: MachineInventory => Seq[A]): Chunk[A] = {
      inventory.machine match {
        case None    => Chunk.empty
        case Some(m) => getter(m).toChunk
      }
    }

    // not sure we wwant that, TODO POC
    require(
      node.id == inventory.node.main.id,
      s"Inventory can only be update on a node with the same ID, but nodeId='${node.id.value}' and in inventory='${inventory.node.main.id.value}'"
    )

    val properties = (
      node.properties.filterNot(
        _.provider == Some(NodeProperty.customPropertyProvider)
      ) ++ inventory.node.customProperties.map(NodeProperty.fromInventory)
    ).sortBy(_.name)

    // now machine are mandatory so if we don't have it inventory, don't update
    val machine = inventory.machine.map { m =>
      MachineInfo(
        NodeFact.toMachineId(inventory.node.main.id),
        m.machineType,
        m.systemSerialNumber,
        m.manufacturer
      )
    }

    node
      .modify(_.fqdn)
      .setTo(inventory.node.main.hostname)
      .modify(_.os)
      .setTo(inventory.node.main.osDetails)
      .modify(_.rudderSettings.policyServerId)
      .setTo(inventory.node.main.policyServerId)
      .modify(_.rudderSettings.keyStatus)
      .setTo(inventory.node.main.keyStatus)
      .modify(_.rudderAgent)
      .setToIfDefined(agentFromInventory(inventory.node))
      .modify(_.properties)
      .setTo(properties)
      .modify(_.factProcessedDate)
      .setToIfDefined(inventory.node.receiveDate)
      .modify(_.lastInventoryDate)
      .setTo(inventory.node.inventoryDate)
      .modify(_.ipAddresses)
      .setTo(Chunk.fromIterable(inventory.node.serverIps.map(IpAddress)))
      .modify(_.timezone)
      .setTo(inventory.node.timezone)
      .modify(_.machine)
      .setToIfDefined(machine)
      .modify(_.ram)
      .setTo(inventory.node.ram)
      .modify(_.swap)
      .setTo(inventory.node.swap)
      .modify(_.archDescription)
      .setTo(inventory.node.archDescription)
      .modify(_.accounts)
      .setTo(inventory.node.accounts.toChunk)
      .modify(_.bios)
      .setTo(chunkOpt(_.bios))
      .modify(_.controllers)
      .setTo(chunkOpt(_.controllers))
      .modify(_.environmentVariables)
      .setTo(inventory.node.environmentVariables.toChunk.map(ev => (ev.name, ev.value.getOrElse(""))))
      .modify(_.fileSystems)
      .setTo(inventory.node.fileSystems.toChunk)
      // missing: inputs, local group, local users, logical volumes, physical volumes
      .modify(_.memories)
      .setTo(chunkOpt(_.memories))
      .modify(_.networks)
      .setTo(inventory.node.networks.toChunk)
      .modify(_.ports)
      .setTo(chunkOpt(_.ports))
      .modify(_.processes)
      .setTo(inventory.node.processes.toChunk)
      .modify(_.processors)
      .setTo(chunkOpt(_.processors))
      .modify(_.slots)
      .setTo(chunkOpt(_.slots))
      .modify(_.software)
      .setToIfDefined(software.map(s => Chunk.fromIterable(s.flatMap(_.toFact))))
      .modify(_.softwareUpdate)
      .setTo(inventory.node.softwareUpdates.toChunk)
      .modify(_.sounds)
      .setTo(chunkOpt(_.sounds))
      .modify(_.storages)
      .setTo(chunkOpt(_.storages))
      .modify(_.videos)
      .setTo(chunkOpt(_.videos))
      .modify(_.vms)
      .setTo(inventory.node.vms.toChunk)
  }

  def updateNode(node: NodeFact, n: Node): NodeFact = {
    import com.softwaremill.quicklens._
    node
      .modify(_.description)
      .setTo(Some(n.description))
      .modify(_.rudderSettings.state)
      .setTo(n.state)
      .modify(_.rudderSettings.kind)
      .setTo(if (n.isPolicyServer) NodeKind.Relay else NodeKind.Node)
      .modify(_.creationDate)
      .setTo(n.creationDate)
      .modify(_.rudderSettings.reportingConfiguration)
      .setTo(n.nodeReportingConfiguration)
      .modify(_.properties)
      .setTo(Chunk.fromIterable(n.properties))
      .modify(_.rudderSettings.policyMode)
      .setTo(n.policyMode)
  }

}

final case class NodeFact(
    id:             NodeId,
    description:    Option[String],
    @jsonField("hostname")
    fqdn:           String,
    os:             OsDetails,
    machine:        MachineInfo,
    rudderSettings: RudderSettings,
    rudderAgent:    RudderAgent,
    properties:     Chunk[NodeProperty],
// what the point ? Derive from RudderAgent ? At least details.
//    managementTechnology:        Chunk[ManagementTechnology],
//    managementTechnologyDetails: ManagementTechnologyDetails,

    // inventory information part of minimal node info (node create api
    // the date on which the node was accepted/created by API
    creationDate:      DateTime,
    // the date on which the fact describing that node fact was processed
    factProcessedDate: DateTime,
    // the date on which information about that fact were generated on original system
    lastInventoryDate: Option[DateTime] = None,
    ipAddresses:       Chunk[IpAddress] = Chunk.empty,
    timezone:          Option[NodeTimezone] = None,

    // inventory details, optional

    ram:                  Option[MemorySize] = None,
    swap:                 Option[MemorySize] = None,
    archDescription:      Option[String] = None,
    accounts:             Chunk[String] = Chunk.empty,
    bios:                 Chunk[Bios] = Chunk.empty,
    controllers:          Chunk[Controller] = Chunk.empty,
    environmentVariables: Chunk[(String, String)] = Chunk.empty,
    fileSystems:          Chunk[FileSystem] = Chunk.empty,
    inputs:               Chunk[InputDevice] = Chunk.empty,
    localGroups:          Chunk[LocalGroup] = Chunk.empty,
    localUsers:           Chunk[LocalUser] = Chunk.empty,
    logicalVolumes:       Chunk[LogicalVolume] = Chunk.empty,
    memories:             Chunk[MemorySlot] = Chunk.empty,
    networks:             Chunk[Network] = Chunk.empty,
    physicalVolumes:      Chunk[PhysicalVolume] = Chunk.empty,
    ports:                Chunk[Port] = Chunk.empty,
    processes:            Chunk[Process] = Chunk.empty,
    processors:           Chunk[Processor] = Chunk.empty,
    slots:                Chunk[Slot] = Chunk.empty,
    software:             Chunk[SoftwareFact] = Chunk.empty,
    softwareUpdate:       Chunk[SoftwareUpdate] = Chunk.empty,
    sounds:               Chunk[Sound] = Chunk.empty,
    storages:             Chunk[Storage] = Chunk.empty,
    videos:               Chunk[Video] = Chunk.empty,
    vms:                  Chunk[VirtualMachine] = Chunk.empty
) {
  // we don't have a machine id anymore, by convention it's the node id prefixed by "machine-"
  def machineId = NodeFact.toMachineId(id)

  def isPolicyServer: Boolean = rudderSettings.kind != NodeKind.Node
  def isSystem:       Boolean = isPolicyServer
  def serverIps        = ipAddresses.map(_.inet).toList
  def customProperties = properties.collect {
    case p if (p.provider == Some(NodeProperty.customPropertyProvider)) => CustomProperty(p.name, p.jsonValue)
  }
}

final case class JsonOsDetails(
    @jsonField("type") osType: String, // this is "kernalName"
    name:                      String,
    version:                   String,
    fullName:                  String,
    kernelVersion:             String,
    servicePack:               Option[String],
    userDomain:                Option[String],
    registrationCompany:       Option[String],
    productKey:                Option[String],
    productId:                 Option[String]
)

final case class JsonAgentRunInterval(
    overrides:   String, // true, false, null (???)
    interval:    Int,
    startHour:   Int,
    startMinute: Int,
    splayHour:   Int,
    splayMinute: Int
)

final case class JSecurityToken(kind: String, token: String)

final case class JNodeProperty(name: String, value: ConfigValue, mode: Option[String], provider: Option[String])

object NodeFactSerialisation {

  // we need to have several object to avoid:
  // scalac: Error while emitting com/normation/rudder/facts/nodes/NodeFactSerialisation$
  // Method too large: com/normation/rudder/facts/nodes/NodeFactSerialisation$.<clinit> ()V

  import com.normation.inventory.domain.JsonSerializers.implicits.{decoderDateTime => _, encoderDateTime => _, _}

  object SimpleCodec {

    implicit val encoderDateTime:     JsonEncoder[DateTime]       = JsonEncoder.string.contramap(DateFormaterService.serialize)
    implicit val decoderDateTime:     JsonDecoder[DateTime]       =
      JsonDecoder.string.mapOrFail(d => DateFormaterService.parseDate(d).left.map(_.fullMsg))
    implicit val codecOptionDateTime: JsonCodec[Option[DateTime]] = DeriveJsonCodec.gen
    implicit val codecNodeId = JsonCodec.string.transform[NodeId](NodeId(_), _.value)
    implicit val codecJsonOsDetails: JsonCodec[JsonOsDetails] = DeriveJsonCodec.gen

    implicit val decoderOsDetails: JsonDecoder[OsDetails] = JsonDecoder[JsonOsDetails].map { jod =>
      val tpe     = ParseOSType.getType(jod.osType, jod.name, jod.fullName)
      val details =
        ParseOSType.getDetails(tpe, jod.fullName, new SVersion(jod.version), jod.servicePack, new SVersion(jod.kernelVersion))
      details match {
        case w: Windows =>
          w.copy(
            userDomain = jod.userDomain,
            registrationCompany = jod.registrationCompany,
            productKey = jod.productKey,
            productId = jod.productId
          )
        case other => other
      }
    }

    implicit val encoderOsDetails: JsonEncoder[OsDetails] = JsonEncoder[JsonOsDetails].contramap { od =>
      val jod = {
        JsonOsDetails(
          od.os.kernelName,
          od.os.name,
          od.version.value,
          od.fullName,
          od.kernelVersion.value,
          od.servicePack,
          None,
          None,
          None,
          None
        )
      }
      od match {
        case w: Windows =>
          jod.copy(
            userDomain = w.userDomain,
            registrationCompany = w.registrationCompany,
            productKey = w.productKey,
            productId = w.productId
          )
        case _ => jod
      }
    }

    implicit val codecJsonAgentRunInterval:   JsonCodec[JsonAgentRunInterval]   = DeriveJsonCodec.gen
    implicit val codecAgentRunInterval:       JsonCodec[AgentRunInterval]       = JsonCodec(
      JsonEncoder[JsonAgentRunInterval].contramap[AgentRunInterval] { ari =>
        JsonAgentRunInterval(
          ari.overrides.map(_.toString()).getOrElse("default"),
          ari.interval,
          ari.startMinute,
          ari.startHour,
          ari.splaytime / 60,
          ari.splaytime % 60
        )
      },
      JsonDecoder[JsonAgentRunInterval].map[AgentRunInterval] { jari =>
        val o = jari.overrides match {
          case "true"  => Some(true)
          case "false" => Some(false)
          case _       => None
        }
        AgentRunInterval(o, jari.interval, jari.startMinute, jari.startHour, jari.splayHour * 60 + jari.splayMinute)
      }
    )
    implicit val codecAgentReportingProtocol = JsonCodec.string.transformOrFail[AgentReportingProtocol](
      s => AgentReportingProtocol.parse(s).left.map(_.fullMsg),
      _.value
    )
    implicit val codecHeartbeatConfiguration: JsonCodec[HeartbeatConfiguration] = DeriveJsonCodec.gen
    implicit val codecReportingConfiguration: JsonCodec[ReportingConfiguration] = DeriveJsonCodec.gen

    implicit val codecNodeKind = JsonCodec.string.transformOrFail[NodeKind](NodeKind.parse, _.name)

    implicit val encoderOptionPolicyMode: JsonEncoder[Option[PolicyMode]] = JsonEncoder.string.contramap {
      case None       => "default"
      case Some(mode) => mode.name
    }
    implicit val decoderOptionPolicyMode: JsonDecoder[Option[PolicyMode]] = JsonDecoder[Option[String]].mapOrFail(opt => {
      opt match {
        case None            => Right(None)
        // we need to be able to set "default", for example to reset in clone
        case Some("default") => Right(None)
        case Some(s)         => PolicyMode.parse(s).left.map(_.fullMsg).map(Some(_))
      }
    })

    implicit val codecKeyStatus = JsonCodec.string.transform[KeyStatus](
      _ match {
        case "certified" => CertifiedKey
        case _           => UndefinedKey
      },
      _.value
    )

    implicit val codecInventoryStatus = JsonCodec.string.transformOrFail[InventoryStatus](
      s => {
        InventoryStatus(s) match {
          case None     => Left(s"'${s}' is not recognized as a node status. Expected: 'pending', 'accepted'")
          case Some(is) => Right(is)
        }
      },
      _.name
    )

    implicit val codecNodeState = JsonCodec.string.transformOrFail[NodeState](NodeState.parse, _.name)
    implicit val codecRudderSettings: JsonCodec[RudderSettings] = DeriveJsonCodec.gen
    implicit val codecAgentType    =
      JsonCodec.string.transformOrFail[AgentType](s => AgentType.fromValue(s).left.map(_.fullMsg), _.id)
    implicit val codecAgentVersion = JsonCodec.string.transform[AgentVersion](AgentVersion(_), _.value)
    implicit val codecVersion      = JsonCodec.string.transformOrFail[Version](ParseVersion.parse, _.toVersionString)
    implicit val codecJSecurityToken: JsonCodec[JSecurityToken] = DeriveJsonCodec.gen

    implicit val codecSecturityToken: JsonCodec[SecurityToken] = JsonCodec(
      JsonEncoder[JSecurityToken].contramap[SecurityToken](st => JSecurityToken(SecurityToken.kind(st), st.key)),
      JsonDecoder[JSecurityToken].mapOrFail[SecurityToken](jst => SecurityToken.token(jst.kind, jst.token))
    )

    implicit val codecAgentCapability = JsonCodec.string.transform[AgentCapability](AgentCapability(_), _.value)

    implicit val codecConfigValue: JsonCodec[ConfigValue] = JsonCodec(
      new JsonEncoder[ConfigValue] {
        override def unsafeEncode(a: ConfigValue, indent: Option[Int], out: Write): Unit = {
          out.write(
            a.render(ConfigRenderOptions.concise().setFormatted(indent.getOrElse(0) > 0))
          )
        }
      },
      JsonDecoder[Json].map(json => GenericProperty.fromZioJson(json))
    )

    implicit val codecJNodeProperty: JsonCodec[JNodeProperty] = DeriveJsonCodec.gen

    implicit val codecNodeProperty: JsonCodec[NodeProperty] = JsonCodec(
      JsonEncoder[JNodeProperty].contramap[NodeProperty](p =>
        JNodeProperty(p.name, p.value, p.inheritMode.map(_.value), p.provider.map(_.value))
      ),
      JsonDecoder[JNodeProperty].mapOrFail[NodeProperty](jp => {
        jp.mode match {
          case None    => Right(NodeProperty(jp.name, jp.value, None, jp.provider.map(PropertyProvider(_))))
          case Some(p) =>
            InheritMode.parseString(p) match {
              case Left(err) => Left(err.fullMsg)
              case Right(x)  => Right(NodeProperty(jp.name, jp.value, Some(x), jp.provider.map(PropertyProvider(_))))
            }
        }
      })
    )

    implicit val codecRudderAgent:  JsonCodec[RudderAgent]  = DeriveJsonCodec.gen
    implicit val codecIpAddress:    JsonCodec[IpAddress]    = JsonCodec.string.transform(IpAddress(_), _.inet)
    implicit val codecNodeTimezone: JsonCodec[NodeTimezone] = DeriveJsonCodec.gen
    implicit val codecMachineUuid  = JsonCodec.string.transform[MachineUuid](MachineUuid(_), _.value)
    implicit val codecMachineType  = JsonCodec.string.transform[MachineType](
      _ match {
        case UnknownMachineType.kind  => UnknownMachineType
        case PhysicalMachineType.kind => PhysicalMachineType
        case x                        => VirtualMachineType(VmType.parse(x))
      },
      _.kind
    )
    implicit val codecManufacturer = JsonCodec.string.transform[Manufacturer](Manufacturer(_), _.name)
    implicit val codecMachine:        JsonCodec[MachineInfo]    = DeriveJsonCodec.gen
    implicit val codecMemorySize:     JsonCodec[MemorySize]     = JsonCodec.long.transform[MemorySize](MemorySize(_), _.size)
    implicit val codecSVersion:       JsonCodec[SVersion]       = JsonCodec.string.transform[SVersion](new SVersion(_), _.value)
    implicit val codecSoftwareEditor: JsonCodec[SoftwareEditor] =
      JsonCodec.string.transform[SoftwareEditor](SoftwareEditor(_), _.name)
  }

  import SimpleCodec._

  implicit val codecBios: JsonCodec[Bios] = DeriveJsonCodec.gen

  implicit val codecController: JsonCodec[Controller] = DeriveJsonCodec.gen

  def recJsonToJValue(json: Json):     JValue       = {
    json match {
      case Obj(fields)   => JObject(fields.toList.map { case (k, v) => JField(k, recJsonToJValue(v)) })
      case Arr(elements) => JArray(elements.toList.map(recJsonToJValue))
      case Bool(value)   => JBool(value)
      case Str(value)    => JString(value)
      case Num(value)    => JDouble(value.doubleValue()) // this what is done in json-lift
      case Json.Null     => JNull
    }
  }
  def recJValueToJson(jvalue: JValue): Option[Json] = {
    jvalue match {
      case JsonAST.JNothing => None
      case JsonAST.JNull    => Some(Null)
      case JString(s)       => Some(Str(s))
      case JDouble(num)     => Some(Num(num))
      case JInt(num)        => Some(Num(num.longValue))
      case JBool(value)     => Some(Bool(value))
      case JObject(obj)     => Some(Obj(Chunk.fromIterable(obj).flatMap { case JField(k, v) => recJValueToJson(v).map(x => (k, x)) }))
      case JArray(arr)      => Some(Arr(Chunk.fromIterable(arr.flatMap(recJValueToJson(_)))))
    }
  }

  implicit val decoderJValue:       JsonDecoder[JValue]       = JsonDecoder[Option[Json]].map {
    case None    => JNothing
    case Some(v) => recJsonToJValue(v)
  }
  implicit val encoderJValue:       JsonEncoder[JValue]       = JsonEncoder[Option[Json]].contramap(recJValueToJson(_))
  implicit val codecCustomProperty: JsonCodec[CustomProperty] = DeriveJsonCodec.gen

  implicit val codecFileSystem:     JsonCodec[FileSystem]     = DeriveJsonCodec.gen
  implicit val codecInputDevice:    JsonCodec[InputDevice]    = DeriveJsonCodec.gen
  implicit val codecLocalGroup:     JsonCodec[LocalGroup]     = DeriveJsonCodec.gen
  implicit val codecLocalUser:      JsonCodec[LocalUser]      = DeriveJsonCodec.gen
  implicit val codecLogicalVolume:  JsonCodec[LogicalVolume]  = DeriveJsonCodec.gen
  implicit val codecMemorySlot:     JsonCodec[MemorySlot]     = DeriveJsonCodec.gen
  implicit val codecInetAddress:    JsonCodec[InetAddress]    = JsonCodec.string.transformOrFail(
    ip =>
      com.comcast.ip4s.IpAddress.fromString(ip) match {
        case None    => Left(s"Value '${ip}' can not be parsed as an IP address")
        case Some(x) => Right(x.toInetAddress)
      },
    com.comcast.ip4s.IpAddress.fromInetAddress(_).toString
  )
  implicit val codecNetwork:        JsonCodec[Network]        = DeriveJsonCodec.gen
  implicit val codecPhysicalVolume: JsonCodec[PhysicalVolume] = DeriveJsonCodec.gen
  implicit val codecPort:           JsonCodec[Port]           = DeriveJsonCodec.gen
  implicit val codecProcess:        JsonCodec[Process]        = DeriveJsonCodec.gen
  implicit val codecProcessor:      JsonCodec[Processor]      = DeriveJsonCodec.gen
  implicit val codecSlot:           JsonCodec[Slot]           = DeriveJsonCodec.gen
  implicit val codecSoftwareFact:   JsonCodec[SoftwareFact]   = DeriveJsonCodec.gen
  implicit val codecSound:          JsonCodec[Sound]          = DeriveJsonCodec.gen
  implicit val codecStorage:        JsonCodec[Storage]        = DeriveJsonCodec.gen
  implicit val codecVideo:          JsonCodec[Video]          = DeriveJsonCodec.gen
  implicit val codecVirtualMachine: JsonCodec[VirtualMachine] = DeriveJsonCodec.gen

  implicit val codecNodeFact: JsonCodec[NodeFact] = DeriveJsonCodec.gen

}
