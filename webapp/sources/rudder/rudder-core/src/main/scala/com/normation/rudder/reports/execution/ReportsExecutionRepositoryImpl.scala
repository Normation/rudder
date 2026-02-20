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

import cats.implicits.*
import com.normation.errors.IOResult
import com.normation.inventory.domain.NodeId
import com.normation.rudder.db.DB
import com.normation.rudder.db.Doobie
import com.normation.rudder.db.Doobie.*
import com.normation.rudder.domain.logger.TimingDebugLoggerPure
import com.normation.rudder.domain.reports.ExpectedReportsSerialisation
import com.normation.rudder.domain.reports.NodeAndConfigId
import com.normation.rudder.domain.reports.NodeConfigId
import com.normation.rudder.domain.reports.NodeExpectedReports
import com.normation.rudder.repository.jdbc.PostgresqlInClause
import com.normation.rudder.services.reports.NodeConfigurationService
import com.normation.zio.*
import doobie.*
import doobie.implicits.*
import net.liftweb.common.*
import org.joda.time.DateTime
import zio.*
import zio.interop.catz.*
import zio.syntax.*

final case class RoReportsExecutionRepositoryImpl(
    db:                Doobie,
    writeBackend:      WoReportsExecutionRepository,
    nodeConfigService: NodeConfigurationService,
    pgInClause:        PostgresqlInClause,
    jdbcMaxBatchSize:  Int
) extends RoReportsExecutionRepository with Loggable {

  import Doobie.*
  import db.*

  /**
   * Retrieve all runs that were not processed - for the moment, there are no limitation nor ordering/grouping
   */
  def getUnprocessedRuns(): IOResult[Seq[AgentRunWithoutCompliance]] = {
    transactIOResult(s"Error when getting unprocessed runs")(xa => {
      query[DB.UncomputedAgentRun](
        s"""SELECT nodeid, date, nodeconfigid, insertionid, insertiondate FROM ReportsExecution where compliancecomputationdate is null"""
      ).map(_.toAgentRunWithoutCompliance).to[Vector].transact(xa)
    })
  }

  def getNodesAndUncomputedCompliance(): IOResult[Map[NodeId, Option[AgentRunWithNodeConfig]]] = {
    for {
      n1              <- currentTimeMillis
      unprocessedRuns <- getUnprocessedRuns()
      // first evolution, get same behaviour than before, and returns only the last run per node
      // ignore those without a nodeConfigId
      lastRunByNode    = unprocessedRuns.filter(_.nodeConfigVersion.isDefined).groupBy(_.agentRunId.nodeId).map {
                           case (nodeid, seq) => (nodeid, seq.sortBy(_.agentRunId.date).last)
                         }
      // by construct, we do have a nodeConfigId
      agentsRuns       = lastRunByNode.map(x => (x._1, NodeAndConfigId(x._1, x._2.nodeConfigVersion.get)))

      expectedReports <- nodeConfigService.findNodeExpectedReports(agentsRuns.values.toSet)

      runs = agentsRuns.map {
               case (nodeId, nodeAndConfigId) =>
                 (
                   nodeId,
                   Some(
                     AgentRunWithNodeConfig(
                       AgentRunId(nodeId, lastRunByNode(nodeId).agentRunId.date),
                       expectedReports
                         .get(nodeAndConfigId)
                         .map(optionalExpectedReport => (nodeAndConfigId.version, optionalExpectedReport)),
                       lastRunByNode(nodeId).insertionId
                     )
                   )
                 )
             }
      // and finally mark them read. It's so much easier to do it now than to carry all data all the way long
      _   <- writeBackend.setComplianceComputationDate(unprocessedRuns.toList)
      n2  <- currentTimeMillis
      _   <- TimingDebugLoggerPure.trace(s"CachedReportsExecutionRepository: get nodes last run in: ${n2 - n1}ms")
    } yield {
      runs
    }
  }

  /**
   * For initialization of cache, fetch the last agent run that was computed, with the agent execution time
   *
   */
  def getLastComputedRun(nodeIds: Set[NodeId]): Box[Map[NodeId, (NodeAndConfigId, DateTime)]] = {
    val query: ConnectionIO[Map[NodeId, (NodeAndConfigId, DateTime)]] = for {
      runs <-
        (fr"""SELECT nodeid, date, nodeconfigid, true, insertionid
             |FROM ReportsExecution where (nodeid, date) in
             |( SELECT nodeid, max(date)) FROM ReportsExecution where compliancecomputationdate is not null group by nodeid)""".stripMargin)
          .query[DB.AgentRun]
          .to[Vector]
    } yield {
      runs.flatMap {
        case run =>
          val nodeId = NodeId(run.nodeId)
          if (nodeIds.contains(nodeId)) {
            Some((nodeId, (NodeAndConfigId(nodeId, NodeConfigId(run.nodeConfigId.get)), run.date)))
          } else {
            None
          }
      }.toMap
    }
    transactRunBox(xa => query.transact(xa))
  }

  /**
   * Retrieve last agent runs for the given nodes.
   * If none is known for a node, then returned None, so that the property
   * "nodeIds == returnedMap.keySet" holds.
   */
  override def getNodesLastRun(nodeIds: Set[NodeId]): IOResult[Map[NodeId, Option[AgentRunWithNodeConfig]]] = {
    // deserialization of nodeConfig from the outer join: just report the error + None
    def unserNodeConfig(
        opt1: Option[String],
        opt2: Option[String],
        opt3: Option[DateTime],
        opt4: Option[DateTime],
        opt5: Option[String]
    ) = {
      (opt1, opt2, opt3, opt4, opt5) match {
        case (Some(id), Some(config), Some(begin), end, Some(json)) =>
          ExpectedReportsSerialisation.parseJsonNodeExpectedReports(json) match {
            case Full(x) =>
              Some(
                NodeExpectedReports(
                  NodeId(id),
                  NodeConfigId(config),
                  begin,
                  end,
                  x.modes,
                  x.schedules,
                  x.ruleExpectedReports,
                  x.overrides
                )
              )
            case eb: EmptyBox =>
              val e = eb ?~! s"Error when deserialising node configuration for node with ID: ${id}, configId: ${config}"
              logger.error(e.messageChain)
              None
          }
        case _                                                      => None
      }
    }

    val batchedNodeConfigIds = nodeIds.grouped(jdbcMaxBatchSize).toSeq
    ZIO.foreach(batchedNodeConfigIds) { (ids: Set[NodeId]) =>
      // map node id to // ('node-id') // to use in values
      ids.map(id => s"('${id.value}')").toList match {
        case Nil   => Map[NodeId, Option[AgentRunWithNodeConfig]]().succeed
        case nodes =>
          // we can't use "Fragments.in", because of: https://github.com/tpolecat/doobie/issues/426
          // so we use:
          //  SELECT * FROM table where nodeid in (VALUES (a), (b), ...here some thousands more...)

          val innerFromFrag = (
            fr"""from (
                 select reportsexecution.nodeid, reportsexecution.date, reportsexecution.nodeconfigid, reportsexecution.insertionid from
                    reportsexecution where (nodeid, insertionid) in (
                      select nodeid, max(insertionid) as insertionid
                        from reportsexecution
                        where nodeid in """ ++
              Fragment.const(s"""(values ${nodes.mkString(",")} )""") ++
              fr"""
                        GROUP BY nodeid
                     )
                  ) as r"""
          )

          // the whole query

          val query: ConnectionIO[Map[NodeId, Option[AgentRunWithNodeConfig]]] = for {
            // Here to make the query faster, we distinct only on the reportexecution to get the last run
            // but we need to get the matching last entry on nodeconfigurations.
            // I didn't find any better solution than doing a distinct on the table
            runs <- (fr""" select r.nodeid, r.date, r.nodeconfigid, r.insertionid,
                         c.nodeid, c.nodeconfigid, c.begindate, c.enddate, c.configuration
                   """ ++
                    innerFromFrag ++
                    fr""" left outer join nodeconfigurations as c
                         on r.nodeId = c.nodeid and r.nodeconfigid = c.nodeconfigid
                     """)
                      .query[
                        //
                        // For some reason unknown of me, if we use Option[NodeId] for the parameter,
                        // we are getting the assertion fail from NodeId: "An UUID can not have a null or empty value (value: null)"
                        // But somehow, the null value is correctly transformed to None latter.
                        // So we have to use string, and tranform it node in unserNodeConfig. Strange.
                        //
                        (DB.AgentRun, Option[String], Option[String], Option[DateTime], Option[DateTime], Option[String])
                      ]
                      .map { case tuple @ (r, t1, t2, t3, t4, t5) => (r, unserNodeConfig(t1, t2, t3, t4, t5)) }
                      .to[Vector]
          } yield {

            val runsMap = (runs.map {
              case (r, optConfig) =>
                val run    = r.toAgentRun
                val config = run.nodeConfigVersion.map(c => (c, optConfig))
                (run.agentRunId.nodeId, AgentRunWithNodeConfig(run.agentRunId, config, run.insertionId))
            }).toMap
            ids.map(id => (id, runsMap.get(id))).toMap

          }

          transactIOResult(s"Error when trying to fetch node last agent runs information")(xa => query.transact(xa))
      }
    }
  }.map(_.flatten.toMap)
}

final case class WoReportsExecutionRepositoryImpl(
    db: Doobie
) extends WoReportsExecutionRepository with Loggable {

  import db.*

  def setComplianceComputationDate(runs: List[AgentRunWithoutCompliance]): IOResult[Int] = {
    val updateKeys = runs.map(x => (x.agentRunId.nodeId.value, x.agentRunId.date, x.nodeConfigVersion.map(_.value)))
    val sql        =
      """UPDATE reportsexecution set compliancecomputationdate = now() where nodeid = ? and date = ? and nodeconfigid = ?"""
    transactIOResult(s"Error when updating compliance computation date for runs")(xa =>
      Update[(String, DateTime, Option[String])](sql).updateMany(updateKeys).transact(xa)
    )
  }

}
