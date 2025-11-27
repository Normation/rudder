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
import com.normation.plugins.PluginError
import com.normation.plugins.PluginName
import com.softwaremill.quicklens.*
import java.time.ZonedDateTime
import net.liftweb.http.DispatchSnippet
import net.liftweb.http.RenderDispatch
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
class PluginExpirationInfo extends DispatchSnippet with RenderDispatch {
  import PluginExpirationInfo.*

  override def render(html: NodeSeq): NodeSeq = {
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
}

private object PluginExpirationInfo {

  /*
   * Summary of the status of the different plugins
   */
  private def checkPluginWarnings = {
    PluginsInfo.pluginInfos.plugins.foldLeft(Warning.empty) {
      case (pluginsWarning, plugin) =>
        val licenseErrors = plugin.errors.collect {
          case PluginError.RudderLicenseError(reason) =>
            (PluginName(plugin.name) -> reason)
        }.toMap

        val licenseExpiredError = plugin.errors.collect {
          case PluginError.LicenseExpiredError(expirationDate) =>
            (PluginName(plugin.name) -> expirationDate)
        }

        val licenseNearExpirationError = plugin.errors.collect {
          case PluginError.LicenseNearExpirationError(_, expirationDate) =>
            (PluginName(plugin.name) -> expirationDate)
        }

        pluginsWarning
          .modify(_.licenseError)
          .using(_ ++ licenseErrors)
          .modify(_.licenseExpired)
          .using(_ ++ licenseExpiredError)
          .modify(_.licenseNearExpiration)
          .using(_ ++ licenseNearExpirationError)
    }
  }
}
