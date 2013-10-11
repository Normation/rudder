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

package com.normation.rudder.web.rest.group

import com.normation.rudder.repository.RoNodeGroupRepository
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
import net.liftweb.json.JsonDSL._

class GroupAPIHeaderVersion (
    readGroup     : RoNodeGroupRepository
  , restExtractor : RestExtractorService
  , apiV2         : GroupApiService2
) extends RestHelper with Loggable{

  private[this] implicit val availableVersions = List(2,3)

  val requestDispatch : PartialFunction[Req, () => Box[LiftResponse]] = {

    case Get(Nil, req) => {
      ApiVersion.fromRequest(req) match {
        case Full(ApiVersion(2 | 3)) =>  apiV2.listGroups(req)
        case Full(ApiVersion(missingVersion)) => missingResponse(missingVersion,"listAcceptedNodes")
        case _ => notValidVersionResponse("listGroups")
      }
    }

    case Nil JsonPut body -> req => {
      ApiVersion.fromRequest(req) match {
        case Full(ApiVersion(2 | 3)) =>
          req.json match {
            case Full(arg) =>
              val restGroup = restExtractor.extractGroupFromJSON(arg)
              apiV2.createGroup(restGroup, req)
            case eb:EmptyBox=>
              toJsonError(None, "No Json data sent")("createGroup",restExtractor.extractPrettify(req.params))
          }
        case Full(ApiVersion(missingVersion)) => missingResponse(missingVersion,"listAcceptedNodes")
        case _ => notValidVersionResponse("createGroup")
      }
    }

    case Put(Nil, req) => {
      ApiVersion.fromRequest(req) match {
        case Full(ApiVersion(2 | 3)) =>
          val restGroup = restExtractor.extractGroup(req.params)
          apiV2.createGroup(restGroup, req)
        case Full(ApiVersion(missingVersion)) => missingResponse(missingVersion,"listAcceptedNodes")
        case _ => notValidVersionResponse("createGroup")
      }
    }

    case Get(id :: Nil, req) => {
      ApiVersion.fromRequest(req) match {
        case Full(ApiVersion(2 | 3)) =>  apiV2.groupDetails(id, req)
        case Full(ApiVersion(missingVersion)) => missingResponse(missingVersion,"listAcceptedNodes")
        case _ => notValidVersionResponse("groupDetails")
      }
    }

    case Delete(id :: Nil, req) => {
      ApiVersion.fromRequest(req) match {
        case Full(ApiVersion(2 | 3)) =>  apiV2.deleteGroup(id,req)
        case Full(ApiVersion(missingVersion)) => missingResponse(missingVersion,"listAcceptedNodes")
        case _ => notValidVersionResponse("deleteGroup")
      }
    }

    case id :: Nil JsonPost body -> req => {
      ApiVersion.fromRequest(req) match {
        case Full(ApiVersion(2 | 3)) =>
          req.json match {
            case Full(arg) =>
              val restGroup = restExtractor.extractGroupFromJSON(arg)
              apiV2.updateGroup(id,req,restGroup)
            case eb:EmptyBox=>
              toJsonError(None, "No Json data sent")("updateGroup",restExtractor.extractPrettify(req.params))
          }
        case Full(ApiVersion(missingVersion)) => missingResponse(missingVersion,"listAcceptedNodes")
        case _ => notValidVersionResponse("updateGroup")
      }
    }

    case Post(id:: Nil, req) => {
      ApiVersion.fromRequest(req) match {
        case Full(ApiVersion(2 | 3)) =>
          val restGroup = restExtractor.extractGroup(req.params)
          apiV2.updateGroup(id,req,restGroup)
        case Full(ApiVersion(missingVersion)) =>
          missingResponse(missingVersion,"listAcceptedNodes")
        case _ => notValidVersionResponse("updateGroup")
      }
    }
    case Post( id :: "reload" ::  Nil, req) => {
      ApiVersion.fromRequest(req) match {
        case Full(ApiVersion(2 | 3)) =>
          apiV2.reloadGroup(id, req)
        case Full(ApiVersion(missingVersion)) =>
          missingResponse(missingVersion,"listAcceptedNodes")
        case _ =>
          notValidVersionResponse("reloadGroup")
      }
    }
  }
  serve( "api" / "groups" prefix requestDispatch)

}
