/*
*************************************************************************************
* Copyright 2011 Normation SAS
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

package com.normation.plugins

import scala.xml.NodeSeq

import com.normation.rudder.domain.logger.ApplicationLogger
import com.normation.utils.HashcodeCaching
import com.normation.utils.Utils.nonEmpty
import com.typesafe.config.{ Config, ConfigFactory }

import bootstrap.liftweb.{ ClassPathResource, ConfigResource, FileSystemResource, RudderProperties }
import net.liftweb.sitemap.Menu

case class PluginVersion(
    major : Int
  , minor : Int
  , micro : Int
  , prefix : String = ""
  , suffix : String = ""
)  extends HashcodeCaching {

  override def toString = prefix + major + "." + minor + "." + micro + (if(nonEmpty(suffix)) "~" + suffix else "")
}

case class PluginName(value:String) extends HashcodeCaching {
  if(null == value || value.length == 0) {
    ApplicationLogger.error("A plugin name can not be null nor empty")
    throw new IllegalArgumentException("A plugin name can not be null nor empty")
  }
}

/**
 * Definition of a rudder plugins
 */
trait RudderPluginDef {

  /**
   * The name of the plugin, used as the plugin
   * key for plugin resolution.
   * Two different plugin may have the same key
   * to allow overriding or redefinition.
   * The fully qualified name of the plugin definition
   * class will be used as an id (not that one).
   */
  def name : PluginName

  def displayName : String = name.value

  /**
   * The id of the plugin. It uniquely identify a
   * plugin for rudder.
   * The fully qualified name of the plugin if used
   * for that.
   */
  final lazy val id = this.getClass.getName

  /**
   * A description of the module, expected to be displayed to
   * end user.
   */
  def description : NodeSeq

  /**
   * Version of the plugin.
   */
  def version : PluginVersion


  /**
   * The init method of the plugin. It will be called
   * at each at start of the application, so do not put
   * one-time-ever init on here.
   *
   * On the other hand, snippet registration
   * and the like should go here.
   */
  def init : Unit

  /**
   * Provide a sitemap mutator to add that
   * plugin entries in the menu
   */
  def updateSiteMap(menus:List[Menu]) : List[Menu] = menus

  def basePackage : String

  /**
   * A list of config files to get properties from.
   * If some properties are defined in different config files,
   * the will be overriden (the last in the sequence win).
   */
  def configFiles: Seq[ConfigResource]

  /**
   * Build the map of config properties.
   */
  lazy val config : Config = {
    val configs = configFiles.map {
      case ClassPathResource(name) => ConfigFactory.load(name)
      case FileSystemResource(file) => ConfigFactory.load(ConfigFactory.parseFile(file))
    }

    if(configs.isEmpty) {
      RudderProperties.config
    } else {
      configs.reduceLeft[Config] { case (config, newConfig) =>
        newConfig.withFallback(config)
      }
    }
  }

}