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

import com.normation.box._
import com.normation.rudder.domain.secrets.Secret
import com.normation.rudder.rest.ApiPath
import com.normation.rudder.rest.ApiVersion
import com.normation.rudder.rest.AuthzToken
import com.normation.rudder.rest.RestExtractorService
import com.normation.rudder.rest.RestUtils
import com.normation.rudder.rest.{SecretVaultApi => API}
import com.normation.rudder.web.services.SecretService
import net.liftweb.common._
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import net.liftweb.json.JArray
import net.liftweb.json.JsonDSL._


class SecretApi(
    restExtractorService : RestExtractorService
  , secretService        : SecretService
) extends LiftApiModuleProvider[API] {

  def schemas = API

  def getLiftEndpoints(): List[LiftApiModule] = {
    API.endpoints.map(
      e => e match {
        case API.GetSecrets   => GetSecrets
        case API.AddSecret    => AddSecret
        case API.UpdateSecret => UpdateSecret
        case API.DeleteSecret => DeleteSecret
      }
    )
  }

  object GetSecrets extends LiftApiModule0 {
    val schema        = API.GetSecrets
    val restExtractor = restExtractorService

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      implicit val prettify = params.prettify
      implicit val action = schema.name

      val res = for {
        secrets <- secretService.getSecrets
      } yield {
        secrets.map(Secret.serializeSecret)
      }

      res.toBox match {
        case Full(json) =>
          RestUtils.toJsonResponse(None, JArray(json))
        case eb: EmptyBox =>
          val message = (eb ?~ s"Error when trying get secret from file").messageChain
          RestUtils.toJsonError(None, message)(action, true)
      }
    }
  }

  object AddSecret extends LiftApiModule0 {
    val schema        = API.AddSecret
    val restExtractor = restExtractorService

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      implicit val prettify = params.prettify
      implicit val action = schema.name

      val res = for {
        secret <- restExtractorService.extractSecret(req)
        _      <- secretService.addSecret(secret).toBox
      } yield {
        Secret.serializeSecret(secret)
      }

      res match {
        case Full(json)   =>
          RestUtils.toJsonResponse(None, json)
        case eb: EmptyBox =>
          val message = (eb ?~ s"Error when trying add a secret entry").messageChain
          RestUtils.toJsonError(None, message)(action, true)
      }
    }
  }

  object DeleteSecret extends LiftApiModule {
    val schema        = API.DeleteSecret
    val restExtractor = restExtractorService

    def process(version: ApiVersion, path: ApiPath, id: String, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      implicit val prettify = params.prettify
      implicit val action = schema.name

      val res = for {
        _ <- secretService.deleteSecret(id).toBox
      } yield {
        id
      }

      res match {
        case Full(secretId) =>
          RestUtils.toJsonResponse(None, secretId)
        case eb: EmptyBox   =>
          val message = (eb ?~ s"Error when trying delete a secret entry").messageChain
          RestUtils.toJsonError(None, message)(action, true)
      }
    }
  }

  object UpdateSecret extends LiftApiModule0 {
    val schema        = API.UpdateSecret
    val restExtractor = restExtractorService

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      implicit val prettify = params.prettify
      implicit val action = schema.name

      val res = for {
        secret <- restExtractorService.extractSecret(req)
        _      <- secretService.updateSecret(secret).toBox
      } yield {
        Secret.serializeSecret(secret)
      }
      res match {
        case Full(json)   =>
          RestUtils.toJsonResponse(None, json)
        case eb: EmptyBox =>
          val message = (eb ?~ s"Error when trying update a secret entry").messageChain
          RestUtils.toJsonError(None, message)(action, true)
      }
    }
  }

}
