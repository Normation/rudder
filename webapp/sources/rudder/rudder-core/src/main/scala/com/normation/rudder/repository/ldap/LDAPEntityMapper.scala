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

package com.normation.rudder.repository.ldap

import cats.implicits.*
import com.normation.GitVersion
import com.normation.GitVersion.ParseRev
import com.normation.GitVersion.Revision
import com.normation.NamedZioLogger
import com.normation.cfclerk.domain.*
import com.normation.errors.*
import com.normation.inventory.domain.*
import com.normation.inventory.ldap.core.InventoryMapper
import com.normation.inventory.ldap.core.InventoryMappingResult.*
import com.normation.inventory.ldap.core.InventoryMappingRudderError
import com.normation.inventory.ldap.core.InventoryMappingRudderError.MalformedDN
import com.normation.inventory.ldap.core.InventoryMappingRudderError.UnexpectedObject
import com.normation.inventory.ldap.core.InventoryMappingRudderError as Err
import com.normation.inventory.ldap.core.LDAPConstants
import com.normation.inventory.ldap.core.LDAPConstants.*
import com.normation.ldap.sdk.*
import com.normation.ldap.sdk.syntax.*
import com.normation.rudder.api.*
import com.normation.rudder.domain.NodeDit
import com.normation.rudder.domain.RudderDit
import com.normation.rudder.domain.RudderLDAPConstants.*
import com.normation.rudder.domain.appconfig.RudderWebProperty
import com.normation.rudder.domain.appconfig.RudderWebPropertyName
import com.normation.rudder.domain.logger.ApplicationLogger
import com.normation.rudder.domain.nodes.*
import com.normation.rudder.domain.policies.*
import com.normation.rudder.domain.properties.GenericProperty
import com.normation.rudder.domain.properties.GlobalParameter
import com.normation.rudder.domain.properties.GroupProperty
import com.normation.rudder.domain.properties.InheritMode
import com.normation.rudder.domain.properties.NodeProperty
import com.normation.rudder.domain.properties.PropertyProvider
import com.normation.rudder.domain.properties.Visibility
import com.normation.rudder.facts.nodes.NodeSecurityContext
import com.normation.rudder.facts.nodes.SecurityTag
import com.normation.rudder.reports.*
import com.normation.rudder.rule.category.RuleCategory
import com.normation.rudder.rule.category.RuleCategoryId
import com.normation.rudder.services.queries.*
import com.normation.utils.DateFormaterService
import com.softwaremill.quicklens.*
import com.unboundid.ldap.sdk.DN
import io.scalaland.chimney.PartialTransformer
import io.scalaland.chimney.partial
import io.scalaland.chimney.partial.syntax.*
import io.scalaland.chimney.syntax.*
import java.time.Instant
import scala.annotation.nowarn
import zio.*
import zio.json.*
import zio.syntax.*

object NodeStateEncoder {
  implicit def enc(state: NodeState): String = state.name
  implicit def dec(state: String): Either[Throwable, NodeState] = {
    NodeState.parse(state) match {
      case Right(s) => Right(s)
      case Left(_)  => Left(new IllegalArgumentException(s"'${state}' can not be decoded to a NodeState"))
    }
  }
}

/**
 * Map objects from/to LDAPEntries
 *
 */
class LDAPEntityMapper(
    rudderDit:       RudderDit,
    nodeDit:         NodeDit,
    cmdbQueryParser: CmdbQueryParser & RawStringQueryParser, // used to read from LDAP, in which case we do not need validation
    inventoryMapper: InventoryMapper
) extends NamedZioLogger {

  def loggerName = "rudder-ldap-entity-mapper"

  //////////////////////////////    Node    //////////////////////////////
  def nodeToEntry(node: Node): LDAPEntry = {
    import NodeStateEncoder.*
    val entry = {
      if (node.isPolicyServer) {
        nodeDit.NODES.NODE.policyServerNodeModel(node.id)
      } else {
        nodeDit.NODES.NODE.nodeModel(node.id)
      }
    }
    entry.resetValuesTo(A_NAME, node.name)
    entry.resetValuesTo(A_DESCRIPTION, node.description)
    entry.resetValuesTo(A_STATE, enc(node.state))
    entry.resetValuesTo(A_IS_SYSTEM, node.isSystem.toLDAPString)

    node.nodeReportingConfiguration.agentRunInterval match {
      case Some(interval) =>
        entry.resetValuesTo(A_SERIALIZED_AGENT_RUN_INTERVAL, interval.toJson)
      case _              =>
    }

    node.nodeReportingConfiguration.agentReportingProtocol match {
      case Some(protocol) => entry.resetValuesTo(A_AGENT_REPORTING_PROTOCOL, protocol.value)
      case _              =>
    }

    // for node properties, we ALWAYS filter-out properties coming from inventory,
    // because we don't want to store them there.
    entry.resetValuesTo(
      A_NODE_PROPERTY,
      node.properties.collect { case p if (p.provider != Some(NodeProperty.customPropertyProvider)) => p.toData }*
    )

    node.nodeReportingConfiguration.heartbeatConfiguration match {
      case Some(heatbeatConfiguration) =>
        entry.resetValuesTo(A_SERIALIZED_HEARTBEAT_RUN_CONFIGURATION, heatbeatConfiguration.toJson)
      case _                           => // Save nothing if missing
    }
    entry.addValues(A_POLICY_MODE, node.policyMode.map(_.name).getOrElse(PolicyMode.defaultValue))

    node.securityTag.foreach(t => entry.resetValuesTo(A_SECURITY_TAG, t.toJson))

    entry
  }

  def entryToNode(e: LDAPEntry): PureResult[Node] = {
    if (e.isA(OC_RUDDER_NODE) || e.isA(OC_POLICY_SERVER_NODE)) {
      // OK, translate
      for {
        id                     <- nodeDit.NODES.NODE.idFromDn(e.dn).toRight(Inconsistency(s"Bad DN found for a Node: ${e.dn}"))
        date                   <- e.requiredAs[GeneralizedTime](_.getAsGTime, A_OBJECT_CREATION_DATE)
        agentRunInterval        = e(A_SERIALIZED_AGENT_RUN_INTERVAL).flatMap(_.fromJson[AgentRunInterval].toOption)
        heartbeatConf           = e(A_SERIALIZED_HEARTBEAT_RUN_CONFIGURATION).flatMap(_.fromJson[HeartbeatConfiguration].toOption)
        agentReportingProtocol <- e(A_AGENT_REPORTING_PROTOCOL) match {
                                    case None        => Right(None)
                                    case Some(value) => AgentReportingProtocol.parse(value).map(Some(_))
                                  }
        policyMode             <- e(A_POLICY_MODE) match {
                                    case None        => Right(None)
                                    case Some(value) => PolicyMode.parseDefault(value)
                                  }
        properties             <- e.valuesFor(A_NODE_PROPERTY).toList.traverse(NodeProperty.unserializeLdapNodeProperty)
        securityTags            = e(A_SECURITY_TAG).flatMap(_.fromJson[SecurityTag].toOption)
      } yield {
        val hostname = e(A_NAME).getOrElse("")
        Node(
          id,
          hostname,
          e(A_DESCRIPTION).getOrElse(""), // node state missing or unknown value => enable, so that we are upward compatible

          NodeStateEncoder.dec(e(A_STATE).getOrElse(NodeState.Enabled.name)) match {
            case Right(s) => s
            case Left(ex) =>
              logEffect.debug(s"Can not decode 'state' for node '${hostname}' (${id.value}): ${ex.getMessage}")
              NodeState.Enabled
          },
          e.getAsBoolean(A_IS_SYSTEM).getOrElse(false),
          e.isA(OC_POLICY_SERVER_NODE),
          date.instant,
          ReportingConfiguration(
            agentRunInterval,
            heartbeatConf,
            agentReportingProtocol
          ),
          properties,
          policyMode,
          securityTags
        )
      }
    } else {
      Left(
        Unexpected(
          s"The given entry is not of the expected ObjectClass ${OC_RUDDER_NODE} or ${OC_POLICY_SERVER_NODE}. Entry details: ${e}"
        )
      )
    }
  }

  //////////////////////////////    NodeInfo    //////////////////////////////

  /**
   * From a nodeEntry and an inventoryEntry, create a NodeInfo
   *
   * The only thing used in machineEntry is its object class.
   *
   */
  def convertEntriesToNodeInfos(
      nodeEntry:      LDAPEntry,
      inventoryEntry: LDAPEntry,
      machineEntry:   Option[LDAPEntry]
  ): IOResult[NodeInfo] = {
    // why not using InventoryMapper ? Some required things for node are not
    // wanted here ?
    for {
      node        <- entryToNode(nodeEntry).toIO
      checkSameID <- ZIO.when(!nodeEntry(A_NODE_UUID).isDefined || nodeEntry(A_NODE_UUID) != inventoryEntry(A_NODE_UUID)) {
                       Err
                         .MissingMandatory(
                           s"Mismatch id for the node '${nodeEntry(A_NODE_UUID)}' and the inventory '${inventoryEntry(A_NODE_UUID)}'"
                         )
                         .fail
                     }
      nodeInfo    <- inventoryEntriesToNodeInfos(node, inventoryEntry, machineEntry).chainError(
                       s"Error when getting '${node.id.value}' from LDAP"
                     )
    } yield {
      nodeInfo
    }
  }

  /**
   * Convert at the best you can node inventories to node information.
   * Some information will be false for sure - that's understood.
   */
  def convertEntriesToSpecialNodeInfos(inventoryEntry: LDAPEntry, machineEntry: Option[LDAPEntry]): IOResult[NodeInfo] = {
    for {
      id       <- inventoryEntry(A_NODE_UUID).notOptional(s"Missing node id information in Node entry: ${inventoryEntry}")
      node      = Node(
                    NodeId(id),
                    inventoryEntry(A_NAME).getOrElse(""),
                    inventoryEntry(A_DESCRIPTION).getOrElse(""),
                    NodeState.Enabled,
                    inventoryEntry.getAsBoolean(A_IS_SYSTEM).getOrElse(false),
                    isPolicyServer = false, // we don't know anymore if it was a policy server

                    creationDate = Instant.ofEpochMilli(0), // we don't know anymore the acceptation date

                    ReportingConfiguration(None, None, None), // we don't know anymore agent run frequency

                    properties = Nil, // we forgot node properties

                    policyMode = None,
                    securityTag = None
                  )
      nodeInfo <- inventoryEntriesToNodeInfos(node, inventoryEntry, machineEntry)
    } yield {
      nodeInfo
    }
  }

  def parseAgentInfo(nodeId: NodeId, inventoryEntry: LDAPEntry): IOResult[(List[AgentInfo], KeyStatus)] = {
    for {
      agentsName <- {
        val agents = inventoryEntry.valuesFor(A_AGENT_NAME).toList
        ZIO.foreach(agents) { case agent => agent.fromJson[AgentInfo].toIO }
      }
      keyStatus  <- inventoryEntry(A_KEY_STATUS).map(KeyStatus(_)).getOrElse(Right(UndefinedKey)).toIO
    } yield {
      (agentsName, keyStatus)
    }
  }

  private def inventoryEntriesToNodeInfos(
      node:           Node,
      inventoryEntry: LDAPEntry,
      machineEntry:   Option[LDAPEntry]
  ): IOResult[NodeInfo] = {
    // why not using InventoryMapper ? Some required things for node are not
    // wanted here ?
    for {
      // Compute the parent policy Id
      policyServerId <- inventoryEntry.valuesFor(A_POLICY_SERVER_UUID).toList match {
                          case Nil      =>
                            Err
                              .MissingMandatory(
                                s"Missing policy server id for Node '${node.id.value}'. Entry details: ${inventoryEntry}"
                              )
                              .fail
                          case x :: Nil => x.succeed
                          case _        =>
                            Unexpected(
                              s"Too many policy servers for a Node '${node.id.value}'. Entry details: ${inventoryEntry}"
                            ).fail
                        }
      osDetails      <- inventoryMapper.mapOsDetailsFromEntry(inventoryEntry).toIO
      timezone        = (inventoryEntry(A_TIMEZONE_NAME), inventoryEntry(A_TIMEZONE_OFFSET)) match {
                          case (Some(name), Some(offset)) => Some(NodeTimezone(name, offset))
                          case _                          => None
                        }
      // custom properties mapped as NodeProperties
      properties     <- ZIO.foreach(inventoryEntry.valuesFor(A_CUSTOM_PROPERTY)) { json =>
                          GenericProperty
                            .parseConfig(json)
                            .toIO
                            .foldZIO(
                              err =>
                                logPure.error(
                                  Chained(s"Error when trying to deserialize Node Custom Property, it will be ignored", err).fullMsg
                                ) *>
                                None.succeed,
                              conf => Some(NodeProperty(conf).withProvider(NodeProperty.customPropertyProvider)).succeed
                            )
                        }
      agentsInfo     <- parseAgentInfo(node.id, inventoryEntry)
    } yield {
      val machineInfo = machineEntry.flatMap { e =>
        for {
          machineUuid <- e(A_MACHINE_UUID).map(MachineUuid.apply)
        } yield {
          MachineInfo(
            machineUuid,
            inventoryMapper.machineTypeFromObjectClasses(e.valuesFor("objectClass")),
            e(LDAPConstants.A_SERIAL_NUMBER),
            e(LDAPConstants.A_MANUFACTURER).map(Manufacturer(_))
          )
        }
      }

      // fetch the inventory datetime of the object
      val dateTime = inventoryEntry.getAsGTime(A_INVENTORY_DATE).map(_.instant).getOrElse(Instant.now)
      NodeInfo(
        node.copy(properties = overrideProperties(node.id, node.properties, properties.toList.flatten)),
        inventoryEntry(A_HOSTNAME).getOrElse(""),
        machineInfo,
        osDetails,
        inventoryEntry.valuesFor(A_LIST_OF_IP).toList,
        dateTime,
        agentsInfo._2,
        agentsInfo._1,
        NodeId(policyServerId),
        inventoryEntry(A_ROOT_USER).getOrElse(""),
        inventoryEntry(A_ARCH),
        inventoryEntry(A_OS_RAM).map(m => MemorySize(m)),
        timezone
      )
    }
  }

  /**
  * Override logic between existing properties and inventory ones.
  * We only override Rudder properties with default provider with
  * inventory ones. Other providers (like datasources) are more
  * prioritary.
  * In all case, a user shoud just avoid these cases, so we log.
  */
  def overrideProperties(nodeId: NodeId, existing: Seq[NodeProperty], inventory: List[NodeProperty]): List[NodeProperty] = {
    val customProperties = inventory.map(x => (x.name, x)).toMap
    val overridden       = existing.map { current =>
      customProperties.get(current.name) match {
        case None         => current
        case Some(custom) =>
          current.provider match {
            case None | Some(PropertyProvider.defaultPropertyProvider) => // override and log
              logEffect.info(
                s"On node [${nodeId.value}]: overriding existing node property '${current.name}' with custom node inventory property with same name."
              )
              custom
            case other                                                 => // keep existing prop from other provider but log
              logEffect.info(
                s"On node [${nodeId.value}]: ignoring custom node inventory property with name '${current.name}' (an other provider already set that property)."
              )
              current
          }
      }
    }

    // now, filter remaining inventory properties (ie the one not already processed)
    val alreadyDone = overridden.map(_.name).toSet

    (overridden ++ inventory.filter(x => !alreadyDone.contains(x.name))).toList
  }

  /**
   * Build the ActiveTechniqueCategoryId from the given DN
   */
  def dn2ActiveTechniqueCategoryId(dn: DN): ActiveTechniqueCategoryId = {
    import net.liftweb.common.*
    rudderDit.ACTIVE_TECHNIQUES_LIB.getCategoryIdValue(dn) match {
      case Full(value) => ActiveTechniqueCategoryId(value)
      case e: EmptyBox =>
        throw new RuntimeException("The dn %s is not a valid Active Technique Category ID. Error was: %s".format(dn, e.toString))
    }
  }

  def dn2ActiveTechniqueId(dn: DN): ActiveTechniqueId = {
    import net.liftweb.common.*
    rudderDit.ACTIVE_TECHNIQUES_LIB.getActiveTechniqueId(dn) match {
      case Full(value) => ActiveTechniqueId(value)
      case e: EmptyBox =>
        throw new RuntimeException("The dn %s is not a valid Active Technique ID. Error was: %s".format(dn, e.toString))
    }
  }

  /**
   * Build the Group Category Id from the given DN
   */
  def dn2NodeGroupCategoryId(dn: DN): NodeGroupCategoryId = {
    import net.liftweb.common.*
    rudderDit.GROUP.getCategoryIdValue(dn) match {
      case Full(value) => NodeGroupCategoryId(value)
      case e: EmptyBox =>
        throw new RuntimeException("The dn %s is not a valid Node Group Category ID. Error was: %s".format(dn, e.toString))
    }
  }

  /**
   * Build the Node Group Category Id from the given DN
   */
  def dn2NodeGroupId(dn: DN): NodeGroupId = {
    import net.liftweb.common.*
    rudderDit.GROUP.getGroupId(dn) match {
      case Full(value) =>
        NodeGroupId.parse(value) match {
          case Left(err) => throw new RuntimeException(s"The dn ${dn} is not a valid Node Group ID. Error was: ${err}")
          case Right(id) => id
        }
      case e: EmptyBox => throw new RuntimeException(s"The dn ${dn} is not a valid Node Group UID. Error was: ${e.toString}")
    }
  }

  /**
   * Build the Rule Category Id from the given DN
   */
  def dn2RuleCategoryId(dn: DN): RuleCategoryId = {
    import net.liftweb.common.*
    rudderDit.RULECATEGORY.getCategoryIdValue(dn) match {
      case Full(value) => RuleCategoryId(value)
      case e: EmptyBox =>
        throw new RuntimeException("The dn %s is not a valid Rule Category ID. Error was: %s".format(dn, e.toString))
    }
  }

  def dn2LDAPDirectiveUid(dn: DN): DirectiveUid = {
    import net.liftweb.common.*
    rudderDit.ACTIVE_TECHNIQUES_LIB.getLDAPDirectiveUid(dn) match {
      case Full(value) => DirectiveUid(value)
      case e: EmptyBox =>
        throw new RuntimeException("The dn %s is not a valid Directive UID. Error was: %s".format(dn, e.toString))
    }
  }

  def dn2RuleUid(dn: DN): RuleUid = {
    import net.liftweb.common.*
    rudderDit.RULES.getRuleUid(dn) match {
      case Full(value) => RuleUid(value)
      case e: EmptyBox => throw new RuntimeException("The dn %s is not a valid Rule UID. Error was: %s".format(dn, e.toString))
    }
  }

  def nodeDn2OptNodeId(dn: DN): InventoryMappingPure[NodeId] = {
    val rdn = dn.getRDN
    if (!rdn.isMultiValued && rdn.hasAttribute(A_NODE_UUID)) {
      Right(NodeId(rdn.getAttributeValues()(0)))
    } else Left(Err.MalformedDN("Bad RDN for a node, expecting attribute name '%s', got: %s".format(A_NODE_UUID, rdn)))
  }

  //////////////////////////////    ActiveTechniqueCategory    //////////////////////////////

  /**
   * children and items are left empty
   */
  def entry2ActiveTechniqueCategory(e: LDAPEntry): InventoryMappingPure[ActiveTechniqueCategory] = {
    if (e.isA(OC_TECHNIQUE_CATEGORY)) {
      // OK, translate
      for {
        id         <- e.required(A_TECHNIQUE_CATEGORY_UUID)
        name       <- e.required(A_NAME)
        description = e(A_DESCRIPTION).getOrElse("")
        isSystem    = e.getAsBoolean(A_IS_SYSTEM).getOrElse(false)
      } yield {
        ActiveTechniqueCategory(ActiveTechniqueCategoryId(id), name, description, Nil, Nil, isSystem)
      }
    } else {
      Left(
        Err.UnexpectedObject(
          s"The given entry is not of the expected ObjectClass '${OC_TECHNIQUE_CATEGORY}'. Entry details: ${e}"
        )
      )
    }
  }

  /**
   * children and items are ignored
   */
  def activeTechniqueCategory2ldap(category: ActiveTechniqueCategory, parentDN: DN): LDAPEntry = {
    val entry = rudderDit.ACTIVE_TECHNIQUES_LIB.activeTechniqueCategoryModel(category.id.value, parentDN)
    entry.resetValuesTo(A_NAME, category.name)
    entry.resetValuesTo(A_DESCRIPTION, category.description)
    entry.resetValuesTo(A_IS_SYSTEM, category.isSystem.toLDAPString)
    entry
  }

  //////////////////////////////    ActiveTechnique    //////////////////////////////

  /**
   * Build a ActiveTechnique from and LDAPEntry.
   * children directives are left empty
   */
  def entry2ActiveTechnique(e: LDAPEntry): InventoryMappingPure[ActiveTechnique] = {
    if (e.isA(OC_ACTIVE_TECHNIQUE)) {
      // OK, translate
      for {
        id                   <- e.required(A_ACTIVE_TECHNIQUE_UUID)
        refTechniqueUuid     <- e.required(A_TECHNIQUE_UUID).map(x => TechniqueName(x))
        isEnabled             = e.getAsBoolean(A_IS_ENABLED).getOrElse(false)
        policyTypes           = e(A_POLICY_TYPES) match {
                                  case Some(json) => json.fromJson[PolicyTypes].getOrElse(PolicyTypes.rudderBase)
                                  case None       =>
                                    if (e.getAsBoolean(A_IS_SYSTEM).getOrElse(false)) PolicyTypes.rudderSystem
                                    else PolicyTypes.rudderBase
                                }
        acceptationDatetimes <- e(A_ACCEPTATION_DATETIME) match {
                                  case Some(v) =>
                                    v.fromJson[AcceptationDateTime]
                                      .leftMap(e => InventoryMappingRudderError.UnexpectedObject(e))
                                  case None    => Right(AcceptationDateTime.empty)
                                }
      } yield {
        ActiveTechnique(ActiveTechniqueId(id), refTechniqueUuid, acceptationDatetimes, Nil, isEnabled, policyTypes)
      }
    } else {
      Left(
        Err.UnexpectedObject(
          "The given entry is not of the expected ObjectClass '%s'. Entry details: %s".format(OC_TECHNIQUE_CATEGORY, e)
        )
      )
    }
  }

  def activeTechnique2Entry(activeTechnique: ActiveTechnique, parentDN: DN): LDAPEntry = {
    val entry = rudderDit.ACTIVE_TECHNIQUES_LIB.activeTechniqueModel(
      activeTechnique.id.value,
      parentDN,
      activeTechnique.techniqueName,
      activeTechnique.acceptationDatetimes.toJson,
      activeTechnique.isEnabled,
      activeTechnique.policyTypes
    )
    entry
  }

  //////////////////////////////    NodeGroupCategory    //////////////////////////////

  /**
   * children and items are left empty
   */
  def entry2NodeGroupCategory(e: LDAPEntry): InventoryMappingPure[NodeGroupCategory] = {
    if (e.isA(OC_GROUP_CATEGORY)) {
      // OK, translate
      for {
        id         <- e.required(A_GROUP_CATEGORY_UUID)
        name       <- e.required(A_NAME)
        description = e(A_DESCRIPTION).getOrElse("")
        isSystem    = e.getAsBoolean(A_IS_SYSTEM).getOrElse(false)
      } yield {
        NodeGroupCategory(NodeGroupCategoryId(id), name, description, Nil, Nil, isSystem)
      }
    } else {
      Left(
        Err.UnexpectedObject(
          "The given entry is not of the expected ObjectClass '%s'. Entry details: %s".format(OC_GROUP_CATEGORY, e)
        )
      )
    }
  }

  /**
   * children and items are ignored
   */
  def nodeGroupCategory2ldap(category: NodeGroupCategory, parentDN: DN): LDAPEntry = {
    val entry = rudderDit.GROUP.groupCategoryModel(category.id.value, parentDN)
    entry.resetValuesTo(A_NAME, category.name)
    entry.resetValuesTo(A_DESCRIPTION, category.description)
    entry.resetValuesTo(A_IS_SYSTEM, category.isSystem.toLDAPString)
    entry
  }

  ////////////////////////////////// Node Group //////////////////////////////////

  /**
   * Build a node group from and LDAPEntry.
   */
  def entry2NodeGroup(e: LDAPEntry): InventoryMappingPure[NodeGroup] = {
    if (e.isA(OC_RUDDER_NODE_GROUP)) {
      // OK, translate
      for {
        id         <- e.required(A_NODE_GROUP_UUID).flatMap(NodeGroupId.parse(_).left.map(MalformedDN.apply))
        name       <- e.required(A_NAME)
        nodeIds     = e.valuesFor(A_NODE_UUID).map(x => NodeId(x))
        query      <- e(A_QUERY_NODE_GROUP) match {
                        case None    => Right(None)
                        case Some(q) =>
                          cmdbQueryParser(q).toPureResult match {
                            case Right(x)  => Right(Some(x))
                            case Left(err) =>
                              val error =
                                s"Error when parsing query for node group persisted at DN '${e.dn}' (name: '${name}'), that seems to be an inconsistency. You should modify that group. Erros was: ${err.fullMsg}}"
                              logEffect.error(error)
                              Right(None)
                          }
                      }
        // better to ignore a bad property (set by something extern to rudder) than to make group unusable
        properties  = e.valuesFor(A_JSON_PROPERTY)
                        .toList
                        .flatMap(s => {
                          GroupProperty.unserializeLdapGroupProperty(s) match {
                            case Right(p)  => Some(p)
                            case Left(err) =>
                              ApplicationLogger.error(s"Group has an invalid property that will be ignore: ${err.fullMsg}")
                              None
                          }
                        })
        isDynamic   = e.getAsBoolean(A_IS_DYNAMIC).getOrElse(false)
        isEnabled   = e.getAsBoolean(A_IS_ENABLED).getOrElse(false)
        isSystem    = e.getAsBoolean(A_IS_SYSTEM).getOrElse(false)
        description = e(A_DESCRIPTION).getOrElse("")
      } yield {
        NodeGroup(id, name, description, properties, query, isDynamic, nodeIds, isEnabled, isSystem)
      }
    } else {
      Thread.currentThread().getStackTrace.foreach(println)
      Left(
        Err.UnexpectedObject(
          "The given entry is not of the expected ObjectClass '%s'. Entry details: %s".format(OC_RUDDER_NODE_GROUP, e)
        )
      )
    }
  }

  // Lightweight implementation, returns only the nodegroupid, and list of nodes
  def entryToGroupNodeIds(e: LDAPEntry): InventoryMappingPure[(NodeGroupId, Set[NodeId])] = {
    if (e.isA(OC_RUDDER_NODE_GROUP)) {
      for {
        id     <- e.required(A_NODE_GROUP_UUID).flatMap(NodeGroupId.parse(_).left.map(MalformedDN.apply))
        nodeIds = e.valuesFor(A_NODE_UUID).map(x => NodeId(x))
      } yield {
        (id, nodeIds)
      }
    } else {
      Left(
        Err.UnexpectedObject(s"The given entry is not of the expected ObjectClass '${OC_RUDDER_NODE_GROUP}'. Entry details: ${e}")
      )
    }
  }

  // Lightweight implementation, returns only the nodegroupid, and list of nodes
  def entryToGroupNodeIdsChunk(e: LDAPEntry): InventoryMappingPure[(NodeGroupId, Chunk[NodeId])] = {
    if (e.isA(OC_RUDDER_NODE_GROUP)) {
      for {
        id     <- e.required(A_NODE_GROUP_UUID).flatMap(NodeGroupId.parse(_).left.map(MalformedDN.apply))
        nodeIds = e.valuesForChunk(A_NODE_UUID).map(x => NodeId(x))
      } yield {
        (id, nodeIds)
      }
    } else {
      Left(
        Err.UnexpectedObject(s"The given entry is not of the expected ObjectClass '${OC_RUDDER_NODE_GROUP}'. Entry details: ${e}")
      )
    }
  }

  def nodeGroupToLdap(group: NodeGroup, parentDN: DN): LDAPEntry = {
    val entry = rudderDit.GROUP.groupModel(
      group.id.serialize,
      parentDN,
      group.name,
      group.description,
      group.query,
      group.isDynamic,
      group.serverList,
      group.isEnabled,
      group.isSystem
    )
    // we never ever want to save a property with a blank name
    val props = group.properties.collect { case p if (!p.name.trim.isEmpty) => p.toData }
    if (ApplicationLogger.isDebugEnabled && props.size != group.properties.size) {
      ApplicationLogger.debug(
        s"Some properties from group '${group.name}' (${group.id.serialize}) were ignored because their name was blank and it's forbidden"
      )
    }
    entry.resetValuesTo(A_JSON_PROPERTY, props*)
    entry
  }

  //////////////////////////////    Special Policy target info    //////////////////////////////

  def entry2RuleTargetInfo(e: LDAPEntry): InventoryMappingPure[FullRuleTargetInfo] = {
    if (e.isA(OC_RUDDER_NODE_GROUP)) {
      entry2NodeGroup(e).map(g =>
        FullRuleTargetInfo(FullGroupTarget(GroupTarget(g.id), g), g.name, g.description, g.isEnabled, g.isSystem)
      )

    } else if (e.isA(OC_SPECIAL_TARGET)) {
      for {
        targetString <- e.required(A_RULE_TARGET)
        target       <-
          RuleTarget
            .unser(targetString)
            .toRight(
              UnexpectedObject("Can not unserialize target, '%s' does not match any known target format".format(targetString))
            )
        name         <- e.required(A_NAME)
        description   = e(A_DESCRIPTION).getOrElse("")
        isEnabled     = e.getAsBoolean(A_IS_ENABLED).getOrElse(false)
        isSystem      = e.getAsBoolean(A_IS_SYSTEM).getOrElse(false)
        ruleTarget   <- target match {
                          case x: NonGroupRuleTarget => Right(FullOtherTarget(x))
                          case x => // group was processed previously, so in other case here, we raise an error
                            Left(
                              Err.UnexpectedObject(
                                s"We we not able to unseriable target type, which is not a NonGroupRuleTarget, '${x}' for entry ${e}"
                              )
                            )
                        }
      } yield {
        FullRuleTargetInfo(ruleTarget, name, description, isEnabled, isSystem)
      }
    } else {
      Left(
        Err.UnexpectedObject(
          s"The given entry is not of the expected ObjectClass '${OC_RUDDER_NODE_GROUP}' or '${OC_SPECIAL_TARGET}. Entry details: ${e}"
        )
      )
    }
  }

  //////////////////////////////    Directive    //////////////////////////////

  def entry2Directive(e: LDAPEntry): PureResult[Directive] = {

    if (e.isA(OC_DIRECTIVE)) {
      // OK, translate
      for {
        id              <- e.required(A_DIRECTIVE_UUID)
        s_version       <- e.required(A_TECHNIQUE_VERSION)
        policyMode      <- e(A_POLICY_MODE) match {
                             case None        => Right(None)
                             case Some(value) => PolicyMode.parse(value).map(Some(_))
                           }
        version         <- TechniqueVersion.parse(s_version).leftMap(Unexpected.apply)
        name             = e(A_NAME).getOrElse(id)
        params           = parsePolicyVariables(e.valuesFor(A_DIRECTIVE_VARIABLES).toSeq)
        shortDescription = e(A_DESCRIPTION).getOrElse("")
        longDescription  = e(A_LONG_DESCRIPTION).getOrElse("")
        priority         = e.getAsInt(A_PRIORITY).getOrElse(0)
        isEnabled        = e.getAsBoolean(A_IS_ENABLED).getOrElse(false)
        isSystem         = e.getAsBoolean(A_IS_SYSTEM).getOrElse(false)
        tags            <- Tags.parse(e(A_SERIALIZED_TAGS)).chainError(s"Invalid attribute value for tags ${A_SERIALIZED_TAGS}")
      } yield {
        Directive(
          DirectiveId(DirectiveUid(id), ParseRev(e(A_REV_ID))),
          version,
          params,
          name,
          shortDescription,
          policyMode,
          longDescription,
          priority,
          isEnabled,
          isSystem,
          tags
        )
      }
    } else {
      Left(
        Err.UnexpectedObject("The given entry is not of the expected ObjectClass '%s'. Entry details: %s".format(OC_DIRECTIVE, e))
      )
    }
  }

  def userDirective2Entry(directive: Directive, parentDN: DN): LDAPEntry = {
    val entry = rudderDit.ACTIVE_TECHNIQUES_LIB.directiveModel(
      directive.id.uid,
      directive.id.rev,
      directive.techniqueVersion,
      parentDN
    )

    entry.resetValuesTo(A_DIRECTIVE_VARIABLES, policyVariableToSeq(directive.parameters)*)
    entry.resetValuesTo(A_NAME, directive.name)
    entry.resetValuesTo(A_DESCRIPTION, directive.shortDescription)
    entry.resetValuesTo(A_LONG_DESCRIPTION, directive.longDescription.toString)
    entry.resetValuesTo(A_PRIORITY, directive.priority.toString)
    entry.resetValuesTo(A_IS_ENABLED, directive.isEnabled.toLDAPString)
    entry.resetValuesTo(A_IS_SYSTEM, directive.isSystem.toLDAPString)
    directive.policyMode.foreach(mode => entry.resetValuesTo(A_POLICY_MODE, mode.name))
    entry.resetValuesTo(A_SERIALIZED_TAGS, directive.tags.toJson)
    entry
  }

  //////////////////////////////    Rule Category    //////////////////////////////

  /**
   * children and items are left empty
   */
  def entry2RuleCategory(e: LDAPEntry): InventoryMappingPure[RuleCategory] = {
    if (e.isA(OC_RULE_CATEGORY)) {
      // OK, translate
      for {
        id         <- e.required(A_RULE_CATEGORY_UUID)
        name       <- e.required(A_NAME)
        description = e(A_DESCRIPTION).getOrElse("")
        isSystem    = e.getAsBoolean(A_IS_SYSTEM).getOrElse(false)
      } yield {
        RuleCategory(RuleCategoryId(id), name, description, Nil, isSystem)
      }
    } else {
      Left(Err.UnexpectedObject(s"The given entry is not of the expected ObjectClass '${OC_RULE_CATEGORY}'. Entry details: ${e}"))
    }
  }

  /**
   * children and items are ignored
   */
  def ruleCategory2ldap(category: RuleCategory, parentDN: DN): LDAPEntry = {
    rudderDit.RULECATEGORY.ruleCategoryModel(category.id.value, parentDN, category.name, category.description, category.isSystem)
  }

  //////////////////////////////    Rule    //////////////////////////////
  def entry2OptTarget(optValue: Option[String]): InventoryMappingPure[Option[RuleTarget]] = {
    optValue match {
      case None        => Right(None)
      case Some(value) =>
        RuleTarget.unser(value).toRight(UnexpectedObject(s"Bad parameter for a rule target: ${value}")).map(Some(_))
    }
  }

  def entry2Rule(e: LDAPEntry): PureResult[Rule] = {

    if (e.isA(OC_RULE)) {
      for {
        id   <- e.required(A_RULE_UUID)
        tags <- Tags.parse(e(A_SERIALIZED_TAGS)).chainError(s"Invalid attribute value for tags ${A_SERIALIZED_TAGS}")
      } yield {
        val targets      = for {
          target           <- e.valuesFor(A_RULE_TARGET)
          optionRuleTarget <- entry2OptTarget(Some(target)) match {
                                case Right(value) => Some(value)
                                case Left(err)    =>
                                  logEffect.error(s"Invalid attribute '${target}' for entry ${A_RULE_TARGET}.")
                                  None
                              }
          ruleTarget       <- optionRuleTarget
        } yield {
          ruleTarget
        }
        val rev          = ParseRev(e(A_REV_ID))
        val directiveIds = e
          .valuesFor(A_DIRECTIVE_UUID)
          .map(x => DirectiveId.parse(x).getOrElse(DirectiveId(DirectiveUid("")))) // the error case handling is for compat
        val name             = e(A_NAME).getOrElse(id)
        val shortDescription = e(A_DESCRIPTION).getOrElse("")
        val longDescription  = e(A_LONG_DESCRIPTION).getOrElse("")
        val isEnabled        = e.getAsBoolean(A_IS_ENABLED).getOrElse(false)
        val isSystem         = e.getAsBoolean(A_IS_SYSTEM).getOrElse(false)
        val category         = e(A_RULE_CATEGORY).map(RuleCategoryId(_)).getOrElse(rudderDit.RULECATEGORY.rootCategoryId)

        Rule(
          RuleId(RuleUid(id), rev),
          name,
          category,
          targets,
          directiveIds,
          shortDescription,
          longDescription,
          isEnabled,
          isSystem,
          tags
        )
      }
    } else {
      Left(Err.UnexpectedObject(s"The given entry is not of the expected ObjectClass '${OC_RULE}'. Entry details: ${e}"))
    }
  }

  /**
   * Map a rule to an LDAP Entry.
   * WARN: serial is NEVER mapped.
   */
  def rule2Entry(rule: Rule): LDAPEntry = {
    val entry = rudderDit.RULES.ruleModel(
      rule.id,
      rule.name,
      rule.isEnabledStatus,
      rule.isSystem,
      rule.categoryId.value
    )

    entry.resetValuesTo(A_RULE_TARGET, rule.targets.map(_.target).toSeq*)
    entry.resetValuesTo(A_DIRECTIVE_UUID, rule.directiveIds.map(_.serialize).toSeq*)
    entry.resetValuesTo(A_DESCRIPTION, rule.shortDescription)
    entry.resetValuesTo(A_LONG_DESCRIPTION, rule.longDescription.toString)
    entry.resetValuesTo(A_SERIALIZED_TAGS, rule.tags.toJson)

    entry
  }

  //////////////////////////////    API Accounts    //////////////////////////////

  def serApiAcl(authz: List[ApiAclElement]): String = {
    JsonApiAcl(acl = authz.map(a => JsonApiAuthz(path = a.path.value, actions = a.actions.toList.map(_.name)))).toJson
  }

  def unserApiAcl(s: String): Either[String, List[ApiAclElement]] = {
    s.fromJson[JsonApiAcl] match {
      case Right(acl) =>
        acl.transformIntoPartial[List[ApiAclElement]].asEitherErrorPathMessageStrings.left.map(_.mkString(", "))
      case Left(err)  =>
        Left(s"Can not extract API ACL object from json: ${s}. Error was: ${err}")
    }
  }

  /**
   * Build an API Account from an entry
   */
  def entry2ApiAccount(e: LDAPEntry): InventoryMappingPure[ApiAccount] = {
    if (e.isA(OC_API_ACCOUNT)) {
      // OK, translate
      for {
        id                    <- e.required(A_API_UUID).map(ApiAccountId(_))
        name                  <- e.required(A_NAME).map(ApiAccountName(_))
        token                  = e(A_API_TOKEN).map(ApiTokenHash.fromHashValue(_))
        creationDatetime      <- e.requiredAs[GeneralizedTime](_.getAsGTime, A_CREATION_DATETIME)
        tokenCreationDatetime <- e.requiredAs[GeneralizedTime](_.getAsGTime, A_API_TOKEN_CREATION_DATETIME)
        isEnabled              = e.getAsBoolean(A_IS_ENABLED).getOrElse(false)
        description            = e(A_DESCRIPTION).getOrElse("")
        // expiration date is optional
        expirationDate         = e.getAsGTime(A_API_EXPIRATION_DATETIME)
        // api authz kind/acl are not optional, but may be missing for Rudder < 4.3 migration
        // in that case, use the defaultACL
        accountType            = e(A_API_KIND) match {
                                   case None    => ApiAccountType.PublicApi // this is the default
                                   case Some(s) => ApiAccountType.values.find(_.name == s).getOrElse(ApiAccountType.PublicApi)
                                 }
        authz                 <- e(A_API_AUTHZ_KIND) match {
                                   case None    =>
                                     if (accountType == ApiAccountType.PublicApi) {
                                       logEffect.warn(s"Missing API authorizations level kind for token '${name.value}' with id '${id.value}'")
                                     }
                                     Right(ApiAuthorization.None)
                                   case Some(s) =>
                                     ApiAuthorizationKind.parse(s) match {
                                       case Left(error) => Left(Err.UnexpectedObject(error))
                                       case Right(kind) =>
                                         kind match {
                                           case ApiAuthorizationKind.ACL  =>
                                             // parse acl
                                             e(A_API_ACL) match {
                                               case None    =>
                                                 logEffect.debug(
                                                   s"API authorizations level kind for token '${name.value}' with id '${id.value}' is 'ACL' but it doesn't have any ACLs conigured"
                                                 )
                                                 Right(
                                                   ApiAuthorization.None
                                                 ) // for Rudder < 4.3, it should have been migrated. So here, we just don't gave any access.
                                               case Some(s) =>
                                                 unserApiAcl(s) match {
                                                   case Right(x)  => Right(ApiAuthorization.ACL(x))
                                                   case Left(msg) => Left(Err.UnexpectedObject(msg))
                                                 }
                                             }
                                           case ApiAuthorizationKind.None => Right(ApiAuthorization.None)
                                           case ApiAuthorizationKind.RO   => Right(ApiAuthorization.RO)
                                           case ApiAuthorizationKind.RW   => Right(ApiAuthorization.RW)
                                         }
                                     }
                                 }
        tenants               <-
          NodeSecurityContext.parse(e(A_API_TENANT)).left.map(err => InventoryMappingRudderError.UnexpectedObject(err.fullMsg))
      } yield {

        def warnOnIgnoreAuthz(): Unit = {
          if (e(A_API_AUTHZ_KIND).isDefined || e(A_API_EXPIRATION_DATETIME).isDefined) {
            // this is a log for dev, an user can't do anything about it.
            logEffect.debug(
              s"Attribute '${A_API_AUTHZ_KIND}' or '${A_API_EXPIRATION_DATETIME}' is defined for " +
              s"API account '${name.value}' [${id.value}], it will be ignored because the account is of type '${accountType.name}'."
            )
          }
        }

        val accountKind = accountType match {
          case ApiAccountType.System    =>
            warnOnIgnoreAuthz()
            ApiAccountKind.System
          case ApiAccountType.User      =>
            warnOnIgnoreAuthz()
            ApiAccountKind.User
          case ApiAccountType.PublicApi =>
            ApiAccountKind.PublicApi(authz, expirationDate.map(x => DateFormaterService.toDateTime(x.instant)))
        }

        // as of 8.3, we disable an API account with token version < 2
        val isEnabledAndVersionOk = token match {
          case Some(t) => if (t.version() >= 2) isEnabled else false
          case None    => isEnabled
        }
        ApiAccount(
          id,
          accountKind,
          name,
          token,
          description,
          isEnabledAndVersionOk,
          DateFormaterService.toDateTime(creationDatetime.instant),
          DateFormaterService.toDateTime(tokenCreationDatetime.instant),
          tenants
        )
      }
    } else {
      Left(Err.UnexpectedObject(s"The given entry is not of the expected ObjectClass '${OC_API_ACCOUNT}'. Entry details: ${e}"))
    }
  }

  def apiAccount2Entry(principal: ApiAccount): LDAPEntry = {
    val mod = LDAPEntry(rudderDit.API_ACCOUNTS.API_ACCOUNT.dn(principal.id))
    mod.resetValuesTo(A_OC, OC.objectClassNames(OC_API_ACCOUNT).toSeq*)
    mod.resetValuesTo(A_API_UUID, principal.id.value)
    mod.resetValuesTo(A_NAME, principal.name.value)
    mod.resetValuesTo(A_CREATION_DATETIME, GeneralizedTime(principal.creationDate).toString)
    principal.token.flatMap(_.exposeHash()) match {
      case Some(value) => mod.resetValuesTo(A_API_TOKEN, value)
      case None        => mod.deleteAttribute(A_API_TOKEN)
    }
    mod.resetValuesTo(A_API_TOKEN_CREATION_DATETIME, GeneralizedTime(principal.tokenGenerationDate).toString)
    mod.resetValuesTo(A_DESCRIPTION, principal.description)
    mod.resetValuesTo(A_IS_ENABLED, principal.isEnabled.toLDAPString)
    mod.resetValuesTo(A_API_KIND, principal.kind.kind.name)
    mod.resetValuesTo(A_API_TENANT, principal.tenants.serialize)

    principal.kind match {
      case ApiAccountKind.PublicApi(authz, exp) =>
        exp.foreach(e => mod.resetValuesTo(A_API_EXPIRATION_DATETIME, GeneralizedTime(e).toString()))
        // authorisation
        authz match {
          case ApiAuthorization.ACL(acl) =>
            mod.resetValuesTo(A_API_AUTHZ_KIND, authz.kind.name)
            mod.resetValuesTo(A_API_ACL, serApiAcl(acl))
          case x                         =>
            mod.resetValuesTo(A_API_AUTHZ_KIND, x.kind.name)
        }
      case _                                    => // nothing to add
    }
    mod
  }

  //////////////////////////////    Parameters    //////////////////////////////

  /*
   * We need to know if the format is 6.0 and before or 6.1.
   * For that, we look if attribute `overridable` is present: if so, it's 6.0 or before
   * (and we remove it for 6.1 and above).
   */
  def entry2Parameter(e: LDAPEntry): InventoryMappingPure[GlobalParameter] = {
    import com.normation.rudder.domain.properties.GenericProperty.*
    if (e.isA(OC_PARAMETER)) {
      // OK, translate
      for {
        name       <- e.required(A_PARAMETER_NAME)
        description = e(A_DESCRIPTION).getOrElse("")
        provider    = e(A_PROPERTY_PROVIDER).map(PropertyProvider.apply)
        parsed      = e(A_PARAMETER_VALUE).getOrElse("").parseGlobalParameter(name, e.hasAttribute("overridable"))
        mode        = e(A_INHERIT_MODE).flatMap(InheritMode.parseString(_).toOption)
        rev         = e(A_REV_ID).map(Revision(_)) match {
                        case None    => GitVersion.DEFAULT_REV
                        case Some(x) => x
                      }
        visibility <- e(A_VISIBILITY) match {
                        case None             => Right(Visibility.default)
                        case Some(visibility) =>
                          Visibility.withNameInsensitiveEither(visibility) match {
                            case Left(err) =>
                              Left(InventoryMappingRudderError.UnexpectedObject(err.getMessage()))
                            case Right(x)  => Right(x)
                          }
                      }
      } yield {
        GlobalParameter(name, rev, parsed, mode, description, provider, visibility)
      }
    } else {
      Left(
        Err.UnexpectedObject("The given entry is not of the expected ObjectClass '%s'. Entry details: %s".format(OC_PARAMETER, e))
      )
    }
  }

  def parameter2Entry(parameter: GlobalParameter): LDAPEntry = {
    import com.normation.rudder.domain.properties.GenericProperty.*
    val entry = rudderDit.PARAMETERS.parameterModel(
      parameter.name
    )
    parameter.rev.foreach(r => entry.resetValuesTo(A_REV_ID, r.value))
    entry.resetValuesTo(A_PARAMETER_VALUE, parameter.value.serializeGlobalParameter)
    entry.resetValuesTo(A_DESCRIPTION, parameter.description)
    parameter.provider.foreach(p => entry.resetValuesTo(A_PROPERTY_PROVIDER, p.value))
    parameter.inheritMode.foreach(m => entry.resetValuesTo(A_INHERIT_MODE, m.value))
    entry.resetValuesTo(A_VISIBILITY, parameter.visibility.entryName)
    entry
  }

  //////////////////////////////    Rudder Config    //////////////////////////////

  def entry2RudderConfig(e: LDAPEntry): InventoryMappingPure[RudderWebProperty] = {
    if (e.isA(OC_PROPERTY)) {
      // OK, translate
      for {
        name       <- e.required(A_PROPERTY_NAME)
        value       = e(A_PROPERTY_VALUE).getOrElse("")
        description = e(A_DESCRIPTION).getOrElse("")
      } yield {
        RudderWebProperty(
          RudderWebPropertyName(name),
          value,
          description
        )
      }
    } else Left(Err.UnexpectedObject(s"The given entry is not of the expected ObjectClass '${OC_PROPERTY}'. Entry details: ${e}"))
  }

  def rudderConfig2Entry(property: RudderWebProperty): LDAPEntry = {
    val entry = rudderDit.APPCONFIG.propertyModel(
      property.name
    )
    entry.resetValuesTo(A_PROPERTY_VALUE, property.value)
    entry.resetValuesTo(A_DESCRIPTION, property.description)
    entry
  }

}

// This need to be on top level, else lift json does absolutely nothing good.
// a stable case class for json serialisation
// { "acl": [ {"path":"some/path", "actions":["get","put"]}, {"path":"other/path","actions":["get"]}}
final case class JsonApiAuthz(path: String, actions: List[String])
object JsonApiAuthz {
  @nowarn("msg=type parameter Err.*") // we don't have any hand on that
  implicit val codecJsonApiAuthz: JsonCodec[JsonApiAuthz] = DeriveJsonCodec.gen

  /**
   * Enforce that permissions are sorted by path first, then by verb (verb here)
   */
  def from(acl: ApiAclElement): JsonApiAuthz = {
    JsonApiAuthz(acl.path.value, acl.actions.toList.map(_.name).sorted)
  }

  implicit val transformJsonApiAuthz: PartialTransformer[JsonApiAuthz, ApiAclElement] = {
    PartialTransformer.apply[JsonApiAuthz, ApiAclElement] {
      case JsonApiAuthz(path, actions) =>
        (for {
          p <- AclPath.parse(path)
          a <- actions.traverse(HttpAction.parse)
        } yield {
          ApiAclElement(p, a.toSet)
        }).asResult
    }
  }
}

final case class JsonApiAcl(acl: List[JsonApiAuthz]) extends AnyVal
object JsonApiAcl {
  implicit val decoderJsonApiAcl: JsonDecoder[JsonApiAcl] = JsonDecoder.list[JsonApiAuthz].map(JsonApiAcl.apply)
  implicit val encoderJsonApiAcl: JsonEncoder[JsonApiAcl] = JsonEncoder.list[JsonApiAuthz].contramap(_.acl)

  /*
   * When we get JsonApiAcl, we always group/sort by path when transforming into business elements
   */
  implicit val transformJsonApiAcl: PartialTransformer[JsonApiAcl, List[ApiAclElement]] = {
    PartialTransformer.apply[JsonApiAcl, List[ApiAclElement]] {
      case JsonApiAcl(acl) =>
        partial.Result
          .traverse(acl.iterator, (x: JsonApiAuthz) => x.transformIntoPartial[ApiAclElement], failFast = false)
          .map(x => {
            x.groupMapReduce(_.path)(identity)((a, b) => a.modify(_.actions).setTo(a.actions ++ b.actions))
              .values
              .toList
              .sortBy(_.path)
          })
    }
  }

  /**
   * Enforce that permissions are sorted by path first, then by verb (path here)
   */
  def from(acls: List[ApiAclElement]): JsonApiAcl = JsonApiAcl(acls.map(JsonApiAuthz.from).sortBy(_.path))

}
