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

import com.normation.rudder.domain.policies._
import com.normation.cfclerk.domain.PolicyPackageName
import net.liftweb.common._
import com.normation.cfclerk.domain.PolicyVersion
import org.joda.time.DateTime


/**
 * Define action on User policy template with the
 * back-end : save them, retrieve them, etc. 
 */

trait UserPolicyTemplateRepository {

  
  /**
   * Find back an user policy template thanks to its id. 
   * Return Empty if the user policy template is not found, 
   * Fails on error.
   */
  def getUserPolicyTemplate(id:UserPolicyTemplateId) : Box[UserPolicyTemplate]
  
  
  /**
   * Find back an user policy template thanks to the id of its referenced
   * Policy Template. 
   * Return Empty if the user policy template is not found, 
   * Fails on error.
   */
  def getUserPolicyTemplate(id:PolicyPackageName) : Box[UserPolicyTemplate]
  
  
  /**
   * Create a user policy template from the parameter WBPolicyTemplate
   * and add it in the given UserPolicyTemplateCategory
   * 
   * Returned the freshly created UserPolicyTemplate
   * 
   * Fails if 
   *   - the Policy Template id refer to none Policy Template, 
   *   - the category id does not exists,
   *   - the policy template is already in the user policy template 
   *     library
   */
  def addPolicyTemplateInUserLibrary(
    categoryId:UserPolicyTemplateCategoryId, 
    policyTemplateName:PolicyPackageName,
    versions:Seq[PolicyVersion]
  ) : Box[UserPolicyTemplate] 

  
  /**
   * Move a policy template to a new category.
   * Failure if the given policy template or category
   * does not exists. 
   * 
   */
  def move(uptId:UserPolicyTemplateId, newCategoryId:UserPolicyTemplateCategoryId) : Box[UserPolicyTemplateId] 
  
  /**
   * Set the status of the policy template to the new value
   */
  def changeStatus(uptId:UserPolicyTemplateId, status:Boolean) : Box[UserPolicyTemplateId] 
  
  /**
   * Add new (version,acceptation datetime) to existing 
   * acceptation datetimes by the new one.
   * 
   * Return empty if the uptIs not in the repos,
   * Failure if an error happened, 
   * Full(id) when success
   */
  def setAcceptationDatetimes(uptId:UserPolicyTemplateId, datetimes: Map[PolicyVersion,DateTime]) : Box[UserPolicyTemplateId]
  
  /**
   * Delete the policy template in user library.
   * If no such element exists, it is a success.
   */
  def delete(uptId:UserPolicyTemplateId) : Box[UserPolicyTemplateId] 
  
  /**
   * Retrieve the list of parents for the given policy template, 
   * till the root of policy library.
   * Return empty if the path can not be build
   * (missing policy template, missing category, etc)
   */
  def userPolicyTemplateBreadCrump(id:UserPolicyTemplateId) : Box[List[UserPolicyTemplateCategory]]
  
}