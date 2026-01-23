/*
 *************************************************************************************
 * Copyright 2011 Normation SAS
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
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.PolicyTypeName
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.reports.*
import com.normation.rudder.domain.reports.NodeStatusReport.*
import com.normation.rudder.tenants.QueryContext
import zio.{System as _, *}

object ReportingServiceUtils {

  def log(msg: String): ZIO[Any, Nothing, Unit] = ZIO.succeed(println(msg)) // you actual log lib
  val effect: Task[Nothing] = ZIO.attempt(
    throw new RuntimeException("I'm some impure code!")
  ) // here, exception is caught and you get a ZIO[Any, Throwable, Something]
  val withLogError: ZIO[Any, Throwable, Nothing] =
    effect.flatMapError(exception => log(exception.getMessage) *> ZIO.succeed(exception))

}

/*
 * A simple implementation of reporting service that just uses an underlying NodeStatusReport
 * repository to get reports, and then do basic filtering/computation on them.
 */
class ReportingServiceImpl2(nsrRepo: NodeStatusReportRepository) extends ReportingService {

  override def findRuleNodeStatusReports(nodeIds: Set[NodeId], filterByRules: Set[RuleId])(implicit
      qc: QueryContext
  ): IOResult[Map[NodeId, NodeStatusReport]] = {
    nsrRepo.getNodeStatusReports(nodeIds).map(ReportingService.filterReportsByRules(_, filterByRules))
  }

  override def findDirectiveNodeStatusReports(nodeIds: Set[NodeId], filterByDirectives: Set[DirectiveId])(implicit
      qc: QueryContext
  ): IOResult[Map[NodeId, NodeStatusReport]] = {
    nsrRepo.getNodeStatusReports(nodeIds).map(ReportingService.filterReportsByDirectives(_, filterByDirectives))
  }

  override def findDirectiveRuleStatusReportsByRule(ruleId: RuleId)(implicit
      qc: QueryContext
  ): IOResult[Map[NodeId, NodeStatusReport]] = {
    nsrRepo.getAll().map(ReportingService.filterReportsByRules(_, Set(ruleId)))
  }

  override def findNodeStatusReport(nodeId: NodeId)(implicit qc: QueryContext): IOResult[NodeStatusReport] = {
    nsrRepo.getAll().flatMap(_.get(nodeId).notOptional(s"Node with id '${nodeId.value}' doesn't have a status report"))
  }

  override def findUserNodeStatusReport(nodeId: NodeId)(implicit qc: QueryContext): IOResult[NodeStatusReport] = {
    findNodeStatusReport(nodeId).map(_.forPolicyType(PolicyTypeName.rudderBase))
  }

  override def findSystemNodeStatusReport(nodeId: NodeId)(implicit qc: QueryContext): IOResult[NodeStatusReport] = {
    findNodeStatusReport(nodeId).map(_.forPolicyType(PolicyTypeName.rudderSystem))
  }

  override def getUserNodeStatusReports()(implicit qc: QueryContext): IOResult[Map[NodeId, NodeStatusReport]] = {
    nsrRepo.getAll().map(_.map(x => (x._1, x._2.forPolicyType(PolicyTypeName.rudderBase))))
  }

  override def findStatusReportsForDirective(directiveId: DirectiveId)(implicit
      qc: QueryContext
  ): IOResult[Map[NodeId, NodeStatusReport]] = {
    nsrRepo.getAll().map(ReportingService.filterReportsByDirectives(_, Set(directiveId)))
  }

  override def getSystemAndUserCompliance(optNodeIds: Option[Set[NodeId]])(implicit
      qc: QueryContext
  ): IOResult[SystemUserComplianceRun] = {
    for {
      m <- optNodeIds match {
             case Some(nodeIds) => nsrRepo.getNodeStatusReports(nodeIds)
             case None          => nsrRepo.getAll()
           }
    } yield {
      SystemUserComplianceRun.fromNodeStatusReports(m)
    }
  }

  override def getGlobalUserCompliance()(implicit qc: QueryContext): IOResult[Option[(ComplianceLevel, Long)]] = {
    for {
      reports <- getUserNodeStatusReports()
    } yield {
      ReportingService.computeComplianceFromReports(reports)
    }
  }
}
