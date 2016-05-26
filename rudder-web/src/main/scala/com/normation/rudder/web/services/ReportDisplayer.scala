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

import scala.xml.NodeSeq
import scala.xml.NodeSeq.seqToNodeSeq
import com.normation.cfclerk.services.TechniqueRepository
import com.normation.exceptions.TechnicalException
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.domain.reports._
import com.normation.rudder.repository.RoDirectiveRepository
import com.normation.rudder.repository.RoRuleRepository
import com.normation.rudder.services.reports.ReportingService
import bootstrap.liftweb.RudderConfig
import net.liftweb.common._
import net.liftweb.http.S
import net.liftweb.http.SHtml
import net.liftweb.http.Templates
import net.liftweb.http.js.JE._
import net.liftweb.http.js.JsCmd
import net.liftweb.http.js.JsCmds._
import net.liftweb.util.Helpers._
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies.Rule
import com.normation.rudder.repository.FullActiveTechniqueCategory
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.nodes.Node
import com.normation.rudder.web.model.JsNodeId
import com.normation.rudder.services.reports._
import com.normation.rudder.repository.NodeConfigIdInfo
import org.joda.time.format.DateTimeFormat

/**
 * Display the last reports of a server
 * Based on template : templates-hidden/reports_server
 */
class ReportDisplayer(
    ruleRepository      : RoRuleRepository
  , directiveRepository : RoDirectiveRepository
  , reportingService    : ReportingService
  , techniqueRepository : TechniqueRepository
) extends Loggable {

  private[this] val getAllNodeInfos = RudderConfig.nodeInfoService.getAll _

  private[this] val templateByNodePath = List("templates-hidden", "reports_server")
  private def templateByNode() =  Templates(templateByNodePath) match {
    case Empty | Failure(_,_,_) =>
      throw new TechnicalException("Template for execution batch history not found. I was looking for %s.html".format(templateByNodePath.mkString("/")))
    case Full(n) => n
  }

  def reportByNodeTemplate = chooseTemplate("batches", "list", templateByNode)
  def directiveDetails = chooseTemplate("directive", "foreach", templateByNode)

  /**
   * Main entry point to display the tab with reports of a node.
   * It build up to 3 tables:
   * - general compliance table (displayed by rules)
   * - missing reports table if such reports exists
   * - unknown reports table if such reports exists
   */
  def asyncDisplay(node : NodeInfo) : NodeSeq = {
    val id = JsNodeId(node.id)
    val callback =  SHtml.ajaxInvoke(() => SetHtml("reportsDetails",displayReports(node)) )
    Script(OnLoad(JsRaw(
      s"""
        $$("#details_${id}").bind( "show", function(event, ui) {
          if(ui.panel.id== 'node_reports') { ${callback.toJsCmd} }
        });
       """
    )))
  }

  /**
   * Refresh the main compliance table
   */
  def refreshReportDetail(node : NodeInfo) = {
    def refreshData : Box[JsCmd] = {
      for {
        report  <- reportingService.findNodeStatusReport(node.id)
        data    <- getComplianceData(node.id, report)
      } yield {
        import net.liftweb.util.Helpers.encJs
        val intro = encJs(displayIntro(report).toString)
        JsRaw(s"""refreshTable("reportsGrid",${data.json.toJsCmd}); $$("#node-compliance-intro").replaceWith(${intro})""")
      }
    }

    val ajaxCall = {
      SHtml.ajaxCall(
          JsNull
        , (s) => refreshData.getOrElse(Noop)
      )
    }

    AnonFunc(ajaxCall)
  }


  private[this] def displayIntro(report: NodeStatusReport): NodeSeq = {
    def explainCompliance(info: RunAndConfigInfo): NodeSeq = {
      val dateFormat = DateTimeFormat.forPattern("YYYY-MM-dd HH:mm:ss")

      def currentConfigId(expectedConfigId: NodeConfigIdInfo) = {
        s"Current configuration ID for the node is '${expectedConfigId.configId.value}' (generated on ${expectedConfigId.creation.toString(dateFormat)})"
      }

      info match {
        case ComputeCompliance(lastRunDateTime, lastRunConfigId, expectedConfigId, expirationDateTime, missingReportStatus) =>
          (
            <p>{currentConfigId(expectedConfigId)}.</p>
            <p>Last complete agent run has correct configuration ID, started on node at {lastRunDateTime.toString(dateFormat)}.</p>
          )

        case Pending(expectedConfigId, optLastRun, expirationDateTime, missingReportStatus) =>
          val runInfo = optLastRun match {
            case None => "No recent runs received for this node"
            case Some((date, id)) =>
              s"Last run for node started at ${date.toString(dateFormat)} for configuration ID ${id.configId.value} " +
              (id.endOfLife match {
                case None => ""
                case Some(exp) => s"which expired at ${exp.toString(dateFormat)}"
              })
          }
          (
            <p>{currentConfigId(expectedConfigId)}.</p>
            <p>The node is expected to send a report for that configuration ID before {expirationDateTime.toString(dateFormat)} but no reports
               have been received yet. If that state persists, check that the agent is running.</p>
            <p>{runInfo}</p>
          )

        case NoReportInInterval(expectedConfigId) =>
          (
            <p>{currentConfigId(expectedConfigId)}.</p>
            <p>No recent runs were received for node, and the grace period to receive them expired.</p>
          )

        case NoRunNoInit =>
          <p>No configuration ID and no run received for that node. It may have been accepted after the last policy regeneration.</p>

        case VersionNotFound(lastRunDateTime, lastRunConfigId) =>

          <p>No configuration ID found for that node, but a run was received at {lastRunDateTime.toString(dateFormat)}. There is probably a
             problem with that node, perhaps was it deleted and reaccepted, and it is not yet up to date</p>

        case UnexpectedVersion(lastRunDateTime, lastRunConfigId, lastRunExpiration, expectedConfigId, expectedExpiration) =>
          (
            <p>{currentConfigId(expectedConfigId)} but we received a run, started at {lastRunDateTime.toString(dateFormat)}, with a different configuration ID ({lastRunConfigId.configId.value}).</p>
            <p>The time since generation of the new configuration is longer than the grace period, so all received reports will be mark as unexpected.
               Please check that the node is able to get its new policies by running 'rudder agent update' on the node.</p>
          )

        case UnexpectedNoVersion(lastRunDateTime, lastRunConfigId, lastRunExpiration, expectedConfigId, expectedExpiration) =>
          (
            <p>{currentConfigId(expectedConfigId)} but we received a run, started at {lastRunDateTime.toString(dateFormat)}, without any configuration ID.</p>
            <p>The time since generation of the new configuration is longer than the grace period, so all received reports will be marked as unexpected.
               Please check that the node is able to get its new policies.</p>
          )

      }

    }
    import com.normation.rudder.domain.logger.ComplianceDebugLogger.RunAndConfigInfoToLog
    //what we print before all the tables
    val nbAttention = report.compliance.noAnswer + report.compliance.error + report.compliance.missing + report.compliance.unexpected

    //depending on the kind of report, choose the background.

    val background = report.runInfo match {
      case _: Pending => "bg-info text-info"
      case _: ComputeCompliance =>
        if(nbAttention > 0) {
          "bg-warning text-warning"
        } else {
          "bg-success text-success"
        }
      case _          =>
          "bg-danger text-danger"
    }


    <div class="tw-bs">
      <div id="node-compliance-intro" class={background}>
        <p>{explainCompliance(report.runInfo)}</p>{
        if(report.compliance.total <= 0) {
          NodeSeq.Empty
        } else {
          <p>{
            if(nbAttention > 0) {
              s"There are ${nbAttention} out of ${report.compliance.total} reports that require attention"
            } else if(report.compliance.pc_pending > 0) {
              "Policy update in progress"
            } else {
              "All the last execution reports for this server are ok"
            }
          }</p>
        }
      }</div>
    </div>
  }


  private[this] def displayReports(node : NodeInfo) : NodeSeq = {
    val boxXml = (
      for {
        report       <- reportingService.findNodeStatusReport(node.id)
        directiveLib <- directiveRepository.getFullDirectiveLibrary
      } yield {


        val missing    = getComponents(MissingReportType   , report, directiveLib).toSet
        val unexpected = getComponents(UnexpectedReportType, report, directiveLib).toSet

        bind("lastReportGrid", reportByNodeTemplate
          , "intro"      -> displayIntro(report)
          , "grid"       -> showReportDetail(report, node)
          , "missing"    -> showMissingReports(missing)
          , "unexpected" -> showUnexpectedReports(unexpected)
        )
      }
    )

    boxXml match {
      case e:EmptyBox =>

        logger.error(e)
        <div class="error">Could not fetch reports information</div>
      case Full(xml) => xml
    }
  }

  private[this] def showReportDetail(reports: NodeStatusReport, node: NodeInfo): NodeSeq = {
    val data = getComplianceData(node.id, reports).map(_.json).getOrElse(JsArray())

    <table id="reportsGrid" class="fixedlayout tablewidth" cellspacing="0"></table> ++
    Script(JsRaw(s"""
      createRuleComplianceTable("reportsGrid",${data.toJsCmd},"${S.contextPath}", ${refreshReportDetail(node).toJsCmd});
      createTooltip();
    """))
  }

  private[this] def getComplianceData(nodeId: NodeId, reportStatus: NodeStatusReport) = {
    for {
      directiveLib <- directiveRepository.getFullDirectiveLibrary
      allNodeInfos <- getAllNodeInfos()
      rules        <- ruleRepository.getAll(true)
    } yield {
      ComplianceData.getNodeByRuleComplianceDetails(nodeId, reportStatus, allNodeInfos, directiveLib, rules)
    }
  }

  def showMissingReports(reports:Set[((String,String,List[String]),String,String)]) : NodeSeq = {
    def showMissingReport(report:((String,String, List[String]),String,String)) : NodeSeq = {
      val techniqueName =report._2
      val techniqueVersion = report._3
      val reportValue = report._1

      ( "#technique *" #>  "%s (%s)".format(techniqueName,techniqueVersion)
      & "#component *" #>  reportValue._1
      & "#value *"     #>  reportValue._2
      ) (
        <tr>
          <td id="technique"></td>
          <td id="component"></td>
          <td id="value"></td>
        </tr>
      )
    }

    /*
     * NOTE : a missing report is an unknown report with no message
     */
    val missingComponents = reports.filter(r => r._1._3.isEmpty)
    if (missingComponents.size >0){
      ( "#reportLine" #> missingComponents.flatMap(showMissingReport(_) )).apply(
      <h3>Missing reports</h3>
      <div>The following reports are what Rudder expected to receive, but did not. This usually indicates a bug in the Technique being used.</div>
      <table id="missingGrid"  cellspacing="0" style="clear:both">
        <thead>
          <tr class="head">
            <th>Technique<span/></th>
            <th>Component<span/></th>
            <th>Value<span/></th>
          </tr>
        </thead>
        <tbody>
          <div id="reportLine"/>
        </tbody>
      </table>
      <br/>
      ) ++
      buildTable(
          "missing"
         ,"missingGrid"
         , """{ "sWidth": "150px" },
              { "sWidth": "150px" },
              { "sWidth": "150px" }"""
      )
    } else {
      NodeSeq.Empty
    }
  }

  def showUnexpectedReports(reports:Set[((String,String,List[String]),String,String)]) : NodeSeq = {
    def showUnexpectedReport(report:((String,String,List[String]),String,String)) : NodeSeq = {
      val techniqueName =report._2
      val techniqueVersion = report._3
      val reportValue = report._1

      ( "#technique *"  #>  "%s (%s)".format(techniqueName,techniqueVersion)
      &  "#component *" #>  reportValue._1
      & "#value *"      #>  reportValue._2
      & "#message *"    #>  <ul>{reportValue._3.map(msg => <li>{msg}</li>)}</ul>
      ) (
        <tr><td id="technique"></td><td id="component"></td><td id="value"></td><td id="message"></td></tr>
      )
    }

   /*
    * Note: unexpected reports are only the one with messages
    */
    val missingComponents = reports.filter(r => r._1._3.nonEmpty)
    if (missingComponents.size >0){
      ( "#reportLine" #> missingComponents.flatMap(showUnexpectedReport(_) )).apply(
      <h3>Unexpected reports</h3>
      <div>The following reports were received by Rudder, but did not match the reports declared by the Technique. This usually indicates a bug in the Technique being used.</div>

      <table id="unexpectedGrid"  cellspacing="0" style="clear:both">
        <thead>
          <tr class="head">
            <th>Technique<span/></th>
            <th>Component<span/></th>
            <th>Value<span/></th>
            <th>Message<span/></th>
          </tr>
        </thead>
        <tbody>
          <div id="reportLine"/>
        </tbody>
      </table>
      <br/> ) ++
      buildTable("unexpected"
        ,"unexpectedGrid"
        , """{ "sWidth": "100px" },
             { "sWidth": "100px" },
             { "sWidth": "100px" },
             { "sWidth": "200px" }"""
      )
    } else {
     NodeSeq.Empty
    }
  }

  private[this] def getComponents(status: ReportType, nodeStatusReports: NodeStatusReport, directiveLib: FullActiveTechniqueCategory) = {
   /*
    * Note:
    * - missing reports are unexpected without error message
    * - unexpected reports are only the one with messages
    *
    * To get unexpected reports we have to find them in each node report
    * So we have to go the value level, get the messages
    * and also get technique details at directive level for each report
    * we could add more information at each level (directive name? rule name?)
    */
    for {
      (_, directive) <- nodeStatusReports.report.directives
      value          <- directive.getValues(v => v.status == status)
    } yield {
      val (techName, techVersion) = directiveLib.allDirectives.get(value._1).map { case(tech,dir) =>
        (tech.techniqueName.value, dir.techniqueVersion.toString)
      }.getOrElse(("Unknown technique", "N/A"))

      ((value._2, value._3.componentValue, value._3.messages.flatMap(_.message)), techName, techVersion)
    }
  }

  private[this] def buildTable(name1: String, name2: String, colums: String): NodeSeq = {
    Script( JsRaw(s"""
     var oTable${name1} = $$('#${name2}').dataTable({
       "asStripeClasses": [ 'color1', 'color2' ],
       "bAutoWidth": false,
       "bFilter" : true,
       "bPaginate" : true,
       "bLengthChange": true,
       "bStateSave": true,
                    "fnStateSave": function (oSettings, oData) {
                      localStorage.setItem( 'DataTables_${name2}', JSON.stringify(oData) );
                    },
                    "fnStateLoad": function (oSettings) {
                      return JSON.parse( localStorage.getItem('DataTables_${name2}') );
                    },
       "sPaginationType": "full_numbers",
       "bJQueryUI": true,
       "oLanguage": {
         "sSearch": ""
       },
       "sDom": '<"dataTables_wrapper_top"fl>rt<"dataTables_wrapper_bottom"ip>',
       "aaSorting": [[ 0, "asc" ]],
       "aoColumns": [
         ${colums}
       ]
     } );
    """) )
  }

}
