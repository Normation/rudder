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

import com.normation.NamedZioLogger
import com.normation.cfclerk.domain.SectionSpec
import com.normation.cfclerk.domain.Technique
import com.normation.cfclerk.domain.TechniqueCategoryMetadata
import com.normation.cfclerk.domain.TechniqueId
import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.services.TechniqueRepository
import com.normation.cfclerk.xmlparsers.TechniqueParser
import com.normation.errors.*
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.inventory.domain.AgentType
import com.normation.rudder.domain.Constants.CONFIGURATION_RULES_ARCHIVE_TAG
import com.normation.rudder.domain.Constants.GROUPS_ARCHIVE_TAG
import com.normation.rudder.domain.Constants.PARAMETERS_ARCHIVE_TAG
import com.normation.rudder.domain.Constants.POLICY_LIBRARY_ARCHIVE_TAG
import com.normation.rudder.domain.logger.GitArchiveLoggerPure
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.nodes.NodeGroupCategory
import com.normation.rudder.domain.nodes.NodeGroupCategoryId
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.policies.*
import com.normation.rudder.domain.properties.GlobalParameter
import com.normation.rudder.git.GitArchiveId
import com.normation.rudder.git.GitArchiverFullCommitUtils
import com.normation.rudder.git.GitConfigItemRepository
import com.normation.rudder.git.GitPath
import com.normation.rudder.git.GitRepositoryProvider
import com.normation.rudder.ncf.ResourceFile
import com.normation.rudder.ncf.ResourceFileState
import com.normation.rudder.ncf.TechniqueCompiler
import com.normation.rudder.repository.*
import com.normation.rudder.services.marshalling.*
import com.normation.rudder.services.user.PersonIdentService
import java.io.File
import java.io.FileNotFoundException
import net.liftweb.common.*
import org.apache.commons.io.FileUtils
import org.eclipse.jgit.lib.PersonIdent
import scala.collection.mutable.Buffer
import scala.xml.Source
import scala.xml.XML
import zio.*
import zio.syntax.*

class GitRuleArchiverImpl(
    override val gitRepo: GitRepositoryProvider,
    ruleSerialisation:    RuleSerialisation,
    ruleRootDir:          String, // relative path !

    override val xmlPrettyPrinter:          RudderPrettyPrinter,
    override val gitModificationRepository: GitModificationRepository,
    override val encoding:                  String,
    override val groupOwner:                String
) extends GitRuleArchiver with XmlArchiverUtils with NamedZioLogger with GitConfigItemRepository with GitArchiverFullCommitUtils
    with BuildFilePaths[RuleId] {

  override def loggerName: String = this.getClass.getName
  override val relativePath = ruleRootDir
  override val tagPrefix    = "archives/configurations-rules/"

  override def getFileName(ruleId: RuleId): String = ruleId.serialize + ".xml"

  def archiveRule(rule: Rule, doCommit: Option[(ModificationId, PersonIdent, Option[String])]): IOResult[GitPath] = {
    for {
      crFile <- newFile(rule.id).toIO
      gitPath = toGitPath(crFile)

      archive <- writeXml(
                   crFile,
                   ruleSerialisation.serialise(rule),
                   "Archived rule: " + crFile.getPath
                 )
      commit  <- doCommit match {
                   case Some((modId, commiter, reason)) =>
                     commitAddFileWithModId(modId, commiter, gitPath, s"Archive rule with ID '${rule.id.serialize}'${GET(reason)}")
                   case None                            => ZIO.unit
                 }
    } yield {
      GitPath(gitPath)
    }
  }

  def commitRules(modId: ModificationId, commiter: PersonIdent, reason: Option[String]): IOResult[GitArchiveId] = {
    this.commitFullGitPathContentAndTag(
      commiter,
      CONFIGURATION_RULES_ARCHIVE_TAG + " Commit all modification done on rules (git path: '%s')%s".format(
        ruleRootDir,
        GET(reason)
      )
    )
  }

  def deleteRule(ruleId: RuleId, doCommit: Option[(ModificationId, PersonIdent, Option[String])]): IOResult[GitPath] = {
    for {
      crFile <- newFile(ruleId).toIO
      gitPath = toGitPath(crFile)
      _      <- ZIO.whenZIO(IOResult.attempt(crFile.exists)) {
                  for {
                    deleted <- IOResult.attempt(FileUtils.forceDelete(crFile))
                    _       <- logPure.debug("Deleted archive of rule: " + crFile.getPath)
                    _       <- doCommit match {
                                 case Some((modId, commiter, reason)) =>
                                   commitRmFileWithModId(
                                     modId,
                                     commiter,
                                     gitPath,
                                     s"Delete archive of rule with ID '${ruleId.serialize}'${GET(reason)}"
                                   )
                                 case None                            => ZIO.unit
                               }
                  } yield ()
                }
    } yield {
      GitPath(gitPath)
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
  // obtain the root directory from the main class mixed with me
  def getItemDirectory: File

  def getCategoryName(categoryId: T): String

  // list of directories : don't forget the one for the serialized category.
  // revert the order to start by the root of technique library.
  // Fail if the directory attempting to write to is not under the root directory getItemDirectory
  def newCategoryDirectory(catId: T, parents: List[T]): IOResult[File] = {
    def recNewCategoryDirectory(catId: T, parents: List[T]): File = {
      parents match {
        case Nil       => // that's the root
          getItemDirectory
        case h :: tail => // skip the head, which is the root category
          new File(recNewCategoryDirectory(h, tail), getCategoryName(catId))
      }
    }
    val target = recNewCategoryDirectory(catId, parents)

    if (target.getCanonicalPath().startsWith(getItemDirectory.getCanonicalPath())) {
      target.succeed
    } else {
      Inconsistency(
        s"Error when checking required directories '${target.getPath}' to archive in gitRepo.git: relative path must not allow access to parent directories, " +
        s"only to directories under directory ${getItemDirectory}"
      ).fail
    }
  }
}

/**
 * An utility trait that enforces file system access to local items in git repository.
 * It should be the prefered way to build paths within a local item repository (e.g. rules), otherwise there would be security concerns.
 */
trait BuildFilePaths[T] {

  def getItemDirectory: File

  def getFileName(itemId: T): String

  /**
   * Returns the file path in the git repository for the item.
   */
  def newFile(itemId: T): PureResult[File] = {
    val target = new File(getItemDirectory, getFileName(itemId))
    if (target.getCanonicalPath().startsWith(getItemDirectory.getCanonicalPath())) {
      Right(target)
    } else {
      Left(
        Inconsistency(
          s"Error when checking required directories '${target.getPath}' to archive in gitRepo.git: relative path must not allow access to parent directories, " +
          s"only to directories under directory ${getItemDirectory}"
        )
      )
    }
  }

}

///////////////////////////////////////////////////////////////
//////  Archive techniques (techniques, resources files  //////
///////////////////////////////////////////////////////////////

/*
 * There is a low level API that works at "metadata.xml and files" level and only checks things like metadata.xml ok, overwriting ok,
 * unicity, resources presents, agent file presents, etc
 *
 * This service is also in charge of low-level git related actions: delete files, commit, etc.
 */
trait TechniqueArchiver {
  def deleteTechnique(
      techniqueId: TechniqueId,
      categories:  Seq[String],
      modId:       ModificationId,
      committer:   EventActor,
      msg:         String
  ): IOResult[Unit]

  def saveTechnique(
      techniqueId:     TechniqueId,
      categories:      Seq[String],
      resourcesStatus: Chunk[ResourceFile],
      modId:           ModificationId,
      committer:       EventActor,
      msg:             String
  ): IOResult[Unit]

  def saveTechniqueCategory(
      categories: Seq[String], // path (inclusive) to the category
      metadata:   TechniqueCategoryMetadata,
      modId:      ModificationId,
      committer:  EventActor,
      msg:        String
  ): IOResult[Unit]
}

/*
 * List of files to add/delete in the commit.
 * All path are given relative to git root, ie they can be used in
 * a git command as they are.
 */
final case class TechniqueFilesToCommit(
    add:    Chunk[String],
    delete: Chunk[String],
    stage:  Chunk[String] // files to just stage. Do not add or rm them explicitly, just do so if they are/are not on the fs
)

object TechniqueFiles {

  val json = "technique.json" // pre-rudder 8.0 technique file descriptor. Need migration.
  val yaml = "technique.yml"  // high-level API between technique editor and rudderc.

  // generated

  object Generated {

    val metadata = "metadata.xml" // standard technique API, used for policy generation pipeline

    val all_common: Chunk[String] = Chunk(
      metadata
    )

    // specific by agent

    // `rudder_reporting.cf` is only used when the generation is done by webapp, it does not exist with rudderc
    val cfengineReporting = "rudder_reporting.cf"
    val cfengineRudderc: Chunk[String] = Chunk("technique.cf")
    val cfengineAll:     Chunk[String] = Chunk(cfengineReporting, "technique.cf")

    val dsc: Chunk[String] = Chunk("technique.ps1")

    val all: Chunk[String] = all_common ++ cfengineAll ++ dsc
  }

  // all is for current version only, ie json is not part of it.
  val all:    Chunk[String] = yaml +: Generated.all
  val common: Chunk[String] = yaml +: Generated.all_common
}

/*
 * This implementation will try:
 * - to compile a YAML technique if `technique.yml` exists but not `metadata.xml`
 * - to migrate from `technique.json` to `technique.yml` if needed
 */
class TechniqueArchiverImpl(
    override val gitRepo:                   GitRepositoryProvider,
    override val xmlPrettyPrinter:          RudderPrettyPrinter,
    override val gitModificationRepository: GitModificationRepository,
    personIdentservice:                     PersonIdentService,
    techniqueParser:                        TechniqueParser,
    techniqueCompiler:                      TechniqueCompiler,
    override val groupOwner:                String
) extends GitConfigItemRepository with XmlArchiverUtils with TechniqueArchiver {

  override val encoding: String = "UTF-8"

  // we can't use "techniques" for relative path because of ncf and dsc files. This is an architecture smell, we need to clean it.
  override val relativePath = "techniques"

  def deleteTechnique(
      techniqueId: TechniqueId,
      categories:  Seq[String],
      modId:       ModificationId,
      committer:   EventActor,
      msg:         String
  ): IOResult[Unit] = {
    (for {
      ident        <- personIdentservice.getPersonIdentOrDefault(committer.name)
      // construct the path to the technique. Root category is "/", so we filter out all / to be sure
      categoryPath <- categories.filter(_ != "/").mkString("/").succeed
      rm           <- IOResult.attempt(gitRepo.git.rm.addFilepattern(s"${relativePath}/${categoryPath}/${techniqueId.serialize}").call())

      commit <- IOResult.attempt(gitRepo.git.commit.setCommitter(ident).setMessage(msg).call())
    } yield {
      s"${relativePath}/${categoryPath}/${techniqueId.serialize}"
    }).chainError(s"error when deleting and committing Technique '${techniqueId.serialize}").unit
  }

  /*
   * Return the list of files to commit for the technique.
   * For resources, we need to have an hint about the state because we can't guess for deleted files.
   *
   * TODO: resource files path and status should be deducted from the metadata.xml and the Resources ID.
   *  It would insure that there is consistency between technique descriptor and actual content, and
   * insure that the lower generation part has a clear and defined API, and that we can do whatever we want
   * in the middle.
   * All path are related to git repository root path (ie, the correct path to use in git command).
   * gitTechniquePath is the path for the technique relative to that git root, without ending slash
   */
  def getFilesToCommit(
      technique:        Technique,
      gitTechniquePath: String,
      resourcesStatus:  Chunk[ResourceFile]
  ): TechniqueFilesToCommit = {
    // parse metadata.xml and find what files need to be added
    val filesToAdd = (TechniqueFiles.common ++
      (if (
         technique.agentConfigs
           .collectFirst(a => a.agentType == AgentType.CfeCommunity || a.agentType == AgentType.CfeEnterprise)
           .nonEmpty
       ) {
         TechniqueFiles.Generated.cfengineAll
       } else Chunk()) ++
      (if (technique.agentConfigs.collectFirst(_.agentType == AgentType.Dsc).nonEmpty) {
         TechniqueFiles.Generated.dsc
       } else Chunk()) ++
      resourcesStatus.collect {
        case ResourceFile(path, action) if action == ResourceFileState.New | action == ResourceFileState.Modified => path
      }).map(p => gitTechniquePath + "/" + p) // from path relative to technique to relative to git root

    // apart resources, additional files to delete are for migration purpose.
    val filesToDelete = (
      s"ncf/50_techniques/${technique.id.name.value}" +:       // added in 5.1 for migration to 6.0
        s"dsc/ncf/50_techniques/${technique.id.name.value}" +: // added in 6.1
        (gitTechniquePath + "/technique.rd") +:                // deprecated in 7.2. Old rudder-lang input for rudderc, will be replace by yml file
        resourcesStatus.collect { case ResourceFile(path, ResourceFileState.Deleted) => gitTechniquePath + "/" + path }
    )

    val filesToStage = Chunk(
      (gitTechniquePath + "/technique.json"),     // deprecated 8.0. Old json format replaced by using technique.yml
      (gitTechniquePath + "/rudder_reporting.cf") // deprecated in 8.0. It was merged within technique.cf
    )

    TechniqueFilesToCommit(filesToAdd, filesToDelete, filesToStage)
  }

  override def saveTechnique(
      techniqueId:     TechniqueId,
      categories:      Seq[String],
      resourcesStatus: Chunk[ResourceFile],
      modId:           ModificationId,
      committer:       EventActor,
      msg:             String
  ): IOResult[Unit] = {

    val categoryPath     = categories.filter(_ != "/").mkString("/")
    val techniqueGitPath = s"${relativePath}/${categoryPath}/${techniqueId.serialize}"
    val techniquePath    = gitRepo.rootDirectory / techniqueGitPath

    (for {
      res       <- techniqueCompiler.migrateCompileIfNeeded(techniquePath)
      _         <- ZIO.when(res.isError) {
                     Unexpected(
                       s"Error when trying to compile technique '${techniquePath.pathAsString}'. Error details are " +
                       s"available in `compilation-output.yml` file in the same directory. Error message: '${res.msg}'"
                     ).fail
                   }
      // update file status file what the compiler did
      known      = resourcesStatus.map(f => (f.path, f)).toMap
      updated    = res.fileStatus.map(f => (f.path, f)).toMap
      fileStates = Chunk.fromIterable((known ++ updated).values)
      metadata  <- IOResult.attempt(XML.load(Source.fromFile((techniquePath / TechniqueFiles.Generated.metadata).toJava)))
      tech      <- techniqueParser.parseXml(metadata, techniqueId).toIO
      files      = getFilesToCommit(tech, techniqueGitPath, fileStates)
      ident     <- personIdentservice.getPersonIdentOrDefault(committer.name)
      added     <- ZIO.foreach(files.add)(f => IOResult.attempt(gitRepo.git.add.addFilepattern(f).call()))
      removed   <- ZIO.foreach(files.delete)(f => IOResult.attempt(gitRepo.git.rm.addFilepattern(f).call()))
      staged    <- ZIO.foreach(files.stage)(f => IOResult.attempt(gitRepo.git.add.setUpdate(true).addFilepattern(f).call()))
      commit    <- IOResult.attempt(gitRepo.git.commit.setCommitter(ident).setMessage(msg).call())
    } yield ()).chainError(s"error when committing Technique '${techniqueId.serialize}'").unit
  }

  def saveTechniqueCategory(
      categories: Seq[String], // path (inclusive) to the category
      metadata:   TechniqueCategoryMetadata,
      modId:      ModificationId,
      committer:  EventActor,
      msg:        String
  ): IOResult[Unit] = {
    val categoryPath = categories.filter(_ != "/").mkString("/")
    val catGitPath   = s"${relativePath}/${categoryPath}/${TechniqueCategoryMetadata.FILE_NAME_XML}"
    val categoryFile = gitRepo.rootDirectory / catGitPath
    val xml          = metadata.toXml

    categories.lastOption match {
      case None        => Unexpected("You can't change the root category information").fail
      case Some(catId) =>
        (for {
          // the file may not exist, which is not an error in that case
          existing <- IOResult.attempt {
                        val elem = XML.load(Source.fromFile(categoryFile.toJava))
                        Some(TechniqueCategoryMetadata.parseXML(elem, catId))
                      }.catchSome { case SystemError(_, _: FileNotFoundException) => None.succeed }
          _        <- if (existing.contains(metadata)) {
                        GitArchiveLoggerPure.debug(s"Not commiting '${catGitPath}' because it already exists with these values")
                      } else {
                        for {
                          ident <- personIdentservice.getPersonIdentOrDefault(committer.name)
                          parent = categoryFile.parent
                          _     <- writeXml(categoryFile.toJava, xml, s"Archived technique category: ${catGitPath}")
                          _     <- IOResult.attempt(gitRepo.git.add.addFilepattern(catGitPath).call())
                          _     <- IOResult.attempt(gitRepo.git.commit.setCommitter(ident).setMessage(msg).call())
                        } yield ()
                      }
        } yield ()).chainError(s"error when committing technique category '${catGitPath}'").unit
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
    override val gitRepo:                 GitRepositoryProvider,
    activeTechniqueCategorySerialisation: ActiveTechniqueCategorySerialisation,
    techniqueLibraryRootDir:              String, // relative path !

    override val xmlPrettyPrinter:          RudderPrettyPrinter,
    override val gitModificationRepository: GitModificationRepository,
    override val encoding:                  String,
    serializedCategoryName:                 String,
    override val groupOwner:                String
) extends GitActiveTechniqueCategoryArchiver with Loggable with GitConfigItemRepository with XmlArchiverUtils
    with BuildCategoryPathName[ActiveTechniqueCategoryId] with GitArchiverFullCommitUtils {

  override def loggerName: String = this.getClass.getName
  override lazy val relativePath = techniqueLibraryRootDir
  override def getCategoryName(categoryId: ActiveTechniqueCategoryId) = categoryId.value

  override lazy val tagPrefix = "archives/directives/"

  private def newActiveTechniquecFile(uptcId: ActiveTechniqueCategoryId, parents: List[ActiveTechniqueCategoryId]) = {
    newCategoryDirectory(uptcId, parents).map(new File(_, serializedCategoryName))
  }

  private def archiveWithRename(
      uptc:       ActiveTechniqueCategory,
      oldParents: Option[List[ActiveTechniqueCategoryId]],
      newParents: List[ActiveTechniqueCategoryId],
      gitCommit:  Option[(ModificationId, PersonIdent, Option[String])]
  ): IOResult[GitPath] = {

    for {
      uptcFile   <- newActiveTechniquecFile(uptc.id, newParents)
      gitPath     = toGitPath(uptcFile)
      archive    <- writeXml(
                      uptcFile,
                      activeTechniqueCategorySerialisation.serialise(uptc),
                      "Archived technique library category: " + uptcFile.getPath
                    )
      uptcGitPath = gitPath
      commit     <- gitCommit match {
                      case Some((modId, commiter, reason)) =>
                        oldParents match {
                          case Some(olds) =>
                            newActiveTechniquecFile(uptc.id, olds).flatMap(oldUptcFile => {
                              commitMvDirectoryWithModId(
                                modId,
                                commiter,
                                toGitPath(oldUptcFile),
                                uptcGitPath,
                                "Move archive of technique library category with ID '%s'%s".format(uptc.id.value, GET(reason))
                              )
                            })
                          case None       =>
                            commitAddFileWithModId(
                              modId,
                              commiter,
                              uptcGitPath,
                              "Archive of technique library category with ID '%s'%s".format(uptc.id.value, GET(reason))
                            )
                        }
                      case None                            => ZIO.unit
                    }
    } yield {
      GitPath(gitPath)
    }
  }

  override def archiveActiveTechniqueCategory(
      uptc:       ActiveTechniqueCategory,
      getParents: List[ActiveTechniqueCategoryId],
      gitCommit:  Option[(ModificationId, PersonIdent, Option[String])]
  ): IOResult[GitPath] = {
    archiveWithRename(uptc, None, getParents, gitCommit)
  }

  override def deleteActiveTechniqueCategory(
      uptcId:     ActiveTechniqueCategoryId,
      getParents: List[ActiveTechniqueCategoryId],
      gitCommit:  Option[(ModificationId, PersonIdent, Option[String])]
  ): IOResult[GitPath] = {
    for {
      uptcFile <- newActiveTechniquecFile(uptcId, getParents)
      gitPath   = toGitPath(uptcFile)
      // don't forget to delete the category *directory*
      _        <- ZIO.whenZIO(IOResult.attempt(uptcFile.exists)) {
                    for {
                      _ <- IOResult.attempt(FileUtils.forceDelete(uptcFile))
                      _ <- logPure.debug("Deleted archived technique library category: " + uptcFile.getPath)
                      _ <- gitCommit match {
                             case Some((modId, commiter, reason)) =>
                               commitRmFileWithModId(
                                 modId,
                                 commiter,
                                 gitPath,
                                 s"Delete archive of technique library category with ID '${uptcId.value}'${GET(reason)}"
                               )
                             case None                            => ZIO.unit
                           }
                    } yield ()
                  }
    } yield {
      GitPath(gitPath)
    }
  }

  // TODO : keep content when moving !!!
  // well, for now, that's ok, because we can only move empty categories
  override def moveActiveTechniqueCategory(
      uptc:       ActiveTechniqueCategory,
      oldParents: List[ActiveTechniqueCategoryId],
      newParents: List[ActiveTechniqueCategoryId],
      gitCommit:  Option[(ModificationId, PersonIdent, Option[String])]
  ): IOResult[GitPath] = {
    if (oldParents == newParents) { // actually, an update
      this.archiveActiveTechniqueCategory(uptc, oldParents, gitCommit)
    } else {
      for {
        _        <- deleteActiveTechniqueCategory(uptc.id, oldParents, None)
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
  override def commitActiveTechniqueLibrary(
      modId:    ModificationId,
      commiter: PersonIdent,
      reason:   Option[String]
  ): IOResult[GitArchiveId] = {
    this.commitFullGitPathContentAndTag(
      commiter,
      POLICY_LIBRARY_ARCHIVE_TAG + " Commit all modification done in the active technique library (git path: '%s'%s)".format(
        techniqueLibraryRootDir,
        GET(reason)
      )
    )
  }
}

trait ActiveTechniqueModificationCallback {

  // Name of the callback, for debugging
  def uptModificationCallbackName: String

  /**
   * What to do on activeTechnique save
   */
  def onArchive(
      activeTechnique: ActiveTechnique,
      parents:         List[ActiveTechniqueCategoryId],
      gitCommit:       Option[(ModificationId, PersonIdent, Option[String])]
  ): IOResult[Seq[DirectiveNotArchived]]

  /**
   * What to do on activeTechnique deletion
   */
  def onDelete(
      ptName:     TechniqueName,
      getParents: List[ActiveTechniqueCategoryId],
      gitCommit:  Option[(ModificationId, PersonIdent, Option[String])]
  ): IOResult[Unit]

  /**
   * What to do on activeTechnique move
   */
  def onMove(
      activeTechnique: ActiveTechnique,
      oldParents:      List[ActiveTechniqueCategoryId],
      newParents:      List[ActiveTechniqueCategoryId],
      gitCommit:       Option[(ModificationId, PersonIdent, Option[String])]
  ): IOResult[Unit]
}

class UpdatePiOnActiveTechniqueEvent(
    gitDirectiveArchiver: GitDirectiveArchiver,
    techniqeRepository:   TechniqueRepository,
    directiveRepository:  RoDirectiveRepository
) extends ActiveTechniqueModificationCallback with NamedZioLogger {
  override val uptModificationCallbackName = "Update PI on UPT events"

  override def loggerName: String = this.getClass.getName
  // TODO: why gitCommit is not used here ?
  override def onArchive(
      activeTechnique: ActiveTechnique,
      parents:         List[ActiveTechniqueCategoryId],
      gitCommit:       Option[(ModificationId, PersonIdent, Option[String])]
  ): IOResult[Seq[DirectiveNotArchived]] = {

    logPure.debug("Executing archivage of PIs for UPT '%s'".format(activeTechnique))

    ZIO
      .foreach(activeTechnique.directives) { directiveId =>
        for {
          directive            <-
            directiveRepository
              .getDirective(directiveId)
              .notOptional(
                s"Can not find directive with id '${directiveId.value}' in repository but it is viewed as a child of '${activeTechnique.id.value}'. This is likely a bug, please report it."
              )
          optDirectiveArchived <- (if (directive.isSystem) {
                                     None.succeed
                                   } else {
                                     for {
                                       technique  <-
                                         techniqeRepository
                                           .get(TechniqueId(activeTechnique.techniqueName, directive.techniqueVersion))
                                           .notOptional(
                                             s"Can not find Technique '${activeTechnique.techniqueName.value}:${directive.techniqueVersion.debugString}'"
                                           )
                                       archivedPi <- gitDirectiveArchiver.archiveDirective(
                                                       directive,
                                                       technique.id.name,
                                                       parents,
                                                       technique.rootSection,
                                                       gitCommit
                                                     )
                                     } yield {
                                       None
                                     }
                                   }) catchAll { err => Some(DirectiveNotArchived(directiveId, err)).succeed }

        } yield {
          optDirectiveArchived
        }
      }
      .map(_.flatten)
  }

  override def onDelete(
      ptName:     TechniqueName,
      getParents: List[ActiveTechniqueCategoryId],
      gitCommit:  Option[(ModificationId, PersonIdent, Option[String])]
  ): UIO[Unit] = ZIO.unit
  override def onMove(
      activeTechnique: ActiveTechnique,
      oldParents:      List[ActiveTechniqueCategoryId],
      newParents:      List[ActiveTechniqueCategoryId],
      gitCommit:       Option[(ModificationId, PersonIdent, Option[String])]
  ): UIO[Unit] = ZIO.unit
}

/**
 * A specific trait to create archive of an active technique.
 */
class GitActiveTechniqueArchiverImpl(
    override val gitRepo:         GitRepositoryProvider,
    activeTechniqueSerialisation: ActiveTechniqueSerialisation,
    techniqueLibraryRootDir:      String, // relative path !

    override val xmlPrettyPrinter:          RudderPrettyPrinter,
    override val gitModificationRepository: GitModificationRepository,
    val uptModificationCallback:            Buffer[ActiveTechniqueModificationCallback],
    override val encoding:                  String,
    val activeTechniqueFileName:            String,
    override val groupOwner:                String
) extends GitActiveTechniqueArchiver with NamedZioLogger with GitConfigItemRepository with XmlArchiverUtils
    with BuildCategoryPathName[ActiveTechniqueCategoryId] {

  override def loggerName: String = this.getClass.getName
  override lazy val relativePath = techniqueLibraryRootDir
  override def getCategoryName(categoryId: ActiveTechniqueCategoryId) = categoryId.value

  private def newActiveTechniqueFile(ptName: TechniqueName, parents: List[ActiveTechniqueCategoryId]) = {
    // parents can not be null: we must have at least the root category
    parents match {
      case Nil       =>
        Inconsistency(
          s"Active Techniques '${ptName.value}' was asked to be saved in a category which does not exist (empty list of parents, not even the root cateogy was given!)"
        ).fail
      case h :: tail =>
        newCategoryDirectory(h, tail).map(catDir => new File(new File(catDir, ptName.value), activeTechniqueFileName))

    }
  }

  override def archiveActiveTechnique(
      activeTechnique: ActiveTechnique,
      parents:         List[ActiveTechniqueCategoryId],
      gitCommit:       Option[(ModificationId, PersonIdent, Option[String])]
  ): IOResult[(GitPath, Seq[DirectiveNotArchived])] = {
    for {
      uptFile   <- newActiveTechniqueFile(activeTechnique.techniqueName, parents)
      gitPath    = toGitPath(uptFile)
      _         <- writeXml(
                     uptFile,
                     activeTechniqueSerialisation.serialise(activeTechnique),
                     "Archived technique library template: " + uptFile.getPath
                   )
      // strategy for callbaack:
      // if at least one callback is in error, we don't execute the others and the full ActiveTechnique is in error.
      // if none is in error, we are going to next step
      callbacks <- ZIO.foreach(uptModificationCallback.toList)(_.onArchive(activeTechnique, parents, gitCommit))
      _         <- gitCommit match {
                     case Some((modId, commiter, reason)) =>
                       commitAddFileWithModId(
                         modId,
                         commiter,
                         gitPath,
                         s"Archive of technique library template for technique name '${activeTechnique.techniqueName.value}'${GET(reason)}"
                       )
                     case None                            => ZIO.unit
                   }
    } yield {
      (GitPath(gitPath), callbacks.toSeq.flatten)
    }
  }

  override def deleteActiveTechnique(
      ptName:    TechniqueName,
      parents:   List[ActiveTechniqueCategoryId],
      gitCommit: Option[(ModificationId, PersonIdent, Option[String])]
  ): IOResult[GitPath] = {
    for {
      atFile <- newActiveTechniqueFile(ptName, parents)
      exists <- IOResult.attempt(atFile.exists)
      res    <- if (exists) {
                  for {
                    // don't forget to delete the category *directory*
                    _      <- IOResult.attempt(FileUtils.forceDelete(atFile))
                    _       = logPure.debug(s"Deleted archived technique library template: ${atFile.getPath}")
                    gitPath = toGitPath(atFile)
                    _      <- ZIO.foreach(uptModificationCallback.toList)(_.onDelete(ptName, parents, None))
                    _      <- gitCommit match {
                                case Some((modId, commiter, reason)) =>
                                  commitRmFileWithModId(
                                    modId,
                                    commiter,
                                    gitPath,
                                    s"Delete archive of technique library template for technique name '${ptName.value}'${GET(reason)}"
                                  )
                                case None                            => ZIO.unit
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
  override def moveActiveTechnique(
      activeTechnique: ActiveTechnique,
      oldParents:      List[ActiveTechniqueCategoryId],
      newParents:      List[ActiveTechniqueCategoryId],
      gitCommit:       Option[(ModificationId, PersonIdent, Option[String])]
  ): IOResult[(GitPath, Seq[DirectiveNotArchived])] = {
    if (oldParents == newParents) { // actually an update
      this.archiveActiveTechnique(activeTechnique, oldParents, gitCommit)
    } else {
      for {
        oldActiveTechniqueFile      <- newActiveTechniqueFile(activeTechnique.techniqueName, oldParents)
        oldActiveTechniqueDirectory <- IOResult.attempt(oldActiveTechniqueFile.getParentFile)
        newActiveTechniqueFile      <- newActiveTechniqueFile(activeTechnique.techniqueName, newParents)
        newActiveTechniqueDirectory <- IOResult.attempt(newActiveTechniqueFile.getParentFile)
        existsNew                   <- IOResult.attempt(newActiveTechniqueDirectory.exists)
        clearNew                    <- ZIO.when(existsNew)(IOResult.attempt(FileUtils.forceDelete(newActiveTechniqueDirectory)))
        existsOld                   <- IOResult.attempt(oldActiveTechniqueDirectory.exists)
        deleteOld                   <- ZIO.when(existsOld)(IOResult.attempt(FileUtils.forceDelete(oldActiveTechniqueDirectory)))
        archived                    <- archiveActiveTechnique(activeTechnique, newParents, gitCommit)
        commited                    <- gitCommit match {
                                         case Some((modId, commiter, reason)) =>
                                           commitMvDirectoryWithModId(
                                             modId,
                                             commiter,
                                             toGitPath(oldActiveTechniqueDirectory),
                                             toGitPath(newActiveTechniqueDirectory),
                                             "Move active technique for technique name '%s'%s".format(
                                               activeTechnique.techniqueName.value,
                                               GET(reason)
                                             )
                                           )
                                         case None                            => ZIO.unit
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
    override val gitRepo:    GitRepositoryProvider,
    directiveSerialisation:  DirectiveSerialisation,
    techniqueLibraryRootDir: String, // relative path !

    override val xmlPrettyPrinter:          RudderPrettyPrinter,
    override val gitModificationRepository: GitModificationRepository,
    override val encoding:                  String,
    override val groupOwner:                String
) extends GitDirectiveArchiver with NamedZioLogger with GitConfigItemRepository with XmlArchiverUtils
    with BuildCategoryPathName[ActiveTechniqueCategoryId] {

  override def loggerName: String = this.getClass.getName
  override lazy val relativePath = techniqueLibraryRootDir
  override def getCategoryName(categoryId: ActiveTechniqueCategoryId) = categoryId.value

  private def newPiFile(
      directiveId: DirectiveUid,
      ptName:      TechniqueName,
      parents:     List[ActiveTechniqueCategoryId]
  ) = {
    parents match {
      case Nil       =>
        Inconsistency(
          "Can not save directive '%s' for technique '%s' because no category (not even the root one) was given as parent for that technique"
            .format(directiveId.value, ptName.value)
        ).fail
      case h :: tail =>
        newCategoryDirectory(h, tail).map(catDir => new File(new File(catDir, ptName.value), directiveId.value + ".xml"))
    }
  }

  override def archiveDirective(
      directive:           Directive,
      ptName:              TechniqueName,
      catIds:              List[ActiveTechniqueCategoryId],
      variableRootSection: SectionSpec,
      gitCommit:           Option[(ModificationId, PersonIdent, Option[String])]
  ): IOResult[GitPath] = {

    for {
      piFile  <- newPiFile(directive.id.uid, ptName, catIds)
      gitPath  = toGitPath(piFile)
      archive <- writeXml(
                   piFile,
                   directiveSerialisation.serialise(ptName, Some(variableRootSection), directive),
                   "Archived directive: " + piFile.getPath
                 )
      commit  <- gitCommit match {
                   case Some((modId, commiter, reason)) =>
                     commitAddFileWithModId(
                       modId,
                       commiter,
                       gitPath,
                       "Archive directive with ID '%s'%s".format(directive.id.uid.value, GET(reason))
                     )
                   case None                            => ZIO.unit
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
      directiveId: DirectiveUid,
      ptName:      TechniqueName,
      catIds:      List[ActiveTechniqueCategoryId],
      gitCommit:   Option[(ModificationId, PersonIdent, Option[String])]
  ): IOResult[GitPath] = {
    for {
      piFile <- newPiFile(directiveId, ptName, catIds)
      exists <- IOResult.attempt(piFile.exists)
      res    <- if (exists) {
                  for {
                    _      <- IOResult.attempt(FileUtils.forceDelete(piFile))
                    _      <- logPure.debug(s"Deleted archive of directive: '${piFile.getPath}'")
                    gitPath = toGitPath(piFile)
                    _      <- gitCommit match {
                                case Some((modId, commiter, reason)) =>
                                  commitRmFileWithModId(
                                    modId,
                                    commiter,
                                    gitPath,
                                    s"Delete archive of directive with ID '${directiveId.value}'${GET(reason)}"
                                  )
                                case None                            => ZIO.unit
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
    override val gitRepo:           GitRepositoryProvider,
    nodeGroupSerialisation:         NodeGroupSerialisation,
    nodeGroupCategorySerialisation: NodeGroupCategorySerialisation,
    groupLibraryRootDir:            String, // relative path !

    override val xmlPrettyPrinter:          RudderPrettyPrinter,
    override val gitModificationRepository: GitModificationRepository,
    override val encoding:                  String,
    serializedCategoryName:                 String,
    override val groupOwner:                String
) extends GitNodeGroupArchiver with NamedZioLogger with GitConfigItemRepository with XmlArchiverUtils
    with BuildCategoryPathName[NodeGroupCategoryId] with GitArchiverFullCommitUtils {

  override def loggerName: String = this.getClass.getName
  override lazy val relativePath = groupLibraryRootDir
  override def getCategoryName(categoryId: NodeGroupCategoryId) = categoryId.value

  override lazy val tagPrefix = "archives/groups/"

  private def newNgFile(ngcId: NodeGroupCategoryId, parents: List[NodeGroupCategoryId]) = {
    newCategoryDirectory(ngcId, parents).map(new File(_, serializedCategoryName))
  }

  override def archiveNodeGroupCategory(
      ngc:       NodeGroupCategory,
      parents:   List[NodeGroupCategoryId],
      gitCommit: Option[(ModificationId, PersonIdent, Option[String])]
  ): IOResult[GitPath] = {

    for {
      ngcFile <- newNgFile(ngc.id, parents)
      archive <- writeXml(
                   ngcFile,
                   nodeGroupCategorySerialisation.serialise(ngc),
                   "Archived node group category: " + ngcFile.getPath
                 )
      gitPath  = toGitPath(ngcFile)
      commit  <- gitCommit match {
                   case Some((modId, commiter, reason)) =>
                     commitAddFileWithModId(
                       modId,
                       commiter,
                       gitPath,
                       "Archive of node group category with ID '%s'%s".format(ngc.id.value, GET(reason))
                     )
                   case None                            => ZIO.unit
                 }
    } yield {
      GitPath(gitPath)
    }
  }

  override def deleteNodeGroupCategory(
      ngcId:      NodeGroupCategoryId,
      getParents: List[NodeGroupCategoryId],
      gitCommit:  Option[(ModificationId, PersonIdent, Option[String])]
  ): IOResult[GitPath] = {
    for {
      ngcFile <- newNgFile(ngcId, getParents)
      gitPath  = toGitPath(ngcFile)
      // don't forget to delete the category *directory*
      _       <- ZIO.whenZIO(IOResult.attempt(ngcFile.exists)) {
                   for {
                     _ <- IOResult.attempt(FileUtils.forceDelete(ngcFile))
                     _ <- logPure.debug(s"Deleted archived node group category: ${ngcFile.getPath}")
                     _ <- gitCommit match {
                            case Some((modId, commiter, reason)) =>
                              commitRmFileWithModId(
                                modId,
                                commiter,
                                gitPath,
                                s"Delete archive of node group category with ID '${ngcId.value}'${GET(reason)}"
                              )
                            case None                            => ZIO.unit
                          }
                   } yield ()
                 }
    } yield {
      GitPath(gitPath)
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
  override def moveNodeGroupCategory(
      ngc:        NodeGroupCategory,
      oldParents: List[NodeGroupCategoryId],
      newParents: List[NodeGroupCategoryId],
      gitCommit:  Option[(ModificationId, PersonIdent, Option[String])]
  ): IOResult[GitPath] = {
    if (oldParents == newParents) { // actually, it's an archive, not a move
      this.archiveNodeGroupCategory(ngc, oldParents, gitCommit)
    } else {
      for {
        oldNgcDir     <- newNgFile(ngc.id, oldParents).map(_.getParentFile)
        newNgcXmlFile <- newNgFile(ngc.id, newParents)
        newNgcDir      = newNgcXmlFile.getParentFile
        archive       <- writeXml(
                           newNgcXmlFile,
                           nodeGroupCategorySerialisation.serialise(ngc),
                           "Archived node group category: " + newNgcXmlFile.getPath
                         )
        canMove       <- IOResult.attempt(null != oldNgcDir && oldNgcDir.exists)
        moved         <- ZIO.when(canMove) {
                           ZIO.whenZIO(IOResult.attempt(oldNgcDir.isDirectory)) {
                             // move content except category.xml
                             ZIO.foreach(oldNgcDir.listFiles.toSeq.filter(f => f.getName != serializedCategoryName)) { f =>
                               IOResult.attempt(FileUtils.moveToDirectory(f, newNgcDir, false))
                             }
                           } *>
                           // in all case, delete the file at the old directory path
                           IOResult.attempt(FileUtils.deleteQuietly(oldNgcDir))
                         }
        commit        <- gitCommit match {
                           case Some((modId, commiter, reason)) =>
                             commitMvDirectoryWithModId(
                               modId,
                               commiter,
                               toGitPath(oldNgcDir),
                               toGitPath(newNgcDir),
                               "Move archive of node group category with ID '%s'%s".format(ngc.id.value, GET(reason))
                             )
                           case None                            => ZIO.unit
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
  override def commitGroupLibrary(
      modificationId: ModificationId,
      commiter:       PersonIdent,
      reason:         Option[String]
  ): IOResult[GitArchiveId] = {
    this.commitFullGitPathContentAndTag(
      commiter,
      GROUPS_ARCHIVE_TAG + " Commit all modification done in Groups (git path: '%s')".format(groupLibraryRootDir)
    )
  }

  private def newNgFile(ngId: NodeGroupId, parents: List[NodeGroupCategoryId]) = {
    parents match {
      case h :: t => newCategoryDirectory(h, t).map(new File(_, ngId.withDefaultRev.serialize + ".xml"))
      case Nil    =>
        Inconsistency(
          "The given parent category list for node group with id '%s' is empty, what is forbidden".format(
            ngId.withDefaultRev.serialize
          )
        ).fail
    }
  }

  override def archiveNodeGroup(
      ng:        NodeGroup,
      parents:   List[NodeGroupCategoryId],
      gitCommit: Option[(ModificationId, PersonIdent, Option[String])]
  ): IOResult[GitPath] = {
    for {
      ngFile  <- newNgFile(ng.id, parents)
      archive <- writeXml(
                   ngFile,
                   nodeGroupSerialisation.serialise(ng),
                   "Archived node group: " + ngFile.getPath
                 )
      commit  <- gitCommit match {
                   case Some((modId, commiter, reason)) =>
                     commitAddFileWithModId(
                       modId,
                       commiter,
                       toGitPath(ngFile),
                       "Archive of node group with ID '%s'%s".format(ng.id.withDefaultRev.serialize, GET(reason))
                     )
                   case None                            => ZIO.unit
                 }
    } yield {
      GitPath(toGitPath(archive))
    }
  }

  override def deleteNodeGroup(
      ngId:       NodeGroupId,
      getParents: List[NodeGroupCategoryId],
      gitCommit:  Option[(ModificationId, PersonIdent, Option[String])]
  ): IOResult[GitPath] = {
    for {
      ngFile <- newNgFile(ngId, getParents)
      gitPath = toGitPath(ngFile)
      exists <- IOResult.attempt(ngFile.exists)
      res    <- if (exists) {
                  for {
                    // don't forget to delete the category *directory*
                    _ <- IOResult.attempt(FileUtils.forceDelete(ngFile))
                    _ <- logPure.debug(s"Deleted archived node group: ${ngFile.getPath}")
                    _ <- gitCommit match {
                           case Some((modId, commiter, reason)) =>
                             commitRmFileWithModId(
                               modId,
                               commiter,
                               gitPath,
                               s"Delete archive of node group with ID '${ngId.withDefaultRev.serialize}'${GET(reason)}"
                             )
                           case None                            => ZIO.unit
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

  override def moveNodeGroup(
      ng:         NodeGroup,
      oldParents: List[NodeGroupCategoryId],
      newParents: List[NodeGroupCategoryId],
      gitCommit:  Option[(ModificationId, PersonIdent, Option[String])]
  ): IOResult[GitPath] = {
    if (oldParents == newParents) { // actually, it's an update not a move
      this.archiveNodeGroup(ng, oldParents, gitCommit)
    } else {
      for {
        oldNgXmlFile <- newNgFile(ng.id, oldParents)
        newNgXmlFile <- newNgFile(ng.id, newParents)
        archive      <- writeXml(
                          newNgXmlFile,
                          nodeGroupSerialisation.serialise(ng),
                          "Archived node group: " + newNgXmlFile.getPath
                        )
        moved        <- ZIO.when(null != oldNgXmlFile && oldNgXmlFile.exists) {
                          IOResult.attempt(FileUtils.deleteQuietly(oldNgXmlFile))
                        }
        commit       <- gitCommit match {
                          case Some((modId, commiter, reason)) =>
                            commitMvDirectoryWithModId(
                              modId,
                              commiter,
                              toGitPath(oldNgXmlFile),
                              toGitPath(newNgXmlFile),
                              "Move archive of node group with ID '%s'%s".format(ng.id.withDefaultRev.serialize, GET(reason))
                            )
                          case None                            => ZIO.unit
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
    override val gitRepo:   GitRepositoryProvider,
    parameterSerialisation: GlobalParameterSerialisation,
    parameterRootDir:       String, // relative path !

    override val xmlPrettyPrinter:          RudderPrettyPrinter,
    override val gitModificationRepository: GitModificationRepository,
    override val encoding:                  String,
    override val groupOwner:                String
) extends GitParameterArchiver with NamedZioLogger with GitConfigItemRepository with XmlArchiverUtils
    with GitArchiverFullCommitUtils with BuildFilePaths[String] {

  override def loggerName: String = this.getClass.getName
  override val relativePath = parameterRootDir
  override val tagPrefix    = "archives/parameters/"

  override def getFileName(parameterName: String) = parameterName + ".xml"

  def archiveParameter(
      parameter: GlobalParameter,
      doCommit:  Option[(ModificationId, PersonIdent, Option[String])]
  ): IOResult[GitPath] = {
    for {
      paramFile <- newFile(parameter.name).toIO
      gitPath    = toGitPath(paramFile)
      archive   <- writeXml(
                     paramFile,
                     parameterSerialisation.serialise(parameter),
                     "Archived parameter: " + paramFile.getPath
                   )
      commit    <- doCommit match {
                     case Some((modId, commiter, reason)) =>
                       val msg = "Archive parameter with name '%s'%s".format(parameter.name, GET(reason))
                       commitAddFileWithModId(modId, commiter, gitPath, msg)
                     case None                            => ZIO.unit
                   }
    } yield {
      GitPath(gitPath)
    }
  }

  def commitParameters(modId: ModificationId, commiter: PersonIdent, reason: Option[String]): IOResult[GitArchiveId] = {
    this.commitFullGitPathContentAndTag(
      commiter,
      PARAMETERS_ARCHIVE_TAG + " Commit all modification done on parameters (git path: '%s')%s".format(
        parameterRootDir,
        GET(reason)
      )
    )
  }

  def deleteParameter(
      parameterName: String,
      doCommit:      Option[(ModificationId, PersonIdent, Option[String])]
  ): IOResult[GitPath] = {
    for {
      paramFile <- newFile(parameterName).toIO
      gitPath    = toGitPath(paramFile)
      _         <- ZIO.whenZIO(IOResult.attempt(paramFile.exists)) {
                     for {
                       deleted <- IOResult.attempt(FileUtils.forceDelete(paramFile))
                       _       <- logPure.debug(s"Deleted archive of parameter: ${paramFile.getPath}")
                       _       <- doCommit match {
                                    case Some((modId, commiter, reason)) =>
                                      commitRmFileWithModId(
                                        modId,
                                        commiter,
                                        gitPath,
                                        s"Delete archive of parameter with name '${parameterName}'${GET(reason)}"
                                      )
                                    case None                            => ZIO.unit
                                  }
                     } yield ()
                   }
    } yield {
      GitPath(gitPath)
    }
  }

}
