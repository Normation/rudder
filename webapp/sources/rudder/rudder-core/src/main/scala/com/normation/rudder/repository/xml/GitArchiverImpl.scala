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

import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.domain.SectionSpec
import com.normation.cfclerk.services.GitRepositoryProvider
import com.normation.rudder.domain.policies._
import com.normation.rudder.repository._
import com.normation.rudder.services.marshalling._
import com.normation.cfclerk.services.TechniqueRepository
import com.normation.rudder.domain.Constants.{CONFIGURATION_RULES_ARCHIVE_TAG, GROUPS_ARCHIVE_TAG, PARAMETERS_ARCHIVE_TAG, POLICY_LIBRARY_ARCHIVE_TAG}
import com.normation.rudder.domain.nodes.NodeGroupCategoryId
import com.normation.rudder.domain.nodes.NodeGroupCategory
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.nodes.NodeGroup
import java.io.File

import com.normation.NamedZioLogger
import org.apache.commons.io.FileUtils
import org.eclipse.jgit.lib.PersonIdent
import net.liftweb.common._

import scala.collection.mutable.Buffer
import com.normation.cfclerk.domain.TechniqueId
import com.normation.eventlog.ModificationId
import com.normation.rudder.domain.parameters.GlobalParameter
import com.normation.rudder.domain.parameters.ParameterName
import com.normation.errors._
import scalaz.zio._
import scalaz.zio.syntax._

class GitRuleArchiverImpl(
    override val gitRepo                   : GitRepositoryProvider
  , override val gitRootDirectory          : File
  , ruleSerialisation                      : RuleSerialisation
  , ruleRootDir                            : String //relative path !
  , override val xmlPrettyPrinter          : RudderPrettyPrinter
  , override val gitModificationRepository : GitModificationRepository
  , override val encoding                  : String = "UTF-8"
) extends
  GitRuleArchiver with
  NamedZioLogger with
  GitArchiverUtils with
  GitArchiverFullCommitUtils
{


  override def loggerName: String = this.getClass.getName
  override val relativePath = ruleRootDir
  override val tagPrefix = "archives/configurations-rules/"

  private[this] def newCrFile(ruleId: RuleId) = new File(getRootDirectory, ruleId.value + ".xml")

  def archiveRule(rule:Rule, doCommit: Option[(ModificationId, PersonIdent, Option[String])]) : IOResult[GitPath] = {
    val crFile  = newCrFile(rule.id)
    val gitPath = toGitPath(crFile)

    for {
      archive <- writeXml(
                     crFile
                   , ruleSerialisation.serialise(rule)
                   , "Archived rule: " + crFile.getPath
                 )
      commit  <- doCommit match {
                   case Some((modId, commiter, reason)) =>
                     commitAddFile(modId, commiter, gitPath, s"Archive rule with ID '${rule.id.value}'${GET(reason)}")
                   case None => UIO.unit
                 }
    } yield {
      GitPath(gitPath)
    }
  }

  def commitRules(modId: ModificationId, commiter:PersonIdent, reason:Option[String]) : IOResult[GitArchiveId] = {
    this.commitFullGitPathContentAndTag(
        commiter
      , CONFIGURATION_RULES_ARCHIVE_TAG + " Commit all modification done on rules (git path: '%s')%s".format(ruleRootDir, GET(reason))
    )
  }

  def deleteRule(ruleId:RuleId, doCommit:Option[(ModificationId, PersonIdent, Option[String])]) : IOResult[GitPath] = {
    val crFile = newCrFile(ruleId)
    val gitPath = toGitPath(crFile)
    if(crFile.exists) {
      for {
        deleted  <- IOResult.effect(FileUtils.forceDelete(crFile))
        _        <- logPure.debug("Deleted archive of rule: " + crFile.getPath)
        commited <- doCommit match {
                      case Some((modId, commiter, reason)) =>
                        commitRmFile(modId, commiter, gitPath, s"Delete archive of rule with ID '${ruleId.value}'${GET(reason)}")
                      case None => UIO.unit
                    }
      } yield {
        GitPath(gitPath)
      }
    } else {
      GitPath(gitPath).succeed
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
  , techniqueLibraryRootDir             : String //relative path !
  , override val xmlPrettyPrinter       : RudderPrettyPrinter
  , override val gitModificationRepository : GitModificationRepository
  , override val encoding               : String = "UTF-8"
  , serializedCategoryName              : String = "category.xml"
) extends
  GitActiveTechniqueCategoryArchiver with
  Loggable with
  GitArchiverUtils with
  BuildCategoryPathName[ActiveTechniqueCategoryId] with
  GitArchiverFullCommitUtils
{


  override def loggerName: String = this.getClass.getName
  override lazy val relativePath = techniqueLibraryRootDir
  override def  getCategoryName(categoryId:ActiveTechniqueCategoryId) = categoryId.value

  override lazy val tagPrefix = "archives/directives/"

  private[this] def newActiveTechniquecFile(uptcId:ActiveTechniqueCategoryId, parents: List[ActiveTechniqueCategoryId]) = {
    new File(newCategoryDirectory(uptcId, parents), serializedCategoryName)
  }

  private[this] def archiveWithRename(uptc:ActiveTechniqueCategory
                                    , oldParents: Option[List[ActiveTechniqueCategoryId]]
                                    , newParents: List[ActiveTechniqueCategoryId]
                                    , gitCommit:Option[(ModificationId,PersonIdent, Option[String])]
  ) : IOResult[GitPath] = {

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
                       case Some((modId,commiter, reason)) =>
                         oldParents match {
                           case Some(olds) =>
                             commitMvDirectory(modId, commiter, toGitPath(newActiveTechniquecFile(uptc.id, olds)), uptcGitPath, "Move archive of technique library category with ID '%s'%s".format(uptc.id.value, GET(reason)))
                           case None       =>
                             commitAddFile(modId, commiter, uptcGitPath, "Archive of technique library category with ID '%s'%s".format(uptc.id.value, GET(reason)))
                         }
                       case None => UIO.unit
                    }
    } yield {
      GitPath(gitPath)
    }
  }

  override def archiveActiveTechniqueCategory(uptc:ActiveTechniqueCategory, getParents: List[ActiveTechniqueCategoryId], gitCommit:Option[(ModificationId,PersonIdent, Option[String])]) : IOResult[GitPath] = {
    archiveWithRename(uptc, None, getParents, gitCommit)
  }

  override def deleteActiveTechniqueCategory(uptcId:ActiveTechniqueCategoryId, getParents: List[ActiveTechniqueCategoryId], gitCommit:Option[(ModificationId, PersonIdent, Option[String])]) : IOResult[GitPath] = {
    val uptcFile = newActiveTechniquecFile(uptcId, getParents)
    val gitPath = toGitPath(uptcFile)
    if(uptcFile.exists) {
      for {
        //don't forget to delete the category *directory*
        deleted  <- IOResult.effect(FileUtils.forceDelete(uptcFile))
        _        <- logPure.debug("Deleted archived technique library category: " + uptcFile.getPath)
        commited <- gitCommit match {
                      case Some((modId, commiter, reason)) =>
                        commitRmFile(modId, commiter, gitPath, s"Delete archive of technique library category with ID '${uptcId.value}'${GET(reason)}")
                      case None => UIO.unit
                    }
      } yield {
        GitPath(gitPath)
      }
    } else {
      GitPath(gitPath).succeed
    }
  }

  // TODO : keep content when moving !!!
  // well, for now, that's ok, because we can only move empty categories
  override def moveActiveTechniqueCategory(uptc:ActiveTechniqueCategory, oldParents: List[ActiveTechniqueCategoryId], newParents: List[ActiveTechniqueCategoryId], gitCommit:Option[(ModificationId,PersonIdent, Option[String])]) : IOResult[GitPath] = {
    if(oldParents == newParents) { //actually, an update
      this.archiveActiveTechniqueCategory(uptc, oldParents, gitCommit)
    } else {
      for {
        deleted  <- deleteActiveTechniqueCategory(uptc.id, oldParents, None)
        archived <- archiveWithRename(uptc, Some(oldParents), newParents, gitCommit)
      } yield {
        archived
      }
    }
  }

  /**
   * Commit modification done in the Git repository for any
   * category, technique and directive in the
   * active technique library.
   * Return the git commit id.
   */
  override def commitActiveTechniqueLibrary(modId: ModificationId, commiter:PersonIdent, reason:Option[String]) : IOResult[GitArchiveId] = {
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
  def onArchive(activeTechnique:ActiveTechnique, parents: List[ActiveTechniqueCategoryId], gitCommit:Option[(ModificationId, PersonIdent, Option[String])]) : IOResult[Seq[DirectiveNotArchived]]

  /**
   * What to do on activeTechnique deletion
   */
  def onDelete(ptName:TechniqueName, getParents: List[ActiveTechniqueCategoryId], gitCommit:Option[(ModificationId, PersonIdent, Option[String])]) : IOResult[Unit]

  /**
   * What to do on activeTechnique move
   */
  def onMove(activeTechnique:ActiveTechnique, oldParents: List[ActiveTechniqueCategoryId], newParents: List[ActiveTechniqueCategoryId], gitCommit:Option[(ModificationId, PersonIdent, Option[String])]) : IOResult[Unit]
}

class UpdatePiOnActiveTechniqueEvent(
    gitDirectiveArchiver: GitDirectiveArchiver
  , techniqeRepository  : TechniqueRepository
  , directiveRepository : RoDirectiveRepository
) extends ActiveTechniqueModificationCallback with NamedZioLogger {
  override val uptModificationCallbackName = "Update PI on UPT events"

  override def loggerName: String = this.getClass.getName
  //TODO: why gitCommit is not used here ?
  override def onArchive(activeTechnique:ActiveTechnique, parents: List[ActiveTechniqueCategoryId], gitCommit:Option[(ModificationId, PersonIdent, Option[String])]) : IOResult[Seq[DirectiveNotArchived]] = {

    logPure.debug("Executing archivage of PIs for UPT '%s'".format(activeTechnique))

    ZIO.foreach(activeTechnique.directives) { directiveId =>
      for {
        directive            <- directiveRepository.getDirective(directiveId).notOptional(s"Can not find directive with id '${directiveId.value}' in repository but it is viewed as a child of '${activeTechnique.id.value}'. This is likely a bug, please report it.")
        optDirectiveArchived <- ( if (directive.isSystem) {
                                  None.succeed
                                } else {
                                  for {
                                    technique  <- techniqeRepository.get(TechniqueId(activeTechnique.techniqueName, directive.techniqueVersion)).notOptional(s"Can not find Technique '${activeTechnique.techniqueName.value}:${directive.techniqueVersion}'")
                                    archivedPi <- gitDirectiveArchiver.archiveDirective(directive, technique.id.name, parents, technique.rootSection, gitCommit)
                                  } yield {
                                      None
                                  }
                                }) catchAll { err => Some(DirectiveNotArchived(directiveId, err)).succeed }

      } yield {
        optDirectiveArchived
      }
    }.map( _.flatten )
  }

  override def onDelete(ptName:TechniqueName, getParents: List[ActiveTechniqueCategoryId], gitCommit:Option[(ModificationId, PersonIdent, Option[String])]) = UIO.unit
  override def onMove(activeTechnique:ActiveTechnique, oldParents: List[ActiveTechniqueCategoryId], newParents: List[ActiveTechniqueCategoryId], gitCommit:Option[(ModificationId, PersonIdent, Option[String])]) = UIO.unit
}

/**
 * A specific trait to create archive of an active technique.
 */
class GitActiveTechniqueArchiverImpl(
    override val gitRepo           : GitRepositoryProvider
  , override val gitRootDirectory  : File
  , activeTechniqueSerialisation   : ActiveTechniqueSerialisation
  , techniqueLibraryRootDir        : String //relative path !
  , override val xmlPrettyPrinter  : RudderPrettyPrinter
  , override val gitModificationRepository : GitModificationRepository
  , override val encoding          : String = "UTF-8"
  , val uptModificationCallback    : Buffer[ActiveTechniqueModificationCallback] = Buffer()
  , val activeTechniqueFileName : String = "activeTechniqueSettings.xml"
) extends GitActiveTechniqueArchiver with NamedZioLogger with GitArchiverUtils with BuildCategoryPathName[ActiveTechniqueCategoryId] {

  override def loggerName: String = this.getClass.getName
  override lazy val relativePath = techniqueLibraryRootDir
  override def  getCategoryName(categoryId:ActiveTechniqueCategoryId) = categoryId.value

  private[this] def newActiveTechniqueFile(ptName:TechniqueName, parents: List[ActiveTechniqueCategoryId]) = {
    //parents can not be null: we must have at least the root category
    parents match {
      case Nil => Unconsistancy(s"Active Techniques '${ptName.value}' was asked to be saved in a category which does not exist (empty list of parents, not even the root cateogy was given!)").fail
      case h::tail => new File(new File(newCategoryDirectory(h,tail),ptName.value), activeTechniqueFileName).succeed
    }
  }

  override def archiveActiveTechnique(activeTechnique:ActiveTechnique, parents: List[ActiveTechniqueCategoryId], gitCommit:Option[(ModificationId, PersonIdent, Option[String])]) : IOResult[(GitPath, Seq[DirectiveNotArchived])] = {
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
      callbacks <- ZIO.foreach(uptModificationCallback) { _.onArchive(activeTechnique, parents, gitCommit) }
      commit    <- gitCommit match {
                     case Some((modId, commiter, reason)) => commitAddFile(modId, commiter, gitPath, s"Archive of technique library template for technique name '${activeTechnique.techniqueName.value}'${GET(reason)}")
                     case None => UIO.unit
                   }
    } yield {
      (GitPath(gitPath), callbacks.flatten)
    }
  }

  override def deleteActiveTechnique(ptName:TechniqueName, parents: List[ActiveTechniqueCategoryId], gitCommit:Option[(ModificationId, PersonIdent, Option[String])]) : IOResult[GitPath] = {
    for {
      atFile <- newActiveTechniqueFile(ptName, parents)
      exists <- IOResult.effect(atFile.exists)
      res    <- if(exists) {
                  for {
                    //don't forget to delete the category *directory*
                    deleted <- IOResult.effect(FileUtils.forceDelete(atFile))
                    _       =  logPure.debug(s"Deleted archived technique library template: ${atFile.getPath}")
                    gitPath =  toGitPath(atFile)
                    callbacks <- ZIO.foreach(uptModificationCallback) { _.onDelete(ptName, parents, None) }
                    commited <- gitCommit match {
                                  case Some((modId, commiter, reason)) =>
                                    commitRmFile(modId, commiter, gitPath, s"Delete archive of technique library template for technique name '${ptName.value}'${GET(reason)}")
                                  case None => UIO.unit
                                }
                  } yield {
                    GitPath(gitPath)
                  }
                } else {
                  GitPath(toGitPath(atFile)).succeed
                }
    } yield {
      res
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
  override def moveActiveTechnique(activeTechnique:ActiveTechnique, oldParents: List[ActiveTechniqueCategoryId], newParents: List[ActiveTechniqueCategoryId], gitCommit:Option[(ModificationId, PersonIdent, Option[String])]) : IOResult[(GitPath, Seq[DirectiveNotArchived])] = {
    if(oldParents == newParents) {//actually an update
      this.archiveActiveTechnique(activeTechnique, oldParents, gitCommit)
    } else {
      for {
        oldActiveTechniqueFile      <- newActiveTechniqueFile(activeTechnique.techniqueName, oldParents)
        oldActiveTechniqueDirectory <- IOResult.effect(oldActiveTechniqueFile.getParentFile)
        newActiveTechniqueFile      <- newActiveTechniqueFile(activeTechnique.techniqueName, newParents)
        newActiveTechniqueDirectory <- IOResult.effect(newActiveTechniqueFile.getParentFile)
        existsNew                   <- IOResult.effect(newActiveTechniqueDirectory.exists)
        clearNew                    <- if(existsNew) IOResult.effect(FileUtils.forceDelete(newActiveTechniqueDirectory))
                                       else UIO.unit
        existsOld                   <- IOResult.effect(oldActiveTechniqueDirectory.exists)
        deleteOld                   <- if(existsOld) IOResult.effect(FileUtils.forceDelete(oldActiveTechniqueDirectory))
                                       else UIO.unit
        archived                    <- archiveActiveTechnique(activeTechnique, newParents, gitCommit)
        commited                    <- gitCommit match {
                                         case Some((modId, commiter, reason)) =>
                                           commitMvDirectory(
                                               modId
                                             , commiter
                                             , toGitPath(oldActiveTechniqueDirectory)
                                             , toGitPath(newActiveTechniqueDirectory)
                                             , "Move active technique for technique name '%s'%s".format(activeTechnique.techniqueName.value, GET(reason))
                                           )
                                         case None => UIO.unit
                                       }
      } yield {
        archived
      }
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
  , override val xmlPrettyPrinter  : RudderPrettyPrinter
  , override val gitModificationRepository : GitModificationRepository
  , override val encoding          : String = "UTF-8"
) extends GitDirectiveArchiver with NamedZioLogger with GitArchiverUtils with BuildCategoryPathName[ActiveTechniqueCategoryId] {


  override def loggerName: String = this.getClass.getName
  override lazy val relativePath = techniqueLibraryRootDir
  override def  getCategoryName(categoryId:ActiveTechniqueCategoryId) = categoryId.value

  private[this] def newPiFile(
      directiveId   : DirectiveId
    , ptName : TechniqueName
    , parents: List[ActiveTechniqueCategoryId]
  ) = {
    parents match {
      case Nil => Unconsistancy("Can not save directive '%s' for technique '%s' because no category (not even the root one) was given as parent for that technique".format(directiveId.value, ptName.value)).fail
      case h::tail =>
        new File(new File(newCategoryDirectory(h, tail), ptName.value), directiveId.value+".xml").succeed
    }
  }

  override def archiveDirective(
      directive          : Directive
    , ptName             : TechniqueName
    , catIds             : List[ActiveTechniqueCategoryId]
    , variableRootSection: SectionSpec
    , gitCommit          : Option[(ModificationId, PersonIdent, Option[String])]
  ) : IOResult[GitPath] = {

    for {
      piFile  <- newPiFile(directive.id, ptName, catIds)
      gitPath =  toGitPath(piFile)
      archive <- writeXml(
                     piFile
                   , directiveSerialisation.serialise(ptName, variableRootSection, directive)
                   , "Archived directive: " + piFile.getPath
                 )
      commit  <- gitCommit match {
                   case Some((modId, commiter, reason)) => commitAddFile(modId, commiter, gitPath, "Archive directive with ID '%s'%s".format(directive.id.value,GET(reason)))
                   case None => UIO.unit
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
    , gitCommit: Option[(ModificationId, PersonIdent, Option[String])]
  ) : IOResult[GitPath] = {
    for {
      piFile <- newPiFile(directiveId, ptName, catIds)
      exists <- IOResult.effect(piFile.exists)
      res    <- if(exists) {
                  for {
                    deleted  <- IOResult.effect(FileUtils.forceDelete(piFile))
                    _        <- logPure.debug(s"Deleted archive of directive: '${piFile.getPath}'")
                    gitPath  =  toGitPath(piFile)
                    commited <- gitCommit match {
                                  case Some((modId, commiter, reason)) =>
                                    commitRmFile(modId, commiter, gitPath, s"Delete archive of directive with ID '${directiveId.value}'${GET(reason)}")
                                  case None => UIO.unit
                                }
                  } yield {
                    GitPath(gitPath)
                  }
                } else {
                  GitPath(toGitPath(piFile)).succeed
                }
    } yield {
      res
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
 */
class GitNodeGroupArchiverImpl(
    override val gitRepo          : GitRepositoryProvider
  , override val gitRootDirectory : File
  , nodeGroupSerialisation        : NodeGroupSerialisation
  , nodeGroupCategorySerialisation: NodeGroupCategorySerialisation
  , groupLibraryRootDir           : String //relative path !
  , override val xmlPrettyPrinter : RudderPrettyPrinter
  , override val gitModificationRepository : GitModificationRepository
  , override val encoding         : String = "UTF-8"
  , serializedCategoryName        : String = "category.xml"
) extends
  GitNodeGroupArchiver with
  NamedZioLogger with
  GitArchiverUtils with
  BuildCategoryPathName[NodeGroupCategoryId] with
  GitArchiverFullCommitUtils {


  override def loggerName: String = this.getClass.getName
  override lazy val relativePath = groupLibraryRootDir
  override def  getCategoryName(categoryId:NodeGroupCategoryId) = categoryId.value

  override lazy val tagPrefix = "archives/groups/"

  private[this] def newNgFile(ngcId:NodeGroupCategoryId, parents: List[NodeGroupCategoryId]) = {
    new File(newCategoryDirectory(ngcId, parents), serializedCategoryName)
  }

  override def archiveNodeGroupCategory(ngc:NodeGroupCategory, parents: List[NodeGroupCategoryId], gitCommit:Option[(ModificationId, PersonIdent, Option[String])]) : IOResult[GitPath] = {
    val ngcFile = newNgFile(ngc.id, parents)

    for {
      archive   <- writeXml(
                         ngcFile
                       , nodeGroupCategorySerialisation.serialise(ngc)
                       , "Archived node group category: " + ngcFile.getPath
                    )
      gitPath    =  toGitPath(ngcFile)
      commit     <- gitCommit match {
                      case Some((modId, commiter, reason)) => commitAddFile(modId, commiter, gitPath, "Archive of node group category with ID '%s'%s".format(ngc.id.value,GET(reason)))
                      case None => UIO.unit
                    }
    } yield {
      GitPath(gitPath)
    }
  }

  override def deleteNodeGroupCategory(ngcId:NodeGroupCategoryId, getParents: List[NodeGroupCategoryId], gitCommit:Option[(ModificationId, PersonIdent, Option[String])]) : IOResult[GitPath] = {
    val ngcFile = newNgFile(ngcId, getParents)
    val gitPath = toGitPath(ngcFile)
    if(ngcFile.exists) {
      for {
        //don't forget to delete the category *directory*
        deleted  <- IOResult.effect(FileUtils.forceDelete(ngcFile))
        _        <- logPure.debug(s"Deleted archived node group category: ${ngcFile.getPath}")
        commited <- gitCommit match {
                      case Some((modId, commiter, reason)) =>
                        commitRmFile(modId, commiter, gitPath, s"Delete archive of node group category with ID '${ngcId.value}'${GET(reason)}")
                      case None => UIO.unit
                    }
      } yield {
        GitPath(gitPath)
      }
    } else {
      GitPath(gitPath).succeed
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
  override def moveNodeGroupCategory(ngc:NodeGroupCategory, oldParents: List[NodeGroupCategoryId], newParents: List[NodeGroupCategoryId], gitCommit:Option[(ModificationId, PersonIdent, Option[String])]) : IOResult[GitPath] = {
    if(oldParents == newParents) { //actually, it's an archive, not a move
      this.archiveNodeGroupCategory(ngc, oldParents, gitCommit)
    } else {

      val oldNgcDir = newNgFile(ngc.id, oldParents).getParentFile
      val newNgcXmlFile = newNgFile(ngc.id, newParents)
      val newNgcDir = newNgcXmlFile.getParentFile

      for {
        archive <- writeXml(
                       newNgcXmlFile
                     , nodeGroupCategorySerialisation.serialise(ngc)
                     , "Archived node group category: " + newNgcXmlFile.getPath
                   )
        canMove <- IOResult.effect(null != oldNgcDir && oldNgcDir.exists)
        moved   <- if(canMove) {
                     IOResult.effectM(if(oldNgcDir.isDirectory) {
                         //move content except category.xml
                         ZIO.foreach(oldNgcDir.listFiles.toSeq.filter( f => f.getName != serializedCategoryName)) { f =>
                           IOResult.effect(FileUtils.moveToDirectory(f, newNgcDir, false))
                         }
                       } else {
                        UIO.unit
                       }
                     ) *>
                     //in all case, delete the file at the old directory path
                     IOResult.effect(FileUtils.deleteQuietly(oldNgcDir))
                   } else UIO.unit

        commit  <- gitCommit match {
                     case Some((modId, commiter, reason)) => commitMvDirectory(modId, commiter, toGitPath(oldNgcDir), toGitPath(newNgcDir), "Move archive of node group category with ID '%s'%s".format(ngc.id.value,GET(reason)))
                     case None => UIO.unit
                   }
      } yield {
        GitPath(toGitPath(archive))
      }
    }
  }

  /**
   * Commit modification done in the Git repository for any
   * category, technique and directive in the
   * active technique library.
   * Return the git commit id.
   */
  override def commitGroupLibrary(modificationId:ModificationId, commiter: PersonIdent, reason:Option[String]) : IOResult[GitArchiveId] = {
    this.commitFullGitPathContentAndTag(
        commiter
      , GROUPS_ARCHIVE_TAG + " Commit all modification done in Groups (git path: '%s')".format(groupLibraryRootDir)
    )
  }


  private[this] def newNgFile(ngId:NodeGroupId, parents: List[NodeGroupCategoryId]) = {
    parents match {
      case h :: t => new File(newCategoryDirectory(h, t), ngId.value + ".xml").succeed
      case Nil    => Unconsistancy("The given parent category list for node group with id '%s' is empty, what is forbiden".format(ngId.value)).fail
    }
  }

  override def archiveNodeGroup(ng:NodeGroup, parents: List[NodeGroupCategoryId], gitCommit:Option[(ModificationId, PersonIdent, Option[String])]) : IOResult[GitPath] = {
    for {
      ngFile    <- newNgFile(ng.id, parents)
      archive   <- writeXml(
                        ngFile
                      , nodeGroupSerialisation.serialise(ng)
                      , "Archived node group: " + ngFile.getPath
                    )
      commit     <- gitCommit match {
                      case Some((modId, commiter, reason)) => commitAddFile(modId, commiter, toGitPath(ngFile), "Archive of node group with ID '%s'%s".format(ng.id.value,GET(reason)))
                      case None => UIO.unit
                    }
    } yield {
      GitPath(toGitPath(archive))
    }
  }

  override def deleteNodeGroup(ngId:NodeGroupId, getParents: List[NodeGroupCategoryId], gitCommit:Option[(ModificationId, PersonIdent, Option[String])]) : IOResult[GitPath] = {
    for {
      ngFile  <- newNgFile(ngId, getParents)
      gitPath = toGitPath(ngFile)
      exists  <- IOResult.effect(ngFile.exists)
      res     <- if(exists) {
                   for {
                     //don't forget to delete the category *directory*
                     deleted  <- IOResult.effect(FileUtils.forceDelete(ngFile))
                     _        <- logPure.debug(s"Deleted archived node group: ${ngFile.getPath}")
                     commited <- gitCommit match {
                                   case Some((modId, commiter, reason)) =>
                                     commitRmFile(modId, commiter, gitPath, s"Delete archive of node group with ID '${ngId.value}'${GET(reason)}")
                                   case None => UIO.unit
                                 }
                   } yield {
                     GitPath(gitPath)
                   }
                 } else {
                   GitPath(gitPath).succeed
                 }
    } yield {
      res
    }
  }

  override def moveNodeGroup(ng:NodeGroup, oldParents: List[NodeGroupCategoryId], newParents: List[NodeGroupCategoryId], gitCommit:Option[(ModificationId, PersonIdent, Option[String])]) : IOResult[GitPath] = {
    if(oldParents == newParents) { //actually, it's an update not a move
      this.archiveNodeGroup(ng, oldParents, gitCommit)
    } else {
      for {
        oldNgXmlFile <- newNgFile(ng.id, oldParents)
        newNgXmlFile <- newNgFile(ng.id, newParents)
        archive      <- writeXml(
                            newNgXmlFile
                          , nodeGroupSerialisation.serialise(ng)
                          , "Archived node group: " + newNgXmlFile.getPath
                        )
        moved        <- if(null != oldNgXmlFile && oldNgXmlFile.exists) {
                           IOResult.effect(FileUtils.deleteQuietly(oldNgXmlFile))
                         } else UIO.unit
        commit       <- gitCommit match {
                          case Some((modId, commiter, reason)) => commitMvDirectory(modId, commiter, toGitPath(oldNgXmlFile), toGitPath(newNgXmlFile), "Move archive of node group with ID '%s'%s".format(ng.id.value,GET(reason)))
                          case None => UIO.unit
                        }
      } yield {
        GitPath(toGitPath(archive))
      }
    }
  }
}

/////////////////////////////////////////////////////////////
////// Parameters                                      //////
/////////////////////////////////////////////////////////////

/**
 * A specific trait to create archive of global parameters
 *
 */
class GitParameterArchiverImpl(
    override val gitRepo          : GitRepositoryProvider
  , override val gitRootDirectory : File
  , parameterSerialisation        : GlobalParameterSerialisation
  , parameterRootDir              : String //relative path !
  , override val xmlPrettyPrinter : RudderPrettyPrinter
  , override val gitModificationRepository : GitModificationRepository
  , override val encoding         : String = "UTF-8"
) extends
  GitParameterArchiver with
  NamedZioLogger with
  GitArchiverUtils with
  GitArchiverFullCommitUtils {

  override def loggerName: String = this.getClass.getName
  override val relativePath = parameterRootDir
  override val tagPrefix = "archives/parameters/"

  private[this] def newParameterFile(parameterName:ParameterName) = new File(getRootDirectory, parameterName.value + ".xml")

  def archiveParameter(
      parameter:GlobalParameter
    , doCommit:Option[(ModificationId, PersonIdent,Option[String])]
  ) : IOResult[GitPath] = {
    val paramFile = newParameterFile(parameter.name)
    val gitPath = toGitPath(paramFile)
    for {
      archive <- writeXml(
                     paramFile
                   , parameterSerialisation.serialise(parameter)
                   , "Archived parameter: " + paramFile.getPath
                 )
      commit  <- doCommit match {
                   case Some((modId, commiter, reason)) =>
                     val msg = "Archive parameter with name '%s'%s".format(parameter.name.value, GET(reason))
                     commitAddFile(modId, commiter, gitPath, msg)
                   case None => UIO.unit
                 }
    } yield {
      GitPath(gitPath)
    }
  }

  def commitParameters(modId: ModificationId, commiter:PersonIdent, reason:Option[String]) : IOResult[GitArchiveId] = {
    this.commitFullGitPathContentAndTag(
        commiter
      , PARAMETERS_ARCHIVE_TAG + " Commit all modification done on parameters (git path: '%s')%s".format(parameterRootDir, GET(reason))
    )
  }

  def deleteParameter(parameterName:ParameterName, doCommit:Option[(ModificationId, PersonIdent,Option[String])]) : IOResult[GitPath] = {
    val paramFile = newParameterFile(parameterName)
    val gitPath = toGitPath(paramFile)
    if(paramFile.exists) {
      for {
        deleted  <- IOResult.effect(FileUtils.forceDelete(paramFile))
        _        <- logPure.debug(s"Deleted archive of parameter: ${paramFile.getPath}")
        commited <- doCommit match {
                      case Some((modId, commiter, reason)) =>
                        commitRmFile(modId, commiter, gitPath, s"Delete archive of parameter with name '${parameterName.value}'${GET(reason)}")
                      case None => UIO.unit
                    }
      } yield {
        GitPath(gitPath)
      }
    } else {
      GitPath(gitPath).succeed
    }
  }
}

