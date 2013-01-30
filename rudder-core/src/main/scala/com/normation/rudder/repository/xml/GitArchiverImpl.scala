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

import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.domain.SectionSpec
import com.normation.cfclerk.services.GitRepositoryProvider
import com.normation.exceptions.TechnicalException
import com.normation.rudder.domain.policies._
import com.normation.rudder.repository._
import com.normation.rudder.services.marshalling._
import com.normation.utils.Utils
import com.normation.utils.Control.sequence
import com.normation.cfclerk.domain.Technique
import com.normation.cfclerk.services.TechniqueRepository
import com.normation.rudder.repository.DirectiveRepository
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
import com.normation.cfclerk.domain.TechniqueId

private[xml] object GET {
  def apply(reason:Option[String]) = reason match {
    case None => ""
    case Some(m) => "\n\nReason provided by user:\n" + m
  }
}

class GitRuleArchiverImpl(
    override val gitRepo          : GitRepositoryProvider
  , override val gitRootDirectory : File
  , ruleSerialisation             : RuleSerialisation
  , ruleRootDir                   : String //relative path !
  , override val xmlPrettyPrinter : PrettyPrinter
  , override val encoding         : String = "UTF-8"
) extends 
  GitRuleArchiver with 
  Loggable with 
  GitArchiverUtils with 
  GitArchiverFullCommitUtils 
{


  override val relativePath = ruleRootDir
  override val tagPrefix = "archives/configurations-rules/"
  
  private[this] def newCrFile(ruleId:RuleId) = new File(getRootDirectory, ruleId.value + ".xml")
  
  def archiveRule(rule:Rule, doCommit:Option[(PersonIdent, Option[String])]) : Box[GitPath] = {
    val crFile = newCrFile(rule.id)
    val gitPath = toGitPath(crFile)
    for {   
      archive <- writeXml(
                     crFile
                   , ruleSerialisation.serialise(rule)
                   , "Archived rule: " + crFile.getPath
                 )
      commit  <- doCommit match {
                   case Some((commiter, reason)) => 
                     val msg = "Archive rule with ID '%s'%s".format(rule.id.value, GET(reason))
                     commitAddFile(commiter, gitPath, msg)
                   case None => Full("ok")
                 }
    } yield {
      GitPath(gitPath)
    }
  }

  def commitRules(commiter:PersonIdent, reason:Option[String]) : Box[GitArchiveId] = {
    this.commitFullGitPathContentAndTag(
        commiter
      , CONFIGURATION_RULES_ARCHIVE_TAG + " Commit all modification done on rules (git path: '%s')%s".format(ruleRootDir, GET(reason))
    )
  }
  
  def deleteRule(ruleId:RuleId, doCommit:Option[(PersonIdent, Option[String])]) : Box[GitPath] = {
    val crFile = newCrFile(ruleId)
    val gitPath = toGitPath(crFile)
    if(crFile.exists) {
      for {
        deleted  <- tryo { 
                      FileUtils.forceDelete(crFile) 
                      logger.debug("Deleted archive of rule: " + crFile.getPath)
                    }
        commited <- doCommit match {
                      case Some((commiter, reason)) => commitRmFile(commiter, gitPath, "Delete archive of rule with ID '%s'%s".format(ruleId.value, GET(reason)))
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
  //revert the order to start by the root of technique library. 
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
//////  Archive the active technique library (categories, techniques, directives) //////
///////////////////////////////////////////////////////////////////////////////////////////////

/**
 * A specific trait to create archive of an active technique category.
 * 
 * Basically, we directly map the category tree to file-system directories,
 * with the root category being the file denoted by "techniqueLibraryRootDir"
 * 
 */
class GitActiveTechniqueCategoryArchiverImpl(
    override val gitRepo                : GitRepositoryProvider
  , override val gitRootDirectory       : File
  , activeTechniqueCategorySerialisation: ActiveTechniqueCategorySerialisation
  , techniqueLibraryRootDir                : String //relative path !
  , override val xmlPrettyPrinter       : PrettyPrinter
  , override val encoding               : String = "UTF-8"
  , serializedCategoryName              : String = "category.xml"
) extends 
  GitActiveTechniqueCategoryArchiver with 
  Loggable with 
  GitArchiverUtils with 
  BuildCategoryPathName[ActiveTechniqueCategoryId] with 
  GitArchiverFullCommitUtils 
{


  override lazy val relativePath = techniqueLibraryRootDir
  override def  getCategoryName(categoryId:ActiveTechniqueCategoryId) = categoryId.value
  
  override lazy val tagPrefix = "archives/directives/"
  
  private[this] def newActiveTechniquecFile(uptcId:ActiveTechniqueCategoryId, parents: List[ActiveTechniqueCategoryId]) = {
    new File(newCategoryDirectory(uptcId, parents), serializedCategoryName) 
  }
  
  private[this] def archiveWithRename(uptc:ActiveTechniqueCategory
                                    , oldParents: Option[List[ActiveTechniqueCategoryId]]
                                    , newParents: List[ActiveTechniqueCategoryId]
                                    , gitCommit:Option[(PersonIdent, Option[String])]
  ) : Box[GitPath] = {
    
    val uptcFile = newActiveTechniquecFile(uptc.id, newParents)
    val gitPath = toGitPath(uptcFile)
    for {
      archive     <- writeXml(
                         uptcFile
                       , activeTechniqueCategorySerialisation.serialise(uptc)
                       , "Archived technique library category: " + uptcFile.getPath
                     )
      uptcGitPath =  gitPath
      commit      <- gitCommit match {
                       case Some((commiter, reason)) =>
                         oldParents match {
                           case Some(olds) => 
                             commitMvDirectory(commiter, toGitPath(newActiveTechniquecFile(uptc.id, olds)), uptcGitPath, "Move archive of technique library category with ID '%s'%s".format(uptc.id.value, GET(reason)))
                           case None       => 
                             commitAddFile(commiter, uptcGitPath, "Archive of technique library category with ID '%s'%s".format(uptc.id.value, GET(reason)))
                         }
                       case None => Full("ok")
                    }
    } yield {
      GitPath(gitPath)
    }
  }

  override def archiveActiveTechniqueCategory(uptc:ActiveTechniqueCategory, getParents: List[ActiveTechniqueCategoryId], gitCommit:Option[(PersonIdent, Option[String])]) : Box[GitPath] = {     
    archiveWithRename(uptc, None, getParents, gitCommit)
  }
  
  override def deleteActiveTechniqueCategory(uptcId:ActiveTechniqueCategoryId, getParents: List[ActiveTechniqueCategoryId], gitCommit:Option[(PersonIdent, Option[String])]) : Box[GitPath] = {
    val uptcFile = newActiveTechniquecFile(uptcId, getParents)
    val gitPath = toGitPath(uptcFile)
    if(uptcFile.exists) {
      for {
        //don't forget to delete the category *directory*
        deleted  <- tryo { 
                      FileUtils.forceDelete(uptcFile.getParentFile) 
                      logger.debug("Deleted archived technique library category: " + uptcFile.getPath)
                    }
        commited <- gitCommit match {
                      case Some((commiter,reason)) => commitRmFile(commiter, gitPath, "Delete archive of technique library category with ID '%s'%s".format(uptcId.value, GET(reason)))
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
  override def moveActiveTechniqueCategory(uptc:ActiveTechniqueCategory, oldParents: List[ActiveTechniqueCategoryId], newParents: List[ActiveTechniqueCategoryId], gitCommit:Option[(PersonIdent, Option[String])]) : Box[GitPath] = {
    for {
      deleted  <- deleteActiveTechniqueCategory(uptc.id, oldParents, None)
      archived <- archiveWithRename(uptc, Some(oldParents), newParents, gitCommit)
    } yield {
      archived
    }
  }
  
  /**
   * Commit modification done in the Git repository for any
   * category, technique and directive in the
   * active technique library.
   * Return the git commit id. 
   */
  override def commitActiveTechniqueLibrary(commiter:PersonIdent, reason:Option[String]) : Box[GitArchiveId] = {
    this.commitFullGitPathContentAndTag(
        commiter
      , POLICY_LIBRARY_ARCHIVE_TAG + " Commit all modification done in the active technique library (git path: '%s'%s)".format(techniqueLibraryRootDir, GET(reason))
    )
  }
}


trait ActiveTechniqueModificationCallback {
  
  //Name of the callback, for debugging
  def uptModificationCallbackName : String

  /**
   * What to do on activeTechnique save
   */
  def onArchive(activeTechnique:ActiveTechnique, parents: List[ActiveTechniqueCategoryId], gitCommit:Option[(PersonIdent, Option[String])]) : Box[Seq[DirectiveNotArchived]]

  /**
   * What to do on activeTechnique deletion
   */
  def onDelete(ptName:TechniqueName, getParents: List[ActiveTechniqueCategoryId], gitCommit:Option[(PersonIdent, Option[String])]) : Box[Unit]

  /**
   * What to do on activeTechnique move
   */
  def onMove(activeTechnique:ActiveTechnique, oldParents: List[ActiveTechniqueCategoryId], newParents: List[ActiveTechniqueCategoryId], gitCommit:Option[(PersonIdent, Option[String])]) : Box[Unit]
}

class UpdatePiOnActiveTechniqueEvent(
    gitDirectiveArchiver: GitDirectiveArchiver
  , techniqeRepository  : TechniqueRepository
  , directiveRepository : DirectiveRepository    
) extends ActiveTechniqueModificationCallback with Loggable {
  override val uptModificationCallbackName = "Update PI on UPT events"
  
  //TODO: why gitCommit is not used here ?
  override def onArchive(activeTechnique:ActiveTechnique, parents: List[ActiveTechniqueCategoryId], gitCommit:Option[(PersonIdent, Option[String])]) : Box[Seq[DirectiveNotArchived]] = {
    
    logger.debug("Executing archivage of PIs for UPT '%s'".format(activeTechnique))
    
    if(activeTechnique.directives.isEmpty) Full(Seq())
    else {
      //we are only interested in directive in error
      val notArchivedDirective = 
      for {
        directiveId          <- activeTechnique.directives
        optDirectiveArchived =  (for {
                                  directive  <- directiveRepository.getDirective(directiveId) ?~! "Can not find directive with id '%s' in repository".format(directiveId.value)
                                  technique  <- Box(techniqeRepository.get(TechniqueId(activeTechnique.techniqueName, directive.techniqueVersion))) ?~! "Can not find Technique '%s:%s'".format(activeTechnique.techniqueName.value, directive.techniqueVersion)
                                  archivedPi <- gitDirectiveArchiver.archiveDirective(directive, technique.id.name, parents, technique.rootSection, gitCommit)
                                } yield {
                                  archivedPi
                                }) match {
                                  case Full(_)   => None
                                  case Empty     => Some(DirectiveNotArchived(directiveId, Failure("No error message was left")))
                                  case f:Failure => Some(DirectiveNotArchived(directiveId, f))
                                }
      } yield {
        optDirectiveArchived
      }
      
      Full(notArchivedDirective.collect { case Some(x) => x } )
      
    }
  }
  
  override def onDelete(ptName:TechniqueName, getParents: List[ActiveTechniqueCategoryId], gitCommit:Option[(PersonIdent, Option[String])]) = Full({})
  override def onMove(activeTechnique:ActiveTechnique, oldParents: List[ActiveTechniqueCategoryId], newParents: List[ActiveTechniqueCategoryId], gitCommit:Option[(PersonIdent, Option[String])]) = Full({})
}

/**
 * A specific trait to create archive of an active technique.
 */
class GitActiveTechniqueArchiverImpl(
    override val gitRepo           : GitRepositoryProvider
  , override val gitRootDirectory  : File
  , activeTechniqueSerialisation   : ActiveTechniqueSerialisation
  , techniqueLibraryRootDir        : String //relative path !
  , override val xmlPrettyPrinter  : PrettyPrinter
  , override val encoding          : String = "UTF-8"
  , val uptModificationCallback    : Buffer[ActiveTechniqueModificationCallback] = Buffer()
  , val activeTechniqueFileName : String = "activeTechniqueSettings.xml"
) extends GitActiveTechniqueArchiver with Loggable with GitArchiverUtils with BuildCategoryPathName[ActiveTechniqueCategoryId] {

  override lazy val relativePath = techniqueLibraryRootDir
  override def  getCategoryName(categoryId:ActiveTechniqueCategoryId) = categoryId.value

  private[this] def newActiveTechniqueFile(ptName:TechniqueName, parents: List[ActiveTechniqueCategoryId]) = {
    //parents can not be null: we must have at least the root category
    parents match {
      case Nil => Failure("UPT '%s' was asked to be saved in a category which does not exists (empty list of parents, not even the root cateogy was given!)".format(ptName.value))
      case h::tail => Full(new File(new File(newCategoryDirectory(h,tail),ptName.value), activeTechniqueFileName))
    }
  }
  
  override def archiveActiveTechnique(activeTechnique:ActiveTechnique, parents: List[ActiveTechniqueCategoryId], gitCommit:Option[(PersonIdent, Option[String])]) : Box[(GitPath, Seq[DirectiveNotArchived])] = {     
    for {
      uptFile   <- newActiveTechniqueFile(activeTechnique.techniqueName, parents)
      gitPath   =  toGitPath(uptFile)
      archive   <- writeXml(
                       uptFile
                     , activeTechniqueSerialisation.serialise(activeTechnique)
                     , "Archived technique library template: " + uptFile.getPath
                   )
      //strategy for callbaack:
      //if at least one callback is in error, we don't execute the others and the full ActiveTechnique is in error.
      //if none is in error, we are going to next step             
      callbacks <- sequence(uptModificationCallback) { _.onArchive(activeTechnique, parents, gitCommit) }
      commit    <- gitCommit match {
                     case Some((commiter, reason)) => commitAddFile(commiter, gitPath, "Archive of technique library template for technique name '%s'%s".format(activeTechnique.techniqueName.value, GET(reason)))
                     case None => Full("ok")
                   }
    } yield {
      (GitPath(gitPath), callbacks.flatten)
    }
  }
  
  override def deleteActiveTechnique(ptName:TechniqueName, parents: List[ActiveTechniqueCategoryId], gitCommit:Option[(PersonIdent, Option[String])]) : Box[GitPath] = {
    newActiveTechniqueFile(ptName, parents) match {
      case Full(uptFile) if(uptFile.exists) =>
        for {
          //don't forget to delete the category *directory*
          deleted  <- tryo { 
                        if(uptFile.exists) FileUtils.forceDelete(uptFile) 
                        logger.debug("Deleted archived technique library template: " + uptFile.getPath)
                      }
          gitPath   =  toGitPath(uptFile)
          callbacks <- sequence(uptModificationCallback) { _.onDelete(ptName, parents, None) }
          commited <- gitCommit match {
                        case Some((commiter,reason)) => commitRmFile(commiter, gitPath, "Delete archive of technique library template for technique name '%s'%s".format(ptName.value, GET(reason)))
                        case None => Full("OK")
                      }
        } yield {
          GitPath(gitPath)
        }
      case other => other.map(f => GitPath(toGitPath(f)))
    }
  }
 
  /*
   * For that one, we have to move the directory of the active techniques 
   * to its new parent location. 
   * If the commit has to be done, we have to add all files under that new repository,
   * and remove from old one.
   * 
   * As we can't know at all if all PI currently defined for an UPT were saved, we
   * DO have to always consider a fresh new archive. 
   */
  override def moveActiveTechnique(activeTechnique:ActiveTechnique, oldParents: List[ActiveTechniqueCategoryId], newParents: List[ActiveTechniqueCategoryId], gitCommit:Option[(PersonIdent, Option[String])]) : Box[(GitPath, Seq[DirectiveNotArchived])] = {
    for {
      oldActiveTechniqueFile      <- newActiveTechniqueFile(activeTechnique.techniqueName, oldParents)
      oldActiveTechniqueDirectory =  oldActiveTechniqueFile.getParentFile
      newActiveTechniqueFile      <- newActiveTechniqueFile(activeTechnique.techniqueName, newParents)
      newActiveTechniqueDirectory =  newActiveTechniqueFile.getParentFile
      clearNew        <- tryo {
                           if(newActiveTechniqueDirectory.exists) FileUtils.forceDelete(newActiveTechniqueDirectory)
                           else "ok"
                         }
      deleteOld       <- tryo {
                           if(oldActiveTechniqueDirectory.exists) FileUtils.forceDelete(oldActiveTechniqueDirectory)
                           else "ok"
                         }
      archived        <- archiveActiveTechnique(activeTechnique, newParents, gitCommit)
      commited        <- gitCommit match {
                           case Some((commiter, reason)) => 
                             commitMvDirectory(
                                 commiter
                               , toGitPath(oldActiveTechniqueDirectory)
                               , toGitPath(newActiveTechniqueDirectory)
                               , "Move active technique for technique name '%s'%s".format(activeTechnique.techniqueName.value, GET(reason))
                             )
                           case None => Full("OK")
                         }
    } yield {
      archived
    }
  }
}


/**
 * A specific trait to create archive of an active technique.
 */
class GitDirectiveArchiverImpl(
    override val gitRepo           : GitRepositoryProvider
  , override val gitRootDirectory  : File
  , directiveSerialisation         : DirectiveSerialisation
  , techniqueLibraryRootDir        : String //relative path !
  , override val xmlPrettyPrinter  : PrettyPrinter
  , override val encoding          : String = "UTF-8"
) extends GitDirectiveArchiver with Loggable with GitArchiverUtils with BuildCategoryPathName[ActiveTechniqueCategoryId] {

  override lazy val relativePath = techniqueLibraryRootDir
  override def  getCategoryName(categoryId:ActiveTechniqueCategoryId) = categoryId.value

  private[this] def newPiFile(
      directiveId   : DirectiveId
    , ptName : TechniqueName
    , parents: List[ActiveTechniqueCategoryId]
  ) = {
    parents match {
      case Nil => Failure("Can not save directive '%s' for technique '%s' because no category (not even the root one) was given as parent for that technique".format(directiveId.value, ptName.value))
      case h::tail => 
        Full(new File(new File(newCategoryDirectory(h, tail), ptName.value), directiveId.value+".xml"))
    }
  }
  
  override def archiveDirective(
      directive          : Directive
    , ptName             : TechniqueName
    , catIds             : List[ActiveTechniqueCategoryId]
    , variableRootSection: SectionSpec
    , gitCommit          : Option[(PersonIdent, Option[String])]
  ) : Box[GitPath] = {
        
    for {
      piFile  <- newPiFile(directive.id, ptName, catIds)
      gitPath =  toGitPath(piFile)
      archive <- writeXml( 
                     piFile
                   , directiveSerialisation.serialise(ptName, variableRootSection, directive)
                   , "Archived directive: " + piFile.getPath
                 )
      commit  <- gitCommit match {
                   case Some((commiter,reason)) => commitAddFile(commiter, gitPath, "Archive directive with ID '%s'%s".format(directive.id.value,GET(reason)))
                   case None => Full("ok")
                 }
    } yield {
      GitPath(gitPath)
    }    
  }
    
  /**
   * Delete an archived directive. 
   * If gitCommit is true, the modification is
   * saved in git. Else, no modification in git are saved.
   */
  override def deleteDirective(
      directiveId:DirectiveId
    , ptName   : TechniqueName
    , catIds   : List[ActiveTechniqueCategoryId]
    , gitCommit: Option[(PersonIdent, Option[String])]
  ) : Box[GitPath] = {
    newPiFile(directiveId, ptName, catIds) match {
      case Full(piFile) if(piFile.exists) =>
        for {
          deleted  <- tryo { 
                        FileUtils.forceDelete(piFile) 
                        logger.debug("Deleted archive of directive: " + piFile.getPath)
                      }
          gitPath  =  toGitPath(piFile)
          commited <- gitCommit match {
                        case Some((commiter,reason)) => commitRmFile(commiter, gitPath, "Delete archive of directive with ID '%s'%s".format(directiveId.value,GET(reason)))
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
  
  override def archiveNodeGroupCategory(ngc:NodeGroupCategory, parents: List[NodeGroupCategoryId], gitCommit:Option[(PersonIdent, Option[String])]) : Box[GitPath] = {     
    val ngcFile = newNgFile(ngc.id, parents)
    
    for {
      archive   <- writeXml(
                         ngcFile
                       , nodeGroupCategorySerialisation.serialise(ngc)
                       , "Archived node group category: " + ngcFile.getPath
                    )
      gitPath    =  toGitPath(ngcFile)
      commit     <- gitCommit match {
                      case Some((commiter,reason)) => commitAddFile(commiter, gitPath, "Archive of node group category with ID '%s'%s".format(ngc.id.value,GET(reason)))
                      case None => Full("ok")
                    }
    } yield {
      GitPath(gitPath)
    }
  }
  
  override def deleteNodeGroupCategory(ngcId:NodeGroupCategoryId, getParents: List[NodeGroupCategoryId], gitCommit:Option[(PersonIdent, Option[String])]) : Box[GitPath] = {
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
                      case Some((commiter,reason)) => commitRmFile(commiter, gitPath, "Delete archive of node group category with ID '%s'%s".format(ngcId.value,GET(reason)))
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
  override def moveNodeGroupCategory(ngc:NodeGroupCategory, oldParents: List[NodeGroupCategoryId], newParents: List[NodeGroupCategoryId], gitCommit:Option[(PersonIdent, Option[String])]) : Box[GitPath] = {
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
                   case Some((commiter,reason)) => commitMvDirectory(commiter, toGitPath(oldNgcDir), toGitPath(newNgcDir), "Move archive of node group category with ID '%s'%s".format(ngc.id.value,GET(reason)))
                   case None => Full("ok")
                 }
    } yield {
      GitPath(toGitPath(archive))
    }
  }
  
  /**
   * Commit modification done in the Git repository for any
   * category, technique and directive in the
   * active technique library.
   * Return the git commit id. 
   */
  override def commitGroupLibrary(commiter: PersonIdent, reason:Option[String]) : Box[GitArchiveId] = {
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
 * with the root category being the file denoted by "techniqueLibraryRootDir"
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
  
  override def archiveNodeGroup(ng:NodeGroup, parents: List[NodeGroupCategoryId], gitCommit:Option[(PersonIdent, Option[String])]) : Box[GitPath] = {     
    for {
      ngFile    <- newNgFile(ng.id, parents)
      archive   <- writeXml(
                        ngFile
                      , nodeGroupSerialisation.serialise(ng)
                      , "Archived node group: " + ngFile.getPath
                    )
      commit     <- gitCommit match {
                      case Some((commiter,reason)) => commitAddFile(commiter, toGitPath(ngFile), "Archive of node group with ID '%s'%s".format(ng.id.value,GET(reason)))
                      case None => Full("ok")
                    }
    } yield {
      GitPath(toGitPath(archive))
    }
  }
  
  override def deleteNodeGroup(ngId:NodeGroupId, getParents: List[NodeGroupCategoryId], gitCommit:Option[(PersonIdent, Option[String])]) : Box[GitPath] = {
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
                          case Some((commiter,reason)) => commitRmFile(commiter, gitPath, "Delete archive of node group with ID '%s'%s".format(ngId.value,GET(reason)))
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
  
  override def moveNodeGroup(ng:NodeGroup, oldParents: List[NodeGroupCategoryId], newParents: List[NodeGroupCategoryId], gitCommit:Option[(PersonIdent, Option[String])]) : Box[GitPath] = {
    
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
                        case Some((commiter,reason)) => commitMvDirectory(commiter, toGitPath(oldNgXmlFile), toGitPath(newNgXmlFile), "Move archive of node group with ID '%s'%s".format(ng.id.value,GET(reason)))
                        case None => Full("ok")
                      }
    } yield {
      GitPath(toGitPath(archive))
    }
  }

}



