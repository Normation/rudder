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
import org.joda.time.DateTime

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


  def findAndSaveExecutions = {

    // Get execution status
    statusUpdateRepository.getExecutionStatus match {
      // Find it, start looking for new executions
      case Full(Some((lastReportId,lastReportDate))) =>
        logger.info(s"Starting to fetch Nodes Executions from report ID ${lastReportId} - date from ${lastReportDate} to ${lastReportDate plusDays(maxDays)}")

        // Get reports of the last id and before last report date plus maxDays
        val endBatchDate = if (lastReportDate.plusDays(maxDays).isAfter(DateTime.now)) {
                              DateTime.now
                            } else {
                              lastReportDate.plusDays(maxDays)
                            }

        reportsRepository.getReportsfromId(lastReportId, endBatchDate) match {
          case Full((reportExec, maxReportId)) =>
            if (reportExec.size > 0) {
              val maxDate = reportExec.maxBy(_.date.getMillis()).date
              // Save new executions
              writeExecutions.updateExecutions(reportExec) match {
                case Full(result) =>
                  logger.info(s"Saved ${result.size} execution of nodes, up to the ID ${maxReportId} and date ${maxDate}")
                  statusUpdateRepository.setExecutionStatus(maxReportId, maxDate)

                case eb:EmptyBox => val fail = eb ?~! "could not save nodes executions"
                  logger.error(s"Could not save execution of Nodes from report ID ${lastReportId} - date ${lastReportDate} to ${lastReportDate plusDays(maxDays)}, cause is : ${fail.messageChain}")
              }

            } else {
              logger.info("There are no nodes executions to store")
              logger.debug(s"Storing date ${endBatchDate} for next upper limits")
              statusUpdateRepository.setExecutionStatus(lastReportId, endBatchDate)
            }

          case eb:EmptyBox =>
            val fail = eb ?~! "could not get Reports"
            logger.error(s"Could not get node execution reports in the RudderSysEvents table, cause is: ${fail.messageChain}")
        }

      // Executions status not initialized ... initialize it!
      case Full(None) =>
        reportsRepository.getReportsWithLowestId match {
          case Full(Some((report,id))) =>
            logger.debug(s"Initializing the status execution update to  id ${id}, date ${report.executionTimestamp}")
            statusUpdateRepository.setExecutionStatus(id, report.executionTimestamp)
          case Full(None) =>
            logger.debug("There are no node execution in the database, cannot save the execution")
          case eb:EmptyBox =>
            val fail = eb ?~! "Could not get Reports with lowest id from the RudderSysEvents table"
            logger.error(s"Could not get reports from the database, cause is: ${fail.messageChain}")
        }
      case eb:EmptyBox =>
        val fail = eb ?~! "Could not get node execution status"
        logger.error(s"Could not get node executions reports from the RudderSysEvents table  cause is: ${fail.messageChain}")


    }


} }