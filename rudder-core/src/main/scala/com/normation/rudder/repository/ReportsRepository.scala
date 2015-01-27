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

package com.normation.rudder.repository

import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.reports._
import org.joda.time._
import com.normation.cfclerk.domain.{Cf3PolicyDraftId}
import net.liftweb.common.Box
import com.normation.rudder.reports.execution.AgentRun
import com.normation.rudder.reports.execution.AgentRunId

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
   *
   * That method doesn't check if there is missing execution in
   * the result compared to inputs.
   */
  def getExecutionReports(runs: Set[AgentRunId], filterByRules: Set[RuleId]): Box[Map[NodeId, Seq[Reports]]]



  /**
   * Returns all reports for the node, between the two differents date (optionnal)
   * for a rule (optionnal) and for a specific serial of this rule (optionnal)
   * Note : serial is used only if rule is used
   * Note : only the 1000 first entry are returned
   */
  def findReportsByNode(
      nodeId   : NodeId
  ) : Seq[Reports]

  /**
   * All reports for a node and rule/serial, between two date, ordered by date
   */
  def findReportsByNode(
      nodeId   : NodeId
    , ruleId   : RuleId
    , serial   : Int
    , beginDate: DateTime
    , endDate  : Option[DateTime]
  ) : Seq[Reports]


  //advanced reporting only
  def findExecutionTimeByNode(
      nodeId   : NodeId
    , beginDate: DateTime
    , endDate  : Option[DateTime]
  ) : Seq[DateTime]

  def getOldestReports() : Box[Option[Reports]]

  //databaseManager only
  def getOldestArchivedReports() : Box[Option[Reports]]
  def getNewestReports() : Box[Option[Reports]]
  def getNewestArchivedReports() : Box[Option[Reports]]
  def getDatabaseSize(databaseName : String) : Box[Long]
  def reportsTable : String
  def archiveTable : String
  def archiveEntries(date : DateTime) : Box[Int]
  def deleteEntries(date : DateTime) : Box[Int]

  //automaticReportLogger only
  def getHighestId : Box[Long]
  def getLastHundredErrorReports(kinds:List[String]) : Box[Seq[(Reports,Long)]]
  def getErrorReportsBeetween(lower : Long, upper:Long,kinds:List[String]) : Box[Seq[Reports]]


  //nodechangesServices
  def getChangeReports(notBefore: DateTime): Box[Seq[ResultRepairedReport]]
  def getChangeReportsOnInterval(lowestId: Long, highestId: Long): Box[Seq[ResultRepairedReport]]

  //reportExecution only
  /**
   * From an id and an end date, return a list of AgentRun, and the max ID that has been considered
   */
  def getReportsfromId(id : Long, endDate : DateTime) : Box[(Seq[AgentRun], Long)]

  def getReportsWithLowestId : Box[Option[(Reports,Long)]]
}
