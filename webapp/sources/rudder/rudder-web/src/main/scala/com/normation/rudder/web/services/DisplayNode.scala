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

import bootstrap.liftweb.RudderConfig
import com.normation.box._
import com.normation.cfclerk.domain.HashAlgoConstraint.SHA1
import com.normation.eventlog.ModificationId
import com.normation.inventory.domain._
import com.normation.rudder.batch.AutomaticStartDeployment
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.domain.nodes.NodeKind
import com.normation.rudder.domain.policies.GlobalPolicyMode
import com.normation.rudder.domain.policies.PolicyModeOverrides._
import com.normation.rudder.domain.reports.ComplianceLevel
import com.normation.rudder.domain.reports.ComplianceLevelSerialisation
import com.normation.rudder.domain.reports.NodeStatusReport
import com.normation.rudder.hooks.HookReturnCode
import com.normation.rudder.services.reports.NoReportInInterval
import com.normation.rudder.services.reports.Pending
import com.normation.rudder.web.model.JsNodeId
import com.normation.rudder.web.services.CurrentUser
import com.normation.utils.DateFormaterService
import com.normation.zio._
import net.liftweb.common._
import net.liftweb.http._
import net.liftweb.http.js._
import net.liftweb.http.js.JE.JsArray
import net.liftweb.http.js.JE.JsRaw
import net.liftweb.http.js.JE.JsVar
import net.liftweb.http.js.JE.Str
import net.liftweb.http.js.JsCmds._
import net.liftweb.util._
import net.liftweb.util.Helpers._
import org.joda.time.DateTime
import scala.xml._
import scala.xml.Utility.escape

/**
 * A service used to display details about a server
 * inventory with tabs like:
 * [summary][compliance reports][inventory][properties][technical logs][settings]
 *
 * Use it by calling:
 * # head if not yet called in that page
 * # show(nodeId) : NodeSeq
 *    where you want to display node information
 * # jsInit(nodeId) : Cmd
 *    to init javascript for it
 */
object DisplayNode extends Loggable {

  private[this] val getSoftwareService    = RudderConfig.readOnlySoftwareDAO
  private[this] val removeNodeService     = RudderConfig.removeNodeService
  private[this] val asyncDeploymentAgent  = RudderConfig.asyncDeploymentAgent
  private[this] val uuidGen               = RudderConfig.stringUuidGenerator
  private[this] val nodeInfoService       = RudderConfig.nodeInfoService
  private[this] val linkUtil              = RudderConfig.linkUtil
  private[this] val reportingService      = RudderConfig.reportingService
  private[this] val deleteNodePopupHtmlId = "deleteNodePopupHtmlId"
  private[this] val errorPopupHtmlId      = "errorPopupHtmlId"
  private[this] val successPopupHtmlId    = "successPopupHtmlId"

  private def escapeJs(in: String):   JsExp   = Str(escape(in))
  private def escapeHTML(in: String): NodeSeq = Text(escape(in))
  private def ?(in: Option[String]):  NodeSeq = in.map(escapeHTML).getOrElse(NodeSeq.Empty)

  private def loadComplianceBar(nodeInfo: Option[NodeInfo]): Option[JsArray] = {
    for {
      node                 <- nodeInfo
      userNodeStatusReport <- reportingService.findUserNodeStatusReport(node.node.id)
      nodeCompliance        = makeComplianceFromNodeStatusReport(userNodeStatusReport)
    } yield {
      ComplianceLevelSerialisation.ComplianceLevelToJs(nodeCompliance).toJsArray
    }
  }

  // this method will transform the compliance for case when there's not rules applied
  // a node with pending status and no rules will be converted to "pending=1"
  // a node with no report status and no rules will be converted to "no report=1"
  // anything else with no rules will stay 0 compliance
  private def makeComplianceFromNodeStatusReport(nodeStatusReport: NodeStatusReport): ComplianceLevel = {
    val compliance = nodeStatusReport.compliance
    // different case given the status
    nodeStatusReport.runInfo match {
      case Pending(_, _, _) =>
        // if there are no reports at all because no rule is applied, we say one is pending
        if (compliance.total == 0) {
          compliance.copy(pending = 1)
        } else {
          compliance
        }

      case NoReportInInterval(_, _) =>
        // if there are no reports at all because no rule is applied, we say one is missing
        if (compliance.total == 0) {
          compliance.copy(missing = 1)
        } else {
          compliance
        }

      case _ => // nothing to do in this case
        nodeStatusReport.compliance
    }
  }

  private def loadSoftware(jsId: JsNodeId, softIds: Seq[SoftwareUuid])(nodeId: String): JsCmd = {
    (for {
      seq       <- getSoftwareService.getSoftware(softIds)
      gridDataId = htmlId(jsId, "soft_grid_data_")
      gridId     = "soft"
    } yield SetExp(
      JsVar(gridDataId),
      JsArray(seq.map { x =>
        JsArray(
          escapeJs(x.name.getOrElse("")),
          escapeJs(x.version.map(_.value).getOrElse("")),
          escapeJs(x.description.getOrElse(""))
        )
      }: _*)
    ) & JsRaw(s"""
          $$('#${htmlId(jsId, gridId + "_")}').dataTable({
            "aaData":${gridDataId},
            "bJQueryUI": false,
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
            """)).toBox match {
      case Empty            => Alert("No software found for that server")
      case Failure(m, _, _) => Alert("Error when trying to fetch software. Reported message: " + m)
      case Full(js)         => js
    }
  }

  def jsInit(nodeId: NodeId, softIds: Seq[SoftwareUuid], salt: String = ""): JsCmd = {
    val jsId           = JsNodeId(nodeId, salt)
    val detailsId      = htmlId(jsId, "details_")
    val softGridDataId = htmlId(jsId, "soft_grid_data_")
    val softPanelId    = "soft_tab"
    val eltIdswidth    =
      List(("process", List("50", "50", "70", "85", "120", "50", "100", "850"), 1), ("var", List("200", "800"), 0))
    val eltIds         = List(
      "vm",
      "fs",
      "net",
      "bios",
      "controllers",
      "memories",
      "ports",
      "processors",
      "slots",
      "sounds",
      "softUpdates",
      "storages",
      "videos"
    )

    JsRaw("var " + softGridDataId + "= null") &
    OnLoad(
      JsRaw("$('#" + detailsId + "').tabs()") & {
        eltIds.map { i =>
          JsRaw(s"""
              $$('#${htmlId(jsId, i + "_")}').dataTable({
                "bJQueryUI": false,
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
                   | """.stripMargin('|')): JsCmd
        }.reduceLeft((i, acc) => acc & i)
      } & {
        eltIdswidth.map {
          case (id, columns, sorting) =>
            JsRaw(s"""
              $$('#${htmlId(jsId, id + "_")}').dataTable({
                "bJQueryUI": false,
                "bRetrieve": true,
                "sPaginationType": "full_numbers",
                "bFilter": true,
                "asStripeClasses": [ 'color1', 'color2' ],
                "bPaginate": true,
                "aoColumns": ${columns.map(col => s"{'sWidth': '${col}px'}").mkString("[", ",", "]")} ,
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
           """): JsCmd
        }.reduceLeft((i, acc) => acc & i)
      } &
      // for the software tab, we check for the panel id, and the firstChild id
      // if the firstChild.id == softGridId, then it hasn't been loaded, otherwise it is softGridId_wrapper
      JsRaw(s"""
        $$("#${softPanelId}").click(function() {
            ${SHtml.ajaxCall(JsRaw("'" + nodeId.value + "'"), loadSoftware(jsId, softIds))._2.toJsCmd}
        });
        """)
    )
  }

  def showInventoryVerticalMenu(sm: FullInventory, salt: String = ""): NodeSeq = {
    val jsId = JsNodeId(sm.node.main.id, salt)
    val mainTabDeclaration: List[NodeSeq] = {
      <li><a href={htmlId_#(jsId, "sd_fs_")}>File systems</a></li> ::
      <li><a href={htmlId_#(jsId, "sd_net_")}>Network interfaces</a></li> ::
      <li id="soft_tab"><a href={htmlId_#(jsId, "sd_soft_")}>Software</a></li> ::
      <li><a href={htmlId_#(jsId, "sd_softUpdates_")}>Software Update</a></li> ::
      <li><a href={htmlId_#(jsId, "sd_var_")}>Environment</a></li> ::
      // Hardware content
      <li><a href={htmlId_#(jsId, "sd_bios_")}>BIOS</a></li> ::
      <li><a href={htmlId_#(jsId, "sd_controllers_")}>Controllers</a></li> ::
      <li><a href={htmlId_#(jsId, "sd_memories_")}>Memory</a></li> ::
      <li><a href={htmlId_#(jsId, "sd_ports_")}>Ports</a></li> ::
      <li><a href={htmlId_#(jsId, "sd_processors_")}>Processors</a></li> ::
      <li><a href={htmlId_#(jsId, "sd_slots_")}>Slots</a></li> ::
      <li><a href={htmlId_#(jsId, "sd_sounds_")}>Sound</a></li> ::
      <li><a href={htmlId_#(jsId, "sd_storages_")}>Storage</a></li> ::
      <li><a href={htmlId_#(jsId, "sd_videos_")}>Video</a></li> ::
      //
      <li><a href={htmlId_#(jsId, "sd_process_")}>Processes</a></li> ::
      <li><a href={htmlId_#(jsId, "sd_vm_")}>Virtual Machines</a></li> ::
      Nil
    }

    val tabContent = {
      displayTabBios(jsId, sm) ::
      displayTabControllers(jsId, sm) ::
      displayTabMemories(jsId, sm) ::
      displayTabPorts(jsId, sm) ::
      displayTabProcessors(jsId, sm) ::
      displayTabSlots(jsId, sm) ::
      displayTabSounds(jsId, sm) ::
      displayTabStorages(jsId, sm) ::
      displayTabVideos(jsId, sm) ::
      displayTabFilesystems(jsId, sm) ::
      displayTabNetworks(jsId, sm) ::
      displayTabSoftware(jsId) ::
      displayTabSoftwareUpdates(jsId, sm) ::
      displayTabVariable(jsId, sm) ::
      displayTabProcess(jsId, sm) ::
      displayTabVM(jsId, sm) ::
      Nil
    }

    val tabId = htmlId(jsId, "node_details_")

    <div id={tabId} class="sInventory ui-tabs-vertical">
      <ul class="list-tabs-invetory">{mainTabDeclaration}</ul>
      {tabContent.flatten}
    </div> ++ Script(OnLoad(JsRaw(s"$$('#${tabId}').tabs()")))
  }

  /**
   * Show the details in a panned version, with Node Summary, Inventory, Network, Software
   * Should be used with jsInit(dn:String, softIds:Seq[SoftwareUuid], salt:String="")
   */
  def showPannedContent(
      nodeAndGlobalMode: Option[(NodeInfo, GlobalPolicyMode)],
      sm:                FullInventory,
      inventoryStatus:   InventoryStatus,
      salt:              String = ""
  ): NodeSeq = {
    val jsId      = JsNodeId(sm.node.main.id, salt)
    val detailsId = htmlId(jsId, "details_")
    <div id={detailsId} class="tabs">
      <ul class="rudder-ui-tabs">
        <li><a href={htmlId_#(jsId, "node_summary_")}>Summary</a></li>
        <li><a href={htmlId_#(jsId, "node_inventory_")}>Inventory</a></li>
      </ul>
      <div id={htmlId(jsId, "node_summary_")}>
        {showNodeDetails(sm, nodeAndGlobalMode, None, inventoryStatus, salt)}
      </div>
      <div id={htmlId(jsId, "node_inventory_")}>
        {showInventoryVerticalMenu(sm)}
      </div>
    </div>
  }

  // mimic the content of server_details/ShowNodeDetailsFromNode
  def showNodeDetails(
      sm:                  FullInventory,
      nodeAndGlobalMode:   Option[(NodeInfo, GlobalPolicyMode)],
      creationDate:        Option[DateTime],
      inventoryStatus:     InventoryStatus,
      salt:                String = "",
      isDisplayingInPopup: Boolean = false
  ): NodeSeq = {

    val nodeInfo = nodeAndGlobalMode.map(_._1)

    val compliance     = loadComplianceBar(nodeInfo).getOrElse(JsArray())
    val nodePolicyMode = nodeAndGlobalMode match {
      case Some((node, globalMode)) =>
        Some((globalMode.overridable, node.policyMode) match {
          case (Always, Some(mode)) =>
            (mode, "<p>This mode is an override applied to this node. You can change it in the <i><b>node settings</b></i>.</p>")
          case (Always, None)       =>
            val expl =
              """<p>This mode is the globally defined default. You can change it in <i><b>settings</b></i>.</p><p>You can also override it on this node in the <i><b>node's settings</b></i>.</p>"""
            (globalMode.mode, expl)
          case (Unoverridable, _)   =>
            (globalMode.mode, "<p>This mode is the globally defined default. You can change it in <i><b>Settings</b></i>.</p>")
        })

      case None =>
        None
    }

    val deleteButton: NodeSeq = {
      sm.node.main.status match {
        case AcceptedInventory =>
          <div>
              <div id={deleteNodePopupHtmlId}  class="modal fade" data-keyboard="true" tabindex="-1" />
              <div id={errorPopupHtmlId}  class="modal fade" data-keyboard="true" tabindex="-1" />
              <div id={successPopupHtmlId}  class="modal fade" data-keyboard="true" tabindex="-1" />
          </div>
          <lift:authz role="node_write">
            {
            if (!isRootNode(sm.node.main.id)) {
              <div>
                  <div class="col-xs-12">
                    {showDeleteButton(sm.node.main.id)}
                  </div>
                </div>
            } else { NodeSeq.Empty }
          }
            </lift:authz>
        case _                 => NodeSeq.Empty
      }
    }
    val osIcon = sm.node.main.osDetails.os.name.toLowerCase();

    val osTooltip:      String = {
      s"""
        <h4>Operating system details</h4>
        <div class="tooltip-content">
          <ul>
            <li><b>Type:</b> ${escape(sm.node.main.osDetails.os.kernelName)}</li>
            <li><b>Name:</b> ${escape(S.?("os.name." + sm.node.main.osDetails.os.name))}</li>
            <li><b>Version:</b> ${escape(sm.node.main.osDetails.version.value)}</li>
            <li><b>Service Pack:</b> ${escape(sm.node.main.osDetails.servicePack.getOrElse("None"))}</li>
            <li><b>Architecture:</b> ${escape(sm.node.archDescription.getOrElse("None"))}</li>
            <li><b>Kernel version:</b> ${escape(sm.node.main.osDetails.kernelVersion.value)}</li>
          </ul>
        </div>
      """
    }
    val machineTooltip: String = {
      s"""
        <h4>Operating system details</h4>
        <div class="tooltip-content">
          <ul>
            <li><b>Total physical memory (RAM):</b> ${escape(sm.node.ram.map(_.toStringMo).getOrElse("-"))}</li>
            <li><b>Manufacturer:</b> ${escape(sm.machine.flatMap(x => x.manufacturer).map(x => x.name).getOrElse("-"))}</li>
            <li><b>Total swap space:</b> ${escape(sm.node.swap.map(_.toStringMo).getOrElse("-"))}</li>
            <li><b>System Serial Number:</b> ${escape(sm.machine.flatMap(x => x.systemSerialNumber).getOrElse("-"))}</li>
            <li><b>Time Zone:</b>
              ${escape(
          sm.node.timezone
            .map(x => if (x.name.toLowerCase == "utc") "UTC" else s"${x.name} (UTC ${x.offset})")
            .getOrElse("unknown")
        )}
            </li>
            <li>
              ${sm.machine
          .map(_.id.value)
          .map(machineId => "<b>Machine ID:</b> " ++ { escape(machineId) })
          .getOrElse("""<span class="error">Machine Information are missing for that node</span>""")}
            </li>
          </ul>
        </div>
      """
    }

    <div id="nodeDetails">
      <div class="id-card node">
        <div class={"card-img " ++ osIcon}></div>
        <div class="card-info">
          <div><label>Hostname:         </label>{sm.node.main.hostname}{
      nodeAndGlobalMode match {
        case Some((n, _)) => <span class={"node-state " ++ getNodeState(n.state).toLowerCase}></span>
        case None         => NodeSeq.Empty
      }
    }
          </div>
          <div><label>Node ID:          </label>{sm.node.main.id.value}</div>
          <div><label>Operating system: </label>{
      escape(sm.node.main.osDetails.fullName)
    }<span class="glyphicon glyphicon-info-sign icon-info" data-toggle="tooltip" data-placement="right" data-html="true" data-original-title={
      osTooltip
    }></span></div>
          <div>
            <label>Machine:             </label>{displayMachineType(sm.machine)}
            <span class="machine-info ram">{sm.node.ram.map(_.toStringMo).getOrElse("-")}</span>
            <span class="glyphicon glyphicon-info-sign icon-info" data-toggle="tooltip" data-placement="right" data-html="true" data-original-title={
      machineTooltip
    }></span>
          </div>
        </div>
      </div>

      <div class="row">

        <div class="rudder-info col-lg-6 col-sm-7 col-xs-12">
          <h3>Rudder information</h3>
          <div>
            {
      nodePolicyMode match {
        case None                      => NodeSeq.Empty
        case Some((mode, explanation)) =>
          <label>Policy mode:</label><span id="badge-apm"></span> ++
          Script(OnLoad(JsRaw(s"""
                $$('#badge-apm').append(createBadgeAgentPolicyMode('node',"${mode}","${explanation}", "body"));
                $$('.rudder-label, .icon-info').bsTooltip();
              """)))
      }
    }
          </div>
          {displayServerRole(sm, inventoryStatus)}
          <div><label>Agent:</label> {
      sm.node.agents.map { a =>
        val capabilities = {
          if (a.capabilities.isEmpty) "no extra capabilities"
          else "capabilities: " + a.capabilities.map(_.value).toList.sorted.mkString(", ")
        }
        s"${a.agentType.displayName} (${a.version.map(_.value).getOrElse("unknown version")} with ${capabilities})"
      }.headOption.getOrElse("Not found")
    }</div>
          {displayPolicyServerInfos(sm)}
          <div><label>Administrator account:</label> {sm.node.main.rootUser}</div>
          {
      sm.node.agents.headOption match {
        case Some(agent) =>
          val checked     = (sm.node.main.status, sm.node.main.keyStatus) match {
            case (AcceptedInventory, CertifiedKey) =>
              <span>
                    <span class="glyphicon glyphicon-ok text-success tooltipable" title="" tooltipid={
                s"tooltip-key-${sm.node.main.id.value}"
              }></span>
                    <span class="tooltipContent" id={s"tooltip-key-${sm.node.main.id.value}"}>
                      Inventories for this Node must be signed with this key
                    </span>
                  </span>
            case (AcceptedInventory, UndefinedKey) =>
              <span>
                    <span class="glyphicon glyphicon-exclamation-sign text-warning tooltipable" title="" tooltipid={
                s"tooltip-key-${sm.node.main.id.value}"
              }></span>
                    <span class="tooltipContent" id={s"tooltip-key-${sm.node.main.id.value}"}>
                      Certificate for this node has been reset, next inventory will be trusted automatically
                    </span>
                  </span>
            case _                                 => // not accepted inventory? Should not get there
              NodeSeq.Empty
          }
          val nodeId      = sm.node.main.id
          val publicKeyId = s"publicKey-${nodeId.value}"
          val cfKeyHash   = nodeInfoService.getNodeInfo(nodeId).either.runNow match {
            case Right(Some(nodeInfo)) if (nodeInfo.keyHashCfengine.nonEmpty) =>
              <div><label>Key hash:</label> <samp>{nodeInfo.keyHashCfengine}</samp></div>
            case _                                                            => NodeSeq.Empty
          }
          val curlHash    = nodeInfoService.getNodeInfo(nodeId).either.runNow match {
            case Right(Some(nodeInfo)) if (nodeInfo.keyHashCfengine.nonEmpty) =>
              <div><label>Key hash:</label> <samp>sha256//{nodeInfo.keyHashBase64Sha256}</samp></div>
            case _                                                            => NodeSeq.Empty
          }

          val tokenKind = agent.securityToken match {
            case _: PublicKey   => "Public key"
            case _: Certificate => "Certificate"
          }
          <div class="security-info">
                {
            agent.securityToken match {
              case _: PublicKey   => NodeSeq.Empty
              case c: Certificate =>
                c.cert.either.runNow match {
                  case Left(e)     => <span title={e.fullMsg}>Error while reading certificate information</span>
                  case Right(cert) => (
                    <div><label>SHA1 Fingerprint: </label> <samp>{
                      SHA1.hash(cert.getEncoded).grouped(2).mkString(":")
                    }</samp></div>
                        <div><label>Expiration date: </label> {
                      DateFormaterService.getDisplayDate(new DateTime(cert.getNotAfter))
                    }</div>
                  )
                }
            }
          }
                {curlHash}
                {cfKeyHash}
                <button type="button" class="toggle-security-info btn btn-default" onclick={
            s"$$('#${publicKeyId}').toggle(300); $$(this).toggleClass('opened'); return false;"
          }> <b>{tokenKind}</b> {checked}</button>
                <pre id={publicKeyId} class="display-keys" style="display:none;"><div>{agent.securityToken.key}</div></pre>{
            Script(OnLoad(JsRaw(s"""createTooltip();""")))
          }
              </div>
        case None        => NodeSeq.Empty
      }
    }
        </div>

        <div class="status-info col-lg-6 col-sm-5 col-xs-12">
          <h3>Status information</h3>
          <div class="node-compliance-bar"></div>
          <div>
            <label>Inventory created (node local time):</label>  {
      sm.node.inventoryDate.map(DateFormaterService.getDisplayDate(_)).getOrElse("Unknown")
    }
          </div>
          <div>
            <label>Inventory received:</label>  {
      sm.node.receiveDate.map(DateFormaterService.getDisplayDate(_)).getOrElse("Unknown")
    }
          </div>
          <div>
            {
      creationDate.map { creation =>
        <xml:group><label>Accepted since:</label> {DateFormaterService.getDisplayDate(creation)}</xml:group>
      }.getOrElse(NodeSeq.Empty)
    }
          </div>
          <div>
            <label>Software updates available:</label> {sm.node.softwareUpdates.size}
          </div>
        </div>

        <div class="accounts-info col-xs-12">
          <h3>Accounts</h3>
          <div>
            <label>Local account(s):</label> {displayAccounts(sm.node)}
          </div>
        </div>
        {deleteButton}
      </div>
    </div> ++ Script(OnLoad(JsRaw(s"""
        $$(".node-compliance-bar").html(buildComplianceBar(${compliance.toJsCmd}))
      """)))
  }

  private def htmlId(jsId: JsNodeId, prefix: String):   String = prefix + jsId.toString
  private def htmlId_#(jsId: JsNodeId, prefix: String): String = "#" + prefix + jsId.toString

  // Display the role of the node
  private def displayServerRole(sm: FullInventory, inventoryStatus: InventoryStatus): NodeSeq = {
    val nodeId = sm.node.main.id
    inventoryStatus match {
      case AcceptedInventory =>
        val nodeInfoBox = nodeInfoService.getNodeInfo(nodeId).either.runNow
        nodeInfoBox match {
          case Right(Some(nodeInfo)) =>
            val kind = {
              nodeInfo.nodeKind match {
                case NodeKind.Root  => "server"
                case NodeKind.Relay => "relay server"
                case NodeKind.Node  => "node"
              }
            }

            <div><label>Role:</label> Rudder {kind}</div>
          case Right(None)           =>
            logger.error(s"Could not fetch node details for node with id ${sm.node.main.id}")
            <div class="error"><label>Role:</label> Could not fetch Role for this node</div>
          case Left(err)             =>
            val e = s"Could not fetch node details for node with id ${sm.node.main.id}: ${err.fullMsg}"
            logger.error(e)
            <div class="error"><label>Role:</label> Could not fetch Role for this node</div>
        }
      case RemovedInventory  =>
        <div><label>Role:</label> Deleted node</div>
      case PendingInventory  =>
        <div><label>Role:</label> Pending node</div>
    }
  }

  private def displayPolicyServerInfos(sm: FullInventory): NodeSeq = {
    nodeInfoService.getNodeInfo(sm.node.main.policyServerId).either.runNow match {
      case Left(err)                        =>
        val e = s"Could not fetch policy server details (id '${sm.node.main.policyServerId.value}') for node '${escape(
            sm.node.main.hostname
          )}' ('${sm.node.main.id.value}'): ${err.fullMsg}"
        logger.error(e)
        <div class="error"><label>Policy Server:</label> Could not fetch details about the policy server</div>
      case Right(Some(policyServerDetails)) =>
        <div><label>Policy Server:</label> <a href={linkUtil.baseNodeLink(policyServerDetails.id)}  onclick={
          s"updateNodeIdAndReload('${policyServerDetails.id.value}')"
        }>{escape(policyServerDetails.hostname)}</a></div>
      case Right(None)                      =>
        logger.error(
          s"Could not fetch policy server details (id '${sm.node.main.policyServerId.value}') for node '${sm.node.main.hostname}' ('${sm.node.main.id.value}')"
        )
        <div class="error"><label>Policy Server:</label> Could not fetch details about the policy server</div>
    }
  }

  private def displayMachineType(opt: Option[MachineInventory]): NodeSeq = {
    opt match {
      case None          => NodeSeq.Empty
      case Some(machine) => (
        machine.machineType match {
          case PhysicalMachineType        => Text("Physical machine")
          case VirtualMachineType(vmType) => Text("Virtual machine (%s)".format(S.?("vm.type." + vmType.name)))
        }
      )
    }
  }

  // show a comma separated list with description in tooltip

  private def displayAccounts(node: NodeInventory): NodeSeq = {
    escapeHTML {
      if (node.accounts.isEmpty) {
        "None"
      } else {
        node.accounts.sortWith(_ < _).mkString(", ")
      }
    }
  }

  private def displayTabGrid[T](
      jsId:  JsNodeId
  )(eltName: String, optSeq: Box[Seq[T]], title: Option[String] = None)(columns: List[(String, T => NodeSeq)]) = {
    <div id={htmlId(jsId, "sd_" + eltName + "_")} class="sInventory overflow_auto">{
      optSeq match {
        case Empty                                           =>
          <div class="col-xs-12 alert alert-warning">
            <span>No matching components detected on this node</span>
          </div>
        case Failure(m, _, _)                                => <span class="error">Error when trying to fetch file systems. Reported message: {m}</span>
        case Full(seq) if (seq.isEmpty && eltName != "soft") =>
          <div class="col-xs-12 alert alert-warning">
            <span>No matching components detected on this node</span>
          </div>
        case Full(seq)                                       =>
          <table cellspacing="0" id={htmlId(jsId, eltName + "_")} class="tablewidth">
          {
            title match {
              case None        => NodeSeq.Empty
              case Some(title) => <div style="text-align:center"><b>{title}</b></div>
            }
          }
          <thead>
          <tr class="head">
          </tr>
            <tr class="head">{
            columns.map(h => <th>{h._1}</th>).toSeq
          }</tr>
          </thead>
          <tbody class="toggle-color">{
            seq.flatMap(x => <tr>{columns.flatMap { case (header, renderLine) => <td>{renderLine(x)}</td> }}</tr>)
          }</tbody>
          </table>
      }
    }<div id={htmlId(jsId, eltName + "_grid_") + "_paginate_area"} class="paginate"/>
    </div>
  }

  private def displayTabSoftware(jsId: JsNodeId): NodeSeq = {
    displayTabGrid(jsId)(
      "soft",
      // do not retrieve software here
      Full(Seq())
    ) {
      ("Name", { x: Software => ?(x.name) }) ::
      ("Version", { x: Software => ?(x.version.map(_.value)) }) ::
      ("Description", { x: Software => ?(x.description) }) ::
      Nil
    }
  }

  private def displayTabSoftwareUpdates(jsId: JsNodeId, sm: FullInventory): NodeSeq = {
    displayTabGrid(jsId)("softUpdates", Full(sm.node.softwareUpdates)) {
      ("Name", { x: SoftwareUpdate => Text(x.name) }) ::
      ("Version", { x: SoftwareUpdate => Text(x.version.getOrElse("-")) }) ::
      ("Arch", { x: SoftwareUpdate => Text(x.arch.getOrElse("-")) }) ::
      ("From", { x: SoftwareUpdate => Text(x.from.getOrElse("-")) }) ::
      ("Kind", { x: SoftwareUpdate => Text(x.kind.name) }) ::
      ("Source", { x: SoftwareUpdate => ?(x.source) }) ::
      Nil
    }
  }

  private def displayTabNetworks(jsId: JsNodeId, sm: FullInventory): NodeSeq = {
    displayTabGrid(jsId)("net", Full(sm.node.networks)) {
      ("Interface", { x: Network => escapeHTML(x.name) }) ::
      ("IP address", { x: Network => (x.ifAddresses.map { y => (<div>{escapeHTML(y.getHostAddress)}</div>) }): NodeSeq }) ::
      ("Mask", { x: Network => (x.ifMask.map { y => (<div>{escapeHTML(y.getHostAddress)}</div>) }): NodeSeq }) ::
      ("Network", { x: Network => (x.ifSubnet.map { y => (<div>{escapeHTML(y.getHostAddress)}</div>) }): NodeSeq }) ::
      ("Gateway", { x: Network => (x.ifGateway.map { y => (<div>{escapeHTML(y.getHostAddress)}</div>) }): NodeSeq }) ::
      ("DHCP server", { x: Network => escapeHTML(x.ifDhcp.map(_.getHostAddress).mkString(", ")) }) ::
      ("MAC address", { x: Network => ?(x.macAddress) }) ::
      ("Type", { x: Network => ?(x.ifType) }) ::
      ("Speed", { x: Network => ?(x.speed) }) ::
      ("Status", { x: Network => ?(x.status) }) ::
      Nil
    }
  }

  private def displayTabFilesystems(jsId: JsNodeId, sm: FullInventory): NodeSeq = {
    displayTabGrid(jsId)("fs", Full(sm.node.fileSystems)) {
      ("Mount point", { x: FileSystem => escapeHTML(x.mountPoint) }) ::
      ("Filesystem", { x: FileSystem => ?(x.name) }) ::
      ("Free space", { x: FileSystem => ?(x.freeSpace.map(_.toStringMo)) }) ::
      ("Total space", { x: FileSystem => ?(x.totalSpace.map(_.toStringMo)) }) ::
      ("File count", { x: FileSystem => ?(x.fileCount.map(_.toString)) }) ::
      Nil
    }
  }

  private def displayTabVariable(jsId: JsNodeId, sm: FullInventory): NodeSeq = {

    val title = sm.node.inventoryDate.map(date => s"Environment variable status on ${DateFormaterService.getDisplayDate(date)}")
    displayTabGrid(jsId)("var", Full(sm.node.environmentVariables), title) {
      ("Name", { x: EnvironmentVariable => escapeHTML(x.name) }) ::
      ("Value", { x: EnvironmentVariable => escapeHTML(x.value.getOrElse("Unspecified")) }) ::
      Nil
    }
  }

  def displayTabProperties(jsId: JsNodeId, node: NodeInfo): NodeSeq = {
    import com.normation.rudder.AuthorizationType

    val nodeId        = node.id.value
    val userHasRights = CurrentUser.checkRights(AuthorizationType.Node.Edit)
    def tabProperties = ChooseTemplate(List("templates-hidden", "components", "ComponentNodeProperties"), "nodeproperties-tab")
    val tabId         = htmlId(jsId, "sd_props_")
    val css: CssSel = "#tabPropsId [id]" #> tabId
    css(tabProperties) ++ Script(OnLoad(JsRaw(s"""
        angular.bootstrap('#nodeProp', ['nodeProperties']);
        var scope  = angular.element($$("#nodeProp")).scope();
        scope.$$apply(function(){
          scope.init("${nodeId}",${userHasRights},'node');
        });
      """)))
  }

  private def displayTabProcess(jsId: JsNodeId, sm: FullInventory): NodeSeq = {
    val title = sm.node.inventoryDate.map(date => s"Process status on ${DateFormaterService.getDisplayDate(date)}")
    displayTabGrid(jsId)("process", Full(sm.node.processes), title) {
      ("User", { x: Process => ?(x.user) }) ::
      ("PID", { x: Process => escapeHTML(x.pid.toString()) }) ::
      ("% CPU", { x: Process => ?(x.cpuUsage.map(_.toString())) }) ::
      ("% Memory", { x: Process => ?(x.memory.map(_.toString())) }) ::
      ("Virtual memory", { x: Process => ?(x.virtualMemory.map(memory => MemorySize(memory.toLong).toStringMo)) }) ::
      ("TTY", { x: Process => ?(x.tty) }) ::
      ("Started on", { x: Process => ?(x.started) }) ::
      ("Command", { x: Process => ?(x.commandName) }) ::
      Nil
    }
  }

  private def displayTabVM(jsId: JsNodeId, sm: FullInventory): NodeSeq = {
    displayTabGrid(jsId)("vm", Full(sm.node.vms)) {
      ("Name", { x: VirtualMachine => ?(x.name) }) ::
      ("Type", { x: VirtualMachine => ?(x.vmtype) }) ::
      ("SubSystem", { x: VirtualMachine => ?(x.subsystem) }) ::
      ("Uuid", { x: VirtualMachine => escapeHTML(x.uuid.value) }) ::
      ("Status", { x: VirtualMachine => ?(x.status) }) ::
      ("Owner", { x: VirtualMachine => ?(x.owner) }) ::
      ("# Cpu", { x: VirtualMachine => ?(x.vcpu.map(_.toString())) }) ::
      ("Memory", { x: VirtualMachine => ?(x.memory) }) ::
      Nil
    }
  }

  private def displayTabBios(jsId: JsNodeId, sm: FullInventory): NodeSeq = {
    displayTabGrid(jsId)("bios", sm.machine.map(fm => fm.bios)) {
      ("Name", { x: Bios => escapeHTML(x.name) }) ::
      ("Editor", { x: Bios => ?(x.editor.map(_.name)) }) ::
      ("Version", { x: Bios => ?(x.version.map(_.value)) }) ::
      ("Release Date", { x: Bios => ?(x.releaseDate.map(DateFormaterService.getDisplayDate(_))) }) ::
      Nil
    }
  }

  private def displayTabControllers(jsId: JsNodeId, sm: FullInventory): NodeSeq = {
    displayTabGrid(jsId)("controllers", sm.machine.map(fm => fm.controllers)) {
      ("Name", { x: Controller => escapeHTML(x.name) }) ::
      ("Manufacturer", { x: Controller => ?(x.manufacturer.map(_.name)) }) ::
      ("Type", { x: Controller => ?(x.cType) }) ::
      ("Quantity", { x: Controller => escapeHTML(x.quantity.toString) }) ::
      Nil
    }
  }

  private def displayTabMemories(jsId: JsNodeId, sm: FullInventory): NodeSeq = {
    displayTabGrid(jsId)("memories", sm.machine.map(fm => fm.memories)) {
      ("Slot", { x: MemorySlot => escapeHTML(x.slotNumber) }) ::
      ("Capacity", { x: MemorySlot => ?(x.capacity.map(_.toStringMo)) }) ::
      ("Description", { x: MemorySlot => ?(x.description) }) ::
      ("Serial Number", { x: MemorySlot => ?(x.serialNumber) }) ::
      ("Speed", { x: MemorySlot => ?(x.speed) }) ::
      ("Type", { x: MemorySlot => ?(x.memType) }) ::
      ("Quantity", { x: MemorySlot => escapeHTML(x.quantity.toString) }) ::
      Nil
    }
  }

  private def displayTabPorts(jsId: JsNodeId, sm: FullInventory): NodeSeq = {
    displayTabGrid(jsId)("ports", sm.machine.map(fm => fm.ports)) {
      ("Name", { x: Port => escapeHTML(x.name) }) ::
      ("Type", { x: Port => ?(x.pType) }) ::
      ("Description", { x: Port => ?(x.description) }) ::
      ("Quantity", { x: Port => escapeHTML(x.quantity.toString) }) ::
      Nil
    }
  }

  private def displayTabProcessors(jsId: JsNodeId, sm: FullInventory): NodeSeq = {
    displayTabGrid(jsId)("processors", sm.machine.map(fm => fm.processors)) {
      ("Name", { x: Processor => escapeHTML(x.name) }) ::
      ("Speed", { x: Processor => ?(x.speed.map(_.toString)) }) ::
      ("Model", { x: Processor => ?(x.model.map(_.toString())) }) ::
      ("Family", { x: Processor => ?(x.family.map(_.toString())) }) ::
      ("Family Name", { x: Processor => ?(x.familyName) }) ::
      ("Manufacturer", { x: Processor => ?(x.manufacturer.map(_.name)) }) ::
      ("Thread", { x: Processor => ?(x.thread.map(_.toString())) }) ::
      ("Core", { x: Processor => ?(x.core.map(_.toString())) }) ::
      ("CPUID", { x: Processor => ?(x.cpuid) }) ::
      ("Architecture", { x: Processor => ?(x.arch) }) ::
      ("Stepping", { x: Processor => ?(x.stepping.map(_.toString)) }) ::
      ("Quantity", { x: Processor => escapeHTML(x.quantity.toString) }) ::
      Nil
    }
  }

  private def displayTabSlots(jsId: JsNodeId, sm: FullInventory): NodeSeq = {
    displayTabGrid(jsId)("slots", sm.machine.map(fm => fm.slots)) {
      ("Name", { x: Slot => escapeHTML(x.name) }) ::
      ("Description", { x: Slot => ?(x.description) }) ::
      ("Status", { x: Slot => ?(x.status) }) ::
      ("Quantity", { x: Slot => escapeHTML(x.quantity.toString) }) ::
      Nil
    }
  }

  private def displayTabSounds(jsId: JsNodeId, sm: FullInventory): NodeSeq = {
    displayTabGrid(jsId)("sounds", sm.machine.map(fm => fm.sounds)) {
      ("Name", { x: Sound => escapeHTML(x.name) }) ::
      ("Description", { x: Sound => ?(x.description) }) ::
      ("Quantity", { x: Sound => escapeHTML(x.quantity.toString) }) ::
      Nil
    }
  }

  private def displayTabStorages(jsId: JsNodeId, sm: FullInventory): NodeSeq = {
    displayTabGrid(jsId)("storages", sm.machine.map(fm => fm.storages)) {
      ("Name", { x: Storage => escapeHTML(x.name) }) ::
      ("Description", { x: Storage => ?(x.description) }) ::
      ("Size", { x: Storage => ?(x.size.map(_.toStringMo)) }) ::
      ("Firmware", { x: Storage => ?(x.firmware) }) ::
      ("Manufacturer", { x: Storage => ?(x.manufacturer.map(_.name)) }) ::
      ("Model", { x: Storage => ?(x.model) }) ::
      ("Serial", { x: Storage => ?(x.serialNumber) }) ::
      ("Type", { x: Storage => ?(x.sType) }) ::
      ("Quantity", { x: Storage => escapeHTML(x.quantity.toString) }) ::
      Nil
    }
  }

  private def displayTabVideos(jsId: JsNodeId, sm: FullInventory): NodeSeq = {
    displayTabGrid(jsId)("videos", sm.machine.map(fm => fm.videos)) {
      ("Name", { x: Video => escapeHTML(x.name) }) ::
      ("Chipset", { x: Video => ?(x.chipset) }) ::
      ("Memory", { x: Video => ?(x.memory.map(_.toStringMo)) }) ::
      ("Resolution", { x: Video => ?(x.resolution) }) ::
      ("Quantity", { x: Video => escapeHTML(x.quantity.toString) }) ::
      Nil
    }
  }

  private[this] def showDeleteButton(nodeId: NodeId) = {
    def toggleDeletion(): JsCmd = {
      JsRaw(""" $('#deleteButton').toggle(300); $('#confirmDeletion').toggle(300) """)
    }
    SHtml.ajaxButton(
      "Delete",
      () => { toggleDeletion() },
      ("id", "deleteButton"),
      ("class", "btn btn-danger")
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
      SHtml.ajaxButton("Cancel", () => { toggleDeletion() }, ("class", "btn btn-default"))
    }
          {
      SHtml.ajaxButton("Confirm", () => { removeNode(nodeId) }, ("class", "btn btn-danger"))
    }
        </span>
      </div>
    </div>
    </div>
</div>
  }

  private[this] def removeNode(nodeId: NodeId): JsCmd = {
    val modId = ModificationId(uuidGen.newUuid)
    removeNodeService.removeNodePure(nodeId, RudderConfig.RUDDER_DEFAULT_DELETE_NODE_MODE, modId, CurrentUser.actor).toBox match {
      case Full(_) =>
        asyncDeploymentAgent ! AutomaticStartDeployment(modId, CurrentUser.actor)
        onSuccess
      case eb: EmptyBox =>
        val message = s"There was an error while deleting Node '${nodeId.value}'"
        val e       = eb ?~! message
        logger.error(e.messageChain)
        onFailure(nodeId, message, e.messageChain, None)
    }
  }

  private[this] def onFailure(
      nodeId:    NodeId,
      message:   String,
      details:   String,
      hookError: Option[HookReturnCode.Error]
  ): JsCmd = {

    val hookDetails = {
      hookError match {
        case Some(error) =>
          {
            if (error.stdout.size > 0) {
              <b>stdout</b>
            <pre>{error.stdout}</pre>
            } else {
              NodeSeq.Empty
            }
          } ++ {
            if (error.stderr.size > 0) {
              <b>stderr</b>
            <pre>{error.stderr}</pre>
            } else {
              NodeSeq.Empty
            }
          }
        case None        => NodeSeq.Empty
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
    JsRaw("""$('#errorPopupHtmlId').bsModal('hide');""") &
    SetHtml(errorPopupHtmlId, popupHtml) &
    JsRaw(s""" callPopupWithTimeout(200,"${errorPopupHtmlId}")""")
  }

  private[this] def onSuccess: JsCmd = {
    val popupHtml = {
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
    }
    JsRaw(s"updateHashString('nodeId', undefined); forceParseHashtag()") &
    SetHtml("serverDetails", NodeSeq.Empty) &
    JsRaw("""$('#successPopupHtmlId').bsModal('hide');""") &
    SetHtml(successPopupHtmlId, popupHtml) &
    JsRaw(s""" callPopupWithTimeout(200,"${successPopupHtmlId}") """)
  }

  private[this] def isRootNode(n: NodeId): Boolean = {
    return n.value.equals("root");
  }

  import com.normation.rudder.domain.nodes.NodeState
  private[this] def getNodeState(nodeState: NodeState): String = {
    S.?(s"node.states.${nodeState.name}")
  }
}
