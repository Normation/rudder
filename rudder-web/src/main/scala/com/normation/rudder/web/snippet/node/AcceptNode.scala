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

import com.normation.rudder.domain.servers.Srv
import com.normation.rudder.services.servers.NodeSummaryService
import com.normation.rudder.services.servers.NewNodeManager
import com.normation.inventory.ldap.core.{InventoryHistoryLogRepository,InventoryDit}

import com.normation.inventory.domain.NodeId

import com.normation.inventory.ldap.core.LDAPConstants._
import com.normation.ldap.sdk._
import BuildFilter._

import org.slf4j.LoggerFactory
import net.liftweb.json._
import JsonDSL._

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

import org.joda.time.DateTime
import com.normation.rudder.domain.RudderDit
import com.normation.rudder.web.services.NodeGrid
import com.normation.eventlog.EventLogService
import bootstrap.liftweb.LiftSpringApplicationContext.inject
import com.normation.rudder.domain.eventlog._
import com.normation.utils.User
import com.normation.rudder.web.components.popup.ExpectedPolicyPopup
import com.normation.exceptions.TechnicalException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.userdetails.UserDetails
import com.normation.rudder.web.components.DateFormaterService
import com.normation.eventlog.EventActor
import com.normation.rudder.web.model.CurrentUser
import com.normation.rudder.domain.eventlog.{
  AcceptNodeEventLog, RefuseNodeEventLog
}

/**
 * Check for server in the pending repository and propose to 
 * accept or refuse them.
 * 
 */
class AcceptNode {
  val logger = LoggerFactory.getLogger(classOf[AcceptNode])
  val newNodeManager = inject[NewNodeManager]
  val ldap = inject[LDAPConnectionProvider]
  val rudderDit = inject[RudderDit]
  val serverGrid = inject[NodeGrid]
  val serverSummaryService = inject[NodeSummaryService]
  val diffRepos = inject[InventoryHistoryLogRepository]
  val logService = inject[EventLogService]
  val acceptedNodesDit = inject[InventoryDit]("acceptedNodesDit")
  val pendingNodeDit = inject[InventoryDit]("pendingNodesDit")
  
  val gridHtmlId = "acceptNodeGrid"

  val authedUser = {
     SecurityContextHolder.getContext.getAuthentication.getPrincipal match {
      case u:UserDetails => EventActor(u.getUsername)
      case _ => logger.error("No authenticated user !")
                EventActor("Unknown user")
     }
  }
     
  def acceptTemplatePath = List("templates-hidden", "Popup", "accept_new_server")
  def template() =  Templates(acceptTemplatePath) match {
    case Empty | Failure(_,_,_) => 
      throw new TechnicalException("Template for server grid not found. I was looking for %s.html".format(acceptTemplatePath.mkString("/")))
    case Full(n) => n
  }
  
  def acceptTemplate = chooseTemplate("accept_new_server","template",template)
  
  
  def refuseTemplatePath = List("templates-hidden", "Popup", "refuse_new_server")
  def templateRefuse() =  Templates(refuseTemplatePath) match {
    case Empty | Failure(_,_,_) => 
      throw new TechnicalException("Template for server grid not found. I was looking for %s.html".format(refuseTemplatePath.mkString("/")))
    case Full(n) => n
  }

  def refuseTemplate = chooseTemplate("refuse_new_server","template",templateRefuse)
  
  
  def head() : NodeSeq = 
    <head>{ 
      serverGrid.head ++ Script(OnLoad(JsRaw("""
          $("button", ".bottomButton").button();
      """) ) )
    }</head>

  /*
   * List all server that have there isAccpeted tag to pending.
   * For all servers, provides an Accept / Refuse link.
   * 
   * On refuse, the server is banned (isAccepted = refused )
   * and it's moved to the Banned Node branch.
   * On accept, isAccepted = accepted
   */
  def list(html:NodeSeq) :  NodeSeq =  {
    var errors : Option[String] = None
    
    // Retrieve the list of selected server when submiting
    def nodeIdsFromClient() : Seq[NodeId] = {
      S.params("serverids").map(x => NodeId(x))
    }
    
    /**
     * Retrieve the last inventory for the selected server
     */
    def retrieveLastVersions(uuid : NodeId) : Option[DateTime] = {
      diffRepos.versions(uuid) match {
        case Full(list) if (list.size > 0) => Some(list.head)
        case _ => None
      }
    }
     
    def addNodes(listNode : Seq[NodeId]) : Unit = {
      //TODO : manage error message
      S.clearCurrentNotices
      listNode.foreach { id => newNodeManager.accept(id, CurrentUser.getActor) match {
        case f:Failure => 
          S.error(
            <span class="error">
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
                    
                    logService.saveEventLog(entry) match {
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
      listNode.foreach { id => newNodeManager.refuse(id, CurrentUser.getActor) match {
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
            
              logService.saveEventLog(entry) match {
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
              "asStripClasses": [ 'color1', 'color2' ],
              "bAutoWidth": false,
              "bFilter" :false,
              "bLengthChange": false,
              "bPaginate": false,
              "bJQueryUI": false,
              "aaSorting": [[ 0, "asc" ]],
              "aoColumns": [ 
                { "sWidth": "180px" },
                { "sWidth": "300px" }
              ]
            });"""
          ) & JsRaw("""
                createPopup("#popupId#",300,660);
          $('#pendingNodeConfirm_info').remove();""".replaceAll("#popupId#", popupId))
          )
      }
    }
    
    /**
     * Display the list of selected server, and the accept/refuse button
     */
    def listNode(listNode : Seq[NodeId], template : NodeSeq) : NodeSeq = {
        serverSummaryService.find(pendingNodeDit,listNode:_*) match {
          case Full(servers) => 
            bind("servergrid",template, 
              "lines" -> servers.flatMap { case s@Srv(id,status, hostname,ostype, osname, fullos, ips, _) =>
                 bind("line",chooseTemplate("servergrid","lines",template),
                    "os" -> fullos,
                    "hostname" -> hostname,
                    "ips" -> (ips.flatMap{ ip => <div class="ip">{ip}</div> })  // TODO : enhance this
                  ) 
              },
              "accept" -> SHtml.submit("Accept", { 
                () => { addNodes(listNode) }
                S.redirectTo(S.uri)
              })
              ,
              "refuse" -> SHtml.submit("Refuse", { 
                () => refuseNodes(listNode)
                 S.redirectTo(S.uri)
              }, ("class", "red")),
              "close" ->SHtml.ajaxButton("Cancel", { 
                () => JsRaw(" $.modal.close();") : JsCmd
              }) 
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
  def showExpectedPolicyPopup(nodeId : NodeId) = {
    
    SetHtml("expectedPolicyZone", (new ExpectedPolicyPopup("expectedPolicyZone", nodeId)).display ) & 
    OnLoad(JsRaw("""createPopup("expectedPolicyPopup",300,660)
      """) )
  }
  
    newNodeManager.listNewNodes match {
      case Empty => <div>Error, no server found</div>
      case f@Failure(_,_,_) => <div>Error while retriving server to confirm</div>
      case Full(seq) => bind("pending",html,
      "servers" -> serverGrid.displayAndInit(seq,"acceptNodeGrid",
        Seq( 
            (Text("Since"),
                   {e => Text(DateFormaterService.getFormatedDate(e.creationDate))}),
            (Text("Directive"), 
                  { e => SHtml.ajaxButton(<img src="/images/icPolicies.jpg"/>, { 
                      () =>  showExpectedPolicyPopup(e.id)
                    }, ("class", "smallButton")
                   )
                  }
               ),
              (Text(""), { e => 
                  <input type="checkbox" name="serverids" value={e.id.value.toString}/> 
                })
              
        ),         
        """,{ "sWidth": "60px" },{ "sWidth": "70px", "bSortable":false },{ "sWidth": "15px", "bSortable":false }"""
        ,true
      ),
      "accept" -> {if (seq.size > 0 ) { SHtml.ajaxButton("Accept into Rudder", {
        () =>  showConfirmPopup(acceptTemplate, "confirmPopup")
      }) % ("style", "width:170px")} else NodeSeq.Empty},
      "refuse" -> {if (seq.size > 0 ) { SHtml.ajaxButton("Refuse", {
        () => showConfirmPopup(refuseTemplate, "refusePopup")
      }) } else NodeSeq.Empty},
      "errors" -> (errors match {
        case None => NodeSeq.Empty
        case Some(x) => <div>x</div>
      }),
      "selectall" -> {if (seq.size > 0 ) {selectAll} else NodeSeq.Empty}
      )
    }
  }
  
  /**
   * 
   * @return
   */
  val selectAll : NodeSeq =
    <div class="checkbox">
      <p>
      Select/deselect all <input type="checkbox" id="selectAll" onClick="jqCheckAll('selectAll', 'serverids')"/>
      </p>
      </div>
}


