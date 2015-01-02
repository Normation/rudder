/*
*************************************************************************************
* Copyright 2013 Normation SAS
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

package com.normation.rudder.reports.execution

import com.normation.inventory.domain.NodeId
import net.liftweb.common._
import org.joda.time.DateTime
import com.normation.rudder.repository.CachedRepository

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
  def getNodesLastRun(nodeIds: Set[NodeId]): Box[Map[NodeId, Option[AgentRun]]]
}


trait WoReportsExecutionRepository {

  /**
   * Create or update the list of execution in the execution tables
   * Only return execution which where actually changed in backend
   *
   * The logic is:
   * - a new execution (not present in backend) is inserted as provided
   * - a existing execution can only change the completion status from
   *   "not completed" to "completed" (i.e: a completed execution can
   *   not be un-completed).
   */
  def updateExecutions(executions : Seq[AgentRun]) : Box[Seq[AgentRun]]

}

/**
 * A cached version of the service that only look in the underlying data (expected to
 * be slow) when no cache available.
 */
class CachedReportsExecutionRepository(
    readBackend : RoReportsExecutionRepository
  , writeBackend: WoReportsExecutionRepository
) extends RoReportsExecutionRepository with WoReportsExecutionRepository with CachedRepository {

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
  private[this] var cache = Map[NodeId, Option[AgentRun]]()


  override def clearCache(): Unit = this.synchronized {
    cache = Map()
  }

  override def getNodesLastRun(nodeIds: Set[NodeId]): Box[Map[NodeId, Option[AgentRun]]] = this.synchronized {
    for {
      runs <- readBackend.getNodesLastRun(nodeIds.diff(cache.keySet))
    } yield {
      cache = cache ++ runs
      cache.filterKeys { x => nodeIds.contains(x) }
    }
  }

  override def updateExecutions(executions : Seq[AgentRun]) : Box[Seq[AgentRun]] = this.synchronized {
    for {
      runs <- writeBackend.updateExecutions(executions)
    } yield {
      cache = cache ++ runs.map(x => (x.agentRunId.nodeId, Some(x))).toMap
      runs
    }
  }
}
