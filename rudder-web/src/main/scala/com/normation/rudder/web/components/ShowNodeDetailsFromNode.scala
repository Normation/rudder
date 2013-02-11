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

import com.normation.rudder.domain.policies.GroupTarget
import com.normation.inventory.ldap.core.InventoryDit
import com.normation.inventory.domain._
import com.normation.inventory.services.core.FullInventoryRepository
import com.normation.rudder.services.nodes._
import com.normation.rudder.domain.nodes.{NodeGroupCategory, NodeInfo, NodeGroup}
import com.normation.rudder.repository.{NodeGroupCategoryRepository, NodeGroupRepository}
import bootstrap.liftweb.LiftSpringApplicationContext.inject
import com.normation.rudder.domain.queries.Query
import com.normation.rudder.services.policies.{DependencyAndDeletionService, RuleTargetService}
import com.normation.rudder.domain.policies.{RuleTargetInfo, GroupTarget, RuleTarget}
import com.normation.rudder.web.model.JsTreeNode
import com.normation.utils.Control.sequence
import com.normation.rudder.web.components.popup.CreateCategoryOrGroupPopup
import com.normation.rudder.domain.nodes.{NodeGroup,  NodeInfo}
import com.normation.rudder.web.services.{LogDisplayer, ReportDisplayer, DisplayNode}
import com.normation.inventory.ldap.core.LDAPFullInventoryRepository
import com.normation.exceptions.TechnicalException

//lift std import
import scala.xml._
import net.liftweb.common._
import net.liftweb.http._
import net.liftweb.util._
import Helpers._
import net.liftweb.http.js._
import JsCmds._ // For implicits
import JE._
import net.liftmodules.widgets.autocomplete._



object ShowNodeDetailsFromNode {

  /**
   * All pages that use that component should include the result of that
   * method
   */
  def staticInit : NodeSeq = {
    <head>
      <script type="text/javascript" src="/javascript/jstree/jquery.jstree.js" id="jstree"></script>
      <script type="text/javascript" src="/javascript/rudder/tree.js" id="tree"></script>
    </head>
  }


  private val htmlId_crTree = "crTree"

  private def serverPortletPath = List("templates-hidden", "server", "server_details")
  private def serverPortletTemplateFile() =  Templates(serverPortletPath) match {
    case Empty | Failure(_,_,_) =>
      throw new TechnicalException("Template for node details not found. I was looking for %s.html".format(serverPortletPath.mkString("/")))
    case Full(n) => n
  }
  private def serverDetailsTemplate = chooseTemplate("detail","server",serverPortletTemplateFile)
}

class ShowNodeDetailsFromNode(
  nodeId:NodeId
) extends DispatchSnippet with Loggable {
  import ShowNodeDetailsFromNode._

  private[this] val nodeInfoService = inject[NodeInfoService]
  private[this] val serverAndMachineRepo = inject[LDAPFullInventoryRepository]
  private[this] val acceptedInventoryDit = inject[InventoryDit]("acceptedNodesDit")
  private[this] val reportDisplayer = inject[ReportDisplayer]
  private[this] val logDisplayer = inject[LogDisplayer]

  // to create the JsTree with the Group/CR
  private[this] val nodeGroupRepository = inject[NodeGroupRepository]
  private[this] val groupCategoryRepository = inject[NodeGroupCategoryRepository]
  private[this] val targetService = inject[RuleTargetService]
  private[this] val dependencyService = inject[DependencyAndDeletionService]

  def dispatch = {
    case "display" => { _ => display(false) }
  }


  def display(withinPopup : Boolean = false) : NodeSeq = {
    nodeInfoService.getNodeInfo(nodeId) match {
      case Empty =>
        <div class="error">Node with id {nodeId.value} was not found</div>
      case f@Failure(_,_,_) =>
        logger.debug("Root exception:", f)
        <div class="error">
          <p>Node with id {nodeId.value} was not found</p>
          <p>Error message was: {f.messageChain}</p>
        </div>
      case Full(server) => // currentSelectedNode = Some(server)
        serverAndMachineRepo.get(server.id,AcceptedInventory) match {
          case Full(sm) =>
            bindNode(server, sm, withinPopup) ++ Script(OnLoad(
              DisplayNode.jsInit(server.id, sm.node.softwareIds, "", Some("node_tabs")) &
              //reportDisplayer.initJs("reportsGrid") &
              OnLoad(buildJsTree(htmlId_crTree))
            ))
          case e:EmptyBox =>
            val msg = "Can not find inventory details for node with ID %s".format(server.id.value)
            logger.error(msg, e)
            <div class="error">{msg}</div>
        }
    }
  }

  /**
   * Show the content of a node in the portlet
   * @param server
   * @return
   */
  private def bindNode(node : NodeInfo, inventory: FullInventory, withinPopup : Boolean = false) : NodeSeq = {
      bind("server", serverDetailsTemplate,
             "header" ->
              <div id="node_header" class="nodeheader">
                <div class="nodeheadercontent ui-corner-top"> Node Details - {inventory.node.main.hostname}
                 (last updated { inventory.node.inventoryDate.map(DateFormaterService.getFormatedDate(_)).getOrElse("Unknown")}) </div>
              </div>,
             "jsTree" ->
              <div id={htmlId_crTree}>
                <ul>{buildTree(node)}</ul>
              </div>,
              "nodeDetails" -> DisplayNode.showNodeDetails(inventory, Some(node.creationDate), isDisplayingInPopup = withinPopup),
              "inventory" -> DisplayNode.show(inventory, false),
              "extraHeader" -> DisplayNode.showExtraHeader(inventory),
              "extraContent" -> DisplayNode.showExtraContent(inventory),
              "reports" -> reportDisplayer.asyncDisplay(node),
              "logs" -> logDisplayer.asyncDisplay(node.id, withinPopup)
            )
  }

  private def findTargets(node : NodeInfo): Box[Seq[RuleTarget]] = {
    for {
      allTargets <- targetService.findTargets(Seq(node.id)) //allTargets: Map[NodeId, Set[RuleTarget]]
      targets = allTargets.values.flatMap(_.toSeq).filter(x => x.isInstanceOf[GroupTarget]).toSeq
    } yield {
      targets
    }
  }


  private def buildTree(node : NodeInfo) : NodeSeq = {
    findTargets(node) match {
      case Full(seq) => targets = seq
            groupCategoryRepository.findGroupHierarchy(
            groupCategoryRepository.getRootCategory().id,
            seq) match {
          case Empty => <div>This node is not contained in any group</div>
          case f:Failure => <div class="error">{f.messageChain}</div>
          case Full(category) => categoryToJsTreeNode(category).toXml
      }
      case Empty => <div>This node is not contained in any group</div>
      case f:Failure => <div class="error">{f.messageChain}</div>
    }
  }


 /********************************************
  * Utilitary methods for JSTree
  ********************************************/
  private[this] val rootCategoryId = groupCategoryRepository.getRootCategory.id
  private[this] var targets :  Seq[RuleTarget] = Seq()

  /**
   * Javascript to initialize a tree.
   * htmlId is the id of the div enclosing tree datas
   */
  private def buildJsTree(htmlId:String) : JsExp = JsRaw(
    """buildGroupTree('#%s', '%s')""".format(htmlId,S.contextPath)
  )

  private[this] def categoryToJsTreeNode(category:NodeGroupCategory) : JsTreeNode = new JsTreeNode {
    override def body = {
        <a><span class="treeGroupCategoryName tooltipable" title="" tooltipid={category.id.value.replaceAll("/", "")} >{category.name}</span></a>
        <div class="tooltipContent" id={category.id.value.replaceAll("/", "")}><h3>{category.name}</h3><div>{category.description}</div></div>
    }

    override val attrs =
      ( "rel" -> { if(category.id == rootCategoryId) "root-category" else "category" } ) ::
      ( "catId" -> category.id.value ) ::
      ( "class" -> "" ) ::
      Nil

    override def children = (
      category.children.map(x =>  groupCategoryRepository.findGroupHierarchy(x, targets)).collect { case Full(x) => categoryToJsTreeNode(x) }
      ++ category.items.map(x => policyTargetInfoToJsTreeNode(x) )
    )
  }


  //fetch server group id and transform it to a tree node
  private[this] def policyTargetInfoToJsTreeNode(targetInfo:RuleTargetInfo) : JsTreeNode = {
    targetInfo.target match {
      case GroupTarget(id) =>
        nodeGroupRepository.getNodeGroup(id) match {
          case Full(group) => nodeGroupToJsTreeNode(group)
          case _ => new JsTreeNode {
            override def body = <span class="error">Can not find node {id.value}</span>
            override def children = Nil
          }
        }
      case x => new JsTreeNode {
         override def body = { <span class="treeGroupName tooltipable" title="" tooltipid={targetInfo.target.target} >{targetInfo.name} <span class="greyscala">(special)</span></span>
             <div class="tooltipContent" id={targetInfo.target.target}><h3>{targetInfo.name}</h3><div>{targetInfo.description}</div></div>
          }
         override def children = Nil
         override val attrs = ( "rel" -> "special_target" ) :: Nil
      }
    }
  }

  /**
   * Transform a WBNodeGroup into a JsTree leaf.
   */
  private[this] def nodeGroupToJsTreeNode(group : NodeGroup) : JsTreeNode = new JsTreeNode {

    override def body = <a href="#"><span class="treeGroupName">{List(group.name,group.isDynamic?"dynamic"|"static").mkString(": ")}</span></a>
    override def children = Nil
    override val attrs =
      ( "rel" -> "group" ) ::
      ( "groupId" -> group.id.value ) ::
      Nil
  }
}
