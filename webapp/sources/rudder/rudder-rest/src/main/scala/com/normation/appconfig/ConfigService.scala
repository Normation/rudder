/*
*************************************************************************************
* Copyright 2013 Normation SAS
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
package com.normation.appconfig

import com.normation.NamedZioLogger
import net.liftweb.common.Full
import com.typesafe.config.Config
import com.normation.rudder.batch.AsyncWorkflowInfo
import com.normation.rudder.services.workflows.WorkflowUpdate
import com.typesafe.config.ConfigFactory
import com.normation.rudder.domain.appconfig.RudderWebProperty
import com.normation.rudder.domain.appconfig.RudderWebPropertyName
import com.normation.rudder.reports.ComplianceMode
import com.normation.rudder.domain.appconfig.RudderWebProperty
import com.normation.rudder.reports.FullCompliance
import com.normation.rudder.reports.ChangesOnly
import com.normation.eventlog.EventActor
import com.normation.rudder.domain.eventlog.ModifySendServerMetricsEventType
import com.normation.rudder.domain.eventlog.ModifyComplianceModeEventType
import com.normation.rudder.domain.eventlog.ModifyHeartbeatPeriodEventType
import net.liftweb.common.EmptyBox
import com.normation.rudder.domain.eventlog.ModifyAgentRunIntervalEventType
import com.normation.rudder.domain.eventlog.ModifyAgentRunStartHourEventType
import com.normation.rudder.domain.eventlog.ModifyAgentRunStartMinuteEventType
import com.normation.rudder.domain.eventlog.ModifyAgentRunSplaytimeEventType
import com.normation.rudder.reports._
import com.normation.rudder.domain.eventlog.ModifyRudderSyslogProtocolEventType

import scala.language.implicitConversions
import com.normation.rudder.domain.appconfig.FeatureSwitch
import com.normation.rudder.domain.nodes.NodeState
import com.normation.rudder.domain.policies.PolicyMode
import com.normation.rudder.domain.policies.PolicyMode._
import com.normation.rudder.domain.policies.GlobalPolicyMode
import com.normation.rudder.domain.policies.PolicyModeOverrides
import com.normation.rudder.services.reports.UnexpectedReportBehavior
import com.normation.rudder.services.reports.UnexpectedReportInterpretation
import com.normation.rudder.services.servers.RelaySynchronizationMethod._
import com.normation.rudder.services.servers.RelaySynchronizationMethod
import com.normation.rudder.services.workflows.WorkflowLevelService
import com.normation.errors._
import scalaz.zio._
import scalaz.zio.syntax._

/**
 * A service that Read mutable (runtime) configuration properties
 *
 * Configuration read by that service MUST NOT BE CACHED.
 * Else, of course, they loose their runtime/mutable property.
 *
 */
trait ReadConfigService {

  /**
   * Change message properties
   */
  def rudder_ui_changeMessage_enabled() : IOResult[Boolean]
  def rudder_ui_changeMessage_mandatory() : IOResult[Boolean]
  def rudder_ui_changeMessage_explanation() : IOResult[String]

  /**
   * Workflow.
   * The semantic of that property for "enabled" depends ALSO
   * of the plugins installed for workflows: if you don't have
   * workflow plugin, you won't be able to enable it. And in the
   * line, once you have the plugin, you need to enable it their.
   */
  def rudder_workflow_enabled(): IOResult[Boolean]
  def rudder_workflow_self_validation(): IOResult[Boolean]
  def rudder_workflow_self_deployment(): IOResult[Boolean]

  /**
   * CFEngine server properties
   */
  def cfengine_server_denybadclocks(): IOResult[Boolean]

  /**
   * Relay synchronization configuration
   */
  def relay_server_sync_method()       : IOResult[RelaySynchronizationMethod]
  def relay_server_syncpromises()      : IOResult[Boolean]
  def relay_server_syncsharedfiles()   : IOResult[Boolean]

  /**
   * Agent execution interval and start run
   */
  def agent_run_interval(): IOResult[Int]

  def agent_run_splaytime(): IOResult[Int]
  def agent_run_start_hour(): IOResult[Int]
  def agent_run_start_minute(): IOResult[Int]

  /**
   * CFEngine global properties
   */
  def cfengine_modified_files_ttl(): IOResult[Int]
  def cfengine_outputs_ttl(): IOResult[Int]

  /**
   * Logging properties
   */
  def rudder_store_all_centralized_logs_in_file(): IOResult[Boolean]

  /**
   * Compliance mode: See ComplianceMode class for more details
   */
  def rudder_compliance_mode(): IOResult[GlobalComplianceMode] = {
    for {
        name     <- rudder_compliance_mode_name
        modeName <- ComplianceModeName.parse(name).toIO
        period   <- rudder_compliance_heartbeatPeriod
    } yield {
      GlobalComplianceMode(modeName,period)
    }
  }

  def rudder_compliance_mode_name(): IOResult[String]

  def rudder_compliance_heartbeatPeriod(): IOResult[Int]

  /**
   * Policy mode: See PolicyMode class for more details
   */
  def rudder_global_policy_mode(): IOResult[GlobalPolicyMode] = {
    for {
        mode        <- rudder_policy_mode_name
        overridable <- rudder_policy_overridable
    } yield {
      GlobalPolicyMode(mode, if(overridable) PolicyModeOverrides.Always else PolicyModeOverrides.Unoverridable)
    }
  }
  def rudder_policy_mode_name(): IOResult[PolicyMode]
  def rudder_policy_overridable(): IOResult[Boolean]

  /**
   * Send Metrics
   */
  def send_server_metrics(): IOResult[Option[Boolean]]

  /**
   * Report protocol
   */
  def rudder_syslog_protocol(): IOResult[SyslogProtocol]

  /**
   * Should we display recent changes graphs  ?
   */
  def display_changes_graph(): IOResult[Boolean]

  /**
   * Should we hide compliance/recent changes column in directive screen for rule ?
   */
  def rudder_ui_display_ruleComplianceColumns(): IOResult[Boolean]

  /**
   * Should we activate the script engine bar ?
   */
  def rudder_featureSwitch_directiveScriptEngine(): IOResult[FeatureSwitch]

  /**
   * Default value for node properties after acceptation:
   * - policy mode
   * - node lifecycle state
   */
  def rudder_node_onaccept_default_policy_mode(): IOResult[Option[PolicyMode]]
  def rudder_node_onaccept_default_state(): IOResult[NodeState]

  /**
   * What is the behavior to adopt regarding unexpected reports ?
   */
  def rudder_compliance_unexpected_report_interpretation(): IOResult[UnexpectedReportInterpretation]
}

/**
 * A service that modify existing config parameters
 */
trait UpdateConfigService {

  def set_rudder_ui_changeMessage_enabled(value: Boolean): IOResult[Unit]
  def set_rudder_ui_changeMessage_mandatory(value: Boolean): IOResult[Unit]
  def set_rudder_ui_changeMessage_explanation(value: String): IOResult[Unit]

  /**
   * Workflows
   */
  def set_rudder_workflow_enabled(value: Boolean): IOResult[Unit]
  def set_rudder_workflow_self_validation(value: Boolean): IOResult[Unit]
  def set_rudder_workflow_self_deployment(value: Boolean): IOResult[Unit]

  /**
   * Set CFEngine server properties
   */
  def set_cfengine_server_denybadclocks(value: Boolean): IOResult[Unit]
  def set_cfengine_server_skipidentify(value: Boolean): IOResult[Unit]

  /**
   * Set Relay-Server synchronization method
   */
  def set_relay_server_sync_method(value: RelaySynchronizationMethod): IOResult[Unit]
  def set_relay_server_syncpromises(value: Boolean)    : IOResult[Unit]
  def set_relay_server_syncsharedfiles(value: Boolean) : IOResult[Unit]

  /**
   * Agent frequency and start run
   */
  def set_agent_run_interval(value: Int, actor : EventActor, reason: Option[String]): IOResult[Unit]
  def set_agent_run_splaytime(value: Int, actor : EventActor, reason: Option[String]): IOResult[Unit]
  def set_agent_run_start_hour(value: Int, actor : EventActor, reason: Option[String]): IOResult[Unit]
  def set_agent_run_start_minute(value: Int, actor : EventActor, reason: Option[String]): IOResult[Unit]

  /**
   * Set CFEngine global properties
   */
  def set_cfengine_modified_files_ttl(value: Int): IOResult[Unit]
  def set_cfengine_outputs_ttl(value: Int): IOResult[Unit]

  /**
   * Logging properties
   */
  def set_rudder_store_all_centralized_logs_in_file(value: Boolean): IOResult[Unit]

  /**
   * Send Metrics
   */
  def set_send_server_metrics(value : Option[Boolean], actor : EventActor, reason: Option[String]): IOResult[Unit]

  /**
   * Set the compliance mode
   */
  def set_rudder_compliance_mode(mode : ComplianceMode, actor: EventActor, reason: Option[String]): IOResult[Unit] = {
    for {
      _ <- set_rudder_compliance_mode_name(mode.name,actor, reason)
      u <- mode.name match {
             case ChangesOnly.name =>  set_rudder_compliance_heartbeatPeriod(mode.heartbeatPeriod, actor, reason)
             case _ => UIO.unit
           }
    } yield {
      u
    }

  }

  def set_rudder_compliance_mode_name(name : String, actor : EventActor, reason: Option[String]) : IOResult[Unit]

  def set_rudder_compliance_heartbeatPeriod(frequency : Int, actor: EventActor, reason: Option[String]) : IOResult[Unit]

  /**
   * Report protocol
   */
  def set_rudder_syslog_protocol(value : SyslogProtocol, actor : EventActor, reason: Option[String]): IOResult[Unit]

  /**
   * Should we display recent changes graphs  ?
   */
  def set_display_changes_graph(displayGraph : Boolean): IOResult[Unit]

  /**
   * Should we hide compliance/recent changes column in directive screen for rule ?
   */
  def set_rudder_ui_display_ruleComplianceColumns(Columns: Boolean): IOResult[Unit]

  /**
   * Should we evaluate scripts in variable values?
   */
  def set_rudder_featureSwitch_directiveScriptEngine(status: FeatureSwitch): IOResult[Unit]

/**
   * Set the compliance mode
   */
  def set_rudder_policy_mode(mode : GlobalPolicyMode, actor: EventActor, reason: Option[String]): IOResult[Unit] = {
    for {
      _ <- set_rudder_policy_mode_name(mode.mode, actor, reason)
      u <- set_rudder_policy_overridable(if(mode.overridable == PolicyModeOverrides.Always) true else false, actor, reason)
    } yield {
      u
    }

  }

  def set_rudder_policy_mode_name(name : PolicyMode, actor : EventActor, reason: Option[String]) : IOResult[Unit]

  def set_rudder_policy_overridable(overridable : Boolean, actor: EventActor, reason: Option[String]) : IOResult[Unit]

  /**
   * Default value for node properties after acceptation:
   * - policy mode
   * - node lifecycle state
   */
  def set_rudder_node_onaccept_default_policy_mode(policyMode: Option[PolicyMode]): IOResult[Unit]
  def set_rudder_node_onaccept_default_state(nodeState: NodeState): IOResult[Unit]
  def set_rudder_compliance_unexpected_report_interpretation(mode: UnexpectedReportInterpretation) : IOResult[Unit]
}

class LDAPBasedConfigService(
    configFile    : Config
  , repos         : ConfigRepository
  , workflowUpdate: AsyncWorkflowInfo
  , workflowLevel : WorkflowLevelService
) extends ReadConfigService with UpdateConfigService with NamedZioLogger {

  override def loggerName: String = this.getClass.getName

  /**
   * Create a cache for values that should never fail
   */

  val defaultConfig =
    s"""rudder.ui.changeMessage.enabled=true
       rudder.ui.changeMessage.mandatory=false
       rudder.ui.changeMessage.explanation=Please enter a reason explaining this change.
       rudder.workflow.enabled=false
       rudder.workflow.self.validation=false
       rudder.workflow.self.deployment=true
       cfengine.server.denybadclocks=true
       cfengine.server.skipidentify=false
       relay.sync.method=classic
       relay.sync.promises=true
       relay.sync.sharedfiles=true
       agent.run.interval=5
       agent.run.splaytime=4
       agent.run.start.hour=0
       agent.run.start.minute=0
       cfengine.modified.files.ttl=30
       cfengine.outputs.ttl=7
       rudder.store.all.centralized.logs.in.file=true
       send.server.metrics=none
       rudder.compliance.mode=${FullCompliance.name}
       rudder.compliance.heartbeatPeriod=1
       rudder.syslog.protocol=UDP
       display.changes.graph=true
       rudder.ui.display.ruleComplianceColumns=false
       rudder.policy.mode.name=${Enforce.name}
       rudder.policy.mode.overridable=true
       rudder.featureSwitch.directiveScriptEngine=enabled
       rudder.node.onaccept.default.state=enabled
       rudder.node.onaccept.default.policyMode=default
       rudder.compliance.unexpectedReportAllowsDuplicate=true
       rudder.compliance.unexpectedReportUnboundedVarValues=true
    """

  val configWithFallback = configFile.withFallback(ConfigFactory.parseString(defaultConfig))

  /*
   *  Correct implementation use a macro in place of all the
   *  redondant calls...
   *
   */

  private[this] def get[T](name: String)(implicit converter: RudderWebProperty => T) : IOResult[T] = {
    for {
      params <- repos.getConfigParameters
      param  <- params.find( _.name.value == name) match {
                  case None =>
                    val configName = name.replaceAll("_", ".")
                    val value      = configWithFallback.getString(configName)
                    save(name, value)
                  case Some(p) => p.succeed
                }
    } yield {
      param
    }
  }

  private[this] def save[T](name: String, value: T, modifyGlobalPropertyInfo : Option[ModifyGlobalPropertyInfo] = None)(implicit ser: T => String): IOResult[RudderWebProperty] = {
    val p = RudderWebProperty(RudderWebPropertyName(name), ser(value), "")
    repos.saveConfigParameter(p,modifyGlobalPropertyInfo)
  }

  private[this] implicit def serInt(x: Int): String = x.toString
  private[this] implicit def serPolicyMode(x: PolicyMode): String = x.name
  private[this] implicit def serFeatureSwitch(x: FeatureSwitch): String = x.name


  private[this] implicit def toBoolean(p: RudderWebProperty): Boolean = p.value.toLowerCase match {
    case "true" | "1" => true
    case _ => false
  }
  private[this] implicit def serBoolean(x: Boolean): String = if(x) "true" else "false"

  private[this] implicit def toOptionBoolean(p: RudderWebProperty): Option[Boolean] = p.value.toLowerCase match {
    case "true" | "1" => Some(true)
    case "none" => None
    case _ => Some(false)
  }

  private[this] implicit def toSyslogProtocol(p: RudderWebProperty): SyslogProtocol = p.value match {
    case SyslogTCP.value => // value is TCP
      SyslogTCP
    case _ => SyslogUDP
  }

  private[this] implicit def toOptionPolicyMode(p: RudderWebProperty): Option[PolicyMode] = {
    PolicyMode.allModes.find( _.name == p.value.toLowerCase())
  }

  private[this] implicit def serOptionPolicyMode(x: Option[PolicyMode]): String = x match {
    case None    => "default"
    case Some(p) => p.name
  }

  private[this] implicit def toNodeState(p: RudderWebProperty): NodeState = {
    NodeState.values.find( _.name == p.value.toLowerCase()).getOrElse(NodeState.Enabled) //default value is "enabled"
  }

  private[this] implicit def serState(x: NodeState): String = x.name

  private[this] implicit def toRelaySynchronisationMethod(p: RudderWebProperty): RelaySynchronizationMethod = p.value match {
    case Rsync.value    => Rsync
    case Disabled.value => Disabled
    case _              => Classic  // default is classic
  }

  private[this] implicit def toString(p: RudderWebProperty): String = p.value

  private[this] implicit def toUnit(p: IOResult[RudderWebProperty]) : IOResult[Unit] = p.map( _ => ())

  private[this] implicit def toInt(p: IOResult[RudderWebProperty]) : IOResult[Int] = {
    try {
      p.map(Integer.parseInt(_))
    } catch {
      case ex:NumberFormatException => Unconsistancy(ex.getMessage).fail
    }
  }

  /**
   * A feature switch is defaulted to Disabled is parsing fails.
   */
  private[this] implicit def toFeatureSwitch(p: RudderWebProperty): FeatureSwitch = FeatureSwitch.parse(p.value) match {
    case Full(status) => status
    case eb: EmptyBox =>
      val e = eb ?~! s"Error when trying to parse property '${p.name}' with value '${p.value}' into a feature switch status"
      logEffect.warn(e.messageChain)
      FeatureSwitch.Disabled
  }

  def rudder_ui_changeMessage_enabled() = get("rudder_ui_changeMessage_enabled")
  def rudder_ui_changeMessage_mandatory() = get("rudder_ui_changeMessage_mandatory")
  def rudder_ui_changeMessage_explanation() = get("rudder_ui_changeMessage_explanation")
  def set_rudder_ui_changeMessage_enabled(value: Boolean): IOResult[Unit] = save("rudder_ui_changeMessage_enabled", value)
  def set_rudder_ui_changeMessage_mandatory(value: Boolean): IOResult[Unit] = save("rudder_ui_changeMessage_mandatory", value)
  def set_rudder_ui_changeMessage_explanation(value: String): IOResult[Unit] = save("rudder_ui_changeMessage_explanation", value)

  ///// workflows /////
  def rudder_workflow_enabled() = {
    if(workflowLevel.workflowLevelAllowsEnable) {
      get("rudder_workflow_enabled")
    } else {
      false.succeed
    }
  }
  def rudder_workflow_self_validation() = get("rudder_workflow_self_validation")
  def rudder_workflow_self_deployment() = get("rudder_workflow_self_deployment")
  def set_rudder_workflow_enabled(value: Boolean): IOResult[Unit] = {
    if(workflowLevel.workflowLevelAllowsEnable) {
      save("rudder_workflow_enabled", value) <*
      IOResult.effect(workflowUpdate ! WorkflowUpdate)
    } else {
      Unconsistancy("You can't change the change validation workflow type. Perhaps are you missing the 'changes validation' plugin?").fail
    }
  }
  def set_rudder_workflow_self_validation(value: Boolean): IOResult[Unit] = save("rudder_workflow_self_validation", value)
  def set_rudder_workflow_self_deployment(value: Boolean): IOResult[Unit] = save("rudder_workflow_self_deployment", value)

  ///// CFEngine server /////
  def cfengine_server_denybadclocks(): IOResult[Boolean] = get("cfengine_server_denybadclocks")
  def set_cfengine_server_denybadclocks(value: Boolean): IOResult[Unit] = save("cfengine_server_denybadclocks", value)
  def cfengine_server_skipidentify(): IOResult[Boolean] = get("cfengine_server_skipidentify")
  def set_cfengine_server_skipidentify(value: Boolean): IOResult[Unit] = save("cfengine_server_skipidentify", value)

  // Relay synchronization configuration
  def relay_server_sync_method()     : IOResult[RelaySynchronizationMethod] = get("relay.sync.method")
  def set_relay_server_sync_method(value: RelaySynchronizationMethod): IOResult[Unit] = save("relay.sync.method", value.value)
  def relay_server_syncpromises()                      : IOResult[Boolean] = get("relay.sync.promises")
  def set_relay_server_syncpromises(value    : Boolean): IOResult[Unit]    = save("relay.sync.promises", value)
  def relay_server_syncsharedfiles()                   : IOResult[Boolean] = get("relay.sync.sharedfiles")
  def set_relay_server_syncsharedfiles(value: Boolean) : IOResult[Unit]    = save("relay.sync.sharedfiles", value)

  def agent_run_interval(): IOResult[Int] = get("agent_run_interval")
  def set_agent_run_interval(value: Int, actor: EventActor, reason: Option[String]): IOResult[Unit] = {
    val info = ModifyGlobalPropertyInfo(ModifyAgentRunIntervalEventType,actor,reason)
    save("agent_run_interval", value, Some(info))
  }

  def agent_run_splaytime(): IOResult[Int] = get("agent_run_splaytime")
  def set_agent_run_splaytime(value: Int, actor: EventActor, reason: Option[String]): IOResult[Unit] = {
    val info = ModifyGlobalPropertyInfo(ModifyAgentRunSplaytimeEventType,actor,reason)
    save("agent_run_splaytime", value, Some(info))
  }

  def agent_run_start_hour(): IOResult[Int] = get("agent_run_start_hour")
  def set_agent_run_start_hour(value: Int, actor: EventActor, reason: Option[String]): IOResult[Unit] = {
    val info = ModifyGlobalPropertyInfo(ModifyAgentRunStartHourEventType,actor,reason)
    save("agent_run_start_hour", value, Some(info))
  }

  def agent_run_start_minute(): IOResult[Int] = get("agent_run_start_minute")
  def set_agent_run_start_minute(value: Int, actor: EventActor, reason: Option[String]): IOResult[Unit] = {
    val info = ModifyGlobalPropertyInfo(ModifyAgentRunStartMinuteEventType,actor,reason)
    save("agent_run_start_minute", value, Some(info))
  }

  ///// CFEngine server /////
  def cfengine_modified_files_ttl(): IOResult[Int] = get("cfengine_modified_files_ttl")
  def set_cfengine_modified_files_ttl(value: Int): IOResult[Unit] = save("cfengine_modified_files_ttl", value)
  def cfengine_outputs_ttl(): IOResult[Int] = get("cfengine_outputs_ttl")
  def set_cfengine_outputs_ttl(value: Int): IOResult[Unit] = save("cfengine_outputs_ttl", value)

  /**
   * Logging properties
   */
  def rudder_store_all_centralized_logs_in_file(): IOResult[Boolean] = get("rudder_store_all_centralized_logs_in_file")
  def set_rudder_store_all_centralized_logs_in_file(value: Boolean) = save("rudder_store_all_centralized_logs_in_file", value)

  /**
   * Compliance mode
   */
  def rudder_compliance_mode_name(): IOResult[String] = get("rudder_compliance_mode")
  def set_rudder_compliance_mode_name(value: String, actor : EventActor, reason: Option[String]): IOResult[Unit] = {
    val info = ModifyGlobalPropertyInfo(ModifyComplianceModeEventType,actor,reason)
    save("rudder_compliance_mode", value, Some(info))
  }

  /**
   * Heartbeat frequency mode
   */
  def rudder_compliance_heartbeatPeriod(): IOResult[Int] = get("rudder_compliance_heartbeatPeriod")
  def set_rudder_compliance_heartbeatPeriod(value: Int, actor: EventActor, reason: Option[String]): IOResult[Unit] = {
    val info = ModifyGlobalPropertyInfo(ModifyHeartbeatPeriodEventType,actor,reason)
    save("rudder_compliance_heartbeatPeriod", value, Some(info))
  }

  def rudder_policy_mode_name(): IOResult[PolicyMode] = get("rudder_policy_mode_name").flatMap { PolicyMode.parse(_).toIO }
  def set_rudder_policy_mode_name(name : PolicyMode, actor : EventActor, reason: Option[String]) : IOResult[Unit] = {
    val info = ModifyGlobalPropertyInfo(ModifyComplianceModeEventType,actor,reason)
    save("rudder_policy_mode_name", name, Some(info))
  }

  def rudder_policy_overridable(): IOResult[Boolean] = get("rudder_policy_mode_overridable")
  def set_rudder_policy_overridable(overridable : Boolean, actor: EventActor, reason: Option[String]) : IOResult[Unit] = {
    val info = ModifyGlobalPropertyInfo(ModifyComplianceModeEventType,actor,reason)
    save("rudder_policy_mode_overridable", overridable, Some(info))
  }

  /**
   * Send Metrics
   */
  def send_server_metrics(): IOResult[Option[Boolean]] = get("send_server_metrics")

  def set_send_server_metrics(value : Option[Boolean], actor : EventActor, reason: Option[String]): IOResult[Unit] = {
    val newVal = value.map(_.toString).getOrElse("none")
    val info = ModifyGlobalPropertyInfo(ModifySendServerMetricsEventType,actor,reason)
    save("send_server_metrics",newVal,Some(info))
  }

  /**
   * Report protocol
   */
  def rudder_syslog_protocol(): IOResult[SyslogProtocol] = get("rudder_syslog_protocol")
  def set_rudder_syslog_protocol(protocol : SyslogProtocol, actor : EventActor, reason: Option[String]): IOResult[Unit] =  {
    val info = ModifyGlobalPropertyInfo(ModifyRudderSyslogProtocolEventType,actor,reason)
    save("rudder_syslog_protocol", protocol.value, Some(info))
  }

  /**
   * Should we display recent changes graphs  ?
   */
  def display_changes_graph(): IOResult[Boolean] =  get("display_changes_graph")
  def set_display_changes_graph(displayGraphs : Boolean): IOResult[Unit] = save("display_changes_graph", displayGraphs)

  /**
   * Should we always display compliance/recent change columns ?
   */
  def rudder_ui_display_ruleComplianceColumns(): IOResult[Boolean] = get("rudder_ui_display_ruleComplianceColumns")
  def set_rudder_ui_display_ruleComplianceColumns(displayColumns: Boolean): IOResult[Unit] = save("rudder_ui_display_ruleComplianceColumns", displayColumns)

  /////
  ///// Feature switches /////
  /////

  /**
   * Should we evaluate scripts in the variables?
   */
  def rudder_featureSwitch_directiveScriptEngine(): IOResult[FeatureSwitch] = get("rudder_featureSwitch_directiveScriptEngine")
  def set_rudder_featureSwitch_directiveScriptEngine(status: FeatureSwitch): IOResult[Unit] = save("rudder_featureSwitch_directiveScriptEngine", status)

  /**
   * Default value for node properties after acceptation:
   * - policy mode
   * - node lifecycle state
   */
  def rudder_node_onaccept_default_policy_mode(): IOResult[Option[PolicyMode]] =get("rudder_node_onaccept_default_state")
  def set_rudder_node_onaccept_default_policy_mode(policyMode: Option[PolicyMode]): IOResult[Unit] = save("rudder_node_onaccept_default_state", policyMode)
  def rudder_node_onaccept_default_state(): IOResult[NodeState] = get("rudder_node_onaccept_default_policyMode")
  def set_rudder_node_onaccept_default_state(nodeState: NodeState): IOResult[Unit] = save("rudder_node_onaccept_default_policyMode", nodeState)

  def rudder_compliance_unexpected_report_interpretation(): IOResult[UnexpectedReportInterpretation] = {
    for {
      duplicate <- get[Boolean]("rudder_compliance_unexpectedReportAllowsDuplicate")
      iterators <- get[Boolean]("rudder_compliance_unexpectedReportUnboundedVarValues")
    } yield {
      UnexpectedReportInterpretation(
        (if(duplicate) Set(UnexpectedReportBehavior.AllowsDuplicate) else Set() ) ++
        (if(iterators) Set(UnexpectedReportBehavior.UnboundVarValues) else Set())
      )
    }
  }
  def set_rudder_compliance_unexpected_report_interpretation(mode: UnexpectedReportInterpretation) : IOResult[Unit] = {
    for {
      _ <- save("rudder_compliance_unexpectedReportAllowsDuplicate", mode.isSet(UnexpectedReportBehavior.AllowsDuplicate))
      _ <- save("rudder_compliance_unexpectedReportUnboundedVarValues", mode.isSet(UnexpectedReportBehavior.UnboundVarValues))
    } yield ()
  }
}
