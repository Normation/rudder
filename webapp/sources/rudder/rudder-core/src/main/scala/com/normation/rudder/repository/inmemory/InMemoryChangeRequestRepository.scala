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

package com.normation.rudder.repository.inmemory

import scala.collection.mutable.{ Map => MutMap }
import com.normation.eventlog.EventActor
import com.normation.rudder.domain.workflows.{ ChangeRequest, ChangeRequestId }
import com.normation.rudder.repository.{ RoChangeRequestRepository, WoChangeRequestRepository }
import net.liftweb.common._
import com.normation.eventlog.EventActor
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.policies.RuleId

class InMemoryChangeRequestRepository
 extends RoChangeRequestRepository with WoChangeRequestRepository with Loggable {

  imMemoryRepo =>

  private[this] val repo = MutMap[ChangeRequestId, ChangeRequest]()

  private[this] var id = 0

  private[this] def getNextId : Int = {
    imMemoryRepo.synchronized {
      id = id + 1
      id
    }
  }

  /** As seen from class InMemoryChangeRequestRepository, the missing signatures are as follows.
   *  For convenience, these are usable as stub implementations.
   */
  // Members declared in com.normation.rudder.repository.RoChangeRequestRepository
  def get(changeRequestId: ChangeRequestId): Box[Option[ChangeRequest]] = {
    Full(repo.get(changeRequestId))
  }
  def getAll(): Box[Vector[ChangeRequest]] = {
    Full(repo.values.toVector)
  }

  def createChangeRequest(changeRequest: ChangeRequest,actor: EventActor,reason: Option[String]): Box[ChangeRequest] = {
    val crId = ChangeRequestId(getNextId)
    repo.get(crId) match {
      case Some(x) => Failure(s"Change request with ID ${crId} is already created")
      case None =>
        val serializedCr = ChangeRequest.updateId(changeRequest, crId)
        repo += (crId -> serializedCr)
        Full(serializedCr)
      }

  }

  def deleteChangeRequest(changeRequestId: ChangeRequestId,actor: EventActor,reason: Option[String]): Box[ChangeRequest] = {
      repo.get(changeRequestId) match {
        case None => Failure(s"Could not delete non existing CR with id ${changeRequestId.value}")
        case Some(request) =>
          repo -= changeRequestId
          Full(request)
      }
  }

  def updateChangeRequest(changeRequest: ChangeRequest,actor: EventActor,reason: Option[String]): Box[ChangeRequest] = {
      repo.get(changeRequest.id) match {
        case None => Failure(s"Change request with ID ${changeRequest.id} does not exist")
        case Some(_) =>
          repo += (changeRequest.id-> changeRequest)
          Full(changeRequest)
      }

  }

  def getByContributor(actor:EventActor) = Full(Vector())

  def getByIds(changeRequestId:Seq[ChangeRequestId]) : Box[Seq[ChangeRequest]] =  Full(Seq())

  def getByDirective(id : DirectiveId, onlyPending:Boolean) : Box[Vector[ChangeRequest]] = Full(Vector())

  def getByNodeGroup(id : NodeGroupId, onlyPending:Boolean) : Box[Vector[ChangeRequest]] = Full(Vector())

  def getByRule(id : RuleId, onlyPending:Boolean) : Box[Vector[ChangeRequest]] = Full(Vector())

}
