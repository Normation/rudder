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

import org.joda.time.DateTime
import com.normation.rudder.batch.FindNewReportsExecution
import com.normation.rudder.reports.statusUpdate.StatusUpdateRepository
import com.normation.rudder.repository.ReportsRepository
import net.liftweb.common._
import com.normation.rudder.domain.logger.ReportLogger
import com.normation.rudder.services.reports.CachedNodeChangesServiceImpl
import scala.concurrent._
import ExecutionContext.Implicits.global
import scala.util.Success

/**
 * That service contains most of the logic to merge
 * interval of same criticity together.
 */
class ReportsExecutionService (
    reportsRepository      : ReportsRepository
  , writeExecutions        : WoReportsExecutionRepository
  , statusUpdateRepository : StatusUpdateRepository
  , cachedChanges          : CachedNodeChangesServiceImpl
  , maxDays                : Int // in days
) {

  val format = "yyyy/MM/dd HH:mm:ss"

  val logger = ReportLogger

  def findAndSaveExecutions(processId : Long) = {
    val startTime = DateTime.now().getMillis()
    // Get execution status
    statusUpdateRepository.getExecutionStatus match {
      // Find it, start looking for new executions
      case Full(Some((lastReportId,lastReportDate))) =>

        // Get reports of the last id and before last report date plus maxDays
        val endBatchDate = if (lastReportDate.plusDays(maxDays).isAfter(DateTime.now)) {
                              DateTime.now
                            } else {
                              lastReportDate.plusDays(maxDays)
                            }

        logger.debug(s"[${FindNewReportsExecution.SERVICE_NAME}] Task #${processId}: Starting analysis for run times from ${lastReportDate.toString(format)} up to ${endBatchDate.toString(format)} (runs after SQL table ID ${lastReportId})")

        reportsRepository.getReportsfromId(lastReportId, endBatchDate) match {
          case Full((reportExec, maxReportId)) =>
            if (reportExec.size > 0) {

              val maxDate = {
                // Keep the last report date if the last processed report is after all reports processed in this batch
                val maxReportsDate = reportExec.maxBy(_.agentRunId.date.getMillis()).agentRunId.date
                if (maxReportsDate isAfter lastReportDate) {
                  maxReportsDate
                } else {
                  lastReportDate
                }
              }


              /*
               * here is a hook to plug the update for changes.
               * it should be made generic and inverted with some hooks method defined in
               * that class (giving to caller the lowed and highest index of reports to
               * take into account) BUT: these users will be extremely hard on the DB
               * performance, most likelly each of them doing a query. Of course, it is
               * much more interesting to mutualise the queries (and if possible, make
               * them asynchrone from the main current loop), what is much harder with
               * an IoC pattern.
               * So, until we have a better view of what the actual user are, I let that
               * here.
               */
              this.asyncHook(lastReportId, maxReportId)
              // end of hooks code

              // Save new executions
              writeExecutions.updateExecutions(reportExec) match {
                case Full(result) =>
                  val executionTime = DateTime.now().getMillis() - startTime
                  logger.debug(s"[${FindNewReportsExecution.SERVICE_NAME}] Task #${processId}: Finished analysis in ${executionTime} ms. Added or updated ${result.size} agent runs, up to SQL table ID ${maxReportId} (last run time was ${maxDate.toString(format)})")
                  statusUpdateRepository.setExecutionStatus(maxReportId, maxDate)

                case eb:EmptyBox => val fail = eb ?~! "could not save nodes executions"
                  logger.error(s"Could not save execution of Nodes from report ID ${lastReportId} - date ${lastReportDate} to ${(lastReportDate plusDays(maxDays)).toString(format)}, cause is : ${fail.messageChain}")
              }

            } else {
              logger.debug("There are no nodes executions to store")
              val executionTime = DateTime.now().getMillis() - startTime
              logger.debug(s"[${FindNewReportsExecution.SERVICE_NAME}] Task #${processId}: Finished analysis in ${executionTime} ms. Added or updated 0 agent runs (up to SQL table ID ${maxReportId})")
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


  }

  /*
   * The hook method where the other method needing to happen when
   * new reports are processed are called.
   */
  private[this] def asyncHook(lowestId: Long, highestId: Long) : Unit = {
    //for now, just update the list of changes by rule
    future {
      for {
        changes <- reportsRepository.getChangeReportsOnInterval(lowestId, highestId)
        updated <- cachedChanges.update(changes)
      } yield {
        updated
      }
    }.onComplete {
      case Success(x) => x match {
        case eb: EmptyBox =>
          val e = eb ?~! "An error occured when trying to update the cache of last changes"
          logger.error(e.messageChain)
          e.rootExceptionCause.foreach { ex =>
            logger.error("Root exception was: ", ex)
          }
        case Full(x) => //youhou
      }
      case scala.util.Failure(ex) =>
        logger.error("An error occured when trying to update the cache of last changes", ex)
    }
  }

}
