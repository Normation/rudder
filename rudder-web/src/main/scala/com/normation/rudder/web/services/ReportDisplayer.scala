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
import com.normation.rudder.domain.policies.GlobalPolicyMode
import com.normation.rudder.appconfig.ConfigRepository
import com.normation.rudder.appconfig.ReadConfigService

/**
 * Display the last reports of a server
 * Based on template : templates-hidden/reports_server
 */
class ReportDisplayer(
    ruleRepository      : RoRuleRepository
  , directiveRepository : RoDirectiveRepository
  , reportingService    : ReportingService
  , techniqueRepository : TechniqueRepository
  , configService       : ReadConfigService
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
    Script(OnLoad(JsRaw(s"""${callback.toJsCmd}""")))
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

      def currentConfigId(expectedConfigInfo: NodeConfigIdInfo) = {
        s"Current configuration ID for this node is '${expectedConfigInfo.configId.value}' (generated on ${expectedConfigInfo.creation.toString(dateFormat)})"
      }

      info match {
        case ComputeCompliance(lastRunDateTime, expectedConfigInfo, expirationDateTime, missingReportStatus) =>
          (
            <p>This node has up to date policy and the agent is running. Reports below are from the latest run, which started on the node at {lastRunDateTime.toString(dateFormat)}.</p>
            <p>{currentConfigId(expectedConfigInfo)}.</p>
          )

        case Pending(expectedConfigInfo, optLastRun, expirationDateTime, missingReportStatus) =>
          val runInfo = optLastRun match {
            case None => "No recent reports have been received for this node. Check that the agent is running (run 'rudder agent check' on the node)."
            case Some((date, id)) =>
              (id.endOfLife match {
                case None =>
                  s"This is expected, the node is reporting on the previous configuration policy and should report on the new one at latest" +
                  " ${expirationDateTime.toString(dateFormat)}. Previous known states are displayed below."
                case Some(exp) =>
                  s"This is unexpected, since the node is reporting on a configuration policy that expired at ${exp.toString(dateFormat)}."
              }) +
              s" The latest reports received for this node are from a run started at ${date.toString(dateFormat)} with configuration ID ${id.configId.value}."
          }
          (
            <p>This node has recently been assigned a new policy but no reports have been received for the new policy yet.</p>
            <p>{runInfo}</p>
            <p>{currentConfigId(expectedConfigInfo)}.</p>
          )

        case NoReportInInterval(expectedConfigInfo) =>
          (
            <p>No recent reports have been received for this node in the grace period since the last configuration policy change.
               This is unexpected. Please check the status of the agent by running 'rudder agent health' on the node.</p>
            <p>For information, expected node policies are displayed below.</p>
            <p>{currentConfigId(expectedConfigInfo)}.</p>
          )

        case NoRunNoExpectedReport =>
          <p>This is a new node that does not yet have a configured policy. If a policy generation is in progress, this will apply to this node when it is done.</p>

        case NoExpectedReport(lastRunDateTime, lastRunConfigId) =>
          val configIdmsg = lastRunConfigId match {
            case None     =>  "without a configuration ID, although one is required"
            case Some(id) => s"with configuration ID '${id.value}' that is unknown to Rudder"
          }

          <p>This node has no configuration policy assigned to it, but reports have been received for it {configIdmsg} (run started at {lastRunDateTime.toString(dateFormat)}).
             Either this node was deleted from Rudder but still has a running agent or the node is sending a corrupted configuration ID.
             Please run "rudder agent update -f" on the node to force a policy update and, if the problem persists,
             force a policy regeneration with the "Clear caches" button in Administration > Settings.</p>

        case UnexpectedVersion(lastRunDateTime, Some(lastRunConfigInfo), lastRunExpiration, expectedConfigInfo, expectedExpiration) =>
          (
            <p>This node is sending reports from an out-of-date configuration policy ({expectedConfigInfo.configId.value}, run started at {lastRunDateTime.toString(dateFormat)}).
               Please check that the node is able to update it's policy by running 'rudder agent update' on the node.</p>
            <p>For information, expected node policies are displayed below.</p>
            <p>{currentConfigId(expectedConfigInfo)}</p>
          )

        case UnexpectedNoVersion(lastRunDateTime, Some(lastRunConfigInfo), lastRunExpiration, expectedConfigInfo, expectedExpiration) =>
          (
            <p>This node is sending reports without a configuration ID (run started on the node at {lastRunDateTime.toString(dateFormat)}), although one is required.</p>
            <p>Please run "rudder agent update -f" on the node to force a policy update.</p>
            <p>For information, expected node policies are displayed below.</p>
            <p>{currentConfigId(expectedConfigInfo)}</p>
          )

        case ReportsDisabledInInterval(expectedConfigId) =>
          (
            <p>{currentConfigId(expectedConfigId)} and reports are disabled for that node.</p>
          )

        case UnexpectedUnknowVersion(lastRunDateTime, lastRunConfigId, expectedConfigInfo, expectedExpiration) =>
          (
            <p>This node is sending reports from an unknown configuration policy (with configuration ID '${lastRunConfigId.value}'
               that is unknown to Rudder, run started at {lastRunDateTime.toString(dateFormat)}).
               Please run "rudder agent update -f" on the node to force a policy update.</p>
            <p>For information, expected node policies are displayed below.</p>
            <p>{currentConfigId(expectedConfigInfo)}</p>
          )
      }

    }

    /*
     * Number of reports requiring attention. We only display that message if we are in a case where
     * compliance is actually meaningful.
     */
    val (background, lookReportsMessage) = report.runInfo match {
      case NoRunNoExpectedReport | _:NoExpectedReport |
           _:UnexpectedVersion | _:UnexpectedNoVersion |
           _:UnexpectedUnknowVersion | _:NoReportInInterval =>

        ("bg-danger text-danger", NodeSeq.Empty)

      case _: ReportsDisabledInInterval =>
        ("progress-bar-reportsdisabled", NodeSeq.Empty)

      case  _:Pending =>
        ("bg-info text-info", NodeSeq.Empty)

      case _:ComputeCompliance =>

        if(report.compliance.total <= 0) { //should not happen
          ("bg-success text-success" , NodeSeq.Empty)
        } else {
          val nbAttention = report.compliance.noAnswer + report.compliance.error + report.compliance.missing + report.compliance.unexpected
          if(nbAttention > 0) {
            ( "bg-warning text-warning"
            , <p>{nbAttention} reports below (out of {report.compliance.total} total reports) are not in Success, and may require attention."</p>
            )
          } else if(report.compliance.pc_pending > 0) {
            ("bg-info text-info", NodeSeq.Empty)

          } else {
            ( "bg-success text-success"
            , <p>All reports received for this node are in Success.</p>
            )
          }
        }
    }

    <div class="tw-bs">
      <div id="node-compliance-intro" class={background}>
        <p>{explainCompliance(report.runInfo)}</p>{
          lookReportsMessage
      }</div>
    </div>
  }

  private[this] def displayReports(node : NodeInfo) : NodeSeq = {
    val boxXml = (
      for {
        report       <- reportingService.findNodeStatusReport(node.id)
        directiveLib <- directiveRepository.getFullDirectiveLibrary
      } yield {

        val intro = displayIntro(report)

        /*
         * Now, on some case, we don't want to display 50% missing / 50% unexpected
         * because the node config id is not correct (or related cases), we want to display
         * what is expected config, but without the compliance part.
         *
         * And if don't even have expected configuration, don't display anything.
         */

        report.runInfo match {
          case NoRunNoExpectedReport | _:NoExpectedReport =>
            bind("lastreportgrid", reportByNodeTemplate
              , "intro"      -> intro
              , "grid"       -> NodeSeq.Empty
              , "missing"    -> NodeSeq.Empty
              , "unexpected" -> NodeSeq.Empty
            )

          case _:UnexpectedVersion | _:UnexpectedNoVersion |
               _:UnexpectedUnknowVersion | _:NoReportInInterval |
               _:ReportsDisabledInInterval =>

            /*
             * In these case, filter out "unexpected" reports to only
             * keep missing ones, and do not show the "compliance" row.
             */
            val filtered = NodeStatusReport(report.forNode, report.runInfo, report.report.reports.flatMap { x =>
              x.withFilteredElements(
                  _ => true   //keep all (non empty) directives
                , _ => true   //keep all (non empty) component values
                , { value =>  //filter values based on the message type - we don't want Unexpected values
                    value.messages.forall { m => m.reportType != ReportType.Unexpected }
                  }
              )
            } )

            bind("lastreportgrid", reportByNodeTemplate
              , "intro"      -> intro
              , "grid"       -> showReportDetail(filtered, node, withCompliance = false)
              , "missing"    -> NodeSeq.Empty
              , "unexpected" -> NodeSeq.Empty
            )

          case  _:Pending | _:ComputeCompliance =>

            val missing    = getComponents(ReportType.Missing   , report, directiveLib).toSet
            val unexpected = getComponents(ReportType.Unexpected, report, directiveLib).toSet

            bind("lastreportgrid", reportByNodeTemplate
              , "intro"      -> intro
              , "grid"       -> showReportDetail(report, node, withCompliance = true)
              , "missing"    -> showMissingReports(missing)
              , "unexpected" -> showUnexpectedReports(unexpected)
            )
        }
      }
    )

    boxXml match {
      case e:EmptyBox =>
        logger.error(e)
        <div class="error">Could not fetch reports information</div>
      case Full(xml) => xml
    }
  }

  private[this] def showReportDetail(reports: NodeStatusReport, node: NodeInfo, withCompliance: Boolean): NodeSeq = {
    val data = getComplianceData(node.id, reports).map(_.json).getOrElse(JsArray())
    val configService = RudderConfig.configService

    val jsFunctionName = if(withCompliance) {
      "createRuleComplianceTable"
    } else {
      "createExpectedReportTable"
    }

    <table id="reportsGrid" class="tablewidth" cellspacing="0"></table> ++
    Script(JsRaw(s"""
      ${jsFunctionName}("reportsGrid",${data.toJsCmd},"${S.contextPath}", ${refreshReportDetail(node).toJsCmd});
      createTooltip();
    """))
  }

  private[this] def getComplianceData(nodeId: NodeId, reportStatus: NodeStatusReport) = {
    for {
      directiveLib <- directiveRepository.getFullDirectiveLibrary
      allNodeInfos <- getAllNodeInfos()
      rules        <- ruleRepository.getAll(true)
      globalMode   <- configService.rudder_global_policy_mode()
    } yield {
      ComplianceData.getNodeByRuleComplianceDetails(nodeId, reportStatus, allNodeInfos, directiveLib, rules, globalMode)
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
      & "#component *"  #>  reportValue._1
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
