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
import com.normation.box.*
import com.normation.cfclerk.domain.HashAlgoConstraint.SHA1
import com.normation.eventlog.ModificationId
import com.normation.inventory.domain.*
import com.normation.rudder.AuthorizationType
import com.normation.rudder.batch.AutomaticStartDeployment
import com.normation.rudder.domain.logger.NodeLoggerPure
import com.normation.rudder.domain.nodes.NodeKind
import com.normation.rudder.domain.policies.GlobalPolicyMode
import com.normation.rudder.domain.policies.PolicyModeOverrides.*
import com.normation.rudder.facts.nodes.ChangeContext
import com.normation.rudder.facts.nodes.CoreNodeFact
import com.normation.rudder.facts.nodes.MinimalNodeFactInterface
import com.normation.rudder.facts.nodes.NodeFact
import com.normation.rudder.facts.nodes.QueryContext
import com.normation.rudder.facts.nodes.SecurityTag
import com.normation.rudder.facts.nodes.SelectFacts
import com.normation.rudder.services.servers.DeleteMode
import com.normation.rudder.users.CurrentUser
import com.normation.rudder.web.model.JsNodeId
import com.normation.rudder.web.snippet.RegisterToasts
import com.normation.rudder.web.snippet.ToastNotification
import com.normation.rudder.web.snippet.WithNonce
import com.normation.utils.DateFormaterService
import com.normation.zio.*
import com.softwaremill.quicklens.*
import io.scalaland.chimney.Transformer
import io.scalaland.chimney.syntax.*
import java.time.Instant
import net.liftweb.common.*
import net.liftweb.http.*
import net.liftweb.http.js.*
import net.liftweb.http.js.JE.JsArray
import net.liftweb.http.js.JE.JsRaw
import net.liftweb.http.js.JE.JsVar
import net.liftweb.http.js.JE.Str
import net.liftweb.http.js.JsCmds.*
import net.liftweb.util.*
import net.liftweb.util.Helpers.*
import org.apache.commons.text.StringEscapeUtils
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import scala.xml.*
import zio.json.*
import zio.syntax.*

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
  import DisplayNodeJson.*

  private val nodeFactRepository   = RudderConfig.nodeFactRepository
  private val removeNodeService    = RudderConfig.removeNodeService
  private val asyncDeploymentAgent = RudderConfig.asyncDeploymentAgent
  private val uuidGen              = RudderConfig.stringUuidGenerator
  private val linkUtil             = RudderConfig.linkUtil

  private def escapeJs(in:   String):         JsExp   = Str(StringEscapeUtils.escapeEcmaScript(in))
  private def escapeHTML(in: String):         NodeSeq = Text(StringEscapeUtils.escapeHtml4(in))
  private def ?(in:          Option[String]): NodeSeq = in.map(escapeHTML).getOrElse(NodeSeq.Empty)

  private def loadSoftware(jsId: JsNodeId)(nodeId: String): JsCmd = {
    implicit val attrs = SelectFacts.none.copy(software = SelectFacts.none.software.toRetrieve)
    implicit val qc    = CurrentUser.queryContext
    (for {
      seq       <- nodeFactRepository.slowGet(NodeId(nodeId)).map(_.toList.flatMap(_.software.map(_.toSoftware)))
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
      }*)
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
            "sDom": '<"dataTables_wrapper_top d-flex" f <"d-flex ms-auto my-auto" B>>rt<"dataTables_wrapper_bottom"lip>',
            "buttons" : [ csvButtonConfig("node_${nodeId}_software", "btn-sm") ],
            "lengthMenu": [ [10, 25, 50, 100, 500, 1000, -1], [10, 25, 50, 100, 500, 1000, "All"] ],
            "pageLength": 25
        });
        $$('.dataTables_filter input').attr("placeholder", "Filter");
            """)).toBox match { // JsRaw ok, escaped
      case Empty            => Alert("No software found for that server")
      case Failure(m, _, _) => Alert("Error when trying to fetch software. Reported message: " + m)
      case Full(js)         => js
    }
  }

  def jsInit(nodeId: NodeId, salt: String = ""): JsCmd = {
    val jsId           = JsNodeId(nodeId, salt)
    val softGridDataId = htmlId(jsId, "soft_grid_data_")
    val softPanelId    = "soft_tab"
    val eltIdswidth    =
      List(("process", List("50", "50", "70", "85", "120", "50", "100", "850"), 1), ("var", List("200", "800"), 0))
    val eltIds         = List(
      "os",
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

    JsRaw("var " + softGridDataId + "= null") & // JsRaw ok, escaped
    OnLoad({
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
                "sDom": '<"dataTables_wrapper_top d-flex" f <"d-flex ms-auto my-auto" B>>rt<"dataTables_wrapper_bottom"lip>',
                "buttons" : [ csvButtonConfig("node_${nodeId.value}_${i}", "btn-sm") ],
                "lengthMenu": [ [10, 25, 50, 100, 500, 1000, -1], [10, 25, 50, 100, 500, 1000, "All"] ],
                "pageLength": 25
              });

              $$('.dataTables_filter input').attr("placeholder", "Filter");
                   | """.stripMargin('|')): JsCmd
        }.reduceLeft((i, acc) => acc & i) // JsRaw ok, escaped
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
                "sDom": '<"dataTables_wrapper_top d-flex" f <"d-flex ms-auto my-auto" B>>rt<"dataTables_wrapper_bottom"lip>',
                "buttons" : [ csvButtonConfig("node_${nodeId.value}_${id}", "btn-sm") ],
                "lengthMenu": [ [10, 25, 50, 100, 500, 1000, -1], [10, 25, 50, 100, 500, 1000, "All"] ],
                "pageLength": 25
              });
              $$('.dataTables_filter input').attr("placeholder", "Filter");
           """): JsCmd // JsRaw ok, no user inputs
        }.reduceLeft((i, acc) => acc & i)
      } &
      // for the software tab, we check for the panel id, and the firstChild id
      // if the firstChild.id == softGridId, then it hasn't been loaded, otherwise it is softGridId_wrapper
      JsRaw(s"""
        $$("#${softPanelId}").click(function() {
            ${SHtml.ajaxCall(JsRaw("'" + StringEscapeUtils.escapeEcmaScript(nodeId.value) + "'"), loadSoftware(jsId))._2.toJsCmd}
        });
        """) // JsRaw ok, escaped
    )
  }

  def showInventoryVerticalMenu(sm: FullInventory, node: CoreNodeFact, salt: String = ""): NodeSeq = {
    val jsId = JsNodeId(sm.node.main.id, salt)
    val mainTabDeclaration: List[NodeSeq] = {
      <li class="nav-item">
        <button class="nav-link active" data-bs-toggle="tab" data-bs-target={
        htmlId_#(jsId, "sd_os_")
      } type="button" role="tab" aria-controls={htmlId_#(jsId, "sd_os_")}>Operating system</button>
      </li> ::
      <li class="nav-item">
          <button class="nav-link" data-bs-toggle="tab" data-bs-target={
        htmlId_#(jsId, "sd_fs_")
      } type="button" role="tab" aria-controls={htmlId_#(jsId, "sd_fs_")}>File systems</button>
      </li> ::
      <li class="nav-item">
          <button class="nav-link" data-bs-toggle="tab" data-bs-target={
        htmlId_#(jsId, "sd_net_")
      } type="button" role="tab" aria-controls={htmlId_#(jsId, "sd_net_")}>Network interfaces</button>
        </li> ::
      <li class="nav-item" id="soft_tab">
          <button class="nav-link" data-bs-toggle="tab" data-bs-target={
        htmlId_#(jsId, "sd_soft_")
      } type="button" role="tab" aria-controls={htmlId_#(jsId, "sd_soft_")}>Software</button>
        </li> ::
      <li class="nav-item">
          <button class="nav-link" data-bs-toggle="tab" data-bs-target={
        htmlId_#(jsId, "sd_softUpdates_")
      } type="button" role="tab" aria-controls={htmlId_#(jsId, "sd_softUpdates_")}>Software updates</button>
        </li> ::
      <li class="nav-item">
          <button class="nav-link" data-bs-toggle="tab" data-bs-target={
        htmlId_#(jsId, "sd_var_")
      } type="button" role="tab" aria-controls={htmlId_#(jsId, "sd_var_")}>Environment</button>
        </li> ::
      // Hardware content
      <li class="nav-item">
          <button class="nav-link" data-bs-toggle="tab" data-bs-target={
        htmlId_#(jsId, "sd_bios_")
      } type="button" role="tab" aria-controls={htmlId_#(jsId, "sd_bios_")}>BIOS</button>
        </li> ::
      <li class="nav-item">
          <button class="nav-link" data-bs-toggle="tab" data-bs-target={
        htmlId_#(jsId, "sd_controllers_")
      } type="button" role="tab" aria-controls={htmlId_#(jsId, "sd_controllers_")}>Controllers</button>
        </li> ::
      <li class="nav-item">
          <button class="nav-link" data-bs-toggle="tab" data-bs-target={
        htmlId_#(jsId, "sd_memories_")
      } type="button" role="tab" aria-controls={htmlId_#(jsId, "sd_memories_")}>Memory</button>
        </li> ::
      <li class="nav-item">
          <button class="nav-link" data-bs-toggle="tab" data-bs-target={
        htmlId_#(jsId, "sd_ports_")
      } type="button" role="tab" aria-controls={htmlId_#(jsId, "sd_ports_")}>Ports</button>
        </li> ::
      <li class="nav-item">
          <button class="nav-link" data-bs-toggle="tab" data-bs-target={
        htmlId_#(jsId, "sd_processors_")
      } type="button" role="tab" aria-controls={htmlId_#(jsId, "sd_processors_")}>Processors</button>
        </li> ::
      <li class="nav-item">
          <button class="nav-link" data-bs-toggle="tab" data-bs-target={
        htmlId_#(jsId, "sd_slots_")
      } type="button" role="tab" aria-controls={htmlId_#(jsId, "sd_slots_")}>Slots</button>
        </li> ::
      <li class="nav-item">
          <button class="nav-link" data-bs-toggle="tab" data-bs-target={
        htmlId_#(jsId, "sd_sounds_")
      } type="button" role="tab" aria-controls={htmlId_#(jsId, "sd_sounds_")}>Sound</button>
        </li> ::
      <li class="nav-item">
          <button class="nav-link" data-bs-toggle="tab" data-bs-target={
        htmlId_#(jsId, "sd_storages_")
      } type="button" role="tab" aria-controls={htmlId_#(jsId, "sd_storages_")}>Storage</button>
        </li> ::
      <li class="nav-item">
          <button class="nav-link" data-bs-toggle="tab" data-bs-target={
        htmlId_#(jsId, "sd_videos_")
      } type="button" role="tab" aria-controls={htmlId_#(jsId, "sd_videos_")}>Video</button>
        </li> ::
      //
      <li class="nav-item">
          <button class="nav-link" data-bs-toggle="tab" data-bs-target={
        htmlId_#(jsId, "sd_process_")
      } type="button" role="tab" aria-controls={htmlId_#(jsId, "sd_process_")}>Processes</button>
        </li> ::
      <li class="nav-item">
          <button class="nav-link" data-bs-toggle="tab" data-bs-target={
        htmlId_#(jsId, "sd_vm_")
      } type="button" role="tab" aria-controls={htmlId_#(jsId, "sd_vm_")}>Virtual machines</button>
        </li> ::
      Nil
    }

    val tabContent = {
      displayTabOS(jsId, sm, node) ::
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

    <div id={tabId} class="sInventory d-flex">
      <ul class="list-tabs-inventory nav flex-column pe-3" aria-orientation="vertical">{mainTabDeclaration}</ul>
      <div class="tab-content">{tabContent.flatten}</div>
    </div> ++ WithNonce.scriptWithNonce(
      Script(
        OnLoad(JsRaw(s"$$('.sInventory .tab-content > .tab-pane:first-child').addClass('active');"))
      )
    ) // JsRaw OK, const

  }

  /**
   * Show the details in a panned version, with Node Summary, Inventory, Network, Software
   * Should be used with jsInit(dn:String, softIds:Seq[SoftwareUuid], salt:String="")
   */
  def showPannedContent(
      nodeFact:   NodeFact,
      globalMode: GlobalPolicyMode,
      salt:       String = ""
  )(implicit qc: QueryContext): NodeSeq = {
    val jsId      = JsNodeId(nodeFact.id, salt)
    val detailsId = htmlId(jsId, "details_")
    val sm        = nodeFact.toFullInventory

    <div id={detailsId}>
      <div class="main-header">
        {showNodeHeader(sm, nodeFact)}
      </div>
      <div class="tabs">
        <div class="main-navbar">
          <ul class="nav nav-underline mx-0">
            <li class="nav-item">
              <button class="nav-link active" data-bs-toggle="tab" data-bs-target={
      htmlId_#(jsId, "node_summary_")
    } type="button" role="tab" aria-controls={htmlId_#(jsId, "node_summary_")} aria-selected="false">Summary</button>
            </li>
            <li class="nav-item">
              <button class="nav-link" data-bs-toggle="tab" data-bs-target={
      htmlId_#(jsId, "node_inventory_")
    } type="button" role="tab" aria-controls={htmlId_#(jsId, "node_inventory_")} aria-selected="false">Inventory</button>
            </li>
          </ul>
        </div>
        <div class="tab-content">
          <div id={htmlId(jsId, "node_summary_")} class="tab-pane active show">
            <div class="d-flex px-3 py-2">
              {showNodeDetails(nodeFact, globalMode, None, salt)}
            </div>
          </div>
          <div id={htmlId(jsId, "node_inventory_")} class="tab-pane p-3">
            {showInventoryVerticalMenu(sm, nodeFact.toCore)}
          </div>
        </div>
      </div>
    </div>
  }

  def showNodeHeader(sm: FullInventory, node: NodeFact): NodeSeq = {
    val machineTooltip: String = {
      s"""
         |<h4>Machine details</h4>
         |<div class='tooltip-content'>
         |  <ul>
         |    <li><b>Type:</b> ${displayMachineType(sm.machine)}</li>
         |    <li><b>Total physical memory (RAM):</b> ${StringEscapeUtils.escapeHtml4(
          sm.node.ram.map(_.toStringMo).getOrElse("-")
        )}</li>
         |    <li><b>Manufacturer:</b> ${StringEscapeUtils.escapeHtml4(
          sm.machine.flatMap(x => x.manufacturer).map(x => x.name).getOrElse("-")
        )}</li>
         |    <li><b>Total swap space:</b> ${StringEscapeUtils.escapeHtml4(sm.node.swap.map(_.toStringMo).getOrElse("-"))}</li>
         |    <li><b>System serial number:</b> ${StringEscapeUtils.escapeHtml4(
          sm.machine.flatMap(x => x.systemSerialNumber).getOrElse("-")
        )}</li>
         |    <li><b>Time zone:</b> ${StringEscapeUtils.escapeHtml4(
          sm.node.timezone
            .map(x => if (x.name.toLowerCase == "utc") "UTC" else s"${x.name} (UTC ${x.offset})")
            .getOrElse("unknown")
        )}</li>
         |    <li>${sm.machine
          .map(_.id.value)
          .map(machineId => "<b>Machine ID:</b> " ++ { StringEscapeUtils.escapeHtml4(machineId) })
          .getOrElse("<span class='error'>Machine Information are missing for that node</span>")}</li>
         |  </ul>
         |</div>
         |""".stripMargin.replaceAll("\n", " ")
    }

    val deleteBtn = {
      sm.node.main.status match {
        case AcceptedInventory =>
          <lift:authz role="node_write">
            {
            if (!isRootNode(sm.node.main.id)) {
              <button type="button" class="btn btn-danger btn-icon" data-bs-toggle="modal" data-bs-target="#nodeDeleteModal">
                Delete
                <i class="fa fa-times-circle"></i>
              </button>
            } else { NodeSeq.Empty }
          }
          </lift:authz>
        case _                 => NodeSeq.Empty
      }
    }

    val osTooltip: String = {
      s"""
         |<h4>Operating system details</h4>
         |<div class='tooltip-content'>
         |  <ul>
         |    <li><b>Type:</b> ${StringEscapeUtils.escapeHtml4(sm.node.main.osDetails.os.kernelName)}</li>
         |    <li><b>Name:</b> ${StringEscapeUtils.escapeHtml4(S.?("os.name." + sm.node.main.osDetails.os.name))}</li>
         |    <li><b>Version:</b> ${StringEscapeUtils.escapeHtml4(sm.node.main.osDetails.version.value)}</li>
         |    <li><b>Service pack:</b> ${StringEscapeUtils.escapeHtml4(sm.node.main.osDetails.servicePack.getOrElse("None"))}</li>
         |    <li><b>Architecture:</b> ${StringEscapeUtils.escapeHtml4(sm.node.archDescription.getOrElse("None"))}</li>
         |    <li><b>Kernel version:</b> ${StringEscapeUtils.escapeHtml4(sm.node.main.osDetails.kernelVersion.value)}</li>
         |  </ul>
         |</div>""".stripMargin.replaceAll("\n", " ")
    }

    val nodeStateClasses: String =
      "node-state " ++ StringEscapeUtils.escapeHtml4(getNodeState(node.rudderSettings.state).toLowerCase).replaceAll(" ", "-")
    val nodeStateIcon = {
      <span class={nodeStateClasses}></span>
    }

    <div class="header-title">
    <div class={"os-logo " ++ sm.node.main.osDetails.os.name.toLowerCase()} data-bs-toggle="tooltip" title={osTooltip}></div>
    <h1>
      <div id="nodeHeaderInfo">
        <span>{sm.node.main.hostname}</span>
        <span class="machine-os-info">
          <span class="machine-info">{sm.node.main.osDetails.fullName}</span>
          <span class="machine-info ram">{sm.node.ram.map(_.toStringMo).getOrElse("-")}</span>
          <span class="fa fa-info-circle icon-info me-1" data-bs-toggle="tooltip" data-bs-placement="bottom" title={
      machineTooltip
    }></span>
        </span>
        {nodeStateIcon}
      </div>
      <div class="header-subtitle">
        <a class="clipboard" title="Copy to clipboard" onclick={s"copy('${sm.node.main.id.value}')"}>
          <span id="nodeHeaderId">{sm.node.main.id.value}</span>
          <i class="ion ion-clipboard"></i>
        </a>
      </div>
    </h1>
    <div class="header-buttons">
      {deleteBtn}
    </div>
  </div>
  }

  // mimic the content of server_details/ShowNodeDetailsFromNode
  def showNodeDetails(
      nodeFact:            NodeFact,
      globalMode:          GlobalPolicyMode,
      creationDate:        Option[Instant],
      salt:                String = "",
      isDisplayingInPopup: Boolean = false
  )(implicit qc: QueryContext): NodeSeq = {

    val nodePolicyMode     = {
      (globalMode.overridable, nodeFact.rudderSettings.policyMode) match {
        case (Always, Some(mode)) =>
          (mode, "<p>This mode is an override applied to this node. You can change it in the <i><b>node settings</b></i>.</p>")
        case (Always, None)       =>
          val expl =
            """<p>This mode is the globally defined default. You can change it in <i><b>settings</b></i>.</p><p>You can also override it on this node in the <i><b>node's settings</b></i>.</p>"""
          (globalMode.mode, expl)
        case (Unoverridable, _)   =>
          (globalMode.mode, "<p>This mode is the globally defined default. You can change it in <i><b>Settings</b></i>.</p>")
      }
    }
    val globalScoreApp     = {
      <div id="global-score-app"></div> ++
      WithNonce.scriptWithNonce {
        Script(OnLoad(JsRaw(s"""
                               |var main = document.getElementById("global-score-app")
                               |var initValues = {
                               |  id : "${nodeFact.id.value}",
                               |  contextPath : contextPath,
                               |};
                               |var globalScoreApp = Elm.Score.init({node: main, flags: initValues});
                               |globalScoreApp.ports.errorNotification.subscribe(function(str) {
                               |  createErrorNotification(str)
                               |});
                               |""".stripMargin)))
      }
    }
    val complianceScoreApp = {
      <div id="compliance-app"></div> ++
      WithNonce.scriptWithNonce {
        Script(
          OnLoad(JsRaw("""var complianceScoreMain = document.getElementById("compliance-app");
                         |var complianceAppScore = Elm.ComplianceScore.init({node: complianceScoreMain, flags : {}});
                         |scoreDetailsDispatcher["compliance"] = function(value){ complianceAppScore.ports.getValue.send(value) };
                         |complianceAppScore.ports.sendHtml.subscribe(function(html) {
                         |  scoreDetailsApp.ports.receiveDetails.send({name : "compliance",html : html});
                         |});
                         |complianceAppScore.ports.errorNotification.subscribe(function(str) {
                         |  createErrorNotification(str)
                         |});""".stripMargin))
        )
      }
    }
    val systemUpdateApp    = {
      <div id="system-updates-app"></div> ++
      WithNonce.scriptWithNonce {
        Script(
          OnLoad(
            JsRaw(
              """var systemUpdatesMain = document.getElementById("system-updates-app");
                |var systemUpdatesAppScore = Elm.SystemUpdateScore.init({node: systemUpdatesMain, flags : {}});
                |scoreDetailsDispatcher["system-updates"] = function(value){ systemUpdatesAppScore.ports.getValue.send(value) };
                |systemUpdatesAppScore.ports.sendHtml.subscribe(function(html) {
                |  scoreDetailsApp.ports.receiveDetails.send({name : "system-updates",html : html});
                |});
                |systemUpdatesAppScore.ports.errorNotification.subscribe(function(str) {
                |  createErrorNotification(str)
                |});""".stripMargin
            )
          )
        )
      }
    }
    val nodeApp            = {
      <div id="system-updates-app"></div> ++
      <div id="node-app"></div> ++
      WithNonce.scriptWithNonce {
        Script(
          OnLoad(
            JsRaw(s"""
                     |var main = document.getElementById("node-app")
                     |var initValues = {
                     |  id : "${nodeFact.id.value}",
                     |  contextPath : contextPath,
                     |};
                     |scoreDetailsApp = Elm.Node.init({node: main, flags: initValues});
                     |scoreDetailsApp.ports.errorNotification.subscribe(function(str) {
                     |  createErrorNotification(str)
                     |});
                     |scoreDetailsApp.ports.getDetails.subscribe(function(data) {
                     |  var name = data.name
                     |  var value = data.details
                     |  var detailsHandler = scoreDetailsDispatcher[name];
                     |  if (detailsHandler !== undefined) {
                     |    detailsHandler(value)
                     |  }
                     |});""".stripMargin)
          )
        )
      }
    }
    <div id="nodeDetails">
      {globalScoreApp ++ complianceScoreApp ++ systemUpdateApp}
      <div class="row">
        <div class="rudder-info col-xl-6 col-md-7 col-sm-12">
          {nodeApp}
        </div>

        <div class="status-info col-xl-6 col-md-5 col-sm-12">
          <h3>Monitoring</h3>
          <div>
            <label>Inventory created (node local time):</label> {
      nodeFact.lastInventoryDate.map(DateFormaterService.getDisplayDate(_)).getOrElse("Unknown")
    }
          </div>
          <div>
            <label>Inventory received:</label> {
      DateFormaterService.getDisplayDate(nodeFact.factProcessedDate)
    }
          </div>
        </div>
        <div class="rudder-info">
          <h3>Documentation</h3>
          <div class="markdown" id="nodeDocumentation">
          </div>
          {
      WithNonce.scriptWithNonce(
        Script(OnLoad(JsRaw(s"generateMarkdown(${Str(nodeFact.documentation.getOrElse("")).toJsCmd}, '#nodeDocumentation')")))
      )
    }
        </div>
      </div>
      <div class="rudder-info">
        <h3>Rudder information</h3>
        <div>
          {
      nodePolicyMode match {
        case (mode, explanation) =>
          <label>Policy mode:</label><span id="badge-apm"></span> ++
          WithNonce.scriptWithNonce(Script(OnLoad(JsRaw(s"""
                $$('#badge-apm').append(createBadgeAgentPolicyMode('node',"${mode}","${StringEscapeUtils.escapeEcmaScript(
              explanation
            )}"));
                //initBsTooltips(getNodeInfo);
              """)))) // JsRaw OK, escaped
      }
    }
        </div>
        {displayServerRole(nodeFact) ++ displayTenant(nodeFact)}
        <div>
          <label>Agent:</label>
          {
      val capabilities = {
        if (nodeFact.rudderAgent.capabilities.isEmpty) "no extra capabilities"
        else "capabilities: " + nodeFact.rudderAgent.capabilities.map(_.value).toList.sorted.mkString(", ")
      }
      s"${nodeFact.rudderAgent.agentType.displayName} (${nodeFact.rudderAgent.version.value} with ${capabilities})"
    }
            </div>
            {displayPolicyServerInfos(nodeFact.toFullInventory)}
            <div>
              {
      creationDate.map { creation =>
        <xml:group><label>Accepted since:</label> {DateFormaterService.getDisplayDate(creation)}</xml:group>
      }.getOrElse(NodeSeq.Empty)
    }
            </div>
            {displaySecurityInfo(nodeFact)}
      </div>
    </div>
  }

  private def htmlId(jsId:   JsNodeId, prefix: String): String = prefix + jsId.toString
  private def htmlId_#(jsId: JsNodeId, prefix: String): String = "#" + prefix + jsId.toString

  /*
   * Display the div with ID `security-info` with cfenfine and curl key/certificate hash,
   * the key/certificate detail, and the button to untrust the key
   */
  private def displaySecurityInfo(nodeFact: NodeFact)(implicit qc: QueryContext): NodeSeq = {

    val checked     = (nodeFact.rudderSettings.status, nodeFact.rudderSettings.keyStatus) match {
      case (AcceptedInventory, CertifiedKey) =>
        <span>
                <span class="fa fa-check text-success" data-bs-toggle="tooltip" title="Inventories for this Node must be signed with this key"></span>
              </span>
      case (AcceptedInventory, UndefinedKey) =>
        <span>
                <span class="fa fa-exclamation-triangle text-warning" data-bs-toggle="tooltip" title="Certificate for this node has been reset, next inventory will be trusted automatically"></span>
              </span>
      case _                                 => // not accepted inventory? Should not get there
        NodeSeq.Empty
    }
    val nodeId      = nodeFact.id
    val publicKeyId = s"publicKey-${nodeId.value}"
    val cfKeyHash   = nodeFactRepository.get(nodeId).either.runNow match {
      case Right(Some(nodeFact_)) if (nodeFact_.keyHashCfengine.nonEmpty) =>
        <div><label>Key hash:</label> <samp>{nodeFact_.keyHashCfengine}</samp></div>
      case _                                                              => NodeSeq.Empty
    }
    val curlHash    = nodeFactRepository.get(nodeId).either.runNow match {
      case Right(Some(nodeFact_)) if (nodeFact_.keyHashCfengine.nonEmpty) =>
        <div><label>Key hash:</label> <samp>sha256//{nodeFact_.keyHashBase64Sha256}</samp></div>
      case _                                                              => NodeSeq.Empty
    }

    val agent     = nodeFact.rudderAgent
    val tokenKind = agent.securityToken match {
      case _: Certificate => "Certificate"
    }
    <div class="security-info" id={"security-" + nodeFact.id.value}>{
      agent.securityToken match {
        case c: Certificate =>
          c.cert.either.runNow match {
            case Left(e)     => <span title={e.fullMsg}>Error while reading certificate information</span>
            case Right(cert) => (
              <div><label>Fingerprint (sha1): </label> <samp>{
                SHA1.hash(cert.getEncoded).grouped(2).mkString(":")
              }</samp></div>
                    <div><label>Expiration date: </label> {
                DateFormaterService.getDisplayDate(new DateTime(cert.getNotAfter, DateTimeZone.UTC))
              }</div>
            )
          }
      }
    }
        {curlHash}
        {cfKeyHash}
        <button type="button" class="toggle-security-info btn btn-default" onclick={
      s"$$('#${publicKeyId}').toggle(300); $$(this).toggleClass('opened'); return false;"
    }> <b>{tokenKind}</b> {checked} </button>
      <div>
        {
      if (CurrentUser.checkRights(AuthorizationType.Node.Write) && nodeFact.rudderSettings.keyStatus == CertifiedKey) {
        SHtml.ajaxButton(
          "Reset status to be able to accept a different key",
          () => resetKeyStatus(nodeFact)
        ) % ("class", "btn btn-default btn-sm")
      } else NodeSeq.Empty
    }
        <pre id={publicKeyId} class="display-keys" style="display:none;"><div>{agent.securityToken.key}</div></pre>{
      WithNonce.scriptWithNonce(Script(OnLoad(JsRaw(s"""initBsTooltips();""")))) // JsRaw ok, const
    }
      </div>
    </div>
  }

  /*
   * Reset key status for given node and redisplay it's security <div>
   */
  private def resetKeyStatus(nodeFact: NodeFact)(implicit qc: QueryContext): JsCmd = {
    implicit val cc: ChangeContext = ChangeContext(
      ModificationId(RudderConfig.stringUuidGenerator.newUuid),
      CurrentUser.actor,
      Instant.now(),
      Some("Trusted key status reset to accept new key (first use)"),
      None,
      CurrentUser.nodePerms
    )

    (for {
      node   <- RudderConfig.nodeFactRepository
                  .get(nodeFact.id)(cc.toQuery)
                  .notOptional(s"Cannot update node with id ${nodeFact.id.value}: there is no node with that id")
      newNode = node.modify(_.rudderSettings.keyStatus).setTo(UndefinedKey)
      _      <- RudderConfig.nodeFactRepository.save(newNode)
    } yield {
      val js: JsCmd = {
        SetHtml(
          s"security-${nodeFact.id.value}",
          displaySecurityInfo(nodeFact.modify(_.rudderSettings.keyStatus).setTo(UndefinedKey))
        ) &
        JsRaw(s"""createSuccessNotification("Key status for node '${StringEscapeUtils.escapeEcmaScript(
            nodeFact.id.value
          )}' correctly changed.")""") // JsRaw ok, escaped
      }
      js
    }).catchAll { err =>
      val js: JsCmd = JsRaw(
        s"""createErrorNotification("${s"An error happened when trying to change key status of node '${StringEscapeUtils
              .escapeEcmaScript(nodeFact.fqdn)}' [${StringEscapeUtils.escapeEcmaScript(nodeFact.id.value)}]. " +
          "Please contact your server admin to resolve the problem. " +
          s"Error was: '${err.fullMsg}'"}")"""
      ) // JsRaw ok, escaped
      js.succeed
    }.runNow

  }

  // Display the role of the node
  private def displayServerRole(nodeFact: NodeFact): NodeSeq = {
    nodeFact.rudderSettings.status match {
      case AcceptedInventory =>
        val kind = {
          nodeFact.rudderSettings.kind match {
            case NodeKind.Root  => "server"
            case NodeKind.Relay => "relay server"
            case NodeKind.Node  => "node"
          }
        }

        <div><label>Role:</label> Rudder {kind}</div>
      case RemovedInventory  =>
        <div><label>Role:</label> Deleted node</div>
      case PendingInventory  =>
        <div><label>Role:</label> Pending node</div>
    }
  }

  // Display the node tenant if defined (ie if different from "no tenant"
  private def displayTenant(nodeFact: NodeFact): NodeSeq = {
    nodeFact.rudderSettings.security match {
      case Some(SecurityTag(tenants)) if (tenants.nonEmpty) =>
        <div><label>Tenant:</label> {tenants.map(_.value).mkString(", ")}</div>
      case _                                                =>
        NodeSeq.Empty
    }
  }

  private def displayPolicyServerInfos(sm: FullInventory)(implicit qc: QueryContext): NodeSeq = {
    nodeFactRepository.get(sm.node.main.policyServerId).either.runNow match {
      case Left(err)                        =>
        val e = s"Could not fetch policy server details (id '${sm.node.main.policyServerId.value}') for node '${escapeHTML(
            sm.node.main.hostname
          )}' ('${sm.node.main.id.value}'): ${err.fullMsg}"
        logger.error(e)
        <div class="error"><label>Policy server:</label> Could not fetch details about the policy server</div>
      case Right(Some(policyServerDetails)) =>
        <div><label>Policy server:</label> <a href={linkUtil.baseNodeLink(policyServerDetails.id)}  onclick={
          s"updateNodeIdAndReload('${policyServerDetails.id.value}')"
        }>{escapeHTML(policyServerDetails.fqdn)}</a></div>
      case Right(None)                      =>
        logger.error(
          s"Could not fetch policy server details (id '${sm.node.main.policyServerId.value}') for node '${sm.node.main.hostname}' ('${sm.node.main.id.value}')"
        )
        <div class="error"><label>Policy Server:</label> Could not fetch details about the policy server</div>
    }
  }

  def displayMachineType(opt: Option[MachineInventory]): NodeSeq = {
    opt match {
      case None          => NodeSeq.Empty
      case Some(machine) => (
        machine.machineType match {
          case UnknownMachineType         => Text("Unknown machine type")
          case PhysicalMachineType        => Text("Physical machine")
          case VirtualMachineType(vmType) => Text(s"Virtual machine (${vmType.displayName})")
        }
      )
    }
  }

  // show a comma separated list with description in tooltip

  private def displayAccounts(node: NodeInventory): String = {
    if (node.accounts.isEmpty) {
      "None"
    } else {
      node.accounts.sortWith(_ < _).mkString(", ")
    }
  }

  private def displayTabGrid[T](
      jsId: JsNodeId
  )(eltName: String, optSeq: Box[Seq[T]], title: Option[String] = None)(columns: List[(String, T => NodeSeq)]) = {
    <div id={htmlId(jsId, "sd_" + eltName + "_")} class="sInventory tab-pane">{
      optSeq match {
        case Empty                                           =>
          <div class="col-sm-12 alert alert-warning">
            <span>No matching components detected on this node</span>
          </div>
        case Failure(m, _, _)                                => <span class="error">Error when trying to fetch file systems. Reported message: {m}</span>
        case Full(seq) if (seq.isEmpty && eltName != "soft") =>
          <div class="col-sm-12 alert alert-warning">
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

  private def displayTabOS(jsId: JsNodeId, sm: FullInventory, node: CoreNodeFact): NodeSeq = {
    displayTabGrid(jsId)(
      "os",
      // special: add name -> value to be displayed as a table
      Full(
        Seq(
          ("Node ID", StringEscapeUtils.escapeHtml4(sm.node.main.id.value)),
          ("Hostname", StringEscapeUtils.escapeHtml4(sm.node.main.hostname)),
          ("Policy server ID", StringEscapeUtils.escapeHtml4(sm.node.main.policyServerId.value)),
          ("Operating system detailed name", StringEscapeUtils.escapeHtml4(sm.node.main.osDetails.fullName)),
          ("Operating system type", StringEscapeUtils.escapeHtml4(sm.node.main.osDetails.os.kernelName)),
          ("Operating system name", StringEscapeUtils.escapeHtml4(S.?("os.name." + sm.node.main.osDetails.os.name))),
          ("Operating system version", StringEscapeUtils.escapeHtml4(sm.node.main.osDetails.version.value)),
          ("Operating system service pack", StringEscapeUtils.escapeHtml4(sm.node.main.osDetails.servicePack.getOrElse("None"))),
          ("Operating system architecture description", StringEscapeUtils.escapeHtml4(sm.node.archDescription.getOrElse("None"))),
          ("Kernel version", StringEscapeUtils.escapeHtml4(sm.node.main.osDetails.kernelVersion.value)),
          ("Total physical memory (RAM)", StringEscapeUtils.escapeHtml4(sm.node.ram.map(_.toStringMo).getOrElse("-"))),
          (
            "Manufacturer",
            StringEscapeUtils.escapeHtml4(sm.machine.flatMap(x => x.manufacturer).map(x => x.name).getOrElse("-"))
          ),
          ("Machine type", displayMachineType(sm.machine).text),
          ("Total swap space (Swap)", StringEscapeUtils.escapeHtml4(sm.node.swap.map(_.toStringMo).getOrElse("-"))),
          ("System serial number", StringEscapeUtils.escapeHtml4(sm.machine.flatMap(x => x.systemSerialNumber).getOrElse("-"))),
          ("Agent type", StringEscapeUtils.escapeHtml4(sm.node.agents.headOption.map(_.agentType.displayName).getOrElse("-"))),
          (
            "Agent version",
            StringEscapeUtils.escapeHtml4(sm.node.agents.headOption.flatMap(_.version.map(_.value)).getOrElse("-"))
          ),
          ("Node state", StringEscapeUtils.escapeHtml4(getNodeState(node.rudderSettings.state))),
          ("Account(s)", displayAccounts(sm.node)),
          ("Administrator account", StringEscapeUtils.escapeHtml4(sm.node.main.rootUser)),
          ("IP addresses", StringEscapeUtils.escapeHtml4(sm.node.serverIps.mkString(", "))),
          (
            "Last inventory date",
            StringEscapeUtils.escapeHtml4(node.lastInventoryDate.map(DateFormaterService.getDisplayDate).getOrElse("-"))
          ),
          ("Policy server ID", StringEscapeUtils.escapeHtml4(sm.node.main.policyServerId.value)),
          (
            "Time zone",
            StringEscapeUtils.escapeHtml4(
              sm.node.timezone
                .map(x => if (x.name.toLowerCase == "utc") "UTC" else s"${x.name} (UTC ${x.offset})")
                .getOrElse("unknown")
            )
          ),
          (
            "Machine ID",
            sm.machine
              .map(x => StringEscapeUtils.escapeHtml4(x.id.value))
              .getOrElse("Machine information is missing for that node")
          )
        )
      )
    ) {
      ("Name", { (x: (String, String)) => Text(x._1) }) ::
      ("Value", { (x: (String, String)) => Text(x._2) }) ::
      Nil
    }
  }

  private def displayTabSoftware(jsId: JsNodeId): NodeSeq = {
    displayTabGrid(jsId)(
      "soft",
      // do not retrieve software here
      Full(Seq())
    ) {
      ("Name", { (x: Software) => ?(x.name) }) ::
      ("Version", { (x: Software) => ?(x.version.map(_.value)) }) ::
      ("Description", { (x: Software) => ?(x.description) }) ::
      Nil
    }
  }

  private def displayTabSoftwareUpdates(jsId: JsNodeId, sm: FullInventory): NodeSeq = {
    displayTabGrid(jsId)("softUpdates", Full(sm.node.softwareUpdates)) {
      ("Name", { (x: SoftwareUpdate) => Text(x.name) }) ::
      ("Version", { (x: SoftwareUpdate) => Text(x.version.getOrElse("-")) }) ::
      ("Architecture", { (x: SoftwareUpdate) => Text(x.arch.getOrElse("-")) }) ::
      ("From", { (x: SoftwareUpdate) => Text(x.from.getOrElse("-")) }) ::
      ("Kind", { (x: SoftwareUpdate) => Text(x.kind.name) }) ::
      ("Source", { (x: SoftwareUpdate) => ?(x.source) }) ::
      Nil
    }
  }

  private def displayTabNetworks(jsId: JsNodeId, sm: FullInventory): NodeSeq = {
    displayTabGrid(jsId)("net", Full(sm.node.networks)) {
      ("Interface", { (x: Network) => escapeHTML(x.name) }) ::
      ("IP address", { (x: Network) => (x.ifAddresses.map(y => (<div>{escapeHTML(y.getHostAddress)}</div>))): NodeSeq }) ::
      ("Mask", { (x: Network) => (x.ifMask.map(y => (<div>{escapeHTML(y.getHostAddress)}</div>))): NodeSeq }) ::
      ("Network", { (x: Network) => (x.ifSubnet.map(y => (<div>{escapeHTML(y.getHostAddress)}</div>))): NodeSeq }) ::
      ("Gateway", { (x: Network) => (x.ifGateway.map(y => (<div>{escapeHTML(y.getHostAddress)}</div>))): NodeSeq }) ::
      ("DHCP server", { (x: Network) => escapeHTML(x.ifDhcp.map(_.getHostAddress).mkString(", ")) }) ::
      ("MAC address", { (x: Network) => ?(x.macAddress) }) ::
      ("Type", { (x: Network) => ?(x.ifType) }) ::
      ("Speed", { (x: Network) => ?(x.speed) }) ::
      ("Status", { (x: Network) => ?(x.status) }) ::
      Nil
    }
  }

  private def displayTabFilesystems(jsId: JsNodeId, sm: FullInventory): NodeSeq = {
    displayTabGrid(jsId)("fs", Full(sm.node.fileSystems)) {
      ("Mount point", { (x: FileSystem) => escapeHTML(x.mountPoint) }) ::
      ("Filesystem", { (x: FileSystem) => ?(x.name) }) ::
      ("Free space", { (x: FileSystem) => ?(x.freeSpace.map(_.toStringMo)) }) ::
      ("Total space", { (x: FileSystem) => ?(x.totalSpace.map(_.toStringMo)) }) ::
      ("File count", { (x: FileSystem) => ?(x.fileCount.map(_.toString)) }) ::
      Nil
    }
  }

  private def displayTabVariable(jsId: JsNodeId, sm: FullInventory): NodeSeq = {

    val title = sm.node.inventoryDate.map(date => s"Environment variable status on ${DateFormaterService.getDisplayDate(date)}")
    displayTabGrid(jsId)("var", Full(sm.node.environmentVariables), title) {
      ("Name", { (x: EnvironmentVariable) => escapeHTML(x.name) }) ::
      ("Value", { (x: EnvironmentVariable) => escapeHTML(x.value.getOrElse("Unspecified")) }) ::
      Nil
    }
  }

  def displayTabProperties(jsId: JsNodeId, node: NodeFact, sm: FullInventory): NodeSeq = {

    val nodeId        = node.id.value
    def tabProperties = ChooseTemplate(List("templates-hidden", "components", "ComponentNodeProperties"), "nodeproperties-tab")
    val tabId         = htmlId(jsId, "sd_props_")
    val css: CssSel = "#tabPropsId [id]" #> tabId &
      "#inventoryVariables *" #> DisplayNode.displayTabInventoryVariable(jsId, node.toCore, sm)

    css(tabProperties) ++ WithNonce.scriptWithNonce(
      Script(
        OnLoad(
          JsRaw(
            s"""
               |
      var main = document.getElementById("nodeproperties-app")
               |var initValues = {
               |    contextPath    : "${S.contextPath}"
               |  , hasNodeWrite   : CanWriteNode
               |  , hasNodeRead    : CanReadNode
               |  , nodeId         : "${nodeId}"
               |  , objectType     : 'node'
               |};
               |var app = Elm.Nodeproperties.init({node: main, flags: initValues});
               |app.ports.successNotification.subscribe(function(str) {
               |  createSuccessNotification(str)
               |});
               |app.ports.errorNotification.subscribe(function(str) {
               |  createErrorNotification(str)
               |});
               |// Initialize tooltips
               |app.ports.initTooltips.subscribe(function(msg) {
               |  setTimeout(function(){
               |    initBsTooltips();
               |  }, 400);
               |});
               |app.ports.copy.subscribe(function(str) {
               |  copy(str);
               |});
               |app.ports.initInputs.subscribe(function(str) {
               |  setTimeout(function(){
               |    $$(".auto-resize").on("input", autoResize).each(function(){
               |      autoResize(this);
               |    });
               |  }, 10);
               |});
               |""".stripMargin
          )
        )
      )
    )
  }

  def displayTabInventoryVariable(jsId: JsNodeId, node: CoreNodeFact, sm: FullInventory): NodeSeq = {
    def displayLine(name: String, value: String): NodeSeq = {
      <tr>
        <td>{name}</td>
        <td>
          <span class="ion ion-clipboard copy-actions" onclick={s"copy('$${node.inventory[${name}]}')"} ></span>
        </td>
        {
        if (value.strip().isEmpty) {
          <td></td>
        } else {
          <td>
            <pre class="json-inventory-vars">{value}</pre>
          </td>
          <td>
            <span class="ion ion-clipboard copy-actions" onclick={s"copy('${value}')"}></span>
          </td>
        }
      }
     </tr>
    }

    val os = sm.node.main.osDetails.transformInto[OsDetailsJson]

    val machine = sm.machine.transformInto[MachineJson]

    val values = Seq[(String, String)](
      ("localAdministratorAccountName", StringEscapeUtils.escapeHtml4(sm.node.main.rootUser)),
      ("hostname", StringEscapeUtils.escapeHtml4(sm.node.main.hostname)),
      ("policyServerId", StringEscapeUtils.escapeHtml4(sm.node.main.policyServerId.value)),
      ("os", os.toJsonEscaped),
      ("archDescription", StringEscapeUtils.escapeHtml4(sm.node.archDescription.getOrElse("None"))),
      ("ram", StringEscapeUtils.escapeHtml4(sm.node.ram.map(_.size.toString).getOrElse(""))),
      ("machine", machine.toJsonEscaped),
      (
        "timezone",
        StringEscapeUtils.escapeHtml4(
          sm.node.timezone
            .map(x => if (x.name.toLowerCase == "utc") "UTC" else s"${x.name} (UTC ${x.offset})")
            .getOrElse("unknown")
        )
      )
    )

    <div>
      <h3 class="page-title foldable" onclick="$('.variables-table-container').toggle(); $(this).toggleClass('folded');">Inventory variables <i class="fa fa-chevron-down"></i></h3>
      <div class="variables-table-container">
        <div class="alert alert-info">
          These are the node inventory variables that can be used in directive inputs with the <b class="code">${{node.inventory[NAME]}}</b> syntax.
        </div>
        <table id="inventoryVariablesTab" class="no-footer dataTable">
          <thead>
            <tr class="head">
              <th class="sorting sorting_desc">Name</th>
              <th></th>
              <th class="sorting">Value</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {values.map { case (n, v) => displayLine(n, v) }}
          </tbody>
        </table>
      </div>
    </div>
  }

  private def displayTabProcess(jsId: JsNodeId, sm: FullInventory): NodeSeq = {
    val title = sm.node.inventoryDate.map(date => s"Process status on ${DateFormaterService.getDisplayDate(date)}")
    displayTabGrid(jsId)("process", Full(sm.node.processes), title) {
      ("User", { (x: Process) => ?(x.user) }) ::
      ("PID", { (x: Process) => escapeHTML(x.pid.toString()) }) ::
      ("% CPU", { (x: Process) => ?(x.cpuUsage.map(_.toString())) }) ::
      ("% Memory", { (x: Process) => ?(x.memory.map(_.toString())) }) ::
      ("Virtual memory", { (x: Process) => ?(x.virtualMemory.map(memory => MemorySize(memory.toLong).toStringMo)) }) ::
      ("TTY", { (x: Process) => ?(x.tty) }) ::
      ("Started on", { (x: Process) => ?(x.started) }) ::
      ("Command", { (x: Process) => ?(x.commandName) }) ::
      Nil
    }
  }

  private def displayTabVM(jsId: JsNodeId, sm: FullInventory): NodeSeq = {
    displayTabGrid(jsId)("vm", Full(sm.node.vms)) {
      ("Name", { (x: VirtualMachine) => ?(x.name) }) ::
      ("Type", { (x: VirtualMachine) => ?(x.vmtype) }) ::
      ("SubSystem", { (x: VirtualMachine) => ?(x.subsystem) }) ::
      ("Uuid", { (x: VirtualMachine) => escapeHTML(x.uuid.value) }) ::
      ("Status", { (x: VirtualMachine) => ?(x.status) }) ::
      ("Owner", { (x: VirtualMachine) => ?(x.owner) }) ::
      ("# Cpu", { (x: VirtualMachine) => ?(x.vcpu.map(_.toString())) }) ::
      ("Memory", { (x: VirtualMachine) => ?(x.memory) }) ::
      Nil
    }
  }

  private def displayTabBios(jsId: JsNodeId, sm: FullInventory): NodeSeq = {
    displayTabGrid(jsId)("bios", sm.machine.map(fm => fm.bios)) {
      ("Name", { (x: Bios) => escapeHTML(x.name) }) ::
      ("Editor", { (x: Bios) => ?(x.editor.map(_.name)) }) ::
      ("Version", { (x: Bios) => ?(x.version.map(_.value)) }) ::
      ("Manufacturer", { (x: Bios) => ?(x.manufacturer.map(_.name)) }) ::
      ("Serial Number", { (x: Bios) => ?(x.serialNumber) }) ::
      ("Release date", { (x: Bios) => ?(x.releaseDate.map(DateFormaterService.getDisplayDate(_))) }) ::
      Nil
    }
  }

  private def displayTabControllers(jsId: JsNodeId, sm: FullInventory): NodeSeq = {
    displayTabGrid(jsId)("controllers", sm.machine.map(fm => fm.controllers)) {
      ("Name", { (x: Controller) => escapeHTML(x.name) }) ::
      ("Manufacturer", { (x: Controller) => ?(x.manufacturer.map(_.name)) }) ::
      ("Type", { (x: Controller) => ?(x.cType) }) ::
      ("Quantity", { (x: Controller) => escapeHTML(x.quantity.toString) }) ::
      Nil
    }
  }

  private def displayTabMemories(jsId: JsNodeId, sm: FullInventory): NodeSeq = {
    displayTabGrid(jsId)("memories", sm.machine.map(fm => fm.memories)) {
      ("Slot", { (x: MemorySlot) => escapeHTML(x.slotNumber) }) ::
      ("Capacity", { (x: MemorySlot) => ?(x.capacity.map(_.toStringMo)) }) ::
      ("Description", { (x: MemorySlot) => ?(x.description) }) ::
      ("Serial number", { (x: MemorySlot) => ?(x.serialNumber) }) ::
      ("Speed", { (x: MemorySlot) => ?(x.speed) }) ::
      ("Type", { (x: MemorySlot) => ?(x.memType) }) ::
      ("Quantity", { (x: MemorySlot) => escapeHTML(x.quantity.toString) }) ::
      Nil
    }
  }

  private def displayTabPorts(jsId: JsNodeId, sm: FullInventory): NodeSeq = {
    displayTabGrid(jsId)("ports", sm.machine.map(fm => fm.ports)) {
      ("Name", { (x: Port) => escapeHTML(x.name) }) ::
      ("Type", { (x: Port) => ?(x.pType) }) ::
      ("Description", { (x: Port) => ?(x.description) }) ::
      ("Quantity", { (x: Port) => escapeHTML(x.quantity.toString) }) ::
      Nil
    }
  }

  private def displayTabProcessors(jsId: JsNodeId, sm: FullInventory): NodeSeq = {
    displayTabGrid(jsId)("processors", sm.machine.map(fm => fm.processors)) {
      ("Name", { (x: Processor) => escapeHTML(x.name) }) ::
      ("Speed", { (x: Processor) => ?(x.speed.map(_.toString)) }) ::
      ("Model", { (x: Processor) => ?(x.model.map(_.toString())) }) ::
      ("Family", { (x: Processor) => ?(x.family.map(_.toString())) }) ::
      ("Family name", { (x: Processor) => ?(x.familyName) }) ::
      ("Manufacturer", { (x: Processor) => ?(x.manufacturer.map(_.name)) }) ::
      ("Thread", { (x: Processor) => ?(x.thread.map(_.toString())) }) ::
      ("Core", { (x: Processor) => ?(x.core.map(_.toString())) }) ::
      ("CPUID", { (x: Processor) => ?(x.cpuid) }) ::
      ("Architecture", { (x: Processor) => ?(x.arch) }) ::
      ("Stepping", { (x: Processor) => ?(x.stepping.map(_.toString)) }) ::
      ("Quantity", { (x: Processor) => escapeHTML(x.quantity.toString) }) ::
      Nil
    }
  }

  private def displayTabSlots(jsId: JsNodeId, sm: FullInventory): NodeSeq = {
    displayTabGrid(jsId)("slots", sm.machine.map(fm => fm.slots)) {
      ("Name", { (x: Slot) => escapeHTML(x.name) }) ::
      ("Description", { (x: Slot) => ?(x.description) }) ::
      ("Status", { (x: Slot) => ?(x.status) }) ::
      ("Quantity", { (x: Slot) => escapeHTML(x.quantity.toString) }) ::
      Nil
    }
  }

  private def displayTabSounds(jsId: JsNodeId, sm: FullInventory): NodeSeq = {
    displayTabGrid(jsId)("sounds", sm.machine.map(fm => fm.sounds)) {
      ("Name", { (x: Sound) => escapeHTML(x.name) }) ::
      ("Description", { (x: Sound) => ?(x.description) }) ::
      ("Quantity", { (x: Sound) => escapeHTML(x.quantity.toString) }) ::
      Nil
    }
  }

  private def displayTabStorages(jsId: JsNodeId, sm: FullInventory): NodeSeq = {
    displayTabGrid(jsId)("storages", sm.machine.map(fm => fm.storages)) {
      ("Name", { (x: Storage) => escapeHTML(x.name) }) ::
      ("Description", { (x: Storage) => ?(x.description) }) ::
      ("Size", { (x: Storage) => ?(x.size.map(_.toStringMo)) }) ::
      ("Firmware", { (x: Storage) => ?(x.firmware) }) ::
      ("Manufacturer", { (x: Storage) => ?(x.manufacturer.map(_.name)) }) ::
      ("Model", { (x: Storage) => ?(x.model) }) ::
      ("Serial", { (x: Storage) => ?(x.serialNumber) }) ::
      ("Type", { (x: Storage) => ?(x.sType) }) ::
      ("Quantity", { (x: Storage) => escapeHTML(x.quantity.toString) }) ::
      Nil
    }
  }

  private def displayTabVideos(jsId: JsNodeId, sm: FullInventory): NodeSeq = {
    displayTabGrid(jsId)("videos", sm.machine.map(fm => fm.videos)) {
      ("Name", { (x: Video) => escapeHTML(x.name) }) ::
      ("Chipset", { (x: Video) => ?(x.chipset) }) ::
      ("Memory", { (x: Video) => ?(x.memory.map(_.toStringMo)) }) ::
      ("Resolution", { (x: Video) => ?(x.resolution) }) ::
      ("Quantity", { (x: Video) => escapeHTML(x.quantity.toString) }) ::
      Nil
    }
  }
  def showDeleteButton(node: MinimalNodeFactInterface):            NodeSeq = {
    SHtml.ajaxButton(
      "Confirm",
      () => { removeNode(node) },
      ("class", "btn btn-danger")
    )
  }

  private def removeNode(node: MinimalNodeFactInterface): JsCmd = {
    implicit val cc: ChangeContext = ChangeContext(
      ModificationId(uuidGen.newUuid),
      CurrentUser.actor,
      Instant.now(),
      None,
      S.request.map(_.remoteAddr).toOption,
      CurrentUser.nodePerms
    )

    // only erase for Rudder 8.0
    removeNodeService.removeNodePure(node.id, DeleteMode.Erase).toBox match {
      case Full(_) =>
        asyncDeploymentAgent ! AutomaticStartDeployment(cc.modId, cc.actor)
        onSuccess(node)
      case eb: EmptyBox =>
        val message = s"There was an error while deleting node '${node.fqdn}' [${node.id.value}]"
        val e       = eb ?~! message
        NodeLoggerPure.Delete.logEffect.error(e.messageChain)
        onFailure(node, message)
    }
  }

  private def onFailure(
      node:    MinimalNodeFactInterface,
      message: String
  ): JsCmd = {
    RegisterToasts.register(
      ToastNotification.Error(
        s"An error happened when trying to delete node '${node.fqdn}' [${node.id.value}]. " +
        "Please contact your server admin to resolve the problem. " +
        s"Error was: '${message}'"
      )
    )
    RedirectTo("/secure/nodeManager/nodes")
  }

  private def onSuccess(node: MinimalNodeFactInterface): JsCmd = {
    RegisterToasts.register(ToastNotification.Success(s"Node '${node.fqdn}' [${node.id.value}] was correctly deleted"))
    RedirectTo("/secure/nodeManager/nodes")
  }

  private def isRootNode(n: NodeId): Boolean = {
    n.value.equals("root");
  }

  import com.normation.rudder.domain.nodes.NodeState
  private def getNodeState(nodeState: NodeState): String = {
    S.?(s"node.states.${nodeState.name}")
  }
}

object DisplayNodeJson {
  final case class OsDetailsJson(
      fullName:      String,
      name:          String,
      version:       String,
      servicePack:   String,
      kernelVersion: String
  ) {
    def toJsonEscaped: String = OsDetailsJson(
      StringEscapeUtils.unescapeEcmaScript(fullName),
      StringEscapeUtils.unescapeEcmaScript(S.?("os.name." + name)),
      StringEscapeUtils.unescapeEcmaScript(version),
      StringEscapeUtils.unescapeEcmaScript(servicePack),
      StringEscapeUtils.unescapeEcmaScript(kernelVersion)
    ).toJson
  }

  object OsDetailsJson {
    implicit val transformer: Transformer[OsDetails, OsDetailsJson] = Transformer
      .define[OsDetails, OsDetailsJson]
      .withFieldComputed(_.name, _.os.name)
      .withFieldComputed(_.servicePack, _.servicePack.getOrElse("None"))
      .buildTransformer

    implicit val encoder: JsonEncoder[OsDetailsJson] = DeriveJsonEncoder.gen[OsDetailsJson]
  }

  final case class MachineJson(
      manufacturer: String,
      machineType:  String
  ) {
    def toJsonEscaped: String = copy(manufacturer = StringEscapeUtils.unescapeEcmaScript(manufacturer)).toJson
  }
  object MachineJson   {
    implicit val transformer: Transformer[Option[MachineInventory], MachineJson] = Transformer
      .define[Option[MachineInventory], MachineJson]
      .withFieldComputed(_.manufacturer, _.flatMap(_.manufacturer.map(_.name)).getOrElse(""))
      .withFieldComputed(_.machineType, DisplayNode.displayMachineType(_).text.toString)
      .buildTransformer

    implicit val encoder: JsonEncoder[MachineJson] = DeriveJsonEncoder.gen[MachineJson]
  }
}
