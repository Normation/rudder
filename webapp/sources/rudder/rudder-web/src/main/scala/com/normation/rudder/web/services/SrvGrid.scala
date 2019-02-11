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

package com.normation.rudder.web.services

import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.utils.Utils.isEmpty
import org.slf4j.LoggerFactory
import scala.xml._
import net.liftweb.common._
import net.liftweb.http._
import net.liftweb.http.js._
import JsCmds._
import JE._
import net.liftweb.http.SHtml._
import com.normation.rudder.web.components.DateFormaterService
import com.normation.rudder.reports.execution.RoReportsExecutionRepository
import com.normation.rudder.domain.logger.TimingDebugLogger
import com.normation.inventory.domain.VirtualMachineType
import com.normation.inventory.domain.PhysicalMachineType
import com.normation.rudder.domain.policies.GlobalPolicyMode
import com.normation.rudder.domain.policies.PolicyModeOverrides._
import com.normation.appconfig.ReadConfigService
import com.normation.rudder.reports.execution.AgentRunWithNodeConfig

import com.normation.box._

/**
 * Very much like the NodeGrid, but with the new WB and without ldap information
 *
 * @author Nicolas CHARLES
 *
 */
object SrvGrid {
  val logger = LoggerFactory.getLogger(classOf[SrvGrid])
}

/**
 * Present a grid of server in a jQuery Datatable
 * widget.
 *
 * To use it:
 * - add the need js/css dependencies by adding the result
 *   of head() in the calling template head
 * - call the display(servers) method
 */
class SrvGrid(
    roAgentRunsRepository : RoReportsExecutionRepository
  , asyncComplianceService: AsyncComplianceService
  , configService         : ReadConfigService
) extends Loggable {

  def jsVarNameForId(tableId:String) = "oTable" + tableId

  /**
   * Display and init the display for the list of server
   * @param tableId : the id of the table
   * @param callback : Optionnal callback to use on node, if missing, replaced by a link to that node
   */
  def displayAndInit(
      nodes    : Seq[NodeInfo]
    , tableId  : String
    , callback : Option[(String, Boolean) => JsCmd] = None
    , refreshNodes : Option[ () => Seq[NodeInfo]] = None
   ) : NodeSeq = {
    val script = {
      configService.rudder_global_policy_mode().toBox match {
        case Full(globalPolicyMode) => Script(OnLoad(initJs(tableId,nodes,globalPolicyMode,callback,refreshNodes)))
        case eb : EmptyBox =>
          val fail = eb ?~! "Could not find global policy Mode"
          logger.error(fail.messageChain)
          NodeSeq.Empty
      }
    }
    tableXml(tableId) ++ script
  }

  /**
   * Initialize the table by javascript
   * takes the id of the table as param
   * the nodes to compute the datas
   * and the optionnal callback
   */
  def initJs(
      tableId  : String
    , nodes    : Seq[NodeInfo]
    , globalPolicyMode : GlobalPolicyMode
    , callback : Option[(String, Boolean) => JsCmd]
    , refreshNodes : Option[ () => Seq[NodeInfo]]
  ) : JsCmd = {

    val data = getTableData(nodes,callback)

    val globalOverride = globalPolicyMode.overridable match {
      case Always => true
      case Unoverridable => false
    }
    val objGlobalPolicyMode = JsObj(("override"->globalOverride), ("policyMode"->globalPolicyMode.mode.name))
    val refresh = refreshNodes.map(refreshData(_,callback,tableId).toJsCmd).getOrElse("undefined")

    JsRaw(s"""createNodeTable("${tableId}",${data.json.toJsCmd},"${S.contextPath}",${refresh}, ${objGlobalPolicyMode});""")
  }

  def getTableData (
      nodes    : Seq[NodeInfo]
    , callback : Option[(String,Boolean) => JsCmd]
  ) = {

    val now = System.currentTimeMillis
    val runs = roAgentRunsRepository.getNodesLastRun(nodes.map(_.id).toSet)

    if(TimingDebugLogger.isDebugEnabled) {
      TimingDebugLogger.debug(s"Get all last run date time: ${System.currentTimeMillis - now} ms")
    }

    val lines = (for {
      lastReports <- runs
      globalMode  <- configService.rudder_global_policy_mode().toBox
    } yield {
      nodes.map(node => NodeLine(node, lastReports.get(node.id), callback, globalMode))
    }) match {
      case eb: EmptyBox =>
        val msg = "Error when trying to get nodes info"
        val e = eb ?~! msg
        logger.error(e.messageChain)
        e.rootExceptionCause.foreach(ex => logger.error(ex) )
        Nil
      case Full(lines) => lines.toList
    }

    JsTableData(lines)
  }

  def refreshData (
      refreshNodes : () => Seq[NodeInfo]
    , callback : Option[(String, Boolean) => JsCmd]
    , tableId: String
  ) = {
    val ajaxCall = SHtml.ajaxCall(JsNull, (s) => {
      val nodes = refreshNodes()
      val futureCompliances = asyncComplianceService.complianceByNode(nodes.map(_.id).toSet, Set(), tableId)

      val data = getTableData(nodes,callback)
      JsRaw(s"""
          nodeCompliances = {};
          refreshTable("${tableId}",${data.json.toJsCmd});
          ${futureCompliances.toJsCmd}
      """)
    } )

    AnonFunc("",ajaxCall)
  }

  /**
   * Html templace of the table, id is in parameter
   */
  def tableXml(tableId:String) : NodeSeq = {
    <table id={tableId} cellspacing="0"/>
  }

}

/*
 *   Javascript object containing all data to create a line in the DataTable
 *   { "name" : Node hostname [String]
 *   , "id" : Node id [String]
 *   , "machineType" : Node machine type [String]
 *   , "osName" : Node OS name [String]
 *   , "osVersion" : Node OS version [ String ]
 *   , "servicePack" : Node OS service pack [ String ]
 *   , "lastReport" : Last report received about that node [ String ]
 *   , "callBack" : Callback on Node, if absend replaced by a link to nodeId [ Function ]
 *   }
 */
case class NodeLine (
    node       : NodeInfo
  , lastReport : Box[Option[AgentRunWithNodeConfig]]
  , callback   : Option[(String, Boolean) => JsCmd]
  , globalMode : GlobalPolicyMode
) extends JsTableLine {

  val (policyMode,explanation) =
      (globalMode.overridable,node.policyMode) match {
        case (Always,Some(mode)) =>
          (mode,"<p>This mode is an override applied to this node. You can change it in the <i><b>node's settings</b></i>.</p>")
        case (Always,None) =>
          val expl = """<p>This mode is the globally defined default. You can change it in <i><b>settings</b></i>.</p><p>You can also override it on this node in the <i><b>node's settings</b></i>.</p>"""
          (globalMode.mode, expl)
        case (Unoverridable,_) =>
          (globalMode.mode, "<p>This mode is the globally defined default. You can change it in <i><b>Settings</b></i>.</p>")
      }

  val optCallback = {
    callback.map(cb => ("callback", AnonFunc("displayCompliance", ajaxCall(JsVar("displayCompliance"), s =>
      {
       val displayCompliance = s.toBoolean
      cb(node.id.value,displayCompliance)}))))
  }
  val hostname = {
    if (isEmpty(node.hostname)) {
      s"(Missing name)  ${node.id.value}"
    } else {
      node.hostname
    }
  }

  val lastReportValue = {
    lastReport match {
      case Full(exec) =>
        exec.map(report =>  DateFormaterService.getFormatedDate(report.agentRunId.date)).getOrElse("Never")
      case eb : EmptyBox =>
        "Error While fetching node executions"
    }
  }

  val baseFields = {
   JsObj(
       ( "name" -> hostname )
     , ( "state" -> node.state.name )
     , ( "id" -> node.id.value )
     , ( "machineType" -> (node.machine.map { _.machineType match {
                            case _: VirtualMachineType => "Virtual"
                            case PhysicalMachineType   => "Physical"
                          } }.getOrElse("No Machine Inventory" ):String )
       )
     , ( "osName") -> S.?(s"os.name.${node.osDetails.os.name}")
     , ( "osVersion" -> node.osDetails.version.value)
     , ( "servicePack" -> (node.osDetails.servicePack.getOrElse("N/A"): String))
     , ( "agentPolicyMode" -> policyMode.toString)
     , ( "explanation" -> explanation.toString)
     , ( "lastReport" ->  lastReportValue )
     )
  }

  val json = baseFields +* JsObj(optCallback.toSeq:_*)
}
