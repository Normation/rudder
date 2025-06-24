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

import bootstrap.liftweb.MenuUtils
import com.normation.rudder.domain.logger.ApplicationLogger
import com.normation.rudder.domain.logger.PluginLogger
import com.typesafe.config.Config
import com.typesafe.config.ConfigException
import com.typesafe.config.ConfigFactory
import net.liftweb.sitemap.Menu
import net.liftweb.util.Helpers
import scala.xml.NodeSeq

trait PluginEnableImpl extends PluginStatus {
  override val current: RudderPluginLicenseStatus = RudderPluginLicenseStatus.EnabledNoLicense
  override def isEnabled() = true
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

  // get properties name for the plugin from "build.conf" file
  // have default string for errors (and avoid "missing prop exception"):
  lazy val defaults: String = {
    val d1 = List(
      "plugin-name",
      "plugin-fullname",
      "plugin-title-description",
      "plugin-version"
    ).map(p => s"$p=missing property with name '$p' in file 'build.conf' for '${basePackage}'").mkString("\n")

    val d2 = List(
      "branch-type",
      "rudder-version",
      "common-version",
      "private-version"
    ).map(p => s"$p=missing property with name '$p' in file 'main-build.conf' for '${basePackage}'").mkString("\n")

    val res = d1 + "\n" + d2
    res
  }

  // by convention, plugin "build.conf" and plugin-commons "main-build.conf" files are copied into path:
  // target/classes/com/normation/plugins/${project.artifactId}
  lazy val buildConfPath:     String = basePackage.replaceAll("""\.""", "/") + "/build.conf"
  lazy val mainBuildConfPath: String = basePackage.replaceAll("""\.""", "/") + "/main-build.conf"
  lazy val buildConf:         Config = {
    try {
      val c1 = ConfigFactory.load(this.getClass.getClassLoader, buildConfPath).withFallback(ConfigFactory.parseString(defaults))
      ConfigFactory.load(this.getClass.getClassLoader, mainBuildConfPath).withFallback(c1)
    } catch {
      case ex: ConfigException => // something want very wrong with "build.conf" parsing
        ApplicationLogger.error(s"Error when parsing coniguration file for plugin '${basePackage}': ${ex.getMessage}", ex)
        ConfigFactory.parseString(defaults)
    }
  }

  override lazy val name:        PluginName          = PluginName(buildConf.getString("plugin-fullname"))
  override lazy val shortName:   String              = buildConf.getString("plugin-name")
  override lazy val displayName: String              = buildConf.getString("plugin-title-description")
  override lazy val version:     RudderPluginVersion = {
    val versionString = buildConf.getString("rudder-version") + "-" + buildConf.getString("plugin-version")
    RudderPluginVersion.from(versionString).getOrElse(RudderPluginVersion.PARSING_ERROR(versionString))
  }
  override lazy val versionInfo: Option[String]      = {
    try {
      Some(buildConf.getString("version-info"))
    } catch {
      case e: ConfigException.Missing => None
    }
  }

  override def description: NodeSeq = (
    <div>
     {
      if (buildConf.hasPathOrNull("plugin-web-description")) {
        Helpers.secureXML.loadString(buildConf.getString("plugin-web-description"))
      } else {
        displayName
      }
    }
     </div>
  )

  /*
   * By default, the plugin can provide a menu entry and it is added under the
   * "Plugins" menu. Override that method if you want a more potent
   * interaction with Rudder menu (at the risk of breaking it).
   */
  def pluginMenuEntry:  List[(Menu, Option[String])] = Nil
  def pluginMenuParent: List[Menu]                   = Nil

  override def updateSiteMap(menus: List[Menu]): List[Menu] = {

    val updatedMenu = pluginMenuParent
      .foldRight(menus) {
        // We need to ensure plugin name is managed correctly between plugins
        case (newParent, menu) =>
          if (menu.exists(_.loc.name == newParent.loc.name)) menu else newParent :: menu
      }
      .sortBy(_.loc.name)

    pluginMenuEntry.foldRight(updatedMenu) {
      case ((newMenu, optParent), menu) =>
        val parent = optParent.getOrElse(MenuUtils.administrationMenu)

        menu.map {
          case m: Menu if (m.loc.name == parent) =>
            // We need to avoid collision on name/loc
            if (m.kids.exists(_.loc.name == newMenu.loc.name)) {
              PluginLogger.error(s"There is already a menu with id (${newMenu.loc.name}, please contact Plugin team")
              m
            } else {
              Menu(m.loc, (m.kids :+ newMenu).sortBy(_.loc.name)*)
            }
          case m => m
        }
    }
  }
}
