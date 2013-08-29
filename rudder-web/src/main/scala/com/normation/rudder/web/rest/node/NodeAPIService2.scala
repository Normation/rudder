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

import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.domain.servers.Srv
import com.normation.rudder.services.nodes.NodeInfoService
import com.normation.rudder.services.servers.NewNodeManager
import com.normation.rudder.services.servers.RemoveNodeService
import com.normation.rudder.web.rest.RestUtils.getActor
import com.normation.rudder.web.rest.RestUtils.toJsonError
import com.normation.rudder.web.rest.RestUtils.toJsonResponse
import com.normation.rudder.web.rest.RestExtractorService
import com.normation.utils.Control._
import com.normation.utils.StringUuidGenerator
import net.liftweb.common.Box.box2Iterable
import net.liftweb.common.EmptyBox
import net.liftweb.common.Full
import net.liftweb.common.Loggable
import net.liftweb.http.Req
import net.liftweb.json.JArray
import net.liftweb.json.JValue
import net.liftweb.json.JsonDSL.jobject2assoc
import net.liftweb.json.JsonDSL.pair2Assoc
import net.liftweb.json.JsonDSL.pair2jvalue
import net.liftweb.json.JsonDSL.string2jvalue
import net.liftweb.common.Box
import net.liftweb.json.JValue
import com.normation.inventory.domain.FullInventory
import com.normation.inventory.domain.NodeInventory
import com.normation.inventory.domain.MachineType
import com.normation.inventory.domain.PhysicalMachineType
import com.normation.inventory.domain.VirtualMachineType


class NodeApiService2 (
    newNodeManager  : NewNodeManager
  , nodeInfoService : NodeInfoService
  , removeNodeService: RemoveNodeService
  , uuidGen           : StringUuidGenerator
  , restExtractor        : RestExtractorService
) extends Loggable {

  def listAcceptedNodes (req : Req) = {
    implicit val prettify = restExtractor.extractPrettify(req.params)
    implicit val action = "listAcceptedNodes"
      nodeInfoService.getAll match {
        case Full(nodes) =>
          val acceptedNodes = nodes.map(toJSON(_,"accepted"))
          toJsonResponse(None, ( "nodes" -> JArray(acceptedNodes.toList)))

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
        val message = (eb ?~ s"Could not find accepted Node ${id.value}").msg
        toJsonError(None, message)
    }
  }


  def pendingNodeDetails (nodeId : NodeId, prettifyStatus : Boolean) =  {
    implicit val prettify = prettifyStatus
    implicit val action = "pendingNodeDetails"
    newNodeManager.listNewNodes match {
      case Full(pendingNodes) =>
        pendingNodes.filter(_.id==nodeId) match {
          case Seq() =>
            val message = s"Could not find pending Node ${nodeId.value}"
            toJsonError(None, message)
          case Seq(info) =>
            val node =  toJSON(info,"pending")
            toJsonResponse(None, ( "nodes" -> JArray(List(node))))
          case tooManyNodes =>
            val message = s"Too many pending Nodes with same id ${nodeId.value} : ${tooManyNodes.size} "
            toJsonError(None, message)
        }
      case eb : EmptyBox =>
        val message = (eb ?~ s"Could not find pending Node ${nodeId.value}").msg
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
        val deletedNodes = jsonValues
        toJsonResponse(None, ( "nodes" -> JArray(deletedNodes)))

      case eb: EmptyBox => val message = (eb ?~ ("Error when deleting Nodes")).msg
        toJsonError(None, message)
    }
  }

  def modifyStatusFromAction(ids : Seq[NodeId], action : NodeStatusAction,modId : ModificationId, actor:EventActor) : Box[List[JValue]] = {
    def actualNodeDeletion(id : NodeId, modId : ModificationId, actor:EventActor) = {
      for {
        info   <- nodeInfoService.getNodeInfo(id)
        remove <- removeNodeService.removeNode(info.id, modId, actor)
      } yield { toJSON(info,"deleted") }
    }

   boxSequence(action match {
      case AcceptNode =>
        ids.map(newNodeManager.accept(_, modId, actor).map(toJSON(_,"accepted")))

      case RefuseNode =>
        ids.map(newNodeManager.refuse(_, modId, actor)).map(_.map(toJSON(_,"refused")))

      case DeleteNode =>
        ids.map(actualNodeDeletion(_,modId,actor))
    }).map(_.toList)
  }

  def changeNodeStatus (
      nodeIds :Box[Option[List[NodeId]]]
    , nodeStatusAction : Box[NodeStatusAction]
    , actor : EventActor
    , prettifyStatus : Boolean
  ) = {
    implicit val prettify = prettifyStatus
    implicit val action = "changePendingNodeStatus"
    val modId = ModificationId(uuidGen.newUuid)
    nodeIds match {
      case Full(Some(ids)) =>
        logger.debug(s" Nodes to change Status : ${ids.mkString("[ ", ", ", " ]")}")
        nodeStatusAction match {
          case Full(nodeStatusAction  ) =>
            modifyStatusFromAction(ids,nodeStatusAction,modId,actor) match {
              case Full(result) =>
                toJsonResponse(None, ( "nodes" -> JArray(result)))
              case eb: EmptyBox =>
                val message = (eb ?~ ("Error when changing Nodes status")).msg
                toJsonError(None, message)
            }

          case eb:EmptyBox =>
            val fail = eb ?~ "node status needs to be specified"
            toJsonError(None, fail.msg)
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

  def toJSON (node : FullInventory, status : String) : JValue ={

    val machineType = node.machine.map(_.machineType match {
      case PhysicalMachineType => "Physical"
      case VirtualMachineType(kind) => "Virtual"
    }).getOrElse("No machine Inventory")


    ("id"          -> node.node.main.id.value) ~
    ("status"      -> status) ~
    ("hostname"    -> node.node.main.hostname) ~
    ("osName"      -> node.node.main.osDetails.os.name) ~
    ("osVersion"   -> node.node.main.osDetails.version.toString) ~
    ("machyneType" -> machineType)

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