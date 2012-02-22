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

//lift std import
import scala.xml._
import net.liftweb.common._
import net.liftweb.http._
import net.liftweb.util._
import Helpers._
import net.liftweb.http.js._
import JsCmds._ // For implicits
import JE._
import net.liftweb.http.SHtml._
import bootstrap.liftweb.LiftSpringApplicationContext.inject
import net.liftweb.http.Templates

/**
 * Display the last reports of a server
 * Based on template : templates-hidden/reports_server
 * 
 * 
 * @author Nicolas CHARLES
 *
 */
class ReportDisplayer(ruleRepository : RuleRepository,
    reportingService : ReportingService) {
  
  
  
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
           "intro" ->  (staticReportsSeq.filter(x => ( (x.getErrorNodeIds.size>0) || 
                                                //(x.getWarnNode.size>0) ||
                                                (x.getRepairedNodeIds.size>0) ||
                                                (x.getNoReportNodeIds.size>0))).toList match {
             case x if (x.size > 0) => <div>There are {x.size} out of {staticReportsSeq.size} reports that require our attention</div>
             case _ => if (staticReportsSeq.filter(x => (x.getPendingNodeIds().size>0)).size>0) {
                     <div>Deployment in progress</div>
                   } else {
                     <div>All the last execution reports for this server are ok</div>
                   }
           } ),
           "lines" -> 
               (((staticReportsSeq.map { x => x match { 
                 case ex:ExecutionBatch if (ex.getErrorNodeIds.size > 0) => ("Error", ex)
                 //case ex:ExecutionBatch if (ex.getWarnNode.size > 0) => ("Warning", ex)
                 case ex:ExecutionBatch if (ex.getNoReportNodeIds.size > 0) => ("No answer", ex)
                 case ex:ExecutionBatch if (ex.getPendingNodeIds.size > 0) => ("Applying", ex)
                 case ex:ExecutionBatch if (ex.getRepairedNodeIds.size > 0) => ("Repaired", ex)
                 case ex:ExecutionBatch if (ex.getSuccessNodeIds.size > 0) => ("Success", ex)
                 case ex:ExecutionBatch => ("Unknown", ex)
               }
               }):Seq[(String, ExecutionBatch)]).flatMap {       
                   case s@(severity:String, executionBatch:ExecutionBatch) =>
                   ruleRepository.get(executionBatch.ruleId) match {
                       case Full(rule) =>                    
                        <tr class={severity.replaceAll(" ", "")}>
                        {bind("line",chooseTemplate("lastReportGrid","lines",reportByNodeTemplate),
                         "rule" -> <span jsuuid={executionBatch.ruleId.value.replaceAll("-","")} ruleId={executionBatch.ruleId.value}>{rule.name}</span> ,
                         "severity" -> severity )}
                        </tr>
                       case _ => NodeSeq.Empty 
                     }
                     case _ => NodeSeq.Empty

                   }
            )
           )
  }

  def displayReports(node : NodeInfo) : NodeSeq = {
    display(reportingService.findImmediateReportsByNode(node.id), "reportsGrid")
  }
  
  /**
   * show the execution batches of a unique server
   * Ought to be the last execution of a server, so that all policyinstance are unique
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
        var #table_var#;
        /* Formating function for row details */
        function fnFormatDetails ( id ) {
          var sOut = '<div id="'+id+'" class="reportDetailsGroup"/>';
          return sOut;
        }
      """.replaceAll("#table_var#",jsVarNameForId(tableId))
    ) & OnLoad(    
        JsRaw("""
          /* Event handler function */
          #table_var# = $('#%s').dataTable({
            "bAutoWidth": false,
            "bLengthChange": false,
            "bJQueryUI": false,
            "aaSorting": [[ 1, "asc" ]],
            "aoColumns": [ 
              { "sWidth": "300px" },
              { "sWidth": "200px" }
            ]
          });moveFilterAndPaginateArea('#%s');""".format(tableId, tableId).replaceAll("#table_var#",jsVarNameForId(tableId))
        )  // &  initJsCallBack(tableId)  //for now, no details on report #783
    )
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


  /**
   * Display the detail for reports
   * @param s : javascript id | policyinstance
   * @return
   */
  //for now, no details on report #783
//  private def details(s:String) : JsCmd = {
//    Alert("""error("TODO")""")
//  }
  
  
  /**
   * Look in the service for the given policyinstance uuid and returns some basic data
   */
//  private def showPolicyDetail(instance : RuleVal) : NodeSeq = {
//      <div class="reportsList"> 
//        <div class="reportsTitle">{instance.TechniqueId.value} : {}</div>
//        <span>
//        </span>
//          { /* if (instance.executionPlanning != None) {
//            TemporalVariableEditor.apply(instance.executionPlanning.get).toHtml
//          } */ }
//        <div>List of the variables defined: 
//          {instance.variables.map(x => Text(x._1 +":" +x._2.mkString(";") ) ++ <BR/>   )  }
//       </div>
//     </div>
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
      <span>{report.severity}, on the {DateFormaterService.getFormatedDate(report.executionDate)}, with the message : {report.message}</span>
    </div>
  }
  
  
  
  /**
   * Look in the service for the given policyinstance uuid and returns some basic data
   */
//  private def showPolicyDetail(uuid : String) : NodeSeq = {
//    <div class="error">TODO: correct the configuration rule display with id {uuid}</div>
//  }
}
