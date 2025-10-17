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

import com.normation.rudder.api.ApiVersion
import com.normation.rudder.api.HttpAction
import com.normation.rudder.rest.*
import com.normation.rudder.rest.InfoApi as API
import com.normation.rudder.rest.implicits.*
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import zio.json.ast.*
import zio.json.ast.Json.*
import zio.syntax.*

/*
 * Information about the API
 */
class InfoApi(
    supportedVersions: List[ApiVersion],
    endpoints:         List[Endpoint]
) extends LiftApiModuleProvider[API] {
  api =>

  def schemas: ApiModuleProvider[API] = API

  def getLiftEndpoints(): List[LiftApiModule] = {
    API.endpoints.map {
      case API.ApiGeneralInformations => ApiGeneralInformations
      case API.ApiSubInformations     => ApiSubInformations
      case API.ApiInformations        => ApiInformations
    }
  }

  private case class EndpointInfo(
      name:     String,
      action:   HttpAction,
      versions: Set[ApiVersion],
      desc:     String,
      path:     ApiPath
  )

  implicit class ApiVersionToJson(version: ApiVersion) {
    def json: Json.Obj = {
      Obj(
        "version" -> Num(version.value),
        "status"  -> Str({ if (version.deprecated) "deprecated" else "maintained" })
      )
    }
  }

  private def list(startWith: Option[String]): Obj = {

    implicit class EndpointToJson(endpoint: EndpointInfo) {
      def json: Json.Obj = {
        val versions = endpoint.versions.map(_.value).toList.sorted.mkString("[", ",", "]")
        val action   = endpoint.action.name.toUpperCase()

        Obj(
          endpoint.name -> Str(endpoint.desc),
          action        -> Str(versions + " /" + endpoint.path.value)
        )
      }
    }

    val availableVersions = supportedVersions.sortBy(_.value)

    availableVersions.reverse match {
      case Nil =>
        Obj(
          "documentation" -> Str("https://docs.rudder.io/api/"),
          "error"         -> Str("No API version supported. please contact your administrator or report a bug")
        )

      case max :: tail =>
        val list = endpoints.filter(e => {
          (e.schema.kind == ApiKind.General || e.schema.kind == ApiKind.Public) &&
          (startWith match {
            case None    => true
            case Some(x) => e.schema.path.parts.head.value == x
          })
        })

        // we want to keep endpoints in their zz order, because it's important information.
        // the `schema` is treated to be unique, as it is an `object` that identifies an endpoint declaration
        val jsonInfos     = list.groupBy(_.schema).map {
          case (endpoint, seq) =>
            // we just want to gather version for each api
            (
              endpoint,
              EndpointInfo(
                endpoint.name,
                seq.head.schema.action,
                seq.map(_.version).toSet,
                seq.head.schema.description,
                seq.head.schema.path
              ).json
            )
        }
        val jsonEndpoints = list.map(_.schema).distinct.map(n => jsonInfos(n)) // can't fail, same source 'list'

        Obj(
          "documentation"     -> Str("https://docs.rudder.io/api/"),
          "availableVersions" -> Obj("latest" -> Num(max.value), "all" -> Arr(availableVersions.map(_.json)*)),
          "endpoints"         -> Arr(jsonEndpoints*)
        )
    }
  }

  object ApiGeneralInformations extends LiftApiModule0 {
    val schema: API.ApiGeneralInformations.type = API.ApiGeneralInformations

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      list(None).succeed.toLiftResponseOne(params, schema, _ => None)
    }
  }

  object ApiSubInformations extends LiftApiModule {
    val schema: OneParam = API.ApiSubInformations
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        name:       String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      list(Some(name)).succeed.toLiftResponseOne(params, schema, _ => None)
    }
  }

  object ApiInformations extends LiftApiModule {
    val schema: OneParam = API.ApiInformations
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        name:       String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {

      implicit class EndpointToJson(endpoint: Endpoint) {
        def json: Json.Obj = {
          val path   = "/" + endpoint.prefix.value + "/" + endpoint.schema.path.value
          val action = endpoint.schema.action.name.toUpperCase()

          Obj(
            action    -> Str(path),
            "version" -> endpoint.version.json
          )
        }
      }

      val json = endpoints
        .filter(e => e.schema.name.equalsIgnoreCase(name) && e.schema.kind != ApiKind.Internal)
        .sortBy(_.version.value) match {
        case Nil =>
          Obj(
            "documentation" -> Str("https://docs.rudder.io/api/"),
            "error"         -> Str(s"No endpoint with name '${name}' defined.")
          )

        case h :: tail =>
          val jsonEndpoints = (h :: tail).map(_.json)

          Obj(
            "documentation" -> Str("https://docs.rudder.io/api/"),
            name            -> Str(h.schema.description),
            "endpoints"     -> Arr(jsonEndpoints*)
          )
      }
      json.succeed.toLiftResponseOne(params, schema, _ => None)
    }
  }

}
