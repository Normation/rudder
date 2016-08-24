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

package com.normation.rudder.web.rest.group

import com.normation.rudder.repository.RoNodeGroupRepository
import com.normation.rudder.web.rest.RestExtractorService
import com.normation.rudder.web.rest.RestUtils._
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import net.liftweb.json.JsonDSL._
import net.liftweb.http.rest.RestHelper
import net.liftweb.common._
import com.normation.rudder.web.rest.ApiVersion

class GroupAPI2 (
    readGroup     : RoNodeGroupRepository
  , restExtractor : RestExtractorService
  , apiV2         : GroupApiService2
) extends RestHelper with GroupAPI with Loggable{


  override def requestDispatch(apiVersion : ApiVersion): PartialFunction[Req, () => Box[LiftResponse]] = {

    case Get(Nil, req) => apiV2.listGroups(req, apiVersion)

    case Nil JsonPut body -> req => {
      req.json match {
        case Full(arg) =>
          val restGroup = restExtractor.extractGroupFromJSON(arg)
          apiV2.createGroup(restGroup, req, apiVersion)
        case eb:EmptyBox=>
          toJsonError(None, "No Json data sent")("createGroup",restExtractor.extractPrettify(req.params))
      }
    }

    case Put(Nil, req) => {
      val restGroup = restExtractor.extractGroup(req.params)
      apiV2.createGroup(restGroup, req, apiVersion)
    }

    case Get(id :: Nil, req) => apiV2.groupDetails(id, req, apiVersion)

    case Delete(id :: Nil, req) =>  apiV2.deleteGroup(id,req, apiVersion)

    case id :: Nil JsonPost body -> req => {
      req.json match {
        case Full(arg) =>
          val restGroup = restExtractor.extractGroupFromJSON(arg)
          apiV2.updateGroup(id,req,restGroup, apiVersion)
        case eb:EmptyBox=>
          toJsonError(None, "No Json data sent")("updateGroup",restExtractor.extractPrettify(req.params))
      }
    }

    case Post(id:: Nil, req) => {
      val restGroup = restExtractor.extractGroup(req.params)
      apiV2.updateGroup(id,req,restGroup,apiVersion)
    }

    case Post( id :: "reload" ::  Nil, req) => {
      apiV2.reloadGroup(id, req, apiVersion)
    }

  }


}
