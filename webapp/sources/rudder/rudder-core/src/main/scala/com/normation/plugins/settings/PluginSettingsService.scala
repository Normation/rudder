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
import com.normation.utils.Ini
import java.nio.charset.StandardCharsets
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

object FilePluginSettingsService {

  /*
   * `rudder-pkg.conf` is an INI file which is also read by `rudder package` with `serde_ini` (see `RawConfiguration`
   * in `relay/sources/rudder-package/src/config.rs`).
   * Keys living outside of that section are ignored by both implementations.
   */
  val RUDDER_SECTION = "Rudder"
}

/**
  * Implementation that manages settings value in an INI file,
  * and syncs the setup state
  */
class FilePluginSettingsService(pluginConfFile: File, readSetupDone: IOResult[Boolean], writeSetupDone: Boolean => IOResult[Unit])
    extends PluginSettingsService {
  import FilePluginSettingsService.RUDDER_SECTION

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
    for {
      content <- IOResult.attempt(s"Reading plugin settings from ${pluginConfFile.pathAsString}") {
                   pluginConfFile.contentAsString(using StandardCharsets.UTF_8)
                 }
      ini     <- Ini
                   .parse(content)
                   .chainError(s"Error in plugin settings file ${pluginConfFile.pathAsString}")
                   .toIO
    } yield {
      def get(key: String) = ini.getNonEmpty(RUDDER_SECTION, key)

      PluginSettings(
        get("url"),
        get("username"),
        get("password"),
        get("proxy_url"),
        get("proxy_user"),
        get("proxy_password")
      )
    }
  }

  def writePluginSettings(update: PluginSettings): IOResult[Unit] = {
    for {
      base    <- readPluginSettings()
      settings = PluginSettings(
                   url = update.url orElse base.url,
                   username = update.username orElse base.username,
                   password = update.password orElse base.password,
                   proxyUrl = update.proxyUrl orElse base.proxyUrl,
                   proxyUser = update.proxyUser orElse base.proxyUser,
                   proxyPassword = update.proxyPassword orElse base.proxyPassword
                 )
      // `Ini.render` refuses values it would not read back identically to avoid breaking persisted file content
      content <- Ini
                   .render(RUDDER_SECTION, settings.entries)
                   .chainError(s"Can not write plugin settings in ${pluginConfFile.pathAsString}")
                   .toIO
      _       <- IOResult.attempt(s"Writing plugin settings in ${pluginConfFile.pathAsString}") {
                   pluginConfFile.writeText(content)(using File.OpenOptions.default, StandardCharsets.UTF_8)
                 }
    } yield {}
  }
}
