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

import com.normation.errors._
import com.normation.errors.IOResult
import com.normation.rudder.domain.reports.Reports
import com.normation.rudder.repository.ReportsRepository
import com.normation.rudder.repository.RudderPropertiesRepository
import zio._

trait JSONReportsHandler {
  def handle: PartialFunction[Reports, IOResult[Reports]]
}

case class JSONReportsAnalyser(reportsRepository: ReportsRepository, propRepo: RudderPropertiesRepository) {

  private[this] var handlers: List[JSONReportsHandler] = Nil

  def addHandler(handler: JSONReportsHandler) = handlers = handler :: handlers

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
  def loop = {
    val highestId = reportsRepository.getHighestId().getOrElse(0L)
    for {
      optLowerId <- propRepo.getReportHandlerLastId
      lowerId     = optLowerId.getOrElse(highestId)
      reports    <- reportsRepository.getReportsByKindBetween(lowerId, None, 1000, List(Reports.REPORT_JSON)).toIO

      _ <- reports.maxByOption(_._1) match {
             case None    =>
               propRepo.updateReportHandlerLastId(highestId)
             case Some(r) =>
               ZIO.foreach(reports)(r => handle(r._2)).catchAll(err => CampaignLogger.error(err.fullMsg)) *>
               propRepo.updateReportHandlerLastId((r._1 + 1))
           }
    } yield {
      ()
    }
  }

  /*
   * start the handler process. It will execute at provided intervals
   */
  def start(interval: Duration) = {
    loop.delay(interval).forever
  }

}
