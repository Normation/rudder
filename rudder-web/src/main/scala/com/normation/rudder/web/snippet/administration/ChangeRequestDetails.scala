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

package com.normation.rudder.web.snippet.administration


import net.liftweb.common._
import bootstrap.liftweb.RudderConfig
import net.liftweb.http._
import net.liftweb.http.js._
import JE._
import JsCmds._
import net.liftweb.util._
import Helpers._
import scala.xml.Text
import scala.xml.NodeSeq
import com.normation.rudder.domain.workflows._
import com.normation.rudder.web.model._
import org.joda.time.DateTime
import com.normation.cfclerk.domain.TechniqueId
import com.normation.rudder.domain.policies._
import com.normation.rudder.web.components._
import com.normation.eventlog.EventActor
import com.normation.eventlog.EventLog
import com.normation.rudder.domain.eventlog.ModifyChangeRequest
import com.normation.rudder.domain.eventlog.AddChangeRequest
import com.normation.rudder.domain.eventlog.DeleteChangeRequest
import com.normation.rudder.services.workflows.NoWorkflowAction
import com.normation.rudder.services.workflows.WorkflowAction
import com.normation.rudder.authorization.Edit
import com.normation.rudder.authorization.Read


object ChangeRequestDetails {

  private[this] val templatePathList = "templates-hidden" :: "components" :: "ComponentChangeRequest" :: Nil
  private val templatePath = templatePathList.mkString("", "/", ".html")

  private[this] def chooseNodes(tag:String) : NodeSeq = {
    val xml = chooseTemplate("component", tag, template)
    if(xml.isEmpty) throw new Exception(s"Missing tag <component:${tag}> in template at path ${templatePath}")
    else xml
  }

  def template = Templates(templatePathList).openOrThrowException(s"Can not find template at path ${templatePath}")

  val header = chooseNodes("header")
  val popup = chooseNodes("popup")
  val popupContent = chooseNodes("popupContent")
  val actionButtons = chooseNodes("actionButtons")
  def unmergeableWarning = chooseNodes("warnUnmergeable")
}

class ChangeRequestDetails extends DispatchSnippet with Loggable {
  import ChangeRequestDetails._


  private[this] val techRepo = RudderConfig.techniqueRepository
  private[this] val rodirective = RudderConfig.roDirectiveRepository
  private[this] val uuidGen = RudderConfig.stringUuidGenerator
  private[this] val userPropertyService      = RudderConfig.userPropertyService
  private[this] val workFlowEventLogService =  RudderConfig.workflowEventLogService
  private[this] val changeRequestService  = RudderConfig.changeRequestService
  private[this] val workflowService = RudderConfig.workflowService
  private[this] val eventlogDetailsService = RudderConfig.eventLogDetailsService
  private[this] val commitAndDeployChangeRequest =  RudderConfig.commitAndDeployChangeRequest


  private[this] val changeRequestTableId = "ChangeRequestId"
  private[this] val CrId: Box[Int] = {S.param("crId").map(x=>x.toInt) }
  private[this] var changeRequest: Box[ChangeRequest] = {
    CrId match {
    case Full(id) => changeRequestService.get(ChangeRequestId(id)) match {
      case Full(Some(cr)) =>
        if (CurrentUser.checkRights(Read("validator"))||CurrentUser.checkRights(Read("deployer"))||cr.owner == CurrentUser.getActor.name)
        Full(cr)
        else Failure("You are not allowed to see this change request")
      case Full(None) => Failure(s"There is no Cr with id :${id}")
      case eb:EmptyBox =>       val fail = eb ?~ "no id selected"
      Failure(s"Error in the cr id asked: ${fail.msg}")
    }
    case eb:EmptyBox =>
      val fail = eb ?~ "no id selected"
      Failure(s"Error in the cr id asked: ${fail.msg}")
  }
  }
  private[this] def step = changeRequest.flatMap(cr => workflowService.findStep(cr.id))

  def dispatch = {
    // Display Change request Header
    case "header" =>
      ( xml =>
        changeRequest match {
          case eb:EmptyBox => NodeSeq.Empty
          case Full(cr) => displayHeader(cr)
        }
      )

    // Display change request details
    case "details" =>
      ( xml =>

        changeRequest match {
          case eb:EmptyBox =>
            val error = eb ?~ "Error"
            <div style="padding :40px;text-align:center">
              <h2>{error.msg}</h2>
              <h3>You will be redirected to the change requests page</h3>
            </div>++
          Script(JsRaw(s"""setTimeout("location.href = '${S.contextPath}/secure/utilities/changeRequests';",5000);"""))
          case Full(cr) =>
            new ChangeRequestEditForm(
                cr.info
              , step.map(_.value)
              , cr.id
              , changeDetailsCallback(cr) _
            ).display
        }
      )

    // Display change request content
    case "changes" =>
      ( xml =>
        changeRequest match {
          case eb:EmptyBox => NodeSeq.Empty
          case Full(id) =>
            val form = new ChangeRequestChangesForm(id).dispatch("changes")(xml)
            <div id="changeRequestChanges">{form}</div>
        }
      )

    // Add action buttons
    case "actions" =>
      ( _ =>
        changeRequest match {
          case eb:EmptyBox => NodeSeq.Empty
          case Full(cr) =>
            step match {
              case eb:EmptyBox =>  NodeSeq.Empty
              case Full(step) => <div id="workflowActionButtons" style="margin: 0 40px">{displayActionButton(cr,step)}</div>
            }
        }
      )

    case "warnUnmergeable" => ( _ =>
      changeRequest match {
        case eb:EmptyBox => NodeSeq.Empty
        case Full(cr) => displayWarnUnmergeable(cr)
      }
    )
  }

  def displayActionButton(cr:ChangeRequest,step:WorkflowNodeId):NodeSeq = {
    val authz = CurrentUser.getRights.authorizationTypes.toSeq.collect{case Edit(right) => right}
    val isOwner = cr.owner == CurrentUser.getActor.name
    ( "#backStep" #> {
      workflowService.findBackSteps(authz, step,isOwner) match {
        case Nil => NodeSeq.Empty
        case steps =>
          SHtml.ajaxButton(
              "Decline"
            , () => ChangeStepPopup("Decline", steps, cr)
          ) } }  &
      "#nextStep" #> {
        if(commitAndDeployChangeRequest.isMergeable(cr.id)) {
          workflowService.findNextSteps(authz,step,isOwner) match {
            case NoWorkflowAction => NodeSeq.Empty
            case WorkflowAction(actionName,emptyList) if emptyList.size == 0 => NodeSeq.Empty
            case WorkflowAction(actionName,steps) =>
              SHtml.ajaxButton(
                  actionName
                , () => ChangeStepPopup(actionName,steps,cr)
              ) }
        } else NodeSeq.Empty
      }
    ) (actionButtons)
  }

  private[this] def changeDetailsCallback (cr:ChangeRequest)(statusUpdate:ChangeRequestInfo) =  {
    val newCR = changeRequestService.updateChangeRequestInfo(cr, statusUpdate, CurrentUser.getActor, None)
    changeRequest = newCR
    SetHtml("changeRequestHeader", displayHeader(newCR.openOr(cr))) &
    SetHtml("changeRequestChanges", new ChangeRequestChangesForm(newCR.openOr(cr)).dispatch("changes")(NodeSeq.Empty))
  }

  def displayHeader(cr:ChangeRequest) = {
    //last action on the change Request (name/description changed):
    val (action,date) = changeRequestService.getLastLog(cr.id) match {
      case eb:EmptyBox => ("Error when retrieving the last action",None)
      case Full(None)  => ("Error, no action were recorded for that change request",None) //should not happen here !
      case Full(Some(e:EventLog)) =>
        val actionName = e match {
          case ModifyChangeRequest(_) => "Modified"
          case AddChangeRequest(_)    => "Created"
          case DeleteChangeRequest(_) => "Deleted"
        }
        (s"${actionName} on ${DateFormaterService.getFormatedDate(e.creationDate)} by ${e.principal.name}",Some(e.creationDate))
    }

    // Last workflow change on that change Request
    val (step,stepDate) = workFlowEventLogService.getLastLog(cr.id) match {
      case eb:EmptyBox => ("Error when retrieving the last action",None)
      case Full(None)  => ("Error when retrieving the last action",None) //should not happen here !
      case Full(Some(event)) =>
        val changeStep = eventlogDetailsService.getWorkflotStepChange(event.details).map(step => s"State changed from ${step.from} to ${step.to}").getOrElse("Step changed")

        (s"${changeStep} on ${DateFormaterService.getFormatedDate(event.creationDate)} by ${event.principal.name}",Some(event.creationDate))
    }

    // Compare both to find the oldest
    val last = (date,stepDate) match {
      case (None,None) => action
      case (Some(_),None) => action
      case (None,Some(_)) => step
      case (Some(date),Some(stepDate)) => if (date.isAfter(stepDate)) action else step
    }
    val link = <b style="margin-top:10px"> ← Back to change request list</b>
    ( "#backButton *" #> SHtml.a(() => S.redirectTo("/secure/utilities/changeRequests"),link) &
      "#CRName *" #> s"CR #${cr.id}: ${cr.info.name}" &
      "#CRStatus *" #> workflowService.findStep(cr.id).map(x => Text(x.value)).openOr(<div class="error">Cannot find the status of this change request</div>) &
      "#CRLastAction *" #> s"${ last }"
    ) (header)

  }

  def displayWarnUnmergeable(cr:ChangeRequest) : NodeSeq = {
    if(commitAndDeployChangeRequest.isMergeable(cr.id)) {
      NodeSeq.Empty
    } else {
      unmergeableWarning
    }
  }


  def ChangeStepPopup(action:String,nextSteps:Seq[(WorkflowNodeId,(ChangeRequestId,EventActor, Option[String]) => Box[WorkflowNodeId])],cr:ChangeRequest) = {
    type stepChangeFunction = (ChangeRequestId,EventActor, Option[String]) => Box[WorkflowNodeId]

    def closePopup : JsCmd =
      SetHtml("changeRequestHeader", displayHeader(cr)) &
      SetHtml("CRStatusDetails",workflowService.findStep(cr.id).map(x => Text(x.value)).openOr(<div class="error">Cannot find the status of this change request</div>) ) &
      SetHtml("changeRequestChanges", new ChangeRequestChangesForm(cr).dispatch("changes")(NodeSeq.Empty)) &
      JsRaw("""correctButtons();
               $.modal.close();""")

    var nextChosen = nextSteps.head._2
    val nextSelect =
      SHtml.selectObj(
          nextSteps.map(v => (v._2,v._1.value)), Full(nextChosen)
        , {t:stepChangeFunction => nextChosen = t}
      )
    def nextOne(next:String) : NodeSeq=

        <span id="CRStatus">
          {next}
        </span>


    def buildReasonField(mandatory:Boolean, containerClass:String = "twoCol") = {
      new WBTextAreaField("Message", "") {
        override def setFilter = notNull _ :: trim _ :: Nil
        override def inputField = super.inputField  %  ("style" -> "height:8em;")
        override def validations() = {
          if(mandatory){
            valMinLen(5, "The reason must have at least 5 characters.") _ :: Nil
          } else {
            Nil
          }
        }
      }
    }

    val changeMessage = {
      import com.normation.rudder.web.services.ReasonBehavior._
      userPropertyService.reasonsFieldBehavior match {
        case Disabled => None
        case Mandatory => Some(buildReasonField(true, "subContainerReasonField"))
        case Optionnal => Some(buildReasonField(false, "subContainerReasonField"))
      }
    }

    def changeMessageDisplay = {
      changeMessage.map { f =>
        <div>
          <div style="margin:10px 0px 5px 0px; color:#444">
            {userPropertyService.reasonsFieldExplanation}
          </div>
          {f.toForm_!}
        </div>
      }
    }

    val next = {
      nextSteps match {
        case Nil => <span id="CRStatus">Error</span>
        case (head,_) :: Nil =>  <span id="CRStatus"> {head.value} </span>
        case _ => nextSelect
      }
    }

    val formTracker = new FormTracker( changeMessage.toList )

    def updateAndDisplayNotifications() : NodeSeq = {
      val notifications = formTracker.formErrors
      formTracker.cleanErrors
      if(notifications.isEmpty)
        NodeSeq.Empty
      else
        <div id="notifications" class="notify"><ul>{notifications.map( n => <li>{n}</li>) }</ul></div>
    }

    val introMessage = {
      <div>
        <h2>You want to {action} that change request</h2>
        <b>{ nextSteps match {
          case Nil => "You can't confirm"
          case (next,_) :: Nil => s"The change request will be sent to the '${next}' state"
          case list => s"You have to chose a next state for the change request before you confirm"
        } }
        </b>
     </div>
    }
    def content = {
      ( "#header"   #>  s"${action} CR #${cr.id}: ${cr.info.name}" &
        "#form -*"  #>
          SHtml.ajaxForm(
            ( "#reason"  #> changeMessageDisplay &
              "#next"    #> next &
              "#cancel"  #> SHtml.ajaxButton("Cancel", () => closePopup ) &
              "#confirm" #> SHtml.ajaxSubmit("Confirm", () => confirm()) &
              "#intro *+" #> introMessage andThen
              "#formError *" #> updateAndDisplayNotifications()
              ) (popupContent)
          )
      ) ( popup ) ++
      Script(JsRaw("""updatePopup();"""))
    }

    def updateForm = Replace("changeStatePopup",content)

    def error(msg:String) = <span class="error">{msg}</span>

    def confirm() : JsCmd = {
      if (formTracker.hasErrors) {
        formTracker.addFormError(error("The form contains some errors, please correct them"))
        updateForm
      }
      else {
        nextChosen(cr.id,CurrentUser.getActor,changeMessage.map(_.is)) match {
          case Full(next) =>
            SetHtml("workflowActionButtons", displayActionButton(cr,next)) &
            SetHtml("newStatus",Text(next.value)) &
            closePopup & JsRaw(""" callPopupWithTimeout(200, "successWorkflow"); """)
          case eb:EmptyBox => val fail = eb ?~! "could not change Change request step"
            formTracker.addFormError(error(fail.msg))
            updateForm
        }

      }
    }

    SetHtml("popupContent",content) &
    JsRaw("createPopup('changeStatePopup')")

  }
}
