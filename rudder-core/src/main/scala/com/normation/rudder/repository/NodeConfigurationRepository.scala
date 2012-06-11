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

import com.normation.inventory.domain.NodeId
import net.liftweb.common.Box
import com.normation.rudder.domain.policies.RuleId
import scala.collection._
import com.normation.rudder.domain.servers._
import com.normation.rudder.domain._
import com.normation.rudder.exceptions._
import com.normation.cfclerk.domain.{TechniqueId}

trait NodeConfigurationRepository {

  
  def getRootNodeConfiguration() : Box[RootNodeConfiguration]
  
   /**
   * Add the root server to the repo
   * @param server
   * @return
   */
  def addRootNodeConfiguration(server : RootNodeConfiguration) : Box[RootNodeConfiguration]
  
  /**
   * Search a server by its uuid
   * @param uuid
   * @return the server
   */
  def findNodeConfiguration(nodeId : NodeId) : Box[NodeConfiguration]

  /**
   * Return multiples servers
   * @param uuids
   * @return
   */
  def getMultipleNodeConfigurations(nodeId : Seq[NodeId]) : Box[Set[NodeConfiguration]]
  
  /**
   * Save a server in the repo
   * @param server
   * @return
   */
  def saveNodeConfiguration(server:NodeConfiguration) : Box[NodeConfiguration]
  
  
  /**
   * Save several servers in the repo
   * @param server
   * @return
   */
  def saveMultipleNodeConfigurations(server: Seq[NodeConfiguration]) : Box[Seq[NodeConfiguration]]
  
  /**
   * Delete a server. It will first clean its roles, and keep the consistencies of data
   * @param server
   */
  def deleteNodeConfiguration(server:NodeConfiguration) : Box[String]
  /**
   * Delete a server. Does not check the consistency of anything
   * @param server
   */
  def deleteNodeConfiguration(uuid:String) : Box[String]

  /**
   * Delete all node configurations
   */
  def deleteAllNodeConfigurations : Box[Set[NodeId]]
  
  /**
   * Return all servers
   * @return
   */
  def getAll() : Box[Map[String, NodeConfiguration]]


  /**
   * Look for all server which have the given directive ID in 
   * their CURRENT directives.
   */
  def findNodeConfigurationByCurrentRuleId(uuid:RuleId) : Box[Seq[NodeConfiguration]] 
  
  /**
   * Look for all server which have the given policy name (however 
   * TARGET directives of that policy they have, as long 
   * as they have at least one)
   */
  def findNodeConfigurationByTargetPolicyName(policyName:TechniqueId) : Box[Seq[NodeConfiguration]] 
  
  /**
   * Return all the server that need to be commited
   * Meaning, all servers that have a difference between the current and target directive
   * 
   * TODO: perhaps it should be a method of BridgeToCfclerkService, 
   * and then NodeConfigurationService will be able to find all servers with
   * theses directives
   */
  def findUncommitedNodeConfigurations() : Box[Seq[NodeConfiguration]]
  
  
}
