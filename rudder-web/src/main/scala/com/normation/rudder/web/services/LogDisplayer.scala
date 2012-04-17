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
import com.normation.rudder.domain.policies.ConfigurationRuleId
import com.normation.rudder.domain.policies.ConfigurationRuleVal
import com.normation.rudder.services.servers.ServerSummaryService
import com.normation.rudder.web.model._
import com.normation.rudder.repository.{ReportsRepository, ConfigurationRuleRepository}
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.reports.bean.Reports
import com.normation.rudder.web.components.DateFormaterService
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
import com.normation.rudder.repository.PolicyInstanceRepository
import scala.collection._
import com.normation.rudder.domain.policies.PolicyInstanceId
import com.normation.exceptions.TechnicalException
import net.liftweb.http.Templates

/**
 * Show the reports from cfengine (raw data)
 */
class LogDisplayer(reportRepository :ReportsRepository,
		policyInstanceRepository : PolicyInstanceRepository, 
		configurationRuleRepository : ConfigurationRuleRepository) {


  private val templatePath = List("templates-hidden", "node_logs_tabs")
  private def template() =  Templates(templatePath) match {
    case Empty | Failure(_,_,_) =>
      throw new TechnicalException("Template for server details not found. I was looking for %s.html".format(templatePath.mkString("/")))
    case Full(n) => n
  }

  private def content = chooseTemplate("logs","content",template)

  private def jsVarNameForId(tableId:String) = "oTable" + tableId

  private val gridName = "logsGrid"


    
  def display(nodeId : NodeId) : NodeSeq = {
  	val PIMap = mutable.Map[PolicyInstanceId, String]()
	  val CRMap = mutable.Map[ConfigurationRuleId, String]()
	
	  
	  def getPIName(policyInstanceId : PolicyInstanceId) : String = {
	  	PIMap.get(policyInstanceId).getOrElse({val result = policyInstanceRepository.getPolicyInstance(policyInstanceId).map(_.name).openOr(policyInstanceId.value); PIMap += ( policyInstanceId -> result); result } )
	  }
	
	  def getCRName(configurationRuleId : ConfigurationRuleId) : String = {
	  	CRMap.get(configurationRuleId).getOrElse({val result = configurationRuleRepository.get(configurationRuleId).map(x => x.name).openOr(configurationRuleId.value); CRMap += ( configurationRuleId -> result); result } )
	  }
  
    val lines = reportRepository.findReportsByServer(nodeId, None, None, None, None).flatMap {
          case Reports(executionDate, configurationRuleId, policyInstanceId, nodeId, serial, component, keyValue, executionTimestamp, severity, message) =>
           <tr>
            <td>{DateFormaterService.getFormatedDate(executionDate)}</td>
            <td>{severity}</td>
            <td>{getPIName(policyInstanceId)}</td>
            <td>{getCRName(configurationRuleId)}</td>
            <td>{component}</td>
            <td>{if("None" == keyValue) "-" else keyValue}</td>
            <td>{message}</td>
           </tr>
        }
     
    ("tbody *" #> lines)(content)
    
  }

  /**
   * If it is displayed within a popup, we only display 5 logs per page
   */
  def initJs(withinPopup: Boolean = false) : JsCmd = {
    val extension = if (withinPopup) """"iDisplayLength": 5,""" else ""

    JsRaw("var %s;".format(jsVarNameForId(gridName))) &
    OnLoad(
        JsRaw("""
          /* Event handler function */
          #table_var# = $('#%1$s').dataTable({
            "asStripClasses": [ 'color1', 'color2' ],
            "bAutoWidth": false,
            "bFilter" :true,
            "bPaginate" :true,
            "bLengthChange": false,
            %2$s
        		"sPaginationType": "full_numbers",
        		"oLanguage": {
            	"sSearch": "Filter:"
        		},
            "bJQueryUI": false,
        		"aaSorting":[],
            "aoColumns": [
              { "sWidth": "120px" },
              { "sWidth": "80px" },
              { "sWidth": "110px" },
              { "sWidth": "120px" },
              { "sWidth": "100px" },
              { "sWidth": "100px" },
              { "sWidth": "220px" }
            ]
          });moveFilterAndFullPaginateArea('#%3$s');""".format(gridName,extension, gridName).replaceAll("#table_var#",jsVarNameForId(gridName))
        )
    )
  }

}