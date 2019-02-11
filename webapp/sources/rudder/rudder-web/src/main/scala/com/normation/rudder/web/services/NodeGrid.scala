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

package com.normation.rudder.web.services

import com.normation.utils.Utils.isEmpty
import com.normation.inventory.domain.{NodeId,InventoryStatus}
import com.normation.inventory.ldap.core._
import com.normation.rudder.domain.servers.Srv
import org.slf4j.LoggerFactory
import scala.xml._
import net.liftweb.common._
import net.liftweb.http._
import net.liftweb.util._
import Helpers._
import net.liftweb.http.js._
import JsCmds._
import JE._
import net.liftweb.json._
import com.normation.rudder.domain.servers.Srv
import com.normation.utils.HashcodeCaching
import com.normation.rudder.services.nodes.NodeInfoService
import com.normation.appconfig.ReadConfigService
import com.normation.rudder.web.ChooseTemplate

import com.normation.box._

object NodeGrid {
  val logger = LoggerFactory.getLogger(classOf[NodeGrid])
}

/**
 * a case class used to pass the JSON that contains id of
 * the node we want args for
 */
case class JsonArg(jsid:String, id:String, status:String) extends HashcodeCaching

/**
 * Present a grid of server in a jQuery Datatable
 * widget.
 *
 * To use it:
 * - add the need js/css dependencies by adding the result
 *   of head() in the calling template head
 * - call the display(servers) method
 */
class NodeGrid(
    getNodeAndMachine: LDAPFullInventoryRepository
  , nodeInfoService  : NodeInfoService
  , configService    : ReadConfigService
) extends Loggable {

  private def tableTemplate = ChooseTemplate(
      List("templates-hidden", "server_grid")
    , "servergrid-table"
  )

  def displayAndInit(
      servers:Seq[Srv],
      tableId:String,
      columns:Seq[(Node,Srv => NodeSeq)]=Seq(),
      aoColumns:String ="",
      searchable : Boolean = true,
      paginate : Boolean = true
   ) : NodeSeq = {
    display(servers, tableId, columns, aoColumns) ++
    Script(initJs(tableId, columns, aoColumns, searchable, paginate))
  }

  def jsVarNameForId(tableId:String) = "oTable" + tableId

  /*
   * Init Javascript for the table with ID
   * 'tableId'
   */
  def initJs(tableId:String, columns:Seq[(Node,Srv => NodeSeq)]=Seq(), aoColumns:String ="", searchable : Boolean, paginate : Boolean) : JsCmd = {

    JsRaw(s"""
        var ${jsVarNameForId(tableId)};
        /* Formating function for row details */
        function fnFormatDetails ( id ) {
          var sOut = '<span id="'+id+'" class="sgridbph"/>';
          return sOut;
        }
      """
    ) & OnLoad(

        JsRaw(s"""
          /* Event handler function */
          ${jsVarNameForId(tableId)} = $$('#${tableId}').dataTable({
            "asStripeClasses": [ 'color1', 'color2' ],
            "bAutoWidth": false,
            "bFilter" : ${searchable},
            "bPaginate" : ${paginate},
            "bLengthChange": true,
            "bStateSave": true,
                    "fnStateSave": function (oSettings, oData) {
                      localStorage.setItem( 'DataTables_${tableId}', JSON.stringify(oData) );
                    },
                    "fnStateLoad": function (oSettings) {
                      return JSON.parse( localStorage.getItem('DataTables_${tableId}') );
                    },
            "bJQueryUI": true,
            "aaSorting": [[ 0, "asc" ]],
            "sPaginationType": "full_numbers",
            "oLanguage": {
              "sSearch": ""
            },
            "aoColumns": [
              { "sWidth": "30%" },
              { "sWidth": "27%" },
              { "sWidth": "20%" } ${aoColumns}
            ],
            "lengthMenu": [ [10, 25, 50, 100, 500, 1000, -1], [10, 25, 50, 100, 500, 1000, "All"] ],
            "pageLength": 25 ,
            "sDom": '<"dataTables_wrapper_top"f>rt<"dataTables_wrapper_bottom"lip>'
          });
            """
        ) &

        initJsCallBack(tableId)
    )

   }

  /**
   * Initialize JS callback bound to the servername item
   * You will have to do that for line added after table
   * initialization.
   */
  def initJsCallBack(tableId:String) : JsCmd = {
      JsRaw(s"""$$( ${jsVarNameForId(tableId)}.fnGetNodes() ).each( function () {
          $$(this).click( function (event) {
            var source = event.target || event.srcElement;
            event.stopPropagation();
            if(!( $$(source).is("button") || $$(source).is("input") )){
              var opened = $$(this).prop("open");
              if (opened && opened.match("opened")) {
                $$(this).prop("open", "closed");
                $$(this).find("span.listclose").removeClass("listclose").addClass("listopen");
                ${jsVarNameForId(tableId)}.fnClose(this);
              } else {
                $$(this).prop("open", "opened");
                $$(this).find("span.listopen").removeClass("listopen").addClass("listclose");
                var jsid = $$(this).attr("jsuuid");
                var node = $$(this).attr("nodeid");
                var ajaxParam = JSON.stringify({"jsid":jsid , "id":$$(this).attr("nodeid") , "status":$$(this).attr("nodeStatus")});
                ${jsVarNameForId(tableId)}.fnOpen( this, fnFormatDetails(jsid), 'details' );
                ${SHtml.ajaxCall(JsVar("ajaxParam"), details _)._2.toJsCmd}
              }
            }
          } );
        } )
      """
     )
  }

  /**
   * Build the HTML grid of server, with all its row
   * initilialized.
   * This method does not initialize grid's Javascript,
   * use <code>displayAndInit</code> for that.
   *
   * @parameter : servers
   *    the list of servers to display
   * @parameter : columns
   *    a list of supplementary column to add in the grid,
   *    where the _1 is the header, and the (server => NodeSeq)
   *    is the content of the column
   * @parameter aoColumns : the javascript for the datatable
   */

  def display(servers:Seq[Srv], tableId:String, columns:Seq[(Node,Srv => NodeSeq)]=Seq(), aoColumns:String ="") : NodeSeq = {
    //bind the table
    val headers : NodeSeq = columns flatMap { c => <th>{c._1}</th> }

    def serverLine (server: Srv) : NodeSeq = {
        (".hostname *" #> {(if(isEmpty(server.hostname)) "(Missing host name) " + server.id.value else server.hostname)} &
         ".fullos *" #> server.osFullName &
         ".ips *" #> ( (server.ips.flatMap{ ip => <div class="ip">{ip}</div> }):NodeSeq ) & // TODO : enhance this
         ".other" #> ( (columns flatMap { c => <td style="overflow:hidden">{c._2(server)}</td> }):NodeSeq ) &
         ".nodetr [jsuuid]" #> {server.id.value.replaceAll("-","")} &
         ".nodetr [nodeid]" #> {server.id.value} &
         ".nodetr [nodestatus]" #> {server.status.name}
         )(datatableXml)
    }

    val lines : NodeSeq = servers.flatMap{serverLine _}

    ( "table [id]" #> tableId &
      "#header *+" #> headers &
      "#lines *" #> lines
    ).apply(tableTemplate)
  }
  private[this] val datatableXml = {
    <tr class="nodetr curspoint" jsuuid="id" nodeid="nodeid" nodestatus="status">
      <td class="curspoint"><span class="hostname listopen"></span></td>
      <td class="fullos curspoint"></td>
      <td class="ips curspoint"></td>
      <td class="other"></td>
    </tr>
  }
  /**
   * We expect a json string with paramaters:
   * jsid: javascript id
   * id: the nodeid
   * status: the node status (pending, accecpted)
   */
  private def details(jsonArg:String) : JsCmd = {
    import Box._
    implicit val formats = DefaultFormats

    ( for {
      json   <- tryo(parse(jsonArg)) ?~! "Error when trying to parse argument for node"
      arg    <- tryo(json.extract[JsonArg])
      status : InventoryStatus <- Box(InventoryStatus(arg.status))
      nodeId =  NodeId(arg.id)
      sm     <- getNodeAndMachine.get(nodeId, status).notOptional(s"Error when trying to find inventory for node '${nodeId.value}'").toBox
      nodeAndGlobalMode <- {
        nodeInfoService.getNodeInfo(nodeId) match {
          case Full(Some(node)) =>
            configService.rudder_global_policy_mode().toBox match {
              case Full(mode)    => Full(Some((node,mode)))
              case eb : EmptyBox =>
                val fail = eb ?~! s" Could not get global policy mode when getting node '${nodeId}' details"
                fail
            }
          case eb : EmptyBox =>
            val fail = eb ?~! s" Error when getting node '${nodeId}' details"
            fail
          case Full(None) => Full(None)
        }
      }
    } yield (nodeId, sm, arg.jsid, status, nodeAndGlobalMode) ) match {
      case Full((nodeId, sm, jsid, status, nodeAndGlobalMode)) =>
        // Node may not be available, so we look for it outside the for comprehension
        SetHtml(jsid, DisplayNode.showPannedContent(nodeAndGlobalMode, sm, status)) &
        DisplayNode.jsInit(sm.node.main.id, sm.node.softwareIds, "")
      case e:EmptyBox =>
        logger.debug((e ?~! "error").messageChain)
        Alert("Called id is not valid: %s".format(jsonArg))
    }
  }
}
