/*
 *************************************************************************************
 * Copyright 2019 Normation SAS
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

package com.normation.rudder.web.snippet

import bootstrap.liftweb.PluginsInfo
import com.normation.plugins.PluginName
import com.normation.plugins.RudderPluginLicenseStatus
import com.softwaremill.quicklens.*
import java.time.ZonedDateTime
import net.liftweb.common.*
import net.liftweb.http.DispatchSnippet
import scala.xml.NodeSeq

private case class Warning(
    licenseError:          Map[PluginName, String],
    licenseExpired:        Map[PluginName, ZonedDateTime],
    licenseNearExpiration: Map[PluginName, ZonedDateTime]
)

private object Warning {
  def empty: Warning = Warning(Map.empty, Map.empty, Map.empty)
}

/**
 * This snippet allow to display a warning if a plugin is near
 * expiration date.
 */
class PluginExpirationInfo extends DispatchSnippet with Loggable {
  import PluginExpirationInfo.*

  def dispatch: PartialFunction[String, NodeSeq => NodeSeq] = {
    case "render"     => pluginInfo
    case "renderIcon" => pluginInfoIcon
  }

  def pluginInfo(html: NodeSeq): NodeSeq = {
    val warning = checkPluginWarnings
    def notifHtml(notifClass: String, notifTitle: String): NodeSeq = {
      val tooltipContent =
        "<h4><i class='fa fa-exclamation-triangle'></i> " + notifTitle + "</h4><div class=\"tooltip-content\">More details on <b>Plugin information</b> page</div>"
      <li
      class={"plugin-warning " + notifClass}
      data-bs-toggle="tooltip"
      data-bs-placement="bottom"
      title={tooltipContent}
      >
        <a href="/secure/administration/pluginInformation"><span class="fa fa-puzzle-piece"></span></a>
      </li>
    }
    if (warning.licenseError.nonEmpty || warning.licenseExpired.nonEmpty) {
      notifHtml("critical", "Plugin license error require your attention")
    } else if (warning.licenseNearExpiration.nonEmpty) {
      notifHtml("warning", "Plugin license near expiration")
    } else {
      NodeSeq.Empty
    }
  }

  def pluginInfoIcon(html: NodeSeq): NodeSeq = {
    val warning                                                  = checkPluginWarnings
    def displayPluginIcon(iconClass: String, notifTitle: String) = {
      val tooltipContent =
        "<h4 class='" + iconClass + "' > <i class='fa fa-exclamation-triangle'></i> " + notifTitle + "</h4><div>More details on <b>Plugin information</b> page</div>"
      <i class={
        "fa fa-exclamation-triangle plugin-icon icon-info " ++ iconClass
      } data-bs-toggle="tooltip" data-bs-placement="right" title={
        tooltipContent
      } data-bs-container="body"></i>
    }
    if (warning.licenseError.nonEmpty || warning.licenseExpired.nonEmpty) {
      displayPluginIcon("critical", "Plugin license error require your attention")
    } else if (warning.licenseNearExpiration.nonEmpty) {
      displayPluginIcon("warning", "Plugin license near expiration")
    } else {
      NodeSeq.Empty
    }
  }
}

private object PluginExpirationInfo {

  /*
   * Summary of the status of the different plugins
   */
  private def checkPluginWarnings = {
    val now = ZonedDateTime.now()
    PluginsInfo.plugins.foldLeft(Warning.empty) {
      case (current, (_, plugin)) =>
        plugin.status.current match {
          case RudderPluginLicenseStatus.EnabledNoLicense            => current
          case RudderPluginLicenseStatus.EnabledWithLicense(lic)     =>
            if (lic.endDate.minusMonths(1).isBefore(now)) {
              current.modify(_.licenseNearExpiration).using(m => m + (plugin.name -> lic.endDate))
            } else {
              current
            }
          case RudderPluginLicenseStatus.Disabled(reason, None)      =>
            current.modify(_.licenseError).using(_ + (plugin.name -> reason))
          case RudderPluginLicenseStatus.Disabled(reason, Some(lic)) =>
            if (lic.endDate.isBefore(now)) {
              current.modify(_.licenseExpired).using(m => m + (plugin.name -> lic.endDate))
            } else {
              current.modify(_.licenseError).using(_ + (plugin.name -> reason))
            }
        }
    }
  }
}
