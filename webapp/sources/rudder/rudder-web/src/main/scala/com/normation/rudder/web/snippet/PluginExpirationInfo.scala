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
import com.softwaremill.quicklens._
import net.liftweb.common._
import net.liftweb.http.DispatchSnippet
import org.joda.time.DateTime

import scala.xml.NodeSeq

protected final case class Warning(
    licenseError: List[String]
  , licenseExpired: Map[String, DateTime]
  , licenseNearExpiration: Map[String, DateTime]
)

/**
 * This snippet allow to display a warning if a plugin is near
 * expiration date.
 */
class PluginExpirationInfo extends DispatchSnippet with Loggable {


  def dispatch = {
    case "render"     => pluginInfo
    case "renderIcon" => pluginInfoIcon
  }

  def pluginInfo(html: NodeSeq) : NodeSeq = {
    /*
     * Summary of the status of the different plugins
     */
    val warnings = PluginsInfo.plugins.foldLeft(Warning(Nil, Map(), Map())) { case (current, (_, plugin)) =>
      plugin.status.current match {
        case PluginStatusInfo.EnabledNoLicense =>
          current
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

    def notifHtml (notifClass:String , notifTitle:String): NodeSeq ={
      val tooltipContent = "<h4><i class='fa fa-exclamation-triangle'></i> "+ notifTitle +"</h4><div>More details on <b>Plugin information</b> page</div>"
      <li
      class={"plugin-warning " + notifClass}
      data-toggle="tooltip"
      data-placement="bottom"
      data-html="true"
      data-original-title={tooltipContent}
      >
        <a href="/secure/plugins/pluginInformation"><span class="fa fa-puzzle-piece"></span></a>
      </li>
    }
    if(warnings.licenseError.nonEmpty || warnings.licenseExpired.nonEmpty) {
      notifHtml("critical", "Plugin license error require your attention")
    } else if(warnings.licenseNearExpiration.nonEmpty) {
      notifHtml("warning", "Plugin license near expiration")
    } else {
      NodeSeq.Empty
    }
  }

  def pluginInfoIcon(html: NodeSeq) : NodeSeq = {
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
    def displayPluginIcon(iconClass: String, notifTitle: String) = {
      val tooltipContent = "<h4 class='"+ iconClass +"' > <i class='fa fa-exclamation-triangle'></i> "+ notifTitle +"</h4><div>More details on <b>Plugin information</b> page</div>"
      <i class={"fa fa-exclamation-triangle plugin-icon icon-info " ++ iconClass} data-toggle="tooltip" data-placement="right" data-html="true" data-original-title={tooltipContent} data-container="body"></i>
    }
    if(warnings.licenseError.nonEmpty || warnings.licenseExpired.nonEmpty) {
      displayPluginIcon("critical" ,"Plugin license error require your attention")
    } else if(warnings.licenseNearExpiration.nonEmpty) {
      displayPluginIcon("warning"  ,"Plugin license near expiration")
    } else {
      NodeSeq.Empty
    }
  }
}
