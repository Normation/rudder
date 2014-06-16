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

package com.normation.rudder.web.services

import com.normation.rudder.services.reports.ReportingService
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.repository.RoRuleRepository
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.RuleVal
import com.normation.rudder.services.servers.NodeSummaryService
import com.normation.rudder.web.components.DateFormaterService
import com.normation.rudder.web.model._
import com.normation.rudder.domain.reports.bean._
import com.normation.rudder.domain.reports.bean.{Reports => TWReports}
import com.normation.inventory.ldap.core.InventoryDit
import com.normation.exceptions.TechnicalException
import scala.xml._
import net.liftweb.common._
import net.liftweb.http._
import net.liftweb.util._
import Helpers._
import net.liftweb.http.js._
import JsCmds._
import JE._
import net.liftweb.http.SHtml._
import bootstrap.liftweb.LiftSpringApplicationContext.inject
import net.liftweb.http.Templates
import com.normation.rudder.repository.RoDirectiveRepository
import com.normation.cfclerk.services.TechniqueRepository
import bootstrap.liftweb.RudderConfig

/**
 * Display the last reports of a server
 * Based on template : templates-hidden/reports_server
 *
 *
 * @author Nicolas CHARLES
 *
 */
class ReportDisplayer(
    ruleRepository      : RoRuleRepository
  , directiveRepository : RoDirectiveRepository
  , reportingService    : ReportingService
  , techniqueRepository : TechniqueRepository
) {

  private[this] val getAllNodeInfos = RudderConfig.nodeInfoService.getAll _

  private[this] val templateByNodePath = List("templates-hidden", "reports_server")
  private def templateByNode() =  Templates(templateByNodePath) match {
    case Empty | Failure(_,_,_) =>
      throw new TechnicalException("Template for execution batch history not found. I was looking for %s.html".format(templateByNodePath.mkString("/")))
    case Full(n) => n
  }

  def reportByNodeTemplate = chooseTemplate("batches", "list", templateByNode)
  def directiveDetails = chooseTemplate("directive", "foreach", templateByNode)

  var staticReportsSeq : Seq[ExecutionBatch] = Seq[ExecutionBatch]()

  def display(reportsSeq : Seq[ExecutionBatch], tableId:String, node : NodeInfo): NodeSeq = {

    staticReportsSeq = reportsSeq.filter( x => ruleRepository.get(x.ruleId).isDefined  )
    bind("lastReportGrid",reportByNodeTemplate,
           "intro" ->  (staticReportsSeq.filter(x => (
                               (x.getNodeStatus().exists(x => x.nodeReportType == ErrorReportType)) ||
                               (x.getNodeStatus().exists(x => x.nodeReportType == RepairedReportType)) ||
                               (x.getNodeStatus().exists(x => x.nodeReportType == NoAnswerReportType))
                        )
                                ).toList match {
             case x if (x.size > 0) => <div>There are {x.size} out of {staticReportsSeq.size} reports that require our attention</div>
             case _ => if (staticReportsSeq.filter(x => (x.getNodeStatus().exists(x => x.nodeReportType == PendingReportType))).size>0) {
                     <div>Policy update in progress</div>
                   } else {
                     <div>All the last execution reports for this server are ok</div>
                   }
           } ),
           "grid" -> showReportDetail(staticReportsSeq, node),
           "missing" -> showMissingReports(staticReportsSeq),
           "unexpected" -> showUnexpectedReports(staticReportsSeq)
           )
  }

  def getComplianceData(executionsBatches : Seq[ExecutionBatch]) = {
    for {
      directiveLib <-directiveRepository.getFullDirectiveLibrary
      allNodeInfos <- getAllNodeInfos()
      reportStatus = executionsBatches.flatMap(x => x.getNodeStatus())
    } yield {
      val complianceData = ComplianceData(directiveLib,allNodeInfos)

      complianceData.getRuleComplianceDetails(reportStatus)

    }
  }

  def showReportDetail(executionsBatches : Seq[ExecutionBatch], node : NodeInfo) : NodeSeq = {

    val data = getComplianceData(executionsBatches).map(_.json).getOrElse(JsArray())


    reportsGridXml++
        Script(JsRaw(s"""
          createRuleComplianceTable("reportsGrid",${data.toJsCmd},"${S.contextPath}", ${refreshReportDetail(node).toJsCmd});
          createTooltip();
        """))

  }

  def refreshReportDetail (node : NodeInfo) = {

    def refreshData : Box[JsCmd] = {
      for {
        reports <- reportingService.findImmediateReportsByNode(node.id)
        data <- getComplianceData(reports)
      } yield {
        JsRaw(s"""refreshTable("reportsGrid",${data.json.toJsCmd});""")
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

  def asyncDisplay(node : NodeInfo) : NodeSeq = {
      Script(OnLoad(JsRaw("""
              | $("#%s").bind( "show", function(event, ui) {
              | if(ui.panel.id== '%s') { %s; }
              | });
              """.stripMargin('|').format("node_tabs",
            "node_reports",
            SHtml.ajaxCall(JsRaw(""),(v:String) => SetHtml("reportsDetails",displayReports(node)) )._2.toJsCmd
       )))
      )
  }

  def displayReports(node : NodeInfo) : NodeSeq = {
    reportingService.findImmediateReportsByNode(node.id) match {
      case e:EmptyBox => <div class="error">Could not fetch reports information</div>
      case Full(batches) => display(batches, "reportsGrid", node)
    }
  }

  def reportsGridXml : NodeSeq = {
    <table id="reportsGrid" class="fixedlayout tablewidth" cellspacing="0">
    </table>
  }

  def missingGridXml : NodeSeq = {

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
    }

  def missingLineXml : NodeSeq = {
      <tr>
        <td id="technique"></td>
        <td id="component"></td>
        <td id="value"></td>
      </tr>
    }

    def showMissingReports(batches:Seq[ExecutionBatch]) : NodeSeq = {
      def showMissingReport(report:((String,String),String,String)) : NodeSeq = {
        val techniqueName =report._2
        val techniqueVersion = report._3
        val reportValue = report._1
              ( "#technique *" #>  "%s (%s)".format(techniqueName,techniqueVersion)&
                "#component *" #>  reportValue._1&
                "#value *" #>  reportValue._2
              ) ( missingLineXml )
            }

      /*
       * To get missing reports we have to find them in each node report
       * So we have to go the value level and also get technique details at directive level for each report
       * we could add more information at each level (directive name? rule name?)
       * NOTE : a missing report is an unknown report with no message
       */
      val reports = batches.flatMap(x => x.getNodeStatus()).filter(_.nodeReportType==UnknownReportType).flatMap { reports =>
        val techniqueComponentsReports = reports.directives.filter(_.directiveReportType==UnknownReportType).flatMap{dir =>
          val componentsReport = dir.components.filter(_.componentReportType==UnknownReportType).flatMap{component =>
            val values = (component.componentValues++component.unexpectedCptValues).filter(value => value.cptValueReportType==UnknownReportType&&value.message.size==0)
            values.map(value => (component.component,value.componentValue))
            }
          val tech = directiveRepository.getActiveTechnique(dir.directiveId).map(tech => techniqueRepository.getLastTechniqueByName(tech.techniqueName).map(_.name).getOrElse("Unknown Technique")).getOrElse("Unknown Technique")
          val techVersion = directiveRepository.getDirective(dir.directiveId).map(_.techniqueVersion.toString).getOrElse("N/A")
          componentsReport.map(compo=> (compo,tech,techVersion))}
        techniqueComponentsReports }

      if (reports.size >0){
          ( "#reportLine" #> reports.flatMap(showMissingReport(_) )
          ).apply(missingGridXml ) ++
            Script( JsRaw("""
             var oTable%1$s = $('#%2$s').dataTable({
               "asStripeClasses": [ 'color1', 'color2' ],
               "bAutoWidth": false,
               "bFilter" : true,
               "bPaginate" : true,
               "bLengthChange": true,
               "bStateSave": true,
               "sCookiePrefix": "Rudder_DataTables_",
               "sPaginationType": "full_numbers",
               "bJQueryUI": true,
               "oLanguage": {
                 "sSearch": ""
               },
               "sDom": '<"dataTables_wrapper_top"fl>rt<"dataTables_wrapper_bottom"ip>',
               "aaSorting": [[ 0, "asc" ]],
               "aoColumns": [
                 { "sWidth": "150px" },
                 { "sWidth": "150px" },
                 { "sWidth": "150px" }
               ]
             } );
         """.format("missing","missingGrid") ) ) }
        else
          NodeSeq.Empty
        }

   def unexpectedGridXml : NodeSeq = {

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
      <br/>
    }

    def unexpectedLineXml : NodeSeq = {
      <tr>
        <td id="technique"></td>
        <td id="component"></td>
        <td id="value"></td>
        <td id="message"></td>
      </tr>
    }

    def showUnexpectedReports(batches:Seq[ExecutionBatch]) : NodeSeq = {
       def showUnexpectedReport(report:((String,String,List[String]),String,String)) : NodeSeq = {
        val techniqueName =report._2
        val techniqueVersion = report._3
        val reportValue = report._1
              (  "#technique *" #>  "%s (%s)".format(techniqueName,techniqueVersion)&
                "#component *" #>  reportValue._1 &
                "#value *" #>  reportValue._2 &
                "#message *" #>  <ul>{reportValue._3.map(msg => <li>{msg}</li>)}</ul>
              ) ( unexpectedLineXml )
            }

      /*
       * To get unexpected reports we have to find them in each node report
       * So we have to go the value level, get the messages
       * and also get technique details at directive level for each report
       * we could add more information at each level (directive name? rule name?)
       */
      val reports = batches.flatMap(x => x.getNodeStatus()).filter(_.nodeReportType==UnknownReportType).flatMap { reports =>
        val techniqueComponentsReports = reports.directives.filter(_.directiveReportType==UnknownReportType).flatMap{dir =>
          val componentsReport = dir.components.filter(_.componentReportType==UnknownReportType).flatMap{component =>
            val values = (component.componentValues++component.unexpectedCptValues).filter(value => value.cptValueReportType==UnknownReportType&&value.message.size!=0)
            values.map(value => (component.component,value.componentValue,value.message))
            }
          val tech = directiveRepository.getActiveTechnique(dir.directiveId).map(tech => techniqueRepository.getLastTechniqueByName(tech.techniqueName).map(_.name).getOrElse("Unknown Technique")).getOrElse("Unknown Technique")
          val techVersion = directiveRepository.getDirective(dir.directiveId).map(_.techniqueVersion.toString).getOrElse("N/A")
          componentsReport.map(compo=> (compo,tech,techVersion))}
        techniqueComponentsReports }
       if (reports.size >0){
         ( "#reportLine" #> reports.flatMap(showUnexpectedReport(_) )
         ).apply(unexpectedGridXml ) ++
            Script( JsRaw("""
             var oTable%1$s = $('#%2$s').dataTable({
               "asStripeClasses": [ 'color1', 'color2' ],
               "bAutoWidth": false,
               "bFilter" : true,
               "bPaginate" : true,
               "bLengthChange": true,
               "bStateSave": true,
               "sCookiePrefix": "Rudder_DataTables_",
               "sPaginationType": "full_numbers",
               "bJQueryUI": true,
               "oLanguage": {
                 "sSearch": ""
               },
               "sDom": '<"dataTables_wrapper_top"fl>rt<"dataTables_wrapper_bottom"ip>',
               "aaSorting": [[ 0, "asc" ]],
               "aoColumns": [
                 { "sWidth": "100px" },
                 { "sWidth": "100px" },
                 { "sWidth": "100px" },
                 { "sWidth": "200px" }
               ]
             } );
         """.format("unexpected","unexpectedGrid") ) ) }
        else
          NodeSeq.Empty
        }

}
