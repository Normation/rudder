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

import com.normation.appconfig.ReadConfigService
import com.normation.box.*
import com.normation.inventory.domain.AgentType
import com.normation.inventory.domain.NodeId
import com.normation.rudder.AuthorizationType
import com.normation.rudder.domain.nodes.NodeState
import com.normation.rudder.domain.policies.PolicyMode
import com.normation.rudder.domain.policies.PolicyTypeName
import com.normation.rudder.domain.reports.{RunAnalysisKind as R, *}
import com.normation.rudder.facts.nodes.CoreNodeFact
import com.normation.rudder.facts.nodes.NodeFactRepository
import com.normation.rudder.facts.nodes.QueryContext
import com.normation.rudder.facts.nodes.RudderSettings
import com.normation.rudder.repository.FullActiveTechniqueCategory
import com.normation.rudder.repository.RoDirectiveRepository
import com.normation.rudder.repository.RoRuleRepository
import com.normation.rudder.users.CurrentUser
import com.normation.rudder.web.ChooseTemplate
import com.normation.rudder.web.snippet.WithNonce
import com.normation.utils.DateFormaterService
import com.normation.zio.*
import net.liftweb.common.*
import net.liftweb.http.SHtml
import net.liftweb.http.js.JE.*
import net.liftweb.http.js.JsCmd
import net.liftweb.http.js.JsCmds.*
import net.liftweb.util.Helpers.*
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import scala.xml.NodeSeq
import scala.xml.NodeSeq.seqToNodeSeq
import zio.json.*

/**
 * Display the last reports of a server
 * Based on template : templates-hidden/reports_server
 */
class ReportDisplayer(
    ruleRepository:      RoRuleRepository,
    directiveRepository: RoDirectiveRepository,
    nodeFactRepo:        NodeFactRepository,
    configService:       ReadConfigService,
    logDisplayer:        LogDisplayer
) extends Loggable {

  def reportByNodeTemplate: NodeSeq = ChooseTemplate(List("templates-hidden", "reports_server"), "batches-list")

  /**
   * Main entry point to display the tab with reports of a node.
   * It build up to 3 tables:
   * - general compliance table (displayed by rules)
   * - missing reports table if such reports exists
   * - unknown reports table if such reports exists
   * addOverridden decides if we need to add overridden policies (policy tab), or not (system tab)
   */
  def asyncDisplay(
      node:        CoreNodeFact,
      tabId:       String,
      containerId: String,
      tableId:     String,
      getReports:  NodeId => Box[NodeStatusReport],
      onlySystem:  Boolean
  )(implicit qc: QueryContext): NodeSeq = {
    val i        = configService.agent_run_interval().option.runNow.getOrElse(10)
    val callback = {
      SHtml.ajaxInvoke(() => SetHtml(containerId, displayReports(node, getReports, tabId, tableId, containerId, onlySystem, i)))
    }
    WithNonce.scriptWithNonce(Script(OnLoad(JsRaw(s"""
      const triggerEl = document.querySelector("[aria-controls='${tabId}']");
      if(triggerEl.classList.contains('active')){
        ${callback.toJsCmd}
      }
      const tabEl = document.querySelectorAll('.nav-underline button[data-bs-toggle="tab"]')
      var tabId;
      tabEl.forEach((element) =>
        element.addEventListener('shown.bs.tab', event => {
          tabId = event.target.getAttribute("aria-controls");
          if(tabId = '${tabId}') {
            ${callback.toJsCmd}
          }
        })
      );
    """)))) // JsRaw ok, escaped
  }

  def getRunDate(r: RunAnalysis): Option[DateTime] = {
    r.lastRunDateTime
  }

  /**
   * Refresh the main compliance table
   */
  def refreshReportDetail(
      node:               CoreNodeFact,
      tableId:            String,
      getReports:         NodeId => Box[NodeStatusReport],
      defaultRunInterval: Int
  )(implicit qc: QueryContext): AnonFunc = {
    implicit val next: ProvideNextName = LiftProvideNextName
    def refreshData:   Box[JsCmd]      = {
      for {
        report <- getReports(node.id)
        data   <- getComplianceData(node.id, report)
        runDate: Option[DateTime] = getRunDate(report.runInfo)
      } yield {
        import net.liftweb.util.Helpers.encJs
        val intro = encJs(displayIntro(report, node.rudderSettings, defaultRunInterval).toString)
        JsRaw(
          s"""refreshTable("${tableId}",${data.toJson}); $$("#node-compliance-intro").replaceWith(${intro})"""
        ) // JsRaw ok, escaped
      }
    }

    val ajaxCall = {
      SHtml.ajaxCall(
        JsNull,
        (s) => refreshData.getOrElse(Noop)
      )
    }

    AnonFunc(ajaxCall)
  }

  private def displayIntro(report: NodeStatusReport, nodeSettings: RudderSettings, defaultInterval: Int): NodeSeq = {

    def displayDate(d:    DateTime) = DateFormaterService.getDisplayDate(d)
    def displayDateOpt(d: Option[DateTime]): String = d.fold("an unknown date")(displayDate)
    def explainCompliance(info: RunAnalysis): NodeSeq = {

      def currentConfigId(expectedConfig: RunAnalysis): String = {
        s"Current configuration ID for this node is '${expectedConfig.expectedConfigId.fold("unknown")(_.value)}' " +
        s"(generated on ${displayDateOpt(expectedConfig.expectedConfigStart)})"
      }
      def logRun(info: RunAnalysis):                    String = {
        val configId = info.lastRunConfigId.fold("none or too old")(i => s"configId: ${i.value}")
        val date     = info.lastRunDateTime.fold("unknown")(displayDate)
        val exp      = info.lastRunExpiration.fold("")(r => s" | expired at ${displayDate(r)}")

        s"${configId} received at: ${date}${exp}"

      }

      info.kind match {
        case R.ComputeCompliance =>
          (<p>This node has up to date policy and the agent is running. Reports below are from the latest run: {
            logRun(info)
          }.</p><p>{currentConfigId(info)}.</p>)

        case R.NoUserRulesDefined =>
          (<p>This node has up to date policy and the agent is running, but no user rules are defined. Last run is {
            logRun(info)
          }.</p><p>{currentConfigId(info)}.</p>)

        case R.Pending =>
          val runInfo = info.lastRunDateTime match {
            case None       =>
              "No recent reports have been received for this node. Check that the agent is running (run 'rudder agent check' on the node)."
            case Some(date) =>
              (info.lastRunExpiration match {
                case None      =>
                  // here, if the date of last run is very old, the node was grey and it changed to blue due to the new config, but it's
                  // very unlikely that it will start to answer.
                  val runIntervalMinutes =
                    nodeSettings.reportingConfiguration.agentRunInterval.map(_.interval).getOrElse(defaultInterval)
                  val minDate            =
                    info.expectedConfigStart.getOrElse(DateTime.now(DateTimeZone.UTC)).minusMinutes(runIntervalMinutes * 2)
                  val expiration         = info.expirationDateTime.getOrElse(DateTime.now(DateTimeZone.UTC)).plus(runIntervalMinutes * 2L)

                  if (date.isBefore(minDate)) { // most likely a disconnected node
                    s"The node was reporting on a previous configuration policy, and didn't send reports since a long time: please" +
                    s" check that the node connection to server is correct. It should report on the new one at latest" +
                    s" ${displayDate(expiration)}. Previous known states are displayed below."
                  } else {
                    s"This is expected, the node is reporting on the previous configuration policy and should report on the new one at latest" +
                    s" ${displayDate(expiration)}. Previous known states are displayed below."
                  }
                case Some(exp) =>
                  s"This is unexpected, since the node is reporting on a configuration policy that expired at ${displayDate(exp)}."
              }) +
              s" The latest reports received for this node are from a run started at ${displayDate(date)} (${logRun(info)})."
          }

          (
            <p>This node has recently been assigned a new policy but no reports have been received for the new policy yet.</p>
            <p>{runInfo}</p>
            <p>{currentConfigId(info)}.</p>
          )

        case R.NoReportInInterval =>
          (
            <p>No recent reports have been received for this node in the grace period since the last configuration policy change.
               This is unexpected. Please check the status of the agent by running 'rudder agent health' on the node.</p>
            <p>For information, expected node policies are displayed below.</p>
            <p>{currentConfigId(info)}.</p>
          )

        case R.KeepLastCompliance =>
          (
            <p>This node is in "keep compliance" mode because it didn't received any reports recently.</p>
            <p>The last reports received are from 🕔 <b>{displayDateOpt(info.expiredSince)}</b>.</p>
            <p>That mode will expire if no reports are received before {displayDateOpt(info.expirationDateTime)}.</p> ++ {
              info.lastRunConfigId match {
                case Some(id) =>
                  <p>The last compliance was for configuration ID '{id.value}' for a run executed at {
                    displayDateOpt(info.lastRunDateTime)
                  }</p>
                case None     => NodeSeq.Empty
              }
            }
          )

        case R.NoRunNoExpectedReport =>
          <p>This is a new node that does not yet have a configured policy. If a policy generation is in progress, this will apply to this node when it is done.</p>

        case R.NoExpectedReport =>
          val configIdmsg = info.lastRunConfigId match {
            case None     => "without a configuration ID, although one is required"
            case Some(id) => s"with configuration ID '${id.value}' that is unknown to Rudder"
          }

          <p>This node has no configuration policy assigned to it, but reports have been received for it {
            configIdmsg
          } (run started at {displayDateOpt(info.lastRunDateTime)}).
             Either this node was deleted from Rudder but still has a running agent or the node is sending a corrupted configuration ID.
             Please run "rudder agent update -f" on the node to force a policy update and, if the problem persists,
             force a policy regeneration with the "Clear caches" button in Administration > Settings.</p>

        case R.UnexpectedVersion =>
          (
            <p>This node is sending reports from an out-of-date configuration policy ({
              info.lastRunConfigId.fold("unknown")(_.value)
            }, run started at {displayDateOpt(info.lastRunDateTime)}).
               Please check that the node is able to update it's policy by running 'rudder agent update' on the node.</p>
            <p>For information, expected node policies are displayed below.</p>
            <p>{currentConfigId(info)}</p>
          )

        case R.UnexpectedNoVersion =>
          (
            <p>This node is sending reports without a configuration ID (run started on the node at {
              info.lastRunConfigId.fold("unknown")(_.value)
            }), although one is required.</p>
            <p>Please run "rudder agent update -f" on the node to force a policy update.</p>
            <p>For information, expected node policies are displayed below.</p>
            <p>{currentConfigId(info)}</p>
          )

        case R.ReportsDisabledInInterval =>
          (
            <p>{currentConfigId(info)} and reports are disabled for that node.</p>
          )

        case R.UnexpectedUnknownVersion =>
          (
            <p>This node is sending reports from an unknown configuration policy (with configuration ID '{
              info.lastRunConfigId.fold("missing")(_.value)
            }'
               that is unknown to Rudder, run started at {displayDateOpt(info.lastRunDateTime)}).
               Please run "rudder agent update -f" on the node to force a policy update.</p>
            <p>For information, expected node policies are displayed below.</p>
            <p>{currentConfigId(info)}</p>
          )
      }
    }

    /*
     * Number of reports requiring attention. We only display that message if we are in a case where
     * compliance is actually meaningful.
     */
    val (background, lookReportsMessage) = report.runInfo.kind match {
      case R.NoRunNoExpectedReport | R.NoExpectedReport | R.UnexpectedVersion | R.UnexpectedNoVersion |
          R.UnexpectedUnknownVersion | R.NoReportInInterval =>
        ("alert alert-danger", NodeSeq.Empty)

      case R.ReportsDisabledInInterval =>
        ("progress-bar-reportsdisabled", NodeSeq.Empty)

      case R.Pending | R.NoUserRulesDefined =>
        ("alert alert-info", NodeSeq.Empty)

      case R.ComputeCompliance | R.KeepLastCompliance =>
        if (report.compliance.total <= 0) { // should not happen
          ("alert alert-success", NodeSeq.Empty)
        } else {
          val nbAttention = (
            report.compliance.noAnswer + report.compliance.missing + report.compliance.unexpected + report.compliance.badPolicyMode +
              report.compliance.error + report.compliance.nonCompliant + report.compliance.auditError
          )
          if (nbAttention > 0) {
            (
              "alert alert-warning",
              <p>{nbAttention} reports below (out of {
                report.compliance.total
              } total reports) are not in Success, and may require attention.</p>
            )
          } else if (report.runInfo.kind == R.KeepLastCompliance) {
            ("alert alert-warning", NodeSeq.Empty)
          } else if (report.compliance.computePercent().pending > 0) {
            ("alert alert-info", NodeSeq.Empty)
          } else {
            ("alert alert-success", <p>All reports received for this node are in Success.</p>)
          }
        }
    }

    val (updatedBackground, specialPolicyModeError) = report.statusInfo match {
      case RunComplianceInfo.OK | RunComplianceInfo.PolicyModeInconsistency(Nil) => (background, NodeSeq.Empty)
      case RunComplianceInfo.PolicyModeInconsistency(list)                       =>
        (
          "alert alert-danger",
          <div>
          <p>The node is reporting an error regarding the requested policy mode of the policies. This problem require special attention.</p>
          <ul>{
            list.map { error =>
              error match {
                case RunComplianceInfo.PolicyModeError.TechniqueMixedMode(msg)       => <li>{msg}</li>
                case RunComplianceInfo.PolicyModeError.AgentAbortMessage(cause, msg) =>
                  cause.toLowerCase match {
                    case "unsupported_dryrun"     =>
                      <li><b>That node does not support the request {
                        PolicyMode.Audit.name
                      } policy mode. The run was aborted to avoid changes</b></li>
                    case "repaired_during_dryrun" =>
                      <li><b>We detected a change for a check that was requested in {
                        PolicyMode.Audit.name
                      } policy mode. The run was aborted to further changes</b></li>
                    case "unsupported_agent"      =>
                      <li><b>That node runs an agent too old to run policies from this server, please upgrade the agent. The run was aborted to avoid any unexpected behavior</b></li>
                  }
              }
            }
          }</ul>
        </div>
        )
    }

    <div>
      <div id="node-compliance-intro" class={updatedBackground}>
        <p>{explainCompliance(report.runInfo)}</p>{
      specialPolicyModeError ++
      lookReportsMessage
    }</div>
    </div>
  }

  private def displayReports(
      node:               CoreNodeFact,
      getReports:         NodeId => Box[NodeStatusReport],
      tabId:              String,
      tableId:            String,
      containerId:        String,
      onlySystem:         Boolean,
      defaultRunInterval: Int
  )(implicit qc: QueryContext): NodeSeq = {
    val boxXml = (if (node.rudderSettings.state == NodeState.Ignored) {
                    Full(
                      <div><div class="col-md-3"><p class="center alert alert-info" style="padding: 25px; margin:5px;">This node is disabled.</p></div></div>
                    )
                  } else {
                    for {
                      report       <- getReports(node.id)
                      directiveLib <- directiveRepository.getFullDirectiveLibrary().toBox
                    } yield {

                      val runDate: Option[DateTime] = getRunDate(report.runInfo)

                      val intro = {
                        if (tableId == "reportsGrid") displayIntro(report, node.rudderSettings, defaultRunInterval)
                        else NodeSeq.Empty
                      }

                      /*
                       * Start a remote run for that node and display results.
                       * Remote runs are only supported on cfengine agent, so disable access to button for
                       * other kind of agent (windows in particular).
                       */
                      def triggerAgent(node: CoreNodeFact): NodeSeq = if (tableId == "reportsGrid") {
                        if (node.rudderAgent.agentType == AgentType.CfeCommunity) {
                          <div id="triggerAgent" class="mb-3">
            <button id="triggerBtn" class="btn btn-primary btn-trigger"  onclick={
                            s"callRemoteRun('${node.id.value}', ${refreshReportDetail(node, tableId, getReports, defaultRunInterval).toJsCmd});"
                          }>
              <span>Trigger agent</span>
              &nbsp;
              <i class="fa fa-play"></i>
            </button>
            &nbsp;
            <button id="visibilityOutput" class="btn btn-content btn-state" type="button" data-bs-toggle="collapse" data-bs-target="#report" aria-expanded="false" aria-controls="report" style="display: none;" >
            </button>
            &emsp;
            <div id="countDown" style="display:inline-block;">
              <span style="color:#b1bbcb;"></span>
            </div>
            <div id="report" style="margin-top:10px;" class="collapse">
              <pre class="p-2"></pre>
            </div>
          </div>
                        } else {
                          <div id="triggerAgent" class="mb-3">
            <button id="triggerBtn" class="btn btn-primary btn-trigger" disabled="disabled" title="This action is not supported for Windows node">
              <span>Trigger Agent</span>
              &nbsp;
              <i class="fa fa-play"></i>
            </button>
          </div>
                        }
                      } else {
                        NodeSeq.Empty
                      }

                      /*
                       * Now, on some case, we don't want to display 50% missing / 50% unexpected
                       * because the node config id is not correct (or related cases), we want to display
                       * what is expected config, but without the compliance part.
                       *
                       * And if don't even have expected configuration, don't display anything.
                       */

                      report.runInfo.kind match {
                        case R.NoRunNoExpectedReport | R.NoExpectedReport =>
                          (
                            "lastreportgrid-intro" #> intro
                            & "runagent" #> triggerAgent(node)
                            & "lastreportgrid-grid" #> NodeSeq.Empty
                            & "lastreportgrid-missing" #> NodeSeq.Empty
                            & "lastreportgrid-unexpected" #> NodeSeq.Empty
                          )(reportByNodeTemplate)

                        case R.UnexpectedVersion | R.UnexpectedNoVersion | R.UnexpectedUnknownVersion | R.NoReportInInterval |
                            R.ReportsDisabledInInterval | R.NoUserRulesDefined =>
                          (
                            "lastreportgrid-intro" #> intro
                            & "runagent" #> triggerAgent(node)
                            & "lastreportgrid-grid" #> showReportDetail(
                              node,
                              withCompliance = false,
                              onlySystem
                            )
                            & "lastreportgrid-missing" #> NodeSeq.Empty
                            & "lastreportgrid-unexpected" #> NodeSeq.Empty
                          )(reportByNodeTemplate) ++ displayRunLogs(node.id, runDate, tabId, tableId)

                        case R.Pending | R.ComputeCompliance | R.KeepLastCompliance =>
                          val missing    = getComponents(ReportType.Missing, report, directiveLib).toSet
                          val unexpected = getComponents(ReportType.Unexpected, report, directiveLib).toSet

                          (
                            "lastreportgrid-intro" #> intro
                            & "runagent" #> triggerAgent(node)
                            & "lastreportgrid-grid" #> showReportDetail(
                              node,
                              withCompliance = true,
                              onlySystem
                            )
                            & "lastreportgrid-missing" #> showMissingReports(missing, tableId)
                            & "lastreportgrid-unexpected" #> showUnexpectedReports(unexpected, tableId)
                          )(reportByNodeTemplate) ++ displayRunLogs(node.id, runDate, tabId, tableId)
                      }
                    }
                  })

    boxXml match {
      case e: EmptyBox =>
        logger.error(e)
        <div class="error">Could not fetch reports information</div>
      case Full(xml) => xml
    }
  }

  private def showReportDetail(
      node:           CoreNodeFact,
      withCompliance: Boolean,
      onlySystem:     Boolean
  ): NodeSeq = {
    // system compliance is not compliance, it's about how rudder works for that node, so linked to node perm
    if (onlySystem || CurrentUser.checkRights(AuthorizationType.Compliance.Read)) {
      <div id="nodecompliance-app"></div> ++
      Script(JsRaw(s"""
                      |var main = document.getElementById("nodecompliance-app")
                      |var initValues = {
                      |  nodeId : "${node.id.value}",
                      |  contextPath : contextPath,
                      |  onlySystem: ${onlySystem}
                      |};
                      |var app = Elm.Nodecompliance.init({node: main, flags: initValues});
                      |app.ports.errorNotification.subscribe(function(str) {
                      |  createErrorNotification(str)
                      |});
                      |// Initialize tooltips
                      |app.ports.initTooltips.subscribe(function(msg) {
                      |  setTimeout(function(){
                      |    initBsTooltips();
                      |  }, 800);
                      |});
                      |""".stripMargin))
    } else {
      <div class="alert alert-warning">
        <p></p>
        <p>You don't have enough rights to see compliance details.</p>
        <p></p>
      </div>
    }
  }

  // Handle show/hide logic of buttons, and rewrite button/container ids to avoid conflicts between tabs
  private def displayRunLogs(nodeId: NodeId, runDate: Option[DateTime], tabId: String, tableId: String): NodeSeq = {
    val btnId               = s"allLogButton-${tabId}"
    val logRunId            = s"logRun-${tabId}"
    val complianceLogGridId = s"complianceLogsGrid-${tabId}"

    val classes               = "btn btn-primary" + (if (runDate.isEmpty || tableId != "reportsGrid") " hide" else "")
    val onclick               = if (runDate.nonEmpty || tableId == "reportsGrid") {
      val init    = AnonFunc(logDisplayer.asyncDisplay(nodeId, runDate, complianceLogGridId))
      val refresh = AnonFunc(logDisplayer.ajaxRefresh(nodeId, runDate, complianceLogGridId))
      s"""showHideRunLogs("#${logRunId}", "${tabId}", ${init.toJsCmd}, ${refresh.toJsCmd})"""
    } else ""
    val btnHtml               = <button id={btnId} class={classes} onclick={onclick}>Show logs <i class="fa fa-table"></i></button>
    val hideBtnHtml           = <button id={s"hideLogButton-${tabId}"} class="btn btn-primary hide" onclick={
      s"showHideRunLogs('#node-compliance-intro', '${tabId}')"
    }>Hide logs</button>
    val complianceLogGridHtml =
      <table id={complianceLogGridId} cellspacing="0"></table>

    val replaceBtnHtml = Replace("AllLogButton", btnHtml) & Replace("AllLogHideButton", hideBtnHtml) & Replace(
      "complianceLogsGrid",
      complianceLogGridHtml
    )
    // Assign the button only when it already is in the DOM : the extractInlineJavaScript Lift parameter binds the onclick too early
    Script(OnLoad(JsRaw(s"""
      $$('div[id="logRun"]').prop('id', '${logRunId}');
      $$('button[id="AllLogButton"]').ready(${AnonFunc(replaceBtnHtml).toJsCmd});
    """)))
  }

  // this method cannot return an IOResult, as it uses S.
  // Only check for base compliance.
  private def getComplianceData(
      nodeId:       NodeId,
      reportStatus: NodeStatusReport
  )(implicit qc: QueryContext): Box[RuleComplianceLines] = {
    for {
      directiveLib <- directiveRepository.getFullDirectiveLibrary().toBox
      allNodeInfos <- nodeFactRepo.getAll().toBox
      rules        <- ruleRepository.getAll(true).toBox
      globalMode   <- configService.rudder_global_policy_mode().toBox
    } yield {
      ComplianceData.getNodeByRuleComplianceDetails(
        nodeId,
        reportStatus,
        PolicyTypeName.rudderBase,
        allNodeInfos.toMap,
        directiveLib,
        rules,
        globalMode
      )
    }
  }

  def showMissingReports(reports: Set[((String, String, List[String]), String, String)], tableId: String): NodeSeq = {
    def showMissingReport(report: ((String, String, List[String]), String, String)): NodeSeq = {
      val techniqueName    = report._2
      val techniqueVersion = report._3
      val reportValue      = report._1

      ("#technique *" #> "%s (%s)".format(techniqueName, techniqueVersion)
      & "#component *" #> reportValue._1
      & "#value *" #> reportValue._2)(
        <tr>
          <td id="technique"></td>
          <td id="component"></td>
          <td id="value"></td>
        </tr>
      )
    }

    /*
     * NOTE : a missing report is an unknown report with no message
     */
    val missingComponents = reports.filter(r => r._1._3.isEmpty)
    if (missingComponents.size > 0) {
      ("#reportLine" #> missingComponents.flatMap(showMissingReport(_))).apply(
        <h3>Missing reports</h3>
      <div>The following reports are what Rudder expected to receive, but did not. This usually indicates a bug in the Technique being used.</div>
      <table id={s"missingGrid${tableId}"}  cellspacing="0" style="clear:both">
        <thead>
          <tr class="head">
            <th>Technique<span/></th>
            <th>Component<span/></th>
            <th>Value<span/></th>
          </tr>
        </thead>
        <tbody>
          <div id="reportLine"/>
        </tbody>
      </table>
      <br/>
      ) ++
      buildTable(
        "missing",
        s"missingGrid${tableId}",
        """{ "sWidth": "150px" },
              { "sWidth": "150px" },
              { "sWidth": "150px" }"""
      )
    } else {
      NodeSeq.Empty
    }
  }

  def showUnexpectedReports(reports: Set[((String, String, List[String]), String, String)], tableId: String): NodeSeq = {
    def showUnexpectedReport(report: ((String, String, List[String]), String, String)): NodeSeq = {
      val techniqueName    = report._2
      val techniqueVersion = report._3
      val reportValue      = report._1

      ("#technique *" #> "%s (%s)".format(techniqueName, techniqueVersion)
      & "#component *" #> reportValue._1
      & "#value *" #> reportValue._2
      & "#message *" #> <ul>{reportValue._3.map(msg => <li>{msg}</li>)}</ul>)(
        <tr><td id="technique"></td><td id="component"></td><td id="value"></td><td id="message"></td></tr>
      )
    }

    /*
     * Note: unexpected reports are only the one with messages
     */
    val missingComponents = reports.filter(r => r._1._3.nonEmpty)
    if (missingComponents.size > 0) {
      ("#reportLine" #> missingComponents.flatMap(showUnexpectedReport(_))).apply(<h3>Unexpected reports</h3>
      <div>The following reports were received by Rudder, but did not match the reports declared by the Technique. This usually indicates a bug in the Technique being used.</div>

      <table id={s"unexpectedGrid${tableId}"}  cellspacing="0" style="clear:both">
        <thead>
          <tr class="head">
            <th>Technique<span/></th>
            <th>Component<span/></th>
            <th>Value<span/></th>
            <th>Message<span/></th>
          </tr>
        </thead>
        <tbody>
          <div id="reportLine"/>
        </tbody>
      </table>
      <br/>) ++
      buildTable(
        "unexpected",
        s"unexpectedGrid${tableId}",
        """{ "sWidth": "100px" },
             { "sWidth": "100px" },
             { "sWidth": "100px" },
             { "sWidth": "200px" }"""
      )
    } else {
      NodeSeq.Empty
    }
  }

  private def getComponents(
      status:            ReportType,
      nodeStatusReports: NodeStatusReport,
      directiveLib:      FullActiveTechniqueCategory
  ) = {
    /*
     * Note:
     * - missing reports are unexpected without error message
     * - unexpected reports are only the one with messages
     *
     * To get unexpected reports we have to find them in each node report
     * So we have to go the value level, get the messages
     * and also get technique details at directive level for each report
     * we could add more information at each level (directive name? rule name?)
     */
    for {
      (_, directive) <-
        DirectiveStatusReport.merge(nodeStatusReports.reports.toList.flatMap(_._2.reports.toList.flatMap(_.directives.values)))
      value          <- directive.getValues(v => v.status == status)
    } yield {
      val (techName, techVersion) = directiveLib.allDirectives
        .get(value._1)
        .map {
          case (tech, dir) =>
            (tech.techniqueName.value, dir.techniqueVersion.serialize)
        }
        .getOrElse(("Unknown technique", "N/A"))

      ((value._2, value._3.componentValue, value._3.messages.flatMap(_.message)), techName, techVersion)
    }
  }

  private def buildTable(name1: String, name2: String, colums: String): NodeSeq = {
    Script(JsRaw(s"""
     var oTable${name1} = $$('#${name2}').dataTable({
       "asStripeClasses": [ 'color1', 'color2' ],
       "bAutoWidth": false,
       "bFilter" : true,
       "bPaginate" : true,
       "bLengthChange": true,
       "bStateSave": true,
                    "fnStateSave": function (oSettings, oData) {
                      localStorage.setItem( 'DataTables_${name2}', JSON.stringify(oData) );
                    },
                    "fnStateLoad": function (oSettings) {
                      return JSON.parse( localStorage.getItem('DataTables_${name2}') );
                    },
       "sPaginationType": "full_numbers",
       "bJQueryUI": false,
       "oLanguage": {
         "sSearch": ""
       },
       "sDom": '<"dataTables_wrapper_top"f>rt<"dataTables_wrapper_bottom"lip>',
       "aaSorting": [[ 0, "asc" ]],
       "aoColumns": [
         ${colums}
       ]
     } );
    """)) // JsRaw ok, const
  }

}
