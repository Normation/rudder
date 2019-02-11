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

package com.normation.rudder.repository
package ldap


import org.joda.time.DateTime
import com.normation.rudder.domain.policies._
import com.normation.inventory.ldap.core.LDAPConstants.A_NAME
import com.normation.ldap.sdk._
import BuildFilter._
import com.normation.rudder.domain.{RudderDit,RudderLDAPConstants}
import RudderLDAPConstants._
import net.liftweb.common._
import com.normation.utils.Control.sequence
import com.normation.eventlog.EventActor
import org.joda.time.format.ISODateTimeFormat
import com.normation.rudder.domain.archives.RuleArchiveId
import com.unboundid.ldif.LDIFChangeRecord
import com.normation.rudder.services.user.PersonIdentService
import com.normation.eventlog.ModificationId
import com.normation.ldap.sdk.IOLdap._
import cats.implicits._

class RoLDAPRuleRepository(
    val rudderDit: RudderDit
  , val ldap     : LDAPConnectionProvider[RoLDAPConnection]
  , val mapper   : LDAPEntityMapper
) extends RoRuleRepository with Loggable {
  repo =>

  /**
   * Try to find the rule with the given ID.
   * Empty: no directive with such ID
   * Full((parent,directive)) : found the directive (directive.id == directiveId) in given parent
   * Failure => an error happened.
   */
  def get(id:RuleId) : Box[Rule]  = {
    for {
      con     <- ldap
      crEntry <- con.get(rudderDit.RULES.configRuleDN(id.value)).notOptional(s"Rule with id '${id.value}' was not found")
      rule    <- (mapper.entry2Rule(crEntry) ?~! "Error when transforming LDAP entry into a rule for id %s. Entry: %s".format(id, crEntry)).toIOLdap
    } yield {
      rule
    }
  }.toBox

  def getAll(includeSystem:Boolean = false) : Box[Seq[Rule]] = {
    val filter = if(includeSystem) IS(OC_RULE) else AND(IS(OC_RULE), EQ(A_IS_SYSTEM,false.toLDAPString))
    for {
      con     <- ldap
      entries <- con.searchOne(rudderDit.RULES.dn, filter)
      rules   <- entries.toVector.traverse { crEntry =>
                   (mapper.entry2Rule(crEntry) ?~! "Error when transforming LDAP entry into a rule. Entry: %s".format(crEntry)).toIOLdap
                 }
    } yield {
      rules
    }
  }.toBox

  def getIds(includeSystem:Boolean = false) : Box[Set[RuleId]] = {
    val filter = if(includeSystem) IS(OC_RULE) else AND(IS(OC_RULE), EQ(A_IS_SYSTEM,false.toLDAPString))
    for {
      con     <- ldap
      entries <- con.searchOne(rudderDit.RULES.dn, filter, A_RULE_UUID)
      ids     <- entries.toVector.traverse { ruleEntry =>
                   for {
                     id <- ruleEntry(A_RULE_UUID).successIOLdap().notOptional(s"Missing required attribute uuid in rule entry ${ruleEntry.dn.toString}")
                   } yield {
                     RuleId(id)
                   }
                 }
    } yield {
      ids.toSet
    }
  }.toBox

}

class WoLDAPRuleRepository(
    roLDAPRuleRepository: RoLDAPRuleRepository
  , ldap                : LDAPConnectionProvider[RwLDAPConnection]
  , diffMapper          : LDAPDiffMapper
  , groupRepository     : RoNodeGroupRepository
  , actionLogger        : EventLogRepository
  , gitCrArchiver       : GitRuleArchiver
  , personIdentService  : PersonIdentService
  , autoExportOnModify  : Boolean
) extends WoRuleRepository with Loggable {
  repo =>


  import roLDAPRuleRepository._

  /**
   * Check if a configuration exist with the given name, and another id
   */
  private[this] def nodeRuleNameExists(con:RoLDAPConnection, name : String, id:RuleId) : Boolean = {
    val filter = AND(AND(IS(OC_RULE), EQ(A_NAME,name), NOT(EQ(A_RULE_UUID, id.value))))
    con.searchSub(rudderDit.RULES.dn, filter).size match {
      case 0 => false
      case 1 => true
      case _ => logger.error("More than one rule has %s name".format(name)); true
    }
  }

  def delete(id:RuleId, modId: ModificationId, actor:EventActor, reason:Option[String]) : Box[DeleteRuleDiff] = {
    for {
      con          <- ldap
      entry        <- con.get(rudderDit.RULES.configRuleDN(id.value)).notOptional("rule with ID '%s' is not present".format(id.value))
      oldCr        <- (mapper.entry2Rule(entry) ?~! "Error when transforming LDAP entry into a rule for id %s. Entry: %s".format(id, entry)).toIOLdap
      isSytem      <- if(oldCr.isSystem) "Deleting system rule '%s (%s)' is forbiden".format(oldCr.name, oldCr.id.value).failureIOLdap() else "OK".successIOLdap()
      deleted      <- con.delete(rudderDit.RULES.configRuleDN(id.value)) ?~! "Error when deleting rule with ID %s".format(id)
      diff         =  DeleteRuleDiff(oldCr)
      loggedAction <- actionLogger.saveDeleteRule(modId, principal = actor, deleteDiff = diff, reason = reason).toIOLdap
      autoArchive  <- (if(autoExportOnModify && deleted.size > 0  && !oldCr.isSystem) {
                        for {
                          commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                          archive  <- gitCrArchiver.deleteRule(id, Some((modId, commiter, reason)))
                        } yield {
                          archive
                        }
                      } else Full("ok")).toIOLdap
    } yield {
      diff
    }
  }.toBox

  def create(rule:Rule, modId: ModificationId, actor:EventActor, reason:Option[String]) : Box[AddRuleDiff] = {
    repo.synchronized { for {
      con             <- ldap
      idDoesntExist   <- if(con.exists(rudderDit.RULES.configRuleDN(rule.id.value))) {
                           s"Cannot create a rule with ID '${rule.id.value}' : there is already a rule with the same id".failureIOLdap()
                         } else {
                           Unit.successIOLdap()
                         }
      nameIsAvailable <- if (nodeRuleNameExists(con, rule.name, rule.id)) "Cannot create a rule with name %s : there is already a rule with the same name".format(rule.name).failureIOLdap()
                         else Unit.successIOLdap()
      crEntry         =  mapper.rule2Entry(rule)
      result          <- con.save(crEntry) ?~! s"Error when saving rule entry in repository: ${crEntry}"
      diff            <- diffMapper.addChangeRecords2RuleDiff(crEntry.dn,result).toIOLdap
      loggedAction    <- actionLogger.saveAddRule(modId, principal = actor, addDiff = diff, reason = reason).toIOLdap
      autoArchive     <- (if(autoExportOnModify && !rule.isSystem) {
                           for {
                             commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                             archive  <- gitCrArchiver.archiveRule(rule, Some((modId, commiter, reason)))
                           } yield {
                             archive
                           }
                         } else Full("ok")).toIOLdap
    } yield {
      diff
    } }
  }.toBox

  private[this] def internalUpdate(rule:Rule, modId: ModificationId, actor:EventActor, reason:Option[String], systemCall:Boolean) : Box[Option[ModifyRuleDiff]] = {
    repo.synchronized { for {
      con           <- ldap
      existingEntry <- con.get(rudderDit.RULES.configRuleDN(rule.id.value)).notOptional(s"Cannot update rule with id ${rule.id.value} : there is no rule with that id")
      oldRule       <- (mapper.entry2Rule(existingEntry) ?~! s"Error when transforming LDAP entry into a Rule for id ${rule.id.value}. Entry: ${existingEntry}").toIOLdap
      systemCheck   <- (oldRule.isSystem, systemCall) match {
                       case (true, false) => s"System rule '${oldRule.name}' (${oldRule.id.value}) can not be modified".failureIOLdap()
                       case (false, true) => "You can't modify a non-system rule with updateSystem method".failureIOLdap()
                       case _ => "OK".successIOLdap()
                     }
      nameIsAvailable <- if (nodeRuleNameExists(con, rule.name, rule.id)) {
                           "Cannot update rule with name \"%s\" : this name is already in use.".format(rule.name).failureIOLdap()
                         } else Unit.successIOLdap()
      crEntry         =  mapper.rule2Entry(rule)
      result          <- con.save(crEntry, true) ?~! "Error when saving rule entry in repository: %s".format(crEntry)
      optDiff         <- (diffMapper.modChangeRecords2RuleDiff(existingEntry,result) ?~! "Error when mapping rule '%s' update to an diff: %s".format(rule.id.value, result)).toIOLdap
      loggedAction    <- optDiff match {
                           case None => "OK".successIOLdap()
                           case Some(diff) => actionLogger.saveModifyRule(modId, principal = actor, modifyDiff = diff, reason = reason).toIOLdap
                         }
      autoArchive     <- (if(autoExportOnModify && optDiff.isDefined  && !rule.isSystem) {
                           for {
                             commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                             archive  <- gitCrArchiver.archiveRule(rule, Some((modId, commiter, reason)))
                           } yield {
                             archive
                           }
                         } else Full("ok")).toIOLdap
    } yield {
      optDiff
    } }
  }.toBox

  def update(rule:Rule, modId: ModificationId, actor:EventActor, reason:Option[String]) : Box[Option[ModifyRuleDiff]] = {
    internalUpdate(rule, modId, actor, reason, false)
  }

  def updateSystem(rule:Rule, modId: ModificationId, actor:EventActor, reason:Option[String]) : Box[Option[ModifyRuleDiff]] = {
    internalUpdate(rule, modId, actor, reason, true)
  }

  /**
   * Implementation logic:
   * - lock LDAP for other writes (more precisely, only that repos, with
   *   synchronized method),
   * - create a ou=Rules-YYYY-MM-dd_HH-mm in the "archive" branche
   * - move ALL (included system) current rules in the previous 'ou'
   * - save newCr, filtering out system ones if includeSystem is false
   * - if includeSystem is false, copy back save system CR into rule branch
   * - release lock
   *
   * If something goes wrong, try to restore.
   */
  def swapRules(newCrs:Seq[Rule]) : Box[RuleArchiveId] = {

    //save rules, taking care of serial value
    def saveCR(con:RwLDAPConnection, rule:Rule) : IOLdap[LDIFChangeRecord] = {
      val entry = mapper.rule2Entry(rule)
      con.save(entry)
    }
    //restore the archive in case of error
    //that method will be hard to achieve out of here due to serial
    def restore(con:RwLDAPConnection, previousCRs:Seq[Rule]): IOLdap[Seq[LDIFChangeRecord]] = {
      for {
        deleteCR  <- con.delete(rudderDit.RULES.dn)
        savedBack <- previousCRs.toVector.traverse { rule =>
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

    repo.synchronized {

      for {
        existingCrs <- getAll(true).toIOLdap
        con         <- ldap
        //ok, now that's the dangerous part
        swapCr      <- (for {
                         //move old rule to archive branch
                         renamed     <- con.move(rudderDit.RULES.dn, ou.dn.getParent, Some(ou.dn.getRDN))
                         //now, create back config rule branch and save rules
                         crOu        <- con.save(rudderDit.RULES.model)
                         savedCrs    <- newCrs.toVector.traverse { rule =>
                                          saveCR(con, rule)
                                        }
                         //if include system is false, copy back system rule
                         copyBack    <- existingCrs.filter( _.isSystem).toVector.traverse { syscr =>
                                          saveCR(con, syscr)
                                        }
                       } yield {
                         id
                       }).leftMap { eb =>
                           val e = LDAPConnectionError.Chained("Error when importing CRs, trying to restore old CR", eb)
                           logger.error(e.messageChain)
                           restore(con, existingCrs).fold(_ =>
                               LDAPConnectionError.Chained("Error when rollbacking corrupted import for rules, expect other errors. Archive ID: '%s'".format(id.value), e)
                            ,  _ => {
                                 logger.info("Rollback rules")
                                 LDAPConnectionError.Chained("Rollbacked imported rules to previous state", e)
                               }
                            )
                       }
      } yield {
        id
      }
    }
  }.toBox

  def deleteSavedRuleArchiveId(archiveId:RuleArchiveId) : Box[Unit] = {
    repo.synchronized {
      for {
        con     <- ldap
        deleted <- con.delete(rudderDit.ARCHIVES.ruleModel(archiveId).dn)
      } yield {
        {}
      }
    }
  }.toBox
}
