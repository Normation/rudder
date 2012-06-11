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
import com.normation.rudder.repository.ActiveTechniqueLibraryArchiveId
import com.normation.rudder.repository.NodeGroupLibraryArchiveId

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
  
  /**
   * Create a new category for the active technique library
   */
  def activeTechniqueCategory(
      uuid       : String
    , parentDN   : DN
    , name       : String = ""
    , description: String = ""
  ) : CATEGORY = {
    new CATEGORY(
        uuid            = uuid
      , name            = name
      , description     = description
      , parentDN        = parentDN
      , objectClass     = OC_TECHNIQUE_CATEGORY
      , objectClassUuid = A_TECHNIQUE_CATEGORY_UUID
    )
  }
  
  def groupCategory(
      uuid       : String
    , parentDN   : DN
    , name       : String = ""
    , description: String = ""
  ) : CATEGORY = {
    new CATEGORY(
        uuid            = uuid
      , name            = name
      , description     = description
      , parentDN        = parentDN
      , objectClass = OC_GROUP_CATEGORY
      , objectClassUuid = A_GROUP_CATEGORY_UUID
    )
  }
  
  
  //here, we can't use activeTechniqueCategory because we want a subclass
  val ACTIVE_TECHNIQUES_LIB = new CATEGORY(
      uuid = "Active Techniques",
      parentDN = BASE_DN,
      name = "Root of active techniques's library",
      description = "This is the root category for active techniques. It contains subcategories, actives techniques and directives",
      isSystem = true,
      objectClass = OC_TECHNIQUE_CATEGORY,
      objectClassUuid = A_TECHNIQUE_CATEGORY_UUID
  ) {
    activeTechniques => 
    
    //check for the presence of that entry at bootstrap
    dit.register(activeTechniques.model)

    
     
    /**
     * From a DN of a category, return the value of the rdn (uuid)
     */
    def getCategoryIdValue(dn:DN) = singleRdnValue(dn,activeTechniques.rdnAttribute._1)
    
    /**
     * From a DN of an active technique, return the value of the rdn (uuid)
     */
    def getActiveTechniqueId(dn:DN) : Box[String] = singleRdnValue(dn,A_ACTIVE_TECHNIQUE_UUID)
    
    
    def getLDAPRuleID(dn:DN) : Box[String] = singleRdnValue(dn,A_DIRECTIVE_UUID)
    
    /**
     * Return a new sub category 
     */
    def activeTechniqueCategoryModel(uuid:String, parentDN:DN) : LDAPEntry = activeTechniqueCategory(uuid, parentDN).model
    
    /**
     * Create a new active technique entry
     */
    def activeTechniqueModel(
        uuid:String
      , parentDN:DN
      , techniqueName:TechniqueName
      , acceptationDateTimes:JObject
      , isEnabled:Boolean
      , isSystem : Boolean
    ) : LDAPEntry = {
      val mod = LDAPEntry(new DN(new RDN(A_ACTIVE_TECHNIQUE_UUID,uuid),parentDN))
      mod +=! (A_OC, OC.objectClassNames(OC_ACTIVE_TECHNIQUE).toSeq:_*)
      mod +=! (A_TECHNIQUE_UUID, techniqueName.value)
      mod +=! (A_IS_ENABLED, isEnabled.toLDAPString)
      mod +=! (A_IS_SYSTEM, isSystem.toLDAPString)
      mod +=! (A_ACCEPTATION_DATETIME, Printer.compact(JsonAST.render(acceptationDateTimes)))
      mod
    }
    
    def directiveModel(uuid:String, techniqueVersion:TechniqueVersion, parentDN:DN) : LDAPEntry = {
      val mod = LDAPEntry(new DN(new RDN(A_DIRECTIVE_UUID,uuid),parentDN))
      mod +=! (A_TECHNIQUE_VERSION, techniqueVersion.toString)
      mod +=! (A_OC, OC.objectClassNames(OC_DIRECTIVE).toSeq:_*)
      mod
    }
    
  }
  
  val RULES = new OU("Rules", BASE_DN) {
    rules =>
    //check for the presence of that entry at bootstrap
    dit.register(rules.model)
    
    def getRuleId(dn:DN) : Box[String] = singleRdnValue(dn,A_RULE_UUID)
    
    def configRuleDN(uuid:String) = new DN(new RDN(A_RULE_UUID, uuid), rules.dn)

    def ruleModel(
      uuid:String,
      name:String,
      isEnabled : Boolean,
      isSystem : Boolean
    ) : LDAPEntry = {
      val mod = LDAPEntry(configRuleDN(uuid))
      mod +=! (A_OC,OC.objectClassNames(OC_RULE).toSeq:_*)
      mod +=! (A_NAME, name)
      mod +=! (A_IS_ENABLED, isEnabled.toLDAPString)
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
    def groupCategoryModel(uuid:String, parentDN:DN) : LDAPEntry = groupCategory(uuid, parentDN).model
    
    def groupDN(groupId:String, parentDN:DN) : DN = new DN(new RDN(A_NODE_GROUP_UUID,groupId),parentDN)   
      
    /**
     * Create a new group entry
     */
    def groupModel(uuid:String, parentDN:DN, name:String, description : String, query: Option[Query], isDynamic : Boolean, srvList : Set[NodeId], isEnabled : Boolean, isSystem : Boolean = false) : LDAPEntry = {
      val mod = LDAPEntry(group.groupDN(uuid, parentDN))
      mod +=! (A_OC, OC.objectClassNames(OC_RUDDER_NODE_GROUP).toSeq:_*)
      mod +=! (A_NAME, name)
      mod +=! (A_DESCRIPTION, description)
      mod +=! (A_IS_ENABLED, isEnabled.toLDAPString)
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
      
      def targetDN(target:RuleTarget) : DN = target match {
        case GroupTarget(groupId) => group.groupDN(groupId.value, system.dn)
        case t => new DN(new RDN(A_RULE_TARGET, target.target), system.dn)
      }
        
      
    }
  }
  
  /**
   * That branch contains definition for Rudder server type.
   */
  val NODE_CONFIGS = new OU("Nodes Configuration", BASE_DN) {
    servers =>
 
    /**
     * There is two actual implementations of Rudder server, which 
     * differ only slightly in their identification.
     */  
    val NODE_CONFIG = new ENTRY1(A_NODE_UUID) {
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
        mod +=! (A_OC, OC.objectClassNames(OC_NODE_CONFIGURATION).toSeq:_*)
        mod
      }     
     
     
      /**
       * Directives for a server.
       * There is both current and target Directives,
       * they only differ on the objectType
       */
      val CF3POLICYDRAFT = new ENTRY1(A_DIRECTIVE_UUID) {
        def dn(uuid:String,nodeConfigurationDN:DN) : DN = {
          require(nonEmpty(uuid), "A CF3 Policy Draft ID can not be empty")
          require( !nodeConfigurationDN.isNullDN , "Can not use a null DN for the Cf3 Policy Draft's node configuration")
          new DN(this.rdn(uuid),nodeConfigurationDN)
        }
        
        def model(directiveUUID:String,serverDN:DN) : LDAPEntry = {
          val mod = LDAPEntry(this.dn(directiveUUID,serverDN))
          mod +=! (A_OC, OC.objectClassNames(OC_RULE_WITH_CF3POLICYDRAFT).toSeq:_*)
          mod
        }     
      } //end CF3POLICYDRAFT
      
      val TARGET_CF3POLICYDRAFT = new ENTRY1(A_TARGET_DIRECTIVE_UUID) {
        def dn(uuid:String,serverDN:DN) : DN = {
          require(nonEmpty(uuid), "A CF3 Policy Draft ID can not be empty")
          require( !serverDN.isNullDN , "Can not use a null DN for the Cf3 Policy Draft's node configuration")
          new DN(this.rdn(uuid),serverDN)
        }
        
       def model(directiveUUID:String,serverDN:DN) : LDAPEntry = {
          val mod = LDAPEntry(this.dn(directiveUUID,serverDN))
          mod +=! (A_OC, OC.objectClassNames(OC_TARGET_RULE_WITH_CF3POLICYDRAFT).toSeq:_*)
          mod
        }
        
      } //end TARGET_CF3POLICYDRAFT
      
    } //end NODE_CONFIG
    
  } //end NODE_CONFIGS
  
  val ARCHIVES = new OU("Archives", BASE_DN) {
    archives =>
    //check for the presence of that entry at bootstrap
    dit.register(archives.model)
    
    def ruleModel(
      crArchiveId : RuleArchiveId
    ) : LDAPEntry = {
      (new OU("Rules-" + crArchiveId.value, archives.dn)).model
    }
    
    def userLibDN(id:ActiveTechniqueLibraryArchiveId) : DN = {
      activeTechniqueCategory(
          uuid     = "ActiveTechniqueLibrary-" + id.value
        , parentDN = archives.dn
      ).model.dn
    }
  
    def groupLibDN(id:NodeGroupLibraryArchiveId) : DN = {
      groupCategory(
          uuid     = "GroupLibrary-" + id.value
        , parentDN = archives.dn
      ).model.dn
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
