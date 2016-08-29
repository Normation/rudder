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
import com.normation.inventory.domain.NodeId
import com.normation.inventory.ldap.core._
import LDAPConstants._
import bootstrap.liftweb.LiftSpringApplicationContext.inject
import org.slf4j.LoggerFactory
import scala.xml._
import net.liftweb.common._
import net.liftweb.http._
import net.liftweb.util._
import Helpers._
import net.liftweb.http.js._
import JsCmds._
import JE._
import net.liftweb.http.SHtml._
import com.normation.exceptions.TechnicalException
import net.liftweb.http.Templates
import com.normation.rudder.repository.ReportsRepository
import bootstrap.liftweb.RudderConfig
import com.normation.rudder.web.components.DateFormaterService
import com.normation.rudder.reports.execution.RoReportsExecutionRepository
import com.normation.rudder.reports.execution.AgentRun
import com.normation.rudder.domain.logger.TimingDebugLogger
import com.normation.inventory.domain.VirtualMachineType
import com.normation.inventory.domain.PhysicalMachineType
import scala.concurrent.Await
import scala.concurrent.duration.Duration

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
  , asyncComplianceService : AsyncComplianceService
) extends Loggable {

  private def templatePath = List("templates-hidden", "srv_grid")
  private def template() =  Templates(templatePath) match {
    case Empty | Failure(_,_,_) =>
      throw new TechnicalException("Template for server grid not found. I was looking for %s.html".format(templatePath.mkString("/")))
    case Full(n) => n
  }

  private def tableTemplate = chooseTemplate("servergrid","table",template)

  def jsVarNameForId(tableId:String) = "oTable" + tableId

  /**
   * Display and init the display for the list of server
   * @param servers : a sequence of the node to show
   * @param tableId : the id of the table
   * @param callback : Optionnal callback to use on node, if missing, replaced by a link to that node
   */
  def displayAndInit(
      nodes    : Seq[NodeInfo]
    , tableId  : String
    , callback : Option[(String, Boolean) => JsCmd] = None
    , refreshNodes : Option[ () => Seq[NodeInfo]] = None
   ) : NodeSeq = {
    tableXml( tableId) ++ Script(OnLoad(initJs(tableId,nodes,callback,refreshNodes)))
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
    , callback : Option[(String, Boolean) => JsCmd]
    , refreshNodes : Option[ () => Seq[NodeInfo]]
  ) : JsCmd = {

    val data = getTableData(nodes,callback)

    val refresh = refreshNodes.map(refreshData(_,callback,tableId).toJsCmd).getOrElse("undefined")

    JsRaw(s"""createNodeTable("${tableId}",${data.json.toJsCmd},"${S.contextPath}",${refresh});""")

  }

  def getTableData (
      nodes    : Seq[NodeInfo]
    , callback : Option[(String,Boolean) => JsCmd]
  ) = {

    val now = System.currentTimeMillis
    val runs = Await.result(roAgentRunsRepository.getNodesLastRun(nodes.map(_.id).toSet), Duration.Inf)

    if(TimingDebugLogger.isDebugEnabled) {
      TimingDebugLogger.debug(s"Get all last run date time: ${System.currentTimeMillis - now} ms")
    }

    val lines = (for {
      lastReports <- runs
    } yield {
      nodes.map(node => NodeLine(node,lastReports.get(node.id), callback))
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
  , lastReport : Box[Option[AgentRun]]
  , callback   : Option[(String, Boolean) => JsCmd]
) extends JsTableLine {

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
     , ( "id" -> node.id.value )
     , ( "machineType" -> node.machine.map { _.machineType match {
                            case _: VirtualMachineType => "Virtual"
                            case PhysicalMachineType   => "Physical"
                          } }.getOrElse("No Machine Inventory" )
       )
     , ( "osName") -> S.?(s"os.name.${node.osDetails.os.name}")
     , ( "osVersion" -> node.osDetails.version.value)
     , ( "servicePack" -> node.osDetails.servicePack.getOrElse("N/A"))
     , ( "lastReport" ->  lastReportValue )
     )
  }

  val json = baseFields +* JsObj(optCallback.toSeq:_*)
}
