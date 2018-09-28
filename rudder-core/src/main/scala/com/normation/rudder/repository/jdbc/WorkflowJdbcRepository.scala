/*
*************************************************************************************
* Copyright 2011-2013 Normation SAS
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
package com.normation.rudder.repository.jdbc

import net.liftweb.common.Loggable
import net.liftweb.common._
import com.normation.rudder.repository._
import com.normation.rudder.domain.workflows.WorkflowNodeId
import com.normation.rudder.domain.workflows.ChangeRequestId

import com.normation.rudder.db.Doobie
import com.normation.rudder.db.Doobie._
import doobie._, doobie.implicits._
import cats.implicits._


class RoWorkflowJdbcRepository(doobie: Doobie) extends RoWorkflowRepository with Loggable {

  import doobie._

  val SELECT_SQL = fr"SELECT id, state FROM Workflow "

  def getAllByState(state : WorkflowNodeId) :  Box[Seq[ChangeRequestId]] = {
    sql"""select id from workflow where state = ${state}""".query[ChangeRequestId].to[Vector].transact(xa).attempt.unsafeRunSync
  }

  def getStateOfChangeRequest(crId: ChangeRequestId) : Box[WorkflowNodeId] = {
    sql"""select state from workflow where id = ${crId}""".query[WorkflowNodeId].unique.transact(xa).attempt.unsafeRunSync
  }

  def getAllChangeRequestsState() : Box[Map[ChangeRequestId,WorkflowNodeId]] = {
    sql"select id, state from workflow".query[(ChangeRequestId, WorkflowNodeId)].to[Vector].transact(xa).attempt.unsafeRunSync.map( _.toMap )
  }
}

class WoWorkflowJdbcRepository(doobie: Doobie) extends WoWorkflowRepository with Loggable {

  import doobie._

  def createWorkflow(crId: ChangeRequestId, state : WorkflowNodeId) : Box[WorkflowNodeId] = {
    val process = {
      for {
        exists  <- sql"""select state from workflow where id = ${crId}""".query[WorkflowNodeId].option
        created <- exists match {
                     case None    => sql"""insert into workflow (id, state) values (${crId},${state})""".update.run.attempt
                     case Some(s) => (Left(s"Cannot start a workflow for Change Request id ${crId.value}, "+
                                     s"as it is already part of a workflow in state '${s}'")).pure[ConnectionIO]
                   }
      } yield {
        state
      }
    }
    process.transact(xa).attempt.unsafeRunSync
  }

  def updateState(crId: ChangeRequestId, from :  WorkflowNodeId, state : WorkflowNodeId) : Box[WorkflowNodeId] = {
    val process = {
      for {
        exists  <- sql"""select state from workflow where id = ${crId}""".query[WorkflowNodeId].option
        created <- exists match {
                     case Some(s) =>
                       if(s == from) {
                         sql"""update workflow set state = ${state} where id = ${crId}""".update.run.attempt
                       } else {
                         (Left(s"Cannot change status of ChangeRequest '${crId.value}': it has the status '${s.value}' "+
                              s"but we were expecting '${from.value}'. Perhaps someone else changed it concurently?").pure[ConnectionIO])
                       }
                     case None    => (Left(s"Cannot change a workflow for Change Request id ${crId.value}, "+
                                     s"as it is not part of any workflow yet").pure[ConnectionIO])
                   }
      } yield {
        state
      }
    }
    process.transact(xa).attempt.unsafeRunSync
  }
}
