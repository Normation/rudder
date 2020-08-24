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


import com.normation.inventory.domain.NodeId
import com.normation.rudder.batch.FindNewReportsExecution
import com.normation.rudder.domain.logger.ReportLogger
import com.normation.rudder.repository.ReportsRepository
import com.normation.rudder.services.reports.CachedFindRuleNodeStatusReports
import com.normation.rudder.services.reports.CachedNodeChangesServiceImpl
import com.normation.utils.Control
import org.joda.time.DateTime
import org.joda.time.format.PeriodFormat
import net.liftweb.common._
import com.normation.rudder.db.DB
import com.normation.rudder.repository.ComplianceRepository
import com.normation.errors._

import scala.concurrent.duration.FiniteDuration

import com.normation.zio._

// message for the queue: what nodes were updated?
final case class InvalidateComplianceCacheMsg(updatedNodeIds: Set[NodeId])

/**
 * That service contains most of the logic to merge
 * interval of same criticity together.
 */
class ReportsExecutionService (
    reportsRepository      : ReportsRepository
  , writeExecutions        : WoReportsExecutionRepository
  , statusUpdateRepository : LastProcessedReportRepository
  , cachedChanges          : CachedNodeChangesServiceImpl
  , cachedCompliance       : CachedFindRuleNodeStatusReports
  , complianceRepos        : ComplianceRepository
  , catchupFromDuration    : FiniteDuration
  , catchupInterval        : FiniteDuration
) {

  val logger = ReportLogger
  var idForCheck: Long = 0

  private[this] def computeCatchupEndTime(catchupFromDateTime: DateTime) : DateTime= {
    // Get reports of the last id and before last report date plus the maxCatchup interval
    if (catchupFromDateTime.plus(catchupInterval.toMillis).isAfter(DateTime.now)) {
      DateTime.now
    } else {
      catchupFromDateTime.plus(catchupInterval.toMillis)
    }
  }

  // This is much simpler than before:
  // * get unprocessed reports
  // * process them
  // * mark them as processed
  // * save compliance
  def findAndSaveExecutions(processId : Long): Box[Option[DB.StatusUpdate]] = {
    val startTime = DateTime.now()
    statusUpdateRepository.getExecutionStatus match {
      // Find it, start looking for new executions
      case Full(Some((lastReportId,lastReportDate))) =>
        if(idForCheck != 0 && lastReportId != idForCheck) {
          logger.error(s"There is an inconsistency in the processed agent runs: last process report id should be ${idForCheck} " +
            s"but the value ${lastReportId} was retrieve from base. Check that you don't have several Rudder application " +
            s"using the same database, or report that message to you support")
        }

        // then find last reports id
        reportsRepository.getHighestId() match {
          case eb:EmptyBox =>
            val fail = eb ?~! "Could not get Reports with highest id from the RudderSysEvents table"
            logger.error(s"Could not get reports from the database, cause is: ${fail.messageChain}")
            fail
          case Full(maxReportId) =>
            // ok, we'll manage reports from lastReportId to maxReportId - and this is only useful for changes

            fetchRunsAndCompliance()

            hookForChanges(lastReportId, maxReportId)

            val executionTime = DateTime.now().getMillis() - startTime.getMillis
            logger.debug(s"[${FindNewReportsExecution.SERVICE_NAME} #${processId}] (${executionTime} ms) " +
              s"Added or updated xxxxx agent runs")
            idForCheck = maxReportId
            statusUpdateRepository.setExecutionStatus(maxReportId, startTime).map( Some(_) )


        }

      // Executions status not initialized ... initialize it!
      case Full(None) =>
        reportsRepository.getReportsWithLowestId match {
          case Full(Some((id, report))) =>
            logger.debug(s"Initializing the status execution update to  id ${id}, date ${report.executionTimestamp}")
            idForCheck = id
            statusUpdateRepository.setExecutionStatus(id, report.executionTimestamp).map( Some(_) )
          case Full(None) =>
            logger.debug("There are no node execution in the database, cannot save the execution")
            Full( None )
          case eb:EmptyBox =>
            val fail = eb ?~! "Could not get Reports with lowest id from the RudderSysEvents table"
            logger.error(s"Could not get reports from the database, cause is: ${fail.messageChain}")
            fail
        }
      case eb:EmptyBox =>
        val fail = eb ?~! "Could not get node execution status"
        logger.error(s"Could not get node executions reports from the RudderSysEvents table  cause is: ${fail.messageChain}")
        fail
    }
  }

  def fetchRunsAndCompliance() = {
    // fetch unprocessed run
    // process
    // update cache
    // profit
    val startCompliance = System.currentTimeMillis

    val updateCompliance = {
      for {
        nodeWithCompliances <- cachedCompliance.findUncomputedNodeStatusReports().toIO
        invalidate          <- cachedCompliance.invalidateWithCompliance(nodeWithCompliances.map { case (nodeid, compliance) => (nodeid, Some(compliance))}.toSeq)
        _                   <- complianceRepos.saveRunCompliance(nodeWithCompliances.values.toList)
      } yield ()
    }
    updateCompliance.runNow

    import org.joda.time.Duration
    logger.debug(s"Computing compliance in : ${PeriodFormat.getDefault().print(Duration.millis(System.currentTimeMillis - startCompliance).toPeriod())}")
  }

  /*
  * The hook method where the other method needing to happen when
  * new reports are processed are called.
  */
  private[this] def hookForChanges(lowestId: Long, highestId: Long) : Unit = {
    val startHooks = System.currentTimeMillis

    // notify changes updates
    cachedChanges.update(lowestId, highestId)

    import org.joda.time.Duration
    logger.debug(s"Hooks from changes updates time: ${PeriodFormat.getDefault().print(Duration.millis(System.currentTimeMillis - startHooks).toPeriod())}")

  }
}
