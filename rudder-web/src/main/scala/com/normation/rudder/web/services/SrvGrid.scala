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

import com.normation.rudder.domain.nodes.NodeInfo

import com.normation.utils.Utils.isEmpty
import com.normation.inventory.domain.NodeId
import com.normation.inventory.ldap.core._
import LDAPConstants._
import bootstrap.liftweb.LiftSpringApplicationContext.inject

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
import com.normation.exceptions.TechnicalException
import net.liftweb.http.Templates

/**
 * Very much like the NodeGrid, but with the new WB and without ldap information
 * 
 * @author Nicolas CHARLES
 *
 */
object SrvGrid {
  val logger = LoggerFactory.getLogger(classOf[SrvGrid])
}

/**
 * Present a grid of server in a jQuery Datatable 
 * widget. 
 *
 * To use it:
 * - add the need js/css dependencies by adding the result
 *   of head() in the calling template head
 * - call the display(servers) method  
 */
class SrvGrid {
  
  private def templatePath = List("templates-hidden", "srv_grid")
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
  
  def jsVarNameForId(tableId:String) = "oTable" + tableId
  
  /**
   * Display and init the display for the list of server
   * @param servers : a SEQ of the WBSrv to be shown
   * @param tableId : the id of the table
   * @param columns : a list of supplementary column to add in the grid, 
   * where the _1 is the header, and the (server => NodeSeq)
   *    is the content of the column
   * @param aoColumns : the aoColumns field in the datatable for the extra columns
   * @param searchable : true if the table is searchable
   * @param paginate : true if the table should show pagination controls
   * @return
   */
  def displayAndInit(
      servers:Seq[NodeInfo], 
      tableId:String, 
      columns:Seq[(Node,NodeInfo => NodeSeq)]=Seq(), 
      aoColumns:String ="", 
      searchable : Boolean = true, 
      paginate : Boolean = true, 
      callback : String => JsCmd = x => Noop
   ) : NodeSeq = {
    display(servers, tableId, columns, aoColumns) ++
    Script(initJs(tableId, columns, aoColumns, searchable, paginate, callback))    
  }
  
  /*
   * Init Javascript for the table with ID 'tableId'
   * 
   */
  def initJs(tableId:String, columns:Seq[(Node,NodeInfo => NodeSeq)]=Seq(), aoColumns:String ="", searchable : Boolean, paginate : Boolean,callback : String => JsCmd) : JsCmd = {
      
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
            "asStripeClasses": [ 'color1', 'color2' ],
            "bAutoWidth": false,
            "bFilter" :true,
            "bPaginate" :%s,
            "bLengthChange": true,
            "sPaginationType": "full_numbers",
            "oLanguage": {
              "sSearch": ""
            },
            "bJQueryUI": true,
            "aaSorting": [[ 0, "asc" ]],
            "aoColumns": [ 
              { "sWidth": "180px" },
              { "sWidth": "300px" } %s
            ],
            "sDom": '<"dataTables_wrapper_top"fl>rt<"dataTables_wrapper_bottom"ip>'
          });
          $('.dataTables_filter input').attr("placeholder", "Search");
          """.format(tableId,paginate,aoColumns).replaceAll("#table_var#",jsVarNameForId(tableId))
        ) &
        
        initJsCallBack(tableId, callback)
    )
        
   }
  
  /**
   * Initialize JS callback bound to the servername item
   * You will have to do that for line added after table
   * initialization.
   */
  def initJsCallBack(tableId:String, callback : (String) => JsCmd) : JsCmd = {
      JsRaw("""$('td[name="serverName"]', #table_var#.fnGetNodes() ).each( function () {
              var td = this;
          $(this.parentNode).click( function () {
              var aPos = #table_var#.fnGetPosition( td );
              var aData = $(#table_var#.fnGetData( aPos[0] ));
              var node = $(aData[aPos[1]]);
              var id = node.attr("serverid");
              var jsid = node.attr("jsuuid");
              var ajaxParam = jsid + "|" + id;
              %s;
          } );
        })
      """.format(
          SHtml.ajaxCall(JsVar("ajaxParam"), x => callback(x))._2.toJsCmd).replaceAll("#table_var#",
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
  
  def display(servers:Seq[NodeInfo], tableId:String, columns:Seq[(Node,NodeInfo => NodeSeq)]=Seq(), aoColumns:String ="") : NodeSeq = {
    //bind the table
    <table id={tableId} cellspacing="0">{
    bind("servergrid",tableTemplate,
      "header" -> (columns flatMap { c => <th>{c._1}<span/></th> }),
      "lines" -> ( servers.flatMap { case s@NodeInfo(id,name,description, hostname, operatingSystem, ips, inventoryDate,pkey, agentsName, policyServerId, admin, creationDate, isBroken, isSystem, isPolicyServer) =>
        //build all table lines
        bind("line",chooseTemplate("servergrid","lines",tableTemplate),
          "name" -> <span class="hostnamecurs" jsuuid={id.value.replaceAll("-","")} serverid={id.value.toString} >{(if(isEmpty(name)) "(Missing name) " + id.value else hostname)}</span>,
          "fullos" -> operatingSystem,
          "other" -> (columns flatMap { c => <td>{c._2(s)}</td> })
        )
      })
    )}</table>
      <div class={tableId +"_pagination"}>
        <div id={tableId +"_paginate_area"}></div>
        <div id={tableId +"_filter_area"}></div>
      </div>
  }

}