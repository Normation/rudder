/*
*************************************************************************************
* Copyright 2013 Normation SAS
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
import com.normation.rudder.reports.ComplianceMode
import com.normation.rudder.domain.appconfig.RudderWebProperty

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
  def rudder_compliance_mode(): Box[ComplianceMode]

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
  def set_agent_run_interval(value: Int): Box[Unit]
  def set_agent_run_splaytime(value: Int): Box[Unit]
  def set_agent_run_start_hour(value: Int): Box[Unit]
  def set_agent_run_start_minute(value: Int): Box[Unit]

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
   * Set the compliance mode
   */
  def set_rudder_compliance_mode(value: ComplianceMode): Box[Unit]
}

class LDAPBasedConfigService(configFile: Config, repos: ConfigRepository, workflowUpdate: AsyncWorkflowInfo) extends ReadConfigService with UpdateConfigService with Loggable {

  /**
   * Create a cache for already values that should never fail
   */
  var cacheExecutionInterval: Option[Int] = None

  val defaultConfig =
    """rudder.ui.changeMessage.enabled=true
       rudder.ui.changeMessage.mandatory=false
       rudder.ui.changeMessage.explanation=Please enter a reason explaining this change.
       rudder.workflow.enabled=false
       rudder.workflow.self.validation=false
       rudder.workflow.self.deployment=true
       cfengine.server.denybadclocks=true
       cfengine.server.skipidentify=false
       agent.run.interval=5
       agent.run.splaytime=5
       agent.run.start.hour=0
       agent.run.start.minute=0
       cfengine.modified.files.ttl=30
       cfengine.outputs.ttl=7
       rudder.store.all.centralized.logs.in.file=true
       rudder.compliance.mode=fullCompliance
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

  private[this] def save[T](name: String, value: T): Box[RudderWebProperty] = {
    val p = RudderWebProperty(RudderWebPropertyName(name), value.toString, "")
    repos.saveConfigParameter(p)
  }

  private[this] implicit def toBoolean(p: RudderWebProperty): Boolean = p.value.toLowerCase match {
    case "true" | "1" => true
    case _ => false
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

  private[this] implicit def toComplianceMode(x: Box[RudderWebProperty]) : Box[ComplianceMode] = {
    for {
      value <- x
    } yield {
      ComplianceMode.parse(value)
    }
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
      case f: Failure =>
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
  def set_agent_run_interval(value: Int): Box[Unit] = {
    cacheExecutionInterval = Some(value)
    save("agent_run_interval", value)
  }

  def agent_run_splaytime(): Box[Int] = get("agent_run_splaytime")
  def set_agent_run_splaytime(value: Int): Box[Unit] = save("agent_run_splaytime", value)

  def agent_run_start_hour(): Box[Int] = get("agent_run_start_hour")
  def set_agent_run_start_hour(value: Int): Box[Unit] = save("agent_run_start_hour", value)

  def agent_run_start_minute(): Box[Int] = get("agent_run_start_minute")
  def set_agent_run_start_minute(value: Int): Box[Unit] = save("agent_run_start_minute", value)

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
   *
   */
  def rudder_compliance_mode(): Box[ComplianceMode] = get("rudder_compliance_mode")
  def set_rudder_compliance_mode(value: ComplianceMode): Box[Unit] = {
    val p = RudderWebProperty(RudderWebPropertyName("rudder_compliance_mode"), value.name, "")
    repos.saveConfigParameter(p)
  }

}
