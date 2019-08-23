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
import com.normation.plugins.PluginStatusInfo
import com.normation.rudder.services.quicksearch.QSMapping
import com.normation.rudder.services.quicksearch.QSObject
import com.softwaremill.quicklens._
import net.liftweb.common._
import net.liftweb.http.DispatchSnippet
import net.liftweb.http.S
import net.liftweb.util.Helpers._
import org.joda.time.DateTime

import scala.xml.NodeSeq
/**
 * This snippet allow to display a warning if a plugin is near
 * expiration date.
 */
class PluginExpirationInfo extends DispatchSnippet with Loggable {

  final case class Warning(
      licenseError: List[String]
    , licenseExpired: Map[String, DateTime]
    , licenseNearExpiration: Map[String, DateTime]
  )

  def dispatch = {
    case "render" => pluginInfo
  }

  def pluginInfo(html: NodeSeq) : NodeSeq = {
    /*
     * Summary of the status of the different plugins
     */
    val warnings = PluginsInfo.plugins.foldLeft(Warning(Nil, Map(), Map())) { case (current, (_, plugin)) =>
      plugin.status.current match {
        case PluginStatusInfo.EnabledNoLicense => current
        case PluginStatusInfo.EnabledWithLicense(lic) =>
          if(lic.endDate.minusMonths(1).isBeforeNow()) {
            current.modify(_.licenseNearExpiration).using(m => m + (plugin.name.value -> lic.endDate))
          } else {
            current
          }
        case PluginStatusInfo.Disabled(_, None) =>
          current.modify(_.licenseError).using(plugin.name.value :: _)
        case PluginStatusInfo.Disabled(_, Some(lic)) =>
          if(lic.endDate.isBeforeNow) {
            current.modify(_.licenseExpired).using(m => m + (plugin.name.value -> lic.endDate))
          } else {
            current.modify(_.licenseError).using(plugin.name.value :: _)
          }
      }
    }

    if(warnings.licenseError.nonEmpty || warnings.licenseExpired.nonEmpty) {
      <li title="Plugin license error require your attention" class="bg-danger">
        <a href="/secure/plugins/pluginInformation" style="font-size:120%"><span class="fa fa-puzzle-piece"></span></a>
      </li>
    } else if(warnings.licenseNearExpiration.nonEmpty) {
      <li title="Plugin license near expiration" class="bg-info">
        <a href="/secure/plugins/pluginInformation" style="font-size:120%"><span class="fa fa-puzzle-piece"></span></a>
      </li>
    } else {
      NodeSeq.Empty
    }
  }
}
