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

import cats.data.NonEmptyList
import com.normation.errors.*
import com.normation.inventory.domain.NodeId
import com.normation.rudder.db.Doobie
import com.normation.rudder.db.Doobie.*
import com.normation.rudder.domain.logger.ComplianceLoggerPure
import com.normation.rudder.domain.logger.ReportLoggerPure
import com.normation.rudder.domain.reports.JsonPostgresqlSerialization.JNodeStatusReport
import com.normation.rudder.domain.reports.NodeStatusReport
import com.normation.rudder.domain.reports.RunAnalysisKind
import com.normation.rudder.tenants.ChangeContext
import com.normation.rudder.tenants.QueryContext
import com.softwaremill.quicklens.*
import doobie.*
import doobie.implicits.*
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import zio.{System as _, *}
import zio.interop.catz.*

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

trait NodeStatusReportStorage {

  def getAll(): IOResult[Map[NodeId, NodeStatusReport]]

  // this method save (add or update) these nodes with the provided status reports
  def save(reports: Iterable[(NodeId, NodeStatusReport)]): IOResult[Unit]

  // this method erase these node status reports
  def delete(nodes: Iterable[NodeId]): IOResult[Unit]
}

object NodeStatusReportRepositoryImpl {

  /*
   * Create a repo from a datastore, initially loading data from it.
   */
  def makeAndInit(storage: NodeStatusReportStorage): IOResult[NodeStatusReportRepositoryImpl] = {
    for {
      reports <- storage.getAll()
      ref     <- Ref.make(reports)
    } yield new NodeStatusReportRepositoryImpl(storage, ref)
  }

}

/*
 * Default implementation for the NodeStatusReportRepository.
 * It only relies on a local cache for access methods.
 * The cache is update along with an underlying cold storage on update/delete operation.
 */
class NodeStatusReportRepositoryImpl(
    storage:        NodeStatusReportStorage,
    initialReports: Ref[Map[NodeId, NodeStatusReport]]
) extends NodeStatusReportRepository {

  val cache: Ref[Map[NodeId, NodeStatusReport]] = initialReports

  // load data from the data storage in cache
  def init(): IOResult[Unit] = {
    for {
      reports <- storage.getAll()
      _       <- cache.set(reports)
    } yield ()
  }

  override def getAll()(implicit qc: QueryContext): IOResult[Map[NodeId, NodeStatusReport]] = {
    cache.get
  }

  override def getNodeStatusReports(nodeIds: Set[NodeId])(implicit qc: QueryContext): IOResult[Map[NodeId, NodeStatusReport]] = {
    cache.get.map(_.filter(x => nodeIds.contains(x._1)))
  }

  override def saveNodeStatusReports(
      reports: Iterable[(NodeId, NodeStatusReport)]
  )(implicit cc: ChangeContext): IOResult[Chunk[(NodeId, NodeStatusReport)]] = {
    for {
      _       <- ReportLoggerPure.Repository.debug(
                   s"Add NodeStatusReports to repository for nodes: [${reports.map(_._1.value).mkString(", ")}]"
                 )
      updated <- cache.modify { m =>
                   // we need to take special care for node with "keep compliance"
                   val newOrUpdated = reports.flatMap {
                     case (id, report) =>
                       report.runInfo.kind match {
                         // if we are asked to keep compliance, start by checking we do have one
                         case RunAnalysisKind.KeepLastCompliance =>
                           m.get(id) match {
                             case Some(cached) =>
                               cached.runInfo.kind match { // here we need to check for all expiring status from `def outDatedCompliance`
                                 case RunAnalysisKind.ComputeCompliance | RunAnalysisKind.Pending =>
                                   // For ComputeCompliance: it's the standard case of keeping existing compliance.
                                   // If the saved node is pending, it means that we are in a case where the node never saved
                                   // anything else than pending since it was first processed, so the node never had a computed
                                   // compliance (it was a new report, directly in pending). Let it blue.
                                   // In both case: keep existing compliance, change run info to avoid loop.

                                   // we need to copy new report information BUT NOT the one regarding "last run",
                                   // which were lost if we reach that point
                                   val runInfo = report.runInfo.copyLastRunInfo(cached.runInfo)
                                   Some((id, cached.modify(_.runInfo).setTo(runInfo)))

                                 // comparison for KeepLastCompliance: we need to check if configId/things not linked to
                                 // last run changed, perhaps during a reboot, and update them if it's the case
                                 case RunAnalysisKind.KeepLastCompliance                          =>
                                   if (cached.runInfo.equalsWithoutLastRun(report.runInfo)) None
                                   else {
                                     // keep last run from cache, but update other runInfo data
                                     val newRunInfo = report.runInfo.copyLastRunInfo(cached.runInfo)
                                     Some((id, cached.modify(_.runInfo).setTo(newRunInfo)))
                                   }

                                 case _ => // in any other case, change nothing but check if there's an actual change
                                   if (cached == report) None else Some((id, cached))
                               }
                             case None         => // ok, just a new report
                               Some((id, report))
                           }
                         // in other case, just update what is in cache if it's actually different
                         case _                                  =>
                           m.get(id) match {
                             case Some(e) =>
                               if (e == report) None else Some((id, report))
                             case None    => Some((id, report))
                           }
                       }
                   }
                   (Chunk.fromIterable(newOrUpdated), m ++ newOrUpdated)
                 }
      _       <- storage.save(updated)
    } yield updated
  }

  override def deleteNodeStatusReports(nodeIds: Chunk[NodeId])(implicit cc: ChangeContext): IOResult[Unit] = {
    for {
      _ <- ReportLoggerPure.Repository.debug(
             s"Remove NodeStatusReports from repository for nodes: [${nodeIds.map(_.value).mkString(", ")}]"
           )
      _ <- cache.update(_ -- nodeIds)
      _ <- storage.delete(nodeIds)
    } yield ()
  }
}

/*
 * Dummy, in memory storage. For test.
 */
class InMemoryNodeStatusReportStorage(storage: Ref[Map[NodeId, NodeStatusReport]]) extends NodeStatusReportStorage {
  override def getAll(): IOResult[Map[NodeId, NodeStatusReport]] = {
    storage.get
  }

  override def save(reports: Iterable[(NodeId, NodeStatusReport)]): IOResult[Unit] = {
    storage.update(_ ++ reports)
  }

  override def delete(nodeIds: Iterable[NodeId]): IOResult[Unit] = {
    storage.update(_ -- nodeIds)
  }
}

/*
 * What we save in base is segmented by policy type if
 */

/*
 * A JDBC implementation using the `NodeLastCompliance` table as backend
 */
class JdbcNodeStatusReportStorage(doobie: Doobie, jdbcBatchSize: Int) extends NodeStatusReportStorage {
  import doobie.*

  override def getAll(): IOResult[Map[NodeId, NodeStatusReport]] = {
    val query = sql"""select nodeid, details from NodeLastCompliance"""

    ComplianceLoggerPure.debug(s"Get all compliance from base") *>
    transactIOResult(s"error when getting save compliance for nodes")(xa =>
      query.query[(NodeId, JNodeStatusReport)].to[Chunk].transact(xa)
    ).map(_.map { case (id, d) => (id, d.to) }.toMap)
  }

  override def save(reports: Iterable[(NodeId, NodeStatusReport)]): IOResult[Unit] = {
    val t = DateTime.now(DateTimeZone.UTC)

    def toRows(rs: Iterable[(NodeId, NodeStatusReport)]): Vector[(NodeId, DateTime, JNodeStatusReport)] = {
      rs.map { case (a, b) => (a, t, JNodeStatusReport.from(b)) }.toVector
    }

    val query = Update[(NodeId, DateTime, JNodeStatusReport)](
      """INSERT INTO NodeLastCompliance (nodeId, computationDateTime, details) VALUES (?,?,?)
        |  ON CONFLICT (nodeId) DO UPDATE
        |  SET computationDateTime = excluded.computationDateTime, details = excluded.details ;""".stripMargin
    )

    // batch update, don't fail on first error
    ZIO
      .validate(reports.sliding(jdbcBatchSize).to(Iterable)) { rs =>
        val rows = toRows(rs)
        ComplianceLoggerPure.debug(s"Saving compliance state for ${rs.size} nodes in base") *>
        transactIOResult(s"error when saving compliance for nodes")(xa => query.updateMany(rows).transact(xa))
      }
      .mapError(errs => Accumulated(NonEmptyList(errs.head, errs.tail)))
      .unit
  }

  override def delete(nodes: Iterable[NodeId]): IOResult[Unit] = {
    val query = Update[NodeId]("delete from NodeLastCompliance where nodeId = ?").updateMany(nodes.toVector)

    ComplianceLoggerPure.debug(s"Deleting compliance state for ${nodes.size} nodes from base") *>
    transactIOResult(s"error when saving compliance for nodes")(xa => query.transact(xa)).unit
  }

}
