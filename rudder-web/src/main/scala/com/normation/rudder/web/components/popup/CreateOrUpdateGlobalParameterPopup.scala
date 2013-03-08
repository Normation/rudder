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
package com.normation.rudder.web.components.popup

import bootstrap.liftweb.RudderConfig

import net.liftweb.common._
import net.liftweb.http.DispatchSnippet
import net.liftweb.util._
import net.liftweb.util.Helpers._
import scala.xml._
import net.liftweb.http._
import net.liftweb.http.js._
import net.liftweb.http.js.JsCmds._
import net.liftweb.http.js.JE._
import com.normation.rudder.domain.parameters.GlobalParameter
import com.normation.rudder.domain.parameters.ParameterName
import com.normation.eventlog.ModificationId
import org.joda.time.DateTime
import com.normation.rudder.web.model.CurrentUser
import com.normation.rudder.web.model.{
  WBTextField, FormTracker, WBTextAreaField, WBRadioField
}
import java.util.regex.Pattern
import CreateOrUpdateGlobalParameterPopup._

class CreateOrUpdateGlobalParameterPopup(
  parameter : Option[GlobalParameter],
  onSuccessCallback : (String) => JsCmd = { (String) => Noop },
  onFailureCallback : () => JsCmd = { () => Noop }
) extends DispatchSnippet  with Loggable {
  private[this] val roParameterService = RudderConfig.roParameterService
  private[this] val woParameterService = RudderConfig.woParameterService
  private[this] val uuidGen            = RudderConfig.stringUuidGenerator
  private[this] val userPropertyService= RudderConfig.userPropertyService
  
  def dispatch = {
    case "popupContent" =>  { _ =>   popupContent }
  }
  
  private[this] def onSubmit() : JsCmd = {
    if(formTracker.hasErrors) {
      onFailure
    } else {
      val newParameter = new GlobalParameter(
        name        = ParameterName(parameterName.is),
        value       = parameterValue.is,
        description = parameterDescription.is,
        overridable = parameterOverridable
      )
      parameter match {
        case None => // creation
          woParameterService.saveParameter(newParameter, ModificationId(uuidGen.newUuid), CurrentUser.getActor, paramReasons.map(_.is)) match {
            case Full(x) => closePopup() & onSuccessCallback(parameterName.is)
            case Empty => 
              logger.error("An error occurred while saving the parameter")
              formTracker.addFormError(error("An error occurred while saving the parameter"))
              onFailure
            case Failure(m,_,_) =>
              logger.error("An error occurred while saving the parameter: " + m)
              formTracker.addFormError(error(m))
              onFailure 
          }
        case Some(oldParam) => 
          woParameterService.updateParameter(newParameter, ModificationId(uuidGen.newUuid), CurrentUser.getActor, paramReasons.map(_.is)) match {
            case Full(x) => closePopup() & onSuccessCallback(parameterName.is)
            case Empty => 
              logger.error("An error occurred while updating the parameter")
              formTracker.addFormError(error("An error occurred while updating the parameter"))
              onFailure
            case Failure(m,_,_) =>
              logger.error("An error occurred while updating the parameter: " + m)
              formTracker.addFormError(error(m))
              onFailure 
          }
      }     
    }
  }
  
  private[this] def onFailure: JsCmd = {
    formTracker.addFormError(error("The form contains some errors, please correct them"))
    updateFormClientSide()
  }
  
  private[this] def closePopup() : JsCmd = {
    JsRaw(""" $.modal.close();""")
  }
    
  /**
   * Update the form when something happened
   */
  private[this] def updateFormClientSide() : JsCmd = {
    SetHtml(htmlId_popupContainer, popupContent()) & JsRaw("correctButtons();")
  }
  
  private[this] def updateAndDisplayNotifications(formTracker : FormTracker) : NodeSeq = {
    val notifications = formTracker.formErrors
    formTracker.cleanErrors

    if(notifications.isEmpty) {
      NodeSeq.Empty
    }
    else {
      <div id="notifications" class="notify">
        <ul class="field_errors">{notifications.map( n => <li>{n}</li>) }</ul>
      </div>
    }
  }
  
  ////////////////////////// fields for form ////////////////////////
  private[this] val patternName = Pattern.compile("[a-zA-Z0-9_]+");

  private[this] val parameterName = new WBTextField("Name", parameter.map(_.name.value).getOrElse("")) {
    override def setFilter = notNull _ :: trim _ :: Nil
    override def errorClassName = ""
    override def inputField = (parameter match {
      case Some(entry) => super.inputField % ("disabled" -> "true")
      case None => super.inputField
    }) % ("onkeydown" , "return processKey(event , 'createParameterSaveButton')")  % ("tabindex","1")
    override def validations =
      valMinLen(3, "The name must have at least 3 characters") _ ::
      valRegex(patternName, "The name can contain only letters, digits and underscore") _ :: Nil
  }
  
  // The value may be empty
  private[this] val parameterValue = new WBTextAreaField("Value", parameter.map(_.value).getOrElse("")) {
    override def setFilter = trim _ :: Nil
    override def inputField = super.inputField  % ("style" -> "height:4em")  % ("tabindex","2")
    override def errorClassName = ""
    override def validations = Nil
  }

  private[this] val parameterDescription = new WBTextAreaField("Description", parameter.map(_.description).getOrElse("")) {
    override def setFilter = notNull _ :: trim _ :: Nil
    override def inputField = super.inputField  % ("style" -> "height:3em")  % ("tabindex","3")
    override def errorClassName = ""
    override def validations = Nil
  }
  
  /*private[this] val parameterOverridable = new WBRadioField("Overridable", Seq("true", "false"), "static", {
    case "true" =>
      <span title="This parameter may be overriden by others parameters (tags, nodes)">
        True
      </span>
    case "false" =>
      <span title="This parameter cannot be overriden">False</span>
  },Some(5)) {
    override def setFilter = notNull _ :: trim _ :: Nil
    override def inputField = super.inputField  % ("style" -> "height:3em")
    override def errorClassName = ""
    override def validations = Nil
  }*/
  // default value is true
  val parameterOverridable = true
  
  private[this] val paramReasons = {
    import com.normation.rudder.web.services.ReasonBehavior._
    userPropertyService.reasonsFieldBehavior match {
      case Disabled => None
      case Mandatory => Some(buildReasonField(true, "subContainerReasonField"))
      case Optionnal => Some(buildReasonField(false, "subContainerReasonField"))
    }
  }
  
  def buildReasonField(mandatory:Boolean, containerClass:String = "twoCol") = {
    new WBTextAreaField("Message", "") {
      override def setFilter = notNull _ :: trim _ :: Nil
      override def inputField = super.inputField  %
        ("style" -> "height:5em;")  % ("tabindex","4")
      override def errorClassName = ""
      override def validations() = {
        if(mandatory){
          valMinLen(5, "The reason must have at least 5 characters.") _ :: Nil
        } else {
          Nil
        }
      }
    }
  }
  
  private[this] val formTracker = new FormTracker(parameterName :: parameterValue :: paramReasons.toList)

  private[this] var notifications = List.empty[NodeSeq]

  private[this] def error(msg:String) = <span class="error">{msg}</span>
  
  def popupContent() = {
    (
      "#title *" #> parameter.map(x => "Update global parameter").getOrElse("Create a new global parameter") &
      ".name" #> parameterName.toForm_! &
      ".value" #> parameterValue.toForm_! &
      ".description *" #> parameterDescription.toForm_! &
      ".itemReason *" #> { paramReasons.map { f =>
        <div>
          <div style="margin:10px 0px 5px 0px; color:#444">
            {userPropertyService.reasonsFieldExplanation}
          </div>
          {f.toForm_!}
        </div>
      } } &
      "#cancel"  #> (SHtml.ajaxButton("Cancel", { () => closePopup() })  % ("tabindex","6")) &
      "#save" #> (SHtml.ajaxSubmit(parameter.map(_ => "Update").getOrElse("Save"), onSubmit _) % ("id","createParameterSaveButton")  % ("tabindex","5")) andThen
      ".notifications *"  #> { updateAndDisplayNotifications(formTracker) }
    ).apply(formXml())
  }
    
  private[this] def formXml() : NodeSeq = {
    SHtml.ajaxForm(
    <div id="paramForm">
      <div class="simplemodal-title">
        <h1 id="title">Here comes title</h1>
        <hr/>
      </div>
      <div class="simplemodal-content">
        <div class="notifications">Here comes validation messages</div>
        <hr class="spacer"/>
        <div class="name"/>
        <hr class="spacer"/>
        <div class="value"/>
        <hr class="spacer" />
        <div class="description"/>
        <hr class="spacer" />
        <div class="itemReason"/>
        <hr class="spacer" />
      </div>
      <div class="simplemodal-bottom">
        <hr/>
        <div class="popupButton">
          <span>
            <div id="cancel"/>
            <div id="save"/>
          </span>
        </div>
      </div>
    </div>)
  }
}

object CreateOrUpdateGlobalParameterPopup {
  val htmlId_popupContainer = "createGlobalParameterContainer"
  val htmlId_popup = "createGlobalParameterPopup"
}