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

package com.normation.rudder.services.policies.nodeconfig

import com.normation.rudder.domain.servers._
import net.liftweb.common.Box
import com.normation.inventory.domain.NodeId
import com.normation.rudder.repository.FullActiveTechniqueCategory
import com.normation.rudder.domain.policies.RuleId

trait NodeConfigurationService {

  /**
   * Get all NodeConfigurations cache
   */
  def getNodeConfigurationCache(): Box[Map[NodeId, NodeConfigurationCache]]

  /**
   * Update a node configuration using a NodeConfiguration :
   * update the directives and the node context, as well as the agentsName
   *
   * Return the map of all node config, with the
   */
  def sanitize(targets : Seq[NodeConfiguration]): Box[Map[NodeId, NodeConfiguration]]


  def detectChangeInNodes(nodes : Seq[NodeConfiguration], cache: Map[NodeId, NodeConfigurationCache], directiveLib: FullActiveTechniqueCategory): Set[RuleId]


  /**
   * Delete a list of node configurations
   * If a NodeConfiguration is not found, ignore it.
   */
  def deleteNodeConfigurations(nodeIds:Set[NodeId]): Box[Set[NodeId]]

  /**
   * Inverse of delete: delete all node configuration not
   * given in the argument.
   */
  def onlyKeepNodeConfiguration(nodeIds:Set[NodeId]): Box[Set[NodeId]]

  /**
   * Delete all node configurations
   */
  def deleteAllNodeConfigurations(): Box[Unit]

  /**
   * Cache these node configurations.
   */
  def cacheNodeConfiguration(nodeConfigurations: Set[NodeConfiguration]): Box[Set[NodeId]]

  /**
   * Write the templates of ALL the given node configuration.
   * Select them carrefully!
   */
  def writeTemplate(rootNodeId: NodeId, configToWrite: Set[NodeId], allNodeConfigs: Map[NodeId, NodeConfiguration]): Box[Seq[NodeConfiguration]]

  /**
   * Look what are the node configuration updated compared to information in cache
   */
  def selectUpdatedNodeConfiguration(nodeConfigurations: Map[NodeId, NodeConfiguration], cache: Map[NodeId, NodeConfigurationCache]): Set[NodeId]

  def detectChangeInNode(currentOpt: Option[NodeConfigurationCache], targetConfig: NodeConfiguration, directiveLib: FullActiveTechniqueCategory): Set[RuleId]

}
