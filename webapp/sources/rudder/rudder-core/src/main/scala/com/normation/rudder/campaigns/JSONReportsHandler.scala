/*
 *************************************************************************************
 * Copyright 2022 Normation SAS
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

package com.normation.rudder.campaigns

import com.normation.errors.*
import com.normation.rudder.domain.reports.Reports
import com.normation.rudder.repository.ReportsRepository
import com.normation.rudder.repository.RudderPropertiesRepository
import com.normation.zio.currentTimeMillis
import zio.*

trait JSONReportsHandler {
  def handle: PartialFunction[Reports, IOResult[Reports]]
}

case class JSONReportsAnalyser(reportsRepository: ReportsRepository, propRepo: RudderPropertiesRepository) {

  private var handlers: List[JSONReportsHandler] = Nil

  def addHandler(handler: JSONReportsHandler): Unit = handlers = handler :: handlers

  def handle(report: Reports): IOResult[Reports] = {

    def base: PartialFunction[Reports, IOResult[Reports]] = {
      case c =>
        for {
          _ <- CampaignLogger.warn(s"Could not handle json report with type ${c.component}")
        } yield {
          c
        }

    }

    handlers.map(_.handle).fold(base) { case (a, b) => b orElse a }(report)
  }
  def loop: IOResult[Unit] = {
    for {
      t0         <- currentTimeMillis
      highestId  <- reportsRepository.getHighestId().toIO
      t1         <- currentTimeMillis
      _          <- CampaignLogger.Reports.trace(s"Got highest id of database in ${t1 - t0} ms")
      _          <- CampaignLogger.Reports.debug(s"looking for new json report to parse")
      optLowerId <- propRepo.getReportHandlerLastId
      t2         <- currentTimeMillis
      _          <- CampaignLogger.Reports.trace(s"Got parsed id in ${t2 - t1} ms")
      lowerId     = optLowerId.getOrElse(highestId)
      _          <- CampaignLogger.Reports.debug(s"Last parsed id was ${lowerId}, max id in database is ${highestId}")
      reports    <- reportsRepository.getReportsByKindBetween(lowerId, None, 1000, List(Reports.REPORT_JSON)).toIO
      t3         <- currentTimeMillis
      _          <- CampaignLogger.Reports.trace(s"Got reports in ${t3 - t2} ms")
      _          <- reports.maxByOption(_._1) match {
                      case None              =>
                        CampaignLogger.Reports.debug(s"Found no json report, update parsed id to max id ${highestId}") *>
                        propRepo.updateReportHandlerLastId(highestId)
                      case Some(maxIdReport) =>
                        CampaignLogger.Reports.debug(
                          s"Found ${reports.size} json reports, update parsed id to max id ${maxIdReport._1 + 1}"
                        ) *>
                        ZIO.foreach(reports)(r => handle(r._2)).unit.catchAll(err => CampaignLogger.Reports.error(err.fullMsg)) *>
                        propRepo.updateReportHandlerLastId(maxIdReport._1 + 1)
                    }
      t4         <- currentTimeMillis
      _          <- CampaignLogger.Reports.trace(s"treated reports in ${t4 - t3} ms")
    } yield {
      ()
    }
  }

  /*
   * start the handler process. It will execute at provided intervals
   */
  def start(interval: Duration): UIO[Nothing] = {
    CampaignLogger.Reports.info(s"Starting campaign report handler running every ${interval.render}") *>
    loop
      .catchAllCause(cause => {
        cause.failureOrCause match {
          case Left(err)             =>
            CampaignLogger.Reports.error(s"An error occurred while handling campaign reports, error message: ${err.msg}") *>
            CampaignLogger.Reports.debug(s"Campaign report handling error details: ${err.fullMsg}")
          case Right(systemErrCause) =>
            CampaignLogger.Reports.error(
              s"An error occurred within campaign reports handling system, restarting it, details in debug"
            ) *>
            CampaignLogger.Reports.debug(s"Campaign report handling system error details: ${systemErrCause.toString}")
        }
      })
      .delay(interval)
      .forever
  }

}
