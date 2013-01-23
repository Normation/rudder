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

case class ReportsExecutionSquerylRepository (
    sessionProvider : SquerylConnectionProvider
) extends ReportsExecutionRepository with Loggable {

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

  def saveOrUpdateExecution (execution : ReportExecution) : Box[ReportExecution] = {
    getExecutionByNodeandDate(execution.nodeId, execution.date) match {
      case Full(Some(_)) =>
        if (execution.isComplete) {
          logger.info("need to update")
          closeExecution(execution)
        } else {
          logger.info("not ended yet")
          Full(execution)
        }
      case Full(None) =>
        saveExecution(execution)
      case eb: EmptyBox =>
        eb ?~! "could not get previous execution"
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
        Failure(s"could not save execution reports cause is : ${e.getMessage}")
    }
  }
  def saveExecutions (executions : Seq[ReportExecution]) : Box[Seq[ReportExecution]] =  {
    boxSequence(executions.map(saveOrUpdateExecution))
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

  private[this] implicit def toTimeStamp(d:DateTime) : Timestamp = {
    new Timestamp(d.getMillis)
  }

  private[this] implicit def toDB (execution : ReportExecution)  : DBReportExecution = {
    DBReportExecution(execution.nodeId.value, execution.date, execution.isComplete)
  }

  private[this] implicit def fromDB (execution : DBReportExecution)  : ReportExecution = {
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
