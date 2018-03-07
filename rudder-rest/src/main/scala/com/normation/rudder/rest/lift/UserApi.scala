/*
*************************************************************************************
* Copyright 2017 Normation SAS
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

import com.normation.rudder.rest.RestExtractorService
import net.liftweb.http.Req
import net.liftweb.http.LiftResponse
import com.normation.utils.StringUuidGenerator
import com.normation.rudder.rest.ApiVersion
import net.liftweb.common.Full
import com.normation.eventlog.ModificationId
import com.normation.rudder.rest.ApiPath
import com.normation.rudder.rest.AuthzToken
import com.normation.rudder.api._
import net.liftweb.json.JsonAST.JArray
import net.liftweb.common.EmptyBox
import org.joda.time.DateTime
import com.normation.rudder.rest.ApiAccountSerialisation._
import com.normation.rudder.rest.RestUtils
import net.liftweb.json.JsonDSL._
import net.liftweb.json._

class UserApi(
    restExtractor : RestExtractorService
  , readApi       : RoApiAccountRepository
  , writeApi      : WoApiAccountRepository
  , tokenGenerator: TokenGenerator
  , uuidGen       : StringUuidGenerator
) extends LiftApiModuleProvider {
  api =>

  import com.normation.rudder.rest.{UserApi => API}

  def getLiftEndpoints(): List[LiftApiModule] = {
    API.endpoints.map(e => e match {
        case API.GetApiToken    => GetApiToken
        case API.CreateApiToken => CreateApiToken
        case API.DeleteApiToken => DeleteApiToken
    }).toList
  }

  /*
   * By convention, an USER API token has the user login for identifier and name
   * (so that we enforce only one token by user - that could be change in the future
   * by only enforcing the name)
   */


  object GetApiToken extends LiftApiModule0 {
    val schema = API.GetApiToken
    val restExtractor = api.restExtractor
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      readApi.getById(ApiAccountId(authzToken.actor.name)) match {
        case Full(Some(token)) =>
          val accounts: JValue = ("accounts" -> JArray(List(token.toJson)))
          RestUtils.toJsonResponse(None, accounts)(schema.name, true)

        case Full(None) =>
          val accounts: JValue = ("accounts" -> JArray(Nil))
          RestUtils.toJsonResponse(None, accounts)(schema.name, true)

        case eb: EmptyBox =>
          val e = eb ?~! s"Error when trying to get user '${authzToken.actor.name}' API token"
          RestUtils.toJsonError(None, e.messageChain)(schema.name, true)
      }
    }
  }

  object CreateApiToken extends LiftApiModule0 {
    val schema = API.CreateApiToken
    val restExtractor = api.restExtractor
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      val now = DateTime.now
      val account = ApiAccount(
          ApiAccountId(authzToken.actor.name)
        , ApiAccountKind.User
        , ApiAccountName(authzToken.actor.name)
        , ApiToken(tokenGenerator.newToken(32))
        , s"API token for user '${authzToken.actor.name}'"
        , true
        , now
        , now
      )

      writeApi.save(account, ModificationId(uuidGen.newUuid), authzToken.actor) match {
        case Full(token) =>
          val accounts: JValue = ("accounts" -> JArray(List(token.toJson)))
          RestUtils.toJsonResponse(None,accounts)(schema.name, true)

        case eb: EmptyBox =>
          val e = eb ?~! s"Error when trying to save user '${authzToken.actor.name}' API token"
          RestUtils.toJsonError(None, e.messageChain)(schema.name, true)
      }
    }
  }

  object DeleteApiToken extends LiftApiModule0 {
    val schema = API.DeleteApiToken
    val restExtractor = api.restExtractor
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      writeApi.delete(ApiAccountId(authzToken.actor.name), ModificationId(uuidGen.newUuid), authzToken.actor) match {
        case Full(token) =>
          val accounts: JValue = ("accounts" -> ("id" -> token.value))
          RestUtils.toJsonResponse(None,accounts)(schema.name, true)

        case eb: EmptyBox =>
          val e = eb ?~! s"Error when trying to delete user '${authzToken.actor.name}' API token"
          RestUtils.toJsonError(None, e.messageChain)(schema.name, true)
      }
    }
  }
}
