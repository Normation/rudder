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
import JE._
import net.liftweb.common._
import net.liftweb.http.{SHtml,DispatchSnippet}
import scala.xml._
import net.liftweb.util.Helpers._
import com.normation.rudder.web.model.{
  WBTextField, FormTracker, WBTextAreaField, WBSelectField
}
import com.normation.rudder.web.model.CurrentUser
import bootstrap.liftweb.RudderConfig
import com.normation.rudder.rule.category.RuleCategory
import com.normation.rudder.rule.category.RuleCategory
import com.normation.rudder.rule.category.RuleCategoryId
import com.normation.eventlog.ModificationId
import com.normation.rudder.rule.category.RuleCategory
import com.normation.rudder.web.ChooseTemplate

import com.normation.box._

/**
 * Create a group or a category
 * This is a popup that allows for the creation of a group or a category, or
 * if a group is passed as an argument, will force the creation of a new group based on the query
 * contained
 */
class RuleCategoryPopup(
    rootCategory      : RuleCategory
  , targetCategory    : Option[RuleCategory]
  , selectedCategory  : RuleCategoryId
  , onSuccessCategory : (RuleCategory) => JsCmd
  , onSuccessCallback : (String) => JsCmd = { _ => Noop }
  , onFailureCallback : () => JsCmd = { () => Noop }
) extends DispatchSnippet with Loggable {

  // Load the template from the popup
  private def html = ChooseTemplate(
      "templates-hidden" :: "Popup" :: "RuleCategoryPopup" :: Nil
    , "component-creationpopup"
  )

  private def deleteHtml = ChooseTemplate(
      "templates-hidden" :: "Popup" :: "RuleCategoryPopup" :: Nil
    , "component-deletepopup"
  )

  private[this] val woRulecategoryRepository   = RudderConfig.woRuleCategoryRepository
  private[this] val categoryHierarchyDisplayer = RudderConfig.categoryHierarchyDisplayer
  private[this] val uuidGen                    = RudderConfig.stringUuidGenerator

  def dispatch = {
    case "popupContent" => { _ => popupContent }
  }

  val title = {
    targetCategory match {
      case None => "Create new Rule category"
      case Some(c) => s"Update category '${c.name}'"
    }
  }
  val TextForButton = {
    targetCategory match {
      case None => "Create"
      case Some(c) => s"Update"
    }
  }

  val parentCategory = targetCategory.flatMap(rootCategory.findParent(_)).map(_.id.value)
    def popupContent() : NodeSeq = {
     (

      "#creationForm *"        #> { (xml:NodeSeq) => SHtml.ajaxForm(xml) } andThen
      "#dialogTitle *"         #> title &
      "#categoryName * "       #> categoryName.toForm_! &
      "#categoryParent *"      #> categoryParent.toForm_! &
      "#categoryDescription *" #> categoryDescription.toForm_! &
      "#categoryId *"          #> (targetCategory match {
                                    case None => NodeSeq.Empty
                                    case Some(c) =>
                                      <div class="row form-group">
                                        <label class="col-lg-3 col-sm-12 col-xs-12 text-right wbBaseFieldLabel"><b>Rudder ID</b>
                                        </label>
                                        <div class="col-lg-9 col-sm-12 col-xs-12">
                                          <input class="form-control col-lg-12 col-sm-12 col-xs-12" type="text" value={c.id.value} disabled="true">
                                          </input>
                                        </div>
                                        </div>
                                  }) &
      "#saveCategory"          #> SHtml.ajaxSubmit(TextForButton, () => onSubmit(), ("id", "createRuleCategorySaveButton") , ("tabindex","5"), ("style","margin-left:5px;")) andThen
      ".notifications *"       #> updateAndDisplayNotifications()

    )(html)
  }

  def deletePopupContent(canBeDeleted : Boolean) : NodeSeq = {
    val action = () => if (canBeDeleted)  onSubmitDelete else closePopup
    val disabled  = if (canBeDeleted) ("","") else ("disabled","true")
     (
      "#dialogTitle *"  #> s"Delete Rule category ${s"'${targetCategory.map(_.name).getOrElse("")}'"}" &
      "#text * "        #> (if(canBeDeleted) "Are you sure you want to delete this Rule category?" else "This Rule category is not empty and therefore cannot be deleted")  &
      "#deleteCategoryButton" #> SHtml.ajaxButton("Delete", action, ("id", "createRuleCategorySaveButton") ,("tabindex","1") , ("class","btn-danger"), disabled )
    )(deleteHtml)
  }

  ///////////// fields for category settings ///////////////////

    private[this] val categoryName = new WBTextField("Name", targetCategory.map(_.name).getOrElse("")) {
    override def setFilter = notNull _ :: trim _ :: Nil
    override def subContainerClassName = "col-lg-9 col-sm-12 col-xs-12"
    override def errorClassName = "col-lg-12 errors-container"
    override def inputField = super.inputField %("onkeydown" -> "return processKey(event , 'createRuleCategorySaveButton')") % ("tabindex" -> "2")
    override def validations =
      valMinLen(1, "The name must not be empty.") _ :: Nil
  }

  private[this] val categoryDescription = new WBTextAreaField("Description", targetCategory.map(_.description).getOrElse("")) {
    override def subContainerClassName = "col-lg-9 col-sm-12 col-xs-12"
    override def setFilter = notNull _ :: trim _ :: Nil
    override def inputField = super.inputField  % ("tabindex" -> "4")
    override def errorClassName = "col-lg-12 errors-container"
    override def validations =  Nil

  }

  val categories = targetCategory.map(rootCategory.filter(_)).getOrElse(rootCategory)

  private[this] val categoryParent =
    new WBSelectField(
        "Parent"
      , categoryHierarchyDisplayer.getRuleCategoryHierarchy(categories, None).map { case (id, name) => (id.value -> name)}
      , parentCategory.getOrElse(selectedCategory.value)
    ) {
    override def subContainerClassName = "col-lg-9 col-sm-12 col-xs-12"
    override def inputField = super.inputField % ("tabindex" -> "3")
    override def className = "col-lg-12 col-sm-12 col-xs-12 form-control"
    override def validations =
      valMinLen(1, "Please select a category") _ :: Nil
  }

  private[this] val formTracker = new FormTracker(categoryName, categoryDescription, categoryParent)

  private[this] var notifications = List.empty[NodeSeq]

  private[this] def error(msg:String) = <span class="col-lg-12 errors-container">{msg}</span>

  private[this] def closePopup() : JsCmd = {
    JsRaw(""" $('#createRuleCategoryPopup').bsModal('hide');""")
  }
  /**
   * Update the form when something happened
   */
  private[this] def updateFormClientSide() : JsCmd = {
    SetHtml("RuleCategoryCreationContainer", popupContent())
  }

  private[this] def onSubmitDelete() : JsCmd = {
    targetCategory match {
      case Some(category) =>
        val modId = new ModificationId(uuidGen.newUuid)
        woRulecategoryRepository.delete(category.id, modId , CurrentUser.actor, None, true).toBox match {
          case Full(x) =>
            closePopup() &
            onSuccessCallback(x.value) &
            onSuccessCategory(category)
          case eb:EmptyBox =>
            val fail = eb ?~! s"An error occurred while deleting category '${category.name}'"
            logger.error(s"An error occurred while deleting the category: ${fail.msg}")
            formTracker.addFormError(error(fail.msg))
            onFailure & onFailureCallback()
        }
      case None =>
        // Should not happen, close popup
        closePopup()
    }
  }

  private[this] def onSubmit() : JsCmd = {

    if(formTracker.hasErrors) {
      onFailure & onFailureCallback()
    } else {
      (targetCategory match {
        case None =>

          val newCategory = RuleCategory(
                                RuleCategoryId(uuidGen.newUuid)
                              , categoryName.get
                              , categoryDescription.get
                              , Nil
                              , false
                            )

          val parent = RuleCategoryId(categoryParent.get)
          val modId = new ModificationId(uuidGen.newUuid)
          woRulecategoryRepository.create(newCategory, parent,modId , CurrentUser.actor, None).toBox
        case Some(category) =>
          val updated = category.copy(
                            name        =  categoryName.get
                          , description = categoryDescription.get
                        )
          val parent = RuleCategoryId(categoryParent.get)

          if (updated == category && parentCategory.exists(_ == parent.value)) {
            Failure("There are no modifications to save")
          } else {
            val modId = new ModificationId(uuidGen.newUuid)
            woRulecategoryRepository.updateAndMove(updated, parent,modId , CurrentUser.actor, None).toBox
          }
      }) match {
          case Full(x) => closePopup() & onSuccessCallback(x.id.value) & onSuccessCategory(x)
          case Empty =>
            logger.error("An error occurred while saving the category")
            formTracker.addFormError(error("An error occurred while saving the category"))
            onFailure & onFailureCallback()
          case Failure(m,_,_) =>
            logger.error("An error occurred while saving the category:" + m)
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
      val html = <div id="notifications" class="alert alert-danger text-center col-lg-12 col-xs-12 col-sm-12" role="alert"><ul class="text-danger">{notifications.map( n => <li>{n}</li>) }</ul></div>
      notifications = Nil
      html
    }
  }
}
