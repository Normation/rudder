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
import com.normation.rudder.domain.servers.NodeConfiguration
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.RuleWithCf3PolicyDraft
import com.normation.cfclerk.domain.Cf3PolicyDraftId
import com.normation.cfclerk.domain.Variable
import net.liftweb.common.Loggable
import com.normation.rudder.repository.ActiveTechniqueRepository
import net.liftweb.common.Box
import org.joda.time.DateTime
import com.normation.cfclerk.domain.TechniqueId
import net.liftweb.common.Full


/**
 * Detect modification in NodeConfiguration, and return the list of modified CR
 */
trait NodeConfigurationChangeDetectService {

  def detectChangeInNode(node : NodeConfiguration) : Set[RuleId]

  def detectChangeInNodes(nodes : Seq[NodeConfiguration]) : Seq[RuleId]

}


class NodeConfigurationChangeDetectServiceImpl(
    activeTechniqueRepository : ActiveTechniqueRepository) extends NodeConfigurationChangeDetectService with Loggable {

  /**
   * Return true if the variables are differents
   */
  private def compareVariablesValues(one : Variable, two : Variable) : Boolean = {
    return one.values.map(x => x.trim) != two.values.map(x => x.trim)
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
   * Fetch the acceptation date of a Technique
   */
  private def getAcceptationDate(TechniqueId : TechniqueId) : Box[DateTime] = {
    activeTechniqueRepository.getActiveTechnique(TechniqueId.name).map(x =>
      x.acceptationDatetimes(TechniqueId.version))

  }


  def detectChangeInNode(node : NodeConfiguration) : Set[RuleId] = {
    logger.info("Checking changes in node %s".format( node.id) )

    // First case : a change in the minimalnodeconfig is a change of all CRs
    if (node.currentMinimalNodeConfig != node.targetMinimalNodeConfig) {
      logger.trace("A change in the minimal configuration of node %s".format( node.id) )
      return node.getCurrentDirectives.map(x => x._2.ruleId).toSet ++ node.getDirectives.map(x => x._2.ruleId).toSet
    }

    // Second case : a change in the system variable is a change of all CRs
    if (detectChangeInSystemVar(node.getCurrentSystemVariables, node.getTargetSystemVariables)) {
      logger.trace("A change in the system variable node %s".format( node.id) )
      return node.getCurrentDirectives.map(x => x._2.ruleId).toSet ++ node.getDirectives.map(x => x._2.ruleId).toSet
    }

    val mySet = scala.collection.mutable.Set[RuleId]()

    val currents = node.getCurrentDirectives
    val targets  = node.getDirectives
    // Other case :
    // Added or modified directive
    for (target <- targets) {
      currents.get(target._1) match {
        case None =>  mySet += target._2.ruleId
        case Some(currentIdPi) =>
          // Check that the PI is in the same CR
          if (currentIdPi.ruleId != target._2.ruleId) {
            mySet += target._2.ruleId
            mySet += currentIdPi.ruleId
          } else {
            if (!currentIdPi.cf3PolicyDraft.equalsWithSameValues(target._2.cf3PolicyDraft)) {
              mySet += currentIdPi.ruleId
              // todo : check the date also
            }
          }
      }
    }

    // Removed PI
    for ((currentCFCId, currentIdentifiable) <- currents) {
      targets.get(currentCFCId) match {
        case None => mySet += currentIdentifiable.ruleId
        case Some(x) => // Nothing to do, it has been handled previously
      }
    }

    mySet ++= currents.filter(x => getAcceptationDate(x._2.cf3PolicyDraft.techniqueId) match {
      case Full(acceptationDate) =>
        node.writtenDate match {
          case Some(writtenDate) => acceptationDate.isAfter(writtenDate)
          case None => true
        }
      case _ => logger.warn("Could not find the acceptation date for policy package %s version %s".format(x._2.cf3PolicyDraft.techniqueId.name, x._2.cf3PolicyDraft.techniqueId.version.toString))
                false

    }).map(x => x._2.ruleId)

    mySet.toSet

  }


  def detectChangeInNodes(nodes : Seq[NodeConfiguration]) = {
    nodes.flatMap(node => detectChangeInNode(node)).toSet.toSeq
  }
}
