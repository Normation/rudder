/*
*************************************************************************************
* Copyright 2011 Normation SAS
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
import com.normation.box._
import com.normation.cfclerk.domain.TechniqueVersion
import com.normation.eventlog.ModificationId
import com.normation.rudder.domain.policies._
import com.normation.rudder.web.components.popup.CreateCloneDirectivePopup._
import com.normation.rudder.web.model.CurrentUser
import com.normation.rudder.web.model.FormTracker
import com.normation.rudder.web.model.WBTextAreaField
import com.normation.rudder.web.model.WBTextField
import net.liftweb.common._
import net.liftweb.http.js.JE._
import net.liftweb.http.js.JsCmds._
import net.liftweb.http.js._
import net.liftweb.http.DispatchSnippet
import net.liftweb.http.SHtml
import net.liftweb.util.Helpers._

import scala.xml._

object CreateCloneDirectivePopup {
  val htmlId_popupContainer = "createCloneDirectiveContainer"
  val htmlId_popup = "createCloneDirectivePopup"
val html =  SHtml.ajaxForm(
    <div class="modal-backdrop fade in" style="height: 100%;"></div>
    <div id="createCloneDirectiveContainer" class="modal-dialog">
        <div class="modal-content">
            <div class="modal-header">
                <div class="close" data-dismiss="modal">
                <span aria-hidden="true">&times;</span>
                <span class="sr-only">Close</span>
                </div>
                <h4 class="modal-title">Clone directive</h4>
            </div>
            <div class="modal-body">
                <div id="notifications">Here comes validation messages</div>
                <div id="itemName">Here come the Directive name</div>
                <div id="itemDescription">Here come the short description</div>
                <div id="itemReason">Here come the reason field</div>
            </div>
            <div class="modal-footer">
                <button id="cancel" class="btn btn-default">Cancel</button>
                <button id="save" class="btn btn-success">Clone</button>
            </div>
        </div><!-- /.modal-content -->
    </div><!-- /.modal-dialog -->
    )
}

class CreateCloneDirectivePopup(
  techniqueName:String,
  techniqueDescription:String, // this field is unused
  techniqueVersion:TechniqueVersion,
  val directive: Directive,
  onSuccessCallback : (Directive) => JsCmd = { (directive : Directive) => Noop },
  onFailureCallback : () => JsCmd = { () => Noop }
) extends DispatchSnippet with Loggable {

  private[this] val uuidGen               = RudderConfig.stringUuidGenerator
  private[this] val userPropertyService   = RudderConfig.userPropertyService
  private[this] val roDirectiveRepository = RudderConfig.roDirectiveRepository
  private[this] val woDirectiveRepository = RudderConfig.woDirectiveRepository

  def dispatch = {
    case "popupContent" => { _ => popupContent }
  }

  def popupContent() : NodeSeq = {
    ( "#techniqueName" #> techniqueName
    & "#itemName" #> directiveName.toForm_!
    & "#itemDescription" #> directiveShortDescription.toForm_!
    & "#itemReason" #> { reasons.map { f =>
        <div>
           <h4 class="col-lg-12 col-sm-12 col-xs-12 audit-title">Change Audit Log</h4>
          {f.toForm_!}
        </div>
      } }
    & "#cancel" #> (SHtml.ajaxButton("Cancel", { () => closePopup() }) % ("tabindex" -> "4") % ("class" -> "btn btn-default"))
    & "#save" #> (SHtml.ajaxSubmit("Clone", onSubmit _) % ("id" -> "createDirectiveSaveButton") % ("tabindex" -> "3") % ("class" -> "btn"))
    andThen
      "#notifications" #> updateAndDisplayNotifications()
    ) (html)

  }

  ///////////// fields for category settings ///////////////////

  private[this] val reasons = {
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
      override def inputField = super.inputField  % ("style" -> "height:5em;") % ("tabindex" -> "3") % ("placeholder" -> {userPropertyService.reasonsFieldExplanation})
      override def errorClassName = "col-lg-12 errors-container"
      override def validations = {
        if(mandatory){
          valMinLen(5, "The reason must have at least 5 characters.") _ :: Nil
        } else {
          Nil
        }
      }
    }
  }

  private[this] val directiveName = new WBTextField("Name", "Copy of <%s>".format(directive.name)) {
    override def setFilter = notNull _ :: trim _ :: Nil
    override def errorClassName = "col-lg-12 errors-container"
    override def inputField = super.inputField % ("onkeydown" -> "return processKey(event , 'createDirectiveSaveButton')") % ("tabindex" -> "1")
    override def validations =
      valMinLen(1, "Name must not be empty") _ :: Nil
  }

  private[this] val directiveShortDescription =
    new WBTextAreaField("Short description", directive.shortDescription) {
    override def setFilter = notNull _ :: trim _ :: Nil
    override def inputField = super.inputField  % ("style" -> "height:7em") % ("tabindex" -> "2")
    override def errorClassName = "col-lg-12 errors-container"
    override def validations = Nil
  }

  private[this] val formTracker = new FormTracker(directiveName :: directiveShortDescription :: reasons.toList)

  private[this] var notifications = List.empty[NodeSeq]

  private[this] def error(msg:String) = Text(msg)

  private[this] def closePopup() : JsCmd = {
    JsRaw(""" $('#createCloneDirectivePopup').bsModal('hide');""")
  }
  /**
   * Update the form when something happened
   */
  private[this] def updateFormClientSide() : JsCmd = {
    SetHtml(htmlId_popupContainer, popupContent())
  }

  private[this] def onSubmit() : JsCmd = {

    if(formTracker.hasErrors) {
      onFailure & onFailureCallback()
    } else {
      val cloneDirective =
        new Directive(
            id = DirectiveId(uuidGen.newUuid)
          , techniqueVersion = directive.techniqueVersion
          , parameters = directive.parameters
          , name = directiveName.get
          , shortDescription = directiveShortDescription.get
          , _isEnabled = directive.isEnabled
          , policyMode = directive.policyMode
        )
      roDirectiveRepository.getActiveTechniqueAndDirective(directive.id).notOptional(s"Error: active technique for directive '${directive.id}' was not found").toBox match {
        case Full((activeTechnique, _)) =>
          woDirectiveRepository.saveDirective(activeTechnique.id, cloneDirective, ModificationId(uuidGen.newUuid), CurrentUser.actor, reasons.map(_.get)).toBox match {
            case Full(directive) => {
               closePopup() & onSuccessCallback(cloneDirective)
            }
            case eb:EmptyBox =>
              val failure = eb ?~! "An error occurred while saving the new clones Directive %s".format(cloneDirective.name)
              logger.error(failure)
              formTracker.addFormError(error(failure.messageChain))
              onFailure & onFailureCallback()
          }
        case eb:EmptyBox =>
          val failure = eb ?~! "An error occurred while fetching the active Technique of the new cloned Directive %s".format(cloneDirective.name)
          logger.error(failure)
          formTracker.addFormError(error(failure.messageChain))
          onFailure & onFailureCallback()
      }
    }
  }

  private[this] def onFailure : JsCmd = {
    formTracker.addFormError(error("There was problem with your request"))
    updateFormClientSide()
  }

  private[this] def updateAndDisplayNotifications() : NodeSeq = {
    notifications :::= formTracker.formErrors
    formTracker.cleanErrors

    if(notifications.isEmpty) NodeSeq.Empty
    else {
      val html = <div id="notifications" class="alert alert-danger text-center col-lg-12 col-xs-12 col-sm-12" role="alert"><ul class="text-danger">{notifications.map( n => <li>{n}</li>) }</ul></div>
      notifications = Nil
      html
    }
  }
}
