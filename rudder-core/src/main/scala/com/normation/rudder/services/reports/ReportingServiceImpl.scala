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


class ReportingServiceImpl(
    confExpectedRepo   : FindExpectedReportRepository
  , reportsRepository  : ReportsRepository
  , agentRunRepository : RoReportsExecutionRepository
  , nodeConfigInfoRepo : RoNodeConfigIdInfoRepository
  , getAgentRunInterval: () => Int
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


  override def findNodeStatusReport(nodeId: NodeId) : Box[NodeStatusReport] = {
    for {
      reports <- findRuleNodeStatusReports(Set(nodeId), Set())
      report  =  reports.groupBy(_.nodeId).map { case(nodeId, reports) => NodeStatusReport(nodeId, reports) }.toSet
      result  <- if(report.size == 1) Full(report.head) else Failure("Found bad number of reports for node " + nodeId)
    } yield {
      result
    }
  }


  override def findRuleNodeStatusReports(nodeIds: Set[NodeId], ruleIds: Set[RuleId]) : Box[Set[RuleNodeStatusReport]] = {
    for {
      runInfos                 <- getNodeRunInfos(nodeIds)

      /*
       * For node with bad or no usable config id, we need
       * to retrieve the last expected reports.
       * For other, we find expected reports by configId
       */

      ( nodesForOpenExpected
      , nodesByConfigId      ) =  runInfos.foldLeft((Set.empty[NodeId], Set.empty[NodeAndConfigId])) { case((l1,l2), (nodeId, x)) => x.configIdForExpectedReports match {
                                    case None => (l1+nodeId, l2)
                                    case Some(c) => (l1, l2 + NodeAndConfigId(nodeId, c))
                                  } }

      lastExpectedReports      <- confExpectedRepo.getLastExpectedReports(nodesForOpenExpected, ruleIds)

      //get nodeConfigId for nodes not in the nodesForOpenExpected and with a config version
      byVersionExpectedReports <- confExpectedRepo.getExpectedReports(nodesByConfigId, ruleIds)

      allExpected              =  (byVersionExpectedReports ++ lastExpectedReports).toSeq

      // We need to list all the pending node entries, to get the expected configId, and the received ConfigId
      pendingNodeEntries       = runInfos.collect{
        case (nodeId, Pending(expected, Some(actualLastRun), _)) =>  PreviousAndExpectedNodeConfigId(NodeAndConfigId(nodeId,actualLastRun.configId), NodeAndConfigId(nodeId,expected.configId)) }.toSet

      olderExpectedReports     <- confExpectedRepo.getPendingReports(pendingNodeEntries, ruleIds)


      // allExpected contains all the expected reports, but in the case of pending Node, we still have some relevant information
      // from previous runs, that we'd need to use
      // However we should exclude from these previous runs the reports corresponding to older expected reports
      nodeStatusReports        <- buildNodeStatusReports(runInfos, allExpected, ruleIds, olderExpectedReports)
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
    } yield {
      val agentRunInterval = getAgentRunInterval()
      val nodeWithGrace = nodeIds.map( (_, Duration.standardMinutes(agentRunInterval+ExecutionBatch.GRACE_TIME_PENDING))).toMap

      ExecutionBatch.computeNodeRunInfo(nodeWithGrace, runs, nodeConfigIdInfos, compliance)

    }
  }


  /*
   * Given a set of agen runs and expected reports, retrieve the corresponding
   * execution reports and then nodestatusreports, being smart about what to
   * query for
   * When a node is in pending state, we drop the olderExpectedReports from it
   */
  private[this] def buildNodeStatusReports(
      runInfos          : Map[NodeId, RunAndConfigInfo]
    , allExpectedReports: Seq[RuleExpectedReports]
    , ruleIds           : Set[RuleId]
    , olderExpectedReports: Set[RuleExpectedReports]
  ): Box[Seq[RuleNodeStatusReport]] = {

    val now = DateTime.now
    /**
     * We want to optimize and only query reports for nodes that we
     * actually want to merge/compare or report as unexpected reports
     */
    val agentRunIds = (runInfos.collect { case(nodeId, run:InterestingRun) =>
       AgentRunId(nodeId, run.dateTime)
    }).toSet ++ (runInfos.collect { case(nodeId, Pending(expected, Some(actual), Some(run))) =>
       AgentRunId(nodeId, run)
    }).toSet

    for {
      /*
       * now get reports for agent rules.
       * We don't want to reach for node reports that are out of date, i.e in full compliance mode,
       * reports older that agent's run interval + 5 minutes compare to now
       */
      reports              <- reportsRepository.getExecutionReports(agentRunIds, ruleIds)
    } yield {

      //we want to have nodeStatus for all asked node, not only the ones with reports
      runInfos.flatMap { case(nodeId, optRun) =>
        ExecutionBatch.getNodeStatusReports(nodeId, optRun
            , allExpectedReports
            , reports.getOrElse(nodeId, Seq())
            , olderExpectedReports
        )
      }
    }.toSeq
  }


}

