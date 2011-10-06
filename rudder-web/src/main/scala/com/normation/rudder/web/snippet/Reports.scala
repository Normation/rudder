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

package com.normation.rudder.web.snippet

import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies.ConfigurationRuleId
import com.normation.rudder.services.servers.ServerSummaryService
import com.normation.rudder.web.model._
import com.normation.rudder.domain.reports.bean.ExecutionBatch
import com.normation.rudder.domain.reports.bean.{Reports => TWReports}
import com.normation.rudder.services.policies.ConfigurationRuleValService
import com.normation.rudder.web.services._
import com.normation.rudder.domain.policies.{ConfigurationRuleVal}

import com.normation.rudder.web.services.ReportDisplayer
import com.normation.rudder.services.reports.ReportingService
import bootstrap.liftweb.LiftSpringApplicationContext.inject
import com.normation.cfclerk.domain.CFCPolicyInstanceId
import com.normation.rudder.web.components.DateFormaterService

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
import com.normation.inventory.ldap.core.InventoryDit
import com.normation.exceptions.TechnicalException


/**
 * Display the report list
 * Based on template : templates-hidden/reports_grid
 * Call the function displayReportList with an ExecutionBatch, and display
 * the reports, ordered by category -> this is called when we click on a policyinstance 
 * 
 */
class Reports {
  val reportDisplayer = inject[ReportDisplayer]
  val reportingService = inject[ReportingService]
  val acceptedServersDit = inject[InventoryDit]("acceptedServersDit")
  val serverService = inject[ServerSummaryService]
  val configurationRuleValService = inject[ConfigurationRuleValService]

  
  
  def templatePath = List("templates-hidden", "reports_grid")
  def template() =  Templates(templatePath) match {
    case Empty | Failure(_,_,_) => 
      throw new TechnicalException("Template for report grid not found. I was looking for %s.html".format(templatePath.mkString("/")))
    case Full(n) => n
  }
  def reportTemplate = chooseTemplate("reports", "report", template)
    
  /**
   * init the report list
   */
  val reportList : Box[ExecutionBatch] = { for {
    configurationRuleId <- S.param("configurationRuleId")
    lastReports <- reportingService.findImmediateReportsByConfigurationRule(ConfigurationRuleId(configurationRuleId)) 
  } yield {
    lastReports
  } }
  
  
  val policyInstance : Box[ConfigurationRuleVal] = { for {
     policyInstanceId <- S.param("policyInstance")
     instance <- configurationRuleValService.findConfigurationRuleVal(ConfigurationRuleId(policyInstanceId))
  } yield {
    instance
  } }
  /**
   * Show the grid with all the servers/reports
   * @param html
   * @return
   */
  def showResult(html:NodeSeq) :  NodeSeq = {
      (reportList, policyInstance) match {
        case (Failure(m,_,_), _) => <p class="error">Error : {m}</p>
        case (_, Failure(m,_,_)) => <p class="error">Error : {m}</p>
        case (Empty, _) => <p>There are no reports matching this policy</p>
        case (_, Empty) => <p>There are no policies matching this criteria</p>
        case (Full(reports), Full(instance)) => displayAndInit(reports, instance)
      }
  }
  
  
  def jsVarNameForId(tableId:String) = "oTable" + tableId
  
  
  def displayAndInit(report :ExecutionBatch, instance : ConfigurationRuleVal) : NodeSeq = {
    display(report, instance, "reportsGrid") ++
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
            "aaSorting": [[ 3, "asc" ]],
            "aoColumns": [ 
              { "sWidth": "200px" },
              { "sWidth": "300px" }
            ]
          });moveFilterAndPaginateArea('#%s');""".format(tableId, tableId).replaceAll("#table_var#",jsVarNameForId(tableId))
        ) &  initJsCallBack(tableId) 
    )
  }
  
 /**
   * Initialize JS callback bound to the servername item
   * You will have to do that for line added after table
   * initialization.
   */
  def initJsCallBack(tableId:String) : JsCmd = {
      JsRaw("""$('td[name="serverName"]', #table_var#.fnGetNodes() ).each( function () {
          $(this).click( function () {
            var nTr = this.parentNode;
            var opened = jQuery(nTr).attr("open");

            if (opened && opened.match("opened")) {
              jQuery(nTr).attr("open", "closed");
              #table_var#.fnClose(nTr);
            } else {
              jQuery(nTr).attr("open", "opened");
              var aPos = #table_var#.fnGetPosition( this );
            
              var aData = jQuery(#table_var#.fnGetData( aPos[0] ));
              var node = jQuery(aData[aPos[1]]);
              var id = node.attr("serverid");
              var jsid = node.attr("jsuuid");
              var ajaxParam = jsid + "|" + id;
              #table_var#.fnOpen( nTr, fnFormatDetails(jsid), 'details' );
              %s;
            }
          } );
        })
      """.format(
          SHtml.ajaxCall(JsVar("ajaxParam"), details _)._2.toJsCmd).replaceAll("#table_var#",
              jsVarNameForId(tableId))
     )
  }
  
  def display(reports :ExecutionBatch, crVal : ConfigurationRuleVal, tableId:String) = {
       bind("lastReportGrid",reportTemplate,
  // TODO : this will be multivalued
           "policyName" -> Text(crVal.policies.head.policyPackageId.name.value + ":" + crVal.policies.head.policyPackageId.version.toString),
           "policyInstanceId" -> Text(crVal.configurationRuleId.value ),
           "lines" -> ( ((reports.getSuccessServer().map ( x =>  ("Success" , x)) ++
               reports.getRepairedServer().map ( x => ("Repaired" , x)) ++
          		 //reports.getWarnServer().map ( x => ("Warn" , x)) ++
               reports.getErrorServer().map ( x =>  ("Error" , x)) ++
               reports.getPendingServer().map ( x =>  ("Applying" , x)) ++
               reports.getServerWithNoReports().map ( x => ("No answer" , x)) ):Seq[(String, NodeId)]).flatMap {
                 case s@(severity:String, uuid:NodeId) if (uuid != null) =>
                   serverService.find(acceptedServersDit, uuid) match {
                     case Full(servers) if ( servers.size == 1) => {
                        val srv = servers.head
                        <tr class={severity.replaceAll(" ", "")}>
                        {bind("line",chooseTemplate("lastReportGrid","lines",reportTemplate),
                         "hostname" -> <span style="cursor:pointer" jsuuid={uuid.value.replaceAll("-","")} serverid={uuid.value}>{srv.hostname}</span>,
                         "severity" -> severity )}
                        </tr>
                     }
                     case _ => NodeSeq.Empty
                   }
                  
               }
            )
           )
 
  }
  
  
  /**
   * Display the detail for reports
   * @param s
   * @return
   */
  private def details(s:String) : JsCmd = {
    val arr = s.split("\\|")
    SetHtml(arr(0), {
      reportList match {
          case Full(reports)=> 
              reports.executionReports.filter(x => (x.nodeId.value==arr(1))) match {
                case seq  if (seq.size> 0) => seq.flatMap( reportToXML _ )
                case _ => <p class="error"></p>
              }
          case _=> <p class="error"></p>
      } 
    })
  }
  
  /**
   * Display the report in the html form
   */
  def reportToXML(report : TWReports) : NodeSeq = {
    <div>
      <span>{report.severity}, on the {DateFormaterService.getFormatedDate(report.executionDate)}, with the message: {report.message}</span>
    </div>
  }
}
