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

package com.normation.rudder.repository

import java.io.File
import com.normation.rudder.domain.policies.ConfigurationRule
import com.normation.rudder.domain.policies.ConfigurationRuleId
import net.liftweb.common.Box
import com.normation.rudder.domain.policies.PolicyInstance
import com.normation.rudder.domain.policies.PolicyInstanceId
import com.normation.rudder.domain.policies.UserPolicyTemplateCategory
import com.normation.rudder.domain.policies.UserPolicyTemplateCategoryId
import com.normation.rudder.domain.policies.UserPolicyTemplate
import com.normation.rudder.domain.policies.UserPolicyTemplateId
import com.normation.rudder.domain.policies.UserPolicyTemplateCategoryId
import com.normation.cfclerk.domain.SectionSpec
import com.normation.cfclerk.domain.PolicyPackageName
import com.normation.cfclerk.domain.PolicyPackage
import com.normation.rudder.domain.nodes.NodeGroupCategoryId
import com.normation.rudder.domain.nodes.NodeGroupCategory
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.nodes.NodeGroupId
import org.eclipse.jgit.revwalk.RevTag
import org.joda.time.DateTime

//case class to identify an archive
case class ArchiveId(value:String)

/**
 * This trait allow to manage archives of Policy library, configuration rules
 * and groupes. 
 * 
 * Archive can be done in one shot, partially updated, or read back. 
 */
trait ItemArchiveManager {
  
  /**
   * Save all items handled by that archive manager 
   * and return an ID for the archive on success. 
   */
  def exportAll(includeSystem:Boolean = false) : Box[ArchiveId]
  
  
  def exportConfigurationRules(includeSystem:Boolean = false) : Box[ArchiveId]
  
  def exportPolicyLibrary(includeSystem:Boolean = false) : Box[ArchiveId]
  
  def exportGroupLibrary(includeSystem:Boolean = false) : Box[ArchiveId]
  
  /**
   * Import the archive with the given ID in Rudder. 
   * If anything goes bad, implementation of that method
   * should take all the care to let Rudder in the state
   * where it was just before the (unsuccessful) import 
   * was required. 
   * 
   */
  def importAll(archiveId:RevTag, includeSystem:Boolean = false) : Box[Unit]

  
  def importConfigurationRules(archiveId:RevTag, includeSystem:Boolean = false) : Box[Unit]
  
  def importPolicyLibrary(archiveId:RevTag, includeSystem:Boolean = false) : Box[Unit]
  
  def importGroupLibrary(archiveId:RevTag, includeSystem:Boolean = false) : Box[Unit]
  
  
  /**
   * Get the list of tags for the archive type
   */
  
  def getFullArchiveTags : Box[Map[DateTime,RevTag]]
  
  def getGroupLibraryTags : Box[Map[DateTime,RevTag]]
  
  def getPolicyLibraryTags : Box[Map[DateTime,RevTag]]
  
  def getConfigurationRulesTags : Box[Map[DateTime,RevTag]]
  
}


/**
 * A specific trait to create archive of configuration rules
 */
trait GitConfigurationRuleArchiver {
  /**
   * Archive a configuration rule in a file system
   * managed by git. 
   * If gitCommit is true, the modification is
   * saved in git. Else, no modification in git are saved.
   */
  def archiveConfigurationRule(cr:ConfigurationRule, gitCommitCr:Boolean = true) : Box[File]
  
  /**
   * Commit modification done in the Git repository for any
   * configuration rules.
   * Return the git commit id. 
   */
  def commitConfigurationRules() : Box[String]
  
  def getTags() : Box[Map[DateTime,RevTag]]
  
  /**
   * Delete an archived configuration rule. 
   * If gitCommit is true, the modification is
   * saved in git. Else, no modification in git are saved.
   */
  def deleteConfigurationRule(crId:ConfigurationRuleId, gitCommitCr:Boolean = true) : Box[File]
  
  /**
   * Get the root directory where configuration rules are saved
   */
  def getRootDirectory : File
}


/////////////// User Policy Template Library ///////////////

/**
 * A specific trait to create archive of an user policy template category.
 */
trait GitUserPolicyTemplateCategoryArchiver {
  
  /**
   * Archive an user policy template category in a file system
   * managed by git. 
   * If gitCommit is true, the modification is
   * saved in git. Else, no modification in git are saved.
   */
  def archiveUserPolicyTemplateCategory(uptc:UserPolicyTemplateCategory, getParents:List[UserPolicyTemplateCategoryId], gitCommit:Boolean = true) : Box[File]
    
  /**
   * Delete an archived user policy template category. 
   * If gitCommit is true, the modification is
   * saved in git. Else, no modification in git are saved.
   */
  def deleteUserPolicyTemplateCategory(uptcId:UserPolicyTemplateCategoryId, getParents:List[UserPolicyTemplateCategoryId], gitCommit:Boolean = true) : Box[File]
  
  /**
   * Move an archived policy template category from a 
   * parent category to an other. 
   */
  def moveUserPolicyTemplateCategory(uptc:UserPolicyTemplateCategory, oldParents: List[UserPolicyTemplateCategoryId], newParents: List[UserPolicyTemplateCategoryId], gitCommit:Boolean = true) : Box[File]

  /**
   * Get the root directory where user policy template categories are saved.
   */
  def getRootDirectory : File  
  
  /**
   * Commit modification done in the Git repository for any
   * category, policy template and policy instance in the
   * user policy library.
   * Return the git commit id. 
   */
  def commitUserPolicyLibrary : Box[String]
  
  def getTags() : Box[Map[DateTime,RevTag]]
}

/**
 * A specific trait to create archive of an user policy template category.
 */
trait GitUserPolicyTemplateArchiver {
  
  /**
   * Archive an user policy template in a file system
   * managed by git. 
   * If gitCommit is true, the modification is
   * saved in git. Else, no modification in git are saved.
   */
  def archiveUserPolicyTemplate(upt:UserPolicyTemplate, parents:List[UserPolicyTemplateCategoryId], gitCommit:Boolean = true) : Box[File]
    
  /**
   * Delete an archived user policy template. 
   * If gitCommit is true, the modification is
   * saved in git. Else, no modification in git are saved.
   */
  def deleteUserPolicyTemplate(ptName:PolicyPackageName, parents:List[UserPolicyTemplateCategoryId], gitCommit:Boolean = true) : Box[File]
  
  /**
   * Move an archived policy template from a 
   * parent category to an other. 
   */
  def moveUserPolicyTemplate(upt:UserPolicyTemplate, oldParents: List[UserPolicyTemplateCategoryId], newParents: List[UserPolicyTemplateCategoryId], gitCommit:Boolean = true) : Box[File]

  /**
   * Get the root directory where user policy template categories are saved.
   */
  def getRootDirectory : File  
}

/**
 * A specific trait to create archive of a policy instance.
 */
trait GitPolicyInstanceArchiver {
  /**
   * Archive a policy instance in a file system
   * managed by git. 
   * If gitCommit is true, the modification is
   * saved in git. Else, no modification in git are saved.
   */
  def archivePolicyInstance(
      pi                 : PolicyInstance
    , ptName             : PolicyPackageName
    , catIds             : List[UserPolicyTemplateCategoryId]
    , variableRootSection: SectionSpec
    , gitCommit          : Boolean = true
  ) : Box[File]
    
  /**
   * Delete an archived policy instance. 
   * If gitCommit is true, the modification is
   * saved in git. Else, no modification in git are saved.
   */
  def deletePolicyInstance(
      piId     : PolicyInstanceId
    , ptName   : PolicyPackageName
    , catIds   : List[UserPolicyTemplateCategoryId]
    , gitCommit: Boolean = true
  ) : Box[File]
  
  /**
   * Get the root directory where policy instance are saved.
   * A policy instance won't be directly under that directory, but
   * will be on the sub-directories matching the category on which they are.
   */
  def getRootDirectory : File  
}


/////////////// Node Groups ///////////////


/**
 * A specific trait to create archive of an user policy template category.
 */
trait GitNodeGroupCategoryArchiver {
  
  /**
   * Archive a node group category in a file system
   * managed by git. 
   * If gitCommit is true, the modification is
   * saved in git. Else, no modification in git are saved.
   */
  def archiveNodeGroupCategory(groupCat:NodeGroupCategory, getParents:List[NodeGroupCategoryId], gitCommit:Boolean = true) : Box[File]
    
  /**
   * Delete an archived node group category. 
   * If gitCommit is true, the modification is
   * saved in git. Else, no modification in git are saved.
   */
  def deleteNodeGroupCategory(groupCatId:NodeGroupCategoryId, getParents:List[NodeGroupCategoryId], gitCommit:Boolean = true) : Box[File]
  
  /**
   * Move an archived node group category from a 
   * parent category to an other. 
   */
  def moveNodeGroupCategory(groupCat:NodeGroupCategory, oldParents: List[NodeGroupCategoryId], newParents: List[NodeGroupCategoryId], gitCommit:Boolean = true) : Box[File]

  /**
   * Get the root directory where node group categories are saved.
   */
  def getRootDirectory : File  
  
  /**
   * Commit modification done in the Git repository for any
   * category and groups. 
   * Return the git commit id. 
   */
  def commitGroupLibrary : Box[String]
  
  def getTags() : Box[Map[DateTime,RevTag]]
}

/**
 * A specific trait to create archive of an user node group.
 */
trait GitNodeGroupArchiver {
  
  /**
   * Archive an user policy template in a file system
   * managed by git. 
   * If gitCommit is true, the modification is
   * saved in git. Else, no modification in git are saved.
   */
  def archiveNodeGroup(nodeGroup:NodeGroup, parents:List[NodeGroupCategoryId], gitCommit:Boolean = true) : Box[File]
    
  /**
   * Delete an archived user policy template. 
   * If gitCommit is true, the modification is
   * saved in git. Else, no modification in git are saved.
   */
  def deleteNodeGroup(nodeGroup:NodeGroupId, parents:List[NodeGroupCategoryId], gitCommit:Boolean = true) : Box[File]
  
  /**
   * Move an archived policy template from a 
   * parent category to an other. 
   */
  def moveNodeGroup(nodeGroup:NodeGroup, oldParents: List[NodeGroupCategoryId], newParents: List[NodeGroupCategoryId], gitCommit:Boolean = true) : Box[File]

  /**
   * Get the root directory where user policy template categories are saved.
   */
  def getRootDirectory : File  
}
