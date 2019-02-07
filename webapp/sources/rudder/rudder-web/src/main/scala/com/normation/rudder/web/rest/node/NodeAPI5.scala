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

package com.normation.rudder.web.rest.node

import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.nodes.Node
import com.normation.rudder.web.rest.ApiVersion
import com.normation.rudder.web.rest.RestDataSerializer
import com.normation.rudder.web.rest.RestExtractorService
import com.normation.rudder.web.rest.RestUtils
import com.normation.rudder.web.rest.RestUtils._

import net.liftweb.common._
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import net.liftweb.http.rest.RestHelper
import net.liftweb.json.JsonDSL._

class NodeAPI5 (
    apiV4        : NodeAPI4
  , apiV8service : NodeApiService8
  , extractor    : RestExtractorService
  , serializer   : RestDataSerializer
) extends RestHelper with NodeAPI with Loggable{

  private[this] def serialize(node : Node) = {
    serializer.serializeNode(node)
  }

  val v5Dispatch : PartialFunction[Req, () => Box[LiftResponse]] = {

    /*
     * The two following function were in NodeAPI8 before, but we choose to make
     * the code dryer by moving them in place of previous NodeAPI dedicated code.
     *
     *  It is the reason for using NodeApiService8 here.
     *
     * The behaviour DOES change because now, NodeAPI v5 knows about policyMode,
     * but this not a problem because it does not change at all behaviour of
     * pre-existing script using that API version exactly.
     */

    case id :: Nil JsonPost body -> req => {
      implicit val prettify = extractor.extractPrettify(req.params)
      implicit val action = "updateNode"
      val actor = RestUtils.getActor(req)

      (for {
        restNode <- extractor.extractNodeFromJSON(body)
        reason   <- extractor.extractReason(req)
        result   <- apiV8service.updateRestNode(NodeId(id), restNode, actor, reason)
      } yield {
        toJsonResponse(Some(id), serialize(result))
      }) match {
        case Full(response) =>
          response
        case eb : EmptyBox =>
          val fail = eb ?~! s"An error occured while updating Node '${id}'"
          logger.error(fail.messageChain)
          toJsonError(Some(id), fail.messageChain)
      }
    }

   case Post(id :: Nil, req) => {
      implicit val prettify = extractor.extractPrettify(req.params)
      implicit val action = "updateNode"
      val actor = RestUtils.getActor(req)
      (for {
        restNode <- extractor.extractNode(req.params)
        reason   <- extractor.extractReason(req)
        result   <- apiV8service.updateRestNode(NodeId(id), restNode, actor, reason)
      } yield {
        toJsonResponse(Some(id), serialize(result))
      }) match {
        case Full(response) =>
          response
        case eb : EmptyBox =>
          val fail = eb ?~! s"An error occured while updating Node '${id}'"
          toJsonError(Some(id), fail.messageChain)
      }
    }

  }

  // Node API Version 5 fallback to Node API v4 if request is not handled in V5
  override def requestDispatch(apiVersion: ApiVersion) : PartialFunction[Req, () => Box[LiftResponse]] = {
    v5Dispatch orElse apiV4.requestDispatch(apiVersion)
  }
}
