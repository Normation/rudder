/*
 *************************************************************************************
 * Copyright 2017 Normation SAS
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

package com.normation.plugins

import com.normation.rudder.domain.logger.ApplicationLoggerPure

/**
 * This file defined an entry point for license information and other
 * enabling of the plugin
 */

sealed trait RudderPluginLicenseStatus
object RudderPluginLicenseStatus {
  case object EnabledNoLicense                                              extends RudderPluginLicenseStatus
  final case class EnabledWithLicense(i: PluginLicense)                     extends RudderPluginLicenseStatus
  final case class Disabled(reason: String, details: Option[PluginLicense]) extends RudderPluginLicenseStatus
}

/**
  * Plugin status in Ruddder determines the status of a plugin,
  * along with the license information to 
  */
trait PluginStatus {

  /*
   * What is the CURRENT status of the plugin at the moment of the request
   */
  def current: RudderPluginLicenseStatus

  /*
   * Is the plugin currently enabled (at the moment of the request) ?
   */
  def isEnabled(): Boolean = {
    current match {
      case RudderPluginLicenseStatus.EnabledNoLicense | _: RudderPluginLicenseStatus.EnabledWithLicense => true
      case RudderPluginLicenseStatus.Disabled(reason, optInfo)                                          =>
        val name = optInfo match {
          case Some(i) => s"'${i.softwareId}' "
          case None    => ""
        }
        ApplicationLoggerPure.Plugin.logEffect.warn(s"Plugin ${name} is disabled: ${reason}")
        false
    }
  }
}

/*
 * Default "always enable" status
 */
object AlwaysEnabledPluginStatus extends PluginStatus {
  override val current: RudderPluginLicenseStatus = RudderPluginLicenseStatus.EnabledNoLicense
  override def isEnabled() = true
}
