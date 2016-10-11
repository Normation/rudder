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

import scalaz.{Failure => _, _}, Scalaz._
import doobie.imports._
import scalaz.concurrent.Task
import com.normation.rudder.domain.reports.NodeExpectedReports
import com.normation.rudder.domain.reports.NodeConfigId
import org.joda.time.DateTime
import com.normation.rudder.domain.reports.ExpectedReportsSerialisation

case class RoReportsExecutionRepositoryImpl (
    db: Doobie
  , pgInClause : PostgresqlInClause
) extends RoReportsExecutionRepository with Loggable {

  import Doobie._
  import db._

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
            case Full(x)      =>
              Some(NodeExpectedReports(NodeId(id), NodeConfigId(config), begin, end, x.modes, x.ruleExpectedReports))
            case eb: EmptyBox =>
              val e = eb ?~! s"Error when deserialising node configuration for node with ID: ${id}, configId: ${config}"
              logger.error(e.messageChain)
              None
          }
        case _ => None
      }
    }

    nodeIds.map( _.value).toList.toNel match {
      case None => Full(Map())
      case Some(nodes) =>

        //val nodes = pgInClause.in("nodeid", nodeIds.map(_.value))
        implicit val nodesParam = Param.many(nodes)

        // notice that we can't use pgInClause because we don't have any way
        // to interpolate the actual value of in - sql""" """ is more
        // than just interpolation. Need to check perfomances.
        (for {
          runs <- sql"""select distinct on (r.nodeid)
                         r.nodeid, r.date, r.nodeconfigid, r.complete, r.insertionid
                       , c.nodeid, c.nodeconfigid, c.begindate, c.enddate, c.configuration
                       from  reportsexecution r
                       left outer join nodeconfigurations c
                         on r.nodeId = c.nodeid and r.nodeconfigid = c.nodeconfigid
                       where r.complete = true and r.nodeid in (${nodes : nodes.type})
                       order by r.nodeid, r.insertionid desc
                     """.query[
                          //
                          // For some reason unknown of me, if we use Option[NodeId] for the parameter,
                          // we are getting the assertion fail from NodeId: "An UUID can not have a null or empty value (value: null)"
                          // But somehow, the null value is correctly transformed to None latter.
                          // So we have to use string, and tranform it node in unserNodeConfig. Strange.
                          //
                          (DB.AgentRun, Option[String], Option[String], Option[DateTime], Option[DateTime], Option[String])
                        ].map {
                          case tuple@(r, t1, t2, t3, t4, t5) => (r, unserNodeConfig(t1, t2, t3, t4, t5))
                        }.vector
        } yield {
          val runsMap = (runs.map { case (r, optConfig) =>
            val run = r.toAgentRun
            val config = run.nodeConfigVersion.map(c => (c, optConfig))
            (run.agentRunId.nodeId, AgentRunWithNodeConfig(run.agentRunId, config, run.isCompleted, run.insertionId))
          }).toMap
          nodeIds.map(id => (id, runsMap.get(id))).toMap
        }).attempt.transact(xa).run
    }
  }
}

case class WoReportsExecutionRepositoryImpl (
    db            : Doobie
  , readExecutions: RoReportsExecutionRepositoryImpl
) extends WoReportsExecutionRepository with Loggable {

  import db._

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
       * We return an \/[Option[AgentRun]], if None => no upsert done (no modification)
       */
      val action = for {
        select <- sql"""select nodeid, date, nodeconfigid, complete, insertionid
                        from reportsexecution
                        where nodeid=${dbar.nodeId} and date=${dbar.date}
                     """.query[DB.AgentRun].option
        result <- select match {
                      case None =>
                          (sql"""insert into reportsexecution (nodeid, date, nodeconfigid, complete, insertionid)
                                 values (${dbar.nodeId}, ${dbar.date}, ${dbar.nodeConfigId}, ${dbar.isCompleted}, ${dbar.insertionId})"""
                                 .update.run.map(_ => some(dbar)) )
                      case Some(existing) => // if it's exactly the same element, don't update it
                       val reverted = existing.isCompleted && !dbar.isCompleted
                       if( reverted || existing == dbar ) { // does nothing if equals or isCompleted reverted to false
                         none[DB.AgentRun].point[ConnectionIO]
                       } else {
                         val version = dbar.nodeConfigId.orElse(existing.nodeConfigId)
                         val completed = dbar.isCompleted || existing.isCompleted
                         sql"""update reportsexecution set nodeconfigid=${version}, complete=${completed}, insertionid=${dbar.insertionId}
                               where nodeid=${dbar.nodeId} and date=${dbar.date}""".update.run.map(_ => some(dbar))
                        }
                    }
      } yield {
        result
      }

      action.attempt.transact(xa)
    }


    runs.toList.map(updateOne).sequence.run.flatMap(x => x match {
      case -\/(ex)        =>
        Some(Failure(s"Error when updating last agent runs information: ${ex.getMessage()}"))
      case \/-(Some(res)) =>
        Some(Full(res.toAgentRun))
      case \/-(None)      =>
        None
    })
  }

}

