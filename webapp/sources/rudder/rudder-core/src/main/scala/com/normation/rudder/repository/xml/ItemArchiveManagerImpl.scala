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

package com.normation.rudder.repository.xml

import com.normation.GitVersion
import com.normation.GitVersion.Revision
import com.normation.errors.*
import com.normation.eventlog.*
import com.normation.inventory.domain.Version
import com.normation.rudder.batch.AsyncDeploymentActor
import com.normation.rudder.batch.AutomaticStartDeployment
import com.normation.rudder.domain.Constants.*
import com.normation.rudder.domain.eventlog.*
import com.normation.rudder.domain.logger.GitArchiveLoggerPure
import com.normation.rudder.domain.nodes.*
import com.normation.rudder.domain.policies.*
import com.normation.rudder.git.*
import com.normation.rudder.ncf.{DeleteEditorTechnique as _, *}
import com.normation.rudder.ncf.eventlogs.*
import com.normation.rudder.ncf.yaml.YamlTechniqueSerializer
import com.normation.rudder.repository.*
import com.normation.rudder.rule.category.*
import com.normation.rudder.services.queries.DynGroupUpdaterService
import com.normation.rudder.tenants.*
import com.normation.rudder.tenants.ChangeContext.toQC
import com.normation.zio.*
import com.softwaremill.quicklens.*
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.Instant
import org.apache.commons.io.FileUtils
import org.apache.commons.io.IOUtils
import org.eclipse.jgit.api.*
import org.eclipse.jgit.lib.PersonIdent
import zio.*
import zio.syntax.*

class ItemArchiveManagerImpl(
    roRuleRepository:                   RoRuleRepository,
    woRuleRepository:                   WoRuleRepository,
    roRuleCategoryeRepository:          RoRuleCategoryRepository,
    roDirectiveRepository:              RoDirectiveRepository,
    woDirectiveRepository:              WoDirectiveRepository,
    roGroupRepository:                  RoNodeGroupRepository,
    woGroupRepository:                  WoNodeGroupRepository,
    roParameterRepository:              RoParameterRepository,
    woParameterRepository:              WoParameterRepository,
    override val gitRepo:               GitRepositoryProvider,
    gitRuleArchiver:                    GitRuleArchiver,
    gitRuleCategoryArchiver:            GitRuleCategoryArchiver,
    gitActiveTechniqueCategoryArchiver: GitActiveTechniqueCategoryArchiver,
    gitActiveTechniqueArchiver:         GitActiveTechniqueArchiver,
    gitNodeGroupArchiver:               GitNodeGroupArchiver,
    gitParameterArchiver:               GitParameterArchiver,
    parseRules:                         ParseRules,
    parseActiveTechniqueLibrary:        ParseActiveTechniqueLibrary,
    parseGlobalParameters:              ParseGlobalParameters,
    parseRuleCategories:                ParseRuleCategories,
    importTechniqueLibrary:             ImportTechniqueLibrary,
    parseGroupLibrary:                  ParseGroupLibrary,
    importGroupLibrary:                 ImportGroupLibrary,
    importRuleCategoryLibrary:          ImportRuleCategoryLibrary,
    eventLogger:                        EventLogRepository,
    asyncDeploymentAgent:               AsyncDeploymentActor,
    gitModificationRepo:                GitModificationRepository,
    updateDynamicGroups:                DynGroupUpdaterService,
    techniqueWriter:                    TechniqueWriter,
    yamlTechniqueSerializer:            YamlTechniqueSerializer
) extends ItemArchiveManager with GitArchiverFullCommitUtils {

  // import (retore, rollback, etc) action must be exclusive so if a second one happens concurrently, it's an error.
  val importSemaphore: Semaphore = Semaphore.make(1).runNow

  def useSemaphoreOrFail[A](effect: IOResult[A]): ZIO[Any, RudderError, A] = {
    // we timeout the semaphore acquisition to fail if another op is already running
    ZIO.scoped(
      importSemaphore.withPermitScoped
        .timeout(1.second)
        .flatMap(isOK => {
          if (isOK.isDefined) {
            effect
          } else {
            Inconsistency(
              "An other operation of import or rollback is already running. You should check its result before doing another one. Please retry latter."
            ).fail
          }
        })
    )
  }

  override val tagPrefix                 = "archives/full/"
  override val relativePath              = "."
  override val gitModificationRepository = gitModificationRepo

  ///// implementation /////
  override def loggerName: String = this.getClass.getName

  /*
   * Archive import/rollback restore a whole library (rules, groups, technique library, parameters) by
   * swapping it wholesale. There is no sound per-tenant semantic for such a "replace everything" operation,
   * so it must be restricted to administrators (tenant access grant '*'). For a tenant-restricted user, it
   * would otherwise allow replacing/removing configuration objects belonging to other tenants.
   * Note: when the multi-tenant feature is disabled, every user has an 'All' grant, so this is a no-op.
   */
  private def checkArchiveRestoreAllowed(implicit cc: ChangeContext): IOResult[Unit] = {
    ZIO
      .unless(cc.accessGrant == TenantAccessGrant.All) {
        Inconsistency(
          s"User '${cc.actor.name}' is not allowed to import or rollback an archive: this operation replaces the " +
          s"whole configuration and is restricted to administrators with access to all tenants (grant '*')."
        ).fail
      }
      .unit
  }

  // Clean a directory only if it exists, all exception are caught by the tryo
  private def cleanExistingDirectory(directory: File): IOResult[Unit] = {
    IOResult.attempt {
      if (directory.exists) FileUtils.cleanDirectory(directory)
      else ()
    }
  }

  override def exportAll(
      commiter: PersonIdent,
      modId:    ModificationId,
      actor:    EventActor,
      reason:   Option[String]
  )(implicit qc: QueryContext): IOResult[(GitArchiveId, NotArchivedElements)] = {
    for {
      saveCrs        <- exportRules(commiter, modId, actor, reason)
      saveUserLib    <- exportTechniqueLibrary(commiter, modId, actor, reason)
      saveGroups     <- exportGroupLibrary(commiter, modId, actor, reason)
      saveParameters <- exportParameters(commiter, modId, actor, reason)
      msg             = (FULL_ARCHIVE_TAG
                          + " Archive and tag groups, technique library, rules and parameters"
                          + (reason match {
                            case None    => ""
                            case Some(m) => ", reason: " + m
                          }))
      archiveAll     <- this.commitFullGitPathContentAndTag(commiter, msg)
      eventLogged    <- eventLogger.saveEventLog(modId, new ExportFullArchive(actor, archiveAll, reason))
    } yield {
      (archiveAll, saveUserLib._2)
    }
  }

  private def exportRuleCategories(
      commiter: PersonIdent,
      modId:    ModificationId,
      actor:    EventActor,
      reason:   Option[String]
  ) = {
    for {
      // Get Map of all categories grouped by parent categories
      categories  <- roRuleCategoryeRepository.getRootCategory()(using QueryContext.systemQC).map(_.childrenMap)
      cleanedRoot <- cleanExistingDirectory(gitRuleCategoryArchiver.getItemDirectory)
      _           <- ZIO.foreachDiscard(categories) {
                       case (parentCategories, cats) =>
                         // Archive each category
                         ZIO.foreach(cats) { category =>
                           gitRuleCategoryArchiver.archiveRuleCategory(category, parentCategories, gitCommit = None)
                         }
                     }
      commitId    <- gitRuleCategoryArchiver.commitRuleCategories(modId, commiter, reason)
    } yield {
      commitId
    }
  }

  override def exportRules(
      commiter: PersonIdent,
      modId:    ModificationId,
      actor:    EventActor,
      reason:   Option[String]
  ): IOResult[GitArchiveId] = {
    for {
      // Treat categories before treating Rules
      categories  <- exportRuleCategories(commiter, modId, actor, reason)
      rules       <- roRuleRepository.getAll(false)(using QueryContext.systemQC)
      cleanedRoot <- IOResult.attempt(FileUtils.cleanDirectory(gitRuleArchiver.getItemDirectory))
      saved       <- ZIO.foreach(rules.filterNot(_.isSystem))(rule => gitRuleArchiver.archiveRule(rule, None))
      commitId    <- gitRuleArchiver.commitRules(modId, commiter, reason)
      eventLogged <- eventLogger.saveEventLog(modId, new ExportRulesArchive(actor, commitId, reason))
    } yield {
      commitId
    }
  }

  override def exportTechniqueLibrary(
      commiter: PersonIdent,
      modId:    ModificationId,
      actor:    EventActor,
      reason:   Option[String]
  ): IOResult[(GitArchiveId, NotArchivedElements)] = {
    // export is a system-level operation, it sees all tenants
    for {
      catWithUPT  <- roDirectiveRepository.getActiveTechniqueByCategory(includeSystem = true)(using QueryContext.systemQC)
      // remove systems categories, we don't want to export them anymore
      okCatWithUPT = catWithUPT.toMap.collect {
                       // always include root category, even if it's a system one
                       case (categories, CategoryWithActiveTechniques(cat, upts))
                           if (cat.isSystem == false || categories.size <= 1) =>
                         (categories, CategoryWithActiveTechniques(cat, upts.filter(_.policyTypes.isSystem == false)))
                     }
      cleanedRoot <- IOResult.attempt(FileUtils.cleanDirectory(gitActiveTechniqueCategoryArchiver.getItemDirectory))

      savedItems <- exportElements(okCatWithUPT.toSeq)

      commitId    <- gitActiveTechniqueCategoryArchiver.commitActiveTechniqueLibrary(modId, commiter, reason)
      eventLogged <- eventLogger.saveEventLog(modId, new ExportTechniqueLibraryArchive(actor, commitId, reason))
    } yield {
      (commitId, savedItems)
    }
  }

  /*
   * strategy here:
   * - if the category archiving fails, we just record that and continue - that's not a big issue
   * - if an active technique fails, we don't go further to directive, and record that failure
   * - if a directive fails, we record that failure.
   * At the end, we can't have total failure for that part, so we don't have a IOResult
   */
  private def exportElements(
      elements: Seq[(List[ActiveTechniqueCategoryId], CategoryWithActiveTechniques)]
  ): IOResult[NotArchivedElements] = {
    ZIO.foldLeft(elements)(NotArchivedElements(Seq(), Seq(), Seq())) {
      case (notArchived, (categories, CategoryWithActiveTechniques(cat, activeTechniques))) =>
        // we try to save the category, and else record an error. It's a seq with at most one element
        val catInErrorIO: UIO[Seq[CategoryNotArchived]] = gitActiveTechniqueCategoryArchiver
          .archiveActiveTechniqueCategory(cat, categories.reverse.tail, gitCommit = None)
          .foldZIO(
            err => Seq(CategoryNotArchived(cat.id, err)).succeed,
            suc => Seq().succeed
          )

        // now, we try to save the active techniques - we only
        val activeTechniquesInErrorIO: UIO[Set[(Seq[ActiveTechniqueNotArchived], Seq[DirectiveNotArchived])]] = {
          ZIO.foreach(activeTechniques.filterNot(_.policyTypes.isSystem)) { activeTechnique =>
            gitActiveTechniqueArchiver
              .archiveActiveTechnique(activeTechnique, categories.reverse, gitCommit = None)
              .foldZIO(
                err =>
                  (
                    Seq(ActiveTechniqueNotArchived(activeTechnique.id, err)),
                    Seq.empty[DirectiveNotArchived]
                  ).succeed, // in case of success, we can still have directive not archived

                suc => (Seq.empty[ActiveTechniqueNotArchived], suc._2).succeed
              )
          }
        }
        for {
          catInError              <- catInErrorIO
          activeTechniquesInError <- activeTechniquesInErrorIO
        } yield {

          val (atNotArchived, dirNotArchived) = {
            activeTechniquesInError.foldLeft((Seq.empty[ActiveTechniqueNotArchived], Seq.empty[DirectiveNotArchived])) {
              case ((ats, dirs), (at, dir)) => (ats ++ at, dirs ++ dir)
            }
          }

          // now group all non archive for all categories
          NotArchivedElements(
            notArchived.categories ++ catInError,
            notArchived.activeTechniques ++ atNotArchived,
            notArchived.directives ++ dirNotArchived
          )
        }
    }
  }

  override def exportGroupLibrary(
      commiter: PersonIdent,
      modId:    ModificationId,
      actor:    EventActor,
      reason:   Option[String]
  )(implicit qc: QueryContext): IOResult[GitArchiveId] = {
    for {
      catWithGroups <- roGroupRepository.getGroupsByCategory(includeSystem = true)
      // remove systems categories, because we don't want them
      okCatWithGroup = catWithGroups.toMap.collect {
                         // always include root category, even if it's a system one
                         case (categories, CategoryAndNodeGroup(cat, groups))
                             if (cat.isSystem == false || categories.size <= 1) =>
                           (categories, CategoryAndNodeGroup(cat, groups.filter(_.isSystem == false)))
                       }
      _             <- IOResult.attempt(FileUtils.cleanDirectory(gitNodeGroupArchiver.getItemDirectory))
      _             <- ZIO.foreach(okCatWithGroup.toSeq) {
                         case (categories, CategoryAndNodeGroup(cat, groups)) =>
                           for {
                             // categories.tail is OK, as no category can have an empty path (id)
                             _ <-
                               gitNodeGroupArchiver.archiveNodeGroupCategory(cat, categories.reverse.tail, gitCommit = None)
                             _ <- ZIO.foreach(groups.toSeq.filterNot(_.isSystem)) { group =>
                                    gitNodeGroupArchiver.archiveNodeGroup(group, categories.reverse, gitCommit = None)
                                  }
                           } yield {
                             "OK"
                           }
                       }
      commitId      <- gitNodeGroupArchiver.commitGroupLibrary(modId, commiter, reason)
      _             <- eventLogger.saveEventLog(modId, new ExportGroupsArchive(actor, commitId, reason))
    } yield {
      commitId
    }
  }

  override def exportParameters(
      commiter: PersonIdent,
      modId:    ModificationId,
      actor:    EventActor,
      reason:   Option[String]
  ): IOResult[GitArchiveId] = {
    for {
      parameters <- roParameterRepository.getAllGlobalParameters()(using QueryContext.systemQC)
      _          <- IOResult.attempt(FileUtils.cleanDirectory(gitParameterArchiver.getItemDirectory))
      _          <- ZIO.foreach(parameters)(param => gitParameterArchiver.archiveParameter(param, None))
      commitId   <- gitParameterArchiver.commitParameters(modId, commiter, reason)
      _          <- eventLogger.saveEventLog(modId, new ExportParametersArchive(actor, commitId, reason))
    } yield {
      commitId
    }
  }
  ////////// Import //////////

  override def importAll(
      archiveId: GitCommitId,
      commiter:  PersonIdent
  )(implicit cc: ChangeContext): IOResult[GitCommitId] = {
    import cc.*
    useSemaphoreOrFail(
      for {
        _ <- checkArchiveRestoreAllowed
        _ <- GitArchiveLoggerPure.info("Importing full archive with id '%s'".format(archiveId.value))
        _ <- importRulesAndDeploy(archiveId, deploy = false)
        _ <- importTechniqueLibraryAndDeploy(archiveId, deploy = false)
        _ <- importGroupLibraryAndDeploy(archiveId, deploy = false)
        _ <- importParametersAndDeploy(archiveId)
        _ <- eventLogger.saveEventLog(modId, new ImportFullArchive(actor, archiveId, message))
        _ <- restoreCommitAtHead(
               commiter,
               "User %s requested full archive restoration to commit %s".format(actor.name, archiveId.value),
               archiveId,
               FullArchive,
               modId
             )
      } yield {
        asyncDeploymentAgent ! AutomaticStartDeployment(modId, actor)
        archiveId
      }
    )
  }

  override def importRules(
      archiveId: GitCommitId,
      commiter:  PersonIdent
  )(implicit cc: ChangeContext): ZIO[Any, RudderError, GitCommitId] = {
    import cc.*
    val commitMsg = "User %s requested rule archive restoration to commit %s".format(actor.name, archiveId.value)
    useSemaphoreOrFail(
      for {
        _ <- checkArchiveRestoreAllowed
        _ <- importRulesAndDeploy(archiveId)
        _ <- eventLogger.saveEventLog(modId, new ImportRulesArchive(actor, archiveId, message))
        _ <- restoreCommitAtHead(commiter, commitMsg, archiveId, PartialArchive.ruleArchive, modId)
      } yield {
        archiveId
      }
    )
  }

  private def importRuleCategories(archiveId: GitCommitId): IOResult[GitCommitId] = {
    for {
      _      <- GitArchiveLoggerPure.info("Importing rule categories archive with id '%s'".format(archiveId.value))
      parsed <- parseRuleCategories.getArchive(archiveId)
      _      <- importRuleCategoryLibrary.swapRuleCategory(parsed)
    } yield {
      archiveId
    }
  }

  private def importRulesAndDeploy(
      archiveId: GitCommitId,
      deploy:    Boolean = true
  )(implicit cc: ChangeContext): IOResult[GitCommitId] = {
    import cc.*
    for {
      _        <- GitArchiveLoggerPure.info("Importing rules archive with id '%s'".format(archiveId.value))
      _        <- importRuleCategories(archiveId)
      parsed   <- parseRules.getArchive(archiveId)
      imported <- woRuleRepository.swapRules(parsed)
      // try to clean
      _        <- woRuleRepository
                    .deleteSavedRuleArchiveId(imported)
                    .catchAll(err => GitArchiveLoggerPure.warn(s"Error when trying to delete saved archive of old rule: ${err.fullMsg}"))
      _        <- effectUioUnit(if (deploy) {
                    asyncDeploymentAgent ! AutomaticStartDeployment(modId, actor)
                  })
    } yield {
      if (deploy) {
        asyncDeploymentAgent ! AutomaticStartDeployment(modId, actor)
      }
      archiveId
    }
  }

  override def importTechniqueLibrary(
      archiveId: GitCommitId,
      commiter:  PersonIdent
  )(implicit cc: ChangeContext): IOResult[GitCommitId] = {
    import cc.*
    val commitMsg = "User %s requested directive archive restoration to commit %s".format(actor.name, archiveId.value)
    useSemaphoreOrFail(
      for {
        _ <- checkArchiveRestoreAllowed
        _ <- importTechniqueLibraryAndDeploy(archiveId)
        _ <- eventLogger.saveEventLog(modId, new ImportTechniqueLibraryArchive(actor, archiveId, message))
        _ <- restoreCommitAtHead(commiter, commitMsg, archiveId, TechniqueLibraryArchive, modId)
      } yield {
        archiveId
      }
    )
  }

  private def importTechniqueLibraryAndDeploy(
      archiveId: GitCommitId,
      deploy:    Boolean = true
  )(implicit cc: ChangeContext): IOResult[GitCommitId] = {
    for {
      _        <- GitArchiveLoggerPure.info(s"Importing technique library archive with id '${archiveId.value}'")
      parsed   <- parseActiveTechniqueLibrary.getArchive(archiveId)
      imported <- importTechniqueLibrary.swapActiveTechniqueLibrary(parsed)
    } yield {
      if (deploy) {
        asyncDeploymentAgent ! AutomaticStartDeployment(cc.modId, cc.actor)
      }
      archiveId
    }
  }

  override def importGroupLibrary(
      archiveId: GitCommitId,
      commiter:  PersonIdent
  )(implicit cc: ChangeContext): IOResult[GitCommitId] = {
    import cc.*
    val commitMsg = "User %s requested group archive restoration to commit %s".format(actor.name, archiveId.value)
    useSemaphoreOrFail(
      for {
        _ <- checkArchiveRestoreAllowed
        _ <- importGroupLibraryAndDeploy(archiveId)
        _ <- eventLogger.saveEventLog(modId, new ImportGroupsArchive(actor, archiveId, message))
        _ <- restoreCommitAtHead(commiter, commitMsg, archiveId, PartialArchive.groupArchive, modId)
      } yield {
        archiveId
      }
    )
  }

  private def importGroupLibraryAndDeploy(
      archiveId: GitCommitId,
      deploy:    Boolean = true
  )(implicit cc: ChangeContext): IOResult[GitCommitId] = {
    for {
      _        <- GitArchiveLoggerPure.info(s"Importing groups archive with id '${archiveId.value}'")
      parsed   <- parseGroupLibrary.getArchive(archiveId)
      imported <- importGroupLibrary.swapGroupLibrary(parsed)
      dynGroup <- updateDynamicGroups.updateAll(cc.modId).toIO
    } yield {
      if (deploy) {
        asyncDeploymentAgent ! AutomaticStartDeployment(cc.modId, cc.actor)
      }
      archiveId
    }
  }

  override def importParameters(
      archiveId: GitCommitId,
      commiter:  PersonIdent
  )(implicit cc: ChangeContext): ZIO[Any, RudderError, GitCommitId] = {
    import cc.*
    val commitMsg = "User %s requested Parameters archive restoration to commit %s".format(actor.name, archiveId.value)
    useSemaphoreOrFail(
      for {
        _ <- checkArchiveRestoreAllowed
        _ <- importParametersAndDeploy(archiveId)
        _ <- eventLogger.saveEventLog(modId, new ImportParametersArchive(actor, archiveId, message))
        _ <- restoreCommitAtHead(commiter, commitMsg, archiveId, PartialArchive.parameterArchive, modId)
      } yield {
        archiveId
      }
    )
  }

  private def importParametersAndDeploy(
      archiveId: GitCommitId,
      deploy:    Boolean = true
  )(implicit cc: ChangeContext): IOResult[GitCommitId] = {
    for {
      _        <- GitArchiveLoggerPure.info(s"Importing Parameters archive with id '${archiveId.value}'")
      parsed   <- parseGlobalParameters.getArchive(archiveId)
      imported <- woParameterRepository.swapParameters(parsed)
      // try to clean
      _        <- woParameterRepository
                    .deleteSavedParametersArchiveId(imported)
                    .catchAll(err =>
                      GitArchiveLoggerPure.warn(s"Error when trying to delete saved archive of old parameters: ${err.fullMsg}")
                    )
      _        <- effectUioUnit(if (deploy) {
                    asyncDeploymentAgent ! AutomaticStartDeployment(cc.modId, cc.actor)
                  })
    } yield {
      archiveId
    }
  }

  /*
   * Rollback, it acts like a full archive restoration
   * (restoring rules, groups, directives) but it is based on a git commit
   * linked to a modification made in the rudder UI.
   */

  override def rollback(
      archiveId:        GitCommitId,
      commiter:         PersonIdent,
      rollbackedEvents: Seq[EventLog],
      target:           EventLog,
      rollbackType:     String
  )(implicit cc: ChangeContext): IOResult[GitCommitId] = {
    import cc.*
    useSemaphoreOrFail(
      for {
        _ <- checkArchiveRestoreAllowed
        _ <- GitArchiveLoggerPure.info(s"Importing full archive with id '${archiveId.value}'")
        _ <- importRulesAndDeploy(archiveId, deploy = false)
        _ <- importTechniqueLibraryAndDeploy(archiveId, deploy = false)
        _ <- importGroupLibraryAndDeploy(archiveId, deploy = false)
        _ <- importParametersAndDeploy(archiveId)
        _ <- eventLogger.saveEventLog(modId, new Rollback(actor, rollbackedEvents, target, rollbackType, message))
        _ <- restoreCommitAtHead(
               commiter,
               "User %s requested a rollback to a previous configuration : %s".format(actor.name, archiveId.value),
               archiveId,
               FullArchive,
               modId
             )
      } yield {
        asyncDeploymentAgent ! AutomaticStartDeployment(modId, actor)
        archiveId
      }
    )
  }

  /**
   * Rollback the items (directives, groups, global parameters, rules, techniques) corresponding to given event logs
   * to their version at given commit: `archiveId` is already the commit to which we want to restore the item.
   *
   * Rollback must ensure that the git repo is consistent even for a single item, so use semaphore
   */
  override def rollbackItem(
      archiveId:        GitCommitId,
      commiter:         PersonIdent,
      rollbackedEvents: Seq[EventLog],
      target:           EventLog
  )(implicit cc: ChangeContext): IOResult[GitCommitId] = {
    import cc.*
    for {
      _ <- GitArchiveLoggerPure.info(s"Rolling back item to their state in commit '${archiveId.value}'")
      _ <- ZIO.foreachDiscard(rollbackedEvents)(ev => useSemaphoreOrFail(rollbackOneItem(archiveId, ev)))
      _ <- eventLogger.saveEventLog(modId, new Rollback(actor, rollbackedEvents, target, "item", message))
    } yield {
      asyncDeploymentAgent ! AutomaticStartDeployment(modId, actor)
      archiveId
    }
  }

  /*
   * Rolling back always needs item ID in repos, we can find it in the event log details, at specific XML selector
   *
   * Logic of reverting change, depends on event log type:
   * - reverting an addition is just a deletion
   * - reverting a deletion or modification is a restore of the item as it is
   *   - archive needs to be observed e.g. for category
   *   - and
   */
  private[xml] def rollbackOneItem(archiveId: GitCommitId, event: EventLog)(implicit cc: ChangeContext): IOResult[Unit] = {
    event match {
      case e: DirectiveEventLog =>
        for {
          sid <- rolledBackItemId(e, XML_TAG_DIRECTIVE)
          id  <- DirectiveId.parse(sid).toIO
          _   <- e match {
                   case _: AddDirective => woDirectiveRepository.delete(id.uid).unit
                   case _: DeleteDirective | _: ModifyDirective => restoreDirective(archiveId, id.uid)
                 }
        } yield ()

      case e: NodeGroupEventLog =>
        for {
          sid <- rolledBackItemId(e, XML_TAG_NODE_GROUP)
          id  <- NodeGroupId.parse(sid).toIO
          _   <- e match {
                   case _: AddNodeGroup => woGroupRepository.delete(id).unit
                   case _: DeleteNodeGroup | _: ModifyNodeGroup => restoreNodeGroup(archiveId, id)
                 }
        } yield ()

      case e: ParameterEventLog =>
        for {
          name <- rolledBackItemId(e, XML_TAG_GLOBAL_PARAMETER, idTag = "name")
          _    <- e match {
                    case _: AddGlobalParameter => deleteParameter(name)
                    case _: DeleteGlobalParameter | _: ModifyGlobalParameter => restoreParameter(archiveId, name)
                  }
        } yield ()

      case e: RuleEventLog =>
        for {
          sid <- rolledBackItemId(e, XML_TAG_RULE)
          // rules are archived at their default revision, drop any revision the event log may carry
          id  <- RuleId.parse(sid).map(r => RuleId(r.uid)).toIO
          _   <- e match {
                   case _: AddRule => woRuleRepository.delete(id).unit
                   case _: DeleteRule | _: ModifyRule => restoreRule(archiveId, id)
                 }
        } yield ()

      case e: EditorTechniqueEventLog =>
        for {
          id      <- rolledBackItemId(e, XML_TAG_EDITOR_TECHNIQUE, idTag = "id")
          version <- rolledBackItemId(e, XML_TAG_EDITOR_TECHNIQUE, idTag = "version")
          _       <- e match {
                       // deleteDirective = false: a rollback must stay scoped to that one item, so we don't
                       // cascade to the directives using the technique. Deletion fails if there are any.
                       case _: AddEditorTechnique => techniqueWriter.deleteTechnique(id, version, deleteDirective = false)
                       case _: DeleteEditorTechnique | _: ModifyEditorTechnique =>
                         restoreEditorTechnique(archiveId, BundleName(id), Version(version))
                     }
        } yield ()

      case _ =>
        GitArchiveLoggerPure.warn(
          s"Item rollback is not supported for event type '${event.eventType.serialize}', that event is ignored"
        )
    }
  }

  /*
   * All item event log details are of the form `<entry><directive ...><id>xxxx</id>...`, whatever
   * the change type: get back the identifier of the item the event is about.
   */
  private def rolledBackItemId(event: EventLog, itemTag: String, idTag: String = "id"): IOResult[String] = {
    (event.details \ itemTag \ idTag).headOption
      .map(_.text.trim)
      .notOptional(s"Missing <${idTag}> in the <${itemTag}> details of event log '${event.id.getOrElse("")}'")
  }

  private def restoreDirective(archiveId: GitCommitId, uid: DirectiveUid)(implicit cc: ChangeContext): IOResult[Unit] = {
    val rev = Revision(archiveId.value)
    for {
      (activeTechnique, directive) <-
        parseActiveTechniqueLibrary
          .getDirectiveRevision(uid, rev)
          .notOptional(
            s"Directive '${uid.value}' was not found in the archive for commit '${archiveId.value}', it can not be restored"
          )
      // the technique version in the archive should be kept as is
      restoredDirective             = directive
                                        .modify(_.id.rev)
                                        .setTo(GitVersion.DEFAULT_REV)
                                        .modify(_.techniqueVersion)
                                        .using(v => if (v.rev == rev) v.withRevision(GitVersion.DEFAULT_REV) else v)
      _                            <- woDirectiveRepository.saveDirective(activeTechnique.id, restoredDirective)
    } yield ()
  }

  private def restoreNodeGroup(archiveId: GitCommitId, id: NodeGroupId)(implicit cc: ChangeContext): IOResult[Unit] = {
    implicit val qc: QueryContext = cc.toQC
    for {
      group        <- parseGroupLibrary
                        .getGroupRevision(id.uid, Revision(archiveId.value))
                        .notOptional(
                          s"Group '${id.serialize}' was not found in the archive for commit '${archiveId.value}', it can not be restored"
                        )
      // same as for directives: the group must be restored in place, not as a copy frozen at the
      // revision we looked it up at
      restoredGroup = group.group.modify(_.id.rev).setTo(GitVersion.DEFAULT_REV)
      existing     <- roGroupRepository.getNodeGroupOpt(id)
      _            <- existing match {
                        case Some(_) => woGroupRepository.update(restoredGroup).unit
                        case None    => woGroupRepository.create(restoredGroup, group.categoryId).unit
                      }
    } yield ()
  }

  private def restoreParameter(archiveId: GitCommitId, name: String)(implicit cc: ChangeContext): IOResult[Unit] = {
    implicit val qc: QueryContext = cc.toQC
    for {
      archive  <- parseGlobalParameters.getArchive(archiveId)
      param    <-
        archive
          .find(_.name == name)
          .notOptional(
            s"Global parameter '${name}' was not found in the archive for commit '${archiveId.value}', it can not be restored"
          )
      existing <- roParameterRepository.getGlobalParameter(name)
      _        <- existing match {
                    case Some(_) => woParameterRepository.updateParameter(param).unit
                    case None    => woParameterRepository.saveParameter(param).unit
                  }
    } yield ()
  }

  /*
   * Deleting a parameter needs its provider, which is only known from the currently stored one.
   */
  private def deleteParameter(name: String)(implicit cc: ChangeContext): IOResult[Unit] = {
    implicit val qc: QueryContext = cc.toQC
    roParameterRepository.getGlobalParameter(name).flatMap {
      case Some(param) => woParameterRepository.delete(name, param.provider).unit
      case None        => ZIO.unit
    }
  }

  private def restoreRule(archiveId: GitCommitId, id: RuleId)(implicit cc: ChangeContext): IOResult[Unit] = {
    implicit val qc: QueryContext = cc.toQC
    for {
      rule        <- parseRules
                       .getRuleRevision(id.uid, Revision(archiveId.value))
                       .notOptional(
                         s"Rule '${id.serialize}' was not found in the archive for commit '${archiveId.value}', it can not be restored"
                       )
      // same as for directives: the rule must be restored in place, not as a copy frozen at the
      // revision we looked it up at
      restoredRule = rule.modify(_.id.rev).setTo(GitVersion.DEFAULT_REV)
      existing    <- roRuleRepository.getOpt(id)
      _           <- existing match {
                       case Some(_) => woRuleRepository.update(restoredRule).unit
                       case None    => woRuleRepository.create(restoredRule).unit
                     }
    } yield ()
  }

  /*
   * Techniques are restored with the `technique.yml` main file: read back that file at the
   * wanted commit and let the technique writer recompile and update
   */
  private def restoreEditorTechnique(archiveId: GitCommitId, id: BundleName, version: Version)(implicit
      cc: ChangeContext
  ): IOResult[Unit] = {
    val yamlPathEnd   = s"/${id.value}/${version.value}/${TechniqueFiles.yaml}"
    // editor techniques are archived under that directory of the config repo, see GitTechniqueArchiverImpl
    val techniquesDir = "techniques"

    for {
      treeId        <- GitFindUtils.findRevTreeFromRevString(gitRepo.db, archiveId.value)
      gitPaths      <- GitFindUtils.listFiles(gitRepo.db, treeId, List(techniquesDir), List(yamlPathEnd))
      gitPath       <- gitPaths.toList match {
                         case p :: Nil => p.succeed

                         case Nil =>
                           Inconsistency(
                             s"Technique '${id.value}/${version.value}' was not found in the archive for commit " +
                             s"'${archiveId.value}', it can not be restored"
                           ).fail

                         case several =>
                           Inconsistency(
                             s"There is more than one technique '${id.value}/${version.value}' in the archive for commit " +
                             s"'${archiveId.value}', it can not be restored: ${several.mkString(", ")}"
                           ).fail

                       }
      techniquePath <- EditorTechniquePath(better.files.File(gitPath)).notOptional(
                         s"'${gitPath}' is not a conventional technique path, it must be of the form " +
                         s"'.../{category}/{techniqueId}/{techniqueVersion}/${TechniqueFiles.yaml}'"
                       )
      content       <- GitFindUtils.getFileContent(gitRepo.db, treeId, gitPath) { is =>
                         IOResult.attempt(s"Error when reading '${gitPath}' in commit '${archiveId.value}'")(
                           IOUtils.toString(is, StandardCharsets.UTF_8)
                         )
                       }
      parsed        <- yamlTechniqueSerializer.yamlToEditorTechnique(content)
      technique     <- ZIO.fromEither(parsed.left.map(EditorTechniqueParsingError(techniquePath, content, _)))
      _             <- techniqueWriter.writeTechniqueAndUpdateLib(technique)
    } yield ()
  }

  override def getFullArchiveTags: IOResult[Map[Instant, GitArchiveId]] = this.getTags()

  // groups, technique library and rules may use
  // their own tag or a global one.

  override def getGroupLibraryTags: IOResult[Map[Instant, GitArchiveId]] = {
    for {
      globalTags <- this.getTags()
      groupsTags <- gitNodeGroupArchiver.getTags()
    } yield {
      globalTags ++ groupsTags
    }
  }

  override def getTechniqueLibraryTags: IOResult[Map[Instant, GitArchiveId]] = {
    for {
      globalTags    <- this.getTags()
      policyLibTags <- gitActiveTechniqueCategoryArchiver.getTags()
    } yield {
      globalTags ++ policyLibTags
    }
  }

  override def getRulesTags: IOResult[Map[Instant, GitArchiveId]] = {
    for {
      globalTags <- this.getTags()
      crTags     <- gitRuleArchiver.getTags()
    } yield {
      globalTags ++ crTags
    }
  }

  override def getParametersTags: IOResult[Map[Instant, GitArchiveId]] = {
    for {
      globalTags <- this.getTags()
      crTags     <- gitParameterArchiver.getTags()
    } yield {
      globalTags ++ crTags
    }
  }
}

/*
 * In a near future we should factorise code in archive manager to have only 2
 * implementation (Partial, Full) instead of 4 (All, groups, directives, rules)
 */
trait ArchiveMode extends Any {
  def configureRm(rmCmd:       RmCommand):       RmCommand
  def configureCheckout(coCmd: CheckoutCommand): CheckoutCommand
}

/**
 * Restore a part of the configuration repository
 * the directory is a path from the configuration git, so the path is
 * relative to git directory root.
 * To be counted as a directory the last character have to be a /.
 */
final case class PartialArchive(directory: String) extends AnyVal with ArchiveMode {
  def configureRm(rmCmd:       RmCommand):       RmCommand       = rmCmd.addFilepattern(directory)
  def configureCheckout(coCmd: CheckoutCommand): CheckoutCommand = coCmd.addPath(directory)
}

object PartialArchive {
  val groupArchive:     PartialArchive = PartialArchive("groups/")
  val ruleArchive:      PartialArchive = PartialArchive("rules/")
  val directiveArchive: PartialArchive = PartialArchive("directives/")
  val ncfArchive:       PartialArchive = PartialArchive("ncf/")
  val parameterArchive: PartialArchive = PartialArchive("parameters/")
}

import com.normation.rudder.repository.xml.PartialArchive.*

case object TechniqueLibraryArchive extends ArchiveMode {
  def configureRm(rmCmd: RmCommand): RmCommand = directiveArchive.configureRm(ncfArchive.configureRm(rmCmd))
  def configureCheckout(coCmd: CheckoutCommand): CheckoutCommand =
    directiveArchive.configureCheckout(ncfArchive.configureCheckout(coCmd))
}
case object FullArchive             extends ArchiveMode {

  def configureRm(rmCmd: RmCommand): RmCommand = {
    TechniqueLibraryArchive.configureRm(
      ruleArchive.configureRm(
        groupArchive.configureRm(
          parameterArchive.configureRm(rmCmd)
        )
      )
    )
  }

  def configureCheckout(coCmd: CheckoutCommand): CheckoutCommand = {
    TechniqueLibraryArchive.configureCheckout(
      ruleArchive.configureCheckout(
        groupArchive.configureCheckout(
          parameterArchive.configureCheckout(coCmd)
        )
      )
    )
  }
}
