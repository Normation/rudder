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

import com.normation.inventory.domain.AgentType
import scala.collection._
import com.normation.rudder.domain.servers._
import net.liftweb.common.Box
import com.normation.cfclerk.domain.{Cf3PolicyDraftId,TechniqueId}
import com.normation.rudder.services.policies.targetNodeConfiguration
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies.{Rule,RuleId}

trait NodeConfigurationService {

  /**
   * Find a node configuration by its uuid
   * @param nodeId
   * @return
   */
  def findNode(nodeId: NodeId) : Option[NodeConfiguration]

  /**
   * Return multiple NodeConfigurations by their uuid
   * If an uuid is not existent, it is simply skipped
   * @param uuids
   * @return
   */
  def getMultipleNodeConfigurations(uuids : Seq[NodeId]) : Set[NodeConfiguration]


  /**
   * Find all NodeConfigurations
   * @return
   */
  def getAllNodeConfigurations() : Map[String, NodeConfiguration]

  /**
   * Update a node configuration using a targetNodeConfiguration :
   * update the directives and the node context, as well as the agentsName
   * (well, every fields actually)
   * @param target
   * @return
   */
  def updateNodeConfiguration(target : targetNodeConfiguration) : Box[NodeConfiguration]

    /**
   * From the list of updated rules (is it what we need) ? and the list of ALL NODES (caution, we must have 'em all)
   * update the serials, and save them
   */
  def incrementSerials(rules: Seq[(RuleId,Int)], nodes : Seq[NodeConfiguration]) : Box[Seq[NodeConfiguration]]


  /**
   * Create a node configuration from a target.
   * Hence, it will have empty current configuration
   * @param target
   * @return
   */
  def addNodeConfiguration(target : targetNodeConfiguration) : Box[NodeConfiguration]

  /**
   * Delete a NodeConfiguration by its uuid
   * If a NodeConfiguration is not existant, throw a NotFoundException
   */
  def deleteNodeConfiguration(nodeConfigurationUUID:String) : Box[Unit]

  /**
   * Delete all node configurations
   */
  def deleteAllNodeConfigurations() : Box[Set[NodeId]]

  /**
   * Return the NodeConfiguration that need to be commited (that have been updated, and
   * their promises are not yet written)
   */
  def getUpdatedNodeConfigurations() : Seq[NodeConfiguration]


  /**
   * Write the templates of the updated NodeConfigurations
   * All the updated NodeConfigurations must be written
   * @param uuids
   */
  def writeTemplateForUpdatedNodeConfigurations(uuids : Seq[NodeId]) : Box[Seq[NodeConfiguration]]

  /**
   * Rollback the configuration of the updated NodeConfigurations
   * All the updated NodeConfigurations must be rollbacked in one shot
   * If the input parameters contains NodeConfiguration that does not need to be roolbacked, they won't be
   * @param uuids : the uuid of the updated NodeConfiguration
   */
  def rollbackNodeConfigurations(uuids : Seq[NodeId]) : Box[Unit]


  /**
   * Find the NodeConfigurations having all the policies name listed (it's policy name, not instance)
   */
  def getNodeConfigurationsMatchingPolicy(policyName : TechniqueId) : Seq[NodeConfiguration]

  /**
   * Find the NodeConfigurations having the directive named (it's the directiveId)
   */
  def getNodeConfigurationsMatchingDirective(directiveId : Cf3PolicyDraftId) : Seq[NodeConfiguration]
}
