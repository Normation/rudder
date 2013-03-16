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
import com.normation.inventory.domain.AgentType
import com.normation.rudder._
import com.normation.rudder.domain._
import com.normation.rudder.domain.eventlog._
import com.normation.cfclerk.domain.{TechniqueId, Cf3PolicyDraftId, Cf3PolicyDraft, Cf3PolicyDraftContainer}
import com.normation.inventory.domain.AgentType
import org.joda.time.DateTime
import org.joda.time.format._
import scala.collection.{Seq,Map}
import scala.collection.mutable.{Map=>MutMap}
import scala.xml._
import net.liftweb.common.Loggable
import com.normation.rudder.domain.policies.{Rule,RuleId}
import net.liftweb.common._
import com.normation.utils.HashcodeCaching
import com.normation.rudder.domain.parameters.Parameter
import com.normation.rudder.services.policies.ParameterForConfiguration

case class MinimalNodeConfig(
    name                         : String
  , hostname                     : String
  , agentsName                   : Seq[AgentType]
  , policyServerId               : String
  , localAdministratorAccountName: String
) extends HashcodeCaching

/**
 * The Node class, holding all relevant information about server configuration :
 * Policies
 * Variables

 * @author Nicolas CHARLES
 *
 */
case class SimpleNodeConfiguration(
    id                         : String
  , __currentRulePolicyDrafts  : Seq[RuleWithCf3PolicyDraft] = Seq()
  , __targetRulePolicyDrafts   : Seq[RuleWithCf3PolicyDraft] = Seq()
  , isPolicyServer             : Boolean = false
  , currentMinimalNodeConfig   : MinimalNodeConfig
  , targetMinimalNodeConfig    : MinimalNodeConfig
  , writtenDate                : Option[DateTime]
  , currentSystemVariables     : Map[String, Variable]
  , targetSystemVariables      : Map[String, Variable]
  , currentParameters          : Set[ParameterForConfiguration]
  , targetParameters           : Set[ParameterForConfiguration]
) extends NodeConfiguration with HashcodeCaching {


  /**
   * Add a Directive
   * @param policy
   * @return
   */
  def addDirective(ruleWithCf3PolicyDraft : RuleWithCf3PolicyDraft) : Box[SimpleNodeConfiguration]= {
    targetRulePolicyDrafts.get(ruleWithCf3PolicyDraft.cf3PolicyDraft.id) match {
      case None =>
        // we first need to fetch all the policies in a mutable map to modify them
        val newRulePolicyDrafts =  MutMap[Cf3PolicyDraftId, RuleWithCf3PolicyDraft]()
        __targetRulePolicyDrafts.foreach { cf3 =>
            newRulePolicyDrafts += ( cf3.cf3PolicyDraft.id -> cf3.copy()) }

        updateAllUniqueVariables(ruleWithCf3PolicyDraft.cf3PolicyDraft,newRulePolicyDrafts)
        newRulePolicyDrafts += (ruleWithCf3PolicyDraft.cf3PolicyDraft.id -> ruleWithCf3PolicyDraft.copy())

        Full(copy(__targetRulePolicyDrafts = newRulePolicyDrafts.values.toSeq))
      case Some(x) => Failure("An instance of the policy with the same identifier %s already exists".format(ruleWithCf3PolicyDraft.cf3PolicyDraft.id.value))
    }
  }

  def setSerial(rules : Seq[(RuleId,Int)]) : SimpleNodeConfiguration = {
    val newRulePolicyDrafts =  MutMap[Cf3PolicyDraftId, RuleWithCf3PolicyDraft]()
        __targetRulePolicyDrafts.foreach { ruleWithCf3PolicyDraft =>
            newRulePolicyDrafts += ( ruleWithCf3PolicyDraft.cf3PolicyDraft.id ->ruleWithCf3PolicyDraft.copy()) }


    for ( (id,serial) <- rules) {
      newRulePolicyDrafts ++= newRulePolicyDrafts.
            filter(x => x._2.ruleId == id).
            map(x => (x._1 -> new RuleWithCf3PolicyDraft(x._2.ruleId, x._2.cf3PolicyDraft.copy(serial = serial))))
    }
    copy(__targetRulePolicyDrafts = newRulePolicyDrafts.values.toSeq)
  }

  /**
   * Called when we have written the promises, to say that the current configuration is indeed the target one
   * @return nothing
   */
  def commitModification() : SimpleNodeConfiguration = {
    logger.debug("Commiting server " + this);

    copy(__currentRulePolicyDrafts = this.__targetRulePolicyDrafts,
        currentMinimalNodeConfig = this.targetMinimalNodeConfig,
        currentSystemVariables = this.targetSystemVariables,
        currentParameters = this.targetParameters,
        writtenDate = Some(DateTime.now()))

  }

  /**
   * Called when we want to rollback modification, to say that the target configuration is the current one
   * @return nothing
   */
  def rollbackModification() : SimpleNodeConfiguration = {
    copy(__targetRulePolicyDrafts = this.__currentRulePolicyDrafts,
        targetMinimalNodeConfig = this.currentMinimalNodeConfig,
        targetSystemVariables = this.currentSystemVariables,
        targetParameters = this.currentParameters
        )

  }


  override def toString() = "%s %s".format(currentMinimalNodeConfig.name, id)

  override def clone() : NodeConfiguration = {
    val returnedMachine = new SimpleNodeConfiguration(
        this.id,
      currentRulePolicyDrafts.valuesIterator.map( x => x.copy()).toSeq,
      targetRulePolicyDrafts.valuesIterator.map( x => x.copy()).toSeq,
      isPolicyServer,
      currentMinimalNodeConfig,
      targetMinimalNodeConfig,
      writtenDate,
      currentSystemVariables,
      targetSystemVariables,
      currentParameters,
      targetParameters
    )
    returnedMachine
  }

}

