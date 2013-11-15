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

}

class LDAPBasedConfigService(configFile: Config, repos: ConfigRepository, workflowUpdate : AsyncWorkflowInfo) extends ReadConfigService with UpdateConfigService {


  val defaultConfig =
    """rudder.ui.changeMessage.enabled=true
       rudder.ui.changeMessage.mandatory=false
       rudder.ui.changeMessage.explanation=Please enter a reason explaining this change.
       rudder.workflow.enabled=false
       rudder.workflow.self.validation=false
       rudder.workflow.self.deployment=true
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
}
