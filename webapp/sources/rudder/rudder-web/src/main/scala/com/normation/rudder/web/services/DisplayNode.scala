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

package com.normation.rudder.web
package services

import model.JsNodeId
import com.normation.inventory.domain._
import com.normation.rudder.web.components.DateFormaterService
import scala.xml._
import net.liftweb.common._
import net.liftweb.http._
import net.liftweb.util._
import Helpers._
import net.liftweb.http.js._
import JsCmds._
import JE.{JsRaw, JsVar, JsArray, Str}
import org.joda.time.DateTime
import com.normation.rudder.web.model.CurrentUser
import com.normation.rudder.batch.AutomaticStartDeployment
import com.normation.eventlog.ModificationId
import bootstrap.liftweb.RudderConfig
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.domain.policies.PolicyModeOverrides._
import com.normation.rudder.domain.policies.GlobalPolicyMode
import com.normation.rudder.hooks.HookReturnCode.Interrupt
import com.normation.rudder.hooks.HookReturnCode

import com.normation.box._

/**
 * A service used to display details about a server
 * inventory with tabs like:
 * [general][software][network][file system]
 *
 * Use it by calling:
 * # head if not yet called in that page
 * # show(nodeId) : NodeSeq
 *    where you want to display node information
 * # jsInit(nodeId) : Cmd
 *    to init javascript for it
 */
object DisplayNode extends Loggable {

  private[this] val getSoftwareService   = RudderConfig.readOnlySoftwareDAO
  private[this] val removeNodeService    = RudderConfig.removeNodeService
  private[this] val asyncDeploymentAgent = RudderConfig.asyncDeploymentAgent
  private[this] val uuidGen              = RudderConfig.stringUuidGenerator
  private[this] val nodeInfoService      = RudderConfig.nodeInfoService
  private[this] val linkUtil             = RudderConfig.linkUtil
  private[this] val deleteNodePopupHtmlId = "deleteNodePopupHtmlId"
  private[this] val errorPopupHtmlId = "errorPopupHtmlId"
  private[this] val successPopupHtmlId = "successPopupHtmlId"

  private def loadSoftware(jsId:JsNodeId, softIds:Seq[SoftwareUuid])(nodeId:String):JsCmd = {
    (for {
      seq <- getSoftwareService.getSoftware(softIds)
      gridDataId = htmlId(jsId,"soft_grid_data_")
      gridId = "soft"
    } yield SetExp(JsVar(gridDataId),JsArray(seq.map { x => JsArray(
        Str(x.name.getOrElse("")),
        Str(x.version.map(_.value).getOrElse("")),
        Str(x.description.getOrElse(""))
      )}:_*) ) & JsRaw(s"""
          $$('#${htmlId(jsId,gridId+"_")}').dataTable({
            "aaData":${gridDataId},
            "bJQueryUI": true,
            "bPaginate": true,
            "bRetrieve": true,
            "bLengthChange": true,
            "sPaginationType": "full_numbers",
            "asStripeClasses": [ 'color1', 'color2' ] ,
            "oLanguage": {
              "sSearch": ""
            },
            "bLengthChange": true,
            "bStateSave": true,
                    "fnStateSave": function (oSettings, oData) {
                      localStorage.setItem( 'DataTables_${gridId}', JSON.stringify(oData) );
                    },
                    "fnStateLoad": function (oSettings) {
                      return JSON.parse( localStorage.getItem('DataTables_${gridId}') );
                    },
            "bAutoWidth": false,
            "aoColumns": [ {"sWidth": "200px"},{"sWidth": "150px"},{"sWidth": "350px"}],
            "sDom": '<"dataTables_wrapper_top"f>rt<"dataTables_wrapper_bottom"lip>',
            "lengthMenu": [ [10, 25, 50, 100, 500, 1000, -1], [10, 25, 50, 100, 500, 1000, "All"] ],
            "pageLength": 25
        });
        $$('.dataTables_filter input').attr("placeholder", "Filter");
            """)
    ).toBox match {
      case Empty => Alert("No software found for that server")
      case Failure(m,_,_) => Alert("Error when trying to fetch software. Reported message: "+m)
      case Full(js) => js
    }
  }

  def jsInit(nodeId:NodeId, softIds:Seq[SoftwareUuid], salt:String=""):JsCmd = {
    val jsId = JsNodeId(nodeId,salt)
    val detailsId = htmlId(jsId,"details_")
    val softGridDataId = htmlId(jsId,"soft_grid_data_")
    val softPanelId = htmlId(jsId,"sd_soft_")
    val eltIdswidth = List( ("process",List("50","50","70","85","120","50","100","850"),1),("var",List("200","800"),0))
    val eltIds = List( "vm", "fs", "net","bios", "controllers", "memories", "ports", "processors", "slots", "sounds", "storages", "videos")

    JsRaw("var "+softGridDataId +"= null") &
    OnLoad(
      JsRaw("$('#"+detailsId+"').tabs()") &
      { eltIds.map { i =>
          JsRaw(s"""
              $$('#${htmlId(jsId,i+"_")}').dataTable({
                "bJQueryUI": true,
                "bRetrieve": true,
                "bFilter": true,
                "asStripeClasses": [ 'color1', 'color2' ],
                "oLanguage": {
                  "sSearch": ""
                },
                "bLengthChange": true,
                "bStateSave": true,
                    "fnStateSave": function (oSettings, oData) {
                      localStorage.setItem( 'DataTables_${i}', JSON.stringify(oData) );
                    },
                    "fnStateLoad": function (oSettings) {
                      return JSON.parse( localStorage.getItem('DataTables_${i}') );
                    },
                "sPaginationType": "full_numbers",
                "bPaginate": true,
                "bAutoWidth": false,
                "bInfo":true,
                "sDom": '<"dataTables_wrapper_top"f>rt<"dataTables_wrapper_bottom"lip>',
                "lengthMenu": [ [10, 25, 50, 100, 500, 1000, -1], [10, 25, 50, 100, 500, 1000, "All"] ],
                "pageLength": 25
              });

              $$('.dataTables_filter input').attr("placeholder", "Filter");
          | """.stripMargin('|')):JsCmd
        }.reduceLeft( (i,acc) => acc & i )
      } &
      { eltIdswidth.map { case (id,columns,sorting) =>
          JsRaw(s"""
              $$('#${htmlId(jsId,id+"_")}').dataTable({
                "bJQueryUI": true,
                "bRetrieve": true,
                "sPaginationType": "full_numbers",
                "bFilter": true,
                "asStripeClasses": [ 'color1', 'color2' ],
                "bPaginate": true,
                "aoColumns": ${columns.map(col => s"{'sWidth': '${col}px'}").mkString("[",",","]")} ,
                "aaSorting": [[ ${sorting}, "asc" ]],
                "oLanguage": {
                  "sSearch": ""
                },
                "bLengthChange": true,
                "bStateSave": true,
                    "fnStateSave": function (oSettings, oData) {
                      localStorage.setItem( 'DataTables_${id}', JSON.stringify(oData) );
                    },
                    "fnStateLoad": function (oSettings) {
                      return JSON.parse( localStorage.getItem('DataTables_${id}') );
                    },
                "bAutoWidth": false,
                "bInfo":true,
                "sDom": '<"dataTables_wrapper_top"f>rt<"dataTables_wrapper_bottom"lip>',
                "lengthMenu": [ [10, 25, 50, 100, 500, 1000, -1], [10, 25, 50, 100, 500, 1000, "All"] ],
                "pageLength": 25
              });
              $$('.dataTables_filter input').attr("placeholder", "Filter");
           """) : JsCmd
        }.reduceLeft( (i,acc) => acc & i )
      } &
      // for the software tab, we check for the panel id, and the firstChild id
      // if the firstChild.id == softGridId, then it hasn't been loaded, otherwise it is softGridId_wrapper
      JsRaw(s"""
        $$("#${detailsId}").on( "tabsactivate", function(event, ui) {
          if(ui.newPanel.attr('id')== '${softPanelId}' ){
            ${  SHtml.ajaxCall(JsRaw("'"+nodeId.value+"'"), loadSoftware(jsId, softIds) )._2.toJsCmd}
          }
        });
        """)
    )
  }

  /**
   * Show details about the server in a tabbed fashion if
   * the server exists, display an error message if the
   * server is not found or if a problem occurred when fetching it
   *
   * showExtraFields : if true, then everything is shown, otherwise, the extrafileds are not in the main tabs.
   * To show then, look at showExtraHeader
   *
   * Salt is a string added to every used id.
   * It useful only if you have several DisplayNode element on a single page.
   */
  def show(sm:FullInventory, showExtraFields : Boolean = true, salt:String = "") : NodeSeq = {
    val jsId = JsNodeId(sm.node.main.id,salt)
    val mainTabDeclaration : List[NodeSeq] =
      <li><a href={htmlId_#(jsId,"sd_bios_")}>BIOS</a></li> ::
      <li><a href={htmlId_#(jsId,"sd_controllers_")}>Controllers</a></li> ::
      <li><a href={htmlId_#(jsId,"sd_memories_")}>Memory</a></li> ::
      <li><a href={htmlId_#(jsId,"sd_ports_")}>Ports</a></li> ::
      <li><a href={htmlId_#(jsId,"sd_processors_")}>Processors</a></li> ::
      <li><a href={htmlId_#(jsId,"sd_slots_")}>Slots</a></li> ::
      <li><a href={htmlId_#(jsId,"sd_sounds_")}>Sound</a></li> ::
      <li><a href={htmlId_#(jsId,"sd_storages_")}>Storage</a></li> ::
      <li><a href={htmlId_#(jsId,"sd_videos_")}>Video</a></li> ::
      Nil

    val tabContent =
      { if (showExtraFields) displayTabFilesystems(jsId, sm) else Nil } ::
      { if (showExtraFields) displayTabNetworks(jsId, sm) else Nil } ::
      { if (showExtraFields) displayTabSoftware(jsId) else Nil } ::
      displayTabBios(jsId, sm) ::
      displayTabControllers(jsId, sm) ::
      displayTabMemories(jsId, sm) ::
      displayTabPorts(jsId, sm) ::
      displayTabProcessors(jsId, sm) ::
      displayTabSlots(jsId, sm) ::
      displayTabSounds(jsId, sm) ::
      displayTabStorages(jsId, sm) ::
      displayTabVideos(jsId, sm) ::
      Nil

      val tabId = htmlId(jsId,"hardware_details_")
      <div id={tabId} class="sInventory ui-tabs-vertical">
        <ul>{mainTabDeclaration}</ul>
        {tabContent.flatten}
      </div> ++ Script(OnLoad(JsRaw(s"$$('#${tabId}').tabs()")))
  }

  /**
  * show the extra tabs header part
  */
  def showExtraHeader(sm:FullInventory, salt:String = "") : NodeSeq = {
    val jsId = JsNodeId(sm.node.main.id,salt)
    <xml:group>
    <li><a href={htmlId_#(jsId,"sd_fs_")}>File systems</a></li>
    <li><a href={htmlId_#(jsId,"sd_net_")}>Network interfaces</a></li>
    <li><a href={htmlId_#(jsId,"sd_soft_")}>Software</a></li>
    <li><a href={htmlId_#(jsId,"sd_var_")}>Environment</a></li>
    <li><a href={htmlId_#(jsId,"sd_process_")}>Processes</a></li>
    <li><a href={htmlId_#(jsId,"sd_vm_")}>Virtual machines</a></li>
    <li><a href={htmlId_#(jsId,"sd_props_")}>Properties</a></li>
    </xml:group>
  }

  /**
  * show the extra part
  * If there is no node available (pending inventory), there is nothing to show
  */
  def showExtraContent(node: Option[NodeInfo], sm: FullInventory, salt:String = "") : NodeSeq = {
    val jsId = JsNodeId(sm.node.main.id,salt)
    displayTabFilesystems(jsId, sm) ++
    displayTabNetworks(jsId, sm) ++
    displayTabVariable(jsId, sm) ++
    displayTabProcess(jsId, sm) ++
    displayTabVM(jsId, sm) ++
    node.map(displayTabProperties(jsId, _)).getOrElse(Nil) ++
    displayTabSoftware(jsId)
  }

  /**
   * Show the details in a panned version, with Node Summary, Inventory, Network, Software
   * Should be used with jsInit(dn:String, softIds:Seq[SoftwareUuid], salt:String="")
   */
  def showPannedContent(
      nodeAndGlobalMode: Option[(NodeInfo,GlobalPolicyMode)]
    , sm               : FullInventory
    , inventoryStatus  : InventoryStatus
    , salt             : String = ""
  ) : NodeSeq = {
    val jsId = JsNodeId(sm.node.main.id,salt)
    val detailsId = htmlId(jsId,"details_")
    <div id={detailsId} class="tabs">
      <ul>
        <li><a href={htmlId_#(jsId,"node_summary_")}>Summary</a></li>
        <li><a href={htmlId_#(jsId,"node_inventory_")}>Hardware</a></li>
        {showExtraHeader(sm, salt)}
       </ul>
       <div id="node_inventory">
         <div id={htmlId(jsId,"node_inventory_")}>
           {show(sm, false, "")}
         </div>
       </div>
       {showExtraContent(nodeAndGlobalMode.map(_._1), sm, salt)}

       <div id={htmlId(jsId,"node_summary_")}>
         {showNodeDetails(sm, nodeAndGlobalMode, None, inventoryStatus, salt)}
       </div>
    </div>
  }

  // mimic the content of server_details/ShowNodeDetailsFromNode
  def showNodeDetails(
      sm                 : FullInventory
    , nodeAndGlobalMode  : Option[(NodeInfo,GlobalPolicyMode)]
    , creationDate       : Option[DateTime]
    , inventoryStatus    : InventoryStatus
    , salt               : String = ""
    , isDisplayingInPopup: Boolean = false
  ) : NodeSeq = {
    val nodePolicyMode = nodeAndGlobalMode match {
      case Some((node,globalMode)) =>
        Some((globalMode.overridable,node.policyMode) match {
            case (Always, Some(mode)) =>
              (mode,"<p>This mode is an override applied to this node. You can change it in the <i><b>node settings</b></i>.</p>")
            case (Always,None) =>
              val expl = """<p>This mode is the globally defined default. You can change it in <i><b>settings</b></i>.</p><p>You can also override it on this node in the <i><b>node's settings</b></i>.</p>"""
              (globalMode.mode, expl)
            case (Unoverridable,_) =>
              (globalMode.mode, "<p>This mode is the globally defined default. You can change it in <i><b>Settings</b></i>.</p>")
          }
        )

      case None =>
        None
    }

    val deleteButton : NodeSeq= {
      sm.node.main.status match {
        case AcceptedInventory =>
          <div>
              <div id={deleteNodePopupHtmlId}  class="modal fade" data-keyboard="true" tabindex="-1" />
              <div id={errorPopupHtmlId}  class="modal fade" data-keyboard="true" tabindex="-1" />
              <div id={successPopupHtmlId}  class="modal fade" data-keyboard="true" tabindex="-1" />
          </div>
          <lift:authz role="node_write">
            {
              if(!isRootNode(sm.node.main.id)) {
                <div>
                  <div class="col-xs-12">
                    { showDeleteButton(sm.node.main.id) }
                  </div>
                </div>
              } else {NodeSeq.Empty}
            }
            </lift:authz>
        case _ => NodeSeq.Empty
      }
    }

     <div  id="nodeDetails" >
     <h3> Node characteristics</h3>
      <h4 class="tablemargin">General</h4>

        <div class="tablepadding">
          {/* style "inline-block" is for selection by triple-click of the whole content in firefox, need something else for chrome */}
          <b>Hostname:</b> <p style="display:inline-block">{sm.node.main.hostname}</p><br/>
          <b>Rudder node ID:</b> <p style="display:inline-block">{sm.node.main.id.value}</p><br/>
          <b>Machine type:</b> {displayMachineType(sm.machine)}<br/>
          <b>Manufacturer:</b> {sm.machine.flatMap(x => x.manufacturer).map(x => x.name).getOrElse("-")}<br/>
          <b>Total physical memory (RAM):</b> {sm.node.ram.map( _.toStringMo).getOrElse("-")}<br/>
          <b>Total swap space:</b> {sm.node.swap.map( _.toStringMo).getOrElse("-")}<br/>
          <b>System Serial Number:</b> {sm.machine.flatMap(x => x.systemSerialNumber).getOrElse("-")}<br/>
          <b>Time Zone:</b> {sm.node.timezone.map(x =>
              if(x.name.toLowerCase == "utc") "UTC" else s"${x.name} (UTC ${x.offset})"
            ).getOrElse("unknown")}<br/>
        </div>

      <h4 class="tablemargin">Operating system details</h4>
        <div class="tablepadding">
          <b>Operating System:</b> {sm.node.main.osDetails.fullName}<br/>
          <b>Operating System Type:</b> {sm.node.main.osDetails.os.kernelName}<br/>
          <b>Operating System Name:</b> {S.?("os.name."+sm.node.main.osDetails.os.name)}<br/>
          <b>Operating System Version:</b> {sm.node.main.osDetails.version.value}<br/>
          <b>Operating System Service Pack:</b> {sm.node.main.osDetails.servicePack.getOrElse("None")}<br/>
          <b>Operating System Architecture Description:</b> {sm.node.archDescription.getOrElse("None")}<br/>
        </div>

      <h4 class="tablemargin">Rudder information</h4>
        <div class="tablepadding">
         { nodeAndGlobalMode match {
           case Some((n, _)) => <b>Rudder node state: </b><span class="rudder-label label-sm label-state">{getNodeState(n.state)}</span><br/>
           case None         => NodeSeq.Empty
         } }
         { nodePolicyMode match {
           case None => NodeSeq.Empty
           case Some((mode,explanation)) =>
            <b>Node policy mode:</b><span id="badge-apm"></span><br/>  ++
            Script(OnLoad(JsRaw(s"""
              $$('#badge-apm').append(createBadgeAgentPolicyMode('node',"${mode}","${explanation}"));
              $$('.rudder-label').bsTooltip();
            """)))
         } }
          { displayServerRole(sm, inventoryStatus) }
          <b>Inventory date (node local time):</b>  {sm.node.inventoryDate.map(DateFormaterService.getFormatedDate(_)).getOrElse("Unknown")}<br/>
          <b>Date inventory last received:</b>  {sm.node.receiveDate.map(DateFormaterService.getFormatedDate(_)).getOrElse("Unknown")}<br/>
          {creationDate.map { creation =>
            <xml:group><b>Date first accepted in Rudder:</b> {DateFormaterService.getFormatedDate(creation)}<br/></xml:group>
          }.getOrElse(NodeSeq.Empty) }
          <b>Rudder agent version:</b> {sm.node.agents.map(_.version.map(_.value)).headOption.flatten.getOrElse("Not found")
          }<br/>
          <b>Agent name:</b> {sm.node.agents.map(_.agentType.displayName).mkString(";")}<br/>
          { sm.machine.map( _.id.value).map( machineId =>
              <div>
                <b>Machine ID:</b> {machineId}
              </div>
            ).getOrElse(<span class="error">Machine Information are missing for that node</span>)
          }<br/>
          { displayPolicyServerInfos(sm) }<br/>
          {
            sm.node.agents.headOption match {
              case Some(agent) =>
                val checked = (sm.node.main.status, sm.node.main.keyStatus) match {
                  case (AcceptedInventory, CertifiedKey) => <span>
                                                              <span class="glyphicon glyphicon-ok text-success tooltipable" title="" tooltipid={s"tooltip-key-${sm.node.main.id.value}"}></span>
                                                              <span class="tooltipContent" id={s"tooltip-key-${sm.node.main.id.value}"}>
                                                                Inventories for this Node must be signed with this key
                                                              </span>
                                                            </span>
                  case (AcceptedInventory, UndefinedKey) => <span>
                                                              <span class="glyphicon glyphicon-ok tooltipable" title="" tooltipid={s"tooltip-key-${sm.node.main.id.value}"}></span>
                                                              <span class="tooltipContent" id={s"tooltip-key-${sm.node.main.id.value}"}>
                                                                Inventories for this Node are not signed
                                                              </span>
                                                            </span>
                  case _ => NodeSeq.Empty
                }
                val nodeId      = sm.node.main.id
                val publicKeyId = s"publicKey-${nodeId.value}"
                val cfKeyHash   = nodeInfoService.getNodeInfo(nodeId) match {
                  case Full(Some(nodeInfo)) if(nodeInfo.securityTokenHash.nonEmpty) => <span>{nodeInfo.securityTokenHash}</span>
                  case _                                                            => <span><i>Hash not available</i></span>
                }

                val tokenKind = agent.securityToken match {
                  case _ : PublicKey   => "Public key"
                  case _ : Certificate => "Certificate"
                }
                <b><a href="#" onclick={s"$$('#${publicKeyId}').toggle(300); return false;"}>Display Node key {checked}</a></b>
                <div style="width=100%; overflow:auto;">
                  <pre id={publicKeyId} class="display-keys" style="display:none;"><label>{tokenKind}:</label>{agent.securityToken.key}<label>{tokenKind} Hash:</label>{cfKeyHash}</pre>
                </div> ++
                Script(OnLoad(JsRaw(s"""createTooltip();""")))
              case None => NodeSeq.Empty
            }
          }
        </div>

      <h4 class="tablemargin">Accounts</h4>
        <div class="tablepadding">
          <b>Administrator account:</b> {sm.node.main.rootUser}<br/>
          <b>Local account(s):</b> {displayAccounts(sm.node)}<br/>
        </div> <br/>
        {deleteButton}

      </div>
  }

  private def htmlId(jsId:JsNodeId, prefix:String) : String = prefix + jsId.toString
  private def htmlId_#(jsId:JsNodeId, prefix:String) : String = "#" + prefix + jsId.toString

  private def ?(in:Option[String]) : NodeSeq = in.map(Text(_)).getOrElse(NodeSeq.Empty)

  // Display the role of the node
  private def displayServerRole(sm:FullInventory, inventoryStatus : InventoryStatus) : NodeSeq = {
    val nodeId = sm.node.main.id
    inventoryStatus match {
      case AcceptedInventory =>
        val nodeInfoBox = nodeInfoService.getNodeInfo(nodeId)
        nodeInfoBox match {
          case Full(Some(nodeInfo)) =>
            val kind = {
              if(nodeInfo.isPolicyServer) {
                if(isRootNode(nodeId) ) {
                  "server"
                } else {
                  "relay server"
                }
              } else {
                if (nodeInfo.serverRoles.isEmpty){
                  "node"
                } else {
                  "server component"
                }
              }
            }
            val roles = if (nodeInfo.serverRoles.isEmpty) {
              ""
            } else {
              nodeInfo.serverRoles.map(_.value).mkString("(",", ",")")
            }

            <span><b>Role: </b>Rudder {kind} {roles}</span><br/>
          case Full(None) =>
            logger.error(s"Could not fetch node details for node with id ${sm.node.main.id}")
            <span class="error"><b>Role: </b>Could not fetch Role for this node</span><br/>
          case eb:EmptyBox =>
            val e = eb ?~! s"Could not fetch node details for node with id ${sm.node.main.id}"
            logger.error(e.messageChain)
            <span class="error"><b>Role: </b>Could not fetch Role for this node</span><br/>
        }
      case RemovedInventory =>
        <span><b>Role: </b>Deleted node</span><br/>
      case PendingInventory =>
        <span><b>Role: </b>Pending node</span><br/>
    }
  }

  private def displayPolicyServerInfos(sm:FullInventory) : NodeSeq = {
    nodeInfoService.getNodeInfo(sm.node.main.policyServerId) match {
      case eb:EmptyBox =>
        val e = eb ?~! s"Could not fetch policy server details (id '${sm.node.main.policyServerId.value}') for node '${sm.node.main.hostname}' ('${sm.node.main.id.value}')"
        logger.error(e.messageChain)
        <span class="error"><b>Rudder Policy Server: </b>Could not fetch details about the policy server</span>
      case Full(Some(policyServerDetails)) =>
        <span><b>Rudder Policy Server: </b><a href={linkUtil.baseNodeLink(policyServerDetails.id)}  onclick="location.reload()">{policyServerDetails.hostname}</a></span>
      case Full(None) =>
        logger.error(s"Could not fetch policy server details (id '${sm.node.main.policyServerId.value}') for node '${sm.node.main.hostname}' ('${sm.node.main.id.value}')")
        <span class="error"><b>Rudder Policy Server: </b>Could not fetch details about the policy server</span>
    }
  }

  private def displayMachineType(opt:Option[MachineInventory]) : NodeSeq = {
    opt match {
      case None => NodeSeq.Empty
      case Some(machine) => (
        machine.machineType match {
          case PhysicalMachineType => Text("Physical machine")
          case VirtualMachineType(vmType) => Text("Virtual machine (%s)".format(S.?("vm.type." + vmType.name)))
        }
      )
    }
  }

  //show a comma separated list with description in tooltip

  private def displayAccounts(node:NodeInventory) : NodeSeq = {
    Text{if(node.accounts.isEmpty) {
        "None"
      } else {
        node.accounts.sortWith(_ < _).mkString(", ")
      }
    }
  }

  private def displayTabGrid[T](jsId:JsNodeId)(eltName:String, optSeq:Box[Seq[T]],title:Option[String]=None)(columns:List[(String, T => NodeSeq)]) = {

    <div id={htmlId(jsId,"sd_"+eltName +"_")} class="sInventory overflow_auto" style="display:none;">{
      optSeq match {
        case Empty =>
          <div class="col-xs-12 alert alert-warning">
            <span>No matching components detected on this node</span>
          </div>
        case Failure(m,_,_) => <span class="error">Error when trying to fetch file systems. Reported message: {m}</span>
        case Full(seq) if (seq.isEmpty && eltName != "soft") =>
          <div class="col-xs-12 alert alert-warning">
            <span>No matching components detected on this node</span>
          </div>
        case Full(seq) =>
          <table cellspacing="0" id={htmlId(jsId,eltName+"_")} class="tablewidth">
          { title match {
            case None => NodeSeq.Empty
            case Some(title) => <div style="text-align:center"><b>{title}</b></div>
            }
          }
          <thead>
          <tr class="head">
          </tr>
            <tr class="head">{
              columns.map {h => <th>{h._1}</th> }.toSeq
            }</tr>
          </thead>
          <tbody class="toggle-color">{ seq.flatMap { x =>
            <tr>{ columns.flatMap{ case(header,renderLine) =>  <td>{renderLine(x)}</td> } }</tr>
          } }</tbody>
          </table>
      }
    }<div id={htmlId(jsId,eltName + "_grid_") + "_paginate_area"} class="paginate"/>
    </div>
  }

  private def displayTabSoftware(jsId:JsNodeId) : NodeSeq =
    displayTabGrid(jsId)("soft",
        //do not retrieve software here
        Full(Seq())
    ){
      ("Name", {x:Software => ?(x.name)} ) ::
      ("Version", {x:Software => ?(x.version.map(_.value)) } ) ::
      ("Description", {x:Software => ?(x.description) } ) ::
      Nil
    }

  private def displayTabNetworks(jsId:JsNodeId,sm:FullInventory) : NodeSeq =
    displayTabGrid(jsId)("net", Full(sm.node.networks)){
        ("Interface", {x:Network => Text(x.name)}) ::
        ("IP address", {x:Network => Text(x.ifAddresses.map{ _.getHostAddress }.mkString(", "))}) ::
        ("Mask", {x:Network => Text(x.ifMask.map{ _.getHostAddress }.mkString(", "))}) ::
        ("Network", {x:Network => Text(x.ifSubnet.map{ _.getHostAddress }.mkString(", "))}) ::
        ("Gateway", {x:Network => Text(x.ifGateway.map{ _.getHostAddress }.mkString(", "))}) ::
        ("DHCP server", {x:Network => Text(x.ifDhcp.map{ _.getHostAddress }.mkString(", "))}) ::
        ("MAC address", {x:Network => ?(x.macAddress)}) ::
        ("Type", {x:Network => ?(x.ifType)}) ::
        ("Speed", {x:Network => ?(x.speed)}) ::
        ("Status", {x:Network => ?(x.status)}) ::
        Nil
    }

  private def displayTabFilesystems(jsId:JsNodeId,sm:FullInventory) : NodeSeq =
    displayTabGrid(jsId)("fs", Full(sm.node.fileSystems)){
        ("Mount point", {x:FileSystem => Text(x.mountPoint)}) ::
        ("Filesystem", {x:FileSystem => ?(x.name)}) ::
        ("Free space", {x:FileSystem => ?(x.freeSpace.map(_.toStringMo))}) ::
        ("Total space", {x:FileSystem => ?(x.totalSpace.map(_.toStringMo))}) ::
        ("File count", {x:FileSystem => ?(x.fileCount.map(_.toString))}) ::
        Nil
    }

    private def displayTabVariable(jsId:JsNodeId,sm:FullInventory) : NodeSeq = {
      val title = sm.node.inventoryDate.map(date => "Environment variable status on %s".format(DateFormaterService.getFormatedDate(date)))
      displayTabGrid(jsId)("var", Full(sm.node.environmentVariables),title){
        ("Name", {x:EnvironmentVariable => Text(x.name)}) ::
        ("Value", {x:EnvironmentVariable => Text(x.value.getOrElse("Unspecified"))}) ::
        Nil
      }
    }

    private def displayTabProperties(jsId:JsNodeId, node: NodeInfo) : NodeSeq = {
      import com.normation.rudder.domain.nodes.JsonSerialisation._
      import com.normation.rudder.AuthorizationType
      import net.liftweb.json._

      val nodeId = node.id.value
      val jsonProperties = compactRender(node.properties.toApiJson())
      val userHasRights = CurrentUser.checkRights(AuthorizationType.Node.Write)
      def tabProperties = ChooseTemplate(List("templates-hidden", "components", "ComponentNodeProperties") , "nodeproperties-tab")

      val css: CssSel =  "#tabPropsId [id]" #> htmlId(jsId,"sd_props_")
      css(tabProperties) ++ Script(OnLoad(JsRaw(s"""
        angular.bootstrap('#nodeProp', ['nodeProperties']);
        var scope  = angular.element($$("#nodeProp")).scope();
        scope.$$apply(function(){
          scope.init(${jsonProperties},"${nodeId}",${userHasRights});
        });
      """)))
    }

    private def displayTabProcess(jsId:JsNodeId,sm:FullInventory) : NodeSeq = {
    val title = sm.node.inventoryDate.map(date => "Process status on %s".format(DateFormaterService.getFormatedDate(date)))
    displayTabGrid(jsId)("process", Full(sm.node.processes),title){
        ("User", {x:Process => ?(x.user)}) ::
        ("PID", {x:Process => Text(x.pid.toString())}) ::
        ("% CPU", {x:Process => ?(x.cpuUsage.map(_.toString()))}) ::
        ("% Memory", {x:Process => ?(x.memory.map(_.toString()))}) ::
        ("Virtual memory", {x:Process => ?(x.virtualMemory.map(memory => MemorySize(memory.toLong).toStringMo()))}) ::
        ("TTY", {x:Process => ?(x.tty)}) ::
        ("Started on", {x:Process => ?(x.started)}) ::
        ("Command", { x:Process => ?(x.commandName) }) ::
        Nil
    }
    }

    private def displayTabVM(jsId:JsNodeId,sm:FullInventory) : NodeSeq =
    displayTabGrid(jsId)("vm", Full(sm.node.vms)){
        ("Name", {x:VirtualMachine => ?(x.name)}) ::
        ("Type", {x:VirtualMachine => ?(x.vmtype)}) ::
        ("SubSystem", {x:VirtualMachine => ?(x.subsystem)}) ::
        ("Uuid", {x:VirtualMachine => Text(x.uuid.value)}) ::
        ("Status", {x:VirtualMachine => ?(x.status)}) ::
        ("Owner", {x:VirtualMachine => ?(x.owner)}) ::
        ("# Cpu", {x:VirtualMachine => ?(x.vcpu.map(_.toString()))}) ::
        ("Memory", { x:VirtualMachine => ?(x.memory) }) ::
        Nil
    }

  private def displayTabBios(jsId:JsNodeId,sm:FullInventory) : NodeSeq =
    displayTabGrid(jsId)("bios", sm.machine.map(fm => fm.bios)){
        ("Name", {x:Bios => Text(x.name)}) ::
        ("Editor", {x:Bios => ?(x.editor.map( _.name))}) ::
        ("Version", {x:Bios => ?(x.version.map( _.value))}) ::
        ("Release Date", {x:Bios => ?(x.releaseDate.map(DateFormaterService.getFormatedDate(_)))}) ::
        Nil
    }

  private def displayTabControllers(jsId:JsNodeId,sm:FullInventory) : NodeSeq =
    displayTabGrid(jsId)("controllers", sm.machine.map(fm => fm.controllers)){
        ("Name", {x:Controller => Text(x.name)}) ::
        ("Manufacturer", {x:Controller => ?(x.manufacturer.map( _.name))}) ::
        ("Type", {x:Controller => ?(x.cType)}) ::
        ("Quantity", {x:Controller => Text(x.quantity.toString)}) ::
        Nil
    }

  private def displayTabMemories(jsId:JsNodeId,sm:FullInventory) : NodeSeq =
    displayTabGrid(jsId)("memories", sm.machine.map(fm => fm.memories)){
        ("Slot", {x:MemorySlot => Text(x.slotNumber)}) ::
        ("Capacity", {x:MemorySlot => ?(x.capacity.map( _.toStringMo ))}) ::
        ("Description", {x:MemorySlot => ?(x.description)}) ::
        ("Serial Number", {x:MemorySlot => ?(x.serialNumber)}) ::
        ("Speed", {x:MemorySlot => ?(x.speed)}) ::
        ("Type", {x:MemorySlot => ?(x.memType)}) ::
        ("Quantity", {x:MemorySlot => Text(x.quantity.toString)}) ::
        Nil
    }

  private def displayTabPorts(jsId:JsNodeId,sm:FullInventory) : NodeSeq =
    displayTabGrid(jsId)("ports", sm.machine.map(fm => fm.ports)){
        ("Name", {x:Port => Text(x.name)}) ::
        ("Type", {x:Port => ?(x.pType )}) ::
        ("Description", {x:Port => ?(x.description)}) ::
        ("Quantity", {x:Port => Text(x.quantity.toString)}) ::
        Nil
    }

  private def displayTabProcessors(jsId:JsNodeId,sm:FullInventory) : NodeSeq =
    displayTabGrid(jsId)("processors", sm.machine.map(fm => fm.processors)){
        ("Name", {x:Processor => Text(x.name)}) ::
        ("Speed", {x:Processor => ?(x.speed.map(_.toString))}) ::
        ("Model", {x:Processor => ?(x.model.map(_.toString()))}) ::
        ("Family", {x:Processor => ?(x.family.map(_.toString()))}) ::
        ("Family Name", {x:Processor => ?(x.familyName)}) ::
        ("Manufacturer", {x:Processor => ?(x.manufacturer.map(_.name))}) ::
        ("Thread", {x:Processor => ?(x.thread.map(_.toString()))}) ::
        ("Core", {x:Processor => ?(x.core.map(_.toString()))}) ::
        ("CPUID", {x:Processor => ?(x.cpuid)}) ::
        ("Architecture", {x:Processor => ?(x.arch)}) ::
        ("Stepping", {x:Processor => ?(x.stepping.map(_.toString))}) ::
        ("Quantity", {x:Processor => Text(x.quantity.toString)}) ::
        Nil
    }

  private def displayTabSlots(jsId:JsNodeId,sm:FullInventory) : NodeSeq =
    displayTabGrid(jsId)("slots", sm.machine.map(fm => fm.slots)){
        ("Name" , {x:Slot => Text(x.name)}) ::
        ( "Description" , {x:Slot => ?(x.description)}) ::
        ( "Status" , {x:Slot => ?(x.status)}) ::
        ( "Quantity" , {x:Slot => Text(x.quantity.toString)}) ::
        Nil
    }

  private def displayTabSounds(jsId:JsNodeId,sm:FullInventory) : NodeSeq =
    displayTabGrid(jsId)("sounds", sm.machine.map(fm => fm.sounds)){
        ("Name" , {x:Sound => Text(x.name)}) ::
        ( "Description" , {x:Sound => ?(x.description)}) ::
        ( "Quantity" , {x:Sound => Text(x.quantity.toString)}) ::
        Nil
    }

  private def displayTabStorages(jsId:JsNodeId,sm:FullInventory) : NodeSeq =
    displayTabGrid(jsId)("storages", sm.machine.map(fm => fm.storages)){
        ( "Name" , {x:Storage => Text(x.name)}) ::
        ( "Description" , {x:Storage => ?(x.description)}) ::
        ( "Size" , {x:Storage => ?(x.size.map( _.toStringMo))}) ::
        ( "Firmware" , {x:Storage => ?(x.firmware)}) ::
        ( "Manufacturer" , {x:Storage => ?(x.manufacturer.map(_.name))}) ::
        ( "Model" , {x:Storage => ?(x.model)}) ::
        ( "Serial" , {x:Storage => ?(x.serialNumber)}) ::
        ( "Type" , {x:Storage => ?(x.sType)}) ::
        ( "Quantity" , {x:Storage => Text(x.quantity.toString)}) ::
        Nil
    }

  private def displayTabVideos(jsId:JsNodeId,sm:FullInventory) : NodeSeq =
    displayTabGrid(jsId)("videos", sm.machine.map(fm => fm.videos)){
        ("Name" , {x:Video => Text(x.name)}) ::
        ( "Chipset" , {x:Video => ?(x.chipset)}) ::
        ( "Memory" , {x:Video => ?(x.memory.map( _.toStringMo))}) ::
        ( "Resolution" , {x:Video => ?(x.resolution)}) ::
        ( "Quantity" , {x:Video => Text(x.quantity.toString)}) ::
        Nil
    }

  private[this] def showDeleteButton(nodeId : NodeId) = {
    def toggleDeletion() : JsCmd = {
      JsRaw(""" $('#deleteButton').toggle(300); $('#confirmDeletion').toggle(300) """)
    }
    SHtml.ajaxButton(
        "Delete"
      , { () => {toggleDeletion() } }
      , ("id", "deleteButton")
      , ("class", "btn btn-danger")
    ) ++ <div style="display:none" id="confirmDeletion">
    <div style="margin:5px;">
     <div>
      <div>
          <i class="fa fa-exclamation-triangle warnicon" aria-hidden="true"></i>
          <b>Are you sure you want to delete this node?</b>
      </div>
      <div style="margin-top:7px">If you choose to remove this node from Rudder, it won't be managed anymore,
       and all information about it will be removed from the application.</div>
     </div>
    <div>
      <div style="margin-top:7px">
        <span >
          {
            SHtml.ajaxButton("Cancel", { () => { toggleDeletion } } , ("class", "btn btn-default"))
          }
          {
            SHtml.ajaxButton("Confirm", { () => {removeNode(nodeId) }}, ("class", "btn btn-danger") )
          }
        </span>
      </div>
    </div>
    </div>
</div>
  }

  private[this] def removeNode(nodeId: NodeId) : JsCmd = {
    val modId = ModificationId(uuidGen.newUuid)
    import com.normation.rudder.services.servers.DeletionResult._
    removeNodeService.removeNode(nodeId, modId, CurrentUser.actor) match {
      case Full(Success) =>
        asyncDeploymentAgent ! AutomaticStartDeployment(modId, CurrentUser.actor)
        onSuccess
      case Full(PostHookFailed(postHook)) =>
        val message = s"Node '${nodeId.value}' was deleted, but an error occured while running post-deletion hooks"
        logger.error(message)
        logger.error(postHook.msg)
        onFailure(nodeId, message, postHook.msg, Some(postHook))
      case Full(PreHookFailed(int : Interrupt)) =>
        val message = s"Node '${nodeId.value}' deletion was interrupted because one of the pre-deletion hooks exited with interrupt code (${int.code})"
        logger.warn(message)
        logger.warn(int.msg)
        onFailure(nodeId, message, int.msg, Some(int))
      case Full(PreHookFailed(hook)) =>
        val message = s"Node '${nodeId.value}' was not deleted, since an error occurred while running pre-deletion hooks"
        logger.error(message)
        logger.error(hook.msg)
        onFailure(nodeId, message,hook.msg, Some(hook))
      case eb:EmptyBox =>
        val message = s"Could not remove Node '${nodeId.value}' from Rudder"
        val e = eb ?~! "There was an error while deleting Node"
        logger.error(e.messageChain)
        onFailure(nodeId, message, e.messageChain, None)
    }
  }

  private[this] def onFailure(nodeId: NodeId, message : String, details : String, hookError : Option[HookReturnCode.Error]) : JsCmd = {

    val hookDetails = {
      hookError match {
        case Some(error) =>
          { if (error.stdout.size > 0) {
            <b>stdout</b>
            <pre>{error.stdout}</pre>
          } else {
            NodeSeq.Empty
          } } ++ {
          if (error.stderr.size > 0) {
            <b>stderr</b>
            <pre>{error.stderr}</pre>
          } else {
            NodeSeq.Empty
          } }
        case None => NodeSeq.Empty
      }
    }

    val popupHtml =
    <div class="modal-backdrop fade in" style="height: 100%;"></div>
    <div class="modal-dialog">
        <div class="modal-content">
            <div class="modal-header">
                <div class="close" data-dismiss="modal">
                    <span aria-hidden="true">&times;</span>
                    <span class="sr-only">Close</span>
                </div>
                <h4 class="modal-title">
                    Error during Node deletion
                </h4>
            </div>
            <div class="modal-body">
                <h4>{message}</h4>
                <p>{details}</p>
                {hookDetails}
            </div>
            <div class="modal-footer">
                <button class="btn btn-default" type="button" data-dismiss="modal">
                    Close
                </button>
            </div>
        </div>
    </div>;
    JsRaw( """$('#errorPopupHtmlId').bsModal('hide');""") &
    SetHtml(errorPopupHtmlId, popupHtml) &
    JsRaw( s""" callPopupWithTimeout(200,"${errorPopupHtmlId}")""")
  }

  private[this] def onSuccess : JsCmd = {
    val popupHtml =
      <div class="modal-backdrop fade in" style="height: 100%;"></div>
        <div class="modal-dialog">
        <div class="modal-content">
            <div class="modal-header">
                <div class="close" data-dismiss="modal">
                    <span aria-hidden="true">&times;</span>
                    <span class="sr-only">Close</span>
                </div>
                <h4 class="modal-title">
                    Success
                </h4>
            </div>
            <div class="modal-body">
                <h4 class="text-center">The node has been successfully removed from Rudder.</h4>
            </div>
            <div class="modal-footer">
                <button class="btn btn-default" type="button" data-dismiss="modal">
                    Close
                </button>
            </div>
        </div>
    </div>
    JsRaw(s"updateHashString('nodeId', undefined); forceParseHashtag()") &
    SetHtml("serverDetails", NodeSeq.Empty) &
    JsRaw( """$('#successPopupHtmlId').bsModal('hide');""") &
    SetHtml(successPopupHtmlId, popupHtml) &
    JsRaw( s""" callPopupWithTimeout(200,"${successPopupHtmlId}") """)
  }

  private [this] def isRootNode(n: NodeId): Boolean = {
    return n.value.equals("root");
  }

  import com.normation.rudder.domain.nodes.NodeState
  private[this] def getNodeState(nodeState: NodeState) : String = {
    S.?(s"node.states.${nodeState.name}")
  }
}
