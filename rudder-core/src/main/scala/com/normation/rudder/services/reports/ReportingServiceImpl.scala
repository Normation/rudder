/*
*************************************************************************************
* Copyright 2011 Normation SAS
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

package com.normation.rudder.services.reports

import com.normation.inventory.domain.NodeId
import net.liftweb.common._
import org.joda.time._
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.reports._
import com.normation.rudder.repository._
import com.normation.rudder.reports.execution.RoReportsExecutionRepository
import com.normation.rudder.reports.ComplianceMode
import com.normation.rudder.reports.execution.AgentRunId
import com.normation.rudder.domain.reports.RuleStatusReport
import com.normation.rudder.domain.reports.NodeStatusReport
import com.normation.rudder.reports.execution.AgentRun
import com.normation.rudder.reports.ResolvedAgentRunInterval
import com.normation.rudder.reports.AgentRunIntervalService
import com.normation.rudder.domain.policies.SerialedRuleId
import com.normation.rudder.domain.logger.TimingDebugLogger


class ReportingServiceImpl(
    confExpectedRepo   : FindExpectedReportRepository
  , reportsRepository  : ReportsRepository
  , agentRunRepository : RoReportsExecutionRepository
  , nodeConfigInfoRepo : RoNodeConfigIdInfoRepository
  , runIntervalService : AgentRunIntervalService
  , getComplianceMode  : () => Box[ComplianceMode]
) extends ReportingService with Loggable {



  override def findDirectiveRuleStatusReportsByRule(ruleId: RuleId): Box[RuleStatusReport] = {
    //here, the logic is ONLY to get the node for which that rule applies and then step back
    //on the other method
    for {
      nodeIds <- confExpectedRepo.findCurrentNodeIds(ruleId)
      reports <- findRuleNodeStatusReports(nodeIds, Set(ruleId))
    } yield {
      RuleStatusReport(ruleId, reports.toIterable)
    }
  }


   def findNodeStatusReport(nodeId: NodeId) : Box[NodeStatusReport] = {
    for {
      reports <- findRuleNodeStatusReports(Set(nodeId), Set())
      report  =  reports.groupBy(_.nodeId).map { case(nodeId, reports) => NodeStatusReport(nodeId, reports) }.toSet
      result  <- if(report.size == 1) Full(report.head) else Failure("Found bad number of reports for node " + nodeId)
    } yield {
      result
    }
  }


  override def findRuleNodeStatusReports(nodeIds: Set[NodeId], ruleIds: Set[RuleId]) : Box[Set[RuleNodeStatusReport]] = {
    /*
     * This is the main logic point to get reports.
     *
     * Compliance for a given node is a function of ONLY(expectedNodeConfigId, lastReceivedAgentRun).
     *
     * The logic is:
     *
     * - for a (or n) given node (we have a node-bias),
     * - get the expected configuration right now
     *   - errors may happen if the node does not exists or if
     *     it does not have config right now. For example, it
     *     was added just a second ago.
     *     => "no data for that node"
     * - get the last run for the node.
     *
     * If nodeConfigId(last run) == nodeConfigId(expected config)
     *  => simple compare & merge
     * else {
     *   - expected reports INTERSECTION received report ==> compute the compliance on
     *      received reports (with an expiration date)
     *   - expected reports - received report ==> pending reports (with an expiration date)
     *
     * }
     *
     *
     */
    val t0 = System.currentTimeMillis
    for {
      // we want compliance on these nodes
      runInfos            <- getNodeRunInfos(nodeIds)

      // that gives us configId for runs, and expected configId (some may be in both set)
      expectedConfigId    =  runInfos.collect { case (nodeId, x:ExpectedConfigAvailable) => NodeAndConfigId(nodeId, x.expectedConfigId.configId) }
      lastrunConfigId     =  runInfos.collect {
                               case (nodeId, x:LastRunAvailable) => NodeAndConfigId(nodeId, x.lastRunConfigId.configId)
                               case (nodeId, Pending(_, Some(run), _, _)) => NodeAndConfigId(nodeId, run._2.configId)
                             }

      t1                  =  System.currentTimeMillis
      _                   =  TimingDebugLogger.debug(s"Compliance: get run infos: ${t1-t0}ms")

      // so now, get all expected reports for these config id
      allExpectedReports  <- confExpectedRepo.getExpectedReports(expectedConfigId.toSet++lastrunConfigId, ruleIds)

      t2                  =  System.currentTimeMillis
      _                   =  TimingDebugLogger.debug(s"Compliance: get expected reports: ${t2-t1}ms")

      // compute the status
      nodeStatusReports   <- buildNodeStatusReports(runInfos, allExpectedReports, ruleIds)

      t3                  =  System.currentTimeMillis
      _                   =  TimingDebugLogger.debug(s"Compliance: compute compliance reports: ${t3-t2}ms")
    } yield {
      nodeStatusReports.toSet
    }
  }

  /*
   * For each node, get the config it has.
   * This method bases its result on THE LAST RUN
   * of each node, and try to discover the run linked information (datetime, config id).
   *
   */
  private[this] def getNodeRunInfos(nodeIds: Set[NodeId]): Box[Map[NodeId, RunAndConfigInfo]] = {
    def findVersionById(id: NodeConfigId, infos: Seq[NodeConfigIdInfo]): Option[NodeConfigIdInfo] ={
      infos.find { i => i.configId == id}
    }

    def findVersionByDate(date: DateTime, infos: Seq[NodeConfigIdInfo]): Option[NodeConfigIdInfo] ={
      infos.find { i => i.creation.isBefore(date) && (i.endOfLife match {
        case None => true
        case Some(t) => t.isAfter(date)
      }) }
    }


    for {
      compliance        <- getComplianceMode()
      runs              <- agentRunRepository.getNodesLastRun(nodeIds)
      nodeConfigIdInfos <- nodeConfigInfoRepo.getNodeConfigIdInfos(nodeIds)
      runIntervals      <- runIntervalService.getNodeReportingConfigurations(nodeIds)
      //just a validation that we have all nodes interval
      _                 <- if(runIntervals.keySet == nodeIds) Full("OK") else {
                             Failure(s"We weren't able to get agent run interval configuration (even using default values) for some node: ${(nodeIds -- runIntervals.keySet).map(_.value).mkString(", ")}")
                           }
    } yield {
      ExecutionBatch.computeNodeRunInfo(runIntervals, runs, nodeConfigIdInfos, compliance)
    }
  }


  /*
   * Given a set of agent runs and expected reports, retrieve the corresponding
   * execution reports and then nodestatusreports, being smart about what to
   * query for
   * When a node is in pending state, we drop the olderExpectedReports from it
   */
  private[this] def buildNodeStatusReports(
      runInfos          : Map[NodeId, RunAndConfigInfo]
    , allExpectedReports: Map[NodeId, Map[NodeConfigId, Map[SerialedRuleId, RuleNodeExpectedReports]]]
    , ruleIds           : Set[RuleId]
  ): Box[Seq[RuleNodeStatusReport]] = {

    val now = DateTime.now

    /*
     * We want to optimize and only query reports for nodes that we
     * actually want to merge/compare or report as unexpected reports
     */
    val agentRunIds = (runInfos.collect { case(nodeId, run:LastRunAvailable) =>
       AgentRunId(nodeId, run.lastRunDateTime)
    }).toSet ++ (runInfos.collect { case(nodeId, Pending(_, Some(run), _, _)) =>
       AgentRunId(nodeId, run._1)
    }).toSet

    for {
      /*
       * now get reports for agent rules.
       * We don't want to reach for node reports that are out of date, i.e in full compliance mode,
       * reports older that agent's run interval + 5 minutes compare to now
       */
      reports <- reportsRepository.getExecutionReports(agentRunIds, ruleIds)
    } yield {
      //we want to have nodeStatus for all asked node, not only the ones with reports
      runInfos.flatMap { case(nodeId, runInfo) =>
        ExecutionBatch.getNodeStatusReports(nodeId, runInfo
            , allExpectedReports.getOrElse(nodeId, Map())
            , reports.getOrElse(nodeId, Seq())
        )
      }
    }.toSeq
  }


}

