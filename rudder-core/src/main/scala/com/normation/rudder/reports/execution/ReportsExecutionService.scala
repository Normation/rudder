/*
*************************************************************************************
* Copyright 2013 Normation SAS
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

package com.normation.rudder.reports.execution

import com.normation.rudder.repository.ReportsRepository
import com.normation.rudder.domain.reports.bean._
import com.normation.utils.HashcodeCaching
import net.liftweb.common._
import com.normation.rudder.reports.status.StatusUpdateRepository

/**
 * That service contains most of the logic to merge
 * interval of same criticity together.
 */
class ReportsExecutionService (
    reportsRepository      : ReportsRepository
  , readExecutions         : RoReportsExecutionRepository
  , writeExecutions        : WoReportsExecutionRepository
  , statusUpdateRepository : StatusUpdateRepository
  , maxDays                : Int // in days
) extends Loggable {

  def findEndReport(report : Reports) : Boolean = {
    report.directiveId.value == "common-root" && report.component == "common" && report.keyValue == "EndRun"
  }
  def findAndSaveExecutions = {

    // Get execution status
    statusUpdateRepository.getExecutionStatus match {
      // Find it, start looking for new executions
      case Full(Some((lastReportId,lastReportDate))) =>

        // Get reports of the last id and before last report date plus maxDays
        reportsRepository.getReportsfromId(lastReportId, lastReportDate plusDays(maxDays)) match {
          case Full(reportsWithIndex) =>
            val reports = reportsWithIndex.map(_._1)
            val reportExec : Seq[ReportExecution] =
              ( for {
                  ((nodeId,date), reports) <- reports.groupBy(report => (report.nodeId,report.executionTimestamp))
                } yield {

                  if (reports.exists(findEndReport)){
                    ReportExecution(nodeId,date,true)
                  } else {
                    logger.warn("not complete")
                    ReportExecution(nodeId,date,false)
                } }
              ).toSeq
            // Check if there is no interesting reports
            if (!reportsWithIndex.isEmpty) {
              // Save new executions
              writeExecutions.saveExecutions(reportExec) match {
                case Full(result) =>
                  logger.debug(s"Saved ${result.size} executions")
                  val lastReport = reportsWithIndex.maxBy(_._2)
                  statusUpdateRepository.setExecutionStatus(lastReport._2, lastReport._1.executionTimestamp)

                case eb:EmptyBox => val fail = eb ?~! "could not save reports executions"
                  logger.error(s"could not save reports execution cause is : ${fail.messageChain}")
              }

            } else {
              logger.debug("No reports to aggregate ")
              statusUpdateRepository.setExecutionStatus(lastReportId, lastReportDate plusDays maxDays)
            }

          case eb:EmptyBox =>
            val fail = eb ?~! "could not get Reports"
            logger.error(s"could not get reports cause is: ${fail.messageChain}")
        }

      // Executions status not initialized ... initialize it!
      case Full(None) =>
        reportsRepository.getReportsWithLowestId match {
          case Full(Some((report,id))) =>
            statusUpdateRepository.setExecutionStatus(id, report.executionTimestamp)
          case Full(None) =>
            logger.debug("no reports in database, could not start aggregation for now")
          case eb:EmptyBox =>
            val fail = eb ?~! "could not get Reports with lowest id"
            logger.error(s"could not get update aggregation status cause is: ${fail.messageChain}")
        }
      case eb:EmptyBox =>
        val fail = eb ?~! "could not get execution status"
        logger.error(s"could not find reports executions cause is: ${fail.messageChain}")


    }


} }