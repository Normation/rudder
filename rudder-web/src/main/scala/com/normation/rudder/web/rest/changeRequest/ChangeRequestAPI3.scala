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

package com.normation.rudder.web.rest.changeRequest

import com.normation.rudder.repository.RoChangeRequestRepository
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
import com.normation.rudder.domain.workflows.ChangeRequestId

class ChangeRequestAPI3 (
    restExtractor : RestExtractorService
  , apiV3       : ChangeRequestAPIService3
) extends RestHelper with ChangeRequestAPI with Loggable{


  val requestDispatch : PartialFunction[Req, () => Box[LiftResponse]] = {

    case Get(Nil | List(""),req) =>
      restExtractor.extractWorkflowStatus(req.params) match {
        case Full(status) =>
          apiV3.listChangeRequests(req,status)
        case eb : EmptyBox =>
          toJsonError(None, JString("No parameter 'status' sent"))("listChangeRequests",restExtractor.extractPrettify(req.params))
      }

    case Get(List(id), req) =>
      try {
        apiV3.changeRequestDetails(ChangeRequestId(id.toInt), req)
      } catch {
        case e : Exception =>
          toJsonError(None, JString(s"${id} is not a valid change request id (need to be an integer)"))("changeRequestDetails",restExtractor.extractPrettify(req.params))
      }


    case Delete(id :: Nil, req) =>
      try {
        apiV3.declineChangeRequest(ChangeRequestId(id.toInt), req)
      } catch {
        case e : Exception =>
          toJsonError(None, JString(s"${id} is not a valid change request id (need to be an integer)"))("declineChangeRequest",restExtractor.extractPrettify(req.params))
      }

    case Post(id :: "accept" :: Nil, req) =>

      restExtractor.extractWorkflowTargetStatus(req.params) match {
        case Full(target) =>
          try {
            apiV3.acceptChangeRequest(ChangeRequestId(id.toInt), target, req)
          } catch {
            case e : Exception =>
              toJsonError(None, JString(s"${id} is not a valid change request id (need to be an integer)"))("acceptChangeRequest",restExtractor.extractPrettify(req.params))
          }

        case eb : EmptyBox =>
          val fail = eb ?~ "Not valid 'status' parameter sent"
          val message=  s"Could not accept ChangeRequest ${id} details cause is: ${fail.messageChain}."
          toJsonError(None, JString(message))("acceptChangeRequest",restExtractor.extractPrettify(req.params))
      }

    case Post(id :: Nil, req) => {
      restExtractor.extractChangeRequestInfo(req.params) match {
        case Full(info) =>
          apiV3.updateChangeRequest(ChangeRequestId(id.toInt), info, req)
        case eb : EmptyBox =>
          val fail = eb ?~!(s"No parameters sent to update change request" )
          val message=  s"Could not update ChangeRequest ${id} details cause is: ${fail.messageChain}."
          toJsonError(None, JString(message))("updateChangeRequest",restExtractor.extractPrettify(req.params))
      }
    }
  }
  serve( "api" / "3" / "changeRequests" prefix requestDispatch)

}
