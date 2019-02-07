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
import org.joda.time._
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.reports._
import net.liftweb.common.Box


trait UpdateExpectedReportsRepository {

  /**
   * Save a list of NodeExpectedReports, correctly handling
   * previous NodeExpectedReports for that node
   */
  def saveNodeExpectedReports(configs: List[NodeExpectedReports]): Box[List[NodeExpectedReports]]

  /**
   * Close opened expected node configurations for the given nodeID.
   */
  def closeNodeConfigurations(nodeId: NodeId): Box[NodeId]

  /**
   * Delete all node config id info that finished before date
   */
  def deleteNodeConfigIdInfo(date:DateTime) : Box[Int]

  /**
   * Archive all NodeConfigurations closed before a date
   */
  def archiveNodeConfigurations(date: DateTime) : Box[Int]

  /**
   * Delete all NodeNonfigurations closed before a date
   */
  def deleteNodeConfigurations(date: DateTime) : Box[Int]

  /**
   * Archive all NodeCoompliance for runs older than date
   */
  def archiveNodeCompliances(date: DateTime) : Box[Int]

  /**
   * Delete all NodeCompliance for runs older than date
   */
  def deleteNodeCompliances(date: DateTime) : Box[Int]

  /**
   * Delete all nodecompliancelevels for uns older than date.
   */
  def deleteNodeComplianceLevels(date: DateTime): Box[Int]
}




trait FindExpectedReportRepository {

  /**
   * Return node ids associated to the rule (based on expectedreports (the one still pending)) for this Rule
   */
  def findCurrentNodeIds(rule : RuleId) : Box[Set[NodeId]]

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
  def getNodeConfigIdInfos(nodeIds: Set[NodeId]): Box[Map[NodeId, Option[Vector[NodeConfigIdInfo]]]]
}
