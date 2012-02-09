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

package com.normation.rudder.services.log

import net.liftweb.common._
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.policies.PolicyInstance
import com.normation.rudder.domain.policies.ConfigurationRuleVal
import com.normation.rudder.domain.policies.ConfigurationRule
import com.normation.rudder.repository.HistorizationRepository
import com.normation.rudder.repository.NodeGroupRepository
import com.normation.rudder.repository.PolicyInstanceRepository
import com.normation.cfclerk.services.PolicyPackageService
import com.normation.cfclerk.domain.PolicyPackageId
import com.normation.rudder.repository.ConfigurationRuleRepository
import com.normation.rudder.services.nodes.NodeInfoService

/**
 * At each deployment, we compare the content of the groups/PI/CR in the ldap with the content
 * in the database
 * If there is a change (serial updated, name changed, size of group modification, etc)
 * we close the previous entry, and add a new one
 * 
 */
trait HistorizationService {

  def updateNodes() : Box[Unit]
  
  /**
   * Update the groups, based on what is on the ldap, and return nothing (I don't know yet how to know what has been updated)
   */

  def updateGroups() : Box[Unit]

  /**
   * Update the policy details, based on what is on the ldap and what is on the file system.
   * A policy instance changed when it is deleted, renamed, changed version, changed priority,
   * it's underlying PT changed name, description 
   */
  def updatePINames() : Box[Unit]
  
  def updatesConfigurationRuleNames() : Box[Unit]

}


class HistorizationServiceImpl(
    historizationRepository : HistorizationRepository,
    nodeInfoService : NodeInfoService,
    nodeGroupRepository : NodeGroupRepository,
    policyInstanceRepository : PolicyInstanceRepository,
    policyPackageService : PolicyPackageService,
    configurationRuleRepository : ConfigurationRuleRepository) extends HistorizationService with  Loggable {
  
  
  def updateNodes() : Box[Unit] = {
    try {
    // Fetch all nodeinfo from the ldap 
      val ids = nodeInfoService.getAllUserNodeIds().openOr({logger.error("Could not fetch nodeids"); Seq()})
            
      val nodeInfos = nodeInfoService.find(ids).openOr(
          {logger.error("Could not fetch groups "); Seq()} )
      
      // fetch all the current nodes in the jdbc
      val registered = historizationRepository.getAllOpenedNodes().map(x => x.nodeId -> x).toMap
      
      // detect changes 
      val changed = nodeInfos.filter(x => registered.get(x.id.value) match {
        case None => true
        case Some(entry) => (entry.nodeName != x.hostname || entry.nodeDescription != x.description )
      })
      
      // a node closable is a node that is current in the database, but don't exist in the
      // ldap
      val closable = registered.keySet.filter(x => !(nodeInfos.map(node => node.id.value)).contains(x)) 
     
      historizationRepository.updateNodes(changed, closable.toSeq)
      Full(Unit)
    } catch {
      case e:Exception => logger.error("Could not update the nodes. Reason : "+e.getMessage())
                          Failure("Could not update the nodes. Reason : "+e.getMessage())
    }
  }
    
  def updateGroups() : Box[Unit] = {
    try {
    // Fetch all groups from the ldap 
      val nodeGroups = nodeGroupRepository.getAll().openOr(
          {logger.error("Could not fetch groups "); Seq()} )
      
      // fetch all the current group in the ldap
      val registered = historizationRepository.getAllOpenedGroups().map(x => x.groupId -> x).toMap
      
      // detect changes 
      val changed = nodeGroups.filter(x => registered.get(x.id.value) match {
        case None => true
        case Some(entry) => (entry.groupName != x.name || entry.groupDescription != x.description || entry.nodeCount != x.serverList.size)
      })
      
      // a group closable is a group that is current in the database, but don't exist in the
      // ldap
      val closable = registered.keySet.filter(x => !(nodeGroups.map(group => group.id.value)).contains(x)) 
     
      historizationRepository.updateGroups(changed, closable.toSeq)
      Full(Unit)
    } catch {
      case e:Exception => logger.error("Could not update the groups. Reason : "+e.getMessage())
                          Failure("Could not update the groups. Reason : "+e.getMessage())
    }
  }
  
  def updatePINames() : Box[Unit] = {
    try {
    // sorry for this piece of nightmare
    // I don't want it to fail on PT deleted
    // Fetch a triplet policyinstance, userPT, policypackage from the ldap/filesystem
    // starting by the policy instances, then search for the userPT (which can fails)
    // then look on the file system for the matching policypackagename/policyversion
    // againt, it should not fail (but report an error nonetheless)
      val policyInstances = policyInstanceRepository.getAll().openOr(Seq()).map(x => 
            (x, policyInstanceRepository.getUserPolicyTemplate(x.id)))
              .filter { case (pi, userPt) => 
                          userPt match {
                              case Full(userPT) => true
                              case _ => logger.error("Could not find matching PT for PI %s".format(pi.id.value))
                                    false
                          }
              }.
              map(x =>( x._1 -> x._2.openTheBox)). // shoud not fail if a PT is deleted
              map { case (pi, userPT) => 
                (pi, userPT, policyPackageService.getPolicy(new PolicyPackageId(userPT.referencePolicyTemplateName, pi.policyTemplateVersion)))
              }.filter { case (pi, userPt, policyPackage) => 
                          policyPackage match {
                              case Some(pp) => true
                              case _ => logger.error("Could not find matching PolicyPackage for PI %s".format(pi.id.value))
                                    false
                          }
              }.map(x =>( x._1 , x._2 , x._3.get))
              
              
              
      val registered = historizationRepository.getAllOpenedPIs().map(x => x.policyInstanceId -> x).toMap
      
      
      val changed = policyInstances.filter { case (pi, userPT, policyPackage) => 
          registered.get(pi.id.value) match {
              case None => true
              case Some(entry) => 
                     (entry.policyInstanceName != pi.name || 
                  entry.policyInstanceDescription != pi.shortDescription || 
                  entry.priority != pi.priority ||
                  entry.policyTemplateHumanName != policyPackage.name ||
                  entry.policyPackageName != userPT.referencePolicyTemplateName.value ||
                  entry.policyPackageDescription != policyPackage.description ||
                  entry.policyPackageVersion != pi.policyTemplateVersion.toString )
                
         }
     }
     
     val closable = registered.keySet.filter(x => !(policyInstances.map(pi => pi._1.id.value)).contains(x))
  
     historizationRepository.updatePIs(changed, closable.toSeq)
     Full(Unit)
    } catch {
      case e:Exception => logger.error("Could not update the pis. Reason : "+e.getMessage())
                          Failure("Could not update the pis. Reason : "+e.getMessage())
    }
  }
  
  def updatesConfigurationRuleNames() : Box[Unit] = {
    try {
      val crs = configurationRuleRepository.getAll().openOr({
          logger.error("Could not fetch all CRs");
          Seq()})
          
      val registered = historizationRepository.getAllOpenedCRs().map(x => x.id -> x).toMap
   
      
      val changed = crs.filter(cr => registered.get(cr.id) match {
          case None => true
          case Some(entry) =>
            !isEqual(entry, cr)
      })
      
      // a closable cr is a cr that is in the database, but not in the ldap
      val closable = registered.keySet.filter(x => !(crs.map(cr => cr.id)).contains(x)).
                    map(x => x.value)
  
      historizationRepository.updateCrs(changed, closable.toSeq)
      Full(Unit)
    } catch {
      case e:Exception => logger.error("Could not update the crs. Reason : "+e.getMessage())
                          Failure("Could not update the crs. Reason : "+e.getMessage())
    } 
  }
    

  private def isEqual(entry : ConfigurationRule, cr : ConfigurationRule) : Boolean = {
    (entry.name == cr.name &&
                entry.shortDescription == cr.shortDescription &&
                entry.longDescription == cr.longDescription &&
                entry.isActivatedStatus == cr.isActivatedStatus &&
                (
                    entry.target == cr.target // TODO : this won't do with multi target
                ) && 
                (
                    entry.policyInstanceIds == cr.policyInstanceIds 
                )
                
    )
  }
   
}
