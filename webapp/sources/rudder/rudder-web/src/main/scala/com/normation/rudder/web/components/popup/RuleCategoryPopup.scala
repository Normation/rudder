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
import com.normation.box.*
import com.normation.eventlog.ModificationId
import com.normation.rudder.facts.nodes.QueryContext
import com.normation.rudder.rule.category.RuleCategory
import com.normation.rudder.rule.category.RuleCategoryId
import com.normation.rudder.web.ChooseTemplate
import com.normation.rudder.web.model.FormTracker
import com.normation.rudder.web.model.WBSelectField
import com.normation.rudder.web.model.WBTextAreaField
import com.normation.rudder.web.model.WBTextField
import net.liftweb.common.*
import net.liftweb.http.SecureDispatchSnippet
import net.liftweb.http.SHtml
import net.liftweb.http.js.*
import net.liftweb.http.js.JE.*
import net.liftweb.http.js.JsCmds.*
import net.liftweb.util.FieldError
import net.liftweb.util.Helpers.*
import scala.xml.*

/**
 * Create a group or a category
 * This is a popup that allows for the creation of a group or a category, or
 * if a group is passed as an argument, will force the creation of a new group based on the query
 * contained
 */
class RuleCategoryPopup(
    rootCategory:      RuleCategory,
    targetCategory:    Option[RuleCategory],
    selectedCategory:  RuleCategoryId,
    onSuccessCategory: (RuleCategory) => JsCmd,
    onSuccessCallback: (String) => JsCmd = { _ => Noop },
    onFailureCallback: () => JsCmd = { () => Noop }
) extends SecureDispatchSnippet with Loggable {

  // Load the template from the popup
  private def html: NodeSeq = ChooseTemplate(
    "templates-hidden" :: "Popup" :: "RuleCategoryPopup" :: Nil,
    "component-creationpopup"
  )

  private def deleteHtml: NodeSeq = ChooseTemplate(
    "templates-hidden" :: "Popup" :: "RuleCategoryPopup" :: Nil,
    "component-deletepopup"
  )

  private val woRulecategoryRepository   = RudderConfig.woRuleCategoryRepository
  private val categoryHierarchyDisplayer = RudderConfig.categoryHierarchyDisplayer
  private val uuidGen                    = RudderConfig.stringUuidGenerator

  def secureDispatch: QueryContext ?=> PartialFunction[String, NodeSeq => NodeSeq] = {
    case "popupContent" => { _ => popupContent() }
  }

  val title:         String = {
    targetCategory match {
      case None    => "Create new Rule category"
      case Some(c) => s"Update category '${c.name}'"
    }
  }
  val TextForButton: String = {
    targetCategory match {
      case None    => "Create"
      case Some(c) => s"Update"
    }
  }

  val parentCategory:                         Option[String] = targetCategory.flatMap(rootCategory.findParent(_)).map(_.id.value)
  def popupContent()(using qc: QueryContext): NodeSeq        = {
    (
      "#creationForm *" #> { (xml: NodeSeq) => SHtml.ajaxForm(xml) } andThen
      "#dialogTitle *" #> title &
      "#categoryName * " #> categoryName.toForm_! &
      "#categoryParent *" #> categoryParent.toForm_! &
      "#categoryDescription *" #> categoryDescription.toForm_! &
      "#categoryId *" #> (targetCategory match {
        case None    => NodeSeq.Empty
        case Some(c) =>
          <div class="row form-group">
                                        <label class="wbBaseFieldLabel">Rudder ID</label>
                                        <input class="form-control" type="text" value={c.id.value} disabled="true"/>
                                      </div>
      }) &
      "#saveCategory" #> SHtml.ajaxSubmit(
        TextForButton,
        () => onSubmit(),
        ("id", "createRuleCategorySaveButton"),
        ("tabindex", "5"),
        ("style", "margin-left:5px;")
      ) andThen
      ".notifications *" #> updateAndDisplayNotifications()
    )(html)
  }

  def deletePopupContent(canBeDeleted: Boolean)(using qc: QueryContext): NodeSeq = {
    val action   = () => if (canBeDeleted) onSubmitDelete() else closePopup()
    val disabled = if (canBeDeleted) ("", "") else ("disabled", "true")
    (
      "#dialogTitle *" #> s"Delete Rule category ${s"'${targetCategory.map(_.name).getOrElse("")}'"}" &
      "#text * " #> (if (canBeDeleted) "Are you sure you want to delete this Rule category?"
                     else "This Rule category is not empty and therefore cannot be deleted") &
      "#deleteCategoryButton" #> SHtml.ajaxButton(
        "Delete",
        action,
        ("id", "createRuleCategorySaveButton"),
        ("tabindex", "1"),
        ("class", "btn-danger"),
        disabled
      )
    )(deleteHtml)
  }

  ///////////// fields for category settings ///////////////////

  private val categoryName = new WBTextField("Name", targetCategory.map(_.name).getOrElse("")) {
    override def setFilter             = notNull :: trim :: Nil
    override def subContainerClassName = "col-xl-9 col-md-12 col-sm-12"
    override def errorClassName        = "col-xl-12 errors-container"
    override def inputField            =
      super.inputField % ("onkeydown" -> "return processKey(event , 'createRuleCategorySaveButton')") % ("tabindex" -> "2")
    override def validations =
      valMinLen(1, "The name must not be empty.") :: Nil
  }

  private val categoryDescription = new WBTextAreaField("Description", targetCategory.map(_.description).getOrElse("")) {
    override def subContainerClassName = "col-xl-9 col-md-12 col-sm-12"
    override def setFilter             = notNull :: trim :: Nil
    override def inputField            = super.inputField % ("tabindex" -> "4")
    override def errorClassName        = "col-xl-12 errors-container"
    override def validations: List[String => List[FieldError]] = Nil

  }

  val categories: RuleCategory = targetCategory.map(rootCategory.filter(_)).getOrElse(rootCategory)

  private val categoryParent = {
    new WBSelectField(
      "Parent",
      categoryHierarchyDisplayer.getRuleCategoryHierarchy(categories, None).map { case (id, name) => (id.value -> name) },
      parentCategory.getOrElse(selectedCategory.value)
    ) {
      override def subContainerClassName = "col-xl-9 col-md-12 col-sm-12"
      override def inputField            = super.inputField % ("tabindex" -> "3")
      override def className             = "col-xl-12 col-md-12 col-sm-12 form-select"
      override def validations           =
        valMinLen(1, "Please select a category") :: Nil
    }
  }

  private val formTracker = new FormTracker(categoryName, categoryDescription, categoryParent)

  private var notifications = List.empty[NodeSeq]

  private def error(msg: String) = <span class="col-xl-12 errors-container">{msg}</span>

  private def closePopup(): JsCmd = {
    JsRaw("""hideBsModal('createRuleCategoryPopup');""") // JsRaw ok, const
  }

  /**
   * Update the form when something happened
   */
  private def updateFormClientSide()(using qc: QueryContext): JsCmd = {
    SetHtml("RuleCategoryCreationContainer", popupContent())
  }

  private def onSubmitDelete()(using qc: QueryContext): JsCmd = {
    targetCategory match {
      case Some(category) =>
        val modId = new ModificationId(uuidGen.newUuid)
        woRulecategoryRepository.delete(category.id, modId, qc.actor, None, checkEmpty = true).toBox match {
          case Full(x) =>
            closePopup() &
            onSuccessCallback(x.value) &
            onSuccessCategory(category)
          case eb: EmptyBox =>
            val fail = eb ?~! s"An error occurred while deleting category '${category.name}'"
            logger.error(s"An error occurred while deleting the category: ${fail.msg}")
            formTracker.addFormError(error(fail.msg))
            onFailure & onFailureCallback()
        }
      case None           =>
        // Should not happen, close popup
        closePopup()
    }
  }

  private def onSubmit()(using qc: QueryContext): JsCmd = {

    if (formTracker.hasErrors) {
      onFailure & onFailureCallback()
    } else {
      (targetCategory match {
        case None           =>
          val newCategory = RuleCategory(
            RuleCategoryId(uuidGen.newUuid),
            categoryName.get,
            categoryDescription.get,
            Nil,
            isSystem = false
          )

          val parent = RuleCategoryId(categoryParent.get)
          val modId  = new ModificationId(uuidGen.newUuid)
          woRulecategoryRepository.create(newCategory, parent, modId, qc.actor, None).toBox
        case Some(category) =>
          val updated = category.copy(
            name = categoryName.get,
            description = categoryDescription.get
          )
          val parent  = RuleCategoryId(categoryParent.get)

          if (updated == category && parentCategory.exists(_ == parent.value)) {
            Failure("There are no modifications to save")
          } else {
            val modId = new ModificationId(uuidGen.newUuid)
            woRulecategoryRepository.updateAndMove(updated, parent, modId, qc.actor, None).toBox
          }
      }) match {
        case Full(x)          => closePopup() & onSuccessCallback(x.id.value) & onSuccessCategory(x)
        case Empty            =>
          logger.error("An error occurred while saving the category")
          formTracker.addFormError(error("An error occurred while saving the category"))
          onFailure & onFailureCallback()
        case Failure(m, _, _) =>
          logger.error("An error occurred while saving the category:" + m)
          formTracker.addFormError(error(m))
          onFailure & onFailureCallback()
      }
    }
  }

  private def onFailure(using qc: QueryContext): JsCmd = {
    updateFormClientSide()
  }

  private def updateAndDisplayNotifications(): NodeSeq = {
    notifications :::= formTracker.formErrors
    formTracker.cleanErrors

    if (notifications.isEmpty) NodeSeq.Empty
    else {
      val html = {
        <div id="notifications" class="alert alert-danger text-center col-xl-12 col-sm-12 col-md-12" role="alert"><ul class="text-danger">{
          notifications.map(n => <li>{n}</li>)
        }</ul></div>
      }
      notifications = Nil
      html
    }
  }
}
