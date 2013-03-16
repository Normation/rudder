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

package com.normation.rudder.domain.servers



import com.normation.rudder.domain.policies.RuleWithCf3PolicyDraft
import com.normation.cfclerk.domain.Variable
import net.liftweb.common._
import org.joda.time.DateTime
import com.normation.cfclerk.domain.Cf3PolicyDraft
import scala.collection.Map
import scala.collection.mutable.{Map => MutMap}
import com.normation.cfclerk.domain.{TechniqueId, Cf3PolicyDraftId,Cf3PolicyDraft, Cf3PolicyDraftContainer}
import com.normation.rudder.domain.policies.{Rule,RuleId}
import com.normation.rudder.domain.parameters.Parameter
import com.normation.cfclerk.domain.ParameterEntry
import com.normation.rudder.services.policies.ParameterForConfiguration


trait NodeConfiguration extends Loggable {

  def id                        : String
  def __currentRulePolicyDrafts : Seq[RuleWithCf3PolicyDraft]
  def __targetRulePolicyDrafts  : Seq[RuleWithCf3PolicyDraft]
  def isPolicyServer            : Boolean
  def currentMinimalNodeConfig  : MinimalNodeConfig
  def targetMinimalNodeConfig   : MinimalNodeConfig
  def writtenDate               : Option[DateTime]
  def currentSystemVariables    : Map[String, Variable]
  def targetSystemVariables     : Map[String, Variable]
  def currentParameters         : Set[ParameterForConfiguration]
  def targetParameters          : Set[ParameterForConfiguration]

  lazy val currentRulePolicyDrafts = __currentRulePolicyDrafts.map(ruleWithCf3PolicyDraft => ( ruleWithCf3PolicyDraft.cf3PolicyDraft.id -> ruleWithCf3PolicyDraft.copy()) ).toMap

  lazy val targetRulePolicyDrafts = __targetRulePolicyDrafts.map(ruleWithCf3PolicyDraft => ( ruleWithCf3PolicyDraft.cf3PolicyDraft.id -> ruleWithCf3PolicyDraft.copy()) ).toMap


  /**
   * Add a directive
   * @param policy
   * @return
   */
  def addDirective(policy : RuleWithCf3PolicyDraft) : Box[NodeConfiguration]

  def setSerial(rules : Seq[(RuleId,Int)]) : NodeConfiguration

  /**
   * Called when we have written the promises, to say that the current configuration is indeed the target one
   * @return the commmited NodeConfiguration
   */
  def commitModification() : NodeConfiguration

   /**
   * Called when we want to rollback modification, to say that the target configuration is the current one
   * @return the rolled back NodeConfiguration
   */
  def rollbackModification() : NodeConfiguration


  /**
   * Check if the node is modified and must be written
   * Modified is :
   * * Not the same number of policies
   * * Not the same ID of policies
   * * Serial different between two policies
   */
  def isModified : Boolean = {
    if(currentRulePolicyDrafts.size != targetRulePolicyDrafts.size) true
    else if (currentParameters !=  targetParameters) true
    else if(currentRulePolicyDrafts.keySet != targetRulePolicyDrafts.keySet) true
    else {
      for {
        (key,currentCFC) <- currentRulePolicyDrafts
      } {
        val targetPi = targetRulePolicyDrafts.getOrElse(key,return true)
        if(currentCFC.cf3PolicyDraft.serial != targetPi.cf3PolicyDraft.serial) return true
      }
      false
    }

  }

  /**
   * Go through each Cf3PolicyDraft and update the unique variable
   * it has a side effect on policies
   * @param policy
   */
  protected def updateAllUniqueVariables(policy : Cf3PolicyDraft,
              policies : MutMap[Cf3PolicyDraftId, RuleWithCf3PolicyDraft]) = {
    for (uniqueVariable <- policy.getVariables.filter(x => (x._2 .spec.isUniqueVariable ))) {
      // We need to update only the variable that already exists, not add them !!
      for (instance <- policies.filter(x => (x._2.cf3PolicyDraft.getVariable(uniqueVariable._1) != None ))) {
        instance._2.cf3PolicyDraft.setVariable(uniqueVariable._2)
      }
    }

  }


  def findDirectiveByTechnique(techniqueId : TechniqueId): Map[Cf3PolicyDraftId, RuleWithCf3PolicyDraft] = {
    targetRulePolicyDrafts.filter(x =>
      x._2.cf3PolicyDraft.techniqueId.name.value.equalsIgnoreCase(techniqueId.name.value) &&
      x._2.cf3PolicyDraft.techniqueId.version == techniqueId.version
    ).map(x => (x._1, x._2 .copy()))
  }

  def findCurrentDirectiveByTechnique(techniqueId : TechniqueId) = {
    currentRulePolicyDrafts.filter { x =>
      x._2.cf3PolicyDraft.techniqueId.name.value.equalsIgnoreCase(techniqueId.name.value) &&
      x._2.cf3PolicyDraft.techniqueId.version == techniqueId.version
    }.map(x => (x._1, x._2 .copy()))
  }

  def getAllPoliciesNames() :Set[TechniqueId] = {
    targetRulePolicyDrafts.map(x => x._2 .cf3PolicyDraft.techniqueId).toSet[TechniqueId]
  }


  def getCurrentDirectives() :  Map[Cf3PolicyDraftId, RuleWithCf3PolicyDraft] = {
    currentRulePolicyDrafts.map(x => (x._1, x._2.copy())).toMap
  }

  def getDirectives() : Map[Cf3PolicyDraftId, RuleWithCf3PolicyDraft] = {
    targetRulePolicyDrafts.map(x => (x._1, x._2.copy())).toMap
  }

  def getCurrentDirective(id : Cf3PolicyDraftId) : Option[RuleWithCf3PolicyDraft] = {
    currentRulePolicyDrafts.get(id).map(x => x.copy())
  }
  def getDirective(id : Cf3PolicyDraftId) : Option[RuleWithCf3PolicyDraft] = {
    targetRulePolicyDrafts.get(id).map(x => x.copy())
  }

  def getCurrentSystemVariables() : Map[String, Variable] = {
    currentSystemVariables.map(x => (x._1 -> Variable.matchCopy(x._2))).toMap
  }
  def getTargetSystemVariables() : Map[String, Variable] = {
    targetSystemVariables.map(x => (x._1 -> Variable.matchCopy(x._2))).toMap
  }

}



object NodeConfiguration {

  def toContainer(outPath : String, node : NodeConfiguration) : Cf3PolicyDraftContainer = {
    val container = new Cf3PolicyDraftContainer(
        outPath
      , node.targetParameters.map(x => ParameterEntry(x.name.value, x.value))
    )
    node.targetRulePolicyDrafts foreach (x =>  container.add(x._2.cf3PolicyDraft))
    container
  }


}