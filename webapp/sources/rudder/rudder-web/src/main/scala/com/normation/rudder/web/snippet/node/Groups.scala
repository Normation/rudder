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

package com.normation.rudder.web.snippet.node

import bootstrap.liftweb.RudderConfig
import com.normation.box.*
import com.normation.plugins.DefaultExtendableSnippet
import com.normation.rudder.AuthorizationType
import com.normation.rudder.domain.nodes.*
import com.normation.rudder.domain.policies.*
import com.normation.rudder.domain.workflows.ChangeRequestId
import com.normation.rudder.repository.*
import com.normation.rudder.tenants.ChangeContext
import com.normation.rudder.tenants.QueryContext
import com.normation.rudder.users.CurrentUser
import com.normation.rudder.web.components.NodeGroupCategoryForm
import com.normation.rudder.web.components.NodeGroupForm
import com.normation.rudder.web.components.popup.CreateCategoryOrGroupPopup
import com.normation.rudder.web.services.DisplayNodeGroupTree
import com.normation.rudder.web.snippet.WithNonce
import net.liftweb.common.*
import net.liftweb.http.*
import net.liftweb.http.js.*
import net.liftweb.http.js.JE.*
import net.liftweb.http.js.JsCmds.*
import net.liftweb.json.*
import net.liftweb.util.*
import org.apache.commons.text.StringEscapeUtils
import scala.xml.*

object Groups {
  val htmlId_groupTree           = "groupTree"
  val htmlId_item                = "ajaxItemContainer"
  val htmlId_updateContainerForm = "updateContainerForm"

  sealed private trait RightPanel
  private case object NoPanel                                        extends RightPanel
  final private case class GroupForm(group: Either[NonGroupRuleTarget, NodeGroup], parentCategoryId: NodeGroupCategoryId)
      extends RightPanel
  final private case class CategoryForm(category: NodeGroupCategory) extends RightPanel

}

class Groups extends StatefulSnippet with DefaultExtendableSnippet[Groups] with Loggable {
  import Groups.*

  private val getFullGroupLibrary   = () => RudderConfig.roNodeGroupRepository.getFullGroupLibrary()(using CurrentUser.queryContext)
  private val woNodeGroupRepository = RudderConfig.woNodeGroupRepository
  private val linkUtil              = RudderConfig.linkUtil

  private var boxGroupLib = getFullGroupLibrary().toBox

  val mainDispatch: Map[String, NodeSeq => NodeSeq] = {
    implicit val qc: QueryContext = CurrentUser.queryContext // bug https://issues.rudder.io/issues/26605

    Map(
      "head"           -> head,
      "detailsPopup"   -> { (_: NodeSeq) => NodeGroupForm.staticBody },
      "initRightPanel" -> { (_: NodeSeq) => initRightPanel() },
      "groupHierarchy" -> groupHierarchy(boxGroupLib)
    )
  }

  // the current nodeGroupCategoryForm component
  private val nodeGroupCategoryForm = new LocalSnippet[NodeGroupCategoryForm]

  // the current nodeGroupForm component
  private val nodeGroupForm = new LocalSnippet[NodeGroupForm]

  // the popup component
  private val creationPopup = new LocalSnippet[CreateCategoryOrGroupPopup]

  private var selectedCategoryId = boxGroupLib.map(_.id)

  /**
   * Head of the portlet, nothing much yet
   * @param html
   * @return
   */
  def head(html: NodeSeq): NodeSeq = {
    NodeGroupForm.staticInit
  }

  /**
   * Display the Groups tree on the left in Elm, and change the content on the right when needed within the `html_id` element of the page)
   * @param html
   * @return
   */
  def groupHierarchy(rootCategory: Box[FullNodeGroupCategory])(implicit qc: QueryContext): CssSel = {
    val newItemCallback        = AnonFunc(
      SHtml.ajaxCall(JsVar("hasWriteRights"), (hasWriteRights) => if (hasWriteRights.toBoolean) showPopup() else Noop)
    )
    val displayGroupDetails    = AnonFunc( // closure with "groupIdOrTarget"
      SHtml.ajaxCall(JsVar("groupIdOrTarget"), _ => parseJsArg(boxGroupLib))
    )
    val displayCategoryDetails = AnonFunc( // closure with "categoryId"
      SHtml.ajaxCall(
        JsVar("categoryId"),
        (catId) => displayCategory(catId).getOrElse(JsRaw(s"createErrorNotification('Category ${catId} not found')"))
      )
    )
    _ =>
      WithNonce.scriptWithNonce(
        Script(
          OnLoad(
            JsRaw(
              s"""
                 |var main = document.getElementById("groups-app");
                 |var initValues = {
                 |  contextPath    : contextPath
                 |, hasGroupToDisplay : hasGroupToDisplay
                 |, hasWriteRights : hasWriteRights
                 |};
                 |var app = Elm.Groups.init({node: main, flags: initValues});
                 |app.ports.errorNotification.subscribe(function(str) {
                 |  createErrorNotification(str)
                 |});
                 |app.ports.pushUrl.subscribe(function(url) {
                 |  var hashKey = url[0];
                 |  var hashValue = url[1];
                 |  var url = contextPath + "/secure/nodeManager/groups";
                 |  if (hashKey == "") {
                 |    window.location.hash = "";
                 |  } else {
                 |    window.location.hash = JSON.stringify({[hashKey]: hashValue});
                 |  }
                 |});
                 |// support loading another group from change in URL hash while staying in page (e.g. from quicksearch result)
                 |window.addEventListener('hashchange', function (e) {
                 |  var newHash = e.target.location.hash;
                 |  var splitHash = newHash.split("#");
                 |  if (splitHash.length > 0) {
                 |    try {
                 |      var hashJsonObj = JSON.parse(decodeURIComponent(splitHash[1]));
                 |      if ("groupId" in hashJsonObj) {
                 |        app.ports.readUrl.send(hashJsonObj["groupId"]);
                 |      }
                 |    } catch {}
                 |  }
                 |});
                 |app.ports.adjustComplianceCols.subscribe(function() {
                 |  //call the equalize width function
                 |  var group = $$(".compliance-col");
                 |  var widest = 0;
                 |  group.each(function() {
                 |      var thisWidth = $$(this).width();
                 |      if(thisWidth > widest) {
                 |          widest = thisWidth;
                 |      }
                 |  });
                 |  group.width(widest);
                 |});
                 |app.ports.displayCategoryDetails.subscribe(function(categoryId) {
                 |  var displayCategoryDetailsCallback = ${displayCategoryDetails.toJsCmd};
                 |  displayCategoryDetailsCallback()
                 |});
                 |app.ports.displayGroupDetails.subscribe(function(groupIdOrTarget) {
                 |  var displayGroupDetailsCallback = ${displayGroupDetails.toJsCmd};
                 |  displayGroupDetailsCallback()
                 |});
                 |var createGroupCallback = ${newItemCallback.toJsCmd};
                 |app.ports.createGroupModal.subscribe(function(msg) {
                 |  createGroupCallback()
                 |});
                 |
                 |// We need to notify the Elm app when an action could have been executed to refresh the display
                 |$$("#createGroupPopup").on("hidden.bs.modal", function () {
                 |  app.ports.closeModal.send(null)
                 |});
                 |$$("#basePopup").on("hidden.bs.modal", function () {
                 |  app.ports.closeModal.send(null)
                 |});
                 |$$("#confirmUpdateActionDialog").on("hidden.bs.modal", function () {
                 |  app.ports.closeModal.send(null)
                 |});
                 |$$("#createCloneGroupPopup").on("hidden.bs.modal", function () {
                 |  app.ports.closeModal.send(null)
                 |});
                 |// When custom event to close group details fires, we load the group table state in the Elm app
                 |$$("#${htmlId_item}").on("group-close-detail", function () {
                 |  $$("#${htmlId_item} .main-container").hide(); // guarantee to hide details
                 |  app.ports.loadGroupTable.send(null)
                 |});
                 |
                 |// Initialize tooltips
                 |app.ports.initTooltips.subscribe(function(msg) {
                 |  initBsTooltips();
                 |  setTimeout(function(){
                 |    initBsTooltips();
                 |  }, 800);
                 |});
              """.stripMargin
            )
          )
        )
      )
  }

  /**
   * Does the init part (showing the right component and highlighting
   * the tree if necessary)
   */
  def initRightPanel()(implicit qc: QueryContext): NodeSeq = {
    WithNonce.scriptWithNonce(Script(OnLoad(parseJsArg(boxGroupLib))))
  }

  /**
   * If a query is passed as argument, try to dejoniffy-it, in a best effort
   * way - just don't take of errors.
   *
   * We want to look for #{ "groupId":"XXXXXXXXXXXX" } or #{"targer":"....."}
   */
  private def parseJsArg(rootCategory: Box[FullNodeGroupCategory])(implicit qc: QueryContext): JsCmd = {
    def displayGroupNotFound:                     JsCmd = SetHtml(
      htmlId_item,
      <div class="jumbotron">
        <h2>Group not found</h2>
      </div>
    )
    def displayDetailsGroup(groupId: String):     JsCmd = {
      val gid = NodeGroupId(NodeGroupUid(groupId))
      rootCategory match {
        case eb: EmptyBox => displayGroupNotFound
        case Full(lib) =>
          lib.allGroups.get(gid) match {
            case None                  => displayGroupNotFound
            case Some(fullGroupTarget) => // so we also have its parent category
              // no modification, so no refreshGroupLib
              refreshTree(htmlTreeNodeId(groupId)) &
              showGroupSection(Right(fullGroupTarget.nodeGroup), lib.categoryByGroupId(gid)) &
              JsRaw("initBsTooltips()") // JsRaw ok, const
          }
      }
    }
    def displayDetailsTarget(targetName: String): JsCmd = {
      RuleTarget.unser(targetName) match {
        case Right(t: NonGroupRuleTarget) =>
          refreshTree(htmlTreeNodeId(targetName)) &
          showGroupSection(Left(t), NodeGroupCategoryId("SystemGroups")) &
          JsRaw("initBsTooltips()") // JsRaw ok, const
        case _                            => displayGroupNotFound
      }
    }

    JsRaw(s"""
        var targetName = null;
        var groupId = null;
        try {
          var decoded = JSON.parse(decodeURI(window.location.hash.substring(1)));
          groupId = decoded.groupId;
          targetName = decoded.target;
        } catch(e) {
          groupId = null;
          targetName = null;
        }
        if( groupId != null && groupId.length > 0) {
          ${SHtml.ajaxCall(JsVar("groupId"), displayDetailsGroup)._2.toJsCmd};
          hasGroupToDisplay = true;
        } else if( targetName != null && targetName.length > 0) {
          ${SHtml.ajaxCall(JsVar("targetName"), displayDetailsTarget)._2.toJsCmd};
          hasGroupToDisplay = true;
        }
    """) // JsRaw ok, escaped
  }

  ////////////////////////////////////////////////////////////////////////////////////

  private def htmlTreeNodeId(id: String) = "jsTree-" + id

  /**
   *  Manage the state of what should be displayed on the right panel.
   * It could be nothing, a group edit form, or a category edit form.
   */
  private def setAndShowRightPanel(panel: RightPanel, rootCategory: FullNodeGroupCategory)(implicit
      qc: QueryContext
  ): NodeSeq = {
    panel match {
      case NoPanel                       => NodeSeq.Empty
      case GroupForm(group, parentCatId) =>
        val form = new NodeGroupForm(
          htmlId_item,
          group,
          parentCatId,
          rootCategory,
          { (redirect: Either[(Either[NonGroupRuleTarget, NodeGroup], NodeGroupCategoryId), ChangeRequestId]) =>
            redirect match {
              case Left((newGroup, newParentId)) =>
                refreshGroupLib()
                refreshTree(htmlTreeNodeId(newGroup.fold(_.target, _.id.serialize))) &
                showGroupSection(newGroup, newParentId)
              case Right(crId)                   =>
                linkUtil.redirectToChangeRequestLink(crId)
            }
          },
          () => {
            // On failure, the NodeGroupForm replaces the DOM, and we need to still be initialize the group properties Elm app
            showGroupProperties(group, parentCatId)
          }
        )

        nodeGroupForm.set(Full(form))
        form.dispatch("showForm")(NodeSeq.Empty);

      case CategoryForm(category) =>
        val form = new NodeGroupCategoryForm(htmlId_item, category, rootCategory, onSuccessCallback())
        nodeGroupCategoryForm.set(Full(form))
        form.showForm()
    }
  }

  // utility to refresh right panel
  private def refreshRightPanel(panel: RightPanel)(implicit qc: QueryContext): JsCmd = {
    boxGroupLib match {
      case Full(lib) => SetHtml(htmlId_item, setAndShowRightPanel(panel, lib))
      case eb: EmptyBox =>
        val e = eb ?~! "Error when trying to get the root node group category"
        logger.error(e.messageChain)
        Alert(e.messageChain)
    }
  }

  // that must be separated from refreshTree/refreshRightPanel
  // to avoid duplicate refresh or useless one (when only displaying without modification)
  private def refreshGroupLib(): Unit = {
    boxGroupLib = getFullGroupLibrary().toBox
  }

  private def setCreationPopup(rootCategory: FullNodeGroupCategory)(implicit qc: QueryContext): Unit = {
    creationPopup.set(
      Full(
        new CreateCategoryOrGroupPopup(
          None,
          rootCategory,
          selectedCategoryId,
          onSuccessCategory = displayCategory,
          onSuccessGroup = showGroupSection,
          onSuccessCallback = onSuccessCallback()
        )
      )
    )
  }

  private def onSuccessCallback()(implicit qc: QueryContext) = { (id: String) =>
    { refreshGroupLib(); refreshTree(htmlTreeNodeId(id)) }
  }

  /**
   * build the tree of categories and group and init its JS
   */
  private def buildGroupTree(selectedNode: String)(implicit qc: QueryContext): NodeSeq = {
    boxGroupLib match {
      case eb: EmptyBox =>
        val e = eb ?~! "Can not get the group library"
        logger.error(e.messageChain)
        e.rootExceptionCause.foreach(ex => logger.error("Root exception was:", ex))

        <div id={htmlId_groupTree}>Error: {e.msg}</div>
      case Full(lib) =>
        // We want to fold categories if there are more than 11 (10 + 1 taking into account the hidden root node)
        val foldCategories = lib.allCategories.size > 11
        (
          <div id={htmlId_groupTree} class="col-sm-12">
          <ul>{
            DisplayNodeGroupTree.displayTree(
              lib,
              Some(fullDisplayCategory),
              Some(showTargetInfo),
              Map()
            )
          }</ul>
        </div>: NodeSeq
        ) ++ Script(
          OnLoad(
            // build jstree and
            // init bind callback to move
            JsRaw(s"""
        buildGroupTree('#${htmlId_groupTree}','${S.contextPath}', '${selectedNode}', 'off', true, ${CurrentUser.checkRights(
                AuthorizationType.Group.Edit
              )});
        if(${foldCategories}){
          $$('#${htmlId_groupTree}').jstree().close_all();
        }
        $$('#${htmlId_groupTree}').bind("move_node.jstree", function (e,data) {
          var sourceCatId = $$(data.rslt.o).attr("catId");
          var sourceGroupId = $$(data.rslt.o).attr("groupId");
          var destCatId = $$(data.rslt.np).attr("catId");
          if( destCatId ) {
            if(sourceGroupId) {
              var arg = JSON.stringify({ 'sourceGroupId' : sourceGroupId, 'destCatId' : destCatId });
              ${SHtml.ajaxCall(JsVar("arg"), moveGroup(lib))._2.toJsCmd};
            } else if(  sourceCatId ) {
              var arg = JSON.stringify({ 'sourceCatId' : sourceCatId, 'destCatId' : destCatId });
              ${SHtml.ajaxCall(JsVar("arg"), moveCategory(lib)(_)(using qc.newCC()))._2.toJsCmd};
            } else {
              alert("Can not move that kind of object");
              $$.jstree.rollback(data.rlbk);
            }
          } else {
            alert("Can not move to something else than a category");
            $$.jstree.rollback(data.rlbk);
          }
        });
      """) // JsRaw ok, escaped
          )
        )
    }
  }

  /**
   * Create the popup
   */
  private def createPopup: NodeSeq = {
    creationPopup.get match {
      case Failure(m, _, _) => <span class="error">Error: {m}</span>
      case Empty            => <div>The component is not set</div>
      case Full(popup)      => popup.popupContent()
    }
  }

  ///////////////////// Callback function for Drag'n'drop in the tree /////////////////////
  private def moveGroup(lib: FullNodeGroupCategory)(arg: String)(implicit qc: QueryContext): JsCmd = {
    // parse arg, which have to  be json object with sourceGroupId, destCatId
    try {
      (for {
        case JObject(child) <- JsonParser.parse(arg)
        case JField("sourceGroupId", JString(sourceGroupId)) <- child
        case JField("destCatId", JString(destCatId)) <- child
      } yield {
        (sourceGroupId, destCatId)
      }) match {
        case (sourceGroupId, destCatId) :: Nil =>
          (for {
            result <-
              woNodeGroupRepository
                .move(
                  NodeGroupId(NodeGroupUid(sourceGroupId)),
                  NodeGroupCategoryId(destCatId)
                )(using qc.newCC(Some("Group moved by user")))
                .toBox ?~! "Error while trying to move group with requested id '%s' to category id '%s'"
                .format(sourceGroupId, destCatId)
            group  <- Box(lib.allGroups.get(NodeGroupId(NodeGroupUid(sourceGroupId)))) ?~! s"No such group: ${sourceGroupId}"
          } yield {
            (group.nodeGroup, lib.categoryByGroupId(group.nodeGroup.id))
          }) match {
            case Full((ng, cat)) =>
              refreshGroupLib()
              (
                refreshTree(htmlTreeNodeId(ng.id.serialize))
                & JsRaw(
                  """setTimeout(function() { $("[groupid=%s]").attempt("highlight", {}, 2000)}, 100)""".format(sourceGroupId)
                ) // JsRaw ok, comes from json
                & refreshRightPanel(GroupForm(Right(ng), cat))
              )
            case f: Failure => Alert(f.messageChain + "\nPlease reload the page")
            case Empty           =>
              Alert(
                "Error while trying to move group with requested id '%s' to category id '%s'\nPlease reload the page.".format(
                  sourceGroupId,
                  destCatId
                )
              )
          }
        case _                                 => Alert("Error while trying to move group: bad client parameters")
      }
    } catch {
      case e: Exception => Alert("Error while trying to move group")
    }
  }

  private def moveCategory(lib: FullNodeGroupCategory)(arg: String)(implicit cc: ChangeContext): JsCmd = {
    // parse arg, which have to  be json object with sourceGroupId, destCatId
    try {
      (for {
        case JObject(child) <- JsonParser.parse(arg)
        case JField("sourceCatId", JString(sourceCatId)) <- child
        case JField("destCatId", JString(destCatId)) <- child
      } yield {
        (sourceCatId, destCatId)
      }) match {
        case (sourceCatId, destCatId) :: Nil =>
          (for {
            category <- Box(
                          lib.allCategories.get(NodeGroupCategoryId(sourceCatId))
                        ) ?~! "Error while trying to find category with requested id %s".format(sourceCatId)

            result <-
              woNodeGroupRepository
                .saveGroupCategory(
                  category.toNodeGroupCategory,
                  NodeGroupCategoryId(destCatId)
                )
                .toBox ?~! "Error while trying to move category with requested id '%s' to category id '%s'"
                .format(sourceCatId, destCatId)
          } yield {
            (category.id.value, result)
          }) match {
            case Full((id, res)) =>
              refreshGroupLib()
              (
                refreshTree(htmlTreeNodeId(id))(using cc.toQC)
                & OnLoad(
                  JsRaw("""setTimeout(function() { $("[catid=%s]").attempt("highlight", {}, 2000);}, 100)""".format(sourceCatId))
                ) // JsRaw ok, comes from json
                & refreshRightPanel(CategoryForm(res))(using cc.toQC)
              )
            case f: Failure => Alert(f.messageChain + "\nPlease reload the page")
            case Empty           =>
              Alert(
                "Error while trying to move category with requested id '%s' to category id '%s'\nPlease reload the page.".format(
                  sourceCatId,
                  destCatId
                )
              )
          }
        case _                               => Alert("Error while trying to move group: bad client parameters")
      }
    } catch {
      case e: Exception => Alert("Error while trying to move group")
    }
  }

  ////////////////////

  private def refreshTree(selectedNode: String)(implicit qc: QueryContext): JsCmd = {
    Replace(htmlId_groupTree, buildGroupTree(selectedNode))
  }

  /********************************************
  * Utility methods for JS
  ********************************************/

  /**
    * @return None if the category is not found
    */
  private def displayCategory(categoryId: String)(implicit qc: QueryContext): Option[JsCmd] = {
    for {
      groupLib <- boxGroupLib.toOption
      category <- groupLib.allCategories.get(NodeGroupCategoryId(categoryId))
    } yield {
      displayCategory(category.toNodeGroupCategory)
    }
  }

  private def displayCategory(category: NodeGroupCategory)(implicit qc: QueryContext): JsCmd = {
    selectedCategoryId = Full(category.id)
    // update UI - no modification here, so no refreshGroupLib
    refreshRightPanel(CategoryForm(category)) &
    JsRaw("""$('#ajaxItemContainer').show();""") // JsRaw ok, const
  }

  // adaptater
  private def fullDisplayCategory(category: FullNodeGroupCategory)(implicit qc: QueryContext) = displayCategory(
    category.toNodeGroupCategory
  )

  private def showGroupSection(g: Either[NonGroupRuleTarget, NodeGroup], parentCategoryId: NodeGroupCategoryId)(implicit
      qc: QueryContext
  ) = {
    refreshRightPanel(GroupForm(g, parentCategoryId)) &
    showGroupProperties(g, parentCategoryId)
  }

  private def showGroupProperties(g: Either[NonGroupRuleTarget, NodeGroup], parentCategoryId: NodeGroupCategoryId) = {
    val value = StringEscapeUtils.escapeEcmaScript(g.fold(_.target, _.id.serialize))
    val js    = g match {
      case Left(_)  => s"'target':'${value}'"
      case Right(_) => s"'groupId':'${value}'"
    }
    JsRaw(s"""
             |jQuery('#ajaxItemContainer').show();
             |var groupId = JSON.stringify({${js}});
             |window.location.hash = "#"+groupId;
             |
             |// When the tab is shown we need to initialize the Elm app
             |$$('#groupPropertiesLinkTab').on('show.bs.tab', function() {
             |  var main = document.getElementById("nodeproperties-app")
             |  if (main) {
             |    var initValues = {
             |        contextPath    : "${S.contextPath}"
             |      , hasNodeWrite   : CanWriteNode
             |      , hasNodeRead    : CanReadNode
             |      , nodeId         : '${value}'
             |      , objectType     : 'group'
             |    };
             |    var app = Elm.Nodeproperties.init({node: main, flags: initValues});
             |    app.ports.successNotification.subscribe(function(str) {
             |      createSuccessNotification(str)
             |    });
             |    app.ports.errorNotification.subscribe(function(str) {
             |      createErrorNotification(str)
             |    });
             |    // Initialize tooltips
             |    app.ports.initTooltips.subscribe(function(msg) {
             |      setTimeout(function(){
             |        initBsTooltips();
             |      }, 400);
             |    });
             |    app.ports.copy.subscribe(function(str) {
             |      copy(str);
             |    });
             |    app.ports.initInputs.subscribe(function(str) {
             |      setTimeout(function(){
             |        $$(".auto-resize").on("input", autoResize).each(function(){
             |          autoResize(this);
             |        });
             |      }, 10);
             |    });
             |  }
             |});
             |""".stripMargin) // JsRaw ok, escaped

  }

  private def showTargetInfo(parentCategory: FullNodeGroupCategory, targetInfo: FullRuleTargetInfo)(implicit
      qc: QueryContext
  ): JsCmd = {
    // update UI - no modeification here, so no refreshGroupLib

    // action only for node group

    targetInfo.target match {
      case t: FullGroupTarget => showGroupSection(Right(t.nodeGroup), parentCategory.id)
      case FullOtherTarget(t) => showGroupSection(Left(t), parentCategory.id)
      case _                  => Noop
    }
  }

  private def showPopup()(implicit qc: QueryContext): JsCmd = {

    boxGroupLib match {
      case eb: EmptyBox => Alert("Error when trying to get the list of categories")
      case Full(rootCategory) =>
        setCreationPopup(rootCategory)

        // update UI
        SetHtml("createGroupContainer", createPopup) &
        JsRaw("""initBsModal("createGroupPopup")""") // JsRaw ok, const
    }
  }
}
