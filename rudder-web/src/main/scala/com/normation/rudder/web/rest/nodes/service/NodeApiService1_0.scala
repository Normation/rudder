package com.normation.rudder.web.rest.nodes.service

import com.normation.rudder.services.servers.NewNodeManager
import com.normation.rudder.services.nodes.NodeInfoService
import com.normation.rudder.services.servers.RemoveNodeService
import com.normation.utils.StringUuidGenerator
import com.normation.rudder.batch.AsyncDeploymentAgent
import net.liftweb.common.Loggable
import com.normation.rudder.domain.nodes.NodeInfo
import net.liftweb.json.JValue
import net.liftweb.http.Req
import com.normation.rudder.web.rest.RestUtils._
import com.normation.rudder.web.services.rest.RestExtractorService
import net.liftweb.common.Full
import net.liftweb.common.EmptyBox
import net.liftweb.json.JsonDSL._
import net.liftweb.json.JArray
import com.normation.rudder.web.rest.RestError
import com.normation.rudder.domain.servers.Srv


class NodeApiService1_0 (
    newNodeManager  : NewNodeManager
  , nodeInfoService : NodeInfoService
  , removeNodeService: RemoveNodeService
  , uuidGen           : StringUuidGenerator
  , asyncDeploymentAgent : AsyncDeploymentAgent
  , restExtractor        : RestExtractorService
) extends Loggable {

  def listAcceptedNodes (req : Req) = {
    implicit val prettify = restExtractor.extractPrettify(req.params)
        implicit val action = "listAcceptedNodes"
      nodeInfoService.getAllIds match {
        case Full(ids) =>
          val acceptedNodes = ids.map(nodeInfoService.getNodeInfo(_).map(toJSON(_,"accepted"))).toList
          val optFailure = acceptedNodes.collectFirst{case eb:EmptyBox => Some(eb ?~ " could not get Node informations")}
          optFailure match {
            case Some(fail) => fail
            case None       =>
              val jsonNodes = acceptedNodes.collect{ case Full(jsonNode) => jsonNode}
              toJsonResponse("N/A", ( "nodes" -> JArray(jsonNodes)))
          }

        case eb: EmptyBox => val message = (eb ?~ ("Could not fetch accepted Nodes")).msg
          toJsonResponse("N/A", message, RestError)
      }
  }

  def listPendingNodes (req : Req) = {
    implicit val prettify = restExtractor.extractPrettify(req.params)
    implicit val action = "listPendingNodes"
    newNodeManager.listNewNodes match {
      case Full(ids) =>
        val pendingNodes = ids.map(toJSON(_,"pending")).toList
        toJsonResponse("N/A", ( "nodes" -> JArray(pendingNodes)))

      case eb: EmptyBox => val message = (eb ?~ ("Could not fetch pending Nodes")).msg
        toJsonResponse("N/A", message, RestError)
    }
  }

  def changePendingNodeStatus (req : Req) = {
    implicit val prettify = restExtractor.extractPrettify(req.params)
    implicit val action = "changePendingNodeStatus"
    newNodeManager.listNewNodes match {
      case Full(ids) =>
        val pendingNodes = ids.map(toJSON(_,"pending")).toList
        toJsonResponse("N/A", ( "nodes" -> JArray(pendingNodes)))

      case eb: EmptyBox => val message = (eb ?~ ("Could not fetch pending Nodes")).msg
        toJsonResponse("N/A", message, RestError)
    }
  }

  def toJSON (node : NodeInfo, status : String) : JValue ={

    ("id"          -> node.id.value) ~
    ("status"      -> status) ~
    ("hostname"    -> node.hostname) ~
    ("osName"      -> node.osName) ~
    ("osVersion"   -> node.osVersion) ~
    ("machyneType" -> node.machineType)

  }

  def toJSON (node : Srv, status : String) : JValue ={

    ("id"          -> node.id.value) ~
    ("status"      -> status) ~
    ("hostname"    -> node.hostname) ~
    ("osName"      -> node.osName)

  }
}