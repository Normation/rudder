package com.normation.rudder.web.rest.node

import com.normation.rudder.web.services.rest.RestExtractorService
import com.normation.rudder.web.rest.RestUtils._
import net.liftweb.http.Req
import net.liftweb.http.rest.RestHelper
import net.liftweb.common._
import net.liftweb.http.LiftResponse
import com.normation.rudder.web.rest.RestError
import com.normation.rudder.web.rest.rule.RuleAPI
import com.normation.rudder.web.rest.node.service.NodeApiService1_0
import com.normation.inventory.domain.NodeId

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
        case _ => notValidVersionResponse("listRules")
      }
    }
  }

  serve( "api" / "nodes" prefix requestDispatch)

}
