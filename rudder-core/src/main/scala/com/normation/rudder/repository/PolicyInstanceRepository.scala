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
import net.liftweb.common._
import com.normation.eventlog.EventActor



/**
 * The policy instance repository. 
 * 
 * Policy instance are instance of policy template
 * (a policy template + values for its parameters)
 *
 */
trait PolicyInstanceRepository {

  /**
   * Try to find the policy instance with the given ID.
   * Empty: no policy instance with such ID
   * Full((parent,pi)) : found the policy instance (pi.id == piId) in given parent
   * Failure => an error happened.
   */
  def getPolicyInstance(piId:PolicyInstanceId) : Box[PolicyInstance]

  
  /**
   * Find the user policy template for which the given policy
   * instance is an instance. 
   * 
   * Return empty if no such policy instance is known, 
   * fails if no User policy template match the policy instance.
   */
  def getUserPolicyTemplate(id:PolicyInstanceId) : Box[UserPolicyTemplate]  
  
  /**
   * Get policy instances for given policy template.
   * A not known policy template id is a failure.
   */
  def getPolicyInstances(ptId:UserPolicyTemplateId, includeSystem:Boolean = false) : Box[Seq[PolicyInstance]]
  
  /**
   * Save the given policy instance into given user policy template
   * If the policy instance is already present in the system but not
   * in the given category, raise an error.
   * If the policy instance is already in the given policy template,
   * update the policy instance.
   * If the policy instance is not in the system, add it.
   * 
   * System policy instance can't be saved with that method.
   *
   * Returned the saved UserPolicyInstance
   */
  def savePolicyInstance(inUserPolicyTemplateId:UserPolicyTemplateId,pi:PolicyInstance, actor:EventActor) : Box[Option[PolicyInstanceSaveDiff]]
 
  /**
   * Save the given system policy instance into given user policy template
   * If the policy instance is already present in the system but not
   * in the given category, raise an error.
   * If the policy instance is already in the given policy template,
   * update the policy instance.
   * If the policy instance is not in the system, add it.
   *
   * Non system policy instance can't be saved with that method.
   *
   * Returned the saved UserPolicyInstance
   */
  def saveSystemPolicyInstance(inUserPolicyTemplateId:UserPolicyTemplateId,pi:PolicyInstance, actor:EventActor) : Box[Option[PolicyInstanceSaveDiff]]

  /**
   * Get all policy instances defined in that repository
   */
  def getAll(includeSystem:Boolean = false) : Box[Seq[PolicyInstance]]

  /**
   * Delete a policy instance.
   * No dependency check are done, and so you will have to
   * delete dependent configuration rule (or other items) by
   * hand if you want.
   * 
   * If the given policyInstanceId does not exists, it leads to a
   * failure.
   *
   * System policy instance can't be deleted.
   */
  def delete(id:PolicyInstanceId, actor:EventActor) : Box[DeletePolicyInstanceDiff]

}