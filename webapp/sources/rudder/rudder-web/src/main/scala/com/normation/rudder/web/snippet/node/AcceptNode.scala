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
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.eventlog._
import com.normation.rudder.domain.eventlog.AcceptNodeEventLog
import com.normation.rudder.domain.eventlog.RefuseNodeEventLog
import com.normation.rudder.domain.logger.TimingDebugLogger
import com.normation.rudder.domain.servers.Srv
import com.normation.rudder.web.ChooseTemplate
import com.normation.rudder.web.components.DateFormaterService
import com.normation.rudder.web.components.popup.ExpectedPolicyPopup
import com.normation.rudder.web.model.CurrentUser
import net.liftweb.common._
import net.liftweb.http._
import net.liftweb.http.js._
import net.liftweb.http.js.JE._
import net.liftweb.http.js.JsCmds._
import net.liftweb.json._
import net.liftweb.util.Helpers._
import org.joda.time.DateTime
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.userdetails.UserDetails
import scala.xml._

import com.normation.box._

/**
 * Check for server in the pending repository and propose to
 * accept or refuse them.
 *
 */
class AcceptNode extends Loggable {

  val newNodeManager       = RudderConfig.newNodeManager
  val rudderDit            = RudderConfig.rudderDit
  val serverGrid           = RudderConfig.nodeGrid
  val serverSummaryService = RudderConfig.nodeSummaryService

  val diffRepos            = RudderConfig.inventoryHistoryLogRepository
  val logRepository        = RudderConfig.eventLogRepository
  val acceptedNodesDit     = RudderConfig.acceptedNodesDit
  val pendingNodeDit       = RudderConfig.pendingNodesDit
  val uuidGen              = RudderConfig.stringUuidGenerator

  val gridHtmlId = "acceptNodeGrid"

  def authedUser = {
     SecurityContextHolder.getContext.getAuthentication.getPrincipal match {
      case u:UserDetails => EventActor(u.getUsername)
      case _ => logger.error("No authenticated user !")
                EventActor("Unknown user")
     }
  }

  def acceptTemplate = ChooseTemplate(
      List("templates-hidden", "Popup", "accept_new_server")
    , "accept_new_server-template"
  )

  def refuseTemplate = ChooseTemplate(
      List("templates-hidden", "Popup", "refuse_new_server")
    , "refuse_new_server-template"
  )

  /*
   * List all server that have there isAccpeted tag to pending.
   * For all servers, provides an Accept / Refuse link.
   *
   * On refuse, the server is banned (isAccepted = refused )
   * and it's moved to the Banned Node branch.
   * On accept, isAccepted = accepted
   */

  var errors : Option[String] = None

  def list(html:NodeSeq) :  NodeSeq =  {

    newNodeManager.listNewNodes match {
      case Empty => <div>Error, no server found</div>
      case f@Failure(_,_,_) => <div>Error while retrieving pending nodes list</div>
      case Full(seq) => display(html,seq)
    }
  }

  // Retrieve the list of selected server when submiting
  def nodeIdsFromClient() : Seq[NodeId] = {
    S.params("serverids").map(x => NodeId(x))
  }

  /**
   * Retrieve the last inventory for the selected server
   */
  def retrieveLastVersions(uuid : NodeId) : Option[DateTime] = {
    diffRepos.versions(uuid).toBox match {
      case Full(list) if (list.size > 0) => Some(list.head)
      case _ => None
    }
  }

  def addNodes(listNode : Seq[NodeId]) : Unit = {

    val modId = ModificationId(uuidGen.newUuid)
    //TODO : manage error message
    S.clearCurrentNotices
    listNode.foreach { id =>
      val now = System.currentTimeMillis
      val accept = newNodeManager.accept(id, modId, CurrentUser.actor)
      if(TimingDebugLogger.isDebugEnabled) {
        TimingDebugLogger.debug(s"Accepting node ${id.value}: ${System.currentTimeMillis - now}ms")
      }
      accept match {
      case f:Failure =>
        S.error(
          <span>
            {f.messageChain}
          </span>
        )
      case e:EmptyBox =>
        logger.error("Add new node '%s' lead to Failure.".format(id.value.toString), e)
        S.error(<span class="error">Error while accepting node(s).</span>)
      case Full(inventory) =>
        // TODO : this will probably move to the NewNodeManager, when we'll know
        // how we handle the user
        val version = retrieveLastVersions(id)
        version match {
          case Some(x) =>
            serverSummaryService.find(acceptedNodesDit,id) match {
              case Full(srvs) if (srvs.size==1) =>
                  val srv = srvs.head
                  val entry = AcceptNodeEventLog.fromInventoryLogDetails(
                      principal        = authedUser
                    , inventoryDetails = InventoryLogDetails(
                          nodeId           = srv.id
                        , inventoryVersion = x
                        , hostname         = srv.hostname
                        , fullOsName       = srv.osFullName
                        , actorIp          = S.containerRequest.map(_.remoteAddress).openOr("Unknown IP")
                      )
                  )

                  logRepository.saveEventLog(modId, entry).toBox match {
                      case Full(_) => logger.debug("Successfully added node '%s'".format(id.value.toString))
                      case _ => logger.warn("Node '%s'added, but the action couldn't be logged".format(id.value.toString))
                  }

              case _ => logger.error("Something bad happened while searching for node %s to log the acceptation, search %s".format(id.value.toString, id.value))
            }

          case None => logger.warn("Node '%s'added, but couldn't find it's inventory %s".format(id.value.toString, id.value))
        }
    } }

  }

  def refuseNodes(listNode : Seq[NodeId]) : Unit = {
    //TODO : manage error message
    S.clearCurrentNotices
    val modId = ModificationId(uuidGen.newUuid)
    listNode.foreach { id => newNodeManager.refuse(id, modId, CurrentUser.actor) match {
      case e:EmptyBox =>
        logger.error("Refuse node '%s' lead to Failure.".format(id.value.toString), e)
        S.error(<span class="error">Error while refusing node(s).</span>)
      case Full(srv) =>
        // TODO : this will probably move to the NewNodeManager, when we'll know
        // how we handle the user
        val version = retrieveLastVersions(srv.id)
        version match {
          case Some(x) =>
            val entry = RefuseNodeEventLog.fromInventoryLogDetails(
                principal        = authedUser
              , inventoryDetails = InventoryLogDetails(
                    nodeId           = srv.id
                  , inventoryVersion = x
                  , hostname         = srv.hostname
                  , fullOsName       = srv.osFullName
                  , actorIp          = S.containerRequest.map(_.remoteAddress).openOr("Unknown IP")
                )

            )

            logRepository.saveEventLog(modId, entry).toBox match {
              case Full(_) => logger.debug("Successfully refused node '%s'".format(id.value.toString))
              case _ => logger.warn("Node '%refused, but the action couldn't be logged".format(id.value.toString))
            }

          case None => logger.warn("Node '%s' refused, but couldn't find it's inventory %s".format(id.value.toString, id.value))
        }
      }
    }
  }

  /**
   * Display the details of the server to confirm the accept/refuse
   * s : the javascript selected list
   * template : the template that will be used (accept, or refuse)
   * popuId : the id of the popup
   */
  def details(jsonArrayOfIds:String, template : NodeSeq, popupId : String) : JsCmd = {
    implicit val formats = DefaultFormats
    val serverList = parse(jsonArrayOfIds).extract[List[String]].map(x => NodeId(x))

    if (serverList.size == 0) {
      Alert("You didn't select any nodes")
    } else {
      SetHtml("manageNewNode", listNode(serverList, template))  & OnLoad(
        JsRaw("""
          /* Set the table layout */
          $('#pendingNodeConfirm').dataTable({
            "asStripeClasses": [ 'color1', 'color2' ],
            "bAutoWidth": false,
            "bFilter" :true,
            "bLengthChange": true,
            "bPaginate": false,
            "bJQueryUI": true,
            "aaSorting": [[ 0, "asc" ]],
            "aoColumns": [
              { "sWidth": "180px" },
              { "sWidth": "300px" }
            ],
            "lengthMenu": [ [10, 25, 50, 100, 500, 1000, -1], [10, 25, 50, 100, 500, 1000, "All"] ],
            "pageLength": 25
          });"""
        ) & JsRaw(s"""
              createPopup("${popupId}");
        $$('#pendingNodeConfirm_info').remove();""")
        )
    }
  }

  /**
   * Display the list of selected server, and the accept/refuse button
   */

  def listNode(listNode : Seq[NodeId], template : NodeSeq) : NodeSeq = {

    val serverLine = {
      <tr>
       <td id="server_hostname"></td>
       <td id="server_os"></td>
      </tr>
    }

    def displayServerLine(srv : Srv) : NodeSeq = {
      ( "#server_hostname *" #> srv.hostname &
        "#server_os *"       #> srv.osFullName
      ) (serverLine)
    }

    serverSummaryService.find(pendingNodeDit,listNode:_*) match {
      case Full(servers) =>
        val lines : NodeSeq = servers.flatMap(displayServerLine)
        ("#server_lines" #> lines).apply(
          (
              "servergrid-accept" #>
                SHtml.submit("Accept", {
                  () => { addNodes(listNode) }
                  S.redirectTo(S.uri)
                }, ("class", "btn btn-success"))
            & "servergrid-refuse" #>
                SHtml.submit("Refuse", {
                  () => refuseNodes(listNode)
                  S.redirectTo(S.uri)
                }, ("class", "btn btn-danger"))
            &  "servergrid-close" #>
                 SHtml.ajaxButton("Cancel", {
                   () => JsRaw(" $('#confirmPopup').bsModal('hide');$('#refusePopup').bsModal('hide');") : JsCmd
                 }, ("class", "btn btn-default"))
          )(template)
        )
      case e:EmptyBox =>
        val error = e ?~! "An error occured when trying to get server details for displaying them in the popup"
        logger.debug(error.messageChain, e)
        NodeSeq.Empty
    }
  }

  /**
   * retrieve the list of all checked servers with JS
   * and then show the popup
   */
  def showConfirmPopup(template : NodeSeq, popupId : String) = {
    net.liftweb.http.js.JE.JsRaw("""
        var selectedNode = JSON.stringify($('input[name="serverids"]:checkbox:checked').map(function() {
          return $(this).val();
        }).get())
      """) &  SHtml.ajaxCall(JsVar("selectedNode"), details(_,template, popupId))._2
  }

  /**
   * Display the expected Directives for a machine
   */
  def showExpectedPolicyPopup(node : Srv) = {
    SetHtml("expectedPolicyZone", (new ExpectedPolicyPopup("expectedPolicyZone", node)).display ) &
    OnLoad(JsRaw("""createPopup("expectedPolicyPopup")""") )
  }

  def display(html:NodeSeq, nodes: Seq[Srv]) = {
    val servers = {
      serverGrid.displayAndInit (
          nodes
        , "acceptNodeGrid"
        , Seq( ( Text("Since")     , { e => Text(DateFormaterService.getFormatedDate(e.creationDate))})
             , ( Text("Directive") , { e => SHtml.ajaxButton(<span><i class="glyphicon glyphicon-search"></i></span>, { () =>  showExpectedPolicyPopup(e) }, ("class", "smallButton") )})
             , ( selectAll         , { e => <input type="checkbox" name="serverids" value={e.id.value.toString}/>  })
          )
        , """,{ "sWidth": "10%" },{ "sWidth": "11%", "bSortable":false },{ "sWidth": "2%", "bSortable":false }"""
        , true
      )
    }

    (
        "pending-servers" #> servers
      & "pending-accept" #>
          SHtml.ajaxButton(
              "Accept"
            , { () =>  showConfirmPopup(acceptTemplate, "confirmPopup") }
            , ("class", "btn btn-success") )
      & "pending-refuse" #>
          SHtml.ajaxButton(
              "Refuse"
            , { () => showConfirmPopup(refuseTemplate, "refusePopup" ) }
            , ("class", "btn btn-danger") )
      & "pending-errors" #> (errors match {
        case None => NodeSeq.Empty
        case Some(x) => <div>x</div>
      })
    )(html)
  }

  /**
   *
   * @return
   */
  val selectAll : Node =
    <input type="checkbox" id="selectAll" onClick="jqCheckAll('selectAll', 'serverids')"/>
}
