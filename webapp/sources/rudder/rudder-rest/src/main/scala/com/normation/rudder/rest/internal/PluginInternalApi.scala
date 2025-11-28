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

import com.normation.errors.Inconsistency
import com.normation.plugins.GlobalPluginsLicense
import com.normation.plugins.PluginId
import com.normation.plugins.PluginInstallStatus
import com.normation.plugins.PluginService
import com.normation.plugins.PluginsMetadata
import com.normation.plugins.cli.RudderPackageService.PluginSettingsError
import com.normation.rudder.api.ApiVersion
import com.normation.rudder.domain.logger.ApplicationLoggerPure
import com.normation.rudder.rest.ApiModuleProvider
import com.normation.rudder.rest.ApiPath
import com.normation.rudder.rest.AuthzToken
import com.normation.rudder.rest.PluginInternalApi as API
import com.normation.rudder.rest.RudderJsonRequest.*
import com.normation.rudder.rest.RudderJsonResponse
import com.normation.rudder.rest.data.JsonPluginsSystemDetails
import com.normation.rudder.rest.syntax.*
import io.scalaland.chimney.syntax.*
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import zio.Chunk
import zio.syntax.*

class PluginInternalApi(
    pluginService: PluginService
) extends LiftApiModuleProvider[API] {

  def schemas: ApiModuleProvider[API] = API

  def getLiftEndpoints(): List[LiftApiModule] = {
    API.endpoints.map {
      case API.UpdatePluginsIndex  => UpdatePluginsIndex
      case API.ListPlugins         => ListPlugins
      case API.InstallPlugins      => InstallPlugins
      case API.RemovePlugins       => RemovePlugins
      case API.ChangePluginsStatus => ChangePluginsStatus
    }
  }

  object UpdatePluginsIndex extends LiftApiModule0 {
    val schema:                                                                                                API.UpdatePluginsIndex.type = API.UpdatePluginsIndex
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse                = {
      pluginService
        .updateIndex()
        .chainError("Could not update plugins index")
        .foldZIO(
          err => ApplicationLoggerPure.Plugin.error(err.fullMsg) *> err.fail,
          {
            // the corresponding HTTP errors are the following ones
            case Some(PluginSettingsError.InvalidCredentials(msg)) =>
              Left(RudderJsonResponse.UnauthorizedError(Some(msg))).succeed
            case Some(PluginSettingsError.Unauthorized(msg))       =>
              Left(RudderJsonResponse.ForbiddenError(Some(msg))).succeed
            case Some(err)                                         =>
              err.fail
            case None                                              =>
              Right(()).succeed
          }
        )
        .toLiftResponseZeroEither(params, schema, None)
    }

  }

  object ListPlugins extends LiftApiModule0 {
    val schema:                                                                                                API.ListPlugins.type = API.ListPlugins
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse         = {
      pluginService
        .list()
        .chainError("Could not get plugins list")
        .tapError(err => ApplicationLoggerPure.Plugin.error(err.fullMsg))
        .map(plugins =>
          PluginsMetadata.fromPlugins[GlobalPluginsLicense.DateCounts](plugins).transformInto[JsonPluginsSystemDetails]
        )
        .toLiftResponseOne(params, schema, None)
    }

  }

  object InstallPlugins extends LiftApiModule0 {
    val schema:                                                                                                API.InstallPlugins.type = API.InstallPlugins
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse            = {
      ({
        for {
          plugins <- req.fromJson[Chunk[PluginId]].toIO.chainError("Could not parse the list of plugins to install")
          _       <- pluginService.install(plugins).tapError(err => ApplicationLoggerPure.Plugin.error(err.fullMsg))
        } yield ()
      }).chainError("Could not install plugins")
        .toLiftResponseZeroUnit(params, schema)
    }
  }

  object RemovePlugins extends LiftApiModule0 {
    val schema:                                                                                                API.RemovePlugins.type = API.RemovePlugins
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse           = {
      ({
        for {
          plugins <- req.fromJson[Chunk[PluginId]].toIO.chainError("Could not parse the list of plugins to remove")
          _       <- pluginService.remove(plugins)
        } yield ()
      }).chainError("Could not remove plugins")
        .tapError(err => ApplicationLoggerPure.error(err.fullMsg))
        .toLiftResponseZeroUnit(params, schema)
    }
  }

  object ChangePluginsStatus extends LiftApiModuleString {
    val schema: API.ChangePluginsStatus.type = API.ChangePluginsStatus
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        s:          String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      ({
        for {
          status  <- s match {
                       case "enable"  => PluginInstallStatus.Enabled.succeed
                       case "disable" => PluginInstallStatus.Disabled.succeed
                       case _         => Inconsistency(s"Unknown plugin status, valid status are enable/disabled").fail
                     }
          plugins <- req.fromJson[Chunk[PluginId]].toIO.chainError("Could not parse the list of plugins to remove")
          _       <- pluginService.updateStatus(status, plugins).tapError(err => ApplicationLoggerPure.Plugin.error(err.fullMsg))
        } yield ()
      }).chainError(s"Could not change plugin status to '${s}'")
        .toLiftResponseZeroUnit(params, schema)
    }
  }

}
