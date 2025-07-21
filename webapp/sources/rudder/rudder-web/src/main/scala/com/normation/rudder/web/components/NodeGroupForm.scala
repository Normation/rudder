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
import com.normation.errors.Inconsistency
import com.normation.errors.IOResult
import com.normation.plugins.DefaultExtendableSnippet
import com.normation.rudder.AuthorizationType
import com.normation.rudder.apidata.implicits.*
import com.normation.rudder.domain.nodes.*
import com.normation.rudder.domain.policies.*
import com.normation.rudder.domain.queries.*
import com.normation.rudder.domain.reports.ComplianceLevelSerialisation
import com.normation.rudder.domain.workflows.ChangeRequestId
import com.normation.rudder.facts.nodes.CoreNodeFact
import com.normation.rudder.facts.nodes.QueryContext
import com.normation.rudder.repository.FullNodeGroupCategory
import com.normation.rudder.services.workflows.DGModAction
import com.normation.rudder.services.workflows.NodeGroupChangeRequest
import com.normation.rudder.users.CurrentUser
import com.normation.rudder.web.ChooseTemplate
import com.normation.rudder.web.components.popup.CreateCloneGroupPopup
import com.normation.rudder.web.components.popup.ModificationValidationPopup
import com.normation.rudder.web.model.*
import com.normation.zio.UnsafeRun
import net.liftweb.common.*
import net.liftweb.http.*
import net.liftweb.http.js.*
import net.liftweb.http.js.JE.*
import net.liftweb.http.js.JsCmds.*
import net.liftweb.util.*
import net.liftweb.util.Helpers.*
import org.apache.commons.text.StringEscapeUtils
import scala.xml.*
import zio.ZIO
import zio.json.*
import zio.json.ast.*
import zio.syntax.*

object NodeGroupForm {
  val templatePath: List[String] = "templates-hidden" :: "components" :: "NodeGroupForm" :: Nil

  val staticInit: NodeSeq = ChooseTemplate(templatePath, "component-staticinit")
  val body:       NodeSeq = ChooseTemplate(templatePath, "component-body")
  val staticBody: NodeSeq = ChooseTemplate(templatePath, "component-staticbody")

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
  import NodeGroupForm.*
  implicit private val qc: QueryContext = CurrentUser.queryContext

  private val nodeFactRepo               = RudderConfig.nodeFactRepository
  private val categoryHierarchyDisplayer = RudderConfig.categoryHierarchyDisplayer
  private val workflowLevelService       = RudderConfig.workflowLevelService
  private val dependencyService          = RudderConfig.dependencyAndDeletionService
  private val roNodeGroupRepository      = RudderConfig.roNodeGroupRepository
  private val complianceService          = RudderConfig.complianceService
  private val ruleRepository             = RudderConfig.roRuleRepository

  private val nodeGroupCategoryForm = new LocalSnippet[NodeGroupCategoryForm]
  private val nodeGroupForm         = new LocalSnippet[NodeGroupForm]
  private val searchNodeComponent   = new LocalSnippet[SearchNodeComponent]

  private var query:   Option[Query]          = nodeGroup.toOption.flatMap(_.query)
  private var srvList: Box[Seq[CoreNodeFact]] = getNodeList(nodeGroup)(CurrentUser.queryContext)

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

  private def getNodeList(target: Either[NonGroupRuleTarget, NodeGroup])(implicit qc: QueryContext): Box[Seq[CoreNodeFact]] = {

    for {
      nodes <- nodeFactRepo.getAll().toBox
      setIds = target match {
                 case Right(nodeGroup_) => nodeGroup_.serverList
                 case Left(target)      =>
                   val allNodes = nodes.mapValues(_.rudderSettings.kind.isPolicyServer)
                   RuleTarget.getNodeIds(Set(target), allNodes, Map())
               }
    } yield {
      nodes.filterKeys(id => setIds.contains(id)).map(_._2).toSeq
    }
  }

  private def saveButtonCallBack(searchStatus: Boolean, query: Option[Query]): JsCmd = {
    JsRaw(s"""$$('#${saveButtonId}').prop("disabled", ${searchStatus})""") // JsRaw ok, no string input
  }

  setSearchNodeComponent

  def mainDispatch: Map[String, NodeSeq => NodeSeq] = Map(
    "showForm"  -> { (_: NodeSeq) => showForm() },
    "showGroup" -> { (_: NodeSeq) =>
      searchNodeComponent.get match {
        case Full(component) => component.buildQuery(true)
        case _               => <div>The component is not set</div>
      }
    }
  )

  val pendingChangeRequestXml: Elem = {
    <div class="callout-fade callout-info callout-cr" id="pendingChangeRequestNotification">
        <p><b><i class="fa fa-info-circle"></i>Pending change requests</b><span class="fa fa-chevron-down" onclick="$('#pendingChangeRequestNotification').toggleClass('list-hidden')"></span></p>
        <div>
          <p>The following pending change requests affect this Group, you should check that your modification is not already pending:</p>
          <ul id="changeRequestList"></ul>
        </div>
      </div>
  }

  def showForm(): NodeSeq = {
    val html = SHtml.ajaxForm(body)

    (nodeGroup match {
      case Left(target)                     =>
        showFormTarget(target, allowCloning = false)(html) ++ showRelatedRulesTree(target) ++ showGroupCompliance(target.target)
      case Right(group) if (group.isSystem) =>
        showFormTarget(GroupTarget(group.id), Some(group))(html) ++ showRelatedRulesTree(
          GroupTarget(group.id)
        ) ++ showGroupCompliance(
          group.id.uid.value
        )
      case Right(group)                     =>
        showFormNodeGroup(group)(html) ++ showRelatedRulesTree(GroupTarget(group.id)) ++ showGroupCompliance(
          group.id.uid.value
        )
    })
  }

  private def showRelatedRulesTree(target: RuleTarget): NodeSeq = {
    val relatedRules                     = {
      dependencyService
        .targetDependencies(target)
        .map(_.rules.toSet.filter(!_.isSystem).map(_.id))
        .orElseSucceed(Set.empty[RuleId])
        .runNow
    }
    val (includingGroup, excludingGroup) = {
      ZIO
        .foreach(relatedRules)(ruleRepository.get)
        .map(_.partition(r => RuleTarget.merge(r.targets).includes(target)))
        .map { case (included, excluded) => (included.map(_.id), excluded.map(_.id)) }
        .runNow
    }
    Script(
      OnLoad(
        JsRaw(s"""
                 |var main = document.getElementById("groupRelatedRulesApp")
                 |var initValues = {
                 |  includedRules: ${includingGroup.toJson},
                 |  excludedRules: ${excludingGroup.toJson},
                 |  contextPath : contextPath
                 |};
                 |var app = Elm.Grouprelatedrules.init({node: main, flags: initValues});
                 |app.ports.errorNotification.subscribe(function(str) {
                 |  createErrorNotification(str);
                 |});
                 |app.ports.initTooltips.subscribe(function(msg) {
                 |  setTimeout(function(){
                 |    initBsTooltips();
                 |  }, 400);
                 |});
                 |$$("#relatedRulesLinkTab").on("click", function (){
                 |  app.ports.loadRelatedRulesTree.send(${relatedRules.toJson});
                 |});
                 |""".stripMargin)
      )
    )
  }

  private[this] def showGroupCompliance(targetOrGroupIdStr: String): NodeSeq = {
    Script(
      OnLoad(
        JsRaw(s"""
                 |var main = document.getElementById("groupComplianceApp")
                 |var initValues = {
                 |  groupId : "${targetOrGroupIdStr}",
                 |  contextPath : contextPath
                 |};
                 |var app = Elm.Groupcompliance.init({node: main, flags: initValues});
                 |app.ports.errorNotification.subscribe(function(str) {
                 |  createErrorNotification(str)
                 |});
                 |// Initialize tooltips
                 |app.ports.initTooltips.subscribe(function(msg) {
                 |  setTimeout(function(){
                 |    initBsTooltips();
                 |  }, 400);
                 |});
                 |// Clear tooltips
                 |app.ports.clearTooltips.subscribe(function(msg) {
                 |  removeBsTooltips();
                 |});
                 |$$("#complianceLinkTab").on("click", function (){
                 |  app.ports.loadCompliance.send(null);
                 |});
                 |""".stripMargin)
      )
    )
  }
  private val groupNameString = nodeGroup.fold(
    t => rootCategory.allTargets.get(t).map(_.name).getOrElse(t.target),
    _.name
  )

  private def showComplianceForGroup(progressBarSelector: String, optComplianceArray: Option[Json.Arr]) = {
    val complianceHtml = optComplianceArray.map(js => s"buildComplianceBar(${js.toJson})").getOrElse("\"No report\"")
    Script(JsRaw(s"""$$("${progressBarSelector}").html(${complianceHtml});"""))
  }

  private def showFormNodeGroup(nodeGroup: NodeGroup): CssSel = {
    val nodesSel = "#gridResult" #> NodeSeq.Empty
    val nodes    = nodesSel(searchNodeComponent.get match {
      case Full(req) => req.buildQuery(true)
      case eb: EmptyBox => <span class="error">Error when retrieving the request, please try again</span>
    })
    (
      "#group-title [class]" #> (if (nodeGroup.isEnabled) "" else "item-disabled")
      & "#group-name *" #> {
        Text(groupNameString) ++ (if (nodeGroup.isEnabled) NodeSeq.Empty else <span class="badge-disabled"></span>)
      }
      & "group-pendingchangerequest" #> PendingChangeRequestDisplayer.checkByGroup(pendingChangeRequestXml, nodeGroup.id)
      & "group-name" #> groupName.toForm_!
      & "group-rudderid" #> <div class="form-group">
                      <label class="wbBaseFieldLabel">Group ID</label>
                      <div class="position-relative align-items-center">
                        <input readonly="" class="form-control" value={nodeGroup.id.serialize}/>
                          <a class="my-2 mx-3 position-absolute end-0 top-0" title="Copy to clipboard" onclick={
        s"copy('${nodeGroup.id.serialize}')"
      }>
                            <i class="fa fa-clipboard"></i>
                        </a>
                      </div>
                    </div>
      & "group-cfeclasses" #> <div class="form-group">
                          <label class="wbBaseFieldLabel toggle-cond cond-hidden fw-normal" onclick="$(this).toggleClass('cond-hidden')">Agent conditions<i class="fa fa-chevron-down"></i></label>
                          <div class="well" id={s"cfe-${nodeGroup.id.serialize}"}>
                            {RuleTarget.toCFEngineClassName(nodeGroup.id.serialize)}<br/>
                            {RuleTarget.toCFEngineClassName(nodeGroup.name)}
                          </div>
                        </div>
      & "#longDescriptionField *" #> (groupDescription.toForm_! ++ Script(
        OnLoad(
          JsRaw(s"""setupMarkdown(${Str(nodeGroup.description).toJsCmd}, "longDescriptionField")""")
        ) // JsRaw OK, toJsCmd encodes
      ))
      & "group-container" #> groupContainer.toForm_!
      & "group-static" #> groupStatic.toForm_!
      & "group-showgroup" #> nodes
      & "group-close" #>
      <button class="btn btn-default" onclick={
        s"""$$('#${htmlIdCategory}').trigger("group-close-detail")"""
      }>
        Close
        <i class="fa fa-times"></i>
      </button>
      & "group-clone" #> {
        if (CurrentUser.checkRights(AuthorizationType.Group.Write)) {
          <li>
            {
            SHtml.ajaxButton(
              <span>
              <i class="fa fa-clone"></i>
              Clone
            </span>,
              () => showCloneGroupPopup()
            ) % ("class" -> "dropdown-item")
          }
          </li>
        } else NodeSeq.Empty
      }
      & "group-disable" #> {
        if (CurrentUser.checkRights(AuthorizationType.Group.Write)) {
          val btnText   = if (nodeGroup.isEnabled) { "Disable" }
          else { "Enable" }
          val btnIcon   = if (nodeGroup.isEnabled) { "fa fa-ban" }
          else { "fa fa-check-circle" }
          val btnAction = if (nodeGroup.isEnabled) { DGModAction.Disable }
          else { DGModAction.Enable }
          <li>
            {
            SHtml.ajaxButton(
              <span>
                  <i class={btnIcon}></i>
                  {btnText}
                </span>,
              () => onSubmitDisable(btnAction)
            ) % ("id" -> "groupDisableButtonId") % ("class" -> "dropdown-item")
          }
          </li>
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
                      <span class="save-tooltip" data-bs-toggle="tooltip" title="Your 'Save' button is disabled, it means that you have updated the query without Searching for new Nodes. Please click on 'Search' to enable saving again"></span>
                    </span>
        } else NodeSeq.Empty
      }
      & "group-delete" #>
      <li>
          {
        SHtml.ajaxButton(
          <span>
              <i class="fa fa-times-circle"></i>
              Delete
            </span>,
          () => onSubmitDelete(),
          ("class" -> "dropdown-item action-danger")
        )
      }
        </li>
      & "group-notifications" #> updateAndDisplayNotifications()
      & "#groupPropertiesTabContent" #> showGroupProperties(nodeGroup)
      & "#group-shownodestable *" #> (searchNodeComponent.get match {
        case Full(req) => req.displayNodesTable
        case eb: EmptyBox =>
          <span class="error">Error when retrieving the request, please try again</span>
      })
      & ".groupGlobalComplianceProgressBar *" #> showComplianceForGroup(
        ".groupGlobalComplianceProgressBar",
        loadComplianceBar(true)
      )
      & ".groupTargetedComplianceProgressBar *" #> showComplianceForGroup(
        ".groupTargetedComplianceProgressBar",
        loadComplianceBar(false)
      )
    )
  }

  private def showFormTarget(target: SimpleTarget, group: Option[NodeGroup] = None, allowCloning: Boolean = true): CssSel = {
    ("group-pendingchangerequest" #> NodeSeq.Empty
    & "#group-name" #> <span>{groupNameString}<span class="group-system"></span></span>
    & "group-name" #> groupName.readOnlyValue
    & "#groupTabMenu" #> <ul id="groupTabMenu" class="nav nav-underline">
                           <li class="nav-item">
                             <button class="nav-link active" data-bs-toggle="tab" data-bs-target="#groupParametersTab" type="button" role="tab" aria-controls="groupParametersTab">Parameters</button>
                           </li>
                           <li class="nav-item">
                             <button id="relatedRulesLinkTab" class="nav-link" data-bs-toggle="tab" data-bs-target="#groupRulesTab" type="button" role="tab" aria-controls="groupRulesTab">Related rules</button>
                           </li>
                           <li class="nav-item">
                             <button id="complianceLinkTab" class="nav-link" data-bs-toggle="tab" data-bs-target="#groupComplianceTab" type="button" role="tab" aria-controls="groupComplianceTab" aria-selected="false">Compliance</button>
                           </li>
                         </ul>
    & "group-rudderid" #> <div>
                    <label class="wbBaseFieldLabel">Group ID</label>
                    <div class="position-relative align-items-center">
                      <input readonly="" class="form-control" value={target.target}/>
                        <a class="my-2 mx-3 position-absolute end-0 top-0" title="Copy to clipboard" onclick={
      s"copy('${target.target}')"
    }>
                          <i class="fa fa-clipboard"></i>
                      </a>
                    </div>
                  </div>
    & "group-cfeclasses" #> NodeSeq.Empty
    & "#longDescriptionFieldMarkdownContainer i" #> NodeSeq.Empty
    & "#longDescriptionField" #> (groupDescription.toForm_! ++ Script(
      JsRaw(
        s"""setupMarkdown(${Str(groupDescription.defaultValue).toJsCmd}, "longDescriptionField")"""
      ) // JsRaw OK, toJsCmd encodes
    ))
    & "group-container" #> groupContainer.readOnlyValue
    & "group-static" #> NodeSeq.Empty
    & "group-showgroup" #> NodeSeq.Empty
    & "group-clone" #> (if (allowCloning) systemGroupCloneButton() else NodeSeq.Empty)
    & "group-close" #>
    <button class="btn btn-default" onclick={
      s"""$$('#${htmlIdCategory}').trigger("group-close-detail")"""
    }>
      Close
      <i class="fa fa-times"></i>
    </button>
    & "group-save" #> NodeSeq.Empty
    & "group-delete" #> group.map(systemGroupDeleteButton).getOrElse(NodeSeq.Empty)
    & "group-notifications" #> NodeSeq.Empty
    & "#group-shownodestable *" #> (searchNodeComponent.get match {
      case Full(req) => req.displayNodesTable
      case eb: EmptyBox =>
        <span class="error">Error when retrieving the request, please try again</span>
    })
    & ".groupGlobalComplianceProgressBar *" #> showComplianceForGroup(
      ".groupGlobalComplianceProgressBar",
      loadComplianceBar(true)
    )
    & ".groupTargetedComplianceProgressBar *" #> showComplianceForGroup(
      ".groupTargetedComplianceProgressBar",
      loadComplianceBar(false)
    ))
  }
  private def systemGroupDeleteButton(group: NodeGroup) = {
    // cve-groups category has another delete button in the CVE UI
    if (rootCategory.categoryByGroupId.get(group.id).exists(_.value == "cve-groups")) { // button is a link with a tooltip
      val href           = s"/secure/patch/cveManagement/cve/${StringEscapeUtils.escapeHtml4(group.id.uid.value)}"
      val tooltipContent =
        "<h4 class='tags-tooltip-title'>CVE group not deletable from here</h4><div class='tooltip-inner-content'>This group can only be deleted from the CVE details page from where it has been created.</div>"
      <a class="btn btn-default btn-icon" href={
        href
      } data-bs-toggle="tooltip" data-bs-placement="top" data-bs-html="true" data-bs-original-title={
        tooltipContent
      }>
        Delete
        <i class="fa fa-arrow-right"></i>
      </a>
    } else { // delete button not shown for all other system groups
      NodeSeq.Empty
    }
  }

  private def systemGroupCloneButton() = {
    if (CurrentUser.checkRights(AuthorizationType.Group.Write)) {
      SHtml.ajaxButton(
        <span>Clone
            <i class="fa fa-clone"></i>
          </span>,
        () => showCloneGroupPopup()
      ) % ("id" -> "groupCloneButtonId") % ("class" -> " btn btn-default btn-icon")
    } else NodeSeq.Empty
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
        a property with the same name than a parent, then that property value is overridden and
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
    intro ++ tabProperties
  }

  private def loadComplianceBar(isGlobalCompliance: Boolean): Option[Json.Arr] = {
    val target = nodeGroup match {
      case Left(value)  => value
      case Right(value) => GroupTarget(value.id)
    }
    for {
      compliance <-
        complianceService.getNodeGroupCompliance(target, level = Some(1), isGlobalCompliance = isGlobalCompliance).toOption
    } yield {
      ComplianceLevelSerialisation.ComplianceLevelToJs(compliance.compliance).toJsArray
    }
  }

  ///////////// fields for category settings ///////////////////

  private val groupName = {
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

  private val groupDescription = {
    val desc = nodeGroup.fold(
      t => rootCategory.allTargets.get(t).map(_.description).getOrElse(""),
      _.description
    )
    new WBTextAreaField("Description", desc) {
      override def setFilter             = notNull _ :: trim _ :: Nil
      override def className             = "form-control"
      override def labelClassName        = ""
      override def subContainerClassName = ""
      override def containerClassName    = "pe-2"
      override def errorClassName        = "text-danger mt-1"
      override def inputAttributes: Seq[(String, String)] = Seq(("rows", "15"))
      override def labelExtensions: NodeSeq               = {
        <i class="fa fa-check text-success cursorPointer half-opacity"     onmouseenter="toggleOpacity(this)" title="Valid description" onmouseout="toggleOpacity(this)" onclick="toggleMarkdownEditor('longDescriptionField')"></i> ++ Text(
          " "
        ) ++
        <i class="fa fa-eye-slash text-primary cursorPointer half-opacity" onmouseenter="toggleOpacity(this)" title="Show/hide preview" onmouseout="toggleOpacity(this)" onclick="togglePreview(this, 'longDescriptionField')"></i>
      }

    }
  }

  private val groupStatic = {
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

  private val groupContainer = new WBSelectField(
    "Category",
    (categoryHierarchyDisplayer.getCategoriesHierarchy(rootCategory, None).map { case (id, name) => (id.value -> name) }),
    parentCategoryId.value
  ) {
    override def className             = "form-select w-100"
    override def labelClassName        = ""
    override def subContainerClassName = ""
  }

  private val formTracker = new FormTracker(List(groupName, groupDescription, groupContainer, groupStatic))

  private def updateFormClientSide(): JsCmd = {
    SetHtml(htmlIdCategory, showForm())
  }

  private def error(msg: String) = <span class="error">{msg}</span>

  private def onFailure: JsCmd = {
    formTracker.addFormError(error("There was a problem with your request."))
    updateFormClientSide() & JsRaw("""scrollToElement("errorNotification","#ajaxItemContainer");""") // JsRaw OK, no user input
  }

  private def onSubmit(): JsCmd = {
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
          serverList = srvList.getOrElse(Set.empty[CoreNodeFact]).map(_.id).toSet
        )

        /*
         * - If a group changes from dynamic to static, or is static, we must ensure that it does not refer
         *   any dynamic subgroup, else raise an error
         * See https://issues.rudder.io/issues/18952
         */
        if (newGroup.isDynamic == false) {
          hasDynamicSubgroups(newGroup.query).either.runNow match {
            case Left(err)        =>
              formTracker.addFormError(Text("Error when saving group"))
              logger.error(
                s"Error when getting group information for consistency check on static change status: ${err.fullMsg}"
              )
            case Right(Some(msg)) =>
              val m = {
                s"Error when getting group information for consistency check on static/dynamic status:" +
                s"current group can not be static because it uses following dynamic groups as a subgroup criteria: ${msg}"
              }
              formTracker.addFormError(Text(m))
              logger.error(m)
            case Right(None)      => // ok
          }
        }

        /*
         * - If a group changes from static to dynamic, we must ensure that it is not referred in any static
         *   group target, else raise an error
         * See https://issues.rudder.io/issues/18952
         */
        if (savedGroup.isDynamic == false && newGroup.isDynamic == true) {
          getDependingGroups(newGroup.id, onlyStatic = true).either.runNow match {
            case Left(err)        =>
              formTracker.addFormError(Text("Error when saving group"))
              logger.error(
                s"Error when getting group information for consistency check on static change status: ${err.fullMsg}"
              )
            case Right(Some(msg)) =>
              val m = s"Error when getting group information for consistency check on static change status: you " +
                s"can't make that group dynamic since groups ${msg} are static and use it as a subgroup target."
              formTracker.addFormError(Text(m))
              logger.error(m)
            case Right(None)      => // ok
          }
        }

        if (newGroup == savedGroup && optContainer.isEmpty) {
          formTracker.addFormError(Text("There are no modifications to save"))
        }

        if (formTracker.hasErrors) {
          onFailure & onFailureCallback()
        } else {
          // don't warn on mod of a group for impact on depending groups
          displayConfirmationPopup(DGModAction.Update, newGroup, optContainer, None)
        }
    }
  }

  // find used subgroup in the query (wherever their place is) if any
  private[components] def hasDynamicSubgroups(query: Option[Query]): IOResult[Option[String]] = {
    query match {
      case None    => None.succeed
      case Some(q) =>
        val subgroups = q.criteria.collect {
          case CriterionLine(_, a, _, value) if (a.cType.isInstanceOf[SubGroupComparator]) => NodeGroupId(NodeGroupUid(value))
        }

        for {
          groups <- roNodeGroupRepository.getFullGroupLibrary()
        } yield {
          val depending = subgroups.flatMap { gid =>
            groups.allGroups.get(gid) match {
              case None    => // ? ignore, it's strange but that does not change things
                None
              case Some(g) =>
                if (g.nodeGroup.isDynamic) { Some(g) }
                else { None }
            }
          }
          depending match {
            case Nil  =>
              None
            case list =>
              val gs = list.map(g => s"'${g.nodeGroup.name}' [${g.nodeGroup.id.serialize}]").mkString(", ")
              Some(gs)
          }
        }
    }

  }

  // get the list of group that use that group as a target (optionally: only the static ones)
  // The returned value is a message with the list of dep groups that can be used in form error or warning pop-up.
  // If none, no dependent group were found.
  private[components] def getDependingGroups(id: NodeGroupId, onlyStatic: Boolean): IOResult[Option[String]] = {
    def queryTargetsSubgroup(query: Option[Query], id: NodeGroupId): Boolean = {
      query match {
        case None    => false
        case Some(q) =>
          q.criteria.find {
            case CriterionLine(_, a, _, value) => a.cType.isInstanceOf[SubGroupComparator] && value == id.serialize
          }.nonEmpty
      }
    }
    def checkStatic(isDynamic: Boolean, onlyStatic: Boolean) = !onlyStatic || !isDynamic

    roNodeGroupRepository.getFullGroupLibrary().map { groups =>
      val dependingGroups = groups.allGroups.collect {
        case (_, g) if (checkStatic(g.nodeGroup.isDynamic, onlyStatic) && queryTargetsSubgroup(g.nodeGroup.query, id)) =>
          g.nodeGroup
      }.toList
      if (dependingGroups.nonEmpty) {
        val gs = dependingGroups.map(g => s"'${g.name}' [${g.id.serialize}]}").mkString(", ")

        Some(gs)
      } else None
    }
  }

  private def onSubmitDisable(action: DGModAction): JsCmd = {
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

        val optContainer = {
          val c = NodeGroupCategoryId(groupContainer.get)
          if (c == parentCategoryId) None
          else Some(c)
        }

        // submit can be done only for node group, not system one
        val newGroup = savedGroup.copy(
          name = groupName.get,
          description = groupDescription.get, // , container = container

          isDynamic = groupStatic.get match {
            case "dynamic" => true;
            case _         => false
          },
          query = query,
          _isEnabled = action match {
            case DGModAction.Enable if !savedGroup.isSystem  => true
            case DGModAction.Disable if !savedGroup.isSystem => false
            case _                                           => savedGroup._isEnabled
          },
          serverList = srvList.getOrElse(Set.empty[CoreNodeFact]).map(_.id).toSet
        )

        displayConfirmationPopup(action, newGroup, optContainer, None)
    }
  }

  private def onSubmitDelete(): JsCmd = {
    nodeGroup match {
      case Left(_)   => Noop
      case Right(ng) =>
        (ZIO.when(ng.isSystem) {
          Inconsistency(s"Could not delete group '${ng.id.serialize}', cause is: system groups cannot be deleted.").fail
        } *> getDependingGroups(ng.id, onlyStatic = false).either).runNow match {
          case Left(err)  =>
            onFailure & onFailureCallback()
          case Right(msg) =>
            displayConfirmationPopup(DGModAction.Delete, ng, None, msg)
        }
    }
  }

  /*
   * Create the confirmation pop-up
   */

  private def displayConfirmationPopup(
      action:             DGModAction,
      newGroup:           NodeGroup,
      newCategory:        Option[NodeGroupCategoryId],
      dependingSubgroups: Option[String] // if Some, string contains a message with the groups
  ): JsCmd = {

    val optOriginal = nodeGroup.toOption
    val change      = NodeGroupChangeRequest(action, newGroup, newCategory, optOriginal)
    val errMsg      = s"Error when getting the validation workflow for changes in group '${change.newGroup.name}'"

    workflowLevelService
      .getForNodeGroup(CurrentUser.actor, change)
      .chainError(errMsg)
      .either
      .runNow match {
      case Left(err) =>
        logger.warn(err.fullMsg)
        JsRaw(s"alert(${errMsg})")

      case Right(workflowService) =>
        val popup = {

          def successCallback(crId: ChangeRequestId) = {
            if (workflowService.needExternalValidation()) {
              onSuccessCallback(Right(crId))
            } else {
              val updateCategory = newCategory.getOrElse(parentCategoryId)
              successPopup & onSuccessCallback(Left((Right(newGroup), updateCategory))) &
              (if (action == DGModAction.Delete)
                 RedirectTo("/secure/nodeManager/groups")
               else
                 Noop)
            }
          }
          new ModificationValidationPopup(
            Right(change),
            workflowService,
            crId => JsRaw("hideBsModal('confirmUpdateActionDialog');") & successCallback(crId), // JsRaw ok, const
            xml => JsRaw("hideBsModal('confirmUpdateActionDialog');") & onFailure,              // JsRaw ok, const
            parentFormTracker = formTracker
          )
        }

        if (popup.popupWarningMessages.isEmpty && dependingSubgroups.isEmpty) {
          popup.onSubmit()
        } else {
          val html: NodeSeq = dependingSubgroups match {
            case None      => popup.popupContent()
            case Some(msg) =>
              val cssSel: CssSel = "#explanationMessageZone *+" #>
                <div id="dialogSubgroupWarning" class="col-xl-12 col-md-12 col-sm-12 alert alert-warning text-center">
                  This group is used as a subgroups of group {msg}. If you delete it, they will be impacted.
                </div>

              cssSel(popup.popupContent())
          }

          SetHtml("confirmUpdateActionDialog", html) &
          JsRaw("""initBsModal("confirmUpdateActionDialog")""") // JsRaw ok, const
        }
    }
  }

  def createPopup(name: String):     JsCmd = {
    JsRaw(s"""initBsModal("${name}");""") // JsRaw ok, const
  }
  private def showCloneGroupPopup(): JsCmd = {

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

  private def displayACategory(category: NodeGroupCategory): JsCmd = {
    refreshRightPanel(CategoryForm(category))
  }

  private def refreshRightPanel(panel: RightPanel): JsCmd = SetHtml(htmlId_item, setAndShowRightPanel(panel))

  /**
   *  Manage the state of what should be displayed on the right panel.
   * It could be nothing, a group edit form, or a category edit form.
   */
  private def setAndShowRightPanel(panel: RightPanel): NodeSeq = {
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

  private def showGroupSection(
      group:            Either[NonGroupRuleTarget, NodeGroup],
      parentCategoryId: NodeGroupCategoryId
  ): JsCmd = {
    val js = group match {
      case Left(target) => s"'target':'${StringEscapeUtils.escapeEcmaScript(target.target)}"
      case Right(g)     => s"'groupId':'${StringEscapeUtils.escapeEcmaScript(g.id.serialize)}'"
    }
    // update UI
    onSuccessCallback(Left((group, parentCategoryId))) &
    JsRaw(s"""this.window.location.hash = "#" + JSON.stringify({${js}})""") // JsRaw ok, escaped
  }

  private def updateAndDisplayNotifications(): NodeSeq = {

    val notifications = formTracker.formErrors
    formTracker.cleanErrors

    if (notifications.isEmpty) {
      NodeSeq.Empty
    } else {
      val html = {
        <div id="errorNotification" class="notify">
          <ul class="text-danger">{notifications.map(n => <li>{n}</li>)}</ul>
        </div>
      }
      html
    }
  }

  private def successPopup: JsCmd = {
    JsRaw("""createSuccessNotification()""") // JsRaw ok, const
  }

}
