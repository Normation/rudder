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
import com.normation.rudder.db.DB
import com.normation.rudder.repository.jdbc.PostgresqlInClause
import net.liftweb.common._
import com.normation.rudder.db.Doobie
import com.normation.rudder.db.Doobie._
import doobie._
import doobie.implicits._
import cats.implicits._
import com.normation.errors.IOResult
import com.normation.rudder.db.DB.UncomputedAgentRun
import com.normation.utils.Control.sequence
import zio._
import com.normation.rudder.domain.reports.{ExpectedReportsSerialisation, NodeConfigId, NodeExpectedReports, NodeStatusReport}
import org.joda.time.DateTime
import com.normation.zio.ZioRuntime
import zio.interop.catz._

final case class RoReportsExecutionRepositoryImpl (
    db              : Doobie
  , pgInClause      : PostgresqlInClause
  , jdbcMaxBatchSize: Int
) extends RoReportsExecutionRepository with Loggable {

  import Doobie._
  import db._

  /**
   * Retrieve all runs that were not processed - for the moment, there are no limitation nor ordering/grouping
   */
  def getUnprocessedRuns(): IOResult[Seq[UncomputedAgentRun]] = {
    transactIOResult(s"Error when getting unprocessed runs")(xa => query[DB.UncomputedAgentRun](
      s"""SELECT nodeid, date, nodeconfigid, insertionid, insertiondate FROM ReportsExecution where compliancecomputatiodate is null"""
    ).to[Vector].transact(xa))
  }
  def getNodesLastRunv2(): IOResult[Map[NodeId, Option[AgentRunWithNodeConfig]]] = ???

  /**
   * Retrieve last agent runs for the given nodes.
   * If none is known for a node, then returned None, so that the property
   * "nodeIds == returnedMap.keySet" holds.
   */
  override def getNodesLastRun(nodeIds: Set[NodeId]): Box[Map[NodeId, Option[AgentRunWithNodeConfig]]] = {
    //deserialization of nodeConfig from the outer join: just report the error + None
    def unserNodeConfig(opt1: Option[String], opt2: Option[String], opt3: Option[DateTime], opt4: Option[DateTime], opt5: Option[String]) = {
      (opt1, opt2, opt3, opt4, opt5) match {
        case (Some(id), Some(config), Some(begin), end, Some(json)) =>
          ExpectedReportsSerialisation.parseJsonNodeExpectedReports(json) match {
            case Full(x) =>
              Some(NodeExpectedReports(NodeId(id), NodeConfigId(config), begin, end, x.modes, x.ruleExpectedReports, x.overrides))
            case eb: EmptyBox =>
              val e = eb ?~! s"Error when deserialising node configuration for node with ID: ${id}, configId: ${config}"
              logger.error(e.messageChain)
              None
          }
        case _ => None
      }
    }

    val batchedNodeConfigIds = nodeIds.grouped(jdbcMaxBatchSize).toSeq
    sequence(batchedNodeConfigIds) { ids: Set[NodeId] =>
      // map node id to // ('node-id') // to use in values
      ids.map(id => s"('${id.value}')").toList match {
        case Nil => Full(Map[NodeId, Option[AgentRunWithNodeConfig]]())
        case nodes =>
          // we can't use "Fragments.in", because of: https://github.com/tpolecat/doobie/issues/426
          // so we use:
          //  SELECT * FROM table where nodeid in (VALUES (a), (b), ...here some thousands more...)

          val innerFromFrag = (
            fr"""from (
                 select reportsexecution.nodeid, reportsexecution.date, reportsexecution.nodeconfigid, reportsexecution.complete, reportsexecution.insertionid from
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

          //the whole query

          val query: ConnectionIO[Map[NodeId, Option[AgentRunWithNodeConfig]]] = for {
            // Here to make the query faster, we distinct only on the reportexecution to get the last run
            // but we need to get the matching last entry on nodeconfigurations.
            // I didn't find any better solution than doing a distinct on the table
            runs <- (fr""" select r.nodeid, r.date, r.nodeconfigid, r.complete, r.insertionid,
                         c.nodeid, c.nodeconfigid, c.begindate, c.enddate, c.configuration
                   """ ++
              innerFromFrag ++
              fr""" left outer join nodeconfigurations as c
                         on r.nodeId = c.nodeid and r.nodeconfigid = c.nodeconfigid
                     """).query[
              //
              // For some reason unknown of me, if we use Option[NodeId] for the parameter,
              // we are getting the assertion fail from NodeId: "An UUID can not have a null or empty value (value: null)"
              // But somehow, the null value is correctly transformed to None latter.
              // So we have to use string, and tranform it node in unserNodeConfig. Strange.
              //
              (DB.AgentRun, Option[String], Option[String], Option[DateTime], Option[DateTime], Option[String])
            ].map {
              case tuple@(r, t1, t2, t3, t4, t5) => (r, unserNodeConfig(t1, t2, t3, t4, t5))
            }.to[Vector]
          } yield {

            val runsMap = (runs.map { case (r, optConfig) =>
              val run = r.toAgentRun
              val config = run.nodeConfigVersion.map(c => (c, optConfig))
              (run.agentRunId.nodeId, AgentRunWithNodeConfig(run.agentRunId, config, run.isCompleted, run.insertionId))
            }).toMap
            ids.map(id => (id, runsMap.get(id))).toMap

          }

        transactRunBox(xa => query.transact(xa))
      }
    }
  }.map(_.flatten.toMap)
}

final case class WoReportsExecutionRepositoryImpl (
    db            : Doobie
  , readExecutions: RoReportsExecutionRepositoryImpl
) extends WoReportsExecutionRepository with Loggable {

  import db._

  def setComplianceComputationDate(runs: List[UncomputedAgentRun]): IOResult[Int] = {
    val updateKeys = runs.map(x => (x.nodeId, x.date, x.nodeConfigId))
    val sql = """UPDATE reportsexecution set compliancecomputatiodate = now() where nodeid = ? and date = ? and nodeconfigid = ?"""
    transactIOResult(s"Error when updating compliance computation date for runs")(xa => Update[(String, DateTime, String)](sql).updateMany(updateKeys).transact(xa)
    )
  }

  def updateExecutions(runs : Seq[AgentRun]) : Seq[Box[AgentRun]] =  {

    //
    // Question: do we want to save an updated nodeConfigurationVersion ?
    // for now, say we update all
    //

    /*
     * Three cases:
     * - already saved, completed: update them but keeping the "completed" state
     * - already saved, not completed: update them with whatever we found
     * - not already saved: insert them
     *
     * Note that each get-check-update_or_insert must be transactionnal, to
     * not allow insert failure. But we don't need (at all) a big transaction
     * wrapping ALL updates, quite the contrary.
     * So the logic is near from an upsert, but with some more logic in the
     * middle in case of update, to get the correct values for isCompleted/version
     */

    def updateOne(ar: AgentRun) = {
      val dbar = DB.AgentRun(
          ar.agentRunId.nodeId.value        // String
        , ar.agentRunId.date                // DateTume
        , ar.nodeConfigVersion.map(_.value) // Option[String]
        , ar.isCompleted                    // Boolean
        , ar.insertionId                    // Long
     )

      /*
       * We return an Either[Option[AgentRun]], if None => no upsert done (no modification)
       */
      val action: ConnectionIO[Option[DB.AgentRun]] = for {
        select <- sql"""select nodeid, date, nodeconfigid, complete, insertionid
                        from reportsexecution
                        where nodeid=${dbar.nodeId} and date=${dbar.date}
                     """.query[DB.AgentRun].option
        result <- select match {
                      case None =>
                          (sql"""insert into reportsexecution (nodeid, date, nodeconfigid, complete, insertionid)
                                 values (${dbar.nodeId}, ${dbar.date}, ${dbar.nodeConfigId}, ${dbar.isCompleted}, ${dbar.insertionId})"""
                                 .update.run.map(_ => dbar.some ) )
                      case Some(existing) => // if it's exactly the same element, don't update it
                       val reverted = existing.isCompleted && !dbar.isCompleted
                       if( reverted || existing == dbar ) { // does nothing if equals or isCompleted reverted to false
                         none[DB.AgentRun].pure[ConnectionIO]
                       } else {
                         val version = dbar.nodeConfigId.orElse(existing.nodeConfigId)
                         val completed = dbar.isCompleted || existing.isCompleted
                         sql"""update reportsexecution set nodeconfigid=${version}, complete=${completed}, insertionid=${dbar.insertionId}
                               where nodeid=${dbar.nodeId} and date=${dbar.date}""".update.run.map(_ => dbar.some)
                        }
                    }
      } yield {
        result
      }

      transactTask(xa => action.transact(xa).either)
    }


    ZioRuntime.unsafeRun(ZIO.collectAll(runs.toList.map(updateOne))).flatMap(x => x match {
      case Left(ex)        =>
        Some(Failure(s"Error when updating last agent runs information: ${ex.getMessage()}"))
      case Right(Some(res)) =>
        Some(Full(res.toAgentRun))
      case Right(None)      =>
        None
    })
  }

}

