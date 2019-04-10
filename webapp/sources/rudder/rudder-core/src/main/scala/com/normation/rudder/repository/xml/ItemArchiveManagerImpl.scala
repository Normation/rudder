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

import org.apache.commons.io.FileUtils
import com.normation.rudder.repository._
import com.normation.utils.Control._
import com.normation.cfclerk.services.GitRepositoryProvider
import com.normation.rudder.domain.Constants.FULL_ARCHIVE_TAG
import org.joda.time.DateTime
import org.eclipse.jgit.lib.PersonIdent
import com.normation.cfclerk.services.GitRevisionProvider
import com.normation.eventlog.EventActor
import com.normation.rudder.domain.eventlog._
import com.normation.rudder.batch.AsyncDeploymentActor
import com.normation.rudder.batch.AutomaticStartDeployment
import com.normation.rudder.domain.policies.ActiveTechniqueCategoryId
import org.eclipse.jgit.api._
import com.normation.eventlog.ModificationId
import com.normation.eventlog.EventLog
import com.normation.rudder.rule.category.RoRuleCategoryRepository
import com.normation.rudder.rule.category.GitRuleCategoryArchiver
import java.io.File

import com.normation.NamedZioLogger
import com.normation.rudder.rule.category.ImportRuleCategoryLibrary
import com.normation.rudder.repository.EventLogRepository
import com.normation.rudder.services.queries.DynGroupUpdaterService

import com.normation.errors._
import scalaz.zio._
import scalaz.zio.syntax._

class ItemArchiveManagerImpl(
    roRuleRepository                  : RoRuleRepository
  , woRuleRepository                  : WoRuleRepository
  , roRuleCategoryeRepository         : RoRuleCategoryRepository
  , uptRepository                     : RoDirectiveRepository
  , groupRepository                   : RoNodeGroupRepository
  , roParameterRepository             : RoParameterRepository
  , woParameterRepository             : WoParameterRepository
  , override val gitRepo              : GitRepositoryProvider
  , revisionProvider                  : GitRevisionProvider
  , gitRuleArchiver                   : GitRuleArchiver
  , gitRuleCategoryArchiver           : GitRuleCategoryArchiver
  , gitActiveTechniqueCategoryArchiver: GitActiveTechniqueCategoryArchiver
  , gitActiveTechniqueArchiver        : GitActiveTechniqueArchiver
  , gitNodeGroupArchiver              : GitNodeGroupArchiver
  , gitParameterArchiver              : GitParameterArchiver
  , parseRules                        : ParseRules
  , parseActiveTechniqueLibrary       : ParseActiveTechniqueLibrary
  , parseGlobalParameters             : ParseGlobalParameters
  , parseRuleCategories               : ParseRuleCategories
  , importTechniqueLibrary            : ImportTechniqueLibrary
  , parseGroupLibrary                 : ParseGroupLibrary
  , importGroupLibrary                : ImportGroupLibrary
  , importRuleCategoryLibrary         : ImportRuleCategoryLibrary
  , eventLogger                       : EventLogRepository
  , asyncDeploymentAgent              : AsyncDeploymentActor
  , gitModificationRepo               : GitModificationRepository
  , updateDynamicGroups               : DynGroupUpdaterService
) extends
  ItemArchiveManager with
  NamedZioLogger with
  GitArchiverFullCommitUtils
{

  override val tagPrefix = "archives/full/"
  override val relativePath = "."
  override val gitModificationRepository = gitModificationRepo
  ///// implementation /////

  // Clean a directory only if it exists, all exception are catched by the tryo
  private[this] def cleanExistingDirectory (directory : File) : IOResult[Unit] = {
    IOResult.effect {
      if (directory.exists) FileUtils.cleanDirectory(directory)
      else ()
    }
  }

  override def exportAll(commiter:PersonIdent, modId:ModificationId, actor:EventActor, reason:Option[String], includeSystem:Boolean = false): IOResult[(GitArchiveId, NotArchivedElements)] = {
    for {
      saveCrs        <- exportRules(commiter, modId, actor, reason, includeSystem)
      saveUserLib    <- exportTechniqueLibrary(commiter, modId, actor, reason, includeSystem)
      saveGroups     <- exportGroupLibrary(commiter, modId, actor, reason, includeSystem)
      saveParameters <- exportParameters(commiter, modId, actor, reason, includeSystem)
      msg            =  (  FULL_ARCHIVE_TAG
                      + " Archive and tag groups, technique library, rules and parameters"
                      + (reason match {
                          case None => ""
                          case Some(m) => ", reason: " + m
                        })
                     )
      archiveAll  <- this.commitFullGitPathContentAndTag(commiter, msg)
      eventLogged <- eventLogger.saveEventLog(modId, new ExportFullArchive(actor, archiveAll, reason))
    } yield {
      (archiveAll,saveUserLib._2)
    }
  }

  private[this] def exportRuleCategories(commiter:PersonIdent, modId:ModificationId, actor:EventActor, reason:Option[String]) = {
    for {
      // Get Map of all categories grouped by parent categories
      categories  <- roRuleCategoryeRepository.getRootCategory.map(_.childrenMap)
      cleanedRoot <- cleanExistingDirectory(gitRuleCategoryArchiver.getRootDirectory)
      saved       <- ZIO.foreach(categories) {
                       case (parentCategories, categories ) =>
                         // Archive each category
                         ZIO.foreach(categories) { category =>
                             gitRuleCategoryArchiver.archiveRuleCategory(category,parentCategories, gitCommit = None)
                         }
                       }
      commitId    <- gitRuleCategoryArchiver.commitRuleCategories(modId, commiter, reason)
    } yield {
      commitId
    }
  }
  override def exportRules(commiter:PersonIdent, modId:ModificationId, actor:EventActor, reason:Option[String], includeSystem:Boolean = false): IOResult[GitArchiveId] = {
    for {
      // Treat categories before treating Rules
      categories  <- exportRuleCategories(commiter, modId, actor, reason)
      rules       <- roRuleRepository.getAll(false)
      cleanedRoot <- IOResult.effect( FileUtils.cleanDirectory(gitRuleArchiver.getRootDirectory) )
      saved       <- ZIO.foreach(rules.filterNot(_.isSystem)) { rule =>
                       gitRuleArchiver.archiveRule(rule, None)
                     }
      commitId    <- gitRuleArchiver.commitRules(modId, commiter, reason)
      eventLogged <- eventLogger.saveEventLog(modId, new ExportRulesArchive(actor,commitId, reason))
    } yield {
      commitId
    }
  }

  override def exportTechniqueLibrary(commiter:PersonIdent, modId:ModificationId, actor:EventActor, reason:Option[String], includeSystem:Boolean = false): IOResult[(GitArchiveId, NotArchivedElements)] = {
    //case class SavedDirective( saved:Seq[String, ])

    for {
      catWithUPT   <- uptRepository.getActiveTechniqueByCategory(includeSystem = true)
      //remove systems categories, we don't want to export them anymore
      okCatWithUPT =  catWithUPT.collect {
                          //always include root category, even if it's a system one
                          case (categories, CategoryWithActiveTechniques(cat, upts)) if(cat.isSystem == false || categories.size <= 1) =>
                            (categories, CategoryWithActiveTechniques(cat, upts.filter( _.isSystem == false )))
                      }
      cleanedRoot <- IOResult.effect( FileUtils.cleanDirectory(gitActiveTechniqueCategoryArchiver.getRootDirectory) )

      savedItems  <- exportElements(okCatWithUPT.toSeq)

      commitId    <- gitActiveTechniqueCategoryArchiver.commitActiveTechniqueLibrary(modId, commiter, reason)
      eventLogged <- eventLogger.saveEventLog(modId, new ExportTechniqueLibraryArchive(actor,commitId, reason))
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
  private[this] def exportElements(elements: Seq[(List[ActiveTechniqueCategoryId], CategoryWithActiveTechniques)]) : IOResult[NotArchivedElements] = {
    ZIO.foldLeft(elements)(NotArchivedElements( Seq(), Seq(), Seq())) { case (notArchived, (categories, CategoryWithActiveTechniques(cat, activeTechniques))) =>

      //we try to save the category, and else record an error. It's a seq with at most one element
      val catInErrorIO = gitActiveTechniqueCategoryArchiver.archiveActiveTechniqueCategory(cat,categories.reverse.tail, gitCommit = None).foldM(
        err => Seq(CategoryNotArchived(cat.id, err)).succeed
      , suc => Seq().succeed
      )

      //now, we try to save the active techniques - we only
      val activeTechniquesInErrorIO = ZIO.foreach(activeTechniques.filterNot(_.isSystem)) { activeTechnique =>
        gitActiveTechniqueArchiver.archiveActiveTechnique(activeTechnique,categories.reverse, gitCommit = None).foldM(
          err => (Seq(ActiveTechniqueNotArchived(activeTechnique.id, err)), Seq.empty[DirectiveNotArchived]).succeed
          // in case of success, we can still have directive not archived
        , suc => (Seq.empty[ActiveTechniqueNotArchived], suc._2).succeed
        )
      }
      for {
        catInError              <- catInErrorIO
        activeTechniquesInError <- activeTechniquesInErrorIO
      } yield {

        val (atNotArchived, dirNotArchived) = ( (Seq.empty[ActiveTechniqueNotArchived], Seq.empty[DirectiveNotArchived]) /: activeTechniquesInError) {
          case ( (ats,dirs) , (at,dir)  ) => (ats ++ at, dirs ++ dir)
        }

        // now group all non archive for all categories
        NotArchivedElements(notArchived.categories ++ catInError, notArchived.activeTechniques ++ atNotArchived, notArchived.directives ++ dirNotArchived)
      }
    }
  }

  override def exportGroupLibrary(commiter:PersonIdent, modId:ModificationId, actor:EventActor, reason:Option[String], includeSystem:Boolean = false): IOResult[GitArchiveId] = {
    for {
      catWithGroups   <- groupRepository.getGroupsByCategory(includeSystem = true)
      //remove systems categories, because we don't want them
      okCatWithGroup  =   catWithGroups.collect {
                            //always include root category, even if it's a system one
                            case (categories, CategoryAndNodeGroup(cat, groups)) if(cat.isSystem == false || categories.size <= 1) =>
                              (categories, CategoryAndNodeGroup(cat, groups.filter( _.isSystem == false )))
                         }
      cleanedRoot     <- IOResult.effect( FileUtils.cleanDirectory(gitNodeGroupArchiver.getRootDirectory) )
      savedItems      <- ZIO.foreach(okCatWithGroup.toSeq) { case (categories, CategoryAndNodeGroup(cat, groups)) =>
                           for {
                             //categories.tail is OK, as no category can have an empty path (id)
                             savedCat    <- gitNodeGroupArchiver.archiveNodeGroupCategory(cat,categories.reverse.tail, gitCommit = None)
                             savedgroups <- ZIO.foreach(groups.toSeq.filterNot(_.isSystem)) { group =>
                                              gitNodeGroupArchiver.archiveNodeGroup(group,categories.reverse, gitCommit = None)
                                            }
                           } yield {
                             "OK"
                           }
                         }
      commitId        <- gitNodeGroupArchiver.commitGroupLibrary(modId, commiter, reason)
      eventLogged     <- eventLogger.saveEventLog(modId, new ExportGroupsArchive(actor,commitId, reason))
    } yield {
      commitId
    }
  }

  override def exportParameters(commiter:PersonIdent, modId: ModificationId, actor:EventActor, reason:Option[String], includeSystem:Boolean = false) : IOResult[GitArchiveId] = {
    for {
      parameters  <- roParameterRepository.getAllGlobalParameters()
      cleanedRoot <- IOResult.effect( FileUtils.cleanDirectory(gitParameterArchiver.getRootDirectory) )
      saved       <- ZIO.foreach(parameters) { param =>
                       gitParameterArchiver.archiveParameter(param, None)
                     }
      commitId    <- gitParameterArchiver.commitParameters(modId, commiter, reason)
      eventLogged <- eventLogger.saveEventLog(modId, new ExportParametersArchive(actor,commitId, reason))
    } yield {
      commitId
    }
  }
  ////////// Import //////////

  override def importAll(archiveId:GitCommitId, commiter:PersonIdent, modId:ModificationId, actor:EventActor, reason:Option[String], includeSystem:Boolean = false) : IOResult[GitCommitId] = {
    for {
      _           <- logPure.info("Importing full archive with id '%s'".format(archiveId.value))
      rules       <- importRulesAndDeploy(archiveId, modId, actor, reason, includeSystem, false)
      userLib     <- importTechniqueLibraryAndDeploy(archiveId, modId, actor, reason, includeSystem, false)
      groupLIb    <- importGroupLibraryAndDeploy(archiveId, modId, actor, reason, includeSystem, false)
      parameters  <- importParametersAndDeploy(archiveId, modId, actor, reason, false)
      eventLogged <- eventLogger.saveEventLog(modId,new ImportFullArchive(actor,archiveId, reason))
      commit      <- restoreCommitAtHead(commiter,"User %s requested full archive restoration to commit %s".format(actor.name,archiveId.value),archiveId,FullArchive,modId)
    } yield {

      asyncDeploymentAgent ! AutomaticStartDeployment(modId, actor)
      archiveId
    }
  }

  override def importRules(archiveId:GitCommitId, commiter:PersonIdent, modId:ModificationId, actor:EventActor, reason:Option[String], includeSystem:Boolean = false) = {
    val commitMsg = "User %s requested rule archive restoration to commit %s".format(actor.name,archiveId.value)
    for {

    rulesArchiveId <- importRulesAndDeploy(archiveId, modId, actor, reason, includeSystem)
    eventLogged    <- eventLogger.saveEventLog(modId,new ImportRulesArchive(actor,archiveId, reason))
    commit         <- restoreCommitAtHead(commiter,commitMsg,archiveId,RuleArchive,modId)
    } yield
      archiveId
  }

  private[this] def importRuleCategories(archiveId:GitCommitId) : IOResult[GitCommitId] = {
    for {
      _         <- logPure.info("Importing rule categories archive with id '%s'".format(archiveId.value))
      parsed    <- parseRuleCategories.getArchive(archiveId)
      imported  <- importRuleCategoryLibrary.swapRuleCategory(parsed)
    } yield {
      archiveId
    }
  }

  private[this] def importRulesAndDeploy(archiveId:GitCommitId, modId:ModificationId, actor:EventActor, reason:Option[String], includeSystem:Boolean, deploy:Boolean = true) : IOResult[GitCommitId] = {
    for {
      _          <- logPure.info("Importing rules archive with id '%s'".format(archiveId.value))
      categories <- importRuleCategories(archiveId)
      parsed     <- parseRules.getArchive(archiveId)
      imported   <- woRuleRepository.swapRules(parsed)
      //try to clean
      _          <- woRuleRepository.deleteSavedRuleArchiveId(imported).catchAll(err =>
                      logPure.warn(s"Error when trying to delete saved archive of old rule: ${err.fullMsg}")
                    )
      _          <- IOResult.effect(if(deploy) { asyncDeploymentAgent ! AutomaticStartDeployment(modId, actor)}).run.void
    } yield {
      if(deploy) { asyncDeploymentAgent ! AutomaticStartDeployment(modId, actor) }
      archiveId
    }
  }

  override def importTechniqueLibrary(archiveId:GitCommitId, commiter:PersonIdent, modId:ModificationId, actor:EventActor, reason:Option[String], includeSystem:Boolean) : IOResult[GitCommitId] = {
    val commitMsg = "User %s requested directive archive restoration to commit %s".format(actor.name,archiveId.value)
    for {
      directivesArchiveId <- importTechniqueLibraryAndDeploy(archiveId, modId, actor, reason, includeSystem)
      eventLogged         <- eventLogger.saveEventLog(modId, new ImportTechniqueLibraryArchive(actor,archiveId, reason))
      commit              <- restoreCommitAtHead(commiter,commitMsg,archiveId,TechniqueLibraryArchive,modId)
    } yield
      archiveId
  }
  private[this] def importTechniqueLibraryAndDeploy(archiveId:GitCommitId, modId:ModificationId, actor:EventActor, reason:Option[String], includeSystem:Boolean, deploy:Boolean = true) : IOResult[GitCommitId] = {
      for {
        _        <- logPure.info(s"Importing technique library archive with id '${archiveId.value}'")
        parsed   <- parseActiveTechniqueLibrary.getArchive(archiveId)
        imported <- importTechniqueLibrary.swapActiveTechniqueLibrary(parsed, includeSystem)
      } yield {
        if(deploy) { asyncDeploymentAgent ! AutomaticStartDeployment(modId, actor) }
        archiveId
      }
  }

  override def importGroupLibrary(archiveId:GitCommitId, commiter:PersonIdent, modId:ModificationId, actor:EventActor, reason:Option[String], includeSystem:Boolean) : IOResult[GitCommitId] = {
    val commitMsg = "User %s requested group archive restoration to commit %s".format(actor.name,archiveId.value)
    for {
      groupsArchiveId <- importGroupLibraryAndDeploy(archiveId, modId, actor, reason, includeSystem)
      eventLogged     <- eventLogger.saveEventLog(modId, new ImportGroupsArchive(actor,archiveId, reason))
      commit          <- restoreCommitAtHead(commiter,commitMsg,archiveId,GroupArchive,modId)
    } yield
      archiveId
  }

  private[this] def importGroupLibraryAndDeploy(archiveId:GitCommitId, modId:ModificationId, actor:EventActor, reason:Option[String], includeSystem:Boolean, deploy:Boolean = true) : IOResult[GitCommitId] = {
      for {
        _        <- logPure.info(s"Importing groups archive with id '${archiveId.value}'")
        parsed   <- parseGroupLibrary.getArchive(archiveId)
        imported <- importGroupLibrary.swapGroupLibrary(parsed, includeSystem)
        dynGroup <- updateDynamicGroups.updateAll(modId,actor,reason)
      } yield {
        if(deploy) { asyncDeploymentAgent ! AutomaticStartDeployment(modId, actor) }
        archiveId
      }
  }

  override def importParameters(archiveId:GitCommitId, commiter:PersonIdent, modId:ModificationId, actor:EventActor, reason:Option[String], includeSystem:Boolean = false) = {
    val commitMsg = "User %s requested Parameters archive restoration to commit %s".format(actor.name,archiveId.value)
    for {
    parametersArchiveId <- importParametersAndDeploy(archiveId, modId, actor, reason, includeSystem)
    eventLogged    <- eventLogger.saveEventLog(modId,new ImportParametersArchive(actor,archiveId, reason))
    commit         <- restoreCommitAtHead(commiter,commitMsg,archiveId,ParameterArchive,modId)
    } yield
      archiveId
  }

  private[this] def importParametersAndDeploy(archiveId:GitCommitId, modId:ModificationId, actor:EventActor, reason:Option[String], includeSystem:Boolean, deploy:Boolean = true) : IOResult[GitCommitId] = {
    for {
      _        <- logPure.info(s"Importing Parameters archive with id '${archiveId.value}'")
      parsed   <- parseGlobalParameters.getArchive(archiveId)
      imported <- woParameterRepository.swapParameters(parsed)
      //try to clean
      _        <- woParameterRepository.deleteSavedParametersArchiveId(imported).catchAll(err =>
                    logPure.warn(s"Error when trying to delete saved archive of old parameters: ${err.fullMsg}")
                  )
      _        <- IOResult.effect(if(deploy) { asyncDeploymentAgent ! AutomaticStartDeployment(modId, actor)}).run.void
    } yield {
      archiveId
    }
  }

  /*
   * Rollback, it acts like a full archive restoration
   * (restoring rules, groups, directives) but it is based on a git commit
   * linked to a modification made in the rudder UI.
   */

  override def rollback(archiveId:GitCommitId, commiter:PersonIdent, modId:ModificationId, actor:EventActor, reason:Option[String],  rollbackedEvents :Seq[EventLog], target:EventLog, rollbackType:String, includeSystem:Boolean = false) : IOResult[GitCommitId] = {
    for {
      _           <- logPure.info(s"Importing full archive with id '${archiveId.value}'")
      rules       <- importRulesAndDeploy(archiveId, modId, actor, reason, includeSystem, false)
      userLib     <- importTechniqueLibraryAndDeploy(archiveId, modId, actor, reason, includeSystem, false)
      groupLIb    <- importGroupLibraryAndDeploy(archiveId, modId, actor, reason, includeSystem, false)
      parameters  <- importParametersAndDeploy(archiveId, modId, actor, reason, false)
      eventLogged <- eventLogger.saveEventLog(modId,new Rollback(actor,rollbackedEvents, target, rollbackType, reason))
      commit      <- restoreCommitAtHead(commiter,"User %s requested a rollback to a previous configuration : %s".format(actor.name,archiveId.value),archiveId,FullArchive,modId)
    } yield {
      asyncDeploymentAgent ! AutomaticStartDeployment(modId, actor)
      archiveId
    }
  }

  override def getFullArchiveTags : IOResult[Map[DateTime,GitArchiveId]] = this.getTags()

  // groups, technique library and rules may use
  // their own tag or a global one.

  override def getGroupLibraryTags : IOResult[Map[DateTime,GitArchiveId]] = {
    for {
      globalTags <- this.getTags()
      groupsTags <- gitNodeGroupArchiver.getTags()
    } yield {
      globalTags ++ groupsTags
    }
  }

  override def getTechniqueLibraryTags : IOResult[Map[DateTime,GitArchiveId]] = {
    for {
      globalTags    <- this.getTags()
      policyLibTags <- gitActiveTechniqueCategoryArchiver.getTags()
    } yield {
      globalTags ++ policyLibTags
    }
  }

  override def getRulesTags : IOResult[Map[DateTime,GitArchiveId]] = {
    for {
      globalTags <- this.getTags()
      crTags     <- gitRuleArchiver.getTags()
    } yield {
      globalTags ++ crTags
    }
  }

  override def getParametersTags : IOResult[Map[DateTime,GitArchiveId]] = {
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
trait ArchiveMode {
  def configureRm(rmCmd:RmCommand):RmCommand
  def configureCheckout(coCmd:CheckoutCommand):CheckoutCommand
}
/**
 * Restore a part of the configuration repository
 * the directory is a path from the configuration git, so the path is
 * relative to git directory root.
 * To be counted as a directory the last character have to be a /.
 */
case class PartialArchive(directory:String) extends ArchiveMode {
  def configureRm(rmCmd:RmCommand) = rmCmd.addFilepattern(directory)
  def configureCheckout(coCmd:CheckoutCommand) = coCmd.addPath(directory)
}

object GroupArchive     extends PartialArchive("groups/")
object RuleArchive      extends PartialArchive("rules/")
object DirectiveArchive extends PartialArchive("directives/")
object ncfArchive       extends PartialArchive("ncf/")
object ParameterArchive extends PartialArchive("parameters/")

case object TechniqueLibraryArchive extends ArchiveMode {

  def configureRm(rmCmd:RmCommand) = DirectiveArchive.configureRm(ncfArchive.configureRm(rmCmd))

  def configureCheckout(coCmd:CheckoutCommand) = DirectiveArchive.configureCheckout(ncfArchive.configureCheckout(coCmd))
}
case object FullArchive extends ArchiveMode {

  def configureRm(rmCmd:RmCommand) =
    TechniqueLibraryArchive.configureRm(
      RuleArchive.configureRm(
        GroupArchive.configureRm(
          ParameterArchive.configureRm(rmCmd)
        )
    ) )

  def configureCheckout(coCmd:CheckoutCommand) =
    TechniqueLibraryArchive.configureCheckout(
      RuleArchive.configureCheckout(
        GroupArchive.configureCheckout(
          ParameterArchive.configureCheckout(coCmd)
        )
    ) )
}
