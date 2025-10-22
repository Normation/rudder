/*
 *************************************************************************************
 * Copyright 2013 Normation SAS
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

package com.normation.rudder.reports.execution

import com.normation.box.*
import com.normation.errors.*
import com.normation.inventory.domain.NodeId
import com.normation.rudder.batch.FindNewReportsExecution
import com.normation.rudder.db.DB
import com.normation.rudder.domain.logger.ReportLogger
import com.normation.rudder.domain.logger.ReportLoggerPure
import com.normation.rudder.repository.ReportsRepository
import com.normation.rudder.services.reports.CacheComplianceQueueAction.UpdateCompliance
import com.normation.rudder.services.reports.CachedNodeChangesServiceImpl
import com.normation.rudder.services.reports.ComputeNodeStatusReportService
import com.normation.rudder.services.reports.FindNewNodeStatusReports
import net.liftweb.common.*
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.joda.time.format.PeriodFormat

// message for the queue: what nodes were updated?
final case class InvalidateComplianceCacheMsg(updatedNodeIds: Set[NodeId])

/**
 * That service contains most of the logic to merge
 * interval of same criticity together.
 */
class ReportsExecutionService(
    reportsRepository:              ReportsRepository,
    statusUpdateRepository:         LastProcessedReportRepository,
    cachedChanges:                  CachedNodeChangesServiceImpl,
    computeNodeStatusReportService: ComputeNodeStatusReportService,
    findNewNodeStatusReports:       FindNewNodeStatusReports
) {

  val logger = ReportLogger
  var idForCheck: Long = 0

  // This is much simpler than before:
  // * get unprocessed reports
  // * process them
  // * mark them as processed
  // * save compliance
  def findAndSaveExecutions(processId: Long): Box[Option[DB.StatusUpdate]] = {
    val startTime = DateTime.now(DateTimeZone.UTC)
    statusUpdateRepository.getExecutionStatus match {
      // Find it, start looking for new executions
      case Full(Some((lastReportId, lastReportDate))) =>
        if (idForCheck != 0 && lastReportId != idForCheck) {
          logger.error(
            s"There is an inconsistency in the processed agent runs: last process report id should be ${idForCheck} " +
            s"but the value ${lastReportId} was retrieve from base. Check that you don't have several Rudder application " +
            s"using the same database (for example, you have several rudder archives with '.war' extension in " +
            s"/opt/rudder/share/webapp/), or report that message to your support"
          )
        }

        // then find last reports id
        reportsRepository.getHighestId() match {
          case eb: EmptyBox =>
            val fail = eb ?~! "Could not get Reports with highest id from the RudderSysEvents table"
            logger.error(s"Could not get reports from the database, cause is: ${fail.messageChain}")
            fail
          case Full(maxReportId) =>
            // the maxReportId isn't used for new reports since insert is done by relayd. It is only used
            // for the hook for computing changes in intervall

            for {
              nodes        <- fetchRunsAndCompliance().toBox
              _             = hookForChanges(lastReportId, maxReportId)
              executionTime = DateTime.now(DateTimeZone.UTC).getMillis() - startTime.getMillis
              _             = logger.debug(
                                s"[${FindNewReportsExecution.SERVICE_NAME} #${processId}] (${executionTime} ms) " +
                                s"Added or updated ${nodes.size} agent runs"
                              )
            } yield {
              nodes
            }
            idForCheck = maxReportId
            statusUpdateRepository.setExecutionStatus(maxReportId, startTime).map(Some(_))

        }

      // Executions status not initialized ... initialize it!
      case Full(None)                                 =>
        reportsRepository.getReportsWithLowestId match {
          case Full(Some((id, report))) =>
            logger.debug(s"Initializing the status execution update to  id ${id}, date ${report.executionTimestamp}")
            idForCheck = id
            statusUpdateRepository.setExecutionStatus(id, report.executionTimestamp).map(Some(_))
          case Full(None)               =>
            logger.debug("There are no node execution in the database, cannot save the execution")
            Full(None)
          case eb: EmptyBox =>
            val fail = eb ?~! "Could not get Reports with lowest id from the RudderSysEvents table"
            logger.error(s"Could not get reports from the database, cause is: ${fail.messageChain}")
            fail
        }
      case eb: EmptyBox =>
        val fail = eb ?~! "Could not get node execution status"
        logger.error(s"Could not get node executions reports from the RudderSysEvents table  cause is: ${fail.messageChain}")
        fail
    }
  }

  def fetchRunsAndCompliance(): IOResult[Seq[NodeId]] = {
    // fetch unprocessed run
    // process
    // update cache
    // profit
    import org.joda.time.Duration

    val startCompliance = System.currentTimeMillis
    for {
      nodeWithCompliances <- findNewNodeStatusReports.findUncomputedNodeStatusReports()
      _                   <- computeNodeStatusReportService.invalidateWithAction(nodeWithCompliances.map {
                               case (nodeid, compliance) => (nodeid, UpdateCompliance(nodeid, compliance))
                             }.toSeq)
      _                   <- ReportLoggerPure.Cache.debug(
                               s"Invalidated and updated compliance for nodes: [${nodeWithCompliances.map(_._1.value).mkString(", ")}]"
                             )
      _                   <- computeNodeStatusReportService.outDatedCompliance(
                               new DateTime(startCompliance, DateTimeZone.UTC),
                               nodeWithCompliances.keySet
                             )
      _                   <-
        ReportLoggerPure.Cache.debug(
          s"Computing compliance in : ${PeriodFormat.getDefault().print(Duration.millis(System.currentTimeMillis - startCompliance).toPeriod())}"
        )
    } yield {
      nodeWithCompliances.map(_._1).toSeq
    }
  }

  /*
   * The hook method where the other method needing to happen when
   * new reports are processed are called.
   */
  private def hookForChanges(lowestId: Long, highestId: Long): Unit = {
    val startHooks = System.currentTimeMillis

    // notify changes updates
    cachedChanges.update(lowestId, highestId)

    import org.joda.time.Duration
    logger.debug(
      s"Hooks from changes updates time: ${PeriodFormat.getDefault().print(Duration.millis(System.currentTimeMillis - startHooks).toPeriod())}"
    )

  }
}
