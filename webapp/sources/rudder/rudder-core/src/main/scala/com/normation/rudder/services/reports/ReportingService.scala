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

import com.normation.errors.IOResult
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.logger.TimingDebugLogger
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.reports.ComplianceLevel
import com.normation.rudder.domain.reports.NodeStatusReport
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
  def findRuleNodeStatusReports(nodeIds: Set[NodeId], filterByRules: Set[RuleId]): Box[Map[NodeId, NodeStatusReport]]
  def findDirectiveNodeStatusReports(
      nodeIds:                           Set[NodeId],
      filterByDirectives:                Set[DirectiveId]
  ): Box[Map[NodeId, NodeStatusReport]]

  def findUncomputedNodeStatusReports(): Box[Map[NodeId, NodeStatusReport]]

  /**
   * Retrieve a set of rule/node compliances given the nodes Id.
   * Optionally restrict the set to some rules if filterByRules is non empty (else,
   * find node status reports for all rules)
   */
  def findRuleNodeCompliance(nodeIds: Set[NodeId], filterByRules: Set[RuleId]): IOResult[Map[NodeId, ComplianceLevel]]

  /**
   * Retrieve two sets of rule/node compliances level given the nodes Id.
   * Optionally restrict the set to some rules if filterByRules is non empty (else,
   * find node status reports for all rules)
   */
  def findSystemAndUserRuleCompliances(
      nodeIds:             Set[NodeId],
      filterBySystemRules: Set[RuleId],
      filterByUserRules:   Set[RuleId]
  ): IOResult[(Map[NodeId, ComplianceLevel], Map[NodeId, ComplianceLevel])]

  /**
   * A specialised version of `findRuleNodeStatusReports` to find node status reports for a given rule.
   */
  def findDirectiveRuleStatusReportsByRule(ruleId: RuleId): IOResult[Map[NodeId, NodeStatusReport]]

  /**
    * find node status reports for a given node.
    */
  def findNodeStatusReport(nodeId: NodeId): Box[NodeStatusReport]

  /**
    * find node status reports for a given node.
    */
  def findUserNodeStatusReport(nodeId: NodeId): Box[NodeStatusReport]

  /**
    * find system node status reports for a given node.
    */
  def findSystemNodeStatusReport(nodeId: NodeId): Box[NodeStatusReport]

  /**
   * find node status reports for *user* rules (all non system rules)
   */
  def getUserNodeStatusReports(): Box[Map[NodeId, NodeStatusReport]]

  def findStatusReportsForDirective(directiveId: DirectiveId): IOResult[Map[NodeId, NodeStatusReport]]

  /**
   * find node status reports for user and system rules but in a separated couple (system is first element, user second)
   */
  def getSystemAndUserCompliance(
      optNodeIds: Option[Set[NodeId]]
  ): IOResult[(Map[NodeId, ComplianceLevel], Map[NodeId, ComplianceLevel])]

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
    if (ruleIds.isEmpty) {
      reports
    } else
      {
        val n1     = System.currentTimeMillis
        val result = reports.view.mapValues {
          case status =>
            NodeStatusReport.filterByRules(status, ruleIds)
        }.filter { case (_, v) => v.reports.nonEmpty || v.overrides.nonEmpty }
        val n2     = System.currentTimeMillis
        TimingDebugLogger.trace(s"Filter Node Status Reports on ${ruleIds.size} in : ${n2 - n1}ms")
        result
      }.toMap
  }

  def filterReportsByDirectives(
      reports:      Map[NodeId, NodeStatusReport],
      directiveIds: Set[DirectiveId]
  ): Map[NodeId, NodeStatusReport] = {
    if (directiveIds.isEmpty) {
      reports
    } else
      {
        val n1     = System.currentTimeMillis
        val result = reports.view.mapValues {
          case status =>
            NodeStatusReport.filterByDirectives(status, directiveIds)
        }.filter { case (_, v) => v.reports.nonEmpty || v.overrides.nonEmpty }
        val n2     = System.currentTimeMillis
        TimingDebugLogger.trace(s"Filter Node Status Reports on ${directiveIds.size} Directives in : ${n2 - n1}ms")
        result
      }.toMap
  }

  def complianceByRules(report: NodeStatusReport, ruleIds: Set[RuleId]): ComplianceLevel = {
    if (ruleIds.isEmpty) {
      report.compliance
    } else {
      // compute compliance only for the selected rules
      // BE CAREFUL: reports is a SET - and it's likely that
      // some compliance will be equals. So change to seq.
      ComplianceLevel.sum(report.reports.toSeq.collect {
        case report if (ruleIds.contains(report.ruleId)) =>
          report.compliance
      })
    }
  }

  def complianceByRulesByDirectives(
      report:       NodeStatusReport,
      ruleIds:      Set[RuleId],
      directiveIds: Set[DirectiveId]
  ): ComplianceLevel = {
    if (ruleIds.isEmpty) {
      report.compliance
    } else {
      // compute compliance only for the selected rules
      // BE CAREFUL: reports is a SET - and it's likely that
      // some compliance will be equals. So change to seq.
      ComplianceLevel.sum(report.reports.toSeq.collect {
        case report if (ruleIds.contains(report.ruleId)) =>
          report.compliance
      })
    }
  }
}
