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
import com.normation.rudder.repository.jdbc.SerializedGroups
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.repository.jdbc.SerializedPIs
import com.normation.rudder.domain.policies.PolicyInstance
import com.normation.rudder.domain.policies.UserPolicyTemplate
import com.normation.cfclerk.domain.PolicyPackage
import com.normation.rudder.domain.policies.ConfigurationRule
import com.normation.rudder.repository.jdbc.SerializedCRPIs
import com.normation.rudder.repository.jdbc.SerializedCRGroups
import com.normation.rudder.repository.jdbc.SerializedCRs
import org.joda.time.DateTime
import com.normation.rudder.repository.jdbc.SerializedNodes
import com.normation.rudder.domain.nodes.NodeInfo

/**
 * Repository to retrieve information about Nodes, Groups, Policy Instances, Configuration Rules
 * An item is said to be opened when it doesn't have an endTime, meaning it
 * is the current version of the item (as reflected in the ldap)
 */
trait HistorizationRepository {

  /**
   * Return all the nodes that are still "opened"
   */
  def getAllOpenedNodes() : Seq[SerializedNodes]
  
  /**
   * Return all nodes that have been updated or created after a specific time (optionnal),
   * If fetchUnclosed is set to true, it will also return the opened nodes, regardless of their
   * opening time
   */
  def getAllNodes(after : Option[DateTime], fetchUnclosed : Boolean = false) : Seq[SerializedNodes]
  
  /**
   * Update a list of nodes, and close (end) another list, based on their id
   * Updating is really only setting now as a endTime for nodes, and creating them after
   */
  def updateNodes(nodes : Seq[NodeInfo], closable : Seq[String]) :Seq[SerializedNodes]
  
  /**
   * Return all groups that have been updated or created after a specific time (optionnal),
   * If fetchUnclosed is set to true, it will also return the opened group, regardless of their
   * opening time
   */
  def getAllGroups(after : Option[DateTime], fetchUnclosed : Boolean = false) : Seq[SerializedGroups]
  
  /**
   * Return all the groups that are still "opened"
   */
  def getAllOpenedGroups() : Seq[SerializedGroups] 
  
  /**
   * Update a list of groups, and close (end) another list, based on their id
   * Updating is really setting a given endTime for the groups, and creating new ones, with all the nodes within 
   */
  def updateGroups(nodes : Seq[NodeGroup], closable : Seq[String]) :Seq[SerializedGroups]
  
  /**
   * Return all policy instances that have been updated or created after a specific time (optionnal),
   * If fetchUnclosed is set to true, it will also return the opened policy instances, regardless of their
   * opening time
   */
  def getAllPIs(after : Option[DateTime], fetchUnclosed : Boolean = false) : Seq[SerializedPIs] 
  
  /**
   * Return all the directives that are still "opened"
   */
  def getAllOpenedPIs() : Seq[SerializedPIs]
  
  /**
   * Update a list of policy instance, and close (end) another list, based on their id
   * Updating is really only setting now as a endTime for the policy instance, and then 
   * (re)create the policy instance
   */
  def updatePIs(pis : Seq[(PolicyInstance, UserPolicyTemplate, PolicyPackage)], 
      				closable : Seq[String]) :Seq[SerializedPIs]
  /**
   * Return all configuration rules created or closed after a given time, and if 
   * fetchUnclosed is true, return also the unclosed configuration rule
   */ 
  def getAllCRs(after : Option[DateTime], fetchUnclosed : Boolean = false) : Seq[(SerializedCRs, Seq[SerializedCRGroups],  Seq[SerializedCRPIs])]
  
  /**
   * Return all the configuration rules that are still "opened"
   */
  def getAllOpenedCRs() : Seq[ConfigurationRule] 
  /**
   * close the configuration rules based on their id, update the rule to update (updating is closing and creating)
   */
  def updateCrs(crs : Seq[ConfigurationRule], closable : Seq[String]) : Unit
  
 
}

