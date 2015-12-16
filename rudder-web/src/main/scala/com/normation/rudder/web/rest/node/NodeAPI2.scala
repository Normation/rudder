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
import net.liftweb.common.Box
import net.liftweb.common.Loggable
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import net.liftweb.http.rest.RestHelper
import com.normation.rudder.web.rest.RestExtractorService
import com.normation.rudder.web.rest.RestUtils._
import net.liftweb.common._
import net.liftweb.json.JsonDSL._
import com.normation.rudder.web.rest.ApiVersion


case class NodeAPI2 (
    apiV2         : NodeApiService2
  , restExtractor : RestExtractorService
) extends NodeAPI with Loggable{


  override def requestDispatch(apiVersion: ApiVersion) : PartialFunction[Req, () => Box[LiftResponse]] = {

    case Get(Nil, req) => apiV2.listAcceptedNodes(req)


    case Get("pending" :: Nil, req) => apiV2.listPendingNodes(req)

    case Get(id :: Nil, req) => apiV2.acceptedNodeDetails(req, NodeId(id))

    case Get("pending" :: id :: Nil, req) => {
      val prettify = restExtractor.extractPrettify(req.params)
      apiV2.pendingNodeDetails(NodeId(id),prettify)
    }

    case Delete(id :: Nil, req) =>  apiV2.deleteNode(req, Seq(NodeId(id)))

    case "pending" :: Nil JsonPost body -> req => {
      req.json match {
        case Full(json) =>
          val prettify = restExtractor.extractPrettify(req.params)
          val nodeIds  = restExtractor.extractNodeIdsFromJson(json)
          val nodeStatus = restExtractor.extractNodeStatusFromJson(json)
          val actor = getActor(req)
          apiV2.changeNodeStatus(nodeIds,nodeStatus,actor,prettify)
        case eb:EmptyBox =>
          toJsonError(None, "No Json data sent")("updateGroup",restExtractor.extractPrettify(req.params))
      }
    }

    case "pending" :: id :: Nil JsonPost body -> req => {
      req.json match {
        case Full(json) =>
          val prettify = restExtractor.extractPrettify(req.params)
          val nodeId  = Full(Some(List(NodeId(id))))
          val nodeStatus = restExtractor.extractNodeStatusFromJson(json)
          val actor = getActor(req)
          apiV2.changeNodeStatus(nodeId,nodeStatus,actor,prettify)
        case eb:EmptyBox =>
          toJsonError(None, "No Json data sent")("updateGroup",restExtractor.extractPrettify(req.params))
      }
    }

    case Post("pending" :: Nil, req) => {
      val prettify = restExtractor.extractPrettify(req.params)
      val nodeIds  = restExtractor.extractNodeIds(req.params)
      val nodeStatus = restExtractor.extractNodeStatus(req.params)
      val actor = getActor(req)
      apiV2.changeNodeStatus(nodeIds,nodeStatus,actor,prettify)
      }

    case Post("pending" :: id :: Nil, req) => {
      val prettify = restExtractor.extractPrettify(req.params)
      val nodeId  = Full(Some(List(NodeId(id))))
      val nodeStatus = restExtractor.extractNodeStatus(req.params)
      val actor = getActor(req)
      apiV2.changeNodeStatus(nodeId,nodeStatus,actor,prettify)
      }

  }

}
