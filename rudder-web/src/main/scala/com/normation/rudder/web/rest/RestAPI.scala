/*
*************************************************************************************
* Copyright 2014 Normation SAS
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

package com.normation.rudder.web.rest

import net.liftweb.http.Req
import net.liftweb.common.Box
import net.liftweb.http.LiftResponse
import net.liftweb.http.rest.RestHelper
import net.liftweb.common.Full
import net.liftweb.common.EmptyBox
import net.liftweb.json.JString
import net.liftweb.json.JValue



/**
 * The trait of all API definition
 * It needs to define its kind (will  be the url prefix )
 * and its requestDispatch ( which requests are concerned, how to handle it and respond )
 * That class will not dispatch the api itself only define it
 */
trait RestAPI  extends RestHelper{

  def kind : String

  def requestDispatch(apiVersion : ApiVersion): PartialFunction[Req, () => Box[LiftResponse]]

}


/**
 * The class that actually dispatch the API
 * It dispatchs them from a map of API definition
 * then define the latest version of API and dispatch it as latest
 * Then define the header API from the map
 */
case class APIDispatcher (
    apisByVersion : Map[ApiVersion, List[RestAPI]]
  , restExtractor : RestExtractorService
) extends RestHelper {

    // For each api version
    apisByVersion.foreach{
      case (v@ApiVersion(version,_),apis) =>
        // Dispatch all api
        apis.foreach{
          api =>
            serve("api" / s"${version}" / s"${api.kind}" prefix api.requestDispatch(v)  )
        }
    }

    // Get the max version ...
    val latest = apisByVersion.keySet.maxBy(_.value)
    apisByVersion(latest).foreach{
      api =>
        // ... and Dispatch it
        serve("api" / "latest" / s"${api.kind}" prefix api.requestDispatch(latest))
    }

    // regroup api by kind, to be able to dispatch header api version
    val apiByKindByVersion = apisByVersion.toList.flatMap{case (version,list) => list.map((version,_))}.groupBy(_._2.kind).mapValues(_.toMap)

    // for each kind
    apiByKindByVersion.foreach{
      case (kind,apis) =>

        implicit val availableVersions = apis.keySet.toList
        // Build request dispatch
        val requestDispatch : PartialFunction[Req, () => Box[LiftResponse]] = {
        // on all requests
        case req =>
            // analyze apiVersion
            ApiVersion.fromRequest(req) match {
              case Full(apiVersion) => apis.get(apiVersion) match {
                // If the api exists
                case Some(api) =>
                  // Use the api of that version
                  api.requestDispatch(apiVersion)(req)
                case None =>
                  // Not a valid version
                  RestUtils.notValidVersionResponse(kind)
              }
              case eb : EmptyBox =>
                // Not a valid version
                RestUtils.notValidVersionResponse(kind)
              }
            }
        // Dispatch header API
        serve("api" / s"${kind}" prefix requestDispatch)
    }


    serve {
      case Get("api" :: "info" :: Nil,req) =>
        implicit val action = "ApiGeneralInformations"
        implicit val prettify = restExtractor.extractPrettify(req.params)

        import net.liftweb.json.JsonDSL._
        implicit def apiVersionToJValue (version : ApiVersion) : JValue ={
          ("version" -> version.value) ~
          ("status" -> {if (version.deprecated) "deprecated" else "maintained"})
        }
        val availableVersions = apisByVersion.keySet.toList.sortBy(_.value)
        val max = availableVersions.maxBy(_.value)
        val versions: JValue =
          ( "availableVersions" ->
            ( "latest" -> max.value) ~
            ( "all" -> availableVersions)
          )


        RestUtils.toJsonResponse(None, versions)

    }
}
