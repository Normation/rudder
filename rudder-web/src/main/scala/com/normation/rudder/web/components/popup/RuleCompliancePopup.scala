/*
*************************************************************************************
* Copyright 2014 Normation SAS
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

package com.normation.rudder.web.components.popup

import com.normation.rudder.repository.FullActiveTechniqueCategory
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.nodes.NodeInfo
import scala.xml._
import com.normation.rudder.domain.reports.bean._
import net.liftweb.util.Helpers._
import net.liftweb.util._
import com.normation.rudder.domain.policies._
import net.liftweb.http.js.JsCmds._
import net.liftweb.http.js.JsCmd
import net.liftweb.http.SHtml
import net.liftweb.http.js.JE.JsRaw
import net.liftweb.http.js.JE.JsObj
import net.liftweb.http.js.JE.JsArray
import net.liftweb.http.js.JE.Str
import net.liftweb.http.S
import net.liftweb.common._
import com.normation.rudder.web.services.ComplianceData

/**
 *  That popup Display the compliance of a Rule by Node starting from different Scope ( Directive, Component, or a specific value)
 */



class RuleCompliancePopup(
    rule         :Rule
  , directiveLib : FullActiveTechniqueCategory
  , allNodeInfos : Map[NodeId, NodeInfo]
) extends Loggable {

  val htmlId_rulesGridZone = "rules_grid_zone"
  val htmlId_reportsPopup = "popup_" + htmlId_rulesGridZone
  val htmlId_modalReportsPopup = "modal_" + htmlId_rulesGridZone

  val complianceData = ComplianceData(rule,directiveLib,allNodeInfos)

  import RuleCompliancePopup._

  /**
   * Display the popup
   */
  def showPopup(directiveStatus: RuleStatusReport) : JsCmd = {
    val popupHtml = createPopup(directiveStatus)
    SetHtml(htmlId_reportsPopup, popupHtml) & OnLoad(
        JsRaw("""$('.dataTables_filter input').attr("placeholder", "Search");""")
    ) &
    JsRaw( s""" createPopup("${htmlId_modalReportsPopup}")""")
  }

  /**
   * Create popup content
   */
  private[this] def createPopup(directiveByNode: RuleStatusReport) : NodeSeq = {

    (
      "#innerContent" #> {
        val xml = directiveByNode match {
          case d:DirectiveRuleStatusReport =>
            <div>
              <ul>
                <li> <b>Rule:</b> {rule.name}</li>
                <li><b>Directive:</b> {directiveName(d.directiveId)}</li>
              </ul>
            </div>
          case c:ComponentRuleStatusReport =>
            <div>
              <ul>
                <li> <b>Rule:</b> {rule.name}</li>
                <li><b>Directive:</b> {directiveName(c.directiveId)}</li>
                <li><b>Component:</b> {c.component}</li>
              </ul>
            </div>
          case v@ComponentValueRuleStatusReport(directiveId,component,value,unexpanded,_,_) =>
            <div>
              <ul>
                <li> <b>Rule:</b> {rule.name}</li>
                <li><b>Directive:</b> {directiveName(directiveId)}</li>
                <li><b>Component:</b> {component}</li>
                <li><b>Value:</b> {v.key}</li>
              </ul>
            </div>
          }
        val directiveId = getDirectiveIdFromReport(directiveByNode)
        val tab = showReportsByType(directiveByNode, directiveId)

        xml++tab
      }
    ).apply(popupXml)
  }

  /**
   * treat reports
   *  - show them in the node compliance table
   *  - compute missing/unexpected/unkown table and show them in a specific table
   */
  def showReportsByType(
      reports: RuleStatusReport
    , directiveId : DirectiveId
  ) : NodeSeq = {
    val optDirective = directiveLib.allDirectives.get(directiveId)
    val (techName, techVersion) = optDirective.map { case (fat, d) => (fat.techniqueName.value, d.techniqueVersion.toString) }.getOrElse(("Unknown Technique", "N/A"))
    val missing = reports.processMessageReport(nreport => nreport.reportType == UnknownReportType & nreport.message.size == 0)
    val unexpected = reports.processMessageReport(nreport => nreport.reportType == UnknownReportType & nreport.message.size != 0)

    val xml = (
         showNodeComplianceTable(reports) ++
         showMissingReports(missing, "missing", 1, techName, techVersion) ++
         showUnexpectedReports(unexpected, "unexpected", 2, techName, techVersion)
    )
    xml
  }

  ///////////////// Compliance table by node and detail /////////////////////////

  /**
   * reports are displayed in cascaded dataTables
   * Parameters:
   *  reports : the reports we need to show
   */
  private[this] def showNodeComplianceTable (
      reports: RuleStatusReport
  ): NodeSeq = {

    val data = complianceData.getNodeComplianceDetails(reports)

    <table id="reportsDetailGrid" cellspacing="0" style="clear:both"/>
    <br/> ++
    Script(JsRaw(s"""createNodeComplianceTable("reportsDetailGrid",${data.json.toJsCmd},1,"${S.contextPath}");"""))

  }

  ///////////////// Show reports in Missing/unexepected/unknown status /////////////////////////

  def showMissingReports(reports: Seq[MessageReport], gridId: String, tabid: Int, techniqueName: String, techniqueVersion: String): NodeSeq = {
    def showMissingReport(report: (String, String)): NodeSeq = {
      ("#technique *" #> "%s (%s)".format(techniqueName, techniqueVersion) &
        "#component *" #> report._1 &
        "#value *" #> report._2)(missingLineXml)
    }

    if (reports.size > 0) {
      val components: Seq[String] = reports.map(_.component).distinct
      val missingreports = components.flatMap(component => reports.filter(_.component == component).map(report => (component, report.value))).distinct
      ("#reportLine" #> missingreports.flatMap(showMissingReport(_))).apply(missingGridXml(gridId)) ++
        Script(JsRaw("""
             var oTable%1$s = $('#%2$s').dataTable({
               "asStripeClasses": [ 'color1', 'color2' ],
               "bAutoWidth": false,
               "bFilter" : true,
               "bPaginate" : true,
               "bLengthChange": true,
               "sPaginationType": "full_numbers",
               "bJQueryUI": true,
               "bStateSave": true,
               "sCookiePrefix": "Rudder_DataTables_",
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
         """.format(tabid, gridId + "Grid")))
    } else
      NodeSeq.Empty
  }




  def showUnexpectedReports(reports: Seq[MessageReport], gridId: String, tabid: Int, techniqueName: String, techniqueVersion: String): NodeSeq = {
    def showUnexpectedReport(report: MessageReport): NodeSeq = {
      allNodeInfos.get(report.report.node) match {
        case Some(nodeInfo) => {
          ("#node *" #>
            <a class="unfoldable" href={ """/secure/nodeManager/searchNodes#{"nodeId":"%s"}""".format(report.report.node.value) }>
              <span class="curspoint noexpand">
                { nodeInfo.hostname }
              </span>
            </a> &
            "#technique *" #> "%s (%s)".format(techniqueName, techniqueVersion) &
            "#component *" #> report.component &
            "#value *" #> report.value &
            "#message *" #> <ul>{ report.report.message.map(msg => <li>{ msg }</li>) }</ul>)(unexpectedLineXml)
        }
        case None =>
          logger.error("An error occured when trying to load node %s".format(report.report.node.value))
          <div class="error">Node with ID "{ report.report.node.value }" is invalid</div>
      }
    }

    if (reports.size > 0) {
      ("#reportLine" #> reports.flatMap(showUnexpectedReport(_))).apply(unexpectedGridXml(gridId)) ++
        Script(JsRaw("""
             var oTable%1$s = $('#%2$s').dataTable({
               "asStripeClasses": [ 'color1', 'color2' ],
               "bAutoWidth": false,
               "bFilter" : true,
               "bPaginate" : true,
               "bLengthChange": true,
               "sPaginationType": "full_numbers",
               "bJQueryUI": true,
               "bStateSave": true,
               "sCookiePrefix": "Rudder_DataTables_",
               "oLanguage": {
                 "sSearch": ""
               },
               "sDom": '<"dataTables_wrapper_top"fl>rt<"dataTables_wrapper_bottom"ip>',
               "aaSorting": [[ 0, "asc" ]],
               "aoColumns": [
                 { "sWidth": "100px" },
                 { "sWidth": "100px" },
                 { "sWidth": "100px" },
                 { "sWidth": "100px" },
                 { "sWidth": "200px" }
               ]
             } );
         """.format(tabid, gridId + "Grid")))
    } else
      NodeSeq.Empty
  }

  private[this] def directiveName(id:DirectiveId) = directiveLib.allDirectives.get(id).map( _._2.name ).getOrElse("can't find directive name")

  /**
   * Get directive id from a report
   */
  def getDirectiveIdFromReport (report: RuleStatusReport): DirectiveId = {
    report match {
      case DirectiveRuleStatusReport(directiveId,_, _) =>
        directiveId
      case component : ComponentRuleStatusReport =>
        component.directiveId
      case value: ComponentValueRuleStatusReport =>
        value.directiveId
    }
  }

}

object RuleCompliancePopup {

  private val popupXml: NodeSeq = {
    <div class="simplemodal-title">
      <h1>Node compliance detail</h1>
      <hr/>
    </div>
    <div class="simplemodal-content" style="max-height:500px;overflow-y:auto;">
      <div id="innerContent"/>
    </div>
    <div class="simplemodal-bottom">
      <hr/>
      <div class="popupButton">
        <span>
          <button class="simplemodal-close" onClick="return false;">
            Close
          </button>
        </span>
      </div>
    </div>
  }


  // Reporting templates

  // Unknown reports templates

  private def unexpectedGridXml(id: String = "reports", message: String = ""): NodeSeq = {
    <h3>Unexpected reports</h3>
    <div>The following reports were received by Rudder, but did not match the reports declared by the Technique. This usually indicates a bug in the Technique being used.</div>
    <table id={ id + "Grid" } cellspacing="0" style="clear:both">
      <thead>
        <tr class="head">
          <th>Node<span/></th>
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

  private val unexpectedLineXml: NodeSeq = {
    <tr>
      <td id="node"></td>
      <td id="technique"></td>
      <td id="component"></td>
      <td id="value"></td>
      <td id="message"></td>
    </tr>
  }

  private def missingGridXml(id: String = "reports", message: String = ""): NodeSeq = {

    <h3>Missing reports</h3>
    <div>The following reports are what Rudder expected to receive, but did not. This usually indicates a bug in the Technique being used.</div>
    <table id={ id + "Grid" } cellspacing="0" style="clear:both">
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

  private def missingLineXml: NodeSeq = {
    <tr>
      <td id="technique"></td>
      <td id="component"></td>
      <td id="value"></td>
    </tr>
  }
}