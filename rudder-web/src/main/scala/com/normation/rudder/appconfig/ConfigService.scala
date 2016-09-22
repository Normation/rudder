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
package com.normation.rudder.appconfig

import net.liftweb.common.Full
import com.typesafe.config.Config
import net.liftweb.common.Box
import com.normation.rudder.batch.AsyncWorkflowInfo
import com.normation.rudder.services.workflows.WorkflowUpdate
import com.typesafe.config.ConfigFactory
import com.normation.rudder.domain.appconfig.RudderWebProperty
import com.normation.rudder.domain.appconfig.RudderWebPropertyName
import net.liftweb.common.Failure
import net.liftweb.common.Loggable
import net.liftweb.common.EmptyBox
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
import ca.mrvisser.sealerate
import com.normation.rudder.web.components.popup.ModificationValidationPopup.Disable
import com.normation.rudder.domain.appconfig.FeatureSwitch

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
  def rudder_ui_changeMessage_enabled() : Box[Boolean]
  def rudder_ui_changeMessage_mandatory() : Box[Boolean]
  def rudder_ui_changeMessage_explanation() : Box[String]

  /**
   * Workflow
   */
  def rudder_workflow_enabled(): Box[Boolean]
  def rudder_workflow_self_validation(): Box[Boolean]
  def rudder_workflow_self_deployment(): Box[Boolean]

  /**
   * CFEngine server properties
   */
  def cfengine_server_denybadclocks(): Box[Boolean]
  def cfengine_server_skipidentify(): Box[Boolean]

  /**
   * Agent execution interval and start run
   * Note: Interval may NEVER fail, if the value is not in LDAP, then default value arise
   */
  def agent_run_interval(): Int

  def agent_run_splaytime(): Box[Int]
  def agent_run_start_hour(): Box[Int]
  def agent_run_start_minute(): Box[Int]

  /**
   * CFEngine global properties
   */
  def cfengine_modified_files_ttl(): Box[Int]
  def cfengine_outputs_ttl(): Box[Int]

  /**
   * Logging properties
   */
  def rudder_store_all_centralized_logs_in_file(): Box[Boolean]

  /**
   * Compliance mode:
   * - "compliance": full compliance mode, where "success" execution reports are sent
   *   back from node to server, and taken into account for compliance reports,
   * - "error_only": only error and repaired are going to the server.
   */
  def rudder_compliance_mode(): Box[(String,Int)] = {
    for {
        name <- rudder_compliance_mode_name
        period <- rudder_compliance_heartbeatPeriod
    } yield {
      (name,period)
    }
  }

  def rudder_compliance_mode_name(): Box[String]

  def rudder_compliance_heartbeatPeriod(): Box[Int]

  /**
   * Send Metrics
   */
  def send_server_metrics(): Box[Option[Boolean]]

  /**
   * Report protocol
   */
  def rudder_syslog_protocol(): Box[SyslogProtocol]

  /**
   * Should we display recent changes graphs  ?
   */
  def display_changes_graph(): Box[Boolean]

  /**
   * Should we send backward compatible data from API
   */
  def api_compatibility_mode(): Box[Boolean]

  /**
   * Should we activate the script engine bar ?
   */
  def rudder_featureSwitch_directiveScriptEngine(): Box[FeatureSwitch]

  /*
   * Should we display the new quicksearch everything bar ?
   */
  def rudder_featureSwitch_quicksearchEverything(): Box[FeatureSwitch]
}

/**
 * A service that modify existing config parameters
 */
trait UpdateConfigService {

  def set_rudder_ui_changeMessage_enabled(value: Boolean): Box[Unit]
  def set_rudder_ui_changeMessage_mandatory(value: Boolean): Box[Unit]
  def set_rudder_ui_changeMessage_explanation(value: String): Box[Unit]

  /**
   * Workflows
   */
  def set_rudder_workflow_enabled(value: Boolean): Box[Unit]
  def set_rudder_workflow_self_validation(value: Boolean): Box[Unit]
  def set_rudder_workflow_self_deployment(value: Boolean): Box[Unit]

  /**
   * Set CFEngine server properties
   */
  def set_cfengine_server_denybadclocks(value: Boolean): Box[Unit]
  def set_cfengine_server_skipidentify(value: Boolean): Box[Unit]

  /**
   * Agent frequency and start run
   */
  def set_agent_run_interval(value: Int, actor : EventActor, reason: Option[String]): Box[Unit]
  def set_agent_run_splaytime(value: Int, actor : EventActor, reason: Option[String]): Box[Unit]
  def set_agent_run_start_hour(value: Int, actor : EventActor, reason: Option[String]): Box[Unit]
  def set_agent_run_start_minute(value: Int, actor : EventActor, reason: Option[String]): Box[Unit]

  /**
   * Set CFEngine global properties
   */
  def set_cfengine_modified_files_ttl(value: Int): Box[Unit]
  def set_cfengine_outputs_ttl(value: Int): Box[Unit]

  /**
   * Logging properties
   */
  def set_rudder_store_all_centralized_logs_in_file(value: Boolean): Box[Unit]

  /**
   * Send Metrics
   */
  def set_send_server_metrics(value : Option[Boolean], actor : EventActor, reason: Option[String]): Box[Unit]

  /**
   * Set the compliance mode
   */
  def set_rudder_compliance_mode(name : String, frequency : Int, actor: EventActor, reason: Option[String]): Box[Unit] = {
    for {
      _ <- set_rudder_compliance_mode_name(name,actor, reason)
      u <- name match {
             case ChangesOnly.name =>  set_rudder_compliance_heartbeatPeriod(frequency, actor, reason)
             case _ => Full(())
           }
    } yield {
      u
    }

  }

  def set_rudder_compliance_mode_name(name : String, actor : EventActor, reason: Option[String]) : Box[Unit]

  def set_rudder_compliance_heartbeatPeriod(frequency : Int, actor: EventActor, reason: Option[String]) : Box[Unit]

  /**
   * Report protocol
   */
  def set_rudder_syslog_protocol(value : SyslogProtocol, actor : EventActor, reason: Option[String]): Box[Unit]

  /**
   * Should we display recent changes graphs  ?
   */
  def set_display_changes_graph(displayGraph : Boolean): Box[Unit]

  /**
   * Should we send backward compatible data from API
   */
  def set_api_compatibility_mode(value: Boolean): Box[Unit]

  /**
   * Should we evaluate scripts in variable values?
   */
  def set_rudder_featureSwitch_directiveScriptEngine(status: FeatureSwitch): Box[Unit]

  /**
   * Should we display the new quicksearch everything bar ?
   */
  def set_rudder_featureSwitch_quicksearchEverything(status: FeatureSwitch): Box[Unit]
}

class LDAPBasedConfigService(configFile: Config, repos: ConfigRepository, workflowUpdate: AsyncWorkflowInfo) extends ReadConfigService with UpdateConfigService with Loggable {

  /**
   * Create a cache for already values that should never fail
   */
  var cacheExecutionInterval: Option[Int] = None

  val defaultConfig =
    s"""rudder.ui.changeMessage.enabled=true
       rudder.ui.changeMessage.mandatory=false
       rudder.ui.changeMessage.explanation=Please enter a reason explaining this change.
       rudder.workflow.enabled=false
       rudder.workflow.self.validation=false
       rudder.workflow.self.deployment=true
       cfengine.server.denybadclocks=true
       cfengine.server.skipidentify=false
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
       api.compatibility.mode=false
       rudder.featureSwitch.directiveScriptEngine=disabled
       rudder.featureSwitch.quicksearchEverything=enabled
    """

  val configWithFallback = configFile.withFallback(ConfigFactory.parseString(defaultConfig))

  /*
   *  Correct implementation use a macro in place of all the
   *  redondant calls...
   *
   */

  private[this] def get[T](name: String)(implicit converter: RudderWebProperty => T) : Box[T] = {
    for {
      params <- repos.getConfigParameters
      param  <- params.find( _.name.value == name) match {
                  case None =>
                    val configName = name.replaceAll("_", ".")
                    val value = configWithFallback.getString(configName)
                    save(name, value)
                  case Some(p) => Full(p)
                }
    } yield {
      param
    }
  }

  private[this] def save[T](name: String, value: T, modifyGlobalPropertyInfo : Option[ModifyGlobalPropertyInfo] = None): Box[RudderWebProperty] = {
    val p = RudderWebProperty(RudderWebPropertyName(name), value.toString, "")
    repos.saveConfigParameter(p,modifyGlobalPropertyInfo)
  }

  private[this] implicit def toBoolean(p: RudderWebProperty): Boolean = p.value.toLowerCase match {
    case "true" | "1" => true
    case _ => false
  }

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
  private[this] implicit def toString(p: RudderWebProperty): String = p.value

  private[this] implicit def toUnit(p: Box[RudderWebProperty]) : Box[Unit] = p.map( _ => ())

  private[this] implicit def toInt(p: Box[RudderWebProperty]) : Box[Int] = {
    try {
      p.map(Integer.parseInt(_))
    } catch {
      case ex:NumberFormatException => Failure(ex.getMessage)
    }
  }

  /**
   * A feature switch is defaulted to Disabled is parsing fails.
   */
  private[this] implicit def toFeatureSwitch(p: RudderWebProperty): FeatureSwitch = FeatureSwitch.parse(p.value) match {
    case Full(status) => status
    case eb: EmptyBox =>
      val e = eb ?~! s"Error when trying to parse property '${p.name}' with value '${p.value}' into a feature switch status"
      logger.warn(e.messageChain)
      FeatureSwitch.Disabled
  }

  def rudder_ui_changeMessage_enabled() = get("rudder_ui_changeMessage_enabled")
  def rudder_ui_changeMessage_mandatory() = get("rudder_ui_changeMessage_mandatory")
  def rudder_ui_changeMessage_explanation() = get("rudder_ui_changeMessage_explanation")
  def set_rudder_ui_changeMessage_enabled(value: Boolean): Box[Unit] = save("rudder_ui_changeMessage_enabled", value)
  def set_rudder_ui_changeMessage_mandatory(value: Boolean): Box[Unit] = save("rudder_ui_changeMessage_mandatory", value)
  def set_rudder_ui_changeMessage_explanation(value: String): Box[Unit] = save("rudder_ui_changeMessage_explanation", value)

  ///// workflows /////
  def rudder_workflow_enabled() = get("rudder_workflow_enabled")
  def rudder_workflow_self_validation() = get("rudder_workflow_self_validation")
  def rudder_workflow_self_deployment() = get("rudder_workflow_self_deployment")
  def set_rudder_workflow_enabled(value: Boolean): Box[Unit] = {
    save("rudder_workflow_enabled", value)
    Full(workflowUpdate ! WorkflowUpdate)
  }
  def set_rudder_workflow_self_validation(value: Boolean): Box[Unit] = save("rudder_workflow_self_validation", value)
  def set_rudder_workflow_self_deployment(value: Boolean): Box[Unit] = save("rudder_workflow_self_deployment", value)

  ///// CFEngine server /////
  def cfengine_server_denybadclocks(): Box[Boolean] = get("cfengine_server_denybadclocks")
  def set_cfengine_server_denybadclocks(value: Boolean): Box[Unit] = save("cfengine_server_denybadclocks", value)
  def cfengine_server_skipidentify(): Box[Boolean] = get("cfengine_server_skipidentify")
  def set_cfengine_server_skipidentify(value: Boolean): Box[Unit] = save("cfengine_server_skipidentify", value)

  def agent_run_interval(): Int = {
    toInt(get("agent_run_interval")) match {
      case Full(interval) =>
        cacheExecutionInterval = Some(interval)
        interval
      case f: EmptyBox =>
        val e = f ?~! "Failure when fetching the agent run interval "
        logger.error(e.messageChain)
        cacheExecutionInterval match {
          case Some(interval) =>
            interval
          case None =>
            val errorMsg = "Error while fetch the agent run interval; the value is unavailable in the LDAP and in cache."
            logger.error(errorMsg)
            throw new RuntimeException(errorMsg)
        }
    }

  }
  def set_agent_run_interval(value: Int, actor: EventActor, reason: Option[String]): Box[Unit] = {
    cacheExecutionInterval = Some(value)
    val info = ModifyGlobalPropertyInfo(ModifyAgentRunIntervalEventType,actor,reason)
    save("agent_run_interval", value, Some(info))
  }

  def agent_run_splaytime(): Box[Int] = get("agent_run_splaytime")
  def set_agent_run_splaytime(value: Int, actor: EventActor, reason: Option[String]): Box[Unit] = {
    val info = ModifyGlobalPropertyInfo(ModifyAgentRunSplaytimeEventType,actor,reason)
    save("agent_run_splaytime", value, Some(info))
  }

  def agent_run_start_hour(): Box[Int] = get("agent_run_start_hour")
  def set_agent_run_start_hour(value: Int, actor: EventActor, reason: Option[String]): Box[Unit] = {
    val info = ModifyGlobalPropertyInfo(ModifyAgentRunStartHourEventType,actor,reason)
    save("agent_run_start_hour", value, Some(info))
  }

  def agent_run_start_minute(): Box[Int] = get("agent_run_start_minute")
  def set_agent_run_start_minute(value: Int, actor: EventActor, reason: Option[String]): Box[Unit] = {
    val info = ModifyGlobalPropertyInfo(ModifyAgentRunStartMinuteEventType,actor,reason)
    save("agent_run_start_minute", value, Some(info))
  }

  ///// CFEngine server /////
  def cfengine_modified_files_ttl(): Box[Int] = get("cfengine_modified_files_ttl")
  def set_cfengine_modified_files_ttl(value: Int): Box[Unit] = save("cfengine_modified_files_ttl", value)
  def cfengine_outputs_ttl(): Box[Int] = get("cfengine_outputs_ttl")
  def set_cfengine_outputs_ttl(value: Int): Box[Unit] = save("cfengine_outputs_ttl", value)

  /**
   * Logging properties
   */
  def rudder_store_all_centralized_logs_in_file(): Box[Boolean] = get("rudder_store_all_centralized_logs_in_file")
  def set_rudder_store_all_centralized_logs_in_file(value: Boolean) = save("rudder_store_all_centralized_logs_in_file", value)

  /**
   * Compliance mode
   */
  def rudder_compliance_mode_name(): Box[String] = get("rudder_compliance_mode")
  def set_rudder_compliance_mode_name(value: String, actor : EventActor, reason: Option[String]): Box[Unit] = {
    val info = ModifyGlobalPropertyInfo(ModifyComplianceModeEventType,actor,reason)
    save("rudder_compliance_mode", value, Some(info))
  }

  /**
   * Heartbeat frequency mode
   */
  def rudder_compliance_heartbeatPeriod(): Box[Int] = get("rudder_compliance_heartbeatPeriod")
  def set_rudder_compliance_heartbeatPeriod(value: Int, actor: EventActor, reason: Option[String]): Box[Unit] = {
    val info = ModifyGlobalPropertyInfo(ModifyHeartbeatPeriodEventType,actor,reason)
    save("rudder_compliance_heartbeatPeriod", value, Some(info))
  }

  /**
   * Send Metrics
   */
  def send_server_metrics(): Box[Option[Boolean]] = get("send_server_metrics")

  def set_send_server_metrics(value : Option[Boolean], actor : EventActor, reason: Option[String]): Box[Unit] = {
    val newVal = value.map(_.toString).getOrElse("none")
    val info = ModifyGlobalPropertyInfo(ModifySendServerMetricsEventType,actor,reason)
    save("send_server_metrics",newVal,Some(info))
  }

  /**
   * Report protocol
   */
  def rudder_syslog_protocol(): Box[SyslogProtocol] = get("rudder_syslog_protocol")
  def set_rudder_syslog_protocol(protocol : SyslogProtocol, actor : EventActor, reason: Option[String]): Box[Unit] =  {
    val info = ModifyGlobalPropertyInfo(ModifyRudderSyslogProtocolEventType,actor,reason)
    save("rudder_syslog_protocol", protocol.value, Some(info))
  }

  /**
   * Should we display recent changes graphs  ?
   */
  def display_changes_graph(): Box[Boolean] =  get("display_changes_graph")

  def set_display_changes_graph(displayGraphs : Boolean): Box[Unit] = save("display_changes_graph", displayGraphs)

  /**
   * Should we send backward compatible data from API
   */
  def api_compatibility_mode(): Box[Boolean] = get("api_compatibility_mode")
  def set_api_compatibility_mode(value : Boolean): Box[Unit] = save("api_compatibility_mode", value)

  /////
  ///// Feature switches /////
  /////

  /**
   * Should we evaluate scripts in the variables?
   */
  def rudder_featureSwitch_directiveScriptEngine(): Box[FeatureSwitch] = get("rudder_featureSwitch_directiveScriptEngine")
  def set_rudder_featureSwitch_directiveScriptEngine(status: FeatureSwitch): Box[Unit] = save("rudder_featureSwitch_directiveScriptEngine", status)

  /**
   * Should we display the new quicksearch everything bar ?
   */
  def rudder_featureSwitch_quicksearchEverything(): Box[FeatureSwitch] = get("rudder_featureSwitch_quicksearchEverything")
  def set_rudder_featureSwitch_quicksearchEverything(status: FeatureSwitch): Box[Unit] = save("rudder_featureSwitch_quicksearchEverything", status)
}
