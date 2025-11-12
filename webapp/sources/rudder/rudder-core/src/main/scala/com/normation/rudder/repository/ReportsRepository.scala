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

import com.normation.errors.IOResult
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.reports.*
import com.normation.rudder.reports.execution.AgentRun
import com.normation.rudder.reports.execution.AgentRunId
import net.liftweb.common.Box
import org.joda.time.*

/**
 * An overly simple repository for searching through the cfengine reports
 * Can search by CR, by Node, by both, and or by date
 *
 */
trait ReportsRepository {

  /*
   * now, for each node, three cases:
   * - no last run => no exec report for node, only get LAST expected reports for it
   * - a last run, no config version => get the execution reports of that run and the LAST expected reports (no other info)
   * - a last run and a config => get the execution reports for that run and the expected reports for the config
   *
   * So: get reports for available execution, sort the result by nodes with a default of "no reports"
   *
   * Optimize the expected reports part because several nodes can have the same reports
   */

  /**
   * Find the reports corresponding to the given agent executions,
   * so the reports for a set of (node, execution starting timestamp).
   *  We assume that this method is called with a limited list of runs, as it can return a
   *  *really* large amount of data
   * That method doesn't check if there is missing execution in
   * the result compared to inputs.
   */
  def getExecutionReports(
      runs: Set[AgentRunId]
  ): IOResult[Map[NodeId, Seq[Reports]]]

  /**
   * Returns all reports for the node, between the two different date (optionnal)
   * for a rule (optional) and for a specific serial of this rule (optional)
   * Note : only the 5000 first entry are returned
   */
  def findReportsByNode(
      nodeId: NodeId,
      limit:  Int = 5000
  ): Seq[Reports]

  def findReportsByNodeOnInterval(
      nodeId: NodeId,
      start:  Option[DateTime],
      end:    Option[DateTime]
  ): Seq[Reports]

  def findReportsByNodeByRun(
      nodeId:  NodeId,
      runDate: DateTime
  ): Seq[Reports]

  // databaseManager only
  def getReportsInterval(): Box[(Option[DateTime], Option[DateTime])]

  def getDatabaseSize(databaseName: String): Box[Long]
  def reports: String
  def deleteEntries(date: DateTime): Box[Int]

  def deleteLogReports(date: DateTime): Box[Int]

  // automaticReportLogger only
  /**
   * Get the highest id of any kind of reports.
   */
  def getHighestId(): Box[Long]
  def getLastHundredErrorReports(kinds: List[String]): Box[Seq[(Long, Reports)]]
  // return the reports between the two ids, limited to limit number of reports, in asc order of id.
  def getReportsByKindBetween(lower:    Long, upper: Option[Long], limit: Int, kinds: List[String]): Box[Seq[(Long, Reports)]]

  /*
   * Count number of changes by rule by interval. Also return the id of the highest result_repair.
   */
  def countChangeReportsByBatch(intervals: List[Interval]): Box[(Long, Map[RuleId, Map[Interval, Int]])]

  // nodechangesServices
  /*
   *  Count change reports by rules on interval of intervalSizeHour hour, starting at startTime
   *  StartTime should be a 00:00:00 time.
   */
  def countChangeReports(startTime:            DateTime, intervalSizeHour: Int): Box[Map[RuleId, Map[Interval, Int]]]
  def getChangeReportsOnInterval(lowestId:     Long, highestId:            Long): IOResult[Seq[ChangeForCache]]
  def getChangeReportsByRuleOnInterval(ruleId: RuleId, interval:           Interval, limit: Option[Int]): Box[Seq[ResultRepairedReport]]

  // reportExecution only
  // Return the max id before a datetime
  def getMaxIdBeforeDateTime(fromId: Long, before: DateTime): Box[Option[Long]]

  /**
   * From an id and an end date (optionnal, if none, till now), return a list of AgentRun, and the max ID that has been considered
   */
  def getReportsFromId(id: Long, endDate: DateTime): Box[(Seq[AgentRun], Long)]

  def getReportsWithLowestId: Box[Option[(Long, Reports)]]

  def getReportsWithLowestIdFromDate(from: DateTime): Box[Option[(Long, Reports)]]

}
