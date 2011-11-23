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


import com.normation.rudder.domain.nodes._
import com.normation.inventory.domain.NodeId
import org.slf4j.LoggerFactory
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.domain.queries.Query
import net.liftweb.http.LocalSnippet

import net.liftweb.http.Templates

import net.liftweb.http.js._
import JsCmds._
import com.normation.utils.StringUuidGenerator

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
import com.normation.rudder.services.nodes.NodeInfoService
import com.normation.rudder.web.model.CurrentUser

/**
 * Create a group or a category
 * This is a popup that allows for the creation of a group or a category, or
 * if a group is passed as an argument, will force the creation of a new group based on the query
 * contained
 */
class CreateCategoryOrGroupPopup(
  groupGenerator : Option[NodeGroup] = None,
  onSuccessCategory : (NodeGroupCategory) => JsCmd,
  onSuccessGroup : (NodeGroup) => JsCmd,
  onSuccessCallback : (String) => JsCmd = { _ => Noop },
  onFailureCallback : () => JsCmd = { () => Noop }
       ) extends DispatchSnippet with Loggable {

  // Load the template from the popup
  def templatePath = List("templates-hidden", "Popup", "createCategoryOrGroup")
  def template() =  Templates(templatePath) match {
     case Empty | Failure(_,_,_) =>
       error("Template for creation popup not found. I was looking for %s.html".format(templatePath.mkString("/")))
     case Full(n) => n
  }
  def popupTemplate = chooseTemplate("groups", "createGroupPopup", template)


  private[this] val nodeGroupRepository = inject[NodeGroupRepository]
	private[this] val groupCategoryRepository = inject[GroupCategoryRepository]
  private[this] val nodeInfoService = inject[NodeInfoService]
  private[this] val categories = groupCategoryRepository.getAllNonSystemCategories
  private[this] val uuidGen = inject[StringUuidGenerator]
  
  var createContainer = false //issue #1190 always create a group by default

  def dispatch = {
    case "popupContent" => { _ => popupContent }
  }

  /**
   * If we create a category, the info about the group is hidden (default), otherwise we show it
   */
 private[this] def initJs : JsCmd = {
  	JsRaw("correctButtons();") & 
  	JsShowId("createGroupHiddable") & 
  	{
  		if (groupGenerator != None) {
  			JsHideId("itemCreation")
  		} else {
  			Noop
  		}
  	}
  }

  def popupContent() : NodeSeq = {
    SHtml.ajaxForm(bind("item", popupTemplate,
      "itemType" -> { groupGenerator match {
        case None =>  SHtml.ajaxRadio(Seq("Category", "Group"), 
            Full("Group"), //issue #1190 always create a group by default
            {selected : String => selected match {
                case "Group" => createContainer = false; JsShowId("createGroupHiddable")
                case _ => createContainer = true; JsHideId("createGroupHiddable")
              }
            }, ("class", "radio")).toForm
        case Some(x) => NodeSeq.Empty
      } },
      "itemName" -> piName.toForm_!,
      "itemContainer" -> piContainer.toForm_!,
      "itemDescription" -> piDescription.toForm_!,
      "groupType" -> piStatic.toForm_!,
      "notifications" -> updateAndDisplayNotifications(),
      "cancel" -> SHtml.ajaxButton("Cancel", { () => closePopup() }) % ("tabindex","4"),
      "save" -> SHtml.ajaxSubmit("Save", onSubmit _) % ("id","createCOGSaveButton") % ("tabindex","3")
    )) ++ Script(OnLoad(initJs)) 
  }

	///////////// fields for category settings ///////////////////
  private[this] val piName = new WBTextField("Name: ", "") {
    override def displayNameHtml = Some(<b>{displayName}</b>)
    override def setFilter = notNull _ :: trim _ :: Nil
    override def className = "twoCol"
    override def errorClassName = ""
    override def inputField = super.inputField %("onkeydown" , "return processKey(event , 'createCOGSaveButton')") % ("tabindex","1")
    override def validations =
      valMinLen(3, "The name must have at least 3 characters") _ :: Nil
  }

  private[this] val piDescription = new WBTextAreaField("Description: ", "") {
    override def setFilter = notNull _ :: trim _ :: Nil
    override def inputField = super.inputField  % ("style" -> "height:10em") % ("tabindex","2")
    override def className = "twoCol"
    override def errorClassName = ""
    override def validations =  Nil

  }

  private[this] val piStatic = new WBRadioField("Group type: ", Seq("static", "dynamic"), "static", {
    //how to display label ? Capitalize, and with a tooltip
    case "static" => <span class="tooltip" title="The list of member nodes is defined at creation and will not change automatically.">Static</span>
    case "dynamic" => <span class="tooltip" title="Nodes will be automatically added and removed so that the list of members always matches this group's search criteria.">Dynamic</span>
  }) {
    override def displayNameHtml = Some(<b>{displayName}</b>)
    override def setFilter = notNull _ :: trim _ :: Nil
    override def className = "twoCol"
  }

  private[this] val piContainer = new WBSelectField("Parent category: ",
			(categories.open_!.map(x => (x.id.value -> x.name))),
			"") {
    override def className = "twoCol"
  }

  private[this] val formTracker = new FormTracker(piName,piDescription,piContainer, piStatic)

  private[this] var notifications = List.empty[NodeSeq]

  private[this] def error(msg:String) = <span class="error">{msg}</span>


  private[this] def closePopup() : JsCmd = {
    JsRaw(""" $.modal.close();""")
  }
  /**
   * Update the form when something happened
   */
	private[this] def updateFormClientSide() : JsCmd = {
    SetHtml("createGroupContainer", popupContent())
  }

  private[this] def onSubmit() : JsCmd = {
    if(formTracker.hasErrors) {
      onFailure & onFailureCallback()
    } else {
      // get the type of query :
      if (createContainer) {
        groupCategoryRepository.addGroupCategorytoCategory(
          new NodeGroupCategory(
            NodeGroupCategoryId(uuidGen.newUuid),
            piName.is,
            piDescription.is,
            Nil,
            Nil
          ),
          NodeGroupCategoryId(piContainer.is)
        ) match {
          case Full(x) => closePopup() & onSuccessCallback(x.id.value) & onSuccessCategory(x)
          case Empty =>
            logger.error("An error occurred while saving the category")
            formTracker.addFormError(error("An error occurred while saving the category"))
            onFailure & onFailureCallback()
          case Failure(m,_,_) =>
            logger.error("An error occurred while saving the category:" + m)
            formTracker.addFormError(error("An error occurred while saving the category: " + m))
            onFailure & onFailureCallback()
        }
      } else {
        // we are creating a group
        nodeGroupRepository.createNodeGroup(
          piName.is,
          piDescription.is,
          groupGenerator.flatMap(_.query),
          {piStatic.is match { case "dynamic" => true ; case _ => false } },
          groupGenerator.map(_.serverList).getOrElse(Set[NodeId]()),
          NodeGroupCategoryId(piContainer.is),
          true,
          CurrentUser.getActor
        ) match {
          case Full(x) =>closePopup() & onSuccessCallback(x.group.id.value) & onSuccessGroup(x.group)
          case Empty =>
            logger.error("An error occurred while saving the group")
            formTracker.addFormError(error("An error occurred while saving the group"))
            onFailure & onFailureCallback()
          case Failure(m,_,_) =>
            logger.error("An error occurred while saving the group:" + m)
            formTracker.addFormError(error("An error occurred while saving the group: " + m))
            onFailure & onFailureCallback()
        }
      }
    }
  }

  private[this] def onCreateSuccess : JsCmd = {
    notifications ::=  <span class="greenscala">The group was successfully created</span>
    updateFormClientSide
  }
  private[this] def onUpdateSuccess : JsCmd = {
    notifications ::=  <span class="greenscala">The group was successfully updated</span>
    updateFormClientSide
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
