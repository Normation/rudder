/*
 *************************************************************************************
 * Copyright 2017 Normation SAS
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
 *
 *************************************************************************************
 */

package com.normation.rudder.rest.lift

import cats.data.Validated.Invalid
import cats.data.Validated.Valid
import cats.data.ValidatedNel
import com.normation.errors.*
import com.normation.eventlog.ModificationId
import com.normation.inventory.domain.*
import com.normation.inventory.ldap.core.InventoryDit
import com.normation.inventory.ldap.core.UUID_ENTRY
import com.normation.ldap.sdk.LDAPConnectionProvider
import com.normation.ldap.sdk.RwLDAPConnection
import com.normation.rudder.api.ApiVersion
import com.normation.rudder.apidata.DefaultDetailLevel
import com.normation.rudder.apidata.JsonQueryObjects.*
import com.normation.rudder.apidata.JsonResponseObjects.*
import com.normation.rudder.apidata.NodeDetailLevel
import com.normation.rudder.apidata.RenderInheritedProperties
import com.normation.rudder.apidata.ZioJsonExtractor
import com.normation.rudder.apidata.implicits.*
import com.normation.rudder.config.ReasonBehavior
import com.normation.rudder.config.UserPropertyService
import com.normation.rudder.domain.NodeDit
import com.normation.rudder.domain.logger.NodeLogger
import com.normation.rudder.domain.logger.NodeLoggerPure
import com.normation.rudder.domain.logger.TimingDebugLoggerPure
import com.normation.rudder.domain.nodes.Node
import com.normation.rudder.domain.nodes.NodeState
import com.normation.rudder.domain.policies.GlobalPolicyMode
import com.normation.rudder.domain.properties.CompareProperties
import com.normation.rudder.domain.properties.FailedNodePropertyHierarchy
import com.normation.rudder.domain.properties.NodeProperty
import com.normation.rudder.domain.properties.NodePropertyHierarchy
import com.normation.rudder.domain.properties.ParentProperty
import com.normation.rudder.domain.properties.PropertyHierarchy
import com.normation.rudder.domain.properties.PropertyHierarchyError
import com.normation.rudder.domain.properties.PropertyHierarchySpecificError
import com.normation.rudder.domain.properties.Visibility.Displayed
import com.normation.rudder.domain.properties.Visibility.Hidden
import com.normation.rudder.domain.queries.Query
import com.normation.rudder.domain.reports.RunAnalysisKind
import com.normation.rudder.facts.nodes.ChangeContext
import com.normation.rudder.facts.nodes.CoreNodeFact
import com.normation.rudder.facts.nodes.NodeFact
import com.normation.rudder.facts.nodes.NodeFactRepository
import com.normation.rudder.facts.nodes.QueryContext
import com.normation.rudder.facts.nodes.SelectFacts
import com.normation.rudder.facts.nodes.SelectNodeStatus
import com.normation.rudder.properties.NodePropertiesService
import com.normation.rudder.properties.PropertiesRepository
import com.normation.rudder.reports.ReportingConfiguration
import com.normation.rudder.reports.execution.AgentRunWithNodeConfig
import com.normation.rudder.reports.execution.RoReportsExecutionRepository
import com.normation.rudder.repository.ldap.LDAPEntityMapper
import com.normation.rudder.rest.ApiModuleProvider
import com.normation.rudder.rest.ApiPath
import com.normation.rudder.rest.AuthzToken
import com.normation.rudder.rest.NodeApi as API
import com.normation.rudder.rest.OneParam
import com.normation.rudder.rest.RudderJsonResponse
import com.normation.rudder.rest.data.*
import com.normation.rudder.rest.data.Creation.CreationError
import com.normation.rudder.rest.data.NodeTemplate.AcceptedNodeTemplate
import com.normation.rudder.rest.data.NodeTemplate.PendingNodeTemplate
import com.normation.rudder.rest.data.Rest.NodeDetails
import com.normation.rudder.rest.data.Validation.NodeValidationError
import com.normation.rudder.rest.implicits.*
import com.normation.rudder.score.GlobalScore
import com.normation.rudder.score.NoDetailsScore
import com.normation.rudder.score.Score
import com.normation.rudder.score.ScoreSerializer
import com.normation.rudder.score.ScoreService
import com.normation.rudder.score.ScoreValue
import com.normation.rudder.services.nodes.NodeInfoService
import com.normation.rudder.services.queries.*
import com.normation.rudder.services.reports.ReportingService
import com.normation.rudder.services.servers.DeleteMode
import com.normation.rudder.services.servers.NewNodeManager
import com.normation.rudder.services.servers.RemoveNodeService
import com.normation.utils.StringUuidGenerator
import com.normation.zio.*
import io.scalaland.chimney.syntax.*
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.ConnectException
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Arrays
import net.liftweb.common.Box
import net.liftweb.common.Failure
import net.liftweb.http.LiftResponse
import net.liftweb.http.OutputStreamResponse
import net.liftweb.http.Req
import scala.collection.MapView
import scalaj.http.Http
import scalaj.http.HttpOptions
import scalaj.http.HttpRequest
import zio.{System as _, *}
import zio.json.JsonEncoder
import zio.stream.ZSink
import zio.syntax.*

/*
 * NodeApi implementation.
 *
 * This must be reworked to note use a "nodeApiService",
 * but make the implementation directly here.
 */
class NodeApi(
    zioJsonExtractor:      ZioJsonExtractor,
    nodePropertiesService: NodePropertiesService,
    nodeApiService:        NodeApiService,
    userPropertyService:   UserPropertyService,
    inheritedProperties:   NodeApiInheritedProperties,
    uuidGen:               StringUuidGenerator,
    deleteDefaultMode:     DeleteMode
) extends LiftApiModuleProvider[API] {

  implicit def reasonBehavior: ReasonBehavior = userPropertyService.reasonsFieldBehavior

  def schemas: ApiModuleProvider[API] = API

  def getLiftEndpoints(): List[LiftApiModule] = {
    API.endpoints.map {
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
      case API.NodeGlobalScore                => GetNodeGlobalScore
      case API.NodeScoreDetails               => GetNodeScoreDetails
      case API.NodeScoreDetail                => GetNodeScoreDetail
    }
  }

  /*
   * Return a Json Object that list available backend,
   * their state of configuration, and what are the current
   * enabled ones.
   */
  object CreateNodes extends LiftApiModule0 { //
    val schema: API.CreateNodes.type = API.CreateNodes
    val restExtractor = zioJsonExtractor

    import com.normation.rudder.rest.data.Rest.JsonCodecNodeDetails.*
    import zio.json.*

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      import com.softwaremill.quicklens.*
      implicit val prettify: Boolean = params.prettify
      val result = (for {
        json  <- (if (req.json_?) req.body else Failure("This API only Accept JSON request")).toIO
        nodes <- new String(json, StandardCharsets.UTF_8).fromJson[List[NodeDetails]].toIO
        _     <- NodeLoggerPure.info(s"API request for creating nodes: [${nodes.map(n => s"${n.id} (${n.status})").mkString("; ")}]")
        res   <- ZIO.foldLeft(nodes)(ResultHolder(Nil, Nil)) {
                   case (res, node) =>
                     nodeApiService.saveNode(node, authzToken.qc, req.remoteAddr).either.map {
                       case Right(id) => res.modify(_.created).using(_ :+ id)
                       case Left(err) => res.modify(_.failed).using(_ :+ ((node.id, err)))
                     }
                 }
        _     <- ZIO.when(res.created.nonEmpty) {
                   NodeLoggerPure.info(s"Nodes successfully created by API: ${res.created.map(_.value).mkString("; ")}")
                 }
        _     <- ZIO.when(res.failed.nonEmpty) {
                   NodeLoggerPure.error(
                     s"Error when creating nodes by API: ${res.failed.map(e => s"${e._1}: ${e._2.errorMsg}").mkString(" ; ")}"
                   )
                 }
      } yield res).chainError("Error when trying to parse node creation request")

      // the result schema depends on result of the operation
      result
        .fold(
          err => {
            LiftApiProcessingLogger.error(err.fullMsg)
            RudderJsonResponse.internalError(None, RudderJsonResponse.ResponseSchema.fromSchema(schema), err.fullMsg)
          },
          one => {
            if (one.failed.nonEmpty) {
              RudderJsonResponse.internalError(None, RudderJsonResponse.ResponseSchema.fromSchema(schema), one, None)
            } else {
              RudderJsonResponse.successOne(RudderJsonResponse.ResponseSchema.fromSchema(schema), one, None)
            }
          }
        )
        .runNow
    }
  }

  object NodeDetails extends LiftApiModule {
    val schema: OneParam = API.NodeDetails
    val restExtractor = zioJsonExtractor
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        id:         String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val qc: QueryContext = authzToken.qc

      implicit val nodeDetailLevelEncoder: JsonEncoder[JRNodeDetailLevel] = {
        com.normation.rudder.apidata.implicits.nodeDetailLevelEncoder
      }

      (for {
        level <- restExtractor.extractNodeDetailLevelFromParams(req.params).chainError("error with node level detail").toIO
        res   <-
          nodeApiService.nodeDetailsGeneric(
            NodeId(id),
            level.map(_.transformInto[NodeDetailLevel]).getOrElse(DefaultDetailLevel)
          )
      } yield {
        res
      }).toLiftResponseOne(params, schema, _ => Some(id))
    }
  }

  object NodeInheritedProperties extends LiftApiModule {
    val schema: OneParam = API.NodeInheritedProperties
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        id:         String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val qc: QueryContext = authzToken.qc

      inheritedProperties
        .getNodePropertiesTree(NodeId(id), RenderInheritedProperties.JSON)
        .map(Chunk(_))
        .toLiftResponseOne(params, schema, _ => None)
    }
  }

  object NodeDisplayInheritedProperties extends LiftApiModule {
    val schema: OneParam = API.NodeDisplayInheritedProperties
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        id:         String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val qc: QueryContext = authzToken.qc

      inheritedProperties
        .getNodePropertiesTree(NodeId(id), RenderInheritedProperties.HTML)
        .map(Chunk(_))
        .toLiftResponseOne(params, schema, _ => None)
    }
  }

  object PendingNodeDetails extends LiftApiModule {
    val schema: OneParam = API.PendingNodeDetails
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        id:         String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val qc: QueryContext = authzToken.qc
      nodeApiService.pendingNodeDetails(NodeId(id)).toLiftResponseOne(params, schema, _ => Some(id))
    }
  }

  /*
   * Delete a node.
   * Boolean option "clean" allows to
   */
  object DeleteNode extends LiftApiModule {

    val schema: OneParam = API.DeleteNode
    val restExtractor = zioJsonExtractor
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        id:         String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val qc: QueryContext = authzToken.qc

      (for {
        optDeleteMode <- restExtractor.extractDeleteMode(req).toIO
        deleteMode     = optDeleteMode.getOrElse(deleteDefaultMode)
        deleted       <- nodeApiService.deleteNode(NodeId(id), deleteMode, Some(req.remoteAddr))
      } yield {
        deleted
      }).chainError("Error when deleting Nodes").toLiftResponseList(params, schema)
    }
  }

  object UpdateNode extends LiftApiModule {
    val schema: OneParam = API.UpdateNode
    val restExtractor = zioJsonExtractor
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        id:         String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {

      (for {
        restNode <- restExtractor.extractUpdateNode(req).toIO
        cc       <- extractReason(restNode).map(
                      ChangeContext(
                        ModificationId(uuidGen.newUuid),
                        authzToken.qc.actor,
                        Instant.now(),
                        _,
                        Some(req.remoteAddr),
                        authzToken.qc.nodePerms
                      )
                    )
        result   <- nodeApiService.updateRestNode(NodeId(id), restNode)(using
                      ChangeContext(
                        ModificationId(uuidGen.newUuid),
                        authzToken.qc.actor,
                        Instant.now(),
                        restNode.reason,
                        Some(req.remoteAddr),
                        authzToken.qc.nodePerms
                      )
                    )
        // await all properties update to guarantee that properties are resolved after node modification
        _        <- nodePropertiesService.updateAll()
      } yield {
        result.transformInto[JRUpdateNode]
      }).chainError(s"An error occurred while updating Node '${id}'")
        .toLiftResponseOne(params, schema, _ => Some(id))
    }
  }

  object ChangePendingNodeStatus extends LiftApiModule0 {
    val schema: API.ChangePendingNodeStatus.type = API.ChangePendingNodeStatus
    val restExtractor = zioJsonExtractor
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      implicit val qc: QueryContext = authzToken.qc
      (for {
        nodeIdStatus <- restExtractor.extractNodeIdStatus(req).toIO.chainError("Node ID or status not correctly sent")
        res          <- nodeApiService.changeNodeStatus(
                          nodeIdStatus.nodeId,
                          nodeIdStatus.status.transformInto[NodeStatusAction],
                          Some(req.remoteAddr)
                        )
      } yield {
        res
      }).chainError("Error when changing Node status").toLiftResponseList(params, schema)
    }
  }

  object ChangePendingNodeStatus2 extends LiftApiModule {
    val schema: OneParam = API.ChangePendingNodeStatus2
    val restExtractor = zioJsonExtractor
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        id:         String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val qc: QueryContext = authzToken.qc
      (for {
        status <- restExtractor.extractNodeStatus(req).toIO.chainError("Node status not correctly sent")
        res    <-
          nodeApiService.changeNodeStatus(List(NodeId(id)), status.status.transformInto[NodeStatusAction], Some(req.remoteAddr))
      } yield {
        res
      }).chainError("Error when changing Node status").toLiftResponseList(params, schema)
    }
  }

  object ListAcceptedNodes extends LiftApiModule0 {
    val schema: API.ListAcceptedNodes.type = API.ListAcceptedNodes
    val restExtractor = zioJsonExtractor
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      implicit val qc:                     QueryContext                   = authzToken.qc
      implicit val nodeDetailLevelEncoder: JsonEncoder[JRNodeDetailLevel] = {
        com.normation.rudder.apidata.implicits.nodeDetailLevelEncoder
      }
      val state = AcceptedInventory
      (for {
        optLevel <-
          restExtractor.extractNodeDetailLevelFromParams(req.params).toIO.chainError("Node detail level not correctly sent")
        level     = optLevel.map(_.transformInto[NodeDetailLevel]).getOrElse(DefaultDetailLevel)
        query    <- restExtractor.extractQueryFromParams(req.params).toIO.chainError("Node query not correctly sent")
        res      <- (query match {
                      case None        => nodeApiService.listNodes(state, level, None)
                      case Some(query) => nodeApiService.queryNodes(query, state, level)

                    }).chainError(s"Could not fetch ${state.name} Nodes")
      } yield {
        res
      }).toLiftResponseList(params, schema)
    }
  }

  object ListPendingNodes extends LiftApiModule0 {
    val schema: API.ListPendingNodes.type = API.ListPendingNodes
    val restExtractor = zioJsonExtractor

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      implicit val qc:                     QueryContext                   = authzToken.qc
      implicit val nodeDetailLevelEncoder: JsonEncoder[JRNodeDetailLevel] = {
        com.normation.rudder.apidata.implicits.nodeDetailLevelEncoder
      }
      val state = PendingInventory
      (for {
        optLevel <-
          restExtractor.extractNodeDetailLevelFromParams(req.params).toIO.chainError("Node detail level not correctly sent")
        level     = optLevel.map(_.transformInto[NodeDetailLevel]).getOrElse(DefaultDetailLevel)
        query    <- restExtractor.extractQueryFromParams(req.params).toIO.chainError("Query for pending nodes not correctly sent")
        res      <- (query match {
                      case None        => nodeApiService.listNodes(state, level, None)
                      case Some(query) => nodeApiService.queryNodes(query, state, level)

                    }).chainError(s"Could not fetch ${state.name} Nodes")
      } yield {
        res
      }).toLiftResponseList(params, schema)
    }
  }

  object ApplyPolicyAllNodes extends LiftApiModule0 {
    val schema: API.ApplyPolicyAllNodes.type = API.ApplyPolicyAllNodes
    val restExtractor = zioJsonExtractor
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      implicit val qc = authzToken.qc

      (for {
        classes  <- restExtractor.extractClasses(req).toIO
        response <- nodeApiService.runAllNodes(classes)
      } yield {
        response
      }).chainError("An error occurred when applying policy on all Nodes").toLiftResponseOne(params, schema, _ => None)
    }
  }

  object ApplyPolicy extends LiftApiModule {
    val schema: OneParam = API.ApplyPolicy
    val restExtractor = zioJsonExtractor
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        id:         String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val prettify = params.prettify
      implicit val qc       = authzToken.qc

      (for {
        classes <- restExtractor.extractClasses(req).toIO
        optNode <- nodeApiService.nodeFactRepository.get(NodeId(id))
      } yield {
        optNode match {
          case Some(node) if (node.rudderAgent.agentType == AgentType.CfeCommunity) =>
            OutputStreamResponse(nodeApiService.runNode(node.id, classes))
          case Some(node)                                                           =>
            RudderJsonResponse
              .internalError(
                None,
                RudderJsonResponse.ResponseSchema.fromSchema(schema),
                s"Node with id '${id}' has an agent type (${node.rudderAgent.agentType.displayName}) which doesn't support remote run"
              )
          case None                                                                 =>
            RudderJsonResponse
              .internalError(
                None,
                RudderJsonResponse.ResponseSchema.fromSchema(schema),
                s"Node with id '${id}' was not found"
              )
        }
      }).chainError(s"An error occurred when applying policy on Node '${id}'").runNow
    }
  }

  object GetNodesStatus extends LiftApiModule0 {
    val schema: API.GetNodesStatus.type = API.GetNodesStatus
    val restExtractor = zioJsonExtractor
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      def errorMsg(ids: List[NodeId]) = s"Error when trying to get status for nodes with IDs '${ids.map(_.value).mkString(",")}''"
      (for {
        ids       <- restExtractor
                       .extractIdsFromParams(req.params)
                       .map(_.getOrElse(Nil).map(NodeId(_)))
                       .toIO
        nodes     <- nodeApiService.nodeFactRepository.getAll()(using authzToken.qc, SelectNodeStatus.Any).chainError(errorMsg(ids))
        nodeStatus = {
          Chunk
            .fromIterable(ids)
            .map(id => {
              JRNodeIdStatus(
                id,
                nodes.get(id).map(_.rudderSettings.status).getOrElse(RemovedInventory).transformInto[JRInventoryStatus]
              )
            })
        }
      } yield {
        nodeStatus
      }).chainError(s"An error occurred when trying to get nodes status")
        .toLiftResponseList(params, schema)
    }
  }

  object GetNodeGlobalScore extends LiftApiModule {
    val schema: API.NodeGlobalScore.type = API.NodeGlobalScore

    def process(
        version:    ApiVersion,
        path:       ApiPath,
        id:         String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      import com.normation.rudder.score.ScoreSerializer.*
      (for {
        score <- nodeApiService.getNodeGlobalScore(NodeId(id))(using authzToken.qc)
      } yield {
        score
      }).toLiftResponseOne(params, schema, _ => Some(id))
    }
  }

  object GetNodeScoreDetails extends LiftApiModule {
    val schema: API.NodeScoreDetails.type = API.NodeScoreDetails

    def process(
        version:    ApiVersion,
        path:       ApiPath,
        id:         String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      import com.normation.rudder.score.ScoreSerializer.*
      import com.normation.rudder.rest.implicits.*
      nodeApiService.getNodeDetailsScore(NodeId(id))(using authzToken.qc).toLiftResponseOne(params, schema, _ => Some(id))
    }
  }

  object GetNodeScoreDetail extends LiftApiModuleString2 {
    val schema: API.NodeScoreDetail.type = API.NodeScoreDetail

    def process(
        version:    ApiVersion,
        path:       ApiPath,
        id:         (String, String),
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      // implicit val action = "getNodeGlobalScore"
      // implicit val prettify = params.prettify
      import com.normation.rudder.score.ScoreSerializer.*
      val (nodeId, scoreId) = id
      (for {
        allDetails <- nodeApiService.getNodeDetailsScore(NodeId(nodeId))(using authzToken.qc)
      } yield {
        allDetails.filter(_.scoreId == scoreId)
      }).toLiftResponseOne(params, schema, _ => Some(nodeId))
    }
  }

  // WARNING : This is a READ ONLY action
  //   No modifications will be performed
  //   read_only user can access this endpoint
  object NodeDetailsTable extends LiftApiModule0 {
    val schema: API.NodeDetailsTable.type = API.NodeDetailsTable
    val restExtractor = zioJsonExtractor
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      implicit val prettify: Boolean      = params.prettify
      implicit val qc:       QueryContext = authzToken.qc

      val result = (for {
        query <- restExtractor.extractNodeIdsSoftwareProperties(req).toIO
        nodes <- nodeApiService.listNodes(query)
      } yield {
        nodes
      }).chainError("An error occurred while getting node details")

      result.either.runNow.fold(
        err => RudderJsonResponse.internalError(None, RudderJsonResponse.ResponseSchema.fromSchema(schema), err.fullMsg),
        RudderJsonResponse.LiftJsonResponse(_, params.prettify, 200)
      )
    }
  }

  object NodeDetailsSoftware extends LiftApiModule {
    val schema: OneParam = API.NodeDetailsSoftware
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        software:   String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      import com.normation.inventory.domain.JsonSerializers.implicits.encoderVersion
      implicit val prettify = params.prettify
      implicit val qc       = authzToken.qc

      val result = (for {
        response <- nodeApiService.software(req, software)
      } yield {
        response
      }).chainError(s"An error occurred while fetching versions of '${software}' software for nodes")

      result.either.runNow.fold(
        err => RudderJsonResponse.internalError(None, RudderJsonResponse.ResponseSchema.fromSchema(schema), err.fullMsg),
        RudderJsonResponse.LiftJsonResponse(_, params.prettify, 200)
      )
    }
  }
  object NodeDetailsProperty extends LiftApiModule {
    val schema: OneParam = API.NodeDetailsProperty
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        property:   String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val prettify = params.prettify
      implicit val qc       = authzToken.qc

      val result = (for {
        inheritedProperty <- zioJsonExtractor.extractNodeInherited(req).toIO
        response          <- nodeApiService.property(req, property, inheritedProperty.getOrElse(false))
      } yield {
        response
      }).chainError(s"An error occurred while getting value of property '${property}' for nodes")

      result.either.runNow.fold(
        err => RudderJsonResponse.internalError(None, RudderJsonResponse.ResponseSchema.fromSchema(schema), err.fullMsg),
        RudderJsonResponse.LiftJsonResponse(_, params.prettify, 200)
      )
    }
  }

  private def extractReason(restNode: JQUpdateNode): IOResult[Option[String]] = {
    import com.normation.rudder.config.ReasonBehavior.*
    (userPropertyService.reasonsFieldBehavior match {
      case Disabled => ZIO.none
      case mode     =>
        val reason = restNode.reason
        (mode: @unchecked) match {
          case Mandatory =>
            reason
              .notOptional("Reason field is mandatory and should be at least 5 characters long")
              .reject {
                case s if s.lengthIs < 5 => Inconsistency("Reason field should be at least 5 characters long")
              }
              .map(Some(_))
          case Optional  => reason.succeed
        }
    }).chainError("There was an error while extracting reason message")
  }
}

class NodeApiInheritedProperties(
    propRepo: PropertiesRepository
) {

  /*
   * Full list of node properties, including inherited ones for a node but
   * without hidden property.
   */
  def getNodePropertiesTree(nodeId: NodeId, renderInHtml: RenderInheritedProperties)(implicit
      qc: QueryContext
  ): IOResult[JRNodeInheritedProperties] = {
    for {
      properties       <- propRepo.getNodeProps(nodeId).notOptional(s"Node or properties with ID '${nodeId.value}' was not found.'")
      propertiesDetails = {
        val success = properties.resolved.collect {
          case p if p.prop.visibility == Displayed => InheritedPropertyStatus.from(p)
        }
        val error   = properties match {
          case f: FailedNodePropertyHierarchy =>
            f.error match {
              case propsErrors: PropertyHierarchySpecificError =>
                // these are individual errors by property that can be resolved and rendered individually
                Chunk.from(propsErrors.propertiesErrors.values.flatMap {
                  case (_, v, p) => v.map(ErrorInheritedPropertyStatus.from(_, p))
                })
              case _:           PropertyHierarchyError         =>
                // we don't know the errored props, it may be all of them and there may be a global status error
                Chunk(GlobalPropertyStatus.fromResolvedNodeProperty(f))
            }
          case _ => Chunk.empty
        }
        success ++ error
      }
    } yield {
      JRNodeInheritedProperties.fromNode(
        nodeId,
        propertiesDetails,
        renderInHtml
      )
    }
  }

}

class NodeApiService(
    ldapConnection:             LDAPConnectionProvider[RwLDAPConnection],
    val nodeFactRepository:     NodeFactRepository,
    propertiesRepo:             PropertiesRepository,
    reportsExecutionRepository: RoReportsExecutionRepository,
    ldapEntityMapper:           LDAPEntityMapper,
    uuidGen:                    StringUuidGenerator,
    nodeDit:                    NodeDit,
    pendingDit:                 InventoryDit,
    acceptedDit:                InventoryDit,
    newNodeManager:             NewNodeManager,
    removeNodeService:          RemoveNodeService,
    restExtractor:              ZioJsonExtractor,
    reportingService:           ReportingService,
    acceptedNodeQueryProcessor: QueryProcessor,
    pendingNodeQueryProcessor:  QueryChecker,
    getGlobalMode:              () => Box[GlobalPolicyMode],
    relayApiEndpoint:           String,
    scoreService:               ScoreService
) {

/// utility functions ///

  /*
   * for a given nodedetails, we:
   * - try to convert it to inventory/node setup info
   * - save inventory
   * - if needed, accept
   * - now, setup node info (property, state, etc)
   */
  def saveNode(nodeDetails: Rest.NodeDetails, qc: QueryContext, actorIp: String): IO[CreationError, NodeId] = {
    def toCreationError(res: ValidatedNel[NodeValidationError, NodeTemplate]) = {
      res match {
        case Invalid(nel) => CreationError.OnValidation(nel).fail
        case Valid(r)     => r.succeed
      }
    }

    implicit val cc: ChangeContext = ChangeContext(
      ModificationId(uuidGen.newUuid),
      qc.actor,
      Instant.now(),
      None,
      Some(actorIp),
      qc.nodePerms
    )

    for {
      _         <- NodeLoggerPure.debug(s"Create node API: validate node template for '${nodeDetails.id}''")
      validated <- toCreationError(Validation.toNodeTemplate(nodeDetails))
      _         <- checkUuid(validated.inventory.node.main.id)
      _         <- NodeLoggerPure.debug(s"Create node API: saving inventory for node ${validated.inventory.node.main.id}")
      created   <- saveInventory(validated.inventory)
      _         <- NodeLoggerPure.debug(s"Create node API: node status should be ${nodeDetails.status}")
      nodeSetup <- accept(validated)
      _         <- NodeLoggerPure.debug(s"Create node API: update node properties and setting if needed")
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
              case None    => con.exists((dit.NODES.NODE: UUID_ENTRY[NodeId]).dn(id)).map(exists => if (exists) Some(s) else None)
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
    import com.softwaremill.quicklens.*

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
        isSystem = false,
        isPolicyServer = false,
        creationDate = Instant.now(),
        nodeReportingConfiguration = ReportingConfiguration(None, None),
        properties = Nil,
        policyMode = None,
        securityTag = None
      )
    }

    (for {
      ldap    <- ldapConnection
      // try t get node
      entry   <- ldap.get(nodeDit.NODES.NODE.dn(id.value), NodeInfoService.nodeInfoAttributes*)
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
      nodeInfos:  MapView[NodeId, CoreNodeFact],
      properties: List[String]
  )(implicit qc: QueryContext): IOResult[Map[NodeId, Chunk[PropertyHierarchy]]] = {
    for {
      properties <-
        ZIO.foreach(nodeInfos.values) { fact =>
          propertiesRepo
            .getNodeProps(fact.id)
            .fold(
              err =>
                (
                  fact.id,
                  fact.properties.collect {
                    case p if properties.contains(p.name) =>
                      NodePropertyHierarchy(fact.id, ParentProperty.Node(fact.fqdn, fact.id, p, None))
                  }
                ),
              optHierarchy => {
                // here we can have the whole parent hierarchy like in node properties details with p.toApiJsonRenderParents but it needs
                // adaptation in datatable display
                (fact.id, optHierarchy.orEmpty.resolved.filter(p => properties.contains(p.prop.name)))
              }
            )
        }
    } yield {
      properties.toMap
    }
  }

  def listNodes(query: JQNodeIdsSoftwareProperties)(implicit qc: QueryContext): IOResult[Chunk[JRNodeDetailTable]] = {

    for {
      n1             <- currentTimeMillis
      nodes          <- query.nodeIds match {
                          case None          =>
                            nodeFactRepository.getAll()
                          case Some(nodeIds) =>
                            nodeFactRepository.getAll().map(_.filterKeys(id => nodeIds.contains(id)))
                        }
      scores         <- scoreService.getAll()
      allScoreId     <- scoreService.getAvailableScore().map(_.map(_._1))
      n2             <- currentTimeMillis
      _              <- TimingDebugLoggerPure.trace(s"Getting node infos: ${n2 - n1}ms")
      runs           <- reportsExecutionRepository.getNodesLastRun(nodes.keySet.toSet)
      n3             <- currentTimeMillis
      _              <- TimingDebugLoggerPure.trace(s"Getting run infos: ${n3 - n2}ms")
      compliance     <- reportingService.getSystemAndUserCompliance(Some(nodes.keySet.toSet))
      n4             <- currentTimeMillis
      _              <- TimingDebugLoggerPure.trace(s"Getting compliance infos: ${n4 - n3}ms")
      globalMode     <- getGlobalMode().toIO
      n5             <- currentTimeMillis
      _              <- TimingDebugLoggerPure.trace(s"Getting global mode: ${n5 - n4}ms")
      softToLookAfter = query.software.getOrElse(List.empty)
      softs          <- ZIO
                          .foreach(softToLookAfter)(soft => nodeFactRepository.getNodesBySoftwareName(soft))
                          .map(_.flatten.groupMap(_._1)(_._2))
      n6             <- currentTimeMillis
      _              <- TimingDebugLoggerPure.trace(s"all data fetched for response: ${n6 - n5}ms")
      properties      = query.properties.getOrElse(List.empty)
      props          <- properties.partition(_.inherited) match {
                          case (inheritedProp, nonInheritedProp) =>
                            val propMap = nodes.values.groupMapReduce(_.id)(n => {
                              n.properties.filter(p => {
                                nonInheritedProp.exists(
                                  _.value == p.name
                                )
                              })
                            })(_ ++ _)

                            if (inheritedProp.isEmpty) {
                              (Map.empty[NodeId, List[PropertyHierarchy]], propMap).succeed
                            } else {
                              for {
                                inheritedProp <- getNodesPropertiesTree(nodes, inheritedProp.map(_.value))
                              } yield {
                                (inheritedProp, propMap)
                              }
                            }
                        }
    } yield {
      val (inheritedProp, nonInheritedProp) = props
      val res                               = {
        Chunk
          .fromIterable(nodes.values)
          .map(n => {
            implicit val globalPolicyMode:       GlobalPolicyMode               = globalMode
            implicit val agentRunWithNodeConfig: Option[AgentRunWithNodeConfig] = runs.get(n.id).flatten
            implicit val properties:             Chunk[NodeProperty]            = Chunk.fromIterable(nonInheritedProp.get(n.id).getOrElse(Nil))
            implicit val inheritedProperties:    Chunk[PropertyHierarchy]       =
              Chunk.fromIterable(inheritedProp.get(n.id).getOrElse(Nil))
            implicit val softwares:              Chunk[Software]                = Chunk.fromIterable(softs.get(n.id).getOrElse(Nil))
            implicit val nodeCompliance:         Option[JRNodeCompliance]       =
              compliance.user.get(n.id).map { case (_, compliance) => JRNodeCompliance(compliance) }
            implicit val runAnalysisKind:        Option[RunAnalysisKind]        = compliance.user.get(n.id).map { case (kind, _) => kind }
            implicit val systemCompliance:       Option[JRNodeSystemCompliance] =
              compliance.system.get(n.id).map(JRNodeSystemCompliance(_))
            implicit val score:                  GlobalScore                    = {
              scores
                .get(n.id)
                .getOrElse(GlobalScore(ScoreValue.NoScore, "", allScoreId.map(s => NoDetailsScore(s, ScoreValue.NoScore, ""))))
            }

            n.transformInto[JRNodeDetailTable]
          })
      }

      val n7 = System.currentTimeMillis
      TimingDebugLoggerPure.logEffect.trace(s"serialized to json: ${n7 - n6}ms")
      res
    }
  }

  def software(req: Req, software: String)(implicit qc: QueryContext): IOResult[Map[String, Version]] = {

    for {
      optNodeIds <- restExtractor.extractNodeIdChunk(req).toIO
      nodes      <- optNodeIds match {
                      case None          => nodeFactRepository.getAll()
                      case Some(nodeIds) => nodeFactRepository.getAll().map(_.filterKeys(id => nodeIds.contains(id.value)))
                    }
      softs      <- nodeFactRepository.getNodesBySoftwareName(software).map(_.toMap)
    } yield {
      nodes.keySet.toList.flatMap(id => softs.get(id).flatMap(_.version.map(v => (id.value, v)))).toMap
    }
  }

  def property(req: Req, property: String, inheritedValue: Boolean)(implicit
      qc: QueryContext
  ): IOResult[Map[String, JRProperty]] = {

    for {
      optNodeIds <- restExtractor.extractNodeIdChunk(req).toIO
      nodes      <- optNodeIds match {
                      case None          => nodeFactRepository.getAll()
                      case Some(nodeIds) => nodeFactRepository.getAll().map(_.filterKeys(id => nodeIds.contains(id.value)))
                    }

      mapProps <- (if (inheritedValue) {
                     getNodesPropertiesTree(nodes, List(property))
                       .map(
                         _.view.mapValues(
                           Chunk
                             .fromIterable(_)
                             // HTML was always being escaped in this node API
                             .map(JRProperty.fromNodePropertyHierarchy(_, RenderInheritedProperties.HTML, escapeHtml = true))
                         )
                       )
                   } else {
                     nodes.values
                       .groupMapReduce(_.id)(n => n.properties.filter(_.name == property))(_ ++ _)
                       .view
                       .mapValues(
                         Chunk.fromIterable(_).map(JRProperty.fromNodeProp(_))
                       )
                       .succeed
                   }): IOResult[MapView[NodeId, Chunk[JRProperty]]]
    } yield {
      // this is unclear why the final map is kept as Chunk above. Combining flatMap here leads to a single JRProperty per node anyway...
      nodes.keySet.toList.flatMap(id => mapProps.get(id).toList.flatMap(_.map(p => (id.value, p)))).sortBy(_._1).toMap
    }
  }

  def pendingNodeDetails(nodeId: NodeId)(implicit qc: QueryContext): IOResult[Chunk[JRNodeInfo]] = {
    implicit val status: InventoryStatus = PendingInventory
    for {
      pendingNodes <- nodeFactRepository.getAll()(using qc, SelectNodeStatus.Pending)
      nodeFact     <- pendingNodes.get(nodeId).notOptional(s"Could not find pending Node ${nodeId.value}")
    } yield {
      Chunk(nodeFact.toNodeInfo.transformInto[JRNodeInfo])
    }
  }

  def modifyStatusFromAction(
      ids:    List[NodeId],
      action: NodeStatusAction
  )(implicit cc: ChangeContext): IOResult[Chunk[JRNodeChangeStatus]] = {
    def actualNodeDeletion(id: NodeId)(implicit cc: ChangeContext) = {
      implicit val status: InventoryStatus = RemovedInventory
      for {
        nodeFact <- nodeFactRepository
                      .get(id)(using cc.toQuery)
                      .notOptional(s"Can not removed the node with id '${id.value}' because it was not found")
        _        <- removeNodeService.removeNode(nodeFact.id)
      } yield {
        nodeFact.toNodeInfo.transformInto[JRNodeChangeStatus]
      }
    }

    (action match {
      case AcceptNode =>
        implicit val status: InventoryStatus = AcceptedInventory
        newNodeManager
          .acceptAll(ids)
          .map(l => Chunk.fromIterable(l.map(cnf => NodeFact.fromMinimal(cnf).toFullInventory.transformInto[JRNodeChangeStatus])))

      case RefuseNode =>
        newNodeManager
          .refuseAll(ids)
          .map(l => Chunk.fromIterable(l.map(cnf => cnf.toSrv.transformInto[JRNodeChangeStatus])))

      case DeleteNode =>
        ZIO.foreach(Chunk.fromIterable(ids))(actualNodeDeletion(_))
    })
  }

  def changeNodeStatus(
      nodeIds:          List[NodeId],
      nodeStatusAction: NodeStatusAction,
      actorIp:          Option[String]
  )(implicit qc: QueryContext): IOResult[Chunk[JRNodeChangeStatus]] = {
    val modId = ModificationId(uuidGen.newUuid)
    for {
      _ <- NodeLogger.PendingNodePure.debug(s" Nodes to change Status : ${nodeIds.mkString("[ ", ", ", " ]")}")

      res <- modifyStatusFromAction(nodeIds, nodeStatusAction)(using
               ChangeContext(modId, qc.actor, Instant.now(), None, actorIp, qc.nodePerms)
             )
    } yield {
      res
    }
  }

  def getNodeDetails(
      nodeId:      NodeId,
      detailLevel: NodeDetailLevel,
      state:       InventoryStatus
  )(implicit qc: QueryContext): IOResult[Option[JRNodeDetailLevel]] = {
    for {
      optNodeFact <- nodeFactRepository.slowGetCompat(nodeId, state, SelectFacts.fromNodeDetailLevel(detailLevel))
      runs        <- ZIO.foreach(optNodeFact)(_ => reportsExecutionRepository.getNodesLastRun(Set(nodeId))).map(_.getOrElse(Map.empty))
    } yield {
      optNodeFact.map { fact =>
        implicit val nodeFact:        NodeFact                       = fact
        implicit val agentRun:        Option[AgentRunWithNodeConfig] = runs.get(nodeId).flatten
        implicit val inventoryStatus: InventoryStatus                = state
        detailLevel.transformInto[JRNodeDetailLevel]
      }
    }
  }

  def nodeDetailsGeneric(nodeId: NodeId, detailLevel: NodeDetailLevel)(implicit
      qc: QueryContext
  ): IOResult[JRNodeDetailLevel] = {
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
      orDeleted
    }).notOptional(s"Node with ID '${nodeId.value}' was not found in Rudder")
      .chainError(s"An error was encountered when looking for node with ID '${nodeId.value}'")
  }

  def listNodes(state: InventoryStatus, detailLevel: NodeDetailLevel, nodeFilter: Option[Seq[NodeId]])(implicit
      qc: QueryContext
  ): IOResult[Chunk[JRNodeDetailLevel]] = {
    val predicate = nodeFilter match {
      case Some(ids) => (n: NodeFact) => ids.contains(n.id)
      case None      => (_: NodeFact) => true
    }

    for {
      nodeFacts <-
        nodeFactRepository
          .slowGetAllCompat(state, SelectFacts.fromNodeDetailLevel(detailLevel))
          .filter(predicate)
          .run(ZSink.collectAllToMap[NodeFact, NodeId](_.id)((a, b) => a))
      runs      <- reportsExecutionRepository.getNodesLastRun(nodeFacts.keySet)
    } yield {
      nodeFacts.toChunk.map {
        case (nodeId, nodeFact) =>
          implicit val agentRun:       Option[AgentRunWithNodeConfig] = runs.get(nodeId).flatten
          implicit val nodeFactValue:  NodeFact                       = nodeFact
          implicit val inventoryState: InventoryStatus                = state
          detailLevel.transformInto[JRNodeDetailLevel]
      }
    }
  }

  def getNodeGlobalScore(nodeId: NodeId)(implicit qc: QueryContext): IOResult[GlobalScore] = {
    scoreService.getGlobalScore(nodeId)
  }

  def getNodeDetailsScore(nodeId: NodeId)(implicit qc: QueryContext): IOResult[List[Score]] = {
    scoreService.getScoreDetails(nodeId)
  }
  def queryNodes(query: Query, state: InventoryStatus, detailLevel: NodeDetailLevel)(implicit
      qc: QueryContext
  ): IOResult[Chunk[JRNodeDetailLevel]] = {
    for {
      nodeIds <- state match {
                   case PendingInventory  => pendingNodeQueryProcessor.check(query, None)
                   case AcceptedInventory => acceptedNodeQueryProcessor.process(query).toIO
                   case _                 =>
                     Inconsistency(
                       s"Invalid branch used for nodes query, expected either AcceptedInventory or PendingInventory, got ${state}"
                     ).fail
                 }
      res     <- listNodes(state, detailLevel, Some(nodeIds.toSeq))
    } yield {
      res
    }
  }

  def updateRestNode(nodeId: NodeId, update: JQUpdateNode)(implicit cc: ChangeContext): IOResult[CoreNodeFact] = {

    def updateNode(
        node:             CoreNodeFact,
        update:           JQUpdateNode,
        newProperties:    List[NodeProperty],
        newKey:           Option[SecurityToken],
        newKeyStatus:     Option[KeyStatus],
        newDocumentation: Option[String]
    ): CoreNodeFact = {
      import com.softwaremill.quicklens.*

      val propNames: Set[String] = newProperties.map(_.name).toSet
      val documentation = newDocumentation match {
        case Some("") => Some(None)
        case Some(x)  => Some(Some(x))
        case None     => None
      }

      node
        .modify(_.properties)
        // we need to keep hidden properties, since the user can't know they are here.
        // Still, if a duplicate key exists, the user provided one wins.
        .setTo(
          Chunk.fromIterable(newProperties) ++ node.properties.filter(p => p.visibility == Hidden && !propNames.contains(p.name))
        )
        .modify(_.rudderSettings.policyMode)
        .using(current => update.policyMode.getOrElse(current))
        .modify(_.rudderSettings.state)
        .using(current => update.state.getOrElse(current))
        .modify(_.rudderAgent.securityToken)
        .setToIfDefined(newKey)
        .modify(_.rudderSettings.keyStatus)
        .setToIfDefined(newKeyStatus)
        .modify(_.documentation)
        .setToIfDefined(documentation)
    }

    implicit val qc: QueryContext = cc.toQuery

    for {
      nodeFact      <- nodeFactRepository.get(nodeId).notOptional(s"node with id '${nodeId.value}' was not found")
      newProperties <- CompareProperties.updateProperties(nodeFact.properties.toList, update.properties).toIO
      updated        = updateNode(nodeFact, update, newProperties, update.keyInfo._1, update.keyInfo._2, update.documentation)
      _             <- if (CoreNodeFact.same(updated, nodeFact)) ZIO.unit
                       else nodeFactRepository.save(updated).unit
    } yield {
      updated
    }
  }

  def remoteRunRequest(nodeId: NodeId, classes: List[String], keepOutput: Boolean, asynchronous: Boolean): HttpRequest = {
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

    val request = {
      remoteRunRequest(nodeId, classes, keepOutput = true, asynchronous = false)
        .timeout(connTimeoutMs = 1000, readTimeoutMs = readTimeout.toMillis.toInt)
    }

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
      _   <- copy(out, readTimeout).forkDaemon            // read from HTTP request
      res <- IOResult.attempt(copyStreamTo(pipeSize, in)) // give the writer function waiting for out
      // don't close out here, it was closed inside `copy`
    } yield res).catchAll { err =>
      NodeLoggerPure.error(errorMessageWithHint(err.fullMsg)) *>
      IOResult.attempt(
        copyStreamTo(pipeSize, new ByteArrayInputStream(errorMessageWithHint(err.msg).getBytes(StandardCharsets.UTF_8)))
      )
    }.runNow
  }

  def runAllNodes(classes: List[String])(implicit qc: QueryContext): IOResult[Chunk[JRNodeIdHostnameResult]] = {

    for {
      nodes <- nodeFactRepository.getAll().chainError("Could not find nodes informations")
    } yield {
      val res = {
        for {
          node <- Chunk.fromIterable(nodes.values)
        } yield {
          // remote run only works for CFEngine based agent
          val commandResult = {
            if (node.rudderAgent.agentType == AgentType.CfeCommunity) {
              val request = remoteRunRequest(node.id, classes, keepOutput = false, asynchronous = true)
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
              s"Node with id '${node.id.value}' has an agent type (${node.rudderAgent.agentType.displayName}) which doesn't support remote run"
            }
          }
          JRNodeIdHostnameResult(node.id, node.fqdn, commandResult)
        }
      }
      res
    }
  }

  def deleteNode(id: NodeId, mode: DeleteMode, actorIp: Option[String])(implicit
      qc: QueryContext
  ): IOResult[Chunk[JRNodeInfo]] = {
    implicit val status: InventoryStatus = RemovedInventory
    val modId = ModificationId(uuidGen.newUuid)

    for {
      info <-
        removeNodeService
          .removeNodePure(id, mode)(using ChangeContext(modId, qc.actor, Instant.now(), None, actorIp, qc.nodePerms))
    } yield {
      Chunk.fromIterable(info.map(_.toNodeInfo.transformInto[JRNodeInfo]))
    }
  }

}
