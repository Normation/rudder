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
    val entry = nodeDit.NODES.NODE.nodeModel(node.id)
    entry +=! (A_NAME, node.name)
    entry +=! (A_DESCRIPTION, node.description)
    entry +=! (A_IS_BROKEN, node.isBroken.toLDAPString)
    entry +=! (A_IS_SYSTEM, node.isSystem.toLDAPString)
    entry
  }
   
  def policyServerNodeToEntry(node : PolicyServerNodeInfo) : LDAPEntry = {
  	val entry = nodeDit.NODES.NODE.policyServerNodeModel(node.id)
    entry +=! (A_NAME, node.name)
    entry +=! (A_DESCRIPTION, node.description)
    entry +=! (A_HOSTNAME, node.hostname)
    entry +=! (A_PKEYS, node.publicKey)
    entry +=! (A_LIST_OF_IP, node.ips:_*)
    entry +=! (A_INVENTORY_DATE, GeneralizedTime(node.inventoryDate).toString)
    entry +=! (A_ROOT_USER, node.localAdministratorAccountName)
    entry +=! (A_AGENTS_NAME, node.agentsName.map(x => x.toString):_*)
    entry +=! (A_NODE_POLICY_SERVER, node.policyServerId.value)
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
      checkIsAServer <- if(inventoryEntry.isA(OC_NODE)) Full("Ok") else Failure("Bad object class, need %s and found %s".format(OC_NODE,inventoryEntry.valuesFor(A_OC)))
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
          nodeEntry.getAsBoolean(A_IS_SYSTEM).getOrElse(false)
      )
    }
  }
  
  
  def convertEntryToPolicyServerNodeInfo(policyServerNodeEntry:LDAPEntry) : Box[PolicyServerNodeInfo] = {
    
    for {
      checkIsAPolicyServer <- if(policyServerNodeEntry.isA(OC_POLICY_SERVER_NODE)) Full("OK")
        else Failure("Bad object class, need %s and found %s".format(OC_POLICY_SERVER_NODE,policyServerNodeEntry.valuesFor(A_OC)))
      id <- nodeDit.NODES.NODE.idFromDn(policyServerNodeEntry.dn) ?~! "Bad DN found for a Node: %s".format(policyServerNodeEntry.dn)
      psId <- policyServerNodeEntry(A_NODE_POLICY_SERVER) ?~! "No policy server found for policy server %s".format(policyServerNodeEntry.dn)
      agentsName <- sequence(policyServerNodeEntry.valuesFor(A_AGENTS_NAME).toSeq) { x =>
                        AgentType.fromValue(x) ?~! "Unknow value for agent type: '%s'. Authorized values are: %s".format(x, AgentType.allValues.mkString(", "))
                     }
      date <- policyServerNodeEntry.getAsGTime(A_OBJECT_CREATION_DATE) ?~! "Can not find mandatory attribute '%s' in entry".format(A_OBJECT_CREATION_DATE)
    } yield {
    	// fetch the datetime for the inventory
      val dateTime = policyServerNodeEntry.getAsGTime(A_INVENTORY_DATE) match {
        case None => DateTime.now() 
        case Some(date) =>date.dateTime 
      }
  	
    	PolicyServerNodeInfo(
          id,
          policyServerNodeEntry(A_NAME).getOrElse(""),
          policyServerNodeEntry(A_DESCRIPTION).getOrElse(""),
          policyServerNodeEntry(A_HOSTNAME).getOrElse(""),
          policyServerNodeEntry.valuesFor(A_LIST_OF_IP).toList, 
          dateTime,
          policyServerNodeEntry(A_PKEYS).getOrElse(""),
          scala.collection.mutable.Seq() ++ agentsName,
          NodeId(psId),
          policyServerNodeEntry(A_ROOT_USER).getOrElse(""),
          date.dateTime,
          policyServerNodeEntry.getAsBoolean(A_IS_BROKEN).getOrElse(false),
          policyServerNodeEntry.getAsBoolean(A_IS_SYSTEM).getOrElse(false)
      )
    }
  }

  /**
   * Build the UserPolicyTemplateCategoryId from the given DN
   */
  def dn2UserPolicyTemplateCategoryId(dn:DN) : UserPolicyTemplateCategoryId = {
    rudderDit.POLICY_TEMPLATE_LIB.getCategoryIdValue(dn) match {
      case Full(value) => UserPolicyTemplateCategoryId(value)
      case e:EmptyBox => throw new RuntimeException("The dn %s is not a valid User Policy Template Category ID. Error was: %s".format(dn,e.toString))
    }
  }
  
  def dn2UserPolicyTemplateId(dn:DN) : UserPolicyTemplateId = {
    rudderDit.POLICY_TEMPLATE_LIB.getUserPolicyTemplateId(dn) match {
      case Full(value) => UserPolicyTemplateId(value)
      case e:EmptyBox => throw new RuntimeException("The dn %s is not a valid User Policy Template ID. Error was: %s".format(dn,e.toString))
    }
  }
  
  /**
   * Build the Group Category Id from the given DN
   */
  def dn2NodeGroupCategoryId(dn:DN) : NodeGroupCategoryId = {
    rudderDit.GROUP.getCategoryIdValue(dn) match {
      case Full(value) => NodeGroupCategoryId(value)
      case e:EmptyBox => throw new RuntimeException("The dn %s is not a valid Server Group Category ID. Error was: %s".format(dn,e.toString))
    }
  }

  /**
   * Build the Server Group Category Id from the given DN
   */
  def dn2NodeGroupId(dn:DN) : NodeGroupId = {
    rudderDit.GROUP.getGroupId(dn) match {
      case Full(value) => NodeGroupId(value)
      case e:EmptyBox => throw new RuntimeException("The dn %s is not a valid Server Group ID. Error was: %s".format(dn,e.toString))
    }
  }

  def dn2LDAPConfigurationRuleID(dn:DN) : PolicyInstanceId = {
    rudderDit.POLICY_TEMPLATE_LIB.getLDAPConfigurationRuleID(dn) match {
      case Full(value) => PolicyInstanceId(value)
      case e:EmptyBox => throw new RuntimeException("The dn %s is not a valid User Policy Instance ID. Error was: %s".format(dn,e.toString))
    }    
  }
  
  def dn2ConfigurationRuleId(dn:DN) : ConfigurationRuleId = {
    rudderDit.CONFIG_RULE.getConfigurationRuleId(dn) match {
      case Full(value) => ConfigurationRuleId(value)
      case e:EmptyBox => throw new RuntimeException("The dn %s is not a valid Configuration Rule ID. Error was: %s".format(dn,e.toString))
    }
  }

  
  def nodeDn2OptNodeId(dn:DN) : Box[NodeId] = {
    val rdn = dn.getRDN
    if(!rdn.isMultiValued && rdn.hasAttribute(A_NODE_UUID)) {
      Full(NodeId(rdn.getAttributeValues()(0)))
    } else Failure("Bad RDN for a node, expecting attribute name '%s', got: %s".format(A_NODE_UUID,rdn))
  }
  
  //////////////////////////////    UserPolicyTemplateCategory    //////////////////////////////
  
  
  /**
   * children and items are left empty
   */
  def entry2UserPolicyTemplateCategory(e:LDAPEntry) : Box[UserPolicyTemplateCategory] = {
    if(e.isA(OC_CATEGORY)) {
      //OK, translate
      for {
        id <- e(A_CATEGORY_UUID) ?~! "Missing required id (attribute name %s) in entry %s".format(A_CATEGORY_UUID, e)
        name <- e(A_NAME) ?~! "Missing required name (attribute name %s) in entry %s".format(A_NAME, e)
        description = e(A_DESCRIPTION).getOrElse("")
        isSystem = e.getAsBoolean(A_IS_SYSTEM).getOrElse(false) 
      } yield {
         UserPolicyTemplateCategory(UserPolicyTemplateCategoryId(id), name, description, Nil, Nil, isSystem)
      }
    } else Failure("The given entry is not of the expected ObjectClass '%s'. Entry details: %s".format(OC_CATEGORY, e))
  }
  
  /**
   * children and items are ignored
   */
  def userPolicyTemplateCategory2ldap(category:UserPolicyTemplateCategory, parentDN:DN) = {
    val entry = rudderDit.POLICY_TEMPLATE_LIB.userPolicyTemplateCategoryModel(category.id.value, parentDN)
    entry +=! (A_NAME, category.name)
    entry +=! (A_DESCRIPTION, category.description)
    entry +=! (A_IS_SYSTEM, category.isSystem.toLDAPString)
    entry
  }
  
  //////////////////////////////    UserPolicyTemplate    //////////////////////////////

  //two utilities to serialize / deserialize Map[PolicyVersion,DateTime]
  def unserializeAcceptations(value:String):Map[PolicyVersion, DateTime] = {
    import net.liftweb.json.JsonParser._
    import net.liftweb.json.JsonAST.{JField,JString}
    
    parse(value) match {
      case JObject(fields) =>
        fields.collect { case JField(version, JString(date)) => 
          (PolicyVersion(version) -> GeneralizedTime(date).dateTime) 
        }.toMap
      case _ => Map()
    }
  }
  
  def serializeAcceptations(dates:Map[PolicyVersion,DateTime]) : JObject = {
    import net.liftweb.json.JsonDSL._
    ( JObject(List()) /: dates) { case (js, (version, date)) => 
      js ~ (version.toString -> GeneralizedTime(date).toString)
    }
  }
  
  
  /**
   * Build a UserPolicyTemplate from and LDAPEntry.
   * children policy instances are left empty
   */
  def entry2UserPolicyTemplate(e:LDAPEntry) : Box[UserPolicyTemplate] = {
    if(e.isA(OC_USER_POLICY_TEMPLATE)) {
      //OK, translate
      for {
        id <- e(A_USER_POLICY_TEMPLATE_UUID) ?~! "Missing required id (attribute name %s) in entry %s".format(A_USER_POLICY_TEMPLATE_UUID, e)
        refPolicyTemplateUuid <- e(A_REFERENCE_POLICY_TEMPLATE_UUID).map(x => PolicyPackageName(x)) ?~! "Missing required name (attribute name %s) in entry %s".format(A_REFERENCE_POLICY_TEMPLATE_UUID, e)
        isActivated = e.getAsBoolean(A_IS_ACTIVATED).getOrElse(false)
        isSystem = e.getAsBoolean(A_IS_SYSTEM).getOrElse(false)
        acceptationDatetimes = e(A_ACCEPTATION_DATETIME).map(unserializeAcceptations(_)).getOrElse(Map())
      } yield {
         UserPolicyTemplate(UserPolicyTemplateId(id), refPolicyTemplateUuid, acceptationDatetimes, Nil, isActivated, isSystem)
      }
    } else Failure("The given entry is not of the expected ObjectClass '%s'. Entry details: %s".format(OC_CATEGORY, e))
  }
  
  def userPolicyTemplate2Entry(upt:UserPolicyTemplate, parentDN:DN) : LDAPEntry = {
    val entry = rudderDit.POLICY_TEMPLATE_LIB.userPolicyTemplateModel(
        upt.id.value, 
        parentDN,
        upt.referencePolicyTemplateName,
        serializeAcceptations(upt.acceptationDatetimes),
        upt.isActivated,
        upt.isSystem
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
  
  ////////////////////////////////// Server Group //////////////////////////////////
  
   /**
   * Build a UserPolicyTemplate from and LDAPEntry.
   */
  def entry2NodeGroup(e:LDAPEntry) : Box[NodeGroup] = {   
    if(e.isA(OC_RUDDER_NODE_GROUP)) {
      //OK, translate
      for {
        id <- e(A_NODE_GROUP_UUID) ?~! "Missing required id (attribute name %s) in entry %s".format(A_NODE_GROUP_UUID, e)
        name <- e(A_NAME) ?~! "Missing required name (attribute name %s) in entry %s".format(A_NAME, e)
        query = e(A_QUERY_NODE_GROUP)
        serverIds =  e.valuesFor(A_NODE_UUID).map(x => NodeId(x))
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
        isActivated = e.getAsBoolean(A_IS_ACTIVATED).getOrElse(false)
        isSystem = e.getAsBoolean(A_IS_SYSTEM).getOrElse(false)
        description = e(A_DESCRIPTION).getOrElse("")
      } yield {
         NodeGroup(NodeGroupId(id), name, description, query, isDynamic, serverIds, isActivated, isSystem)
      }
    } else Failure("The given entry is not of the expected ObjectClass '%s'. Entry details: %s".format(OC_CATEGORY, e))
  }
  
  
  //////////////////////////////    Special Policy target info    //////////////////////////////

  def entry2PolicyInstanceTargetInfo(e:LDAPEntry) : Box[PolicyInstanceTargetInfo] = {
    for {
      target <- {
        if(e.isA(OC_RUDDER_NODE_GROUP)) {
          (e(A_NODE_GROUP_UUID).map(id => GroupTarget(NodeGroupId(id))) )?~! "Missing required id (attribute name %s) in entry %s".format(A_NODE_GROUP_UUID, e)
        } else if(e.isA(OC_SPECIAL_TARGET))
          for {
            targetString <- e(A_POLICY_TARGET) ?~! "Missing required target id (attribute name %s) in entry %s".format(A_POLICY_TARGET, e)
            target <- PolicyInstanceTarget.unser(targetString) ?~! "Can not unserialize target, '%s' does not match any known target format".format(targetString)
          } yield {
            target
          } else Failure("The given entry is not of the expected ObjectClass '%s' or '%s'. Entry details: %s".format(OC_RUDDER_NODE_GROUP, OC_SPECIAL_TARGET, e))
      }
      name <-  e(A_NAME) ?~! "Missing required name (attribute name %s) in entry %s".format(A_NAME, e)
      description = e(A_DESCRIPTION).getOrElse("")
      isActivated = e.getAsBoolean(A_IS_ACTIVATED).getOrElse(false)
      isSystem = e.getAsBoolean(A_IS_SYSTEM).getOrElse(false)
    } yield {
      PolicyInstanceTargetInfo(target, name , description , isActivated , isSystem)
    }
  }



  //////////////////////////////    PolicyInstance    //////////////////////////////

  def entry2PolicyInstance(e:LDAPEntry) : Box[PolicyInstance] = {
    
    if(e.isA(OC_WBPI)) {
      //OK, translate
      for {
        id <- e(A_WBPI_UUID) ?~! "Missing required attribute %s in entry %s".format(A_WBPI_UUID, e)
        s_version <- e(A_REFERENCE_POLICY_TEMPLATE_VERSION) ?~! "Missing required attribute %s in entry %s".format(A_REFERENCE_POLICY_TEMPLATE_VERSION, e)
        version <- tryo(PolicyVersion(s_version))
        name = e(A_NAME).getOrElse(id)
        params = parsePolicyVariables(e.valuesFor(A_POLICY_VARIABLES).toSeq)
        shortDescription = e(A_DESCRIPTION).getOrElse("")
        longDescription = e(A_LONG_DESCRIPTION).getOrElse("")
        priority = e.getAsInt(A_PRIORITY).getOrElse(0)
        isActivated = e.getAsBoolean(A_IS_ACTIVATED).getOrElse(false)
        isSystem = e.getAsBoolean(A_IS_SYSTEM).getOrElse(false)
      } yield {
        PolicyInstance(
            PolicyInstanceId(id), version, params, name, 
            shortDescription,longDescription,priority, isActivated, isSystem
        )
      }
    } else Failure("The given entry is not of the expected ObjectClass '%s'. Entry details: %s".format(OC_WBPI, e))
  }
  
  def userPolicyInstance2Entry(pi:PolicyInstance, parentDN:DN) : LDAPEntry = {
    val entry = rudderDit.POLICY_TEMPLATE_LIB.policyInstanceModel(
        pi.id.value, 
        pi.policyTemplateVersion,
        parentDN
    )
    
    entry +=! (A_POLICY_VARIABLES, policyVariableToSeq(pi.parameters):_*)
    entry +=! (A_NAME, pi.name)
    entry +=! (A_DESCRIPTION, pi.shortDescription)
    entry +=! (A_LONG_DESCRIPTION, pi.longDescription.toString)
    entry +=! (A_PRIORITY, pi.priority.toString)
    entry +=! (A_IS_ACTIVATED, pi.isActivated.toLDAPString)
    entry +=! (A_IS_SYSTEM, pi.isSystem.toLDAPString)
    entry
  }  
  
  //////////////////////////////    ConfigurationRule    //////////////////////////////
  def entry2OptTarget(optValue:Option[String]) : Box[Option[PolicyInstanceTarget]] = {
    (for {
      targetValue <- Box(optValue)
      target <- PolicyInstanceTarget.unser(targetValue) ?~! "Bad parameter for a configuration rule target: %s".format(targetValue)
    } yield {
      target
    }) match {
      case Full(t) => Full(Some(t))
      case Empty => Full(None)
      case f:Failure => f
    }
  }
  
  def entry2ConfigurationRule(e:LDAPEntry) : Box[ConfigurationRule] = {
    
    if(e.isA(OC_CONFIGURATION_RULE)) {
      //OK, translate
      for {
        id <- e(A_CONFIGURATION_RULE_UUID) ?~! "Missing required attribute %s in entry %s".format(A_CONFIGURATION_RULE_UUID, e)
        serial <- e.getAsInt(A_SERIAL) ?~! "Missing required attribute %s in entry %s".format(A_SERIAL, e)
        target <- entry2OptTarget(e(A_POLICY_TARGET))
      } yield {
        val piUuids = e.valuesFor(A_WBPI_UUID).map(x => PolicyInstanceId(x))
        val name = e(A_NAME).getOrElse(id)
        val shortDescription = e(A_DESCRIPTION).getOrElse("")
        val longDescription = e(A_LONG_DESCRIPTION).getOrElse("")
        val isActivated = e.getAsBoolean(A_IS_ACTIVATED).getOrElse(false)
        val isSystem = e.getAsBoolean(A_IS_SYSTEM).getOrElse(false)
        
        ConfigurationRule(
            ConfigurationRuleId(id), name, serial, target, piUuids,
            shortDescription, longDescription, isActivated, isSystem
        )
      }
    } else Failure("The given entry is not of the expected ObjectClass '%s'. Entry details: %s".format(OC_CONFIGURATION_RULE, e))
  }
  
  
  /**
   * Map a configuration rule to an LDAP Entry.
   * WARN: serial is NEVER mapped. 
   */
  def configurationRule2Entry(cr:ConfigurationRule) : LDAPEntry = {
    val entry = rudderDit.CONFIG_RULE.configurationRuleModel(
        cr.id.value, 
        cr.name,
        cr.isActivatedStatus,
        cr.isSystem
    )
    
    cr.target.foreach { t => entry +=! (A_POLICY_TARGET, t.target) }
    entry +=! (A_WBPI_UUID, cr.policyInstanceIds.map( _.value).toSeq :_* )
    entry +=! (A_DESCRIPTION, cr.shortDescription)
    entry +=! (A_LONG_DESCRIPTION, cr.longDescription.toString)
    entry
  }  
  
}