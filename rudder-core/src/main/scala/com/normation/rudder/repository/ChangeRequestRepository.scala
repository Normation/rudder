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

package com.normation.rudder.repository

import com.normation.rudder.domain.workflows.ChangeRequest
import net.liftweb.common.Box
import com.normation.rudder.domain.workflows.ChangeRequestId
import com.normation.rudder.domain.workflows.ChangeRequestId
import com.normation.eventlog.EventActor
import com.normation.eventlog.EventActor
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.policies.RuleId
import com.normation.eventlog.EventActor

/**
 * Read access to change request
 */
trait RoChangeRequestRepository {


  def getAll() : Box[Vector[ChangeRequest]]

  def get(changeRequestId:ChangeRequestId) : Box[Option[ChangeRequest]]

  def getByDirective(id : DirectiveId, onlyPending:Boolean) : Box[Vector[ChangeRequest]]

  def getByNodeGroup(id : NodeGroupId, onlyPending:Boolean) : Box[Vector[ChangeRequest]]

  def getByRule(id : RuleId, onlyPending:Boolean) : Box[Vector[ChangeRequest]]

  def getByContributor(actor:EventActor) : Box[Vector[ChangeRequest]]
}


/**
 * A proxy implementation simply delegating to either A or B implementation
 */
class EitherRoChangeRequestRepository(cond: () => Box[Boolean], whenTrue: RoChangeRequestRepository, whenFalse: RoChangeRequestRepository) extends RoChangeRequestRepository {
  //remove some boilerplate to make following proxy implementation more readable, just exposing the actual method to call
  private[this] def condApply[T](method: RoChangeRequestRepository => Box[T]) : Box[T] =  {
    cond().flatMap( if(_) method(whenTrue) else method(whenFalse) )
  }

  def getAll() : Box[Vector[ChangeRequest]] = condApply( _.getAll )

  def get(changeRequestId:ChangeRequestId) : Box[Option[ChangeRequest]] = condApply( _.get(changeRequestId) )

  def getByDirective(id : DirectiveId, onlyPending:Boolean) : Box[Vector[ChangeRequest]] = condApply( _.getByDirective(id, onlyPending) )

  def getByNodeGroup(id : NodeGroupId, onlyPending:Boolean) : Box[Vector[ChangeRequest]] = condApply( _.getByNodeGroup(id, onlyPending) )

  def getByRule(id : RuleId, onlyPending:Boolean) : Box[Vector[ChangeRequest]] = condApply( _.getByRule(id, onlyPending) )

  def getByContributor(actor:EventActor) : Box[Vector[ChangeRequest]] = condApply( _.getByContributor(actor) )
}


/**
 * Write access to change request
 */
trait WoChangeRequestRepository {

  /**
   * Save a new change request in the back-end.
   * The id is ignored, and a new one will be attributed
   * to the change request.
   */
  def createChangeRequest(changeRequest:ChangeRequest, actor:EventActor, reason: Option[String]) : Box[ChangeRequest]

  /**
   * Update a change request. The change request must not
   * be in read-only mode and must exists.
   * The update can not change the read/write mode (such a change
   * will be ignore), an explicit call to setWriteOnly must be
   * done for that.
   */
  def updateChangeRequest(changeRequest:ChangeRequest, actor:EventActor, reason: Option[String]) : Box[ChangeRequest]

  /**
   * Delete a change request.
   * (whatever the read/write mode is).
   */
  def deleteChangeRequest(changeRequestId:ChangeRequestId, actor:EventActor, reason: Option[String]) : Box[ChangeRequest]

}

/**
 * Again, a proxy forwarding to an implementation based on a runtime property
 */
class EitherWoChangeRequestRepository(cond: () => Box[Boolean], whenTrue: WoChangeRequestRepository, whenFalse: WoChangeRequestRepository) extends WoChangeRequestRepository {
  def createChangeRequest(changeRequest:ChangeRequest, actor:EventActor, reason: Option[String]) : Box[ChangeRequest] =
    cond().flatMap( if(_) whenTrue.createChangeRequest(changeRequest, actor, reason) else whenFalse.createChangeRequest(changeRequest, actor, reason))
  def updateChangeRequest(changeRequest:ChangeRequest, actor:EventActor, reason: Option[String]) : Box[ChangeRequest] =
    cond().flatMap( if(_) whenTrue.updateChangeRequest(changeRequest, actor, reason) else whenFalse.updateChangeRequest(changeRequest, actor, reason))
  def deleteChangeRequest(changeRequestId:ChangeRequestId, actor:EventActor, reason: Option[String]) : Box[ChangeRequest] =
    cond().flatMap( if(_) whenTrue.deleteChangeRequest(changeRequestId, actor, reason) else whenFalse.deleteChangeRequest(changeRequestId, actor, reason))
}

