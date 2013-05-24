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

package com.normation.rudder.repository.xml

import org.apache.commons.io.FileUtils
import com.normation.rudder.repository._
import com.normation.utils.Control._
import net.liftweb.common._
import net.liftweb.util.Helpers.tryo
import com.normation.cfclerk.services.GitRepositoryProvider
import com.normation.rudder.domain.Constants.FULL_ARCHIVE_TAG
import org.eclipse.jgit.revwalk.RevTag
import org.joda.time.DateTime
import org.eclipse.jgit.lib.PersonIdent
import com.normation.cfclerk.services.GitRevisionProvider
import com.normation.eventlog.EventActor
import com.normation.rudder.domain.eventlog._
import com.normation.rudder.batch.AsyncDeploymentAgent
import com.normation.rudder.batch.AutomaticStartDeployment
import com.normation.rudder.domain.policies.ActiveTechniqueCategoryId
import com.normation.rudder.domain.policies.ActiveTechniqueId
import org.eclipse.jgit.api._
import com.normation.eventlog.ModificationId
import com.normation.eventlog.EventLog

class ItemArchiveManagerImpl(
    ruleRepository                    : RuleRepository
  , uptRepository                     : ActiveTechniqueRepository
  , groupRepository                   : NodeGroupRepository
  , override val gitRepo              : GitRepositoryProvider
  , revisionProvider                  : GitRevisionProvider
  , gitRuleArchiver                   : GitRuleArchiver
  , gitActiveTechniqueCategoryArchiver: GitActiveTechniqueCategoryArchiver
  , gitActiveTechniqueArchiver        : GitActiveTechniqueArchiver
  , gitNodeGroupCategoryArchiver      : GitNodeGroupCategoryArchiver
  , gitNodeGroupArchiver              : GitNodeGroupArchiver
  , parseRules                        : ParseRules
  , parseActiveTechniqueLibrary       : ParseActiveTechniqueLibrary
  , importTechniqueLibrary            : ImportTechniqueLibrary
  , parseGroupLibrary                 : ParseGroupLibrary
  , importGroupLibrary                : ImportGroupLibrary
  , eventLogger                       : EventLogRepository
  , asyncDeploymentAgent              : AsyncDeploymentAgent
  , gitModificationRepo         : GitModificationRepository
) extends 
  ItemArchiveManager with 
  Loggable with 
  GitArchiverFullCommitUtils 
{
  
  override val tagPrefix = "archives/full/"
  override val relativePath = "."
  override val gitModificationRepository = gitModificationRepo
  ///// implementation /////
  
  override def exportAll(commiter:PersonIdent, modId:ModificationId, actor:EventActor, reason:Option[String], includeSystem:Boolean = false): Box[(GitArchiveId, NotArchivedElements)] = { 
    for {
      saveCrs     <- exportRulesAndDeploy(commiter, modId, actor, reason, includeSystem, false)
      saveUserLib <- exportTechniqueLibraryAndDeploy(commiter, modId, actor, reason, includeSystem, false)
      saveGroups  <- exportGroupLibraryAndDeploy(commiter, modId, actor, reason, includeSystem, false)
      val msg     =  (  FULL_ARCHIVE_TAG 
                      + " Archive and tag groups, technique library and rules" 
                      + (reason match {
                          case None => ""
                          case Some(m) => ", reason: " + m
                        })
                     )
      archiveAll  <- this.commitFullGitPathContentAndTag(commiter, msg)
      eventLogged <- eventLogger.saveEventLog(modId, new ExportFullArchive(actor, archiveAll, reason))
    } yield {
      asyncDeploymentAgent ! AutomaticStartDeployment(modId, actor)
      (archiveAll,saveUserLib._2)
    }
  }

  
  override def exportRules(commiter:PersonIdent, modId:ModificationId, actor:EventActor, reason:Option[String], includeSystem:Boolean = false): Box[GitArchiveId] =
    exportRulesAndDeploy(commiter, modId, actor, reason, includeSystem)
    
  private[this] def exportRulesAndDeploy(commiter:PersonIdent, modId:ModificationId, actor:EventActor, reason:Option[String], includeSystem:Boolean = false, deploy:Boolean = true): Box[GitArchiveId] = { 
    for {
      rules       <- ruleRepository.getAll(false)
      cleanedRoot <- tryo { FileUtils.cleanDirectory(gitRuleArchiver.getRootDirectory) }
      saved       <- sequence(rules.filterNot(_.isSystem)) { rule =>
                       gitRuleArchiver.archiveRule(rule, None)
                     }
      commitId    <- gitRuleArchiver.commitRules(modId, commiter, reason)
      eventLogged <- eventLogger.saveEventLog(modId, new ExportRulesArchive(actor,commitId, reason))
    } yield {
      if(deploy) { asyncDeploymentAgent ! AutomaticStartDeployment(modId, actor) }
      commitId
    }
  }
  
  override def exportTechniqueLibrary(commiter:PersonIdent, modId:ModificationId, actor:EventActor, reason:Option[String], includeSystem:Boolean = false): Box[(GitArchiveId, NotArchivedElements)] =
    exportTechniqueLibraryAndDeploy(commiter, modId, actor, reason, includeSystem) 
    

  //TODO : remove include system because we don't want to include system anymore
  private[this] def exportTechniqueLibraryAndDeploy(commiter:PersonIdent, modId:ModificationId, actor:EventActor, reason:Option[String], includeSystem:Boolean = false, deploy:Boolean = true): Box[(GitArchiveId, NotArchivedElements)] = { 
    //case class SavedDirective( saved:Seq[String, ])
    
    for { 
      catWithUPT   <- uptRepository.getActiveTechniqueByCategory(includeSystem = true)
      //remove systems categories, we don't want to export them anymore
      okCatWithUPT =  catWithUPT.collect {
                          //always include root category, even if it's a system one
                          case (categories, CategoryWithActiveTechniques(cat, upts)) if(cat.isSystem == false || categories.size <= 1) => 
                            (categories, CategoryWithActiveTechniques(cat, upts.filter( _.isSystem == false )))
                      }
      cleanedRoot <- tryo { FileUtils.cleanDirectory(gitActiveTechniqueCategoryArchiver.getRootDirectory) }

      savedItems  = exportElements(okCatWithUPT.toSeq)
      
      commitId    <- gitActiveTechniqueCategoryArchiver.commitActiveTechniqueLibrary(modId, commiter, reason)
      eventLogged <- eventLogger.saveEventLog(modId, new ExportTechniqueLibraryArchive(actor,commitId, reason))
    } yield {
      if(deploy) { asyncDeploymentAgent ! AutomaticStartDeployment(modId, actor) }
      (commitId, savedItems)
    }
  }
  
  /*
   * strategy here: 
   * - if the category archiving fails, we just record that and continue - that's not a big issue
   * - if an active technique fails, we don't go further to directive, and record that failure
   * - if a directive fails, we record that failure. 
   * At the end, we can't have total failure for that part, so we don't have a box
   */
  private[this] def exportElements(elements: Seq[(List[ActiveTechniqueCategoryId],CategoryWithActiveTechniques)]) : NotArchivedElements = {
    val byCategories = for {
      (categories, CategoryWithActiveTechniques(cat, activeTechniques)) <- elements
    } yield {
      //we try to save the category, and else record an error. It's a seq with at most one element
      val catInError = gitActiveTechniqueCategoryArchiver.archiveActiveTechniqueCategory(cat,categories.reverse.tail, gitCommit = None) match {
                         case Full(ok) => Seq.empty[CategoryNotArchived]
                         case Empty    => Seq(CategoryNotArchived(cat.id, Failure("No error message were left")))
                         case f:Failure=> Seq(CategoryNotArchived(cat.id, f))
                       }
      //now, we try to save the active techniques - we only
      val activeTechniquesInError = activeTechniques.toSeq.filterNot(_.isSystem).map { activeTechnique =>
                                      gitActiveTechniqueArchiver.archiveActiveTechnique(activeTechnique,categories.reverse, gitCommit = None) match {
                                        case Full((gitPath, directivesNotArchiveds)) => (Seq.empty[ActiveTechniqueNotArchived], directivesNotArchiveds)
                                        case Empty => (Seq(ActiveTechniqueNotArchived(activeTechnique.id, Failure("No error message was left"))), Seq.empty[DirectiveNotArchived])
                                        case f:Failure => (Seq(ActiveTechniqueNotArchived(activeTechnique.id, f)), Seq.empty[DirectiveNotArchived])
                                      }
                                    }
      val (atNotArchived, dirNotArchived) = ( (Seq.empty[ActiveTechniqueNotArchived], Seq.empty[DirectiveNotArchived]) /: activeTechniquesInError) { 
        case ( (ats,dirs) , (at,dir)  ) => (ats ++ at, dirs ++ dir) 
      }
      (catInError, atNotArchived, dirNotArchived)
    }
    
    (NotArchivedElements( Seq(), Seq(), Seq()) /: byCategories) { 
      case (NotArchivedElements(cats, ats, dirs), (cat,at,dir)) => NotArchivedElements(cats++cat, ats++at, dirs++dir)
    }
  }
  
  override def exportGroupLibrary(commiter:PersonIdent, modId:ModificationId, actor:EventActor, reason:Option[String], includeSystem:Boolean = false): Box[GitArchiveId] = 
    exportGroupLibraryAndDeploy(commiter, modId, actor, reason, includeSystem) 
    
  private[this] def exportGroupLibraryAndDeploy(commiter:PersonIdent, modId:ModificationId, actor:EventActor, reason:Option[String], includeSystem:Boolean = false, deploy:Boolean = true): Box[GitArchiveId] = { 
    for { 
      catWithGroups   <- groupRepository.getGroupsByCategory(includeSystem = true)
      //remove systems categories, because we don't want them
      okCatWithGroup  =   catWithGroups.collect {
                            //always include root category, even if it's a system one
                            case (categories, CategoryAndNodeGroup(cat, groups)) if(cat.isSystem == false || categories.size <= 1) => 
                              (categories, CategoryAndNodeGroup(cat, groups.filter( _.isSystem == false )))
                         }
      cleanedRoot     <- tryo { FileUtils.cleanDirectory(gitNodeGroupCategoryArchiver.getRootDirectory) }
      savedItems      <- sequence(okCatWithGroup.toSeq) { case (categories, CategoryAndNodeGroup(cat, groups)) => 
                           for {
                             //categories.tail is OK, as no category can have an empty path (id)
                             savedCat    <- gitNodeGroupCategoryArchiver.archiveNodeGroupCategory(cat,categories.reverse.tail, gitCommit = None)
                             savedgroups <- sequence(groups.toSeq.filterNot(_.isSystem)) { group =>
                                              gitNodeGroupArchiver.archiveNodeGroup(group,categories.reverse, gitCommit = None)
                                            }
                           } yield {
                             "OK"
                           }
                         }
      commitId        <- gitNodeGroupCategoryArchiver.commitGroupLibrary(modId, commiter, reason)
      eventLogged     <- eventLogger.saveEventLog(modId, new ExportGroupsArchive(actor,commitId, reason))
    } yield {
      if(deploy) { asyncDeploymentAgent ! AutomaticStartDeployment(modId, actor) }
      commitId
    }
  }
  
  
  ////////// Import //////////
  
  
  
  override def importAll(archiveId:GitCommitId, commiter:PersonIdent, modId:ModificationId, actor:EventActor, reason:Option[String], includeSystem:Boolean = false) : Box[GitCommitId] = {
    logger.info("Importing full archive with id '%s'".format(archiveId.value))
    for {
      rules       <- importRulesAndDeploy(archiveId, modId, actor, reason, includeSystem, false)
      userLib     <- importTechniqueLibraryAndDeploy(archiveId, modId, actor, reason, includeSystem, false)
      groupLIb    <- importGroupLibraryAndDeploy(archiveId, modId, actor, reason, includeSystem, false)
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
        
  private[this] def importRulesAndDeploy(archiveId:GitCommitId, modId:ModificationId, actor:EventActor, reason:Option[String], includeSystem:Boolean = false, deploy:Boolean = true) : Box[GitCommitId] = {
    logger.info("Importing rules archive with id '%s'".format(archiveId.value))
    for {
      parsed      <- parseRules.getArchive(archiveId)
      imported    <- ruleRepository.swapRules(parsed)
    } yield {
      //try to clean
      ruleRepository.deleteSavedRuleArchiveId(imported) match {
        case eb:EmptyBox =>
          val e = eb ?~! ("Error when trying to delete saved archive of old rule: " + imported)
          logger.error(e)
        case _ => //ok
      }
      if(deploy) { asyncDeploymentAgent ! AutomaticStartDeployment(modId, actor) }
      archiveId
    }
  }
  
  override def importTechniqueLibrary(archiveId:GitCommitId, commiter:PersonIdent, modId:ModificationId, actor:EventActor, reason:Option[String], includeSystem:Boolean) : Box[GitCommitId] = {
    val commitMsg = "User %s requested directive archive restoration to commit %s".format(actor.name,archiveId.value)
    for {
      directivesArchiveId <- importTechniqueLibraryAndDeploy(archiveId, modId, actor, reason, includeSystem)
      eventLogged <- eventLogger.saveEventLog(modId, new ImportTechniqueLibraryArchive(actor,archiveId, reason))
      commit              <- restoreCommitAtHead(commiter,commitMsg,archiveId,TechniqueLibraryArchive,modId)
    } yield
      archiveId
  }
  private[this] def importTechniqueLibraryAndDeploy(archiveId:GitCommitId, modId:ModificationId, actor:EventActor, reason:Option[String], includeSystem:Boolean, deploy:Boolean = true) : Box[GitCommitId] = {
    logger.info("Importing technique library archive with id '%s'".format(archiveId.value))
      for {
        parsed      <- parseActiveTechniqueLibrary.getArchive(archiveId)
        imported    <- importTechniqueLibrary.swapActiveTechniqueLibrary(parsed, includeSystem)
      } yield {
        if(deploy) { asyncDeploymentAgent ! AutomaticStartDeployment(modId, actor) }
        archiveId
      }
  }
  
  override def importGroupLibrary(archiveId:GitCommitId, commiter:PersonIdent, modId:ModificationId, actor:EventActor, reason:Option[String], includeSystem:Boolean) : Box[GitCommitId] = {
    val commitMsg = "User %s requested group archive restoration to commit %s".format(actor.name,archiveId.value)
    for {
      groupsArchiveId <- importGroupLibraryAndDeploy(archiveId, modId, actor, reason, includeSystem)
      eventLogged <- eventLogger.saveEventLog(modId, new ImportGroupsArchive(actor,archiveId, reason))
      commit          <- restoreCommitAtHead(commiter,commitMsg,archiveId,GroupArchive,modId)
    } yield
      archiveId
  }

  private[this] def importGroupLibraryAndDeploy(archiveId:GitCommitId, modId:ModificationId, actor:EventActor, reason:Option[String], includeSystem:Boolean, deploy:Boolean = true) : Box[GitCommitId] = {
    logger.info("Importing groups archive with id '%s'".format(archiveId.value))
      for {
        parsed   <- parseGroupLibrary.getArchive(archiveId)
        imported <- importGroupLibrary.swapGroupLibrary(parsed, includeSystem)
      } yield {
        if(deploy) { asyncDeploymentAgent ! AutomaticStartDeployment(modId, actor) }
        archiveId
      }
  }

  /*
   * Rollback, it acts like a full archive restoration
   * (restoring rules, groups, directives) but it is based on a git commit
   * linked to a modification made in the rudder UI.
   */

  override def rollback(archiveId:GitCommitId, commiter:PersonIdent, modId:ModificationId, actor:EventActor, reason:Option[String],  rollbackedEvents :Seq[EventLog], target:EventLog, rollbackType:String, includeSystem:Boolean = false) : Box[GitCommitId] = {
    logger.info("Importing full archive with id '%s'".format(archiveId.value))
    for {
      rules       <- importRulesAndDeploy(archiveId, modId, actor, reason, includeSystem, false)
      userLib     <- importTechniqueLibraryAndDeploy(archiveId, modId, actor, reason, includeSystem, false)
      groupLIb    <- importGroupLibraryAndDeploy(archiveId, modId, actor, reason, includeSystem, false)
      eventLogged <- eventLogger.saveEventLog(modId,new Rollback(actor,rollbackedEvents, target, rollbackType, reason))
      commit      <- restoreCommitAtHead(commiter,"User %s requested a rollback to a previous configuration : %s".format(actor.name,archiveId.value),archiveId,FullArchive,modId)
    } yield {
      asyncDeploymentAgent ! AutomaticStartDeployment(modId, actor)
      archiveId
    }
  }
  
  override def getFullArchiveTags : Box[Map[DateTime,GitArchiveId]] = this.getTags()
  
  // groups, technique library and rules may use
  // their own tag or a global one. 
  
  override def getGroupLibraryTags : Box[Map[DateTime,GitArchiveId]] = {
    for {
      globalTags <- this.getTags()
      groupsTags <- gitNodeGroupCategoryArchiver.getTags()
    } yield {
      globalTags ++ groupsTags
    }
  }
  
  override def getTechniqueLibraryTags : Box[Map[DateTime,GitArchiveId]] = {
    for {
      globalTags    <- this.getTags()
      policyLibTags <- gitActiveTechniqueCategoryArchiver.getTags()
    } yield {
      globalTags ++ policyLibTags
    }
  }
  
  override def getRulesTags : Box[Map[DateTime,GitArchiveId]] = {
    for {
      globalTags <- this.getTags()
      crTags     <- gitRuleArchiver.getTags()
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

object GroupArchive            extends PartialArchive("groups/")
object RuleArchive             extends PartialArchive("rules/")
object TechniqueLibraryArchive extends PartialArchive("directives/")

case object FullArchive extends ArchiveMode {

  def configureRm(rmCmd:RmCommand) =
    TechniqueLibraryArchive.configureRm(
      RuleArchive.configureRm(
        GroupArchive.configureRm(rmCmd)
    ) )

  def configureCheckout(coCmd:CheckoutCommand) =
    TechniqueLibraryArchive.configureCheckout(
      RuleArchive.configureCheckout(
        GroupArchive.configureCheckout(coCmd)
    ) )
}