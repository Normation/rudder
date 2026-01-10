/*
 *************************************************************************************
 * Copyright 2013 Normation SAS
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

package com.normation.rudder.reports.execution

import com.normation.errors.*
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.logger.ComplianceDebugLoggerPure
import com.normation.rudder.domain.logger.TimingDebugLoggerPure
import com.normation.rudder.repository.CachedRepository
import com.normation.zio.*
import zio.*

/**
 * Service for reading or storing execution of Nodes
 */
trait RoReportsExecutionRepository {

  /**
   * Find the last execution of nodes, whatever is its state.
   * Last execution is defined as "the last executions that have been inserted in the database",
   * and do not rely on date (which can change too often)
   * The goal is to have reporting that does not depend on time, as node may have time in the future, or
   * past, or even change during their lifetime
   * So the last run are the last run inserted in the reports database
   * See ticket http://www.rudder-project.org/redmine/issues/6005
   */
  def getNodesLastRun(nodeIds: Set[NodeId]): IOResult[Map[NodeId, Option[AgentRunWithNodeConfig]]]

  def getNodesAndUncomputedCompliance(): IOResult[Map[NodeId, Option[AgentRunWithNodeConfig]]]

  /**
   * Retrieve all runs that were not processed - for the moment, there are no limitation nor ordering/grouping
   */
  def getUnprocessedRuns(): IOResult[Seq[AgentRunWithoutCompliance]]

}

trait WoReportsExecutionRepository {

  // Set the computation date of compliance for a run that had no compliance computed
  def setComplianceComputationDate(runs: List[AgentRunWithoutCompliance]): IOResult[Int]

}

/**
 * A cached version of the service that only look in the underlying data (expected to
 * be slow) when no cache available.
 */
class CachedReportsExecutionRepository(
    readBackend: RoReportsExecutionRepository
) extends RoReportsExecutionRepository with CachedRepository {

  /*
   * We need to synchronise on cache to avoid the case:
   * - initial state: RUNS_0 in backend
   * - write RUNS_1 (cache => None) : ok, only write in backend
   * [interrupt before actual write]
   * - read: cache = None => update cache with RUNS_0
   * - cache = RUNS_0
   * [resume write]
   * - write in backend RUNS_1
   *
   * => cache will never ses RUNS_1 before a clear.
   */

  /*
   * The cache is managed node by node, i.e it can be initialized
   * for certain and not for other.
   * The initialization criteria (and so, the fact that the cache
   * can be used for a given node) is given by the presence of the
   * nodeid in map's keys.
   */
  private val cacheRef: Ref.Synchronized[Map[NodeId, Option[AgentRunWithNodeConfig]]] =
    Ref.Synchronized.make(Map[NodeId, Option[AgentRunWithNodeConfig]]()).runNow

  override def clearCache(): Unit = cacheRef.set(Map()).runNow

  override def getNodesLastRun(nodeIds: Set[NodeId]): IOResult[Map[NodeId, Option[AgentRunWithNodeConfig]]] = cacheRef
    .updateAndGetZIO(f = cache => {
      for {
        n1   <- currentTimeMillis
        _    <- ComplianceDebugLoggerPure.trace(s"cache last run ; $cache")
        _    <- ComplianceDebugLoggerPure.trace(s"node id diff last run ; ${nodeIds.diff(cache.keySet)}")
        runs <- readBackend.getNodesLastRun(nodeIds.diff(cache.keySet))
        n2   <- currentTimeMillis
        _    <- TimingDebugLoggerPure.trace(s"CachedReportsExecutionRepository: get nodes last run in: ${n2 - n1}ms")
      } yield {
        cache ++ runs
      }
    })
    .map(_.view.filterKeys(x => nodeIds.contains(x)).toMap)
    .chainError(s"Error when trying to update the cache of Agent Runs information")

  def getUnprocessedRuns(): IOResult[Seq[AgentRunWithoutCompliance]] = readBackend.getUnprocessedRuns()

  /**
   * Retrieve all runs that were not processed - for the moment, there are no limitation nor ordering/grouping
   */
  def getNodesAndUncomputedCompliance(): IOResult[Map[NodeId, Option[AgentRunWithNodeConfig]]] = {
    for {
      runs         <- readBackend.getNodesAndUncomputedCompliance()
      updatedCache <- cacheRef.updateAndGet(cache => cache ++ runs)
    } yield {
      updatedCache.view.filterKeys(x => runs.contains(x)).toMap
    }
  }
}
