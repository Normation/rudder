/*
*************************************************************************************
* Copyright 2013 Normation SAS
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

package com.normation.rudder.rule.category

import com.normation.cfclerk.services.GitRepositoryProvider
import java.io.File
import com.normation.rudder.services.marshalling.RuleCategorySerialisation
import scala.xml.PrettyPrinter
import com.normation.rudder.repository.GitModificationRepository
import net.liftweb.common.Loggable
import com.normation.rudder.repository.xml._
import com.normation.eventlog.ModificationId
import com.normation.rudder.services.user.PersonIdentService
import com.normation.rudder.domain.Constants.RULE_CATEGORY_ARCHIVE_TAG
import net.liftweb.common.Box
import com.normation.rudder.repository.GitArchiveId
import com.normation.rudder.repository.GitPath
import net.liftweb.common.Full
import org.joda.time.DateTime
import com.normation.utils.Control.sequence
import net.liftweb.util.ControlHelpers.tryo
import org.apache.commons.io.FileUtils
import org.eclipse.jgit.lib.PersonIdent

trait GitRuleCategoryArchiver {

  /**
   * Archive a Rule category in a file system managed by git.
   * If gitCommit is true, the modification is
   * saved in git. Else, no modification in git are saved.
   *
   * reason is an optional commit message to add to the standard one.
   */
  def archiveRuleCategory (
      category         : RuleCategory
    , parentCategories : List[RuleCategoryId]
    , gitCommit        : Option[(ModificationId, PersonIdent,Option[String])]
  ) : Box[GitPath]

  /**
   * Commit modification done in the Git repository for any Rule category.
   * Also add a tag with the given return GitPath.
   * The returned commit hash is the one for the tag.
   * Return the git commit id.
   *
   * reason is an optional commit message to add to the standard one.
   */
  def commitRuleCategories(
      modId: ModificationId
    , commiter:PersonIdent
    , reason:Option[String]
  ) : Box[GitArchiveId]

  def getTags : Box[Map[DateTime,GitArchiveId]]

  /**
   * Delete an archived Rule category.
   * If gitCommit is true, the modification is
   * saved in git. Else, no modification in git are saved.
   *
   * reason is an optional commit message to add to the standard one.
   */
  def deleteRuleCategory(
      categoryId : RuleCategoryId
    , parents    : List[RuleCategoryId]
    , gitCommit  : Option[(ModificationId, PersonIdent,Option[String])]
  ) : Box[GitPath]

  /**
   * Move an archived Rule category from a
   * parent category to an other.
   */
  def moveRuleCategory (
      groupCat:RuleCategory
    , oldParents: List[RuleCategoryId]
    , newParents: List[RuleCategoryId]
    , gitCommit:Option[(ModificationId, PersonIdent, Option[String])]
  ) : Box[GitPath]

  /**
   * Get the root directory where Rule categories are saved
   */
  def getRootDirectory : File
}


class GitRuleCategoryArchiverImpl(
    override val gitRepo                   : GitRepositoryProvider
  , override val gitRootDirectory          : File
  , ruleCategorySerialisation              : RuleCategorySerialisation
  , ruleCategoryRootDir                    : String //relative path !
  , override val xmlPrettyPrinter          : PrettyPrinter
  , override val gitModificationRepository : GitModificationRepository
  , override val encoding                  : String = "UTF-8"
  , categoryFileName                       : String = "category.xml"
) extends
  GitRuleCategoryArchiver with
  Loggable with
  GitArchiverUtils with
  GitArchiverFullCommitUtils with
  BuildCategoryPathName[RuleCategoryId]
{


  override val relativePath = ruleCategoryRootDir
  override val tagPrefix = "archives/configurations-rules/"

  def getCategoryName(categoryId:RuleCategoryId):String = categoryId.value

  private[this] def categoryFile(category: RuleCategoryId, parents : List[RuleCategoryId]) = new File(newCategoryDirectory(category, parents), categoryFileName)

  def archiveRuleCategory(
      category  : RuleCategory
    , parents   : List[RuleCategoryId]
    , gitCommit : Option[(ModificationId, PersonIdent, Option[String])]
  ) : Box[GitPath] = {
    // Build Rule category file, needs to reverse parents , start from end)
    val ruleCategoryFile = categoryFile(category.id, parents.reverse)
    val gitPath = toGitPath(ruleCategoryFile)
    for {
      archive <- writeXml(
                     ruleCategoryFile
                   , ruleCategorySerialisation.serialise(category)
                   , "Archived Rule category: " + ruleCategoryFile.getPath
                 )
      commit  <- gitCommit match {
                   case Some((modId, commiter, reason)) =>
                     val commitMsg = s"Archive rule Category with ID '${category.id.value}' ${GET(reason)}"
                     commitAddFile(modId, commiter, gitPath, commitMsg)
                   case None =>
                     Full("ok")
                 }
    } yield {
      GitPath(gitPath)
    }
  }

  def commitRuleCategories(modId: ModificationId, commiter:PersonIdent, reason:Option[String]) : Box[GitArchiveId] = {
    this.commitFullGitPathContentAndTag(
        commiter
      , s"${RULE_CATEGORY_ARCHIVE_TAG} Commit all modification done on rules (git path: '${ruleCategoryRootDir}') ${GET(reason)}"
    )
  }

  def deleteRuleCategory(
      categoryId : RuleCategoryId
    , parents    : List[RuleCategoryId]
    , doCommit   :Option[(ModificationId, PersonIdent, Option[String])]
  ) : Box[GitPath] = {
    // Build Rule category file, needs to reverse parents , start from end)
    val ruleCategoryFile = categoryFile(categoryId, parents.reverse)
    val gitPath = toGitPath(ruleCategoryFile)
    if(ruleCategoryFile.exists) {
      for {
        deleted  <- tryo {
                      FileUtils.forceDelete(ruleCategoryFile)
                      logger.debug("Deleted archive of rule: " + ruleCategoryFile.getPath)
                    }
        commited <- doCommit match {
                      case Some((modId, commiter, reason)) =>
                        val commitMsg = s"Delete archive of rule with ID '${categoryId.value} ${GET(reason)}"
                        commitRmFile(modId, commiter, gitPath, commitMsg)
                      case None =>
                        Full("OK")
                    }
      } yield {
        GitPath(gitPath)
      }
    } else {
      Full(GitPath(gitPath))
    }
  }

  override def moveRuleCategory(
      category   : RuleCategory
    , oldParents : List[RuleCategoryId]
    , newParents : List[RuleCategoryId]
    , gitCommit  : Option[(ModificationId, PersonIdent, Option[String])]
  ) : Box[GitPath] = {
    val oldCategoryDir  = categoryFile(category.id, oldParents.reverse).getParentFile
    val newCategoryFile = categoryFile(category.id, newParents.reverse)
    val newCategoryDir  = newCategoryFile.getParentFile

    for {
      archive <- writeXml(
                     newCategoryFile
                   , ruleCategorySerialisation.serialise(category)
                   , s"Archived rule category: ${newCategoryFile.getPath}"
                 )
      moved   <- {
                   if(null != oldCategoryDir && oldCategoryDir.exists) {
                     if(oldCategoryDir.isDirectory) {
                       //move content except category.xml
                       val filteredDir = oldCategoryDir.listFiles.toSeq.filter( f => f.getName != categoryFileName)
                       sequence(filteredDir) { f => tryo { FileUtils.moveToDirectory(f, newCategoryDir, false) } }
                     }
                     //in all case, delete the file at the old directory path
                     tryo { FileUtils.deleteQuietly(oldCategoryDir) }
                   } else {
                     Full("OK")
                   }
                 }
      commit  <- gitCommit match {
                   case Some((modId, commiter, reason)) =>
                     val commitMsg = s"Move archive of node group category with ID '${category.id.value}'${GET(reason)}"
                     val oldPath = toGitPath(oldCategoryDir)
                     val newPath = toGitPath(newCategoryDir)
                     commitMvDirectory(modId, commiter, oldPath, newPath, commitMsg)
                   case None =>
                     Full("ok")
                 }
    } yield {
      GitPath(toGitPath(archive))
    }
  }

}