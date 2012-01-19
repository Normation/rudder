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

package com.normation.rudder.web.snippet.administration

import scala.xml._
import net.liftweb.http.{DispatchSnippet,S}
import net.liftweb.common._
import net.liftweb.util._
import net.liftweb.util.Helpers._
import bootstrap.liftweb.LiftSpringApplicationContext.inject
import com.normation.rudder.repository.EventLogRepository
import com.normation.eventlog.EventLog
import com.normation.rudder.domain.log._
import com.normation.rudder.web.components.DateFormaterService
import net.liftweb.http.js._
import net.liftweb.http.js.JE._
import net.liftweb.http.js.JsCmds._
import net.liftweb.http.SHtml
import com.normation.rudder.services.log.EventLogDetailsService
import com.normation.rudder.domain.policies._
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.cfclerk.domain.PolicyPackageName
import com.normation.rudder.domain.queries.Query
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.log.InventoryLogDetails
import com.normation.rudder.web.model.JsInitContextLinkUtil._
import com.normation.rudder.web.services.EventListDisplayer

class EventLogsViewer extends DispatchSnippet with Loggable {
  private[this] val repos = inject[EventLogRepository]
  private[this] val logDetailsService = inject[EventLogDetailsService]
  private[this] val eventList = inject[EventListDisplayer]
  private[this] val xmlPretty = new scala.xml.PrettyPrinter(80, 2)
  
 
  
  def getLastEvents : Box[Seq[EventLog]] = {
    repos.getEventLogByCriteria(None, Some(1000), Some("id DESC")) 
  }
    
  def dispatch = { 
    case "display" => xml => getLastEvents match {
      case Full(seq) => display(seq,xml)
      case Empty => display(Seq(),xml)
      case f:Failure => 
        <div class="error">Error when trying to get last event logs. Error message was: {f.msg}</div>
    } 
  } 
  
  
  def display(events:Seq[EventLog], xml:NodeSeq) : NodeSeq  = {
    (
      "tbody *" #> ("tr" #> events.map { event => 
        ".logId *" #> event.id.getOrElse(0).toString &
        ".logDatetime *" #> DateFormaterService.getFormatedDate(event.creationDate) &
        ".logActor *" #> event.principal.name &
        ".logType *" #> event.eventType.serialize &
        ".logCategory *" #> S.?("event.log.category."+event.eventLogCategory.getClass().getSimpleName()) &
        ".logDescription *" #> eventList.displayDescription(event) &
        ".logDetails *" #> { 
        	if (event.details != <entry></entry> ) 
        	  SHtml.a(Text("Details"))(showEventsDetail(event.id.getOrElse(0), event.eventType.serialize)) 
        	else 
        	  NodeSeq.Empty
        	}
      })
     )(xml) ++ initJs
  }
  
  private[this] val gridName = "eventLogsGrid"
  private[this] val jsGridName = "oTable" + gridName
  private[this] val eventDetailPopupHtmlId = "eventDetailPopup" 
    
  private[this] def initJs : NodeSeq = Script(
    JsRaw("var %s;".format(jsGridName)) &
    OnLoad(
        JsRaw("""
          /* Event handler function */
          #table_var# = $('#%s').dataTable({
            "asStripClasses": [ 'color1', 'color2' ],
            "bAutoWidth": false,
            "bFilter" :true,
            "bPaginate" :true,
            "bLengthChange": false,
            "sPaginationType": "full_numbers",
            "oLanguage": {
              "sSearch": "Filter:"
            },
            "bJQueryUI": false,
            "aaSorting":[],
            "aoColumns": [
                { "sWidth": "30px" }
              , { "sWidth": "110px" }
              , { "sWidth": "110px" }
              , { "sWidth": "110px" }
              , { "sWidth": "100px" }
              , { "sWidth": "150px" }
              , { "sWidth": "80px" }
            ]
          });moveFilterAndFullPaginateArea('#%s');""".format(gridName,gridName).replaceAll("#table_var#",jsGridName)
        )
    )    
  )
    
  private[this] def showEventsDetail(id : Int, eventType : String) : JsCmd = {
    ( repos.getEventLog(id) match {
      case Full(event) =>
        SetHtml(eventDetailPopupHtmlId, (
            "#popupTitle" #> "Details for event %s".format(eventType) &
            "#popupContent" #>  eventList.displayDetails(event)
            )(popupContentDetail))
      case _ => 
        SetHtml(eventDetailPopupHtmlId, (
            "#popupTitle" #> "Error" &
            "#popupContent" #> "Could not fetch the event details for event %s".format(eventType)
            )(popupContentDetail))
    } ) & 
    JsRaw("createPopup('%s', 150, 400)".format(eventDetailPopupHtmlId))
    
    
  }
  

      
      
  private[this] val popupContentDetail = <div class="simplemodal-title">
      <h1><value id="popupTitle"/></h1>
      <hr/>
    </div>
    <div class="simplemodal-content">
      <br />
      <div id="popupContent"/>
	</div>
	<div class="simplemodal-bottom">
      <hr/>
      <p align="right">
        <button class="simplemodal-close" onClick="return false;">
          Close
        </button>
      </p>
    </div>
}
