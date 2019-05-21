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
import com.normation.rudder.domain.policies.{Rule,RuleId}
import JE._
import net.liftweb.common._
import net.liftweb.http.{SHtml,DispatchSnippet,Templates}
import scala.xml._
import net.liftweb.util.Helpers._
import com.normation.rudder.web.model.{
  WBTextField, FormTracker, WBTextAreaField
}
import CreateOrCloneRulePopup._
import com.normation.rudder.web.model.CurrentUser
import com.normation.eventlog.ModificationId
import bootstrap.liftweb.RudderConfig
import com.normation.rudder.rule.category.RuleCategoryId
import com.normation.rudder.web.model.WBSelectField
import com.normation.rudder.rule.category.RuleCategory
import com.normation.rudder.web.ChooseTemplate

import com.normation.box._

class CreateOrCloneRulePopup(
    rootRuleCategory  : RuleCategory
  , clonedRule        : Option[Rule]
  , selectedCategory  : RuleCategoryId
  , onSuccessCallback : (Rule) => JsCmd = { (rule : Rule) => Noop }
  , onFailureCallback : () => JsCmd = { () => Noop }
) extends DispatchSnippet with Loggable {

  // Load the template from the popup
  def templatePath = List("templates-hidden", "Popup", "createRule")
  def template() =  Templates(templatePath) match {
     case Empty | Failure(_,_,_) =>
       error("Template for creation popup not found. I was looking for %s.html".format(templatePath.mkString("/")))
     case Full(n) => n
  }

  def popupTemplate = ChooseTemplate(
      List("templates-hidden", "Popup", "createRule")
    , "rule-createrulepopup"
  )

  private[this] val woRuleRepository    = RudderConfig.woRuleRepository
  private[this] val uuidGen             = RudderConfig.stringUuidGenerator
  private[this] val userPropertyService = RudderConfig.userPropertyService
  private[this] val categoryHierarchyDisplayer = RudderConfig.categoryHierarchyDisplayer

  def dispatch = {
    case "popupContent" => _ => popupContent()
  }

  def popupContent() : NodeSeq = {

    SHtml.ajaxForm( (
      "item-title" #> (if(clonedRule.isDefined) "Clone a rule" else "Create a new rule")
    & "item-itemname" #> ruleName.toForm_!
    & "item-category" #> category.toForm_!
    & "item-itemshortdescription" #> ruleShortDescription.toForm_!
    & "item-itemreason" #> { reason.map { f =>
        <div>
            <h4 class="col-lg-12 col-sm-12 col-xs-12 audit-title">Change Audit Log</h4>
            {f.toForm_!}
        </div>

      } }
    & "item-clonenotice" #> {
        if(clonedRule.isDefined)
            <hr class="css-fix"/>
            <div class="alert alert-info text-center">
                <span class="glyphicon glyphicon-info-sign"></span>
                The new rule will be disabled.
            </div>
        else
          NodeSeq.Empty
      }
    & "item-cancel" #> (SHtml.ajaxButton("Cancel", { () => closePopup() }) % ("tabindex" -> "5") % ("class" -> "btn btn-default"))
    & "item-save" #> (SHtml.ajaxSubmit(if(clonedRule.isDefined) "Clone" else "Create", onSubmit _) % ("id" -> "createCRSaveButton") % ("tabindex" -> "4") % ("class" -> "btn btn-success"))
    andThen
      "item-notifications" #> updateAndDisplayNotifications()
    )(popupTemplate) )
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

  private[this] val ruleName = new WBTextField("Name", clonedRule.map(r => "Copy of <%s>".format(r.name)).getOrElse("")) {
    override def setFilter = notNull _ :: trim _ :: Nil
    override def errorClassName = "col-lg-12 errors-container"
    override def inputField = super.inputField % ("onkeydown" -> "return processKey(event , 'createCRSaveButton')") % ("tabindex" -> "1")
    override def validations =
      valMinLen(1, "Name must not be empty") _ :: Nil
  }

  private[this] val ruleShortDescription = new WBTextAreaField("Description", clonedRule.map( _.shortDescription).getOrElse("")) {
    override def setFilter = notNull _ :: trim _ :: Nil
    override def inputField = super.inputField  % ("style" -> "height:7em") % ("tabindex" -> "2")
    override def errorClassName = "col-lg-12 errors-container"
    override def validations = Nil

  }

  private[this] val category =
    new WBSelectField(
        "Category"
      , categoryHierarchyDisplayer.getRuleCategoryHierarchy(rootRuleCategory, None).map { case (id, name) => (id.value -> name)}
      , selectedCategory.value
    ) {
      override def className = "form-control col-lg-12 col-sm-12 col-xs-12"
      override def validations =
        valMinLen(1, "Please select a category") _ :: Nil
    }

  private[this] val formTracker = new FormTracker(ruleName :: ruleShortDescription :: reason.toList)

  private[this] var notifications = List.empty[NodeSeq]

  private[this] def error(msg:String) = <span class="col-lg-12 errors-container">{msg}</span>

  private[this] def closePopup() : JsCmd = {
    JsRaw("""$('#createRulePopup').bsModal('hide');""")
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

      val rule =
        Rule(
            RuleId(uuidGen.newUuid)
          , ruleName.get
          , RuleCategoryId(category.get)
          , targets = clonedRule.map( _.targets).getOrElse(Set())
          , directiveIds = clonedRule.map( _.directiveIds).getOrElse(Set())
          , shortDescription = ruleShortDescription.get
          , longDescription = clonedRule.map( _.longDescription ).getOrElse("")
          , isEnabledStatus = !clonedRule.isDefined
      )

      val createRule = woRuleRepository.create(rule, ModificationId(uuidGen.newUuid),CurrentUser.actor, reason.map( _.get )).toBox match {
          case Full(x) =>
            onSuccessCallback(rule) & closePopup()
          case Empty =>
            logger.error("An error occurred while saving the Rule")
            formTracker.addFormError(error("An error occurred while saving the Rule"))
            onFailure & onFailureCallback()
          case Failure(m,_,_) =>
            logger.error("An error occurred while saving the Rule:" + m)
            formTracker.addFormError(error(m))
            onFailure & onFailureCallback()
      }
      createRule & JsRaw(s"""localStorage.setItem('Active_Rule_Tab', 0)""")
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
      val html = <div id="notifications" class="alert alert-danger text-center col-lg-12 col-xs-12 col-sm-12" role="alert"><ul class="text-danger">{notifications.map( n => <li>{n}</li>) }</ul></div>
      notifications = Nil
      html
    }
  }
}

object CreateOrCloneRulePopup {
  val htmlId_popupContainer = "createRuleContainer"
  val htmlId_popup = "createRulePopup"
}
