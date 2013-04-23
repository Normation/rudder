/*
*************************************************************************************
* Copyright 2011-2013 Normation SAS
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
package com.normation.rudder.repository.jdbc

import net.liftweb.common.Loggable
import net.liftweb.common._
import scala.util.{Try, Failure => Catch, Success}
import org.springframework.jdbc.core.JdbcTemplate
import com.normation.rudder.domain.workflows.ChangeRequest
import com.normation.rudder.repository._
import com.normation.rudder.domain.workflows.WorkflowNodeId
import com.normation.rudder.domain.workflows.WorkflowNode
import scala.collection.JavaConversions._
import com.normation.rudder.domain.workflows.ChangeRequestId
import org.springframework.jdbc.core.RowMapper
import java.sql.ResultSet
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.jdbc.core.PreparedStatementCreator

class RoWorkflowJdbcRepository(
    jdbcTemplate : JdbcTemplate
)extends RoWorkflowRepository with Loggable {
  val SELECT_SQL = "SELECT id, state FROM Workflow "

  def getAllByState(state : WorkflowNodeId) :  Box[Seq[ChangeRequestId]] = {
    Try {
      val list = jdbcTemplate.query(SELECT_SQL + "WHERE state = ?", Array[AnyRef](state.value.asInstanceOf[AnyRef]), DummyWorkflowsMapper)
      list.toSeq.map(x => ChangeRequestId(x.crId))
    } match {
      case Success(x) => Full(x)
      case Catch(error) =>
        logger.error(s"Error when fetching all change request by state ${state.value} : ${error.toString()}")
        Failure(error.toString())
    }
  }

  def getStateOfChangeRequest(crId: ChangeRequestId) : Box[WorkflowNodeId] = {
     Try {
      val list = jdbcTemplate.query(SELECT_SQL + "WHERE id = ?", Array[AnyRef](crId.value.asInstanceOf[AnyRef]), DummyWorkflowsMapper)
      list.toSeq match {
        case seq if seq.size == 0 =>
          logger.warn(s"Change request ${crId.value} doesn't exist")
          Failure(s"Change request ${crId.value} doesn't exist")
        case seq if seq.size > 1 =>
          logger.error(s"Too many change request with same id ${crId.value}")
          Failure(s"Too many change request with same id ${crId.value}")
        case seq if seq.size == 1 => Full(WorkflowNodeId(seq.head.state))
      }
    } match {
      case Success(x) => x
      case Catch(error) =>
        logger.error(s"Error when fetching status of change request ${crId.value} : ${error.toString()}")
        Failure(error.toString())
    }
  }

  def isChangeRequestInWorkflow(crId: ChangeRequestId) : Box[Boolean] = {
     Try {
      val list = jdbcTemplate.query(SELECT_SQL + "WHERE id = ?", Array[AnyRef](crId.value.asInstanceOf[AnyRef]), DummyWorkflowsMapper)
      list.toSeq match {
        case seq if seq.size == 0 =>
          Full(true)
        case seq if seq.size > 1 =>
          logger.error(s"Too many change request with same id ${crId.value}")
          Failure(s"Too many change request with same id ${crId.value}")
        case seq if seq.size == 1 => Full(false)
      }
    } match {
      case Success(x) => x
      case Catch(error) =>
        logger.error(s"Error when fetching existance of change request ${crId.value} : ${error.toString()}")
        Failure(error.toString())
    }
  }
}

class WoWorkflowJdbcRepository(
    jdbcTemplate : JdbcTemplate
  , roRepo       : RoWorkflowRepository
)extends WoWorkflowRepository with Loggable {
  val UPDATE_SQL = "UPDATE Workflow set state = ? where id = ?"

  val INSERT_SQL = "INSERT into Workflow (id, state) values (?, ?)"

  def createWorkflow(crId: ChangeRequestId, state : WorkflowNodeId) : Box[WorkflowNodeId] = {
    Try {
      roRepo.isChangeRequestInWorkflow(crId) match {
        case eb : EmptyBox => eb
        case Full(true) =>
          jdbcTemplate.update(
              INSERT_SQL
            , new java.lang.Integer(crId.value)
            , state.value
          )
          roRepo.getStateOfChangeRequest(crId)
        case _ =>
          logger.error(s"Cannot start a workflow for Change Request id ${crId.value}, as it is already part of a workflow")
          Failure(s"Cannot start a workflow for Change Request id ${crId.value}, as it is already part of a workflow")

      }
    } match {
      case Success(x) => x
      case Catch(error) =>
        logger.error(s"Error when updating status of change request ${crId.value}: ${error.toString}")
        Failure(error.toString())
    }
  }

  def updateState(crId: ChangeRequestId, from :  WorkflowNodeId, state : WorkflowNodeId) : Box[WorkflowNodeId] = {
    Try {
      roRepo.getStateOfChangeRequest(crId) match {
        case eb : EmptyBox => eb
        case Full(entry) =>
          if (entry != from) {
            Failure(s"Cannot change status of ChangeRequest id ${crId.value} : it has the status ${entry.value} but we were expecting ${from.value}")
          } else {
            jdbcTemplate.update(
                  UPDATE_SQL
                , state.value
                , new java.lang.Integer(crId.value)
              )
          }
          roRepo.getStateOfChangeRequest(crId)
      }
    } match {
      case Success(x) => x
      case Catch(error) =>
        logger.error(s"Error when updating status of change request ${crId.value} : ${error.toString()}")
        Failure(error.toString())
    }
  }

}

/**
 * A dummy object easing the transition between database and code
 */
private[jdbc] case class DummyMapper(
  state : String
, crId  : Int
)

object DummyWorkflowsMapper extends RowMapper[DummyMapper] with Loggable {
  def mapRow(rs : ResultSet, rowNum: Int) : DummyMapper = {
    // dummy code
    DummyMapper(
        rs.getString("state")
      , rs.getInt("id")
    )
  }

}