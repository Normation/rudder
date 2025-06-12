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

import com.normation.GitVersion
import com.normation.NamedZioLogger
import com.normation.errors.*
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.inventory.ldap.core.LDAPConstants.A_NAME
import com.normation.ldap.sdk.*
import com.normation.ldap.sdk.BuildFilter.*
import com.normation.ldap.sdk.LDAPIOResult.*
import com.normation.ldap.sdk.syntax.*
import com.normation.rudder.domain.RudderDit
import com.normation.rudder.domain.RudderLDAPConstants.*
import com.normation.rudder.domain.archives.RuleArchiveId
import com.normation.rudder.domain.policies.*
import com.normation.rudder.services.user.PersonIdentService
import com.unboundid.ldif.LDIFChangeRecord
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.joda.time.format.ISODateTimeFormat
import zio.*
import zio.syntax.*

class RoLDAPRuleRepository(
    val rudderDit: RudderDit,
    val ldap:      LDAPConnectionProvider[RoLDAPConnection],
    val mapper:    LDAPEntityMapper,
    val ruleMutex: ScalaReadWriteLock
) extends RoRuleRepository with NamedZioLogger {
  repo =>

  override def loggerName: String = this.getClass.getName

  /**
   * Try to find the rule with the given ID.
   */
  def getOpt(id: RuleId): IOResult[Option[Rule]] = {
    ruleMutex.readLock(for {
      con     <- ldap
      crEntry <- con.get(rudderDit.RULES.configRuleDN(id))
      rule    <- crEntry match {
                   case None    => None.succeed
                   case Some(r) =>
                     mapper
                       .entry2Rule(r)
                       .map(Some(_))
                       .toIO
                       .chainError(s"Error when transforming LDAP entry into a rule for id '${id.serialize}'. Entry: ${r}")
                 }
    } yield {
      rule
    })
  }

  def getAll(includeSystem: Boolean = false): IOResult[Seq[Rule]] = {
    val filter = if (includeSystem) IS(OC_RULE) else AND(IS(OC_RULE), EQ(A_IS_SYSTEM, false.toLDAPString))
    ruleMutex.readLock(for {
      con     <- ldap
      entries <- con.searchOne(rudderDit.RULES.dn, filter)
      rules   <-
        ZIO.foreach(entries) { crEntry =>
          mapper.entry2Rule(crEntry).toIO.chainError("Error when transforming LDAP entry into a rule. Entry: %s".format(crEntry))
        }
    } yield {
      rules
    })
  }

  def getIds(includeSystem: Boolean = false): IOResult[Set[RuleId]] = {
    val filter = if (includeSystem) IS(OC_RULE) else AND(IS(OC_RULE), EQ(A_IS_SYSTEM, false.toLDAPString))
    ruleMutex.readLock(for {
      con     <- ldap
      entries <- con.searchOne(rudderDit.RULES.dn, filter, A_RULE_UUID)
      ids     <- ZIO.foreach(entries) { ruleEntry =>
                   for {
                     id <-
                       ruleEntry(A_RULE_UUID).notOptional(s"Missing required attribute uuid in rule entry ${ruleEntry.dn.toString}")
                   } yield {
                     RuleId(RuleUid(id))
                   }
                 }
    } yield {
      ids.toSet
    })
  }

}

class WoLDAPRuleRepository(
    roLDAPRuleRepository: RoLDAPRuleRepository,
    ldap:                 LDAPConnectionProvider[RwLDAPConnection],
    diffMapper:           LDAPDiffMapper,
    groupRepository:      RoNodeGroupRepository,
    actionLogger:         EventLogRepository,
    gitCrArchiver:        GitRuleArchiver,
    personIdentService:   PersonIdentService,
    autoExportOnModify:   Boolean
) extends WoRuleRepository with NamedZioLogger {
  repo =>

  import roLDAPRuleRepository.*

  override def loggerName: String = this.getClass.getName

  /**
   * Check if a configuration exist with the given name, and another id
   */
  private def nodeRuleNameExists(con: RoLDAPConnection, name: String, id: RuleId): IOResult[Boolean] = {
    val filter = AND(AND(IS(OC_RULE), EQ(A_NAME, name), NOT(EQ(A_RULE_UUID, id.uid.value))))
    con
      .searchSub(rudderDit.RULES.dn, filter)
      .flatMap(_.size match {
        case 0 => false.succeed
        case 1 => true.succeed
        case _ => logPure.error(s"More than one rule has name '${name}'") *> true.succeed
      })
  }

  private def internalDeleteRule(
      id:         RuleId,
      modId:      ModificationId,
      actor:      EventActor,
      reason:     Option[String],
      callSystem: Boolean
  ): IOResult[DeleteRuleDiff] = {
    ruleMutex.writeLock(for {
      _            <- ZIO.when(id.rev != GitVersion.DEFAULT_REV) {
                        Inconsistency(
                          s"Error: you can't delete a rule with a specific revision like here for rule with id '${id.uid.serialize}' which has revision '${id.rev.value}'"
                        ).fail
                      }
      con          <- ldap
      entry        <- con.get(rudderDit.RULES.configRuleDN(id)).notOptional("rule with ID '%s' is not present".format(id.serialize))
      oldCr        <- mapper
                        .entry2Rule(entry)
                        .toIO
                        .chainError("Error when transforming LDAP entry into a rule for id %s. Entry: %s".format(id, entry))
      checkSystem  <- (oldCr.isSystem, callSystem) match {
                        case (true, false) => Unexpected(s"System Rule '${id.serialize}' can't be deleted").fail
                        case (false, true) =>
                          Inconsistency(s"Non-system Rule '${id.serialize}' can not be deleted with that method").fail
                        case _             => oldCr.succeed
                      }
      deleted      <- con.delete(rudderDit.RULES.configRuleDN(id)).chainError("Error when deleting rule with ID %s".format(id))
      diff          = DeleteRuleDiff(oldCr)
      loggedAction <- actionLogger.saveDeleteRule(modId, principal = actor, deleteDiff = diff, reason = reason)
      autoArchive  <- ZIO.when(autoExportOnModify && deleted.nonEmpty && !oldCr.isSystem) {
                        for {
                          commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                          archive  <- gitCrArchiver.deleteRule(id, Some((modId, commiter, reason)))
                        } yield {
                          archive
                        }
                      }
    } yield {
      diff
    })
  }

  override def deleteSystemRule(
      id:     RuleId,
      modId:  ModificationId,
      actor:  EventActor,
      reason: Option[String]
  ): IOResult[DeleteRuleDiff] = {
    internalDeleteRule(id, modId, actor, reason, callSystem = true)
  }

  override def delete(id: RuleId, modId: ModificationId, actor: EventActor, reason: Option[String]): IOResult[DeleteRuleDiff] = {
    internalDeleteRule(id, modId, actor, reason, callSystem = false)
  }

  override def load(rule: Rule, modId: ModificationId, actor: EventActor, reason: Option[String]): IOResult[Unit] = {
    ruleMutex.writeLock(
      for {
        _             <- ZIO.when(rule.id.rev == GitVersion.DEFAULT_REV) {
                           Inconsistency(
                             s"Error: you can't load a rule with default revision like here for rule with id '${rule.id.uid.serialize}'. Use create or update for that."
                           ).fail
                         }
        con           <- ldap
        ruleExits     <- con.exists(rudderDit.RULES.configRuleDN(rule.id))
        idDoesntExist <- ZIO.when(ruleExits) {
                           logPure.info(s"Rule with ID '${rule.id.serialize}' is already loaded: updating it.")
                         }
        crEntry        = mapper.rule2Entry(rule)
        _             <- con.save(crEntry).chainError(s"Error when saving rule entry in repository: ${crEntry}")
        // we don't persist a diff in git here because it wouldn't make any sens, but perhaps we
        // at least need to store an new event log for that. Since it's a WIP API, not doing it right now.
        // That's also why there is a modId/actor/reason not used.
      } yield ()
    )
  }

  override def unload(ruleId: RuleId, modId: ModificationId, actor: EventActor, reason: Option[String]): IOResult[Unit] = {
    ruleMutex.writeLock(
      for {
        _   <- ZIO.when(ruleId.rev == GitVersion.DEFAULT_REV) {
                 Inconsistency(
                   s"Error: you can't unload a rule with default revision like here for rule with id '${ruleId.uid.serialize}'. Use delete for that."
                 ).fail
               }
        con <- ldap
        _   <-
          con
            .delete(rudderDit.RULES.configRuleDN(ruleId))
            .chainError(s"Error when unloading rule with ID '${ruleId.uid.serialize}' for revision '${ruleId.rev.value}'")
        // we don't persist a diff in git here because it wouldn't make any sens, but perhaps we
        // at least need to store an new event log for that. Since it's a WIP API, not doing it right now.
        // That's also why there is a modId/actor/reason not used.
      } yield ()
    )
  }

  def create(rule: Rule, modId: ModificationId, actor: EventActor, reason: Option[String]): IOResult[AddRuleDiff] = {
    ruleMutex.writeLock(for {
      _          <- ZIO.when(rule.id.rev != GitVersion.DEFAULT_REV) {
                      Inconsistency(
                        s"Error: you can't create a rule with a specific revision like here for rule with id '${rule.id.uid.serialize}' which has revision '${rule.id.rev.value}'"
                      ).fail
                    }
      con        <- ldap
      ruleExits  <- con.exists(rudderDit.RULES.configRuleDN(rule.id))
      _          <- ZIO.when(ruleExits) {
                      s"Cannot create a rule with ID '${rule.id.serialize}' : there is already a rule with the same id".fail
                    }
      nameExists <- nodeRuleNameExists(con, rule.name, rule.id)
      _          <- ZIO.when(nameExists) {
                      "Cannot create a rule with name %s : there is already a rule with the same name".format(rule.name).fail
                    }
      crEntry     = mapper.rule2Entry(rule)
      result     <- con.save(crEntry).chainError(s"Error when saving rule entry in repository: ${crEntry}")
      diff       <- diffMapper.addChangeRecords2RuleDiff(crEntry.dn, result).toIO
      _          <- actionLogger.saveAddRule(modId, principal = actor, addDiff = diff, reason = reason)
      _          <- ZIO.when(autoExportOnModify && !rule.isSystem) {
                      for {
                        commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                        archive  <- gitCrArchiver.archiveRule(rule, Some((modId, commiter, reason)))
                      } yield {
                        archive
                      }
                    }
    } yield {
      diff
    })
  }

  private def internalUpdate(
      rule:       Rule,
      modId:      ModificationId,
      actor:      EventActor,
      reason:     Option[String],
      systemCall: Boolean
  ): IOResult[Option[ModifyRuleDiff]] = {
    ruleMutex.writeLock(
      for {
        _               <- ZIO.when(rule.id.rev != GitVersion.DEFAULT_REV) {
                             Inconsistency(
                               s"Error: you can't update a rule with a specific revision like here for rule with id '${rule.id.uid.serialize}' which has revision '${rule.id.rev.value}'"
                             ).fail
                           }
        con             <- ldap
        existingEntry   <- con
                             .get(rudderDit.RULES.configRuleDN(rule.id))
                             .notOptional(s"Cannot update rule with id ${rule.id.serialize} : there is no rule with that id")
        oldRule         <-
          mapper
            .entry2Rule(existingEntry)
            .toIO
            .chainError(s"Error when transforming LDAP entry into a Rule for id ${rule.id.serialize}. Entry: ${existingEntry}")
        systemCheck     <- (oldRule.isSystem, systemCall) match {
                             case (true, false) => s"System rule '${oldRule.name}' (${oldRule.id.serialize}) can not be modified".fail
                             case (false, true) => "You can't modify a non-system rule with updateSystem method".fail
                             case _             => ZIO.unit
                           }
        exists          <- nodeRuleNameExists(con, rule.name, rule.id)
        nameIsAvailable <- ZIO.when(exists) {
                             s"Cannot update rule with name ${rule.name}: this name is already in use.".fail
                           }
        crEntry          = mapper.rule2Entry(rule)
        result          <-
          con.save(crEntry, removeMissingAttributes = true).chainError(s"Error when saving rule entry in repository: ${crEntry}")
        optDiff         <- diffMapper
                             .modChangeRecords2RuleDiff(existingEntry, result)
                             .toIO
                             .chainError(s"Error when mapping rule '${rule.id.serialize}' update to an diff: ${result}")
        loggedAction    <- optDiff match {
                             case None       => ZIO.unit
                             case Some(diff) =>
                               actionLogger.saveModifyRule(modId, principal = actor, modifyDiff = diff, reason = reason)
                           }
        autoArchive     <- ZIO.when(autoExportOnModify && optDiff.isDefined && !rule.isSystem) {
                             for {
                               commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                               archive  <- gitCrArchiver.archiveRule(rule, Some((modId, commiter, reason)))
                             } yield {
                               archive
                             }
                           }
      } yield {
        optDiff
      }
    )
  }

  def update(rule: Rule, modId: ModificationId, actor: EventActor, reason: Option[String]): IOResult[Option[ModifyRuleDiff]] = {
    internalUpdate(rule, modId, actor, reason, systemCall = false)
  }

  def updateSystem(
      rule:   Rule,
      modId:  ModificationId,
      actor:  EventActor,
      reason: Option[String]
  ): IOResult[Option[ModifyRuleDiff]] = {
    internalUpdate(rule, modId, actor, reason, systemCall = true)
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
  def swapRules(newCrs: Seq[Rule]): IOResult[RuleArchiveId] = {

    // save rules, taking care of serial value
    def saveCR(con: RwLDAPConnection, rule: Rule):              LDAPIOResult[LDIFChangeRecord]      = {
      val entry = mapper.rule2Entry(rule)
      con.save(entry)
    }
    // restore the archive in case of error
    // that method will be hard to achieve out of here due to serial
    def restore(con: RwLDAPConnection, previousCRs: Seq[Rule]): LDAPIOResult[Seq[LDIFChangeRecord]] = {
      for {
        deleteCR  <- con.delete(rudderDit.RULES.dn)
        savedBack <- ZIO.foreach(previousCRs)(rule => saveCR(con, rule))
      } yield {
        savedBack
      }
    }

    ///// actual code for swapRules /////

    val id = RuleArchiveId((DateTime.now(DateTimeZone.UTC)).toString(ISODateTimeFormat.dateTime))
    val ou = rudderDit.ARCHIVES.ruleModel(id)
    // filter systemCr if they are not included, so that merge does not have to deal with that.

    ruleMutex.writeLock(
      for {
        existingCrs <- getAll(true)
        con         <- ldap
        // ok, now that's the dangerous part
        swapCr      <- (for {
                         // move old rule to archive branch
                         renamed  <- con.move(rudderDit.RULES.dn, ou.dn.getParent, Some(ou.dn.getRDN))
                         // now, create back config rule branch and save rules
                         crOu     <- con.save(rudderDit.RULES.model)
                         savedCrs <- ZIO.foreach(newCrs)(rule => saveCR(con, rule))
                         // if include system is false, copy back system rule
                         copyBack <- ZIO.foreach(existingCrs.filter(_.isSystem))(syscr => saveCR(con, syscr))
                       } yield {
                         id
                       }).catchAll { eb =>
                         val e = Chained("Error when importing CRs, trying to restore old CR", eb)
                         logPure.error(e.msg) *>
                         restore(con, existingCrs).foldZIO(
                           _ =>
                             Chained(
                               "Error when rollbacking corrupted import for rules, expect other errors. Archive ID: '%s'".format(
                                 id.value
                               ),
                               e
                             ).fail,
                           _ => {
                             logPure.info("Rollback rules") *>
                             Chained("Rollbacked imported rules to previous state", e).fail
                           }
                         )
                       }
      } yield {
        id
      }
    )
  }

  def deleteSavedRuleArchiveId(archiveId: RuleArchiveId): IOResult[Unit] = {
    ruleMutex.writeLock(for {
      con     <- ldap
      deleted <- con.delete(rudderDit.ARCHIVES.ruleModel(archiveId).dn)
    } yield {
      ()
    })
  }
}
