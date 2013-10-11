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
import scala.xml._
import net.liftweb.common._
import net.liftweb.http._
import net.liftweb.util._
import Helpers._
import net.liftweb.http.js._
import JsCmds._
import JE._
import net.liftweb.http.SHtml._
import com.normation.exceptions.TechnicalException
import net.liftweb.http.Templates
import com.normation.rudder.repository.ReportsRepository
import bootstrap.liftweb.RudderConfig
import com.normation.rudder.web.components.DateFormaterService
import com.normation.rudder.reports.execution.RoReportsExecutionRepository

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
class SrvGrid(
  roReportExecutionsRepository : RoReportsExecutionRepository
) {

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
      servers:Seq[NodeInfo]
    , tableId:String
    , callback : String => JsCmd = x => Noop
    , isGroupPage : Boolean = false
   ) : NodeSeq = {
    display(servers, tableId) ++
    Script(initJs(tableId, callback,isGroupPage))
  }

  /*
   * Init Javascript for the table with ID 'tableId'
   *
   */
  def initJs(tableId:String,callback : String => JsCmd,isGroupPage:Boolean) : JsCmd = {

    val sizes = if (isGroupPage)
        Seq(210,60,60,60,40,115) //545px width
      else
        Seq(225,125,100,100,75,125) // 750px width
    JsRaw("""
        var #table_var#;
        /* Formating function for row details */
        function fnFormatDetails ( id ) {
          var sOut = '<span id="'+id+'" class="sgridbph"/>';
          return sOut;
        }
      """.replaceAll("#table_var#",jsVarNameForId(tableId))
    ) & OnLoad(

        JsRaw(s"""
          /* Event handler function */
          ${jsVarNameForId(tableId)} = $$('#${tableId}').dataTable({
            "asStripeClasses": [ 'color1', 'color2' ],
            "bAutoWidth": false,
            "bFilter"  : true,
            "bPaginate": true,
            "bLengthChange": true,
            "sPaginationType": "full_numbers",
            "oLanguage": {
              "sSearch": ""
            },
            "bJQueryUI": true,
            "aaSorting": [[ 0, "asc" ]],
            "aoColumns": [${sizes.map(size => s""" { "sWidth": "${size}" }""").mkString(",")}],
            "sDom": '<"dataTables_wrapper_top"fl>rt<"dataTables_wrapper_bottom"ip>'
          });
          $$('.dataTables_filter input').attr("placeholder", "Search");
          """
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
      JsRaw("""$("#serverName", #table_var#.fnGetNodes() ).each( function () {
              var td = $(this);
          td.click( function () {
              var id = td.attr("serverid");
              var jsid = td.attr("jsuuid");
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
   */

  def display(servers:Seq[NodeInfo], tableId:String) : NodeSeq = {
    //bind the table
    <table id={tableId} cellspacing="0">
    <thead>
      <tr class="head">
        <th>Node name</th>
        <th>Machine type</th>
        <th>OS name</th>
        <th>OS version</th>
        <th>OS SP</th>
        <th>Last seen</th>
      </tr>
    </thead>
    <tbody>
    {servers.map { server =>

      (("#serverName *") #> ( if (isEmpty(server.name))
                                s"(Missing name)  ${server.id.value}"
                              else
                                server.hostname ) &
      ("#machineType *") #> server.machineType &
      ("#osFullName *")  #> S.?(s"os.name.${server.osName}") &
      ("#osVersion *")   #> server.osVersion &
      ("#servicePack *") #> server.servicePack.getOrElse("N/A") &
      ("#lastReport *")  #> { roReportExecutionsRepository.getNodeLastExecution(server.id) match {
                                case Full(exec) =>
                                  exec.map(report =>  DateFormaterService.getFormatedDate(report.date)).getOrElse("Never")
                                case eb : EmptyBox => "Error While fetching node executions"
                            } }
      )(lineXml(server.id.value))
    } }
    </tbody>
      </table>
      <div class={tableId +"_pagination"}>
        <div id={tableId +"_paginate_area"}></div>
        <div id={tableId +"_filter_area"}></div>
      </div>
  }

  private[this] def lineXml(serverId:String) =
    <tr>
      <td id="serverName" class="hostnamecurs" jsuuid={serverId.replaceAll("-","")} serverid={serverId}/>
      <td id="machineType"/>
      <td id="osFullName" />
      <td id="osVersion"  />
      <td id="servicePack"/>
      <td id="lastReport" />
   </tr>
}