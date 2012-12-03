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
import com.normation.utils.StringUuidGenerator
import com.normation.rudder.domain.policies.{Rule,RuleId}

// For implicits
import JE._
import net.liftweb.common._
import net.liftweb.http.{SHtml,DispatchSnippet,Templates}
import scala.xml._
import net.liftweb.util.Helpers._

import com.normation.rudder.web.model.{
  WBTextField, FormTracker, WBTextAreaField
}
import com.normation.rudder.repository._
import bootstrap.liftweb.LiftSpringApplicationContext.inject
import CreateOrCloneRulePopup._
import com.normation.rudder.domain.eventlog.AddRule
import com.normation.rudder.web.model.CurrentUser
import com.normation.rudder.web.services.UserPropertyService

class CreateOrCloneRulePopup(
  clonedRule: Option[Rule],
  onSuccessCallback : (Rule) => JsCmd = { (rule : Rule) => Noop },
  onFailureCallback : () => JsCmd = { () => Noop }
       ) extends DispatchSnippet with Loggable {

  // Load the template from the popup
  def templatePath = List("templates-hidden", "Popup", "createRule")
  def template() =  Templates(templatePath) match {
     case Empty | Failure(_,_,_) =>
       error("Template for creation popup not found. I was looking for %s.html".format(templatePath.mkString("/")))
     case Full(n) => n
  }
  def popupTemplate = chooseTemplate("rule", "createRulePopup", template)


  private[this] val ruleRepository = inject[RuleRepository]
  private[this] val uuidGen = inject[StringUuidGenerator]
  private[this] val userPropertyService = inject[UserPropertyService]

  def dispatch = {
    case "popupContent" => _ => popupContent()
  }

  def initJs : JsCmd = {
      JsRaw("correctButtons();")
  }

  def popupContent() : NodeSeq = {

    SHtml.ajaxForm(bind("item", popupTemplate,
      "title" -> { if(clonedRule.isDefined) "Clone a rule" else "Create a new rule" },
      "itemName" -> ruleName.toForm_!,
      "itemShortDescription" -> ruleShortDescription.toForm_!,
      "itemReason" -> { reason.map { f =>
        <div> 
          <div style="margin:10px 0px 5px 0px; color:#444">
            {userPropertyService.reasonsFieldExplanation}
          </div> 
          {f.toForm_!}
        </div>
      } },
      "cloneNotice" -> { if(clonedRule.isDefined) 
                           <span style="margin:10px 0px 5px 0px; color:#444">The cloned rule will be created disabled.</span> 
                         else NodeSeq.Empty 
      },
      "notifications" -> updateAndDisplayNotifications(),
      "cancel" -> SHtml.ajaxButton("Cancel", { () => closePopup() }) % ("tabindex","5"),
      "save" -> SHtml.ajaxSubmit(if(clonedRule.isDefined) "Clone" else "Save", onSubmit _) % ("id","createCRSaveButton") % ("tabindex","4")

    ))
  }

  ///////////// fields for category settings ///////////////////
  
  private[this] val reason = {
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
      override def inputField = super.inputField  % ("style" -> "height:5em;") % ("tabindex" -> "3")
      override def errorClassName = ""
      override def validations() = {
        if(mandatory){
          valMinLen(5, "The reasons must have at least 5 characters.") _ :: Nil
        } else {
          Nil
        }
      }
    }
  }

  private[this] val ruleName = new WBTextField("Name", clonedRule.map(r => "Copy of <%s>".format(r.name)).getOrElse("")) {
    override def setFilter = notNull _ :: trim _ :: Nil
    override def errorClassName = ""
    override def inputField = super.inputField % ("onkeydown" , "return processKey(event , 'createCRSaveButton')") % ("tabindex","1")
    override def validations =
      valMinLen(3, "The name must have at least 3 characters") _ :: Nil
  }

  private[this] val ruleShortDescription = new WBTextAreaField("Short description", clonedRule.map( _.shortDescription).getOrElse("")) {
    override def setFilter = notNull _ :: trim _ :: Nil
    override def inputField = super.inputField  % ("style" -> "height:7em") % ("tabindex","2")
    override def errorClassName = ""
    override def validations = Nil

  }

  private[this] val formTracker = new FormTracker(ruleName :: ruleShortDescription :: reason.toList)

  private[this] var notifications = List.empty[NodeSeq]

  private[this] def error(msg:String) = <span class="error">{msg}</span>


  private[this] def closePopup() : JsCmd = {
    JsRaw(""" $.modal.close();""")
  }
  /**
   * Update the form when something happened
   */
  private[this] def updateFormClientSide() : JsCmd = {
    SetHtml(htmlId_popupContainer, popupContent()) &
    initJs
  }

  private[this] def onSubmit() : JsCmd = {
    if(formTracker.hasErrors) {
      onFailure & onFailureCallback()
    } else {

      val rule = Rule(
          id = RuleId(uuidGen.newUuid),
          name = ruleName.is,
          serial = 0,
          targets = clonedRule.map( _.targets).getOrElse(Set()),
          directiveIds = clonedRule.map( _.directiveIds).getOrElse(Set()),
          shortDescription = ruleShortDescription.is,
          longDescription = clonedRule.map( _.longDescription ).getOrElse(""),
          isEnabledStatus = !clonedRule.isDefined
      )


      ruleRepository.create(rule, CurrentUser.getActor, reason.map( _.is )) match {
          case Full(x) => 
            closePopup() & onSuccessCallback(rule)
          case Empty =>
            logger.error("An error occurred while saving the Rule")
            formTracker.addFormError(error("An error occurred while saving the Rule"))
            onFailure & onFailureCallback()
          case Failure(m,_,_) =>
            logger.error("An error occurred while saving the Rule:" + m)
            formTracker.addFormError(error(m))
            onFailure & onFailureCallback()
      }
    }
  }


  private[this] def onFailure : JsCmd = {
    updateFormClientSide() 
  }


  private[this] def updateAndDisplayNotifications() : NodeSeq = {
    notifications :::= formTracker.formErrors
    formTracker.cleanErrors

    if(notifications.isEmpty) NodeSeq.Empty
    else {
      val html = <div id="notifications" class="notify"><ul>{notifications.map( n => <li>{n}</li>) }</ul></div>
      notifications = Nil
      html
    }
  }
}

object CreateOrCloneRulePopup {
  val htmlId_popupContainer = "createRuleContainer"
  val htmlId_popup = "createRulePopup"
}
