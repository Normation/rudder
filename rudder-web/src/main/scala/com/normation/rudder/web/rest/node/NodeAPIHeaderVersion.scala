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

package com.normation.rudder.web.rest.node

import com.normation.inventory.domain.NodeId
import com.normation.rudder.web.rest.RestUtils.notValidVersionResponse
import com.normation.rudder.web.rest.rule.RuleAPI

import net.liftweb.common.Box
import net.liftweb.common.Full
import net.liftweb.common.Loggable
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import net.liftweb.http.rest.RestHelper

class NodeAPIHeaderVersion (
    apiV1_0              : NodeApiService1_0
) extends RestHelper with RuleAPI with Loggable{


  val requestDispatch : PartialFunction[Req, () => Box[LiftResponse]] = {

    case Get(Nil, req) => {
      req.header("X-API-VERSION") match {
        case Full("1.0") =>  apiV1_0.listAcceptedNodes(req)
        case _ => notValidVersionResponse("listAcceptedNodes")
      }
    }

    case Get("pending" :: Nil, req) => {
      req.header("X-API-VERSION") match {
        case Full("1.0") =>  apiV1_0.listPendingNodes(req)
        case _ => notValidVersionResponse("listPendingNodes")
      }
    }


    case Get(id :: Nil, req) => {
      req.header("X-API-VERSION") match {
        case Full("1.0") => apiV1_0.acceptedNodeDetails(req, NodeId(id))
        case _ => notValidVersionResponse("acceptedNodeDetails")
      }
    }

    case Delete(id :: Nil, req) => {
      req.header("X-API-VERSION") match {
        case Full("1.0") => apiV1_0.deleteNode(req, Seq(NodeId(id)))
        case _ => notValidVersionResponse("deleteNode")
      }
    }

     case Post("pending" :: Nil, req) =>  {
      req.header("X-API-VERSION") match {
        case Full("1.0") =>
          apiV1_0.changeNodeStatus(req)
        case _ => notValidVersionResponse("changeNodeStatus")
      }
    }
  }

  serve( "api" / "nodes" prefix requestDispatch)

}
