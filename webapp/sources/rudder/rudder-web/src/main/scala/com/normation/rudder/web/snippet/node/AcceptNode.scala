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
import com.normation.box.*
import com.normation.eventlog.ModificationId
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.logger.TimingDebugLogger
import com.normation.rudder.domain.servers.Srv
import com.normation.rudder.facts.nodes.ChangeContext
import com.normation.rudder.facts.nodes.CoreNodeFact
import com.normation.rudder.facts.nodes.QueryContext
import com.normation.rudder.facts.nodes.SelectNodeStatus
import com.normation.rudder.users.CurrentUser
import com.normation.rudder.web.ChooseTemplate
import com.normation.rudder.web.components.popup.ExpectedPolicyPopup
import com.normation.utils.DateFormaterService
import java.time.Instant
import net.liftweb.common.*
import net.liftweb.http.*
import net.liftweb.http.js.*
import net.liftweb.http.js.JE.*
import net.liftweb.http.js.JsCmds.*
import net.liftweb.util.Helpers.*
import org.apache.commons.text.StringEscapeUtils
import scala.xml.*
import zio.json.*

/**
 * Check for server in the pending repository and propose to
 * accept or refuse them.
 *
 */
class AcceptNode extends DispatchSnippet with Loggable {
  import AcceptNodeJson.*

  val newNodeManager     = RudderConfig.newNodeManager
  val rudderDit          = RudderConfig.rudderDit
  val serverGrid         = RudderConfig.nodeGrid
  val nodeFactRepository = RudderConfig.nodeFactRepository

  val historyRepos     = RudderConfig.inventoryHistoryJdbcRepository
  val logRepository    = RudderConfig.eventLogRepository
  val acceptedNodesDit = RudderConfig.acceptedNodesDit
  val uuidGen          = RudderConfig.stringUuidGenerator

  def acceptTemplate: NodeSeq = ChooseTemplate(
    List("templates-hidden", "Popup", "accept_new_server"),
    "accept_new_server-template"
  )

  def refuseTemplate: NodeSeq = ChooseTemplate(
    List("templates-hidden", "Popup", "refuse_new_server"),
    "refuse_new_server-template"
  )

  override def dispatch: DispatchIt = {
    implicit val qc: QueryContext = CurrentUser.queryContext // bug https://issues.rudder.io/issues/26605

    { case "list" => listAll(_) }
  }

  /*
   * List all server that have there isAccpeted tag to pending.
   * For all servers, provides an Accept / Refuse link.
   *
   * On refuse, the server is banned (isAccepted = refused )
   * and it's moved to the Banned Node branch.
   * On accept, isAccepted = accepted
   */

  var errors: Option[String] = None

  /*
   * This is the main (and only) entry point of the snippet,
   * drawing the pending nodes table.
   */
  private def listAll(html: NodeSeq)(implicit qc: QueryContext): NodeSeq = {

    newNodeManager.listNewNodes().toBox match {
      case Empty                => <div>Error, no server found</div>
      case f @ Failure(_, _, _) => <div>Error while retrieving pending nodes list</div>
      case Full(seq)            => display(html, seq)
    }
  }

  def addNodes(listNode: Seq[NodeId]): Unit = {

    val modId = ModificationId(uuidGen.newUuid)
    // TODO : manage error message
    S.clearCurrentNotices
    listNode.foreach { id =>
      implicit val cc: ChangeContext = {
        ChangeContext(
          modId,
          CurrentUser.actor,
          Instant.now(),
          None,
          S.request.map(_.remoteAddr).toOption,
          CurrentUser.nodePerms
        )
      }
      val now    = System.currentTimeMillis
      val accept =
        newNodeManager.accept(id).toBox
      if (TimingDebugLogger.isDebugEnabled) {
        TimingDebugLogger.debug(s"Accepting node ${id.value}: ${System.currentTimeMillis - now}ms")
      }
      accept match {
        case f:     Failure  =>
          S.error(
            <div class="alert alert-danger">
              <i class ="fa fa-warning"></i>
              <strong>{f.messageChain}</strong>
            </div>
          )
        case empty: EmptyBox =>
          val errorMsg = s"Add new node '$id.value' lead to Failure."
          val e        = empty ?~! errorMsg
          JsRaw(s"""createErrorNotification("${e.messageChain}")""")
          logger.error(errorMsg, e)
        case Full(_) =>
          logger.debug(s"Successfully added node '${id.value}'")
      }
    }

  }

  def refuseNodes(listNode: Seq[NodeId]): Unit = {
    // TODO : manage error message
    S.clearCurrentNotices
    val modId = ModificationId(uuidGen.newUuid)
    listNode.foreach { id =>
      newNodeManager
        .refuse(id)(using
          ChangeContext(
            modId,
            CurrentUser.actor,
            Instant.now(),
            None,
            S.request.map(_.remoteAddr).toOption,
            CurrentUser.nodePerms
          )
        )
        .toBox match {
        case empty: EmptyBox =>
          val errorMsg = s"Refuse node '${id.value}' lead to Failure."
          val e        = empty ?~! errorMsg
          JsRaw(s"""createErrorNotification("${e.messageChain}")""")
          logger.error(errorMsg, e)
        case Full(_) =>
          logger.debug(s"Successfully refused node '${id.value}'")
      }
    }
  }

  /**
   * Display the details of the server to confirm the accept/refuse
   * s : the javascript selected list
   * template : the template that will be used (accept, or refuse)
   * popuId : the id of the popup
   */
  def details(jsonArrayOfIds: String, template: NodeSeq, popupId: String): JsCmd = {
    val serverList = jsonArrayOfIds.fromJson[List[NodeId]].getOrElse(List.empty)

    if (serverList.isEmpty) {
      Alert("You didn't select any nodes")
    } else {
      SetHtml("manageNewNode", listNode(serverList, template)) & OnLoad(
        JsRaw("""
          /* Set the table layout */
          $('#pendingNodeConfirm').dataTable({
            "asStripeClasses": [ 'color1', 'color2' ],
            "bAutoWidth": false,
            "searching": false,
            "bLengthChange": true,
            "bPaginate": false,
            "bJQueryUI": false,
            "aaSorting": [[ 0, "asc" ]],
            "aoColumns": [
              { "sWidth": "180px" },
              { "sWidth": "300px" }
            ],
            "lengthMenu": [ [10, 25, 50, 100, 500, 1000, -1], [10, 25, 50, 100, 500, 1000, "All"] ],
            "pageLength": 25
          });""") & JsRaw(s"""
              initBsModal("${StringEscapeUtils.escapeEcmaScript(popupId)}");
        $$('#pendingNodeConfirm_info').remove();""") // JsRaw ok, const or escaped
      )
    }
  }

  /**
   * Display the list of selected server, and the accept/refuse button
   */

  def listNode(listNode: Seq[NodeId], template: NodeSeq): NodeSeq = {

    val serverLine = {
      <tr>
       <td id="server_hostname"></td>
       <td id="server_os"></td>
      </tr>
    }

    def displayServerLine(srv: CoreNodeFact): NodeSeq = {
      ("#server_hostname *" #> srv.fqdn &
      "#server_os *" #> srv.os.fullName)(serverLine)
    }

    nodeFactRepository
      .getAll()(using CurrentUser.queryContext, SelectNodeStatus.Pending)
      .map(_.collect { case (id, n) if listNode.contains(id) => n })
      .toBox match {
      case Full(servers) =>
        val lines: NodeSeq = servers.flatMap(displayServerLine).toSeq
        ("#server_lines" #> lines).apply(
          (
            "servergrid-accept" #>
            SHtml.submit(
              "Accept",
              { () =>
                {
                  addNodes(listNode)
                  S.redirectTo(S.uri)
                }
              },
              ("class", "btn btn-success")
            )
            & "servergrid-refuse" #>
            SHtml.submit(
              "Refuse",
              { () =>
                {
                  refuseNodes(listNode)
                  S.redirectTo(S.uri)
                }
              },
              ("class", "btn btn-danger")
            )
          )(template)
        )
      case e: EmptyBox =>
        val error = e ?~! "An error occurred when trying to get server details for displaying them in the popup"
        logger.debug(error.messageChain, e)
        NodeSeq.Empty
    }
  }

  /**
   * retrieve the list of all checked servers with JS
   * and then show the popup
   */
  def showConfirmPopup(template: NodeSeq, popupId: String): JsCmd = {
    net.liftweb.http.js.JE.JsRaw("""
        var selectedNode = JSON.stringify($('input[name="serverids"]:checkbox:checked').map(function() {
          return $(this).val();
        }).get())
      """) & SHtml.ajaxCall(JsVar("selectedNode"), details(_, template, popupId))._2 // JsRaw ok, const
  }

  /**
   * Display the expected Directives for a machine
   */
  def showExpectedPolicyPopup(node: Srv): JsCmd = {
    SetHtml("expectedPolicyZone", (new ExpectedPolicyPopup("expectedPolicyZone", node)).display) &
    OnLoad(JsRaw("""initBsModal("expectedPolicyPopup")""")) // JsRaw ok, const
  }

  def display(html: NodeSeq, nodes: Seq[CoreNodeFact])(implicit qc: QueryContext): NodeSeq = {
    val servers = {
      serverGrid.displayAndInit(
        nodes.map(_.toSrv),
        "acceptNodeGrid",
        Seq(
          (Text("Since"), { e => Text(DateFormaterService.getDisplayDate(e.creationDate)) }),
          (
            Text("Directive"),
            { e =>
              SHtml.ajaxButton(
                <span><i class="fa fa-search"></i></span>,
                () => showExpectedPolicyPopup(e),
                ("class", "btn btn-default")
              )
            }
          ),
          (selectAll, { e => <input type="checkbox" name="serverids" value={e.id.value.toString}/> })
        ),
        """,{ "sWidth": "10%" },{ "sWidth": "11%", "bSortable":false },{ "sWidth": "2%", "bSortable":false }""",
        searchable = true,
        paginate = true
      )
    }

    (
      "pending-servers" #> servers
      & "pending-accept" #>
      SHtml.ajaxButton("Accept", () => showConfirmPopup(acceptTemplate, "confirmPopup"), ("class", "btn btn-success"))
      & "pending-refuse" #>
      SHtml.ajaxButton("Refuse", () => showConfirmPopup(refuseTemplate, "refusePopup"), ("class", "btn btn-danger"))
      & "pending-errors" #> (errors match {
        case None    => NodeSeq.Empty
        case Some(x) =>
          <div>x</div>
      })
    )(html)
  }

  /**
   *
   * @return
   */
  val selectAll: Node =
    <input type="checkbox" id="selectAll" onclick="jqCheckAll('selectAll', 'serverids')"/>
}

object AcceptNodeJson {
  implicit val decodeNodeId: JsonDecoder[NodeId] = JsonDecoder[String].map(NodeId.apply)
}
