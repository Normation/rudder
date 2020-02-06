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

package com.normation.rudder.services.reports

import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.logger.TimingDebugLogger
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.reports.{ComplianceLevel, NodeStatusReport, RuleStatusReport}
import net.liftweb.common.Box

/**
 * That service allows to retrieve status of nodes or
 * rules.
 */
trait ReportingService {

  /**
   * Retrieve a set of rule/node status reports given the nodes Id.
   * Optionally restrict the set to some rules if filterByRules is non empty (else,
   * find node status reports for all rules)
   */
  def findRuleNodeStatusReports(nodeIds: Set[NodeId], filterByRules : Set[RuleId]): Box[Map[NodeId, NodeStatusReport]]

  /**
   * find rule status reports for a given rule.
   */
  def findDirectiveRuleStatusReportsByRule(ruleId: RuleId): Box[RuleStatusReport]

  /**
   * find node status reports for a given node.
   */
  def findNodeStatusReport(nodeId: NodeId) : Box[NodeStatusReport]


  /**
   * find node status reports for *user* rules (all non system rules)
   */
  def getUserNodeStatusReports() : Box[Map[NodeId, NodeStatusReport]]

  /**
   * * Get the global compliance for reports passed in parameters
   * * Returns get an unique number which describe the global compliance value (without
   * * taking into account pending reports).
   * * It's what is displayed on Rudder home page.
   * * If reports are empty, returns none.
   */
  def computeComplianceFromReports(reports: Map[NodeId, NodeStatusReport]): Option[(ComplianceLevel, Long)]

 /**
  * Get the global compliance, restricted to user defined rules/directives.
  * Also get an unique number which describe the global compliance value (without
  * taking into account pending reports).
  * It's what is displayed on Rudder home page.
  * If all rules/directies are system one, returns none.
  */
  def getGlobalUserCompliance(): Box[Option[(ComplianceLevel, Long)]]




  // Utilitary method to filter reports by rules
  def filterReportsByRules(reports: Map[NodeId, NodeStatusReport], ruleIds: Set[RuleId]): Map[NodeId, NodeStatusReport] = {
    if(ruleIds.isEmpty) {
      reports
    } else {
      val n1 = System.currentTimeMillis
      val result = reports.mapValues { status =>
        NodeStatusReport.filterByRules(status, ruleIds)
      }.filter { case (k,v) => v.reports.nonEmpty || v.overrides.nonEmpty }
      val n2 = System.currentTimeMillis
      TimingDebugLogger.trace(s"Filter Node Status Reports on ${ruleIds.size} in : ${n2 - n1}ms")
      result
    }
  }
}
