/*
*************************************************************************************
* Copyright 2011 Normation SAS
*************************************************************************************
*
* This file is part of Rudder.
*
* Rudder is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU General Public License version 3, the copyright holders add
* the following Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU General
* Public License version 3, when you create a Related Module, this
* Related Module is not considered as a part of the work and may be
* distributed under the license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* Rudder is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with Rudder.  If not, see <http://www.gnu.org/licenses/>.

*
*************************************************************************************
*/

package com.normation.rudder.services.policies.nodeconfig

import net.liftweb.common.Box
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies.RuleId
import org.joda.time.DateTime

trait NodeConfigurationService {

  /**
   * Get all NodeConfigurations cache
   */
  def getNodeConfigurationHash(): Box[Map[NodeId, NodeConfigurationHash]]

  /**
   * Update a node configuration using a NodeConfiguration :
   * update the directives and the node context, as well as the agentsName
   *
   * Return the map of all node config, with the
   */
  def sanitize(targets : Seq[NodeConfiguration]): Box[Map[NodeId, NodeConfiguration]]


  def detectSerialIncrementRequest(nodes : Seq[NodeConfiguration], cacheIsEmpty: Boolean): Set[RuleId]


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
  def cacheNodeConfiguration(nodeConfigurations: Set[NodeConfiguration], writtenDate: DateTime): Box[Set[NodeId]]

  /**
   * Look what are the node configuration updated compared to information in cache
   */
  def selectUpdatedNodeConfiguration(nodeConfigurations: Map[NodeId, NodeConfiguration], cache: Map[NodeId, NodeConfigurationHash]): Set[NodeId]

}
