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
package com.normation.rudder.web.components.popup

import bootstrap.liftweb.RudderConfig
import net.liftweb.http.js._
import JsCmds._
import com.normation.rudder.domain.policies._
import JE._
import net.liftweb.common._
import net.liftweb.http.{SHtml,DispatchSnippet}
import scala.xml._
import net.liftweb.util.Helpers._
import com.normation.rudder.web.model.{
  WBTextField, FormTracker, WBTextAreaField
}
import com.normation.rudder.domain.workflows.ChangeRequestId
import com.normation.rudder.web.model.CurrentUser
import com.normation.rudder.web.ChooseTemplate

/**
 * Validation pop-up for modification on rules only.
 *
 * The validation pop-up contains 2 mains parts, optionnaly empty:
 *
 * - the workflows part (asking what to do if the wf is enable)
 * - the change message
 *
 */

object RuleModificationValidationPopup extends Loggable {
  val htmlId_popupContainer = "validationContainer"

  private def html = ChooseTemplate(
      "templates-hidden" :: "Popup" :: "RuleModificationValidationPopup" :: Nil
    , "component-validationpopup"
  )

  /* Text variation for
   * - Rules
   * - Enable & disable (for rule), delete, modify (save)
   */
  private def titles = Map(
      "enable"  -> "Enable a Rule"
    , "disable" -> "Disable a Rule"
    , "delete"  -> "Delete a Rule"
    , "save"    -> "Update a Rule"
    , "create"  -> "Create a Rule"
  )
  private def titleworkflow = Map(
      "enable"  -> "enable"
    , "disable" -> "disable"
    , "delete"  -> "delete"
    , "save"    -> "update"
    , "create"  -> "create"
  )
  private def explanationMessages = Map(
      "enable"  ->
    <div class="row">
        <h4 class="col-lg-12 col-sm-12 col-xs-12 text-center">
            Are you sure that you want to enable this Rule?
        </h4>
    </div>
    , "disable" ->
    <div class="row">
        <h4 class="col-lg-12 col-sm-12 col-xs-12 text-center">
            Are you sure that you want to disable this Rule?
        </h4>
    </div>
    , "delete"  ->

    <div class="row">
        <h4 class="col-lg-12 col-sm-12 col-xs-12 text-center">
            Are you sure that you want to delete this Rule?
        </h4>
    </div>
    , "save"    ->
      <div class="row">
        <h4 class="col-lg-12 col-sm-12 col-xs-12 text-center">
            Are you sure that you want to update this Rule?
        </h4>
    </div>
    , "create"    ->
      <div class="row">
        <h4 class="col-lg-12 col-sm-12 col-xs-12 text-center">
            Are you sure that you want to create this Rule?
        </h4>
    </div>
  )

}

class RuleModificationValidationPopup(
    rule              : Rule
  , initialState      : Option[Rule]
  , action            : String //one among: save, delete, enable, disable or create
  , workflowEnabled   : Boolean
  , onSuccessCallBack : (Either[Rule,ChangeRequestId]) => JsCmd = { x => Noop }
  , onFailureCallback : () => JsCmd = { () => Noop }
  , parentFormTracker : Option[FormTracker] = None
) extends DispatchSnippet with Loggable {

  import RuleModificationValidationPopup._

  private[this] val userPropertyService      = RudderConfig.userPropertyService
  private[this] val changeRequestService     = RudderConfig.changeRequestService
  private[this] val workflowService          = RudderConfig.workflowService

  def dispatch = {
    case "popupContent" => { _ => popupContent }
  }

  def popupContent() : NodeSeq = {
    val (buttonName, classForButton) = (workflowEnabled,action) match {
      case (false, "save" )=> ("Update", "btn-success")
      case (false, "delete" )=> ("Delete", "btn-danger")
      case (false, "enable" )=> ("Enable", "btn-primary")
      case (false, "disable" )=> ("Disable", "btn-primary")
      case (false, "create" )=> ("Create", "btn-success")
      case (false, _ )=> ("Save", "btn-success")
      case (true, _ )=> ("Open request", "wideButton btn-primary")
    }

    val titleWorkflow = workflowEnabled match {
      case true =>
          <h4 class="text-center titleworkflow">Are you sure that you want to {titleworkflow(action)} this rule?</h4>
          <h4 class="col-lg-12 col-sm-12 col-xs-12 audit-title">Change Request</h4>
          <hr class="css-fix"/>
          <div class="text-center alert alert-info">
            <span class="glyphicon glyphicon-info-sign"></span>
            Workflows are enabled, your change has to be validated in a Change request
          </div>
      case false =>  explanationMessages(action)
    }
    (
      "#validationForm" #> { (xml:NodeSeq) => SHtml.ajaxForm(xml) } andThen
      "#dialogTitle *" #> titles(action) &
      "#titleWorkflow *" #> titleWorkflow &
      "#changeRequestName" #> {
          if (workflowEnabled) {
            changeRequestName.toForm
          } else
            Full(NodeSeq.Empty)
      } &
      ".reasonsFieldsetPopup" #> {
        crReasons.map { f =>
          <div>
            {if (!workflowEnabled) {
              <h4 class="col-lg-12 col-sm-12 col-xs-12 audit-title">Change Audit Log</h4>
            }}
              {f.toForm_!}
          </div>
        }
      } &
      "#saveStartWorkflow" #> (SHtml.ajaxSubmit(buttonName, () => onSubmit(), ("class" -> classForButton)) % ("id", "createDirectiveSaveButton") % ("tabindex","3")) andThen
       ".notifications *" #> updateAndDisplayNotifications()

    )(html)
  }

  ///////////// fields for category settings ///////////////////

  private[this] val crReasons = {
    import com.normation.rudder.web.services.ReasonBehavior._
    userPropertyService.reasonsFieldBehavior match {
      case Disabled => None
      case Mandatory => Some(buildReasonField(true, "subContainerReasonField"))
      case Optionnal => Some(buildReasonField(false, "subContainerReasonField"))
    }
  }

  def buildReasonField(mandatory:Boolean, containerClass:String = "twoCol") = {
    new WBTextAreaField("Change audit message", "") {
      override def setFilter = notNull _ :: trim _ :: Nil
      override def inputField = super.inputField  %  ("style" -> "height:8em;") % ("tabindex" -> "2") % ("placeholder" -> {userPropertyService.reasonsFieldExplanation})
      //override def subContainerClassName = containerClass
      override def validations() = {
        if(mandatory){
          valMinLen(5, "The reason must have at least 5 characters.") _ :: Nil
        } else {
          Nil
        }
      }
    }
  }

  private[this] val defaultActionName = Map (
      "enable"  -> "Enable"
    , "disable" -> "Disable"
    , "delete"  -> "Delete"
    , "save"    -> "Update"
    , "create"  -> "Create"
  )(action)
  private[this] val defaultRequestName = s"${defaultActionName} Rule ${rule.name}"

  private[this] val changeRequestName = new WBTextField("Change request title", defaultRequestName) {
    override def setFilter = notNull _ :: trim _ :: Nil
    override def errorClassName = "col-lg-12 errors-container"
    override def inputField = super.inputField % ("onkeydown" , "return processKey(event , 'createDirectiveSaveButton')") % ("tabindex","1")
    override def validations =
      valMinLen(1, "Name must not be empty") _ :: Nil
  }

  // The formtracker needs to check everything only if there is workflow
  private[this] val formTracker = {
    val fields = crReasons.toList ::: {
      if (workflowEnabled) changeRequestName :: Nil
      else Nil
    }

    new FormTracker(fields)
  }

  private[this] def error(msg:String) = <span>{msg}</span>

  private[this] def closePopup() : JsCmd = {
    JsRaw("""$('#confirmUpdateActionDialog').bsModal('hide');""")
  }

  /**
   * Update the form when something happened
   */
  private[this] def updateFormClientSide() : JsCmd = {
    SetHtml(htmlId_popupContainer, popupContent())
  }

  private[this] def ruleDiffFromAction(): Box[ChangeRequestRuleDiff] = {
    initialState match {
      case None =>
        if ((action == "save") || (action == "create"))
          Full(AddRuleDiff(rule))
        else
          Failure(s"Action ${action} is not possible on a new Rule")
      case Some(d) =>
        action match {
          case "delete" => Full(DeleteRuleDiff(rule))
          case "save" | "disable" | "enable" | "create" => Full(ModifyToRuleDiff(rule))
          case _ => Failure(s"Action ${action} is not possible on a existing Rule")
        }
    }
  }

  def onSubmit() : JsCmd = {
    if(formTracker.hasErrors) {
      onFailure
    } else {
       //based on the choice of the user, create or update a Change request
        val savedChangeRequest = {
          for {
            diff   <- ruleDiffFromAction()
            cr     <- changeRequestService.createChangeRequestFromRule(
                         changeRequestName.get
                       , crReasons.map( _.get ).getOrElse("")
                       , rule
                       , initialState
                       , diff
                       , CurrentUser.getActor
                       , crReasons.map( _.get )
                       )
            wfStarted <- workflowService.startWorkflow(cr.id, CurrentUser.getActor, crReasons.map(_.get))
          } yield {
            cr.id
          }
        }
        savedChangeRequest match {
          case Full(cr) =>
            if (workflowEnabled)
              closePopup() & onSuccessCallBack(Right(cr))
            else
              closePopup() & onSuccessCallBack(Left(rule))
          case eb:EmptyBox =>
            val e = (eb ?~! "Error when trying to save your modification")
            parentFormTracker.map( _.addFormError(error(e.messageChain)))
            logger.error(e.messageChain)
            e.chain.foreach { ex =>
              parentFormTracker.map(x => x.addFormError(error(ex.messageChain)))
              logger.error(s"Exception when trying to update a change request:", ex)
            }
            onFailureCallback()
        }
    }
  }

  private[this] def onFailure : JsCmd = {
    formTracker.addFormError(error("There was problem with your request"))
    updateFormClientSide()
  }

  private[this] def updateAndDisplayNotifications() : NodeSeq = {
    val notifications = formTracker.formErrors
    formTracker.cleanErrors
    if(notifications.isEmpty) NodeSeq.Empty
    else {
      val html = <div id="notifications" class="alert alert-danger text-center col-lg-12 col-xs-12 col-sm-12" role="alert"><ul class="text-danger">{notifications.map( n => <li>{n}</li>) }</ul></div>
      html
    }
  }
}
