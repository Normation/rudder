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
import com.normation.rudder.repository.RuleRepository
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
import com.normation.rudder.repository.DirectiveRepository
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
    ruleRepository      : RuleRepository
  , directiveRepository : DirectiveRepository 
  , reportingService    : ReportingService
  , techniqueRepository : TechniqueRepository) {
  
  
  
  def templateByNodePath = List("templates-hidden", "reports_server")
  def templateByNode() =  Templates(templateByNodePath) match {
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
           "grid" -> showReportDetail(staticReportsSeq)

           )
  }

  def showReportDetail(executionsBatches : Seq[ExecutionBatch]) : NodeSeq = {
    ("#reportsGrid [class+]" #> "fixedlayout tablewidth" &
     "#reportLine" #> executionsBatches.flatMap(x => x.getNodeStatus()).map { reportStatus =>
         ruleRepository.get(reportStatus.ruleId) match {
           case Full(rule) =>
             val tooltipid = Helpers.nextFuncName
             (
                 "#rule [class+]" #> "listopen" &
                 "#rule *" #> <span jsuuid={reportStatus.ruleId.value.replaceAll("-","")} ruleId={reportStatus.ruleId.value}>{rule.name}</span> &
                 "#severity *" #> getSeverityFromStatus(reportStatus.nodeReportType) &
                 ".unfoldable [class+]" #> getSeverityFromStatus(reportStatus.nodeReportType).replaceAll(" ", "") &
                 ".unfoldable [toggler]" #> tooltipid &
                 "#jsid [id]" #> tooltipid
             )(reportsLineXml) ++ showDirectivesReport(reportStatus.directives,tooltipid)
           case _ => <div>Could not find rule {reportStatus.ruleId} </div>
         }
     }
    )(reportsGridXml) 
    
  }
  
  def showDirectivesReport(directives : Seq[DirectiveStatusReport], id :String) : NodeSeq = {
    directives.flatMap { directive =>
      directiveRepository.getDirective(directive.directiveId) match {
        case Full(dir) =>
          val tech = directiveRepository.getActiveTechnique(dir.id).map(act => techniqueRepository.getLastTechniqueByName(act.techniqueName).map(_.name).getOrElse("Unknown technique")).getOrElse("Unknown technique")
          val techversion = dir.techniqueVersion;
          val tooltipid = Helpers.nextFuncName
          val severity = getSeverityFromStatus(directive.directiveReportType)
           (  "#directiveLine [class+]" #> severity.replaceAll(" ", "")  &
              "#directiveLine [id]" #> id &
              "#directive [class+]" #> "listopen" &
              "#directive *" #> <span>{dir.name}</span> &
              "#technique *" #> <span>{"%s (%s)".format(tech,techversion)}</span> &
              "#severity *" #> severity &
              "td [class+]" #> "detailReport" &
              ".unfoldable [toggler]" #> tooltipid
           )(directiveLineXml) ++ showComponentsReports(directive.components,tooltipid)
        case _ => <div>Could not fetch directive {directive.directiveId} </div>
      }
    }
  }
  
  def showComponentsReports(components : Seq[ComponentStatusReport], id:String) : NodeSeq = {
    components.flatMap { component =>
      val severity = getSeverityFromStatus(component.componentReportType)
      ("#componentLine [class+]" #> severity.replaceAll(" ", "")  &
       "#componentLine [id]" #> id &
       "td [class+]" #> "detailReport" &
       "#component *" #> <span>{component.component}</span> &
       "#severity *" #>  severity ) (
      component.componentValues.forall( x => x.componentValue =="None") match {
        case true => // only None, we won't show the details
         componentDetails
        case false => // standard  display that can be expanded
          val tooltipid = Helpers.nextFuncName
           (  "#component [class+]" #> "listopen" &
              ".unfoldable [toggler]" #> tooltipid
           )(componentDetails) ++ showComponentValueReport(component.componentValues,tooltipid)
      })
    }
  }
  
  def showComponentValueReport(values : Seq[ComponentValueStatusReport],id:String) : NodeSeq = {
    values.flatMap { value => val severity = getSeverityFromStatus(value.cptValueReportType)
           (  "#valueLine [class+]" #> severity.replaceAll(" ", "") &
              "#valueLine [id]" #> id &
              "td [class+]" #> "detailReport" &
              "#componentValue *" #> <span>{value.componentValue}</span> &
              "#keySeverity *" #> severity
           )(componentValueDetails)
    }
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
  
  def displayReports(node : NodeInfo) : NodeSeq = {
    display(reportingService.findImmediateReportsByNode(node.id), "reportsGrid")
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
        $(".unfoldable").click(function() {
          var togglerId = $(this).attr("toggler");
          var totoggle = $(this).siblings('#'+togglerId)
          var td = $(this).find("td.listclose")
          if (td.length > 0){
            totoggle.each( function() {
              if( $(this).find("td.listclose").length != 0)
                $(this).click();
            } )
            $(this).find("td.listclose").removeClass("listclose").addClass("listopen");
          } else {
            $(this).find("td.listopen").removeClass("listopen").addClass("listclose");
          }
          totoggle.each( function() { $(this).toggle() } );
        } );
      """);
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
          <th width="10%">Rule</th>
          <th width="12%">Directive</th>
          <th width="16%">Component</th>
          <th width="32%">Value</th>
          <th width="27%">Technique</th>
          <th width="13%">Compliance<span/></th>
        </tr>
      </thead>
      <tbody>
        <div id="reportLine"/>
      </tbody>
    </table>
  }

  def reportsLineXml : NodeSeq = {
    <tr class="unfoldable">
      <td id="rule" colspan="5"></td>
      <td name="severity"><div id="severity"/></td>
    </tr>
  }

  def directiveLineXml : NodeSeq = {
    <tr id="directiveLine" class="detailedReportLine unfoldable severity" style="display:none">
      <td class="emptyTd"/>
      <td id="directive" colspan="3" ></td>
      <td name="technique"><div id="technique"/></td>
      <td name="severity" ><div id="severity"/></td>
    </tr>
  }
  
  def componentDetails : NodeSeq = {
   <tr id="componentLine" class="detailedReportLine unfoldable severity" style="display:none">
      <td class="emptyTd" colspan="2"/>
      <td id="component" colspan="3"></td>
      <td name="severity"><div id="severity"/></td>
   </tr>

  }

  def componentValueDetails : NodeSeq = {
  <tr id="valueLine"  class="detailedReportLine severityClass severity " style="display:none">
      <td class="emptyTd" colspan="3"/>
      <td id="componentValue" colspan="2">></td>
      <td name="keySeverity"><div id="keySeverity"/></td>
  </tr>
  }
}
