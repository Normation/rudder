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

import com.unboundid.ldap.sdk.DN
import com.normation.ldap.sdk.LDAPConnectionProvider
import com.normation.utils.Utils.isEmpty
import com.normation.inventory.domain.{NodeId,InventoryStatus}
import com.normation.inventory.ldap.core._
import LDAPConstants._
import com.normation.rudder.domain.servers.Srv
import com.normation.inventory.services.core.FullInventoryRepository
import bootstrap.liftweb.LiftSpringApplicationContext.inject
import org.slf4j.LoggerFactory
import scala.xml._
import net.liftweb.common._
import net.liftweb.http._
import net.liftweb.util._
import Helpers._
import net.liftweb.http.js._
import JsCmds._
import JE._
import net.liftweb.http.SHtml._
import net.liftweb.json._
import NodeGrid._
import com.unboundid.ldif.LDIFRecord
import com.normation.exceptions.TechnicalException
import net.liftweb.http.Templates
import com.normation.rudder.domain.servers.Srv
import com.normation.utils.HashcodeCaching

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
class NodeGrid(getNodeAndMachine:LDAPFullInventoryRepository) extends Loggable {
  
  private def templatePath = List("templates-hidden", "server_grid")
  private def template() =  Templates(templatePath) match {
    case Empty | Failure(_,_,_) => 
      throw new TechnicalException("Template for server grid not found. I was looking for %s.html".format(templatePath.mkString("/")))
    case Full(n) => n
  }
  
  private def headTemplate = chooseTemplate("servergrid","head",template)
  private def tableTemplate = chooseTemplate("servergrid","table",template)
  
  /*
   * All JS/CSS needed to have datatable working
   */
  def head() : NodeSeq = headTemplate ++ DisplayNode.head
  
  
  def displayAndInit(
      servers:Seq[Srv], 
      tableId:String, 
      columns:Seq[(Node,Srv => NodeSeq)]=Seq(), 
      aoColumns:String ="", 
      searchable : Boolean = true, 
      paginate : Boolean = false
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
      
    JsRaw("""
        var #table_var#;
        /* Formating function for row details */
        function fnFormatDetails ( id ) {
          var sOut = '<span id="'+id+'" class="sgridbph"/>';
          return sOut;
        }
      """.replaceAll("#table_var#",jsVarNameForId(tableId))
    ) & OnLoad(
        
        JsRaw("""
          /* Event handler function */
          #table_var# = $('#%s').dataTable({
            "asStripClasses": [ 'color1', 'color2' ],
            "bAutoWidth": false,
            "bFilter" :%s,
            "bPaginate" :%s,
            "bLengthChange": false,
            "bJQueryUI": false,
            "aaSorting": [[ 0, "asc" ]],
            "aoColumns": [ 
              { "sWidth": "20%%" },
              { "sWidth": "25%%" },
              { "sWidth": "15%%" } %s
            ]
          });moveFilterAndPaginateArea('#%s');""".format(tableId,searchable,paginate,aoColumns,tableId).replaceAll("#table_var#",jsVarNameForId(tableId))
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
      JsRaw("""$('tr.curspoint', #table_var#.fnGetNodes() ).each( function () {
          $(this).click( function () {
            var nTr = this.parentNode;
            var opened = jQuery(nTr).prop("open");
            if (opened && opened.match("opened")) {
              jQuery(nTr).prop("open", "closed");
              jQuery(nTr).find("span.listclose").removeClass("listclose").addClass("listopen");
              #table_var#.fnClose(nTr);
            } else {
              jQuery(nTr).prop("open", "opened");
              jQuery(nTr).find("span.listopen").removeClass("listopen").addClass("listclose");
              var jsid = jQuery(nTr).attr("jsuuid");
              var node = jQuery(nTr).attr("nodeid");
              var ajaxParam = JSON.stringify({"jsid":jsid , "id":jQuery(nTr).attr("nodeid") , "status":jQuery(nTr).attr("nodeStatus")});
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
    <table id={tableId} class="fixedlayout" cellspacing="0">{
    bind("servergrid",tableTemplate,
      "header" -> (columns flatMap { c => <th>{c._1}<span/></th> }),
      "lines" -> ( servers.flatMap { case s@Srv(id,status, hostname,ostype,osname,osFullName,ips,creationDate) =>
        //build all table lines
        
        (".hostname *" #> {(if(isEmpty(hostname)) "(Missing host name) " + id.value else hostname)} &
         ".fullos *" #> osFullName &
         ".ips *" #> (ips.flatMap{ ip => <div class="ip">{ip}</div> }) & // TODO : enhance this
         ".other" #> (columns flatMap { c => <td style="overflow:hidden">{c._2(s)}</td> }) &
         ".nodetr [jsuuid]" #> {id.value.replaceAll("-","")} &
         ".nodetr [nodeid]" #> {id.value} &
         ".nodetr [nodestatus]" #> {status.name}
         )(datatableXml)
      })
    )}</table>  
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
      json <- tryo(parse(jsonArg)) ?~! "Error when trying to parse argument for node"
      arg <- tryo(json.extract[JsonArg])
      status <- Box(InventoryStatus(arg.status))
      sm <- getNodeAndMachine.get(NodeId(arg.id),status)
    } yield (sm,arg.jsid) ) match {
      case Full((sm,jsid)) => 
        SetHtml(jsid, DisplayNode.showPannedContent(sm)) &
        DisplayNode.jsInit(sm.node.main.id, sm.node.softwareIds, "", Some("node_tabs"))
      case e:EmptyBox => 
        logger.debug((e ?~! "error").messageChain)
        Alert("Called id is not valid: %s".format(jsonArg))
    }
  }
}

