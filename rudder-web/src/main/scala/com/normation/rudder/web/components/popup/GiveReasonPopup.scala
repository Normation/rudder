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
import com.normation.rudder.web.services.UserPropertyService
import com.normation.rudder.web.services.ReasonBehavior._
import com.normation.cfclerk.services.TechniqueRepository
import net.liftweb.http.js._
import JsCmds._
import com.normation.utils.StringUuidGenerator
import com.normation.rudder.domain.policies.ActiveTechniqueCategoryId
import com.normation.rudder.domain.policies.ActiveTechniqueCategory
import JE._
import net.liftweb.common._
import net.liftweb.http.{SHtml,S,DispatchSnippet,Templates}
import scala.xml._
import net.liftweb.util.Helpers
import net.liftweb.util.Helpers._
import com.normation.cfclerk.domain.TechniqueName
import com.normation.rudder.web.model.{
  WBTextField, FormTracker, WBTextAreaField,WBSelectField, CurrentUser
}
import com.normation.rudder.repository._
import com.normation.rudder.domain.policies.ActiveTechniqueId
import com.normation.eventlog.ModificationId
import bootstrap.liftweb.RudderConfig


class GiveReasonPopup(
    onSuccessCallback : (ActiveTechniqueId) => JsCmd = { (ActiveTechniqueId) => Noop }
  , onFailureCallback : (String, String) => JsCmd = { (String1, String2) => Noop }
  , refreshActiveTreeLibrary : () => JsCmd = { () => Noop }
  , sourceActiveTechniqueId : ActiveTechniqueId
  , destCatId : ActiveTechniqueCategoryId
) extends DispatchSnippet with Loggable {

 // Load the template from the popup
  def templatePath = List("templates-hidden", "Popup", "giveReason")
  def template() =  Templates(templatePath) match {
     case Empty | Failure(_,_,_) =>
       error("Template for creation popup not found. I was looking for %s.html"
           .format(templatePath.mkString("/")))
     case Full(n) => n
  }
  def popupTemplate = chooseTemplate("reason", "giveReasonPopup", template)


  private[this] val uuidGen                     = RudderConfig.stringUuidGenerator
  private[this] val roActiveTechniqueRepository = RudderConfig.roDirectiveRepository
  private[this] val rwActiveTechniqueRepository = RudderConfig.woDirectiveRepository
  private[this] val userPropertyService         = RudderConfig.userPropertyService
  private[this] val techniqueRepository         = RudderConfig.techniqueRepository

  def dispatch = {
    case "popupContent" => popupContent _
  }

  def initJs : JsCmd = {
    JsRaw("correctButtons();")
  }

  def popupContent(html : NodeSeq) : NodeSeq = {
    SHtml.ajaxForm(bind("item", popupTemplate,
       "reason" -> crReasons.map {f =>
         <div>
          <div style="margin-bottom:5px">
            {userPropertyService.reasonsFieldExplanation}
          </div>
            {f.toForm_!}
         </div>
        },
      "cancel" -> SHtml.ajaxButton("Cancel", { () => closePopup() & refreshActiveTreeLibrary() }) %
        ("tabindex","4"),
      "save" -> SHtml.ajaxSubmit("Save", onSubmit _) %
        ("id","createATCSaveButton") % ("tabindex","3")
    ))
  }


///////////// fields for category settings ///////////////////
  private[this] val crReasons = {
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
        ("style" -> "height:8em;")
      override def subContainerClassName = containerClass
      override def validations() = {
        if(mandatory){
          valMinLen(5, "The reason must have at least 5 characters.") _ :: Nil
        } else {
          Nil
        }
      }
    }
  }

  private[this] val formTracker = {
    val fields = List() ++ crReasons
    new FormTracker(fields)
  }

  private[this] var notifications = List.empty[NodeSeq]

  private[this] def error(msg:String) = <span class="error">{msg}</span>


  private[this] def closePopup() : JsCmd = {
    JsRaw(""" $.modal.close();""")
  }

  /**
   * Update the form when something happened
   */
  private[this] def updateFormClientSide() : JsCmd = {
    SetHtml("createActiveTechniquesContainer", popupContent(NodeSeq.Empty))&
    initJs
  }


  private[this] def onSubmit() : JsCmd = {
    if(formTracker.hasErrors) {
      onFailure & onFailureCallback(sourceActiveTechniqueId.value, destCatId.value)
    } else {
          val ptName = TechniqueName(sourceActiveTechniqueId.value)
          val errorMess = "Error while trying to add Rudder internal " +
          "Technique with requested id '%s' in user library category '%s'"
          (for {
            result <- (
                rwActiveTechniqueRepository
                  .addTechniqueInUserLibrary(
                      ActiveTechniqueCategoryId(destCatId.value),
                      ptName,
                      techniqueRepository.getTechniqueVersions(ptName).toSeq,
                      ModificationId(uuidGen.newUuid),
                      CurrentUser.getActor,
                      crReasons.map (_.is)
                   )
                   ?~! errorMess.format(sourceActiveTechniqueId.value, destCatId.value)
               )
          } yield {
            result
          }) match {
            case Full(res) =>
              val jsString = """setTimeout(function() { $("[activeTechniqueId=%s]")
                .effect("highlight", {}, 2000)}, 100)"""
                formTracker.clean
                closePopup() &
                onSuccessCallback(res.id) &
                JsRaw(jsString.format(sourceActiveTechniqueId.value))
            case f:Failure =>
              Alert(f.messageChain + "\nPlease reload the page")
            case Empty =>
              val errorMess = "Error while trying to move Active Technique with " +
              "requested id '%s' to category id '%s'\nPlease reload the page."
              Alert(errorMess.format(sourceActiveTechniqueId.value, destCatId.value))
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
      val html = <div id="notifications" class="notify">
        <ul>{notifications.map( n => <li>{n}</li>) }</ul></div>
      notifications = Nil
      html
    }
  }
}