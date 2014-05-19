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

package com.normation.rudder.web.components

import com.normation.rudder.domain.policies._
import com.normation.rudder.repository.FullActiveTechniqueCategory
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.nodes.NodeInfo
import scala.xml._
import net.liftweb.http._
import net.liftweb.common._
import com.normation.rudder.domain.reports.bean._
import net.liftweb.util.Helpers._
import net.liftweb.util.Helpers
import net.liftweb.http.js.JsCmds._
import net.liftweb.http.js.JE._
import net.liftweb.http.js.JsCmd
import bootstrap.liftweb.RudderConfig
import com.normation.rudder.web.components.popup.RuleCompliancePopup
import com.normation.rudder.web.model.WBTextField
import com.normation.rudder.web.model.WBTextAreaField
import com.normation.rudder.web.model.WBSelectField

/**
 *   This component display the compliance of a Rule by showing compliance of every Directive  
 *   It generates all Data and put them in a DataTable
 */
object RuleCompliance {
  private def details =
    (for {
      xml <- Templates("templates-hidden" :: "components" :: "ComponentRuleEditForm" :: Nil)
    } yield {
      chooseTemplate("component", "details", xml)
    }) openOr Nil
}

class RuleCompliance (rule : Rule) extends Loggable {

  private[this] val reportingService = RudderConfig.reportingService
  private[this] val categoryService  = RudderConfig.ruleCategoryService

  val popup = new RuleCompliancePopup(rule)

  def  display(directiveLib: FullActiveTechniqueCategory, allNodeInfos: Map[NodeId, NodeInfo]) : NodeSeq = {

    (
      "#ruleName" #>   rule.name &
      "#ruleCategory" #> categoryService.shortFqdn(rule.categoryId) &
      "#rudderID" #> rule.id.value.toUpperCase &
      "#ruleShortDescription" #> rule.shortDescription &
      "#ruleLongDescription" #>  rule.longDescription &
      "#compliancedetails" #> showCompliance(directiveLib, allNodeInfos)
    )(RuleCompliance.details)
  }

  /*
   * For each table : the subtable is contained in td : details
   * when + is clicked: it gets the content of td details then process it has a datatable
   */
  def showCompliance(directiveLib: FullActiveTechniqueCategory, allNodeInfos: Map[NodeId, NodeInfo]) : NodeSeq = {

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
    val innerClickJSFunction = """
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
                  { "sWidth": "355px" },
                  { "sWidth": "50px" },
                  { "sWidth": "120px" },
                  { "sWidth": "10px" , "bSortable": false  , "bVisible":false}
                ]
              });
              $('div.dataTables_wrapper:has(table.noMarginGrid)').addClass('noMarginGrid');
              $('td.details', nDetailsRow).attr("colspan",5);
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
      } ); } );""".format(S.contextPath)
      /*
       * This is the main Javascript function to have cascaded DataTables
       */
    val ReportsGridClickFunction = """
     createTooltip();
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
           var Otable2 =  $('div.innerDetails table:first', nDetailsRow).dataTable({
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
               { "sWidth": "20px", "bSortable": false },
               { "sWidth": "375px" },
               { "sWidth": "50px" },
               { "sWidth": "120px" },
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
    } ) } );""".format(S.contextPath,innerClickJSFunction)

    /*
     * It displays the report Detail of a Rule
     * It displays each Directive and prepare its components detail
     */
    def showReportDetail(batch : Box[Option[ExecutionBatch]], directiveLib: FullActiveTechniqueCategory) : NodeSeq = {
      batch match {
        case e: EmptyBox => <div class="error">Error while fetching report information</div>
        case Full(None) => NodeSeq.Empty
        case Full(Some(reports)) =>
          val directivesreport=reports.getRuleStatus().filter(dir => rule.directiveIds.contains(dir.directiveId))
          val tooltipid = Helpers.nextFuncName
          ( "#reportsGrid [class+]" #> "tablewidth" &
            "#reportLine" #> {
              directivesreport.flatMap { directiveStatus =>
                directiveLib.allDirectives.get(directiveStatus.directiveId) match {
                  case Some((fullActiveTechnique, directive))  => {

                    val tech = fullActiveTechnique.techniques.get(directive.techniqueVersion).map(_.name).getOrElse("Unknown technique")
                    val techversion = directive.techniqueVersion;
                    val tooltipid = Helpers.nextFuncName
                    val components= showComponentsReports(directiveStatus.components)
                    val severity = ReportType.getSeverityFromStatus(directiveStatus.directiveReportType)
                    ( "#status [class+]" #> severity &
                      "#status *" #> <center>{getDisplayStatusFromSeverity(severity)}</center> &
                      "#details *" #> components &
                      "#directive [class+]" #> "listopen" &
                      "#directive *" #>{
                        <span>
                          <b>{directive.name}</b>
                          <span class="tooltipable" tooltipid={tooltipid} title="">
                            <img   src="/images/icInfo.png" style="padding-left:4px"/>
                          </span>
                          { val xml = <img   src="/images/icPen.png" style="padding-left:4px" class="noexpand"/>
                            SHtml.a( {()=> RedirectTo("""/secure/configurationManager/directiveManagement#{"directiveId":"%s"}""".format(directive.id.value))},xml,("style","padding-left:4px"),("class","noexpand"))
                          }
                          <div class="tooltipContent" id={tooltipid}>
                            Directive <b>{directive.name}</b> is based on technique
                            <b>{tech}</b> (version {techversion})
                          </div>
                        </span> }&
                      "#severity *" #> buildComplianceChart(directiveStatus)
                    ) (reportsLineXml)
                  }
                  case None =>
                    logger.error(s"An error occured when trying to load directive ${directiveStatus.directiveId.value}.")
                    <div class="error">Node with ID "{directiveStatus.directiveId.value}" is invalid</div>
                }
              }
            }
          ) (reportsGridXml) ++ Script( JsRaw("""
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
                    "bStateSave": true,
                    "sCookiePrefix": "Rudder_DataTables_",
                    "oLanguage": {
                      "sSearch": ""
                    },
                    "sDom": '<"dataTables_wrapper_top"fl>rt<"dataTables_wrapper_bottom"ip>',
                    "aaSorting": [[ 1, "asc" ]],
                    "aoColumns": [
                      { "sWidth": "403px" },
                      { "sWidth": "50px" },
                      { "sWidth": "120px" },
                      { "sWidth": "10px", "bSortable": false  , "bVisible":false }
                    ],
                    "fnDrawCallback" : function( oSettings ) {%s}
                  } );
                  $('.dataTables_filter input').attr("placeholder", "Search");
                  """.format(FormatDetailsJSFunction,ReportsGridClickFunction) ) )
      }
    }

    /*
     * Display component details of a directive and add its compoenent value details if needed
     */
    def showComponentsReports(components : Seq[ComponentRuleStatusReport]) : NodeSeq = {
      val worstseverity= ReportType.getSeverityFromStatus(ReportType.getWorseType(components.map(_.componentReportType))).replaceAll(" ", "")
      <table id={Helpers.nextFuncName} cellspacing="0" style="display:none" class="noMarginGrid tablewidth">
      <thead>
         <tr class="head tablewidth">
           <th class="emptyTd"><span/></th>
           <th >Component<span/></th>
           <th >Status<span/></th>
           <th >Compliance<span/></th>
           <th style="border-left:0;" ></th>
         </tr>
      </thead>
      <tbody>{
      components.flatMap { component =>
        val severity = ReportType.getSeverityFromStatus(component.componentReportType)
        ( "#status [class+]" #> severity &
          "#status *" #> <center>{getDisplayStatusFromSeverity(severity)}</center> &
          "#component *" #>  <b>{component.component}</b> &
          "#severity *" #>  buildComplianceChart(component)
          ) (
            if (component.componentValues.forall( x => x.componentValue =="None")) {
              // only None, we won't show the details, we don't need the plus and that td should not be clickable
              ("* [class+]" #> "noexpand").apply(componentDetails)
            } else {
              // standard  display that can be expanded
              val tooltipid = Helpers.nextFuncName
              val value = showComponentValueReport(component.componentValues,worstseverity)
              ( "#details *" #>  value &
                "tr [class+]" #> "cursor" &
                "#component [class+]" #>  "listopen"
              ) (componentDetails )
            }
      )  }  }
      </tbody>
    </table>
    }

    /*
     * Display component value details
     */
    def showComponentValueReport(values : Seq[ComponentValueRuleStatusReport],directiveSeverity:String) : NodeSeq = {
    val worstseverity= ReportType.getSeverityFromStatus(ReportType.getWorseType(values.map(_.cptValueReportType))).replaceAll(" ", "")
    <table id={Helpers.nextFuncName} cellspacing="0" style="display:none" class="noMarginGrid tablewidth ">
      <thead>
        <tr class="head tablewidth">
          <th class="emptyTd"><span/></th>
          <th >Value<span/></th>
          <th >Status<span/></th>
          <th >Compliance<span/></th>
          <th style="border-left:0;" ></th>
        </tr>
      </thead>
      <tbody>{
        // we need to group all the ComponentValueRuleStatusReports by unexpandedComponentValue if any, or by component value
        // and agregate them together
        val reportsByComponents = values.groupBy { entry => entry.unexpandedComponentValue.getOrElse(entry.componentValue)}
        reportsByComponents.map { case (key, entries) =>
          val severity = ReportType.getWorseType(entries.map(_.cptValueReportType))
          ComponentValueRuleStatusReport(
             entries.head.directiveid // can't fail because we are in a groupBy
           , entries.head.component  // can't fail because we are in a groupBy
           , key
           , None // TODO : is it what we want ??
           , severity
           , entries.flatMap(_.reports)
          )
        }.flatMap { value =>
          val severity = ReportType.getSeverityFromStatus(value.cptValueReportType)
          ( "#valueStatus [class+]" #> severity &
            "#valueStatus *" #> <center>{getDisplayStatusFromSeverity(severity)}</center> &
            "#componentValue *" #>  <b>{value.componentValue}</b> &
            "#componentValue [class+]" #>  "firstTd" &
            "#keySeverity *" #> buildComplianceChartForComponent(value, reportsByComponents.get(value.componentValue))
         ) (componentValueDetails) } }
      </tbody>
    </table>
    }

    def reportsGridXml : NodeSeq = {
    <table id="reportsGrid" cellspacing="0">
      <thead>
        <tr class="head tablewidth">
          <th >Directive<span/></th>
          <th >Status<span/></th>
          <th >Compliance<span/></th>
          <th style="border-left:0;" ></th>
        </tr>
      </thead>
      <tbody>
        <div id="reportLine"/>
      </tbody>
    </table>
    }

    def reportsLineXml : NodeSeq = {
    <tr class="cursor">
      <td id="directive" class="nestedImg"></td>
      <td id="status" class="firstTd"></td>
      <td name="severity" class="firstTd"><div id="severity" style="text-align:right;"/></td>
      <td id="details" ></td>
    </tr>
    }

    def componentDetails : NodeSeq = {
    <tr id="componentLine" class="detailedReportLine severity" >
      <td id="first" class="emptyTd"/>
      <td id="component" ></td>
      <td id="status" class="firstTd"></td>
      <td name="severity" class="firstTd"><div id="severity" style="text-align:right;"/></td>
      <td id="details"/>
    </tr>
    }

    def componentValueDetails : NodeSeq = {
    <tr id="valueLine"  class="detailedReportLine severityClass severity ">
      <td id="first" class="emptyTd"/>
      <td id="componentValue" class="firstTd"></td>
      <td id="valueStatus" class="firstTd"></td>
      <td name="keySeverity" class="firstTd"><div id="keySeverity" style="text-align:right;"/></td>
      <td/>
    </tr>
    }



    def buildComplianceChart(rulestatusreport:RuleStatusReport) : NodeSeq = {
      rulestatusreport.computeCompliance match {
        case Some(percent) =>  {
          val text = Text(percent.toString + "%")
          val attr = ("class","noexpand")
          SHtml.a({() => popup.showPopup(rulestatusreport, directiveLib, allNodeInfos)}, text,attr)
        }
        case None => Text("Not Applied")
      }
    }

    def buildComplianceChartForComponent(
      ruleStatusReport: ComponentValueRuleStatusReport
    , values          : Option[Seq[ComponentValueRuleStatusReport]]
    ) : NodeSeq = {
    ruleStatusReport.computeCompliance match {
      case Some(percent) =>  {
        val text = Text(percent.toString + "%")
        val attr = ("class","noexpand")
        values match {
          case None => SHtml.a({() => popup.showPopup(ruleStatusReport, directiveLib, allNodeInfos)}, text,attr)
          case Some(reports) => SHtml.a({() => popup.showPopup(ruleStatusReport, reports, directiveLib, allNodeInfos)}, text,attr)
        }
      }
      case None => Text("Not Applied")
    }
    }

    val batch = reportingService.findImmediateReportsByRule(rule.id)

    <div>
    <hr class="spacer" />
        {showReportDetail(batch, directiveLib)}
    </div>++ Script( OnLoad( After( TimeSpan(100), JsRaw("""createTooltip();"""))))
  }



  def buildDisabled(toDisable: List[Int]): String = {
    toDisable match {
      case Nil => ""
      case value :: Nil => value.toString()
      case value :: rest => "%s, %s".format(value, buildDisabled(rest))
    }
  }

  def getDisplayStatusFromSeverity(severity: String) : String = {
    S.?(s"reports.severity.${severity}")
  }

}