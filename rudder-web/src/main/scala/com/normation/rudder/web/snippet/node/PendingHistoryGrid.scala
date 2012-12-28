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

package com.normation.rudder.web.snippet.node


import com.normation.inventory.ldap.core.InventoryHistoryLogRepository
import com.normation.rudder.services.eventlog.{
  InventoryEventLogService, EventLogDetailsService
}
import com.normation.inventory.domain.NodeId
import com.normation.inventory.ldap.core._
import LDAPConstants._
import com.normation.rudder.domain.servers.Srv
import com.normation.rudder.domain.eventlog._
import com.normation.eventlog._
import com.normation.rudder.domain.eventlog._
import bootstrap.liftweb.LiftSpringApplicationContext.inject
import com.normation.rudder.web.services.DisplayNode
import com.normation.rudder.web.model.JsNodeId
import com.normation.rudder.web.components.DateFormaterService

import org.joda.time.DateTime
import org.joda.time.format._
import org.slf4j.LoggerFactory

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

import net.liftweb.json._
import JsonDSL._
import com.normation.exceptions.TechnicalException



object PendingHistoryGrid extends Loggable {

  val history           = inject[InventoryHistoryLogRepository]
  val logService        = inject[InventoryEventLogService]
  val logDetailsService = inject[EventLogDetailsService]
  
  def pendingHistoryTemplatePath = List("templates-hidden", "pending_history_grid")
  def template() =  Templates(pendingHistoryTemplatePath) match {
    case Empty | Failure(_,_,_) => 
      throw new TechnicalException("Template for pending history not found. I was looking for %s.html".format(pendingHistoryTemplatePath.mkString("/")))
    case Full(n) => n
  }
  
  def displayAndInit() : NodeSeq = {    
    logService.getInventoryEventLogs() match {
      case Empty => display(Seq[InventoryEventLog]()) ++ Script(initJs)
      case Full(x) => display(x) ++ Script(initJs)
      case _ => NodeSeq.Empty
    }
    
  }
  
  def jsVarNameForId() = "pendingNodeHistoryTable"
  
  def initJs() : JsCmd = {
    JsRaw("""
        var #table_var#;
        /* Formating function for row details */ 
        function fnFormatDetails ( id ) {
          var sOut = '<span id="'+id+'" class="sgridbph"/>';
          return sOut;
        }
      """.replaceAll("#table_var#",jsVarNameForId)) &  OnLoad(
        JsRaw("""
         #table_var# =  $('#pending_server_history').dataTable({
            "asStripeClasses": [ 'color1', 'color2' ],
            "bAutoWidth": false,
            "bFilter" :true,
            "bLengthChange": true,
            "bJQueryUI": true,
            "oLanguage": {
              "sSearch": ""
            },
            "aaSorting": [[ 0, "desc" ]],
            "sPaginationType": "full_numbers",
            "sDom": '<"dataTables_wrapper_top"fl>rt<"dataTables_wrapper_bottom"ip>'
          });
          $('.dataTables_filter input').attr("placeholder", "Search");            
          """.replaceAll("#table_var#",jsVarNameForId)
        ) & initJsCallBack
       )
  }
  def display(entries : Seq[EventLog]) : NodeSeq = {
    bind("pending_history", template,
       "lines" -> entries.flatMap{ x => 
         val jsuuid = Helpers.nextFuncName
         x match {
         case  x : RefuseNodeEventLog =>  
           logDetailsService.getRefuseNodeLogDetails(x.details) match {
             case Full(details) =>
               <tr class= "curspoint" jsuuid={jsuuid} serveruuid={details.nodeId.value} 
                     inventory={details.inventoryVersion.toString()}>
                 <td>{DateFormaterService.getFormatedDate(x.creationDate)}</td>
                 <td name="serverName">
                   {
                       details.hostname
                     }
                 </td>
                 <td>{details.fullOsName}</td>
                 <td>Refused</td>
                 <td>{x.principal.name}</td>
               </tr>
             case e:EmptyBox => 
               val error = (e ?~! "Error when getting refuse node details")
               logger.debug(error.messageChain, e)
               NodeSeq.Empty
           }
         case x : AcceptNodeEventLog =>  
           logDetailsService.getAcceptNodeLogDetails(x.details) match {
             case Full(details) =>
               <tr class="curspoint" jsuuid={jsuuid} serveruuid={details.nodeId.value} 
                     inventory={details.inventoryVersion.toString()}>
                 <td><span class="listopen"></span>{DateFormaterService.getFormatedDate(x.creationDate)}</td>
                 <td name="serverName">
                   {
                       details.hostname
                     }
                 </td>
                 <td>{details.fullOsName}</td>
                 <td>Accepted</td>
                 <td>{x.principal.name}</td>
               </tr>
             case e:EmptyBox => 
               val error = (e ?~! "Error when getting refuse node details")
               logger.debug(error.messageChain, e)
               NodeSeq.Empty
           }
         case x => 
           logger.error("I wanted a refuse node or accept node event, and got: " + x)
           NodeSeq.Empty
       }  }
    )
  }
  
   /**
   * Initialize JS callback bound to the server name 
   * You will have to do that for line added after table
   * initialization.
   */
  def initJsCallBack() : JsCmd = {
      JsRaw("""
          $(#table_var#.fnGetNodes()).each( function () {
                 $(this).click( function () {
                    var id = $(this).attr("serveruuid");
                    var inventory = $(this).attr("inventory");
                    var jsuuid = $(this).attr("jsuuid");
                    var opened = $(this).prop("open");

                    if (opened && opened.match("opened")) {
                      #table_var#.fnClose(this);
                      $(this).prop("open", "closed");
                      $(this).find("span.listclose").removeClass("listclose").addClass("listopen");
                    } else {
                      $(this).prop("open", "opened");
                      $(this).find("span.listopen").removeClass("listopen").addClass("listclose");
                      var ajaxParam = jsuuid + "|" + id + "|" + inventory;
                      #table_var#.fnOpen( this, fnFormatDetails(jsuuid), 'displayPastInventory' );
                      %s;
                    }
                  } );
                })
          """.format(
          SHtml.ajaxCall(JsVar("ajaxParam"), displayPastInventory _)._2.toJsCmd).replaceAll("#table_var#",
              jsVarNameForId))
  }
  
  
  def displayPastInventory(s : String) : JsCmd = {
    val arr = s.split("\\|")
    if (arr.length != 3) {
      Alert("Called ID is not valid: %s".format(s))
    } else {
      val jsuuid = arr(0)
      val id = NodeId(arr(1))
      val version = ISODateTimeFormat.dateTimeParser.parseDateTime(arr(2))
      history.get(id, version) match {
        case Failure(m,_,_) => Alert("Error while trying to display node history. Error message:" + m)
        case Empty => Alert("No history was retrieved for the chosen date")
        case Full(sm) => 
          SetHtml(
            jsuuid,
            DisplayNode.showPannedContent(sm.data, "hist")) & 
            DisplayNode.jsInit(sm.data.node.main.id,sm.data.node.softwareIds,"hist", Some("node_tabs"))
      }
    }
  }
  
}
