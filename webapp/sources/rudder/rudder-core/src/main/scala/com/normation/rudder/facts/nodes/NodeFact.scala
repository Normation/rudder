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

import cats.syntax.traverse.*
import com.normation.box.*
import com.normation.errors.Inconsistency
import com.normation.errors.PureResult
import com.normation.errors.RudderError
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.inventory.domain.*
import com.normation.inventory.domain.Version as SVersion
import com.normation.rudder.apidata.NodeDetailLevel
import com.normation.rudder.domain.eventlog
import com.normation.rudder.domain.logger.PolicyGenerationLogger
import com.normation.rudder.domain.nodes.MachineInfo
import com.normation.rudder.domain.nodes.Node
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.domain.nodes.NodeKeyHash
import com.normation.rudder.domain.nodes.NodeKind
import com.normation.rudder.domain.nodes.NodeState
import com.normation.rudder.domain.policies.PolicyMode
import com.normation.rudder.domain.properties.GenericProperty
import com.normation.rudder.domain.properties.InheritMode
import com.normation.rudder.domain.properties.NodeProperty
import com.normation.rudder.domain.properties.PropertyProvider
import com.normation.rudder.domain.servers.Srv
import com.normation.rudder.reports.*
import com.normation.rudder.tenants.TenantId
import com.normation.utils.ParseVersion
import com.normation.utils.Version
import com.normation.zio.*
import com.softwaremill.quicklens.*
import com.typesafe.config.ConfigRenderOptions
import com.typesafe.config.ConfigValue
import enumeratum.Enum
import enumeratum.EnumEntry
import io.scalaland.chimney.PartialTransformer
import io.scalaland.chimney.Transformer
import io.scalaland.chimney.partial.Result
import io.scalaland.chimney.syntax.*
import net.liftweb.common.Box
import net.liftweb.common.EmptyBox
import net.liftweb.common.Full
import net.liftweb.json.JsonAST
import net.liftweb.json.JsonAST.*
import net.liftweb.json.JsonAST.JValue
import org.joda.time.DateTime
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.json.ast.Json.*
import zio.json.internal.Write

/**
 * A node fact is the node with all inventory info, settings, properties, etc, in a layout that is similar to what API
 * return about a node.
 * It only contains facts and not computed things about the node:
 * - no compliance scoring
 * - no resolved properties (for ex inherited ones)
 */
final case class IpAddress(inet: String) {
  def isLocalhostIPv4IPv6: Boolean = inet == "127.0.0.1" || inet == "0:0:0:0:0:0:0:1"
}
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
  def toAgentInfo: AgentInfo = AgentInfo(agentType, Some(version), securityToken, capabilities.toSet)
}

// A security token for now is just a a list of tags denoting tenants
// That security tag is not exposed in proxy service
final case class SecurityTag(tenants: Chunk[TenantId])

// default serialization for security tag. Be careful, changing that impacts external APIs.
object SecurityTag {
  implicit val codecSecurityTag: JsonCodec[SecurityTag] = DeriveJsonCodec.gen
}

// rudder settings for that node
final case class RudderSettings(
    keyStatus:              KeyStatus,
    reportingConfiguration: ReportingConfiguration,
    kind:                   NodeKind,
    status:                 InventoryStatus,
    state:                  NodeState,
    policyMode:             Option[PolicyMode],
    policyServerId:         NodeId,
    security:               Option[SecurityTag] // optional for backward compat. None means "no tenant"
) {
  def isPolicyServer: Boolean = kind != NodeKind.Node
}

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
      SoftwareUuid(""), // here, we don't know the uuid. We need a way to mark that it's not valid and don't risk using a bad one
      Some(sf.name),
      None,
      Some(sf.version),
      sf.publisher.map(SoftwareEditor.apply),
      None,
      sf.licenseName.map(l => License(l, sf.licenseDescription, sf.productId, sf.productKey, sf.oem, sf.expirationDate)),
      sf.sourceName,
      sf.sourceVersion
    )
  }

  def fromSoftware(s: Software): Option[SoftwareFact] = {
    import NodeFact.*
    s.toFact
  }
}

object MinimalNodeFactInterface {

  /*
   * Check if the node fact are the same.
   * Sameness is not equality. It does not look for:
   * - inventory date
   * - fact processing time
   * - acceptation time
   * - order in any collection
   *
   * Having a hashcode on node fact would be inefficient,
   * most node fact are never compared for sameness.
   * Same is heavy, don't use it often !
   */
  def same(a: MinimalNodeFactInterface, b: MinimalNodeFactInterface): Boolean = {
    def eq[A](accessor: MinimalNodeFactInterface => A):                                           Boolean = {
      accessor(a) == accessor(b)
    }
    def compare[A, B: Ordering](accessor: MinimalNodeFactInterface => Chunk[A])(orderOn: A => B): Boolean = {
      accessor(a).size == accessor(b).size &&
      accessor(a).sortBy(orderOn) == accessor(b).sortBy(orderOn)
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
    compare(_.softwareUpdate)(_.name)
  }

  def isSystem(node: MinimalNodeFactInterface): Boolean = {
    node.rudderSettings.kind != NodeKind.Node
  }

  def ipAddresses(node: MinimalNodeFactInterface): List[String] = {
    node.ipAddresses.map(_.inet).toList
  }

  def toNode(node: MinimalNodeFactInterface): Node = Node(
    node.id,
    node.fqdn,
    "", // description
    node.rudderSettings.state,
    isSystem(node),
    isSystem((node)),
    node.creationDate,
    node.rudderSettings.reportingConfiguration,
    node.properties.toList,
    node.rudderSettings.policyMode,
    node.rudderSettings.security
  )

  def toNodeInfo(node: MinimalNodeFactInterface, ram: Option[MemorySize], archDescription: Option[String]): NodeInfo = NodeInfo(
    toNode(node),
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
    archDescription,
    ram,
    node.timezone
  )

  def toSrv(node: MinimalNodeFactInterface): Srv = {
    Srv(
      node.id,
      node.rudderSettings.status,
      node.fqdn,
      node.os.os.kernelName,
      node.os.os.name,
      node.os.fullName,
      ipAddresses(node),
      node.creationDate,
      isSystem(node)
    )
  }

  def toNodeSummary(node: MinimalNodeFactInterface): NodeSummary = {
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
  def same(nfa: NodeFact, nfb: NodeFact)(implicit attrs: SelectFacts): Boolean = {

    val a = SelectFacts.mask(nfa)
    val b = SelectFacts.mask(nfb)

    def eq[A](accessor: NodeFact => A):                                           Boolean = {
      accessor(a) == accessor(b)
    }
    // compare two chunk sameness
    def compare[A, B: Ordering](accessor: NodeFact => Chunk[A])(orderOn: A => B): Boolean = {
      accessor(a).size == accessor(b).size &&
      accessor(a).sortBy(orderOn) == accessor(b).sortBy(orderOn)
    }

    MinimalNodeFactInterface.same(a, b) &&
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

  def toMachineId(nodeId: NodeId): MachineUuid = MachineUuid("machine-for-" + nodeId.value)

  implicit class IterableToChunk[A](it: Iterable[A]) {
    def toChunk: Chunk[A] = Chunk.fromIterable(it)
  }

  implicit class EitherToChunk[A](either: Either[?, A]) {
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
    def mask[A](s: SelectFactConfig[A]): NodeFact = {
      s.mode match {
        case SelectMode.Retrieve => node
        case SelectMode.Ignore   => s.modify.setTo(s.zero)(node)
      }
    }

    def maskWith(attrs: SelectFacts): NodeFact = {
      node
        .mask(attrs.swap)
        .mask(attrs.accounts)
        .mask(attrs.bios)
        .mask(attrs.controllers)
        .mask(attrs.environmentVariables)
        .mask(attrs.inputs)
        .mask(attrs.fileSystems)
        .mask(attrs.localGroups)
        .mask(attrs.localUsers)
        .mask(attrs.logicalVolumes)
        .mask(attrs.memories)
        .mask(attrs.networks)
        .mask(attrs.ports)
        .mask(attrs.physicalVolumes)
        .mask(attrs.processes)
        .mask(attrs.processors)
        .mask(attrs.slots)
        .mask(attrs.software)
        .mask(attrs.sounds)
        .mask(attrs.storages)
        .mask(attrs.videos)
        .mask(attrs.vms)
    }

    def toCore: CoreNodeFact = CoreNodeFact.fromMininal(node)

    def toNode: Node = MinimalNodeFactInterface.toNode(node)

    def toNodeInfo: NodeInfo = MinimalNodeFactInterface.toNodeInfo(node, node.ram, node.archDescription)

    def toSrv: Srv = MinimalNodeFactInterface.toSrv(node)

    def toNodeSummary: NodeSummary = MinimalNodeFactInterface.toNodeSummary(node)

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
   * The updates parameter is used only in the case where we don't have the full inventory.
   */
  def fromCompat(
      nodeInfo:  NodeInfo,
      inventory: Either[InventoryStatus, FullInventory],
      software:  Seq[Software],
      updates:   Option[Chunk[SoftwareUpdate]]
  ): NodeFact = {
    NodeFact(
      nodeInfo.id,
      nodeInfo.description.strip() match {
        case "" => None
        case x  => Some(nodeInfo.description)
      },
      nodeInfo.hostname,
      nodeInfo.osDetails,
      nodeInfo.machine.getOrElse(inventory match {
        case Right(FullInventory(_, Some(m))) =>
          MachineInfo(m.id, m.machineType, m.systemSerialNumber, m.manufacturer)
        case _                                => // in that case, we just don't have any info on the matchine, derive a false id from node id
          MachineInfo(NodeFact.toMachineId(nodeInfo.id), UnknownMachineType, None, None)
      }),
      RudderSettings(
        nodeInfo.keyStatus,
        nodeInfo.nodeReportingConfiguration,
        nodeInfo.nodeKind,
        inventory.fold(identity, _.node.main.status),
        nodeInfo.state,
        nodeInfo.policyMode,
        nodeInfo.policyServerId,
        nodeInfo.node.securityTag
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
      nodeInfo.archDescription,
      nodeInfo.ram,
      inventory.toOption.flatMap(_.node.swap),
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
      inventory match {
        case Left(_)    => updates.getOrElse(Chunk())
        case Right(inv) => inv.node.softwareUpdates.toChunk
      },
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
      NodeId("root"),
      None
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
        // we should have the machine in a new full inventory, else generate an uuid for the unknown one
        inventory.machine.map(_.id).getOrElse(NodeFact.toMachineId(inventory.node.main.id)),
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

  def fromMinimal(a: MinimalNodeFactInterface): NodeFact = {
    a match {
      case x: NodeFact => x
      case _ =>
        NodeFact(
          a.id,
          a.description,
          a.fqdn,
          a.os,
          a.machine,
          a.rudderSettings,
          a.rudderAgent,
          a.properties,
          a.creationDate,
          a.factProcessedDate,
          a.lastInventoryDate,
          a.ipAddresses,
          a.timezone,
          a.archDescription,
          a.ram,
          softwareUpdate = a.softwareUpdate
        )
    }
  }

  /*
   * Update all inventory parts from that node fact.
   * The inventory parts are overridden, there is no merge
   * NOTICE: status is ignored !
   */
  def updateInventory(node: NodeFact, inventory: Inventory): NodeFact = {
    updateFullInventory(node, FullInventory(inventory.node, Some(inventory.machine)), Some(inventory.applications))
  }

  def updateInventory(core: CoreNodeFact, inventory: Inventory): NodeFact = {
    updateFullInventory(
      NodeFact.fromMinimal(core),
      FullInventory(inventory.node, Some(inventory.machine)),
      Some(inventory.applications)
    )
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
        m.id,
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
      .setTo(Chunk.fromIterable(inventory.node.serverIps.map(IpAddress.apply)))
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
    import com.softwaremill.quicklens.*
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

  def debugDiff(n1: NodeFact, n2: NodeFact, attrs: SelectFacts): String = {
    def diff[A](a: A, b: A): String = {
      if (a == b) a.toString
      else {
        s"""
           |  -${a.toString}
           |  +${b.toString}""".stripMargin
      }
    }
    val pad     = "\n * "
    val ignored = new StringBuilder().append(pad).append("ignored: ")
    val sb      = new StringBuilder(s"""id:${diff(n1.id.value, n2.id.value)}
                                  | * description:${diff(n1.description, n2.description)}
                                  | * fqdn:${diff(n1.fqdn, n2.fqdn)}
                                  | * os:${diff(n1.os, n2.os)}
                                  | * machine:${diff(n1.machine, n2.machine)}
                                  | * rudderSettings:${diff(n1.rudderSettings, n2.rudderSettings)}
                                  | * rudderAgent:${diff(n1.rudderAgent, n2.rudderAgent)}
                                  | * properties:${diff(n1.properties.toList.sortBy(_.name), n2.properties.toList.sortBy(_.name))}
                                  | * creationDate:${diff(n1.creationDate, n2.creationDate)}
                                  | * factProcessedDate:${diff(n1.factProcessedDate, n2.factProcessedDate)}
                                  | * lastInventoryDate:${diff(n1.lastInventoryDate, n2.lastInventoryDate)}
                                  | * ipAddresses:${diff(n1.ipAddresses, n2.ipAddresses)}
                                  | * timezone:${diff(n1.timezone, n2.timezone)}
                                  | * archDescription:${diff(n1.archDescription, n2.archDescription)}
                                  | * ram:${diff(n1.ram, n2.ram)}""".stripMargin)

    attrs.swap.logDiff(n1, n2, sb, pad, ignored, "")
    attrs.accounts.logDiff(n1, n2, sb, pad, ignored, ",")
    attrs.bios.logDiff(n1, n2, sb, pad, ignored, ",")
    attrs.controllers.logDiff(n1, n2, sb, pad, ignored, ",")
    attrs.environmentVariables.logDiff(n1, n2, sb, pad, ignored, ",")
    attrs.fileSystems.logDiff(n1, n2, sb, pad, ignored, ",")
    attrs.inputs.logDiff(n1, n2, sb, pad, ignored, ",")
    attrs.localGroups.logDiff(n1, n2, sb, pad, ignored, ",")
    attrs.localUsers.logDiff(n1, n2, sb, pad, ignored, ",")
    attrs.logicalVolumes.logDiff(n1, n2, sb, pad, ignored, ",")
    attrs.memories.logDiff(n1, n2, sb, pad, ignored, ",")
    attrs.networks.logDiff(n1, n2, sb, pad, ignored, ",")
    attrs.physicalVolumes.logDiff(n1, n2, sb, pad, ignored, ",")
    attrs.ports.logDiff(n1, n2, sb, pad, ignored, ",")
    attrs.processes.logDiff(n1, n2, sb, pad, ignored, ",")
    attrs.processors.logDiff(n1, n2, sb, pad, ignored, ",")
    attrs.slots.logDiff(n1, n2, sb, pad, ignored, ",")
    attrs.software.logDiff(n1, n2, sb, pad, ignored, ",")
    attrs.sounds.logDiff(n1, n2, sb, pad, ignored, ",")
    attrs.storages.logDiff(n1, n2, sb, pad, ignored, ",")
    attrs.videos.logDiff(n1, n2, sb, pad, ignored, ",")
    attrs.vms.logDiff(n1, n2, sb, pad, ignored, ",")

    sb.append(ignored.toString()).toString()
  }
}

trait MinimalNodeFactInterface {
  def id:                NodeId
  def description:       Option[String]
  def fqdn:              String
  def os:                OsDetails
  def machine:           MachineInfo
  def rudderSettings:    RudderSettings
  def rudderAgent:       RudderAgent
  def properties:        Chunk[NodeProperty]
  def creationDate:      DateTime
  def factProcessedDate: DateTime
  def lastInventoryDate: Option[DateTime]
  def ipAddresses:       Chunk[IpAddress]
  def timezone:          Option[NodeTimezone]
  def archDescription:   Option[String]
  def ram:               Option[MemorySize]
  def softwareUpdate:    Chunk[SoftwareUpdate]

  // this is copied from NodeInfo. Not sure if there is a better way for now.
  /**
   * Get a digest of the key in the proprietary CFEngine digest format. It is
   * formated as expected by CFEngine authentication module, i.e with the
   * "MD5=" prefix for community agent (resp. "SHA=") prefix for enterprise agent).
   */
  lazy val keyHashCfengine: String = {

    def formatDigest(digest: Box[String], algo: String, tokenType: SecurityToken): String = {
      digest match {
        case Full(hash) => s"${algo}=${hash}"
        case eb: EmptyBox =>
          val msgForToken = tokenType match {
            case _: PublicKey   => "of CFEngine public key for"
            case _: Certificate => "for certificate of"
          }
          val e           = eb ?~! s"Error when trying to get the CFEngine-${algo} digest ${msgForToken} node '${fqdn}' (${id.value})"
          PolicyGenerationLogger.error(e.messageChain)
          ""
      }
    }

    (rudderAgent.agentType, rudderAgent.securityToken) match {

      case (AgentType.CfeCommunity, key: PublicKey) =>
        formatDigest(NodeKeyHash.getCfengineMD5Digest(key).toBox, "MD5", key)

      case (AgentType.CfeEnterprise, key: PublicKey) =>
        formatDigest(NodeKeyHash.getCfengineSHA256Digest(key).toBox, "SHA", key)

      case (AgentType.CfeCommunity, cert: Certificate) =>
        formatDigest(NodeKeyHash.getCfengineMD5CertDigest(cert).toBox, "MD5", cert)

      case (AgentType.CfeEnterprise, cert: Certificate) =>
        formatDigest(NodeKeyHash.getCfengineSHA256CertDigest(cert).toBox, "SHA", cert)

      case (AgentType.Dsc, _) =>
        PolicyGenerationLogger.info(
          s"Node '${fqdn}' (${id.value}) is a Windows node and a we do not know how to generate a hash yet"
        )
        ""
    }
  }

  /**
   * Get a base64 sha-256 digest (of the DER byte sequence) of the key.
   *
   * This method never fails, and if we are not able to parse
   * the store key, or if no key is store, it return an empty
   * string. Logs are used to track problems.
   *
   */
  lazy val keyHashBase64Sha256: String = {
    rudderAgent.securityToken match {
      case publicKey: PublicKey   =>
        NodeKeyHash.getB64Sha256Digest(publicKey).either.runNow match {
          case Right(hash) =>
            hash
          case Left(e)     =>
            PolicyGenerationLogger.error(
              s"Error when trying to get the sha-256 digest of CFEngine public key for node '${fqdn}' (${id.value}): ${e.fullMsg}"
            )
            ""
        }
      case cert:      Certificate =>
        NodeKeyHash.getB64Sha256Digest(cert).either.runNow match {
          case Right(hash) => hash
          case Left(e)     =>
            PolicyGenerationLogger.error(
              s"Error when trying to get the sha-256 digest of Certificate for node '${fqdn}' (${id.value}): ${e.fullMsg}"
            )
            ""
        }
    }
  }

}

/*
 * Subset of commonly used properties of node fact, same as NodeInfo.
 * It should have exactly the same fields as MinimalNodeFactInterface
 */
final case class CoreNodeFact(
    id:                NodeId,
    description:       Option[String],
    @jsonField("hostname")
    fqdn:              String,
    os:                OsDetails,
    machine:           MachineInfo,
    rudderSettings:    RudderSettings,
    rudderAgent:       RudderAgent,
    properties:        Chunk[NodeProperty],
    creationDate:      DateTime,
    factProcessedDate: DateTime,
    lastInventoryDate: Option[DateTime] = None,
    ipAddresses:       Chunk[IpAddress] = Chunk.empty,
    timezone:          Option[NodeTimezone] = None,
    archDescription:   Option[String] = None,
    ram:               Option[MemorySize] = None,
    softwareUpdate:    Chunk[SoftwareUpdate] = Chunk.empty
) extends MinimalNodeFactInterface

object CoreNodeFact {

  def updateNode(node: CoreNodeFact, n: Node): CoreNodeFact = {
    import com.softwaremill.quicklens.*
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

  def fromMininal(a: MinimalNodeFactInterface): CoreNodeFact = {
    a match {
      case c: CoreNodeFact => c
      case _ =>
        CoreNodeFact(
          a.id,
          a.description,
          a.fqdn,
          a.os,
          a.machine,
          a.rudderSettings,
          a.rudderAgent,
          a.properties,
          a.creationDate,
          a.factProcessedDate,
          a.lastInventoryDate,
          a.ipAddresses,
          a.timezone,
          a.archDescription,
          a.ram,
          a.softwareUpdate
        )
    }
  }

  def same(a: CoreNodeFact, b: CoreNodeFact): Boolean = {
    MinimalNodeFactInterface.same(a, b) &&
    a.archDescription == b.archDescription &&
    a.ram == b.ram
  }

  implicit class ToCompat(node: CoreNodeFact) {

    def toNode: Node = MinimalNodeFactInterface.toNode(node)

    def toNodeInfo: NodeInfo = MinimalNodeFactInterface.toNodeInfo(node, node.ram, node.archDescription)

    def toSrv: Srv = MinimalNodeFactInterface.toSrv(node)

    def toNodeSummary: NodeSummary = MinimalNodeFactInterface.toNodeSummary(node)
  }
}

sealed trait SelectMode
object SelectMode {
  case object Ignore   extends SelectMode
  case object Retrieve extends SelectMode
}

case class SelectFactConfig[A](
    name:     String,
    mode:     SelectMode,
    selector: NodeFact => A,
    modify:   PathLazyModify[NodeFact, A],
    zero:     A
) {
  // copy helper for fluent api
  def toIgnore:   SelectFactConfig[A] = this.copy(mode = SelectMode.Ignore)
  def toRetrieve: SelectFactConfig[A] = this.copy(mode = SelectMode.Retrieve)
  def invertMode: SelectFactConfig[A] = if (this.mode == SelectMode.Ignore) toRetrieve else toIgnore

  override def toString: String = this.mode.toString

  def merge(other: SelectFactConfig[A], fix: SelectMode): SelectFactConfig[A] = {
    (this.mode, other.mode) match {
      case (a, b) if (a == b) => this
      case _                  => this.copy(mode = fix)
    }
  }

  def max(other: SelectFactConfig[A]): SelectFactConfig[A] = merge(other, SelectMode.Retrieve)

  def min(other: SelectFactConfig[A]): SelectFactConfig[A] = merge(other, SelectMode.Ignore)

  private def optLog(n: NodeFact): Option[String] = {
    mode match {
      case SelectMode.Ignore   => None
      case SelectMode.Retrieve => Some(selector(n).toString) // should be debugString and have a trait for that
    }
  }
  def log(n: NodeFact):            String         = {
    s"${name}: ${optLog(n).getOrElse("ignored")}"
  }

  // with a pair of StringBuilder
  def log(n: NodeFact, out: StringBuilder, pad: String, ignored: StringBuilder, padIgnore: String): Unit = {
    optLog(n) match {
      case Some(x) => out.append(pad).append(name).append(": ").append(x)
      case None    => ignored.append(padIgnore).append(name)
    }
  }

  private def optLogDiff(n1: NodeFact, n2: NodeFact, pad: String): Option[String] = {
    mode match {
      case SelectMode.Ignore   => None
      case SelectMode.Retrieve =>
        (selector(n1), selector(n2)) match {
          case (a, b) if (a == b) => Some(a.toString)
          case (a, b)             =>
            Some(s"""${pad} - ${a.toString}${pad} + ${b.toString}""")
        }
    }
  }

  def logDiff(n1: NodeFact, n2: NodeFact, out: StringBuilder, pad: String, ignored: StringBuilder, padIgnore: String): Unit = {
    optLogDiff(n1, n2, pad) match {
      case Some(x) => out.append(pad).append(name).append(": ").append(x)
      case None    => ignored.append(padIgnore).append(name)
    }
  }
}

case class SelectFacts(
    swap:                 SelectFactConfig[Option[MemorySize]],
    accounts:             SelectFactConfig[Chunk[String]],
    bios:                 SelectFactConfig[Chunk[Bios]],
    controllers:          SelectFactConfig[Chunk[Controller]],
    environmentVariables: SelectFactConfig[Chunk[(String, String)]],
    fileSystems:          SelectFactConfig[Chunk[FileSystem]],
    inputs:               SelectFactConfig[Chunk[InputDevice]],
    localGroups:          SelectFactConfig[Chunk[LocalGroup]],
    localUsers:           SelectFactConfig[Chunk[LocalUser]],
    logicalVolumes:       SelectFactConfig[Chunk[LogicalVolume]],
    memories:             SelectFactConfig[Chunk[MemorySlot]],
    networks:             SelectFactConfig[Chunk[Network]],
    physicalVolumes:      SelectFactConfig[Chunk[PhysicalVolume]],
    ports:                SelectFactConfig[Chunk[Port]],
    processes:            SelectFactConfig[Chunk[Process]],
    processors:           SelectFactConfig[Chunk[Processor]],
    slots:                SelectFactConfig[Chunk[Slot]],
    software:             SelectFactConfig[Chunk[SoftwareFact]],
    sounds:               SelectFactConfig[Chunk[Sound]],
    storages:             SelectFactConfig[Chunk[Storage]],
    videos:               SelectFactConfig[Chunk[Video]],
    vms:                  SelectFactConfig[Chunk[VirtualMachine]]
) {
  def debugString: String =
    this.productElementNames.zip(this.productIterator).map { case (a, b) => s"${a}: ${b.toString}" }.mkString(", ")

  // get the merge of that fact with an other, keeping the most change
  def merge(other: SelectFacts, fix: SelectMode): SelectFacts = SelectFacts(
    swap.merge(other.swap, fix),
    accounts.merge(other.accounts, fix),
    bios.merge(other.bios, fix),
    controllers.merge(other.controllers, fix),
    environmentVariables.merge(other.environmentVariables, fix),
    fileSystems.merge(other.fileSystems, fix),
    inputs.merge(other.inputs, fix),
    localGroups.merge(other.localGroups, fix),
    localUsers.merge(other.localUsers, fix),
    logicalVolumes.merge(other.logicalVolumes, fix),
    memories.merge(other.memories, fix),
    networks.merge(other.networks, fix),
    physicalVolumes.merge(other.physicalVolumes, fix),
    ports.merge(other.ports, fix),
    processes.merge(other.processes, fix),
    processors.merge(other.processors, fix),
    slots.merge(other.slots, fix),
    software.merge(other.software, fix),
    sounds.merge(other.sounds, fix),
    storages.merge(other.storages, fix),
    videos.merge(other.videos, fix),
    vms.merge(other.vms, fix)
  )

  def max(other: SelectFacts): SelectFacts = merge(other, SelectMode.Retrieve)
  def min(other: SelectFacts): SelectFacts = merge(other, SelectMode.Ignore)
}

sealed trait SelectNodeStatus { def name: String }
object SelectNodeStatus       {
  object Pending  extends SelectNodeStatus { val name = PendingInventory.name  }
  object Accepted extends SelectNodeStatus { val name = AcceptedInventory.name }
  object Any      extends SelectNodeStatus { val name = "any"                  }
}

object SelectFacts {

  implicit class Invert(c: SelectFacts) {
    def invert: SelectFacts = {
      SelectFacts(
        c.swap.invertMode,
        c.accounts.invertMode,
        c.bios.invertMode,
        c.controllers.invertMode,
        c.environmentVariables.invertMode,
        c.fileSystems.invertMode,
        c.inputs.invertMode,
        c.localGroups.invertMode,
        c.localUsers.invertMode,
        c.logicalVolumes.invertMode,
        c.memories.invertMode,
        c.networks.invertMode,
        c.physicalVolumes.invertMode,
        c.ports.invertMode,
        c.processes.invertMode,
        c.processors.invertMode,
        c.slots.invertMode,
        c.software.invertMode,
        c.sounds.invertMode,
        c.storages.invertMode,
        c.videos.invertMode,
        c.vms.invertMode
      )
    }
  }

  // format: off
  // there's perhaps a better way to do that, but `shrug` don't know about it
  val none: SelectFacts = SelectFacts(
    SelectFactConfig("swap", SelectMode.Ignore,_.swap, modifyLens[NodeFact](_.swap), None),
    SelectFactConfig("accounts", SelectMode.Ignore,_.accounts, modifyLens[NodeFact](_.accounts), Chunk.empty),
    SelectFactConfig("bios", SelectMode.Ignore,_.bios, modifyLens[NodeFact](_.bios), Chunk.empty),
    SelectFactConfig("controllers", SelectMode.Ignore,_.controllers, modifyLens[NodeFact](_.controllers), Chunk.empty),
    SelectFactConfig("environment", SelectMode.Ignore,_.environmentVariables, modifyLens[NodeFact](_.environmentVariables), Chunk.empty),
    SelectFactConfig("file", SelectMode.Ignore,_.fileSystems, modifyLens[NodeFact](_.fileSystems), Chunk.empty),
    SelectFactConfig("inputs", SelectMode.Ignore,_.inputs, modifyLens[NodeFact](_.inputs), Chunk.empty),
    SelectFactConfig("local", SelectMode.Ignore,_.localGroups, modifyLens[NodeFact](_.localGroups), Chunk.empty),
    SelectFactConfig("local", SelectMode.Ignore,_.localUsers, modifyLens[NodeFact](_.localUsers), Chunk.empty),
    SelectFactConfig("logical", SelectMode.Ignore,_.logicalVolumes, modifyLens[NodeFact](_.logicalVolumes), Chunk.empty),
    SelectFactConfig("memories", SelectMode.Ignore,_.memories, modifyLens[NodeFact](_.memories), Chunk.empty),
    SelectFactConfig("networks", SelectMode.Ignore,_.networks, modifyLens[NodeFact](_.networks), Chunk.empty),
    SelectFactConfig("physical", SelectMode.Ignore,_.physicalVolumes, modifyLens[NodeFact](_.physicalVolumes), Chunk.empty),
    SelectFactConfig("ports", SelectMode.Ignore,_.ports, modifyLens[NodeFact](_.ports), Chunk.empty),
    SelectFactConfig("processes", SelectMode.Ignore,_.processes, modifyLens[NodeFact](_.processes), Chunk.empty),
    SelectFactConfig("processors", SelectMode.Ignore,_.processors, modifyLens[NodeFact](_.processors), Chunk.empty),
    SelectFactConfig("slots", SelectMode.Ignore,_.slots, modifyLens[NodeFact](_.slots), Chunk.empty),
    SelectFactConfig("software", SelectMode.Ignore,_.software, modifyLens[NodeFact](_.software), Chunk.empty),
    SelectFactConfig("sounds", SelectMode.Ignore,_.sounds, modifyLens[NodeFact](_.sounds), Chunk.empty),
    SelectFactConfig("storages", SelectMode.Ignore,_.storages, modifyLens[NodeFact](_.storages), Chunk.empty),
    SelectFactConfig("videos", SelectMode.Ignore,_.videos, modifyLens[NodeFact](_.videos), Chunk.empty),
    SelectFactConfig("vms", SelectMode.Ignore,_.vms, modifyLens[NodeFact](_.vms), Chunk.empty)
  )

  val all: SelectFacts = SelectFacts(
    none.swap.toRetrieve,
    none.accounts.toRetrieve,
    none.bios.toRetrieve,
    none.controllers.toRetrieve,
    none.environmentVariables.toRetrieve,
    none.fileSystems.toRetrieve,
    none.inputs.toRetrieve,
    none.localGroups.toRetrieve,
    none.localUsers.toRetrieve,
    none.logicalVolumes.toRetrieve,
    none.memories.toRetrieve,
    none.networks.toRetrieve,
    none.physicalVolumes.toRetrieve,
    none.ports.toRetrieve,
    none.processes.toRetrieve,
    none.processors.toRetrieve,
    none.slots.toRetrieve,
    none.software.toRetrieve,
    none.sounds.toRetrieve,
    none.storages.toRetrieve,
    none.videos.toRetrieve,
    none.vms.toRetrieve
  )
  // format: on

  val softwareOnly: SelectFacts = none.copy(software = none.software.toRetrieve)
  val noSoftware:   SelectFacts = all.copy(software = all.software.toIgnore)
  val default:      SelectFacts = all.copy(processes = all.processes.toIgnore, software = all.software.toIgnore)

  // inventory elements, not carring for software
  def retrieveInventory(attrs: SelectFacts): Boolean = {
    !(attrs.copy(software = SelectFacts.none.software) == SelectFacts.none)
  }

  def fromNodeDetailLevel(level: NodeDetailLevel): SelectFacts = {
    // change from none to get
    def toGet[A](s: SelectFactConfig[A], switch: Boolean): SelectFactConfig[A] = {
      if (switch) s.toRetrieve
      else s
    }
    SelectFacts(
      toGet(none.swap, level.fields.contains("fileSystems")),
      toGet(none.accounts, level.fields.contains("accounts")),
      toGet(none.bios, level.fields.contains("bios")),
      toGet(none.controllers, level.fields.contains("controllers")),
      toGet(none.environmentVariables, level.fields.contains("environmentVariables")),
      toGet(none.fileSystems, level.fields.contains("fileSystems")),
      none.inputs,
      none.localGroups,
      none.localUsers,
      none.logicalVolumes,
      toGet(none.memories, level.fields.contains("memories")),
      toGet(none.networks, level.fields.contains("networkInterfaces")),
      none.physicalVolumes,
      toGet(none.ports, level.fields.contains("ports")),
      toGet(none.processes, level.fields.contains("processes")),
      toGet(none.processors, level.fields.contains("processors")),
      toGet(none.slots, level.fields.contains("slots")),
      toGet(none.software, level.fields.contains("software")),
      toGet(none.sounds, level.fields.contains("sound")),
      toGet(none.storages, level.fields.contains("storage")),
      toGet(none.videos, level.fields.contains("videos")),
      toGet(none.vms, level.fields.contains("virtualMachines"))
    )
  }

  // semantic: having a new node fact, keep old fact info if the select mode says "ignore"
  // and keep new fact if it says "retrieve"
  def merge(newFact: NodeFact, existing: Option[NodeFact])(implicit attrs: SelectFacts): NodeFact = {
    implicit class NodeFactMerge(newFact: NodeFact) {

      // keep newFact
      def update[A](config: SelectFactConfig[A])(implicit oldFact: NodeFact) = {
        config.mode match {
          case SelectMode.Retrieve => // keep new fact
            newFact
          case SelectMode.Ignore   => // get info from old fact
            config.modify.setTo(config.selector(oldFact))(newFact)
        }
      }
    }

    existing match {
      case None     => newFact
      // we assume that all the properties not in SelectFacts are up-to-date, so we start with newFact and only update
      case Some(of) =>
        implicit val oldFact = of

        newFact
          .update(attrs.swap)
          .update(attrs.accounts)
          .update(attrs.bios)
          .update(attrs.controllers)
          .update(attrs.environmentVariables)
          .update(attrs.inputs)
          .update(attrs.fileSystems)
          .update(attrs.localGroups)
          .update(attrs.localUsers)
          .update(attrs.logicalVolumes)
          .update(attrs.memories)
          .update(attrs.networks)
          .update(attrs.ports)
          .update(attrs.physicalVolumes)
          .update(attrs.processes)
          .update(attrs.processors)
          .update(attrs.slots)
          .update(attrs.software)
          .update(attrs.sounds)
          .update(attrs.storages)
          .update(attrs.videos)
          .update(attrs.vms)
    }
  }

  // given a core node fact, add attributes from an other fact based on what attrs says
  def mergeCore(cnf: CoreNodeFact, fact: NodeFact)(implicit attrs: SelectFacts): NodeFact = {
    // from a implementation point of view, it's the opposite of merge WRT SelectFacts
    merge(NodeFact.fromMinimal(cnf), Some(fact))(attrs.invert)
  }

  // mask the given NodeFact to only expose attrs that are in "Retrieve"
  def mask(fact: NodeFact)(implicit attrs: SelectFacts): NodeFact = {
    // masking is merging with the input fact as "old" and a version with everything set to empty
    mergeCore(CoreNodeFact.fromMininal(fact), fact)
  }
}

final case class NodeFact(
    id:                NodeId,
    description:       Option[String],
    @jsonField("hostname")
    fqdn:              String,
    os:                OsDetails,
    machine:           MachineInfo,
    rudderSettings:    RudderSettings,
    rudderAgent:       RudderAgent,
    properties:        Chunk[NodeProperty],
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

    archDescription:      Option[String] = None,
    ram:                  Option[MemorySize] = None,
    swap:                 Option[MemorySize] = None,
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
) extends MinimalNodeFactInterface {
  def machineId = machine.id

  def isPolicyServer:   Boolean               = rudderSettings.kind != NodeKind.Node
  def isSystem:         Boolean               = isPolicyServer
  def serverIps:        List[String]          = ipAddresses.map(_.inet).toList
  def customProperties: Chunk[CustomProperty] = properties.collect {
    case p if (p.provider == Some(NodeProperty.customPropertyProvider)) => CustomProperty(p.name, p.jsonValue)
  }

  def debugString(attrs: SelectFacts): String = {
    val pad     = "\n * "
    val ignored = new StringBuilder().append(pad).append("ignored: ")
    val sb      = new StringBuilder(s"""id:${this.id.value}
                                  | * description:${this.description}
                                  | * fqdn:${this.fqdn}
                                  | * os:${this.os}
                                  | * machine:${this.machine}
                                  | * rudderSettings:${this.rudderSettings}
                                  | * rudderAgent:${this.rudderAgent}
                                  | * properties:${this.properties.toList.sortBy(_.name)}
                                  | * creationDate:${this.creationDate}
                                  | * factProcessedDate:${this.factProcessedDate}
                                  | * lastInventoryDate:${this.lastInventoryDate}
                                  | * ipAddresses:${this.ipAddresses}
                                  | * timezone:${this.timezone}
                                  | * archDescription:${this.archDescription}
                                  | * ram:${this.ram}
                                  | * softwareUpdates:${this.softwareUpdate}""".stripMargin)

    attrs.swap.log(this, sb, pad, ignored, "")
    attrs.accounts.log(this, sb, pad, ignored, ",")
    attrs.bios.log(this, sb, pad, ignored, ",")
    attrs.controllers.log(this, sb, pad, ignored, ",")
    attrs.environmentVariables.log(this, sb, pad, ignored, ",")
    attrs.fileSystems.log(this, sb, pad, ignored, ",")
    attrs.inputs.log(this, sb, pad, ignored, ",")
    attrs.localGroups.log(this, sb, pad, ignored, ",")
    attrs.localUsers.log(this, sb, pad, ignored, ",")
    attrs.logicalVolumes.log(this, sb, pad, ignored, ",")
    attrs.memories.log(this, sb, pad, ignored, ",")
    attrs.networks.log(this, sb, pad, ignored, ",")
    attrs.physicalVolumes.log(this, sb, pad, ignored, ",")
    attrs.ports.log(this, sb, pad, ignored, ",")
    attrs.processes.log(this, sb, pad, ignored, ",")
    attrs.processors.log(this, sb, pad, ignored, ",")
    attrs.slots.log(this, sb, pad, ignored, ",")
    attrs.software.log(this, sb, pad, ignored, ",")
    attrs.sounds.log(this, sb, pad, ignored, ",")
    attrs.storages.log(this, sb, pad, ignored, ",")
    attrs.videos.log(this, sb, pad, ignored, ",")
    attrs.vms.log(this, sb, pad, ignored, ",")

    sb.append(ignored.toString()).toString()
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

final case class JNodeProperty(
    name:                             String,
    value:                            ConfigValue,
    @jsonAliases("inheritMode") mode: Option[String],
    provider:                         Option[String]
)

/**
 * ADT that allows encoding "Physical/Virtual/Unknown" machine type in a single json field
 * , instead of encoding specific provider of virtual machines.
 */
sealed abstract class JMachineType(override val entryName: String) extends EnumEntry {
  def name: String = entryName
}

object JMachineType extends Enum[JMachineType] {
  case object UnknownMachineType  extends JMachineType("Unknown")
  case object PhysicalMachineType extends JMachineType("Physical")
  case object VirtualMachineType  extends JMachineType("Virtual")

  def values: IndexedSeq[JMachineType] = findValues

  implicit val transformer: Transformer[MachineType, JMachineType] = Transformer.derive[MachineType, JMachineType]
}

/**
 * New serialization format for MachineInfo, which splits the provider of virtual machine if the machine type is Virtual
 * There are also renames from the old version which needs to be supported for backward compatibility.
 */
final case class JMachineInfo(
    id:                                                                   MachineUuid,
    @jsonField("type") @jsonAliases("machineType") machineType:           JMachineType,
    provider:                                                             Option[String],
    manufacturer:                                                         Option[Manufacturer],
    @jsonField("serialNumber") @jsonAliases("systemSerial") systemSerial: Option[String]
)
object JMachineInfo {
  implicit val transformer:     Transformer[MachineInfo, JMachineInfo]        = Transformer
    .define[MachineInfo, JMachineInfo]
    .withFieldComputed(
      _.provider,
      _.machineType match {
        case VirtualMachineType(provider) => Some(provider.name)
        case _                            => None
      }
    )
    .buildTransformer
  implicit val backTransformer: PartialTransformer[JMachineInfo, MachineInfo] = PartialTransformer
    .define[JMachineInfo, MachineInfo]
    .withFieldComputedPartial(
      _.machineType,
      jm => {
        jm.machineType match {
          case JMachineType.UnknownMachineType  => Result.Value(UnknownMachineType)
          case JMachineType.PhysicalMachineType => Result.Value(PhysicalMachineType)
          case JMachineType.VirtualMachineType  =>
            Result.fromEitherString(
              jm.provider.traverse(VmType.parse).map(_.getOrElse(VmType.UnknownVmType)).map(VirtualMachineType.apply)
            )
        }
      }
    )
    .buildTransformer
}

sealed trait NodeFactChangeEvent {
  def name:        String
  def debugString: String
  def debugDiff:   String
}

object NodeFactChangeEvent {
  final case class NewPending(node: NodeFact, attrs: SelectFacts)                           extends NodeFactChangeEvent {
    override val name:        String = "newPending"
    override def debugString: String = s"[${name}] node '${node.fqdn}' (${node.id.value})"
    override def debugDiff:   String = s"${debugString} ${node.debugString(attrs)}"
  }
  final case class UpdatedPending(oldNode: NodeFact, newNode: NodeFact, attrs: SelectFacts) extends NodeFactChangeEvent {
    override val name:        String = "updatedPending"
    override def debugString: String = s"[${name}] node '${newNode.fqdn}' (${newNode.id.value})"
    override def debugDiff:   String = s"${debugString} ${NodeFact.debugDiff(oldNode, newNode, attrs)}"
  }
  final case class Accepted(node: NodeFact, attrs: SelectFacts)                             extends NodeFactChangeEvent {
    override val name:        String = "accepted"
    override def debugString: String = s"[${name}] node '${node.fqdn}' (${node.id.value})"
    override def debugDiff:   String = s"${debugString} ${node.debugString(attrs)}"
  }
  final case class Refused(node: NodeFact, attrs: SelectFacts)                              extends NodeFactChangeEvent {
    override val name:        String = "refused"
    override def debugString: String = s"[${name}] node '${node.fqdn}' (${node.id.value})"
    override def debugDiff:   String = s"${debugString} ${node.debugString(attrs)}"
  }
  final case class Updated(oldNode: NodeFact, newNode: NodeFact, attrs: SelectFacts)        extends NodeFactChangeEvent {
    override val name:        String = "updatedAccepted"
    override def debugString: String = s"[${name}] node '${newNode.fqdn}' (${newNode.id.value})"
    override def debugDiff:   String = s"${debugString} ${NodeFact.debugDiff(oldNode, newNode, attrs)}"
  }
  final case class Deleted(node: NodeFact, attrs: SelectFacts)                              extends NodeFactChangeEvent {
    override val name:        String = "deleted"
    override def debugString: String = s"[${name}] node '${node.fqdn}' (${node.id.value})"
    override def debugDiff:   String = s"${debugString} ${node.debugString(attrs)}"
  }
  final case class Noop(nodeId: NodeId, attrs: SelectFacts)                                 extends NodeFactChangeEvent {
    override val name:        String = "noop"
    override def debugString: String = s"[${name}] node '${nodeId.value}' "
    override def debugDiff:   String = debugString
  }
}

/*
 * A change context groups together information needed to track a change: who, when, why
 */
final case class ChangeContext(
    modId:     ModificationId,
    actor:     EventActor,
    eventDate: DateTime,
    message:   Option[String],
    actorIp:   Option[String],
    nodePerms: NodeSecurityContext
)

object ChangeContext {
  implicit class ChangeContextImpl(cc: ChangeContext) {
    def toQuery: QueryContext = QueryContext(cc.actor, cc.nodePerms)
  }

  def newForRudder(msg: Option[String] = None, actorIp: Option[String] = None): ChangeContext = {
    ChangeContext(
      ModificationId(java.util.UUID.randomUUID.toString),
      eventlog.RudderEventActor,
      DateTime.now(),
      msg,
      actorIp,
      NodeSecurityContext.All
    )
  }
}

/*
 * A query context groups together information that are needed to either filter out
 * some result regarding a security context, or to enhance query efficiency by limiting
 * the item to retrieve. It's granularity is at the item level, not attribute level. For that
 * latter need, by-item solution need to be used (see for ex: SelectFacts for nodes)
 */
final case class QueryContext(
    actor:     EventActor,
    nodePerms: NodeSecurityContext
)

object QueryContext {
  // for test
  implicit val testQC: QueryContext = QueryContext(eventlog.RudderEventActor, NodeSecurityContext.All)

  // for place that didn't get a real node security context yet
  implicit val todoQC: QueryContext = QueryContext(eventlog.RudderEventActor, NodeSecurityContext.All)

  // for system queries (when rudder needs to look-up things)
  implicit val systemQC: QueryContext = QueryContext(eventlog.RudderEventActor, NodeSecurityContext.All)
}

/*
 * People can access nodes based on a security context.
 * For now, there is only three cases:
 * - access all or none nodes, whatever properties the node has
 * - access nodes only if they belongs to one of the listed tenants.
 */
sealed trait NodeSecurityContext {
  def value:     String
  // serialize into a string that can be parsed by parse `NodeSecurityContext.parse`
  def serialize: String
}
object NodeSecurityContext       {

  // a context that can see all nodes whatever their security tags
  case object All                                      extends NodeSecurityContext {
    override val value = "all"
    override def serialize: String = "*"
  }
  // a security context that can't see any node. Very good for performance.
  case object None                                     extends NodeSecurityContext {
    override val value     = "none"
    override def serialize = "-"
  }
  // a security context associated with a list of tenants. If the node share at least one of the
  // tenants, if can be seen. Be careful, it's really just non-empty interesting (so that adding
  // more tag here leads to more nodes, not less).
  final case class ByTenants(tenants: Chunk[TenantId]) extends NodeSecurityContext {
    override val value: String = s"tags:[${tenants.map(_.value).mkString(", ")}]"
    override def serialize = tenants.map(_.value).mkString(",")
  }

  /*
   * Tenants name are only alphanumeric (mandatory first) + '-' + '_' with two special cases:
   * - '*' means "all"
   * - '-' means "none"
   * None means "all" for compat
   */
  def parse(tenantString: Option[String], ignoreMalformed: Boolean = true): PureResult[NodeSecurityContext] = {
    parseList(tenantString.map(_.split(",").toList))
  }

  def parseList(tenants: Option[List[String]], ignoreMalformed: Boolean = true): PureResult[NodeSecurityContext] = {
    tenants match {
      case scala.None => Right(NodeSecurityContext.All) // for compatibility with previous versions
      case Some(ts)   =>
        (ts
          .foldLeft(Right(NodeSecurityContext.ByTenants(Chunk.empty)): PureResult[NodeSecurityContext]) {
            case (x: Left[RudderError, NodeSecurityContext], _) => x
            case (Right(t1), t2)                                =>
              t2.strip() match {
                case "*"                       => Right(t1.plus(NodeSecurityContext.All))
                case "-"                       => Right(NodeSecurityContext.None)
                case TenantId.checkTenantId(v) => Right(t1.plus(NodeSecurityContext.ByTenants(Chunk(TenantId(v)))))
                case x                         =>
                  if (ignoreMalformed) Right(t1.plus(NodeSecurityContext.ByTenants(Chunk.empty)))
                  else {
                    Left(
                      Inconsistency(
                        s"Value '${x}' is not a valid tenant identifier. It must contains only alpha-num  ascii chars or " +
                        s"'-' and '_' (not in the first) place; or exactly '*' (all tenants) or '-' (none tenants)"
                      )
                    )
                  }
              }
          })
          .map {
            case NodeSecurityContext.ByTenants(c) if (c.isEmpty) => NodeSecurityContext.None
            case x                                               => x
          }
    }
  }

  /*
   * check if the given security context allows to access items marked with
   * that tag
   */
  implicit class NodeSecurityContextExt(val nsc: NodeSecurityContext) extends AnyVal {
    def isNone: Boolean = {
      nsc == None
    }

    // can that security tag be seen in that context, given the set of known tenants?
    def canSee(nodeTag: SecurityTag)(implicit tenants: Set[TenantId]): Boolean = {
      nsc match {
        case All           => true
        case None          => false
        case ByTenants(ts) => ts.exists(s => nodeTag.tenants.exists(_ == s) && tenants.contains(s))
      }
    }

    def canSee(optTag: Option[SecurityTag])(implicit tenants: Set[TenantId]): Boolean = {
      optTag match {
        case Some(t)    => canSee(t)
        case scala.None => nsc == NodeSecurityContext.All // only admin can see private nodes
      }
    }

    def canSee(n: MinimalNodeFactInterface)(implicit tenants: Set[TenantId]): Boolean = {
      canSee(n.rudderSettings.security)
    }

    // NodeSecurityContext is a lattice
    def plus(nsc2: NodeSecurityContext): NodeSecurityContext = {
      (nsc, nsc2) match {
        case (None, _)                      => None
        case (_, None)                      => None
        case (All, _)                       => All
        case (_, All)                       => All
        case (ByTenants(c1), ByTenants(c2)) => ByTenants((c1 ++ c2).distinctBy(_.value))
      }
    }
  }

}

final case class NodeFactChangeEventCC(
    event: NodeFactChangeEvent,
    cc:    ChangeContext
)

object NodeFactSerialisation {

  // we need to have several object to avoid:
  // scalac: Error while emitting com/normation/rudder/facts/nodes/NodeFactSerialisation$
  // Method too large: com/normation/rudder/facts/nodes/NodeFactSerialisation$.<clinit> ()V

  import com.normation.inventory.domain.JsonSerializers.implicits.*
  import com.normation.utils.DateFormaterService.json.*

  object SimpleCodec {

    implicit val codecNodeId:        JsonCodec[NodeId]        = JsonCodec.string.transform[NodeId](NodeId(_), _.value)
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
    implicit val codecAgentReportingProtocol: JsonCodec[AgentReportingProtocol] = {
      JsonCodec.string.transformOrFail[AgentReportingProtocol](
        s => AgentReportingProtocol.parse(s).left.map(_.fullMsg),
        _.value
      )
    }
    implicit val codecHeartbeatConfiguration: JsonCodec[HeartbeatConfiguration] = DeriveJsonCodec.gen
    implicit val codecReportingConfiguration: JsonCodec[ReportingConfiguration] = DeriveJsonCodec.gen

    implicit val codecNodeKind: JsonCodec[NodeKind] = JsonCodec.string.transformOrFail[NodeKind](NodeKind.parse, _.name)

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

    implicit val codecKeyStatus: JsonCodec[KeyStatus] = JsonCodec.string.transform[KeyStatus](
      _ match {
        case "certified" => CertifiedKey
        case _           => UndefinedKey
      },
      _.value
    )

    implicit val codecInventoryStatus: JsonCodec[InventoryStatus] = JsonCodec.string.transformOrFail[InventoryStatus](
      s => {
        InventoryStatus(s) match {
          case None     => Left(s"'${s}' is not recognized as a node status. Expected: 'pending', 'accepted'")
          case Some(is) => Right(is)
        }
      },
      _.name
    )

    implicit val codecSecurityTag:    JsonCodec[SecurityTag]    = DeriveJsonCodec.gen
    implicit val codecNodeState:      JsonCodec[NodeState]      = JsonCodec.string.transformOrFail[NodeState](NodeState.parse, _.name)
    implicit val codecRudderSettings: JsonCodec[RudderSettings] = DeriveJsonCodec.gen
    implicit val codecAgentType:      JsonCodec[AgentType]      =
      JsonCodec.string.transformOrFail[AgentType](s => AgentType.fromValue(s).left.map(_.fullMsg), _.id)
    implicit val codecAgentVersion:   JsonCodec[AgentVersion]   = JsonCodec.string.transform[AgentVersion](AgentVersion(_), _.value)
    implicit val codecVersion:        JsonCodec[Version]        =
      JsonCodec.string.transformOrFail[Version](ParseVersion.parse, _.toVersionString)
    implicit val codecJSecurityToken: JsonCodec[JSecurityToken] = DeriveJsonCodec.gen

    implicit val codecSecturityToken: JsonCodec[SecurityToken] = JsonCodec(
      JsonEncoder[JSecurityToken].contramap[SecurityToken](st => JSecurityToken(SecurityToken.kind(st), st.key)),
      JsonDecoder[JSecurityToken].mapOrFail[SecurityToken](jst => SecurityToken.token(jst.kind, jst.token))
    )

    implicit val codecAgentCapability: JsonCodec[AgentCapability] =
      JsonCodec.string.transform[AgentCapability](AgentCapability(_), _.value)

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

    implicit val codecRudderAgent:    JsonCodec[RudderAgent]    = DeriveJsonCodec.gen
    implicit val codecIpAddress:      JsonCodec[IpAddress]      = JsonCodec.string.transform(IpAddress(_), _.inet)
    implicit val codecNodeTimezone:   JsonCodec[NodeTimezone]   = DeriveJsonCodec.gen
    implicit val codecMachineType:    JsonCodec[MachineType]    = {
      val encoder: JsonEncoder[MachineType] = JsonEncoder.string.contramap(_.kind)
      // for compat before correction of https://issues.rudder.io/issues/24971, we need to keep
      // "physicalMachine". "virtualMachine" wasn't directly used
      val decoder: JsonDecoder[MachineType] = JsonDecoder.string.map {
        case UnknownMachineType.kind                      => UnknownMachineType
        case PhysicalMachineType.kind | "physicalMachine" => PhysicalMachineType
        case x                                            => VirtualMachineType(VmType.parse(x).getOrElse(VmType.UnknownVmType))
      }

      JsonCodec(encoder, decoder)
    }
    implicit val encoderJMachineType: JsonEncoder[JMachineType] = JsonEncoder[String].contramap(_.name)
    implicit val decoderJMachineType: JsonDecoder[JMachineType] =
      JsonDecoder[String].mapOrFail(JMachineType.withNameEither(_).left.map(_.getMessage))
    implicit val encoderJMachineInfo: JsonEncoder[JMachineInfo] = DeriveJsonEncoder.gen
    implicit val decoderJMachineInfo: JsonDecoder[JMachineInfo] = DeriveJsonDecoder.gen
    implicit val codecMachineInfo:    JsonCodec[MachineInfo]    = {
      val decoder: JsonDecoder[MachineInfo] = DeriveJsonDecoder.gen
      JsonCodec(
        JsonEncoder[JMachineInfo].contramap(_.transformInto[JMachineInfo]),
        JsonDecoder[JMachineInfo]
          .mapOrFail(_.transformIntoPartial[MachineInfo].asEither.left.map(_.errors.toString))
          .orElse(decoder)
      )
    }
  }

  import SimpleCodec.*

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

  implicit lazy val decoderJValue:       JsonDecoder[JValue]       = JsonDecoder[Option[Json]].map {
    case None    => JNothing
    case Some(v) => recJsonToJValue(v)
  }
  implicit lazy val encoderJValue:       JsonEncoder[JValue]       = JsonEncoder[Option[Json]].contramap(recJValueToJson(_))
  implicit lazy val codecCustomProperty: JsonCodec[CustomProperty] = DeriveJsonCodec.gen

  implicit lazy val codecInputDevice:    JsonCodec[InputDevice]    = DeriveJsonCodec.gen
  implicit lazy val codecLocalGroup:     JsonCodec[LocalGroup]     = DeriveJsonCodec.gen
  implicit lazy val codecLocalUser:      JsonCodec[LocalUser]      = DeriveJsonCodec.gen
  implicit lazy val codecLogicalVolume:  JsonCodec[LogicalVolume]  = DeriveJsonCodec.gen
  implicit lazy val codecPhysicalVolume: JsonCodec[PhysicalVolume] = DeriveJsonCodec.gen

  implicit lazy val codecSoftwareFact: JsonCodec[SoftwareFact] = DeriveJsonCodec.gen

  implicit lazy val codecNodeFact: JsonCodec[NodeFact] = DeriveJsonCodec.gen

}
