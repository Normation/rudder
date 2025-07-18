/*
 *************************************************************************************
 * Copyright 2021 Normation SAS
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

package com.normation.rudder.apidata

import com.normation.GitVersion
import com.normation.GitVersion.RevisionInfo
import com.normation.cfclerk.domain.Technique
import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.domain.TechniqueVersion
import com.normation.errors.*
import com.normation.inventory.domain
import com.normation.inventory.domain.*
import com.normation.rudder.apidata.JsonResponseObjects.JRPropertyHierarchy.*
import com.normation.rudder.domain.nodes
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.nodes.NodeGroupCategoryId
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.domain.nodes.NodeKind
import com.normation.rudder.domain.nodes.NodeState
import com.normation.rudder.domain.policies.*
import com.normation.rudder.domain.properties.*
import com.normation.rudder.domain.properties.Visibility.Displayed
import com.normation.rudder.domain.queries.*
import com.normation.rudder.domain.reports.ComplianceLevel
import com.normation.rudder.domain.reports.RunAnalysisKind
import com.normation.rudder.domain.servers.Srv
import com.normation.rudder.domain.workflows.ChangeRequestId
import com.normation.rudder.facts.nodes.CoreNodeFact
import com.normation.rudder.facts.nodes.IpAddress
import com.normation.rudder.facts.nodes.NodeFact
import com.normation.rudder.facts.nodes.NodeFact.ToCompat
import com.normation.rudder.facts.nodes.SecurityTag
import com.normation.rudder.hooks.Hooks
import com.normation.rudder.ncf.ResourceFile
import com.normation.rudder.ncf.TechniqueParameter
import com.normation.rudder.properties.MergeNodeProperties
import com.normation.rudder.reports.execution.AgentRunWithNodeConfig
import com.normation.rudder.repository.FullActiveTechnique
import com.normation.rudder.repository.FullActiveTechniqueCategory
import com.normation.rudder.repository.FullNodeGroupCategory
import com.normation.rudder.rule.category.RuleCategory
import com.normation.rudder.rule.category.RuleCategoryId
import com.normation.rudder.score.GlobalScore
import com.normation.rudder.score.ScoreValue
import com.normation.rudder.services.queries.*
import com.normation.rudder.tenants.TenantId
import com.normation.utils.DateFormaterService
import com.softwaremill.quicklens.*
import com.typesafe.config.ConfigRenderOptions
import com.typesafe.config.ConfigValue
import enumeratum.Enum
import enumeratum.EnumEntry
import io.scalaland.chimney.Transformer
import io.scalaland.chimney.dsl.*
import java.time.Instant
import java.time.LocalTime
import zio.*
import zio.Tag as _
import zio.json.*
import zio.json.internal.Write
import zio.syntax.*

/*
 * This class deals with everything serialisation related for API.
 * Change things with care! Everything must be versioned!
 * Even changing a field name can lead to an API incompatible change and
 * so will need a new API version number (and be sure that old behavior is kept
 * for previous versions).
 */

// how to render parent properties in the returned json in node APIs
sealed trait RenderInheritedProperties
object RenderInheritedProperties {
  case object HTML extends RenderInheritedProperties
  case object JSON extends RenderInheritedProperties
}

// to avoid ambiguity with corresponding business objects, we use "JR" as a prfix
object JsonResponseObjects {

  sealed abstract class JRInventoryStatus(val name: String)
  object JRInventoryStatus {
    case object AcceptedInventory extends JRInventoryStatus("accepted")
    case object PendingInventory  extends JRInventoryStatus("pending")
    case object RemovedInventory  extends JRInventoryStatus("deleted")

    implicit val transformer: Transformer[InventoryStatus, JRInventoryStatus] =
      Transformer.derive[InventoryStatus, JRInventoryStatus]
  }

  final case class JRNodeInfo(
      id:          NodeId,
      status:      JRInventoryStatus,
      hostname:    String,
      osName:      String,
      osVersion:   Version,
      machineType: JRNodeDetailLevel.MachineType
  )
  object JRNodeInfo        {
    implicit def transformer(implicit status: InventoryStatus): Transformer[NodeInfo, JRNodeInfo] = Transformer
      .define[NodeInfo, JRNodeInfo]
      .enableBeanGetters
      .withFieldConst(_.status, status.transformInto[JRInventoryStatus])
      .withFieldComputed(_.osName, _.osDetails.os.name)
      .withFieldComputed(_.osVersion, _.osDetails.version)
      .withFieldComputed(
        _.machineType,
        _.machine
          .map(_.machineType)
          .transformInto[JRNodeDetailLevel.MachineType]
      )
      .buildTransformer
  }

  // Same as JRNodeInfo but with optional os and machine details. Mapped from Srv, NodeInfo or FullInventory
  final case class JRNodeChangeStatus(
      id:          NodeId,
      status:      JRInventoryStatus,
      hostname:    String,
      osName:      String,
      osVersion:   Option[Version],
      machineType: Option[JRNodeDetailLevel.MachineType]
  )
  object JRNodeChangeStatus {
    implicit val srvTransformer: Transformer[Srv, JRNodeChangeStatus] =
      Transformer.define[Srv, JRNodeChangeStatus].enableOptionDefaultsToNone.buildTransformer

    implicit def nodeInfoTransformer(implicit status: InventoryStatus): Transformer[NodeInfo, JRNodeChangeStatus] = Transformer
      .define[NodeInfo, JRNodeChangeStatus]
      .enableBeanGetters
      .withFieldConst(_.status, status.transformInto[JRInventoryStatus])
      .withFieldComputed(_.osName, _.osDetails.os.name)
      .withFieldComputed(_.osVersion, n => Some(n.osDetails.version))
      .withFieldComputed(
        _.machineType,
        n => {
          Some(
            n.machine
              .map(_.machineType)
              .transformInto[JRNodeDetailLevel.MachineType]
          )
        }
      )
      .buildTransformer

    implicit def fullInventoryTransformer(implicit status: InventoryStatus): Transformer[FullInventory, JRNodeChangeStatus] = {
      Transformer
        .define[FullInventory, JRNodeChangeStatus]
        .enableBeanGetters
        .withFieldConst(_.status, status.transformInto[JRInventoryStatus])
        .withFieldComputed(_.id, _.node.main.id)
        .withFieldComputed(_.hostname, _.node.main.hostname)
        .withFieldComputed(_.osName, _.node.main.osDetails.os.name)
        .withFieldComputed(_.osVersion, inv => Some(inv.node.main.osDetails.version))
        .withFieldComputed(
          _.machineType,
          inv => {
            Some(
              inv.machine
                .map(_.machineType)
                .transformInto[JRNodeDetailLevel.MachineType]
            )
          }
        )
        .buildTransformer
    }
  }

  final case class JRNodeIdStatus(
      id:     NodeId,
      status: JRInventoryStatus
  )

  final case class JRNodeIdHostnameResult(
      id:       NodeId,
      hostname: String,
      result:   String
  )

  final case class JRUpdateNode(
      id:            NodeId,
      properties:    Chunk[JRProperty], // sorted by name
      policyMode:    Option[PolicyMode],
      state:         NodeState,
      documentation: Option[String]
  )
  object JRUpdateNode       {
    implicit val transformer: Transformer[CoreNodeFact, JRUpdateNode] = {
      Transformer
        .define[CoreNodeFact, JRUpdateNode]
        .withFieldComputed(_.state, _.rudderSettings.state)
        .withFieldComputed(_.policyMode, _.rudderSettings.policyMode)
        .withFieldComputed(
          _.properties,
          _.properties.sortBy(_.name).map(JRProperty.fromNodeProp).transformInto[Chunk[JRProperty]]
        )
        .withFieldComputed(_.documentation, _.documentation)
        .buildTransformer
    }
  }

  // Node details json with all fields optional but minimal fields. Fields are in the same order as in the list of all fields.
  final case class JRNodeDetailLevel(
      // minimal
      id:                          NodeId,
      hostname:                    String,
      status:                      JRInventoryStatus,
      // default
      state:                       Option[NodeState],
      os:                          Option[domain.OsDetails],
      architectureDescription:     Option[String],
      ram:                         Option[MemorySize],
      machine:                     Option[nodes.MachineInfo],
      ipAddresses:                 Option[Chunk[String]],
      description:                 Option[String],
      acceptanceDate:              Option[Instant],
      lastInventoryDate:           Option[Instant],
      lastRunDate:                 Option[Instant],
      policyServerId:              Option[NodeId],
      managementTechnology:        Option[Chunk[JRNodeDetailLevel.Management]],
      properties:                  Option[Chunk[JRProperty]],
      policyMode:                  Option[String],
      timezone:                    Option[domain.NodeTimezone],
      tenant:                      Option[TenantId],
      // full
      accounts:                    Option[Chunk[String]],
      bios:                        Option[Chunk[domain.Bios]],
      controllers:                 Option[Chunk[domain.Controller]],
      environmentVariables:        Option[Map[String, String]],
      fileSystems:                 Option[Chunk[domain.FileSystem]],
      managementTechnologyDetails: Option[JRNodeDetailLevel.ManagementDetails],
      memories:                    Option[Chunk[domain.MemorySlot]],
      networkInterfaces:           Option[Chunk[domain.Network]],
      processes:                   Option[Chunk[domain.Process]],
      processors:                  Option[Chunk[domain.Processor]],
      slots:                       Option[Chunk[domain.Slot]],
      software:                    Option[Chunk[domain.Software]],
      softwareUpdate:              Option[Chunk[SoftwareUpdate]],
      sound:                       Option[Chunk[domain.Sound]],
      storage:                     Option[Chunk[domain.Storage]],
      ports:                       Option[Chunk[domain.Port]],
      videos:                      Option[Chunk[domain.Video]],
      virtualMachines:             Option[Chunk[domain.VirtualMachine]]
  )

  object JRNodeDetailLevel {
    implicit def transformer(implicit
        nodeFact: NodeFact,
        status:   InventoryStatus,
        agentRun: Option[AgentRunWithNodeConfig]
    ): Transformer[NodeDetailLevel, JRNodeDetailLevel] = {
      val nodeInfo:    NodeInfo               = nodeFact.toNodeInfo
      val securityTag: Option[SecurityTag]    = nodeFact.rudderSettings.security
      val software:    Chunk[domain.Software] = nodeFact.software.map(_.toSoftware)
      // we could keep inventory as Option to make syntax easier to filter empty lists, or write Some(...).filter(_.nonEmpty) everywhere
      val inventory:   Option[FullInventory]  = Some(nodeFact.toFullInventory)

      Transformer
        .define[NodeDetailLevel, JRNodeDetailLevel]
        .withFieldConst(_.id, nodeInfo.id)
        .withFieldConst(_.hostname, nodeInfo.hostname)
        .withFieldConst(_.status, status.transformInto[JRInventoryStatus])
        // default
        .withFieldComputed(_.state, levelField("state")(nodeInfo.state))
        .withFieldComputed(_.os, levelField("os")(nodeInfo.osDetails))
        .withFieldComputed(_.architectureDescription, levelField(_)("architectureDescription")(nodeInfo.archDescription))
        .withFieldComputed(_.ram, levelField(_)("ram")(nodeInfo.ram))
        .withFieldComputed(_.machine, levelField(_)("machine")(nodeInfo.machine))
        .withFieldComputed(_.ipAddresses, levelField("ipAddresses")(nodeInfo.ips.transformInto[Chunk[String]]))
        .withFieldComputed(_.description, levelField("description")(nodeInfo.description))
        .withFieldComputed(_.acceptanceDate, levelField("acceptanceDate")(nodeFact.creationDate))
        .withFieldComputed(_.lastInventoryDate, levelField("lastInventoryDate")(nodeInfo.inventoryDate))
        .withFieldComputed(
          _.lastRunDate,
          levelField(_)("lastRunDate")(agentRun.map(x => DateFormaterService.toInstant(x.agentRunId.date)))
        )
        .withFieldComputed(_.policyServerId, levelField("policyServerId")(nodeInfo.policyServerId))
        .withFieldComputed(
          _.managementTechnology,
          levelField("managementTechnology")(nodeInfo.transformInto[Chunk[Management]])
        )
        .withFieldComputed(
          _.properties,
          levelField("properties")(
            Chunk.fromIterable(nodeInfo.properties.filter(_.visibility == Displayed).sortBy(_.name).map(JRProperty.fromNodeProp))
          )
        )
        .withFieldComputed(_.policyMode, levelField("policyMode")(nodeInfo.policyMode.map(_.name).getOrElse("default")))
        .withFieldComputed(_.timezone, levelField(_)("timezone")(nodeInfo.timezone))
        .withFieldComputed(_.tenant, levelField(_)("tenant")(securityTag.flatMap(_.tenants.headOption)))
        // full
        .withFieldComputed(
          _.accounts,
          levelField(_)("accounts")(inventory.map(_.node.accounts.transformInto[Chunk[String]]).filter(_.nonEmpty))
        )
        .withFieldComputed(
          _.bios,
          levelField(_)("bios")(
            inventory.flatMap(_.machine.map(_.bios.transformInto[Chunk[domain.Bios]])).filter(_.nonEmpty)
          )
        )
        .withFieldComputed(
          _.controllers,
          levelField(_)("controllers")(
            inventory
              .flatMap(_.machine.map(_.controllers.transformInto[Chunk[domain.Controller]]))
              .filter(_.nonEmpty)
          )
        )
        .withFieldComputed(
          _.environmentVariables,
          levelField(_)("environmentVariables")(
            inventory.map(_.node.environmentVariables.groupMapReduce(_.name)(_.value.getOrElse("")) { case (first, _) => first })
          )
        )
        .withFieldComputed(
          _.fileSystems,
          levelField(_)("fileSystems")(
            inventory.map(i => {
              Chunk(domain.FileSystem("none", Some("swap"), totalSpace = i.node.swap)) ++ i.node.fileSystems
                .transformInto[Chunk[domain.FileSystem]]
            })
          )
        )
        .withFieldComputed(
          _.managementTechnologyDetails,
          levelField(_)("managementTechnologyDetails")(Some(nodeFact.transformInto[ManagementDetails]))
        )
        .withFieldComputed(
          _.memories,
          levelField(_)("memories")(
            inventory
              .flatMap(_.machine.map(_.memories.transformInto[Chunk[domain.MemorySlot]]))
              .filter(_.nonEmpty)
          )
        )
        .withFieldComputed(
          _.networkInterfaces,
          levelField(_)("networkInterfaces")(
            inventory.map(_.node.networks.transformInto[Chunk[domain.Network]])
          )
        )
        .withFieldComputed(
          _.processes,
          levelField(_)("processes")(inventory.map(_.node.processes.transformInto[Chunk[domain.Process]]))
        )
        .withFieldComputed(
          _.processors,
          levelField(_)("processors")(
            inventory.flatMap(_.machine.map(_.processors.transformInto[Chunk[domain.Processor]])).filter(_.nonEmpty)
          )
        )
        .withFieldComputed(
          _.slots,
          levelField(_)("slots")(
            inventory.flatMap(_.machine.map(_.slots.transformInto[Chunk[domain.Slot]])).filter(_.nonEmpty)
          )
        )
        .withFieldComputed(_.software, levelField("software")(software.map(_.transformInto[domain.Software])))
        .withFieldComputed(
          _.softwareUpdate,
          levelField(_)("software")(inventory.map(_.node.softwareUpdates.transformInto[Chunk[domain.SoftwareUpdate]]))
        )
        .withFieldComputed(
          _.sound,
          levelField(_)("sound")(
            inventory.flatMap(_.machine.map(_.sounds.transformInto[Chunk[domain.Sound]])).filter(_.nonEmpty)
          )
        )
        .withFieldComputed(
          _.storage,
          levelField(_)("storage")(
            inventory.flatMap(_.machine.map(_.storages.transformInto[Chunk[domain.Storage]])).filter(_.nonEmpty)
          )
        )
        .withFieldComputed(
          _.ports,
          levelField(_)("ports")(
            inventory.flatMap(_.machine.map(_.ports.transformInto[Chunk[domain.Port]])).filter(_.nonEmpty)
          )
        )
        .withFieldComputed(
          _.videos,
          levelField(_)("videos")(
            inventory.flatMap(_.machine.map(_.videos.transformInto[Chunk[domain.Video]])).filter(_.nonEmpty)
          )
        )
        .withFieldComputed(
          _.virtualMachines,
          levelField(_)("virtualMachines")(
            inventory.map(_.node.vms.transformInto[Chunk[domain.VirtualMachine]]).filter(_.nonEmpty)
          )
        )
        .buildTransformer
    }

    /**
     * Needed to handle the serialization of the "type" of a machine even if it there is no machine.
     */
    sealed abstract class MachineType(override val entryName: String) extends EnumEntry {
      def name: String = entryName
    }

    object MachineType extends Enum[MachineType] {
      case object UnknownMachineType  extends MachineType("Unknown")
      case object PhysicalMachineType extends MachineType("Physical")
      case object VirtualMachineType  extends MachineType("Virtual")
      case object NoMachine           extends MachineType("No machine Inventory")

      def values: IndexedSeq[MachineType] = findValues

      implicit val transformer: Transformer[Option[domain.MachineType], MachineType] = {
        Transformer
          .define[Option[domain.MachineType], MachineType]
          .withEnumCaseHandled[None.type] { case None => NoMachine }
          .withEnumCaseHandled[Some[domain.MachineType]] {
            case Some(domain.UnknownMachineType)    => UnknownMachineType
            case Some(domain.PhysicalMachineType)   => PhysicalMachineType
            case Some(_: domain.VirtualMachineType) => VirtualMachineType
          }
          .buildTransformer
      }
    }

    /**
     * The structure does not directly match the domain object, nodeKind is a contextual value of the node added to each AgentInfo
     * and we just have the display name in place of the AgentType
     */
    final case class Management(
        name:         String,
        version:      Option[AgentVersion],
        capabilities: Chunk[String],
        nodeKind:     NodeKind
    )
    object Management {
      implicit val transformer: Transformer[NodeInfo, Chunk[Management]] = (info: NodeInfo) => {
        val agents = info.agentsName.map { agent =>
          val capabilities = agent.capabilities.map(_.value).toList.sorted
          Management(
            agent.agentType.displayName,
            agent.version,
            Chunk.fromIterable(capabilities),
            info.nodeKind
          )
        }
        Chunk.fromIterable(agents)
      }
    }

    final case class ScheduleOverride(
        runInterval: String,
        firstRun:    String,
        splayTime:   String
    )

    /**
     * The structure does not directly match the domain object, it aggregates some fields
     * and the cfengineKeys is only the string for PEM format, not the JSON serialisation used in LDAP
     */
    final case class ManagementDetails(
        cfengineKeys:     Chunk[String],
        cfengineUser:     String,
        scheduleOverride: Option[ScheduleOverride]
    )
    object ManagementDetails {
      implicit val transformer: Transformer[NodeFact, ManagementDetails] = Transformer
        .define[NodeFact, ManagementDetails]
        .withFieldComputed(_.cfengineKeys, n => Chunk(n.rudderAgent.securityToken.key))
        .withFieldComputed(_.cfengineUser, _.rudderAgent.user)
        .withFieldComputed(
          _.scheduleOverride,
          _.rudderSettings.reportingConfiguration.agentRunInterval.map(r =>
            ScheduleOverride(s"${r.interval} min", LocalTime.of(r.startHour, r.startMinute).toString, s"${r.splaytime} min")
          )
        )
        .buildTransformer
    }

    // Helpers for more concise syntax of chimney accessor. Different signature are used to avoid abstract type collision
    // levelField("field")(value) for not optional computed value
    private def levelField[A](field: String)(a: => A)(level: NodeDetailLevel):         Option[A] =
      Option.when(level.fields(field))(a)
    // levelField(_)("field")(value) for optional computed value
    private def levelField[A](level: NodeDetailLevel)(field: String)(a: => Option[A]): Option[A] =
      if (level.fields(field)) a else None
  }

  // pretty format for score with details in a map of key-value by score id
  final case class JRGlobalScore(
      score:   ScoreValue,
      details: Map[String, ScoreValue]
  )
  object JRGlobalScore {
    implicit val transformer: Transformer[GlobalScore, JRGlobalScore] = {
      Transformer
        .define[GlobalScore, JRGlobalScore]
        .withFieldRenamed(_.value, _.score)
        .withFieldComputed(_.details, _.details.map(d => (d.scoreId, d.value)).toMap)
        .buildTransformer
    }
  }

  final case class JRNodeCompliance(compliance: ComplianceLevel)       extends AnyVal
  final case class JRNodeSystemCompliance(compliance: ComplianceLevel) extends AnyVal
  final case class JRNodeDetailTable(
      id:                      NodeId,
      @jsonField("name") fqdn: String,
      ram:                     Option[String], // toStringMo applied on MemorySize
      policyServerId:          NodeId,
      policyMode:              PolicyMode,
      globalModeOverride:      String,
      kernel:                  Version,
      agentVersion:            AgentVersion,
      machineType:             String,         // old version of machine type serialization
      os:                      String,
      state:                   NodeState,
      compliance:              Option[JRNodeCompliance],
      runAnalysisKind:         Option[RunAnalysisKind],
      systemError:             Boolean,
      ipAddresses:             Chunk[IpAddress],
      acceptanceDate:          String,         // display date
      lastRun:                 String,         // display date
      lastInventory:           String,         // display date
      software:                Map[String, String],
      properties:              Map[String, JRProperty],
      inheritedProperties:     Map[String, JRProperty],
      score:                   JRGlobalScore
  )
  object JRNodeDetailTable {
    implicit def transformer(implicit
        globalPolicyMode:       GlobalPolicyMode,
        agentRunWithNodeConfig: Option[AgentRunWithNodeConfig],
        properties:             Chunk[NodeProperty],
        inheritedProperties:    Chunk[PropertyHierarchy],
        softs:                  Chunk[domain.Software],
        compliance:             Option[JRNodeCompliance],
        runAnalysisKind:        Option[RunAnalysisKind],
        systemCompliance:       Option[JRNodeSystemCompliance],
        score:                  GlobalScore
    ): Transformer[CoreNodeFact, JRNodeDetailTable] = {
      def getPolicyModeAndGlobalModeOverride(nodeFact: CoreNodeFact): (PolicyMode, String) = {
        (globalPolicyMode.overridable, nodeFact.rudderSettings.policyMode) match {
          case (PolicyModeOverrides.Always, Some(mode)) =>
            (mode, "override")
          case (PolicyModeOverrides.Always, None)       =>
            (globalPolicyMode.mode, "default")
          case (PolicyModeOverrides.Unoverridable, _)   =>
            (globalPolicyMode.mode, "none")
        }
      }
      Transformer
        .define[CoreNodeFact, JRNodeDetailTable]
        .withFieldComputed(_.policyServerId, _.rudderSettings.policyServerId)
        .withFieldComputed(_.policyMode, getPolicyModeAndGlobalModeOverride(_)._1)
        .withFieldComputed(_.globalModeOverride, getPolicyModeAndGlobalModeOverride(_)._2)
        .withFieldComputed(_.kernel, _.os.kernelVersion)
        .withFieldComputed(_.agentVersion, _.rudderAgent.version)
        .withFieldComputed(_.ram, _.ram.map(_.toStringMo))
        .withFieldComputed(_.machineType, _.machine.machineType.kind)
        .withFieldComputed(_.os, _.os.fullName)
        .withFieldComputed(_.state, _.rudderSettings.state)
        .withFieldComputed(_.ipAddresses, _.ipAddresses.filterNot(_.isLocalhostIPv4IPv6))
        .withFieldConst(
          _.lastRun,
          agentRunWithNodeConfig.map(d => DateFormaterService.getDisplayDate(d.agentRunId.date)).getOrElse("Never")
        )
        .withFieldComputed(
          _.acceptanceDate,
          nf => DateFormaterService.getDisplayDate(nf.creationDate)
        )
        .withFieldComputed(
          _.lastInventory,
          nf => DateFormaterService.getDisplayDate(nf.lastInventoryDate.getOrElse(nf.factProcessedDate))
        )
        .withFieldConst(_.compliance, compliance)
        .withFieldConst(_.runAnalysisKind, runAnalysisKind)
        .withFieldConst(
          _.systemError,
          systemCompliance
            .map(_.compliance.computePercent().compliance < 100)
            .getOrElse(false)
        ) // do not display error if no sys compliance
        .withFieldConst(
          _.software,
          softs.map(s => (s.name.getOrElse(""), s.version.map(_.value).getOrElse("N/A"))).toMap
        )
        .withFieldConst(
          _.properties,
          properties.map(s => (s.name, JRProperty.fromNodeProp(s))).toMap
        )
        .withFieldConst(
          _.inheritedProperties,
          inheritedProperties
            .map(s => (s.prop.name, JRProperty.fromNodePropertyHierarchy(s, RenderInheritedProperties.HTML, escapeHtml = true)))
            .toMap
        )
        .withFieldConst(
          _.score,
          score.transformInto[JRGlobalScore]
        )
        .buildTransformer
    }
  }

  final case class JRComplianceLevelArray(compliance: ComplianceLevel) extends AnyVal

  final case class JRActiveTechnique(
      name:     String,
      versions: List[String]
  )

  object JRActiveTechnique {
    def fromTechnique(activeTechnique: FullActiveTechnique): JRActiveTechnique = {
      JRActiveTechnique(activeTechnique.techniqueName.value, activeTechnique.techniques.map(_._1.serialize).toList)
    }
  }

  /*
   * "sections": [
   * {
   *   "section": {
   *     "name": "File to manage",
   *     "sections": [
   *       {
   *         "section": {
   *           "name": "Enforce content by section",
   *           "vars": [
   *             {
   *               "var": {
   *                 "name": "GENERIC_FILE_CONTENT_SECTION_MANAGEMENT",
   *                 "value": "false"
   *               }
   *             },
   *             {
   *               "var": {
   *                 "name": "GENERIC_FILE_SECTION_CONTENT",
   *                 "value": ""
   *               }
   *             },
   *      ],
   *      "vars": [ .... ]
   * .....
   */
  final case class JRDirectiveSectionVar(
      name:  String,
      value: String
  )
  final case class JRDirectiveSection(
      name:     String, // we have one more "var" indirection level between a var and its details:
      // { vars":[ { "var":{ "name": .... } }, { "var": { ... }} ]

      vars:     Option[
        List[Map[String, JRDirectiveSectionVar]]
      ], // we have one more "section" indirection level between a section and its details:
      // { sections":[ { "section":{ "name": .... } }, { "section": { ... }} ]

      sections: Option[List[Map[String, JRDirectiveSection]]]
  ) {

    // toMapVariable is just accumulating var by name in seq, see SectionVal.toMapVariables
    def toMapVariables: Map[String, Seq[String]] = {
      import scala.collection.mutable.Buffer
      import scala.collection.mutable.Map
      val res = Map[String, Buffer[String]]()

      def recToMap(sec: JRDirectiveSection): Unit = {
        sec.vars.foreach(_.foreach(_.foreach {
          case (_, sectionVar) =>
            res.getOrElseUpdate(sectionVar.name, Buffer()).append(sectionVar.value)
        }))
        sec.sections.foreach(_.foreach(_.foreach {
          case (_, section) =>
            recToMap(section)
        }))
      }
      recToMap(this)
      res.map { case (k, buf) => (k, buf.toSeq) }.toMap
    }
  }

  // we have one more level between a directive section and a section
  final case class JRDirectiveSectionHolder(
      section: JRDirectiveSection
  )

  object JRDirectiveSection {
    def fromSectionVal(name: String, sectionVal: SectionVal): JRDirectiveSection = {
      JRDirectiveSection(
        name = name,
        sections = sectionVal.sections.toList.sortBy(_._1) match {
          case Nil  => None
          case list => Some(list.flatMap { case (n, sections) => sections.map(s => Map("section" -> fromSectionVal(n, s))) })
        },
        vars = sectionVal.variables.toList.sortBy(_._1) match {
          case Nil  => None
          case list => Some(list.map { case (n, v) => Map("var" -> JRDirectiveSectionVar(n, v)) })
        }
      )
    }
  }
  final case class JRRevisionInfo(
      revision: String,
      date:     String,
      author:   String,
      message:  String
  )
  object JRRevisionInfo     {
    def fromRevisionInfo(r: RevisionInfo): JRRevisionInfo = {
      JRRevisionInfo(r.rev.value, DateFormaterService.serialize(r.date), r.author, r.message)
    }
  }

  sealed trait JRTechnique    {
    def id:      String
    def name:    String
    def version: String
    def source:  String
  }

  final case class JRBuiltInTechnique(
      name:    String,
      id:      String,
      version: String
  ) extends JRTechnique { val source = "builtin" }

  final case class JRTechniqueParameter(
      id:          String,
      name:        String,
      description: String,
      mayBeEmpty:  Boolean
  )
  object JRTechniqueParameter {
    def from(param: TechniqueParameter): JRTechniqueParameter = {
      JRTechniqueParameter(
        param.id.value,
        param.name,
        param.description.getOrElse(""),
        param.mayBeEmpty
      )
    }
  }

  final case class JRTechniqueResource(
      path:  String,
      state: String
  )
  object JRTechniqueResource  {
    def from(resource: ResourceFile): JRTechniqueResource = {
      JRTechniqueResource(
        resource.path,
        resource.state.value
      )
    }
  }

  final case class JRMethodCallValue(
      name:  String,
      value: String
  )
  final case class JRReportingLogic(
      name:  String,
      value: Option[String]
  )

  final case class JRDirective(
      changeRequestId: Option[String],
      id:              String, // id is in format uid+rev

      displayName:      String,
      shortDescription: String,
      longDescription:  String,
      techniqueName:    String,
      techniqueVersion: String,
      parameters:       Map[String, JRDirectiveSection],
      priority:         Int,
      enabled:          Boolean,
      system:           Boolean,
      policyMode:       String,
      tags:             List[Map[String, String]]
  ) {
    def toDirective(): IOResult[(TechniqueName, Directive)] = {
      for {
        i <- DirectiveId.parse(id).toIO
        v <- TechniqueVersion.parse(techniqueVersion).toIO
        // the Map is just for "section" -> ...
        s <- parameters.get("section").notOptional("Root section entry 'section' is missing for directive parameters")
        m <- PolicyMode.parseDefault(policyMode).toIO
      } yield {
        (
          TechniqueName(techniqueName),
          Directive(
            i,
            v,
            s.toMapVariables,
            displayName,
            shortDescription,
            m,
            longDescription,
            priority,
            enabled,
            system,
            Tags.fromMaps(tags)
          )
        )
      }
    }
  }
  object JRDirective          {
    def empty(id: String): JRDirective =
      JRDirective(None, id, "", "", "", "", "", Map(), 5, enabled = false, system = false, policyMode = "", tags = List())

    def fromDirective(technique: Technique, directive: Directive, crId: Option[ChangeRequestId]): JRDirective = {
      directive
        .into[JRDirective]
        .enableBeanGetters
        .withFieldConst(_.changeRequestId, crId.map(_.value.toString))
        .withFieldComputed(_.id, _.id.serialize)
        .withFieldRenamed(_.name, _.displayName)
        .withFieldConst(_.techniqueName, technique.id.name.value)
        .withFieldComputed(_.techniqueVersion, _.techniqueVersion.serialize)
        .withFieldConst(
          _.parameters,
          Map(
            "section" -> JRDirectiveSection.fromSectionVal(
              SectionVal.ROOT_SECTION_NAME,
              SectionVal.directiveValToSectionVal(technique.rootSection, directive.parameters)
            )
          )
        )
        .withFieldComputed(_.policyMode, _.policyMode.map(_.name).getOrElse("default"))
        .withFieldComputed(_.tags, x => JRTags.fromTags(x.tags))
        .transform
    }
  }

  final case class JRDirectives(directives: List[JRDirective])

  final case class JRDirectiveTreeCategory(
      name:          String,
      description:   String,
      subCategories: List[JRDirectiveTreeCategory],
      techniques:    List[JRDirectiveTreeTechnique]
  )
  object JRDirectiveTreeCategory  {
    def fromActiveTechniqueCategory(technique: FullActiveTechniqueCategory): JRDirectiveTreeCategory = {
      JRDirectiveTreeCategory(
        technique.name,
        technique.description,
        technique.subCategories.map(fromActiveTechniqueCategory),
        technique.activeTechniques.map(JRDirectiveTreeTechnique.fromActiveTechnique)
      )
    }
  }

  final case class JRDirectiveTreeTechnique(
      id:         String,
      name:       String,
      directives: List[JRDirective]
  )
  object JRDirectiveTreeTechnique {
    def fromActiveTechnique(technique: FullActiveTechnique): JRDirectiveTreeTechnique = {
      JRDirectiveTreeTechnique(
        technique.techniqueName.value,
        technique.newestAvailableTechnique.map(_.name).getOrElse(technique.techniqueName.value),
        technique.directives.flatMap { d =>
          technique.techniques.get(d.techniqueVersion).map(t => JRDirective.fromDirective(t, d, None))
        }
      )
    }
  }
  final case class JRApplicationStatus(
      value:   String,
      details: Option[String]
  )
  final case class JRRule(
      changeRequestId: Option[String] = None,
      id:              String, // id is in format uid+rev

      displayName:      String,
      categoryId:       String,
      shortDescription: String,
      longDescription:  String,
      directives:       List[String], // directives ids

      targets:    List[JRRuleTarget],
      enabled:    Boolean,
      system:     Boolean,
      tags:       List[Map[String, String]],
      policyMode: Option[String],
      status:     Option[JRApplicationStatus]
  ) {
    def toRule(): IOResult[Rule] = {
      for {
        i <- RuleId.parse(id).toIO
        d <- ZIO.foreach(directives)(DirectiveId.parse(_).toIO)
      } yield Rule(
        i,
        displayName,
        RuleCategoryId(categoryId),
        targets.map(_.toRuleTarget).toSet,
        d.toSet,
        shortDescription,
        longDescription,
        enabled,
        system,
        Tags.fromMaps(tags)
      )
    }
  }

  object JRRule {
    // create an empty json rule with just ID set
    def empty(id: String): JRRule =
      JRRule(None, id, "", "", "", "", Nil, Nil, enabled = false, system = false, tags = Nil, policyMode = None, status = None)

    // create from a rudder business rule
    def fromRule(
        rule:       Rule,
        crId:       Option[ChangeRequestId],
        policyMode: Option[String],
        status:     Option[(String, Option[String])]
    ): JRRule = {
      rule
        .into[JRRule]
        .enableBeanGetters
        .withFieldConst(_.changeRequestId, crId.map(_.value.toString))
        .withFieldComputed(_.id, _.id.serialize)
        .withFieldRenamed(_.name, _.displayName)
        .withFieldComputed(_.categoryId, _.categoryId.value)
        .withFieldComputed(_.directives, _.directiveIds.map(_.serialize).toList.sorted)
        .withFieldComputed(_.targets, _.targets.toList.sortBy(_.target).map(t => JRRuleTarget(t)))
        .withFieldRenamed(_.isEnabledStatus, _.enabled)
        .withFieldComputed(_.tags, x => JRTags.fromTags(rule.tags))
        .withFieldConst(_.policyMode, policyMode)
        .withFieldConst(_.status, status.map(s => JRApplicationStatus(s._1, s._2)))
        .transform
    }
  }

  object JRTags {
    def fromTags(tags: Tags): List[Map[String, String]] = {
      tags.tags.toList.sortBy(_.name.value).map(t => Map((t.name.value, t.value.value)))
    }
  }

  final case class JRRules(rules: List[JRRule])

  sealed trait JRRuleTarget {
    def toRuleTarget: RuleTarget
  }
  object JRRuleTarget       {
    def apply(t: RuleTarget): JRRuleTarget = {
      def compose(x: TargetComposition): JRRuleTargetComposition = x match {
        case TargetUnion(targets)        => JRRuleTargetComposition.or(x.targets.toList.map(JRRuleTarget(_)))
        case TargetIntersection(targets) => JRRuleTargetComposition.and(x.targets.toList.map(JRRuleTarget(_)))
      }

      t match {
        case x: SimpleTarget      => JRRuleTargetString(x)
        case x: TargetComposition => compose(x)
        case x: TargetExclusion   => JRRuleTargetComposed(compose(x.includedTarget), compose(x.excludedTarget))
      }
    }

    final case class JRRuleTargetString(r: SimpleTarget) extends JRRuleTarget {
      override def toRuleTarget: RuleTarget = r
    }
    final case class JRRuleTargetComposed(
        include: JRRuleTargetComposition,
        exclude: JRRuleTargetComposition
    ) extends JRRuleTarget {
      override def toRuleTarget: RuleTarget = TargetExclusion(include.toRuleTarget, exclude.toRuleTarget)
    }
    sealed trait JRRuleTargetComposition                 extends JRRuleTarget {
      override def toRuleTarget: TargetComposition
    }
    object JRRuleTargetComposition {
      final case class or(list: List[JRRuleTarget])  extends JRRuleTargetComposition {
        override def toRuleTarget: TargetComposition = TargetUnion(list.map(_.toRuleTarget).toSet)
      }
      final case class and(list: List[JRRuleTarget]) extends JRRuleTargetComposition {
        override def toRuleTarget: TargetComposition = TargetUnion(list.map(_.toRuleTarget).toSet)
      }
    }

    implicit val transformer: Transformer[RuleTarget, JRRuleTarget] = apply _
  }

  final case class JRRuleTargetInfo(
      id:                              JRRuleTarget,
      @jsonField("displayName") name:  String,
      description:                     String,
      @jsonField("enabled") isEnabled: Boolean,
      target:                          JRRuleTarget
  )

  object JRRuleTargetInfo {
    implicit val transformer: Transformer[RuleTargetInfo, JRRuleTargetInfo] =
      Transformer.define[RuleTargetInfo, JRRuleTargetInfo].withFieldRenamed(_.target, _.id).buildTransformer
  }

  // CategoryKind is either JRRuleCategory or String (category id)
  // RuleKind is either JRRule or String (rule id)
  final case class JRFullRuleCategory(
      id:          String,
      name:        String,
      description: String,
      parent:      Option[String],
      categories:  List[JRFullRuleCategory],
      rules:       List[JRRule]
  )
  object JRFullRuleCategory {
    /*
     * Prepare for json.
     * Sort field by ID to keep diff easier.
     */
    def fromCategory(
        cat:      RuleCategory,
        allRules: Map[String, Seq[(Rule, Option[String], Option[(String, Option[String])])]],
        parent:   Option[String]
    ): JRFullRuleCategory = {
      cat
        .into[JRFullRuleCategory]
        .withFieldConst(_.parent, parent)
        .withFieldComputed(
          _.categories,
          _.childs.map(c => JRFullRuleCategory.fromCategory(c, allRules, Some(cat.id.value))).sortBy(_.id)
        )
        .withFieldConst(
          _.rules,
          allRules.get(cat.id.value).getOrElse(Nil).map { case (r, p, s) => JRRule.fromRule(r, None, p, s) }.toList.sortBy(_.id)
        )
        .transform
    }
  }

  // when returning a root category, we have a "data":{"ruleCategories":{.... }}. Seems like a bug, though
  final case class JRCategoriesRootEntryFull(ruleCategories: JRFullRuleCategory)
  final case class JRCategoriesRootEntrySimple(ruleCategories: JRSimpleRuleCategory)
  final case class JRCategoriesRootEntryInfo(ruleCategories: JRRuleCategoryInfo)

  final case class JRSimpleRuleCategory(
      id:          String,
      name:        String,
      description: String,
      parent:      String,
      categories:  List[String],
      rules:       List[String]
  )
  object JRSimpleRuleCategory {
    def fromCategory(cat: RuleCategory, parent: String, rules: List[String]): JRSimpleRuleCategory = {
      cat
        .into[JRSimpleRuleCategory]
        .withFieldComputed(_.id, _.id.value)
        .withFieldConst(_.parent, parent)
        .withFieldComputed(_.categories, _.childs.map(_.id.value).sorted)
        .withFieldConst(_.rules, rules)
        .transform
    }
  }

  final case class JRRuleCategoryInfo(
      id:          String,
      name:        String,
      description: String,
      parent:      Option[String],
      categories:  List[JRRuleCategoryInfo],
      rules:       List[JRRuleInfo]
  )
  object JRRuleCategoryInfo   {
    def fromCategory(
        cat:      RuleCategory,
        allRules: Map[RuleCategoryId, Seq[Rule]],
        parent:   Option[String]
    ): JRRuleCategoryInfo = {
      cat
        .into[JRRuleCategoryInfo]
        .withFieldConst(_.parent, parent)
        .withFieldComputed(
          _.categories,
          _.childs.map(c => JRRuleCategoryInfo.fromCategory(c, allRules, Some(cat.id.value))).sortBy(_.id)
        )
        .withFieldConst(
          _.rules,
          allRules.get(cat.id).getOrElse(Nil).map(JRRuleInfo.fromRule).toList.sortBy(_.id)
        )
        .transform
    }
  }

  final case class JRRuleInfo(
      id:               String,
      displayName:      String,
      categoryId:       String,
      shortDescription: String,
      longDescription:  String,
      enabled:          Boolean,
      tags:             List[Map[String, String]]
  )
  object JRRuleInfo           {
    def fromRule(rule: Rule): JRRuleInfo = {
      rule
        .into[JRRuleInfo]
        .enableBeanGetters
        .withFieldComputed(_.id, _.id.serialize)
        .withFieldRenamed(_.name, _.displayName)
        .withFieldComputed(_.categoryId, _.categoryId.value)
        .withFieldComputed(_.tags, x => JRTags.fromTags(rule.tags))
        .transform
    }
  }

  final case class JRGlobalParameter(
      changeRequestId: Option[String] = None,
      id:              String,
      value:           ConfigValue,
      description:     String,
      inheritMode:     Option[InheritMode],
      provider:        Option[PropertyProvider]
  )

  object JRGlobalParameter         {
    import GenericProperty.*
    def empty(name: String): JRGlobalParameter = JRGlobalParameter(None, name, "".toConfigValue, "", None, None)
    def fromGlobalParameter(p: GlobalParameter, crId: Option[ChangeRequestId]): JRGlobalParameter = {
      JRGlobalParameter(crId.map(_.value.toString), p.name, p.value, p.description, p.inheritMode, p.provider)
    }
  }

  final case class JRPropertyHierarchyStatus(
      hasChildTypeConflicts: Boolean,
      fullHierarchy:         JRParentPropertyDetails,
      errorMessage:          Option[String]
  )
  object JRPropertyHierarchyStatus {
    def fromInherited(
        inheritedPropertyStatus: InheritedPropertyStatus
    ): JRPropertyHierarchyStatus = {
      val parentProperties = inheritedPropertyStatus.property

      JRPropertyHierarchyStatus(
        inheritedPropertyStatus.hasChildTypeConflicts,
        JRParentPropertyDetails.fromParentProperty(parentProperties),
        inheritedPropertyStatus.errorMessage
      )

    }
    def fromParentProperties(
        hasChildTypeConflicts: Boolean,
        parentProperties:      ParentProperty[?]
    ): JRPropertyHierarchyStatus = {

      JRPropertyHierarchyStatus(
        hasChildTypeConflicts,
        JRParentPropertyDetails.fromParentProperty(parentProperties), // sort to keep hierarchical order
        None
      )

    }
  }

  @jsonDiscriminator("kind") sealed trait JRParentPropertyDetails {
    def valueType: String
  }

  object JRParentPropertyDetails {
    @jsonHint("global")
    final case class JRParentGlobalDetails(
        valueType: String
    ) extends JRParentPropertyDetails
    @jsonHint("group")
    final case class JRParentGroupDetails(
        name:      String,
        id:        String,
        valueType: String,
        parent:    Option[JRParentPropertyDetails]
    ) extends JRParentPropertyDetails
    @jsonHint("node")
    final case class JRParentNodeDetails(
        name:      String,
        id:        String,
        valueType: String,
        parent:    Option[JRParentPropertyDetails]
    ) extends JRParentPropertyDetails

    def fromParentProperty(p: ParentProperty[?]): JRParentPropertyDetails = {
      def serializeValueType(v: ConfigValue): String = v.valueType.name().toLowerCase().capitalize
      p match {
        case g: ParentProperty.Group =>
          JRParentGroupDetails(g.name, g.id, serializeValueType(g.value.value), g.parentProperty.map(fromParentProperty))
        case n: ParentProperty.Node  =>
          JRParentNodeDetails(n.name, n.id, serializeValueType(n.value.value), n.parentProperty.map(fromParentProperty))
        case ParentProperty.Global(value) =>
          JRParentGlobalDetails(serializeValueType(value.value))
      }
    }
  }

  // similar to JRGlobalParameter but s/id/name and no changeRequestId
  final case class JRProperty(
      name:            String,
      value:           ConfigValue,
      description:     Option[String],
      inheritMode:     Option[InheritMode],
      provider:        Option[PropertyProvider],
      hierarchy:       Option[JRPropertyHierarchy],
      hierarchyStatus: Option[JRPropertyHierarchyStatus],
      origval:         Option[ConfigValue]
  )
  object JRProperty {
    def fromGroupProp(p: GroupProperty): JRProperty = {
      val desc = if (p.description.trim.isEmpty) None else Some(p.description)
      JRProperty(p.name, p.value, desc, p.inheritMode, p.provider, None, None, None)
    }

    def fromNodeProp(p: NodeProperty): JRProperty = {
      val desc = if (p.description.trim.isEmpty) None else Some(p.description)
      JRProperty(p.name, p.value, desc, p.inheritMode, p.provider, None, None, None)
    }
    def fromInheritedPropertyStatus(
        inheritedPropertyStatus: InheritedPropertyStatus,
        renderInHtml:            RenderInheritedProperties,
        escapeHtml:              Boolean = false
    ): JRProperty = {
      // we need to consider the main property in case of success
      val propsHierarchy  = inheritedPropertyStatus match {
        case s: SuccessInheritedPropertyStatus =>
          s.property
        case e: ErrorInheritedPropertyStatus   =>
          e.property
      }
      val parents         = transformParentProperties(propsHierarchy, renderInHtml, escapeHtml)
      val origval         = getHierarchyOriginalValue(propsHierarchy)
      val hierarchyStatus =
        JRPropertyHierarchyStatus.fromInherited(inheritedPropertyStatus)

      val propertyId = inheritedPropertyStatus.propertyId.value
      inheritedPropertyStatus match {
        case s: SuccessInheritedPropertyStatus =>
          JRProperty(
            propertyId,
            s.prop.prop.value,
            s.nonEmptyDescription,
            s.prop.prop.inheritMode,
            s.prop.prop.provider,
            parents,
            Some(hierarchyStatus),
            Some(origval)
          )
        case e: ChildTypeConflictStatus        =>
          JRProperty(
            propertyId,
            e.property.resolvedValue.value,
            e.nonEmptyDescription,
            e.inheritMode,
            e.provider,
            parents,
            Some(hierarchyStatus),
            Some(origval)
          )
        case e: ErrorInheritedPropertyStatus   =>
          JRProperty(
            propertyId,
            origval, // .getOrElse("### error ###".toConfigValue), // comment should be safe for display only
            e.nonEmptyDescription,
            e.inheritMode,
            e.provider,
            parents,
            Some(hierarchyStatus),
            Some(origval)
          )
      }
    }
    def fromNodePropertyHierarchy(
        prop:         PropertyHierarchy,
        renderInHtml: RenderInheritedProperties,
        escapeHtml:   Boolean
    ): JRProperty = {
      fromNodePropertyHierarchy(
        prop,
        hasChildTypeConflicts = false,
        renderInHtml,
        escapeHtml = escapeHtml
      )
    }

    def fromNodePropertyHierarchy(
        prop:                  PropertyHierarchy,
        hasChildTypeConflicts: Boolean,
        renderInHtml:          RenderInheritedProperties,
        escapeHtml:            Boolean = false
    ): JRProperty = {
      val hierarchyStatus = JRPropertyHierarchyStatus.fromParentProperties(hasChildTypeConflicts, prop.hierarchy)
      val desc            = if (prop.prop.description.trim.isEmpty) None else Some(prop.prop.description)
      JRProperty(
        prop.prop.name,
        prop.prop.value,
        desc,
        prop.prop.inheritMode,
        prop.prop.provider,
        transformParentProperties(prop.hierarchy, renderInHtml, escapeHtml),
        Some(hierarchyStatus),
        Some(getHierarchyOriginalValue(prop.hierarchy))
      )
    }

    /**
     * Transform proprety hierarchy into HTML or JSON
     * Provided hierarchy should be sorted from original order of parent properties :
     * - nodes
     * - groups
     * - global params
     */
    private def transformParentProperties(
        hierarchy:    ParentProperty[?],
        renderInHtml: RenderInheritedProperties,
        escapeHtml:   Boolean
    ): Option[JRPropertyHierarchy] = {
      def renderHtml(nodeParentProperty: ParentProperty[?]): List[String] = {
        nodeParentProperty match {
          case n: ParentProperty.Node  =>
            n.parentProperty
              .map(renderHtml)
              .getOrElse(Nil) :::
            (s"<p>from <b>Node ${n.name} (${n.id})</b>:<pre>${(if (escapeHtml) xml.Utility.escape(_: String)
                                                               else identity[String])
                .apply(n.value.value.render(ConfigRenderOptions.defaults().setOriginComments(false)))}</pre></p>" :: Nil)
          case g: ParentProperty.Group =>
            g.parentProperty
              .map(renderHtml)
              .getOrElse(Nil) :::
            (s"<p>from <b>Group ${g.name} (${g.id})</b>:<pre>${(if (escapeHtml) xml.Utility.escape(_: String)
                                                                else identity[String])
                .apply(g.value.value.render(ConfigRenderOptions.defaults().setOriginComments(false)))}</pre></p>" :: Nil)
          case ParentProperty.Global(v) =>
            s"<p>from <b>Global parameter </b>:<pre>${(if (escapeHtml) xml.Utility.escape(_: String) else identity[String])
                .apply(v.value.render(ConfigRenderOptions.defaults().setOriginComments(false)))}</pre></p>" :: Nil
        }
      }
      val parents = renderInHtml match {
        case RenderInheritedProperties.HTML =>
          JRPropertyHierarchyHtml(renderHtml(hierarchy).mkString(""))
        case RenderInheritedProperties.JSON =>
          JRPropertyHierarchyJson(JRParentProperty.fromParentProperty(hierarchy))
      }
      Some(parents)

    }

    /**
     * The first element of the hierarchy in the original order is the original value
     */
    private def getHierarchyOriginalValue(hierarchy: ParentProperty[?]): ConfigValue =
      hierarchy.value.value
  }

  @jsonDiscriminator("kind") sealed trait JRParentProperty { def value: ConfigValue }
  object JRParentProperty                                  {
    @jsonHint("global")
    final case class JRParentGlobal(
        value: ConfigValue
    ) extends JRParentProperty

    @jsonHint("group")
    final case class JRParentGroup(
        name:          String,
        id:            String,
        value:         ConfigValue,
        resolvedValue: ConfigValue,
        parent:        Option[JRParentProperty]
    ) extends JRParentProperty

    @jsonHint("node")
    final case class JRParentNode(
        name:          String,
        id:            String,
        value:         ConfigValue,
        resolvedValue: ConfigValue,
        parent:        Option[JRParentProperty]
    ) extends JRParentProperty
    def fromParentProperty(p: ParentProperty[?]): JRParentProperty = {
      p match {
        case n: ParentProperty.Node  =>
          JRParentNode(n.name, n.id, n.value.value, n.resolvedValue.value, n.parentProperty.map(fromParentProperty))
        case g: ParentProperty.Group =>
          JRParentGroup(g.name, g.id, g.value.value, g.resolvedValue.value, g.parentProperty.map(fromParentProperty))
        case _ =>
          JRParentGlobal(p.value.value)
      }
    }
  }

  sealed trait JRPropertyHierarchy extends Product
  object JRPropertyHierarchy {
    final case class JRPropertyHierarchyHtml(html: String)              extends JRPropertyHierarchy
    final case class JRPropertyHierarchyJson(parents: JRParentProperty) extends JRPropertyHierarchy
  }

  final case class JRGroupInheritedProperties(
      groupId:      String,
      properties:   Chunk[JRProperty],
      errorMessage: Option[String]
  )

  final case class JRPropertyId(value: String) extends AnyVal
  object JRPropertyId {
    implicit val ordering: Ordering[JRPropertyId] = Ordering[String].on(_.value)
  }

  /**
   * Model that specifies what one or all properties has :
   * an error message that needs to be there when
   * the global status of property is fine,
   * and which is also optional in the status of a property
   */
  sealed trait PropertyStatus {
    def errorMessage: Option[String]
  }
  object PropertyStatus       {

    /**
   * When there has been some errors when computing the hierarchy,
   * we can have multiple property statuses in global or specific error:
   * find all of them
   */
    def fromFailedHierarchy(f: FailedNodePropertyHierarchy): Chunk[PropertyStatus] = {
      f.error match {
        case specificError: PropertyHierarchySpecificError =>
          Chunk.from(specificError.propertiesErrors.collect {
            case (_, (p, cp, message)) if p.visibility == Displayed =>
              // there are individual errors by property that can be resolved and rendered individually
              ErrorInheritedPropertyStatus.from(cp.head, message)
          })
        case _:             PropertyHierarchyError         =>
          // we don't know the errored props, it may be all of them and there may be a global status error
          Chunk(GlobalPropertyStatus.fromResolvedNodeProperty(f))
      }

    }

    implicit class IterablePropertyStatusOps(it: Iterable[PropertyStatus]) {

      /**
       * Split global status from inherited one :
       * global ok/error status vs status on specific properties.
       *
       * The global one cannot be duplicated (for distinct error messages)
       * so it needs to be a set.
       */
      def separate: (Set[GlobalPropertyStatus], Iterable[InheritedPropertyStatus]) = {
        val (global, inherited) = it.partitionMap {
          case g: GlobalPropertyStatus    =>
            Left(g)
          case i: InheritedPropertyStatus =>
            Right(i)
        }
        global.toSet -> inherited
      }

      /**
       * Gather all error messages
       *
       * Implementation uses a toSet because iterable
       * is usually already a set
       */
      def distictErrorMessages: Set[String] = {
        it.flatMap(_.errorMessage.map(_.strip).filter(_.nonEmpty)).toSet
      }

    }

  }

  /**
   * Global properties are OK or are in error :
   * they can have global error messages in properties
   * (this does not prevent inherited properties to exist
   * and also have errors)
   */
  sealed trait GlobalPropertyStatus extends PropertyStatus
  object GlobalPropertyStatus {
    case object GlobalPropertyOkStatus extends GlobalPropertyStatus {
      override def errorMessage: Option[String] = None
    }
    case class GlobalPropertyErrorStatus private[GlobalPropertyStatus] (override val errorMessage: Some[String])
        extends GlobalPropertyStatus

    def fromResolvedNodeProperty(hierarchy: ResolvedNodePropertyHierarchy): GlobalPropertyStatus = hierarchy match {
      case f: FailedNodePropertyHierarchy if f.noResolved => // no success + failure is a global failure
        new GlobalPropertyErrorStatus(Some(f.getMessage))

      case f: FailedNodePropertyHierarchy =>
        f.error match {
          case _: PropertyHierarchySpecificError => // there is only an error specific to single properties
            GlobalPropertyOkStatus
          case _: PropertyHierarchyError         => // there is an error that can apply to many properties
            new GlobalPropertyErrorStatus(Some(f.getMessage))
        }

      case _: SuccessNodePropertyHierarchy =>
        GlobalPropertyOkStatus
    }
  }

  /**
   * Status of individual properties :
   * there is always a property identifier (the property name),
   * an set of properties inherited from parent (ordered by ancestry),
   *
   * There can be conflicts in children properties so this identifies
   * as a subtype.
   */
  sealed trait InheritedPropertyStatus extends PropertyStatus {
    def property:   ParentProperty[?]
    def propertyId: JRPropertyId = property.resolvedValue.name.transformInto[JRPropertyId]

    def inheritMode: Option[InheritMode] = property.resolvedValue.inheritMode

    def description:           String                   = property.resolvedValue.description
    def provider:              Option[PropertyProvider] = property.resolvedValue.provider
    def hasChildTypeConflicts: Boolean

    def nonEmptyDescription: Option[String] = Some(description).filter(_.nonEmpty)
  }

  // This describes a property that has no problem
  final case class SuccessInheritedPropertyStatus private[apidata] (
      prop: PropertyHierarchy
  ) extends InheritedPropertyStatus {

    override def errorMessage:          Option[String] = None
    override def hasChildTypeConflicts: Boolean        = false

    /**
     * The hierarchy of the main property, not the one as seen by the descendants (parentsInheritedProps)
     */
    def property: ParentProperty[?] = prop.hierarchy
  }

  // This describes a property that is in error, subtypes can be seen as this generic error
  sealed abstract class ErrorInheritedPropertyStatus(
      override val property:              ParentProperty[?],
      override val errorMessage:          Some[String],
      override val hasChildTypeConflicts: Boolean = false
  ) extends InheritedPropertyStatus {}
  object ErrorInheritedPropertyStatus {
    def from(
        prop:         ParentProperty[?],
        errorMessage: String
    ): ErrorInheritedPropertyStatus = {
      new ErrorInheritedPropertyStatus(
        prop,
        Some(errorMessage)
      ) {}
    }
  }

  // This describes the specific error of having child type conflicts
  // It is used to construct an error with known attributes : conflicts and message
  final case class ChildTypeConflictStatus(
      val prop: PropertyHierarchy
  ) extends ErrorInheritedPropertyStatus(
        prop.hierarchy,
        Some("Conflicting types in inherited node properties"),
        hasChildTypeConflicts = true
      ) {}

  object InheritedPropertyStatus {

    /**
     * Validate a NodePropertyHierarchy to get property status
     */
    def from(parent: PropertyHierarchy): InheritedPropertyStatus = {
      fromChildren(
        parent,
        Some(parent.hierarchy)
      )
    }

    /**
     * Validate by doing checks against children :
     * are there conflicting types in the children hierarchy (or current one of parent) ?
     */
    def fromChildren(parent: PropertyHierarchy, children: Option[ParentProperty[?]]): InheritedPropertyStatus = {
      children match {
        case None                 =>
          SuccessInheritedPropertyStatus(parent)
        case Some(childHierarchy) =>
          val hasConflicts = MergeNodeProperties
            .checkValueTypes(childHierarchy)
            .isLeft

          if (hasConflicts) {
            ChildTypeConflictStatus(
              parent
            )
          } else {
            // when using the parent hierarchy "this group" is displayed as name, instead of the real group name
            // we should be changing the mergeDefault method to get and use the node names and group names information instead of hardcodec "this node"/"this group"
            SuccessInheritedPropertyStatus(parent)
          }
      }

    }

    implicit class IterableInheritedPropertyStatusOps(properties: Iterable[InheritedPropertyStatus]) {

      /**
        * Merge properties with the same id together, and transform properties into json response
        */
      def toJRProperty(implicit
          renderInHtml: RenderInheritedProperties
      ): Chunk[JRProperty] = {

        Chunk
          .from(
            properties
              .groupMapReduce(_.propertyId)(identity) {
                case (a, b) =>
                  // we should only reduce under the same propertyId.
                  // the provider is supposed to be the same for a property at any level of the hierarchy
                  // the inheritMode is also defined only at the top-most level and cannot be overridden
                  // same for description
                  a
              }
              .values
          )
          .sortBy(_.propertyId)
          .map(p => JRProperty.fromInheritedPropertyStatus(p, renderInHtml))
      }
    }
  }
  object JRGroupInheritedProperties {
    def fromGroup(
        groupId:          NodeGroupId,
        propertyStatuses: Iterable[PropertyStatus],
        renderInHtml:     RenderInheritedProperties
    ): JRGroupInheritedProperties = {
      implicit val render: RenderInheritedProperties = renderInHtml
      val (global, inherited) = propertyStatuses.separate
      val properties          = inherited.toJRProperty

      JRGroupInheritedProperties(
        groupId.serialize,
        properties,
        Some(global.distictErrorMessages.mkString("\n")).filter(_.nonEmpty)
      )
    }
  }

  final case class JRNodeInheritedProperties(
      nodeId:       NodeId,
      properties:   Chunk[JRProperty],
      errorMessage: Option[String]
  )
  object JRNodeInheritedProperties  {
    def fromNode(
        nodeId:           NodeId,
        propertyStatuses: Iterable[PropertyStatus],
        renderInHtml:     RenderInheritedProperties
    ): JRNodeInheritedProperties = {
      implicit val render: RenderInheritedProperties = renderInHtml
      val (global, inherited) = propertyStatuses.separate
      val properties          = inherited.toJRProperty

      JRNodeInheritedProperties(
        nodeId,
        properties,
        Some(global.distictErrorMessages.mkString("\n")).filter(_.nonEmpty)
      )
    }
  }

  final case class JRCriterium(
      objectType: String,
      attribute:  String,
      comparator: String,
      value:      String
  ) {
    def toStringCriterionLine: StringCriterionLine = StringCriterionLine(objectType, attribute, comparator, Some(value))
  }

  object JRCriterium {
    def fromCriterium(c: CriterionLine): JRCriterium = {
      c.into[JRCriterium]
        .withFieldComputed(_.objectType, _.objectType.objectType)
        .withFieldComputed(_.attribute, _.attribute.name)
        .withFieldComputed(_.comparator, _.comparator.id)
        .transform
    }
  }

  final case class JRQuery(
      select:      String,
      composition: String,
      transform:   Option[String],
      where:       List[JRCriterium]
  )

  object JRQuery {
    def fromQuery(query: Query): JRQuery = {
      JRQuery(
        query.returnType.value,
        query.composition.value,
        query.transform match {
          case ResultTransformation.Identity => None
          case x                             => Some(x.value)
        },
        query.criteria.map(JRCriterium.fromCriterium(_))
      )
    }
  }

  final case class JRGroup(
      changeRequestId: Option[String] = None,
      id:              String,
      displayName:     String,
      description:     String,
      category:        String,
      query:           Option[JRQuery],
      nodeIds:         List[String],
      dynamic:         Boolean,
      enabled:         Boolean,
      groupClass:      List[String],
      properties:      List[JRProperty],
      target:          String,
      system:          Boolean
  ) {
    def toGroup(queryParser: CmdbQueryParser): IOResult[(NodeGroupCategoryId, NodeGroup)] = {
      for {
        i <- NodeGroupId.parse(id).toIO
        q <- query match {
               case None    => None.succeed
               case Some(q) =>
                 for {
                   t <- QueryReturnType(q.select).toIO
                   x <- queryParser
                          .parse(StringQuery(t, Some(q.composition), q.transform, q.where.map(_.toStringCriterionLine)))
                          .toIO
                 } yield Some(x)
             }
      } yield {
        (
          NodeGroupCategoryId(category),
          NodeGroup(
            i,
            displayName,
            description,
            properties.map(p => GroupProperty(p.name, GitVersion.DEFAULT_REV, p.value, p.inheritMode, p.provider)),
            q,
            dynamic,
            nodeIds.map(NodeId(_)).toSet,
            enabled,
            system
          )
        )
      }
    }
  }

  object JRGroup {
    def empty(id: String): JRGroup = JRGroup(
      None,
      id,
      "",
      "",
      "",
      None,
      Nil,
      dynamic = false,
      enabled = false,
      groupClass = Nil,
      properties = Nil,
      target = "",
      system = false
    )

    def fromGroup(group: NodeGroup, catId: NodeGroupCategoryId, crId: Option[ChangeRequestId]): JRGroup = {
      group
        .into[JRGroup]
        .enableBeanGetters
        .withFieldConst(_.changeRequestId, crId.map(_.value.toString))
        .withFieldComputed(_.id, _.id.serialize)
        .withFieldRenamed(_.name, _.displayName)
        .withFieldConst(_.category, catId.value)
        .withFieldComputed(_.query, _.query.map(JRQuery.fromQuery(_)))
        .withFieldComputed(_.nodeIds, _.serverList.toList.map(_.value).sorted)
        .withFieldComputed(_.groupClass, x => List(x.id.serialize, x.name).map(RuleTarget.toCFEngineClassName _).sorted)
        .withFieldComputed(_.properties, _.properties.filter(_.visibility == Displayed).map(JRProperty.fromGroupProp(_)))
        .withFieldComputed(_.target, x => GroupTarget(x.id).target)
        .withFieldComputed(_.system, _.isSystem)
        .transform
    }
  }

  /**
   * Data container for the whole group category tree, provides the "groupCategories" dataContainer
   */
  final case class JRGroupCategoriesFull(groupCategories: JRFullGroupCategory)

  /**
   * Data container for the group category tree with minimal info, provides the "groupCategories" dataContainer
   */
  final case class JRGroupCategoriesMinimal(groupCategories: JRMinimalGroupCategory)

  /**
   * Representation of a group category with full group information
   */
  final case class JRFullGroupCategory(
      id:                                     NodeGroupCategoryId,
      name:                                   String,
      description:                            String,
      parent:                                 NodeGroupCategoryId,
      @jsonField("categories") subCategories: List[JRFullGroupCategory],
      groups:                                 List[JRGroup],
      @jsonField("targets") targetInfos:      List[JRRuleTargetInfo]
  )

  object JRFullGroupCategory {
    /*
     * Somehow the chimney transformer derivation does not provide a way to pass the parent due to recursive type,
     * so we have to make an inline transformer.
     * Sort by ID, also in groups to keep diff easier
     */
    def fromCategory(
        cat:    FullNodeGroupCategory,
        parent: Option[NodeGroupCategoryId]
    ): JRFullGroupCategory = {
      cat
        .into[JRFullGroupCategory]
        .withFieldConst(
          _.parent,
          parent.getOrElse(cat.id)
        )
        .withFieldComputed(
          _.subCategories,
          cat => cat.subCategories.map(c => fromCategory(c, Some(cat.id))).sortBy(_.id.value)
        )
        .withFieldComputed(
          _.groups,
          cat => {
            cat.ownGroups.values.toList.map(t => JRGroup.fromGroup(t.nodeGroup, cat.id, None)).sortBy(_.id)
          }
        )
        .withFieldComputed(
          _.targetInfos,
          _.targetInfos.collect {
            case t @ FullRuleTargetInfo(_: FullOtherTarget, _, _, _, _) => t.toTargetInfo.transformInto[JRRuleTargetInfo]
          }
        )
        .transform
    }
  }

  /**
   * Representation of a group category with bare minimum group and subcategories ids list
   */
  final case class JRMinimalGroupCategory(
      id:                                     NodeGroupCategoryId,
      name:                                   String,
      description:                            String,
      parent:                                 NodeGroupCategoryId,
      @jsonField("categories") subCategories: List[NodeGroupCategoryId],
      groups:                                 List[NodeGroupId],
      @jsonField("targets") targetInfos:      List[JRRuleTargetInfo]
  )

  object JRMinimalGroupCategory {
    def fromCategory(
        cat:    FullNodeGroupCategory,
        parent: NodeGroupCategoryId
    ): JRMinimalGroupCategory = {
      cat
        .into[JRMinimalGroupCategory]
        .withFieldConst(
          _.parent,
          parent
        )
        .withFieldComputed(
          _.subCategories,
          _.subCategories.map(_.id).sortBy(_.value)
        )
        .withFieldComputed(
          _.groups,
          cat => {
            cat.ownGroups.keys.toList.sortBy(_.serialize)
          }
        )
        .withFieldComputed(
          _.targetInfos,
          _.targetInfos.collect {
            case t @ FullRuleTargetInfo(_: FullOtherTarget, _, _, _, _) => t.toTargetInfo.transformInto[JRRuleTargetInfo]
          }
        )
        .transform
    }
  }

  /**
   * Representation of a group category with minimal group information:
   * it only contains information useful for display of a group within the hierarchy of groups.
   */
  final case class JRGroupCategoryInfo(
      id:                                     String,
      name:                                   String,
      description:                            String,
      @jsonField("categories") subCategories: List[JRGroupCategoryInfo],
      groups:                                 List[JRGroupCategoryInfo.JRGroupInfo],
      @jsonField("targets") targetInfos:      List[JRRuleTargetInfo]
  )

  object JRGroupCategoryInfo {
    final case class JRGroupInfo(
        id:                              NodeGroupId,
        @jsonField("displayName") name:  String,
        description:                     String,
        category:                        Option[NodeGroupCategoryId],
        @jsonField("dynamic") isDynamic: Boolean,
        @jsonField("enabled") isEnabled: Boolean,
        target:                          String
    )
    object JRGroupInfo {
      implicit def transformer(implicit categoryId: Option[NodeGroupCategoryId]): Transformer[NodeGroup, JRGroupInfo] = {
        Transformer
          .define[NodeGroup, JRGroupInfo]
          .enableBeanGetters
          .withFieldConst(_.category, categoryId)
          .withFieldComputed(_.target, x => GroupTarget(x.id).target)
          .buildTransformer
      }

    }

    implicit lazy val transformer: Transformer[FullNodeGroupCategory, JRGroupCategoryInfo] = {
      Transformer
        .define[FullNodeGroupCategory, JRGroupCategoryInfo]
        .withFieldComputed(
          _.subCategories,
          _.subCategories.sortBy(_.id.value).transformInto[List[JRGroupCategoryInfo]]
        )
        .withFieldComputed(
          _.groups,
          cat => {
            cat.ownGroups.values.toList.map(t => {
              implicit val categoryId: Option[NodeGroupCategoryId] = cat.categoryByGroupId.get(t.nodeGroup.id)
              t.nodeGroup.transformInto[JRGroupInfo]
            })
          }
        )
        .withFieldComputed(
          _.targetInfos,
          _.targetInfos.collect {
            case t @ FullRuleTargetInfo(_: FullOtherTarget, _, _, _, _) => t.toTargetInfo.transformInto[JRRuleTargetInfo]
          }
        )
        .buildTransformer
    }
  }

  final case class JRRuleNodesDirectives(
      id: String, // id is in format uid+rev

      numberOfNodes:      Int,
      numberOfDirectives: Int
  )

  object JRRuleNodesDirectives {
    // create an empty json rule with just ID set
    def empty(id: String): JRRuleNodesDirectives = JRRuleNodesDirectives(id, 0, 0)

    // create from a rudder business rule
    def fromData(ruleId: RuleId, nodesCount: Int, directivesCount: Int): JRRuleNodesDirectives = {
      JRRuleNodesDirectives(ruleId.serialize, nodesCount, directivesCount)
    }
  }

  final case class JRHooks(
      basePath:  String,
      hooksFile: List[String]
  )

  object JRHooks {
    def fromHook(hook: Hooks): JRHooks = {
      hook
        .into[JRHooks]
        .withFieldConst(_.basePath, hook.basePath)
        .withFieldConst(_.hooksFile, hook.hooksFile.map(_._1))
        .transform
    }
  }

  implicit def seqToChunkTransformer[A, B](implicit transformer: Transformer[A, B]): Transformer[Seq[A], Chunk[B]] = {
    (a: Seq[A]) => Chunk.fromIterable(a.map(transformer.transform))
  }

}
//////////////////////////// zio-json encoders ////////////////////////////

object JRRuleEncoder {

  import JsonResponseObjects.*
  import JsonResponseObjects.JRRuleTarget.*

  // FIXME: hide / split some encoders to reduce method size
  implicit val targetEncoder: JsonEncoder[JRRuleTarget] = new JsonEncoder[JRRuleTarget] {
    implicit lazy val stringTargetEnc: JsonEncoder[JRRuleTargetString]          = JsonEncoder[String].contramap(_.r.target)
    implicit lazy val andTargetEnc:    JsonEncoder[JRRuleTargetComposition.or]  = JsonEncoder[List[JRRuleTarget]].contramap(_.list)
    implicit lazy val orTargetEnc:     JsonEncoder[JRRuleTargetComposition.and] = JsonEncoder[List[JRRuleTarget]].contramap(_.list)
    implicit lazy val comp1TargetEnc:  JsonEncoder[JRRuleTargetComposed]        = DeriveJsonEncoder.gen
    implicit lazy val comp2TargetEnc:  JsonEncoder[JRRuleTargetComposition]     = DeriveJsonEncoder.gen

    override def unsafeEncode(a: JRRuleTarget, indent: Option[Int], out: Write): Unit = {
      a match {
        case x: JRRuleTargetString      => stringTargetEnc.unsafeEncode(x, indent, out)
        case x: JRRuleTargetComposed    => comp1TargetEnc.unsafeEncode(x, indent, out)
        case x: JRRuleTargetComposition => comp2TargetEnc.unsafeEncode(x, indent, out)
      }
    }
  }

  implicit lazy val applicationStatusEncoder:  JsonEncoder[JRApplicationStatus]   = DeriveJsonEncoder.gen
  implicit lazy val ruleTargetInfoEncoder:     JsonEncoder[JRRuleTargetInfo]      = DeriveJsonEncoder.gen
  implicit lazy val ruleEncoder:               JsonEncoder[JRRule]                = DeriveJsonEncoder.gen
  implicit lazy val ruleNodesDirectiveEncoder: JsonEncoder[JRRuleNodesDirectives] = DeriveJsonEncoder.gen
  implicit lazy val ruleInfoEncoder:           JsonEncoder[JRRuleInfo]            = DeriveJsonEncoder.gen
}

object CategoryEncoder {
  import JRRuleEncoder.*
  import JsonResponseObjects.*

  implicit lazy val simpleCategoryEncoder: JsonEncoder[JRSimpleRuleCategory]        = DeriveJsonEncoder.gen
  implicit lazy val fullCategoryEncoder:   JsonEncoder[JRFullRuleCategory]          = DeriveJsonEncoder.gen
  implicit lazy val infoCategoryEncoder:   JsonEncoder[JRRuleCategoryInfo]          = DeriveJsonEncoder.gen
  implicit lazy val rootCategoryEncoder1:  JsonEncoder[JRCategoriesRootEntryFull]   = DeriveJsonEncoder.gen
  implicit lazy val rootCategoryEncoder2:  JsonEncoder[JRCategoriesRootEntrySimple] = DeriveJsonEncoder.gen
  implicit lazy val rootCategoryEncoder3:  JsonEncoder[JRCategoriesRootEntryInfo]   = DeriveJsonEncoder.gen

}

object DirectiveEncoder {
  import JsonResponseObjects.*

  implicit lazy val directiveSectionVarEncoder:    JsonEncoder[JRDirectiveSectionVar]    = DeriveJsonEncoder.gen
  implicit lazy val directiveSectionHolderEncoder: JsonEncoder[JRDirectiveSectionHolder] = DeriveJsonEncoder.gen
  implicit lazy val directiveSectionEncoder:       JsonEncoder[JRDirectiveSection]       = DeriveJsonEncoder.gen
  implicit lazy val directiveEncoder:              JsonEncoder[JRDirective]              = DeriveJsonEncoder.gen
  implicit lazy val directivesEncoder:             JsonEncoder[JRDirectives]             = DeriveJsonEncoder.gen
  implicit lazy val directiveTreeTechniqueEncoder: JsonEncoder[JRDirectiveTreeTechnique] = DeriveJsonEncoder.gen
  implicit lazy val directiveTreeEncoder:          JsonEncoder[JRDirectiveTreeCategory]  = DeriveJsonEncoder.gen
}

trait RudderJsonEncoders {
  export CategoryEncoder.*
  export DirectiveEncoder.*
  export JRRuleEncoder.*
  import JsonResponseObjects.*
  import JsonResponseObjects.JRNodeDetailLevel.*
  import com.normation.inventory.domain.JsonSerializers.implicits.*
  import com.normation.rudder.domain.reports.ComplianceLevelSerialisation.array.*
  import com.normation.rudder.facts.nodes.NodeFactSerialisation.*
  import com.normation.rudder.facts.nodes.NodeFactSerialisation.SimpleCodec.*
  import com.normation.rudder.score.ScoreSerializer.*
  import com.normation.utils.DateFormaterService.json.*

  implicit lazy val ruleIdEncoder:          JsonEncoder[RuleId]              = JsonEncoder[String].contramap(_.serialize)
  implicit lazy val groupIdEncoder:         JsonEncoder[NodeGroupId]         = JsonEncoder[String].contramap(_.serialize)
  implicit lazy val groupCategoryIdEncoder: JsonEncoder[NodeGroupCategoryId] = JsonEncoder[String].contramap(_.value)
  implicit lazy val hookEncoder:            JsonEncoder[JRHooks]             = DeriveJsonEncoder.gen
  implicit lazy val rulesEncoder:           JsonEncoder[JRRules]             = DeriveJsonEncoder.gen
  implicit lazy val activeTechniqueEncoder: JsonEncoder[JRActiveTechnique]   = DeriveJsonEncoder.gen

  implicit lazy val configValueEncoder:      JsonEncoder[ConfigValue]              = new JsonEncoder[ConfigValue] {
    override def unsafeEncode(a: ConfigValue, indent: Option[Int], out: Write): Unit = {
      val options = ConfigRenderOptions.concise().setJson(true).setFormatted(indent.isDefined)
      out.write(a.render(options))
    }
  }
  implicit lazy val propertyProviderEncoder: JsonEncoder[Option[PropertyProvider]] = JsonEncoder[Option[String]].contramap {
    case None | Some(PropertyProvider.defaultPropertyProvider) => None
    case Some(x)                                               => Some(x.value)
  }
  implicit lazy val inheritModeEncoder:      JsonEncoder[InheritMode]              = JsonEncoder[String].contramap(_.value)
  implicit lazy val globalParameterEncoder:  JsonEncoder[JRGlobalParameter]        = DeriveJsonEncoder
    .gen[JRGlobalParameter]
    .contramap(g => {
      // when inheritMode or property provider are set to their default value, don't write them
      g.modify(_.inheritMode)
        .using {
          case Some(InheritMode.Default) => None
          case x                         => x
        }
        .modify(_.provider)
        .using {
          case Some(PropertyProvider.defaultPropertyProvider) => None
          case x                                              => x
        }
    })

  implicit lazy val propertyJRParentProperty: JsonEncoder[JRParentProperty] = DeriveJsonEncoder.gen

  implicit lazy val propertyHierarchyEncoder:        JsonEncoder[JRPropertyHierarchy]        = new JsonEncoder[JRPropertyHierarchy] {
    override def unsafeEncode(a: JRPropertyHierarchy, indent: Option[Int], out: Write): Unit = {
      a match {
        case JRPropertyHierarchy.JRPropertyHierarchyJson(parents) =>
          JsonEncoder[JRParentProperty].unsafeEncode(parents, indent, out)
        case JRPropertyHierarchy.JRPropertyHierarchyHtml(html)    =>
          JsonEncoder[String].unsafeEncode(html, indent, out)
      }
    }
  }
  implicit lazy val propertyDetailsEncoder:          JsonEncoder[JRParentPropertyDetails]    = DeriveJsonEncoder.gen
  implicit lazy val propertyHierarchyStatusEncoder:  JsonEncoder[JRPropertyHierarchyStatus]  = DeriveJsonEncoder.gen
  implicit lazy val propertyEncoder:                 JsonEncoder[JRProperty]                 = DeriveJsonEncoder.gen
  implicit lazy val criteriumEncoder:                JsonEncoder[JRCriterium]                = DeriveJsonEncoder.gen
  implicit lazy val queryEncoder:                    JsonEncoder[JRQuery]                    = DeriveJsonEncoder.gen
  implicit lazy val groupEncoder:                    JsonEncoder[JRGroup]                    = DeriveJsonEncoder.gen
  implicit lazy val objectInheritedObjectProperties: JsonEncoder[JRGroupInheritedProperties] = DeriveJsonEncoder.gen

  implicit lazy val fullGroupCategoryEncoder:      JsonEncoder[JRFullGroupCategory]             = DeriveJsonEncoder.gen
  implicit lazy val minimalGroupCategoryEncoder:   JsonEncoder[JRMinimalGroupCategory]          = DeriveJsonEncoder.gen
  implicit lazy val groupCategoriesFullEncoder:    JsonEncoder[JRGroupCategoriesFull]           = DeriveJsonEncoder.gen
  implicit lazy val groupCategoriesMinimalEncoder: JsonEncoder[JRGroupCategoriesMinimal]        = DeriveJsonEncoder.gen
  implicit lazy val groupInfoEncoder:              JsonEncoder[JRGroupCategoryInfo.JRGroupInfo] = DeriveJsonEncoder.gen
  implicit lazy val groupCategoryInfoEncoder:      JsonEncoder[JRGroupCategoryInfo]             = DeriveJsonEncoder.gen

  implicit lazy val nodeIdEncoder:                  JsonEncoder[NodeId]                    = JsonEncoder[String].contramap(_.value)
  implicit lazy val nodeInheritedPropertiesEncdoer: JsonEncoder[JRNodeInheritedProperties] = DeriveJsonEncoder.gen

  implicit lazy val revisionInfoEncoder: JsonEncoder[JRRevisionInfo] = DeriveJsonEncoder.gen

  implicit lazy val statusEncoder: JsonEncoder[JRInventoryStatus] = JsonEncoder[String].contramap(_.name)
  implicit lazy val stateEncoder:  JsonEncoder[NodeState]         = JsonEncoder[String].contramap(_.name)

  implicit lazy val machineTypeEncoder: JsonEncoder[MachineType] = JsonEncoder[String].contramap(_.name)

  implicit lazy val nodeInfoEncoder: JsonEncoder[JRNodeInfo] = DeriveJsonEncoder.gen

  implicit lazy val nodeChangeStatusEncoder: JsonEncoder[JRNodeChangeStatus] = DeriveJsonEncoder.gen

  implicit lazy val nodeIdStatusEncoder:         JsonEncoder[JRNodeIdStatus]         = DeriveJsonEncoder.gen
  implicit lazy val nodeIdHostnameResultEncoder: JsonEncoder[JRNodeIdHostnameResult] = DeriveJsonEncoder.gen

  implicit lazy val updateNodeEncoder: JsonEncoder[JRUpdateNode] = DeriveJsonEncoder.gen

  // Node details

  implicit lazy val vmTypeEncoder: JsonEncoder[VmType] = JsonEncoder[String].contramap(_.name)

  implicit lazy val nodeKindEncoder:          JsonEncoder[NodeKind]            = JsonEncoder[String].contramap(_.name)
  implicit lazy val managementEncoder:        JsonEncoder[Management]          = DeriveJsonEncoder.gen
  implicit lazy val scheduleOverrideEncoder:  JsonEncoder[ScheduleOverride]    = DeriveJsonEncoder.gen
  implicit lazy val managementDetailsEncoder: JsonEncoder[ManagementDetails]   = DeriveJsonEncoder.gen
  implicit lazy val licenseEncoder:           JsonEncoder[domain.License]      = DeriveJsonEncoder.gen
  implicit lazy val softwareUuidEncoder:      JsonEncoder[domain.SoftwareUuid] = JsonEncoder[String].contramap(_.value)
  implicit lazy val softwareEncoder:          JsonEncoder[domain.Software]     = DeriveJsonEncoder.gen

  implicit lazy val nodeDetailLevelEncoder: JsonEncoder[JRNodeDetailLevel] = DeriveJsonEncoder.gen

  implicit lazy val runAnalysisKindEncoder:  JsonEncoder[RunAnalysisKind]   = JsonEncoder[String].contramap(_.entryName)
  implicit lazy val jrNodeComplianceEncoder: JsonEncoder[JRNodeCompliance]  = JsonEncoder[ComplianceLevel].contramap(_.compliance)
  implicit lazy val policyModeSerializer:    JsonEncoder[PolicyMode]        = JsonEncoder[String].contramap(_.name)
  implicit lazy val jrGlobalScoreEncoder:    JsonEncoder[JRGlobalScore]     = DeriveJsonEncoder.gen
  implicit lazy val nodeDetailTableEncoder:  JsonEncoder[JRNodeDetailTable] = DeriveJsonEncoder.gen
}

/*
 * Decoders for JsonResponse object, when you need to read back something that they serialized.
 */
object JsonResponseObjectDecodes extends RudderJsonDecoders {
  import JsonResponseObjects.*

  implicit lazy val decodeJRParentProperty:          JsonDecoder[JRParentProperty]          = DeriveJsonDecoder.gen
  implicit lazy val decodeJRPropertyHierarchy:       JsonDecoder[JRPropertyHierarchy]       = DeriveJsonDecoder.gen
  implicit lazy val decodePropertyProvider:          JsonDecoder[PropertyProvider]          = JsonDecoder.string.map(s => PropertyProvider(s))
  implicit lazy val decodeJRParentPropertyDetails:   JsonDecoder[JRParentPropertyDetails]   = DeriveJsonDecoder.gen
  implicit lazy val decodeJRPropertyHierarchyStatus: JsonDecoder[JRPropertyHierarchyStatus] = DeriveJsonDecoder.gen
  implicit lazy val decodeJRProperty:                JsonDecoder[JRProperty]                = DeriveJsonDecoder.gen

  implicit lazy val decodeJRCriterium:           JsonDecoder[JRCriterium]           = DeriveJsonDecoder.gen
  implicit lazy val decodeJRDirectiveSectionVar: JsonDecoder[JRDirectiveSectionVar] = DeriveJsonDecoder.gen

  implicit lazy val decodeJRApplicationStatus: JsonDecoder[JRApplicationStatus] = DeriveJsonDecoder.gen
  implicit lazy val decodeJRQuery:             JsonDecoder[JRQuery]             = DeriveJsonDecoder.gen
  implicit lazy val decodeJRDirectiveSection:  JsonDecoder[JRDirectiveSection]  = DeriveJsonDecoder.gen
  implicit lazy val decodeJRRule:              JsonDecoder[JRRule]              = DeriveJsonDecoder.gen
  implicit lazy val decodeJRGroup:             JsonDecoder[JRGroup]             = DeriveJsonDecoder.gen
  implicit lazy val decodeJRDirective:         JsonDecoder[JRDirective]         = DeriveJsonDecoder.gen

}
