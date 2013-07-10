package com.normation.rudder.web.rest.node

import com.normation.rudder.web.services.rest.RestExtractorService
import com.normation.rudder.web.rest.node.service.NodeApiService1_0
import com.normation.rudder.web.rest.RestUtils._
import net.liftweb.http.Req
import net.liftweb.http.rest.RestHelper
import net.liftweb.common._
import net.liftweb.http.LiftResponse
import com.normation.rudder.web.rest.RestError
import com.normation.inventory.domain.NodeId

class NodeAPI1_0 (
  apiV1_0              : NodeApiService1_0
) extends RestHelper with NodeAPI with Loggable{


  val requestDispatch : PartialFunction[Req, () => Box[LiftResponse]] = {

    case Get(Nil, req) => apiV1_0.listAcceptedNodes(req)


    case Get("pending" :: Nil, req) => apiV1_0.listPendingNodes(req)

    case Get(id :: Nil, req) => apiV1_0.acceptedNodeDetails(req, NodeId(id))

    case Delete(id :: Nil, req) =>  apiV1_0.deleteNode(req, Seq(NodeId(id)))

    case Post("pending" :: Nil, req) => {
      apiV1_0.changeNodeStatus(req)
    }

    case content => println(content)
         toJsonResponse("nothing", "rien", RestError)("error",true)

  }
  serve( "api" / "1.0" / "nodes" prefix requestDispatch)

}
