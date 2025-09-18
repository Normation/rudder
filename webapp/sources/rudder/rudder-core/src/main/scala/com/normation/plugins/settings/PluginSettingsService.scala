/*
 *************************************************************************************
 * Copyright 2025 Normation SAS
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

package com.normation.plugins.settings

import better.files.File
import com.normation.errors.*
import com.normation.rudder.domain.logger.ApplicationLoggerPure
import java.util.Properties
import zio.syntax.*

/**
  * Service that manages a global state (boolean) for the status of the setup,
  * and that manages the settings value.
  */
trait PluginSettingsService {
  def checkIsSetup():       IOResult[Boolean]
  def readPluginSettings(): IOResult[PluginSettings]
  def writePluginSettings(settings: PluginSettings): IOResult[Unit]
}

/**
  * Implementation that manages settings value in a properties file,
  * and syncs the setup state
  */
class FilePluginSettingsService(pluginConfFile: File, readSetupDone: IOResult[Boolean], writeSetupDone: Boolean => IOResult[Unit])
    extends PluginSettingsService {

  /**
    * Watch the rudder_setup_done setting to see if the plugin settings has been setup.
    * It has the side effect of updating the `rudder_setup_done` setting.
    *
    * @return the boolean with the semantics of :
    *  rudder_setup_done && !(is_setting_default || is_setting_empty)
    * and false when the plugin settings are not set, and setup is not done
    */
  def checkIsSetup(): IOResult[Boolean] = {
    readSetupDone
      .flatMap(isSetupDone => {
        if (isSetupDone) {
          true.succeed
        } else {
          // we may need to update setup_done if settings are defined
          readPluginSettings().map(_.isDefined).flatMap {
            case true  =>
              ApplicationLoggerPure.info(
                s"Read plugin settings properties file ${pluginConfFile.pathAsString} with a defined configuration, rudder_setup_done setting is marked as `true`. Go to Rudder Setup page to change the account credentials."
              ) *> writeSetupDone(true).as(true)
            case false =>
              // the plugin settings are not set, setup is not done
              false.succeed
          }
        }
      })
      .tapError(err => ApplicationLoggerPure.error(s"Could not get setting `rudder_setup_done` : ${err.fullMsg}"))
  }

  def readPluginSettings(): IOResult[PluginSettings] = {

    val p = new Properties()
    for {
      _ <- IOResult.attempt(s"Reading properties from ${pluginConfFile.pathAsString}")(p.load(pluginConfFile.newInputStream))

      url            <- IOResult.attempt(s"Getting plugin repository url in ${pluginConfFile.pathAsString}") {
                          val res = p.getProperty("url", "")
                          if (res == "") None else Some(res)
                        }
      userName       <-
        IOResult.attempt(s"Getting user name for plugin download in ${pluginConfFile.pathAsString}") {
          val res = p.getProperty("username", "")
          if (res == "") None else Some(res)
        }
      pass           <-
        IOResult.attempt(s"Getting password for plugin download in ${pluginConfFile.pathAsString}") {
          val res = p.getProperty("password", "")
          if (res == "") None else Some(res)
        }
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

  def writePluginSettings(update: PluginSettings): IOResult[Unit] = {
    for {
      base <- readPluginSettings()
      _    <- IOResult.attempt({
                val settings = base.copy(
                  url = update.url orElse base.url,
                  username = update.username orElse base.username,
                  password = update.password orElse base.password,
                  proxyUrl = update.proxyUrl orElse base.proxyUrl,
                  proxyUser = update.proxyUser orElse base.proxyUser,
                  proxyPassword = update.proxyPassword orElse base.proxyPassword
                )
                pluginConfFile.write(s"""[Rudder]
                                     |url = ${settings.url.getOrElse("")}
                                     |username = ${settings.username.getOrElse("")}
                                     |password = ${settings.password.getOrElse("")}
                                     |proxy_url = ${settings.proxyUrl.getOrElse("")}
                                     |proxy_user = ${settings.proxyUser.getOrElse("")}
                                     |proxy_password = ${settings.proxyPassword.getOrElse("")}
                                     |""".stripMargin)
              })
    } yield {}
  }
}
