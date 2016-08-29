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

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.Try
import scala.util.{ Failure => TFailure }
import scala.util.{ Success => TSuccess }

import com.normation.inventory.domain.NodeId
import com.normation.rudder.db.DB
import com.normation.rudder.db.SlickSchema
import com.normation.rudder.repository.jdbc.PostgresqlInClause

import net.liftweb.common._
import slick.jdbc.GetResult


case class RoReportsExecutionJdbcRepository (
    slickSchema: SlickSchema
  , pgInClause : PostgresqlInClause
) extends RoReportsExecutionRepository with Loggable {

  import slickSchema.plainApi._

  override def getNodesLastRun(nodeIds: Set[NodeId]): Future[Box[Map[NodeId, Option[AgentRun]]]] = {
    if(nodeIds.isEmpty) Future.successful(Full(Map()))
    else {
      val nodes = pgInClause.in("nodeid", nodeIds.map(_.value))
      //needed to transform the result into AgentRuns. PlainSQL returns tuples otherwise.
      implicit val getAgentRunResult = GetResult(r => DB.AgentRun(r.<<, r.nextZonedDateTime(), r.<<, r.<<, r.<<))

      //notice the use of # ({} is forbidden) in place of ${} to interpolate the actual value of in,
      //not make a sql parameter to the query
      val query = sql"""SELECT DISTINCT ON (nodeid)
                          nodeid, date, nodeconfigid, complete, insertionid
                        FROM  reportsexecution
                        WHERE complete = true and #${nodes}
                        ORDER BY nodeid, insertionId DESC""".as[DB.AgentRun]

      val errorMSg = s"Error when trying to get report executions for nodes with Id '${nodeIds.map( _.value).mkString(",")}'"

      slickSchema.db.run(query.asTry).map {
        case TSuccess(entries)  =>
          val runs = entries.map(x => (NodeId(x.nodeId), x.asAgentRun)).toMap
          Full(nodeIds.map(n => (n, runs.get(n))).toMap)
        case TFailure(ex) => Failure(errorMSg + ": " + ex.getMessage, Full(ex), Empty)
      }
    }
  }
}

case class WoReportsExecutionSquerylRepository (
    slickSchema   : SlickSchema
  , readExecutions: RoReportsExecutionJdbcRepository
) extends WoReportsExecutionRepository with Loggable {

  import slickSchema.api._

  def updateExecutions(runs : Seq[AgentRun]) : Future[Seq[Box[AgentRun]]] =  {

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

      val select = (slickSchema.agentRun.filter(x => x.nodeId === dbar.nodeId && x.date === dbar.date ))
      val update = select.map(x => (x.nodeConfigId, x.isCompleted, x.insertionId))
      val insert = (slickSchema.agentRun += dbar)

      //no update of nodeId/date

      // need ? : http://stackoverflow.com/questions/14621172/how-do-you-change-lifted-types-back-to-scala-types-when-using-slick-lifted-embed

      //the whole logic put together
      /*
       * We return an Try[Option[AgentRun]], if None => no upsert done (no modification)
       */
      val action = (for {
        existing <- select.result
        result   <- existing.headOption match {
                      case None           => insert.map( _ => Some(dbar) ).asTry
                      case Some(existing) => // if it's exactly the same element, don't update it
                       val reverted = existing.isCompleted && !dbar.isCompleted
                       if( reverted || existing == dbar ) { // does nothing if equals or isCompleted reverted to false
                          DBIO.successful(TSuccess(None))
                       } else {
                         val version = dbar.nodeConfigId.orElse(existing.nodeConfigId)
                         val completed = dbar.isCompleted || existing.isCompleted
                          update.update((version, completed, dbar.insertionId)).asTry.map {
                            case TSuccess(x)  => TSuccess(Some(dbar))
                            case TFailure(ex) => TFailure(ex)
                          }
                        }
                    }
      } yield {
        result
      }).transactionally

      action
    }

    // run the sequences of upsert, filter out "Full(None)" meaning nothing was done
    slickSchema.db.run(DBIO.sequence(runs.map(updateOne))).map { seq => seq.flatMap {
      case TSuccess(Some(x)) => Some(Full(x.asAgentRun))
      case TSuccess(None)    => None
      case TFailure(ex)      => Some(Failure(s"Error when updatating last agent runs information: ${ex.getMessage()}"))
    } }
  }

}

