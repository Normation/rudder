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

import com.normation.rudder.domain.policies.PolicyInstanceTarget
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
trait ConfigurationRuleRepository {

  /**
   * Try to find the configuration rule with the given ID.
   * Empty: no policy instance with such ID
   * Full((parent,pi)) : found the policy instance (pi.id == piId) in given parent
   * Failure => an error happened.
   */
  def get(crId:ConfigurationRuleId) : Box[ConfigurationRule]

  /**
   * Save the given policy instance into given user policy template
   * If a policy instance with the same ID is already present in the 
   * system, raise an error.
   * If the policy instance is not in the system, add it.
   * 
   * Returned the saved ConfigurationRule
   * 
   * NOTE: only save here, deploy is done in the DeploymentService
   * 
   * NOTE: some parameter may be forced to a value different from the
   * one provided (typically, serial will be set to 0 whatever it's value
   * is). It is the responsability of the user to check that if he wants
   * with the provided resulting configuration rule. 
   * 
   */  
  def create(cr:ConfigurationRule, actor:EventActor) : Box[AddConfigurationRuleDiff]

  /**
   * Update the configuration rule with the given ID with the given 
   * parameters.
   * 
   * If the configuration rule is not in the repos, the method fails. 
   * If the configuration rule is a system one, the methods fails.
   * 
   * NOTE: the serial is *never* updated with that methods. 
   */
  def update(cr:ConfigurationRule, actor:EventActor) : Box[Option[ModifyConfigurationRuleDiff]]
  
  /**
   * Update the system configuration rule with the given ID with the given
   * parameters.
   *
   * NOTE: the serial is *never* updated with that methods.
   */
  def updateSystem(cr:ConfigurationRule, actor:EventActor) : Box[Option[ModifyConfigurationRuleDiff]]

  /**
   * Increment the serial of Configuration Rules with given ID by one. 
   * Return the new serial value. 
   * The method fails if no configuration rule has such ID. 
   */
  def incrementSerial(id:ConfigurationRuleId) : Box[Int]
  
  /**
   * Delete the configuration rule with the given ID. 
   * If no configuration rule with such ID exists, it is an error
   * (it's the caller site responsability to decide if it's
   * and error or not). 
   *
   * A system rule can not be deleted.
   */
  def delete(id:ConfigurationRuleId, actor:EventActor) : Box[DeleteConfigurationRuleDiff]
  
  def getAll(includeSytem:Boolean = false) : Box[Seq[ConfigurationRule]] 
  
  /**
   * Return all activated configuration rule.
   * A configuration rule is activated if 
   * - its attribute "isActivated" is set to true ;
   * - its referenced group is Activated ;
   * - its referenced policy instance is activated (what means that the 
   *   referenced user policy template is activated)
   * @return
   */
  def getAllActivated() : Box[Seq[ConfigurationRule]] 
  
}