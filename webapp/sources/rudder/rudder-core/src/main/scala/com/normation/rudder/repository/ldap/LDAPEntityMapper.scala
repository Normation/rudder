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

import com.normation.cfclerk.domain._
import com.normation.inventory.domain._
import com.normation.inventory.ldap.core.InventoryDit
import com.normation.inventory.ldap.core.InventoryMapper
import com.normation.inventory.ldap.core.LDAPConstants
import com.normation.inventory.ldap.core.LDAPConstants._
import com.normation.ldap.sdk._
import com.normation.rudder.api._
import com.normation.rudder.domain.NodeDit
import com.normation.rudder.domain.RudderDit
import com.normation.rudder.domain.RudderLDAPConstants._
import com.normation.rudder.domain.appconfig.RudderWebProperty
import com.normation.rudder.domain.appconfig.RudderWebPropertyName
import com.normation.rudder.domain.nodes.JsonSerialisation._
import com.normation.rudder.domain.nodes.Node
import com.normation.rudder.domain.nodes._
import com.normation.rudder.domain.parameters._
import com.normation.rudder.domain.policies.PolicyMode
import com.normation.rudder.domain.policies._
import com.normation.rudder.reports._
import com.normation.rudder.repository.json.DataExtractor.CompleteJson
import com.normation.rudder.rule.category.RuleCategory
import com.normation.rudder.rule.category.RuleCategoryId
import com.normation.rudder.services.queries._
import com.unboundid.ldap.sdk.DN
import net.liftweb.json.JsonAST.JObject
import net.liftweb.json.JsonDSL._
import net.liftweb.json._
import net.liftweb.util.Helpers._
import org.joda.time.DateTime
import com.normation.inventory.ldap.core.{InventoryMappingRudderError => Err}
import com.normation.inventory.ldap.core.InventoryMappingResult._
import cats._
import cats.data._
import cats.implicits._
import com.normation.NamedZioLogger
import scalaz.zio._
import scalaz.zio.syntax._
import com.normation.errors._

import scala.language.implicitConversions

final object NodeStateEncoder {
  implicit def enc(state: NodeState): String = state.name
  implicit def dec(state: String): Either[Throwable, NodeState] = {
    NodeState.values.find { state.toLowerCase() == _.name } match {
      case Some(s) => Right(s)
      case None    => Left(new IllegalArgumentException(s"'${state}' can not be decoded to a NodeState"))
    }
  }
}


/**
 * Map objects from/to LDAPEntries
 *
 */
class LDAPEntityMapper(
    rudderDit      : RudderDit
  , nodeDit        : NodeDit
  , inventoryDit   : InventoryDit
  , cmdbQueryParser: CmdbQueryParser
  , inventoryMapper: InventoryMapper
) extends NamedZioLogger {


  def loogerName = "rudder-ldap-entity-mapper"

    //////////////////////////////    Node    //////////////////////////////

  def nodeToEntry(node:Node) : LDAPEntry = {
    import NodeStateEncoder._
    val entry =
      if(node.isPolicyServer) {
        nodeDit.NODES.NODE.policyServerNodeModel(node.id)
      } else {
        nodeDit.NODES.NODE.nodeModel(node.id)
      }
    entry +=! (A_NAME, node.name)
    entry +=! (A_DESCRIPTION, node.description)
    entry +=! (A_STATE, enc(node.state))
    entry +=! (A_IS_SYSTEM, node.isSystem.toLDAPString)

    node.nodeReportingConfiguration.agentRunInterval match {
      case Some(interval) => entry +=! (A_SERIALIZED_AGENT_RUN_INTERVAL, compactRender(serializeAgentRunInterval(interval)))
      case _ =>
    }

    // for node properties, we ALWAYS filter-out properties coming from inventory,
    // because we don't want to store them there.
    entry +=! (A_NODE_PROPERTY, node.properties.collect { case p if(p.provider != Some(NodeProperty.customPropertyProvider)) => compactRender(p.toJson)}:_* )

    node.nodeReportingConfiguration.heartbeatConfiguration match {
      case Some(heatbeatConfiguration) =>
        val json = {
          import net.liftweb.json.JsonDSL._
          ( "overrides"       -> heatbeatConfiguration.overrides ) ~
          ( "heartbeatPeriod" -> heatbeatConfiguration.heartbeatPeriod)
        }
        entry +=! (A_SERIALIZED_HEARTBEAT_RUN_CONFIGURATION, compactRender(json))
      case _ => // Save nothing if missing
    }

    for {
      mode <- node.policyMode
    } entry += (A_POLICY_MODE, mode.name)

    entry
  }

  def serializeAgentRunInterval(agentInterval: AgentRunInterval) : JObject = {
    ( "overrides"  -> agentInterval.overrides ) ~
    ( "interval"   -> agentInterval.interval ) ~
    ( "startMinute"-> agentInterval.startMinute ) ~
    ( "startHour"  -> agentInterval.startHour ) ~
    ( "splaytime"  -> agentInterval.splaytime )
  }

  def unserializeAgentRunInterval(value:String): AgentRunInterval = {
    import net.liftweb.json.JsonParser._
    implicit val formats = DefaultFormats

    parse(value).extract[AgentRunInterval]
  }

  def unserializeNodeHeartbeatConfiguration(value:String): HeartbeatConfiguration = {
    import net.liftweb.json.JsonParser._
    implicit val formats = DefaultFormats

    parse(value).extract[HeartbeatConfiguration]
  }

  def entryToNode(e:LDAPEntry) : PureResult[Node] = {
    if(e.isA(OC_RUDDER_NODE)||e.isA(OC_POLICY_SERVER_NODE)) {
      //OK, translate
      for {
        id   <- nodeDit.NODES.NODE.idFromDn(e.dn).notOptional(s"Bad DN found for a Node: ${e.dn}")
        date <- e.requiredAs[GeneralizedTime]( _.getAsGTime, A_OBJECT_CREATION_DATE)
        agentRunInterval = e(A_SERIALIZED_AGENT_RUN_INTERVAL).map(unserializeAgentRunInterval(_))
        heartbeatConf = e(A_SERIALIZED_HEARTBEAT_RUN_CONFIGURATION).map(unserializeNodeHeartbeatConfiguration(_))
        policyMode <- e(A_POLICY_MODE) match {
                        case None => Right(None)
                        case Some(value) => PolicyMode.parse(value).map {Some(_) }
                      }
        properties <- e.valuesFor(A_NODE_PROPERTY).toList.traverse(v => net.liftweb.json.parseOpt(v) match {
                        case Some(json) => unserializeLdapNodeProperty(json).toPureResult
                        case None => Left(Unexpected("Invalid data when unserializing node property"))
                      })
      } yield {
        val hostname = e(A_NAME).getOrElse("")
        Node(
            id
          , hostname
          , e(A_DESCRIPTION).getOrElse("")
            // node state missing or unknown value => enable, so that we are upward compatible
          , NodeStateEncoder.dec(e(A_STATE).getOrElse(NodeState.Enabled.name)) match {
              case Right(s) => s
              case Left(ex) =>
                logEffect.debug(s"Can not decode 'state' for node '${hostname}' (${id.value}): ${ex.getMessage}")
                NodeState.Enabled
            }
          , e.getAsBoolean(A_IS_SYSTEM).getOrElse(false)
          , e.isA(OC_POLICY_SERVER_NODE)
          , date.dateTime
          , ReportingConfiguration(
                agentRunInterval
              , heartbeatConf
            )
          , properties
          , policyMode
        )
      }
    } else {
      Left(Unexpected(s"The given entry is not of the expected ObjectClass ${OC_RUDDER_NODE} or ${OC_POLICY_SERVER_NODE}. Entry details: ${e}"))
    }
  }
    //////////////////////////////    NodeInfo    //////////////////////////////

  /**
   * From a nodeEntry and an inventoryEntry, create a NodeInfo
   *
   * The only thing used in machineEntry is its object class.
   *
   */
  def convertEntriesToNodeInfos(nodeEntry: LDAPEntry, inventoryEntry: LDAPEntry, machineEntry: Option[LDAPEntry]) : IOResult[NodeInfo] = {
    //why not using InventoryMapper ? Some required things for node are not
    // wanted here ?
    for {
      node         <- entryToNode(nodeEntry).toIO
      checkSameID  <- if(nodeEntry(A_NODE_UUID).isDefined && nodeEntry(A_NODE_UUID) ==  inventoryEntry(A_NODE_UUID)) "Ok".succeed
                      else Err.MissingMandatory(s"Mismatch id for the node '${nodeEntry(A_NODE_UUID)}' and the inventory '${inventoryEntry(A_NODE_UUID)}'").fail

      nodeInfo     <- inventoryEntriesToNodeInfos(node, inventoryEntry, machineEntry)
    } yield {
      nodeInfo
    }
  }

  /**
   * Convert at the best you can node inventories to node information.
   * Some information will be false for sure - that's understood.
   */
  def convertEntriesToSpecialNodeInfos(inventoryEntry: LDAPEntry, machineEntry: Option[LDAPEntry]) : IOResult[NodeInfo] = {
    for {
      id   <- inventoryEntry(A_NODE_UUID).notOptional(s"Missing node id information in Node entry: ${inventoryEntry}").toIO
      node =  Node(
                  NodeId(id)
                , inventoryEntry(A_NAME).getOrElse("")
                , inventoryEntry(A_DESCRIPTION).getOrElse("")
                , NodeState.Enabled
                , inventoryEntry.getAsBoolean(A_IS_SYSTEM).getOrElse(false)
                , false //we don't know anymore if it was a policy server
                , new DateTime(0) // we don't know anymore the acceptation date
                , ReportingConfiguration(None, None) //we don't know anymore agent run frequency
                , Seq() //we forgot node properties
                , None
              )
     nodeInfo <- inventoryEntriesToNodeInfos(node, inventoryEntry, machineEntry)
    } yield {
      nodeInfo
    }
  }

  private[this] def inventoryEntriesToNodeInfos(node: Node, inventoryEntry: LDAPEntry, machineEntry: Option[LDAPEntry]) : IOResult[NodeInfo] = {
    //why not using InventoryMapper ? Some required things for node are not
    // wanted here ?
    for {
      // Compute the parent policy Id
      policyServerId <- inventoryEntry.valuesFor(A_POLICY_SERVER_UUID).toList match {
                          case Nil => Err.MissingMandatory(s"Missing policy server id for Node '${node.id.value}'. Entry details: ${inventoryEntry}").fail
                          case x :: Nil => x.succeed
                          case _ => Unexpected(s"Too many policy servers for a Node '${node.id.value}'. Entry details: ${inventoryEntry}").fail
                        }
      keys           =  inventoryEntry.valuesFor(A_PKEYS).map(Some(_))

      agentsName     <- {
                         val agents = inventoryEntry.valuesFor(A_AGENTS_NAME).toSeq.map(Some(_))
                         ZIO.foreach(agents.zipAll(keys,None,None)) {
                           case (Some(agent),key) => AgentInfoSerialisation.parseCompatNonJson(agent,key)
                           case (None,key)        => (Err.MissingMandatory(s"There was a public key defined for Node ${node.id.value},"+
                                                             " without a releated agent defined, it should not happen")).fail
                         }
                       }
      osDetails     <- inventoryMapper.mapOsDetailsFromEntry(inventoryEntry).toIO
      keyStatus     <- inventoryEntry(A_KEY_STATUS).map(KeyStatus(_)).getOrElse(Right(UndefinedKey)).toIO
      serverRoles   =  inventoryEntry.valuesFor(A_SERVER_ROLE).map(ServerRole(_)).toSet
      timezone      =  (inventoryEntry(A_TIMEZONE_NAME), inventoryEntry(A_TIMEZONE_OFFSET)) match {
                         case (Some(name), Some(offset)) => Some(NodeTimezone(name, offset))
                         case _                          => None
                       }
    } yield {
      val machineInfo = machineEntry.flatMap { e =>
        for {
          machineType <- inventoryMapper.machineTypeFromObjectClasses(e.valuesFor("objectClass"))
          machineUuid <- e(A_MACHINE_UUID).map(MachineUuid)
        } yield {
          MachineInfo(
              machineUuid
            , machineType
            , e(LDAPConstants.A_SERIAL_NUMBER)
            , e(LDAPConstants.A_MANUFACTURER).map(Manufacturer(_))
          )
        }
      }
      // custom properties mapped as NodeProperties
      val customProperties = inventoryEntry.valuesFor(A_CUSTOM_PROPERTY).toList.flatMap { json =>
        import inventoryMapper.CustomPropertiesSerialization._
        json.toCustomProperty match {
          case Left(ex)  =>
            logEffect.error(s"Error when trying to deserialize Node Custom Property, it will be ignored: ${ex.getMessage}", ex)
            None
          case Right(cs) =>
            Some(NodeProperty(cs.name, cs.value, Some(NodeProperty.customPropertyProvider)))
        }
      }

      // fetch the inventory datetime of the object
      val dateTime = inventoryEntry.getAsGTime(A_INVENTORY_DATE) map(_.dateTime) getOrElse(DateTime.now)
      NodeInfo(
          node.copy(properties = overrideProperties(node.id, node.properties, customProperties))
        , inventoryEntry(A_HOSTNAME).getOrElse("")
        , machineInfo
        , osDetails
        , inventoryEntry.valuesFor(A_LIST_OF_IP).toList
        , dateTime
        , keyStatus
        , scala.collection.mutable.Seq() ++ agentsName
        , NodeId(policyServerId)
        , inventoryEntry(A_ROOT_USER).getOrElse("")
        , serverRoles
        , inventoryEntry(A_ARCH)
        , inventoryEntry(A_OS_RAM).map{ m  => MemorySize(m) }
        , timezone
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
  def overrideProperties(nodeId: NodeId, existing: Seq[NodeProperty], inventory: Seq[NodeProperty]): Seq[NodeProperty] = {
    val customProperties = inventory.map(x => (x.name, x) ).toMap
    val overriden = existing.map { current =>
      customProperties.get(current.name) match {
        case None         => current
        case Some(custom) =>
          current.provider match {
            case None | Some(NodeProperty.rudderNodePropertyProvider) => //override and log
              logEffect.info(s"On node [${nodeId.value}]: overriding existing node property '${current.name}' with custom node inventory property with same name.")
              custom
            case other => // keep existing prop from other provider but log
              logEffect.info(s"On node [${nodeId.value}]: ignoring custom node inventory property with name '${current.name}' (an other provider already set that property).")
              current
          }
      }
    }

    // now, filter remaining inventory properties (ie the one not already processed)
    val alreadyDone = overriden.map( _.name ).toSet

    overriden ++ inventory.filter(x => !alreadyDone.contains(x.name))
  }


  /**
   * Build the ActiveTechniqueCategoryId from the given DN
   */
  def dn2ActiveTechniqueCategoryId(dn:DN) : ActiveTechniqueCategoryId = {
    import net.liftweb.common._
    rudderDit.ACTIVE_TECHNIQUES_LIB.getCategoryIdValue(dn) match {
      case Full(value) => ActiveTechniqueCategoryId(value)
      case e:EmptyBox => throw new RuntimeException("The dn %s is not a valid Active Technique Category ID. Error was: %s".format(dn,e.toString))
    }
  }

  def dn2ActiveTechniqueId(dn:DN) : ActiveTechniqueId = {
    import net.liftweb.common._
    rudderDit.ACTIVE_TECHNIQUES_LIB.getActiveTechniqueId(dn) match {
      case Full(value) => ActiveTechniqueId(value)
      case e:EmptyBox => throw new RuntimeException("The dn %s is not a valid Active Technique ID. Error was: %s".format(dn,e.toString))
    }
  }

  /**
   * Build the Group Category Id from the given DN
   */
  def dn2NodeGroupCategoryId(dn:DN) : NodeGroupCategoryId = {
    import net.liftweb.common._
    rudderDit.GROUP.getCategoryIdValue(dn) match {
      case Full(value) => NodeGroupCategoryId(value)
      case e:EmptyBox => throw new RuntimeException("The dn %s is not a valid Node Group Category ID. Error was: %s".format(dn,e.toString))
    }
  }

  /**
   * Build the Node Group Category Id from the given DN
   */
  def dn2NodeGroupId(dn:DN) : NodeGroupId = {
    import net.liftweb.common._
    rudderDit.GROUP.getGroupId(dn) match {
      case Full(value) => NodeGroupId(value)
      case e:EmptyBox => throw new RuntimeException("The dn %s is not a valid Node Group ID. Error was: %s".format(dn,e.toString))
    }
  }

  /**
   * Build the Rule Category Id from the given DN
   */
  def dn2RuleCategoryId(dn:DN) : RuleCategoryId = {
    import net.liftweb.common._
    rudderDit.RULECATEGORY.getCategoryIdValue(dn) match {
      case Full(value) => RuleCategoryId(value)
      case e:EmptyBox => throw new RuntimeException("The dn %s is not a valid Rule Category ID. Error was: %s".format(dn,e.toString))
    }
  }

  def dn2LDAPRuleID(dn:DN) : DirectiveId = {
    import net.liftweb.common._
    rudderDit.ACTIVE_TECHNIQUES_LIB.getLDAPRuleID(dn) match {
      case Full(value) => DirectiveId(value)
      case e:EmptyBox => throw new RuntimeException("The dn %s is not a valid Directive ID. Error was: %s".format(dn,e.toString))
    }
  }

  def dn2RuleId(dn:DN) : RuleId = {
    import net.liftweb.common._
    rudderDit.RULES.getRuleId(dn) match {
      case Full(value) => RuleId(value)
      case e:EmptyBox => throw new RuntimeException("The dn %s is not a valid Rule ID. Error was: %s".format(dn,e.toString))
    }
  }

  def nodeDn2OptNodeId(dn:DN) : InventoryMappingPure[NodeId] = {
    val rdn = dn.getRDN
    if(!rdn.isMultiValued && rdn.hasAttribute(A_NODE_UUID)) {
      Right(NodeId(rdn.getAttributeValues()(0)))
    } else Left(Err.MalformedDN("Bad RDN for a node, expecting attribute name '%s', got: %s".format(A_NODE_UUID,rdn)))
  }

  //////////////////////////////    ActiveTechniqueCategory    //////////////////////////////

  /**
   * children and items are left empty
   */
  def entry2ActiveTechniqueCategory(e:LDAPEntry) : InventoryMappingPure[ActiveTechniqueCategory] = {
    if(e.isA(OC_TECHNIQUE_CATEGORY)) {
      //OK, translate
      for {
        id <- e(A_TECHNIQUE_CATEGORY_UUID).notOptional("Missing required id (attribute name %s) in entry %s".format(A_TECHNIQUE_CATEGORY_UUID, e))
        name <- e(A_NAME).notOptional("Missing required name (attribute name %s) in entry %s".format(A_NAME, e))
        description = e(A_DESCRIPTION).getOrElse("")
        isSystem = e.getAsBoolean(A_IS_SYSTEM).getOrElse(false)
      } yield {
         ActiveTechniqueCategory(ActiveTechniqueCategoryId(id), name, description, Nil, Nil, isSystem)
      }
    } else Failure("The given entry is not of the expected ObjectClass '%s'. Entry details: %s".format(OC_TECHNIQUE_CATEGORY, e))
  }

  /**
   * children and items are ignored
   */
  def activeTechniqueCategory2ldap(category:ActiveTechniqueCategory, parentDN:DN) = {
    val entry = rudderDit.ACTIVE_TECHNIQUES_LIB.activeTechniqueCategoryModel(category.id.value, parentDN)
    entry +=! (A_NAME, category.name)
    entry +=! (A_DESCRIPTION, category.description)
    entry +=! (A_IS_SYSTEM, category.isSystem.toLDAPString)
    entry
  }

  //////////////////////////////    ActiveTechnique    //////////////////////////////

  //two utilities to serialize / deserialize Map[TechniqueVersion,DateTime]
  def unserializeAcceptations(value:String):Map[TechniqueVersion, DateTime] = {
    import net.liftweb.json.JsonAST.JField
    import net.liftweb.json.JsonAST.JString
    import net.liftweb.json.JsonParser._

    parse(value) match {
      case JObject(fields) =>
        fields.collect { case JField(version, JString(date)) =>
          (TechniqueVersion(version) -> GeneralizedTime(date).dateTime)
        }.toMap
      case _ => Map()
    }
  }

  def serializeAcceptations(dates:Map[TechniqueVersion,DateTime]) : JObject = {
    ( JObject(List()) /: dates) { case (js, (version, date)) =>
      js ~ (version.toString -> GeneralizedTime(date).toString)
    }
  }

  /**
   * Build a ActiveTechnique from and LDAPEntry.
   * children directives are left empty
   */
  def entry2ActiveTechnique(e:LDAPEntry) : InventoryMappingPure[ActiveTechnique] = {
    if(e.isA(OC_ACTIVE_TECHNIQUE)) {
      //OK, translate
      for {
        id <- e(A_ACTIVE_TECHNIQUE_UUID).notOptional("Missing required id (attribute name %s) in entry %s".format(A_ACTIVE_TECHNIQUE_UUID, e))
        refTechniqueUuid <- e(A_TECHNIQUE_UUID).map(x => TechniqueName(x)).notOptional("Missing required name (attribute name %s) in entry %s".format(A_TECHNIQUE_UUID, e))
        isEnabled = e.getAsBoolean(A_IS_ENABLED).getOrElse(false)
        isSystem = e.getAsBoolean(A_IS_SYSTEM).getOrElse(false)
        acceptationDatetimes = e(A_ACCEPTATION_DATETIME).map(unserializeAcceptations(_)).getOrElse(Map())
      } yield {
         ActiveTechnique(ActiveTechniqueId(id), refTechniqueUuid, acceptationDatetimes, Nil, isEnabled, isSystem)
      }
    } else Failure("The given entry is not of the expected ObjectClass '%s'. Entry details: %s".format(OC_TECHNIQUE_CATEGORY, e))
  }

  def activeTechnique2Entry(activeTechnique:ActiveTechnique, parentDN:DN) : LDAPEntry = {
    val entry = rudderDit.ACTIVE_TECHNIQUES_LIB.activeTechniqueModel(
        activeTechnique.id.value,
        parentDN,
        activeTechnique.techniqueName,
        serializeAcceptations(activeTechnique.acceptationDatetimes),
        activeTechnique.isEnabled,
        activeTechnique.isSystem
    )
    entry
  }

   //////////////////////////////    NodeGroupCategory    //////////////////////////////

  /**
   * children and items are left empty
   */
  def entry2NodeGroupCategory(e:LDAPEntry) : InventoryMappingPure[NodeGroupCategory] = {
    if(e.isA(OC_GROUP_CATEGORY)) {
      //OK, translate
      for {
        id <- e(A_GROUP_CATEGORY_UUID).notOptional("Missing required id (attribute name %s) in entry %s".format(A_GROUP_CATEGORY_UUID, e))
        name <- e(A_NAME).notOptional("Missing required name (attribute name %s) in entry %s".format(A_NAME, e))
        description = e(A_DESCRIPTION).getOrElse("")
        isSystem = e.getAsBoolean(A_IS_SYSTEM).getOrElse(false)
      } yield {
         NodeGroupCategory(NodeGroupCategoryId(id), name, description, Nil, Nil, isSystem)
      }
    } else Failure("The given entry is not of the expected ObjectClass '%s'. Entry details: %s".format(OC_GROUP_CATEGORY, e))
  }

  /**
   * children and items are ignored
   */
  def nodeGroupCategory2ldap(category:NodeGroupCategory, parentDN:DN) = {
    val entry = rudderDit.GROUP.groupCategoryModel(category.id.value, parentDN)
    entry +=! (A_NAME, category.name)
    entry +=! (A_DESCRIPTION, category.description)
    entry +=! (A_IS_SYSTEM, category.isSystem.toLDAPString)
    entry
  }

  ////////////////////////////////// Node Group //////////////////////////////////

   /**
   * Build a node group from and LDAPEntry.
   */
  def entry2NodeGroup(e:LDAPEntry) : InventoryMappingPure[NodeGroup] = {
    if(e.isA(OC_RUDDER_NODE_GROUP)) {
      //OK, translate
      for {
        id      <- e.required(A_NODE_GROUP_UUID)
        name    <- e.required(A_NAME)
        query   =  e(A_QUERY_NODE_GROUP)
        nodeIds =  e.valuesFor(A_NODE_UUID).map(x => NodeId(x))
        query   <- e(A_QUERY_NODE_GROUP) match {
                     case None => Right(None)
                     case Some(q) => cmdbQueryParser(q).toPureResult match {
                       case Right(x) => Right(Some(x))
                       case Left(err) =>
                         val error = s"Error when parsing query for node group persisted at DN '${e.dn}' (name: '${name}'), that seems to be an inconsistency. You should modify that group. Erros was: ${err.fullMsg}}"
                         logEffect.error(error)
                         Right(None)
                     }
                   }
        isDynamic   = e.getAsBoolean(A_IS_DYNAMIC).getOrElse(false)
        isEnabled   = e.getAsBoolean(A_IS_ENABLED).getOrElse(false)
        isSystem    = e.getAsBoolean(A_IS_SYSTEM).getOrElse(false)
        description = e(A_DESCRIPTION).getOrElse("")
      } yield {
         NodeGroup(NodeGroupId(id), name, description, query, isDynamic, nodeIds, isEnabled, isSystem)
      }
    } else Left(Err.UnexpectedObject("The given entry is not of the expected ObjectClass '%s'. Entry details: %s".format(OC_RUDDER_NODE_GROUP, e)))
  }

  //////////////////////////////    Special Policy target info    //////////////////////////////

  def entry2RuleTargetInfo(e:LDAPEntry) : InventoryMappingPure[RuleTargetInfo] = {
    for {
      target <- {
        if(e.isA(OC_RUDDER_NODE_GROUP)) {
          e.required(A_NODE_GROUP_UUID).map(id => GroupTarget(NodeGroupId(id)))
        } else if(e.isA(OC_SPECIAL_TARGET))
          for {
            targetString <- e.required(A_RULE_TARGET)
            target <- RuleTarget.unser(targetString).notOptional("Can not unserialize target, '%s' does not match any known target format".format(targetString))
          } yield {
            target
          } else Left(Err.UnexpectedObject(s"The given entry is not of the expected ObjectClass '${OC_RUDDER_NODE_GROUP}' or '${OC_SPECIAL_TARGET}. Entry details: ${e}"))
      }
      name <-  e(A_NAME).notOptional("Missing required name (attribute name %s) in entry %s".format(A_NAME, e))
      description = e(A_DESCRIPTION).getOrElse("")
      isEnabled = e.getAsBoolean(A_IS_ENABLED).getOrElse(false)
      isSystem = e.getAsBoolean(A_IS_SYSTEM).getOrElse(false)
    } yield {
      RuleTargetInfo(target, name , description , isEnabled , isSystem)
    }
  }

  //////////////////////////////    Directive    //////////////////////////////

  def entry2Directive(e:LDAPEntry) : PureResult[Directive] = {

    if(e.isA(OC_DIRECTIVE)) {
      //OK, translate
      for {
        id               <- e.required(A_DIRECTIVE_UUID)
        s_version        <- e.required(A_TECHNIQUE_VERSION)
        policyMode       <- e(A_POLICY_MODE) match {
                               case None => Right(None)
                               case Some(value) => PolicyMode.parse(value).map {Some(_) }
                             }
        version          <- tryo(TechniqueVersion(s_version)).toPureResult
        name             =  e(A_NAME).getOrElse(id)
        params           =  parsePolicyVariables(e.valuesFor(A_DIRECTIVE_VARIABLES).toSeq)
        shortDescription =  e(A_DESCRIPTION).getOrElse("")
        longDescription  =  e(A_LONG_DESCRIPTION).getOrElse("")
        priority         =  e.getAsInt(A_PRIORITY).getOrElse(0)
        isEnabled        =  e.getAsBoolean(A_IS_ENABLED).getOrElse(false)
        isSystem         =  e.getAsBoolean(A_IS_SYSTEM).getOrElse(false)
        tags             <- e(A_SERIALIZED_TAGS) match {
                              case None       => Right(Tags(Set()))
                              case Some(tags) => CompleteJson.unserializeTags(tags).toPureResult.chainError(s"Invalid attribute value for tags ${A_SERIALIZED_TAGS}: ${tags}")
                            }
      } yield {
        Directive(
            DirectiveId(id)
          , version
          , params
          , name
          , shortDescription
          , policyMode
          , longDescription
          , priority
          , isEnabled
          , isSystem
          , tags
        )
      }
    } else Left(Err.UnexpectedObject("The given entry is not of the expected ObjectClass '%s'. Entry details: %s".format(OC_DIRECTIVE, e)))
  }

  def userDirective2Entry(directive:Directive, parentDN:DN) : LDAPEntry = {
    val entry = rudderDit.ACTIVE_TECHNIQUES_LIB.directiveModel(
        directive.id.value,
        directive.techniqueVersion,
        parentDN
    )

    entry +=! (A_DIRECTIVE_VARIABLES, policyVariableToSeq(directive.parameters):_*)
    entry +=! (A_NAME, directive.name)
    entry +=! (A_DESCRIPTION, directive.shortDescription)
    entry +=! (A_LONG_DESCRIPTION, directive.longDescription.toString)
    entry +=! (A_PRIORITY, directive.priority.toString)
    entry +=! (A_IS_ENABLED, directive.isEnabled.toLDAPString)
    entry +=! (A_IS_SYSTEM, directive.isSystem.toLDAPString)
    directive.policyMode.foreach ( mode => entry +=! (A_POLICY_MODE, mode.name) )
    entry +=! (A_SERIALIZED_TAGS, JsonTagSerialisation.serializeTags(directive.tags))
    entry
  }

  //////////////////////////////    Rule Category    //////////////////////////////

  /**
   * children and items are left empty
   */
  def entry2RuleCategory(e:LDAPEntry) : InventoryMappingPure[RuleCategory] = {
    if(e.isA(OC_RULE_CATEGORY)) {
      //OK, translate
      for {
        id          <- e(A_RULE_CATEGORY_UUID).notOptional(s"Missing required id (attribute name '${A_RULE_CATEGORY_UUID}) in entry ${e}")
        name        <- e(A_NAME).notOptional(s"Missing required name (attribute name '${A_NAME}) in entry ${e}")
        description =  e(A_DESCRIPTION).getOrElse("")
        isSystem    =  e.getAsBoolean(A_IS_SYSTEM).getOrElse(false)
      } yield {
         RuleCategory(RuleCategoryId(id), name, description, Nil, isSystem)
      }
    } else Left(Err.UnexpectedObject(s"The given entry is not of the expected ObjectClass '${OC_RULE_CATEGORY}'. Entry details: ${e}"))
  }

  /**
   * children and items are ignored
   */
  def ruleCategory2ldap(category:RuleCategory, parentDN:DN) = {
    rudderDit.RULECATEGORY.ruleCategoryModel(category.id.value, parentDN, category.name,category.description,category.isSystem)
  }

  //////////////////////////////    Rule    //////////////////////////////
  def entry2OptTarget(optValue:Option[String]) : InventoryMappingPure[Option[RuleTarget]] = {
    optValue match {
      case None        => Right(None)
      case Some(value) =>
        RuleTarget.unser(value).notOptional(s"Bad parameter for a rule target: ${value}").map(Some(_))
    }
  }

  def entry2Rule(e:LDAPEntry) : PureResult[Rule] = {

    if(e.isA(OC_RULE)) {
      for {
        id       <- e.required(A_RULE_UUID)
        tags     <- e(A_SERIALIZED_TAGS) match {
                      case None => Right(Tags(Set()))
                      case Some(tags) => CompleteJson.unserializeTags(tags).toPureResult.chainError(s"Invalid attribute value for tags ${A_SERIALIZED_TAGS}: ${tags}")
                    }
      } yield {
        val targets = for {
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
        val directiveIds = e.valuesFor(A_DIRECTIVE_UUID).map(x => DirectiveId(x))
        val name = e(A_NAME).getOrElse(id)
        val shortDescription = e(A_DESCRIPTION).getOrElse("")
        val longDescription = e(A_LONG_DESCRIPTION).getOrElse("")
        val isEnabled = e.getAsBoolean(A_IS_ENABLED).getOrElse(false)
        val isSystem = e.getAsBoolean(A_IS_SYSTEM).getOrElse(false)
        val category = e(A_RULE_CATEGORY).map(RuleCategoryId(_)).getOrElse(rudderDit.RULECATEGORY.rootCategoryId)

        Rule(
            RuleId(id)
          , name
          , category
          , targets
          , directiveIds
          , shortDescription
          , longDescription
          , isEnabled
          , isSystem
          , tags
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
  def rule2Entry(rule:Rule) : LDAPEntry = {
    val entry = rudderDit.RULES.ruleModel(
        rule.id.value
      , rule.name
      , rule.isEnabledStatus
      , rule.isSystem
      , rule.categoryId.value
    )

    entry +=! (A_RULE_TARGET, rule.targets.map( _.target).toSeq :_* )
    entry +=! (A_DIRECTIVE_UUID, rule.directiveIds.map( _.value).toSeq :_* )
    entry +=! (A_DESCRIPTION, rule.shortDescription)
    entry +=! (A_LONG_DESCRIPTION, rule.longDescription.toString)
    entry +=! (A_SERIALIZED_TAGS, JsonTagSerialisation.serializeTags(rule.tags))

    entry
  }

  //////////////////////////////    API Accounts    //////////////////////////////

  def serApiAcl(authz: List[ApiAclElement]): String = {
    import net.liftweb.json.Serialization._
    import net.liftweb.json._
    implicit val formats = Serialization.formats(NoTypeHints)
    val toSerialize = JsonApiAcl(acl = authz.map(a =>
      JsonApiAuthz(path = a.path.value, actions = a.actions.toList.map(_.name))
    ))
    write[JsonApiAcl](toSerialize)
  }
  def unserApiAcl(s: String): Either[String, List[ApiAclElement]] = {
    import cats.implicits._
    import net.liftweb.json._
    implicit val formats = net.liftweb.json.DefaultFormats
    for {
      json    <- parseOpt(s).toRight(s"The following string can not be parsed as a JSON object for API ACL: ${s}")
      jacl    <- (json.extractOpt[JsonApiAcl]).toRight(s"Can not extract API ACL object from json: ${s}")
      acl     <- jacl.acl.traverse { case JsonApiAuthz(path, actions) =>
                   for {
                     p <- AclPath.parse(path)
                     a <- actions.traverse(HttpAction.parse)
                   } yield {
                     ApiAclElement(p, a.toSet)
                   }
                 }
    } yield {
      acl
    }
  }


  /**
   * Build an API Account from an entry
   */
  def entry2ApiAccount(e:LDAPEntry) : InventoryMappingPure[ApiAccount] = {
    if(e.isA(OC_API_ACCOUNT)) {
      //OK, translate
      for {
        id                    <- e(A_API_UUID).map( ApiAccountId(_) ).notOptional(s"Missing required id (attribute name ${A_API_UUID}) in entry ${e}")
        name                  <- e(A_NAME).map( ApiAccountName(_) ).notOptional(s"Missing required name (attribute name ${A_NAME}) in entry ${e}")
        token                 <- e(A_API_TOKEN).map( ApiToken(_) ).notOptional(s"Missing required token (attribute name ${A_API_TOKEN}) in entry ${e}")
        creationDatetime      <- e.getAsGTime(A_CREATION_DATETIME).notOptional(s"Missing required creation timestamp (attribute name ${A_CREATION_DATETIME}) in entry ${e}")
        tokenCreationDatetime <- e.getAsGTime(A_API_TOKEN_CREATION_DATETIME).notOptional(s"Missing required token creation timestamp (attribute name ${A_API_TOKEN_CREATION_DATETIME}) in entry ${e}")
        isEnabled             =  e.getAsBoolean(A_IS_ENABLED).getOrElse(false)
        description           =  e(A_DESCRIPTION).getOrElse("")
        // expiration date is optionnal
        expirationDate        =  e.getAsGTime(A_API_EXPIRATION_DATETIME)
        //api authz kind/acl are not optionnal, but may be missing for Rudder < 4.3 migration
        //in that case, use the defaultACL
        accountType           =  e(A_API_KIND) match {
                                   case None    => ApiAccountType.PublicApi // this is the default
                                   case Some(s) => ApiAccountType.values.find( _.name == s ).getOrElse(ApiAccountType.PublicApi)
                                 }
        authz                 <- e(A_API_AUTHZ_KIND) match {
                                   case None    =>
                                     if(accountType == ApiAccountType.PublicApi) {
                                       logEffect.warn(s"Missing API authorizations level kind for token '${name.value}' with id '${id.value}'")
                                     }
                                     Right(ApiAuthorization.None) // for Rudder < 4.3, it should have been migrated. So here, we just don't gave any access.
                                   case Some(s) => ApiAuthorizationKind.parse(s) match {
                                     case Left(error) => Left(Err.UnexpectedObject(error))
                                     case Right(kind) => kind match {
                                       case ApiAuthorizationKind.ACL =>
                                         //parse acl
                                         e(A_API_ACL) match {
                                           case None    =>
                                             logEffect.debug(s"API authorizations level kind for token '${name.value}' with id '${id.value}' is 'ACL' but it doesn't have any ACLs conigured")
                                             Right(ApiAuthorization.None) // for Rudder < 4.3, it should have been migrated. So here, we just don't gave any access.
                                           case Some(s) => unserApiAcl(s) match {
                                             case Right(x)  => Right(ApiAuthorization.ACL(x))
                                             case Left(msg) => Left(Err.UnexpectedObject(msg))
                                           }
                                         }
                                       case ApiAuthorizationKind.None => Right(ApiAuthorization.None)
                                       case ApiAuthorizationKind.RO   => Right(ApiAuthorization.RO  )
                                       case ApiAuthorizationKind.RW   => Right(ApiAuthorization.RW  )
                                     }
                                   }
                                 }
      } yield {

        def warnOnIgnoreAuthz(): Unit = {
          if(e(A_API_AUTHZ_KIND).isDefined || e(A_API_EXPIRATION_DATETIME).isDefined) {
            //this is a log for dev, an user can't do anything about it.
            logEffect.debug(s"Attribute '${A_API_AUTHZ_KIND}' or '${A_API_EXPIRATION_DATETIME}' is defined for " +
                        s"API account '${name.value}' [${id.value}], it will be ignored because the account is of type '${accountType.name}'.")
          }
        }

        val accountKind = accountType match {
          case ApiAccountType.System =>
            warnOnIgnoreAuthz()
            ApiAccountKind.System
          case ApiAccountType.User =>
            warnOnIgnoreAuthz()
            ApiAccountKind.User
          case ApiAccountType.PublicApi =>
            ApiAccountKind.PublicApi(authz, expirationDate.map(_.dateTime))
        }

        ApiAccount(id, accountKind, name, token, description, isEnabled, creationDatetime.dateTime, tokenCreationDatetime.dateTime)
      }
    } else Left(Err.UnexpectedObject(s"The given entry is not of the expected ObjectClass '${OC_API_ACCOUNT}'. Entry details: ${e}"))
  }

  def apiAccount2Entry(principal:ApiAccount) : LDAPEntry = {
    val mod = LDAPEntry(rudderDit.API_ACCOUNTS.API_ACCOUNT.dn(principal.id))
    mod +=! (A_OC, OC.objectClassNames(OC_API_ACCOUNT).toSeq:_*)
    mod +=! (A_API_UUID, principal.id.value)
    mod +=! (A_NAME, principal.name.value)
    mod +=! (A_CREATION_DATETIME, GeneralizedTime(principal.creationDate).toString)
    mod +=! (A_API_TOKEN, principal.token.value)
    mod +=! (A_API_TOKEN_CREATION_DATETIME, GeneralizedTime(principal.tokenGenerationDate).toString)
    mod +=! (A_DESCRIPTION, principal.description)
    mod +=! (A_IS_ENABLED, principal.isEnabled.toLDAPString)
    mod +=! (A_API_KIND, principal.kind.kind.name)

    principal.kind match {
      case ApiAccountKind.PublicApi(authz, exp) =>
        exp.foreach { e =>
          mod +=! (A_API_EXPIRATION_DATETIME, GeneralizedTime(e).toString())
        }
        //authorisation
        authz match {
          case ApiAuthorization.ACL(acl) =>
            mod +=! (A_API_AUTHZ_KIND, authz.kind.name)
            mod +=! (A_API_ACL, serApiAcl(acl))
          case x =>
            mod +=! (A_API_AUTHZ_KIND, x.kind.name)
        }
      case _ => //nothing to add
    }
    mod
  }

  //////////////////////////////    Parameters    //////////////////////////////

  def entry2Parameter(e:LDAPEntry) : InventoryMappingPure[GlobalParameter] = {
    if(e.isA(OC_PARAMETER)) {
      //OK, translate
      for {
        name        <- e(A_PARAMETER_NAME).notOptional("Missing required attribute %s in entry %s".format(A_PARAMETER_NAME, e))
        value       = e(A_PARAMETER_VALUE).getOrElse("")
        description = e(A_DESCRIPTION).getOrElse("")
        overridable = e.getAsBoolean(A_PARAMETER_OVERRIDABLE).getOrElse(true)
      } yield {
        GlobalParameter(
            ParameterName(name)
          , value
          , description
          , overridable
        )
      }
    } else Failure("The given entry is not of the expected ObjectClass '%s'. Entry details: %s".format(OC_PARAMETER, e))
  }

  def parameter2Entry(parameter: GlobalParameter) : LDAPEntry = {
    val entry = rudderDit.PARAMETERS.parameterModel(
        parameter.name
    )
    entry +=! (A_PARAMETER_VALUE, parameter.value)
    entry +=! (A_PARAMETER_OVERRIDABLE, parameter.overridable.toLDAPString)
    entry +=! (A_DESCRIPTION, parameter.description)
    entry
  }

  //////////////////////////////    Rudder Config    //////////////////////////////

  def entry2RudderConfig(e:LDAPEntry) : InventoryMappingPure[RudderWebProperty] = {
    if(e.isA(OC_PROPERTY)) {
      //OK, translate
      for {
        name        <-e(A_PROPERTY_NAME).notOptional(s"Missing required attribute ${A_PROPERTY_NAME} in entry ${e}")
        value       = e(A_PROPERTY_VALUE).getOrElse("")
        description = e(A_DESCRIPTION).getOrElse("")
      } yield {
        RudderWebProperty(
            RudderWebPropertyName(name)
          , value
          , description
        )
      }
    } else Failure(s"The given entry is not of the expected ObjectClass '${OC_PROPERTY}'. Entry details: ${e}")
  }

  def rudderConfig2Entry(property: RudderWebProperty) : LDAPEntry = {
    val entry = rudderDit.APPCONFIG.propertyModel(
        property.name
    )
    entry +=! (A_PROPERTY_VALUE, property.value)
    entry +=! (A_DESCRIPTION, property.description)
    entry
  }

}

// This need to be on top level, else lift json does absolutly nothing good.
// a stable case class for json serialisation
// { "acl": [ {"path":"some/path", "actions":["get","put"]}, {"path":"other/path","actions":["get"]}}
final case class JsonApiAcl(acl: List[JsonApiAuthz])
final case class JsonApiAuthz(path: String, actions: List[String])

