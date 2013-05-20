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
import com.normation.rudder.domain.servers._
import net.liftweb.common.Box
import com.normation.cfclerk.domain.{Cf3PolicyDraftId,TechniqueId}
import com.normation.rudder.services.policies.TargetNodeConfiguration
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies.{Rule,RuleId}

trait NodeConfigurationService {

  /**
   * Get all NodeConfigurations
   */
  def getAllNodeConfigurations() : Box[Map[NodeId, NodeConfiguration]]

  /**
   * Update a node configuration using a targetNodeConfiguration :
   * update the directives and the node context, as well as the agentsName
   * (well, every fields actually)
   * @param target
   * @return
   */
  def updateNodeConfiguration(target : TargetNodeConfiguration, allNodeConfiguration: Map[NodeId, NodeConfiguration]) : Box[Map[NodeId, NodeConfiguration]]


  /**
   * Delete a list of node configurations
   * If a NodeConfiguration is not found, ignore it.
   */
  def deleteNodeConfigurations(nodeIds:Set[NodeId]) : Box[Set[NodeId]]

  /**
   * Delete all node configurations
   */
  def deleteAllNodeConfigurations() : Box[Set[NodeId]]

  /**
   * Write the templates of the updated NodeConfigurations.
   * That method will write the promises for updated nodes
   * and save corresponding node configuration *only*.
   * If there is no modification between current and target state,
   * nothing is done for that node configuration.
   *
   * Return the list of updated node configuration (and so nodes
   * for which promises where written).
   */
  def writeTemplateForUpdatedNodeConfigurations(rootNodeId: NodeId, allNodeConfigs: Map[NodeId, NodeConfiguration]) : Box[Seq[NodeConfiguration]]

  ///// pure methods /////

  /**
   * Find the NodeConfigurations having the policy name listed (it's policy name, not instance).
   * We are looking in TARGET rule policy draft containing technique with given name
   */
  def getNodeConfigurationsMatchingPolicy(techniqueId : TechniqueId, allNodeConfigs:Map[NodeId, NodeConfiguration]) : Seq[NodeConfiguration] = {
    allNodeConfigs.values.toSeq.filterNot( _.findDirectiveByTechnique(techniqueId).isEmpty )
  }

  /**
   * Find the NodeConfigurations having the directive named (it's the directiveId)
   * We are looking for CURRENT rule policy draft
   */
  def getNodeConfigurationsMatchingDirective(cf3PolicyDraftId : Cf3PolicyDraftId, allNodeConfigs: Map[NodeId, NodeConfiguration]) : Seq[NodeConfiguration] = {
    allNodeConfigs.values.toSeq.filter( _.currentRulePolicyDrafts.exists(x => x.draftId == cf3PolicyDraftId) )
  }

}
