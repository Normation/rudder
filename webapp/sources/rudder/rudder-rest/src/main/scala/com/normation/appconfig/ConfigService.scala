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
import com.normation.errors.*
import com.normation.eventlog.EventActor
import com.normation.rudder.batch.AsyncWorkflowInfo
import com.normation.rudder.batch.PolicyGenerationTrigger
import com.normation.rudder.domain.appconfig.FeatureSwitch
import com.normation.rudder.domain.appconfig.RudderWebProperty
import com.normation.rudder.domain.appconfig.RudderWebPropertyName
import com.normation.rudder.domain.eventlog.ModifyAgentRunIntervalEventType
import com.normation.rudder.domain.eventlog.ModifyAgentRunSplaytimeEventType
import com.normation.rudder.domain.eventlog.ModifyAgentRunStartHourEventType
import com.normation.rudder.domain.eventlog.ModifyAgentRunStartMinuteEventType
import com.normation.rudder.domain.eventlog.ModifyComplianceModeEventType
import com.normation.rudder.domain.eventlog.ModifyHeartbeatPeriodEventType
import com.normation.rudder.domain.eventlog.ModifySendServerMetricsEventType
import com.normation.rudder.domain.nodes.NodeState
import com.normation.rudder.domain.policies.GlobalPolicyMode
import com.normation.rudder.domain.policies.PolicyMode
import com.normation.rudder.domain.policies.PolicyMode.*
import com.normation.rudder.domain.policies.PolicyModeOverrides
import com.normation.rudder.reports.*
import com.normation.rudder.services.policies.SendMetrics
import com.normation.rudder.services.servers.RelaySynchronizationMethod
import com.normation.rudder.services.servers.RelaySynchronizationMethod.*
import com.normation.rudder.services.workflows.WorkflowLevelService
import com.normation.rudder.services.workflows.WorkflowUpdate
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import scala.concurrent.duration.Duration
import zio.*
import zio.syntax.*

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
  def rudder_ui_changeMessage_enabled():     IOResult[Boolean]
  def rudder_ui_changeMessage_mandatory():   IOResult[Boolean]
  def rudder_ui_changeMessage_explanation(): IOResult[String]

  /**
   * Workflow.
   * The semantic of that property for "enabled" depends ALSO
   * of the plugins installed for workflows: if you don't have
   * workflow plugin, you won't be able to enable it. And in the
   * line, once you have the plugin, you need to enable it their.
   */
  def rudder_workflow_enabled():         IOResult[Boolean]
  def rudder_workflow_self_validation(): IOResult[Boolean]
  def rudder_workflow_self_deployment(): IOResult[Boolean]
  def rudder_workflow_validate_all():    IOResult[Boolean]

  /**
   * CFEngine server properties
   */
  def cfengine_server_denybadclocks(): IOResult[Boolean]

  /**
   * Relay synchronization configuration
   */
  def relay_server_sync_method():     IOResult[RelaySynchronizationMethod]
  def relay_server_syncpromises():    IOResult[Boolean]
  def relay_server_syncsharedfiles(): IOResult[Boolean]

  /**
   * Agent execution interval and start run
   */
  def agent_run_interval(): IOResult[Int]

  def agent_run_splaytime():    IOResult[Int]
  def agent_run_start_hour():   IOResult[Int]
  def agent_run_start_minute(): IOResult[Int]

  /**
   * CFEngine global properties
   */
  def cfengine_modified_files_ttl(): IOResult[Int]
  def cfengine_outputs_ttl():        IOResult[Int]

  /**
   * Compliance mode: See ComplianceMode class for more details
   */
  def rudder_compliance_mode(): IOResult[GlobalComplianceMode] = {
    for {
      name     <- rudder_compliance_mode_name()
      modeName <- ComplianceModeName.parse(name).toIO
      period   <- rudder_compliance_heartbeatPeriod()
    } yield {
      GlobalComplianceMode(modeName, period)
    }
  }

  def rudder_compliance_mode_name(): IOResult[String]

  def rudder_compliance_heartbeatPeriod(): IOResult[Int]

  /**
   * Policy mode: See PolicyMode class for more details
   */
  def rudder_global_policy_mode(): IOResult[GlobalPolicyMode] = {
    for {
      mode        <- rudder_policy_mode_name()
      overridable <- rudder_policy_overridable()
    } yield {
      GlobalPolicyMode(mode, if (overridable) PolicyModeOverrides.Always else PolicyModeOverrides.Unoverridable)
    }
  }
  def rudder_policy_mode_name():   IOResult[PolicyMode]
  def rudder_policy_overridable(): IOResult[Boolean]

  /**
   * Send Metrics
   */
  def send_server_metrics(): IOResult[Option[SendMetrics]]

  /**
    * default reporting protocol
    */
  def rudder_report_protocol_default(): IOResult[AgentReportingProtocol]

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
  def rudder_node_onaccept_default_state():       IOResult[NodeState]

  def node_accept_duplicated_hostname(): IOResult[Boolean]

  /**
   * For debugging / disabling some part of Rudder. Should not be exposed in UI
   */
  def rudder_compute_changes():              IOResult[Boolean]
  def rudder_generation_compute_dyngroups(): IOResult[Boolean]

  def rudder_generation_max_parallelism(): IOResult[String]
  def rudder_generation_delay():           IOResult[Duration]
  def rudder_generation_trigger():         IOResult[PolicyGenerationTrigger]
  def rudder_generation_js_timeout():      IOResult[Int]

  def rudder_compute_dyngroups_max_parallelism(): IOResult[String]

  def rudder_generation_continue_on_error(): IOResult[Boolean]

  def rudder_setup_done(): IOResult[Boolean]

}

/**
 * A service that modify existing config parameters
 */
trait UpdateConfigService {

  def set_rudder_ui_changeMessage_enabled(value:     Boolean): IOResult[Unit]
  def set_rudder_ui_changeMessage_mandatory(value:   Boolean): IOResult[Unit]
  def set_rudder_ui_changeMessage_explanation(value: String):  IOResult[Unit]

  /**
   * Workflows
   */
  def set_rudder_workflow_enabled(value:         Boolean): IOResult[Unit]
  def set_rudder_workflow_self_validation(value: Boolean): IOResult[Unit]
  def set_rudder_workflow_self_deployment(value: Boolean): IOResult[Unit]
  def set_rudder_workflow_validate_all(value:    Boolean): IOResult[Unit]

  /**
   * Set CFEngine server properties
   */
  def set_cfengine_server_denybadclocks(value: Boolean): IOResult[Unit]
  def set_cfengine_server_skipidentify(value:  Boolean): IOResult[Unit]

  /**
   * Set Relay-Server synchronization method
   */
  def set_relay_server_sync_method(value:     RelaySynchronizationMethod): IOResult[Unit]
  def set_relay_server_syncpromises(value:    Boolean):                    IOResult[Unit]
  def set_relay_server_syncsharedfiles(value: Boolean):                    IOResult[Unit]

  /**
   * Agent frequency and start run
   */
  def set_agent_run_interval(value:     Int, actor: EventActor, reason: Option[String]): IOResult[Unit]
  def set_agent_run_splaytime(value:    Int, actor: EventActor, reason: Option[String]): IOResult[Unit]
  def set_agent_run_start_hour(value:   Int, actor: EventActor, reason: Option[String]): IOResult[Unit]
  def set_agent_run_start_minute(value: Int, actor: EventActor, reason: Option[String]): IOResult[Unit]

  /**
   * Set CFEngine global properties
   */
  def set_cfengine_modified_files_ttl(value: Int): IOResult[Unit]
  def set_cfengine_outputs_ttl(value:        Int): IOResult[Unit]

  /**
   * Send Metrics
   */
  def set_send_server_metrics(value: Option[SendMetrics], actor: EventActor, reason: Option[String]): IOResult[Unit]

  /**
   * Set the compliance mode
   */
  def set_rudder_compliance_mode(mode: ComplianceMode, actor: EventActor, reason: Option[String]): IOResult[Unit] = {
    for {
      _ <- set_rudder_compliance_mode_name(mode.name, actor, reason)
      u <- mode.name match {
             case ChangesOnly.name => set_rudder_compliance_heartbeatPeriod(mode.heartbeatPeriod, actor, reason)
             case _                => ZIO.unit
           }
    } yield {
      u
    }

  }

  def set_rudder_compliance_mode_name(name: String, actor: EventActor, reason: Option[String]): IOResult[Unit]

  def set_rudder_compliance_heartbeatPeriod(frequency: Int, actor: EventActor, reason: Option[String]): IOResult[Unit]

  /**
   * Report protocol
   */
  def set_rudder_report_protocol_default(value: AgentReportingProtocol): IOResult[Unit]

  /**
   * Should we display recent changes graphs  ?
   */
  def set_display_changes_graph(displayGraph: Boolean): IOResult[Unit]

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
  def set_rudder_policy_mode(mode: GlobalPolicyMode, actor: EventActor, reason: Option[String]): IOResult[Unit] = {
    for {
      _ <- set_rudder_policy_mode_name(mode.mode, actor, reason)
      u <- set_rudder_policy_overridable(if (mode.overridable == PolicyModeOverrides.Always) true else false, actor, reason)
    } yield {
      u
    }

  }

  def set_rudder_policy_mode_name(name: PolicyMode, actor: EventActor, reason: Option[String]): IOResult[Unit]

  def set_rudder_policy_overridable(overridable: Boolean, actor: EventActor, reason: Option[String]): IOResult[Unit]

  /**
   * Default name for node properties after acceptation:
   * - policy mode
   * - node lifecycle state
   */
  def set_rudder_node_onaccept_default_policy_mode(policyMode: Option[PolicyMode]): IOResult[Unit]
  def set_rudder_node_onaccept_default_state(nodeState:        NodeState):          IOResult[Unit]

  def set_node_accept_duplicated_hostname(accept: Boolean): IOResult[Unit]

  def set_rudder_compute_changes(value:              Boolean): IOResult[Unit]
  def set_rudder_generation_compute_dyngroups(value: Boolean): IOResult[Unit]

  def set_rudder_generation_max_parallelism(value: String):                  IOResult[Unit]
  def set_rudder_generation_delay(value:           Duration):                IOResult[Unit]
  def set_rudder_generation_trigger(value:         PolicyGenerationTrigger): IOResult[Unit]
  def set_rudder_generation_js_timeout(value:      Int):                     IOResult[Unit]

  def set_rudder_compute_dyngroups_max_parallelism(value: String): IOResult[Unit]

  def set_rudder_generation_continue_on_error(value: Boolean): IOResult[Unit]

  def set_rudder_setup_done(value: Boolean): IOResult[Unit]
}

/*
 * A generic config service that handle all the typing of values but rely on a
 * a backend ConfigRepository to actually save things.
 * Initial values can be provided with a Config file (plus there is some
 * hard coded default values)
 */
class GenericConfigService(
    configFile:     Config,
    repos:          ConfigRepository,
    workflowUpdate: AsyncWorkflowInfo,
    workflowLevel:  WorkflowLevelService
) extends ReadConfigService with UpdateConfigService with NamedZioLogger {

  override def loggerName: String = this.getClass.getName

  /**
   * Create a cache for values that should never fail
   */

  val defaultConfig: String = {
    s"""rudder.ui.changeMessage.enabled=true
       rudder.ui.changeMessage.mandatory=false
       rudder.ui.changeMessage.explanation=Please enter a reason explaining this change.
       rudder.workflow.enabled=false
       rudder.workflow.self.validation=false
       rudder.workflow.self.deployment=true
       rudder.workflow.validate.all=false
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
       rudder.syslog.protocol.disabled=false
       rudder.report.protocol.default=SYSLOG
       rudder.syslog.protocol.transport=UDP
       display.changes.graph=true
       rudder.ui.display.ruleComplianceColumns=false
       rudder.policy.mode.name=${Enforce.name}
       rudder.policy.mode.overridable=true
       rudder.featureSwitch.directiveScriptEngine=enabled
       rudder.node.onaccept.default.state=enabled
       rudder.node.onaccept.default.policyMode=default
       rudder.compute.changes=true
       rudder.generation.compute.dyngroups=true
       rudder.generation.max.parallelism=x0.5
       rudder.generation.js.timeout=30
       rudder.generation.continue.on.error=false
       rudder.generation.delay=0s
       rudder.generation.trigger=all
       node.accept.duplicated.hostname=false
       rudder.compute.dyngroups.max.parallelism=1
       rudder.setup.done=false
       rudder.generation.rudderc.enabled.targets=\"\"\"[]\"\"\"
    """
  }

  val configWithFallback: Config = configFile.withFallback(ConfigFactory.parseString(defaultConfig))

  /*
   * Get the value, default to the default value defined above in `defaultConfig` if not available in base.
   * In that case, the default value is saved in base.
   * Parsing can't fail: in case of bad data in base, we still want to return a correct
   * value (not sure why ? At least, we could save back the value in base ?)
   */
  private def get[T](name: String)(implicit converter: RudderWebProperty => T): IOResult[T] = {
    for {
      params   <- repos.getConfigParameters()
      needSave <- Ref.make(false)
      param    <- params.find(_.name.value == name) match {
                    case None    =>
                      val configName = name.replaceAll("_", ".")
                      val value      = configWithFallback.getString(configName)
                      needSave.set(true) *> RudderWebProperty(RudderWebPropertyName(name), value, "").succeed
                    case Some(p) => p.succeed
                  }
      value     = converter(param)
      _        <- ZIO.whenZIO(needSave.get) {
                    save(name, param)
                  }
    } yield value
  }

  private def getIO[T](name: String)(implicit converter: RudderWebProperty => IOResult[T]): IOResult[T] = {
    get[IOResult[T]](name).flatten
  }

  private def save[T](name: String, value: T, modifyGlobalPropertyInfo: Option[ModifyGlobalPropertyInfo] = None)(implicit
      ser: T => String
  ): IOResult[RudderWebProperty] = {
    val p = RudderWebProperty(RudderWebPropertyName(name), ser(value), "")
    repos.saveConfigParameter(p, modifyGlobalPropertyInfo)
  }

  implicit private def serInt(x:           Int):           String = x.toString
  implicit private def serPolicyMode(x:    PolicyMode):    String = x.name
  implicit private def serFeatureSwitch(x: FeatureSwitch): String = x.name

  implicit private def toBoolean(p: RudderWebProperty): Boolean = p.value.toLowerCase match {
    case "true" | "1" => true
    case _            => false
  }
  implicit private def serBoolean(x: Boolean): String = if (x) "true" else "false"

  // default is HTTPS, in particular for ""
  implicit private def toReportProtocol(p: RudderWebProperty): AgentReportingProtocol = p.value match {
    case _ => AgentReportingHTTPS
  }

  implicit private def toOptionPolicyMode(p: RudderWebProperty): Option[PolicyMode] = {
    PolicyMode.values.find(_.name == p.value.toLowerCase())
  }

  implicit private def serOptionPolicyMode(x: Option[PolicyMode]): String = x match {
    case None    => "default"
    case Some(p) => p.name
  }

  implicit private def toOptionSendMetrics(p: RudderWebProperty): Option[SendMetrics] = {
    p.value.toLowerCase match {
      case "true" | "1" | "complete" => Some(SendMetrics.CompleteMetrics)
      case "minimal"                 => Some(SendMetrics.MinimalMetrics)
      case "false" | "no"            => Some(SendMetrics.NoMetrics)
      case _                         => None
    }
  }

  implicit private def serSendMetrics(x: Option[SendMetrics]): String = x match {
    case None                              => "default"
    case Some(SendMetrics.NoMetrics)       => "no"
    case Some(SendMetrics.MinimalMetrics)  => "minimal"
    case Some(SendMetrics.CompleteMetrics) => "complete"
  }

  implicit private def toNodeState(p: RudderWebProperty): NodeState = {
    NodeState.parse(p.value).getOrElse(NodeState.Enabled) // default value is "enabled"
  }

  implicit private def serState(x: NodeState): String = x.name

  implicit private def toRelaySynchronisationMethod(p: RudderWebProperty): RelaySynchronizationMethod = p.value match {
    case Rsync.value    => Rsync
    case Disabled.value => Disabled
    case _              => Classic // default is classic
  }

  implicit private def toString(p: RudderWebProperty): String = p.value

  implicit private def toUnit(p: IOResult[RudderWebProperty]): IOResult[Unit] = p.map(_ => ())

  implicit private def toInt(p: RudderWebProperty): IOResult[Int] = {
    try {
      Integer.parseInt(p).succeed
    } catch {
      case ex: NumberFormatException => Inconsistency(ex.getMessage).fail
    }
  }

  implicit private def toDuration(p: RudderWebProperty): IOResult[Duration] = {
    try {
      Duration(p).succeed
    } catch {
      case ex: Exception => Inconsistency(ex.getMessage).fail
    }
  }

  implicit private def toPolicyGenerationTrigger(p: RudderWebProperty): IOResult[PolicyGenerationTrigger] = {
    PolicyGenerationTrigger(p).toIO
  }

  implicit private def serPolicyGenerationTrigger(x: PolicyGenerationTrigger): String = {
    x match {
      case PolicyGenerationTrigger.AllGeneration        => "all"
      case PolicyGenerationTrigger.NoGeneration         => "none"
      case PolicyGenerationTrigger.OnlyManualGeneration => "onlyManual"
    }
  }

  /**
   * A feature switch is defaulted to Disabled is parsing fails.
   */
  implicit private def toFeatureSwitch(p: RudderWebProperty): FeatureSwitch = FeatureSwitch.parse(p.value) match {
    case Right(status) => status
    case Left(err)     =>
      logEffect.warn(
        Chained(
          s"Error when trying to parse property '${p.name.value}' with value '${p.value}' into a feature switch status",
          err
        ).fullMsg
      )
      FeatureSwitch.Disabled
  }

  def rudder_ui_changeMessage_enabled():     IOResult[Boolean] = get("rudder_ui_changeMessage_enabled")
  def rudder_ui_changeMessage_mandatory():   IOResult[Boolean] = get("rudder_ui_changeMessage_mandatory")
  def rudder_ui_changeMessage_explanation(): IOResult[String]  = get("rudder_ui_changeMessage_explanation")
  def set_rudder_ui_changeMessage_enabled(value:     Boolean): IOResult[Unit] = save("rudder_ui_changeMessage_enabled", value)
  def set_rudder_ui_changeMessage_mandatory(value:   Boolean): IOResult[Unit] = save("rudder_ui_changeMessage_mandatory", value)
  def set_rudder_ui_changeMessage_explanation(value: String):  IOResult[Unit] = save("rudder_ui_changeMessage_explanation", value)

  ///// workflows /////
  def rudder_workflow_enabled():         IOResult[Boolean] = {
    if (workflowLevel.workflowLevelAllowsEnable) {
      get("rudder_workflow_enabled")
    } else {
      false.succeed
    }
  }
  def rudder_workflow_self_validation(): IOResult[Boolean] = get("rudder_workflow_self_validation")
  def rudder_workflow_self_deployment(): IOResult[Boolean] = get("rudder_workflow_self_deployment")
  def rudder_workflow_validate_all():    IOResult[Boolean] = get("rudder_workflow_validate_all")

  def set_rudder_workflow_enabled(value: Boolean): IOResult[Unit] = {
    if (workflowLevel.workflowLevelAllowsEnable) {
      save("rudder_workflow_enabled", value) <*
      IOResult.attempt(workflowUpdate ! WorkflowUpdate)
    } else {
      Inconsistency(
        "You can't change the change validation workflow type. Perhaps are you missing the 'changes validation' plugin?"
      ).fail
    }
  }
  def set_rudder_workflow_self_validation(value: Boolean): IOResult[Unit] = save("rudder_workflow_self_validation", value)
  def set_rudder_workflow_self_deployment(value: Boolean): IOResult[Unit] = save("rudder_workflow_self_deployment", value)
  def set_rudder_workflow_validate_all(value:    Boolean): IOResult[Unit] = save("rudder_workflow_validate_all", value).unit

  ///// CFEngine server /////
  def cfengine_server_denybadclocks(): IOResult[Boolean] = get("cfengine_server_denybadclocks")
  def set_cfengine_server_denybadclocks(value: Boolean): IOResult[Unit] = save("cfengine_server_denybadclocks", value)
  def cfengine_server_skipidentify(): IOResult[Boolean] = get("cfengine_server_skipidentify")
  def set_cfengine_server_skipidentify(value: Boolean): IOResult[Unit] = save("cfengine_server_skipidentify", value)

  // Relay synchronization configuration
  def relay_server_sync_method(): IOResult[RelaySynchronizationMethod] = get("relay.sync.method")
  def set_relay_server_sync_method(value: RelaySynchronizationMethod): IOResult[Unit] = save("relay.sync.method", value.value)
  def relay_server_syncpromises(): IOResult[Boolean] = get("relay.sync.promises")
  def set_relay_server_syncpromises(value: Boolean): IOResult[Unit] = save("relay.sync.promises", value)
  def relay_server_syncsharedfiles(): IOResult[Boolean] = get("relay.sync.sharedfiles")
  def set_relay_server_syncsharedfiles(value: Boolean): IOResult[Unit] = save("relay.sync.sharedfiles", value)

  def agent_run_interval():                                                          IOResult[Int]  = getIO("agent_run_interval")
  def set_agent_run_interval(value: Int, actor: EventActor, reason: Option[String]): IOResult[Unit] = {
    val info = ModifyGlobalPropertyInfo(ModifyAgentRunIntervalEventType, actor, reason)
    save("agent_run_interval", value, Some(info))
  }

  def agent_run_splaytime():                                                          IOResult[Int]  = getIO("agent_run_splaytime")
  def set_agent_run_splaytime(value: Int, actor: EventActor, reason: Option[String]): IOResult[Unit] = {
    val info = ModifyGlobalPropertyInfo(ModifyAgentRunSplaytimeEventType, actor, reason)
    save("agent_run_splaytime", value, Some(info))
  }

  def agent_run_start_hour():                                                          IOResult[Int]  = getIO("agent_run_start_hour")
  def set_agent_run_start_hour(value: Int, actor: EventActor, reason: Option[String]): IOResult[Unit] = {
    val info = ModifyGlobalPropertyInfo(ModifyAgentRunStartHourEventType, actor, reason)
    save("agent_run_start_hour", value, Some(info))
  }

  def agent_run_start_minute():                                                          IOResult[Int]  = getIO("agent_run_start_minute")
  def set_agent_run_start_minute(value: Int, actor: EventActor, reason: Option[String]): IOResult[Unit] = {
    val info = ModifyGlobalPropertyInfo(ModifyAgentRunStartMinuteEventType, actor, reason)
    save("agent_run_start_minute", value, Some(info))
  }

  ///// CFEngine server /////
  def cfengine_modified_files_ttl(): IOResult[Int] = getIO("cfengine_modified_files_ttl")
  def set_cfengine_modified_files_ttl(value: Int): IOResult[Unit] = save("cfengine_modified_files_ttl", value)
  def cfengine_outputs_ttl(): IOResult[Int] = getIO("cfengine_outputs_ttl")
  def set_cfengine_outputs_ttl(value: Int): IOResult[Unit] = save("cfengine_outputs_ttl", value)

  /**
   * Compliance mode
   */
  def rudder_compliance_mode_name():                                                             IOResult[String] = get("rudder_compliance_mode")
  def set_rudder_compliance_mode_name(value: String, actor: EventActor, reason: Option[String]): IOResult[Unit]   = {
    val info = ModifyGlobalPropertyInfo(ModifyComplianceModeEventType, actor, reason)
    save("rudder_compliance_mode", value, Some(info))
  }

  /**
   * Heartbeat frequency mode
   */
  def rudder_compliance_heartbeatPeriod():                                                          IOResult[Int]  = getIO("rudder_compliance_heartbeatPeriod")
  def set_rudder_compliance_heartbeatPeriod(value: Int, actor: EventActor, reason: Option[String]): IOResult[Unit] = {
    val info = ModifyGlobalPropertyInfo(ModifyHeartbeatPeriodEventType, actor, reason)
    save("rudder_compliance_heartbeatPeriod", value, Some(info))
  }

  def rudder_policy_mode_name():                                                                IOResult[PolicyMode] = get[String]("rudder_policy_mode_name").flatMap(PolicyMode.parse(_).toIO)
  def set_rudder_policy_mode_name(name: PolicyMode, actor: EventActor, reason: Option[String]): IOResult[Unit]       = {
    val info = ModifyGlobalPropertyInfo(ModifyComplianceModeEventType, actor, reason)
    save("rudder_policy_mode_name", name, Some(info))
  }

  def rudder_policy_overridable():                                                                    IOResult[Boolean] = get("rudder_policy_mode_overridable")
  def set_rudder_policy_overridable(overridable: Boolean, actor: EventActor, reason: Option[String]): IOResult[Unit]    = {
    val info = ModifyGlobalPropertyInfo(ModifyComplianceModeEventType, actor, reason)
    save("rudder_policy_mode_overridable", overridable, Some(info))
  }

  /**
   * Send Metrics
   */
  def send_server_metrics(): IOResult[Option[SendMetrics]] = get("send_server_metrics")

  def set_send_server_metrics(value: Option[SendMetrics], actor: EventActor, reason: Option[String]): IOResult[Unit] = {
    val info = ModifyGlobalPropertyInfo(ModifySendServerMetricsEventType, actor, reason)
    save("send_server_metrics", value, Some(info))
  }

  def rudder_report_protocol_default():                                  IOResult[AgentReportingProtocol] = get("rudder_report_protocol_default")
  def set_rudder_report_protocol_default(value: AgentReportingProtocol): IOResult[Unit]                   =
    save("rudder_report_protocol_default", value.value)

  /**
   * Should we display recent changes graphs  ?
   */
  def display_changes_graph(): IOResult[Boolean] = get("display_changes_graph")
  def set_display_changes_graph(displayGraphs: Boolean): IOResult[Unit] = save("display_changes_graph", displayGraphs)

  /**
   * Should we always display compliance/recent change columns ?
   */
  def rudder_ui_display_ruleComplianceColumns():                            IOResult[Boolean] = get("rudder_ui_display_ruleComplianceColumns")
  def set_rudder_ui_display_ruleComplianceColumns(displayColumns: Boolean): IOResult[Unit]    =
    save("rudder_ui_display_ruleComplianceColumns", displayColumns)

  /////
  ///// Feature switches /////
  /////

  /**
   * Should we evaluate scripts in the variables?
   */
  def rudder_featureSwitch_directiveScriptEngine():                          IOResult[FeatureSwitch] = get("rudder_featureSwitch_directiveScriptEngine")
  def set_rudder_featureSwitch_directiveScriptEngine(status: FeatureSwitch): IOResult[Unit]          =
    save("rudder_featureSwitch_directiveScriptEngine", status)

  /**
   * Default value for node properties after acceptation:
   * - policy mode
   * - node lifecycle state
   */
  def rudder_node_onaccept_default_policy_mode():                                   IOResult[Option[PolicyMode]] = get("rudder_node_onaccept_default_state")
  def set_rudder_node_onaccept_default_policy_mode(policyMode: Option[PolicyMode]): IOResult[Unit]               =
    save("rudder_node_onaccept_default_state", policyMode)
  def rudder_node_onaccept_default_state():                                         IOResult[NodeState]          = get("rudder_node_onaccept_default_policyMode")
  def set_rudder_node_onaccept_default_state(nodeState: NodeState):                 IOResult[Unit]               =
    save("rudder_node_onaccept_default_policyMode", nodeState)

  /*
   * Do we allow duplicate hostname for nodes?
   * (true: yes, duplicate hostname are accepted)
   */
  def node_accept_duplicated_hostname(): IOResult[Boolean] = get("node_accept_duplicated_hostname")
  def set_node_accept_duplicated_hostname(accept: Boolean): IOResult[Unit] = save("node_accept_duplicated_hostname", accept)

  ///// debug / perf /////
  def rudder_compute_changes(): IOResult[Boolean] = get("rudder_compute_changes")
  def set_rudder_compute_changes(value: Boolean): IOResult[Unit] = save("rudder_compute_changes", value)
  def rudder_generation_compute_dyngroups(): IOResult[Boolean] = get("rudder_generation_compute_dyngroups")
  def set_rudder_generation_compute_dyngroups(value: Boolean): IOResult[Unit] = save("rudder_generation_compute_dyngroups", value)

  /// generation: js timeout, parallelism
  def rudder_generation_max_parallelism(): IOResult[String] = get("rudder_generation_max_parallelism")
  def set_rudder_generation_max_parallelism(value: String): IOResult[Unit] = save("rudder_generation_max_parallelism", value)
  def rudder_generation_js_timeout(): IOResult[Int] = getIO("rudder_generation_js_timeout")
  def set_rudder_generation_js_timeout(value: Int): IOResult[Unit] = save("rudder_generation_js_timeout", value)

  def rudder_generation_continue_on_error(): IOResult[Boolean] = get("rudder_generation_continue_on_error")
  def set_rudder_generation_continue_on_error(value: Boolean): IOResult[Unit] = save("rudder_generation_continue_on_error", value)

  def rudder_generation_delay(): IOResult[Duration] = getIO("rudder_generation_delay")
  def set_rudder_generation_delay(value: Duration): IOResult[Unit] = save("rudder_generation_delay", value.toString)

  def rudder_generation_trigger(): IOResult[PolicyGenerationTrigger] = getIO("rudder_generation_trigger")
  def set_rudder_generation_trigger(value: PolicyGenerationTrigger): IOResult[Unit] = save("rudder_generation_trigger", value)

  def rudder_compute_dyngroups_max_parallelism():                  IOResult[String] = get("rudder_compute_dyngroups_max_parallelism")
  def set_rudder_compute_dyngroups_max_parallelism(value: String): IOResult[Unit]   =
    save("rudder_compute_dyngroups_max_parallelism", value)

  def rudder_setup_done(): IOResult[Boolean] = get("rudder_setup_done")
  def set_rudder_setup_done(value: Boolean): IOResult[Unit] = save("rudder_setup_done", value)
}
