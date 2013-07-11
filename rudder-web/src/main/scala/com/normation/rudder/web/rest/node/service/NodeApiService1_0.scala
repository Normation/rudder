package com.normation.rudder.web.rest.node.service

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
import com.normation.inventory.domain.NodeId
import com.normation.eventlog.ModificationId
import com.normation.utils.Control._
import net.liftweb.common.Box
import com.normation.eventlog.EventActor
import net.liftweb.common.Failure


class NodeApiService1_0 (
    newNodeManager  : NewNodeManager
  , nodeInfoService : NodeInfoService
  , removeNodeService: RemoveNodeService
  , uuidGen           : StringUuidGenerator
  , restExtractor        : RestExtractorService
) extends Loggable {

  def listAcceptedNodes (req : Req) = {
    implicit val prettify = restExtractor.extractPrettify(req.params)
    implicit val action = "listAcceptedNodes"
      nodeInfoService.getAllIds match {
        case Full(ids) =>
          boxSequence(ids.map(nodeInfoService.getNodeInfo(_).map(toJSON(_,"accepted")))) match {
            case Full(acceptedNodes)=>
              toJsonResponse(None, ( "nodes" -> JArray(acceptedNodes.toList)))
            case eb:EmptyBox =>
              val message = (eb ?~ ("Could not fetch accepted Nodes")).msg
              toJsonError(None, message)
          }

        case eb: EmptyBox => val message = (eb ?~ ("Could not fetch accepted Nodes")).msg
          toJsonError(None, message)
      }
  }


  def acceptedNodeDetails (req : Req, id :NodeId ) = {
    implicit val prettify = restExtractor.extractPrettify(req.params)
    implicit val action = "acceptedNodeDetails"
      nodeInfoService.getNodeInfo(id) match {
        case Full(info) =>
          val node =  toJSON(info,"accepted")
          toJsonResponse(None, ( "nodes" -> JArray(List(node))))
        case eb:EmptyBox =>
          val message = (eb ?~ s"Could not fetch Node ${id.value}").msg
          toJsonError(None, message)
    }
  }

  def listPendingNodes (req : Req) = {
    implicit val prettify = restExtractor.extractPrettify(req.params)
    implicit val action = "listPendingNodes"
    newNodeManager.listNewNodes match {
      case Full(ids) =>
        val pendingNodes = ids.map(toJSON(_,"pending")).toList
        toJsonResponse(None, ( "nodes" -> JArray(pendingNodes)))

      case eb: EmptyBox => val message = (eb ?~ ("Could not fetch pending Nodes")).msg
        toJsonError(None, message)
    }
  }


  def deleteNode(req : Req, ids: Seq[NodeId]) = {
    implicit val prettify = restExtractor.extractPrettify(req.params)
    implicit val action = "changePendingNodeStatus"
    val modId = ModificationId(uuidGen.newUuid)
    val actor = getActor(req)
    modifyStatusFromAction(ids,DeleteNode,modId,actor) match {
      case Full(jsonValues) =>
        val deletedNodes = jsonValues.toList
        toJsonResponse(None, ( "nodes" -> JArray(deletedNodes)))

      case eb: EmptyBox => val message = (eb ?~ ("Error when deleting Nodes")).msg
        toJsonError(None, message)
    }
  }

  def modifyStatusFromAction(ids : Seq[NodeId], action : NodeStatusAction,modId : ModificationId, actor:EventActor) = {
    def actualNodeDeletion(id : NodeId, modId : ModificationId, actor:EventActor) = {
      for {
        info   <- nodeInfoService.getNodeInfo(id)
        remove <- removeNodeService.removeNode(info.id, modId, actor)
      } yield { toJSON(info,"deleted") }
    }

   boxSequence(action match {
      case AcceptNode =>
        newNodeManager.accept(ids, modId, actor).flatMap(box =>  box.map((nodeInfoService.getNodeInfo(_).map(toJSON(_,"accepted")))))

      case RefuseNode =>
        newNodeManager.refuse(ids, modId, actor).map(_.map(toJSON(_,"refused")))

      case DeleteNode =>
        ids.map(actualNodeDeletion(_,modId,actor))
    })
  }

  def changeNodeStatus (req : Req) = {
    implicit val prettify = restExtractor.extractPrettify(req.params)
    implicit val action = "changePendingNodeStatus"
    val modId = ModificationId(uuidGen.newUuid)
    val actor = getActor(req)
    restExtractor.extractNodeIds(req.params) match {
      case Full(Some(ids)) =>
        logger.info(ids)
        val jsonValues = restExtractor.extractNodeStatus(req.params) match {
          case Full(nodeStatusAction  ) => modifyStatusFromAction(ids,nodeStatusAction,modId,actor)
          case eb:EmptyBox => eb ?~ "node status needs to be specified"
        }
        jsonValues match {
        case Full(jsonValue) =>
          val updatedNodes = jsonValue.toList
          toJsonResponse(None, ( "nodes" -> JArray(updatedNodes)))

        case eb: EmptyBox => val message = (eb ?~ ("Error when changing Nodes status")).msg
        toJsonError(None, message)
        }
      case Full(None) =>
        val message = "You must add a node id as target"
        toJsonError(None, message)
      case eb: EmptyBox => val message = (eb ?~ ("Error when extracting Nodes' id")).msg
        toJsonError(None, message)
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

sealed trait NodeStatusAction

case object AcceptNode extends NodeStatusAction

case object RefuseNode extends NodeStatusAction

case object DeleteNode extends NodeStatusAction