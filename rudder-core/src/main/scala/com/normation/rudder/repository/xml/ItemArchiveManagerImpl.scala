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
import com.normation.eventlog.EventLogService
import com.normation.eventlog.EventActor
import com.normation.rudder.domain.log._
import com.normation.rudder.batch.AsyncDeploymentAgent
import com.normation.rudder.batch.AutomaticStartDeployment

class ItemArchiveManagerImpl(
    configurationRuleRepository          : ConfigurationRuleRepository
  , uptRepository                        : UserPolicyTemplateRepository
  , groupRepository                      : NodeGroupRepository
  , override val gitRepo                 : GitRepositoryProvider
  , revisionProvider                     : GitRevisionProvider
  , gitConfigurationRuleArchiver         : GitConfigurationRuleArchiver
  , gitUserPolicyTemplateCategoryArchiver: GitUserPolicyTemplateCategoryArchiver
  , gitUserPolicyTemplateArchiver        : GitUserPolicyTemplateArchiver
  , gitNodeGroupCategoryArchiver         : GitNodeGroupCategoryArchiver
  , gitNodeGroupArchiver                 : GitNodeGroupArchiver
  , parseConfigurationRules              : ParseConfigurationRules
  , parsePolicyLibrary                   : ParsePolicyLibrary
  , importPolicyLibrary                  : ImportPolicyLibrary
  , parseGroupLibrary                    : ParseGroupLibrary
  , importGroupLibrary                   : ImportGroupLibrary
  , eventLogger                          : EventLogService
  , asyncDeploymentAgent                 : AsyncDeploymentAgent
) extends 
  ItemArchiveManager with 
  Loggable with 
  GitArchiverFullCommitUtils 
{
  
  override val tagPrefix = "archives/full/"
  override val relativePath = "."
  
  ///// implementation /////
  
  def exportAll(commiter:PersonIdent, actor:EventActor, includeSystem:Boolean = false): Box[GitArchiveId] = { 
    for {
      saveCrs     <- exportConfigurationRulesAndDeploy(commiter, actor, includeSystem, false)
      saveUserLib <- exportPolicyLibraryAndDeploy(commiter, actor, includeSystem, false)
      saveGroups  <- exportGroupLibraryAndDeploy(commiter, actor, includeSystem, false)
      archiveAll  <- this.commitFullGitPathContentAndTag(
                         commiter
                       , FULL_ARCHIVE_TAG + " Archive and tag groups, policy library and configuration rules"
                     )
      eventLogged <- eventLogger.saveEventLog(new ExportFullArchive(actor,archiveAll))
    } yield {
      asyncDeploymentAgent ! AutomaticStartDeployment(actor)
      archiveAll
    }
  }

  
  def exportConfigurationRules(commiter:PersonIdent, actor:EventActor, includeSystem:Boolean = false): Box[GitArchiveId] =
    exportConfigurationRulesAndDeploy(commiter, actor, includeSystem)
    
  private[this] def exportConfigurationRulesAndDeploy(commiter:PersonIdent, actor:EventActor, includeSystem:Boolean = false, deploy:Boolean = true): Box[GitArchiveId] = { 
    for {
      crs         <- configurationRuleRepository.getAll(false)
      cleanedRoot <- tryo { FileUtils.cleanDirectory(gitConfigurationRuleArchiver.getRootDirectory) }
      saved       <- sequence(crs) { cr => 
                       gitConfigurationRuleArchiver.archiveConfigurationRule(cr,None)
                     }
      commitId    <- gitConfigurationRuleArchiver.commitConfigurationRules(commiter)
      eventLogged <- eventLogger.saveEventLog(new ExportConfigurationRulesArchive(actor,commitId))
    } yield {
      if(deploy) { asyncDeploymentAgent ! AutomaticStartDeployment(actor) }
      commitId
    }
  }
  
  def exportPolicyLibrary(commiter:PersonIdent, actor:EventActor, includeSystem:Boolean = false): Box[GitArchiveId] =
    exportPolicyLibraryAndDeploy(commiter, actor, includeSystem) 
    
  private[this] def exportPolicyLibraryAndDeploy(commiter:PersonIdent, actor:EventActor, includeSystem:Boolean = false, deploy:Boolean = true): Box[GitArchiveId] = { 
    for { 
      catWithUPT   <- uptRepository.getUPTbyCategory(includeSystem = true)
      //remove systems things if asked (both system categories and system upts in non-system categories)
      okCatWithUPT =  if(includeSystem) catWithUPT
                      else catWithUPT.collect { 
                          //always include root category, even if it's a system one
                          case (categories, CategoryAndUPT(cat, upts)) if(cat.isSystem == false || categories.size <= 1) => 
                            (categories, CategoryAndUPT(cat, upts.filter( _.isSystem == false )))
                      }
      cleanedRoot <- tryo { FileUtils.cleanDirectory(gitUserPolicyTemplateCategoryArchiver.getRootDirectory) }
      savedItems  <- sequence(okCatWithUPT.toSeq) { case (categories, CategoryAndUPT(cat, upts)) => 
                       for {
                         //categories.tail is OK, as no category can have an empty path (id)
                         savedCat  <- gitUserPolicyTemplateCategoryArchiver.archiveUserPolicyTemplateCategory(cat,categories.reverse.tail, gitCommit = None)
                         savedUpts <- sequence(upts.toSeq) { upt =>
                                        gitUserPolicyTemplateArchiver.archiveUserPolicyTemplate(upt,categories.reverse, gitCommit = None)
                                      }
                       } yield {
                         "OK"
                       }
                     }
      commitId    <- gitUserPolicyTemplateCategoryArchiver.commitUserPolicyLibrary(commiter)
      eventLogged <- eventLogger.saveEventLog(new ExportPolicyLibraryArchive(actor,commitId))
    } yield {
      if(deploy) { asyncDeploymentAgent ! AutomaticStartDeployment(actor) }
      commitId
    }
  }
  
  def exportGroupLibrary(commiter:PersonIdent, actor:EventActor, includeSystem:Boolean = false): Box[GitArchiveId] = 
    exportGroupLibraryAndDeploy(commiter, actor, includeSystem) 
    
  private[this] def exportGroupLibraryAndDeploy(commiter:PersonIdent, actor:EventActor, includeSystem:Boolean = false, deploy:Boolean = true): Box[GitArchiveId] = { 
    for { 
      catWithGroups   <- groupRepository.getGroupsByCategory(includeSystem = true)
      //remove systems things if asked (both system categories and system groups in non-system categories)
      okCatWithGroup  =  if(includeSystem) catWithGroups
                         else catWithGroups.collect { 
                            //always include root category, even if it's a system one
                            case (categories, CategoryAndNodeGroup(cat, groups)) if(cat.isSystem == false || categories.size <= 1) => 
                              (categories, CategoryAndNodeGroup(cat, groups.filter( _.isSystem == false )))
                         }
      cleanedRoot     <- tryo { FileUtils.cleanDirectory(gitNodeGroupCategoryArchiver.getRootDirectory) }
      savedItems      <- sequence(okCatWithGroup.toSeq) { case (categories, CategoryAndNodeGroup(cat, groups)) => 
                           for {
                             //categories.tail is OK, as no category can have an empty path (id)
                             savedCat    <- gitNodeGroupCategoryArchiver.archiveNodeGroupCategory(cat,categories.reverse.tail, gitCommit = None)
                             savedgroups <- sequence(groups.toSeq) { group =>
                                              gitNodeGroupArchiver.archiveNodeGroup(group,categories.reverse, gitCommit = None)
                                            }
                           } yield {
                             "OK"
                           }
                         }
      commitId        <- gitNodeGroupCategoryArchiver.commitGroupLibrary(commiter)
      eventLogged <- eventLogger.saveEventLog(new ExportGroupsArchive(actor,commitId))
    } yield {
      if(deploy) { asyncDeploymentAgent ! AutomaticStartDeployment(actor) }
      commitId
    }
  }
  
  
  ////////// Import //////////
  
  
  
  def importAll(archiveId:GitCommitId, actor:EventActor, includeSystem:Boolean = false) : Box[GitCommitId] = {
    logger.info("Importing full archive with id '%s'".format(archiveId.value))
    for {
      configurationRules <- importConfigurationRulesAndDeploy(archiveId, actor, includeSystem, false)
      userLib            <- importPolicyLibraryAndDeploy(archiveId, actor, includeSystem, false)
      groupLIb           <- importGroupLibraryAndDeploy(archiveId, actor, includeSystem, false)
      eventLogged        <- eventLogger.saveEventLog(new ImportFullArchive(actor,archiveId))
    } yield {
      asyncDeploymentAgent ! AutomaticStartDeployment(actor)
      archiveId
    }
  }
  
  def importConfigurationRules(archiveId:GitCommitId, actor:EventActor, includeSystem:Boolean = false) =
    importConfigurationRulesAndDeploy(archiveId, actor, includeSystem)
        
  private[this] def importConfigurationRulesAndDeploy(archiveId:GitCommitId, actor:EventActor, includeSystem:Boolean = false, deploy:Boolean = true) : Box[GitCommitId] = {
    logger.info("Importing configuration rules archive with id '%s'".format(archiveId.value))
    for {
      parsed      <- parseConfigurationRules.getArchive(archiveId)
      imported    <- configurationRuleRepository.swapConfigurationRules(parsed)
      eventLogged <- eventLogger.saveEventLog(new ImportConfigurationRulesArchive(actor,archiveId))
    } yield {
      //try to clean
      configurationRuleRepository.deleteSavedCr(imported) match {
        case eb:EmptyBox =>
          val e = eb ?~! ("Error when trying to delete saved archive of old cr: " + imported)
          logger.error(e)
        case _ => //ok
      }
      if(deploy) { asyncDeploymentAgent ! AutomaticStartDeployment(actor) }
      archiveId
    }
  }
  
  def importPolicyLibrary(archiveId:GitCommitId, actor:EventActor, includeSystem:Boolean) : Box[GitCommitId] = 
    importPolicyLibraryAndDeploy(archiveId, actor, includeSystem)
  
  private[this] def importPolicyLibraryAndDeploy(archiveId:GitCommitId, actor:EventActor, includeSystem:Boolean, deploy:Boolean = true) : Box[GitCommitId] = {
    logger.info("Importing policy library archive with id '%s'".format(archiveId.value))
      for {
        parsed      <- parsePolicyLibrary.getArchive(archiveId)
        imported    <- importPolicyLibrary.swapUserPolicyLibrary(parsed, includeSystem)
        eventLogged <- eventLogger.saveEventLog(new ImportPolicyLibraryArchive(actor,archiveId))
      } yield {
        if(deploy) { asyncDeploymentAgent ! AutomaticStartDeployment(actor) }
        archiveId
      }
  }
  
  def importGroupLibrary(archiveId:GitCommitId, actor:EventActor, includeSystem:Boolean) : Box[GitCommitId] =
    importGroupLibraryAndDeploy(archiveId, actor, includeSystem)

  private[this] def importGroupLibraryAndDeploy(archiveId:GitCommitId, actor:EventActor, includeSystem:Boolean, deploy:Boolean = true) : Box[GitCommitId] = {
    logger.info("Importing groups archive with id '%s'".format(archiveId.value))
      for {
        parsed      <- parseGroupLibrary.getArchive(archiveId)
        imported    <- importGroupLibrary.swapGroupLibrary(parsed, includeSystem)
        eventLogged <- eventLogger.saveEventLog(new ImportGroupsArchive(actor,archiveId))
      } yield {
        if(deploy) { asyncDeploymentAgent ! AutomaticStartDeployment(actor) }
        archiveId
      }
  }

  private[this] def lastGitCommitId = GitCommitId(revisionProvider.getAvailableRevTreeId.getName)
  
  def importHeadAll(actor:EventActor, includeSystem:Boolean = false) : Box[GitCommitId] = {
    logger.info("Importing full archive from HEAD")
    this.importAll(lastGitCommitId, actor, includeSystem)
  }
  
  def importHeadConfigurationRules(actor:EventActor, includeSystem:Boolean = false) : Box[GitCommitId] = {
    logger.info("Importing configuration rules archive from HEAD")
    this.importConfigurationRules(lastGitCommitId, actor, includeSystem)
  }
  
  def importHeadPolicyLibrary(actor:EventActor, includeSystem:Boolean = false) : Box[GitCommitId] = {
    logger.info("Importing policy library archive from HEAD")
    this.importPolicyLibrary(lastGitCommitId, actor, includeSystem)
  }
  
  def importHeadGroupLibrary(actor:EventActor, includeSystem:Boolean = false) : Box[GitCommitId] = {
    logger.info("Importing groups archive from HEAD")
    this.importGroupLibrary(lastGitCommitId, actor, includeSystem)
  }
  
  def getFullArchiveTags : Box[Map[DateTime,GitArchiveId]] = this.getTags()
  
  // groups, policy library and configuration rules may use
  // their own tag or a global one. 
  
  def getGroupLibraryTags : Box[Map[DateTime,GitArchiveId]] = {
    for {
      globalTags <- this.getTags()
      groupsTags <- gitNodeGroupCategoryArchiver.getTags()
    } yield {
      globalTags ++ groupsTags
    }
  }
  
  def getPolicyLibraryTags : Box[Map[DateTime,GitArchiveId]] = {
    for {
      globalTags    <- this.getTags()
      policyLibTags <- gitUserPolicyTemplateCategoryArchiver.getTags()
    } yield {
      globalTags ++ policyLibTags
    }
  }
  
  def getConfigurationRulesTags : Box[Map[DateTime,GitArchiveId]] = {
    for {
      globalTags <- this.getTags()
      crTags     <- gitConfigurationRuleArchiver.getTags()
    } yield {
      globalTags ++ crTags
    }
  }
}