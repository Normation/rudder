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
import com.normation.cfclerk.domain.Technique



/**
 * The directive repository. 
 * 
 * directive are instance of technique
 * (a technique + values for its parameters)
 *
 */
trait DirectiveRepository {

  /**
   * Try to find the directive with the given ID.
   * Empty: no directive with such ID
   * Full((parent,directive)) : found the directive (directive.id == directiveId) in given parent
   * Failure => an error happened.
   */
  def getDirective(directiveId:DirectiveId) : Box[Directive]
  
  /**
   * retrieve a Directive with its parent Technique and the 
   * binding Active Technique
   */
  def getDirectiveWithContext(directiveId:DirectiveId) : Box[(Technique, ActiveTechnique, Directive)]

  
  /**
   * Find the active technique for which the given directive is an instance. 
   * 
   * Return empty if no such directive is known, 
   * fails if no active technique match the directive.
   */
  def getActiveTechnique(id:DirectiveId) : Box[ActiveTechnique]  
  
  /**
   * Find the active technique for which the given directive is an instance.
   *
   * Return empty if no such directive is known,
   * fails if no active technique match the directive.
   */
  def getActiveTechniqueAndDirective(id:DirectiveId) : Box[(ActiveTechnique, Directive)]

  /**
   * Get directives for given technique.
   * A not known technique id is a failure.
   */
  def getDirectives(activeTechniqueId:ActiveTechniqueId, includeSystem:Boolean = false) : Box[Seq[Directive]]
  
  /**
   * Save the given directive into given active technique
   * If the directive is already present in the system but not
   * in the given category, raise an error.
   * If the directive is already in the given technique,
   * update the directive.
   * If the directive is not in the system, add it.
   * 

   * Returned the saved UserDirective
   * System policy instance can't be saved with that method.
   *
   * Returned the saved Directive
   */
  def saveDirective(inActiveTechniqueId:ActiveTechniqueId,directive:Directive, actor:EventActor, reason:Option[String]) : Box[Option[DirectiveSaveDiff]]
 
  /**
   * Save the given system directive into given user technique
   * If the directive is already present in the system but not
   * in the given category, raise an error.
   * If the directive is already in the given technique,
   * update the directive.
   * If the directive is not in the system, add it.
   *
   * Non system directive can't be saved with that method.
   *
   * Returned the saved Directive
   */
  def saveSystemDirective(inActiveTechniqueId:ActiveTechniqueId,directive:Directive, actor:EventActor, reason:Option[String]) : Box[Option[DirectiveSaveDiff]]

  /**
   * Get all directives defined in that repository
   */
  def getAll(includeSystem:Boolean = false) : Box[Seq[Directive]]

  /**
   * Delete a directive.
   * No dependency check are done, and so you will have to
   * delete dependent rule (or other items) by
   * hand if you want.
   * 
   * If the given directiveId does not exists, it leads to a
   * failure.
   *
   * System policy instance can't be deleted.
   */
  def delete(id:DirectiveId, actor:EventActor, reason:Option[String]) : Box[DeleteDirectiveDiff]

}