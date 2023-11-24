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

import cats.data.Validated.Invalid
import cats.data.Validated.Valid
import cats.data.ValidatedNel
import com.normation.box._
import com.normation.errors._
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.inventory.domain._
import com.normation.inventory.domain.NodeId
import com.normation.inventory.ldap.core.InventoryDit
import com.normation.ldap.sdk.LDAPConnectionProvider
import com.normation.ldap.sdk.RwLDAPConnection
import com.normation.rudder.api.ApiVersion
import com.normation.rudder.apidata.NodeDetailLevel
import com.normation.rudder.apidata.RenderInheritedProperties
import com.normation.rudder.apidata.RestDataSerializer
import com.normation.rudder.domain.NodeDit
import com.normation.rudder.domain.logger.NodeLogger
import com.normation.rudder.domain.logger.NodeLoggerPure
import com.normation.rudder.domain.logger.TimingDebugLoggerPure
import com.normation.rudder.domain.nodes.Node
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.domain.nodes.NodeState
import com.normation.rudder.domain.policies.GlobalPolicyMode
import com.normation.rudder.domain.policies.PolicyModeOverrides.Always
import com.normation.rudder.domain.policies.PolicyModeOverrides.Unoverridable
import com.normation.rudder.domain.properties.CompareProperties
import com.normation.rudder.domain.properties.NodeProperty
import com.normation.rudder.domain.properties.NodePropertyHierarchy
import com.normation.rudder.domain.queries.Query
import com.normation.rudder.domain.reports.ComplianceLevel
import com.normation.rudder.facts.nodes.ChangeContext
import com.normation.rudder.facts.nodes.CoreNodeFact
import com.normation.rudder.facts.nodes.NodeFact
import com.normation.rudder.facts.nodes.NodeFactRepository
import com.normation.rudder.facts.nodes.QueryContext
import com.normation.rudder.facts.nodes.SelectFacts
import com.normation.rudder.reports.ReportingConfiguration
import com.normation.rudder.reports.execution.AgentRunWithNodeConfig
import com.normation.rudder.reports.execution.RoReportsExecutionRepository
import com.normation.rudder.repository.RoNodeGroupRepository
import com.normation.rudder.repository.RoParameterRepository
import com.normation.rudder.repository.json.DataExtractor.CompleteJson
import com.normation.rudder.repository.json.DataExtractor.OptionnalJson
import com.normation.rudder.repository.ldap.LDAPEntityMapper
import com.normation.rudder.rest.{NodeApi => API}
import com.normation.rudder.rest.ApiPath
import com.normation.rudder.rest.AuthzToken
import com.normation.rudder.rest.NotFoundError
import com.normation.rudder.rest.OneParam
import com.normation.rudder.rest.RestExtractorService
import com.normation.rudder.rest.RestUtils
import com.normation.rudder.rest.RestUtils.effectiveResponse
import com.normation.rudder.rest.RestUtils.toJsonError
import com.normation.rudder.rest.RestUtils.toJsonResponse
import com.normation.rudder.rest.data._
import com.normation.rudder.rest.data.Creation.CreationError
import com.normation.rudder.rest.data.NodeSetup
import com.normation.rudder.rest.data.NodeTemplate
import com.normation.rudder.rest.data.NodeTemplate.AcceptedNodeTemplate
import com.normation.rudder.rest.data.NodeTemplate.PendingNodeTemplate
import com.normation.rudder.rest.data.Rest
import com.normation.rudder.rest.data.Rest.NodeDetails
import com.normation.rudder.rest.data.Validation
import com.normation.rudder.rest.data.Validation.NodeValidationError
import com.normation.rudder.services.nodes.MergeNodeProperties
import com.normation.rudder.services.nodes.NodeInfoService
import com.normation.rudder.services.queries._
import com.normation.rudder.services.reports.ReportingService
import com.normation.rudder.services.servers.DeleteMode
import com.normation.rudder.services.servers.NewNodeManager
import com.normation.rudder.services.servers.RemoveNodeService
import com.normation.utils.DateFormaterService
import com.normation.utils.StringUuidGenerator
import com.normation.zio._
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.ConnectException
import java.nio.charset.StandardCharsets
import java.util.Arrays
import net.liftweb.common.Box
import net.liftweb.common.EmptyBox
import net.liftweb.common.Failure
import net.liftweb.common.Full
import net.liftweb.http.JsonResponse
import net.liftweb.http.LiftResponse
import net.liftweb.http.OutputStreamResponse
import net.liftweb.http.Req
import net.liftweb.http.js.JsExp
import net.liftweb.json.JArray
import net.liftweb.json.JsonAST.JDouble
import net.liftweb.json.JsonAST.JField
import net.liftweb.json.JsonAST.JInt
import net.liftweb.json.JsonAST.JObject
import net.liftweb.json.JsonAST.JString
import net.liftweb.json.JsonDSL._
import net.liftweb.json.JsonDSL.pair2jvalue
import net.liftweb.json.JsonDSL.string2jvalue
import net.liftweb.json.JValue
import org.joda.time.DateTime
import scalaj.http.Http
import scalaj.http.HttpOptions
import zio.{System => _, _}
import zio.stream.ZSink
import zio.syntax._

/*
 * NodeApi implementation.
 *
 * This must be reworked to note use a "nodeApiService",
 * but make the implementation directly here.
 */
class NodeApi(
    restExtractorService: RestExtractorService,
    serializer:           RestDataSerializer,
    nodeApiService:       NodeApiService,
    inheritedProperties:  NodeApiInheritedProperties,
    uuidGen:              StringUuidGenerator,
    deleteDefaultMode:    DeleteMode
) extends LiftApiModuleProvider[API] {

  def schemas = API

  def getLiftEndpoints(): List[LiftApiModule] = {
    API.endpoints.map(e => {
      e match {
        case API.ListPendingNodes               => ListPendingNodes
        case API.NodeDetails                    => NodeDetails
        case API.NodeInheritedProperties        => NodeInheritedProperties
        case API.NodeDisplayInheritedProperties => NodeDisplayInheritedProperties
        case API.PendingNodeDetails             => PendingNodeDetails
        case API.DeleteNode                     => DeleteNode
        case API.ChangePendingNodeStatus        => ChangePendingNodeStatus
        case API.ChangePendingNodeStatus2       => ChangePendingNodeStatus2
        case API.ApplyPolicyAllNodes            => ApplyPolicyAllNodes
        case API.UpdateNode                     => UpdateNode
        case API.ListAcceptedNodes              => ListAcceptedNodes
        case API.ApplyPolicy                    => ApplyPolicy
        case API.GetNodesStatus                 => GetNodesStatus
        case API.NodeDetailsTable               => NodeDetailsTable
        case API.NodeDetailsSoftware            => NodeDetailsSoftware
        case API.NodeDetailsProperty            => NodeDetailsProperty
        case API.CreateNodes                    => CreateNodes
      }
    })
  }

  /*
   * Return a Json Object that list available backend,
   * their state of configuration, and what are the current
   * enabled ones.
   */
  object CreateNodes extends LiftApiModule0 { //
    val schema        = API.CreateNodes
    val restExtractor = restExtractorService

    import ResultHolder._
    import com.normation.rudder.rest.data.Rest.JsonCodecNodeDetails._
    import zio.json._

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      import com.softwaremill.quicklens._
      (for {
        json  <- (if (req.json_?) req.body else Failure("This API only Accept JSON request")).toIO
        nodes <- new String(json, StandardCharsets.UTF_8).fromJson[List[NodeDetails]].toIO
        res   <- ZIO.foldLeft(nodes)(ResultHolder(Nil, Nil)) {
                   case (res, node) =>
                     nodeApiService.saveNode(node, authzToken.actor, req.remoteAddr).either.map {
                       case Right(id) => res.modify(_.created).using(_ :+ id)
                       case Left(err) => res.modify(_.failed).using(_ :+ ((node.id, err)))
                     }
                 }
      } yield res).toBox match {
        case Full(resultHolder) =>
          // if all success, return success.
          // Or if at least one is not failed, return success ?
          val json = resultHolder.toJson()
          if (resultHolder.failed.isEmpty) {
            RestUtils.toJsonResponse(None, json)(schema.name, params.prettify)
          } else {
            RestUtils.toJsonError(None, json)(schema.name, params.prettify)
          }
        case eb: EmptyBox =>
          val err = eb ?~! "Error when trying to parse node creation request"
          RestUtils.toJsonError(None, JString(err.messageChain))(schema.name, params.prettify)
      }
    }
  }

  object NodeDetails extends LiftApiModule {
    val schema: OneParam = API.NodeDetails
    val restExtractor = restExtractorService
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        id:         String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      restExtractor.extractNodeDetailLevel(req.params) match {
        case Full(level) =>
          nodeApiService.nodeDetailsGeneric(NodeId(id), level, version, req)
        case eb: EmptyBox =>
          val failMsg = eb ?~ "node detail level not correctly sent"
          toJsonError(None, failMsg.msg)("nodeDetail", params.prettify)
      }
    }
  }

  object NodeInheritedProperties extends LiftApiModule {
    val schema: OneParam = API.NodeInheritedProperties
    val restExtractor = restExtractorService
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        id:         String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      inheritedProperties.getNodePropertiesTree(NodeId(id), RenderInheritedProperties.JSON).either.runNow match {
        case Right(properties) =>
          toJsonResponse(None, properties)("nodeInheritedProperties", params.prettify)
        case Left(err)         =>
          toJsonError(None, err.fullMsg)("nodeInheritedProperties", params.prettify)
      }
    }
  }

  object NodeDisplayInheritedProperties extends LiftApiModule {
    val schema: OneParam = API.NodeDisplayInheritedProperties
    val restExtractor = restExtractorService
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        id:         String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      inheritedProperties.getNodePropertiesTree(NodeId(id), RenderInheritedProperties.HTML).either.runNow match {
        case Right(properties) =>
          toJsonResponse(None, properties)("nodeDisplayInheritedProperties", params.prettify)
        case Left(err)         =>
          toJsonError(None, err.fullMsg)("nodeDisplayInheritedProperties", params.prettify)
      }
    }
  }

  object PendingNodeDetails extends LiftApiModule {
    val schema: OneParam = API.PendingNodeDetails
    val restExtractor = restExtractorService
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        id:         String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      nodeApiService.pendingNodeDetails(NodeId(id), params.prettify)
    }
  }

  /*
   * Delete a node.
   * Boolean option "clean" allows to
   */
  object DeleteNode extends LiftApiModule {

    val schema: OneParam = API.DeleteNode
    val restExtractor = restExtractorService
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        id:         String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      val pretiffy      = restExtractor.extractPrettify(req.params)
      val deleteModeKey = "mode"

      val mode = restExtractor
        .extractString(deleteModeKey)(req) { m =>
          val found = DeleteMode.all.find(_.name == m)
          Full(found.getOrElse(deleteDefaultMode))
        }
        .map(_.getOrElse(deleteDefaultMode))
        .getOrElse(deleteDefaultMode)

      nodeApiService.deleteNode(NodeId(id), authzToken.actor, req.remoteAddr, pretiffy, mode)
    }
  }

  object UpdateNode extends LiftApiModule {
    val schema: OneParam = API.UpdateNode
    val restExtractor = restExtractorService
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        id:         String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val prettify = params.prettify
      implicit val action   = "updateNode"
      implicit val cc       = ChangeContext(
        ModificationId(uuidGen.newUuid),
        authzToken.actor,
        new DateTime(),
        params.reason,
        Some(req.remoteAddr),
        QueryContext.todoQC.nodePerms
      )

      (for {
        restNode <- if (req.json_?) {
                      req.json.flatMap(body => restExtractor.extractNodeFromJSON(body))
                    } else {
                      restExtractor.extractNode(req.params)
                    }
        reason   <- restExtractor.extractReason(req)
        result   <- nodeApiService.updateRestNode(NodeId(id), restNode).toBox
      } yield {
        toJsonResponse(Some(id), serializer.serializeNode(result.toNode))
      }) match {
        case Full(response) =>
          response
        case eb: EmptyBox =>
          val fail = eb ?~! s"An error occurred while updating Node '${id}'"
          toJsonError(Some(id), fail.messageChain)
      }
    }
  }

  object ChangePendingNodeStatus extends LiftApiModule0 {
    val schema        = API.ChangePendingNodeStatus
    val restExtractor = restExtractorService
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      implicit val prettify     = params.prettify
      val (nodeIds, nodeStatus) = if (req.json_?) {
        req.json match {
          case Full(json) =>
            (restExtractor.extractNodeIdsFromJson(json), restExtractor.extractNodeStatusFromJson(json))
          case eb: EmptyBox => (eb, eb)
        }
      } else {
        (restExtractor.extractNodeIds(req.params), restExtractor.extractNodeStatus(req.params))
      }
      nodeApiService.changeNodeStatus(nodeIds, nodeStatus, authzToken.actor, req.remoteAddr, prettify)
    }
  }

  object ChangePendingNodeStatus2 extends LiftApiModule {
    val schema: OneParam = API.ChangePendingNodeStatus2
    val restExtractor = restExtractorService
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        id:         String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val prettify = params.prettify
      val nodeStatus        = if (req.json_?) {
        req.json match {
          case Full(json) =>
            restExtractor.extractNodeStatusFromJson(json)
          case eb: EmptyBox => eb
        }
      } else {
        restExtractor.extractNodeStatus(req.params)
      }
      nodeApiService.changeNodeStatus(Full(Some(List(NodeId(id)))), nodeStatus, authzToken.actor, req.remoteAddr, prettify)
    }
  }

  object ListAcceptedNodes extends LiftApiModule0 {
    val schema        = API.ListAcceptedNodes
    val restExtractor = restExtractorService
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      implicit val prettify = params.prettify
      implicit val qc       = QueryContext(authzToken.actor, QueryContext.todoQC.nodePerms)
      restExtractor.extractNodeDetailLevel(req.params) match {
        case Full(level) =>
          restExtractor.extractQuery(req.params) match {
            case Full(None)        =>
              nodeApiService.listNodes(AcceptedInventory, level, None, version)
            case Full(Some(query)) =>
              nodeApiService.queryNodes(query, AcceptedInventory, level, version)
            case eb: EmptyBox =>
              val failMsg = eb ?~ "Node query not correctly sent"
              toJsonError(None, failMsg.msg)("listAcceptedNodes", prettify)

          }
        case eb: EmptyBox =>
          val failMsg = eb ?~ "Node detail level not correctly sent"
          toJsonError(None, failMsg.msg)("listAcceptedNodes", prettify)
      }
    }
  }

  object ListPendingNodes extends LiftApiModule0 {
    val schema        = API.ListPendingNodes
    val restExtractor = restExtractorService
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      implicit val prettify = params.prettify
      implicit val qc       = QueryContext(authzToken.actor, QueryContext.todoQC.nodePerms)
      restExtractor.extractNodeDetailLevel(req.params) match {
        case Full(level) =>
          restExtractor.extractQuery(req.params) match {
            case Full(None)        =>
              nodeApiService.listNodes(PendingInventory, level, None, version)
            case Full(Some(query)) =>
              nodeApiService.queryNodes(query, PendingInventory, level, version)
            case eb: EmptyBox =>
              val failMsg = eb ?~ "Query for pending nodes not correctly sent"
              toJsonError(None, failMsg.msg)("listPendingNodes", prettify)
          }
        case eb: EmptyBox =>
          val failMsg = eb ?~ "node detail level not correctly sent"
          toJsonError(None, failMsg.msg)(schema.name, prettify)
      }
    }
  }

  object ApplyPolicyAllNodes extends LiftApiModule0 {
    val schema        = API.ApplyPolicyAllNodes
    val restExtractor = restExtractorService
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      implicit val prettify = params.prettify
      implicit val action   = "applyPolicyAllNodes"

      (for {
        classes  <- restExtractorService.extractList("classes")(req)(json => Full(json))
        response <- nodeApiService.runAllNodes(classes)
      } yield {
        toJsonResponse(None, response)
      }) match {
        case Full(response) => response
        case eb: EmptyBox => {
          val fail = eb ?~! s"An error occurred when applying policy on all Nodes"
          toJsonError(None, fail.messageChain)
        }
      }
    }
  }

  object ApplyPolicy extends LiftApiModule {
    val schema: OneParam = API.ApplyPolicy
    val restExtractor = restExtractorService
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        id:         String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val prettify = params.prettify
      (for {
        classes <- restExtractorService.extractList("classes")(req)(json => Full(json))
        optNode <- nodeApiService.nodeInfoService.getNodeInfo(NodeId(id)).toBox
      } yield {
        optNode match {
          case Some(node)
              if (node.agentsName.exists(a => a.agentType == AgentType.CfeCommunity || a.agentType == AgentType.CfeEnterprise)) =>
            OutputStreamResponse(nodeApiService.runNode(node.id, classes))
          case Some(node) =>
            toJsonError(
              None,
              s"Node with id '${id}' has an agent type (${node.agentsName.map(_.agentType.displayName).mkString(",")}) which doesn't support remote run"
            )("applyPolicy", prettify)
          case None       =>
            toJsonError(None, s"Node with id '${id}' was not found")("applyPolicy", prettify)
        }
      }) match {
        case Full(response) => response
        case eb: EmptyBox => {
          implicit val prettify = params.prettify
          implicit val action   = "applyPolicy"
          val fail              = eb ?~! s"An error occurred when applying policy on Node '${id}'"
          toJsonError(Some(id), fail.messageChain)

        }
      }
    }
  }

  object GetNodesStatus extends LiftApiModule0 {
    val schema        = API.GetNodesStatus
    val restExtractor = restExtractorService
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      implicit val action             = "getNodeStatus"
      implicit val prettify           = params.prettify
      def errorMsg(ids: List[String]) = s"Error when trying to get status for nodes with IDs '${ids.mkString(",")}''"
      (for {
        ids      <- (restExtractorService
                      .extractString("ids")(req)(ids => Full(ids.split(",").map(_.trim))))
                      .map(_.map(_.toList).getOrElse(Nil)) ?~! "Error: 'ids' parameter not found"
        accepted <- nodeApiService.nodeInfoService.getAllNodesIds().map(_.map(_.value)).toBox ?~! errorMsg(ids)
        pending  <- nodeApiService.nodeInfoService.getPendingNodeInfos().map(_.keySet.map(_.value)).toBox ?~! errorMsg(ids)
      } yield {
        val array = ids.map { id =>
          val status = {
            if (accepted.contains(id)) AcceptedInventory.name
            else if (pending.contains(id)) PendingInventory.name
            else "deleted"
          }
          JObject(JField("id", id) :: JField("status", status) :: Nil)
        }
        JObject(JField("nodes", JArray(array)) :: Nil)
      }) match {
        case Full(jarray) =>
          toJsonResponse(None, jarray)
        case eb: EmptyBox => {
          val fail = eb ?~! s"An error occurred when trying to get nodes status"
          toJsonError(None, fail.messageChain)
        }
      }
    }
  }

  // WARNING : This is a READ ONLY action
  //   No modifications will be performed
  //   read_only user can access this endpoint
  object NodeDetailsTable extends LiftApiModule0 {
    val schema        = API.NodeDetailsTable
    val restExtractor = restExtractorService
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      (for {
        nodes <- nodeApiService.listNodes(req)
      } yield {
        JsonResponse(nodes)
      }) match {
        case Full(res) => res
        case eb: EmptyBox =>
          JsonResponse(JObject(JField("error", (eb ?~! "An error occurred while getting node details").messageChain)))
      }
    }
  }

  object NodeDetailsSoftware extends LiftApiModule {
    val schema: OneParam = API.NodeDetailsSoftware
    val restExtractor = restExtractorService
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        software:   String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      (for {
        response <- nodeApiService.software(req, software)
      } yield {
        response
      }) match {
        case Full(res) => res
        case eb: EmptyBox =>
          JsonResponse(
            JObject(
              JField(
                "error",
                (eb ?~! s"An error occurred while fetching versions of '${software}' software for nodes").messageChain
              )
            )
          )
      }
    }
  }
  object NodeDetailsProperty extends LiftApiModule {
    val schema: OneParam = API.NodeDetailsProperty
    val restExtractor = restExtractorService
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        property:   String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      (for {
        inheritedProperty <- req.json.flatMap(j => OptionnalJson.extractJsonBoolean(j, "inherited"))
        response          <- nodeApiService.property(req, property, inheritedProperty.getOrElse(false))
      } yield {
        response
      }) match {
        case Full(res) => res
        case eb: EmptyBox =>
          JsonResponse(
            JObject(
              JField("error", (eb ?~! s"An error occurred while getting value of property '${property}' for nodes").messageChain)
            )
          )
      }
    }
  }
}

class NodeApiInheritedProperties(
    infoService: NodeInfoService,
    groupRepo:   RoNodeGroupRepository,
    paramRepo:   RoParameterRepository
) {

  /*
   * Full list of node properties, including inherited ones for a node
   */
  def getNodePropertiesTree(nodeId: NodeId, renderInHtml: RenderInheritedProperties): IOResult[JValue] = {
    for {
      nodeInfo   <- infoService.getNodeInfo(nodeId).notOptional(s"Node with ID '${nodeId.value}' was not found.'")
      groups     <- groupRepo.getFullGroupLibrary()
      nodeTargets = groups.getTarget(nodeInfo).map(_._2).toList
      params     <- paramRepo.getAllGlobalParameters()
      properties <- MergeNodeProperties.forNode(nodeInfo, nodeTargets, params.map(p => (p.name, p)).toMap).toIO
    } yield {
      import com.normation.rudder.domain.properties.JsonPropertySerialisation._
      val rendered = renderInHtml match {
        case RenderInheritedProperties.HTML => properties.toApiJsonRenderParents
        case RenderInheritedProperties.JSON => properties.toApiJson
      }
      JArray(
        (
          ("nodeId" -> nodeId.value)
          ~ ("properties" -> rendered)
        ) :: Nil
      )
    }
  }
}

class NodeApiService(
    ldapConnection:             LDAPConnectionProvider[RwLDAPConnection],
    nodeFactRepository:         NodeFactRepository,
    groupRepo:                  RoNodeGroupRepository,
    paramRepo:                  RoParameterRepository,
    reportsExecutionRepository: RoReportsExecutionRepository,
    ldapEntityMapper:           LDAPEntityMapper,
    uuidGen:                    StringUuidGenerator,
    nodeDit:                    NodeDit,
    pendingDit:                 InventoryDit,
    acceptedDit:                InventoryDit,
    val nodeInfoService:        NodeInfoService,
    newNodeManager:             NewNodeManager,
    removeNodeService:          RemoveNodeService,
    restExtractor:              RestExtractorService,
    restSerializer:             RestDataSerializer,
    reportingService:           ReportingService,
    acceptedNodeQueryProcessor: QueryProcessor,
    pendingNodeQueryProcessor:  QueryChecker,
    getGlobalMode:              () => Box[GlobalPolicyMode],
    relayApiEndpoint:           String
) {

/// utility functions ///

  /*
   * for a given nodedetails, we:
   * - try to convert it to inventory/node setup info
   * - save inventory
   * - if needed, accept
   * - now, setup node info (property, state, etc)
   */
  def saveNode(nodeDetails: Rest.NodeDetails, eventActor: EventActor, actorIp: String): IO[CreationError, NodeId] = {
    def toCreationError(res: ValidatedNel[NodeValidationError, NodeTemplate]) = {
      res match {
        case Invalid(nel) => CreationError.OnValidation(nel).fail
        case Valid(r)     => r.succeed
      }
    }

    implicit val cc: ChangeContext = ChangeContext(
      ModificationId(uuidGen.newUuid),
      eventActor,
      new DateTime(),
      None,
      Some(actorIp),
      QueryContext.todoQC.nodePerms
    )

    for {
      validated <- toCreationError(Validation.toNodeTemplate(nodeDetails))
      _         <- checkUuid(validated.inventory.node.main.id)
      created   <- saveInventory(validated.inventory)
      nodeSetup <- accept(validated)
      nodeId    <- saveRudderNode(validated.inventory.node.main.id, nodeSetup)
    } yield {
      nodeId
    }
  }

  /*
   * You can't use an existing UUID (neither pending nor accepted)
   */
  def checkUuid(nodeId: NodeId): IO[CreationError, Unit] = {
    // we don't want a node in pending/accepted
    def inventoryExists(con: RwLDAPConnection, id: NodeId) = {
      ZIO
        .foldLeft(Seq((acceptedDit, AcceptedInventory), (pendingDit, PendingInventory)))(Option.empty[InventoryStatus]) {
          case (current, (dit, s)) =>
            current match {
              case None    => con.exists(dit.NODES.NODE.dn(id)).map(exists => if (exists) Some(s) else None)
              case Some(v) => Some(v).succeed
            }
        }
        .flatMap {
          case None    => // ok, it doesn't exists
            ZIO.unit
          case Some(s) => // oups, already present
            Inconsistency(s"A node with id '${nodeId.value}' already exists with status '${s.name}'").fail
        }
    }

    (for {
      con <- ldapConnection
      _   <- inventoryExists(con, nodeId)
    } yield ()).mapError(err => CreationError.OnSaveInventory(s"Error during node ID check: ${err.fullMsg}"))
  }

  /*
   * Save the inventory part. Always save in "pending", acceptation
   * is done afterward if needed.
   */
  def saveInventory(inventory: FullInventory)(implicit cc: ChangeContext): IO[CreationError, NodeId] = {
    nodeFactRepository
      .updateInventory(inventory, software = None)
      .map(_ => inventory.node.main.id)
      .mapError(err => CreationError.OnSaveInventory(s"Error during node creation: ${err.fullMsg}"))
  }

  def accept(template: NodeTemplate)(implicit cc: ChangeContext): IO[CreationError, NodeSetup] = {
    val id = template.inventory.node.main.id

    // only nodes with status "accepted" need to be accepted
    template match {
      case AcceptedNodeTemplate(_, properties, policyMode, state) =>
        newNodeManager
          .accept(id)
          .mapError(err => CreationError.OnAcceptation((s"Can not accept node '${id.value}': ${err.fullMsg}"))) *>
        NodeSetup(properties, policyMode, state).succeed
      case PendingNodeTemplate(_, properties)                     =>
        NodeSetup(properties, None, None).succeed
    }
  }

  def mergeNodeSetup(node: Node, changes: NodeSetup): Node = {
    import com.softwaremill.quicklens._

    // for properties, we don't want to modify any of the existing one because
    // we were put during acceptation (or since node is live).
    val keep       = node.properties.map(p => (p.name, p)).toMap
    val user       = changes.properties.map(p => (p.name, p)).toMap
    // override user prop with keep
    val properties = (user ++ keep).values.toList

    node
      .modify(_.policyMode)
      .using(x => changes.policyMode.fold(x)(Some(_)))
      .modify(_.state)
      .using(x => changes.state.fold(x)(identity))
      .modify(_.properties)
      .setTo(properties)
  }

  /*
   * Save rudder node part. Must be done after acceptation if
   * acceptation is needed. If no acceptation is wanted, then
   * we provide a default node context but we can't ensure that
   * policyMode / node state will be set (validation must forbid that)
   */
  def saveRudderNode(id: NodeId, setup: NodeSetup): IO[CreationError, NodeId] = {
    // a default Node
    def default() = {
      Node(
        id,
        id.value,
        "",
        NodeState.Enabled,
        false,
        false,
        DateTime.now,
        ReportingConfiguration(None, None, None),
        Nil,
        None,
        None
      )
    }

    (for {
      ldap    <- ldapConnection
      // try t get node
      entry   <- ldap.get(nodeDit.NODES.NODE.dn(id.value), NodeInfoService.nodeInfoAttributes: _*)
      current <- entry match {
                   case Some(x) => ldapEntityMapper.entryToNode(x).toIO
                   case None    => default().succeed
                 }
      merged   = mergeNodeSetup(current, setup)
      // we ony want to touch things that were asked by the user
      nSaved  <- ldap.save(ldapEntityMapper.nodeToEntry(merged))
    } yield {
      merged.id
    }).mapError(err => CreationError.OnSaveNode(s"Error during node creation: ${err.fullMsg}"))
  }

  /*
   * Return a map of (NodeId -> propertyName -> inherited property) for the given list of nodes and
   * property. When a node doesn't have a property, the map will always
   */
  def getNodesPropertiesTree(
      nodeInfos:  Iterable[NodeInfo],
      properties: List[String]
  ): IOResult[Map[NodeId, List[NodePropertyHierarchy]]] = {
    for {
      groups      <- groupRepo.getFullGroupLibrary()
      nodesTargets = nodeInfos.map(i => (i, groups.getTarget(i).map(_._2).toList))
      params      <- paramRepo.getAllGlobalParameters()
      properties  <-
        ZIO.foreach(nodesTargets.toList) {
          case (nodeInfo, nodeTargets) =>
            MergeNodeProperties
              .forNode(nodeInfo, nodeTargets, params.map(p => (p.name, p)).toMap)
              .toIO
              .fold(
                err =>
                  (
                    nodeInfo.id,
                    nodeInfo.properties.collect { case p if properties.contains(p.name) => NodePropertyHierarchy(p, Nil) }
                  ),
                props => {
                  // here we can have the whole parent hierarchy like in node properties details with p.toApiJsonRenderParents but it needs
                  // adaptation in datatable display
                  (nodeInfo.id, props.collect { case p if properties.contains(p.prop.name) => p })
                }
              )
        }
    } yield {
      properties.toMap
    }
  }

  def serialize(
      agentRunWithNodeConfig: Option[AgentRunWithNodeConfig],
      globalPolicyMode:       GlobalPolicyMode,
      nodeInfo:               NodeInfo,
      properties:             List[NodeProperty],
      inheritedProperties:    List[NodePropertyHierarchy],
      softs:                  List[Software],
      compliance:             Option[ComplianceLevel],
      sysCompliance:          Option[ComplianceLevel]
  ): JObject = {

    def escapeHTML(s: String): String = JsExp.strToJsExp(xml.Utility.escape(s)).str

    import net.liftweb.json.JsonDSL._
    def toComplianceArray(comp: ComplianceLevel): JArray = {
      val pc = comp.computePercent()
      JArray(
        JArray(JInt(comp.reportsDisabled) :: JDouble(pc.reportsDisabled) :: Nil) ::       // 0
        JArray(JInt(comp.notApplicable) :: JDouble(pc.notApplicable) :: Nil) ::           //  1
        JArray(JInt(comp.success) :: JDouble(pc.success) :: Nil) ::                       //  2
        JArray(JInt(comp.repaired) :: JDouble(pc.repaired) :: Nil) ::                     //  3
        JArray(JInt(comp.error) :: JDouble(pc.error) :: Nil) ::                           //  4
        JArray(JInt(comp.pending) :: JDouble(pc.pending) :: Nil) ::                       //  5
        JArray(JInt(comp.noAnswer) :: JDouble(pc.noAnswer) :: Nil) ::                     //  6
        JArray(JInt(comp.missing) :: JDouble(pc.missing) :: Nil) ::                       //  7
        JArray(JInt(comp.unexpected) :: JDouble(pc.unexpected) :: Nil) ::                 //  8
        JArray(JInt(comp.auditNotApplicable) :: JDouble(pc.auditNotApplicable) :: Nil) :: //  9
        JArray(JInt(comp.compliant) :: JDouble(pc.compliant) :: Nil) ::                   // 10
        JArray(JInt(comp.nonCompliant) :: JDouble(pc.nonCompliant) :: Nil) ::             // 11
        JArray(JInt(comp.auditError) :: JDouble(pc.auditError) :: Nil) ::                 // 12
        JArray(JInt(comp.badPolicyMode) :: JDouble(pc.badPolicyMode) :: Nil) :: Nil       // 13
      )
    }

    val userCompliance            = compliance.map(c => toComplianceArray(c))
    val (policyMode, explanation) = {
      (globalPolicyMode.overridable, nodeInfo.policyMode) match {
        case (Always, Some(mode)) =>
          (mode, "override")
        case (Always, None)       =>
          (globalPolicyMode.mode, "default")
        case (Unoverridable, _)   =>
          (globalPolicyMode.mode, "none")
      }
    }

    import com.normation.rudder.domain.properties.JsonPropertySerialisation._
    (("name"                -> escapeHTML(nodeInfo.hostname))
    ~ ("policyServerId"     -> escapeHTML(nodeInfo.policyServerId.value))
    ~ ("policyMode"         -> escapeHTML(policyMode.name))
    ~ ("globalModeOverride" -> explanation)
    ~ ("kernel"             -> escapeHTML(nodeInfo.osDetails.kernelVersion.value))
    ~ ("agentVersion"       -> nodeInfo.agentsName.headOption.flatMap(_.version.map(_.value)))
    ~ ("id"                 -> escapeHTML(nodeInfo.id.value))
    ~ ("ram"                -> nodeInfo.ram.map(_.toStringMo))
    ~ ("machineType"        -> nodeInfo.machine.map(_.machineType.toString))
    ~ ("os"                 -> nodeInfo.osDetails.fullName)
    ~ ("state"              -> nodeInfo.state.name)
    ~ ("compliance"         -> userCompliance)
    ~ ("systemError"        -> sysCompliance
      .map(_.computePercent().compliance < 100)
      .getOrElse(false)) // do not display error if no sys compliance
    ~ ("ipAddresses"         -> nodeInfo.ips.filter(ip => ip != "127.0.0.1" && ip != "0:0:0:0:0:0:0:1").map(escapeHTML(_)))
    ~ ("lastRun"             -> agentRunWithNodeConfig.map(d => DateFormaterService.getDisplayDate(d.agentRunId.date)).getOrElse("Never"))
    ~ ("lastInventory"       -> DateFormaterService.getDisplayDate(nodeInfo.inventoryDate))
    ~ ("software"            -> JObject(
      softs.map(s => JField(escapeHTML(s.name.getOrElse("")), JString(escapeHTML(s.version.map(_.value).getOrElse("N/A")))))
    ))
    ~ ("properties"          -> JObject(properties.map(s => JField(s.name, s.toJson))))
    ~ ("inheritedProperties" -> JObject(inheritedProperties.map(s => JField(s.prop.name, s.toApiJsonRenderParents)))))
  }

  def listNodes(req: Req) = {
    import com.normation.box._
    val n1 = System.currentTimeMillis

    case class PropertyInfo(value: String, inherited: Boolean)

    def extractNodePropertyInfo(json: JValue) = {
      for {
        value     <- CompleteJson.extractJsonString(json, "value")
        inherited <- CompleteJson.extractJsonBoolean(json, "inherited")
      } yield {
        PropertyInfo(value, inherited)
      }
    }

    for {
      optNodeIds                           <-
        req.json.flatMap(j => OptionnalJson.extractJsonListString(j, "nodeIds", (values => Full(values.map(NodeId(_))))))
      nodes                                <- optNodeIds match {
                                                case None          =>
                                                  nodeInfoService.getAll().toBox
                                                case Some(nodeIds) =>
                                                  nodeInfoService.getNodeInfosSeq(nodeIds).map(_.map(n => (n.id, n)).toMap).toBox
                                              }
      n2                                    = System.currentTimeMillis
      _                                     = TimingDebugLoggerPure.logEffect.trace(s"Getting node infos: ${n2 - n1}ms")
      runs                                 <- reportsExecutionRepository.getNodesLastRun(nodes.keySet).toBox
      n3                                    = System.currentTimeMillis
      _                                     = TimingDebugLoggerPure.logEffect.trace(s"Getting run infos: ${n3 - n2}ms")
      (systemCompliances, userCompliances) <- reportingService.getSystemAndUserCompliance(Some(nodes.keySet)).toBox
      n4                                    = System.currentTimeMillis
      _                                     = TimingDebugLoggerPure.logEffect.trace(s"Getting compliance infos: ${n4 - n3}ms")
      globalMode                           <- getGlobalMode()
      n5                                    = System.currentTimeMillis
      _                                     = TimingDebugLoggerPure.logEffect.trace(s"Getting global mode: ${n5 - n4}ms")
      softToLookAfter                      <- req.json.flatMap(j => OptionnalJson.extractJsonListString(j, "software").map(_.getOrElse(Nil)))
      softs                                <- ZIO
                                                .foreach(softToLookAfter)(soft => nodeFactRepository.getNodesbySofwareName(soft))
                                                .toBox
                                                .map(_.flatten.groupMap(_._1)(_._2))
      n6                                    = System.currentTimeMillis
      _                                     = TimingDebugLoggerPure.logEffect.trace(s"all data fetched for response: ${n6 - n5}ms")

      properties <- req.json
                      .flatMap(j => OptionnalJson.extractJsonArray(j, "properties")(json => extractNodePropertyInfo(json)))
                      .map(_.getOrElse(Nil))

      (inheritedProp: Map[NodeId, List[NodePropertyHierarchy]], nonInheritedProp) <- properties.partition(_.inherited) match {
                                                                                       case (inheritedProp, nonInheritedProp) =>
                                                                                         val propMap = {
                                                                                           nodes.values.groupMapReduce(_.id)(
                                                                                             n => {
                                                                                               n.properties.filter(p => {
                                                                                                 nonInheritedProp.exists(
                                                                                                   _.value == p.name
                                                                                                 )
                                                                                               })
                                                                                             }
                                                                                           )(_ ::: _)
                                                                                         }
                                                                                         if (inheritedProp.isEmpty) {
                                                                                           Full((Map.empty, propMap))
                                                                                         } else {
                                                                                           for {
                                                                                             inheritedProp <-
                                                                                               getNodesPropertiesTree(
                                                                                                 nodes.values,
                                                                                                 inheritedProp.map(_.value)
                                                                                               ).toBox
                                                                                           } yield {
                                                                                             (inheritedProp, propMap)
                                                                                           }
                                                                                         }
                                                                                     }
    } yield {
      val res = JArray(
        nodes.values.toList.map(n => {
          serialize(
            runs.get(n.id).flatten,
            globalMode,
            n,
            nonInheritedProp.get(n.id).getOrElse(Nil),
            inheritedProp.get(n.id).getOrElse(Nil),
            softs.get(n.id).getOrElse(Nil),
            userCompliances.get(n.id),
            systemCompliances.get(n.id)
          )
        })
      )

      val n7 = System.currentTimeMillis
      TimingDebugLoggerPure.logEffect.trace(s"serialized to json: ${n7 - n6}ms")
      res
    }
  }

  def software(req: Req, software: String) = {
    import com.normation.box._

    for {

      optNodeIds <- req.json.flatMap(restExtractor.extractNodeIdsFromJson)

      nodes <- optNodeIds match {
                 case None          => nodeInfoService.getAll().toBox
                 case Some(nodeIds) => nodeInfoService.getNodeInfosSeq(nodeIds).map(_.map(n => (n.id, n)).toMap).toBox
               }
      softs <- nodeFactRepository.getNodesbySofwareName(software).toBox.map(_.toMap)
    } yield {
      JsonResponse(
        JObject(nodes.keySet.toList.flatMap(id => softs.get(id).flatMap(_.version.map(v => JField(id.value, JString(v.value))))))
      )

    }
  }

  def property(req: Req, property: String, inheritedValue: Boolean) = {
    for {
      optNodeIds <- req.json.flatMap(restExtractor.extractNodeIdsFromJson)
      nodes      <- optNodeIds match {
                      case None          => nodeInfoService.getAll().toBox
                      case Some(nodeIds) => nodeInfoService.getNodeInfosSeq(nodeIds).map(_.map(n => (n.id, n)).toMap).toBox
                    }
      propMap     = nodes.values.groupMapReduce(_.id)(n => n.properties.filter(_.name == property))(_ ::: _)

      mapProps: Map[NodeId, List[JValue]] <-
        if (inheritedValue) {
          for {
            inheritedProp <- getNodesPropertiesTree(nodes.values, List(property)).toBox
          } yield {

            import com.normation.rudder.domain.properties.JsonPropertySerialisation._
            inheritedProp.map { case (k, v) => (k, v.map(_.toApiJsonRenderParents)) }
          }
        } else {
          Full(propMap.map { case (k, v) => (k, v.map(_.toJson)) })
        }
    } yield {

      JsonResponse(JObject(nodes.keySet.toList.flatMap(id => mapProps.get(id).toList.flatMap(_.map(p => JField(id.value, p))))))
    }
  }

  import restSerializer._
  def listAcceptedNodes(req: Req) = {
    implicit val prettify = restExtractor.extractPrettify(req.params)
    implicit val action   = "listAcceptedNodes"
    nodeInfoService.getAll().toBox match {
      case Full(nodes) =>
        val acceptedNodes = nodes.values.map(serializeNodeInfo(_, "accepted"))
        toJsonResponse(None, ("nodes" -> JArray(acceptedNodes.toList)))

      case eb: EmptyBox =>
        val message = (eb ?~ ("Could not fetch accepted Nodes")).msg
        toJsonError(None, message)
    }
  }

  def acceptedNodeDetails(req: Req, id: NodeId) = {
    implicit val prettify = restExtractor.extractPrettify(req.params)
    implicit val action   = "acceptedNodeDetails"
    nodeInfoService.getNodeInfo(id).toBox match {
      case Full(Some(info)) =>
        val node = serializeNodeInfo(info, "accepted")
        toJsonResponse(None, ("nodes" -> JArray(List(node))))
      case Full(None)       =>
        toJsonError(None, s"Could not find accepted Node ${id.value}")
      case eb: EmptyBox =>
        val message = (eb ?~ s"Could not find accepted Node ${id.value}").messageChain
        toJsonError(None, message)
    }
  }

  def pendingNodeDetails(nodeId: NodeId, prettifyStatus: Boolean) = {
    implicit val prettify = prettifyStatus
    implicit val action   = "pendingNodeDetails"
    newNodeManager.listNewNodes.toBox match {
      case Full(pendingNodes) =>
        pendingNodes.filter(_.id == nodeId) match {
          case Seq()        =>
            val message = s"Could not find pending Node ${nodeId.value}"
            toJsonError(None, message)
          case Seq(info)    =>
            val node = serializeServerInfo(info.toSrv, "pending")
            toJsonResponse(None, ("nodes" -> JArray(List(node))))
          case tooManyNodes =>
            val message = s"Too many pending Nodes with same id ${nodeId.value} : ${tooManyNodes.size} "
            toJsonError(None, message)
        }
      case eb: EmptyBox =>
        val message = (eb ?~ s"Could not find pending Node ${nodeId.value}").msg
        toJsonError(None, message)
    }
  }

  def listPendingNodes(req: Req) = {
    implicit val prettify = restExtractor.extractPrettify(req.params)
    implicit val action   = "listPendingNodes"
    newNodeManager.listNewNodes.toBox match {
      case Full(cnfs) =>
        val pendingNodes = cnfs.map(cnf => serializeServerInfo(cnf.toSrv, "pending")).toList
        toJsonResponse(None, ("nodes" -> JArray(pendingNodes)))

      case eb: EmptyBox =>
        val message = (eb ?~ ("Could not fetch pending Nodes")).msg
        toJsonError(None, message)
    }
  }

  def modifyStatusFromAction(
      ids:       Seq[NodeId],
      action:    NodeStatusAction
  )(implicit cc: ChangeContext): Box[List[JValue]] = {
    def actualNodeDeletion(id: NodeId)(implicit cc: ChangeContext) = {
      for {
        optInfo <- nodeInfoService.getNodeInfo(id).toBox
        info    <- optInfo match {
                     case None    => Failure(s"Can not removed the node with id '${id.value}' because it was not found")
                     case Some(x) => Full(x)
                   }
        remove  <- removeNodeService.removeNode(info.id)
      } yield { serializeNodeInfo(info, "deleted") }
    }

    (action match {
      case AcceptNode =>
        newNodeManager
          .acceptAll(ids)
          .map(_.map(cnf => serializeInventory(NodeFact.fromMinimal(cnf).toFullInventory, "accepted")))

      case RefuseNode =>
        newNodeManager
          .refuseAll(ids)
          .map(_.map(cnf => serializeServerInfo(cnf.toSrv, "refused")))

      case DeleteNode =>
        ZIO.foreach(ids)(actualNodeDeletion(_).toIO)
    }).toBox.map(_.toList)
  }

  def changeNodeStatus(
      nodeIds:          Box[Option[List[NodeId]]],
      nodeStatusAction: Box[NodeStatusAction],
      actor:            EventActor,
      actorIp:          String,
      prettifyStatus:   Boolean
  ) = {
    implicit val prettify = prettifyStatus
    implicit val action   = "changePendingNodeStatus"
    val modId             = ModificationId(uuidGen.newUuid)
    nodeIds match {
      case Full(Some(ids)) =>
        NodeLogger.PendingNode.debug(s" Nodes to change Status : ${ids.mkString("[ ", ", ", " ]")}")
        nodeStatusAction match {
          case Full(nodeStatusAction) =>
            modifyStatusFromAction(ids, nodeStatusAction)(
              ChangeContext(modId, actor, DateTime.now(), None, Some(actorIp), QueryContext.todoQC.nodePerms)
            ) match {
              case Full(result) =>
                toJsonResponse(None, ("nodes" -> JArray(result)))
              case eb: EmptyBox =>
                val message = (eb ?~ ("Error when changing Nodes status")).msg
                toJsonError(None, message)
            }

          case eb: EmptyBox =>
            val fail = eb ?~ "node status needs to be specified"
            toJsonError(None, fail.msg)
        }
      case Full(None)      =>
        val message = "You must add a node id as target"
        toJsonError(None, message)
      case eb: EmptyBox =>
        val message = (eb ?~ ("Error when extracting Nodes' id")).msg
        toJsonError(None, message)
    }
  }

  def getNodeDetails(
      nodeId:      NodeId,
      detailLevel: NodeDetailLevel,
      state:       InventoryStatus
  )(implicit qc:   QueryContext): IOResult[Option[JValue]] = {
    for {
      optNodeInfo <- nodeFactRepository.slowGetCompat(nodeId, state, SelectFacts.fromNodeDetailLevel(detailLevel))
      nodeInfo    <- optNodeInfo match {
                       case None    => None.succeed
                       case Some(x) =>
                         for {
                           runs     <- reportsExecutionRepository.getNodesLastRun(Set(nodeId))
                           inventory = x.toFullInventory
                           software  = x.software.toList.map(_.toSoftware)
                         } yield {
                           Some((x.toNodeInfo, runs, inventory, software))
                         }
                     }
    } yield {
      nodeInfo.map {
        case (node, runs, inventory, software) =>
          val runDate = runs.get(nodeId).flatMap(_.map(_.agentRunId.date))
          serializeInventory(node, state, runDate, Some(inventory), software, detailLevel)
      }
    }
  }

  def nodeDetailsWithStatus(
      nodeId:      NodeId,
      detailLevel: NodeDetailLevel,
      state:       InventoryStatus,
      version:     ApiVersion,
      req:         Req
  ) = {
    implicit val prettify = restExtractor.extractPrettify(req.params)
    implicit val qc       = QueryContext.todoQC
    implicit val action   = s"${state.name}NodeDetails"
    getNodeDetails(nodeId, detailLevel, state).either.runNow match {
      case Right(Some(inventory)) =>
        toJsonResponse(Some(nodeId.value), ("nodes" -> JArray(List(inventory))))
      case Right(None)            =>
        effectiveResponse(
          Some(nodeId.value),
          s"Could not find Node ${nodeId.value} in state '${state.name}'",
          NotFoundError,
          action,
          prettify
        )
      case Left(err)              =>
        val message = s"Error when trying to find Node ${nodeId.value} in state '${state.name}': ${err.fullMsg}"
        toJsonError(Some(nodeId.value), message)
    }
  }

  def nodeDetailsGeneric(nodeId: NodeId, detailLevel: NodeDetailLevel, version: ApiVersion, req: Req) = {
    implicit val prettify = restExtractor.extractPrettify(req.params)
    implicit val qc       = QueryContext.todoQC
    implicit val action   = "nodeDetails"
    (for {
      accepted  <- getNodeDetails(nodeId, detailLevel, AcceptedInventory)
      orPending <- accepted match {
                     case Some(i) => Some(i).succeed
                     case None    => getNodeDetails(nodeId, detailLevel, PendingInventory)
                   }
      orDeleted <- orPending match {
                     case Some(i) => Some(i).succeed
                     case None    => getNodeDetails(nodeId, detailLevel, RemovedInventory)
                   }
    } yield {
      orDeleted match {
        case Some(inventory) =>
          toJsonResponse(Some(nodeId.value), ("nodes" -> JArray(List(inventory))))
        case None            =>
          effectiveResponse(
            Some(nodeId.value),
            s"Node with ID '${nodeId.value}' was not found in Rudder",
            NotFoundError,
            action,
            prettify
          )
      }
    }).either.runNow match {
      case Right(res) => res
      case Left(err)  =>
        val msg = s"An error was encountered when looking for node with ID '${nodeId.value}': ${err.fullMsg}"
        toJsonError(Some(nodeId.value), msg)
    }
  }

  def listNodes(state: InventoryStatus, detailLevel: NodeDetailLevel, nodeFilter: Option[Seq[NodeId]], version: ApiVersion)(
      implicit
      prettify:        Boolean,
      qc:              QueryContext
  ) = {
    implicit val action = s"list${state.name.capitalize}Nodes"
    val predicate       = (n: NodeFact) => {
      (nodeFilter match {
        case Some(ids) => ids.contains(n.id)
        case None      => true
      })
    }

    (for {
      nodeFacts  <-
        nodeFactRepository
          .slowGetAllCompat(state, SelectFacts.fromNodeDetailLevel(detailLevel))
          .filter(predicate)
          .run(ZSink.collectAllToMap[NodeFact, NodeId](_.id)((a, b) => a))
      nodeIds     = nodeFacts.keySet
      runs       <- reportsExecutionRepository.getNodesLastRun(nodeIds)
      inventories = nodeFacts.map { case (k, v) => (k, v.toFullInventory) }
      software    = nodeFacts.map { case (k, v) => (k, v.software.map(_.toSoftware)) }
    } yield {
      for {
        nodeId   <- nodeIds
        nodeFact <- nodeFacts.get(nodeId)
      } yield {
        val runDate = runs.get(nodeId).flatMap(_.map(_.agentRunId.date))
        serializeInventory(
          nodeFact.toNodeInfo,
          state,
          runDate,
          inventories.get(nodeId),
          software.getOrElse(nodeId, Seq()),
          detailLevel
        )
      }
    }).either.runNow match {
      case Right(nodes) => {
        toJsonResponse(None, ("nodes" -> JArray(nodes.toList)))
      }
      case Left(err)    => {
        val message = s"Could not fetch ${state.name} Nodes: ${err.fullMsg}"
        toJsonError(None, message)
      }
    }
  }

  def queryNodes(query: Query, state: InventoryStatus, detailLevel: NodeDetailLevel, version: ApiVersion)(implicit
      prettify:         Boolean,
      qc:               QueryContext
  ) = {
    implicit val action = s"list${state.name.capitalize}Nodes"
    (for {
      nodeIds <- state match {
                   case PendingInventory  => pendingNodeQueryProcessor.check(query, None).toBox
                   case AcceptedInventory => acceptedNodeQueryProcessor.processOnlyId(query)
                   case _                 =>
                     Failure(
                       s"Invalid branch used for nodes query, expected either AcceptedInventory or PendingInventory, got ${state}"
                     )
                 }
    } yield {
      listNodes(state, detailLevel, Some(nodeIds.toSeq), version)
    }) match {
      case Full(resp) => {
        resp
      }
      case eb: EmptyBox => {
        val message = (eb ?~ (s"Could not find ${state.name} Nodes")).msg
        toJsonError(None, message)
      }
    }
  }

  def updateRestNode(nodeId: NodeId, restNode: RestNode)(implicit cc: ChangeContext): IOResult[CoreNodeFact] = {

    def getKeyInfo(restNode: RestNode): (Option[SecurityToken], Option[KeyStatus]) = {

      // if agentKeyValue is present, we set both it and key status.
      // if only agentKey status is present, don't change value.

      (restNode.agentKey, restNode.agentKeyStatus) match {
        case (None, None)       => (None, None)
        case (Some(k), None)    => (Some(k), Some(CertifiedKey))
        case (None, Some(s))    => (None, Some(s))
        case (Some(k), Some(s)) => (Some(k), Some(s))
      }
    }

    def updateNode(
        node:          CoreNodeFact,
        restNode:      RestNode,
        newProperties: List[NodeProperty],
        newKey:        Option[SecurityToken],
        newKeyStatus:  Option[KeyStatus]
    ): CoreNodeFact = {
      import com.softwaremill.quicklens._

      node
        .modify(_.properties)
        .setTo(Chunk.fromIterable(newProperties))
        .modify(_.rudderSettings.policyMode)
        .using(current => restNode.policyMode.getOrElse(current))
        .modify(_.rudderSettings.state)
        .using(current => restNode.state.getOrElse(current))
        .modify(_.rudderAgent.securityToken)
        .setToIfDefined(newKey)
        .modify(_.rudderSettings.keyStatus)
        .setToIfDefined(newKeyStatus)
    }

    implicit val qc    = cc.toQuery
    implicit val attrs = SelectFacts.none

    for {
      nodeFact      <- nodeFactRepository.get(nodeId).notOptional(s"node with id '${nodeId.value}' was not found")
      newProperties <- CompareProperties.updateProperties(nodeFact.properties.toList, restNode.properties).toIO
      keyInfo        = getKeyInfo(restNode)
      updated        = updateNode(nodeFact, restNode, newProperties, keyInfo._1, keyInfo._2)
      _             <- if (CoreNodeFact.same(updated, nodeFact)) ZIO.unit
                       else nodeFactRepository.save(NodeFact.fromMinimal(updated)).unit
    } yield {
      updated
    }
  }

  def remoteRunRequest(nodeId: NodeId, classes: List[String], keepOutput: Boolean, asynchronous: Boolean) = {
    val url     = s"${relayApiEndpoint}/remote-run/nodes/${nodeId.value}"
//    val url = s"http://localhost/rudder/relay-api/remote-run/nodes/${nodeId.value}"
    val params  = {
      ("classes", classes.mkString(",")) ::
      ("keep_output", keepOutput.toString) ::
      ("asynchronous", asynchronous.toString) ::
      Nil
    }
    // We currently bypass verification on certificate
    // We should add an option to allow the user to define a certificate in configuration file
    //
    // We set a 5 minutes timeout (in milliseconds) as remote-runs can be long
    val options = HttpOptions.allowUnsafeSSL :: HttpOptions.readTimeout(5 * 60 * 1000) :: Nil

    Http(url)
      .params(params)
      .options(options)
      .copy(headers = List(("User-Agent", s"rudder/remote run query for node ${nodeId.value}")))
      .postForm
  }

  /*
   * Execute remote run on given node and pipe response to output stream
   */
  def runNode[A](nodeId: NodeId, classes: List[String]): OutputStream => Unit = {
    /*
     * read from in and copy to out
     */
    def copyStreamTo(pipeSize: Int, in: InputStream)(out: OutputStream): Unit = {
      val bytes: Array[Byte] = new Array(pipeSize)
      val zero = 0.toByte
      var read = 0
      try {
        while (read >= 0) { // stop on -1 because end of stream
          Arrays.fill(bytes, zero)
          read = in.read(bytes)
          if (read > 0) {
            out.write(bytes)
            out.flush()
          }
          // do not close os here
        }
      } catch {
        case e: IOException =>
          out.write(s"Error when trying to contact internal remote-run API: ${e.getMessage}".getBytes(StandardCharsets.UTF_8))
          out.flush()
      }
    }

    def errorMessageWithHint(s: String) =
      s"Error occurred when contacting internal remote-run API to apply classes on Node '${nodeId.value}': ${s}"

    // buffer size for file I/O
    val pipeSize = 4096

    val readTimeout = 30.seconds

    val request =
      remoteRunRequest(nodeId, classes, true, false).timeout(connTimeoutMs = 1000, readTimeoutMs = readTimeout.toMillis.toInt)

    // copy will close `os`
    val copy = (os: OutputStream, timeout: Duration) => {
      for {
        _   <- NodeLoggerPure.debug(s"Executing remote run call: ${request.toString}")
        opt <- IOResult.attempt {
                 request.exec {
                   case (status, headers, is) =>
                     NodeLogger.debug(
                       s"Processing remote-run on ${nodeId.value}: HTTP status ${status}"
                     ) // this one is written two times - why ??
                     if (status >= 200 && status < 300) {
                       copyStreamTo(pipeSize, is)(os)
                     } else {
                       val error = errorMessageWithHint(s"(HTTP code ${status})")
                       NodeLogger.error(error)
                       os.write(error.getBytes)
                       os.flush
                     }
                     os.close() // os must be closed here, else is never know that the stream is closed and wait forever
                 }
               }.unit.catchAll {
                 case error @ SystemError(m, ex) =>
                   // special case for "Connection refused": it means that remoteRunRequest is not working
                   val err = ex match {
                     case _: ConnectException =>
                       Unexpected(s"Can not connect to local remote run API (${request.method.toUpperCase}:${request.url})")
                     case _ => error
                   }

                   NodeLoggerPure.error(errorMessageWithHint(err.fullMsg)) *> IOResult.attempt {
                     os.write(errorMessageWithHint(err.msg).getBytes)
                     os.flush
                     os.close()
                   }
               }.timeout(timeout)
        _   <- NodeLoggerPure.debug("Done processing remote run request")
        _   <- opt.notOptional(errorMessageWithHint(s"request timed out after ${(timeout.render)}"))
      } yield ()
    }

    // all
    // we use pipedStream between node answer and our caller answer to decouple a bit the two.
    // A simpler solution would be to directly copy from request.exec input stream to caller out stream.

    (for {
      in  <- IOResult.attempt(new PipedInputStream(pipeSize))
      out <- IOResult.attempt(new PipedOutputStream(in))
      _   <- NodeLoggerPure.trace("remote-run: reading stream from remote API")
      _   <- copy(out, readTimeout).forkDaemon              // read from HTTP request
      res <- IOResult.attempt(copyStreamTo(pipeSize, in) _) // give the writer function waiting for out
      // don't close out here, it was closed inside `copy`
    } yield res).catchAll { err =>
      NodeLoggerPure.error(errorMessageWithHint(err.fullMsg)) *>
      IOResult.attempt(
        copyStreamTo(pipeSize, new ByteArrayInputStream(errorMessageWithHint(err.msg).getBytes(StandardCharsets.UTF_8))) _
      )
    }.runNow
  }

  def runAllNodes(classes: List[String]): Box[JValue] = {

    for {
      nodes <- nodeInfoService.getAll().toBox ?~! s"Could not find nodes informations"

    } yield {
      val res = {
        for {
          node <- nodes.values.toList
        } yield {
          // remote run only works for CFEngine based agent
          val commandResult = {
            if (node.agentsName.exists(a => a.agentType == AgentType.CfeEnterprise || a.agentType == AgentType.CfeCommunity)) {
              val request = remoteRunRequest(node.id, classes, false, true)
              try {
                val result = request.asString
                if (result.isSuccess) {
                  "Started"
                } else {
                  s"An error occurred when applying policy on Node '${node.id.value}', cause is: ${result.body}"
                }
              } catch {
                case ex: ConnectException =>
                  s"Can not connect to local remote run API (${request.method.toUpperCase}:${request.url})"
              }
            } else {
              s"Node with id '${node.id.value}' has an agent type (${node.agentsName.map(_.agentType.displayName).mkString(",")}) which doesn't support remote run"
            }
          }
          (("id" -> node.id.value)
          ~ ("hostname" -> node.hostname)
          ~ ("result"   -> commandResult))
        }
      }
      JArray(res)
    }
  }

  def deleteNode(id: NodeId, actor: EventActor, actorIp: String, prettify: Boolean, mode: DeleteMode) = {
    implicit val p      = prettify
    implicit val action = "deleteNode"
    val modId           = ModificationId(uuidGen.newUuid)

    removeNodeService
      .removeNodePure(id, mode)(ChangeContext(modId, actor, DateTime.now(), None, Some(actorIp), QueryContext.todoQC.nodePerms))
      .toBox match {
      case Full(info) =>
        toJsonResponse(None, ("nodes" -> JArray(restSerializer.serializeNodeInfo(info, "deleted") :: Nil)))

      case eb: EmptyBox =>
        val message = (eb ?~ ("Error when deleting Nodes")).msg
        toJsonError(None, message)
    }
  }

}
