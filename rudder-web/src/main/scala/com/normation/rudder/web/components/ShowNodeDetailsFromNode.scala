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

import scala.xml.NodeSeq
import com.normation.exceptions.TechnicalException
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
import net.liftweb.http.Templates
import net.liftweb.http.js.JE.JsRaw
import net.liftweb.http.js.JsCmds._
import net.liftweb.http.js.JsExp
import net.liftweb.util.Helpers._
import com.normation.rudder.domain.nodes.Node
import com.normation.plugins.ExtendableSnippet
import com.normation.plugins.SnippetExtensionKey
import com.normation.plugins.SpringExtendableSnippet
import com.normation.rudder.reports.HeartbeatConfiguration
import com.normation.rudder.web.model.JsNodeId

object ShowNodeDetailsFromNode {

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
    val nodeId : NodeId
  , groupLib   : FullNodeGroupCategory
) extends DispatchSnippet with SpringExtendableSnippet[ShowNodeDetailsFromNode] with Loggable {
  import ShowNodeDetailsFromNode._

  private[this] val nodeInfoService      = RudderConfig.nodeInfoService
  private[this] val serverAndMachineRepo = RudderConfig.fullInventoryRepository
  private[this] val reportDisplayer      = RudderConfig.reportDisplayer
  private[this] val logDisplayer         = RudderConfig.logDisplayer
  private[this] val uuidGen              = RudderConfig.stringUuidGenerator
  private[this] val nodeRepo             = RudderConfig.woNodeRepository
  private[this] val asyncDeploymentAgent = RudderConfig.asyncDeploymentAgent
  private[this] val configService = RudderConfig.configService

  def extendsAt = SnippetExtensionKey(classOf[ShowNodeDetailsFromNode].getSimpleName)

  //val nodeInfo = nodeInfoService.getNodeInfo(nodeId)

   def complianceModeEditForm = new ComplianceModeEditForm(
        () => getHeartBeat
      , saveHeart
      , () => Unit
      , () => Some(configService.rudder_compliance_heartbeatPeriod)
    )

  val heartbeatConfiguration = HeartbeatConfiguration(false, 1)
  def getHeartBeat : Box[(String,Int, Boolean)] = {
    for {
      complianceMode <- configService.rudder_compliance_mode_name
      nodeInfo <- nodeInfoService.getNodeInfo(nodeId)
    } yield {
      val hbConf = nodeInfo.nodeReportingConfiguration.heartbeatConfiguration.getOrElse(heartbeatConfiguration)
      (complianceMode,hbConf.heartbeatPeriod,hbConf.overrides)
    }
  }


  def saveHeart(complianceMode : String, frequency: Int, overrides : Boolean) : Box[Unit] = {
    val heartbeatConfiguration = HeartbeatConfiguration(overrides, frequency)
    val modId = ModificationId(uuidGen.newUuid)
    for {
      result <- nodeRepo.updateNodeHeartbeat(nodeId, heartbeatConfiguration, modId, CurrentUser.getActor, None)
    } yield {
      asyncDeploymentAgent ! AutomaticStartDeployment(modId, CurrentUser.getActor)
    }
  }



   def agentScheduleEditForm = new AgentScheduleEditForm(
        () => getSchedule
      , saveSchedule
      , () => Unit
      , () => Some(getGlobalSchedule)
    )

  def getGlobalSchedule() : Box[AgentRunInterval] = {
    for {
      starthour <- configService.agent_run_start_hour
      startmin  <- configService.agent_run_start_minute
      splaytime <- configService.agent_run_splaytime
      interval  = configService.agent_run_interval
    } yield {
      AgentRunInterval(
            None
          , interval
          , startmin
          , starthour
          , splaytime
        )
    }
  }

  val emptyInterval = AgentRunInterval(Some(false), 5, 0, 0, 0)
  def getSchedule : Box[AgentRunInterval] = {
    for {
      nodeInfo <- nodeInfoService.getNodeInfo(nodeId)
    } yield {
      nodeInfo.nodeReportingConfiguration.agentRunInterval.getOrElse(emptyInterval)
    }
  }

  def saveSchedule(schedule: AgentRunInterval) : Box[Unit] = {
    val modId =  ModificationId(uuidGen.newUuid)
    val user  =  CurrentUser.getActor
    for {
      result  <- nodeRepo.updateAgentRunPeriod(nodeId, schedule, modId, user, None)
    } yield {
      asyncDeploymentAgent ! AutomaticStartDeployment(modId, CurrentUser.getActor)
    }
  }

  def mainDispatch = Map(
    "displayInPopup"    -> { _:NodeSeq => privateDisplay(true)  }
  , "displayMainWindow" -> { _:NodeSeq => privateDisplay(false) }
  )

  def display(withinPopup : Boolean = false) : NodeSeq = {
    if(withinPopup) dispatch("displayInPopup")(NodeSeq.Empty)
    else dispatch("displayMainWindow")(NodeSeq.Empty)
  }

  private[this] def privateDisplay(withinPopup : Boolean = false) : NodeSeq = {
    nodeInfoService.getNode(nodeId) match {
      case Empty =>
        <div class="error">Node with id {nodeId.value} was not found</div>
      case f@Failure(_,_,_) =>
        logger.debug("Root exception:", f)
        <div class="error">
          <p>Node with id {nodeId.value} was not found</p>
          <p>Error message was: {f.messageChain}</p>
        </div>
      case Full(node) => // currentSelectedNode = Some(server)
        serverAndMachineRepo.get(node.id, AcceptedInventory) match {
          case Full(sm) =>
            bindNode(node, sm, withinPopup) ++ Script(OnLoad(
              DisplayNode.jsInit(node.id, sm.node.softwareIds, "") &
              OnLoad(buildJsTree(htmlId_crTree))
            ))
          case e:EmptyBox =>
            val msg = "Can not find inventory details for node with ID %s".format(node.id.value)
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
  private def bindNode(node : Node, inventory: FullInventory, withinPopup : Boolean = false) : NodeSeq = {
    val id = JsNodeId(node.id)
    ( "#node_name " #> s"${inventory.node.main.hostname} (last updated ${ inventory.node.inventoryDate.map(DateFormaterService.getFormatedDate(_)).getOrElse("Unknown")})" &
      "#groupTree *" #>
        <div id={htmlId_crTree}>
          <ul>{DisplayNodeGroupTree.buildTreeKeepingGroupWithNode(groupLib, node.id)}</ul>
        </div> &
      "#nodeDetails" #> DisplayNode.showNodeDetails(inventory, Some(node.creationDate), AcceptedInventory, isDisplayingInPopup = withinPopup) &
      "#nodeInventory *" #> DisplayNode.show(inventory, false) &
      "#reportsDetails *" #> reportDisplayer.asyncDisplay(node) &
      "#logsDetails *" #> logDisplayer.asyncDisplay(node.id)&
      "#node_parameters -*" #>  agentScheduleEditForm.cfagentScheduleConfiguration &
      "#node_parameters *+" #> complianceModeEditForm.complianceModeConfiguration &
      "#extraHeader" #> DisplayNode.showExtraHeader(inventory) &
      "#extraContent" #> DisplayNode.showExtraContent(Some(node), inventory) &
      "#node_tabs [id]" #> s"details_${id}"
    ).apply(serverDetailsTemplate)
  }


  /**
   * Javascript to initialize a tree.
   * htmlId is the id of the div enclosing tree datas
   */
  private def buildJsTree(htmlId:String) : JsExp = JsRaw(
    """buildGroupTree('#%s', '%s')""".format(htmlId,S.contextPath)
  )

}
