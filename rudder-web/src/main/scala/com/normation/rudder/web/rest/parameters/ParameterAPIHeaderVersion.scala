/*
*************************************************************************************
* Copyright 2013 Normation SAS
*************************************************************************************
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Affero General Public License as
* published by the Free Software Foundation, either version 3 of the
* License, or (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU Affero GPL v3, the copyright holders add the following
* Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU Affero GPL v3
* licence, when you create a Related Module, this Related Module is
* not considered as a part of the work and may be distributed under the
* license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Affero General Public License for more details.
*
* You should have received a copy of the GNU Affero General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/agpl.html>.
*
*************************************************************************************
*/

package com.normation.rudder.web.rest.parameter

import com.normation.rudder.repository.RoParameterRepository
import com.normation.rudder.web.rest.ApiVersion
import com.normation.rudder.web.rest.RestUtils._
import com.normation.rudder.web.rest.RestExtractorService

import net.liftweb.common.Box
import net.liftweb.common.EmptyBox
import net.liftweb.common.Full
import net.liftweb.common.Loggable
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import net.liftweb.http.rest.RestHelper
import net.liftweb.json.JString
import net.liftweb.json.JsonDSL._

class ParameterAPIHeaderVersion (
    restExtractor  : RestExtractorService
  , apiV2          : ParameterApiService2
) extends RestHelper with ParameterAPI with Loggable {

  val requestDispatch : PartialFunction[Req, () => Box[LiftResponse]] = {

    case Get(Nil, req) => {
      ApiVersion.fromRequest(req) match {
        case Full(ApiVersion(2)) =>  apiV2.listParameters(req)
        case Full(ApiVersion(missingVersion)) => missingResponse(missingVersion,"listAcceptedNodes")
        case _ => notValidVersionResponse("listParameters")
      }
    }

    case Nil JsonPut body -> req => {
      implicit val action = "createParameter"
      implicit val prettify = restExtractor.extractPrettify(req.params)
      ApiVersion.fromRequest(req) match {
        case Full(ApiVersion(2)) =>
          req.json match {
            case Full(json) =>

              val restParameter = restExtractor.extractParameterFromJSON(json)
              restExtractor.extractParameterNameFromJSON(json) match {
                case Full(parameterName) =>
                  apiV2.createParameter(restParameter, parameterName, req)
                case eb : EmptyBox =>
                  val fail = eb ?~ (s"Could extract parameter id from request" )
                  val message = s"Could not create Parameter cause is: ${fail.msg}."
                  toJsonError(None, message)
              }
            case eb:EmptyBox=>
              toJsonError(None, JString("No Json data sent"))
          }
        case Full(ApiVersion(missingVersion)) => missingResponse(missingVersion,"listAcceptedNodes")
        case _ => notValidVersionResponse("createParameter")
      }
    }

    case Put(Nil, req) => {
      ApiVersion.fromRequest(req) match {
        case Full(ApiVersion(2)) =>
          implicit val action = "createParameter"
          implicit val prettify = restExtractor.extractPrettify(req.params)
          val restParameter = restExtractor.extractParameter(req.params)
          restExtractor.extractParameterName(req.params) match {
            case Full(parameterName) =>
              apiV2.createParameter(restParameter, parameterName, req)
            case eb : EmptyBox =>
              val fail = eb ?~ (s"Could extract parameter id from request" )
              val message = s"Could not create Parameter cause is: ${fail.msg}."
              toJsonError(None, message)
          }
        case Full(ApiVersion(missingVersion)) => missingResponse(missingVersion,"listAcceptedNodes")
        case _ => notValidVersionResponse("createParameter")
      }
    }

    case Get(id :: Nil, req) => {
      ApiVersion.fromRequest(req) match {
        case Full(ApiVersion(2)) => apiV2.parameterDetails(id, req)
        case Full(ApiVersion(missingVersion)) => missingResponse(missingVersion,"listAcceptedNodes")
        case _ => notValidVersionResponse("listParameters")
      }
    }

    case Delete(id :: Nil, req) => {
      ApiVersion.fromRequest(req) match {
        case Full(ApiVersion(2)) => apiV2.deleteParameter(id,req)
        case Full(ApiVersion(missingVersion)) => missingResponse(missingVersion,"listAcceptedNodes")
        case _ => notValidVersionResponse("deleteParameter")
      }
    }

    case id :: Nil JsonPost body -> req => {
      ApiVersion.fromRequest(req) match {
        case Full(ApiVersion(2)) =>
          req.json match {
            case Full(arg) =>
              val restParameter = restExtractor.extractParameterFromJSON(arg)
              apiV2.updateParameter(id,req,restParameter)
            case eb:EmptyBox=>
              toJsonError(Some(id), JString("no Json Data sent"))("updateParameter",restExtractor.extractPrettify(req.params))
          }
        case Full(ApiVersion(missingVersion)) => missingResponse(missingVersion,"listAcceptedNodes")
        case _ => notValidVersionResponse("updateParameter")
      }

    }

    case Post(id:: Nil, req) => {
      ApiVersion.fromRequest(req) match {
        case Full(ApiVersion(2)) =>
          val restParameter = restExtractor.extractParameter(req.params)
          apiV2.updateParameter(id,req,restParameter)
        case Full(ApiVersion(missingVersion)) => missingResponse(missingVersion,"listAcceptedNodes")
        case _ => notValidVersionResponse("updateParameter")
      }
    }

  }
  serve( "api" / "parameters" prefix requestDispatch)

}
