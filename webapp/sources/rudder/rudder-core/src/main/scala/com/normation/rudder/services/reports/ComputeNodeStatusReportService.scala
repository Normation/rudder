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
import com.normation.rudder.domain.logger.ComplianceLoggerPure
import com.normation.rudder.domain.logger.ReportLoggerPure
import com.normation.rudder.domain.logger.TimingDebugLoggerPure
import com.normation.rudder.domain.reports.*
import com.normation.rudder.facts.nodes.ChangeContext
import com.normation.rudder.facts.nodes.NodeFactRepository
import com.normation.rudder.facts.nodes.QueryContext
import com.normation.rudder.reports.ComplianceModeName
import com.normation.rudder.reports.GlobalComplianceMode
import com.normation.rudder.reports.ReportsDisabled
import com.normation.rudder.reports.execution.AgentRunId
import com.normation.rudder.reports.execution.AgentRunWithNodeConfig
import com.normation.rudder.reports.execution.RoReportsExecutionRepository
import com.normation.rudder.repository.*
import com.normation.rudder.score.ComplianceScoreEvent
import com.normation.rudder.score.ScoreServiceManager
import com.normation.utils.DateFormaterService
import com.normation.zio.*
import com.softwaremill.quicklens.*
import org.joda.time.*
import zio.{System as _, *}
import zio.syntax.*

/*
 * The role of that service is to compute NodeStatusReports when things
 * change. It then async-call dependent repositories/services to
 * update their information with the new compliance values.
 */

/**
 * Action that can be done on the Compliance Cache
 * The queue receive action, that are processed in FIFO, to change the cache content
 * All the possible actions are:
 * * insert a node in the cache (when a new node is accepted)
 * * remove a node from the cache (when the node is deleted)
 * * initialize compliance
 * * update compliance with a new run (with the new compliance)
 * * init node configuration (at application startup for example - if an existing node configuration is stored for this node, this is discared)
 * * update node configuration (after a policy generation, with new nodeconfiguration)
 * * set the node in node answer state (with the new compliance?)
 */
sealed trait CacheExpectedReportAction { def nodeId: NodeId }
object CacheExpectedReportAction       {
  final case class InsertNodeInCache(nodeId: NodeId) extends CacheExpectedReportAction
  final case class RemoveNodeInCache(nodeId: NodeId) extends CacheExpectedReportAction
  final case class UpdateNodeConfiguration(nodeId: NodeId, nodeConfiguration: NodeExpectedReports)
      extends CacheExpectedReportAction // convert the nodestatursreport to pending, with info from last run
}

sealed trait CacheComplianceQueueAction { def nodeId: NodeId }
object CacheComplianceQueueAction       {
  final case class ExpectedReportAction(action: CacheExpectedReportAction)            extends CacheComplianceQueueAction {
    def nodeId = action.nodeId
  }
  final case class UpdateCompliance(nodeId: NodeId, nodeCompliance: NodeStatusReport) extends CacheComplianceQueueAction
  final case class ExpiredCompliance(nodeId: NodeId)                                  extends CacheComplianceQueueAction
}

trait InvalidateCache[T] {
  def invalidateWithAction(actions: Seq[(NodeId, T)]): IOResult[Unit]
}

trait ComputeNodeStatusReportService extends InvalidateCache[CacheComplianceQueueAction] with NewExpectedReportsAvailableHook {

  override def newExpectedReports(action: CacheExpectedReportAction): IOResult[Unit] = {
    invalidateWithAction(
      Seq((action.nodeId, CacheComplianceQueueAction.ExpectedReportAction(action)))
    )
  }

  /**
   * Find in cache all outdated compliance, and add to queue to recompute them.
   * `ignoreNodes` will be ignored for the search (ie they won't be marked expired
   * in any case).
   */
  def outDatedCompliance(now: DateTime, ignoreNodes: Set[NodeId]): IOResult[Unit]
}

trait HasNodeStatusReportUpdateHook {
  def addHook(h: NodeStatusReportUpdateHook): UIO[Unit]
}

trait NodeStatusReportUpdateHook {
  def name:     String
  def onUpdate: Iterable[(NodeId, NodeStatusReport)] => UIO[Unit]
}

class ScoreNodeStatusReportUpdateHook(scoreServiceManager: ScoreServiceManager) extends NodeStatusReportUpdateHook {
  override val name:     String                                            = "UpdateScoreOnNodeComplianceUpdate"
  override def onUpdate: Iterable[(NodeId, NodeStatusReport)] => UIO[Unit] = { nodeWithCompliances =>
    ZIO.foreachDiscard(nodeWithCompliances) {
      case (id, report) =>
        val cp    = report.baseCompliance.computePercent()
        val event = ComplianceScoreEvent(id, cp)

        ReportLoggerPure.trace(s"Updating compliance score for node '${id.value}' with values: ${cp}'") *>
        scoreServiceManager.handleEvent(event)
    }
  }
}

/**
 * Manage the computation of node status reports.
 * The logic is:
 * - access to `findRuleNodeStatusReports` use data from cache and returns immediately,
 *   filtering for expired reports
 * - the only path to update the cache is through an async blocking queue of `InvalidateComplianceCacheMsg`
 * - the dequeue actor computes updated reports for invalidated nodes and update the NodeStatusReportsRepo
 *   and then, in case of success, the hooks accordingly.
 */
class ComputeNodeStatusReportServiceImpl(
    nodeRepo:                    NodeFactRepository,
    nsrRepo:                     NodeStatusReportRepository,
    findNewNodeStatusReports:    FindNewNodeStatusReports,
    complianceExpirationService: ComplianceExpirationService,
    hooksRef:                    Ref[Chunk[NodeStatusReportUpdateHook]],
    batchSize:                   Int
) extends ComputeNodeStatusReportService with HasNodeStatusReportUpdateHook {

  // add hook in last position of post update hooks
  def addHook(h: NodeStatusReportUpdateHook): UIO[Unit] = {
    hooksRef.update(_ :+ h)
  }

  /**
   * The queue of invalidation request.
   * The size is based on the number of nodes x 10, with a minimum of 1000.
   *   The reasoning is that we can always live with a 1000 elements queue, and it will handle the
   *   cases when Rudder is bootstrapped and nodes are added to it.
   * The strategy is sliding, meaning that newer invalidation will be kept against older ones.
   */
  private val (queueSize, invalidateComplianceRequest) = {
    implicit val qc = QueryContext.systemQC
    for {
      nbNodes <- nodeRepo.getAll().map(_.size)
      size     = Math.max(1000, 10 * nbNodes)
      queue   <- Queue.sliding[(NodeId, CacheComplianceQueueAction)](size)
    } yield (size, queue)
  }.runNow

  /**
   * Update logic. We take message from queue all in one time, and we sort/process them.
   * Becareful, `takeAll` is non blocking, while `takeBetween` is.
   */
  private val updateCacheFromRequest: IO[Nothing, Unit] = {
    invalidateComplianceRequest.takeBetween(1, queueSize).flatMap(processComplianceInvalidationActions)
  }

  // start updating
  updateCacheFromRequest.forever.forkDaemon.runNow

  private def processComplianceInvalidationActions(actions: Chunk[(NodeId, CacheComplianceQueueAction)]): UIO[Unit] = {
    ZIO.foreachDiscard(groupQueueActionByType(actions.map(x => x._2)))(actions =>
      // several strategy:
      // * we have a compliance: yeah, put it in the cache
      // * new policy generation, a new nodeexpectedreports is available: compute compliance for last run of the node, based on this nodeexpectedreports
      // * node deletion: remove from the cache
      // * invalidate cache: ???
      // * no report from the node (compliance expires): recompute compliance
      {
        (for {
          _ <- performAction(actions)
        } yield ()).catchAll(err => {
          ReportLoggerPure.Cache.error(
            s"Error when updating compliance cache for nodes: [${actions.map(_.nodeId).map(_.value).mkString(", ")}]: ${err.fullMsg}"
          )
        })
      }
    )

  }

  /**
   * Do something with the action we received
   * All actions must have *exactly* the *same* type
   * WARNING: do not put I/O or slow computation here, else divergence can appear:
   * https://github.com/Normation/rudder/pull/5737
   * UPDATE: in 8.2, we are trying that back, since now the compliance are in
   * NodeStatusReportRepository.
   */
  private def performAction(actions: Chunk[CacheComplianceQueueAction]): IOResult[Unit] = {
    import com.normation.rudder.services.reports.CacheComplianceQueueAction.*
    import com.normation.rudder.services.reports.CacheExpectedReportAction.*

    // get type of action
    (actions.headOption match {
      case None    => ReportLoggerPure.Cache.debug("Nothing to do")
      case Some(t) =>
        t match {
          case update: UpdateCompliance =>
            ReportLoggerPure.Cache.debug(s"Compliance cache updated for nodes: ${actions.map(_.nodeId.value).mkString(", ")}") *>
            // all action should be homogeneous, but still, fails on other cases
            (for {
              updates <- ZIO.foreach(actions) {
                           case a =>
                             a match {
                               case x: UpdateCompliance => (x.nodeId, x.nodeCompliance).succeed
                               case x =>
                                 Inconsistency(s"Error: found an action of incorrect type in an 'update' for cache: ${x}").fail
                             }
                         }
              _       <- saveUpdatedCompliance(updates)
            } yield ())

          case ExpectedReportAction((RemoveNodeInCache(_))) =>
            for {
              deletes <- ZIO.foreach(actions) {
                           case a =>
                             a match {
                               case ExpectedReportAction((RemoveNodeInCache(nodeId))) => nodeId.succeed
                               case x                                                 =>
                                 Inconsistency(s"Error: found an action of incorrect type in a 'delete' for cache: ${x}").fail
                             }
                         }
              _       <- nsrRepo.deleteNodeStatusReports(deletes)(ChangeContext.newForRudder())
            } yield ()

          // need to compute compliance
          case ExpiredCompliance(_) | ExpectedReportAction(InsertNodeInCache(_)) |
              ExpectedReportAction(UpdateNodeConfiguration(_, _)) =>
            val impactedNodeIds = actions.map(x => x.nodeId)
            for {
              _ <- ZIO.foreach(impactedNodeIds.grouped(batchSize).to(Seq)) { updatedNodes =>
                     for {
                       _       <- ReportLoggerPure.Cache.debug(s"Nodes asked to recompute compliance: ${updatedNodes.size}")
                       _       <- ReportLoggerPure.Cache.trace(
                                    s"Nodes asked to recompute compliance: ${updatedNodes.map(_.value).mkString(", ")}"
                                  )
                       updated <- findNewNodeStatusReports
                                    .findRuleNodeStatusReports(updatedNodes.toSet)(QueryContext.systemQC)
                       _       <- saveUpdatedCompliance(updated)
                     } yield ()
                   }
            } yield ()

        }
    })
  }

  /*
   * handle the save and hook trigger for updated compliance in a centralized place.
   */
  private def saveUpdatedCompliance(newReports: Iterable[(NodeId, NodeStatusReport)]): IOResult[Unit] = {
    for {
      now     <- currentTimeMillis
      _       <- ReportLoggerPure.Cache.trace(
                   s"Reports to save: ${newReports.map { case (id, r) => s"${id.value}: ${r.runInfo}" }.mkString("\n  ")}"
                 )
      kept    <- updateKeepCompliance(new DateTime(now), newReports)
      updated <- nsrRepo.saveNodeStatusReports(kept)(ChangeContext.newForRudder())
      // exec hooks
      hooks   <- hooksRef.get
      _       <- ZIO.foreachDiscard(hooks)(_.onUpdate(updated))
      _       <- ReportLoggerPure.Cache.debug(
                   s"Compliance recomputed, saved and score updated for nodes: ${updated.map(_._1.value).mkString(", ")}"
                 )
      _       <- ReportLoggerPure.Cache.trace(
                   s"Reports actually saved: ${updated.map { case (id, r) => s"${id.value}: ${r.runInfo}" }.mkString("\n  ")}"
                 )
    } yield ()
  }

  /*
   * Filter out reports that are marked as "NoReportInInterval" but have a policy to keep them longer
   */
  private[reports] def updateKeepCompliance(
      now:     DateTime,
      reports: Iterable[(NodeId, NodeStatusReport)]
  ): IOResult[Iterable[(NodeId, NodeStatusReport)]] = {

    val expired = reports.collect { case (id, r) if r.runInfo.kind == RunAnalysisKind.NoReportInInterval => id }
    for {
      expirationPolicy <- complianceExpirationService.getExpirationPolicy(expired)
      res              <- ZIO.foreach(reports) {
                            case (id, r) =>
                              r.runInfo.kind match {
                                case RunAnalysisKind.NoReportInInterval =>
                                  (expirationPolicy.get(id), r.runInfo.expirationDateTime) match {
                                    case (Some(NodeComplianceExpiration(NodeComplianceExpirationMode.KeepLast, Some(d))), Some(expiration)) =>
                                      val keepUntil = expiration.plus(d.toMillis)
                                      if (now.isBefore(keepUntil)) {
                                        for {
                                          _ <-
                                            ComplianceLoggerPure.info(
                                              s"Node with id '${id.value}' hasn't send report in expected time but " +
                                              s"${NodePropertyBasedComplianceExpirationService.PROP_NAME}.${NodePropertyBasedComplianceExpirationService.PROP_SUB_NAME} " +
                                              s"is configured to keep compliance for ${d}: waiting until ${keepUntil.toString(DateFormaterService.rfcDateformat)}"
                                            )
                                          _ <- ComplianceLoggerPure.trace(s"Last run info for '${id.value}': ${r.runInfo.debugString}")
                                          k  = r
                                                 .modify(_.runInfo.kind)
                                                 .setTo(RunAnalysisKind.KeepLastCompliance)
                                                 .modify(_.runInfo.expiredSince)
                                                 .setTo(r.runInfo.expirationDateTime)
                                                 .modify(_.runInfo.expirationDateTime)
                                                 .setTo(Some(keepUntil))
                                          _ <- ComplianceLoggerPure.trace(s"Updated run to keep for '${id.value}': ${k.runInfo.debugString}")
                                        } yield (id, k)
                                      } else {
                                        ComplianceLoggerPure.debug(
                                          s"Node with id '${id.value}' hasn't send report in expected time and the additional duration from policy expired at ${keepUntil
                                              .toString(DateFormaterService.rfcDateformat)}"
                                        ) *>
                                        (id, r).succeed
                                      }

                                    case _ =>
                                      ComplianceLoggerPure.debug(
                                        s"Node with id '${id.value}' hasn't send report in expected time and compliance expiration policy expires immediately (default)"
                                      ) *>
                                      (id, r).succeed
                                  }

                                case _ =>
                                  (id, r).succeed
                              }
                          }
    } yield {
      res
    }
  }

  /**
   * Group all actions queue by the same type, keeping the global order.
   * It is necessary to keep global order so that we serialize compliance in order
   * and don't lose information
   */
  protected[reports] def groupQueueActionByType(
      l: Chunk[CacheComplianceQueueAction]
  ): Chunk[Chunk[CacheComplianceQueueAction]] = {

    (l.foldLeft(Chunk.empty[Chunk[CacheComplianceQueueAction]]) {
      case (acc, action) =>
        acc.headOption match {
          // init
          case None        => Chunk(Chunk(action))
          case Some(chunk) =>
            chunk.headOption.map(_.getClass) match {
              case None                                            =>
                // Should not happen, our chunks are never empty, maybe use NEL?
                Chunk(action) +: acc.tail
              // Class is the same as action accumulate
              case Some(className) if action.getClass == className =>
                (chunk :+ action) +: acc.tail
              case _                                               =>
                // not same class, create a new head chunk that we will check
                Chunk(action) +: acc
            }
        }
    }).reverse

  }

  /**
   * invalidate with an action to do something
   * order is important
   */
  override def invalidateWithAction(actions: Seq[(NodeId, CacheComplianceQueueAction)]): IOResult[Unit] = {
    ZIO
      .when(actions.nonEmpty) {
        ReportLoggerPure.Cache.debug(
          s"Compliance cache: invalidation request for nodes with action: [${actions.map(_._1).map(_.value).mkString(",")}]"
        ) *>
        invalidateComplianceRequest.offerAll(actions)
      }
      .unit
  }

  override def newExpectedReports(action: CacheExpectedReportAction): IOResult[Unit] = {
    invalidateWithAction(
      Seq((action.nodeId, CacheComplianceQueueAction.ExpectedReportAction(action)))
    )
  }

  /**
   * Find in cache all outdated compliance, and add to queue to recompute them
   */
  override def outDatedCompliance(now: DateTime, ignoreNodes: Set[NodeId]): IOResult[Unit] = {
    import com.normation.rudder.domain.reports.RunAnalysisKind.*
    nsrRepo.getAll()(QueryContext.systemQC).flatMap { cache =>
      val nodeWithOutdatedCompliance = cache.filter {
        case (id, compliance) =>
          if (ignoreNodes.contains(id)) false
          else {
            compliance.runInfo.kind match {
              // here, we only want to check for compliance that expires when time pass and we
              // were in a good state. Bad states need an external action (new config, new runs)
              // to change back to good state

              // bad status need an external thing beyond time passing
              case NoRunNoExpectedReport | NoExpectedReport | NoUserRulesDefined | NoReportInInterval |
                  UnexpectedVersion | UnexpectedNoVersion | UnexpectedUnknownVersion | ReportsDisabledInInterval =>
                false
              // good status that can become bad
              case ComputeCompliance | KeepLastCompliance | Pending =>
                compliance.runInfo.expirationDateTime match {
                  case Some(t) => t.isBefore(now)
                  // if there is no expiration date time, we are in a non-standard situation
                  // and we need to wait for external change
                  case None    => false
                }
            }
          }
      }.toSeq

      if (nodeWithOutdatedCompliance.isEmpty) {
        ReportLoggerPure.Cache.trace("Compliance status isn't expired for any node")
      } else {
        ReportLoggerPure.Cache.debug(
          s"Compliance status is expired for nodes: ${nodeWithOutdatedCompliance.map(_._1.value).mkString(", ")}"
        ) *>
        ReportLoggerPure.Cache.trace(
          s"Status of expired compliance: ${nodeWithOutdatedCompliance.map { case (i, s) => s"${i.value}: ${s}" }.mkString(", ")}"
        ) *>
        // send outdated message to queue
        invalidateWithAction(nodeWithOutdatedCompliance.map(x => (x._1, CacheComplianceQueueAction.ExpiredCompliance(x._1))))
      }
    }
  }

}

/*
 * The root method that allows to find new node status reports from base
 */
trait FindNewNodeStatusReports {
  def findRuleNodeStatusReports(nodeIds: Set[NodeId])(implicit
      qc: QueryContext
  ): IOResult[Map[NodeId, NodeStatusReport]]

  def buildNodeStatusReports(
      runInfos:           Map[NodeId, RunAndConfigInfo],
      complianceModeName: ComplianceModeName
  ): IOResult[Map[NodeId, NodeStatusReport]]

  def getNodeRunInfos(
      nodeIds:        Set[NodeId],
      complianceMode: GlobalComplianceMode
  ): IOResult[Map[NodeId, RunAndConfigInfo]]

  def findUncomputedNodeStatusReports(): IOResult[Map[NodeId, NodeStatusReport]]

}

class FindNewNodeStatusReportsImpl(
    confExpectedRepo:        FindExpectedReportRepository,
    nodeConfigService:       NodeConfigurationService,
    reportsRepository:       ReportsRepository,
    agentRunRepository:      RoReportsExecutionRepository,
    getGlobalComplianceMode: () => IOResult[GlobalComplianceMode],
    jdbcMaxBatchSize:        Int
) extends FindNewNodeStatusReports {

  override def findRuleNodeStatusReports(nodeIds: Set[NodeId])(implicit
      qc: QueryContext
  ): IOResult[Map[NodeId, NodeStatusReport]] = {
    /*
     * This is the main logic point to get reports.
     *
     * Compliance for a given node is a function of ONLY(expeexpectedNodeConfigIdctedNodeConfigId, lastReceivedAgentRun).
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
    for {
      t0             <- currentTimeMillis
      complianceMode <- getGlobalComplianceMode()
      // we want compliance on these nodes
      runInfos       <- getNodeRunInfos(nodeIds, complianceMode)
      t1             <- currentTimeMillis
      _              <- TimingDebugLoggerPure.trace(s"Compliance: get node run infos: ${t1 - t0}ms")

      // compute the status
      nodeStatusReports <- buildNodeStatusReports(runInfos, complianceMode.mode)

      t2 <- currentTimeMillis
      _  <- TimingDebugLoggerPure.debug(s"Compliance: compute compliance reports: ${t2 - t1}ms")
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
  override def getNodeRunInfos(
      nodeIds:        Set[NodeId],
      complianceMode: GlobalComplianceMode
  ): IOResult[Map[NodeId, RunAndConfigInfo]] = {
    for {
      t0         <- currentTimeMillis
      runs       <- complianceMode.mode match {
                      // this is an optimisation to avoid querying the db in that case
                      case ReportsDisabled => nodeIds.map(id => (id, None)).toMap.succeed
                      case _               => agentRunRepository.getNodesLastRun(nodeIds)
                    }
      t1         <- currentTimeMillis
      _          <- TimingDebugLoggerPure.trace(s"Compliance: get nodes last run : ${t1 - t0}ms")
      compliance <- computeNodeStatusReportsForRuns(runs)
    } yield {
      compliance
    }
  }

  private def getUnComputedNodeRunInfos(): IOResult[Map[NodeId, RunAndConfigInfo]] = {
    for {
      t0         <- currentTimeMillis
      runs       <- agentRunRepository.getNodesAndUncomputedCompliance()
      t1         <- currentTimeMillis
      _          <- TimingDebugLoggerPure.trace(s"Compliance: get nodes last run : ${t1 - t0}ms")
      compliance <- computeNodeStatusReportsForRuns(runs)
    } yield {
      compliance
    }
  }

  private def computeNodeStatusReportsForRuns(
      runs: Map[NodeId, Option[AgentRunWithNodeConfig]]
  ): IOResult[Map[NodeId, RunAndConfigInfo]] = {
    for {
      t1                <- currentTimeMillis
      nodeIds            = runs.keys.toSet
      currentConfigs    <- nodeConfigService.getCurrentExpectedReports(nodeIds)
      t2                <- currentTimeMillis
      _                 <- TimingDebugLoggerPure.trace(s"Compliance: get current expected reports: ${t2 - t1}ms")
      nodeConfigIdInfos <- confExpectedRepo.getNodeConfigIdInfos(nodeIds)
      t3                <- currentTimeMillis
      _                 <- TimingDebugLoggerPure.trace(s"Compliance: get Node Config Id Infos: ${t3 - t2}ms")
    } yield {
      ExecutionBatch.computeNodesRunInfo(runs, currentConfigs, nodeConfigIdInfos, DateTime.now())
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
  override def buildNodeStatusReports(
      runInfos:           Map[NodeId, RunAndConfigInfo],
      complianceModeName: ComplianceModeName
  ): IOResult[Map[NodeId, NodeStatusReport]] = {

    val batchedRunsInfos = runInfos.grouped(jdbcMaxBatchSize).toSeq
    val result           = ZIO.foreach(batchedRunsInfos) { runBatch =>
      /*
       * We want to optimize and only query reports for nodes that we
       * actually want to merge/compare or report as unexpected reports
       */
      val agentRunIds = runBatch.flatMap {
        case (nodeId, run) =>
          run match {
            case r: LastRunAvailable => Some(AgentRunId(nodeId, r.lastRunDateTime))
            case Pending(_, Some(r), _) => Some(AgentRunId(nodeId, r._1))
            case _                      => None
          }
      }.toSet

      for {
        t0               <- currentTimeNanos
        /*
         * now get reports for agent rules.
         *
         * We don't want to do the query if we are in "reports-disabled" mode, since in that mode,
         * either we don't have reports (expected) or we have reports that will be out of date
         * (for ex. just after a change in the option).
         */
        u1               <- Ref.make(0L)
        u2               <- Ref.make(0L)
        reports          <- complianceModeName match {
                              case ReportsDisabled => Map[NodeId, Seq[Reports]]().succeed
                              case _               => reportsRepository.getExecutionReports(agentRunIds)
                            }
        t1               <- currentTimeNanos
        _                <- u1.update(_ + (t1 - t0))
        _                <- TimingDebugLoggerPure.trace(
                              s"Compliance: get Execution Reports in batch for ${runInfos.size} runInfos: ${(t1 - t0) / 1000}µs"
                            )
        t2               <- currentTimeNanos
        // we want to have nodeStatus for all asked node, not only the ones with reports
        nodeStatusReports = runBatch.map {
                              case (nodeId, runInfo) =>
                                val status = {
                                  ExecutionBatch.getNodeStatusReports(
                                    nodeId,
                                    runInfo,
                                    reports.getOrElse(nodeId, Seq())
                                  )
                                }
                                (status.nodeId, status)
                            }
        t3               <- currentTimeNanos
        _                <- u2.update(_ + (t3 - t2))
        _                <- TimingDebugLoggerPure.trace(
                              s"Compliance: Computing nodeStatusReports in batch from execution batch: ${(t3 - t2) / 1000}µs"
                            )
        u1time           <- u1.get
        u2time           <- u2.get
        _                <- TimingDebugLoggerPure.trace(s"Compliance: get Execution Reports for ${runInfos.size} runInfos: ${u1time / 1000}µs")
        _                <- TimingDebugLoggerPure.trace(s"Compliance: Computing nodeStatusReports from execution batch: ${u2time / 1000}µs")
      } yield {
        nodeStatusReports
      }
    }
    result.map(_.flatten.toMap)
  }

  override def findUncomputedNodeStatusReports(): IOResult[Map[NodeId, NodeStatusReport]] = {
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
    for {
      t0             <- currentTimeMillis
      complianceMode <- getGlobalComplianceMode()
      // get untreated runs
      uncomputedRuns <- getUnComputedNodeRunInfos()
      t1             <- currentTimeMillis
      _              <- TimingDebugLoggerPure.trace(s"Compliance: get uncomputed node run infos: ${t1 - t0}ms")

      // compute the status
      nodeStatusReports <-
        buildNodeStatusReports(uncomputedRuns, complianceMode.mode)
      t2                <- currentTimeMillis
      _                 <- TimingDebugLoggerPure.debug(s"Compliance: compute compliance reports: ${t2 - t1}ms")
    } yield {
      nodeStatusReports
    }
  }

}
