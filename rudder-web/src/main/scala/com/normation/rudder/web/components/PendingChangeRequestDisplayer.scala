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

package com.normation.rudder.web.components

import scala.xml.NodeSeq
import com.normation.rudder.domain.policies.RuleId
import bootstrap.liftweb.RudderConfig
import net.liftweb.common.EmptyBox
import net.liftweb.common.Loggable
import net.liftweb.common.Full
import com.normation.rudder.web.model.JsInitContextLinkUtil
import net.liftweb.util.Helpers._
import com.normation.rudder.domain.workflows.ChangeRequest
import net.liftweb.common.Box
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.web.model.CurrentUser
import com.normation.rudder.authorization.Read

object PendingChangeRequestDisplayer extends Loggable{

  private[this] val roChangeRequestRepo = RudderConfig.roChangeRequestRepository

  private[this] def displayPendingChangeRequest(xml:NodeSeq, crs:Box[Seq[ChangeRequest]] ) : NodeSeq = {
    crs match {
      case eb: EmptyBox =>
        val e = eb ?~! "Error when trying to lookup pending change request"
        logger.error(e.messageChain)
        e.rootExceptionCause.foreach { ex =>
          logger.error("Exception was:", ex)
        }
        <span class="error">{e.messageChain}</span>
      case Full(crs) if(crs.size == 0) =>
        NodeSeq.Empty
      case Full(crs) =>
        // Need to fold the Element into one parent, or it behaves strangely (repeat the parent ...)
        val pendingChangeRequestLink =( NodeSeq.Empty /: crs) {
          (res,cr) => res ++
          {
            if (CurrentUser.checkRights(Read("validator"))||CurrentUser.checkRights(Read("deployer"))||cr.owner == CurrentUser.getActor.name) {
              <li><a href={JsInitContextLinkUtil.changeRequestLink(cr.id)}>CR #{cr.id}: {cr.info.name}</a></li>
            } else {
              <li>CR #{cr.id}</li>
          } }
        }
        ("#changeRequestList *+" #> pendingChangeRequestLink ).apply(xml)
    }

  }

  private type checkFunction[T] = (T,Boolean) => Box[Seq[ChangeRequest]]

  private[this] def checkChangeRequest[T] (
      xml   : NodeSeq
    , id    : T
    , check : checkFunction[T]
  ) = {
    if (RudderConfig.RUDDER_ENABLE_APPROVAL_WORKFLOWS) {
      val crs = check(id,true)
      displayPendingChangeRequest(xml,crs)
    }  else {
      // Workflow disabled, nothing to display
      NodeSeq.Empty
    }
  }

  def checkByRule(xml:NodeSeq,ruleId:RuleId) = {
    checkChangeRequest(xml,ruleId,roChangeRequestRepo.getByRule)
  }

  def checkByGroup(xml:NodeSeq,groupId:NodeGroupId) = {
    checkChangeRequest(xml,groupId,roChangeRequestRepo.getByNodeGroup)
  }

  def checkByDirective(xml:NodeSeq,directiveId:DirectiveId) = {
    checkChangeRequest(xml,directiveId,roChangeRequestRepo.getByDirective)
  }
}