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

package com.normation.rudder.repository

import com.normation.rudder.domain.workflows.ChangeRequest
import net.liftweb.common.Box
import com.normation.rudder.domain.workflows.ChangeRequestId
import com.normation.rudder.domain.workflows.ChangeRequestId
import com.normation.eventlog.EventActor
import com.normation.eventlog.EventActor
import com.normation.rudder.domain.workflows.WorkflowNode
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.policies.RuleId
import com.normation.eventlog.EventActor

/**
 * Read access to change request
 */
trait RoChangeRequestRepository {


  def getAll() : Box[Seq[ChangeRequest]]

  def get(changeRequestId:ChangeRequestId) : Box[Option[ChangeRequest]]

  /**
   * Returns all the change request which are within this ids
   * It is meant to be used with the workflow engine, to fetch all
   * CRs in a specific state
   */
  def getByIds(changeRequestId:Seq[ChangeRequestId]) : Box[Seq[ChangeRequest]]

  def getByDirective(id : DirectiveId) : Box[Seq[ChangeRequest]]

  def getByNodeGroup(id : NodeGroupId) : Box[Seq[ChangeRequest]]

  def getByRule(id : RuleId) : Box[Seq[ChangeRequest]]

  def getByContributor(actor:EventActor) : Box[Seq[ChangeRequest]]
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
