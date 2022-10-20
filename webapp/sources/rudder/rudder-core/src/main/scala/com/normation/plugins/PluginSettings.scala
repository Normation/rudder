/*
 *************************************************************************************
 * Copyright 2021 Normation SAS
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

import better.files.File
import com.normation.errors.IOResult
import java.util.Properties

case class PluginSettings(
    url:           String,
    username:      String,
    password:      String,
    proxyUrl:      Option[String],
    proxyUser:     Option[String],
    proxyPassword: Option[String]
)

trait PluginSettingsService {
  def readPluginSettings(): IOResult[PluginSettings]
  def writePluginSettings(settings: PluginSettings): IOResult[Unit]
}

class FilePluginSettingsService(
    pluginConfFile: File
) extends PluginSettingsService {

  def readPluginSettings() = {

    val p = new Properties()
    for {
      _ <- IOResult.attempt(s"Reading properties from ${pluginConfFile.pathAsString}")(p.load(pluginConfFile.newInputStream))

      url            <- IOResult.attempt(s"Getting plugin repository url in ${pluginConfFile.pathAsString}")(p.getProperty("url"))
      userName       <-
        IOResult.attempt(s"Getting user name for plugin download in ${pluginConfFile.pathAsString}")(p.getProperty("username"))
      pass           <-
        IOResult.attempt(s"Getting password for plugin download in ${pluginConfFile.pathAsString}")(p.getProperty("password"))
      proxy          <- IOResult.attempt(s"Getting proxy for plugin download in ${pluginConfFile.pathAsString}") {
                          val res = p.getProperty("proxy_url", "")
                          if (res == "") None else Some(res)
                        }
      proxy_user     <- IOResult.attempt(s"Getting proxy for plugin download in ${pluginConfFile.pathAsString}") {
                          val res = p.getProperty("proxy_user", "")
                          if (res == "") None else Some(res)
                        }
      proxy_password <- IOResult.attempt(s"Getting proxy for plugin download in ${pluginConfFile.pathAsString}") {
                          val res = p.getProperty("proxy_password", "")
                          if (res == "") None else Some(res)
                        }
    } yield {
      PluginSettings(url, userName, pass, proxy, proxy_user, proxy_password)
    }
  }

  def writePluginSettings(settings: PluginSettings): IOResult[Unit] = {
    IOResult.attempt({
      pluginConfFile.write(s"""[Rudder]
                              |url = ${settings.url}
                              |username = ${settings.username}
                              |password = ${settings.password}
                              |proxy_url = ${settings.proxyUrl.getOrElse("")}
                              |proxy_user = ${settings.proxyUser.getOrElse("")}
                              |proxy_password = ${settings.proxyPassword.getOrElse("")}
                              |""".stripMargin)
    })

  }
}
