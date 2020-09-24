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

import com.normation.inventory.domain.NodeId
import net.liftweb.common._
import com.normation.rudder.repository.CachedRepository
import com.normation.rudder.domain.logger.{ReportLogger, TimingDebugLogger}
import com.normation.rudder.domain.reports.NodeAndConfigId
import com.normation.rudder.repository.FindExpectedReportRepository
import zio._
import com.normation.zio._
import com.normation.errors._


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
  def getNodesLastRun(nodeIds: Set[NodeId]): Box[Map[NodeId, Option[AgentRunWithNodeConfig]]]

  def getNodesAndUncomputedCompliance(): IOResult[Map[NodeId, Option[AgentRunWithNodeConfig]]]

  /**
   * Retrieve all runs that were not processed - for the moment, there are no limitation nor ordering/grouping
   */
  def getUnprocessedRuns(): IOResult[Seq[AgentRunWithoutCompliance]]

  //def getLastComputedRun(nodeIds: Set[NodeId]): Box[Map[NodeId, (NodeAndConfigId, DateTime)]]
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
    readBackend : RoReportsExecutionRepository
  , writeBackend: WoReportsExecutionRepository
  , findConfigs : FindExpectedReportRepository
) extends RoReportsExecutionRepository with WoReportsExecutionRepository with CachedRepository {

  val logger = ReportLogger
  val semaphore = Semaphore.make(1).runNow

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
  private[this] var cache = Map[NodeId, Option[AgentRunWithNodeConfig]]()


  override def clearCache(): Unit = semaphore.withPermit(IOResult.effect {
    cache = Map()
  }).runNow

  override def getNodesLastRun(nodeIds: Set[NodeId]): Box[Map[NodeId, Option[AgentRunWithNodeConfig]]] = semaphore.withPermit(IOResult.effect {
    val n1 = System.currentTimeMillis
    (for {
      runs <- readBackend.getNodesLastRun(nodeIds.diff(cache.keySet))
    } yield {
      val n2 = System.currentTimeMillis
      TimingDebugLogger.trace(s"CachedReportsExecutionRepository: get nodes last run in: ${n2 - n1}ms")
      cache = cache ++ runs
      cache.view.filterKeys { x => nodeIds.contains(x) }.toMap
    }) ?~! s"Error when trying to update the cache of Agent Runs informations"
  }).runNow

  override def getUnprocessedRuns(): IOResult[Seq[AgentRunWithoutCompliance]] = {
    ???
  }

  /**
   * Retrieve all runs that were not processed - for the moment, there are no limitation nor ordering/grouping
   */
  def getNodesAndUncomputedCompliance(): IOResult[Map[NodeId, Option[AgentRunWithNodeConfig]]] = semaphore.withPermit(IOResult.effect {
    val n1 = System.currentTimeMillis
    (for {
      unprocessedRuns <- readBackend.getUnprocessedRuns
      // first evolution, get same behaviour than before, and returns only the last run per node
      // ignore those without a nodeConfigId
      lastRunByNode = unprocessedRuns.filter(_.nodeConfigVersion.isDefined).groupBy(_.agentRunId.nodeId).map {
        case (nodeid, seq) => (nodeid, seq.sortBy(_.agentRunId.date).last)
      }
      // by construct, we do have a nodeConfigId
      agentsRuns =   lastRunByNode.map(x => (x._1, NodeAndConfigId(x._1, x._2.nodeConfigVersion.get)))

      expectedReports <- findConfigs.getExpectedReports(agentsRuns.values.toSet).toIO

      runs = agentsRuns.map { case (nodeId, nodeAndConfigId) =>
        (nodeId,
          Some(AgentRunWithNodeConfig(
               AgentRunId(nodeId, lastRunByNode(nodeId).agentRunId.date)
             , expectedReports.get(nodeAndConfigId).map(optionalExpectedReport => (nodeAndConfigId.version, optionalExpectedReport))
             , true
             , lastRunByNode(nodeId).insertionId)))}
      // and finally mark them read. It's so much easier to do it now than to carry all data all the way long
      _  <- writeBackend.setComplianceComputationDate(unprocessedRuns.toList)
    } yield {
      val n2 = System.currentTimeMillis
      TimingDebugLogger.trace(s"CachedReportsExecutionRepository: get nodes last run in: ${n2 - n1}ms")
      cache = cache ++ runs

      cache.view.filterKeys { x => runs.contains(x) }.toMap
    })
  }).runNow

  def setComplianceComputationDate(runs: List[AgentRunWithoutCompliance]): IOResult[Int] = ???
}
