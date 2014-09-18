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

    <li class="dropdown">
          <a href="#" class="dropdown-toggle"  data-toggle="dropdown">
            <span>CR</span>
            <span class="badge" id="number">42</span>
            <span class="caret"></span>
          </a>
          <ul class="dropdown-menu" role="menu">
          </ul>
  </li>


  def render = {
    val xml = RudderConfig.configService.rudder_workflow_enabled match {
      case eb:EmptyBox =>
        val e = eb ?~! "Error when trying to read Rudder configuration for workflow activation"
        logger.error(e.messageChain)
        e.rootExceptionCause.foreach(ex =>
          logger.error("Exception was:", e)
        )

        (".dropdown-menu *" #> <li class="dropdown-header">{e.msg}</li>).apply(layout)


      case Full(workflowEnabled) =>
        val cssSelect =
          if(workflowEnabled && (isValidator || isDeployer )) {
            {
              if (isValidator) pendingModifications
              else ".dropdown-menu *+" #> NodeSeq.Empty
            } & {
              if (isDeployer) pendingDeployment
              else ".dropdown-menu *+" #> NodeSeq.Empty
            } &
            "#number *" #> requestCount(workflowService)
          } else {
            ".dropdown *" #> NodeSeq.Empty
          }

        cssSelect(layout)
    }

    new RenderOut(xml)
  }

  def requestCount(workflowService : WorkflowService) : Int = {

    workflowService match {
      case ws:TwoValidationStepsWorkflowServiceImpl =>
        val validation = if (isValidator) ws.getItemsInStep(ws.Validation.id).map(_.size).getOrElse(0) else 0
        val deployment = if (isDeployer) ws.getItemsInStep(ws.Deployment.id).map(_.size).getOrElse(0) else 0
        validation  + deployment
      case either: EitherWorkflowService => requestCount(either.current)
      case _ => 0
  }
  }

  def pendingModifications = {
    val xml = pendingModificationRec(workflowService)

    ".dropdown-menu *+" #> xml
  }

  private[this] def pendingModificationRec(workflowService: WorkflowService): NodeSeq = {
    workflowService match {
      case ws:TwoValidationStepsWorkflowServiceImpl =>
        ws.getItemsInStep(ws.Validation.id) match {
          case Full(seq) =>
                <li >
                  <a href="/secure/utilities/changeRequests/Pending_validation">
                  Pending review:
                  <span class="badge">{seq.size}</span>
                  </a>
               </li>
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

    ".dropdown-menu *+" #> xml
  }

  private[this] def pendingDeploymentRec(workflowService: WorkflowService): NodeSeq = {
    workflowService match {
      case ws:TwoValidationStepsWorkflowServiceImpl =>
        ws.getItemsInStep(ws.Deployment.id) match {
          case Full(seq) =>
                <li>
                  <a href="/secure/utilities/changeRequests/Pending_deployment">
                  Pending deployment:
                  <span class="badge">{seq.size}</span>
                  </a>
               </li>

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