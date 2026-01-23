/*
 *************************************************************************************
 * Copyright 2020 Normation SAS
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

package bootstrap.liftweb.checks.endconfig.action

import bootstrap.liftweb.BootstrapChecks
import bootstrap.liftweb.BootstrapLogger
import com.normation.box.*
import com.normation.errors.*
import com.normation.rudder.db.Doobie
import com.normation.rudder.domain.logger.ReportLoggerPure
import com.normation.rudder.facts.nodes.NodeFactRepository
import com.normation.rudder.services.reports.CacheComplianceQueueAction.ExpectedReportAction
import com.normation.rudder.services.reports.CacheExpectedReportAction.InsertNodeInCache
import com.normation.rudder.services.reports.ComputeNodeStatusReportService
import com.normation.rudder.services.reports.NodeStatusReportRepositoryImpl
import com.normation.rudder.tenants.QueryContext
import doobie.implicits.*
import jakarta.servlet.UnavailableException
import net.liftweb.common.EmptyBox
import zio.interop.catz.*

/**
 * At startup, we preload node compliance cache
 */
class LoadNodeComplianceCache(
    nodeStatusReportRepositoryImpl: NodeStatusReportRepositoryImpl,
    nodeFactRepository:             NodeFactRepository,
    computeNodeStatusReportService: ComputeNodeStatusReportService,
    doobie:                         Doobie
) extends BootstrapChecks {

  override val description = "Initialize node compliance cache"

  @throws(classOf[UnavailableException])
  override def checks(): Unit = {
    (for {
      isInDB <- isComplianceInDB()
      _      <- if (isInDB) {
                  BootstrapLogger.info(s"Table 'NodeLastCompliance' is in used, loading past compliance from it") *>
                  loadFromDB()
                } else {
                  BootstrapLogger.info(s"Table 'NodeLastCompliance' is empty, compute last compliance from last available runs") *>
                  computeNewCompliance()
                }
    } yield ()).toBox match {
      case eb: EmptyBox =>
        val err = eb ?~! s"Error when loading node compliance cache:"
        BootstrapLogger.warn(err.messageChain)
      case _ =>
      // ok
    }
  }

  // test if there is a least one report in base
  def isComplianceInDB(): IOResult[Boolean] = {
    import doobie.*
    val q = sql"select exists (select * from NodeLastCompliance limit 1)"
    transactIOResult(s"error when checking if compliance data exists")(xa => q.query[Boolean].unique.transact(xa))
  }

  // load from database
  def loadFromDB(): IOResult[Unit] = {
    nodeStatusReportRepositoryImpl.init()
  }

  // compute from last available runs, for example for migration
  def computeNewCompliance(): IOResult[Unit] = {
    for {
      nodeIds <- nodeFactRepository.getAll()(using QueryContext.systemQC).map(_.keys)
      _       <- ReportLoggerPure.Repository.debug(s"Initialize node status reports for ${nodeIds.size} nodes")
      _       <- computeNodeStatusReportService.invalidateWithAction(
                   nodeIds.toSeq.map(x => (x, ExpectedReportAction(InsertNodeInCache(x))))
                 )
    } yield ()
  }

}
