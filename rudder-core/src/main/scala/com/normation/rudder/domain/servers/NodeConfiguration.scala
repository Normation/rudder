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
import net.liftweb.common._
import org.joda.time.DateTime
import com.normation.cfclerk.domain.CFCPolicyInstance
import scala.collection._
import com.normation.cfclerk.domain.{PolicyPackageId, CFCPolicyInstanceId,CFCPolicyInstance, PoliciesContainer}
import com.normation.rudder.domain.policies.{ConfigurationRule,ConfigurationRuleId}


trait NodeConfiguration extends Loggable {

  def id: String
  def __currentPoliciesInstances : Seq[IdentifiableCFCPI]  
  def __targetPoliciesInstances : Seq[IdentifiableCFCPI] 
  def isPolicyServer : Boolean 
  def currentMinimalNodeConfig : MinimalNodeConfig
  def targetMinimalNodeConfig : MinimalNodeConfig
  def writtenDate : Option[DateTime]
  def currentSystemVariables :  Map[String, Variable]
  def targetSystemVariables :  Map[String, Variable]
  
  lazy val currentPoliciesInstances = __currentPoliciesInstances.map(pi => ( pi.policyInstance.id -> pi.copy()) ).toMap  
    
  lazy val targetPoliciesInstances = __targetPoliciesInstances.map(pi => ( pi.policyInstance.id -> pi.copy()) ).toMap
  
  
  /**
   * Add a policy instance
   * @param policy
   * @return
   */
  def addPolicyInstance(policy : IdentifiableCFCPI) : Box[NodeConfiguration]
  
  def setSerial(crs : Seq[(ConfigurationRuleId,Int)]) : NodeConfiguration
  
  /**
   * Called when we have written the promesses, to say that the current configuration is indeed the target one
   * @return nothing
   */
  def commitModification() : NodeConfiguration
  
   /**
   * Called when we want to rollback modification, to say that the target configuration is the current one
   * @return nothing
   */
  def rollbackModification() : NodeConfiguration
  
  
  /**
   * Check if the server is modified and must be written
   * Modified is : 
   * * Not the same number of policies
   * * Not the same ID of policies
   * * Serial different between two policies 
   */
  def isModified : Boolean = {
    if(currentPoliciesInstances.size != targetPoliciesInstances.size) true
    else if(currentPoliciesInstances.keySet != targetPoliciesInstances.keySet) true
    else {
      for { 
        (key,currentCFC) <- currentPoliciesInstances
      } {
        val targetPi = targetPoliciesInstances.getOrElse(key,return true)
        if(currentCFC.policyInstance.serial != targetPi.policyInstance.serial) return true
      }
      false
    }
    
  }
  
  /**
   * Go through each CFCPolicyInstance and update the unique variable
   * it has a side effect on policies
   * @param policy
   */
  protected def updateAllUniqueVariables(policy : CFCPolicyInstance,
      				policies : mutable.Map[CFCPolicyInstanceId, IdentifiableCFCPI]) = {
    for (uniqueVariable <- policy.getVariables.filter(x => (x._2 .spec.isUniqueVariable ))) {
      // We need to update only the variable that already exists, not add them !!
      for (instance <- policies.filter(x => (x._2.policyInstance.getVariable(uniqueVariable._1) != None ))) {
        instance._2.policyInstance.setVariable(uniqueVariable._2)
      }
    }
    
  }
  
 
  
  
  
  
  def findPolicyInstanceByPolicy(policyId : PolicyPackageId): Map[CFCPolicyInstanceId, IdentifiableCFCPI] = {
    targetPoliciesInstances.filter(x => 
      x._2.policyInstance.policyId.name.value.equalsIgnoreCase(policyId.name.value) &&
      x._2.policyInstance.policyId.version == policyId.version
    ).map(x => (x._1, x._2 .copy()))
  }
  
  def findCurrentPolicyInstanceByPolicy(policyId : PolicyPackageId) = {
    currentPoliciesInstances.filter { x => 
      x._2.policyInstance.policyId.name.value.equalsIgnoreCase(policyId.name.value) &&
      x._2.policyInstance.policyId.version == policyId.version
    }.map(x => (x._1, x._2 .copy()))
  }
  
  def getAllPoliciesNames() :Set[PolicyPackageId] = {
    targetPoliciesInstances.map(x => x._2 .policyInstance.policyId).toSet[PolicyPackageId]
  }
  
  
  def getCurrentPolicyInstances() :  immutable.Map[CFCPolicyInstanceId, IdentifiableCFCPI] = {
    currentPoliciesInstances.map(x => (x._1, x._2.copy())).toMap
  }
  
  def getPolicyInstances() : immutable.Map[CFCPolicyInstanceId, IdentifiableCFCPI] = {
    targetPoliciesInstances.map(x => (x._1, x._2.copy())).toMap
  }
  
  def getCurrentPolicyInstance(id : CFCPolicyInstanceId) : Option[IdentifiableCFCPI] = {
    currentPoliciesInstances.get(id).map(x => x.copy())
  }
  def getPolicyInstance(id : CFCPolicyInstanceId) : Option[IdentifiableCFCPI] = {
    targetPoliciesInstances.get(id).map(x => x.copy())
  }
  
  def getCurrentSystemVariables() : immutable.Map[String, Variable] = {
  	currentSystemVariables.map(x => (x._1 -> Variable.matchCopy(x._2))).toMap
  }
  def getTargetSystemVariables() : immutable.Map[String, Variable] = {
  	targetSystemVariables.map(x => (x._1 -> Variable.matchCopy(x._2))).toMap
  }
 
} 



object NodeConfiguration {
  
  def toContainer(outPath : String, server : NodeConfiguration) : PoliciesContainer = {
    val container = new PoliciesContainer(outPath)
    server.targetPoliciesInstances foreach (x =>  container.addPolicyInstance(x._2.policyInstance))
    container
  }
  
  
}