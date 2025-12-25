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
import com.normation.rudder.domain.policies.PolicyTypeName
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.reports.ComplianceLevel
import com.normation.rudder.domain.reports.NodeStatusReport
import com.normation.rudder.domain.reports.NodeStatusReport.*
import com.normation.rudder.tenants.QueryContext

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
  def findRuleNodeStatusReports(nodeIds: Set[NodeId], filterByRules: Set[RuleId])(implicit
      qc: QueryContext
  ): IOResult[Map[NodeId, NodeStatusReport]]

  def findDirectiveNodeStatusReports(
      nodeIds:            Set[NodeId],
      filterByDirectives: Set[DirectiveId]
  )(implicit qc: QueryContext): IOResult[Map[NodeId, NodeStatusReport]]

  /**
   * A specialised version of `findRuleNodeStatusReports` to find node status reports for a given rule.
   */
  def findDirectiveRuleStatusReportsByRule(ruleId: RuleId)(implicit qc: QueryContext): IOResult[Map[NodeId, NodeStatusReport]]

  /**
    * find node status reports for a given node.
    */
  def findNodeStatusReport(nodeId: NodeId)(implicit qc: QueryContext): IOResult[NodeStatusReport]

  /**
    * find node status reports for a given node.
    */
  def findUserNodeStatusReport(nodeId: NodeId)(implicit qc: QueryContext): IOResult[NodeStatusReport]

  /**
    * find system node status reports for a given node.
    */
  def findSystemNodeStatusReport(nodeId: NodeId)(implicit qc: QueryContext): IOResult[NodeStatusReport]

  /**
   * find node status reports for *user* rules (all non system rules)
   */
  def getUserNodeStatusReports()(implicit qc: QueryContext): IOResult[Map[NodeId, NodeStatusReport]]

  def findStatusReportsForDirective(directiveId: DirectiveId)(implicit qc: QueryContext): IOResult[Map[NodeId, NodeStatusReport]]

  /**
   * find node status reports for user and system rules but in a separated couple (system is first element, user second)
   */
  def getSystemAndUserCompliance(
      optNodeIds: Option[Set[NodeId]]
  )(implicit
      qc:         QueryContext
  ): IOResult[SystemUserComplianceRun]

  /**
  * Get the global compliance, restricted to user defined rules/directives.
  * Also get an unique number which describe the global compliance value (without
  * taking into account pending reports).
  * It's what is displayed on Rudder home page.
  * If all rules/directives are system one, returns none.
  */
  def getGlobalUserCompliance()(implicit qc: QueryContext): IOResult[Option[(ComplianceLevel, Long)]]

}

object ReportingService {
  // Utility method to filter reports by rules
  def filterReportsByRules(reports: Map[NodeId, NodeStatusReport], ruleIds: Set[RuleId]): Map[NodeId, NodeStatusReport] = {
    if (ruleIds.isEmpty) {
      reports
    } else
      {
        val n1     = System.currentTimeMillis
        val result = reports.view.mapValues {
          case status =>
            NodeStatusReport.filterByRules(status, ruleIds)
        }.filter { case (_, v) => v.reports.nonEmpty }
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
        }.filter { case (_, v) => v.reports.nonEmpty }
        val n2     = System.currentTimeMillis
        TimingDebugLogger.trace(s"Filter Node Status Reports on ${directiveIds.size} Directives in : ${n2 - n1}ms")
        result
      }.toMap
  }

  def complianceByPolicyType(report: NodeStatusReport, policyType: PolicyTypeName): ComplianceLevel = {
    ComplianceLevel.sum(report.reports.toSeq.collect {
      case (t, r) if t == policyType =>
        r.compliance
    })
  }

  /**
   * * Get the global compliance for reports passed in parameters
   * * Returns get an unique number which describe the global compliance value (without
   * * taking into account pending reports).
   * * It's what is displayed on Rudder home page.
   * * If reports are empty, returns none.
   */
  def computeComplianceFromReports(reports: Map[NodeId, NodeStatusReport]): Option[(ComplianceLevel, Long)] = {
    // if we don't have any report that is not a system one, the user-rule global compliance is undefined
    val n1 = System.currentTimeMillis
    if (reports.isEmpty) {
      None
    } else { // aggregate values
      val complianceLevel = ComplianceLevel.sum(reports.flatMap(_._2.reports.map(_._2.compliance)))
      val n2              = System.currentTimeMillis
      TimingDebugLogger.trace(s"Aggregating compliance level for  global user compliance in: ${n2 - n1}ms")

      Some(
        (
          complianceLevel,
          complianceLevel.withoutPending.computePercent().compliance.round
        )
      )
    }
  }
}
