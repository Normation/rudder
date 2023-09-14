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
import com.normation.box._
import com.normation.plugins.DefaultExtendableSnippet
import com.normation.rudder.AuthorizationType
import com.normation.rudder.domain.nodes._
import com.normation.rudder.domain.policies._
import com.normation.rudder.domain.queries.Query
import com.normation.rudder.domain.workflows.ChangeRequestId
import com.normation.rudder.repository.FullNodeGroupCategory
import com.normation.rudder.services.workflows.DGModAction
import com.normation.rudder.services.workflows.NodeGroupChangeRequest
import com.normation.rudder.web.ChooseTemplate
import com.normation.rudder.web.components.popup.CreateCloneGroupPopup
import com.normation.rudder.web.components.popup.ModificationValidationPopup
import com.normation.rudder.web.model._
import com.normation.rudder.web.services.CurrentUser
import com.normation.zio._
import net.liftweb.common._
import net.liftweb.http._
import net.liftweb.http.LocalSnippet
import net.liftweb.http.js._
import net.liftweb.http.js.JE._
import net.liftweb.http.js.JsCmds._
import net.liftweb.util.CssSel
import net.liftweb.util.Helpers._
import scala.xml._

object NodeGroupForm {
  val templatePath = "templates-hidden" :: "components" :: "NodeGroupForm" :: Nil

  val staticInit = ChooseTemplate(templatePath, "component-staticinit")
  val body       = ChooseTemplate(templatePath, "component-body")
  val staticBody = ChooseTemplate(templatePath, "component-staticbody")

  private val saveButtonId = "groupSaveButtonId"

  sealed private trait RightPanel
  private case object NoPanel                                        extends RightPanel
  final private case class GroupForm(group: Either[NonGroupRuleTarget, NodeGroup], parentCategoryId: NodeGroupCategoryId)
      extends RightPanel
  final private case class CategoryForm(category: NodeGroupCategory) extends RightPanel

  val htmlId_groupTree           = "groupTree"
  val htmlId_item                = "ajaxItemContainer"
  val htmlId_updateContainerForm = "updateContainerForm"
}

/**
 * The form that deals with updating the server group
 */
class NodeGroupForm(
    htmlIdCategory:    String,
    val nodeGroup:     Either[NonGroupRuleTarget, NodeGroup],
    parentCategoryId:  NodeGroupCategoryId,
    rootCategory:      FullNodeGroupCategory,
    onSuccessCallback: (Either[(Either[NonGroupRuleTarget, NodeGroup], NodeGroupCategoryId), ChangeRequestId]) => JsCmd = {
      (NodeGroup) => Noop
    },
    onFailureCallback: () => JsCmd = { () => Noop }
) extends DispatchSnippet with DefaultExtendableSnippet[NodeGroupForm] with Loggable {
  import NodeGroupForm._

  private[this] val nodeInfoService            = RudderConfig.nodeInfoService
  private[this] val categoryHierarchyDisplayer = RudderConfig.categoryHierarchyDisplayer
  private[this] val workflowLevelService       = RudderConfig.workflowLevelService
  private[this] val dependencyService          = RudderConfig.dependencyAndDeletionService
  private[this] val roNodeGroupRepository      = RudderConfig.roNodeGroupRepository

  private[this] val nodeGroupCategoryForm = new LocalSnippet[NodeGroupCategoryForm]
  private[this] val nodeGroupForm         = new LocalSnippet[NodeGroupForm]
  private[this] val searchNodeComponent   = new LocalSnippet[SearchNodeComponent]

  private[this] var query:   Option[Query]      = nodeGroup.toOption.flatMap(_.query)
  private[this] var srvList: Box[Seq[NodeInfo]] = getNodeList(nodeGroup)

  private def setSearchNodeComponent: Unit = {
    searchNodeComponent.set(
      Full(
        new SearchNodeComponent(
          htmlIdCategory,
          query,
          srvList,
          onSearchCallback = saveButtonCallBack,
          onClickCallback = None,
          saveButtonId = saveButtonId,
          groupPage = false
        )
      )
    )
  }

  private[this] def getNodeList(target: Either[NonGroupRuleTarget, NodeGroup]): Box[Seq[NodeInfo]] = {

    for {
      nodes <- nodeInfoService.getAll().toBox
      setIds = target match {
                 case Right(nodeGroup) => nodeGroup.serverList
                 case Left(target)     =>
                   val allNodes = nodes.view.mapValues(n => n.isPolicyServer).toMap
                   RuleTarget.getNodeIds(Set(target), allNodes, Map())
               }
    } yield {
      nodes.view.filterKeys(id => setIds.contains(id)).values.toSeq
    }
  }

  private[this] def saveButtonCallBack(searchStatus: Boolean, query: Option[Query]): JsCmd = {
    JsRaw(s"""$$('#${saveButtonId}').button();
        $$('#${saveButtonId}').button("option", "disabled", ${searchStatus});""")
  }

  setSearchNodeComponent

  def mainDispatch = Map(
    "showForm"  -> { _: NodeSeq => showForm() },
    "showGroup" -> { _: NodeSeq =>
      searchNodeComponent.get match {
        case Full(component) => component.buildQuery(true)
        case _               => <div>The component is not set</div>
      }
    }
  )

  val pendingChangeRequestXml = {
    <div id="pendingChangeRequestNotification">
      <div class="row">
        <i class="fa fa-exclamation-triangle warnicon" aria-hidden="true"></i>
        <div class="col-md-10">
          The following pending change requests affect this Group, you should check that your modification is not already pending:
          <ul id="changeRequestList"/>
        </div>
      </div>
    </div>
  }

  def showForm(): NodeSeq = {
    val html = SHtml.ajaxForm(body) ++
      Script(
        OnLoad(JsRaw("$('#GroupTabs').tabs( {active : 0 });"))
      )

    (nodeGroup match {
      case Left(target)                     => showFormTarget(target)
      case Right(group) if (group.isSystem) => showFormTarget(GroupTarget(group.id))
      case Right(group)                     =>
        showFormNodeGroup(group)
    })(html)
  }

  private[this] def showRulesForTarget(target: SimpleTarget): NodeSeq = {
    val displayCompliance = DisplayColumn.FromConfig
    val ruleGrid          = new RuleGrid("rules_for_current_group", None, false, None, displayCompliance, displayCompliance)
    val rules             = dependencyService.targetDependencies(target).map(_.rules.toSet.filter(!_.isSystem).map(_.id)).toOption
    ruleGrid.rulesGridWithUpdatedInfo(None, false, false) ++ Script(OnLoad(ruleGrid.asyncDisplayAllRules(rules).applied))
  }
  private[this] val groupNameString = nodeGroup.fold(
    t => rootCategory.allTargets.get(t).map(_.name).getOrElse(t.target),
    _.name
  )
  private[this] def showFormNodeGroup(nodeGroup: NodeGroup):  CssSel  = {
    val nodesSel = "#gridResult" #> NodeSeq.Empty
    val nodes    = nodesSel(searchNodeComponent.get match {
      case Full(req) => req.buildQuery(true)
      case eb: EmptyBox => <span class="error">Error when retrieving the request, please try again</span>
    })
    (
      "#group-name" #> groupNameString
      & "group-pendingchangerequest" #> PendingChangeRequestDisplayer.checkByGroup(pendingChangeRequestXml, nodeGroup.id)
      & "group-name" #> groupName.toForm_!
      & "group-rudderid" #> <div class="form-group row">
                      <label class="wbBaseFieldLabel">Group ID</label>
                      <input readonly="" class="form-control" value={nodeGroup.id.serialize}/>
                    </div>
      & "group-cfeclasses" #> <div class="form-group row">
                          <label class="wbBaseFieldLabel toggle-cond cond-hidden" onclick="$(this).toggleClass('cond-hidden')"><span class="text-fit">Agent conditions</span><i class="fa fa-chevron-down"></i></label>
                          <div class="well" id={s"cfe-${nodeGroup.id.serialize}"}>
                            {RuleTarget.toCFEngineClassName(nodeGroup.id.serialize)}<br/>
                            {RuleTarget.toCFEngineClassName(nodeGroup.name)}
                          </div>
                        </div>
      & "#longDescriptionField *" #> (groupDescription.toForm_! ++ Script(
        OnLoad(JsRaw(s"""setupMarkdown(${Str(nodeGroup.description).toJsCmd}, "longDescriptionField")"""))
      ))
      & "group-container" #> groupContainer.toForm_!
      & "group-static" #> groupStatic.toForm_!
      & "group-showgroup" #> nodes
      & "group-clone" #> {
        if (CurrentUser.checkRights(AuthorizationType.Group.Write)) {
          SHtml.ajaxButton(
            <span>Clone <i class="fa fa-clone"></i></span>,
            () => showCloneGroupPopup()
          ) % ("id" -> "groupCloneButtonId") % ("class" -> " btn btn-default btn-icon")
        } else NodeSeq.Empty
      }
      & "group-save" #> {
        if (CurrentUser.checkRights(AuthorizationType.Group.Edit)) {
          <span class="save-tooltip-container">
                      {
            SHtml.ajaxOnSubmit(onSubmit _)(
              <button class="btn btn-success btn-icon ui-button ui-corner-all ui-widget" id={saveButtonId}>
                            <span>Save <i class="fa fa-download"></i></span>
                          </button>
            )
          }
                      <span class="save-tooltip bsTooltip" title="Your 'Save' button is disabled, it means that you have updated the query without Searching for new Nodes. Please click on 'Search' to enable saving again"></span>
                    </span>
        } else NodeSeq.Empty
      }
      & "group-delete" #> SHtml.ajaxButton(
        <span>Delete <i class="fa fa-times-circle"></i></span>,
        () => onSubmitDelete(),
        ("class" -> " btn btn-danger btn-icon")
      )
      & "group-notifications" #> updateAndDisplayNotifications()
      & "#groupRuleTabsContent" #> showRulesForTarget(GroupTarget(nodeGroup.id))
      & "#groupPropertiesTabContent" #> showGroupProperties(nodeGroup)
      & "#group-shownodestable *" #> (searchNodeComponent.get match {
        case Full(req) => req.displayNodesTable
        case eb: EmptyBox =>
          <span class="error">Error when retrieving the request, please try again</span>
      })
    )
  }

  private[this] def showFormTarget(target: SimpleTarget): CssSel = {
    ("group-pendingchangerequest" #> NodeSeq.Empty
    & "#group-name" #> <span>{groupNameString}<span class="group-system"></span></span>
    & "group-name" #> groupName.readOnlyValue
    & "#groupTabMenu" #> <ul id="groupTabMenu">
                           <li><a href="#groupParametersTab">Parameters</a></li>
                           <li><a href="#groupRulesTab">Related rules</a></li>
                         </ul>
    & "group-rudderid" #> <div class="form-group row">
                    <label class="wbBaseFieldLabel">Group ID</label>
                    <input readonly="" class="form-control" value={target.target}/>
                  </div>
    & "group-cfeclasses" #> NodeSeq.Empty
    & "#longDescriptionField" #> (groupDescription.toForm_! ++ Script(
      JsRaw(s"""setupMarkdown(${Str(groupDescription.defaultValue).toJsCmd}, "longDescriptionField")""")
    ))
    & "group-container" #> groupContainer.readOnlyValue
    & "group-static" #> NodeSeq.Empty
    & "group-showgroup" #> NodeSeq.Empty
    & "group-clone" #> NodeSeq.Empty
    & "group-save" #> NodeSeq.Empty
    & "group-delete" #> NodeSeq.Empty
    & "group-notifications" #> NodeSeq.Empty
    & "#groupRuleTabsContent" #> showRulesForTarget(target)
    & "#group-shownodestable *" #> (searchNodeComponent.get match {
      case Full(req) => req.displayNodesTable
      case eb: EmptyBox =>
        <span class="error">Error when retrieving the request, please try again</span>
    }))
  }

  def showGroupProperties(group: NodeGroup): NodeSeq = {

    val intro = (<div class="info">
        <h4>Hierarchy of group and unicity of property name</h4>
        <p>A group property with a given name can be defined in several group if and only if these
        groups are in a sub-group hierarchical relation. A group 'S' is a subgroup of group 'P' if
          'S' uses the <code>'AND'</code>
          operand with the <code>'Group > Group ID = P'</code>
          criterion.</p>
      </div>
      <div class="info">
        <h4>Property inheritance and overriding in hierarchy</h4>
        <p>When a group is a subgroup of another one, it inherit all its properties. If it defines
        a property with the same name than a parent, then that property value is overriden and
        the subgroup value is used. A node can also define a property with the same name, and in
        that case its value will override any group's value for that property name.</p>
      </div>
      <div class="info">
        <h4>Overriding order between several parents</h4>
        <p>If a group is a subgroup of several groups, then the overriding is done in the
        same order than groups are defined in criteria lines: the last group define the
        group whose value will be chosen among parents.</p>
      </div>)

    def tabProperties = ChooseTemplate(List("templates-hidden", "components", "ComponentNodeProperties"), "nodeproperties-tab")
    intro ++ tabProperties ++ Script(OnLoad(JsRaw(s"""
            var main = document.getElementById("nodeproperties-app")
                                                     |var initValues = {
                                                     |    contextPath    : "${S.contextPath}"
                                                     |  , hasWriteRights : hasWriteRights
                                                     |  , hasReadRights  : hasReadRights
                                                     |  , nodeId         : "${group.id.uid.value}"
                                                     |  , objectType     : 'group'
                                                     |};
                                                     |var app = Elm.Nodeproperties.init({node: main, flags: initValues});
                                                     |app.ports.successNotification.subscribe(function(str) {
                                                     |  createSuccessNotification(str)
                                                     |});
                                                     |app.ports.errorNotification.subscribe(function(str) {
                                                     |  createErrorNotification(str)
                                                     |});
                                                     |// Initialize tooltips
                                                     |app.ports.initTooltips.subscribe(function(msg) {
                                                     |  setTimeout(function(){
                                                     |    $$('.bs-tooltip').bsTooltip();
                                                     |  }, 400);
                                                     |});
                                                     |app.ports.copy.subscribe(function(str) {
                                                     |  navigator.clipboard.writeText(str);
                                                     |});
                                                     |app.ports.initInputs.subscribe(function(str) {
                                                     |  setTimeout(function(){
                                                     |    $$(".auto-resize").on("input", autoResize).each(function(){
                                                     |      autoResize(this);
                                                     |    });
                                                     |  }, 10);
                                                     |});
                                                     |""".stripMargin)))
  }

  ///////////// fields for category settings ///////////////////

  private[this] val groupName = {
    new WBTextField("Group name", groupNameString) {
      override def setFilter             = notNull _ :: trim _ :: Nil
      override def className             = "form-control"
      override def labelClassName        = ""
      override def subContainerClassName = ""
      override def inputField            = super.inputField % ("onkeydown" -> "return processKey(event , '%s')".format(saveButtonId))
      override def validations           =
        valMinLen(1, "Name must not be empty") _ :: Nil
    }
  }

  private[this] val groupDescription = {
    val desc = nodeGroup.fold(
      t => rootCategory.allTargets.get(t).map(_.description).getOrElse(""),
      _.description
    )
    new WBTextAreaField("Description", desc) {
      override def setFilter             = notNull _ :: trim _ :: Nil
      override def className             = "form-control"
      override def labelClassName        = "row col-xs-12"
      override def subContainerClassName = "row col-xs-12"
      override def containerClassName    = "col-xs-6"
      override def errorClassName        = "field_errors paddscala"
      override def inputAttributes: Seq[(String, String)] = Seq(("rows", "15"))
      override def labelExtensions: NodeSeq               = {
        <i class="fa fa-check text-success cursorPointer half-opacity"     onmouseenter="toggleOpacity(this)" title="Valid description" onmouseout="toggleOpacity(this)" onclick="toggleMarkdownEditor('longDescriptionField')"></i> ++ Text(
          " "
        ) ++
        <i class="fa fa-eye-slash text-primary cursorPointer half-opacity" onmouseenter="toggleOpacity(this)" title="Show/hide preview" onmouseout="toggleOpacity(this)" onclick="togglePreview(this, 'longDescriptionField')"></i>
      }

    }
  }

  private[this] val groupStatic = {
    val text = nodeGroup match {
      case Left(_)  => "dynamic"
      case Right(g) => if (g.isDynamic) "dynamic" else "static"
    }

    new WBRadioField(
      "Group type",
      Seq("dynamic", "static"),
      text,
      {
        // how to display label ? Capitalize, and with a tooltip
        case "static"  =>
          <span class="" title="The list of member nodes is defined at creation and will not change automatically.">Static</span>
        case "dynamic" =>
          <span class="" title="Nodes will be automatically added and removed so that the list of members always matches this group's search criteria.">Dynamic</span>
        case _         => NodeSeq.Empty // guarding against NoMatchE
      }
    ) {
      override def setFilter             = notNull _ :: trim _ :: Nil
      override def className             = "switch"
      override def labelClassName        = ""
      override def subContainerClassName = ""
    }
  }

  private[this] val groupContainer = new WBSelectField(
    "Category",
    (categoryHierarchyDisplayer.getCategoriesHierarchy(rootCategory, None).map { case (id, name) => (id.value -> name) }),
    parentCategoryId.value
  ) {
    override def className             = "form-control"
    override def labelClassName        = ""
    override def subContainerClassName = ""
  }

  private[this] val formTracker = new FormTracker(List(groupName, groupDescription, groupContainer, groupStatic))

  private[this] def updateFormClientSide(): JsCmd = {
    SetHtml(htmlIdCategory, showForm())
  }

  private[this] def error(msg: String) = <span class="error">{msg}</span>

  private[this] def onFailure: JsCmd = {
    formTracker.addFormError(error("There was a problem with your request."))
    updateFormClientSide() & JsRaw("""scrollToElement("errorNotification","#ajaxItemContainer");""")
  }

  private[this] def onSubmit(): JsCmd = {
    // submit can be done only for node group, not system one
    nodeGroup match {
      case Left(target) => Noop
      case Right(ng)    =>
        // properties can have been modifier since the page was displayed, but we don't know it.
        // so we look back for them. We also check that other props weren't modified in parallel
        val savedGroup = roNodeGroupRepository.getNodeGroup(ng.id).either.runNow match {
          case Right(g)  => g._1
          case Left(err) =>
            formTracker.addFormError(Text("Error when saving group"))
            logger.error(s"Error when looking for group with id '${ng.id.serialize}': ${err.fullMsg}")
            ng
        }
        if (ng.copy(properties = savedGroup.properties, serverList = savedGroup.serverList) != savedGroup) {
          formTracker.addFormError(Text("Error: group was updated while you were modifying it. Please reload the page. "))
        }

        // Since we are doing the submit from the component, it ought to exist
        searchNodeComponent.get match {
          case Full(req) =>
            query = req.getQuery()
            srvList = req.getSrvList()
          case eb: EmptyBox =>
            val f = eb ?~! "Error when trying to retrieve the current search state"
            logger.error(f.messageChain)
        }

        if (formTracker.hasErrors) {
          onFailure & onFailureCallback()
        } else {
          val optContainer = {
            val c = NodeGroupCategoryId(groupContainer.get)
            if (c == parentCategoryId) None
            else Some(c)
          }

          // submit can be done only for node group, not system one
          val newGroup = savedGroup.copy(
            name = groupName.get,
            description = groupDescription.get, // , container = container

            isDynamic = groupStatic.get match { case "dynamic" => true; case _ => false },
            query = query,
            serverList = srvList.getOrElse(Set()).map(_.id).toSet
          )

          if (newGroup == savedGroup && optContainer.isEmpty) {
            formTracker.addFormError(Text("There are no modifications to save"))
            onFailure & onFailureCallback()
          } else {
            displayConfirmationPopup(DGModAction.Update, newGroup, optContainer)
          }
        }
    }
  }

  private[this] def onSubmitDelete(): JsCmd = {
    nodeGroup match {
      case Left(_)   => Noop
      case Right(ng) =>
        displayConfirmationPopup(DGModAction.Delete, ng, None)
    }
  }

  /*
   * Create the confirmation pop-up
   */

  private[this] def displayConfirmationPopup(
      action:      DGModAction,
      newGroup:    NodeGroup,
      newCategory: Option[NodeGroupCategoryId]
  ): JsCmd = {

    val optOriginal = nodeGroup.toOption
    val change      = NodeGroupChangeRequest(action, newGroup, newCategory, optOriginal)

    workflowLevelService.getForNodeGroup(CurrentUser.actor, change) match {
      case eb: EmptyBox =>
        val msg = s"Error when getting the validation workflow for changes in directive '${change.newGroup.name}'"
        logger.warn(msg, eb)
        JsRaw(s"alert('${msg}')")

      case Full(workflowService) =>
        val popup = {

          def successCallback(crId: ChangeRequestId) = {
            if (workflowService.needExternalValidation()) {
              onSuccessCallback(Right(crId))
            } else {
              val updateCategory = newCategory.getOrElse(parentCategoryId)
              successPopup & onSuccessCallback(Left((Right(newGroup), updateCategory))) &
              (if (action == DGModAction.Delete)
                 SetHtml(htmlId_item, NodeSeq.Empty)
               else
                 Noop)
            }
          }
          new ModificationValidationPopup(
            Right(change),
            workflowService,
            crId => JsRaw("$('#confirmUpdateActionDialog').bsModal('hide');") & successCallback(crId),
            xml => JsRaw("$('#confirmUpdateActionDialog').bsModal('hide');") & onFailure,
            parentFormTracker = formTracker
          )
        }

        popup.popupWarningMessages match {
          case None    =>
            popup.onSubmit()
          case Some(_) =>
            SetHtml("confirmUpdateActionDialog", popup.popupContent()) &
            JsRaw("""createPopup("confirmUpdateActionDialog")""")
        }
    }
  }

  def createPopup(name: String):           JsCmd = {
    JsRaw(s"""createPopup("${name}");""")
  }
  private[this] def showCloneGroupPopup(): JsCmd = {

    val popupSnippet = new LocalSnippet[CreateCloneGroupPopup]
    popupSnippet.set(
      Full(
        new CreateCloneGroupPopup(
          nodeGroup.toOption,
          onSuccessCategory = displayACategory,
          onSuccessGroup = showGroupSection
        )
      )
    )
    val nodeSeqPopup = popupSnippet.get match {
      case Failure(m, _, _) => <span class="error">Error: {m}</span>
      case Empty            => <div>The component is not set</div>
      case Full(popup)      => popup.popupContent()
    }
    SetHtml("createCloneGroupContainer", nodeSeqPopup) &
    createPopup("createCloneGroupPopup")
  }

  private[this] def displayACategory(category: NodeGroupCategory): JsCmd = {
    refreshRightPanel(CategoryForm(category))
  }

  private[this] def refreshRightPanel(panel: RightPanel): JsCmd = SetHtml(htmlId_item, setAndShowRightPanel(panel))

  /**
   *  Manage the state of what should be displayed on the right panel.
   * It could be nothing, a group edit form, or a category edit form.
   */
  private[this] def setAndShowRightPanel(panel: RightPanel): NodeSeq = {
    panel match {
      case NoPanel                 => NodeSeq.Empty
      case GroupForm(group, catId) =>
        val form = new NodeGroupForm(htmlId_item, group, catId, rootCategory, onSuccessCallback)
        nodeGroupForm.set(Full(form))
        form.showForm()

      case CategoryForm(category) =>
        val form = new NodeGroupCategoryForm(htmlId_item, category, rootCategory) // , onSuccessCallback)
        nodeGroupCategoryForm.set(Full(form))
        form.showForm()
    }
  }

  private[this] def showGroupSection(
      group:            Either[NonGroupRuleTarget, NodeGroup],
      parentCategoryId: NodeGroupCategoryId
  ): JsCmd = {
    val js = group match {
      case Left(target) => s"'target':'${target.target}"
      case Right(g)     => s"'groupId':'${g.id.serialize}'"
    }
    // update UI
    onSuccessCallback(Left((group, parentCategoryId))) &
    JsRaw(s"""this.window.location.hash = "#" + JSON.stringify({${js}})""")
  }

  private[this] def updateAndDisplayNotifications(): NodeSeq = {

    val notifications = formTracker.formErrors
    formTracker.cleanErrors

    if (notifications.isEmpty) {
      NodeSeq.Empty
    } else {
      val html = {
        <div id="errorNotification" class="notify">
          <ul class="field_errors">{notifications.map(n => <li>{n}</li>)}</ul>
        </div>
      }
      html
    }
  }

  private[this] def successPopup: JsCmd = {
    JsRaw("""createSuccessNotification()""")
  }

}
