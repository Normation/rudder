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

package com.normation.rudder.web.components

import scala.xml.NodeSeq
import bootstrap.liftweb.RudderConfig
import net.liftweb.common.EmptyBox
import net.liftweb.common.Loggable
import net.liftweb.common.Full
import net.liftweb.util.Helpers._
import com.normation.rudder.domain.workflows.ChangeRequest
import net.liftweb.common.Box
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.web.model.CurrentUser
import com.normation.rudder.AuthorizationType

import com.normation.box._

/*
 * This object is just a service that check if a given rule/directive/etc has
 * already a change request on it to display a relevant information if it is
 * the case.
 */
object PendingChangeRequestDisplayer extends Loggable{

  private[this] val workflowLevel = RudderConfig.workflowLevelService
  private[this] val linkUtil      = RudderConfig.linkUtil

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
            if (CurrentUser.checkRights(AuthorizationType.Validator.Read)||CurrentUser.checkRights(AuthorizationType.Deployer.Read)||cr.owner == CurrentUser.actor.name) {
              <li><a href={linkUtil.baseChangeRequestLink(cr.id)}>CR #{cr.id}: {cr.info.name}</a></li>
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
    if (RudderConfig.configService.rudder_workflow_enabled().toBox.getOrElse(false)) {
      val crs = check(id,true)
      displayPendingChangeRequest(xml,crs)
    }  else {
      // Workflow disabled, nothing to display
      NodeSeq.Empty
    }
  }

  def checkByRule(xml:NodeSeq,ruleId:RuleId) = {
    checkChangeRequest(xml,ruleId,workflowLevel.getByRule)
  }

  def checkByGroup(xml:NodeSeq,groupId:NodeGroupId) = {
    checkChangeRequest(xml,groupId,workflowLevel.getByNodeGroup)
  }

  def checkByDirective(xml:NodeSeq,directiveId:DirectiveId) = {
    checkChangeRequest(xml,directiveId,workflowLevel.getByDirective)
  }
}
