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

package com.normation.cfclerk.domain


import com.normation.cfclerk.exceptions.NotFoundException
import org.joda.time.DateTime
import org.joda.time.format._

import scala.xml._
import com.normation.utils.HashcodeCaching

import net.liftweb.common._

/**
 * Unique identifier for a CFClerk policy instance
 *
 */
case class Cf3PolicyDraftId(value: String) extends HashcodeCaching

/**
 * That policy instance object is an instance of a policy applied (bound)
 * to a particular node, so that its variable can be specialized given the node
 * context.
 *
 * That object is part of a node configuration and is the last abstraction used
 * before actual promises are generated.

 * Please note that a Directive should really have a Variable of the type TrackerVariable,
 * that will hold the id of the directive to be written in the template
 *
 */
final case class Cf3PolicyDraft(
    id              : Cf3PolicyDraftId
  , technique       : Technique
  , variableMap     : Map[String, Variable]
  , trackerVariable : TrackerVariable
  , priority        : Int
  , serial          : Int
  , modificationDate: DateTime = DateTime.now
) extends Loggable {

  /**
   * Return a map of all the non system variable
   * @return
   */
  val getNonSystemVariables: Map[String, Variable] = variableMap.filterNot(_._2.spec.isSystem)

  val getUniqueVariables: Map[String, Variable] = variableMap.filter( _._2.spec.isUniqueVariable)

  /**
   * Return a new Cf3PolicyDraft with a Variable in the list of expected variable
   * If the variable value is empty, then the variable will be removed from the map
   * @param variable : Two strings
   */
  def copyWithAddedVariable(variable: Variable) : Cf3PolicyDraft = {
    // check the the value is not null or empty
    if (variable.values.size == 0) {
      copyWithRemovedVariable(variable.spec.name)
    } else {
      copyWithSetVariable(variable)
    }
  }

  def copyWithRemovedVariable(key: String) : Cf3PolicyDraft = this.copy(variableMap = variableMap - key, modificationDate = DateTime.now)

  /**
   * Add (or update) a Variable in the list of expected variable
   * Return the new Cf3PolicyDraft
   * If the variable value is null or "", then the variable will NOT be removed from the map
   * (nothing is done)
   */
  def copyWithSetVariable(variable: Variable) : Cf3PolicyDraft = {
    copyWithSetVariables(Seq(variable))
  }

  /**
   * Add or update a list of variable. For each variable, if
   * - the variable is not already in current variable, it is added
   * - if the variable is already in the list, then its values are set the
   *   the seq of given value, **even if variable values is null or ""**
   * So with that method, we always have the size of the new variablesMap
   * greater than before the method is called.
   */
  def copyWithSetVariables(variables:Seq[Variable]) : Cf3PolicyDraft = {
    val newVariables = (for {
      variable <- variables
    } yield {
      variableMap.get(variable.spec.name) match {
        case None => Some(variable)
        case Some(values) => if(values == variable.values) None else Some(variable)
      }
    }).flatten.map(x => (x.spec.name, x)).toMap

    if(newVariables.isEmpty) {
      this
    } else {
      this.copy(
          modificationDate = DateTime.now
          //update variable, overriding existing one with the some name and different values
        , variableMap = variableMap ++ newVariables
      )
    }
  }


  def getVariable(key: String): Option[Variable] = variableMap.get(key)

  /**
   * Return a map of all the variable
   * @return
   */
  def getVariables(): Map[String, Variable] = variableMap


  /**
   * Update all unique variable of the Cf3PolicyDraft in the map
   * with the values of that one.
   * We don't add new unique variable, just update values
   * of existing ones.
   *
   * Return the map of Cf3PolicyDraft with updated variables.
   */
  def updateAllUniqueVariables(policies: Seq[Cf3PolicyDraft]) : Seq[Cf3PolicyDraft] = {
    /*
     * For each existing cf3PolicyDraft, we want to update only its existing
     * unique variable with the new values, not add new ones.
     */
    policies.map { policy =>
      policy.copyWithSetVariables(this.getUniqueVariables.collect {
        case(vid, v) if(policy.variableMap.keySet.contains(vid)) => v
      }.toSeq )
    }
  }


  /**
   * Update a policy based on another policy. It will check for var to remove and add, and update the time accordingly
   * Does not check for systemvar.
   *
   * So it actually return a Cf3PolicyDraft with the non-system variable of "other"
   * and an updated time if they are not the same
   *
   */
  def updateCf3PolicyDraft(other: Cf3PolicyDraft): Box[Cf3PolicyDraft] = {
    if (this.id != other.id) {
      Failure(s"Can not update variable of policy with id ${this.id} with variable with an other policy, but policy with id ${other.id} was given as parameter")
    } else {
      if(this.getNonSystemVariables == other.getNonSystemVariables) {
        Full(this)
      } else {
        val newVariables = this.variableMap.filter( _._2.spec.isSystem ) ++ other.getNonSystemVariables
        Full(this.copy(variableMap = newVariables, modificationDate = DateTime.now))
      }
    }
  }

  /**
   * Search in the variables of the policy for the TrackerVariable (that should be unique for the moment),
   * and retrieve it, along with bounded variable (or itself if it's bound to nothing)
   * Can throw a lot of exceptions if something fails
   */
  def getDirectiveVariable(): (TrackerVariable, Variable) = {
      trackerVariable.spec.boundingVariable match {
        case None | Some("") | Some(null) => (trackerVariable.copy(), trackerVariable.copy())
        case Some(value) =>
          variableMap.get(value) match {
            case None => throw new NotFoundException("No valid bounding found for trackerVariable " + trackerVariable.spec.name + " found in directive " + id)
            case Some(variable) => (trackerVariable.copy(), Variable.matchCopy(variable))
          }
      }
  }

}

