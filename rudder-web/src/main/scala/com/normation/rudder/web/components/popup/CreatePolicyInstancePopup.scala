/*
*************************************************************************************
* Copyright 2011 Normation SAS
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

import net.liftweb.http.js._
import JsCmds._
import com.normation.rudder.domain.policies._
import JE._
import net.liftweb.common._
import net.liftweb.http.{SHtml,DispatchSnippet,Templates}
import scala.xml._
import net.liftweb.util.Helpers._
import com.normation.utils.StringUuidGenerator
import com.normation.rudder.web.model.{
  WBTextField, FormTracker, WBTextAreaField
}
import bootstrap.liftweb.LiftSpringApplicationContext.inject
import CreatePolicyInstancePopup._
import com.normation.cfclerk.services.PolicyPackageService
import com.normation.cfclerk.domain.{PolicyVersion,PolicyPackageName}



object CreatePolicyInstancePopup {
  val htmlId_popupContainer = "createPiContainer"
  val htmlId_popup = "createPiPopup"
    
  val html =  SHtml.ajaxForm(
  <div id="createPiContainer">
    <div class="simplemodal-title">
      <h1>Create a new policy based on <span id="ptName">TEMPLATE NAME</span></h1>
      <hr/>
    </div>
    <div class="simplemodal-content">
      <hr class="spacer"/>
      <div id="notifications">Here comes validation messages</div>
      <hr class="spacer"/>
      <div id="itemName">Here come the policy name</div>
      <hr class="spacer"/>
      <div id="itemDescription">Here come the short description</div>
      <hr class="spacer"/>
    </div>
    <div class="simplemodal-bottom">
      <hr/>
      <div class="popupButton">
        <span>
          <button id="cancel" class="simplemodal-close">Cancel</button>
          <button id="save">Configure</button>
        </span>
     </div>
   </div>
  </div>)
}


class CreatePolicyInstancePopup(
  ptName:String,
  ptDescription:String,
  ptVersion:PolicyVersion,
  onSuccessCallback : (PolicyInstance) => JsCmd = { (pi : PolicyInstance) => Noop },
  onFailureCallback : () => JsCmd = { () => Noop }
) extends DispatchSnippet with Loggable {
  
  private[this] val uuidGen = inject[StringUuidGenerator]

  def dispatch = {
    case "popupContent" => { _ => popupContent }
  }


  def popupContent() : NodeSeq = {

    (
      "#ptName" #> ptName &
      "#itemName" #> piName.toForm_! &
      "#itemDescription" #> piShortDescription.toForm_! &
      "#notifications" #> updateAndDisplayNotifications() &
      "#cancel" #> SHtml.ajaxButton("Cancel", { () => closePopup() }) &
      "#save" #> SHtml.ajaxSubmit("Configure", onSubmit _)
    )(html ++ Script(OnLoad(JsRaw("correctButtons();"))))
    
  }

	///////////// fields for category settings ///////////////////
  private[this] val piName = new WBTextField("Name: ", "") {
    override def displayNameHtml = Some(<b>{displayName}</b>)
    override def setFilter = notNull _ :: trim _ :: Nil
    override def className = "twoCol"
    override def errorClassName = ""
    override def validations =
      valMinLen(3, "The name must have at least 3 characters") _ :: Nil
  }

  private[this] val piShortDescription = new WBTextAreaField("Short description: ", "") {
    override def setFilter = notNull _ :: trim _ :: Nil
    override def inputField = super.inputField  % ("style" -> "height:7em")
    override def className = "twoCol"
    override def errorClassName = ""
    override def validations = Nil

  }

  private[this] val formTracker = new FormTracker(piName,piShortDescription)

  private[this] var notifications = List.empty[NodeSeq]

  private[this] def error(msg:String) = <span class="error">{msg}</span>


  private[this] def closePopup() : JsCmd = {
    JsRaw(""" $.modal.close();""")
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
      val pi = new PolicyInstance(
        id = PolicyInstanceId(uuidGen.newUuid),
        policyTemplateVersion = ptVersion,
        parameters = Map(),
        name = piName.is,
        shortDescription = piShortDescription.is,
        isActivated = true
      )

      closePopup() & onSuccessCallback(pi)
    }
  }

  private[this] def onFailure : JsCmd = {
    formTracker.addFormError(error("The form contains some errors, please correct them"))
    updateFormClientSide() 
  }


  private[this] def updateAndDisplayNotifications() : NodeSeq = {
    notifications :::= formTracker.formErrors
    formTracker.cleanErrors

    if(notifications.isEmpty) NodeSeq.Empty
    else {
      val html = <div id="errorNotification" class="notify"><ul>{notifications.map( n => <li>{n}</li>) }</ul></div>
      notifications = Nil
      html
    }
  }
}


