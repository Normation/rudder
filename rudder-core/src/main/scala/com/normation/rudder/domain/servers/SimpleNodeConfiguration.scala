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

import com.normation.rudder.domain.policies.IdentifiableCFCPI
import com.normation.cfclerk.domain.Variable
import com.normation.inventory.domain.AgentType
import com.normation.rudder._
import com.normation.rudder.domain._
import com.normation.rudder.domain.log._
import com.normation.cfclerk.domain.{PolicyPackageId, CFCPolicyInstanceId,CFCPolicyInstance, PoliciesContainer}
import com.normation.inventory.domain.AgentType
import com.normation.eventlog.EventLogNode
import org.joda.time.DateTime
import org.joda.time.format._
import scala.collection._
import scala.xml._
import net.liftweb.common.Loggable
import com.normation.rudder.domain.policies.{ConfigurationRule,ConfigurationRuleId}
import net.liftweb.common._
import com.normation.utils.HashcodeCaching

case class MinimalNodeConfig(
		name: String,
		hostname : String,
		agentsName : Seq[AgentType],
		policyServerId : String,
		localAdministratorAccountName : String
) extends HashcodeCaching

/**
 * The Server class, holding all relevant information about server configuration :
 * Policies
 * Variables

 * @author Nicolas CHARLES
 *
 */
case class SimpleNodeConfiguration(
    val id: String,
  val __currentPoliciesInstances : Seq[IdentifiableCFCPI] = Seq(), 
  val __targetPoliciesInstances : Seq[IdentifiableCFCPI] = Seq(),
  val isPolicyServer : Boolean = false,
  val currentMinimalNodeConfig : MinimalNodeConfig,
  val targetMinimalNodeConfig : MinimalNodeConfig,
  val writtenDate : Option[DateTime],
  val currentSystemVariables :  Map[String, Variable],
  val targetSystemVariables :  Map[String, Variable]
) extends NodeConfiguration with HashcodeCaching {
    
  
  /**
   * Add a policy instance
   * @param policy
   * @return
   */
  def addPolicyInstance(policy : IdentifiableCFCPI) : Box[SimpleNodeConfiguration]= {
    targetPoliciesInstances.get(policy.policyInstance.id) match {
      case None =>
        // we first need to fetch all the policies in a mutable map to modify them
      	val newPoliciesInstances =  mutable.Map[CFCPolicyInstanceId, IdentifiableCFCPI]() 
      	__targetPoliciesInstances.foreach { pi => 
      	  	newPoliciesInstances += ( pi.policyInstance.id ->pi.copy()) }
      	
      	
  
      	updateAllUniqueVariables(policy.policyInstance,newPoliciesInstances)
        newPoliciesInstances += (policy.policyInstance.id -> policy.copy())
        
        
        Full(copy(__targetPoliciesInstances = newPoliciesInstances.values.toSeq))
      case Some(x) => Failure("An instance of the policy with the same identifier %s already exists".format(policy.policyInstance.id.value))
    }
  }
 
  def setSerial(crs : Seq[(ConfigurationRuleId,Int)]) : SimpleNodeConfiguration = {
    val newPoliciesInstances =  mutable.Map[CFCPolicyInstanceId, IdentifiableCFCPI]() 
      	__targetPoliciesInstances.foreach { pi => 
      	  	newPoliciesInstances += ( pi.policyInstance.id ->pi.copy()) }
      	
    
    for ( (id,serial) <- crs) {
      newPoliciesInstances ++= newPoliciesInstances.
      			filter(x => x._2.configurationRuleId == id).
      			map(x => (x._1 -> new IdentifiableCFCPI(x._2.configurationRuleId, x._2.policyInstance.copy(serial = serial))))
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
        writtenDate = Some(new DateTime()))
    
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

