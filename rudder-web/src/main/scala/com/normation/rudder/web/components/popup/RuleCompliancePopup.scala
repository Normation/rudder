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
import net.liftweb.http.S
import net.liftweb.common._

/**
 *  That popup Display the compliance of a Rule by Node starting from different Scope ( Directive, Component, or a specific value)
 */



class RuleCompliancePopup(rule:Rule) extends Loggable {

  val htmlId_rulesGridZone = "rules_grid_zone"
  val htmlId_reportsPopup = "popup_" + htmlId_rulesGridZone
  val htmlId_modalReportsPopup = "modal_" + htmlId_rulesGridZone

  import RuleCompliancePopup._
  /**
   * Create the popup
   */
  def showPopup(directiveStatus: RuleStatusReport, directiveLib: FullActiveTechniqueCategory, allNodeInfos: Map[NodeId, NodeInfo]) : JsCmd = {
    val popupHtml = createPopup(directiveStatus, directiveLib, allNodeInfos)
    SetHtml(htmlId_reportsPopup, popupHtml) & OnLoad(
        JsRaw("""
            $('.dataTables_filter input').attr("placeholder", "Search");
            """
        ) //&  initJsCallBack(tableId)
    ) &
    JsRaw( s""" createPopup("${htmlId_modalReportsPopup}")""")
  }

  /**
   * We need to have a Popup with all the ComponentValueRuleStatusReports for
   * cases when differents nodes have differents values
   */
  def showPopup(
      componentStatus : ComponentValueRuleStatusReport
    , cptStatusReports: Seq[ComponentValueRuleStatusReport]
    , directiveLib    : FullActiveTechniqueCategory
    , allNodeInfos    : Map[NodeId, NodeInfo]
  ) : JsCmd = {

    val popupHtml = createPopup(componentStatus, cptStatusReports, directiveLib, allNodeInfos)

    SetHtml(htmlId_reportsPopup, popupHtml) & OnLoad(
        JsRaw("""
            $('.dataTables_filter input').attr("placeholder", "Search");
            """
        ) //&  initJsCallBack(tableId)
    ) &
    JsRaw( s""" createPopup("${htmlId_modalReportsPopup}")""")
  }

   ///////////////// Compliance detail popup/////////////////////////


    /**
   * Unfold the report to (Seq[ComponentValueRuleStatusReport], DirectiveId)
   */
  def getComponentValueRule(report: RuleStatusReport): (Seq[ComponentValueRuleStatusReport], DirectiveId) = {
    report match {
      case DirectiveRuleStatusReport(directiveId, components, _) =>
        (components.flatMap(_.componentValues), directiveId)
      case ComponentRuleStatusReport(directiveId, component, values, _) =>
        (values, directiveId)
      case value: ComponentValueRuleStatusReport =>
        (Seq(value), value.directiveid)
    }
  }


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

  /*
   * reports are displayed in cascaded dataTables
   * Parameters:
   *  reports : the reports we need to show
   *  GridId  : the dataTable name
   *  tabid   : an identifier to ease javascript
   *  message : Message to display on top of the dataTable
   *
   */
  private[this] def showReports(reports: Seq[MessageReport], gridId: String, tabid: Int, allNodeInfos: Map[NodeId, NodeInfo], message: String = ""): NodeSeq = {

    /*
     * Show report about a node
     * Parameters :
     * nodeId, Seq[componentName,value, unexpandedValue,reportsMessage, reportType)
     */
    def showNodeReport(nodeReport: (NodeId, Seq[(String, String, Option[String], List[String], ReportType)])): NodeSeq = {
      allNodeInfos.get(nodeReport._1) match {
        case Some(nodeInfo) => {
          val status = ReportType.getSeverityFromStatus(ReportType.getWorseType(nodeReport._2.map(_._5)))
          ("#node *" #>
            <span class="unfoldable">
              { nodeInfo.hostname }
              {
                val xml = <img src="/images/icDetails.png" style="margin-bottom:-3px" class="noexpand"/>
                SHtml.a({ () => RedirectTo("""/secure/nodeManager/searchNodes#{"nodeId":"%s"}""".format(nodeReport._1.value)) }, xml, ("style", "padding-left:4px"), ("class", "noexpand"))
              }
            </span> &
            "#status *" #> <center>{ getDisplayStatusFromSeverity(status) }</center> &
            "#status [class+]" #> status &
            "#details *" #> showComponentReport(nodeReport._2))(reportLineXml)
        }
        case None =>
          logger.error("An error occured when trying to load node %s".format(nodeReport._1.value))
          <div class="error">Node with ID "{ nodeReport._1.value }" is invalid</div>
      }
    }

      /* Show the detail of a Component
       * Seq[componentName,value, unexpandedValue,reportsMessage, reportType)
       */
    def showComponentReport(componentReports: (Seq[(String, String, Option[String], List[String], ReportType)])): NodeSeq = {
      <table id={ Helpers.nextFuncName } cellspacing="0" style="display:none" class=" noMarginGrid tablewidth ">
        <thead>
          <tr class="head tablewidth">
            <th class="emptyTd"><span/></th>
            <th>Component<span/></th>
            <th>Status<span/></th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          {
            val components = componentReports.map(_._1).distinct

            val valueReports = components.map(tr => (tr, componentReports.filter(_._1 == tr).map(rep => (rep._2, rep._3, rep._4, rep._5))))
            valueReports.flatMap { report =>
              val status = ReportType.getSeverityFromStatus(ReportType.getWorseType(report._2.map(_._4)))
              ("#component *" #> report._1 &
                "#status *" #> <center>{ getDisplayStatusFromSeverity(status) }</center> &
                "#status [class+]" #> status &
                "#details *" #> showValueReport(report._2))(messageLineXml)
            }
          }
        </tbody>
      </table>
    }

    /*
     * Show the details of a Value
     * Seq [ value, unexpandedValue,reportsMessage, reportType)
     */
    def showValueReport(valueReport: (Seq[(String, Option[String], List[String], ReportType)])): NodeSeq = {
      <table id={ Helpers.nextFuncName } cellspacing="0" style="display:none" class="noMarginGrid tablewidth ">
        <thead>
          <tr class="head tablewidth">
            <th class="emptyTd"><span/></th>
            <th>Value<span/></th>
            <th>Message<span/></th>
            <th>Status<span/></th>
          </tr>
        </thead>
        <tbody>
          {
            valueReport.flatMap { report =>
              val status = ReportType.getSeverityFromStatus(report._4)
              ("#value *" #> {
                report._2 match {
                  case None => Text(report._1)
                  case Some(unexpanded) if unexpanded == report._1 => Text(report._1)
                  case Some(unexpanded) =>
                    val tooltipid = Helpers.nextFuncName
                    <div>
                      <span>{ report._1 }</span>
                      <span class="tooltipable" tooltipid={ tooltipid } title="">
                        <img src="/images/icInfo.png" style="padding-left:4px"/>
                      </span>
                      <div class="tooltipContent" id={ tooltipid }>
                        Value <b>{ report._1 }</b> was expanded from the entry <b>{ unexpanded }</b>
                      </div>
                    </div>
                }
              } &
                "#status *" #> <center>{ getDisplayStatusFromSeverity(status) }</center> &
                "#status [class+]" #> status &
                "#message *" #> <ul>{ report._3.map(msg => <li>{ msg }</li>) }</ul>)(messageValueLineXml)
            }
          }
        </tbody>
      </table>
    }


    ///// actual code for showReports methods /////

    if (reports.size > 0) {
      val nodes = reports.map(_.report.node).distinct
      val datas = nodes.map(node => {
        val report = reports.filter(_.report.node == node).map(report => (report.component, report.value, report.unexpandedValue, report.report.message, report.report.reportType))
        (node, report)
      })
      val innerJsFun = """
              var componentTab = $(this.fnGetNodes());
              componentTab.each( function () {
                $(this).unbind();
                $(this).click( function (e) {
                  if ($(e.target).hasClass('noexpand'))
                    return false;
                  var nTr = this;
                  var i = $.inArray( nTr, anOpen%1$s );
                    if ( i === -1 ) {
                    $(this).find("td.listopen").removeClass("listopen").addClass("listclose");
                    var nDetailsRow = Otable2.fnOpen( nTr, fnFormatDetails(Otable2, nTr), 'details' );
                    $('div.innerDetails table', nDetailsRow).dataTable({
                      "asStripeClasses": [ 'color1', 'color2' ],
                      "bAutoWidth": false,
                      "bFilter" : false,
                      "bPaginate" : false,
                      "bLengthChange": false,
                      "bInfo" : false,
                      "sPaginationType": "full_numbers",
                      "bJQueryUI": true,
                      "aaSorting": [[ 1, "asc" ]],
                      "aoColumns": [
                        { "sWidth": "40px", "bSortable": false },
                        { "sWidth": "110px" },
                        { "sWidth": "210px" },
                        { "sWidth": "50px" , "bSortable": false},
                      ]
                    } );
                    $('div.dataTables_wrapper:has(table.noMarginGrid)').addClass('noMarginGrid');
                    $('td.details', nDetailsRow).attr("colspan",4);
                    $('div.innerDetails table', nDetailsRow).attr("style","");
                    $('div.innerDetails', nDetailsRow).slideDown(300);
                      updatePopup();
                    anOpen%1$s.push( nTr );
                    createTooltip();
                    }
                    else {
                    $(this).find("td.listclose").removeClass("listclose").addClass("listopen");
                    $('div.innerDetails', $(nTr).next()[0]).slideUp( 300,function () {
                      oTable%1$s.fnClose( nTr );
                      anOpen%1$s.splice( i, 1 );
                      updatePopup();
                    } );
                  }
                } ); } )""".format(tabid, S.contextPath)

      val jsFun = """
            var tab = $($('#%2$s').dataTable().fnGetNodes());
            tab.each( function () {
              $(this).unbind();
              $(this).click( function (e) {
                if ($(e.target).hasClass('noexpand'))
                  return false;
                var nTr = this;
                var i = $.inArray( nTr, anOpen%1$s );
                if ( i === -1 ) {
                  $(this).find("td.listopen").removeClass("listopen").addClass("listclose");
                  var fnData = oTable%1$s.fnGetData( nTr );
                  var nDetailsRow = oTable%1$s.fnOpen( nTr, fnFormatDetails%1$s(oTable%1$s, nTr), 'details' );
                  var Otable2 = $('div.innerDetails table:first', nDetailsRow).dataTable({
                    "asStripeClasses": [ 'color1', 'color2' ],
                    "bAutoWidth": false,
                    "bFilter" : false,
                    "bPaginate" : false,
                    "bLengthChange": false,
                    "bInfo" : false,
                    "sPaginationType": "full_numbers",
                    "bJQueryUI": true,
                    "aaSorting": [[ 1, "asc" ]],
                    "aoColumns": [
                      { "sWidth": "20px", "bSortable": false },
                      { "sWidth": "350px" },
                      { "sWidth": "50px" },
                      { "sWidth": "10px", "bSortable": false  , "bVisible":false }
                    ],
                 "fnDrawCallback" : function( oSettings ) {%4$s}
                  } );
                  $('div.innerDetails table:first', nDetailsRow).attr("style","");
                  $('div.innerDetails', nDetailsRow).slideDown(300);
                      updatePopup();
                  $('div.dataTables_wrapper:has(table.noMarginGrid)').addClass('noMarginGrid');
                  anOpen%1$s.push( nTr );
                }
                else {
                   $(this).find("td.listclose").removeClass("listclose").addClass("listopen");
                    $('div.innerDetails', $(nTr).next()[0]).slideUp(300, function () {
                    oTable%1$s.fnClose( nTr );
                    anOpen%1$s.splice( i, 1 );
                      updatePopup();
                  } );
                }
          } );} );""".format(tabid, gridId + "Grid", S.contextPath, innerJsFun)
      ("#reportLine" #> datas.flatMap(showNodeReport(_))).apply(reportsGridXml(gridId, message)) ++
        /* Sorry about the Javascript
         * but we need to have dynamic definition of those datatables
         * As we need to have several dynamic datables, we have to add a specific identifier, the tabid
         * Everything is based what has been done for the previous dataTable
         */
        Script(JsRaw("""
            function fnFormatDetails( oTable, nTr ) {
              var fnData = oTable.fnGetData( nTr );
              var oTable2 = fnData[fnData.length-1];
              var sOut ='<div class="innerDetails">'+oTable2+'</div>';
              return sOut;
            }

            function fnFormatDetails%1$s( oTable, nTr ) {
              var fnData = oTable.fnGetData( nTr );
              var oTable2 = fnData[fnData.length-1]
              var sOut ='<div class="innerDetails">'+oTable2+'</div>';
              return sOut;
            }
            var anOpen%1$s = [];
             var oTable%1$s = $('#%2$s').dataTable({
               "asStripeClasses": [ 'color1', 'color2' ],
               "bAutoWidth": false,
               "bFilter" : true,
               "bPaginate" : true,
               "bLengthChange": true,
               "sPaginationType": "full_numbers",
               "bStateSave": true,
               "sCookiePrefix": "Rudder_DataTables_",
               "bJQueryUI": true,
               "oLanguage": {
                 "sSearch": ""
               },
               "sDom": '<"dataTables_wrapper_top"fl>rt<"dataTables_wrapper_bottom"ip>',
               "aaSorting": [[ 1, "asc" ]],
               "aoColumns": [
                 { "sWidth": "378px" },
                 { "sWidth": "50px" },
                 { "sWidth": "10px","bSortable": false  , "bVisible":false}
               ],
               "fnDrawCallback" : function( oSettings ) {%3$s}
             } );
                """.format(tabid, gridId + "Grid", jsFun)))
    } else
      NodeSeq.Empty
  }

  /**
   * convert the componentvaluesrules to componentValueRule and Directive
   * Checks as well that all componentvaluesrules belong to the same directive
   */
  def getComponentValueRule(entries: Seq[ComponentValueRuleStatusReport]): Box[(Seq[ComponentValueRuleStatusReport], DirectiveId)] = {
    entries.map(x => x.directiveid).distinct match {
      case seq if seq.size == 0 => Failure("Not enough ComponentValueRuleStatusReport")
      case seq if seq.size == 1 => Full((entries, seq.head))
      case seq => logger.error("Too many DirectiveIds fot a specific componentValueRule %s".format(seq)); Failure("Too many DirectiveIds fot a specific componentValueRule %s".format(seq))
    }
  }

  def showReportsByType(reports: Seq[ComponentValueRuleStatusReport], directiveId : DirectiveId, directiveLib: FullActiveTechniqueCategory, allNodeInfos: Map[NodeId, NodeInfo]): NodeSeq = {
    val optDirective = directiveLib.allDirectives.get(directiveId)
    val (techName, techVersion) = optDirective.map { case (fat, d) => (fat.techniqueName.value, d.techniqueVersion.toString) }.getOrElse(("Unknown Technique", "N/A"))
   // val error = reports.flatMap(report => report.processMessageReport(_.reportType == ErrorReportType))
    val missing = reports.flatMap(report => report.processMessageReport(nreport => nreport.reportType == UnknownReportType & nreport.message.size == 0))
    val unexpected = reports.flatMap(report => report.processMessageReport(nreport => nreport.reportType == UnknownReportType & nreport.message.size != 0))
   // val repaired = reports.flatMap(report => report.processMessageReport(_.reportType == RepairedReportType))
   // val success = reports.flatMap(report => report.processMessageReport(_.reportType == SuccessReportType))
    val all = reports.flatMap(report => report.processMessageReport(report => true))

    val xml = (
         showReports(all, "report", 0, allNodeInfos) ++ showMissingReports(missing, "missing", 1, techName, techVersion)
      ++ showUnexpectedReports(unexpected, "unexpected", 2, techName, techVersion, allNodeInfos)
    )
    xml
  }

  def showUnexpectedReports(reports: Seq[MessageReport], gridId: String, tabid: Int, techniqueName: String, techniqueVersion: String, allNodeInfos: Map[NodeId, NodeInfo]): NodeSeq = {
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


    private[this] def directiveName(id:DirectiveId, directiveLib: FullActiveTechniqueCategory) = directiveLib.allDirectives.get(id).map( _._2.name ).getOrElse("can't find directive name")

    private[this] def createPopup(directiveByNode: RuleStatusReport, directiveLib: FullActiveTechniqueCategory, allNodeInfos: Map[NodeId, NodeInfo]) : NodeSeq = {

    (
      "#innerContent" #> {
        val xml = directiveByNode match {
          case d:DirectiveRuleStatusReport =>
            <div>
              <ul>
                <li> <b>Rule:</b> {rule.name}</li>
                <li><b>Directive:</b> {directiveName(d.directiveId, directiveLib)}</li>
              </ul>
            </div>
          case c:ComponentRuleStatusReport =>
            <div>
              <ul>
                <li> <b>Rule:</b> {rule.name}</li>
                <li><b>Directive:</b> {directiveName(c.directiveid, directiveLib)}</li>
                <li><b>Component:</b> {c.component}</li>
              </ul>
            </div>
          case ComponentValueRuleStatusReport(directiveId,component,value,unexpanded,_,_) =>
            <div>
              <ul>
                <li> <b>Rule:</b> {rule.name}</li>
                <li><b>Directive:</b> {directiveName(directiveId, directiveLib)}</li>
                <li><b>Component:</b> {component}</li>
                <li><b>Value:</b> {value}</li>
              </ul>
            </div>
          }
        val (reports, directiveId) = getComponentValueRule(directiveByNode)
        val tab = showReportsByType(reports, directiveId, directiveLib, allNodeInfos)

        xml++tab
      }
    ).apply(popupXml)
  }

  private[this] def createPopup(
      componentStatus : ComponentValueRuleStatusReport
    , cptStatusReports: Seq[ComponentValueRuleStatusReport]
    , directiveLib    : FullActiveTechniqueCategory
    , allNodeInfos    : Map[NodeId, NodeInfo]
  ): NodeSeq = {
    (
      "#innerContent" #> {
        val xml = <div>
                    <ul>
                      <li><b>Rule:</b> { rule.name }</li>
                      <li><b>Directive:</b> { directiveName(componentStatus.directiveid, directiveLib) }</li>
                      <li><b>Component:</b> { componentStatus.component }</li>
                      <li><b>Value:</b> { componentStatus.unexpandedComponentValue.getOrElse(componentStatus.componentValue) }</li>
                    </ul>
                  </div>
        val tab = getComponentValueRule(cptStatusReports) match {
          case e: EmptyBox => <div class="error">Could not retrieve information from reporting</div>
          case Full((reports, directiveId)) => showReportsByType(reports, directiveId, directiveLib, allNodeInfos)
        }
        xml ++ tab
      }).apply(popupXml)
  }


  def getDisplayStatusFromSeverity(severity: String) : String = {
    S.?(s"reports.severity.${severity}")
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

  private def reportsGridXml(id: String = "reports", message: String = ""): NodeSeq = {
    <center><b>{ message }</b></center>
    <table id={ id + "Grid" } cellspacing="0" style="clear:both">
      <thead>
        <tr class="head">
          <th>Node<span/></th>
          <th>Status<span/></th>
          <th style="border-left:0;"></th>
        </tr>
      </thead>
      <tbody>
        <div id="reportLine"/>
      </tbody>
    </table>
    <br/>
  }

  private val reportLineXml: NodeSeq = {
    <tr class="cursor">
      <td id="node" class="listopen"></td>
      <td id="status"></td>
      <td id="details"/>
    </tr>
  }
  private val messageLineXml: NodeSeq = {
    <tr class="cursor">
      <td class="emptyTd"/>
      <td id="component" class="listopen"></td>
      <td id="status"></td>
      <td id="details"></td>
    </tr>
  }

  private val messageValueLineXml: NodeSeq = {
    <tr>
      <td class="emptyTd"/>
      <td id="value"></td>
      <td id="message"></td>
      <td id="status"></td>
    </tr>
  }


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