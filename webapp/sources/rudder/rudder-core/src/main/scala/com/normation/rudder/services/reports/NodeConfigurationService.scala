/*
 *************************************************************************************
 * Copyright 2021 Normation SAS
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
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.reports.NodeAndConfigId
import com.normation.rudder.domain.reports.NodeExpectedReports
import com.normation.rudder.facts.nodes.NodeFactRepository
import com.normation.rudder.facts.nodes.QueryContext
import com.normation.rudder.repository.CachedRepository
import com.normation.rudder.repository.FindExpectedReportRepository
import com.normation.zio.*
import zio.*
import zio.syntax.*

/**
 * That service retrieve node configurations (nodeexpectedreports) from the expectedreportsjdbcrepository, unless its already in cache
 * cache is driven by reporting serviceimpl
 * init add all nodes, withtout anything attached
 * initial setting of nodeexpectedreport is less prioritary than update
 * deletion removes the entry
 * if an entry exists but without anything, then it will query the database
 * if an entry exists but wthout the right nodeconfigid, it will query the database (but not update the cache)
 */
trait NodeConfigurationService {

  /**
   * retrieve expected reports by config version
   */
  def findNodeExpectedReports(
      nodeConfigIds: Set[NodeAndConfigId]
  ): IOResult[Map[NodeAndConfigId, Option[NodeExpectedReports]]]

  /**
   * get the current expected reports
   * fails if request expected reports for a non existent node
   */
  def getCurrentExpectedReports(nodeIds: Set[NodeId]): IOResult[Map[NodeId, Option[NodeExpectedReports]]]

  /**
   * get the nodes applying the rule
   *
   */
  def findNodesApplyingRule(ruleId:           RuleId):      IOResult[Set[NodeId]]
  def findNodesApplyingDirective(directiveId: DirectiveId): IOResult[Set[NodeId]]
}

trait NewExpectedReportsAvailableHook {
  def newExpectedReports(action: CacheExpectedReportAction): IOResult[Unit]
}

class CachedNodeConfigurationService(
    val confExpectedRepo:   FindExpectedReportRepository,
    val nodeFactRepository: NodeFactRepository
) extends NodeConfigurationService with CachedRepository with InvalidateCache[CacheExpectedReportAction] {

  val semaphore: Semaphore = Semaphore.make(1).runNow

  val logger = ReportLoggerPure.Cache

  /**
   * This part is ugly, but it is necessary
   * We need to talk to compliance cache to tell it to reset compliance when
   * configs are changed. However, the compliance cache needs this present class
   * So it's a stackoverflow
   * Workaround is to define hooks that will do the dirty works
   * Introduced in https://issues.rudder.io/issues/19740
   */
  var hooks: List[NewExpectedReportsAvailableHook] = Nil

  def addHook(hook: NewExpectedReportsAvailableHook): Unit = {
    hooks = hook :: hooks
  }

  /**
   * The cache is managed node by node.
   * A missing nodeId mean that the cache wasn't initialized for
   * that node, and should fail
   *
   * This cache is populated by ReportingServiceImpl:
   * * init by adding all existing nodes (and NodeExpectedReports if available)
   * * update after a policy generation to change the value for a nodeid
   * * add a new node when accepting a node
   * * deleting a node
   *
   * Note that a clear cache will None the cache
   *
   * A query to fetch nodeexpectedreports that is not in the cache will return None
   * (if node exists), or fail if node does not exist.
   *
   * Ref allows atomic action on the map, but concurrent non-atomic changes (ex: if
   * you need to something iterativelly to update the cache) still need to be behind a semaphore.
   */
  private val cache = Ref.make(Map.empty[NodeId, Option[NodeExpectedReports]]).runNow

  /**
   * The queue of invalidation request.
   * The queue size is 1 and new request need to merge with existing request
   * It's a List and not a Set, because we want to keep the precedence in
   * invalidation request.
   * // unsure if its a CacheComplianceQueueAction or another queueaction
   */
  private val invalidateNodeConfigurationRequest = Queue.dropping[Chunk[(NodeId, CacheExpectedReportAction)]](1).runNow

  /**
   * We need a semaphore to protect queue content merge-update
   */
  private val invalidateMergeUpdateSemaphore = Semaphore.make(1).runNow

  // Init to do
  // what's the best method ? init directly from db, fetching all nodeconfigurations
  // that are empty
  // or initing it with all nodes, and nothing in it, and only then fetching by batch
  // batching saves memory, but is slower
  // i think it's safer to do the batching part, but i'd like to be sure of that
  def init(): IOResult[Unit] = {
    for {
      _       <- logger.debug("Init cache in NodeConfigurationService")
      // first, get all nodes
      nodeIds <- nodeFactRepository.getAll()(using QueryContext.systemQC).map(_.keySet)
      // void the cache
      _       <- semaphore.withPermit(cache.set(nodeIds.map(_ -> None).toMap))
    } yield ()
  }

  /**
   * Update logic. We take message from queue one at a time, and process.
   * we need to keep order
   */
  val updateCacheFromRequest: IO[Nothing, Unit] = invalidateNodeConfigurationRequest.take.flatMap(invalidatedIds => {
    ZIO.foreachDiscard(invalidatedIds.map(_._2): Chunk[CacheExpectedReportAction])(action => {
      performAction(action).catchAll(err => {
        // when there is an error with an action on the cache, it can becomes inconsistant and we need to (try to) reinit it
        logger.error(s"Error when updating NodeConfiguration cache for node: [${action.nodeId.value}]: ${err.fullMsg}") *>
        init().catchAll(err => logger.error(s"NodeConfiguration cache re-init after error failed, please try to restart app"))
      })
    })
  })

  // start updating
  updateCacheFromRequest.forever.forkDaemon.runNow

  /**
   * Clear cache. Try a reload asynchronously, disregarding
   * the result
   */
  override def clearCache(): Unit = {
    init().runNow
    logger.logEffect.debug("Node expected reports cache cleared")
  }

  /**
   * Do something with the action we received
   */
  private def performAction(action: CacheExpectedReportAction): IOResult[Unit] = {
    import CacheExpectedReportAction.*
    // in a semaphore
    semaphore.withPermit(
      (action match {
        case insert: InsertNodeInCache       => cache.update(data => data + (insert.nodeId -> None))
        case delete: RemoveNodeInCache       => cache.update(data => data.removed(delete.nodeId))
        case update: UpdateNodeConfiguration =>
          cache.update(data => data + (update.nodeId -> Some(update.nodeConfiguration)))
      }) *>
      ZIO.foreachDiscard(hooks)(hook => hook.newExpectedReports(action))
      // complianceCache.get.invalidateWithAction(Seq((action.nodeId, CacheComplianceQueueAction.ExpectedReportAction(action))))
    )
  }

  /**
   * invalidate with an action to do something
   * order is important
   */
  override def invalidateWithAction(actions: Seq[(NodeId, CacheExpectedReportAction)]): IOResult[Unit] = {
    ZIO.when(actions.nonEmpty) {
      logger.debug(
        s"Node Configuration cache: invalidation request for nodes with action: [${actions.map(_._2.getClass.getSimpleName).mkString(",")}]"
      ) *>
      invalidateMergeUpdateSemaphore.withPermit(for {
        elements  <- invalidateNodeConfigurationRequest.takeAll
        allActions = (elements.flatten ++ actions)
        _         <- invalidateNodeConfigurationRequest.offer(allActions)
      } yield ())
    }
  }.unit

  /**
   * get the current expected reports
   */
  def getCurrentExpectedReports(nodeIds: Set[NodeId]): IOResult[Map[NodeId, Option[NodeExpectedReports]]] = {
    // add logging, to ensure that semaphoring the whole is not too blocking
    logger.logEffect.trace(
      s"Calling getCurrentExpectedReports - before semaphore for nodes ${nodeIds.map(_.value).mkString(", ")}"
    )
    val before_semaphoreTime = java.lang.System.currentTimeMillis

    // In a semaphore, nothing should change the cache
    semaphore.withPermit(for {
      timeInSemaphore <- currentTimeMillis
      _               <- logger.trace(s"Entered the semaphore after ${timeInSemaphore - before_semaphoreTime} ms")

      // First, get all nodes from cache (even the none)
      dataFromCache    <- cache.get.map(_.filter { case (nodeId, _) => nodeIds.contains(nodeId) })
      // now fetch others from database, if necessary
      // if the configuration is none, then cache isn't inited for it
      _                <- logger.trace(s"data from cache for expected reports is ${dataFromCache.values.mkString(", \n")}")
      dataUninitialized = dataFromCache.filter { case (nodeId, option) => option.isEmpty }.keySet
      fromDb           <- confExpectedRepo.getCurrentExpectedsReports(dataUninitialized).toIO
      _                <- logger.trace(s"Fetch from DB ${fromDb.size} current expected reports")

      // ? question ?
      // how to properly ensure that cache is synchro ?
      // We only process uninitialized nodes conf here, so we can only get better - ie, if
      // it a "none" config from db, well it was already that in `dataUninitialized`
      // All updates which could insert newer data in cache are processed in `performAction`, but blocked by the semaphore
      // So we can just merge the cache here
      _ <- cache.updateAndGet(_ ++ fromDb)
    } yield {
      // returns only the requested nodes
      dataFromCache ++ fromDb
    })
  }

  /**
   * get the nodes applying the rule
   *
   */
  def findNodesApplyingRule(ruleId: RuleId): IOResult[Set[NodeId]] = {
    // this don't need to be in a semaphore, since it's only one atomic cache read
    for {
      nodeConfs      <- cache.get
      nodesNotInCache = nodeConfs.collect { case (k, value) if (value.isEmpty) => k }.toSet
      dataFromCache   = {
        nodeConfs.collect {
          case (k, Some(nodeExpectedReports)) if (nodeExpectedReports.ruleExpectedReports.map(_.ruleId).contains(ruleId)) => k
        }.toSet
      }
      fromRepo       <- if (nodesNotInCache.isEmpty) {
                          Set.empty[NodeId].succeed
                        } else { // query the repo
                          confExpectedRepo.findCurrentNodeIdsForRule(ruleId, nodesNotInCache)
                        }
    } yield {
      dataFromCache ++ fromRepo
    }
  }

  /**
   * get the nodes applying the rule
   *
   */
  def findNodesApplyingDirective(directiveId: DirectiveId): IOResult[Set[NodeId]] = {
    // this don't need to be in a semaphore, since it's only one atomic cache read
    for {
      nodeConfs      <- cache.get
      nodesNotInCache = nodeConfs.collect { case (k, value) if (value.isEmpty) => k }.toSet
      dataFromCache   = {
        nodeConfs.collect {
          case (k, Some(nodeExpectedReports))
              if (nodeExpectedReports.ruleExpectedReports.flatMap(_.directives.map(_.directiveId)).contains(directiveId)) =>
            k
        }.toSet
      }
      fromRepo       <- if (nodesNotInCache.isEmpty) {
                          Set.empty[NodeId].succeed
                        } else { // query the repo
                          confExpectedRepo.findCurrentNodeIdsForDirective(directiveId, nodesNotInCache)
                        }
    } yield {
      dataFromCache ++ fromRepo
    }
  }

  /**
   * retrieve expected reports by config version
   */
  def findNodeExpectedReports(
      nodeConfigIds: Set[NodeAndConfigId]
  ): IOResult[Map[NodeAndConfigId, Option[NodeExpectedReports]]] = {
    // first get the config which are current (no enddate) from cache. It should be the majority, hopefully
    for {
      allCached           <- cache.get
      inCache              = allCached.map {
                               case (id, expected) =>
                                 expected match {
                                   case None                     => None
                                   case Some(nodeExpectedReport) =>
                                     val nodeAndConfigId = NodeAndConfigId(id, nodeExpectedReport.nodeConfigId)
                                     if (nodeConfigIds.contains(nodeAndConfigId)) {
                                       Some((nodeAndConfigId, expected)) // returns from the cache if it match
                                     } else {
                                       None
                                     }
                                 }
                             }.flatten.toMap
      // search for all others in repo
      // here, we could do something clever by filtering all those with None enddate and add them in repo
      // but I don't want to be double clever
      missingNodeConfigIds = nodeConfigIds -- inCache.keySet
      fromDb              <- confExpectedRepo.getExpectedReports(missingNodeConfigIds).toIO
    } yield {
      fromDb ++ inCache
    }
  }
}

/**
 * simple implementation
 * simply call the repo, as a passthrough
 */
class NodeConfigurationServiceImpl(
    confExpectedRepo: FindExpectedReportRepository
) extends NodeConfigurationService {

  /**
   * retrieve expected reports by config version
   */
  def findNodeExpectedReports(
      nodeConfigIds: Set[NodeAndConfigId]
  ): IOResult[Map[NodeAndConfigId, Option[NodeExpectedReports]]] = {
    confExpectedRepo.getExpectedReports(nodeConfigIds).toIO
  }

  /**
   * get the current expected reports
   * fails if request expected reports for a non existent node
   */
  def getCurrentExpectedReports(nodeIds: Set[NodeId]): IOResult[Map[NodeId, Option[NodeExpectedReports]]] = {
    confExpectedRepo.getCurrentExpectedsReports(nodeIds).toIO
  }

  /**
   * get the nodes applying the rule
   *
   */
  def findNodesApplyingRule(ruleId: RuleId): IOResult[Set[NodeId]] = {
    confExpectedRepo.findCurrentNodeIds(ruleId).toIO
  }

  /**
   * get the nodes applying the rule
   *
   */
  def findNodesApplyingDirective(directiveId: DirectiveId): IOResult[Set[NodeId]] = {
    confExpectedRepo.findCurrentNodeIdsForDirective(directiveId)
  }

}
