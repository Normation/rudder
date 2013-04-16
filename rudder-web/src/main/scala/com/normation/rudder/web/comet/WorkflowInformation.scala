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

package com.normation.rudder.web.comet

import scala.xml._
import net.liftweb.common._
import net.liftweb.http._
import net.liftweb.util._
import Helpers._
import net.liftweb.http.js._
import JsCmds._
import JE._
import net.liftweb.util.Helpers._
import bootstrap.liftweb.RudderConfig
import com.normation.rudder.services.workflows.WorkflowUpdate
import com.normation.rudder.services.workflows.TwoValidationStepsWorkflowServiceImpl
import com.normation.rudder.domain.workflows.WorkflowNodeId
import com.normation.rudder.domain.workflows.ChangeRequestId




class WorkflowInformation extends CometActor with CometListener with Loggable {
  private[this] val workflowService = RudderConfig.workflowService
  private[this] val workflowEnabled = RudderConfig.RUDDER_ENABLE_APPROVAL_WORKFLOWS
  private[this] val asyncWorkflow   = RudderConfig.asyncWorkflowInfo

  def registerWith = asyncWorkflow

  def render = {
    new RenderOut(( workflowEnabled match {
      case true =>   pendingModifications & pendingDeployment
      case _ => ".modificationsDisplayer" #> Text("")
    } ) (layout))
  }

  val layout =
    <div id="workflowInfo" class="modificationsDisplayer"><lift:authz role="validator_read">
        <span >Open change requests:</span>
        <span class="pendingModifications" >[here comes the modifications]</span>
      </lift:authz>
      <lift:authz role="deployer_read">
        <span class="pendingDeployment" >[here comes the pending deployment]</span>
      </lift:authz>
  </div>

  def pendingModifications = {

    val xml = workflowService match {
      case ws:TwoValidationStepsWorkflowServiceImpl =>
        ws.getItemsInStep(ws.Validation.id) match {
          case Full(seq) =>
            seq.size match {
              case 0 =>
                <span style="font-size:12px; padding-left:25px; padding-top:2px">Pending review: 0</span>
              case size =>
                <span style="font-size:12px; padding-top:2px"><img src="/images/icWarn.png" alt="Warning!" height="15" width="15" class="warnicon"/> <a href="/secure/utilities/changeRequests/Pending_validation" style="color:#999999">Pending review: {size}</a></span>
            }
          case e:EmptyBox =>
            <p class="error">Error when trying to fetch pending change requests.</p>
        }
      case _ => //For other kind of workflows, this has no meaning
        <p class="error">Error, the configured workflow does not have that step.</p>
    }

    ".pendingModifications" #> xml
  }

  def pendingDeployment = {
    val xml = workflowService match {
      case ws:TwoValidationStepsWorkflowServiceImpl =>
        ws.getItemsInStep(ws.Deployment.id) match {
          case Full(seq) =>
            seq.size match {
              case 0 =>
                <span style="font-size:12px; padding-left:25px; padding-top:2px">Pending deployment: 0</span>
              case size =>
                <span style="font-size:12px; padding-top:2px"><img src="/images/icWarn.png" alt="Warning!" height="15" width="15" class="warnicon"/> <a href="/secure/utilities/changeRequests/Pending_deployment" style="color:#999999">Pending deployment: {size}</a> </span>
            }
          case e:EmptyBox =>
            <p class="error">Error when trying to fetch pending change requests.</p>
        }
      case _ => //For other kind of workflows, this has no meaning
        <p class="error">Error, the configured workflow does not have that step.</p>
    }

    ".pendingDeployment" #> xml
  }

  override def lowPriority = {
    case WorkflowUpdate => reRender
  }
}