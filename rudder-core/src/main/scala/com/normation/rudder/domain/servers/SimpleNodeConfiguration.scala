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
import com.normation.rudder.domain.log._
import com.normation.cfclerk.domain.{TechniqueId, Cf3PolicyDraftId, Cf3PolicyDraft, Cf3PolicyDraftContainer}
import com.normation.inventory.domain.AgentType
import com.normation.eventlog.EventLogNode
import org.joda.time.DateTime
import org.joda.time.format._
import scala.collection._
import scala.xml._
import net.liftweb.common.Loggable
import com.normation.rudder.domain.policies.{Rule,RuleId}
import net.liftweb.common._
import com.normation.utils.HashcodeCaching

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
  , __currentPoliciesInstances : Seq[RuleWithCf3PolicyDraft] = Seq()
  , __targetPoliciesInstances  : Seq[RuleWithCf3PolicyDraft] = Seq()
  , isPolicyServer             : Boolean = false
  , currentMinimalNodeConfig   : MinimalNodeConfig
  , targetMinimalNodeConfig    : MinimalNodeConfig
  , writtenDate                : Option[DateTime]
  , currentSystemVariables     : Map[String, Variable]
  , targetSystemVariables      : Map[String, Variable]
) extends NodeConfiguration with HashcodeCaching {
    
  
  /**
   * Add a policy instance
   * @param policy
   * @return
   */
  def addDirective(ruleWithCf3PolicyDraft : RuleWithCf3PolicyDraft) : Box[SimpleNodeConfiguration]= {
    targetPoliciesInstances.get(ruleWithCf3PolicyDraft.cf3PolicyDraft.id) match {
      case None =>
        // we first need to fetch all the policies in a mutable map to modify them
        val newPoliciesInstances =  mutable.Map[Cf3PolicyDraftId, RuleWithCf3PolicyDraft]() 
        __targetPoliciesInstances.foreach { cf3 => 
            newPoliciesInstances += ( cf3.cf3PolicyDraft.id -> cf3.copy()) }
  
        updateAllUniqueVariables(ruleWithCf3PolicyDraft.cf3PolicyDraft,newPoliciesInstances)
        newPoliciesInstances += (ruleWithCf3PolicyDraft.cf3PolicyDraft.id -> ruleWithCf3PolicyDraft.copy())
        
        Full(copy(__targetPoliciesInstances = newPoliciesInstances.values.toSeq))
      case Some(x) => Failure("An instance of the policy with the same identifier %s already exists".format(ruleWithCf3PolicyDraft.cf3PolicyDraft.id.value))
    }
  }
 
  def setSerial(rules : Seq[(RuleId,Int)]) : SimpleNodeConfiguration = {
    val newPoliciesInstances =  mutable.Map[Cf3PolicyDraftId, RuleWithCf3PolicyDraft]() 
        __targetPoliciesInstances.foreach { ruleWithCf3PolicyDraft => 
            newPoliciesInstances += ( ruleWithCf3PolicyDraft.cf3PolicyDraft.id ->ruleWithCf3PolicyDraft.copy()) }
        
    
    for ( (id,serial) <- rules) {
      newPoliciesInstances ++= newPoliciesInstances.
            filter(x => x._2.ruleId == id).
            map(x => (x._1 -> new RuleWithCf3PolicyDraft(x._2.ruleId, x._2.cf3PolicyDraft.copy(serial = serial))))
    }
    copy(__targetPoliciesInstances = newPoliciesInstances.values.toSeq)
  }
  
  /**
   * Called when we have written the promesses, to say that the current configuration is indeed the target one
   * @return nothing
   */
  def commitModification() : SimpleNodeConfiguration = {
    logger.debug("Commiting server " + this);
    
    copy(__currentPoliciesInstances = this.__targetPoliciesInstances,
        currentMinimalNodeConfig = this.targetMinimalNodeConfig,
        currentSystemVariables = this.targetSystemVariables,
        writtenDate = Some(DateTime.now()))
    
  }
  
  /**
   * Called when we want to rollback modification, to say that the target configuration is the current one
   * @return nothing
   */
  def rollbackModification() : SimpleNodeConfiguration = {
    copy(__targetPoliciesInstances = this.__currentPoliciesInstances,
        targetMinimalNodeConfig = this.currentMinimalNodeConfig,
        targetSystemVariables = this.currentSystemVariables 
        )
    
  }
 
  /*
  def setCurrentSystemVariables(map : immutable.Map[String, Variable])  = {
    currentSystemVariables.clear 
    currentSystemVariables ++= map.map(x => (x._1 -> Variable.matchCopy(x._2)))
  }
  def setTargetSystemVariables(map : immutable.Map[String, Variable])  = {
    targetSystemVariables.clear 
    targetSystemVariables ++= map.map(x => (x._1 -> Variable.matchCopy(x._2)))
  }
  */
  
    

  override def toString() = "%s %s".format(currentMinimalNodeConfig.name, id)
  
  

    
  
  

  override def clone() : NodeConfiguration = {
    val returnedMachine = new SimpleNodeConfiguration(
        this.id,
      currentPoliciesInstances.valuesIterator.map( x => x.copy()).toSeq,
      targetPoliciesInstances.valuesIterator.map( x => x.copy()).toSeq,
      isPolicyServer,
      currentMinimalNodeConfig,
      targetMinimalNodeConfig,
      writtenDate,
      currentSystemVariables,
      targetSystemVariables
    ) 
    returnedMachine
  }
  
}

