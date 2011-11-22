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

package com.normation.rudder.repository
package ldap


import com.normation.rudder.domain.nodes.NodeGroupId
import org.joda.time.DateTime
import com.normation.rudder.domain.policies._
import com.normation.inventory.domain.NodeId
import com.normation.cfclerk.services.PolicyPackageService
import com.normation.inventory.ldap.core.LDAPConstants.{A_OC, A_NAME}
import com.unboundid.ldap.sdk.{DN,Filter}
import com.normation.ldap.sdk._
import BuildFilter._
import com.normation.rudder.domain.{RudderDit,NodeDit,RudderLDAPConstants}
import RudderLDAPConstants._
import net.liftweb.common._
import com.normation.utils.Control.sequence
import com.normation.cfclerk.domain.PolicyPackageId
import com.normation.eventlog.EventActor
import com.normation.rudder.domain.log.{
  DeleteConfigurationRule, AddConfigurationRule, ModifyConfigurationRule
}

class LDAPConfigurationRuleRepository(
    rudderDit           : RudderDit
  , nodeDit             : NodeDit
  , ldap                : LDAPConnectionProvider
  , mapper              : LDAPEntityMapper
  , diffMapper          : LDAPDiffMapper
  , groupRepository     : NodeGroupRepository
  , policyPackageService: PolicyPackageService
  , actionLogger        : EventLogRepository
) extends ConfigurationRuleRepository with Loggable {
  repo => 
  
  /**
   * Try to find the configuration rule with the given ID.
   * Empty: no policy instance with such ID
   * Full((parent,pi)) : found the policy instance (pi.id == piId) in given parent
   * Failure => an error happened.
   */
  def get(id:ConfigurationRuleId) : Box[ConfigurationRule]  = {
    for {
      con <- ldap 
      crEntry <- con.get(rudderDit.CONFIG_RULE.configRuleDN(id.value)) 
      cr <- mapper.entry2ConfigurationRule(crEntry) ?~! "Error when transforming LDAP entry into a Configuration Rule for id %s. Entry: %s".format(id, crEntry)
    } yield {
      cr
    }
  }


  def delete(id:ConfigurationRuleId, actor:EventActor) : Box[DeleteConfigurationRuleDiff] = {
    for {
      con <- ldap
      entry <- con.get(rudderDit.CONFIG_RULE.configRuleDN(id.value)) ?~! "Configuration rule with ID '%s' is not present".format(id.value)
      oldCr <- mapper.entry2ConfigurationRule(entry) ?~! "Error when transforming LDAP entry into a Configuration Rule for id %s. Entry: %s".format(id, entry)
      deleted <- con.delete(rudderDit.CONFIG_RULE.configRuleDN(id.value)) ?~! "Error when deleting configuration rule with ID %s".format(id)
      diff = DeleteConfigurationRuleDiff(oldCr)
      loggedAction <- actionLogger.saveDeleteConfigurationRule(principal = actor, deleteDiff = diff)
    } yield {
      diff
    }
  }

  
  def getAll(includeSystem:Boolean = false) : Box[Seq[ConfigurationRule]] = {
    val filter = if(includeSystem) IS(OC_CONFIGURATION_RULE) else AND(IS(OC_CONFIGURATION_RULE), EQ(A_IS_SYSTEM,false.toLDAPString))
    for {
      con <- ldap
      crs <- sequence(con.searchOne(rudderDit.CONFIG_RULE.dn, filter)) { crEntry =>
        mapper.entry2ConfigurationRule(crEntry) ?~! "Error when transforming LDAP entry into a Configuration Rule. Entry: %s".format(crEntry)
      }
    } yield {
      crs
    }
  }
  
  
  def create(cr:ConfigurationRule, actor:EventActor) : Box[AddConfigurationRuleDiff] = {
    repo.synchronized { for {
      con <- ldap
      idDoesntExist <- if(con.exists(rudderDit.CONFIG_RULE.configRuleDN(cr.id.value))) {
          Failure("Cannot create a configuration rule with id %s : there is already a configuration rule with the same id".format(cr.id))
        } else { 
          Full(Unit) 
      }
      nameIsAvailable <- if (nodeConfigurationRuleNameExists(con, cr.name, cr.id)) Failure("Cannot create a configuration rule with name %s : there is already a configuration rule with the same name".format(cr.name))
                else Full(Unit)
      crEntry = mapper.configurationRule2Entry(cr) 
      result <- {
        crEntry +=! (A_SERIAL, "0") //serial must be set to 0
        con.save(crEntry) ?~! "Error when saving Configuration Rule entry in repository: %s".format(crEntry)
      }
      diff <- diffMapper.addChangeRecords2ConfigurationRuleDiff(crEntry.dn,result)
      loggedAction <- actionLogger.saveAddConfigurationRule(principal = actor, addDiff = diff)
    } yield {
      diff
    } }
  }

  
  def update(cr:ConfigurationRule, actor:EventActor) : Box[Option[ModifyConfigurationRuleDiff]] = {
    repo.synchronized { for {
      con <- ldap
      existingEntry <- con.get(rudderDit.CONFIG_RULE.configRuleDN(cr.id.value)) ?~! "Cannot update configuration rule with id %s : there is no configuration rule with that id".format(cr.id.value)
      crEntry = mapper.configurationRule2Entry(cr)
      result <- con.save(crEntry, true, Seq(A_SERIAL)) ?~! "Error when saving Configuration Rule entry in repository: %s".format(crEntry)
      optDiff <- diffMapper.modChangeRecords2ConfigurationRuleDiff(existingEntry,result) ?~! "Error when mapping Configuration Rule '%s' update to an diff: %s".format(cr.id.value, result)
      loggedAction <- optDiff match {
        case None => Full("OK")
        case Some(diff) => actionLogger.saveModifyConfigurationRule(principal = actor, modifyDiff = diff)
      }
    } yield {
      optDiff
    } }
  }
        
  
  def incrementSerial(id:ConfigurationRuleId) : Box[Int] = {
    repo.synchronized { for {
      con <- ldap
      entryWithSerial <-  con.get(rudderDit.CONFIG_RULE.configRuleDN(id.value), A_SERIAL)
      serial <- Box(entryWithSerial.getAsInt(A_SERIAL)) ?~! "Missing Int attribute '%s' in entry, cannot update".format(A_SERIAL)
      result <- {
        entryWithSerial +=! (A_SERIAL, (serial + 1).toString)
        con.save(entryWithSerial) ?~! "Error when saving Configuration Rule entry in repository: %s".format(entryWithSerial)
      }
    } yield {
      serial + 1
    } }
  }

  /**
   * Return all activated configuration rule.
   * A configuration rule is activated if 
   * - its attribute "isActivated" is set to true ;
   * - its referenced group is defined and Activated, or it reference a special target
   * - its referenced policy instance is defined and activated (what means that the 
   *   referenced user policy template is activated)
   * @return
   */
  def getAllActivated() : Box[Seq[ConfigurationRule]] = {
  	// First fetch the activated groups
  	val groupsId = (for {
      con <- ldap
      activatedGroups = con.searchSub(rudderDit.GROUP.dn, AND(IS(OC_RUDDER_NODE_GROUP), EQ(A_IS_ACTIVATED, true.toLDAPString)), "1.1")
      groupIds <- sequence(activatedGroups) { entry =>
        rudderDit.GROUP.getGroupId(entry.dn)
      }
  	} yield groupIds )
  	
  	logger.debug("Activated groups are %s".format(groupsId.mkString(";")))
    (for {
      con <- ldap
      activatedUPT_entry = con.searchSub(rudderDit.POLICY_TEMPLATE_LIB.dn, AND(IS(OC_USER_POLICY_TEMPLATE), EQ(A_IS_ACTIVATED, true.toLDAPString)), "1.1")
      activatedPI_DNs = activatedUPT_entry.flatMap { entry =>
        con.searchOne(entry.dn, AND(IS(OC_WBPI), EQ(A_IS_ACTIVATED, true.toLDAPString)), "1.1").map( _.dn)
      }
      activatedPI_ids <- sequence(activatedPI_DNs) { dn =>
        rudderDit.POLICY_TEMPLATE_LIB.getLDAPConfigurationRuleID(dn)
      }
     configRules <- sequence(con.searchSub(rudderDit.CONFIG_RULE.dn, 
        //group is activated and pi is activated and config rule is activated !
        AND(IS(OC_CONFIGURATION_RULE),
          EQ(A_IS_ACTIVATED, true.toLDAPString),
          HAS(A_POLICY_TARGET),
          HAS(A_WBPI_UUID),
          OR(activatedPI_ids.map(id =>  EQ(A_WBPI_UUID, id)):_*)
        )
      )) { entry => mapper.entry2ConfigurationRule(entry) }
    } yield {
      configRules
    } ) match {
  		case Full(list) =>
  		// a config rule activated point to an activated group, or to a special target
  			Full(list.filter( cr => cr.isActivated && ( (cr.target, cr.policyInstanceIds) match {
  			  case (Some(t), pis) if pis.size > 0 => //should be the case, given the request
  			    t match {      			  
      				case GroupTarget(group) =>
      						logger.debug("Checking activation of group %s for the target of cr %s".format(group.value, cr.id.value))
      						groupsId.openOr(Seq()).contains(group.value)
      				case _ => true
  			    }
  			  case _ => false  
  			} ) ))
  		case f : EmptyBox => f
  	}	
  }


  /**
   * Check if a configuration exist with the given name, and another id
   */
  private[this] def nodeConfigurationRuleNameExists(con:LDAPConnection, name : String, id:ConfigurationRuleId) : Boolean = {
    val filter = AND(AND(IS(OC_CONFIGURATION_RULE), EQ(A_NAME,name), NOT(EQ(A_CONFIGURATION_RULE_UUID, id.value))))
    con.searchSub(rudderDit.CONFIG_RULE.dn, filter).size match {
      case 0 => false
      case 1 => true
      case _ => logger.error("More than one Configuration Rule has %s name".format(name)); true
    }
  }
}

