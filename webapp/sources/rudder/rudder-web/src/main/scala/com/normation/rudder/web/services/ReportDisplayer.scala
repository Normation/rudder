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
import com.normation.box._
import com.normation.cfclerk.services.TechniqueRepository
import com.normation.inventory.domain.AgentType
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.domain.nodes.NodeState
import com.normation.rudder.domain.policies.PolicyMode
import com.normation.rudder.domain.reports._
import com.normation.rudder.facts.nodes.CoreNodeFactRepository
import com.normation.rudder.repository.FullActiveTechniqueCategory
import com.normation.rudder.repository.RoDirectiveRepository
import com.normation.rudder.repository.RoRuleRepository
import com.normation.rudder.services.reports._
import com.normation.rudder.web.ChooseTemplate
import com.normation.rudder.web.model.JsNodeId
import net.liftweb.common._
import net.liftweb.http.SHtml
import net.liftweb.http.js.JE._
import net.liftweb.http.js.JsCmd
import net.liftweb.http.js.JsCmds._
import net.liftweb.util.Helpers._
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import scala.xml.NodeSeq
import scala.xml.NodeSeq.seqToNodeSeq

/**
 * Display the last reports of a server
 * Based on template : templates-hidden/reports_server
 */
class ReportDisplayer(
    ruleRepository:      RoRuleRepository,
    directiveRepository: RoDirectiveRepository,
    techniqueRepository: TechniqueRepository,
    nodeFactRepo:        CoreNodeFactRepository,
    configService:       ReadConfigService,
    logDisplayer:        LogDisplayer
) extends Loggable {

  def reportByNodeTemplate = ChooseTemplate(List("templates-hidden", "reports_server"), "batches-list")
  def directiveDetails     = ChooseTemplate(List("templates-hidden", "reports_server"), "directive:foreach")

  /**
   * Main entry point to display the tab with reports of a node.
   * It build up to 3 tables:
   * - general compliance table (displayed by rules)
   * - missing reports table if such reports exists
   * - unknown reports table if such reports exists
   * addOverriden decides if we need to add overriden policies (policy tab), or not (system tab)
   */
  def asyncDisplay(
      node:         NodeInfo,
      tabId:        String,
      containerId:  String,
      tableId:      String,
      getReports:   NodeId => Box[NodeStatusReport],
      addOverriden: Boolean
  ): NodeSeq = {
    val id       = JsNodeId(node.id)
    val callback =
      SHtml.ajaxInvoke(() => SetHtml(containerId, displayReports(node, getReports, tableId, containerId, addOverriden)))
    Script(OnLoad(JsRaw(s"""
      if($$("[aria-controls='${tabId}']").hasClass('ui-tabs-active')){
        ${callback.toJsCmd}
      }
      $$("#details_${id}").on( "tabsactivate", function(event, ui) {
        if(ui.newPanel.attr('id')== '${tabId}') {
          ${callback.toJsCmd}
        }
      });
    """)))
  }

  /**
   * Refresh the main compliance table
   */
  def refreshReportDetail(node: NodeInfo, tableId: String, getReports: NodeId => Box[NodeStatusReport], addOverriden: Boolean) = {
    def refreshData: Box[JsCmd] = {
      for {
        report <- getReports(node.id)
        data   <- getComplianceData(node.id, report, addOverriden)
        runDate: Option[DateTime] = report.runInfo match {
                                      case a: ComputeCompliance         => Some(a.lastRunDateTime)
                                      case a: LastRunAvailable          => Some(a.lastRunDateTime)
                                      case a: NoExpectedReport          => Some(a.lastRunDateTime)
                                      case a: NoReportInInterval        => None
                                      case a: Pending                   => a.optLastRun.map(_._1)
                                      case a: ReportsDisabledInInterval => None
                                      case NoRunNoExpectedReport => None

                                    }
      } yield {
        import net.liftweb.util.Helpers.encJs
        val intro = encJs(displayIntro(report).toString)
        JsRaw(s"""refreshTable("${tableId}",${data.json.toJsCmd}); $$("#node-compliance-intro").replaceWith(${intro})""")
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

  private[this] def displayIntro(report: NodeStatusReport): NodeSeq = {

    def explainCompliance(info: RunAndConfigInfo): NodeSeq = {
      val dateFormat = DateTimeFormat.forPattern("YYYY-MM-dd HH:mm:ssZ")

      def currentConfigId(expectedConfig: NodeExpectedReports) = {
        s"Current configuration ID for this node is '${expectedConfig.nodeConfigId.value}' (generated on ${expectedConfig.beginDate
            .toString(dateFormat)})"
      }

      info match {
        case ComputeCompliance(lastRunDateTime, expectedConfig, expirationDateTime) =>
          (<p>This node has up to date policy and the agent is running. Reports below are from the latest run, which started on the node at {
            lastRunDateTime.toString(dateFormat)
          }.</p>
            <p>{currentConfigId(expectedConfig)}.</p>)
        case NoUserRulesDefined(lastRunDateTime, expectedConfig, _, _, _)           =>
          (<p>This node has up to date policy and the agent is running, but no user rules are defined. Last run was started on the node at {
            lastRunDateTime.toString(dateFormat)
          }.</p>
            <p>{currentConfigId(expectedConfig)}.</p>)

        case Pending(expectedConfig, optLastRun, expirationDateTime) =>
          val runInfo = optLastRun match {
            case None             =>
              "No recent reports have been received for this node. Check that the agent is running (run 'rudder agent check' on the node)."
            case Some((date, id)) =>
              (id.endDate match {
                case None      =>
                  // here, if the date of last run is very old, the node was grey and it changed to blue due to the new config, but it's
                  // very unlikely that it will starts to answer.
                  val runIntervalMinutes =
                    expectedConfig.modes.nodeAgentRun.getOrElse(expectedConfig.modes.globalAgentRun).interval
                  val minDate            = expectedConfig.beginDate.minusMinutes(runIntervalMinutes * 2)
                  if (date.isBefore(minDate)) { // most likely a disconnected node
                    s"The node was reporting on a previous configuration policy, and didn't send reports since a long time: please" +
                    s" check that the node connection to server is correct. It should report on the new one at latest" +
                    s" ${expirationDateTime.toString(dateFormat)}. Previous known states are displayed below."
                  } else {
                    s"This is expected, the node is reporting on the previous configuration policy and should report on the new one at latest" +
                    s" ${expirationDateTime.toString(dateFormat)}. Previous known states are displayed below."
                  }
                case Some(exp) =>
                  s"This is unexpected, since the node is reporting on a configuration policy that expired at ${exp.toString(dateFormat)}."
              }) +
              s" The latest reports received for this node are from a run started at ${date.toString(dateFormat)} with configuration ID ${id.nodeConfigId.value}."
          }
          (
            <p>This node has recently been assigned a new policy but no reports have been received for the new policy yet.</p>
            <p>{runInfo}</p>
            <p>{currentConfigId(expectedConfig)}.</p>
          )

        case NoReportInInterval(expectedConfig, _) =>
          (
            <p>No recent reports have been received for this node in the grace period since the last configuration policy change.
               This is unexpected. Please check the status of the agent by running 'rudder agent health' on the node.</p>
            <p>For information, expected node policies are displayed below.</p>
            <p>{currentConfigId(expectedConfig)}.</p>
          )

        case NoRunNoExpectedReport =>
          <p>This is a new node that does not yet have a configured policy. If a policy generation is in progress, this will apply to this node when it is done.</p>

        case NoExpectedReport(lastRunDateTime, lastRunConfigId) =>
          val configIdmsg = lastRunConfigId match {
            case None     => "without a configuration ID, although one is required"
            case Some(id) => s"with configuration ID '${id.value}' that is unknown to Rudder"
          }

          <p>This node has no configuration policy assigned to it, but reports have been received for it {
            configIdmsg
          } (run started at {lastRunDateTime.toString(dateFormat)}).
             Either this node was deleted from Rudder but still has a running agent or the node is sending a corrupted configuration ID.
             Please run "rudder agent update -f" on the node to force a policy update and, if the problem persists,
             force a policy regeneration with the "Clear caches" button in Administration > Settings.</p>

        case UnexpectedVersion(
              lastRunDateTime,
              Some(lastRunConfigInfo),
              lastRunExpiration,
              expectedConfig,
              expectedExpiration,
              _
            ) =>
          (
            <p>This node is sending reports from an out-of-date configuration policy ({
              lastRunConfigInfo.nodeConfigId.value
            }, run started at {lastRunDateTime.toString(dateFormat)}).
               Please check that the node is able to update it's policy by running 'rudder agent update' on the node.</p>
            <p>For information, expected node policies are displayed below.</p>
            <p>{currentConfigId(expectedConfig)}</p>
          )

        case UnexpectedNoVersion(lastRunDateTime, lastRunConfigId, lastRunExpiration, expectedConfig, expectedExpiration, _) =>
          (
            <p>This node is sending reports without a configuration ID (run started on the node at {
              lastRunDateTime.toString(dateFormat)
            }), although one is required.</p>
            <p>Please run "rudder agent update -f" on the node to force a policy update.</p>
            <p>For information, expected node policies are displayed below.</p>
            <p>{currentConfigId(expectedConfig)}</p>
          )

        case ReportsDisabledInInterval(expectedConfigId, _) =>
          (
            <p>{currentConfigId(expectedConfigId)} and reports are disabled for that node.</p>
          )

        case UnexpectedUnknownVersion(lastRunDateTime, lastRunConfigId, expectedConfig, expectedExpiration, _) =>
          (
            <p>This node is sending reports from an unknown configuration policy (with configuration ID '{lastRunConfigId.value}'
               that is unknown to Rudder, run started at {lastRunDateTime.toString(dateFormat)}).
               Please run "rudder agent update -f" on the node to force a policy update.</p>
            <p>For information, expected node policies are displayed below.</p>
            <p>{currentConfigId(expectedConfig)}</p>
          )
      }

    }

    /*
     * Number of reports requiring attention. We only display that message if we are in a case where
     * compliance is actually meaningful.
     */
    val (background, lookReportsMessage) = report.runInfo match {
      case NoRunNoExpectedReport | _: NoExpectedReport | _: UnexpectedVersion | _: UnexpectedNoVersion |
          _: UnexpectedUnknownVersion | _: NoReportInInterval =>
        ("alert alert-danger", NodeSeq.Empty)

      case _: ReportsDisabledInInterval =>
        ("progress-bar-reportsdisabled", NodeSeq.Empty)

      case _: Pending | _: NoUserRulesDefined =>
        ("bg-info text-info", NodeSeq.Empty)

      case _: ComputeCompliance =>
        if (report.compliance.total <= 0) { // should not happen
          ("bg-success text-success", NodeSeq.Empty)
        } else {
          val nbAttention = (
            report.compliance.noAnswer + report.compliance.missing + report.compliance.unexpected + report.compliance.badPolicyMode +
              report.compliance.error + report.compliance.nonCompliant + report.compliance.auditError
          )
          if (nbAttention > 0) {
            (
              "bg-warning text-warning",
              <p>{nbAttention} reports below (out of {
                report.compliance.total
              } total reports) are not in Success, and may require attention.</p>
            )
          } else if (report.compliance.computePercent().pending > 0) {
            ("bg-info text-info", NodeSeq.Empty)

          } else {
            ("bg-success text-success", <p>All reports received for this node are in Success.</p>)
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
            list.map(error => {
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
            })
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

  private[this] def displayReports(
      node:         NodeInfo,
      getReports:   NodeId => Box[NodeStatusReport],
      tableId:      String,
      containerId:  String,
      addOverriden: Boolean
  ): NodeSeq = {
    val boxXml = (if (node.state == NodeState.Ignored) {
                    Full(
                      <div><div class="col-sm-3"><p class="center bg-info" style="padding: 25px; margin:5px;">This node is disabled.</p></div></div>
                    )
                  } else {
                    for {
                      report       <- getReports(node.id)
                      directiveLib <- directiveRepository.getFullDirectiveLibrary().toBox
                    } yield {

                      val runDate: Option[DateTime] = report.runInfo match {
                        case a: ComputeCompliance         => Some(a.lastRunDateTime)
                        case a: LastRunAvailable          => Some(a.lastRunDateTime)
                        case a: NoExpectedReport          => Some(a.lastRunDateTime)
                        case a: NoReportInInterval        => None
                        case a: Pending                   => a.optLastRun.map(_._1)
                        case a: ReportsDisabledInInterval => None
                        case NoRunNoExpectedReport => None
                      }

                      val intro = if (tableId == "reportsGrid") displayIntro(report) else NodeSeq.Empty

                      /*
                       * Start a remote run for that node and display results.
                       * Remoterun are only supported on cfengine agent, so disable access to button for
                       * other kind of agent (windows in particular).
                       */
                      def triggerAgent(node: NodeInfo): NodeSeq = if (tableId == "reportsGrid") {
                        if (
                          node.agentsName.exists(agent =>
                            agent.agentType == AgentType.CfeCommunity || agent.agentType == AgentType.CfeEnterprise
                          )
                        ) {
                          <div id="triggerAgent">
            <button id="triggerBtn" class="btn btn-primary btn-trigger"  onClick={
                            s"callRemoteRun('${node.id.value}', ${refreshReportDetail(node, tableId, getReports, addOverriden).toJsCmd});"
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
              <pre></pre>
            </div>
          </div>
                        } else {
                          <div id="triggerAgent">
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

                      report.runInfo match {
                        case NoRunNoExpectedReport | _: NoExpectedReport =>
                          (
                            "lastreportgrid-intro" #> intro
                            & "runagent" #> triggerAgent(node)
                            & "lastreportgrid-grid" #> NodeSeq.Empty
                            & "lastreportgrid-missing" #> NodeSeq.Empty
                            & "lastreportgrid-unexpected" #> NodeSeq.Empty
                          )(reportByNodeTemplate)

                        case _: UnexpectedVersion | _: UnexpectedNoVersion | _: UnexpectedUnknownVersion | _: NoReportInInterval |
                            _: ReportsDisabledInInterval | _: NoUserRulesDefined =>
                          /*
                           * In these case, filter out "unexpected" reports to only
                           * keep missing ones, and do not show the "compliance" row.
                           */
                          val filtered = NodeStatusReport(
                            report.nodeId,
                            report.runInfo,
                            report.statusInfo,
                            report.overrides,
                            report.reports.flatMap { x =>
                              x.withFilteredElements(
                                _ => true, // keep all (non empty) directives

                                _ => true, // keep all (non empty) component values

                                value => { // filter values based on the message type - we don't want Unexpected values
                                  value.messages.forall(m => m.reportType != ReportType.Unexpected)
                                }
                              )
                            }
                          )

                          (
                            "lastreportgrid-intro" #> intro
                            & "runagent" #> triggerAgent(node)
                            & "lastreportgrid-grid" #> showReportDetail(
                              filtered,
                              node,
                              withCompliance = false,
                              tableId,
                              containerId,
                              getReports,
                              addOverriden
                            )
                            & "#AllLogButton  [class+]" #> { if (runDate.isEmpty || tableId != "reportsGrid") "hide" else "" }
                            & "#AllLogButton  [onClick]" #> {
                              if (runDate.nonEmpty || tableId == "reportsGrid") {
                                val init    = AnonFunc(logDisplayer.asyncDisplay(node.id, runDate, "complianceLogsGrid"))
                                val refresh = AnonFunc(logDisplayer.ajaxRefresh(node.id, runDate, "complianceLogsGrid"))
                                s"""showHideRunLogs("#logRun", ${init.toJsCmd}, ${refresh.toJsCmd})"""
                              } else ""
                            }
                            & "lastreportgrid-missing" #> NodeSeq.Empty
                            & "lastreportgrid-unexpected" #> NodeSeq.Empty
                          )(reportByNodeTemplate)

                        case _: Pending | _: ComputeCompliance =>
                          val missing    = getComponents(ReportType.Missing, report, directiveLib).toSet
                          val unexpected = getComponents(ReportType.Unexpected, report, directiveLib).toSet

                          (
                            "lastreportgrid-intro" #> intro
                            & "runagent" #> triggerAgent(node)
                            & "lastreportgrid-grid" #> showReportDetail(
                              report,
                              node,
                              withCompliance = true,
                              tableId,
                              containerId,
                              getReports,
                              addOverriden
                            )
                            & "#AllLogButton [class+]" #> { if (runDate.isEmpty || tableId != "reportsGrid") "hide" else "" }
                            & "#AllLogButton [onClick]" #> {
                              if (runDate.nonEmpty || tableId == "reportsGrid") {
                                val init    = AnonFunc(logDisplayer.asyncDisplay(node.id, runDate, "complianceLogsGrid"))
                                val refresh = AnonFunc(logDisplayer.ajaxRefresh(node.id, runDate, "complianceLogsGrid"))
                                s"""showHideRunLogs("#logRun",${init.toJsCmd}, ${refresh.toJsCmd})"""
                              } else ""
                            }
                            & "lastreportgrid-missing" #> showMissingReports(missing, tableId)
                            & "lastreportgrid-unexpected" #> showUnexpectedReports(unexpected, tableId)
                          )(reportByNodeTemplate)
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

  private[this] def showReportDetail(
      reports:        NodeStatusReport,
      node:           NodeInfo,
      withCompliance: Boolean,
      tableId:        String,
      id:             String,
      getReports:     NodeId => Box[NodeStatusReport],
      addOverriden:   Boolean
  ): NodeSeq = {
    <div id="nodecompliance-app"></div> ++
    Script(JsRaw(s"""
                    |var main = document.getElementById("nodecompliance-app")
                    |var initValues = {
                    |  nodeId : "${node.id.value}",
                    |  contextPath : contextPath
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
  }

  // this method cannot return an IOResult, as it uses S.
  private[this] def getComplianceData(
      nodeId:       NodeId,
      reportStatus: NodeStatusReport,
      addOverriden: Boolean
  ): Box[JsTableData[RuleComplianceLine]] = {
    for {
      directiveLib <- directiveRepository.getFullDirectiveLibrary().toBox
      allNodeInfos <- nodeFactRepo.getAll()(CurrentUser.queryContext).toBox
      rules        <- ruleRepository.getAll(true).toBox
      globalMode   <- configService.rudder_global_policy_mode().toBox
    } yield {
      ComplianceData.getNodeByRuleComplianceDetails(
        nodeId,
        reportStatus,
        allNodeInfos.mapValues(_.toNodeInfo).toMap,
        directiveLib,
        rules,
        globalMode,
        addOverriden
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

  private[this] def getComponents(
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
      (_, directive) <- DirectiveStatusReport.merge(nodeStatusReports.reports.toList.flatMap(_.directives.values))
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

  private[this] def buildTable(name1: String, name2: String, colums: String): NodeSeq = {
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
    """))
  }

}
