/*
 *************************************************************************************
 * Copyright 2013 Normation SAS
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

package com.normation.rudder.rule.category

import com.normation.NamedZioLogger
import com.normation.errors.*
import com.normation.eventlog.ModificationId
import com.normation.rudder.domain.Constants.RULE_CATEGORY_ARCHIVE_TAG
import com.normation.rudder.git.GitArchiveId
import com.normation.rudder.git.GitArchiverFullCommitUtils
import com.normation.rudder.git.GitConfigItemRepository
import com.normation.rudder.git.GitPath
import com.normation.rudder.git.GitRepositoryProvider
import com.normation.rudder.repository.GitModificationRepository
import com.normation.rudder.repository.xml.*
import com.normation.rudder.services.marshalling.RuleCategorySerialisation
import java.io.File
import java.time.Instant
import org.apache.commons.io.FileUtils
import org.eclipse.jgit.lib.PersonIdent
import zio.*

trait GitRuleCategoryArchiver {

  /**
   * Archive a Rule category in a file system managed by git.
   * If gitCommit is true, the modification is
   * saved in git. Else, no modification in git are saved.
   *
   * reason is an optional commit message to add to the standard one.
   */
  def archiveRuleCategory(
      category:         RuleCategory,
      parentCategories: List[RuleCategoryId],
      gitCommit:        Option[(ModificationId, PersonIdent, Option[String])]
  ): IOResult[GitPath]

  /**
   * Commit modification done in the Git repository for any Rule category.
   * Also add a tag with the given return GitPath.
   * The returned commit hash is the one for the tag.
   * Return the git commit id.
   *
   * reason is an optional commit message to add to the standard one.
   */
  def commitRuleCategories(
      modId:    ModificationId,
      commiter: PersonIdent,
      reason:   Option[String]
  ): IOResult[GitArchiveId]

  def getTags(): IOResult[Map[Instant, GitArchiveId]]

  /**
   * Delete an archived Rule category.
   * If gitCommit is true, the modification is
   * saved in git. Else, no modification in git are saved.
   *
   * reason is an optional commit message to add to the standard one.
   */
  def deleteRuleCategory(
      categoryId: RuleCategoryId,
      parents:    List[RuleCategoryId],
      gitCommit:  Option[(ModificationId, PersonIdent, Option[String])]
  ): IOResult[GitPath]

  /**
   * Move an archived Rule category from a
   * parent category to an other.
   */
  def moveRuleCategory(
      groupCat:   RuleCategory,
      oldParents: List[RuleCategoryId],
      newParents: List[RuleCategoryId],
      gitCommit:  Option[(ModificationId, PersonIdent, Option[String])]
  ): IOResult[GitPath]

  /**
   * Get the root directory where Rule categories are saved
   */
  def getItemDirectory: File
}

class GitRuleCategoryArchiverImpl(
    override val gitRepo:      GitRepositoryProvider,
    ruleCategorySerialisation: RuleCategorySerialisation,
    ruleCategoryRootDir:       String, // relative path !

    override val xmlPrettyPrinter:          RudderPrettyPrinter,
    override val gitModificationRepository: GitModificationRepository,
    override val encoding:                  String,
    categoryFileName:                       String,
    override val groupOwner:                String
) extends GitRuleCategoryArchiver with NamedZioLogger with GitConfigItemRepository with XmlArchiverUtils
    with GitArchiverFullCommitUtils with BuildCategoryPathName[RuleCategoryId] {

  override def loggerName: String = this.getClass.getName
  override val relativePath = ruleCategoryRootDir
  override val tagPrefix    = "archives/configurations-rules/"

  def getCategoryName(categoryId: RuleCategoryId): String = categoryId.value

  private def categoryFile(category: RuleCategoryId, parents: List[RuleCategoryId]) =
    newCategoryDirectory(category, parents).map(new File(_, categoryFileName))

  def archiveRuleCategory(
      category:  RuleCategory,
      parents:   List[RuleCategoryId],
      gitCommit: Option[(ModificationId, PersonIdent, Option[String])]
  ): IOResult[GitPath] = {
    // Build Rule category file, needs to reverse parents , start from end)
    for {
      ruleCategoryFile <- categoryFile(category.id, parents.reverse)
      gitPath           = toGitPath(ruleCategoryFile)
      archive          <- writeXml(
                            ruleCategoryFile,
                            ruleCategorySerialisation.serialise(category),
                            "Archived Rule category: " + ruleCategoryFile.getPath
                          )
      commit           <- gitCommit match {
                            case Some((modId, commiter, reason)) =>
                              val commitMsg = s"Archive rule Category with ID '${category.id.value}' ${GET(reason)}"
                              commitAddFileWithModId(modId, commiter, gitPath, commitMsg)
                            case None                            =>
                              ZIO.unit
                          }
    } yield {
      GitPath(gitPath)
    }
  }

  def commitRuleCategories(modId: ModificationId, commiter: PersonIdent, reason: Option[String]): IOResult[GitArchiveId] = {
    this.commitFullGitPathContentAndTag(
      commiter,
      s"${RULE_CATEGORY_ARCHIVE_TAG} Commit all modification done on rules (git path: '${ruleCategoryRootDir}') ${GET(reason)}"
    )
  }

  def deleteRuleCategory(
      categoryId: RuleCategoryId,
      parents:    List[RuleCategoryId],
      doCommit:   Option[(ModificationId, PersonIdent, Option[String])]
  ): IOResult[GitPath] = {
    // Build Rule category file, needs to reverse parents , start from end)
    for {
      ruleCategoryFile <- categoryFile(categoryId, parents.reverse)
      gitPath           = toGitPath(ruleCategoryFile)
      _                <- ZIO.whenZIO(IOResult.attempt(ruleCategoryFile.exists)) {
                            for {
                              _ <- IOResult.attempt(FileUtils.forceDelete(ruleCategoryFile))
                              _ <- logPure.debug("Deleted archive of rule: " + ruleCategoryFile.getPath)
                              _ <- doCommit match {
                                     case Some((modId, commiter, reason)) =>
                                       val commitMsg = s"Delete archive of rule with ID '${categoryId.value} ${GET(reason)}"
                                       commitRmFileWithModId(modId, commiter, gitPath, commitMsg)
                                     case None                            =>
                                       ZIO.unit
                                   }
                            } yield ()
                          }
    } yield {
      GitPath(gitPath)
    }
  }

  override def moveRuleCategory(
      category:   RuleCategory,
      oldParents: List[RuleCategoryId],
      newParents: List[RuleCategoryId],
      gitCommit:  Option[(ModificationId, PersonIdent, Option[String])]
  ): IOResult[GitPath] = {

    // guard: if source == dest, then it's an update
    if (oldParents == newParents) {
      this.archiveRuleCategory(category, oldParents, gitCommit)
    } else {
      for {
        oldCategoryDir  <- categoryFile(category.id, oldParents.reverse).map(_.getParentFile)
        newCategoryFile <- categoryFile(category.id, newParents.reverse)
        newCategoryDir   = newCategoryFile.getParentFile
        archive         <- writeXml(
                             newCategoryFile,
                             ruleCategorySerialisation.serialise(category),
                             s"Archived rule category: ${newCategoryFile.getPath}"
                           )
        moved           <- {
          ZIO.when(null != oldCategoryDir && oldCategoryDir.exists) {
            ZIO.when(oldCategoryDir.isDirectory) {
              // move content except category.xml
              val filteredDir = oldCategoryDir.listFiles.toSeq.filter(f => f.getName != categoryFileName)
              ZIO.foreach(filteredDir)(f => IOResult.attempt(FileUtils.moveToDirectory(f, newCategoryDir, false)))
            } *>
            // in all case, delete the file at the old directory path
            IOResult.attempt(FileUtils.deleteQuietly(oldCategoryDir))
          }
        }
        commit          <- gitCommit match {
                             case Some((modId, commiter, reason)) =>
                               val commitMsg = s"Move archive of rule category with ID '${category.id.value}'${GET(reason)}"
                               val oldPath   = toGitPath(oldCategoryDir)
                               val newPath   = toGitPath(newCategoryDir)
                               commitMvDirectoryWithModId(modId, commiter, oldPath, newPath, commitMsg)
                             case None                            =>
                               ZIO.unit
                           }
      } yield {
        GitPath(toGitPath(archive))
      }
    }
  }
}
