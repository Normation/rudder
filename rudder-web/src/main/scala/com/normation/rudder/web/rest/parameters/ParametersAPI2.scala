/*
*************************************************************************************
* Copyright 2013 Normation SAS
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

package com.normation.rudder.web.rest.parameter

import com.normation.rudder.repository.RoParameterRepository
import com.normation.rudder.web.rest.RestUtils.toJsonError
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
import com.normation.rudder.web.rest.ApiVersion

class ParameterAPI2 (
    restExtractor: RestExtractorService
  , apiV2        : ParameterApiService2
) extends ParameterAPI with Loggable{


  override def requestDispatch(apiVersion: ApiVersion) : PartialFunction[Req, () => Box[LiftResponse]] = {

    case Get(Nil, req) => apiV2.listParameters(req)

    case Nil JsonPut body -> req => {
      implicit val action = "createParameter"
      implicit val prettify = restExtractor.extractPrettify(req.params)
      req.json match {
        case Full(json) =>
          logger.info(json)
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
    }

    case Put(Nil, req) => {
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
    }

    case Get(id :: Nil, req) => apiV2.parameterDetails(id, req)

    case Delete(id :: Nil, req) =>  apiV2.deleteParameter(id,req)

    case id :: Nil JsonPost body -> req => {
      implicit val action = "updateParameter"
      implicit val prettify = restExtractor.extractPrettify(req.params)
      req.json match {
        case Full(arg) =>
          val restParameter = restExtractor.extractParameterFromJSON(arg)
          apiV2.updateParameter(id,req,restParameter)
        case eb:EmptyBox=>
          toJsonError(None, JString("No Json data sent"))
      }
    }

    case Post(id:: Nil, req) => {
      val restParameter = restExtractor.extractParameter(req.params)
      apiV2.updateParameter(id,req,restParameter)
    }



  }

}
