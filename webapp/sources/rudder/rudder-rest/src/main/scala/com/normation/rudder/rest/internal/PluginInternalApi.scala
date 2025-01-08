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

package com.normation.rudder.rest.lift

import com.normation.plugins.PluginSettings
import com.normation.plugins.PluginSystemService
import com.normation.rudder.api.ApiVersion
import com.normation.rudder.domain.logger.ApplicationLoggerPure
import com.normation.rudder.rest.ApiModuleProvider
import com.normation.rudder.rest.ApiPath
import com.normation.rudder.rest.AuthzToken
import com.normation.rudder.rest.PluginInternalApi as API
import com.normation.rudder.rest.implicits.*
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import zio.json.DeriveJsonDecoder
import zio.json.DeriveJsonEncoder
import zio.json.JsonDecoder
import zio.json.JsonEncoder

class PluginInternalApi(
    pluginService: PluginSystemService
) extends LiftApiModuleProvider[API] {

  def schemas: ApiModuleProvider[API] = API

  def getLiftEndpoints(): List[LiftApiModule] = {
    API.endpoints.map { case API.ListPlugins => ListPlugins }
  }

  implicit val encoder: JsonEncoder[PluginSettings] = DeriveJsonEncoder.gen[PluginSettings]
  implicit val decoder: JsonDecoder[PluginSettings] = DeriveJsonDecoder.gen[PluginSettings]

  object ListPlugins extends LiftApiModule0 {
    val schema:                                                                                                API.ListPlugins.type = API.ListPlugins
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse         = {
      pluginService
        .list()
        .chainError("Could not get plugins list")
        .tapError(err => ApplicationLoggerPure.error(err.fullMsg))
        .toLiftResponseList(params, schema)
    }

  }

  // object UpdatePluginSettings extends LiftApiModule0 {
  //   val schema: API.UpdatePluginsSettings.type = API.UpdatePluginsSettings
  //   val restExtractor = restExtractorService
  //   def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
  //     import com.normation.errors.*
  //     ({
  //       for {
  //         json <- req.fromJson[PluginSettings].toIO
  //         _    <- pluginSettingsService.writePluginSettings(json)

  //       } yield {
  //         json.copy(password = None, proxyPassword = None)

  //       }
  //     }).toLiftResponseOne(params, schema, None)
  //   }
  // }

}
