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
import com.normation.rudder.services.nodes.NodeInfoService
import scala.concurrent._
import scala.concurrent.ExecutionContext.Implicits.global
import com.normation.rudder.reports.GlobalComplianceMode
import com.normation.rudder.reports.ComplianceModeName
import com.normation.rudder.reports.ReportsDisabled
import scala.concurrent.duration.Duration

/**
 * Defaults non-cached version of the reporting service.
 * Just the composition of the two defaults implementation.
 */
class ReportingServiceImpl(
    val confExpectedRepo    : FindExpectedReportRepository
  , val reportsRepository   : ReportsRepository
  , val agentRunRepository  : RoReportsExecutionRepository
  , val nodeConfigInfoRepo  : RoNodeConfigIdInfoRepository
  , val runIntervalService  : AgentRunIntervalService
  , val getGlobalComplianceMode   : () => Box[GlobalComplianceMode]
) extends ReportingService with RuleOrNodeReportingServiceImpl with DefaultFindRuleNodeStatusReports

class CachedReportingServiceImpl(
    val defaultFindRuleNodeStatusReports: ReportingServiceImpl
  , val nodeInfoService                 : NodeInfoService
) extends ReportingService with RuleOrNodeReportingServiceImpl with CachedFindRuleNodeStatusReports {
  val confExpectedRepo = defaultFindRuleNodeStatusReports.confExpectedRepo
}

/**
 * Two of the reporting services methods are just utilities above
 * "findRuleNodeStatusReports": factor them out of the actual
 * implementation of that one
 */
trait RuleOrNodeReportingServiceImpl extends ReportingService {

  def confExpectedRepo: FindExpectedReportRepository

  override def findDirectiveRuleStatusReportsByRule(ruleId: RuleId): Box[RuleStatusReport] = {
    //here, the logic is ONLY to get the node for which that rule applies and then step back
    //on the other method
    for {
      nodeIds <- confExpectedRepo.findCurrentNodeIds(ruleId)
      reports <- findRuleNodeStatusReports(nodeIds, Set(ruleId))
    } yield {
      val toKeep = reports.values.flatMap( _._2 )
      RuleStatusReport(ruleId, toKeep)
    }
  }

   override def findNodeStatusReport(nodeId: NodeId) : Box[NodeStatusReport] = {
    for {
      reports    <- findRuleNodeStatusReports(Set(nodeId), Set())
      (run, set) <- Box(reports.get(nodeId)) ?~! s"Can not find report for node with ID ${nodeId.value}"
    } yield {
      NodeStatusReport(nodeId, run, set)
    }
  }

}

trait CachedFindRuleNodeStatusReports extends ReportingService with CachedRepository with Loggable {

  /**
   * underlying service that will provide the computation logic
   */
  def defaultFindRuleNodeStatusReports: DefaultFindRuleNodeStatusReports
  def nodeInfoService                 : NodeInfoService

  /**
   * The cache is managed node by node.
   * A missing nodeId mean that the cache wasn't initialized for
   * that node.
   */
  private[this] var cache = Map.empty[NodeId, (RunAndConfigInfo, Set[RuleNodeStatusReport])]

  private[this] def cacheToLog(c: Map[NodeId, (RunAndConfigInfo, Set[RuleNodeStatusReport])]): String = {
    import com.normation.rudder.domain.logger.ComplianceDebugLogger.RunAndConfigInfoToLog

    //display compliance value and expiration date.
    c.map { case (nodeId, (run, reports)) =>

      val reportsString = reports.map { r =>
        s"${r.ruleId.value}/${r.serial}[exp:${r.expirationDate}]${r.compliance.toString}"
      }.mkString("\n  ", "\n  ", "")

      s"node: ${nodeId.value}${run.toLog}${reportsString}"
    }.mkString("\n", "\n", "")
  }

  /**
   * Invalidate some keys in the cache. That won't charge them again
   * immediately
   */
  def invalidate(nodeIds: Set[NodeId]): Box[Map[NodeId, (RunAndConfigInfo, Set[RuleNodeStatusReport])]] = this.synchronized {
    logger.debug(s"Compliance cache: invalidate cache for nodes: [${nodeIds.map { _.value }.mkString(",")}]")
    cache = cache -- nodeIds
    //preload new results
    checkAndUpdateCache(nodeIds)
  }

  /**
   * For the nodeIds in parameter, check that the cache is:
   * - initialized, else go find missing rule node status reports (one time for all)
   * - none reports is expired, else switch its status to "missing" for all components
   */
  private[this] def checkAndUpdateCache(nodeIdsToCheck: Set[NodeId]) : Box[Map[NodeId, (RunAndConfigInfo, Set[RuleNodeStatusReport])]] = this.synchronized {
    if(nodeIdsToCheck.isEmpty) {
      Full(Map())
    } else {
      val now = DateTime.now

      for {
        allNodeIds        <- nodeInfoService.getAll.map( _.keySet)
        //only try to update nodes that are accepted in Rudder
        nodeIds            =  nodeIdsToCheck.intersect(allNodeIds)
        /*
         * Three cases: 1/ cache does exist and up to date, 2/ cache exists but expiration date expired,
         * 3/ cache does note exists.
         * For simplicity (and keeping computation logic elsewhere than in a cache that already has its cache logic
         * to manage), we will group 2 and 3, but it is well noted that it seems that a RuleNodeStatusReports that
         * expired could be computed without any more logic than "every thing is missing".
         *
         */
        (expired, upToDate) =  nodeIds.partition { id =>  //two group: expired id, and up to date info
                                 cache.get(id) match {
                                   case None      => true
                                   case Some((run,set)) => set.exists { _.expirationDate.isBefore(now) }
                                 }
                               }
        newStatus           <- defaultFindRuleNodeStatusReports.findRuleNodeStatusReports(expired, Set())
      } yield {
        //here, newStatus.keySet == expired.keySet, so we have processed all nodeIds that should be modified.
        logger.debug(s"Compliance cache miss (updated):[${newStatus.keySet.map(_.value).mkString(" , ")}], "+
                               s" hit:[${upToDate.map(_.value).mkString(" , ")}]")
        cache = cache ++ newStatus
        val toReturn = cache.filterKeys { id => nodeIds.contains(id) }
        logger.trace("Compliance cache content: " + cacheToLog(toReturn))
        toReturn
      }
    }
  }

  override def findRuleNodeStatusReports(nodeIds: Set[NodeId], ruleIds: Set[RuleId]) : Box[Map[NodeId, (RunAndConfigInfo, Set[RuleNodeStatusReport])]] = {
    for {
      reports <- checkAndUpdateCache(nodeIds)
    } yield {
      if(ruleIds.isEmpty) {
        reports
      } else {
        reports.mapValues { case(run, set) => (run, set.filter(r => ruleIds.contains(r.ruleId) ))}.filter( _._2._2.nonEmpty )
      }
    }
  }

  /**
   * Clear cache. Try a reload asynchronously, disregarding
   * the result
   */
  override def clearCache(): Unit = this.synchronized {
    cache = Map()
    logger.debug("Compliance cache cleared")
    //reload it for future use
    Future {
      for {
        infos <- nodeInfoService.getAll
      } yield {
        checkAndUpdateCache(infos.keySet)
      }
    }
    ()
  }

}

trait DefaultFindRuleNodeStatusReports extends ReportingService {

  def confExpectedRepo  : FindExpectedReportRepository
  def reportsRepository : ReportsRepository
  def agentRunRepository: RoReportsExecutionRepository
  def nodeConfigInfoRepo: RoNodeConfigIdInfoRepository
  def runIntervalService: AgentRunIntervalService
  def getGlobalComplianceMode : () => Box[GlobalComplianceMode]

  override def findRuleNodeStatusReports(nodeIds: Set[NodeId], ruleIds: Set[RuleId]) : Box[Map[NodeId, (RunAndConfigInfo, Set[RuleNodeStatusReport])]] = {
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
     * All nodeIds get a value in the returnedMap, because:
     * - getNodeRunInfos(nodeIds).keySet == nodeIds AND
     * - runInfos.keySet == buildNodeStatusReports(runInfos,...).keySet
     * So nodeIds === returnedMap.keySet holds
     */
    val t0 = System.currentTimeMillis
    for {
      complianceMode      <- getGlobalComplianceMode()
      // we want compliance on these nodes
      runInfos            <- getNodeRunInfos(nodeIds, complianceMode)

      // that gives us configId for runs, and expected configId (some may be in both set)
      expectedConfigIds   =  runInfos.collect { case (nodeId, x:ExpectedConfigAvailable) => NodeAndConfigId(nodeId, x.expectedConfigInfo.configId) }
      lastrunConfigId     =  runInfos.collect {
                               case (nodeId, x:LastRunAvailable) => NodeAndConfigId(nodeId, x.lastRunConfigId)
                               case (nodeId, Pending(_, Some(run), _, _)) => NodeAndConfigId(nodeId, run._2.configId)
                             }

      t1                  =  System.currentTimeMillis
      _                   =  TimingDebugLogger.debug(s"Compliance: get run infos: ${t1-t0}ms")

      // so now, get all expected reports for these config id
      allExpectedReports  <- confExpectedRepo.getExpectedReports(expectedConfigIds.toSet++lastrunConfigId, ruleIds)

      t2                  =  System.currentTimeMillis
      _                   =  TimingDebugLogger.debug(s"Compliance: get expected reports: ${t2-t1}ms")

      // compute the status
      nodeStatusReports   <- buildNodeStatusReports(runInfos, allExpectedReports, ruleIds, complianceMode.mode)

      t3                  =  System.currentTimeMillis
      _                   =  TimingDebugLogger.debug(s"Compliance: compute compliance reports: ${t3-t2}ms")
    } yield {
      nodeStatusReports
    }
  }

  /*
   * For each node, get the config it has.
   * This method bases its result on THE LAST RUN
   * of each node, and try to discover the run linked information (datetime, config id).
   *
   * A value is return for ALL nodeIds, so the assertion nodeIds == returnedMap.keySet holds.
   *
   */
  private[this] def getNodeRunInfos(nodeIds: Set[NodeId], complianceMode: GlobalComplianceMode): Box[Map[NodeId, RunAndConfigInfo]] = {
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
      runs              <- complianceMode.mode match {
                            //this is an optimisation to avoid querying the db in that case
                             case ReportsDisabled => Full(nodeIds.map(id => (id, None)).toMap)
                             case _ => Await.result(agentRunRepository.getNodesLastRun(nodeIds), Duration.Inf)
                           }
      nodeConfigIdInfos <- nodeConfigInfoRepo.getNodeConfigIdInfos(nodeIds)
      runIntervals      <- runIntervalService.getNodeReportingConfigurations(nodeIds)
      //just a validation that we have all nodes interval
      _                 <- if(runIntervals.keySet == nodeIds) Full("OK") else {
                             Failure(s"We weren't able to get agent run interval configuration (even using default values) for some node: ${(nodeIds -- runIntervals.keySet).map(_.value).mkString(", ")}")
                           }
    } yield {
      ExecutionBatch.computeNodesRunInfo(runIntervals, runs, nodeConfigIdInfos, complianceMode)
    }
  }

  /*
   * Given a set of agent runs and expected reports, retrieve the corresponding
   * execution reports and then nodestatusreports, being smart about what to
   * query for
   * When a node is in pending state, we drop the olderExpectedReports from it
   *
   * Each runInfo get a result, even if we don't have information about it.
   * So runInfos.keySet == returnedMap.keySet holds.
   */
  private[this] def buildNodeStatusReports(
      runInfos          : Map[NodeId, RunAndConfigInfo]
    , allExpectedReports: Map[NodeId, Map[NodeConfigId, Map[SerialedRuleId, RuleNodeExpectedReports]]]
    , ruleIds           : Set[RuleId]
    , complianceModeName: ComplianceModeName
  ): Box[Map[NodeId, (RunAndConfigInfo, Set[RuleNodeStatusReport])]] = {

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
       * We want to do a batch query for all nodes to be able to minimize number of requests.
       *
       * We don't want to do the query if we are in "reports-disabled" mode, since in that mode,
       * either we don't have reports (expected) or we have reports that will be out of date
       * (for ex. just after a change in the option).
       */
      reports <- complianceModeName match {
                                      case ReportsDisabled => Full(Map[NodeId,Seq[Reports]]())
                                      case _               => reportsRepository.getExecutionReports(agentRunIds, ruleIds)
                                    }
    } yield {
      //we want to have nodeStatus for all asked node, not only the ones with reports
      runInfos.map { case(nodeId, runInfo) =>
        ExecutionBatch.getNodeStatusReports(nodeId, runInfo
            , allExpectedReports.getOrElse(nodeId, Map())
            , reports.getOrElse(nodeId, Seq())
        )
      }
    }
  }
}
