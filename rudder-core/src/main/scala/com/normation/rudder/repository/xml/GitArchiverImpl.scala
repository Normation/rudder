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

import com.normation.cfclerk.domain.PolicyPackageName
import com.normation.cfclerk.domain.SectionSpec
import com.normation.cfclerk.services.GitRepositoryProvider
import com.normation.exceptions.TechnicalException
import com.normation.rudder.domain.policies._
import com.normation.rudder.repository._
import com.normation.rudder.services.marshalling._
import com.normation.utils.Utils
import com.normation.utils.Control.sequence
import com.normation.cfclerk.domain.PolicyPackage
import com.normation.cfclerk.services.PolicyPackageService
import com.normation.rudder.repository.PolicyInstanceRepository
import com.normation.rudder.domain.Constants.{
    CONFIGURATION_RULES_ARCHIVE_TAG
  , GROUPS_ARCHIVE_TAG
  , POLICY_LIBRARY_ARCHIVE_TAG
}
import com.normation.rudder.domain.nodes.NodeGroupCategoryId
import com.normation.rudder.domain.nodes.NodeGroupCategory
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.nodes.NodeGroup
import java.io.File
import org.apache.commons.io.FileUtils
import org.eclipse.jgit.api.Git
import org.joda.time.format.ISODateTimeFormat
import org.joda.time.DateTime
import org.eclipse.jgit.lib.PersonIdent
import net.liftweb.common._
import net.liftweb.util.Helpers.tryo
import scala.xml.Elem
import scala.xml.PrettyPrinter
import scala.collection.mutable.Buffer
import scala.collection.JavaConversions._


class GitConfigurationRuleArchiverImpl(
    override val gitRepo            : GitRepositoryProvider
  , override val gitRootDirectory   : File
  , configurationRuleSerialisation  : ConfigurationRuleSerialisation
  , configurationRuleRootDir        : String //relative path !
  , override val xmlPrettyPrinter   : PrettyPrinter
  , override val encoding           : String = "UTF-8"
) extends 
  GitConfigurationRuleArchiver with 
  Loggable with 
  GitArchiverUtils with 
  GitArchiverFullCommitUtils 
{


  override val relativePath = configurationRuleRootDir
  override val tagPrefix = "archives/configurations-rules/"
  
  private[this] def newCrFile(crId:ConfigurationRuleId) = new File(getRootDirectory, crId.value + ".xml")
  
  def archiveConfigurationRule(cr:ConfigurationRule, gitCommitCr:Option[PersonIdent]) : Box[GitPath] = {
    val crFile = newCrFile(cr.id)
    val gitPath = toGitPath(crFile)
    for {   
      archive <- writeXml(
                     crFile
                   , configurationRuleSerialisation.serialise(cr)
                   , "Archived Configuration rule: " + crFile.getPath
                 )
      commit  <- gitCommitCr match {
                   case Some(commiter) => commitAddFile(commiter, gitPath, "Archive configuration rule with ID '%s'".format(cr.id.value))
                   case None => Full("ok")
                 }
    } yield {
      GitPath(gitPath)
    }
  }

  def commitConfigurationRules(commiter:PersonIdent) : Box[GitArchiveId] = {
    this.commitFullGitPathContentAndTag(
        commiter
      , CONFIGURATION_RULES_ARCHIVE_TAG + " Commit all modification done on configuration rules (git path: '%s')".format(configurationRuleRootDir)
    )
  }
  
  def deleteConfigurationRule(crId:ConfigurationRuleId, gitCommitCr:Option[PersonIdent]) : Box[GitPath] = {
    val crFile = newCrFile(crId)
    val gitPath = toGitPath(crFile)
    if(crFile.exists) {
      for {
        deleted  <- tryo { 
                      FileUtils.forceDelete(crFile) 
                      logger.debug("Deleted archive of configuration rule: " + crFile.getPath)
                    }
        commited <- gitCommitCr match {
                      case Some(commiter) => commitRmFile(commiter, gitPath, "Delete archive of configuration rule with ID '%s'".format(crId.value))
                      case None => Full("OK")
                    }
      } yield {
        GitPath(gitPath)
      }
    } else {
      Full(GitPath(gitPath))
    }
  }
  
}


/**
 * An Utility trait that allows to build the path from a root directory
 * to the category directory from a list of category ids.
 * Basically, it builds the list of directory has path, special casing
 * the root directory to be the given root file. 
 */
trait BuildCategoryPathName[T] {
  //obtain the root directory from the main class mixed with me
  def getRootDirectory : File
  
  def getCategoryName(categoryId:T):String
  
  //list of directories : don't forget the one for the serialized category. 
  //revert the order to start by the root of policy library. 
  def newCategoryDirectory(catId:T, parents: List[T]) : File = {
    parents match {
      case Nil => //that's the root
        getRootDirectory
      case h::tail => //skip the head, which is the root category
        new File(newCategoryDirectory(h, tail), getCategoryName(catId) )
    }
  }
}


///////////////////////////////////////////////////////////////////////////////////////////////
//////  Archive the User Policy Library (categories, policy templates, policy instances) //////
///////////////////////////////////////////////////////////////////////////////////////////////

/**
 * A specific trait to create archive of an user policy template category.
 * 
 * Basically, we directly map the category tree to file-system directories,
 * with the root category being the file denoted by "policyLibraryRootDir"
 * 
 */
class GitUserPolicyTemplateCategoryArchiverImpl(
    override val gitRepo                   : GitRepositoryProvider
  , override val gitRootDirectory          : File
  , userPolicyTemplateCategorySerialisation: UserPolicyTemplateCategorySerialisation
  , policyLibraryRootDir                   : String //relative path !
  , override val xmlPrettyPrinter          : PrettyPrinter
  , override val encoding                  : String = "UTF-8"
  , serializedCategoryName                 : String = "category.xml"
) extends 
  GitUserPolicyTemplateCategoryArchiver with 
  Loggable with 
  GitArchiverUtils with 
  BuildCategoryPathName[UserPolicyTemplateCategoryId] with 
  GitArchiverFullCommitUtils 
{


  override lazy val relativePath = policyLibraryRootDir
  override def  getCategoryName(categoryId:UserPolicyTemplateCategoryId) = categoryId.value
  
  override lazy val tagPrefix = "archives/policy-library/"
  
  private[this] def newUptcFile(uptcId:UserPolicyTemplateCategoryId, parents: List[UserPolicyTemplateCategoryId]) = {
    new File(newCategoryDirectory(uptcId, parents), serializedCategoryName) 
  }
  
  private[this] def archiveWithRename(uptc:UserPolicyTemplateCategory, oldParents: Option[List[UserPolicyTemplateCategoryId]], newParents: List[UserPolicyTemplateCategoryId], gitCommit:Option[PersonIdent]) : Box[GitPath] = {     
    val uptcFile = newUptcFile(uptc.id, newParents)
    val gitPath = toGitPath(uptcFile)
    for {
      archive     <- writeXml(
                         uptcFile
                       , userPolicyTemplateCategorySerialisation.serialise(uptc)
                       , "Archived policy library category: " + uptcFile.getPath
                     )
      uptcGitPath =  gitPath
      commit      <- gitCommit match {
                       case Some(commiter) =>
                         oldParents match {
                           case Some(olds) => 
                             commitMvDirectory(commiter, toGitPath(newUptcFile(uptc.id, olds)), uptcGitPath, "Move archive of policy library category with ID '%s'".format(uptc.id.value))
                           case None       => 
                             commitAddFile(commiter, uptcGitPath, "Archive of policy library category with ID '%s'".format(uptc.id.value))
                         }
                       case None => Full("ok")
                    }
    } yield {
      GitPath(gitPath)
    }
  }

  def archiveUserPolicyTemplateCategory(uptc:UserPolicyTemplateCategory, getParents: List[UserPolicyTemplateCategoryId], gitCommit:Option[PersonIdent]) : Box[GitPath] = {     
    archiveWithRename(uptc, None, getParents, gitCommit)
  }
  
  def deleteUserPolicyTemplateCategory(uptcId:UserPolicyTemplateCategoryId, getParents: List[UserPolicyTemplateCategoryId], gitCommit:Option[PersonIdent]) : Box[GitPath] = {
    val uptcFile = newUptcFile(uptcId, getParents)
    val gitPath = toGitPath(uptcFile)
    if(uptcFile.exists) {
      for {
        //don't forget to delete the category *directory*
        deleted  <- tryo { 
                      FileUtils.forceDelete(uptcFile.getParentFile) 
                      logger.debug("Deleted archived policy library category: " + uptcFile.getPath)
                    }
        commited <- gitCommit match {
                      case Some(commiter) => commitRmFile(commiter, gitPath, "Delete archive of policy library category with ID '%s'".format(uptcId.value))
                      case None => Full("OK")
                    }
      } yield {
        GitPath(gitPath)
      }
    } else {
      Full(GitPath(gitPath))
    }
  }
  // TODO : keep content when moving !!!
  // well, for now, that's ok, because we can only move empty categories
  def moveUserPolicyTemplateCategory(uptc:UserPolicyTemplateCategory, oldParents: List[UserPolicyTemplateCategoryId], newParents: List[UserPolicyTemplateCategoryId], gitCommit:Option[PersonIdent]) : Box[GitPath] = {
    for {
      deleted  <- deleteUserPolicyTemplateCategory(uptc.id, oldParents, None)
      archived <- archiveWithRename(uptc, Some(oldParents), newParents, gitCommit)
    } yield {
      archived
    }
  }
  
  /**
   * Commit modification done in the Git repository for any
   * category, policy template and policy instance in the
   * user policy library.
   * Return the git commit id. 
   */
  def commitUserPolicyLibrary(commiter:PersonIdent) : Box[GitArchiveId] = {
    this.commitFullGitPathContentAndTag(
        commiter
      , POLICY_LIBRARY_ARCHIVE_TAG + " Commit all modification done in the User Policy Library (git path: '%s')".format(policyLibraryRootDir)
    )
  }
}


trait UptModificationCallback {
  
  //Name of the callback, for debugging
  def uptModificationCallbackName : String

  /**
   * What to do on upt save
   */
  def onArchive(upt:UserPolicyTemplate, parents: List[UserPolicyTemplateCategoryId], gitCommit:Option[PersonIdent]) : Box[Unit]

  /**
   * What to do on upt deletion
   */
  def onDelete(ptName:PolicyPackageName, getParents: List[UserPolicyTemplateCategoryId], gitCommit:Option[PersonIdent]) : Box[Unit]

  /**
   * What to do on upt move
   */
  def onMove(upt:UserPolicyTemplate, oldParents: List[UserPolicyTemplateCategoryId], newParents: List[UserPolicyTemplateCategoryId], gitCommit:Option[PersonIdent]) : Box[Unit]
}

class UpdatePiOnUptEvent(
    gitPiArchiver: GitPolicyInstanceArchiver
  , ptRepository : PolicyPackageService
  , piRepository : PolicyInstanceRepository    
) extends UptModificationCallback with Loggable {
  override val uptModificationCallbackName = "Update PI on UPT events"
  
  def onArchive(upt:UserPolicyTemplate, parents: List[UserPolicyTemplateCategoryId], gitCommit:Option[PersonIdent]) : Box[Unit] = {
    
    logger.debug("Executing archivage of PIs for UPT '%s'".format(upt))
    
    if(upt.policyInstances.isEmpty) Full("OK")
    else {
      for {
        pt  <- Box(ptRepository.getLastPolicyByName(upt.referencePolicyTemplateName))
        pis <- sequence(upt.policyInstances) { piId =>
                 for {
                   pi         <- piRepository.getPolicyInstance(piId)
                   archivedPi <- gitPiArchiver.archivePolicyInstance(pi, pt.id.name, parents, pt.rootSection, None)
                 } yield {
                   archivedPi
                 }
               }
      } yield {
        pis
      }
    }
  }
  
  override def onDelete(ptName:PolicyPackageName, getParents: List[UserPolicyTemplateCategoryId], gitCommit:Option[PersonIdent]) = Full({})
  override def onMove(upt:UserPolicyTemplate, oldParents: List[UserPolicyTemplateCategoryId], newParents: List[UserPolicyTemplateCategoryId], gitCommit:Option[PersonIdent]) = Full({})
}

/**
 * A specific trait to create archive of an user policy template.
 */
class GitUserPolicyTemplateArchiverImpl(
    override val gitRepo           : GitRepositoryProvider
  , override val gitRootDirectory  : File
  , userPolicyTemplateSerialisation: UserPolicyTemplateSerialisation
  , policyLibraryRootDir           : String //relative path !
  , override val xmlPrettyPrinter  : PrettyPrinter
  , override val encoding          : String = "UTF-8"
  , val uptModificationCallback    : Buffer[UptModificationCallback] = Buffer()
  , val userPolicyTemplateFileName : String = "userPolicyTemplateSettings.xml"
) extends GitUserPolicyTemplateArchiver with Loggable with GitArchiverUtils with BuildCategoryPathName[UserPolicyTemplateCategoryId] {

  override lazy val relativePath = policyLibraryRootDir
  override def  getCategoryName(categoryId:UserPolicyTemplateCategoryId) = categoryId.value

  private[this] def newUptFile(ptName:PolicyPackageName, parents: List[UserPolicyTemplateCategoryId]) = {
    //parents can not be null: we must have at least the root category
    parents match {
      case Nil => Failure("UPT '%s' was asked to be saved in a category which does not exists (empty list of parents, not even the root cateogy was given!)".format(ptName.value))
      case h::tail => Full(new File(new File(newCategoryDirectory(h,tail),ptName.value), userPolicyTemplateFileName))
    }
  }
  
  def archiveUserPolicyTemplate(upt:UserPolicyTemplate, parents: List[UserPolicyTemplateCategoryId], gitCommit:Option[PersonIdent]) : Box[GitPath] = {     
    for {
      uptFile   <- newUptFile(upt.referencePolicyTemplateName, parents)
      gitPath   =  toGitPath(uptFile)
      archive   <- writeXml(
                       uptFile
                     , userPolicyTemplateSerialisation.serialise(upt)
                     , "Archived policy library template: " + uptFile.getPath
                   )
      callbacks <- sequence(uptModificationCallback) { _.onArchive(upt, parents, None) }
      commit    <- gitCommit match {
                     case Some(commiter) => commitAddFile(commiter, gitPath, "Archive of policy library template for policy template name '%s'".format(upt.referencePolicyTemplateName.value))
                     case None => Full("ok")
                   }
    } yield {
      GitPath(gitPath)
    }
  }
  
  def deleteUserPolicyTemplate(ptName:PolicyPackageName, parents: List[UserPolicyTemplateCategoryId], gitCommit:Option[PersonIdent]) : Box[GitPath] = {
    newUptFile(ptName, parents) match {
      case Full(uptFile) if(uptFile.exists) =>
        for {
          //don't forget to delete the category *directory*
          deleted  <- tryo { 
                        if(uptFile.exists) FileUtils.forceDelete(uptFile) 
                        logger.debug("Deleted archived policy library template: " + uptFile.getPath)
                      }
          gitPath   =  toGitPath(uptFile)
          callbacks <- sequence(uptModificationCallback) { _.onDelete(ptName, parents, None) }
          commited <- gitCommit match {
                        case Some(commiter) => commitRmFile(commiter, gitPath, "Delete archive of policy library template for policy template name '%s'".format(ptName.value))
                        case None => Full("OK")
                      }
        } yield {
          GitPath(gitPath)
        }
      case other => other.map(f => GitPath(toGitPath(f)))
    }
  }
 
  /*
   * For that one, we have to move the directory of the User policy templates 
   * to its new parent location. 
   * If the commit has to be done, we have to add all files under that new repository,
   * and remove from old one.
   * 
   * As we can't know at all if all PI currently defined for an UPT were saved, we
   * DO have to always consider a fresh new archive. 
   */
  def moveUserPolicyTemplate(upt:UserPolicyTemplate, oldParents: List[UserPolicyTemplateCategoryId], newParents: List[UserPolicyTemplateCategoryId], gitCommit:Option[PersonIdent]) : Box[GitPath] = {
    for {
      oldUptFile      <- newUptFile(upt.referencePolicyTemplateName, oldParents)
      oldUptDirectory =  oldUptFile.getParentFile
      newUptFile      <- newUptFile(upt.referencePolicyTemplateName, newParents)
      newUptDirectory =  newUptFile.getParentFile
      clearNew        <- tryo {
                           if(newUptDirectory.exists) FileUtils.forceDelete(newUptDirectory)
                           else "ok"
                         }
      deleteOld       <- tryo {
                           if(oldUptDirectory.exists) FileUtils.forceDelete(oldUptDirectory)
                           else "ok"
                         }
      archived        <- archiveUserPolicyTemplate(upt, newParents, None)
      commited        <- gitCommit match {
                           case Some(commiter) => 
                             commitMvDirectory(
                                 commiter
                               , toGitPath(oldUptDirectory)
                               , toGitPath(newUptDirectory)
                               , "Move user policy template for policy template name '%s'".format(upt.referencePolicyTemplateName.value)
                             )
                           case None => Full("OK")
                         }
    } yield {
      GitPath(toGitPath(newUptDirectory))
    }
  }
}


/**
 * A specific trait to create archive of an user policy template.
 */
class GitPolicyInstanceArchiverImpl(
    override val gitRepo           : GitRepositoryProvider
  , override val gitRootDirectory  : File
  , policyInstanceSerialisation    : PolicyInstanceSerialisation
  , policyLibraryRootDir           : String //relative path !
  , override val xmlPrettyPrinter  : PrettyPrinter
  , override val encoding          : String = "UTF-8"
) extends GitPolicyInstanceArchiver with Loggable with GitArchiverUtils with BuildCategoryPathName[UserPolicyTemplateCategoryId] {

  override lazy val relativePath = policyLibraryRootDir
  override def  getCategoryName(categoryId:UserPolicyTemplateCategoryId) = categoryId.value

  private[this] def newPiFile(
      piId   : PolicyInstanceId
    , ptName : PolicyPackageName
    , parents: List[UserPolicyTemplateCategoryId]
  ) = {
    parents match {
      case Nil => Failure("Can not save policy instance '%s' for policy template '%s' because no category (not even the root one) was given as parent for that policy template".format(piId.value, ptName.value))
      case h::tail => 
        Full(new File(new File(newCategoryDirectory(h, tail), ptName.value), piId.value+".xml"))
    }
  }
  
  def archivePolicyInstance(
      pi                 : PolicyInstance
    , ptName             : PolicyPackageName
    , catIds             : List[UserPolicyTemplateCategoryId]
    , variableRootSection: SectionSpec
    , gitCommit          : Option[PersonIdent]
  ) : Box[GitPath] = {
        
    for {
      piFile  <- newPiFile(pi.id, ptName, catIds)
      gitPath =  toGitPath(piFile)
      archive <- writeXml( 
                     piFile
                   , policyInstanceSerialisation.serialise(ptName, variableRootSection, pi)
                   , "Archived policy instance: " + piFile.getPath
                 )
      commit  <- gitCommit match {
                   case Some(commiter) => commitAddFile(commiter, gitPath, "Archive policy instance with ID '%s'".format(pi.id.value))
                   case None => Full("ok")
                 }
    } yield {
      GitPath(gitPath)
    }    
  }
    
  /**
   * Delete an archived policy instance. 
   * If gitCommit is true, the modification is
   * saved in git. Else, no modification in git are saved.
   */
  def deletePolicyInstance(
      piId:PolicyInstanceId
    , ptName   : PolicyPackageName
    , catIds   : List[UserPolicyTemplateCategoryId]
    , gitCommit: Option[PersonIdent]
  ) : Box[GitPath] = {
    newPiFile(piId, ptName, catIds) match {
      case Full(piFile) if(piFile.exists) =>
        for {
          deleted  <- tryo { 
                        FileUtils.forceDelete(piFile) 
                        logger.debug("Deleted archive of policy instance: " + piFile.getPath)
                      }
          gitPath  =  toGitPath(piFile)
          commited <- gitCommit match {
                        case Some(commiter) => commitRmFile(commiter, gitPath, "Delete archive of policy instance with ID '%s'".format(piId.value))
                        case None => Full("OK")
                      }
        } yield {
          GitPath(gitPath)
        }
      case other => other.map(f => GitPath(toGitPath(f)))
    }
  }
}


/////////////////////////////////////////////////////////////
////// Archive Node Groups (categories and node group) //////
/////////////////////////////////////////////////////////////

/**
 * A specific trait to create archive of a node group category.
 * 
 * Basically, we directly map the category tree to file-system directories,
 * with the root category being the file denoted by "nodeGroupLibrary
 * 
 */
class GitNodeGroupCategoryArchiverImpl(
    override val gitRepo          : GitRepositoryProvider
  , override val gitRootDirectory : File
  , nodeGroupCategorySerialisation: NodeGroupCategorySerialisation
  , groupLibraryRootDir           : String //relative path !
  , override val xmlPrettyPrinter : PrettyPrinter
  , override val encoding         : String = "UTF-8"
  , serializedCategoryName        : String = "category.xml"
) extends 
  GitNodeGroupCategoryArchiver with 
  Loggable with 
  GitArchiverUtils with 
  BuildCategoryPathName[NodeGroupCategoryId] with 
  GitArchiverFullCommitUtils 
{

  override lazy val relativePath = groupLibraryRootDir
  override def  getCategoryName(categoryId:NodeGroupCategoryId) = categoryId.value
  
  override lazy val tagPrefix = "archives/groups/"
  
  private[this] def newNgFile(ngcId:NodeGroupCategoryId, parents: List[NodeGroupCategoryId]) = {
    new File(newCategoryDirectory(ngcId, parents), serializedCategoryName) 
  }
  
  def archiveNodeGroupCategory(ngc:NodeGroupCategory, parents: List[NodeGroupCategoryId], gitCommit:Option[PersonIdent]) : Box[GitPath] = {     
    val ngcFile = newNgFile(ngc.id, parents)
    
    for {
      archive   <- writeXml(
                         ngcFile
                       , nodeGroupCategorySerialisation.serialise(ngc)
                       , "Archived node group category: " + ngcFile.getPath
                    )
      gitPath    =  toGitPath(ngcFile)
      commit     <- gitCommit match {
                      case Some(commiter) => commitAddFile(commiter, gitPath, "Archive of node group category with ID '%s'".format(ngc.id.value))
                      case None => Full("ok")
                    }
    } yield {
      GitPath(gitPath)
    }
  }
  
  def deleteNodeGroupCategory(ngcId:NodeGroupCategoryId, getParents: List[NodeGroupCategoryId], gitCommit:Option[PersonIdent]) : Box[GitPath] = {
    val ngcFile = newNgFile(ngcId, getParents)
    val gitPath = toGitPath(ngcFile)
    if(ngcFile.exists) {
      for {
        //don't forget to delete the category *directory*
        deleted  <- tryo { 
                      FileUtils.forceDelete(ngcFile.getParentFile) 
                      logger.debug("Deleted archived node group category: " + ngcFile.getPath)
                    }
        commited <- gitCommit match {
                      case Some(commiter) => commitRmFile(commiter, gitPath, "Delete archive of node group category with ID '%s'".format(ngcId.value))
                      case None => Full("OK")
                    }
      } yield {
        GitPath(gitPath)
      }
    } else {
      Full(GitPath(gitPath))
    }
  }
  
  /* 
   * That's the hard one. 
   * We can't make any assumption about the state of the old category place on the file
   * system. Perhaps it is up to date, but perhaps it wasn't created at all, or perhaps it
   * is not synchronized.
   * 
   * Strategy followed:
   * - always (re)write category.xml, so that it is up to date;
   * - try to move old category content (except category.xml) to new
   *   category directory
   * - always try to do a gitMove. 
   */
  def moveNodeGroupCategory(ngc:NodeGroupCategory, oldParents: List[NodeGroupCategoryId], newParents: List[NodeGroupCategoryId], gitCommit:Option[PersonIdent]) : Box[GitPath] = {
    val oldNgcDir = newNgFile(ngc.id, oldParents).getParentFile
    val newNgcXmlFile = newNgFile(ngc.id, newParents)
    val newNgcDir = newNgcXmlFile.getParentFile
    
    for {
      archive <- writeXml(
                     newNgcXmlFile
                   , nodeGroupCategorySerialisation.serialise(ngc)
                   , "Archived node group category: " + newNgcXmlFile.getPath
                 )
      moved   <- { 
                   if(null != oldNgcDir && oldNgcDir.exists) {
                     if(oldNgcDir.isDirectory) {
                       //move content except category.xml
                       sequence(oldNgcDir.listFiles.toSeq.filter( f => f.getName != serializedCategoryName)) { f =>
                         tryo { FileUtils.moveToDirectory(f, newNgcDir, false) }
                       }
                     } 
                     //in all case, delete the file at the old directory path
                     tryo { FileUtils.deleteQuietly(oldNgcDir) }
                   } else Full("OK")
                 } 
      commit  <- gitCommit match {
                   case Some(commiter) => commitMvDirectory(commiter, toGitPath(oldNgcDir), toGitPath(newNgcDir), "Move archive of node group category with ID '%s'".format(ngc.id.value))
                   case None => Full("ok")
                 }
    } yield {
      GitPath(toGitPath(archive))
    }
  }
  
  /**
   * Commit modification done in the Git repository for any
   * category, policy template and policy instance in the
   * user policy library.
   * Return the git commit id. 
   */
  def commitGroupLibrary(commiter: PersonIdent) : Box[GitArchiveId] = {
    this.commitFullGitPathContentAndTag(
        commiter
      , GROUPS_ARCHIVE_TAG + " Commit all modification done in Groups (git path: '%s')".format(groupLibraryRootDir)
    )
  }
}

/**
 * A specific trait to create archive of a node group.
 * 
 * Basically, we directly map the category tree to file-system directories,
 * with the root category being the file denoted by "policyLibraryRootDir"
 * 
 */
class GitNodeGroupArchiverImpl(
    override val gitRepo          : GitRepositoryProvider
  , override val gitRootDirectory : File
  , nodeGroupSerialisation        : NodeGroupSerialisation
  , groupLibraryRootDir           : String //relative path !
  , override val xmlPrettyPrinter : PrettyPrinter
  , override val encoding         : String = "UTF-8"
) extends GitNodeGroupArchiver with Loggable with GitArchiverUtils with BuildCategoryPathName[NodeGroupCategoryId] {

  override lazy val relativePath = groupLibraryRootDir
  override def  getCategoryName(categoryId:NodeGroupCategoryId) = categoryId.value

  private[this] def newNgFile(ngId:NodeGroupId, parents: List[NodeGroupCategoryId]) = {
    parents match {
      case h :: t => Full(new File(newCategoryDirectory(h, t), ngId.value + ".xml"))
      case Nil => Failure("The given parent category list for node group with id '%s' is empty, what is forbiden".format(ngId.value))
    }
  }
  
  def archiveNodeGroup(ng:NodeGroup, parents: List[NodeGroupCategoryId], gitCommit:Option[PersonIdent]) : Box[GitPath] = {     
    for {
      ngFile    <- newNgFile(ng.id, parents)
      archive   <- writeXml(
                        ngFile
                      , nodeGroupSerialisation.serialise(ng)
                      , "Archived node group: " + ngFile.getPath
                    )
      commit     <- gitCommit match {
                      case Some(commiter) => commitAddFile(commiter, toGitPath(ngFile), "Archive of node group with ID '%s'".format(ng.id.value))
                      case None => Full("ok")
                    }
    } yield {
      GitPath(toGitPath(archive))
    }
  }
  
  def deleteNodeGroup(ngId:NodeGroupId, getParents: List[NodeGroupCategoryId], gitCommit:Option[PersonIdent]) : Box[GitPath] = {
    newNgFile(ngId, getParents) match {
      case Full(ngFile) => 
        val gitPath = toGitPath(ngFile)
        if(ngFile.exists) {
          for {
            //don't forget to delete the category *directory*
            deleted  <- tryo { 
                          FileUtils.forceDelete(ngFile.getParentFile) 
                          logger.debug("Deleted archived node group: " + ngFile.getPath)
                        }
            commited <- gitCommit match {
                          case Some(commiter) => commitRmFile(commiter, gitPath, "Delete archive of node group with ID '%s'".format(ngId.value))
                          case None => Full("OK")
                        }
          } yield {
            GitPath(gitPath)
          }
        } else {
          Full(GitPath(gitPath))
        }
      case eb:EmptyBox => eb
    }
  }
  
  def moveNodeGroup(ng:NodeGroup, oldParents: List[NodeGroupCategoryId], newParents: List[NodeGroupCategoryId], gitCommit:Option[PersonIdent]) : Box[GitPath] = {
    
    for {
      oldNgXmlFile <- newNgFile(ng.id, oldParents)
      newNgXmlFile <- newNgFile(ng.id, newParents)
      archive      <- writeXml(
                          newNgXmlFile
                        , nodeGroupSerialisation.serialise(ng)
                        , "Archived node group: " + newNgXmlFile.getPath
                      )
      moved        <- { 
                       if(null != oldNgXmlFile && oldNgXmlFile.exists) {
                         tryo { FileUtils.deleteQuietly(oldNgXmlFile) }
                       } else Full("OK")
                     } 
      commit       <- gitCommit match {
                        case Some(commiter) => commitMvDirectory(commiter, toGitPath(oldNgXmlFile), toGitPath(newNgXmlFile), "Move archive of node group with ID '%s'".format(ng.id.value))
                        case None => Full("ok")
                      }
    } yield {
      GitPath(toGitPath(archive))
    }
  }

}



