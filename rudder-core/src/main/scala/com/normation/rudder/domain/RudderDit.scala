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

package com.normation.rudder.domain

import com.normation.rudder.domain.policies._
import com.normation.rudder.domain.archives._
import com.normation.rudder.domain.queries.CriterionLine
import com.normation.rudder.domain.queries.Query
import com.normation.rudder.domain.queries.GroupCategoryUuid
import com.normation.rudder.domain.queries.QueryUuid
import com.unboundid.ldap.sdk._
import com.normation.ldap.sdk._
import com.normation.inventory.ldap.core._
import LDAPConstants._
import com.normation.utils.Utils.nonEmpty
import com.normation.inventory.domain._
import com.normation.rudder.domain.RudderLDAPConstants._
import net.liftweb.common._
import net.liftweb.json._
import JsonDSL._
import com.normation.cfclerk.domain._
import org.joda.time.DateTime 

class CATEGORY(
    uuid: String,
    parentDN : DN, 
    name : String = "",
    description : String = "",
    isSystem : Boolean = false,
    objectClass : String,
    objectClassUuid : String
)(implicit dit:AbstractDit) extends ENTRY1(objectClassUuid, uuid) {
    
  lazy val rdn : RDN = this.rdn(this.rdnValue._1)
  lazy val dn = new DN(rdn, parentDN)
  def model() : LDAPEntry = {
    val mod = LDAPEntry(dn)
    mod +=! (A_OC, OC.objectClassNames(objectClass).toSeq:_*)
    mod +=! (A_NAME, name)
    mod +=! (A_DESCRIPTION, description)
    mod +=! (A_IS_SYSTEM, isSystem.toLDAPString)
    mod
  }
}



/**
 * 
 * Store the DIT (LDAP tree structure) for Rudder information.
 * 
 * That allows mainly :
 * - to access the DIT programmatically, and so to now what is where for
 *   the mains branches ;
 * - to define what object classes are allowed at each level, thanks to
 *   templates (model) of expected entries
 *
 */
class RudderDit(val BASE_DN:DN) extends AbstractDit {
  dit => 
  implicit val DIT = dit
  
  
  val POLICY_TEMPLATE_LIB = new CATEGORY(
      uuid = "UserPolicyTemplateLibraryRoot",
      parentDN = BASE_DN,
      name = "User policy template library's root",
      description = "This is the root category for the user library of Policy Templates. It contains subcategories and policy templates",
      isSystem = true,
      objectClass = OC_CATEGORY,
      objectClassUuid = A_CATEGORY_UUID
  ) {
    uptLib => 
    
    //check for the presence of that entry at bootstrap
    dit.register(uptLib.model)

    
     
    /**
     * From a DN of a category, return the value of the rdn (uuid)
     */
    def getCategoryIdValue(dn:DN) = singleRdnValue(dn,uptLib.rdnAttribute._1)
    
    /**
     * From a DN of a user policy template, return the value of the rdn (uuid)
     */
    def getUserPolicyTemplateId(dn:DN) : Box[String] = singleRdnValue(dn,A_USER_POLICY_TEMPLATE_UUID)
    
    
    def getLDAPConfigurationRuleID(dn:DN) : Box[String] = singleRdnValue(dn,A_WBPI_UUID)
    
    /**
     * Return a new sub category 
     */
    def userPolicyTemplateCategoryModel(uuid:String, parentDN:DN) : LDAPEntry = new CATEGORY(uuid, parentDN,objectClass = OC_CATEGORY,
      objectClassUuid = A_CATEGORY_UUID).model
    
    /**
     * Create a new user policy template entry
     */
    def userPolicyTemplateModel(
        uuid:String
      , parentDN:DN
      , referencePolicyTemplateName:PolicyPackageName
      , acceptationDateTimes:JObject
      , isActivated:Boolean
      , isSystem : Boolean
    ) : LDAPEntry = {
      val mod = LDAPEntry(new DN(new RDN(A_USER_POLICY_TEMPLATE_UUID,uuid),parentDN))
      mod +=! (A_OC, OC.objectClassNames(OC_USER_POLICY_TEMPLATE).toSeq:_*)
      mod +=! (A_REFERENCE_POLICY_TEMPLATE_UUID, referencePolicyTemplateName.value)
      mod +=! (A_IS_ACTIVATED, isActivated.toLDAPString)
      mod +=! (A_IS_SYSTEM, isSystem.toLDAPString)
      mod +=! (A_ACCEPTATION_DATETIME, Printer.compact(JsonAST.render(acceptationDateTimes)))
      mod
    }
    
    def policyInstanceModel(uuid:String, referencePolicyTemplateVersion:PolicyVersion, parentDN:DN) : LDAPEntry = {
      val mod = LDAPEntry(new DN(new RDN(A_WBPI_UUID,uuid),parentDN))
      mod +=! (A_REFERENCE_POLICY_TEMPLATE_VERSION, referencePolicyTemplateVersion.toString)
      mod +=! (A_OC, OC.objectClassNames(OC_WBPI).toSeq:_*)
      mod
    }
    
  }
  
  val CONFIG_RULE = new OU("Configuration Rules", BASE_DN) {
    crs =>
    //check for the presence of that entry at bootstrap
    dit.register(crs.model)
    
    def getConfigurationRuleId(dn:DN) : Box[String] = singleRdnValue(dn,A_CONFIGURATION_RULE_UUID)
    
    def configRuleDN(uuid:String) = new DN(new RDN(A_CONFIGURATION_RULE_UUID, uuid), crs.dn)

    def configurationRuleModel(
      uuid:String,
      name:String,
      isActivated : Boolean,
      isSystem : Boolean
    ) : LDAPEntry = {
      val mod = LDAPEntry(configRuleDN(uuid))
      mod +=! (A_OC,OC.objectClassNames(OC_CONFIGURATION_RULE).toSeq:_*)
      mod +=! (A_NAME, name)
      mod +=! (A_IS_ACTIVATED, isActivated.toLDAPString)
      mod +=! (A_IS_SYSTEM, isSystem.toLDAPString)
      mod
    }
    
  }
  
  
  val GROUP = new CATEGORY(
      uuid = "GroupRoot",
      parentDN = BASE_DN,
      name = "Root of the group and group categories",
      description = "This is the root category for the groups (both dynamic and static) and group categories",
      isSystem = true,
      objectClass = OC_GROUP_CATEGORY,
      objectClassUuid = A_GROUP_CATEGORY_UUID
  ) {
    group => 
    
    //check for the presence of that entry at bootstrap
    dit.register(group.model)

    
     
    /**
     * From a DN of a category, return the value of the rdn (uuid)
     */
    def getCategoryIdValue(dn:DN) = singleRdnValue(dn,group.rdnAttribute._1)
    
    /**
     * From a DN of a group, return the value of the rdn (uuid)
     */
    def getGroupId(dn:DN) : Box[String] = singleRdnValue(dn,A_NODE_GROUP_UUID)
    
    /**
     * Return a new sub category 
     */
    def groupCategoryModel(uuid:String, parentDN:DN) : LDAPEntry = new CATEGORY(uuid, parentDN, objectClass = OC_GROUP_CATEGORY,
      objectClassUuid = A_GROUP_CATEGORY_UUID).model
    
    def groupDN(groupId:String, parentDN:DN) : DN = new DN(new RDN(A_NODE_GROUP_UUID,groupId),parentDN)   
      
    /**
     * Create a new group entry
     */
    def groupModel(uuid:String, parentDN:DN, name:String, description : String, query: Option[Query], isDynamic : Boolean, srvList : Set[NodeId], isActivated : Boolean, isSystem : Boolean = false) : LDAPEntry = {
      val mod = LDAPEntry(group.groupDN(uuid, parentDN))
      mod +=! (A_OC, OC.objectClassNames(OC_RUDDER_NODE_GROUP).toSeq:_*)
      mod +=! (A_NAME, name)
      mod +=! (A_DESCRIPTION, description)
      mod +=! (A_IS_ACTIVATED, isActivated.toLDAPString)
      mod +=! (A_IS_SYSTEM, isSystem.toLDAPString)
      mod +=! (A_IS_DYNAMIC, isDynamic.toLDAPString)
      mod +=! (A_NODE_UUID, srvList.map(x => x.value).toSeq:_*)

      query match {
        case None => // No query to add. Maybe we'd like to enforce that it is not activated
        case Some(q) =>
          mod +=! (A_QUERY_NODE_GROUP, q.toJSONString)
      }
      mod
    }
    
    /**
     * Group for system targets
     */
    val SYSTEM = new CATEGORY(
      uuid = "SystemGroups",
      parentDN = group.dn,
      name = "Category for system groups and targets",
      description = "This category is the container of all system targets, both groups and other special ones",
      isSystem = true,
      objectClass = OC_GROUP_CATEGORY,
      objectClassUuid = A_GROUP_CATEGORY_UUID
  ) {
      system =>
      
      //check for the presence of that entry at bootstrap
      dit.register(system.model)
      
      def targetDN(target:PolicyInstanceTarget) : DN = target match {
        case GroupTarget(groupId) => group.groupDN(groupId.value, system.dn)
        case t => new DN(new RDN(A_POLICY_TARGET, target.target), system.dn)
      }
        
      
    }
  }
  
  /**
   * That branch contains definition for Rudder server type.
   */
  val RUDDER_NODES = new OU("Nodes Configuration", BASE_DN) {
    servers =>
 
    /**
     * There is two actual implementations of Rudder server, which 
     * differ only slightly in their identification.
     */  
    val SERVER = new ENTRY1(A_NODE_UUID) {
      server => 
      
      //get id from dn
      def idFromDn(dn:DN) : Option[NodeId] = buildId(dn,servers.dn,{x:String => NodeId(x)})
      
      //build the dn from an UUID
      def dn(uuid:String) = new DN(this.rdn(uuid),servers.dn)
      
      def rootPolicyServerModel(uuid:NodeId) : LDAPEntry = {
        val mod = LDAPEntry(this.dn(uuid.value))
        mod +=! (A_OC, OC.objectClassNames(OC_ROOT_POLICY_SERVER).toSeq:_*)
        mod
      }
      
      def nodeConfigurationModel(uuid:NodeId) : LDAPEntry = {
        val mod = LDAPEntry(this.dn(uuid.value))
        mod +=! (A_OC, OC.objectClassNames(OC_RUDDER_SERVER).toSeq:_*)
        mod
      }     
     
     
      /**
       * Policy instances for a server.
       * There is both current and target policy instances,
       * they only differ on the objectType
       */
      val POLICY_INSTANCE = new ENTRY1(A_POLICY_INSTANCE_UUID) {
        def dn(uuid:String,serverDN:DN) : DN = {
          require(nonEmpty(uuid), "A policy instance UUID can not be empty")
          require( !serverDN.isNullDN , "The parent (server) DN of a Server Role can not be empty")
          new DN(this.rdn(uuid),serverDN)
        }
        
        def model(policyInstanceUUID:String,serverDN:DN) : LDAPEntry = {
          val mod = LDAPEntry(this.dn(policyInstanceUUID,serverDN))
          mod +=! (A_OC, OC.objectClassNames(OC_CR_POLICY_INSTANCE).toSeq:_*)
          mod
        }     
      } //end POLICY_INSTANCE
      
      val TARGET_POLICY_INSTANCE = new ENTRY1(A_TARGET_POLICY_INSTANCE_UUID) {
        def dn(uuid:String,serverDN:DN) : DN = {
          require(nonEmpty(uuid), "A policy instance UUID can not be empty")
          require( !serverDN.isNullDN , "The parent (server) DN of a Server Role can not be empty")
          new DN(this.rdn(uuid),serverDN)
        }
        
       def model(policyInstanceUUID:String,serverDN:DN) : LDAPEntry = {
          val mod = LDAPEntry(this.dn(policyInstanceUUID,serverDN))
          mod +=! (A_OC, OC.objectClassNames(OC_TARGET_CR_POLICY_INSTANCE).toSeq:_*)
          mod
        }
        
      } //end TARGET_POLICY_INSTANCE
      
    } //end SERVER
    
  } //end RUDDER_NODES
  
//  val POLICY_INSTANCES = new OU("Policy Instances", BASE_DN) {
//    policies =>
//    
//    val POLICY_INSTANCE = new ENTRY1(A_POLICY_INSTANCE_UUID) {
//      policy => 
//      
//      def model(id:Option[LDAPConfigurationRuleUUID]) : LDAPEntry = {
//        val mod = id match {
//          case None =>  LDAPEntry(None,Some(policies.dn))
//          case Some(u) => LDAPEntry(LDAPLDAPConfigurationRuleID(u,policies.dn).dn)
//        }
//        mod +=! (A_OC, OC.objectClassNames(OC_POLICY_INSTANCE).toSeq:_*)
//        mod
//      }
//    }
//  }
  
  val ARCHIVES = new OU("Archives", BASE_DN) {
    archives =>
    //check for the presence of that entry at bootstrap
    dit.register(archives.model)
    
    def configurationRuleModel(
      crArchiveId : CRArchiveId
    ) : LDAPEntry = {
      (new OU("ConfigurationRules-" + crArchiveId.value, archives.dn)).model
    }
  
  }
  
  
  private def singleRdnValue(dn:DN, expectedAttributeName:String) : Box[String] = {
      val rdn = dn.getRDN
      if(rdn.isMultiValued) Failure("The given RDN is multivalued, what is not expected for it: %s".format(rdn))
      else if(rdn.hasAttribute(expectedAttributeName)) {
        Full(rdn.getAttributeValues()(0))
      } else {
        Failure("The given DN does not seems to be a valid one for a category. Was expecting RDN attribute name '%s' and got '%s'".format(expectedAttributeName, rdn.getAttributeNames()(0))) 
      }
    }

}
