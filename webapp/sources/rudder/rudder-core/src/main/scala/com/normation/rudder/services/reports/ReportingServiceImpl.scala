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

import com.normation.box._
import com.normation.errors._
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.logger.ReportLogger
import com.normation.rudder.domain.logger.ReportLoggerPure
import com.normation.rudder.domain.logger.TimingDebugLogger
import com.normation.rudder.domain.nodes.NodeState
import com.normation.rudder.domain.policies.GlobalPolicyMode
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.reports.{NodeStatusReport, RuleStatusReport, _}
import com.normation.rudder.reports.AgentRunIntervalService
import com.normation.rudder.reports.ComplianceModeName
import com.normation.rudder.reports.GlobalComplianceMode
import com.normation.rudder.reports.ReportsDisabled
import com.normation.rudder.reports.execution.AgentRunId
import com.normation.rudder.reports.execution.RoReportsExecutionRepository
import com.normation.rudder.repository._
import com.normation.rudder.services.nodes.NodeInfoService
import com.normation.utils.Control.sequence
import com.normation.zio._
import net.liftweb.common._
import org.joda.time._
import zio._

object ReportingServiceUtils {

  /*
   * Build rule status reports from node reports, decide=ing which directive should be "skipped"
   */
  def buildRuleStatusReport(ruleId: RuleId, nodeReports: Map[NodeId, NodeStatusReport]): RuleStatusReport = {
    val toKeep = nodeReports.values.flatMap( _.reports ).filter(_.ruleId == ruleId).toList
    // we don't keep overrides for a directive which is already in "toKeep"
    val toKeepDir = toKeep.map(_.directives.keySet).toSet.flatten
    val overrides = nodeReports.values.flatMap( _.overrides.filterNot(r => toKeepDir.contains(r.policy.directiveId))).toList.distinct
    RuleStatusReport(ruleId, toKeep, overrides)
  }
}


/**
 * Action that can be done on the Compliance Cache
 * The queue receive action, that are processed in FIFO, to change the cache content
 * All the possible actions are:
 * * insert a node in the cache (when a new node is accepted)
 * * remove a node from the cache (when the node is deleted)
 * * initialize compliance
 * * update compliance with a new run (with the new compliance)
 * * update node configuration (after a policy generation, with new nodeconfiguration)
 * * set the node in node answer state (with the new compliance?)
 */
sealed trait CacheComplianceQueueAction {val nodeId:NodeId }

case class InsertNodeInCache(nodeId: NodeId) extends CacheComplianceQueueAction
case class RemoveNodeInCache(nodeId: NodeId) extends CacheComplianceQueueAction
case class InitializeCompliance(nodeId: NodeId, nodeCompliance: Option[NodeStatusReport]) extends CacheComplianceQueueAction // do we need this?
case class UpdateCompliance(nodeId: NodeId, nodeCompliance: NodeStatusReport) extends CacheComplianceQueueAction
case class UpdateNodeConfiguration(nodeId: NodeId, nodeConfiguration: NodeExpectedReports) extends CacheComplianceQueueAction // convert the nodestatursreport to pending, with info from last run
case class SetNodeNoAnswer(nodeId: NodeId, actionDate: DateTime) extends CacheComplianceQueueAction

/**
 * Defaults non-cached version of the reporting service.
 * Just the composition of the two defaults implementation.
 */
class ReportingServiceImpl(
    val confExpectedRepo           : FindExpectedReportRepository
  , val reportsRepository          : ReportsRepository
  , val agentRunRepository         : RoReportsExecutionRepository
  , val runIntervalService         : AgentRunIntervalService
  , val nodeInfoService            : NodeInfoService
  , val directivesRepo             : RoDirectiveRepository
  , val rulesRepo                  : RoRuleRepository
  , val getGlobalComplianceMode    : () => Box[GlobalComplianceMode]
  , val getGlobalPolicyMode        : () => IOResult[GlobalPolicyMode]
  , val getUnexpectedInterpretation: () => Box[UnexpectedReportInterpretation]
  , val jdbcMaxBatchSize           : Int
) extends ReportingService with RuleOrNodeReportingServiceImpl with DefaultFindRuleNodeStatusReports

class CachedReportingServiceImpl(
    val defaultFindRuleNodeStatusReports: ReportingServiceImpl
  , val nodeInfoService                 : NodeInfoService
  , val batchSize                       : Int
  , val complianceRepository            : ComplianceRepository
) extends ReportingService with RuleOrNodeReportingServiceImpl with CachedFindRuleNodeStatusReports {
  val confExpectedRepo = defaultFindRuleNodeStatusReports.confExpectedRepo
  val directivesRepo   = defaultFindRuleNodeStatusReports.directivesRepo
  val rulesRepo        = defaultFindRuleNodeStatusReports.rulesRepo

  def findUncomputedNodeStatusReports() : Box[Map[NodeId, NodeStatusReport]] = defaultFindRuleNodeStatusReports.findUncomputedNodeStatusReports()

}

/**
 * Two of the reporting services methods are just utilities above
 * "findRuleNodeStatusReports": factor them out of the actual
 * implementation of that one
 */
trait RuleOrNodeReportingServiceImpl extends ReportingService {

  def confExpectedRepo: FindExpectedReportRepository
  def directivesRepo  : RoDirectiveRepository
  def nodeInfoService : NodeInfoService
  def rulesRepo       : RoRuleRepository

  override def findDirectiveRuleStatusReportsByRule(ruleId: RuleId): Box[RuleStatusReport] = {
    //here, the logic is ONLY to get the node for which that rule applies and then step back
    //on the other method
    val time_0 = System.currentTimeMillis
    for {
      nodeIds <- confExpectedRepo.findCurrentNodeIds(ruleId)
      time_1  =  System.currentTimeMillis
      _       =  TimingDebugLogger.debug(s"findCurrentNodeIds: Getting node IDs for rule '${ruleId.value}' took ${time_1-time_0}ms")
      reports <- findRuleNodeStatusReports(nodeIds, Set(ruleId))
    } yield {
      ReportingServiceUtils.buildRuleStatusReport(ruleId, reports)
    }
  }

  override def findNodeStatusReport(nodeId: NodeId) : Box[NodeStatusReport] = {
    for {
      reports <- findRuleNodeStatusReports(Set(nodeId), Set())
      status  <- Box(reports.get(nodeId)) ?~! s"Can not find report for node with ID ${nodeId.value}"
    } yield {
      status
    }
  }

  override def findUserNodeStatusReport(nodeId: NodeId) : Box[NodeStatusReport] = {
    for {
      userRules <- rulesRepo.getIds().toBox
      reports <- findRuleNodeStatusReports(Set(nodeId), userRules)
      status  <- reports.get(nodeId) match {
        case Some(report) => Full(report)
        case None =>
          findSystemNodeStatusReport(nodeId) match {
            case Full(systemreport) =>
              val runInfo = systemreport.runInfo match {
                // Classic case with data we need, build NoUserRulesDefined
                case a : ExpectedConfigAvailable with LastRunAvailable =>
                  NoUserRulesDefined(a.lastRunDateTime, a.expectedConfig, a.lastRunConfigId, a.lastRunConfigInfo)

                // Pending case / maybe we should keep pending
                case pending @ Pending(expectedConfig, optLastRun, _)  =>
                  optLastRun match {
                    case Some((lastRunDate,lastRunConfigInfo)) =>
                      NoUserRulesDefined(lastRunDate, expectedConfig, lastRunConfigInfo.nodeConfigId, Some(lastRunConfigInfo))
                    case None => pending
                  }
                // Case we don't have enough information to build data / or state is worse than 'no rules'
                case _: NoReportInInterval | _ : ReportsDisabledInInterval | _ : ErrorNoConfigData =>
                  systemreport.runInfo
              }
              Full(NodeStatusReport(nodeId, runInfo, systemreport.statusInfo, Nil, Set()))

            case eb : EmptyBox => eb
          }
      }
    } yield {
      status
    }
  }

  override def findSystemNodeStatusReport(nodeId: NodeId) : Box[NodeStatusReport] = {
    for {
      allRules <- rulesRepo.getIds(true).toBox
      userRules <- rulesRepo.getIds().toBox
      systemRules = allRules.diff(userRules)
      reports <- findRuleNodeStatusReports(Set(nodeId), systemRules)
      status  <- Box(reports.get(nodeId)) ?~! s"Can not find report for node with ID ${nodeId.value}"
    } yield {
      status
    }
  }

  def getUserNodeStatusReports() : Box[Map[NodeId, NodeStatusReport]] = {
    val n1 = System.currentTimeMillis
    for {
      nodeIds            <- nodeInfoService.getAll().map( _.keySet )
      userRules          <- rulesRepo.getIds().toBox
      n2                 = System.currentTimeMillis
      _                  = TimingDebugLogger.trace(s"Reporting service - Get nodes and users rules in: ${n2 - n1}ms")
      reports            <- findRuleNodeStatusReports(nodeIds, userRules)
    } yield {
      reports
    }
  }

  def computeComplianceFromReports(reports: Map[NodeId, NodeStatusReport]): Option[(ComplianceLevel, Long)] =  {
    // if we don't have any report that is not a system one, the user-rule global compliance is undefined
    val n1 = System.currentTimeMillis
    if(reports.isEmpty) {
      None
    } else { // aggregate values
      val complianceLevel = ComplianceLevel.sum(reports.flatMap( _._2.reports.toSeq.map( _.compliance)))
      val n2 = System.currentTimeMillis
      TimingDebugLogger.trace(s"Agregating compliance level for  global user compliance in: ${n2-n1}ms")

      Some((
      complianceLevel
      , complianceLevel.complianceWithoutPending.round
      ))
    }
  }

  def getGlobalUserCompliance(): Box[Option[(ComplianceLevel, Long)]] = {
    for {
      reports    <- getUserNodeStatusReports
      compliance =  computeComplianceFromReports(reports)
    } yield {
      compliance
    }
  }
}



/**
 * Managed a cached version of node reports.
 * The logic is:
 * - we have a map of [NodeId, Reports]
 * - access to `findRuleNodeStatusReports` use data from cache and returns immediatly,
 *   filtering for expired reports
 * - the only path to update the cache is through an async blocking queue of `InvalidateComplianceCacheMsg`
 * - the dequeue actor calculs updated reports for invalidated nodes and update the map accordingly.
 */
trait CachedFindRuleNodeStatusReports extends ReportingService with CachedRepository {

  /**
   * underlying service that will provide the computation logic
   */
  def defaultFindRuleNodeStatusReports: DefaultFindRuleNodeStatusReports
  def nodeInfoService                 : NodeInfoService
  def batchSize                       : Int

  /**
   * The cache is managed node by node.
   * A missing nodeId mean that the cache wasn't initialized for
   * that node, and should fail
   *
   * Initialization of cache is a real question:
   * * node doesn't have report yet
   * * we restart Rudder, after upgrade, we don't have the new runs - none
   * * we restart Rudder, in normal mode: we take the last (most recent execution) *computed*
   * * * we may have outdated info in this case, but that's not an ssue as it will restore itself really fast
   */
  private[this] var cache = Map.empty[NodeId, NodeStatusReport]

  /**
   * The queue of invalidation request.
   * The queue size is 1 and new request need to merge existing
   * node id with new ones.
   * It's a List and not a Set, because we want to keep the precedence in
   * invalidation request.
   */
  private[this] val invalidateComplianceRequest = Queue.dropping[List[(NodeId, CacheComplianceQueueAction)]](1).runNow

  /**
   * We need a semaphore to protect queue content merge-update
   */
  private[this] val invalidateMergeUpdateSemaphore = Semaphore.make(1).runNow

  /**
   * Update logic. We take message from queue one at a time, and process.
   */
  val updateCacheFromRequest: IO[Nothing, Unit] = invalidateComplianceRequest.take.flatMap(invalidatedIds =>
    // batch node processing by slice of batchSize.
    // Be careful, sliding default step is 1.


    ZIO.foreach_(groupQueueActionByType(invalidatedIds.map(x => x._2)).to(Iterable))(actions =>
      // several strategy:
      // * we have a compliance: yeah, put it in the cache
      // * new policy generation, a new nodeexpectedreports is available: compute compliance for last run of the node, based on this nodeexpectedreports
      // * node deletion: remove from the cache
      // * invalidate cache: ???
      // * no report from the node (compliance expires): recompute compliance

      // as a first approach, they could simply findRuleNodeStatusReports


      {


        (for {
          _  <- performAction(actions)
        } yield ()).catchAll(err => ReportLoggerPure.Cache.error(s"Error when updating compliance cache for nodes: [${actions.map(_.nodeId).map(_.value).mkString(", ")}]: ${err.fullMsg}"))
      }
    )
  )

  // start updating
  updateCacheFromRequest.forever.forkDaemon.runNow


  private[this] def performAction(actions: List[CacheComplianceQueueAction]): IOResult[Unit] = {
    // get type of action
    actions.headOption match {
      case None => ReportLoggerPure.Cache.debug("Nothing to do")
      case Some(t) => t match {
        case update:UpdateCompliance =>
          IOResult.effectNonBlocking {
            cache = cache ++ actions.map { case x: UpdateCompliance => (x.nodeId, x.nodeCompliance) }
          }
        case delete: RemoveNodeInCache =>
          IOResult.effectNonBlocking {
            cache = cache.removedAll(actions.map { case x: RemoveNodeInCache => (x.nodeId) })
          }

        // need to compute compliance
        case _ =>
          val impactedNodeIds = actions.map(x => x.nodeId)
          for {
            updated <- defaultFindRuleNodeStatusReports.findRuleNodeStatusReports(impactedNodeIds.toSet, Set()).toIO
            _       <- IOResult.effectNonBlocking {
              cache = cache ++ updated
            }
            _ <- ReportLoggerPure.Cache.debug(s"Compliance cache updated for nodes: ${impactedNodeIds.map(_.value).mkString(", ")}")
          } yield ()
      }
    }
  }
  /**
   * Group all actions queue by the same type, keeping the global order.
   */
  private[this] def groupQueueActionByType(l: List[CacheComplianceQueueAction]): List[List[CacheComplianceQueueAction]] = {
    l.headOption.map{x => val (h,t)=l.span{x.getClass==_.getClass}; h::groupQueueActionByType(t)}.getOrElse(Nil)
  }

  private[this] def cacheToLog(c: Map[NodeId, NodeStatusReport]): String = {
    import com.normation.rudder.domain.logger.ComplianceDebugLogger.RunAndConfigInfoToLog

    //display compliance value and expiration date.
    c.map { case (nodeId, status) =>

      val reportsString = status.reports.map { r =>
        s"${r.ruleId.value}[exp:${r.expirationDate}]${r.compliance.toString}"
      }.mkString("\n  ", "\n  ", "")

      s"node: ${nodeId.value}${status.runInfo.toLog}${reportsString}"
    }.mkString("\n", "\n", "")
  }


  /**
   * Invalidate some keys in the cache. This method returns immediatly.
   * The update is computed asynchronously.
   *//*
  def invalidate(nodeIds: Set[NodeId]): IOResult[Unit] = {
    ZIO.when(nodeIds.nonEmpty) {
      ReportLoggerPure.Cache.debug(s"Compliance cache: invalidation request for nodes: [${nodeIds.map { _.value }.mkString(",")}]") *>
      invalidateMergeUpdateSemaphore.withPermit(for {
        elements <- invalidateComplianceRequest.takeAll
        allIds   =  (elements.flatten.toMap ++ nodeIds.map(ids => (ids, None))).toList
        _        <- invalidateComplianceRequest.offer(allIds)
      } yield ())
    }
  }*/

  /**
   * invalidate with an action to do something
   * order is important
   */
  def invalidateWithAction(actions: Seq[(NodeId, CacheComplianceQueueAction)]): IOResult[Unit] = {
    ZIO.when(actions.nonEmpty) {
      ReportLoggerPure.Cache.debug(s"Compliance cache: invalidation request for nodes with action: [${actions.map(_._1).map { _.value }.mkString(",")}]") *>
        invalidateMergeUpdateSemaphore.withPermit(for {
          elements     <- invalidateComplianceRequest.takeAll
          allActions   =  (elements.flatten ++ actions)
          _            <- invalidateComplianceRequest.offer(allActions)
        } yield ())
    }
  }




  /**
   * Look in the cache for compliance for given nodes.
   * Only data from cache is used, and even then are filtered out for expired data, so
   * in the end, only node with up-to-date data are returned.
   * For missing node in cache, a cache invalidation is triggered.
   *
   * That means that not all parameter node will lead to a NodeStatusReport in the map.
   * This is handled in higher level of the app and leads to "no data available" in
   * place of compliance bar.
   */
  private[this] def checkAndGetCache(nodeIdsToCheck: Set[NodeId]) : Box[Map[NodeId, NodeStatusReport]] = {
    if(nodeIdsToCheck.isEmpty) {
      Full(Map())
    } else {
      val now = DateTime.now

      for {
        // disabled nodes are ignored
        allNodeIds    <- nodeInfoService.getAll.map( _.filter { case(_,n) => n.state != NodeState.Ignored }.keySet )
        //only try to update nodes that are accepted in Rudder
        nodeIds       =  nodeIdsToCheck.intersect(allNodeIds)
        inCache       =  cache.filter { case(id, _) => nodeIds.contains(id) }
        /*
         * Now, we want to signal to cache that some compliance may be missing / expired
         * for the next time.
         *
         * Three cases:
         * 1/ cache does exist and up to date INCLUDING the one with "missing" (because the report is
         *    ok and will be until a new report comes)
         * 2/ cache exists but expiration date expired,
         * 3/ cache does note exists.
         *
         * For both 2 and 3, we trigger a cache regeneration for the corresponding node.
         * For 3, we don't return data. Compliance for that node will appear as "missing data"
         * and will be excluded to nodes count.
         *
         * For 2, we need to return data because of issue https://issues.rudder.io/issues/16612
         * Grace period is alredy taken into account in expiration date.
         * We return the cached value up to 2 runs after grace period expiration (service above that
         * one will display expiration info).
         *
         * The definition of expired date is the following:
         *  - Node is Pending -> expirationDateTime is the expiration time
         *  - There is a LastRunAvailable -> expirationDateTime is the lastRunExpiration
         *  - Other cases: no expiration, ie a "missing report" can not expire (and that's what we want)
         *
         */
         upToDate     =  inCache.filter { case (_, report) =>
                           val expired = report.runInfo match {
                             case t : ExpiringStatus => t.expirationDateTime.isBefore(now)
                             case UnexpectedVersion(_, _, lastRunExpiration, _, _)
                                                     => lastRunExpiration.isBefore(now)
                             case UnexpectedNoVersion(_, _, lastRunExpiration, _, _)
                                                     => lastRunExpiration.isBefore(now)
                             case UnexpectedUnknowVersion(_, _, _, expectedExpiration)
                                                     => expectedExpiration.isBefore(now)
                             case _                  => false
                           }
                           !expired
                         }
        // starting with nodeIds, is all accepted node passed in parameter,
        // we don't miss node ids not yet in cache
        requireUpdate =  nodeIds -- upToDate.keySet
        _             <- invalidateWithAction(requireUpdate.toSeq.map(x => (x, SetNodeNoAnswer(x, DateTime.now())))).unit.toBox
      } yield {
        ReportLogger.Cache.debug(s"Compliance cache to reload (expired, missing):[${requireUpdate.map(_.value).mkString(" , ")}]")
        if (ReportLogger.Cache.isTraceEnabled) {
          ReportLogger.Cache.trace("Compliance cache hit: " + cacheToLog(upToDate))
        }
        inCache
      }
    }
  }

  /**
   * Find node status reports. That method returns immediatly with the information it has in cache, which
   * can be outdated. This is the prefered way to avoid huge contention (see https://issues.rudder.io/issues/16557).
   *
   * That method nonetheless check for expiration dates.
   */
  override def findRuleNodeStatusReports(nodeIds: Set[NodeId], ruleIds: Set[RuleId]) : Box[Map[NodeId, NodeStatusReport]] = {
    val n1 = System.currentTimeMillis
    for {
      reports <- checkAndGetCache(nodeIds)
      n2      =  System.currentTimeMillis
      _       =  ReportLogger.Cache.debug(s"Get node compliance from cache in: ${n2 - n1}ms")
    } yield {
      filterReportsByRules(reports, ruleIds)
    }
  }

  /**
   * Clear cache. Try a reload asynchronously, disregarding
   * the result
   */
  override def clearCache(): Unit = {
    cache = Map()
    ReportLogger.Cache.debug("Compliance cache cleared")
    //reload it for future use
    //nodeInfoService.getAll.flatMap { nodeIds => Full(invalidate(nodeIds.keySet).unit.runNow) }
  }
}

trait DefaultFindRuleNodeStatusReports extends ReportingService {

  def confExpectedRepo           : FindExpectedReportRepository
  def reportsRepository          : ReportsRepository
  def agentRunRepository         : RoReportsExecutionRepository
  def getGlobalComplianceMode    : () => Box[GlobalComplianceMode]
  def getUnexpectedInterpretation: () => Box[UnexpectedReportInterpretation]
  def jdbcMaxBatchSize           : Int

  override def findRuleNodeStatusReports(nodeIds: Set[NodeId], ruleIds: Set[RuleId]) : Box[Map[NodeId, NodeStatusReport]] = {
    /*
     * This is the main logic point to get reports.
     *
     * Compliance for a given node is a function of ONLY(expectedNodeConfigId, lastReceivedAgentRun).
     *
     * The logic is:
     *
     * - for a (or n) given node (we have a node-bias),
     * - get the expected configuration right now
     *   - errors may happen if the node does not exist or if
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
      unexpectedMode      <- getUnexpectedInterpretation()
      // we want compliance on these nodes
      runInfos            <- getNodeRunInfos(nodeIds, complianceMode)
      t1                  =  System.currentTimeMillis
      _                   =  TimingDebugLogger.trace(s"Compliance: get node run infos: ${t1-t0}ms")

      // that gives us configId for runs, and expected configId (some may be in both set)
      expectedConfigIds   =  runInfos.collect { case (nodeId, x:ExpectedConfigAvailable) => NodeAndConfigId(nodeId, x.expectedConfig.nodeConfigId) }
      lastrunConfigId     =  runInfos.collect {
                               case (nodeId, Pending(_, Some(run), _)) => NodeAndConfigId(nodeId, run._2.nodeConfigId)
                               case (nodeId, x:LastRunAvailable) => NodeAndConfigId(nodeId, x.lastRunConfigId)
                             }

      t2                  =  System.currentTimeMillis
      _                   =  TimingDebugLogger.debug(s"Compliance: get run infos: ${t2-t0}ms")

      // compute the status
      nodeStatusReports   <- buildNodeStatusReports(runInfos, ruleIds, complianceMode.mode, unexpectedMode)

      t2                  =  System.currentTimeMillis
      _                   =  TimingDebugLogger.debug(s"Compliance: compute compliance reports: ${t2-t1}ms")
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
    val t0 = System.currentTimeMillis
    for {
      runs              <- complianceMode.mode match {
                            //this is an optimisation to avoid querying the db in that case
                             case ReportsDisabled => Full(nodeIds.map(id => (id, None)).toMap)
                             case _ => agentRunRepository.getNodesLastRun(nodeIds)
                           }
      t1                =  System.currentTimeMillis
      _                 =  TimingDebugLogger.trace(s"Compliance: get nodes last run : ${t1-t0}ms")
      currentConfigs    <- confExpectedRepo.getCurrentExpectedsReports(nodeIds)
      t2                =  System.currentTimeMillis
      _                 =  TimingDebugLogger.trace(s"Compliance: get current expected reports: ${t2-t1}ms")
      nodeConfigIdInfos <- confExpectedRepo.getNodeConfigIdInfos(nodeIds)
      t3                =  System.currentTimeMillis
      _                 =  TimingDebugLogger.trace(s"Compliance: get Node Config Id Infos: ${t3-t2}ms")
    } yield {
      ExecutionBatch.computeNodesRunInfo(runs, currentConfigs, nodeConfigIdInfos)
    }
  }


  override def findUncomputedNodeStatusReports() : Box[Map[NodeId, NodeStatusReport]] = {
    /*
     * This is the main logic point to computed reports.
     *
     * Compliance for a given node is a function of ONLY(expectedNodeConfigId, lastReceivedAgentRun).
     *
     * The logic is:
     *
     * - get the untreated reports
     * - for these given nodes,
     * - get the expected configuration right now
     *   - errors may happen if the node does not exist (has been deleted)
     *     => "no data for that node"
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
      unexpectedMode      <- getUnexpectedInterpretation()
      // get untreated runs
      uncomputedRuns      <- getUnComputedNodeRunInfos(complianceMode)
      t1                  =  System.currentTimeMillis
      _                   =  TimingDebugLogger.trace(s"Compliance: get uncomputed node run infos: ${t1-t0}ms")

      // that gives us configId for runs, and expected configId (some may be in both set)
       // expectedConfigIds   =  uncomputedRuns.collect { case (nodeId, x:ExpectedConfigAvailable) => NodeAndConfigId(nodeId, x.expectedConfig.nodeConfigId) }
      //lastrunConfigId     =  uncomputedRuns.collect {
      //  case (nodeId, Pending(_, Some(run), _)) => NodeAndConfigId(nodeId, run._2.nodeConfigId)
      //  case (nodeId, x:LastRunAvailable) => NodeAndConfigId(nodeId, x.lastRunConfigId)
      //}

      t2                  =  System.currentTimeMillis
      _                   =  TimingDebugLogger.debug(s"Compliance: get run infos: ${t2-t0}ms")

      // compute the status
      nodeStatusReports   <- buildNodeStatusReports(uncomputedRuns, Set(), complianceMode.mode, unexpectedMode)

      t2                  =  System.currentTimeMillis
      _                   =  TimingDebugLogger.debug(s"Compliance: compute compliance reports: ${t2-t1}ms")
    } yield {
      nodeStatusReports
    }
  }


  private[this] def getUnComputedNodeRunInfos(complianceMode: GlobalComplianceMode): Box[Map[NodeId, RunAndConfigInfo]] = {
    val t0 = System.currentTimeMillis
    for {
      runs              <- agentRunRepository.getNodesLastRunv2().toBox
      t1                =  System.currentTimeMillis
      _                 =  TimingDebugLogger.trace(s"Compliance: get nodes last run : ${t1-t0}ms")
      nodeIds           = runs.keys.toSet
      currentConfigs    <- confExpectedRepo.getCurrentExpectedsReports(nodeIds)
      t2                =  System.currentTimeMillis
      _                 =  TimingDebugLogger.trace(s"Compliance: get current expected reports: ${t2-t1}ms")
      nodeConfigIdInfos <- confExpectedRepo.getNodeConfigIdInfos(nodeIds)
      t3                =  System.currentTimeMillis
      _                 =  TimingDebugLogger.trace(s"Compliance: get Node Config Id Infos: ${t3-t2}ms")
    } yield {
      ExecutionBatch.computeNodesRunInfo(runs, currentConfigs, nodeConfigIdInfos)
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
      runInfos                : Map[NodeId, RunAndConfigInfo]
    , ruleIds                 : Set[RuleId]
    , complianceModeName      : ComplianceModeName
    , unexpectedInterpretation: UnexpectedReportInterpretation
  ): Box[Map[NodeId, NodeStatusReport]] = {

    var u1, u2 = 0L

    val batchedRunsInfos = runInfos.grouped(jdbcMaxBatchSize).toSeq
    val result = sequence(batchedRunsInfos) { runBatch =>
      val t0 = System.nanoTime()
      /*
       * We want to optimize and only query reports for nodes that we
       * actually want to merge/compare or report as unexpected reports
       */
      val agentRunIds = runBatch.flatMap { case (nodeId, run) => run match {
        case r: LastRunAvailable    => Some(AgentRunId(nodeId, r.lastRunDateTime))
        case Pending(_, Some(r), _) => Some(AgentRunId(nodeId, r._1))
        case _                      => None
      } }.toSet

      for {
        /*
         * now get reports for agent rules.
         *
         * We don't want to do the query if we are in "reports-disabled" mode, since in that mode,
         * either we don't have reports (expected) or we have reports that will be out of date
         * (for ex. just after a change in the option).
         */
        reports <- complianceModeName match {
          case ReportsDisabled => Full(Map[NodeId, Seq[Reports]]())
          case _               => reportsRepository.getExecutionReports(agentRunIds, ruleIds)
        }
        t1 = System.nanoTime()
        _  = u1 += (t1 -t0)
        _ = TimingDebugLogger.trace(s"Compliance: get Execution Reports in batch for ${runInfos.size} runInfos: ${(t1 - t0)/1000}µs")
      } yield {
        val t2 = System.nanoTime()
        //we want to have nodeStatus for all asked node, not only the ones with reports
        val nodeStatusReports = runBatch.map { case (nodeId, runInfo) =>
          val status = ExecutionBatch.getNodeStatusReports(nodeId, runInfo, reports.getOrElse(nodeId, Seq()), unexpectedInterpretation)
          (status.nodeId, status)
        }
        val t3 = System.nanoTime()
        u2 += (t3 - t2)
        TimingDebugLogger.trace(s"Compliance: Computing nodeStatusReports in batch from execution batch: ${(t3 - t2)/1000}µs")
        nodeStatusReports
      }
    }
    TimingDebugLogger.trace(s"Compliance: get Execution Reports for ${runInfos.size} runInfos: ${u1/1000}µs")
    TimingDebugLogger.trace(s"Compliance: Computing nodeStatusReports from execution batch: ${u2/1000}µs")
    result.map(_.flatten.toMap)
  }
}
