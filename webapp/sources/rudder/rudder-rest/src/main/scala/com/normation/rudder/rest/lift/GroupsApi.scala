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

import com.normation.errors.*
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.rudder.api.ApiVersion
import com.normation.rudder.apidata.JsonQueryObjects.*
import com.normation.rudder.apidata.JsonResponseObjects.*
import com.normation.rudder.apidata.RenderInheritedProperties
import com.normation.rudder.apidata.RestDataSerializer
import com.normation.rudder.apidata.ZioJsonExtractor
import com.normation.rudder.apidata.implicits.*
import com.normation.rudder.batch.AsyncDeploymentActor
import com.normation.rudder.batch.AutomaticStartDeployment
import com.normation.rudder.config.ReasonBehavior
import com.normation.rudder.config.UserPropertyService
import com.normation.rudder.domain.nodes.*
import com.normation.rudder.domain.properties.FailedNodePropertyHierarchy
import com.normation.rudder.domain.properties.SuccessNodePropertyHierarchy
import com.normation.rudder.domain.properties.Visibility.Displayed
import com.normation.rudder.facts.nodes.ChangeContext
import com.normation.rudder.facts.nodes.NodeFactRepository
import com.normation.rudder.facts.nodes.QueryContext
import com.normation.rudder.properties.NodePropertiesService
import com.normation.rudder.properties.PropertiesRepository
import com.normation.rudder.repository.CategoryAndNodeGroup
import com.normation.rudder.repository.FullNodeGroupCategory
import com.normation.rudder.repository.RoNodeGroupRepository
import com.normation.rudder.repository.WoNodeGroupRepository
import com.normation.rudder.rest.*
import com.normation.rudder.rest.GroupApi as API
import com.normation.rudder.rest.RudderJsonRequest.*
import com.normation.rudder.rest.implicits.*
import com.normation.rudder.services.queries.CmdbQueryParser
import com.normation.rudder.services.queries.QueryProcessor
import com.normation.rudder.services.workflows.*
import com.normation.utils.StringUuidGenerator
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import org.joda.time.DateTime
import zio.Chunk
import zio.ZIO
import zio.syntax.*

class GroupsApi(
    propertiesService:   NodePropertiesService,
    zioJsonExtractor:    ZioJsonExtractor,
    uuidGen:             StringUuidGenerator,
    userPropertyService: UserPropertyService,
    service:             GroupApiService14
) extends LiftApiModuleProvider[API] {

  implicit def reasonBehavior: ReasonBehavior = userPropertyService.reasonsFieldBehavior

  def schemas: ApiModuleProvider[API] = API

  /*
   * The actual builder for the compliance API.
   * Depends of authz method and supported version.
   */
  def getLiftEndpoints(): List[LiftApiModule] = {
    API.endpoints.map {
      case API.ListGroups                      => List
      case API.GetGroupTree                    => GetTree
      case API.GroupDetails                    => Get
      case API.GroupInheritedProperties        => GroupInheritedProperties
      case API.GroupDisplayInheritedProperties => GroupDisplayInheritedProperties
      case API.DeleteGroup                     => Delete
      case API.CreateGroup                     => Create
      case API.UpdateGroup                     => Update
      case API.DeleteGroupCategory             => DeleteCategory
      case API.CreateGroupCategory             => CreateCategory
      case API.GetGroupCategoryDetails         => GetCategory
      case API.UpdateGroupCategory             => UpdateCategory
      case API.ReloadGroup                     => Reload
    }
  }

  // group categories
  //
  //
  //   API
  //
  //

  object List extends LiftApiModule0      {
    val schema:                                                                                                API.ListGroups.type = API.ListGroups
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse        = {
      implicit val qc: QueryContext = authzToken.qc
      service.listGroups().toLiftResponseList(params, schema)
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
      NodeGroupId.parse(sid).toIO.flatMap(id => service.groupDetails(id)).toLiftResponseOne(params, schema, s => Some(s.id))
    }
  }

  object GroupInheritedProperties extends LiftApiModuleString {
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
        .flatMap(id => service.getNodePropertiesTree(id, RenderInheritedProperties.JSON))
        .toLiftResponseOne(params, schema, s => Some(s.groupId))
    }
  }

  object GroupDisplayInheritedProperties extends LiftApiModuleString {
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
        .flatMap(id => service.getNodePropertiesTree(id, RenderInheritedProperties.HTML))
        .toLiftResponseOne(params, schema, s => Some(s.groupId))
    }
  }

  object Delete extends LiftApiModuleString {
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
        .flatMap(id => service.deleteGroup(id, params, authzToken.qc.actor))
        .toLiftResponseOne(params, schema, s => Some(s.id))
    }
  }

  object Create extends LiftApiModule0 {
    val schema:                                                                                                API.CreateGroup.type = API.CreateGroup
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse         = {
      implicit val qc: QueryContext = authzToken.qc
      (for {
        restGroup <- zioJsonExtractor.extractGroup(req).chainError(s"Could not extract group parameters from request").toIO
        result    <- service.createGroup(
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

  object Update extends LiftApiModuleString {
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
        res       <- service.updateGroup(restGroup.copy(id = Some(id)), params, authzToken.qc.actor)
        // await all properties update to guarantee that properties are resolved after group modification
        _         <- propertiesService.updateAll()
      } yield {
        res
      }).toLiftResponseOne(params, schema, s => Some(s.id))
    }
  }

  object Reload extends LiftApiModuleString {
    val schema: API.ReloadGroup.type = API.ReloadGroup
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        id:         String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val qc: QueryContext = authzToken.qc
      service.reloadGroup(id, params, authzToken.qc.actor).toLiftResponseOne(params, schema, s => Some(s.id))
    }
  }

  // group categories
  object GetTree        extends LiftApiModule0      {
    val schema:                                                                                                API.GetGroupTree.type = API.GetGroupTree
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse          = {
      (for {
        includeSystem <- zioJsonExtractor.extractIncludeSystem(req).toIO
        res           <-
          service
            .getCategoryTree(includeSystem.getOrElse(true))
            .map(JRGroupCategoriesFull(_))
            .chainError("Could not fetch Group tree")
      } yield {
        res
      }).toLiftResponseOne(params, schema, _ => None)
    }
  }
  object GetCategory    extends LiftApiModuleString {
    val schema: API.GetGroupCategoryDetails.type = API.GetGroupCategoryDetails
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        id:         String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      service
        .getCategoryDetails(NodeGroupCategoryId(id))
        .map(JRGroupCategoriesMinimal(_))
        .chainError(s"Could not fetch Group category '${id}' details")
        .toLiftResponseOne(
          params,
          schema,
          _ => Some(id)
        )
    }
  }
  object DeleteCategory extends LiftApiModuleString {
    val schema: API.DeleteGroupCategory.type = API.DeleteGroupCategory
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        id:         String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      withChangeContext(req, authzToken) { implicit cc =>
        service
          .deleteCategory(NodeGroupCategoryId(id))
          .map(JRGroupCategoriesMinimal(_))
          .chainError(
            s"Could not delete Group category '${id}'"
          )
      }.toLiftResponseOne(
        params,
        schema,
        Some(id)
      )
    }
  }
  object UpdateCategory extends LiftApiModuleString {
    val schema: API.UpdateGroupCategory.type = API.UpdateGroupCategory
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        id:         String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      withChangeContext(req, authzToken)(implicit cc => {
        (for {
          groupCategory        <- zioJsonExtractor.extractGroupCategory(req).toIO
          updatedGroupCategory <- service.updateCategory(NodeGroupCategoryId(id), groupCategory)
        } yield {
          JRGroupCategoriesMinimal(updatedGroupCategory)
        }).chainError(
          s"Could not update Group category '${id}'"
        )
      }).toLiftResponseOne(
        params,
        schema,
        Some(id)
      )
    }
  }
  object CreateCategory extends LiftApiModule0      {
    val schema:                                                                                                API.CreateGroupCategory.type = API.CreateGroupCategory
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse                 = {
      withChangeContext(req, authzToken)(implicit cc => {
        (for {
          groupCategory    <- zioJsonExtractor.extractGroupCategory(req).toIO
          newGroupCategory <- service.createCategory(NodeGroupCategoryId(uuidGen.newUuid), groupCategory)
        } yield {
          JRGroupCategoriesMinimal(newGroupCategory)
        }).chainError(
          s"Could not create group category"
        )
      }).toLiftResponseOne(
        params,
        schema,
        None
      )
    }
  }

  // Extracts reason from the request and provide an (implicit ChangeContext)
  private def withChangeContext[A](req: Req, authzToken: AuthzToken)(
      block: ChangeContext => IOResult[A]
  ): IOResult[A] = {
    extractReason(req).toIO.flatMap(reason => {
      val cc = ChangeContext(
        ModificationId(uuidGen.newUuid),
        authzToken.qc.actor,
        new DateTime(),
        reason,
        None,
        authzToken.qc.nodePerms
      )
      block(cc)
    })
  }
}

class GroupApiService14(
    nodeFactRepo:         NodeFactRepository,
    readGroup:            RoNodeGroupRepository,
    writeGroup:           WoNodeGroupRepository,
    propertiesRepo:       PropertiesRepository,
    propertiesService:    NodePropertiesService,
    uuidGen:              StringUuidGenerator,
    asyncDeploymentAgent: AsyncDeploymentActor,
    workflowLevelService: WorkflowLevelService,
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
        // after group creation, its properties should be computed and resolved
        _        <- propertiesService.updateAll()
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
            val category = restGroup.categoryId.orElse(Some(pair._2))
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
            change    = NodeGroupChangeRequest(DGModAction.CreateSolo, updated, restGroup.categoryId, Some(baseGroup))
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
              .process(query)
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
    val error = Inconsistency(s"Could not delete group '${id.serialize}', cause is: system groups cannot be deleted.")
    readGroup
      .getNodeGroupOpt(id)
      // error should be thrown when group is system
      .reject {
        case Some((group, _)) if group.isSystem => error
      }
      .flatMap {
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
      optCat   = restGroup.categoryId.orElse(Some(pair._2))
      change   = NodeGroupChangeRequest(DGModAction.Update, updated, optCat, Some(pair._1))
      res     <- createChangeRequest(diff, change, params, actor).toIO
    } yield {
      res
    }
  }

  def getCategoryTree(includeSystem: Boolean): IOResult[JRFullGroupCategory] = {
    def filterSystem(cat: FullNodeGroupCategory): FullNodeGroupCategory = {
      if (includeSystem) {
        cat
      } else {
        // system categories are only at root level
        cat.copy(
          subCategories = cat.subCategories.filterNot(_.isSystem),
          targetInfos = cat.targetInfos.filterNot(_.isSystem)
        )
      }
    }
    readGroup
      .getFullGroupLibrary()
      .map(c => JRFullGroupCategory.fromCategory(filterSystem(c), None))
  }

  def getCategoryDetails(id: NodeGroupCategoryId): IOResult[JRMinimalGroupCategory] = {
    for {
      root     <- readGroup.getFullGroupLibrary()
      category <- root.allCategories.get(id).notOptional(s"Cannot find Group category '${id.value}'")
      parent   <- root.parentCategories.get(id).notOptional(s"Cannot find Group category '${id.value}' parent")
    } yield {
      JRMinimalGroupCategory.fromCategory(category, parent.id)
    }
  }

  def deleteCategory(
      id: NodeGroupCategoryId
  )(implicit cc: ChangeContext): IOResult[JRMinimalGroupCategory] = {
    for {
      root     <- readGroup.getFullGroupLibrary()
      category <- root.allCategories.get(id).notOptional(s"Cannot find Group category '${id.value}'")
      _        <- ZIO.when(category.isSystem) {
                    Inconsistency(s"Could not delete group category '${id.value}', cause is: system categories cannot be deleted.").fail
                  }
      parent   <- root.parentCategories.get(id).notOptional(s"Cannot find Groupl category '${id.value}' parent")
      _        <- writeGroup.delete(id, cc.modId, cc.actor, cc.message)
    } yield {
      JRMinimalGroupCategory.fromCategory(category, parent.id)
    }
  }

  def updateCategory(
      id:       NodeGroupCategoryId,
      restData: JQGroupCategory
  )(implicit cc: ChangeContext): IOResult[JRMinimalGroupCategory] = {
    for {
      root      <- readGroup.getFullGroupLibrary()
      category  <- root.allCategories.get(id).notOptional(s"Cannot find Group category '${id.value}'")
      _         <- ZIO.when(category.isSystem) {
                     Inconsistency(s"Could not update group category '${id.value}', cause is: system categories cannot be updated.").fail
                   }
      oldParent <- root.parentCategories.get(id).notOptional(s"Cannot find Group category '${id.value}' parent")
      parent     = restData.parent.getOrElse(oldParent.id)
      update     = restData.update(category)
      _         <- writeGroup.saveGroupCategory(update.toNodeGroupCategory, parent, cc.modId, cc.actor, cc.message)
    } yield {
      JRMinimalGroupCategory.fromCategory(update, parent)
    }
  }

  // defaultId: the id to use if restDateDidn't provide one
  def createCategory(
      defaultId: => NodeGroupCategoryId,
      restData:  JQGroupCategory
  )(implicit cc: ChangeContext): IOResult[JRMinimalGroupCategory] = {
    for {
      update  <- restData.create(defaultId).toIO
      category = update.toNodeGroupCategory
      parent   = restData.parent.getOrElse(NodeGroupCategoryId("GroupRoot"))
      _       <- writeGroup.addGroupCategorytoCategory(category, parent, cc.modId, cc.actor, cc.message)
    } yield {
      JRMinimalGroupCategory.fromCategory(update, parent)
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

      nodes <- nodeFactRepo.getAll().map(_.filterKeys(serverList.contains(_)).values)

      // gather successfully resolved properties, for now nothing is done when there is an error for a failed one for a group (contrary to a node)
      parentProperties      <-
        propertiesRepo.getGroupProps(groupId).notOptional(s"Group with ID '${groupId.serialize}' was not found")
      checkedNodeProperties <-
        // to check for potential type conflicts in the properties of node, we need to look at each node
        ZIO.when(nodes.nonEmpty) {
          ZIO.foreach(nodes) { nodeFact =>
            // for each property, find merged properties for nodes in the group
            propertiesRepo
              .getNodeProps(nodeFact.id)
              .map(
                _.orEmpty // having no node properties means it's empty for that node
                  .prependFailureMessage(
                    s"Inherited properties are in an error state when searching properties in relation with group ${allGroups
                        .get(groupId)
                        .map(_.nodeGroup.name)
                        .getOrElse("current group")} (with ID '${groupId.serialize}') and all its nodes: "
                  )
              )
              .map(resolvedProperties => {
                // for each property of the group, search all properties in node properties
                // and take them into account for property status
                val childProperties = resolvedProperties.resolved
                val success         = parentProperties.resolved.collect {
                  case p if p.prop.visibility == Displayed =>
                    // a single node prop should be matching the current one
                    // and we should validate the found the hierarchy of the child node property found
                    val matchingChildProperties = childProperties.collectFirst {
                      case cp if cp.prop.name == p.prop.name => cp.hierarchy
                    }
                    InheritedPropertyStatus.fromChildren(
                      p,
                      matchingChildProperties
                    )
                }
                val error           = resolvedProperties match {
                  case f: FailedNodePropertyHierarchy  => PropertyStatus.fromFailedHierarchy(f)
                  case s: SuccessNodePropertyHierarchy => Chunk.empty
                }

                success ++ error
              })
          }
        }
      groupPropertyStatuses: Iterable[PropertyStatus] = checkedNodeProperties match {
                                                          case None            => // no Node is group : just use resolved group properties
                                                            parentProperties match {
                                                              case s: SuccessNodePropertyHierarchy =>
                                                                s.resolved.map(InheritedPropertyStatus.from(_))
                                                              case f: FailedNodePropertyHierarchy  =>
                                                                PropertyStatus.fromFailedHierarchy(f)
                                                            }
                                                          case Some(nodeProps) =>
                                                            nodeProps.flatten // gather all properties on every node
                                                        }
    } yield {
      JRGroupInheritedProperties.fromGroup(
        groupId,
        Chunk.from(groupPropertyStatuses),
        renderInHtml
      )
    }
  }
}
