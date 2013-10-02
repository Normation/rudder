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
import com.normation.rudder.web.model.CurrentUser
import com.normation.rudder.authorization.Edit
import com.normation.rudder.services.workflows.WorkflowService
import com.normation.rudder.services.workflows.EitherWorkflowService




class WorkflowInformation extends CometActor with CometListener with Loggable {
  private[this] val workflowService = RudderConfig.workflowService
  private[this] val asyncWorkflow   = RudderConfig.asyncWorkflowInfo

  private[this] val isValidator = CurrentUser.checkRights(Edit("validator"))
  private[this] val isDeployer = CurrentUser.checkRights(Edit("deployer"))
  def registerWith = asyncWorkflow


  val layout =
    <div id="workflowInfo" class="modificationsDisplayer">
        <span >Open change requests</span>
        <span class="pendingModifications" >[here comes the modifications]</span>
        <span class="pendingDeployment" >[here comes the pending deployment]</span>
  </div>

  def render = {
    val xml = RudderConfig.configService.rudder_workflow_enabled match {
      case eb:EmptyBox =>
        val e = eb ?~! "Error when trying to read Rudder configuration for workflow activation"
        logger.error(e.messageChain)
        e.rootExceptionCause.foreach(ex =>
          logger.error("Exception was:", e)
        )

        (".modificationsDisplayer *" #> <span class="error">{e.msg}</span>).apply(layout)


      case Full(workflowEnabled) =>
        val cssSelect =
          if(workflowEnabled && (isValidator || isDeployer )) {
            {
              if (isValidator) pendingModifications
              else ".pendingModifications" #> Text("")
            } & {
              if (isDeployer) pendingDeployment
              else ".pendingDeployment" #> Text("")
            }
          } else {
            ".modificationsDisplayer" #> Text("")
          }

        cssSelect(layout)
    }

    new RenderOut(xml)
  }

  def pendingModifications = {
    val xml = pendingModificationRec(workflowService)

    ".pendingModifications" #> xml
  }

  private[this] def pendingModificationRec(workflowService: WorkflowService): NodeSeq = {
    workflowService match {
      case ws:TwoValidationStepsWorkflowServiceImpl =>
        ws.getItemsInStep(ws.Validation.id) match {
          case Full(seq) =>
            seq.size match {
              case 0 =>
                <span style="font-size:12px; padding-left:25px; padding-top:2px">Pending review: 0</span>
              case size =>
                <span style="font-size:12px; padding-top:2px"><img src="/images/icWarn.png" alt="Warning!" height="15" width="15" class="warnicon" style="margin-top:0px !important"/> <a href="/secure/utilities/changeRequests/Pending_validation" style="color:#999999">Pending review: {size}</a></span>
            }
          case e:EmptyBox =>
            <p class="error">Error when trying to fetch pending change requests.</p>
        }
      case either: EitherWorkflowService => pendingModificationRec(either.current)
      case _ => //For other kind of workflows, this has no meaning
        <p class="error">Error, the configured workflow does not have that step.</p>
    }
  }

  def pendingDeployment = {
    val xml = pendingDeploymentRec(workflowService)

    ".pendingDeployment" #> xml
  }

  private[this] def pendingDeploymentRec(workflowService: WorkflowService): NodeSeq = {
    workflowService match {
      case ws:TwoValidationStepsWorkflowServiceImpl =>
        ws.getItemsInStep(ws.Deployment.id) match {
          case Full(seq) =>
            seq.size match {
              case 0 =>
                <span style="font-size:12px; padding-left:25px; padding-top:2px">Pending deployment: 0</span>
              case size =>
                <span style="font-size:12px; padding-top:2px"><img src="/images/icWarn.png" alt="Warning!" height="15" width="15" class="warnicon" style="margin-top:0px !important"/> <a href="/secure/utilities/changeRequests/Pending_deployment" style="color:#999999">Pending deployment: {size}</a> </span>
            }
          case e:EmptyBox =>
            <p class="error">Error when trying to fetch pending change requests.</p>
        }
      case either: EitherWorkflowService => pendingDeploymentRec(either.current)
      case _ => //For other kind of workflows, this has no meaning
        <p class="error">Error, the configured workflow does not have that step.</p>
    }
  }

  override def lowPriority = {
    case WorkflowUpdate => reRender
  }
}