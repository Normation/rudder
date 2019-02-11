/*
*************************************************************************************
* Copyright 2011 Normation SAS
*************************************************************************************
*
* This file is part of Rudder.
*
* Rudder is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU General Public License version 3, the copyright holders add
* the following Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU General
* Public License version 3, when you create a Related Module, this
* Related Module is not considered as a part of the work and may be
* distributed under the license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* Rudder is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with Rudder.  If not, see <http://www.gnu.org/licenses/>.

*
*************************************************************************************
*/

package com.normation.rudder.web.snippet.node

import bootstrap.liftweb.RudderConfig
import com.normation.eventlog._
import com.normation.inventory.domain.NodeId
import com.normation.inventory.domain.RemovedInventory
import com.normation.rudder.domain.eventlog._
import com.normation.rudder.domain.eventlog.DeleteNodeEventLog
import com.normation.rudder.web.components.DateFormaterService
import com.normation.rudder.web.services.DisplayNode
import net.liftweb.common._
import net.liftweb.http._
import net.liftweb.http.js._
import net.liftweb.http.js.JE._
import net.liftweb.http.js.JsCmds._
import net.liftweb.util._
import net.liftweb.util.Helpers._
import org.joda.time.DateTime
import org.joda.time.format._
import scala.xml._

import com.normation.box._

object PendingHistoryGrid extends Loggable {

  val history           = RudderConfig.inventoryHistoryLogRepository
  val logService        = RudderConfig.inventoryEventLogService
  val logDetailsService = RudderConfig.eventLogDetailsService

  def pendingHistoryTemplatePath = List("templates-hidden", "pending_history_grid")
  def template() =  Templates(pendingHistoryTemplatePath) match {
    case Empty | Failure(_,_,_) =>
      throw new IllegalArgumentException("Template for pending history not found. I was looking for %s.html".format(pendingHistoryTemplatePath.mkString("/")))
    case Full(n) => n
  }

  def displayAndInit() : NodeSeq = {
    logService.getInventoryEventLogs() match {
      case Empty => display(Seq[InventoryEventLog]()) ++ Script(initJs())
      case Full(x) => val (deleted,entries) = x.partition((t:InventoryEventLog) => t.isInstanceOf[DeleteNodeEventLog])
      display(entries) ++ Script(initJs(deleted))
      case _ => NodeSeq.Empty
    }

  }

  def jsVarNameForId() = "pendingNodeHistoryTable"

  def initJs(entries : Seq[EventLog] = Seq()) : JsCmd = {
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
            "bStateSave": true,
                    "fnStateSave": function (oSettings, oData) {
                      localStorage.setItem( 'DataTables_pending_server_history', JSON.stringify(oData) );
                    },
                    "fnStateLoad": function (oSettings) {
                      return JSON.parse( localStorage.getItem('DataTables_pending_server_history') );
                    },
            "bJQueryUI": true,
            "oLanguage": {
              "sSearch": ""
            },
            "aaSorting": [[ 0, "desc" ]],
            "sPaginationType": "full_numbers",
            "sDom": '<"dataTables_wrapper_top"f>rt<"dataTables_wrapper_bottom"lip>'
          });
          $('.dataTables_filter input').attr("placeholder", "Filter");
          $("#new_servers_tab").tabs();
          """.replaceAll("#table_var#",jsVarNameForId)
        ) & initJsCallBack(entries)
       )
  }
  def display(entries : Seq[EventLog]) : NodeSeq = {

      val historyLine = {
        <tr class= "curspoint">
          <td><span class="listopen date"></span></td>
          <td class="name"></td>
          <td class="os"></td>
          <td class="state"></td>
          <td class="performer"></td>
        </tr>
      }

      def displayInventoryLogDetails (event : EventLog, details : InventoryLogDetails, status : String) = {
         val jsuuid = Helpers.nextFuncName
        ( "tr [jsuuid]"     #> jsuuid &
          "tr [serveruuid]" #> details.nodeId.value &
          "tr [kind]"       #> status.toLowerCase &
          "tr [inventory]"  #> details.inventoryVersion.toString() &
          ".date *"      #> DateFormaterService.getFormatedDate(event.creationDate)&
          ".name *"      #>  details.hostname&
          ".os *"        #> details.fullOsName&
          ".state *"     #> status.capitalize &
          ".performer *" #> event.principal.name
        ) (historyLine)

      }

    val lines : NodeSeq = entries.flatMap{
      case ev: RefuseNodeEventLog =>
        logDetailsService.getRefuseNodeLogDetails(ev.details) match {
          case Full(details) => displayInventoryLogDetails(ev,details,"refused")
          case eb: EmptyBox =>
            val error = (eb ?~! "Error when getting refuse node details")
            logger.debug(error.messageChain, eb)
            NodeSeq.Empty
        }
      case ev: AcceptNodeEventLog =>
        logDetailsService.getAcceptNodeLogDetails(ev.details) match {
          case Full(details) => displayInventoryLogDetails(ev,details,"accepted")
          case eb: EmptyBox =>
            val error = (eb ?~! "Error when getting refuse node details")
            logger.debug(error.messageChain, eb)
            NodeSeq.Empty
        }
      case ev =>
        logger.error("I wanted a refuse node or accept node event, and got: " + ev)
        NodeSeq.Empty
    }
    ("#history_lines" #> lines) apply (template)

  }

   /**
   * Initialize JS callback bound to the server name
   * You will have to do that for line added after table
   * initialization.
   */
  def initJsCallBack(entries : Seq[EventLog]) : JsCmd = {
    val eventWithDetails = entries.flatMap(event => logDetailsService.getDeleteNodeLogDetails(event.details).map((event,_)))
    // Group the events by node id, then drop the event details. Set default Map value to an empty Seq
    val deletedNodes = eventWithDetails.groupBy(_._2.nodeId).mapValues(_.map(_._1)).withDefaultValue(Seq())

      JsRaw("""
          $(#table_var#.fnGetNodes()).each( function () {
                 $(this).click( function () {
                    var id = $(this).attr("serveruuid");
                    var inventory = $(this).attr("inventory");
                    var jsuuid = $(this).attr("jsuuid");
                    var kind = $(this).attr("kind");
                    var opened = $(this).prop("open");

                    if (opened && opened.match("opened")) {
                      #table_var#.fnClose(this);
                      $(this).prop("open", "closed");
                      $(this).find("span.listclose").removeClass("listclose").addClass("listopen");
                    } else {
                      $(this).prop("open", "opened");
                      $(this).find("span.listopen").removeClass("listopen").addClass("listclose");
                      var ajaxParam = jsuuid + "|" + id + "|" + inventory + "|" + kind;
                      #table_var#.fnOpen( this, fnFormatDetails(jsuuid), 'displayPastInventory' );
                      %s;
                    }
                  } );
                })
          """.format(
          SHtml.ajaxCall(JsVar("ajaxParam"), displayPastInventory(deletedNodes) _)._2.toJsCmd).replaceAll("#table_var#",
              jsVarNameForId))
  }

  def displayPastInventory(deletedNodes : Map[NodeId, Seq[EventLog]])(s : String) : JsCmd = {

    val arr = s.split("\\|")
    if (arr.length != 4) {
      Alert("Called ID is not valid: %s".format(s))
    } else {
      val jsuuid = arr(0)
      val id = NodeId(arr(1))
      val version = ISODateTimeFormat.dateTimeParser.parseDateTime(arr(2))
      val isAcceptLine = arr(3) == "accepted"
      history.get(id, version).toBox match {
        case Failure(m,_,_) => Alert("Error while trying to display node history. Error message:" + m)
        case Empty => Alert("No history was retrieved for the chosen date")
        case Full(sm) =>
          SetHtml(
            jsuuid,
            ( if (isAcceptLine)
                displayIfDeleted(id,version,deletedNodes)
              else
                NodeSeq.Empty
            ) ++
            DisplayNode.showPannedContent(None, sm.data, RemovedInventory, "hist")) &
            DisplayNode.jsInit(sm.data.node.main.id,sm.data.node.softwareIds,"hist")
      }
    }
  }

  def displayIfDeleted (id:NodeId,lastInventoryDate:DateTime, deletedNodes : Map[NodeId,Seq[EventLog]]) = {
    // only take events that could have delete that inventory, as we set the default value to an empty sequence, there's no null here with the apply on the map
    val effectiveEvents = deletedNodes(id).filter(_.creationDate.isAfter(lastInventoryDate))
    // sort those events by date, to take the closer deletion date from the inventory date (head of the list)
    effectiveEvents.sortWith( (ev1,ev2) => ev1.creationDate.isBefore(ev2.creationDate)).headOption match {
      case Some(deleted) =>
        <div style="padding: 10px 15px 0">
          <i class="fa fa-exclamation-triangle warnicon" aria-hidden="true"></i>
          <h3> {"This node was deleted on %s by %s".format(DateFormaterService.getFormatedDate(deleted.creationDate),deleted.principal.name)}</h3>
        </div>
      case None => NodeSeq.Empty
  }
  }
}
