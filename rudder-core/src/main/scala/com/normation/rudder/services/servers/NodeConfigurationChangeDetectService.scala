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

package com.normation.rudder.services.servers

import org.joda.time.DateTime

import com.normation.cfclerk.domain.Cf3PolicyDraftId
import com.normation.cfclerk.domain.TechniqueId
import com.normation.cfclerk.domain.Variable
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.RuleWithCf3PolicyDraft
import com.normation.rudder.domain.servers.NodeConfiguration
import com.normation.rudder.repository.FullActiveTechniqueCategory
import com.normation.rudder.repository.RoDirectiveRepository
import net.liftweb.common.Loggable
import net.liftweb.common.Box
import org.joda.time.DateTime
import com.normation.cfclerk.domain.TechniqueId
import net.liftweb.common.Full
import com.normation.rudder.domain.parameters.Parameter
import com.normation.rudder.domain.parameters.GlobalParameter
import com.normation.rudder.services.policies.ParameterForConfiguration


/**
 * Detect modification in NodeConfiguration, and return the list of modified CR
 */
trait NodeConfigurationChangeDetectService {

  def detectChangeInNode(node : NodeConfiguration, directiveLib: FullActiveTechniqueCategory) : Set[RuleId]

  def detectChangeInNodes(nodes : Seq[NodeConfiguration], directiveLib: FullActiveTechniqueCategory) : Set[RuleId] = {
    nodes.flatMap(node => detectChangeInNode(node, directiveLib)).toSet
  }

}


class NodeConfigurationChangeDetectServiceImpl() extends NodeConfigurationChangeDetectService with Loggable {



  /**
   * Return true if the variables are differents
   */
  private def compareVariablesValues(one : Variable, two : Variable) : Boolean = {
    one.values.map(x => x.trim) != two.values.map(x => x.trim)
  }

  /**
   * Check that the two map contains the same values
   * Note : an empty variable and a non existent variable are equals, by convention
   *
   */
  private def detectChangeInSystemVar(currentSystemVariables : Map[String, Variable],
      targetSystemVariables : Map[String, Variable]) : Boolean = {
    val current = currentSystemVariables.filter(x => x._2.values.size > 0)
    val target =  targetSystemVariables.filter(x => x._2.values.size > 0)
    // now checking the system variables
    if (current.size != target.size) true
    else if(current.keySet != target.keySet) true
    else {
      for ( (key, value )<- current ) {

        if (compareVariablesValues(target.getOrElse(key, return true), value)) {
          val mytarget  = target.get(key).get
          return true
        }
      }
      false
    }
  }

  /**
   * Checks if two sets of parameters are identical, but doesn't care for
   * the description or the overridable status
   */
  private def detectChangeInParameters(
      currentParameters : Set[ParameterForConfiguration]
    , targetParameters  : Set[ParameterForConfiguration]) : Boolean = {
    currentParameters != targetParameters
  }

  override def detectChangeInNode(node : NodeConfiguration, directiveLib: FullActiveTechniqueCategory) : Set[RuleId] = {
    logger.debug("Checking changes in node %s".format( node.id) )

    // First case : a change in the minimalnodeconfig is a change of all CRs
    if (node.currentMinimalNodeConfig != node.targetMinimalNodeConfig) {
      logger.trace("A change in the minimal configuration of node %s".format( node.id) )
      node.currentRulePolicyDrafts.map(_.ruleId).toSet ++ node.targetRulePolicyDrafts.map(_.ruleId).toSet

    // Second case : a change in the system variable is a change of all CRs
    } else if (detectChangeInSystemVar(node.currentSystemVariables, node.targetSystemVariables)) {
      logger.trace("A change in the system variable node %s".format( node.id) )
      node.currentRulePolicyDrafts.map(_.ruleId).toSet ++ node.targetRulePolicyDrafts.map(_.ruleId).toSet

    // Third case : a change in the parameters is a change of all CRs
    } else if (detectChangeInParameters(node.currentParameters, node.targetParameters)) {
      logger.trace("A change in the parameters for node %s".format( node.id ) )
      node.currentRulePolicyDrafts.map(_.ruleId).toSet ++ node.targetRulePolicyDrafts.map(_.ruleId).toSet
    } else {

      val mySet = scala.collection.mutable.Set[RuleId]()

      val currents = node.currentRulePolicyDrafts
      val targets  = node.targetRulePolicyDrafts
      // Other case :
      // Added or modified directive
      for (target <- targets) {
        currents.find( _.draftId == target.draftId) match {
          case None =>
            mySet += target.ruleId
          case Some(currentIdPi) =>
            // Check that the PI is in the same CR
            if (currentIdPi.ruleId != target.ruleId) {
              mySet += target.ruleId
              mySet += currentIdPi.ruleId
            } else {
              if (!currentIdPi.cf3PolicyDraft.equalsWithSameValues(target.cf3PolicyDraft)) {
                mySet += currentIdPi.ruleId
                // todo : check the date also
              }
            }
        }
      }

      // Removed PI
      for (currentIdentifiable <- currents) {
        targets.find( _.draftId == currentIdentifiable.draftId) match {
          case None => mySet += currentIdentifiable.ruleId
          case Some(x) => // Nothing to do, it has been handled previously
        }
      }

      mySet ++= currents.filter(x => directiveLib.allTechniques.get(x.cf3PolicyDraft.technique.id) match {
        case Some((_, Some(acceptationDate))) =>
          node.writtenDate match {
            case Some(writtenDate) => acceptationDate.isAfter(writtenDate)
            case None => true
          }
        case _ =>
          logger.warn("Could not find the acceptation date for policy package %s version %s".format(x.cf3PolicyDraft.technique.id.name, x.cf3PolicyDraft.technique.id.version.toString))
          false

      }).map(_.ruleId)

      mySet.toSet
    }
  }
}
