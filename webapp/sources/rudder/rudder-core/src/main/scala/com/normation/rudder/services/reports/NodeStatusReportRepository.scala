/*
 *************************************************************************************
 * Copyright 2024 Normation SAS
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

package com.normation.rudder.services.reports

import com.normation.errors.*
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.logger.ReportLoggerPure
import com.normation.rudder.domain.reports.NodeStatusReport
import com.normation.rudder.domain.reports.RunAnalysisKind
import com.normation.rudder.facts.nodes.ChangeContext
import com.normation.rudder.facts.nodes.QueryContext
import com.softwaremill.quicklens.ModifyPimp
import scala.annotation.nowarn
import zio.{System as _, *}

/*
 * The role of that repository is just to be able to save and retrieve NodeStatusReports in
 * an efficient and durable way.
 * The naive implementation is not durable and is just a map.
 * The real implementation is using a database to persist reports, and a cache to answer
 * quickly.
 */

trait NodeStatusReportRepository {

  /*
   * Get all known NodeStatusReport
   */
  def getAll()(implicit qc: QueryContext): IOResult[Map[NodeId, NodeStatusReport]]

  /*
   * Get NodeStatusReports for node ids given in parameter.
   */
  def getNodeStatusReports(nodeIds: Set[NodeId])(implicit qc: QueryContext): IOResult[Map[NodeId, NodeStatusReport]]

  /*
   * Save given NodeStatusReports.
   * Return the UPDATED status reports, which may be different from the
   * ones given as argument (in particular for the case "KeepLastCompliance" state
   */
  def saveNodeStatusReports(reports: Iterable[(NodeId, NodeStatusReport)])(implicit
      cc: ChangeContext
  ): IOResult[Chunk[(NodeId, NodeStatusReport)]]

  /*
   * Delete status reports corresponding to the given nodes. If no report matches a node id,
   * the operation is a no-op.
   */
  def deleteNodeStatusReports(nodeIds: Chunk[NodeId])(implicit cc: ChangeContext): IOResult[Unit]
}

/*
 * Dummy class for tests:
 *   - in memory
 *   - doesn't use Query/ChangeContext
 */
class DummyNodeStatusReportRepository(
    initialReports: Ref[Map[NodeId, NodeStatusReport]]
) extends NodeStatusReportRepository {

  val cache: Ref[Map[NodeId, NodeStatusReport]] = initialReports

  @nowarn
  override def getAll()(implicit qc: QueryContext): IOResult[Map[NodeId, NodeStatusReport]] = {
    cache.get
  }

  override def getNodeStatusReports(nodeIds: Set[NodeId])(implicit qc: QueryContext): IOResult[Map[NodeId, NodeStatusReport]] = {
    cache.get.map(_.filter(x => nodeIds.contains(x._1)))
  }

  override def saveNodeStatusReports(
      reports: Iterable[(NodeId, NodeStatusReport)]
  )(implicit cc: ChangeContext): IOResult[Chunk[(NodeId, NodeStatusReport)]] = {
    ReportLoggerPure.Repository.debug(
      s"Add NodeStatusReports to repository for nodes: [${reports.map(_._1.value).mkString(", ")}]"
    ) *>
    cache.modify { m =>
      // we need to take special care for node with "keep compliance"
      val newOrUpdated = reports.flatMap {
        case (id, report) =>
          report.runInfo.kind match {
            // if we are asked to keep compliance, start by checking we do have one
            case RunAnalysisKind.KeepLastCompliance =>
              m.get(id) match {
                case Some(e) =>
                  e.runInfo.kind match {
                    case RunAnalysisKind.ComputeCompliance => // keep existing compliance, change run info
                      Some(
                        (
                          id,
                          e.modify(_.runInfo.lastRunExpiration)
                            .setTo(report.runInfo.lastRunExpiration)
                            .modify(_.runInfo.lastRunDateTime)
                            .setTo(report.runInfo.lastRunDateTime)
                            .modify(_.runInfo.lastRunConfigId)
                            .setTo(report.runInfo.lastRunConfigId)
                        )
                      )
                    case _                                 => // in any other case, change nothing but check if there's an actual change
                      if (e == report) None else Some((id, e))
                  }
                case None    => // ok, just a new report
                  Some((id, report))
              }
            // in other case, just update what is in cache if it's actually different
            case _                                  =>
              m.get(id) match {
                case Some(e) => if (e == report) None else Some((id, report))
                case None    => Some((id, report))
              }
          }
      }
      (Chunk.fromIterable(newOrUpdated), m ++ newOrUpdated)
    }
  }

  override def deleteNodeStatusReports(nodeIds: Chunk[NodeId])(implicit cc: ChangeContext): IOResult[Unit] = {
    ReportLoggerPure.Repository.debug(
      s"Remove NodeStatusReports from repository for nodes: [${nodeIds.map(_.value).mkString(", ")}]"
    ) *>
    cache.update(_ -- nodeIds)
  }
}
