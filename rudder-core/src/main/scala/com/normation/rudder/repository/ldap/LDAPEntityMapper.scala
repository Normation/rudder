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

package com.normation.rudder.repository.ldap

import com.unboundid.ldap.sdk.DN
import com.normation.utils.Utils
import com.normation.utils.Control._
import com.normation.inventory.domain._
import com.normation.inventory.ldap.core.InventoryDit
import com.normation.inventory.ldap.core.LDAPConstants
import LDAPConstants._
import com.normation.ldap.sdk._
import com.normation.cfclerk.domain._
import com.normation.cfclerk.services._
import com.normation.rudder.domain.Constants._
import com.normation.rudder.domain.RudderLDAPConstants._
import com.normation.rudder.domain.{NodeDit,RudderDit}
import com.normation.rudder.domain.servers._
import com.normation.rudder.domain.nodes.Node
import com.normation.rudder.domain.queries._
import com.normation.rudder.domain.policies._
import com.normation.rudder.domain.nodes._
import com.normation.rudder.services.queries._
import org.joda.time.Duration
import org.joda.time.DateTime
import net.liftweb.common._
import Box._
import net.liftweb.util.Helpers._
import scala.xml.{Text,NodeSeq}
import com.normation.exceptions.{BusinessException,TechnicalException}
import com.normation.rudder.services.policies.VariableBuilderService
import net.liftweb.json.JsonAST.JObject



/**
 * Map objects from/to LDAPEntries
 *
 */
class LDAPEntityMapper(
    rudderDit      : RudderDit
  , nodeDit        : NodeDit
  , inventoryDit   : InventoryDit
  , cmdbQueryParser: CmdbQueryParser
) extends Loggable {
  
  
    //////////////////////////////    Node    //////////////////////////////

  
  
  def nodeToEntry(node:Node) : LDAPEntry = {
    val entry = 
      if(node.isPolicyServer) {
        nodeDit.NODES.NODE.policyServerNodeModel(node.id)
      } else {
        nodeDit.NODES.NODE.nodeModel(node.id)
      }
    entry +=! (A_NAME, node.name)
    entry +=! (A_DESCRIPTION, node.description)
    entry +=! (A_IS_BROKEN, node.isBroken.toLDAPString)
    entry +=! (A_IS_SYSTEM, node.isSystem.toLDAPString)
    entry
  }

  
    //////////////////////////////    NodeInfo    //////////////////////////////

  val nodeInfoAttributes = Seq(A_OC, A_NODE_UUID, A_HOSTNAME, A_OS_FULL_NAME, A_NAME, A_POLICY_SERVER_UUID, A_LIST_OF_IP, A_OBJECT_CREATION_DATE,A_AGENTS_NAME,A_PKEYS, A_ROOT_USER)
  
  /**
   * From a nodeEntry and an inventoryEntry, create a NodeInfo
   * 
   * @param nodeEntry
   * @param inventoryEntry
   * @return
   */
  def convertEntriesToNodeInfos(nodeEntry:LDAPEntry, inventoryEntry:LDAPEntry) : Box[NodeInfo] = {
    //why not using InventoryMapper ? Some required things for node are not 
    // wanted here ?
    
    for {
      checkIsANode <- if(nodeEntry.isA(OC_RUDDER_NODE)) Full("ok") else Failure("Bad object class, need %s and found %s".format(OC_RUDDER_NODE,nodeEntry.valuesFor(A_OC)))
      checkIsANode <- if(inventoryEntry.isA(OC_NODE)) Full("Ok") else Failure("Bad object class, need %s and found %s".format(OC_NODE,inventoryEntry.valuesFor(A_OC)))
      checkSameID <- 
        if(nodeEntry(A_NODE_UUID).isDefined && nodeEntry(A_NODE_UUID) ==  inventoryEntry(A_NODE_UUID)) Full("Ok")
        else Failure("Mismatch id for the node %s and the inventory %s".format(nodeEntry(A_NODE_UUID), inventoryEntry(A_NODE_UUID)))
      id <- nodeDit.NODES.NODE.idFromDn(nodeEntry.dn) ?~! "Bad DN found for a Node: %s".format(nodeEntry.dn)
      // Compute the parent policy Id
      policyServerId <- inventoryEntry.valuesFor(A_POLICY_SERVER_UUID).toList match {
        case Nil => Failure("No policy servers for a Node: %s".format(nodeEntry.dn))
        case x :: Nil => Full(x)
        case _ => Failure("Too many policy servers for a Node: %s".format(nodeEntry.dn))
      }
      agentsName <- sequence(inventoryEntry.valuesFor(A_AGENTS_NAME).toSeq) { x =>
                        AgentType.fromValue(x) ?~! "Unknow value for agent type: '%s'. Authorized values are: %s".format(x, AgentType.allValues.mkString(", "))
                     }
      date <- nodeEntry.getAsGTime(A_OBJECT_CREATION_DATE) ?~! "Can not find mandatory attribute '%s' in entry".format(A_OBJECT_CREATION_DATE)
    } yield {
      // fetch the inventory datetime of the object
      val dateTime = inventoryEntry.getAsGTime(A_INVENTORY_DATE) match {
        case None => DateTime.now() 
        case Some(date) => date.dateTime 
      }
  
      NodeInfo(
          id,
          nodeEntry(A_NAME).getOrElse(""),
          nodeEntry(A_DESCRIPTION).getOrElse(""),
          inventoryEntry(A_HOSTNAME).getOrElse(""),
          //OsType.osTypeFromObjectClasses(inventoryEntry.valuesFor(A_OC)).map(_.toString).getOrElse(""),
          inventoryEntry(A_OS_FULL_NAME).getOrElse(""),
          inventoryEntry.valuesFor(A_LIST_OF_IP).toList, 
          dateTime,
          inventoryEntry(A_PKEYS).getOrElse(""),
          scala.collection.mutable.Seq() ++ agentsName,
          NodeId(policyServerId),
          //nodeDit.NODES.NODE.idFromDn(policyServerDN).getOrElse(error("Bad DN found for the policy server of Node: %s".format(nodeEntry.dn))),
          inventoryEntry(A_ROOT_USER).getOrElse(""),
          date.dateTime,
          nodeEntry.getAsBoolean(A_IS_BROKEN).getOrElse(false),
          nodeEntry.getAsBoolean(A_IS_SYSTEM).getOrElse(false),
          nodeEntry.isA(OC_POLICY_SERVER_NODE)
      )
    }
  }
  
  
  /**
   * Build the ActiveTechniqueCategoryId from the given DN
   */
  def dn2ActiveTechniqueCategoryId(dn:DN) : ActiveTechniqueCategoryId = {
    rudderDit.ACTIVE_TECHNIQUES_LIB.getCategoryIdValue(dn) match {
      case Full(value) => ActiveTechniqueCategoryId(value)
      case e:EmptyBox => throw new RuntimeException("The dn %s is not a valid Active Technique Category ID. Error was: %s".format(dn,e.toString))
    }
  }
  
  def dn2ActiveTechniqueId(dn:DN) : ActiveTechniqueId = {
    rudderDit.ACTIVE_TECHNIQUES_LIB.getActiveTechniqueId(dn) match {
      case Full(value) => ActiveTechniqueId(value)
      case e:EmptyBox => throw new RuntimeException("The dn %s is not a valid Active Technique ID. Error was: %s".format(dn,e.toString))
    }
  }
  
  /**
   * Build the Group Category Id from the given DN
   */
  def dn2NodeGroupCategoryId(dn:DN) : NodeGroupCategoryId = {
    rudderDit.GROUP.getCategoryIdValue(dn) match {
      case Full(value) => NodeGroupCategoryId(value)
      case e:EmptyBox => throw new RuntimeException("The dn %s is not a valid Node Group Category ID. Error was: %s".format(dn,e.toString))
    }
  }

  /**
   * Build the Node Group Category Id from the given DN
   */
  def dn2NodeGroupId(dn:DN) : NodeGroupId = {
    rudderDit.GROUP.getGroupId(dn) match {
      case Full(value) => NodeGroupId(value)
      case e:EmptyBox => throw new RuntimeException("The dn %s is not a valid Node Group ID. Error was: %s".format(dn,e.toString))
    }
  }

  def dn2LDAPRuleID(dn:DN) : DirectiveId = {
    rudderDit.ACTIVE_TECHNIQUES_LIB.getLDAPRuleID(dn) match {
      case Full(value) => DirectiveId(value)
      case e:EmptyBox => throw new RuntimeException("The dn %s is not a valid Directive ID. Error was: %s".format(dn,e.toString))
    }    
  }
  
  def dn2RuleId(dn:DN) : RuleId = {
    rudderDit.RULES.getRuleId(dn) match {
      case Full(value) => RuleId(value)
      case e:EmptyBox => throw new RuntimeException("The dn %s is not a valid Rule ID. Error was: %s".format(dn,e.toString))
    }
  }

  
  def nodeDn2OptNodeId(dn:DN) : Box[NodeId] = {
    val rdn = dn.getRDN
    if(!rdn.isMultiValued && rdn.hasAttribute(A_NODE_UUID)) {
      Full(NodeId(rdn.getAttributeValues()(0)))
    } else Failure("Bad RDN for a node, expecting attribute name '%s', got: %s".format(A_NODE_UUID,rdn))
  }
  
  //////////////////////////////    ActiveTechniqueCategory    //////////////////////////////
  
  
  /**
   * children and items are left empty
   */
  def entry2ActiveTechniqueCategory(e:LDAPEntry) : Box[ActiveTechniqueCategory] = {
    if(e.isA(OC_TECHNIQUE_CATEGORY)) {
      //OK, translate
      for {
        id <- e(A_TECHNIQUE_CATEGORY_UUID) ?~! "Missing required id (attribute name %s) in entry %s".format(A_TECHNIQUE_CATEGORY_UUID, e)
        name <- e(A_NAME) ?~! "Missing required name (attribute name %s) in entry %s".format(A_NAME, e)
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
    import net.liftweb.json.JsonParser._
    import net.liftweb.json.JsonAST.{JField,JString}
    
    parse(value) match {
      case JObject(fields) =>
        fields.collect { case JField(version, JString(date)) => 
          (TechniqueVersion(version) -> GeneralizedTime(date).dateTime) 
        }.toMap
      case _ => Map()
    }
  }
  
  def serializeAcceptations(dates:Map[TechniqueVersion,DateTime]) : JObject = {
    import net.liftweb.json.JsonDSL._
    ( JObject(List()) /: dates) { case (js, (version, date)) => 
      js ~ (version.toString -> GeneralizedTime(date).toString)
    }
  }
  
  
  /**
   * Build a ActiveTechnique from and LDAPEntry.
   * children directives are left empty
   */
  def entry2ActiveTechnique(e:LDAPEntry) : Box[ActiveTechnique] = {
    if(e.isA(OC_ACTIVE_TECHNIQUE)) {
      //OK, translate
      for {
        id <- e(A_ACTIVE_TECHNIQUE_UUID) ?~! "Missing required id (attribute name %s) in entry %s".format(A_ACTIVE_TECHNIQUE_UUID, e)
        refTechniqueUuid <- e(A_TECHNIQUE_UUID).map(x => TechniqueName(x)) ?~! "Missing required name (attribute name %s) in entry %s".format(A_TECHNIQUE_UUID, e)
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
  def entry2NodeGroupCategory(e:LDAPEntry) : Box[NodeGroupCategory] = {
    if(e.isA(OC_GROUP_CATEGORY)) {
      //OK, translate
      for {
        id <- e(A_GROUP_CATEGORY_UUID) ?~! "Missing required id (attribute name %s) in entry %s".format(A_GROUP_CATEGORY_UUID, e)
        name <- e(A_NAME) ?~! "Missing required name (attribute name %s) in entry %s".format(A_NAME, e)
        description = e(A_DESCRIPTION).getOrElse("")
        isSystem = e.getAsBoolean(A_IS_SYSTEM).getOrElse(false)
      } yield {
         NodeGroupCategory(NodeGroupCategoryId(id), name, description, Nil, Nil, isSystem)
      }
    } else Failure("The given entry is not of the expected ObjectClass '%s'. Entry details: %s".format(A_GROUP_CATEGORY_UUID, e))
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
   * Build a ActiveTechnique from and LDAPEntry.
   */
  def entry2NodeGroup(e:LDAPEntry) : Box[NodeGroup] = {   
    if(e.isA(OC_RUDDER_NODE_GROUP)) {
      //OK, translate
      for {
        id <- e(A_NODE_GROUP_UUID) ?~! "Missing required id (attribute name %s) in entry %s".format(A_NODE_GROUP_UUID, e)
        name <- e(A_NAME) ?~! "Missing required name (attribute name %s) in entry %s".format(A_NAME, e)
        query = e(A_QUERY_NODE_GROUP)
        nodeIds =  e.valuesFor(A_NODE_UUID).map(x => NodeId(x))
        query <- e(A_QUERY_NODE_GROUP) match {
          case None => Full(None)
          case Some(q) => cmdbQueryParser(q) match {
            case Full(x) => Full(Some(x))
            case eb:EmptyBox => 
              val error = eb ?~! "Error when parsing query for node group persisted at DN '%s' (name: '%s'), that seems to be an inconsistency. You should modify that group".format(
                  e.dn, name
              )
              logger.error(error)
              Full(None)
          }
        }
        isDynamic = e.getAsBoolean(A_IS_DYNAMIC).getOrElse(false)
        isEnabled = e.getAsBoolean(A_IS_ENABLED).getOrElse(false)
        isSystem = e.getAsBoolean(A_IS_SYSTEM).getOrElse(false)
        description = e(A_DESCRIPTION).getOrElse("")
      } yield {
         NodeGroup(NodeGroupId(id), name, description, query, isDynamic, nodeIds, isEnabled, isSystem)
      }
    } else Failure("The given entry is not of the expected ObjectClass '%s'. Entry details: %s".format(OC_TECHNIQUE_CATEGORY, e))
  }
  
  
  //////////////////////////////    Special Policy target info    //////////////////////////////

  def entry2RuleTargetInfo(e:LDAPEntry) : Box[RuleTargetInfo] = {
    for {
      target <- {
        if(e.isA(OC_RUDDER_NODE_GROUP)) {
          (e(A_NODE_GROUP_UUID).map(id => GroupTarget(NodeGroupId(id))) )?~! "Missing required id (attribute name %s) in entry %s".format(A_NODE_GROUP_UUID, e)
        } else if(e.isA(OC_SPECIAL_TARGET))
          for {
            targetString <- e(A_RULE_TARGET) ?~! "Missing required target id (attribute name %s) in entry %s".format(A_RULE_TARGET, e)
            target <- RuleTarget.unser(targetString) ?~! "Can not unserialize target, '%s' does not match any known target format".format(targetString)
          } yield {
            target
          } else Failure("The given entry is not of the expected ObjectClass '%s' or '%s'. Entry details: %s".format(OC_RUDDER_NODE_GROUP, OC_SPECIAL_TARGET, e))
      }
      name <-  e(A_NAME) ?~! "Missing required name (attribute name %s) in entry %s".format(A_NAME, e)
      description = e(A_DESCRIPTION).getOrElse("")
      isEnabled = e.getAsBoolean(A_IS_ENABLED).getOrElse(false)
      isSystem = e.getAsBoolean(A_IS_SYSTEM).getOrElse(false)
    } yield {
      RuleTargetInfo(target, name , description , isEnabled , isSystem)
    }
  }



  //////////////////////////////    Directive    //////////////////////////////

  def entry2Directive(e:LDAPEntry) : Box[Directive] = {
    
    if(e.isA(OC_DIRECTIVE)) {
      //OK, translate
      for {
        id <- e(A_DIRECTIVE_UUID) ?~! "Missing required attribute %s in entry %s".format(A_DIRECTIVE_UUID, e)
        s_version <- e(A_TECHNIQUE_VERSION) ?~! "Missing required attribute %s in entry %s".format(A_TECHNIQUE_VERSION, e)
        version <- tryo(TechniqueVersion(s_version))
        name = e(A_NAME).getOrElse(id)
        params = parsePolicyVariables(e.valuesFor(A_DIRECTIVE_VARIABLES).toSeq)
        shortDescription = e(A_DESCRIPTION).getOrElse("")
        longDescription = e(A_LONG_DESCRIPTION).getOrElse("")
        priority = e.getAsInt(A_PRIORITY).getOrElse(0)
        isEnabled = e.getAsBoolean(A_IS_ENABLED).getOrElse(false)
        isSystem = e.getAsBoolean(A_IS_SYSTEM).getOrElse(false)
      } yield {
        Directive(
            DirectiveId(id), version, params, name, 
            shortDescription,longDescription,priority, isEnabled, isSystem
        )
      }
    } else Failure("The given entry is not of the expected ObjectClass '%s'. Entry details: %s".format(OC_DIRECTIVE, e))
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
    entry
  }  
  
  //////////////////////////////    Rule    //////////////////////////////
  def entry2OptTarget(optValue:Option[String]) : Box[Option[RuleTarget]] = {
    (for {
      targetValue <- Box(optValue)
      target <- RuleTarget.unser(targetValue) ?~! "Bad parameter for a rule target: %s".format(targetValue)
    } yield {
      target
    }) match {
      case Full(t) => Full(Some(t))
      case Empty => Full(None)
      case f:Failure => f
    }
  }
  
  def entry2Rule(e:LDAPEntry) : Box[Rule] = {
    
    if(e.isA(OC_RULE)) {
      //OK, translate
      for {
        id <- e(A_RULE_UUID) ?~! "Missing required attribute %s in entry %s".format(A_RULE_UUID, e)
        serial <- e.getAsInt(A_SERIAL) ?~! "Missing required attribute %s in entry %s".format(A_SERIAL, e)
        target <- entry2OptTarget(e(A_RULE_TARGET))
      } yield {
        val piUuids = e.valuesFor(A_DIRECTIVE_UUID).map(x => DirectiveId(x))
        val name = e(A_NAME).getOrElse(id)
        val shortDescription = e(A_DESCRIPTION).getOrElse("")
        val longDescription = e(A_LONG_DESCRIPTION).getOrElse("")
        val isEnabled = e.getAsBoolean(A_IS_ENABLED).getOrElse(false)
        val isSystem = e.getAsBoolean(A_IS_SYSTEM).getOrElse(false)
        
        Rule(
            RuleId(id), name, serial, target, piUuids,
            shortDescription, longDescription, isEnabled, isSystem
        )
      }
    } else Failure("The given entry is not of the expected ObjectClass '%s'. Entry details: %s".format(OC_RULE, e))
  }
  
  
  /**
   * Map a rule to an LDAP Entry.
   * WARN: serial is NEVER mapped. 
   */
  def rule2Entry(rule:Rule) : LDAPEntry = {
    val entry = rudderDit.RULES.ruleModel(
        rule.id.value, 
        rule.name,
        rule.isEnabledStatus,
        rule.isSystem
    )
    
    rule.target.foreach { t => entry +=! (A_RULE_TARGET, t.target) }
    entry +=! (A_DIRECTIVE_UUID, rule.directiveIds.map( _.value).toSeq :_* )
    entry +=! (A_DESCRIPTION, rule.shortDescription)
    entry +=! (A_LONG_DESCRIPTION, rule.longDescription.toString)
    entry
  }  
  
}