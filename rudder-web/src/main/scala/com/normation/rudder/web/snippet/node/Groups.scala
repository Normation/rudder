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

package com.normation.rudder.web.snippet.node

import com.normation.rudder.domain.policies._
import com.normation.rudder.domain.nodes._
import com.normation.rudder.web.components.{
  NodeGroupForm,NodeGroupCategoryForm
}
import com.normation.rudder.web.model._
import com.normation.rudder.web.model.WBTextField
import com.normation.rudder.web.model.JsTreeNode
import net.liftweb.http.LocalSnippet
import bootstrap.liftweb.LiftSpringApplicationContext.inject
import com.normation.rudder.repository._
import com.normation.rudder.web.components.popup.CreateCategoryOrGroupPopup
import scala.xml._
import net.liftweb.common._
import net.liftweb.http._
import net.liftweb.util._
import Helpers._
import net.liftweb.http.js._
import JsCmds._
import JE._
import net.liftweb.http.SHtml._
import net.liftweb.json._
import com.normation.rudder.web.model.CurrentUser
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.policies.GroupTarget
import com.normation.rudder.domain.policies.RuleTargetInfo
import com.normation.utils.HashcodeCaching
import com.normation.rudder.domain.nodes.NodeGroupCategory
import com.normation.rudder.domain.nodes.NodeGroupCategoryId
import com.normation.plugins.ExtendableSnippet
import com.normation.plugins.SnippetExtensionKey
import com.normation.plugins.SpringExtendableSnippet
import com.normation.eventlog.ModificationId
import com.normation.utils.StringUuidGenerator
import bootstrap.liftweb.RudderConfig
import com.normation.rudder.domain.workflows.ChangeRequest
import com.normation.rudder.domain.workflows.ChangeRequestId
import com.normation.rudder.web.services.DisplayNodeGroupTree


object Groups {
  val htmlId_groupTree = "groupTree"
  val htmlId_item = "ajaxItemContainer"
  val htmlId_updateContainerForm = "updateContainerForm"


  private sealed trait RightPanel
  private case object NoPanel extends RightPanel
  private case class GroupForm(group:NodeGroup, parentCategoryId:NodeGroupCategoryId) extends RightPanel with HashcodeCaching
  private case class CategoryForm(category: NodeGroupCategory) extends RightPanel with HashcodeCaching

}



class Groups extends StatefulSnippet with SpringExtendableSnippet[Groups] with Loggable {
  import Groups._

  private[this] val getFullGroupLibrary   = RudderConfig.roNodeGroupRepository.getFullGroupLibrary _
  private[this] val woNodeGroupRepository = RudderConfig.woNodeGroupRepository
  private[this] val uuidGen               = RudderConfig.stringUuidGenerator

  val mainDispatch = {
    Map(
        "head" -> head _
      , "detailsPopup" ->  { _:NodeSeq => NodeGroupForm.staticBody }
      , "initRightPanel" -> { _: NodeSeq => initRightPanel() }
      , "groupHierarchy" -> groupHierarchy(boxGroupLib)
    )
  }

  def extendsAt = SnippetExtensionKey(classOf[Groups].getSimpleName)

  //the current nodeGroupCategoryForm component
  private[this] val nodeGroupCategoryForm = new LocalSnippet[NodeGroupCategoryForm]

  //the current nodeGroupForm component
  private[this] val nodeGroupForm = new LocalSnippet[NodeGroupForm]

  //the popup component
  private[this] val creationPopup = new LocalSnippet[CreateCategoryOrGroupPopup]

  private[this] var boxGroupLib = getFullGroupLibrary()

  /**
   * Head of the portlet, nothing much yet
   * @param html
   * @return
   */
  def head(html:NodeSeq) : NodeSeq = {
    {<head>
      <script type="text/javascript" src="/javascript/jquery/ui/jquery.ui.datepicker.js"></script>
      <script type="text/javascript" src="/javascript/jquery/ui/i18n/jquery-ui-i18n.js"></script>
      <script type="text/javascript" src="/javascript/jstree/jquery.jstree.js" id="jstree"></script>
      <script type="text/javascript" src="/javascript/rudder/tree.js" id="tree"></script>
      <script type="text/javascript" src="/javascript/json2.js" id="json2"></script>
    </head>} ++ NodeGroupForm.staticInit
   }


  /**
   * Display the Groups hierarchy fieldset, with the JS tree
   * @param html
   * @return
   */
  def groupHierarchy(rootCategory: Box[FullNodeGroupCategory]) : CssSel = (
      "#groupTree" #> buildGroupTree("")
    & "#newItem"   #> groupNewItem()
  )

  def groupNewItem() : NodeSeq = {
    <div id="createANewItem">
      { SHtml.ajaxButton("Create a new item", () => showPopup()) }
    </div>
  }

  /**
   * Does the init part (showing the right component and highlighting
   * the tree if necessary)
   */
  def initRightPanel() : NodeSeq = {
    Script(OnLoad(parseJsArg(boxGroupLib)))
  }


  /**
   * If a query is passed as argument, try to dejoniffy-it, in a best effort
   * way - just don't take of errors.
   *
   * We want to look for #{ "groupId":"XXXXXXXXXXXX" }
   */
  private[this] def parseJsArg(rootCategory: Box[FullNodeGroupCategory]) : JsCmd = {
    def displayDetails(groupId:String) = {
      val gid = NodeGroupId(groupId)
      rootCategory match {
        case eb: EmptyBox => Noop
        case Full(lib) => lib.allGroups.get(gid) match {
          case None => Noop
          case Some(fullGroupTarget) => //so we also have its parent category
            //no modification, so no refreshGroupLib
            refreshTree(htmlTreeNodeId(groupId)) &
            refreshRightPanel(GroupForm(fullGroupTarget.nodeGroup, lib.categoryByGroupId(gid))) &
            JsRaw("createTooltip()")
        }
      }
    }

    JsRaw("""
        var groupId = null;
        try {
          groupId = JSON.parse(window.location.hash.substring(1)).groupId ;
        } catch(e) {
          groupId = null
        }
        if( groupId != null && groupId.length > 0) {
          %s;
        }
    """.format(SHtml.ajaxCall(JsVar("groupId"), displayDetails _ )._2.toJsCmd)
    )
  }


  ////////////////////////////////////////////////////////////////////////////////////

  private[this] def htmlTreeNodeId(id:String) = "jsTree-" + id

  /**
   *  Manage the state of what should be displayed on the right panel.
   * It could be nothing, a group edit form, or a category edit form.
   */
  private[this] def setAndShowRightPanel(panel: RightPanel, rootCategory: FullNodeGroupCategory) : NodeSeq = {
    panel match {
      case NoPanel => NodeSeq.Empty
      case GroupForm(group,parentCatId) =>
        val form = new NodeGroupForm(
            htmlId_item
          , group
          , parentCatId
          , rootCategory
          , { (redirect: Either[NodeGroup,ChangeRequestId]) =>
              redirect match {
                case Left(group) =>
                  refreshGroupLib()
                  refreshTree(htmlTreeNodeId(group.id.value))
                case Right(crId) => RedirectTo(s"""/secure/utilities/changeRequest/${crId.value}""")
              }
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

  //utility to refresh right panel
  private[this] def refreshRightPanel(panel:RightPanel) : JsCmd = {
    boxGroupLib match {
      case Full(lib) => SetHtml(htmlId_item, setAndShowRightPanel(panel, lib))
      case eb: EmptyBox =>
        val e = eb ?~! "Error when trying to get the root node group category"
        logger.error(e.messageChain)
        Alert(e.messageChain)
    }
  }

  //that must be separated from refreshTree/refreshRightPanel
  //to avoid duplicate refresh or useless one (when only displaying without modification)
  private[this] def refreshGroupLib() : Unit = {
    boxGroupLib = getFullGroupLibrary()
  }

  private[this] def setCreationPopup(rootCategory: FullNodeGroupCategory) : Unit = {
    creationPopup.set(Full(new CreateCategoryOrGroupPopup(
        None
      , rootCategory
      , onSuccessCategory = displayCategory
      , onSuccessGroup = showGroupSection
      , onSuccessCallback = onSuccessCallback()
    )))
  }

  private[this] def onSuccessCallback() = {
    (id: String) => {refreshGroupLib; refreshTree(htmlTreeNodeId(id)) }
  }


  /**
   * build the tree of categories and group and init its JS
   */
  private[this] def buildGroupTree(selectedNode: String) : NodeSeq = {
    boxGroupLib match {
      case eb: EmptyBox =>
        val e = eb ?~! "Can not get the group library"
        logger.error(e.messageChain)
        e.rootExceptionCause.foreach { ex =>
          logger.error("Root exception was:", ex)
        }

        <div id={htmlId_groupTree}>Error: {e.msg}</div>
      case Full(lib) =>
      (
        <div id={htmlId_groupTree}>
          <ul>{DisplayNodeGroupTree.displayTree(
                  groupLib = lib
                , onClickCategory = Some(fullDisplayCategory)
                , onClickTarget  = Some(showTargetInfo)
           )}</ul>
        </div>
      ) ++ Script(OnLoad(
      //build jstree and
      //init bind callback to move
      JsRaw("""
        buildGroupTree('#%1$s','%5$s', '%4$s', 'off', true);
        $('#%1$s').bind("move_node.jstree", function (e,data) {
          var sourceCatId = $(data.rslt.o).attr("catId");
          var sourceGroupId = $(data.rslt.o).attr("groupId");
          var destCatId = $(data.rslt.np).attr("catId");
          if( destCatId ) {
            if(sourceGroupId) {
              var arg = JSON.stringify({ 'sourceGroupId' : sourceGroupId, 'destCatId' : destCatId });
              %2$s;
            } else if(  sourceCatId ) {
              var arg = JSON.stringify({ 'sourceCatId' : sourceCatId, 'destCatId' : destCatId });
              %3$s;
            } else {
              alert("Can not move that kind of object");
              $.jstree.rollback(data.rlbk);
            }
          } else {
            alert("Can not move to something else than a category");
            $.jstree.rollback(data.rlbk);
          }
        });
      """.format(
        // %1$s
        htmlId_groupTree ,
        // %2$s
        SHtml.ajaxCall(JsVar("arg"), moveGroup(lib) _)._2.toJsCmd,
        // %3$s
        SHtml.ajaxCall(JsVar("arg"), moveCategory(lib) _ )._2.toJsCmd,
        // %4$s
        selectedNode ,
        S.contextPath
/*        { nodeGroupId match {
            case Full(x) => "jsTree-"+x
            case _ => ""
          }
        }
 */
      )))
    )}
  }

  /**
   * Create the popup
   */
  private[this] def createPopup : NodeSeq = {
    creationPopup.is match {
      case Failure(m,_,_) =>  <span class="error">Error: {m}</span>
      case Empty => <div>The component is not set</div>
      case Full(popup) => popup.popupContent()
    }
  }

  ///////////////////// Callback function for Drag'n'drop in the tree /////////////////////
  private[this] def moveGroup(lib:FullNodeGroupCategory)(arg: String) : JsCmd = {
    //parse arg, which have to  be json object with sourceGroupId, destCatId
    try {
      (for {
         JObject(child) <- JsonParser.parse(arg)
         JField("sourceGroupId", JString(sourceGroupId)) <- child
         JField("destCatId", JString(destCatId)) <- child
       } yield {
         (sourceGroupId, destCatId)
       }) match {
        case (sourceGroupId, destCatId) :: Nil =>
          (for {
            result <- woNodeGroupRepository.move(NodeGroupId(sourceGroupId), NodeGroupCategoryId(destCatId), ModificationId(uuidGen.newUuid), CurrentUser.getActor, Some("Group moved by user"))?~! "Error while trying to move group with requested id '%s' to category id '%s'".format(sourceGroupId,destCatId)
            group  <- Box(lib.allGroups.get(NodeGroupId(sourceGroupId))) ?~! s"No such group: ${sourceGroupId}"
          } yield {
            (group.nodeGroup, lib.categoryByGroupId(group.nodeGroup.id))
          }) match {
            case Full((ng,cat)) =>
              refreshGroupLib()
              (
                  refreshTree(htmlTreeNodeId(ng.id.value))
                & JsRaw("""setTimeout(function() { $("[groupid=%s]").effect("highlight", {}, 2000)}, 100)""".format(sourceGroupId))
                & refreshRightPanel(GroupForm(ng,cat))
              )
            case f:Failure => Alert(f.messageChain + "\nPlease reload the page")
            case Empty => Alert("Error while trying to move group with requested id '%s' to category id '%s'\nPlease reload the page.".format(sourceGroupId,destCatId))
          }
        case _ => Alert("Error while trying to move group: bad client parameters")
      }
    } catch {
      case e:Exception => Alert("Error while trying to move group")
    }
  }

  private[this] def moveCategory(lib: FullNodeGroupCategory)(arg: String) : JsCmd = {
    //parse arg, which have to  be json object with sourceGroupId, destCatId
    try {
      (for {
         JObject(child) <- JsonParser.parse(arg)
         JField("sourceCatId", JString(sourceCatId)) <- child
         JField("destCatId", JString(destCatId)) <- child
       } yield {
         (sourceCatId, destCatId)
       }) match {
        case (sourceCatId, destCatId) :: Nil =>
          (for {
            category <- Box(lib.allCategories.get(NodeGroupCategoryId(sourceCatId))) ?~! "Error while trying to find category with requested id %s".format(sourceCatId)

            result <- woNodeGroupRepository.saveGroupCategory(
                          category.toNodeGroupCategory
                        , NodeGroupCategoryId(destCatId)
                        , ModificationId(uuidGen.newUuid)
                        , CurrentUser.getActor
                        , reason = None)?~! "Error while trying to move category with requested id '%s' to category id '%s'".format(sourceCatId,destCatId)
          } yield {
            (category.id.value, result)
          }) match {
            case Full((id,res)) =>
              refreshGroupLib
              (
                  refreshTree(htmlTreeNodeId(id))
                & OnLoad(JsRaw("""setTimeout(function() { $("[catid=%s]").effect("highlight", {}, 2000);}, 100)""".format(sourceCatId)))
                & refreshRightPanel(CategoryForm(res))
              )
            case f:Failure => Alert(f.messageChain + "\nPlease reload the page")
            case Empty => Alert("Error while trying to move category with requested id '%s' to category id '%s'\nPlease reload the page.".format(sourceCatId,destCatId))
          }
        case _ => Alert("Error while trying to move group: bad client parameters")
      }
    } catch {
      case e:Exception => Alert("Error while trying to move group")
    }
  }

  ////////////////////

  private[this] def refreshTree(selectedNode:String) : JsCmd =  {
    Replace(htmlId_groupTree, buildGroupTree(selectedNode:String)) &
    OnLoad(After(TimeSpan(50), JsRaw("""createTooltip();""")))
  }

 /********************************************
  * Utilitary methods for JS
  ********************************************/

  private[this] def displayCategory(category : NodeGroupCategory) : JsCmd = {
    //update UI - no modification here, so no refreshGroupLib
    refreshRightPanel(CategoryForm(category))
  }

  //adaptater
  private[this] def fullDisplayCategory(category: FullNodeGroupCategory) = displayCategory(category.toNodeGroupCategory)

  private[this] def showGroupSection(g: NodeGroup, parentCategoryId: NodeGroupCategoryId) = {
    refreshRightPanel(GroupForm(g, parentCategoryId))&
    JsRaw(s"""this.window.location.hash = "#" + JSON.stringify({'groupId':'${g.id.value}'})""")
  }

  private[this] def showTargetInfo(parentCategory: FullNodeGroupCategory, targetInfo: FullRuleTargetInfo) : JsCmd = {
    //update UI - no modeification here, so no refreshGroupLib

    //action only for node group

    targetInfo.target match {
      case t:FullGroupTarget => showGroupSection(t.nodeGroup, parentCategory.id)
      case _ => Noop
    }
  }


  private[this] def showPopup() : JsCmd = {

    boxGroupLib match {
      case eb: EmptyBox => Alert("Error when trying to get the list of categories")
      case Full(rootCategory) =>
        setCreationPopup(rootCategory)

        //update UI
        SetHtml("createGroupContainer", createPopup) &
        JsRaw( """ createPopup("createGroupPopup")""")
    }
  }
}


