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

package com.normation.rudder.web.components

import com.normation.plugins.SpringExtendableSnippet
import com.normation.plugins.SnippetExtensionKey
import com.normation.inventory.domain.NodeId
import com.normation.rudder.authorization._
import com.normation.rudder.domain.nodes._
import com.normation.rudder.domain.queries.Query
import com.normation.rudder.domain.eventlog.RudderEventActor
import com.normation.rudder.domain.policies.GroupTarget
import com.normation.rudder.domain.workflows.ChangeRequestId
import com.normation.rudder.web.model.{
  WBTextField, FormTracker, WBTextAreaField,WBSelectField,WBRadioField
}
import com.normation.rudder.repository.FullNodeGroupCategory
import com.normation.rudder.web.components.popup.CreateCloneGroupPopup
import com.normation.rudder.web.components.popup.ModificationValidationPopup
import com.normation.rudder.web.model.CurrentUser
import com.normation.rudder.web.services.CategoryHierarchyDisplayer
import com.normation.utils.HashcodeCaching

import net.liftweb.http.LocalSnippet
import net.liftweb.http.js._
import JsCmds._
import JE._
import net.liftweb.common._
import net.liftweb.http._
import scala.xml._
import net.liftweb.util.Helpers
import net.liftweb.util.Helpers._

import bootstrap.liftweb.RudderConfig

object NodeGroupForm {
  private[this] val templatePathList = "templates-hidden" :: "components" :: "NodeGroupForm" :: Nil
  private[this] val templatePath = templatePathList.mkString("/", "/", ".html")
  private[this] val template = Templates(templatePathList).openOrThrowException(s"Missing template at path ${templatePath}")
  private[this] def chooseXml(tag:String) = {
    val xml = chooseTemplate("component", tag, template)
    if(xml.isEmpty) throw new Exception(s"Tag <component:${tag} is empty at path ${templatePath}")
    xml
  }

  val staticInit = chooseXml("staticInit")
  val staticBody = chooseXml("staticBody")
  val body = chooseXml("body")

  private val saveButtonId = "groupSaveButtonId"

  private sealed trait RightPanel
  private case object NoPanel extends RightPanel
  private case class GroupForm(group:NodeGroup, parentCategoryId:NodeGroupCategoryId) extends RightPanel with HashcodeCaching
  private case class CategoryForm(category:NodeGroupCategory) extends RightPanel with HashcodeCaching

  val htmlId_groupTree = "groupTree"
  val htmlId_item = "ajaxItemContainer"
  val htmlId_updateContainerForm = "updateContainerForm"
}


/**
 * The form that deals with updating the server group
 */
class NodeGroupForm(
    htmlIdCategory    : String
  , val nodeGroup     : NodeGroup
  , parentCategoryId  : NodeGroupCategoryId
  , rootCategory      : FullNodeGroupCategory
  , onSuccessCallback : (Either[NodeGroup, ChangeRequestId]) => JsCmd = { (NodeGroup) => Noop }
  , onFailureCallback : () => JsCmd = { () => Noop }
) extends DispatchSnippet with SpringExtendableSnippet[NodeGroupForm] with Loggable {
  import NodeGroupForm._

  private[this] val nodeInfoService            = RudderConfig.nodeInfoService
  private[this] val categoryHierarchyDisplayer = RudderConfig.categoryHierarchyDisplayer
  private[this] val workflowEnabled            = RudderConfig.RUDDER_ENABLE_APPROVAL_WORKFLOWS

  private[this] val nodeGroupCategoryForm = new LocalSnippet[NodeGroupCategoryForm]
  private[this] val nodeGroupForm = new LocalSnippet[NodeGroupForm]
  private[this] val searchNodeComponent = new LocalSnippet[SearchNodeComponent]

  private[this] var query : Option[Query] = nodeGroup.query
  private[this] var srvList : Box[Seq[NodeInfo]] = nodeInfoService.getAll.map( _.filter( (x:NodeInfo) => nodeGroup.serverList.contains( x.id ) ).toSeq )

  private def setNodeGroupCategoryForm : Unit = {
    searchNodeComponent.set(Full(new SearchNodeComponent(
        htmlIdCategory
      , query
      , srvList
      , onSearchCallback = saveButtonCallBack
      , onClickCallback  = { id => onClickCallBack(id) }
      , saveButtonId     = saveButtonId
      , groupPage        = false
    )))
  }

  private[this] def saveButtonCallBack(searchStatus : Boolean) : JsCmd = {
    JsRaw(s"""$$('#${saveButtonId}').button();
        $$('#${saveButtonId}').button("option", "disabled", ${searchStatus});""")
  }

  private[this] def onClickCallBack(s:String) : JsCmd = {
    s.split("\\|").toList match {
      case _ :: id :: _ =>
        SetHtml("serverDetails", (new ShowNodeDetailsFromNode(new NodeId(id), rootCategory)).display(true)) &
        createPopup("nodeDetailsPopup")

      case _ => Alert("Error when trying to display node details: received bad parameter for node ID: %s".format(s))
    }
  }

  setNodeGroupCategoryForm

  def extendsAt = SnippetExtensionKey(classOf[NodeGroupForm].getSimpleName)

  def mainDispatch = Map(
    "showForm" -> { _:NodeSeq => showForm() },
    "showGroup" -> { _:NodeSeq => searchNodeComponent.is match {
      case Full(component) => component.buildQuery
      case _ =>  <div>The component is not set</div>
     } }
  )

  def initJs : JsCmd = {
    JsRaw("correctButtons();")
  }


  val pendingChangeRequestXml =
    <div id="pendingChangeRequestNotification">
      <div>
        <img src="/images/icWarn.png" alt="Warning!" height="32" width="32" class="warnicon"/>
        <div style="float:left">
          The following pending change requests affect this Group, you should check that your modification is not already pending:
          <ul id="changeRequestList"/>
        </div>
      </div>
    </div>

  def showForm() : NodeSeq = {
     val html = SHtml.ajaxForm(body) ++
     Script(
       OnLoad(JsRaw(
       """$('#GroupTabs').tabs();
          $( "#GroupTabs" ).tabs('select', 0);"""
       ))
       & initJs
     )

     bind("group", html,
      "pendingChangeRequest" ->  PendingChangeRequestDisplayer.checkByGroup(pendingChangeRequestXml,nodeGroup.id),
      "name" -> groupName.toForm_!,
      "rudderID" -> <div><b class="threeCol">Rudder ID: </b>{nodeGroup.id.value.toUpperCase}</div>,
      "description" -> groupDescription.toForm_!,
      "container" -> groupContainer.toForm_!,
      "static" -> groupStatic.toForm_!,
      "showGroup" -> (searchNodeComponent.is match {
                       case Full(req) => req.buildQuery
                       case eb:EmptyBox => <span class="error">Error when retrieving the request, please try again</span>
      }),
      "clone" -> { if (CurrentUser.checkRights(Write("group")))
                     SHtml.ajaxButton("Clone", () => showCloneGroupPopup()) % ("id", "groupCloneButtonId")
                   else NodeSeq.Empty
                 },
      "save" -> { if (CurrentUser.checkRights(Edit("group")))
                    SHtml.ajaxSubmit("Save", onSubmit _)  %  ("id", saveButtonId)
                   else NodeSeq.Empty
                },
      "delete" -> SHtml.ajaxSubmit("Delete", () => onSubmitDelete(),("class" ,"dangerButton")),
      "notifications" -> updateAndDisplayNotifications()
    )
   }



  ///////////// fields for category settings ///////////////////
  private[this] val groupName = {
    new WBTextField("Group name", nodeGroup.name) {
      override def setFilter = notNull _ :: trim _ :: Nil
      override def className = "rudderBaseFieldClassName"
      override def inputField = super.inputField %("onkeydown" , "return processKey(event , '%s')".format(saveButtonId))
      override def validations =
        valMinLen(3, "The name must have at least 3 characters") _ :: Nil
    }
  }

  private[this] val groupDescription = {
    new WBTextAreaField("Group description", nodeGroup.description) {
      override def setFilter = notNull _ :: trim _ :: Nil
      override def inputField = super.inputField  % ("style" -> "height:10em")
      override def validations =  Nil
      override def errorClassName = "field_errors paddscala"
    }
  }

  private[this] val groupStatic = {
    new WBRadioField(
        "Group type",
        Seq("static", "dynamic"),
        if(nodeGroup.isDynamic) "dynamic" else "static",
        {
           //how to display label ? Capitalize, and with a tooltip
          case "static" => <span class="tooltip" title="The list of member nodes is defined at creation and will not change automatically.">Static</span>
          case "dynamic" => <span class="tooltip" title="Nodes will be automatically added and removed so that the list of members always matches this group's search criteria.">Dynamic</span>
          case _ => NodeSeq.Empty // guarding against NoMatchE
       }
    ) {
      override def setFilter = notNull _ :: trim _ :: Nil
    }
  }

  private[this] val groupContainer = new WBSelectField("Group container",
      (categoryHierarchyDisplayer.getCategoriesHierarchy(rootCategory, None).map { case (id, name) => (id.value -> name)}),
      parentCategoryId.value) {
    override def className = "rudderBaseFieldSelectClassName"
  }

  private[this] val formTracker = new FormTracker(List(groupName, groupDescription, groupContainer, groupStatic))

  private[this] def updateFormClientSide() : JsCmd = {
    SetHtml(htmlIdCategory, showForm()) & initJs
  }

  private[this] def error(msg:String) = <span class="error">{msg}</span>

  private[this] def onFailure : JsCmd = {
    formTracker.addFormError(error("The form contains some errors, please correct them."))
    updateFormClientSide() & JsRaw("""scrollToElement("errorNotification");""")
  }

  private[this] def onSubmit() : JsCmd = {
    // Since we are doing the submit from the component, it ought to exist
    searchNodeComponent.is match {
      case Full(req) =>
        query = req.getQuery
        srvList = req.getSrvList
      case eb:EmptyBox =>
        val f = eb ?~! "Error when trying to retrieve the current search state"
        logger.error(f.messageChain)
    }

    if(formTracker.hasErrors) {
      onFailure & onFailureCallback()
    } else {
      val optContainer = {
        val c = NodeGroupCategoryId(groupContainer.is)
        if(c == parentCategoryId) None
        else Some(c)
      }

      val newGroup = nodeGroup.copy(
          name = groupName.is
        , description = groupDescription.is
        //, container = container
        , isDynamic = groupStatic.is match { case "dynamic" => true ; case _ => false }
        , query = query
        , serverList =  srvList.getOrElse(Set()).map( _.id ).toSet
      )

      if(newGroup == nodeGroup) {
        formTracker.addFormError(Text("There is no modification to save"))
        onFailure & onFailureCallback()
      } else {
        displayConfirmationPopup("save", newGroup, optContainer)
      }
    }
  }

  private[this] def onSubmitDelete(): JsCmd = {
    displayConfirmationPopup("delete", nodeGroup, None)
  }

  /*
   * Create the confirmation pop-up
   */
  private[this] def displayConfirmationPopup(
      action     : String
    , newGroup   : NodeGroup
    , newCategory: Option[NodeGroupCategoryId]
  ) : JsCmd = {

    val optOriginal = Some(nodeGroup)

    val popup = {

      def successCallback(crId:ChangeRequestId) = if (workflowEnabled) {
        onSuccessCallback(Right(crId))
      } else {
        successPopup & onSuccessCallback(Left(newGroup)) &
        (if (action=="delete")
           SetHtml(htmlId_item,NodeSeq.Empty)
        else
             Noop)
      }

      new ModificationValidationPopup(
          Right(newGroup, newCategory, optOriginal)
        , action
        , false
        , crId => JsRaw("$.modal.close();") & successCallback(crId)
        , xml => JsRaw("$.modal.close();") & onFailure
        , parentFormTracker = formTracker
      )
    }

    popup.popupWarningMessages match {
      case None =>
        popup.onSubmit
      case Some(_) =>
        SetHtml("confirmUpdateActionDialog", popup.popupContent) &
        JsRaw("""createPopup("confirmUpdateActionDialog")""")
    }
  }


  def createPopup(name:String) :JsCmd = {
    JsRaw(s"""createPopup("${name}");""")
  }
  private[this] def showCloneGroupPopup() : JsCmd = {
    val popupSnippet = new LocalSnippet[CreateCloneGroupPopup]
            popupSnippet.set(Full(new CreateCloneGroupPopup(
              Some(nodeGroup),
              onSuccessCategory = displayACategory,
              onSuccessGroup = showGroupSection
              //, onSuccessCallback = { onSuccessCallback }
            )))
    val nodeSeqPopup = popupSnippet.is match {
      case Failure(m, _, _) =>  <span class="error">Error: {m}</span>
      case Empty => <div>The component is not set</div>
      case Full(popup) => popup.popupContent()
    }
    SetHtml("createCloneGroupContainer", nodeSeqPopup) &
    createPopup("createCloneGroupPopup")
  }

  private[this] def displayACategory(category : NodeGroupCategory) : JsCmd = {
    refreshRightPanel(CategoryForm(category))
  }

  private[this] def refreshRightPanel(panel:RightPanel) : JsCmd = SetHtml(htmlId_item, setAndShowRightPanel(panel))

  /**
   *  Manage the state of what should be displayed on the right panel.
   * It could be nothing, a group edit form, or a category edit form.
   */
  private[this] def setAndShowRightPanel(panel:RightPanel) : NodeSeq = {
    panel match {
      case NoPanel => NodeSeq.Empty
      case GroupForm(group, catId) =>
        val form = new NodeGroupForm(htmlId_item, group, catId, rootCategory, onSuccessCallback)
        nodeGroupForm.set(Full(form))
        form.showForm()

      case CategoryForm(category) =>
        val form = new NodeGroupCategoryForm(htmlId_item, category, rootCategory) //, onSuccessCallback)
        nodeGroupCategoryForm.set(Full(form))
        form.showForm()
    }
  }

  private[this] def showGroupSection(group: NodeGroup, parentCategoryId: NodeGroupCategoryId) : JsCmd = {
    //update UI
    refreshRightPanel(GroupForm(group, parentCategoryId))&
    JsRaw("""this.window.location.hash = "#" + JSON.stringify({'groupId':'%s'})""".format(group.id.value))
  }

  private[this] def updateAndDisplayNotifications() : NodeSeq = {

    val notifications = formTracker.formErrors
    formTracker.cleanErrors

    if(notifications.isEmpty) {
      NodeSeq.Empty
    }
    else {
      val html =
        <div id="errorNotification" class="notify">
          <ul class="field_errors">{notifications.map( n => <li>{n}</li>) }</ul>
        </div>
      html
    }
  }

  private[this] def successPopup : JsCmd = {
    JsRaw(""" callPopupWithTimeout(200, "successConfirmationDialog")
    """)
  }

}
