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

package com.normation.rudder.repository

import java.io.File

import com.normation.cfclerk.domain.SectionSpec
import com.normation.cfclerk.domain.TechniqueName
import com.normation.eventlog.EventActor
import com.normation.eventlog.EventLog
import com.normation.eventlog.ModificationId
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.nodes.NodeGroupCategory
import com.normation.rudder.domain.nodes.NodeGroupCategoryId
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.parameters.GlobalParameter
import com.normation.rudder.domain.parameters.ParameterName
import com.normation.rudder.domain.policies.ActiveTechnique
import com.normation.rudder.domain.policies.ActiveTechniqueCategory
import com.normation.rudder.domain.policies.ActiveTechniqueCategoryId
import com.normation.rudder.domain.policies.ActiveTechniqueId
import com.normation.rudder.domain.policies.Directive
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.Rule
import com.normation.rudder.domain.policies.RuleId
import com.normation.utils.HashcodeCaching

import org.eclipse.jgit.lib.PersonIdent
import org.joda.time.DateTime

import com.normation.errors._


/**
 * Container for a Git Path.
 * Notice: a GIT path should be well formed
 * (never starts with a "/", not heading nor trailing
 * empty characters), but this is not enforce by that class.
 */
final case class GitPath(value: String) extends HashcodeCaching

/**
 * A git commit string character, the SHA-1 hash that can be
 * use in git command line with git checkout, git show, etc.
 */
final case class GitCommitId(value: String) extends HashcodeCaching


/**
 * A Git archive ID is a couple of the path on witch the archive was made,
 * and the commit that reference the tag.
 * Note that the commit ID is stable in time, but the path is just an
 * indication, especially if its 'master' (a branch path is more likely to be
 * a little more stable).
 */
final case class GitArchiveId(path: GitPath, commit: GitCommitId, commiter: PersonIdent) extends HashcodeCaching


final case class ActiveTechniqueNotArchived(
    activeTechniqueId: ActiveTechniqueId
  , cause            : RudderError
)

final case class DirectiveNotArchived(
    directiveId: DirectiveId
  , cause      : RudderError
)

final case class CategoryNotArchived(
    categoryId: ActiveTechniqueCategoryId
  , cause     : RudderError
)

final case class NotArchivedElements(
    categories      : Seq[CategoryNotArchived]
  , activeTechniques: Seq[ActiveTechniqueNotArchived]
  , directives      : Seq[DirectiveNotArchived]
) {
  val isEmpty = categories.isEmpty && activeTechniques.isEmpty && directives.isEmpty
}



/**
 * This trait allow to manage archives of technique library, rules
 * and groupes.
 *
 * Archive can be done in one shot, partially updated, or read back.
 */
trait ItemArchiveManager {

  /**
   * Save all items handled by that archive manager
   * and return an ID for the archive on success.
   */
  def exportAll(commiter:PersonIdent, modId: ModificationId, actor:EventActor, reason:Option[String], includeSystem:Boolean = false) : IOResult[(GitArchiveId, NotArchivedElements)]

  def exportRules(commiter:PersonIdent, modId: ModificationId, actor:EventActor, reason:Option[String], includeSystem:Boolean = false) : IOResult[GitArchiveId]

  /**
   * Export the technique library.
   * The strategy in case some directive are in error is to ignore them, keeping error message so that they can be logged/displayed
   * at the end of the process.
   */
  def exportTechniqueLibrary(commiter:PersonIdent, modId: ModificationId, actor:EventActor, reason:Option[String], includeSystem:Boolean = false) : IOResult[(GitArchiveId, NotArchivedElements)]

  def exportGroupLibrary(commiter:PersonIdent, modId: ModificationId, actor:EventActor, reason:Option[String], includeSystem:Boolean = false) : IOResult[GitArchiveId]

  def exportParameters(commiter:PersonIdent, modId: ModificationId, actor:EventActor, reason:Option[String], includeSystem:Boolean = false) : IOResult[GitArchiveId]

  /**
   * Import the archive with the given ID in Rudder.
   * If anything goes bad, implementation of that method
   * should take all the care to let Rudder in the state
   * where it was just before the (unsuccessful) import
   * was required.
   *
   */
  def importAll(archiveId:GitCommitId, commiter:PersonIdent, modId: ModificationId, actor:EventActor, reason:Option[String], includeSystem:Boolean = false) : IOResult[GitCommitId]

  def importRules(archiveId:GitCommitId, commiter:PersonIdent, modId: ModificationId, actor:EventActor, reason:Option[String], includeSystem:Boolean = false) : IOResult[GitCommitId]

  def importTechniqueLibrary(archiveId:GitCommitId, commiter:PersonIdent, modId: ModificationId, actor:EventActor, reason:Option[String], includeSystem:Boolean = false) : IOResult[GitCommitId]

  def importGroupLibrary(archiveId:GitCommitId, commiter:PersonIdent, modId: ModificationId, actor:EventActor, reason:Option[String], includeSystem:Boolean = false) : IOResult[GitCommitId]

  def importParameters(archiveId:GitCommitId, commiter:PersonIdent, modId: ModificationId, actor:EventActor, reason:Option[String], includeSystem:Boolean = false) : IOResult[GitCommitId]

  /**
   * Import the item archive from HEAD (corresponding to last commit)
   */
  private[this] def lastGitCommitId = GitCommitId("HEAD")

  def importHeadAll(commiter:PersonIdent, modId: ModificationId, actor:EventActor, reason: Option[String], includeSystem:Boolean = false) : IOResult[GitCommitId] = {
    importAll(lastGitCommitId, commiter, modId, actor, reason, includeSystem)
  }

  def importHeadRules(commiter:PersonIdent, modId: ModificationId, actor:EventActor, reason: Option[String], includeSystem:Boolean = false) : IOResult[GitCommitId] = {
    importRules(lastGitCommitId, commiter, modId, actor, reason, includeSystem)
  }

  def importHeadTechniqueLibrary(commiter:PersonIdent, modId: ModificationId, actor:EventActor, reason: Option[String], includeSystem:Boolean = false) : IOResult[GitCommitId] = {
    importTechniqueLibrary(lastGitCommitId, commiter, modId, actor, reason, includeSystem)
  }

  def importHeadGroupLibrary(commiter:PersonIdent, modId: ModificationId, actor:EventActor, reason: Option[String], includeSystem:Boolean = false) : IOResult[GitCommitId] = {
    importGroupLibrary(lastGitCommitId, commiter, modId, actor, reason, includeSystem)
  }

  def importHeadParameters(commiter:PersonIdent, modId: ModificationId, actor:EventActor, reason:Option[String]) : IOResult[GitCommitId]= {
    importParameters(lastGitCommitId, commiter, modId, actor, reason)
  }

  /**
   * Rollback method
   */
  def rollback(archiveId:GitCommitId, commiter:PersonIdent, modId:ModificationId, actor:EventActor, reason:Option[String],  rollbackedEvents :Seq[EventLog], target:EventLog, rollbackType:String, includeSystem:Boolean = false) : IOResult[GitCommitId]
  /**
   * Get the list of tags for the archive type
   */
  def getFullArchiveTags : IOResult[Map[DateTime,GitArchiveId]]

  def getGroupLibraryTags : IOResult[Map[DateTime,GitArchiveId]]

  def getTechniqueLibraryTags : IOResult[Map[DateTime,GitArchiveId]]

  def getRulesTags : IOResult[Map[DateTime,GitArchiveId]]

  def getParametersTags : IOResult[Map[DateTime,GitArchiveId]]
}


/**
 * A specific trait to create archive of rules
 */
trait GitRuleArchiver {
  /**
   * Archive a rule in a file system
   * managed by git.
   * If gitCommit is true, the modification is
   * saved in git. Else, no modification in git are saved.
   *
   * reason is an optional commit message to add to the standard one.
   */
  def archiveRule(rule:Rule, gitCommit:Option[(ModificationId, PersonIdent,Option[String])]) : IOResult[GitPath]

  /**
   * Commit modification done in the Git repository for any
   * rules.
   * Also add a tag with the given return GitPath.
   * The returned commit hash is the one for the tag.
   * Return the git commit id.
   *
   * reason is an optional commit message to add to the standard one.
   */
  def commitRules(modId: ModificationId, commiter:PersonIdent, reason:Option[String]) : IOResult[GitArchiveId]

  def getTags() : IOResult[Map[DateTime,GitArchiveId]]

  /**
   * Delete an archived rule.
   * If gitCommit is true, the modification is
   * saved in git. Else, no modification in git are saved.
   *
   * reason is an optional commit message to add to the standard one.
   */
  def deleteRule(ruleId:RuleId, gitCommitCr:Option[(ModificationId, PersonIdent,Option[String])]) : IOResult[GitPath]

  /**
   * Get the root directory where rules are saved
   */
  def getRootDirectory : File
}

/**
 * A specific trait to create archive of Rule Categories
 */



/////////////// Active Technique Library ///////////////



/**
 * A specific trait to create archive of an active technique category.
 */
trait GitActiveTechniqueCategoryArchiver {

  /**
   * Archive an active technique category in a file system
   * managed by git.
   * If gitCommit is true, the modification is
   * saved in git. Else, no modification in git are saved.
   */
  def archiveActiveTechniqueCategory(uptc:ActiveTechniqueCategory, getParents:List[ActiveTechniqueCategoryId], gitCommit:Option[(ModificationId, PersonIdent,Option[String])]) : IOResult[GitPath]

  /**
   * Delete an archived active technique category.
   * If gitCommit is true, the modification is
   * saved in git. Else, no modification in git are saved.
   */
  def deleteActiveTechniqueCategory(uptcId:ActiveTechniqueCategoryId, getParents:List[ActiveTechniqueCategoryId], gitCommit:Option[(ModificationId, PersonIdent,Option[String])]) : IOResult[GitPath]

  /**
   * Move an archived technique category from a
   * parent category to an other.
   */
  def moveActiveTechniqueCategory(uptc:ActiveTechniqueCategory, oldParents: List[ActiveTechniqueCategoryId], newParents: List[ActiveTechniqueCategoryId], gitCommit:Option[(ModificationId, PersonIdent,Option[String])]) : IOResult[GitPath]

  /**
   * Get the root directory where active technique categories are saved.
   */
  def getRootDirectory : File

  /**
   * Commit modification done in the Git repository for any
   * category, technique and directive in the
   * active technique library.
   * Return the git commit id.
   */
  def commitActiveTechniqueLibrary(modId: ModificationId, commiter:PersonIdent, reason:Option[String]) : IOResult[GitArchiveId]

  def getTags() : IOResult[Map[DateTime,GitArchiveId]]
}

/**
 * A specific trait to create archive of an active technique category.
 */
trait GitActiveTechniqueArchiver {

  /**
   * Archive an active technique in a file system
   * managed by git.
   * If gitCommit is true, the modification is
   * saved in git. Else, no modification in git are saved.
   *
   * The archiving will archive as many things as it can, and won't stop
   * to the first error if other part could be archivied.
   * For example, a failing directive won't fail the whole archive,
   * but the list of failed directive will be stored.
   *
   * If an error at the ActiveTechnique happens, the whole archive step is
   * in error.
   */
  def archiveActiveTechnique(activeTechnique:ActiveTechnique, parents:List[ActiveTechniqueCategoryId], gitCommit:Option[(ModificationId, PersonIdent,Option[String])]) : IOResult[(GitPath, Seq[DirectiveNotArchived])]

  /**
   * Delete an archived active technique.
   * If gitCommit is true, the modification is
   * saved in git. Else, no modification in git are saved.
   */
  def deleteActiveTechnique(ptName:TechniqueName, parents:List[ActiveTechniqueCategoryId], gitCommit:Option[(ModificationId, PersonIdent,Option[String])]) : IOResult[GitPath]

  /**
   * Move an archived technique from a
   * parent category to an other.
   */
  def moveActiveTechnique(activeTechnique:ActiveTechnique, oldParents: List[ActiveTechniqueCategoryId], newParents: List[ActiveTechniqueCategoryId], gitCommit:Option[(ModificationId, PersonIdent,Option[String])]) : IOResult[(GitPath, Seq[DirectiveNotArchived])]

  /**
   * Get the root directory where active technique categories are saved.
   */
  def getRootDirectory : File
}

/**
 * A specific trait to create archive of a directive.
 */
trait GitDirectiveArchiver {
  /**
   * Archive a directive in a file system
   * managed by git.
   * If gitCommit is true, the modification is
   * saved in git. Else, no modification in git are saved.
   */
  def archiveDirective(
      directive          : Directive
    , ptName             : TechniqueName
    , catIds             : List[ActiveTechniqueCategoryId]
    , variableRootSection: SectionSpec
    , gitCommit          : Option[(ModificationId, PersonIdent,Option[String])]
  ) : IOResult[GitPath]

  /**
   * Delete an archived directive.
   * If gitCommit is true, the modification is
   * saved in git. Else, no modification in git are saved.
   */
  def deleteDirective(
      directiveId: DirectiveId
    , ptName     : TechniqueName
    , catIds     : List[ActiveTechniqueCategoryId]
    , gitCommit  : Option[(ModificationId, PersonIdent,Option[String])]
  ) : IOResult[GitPath]

  /**
   * Get the root directory where directive are saved.
   * A directive won't be directly under that directory, but
   * will be on the sub-directories matching the category on which they are.
   */
  def getRootDirectory : File
}


/////////////// Node Groups ///////////////


/**
 * A specific trait to create archive of an active technique category.
 */
trait GitNodeGroupArchiver {

  /**
   * Archive a node group category in a file system
   * managed by git.
   * If gitCommit is true, the modification is
   * saved in git. Else, no modification in git are saved.
   */
  def archiveNodeGroupCategory(groupCat:NodeGroupCategory, getParents:List[NodeGroupCategoryId], gitCommit:Option[(ModificationId, PersonIdent,Option[String])]) : IOResult[GitPath]

  /**
   * Delete an archived node group category.
   * If gitCommit is true, the modification is
   * saved in git. Else, no modification in git are saved.
   */
  def deleteNodeGroupCategory(groupCatId:NodeGroupCategoryId, getParents:List[NodeGroupCategoryId], gitCommit:Option[(ModificationId, PersonIdent,Option[String])]) : IOResult[GitPath]

  /**
   * Move an archived node group category from a
   * parent category to an other.
   */
  def moveNodeGroupCategory(groupCat:NodeGroupCategory, oldParents: List[NodeGroupCategoryId], newParents: List[NodeGroupCategoryId], gitCommit:Option[(ModificationId, PersonIdent,Option[String])]) : IOResult[GitPath]

  /**
   * Get the root directory where node group categories are saved.
   */
  def getRootDirectory : File

  /**
   * Commit modification done in the Git repository for any
   * category and groups.
   * Return the git commit id.
   */
  def commitGroupLibrary(modId: ModificationId, commiter:PersonIdent, reason:Option[String]) : IOResult[GitArchiveId]

  def getTags() : IOResult[Map[DateTime,GitArchiveId]]

  /**
   * Archive an active technique in a file system
   * managed by git.
   * If gitCommit is true, the modification is
   * saved in git. Else, no modification in git are saved.
   */
  def archiveNodeGroup(nodeGroup:NodeGroup, parents:List[NodeGroupCategoryId], gitCommit:Option[(ModificationId, PersonIdent,Option[String])]) : IOResult[GitPath]

  /**
   * Delete an archived active technique.
   * If gitCommit is true, the modification is
   * saved in git. Else, no modification in git are saved.
   */
  def deleteNodeGroup(nodeGroup:NodeGroupId, parents:List[NodeGroupCategoryId], gitCommit:Option[(ModificationId, PersonIdent,Option[String])]) : IOResult[GitPath]

  /**
   * Move an archived technique from a
   * parent category to an other.
   */
  def moveNodeGroup(nodeGroup:NodeGroup, oldParents: List[NodeGroupCategoryId], newParents: List[NodeGroupCategoryId], gitCommit:Option[(ModificationId, PersonIdent,Option[String])]) : IOResult[GitPath]

}

/**
 * A specific trait to create archive of Global parameter
 */
trait GitParameterArchiver {
  /**
   * Archive a parameter in a file system managed by git.
   * If gitCommit is true, the modification is
   * saved in git. Else, no modification in git are saved.
   *
   * reason is an optional commit message to add to the standard one.
   */
  def archiveParameter(parameter:GlobalParameter, gitCommit:Option[(ModificationId, PersonIdent,Option[String])]) : IOResult[GitPath]

  /**
   * Commit modification done in the Git repository for any
   * parameter.
   * Also add a tag with the given return GitPath.
   * The returned commit hash is the one for the tag.
   * Return the git commit id.
   *
   * reason is an optional commit message to add to the standard one.
   */
  def commitParameters(modId: ModificationId, commiter:PersonIdent, reason:Option[String]) : IOResult[GitArchiveId]

  def getTags() : IOResult[Map[DateTime,GitArchiveId]]

  /**
   * Delete an archived parameter.
   * If gitCommit is true, the modification is
   * saved in git. Else, no modification in git are saved.
   *
   * reason is an optional commit message to add to the standard one.
   */
  def deleteParameter(parameterName:ParameterName, gitCommit:Option[(ModificationId, PersonIdent,Option[String])]) : IOResult[GitPath]

  /**
   * Get the root directory where parameter are saved
   */
  def getRootDirectory : File
}
