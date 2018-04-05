/*
*************************************************************************************
* Copyright 2016 Normation SAS
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

package com.normation.rudder.repository.jdbc


import net.liftweb.common.Box
import com.normation.rudder.domain.reports.NodeStatusReport
import com.normation.rudder.repository.ComplianceRepository
import com.normation.rudder.db.Doobie
import com.normation.rudder.db.Doobie._
import scalaz.{Failure => _, _}, Scalaz._
import doobie.imports._
import com.normation.rudder.services.reports._
import com.normation.inventory.domain.NodeId
import org.joda.time.DateTime
import com.normation.rudder.domain.reports.RunComplianceInfo
import com.normation.rudder.domain.reports.AggregatedStatusReport
import com.normation.rudder.domain.reports.CompliancePercent


final case class RunCompliance(
    nodeId      : NodeId
  , runTimestamp: DateTime
  , endOfLife   : DateTime
  , runInfo     : (RunAndConfigInfo, RunComplianceInfo)
  , summary     : CompliancePercent
  , details     : AggregatedStatusReport
)

object RunCompliance {

  def from(runTimestamp: DateTime, endOfLife: DateTime, report: NodeStatusReport) = {
    RunCompliance(report.nodeId, runTimestamp, endOfLife, (report.runInfo, report.statusInfo), report.compliance.pc, report.report)
  }

}

class ComplianceJdbcRepository(doobie: Doobie) extends ComplianceRepository {
  import doobie._

  /*
   * Save a list of node compliance reports
   */
  override def saveRunCompliance(reports: List[NodeStatusReport]): Box[List[NodeStatusReport]] = {
    /*
     * some sorting of things. We must only store information about node status reports with
     * a run
     */
    val runCompliances = reports.flatMap { r => r.runInfo match {
      //ignore case with no runs
      case _:NoReportInInterval |
           _:NoRunNoExpectedReport |
           _:ReportsDisabledInInterval => None

      case x:Pending => x.optLastRun match {
        case None =>
          None
        case Some((runTime, expected)) =>
          Some(RunCompliance.from(runTime, x.expirationDateTime, r))
      }

      case x:NoExpectedReport =>
        // here, the expiration date has not much meaning, since we don't have
        // information on that node configuration (and so the node has most likelly no
        // idea whatsoever of any config, even global). Take default values,
        // ie 5min for run + 5min for grace
        Some(RunCompliance.from(x.lastRunDateTime, x.lastRunDateTime.plusMinutes(10), r))
      case x:UnexpectedVersion =>
        Some(RunCompliance.from(x.lastRunDateTime, x.lastRunExpiration, r))
      case x:UnexpectedNoVersion =>
        Some(RunCompliance.from(x.lastRunDateTime, x.lastRunExpiration, r))
      case x:UnexpectedUnknowVersion =>
        // same has for NoExpectedReport, we can't now what the node
        // thing its configuration is.
        Some(RunCompliance.from(x.lastRunDateTime, x.lastRunDateTime.plusMinutes(10), r))
      case x:ComputeCompliance =>
        Some(RunCompliance.from(x.lastRunDateTime, x.expirationDateTime, r))

    } }

    //now, save everything

    (for {
      updated  <- Update[RunCompliance](
                    """insert into nodecompliance (nodeid, runtimestamp, endoflife, runanalysis, summary, details)
                       values (?, ?, ?, ?, ?, ?)
                    """
                  ).updateMany(runCompliances)
    } yield {
      val saved = runCompliances.map(_.nodeId)
      reports.filter(r => saved.contains(r.nodeId))
    }).attempt.transact(xa).unsafePerformSync  }

}
