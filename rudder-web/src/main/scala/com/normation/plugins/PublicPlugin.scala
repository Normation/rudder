/*
*************************************************************************************
* Copyright 2018 Normation SAS
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

import com.normation.rudder.domain.logger.ApplicationLogger
import com.typesafe.config.ConfigException
import com.typesafe.config.ConfigFactory
import net.liftweb.util.Helpers

import scala.xml.NodeSeq


trait PluginEnableImpl extends PluginStatus {
  override val current = PluginStatusInfo.EnabledNoLicense
  override val isEnabled = true
}

/*
 * A standard implementation of the RudderPluginDef which expects to get most of its properties
 * from "build.conf" file.
 * It also manage plugin information from a License plugin information trait.
 *
 * Children must give base package (implement `basePackage`) so that build.conf file can be found.
 */
trait DefaultPluginDef extends RudderPluginDef {


  def status: PluginStatus

  //get properties name for the plugin from "build.conf" file
  //have default string for errors (and avoid "missing prop exception"):
  lazy val defaults = List(
      "plugin-name"
    , "plugin-fullname"
    , "plugin-title-description"
    , "plugin-version"
  ).map(p => s"$p=missing property with name '$p' in file 'build.conf' for '${basePackage}'").mkString("\n")

  //by convention, plugin "build.conf" file is copied into path:
  // target/classes/com/normation/plugins/${project.artifactId}
  lazy val buildConfPath = basePackage.replaceAll("""\.""", "/") + "/build.conf"
  lazy val buildConf = try {
    ConfigFactory.load(this.getClass.getClassLoader, buildConfPath).withFallback(ConfigFactory.parseString(defaults))
  } catch {
    case ex: ConfigException => //something want very wrong with "build.conf" parsing

      ApplicationLogger.error(s"Error when parsing coniguration file for plugin '${basePackage}': ${ex.getMessage}", ex)
      ConfigFactory.parseString(defaults)
  }

  override lazy val name = PluginName(buildConf.getString("plugin-fullname"))
  override lazy val shortName = buildConf.getString("plugin-name")
  override lazy val displayName = buildConf.getString("plugin-title-description")
  override lazy val version = {
    val versionString = buildConf.getString("plugin-version")
    PluginVersion.from(versionString).getOrElse(
      //a version name that indicate an erro
      PluginVersion(0,0,1, s"ERROR-PARSING-VERSION: ${versionString}")
    )
  }

  override def description : NodeSeq  = (
     <div>
     {
      if(buildConf.hasPathOrNull("plugin-web-description")) {
        Helpers.secureXML.loadString(buildConf.getString("plugin-web-description"))
      } else {
        displayName
      }
     }
     </div>
  )
}



