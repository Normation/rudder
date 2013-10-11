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
import com.normation.rudder.domain.reports.bean._
import org.joda.time._
import com.normation.cfclerk.domain.{Cf3PolicyDraftId}
import net.liftweb.common.Box
import com.normation.rudder.reports.execution.ReportExecution

/**
 * An overly simple repository for searching through the cfengine reports
 * Can search by CR, by Node, by both, and or by date
 * @author Nicolas CHARLES
 *
 */
trait ReportsRepository {

  /**
   * Returns all reports for the ruleId, between the two differents date (optionnally)
   */
  def findReportsByRule(
      ruleId   : RuleId
    , serial   : Option[Int]
    , beginDate: Option[DateTime]
    , endDate  : Option[DateTime]
  ) : Seq[Reports]


  /**
   * Return the last (really the last, serial wise, with full execution) reports for a rule
   */
  def findLastReportByRule(
      ruleId: RuleId
    , serial: Int
    , node  : Option[NodeId]
  ) : Seq[Reports]


  /**
   * Returns all reports for the node, between the two differents date (optionnal)
   * for a rule (optionnal) and for a specific serial of this rule (optionnal)
   * Note : serial is used only if rule is used
   * Note : only the 1000 first entry are returned
   */
  def findReportsByNode(
      nodeId   : NodeId
    , ruleId   : Option[RuleId]
    , serial   : Option[Int]
    , beginDate: Option[DateTime]
    , endDate  : Option[DateTime]
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

  def findExecutionTimeByNode(
      nodeId   : NodeId
    , beginDate: DateTime
    , endDate  : Option[DateTime]
  ) : Seq[DateTime]


  def getOldestReports() : Box[Option[Reports]]

  def getOldestArchivedReports() : Box[Option[Reports]]

  def getNewestReportOnNode(nodeid:NodeId) : Box[Option[Reports]]

  def getNewestReports() : Box[Option[Reports]]

  def getNewestArchivedReports() : Box[Option[Reports]]

  def getDatabaseSize(databaseName : String) : Box[Long]

  def reportsTable : String

  def archiveTable : String

  def archiveEntries(date : DateTime) : Box[Int]

  def deleteEntries(date : DateTime) : Box[Int]

  def getHighestId : Box[Int]

  def getLastHundredErrorReports(kinds:List[String]) : Box[Seq[(Reports,Int)]]

  def getErrorReportsBeetween(lower : Int, upper:Int,kinds:List[String]) : Box[Seq[Reports]]

  /**
   * From an id and an end date, return a list of ReportExecution, and the max ID that has been considered
   */
  def getReportsfromId(id : Int, endDate : DateTime) : Box[(Seq[ReportExecution], Int)]

  def getReportsWithLowestId : Box[Option[(Reports,Int)]]
}