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

import net.liftweb.http.js._
import JsCmds._
import com.normation.rudder.domain.policies.{ActiveTechniqueCategory,ActiveTechniqueCategoryId}
import JE._
import net.liftweb.common._
import net.liftweb.http.{SHtml,DispatchSnippet}
import scala.xml._
import net.liftweb.util.Helpers._
import com.normation.rudder.web.model.{
  WBTextField, FormTracker, WBTextAreaField,WBSelectField, CurrentUser
}
import com.normation.eventlog.ModificationId
import bootstrap.liftweb.RudderConfig
import com.normation.rudder.web.ChooseTemplate

import com.normation.box._

class CreateActiveTechniqueCategoryPopup(onSuccessCallback : () => JsCmd = { () => Noop },
  onFailureCallback : () => JsCmd = { () => Noop }
       ) extends DispatchSnippet with Loggable {

 // Load the template from the popup
  def popupTemplate = ChooseTemplate(
      "templates-hidden" :: "Popup" :: "createActiveTechniqueCategory" :: Nil
    , "technique-createcategorypopup"
  )

  private[this] val activeTechniqueCategoryRepository   = RudderConfig.roDirectiveRepository
  private[this] val rwActiveTechniqueCategoryRepository = RudderConfig.woDirectiveRepository
  private[this] val uuidGen                             = RudderConfig.stringUuidGenerator

  private[this] val categories = activeTechniqueCategoryRepository.getAllActiveTechniqueCategories().toBox

  def dispatch = {
    case "popupContent" => popupContent _
  }

  def popupContent(html : NodeSeq) : NodeSeq = {
    SHtml.ajaxForm(
      (
          "item-itemname" #> categoryName.toForm_!
        & "item-itemcontainer" #> categoryContainer.toForm_!
        & "item-itemdescription" #> categoryDescription.toForm_!
        & "item-cancel" #> ( SHtml.ajaxButton("Cancel", { () => closePopup() }) % ("tabindex" -> "4") % ("class" -> "btn btn-default") )
        & "item-save" #> ( SHtml.ajaxSubmit("Save", onSubmit _) % ("id" -> "createATCSaveButton") % ("tabindex" -> "3") % ("class" -> "btn btn-success") )
        andThen
          "item-notifications" #> updateAndDisplayNotifications()
      )(popupTemplate)
    )
  }

///////////// fields for category settings ///////////////////
  private[this] val categoryName = new WBTextField("Name", "") {
    override def setFilter = notNull _ :: trim _ :: Nil
    override def errorClassName = "col-lg-12 errors-container"
    override def inputField = super.inputField % ("onkeydown" -> "return processKey(event , 'createATCSaveButton')") % ("tabindex" -> "1")
    override def validations =
      valMinLen(1, "Name must not be empty") _ :: Nil
  }

  private[this] val categoryDescription = new WBTextAreaField("Description", "") {
    override def setFilter = notNull _ :: trim _ :: Nil
    override def inputField = super.inputField  % ("class" -> "form-control col-lg-12 col-sm-12 col-xs-12") % ("tabindex" -> "2")
    override def errorClassName = "col-lg-12 errors-container"
    override def validations = Nil

  }

  private[this] val categoryContainer = new WBSelectField("Parent category: ",
      (categories.getOrElse(Seq()).map(x => (x.id.value -> x.name))),
      "") {
    override def className = "form-control col-lg-12 col-sm-12 col-xs-12"
      override def validations =
      valMinLen(1, "Please select a category") _ :: Nil
  }

  private[this] val formTracker = new FormTracker(categoryName,categoryDescription,categoryContainer)

  private[this] var notifications = List.empty[NodeSeq]

  private[this] def error(msg:String) = Text(msg)

  private[this] def closePopup() : JsCmd = {
    JsRaw(""" $('#createActiveTechniqueCategoryPopup').bsModal('hide');""")
  }
  /**
   * Update the form when something happened
   */
  private[this] def updateFormClientSide() : JsCmd = {
    SetHtml("createActiveTechniquesCategoryContainer", popupContent(NodeSeq.Empty))
  }

  private[this] def onSubmit() : JsCmd = {
    if(formTracker.hasErrors) {
      onFailure & onFailureCallback()
    } else {
      rwActiveTechniqueCategoryRepository.addActiveTechniqueCategory(
          new ActiveTechniqueCategory(
            ActiveTechniqueCategoryId(uuidGen.newUuid),
            name = categoryName.get,
            description = categoryDescription.get,
            children = Nil,
            items = Nil
          )
         , ActiveTechniqueCategoryId(categoryContainer.get)
         , ModificationId(uuidGen.newUuid)
         , CurrentUser.actor
         , Some("user created a new category")
      ).toBox match {
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
