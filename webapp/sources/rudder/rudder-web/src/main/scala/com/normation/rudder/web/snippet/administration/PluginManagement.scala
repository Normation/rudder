/*
*************************************************************************************
* Copyright 2011 Normation SAS
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

package com.normation.rudder.web.snippet.administration

import net.liftweb.common._
import net.liftweb.http.DispatchSnippet
import net.liftweb.util.Helpers._

import scala.xml.NodeSeq
import com.normation.plugins.RudderPluginDef

import bootstrap.liftweb.PluginsInfo

class PluginManagement extends DispatchSnippet with Loggable {

  def dispatch = {
    case "display" => display _
  }


  def display(xml:NodeSeq) : NodeSeq = {
    (
    ".inner-portlet *" #> PluginsInfo.plugins.map { case(name,pluginDef) =>
        displayPlugin(pluginDef) _
      }
    ).apply(xml)
  }

  private[this] def displayPlugin(p:RudderPluginDef)(xml:NodeSeq) : NodeSeq = {
    (
      "data-plugin=name *" #> p.displayName &
      "data-plugin=id" #> p.name.value &
      "data-plugin=version" #> p.version.toString &
      "data-plugin=description" #> p.description &
      "data-plugin=statusInformation" #> p.statusInformation
    )(xml)
  }
}
