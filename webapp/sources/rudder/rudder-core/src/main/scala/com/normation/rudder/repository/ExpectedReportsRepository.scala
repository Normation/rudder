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

package com.normation.rudder.repository
import com.normation.box.*
import com.normation.errors.*
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.reports.*
import net.liftweb.common.Box
import org.joda.time.*

trait UpdateExpectedReportsRepository {

  /**
   * Save a list of NodeExpectedReports, correctly handling
   * previous NodeExpectedReports for that node
   */
  def saveNodeExpectedReports(configs: List[NodeExpectedReports]): Box[Seq[NodeExpectedReports]]

  /**
   * Close opened expected node configurations for the given nodeID.
   */
  def closeNodeConfigurationsPure(nodeId: NodeId): IOResult[NodeId]
  def closeNodeConfigurations(nodeId: NodeId): Box[NodeId] = {
    closeNodeConfigurationsPure(nodeId).toBox
  }

  /**
   * Delete all node config id info that finished before date
   */
  def deleteNodeConfigIdInfo(date: DateTime): Box[Int]

  /**
   * Delete nodes_info for one node, typically when deleting it.
   */
  def deleteNodeInfos(nodeId: NodeId): IOResult[Unit]

  /**
   * Delete all NodeNonfigurations closed before a date
   */
  def deleteNodeConfigurations(date: DateTime): Box[Int]
}

trait FindExpectedReportRepository {

  /**
   * Return node ids associated to the rule (based on expectedreports (the one still pending)) for this Rule
   */
  def findCurrentNodeIds(rule: RuleId): Box[Set[NodeId]]

  /**
   * Return node ids associated to the rule (based on expectedreports (the one still pending)) for this Rule,
   * only limited on the nodeIds in parameter (used when cache is incomplete)
   */
  def findCurrentNodeIdsForRule(ruleId: RuleId, nodeIds: Set[NodeId]): IOResult[Set[NodeId]]

  /**
   * Return node ids associated to the rule (based on expectedreports (the one still pending)) for this Rule,
   * only limited on the nodeIds in parameter (used when cache is incomplete)
   */
  def findCurrentNodeIdsForDirective(ruleId: DirectiveId, nodeIds: Set[NodeId]): IOResult[Set[NodeId]]
  def findCurrentNodeIdsForDirective(ruleId: DirectiveId): IOResult[Set[NodeId]]

  /*
   * Retrieve the expected reports by config version of the nodes.
   *
   * The property "returnedMap.keySet == nodeConfigIds" holds.
   */
  def getExpectedReports(nodeConfigIds: Set[NodeAndConfigId]): Box[Map[NodeAndConfigId, Option[NodeExpectedReports]]]

  /*
   * Retrieve the current expected report for the list of nodes.
   *
   * The property "returnedMam.keySet = nodeIds" holds.
   */
  def getCurrentExpectedsReports(nodeIds: Set[NodeId]): Box[Map[NodeId, Option[NodeExpectedReports]]]

  /**
   * Retrieve all the nodeConfigIdInfo for the given list of nodes.
   * The property "nodeIds == returnedResult.keySet" holds.
   */
  def getNodeConfigIdInfos(nodeIds: Set[NodeId]): IOResult[Map[NodeId, Option[Vector[NodeConfigIdInfo]]]]
}
