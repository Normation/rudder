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
import com.normation.eventlog.ModificationId
import com.normation.inventory.domain.AcceptedInventory
import com.normation.inventory.domain.FullInventory
import com.normation.inventory.domain.NodeId
import com.normation.plugins.DefaultExtendableSnippet
import com.normation.rudder.batch.AutomaticStartDeployment
import com.normation.rudder.domain.Constants
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.domain.nodes.NodeState
import com.normation.rudder.domain.policies.GlobalPolicyMode
import com.normation.rudder.reports.AgentRunInterval
import com.normation.rudder.repository.FullNodeGroupCategory
import com.normation.rudder.web.ChooseTemplate
import com.normation.rudder.web.model.JsNodeId
import com.normation.rudder.web.services.CurrentUser
import com.normation.rudder.web.services.DisplayNode
import com.normation.rudder.web.services.DisplayNodeGroupTree
import net.liftweb.common._
import net.liftweb.http.DispatchSnippet
import net.liftweb.http.S
import net.liftweb.http.js.JE.JsRaw
import net.liftweb.http.js.JsCmds._
import net.liftweb.http.js.JsExp
import net.liftweb.util.Helpers._
import scala.xml.NodeSeq

object ShowNodeDetailsFromNode {

  private val groupTreeId = "node_groupTree"

  private def serverDetailsTemplate: NodeSeq = ChooseTemplate(
    List("templates-hidden", "server", "server_details"),
    "detail-server"
  )

  sealed trait DisplayDetailsMode { def tab: Int }
  case object Summary    extends DisplayDetailsMode { override def tab = 0 }
  case object Compliance extends DisplayDetailsMode { override def tab = 1 }
  case object System     extends DisplayDetailsMode { override def tab = 4 }
}

class ShowNodeDetailsFromNode(
    val nodeId: NodeId,
    groupLib:   FullNodeGroupCategory
) extends DispatchSnippet with DefaultExtendableSnippet[ShowNodeDetailsFromNode] with Loggable {
  import ShowNodeDetailsFromNode._

  private[this] val nodeInfoService      = RudderConfig.nodeInfoService
  private[this] val nodeFactRepo         = RudderConfig.nodeFactRepository
  private[this] val reportDisplayer      = RudderConfig.reportDisplayer
  private[this] val logDisplayer         = RudderConfig.logDisplayer
  private[this] val uuidGen              = RudderConfig.stringUuidGenerator
  private[this] val nodeRepo             = RudderConfig.woNodeRepository
  private[this] val asyncDeploymentAgent = RudderConfig.asyncDeploymentAgent
  private[this] val configService        = RudderConfig.configService
  private[this] val boxNodeInfo          = nodeInfoService.getNodeInfo(nodeId).toBox

  def agentPolicyModeEditForm = new AgentPolicyModeEditForm()

  def agentScheduleEditForm(nodeInfo: NodeInfo) = new AgentScheduleEditForm(
    () => getSchedule(nodeInfo),
    saveSchedule(nodeInfo),
    () => (),
    () => Some(getGlobalSchedule())
  )

  def nodeStateEditForm(nodeInfo: NodeInfo) = new NodeStateForm(
    nodeInfo,
    saveNodeState(nodeInfo.id)
  )

  def saveNodeState(nodeId: NodeId)(nodeState: NodeState): Box[NodeState] = {
    val modId = ModificationId(uuidGen.newUuid)
    val user  = CurrentUser.actor

    for {
      oldNode <- nodeInfoService.getNodeInfo(nodeId).toBox.flatMap(_.map(_.node)) // we can't change the state of a missing node
      newNode  = oldNode.copy(state = nodeState)
      result  <- nodeRepo.updateNode(newNode, modId, user, None).toBox
    } yield {
      asyncDeploymentAgent ! AutomaticStartDeployment(modId, CurrentUser.actor)
      nodeState
    }
  }

  def getGlobalSchedule(): Box[AgentRunInterval] = {
    for {
      starthour <- configService.agent_run_start_hour()
      startmin  <- configService.agent_run_start_minute()
      splaytime <- configService.agent_run_splaytime()
      interval  <- configService.agent_run_interval()
    } yield {
      AgentRunInterval(
        None,
        interval,
        startmin,
        starthour,
        splaytime
      )
    }
  }.toBox

  val emptyInterval = AgentRunInterval(Some(false), 5, 0, 0, 0) // if everything fails, we fall back to the default entry
  def getSchedule(nodeInfo: NodeInfo): Box[AgentRunInterval] = {
    Full(nodeInfo.nodeReportingConfiguration.agentRunInterval.getOrElse(getGlobalSchedule().getOrElse(emptyInterval)))
  }

  def saveSchedule(nodeInfo: NodeInfo)(schedule: AgentRunInterval): Box[Unit] = {
    val modId       = ModificationId(uuidGen.newUuid)
    val user        = CurrentUser.actor
    val newNodeInfo = nodeInfo.copy(
      nodeInfo.node.copy(nodeReportingConfiguration =
        nodeInfo.node.nodeReportingConfiguration.copy(agentRunInterval = Some(schedule))
      )
    )
    (for {
      _ <- nodeRepo.updateNode(newNodeInfo.node, modId, user, None)
    } yield {
      asyncDeploymentAgent ! AutomaticStartDeployment(modId, CurrentUser.actor)
    }).toBox
  }

  def mainDispatch = Map(
    "popupDetails"    -> { _: NodeSeq => privateDisplay(true, Summary) },
    "popupCompliance" -> { _: NodeSeq => privateDisplay(true, Compliance) },
    "popupSystem"     -> { _: NodeSeq => privateDisplay(true, System) },
    "mainDetails"     -> { _: NodeSeq => privateDisplay(false, Summary) },
    "mainCompliance"  -> { _: NodeSeq => privateDisplay(false, Compliance) },
    "mainSystem"      -> { _: NodeSeq => privateDisplay(false, System) }
  )

  def display(popupDisplay: Boolean, displayDetailsMode: DisplayDetailsMode): NodeSeq = {
    val dispatchName = (popupDisplay, displayDetailsMode) match {
      case (true, System)      => "popupSystem"
      case (true, Compliance)  => "popupCompliance"
      case (true, Summary)     => "popupDetails"
      case (false, System)     => "mainSystem"
      case (false, Compliance) => "mainCompliance"
      case (false, Summary)    => "mainDetails"
    }
    dispatch(dispatchName)(NodeSeq.Empty)
  }

  private[this] def privateDisplay(withinPopup: Boolean, displayDetailsMode: DisplayDetailsMode): NodeSeq = {
    boxNodeInfo match {
      case Full(None) =>
        (<ul id="NodeDetailsTabMenu" class="rudder-ui-tabs ui-tabs-nav"></ul>
          <div class="col-xs-12">
            <div class="info-card critical">
              <div class="card-info">
                <h4><span>Node not found</span> <i class="info-icon ion ion-alert-circled"></i></h4>
                <div class="card-information-details">
                  Node with id {nodeId.value} was not found
                </div>
              </div>
            </div>
          </div>)
      case eb: EmptyBox =>
        val e = eb ?~! s"Error when getting node with id '${nodeId.value}'"
        logger.debug("Root exception:", e)
        <div class="error">
          <p>Node with id {nodeId.value} was not found</p>
          <p>Error message was: {e.messageChain}</p>
        </div>
      case Full(Some(node)) => // currentSelectedNode = Some(server)
        nodeFactRepo.slowGet(node.id)(CurrentUser.queryContext).toBox match {
          case Full(Some(nf)) =>
            val tab  = displayDetailsMode.tab
            val jsId = JsNodeId(nodeId, "")
            def htmlId(jsId: JsNodeId, prefix: String): String = prefix + jsId.toString
            val detailsId = htmlId(jsId, "details_")
            configService.rudder_global_policy_mode().toBox match {
              case Full(globalMode) =>
                bindNode(node, nf.toFullInventory, withinPopup, globalMode) ++ Script(
                  DisplayNode.jsInit(node.id, "") &
                  JsRaw(s"""
                    $$('#nodeHostname').html("${xml.Utility.escape(nf.fqdn)}");
                    $$( "#${detailsId}" ).tabs({ active : ${tab} } );
                    $$('#nodeInventory .ui-tabs-vertical .ui-tabs-nav li a').on('click',function(){
                      var tab = $$(this).attr('href');
                      $$('#nodeInventory .ui-tabs-vertical .ui-tabs-nav li a.active').removeClass('active');
                      $$(this).addClass('active');
                      $$('#nodeInventory > .sInventory > .sInventory').hide();
                      $$(tab).show();
                    });
                    """) &
                  buildJsTree(groupTreeId)
                )
              case e: EmptyBox =>
                val msg = e ?~! s"Could not get global policy mode when getting node '${node.id.value}' details"
                logger.error(msg, e)
                <div class="error">{msg}</div>
            }
          case Full(None)     =>
            val msg = "Can not find inventory details for node with ID %s".format(node.id.value)
            logger.error(msg)
            <div class="error">{msg}</div>
          case e: EmptyBox =>
            val msg = "Can not find inventory details for node with ID %s".format(node.id.value)
            logger.error(msg, e)
            <div class="error">{msg}</div>
        }
    }
  }

  /**
   * Show the content of a node in the portlet
   * @return
   */
  private def bindNode(node: NodeInfo, inventory: FullInventory, withinPopup: Boolean, globalMode: GlobalPolicyMode): NodeSeq = {
    val id = JsNodeId(node.id)
    ("#node_groupTree" #>
    <div id={groupTreeId}>
          <ul>{DisplayNodeGroupTree.buildTreeKeepingGroupWithNode(groupLib, node, None, None, Map(("info", _ => Noop)))}</ul>
        </div> &
    "#nodeDetails" #> DisplayNode.showNodeDetails(
      inventory,
      Some((node, globalMode)),
      Some(node.creationDate),
      AcceptedInventory,
      isDisplayingInPopup = withinPopup
    ) &
    "#nodeInventory *" #> DisplayNode.showInventoryVerticalMenu(inventory, Some(node)) &
    "#reportsDetails *" #> reportDisplayer.asyncDisplay(
      node,
      "node_reports",
      "reportsDetails",
      "reportsGrid",
      RudderConfig.reportingService.findUserNodeStatusReport,
      true
    ) &
    "#systemStatus *" #> reportDisplayer.asyncDisplay(
      node,
      "system_status",
      "systemStatus",
      "systemStatusGrid",
      RudderConfig.reportingService.findSystemNodeStatusReport,
      false
    ) &
    "#nodeProperties *" #> DisplayNode.displayTabProperties(id, node, inventory: FullInventory) &
    "#logsDetails *" #> Script(OnLoad(logDisplayer.asyncDisplay(node.id, None, "logsGrid"))) &
    "#node_parameters -*" #> (if (node.id == Constants.ROOT_POLICY_SERVER_ID) NodeSeq.Empty
                              else nodeStateEditForm(node).nodeStateConfiguration) &
    "#node_parameters -*" #> agentPolicyModeEditForm.cfagentPolicyModeConfiguration(Some(node.id)) &
    "#node_parameters -*" #> (if (node.isPolicyServer) NodeSeq.Empty
                              else agentScheduleEditForm(node).cfagentScheduleConfiguration) &
    "#node_tabs [id]" #> s"details_${id}").apply(serverDetailsTemplate)
  }

  /**
   * Javascript to initialize a tree.
   * htmlId is the id of the div enclosing tree datas
   */
  private def buildJsTree(htmlId: String): JsExp = JsRaw(
    s"""buildGroupTree('#${htmlId}', '${S.contextPath}', [], 'on', undefined, false)"""
  )

}
