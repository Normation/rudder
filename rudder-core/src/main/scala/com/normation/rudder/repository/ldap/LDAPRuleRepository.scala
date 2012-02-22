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
import com.normation.cfclerk.services.TechniqueRepository
import com.normation.inventory.ldap.core.LDAPConstants.{A_OC, A_NAME}
import com.unboundid.ldap.sdk.{DN,Filter}
import com.normation.ldap.sdk._
import BuildFilter._
import com.normation.rudder.domain.{RudderDit,NodeDit,RudderLDAPConstants}
import RudderLDAPConstants._
import net.liftweb.common._
import com.normation.utils.Control.sequence
import com.normation.cfclerk.domain.TechniqueId
import com.normation.eventlog.EventActor
import com.normation.rudder.domain.log.{
  DeleteRule, AddRule, ModifyRule
}
import org.joda.time.format.ISODateTimeFormat
import com.normation.rudder.domain.archives.RuleArchiveId
import com.unboundid.ldif.LDIFChangeRecord
import com.normation.rudder.services.user.PersonIdentService

class LDAPRuleRepository(
    rudderDit           : RudderDit
  , nodeDit             : NodeDit
  , ldap                : LDAPConnectionProvider
  , mapper              : LDAPEntityMapper
  , diffMapper          : LDAPDiffMapper
  , groupRepository     : NodeGroupRepository
  , techniqueRepository: TechniqueRepository
  , actionLogger        : EventLogRepository
  , gitCrArchiver       : GitRuleArchiver
  , personIdentService  : PersonIdentService
  , autoExportOnModify  : Boolean
) extends RuleRepository with Loggable {
  repo => 
  
  /**
   * Try to find the configuration rule with the given ID.
   * Empty: no policy instance with such ID
   * Full((parent,directive)) : found the policy instance (directive.id == directiveId) in given parent
   * Failure => an error happened.
   */
  def get(id:RuleId) : Box[Rule]  = {
    for {
      con     <- ldap 
      crEntry <- con.get(rudderDit.RULES.configRuleDN(id.value)) 
      rule      <- mapper.entry2Rule(crEntry) ?~! "Error when transforming LDAP entry into a Configuration Rule for id %s. Entry: %s".format(id, crEntry)
    } yield {
      rule
    }
  }


  def delete(id:RuleId, actor:EventActor) : Box[DeleteRuleDiff] = {
    for {
      con          <- ldap
      entry        <- con.get(rudderDit.RULES.configRuleDN(id.value)) ?~! "Configuration rule with ID '%s' is not present".format(id.value)
      oldCr        <- mapper.entry2Rule(entry) ?~! "Error when transforming LDAP entry into a Configuration Rule for id %s. Entry: %s".format(id, entry)
      deleted      <- con.delete(rudderDit.RULES.configRuleDN(id.value)) ?~! "Error when deleting configuration rule with ID %s".format(id)
      diff         =  DeleteRuleDiff(oldCr)
      loggedAction <- actionLogger.saveDeleteRule(principal = actor, deleteDiff = diff)
      autoArchive  <- if(autoExportOnModify && deleted.size > 0) {
                        for {
                          commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                          archive  <- gitCrArchiver.deleteRule(id, Some(commiter))
                        } yield {
                          archive
                        }
                      } else Full("ok")
    } yield {
      diff
    }
  }

  
  def getAll(includeSystem:Boolean = false) : Box[Seq[Rule]] = {
    val filter = if(includeSystem) IS(OC_RULE) else AND(IS(OC_RULE), EQ(A_IS_SYSTEM,false.toLDAPString))
    for {
      con <- ldap
      rules <- sequence(con.searchOne(rudderDit.RULES.dn, filter)) { crEntry =>
               mapper.entry2Rule(crEntry) ?~! "Error when transforming LDAP entry into a Configuration Rule. Entry: %s".format(crEntry)
             }
    } yield {
      rules
    }
  }
  
  
  def create(rule:Rule, actor:EventActor) : Box[AddRuleDiff] = {
    repo.synchronized { for {
      con             <- ldap
      idDoesntExist   <- if(con.exists(rudderDit.RULES.configRuleDN(rule.id.value))) {
                           Failure("Cannot create a configuration rule with id %s : there is already a configuration rule with the same id".format(rule.id))
                         } else { 
                           Full(Unit) 
                         }
      nameIsAvailable <- if (nodeRuleNameExists(con, rule.name, rule.id)) Failure("Cannot create a configuration rule with name %s : there is already a configuration rule with the same name".format(rule.name))
                         else Full(Unit)
      crEntry         =  mapper.rule2Entry(rule) 
      result          <- {
                           crEntry +=! (A_SERIAL, "0") //serial must be set to 0
                           con.save(crEntry) ?~! "Error when saving Configuration Rule entry in repository: %s".format(crEntry)
                         }
      diff            <- diffMapper.addChangeRecords2RuleDiff(crEntry.dn,result)
      loggedAction    <- actionLogger.saveAddRule(principal = actor, addDiff = diff)
      autoArchive     <- if(autoExportOnModify) {
                           for {
                             commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                             archive  <- gitCrArchiver.archiveRule(rule, Some(commiter))
                           } yield {
                             archive
                           }
                         } else Full("ok")
    } yield {
      diff
    } }
  }

  
  def update(rule:Rule, actor:EventActor) : Box[Option[ModifyRuleDiff]] = {
    repo.synchronized { for {
      con           <- ldap
      existingEntry <- con.get(rudderDit.RULES.configRuleDN(rule.id.value)) ?~! "Cannot update configuration rule with id %s : there is no configuration rule with that id".format(rule.id.value)
      crEntry       =  mapper.rule2Entry(rule)
      result        <- con.save(crEntry, true, Seq(A_SERIAL)) ?~! "Error when saving Configuration Rule entry in repository: %s".format(crEntry)
      optDiff       <- diffMapper.modChangeRecords2RuleDiff(existingEntry,result) ?~! "Error when mapping Configuration Rule '%s' update to an diff: %s".format(rule.id.value, result)
      loggedAction  <- optDiff match {
                         case None => Full("OK")
                         case Some(diff) => actionLogger.saveModifyRule(principal = actor, modifyDiff = diff)
                       }
      autoArchive   <- if(autoExportOnModify && optDiff.isDefined) {
                         for {
                           commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                           archive  <- gitCrArchiver.archiveRule(rule, Some(commiter))
                         } yield {
                           archive
                         }
                       } else Full("ok")
    } yield {
      optDiff
    } }
  }
        
  
  def incrementSerial(id:RuleId) : Box[Int] = {
    repo.synchronized { 
      for {
        con             <- ldap
        entryWithSerial <-  con.get(rudderDit.RULES.configRuleDN(id.value), A_SERIAL)
        serial          <- Box(entryWithSerial.getAsInt(A_SERIAL)) ?~! "Missing Int attribute '%s' in entry, cannot update".format(A_SERIAL)
        result          <- {
                          entryWithSerial +=! (A_SERIAL, (serial + 1).toString)
                          con.save(entryWithSerial) ?~! "Error when saving Configuration Rule entry in repository: %s".format(entryWithSerial)
                        }
      } yield {
        serial + 1
      } 
    }
  }

  /**
   * Return all activated configuration rule.
   * A configuration rule is activated if 
   * - its attribute "isEnabled" is set to true ;
   * - its referenced group is defined and Activated, or it reference a special target
   * - its referenced policy instance is defined and activated (what means that the 
   *   referenced user policy template is activated)
   * @return
   */
  def getAllEnabled() : Box[Seq[Rule]] = {
    // First fetch the activated groups
    val groupsId = (
      for {
        con             <- ldap
        activatedGroups =  con.searchSub(rudderDit.GROUP.dn, AND(IS(OC_RUDDER_NODE_GROUP), EQ(A_IS_ENABLED, true.toLDAPString)), "1.1")
        groupIds        <- sequence(activatedGroups) { entry =>
                          rudderDit.GROUP.getGroupId(entry.dn)
                        }
      } yield {
        groupIds 
      })
    
    logger.debug("Activated groups are %s".format(groupsId.mkString(";")))
    (for {
      con                <- ldap
      activatedUPT_entry =  con.searchSub(rudderDit.ACTIVE_TECHNIQUES_LIB.dn, AND(IS(OC_ACTIVE_TECHNIQUE), EQ(A_IS_ENABLED, true.toLDAPString)), "1.1")
      activatedPI_DNs    =  activatedUPT_entry.flatMap { entry =>
                              con.searchOne(entry.dn, AND(IS(OC_DIRECTIVE), EQ(A_IS_ENABLED, true.toLDAPString)), "1.1").map( _.dn)
                            }
      activatedPI_ids    <- sequence(activatedPI_DNs) { dn =>
                              rudderDit.ACTIVE_TECHNIQUES_LIB.getLDAPRuleID(dn)
                            }
     configRules        <- sequence(con.searchSub(rudderDit.RULES.dn, 
                             //group is activated and directive is activated and config rule is activated !
                             AND(IS(OC_RULE),
                               EQ(A_IS_ENABLED, true.toLDAPString),
                               HAS(A_RULE_TARGET),
                               HAS(A_DIRECTIVE_UUID),
                               OR(activatedPI_ids.map(id =>  EQ(A_DIRECTIVE_UUID, id)):_*)
                             )
                           )) { entry => 
                             mapper.entry2Rule(entry) 
                           }
    } yield {
      configRules
    } ) match {
      case Full(list) =>
      // a config rule activated point to an activated group, or to a special target
        Full(list.filter( rule => rule.isEnabled && ( (rule.target, rule.directiveIds) match {
          case (Some(t), directives) if directives.size > 0 => //should be the case, given the request
            t match {
              case GroupTarget(group) =>
                  logger.debug("Checking activation of group %s for the target of rule %s".format(group.value, rule.id.value))
                  groupsId.openOr(Seq()).contains(group.value)
              case _ => true
            }
          case _ => false
        } ) ))
      case f : EmptyBox => f
    }
  }

  /**
   * Implementation logic: 
   * - lock LDAP for other writes (more precisely, only that repos, with
   *   synchronized method), 
   * - create a ou=Rules-YYYY-MM-DD_HH-mm in the "archive" branche
   * - move ALL (included system) current configuration rules in the previous 'ou'
   * - save newCr, filtering out system ones if includeSystem is false
   * - if includeSystem is false, copy back save system CR into rule branch
   * - release lock
   * 
   * If something goes wrong, try to restore. 
   */
  def swapRules(newCrs:Seq[Rule], includeSystem:Boolean = false) : Box[RuleArchiveId] = {
    //merge imported configuration rules and existing one, taking care of serial value
    def mergeCrs(importedCrs:Seq[Rule], existingCrs:Seq[Rule]) = {
      importedCrs.map { rule => 
        existingCrs.find(other => other.id == rule.id) match {
          //keep and increment serial
          case Some(existingCr) => rule.copy(serial = existingCr.serial + 1)
          //set serial to 0
          case None => rule.copy(serial = 0)
        }
      }
    }
    //save configuration rules, taking care of serial value
    def saveCR(con:LDAPConnection, rule:Rule) : Box[LDIFChangeRecord] = {
      val entry = mapper.rule2Entry(rule)
      entry +=! (A_SERIAL, rule.serial.toString)
      con.save(entry)
    }
    //restore the archive in case of error
    //that method will be hard to achieve out of here due to serial
    def restore(con:LDAPConnection, previousCRs:Seq[Rule], includeSystem:Boolean) = {
      for {
        deleteCR  <- con.delete(rudderDit.RULES.dn)
        savedBack <- sequence(previousCRs) { rule => 
                       saveCR(con,rule)
                     }
      } yield {
        savedBack
      }
    }
    
    ///// actual code for swapRules /////
    
    val id = RuleArchiveId((DateTime.now()).toString(ISODateTimeFormat.dateTime))
    val ou = rudderDit.ARCHIVES.ruleModel(id)
    //filter systemCr if they are not included, so that merge does not have to deal with that. 
    val importedCrs = if(includeSystem) newCrs else newCrs.filter( rule => !rule.isSystem)
    
    repo.synchronized {
      
      for {
        existingCrs <- repo.getAll(true)
        crToImport  =  mergeCrs(importedCrs, existingCrs)
        con         <- ldap
        //ok, now that's the dangerous part
        swapCr      <- (for {
                         //move old rule to archive branch
                         renamed     <- con.move(rudderDit.RULES.dn, ou.dn.getParent, Some(ou.dn.getRDN))
                         //now, create back config rule branch and save rules
                         crOu        <- con.save(rudderDit.RULES.model)
                         savedCrs    <- sequence(newCrs) { rule => 
                                        saveCR(con, rule)
                                     }
                         //if include system is false, copy back system rule
                         copyBack    <- if(!includeSystem) {
                                          sequence(existingCrs.filter( _.isSystem)) { syscr =>
                                           saveCR(con, syscr)
                                          }
                                        } else {
                                          Full("ok")
                                        }
                       } yield {
                         id
                       }) match {
                         case ok:Full[_] => ok
                         case eb:EmptyBox => //ok, so there, we have a problem
                           val e = eb ?~! "Error when importing CRs, trying to restore old CR"
                           logger.error(eb)
                           restore(con, existingCrs, includeSystem) match {
                             case _:Full[_] => 
                               logger.info("Rollback configuration rules")
                               eb ?~! "Rollbacked imported configuration rules to previous state"
                             case x:EmptyBox => 
                               val m = "Error when rollbacking corrupted import for configuration rules, expect other errors. Archive ID: '%s'".format(id.value)
                               eb ?~! m
                           }

                       }
      } yield {
        id
      }
    }
  }
  
  def deleteSavedRuleArchiveId(archiveId:RuleArchiveId) : Box[Unit] = {
    repo.synchronized {
      for {
        con     <- ldap
        deleted <- con.delete(rudderDit.ARCHIVES.ruleModel(archiveId).dn)
      } yield {
        {}
      }
    }
  }
    
    
    
  /**
   * Check if a configuration exist with the given name, and another id
   */
  private[this] def nodeRuleNameExists(con:LDAPConnection, name : String, id:RuleId) : Boolean = {
    val filter = AND(AND(IS(OC_RULE), EQ(A_NAME,name), NOT(EQ(A_RULE_UUID, id.value))))
    con.searchSub(rudderDit.RULES.dn, filter).size match {
      case 0 => false
      case 1 => true
      case _ => logger.error("More than one Configuration Rule has %s name".format(name)); true
    }
  }
}

