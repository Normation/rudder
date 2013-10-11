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

import org.joda.time.DateTime
import com.normation.inventory.domain.NodeId
import org.squeryl.PrimitiveTypeMode._
import org.squeryl._
import org.squeryl.annotations.Column
import java.sql.Timestamp
import net.liftweb.util.Helpers.tryo
import net.liftweb.common._
import com.normation.rudder.repository.jdbc.SquerylConnectionProvider
import org.squeryl.dsl.CompositeKey2
import java.sql.SQLException
import com.normation.utils.Control._

case class RoReportsExecutionSquerylRepository (
    sessionProvider : SquerylConnectionProvider
) extends RoReportsExecutionRepository with Loggable {

  import ExecutionRepositoryUtils._

  def getExecutionByNode (nodeId : NodeId) : Box[Seq[ReportExecution]] = {
    try {  sessionProvider.ourTransaction {
      val queryResult = from(Executions.executions)(entry =>
        where(
          entry.nodeId === nodeId.value
        )
        select(entry)
      ).toSeq.map(fromDB)
      Full(Seq[ReportExecution]() ++ queryResult)
    } } catch {
      case e:Exception  =>
        logger.error(s"Error when trying to get report executions for node '${nodeId.value}'")
        Failure(s"Error when trying to get report executions for node '${nodeId.value}' cause is : $e")
    }
  }

  def getNodeLastExecution (nodeId : NodeId) : Box[Option[ReportExecution]] = {
    try {  sessionProvider.ourTransaction {
      val queryResult = from(Executions.executions)(entry =>
        where(
          entry.nodeId === nodeId.value
        )
        select(entry)
        orderBy(entry.date desc)
      ).page(0,1).headOption.map(fromDB)
      Full( queryResult)
    } } catch {
      case e:Exception  =>
        logger.error(s"Error when trying to get report executions for node '${nodeId.value}'")
        Failure(s"Error when trying to get report executions for node '${nodeId.value}' cause is : $e")
    }
  }
  def getExecutionByNodeandDate (nodeId : NodeId, date: DateTime) : Box[Option[ReportExecution]] = {
    try {  sessionProvider.ourTransaction {
      val queryResult : Option[ReportExecution] = from(Executions.executions)(entry =>
        where(
              entry.nodeId === nodeId.value
          and entry.date   === toTimeStamp(date)
        )
        select(entry)
      ).headOption.map(fromDB)
      Full(queryResult)
    } } catch {
      case e:Exception  =>
        logger.error(s"Error when trying to get report executions for node '${nodeId.value}'")
        Failure(s"Error when trying to get report executions for node '${nodeId.value}' cause is : $e")
    }
  }

  /**
   * From a seq of found executions in RudderSysEvents, find in the existing executions matching
   */
  def getExecutionsByNodeAndDate (executions: Seq[ReportExecution]) : Box[Seq[ReportExecution]] = {
    try {
      sessionProvider.ourTransaction {
        val result = executions.flatMap { execution =>
          from(Executions.executions)(entry =>
            where(
                  entry.nodeId === execution.nodeId.value
              and entry.date   === toTimeStamp(execution.date)
            )
            select(entry)
          )
        }

        Full(Seq() ++ result.toSeq.map(fromDB))
    } } catch {
      case e:Exception  =>
        logger.error(s"Error when trying to get report executions")
        Failure(s"Error when trying to get report executions, cause is : $e")
    }
  }

}

case class WoReportsExecutionSquerylRepository (
    sessionProvider : SquerylConnectionProvider
  , readExecutions  : RoReportsExecutionRepository
) extends WoReportsExecutionRepository with Loggable {


  import readExecutions._
  import ExecutionRepositoryUtils._

  def saveOrUpdateExecution (execution : ReportExecution) : Box[ReportExecution] = {
    getExecutionByNodeandDate(execution.nodeId, execution.date) match {
      case Full(Some(_)) =>
        if (execution.isComplete) {
          logger.trace(s"need to update execution ${execution}")
          closeExecution(execution)
        } else {
          logger.trace(s"execution ${execution} not ended yet")
          Full(execution)
        }
      case Full(None) =>
        saveExecution(execution)
      case eb: EmptyBox =>
        eb ?~! "Could not get previous node execution"
    }
  }

  private[this] def saveExecution (execution: ReportExecution) : Box[ReportExecution] = {
    try {
      val saveResult = sessionProvider.ourTransaction {
        Executions.executions.insert(execution)
      }
      Full(fromDB(saveResult))
    } catch {
      case e:Exception =>
        val msg = s"Could not save the node execution ${execution}, reason is ${e.getMessage()}"
        logger.error(msg)
        Failure(msg)
    }
  }

  private[this] def saveExecutions (executions : Seq[ReportExecution]) : Box[Seq[ReportExecution]] =  {
    try {
      val saveResult = sessionProvider.ourTransaction {
        executions.map( execution =>
          Executions.executions.insert( execution )
        )
      }
      logger.trace(s"Created ${saveResult.size} executions, should have saved ${executions.size}")
      Full(executions)
    } catch {
      case e:Exception =>
        val msg = s"Could not save the ${executions.size} nodes executions, reason is ${e.getMessage()}"
        logger.error(msg)
        Failure(msg)
    }

  }

  def updateExecutions(executions : Seq[ReportExecution]) : Box[Seq[ReportExecution]] =  {
    for {
      existingExec            <- getExecutionsByNodeAndDate(executions) ?~! "Could not fetch the already save executions date for node"
      completeExec            = executions.filter(_.isComplete)

      existingNotCompleteExec = existingExec.filter(!_.isComplete)

      // closed execution are those complete, into the existing execution incomplete
      closedExec              = completeExec.map  { x =>
                                      ReportExecutionWithoutState(x.nodeId, x.date)
                                    }.filter { x =>
                                      existingNotCompleteExec.map(ex =>
                                         ReportExecutionWithoutState(ex.nodeId, ex.date)).
                                           contains(x)
                                    }.map(x => ReportExecution(x.nodeId, x.date, true))

      // a new closed execution is an execution closed, but not in the list of the executions
      // closed
      newlyClosedExec         = completeExec.filter(x => !closedExec.contains(x))
      // new execution are those not complete (otherwise in the previous case),
      // which are not in the existing, independantly of the state
      newExec                 = executions.filter(!_.isComplete).map  { x =>
                                  ReportExecutionWithoutState(x.nodeId, x.date)
                               }.filterNot { x =>
                                  existingExec.map(ex =>
                                    ReportExecutionWithoutState(ex.nodeId, ex.date)).
                                      contains(x)
                               }.map(x => ReportExecution(x.nodeId, x.date, false))
      closed                  <- closeExecutions(closedExec) ?~! s"Could not close the ${closedExec.size} execution that has been completed during the interval"
      newlyClosed             <- saveExecutions(newlyClosedExec) ?~! s"Could not create the ${newlyClosedExec.size} execution that has been completed during the interval"
      newExecutions           <-  saveExecutions(newExec) ?~! s"Could not create the ${newExec.size} execution that has been started during the interval"


    } yield {
      closed ++ newlyClosed ++ newExecutions
    }
  }

  def closeExecution (execution : ReportExecution) : Box[ReportExecution] =  {
    try {
      val closeResult = sessionProvider.ourTransaction {
          Executions.executions.update( exec =>
            where (
                  exec.nodeId === execution.nodeId.value
              and exec.date   === toTimeStamp(execution.date)
            )

            set   ( exec.isComplete := true)
        )
      }
      logger.debug(s" closed 1, should have close")
      Full(execution)
    } catch {
      case e:Exception => Failure("could not create aggregated reports")
    }
  }


  def closeExecutions (executions : Seq[ReportExecution]) : Box[Seq[ReportExecution]] =  {
    try {
      val closeResult = sessionProvider.ourTransaction {
        executions.map( execution =>
          Executions.executions.update( exec =>
            where (
                  exec.nodeId === execution.nodeId.value
              and exec.date   === toTimeStamp(execution.date)
            )

            set   ( exec.isComplete := true)
        ) )
      }
      logger.debug(s" closed ${closeResult.size}, should have close ${executions.size}")
      Full(executions)
    } catch {
      case e:Exception => Failure("could not create aggregated reports")
    }
  }
}

object ExecutionRepositoryUtils {
  implicit def toTimeStamp(d:DateTime) : Timestamp = {
    new Timestamp(d.getMillis)
  }

  implicit def toDB (execution : ReportExecution)  : DBReportExecution = {
    DBReportExecution(execution.nodeId.value, execution.date, execution.isComplete)
  }

  implicit def fromDB (execution : DBReportExecution)  : ReportExecution = {
    ReportExecution(NodeId(execution.nodeId), new DateTime(execution.date), execution.isComplete)
  }
}


object Executions extends Schema {
  val executions = table[DBReportExecution]("reportsexecution")
}

case class DBReportExecution (
    @Column("nodeid")   nodeId     : String
  , @Column("date")     date       : Timestamp
  , @Column("complete") isComplete : Boolean
) extends KeyedEntity[CompositeKey2[String,Timestamp]] {

  def id = compositeKey(nodeId,date)
}
