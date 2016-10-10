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
import com.normation.rudder.domain.policies.SerialedRuleId


trait UpdateExpectedReportsRepository {
  /**
   * Return the ruleId currently opened, and their serial and list of nodes
   * It is only used to know which conf expected report we should close
   *
   * For only the last version (and so only one NodeAndConfigId) is
   * returned for each nodeJoinKey
   */
  //only for update logic
  def findAllCurrentExpectedReportsWithNodesAndSerial(): Map[RuleId, (Int, Int, Map[NodeId, NodeConfigVersions])]


 /**
   * Simply set the endDate for the expected report for this conf rule
   */
  //only for update logic
  def closeExpectedReport(directivesLib: FullActiveTechniqueCategory, ruleId : RuleId, generationTime: DateTime) : Box[Unit]

  /**
   * Insert new expectedReports in base.
   * Not that expectedReports are never "updated". Old
   * one are closed and new one are created (aka saved')
   */
  //only for update logic
  def saveExpectedReports(
      ruleId                   : RuleId
    , serial                   : Int
    , generationTime           : DateTime
    , directiveExpectedReports : Seq[DirectiveExpectedReports]
    , nodeConfigurationVersions: Seq[NodeAndConfigId]
    , directivesLib            : FullActiveTechniqueCategory
  ) : Box[RuleExpectedReports]


  /**
   * Update the list of nodeConfigVersion for the given nodes
   */
  //only for update logic
  def updateNodeConfigVersion(toUpdate: Seq[(Int, NodeConfigVersions)]): Box[Seq[(Int,NodeConfigVersions)]]

  /**
   * Delete all node config id info that finished before date
   */
  def deleteNodeConfigIdInfo(date:DateTime) : Box[Int]

  /**
   * Delete all expected reports closed before a date
   */
  def deleteExpectedReports(date: DateTime) : Box[Int]

}




trait FindExpectedReportRepository {

  /**
   * Return all the expected reports between the two dates
   * ## used by the advanced reporting module ##
   */
  def findExpectedReports(directivesLib: FullActiveTechniqueCategory, beginDate : DateTime, endDate : DateTime) : Box[Seq[RuleExpectedReports]]

  /**
   * Return node ids associated to the rule (based on expectedreports (the one still pending)) for this Rule
   */
  def findCurrentNodeIds(rule : RuleId) : Box[Set[NodeId]]

  /*
   * Retrieve the expected reports by config version of the nodes.
   * Here, for a given node, we can have several configId asked for.
   */
  def getExpectedReports(
      nodeConfigIds: Set[NodeAndConfigId]
    , filterByRules: Set[RuleId]
    , directivesLib: FullActiveTechniqueCategory
  ): Box[Map[NodeId, Map[NodeConfigId, Map[SerialedRuleId, RuleNodeExpectedReports]]]]
}


case class NodeConfigIdInfo(
    configId : NodeConfigId
  , creation : DateTime
  , endOfLife: Option[DateTime]

// that would be very cool to be able to directly link here from nodeConfigId to the list of expectedvalue
// and so we could directly query for a list of node/version the list of expected values
// , expectedReportLines: Set[Long]
)

trait RoNodeConfigIdInfoRepository {

  def getNodeConfigIdInfos(nodeIds: Set[NodeId]): Box[Map[NodeId, Option[Seq[NodeConfigIdInfo]]]]

}

trait WoNodeConfigIdInfoRepository {
  def addNodeConfigIdInfo(updatedNodeConfigs: Map[NodeId, NodeConfigId], generationTime: DateTime): Box[Set[NodeId]]
}
