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

import com.normation.rudder.domain.policies.RuleTarget
import com.normation.rudder.domain.policies._
import net.liftweb.common._
import com.normation.eventlog.EventActor
import com.normation.rudder.domain.archives.RuleArchiveId
import com.normation.eventlog.ModificationId



/**
 * The directive repository.
 *
 * directive are instance of technique
 * (a technique + values for its parameters)
 *
 */
trait RoRuleRepository {

  /**
   * Try to find the rule with the given ID.
   * Empty: no directive with such ID
   * Full((parent,directive)) : found the directive (directive.id == directiveId) in given parent
   * Failure => an error happened.
   */
  def get(ruleId:RuleId) : Box[Rule]

  def getAll(includeSytem:Boolean = false) : Box[Seq[Rule]]

  /**
   * Return all activated rule.
   * A rule is activated if
   * - its attribute "isEnabled" is set to true ;
   * - its referenced group is Activated ;
   * - its referenced directive is activated (what means that the
   *   referenced active technique is activated)
   * @return
   */
  def getAllEnabled() : Box[Seq[Rule]]

}


trait WoRuleRepository {
  /**
   * Save the given directive into given active technique
   * If a directive with the same ID is already present in the
   * system, raise an error.
   * If the directive is not in the system, add it.
   *
   * Returned the saved Rule
   *
   * NOTE: only save here, deploy is done in the DeploymentService
   *
   * NOTE: some parameter may be forced to a value different from the
   * one provided (typically, serial will be set to 0 whatever it's value
   * is). It is the responsability of the user to check that if he wants
   * with the provided resulting rule.
   *
   */
  def create(rule:Rule, modId: ModificationId, actor:EventActor, reason:Option[String]) : Box[AddRuleDiff]

  /**
   * Update the rule with the given ID with the given
   * parameters.
   *
   * If the rule is not in the repos, the method fails.
   * If the rule is a system one, the methods fails.
   * NOTE: the serial is *never* updated with that methods.
   */
  def update(rule:Rule, modId: ModificationId, actor:EventActor, reason:Option[String]) : Box[Option[ModifyRuleDiff]]


  /**
   * Update the system configuration rule with the given ID with the given
   * parameters.
   *
   * NOTE: the serial is *never* updated with that methods.
   */
  def updateSystem(rule:Rule, modId: ModificationId, actor:EventActor, reason:Option[String]) : Box[Option[ModifyRuleDiff]]

  /**
   * Increment the serial of rules with given ID by one.
   * Return the new serial value.
   * The method fails if no rule has such ID.
   */
  def incrementSerial(id:RuleId) : Box[Int]

  /**
   * Delete the rule with the given ID.
   * If no rule with such ID exists, it is an error
   * (it's the caller site responsability to decide if it's
   * and error or not).
   * A system rule can not be deleted.
   */
  def delete(id:RuleId, modId: ModificationId, actor:EventActor, reason:Option[String]) : Box[DeleteRuleDiff]

  /**
   * A (dangerous) method that replace all existing rules
   * by the list given in parameter.
   * If succeed, return an identifier of the place were
   * are stored the old rules - it is the
   * responsibility of the user to delete them.
   *
   * Most of the time, we don't want to change system rules.
   * So when "includeSystem" is false (default), swapRules
   * implementation have to take care to ignore any configuration (both in
   * newCr or in archive).
   *
   * Note: a really really special care have to be taken with serial IDs:
   * - for CR which exists in both imported and existing referential, the
   *   serial ID MUST be updated (+1)
   * - for all other imported CR, the serial MUST be set to 0
   */
  def swapRules(newRules:Seq[Rule], includeSystem:Boolean = false) : Box[RuleArchiveId]

  /**
   * Delete a set of saved rules.
   */
  def deleteSavedRuleArchiveId(saveId:RuleArchiveId) : Box[Unit]
}