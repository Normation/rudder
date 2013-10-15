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
  , techniqueRepository : TechniqueRepository) {



  private[this] val templateByNodePath = List("templates-hidden", "reports_server")
  private def templateByNode() =  Templates(templateByNodePath) match {
    case Empty | Failure(_,_,_) =>
      throw new TechnicalException("Template for execution batch history not found. I was looking for %s.html".format(templateByNodePath.mkString("/")))
    case Full(n) => n
  }



  def reportByNodeTemplate = chooseTemplate("batches", "list", templateByNode)
  def directiveDetails = chooseTemplate("directive", "foreach", templateByNode)



  def jsVarNameForId(tableId:String) = "oTable" + tableId

  var staticReportsSeq : Seq[ExecutionBatch] = Seq[ExecutionBatch]()

  def display(reportsSeq : Seq[ExecutionBatch], tableId:String): NodeSeq = {

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
                     <div>Deployment in progress</div>
                   } else {
                     <div>All the last execution reports for this server are ok</div>
                   }
           } ),
           "grid" -> showReportDetail(staticReportsSeq),
           "missing" -> showMissingReports(staticReportsSeq),
           "unexpected" -> showUnexpectedReports(staticReportsSeq)
           )
  }

  def showReportDetail(executionsBatches : Seq[ExecutionBatch]) : NodeSeq = {
    ("#reportsGrid [class+]" #> "fixedlayout tablewidth " &
     "#reportLine" #> executionsBatches.flatMap(x => x.getNodeStatus()).map { reportStatus =>
         ruleRepository.get(reportStatus.ruleId) match {
           case Full(rule) =>
             val tooltipid = Helpers.nextFuncName
             ( "#details *" #> showDirectivesReport(reportStatus.directives,tooltipid) &
               "#rule *" #> <b>{rule.name}</b> &
               "#status *" #> <center>{getSeverityFromStatus(reportStatus.nodeReportType)}</center> &
               "#status [class+]" #> getSeverityFromStatus(reportStatus.nodeReportType).replaceAll(" ", "")
             )(reportsLineXml)
           case _ => <div>Could not find rule {reportStatus.ruleId} </div>
         }
     }
    )(reportsGridXml)

  }

  def showDirectivesReport(directives : Seq[DirectiveStatusReport], id :String) : NodeSeq = {
       <table id={Helpers.nextFuncName} cellspacing="0" style="display:none" class="noMarginGrid tablewidth ">
      <thead>
        <tr class="head tablewidth">
          <th class="emptyTd"><span/></th>
          <th >Directive<span/></th>
          <th >Status<span/></th>
          <th ></th>
        </tr>
      </thead>
      <tbody>{ directives.flatMap { directive =>
      directiveRepository.getDirective(directive.directiveId) match {
        case Full(dir) =>
          val tech = directiveRepository.getActiveTechnique(dir.id).map(act => techniqueRepository.getLastTechniqueByName(act.techniqueName).map(_.name).getOrElse("Unknown technique")).getOrElse("Unknown technique")
          val techversion = dir.techniqueVersion;
          val tooltipid = Helpers.nextFuncName
          val severity = getSeverityFromStatus(directive.directiveReportType)
          val directiveImage = <img   src="/images/icTools.png" style="padding-left:4px"/>
          val directiveEditLink:NodeSeq = if (!dir.isSystem)
              SHtml.a( {()=> RedirectTo("""/secure/configurationManager/directiveManagement#{"directiveId":"%s"}""".format(dir.id.value))},directiveImage,("style","padding-left:4px"))
            else
              NodeSeq.Empty
          val components = showComponentsReports(directive.components)
           ( "#status [class+]" #> severity.replaceAll(" ", "") &
             "#status *" #> <center>{severity}</center> &
             "#details *" #> components &
             "#directiveLink *" #> directiveEditLink &
             "#directiveInfo *" #>{
                        <b>{dir.name}</b>
                        <span class="tooltipable" tooltipid={tooltipid} title="">
                          <img   src="/images/icInfo.png" style="padding-left:4px; margin:0px;"/>
                        </span>
                         <span/>
                        <div class="tooltipContent" id={tooltipid}>
                          Directive <b>{dir.name}</b> is based on technique
                          <b>{tech}</b> (version {techversion})
                        </div> }
           ) (directiveLineXml)
        case _ => <div>Could not fetch directive {directive.directiveId} </div>
      }
    } }</tbody></table>
  }

  /*
   * Display component details of a directive and add its compoenent value details if needed
   */
  def showComponentsReports(components : Seq[ComponentStatusReport]) : NodeSeq = {
    val worstseverity= ReportType.getSeverityFromStatus(ReportType.getWorseType(components.map(_.componentReportType))).replaceAll(" ", "")
    <table id={Helpers.nextFuncName} cellspacing="0" style="display:none" class="noMarginGrid tablewidth">
     <thead>
       <tr class="head tablewidth">
       <th class="emptyTd"><span/></th>
       <th >Component<span/></th>
       <th >Status<span/></th>
       <th ></th>
     </tr>
    </thead>
    <tbody>{
      components.flatMap { component =>
        val severity = ReportType.getSeverityFromStatus(component.componentReportType)
        val value = showComponentValueReport(component.componentValues++component.unexpectedCptValues,worstseverity)
        ( "#status [class+]" #> severity.replaceAll(" ", "") &
          "#status *" #> <center>{severity}</center> &
          "#component *" #>  <b>{component.component}</b> &
          "#details *" #>  value
        ) (componentDetails)
        } }
      </tbody>
    </table>
  }

 def showComponentValueReport(values : Seq[ComponentValueStatusReport],directiveSeverity:String) : NodeSeq = {
    val worstseverity= ReportType.getSeverityFromStatus(ReportType.getWorseType(values.map(_.cptValueReportType))).replaceAll(" ", "")

    <table id={Helpers.nextFuncName} cellspacing="0" style="display:none" class="noMarginGrid tablewidth ">
      <thead>
        <tr class="head tablewidth">
          <th class="emptyTd"><span/></th>
          <th >Value<span/></th>
          <th >Message<span/></th>
          <th >Status<span/></th>
        </tr>
      </thead>
      <tbody>{
        values.flatMap { value =>
          val severity = ReportType.getSeverityFromStatus(value.cptValueReportType)
          ( "#valueStatus [class+]" #> severity.replaceAll(" ", "") &
            "#valueStatus *" #> <center>{severity}</center> &
            "#message *" #>  <ul>{value.message.map(msg => <li>{msg}</li>)}</ul>&
            "#componentValue *" #> {
              value.unexpandedComponentValue match {
                case None => <b>{value.componentValue}</b>
                case Some(unexpanded) if unexpanded == value.componentValue => <b>{value.componentValue}</b>
                case Some(unexpanded) =>
                  val tooltipid = Helpers.nextFuncName
                  <div>
                  <b>{value.componentValue}</b>
                  <span class="tooltipable" tooltipid={tooltipid} title="">
                    <img src="/images/icInfo.png" style="padding-left:4px"/>
                  </span>
                  <div class="tooltipContent" id={tooltipid}>
                    Value <b>{value.componentValue}</b> was expanded from the entry <b>{unexpanded}</b>
                  </div>
                  </div>}
            } &
            "#componentValue [class+]" #>  "firstTd"
         ) (componentValueDetails) } }
      </tbody>
    </table>
  }

  def getSeverityFromStatus(status : ReportType) : String = {
    status match {
      case SuccessReportType => "Success"
      case RepairedReportType => "Repaired"
      case ErrorReportType => "Error"
      case NoAnswerReportType => "No answer"
      case PendingReportType => "Applying"
      case _ => "Unknown"
    }
  }

  def asyncDisplay(node : NodeInfo) : NodeSeq = {
      Script(OnLoad(JsRaw("""
              | $("#%s").bind( "show", function(event, ui) {
              | if(ui.panel.id== '%s') { %s; }
              | });
              """.stripMargin('|').format("node_tabs",
            "node_reports",
            SHtml.ajaxCall(JsRaw(""),(v:String) => SetHtml("reportsDetails",displayReports(node)) & initJs("reportsGrid") )._2.toJsCmd
       )))
      )
  }

  def displayReports(node : NodeInfo) : NodeSeq = {
    reportingService.findImmediateReportsByNode(node.id) match {
      case e:EmptyBox => <div class="error">Could not fetch reports information</div>
      case Full(batches) => display(batches, "reportsGrid")
    }
  }

  /**
   * show the execution batches of a unique server
   * Ought to be the last execution of a server, so that all Directiveinstance are unique
   * otherwise the result might be unpredictable
   * @param batches
   * @return
   */
  def displayBatches(batches :Seq[ExecutionBatch]) : NodeSeq = {
   display(batches, "reportsGrid") ++
    Script(initJs("reportsGrid"))
  }


   /*
   * Init Javascript for the table with ID
   * 'tableId'
   */
  def initJs(tableId:String) : JsCmd = {
    JsRaw("""
        %s
       var anOpen = [];
       var oTable = $('#reportsGrid').dataTable( {
         "asStripeClasses": [ 'color1', 'color2' ],
         "bAutoWidth": false,
         "bFilter" : true,
         "bPaginate" : true,
         "bLengthChange": true,
         "sPaginationType": "full_numbers",
         "bJQueryUI": true,
         "oLanguage": {
           "sSearch": ""
         },
         "sDom": '<"dataTables_wrapper_top"fl>rt<"dataTables_wrapper_bottom"ip>',
         "aaSorting": [[ 1, "asc" ]],
         "aoColumns": [
           { "sWidth": "500px" },
           { "sWidth": "100px" },
           { "sWidth": "10px", "bSortable": false  , "bVisible":false }
                    ],
          "fnDrawCallback" : function( oSettings ) {%s}
                  } );
                  $('.dataTables_filter input').attr("placeholder", "Search");

      """.format(FormatDetailsJSFunction,ReportsGridClickFunction))
  }

    /*
     * That javascript function gather all the data needed to display the details
     * They are stocked in the details row of the line (which is not displayed)
     */
    val FormatDetailsJSFunction = """
      function fnFormatDetails( oTable, nTr ) {
        var fnData = oTable.fnGetData( nTr );
        var oTable2 = fnData[fnData.length-1];
        var sOut ='<div class="innerDetails">'+oTable2+'</div>';
        return sOut;
      }"""
      /*
       * That Javascript function describe the behavior of the inner dataTable
       * On click it open or close its details
       * the content is dynamically computed
       */
    def valueClickJSFunction = {
      """
      var componentPlusTd = $(this.fnGetNodes());
      componentPlusTd.each( function () {
        $(this).unbind();
        $(this).click( function (e) {
          if ($(e.target).hasClass('noexpand'))
            return false;
            var nTr = this;
            var i = $.inArray( nTr, anOpen );
           if ( i === -1 ) {
             $(this).find("td.listopen").removeClass("listopen").addClass("listclose");
            var details = fnFormatDetails(Otable3, nTr);
            var nDetailsRow = Otable3.fnOpen( nTr, details, 'details' );
            $('div.innerDetails table:first', nDetailsRow).dataTable({
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
              { "sWidth": "65px", "bSortable": false },
              { "sWidth": "75px" },
              { "sWidth": "345px" },
              { "sWidth": "100px" }
            ]
          });
          $('div.dataTables_wrapper:has(table.noMarginGrid)').addClass('noMarginGrid');
          $('td.details', nDetailsRow).attr("colspan",4);
          $('div.innerDetails table', nDetailsRow).attr("style","");
          $('div.innerDetails', nDetailsRow).slideDown(300);

          anOpen.push( nTr );
          createTooltip();
        }
        else {
          $(this).find("td.listclose").removeClass("listclose").addClass("listopen");
          $('div.innerDetails', $(nTr).next()[0]).slideUp( 300,function () {
            oTable.fnClose( nTr );
            anOpen.splice( i, 1 );
          } );
        }
      } );} );""".format(S.contextPath)
    }
      /*
       * That Javascript function describe the behavior of the inner dataTable
       * On click it open or close its details
       * the content is dynamically computed
       */
    def componentClickJSFunction = {
      """
      createTooltip();

      var directivePlusTd = $(this.fnGetNodes());
      directivePlusTd.each( function () {
        $(this).unbind();
        $(this).click( function (e) {
          if ($(e.target).hasClass('noexpand'))
            return false;
            var nTr = this;
        var i = $.inArray( nTr, anOpen );
        if ( i === -1 ) {
          $(this).find("td.listopen").removeClass("listopen").addClass("listclose");
          var nDetailsRow = Otable2.fnOpen( nTr, fnFormatDetails(Otable2, nTr), 'details' );
          var Otable3 = $('div.innerDetails table:first', nDetailsRow).dataTable({
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
              { "sWidth": "45px", "bSortable": false },
              { "sWidth": "447px" },
              { "sWidth": "100px" },
              { "sWidth": "10px" , "bSortable": false  , "bVisible":false}
            ],
          "fnDrawCallback" : function( oSettings ) {%2$s}
          });
          $('div.dataTables_wrapper:has(table.noMarginGrid)').addClass('noMarginGrid');
          $('td.details', nDetailsRow).attr("colspan",4);
          $('div.innerDetails table', nDetailsRow).attr("style","");
          $('div.innerDetails', nDetailsRow).slideDown(300);
          anOpen.push( nTr );
        }
        else {
          $(this).find("td.listclose").removeClass("listclose").addClass("listopen");
          $('div.innerDetails', $(nTr).next()[0]).slideUp( 300,function () {
            oTable.fnClose( nTr );
            anOpen.splice( i, 1 );
          } );
        }
      } ); } );""".format(S.contextPath,valueClickJSFunction)
    }
      /*
       * This is the main Javascript function to have cascaded DataTables
       */
   def ReportsGridClickFunction ={
     """
     var plusTd = $($('#reportsGrid').dataTable().fnGetNodes());

     plusTd.each(function(i) {
       var nTr = this.parentNode;
       var i = $.inArray( nTr, anOpen );
         if ( i != -1 ) {
           $(nTr).next().find("table").dataTable().fnDraw();
         }
     } );

     plusTd.each( function () {
       $(this).unbind();
       $(this).click( function (e) {
         if ($(e.target).hasClass('noexpand'))
           return false;
         var nTr = this;
         var i = $.inArray( nTr, anOpen );
         if ( i === -1 ) {
           $(this).find("td.listopen").removeClass("listopen").addClass("listclose");
           var nDetailsRow = oTable.fnOpen( nTr, fnFormatDetails(oTable, nTr), 'details' );
             var Otable2 = $('div.innerDetails table:first', nDetailsRow).dataTable({
             "asStripeClasses": [ 'color1', 'color2' ],
             "bAutoWidth": false,
             "bFilter" : false,
             "bPaginate" : false,
             "bLengthChange": false,
             "bInfo" : false,
             "sPaginationType": "full_numbers",
             "bJQueryUI": true,
             "aaSorting": [[ 2, "asc" ]],
             "aoColumns": [
               { "sWidth": "10px", "bSortable": false },
               { "sWidth": "472px" },
               { "sWidth": "100px" },
               { "sWidth": "10px", "bSortable": false  , "bVisible":false }
             ],
              "fnDrawCallback" : function( oSettings ) {%2$s}
           } );

           $('div.dataTables_wrapper:has(table.noMarginGrid)').addClass('noMarginGrid');
           $('div.innerDetails table:first', nDetailsRow).attr("style","");
           $('div.innerDetails', nDetailsRow).slideDown(300);
           anOpen.push( nTr );
         }
         else {
           $(this).find("td.listclose").removeClass("listclose").addClass("listopen");
           $('div.innerDetails', $(nTr).next()[0]).slideUp(300, function () {
             oTable.fnClose( nTr );
             anOpen.splice( i, 1 );
           } );
         }
   } );} );""".format(S.contextPath,componentClickJSFunction)
   }

   /**
   * Initialize JS callback bound to the servername item
   * You will have to do that for line added after table
   * initialization.
   */
  //for now, no details on report #783
//  def initJsCallBack(tableId:String) : JsCmd = {
//      JsRaw("""$('td[name="crName"]', #table_var#.fnGetNodes() ).each( function () {
//          $(this).click( function () {
//            var nTr = this.parentNode;
//            var opened = jQuery(nTr).prop("open");
//
//            if (opened && opened.match("opened")) {
//              jQuery(nTr).prop("open", "closed");
//              #table_var#.fnClose(nTr);
//            } else {
//              jQuery(nTr).prop("open", "opened");
//              var aPos = #table_var#.fnGetPosition( this );
//
//              var aData = jQuery(#table_var#.fnGetData( aPos[0] ));
//              var node = jQuery(aData[aPos[1]]);
//              var id = node.attr("ruleId");
//              var jsid = node.attr("jsuuid");
//              var ajaxParam = jsid + "|" + id;
//              #table_var#.fnOpen( nTr, fnFormatDetails(jsid), 'details' );
//              %s;
//            }
//          } );
//        })
//      """.format(
//          SHtml.ajaxCall(JsVar("ajaxParam"), details _)._2.toJsCmd).replaceAll("#table_var#",
//              jsVarNameForId(tableId))
//     )
//  }




  private def showBatchDetail(batch:ExecutionBatch) : NodeSeq = {
    batch match {

      case conf : ConfigurationExecutionBatch =>
        <div class="reportsGroup">Configuration valid {conf.endDate match {
            case None => Text("since " + conf.beginDate)
            case Some(date) => Text("from " + conf.beginDate + " until " +date)
          }
        }
         List of reports :
          <div class="reportsList">{ batch.executionReports.flatMap( x => reportToXML(x) ) }</div>
        </div>
    }
  }




  /**
   * Display the report in the html form
   */
  def reportToXML(report : TWReports) : NodeSeq = {
    <div>
      <span>{report.severity}, oqn the {DateFormaterService.getFormatedDate(report.executionDate)}, with the message : {report.message}</span>
    </div>
  }

  def reportsGridXml : NodeSeq = {
    <table id="reportsGrid" cellspacing="0">
      <thead>
        <tr class="head tablewidth">
          <th>Rule</th>
          <th>Status<span/></th>
		  <th ></th>
        </tr>
      </thead>
      <tbody>
        <div id="reportLine"/>
      </tbody>
    </table>
  }

  def reportsLineXml : NodeSeq = {
    <tr>
      <td id="rule" class="listopen cursor"></td>
      <td id="status" class="firstTd"></td>
      <td id="details" ></td>
    </tr>
  }

  def directiveLineXml : NodeSeq = {
    <tr id="directiveLine" class="detailedReportLine  severity cursor">
      <td id="first" class="emptyTd"/>
      <td id="directive" class="nestedImg listopen"><span id="directiveInfo"/><span id="directiveLink"/></td>
      <td id="status" class="firstTd"></td>
      <td id="details" ></td>
    </tr>
  }

  def componentDetails : NodeSeq = {
    <tr id="componentLine" class="detailedReportLine severity cursor" >
      <td id="first" class="emptyTd"/>
      <td id="component" class="listopen"></td>
      <td id="status" class="firstTd"></td>
      <td id="details"/>
    </tr>
  }

  def componentValueDetails : NodeSeq = {
    <tr id="valueLine"  class="detailedReportLine severityClass severity ">
      <td id="first" class="emptyTd"/>
      <td id="componentValue" class="firstTd"></td>
      <td id="message"></td>
      <td id="valueStatus" class="firstTd"></td>
    </tr>
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
