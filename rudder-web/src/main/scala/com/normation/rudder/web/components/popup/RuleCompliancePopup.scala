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
import com.normation.rudder.domain.reports._
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
import com.normation.rudder.domain.reports.RuleNodeStatusReport

/**
 *  That popup Display the compliance of a Rule by Node starting from different Scope ( Directive, Component, or a specific value)
 */
class RuleCompliancePopup(
    directiveLib : FullActiveTechniqueCategory
  , allNodeInfos : Map[NodeId, NodeInfo]
) extends Loggable {

  val htmlId_rulesGridZone = "rules_grid_zone"
  val htmlId_reportsPopup = "popup_" + htmlId_rulesGridZone
  val htmlId_modalReportsPopup = "modal_" + htmlId_rulesGridZone


  import RuleCompliancePopup._

  /**
   * Display the popup
   */
  def showPopup(
      statusReport : RuleStatusReport
    , ruleName     : String
    , directiveId  : DirectiveId
    , componentName: Option[String]
    , valueName    : Option[String]
  ) : JsCmd = {
    val popupHtml = createPopup(statusReport, ruleName, directiveId, componentName, valueName)

    SetHtml(htmlId_reportsPopup, popupHtml) & OnLoad(
        JsRaw("""$('.dataTables_filter input').attr("placeholder", "Filter");""")
    ) &
    JsRaw( s""" createPopup("${htmlId_modalReportsPopup}")""")
  }

  /**
   * Create popup content
   */
  private[this] def createPopup(
      statusReport : RuleStatusReport
    , ruleName     : String
    , directiveId  : DirectiveId
    , componentName: Option[String]
    , valueName    : Option[String]
  ) : NodeSeq = {

    val (techName, techVersion, directiveName) = directiveLib.allDirectives.get(directiveId).map { case(tech,dir) =>
        (tech.techniqueName.value, dir.techniqueVersion.toString, dir.name)
      }.getOrElse(("Unknown technique", "N/A", s"Missing name for directive with id '${directiveId.value}'"))

    (
      "#innerContent" #> {
        val xml = (
            <div>
              <ul>
                <li><b>Rule:</b> {ruleName}</li>
                <li><b>Directive:</b> {directiveName}</li>{(
                  componentName match {
                    case None => NodeSeq.Empty
                    case Some(cn) =>
                      <li><b>Component:</b> {cn}</li> ++ {
                        valueName match {
                          case None => NodeSeq.Empty
                          case Some(vn) => <li><b>Value:</b> {vn}</li>
                        }
                      }
                  }
                )}
              </ul>
            </div>
        )

        /**
         * Filter reports by directive, component and value
         */
        val filteredReports = statusReport.report.reports.flatMap( _.withFilteredElements(
            dirReport => dirReport.directiveId == directiveId
          , cptReport => componentName.fold(true)(name => cptReport.componentName == name)
          , valReport => valueName.fold(true)(name => valReport.componentValue == name)
        ) )

        val tab = showReportsByType(directiveId, statusReport, techName, techVersion)

        xml++tab
      }
    ).apply(popupXml)
  }

  /**
   * Build up to three tables displaying details about reports:
   *  - show them in the main "by node" compliance table
   *  - compute missing/unexpected/unkown table and show them in a specific table
   */
  def showReportsByType(
      directiveId: DirectiveId
    , reports    : RuleStatusReport
    , techName   : String
    , techVersion: String
  ) : NodeSeq = {

    val unknowns = reports.report.reports.flatMap( r => r.getValues( v => v.status == UnexpectedReportType).map { case (d,c,v) =>
      (r.nodeId, d,c,v)
    }  )
    val missing = reports.report.reports.flatMap( r => r.getValues( v => v.status == MissingReportType))

    val xml = (
         showNodeComplianceTable(directiveId, reports) ++
         showMissingReports(missing, "missing", 1, techName, techVersion) ++
         showUnexpectedReports(unknowns, "unexpected", 2, techName, techVersion)
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
      directiveId: DirectiveId
    , reports    : RuleStatusReport
  ): NodeSeq = {

    val data = ComplianceData.getRuleByNodeComplianceDetails(directiveId, reports, allNodeInfos)

    <table id="reportsDetailGrid" cellspacing="0" style="clear:both"/>
    <br/> ++
    Script(JsRaw(s"""createNodeComplianceTable("reportsDetailGrid",${data.json.toJsCmd},"${S.contextPath}");"""))

  }

  ///////////////// Show reports in Missing/unexepected/unknown status /////////////////////////

  def showMissingReports(
      reports         : Set[(DirectiveId, String, ComponentValueStatusReport)]
    , gridId          : String
    , tabid           : Int
    , techniqueName   : String
    , techniqueVersion: String
  ): NodeSeq = {
    def showMissingReport(report: (String, String)): NodeSeq = {
      ("#technique *" #> "%s (%s)".format(techniqueName, techniqueVersion) &
        "#component *" #> report._1 &
        "#value *" #> report._2)(missingLineXml)
    }

    if (reports.size > 0) {
      val components: Set[String] = reports.map(_._2)
      val missingreports = components.flatMap(component => reports.filter(_._2 == component).map(x => (component, x._3.componentValue)))

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




  def showUnexpectedReports(
      reports         : Set[(NodeId, DirectiveId, String, ComponentValueStatusReport)]
    , gridId          : String
    , tabid           : Int
    , techniqueName   : String
    , techniqueVersion: String
  ): NodeSeq = {
    def showUnexpectedReport(report: (NodeId, DirectiveId, String, ComponentValueStatusReport)): NodeSeq = {
      allNodeInfos.get(report._1) match {
        case Some(nodeInfo) => {
          ("#node *" #>
            <a class="unfoldable" href={ """/secure/nodeManager/searchNodes#{"nodeId":"%s"}""".format(report._1.value) }>
              <span class="curspoint noexpand">
                { nodeInfo.hostname }
              </span>
            </a> &
            "#technique *" #> "%s (%s)".format(techniqueName, techniqueVersion) &
            "#component *" #> report._3 &
            "#value *" #> report._4.componentValue &
            "#message *" #> <ul>{ report._4.messages.collect{ case(msg) if(msg.message.nonEmpty) => <li>{ msg.message.get }</li>} }</ul>)(unexpectedLineXml)
        }
        case None =>
          logger.error("An error occured when trying to load node %s".format(report._1.value))
          <div class="error">Node with ID "{ report._1.value }" is invalid</div>
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