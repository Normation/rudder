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
import scalaz.{Failure => _, _}, Scalaz._
import doobie.imports._
import scalaz.concurrent.Task

case class RoReportsExecutionRepositoryImpl (
    db: Doobie
  , pgInClause : PostgresqlInClause
) extends RoReportsExecutionRepository with Loggable {

  import db._

  override def getNodesLastRun(nodeIds: Set[NodeId]): Box[Map[NodeId, Option[AgentRun]]] = {
    nodeIds.map( _.value).toList.toNel match {
      case None => Full(Map())
      case Some(nodes) =>

        //val nodes = pgInClause.in("nodeid", nodeIds.map(_.value))
        implicit val nodesParam = Param.many(nodes)

        //notice the use of # ({} is forbidden) in place of ${} to interpolate the actual value of in,
        //not make a sql parameter to the query
        val sql = sql"""select distinct on (nodeid)
                            nodeid, date, nodeconfigid, complete, insertionid
                        from  reportsexecution
                        where complete = true and nodeid in (${nodes : nodes.type})
                        order by nodeid, insertionid desc""".query[DB.AgentRun].list

        val errorMSg = s"Error when trying to get report executions for nodes with Id '${nodeIds.map( _.value).mkString(",")}'"

        sql.attempt.transact(xa).run match {
          case \/-(entries)  =>
            val runs = entries.map(x => (NodeId(x.nodeId), x.toAgentRun)).toMap
            Full(nodeIds.map(n => (n, runs.get(n))).toMap)
          case -\/(ex) => Failure(errorMSg + ": " + ex.getMessage, Full(ex), Empty)
        }
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
        println("failure: " + ex)
        Some(Failure(s"Error when updatating last agent runs information: ${ex.getMessage()}"))
      case \/-(Some(res)) =>
        Some(Full(res.toAgentRun))
      case \/-(None)      =>
        println("not updating one")
        None
    })
  }

}

