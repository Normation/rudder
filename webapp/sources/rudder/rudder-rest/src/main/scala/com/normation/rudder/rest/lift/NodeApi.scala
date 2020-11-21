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

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.ConnectException
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
import scalaj.http.HttpOptions
import com.normation.rudder.domain.nodes.NodeProperty
import com.normation.box._
import com.normation.zio._
import com.normation.errors._
import com.normation.rudder.domain.logger.NodeLogger
import com.normation.rudder.domain.logger.NodeLoggerPure
import com.normation.rudder.domain.logger.TimingDebugLoggerPure
import com.normation.rudder.domain.nodes.GenericProperty
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.domain.policies.GlobalPolicyMode
import com.normation.rudder.domain.policies.PolicyModeOverrides.Always
import com.normation.rudder.domain.policies.PolicyModeOverrides.Unoverridable
import com.normation.rudder.domain.reports.ComplianceLevel
import com.normation.rudder.domain.reports.NodeStatusReport
import com.normation.rudder.reports.execution.AgentRunWithNodeConfig
import com.normation.rudder.repository.RoNodeGroupRepository
import com.normation.rudder.repository.RoParameterRepository
import com.normation.rudder.repository.json.DataExtractor.CompleteJson
import com.normation.rudder.repository.json.DataExtractor.OptionnalJson
import com.normation.rudder.services.nodes.MergeNodeProperties
import com.normation.rudder.services.servers.DeleteMode
import com.normation.rudder.services.reports.ReportingService
import com.normation.utils.DateFormaterService
import net.liftweb.http.JsonResponse
import net.liftweb.json.JsonAST.JDouble
import net.liftweb.json.JsonAST.JField
import net.liftweb.json.JsonAST.JInt
import net.liftweb.json.JsonAST.JObject
import net.liftweb.json.JsonAST.JString
import zio.ZIO
import zio.duration._





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
  , apiV12              : NodeApiService12
  , apiV13              : NodeApiService13
  , inheritedProperties : NodeApiInheritedProperties
  , deleteDefaultMode   : DeleteMode
) extends LiftApiModuleProvider[API] {

  def schemas = API

  def getLiftEndpoints(): List[LiftApiModule] = {
    API.endpoints.map(e => e match {
      case API.ListPendingNodes         => ListPendingNodes
      case API.NodeDetails              => NodeDetails
      case API.NodeInheritedProperties  => NodeInheritedProperties
      case API.PendingNodeDetails       => PendingNodeDetails
      case API.DeleteNode               => DeleteNode
      case API.ChangePendingNodeStatus  => ChangePendingNodeStatus
      case API.ChangePendingNodeStatus2 => ChangePendingNodeStatus2
      case API.ApplyPolicyAllNodes      => ApplyPocicyAllNodes
      case API.UpdateNode               => UpdateNode
      case API.ListAcceptedNodes        => ListAcceptedNodes
      case API.ApplyPolicy              => ApplyPolicy
      case API.GetNodesStatus           => GetNodesStatus
      case API.NodeDetailsTable         => NodeDetailsTable
      case API.NodeDetailsSoftware      => NodeDetailsSoftware
      case API.NodeDetailsProperty      => NodeDetailsProperty
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

  object NodeInheritedProperties extends LiftApiModule {
    val schema = API.NodeInheritedProperties
    val restExtractor = restExtractorService
    def process(version: ApiVersion, path: ApiPath, id: String, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      inheritedProperties.getNodePropertiesTree(NodeId(id)).either.runNow match {
        case Right(properties) =>
          toJsonResponse(None, properties)("nodeInheritedProperties", params.prettify)
        case Left(err) =>
          toJsonError(None, err.fullMsg)("nodeInheritedProperties", params.prettify)
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

  /*
   * Delete a node.
   * Boolean option "clean" allows to
   */
  object DeleteNode extends LiftApiModule {

    val schema = API.DeleteNode
    val restExtractor = restExtractorService
    def process(version: ApiVersion, path: ApiPath, id: String, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      val pretiffy = restExtractor.extractPrettify(req.params)
      val deleteModeKey = "mode"

      val mode = restExtractor.extractString(deleteModeKey)(req){m =>
        val found = DeleteMode.all.find(_.name == m)
        Full(found.getOrElse(deleteDefaultMode))
      }.map(_.getOrElse(deleteDefaultMode)).getOrElse(deleteDefaultMode)

      apiV12.deleteNode(NodeId(id), authzToken.actor, pretiffy, mode)
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
    val schema = API.ApplyPolicyAllNodes
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
          val fail = eb ?~! s"An error occurred when applying policy on all Nodes"
          toJsonError(None, fail.messageChain)
        }
      }
    }
  }

  object ApplyPolicy extends LiftApiModule {
    val schema = API.ApplyPolicy
    val restExtractor = restExtractorService
    def process(version: ApiVersion, path: ApiPath, id: String, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      implicit val prettify = params.prettify
      (for {
        classes <- restExtractorService.extractList("classes")(req)(json => Full(json))
        optNode <- apiV2.nodeInfoService.getNodeInfo(NodeId(id))
      } yield {
        optNode match {
          case Some(node) if(node.agentsName.exists(a => a.agentType == AgentType.CfeCommunity || a.agentType == AgentType.CfeEnterprise)) =>
            OutputStreamResponse(apiV8service.runNode(node.id, classes))
          case Some(node) =>
            toJsonError(None, s"Node with id '${id}' has an agent type (${node.agentsName.map(_.agentType.displayName).mkString(",")}) which doesn't support remote run")("applyPolicy", prettify)
          case None =>
            toJsonError(None, s"Node with id '${id}' was not found")("applyPolicy", prettify)
        }
      }) match {
        case Full(response) => response
        case eb : EmptyBox => {
          implicit val prettify = params.prettify
          implicit val action = "applyPolicy"
          val fail = eb ?~! s"An error occurred when applying policy on Node '${id}'"
          toJsonError(Some(id), fail.messageChain)

        }
      }
    }
  }

  object GetNodesStatus extends LiftApiModule0 {
    val schema = API.GetNodesStatus
    val restExtractor = restExtractorService
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      implicit val action = "getNodeStatus"
      implicit val prettify = params.prettify
      def errorMsg(ids: List[String]) = s"Error when trying to get status for nodes with IDs '${ids.mkString(",")}''"
      (for {
        ids      <- (restExtractorService.extractString("ids")(req)(ids => Full(ids.split(",").map(_.trim)))).map(_.map(_.toList).getOrElse(Nil)) ?~! "Error: 'ids' parameter not found"
        accepted <- apiV2.nodeInfoService.getAll().map(_.keySet.map(_.value)) ?~! errorMsg(ids)
        pending  <- apiV2.nodeInfoService.getPendingNodeInfos().map(_.keySet.map(_.value)) ?~! errorMsg(ids)
        deleted  <- apiV2.nodeInfoService.getDeletedNodeInfos().map(_.keySet.map(_.value)) ?~! errorMsg(ids)
      } yield {
        val array = ids.map { id =>
          val status = {
            if(accepted.contains(id))     AcceptedInventory.name
            else if(pending.contains(id)) PendingInventory.name
            else if(deleted.contains(id)) "deleted" // RemovedInventory would output "removed" which is inconsistant with other API
            else                          "unknown"
          }
          JObject(JField("id", id) :: JField("status", status) :: Nil)
        }
        JObject(JField("nodes", JArray(array))::Nil)
      }) match {
        case Full(jarray) =>
          toJsonResponse(None, jarray)
        case eb : EmptyBox => {
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
    val schema = API.NodeDetailsTable
    val restExtractor = restExtractorService
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      (for {
        nodes <- apiV13.listNodes(req)
      } yield {
        JsonResponse(nodes)
      }) match {
        case Full(res) => res
        case eb: EmptyBox =>  JsonResponse(JObject(JField("error", (eb ?~! "An error occurred while getting node details").messageChain)))
      }
    }
  }

  object NodeDetailsSoftware extends LiftApiModule {
    val schema = API.NodeDetailsSoftware
    val restExtractor = restExtractorService
    def process(version: ApiVersion, path: ApiPath, software: String, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      (for {
        response <- apiV13.software(req, software)
      } yield {
        response
      }) match {
        case Full(res) => res
        case eb: EmptyBox =>  JsonResponse(JObject(JField("error", (eb ?~! s"An error occurred while fetching verions of '${software}' software for nodes").messageChain)))
      }
    }
  }
  object NodeDetailsProperty extends LiftApiModule {
    val schema = API.NodeDetailsProperty
    val restExtractor = restExtractorService
    def process(version: ApiVersion, path: ApiPath, property: String, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      (for {
        inheritedProperty <- req.json.flatMap(j => OptionnalJson.extractJsonBoolean(j, "inheritedValue"))
        response <- apiV13.property(req, property, inheritedProperty.getOrElse(false))
      } yield {
        response
      }) match {
        case Full(res) => res
        case eb: EmptyBox =>  JsonResponse(JObject(JField("error", (eb ?~! s"An error occurred while getting value of property '${property}' for nodes").messageChain)))
      }
    }
  }
}

class NodeApiInheritedProperties(
    infoService: NodeInfoService
  , groupRepo  : RoNodeGroupRepository
  , paramRepo  : RoParameterRepository
) {

  /*
   * Full list of node properties, including inherited ones for a node
   */
  def getNodePropertiesTree(nodeId: NodeId): IOResult[JValue] = {
    for {
      nodeInfo     <- infoService.getNodeInfoPure(nodeId).notOptional(s"Node with ID '${nodeId.value}' was not found.'")
      groups       <- groupRepo.getFullGroupLibrary()
      nodeTargets  =  groups.getTarget(nodeInfo).map(_._2).toList
      params       <- paramRepo.getAllGlobalParameters()
      properties   <- MergeNodeProperties.forNode(nodeInfo, nodeTargets, params.map(p => (p.name, p)).toMap).toIO
    } yield {
      import com.normation.rudder.domain.nodes.JsonPropertySerialisation._
      JArray((
        ("nodeId"     -> nodeId.value)
      ~ ("properties" -> properties.toApiJsonRenderParents)
      ) :: Nil)
    }
  }

}

class NodeApiService12(
    removeNodeService  : RemoveNodeService
  , uuidGen            : StringUuidGenerator
  , restSerializer     : RestDataSerializer
) {

  def deleteNode(id: NodeId, actor: EventActor, prettify: Boolean, mode: DeleteMode) = {
    implicit val p = prettify
    implicit val action = "deleteNode"
    val modId = ModificationId(uuidGen.newUuid)

    removeNodeService.removeNodePure(id, mode, modId, actor).toBox match {
      case Full(info) =>
        toJsonResponse(None, ( "nodes" -> JArray(restSerializer.serializeNodeInfo(info, "deleted") :: Nil)))

      case eb: EmptyBox => val message = (eb ?~ ("Error when deleting Nodes")).msg
        toJsonError(None, message)
    }
  }
}

class NodeApiService13 (
    nodeInfoService           : NodeInfoService
  , reportsExecutionRepository: RoReportsExecutionRepository
  , readOnlySoftwareDAO       : ReadOnlySoftwareDAO
  , restExtractor             : RestExtractorService
  , getGlobalMode             : () => Box[GlobalPolicyMode]
  , reportingService          : ReportingService
  , groupRepo                 : RoNodeGroupRepository
  , paramRepo                 : RoParameterRepository
) extends Loggable {

  /*
   * Return a map of (NodeId -> propertyName -> inherited property) for the given list of nodes and
   * property. When a node doesn't have a property, the map will always
   */
  def getNodesPropertiesTree(nodeInfos: Iterable[NodeInfo], properties: List[String]): IOResult[Map[NodeId, List[NodeProperty] ]] = {
    for {
      groups       <- groupRepo.getFullGroupLibrary()
      nodesTargets =  nodeInfos.map(i => (i, groups.getTarget(i).map(_._2).toList))
      params       <- paramRepo.getAllGlobalParameters()
      properties <-
          ZIO.foreach(nodesTargets.toList) { case (nodeInfo, nodeTargets) =>
                        MergeNodeProperties.forNode(nodeInfo, nodeTargets, params.map(p => (p.name, p)).toMap).toIO.fold(
                          err =>
                            (nodeInfo.id, nodeInfo.properties.collect { case p if properties.contains(p.name) =>  p })
                        , props =>
                            // here we can have the whole parent hierarchy like in node properties details with p.toApiJsonRenderParents but it needs
                            // adaptation in datatable display
                            (nodeInfo.id, props.collect { case p if properties.contains(p.prop.name) => p.prop })
                        )
                      }
    } yield {
      properties.toMap
    }
  }

  def serialize(
      agentRunWithNodeConfig: Option[AgentRunWithNodeConfig]
    , globalPolicyMode      : GlobalPolicyMode
    , nodeInfo              : NodeInfo
    , properties            : List[ NodeProperty]
    , softs                 : List[Software]
    , compliance            : Option[NodeStatusReport]
    , sysCompliance         : Option[NodeStatusReport]
  ): JObject = {
    import net.liftweb.json.JsonDSL._
    def toComplianceArray(comp : ComplianceLevel) : JArray =
      JArray (
        JArray(JInt(comp.reportsDisabled) :: JDouble(comp.pc.reportsDisabled) :: Nil)  :: //0
        JArray(JInt(comp.notApplicable) :: JDouble(comp.pc.notApplicable) :: Nil ) ::       //  1
        JArray(JInt(comp.success) :: JDouble(comp.pc.success) :: Nil ) ::         //  2
        JArray(JInt(comp.repaired) :: JDouble(comp.pc.repaired) :: Nil ) ::            //  3
        JArray(JInt(comp.error) :: JDouble(comp.pc.error) :: Nil ) ::               //  4
        JArray(JInt(comp.pending) :: JDouble(comp.pc.pending) :: Nil ) ::             //  5
        JArray(JInt(comp.noAnswer) :: JDouble(comp.pc.noAnswer) :: Nil ) ::            //  6
        JArray(JInt(comp.missing) :: JDouble(comp.pc.missing) :: Nil ) ::             //  7
        JArray(JInt(comp.unexpected) :: JDouble(comp.pc.unexpected) :: Nil ) ::          //  8
        JArray(JInt(comp.auditNotApplicable) :: JDouble(comp.pc.auditNotApplicable) :: Nil ) ::  //  9
        JArray(JInt(comp.compliant) :: JDouble(comp.pc.compliant) :: Nil ) ::           // 10
        JArray(JInt(comp.nonCompliant) :: JDouble(comp.pc.nonCompliant) :: Nil ) ::        // 11
        JArray(JInt(comp.auditError) :: JDouble(comp.pc.auditError) :: Nil ) ::          // 12
        JArray(JInt(comp.badPolicyMode) :: JDouble(comp.pc.badPolicyMode) :: Nil ) :: Nil       // 13
      )

    val userCompliance = compliance.map(c => toComplianceArray(ComplianceLevel.sum(c.reports.toSeq.map(_.compliance))))
    val (policyMode,explanation) =
      (globalPolicyMode.overridable,nodeInfo.policyMode) match {
        case (Always,Some(mode)) =>
          (mode,"override")
        case (Always,None) =>
          (globalPolicyMode.mode, "default")
        case (Unoverridable,_) =>
          (globalPolicyMode.mode, "none")
      }
    (  ("name" -> nodeInfo.hostname)
      ~  ("policyServerId" -> nodeInfo.policyServerId.value)
      ~  ("policyMode" -> policyMode.name)
      ~  ("globalModeOverride" -> explanation)
      ~  ("kernel" -> nodeInfo.osDetails.kernelVersion.value)
      ~  ("agentVersion" -> nodeInfo.agentsName.headOption.flatMap(_.version.map(_.value)))
      ~  ("id" -> nodeInfo.id.value)
      ~  ("ram" -> nodeInfo.ram.map(_.toStringMo))
      ~  ("machineType" -> nodeInfo.machine.map(_.machineType.toString))
      ~  ("os" -> nodeInfo.osDetails.fullName)
      ~  ("state" -> nodeInfo.state.name)
      ~  ("compliance" -> userCompliance )
      ~  ("systemError" -> sysCompliance.map(_.compliance.compliance < 100 ).getOrElse(true) )
      ~  ("ipAddresses" -> nodeInfo.ips.filter(ip => ip != "127.0.0.1" && ip != "0:0:0:0:0:0:0:1"))
      ~  ("lastRun" -> agentRunWithNodeConfig.map(d => DateFormaterService.getDisplayDate(d.agentRunId.date)).getOrElse("Never"))
      ~  ("lastInventory" ->  DateFormaterService.getDisplayDate(nodeInfo.inventoryDate))
      ~  ("software" -> JObject(softs.map(s => JField(s.name.getOrElse(""), JString(s.version.map(_.value).getOrElse("N/A"))))))
      ~  ("property" -> JObject(properties.map(s => JField(s.name, GenericProperty.toJsonValue(s.value)))))
      )
  }

  def listNodes(req: Req) = {
    import com.normation.box._
    val n1 = System.currentTimeMillis

    case class PropertyInfo ( value : String, inherited : Boolean )

    def extractNodePropertyInfo(json: JValue) = {
      for {
        value <- CompleteJson.extractJsonString(json, "value")
        inherited <- CompleteJson.extractJsonBoolean(json, "inherited")
      } yield {
        PropertyInfo(value,inherited)
      }
    }

    for {
      optNodeIds      <- req.json.flatMap(j => OptionnalJson.extractJsonListString(j, "nodeIds",( values => Full(values.map(NodeId(_))))))
      nodes           <- optNodeIds match {
                           case None =>
                             nodeInfoService.getAll()
                           case Some(nodeIds) =>
                             com.normation.utils.Control.sequence(nodeIds)(
                               nodeInfoService.getNodeInfo(_).map(_.map(n => (n.id, n)))
                             ).map(_.flatten.toMap)
                         }
      n2              =  System.currentTimeMillis
      _               =  TimingDebugLoggerPure.logEffect.trace(s"Getting node infos: ${n2 - n1}ms")
      runs            <- reportsExecutionRepository.getNodesLastRun(nodes.keySet)
      n3              =  System.currentTimeMillis
      _               =  TimingDebugLoggerPure.logEffect.trace(s"Getting run infos: ${n3 - n2}ms")
      (systemCompliances, userCompliances) <- reportingService.getUserAndSystemNodeStatusReports(Some(nodes.keySet))
      n4              =  System.currentTimeMillis
      _               =  TimingDebugLoggerPure.logEffect.trace(s"Getting compliance infos: ${n4 - n3}ms")
      globalMode      <- getGlobalMode()
      n5              =  System.currentTimeMillis
      _               =  TimingDebugLoggerPure.logEffect.trace(s"Getting global mode: ${n5 - n4}ms")
      softToLookAfter <- req.json.flatMap(j => OptionnalJson.extractJsonListString(j, "software").map(_.getOrElse(Nil)))
      softs           <- ZIO.foreach(softToLookAfter) {
                           soft => readOnlySoftwareDAO.getNodesbySofwareName(soft)
                         }.toBox.map(_.flatten.groupMap(_._1)(_._2))
      n6              =  System.currentTimeMillis
      _               =  TimingDebugLoggerPure.logEffect.trace(s"all data fetched for response: ${n6 - n5}ms")

      properties     <- req.json.flatMap(j => OptionnalJson.extractJsonArray(j, "properties")(json => extractNodePropertyInfo(json)  )).map(_.getOrElse(Nil))

      mapProps       <- properties.partition(_.inherited) match {
        case (inheritedProp, nonInheritedProp) =>
          val propMap = nodes.values.groupMapReduce(_.id)(n =>  n.properties.filter(p => nonInheritedProp.exists(_.value == p.name)))(_ ::: _)
          if (inheritedProp.isEmpty) {
            Full(propMap)
          } else {
            for {
              inheritedProp <- getNodesPropertiesTree(nodes.values, inheritedProp.map(_.value)).toBox
            } yield {
              inheritedProp.foldLeft(propMap){
                case (acc, (id, props)) => acc.updated(id, acc.getOrElse(id, Nil) ::: props)
              }
            }
          }
      }
    } yield {
      val res = JArray(nodes.values.toList.map(n =>
        serialize(runs.get(n.id).flatten,globalMode,n, mapProps.get(n.id).getOrElse(Nil), softs.get(n.id).getOrElse(Nil), userCompliances.get(n.id), systemCompliances.get(n.id))
      ))

      val n7 = System.currentTimeMillis
      TimingDebugLoggerPure.logEffect.trace(s"serialized to json: ${n7 - n6}ms")
      res
    }
  }

  def software(req : Req, software : String) = {
    import com.normation.box._

    for {

      optNodeIds <- req.json.flatMap(restExtractor.extractNodeIdsFromJson)

      nodes <- optNodeIds match {
        case None => nodeInfoService.getAll()
        case Some(nodeIds) => com.normation.utils.Control.sequence(nodeIds)(nodeInfoService.getNodeInfo(_).map(_.map(n => (n.id, n)))).map(_.flatten.toMap)
      }
      softs <- readOnlySoftwareDAO.getNodesbySofwareName(software).toBox.map(_.toMap)
    } yield {
      JsonResponse(JObject(nodes.keySet.toList.flatMap(id => softs.get(id).flatMap(_.version.map(v => JField(id.value, JString(v.value)))))))

    }
  }

  def property(req : Req, property : String, inheritedValue : Boolean) = {
    for {
      optNodeIds <- req.json.flatMap(restExtractor.extractNodeIdsFromJson)
      nodes      <- optNodeIds match {
                      case None => nodeInfoService.getAll()
                      case Some(nodeIds) => com.normation.utils.Control.sequence(nodeIds)(nodeInfoService.getNodeInfo(_).map(_.map(n => (n.id, n)))).map(_.flatten.toMap)
                    }
      propMap = nodes.values.groupMapReduce(_.id)(n =>  n.properties.filter(_.name == property))(_ ::: _)

      mapProps   <-
            if (inheritedValue) {
              Full(propMap)
            } else {
              for {
                inheritedProp <- getNodesPropertiesTree(nodes.values, List(property)).toBox
              } yield {
                propMap ++ inheritedProp
              }
            }
    } yield {
      JsonResponse(JObject(nodes.keySet.toList.flatMap(id => mapProps.get(id).toList.flatMap(_.map(p => JField(id.value, GenericProperty.toJsonValue(p.value)))))))
    }
  }
}


class NodeApiService2 (
    newNodeManager     : NewNodeManager
  , val nodeInfoService: NodeInfoService
  , removeNodeService  : RemoveNodeService
  , uuidGen            : StringUuidGenerator
  , restExtractor      : RestExtractorService
  , restSerializer     : RestDataSerializer
) extends Loggable {

  import restSerializer._
  def listAcceptedNodes (req : Req) = {
    implicit val prettify = restExtractor.extractPrettify(req.params)
    implicit val action = "listAcceptedNodes"
      nodeInfoService.getAll() match {
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

  def getNodeDetails(nodeId: NodeId, detailLevel: NodeDetailLevel, state: InventoryStatus, version : ApiVersion): Box[Option[JValue]] = {
    for {
      optNodeInfo <- state match {
                    case AcceptedInventory => nodeInfoService.getNodeInfo(nodeId)
                    case PendingInventory  => nodeInfoService.getPendingNodeInfo(nodeId)
                    case RemovedInventory  => nodeInfoService.getDeletedNodeInfo(nodeId)
                  }
      nodeInfo    <- optNodeInfo match {
                       case None    => Full(None)
                       case Some(x) =>
                         for {
                           runs      <- roAgentRunsRepository.getNodesLastRun(Set(nodeId))
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
                           Some((x, runs, inventory, software))
                         }
                     }
     } yield {
      nodeInfo.map { case (node, runs, inventory, software) =>
        val runDate = runs.get(nodeId).flatMap( _.map(_.agentRunId.date))
        serializeInventory(node, state, runDate, inventory, software, detailLevel, version) }
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
    (for {
      accepted  <- getNodeDetails(nodeId, detailLevel, AcceptedInventory, version)
      orPending <- accepted match {
                     case Some(i) => Full(Some(i))
                     case None    => getNodeDetails(nodeId, detailLevel, PendingInventory, version)
                   }
      orDeleted <- orPending match {
                     case Some(i) => Full(Some(i))
                     case None    => getNodeDetails(nodeId, detailLevel, RemovedInventory, version)
                   }
    } yield {
       orDeleted match {
         case Some(inventory) =>
           toJsonResponse(Some(nodeId.value), ( "nodes" -> JArray(List(inventory))))
         case None =>
           toJsonError(Some(nodeId.value), s"Node with ID '${nodeId.value}' was not found in Rudder")
       }
    }) match {
      case Full(res)   => res
      case eb:EmptyBox =>
        val msg = (eb ?~! s"An error was encountered when looking for node with ID '${nodeId.value}'").messageChain
        toJsonError(Some(nodeId.value), msg)
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
        nodeIds <-  acceptedNodeQueryProcessor.processOnlyId(query)
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

    def updateNode(node: Node, restNode: RestNode, newProperties: List[NodeProperty]): Node = {
      import com.softwaremill.quicklens._

      (node
        .modify(_.properties).setTo(newProperties)
        .modify(_.policyMode).using(current => restNode.policyMode.getOrElse(current))
        .modify(_.state).using(current => restNode.state.getOrElse(current))
      )
    }

    for {
      node           <- nodeInfoService.getNodeInfo(nodeId).flatMap( _.map( _.node ))
      newProperties  <- CompareProperties.updateProperties(node.properties, restNode.properties).toBox
      updated        =  updateNode(node, restNode, newProperties)
      keyInfo        =  getKeyInfo(restNode)
      saved          <- if(updated == node) Full(node)
                        else nodeRepository.updateNode(updated, modId, actor, reason).toBox
      keyChanged     =  keyInfo._1.isDefined || keyInfo._2.isDefined
      keys           <- if(keyChanged) {
                           nodeRepository.updateNodeKeyInfo(node.id, keyInfo._1, keyInfo._2, modId, actor, reason).toBox
                        } else Full(())
    } yield {
      if(node != updated || keyChanged) {
        asyncRegenerate ! AutomaticStartDeployment(ModificationId(uuidGen.newUuid), userService.getCurrentUser.actor)
      }
      saved
    }
  }

  def remoteRunRequest(nodeId: NodeId, classes : List[String], keepOutput : Boolean, asynchronous : Boolean) = {
    val url = s"${relayApiEndpoint}/remote-run/nodes/${nodeId.value}"
//    val url = s"http://localhost/rudder/relay-api/remote-run/nodes/${nodeId.value}"
    val params =
      ( "classes"     , classes.mkString(",") ) ::
      ( "keep_output" , keepOutput.toString   ) ::
      ( "asynchronous", asynchronous.toString ) ::
      Nil
    // We currently bypass verification on certificate
    // We should add an option to allow the user to define a certificate in configuration file
    val options = HttpOptions.allowUnsafeSSL :: Nil

    Http(url).params(params).options(options).copy(headers = List(("User-Agent", s"rudder/remote run query for node ${nodeId.value}"))).postForm
  }

  /*
   * Execute remote run on given node and pipe response to output stream
   */
  def runNode[A](nodeId: NodeId, classes : List[String]): OutputStream => Unit = {
    /*
     * read from in and copy to out
     */
    def copyStreamTo(pipeSize: Int, in : InputStream)(out: OutputStream ): Unit=  {
      val bytes : Array[Byte] = new Array(pipeSize)
      val zero = 0.toByte
      var read = 0
      try {
        while (read >= 0) { // stop on -1 because end of stream
          Arrays.fill(bytes,zero)
          read = in.read(bytes)
          if(read > 0) {
            out.write(bytes)
            out.flush()
          }
          // do not close os here
        }
      } catch {
        case e : IOException =>
          out.write(s"Error when trying to contact internal remote-run API: ${e.getMessage}".getBytes(StandardCharsets.UTF_8))
          out.flush()
      }
    }

    def errorMessageWithHint(s: String) = s"Error occured when contacting internal remote-run API to apply classes on Node '${nodeId.value}': ${s}"

    // buffer size for file I/O
    val pipeSize = 4096

    val readTimeout = 30.seconds

    val request = remoteRunRequest(nodeId, classes, true, false).timeout(connTimeoutMs = 1000, readTimeoutMs = readTimeout.toMillis.toInt)

    // copy will close `os`
    val copy = (os: OutputStream, timeout: Duration) => {
      for {
        _   <- NodeLoggerPure.debug(s"Executing remote run call: ${request.toString}")
        opt <- IOResult.effect {
                request.exec{ case (status,headers,is) =>
                  NodeLogger.debug(s"Processing remore-run on ${nodeId.value}: HTTP status ${status}") // this one is written two times - why ??
                  if (status >= 200 && status < 300) {
                    copyStreamTo(pipeSize, is)(os)
                  } else {
                    val error = errorMessageWithHint(s"(HTTP code ${status})")
                    NodeLogger.error(error)
                    os.write(error.getBytes)
                    os.flush
                  }
                  os.close() //os must be closed here, else is never know that the stream is closed and wait forever
                }
              }.unit.catchAll { case error@SystemError(m, ex) =>
                // special case for "Connection refused": it means that remoteRunRequest is not working
                val err = ex match {
                  case _:ConnectException =>
                    Unexpected(s"Can not connect to local remote run API (${request.method.toUpperCase}:${request.url})")
                  case _ => error
                }

                NodeLoggerPure.error(errorMessageWithHint(err.fullMsg)) *> IOResult.effect {
                  os.write(errorMessageWithHint(err.msg).getBytes)
                  os.flush
                  os.close()
                }
              }.timeout(timeout).provide(ZioRuntime.environment)
        _   <- NodeLoggerPure.debug("Done processing remote run request")
        _   <- opt.notOptional(errorMessageWithHint(s"request timed out after ${(timeout.render)}"))
      } yield ()
    }

    // all
    // we use pipedStream between node answer and our caller answer to decouple a bit the two.
    // A simpler solution would be to directly copy from request.exec input stream to caller out stream.


    (for {
      in  <- IOResult.effect(new PipedInputStream(pipeSize))
      out <- IOResult.effect(new PipedOutputStream(in))
      _   <- NodeLoggerPure.trace("remote-run: reading stream from remote API")
      _   <- copy(out, readTimeout).forkDaemon // read from HTTP request
      res <- IOResult.effect(copyStreamTo(pipeSize, in) _) // give the writter function waiting for out
      // don't close out here, it was closed inside `copy`
    } yield res).catchAll { err =>
      NodeLoggerPure.error(errorMessageWithHint(err.fullMsg)) *>
      IOResult.effect(copyStreamTo(pipeSize, new ByteArrayInputStream(errorMessageWithHint(err.msg).getBytes(StandardCharsets.UTF_8))) _)
    }.runNow
  }

  def runAllNodes(classes : List[String]) : Box[JValue] = {

    for {
      nodes <- nodeInfoService.getAll() ?~! s"Could not find nodes informations"

    } yield {
      val res = {
        for {
          node <- nodes.values.toList
        } yield {
          // remote run only works for CFEngine based agent
          val  commandResult = {
            if(node.agentsName.exists(a => a.agentType == AgentType.CfeEnterprise || a.agentType == AgentType.CfeCommunity)) {
              val request = remoteRunRequest(node.id, classes, false, true)
              try {
                val result = request.asString
                if (result.isSuccess) {
                  "Started"
                } else {
                  s"An error occured when applying policy on Node '${node.id.value}', cause is: ${result.body}"
                }
              } catch {
                case ex: ConnectException =>
                  s"Can not connect to local remote run API (${request.method.toUpperCase}:${request.url})"
              }
            } else {
              s"Node with id '${node.id.value}' has an agent type (${node.agentsName.map(_.agentType.displayName).mkString(",")}) which doesn't support remote run"
            }
          }
           ( ( "id" -> node.id.value)
           ~ ( "hostname" -> node.hostname)
           ~ ( "result"   -> commandResult)
           )
        }
      }
      JArray(res)
    }
  }

}
