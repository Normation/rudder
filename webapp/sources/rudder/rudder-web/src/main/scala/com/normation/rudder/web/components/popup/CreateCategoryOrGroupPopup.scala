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
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.nodes.*
import com.normation.rudder.domain.policies.NonGroupRuleTarget
import com.normation.rudder.repository.*
import com.normation.rudder.users.CurrentUser
import com.normation.rudder.web.ChooseTemplate
import com.normation.rudder.web.model.FormTracker
import com.normation.rudder.web.model.WBRadioField
import com.normation.rudder.web.model.WBSelectField
import com.normation.rudder.web.model.WBTextAreaField
import com.normation.rudder.web.model.WBTextField
import net.liftweb.common.*
import net.liftweb.http.DispatchSnippet
import net.liftweb.http.S
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
class CreateCategoryOrGroupPopup(
    groupGenerator:    Option[NodeGroup],
    rootCategory:      FullNodeGroupCategory,
    selectedCategory:  Option[NodeGroupCategoryId],
    onSuccessCategory: (NodeGroupCategory) => JsCmd,
    onSuccessGroup:    (Either[NonGroupRuleTarget, NodeGroup], NodeGroupCategoryId) => JsCmd,
    onSuccessCallback: (String) => JsCmd = { _ => Noop },
    onFailureCallback: () => JsCmd = { () => Noop }
) extends DispatchSnippet with Loggable {

  // Load the template from the popup
  def popupTemplate: NodeSeq = ChooseTemplate(
    List("templates-hidden", "Popup", "createCategoryOrGroup"),
    "groups-creategrouppopup"
  )

  private val woNodeGroupRepository      = RudderConfig.woNodeGroupRepository
  private val propertiesService          = RudderConfig.propertiesService
  private val categoryHierarchyDisplayer = RudderConfig.categoryHierarchyDisplayer
  private val uuidGen                    = RudderConfig.stringUuidGenerator
  private val userPropertyService        = RudderConfig.userPropertyService

  var createContainer = false // issue #1190 always create a group by default

  def dispatch: PartialFunction[String, NodeSeq => NodeSeq] = { case "popupContent" => { _ => popupContent() } }

  /**
   * If we create a category, the info about the group is hidden (default), otherwise we show it
   */
  private def initJs: JsCmd = {
    JsRaw("""
        $('input[value="Group"]').click(
          function() {
            $('#createGroupHiddable').show();;
            $('#itemTitle').text('Group');
          }
        );

        $('input[value="Category"]').click(
          function() {
            $('#createGroupHiddable').hide();
            $('#itemTitle').text('Category');
          }
        );
     """) // JsRaw ok, const
  }

  def popupContent(): NodeSeq = {
    S.appendJs(initJs)
    val form = {
      ("item-itemtype" #> {
        groupGenerator match {
          case None    => piItemType.toForm_!
          case Some(x) => NodeSeq.Empty
        }
      }
      & "#itemTitle *" #> piItemType.get
      & "item-itemname" #> piName.toForm_!
      & "item-itemcontainer" #> piContainer.toForm_!
      & "item-itemdescription" #> piDescription.toForm_!
      & "item-grouptype" #> piStatic.toForm_!
      & "item-itemreason" #> {
        piReasons.map { f =>
          <div>
            <h4 class="col-xl-12 col-md-12 col-sm-12 audit-title">Change Audit Log</h4>
            {f.toForm_!}
          </div>
        }
      }
      & "item-cancel" #> (SHtml.ajaxButton("Cancel", () => closePopup()) % ("tabindex" -> "6")                   % ("class"    -> "btn btn-default"))
      & "item-save" #> (SHtml.ajaxSubmit(
        "Create",
        onSubmit
      )                                                                  % ("id"       -> "createCOGSaveButton") % ("tabindex" -> "5") % ("class" -> "btn btn-success"))
      andThen
      // Updating notification should be done at the end because it empties the form tracker
      // and thus if done at the same time of other css selectors it will emtpy the field errors
      "item-notifications" #> updateAndDisplayNotifications())(popupTemplate)
    }
    SHtml.ajaxForm(form)
  }

  ///////////// fields for category settings ///////////////////
  private val piName = new WBTextField("Name", "") {
    override def setFilter      = notNull :: trim :: Nil
    override def errorClassName = "col-xl-12 errors-container"
    override def inputField     =
      super.inputField % ("onkeydown" -> "return processKey(event , 'createCOGSaveButton')") % ("tabindex" -> "2") % ("autofocus" -> "true")
    override def validations =
      valMinLen(1, "Name must not be empty.") :: Nil
  }

  private val piDescription = new WBTextAreaField("Description", "") {
    override def setFilter      = notNull :: trim :: Nil
    override def inputField     = super.inputField % ("style" -> "height:5em") % ("tabindex" -> "4")
    override def errorClassName = "col-xl-12 errors-container"
    override def validations: List[String => List[FieldError]] = Nil

  }

  private val piStatic = new WBRadioField(
    "Group type",
    Seq("dynamic", "static"),
    "dynamic",
    {
      // how to display label ? Capitalize, and with a tooltip
      case "static"  =>
        <span title="The list of member nodes is defined at creation and will not change automatically.">
        Static
      </span>
      case "dynamic" =>
        <span title="Nodes will be automatically added and removed so that the list of members always matches this group's search criteria.">Dynamic</span>
    },
    Some(5)
  ) {
    override def setFilter      = notNull :: trim :: Nil
    override def className      = "align-radio-generate-input"
    override def errorClassName = "col-xl-12 errors-container"
    override def inputField     = super.inputField % ("onkeydown" -> "return processKey(event , 'createCOGSaveButton')")
    override def validations    =
      valMinLen(1, "Please choose a group type.") :: Nil
  }

  private val piItemType = {
    new WBRadioField(
      "Item to create",
      Seq("Group", "Category"),
      "Group",
      {
        case "Group"    =>
          <span id="textGroupRadio">Group</span>
        case "Category" =>
          <span id="textCategoryRadio">Category</span>
      },
      Some(1)
    ) {
      override def setFilter      = notNull :: trim :: Nil
      override def className      = "align-radio-generate-input"
      override def errorClassName = "col-xl-12 errors-container"
      override def inputField     = super.inputField % ("onkeydown" -> "return processKey(event , 'createCOGSaveButton')")
      override def validations    =
        valMinLen(1, "Please choose between group or category.") :: Nil
    }
  }

  private val piContainer = new WBSelectField(
    "Parent category",
    (categoryHierarchyDisplayer.getCategoriesHierarchy(rootCategory, None).map { case (id, name) => (id.value -> name) }),
    selectedCategory.map(_.value).getOrElse("")
  ) {
    override def errorClassName = "col-xl-12 errors-container"
    override def className      = "col-xl-12 col-md-12 col-sm-12 form-select"
    override def inputField     =
      super.inputField % ("onkeydown" -> "return processKey(event , 'createCOGSaveButton')") % ("tabindex" -> "3")
    override def validations =
      valMinLen(1, "Please select a category") :: Nil
  }

  private val formTracker = new FormTracker(piName, piDescription, piContainer, piStatic)

  private var notifications = List.empty[NodeSeq]

  private def error(msg: String) = Text(msg)

  private def closePopup(): JsCmd = {
    JsRaw("""hideBsModal('createGroupPopup');""") // JsRaw ok, const
  }

  /**
   * Update the form when something happened
   */
  private def updateFormClientSide(): JsCmd = {
    SetHtml("createGroupContainer", popupContent())
  }

  private def onSubmit(): JsCmd = {
    if (formTracker.hasErrors) {
      onFailure & onFailureCallback()
    } else {
      val createCategory = piItemType.get match {
        case "Group"    => false
        case "Category" => true
      }
      if (createCategory) {
        woNodeGroupRepository
          .addGroupCategorytoCategory(
            new NodeGroupCategory(
              NodeGroupCategoryId(uuidGen.newUuid),
              piName.get,
              piDescription.get,
              Nil,
              Nil
            ),
            NodeGroupCategoryId(piContainer.get),
            ModificationId(uuidGen.newUuid),
            CurrentUser.actor,
            piReasons.map(_.get)
          )
          .toBox match {
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
      } else {
        val query     = None
        val isDynamic = piStatic.get match { case "dynamic" => true; case _ => false }
        val srvList   = groupGenerator.map(_.serverList).getOrElse(Set[NodeId]())
        val nodeId    = NodeGroupId(NodeGroupUid(uuidGen.newUuid))
        val nodeGroup = NodeGroup(nodeId, piName.get, piDescription.get, Nil, query, isDynamic, srvList, _isEnabled = true)
        woNodeGroupRepository
          .create(
            nodeGroup,
            NodeGroupCategoryId(piContainer.get),
            ModificationId(uuidGen.newUuid),
            CurrentUser.actor,
            piReasons.map(_.get)
          )
          .tap(_ => propertiesService.updateAll())
          .toBox match {
          case Full(x)          =>
            closePopup() &
            onSuccessCallback(x.group.id.serialize) & onSuccessGroup(
              Right(x.group),
              NodeGroupCategoryId(piContainer.get)
            ) & OnLoad(JsRaw("""$("[href='#groupCriteriaTab']").click();""")) // JsRaw ok, const
          case Empty            =>
            logger.error("An error occurred while saving the group")
            formTracker.addFormError(error("An error occurred while saving the group"))
            onFailure & onFailureCallback()
          case Failure(m, _, _) =>
            logger.error("An error occurred while saving the group: " + m)
            formTracker.addFormError(error(m))
            onFailure & onFailureCallback()
        }
      }
    }
  }

  private val piReasons = {
    import com.normation.rudder.config.ReasonBehavior.*
    userPropertyService.reasonsFieldBehavior match {
      case Disabled  => None
      case Mandatory => Some(buildReasonField(true, "px-1"))
      case Optional  => Some(buildReasonField(false, "px-1"))
    }
  }

  def buildReasonField(mandatory: Boolean, containerClass: String = "twoCol"): WBTextAreaField = {
    new WBTextAreaField("Change audit message", "") {
      override def setFilter  = notNull :: trim :: Nil
      override def inputField = super.inputField %
        ("style" -> "height:5em;") % ("placeholder" -> { userPropertyService.reasonsFieldExplanation })
      override def errorClassName = "col-xl-12 errors-container"
      override def validations    = {
        if (mandatory) {
          valMinLen(5, "The reason must have at least 5 characters.") :: Nil
        } else {
          Nil
        }
      }
    }
  }

  private def onFailure: JsCmd = {
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
