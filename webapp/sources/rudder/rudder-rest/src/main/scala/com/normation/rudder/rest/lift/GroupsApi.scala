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
 *************************************************************************************
 */

package com.normation.rudder.rest.lift

import com.normation.box.*
import com.normation.errors.*
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.rudder.api.ApiVersion
import com.normation.rudder.apidata.FullDetails
import com.normation.rudder.apidata.JsonQueryObjects.*
import com.normation.rudder.apidata.JsonResponseObjects.*
import com.normation.rudder.apidata.MinimalDetails
import com.normation.rudder.apidata.RenderInheritedProperties
import com.normation.rudder.apidata.RestDataSerializer
import com.normation.rudder.apidata.ZioJsonExtractor
import com.normation.rudder.apidata.implicits.*
import com.normation.rudder.batch.AsyncDeploymentActor
import com.normation.rudder.batch.AutomaticStartDeployment
import com.normation.rudder.domain.nodes.*
import com.normation.rudder.domain.properties.NodePropertyHierarchy
import com.normation.rudder.facts.nodes.ChangeContext
import com.normation.rudder.facts.nodes.NodeFactRepository
import com.normation.rudder.facts.nodes.QueryContext
import com.normation.rudder.repository.CategoryAndNodeGroup
import com.normation.rudder.repository.RoNodeGroupRepository
import com.normation.rudder.repository.RoParameterRepository
import com.normation.rudder.repository.WoNodeGroupRepository
import com.normation.rudder.rest.*
import com.normation.rudder.rest.GroupApi as API
import com.normation.rudder.rest.RestExtractorService
import com.normation.rudder.rest.RestUtils.*
import com.normation.rudder.rest.data.*
import com.normation.rudder.rest.implicits.*
import com.normation.rudder.services.nodes.MergeNodeProperties
import com.normation.rudder.services.queries.CmdbQueryParser
import com.normation.rudder.services.queries.QueryProcessor
import com.normation.rudder.services.workflows.*
import com.normation.rudder.users.UserService
import com.normation.utils.StringUuidGenerator
import com.normation.zio.*
import net.liftweb.common.*
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import net.liftweb.json.*
import net.liftweb.json.JsonDSL.*
import org.joda.time.DateTime
import zio.ZIO
import zio.syntax.*

class GroupsApi(
    readGroup:            RoNodeGroupRepository,
    restExtractorService: RestExtractorService,
    zioJsonExtractor:     ZioJsonExtractor,
    uuidGen:              StringUuidGenerator,
    serviceV2:            GroupApiService2,
    serviceV6:            GroupApiService6,
    serviceV14:           GroupApiService14,
    inheritedProperties:  GroupApiInheritedProperties
) extends LiftApiModuleProvider[API] {

  def schemas: ApiModuleProvider[API] = API

  /*
   * The actual builder for the compliance API.
   * Depends of authz method and supported version.
   */
  def getLiftEndpoints(): List[LiftApiModule] = {
    API.endpoints
      .map(e => {
        e match {
          case API.ListGroups                      => ChooseApi0(List, ListV14)
          case API.GetGroupTree                    => ChooseApi0(GetTree, GetTreeV14)
          case API.GroupDetails                    => ChooseApiN(Get, GetV14)
          case API.GroupInheritedProperties        => ChooseApiN(GroupInheritedProperties, GroupInheritedPropertiesV14)
          case API.GroupDisplayInheritedProperties =>
            ChooseApiN(GroupDisplayInheritedProperties, GroupDisplayInheritedPropertiesV14)
          case API.DeleteGroup                     => ChooseApiN(Delete, DeleteV14)
          case API.CreateGroup                     => ChooseApi0(Create, CreateV14)
          case API.UpdateGroup                     => ChooseApiN(Update, UpdateV14)
          case API.DeleteGroupCategory             => ChooseApiN(DeleteCategory, DeleteCategoryV14)
          case API.CreateGroupCategory             => ChooseApi0(CreateCategory, CreateCategoryV14)
          case API.GetGroupCategoryDetails         => ChooseApiN(GetCategory, GetCategoryV14)
          case API.UpdateGroupCategory             => ChooseApiN(UpdateCategory, UpdateCategoryV14)
          case API.ReloadGroup                     => ChooseApiN(Reload, ReloadV14)
        }
      })
      .toList
  }

  object List extends LiftApiModule0      {
    val schema:                                                                                                API.ListGroups.type = API.ListGroups
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse        = {
      implicit val qc: QueryContext = authzToken.qc
      serviceV14.listGroups().toLiftResponseList(params, schema)
    }
  }
  object Get  extends LiftApiModuleString {
    val schema: API.GroupDetails.type = API.GroupDetails
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        sid:        String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val qc: QueryContext = authzToken.qc
      (for {
        id  <- NodeGroupId.parse(sid).toIO
        res <- serviceV14.groupDetails(id)
      } yield res).toLiftResponseOne(params, schema, s => Some(s.id))
    }
  }

  object GroupInheritedProperties extends LiftApiModuleString {
    val schema: API.GroupInheritedProperties.type = API.GroupInheritedProperties
    val restExtractor = restExtractorService
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        id:         String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      inheritedProperties
        .getNodePropertiesTree(NodeGroupId(NodeGroupUid(id)), RenderInheritedProperties.JSON)
        .either
        .runNow match {
        case Right(value) =>
          toJsonResponse(None, value)("groupInheritedProperties", restExtractor.extractPrettify(req.params))
        case Left(err)    =>
          toJsonError(None, err.fullMsg)("groupInheritedProperties", restExtractor.extractPrettify(req.params))
      }
    }
  }

  object GroupDisplayInheritedProperties extends LiftApiModuleString {
    val schema: API.GroupDisplayInheritedProperties.type = API.GroupDisplayInheritedProperties
    val restExtractor = restExtractorService
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        id:         String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      inheritedProperties
        .getNodePropertiesTree(NodeGroupId(NodeGroupUid(id)), RenderInheritedProperties.HTML)
        .either
        .runNow match {
        case Right(value) =>
          toJsonResponse(None, value)("groupInheritedProperties", restExtractor.extractPrettify(req.params))
        case Left(err)    =>
          toJsonError(None, err.fullMsg)("groupInheritedProperties", restExtractor.extractPrettify(req.params))
      }
    }
  }

  object Delete extends LiftApiModuleString {
    val schema: API.DeleteGroup.type = API.DeleteGroup
    val restExtractor = restExtractorService
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        id:         String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val qc: QueryContext = authzToken.qc
      serviceV2.deleteGroup(id, req, version)
    }
  }

  object Create extends LiftApiModule0      {
    val schema: API.CreateGroup.type = API.CreateGroup
    val restExtractor = restExtractorService
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      implicit val qc: QueryContext = authzToken.qc
      if (req.json_?) {
        req.json match {
          case Full(arg) =>
            val restGroup = restExtractor.extractGroupFromJSON(arg)
            serviceV2.createGroup(restGroup, req, version)
          case eb: EmptyBox =>
            toJsonError(None, "No Json data sent")("createGroup", restExtractor.extractPrettify(req.params))
        }
      } else {
        val restGroup = restExtractor.extractGroup(req.params)
        serviceV2.createGroup(restGroup, req, version)
      }
    }
  }
  object Update extends LiftApiModuleString {
    val schema: API.UpdateGroup.type = API.UpdateGroup
    val restExtractor = restExtractorService
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        id:         String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val qc: QueryContext = authzToken.qc
      if (req.json_?) {
        req.json match {
          case Full(arg) =>
            val restGroup = restExtractor.extractGroupFromJSON(arg)
            serviceV2.updateGroup(id, req, restGroup, version)
          case eb: EmptyBox =>
            toJsonError(None, "No Json data sent")("updateGroup", restExtractor.extractPrettify(req.params))
        }
      } else {
        val restGroup = restExtractor.extractGroup(req.params)
        serviceV2.updateGroup(id, req, restGroup, version)
      }
    }
  }
  object Reload extends LiftApiModuleString {
    val schema: API.ReloadGroup.type = API.ReloadGroup
    val restExtractor = restExtractorService
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        id:         String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val qc: QueryContext = authzToken.qc
      serviceV2.reloadGroup(id, req, version)
    }
  }

  import RestUtils.*
  import net.liftweb.json.*

  def response(function: Box[JValue], req: Req, errorMessage: String, id: Option[String])(implicit
      action: String
  ): LiftResponse = {
    RestUtils.response(restExtractorService, "groupCategories", id)(function, req, errorMessage)
  }

  def actionResponse(function: Box[ActionType], req: Req, errorMessage: String, id: Option[String], actor: EventActor)(implicit
      action: String
  ): LiftResponse = {
    RestUtils.actionResponse2(restExtractorService, "groupCategories", uuidGen, id)(function, req, errorMessage)(action, actor)
  }

  // group categories
  object GetTree        extends LiftApiModule0      {
    val schema: API.GetGroupTree.type = API.GetGroupTree
    val restExtractor = restExtractorService
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      implicit val action = schema.name
      response(
        serviceV6.getCategoryTree(version),
        req,
        s"Could not fetch Group tree",
        None
      )
    }
  }
  object GetCategory    extends LiftApiModuleString {
    val schema: API.GetGroupCategoryDetails.type = API.GetGroupCategoryDetails
    val restExtractor = restExtractorService
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        id:         String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val action = schema.name
      response(
        serviceV6.getCategoryDetails(NodeGroupCategoryId(id), version),
        req,
        s"Could not fetch Group category '${id}' details",
        Some(id)
      )
    }
  }
  object DeleteCategory extends LiftApiModuleString {
    val schema: API.DeleteGroupCategory.type = API.DeleteGroupCategory
    val restExtractor = restExtractorService
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        id:         String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val action = schema.name
      actionResponse(
        Full(serviceV6.deleteCategory(NodeGroupCategoryId(id), version)),
        req,
        s"Could not delete Group category '${id}'",
        Some(id),
        authzToken.qc.actor
      )
    }
  }
  object UpdateCategory extends LiftApiModuleString {
    val schema: API.UpdateGroupCategory.type = API.UpdateGroupCategory
    val restExtractor = restExtractorService
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        id:         String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val action = schema.name
      val x               = for {
        restCategory <- {
          if (req.json_?) {
            for {
              json <- req.json ?~! "No JSON data sent"
              cat  <- restExtractor.extractGroupCategory(json)
            } yield {
              cat
            }
          } else {
            restExtractor.extractGroupCategory(req.params)
          }
        }
      } yield {
        serviceV6.updateCategory(NodeGroupCategoryId(id), restCategory, version) _
      }
      actionResponse(
        x,
        req,
        s"Could not update Group category '${id}'",
        Some(id),
        authzToken.qc.actor
      )
    }
  }
  object CreateCategory extends LiftApiModule0      {
    val schema: API.CreateGroupCategory.type = API.CreateGroupCategory
    val restExtractor = restExtractorService
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      implicit val action = schema.name
      val id              = () => NodeGroupCategoryId(uuidGen.newUuid)
      val x               = for {
        restCategory <- {
          if (req.json_?) {
            for {
              json <- req.json ?~! "No JSON data sent"
              cat  <- restExtractor.extractGroupCategory(json)
            } yield {
              cat
            }
          } else {
            restExtractor.extractGroupCategory(req.params)
          }
        }
      } yield {
        serviceV6.createCategory(id, restCategory, version) _
      }
      actionResponse(
        x,
        req,
        s"Could not create group category",
        None,
        authzToken.qc.actor
      )
    }
  }

  //
  //
  //  V14 API
  //
  //

  object ListV14 extends LiftApiModule0      {
    val schema: API.ListGroups.type = API.ListGroups
    val restExtractor = restExtractorService
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      implicit val qc: QueryContext = authzToken.qc
      serviceV14.listGroups().toLiftResponseList(params, schema)
    }
  }
  object GetV14  extends LiftApiModuleString {
    val schema: API.GroupDetails.type = API.GroupDetails
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        sid:        String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val qc: QueryContext = authzToken.qc
      NodeGroupId.parse(sid).toIO.flatMap(id => serviceV14.groupDetails(id)).toLiftResponseOne(params, schema, s => Some(s.id))
    }
  }

  object GroupInheritedPropertiesV14 extends LiftApiModuleString {
    val schema: API.GroupInheritedProperties.type = API.GroupInheritedProperties
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        sid:        String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val qc: QueryContext = authzToken.qc
      NodeGroupId
        .parse(sid)
        .toIO
        .flatMap(id => serviceV14.getNodePropertiesTree(id, RenderInheritedProperties.JSON))
        .toLiftResponseOne(params, schema, s => Some(s.groupId))
    }
  }

  object GroupDisplayInheritedPropertiesV14 extends LiftApiModuleString {
    val schema: API.GroupDisplayInheritedProperties.type = API.GroupDisplayInheritedProperties
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        sid:        String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val qc: QueryContext = authzToken.qc
      NodeGroupId
        .parse(sid)
        .toIO
        .flatMap(id => serviceV14.getNodePropertiesTree(id, RenderInheritedProperties.HTML))
        .toLiftResponseOne(params, schema, s => Some(s.groupId))
    }
  }

  object DeleteV14 extends LiftApiModuleString {
    val schema: API.DeleteGroup.type = API.DeleteGroup
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        sid:        String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val qc: QueryContext = authzToken.qc
      NodeGroupId
        .parse(sid)
        .toIO
        .flatMap(id => serviceV14.deleteGroup(id, params, authzToken.qc.actor))
        .toLiftResponseOne(params, schema, s => Some(s.id))
    }
  }

  object CreateV14 extends LiftApiModule0 {
    val schema:                                                                                                API.CreateGroup.type = API.CreateGroup
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse         = {
      implicit val qc: QueryContext = authzToken.qc
      (for {
        restGroup <- zioJsonExtractor.extractGroup(req).chainError(s"Could not extract group parameters from request").toIO
        result    <- serviceV14.createGroup(
                       restGroup,
                       restGroup.id.getOrElse(NodeGroupId(NodeGroupUid(uuidGen.newUuid))),
                       restGroup.source,
                       params,
                       authzToken.qc.actor
                     )
      } yield {
        val action = if (restGroup.source.nonEmpty) "cloneGroup" else schema.name
        (RudderJsonResponse.ResponseSchema(action, schema.dataContainer), result)
      }).toLiftResponseOneMap(params, RudderJsonResponse.ResponseSchema.fromSchema(schema), x => (x._1, x._2, Some(x._2.id)))
    }
  }

  object UpdateV14 extends LiftApiModuleString {
    val schema: API.UpdateGroup.type = API.UpdateGroup
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        sid:        String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val qc: QueryContext = authzToken.qc
      (for {
        restGroup <- zioJsonExtractor.extractGroup(req).chainError(s"Could not extract a group from request.").toIO
        id        <- NodeGroupId.parse(sid).toIO
        res       <- serviceV14.updateGroup(restGroup.copy(id = Some(id)), params, authzToken.qc.actor)
      } yield {
        res
      }).toLiftResponseOne(params, schema, s => Some(s.id))
    }
  }

  object ReloadV14 extends LiftApiModuleString {
    val schema: API.ReloadGroup.type = API.ReloadGroup
    val restExtractor = restExtractorService
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        id:         String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val qc: QueryContext = authzToken.qc
      serviceV14.reloadGroup(id, params, authzToken.qc.actor).toLiftResponseOne(params, schema, s => Some(s.id))
    }
  }

  // group categories
  object GetTreeV14        extends LiftApiModule0      {
    val schema: API.GetGroupTree.type = API.GetGroupTree
    val restExtractor = restExtractorService
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      implicit val action = schema.name
      response(
        serviceV14.getCategoryTree(version),
        req,
        s"Could not fetch Group tree",
        None
      )
    }
  }
  object GetCategoryV14    extends LiftApiModuleString {
    val schema: API.GetGroupCategoryDetails.type = API.GetGroupCategoryDetails
    val restExtractor = restExtractorService
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        id:         String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val action = schema.name
      response(
        serviceV14.getCategoryDetails(NodeGroupCategoryId(id), version),
        req,
        s"Could not fetch Group category '${id}' details",
        Some(id)
      )
    }
  }
  object DeleteCategoryV14 extends LiftApiModuleString {
    val schema: API.DeleteGroupCategory.type = API.DeleteGroupCategory
    val restExtractor = restExtractorService
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        id:         String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val action = schema.name
      actionResponse(
        Full(serviceV14.deleteCategory(NodeGroupCategoryId(id), version)),
        req,
        s"Could not delete Group category '${id}'",
        Some(id),
        authzToken.qc.actor
      )
    }
  }
  object UpdateCategoryV14 extends LiftApiModuleString {
    val schema: API.UpdateGroupCategory.type = API.UpdateGroupCategory
    val restExtractor = restExtractorService
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        id:         String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val action = schema.name
      val x               = for {
        restCategory <- {
          if (req.json_?) {
            for {
              json <- req.json ?~! "No JSON data sent"
              cat  <- restExtractor.extractGroupCategory(json)
            } yield {
              cat
            }
          } else {
            restExtractor.extractGroupCategory(req.params)
          }
        }
      } yield {
        serviceV14.updateCategory(NodeGroupCategoryId(id), restCategory, version) _
      }
      actionResponse(
        x,
        req,
        s"Could not update Group category '${id}'",
        Some(id),
        authzToken.qc.actor
      )
    }
  }
  object CreateCategoryV14 extends LiftApiModule0      {
    val schema: API.CreateGroupCategory.type = API.CreateGroupCategory
    val restExtractor = restExtractorService
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      implicit val action = schema.name
      val id              = () => NodeGroupCategoryId(uuidGen.newUuid)
      val x               = for {
        restCategory <- {
          if (req.json_?) {
            for {
              json <- req.json ?~! "No JSON data sent"
              cat  <- restExtractor.extractGroupCategory(json)
            } yield {
              cat
            }
          } else {
            restExtractor.extractGroupCategory(req.params)
          }
        }
      } yield {
        serviceV14.createCategory(id, restCategory, version) _
      }
      actionResponse(
        x,
        req,
        s"Could not create group category",
        None,
        authzToken.qc.actor
      )
    }
  }
}

class GroupApiInheritedProperties(
    groupRepo: RoNodeGroupRepository,
    paramRepo: RoParameterRepository
) {

  /*
   * the returned format is a list of properties:
   *
   */
  def getNodePropertiesTree(groupId: NodeGroupId, renderInHtml: RenderInheritedProperties): IOResult[JArray] = {

    for {
      allGroups  <- groupRepo.getFullGroupLibrary().map(_.allGroups)
      params     <- paramRepo.getAllGlobalParameters()
      properties <- MergeNodeProperties.forGroup(groupId, allGroups, params.map(p => (p.name, p)).toMap).toIO
    } yield {
      import com.normation.rudder.domain.properties.JsonPropertySerialisation.*
      val rendered = renderInHtml match {
        case RenderInheritedProperties.HTML => properties.toApiJsonRenderParents
        case RenderInheritedProperties.JSON => properties.toApiJson
      }
      JArray(
        (
          ("groupId" -> groupId.serialize)
          ~ ("properties" -> rendered)
        ) :: Nil
      )
    }
  }
}

class GroupApiService2(
    readGroup:            RoNodeGroupRepository,
    writeGroup:           WoNodeGroupRepository,
    uuidGen:              StringUuidGenerator,
    asyncDeploymentAgent: AsyncDeploymentActor,
    workflowLevelService: WorkflowLevelService,
    restExtractor:        RestExtractorService,
    queryProcessor:       QueryProcessor,
    restDataSerializer:   RestDataSerializer
)(implicit userService: UserService)
    extends Loggable {

  import RestUtils.*
  import restDataSerializer.*

  private def createChangeRequestAndAnswer(
      id:           String,
      diff:         ChangeRequestNodeGroupDiff,
      group:        NodeGroup,
      initialState: Option[NodeGroup],
      newCategory:  Option[NodeGroupCategoryId],
      actor:        EventActor,
      req:          Req,
      act:          DGModAction,
      apiVersion:   ApiVersion
  )(implicit action: String, prettify: Boolean, cc: ChangeContext) = {
    val change = NodeGroupChangeRequest(act, group, newCategory, initialState)

    (for {
      reason   <- restExtractor.extractReason(req)
      crName   <- restExtractor.extractChangeRequestName(req).map(_.getOrElse(s"${act.name} group '${group.name}' from API"))
      workflow <- workflowLevelService.getForNodeGroup(actor, change)
      cr        = ChangeRequestService.createChangeRequestFromNodeGroup(
                    crName,
                    restExtractor.extractChangeRequestDescription(req),
                    group,
                    initialState,
                    diff,
                    actor,
                    reason
                  )
      id       <- workflow.startWorkflow(cr)
    } yield {
      (id, workflow)
    }) match {
      case Full((crId, workflow)) =>
        val optCrId   = if (workflow.needExternalValidation()) Some(crId) else None
        val jsonGroup = List(serializeGroup(group, newCategory, optCrId))
        toJsonResponse(Some(id), ("groups" -> JArray(jsonGroup)))

      case eb: EmptyBox =>
        val fail = eb ?~ (s"Could not save changes on Group ${id}")
        val msg  = s"${act} failed, cause is: ${fail.messageChain}."
        toJsonError(Some(id), msg)
    }
  }

  def listGroups(req: Req, apiVersion: ApiVersion): LiftResponse = {
    implicit val action   = "listGroups"
    implicit val prettify = restExtractor.extractPrettify(req.params)
    readGroup.getAll().toBox match {
      case Full(groups) =>
        toJsonResponse(None, ("groups" -> JArray(groups.sortBy(_.id.serialize).map(g => serializeGroup(g, None, None)).toList)))
      case eb: EmptyBox =>
        val message = (eb ?~ ("Could not fetch Groups")).messageChain
        toJsonError(None, message)
    }
  }

  def createGroup(restGroup: Box[RestGroup], req: Req, apiVersion: ApiVersion)(implicit qc: QueryContext): LiftResponse = {
    implicit val prettify = restExtractor.extractPrettify(req.params)
    val modId             = ModificationId(uuidGen.newUuid)
    val actor             = RestUtils.getActor(req)
    val groupIdBox        = restExtractor
      .extractId(req)(x => Full(NodeGroupId(NodeGroupUid(x))))
      .map(_.getOrElse(NodeGroupId(NodeGroupUid(uuidGen.newUuid))))

    def actualGroupCreation(change: NodeGroupChangeRequest, groupId: NodeGroupId, isClone: Boolean) = {
      (for {
        reason   <- restExtractor.extractReason(req)
        rootCat  <- readGroup.getRootCategoryPure().toBox
        cat       = change.category.getOrElse(rootCat.id)
        saveDiff <- writeGroup.create(change.newGroup, cat, modId, actor, reason).toBox
      } yield {
        (saveDiff, cat)
      }) match {
        case Full((x, cat)) =>
          if (x.needDeployment) {
            // Trigger a deployment only if it is needed
            asyncDeploymentAgent ! AutomaticStartDeployment(modId, actor)
          }
          val jsonGroup       = List(serializeGroup(change.newGroup, Some(cat), None))
          implicit val action = if (isClone) "cloneGroup" else "createGroup"

          toJsonResponse(Some(groupId.serialize), ("groups" -> JArray(jsonGroup)))

        case eb: EmptyBox =>
          val fail    = eb ?~ (s"Could not save group '${groupId.serialize}''")
          val message =
            s"Could not create group '${change.newGroup.name}' (id:${groupId.serialize}), cause is: ${fail.messageChain}."
          toJsonError(Some(groupId.serialize), message)
      }
    }

    // decide if we should create a new group or clone an existing one
    // Return the source group to use in each case.
    def createOrClone(
        actor:         EventActor,
        restGroup:     RestGroup,
        groupId:       NodeGroupId,
        name:          String,
        sourceIdParam: Option[NodeGroupId]
    ): Box[NodeGroupChangeRequest] = {
      sourceIdParam match {
        case Some(sourceId) =>
          // clone existing group
          for {
            (group, cat) <-
              readGroup.getNodeGroup(sourceId).toBox ?~!
              s"Could not create group '${name}' (id:${groupId.serialize}) by cloning group '${sourceId.serialize}')"
            id            = NodeGroupId(NodeGroupUid(restGroup.id.getOrElse(uuidGen.newUuid)))
            updated      <- restGroup.updateGroup(group).toBox
          } yield {
            // in that case, we take rest category and if empty, we default to cloned group category
            val category = restGroup.category.orElse(Some(cat))
            val withId   = updated.copy(id = id)
            NodeGroupChangeRequest(DGModAction.CreateSolo, withId, category, Some(group))
          }

        case None =>
          // If enable is missing in parameter consider it to true
          val defaultEnabled = restGroup.enabled.getOrElse(true)
          // create from scratch - base rule is the same with default values
          val baseGroup      =
            NodeGroup(groupId, name, "", Nil, None, isDynamic = true, serverList = Set(), _isEnabled = defaultEnabled)

          // if only the name parameter is set, consider it to be enabled
          // if not if workflow are enabled, consider it to be disabled
          // if there is no workflow, use the value used as parameter (default to true)
          // code extract :
          /*
           * if (restGroup.onlyName) true
           * else if (workflowEnabled) false
           * else defaultEnabled
           */
          for {
            updated  <- restGroup.updateGroup(baseGroup).toBox
            change    = NodeGroupChangeRequest(DGModAction.CreateSolo, updated, restGroup.category, Some(baseGroup))
            workflow <-
              workflowLevelService.getForNodeGroup(actor, change) ?~! "Could not find workflow status for that rule creation"
          } yield {
            // we don't actually start a workflow, we only disable the group if a workflow should be
            // started. Update rule "enable" status accordingly.
            val enableCheck = restGroup.onlyName || (!workflow.needExternalValidation() && defaultEnabled)
            // Then enabled value in restRule will be used in the saved Rule
            change.copy(newGroup = change.newGroup.copy(_isEnabled = enableCheck))
          }

      }
    }

    (for {
      group   <- restGroup ?~! s"Could extract values from request"
      name    <- Box(group.name) ?~! "Missing mandatory value for group name"
      source  <- restExtractor.extractString("source")(req)(x => NodeGroupId.parse(x).toBox)
      _       <- (group.query, source) match {
                   case (None, None) => Failure("Cannot create a group with an empty query")
                   case _            => Full(())
                 }
      groupId <- groupIdBox
      change  <- createOrClone(actor, group, groupId, name, source)
    } yield {
      actualGroupCreation(change, groupId, source.isDefined)
    }) match {
      case Full(resp) =>
        resp
      case eb: EmptyBox =>
        val fail = eb ?~ (s"Error when creating new group")
        toJsonError(groupIdBox.toOption.map(_.serialize), fail.messageChain)
    }
  }

  def groupDetails(sid: String, req: Req, apiVersion: ApiVersion)(implicit qc: QueryContext): LiftResponse = {
    implicit val action   = "groupDetails"
    implicit val prettify = restExtractor.extractPrettify(req.params)

    NodeGroupId.parse(sid).toIO.flatMap(readGroup.getNodeGroup).toBox match {
      case Full((group, cat)) =>
        val jsonGroup = List(serializeGroup(group, Some(cat), None))
        toJsonResponse(Some(sid), ("groups" -> JArray(jsonGroup)))
      case eb: EmptyBox =>
        val fail    = eb ?~! (s"Could not find Group ${sid}")
        val message = s"Could not get Group ${sid} details cause is: ${fail.msg}."
        toJsonError(Some(sid), message)
    }
  }

  def reloadGroup(sid: String, req: Req, apiVersion: ApiVersion)(implicit qc: QueryContext): LiftResponse = {
    implicit val action   = "reloadGroup"
    implicit val prettify = restExtractor.extractPrettify(req.params)
    val actor             = RestUtils.getActor(req)

    NodeGroupId.parse(sid).toIO.flatMap(readGroup.getNodeGroup).toBox match {
      case Full((group, cat)) =>
        group.query match {
          case Some(query) =>
            queryProcessor.processOnlyId(query) match {
              case Full(nodeIds) =>
                val updatedGroup    = group.copy(serverList = nodeIds.toSet)
                val reloadGroupDiff = ModifyToNodeGroupDiff(updatedGroup)
                implicit val cc: ChangeContext =
                  ChangeContext(ModificationId(uuidGen.newUuid), actor, new DateTime(), None, None, qc.nodePerms)
                createChangeRequestAndAnswer(
                  sid,
                  reloadGroupDiff,
                  group,
                  Some(group),
                  None,
                  actor,
                  req,
                  DGModAction.Update,
                  apiVersion
                )
              case eb: EmptyBox =>
                val fail    = eb ?~ (s"Could not fetch Nodes")
                val message = s"Could not reload Group ${sid} details cause is: ${fail.msg}."
                toJsonError(Some(sid), message)
            }
          case None        =>
            val jsonGroup = List(serializeGroup(group, Some(cat), None))
            toJsonResponse(Some(sid), ("groups" -> JArray(jsonGroup)))
        }
        val jsonGroup = List(serializeGroup(group, Some(cat), None))
        toJsonResponse(Some(sid), ("groups" -> JArray(jsonGroup)))
      case eb: EmptyBox =>
        val fail    = eb ?~ (s"Could not find Group ${sid}")
        val message = s"Could not reload Group ${sid} details cause is: ${fail.msg}."
        toJsonError(Some(sid), message)
    }
  }

  def deleteGroup(sid: String, req: Req, apiVersion: ApiVersion)(implicit qc: QueryContext): LiftResponse = {
    implicit val action   = "deleteGroup"
    implicit val prettify = restExtractor.extractPrettify(req.params)
    val actor             = RestUtils.getActor(req)

    NodeGroupId.parse(sid).toIO.flatMap(readGroup.getNodeGroup).toBox match {
      case Full((group, _)) =>
        val deleteGroupDiff = DeleteNodeGroupDiff(group)
        implicit val cc: ChangeContext =
          ChangeContext(ModificationId(uuidGen.newUuid), actor, new DateTime(), None, None, qc.nodePerms)
        createChangeRequestAndAnswer(sid, deleteGroupDiff, group, Some(group), None, actor, req, DGModAction.Delete, apiVersion)

      case eb: EmptyBox =>
        val fail    = eb ?~ (s"Could not find Group ${sid}")
        val message = s"Could not delete Group ${sid} cause is: ${fail.msg}."
        toJsonError(Some(sid), message)
    }
  }

  def updateGroup(sid: String, req: Req, restValues: Box[RestGroup], apiVersion: ApiVersion)(implicit
      qc: QueryContext
  ): LiftResponse = {
    implicit val action   = "updateGroup"
    implicit val prettify = restExtractor.extractPrettify(req.params)
    val actor             = getActor(req)

    (for {
      groupId      <- NodeGroupId.parse(sid).toBox
      (group, cat) <- readGroup.getNodeGroup(groupId).toBox
      restGroup    <- restValues
      updated      <- restGroup.updateGroup(group).toBox
    } yield {
      (group, updated, restGroup.category.orElse(Some(cat)), ModifyToNodeGroupDiff(updated))
    }) match {
      case Full((group, updated, optCategory, diff)) =>
        implicit val cc: ChangeContext =
          ChangeContext(ModificationId(uuidGen.newUuid), actor, new DateTime(), None, None, qc.nodePerms)
        createChangeRequestAndAnswer(sid, diff, updated, Some(group), optCategory, actor, req, DGModAction.Update, apiVersion)
      case eb: EmptyBox =>
        val fail = eb ?~ s"Could not modify Group ${sid}"
        toJsonError(Some(sid), fail.messageChain)
    }
  }

}

class GroupApiService6(
    readGroup:          RoNodeGroupRepository,
    writeGroup:         WoNodeGroupRepository,
    restDataSerializer: RestDataSerializer
) extends Loggable {

  def getCategoryTree(apiVersion: ApiVersion): Box[JValue] = {
    for {
      root <- readGroup.getFullGroupLibrary().toBox
    } yield {
      restDataSerializer.serializeGroupCategory(root, root.id, FullDetails, apiVersion)
    }
  }

  def getCategoryDetails(id: NodeGroupCategoryId, apiVersion: ApiVersion): Box[JValue] = {
    for {
      root     <- readGroup.getFullGroupLibrary().toBox
      category <- Box(root.allCategories.get(id)) ?~! s"Cannot find Group category '${id.value}'"
      parent   <- Box(root.parentCategories.get(id)) ?~! s"Cannot find Group category '${id.value}' parent"
    } yield {
      restDataSerializer.serializeGroupCategory(category, parent.id, MinimalDetails, apiVersion)
    }
  }

  def deleteCategory(
      id:         NodeGroupCategoryId,
      apiVersion: ApiVersion
  )(actor: EventActor, modId: ModificationId, reason: Option[String]): Box[JValue] = {
    for {
      root     <- readGroup.getFullGroupLibrary().toBox
      category <- Box(root.allCategories.get(id)) ?~! s"Cannot find Group category '${id.value}'"
      parent   <- Box(root.parentCategories.get(id)) ?~! s"Cannot find Groupl category '${id.value}' parent"
      _        <- writeGroup.delete(id, modId, actor, reason).toBox
    } yield {
      restDataSerializer.serializeGroupCategory(category, parent.id, MinimalDetails, apiVersion)
    }
  }

  def updateCategory(
      id:         NodeGroupCategoryId,
      restData:   RestGroupCategory,
      apiVersion: ApiVersion
  )(actor: EventActor, modId: ModificationId, reason: Option[String]): Box[JValue] = {
    for {
      root      <- readGroup.getFullGroupLibrary().toBox
      category  <- Box(root.allCategories.get(id)) ?~! s"Cannot find Group category '${id.value}'"
      oldParent <- Box(root.parentCategories.get(id)) ?~! s"Cannot find Group category '${id.value}' parent"
      parent     = restData.parent.getOrElse(oldParent.id)
      update     = restData.update(category)
      _         <- writeGroup.saveGroupCategory(update.toNodeGroupCategory, parent, modId, actor, reason).toBox
    } yield {
      restDataSerializer.serializeGroupCategory(update, parent, MinimalDetails, apiVersion)
    }
  }

  // defaultId: the id to use if restDateDidn't provide one
  def createCategory(
      defaultId:  () => NodeGroupCategoryId,
      restData:   RestGroupCategory,
      apiVersion: ApiVersion
  )(actor: EventActor, modId: ModificationId, reason: Option[String]): Box[JValue] = {
    for {
      update  <- restData.create(defaultId)
      category = update.toNodeGroupCategory
      parent   = restData.parent.getOrElse(NodeGroupCategoryId("GroupRoot"))
      _       <- writeGroup.addGroupCategorytoCategory(category, parent, modId, actor, reason).toBox
    } yield {
      restDataSerializer.serializeGroupCategory(update, parent, MinimalDetails, apiVersion)
    }
  }

}

class GroupApiService14(
    nodeFactRepo:         NodeFactRepository,
    readGroup:            RoNodeGroupRepository,
    writeGroup:           WoNodeGroupRepository,
    paramRepo:            RoParameterRepository,
    uuidGen:              StringUuidGenerator,
    asyncDeploymentAgent: AsyncDeploymentActor,
    workflowLevelService: WorkflowLevelService,
    restExtractor:        RestExtractorService,
    queryParser:          CmdbQueryParser,
    queryProcessor:       QueryProcessor,
    restDataSerializer:   RestDataSerializer
) {

  private def createChangeRequest(
      diff:   ChangeRequestNodeGroupDiff,
      change: NodeGroupChangeRequest,
      params: DefaultParams,
      actor:  EventActor
  )(implicit cc: ChangeContext) = {
    for {
      workflow <- workflowLevelService.getForNodeGroup(actor, change)
      cr        = ChangeRequestService.createChangeRequestFromNodeGroup(
                    params.changeRequestName.getOrElse(s"${change.action.name} group '${change.newGroup.name}' from API"),
                    params.changeRequestDescription.getOrElse(""),
                    change.newGroup,
                    change.previousGroup,
                    diff,
                    actor,
                    params.reason
                  )
      id       <- workflow.startWorkflow(cr)
    } yield {
      val optCrId = if (workflow.needExternalValidation()) Some(id) else None
      JRGroup.fromGroup(change.newGroup, change.category.getOrElse(readGroup.getRootCategory().id), optCrId)
    }
  }

  def listGroups()(implicit qc: QueryContext): IOResult[Seq[JRGroup]] = {
    readGroup
      .getGroupsByCategory(true)
      .chainError("Could not fetch Groups")
      .map(groups =>
        groups.values.flatMap { case CategoryAndNodeGroup(c, gs) => gs.map(JRGroup.fromGroup(_, c.id, None)) }.toSeq.sortBy(_.id)
      )
  }

  def createGroup(
      restGroup:   JQGroup,
      nodeGroupId: NodeGroupId,
      clone:       Option[NodeGroupId],
      params:      DefaultParams,
      actor:       EventActor
  )(implicit qc: QueryContext): ZIO[Any, RudderError, JRGroup] = {
    def actualGroupCreation(change: NodeGroupChangeRequest, groupId: NodeGroupId) = {
      val modId = ModificationId(uuidGen.newUuid)
      (for {
        rootCat  <- readGroup.getRootCategoryPure()
        cat       = change.category.getOrElse(rootCat.id)
        saveDiff <- writeGroup.create(change.newGroup, cat, modId, actor, params.reason)
      } yield {
        if (saveDiff.needDeployment) {
          // Trigger a deployment only if it is needed
          asyncDeploymentAgent ! AutomaticStartDeployment(modId, actor)
        }
        JRGroup.fromGroup(saveDiff.group, cat, None)
      }).chainError(s"Could not create group '${change.newGroup.name}' (id:${groupId.serialize}).")
    }

    // decide if we should create a new group or clone an existing one
    // Return the source group to use in each case.
    def createOrClone(
        restGroup: JQGroup,
        groupId:   NodeGroupId,
        name:      String,
        clone:     Option[NodeGroupId],
        params:    DefaultParams,
        actor:     EventActor
    ): IOResult[NodeGroupChangeRequest] = {
      clone match {
        case Some(sourceId) =>
          // clone existing group
          for {
            pair    <- readGroup
                         .getNodeGroup(sourceId)
                         .chainError(
                           s"Could not create group '${name}' (id:${groupId.serialize}) by cloning group '${sourceId.serialize}')"
                         )
            id       = restGroup.id.getOrElse(NodeGroupId(NodeGroupUid(uuidGen.newUuid)))
            updated <- restGroup.updateGroup(pair._1, queryParser).toIO
          } yield {
            // in that case, we take rest category and if empty, we default to cloned group category
            val category = restGroup.category.orElse(Some(pair._2))
            val withId   = updated.copy(id = id)
            NodeGroupChangeRequest(DGModAction.CreateSolo, withId, category, Some(pair._1))
          }

        case None =>
          // If enable is missing in parameter consider it to true
          val defaultEnabled = restGroup.enabled.getOrElse(true)
          // create from scratch - base rule is the same with default values
          val baseGroup      =
            NodeGroup(groupId, name, "", Nil, None, isDynamic = true, serverList = Set(), _isEnabled = defaultEnabled)

          // if only the name parameter is set, consider it to be enabled
          // if not if workflow are enabled, consider it to be disabled
          // if there is no workflow, use the value used as parameter (default to true)
          // code extract :
          /*
           * if (restGroup.onlyName) true
           * else if (workflowEnabled) false
           * else defaultEnabled
           */
          for {
            updated  <- restGroup.updateGroup(baseGroup, queryParser).toIO
            change    = NodeGroupChangeRequest(DGModAction.CreateSolo, updated, restGroup.category, Some(baseGroup))
            workflow <- workflowLevelService
                          .getForNodeGroup(actor, change)
                          .toIO
                          .chainError("Could not find workflow status for that rule creation")
          } yield {
            // we don't actually start a workflow, we only disable the group if a workflow should be
            // started. Update rule "enable" status accordingly.
            val enableCheck = restGroup.onlyName || (!workflow.needExternalValidation() && defaultEnabled)
            // Then enabled value in restRule will be used in the saved Rule
            change.copy(newGroup = change.newGroup.copy(_isEnabled = enableCheck))
          }
      }
    }

    (for {
      name    <- restGroup.displayName.checkMandatory(_.size > 3, v => "'displayName' is mandatory and must be at least 3 char long")
      change  <- createOrClone(restGroup, nodeGroupId, name, clone, params, actor)
      created <- actualGroupCreation(change, nodeGroupId)
    } yield {
      created
    }).chainError(s"Error when creating new group")
  }

  def groupDetails(id: NodeGroupId)(implicit qc: QueryContext): IOResult[JRGroup] = {
    readGroup.getNodeGroup(id).map {
      case (g, c) =>
        JRGroup.fromGroup(g, c, None)
    }
  }

  def reloadGroup(sid: String, params: DefaultParams, actor: EventActor)(implicit qc: QueryContext): IOResult[JRGroup] = {

    NodeGroupId.parse(sid).toIO.flatMap(readGroup.getNodeGroup).flatMap {
      case (group, cat) =>
        group.query match {
          case Some(query) =>
            queryProcessor
              .processOnlyId(query)
              .toIO
              .flatMap { nodeIds =>
                val updatedGroup    = group.copy(serverList = nodeIds.toSet)
                val reloadGroupDiff = ModifyToNodeGroupDiff(updatedGroup)
                val change          = NodeGroupChangeRequest(DGModAction.Update, updatedGroup, Some(cat), Some(group))
                implicit val cc: ChangeContext =
                  ChangeContext(ModificationId(uuidGen.newUuid), actor, new DateTime(), None, None, qc.nodePerms)
                createChangeRequest(reloadGroupDiff, change, params, actor).toIO
              }
              .chainError(s"Could not reload Group ${sid} details")

          case None =>
            JRGroup.fromGroup(group, cat, None).succeed
        }
    }
  }

  def deleteGroup(id: NodeGroupId, params: DefaultParams, actor: EventActor)(implicit qc: QueryContext): IOResult[JRGroup] = {
    readGroup.getNodeGroupOpt(id).flatMap {
      case Some((group, cat)) =>
        val deleteGroupDiff = DeleteNodeGroupDiff(group)
        val change          = NodeGroupChangeRequest(DGModAction.Delete, group, Some(cat), Some(group))
        implicit val cc: ChangeContext =
          ChangeContext(ModificationId(uuidGen.newUuid), actor, new DateTime(), None, None, qc.nodePerms)
        createChangeRequest(deleteGroupDiff, change, params, actor).toIO

      case None =>
        JRGroup.empty(id.serialize).succeed
    }
  }

  def updateGroup(restGroup: JQGroup, params: DefaultParams, actor: EventActor)(implicit qc: QueryContext): IOResult[JRGroup] = {
    implicit val cc: ChangeContext =
      ChangeContext(ModificationId(uuidGen.newUuid), actor, new DateTime(), None, None, qc.nodePerms)
    for {
      id      <- restGroup.id.notOptional(s"You must specify the ID of the group that you want to update")
      pair    <- readGroup.getNodeGroup(id)
      updated <- restGroup.updateGroup(pair._1, queryParser).toIO
      diff     = ModifyToNodeGroupDiff(updated)
      optCat   = restGroup.category.orElse(Some(pair._2))
      change   = NodeGroupChangeRequest(DGModAction.Update, updated, optCat, Some(pair._1))
      res     <- createChangeRequest(diff, change, params, actor).toIO
    } yield {
      res
    }
  }

  def getCategoryTree(apiVersion: ApiVersion): Box[JValue] = {
    for {
      root <- readGroup.getFullGroupLibrary().toBox
    } yield {
      restDataSerializer.serializeGroupCategory(root, root.id, FullDetails, apiVersion)
    }
  }

  def getCategoryDetails(id: NodeGroupCategoryId, apiVersion: ApiVersion): Box[JValue] = {
    for {
      root     <- readGroup.getFullGroupLibrary().toBox
      category <- Box(root.allCategories.get(id)) ?~! s"Cannot find Group category '${id.value}'"
      parent   <- Box(root.parentCategories.get(id)) ?~! s"Cannot find Group category '${id.value}' parent"
    } yield {
      restDataSerializer.serializeGroupCategory(category, parent.id, MinimalDetails, apiVersion)
    }
  }

  def deleteCategory(
      id:         NodeGroupCategoryId,
      apiVersion: ApiVersion
  )(actor: EventActor, modId: ModificationId, reason: Option[String]): Box[JValue] = {
    for {
      root     <- readGroup.getFullGroupLibrary().toBox
      category <- Box(root.allCategories.get(id)) ?~! s"Cannot find Group category '${id.value}'"
      parent   <- Box(root.parentCategories.get(id)) ?~! s"Cannot find Groupl category '${id.value}' parent"
      _        <- writeGroup.delete(id, modId, actor, reason).toBox
    } yield {
      restDataSerializer.serializeGroupCategory(category, parent.id, MinimalDetails, apiVersion)
    }
  }

  def updateCategory(
      id:         NodeGroupCategoryId,
      restData:   RestGroupCategory,
      apiVersion: ApiVersion
  )(actor: EventActor, modId: ModificationId, reason: Option[String]): Box[JValue] = {
    for {
      root      <- readGroup.getFullGroupLibrary().toBox
      category  <- Box(root.allCategories.get(id)) ?~! s"Cannot find Group category '${id.value}'"
      oldParent <- Box(root.parentCategories.get(id)) ?~! s"Cannot find Group category '${id.value}' parent"
      parent     = restData.parent.getOrElse(oldParent.id)
      update     = restData.update(category)
      _         <- writeGroup.saveGroupCategory(update.toNodeGroupCategory, parent, modId, actor, reason).toBox
    } yield {
      restDataSerializer.serializeGroupCategory(update, parent, MinimalDetails, apiVersion)
    }
  }

  // defaultId: the id to use if restDateDidn't provide one
  def createCategory(
      defaultId:  () => NodeGroupCategoryId,
      restData:   RestGroupCategory,
      apiVersion: ApiVersion
  )(actor: EventActor, modId: ModificationId, reason: Option[String]): Box[JValue] = {
    for {
      update  <- restData.create(defaultId)
      category = update.toNodeGroupCategory
      parent   = restData.parent.getOrElse(NodeGroupCategoryId("GroupRoot"))
      _       <- writeGroup.addGroupCategorytoCategory(category, parent, modId, actor, reason).toBox
    } yield {
      restDataSerializer.serializeGroupCategory(update, parent, MinimalDetails, apiVersion)
    }
  }
  /*
   * the returned format is a list of properties:
   */
  def getNodePropertiesTree(
      groupId:      NodeGroupId,
      renderInHtml: RenderInheritedProperties
  )(implicit qc: QueryContext): IOResult[JRGroupInheritedProperties] = {
    for {
      groupLibrary <- readGroup.getFullGroupLibrary()
      allGroups     = groupLibrary.allGroups
      serverList    = allGroups.get(groupId).map(_.nodeGroup.serverList).getOrElse(Set.empty)

      nodes <- nodeFactRepo.getAll().map(_.filterKeys(serverList.contains(_)).values.toList)

      params           <- paramRepo.getAllGlobalParameters().map(_.map(p => (p.name, p)).toMap)
      parentProperties <- MergeNodeProperties.forGroup(groupId, allGroups, params).toIO
      properties       <- ZIO.foreach(nodes) { nodeFact =>
                            // for each property, find merged properties for nodes in the group and report type conflict
                            MergeNodeProperties
                              .forNode(
                                nodeFact.toNodeInfo,
                                groupLibrary.getTarget(nodeFact).map(_._2).toList,
                                params
                              )
                              .map(childProperties => {
                                parentProperties
                                  .map(p => {
                                    val matchingChildProperties = childProperties.collect {
                                      case cp if cp.prop.name == p.prop.name => cp.hierarchy
                                    }
                                    val hasConflicts            = MergeNodeProperties
                                      .checkValueTypes(matchingChildProperties.map(NodePropertyHierarchy(p.prop, _)))
                                      .isLeft
                                    (
                                      p,
                                      matchingChildProperties.flatten,
                                      hasConflicts
                                    )
                                  })
                              })
                              .toIO
                          }

    } yield {
      JRGroupInheritedProperties.fromGroup(
        groupId,
        properties.flatten,
        renderInHtml
      )
    }
  }

}
