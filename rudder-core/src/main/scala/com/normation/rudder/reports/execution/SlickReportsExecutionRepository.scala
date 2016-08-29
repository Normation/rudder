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

import java.sql.Timestamp
import org.joda.time.DateTime
import org.squeryl.KeyedEntity
import org.squeryl.PrimitiveTypeMode._
import org.squeryl.Schema
import org.squeryl.annotations.Column
import org.squeryl.dsl.CompositeKey2
import com.normation.inventory.domain.NodeId
import com.normation.rudder.repository.jdbc.SquerylConnectionProvider
import net.liftweb.common._
import ExecutionRepositoryUtils._
import com.normation.rudder.domain.reports.NodeConfigId
import java.sql.BatchUpdateException
import java.sql.SQLException
import org.springframework.jdbc.core.JdbcTemplate
import com.normation.rudder.repository.jdbc.PostgresqlInClause
import net.liftweb.util.Helpers.tryo
import org.springframework.jdbc.core.RowMapper
import java.sql.ResultSet
import com.normation.rudder.domain.reports.NodeConfigId
import scala.collection.JavaConverters.asScalaBufferConverter
import scala.language.implicitConversions


case class RoReportsExecutionJdbcRepository (
    jdbcTemplate: JdbcTemplate
  , pgInClause  : PostgresqlInClause
) extends RoReportsExecutionRepository with Loggable {

  object AgentRunMapper extends RowMapper[(NodeId, AgentRun)] {
    def mapRow(rs : ResultSet, rowNum: Int) : (NodeId, AgentRun) = {

      val nodeId = NodeId(rs.getString("nodeid"))
      val id = AgentRunId(nodeId, new DateTime(rs.getTimestamp("date").getTime))
      val configId = Option(rs.getString("nodeconfigid")).map(NodeConfigId(_))
      val insertionId = rs.getLong("insertionid")
      (nodeId, AgentRun(id, configId, rs.getBoolean("complete"), insertionId))
    }
  }

  override def getNodesLastRun(nodeIds: Set[NodeId]): Box[Map[NodeId, Option[AgentRun]]] = {
    if(nodeIds.isEmpty) Full(Map())
    else {
      import pgInClause.in
      val query =
        s"""SELECT DISTINCT ON (nodeid)
           |  nodeid, date, nodeconfigid, complete, insertionid
           |FROM  reportsexecution
           |WHERE complete = true and ${in("nodeid", nodeIds.map(_.value))}
           |ORDER BY nodeid, insertionId DESC""".stripMargin

      val errorMSg = s"Error when trying to get report executions for nodes with Id '${nodeIds.map( _.value).mkString(",")}'"

      for {
        entries <- tryo ( jdbcTemplate.query(query, AgentRunMapper).asScala ) ?~! errorMSg
      } yield {
        val res = entries.toMap
        nodeIds.map(n => (n, res.get(n))).toMap
      }
    }
  }
}

case class WoReportsExecutionSquerylRepository (
    sessionProvider : SquerylConnectionProvider
  , readExecutions  : RoReportsExecutionJdbcRepository
) extends WoReportsExecutionRepository with Loggable {

  def updateExecutions(incomingExec : Seq[AgentRun]) : Box[Seq[AgentRun]] =  {
    //"contains" is only defined regarding the primary key
    def same(x:AgentRun, y:AgentRun) = {
      x.agentRunId == y.agentRunId
    }

    def find(r:AgentRun, seq: Seq[AgentRun]) = {
      seq.find(x => same(x,r))
    }

    //
    // Question: do we want to save an updated nodeConfigurationVersion ?
    // for now, say we update all
    //

    /*
     * Three cases:
     * - already saved, completed: update them but keeping the "completed" state
     * - already saved, not completed: update them with whatever we found
     * - not already saved: insert them
     */
    try { sessionProvider.ourTransaction {
      val existingExec = incomingExec.flatMap { execution =>
        from(Executions.executions)(entry =>
          where(
                entry.nodeId === execution.agentRunId.nodeId.value
            and entry.date   === toTimeStamp(execution.agentRunId.date)
          )
          select(entry)
        )
      }.toSeq.map(fromDB)

      val toInsert = incomingExec.filter( x => find(x, existingExec).isEmpty )

      val toUpdate =  incomingExec.flatMap( incoming =>
                        find(incoming, existingExec)
                        //for the one toUpdate, always keep the most recent
                        // nodeConfigurationVersion (if not empty) and the most completed status
                        .flatMap { existing =>
                          val completed = incoming.isCompleted || existing.isCompleted
                          val version = incoming.nodeConfigVersion.orElse(existing.nodeConfigVersion)
                          val toSave = incoming.copy( isCompleted = completed, nodeConfigVersion = version)
                          if(toSave == existing) {
                            None
                          } else {
                            Some(toSave)
                          }
                        }
                      )

      val updated = Executions.executions.update(toUpdate.map( toDB(_) ))
      val inserted = Executions.executions.insert(toInsert.map( toDB(_) ))

      Full(toInsert ++ toUpdate)

    } } catch {
      case e:SQLException  =>
        val msg = s"Error when trying to update nodes report executions, reason is ${e.getMessage()}"
        logger.error(msg, e)
        logger.error(e.getNextException)
        Failure(msg)
    }
  }

}

object ExecutionRepositoryUtils {
  implicit def toTimeStamp(d:DateTime) : Timestamp = {
    new Timestamp(d.getMillis)
  }

  implicit def toDB (execution : AgentRun)  : DBAgentRun = {
    DBAgentRun(execution.agentRunId.nodeId.value, execution.agentRunId.date, execution.nodeConfigVersion.map(_.value), execution.isCompleted, execution.insertionId)
  }

  implicit def fromDB (execution : DBAgentRun)  : AgentRun = {
    AgentRun(AgentRunId(NodeId(execution.nodeId), new DateTime(execution.date)), execution.nodeConfigId.map(NodeConfigId(_)), execution.isCompleted, execution.insertionId)
  }
}


object Executions extends Schema {
  val executions = table[DBAgentRun]("reportsexecution")
}

case class DBAgentRun (
    @Column("nodeid")   nodeId        : String
  , @Column("date")     date          : Timestamp
  , @Column("nodeconfigid") nodeConfigId : Option[String]
  , @Column("complete") isCompleted   : Boolean
  , @Column("insertionid") insertionId: Long
) extends KeyedEntity[CompositeKey2[String,Timestamp]] {

  def id = compositeKey(nodeId,date)
}
