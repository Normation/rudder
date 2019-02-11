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

import net.liftweb.http.js._
import JsCmds._
import JE._
import net.liftweb.common._
import net.liftweb.http.{SHtml,DispatchSnippet}
import scala.xml._
import net.liftweb.util.Helpers._
import com.normation.rudder.domain.nodes.{NodeGroupCategory,NodeGroupCategoryId}
import com.normation.rudder.AuthorizationType
import com.normation.rudder.web.model.{
  WBTextField, FormTracker, WBTextAreaField,WBSelectField, CurrentUser
}
import com.normation.rudder.repository._
import com.normation.eventlog.ModificationId
import bootstrap.liftweb.RudderConfig

import com.normation.box._

/**
 * The form that deals with updating the server group category
 *
 * @author Nicolas CHARLES
 *
 */
class NodeGroupCategoryForm(
    htmlIdCategory    : String
  , nodeGroupCategory : NodeGroupCategory
  , rootCategory      : FullNodeGroupCategory
  , onSuccessCallback : (String) => JsCmd = { (String) => Noop }
  , onFailureCallback : () => JsCmd = { () => Noop }
) extends DispatchSnippet with Loggable {

  var _nodeGroupCategory = nodeGroupCategory.copy()

  private[this] val roGroupCategoryRepository = RudderConfig.roNodeGroupRepository
  private[this] val woGroupCategoryRepository = RudderConfig.woNodeGroupRepository
  private[this] val uuidGen                   = RudderConfig.stringUuidGenerator
  private[this] val categoryHierarchyDisplayer= RudderConfig.categoryHierarchyDisplayer

  val categories = roGroupCategoryRepository.getAllNonSystemCategories.toBox match {
    case eb:EmptyBox =>
      val f = eb  ?~! "Can not get Group root category"
      logger.error(f.messageChain)
      f.rootExceptionCause.foreach(ex =>
        logger.error("Exception was:", ex)
      )
      Seq()
    case Full(cats) => cats.filter(x => x.id != _nodeGroupCategory.id)
  }

  val parentCategory = roGroupCategoryRepository.getParentGroupCategory(nodeGroupCategory.id).toBox

  val parentCategoryId = parentCategory match {
    case Full(x) =>  x.id.value
    case _ => ""
  }

  def dispatch = {
    case "showForm" => { _ => showForm }
  }

  def showForm() : NodeSeq = {
    val html = SHtml.ajaxForm(
      <div class="inner-portlet groupCategoryUpdateComponent">
        <div>
          <div class="page-title">Category details</div>
        </div>
        <directive-notifications></directive-notifications>
        <hr class="spacer"/>
        <directive-name></directive-name>
        <hr class="spacer"/>
        <directive-description></directive-description>
        <hr class="spacer"/>
        <directive-container></directive-container>
        <hr class="spacer"/>
        <div class="wbBaseField">
          <span class="threeCol textright"><b>Rudder ID:</b></span>
          <div class="twoCol"><span>{_nodeGroupCategory.id.value}</span></div>
       </div>
        <hr class="spacer"/>
        <lift:authz role="node_write">
        <div class="margins space-top"><directive-save></directive-save> <directive-delete></directive-delete></div>
        </lift:authz>
      </div>
   )

    (
        "directive-name"          #> name.toForm_!
      & "directive-description"   #> description.toForm_!
      & "directive-container"     #> container.toForm_!
      & "directive-notifications" #> updateAndDisplayNotifications()
      & (if (_nodeGroupCategory.isSystem) (
            "input [disabled]"        #> "true"
          & "textarea [disabled]"     #> "true"
          & "directive-save"          #> SHtml.ajaxSubmit("Update", onSubmit _ , ("class","btn btn-success pull-right"))
          & "directive-delete"        #> deleteButton
        ) else (
            "directive-save" #> (
                if (CurrentUser.checkRights(AuthorizationType.Group.Edit)) SHtml.ajaxSubmit("Update", onSubmit _ , ("class","btn btn-default"))
                else NodeSeq.Empty
            )
          & "directive-delete" #> (
              if (CurrentUser.checkRights(AuthorizationType.Group.Write)) deleteButton
                else NodeSeq.Empty
            )
        ))
    )(html)
   }

  ///////////// delete management /////////////

  /**
   * Delete button is only enabled is that category
   * has zero child
   */
  private[this] def deleteButton : NodeSeq = {

    if(parentCategory.isDefined && _nodeGroupCategory.children.isEmpty && _nodeGroupCategory.items.isEmpty) {
      (
       <button id="removeButton" class="btn btn-danger">Delete</button>
        <div>
            <div id="removeActionDialog" class="modal fade" data-keyboard="true" tabindex="-1">
                <div class="modal-backdrop fade in" style="height: 100%;"></div>
                <div class="modal-dialog">
                    <div class="modal-content">
                        <div class="modal-header">
                            <div class="close" data-dismiss="modal">
                                <span aria-hidden="true">&times;</span>
                                <span class="sr-only">Close</span>
                            </div>
                            <h4 class="modal-title text-left">
                                Delete a group category
                            </h4>
                        </div>
                        <div class="modal-body">
                            <div class="row">
                                <div class="col-lg-12">
                                    <h4 class="text-center">
                                        Are you sure that you want to completely delete this category ?
                                    </h4>
                                </div>
                            </div>
                        </div>
                        <div class="modal-footer" style="text-align:center">
                            <button type="button" class="btn btn-default" data-dismiss="modal">Close</button>
                            {SHtml.ajaxButton("Delete", onDelete _ ,("class", "btn btn-danger"))}
                        </div>
                    </div><!-- /.modal-content -->
                </div><!-- /.modal-dialog -->
            </div><!-- /.modal -->
        </div>
      ) ++
      Script(JsRaw("""
        $('#removeButton').click(function() {
          createPopup("removeActionDialog");
          return false;
        });
        """))
    } else {
      <button disabled="disabled" class="btn btn-danger">Delete</button><br/>
      <div class="note"><b>Note: </b>Only empty and non root categories can be deleted.</div>
    }
  }

  private[this] def onDelete() : JsCmd = {
    woGroupCategoryRepository.delete(_nodeGroupCategory.id, ModificationId(uuidGen.newUuid), CurrentUser.actor, Some("Node Group category deleted by user from UI")).toBox match {
      case Full(id) =>
        JsRaw("""$('#removeActionDialog').bsModal('hide');""") &
        SetHtml(htmlIdCategory, NodeSeq.Empty) &
        onSuccessCallback(nodeGroupCategory.id.value) &
        successPopup
      case e:EmptyBox =>
        val m = (e ?~! "Error when trying to delete the category").messageChain
        formTracker.addFormError(error(m))
        updateFormClientSide & onFailureCallback()
    }
  }

  ///////////// fields for category settings ///////////////////
  private[this] val name = new WBTextField("Category name", _nodeGroupCategory.name) {
    override def setFilter = notNull _ :: trim _ :: Nil
    override def validations =
      valMinLen(1, "Name must not be empty") _ :: Nil
  }

  private[this] val description = new WBTextAreaField("Category description", _nodeGroupCategory.description.toString) {
    override def setFilter = notNull _ :: trim _ :: Nil
    override def inputField = super.inputField  % ("style" -> "height:10em")
    override def validations =  Nil
    override def errorClassName = "field_errors paddscala"
  }

  /**
   * If there is no parent, it is its own parent
   */
  private[this] val container = parentCategory match {
    case x:EmptyBox =>
      new WBSelectField("Parent category: ",
        Seq(_nodeGroupCategory.id.value -> _nodeGroupCategory.name),
        _nodeGroupCategory.id .value,
        Seq("disabled" -> "true")
      ) {
        override def className = "rudderBaseFieldSelectClassName"
        override def validations =
      valMinLen(1, "Please select a category") _ :: Nil
      }
    case Full(category) =>
      new WBSelectField(
        "Parent category"
      , categoryHierarchyDisplayer.getCategoriesHierarchy(rootCategory, exclude = Some( _.id == _nodeGroupCategory.id)).
            map { case (id, name) => (id.value -> name)}
      , parentCategoryId)  {
          override def className = "rudderBaseFieldSelectClassName"
          override def validations =
      valMinLen(1, "Please select a category") _ :: Nil
      }
  }

  private[this] val formTracker = new FormTracker(name, description, container)

  private[this] var notifications = List.empty[NodeSeq]

  private[this] def updateFormClientSide : JsCmd = {
    SetHtml(htmlIdCategory, showForm())
  }

  private[this] def error(msg:String) = <span class="col-lg-12 errors-container">{msg}</span>

  private[this] def onSuccess : JsCmd = {

    notifications ::=  <span class="greenscala">Category was correctly updated</span>
    updateFormClientSide
  }

  private[this] def onFailure : JsCmd = {
    formTracker.addFormError(error("There was problem with your request."))
    updateFormClientSide & JsRaw("""scrollToElement("notifications","#groupDetails");""")
  }

  private[this] def onSubmit() : JsCmd = {
    if(formTracker.hasErrors) {
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

      woGroupCategoryRepository.saveGroupCategory(
          newNodeGroup
        , NodeGroupCategoryId(container.get)
        , ModificationId(uuidGen.newUuid)
        , CurrentUser.actor
        , Some("Node Group category saved by user from UI")
      ).toBox match {
        case Full(x) =>
          _nodeGroupCategory = x
          onSuccess & onSuccessCallback(nodeGroupCategory.id.value) & successPopup
        case Empty =>
          logger.error("An error occurred while saving the GroupCategory")
           formTracker.addFormError(error("An error occurred while saving the GroupCategory"))
          onFailure & onFailureCallback()
        case Failure(m,_,_) =>
          logger.error("An error occurred while saving the GroupCategory:" + m)
          formTracker.addFormError(error("An error occurred while saving the GroupCategory: " + m))
          onFailure & onFailureCallback()
      }

    }
  }

  private[this] def updateAndDisplayNotifications() : NodeSeq = {

    val notifications = formTracker.formErrors
    formTracker.cleanErrors

    if(notifications.isEmpty) {
      NodeSeq.Empty
    }
    else {
      val html =
        <div id="notifications" class="notify">
          <ul class="field_errors">{notifications.map( n => <li>{n}</li>) }</ul>
        </div>
      html
    }
  }

  ///////////// success pop-up ///////////////
    private[this] def successPopup : JsCmd = {
      JsRaw("""createSuccessNotification()""")
  }

}
