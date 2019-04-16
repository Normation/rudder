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

import scala.xml.NodeSeq
import com.normation.inventory.domain.AcceptedInventory
import com.normation.inventory.domain.FullInventory
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.repository.FullNodeGroupCategory
import com.normation.rudder.web.services.DisplayNode
import com.normation.rudder.web.services.DisplayNodeGroupTree
import com.normation.rudder.reports.AgentRunInterval
import com.normation.eventlog.ModificationId
import com.normation.rudder.web.model.CurrentUser
import com.normation.rudder.batch.AutomaticStartDeployment
import bootstrap.liftweb.RudderConfig
import net.liftweb.common._
import net.liftweb.http.DispatchSnippet
import net.liftweb.http.S
import net.liftweb.http.js.JE.JsRaw
import net.liftweb.http.js.JsCmds._
import net.liftweb.http.js.JsExp
import net.liftweb.util.Helpers._
import com.normation.plugins.DefaultExtendableSnippet
import com.normation.rudder.domain.Constants
import com.normation.rudder.reports.HeartbeatConfiguration
import com.normation.rudder.web.model.JsNodeId
import com.normation.rudder.reports.NodeComplianceMode
import com.normation.rudder.reports.GlobalComplianceMode
import com.normation.rudder.domain.policies.GlobalPolicyMode
import com.normation.rudder.web.ChooseTemplate
import com.normation.rudder.domain.nodes.NodeState

import com.normation.box._

object ShowNodeDetailsFromNode {

  private val groupTreeId = "node_groupTree"

  private def serverDetailsTemplate = ChooseTemplate(
      List("templates-hidden", "server", "server_details")
    , "detail-server"
  )
}

class ShowNodeDetailsFromNode(
    val nodeId : NodeId
  , groupLib   : FullNodeGroupCategory
) extends DispatchSnippet with DefaultExtendableSnippet[ShowNodeDetailsFromNode] with Loggable {
  import ShowNodeDetailsFromNode._

  private[this] val nodeInfoService      = RudderConfig.nodeInfoService
  private[this] val serverAndMachineRepo = RudderConfig.fullInventoryRepository
  private[this] val reportDisplayer      = RudderConfig.reportDisplayer
  private[this] val logDisplayer         = RudderConfig.logDisplayer
  private[this] val uuidGen              = RudderConfig.stringUuidGenerator
  private[this] val nodeRepo             = RudderConfig.woNodeRepository
  private[this] val asyncDeploymentAgent = RudderConfig.asyncDeploymentAgent
  private[this] val configService        = RudderConfig.configService
  private[this] var boxNodeInfo          = nodeInfoService.getNodeInfo(nodeId)

  def complianceModeEditForm(nodeInfo : NodeInfo) = {
    val (globalMode, nodeMode) = {
      val modes = getHeartBeat(nodeInfo)
      (modes.map(_._1),modes.map(_._2))
    }

    new ComplianceModeEditForm(
        nodeMode
      , saveHeart(nodeInfo)
      , () => Unit
      , globalMode
    )
  }
  def getHeartBeat(nodeInfo : NodeInfo) : Box[(GlobalComplianceMode,NodeComplianceMode)] = {
    for {
      globalMode  <- configService.rudder_compliance_mode().toBox

    } yield {
      // If heartbeat is not overriden, we revert to the default one
      val defaultHeartBeat = HeartbeatConfiguration(false, globalMode.heartbeatPeriod)
      val hbConf = nodeInfo.nodeReportingConfiguration.heartbeatConfiguration.getOrElse(defaultHeartBeat)
      val nodeMode =  NodeComplianceMode(globalMode.mode,hbConf.heartbeatPeriod,hbConf.overrides)
      (globalMode,nodeMode)
    }
  }

  def saveHeart(nodeInfo : NodeInfo)( complianceMode : NodeComplianceMode) : Box[Unit] = {
    val heartbeatConfiguration = HeartbeatConfiguration(complianceMode.overrideGlobal, complianceMode.heartbeatPeriod)
    val modId = ModificationId(uuidGen.newUuid)
    boxNodeInfo = Full(Some(nodeInfo.copy( nodeInfo.node.copy( nodeReportingConfiguration = nodeInfo.node.nodeReportingConfiguration.copy(heartbeatConfiguration = Some(heartbeatConfiguration))))))
    for {
      result <- nodeRepo.updateNode(nodeInfo.node, modId, CurrentUser.actor, None).toBox
    } yield {
      asyncDeploymentAgent ! AutomaticStartDeployment(modId, CurrentUser.actor)
    }
  }

  def agentPolicyModeEditForm = new AgentPolicyModeEditForm()

  def agentScheduleEditForm(nodeInfo : NodeInfo) = new AgentScheduleEditForm(
     () => getSchedule(nodeInfo)
     , saveSchedule(nodeInfo)
     , () => Unit
     , () => Some(getGlobalSchedule)
   )

  def nodeStateEditForm(nodeInfo: NodeInfo) = new NodeStateForm(
      nodeInfo
    , saveNodeState(nodeInfo.id)
  )

  def saveNodeState(nodeId: NodeId)(nodeState: NodeState): Box[NodeState] = {
    val modId =  ModificationId(uuidGen.newUuid)
    val user  =  CurrentUser.actor

    for {
      oldNode <- nodeInfoService.getNodeInfo(nodeId).flatMap( _.map( _.node )) // we can't change the state of a missing node
      newNode =  oldNode.copy(state = nodeState)
      result  <- nodeRepo.updateNode(newNode, modId, user, None).toBox
    } yield {
      asyncDeploymentAgent ! AutomaticStartDeployment(modId, CurrentUser.actor)
      nodeState
    }
  }

  def getGlobalSchedule() : Box[AgentRunInterval] = {
    for {
      starthour <- configService.agent_run_start_hour
      startmin  <- configService.agent_run_start_minute
      splaytime <- configService.agent_run_splaytime
      interval  <- configService.agent_run_interval
    } yield {
      AgentRunInterval(
            None
          , interval
          , startmin
          , starthour
          , splaytime
        )
    }
  }.toBox

  val emptyInterval = AgentRunInterval(Some(false), 5, 0, 0, 0) // if everything fails, we fall back to the default entry
  def getSchedule(nodeInfo : NodeInfo) : Box[AgentRunInterval] = {
     Full( nodeInfo.nodeReportingConfiguration.agentRunInterval.getOrElse(getGlobalSchedule.getOrElse(emptyInterval)))
  }

  def saveSchedule(nodeInfo : NodeInfo)( schedule: AgentRunInterval) : Box[Unit] = {
    val modId =  ModificationId(uuidGen.newUuid)
    val user  =  CurrentUser.actor
    val newNodeInfo = nodeInfo.copy( nodeInfo.node.copy( nodeReportingConfiguration = nodeInfo.node.nodeReportingConfiguration.copy(agentRunInterval = Some(schedule))))
    boxNodeInfo = Full(Some(newNodeInfo))
    for {
      oldNode <- nodeInfoService.getNodeInfo(nodeId).flatMap( _.map( _.node ))
      result  <- nodeRepo.updateNode(newNodeInfo.node, modId, user, None).toBox
    } yield {
      asyncDeploymentAgent ! AutomaticStartDeployment(modId, CurrentUser.actor)
    }
  }

  def mainDispatch = Map(
    "popupDetails"    -> { _:NodeSeq => privateDisplay(true, false)  }
  , "popupCompliance" -> { _:NodeSeq => privateDisplay(true, true)   }
  , "mainDetails"     -> { _:NodeSeq => privateDisplay(false, false) }
  , "mainCompliance"  -> { _:NodeSeq => privateDisplay(false, true)  }
  )

  def display(popupDisplay : Boolean, complianceDisplay : Boolean) : NodeSeq = {
    val dispatchName = (popupDisplay, complianceDisplay) match {
      case (true, true)  => "popupCompliance"
      case (true, _)     => "popupDetails"
      case (false, true) => "mainCompliance"
      case (false, _)    => "mainDetails"
    }
    dispatch (dispatchName) (NodeSeq.Empty)
  }

  private[this] def privateDisplay(withinPopup : Boolean, displayCompliance : Boolean) : NodeSeq = {
    boxNodeInfo match {
      case Full(None) =>
        <div class="error">Node with id {nodeId.value} was not found</div>
      case eb:EmptyBox =>
        val e = eb ?~! s"Error when getting node with id '${nodeId.value}'"
        logger.debug("Root exception:", e)
        <div class="error">
          <p>Node with id {nodeId.value} was not found</p>
          <p>Error message was: {e.messageChain}</p>
        </div>
      case Full(Some(node)) => // currentSelectedNode = Some(server)
        serverAndMachineRepo.get(node.id, AcceptedInventory).toBox match {
          case Full(Some(sm)) =>
            val tab = if (displayCompliance) 1 else 0
            val jsId = JsNodeId(nodeId,"")
            def htmlId(jsId:JsNodeId, prefix:String) : String = prefix + jsId.toString
            val detailsId = htmlId(jsId,"details_")
            configService.rudder_global_policy_mode().toBox match {
              case Full(globalMode) =>

                bindNode(node, sm, withinPopup,displayCompliance, globalMode) ++ Script(
                  DisplayNode.jsInit(node.id, sm.node.softwareIds, "") &
                  JsRaw(s"""
                    $$('.portlet-header.page-title').html("<span>Node: ${sm.node.main.hostname}</span><span class='update-info'>last updated ${sm.node.inventoryDate.map(DateFormaterService.getFormatedDate(_)).getOrElse("Unknown")}</span>");
                    $$( "#${detailsId}" ).tabs({ active : ${tab} } );
                    $$('#nodeInventory .ui-tabs-vertical .ui-tabs-nav li a').on('click',function(){
                      var tab = $$(this).attr('href');
                      $$('#nodeInventory .ui-tabs-vertical .ui-tabs-nav li a.active').removeClass('active');
                      $$(this).addClass('active');
                      $$('#nodeInventory > .sInventory > .sInventory').hide();
                      $$(tab).show();
                    });
                    """)&
                    buildJsTree(groupTreeId)

                )
              case e:EmptyBox =>
                val msg = e ?~! s"Could not get global policy mode when getting node '${node.id.value}' details"
                logger.error(msg, e)
                <div class="error">{msg}</div>
            }
          case Full(None) =>
            val msg = "Can not find inventory details for node with ID %s".format(node.id.value)
            logger.error(msg)
            <div class="error">{msg}</div>
          case e:EmptyBox =>
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
  private def bindNode(node : NodeInfo, inventory: FullInventory, withinPopup : Boolean , displayCompliance: Boolean, globalMode : GlobalPolicyMode) : NodeSeq = {
    val id = JsNodeId(node.id)
    ( "#node_groupTree" #>
        <div id={groupTreeId}>
          <ul>{DisplayNodeGroupTree.buildTreeKeepingGroupWithNode(groupLib, node, None, None, Map(("info", _ => Noop)))}</ul>
        </div> &
      "#nodeDetails" #> DisplayNode.showNodeDetails(inventory, Some((node, globalMode)), Some(node.creationDate),  AcceptedInventory, isDisplayingInPopup = withinPopup) &
      "#nodeInventory *" #> DisplayNode.show(inventory, false) &
      "#reportsDetails *" #> reportDisplayer.asyncDisplay(node) &
      "#logsDetails *" #> logDisplayer.asyncDisplay(node.id) &
      "#node_parameters -*" #> (if(node.id == Constants.ROOT_POLICY_SERVER_ID) NodeSeq.Empty else nodeStateEditForm(node).nodeStateConfiguration) &
      "#node_parameters -*" #> agentPolicyModeEditForm.cfagentPolicyModeConfiguration &
      "#node_parameters -*" #> agentScheduleEditForm(node).cfagentScheduleConfiguration &
      "#node_parameters *+" #> complianceModeEditForm(node).complianceModeConfiguration &
      "#extraHeader" #> DisplayNode.showExtraHeader(inventory) &
      "#extraContent" #> DisplayNode.showExtraContent(Some(node), inventory) &
      "#node_tabs [id]" #> s"details_${id}"
    ).apply(serverDetailsTemplate)
  }

  /**
   * Javascript to initialize a tree.
   * htmlId is the id of the div enclosing tree datas
   */
  private def buildJsTree(htmlId:String) : JsExp = JsRaw(s"""buildGroupTree('#${htmlId}', '${S.contextPath}', [], 'on', undefined, false)""")

}
