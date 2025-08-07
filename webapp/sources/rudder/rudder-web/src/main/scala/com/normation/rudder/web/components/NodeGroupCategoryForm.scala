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

package com.normation.rudder.web.components

import bootstrap.liftweb.RudderConfig
import com.normation.box.*
import com.normation.eventlog.ModificationId
import com.normation.rudder.AuthorizationType
import com.normation.rudder.domain.nodes.NodeGroupCategory
import com.normation.rudder.domain.nodes.NodeGroupCategoryId
import com.normation.rudder.repository.*
import com.normation.rudder.users.CurrentUser
import com.normation.rudder.web.model.FormTracker
import com.normation.rudder.web.model.WBSelectField
import com.normation.rudder.web.model.WBTextAreaField
import com.normation.rudder.web.model.WBTextField
import net.liftweb.common.*
import net.liftweb.http.DispatchSnippet
import net.liftweb.http.SHtml
import net.liftweb.http.js.*
import net.liftweb.http.js.JE.*
import net.liftweb.http.js.JsCmds.*
import net.liftweb.util.FieldError
import net.liftweb.util.Helpers.*
import scala.xml.*

/**
 * The form that deals with updating the server group category
 *
 * @author Nicolas CHARLES
 *
 */
class NodeGroupCategoryForm(
    htmlIdCategory:    String,
    nodeGroupCategory: NodeGroupCategory,
    rootCategory:      FullNodeGroupCategory,
    onSuccessCallback: (String) => JsCmd = { (String) => Noop },
    onFailureCallback: () => JsCmd = { () => Noop }
) extends DispatchSnippet with Loggable {

  var _nodeGroupCategory: NodeGroupCategory = nodeGroupCategory.copy()

  private val roGroupCategoryRepository  = RudderConfig.roNodeGroupRepository
  private val woGroupCategoryRepository  = RudderConfig.woNodeGroupRepository
  private val uuidGen                    = RudderConfig.stringUuidGenerator
  private val categoryHierarchyDisplayer = RudderConfig.categoryHierarchyDisplayer

  val categories: Seq[NodeGroupCategory] = roGroupCategoryRepository.getAllNonSystemCategories().toBox match {
    case eb: EmptyBox =>
      val f = eb ?~! "Can not get Group root category"
      logger.error(f.messageChain)
      f.rootExceptionCause.foreach(ex => logger.error("Exception was:", ex))
      Seq()
    case Full(cats) => cats.filter(x => x.id != _nodeGroupCategory.id)
  }

  val parentCategory: Box[NodeGroupCategory] = roGroupCategoryRepository.getParentGroupCategory(nodeGroupCategory.id).toBox

  val parentCategoryId: String = parentCategory match {
    case Full(x) => x.id.value
    case _       => ""
  }

  def dispatch: PartialFunction[String, NodeSeq => NodeSeq] = { case "showForm" => { _ => showForm() } }

  def showForm(): NodeSeq = {
    val html = SHtml.ajaxForm(
      <div class="main-container">
        <div class="main-header">
          <div class="header-title">
            <h1>
              <i class="title-icon fa fa-folder"></i>
              <category-name></category-name>
            </h1>
            <lift:authz role="group_edit">
              <div class="header-buttons">
                <directive-close></directive-close>
                <lift:authz role="node_write">
                  <directive-delete></directive-delete>
                  <directive-save></directive-save>
                </lift:authz>
              </div>
            </lift:authz>
          </div>
        </div>
        <div class="main-navbar">
          <ul id="groupTabMenu" class="nav nav-underline">
            <li class="nav-item">
              <button class="nav-link active" data-bs-toggle="tab" data-bs-target="#categoryParametersTab" type="button" role="tab" aria-controls="categoryParametersTab" aria-selected="true">Parameters</button>
            </li>
          </ul>
        </div>
        <div class="main-details">
          <div id="categoryParametersTab" class="main-form">
            <directive-notifications></directive-notifications>
            <directive-name></directive-name>
            <directive-description></directive-description>
            <directive-container></directive-container>
            <div class="form-group">
              <label class="wbBaseFieldLabel">Group category ID</label>
              <input readonly="" class="form-control" value={nodeGroupCategory.id.value}/>
            </div>
          </div>
        </div>
      </div>
    )

    (
      "category-name" #> name
      & "directive-name" #> (if (_nodeGroupCategory.isSystem) name.readOnlyValue else name.toForm_!)
      & "directive-description" #> (if (_nodeGroupCategory.isSystem) description.readOnlyValue else description.toForm_!)
      & "directive-container" #> (if (_nodeGroupCategory.isSystem) container.readOnlyValue else container.toForm_!)
      & "directive-notifications" #> updateAndDisplayNotifications()
      & (if (_nodeGroupCategory.isSystem) {
           (
             "input [disabled]" #> "true"
             & "directive-save" #> NodeSeq.Empty
             & "directive-delete" #> deleteButton
           )
         } else {
           (
             "directive-save" #> (
               if (CurrentUser.checkRights(AuthorizationType.Group.Edit))
                 SHtml.ajaxSubmit("Update", onSubmit, ("class", "btn btn-success"))
               else NodeSeq.Empty
             )
             & "directive-delete" #> (
               if (CurrentUser.checkRights(AuthorizationType.Group.Write)) deleteButton
               else NodeSeq.Empty
             )
           )
         })
      & "directive-close" #> (
        <button class="btn btn-default" onclick={
          s"""$$('#${htmlIdCategory}').trigger("group-close-detail")"""
        }>
          Close
          <i class="fa fa-times"></i>
        </button>
      )
    )(html)
  }

  ///////////// delete management /////////////

  /**
   * Delete button is only enabled is that category
   * has zero child and is not a system category
   */
  private def deleteButton: NodeSeq = {

    if (
      parentCategory.isDefined && !_nodeGroupCategory.isSystem && _nodeGroupCategory.children.isEmpty && _nodeGroupCategory.items.isEmpty
    ) {
      val popupContent = {
        <div class="modal-dialog">
             <div class="modal-content">
               <div class="modal-header">
                 <h5 class="modal-title text-start">
                   Delete a group category
                 </h5>
                 <button type="button" class="btn-close" data-bs-dismiss="modal" aria-label="Close"></button>
               </div>
               <div class="modal-body">
                 <div class="row">
                   <div class="col-xl-12">
                     <h4 class="text-center">
                       Are you sure that you want to completely delete this category ?
                     </h4>
                   </div>
                 </div>
               </div>
               <div class="modal-footer" style="text-align:center">
                 <button type="button" class="btn btn-default" data-bs-dismiss="modal">Close</button>
                 {SHtml.ajaxButton("Delete", () => onDelete(), ("class", "btn btn-danger"))}
               </div>
             </div>
           </div>
      }
      SHtml.ajaxSubmit(
        "Delete",
        () => SetHtml("basePopup", popupContent) & JsRaw("""initBsModal("basePopup")"""), // JsRaw ok, const
        ("class", "btn btn-danger")
      )
    } else {
      (<span class="btn btn-danger disabled" data-bs-toggle="tooltip" data-bs-placement="bottom" title={
        "<div><i class='fa fa-exclamation-triangle text-warning'></i>Only empty and user created categories can be deleted.</div>"
      }>Delete</span>) ++ Script(JsRaw("""initBsTooltips();""")) // JsRaw ok, const
    }
  }

  private def onDelete(): JsCmd = {
    woGroupCategoryRepository
      .delete(
        _nodeGroupCategory.id,
        ModificationId(uuidGen.newUuid),
        CurrentUser.actor,
        Some("Node Group category deleted by user from UI")
      )
      .toBox match {
      case Full(id) =>
        JsRaw("""hideBsModal('basePopup');""") & // JsRaw ok, const
        SetHtml(htmlIdCategory, NodeSeq.Empty) &
        onSuccessCallback(nodeGroupCategory.id.value) &
        successPopup
      case e: EmptyBox =>
        val m = (e ?~! "Error when trying to delete the category").messageChain
        formTracker.addFormError(error(m))
        updateFormClientSide & onFailureCallback()
    }
  }

  ///////////// fields for category settings ///////////////////
  private val name = new WBTextField("Category name", _nodeGroupCategory.name) {
    override def setFilter             = notNull :: trim :: Nil
    override def className             = "form-control"
    override def labelClassName        = ""
    override def subContainerClassName = ""
    override def validations           =
      valMinLen(1, "Name must not be empty") :: Nil
  }

  private val description = new WBTextAreaField("Category description", _nodeGroupCategory.description.toString) {
    override def setFilter             = notNull :: trim :: Nil
    override def className             = "form-control"
    override def labelClassName        = ""
    override def subContainerClassName = ""
    override def validations: List[String => List[FieldError]] = Nil
    override def errorClassName = "text-danger mt-1"
  }

  /**
   * If there is no parent, it is its own parent
   */
  private val container = parentCategory match {
    case x: EmptyBox =>
      new WBSelectField(
        "Parent category: ",
        Seq(_nodeGroupCategory.id.value -> _nodeGroupCategory.name),
        _nodeGroupCategory.id.value,
        Seq("disabled"                  -> "true")
      ) {
        override def className             = "form-select"
        override def labelClassName        = ""
        override def subContainerClassName = ""
        override def validations           =
          valMinLen(1, "Please select a category") :: Nil
      }
    case Full(category) =>
      new WBSelectField(
        "Parent category",
        categoryHierarchyDisplayer.getCategoriesHierarchy(rootCategory, exclude = Some(_.id == _nodeGroupCategory.id)).map {
          case (id, name) => (id.value -> name)
        },
        parentCategoryId
      ) {
        override def className             = "form-select w-100"
        override def labelClassName        = ""
        override def subContainerClassName = ""
        override def validations           =
          valMinLen(1, "Please select a category") :: Nil
      }
  }

  private val formTracker = new FormTracker(name, description, container)

  private def updateFormClientSide: JsCmd = {
    SetHtml(htmlIdCategory, showForm())
  }

  private def error(msg: String) = <span class="col-sm-12 errors-container">{msg}</span>

  private def onSuccess: JsCmd = {
    updateFormClientSide
  }

  private def onFailure: JsCmd = {
    formTracker.addFormError(error("There was a problem with your request."))
    updateFormClientSide & JsRaw("""scrollToElement("notifications","#ajaxItemContainer");""") // JsRaw OK, no user input
  }

  private def onSubmit(): JsCmd = {
    if (formTracker.hasErrors) {
      onFailure & onFailureCallback()
    } else {

      // create the new NodeGroupCategory
      val newNodeGroup = new NodeGroupCategory(
        _nodeGroupCategory.id,
        name.get,
        description.get,
        _nodeGroupCategory.children,
        _nodeGroupCategory.items,
        _nodeGroupCategory.isSystem
      )

      woGroupCategoryRepository
        .saveGroupCategory(
          newNodeGroup,
          NodeGroupCategoryId(container.get),
          ModificationId(uuidGen.newUuid),
          CurrentUser.actor,
          Some("Node Group category saved by user from UI")
        )
        .toBox match {
        case Full(x)          =>
          _nodeGroupCategory = x
          onSuccess & onSuccessCallback(nodeGroupCategory.id.value) & successPopup
        case Empty            =>
          logger.error("An error occurred while saving the GroupCategory")
          formTracker.addFormError(error("An error occurred while saving the GroupCategory"))
          onFailure & onFailureCallback()
        case Failure(m, _, _) =>
          logger.error("An error occurred while saving the GroupCategory:" + m)
          formTracker.addFormError(error("An error occurred while saving the GroupCategory: " + m))
          onFailure & onFailureCallback()
      }

    }
  }

  private def updateAndDisplayNotifications(): NodeSeq = {

    val notifications = formTracker.formErrors
    formTracker.cleanErrors

    if (notifications.isEmpty) {
      NodeSeq.Empty
    } else {
      val html = {
        <div id="notifications" class="notify">
          <ul class="text-danger">{notifications.map(n => <li>{n}</li>)}</ul>
        </div>
      }
      html
    }
  }

  ///////////// success pop-up ///////////////
  private def successPopup: JsCmd = {
    JsRaw("""createSuccessNotification()""") // JsRaw ok, const
  }

}
