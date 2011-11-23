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

import net.liftweb.http.LocalSnippet

import net.liftweb.http.js._
import JsCmds._
import com.normation.utils.StringUuidGenerator
import com.normation.rudder.domain.policies.{UserPolicyTemplateCategory,UserPolicyTemplateCategoryId}

// For implicits
import JE._
import net.liftweb.common._
import net.liftweb.http.{SHtml,S,DispatchSnippet,Templates}
import scala.xml._
import net.liftweb.util.Helpers
import net.liftweb.util.Helpers._


import com.normation.rudder.web.model.{
  WBTextField, FormTracker, WBTextAreaField,WBSelectField,WBRadioField
}
import com.normation.rudder.repository._
import bootstrap.liftweb.LiftSpringApplicationContext.inject


class CreateUserPolicyTemplateCategoryPopup(onSuccessCallback : () => JsCmd = { () => Noop },
  onFailureCallback : () => JsCmd = { () => Noop }
       ) extends DispatchSnippet with Loggable {

 // Load the template from the popup
  def templatePath = List("templates-hidden", "Popup", "createUserPTCategory")
  def template() =  Templates(templatePath) match {
     case Empty | Failure(_,_,_) =>
       error("Template for creation popup not found. I was looking for %s.html".format(templatePath.mkString("/")))
     case Full(n) => n
  }
  def popupTemplate = chooseTemplate("pt", "createCategoryPopup", template)


  private[this] val userPolicyTemplateCategoryRepository = inject[UserPolicyTemplateCategoryRepository]
  private[this] val uuidGen = inject[StringUuidGenerator]

  private[this] val categories = userPolicyTemplateCategoryRepository.getAllUserPolicyTemplateCategories()

  def dispatch = {
    case "popupContent" => popupContent _
  }

  def initJs : JsCmd = {
    JsRaw("correctButtons();")
  }

  def popupContent(html : NodeSeq) : NodeSeq = {
    SHtml.ajaxForm(bind("item", popupTemplate,
      "itemName" -> piName.toForm_!,
      "itemContainer" -> piContainer.toForm_!,
      "itemDescription" -> piDescription.toForm_!,
      "notifications" -> updateAndDisplayNotifications(),
      "cancel" -> SHtml.ajaxButton("Cancel", { () => closePopup() }) % ("tabindex","4"),
      "save" -> SHtml.ajaxSubmit("Save", onSubmit _) % ("id","createUPTCSaveButton") % ("tabindex","3")
    ))
  }

///////////// fields for category settings ///////////////////
  private[this] val piName = new WBTextField("Name: ", "") {
    override def displayNameHtml = Some(<b>{displayName}</b>)
    override def setFilter = notNull _ :: trim _ :: Nil
    override def className = "twoCol"
    override def errorClassName = ""
    override def inputField = super.inputField % ("onkeydown" , "return processKey(event , 'createUPTCSaveButton')") % ("tabindex","1")
    override def validations =
      valMinLen(3, "The name must have at least 3 characters") _ :: Nil
  }

  private[this] val piDescription = new WBTextAreaField("Description: ", "") {
    override def setFilter = notNull _ :: trim _ :: Nil
    override def inputField = super.inputField  % ("style" -> "height:10em") % ("tabindex","2")
    override def className = "twoCol"
    override def errorClassName = ""
    override def validations = Nil

  }

  private[this] val piContainer = new WBSelectField("Parent category: ",
			(categories.open_!.map(x => (x.id.value -> x.name))),
			"") {
    override def className = "twoCol"
  }

  private[this] val formTracker = new FormTracker(piName,piDescription,piContainer)

  private[this] var notifications = List.empty[NodeSeq]

  private[this] def error(msg:String) = <span class="error">{msg}</span>


  private[this] def closePopup() : JsCmd = {
    JsRaw(""" $.modal.close();""")
  }
  /**
   * Update the form when something happened
   */
	private[this] def updateFormClientSide() : JsCmd = {
    SetHtml("createPTCategoryContainer", popupContent(NodeSeq.Empty))&
	  initJs
  }


  private[this] def onSubmit() : JsCmd = {
    if(formTracker.hasErrors) {
      onFailure & onFailureCallback()
    } else {
      // First retrieve the parent category
      userPolicyTemplateCategoryRepository.getUserPolicyTemplateCategory(new UserPolicyTemplateCategoryId(piContainer.is)) match {
        case Empty =>
            logger.error("An error occurred while fetching the parent category")
            formTracker.addFormError(error("An error occurred while fetching the parent category"))
            onFailure & onFailureCallback()
        case Failure(m,_,_) =>
            logger.error("An error occurred while fetching the parent category:" + m)
            formTracker.addFormError(error("An error occurred while fetching the parent category: " + m))
            onFailure & onFailureCallback()
        case Full(parent) =>
          userPolicyTemplateCategoryRepository.addUserPolicyTemplateCategory(
              new UserPolicyTemplateCategory(
                 UserPolicyTemplateCategoryId(uuidGen.newUuid),
                 name = piName.is,
                 description = piDescription.is,
                 children = Nil,
                 items = Nil
               ),
               parent
             ) match {
               case Failure(m,_,_) =>
                  logger.error("An error occurred while saving the category:" + m)
                  formTracker.addFormError(error("An error occurred while saving the category:" + m))
                  onFailure & onFailureCallback()
               case Empty =>
                  logger.error("An error occurred while saving the category")
                  formTracker.addFormError(error("An error occurred while saving the category"))
                  onFailure & onFailureCallback()
               case Full(updatedParent) =>
                 formTracker.clean
                 closePopup() & onSuccessCallback()
             }
           }
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