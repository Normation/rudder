/*
*************************************************************************************
* Copyright 2017 Normation SAS
*************************************************************************************

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

package com.normation.rudder.rest.lift

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.charset.StandardCharsets
import java.util.Arrays

import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.inventory.domain.NodeId
import com.normation.inventory.domain._
import com.normation.inventory.ldap.core.LDAPFullInventoryRepository
import com.normation.inventory.services.core.ReadOnlySoftwareDAO
import com.normation.rudder.UserService
import com.normation.rudder.batch.AsyncDeploymentActor
import com.normation.rudder.batch.AutomaticStartDeployment
import com.normation.rudder.domain.nodes.CompareProperties
import com.normation.rudder.domain.nodes.Node
import com.normation.rudder.domain.queries.Query
import com.normation.rudder.reports.execution.RoReportsExecutionRepository
import com.normation.rudder.repository.WoNodeRepository
import com.normation.rudder.rest.ApiPath
import com.normation.rudder.rest.ApiVersion
import com.normation.rudder.rest.AuthzToken
import com.normation.rudder.rest.RestDataSerializer
import com.normation.rudder.rest.RestExtractorService
import com.normation.rudder.rest.RestUtils.getActor
import com.normation.rudder.rest.RestUtils.toJsonError
import com.normation.rudder.rest.RestUtils.toJsonResponse
import com.normation.rudder.rest.data._
import com.normation.rudder.rest.{NodeApi => API}
import com.normation.rudder.services.nodes.NodeInfoService
import com.normation.rudder.services.queries._
import com.normation.rudder.services.servers.NewNodeManager
import com.normation.rudder.services.servers.RemoveNodeService
import com.normation.utils.Control._
import com.normation.utils.StringUuidGenerator
import monix.eval.Task
import net.liftweb.common.Box
import net.liftweb.common.EmptyBox
import net.liftweb.common.Failure
import net.liftweb.common.Full
import net.liftweb.common.Loggable
import net.liftweb.http.LiftResponse
import net.liftweb.http.OutputStreamResponse
import net.liftweb.http.Req
import net.liftweb.json.JArray
import net.liftweb.json.JValue
import net.liftweb.json.JsonDSL._
import net.liftweb.json.JsonDSL.pair2jvalue
import net.liftweb.json.JsonDSL.string2jvalue
import scalaj.http.Http
import scalaj.http.HttpConstants
import scalaj.http.HttpOptions

import com.normation.box._

/*
 * NodeApi implementation.
 *
 * This must be reworked to note use a "nodeApiService",
 * but make the implementation directly here.
 */
class NodeApi (
    restExtractorService: RestExtractorService
  , serializer          : RestDataSerializer
  , apiV2               : NodeApiService2
  , apiV4               : NodeApiService4
  , serviceV6           : NodeApiService6
  , apiV8service        : NodeApiService8
) extends LiftApiModuleProvider[API] {

  def schemas = API

  def getLiftEndpoints(): List[LiftApiModule] = {
    API.endpoints.map(e => e match {
      case API.NodeDetails              => NodeDetails
      case API.PendingNodeDetails       => PendingNodeDetails
      case API.DeleteNode               => DeleteNode
      case API.UpdateNode               => UpdateNode
      case API.ChangePendingNodeStatus  => ChangePendingNodeStatus
      case API.ChangePendingNodeStatus2 => ChangePendingNodeStatus2
      case API.ListAcceptedNodes        => ListAcceptedNodes
      case API.ListPendingNodes         => ListPendingNodes
      case API.ApplyPocicyAllNodes      => ApplyPocicyAllNodes
      case API.ApplyPolicy              => ApplyPolicy
    })
  }

  object NodeDetails extends LiftApiModule {
    val schema = API.NodeDetails
    val restExtractor = restExtractorService
    def process(version: ApiVersion, path: ApiPath, id: String, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      restExtractor.extractNodeDetailLevel(req.params) match {
        case Full(level) =>
          apiV4.nodeDetailsGeneric(NodeId(id), level, version, req)
        case eb:EmptyBox =>
          val failMsg = eb ?~ "node detail level not correctly sent"
          toJsonError(None, failMsg.msg)("nodeDetail", params.prettify)
      }
    }
  }

  object PendingNodeDetails extends LiftApiModule {
    val schema = API.PendingNodeDetails
    val restExtractor = restExtractorService
    def process(version: ApiVersion, path: ApiPath, id: String, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      apiV2.pendingNodeDetails(NodeId(id), params.prettify)
    }
  }

  object DeleteNode extends LiftApiModule {
    val schema = API.DeleteNode
    val restExtractor = restExtractorService
    def process(version: ApiVersion, path: ApiPath, id: String, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      apiV2.deleteNode(req, Seq(NodeId(id)))
    }
  }

  object UpdateNode extends LiftApiModule {
    val schema = API.UpdateNode
    val restExtractor = restExtractorService
    def process(version: ApiVersion, path: ApiPath, id: String, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      implicit val prettify = params.prettify
      implicit val action = "updateNode"

      (for {
        restNode <- if(req.json_?) {
                      req.json.flatMap(body => restExtractor.extractNodeFromJSON(body))
                    } else {
                      restExtractor.extractNode(req.params)
                    }
        reason   <- restExtractor.extractReason(req)
        result   <- apiV8service.updateRestNode(NodeId(id), restNode, authzToken.actor, reason)
      } yield {
        toJsonResponse(Some(id), serializer.serializeNode(result))
      }) match {
        case Full(response) =>
          response
        case eb : EmptyBox =>
          val fail = eb ?~! s"An error occured while updating Node '${id}'"
          toJsonError(Some(id), fail.messageChain)
      }
    }
  }

  object ChangePendingNodeStatus extends LiftApiModule0 {
    val schema = API.ChangePendingNodeStatus
    val restExtractor = restExtractorService
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      implicit val prettify = params.prettify
      val (nodeIds, nodeStatus) = if(req.json_?) {
        req.json match {
          case Full(json) =>
            (restExtractor.extractNodeIdsFromJson(json)
            ,restExtractor.extractNodeStatusFromJson(json)
            )
          case eb:EmptyBox => (eb, eb)
        }
      } else {
        (restExtractor.extractNodeIds(req.params)
        ,restExtractor.extractNodeStatus(req.params)
        )
      }
      apiV2.changeNodeStatus(nodeIds, nodeStatus, authzToken.actor, prettify)
    }
  }

  object ChangePendingNodeStatus2 extends LiftApiModule {
    val schema = API.ChangePendingNodeStatus2
    val restExtractor = restExtractorService
    def process(version: ApiVersion, path: ApiPath, id: String, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      implicit val prettify = params.prettify
      val nodeStatus = if(req.json_?) {
        req.json match {
          case Full(json) =>
            restExtractor.extractNodeStatusFromJson(json)
          case eb:EmptyBox => eb
        }
      } else {
        restExtractor.extractNodeStatus(req.params)
      }
      apiV2.changeNodeStatus(Full(Some(List(NodeId(id)))), nodeStatus, authzToken.actor, prettify)
    }
  }

  object ListAcceptedNodes extends LiftApiModule0 {
    val schema = API.ListAcceptedNodes
    val restExtractor = restExtractorService
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      implicit val prettify = params.prettify
      restExtractor.extractNodeDetailLevel(req.params) match {
        case Full(level) =>
          restExtractor.extractQuery(req.params) match {
            case Full(None) =>
              serviceV6.listNodes(AcceptedInventory, level, None, version)
            case Full(Some(query)) =>
              serviceV6.queryNodes(query,AcceptedInventory, level, version)
            case eb:EmptyBox =>
              val failMsg = eb ?~ "Node query not correctly sent"
              toJsonError(None, failMsg.msg)("listAcceptedNodes",prettify)

          }
        case eb:EmptyBox =>
          val failMsg = eb ?~ "Node detail level not correctly sent"
          toJsonError(None, failMsg.msg)("listAcceptedNodes",prettify)
      }
    }
  }

  object ListPendingNodes extends LiftApiModule0 {
    val schema = API.ListPendingNodes
    val restExtractor = restExtractorService
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      implicit val prettify = params.prettify
      restExtractor.extractNodeDetailLevel(req.params) match {
        case Full(level) => serviceV6.listNodes(PendingInventory, level, None, version)
        case eb:EmptyBox =>
          val failMsg = eb ?~ "node detail level not correctly sent"
          toJsonError(None, failMsg.msg)(schema.name,prettify)
      }
    }
  }

  object ApplyPocicyAllNodes extends LiftApiModule0 {
    val schema = API.ApplyPocicyAllNodes
    val restExtractor = restExtractorService
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      implicit val prettify = params.prettify
      implicit val action = "applyPolicyAllNodes"

      (for {
        classes <- restExtractorService.extractList("classes")(req)(json => Full(json))
        response <- apiV8service.runAllNodes(classes)
      } yield {
        toJsonResponse(None, response)
      }) match {
        case Full(response) => response
        case eb : EmptyBox => {
          val fail = eb ?~! s"An error occured when applying policy on all Nodes"
          toJsonError(None, fail.messageChain)
        }
      }
    }
  }

  object ApplyPolicy extends LiftApiModule {
    val schema = API.ApplyPolicy
    val restExtractor = restExtractorService
    def process(version: ApiVersion, path: ApiPath, id: String, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      (for {
        classes <- restExtractorService.extractList("classes")(req)(json => Full(json))
        response <- apiV8service.runNode(NodeId(id),classes)
      } yield {
        OutputStreamResponse(response)
      }) match {
        case Full(response) => response
        case eb : EmptyBox => {
          implicit val prettify = params.prettify
          implicit val action = "applyPolicy"
          val fail = eb ?~! s"An error occured when applying policy on Node '${id}'"
          toJsonError(Some(id), fail.messageChain)

        }
      }
    }

  }
}

class NodeApiService2 (
    newNodeManager    : NewNodeManager
  , nodeInfoService   : NodeInfoService
  , removeNodeService : RemoveNodeService
  , uuidGen           : StringUuidGenerator
  , restExtractor     : RestExtractorService
  , restSerializer    : RestDataSerializer
) ( implicit userService : UserService ) extends Loggable {

  import restSerializer._
  def listAcceptedNodes (req : Req) = {
    implicit val prettify = restExtractor.extractPrettify(req.params)
    implicit val action = "listAcceptedNodes"
      nodeInfoService.getAll match {
        case Full(nodes) =>
          val acceptedNodes = nodes.values.map(serializeNodeInfo(_,"accepted"))
          toJsonResponse(None, ( "nodes" -> JArray(acceptedNodes.toList)))

        case eb: EmptyBox => val message = (eb ?~ ("Could not fetch accepted Nodes")).msg
          toJsonError(None, message)
      }
  }

  def acceptedNodeDetails (req : Req, id :NodeId ) = {
    implicit val prettify = restExtractor.extractPrettify(req.params)
    implicit val action = "acceptedNodeDetails"
    nodeInfoService.getNodeInfo(id) match {
      case Full(Some(info)) =>
        val node =  serializeNodeInfo(info,"accepted")
        toJsonResponse(None, ( "nodes" -> JArray(List(node))))
      case Full(None) =>
        toJsonError(None, s"Could not find accepted Node ${id.value}")
      case eb:EmptyBox =>
        val message = (eb ?~ s"Could not find accepted Node ${id.value}").messageChain
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
            val node =  serializeServerInfo(info,"pending")
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
        val pendingNodes = ids.map(serializeServerInfo(_,"pending")).toList
        toJsonResponse(None, ( "nodes" -> JArray(pendingNodes)))

      case eb: EmptyBox => val message = (eb ?~ ("Could not fetch pending Nodes")).msg
        toJsonError(None, message)
    }
  }

  def deleteNode(req : Req, ids: Seq[NodeId]) = {
    implicit val prettify = restExtractor.extractPrettify(req.params)
    implicit val action = "deleteNode"
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
        optInfo <- nodeInfoService.getNodeInfo(id)
        info    <- optInfo match {
                     case None    => Failure(s"Can not removed the node with id '${id.value}' because it was not found")
                     case Some(x) => Full(x)
                   }
        remove  <- removeNodeService.removeNode(info.id, modId, actor)
      } yield { serializeNodeInfo(info,"deleted") }
    }

   ( action match {
      case AcceptNode =>
        newNodeManager.accept(ids, modId, actor, "").map(_.map(serializeInventory(_, "accepted")))

      case RefuseNode =>
        newNodeManager.refuse(ids, modId, actor, "").map(_.map(serializeServerInfo(_,"refused")))

      case DeleteNode =>
        boxSequence(ids.map(actualNodeDeletion(_,modId,actor)))
   } ).map(_.toList)
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

}

class NodeApiService4 (
    inventoryRepository  : LDAPFullInventoryRepository
  , nodeInfoService      : NodeInfoService
  , softwareRepository   : ReadOnlySoftwareDAO
  , uuidGen              : StringUuidGenerator
  , restExtractor        : RestExtractorService
  , restSerializer       : RestDataSerializer
  , roAgentRunsRepository: RoReportsExecutionRepository
) extends Loggable {

  import restSerializer._

  def getNodeDetails(nodeId: NodeId, detailLevel: NodeDetailLevel, state: InventoryStatus, version : ApiVersion) = {
    for {
      optNodeInfo <- state match {
                    case AcceptedInventory => nodeInfoService.getNodeInfo(nodeId)
                    case PendingInventory  => nodeInfoService.getPendingNodeInfo(nodeId)
                    case RemovedInventory  => nodeInfoService.getDeletedNodeInfo(nodeId)
                  }
      nodeInfo    <- optNodeInfo match {
                       case None    => Failure(s"The node with id ${nodeId.value} was not found in ${state.name} nodes")
                       case Some(x) => Full(x)
                     }
      runs        <- roAgentRunsRepository.getNodesLastRun(Set(nodeId))
      inventory <- if(detailLevel.needFullInventory()) {
                     inventoryRepository.get(nodeId, state).toBox
                   } else {
                     Full(None)
                   }
      software  <- if(detailLevel.needSoftware()) {
                     (for {
                       software <- inventory match {
                                      case Some(i) => softwareRepository.getSoftware(i.node.softwareIds)
                                      case None    => softwareRepository.getSoftwareByNode(Set(nodeId), state).map( _.get(nodeId).getOrElse(Seq()))
                                    }
                     } yield {
                       software
                     }).toBox
                   } else {
                     Full(Seq())
                   }
    } yield {
      val runDate = runs.get(nodeId).flatMap( _.map(_.agentRunId.date))
      serializeInventory(nodeInfo, state, runDate, inventory, software, detailLevel, version)
    }
  }

  def nodeDetailsWithStatus(nodeId: NodeId, detailLevel: NodeDetailLevel, state: InventoryStatus, version : ApiVersion, req: Req) = {
    implicit val prettify = restExtractor.extractPrettify(req.params)
    implicit val action = s"${state.name}NodeDetails"
    getNodeDetails(nodeId, detailLevel, state, version) match {
        case Full(inventory) =>
          toJsonResponse(Some(nodeId.value), ( "nodes" -> JArray(List(inventory))))
        case eb: EmptyBox =>
          val message = (eb ?~ (s"Could not find Node ${nodeId.value} in state '${state.name}'")).msg
          toJsonError(Some(nodeId.value), message)
      }
  }

  def nodeDetailsGeneric(nodeId: NodeId, detailLevel: NodeDetailLevel, version : ApiVersion, req: Req) = {
    implicit val prettify = restExtractor.extractPrettify(req.params)
    implicit val action = "nodeDetails"
    getNodeDetails(nodeId, detailLevel, AcceptedInventory, version) match {
        case Full(inventory) =>
          toJsonResponse(Some(nodeId.value), ( "nodes" -> JArray(List(inventory))))
        case eb: EmptyBox =>
          getNodeDetails(nodeId, detailLevel, PendingInventory, version) match {
            case Full(inventory) =>
              toJsonResponse(Some(nodeId.value), ( "nodes" -> JArray(List(inventory))))
            case eb: EmptyBox =>
              getNodeDetails(nodeId, detailLevel, RemovedInventory, version) match {
                case Full(inventory) =>
                  toJsonResponse(Some(nodeId.value), ( "nodes" -> JArray(List(inventory))))
                case eb: EmptyBox =>
                  val message = (eb ?~ (s"Could not find Node ${nodeId.value}")).msg
                  toJsonError(Some(nodeId.value), message)
              }
          }
    }
  }
}

class NodeApiService6 (
    nodeInfoService           : NodeInfoService
  , inventoryRepository       : LDAPFullInventoryRepository
  , softwareRepository        : ReadOnlySoftwareDAO
  , restExtractor             : RestExtractorService
  , restSerializer            : RestDataSerializer
  , acceptedNodeQueryProcessor: QueryProcessor
  , roAgentRunsRepository     : RoReportsExecutionRepository
) extends Loggable {

  import restSerializer._
  def listNodes ( state: InventoryStatus, detailLevel : NodeDetailLevel, nodeFilter : Option[Seq[NodeId]], version : ApiVersion)( implicit prettify : Boolean) = {
    implicit val action = s"list${state.name.capitalize}Nodes"

    (for {
      nodeInfos   <- state match {
                       case AcceptedInventory => nodeInfoService.getAll()
                       case PendingInventory  => nodeInfoService.getPendingNodeInfos()
                       case RemovedInventory  => nodeInfoService.getDeletedNodeInfos()
                     }
      nodeIds     =  nodeFilter.getOrElse(nodeInfos.keySet).toSet
      runs        <- roAgentRunsRepository.getNodesLastRun(nodeIds)
      inventories <- if(detailLevel.needFullInventory()) {
                       inventoryRepository.getAllInventories(state).toBox
                     } else {
                       Full(Map[NodeId, FullInventory]())
                     }
      software    <- if(detailLevel.needSoftware()) {
                       softwareRepository.getSoftwareByNode(nodeInfos.keySet, state).toBox
                     } else {
                       Full(Map[NodeId, Seq[Software]]())
                     }
    } yield {
      for {
        nodeId    <- nodeIds
        nodeInfo  <- nodeInfos.get(nodeId)
      } yield {
        val runDate = runs.get(nodeId).flatMap( _.map(_.agentRunId.date))
        serializeInventory(nodeInfo, state, runDate, inventories.get(nodeId), software.getOrElse(nodeId, Seq()), detailLevel, version)
      }
    }
    ) match {
      case Full(nodes) => {
        toJsonResponse(None, ( "nodes" -> JArray(nodes.toList)))
      }
      case eb: EmptyBox => {
        val message = (eb ?~ (s"Could not fetch ${state.name} Nodes")).msg
        toJsonError(None, message)
      }
    }
  }

  def queryNodes ( query: Query, state: InventoryStatus, detailLevel : NodeDetailLevel, version : ApiVersion)( implicit prettify : Boolean) = {
    implicit val action = s"list${state.name.capitalize}Nodes"
    ( for {
        nodeIds <-  acceptedNodeQueryProcessor.process(query).map(_.map(_.id))
      } yield {
        listNodes(state,detailLevel,Some(nodeIds),version)
      }
    ) match {
      case Full(resp) => {
        resp
      }
      case eb: EmptyBox => {
        val message = (eb ?~ (s"Could not find ${state.name} Nodes")).msg
        toJsonError(None, message)
      }
    }
  }

}

class NodeApiService8 (
    nodeRepository  : WoNodeRepository
  , nodeInfoService : NodeInfoService
  , uuidGen         : StringUuidGenerator
  , asyncRegenerate : AsyncDeploymentActor
  , relayApiEndpoint: String
  , userService     : UserService
) extends Loggable {

  def updateRestNode(nodeId: NodeId, restNode: RestNode, actor : EventActor, reason : Option[String]) : Box[Node] = {

    val modId = ModificationId(uuidGen.newUuid)

    for {
      node           <- nodeInfoService.getNodeInfo(nodeId).flatMap( _.map( _.node ))
      newProperties  <- CompareProperties.updateProperties(node.properties, restNode.properties)
      updated        =  node.copy(properties = newProperties, policyMode = restNode.policyMode.getOrElse(node.policyMode), state=restNode.state.getOrElse(node.state))
      saved          <- if(updated == node) Full(node)
                        else nodeRepository.updateNode(updated, modId, actor, reason).toBox
    } yield {
      if(node != updated) {
        asyncRegenerate ! AutomaticStartDeployment(ModificationId(uuidGen.newUuid), userService.getCurrentUser.actor)
      }
      saved
    }
  }

  // buffer size for file I/O
  private[this] val pipeSize = 4096

  def runResponse(in : InputStream)(out : OutputStream) = {
    val bytes : Array[Byte] = new Array(pipeSize)
    val zero = 0.toByte
    var read = 0
    try {
      while (read != -1) {
        Arrays.fill(bytes,zero)
        read = in.read(bytes)
        out.write(bytes)
        out.flush()
      }
    } catch {
      case e : IOException =>
        out.write(s"Error when trying to contact internal remote-run API: ${e.getMessage}".getBytes(StandardCharsets.UTF_8))
        out.flush()
    }
  }

  def remoteRunRequest(nodeId: NodeId, classes : List[String], keepOutput : Boolean, asynchronous : Boolean) = {
    val url = s"${relayApiEndpoint}/remote-run/nodes/${nodeId.value}"
    val params =
      ( "classes"     , classes.mkString(",") ) ::
      ( "keep_output" , keepOutput.toString   ) ::
      ( "asynchronous", asynchronous.toString ) ::
      Nil
    // We currently bypass verification on certificate
    // We should add an option to allow the user to define a certificate in configuration file
    val options = HttpOptions.allowUnsafeSSL :: Nil

    Http(url).params(params).options(options).postForm
  }

  def runNode(nodeId: NodeId, classes : List[String]) : Box[OutputStream => Unit] = {
    import monix.execution.Scheduler.Implicits.global
    val request = remoteRunRequest(nodeId,classes,true,true)

    val in = new PipedInputStream(pipeSize)
    val out = new PipedOutputStream(in)

    val response = Task( request.exec{ case (status,headers,is) =>
      if (status >= 200 && status < 300) {
        runResponse(is)(out)
      } else {
        out.write((s"Error ${status} occured when contacting internal remote-run API to apply " +
                  s"classes on Node '${nodeId.value}': \n${HttpConstants.readString(is)}\n\n").getBytes)
        out.flush
      }
    })
    response.runAsync.onComplete { _ => out.close() }
    Full(runResponse(in))
  }

  def runAllNodes(classes : List[String]) : Box[JValue] = {

    for {
      nodes <- nodeInfoService.getAll() ?~! s"Could not find nodes informations"

    } yield {

      val res =
      for {
        node <- nodes.values.toList
      } yield {
        {

         val request = remoteRunRequest(node.id, classes, false, true)
         val commandRun = {

           val result = request.asString
           if (result.isSuccess) {
             "Started"
           } else {
             s"An error occured when applying policy on Node '${node.id.value}', cause is: ${result.body}"
           }
         }
         ( ( "id" -> node.id.value)
         ~ ( "hostname" -> node.hostname)
         ~ ( "result"   -> commandRun)
         )

        }
      }
      JArray(res)
    }
  }

}
