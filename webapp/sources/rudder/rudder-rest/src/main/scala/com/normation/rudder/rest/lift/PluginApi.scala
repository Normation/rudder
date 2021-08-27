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
import com.normation.plugins.PluginSettingsService
import com.normation.rudder.api.ApiVersion
import com.normation.rudder.rest.ApiPath
import com.normation.rudder.rest.AuthzToken
import com.normation.rudder.rest.RestExtractorService
import com.normation.rudder.rest.RestUtils
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import net.liftweb.json.DefaultFormats
import net.liftweb.json.Serialization
import com.normation.rudder.rest.{PluginApi => API}

class PluginApi (
  restExtractorService: RestExtractorService
, pluginSettingsService: PluginSettingsService
) extends LiftApiModuleProvider[API] {

  def schemas = API

  def getLiftEndpoints(): List[LiftApiModule] =
    API.endpoints.map(e => e match {
      case API.GetPluginsSettings => GetPluginSettings
      case API.UpdatePluginsSettings => UpdatePluginSettings
    })

  object GetPluginSettings extends LiftApiModule0 {
    val schema  = API.GetPluginsSettings
    val restExtractor = restExtractorService
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      import com.normation.box._
    val json=   for {
        conf <- pluginSettingsService.readPluginSettings()
      } yield {
      import net.liftweb.json.JsonDSL._
      (  ("username" -> conf.username)
      ~  ("password" -> conf.password)
      ~  ("url" -> conf.url)
      ~ ("proxyUrl" -> conf.proxyUrl)
      ~ ("proxyUser" -> conf.proxyUser)
      ~ ("proxyPassword" -> conf.proxyPassword)
      )

      }
      RestUtils.response(
        restExtractor
      , "pluginSettings"
      , None
      )(
        json.toBox
      , req
      , s"Could not get plugin settings"
      ) ("getPluginSettings")
    }

  }

  object UpdatePluginSettings extends LiftApiModule0 {
    val schema = API.UpdatePluginsSettings
    val restExtractor = restExtractorService
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      import com.normation.box._
      import com.normation.errors._

      implicit val formats = DefaultFormats
      val json=
        for {
        json <- req.json.toIO
        conf <- IOResult.effect(Serialization.read[PluginSettings](net.liftweb.json.compactRender(json)))
        _ <- pluginSettingsService.writePluginSettings(conf)

      } yield {
        import net.liftweb.json.JsonDSL._
        (  ("username" -> conf.username)
          ~  ("password" -> conf.password)
          ~  ("url" -> conf.url)
          ~ ("proxyUrl" -> conf.proxyUrl)
          ~ ("proxyUser" -> conf.proxyUser)
          ~ ("proxyPassword" -> conf.proxyPassword)
          )

      }
      RestUtils.response(
        restExtractor
        , "pluginSettings"
        , None
      )(
        json.toBox
        , req
        , s"Could not update plugin settings"
      ) ("updatePluginSettings")
    }
  }

}
