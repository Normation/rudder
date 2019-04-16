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

import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.rudder.UserService
import com.normation.rudder.batch.AsyncDeploymentActor
import com.normation.rudder.batch.AutomaticStartDeployment
import com.normation.rudder.domain.nodes._
import com.normation.rudder.repository.RoNodeGroupRepository
import com.normation.rudder.repository.WoNodeGroupRepository
import com.normation.rudder.rest.ApiVersion
import com.normation.rudder.rest.RestExtractorService
import com.normation.rudder.rest.RestUtils._
import com.normation.rudder.rest._
import com.normation.rudder.rest.data._
import com.normation.rudder.rest.{GroupApi => API}
import com.normation.rudder.services.queries.QueryProcessor
import com.normation.rudder.services.workflows._
import com.normation.utils.StringUuidGenerator
import net.liftweb.common._
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import net.liftweb.json.JsonDSL._
import net.liftweb.json._

import com.normation.box._

class GroupsApi(
    readGroup           : RoNodeGroupRepository
  , restExtractorService: RestExtractorService
  , uuidGen             : StringUuidGenerator
  , serviceV2           : GroupApiService2
  , serviceV5           : GroupApiService5
  , serviceV6           : GroupApiService6
) extends LiftApiModuleProvider[API] {


  def schemas = API

  /*
   * The actual builder for the compliance API.
   * Depends of authz method and supported version.
   */
  def getLiftEndpoints(): List[LiftApiModule] = {
    API.endpoints.map(e => e match {
        case API.ListGroups  => List
        case API.GroupDetails => Get
        case API.DeleteGroup  => Delete
        case API.CreateGroup => Create
        case API.UpdateGroup => Update
        case API.GetGroupTree => GetTree
        case API.DeleteGroupCategory => DeleteCategory
        case API.CreateGroupCategory => CreateCategory
        case API.GetGroupCategoryDetails => GetCategory
        case API.ReloadGroup => Reload
        case API.UpdateGroupCategory => UpdateCategory
    }).toList
  }

  object List extends LiftApiModule0 {
    val schema = API.ListGroups
    val restExtractor = restExtractorService
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      serviceV2.listGroups(req, version)
    }
  }
  object Get extends LiftApiModule {
    val schema = API.GroupDetails
    val restExtractor = restExtractorService
    def process(version: ApiVersion, path: ApiPath, id: String, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      println("****** in API.GroupDetails")
      serviceV2.groupDetails(id, req, version)
    }
  }
  object Delete extends LiftApiModule {
    val schema = API.DeleteGroup
    val restExtractor = restExtractorService
    def process(version: ApiVersion, path: ApiPath, id: String, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      serviceV2.deleteGroup(id, req, version)
    }
  }
  object Create extends LiftApiModule0 {
    val schema = API.CreateGroup
    val restExtractor = restExtractorService
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      if(req.json_?) {
        req.json match {
          case Full(arg) =>
            val restGroup = restExtractor.extractGroupFromJSON(arg)
            if(version.value < 5) {
             serviceV2.createGroup(restGroup, req, version)
            } else {
              serviceV5.createGroup(restGroup, req, version)
            }
          case eb:EmptyBox=>
            toJsonError(None, "No Json data sent")("createGroup",restExtractor.extractPrettify(req.params))
        }
      } else {
        val restGroup = restExtractor.extractGroup(req.params)
        if(version.value < 5) {
          serviceV2.createGroup(restGroup, req, version)
        } else {
          serviceV5.createGroup(restGroup, req, version)
        }
      }
    }
  }
  object Update extends LiftApiModule {
    val schema = API.UpdateGroup
    val restExtractor = restExtractorService
    def process(version: ApiVersion, path: ApiPath, id: String, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      if(req.json_?) {
        req.json match {
          case Full(arg) =>
            val restGroup = restExtractor.extractGroupFromJSON(arg)
            serviceV2.updateGroup(id, req, restGroup, version)
          case eb:EmptyBox=>
            toJsonError(None, "No Json data sent")("updateGroup",restExtractor.extractPrettify(req.params))
        }
      } else {
        val restGroup = restExtractor.extractGroup(req.params)
        serviceV2.updateGroup(id, req, restGroup, version)
      }
    }
  }
  object Reload extends LiftApiModule {
    val schema = API.ReloadGroup
    val restExtractor = restExtractorService
    def process(version: ApiVersion, path: ApiPath, id: String, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      serviceV2.reloadGroup(id, req, version)
    }
  }

  import RestUtils._
  import net.liftweb.json._

  def response ( function : Box[JValue], req : Req, errorMessage : String, id : Option[String])(implicit action: String): LiftResponse = {
    RestUtils.response(restExtractorService,  "groupCategories", id)(function, req, errorMessage)
  }

  def actionResponse ( function : Box[ActionType], req : Req, errorMessage : String, id : Option[String], actor: EventActor)(implicit action: String): LiftResponse = {
    RestUtils.actionResponse2(restExtractorService,  "groupCategories", uuidGen, id)(function, req, errorMessage)(action, actor)
  }

  // group categories
  object GetTree extends LiftApiModule0 {
    val schema = API.GetGroupTree
    val restExtractor = restExtractorService
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      serviceV2.listGroups(req, version)
    }
  }
  object GetCategory extends LiftApiModule {
    val schema = API.GetGroupCategoryDetails
    val restExtractor = restExtractorService
    def process(version: ApiVersion, path: ApiPath, id: String, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      implicit val action = schema.name
      response (
          serviceV6.getCategoryDetails(NodeGroupCategoryId(id), version)
        , req
        , s"Could not fetch Group category '${id}' details"
        , Some(id)
      )
    }
  }
  object DeleteCategory extends LiftApiModule {
    val schema = API.DeleteGroupCategory
    val restExtractor = restExtractorService
    def process(version: ApiVersion, path: ApiPath, id: String, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      implicit val action = schema.name
      actionResponse(
          Full(serviceV6.deleteCategory(NodeGroupCategoryId(id), version))
        , req
        , s"Could not delete Group category '${id}'"
        , Some(id)
        , authzToken.actor
      )
    }
  }
  object UpdateCategory extends LiftApiModule {
    val schema = API.UpdateGroupCategory
    val restExtractor = restExtractorService
    def process(version: ApiVersion, path: ApiPath, id: String, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      implicit val action = schema.name
      val x = for {
        restCategory <- { if(req.json_?) {
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
          x
        , req
        , s"Could not update Group category '${id}'"
        , Some(id)
        , authzToken.actor
      )
    }
  }
  object CreateCategory extends LiftApiModule0 {
    val schema = API.CreateGroupCategory
    val restExtractor = restExtractorService
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      implicit val action = schema.name
      val id = NodeGroupCategoryId(uuidGen.newUuid)
      val x = for {
        restCategory <- { if(req.json_?) {
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
          x
        , req
        , s"Could not update Group category '${id.value}'"
        , Some(id.value)
        , authzToken.actor
      )
    }
  }
}


class GroupApiService2 (
    readGroup            : RoNodeGroupRepository
  , writeGroup           : WoNodeGroupRepository
  , uuidGen              : StringUuidGenerator
  , asyncDeploymentAgent : AsyncDeploymentActor
  , workflowLevelService : WorkflowLevelService
  , restExtractor        : RestExtractorService
  , queryProcessor       : QueryProcessor
  , restDataSerializer   : RestDataSerializer
) ( implicit userService : UserService ) extends Loggable {

  import RestUtils._
  import restDataSerializer._

  private[this] def createChangeRequestAndAnswer (
      id           : String
    , diff         : ChangeRequestNodeGroupDiff
    , group        : NodeGroup
    , initialState : Option[NodeGroup]
    , actor        : EventActor
    , req          : Req
    , act          : DGModAction
    , apiVersion   : ApiVersion
  ) (implicit action : String, prettify : Boolean) = {

    val change = NodeGroupChangeRequest(act, group, None, initialState)

    ( for {
        reason    <- restExtractor.extractReason(req)
        crName    <- restExtractor.extractChangeRequestName(req).map(_.getOrElse(s"${act.name} group '${group.name}' from API"))
        workflow  <- workflowLevelService.getForNodeGroup(actor, change)
        cr        =  ChangeRequestService.createChangeRequestFromNodeGroup(
                        crName
                      , restExtractor.extractChangeRequestDescription(req)
                      , group
                      , initialState
                      , diff
                      , actor
                      , reason
                    )
        id        <- workflow.startWorkflow(cr, actor, None)
      } yield {
        (id, workflow)
      }
    ) match {
      case Full((crId, workflow)) =>
        val optCrId = if (workflow.needExternalValidation()) Some(crId) else None
        val jsonGroup = List(serializeGroup(group, optCrId, apiVersion))
        toJsonResponse(Some(id), ("groups" -> JArray(jsonGroup)))

      case eb:EmptyBox =>
        val fail = eb ?~ (s"Could not save changes on Group ${id}" )
        val msg = s"${act} failed, cause is: ${fail.msg}."
        toJsonError(Some(id), msg)
    }
  }

  def listGroups(req : Req, apiVersion: ApiVersion) = {
    implicit val action = "listGroups"
    implicit val prettify = restExtractor.extractPrettify(req.params)
    readGroup.getAll.toBox match {
      case Full(groups) =>
        toJsonResponse(None, ( "groups" -> JArray(groups.map(g => serializeGroup(g, None, apiVersion)).toList)))
      case eb: EmptyBox =>
        val message = (eb ?~ ("Could not fetch Groups")).msg
        toJsonError(None, message)
    }
  }

  def createGroup(restGroup: Box[RestGroup], req:Req, apiVersion: ApiVersion) = {
    implicit val action = "createGroup"
    implicit val prettify = restExtractor.extractPrettify(req.params)
    val modId = ModificationId(uuidGen.newUuid)
    val actor = RestUtils.getActor(req)
    val groupId = NodeGroupId(req.param("id").getOrElse(uuidGen.newUuid))

    def actualGroupCreation(change: NodeGroupChangeRequest) = {
      ( for {
        reason   <- restExtractor.extractReason(req)
        saveDiff <- writeGroup.create(change.newGroup, change.category.getOrElse(readGroup.getRootCategory.id), modId, actor, reason).toBox
      } yield {
        saveDiff
      } ) match {
        case Full(x) =>
          if (x.needDeployment) {
            // Trigger a deployment only if it is needed
            asyncDeploymentAgent ! AutomaticStartDeployment(modId,actor)
          }
          val jsonGroup = List(serializeGroup(change.newGroup, None, apiVersion))
          toJsonResponse(Some(groupId.value), ("groups" -> JArray(jsonGroup)))

        case eb:EmptyBox =>
          val fail = eb ?~ (s"Could not save Group ${groupId.value}" )
          val message = s"Could not create Group ${change.newGroup.name} (id:${groupId.value}) cause is: ${fail.msg}."
          toJsonError(Some(groupId.value), message)
      }
    }

    // decide if we should create a new rule or clone an existing one
    // Return the source rule to use in each case.
    def createOrClone(actor: EventActor, restGroup: RestGroup, id: NodeGroupId, name: String, sourceIdParam: Option[List[String]]): Box[NodeGroupChangeRequest] = {
      sourceIdParam match {
        case Some(sourceId :: Nil) =>
          // clone existing rule
          for {
            (group, cat) <- readGroup.getNodeGroup(NodeGroupId(sourceId)).toBox ?~!
              s"Could not create group ${name} (id:${id.value}) by cloning group '${sourceId}')"
          } yield {
            // in that case, we take rest category and if empty, we default to cloned group category
            val category = restGroup.category.orElse(Some(cat))
            NodeGroupChangeRequest(DGModAction.CreateSolo, restGroup.updateGroup(group), category, Some(group))
          }

        case None =>
          // If enable is missing in parameter consider it to true
          val defaultEnabled = restGroup.enabled.getOrElse(true)
          // create from scratch - base rule is the same with default values
          val baseGroup = NodeGroup(groupId,name,"",None,true,Set(), defaultEnabled)

          val change = NodeGroupChangeRequest(DGModAction.CreateSolo, restGroup.updateGroup(baseGroup), restGroup.category, Some(baseGroup))

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
            workflow <- workflowLevelService.getForNodeGroup(actor, change) ?~! "Could not find workflow status for that rule creation"
          } yield {
            // we don't actually start a workflow, we only disable the rule if a workflow should be
            // started. Update rule "enable" status accordingly.
            val enableCheck = restGroup.onlyName || (!workflow.needExternalValidation() && defaultEnabled)
            // Then enabled value in restRule will be used in the saved Rule
            change.copy(newGroup = change.newGroup.copy(_isEnabled = enableCheck))
          }

        case _                     =>
          Failure(s"Could not create Group ${name} (id:${groupId.value}) based on an already existing group, cause is: too many values for source parameter.")
      }
    }

    (for {
      group  <- restGroup ?~! s"Could extract values from request"
      name   <- Box(group.name) ?~! "Missing mandatory value for group name"
      change <- createOrClone(actor, group, groupId, name, req.params.get("source"))
    } yield {
      actualGroupCreation(change)
    }) match {
      case Full(resp)   =>
        resp
      case eb: EmptyBox =>
        val fail = eb ?~ (s"Error when creating new rule" )
        toJsonError(Some(groupId.value), fail.messageChain)
    }
  }

  def groupDetails(id:String, req:Req, apiVersion: ApiVersion) = {
    implicit val action = "groupDetails"
    implicit val prettify = restExtractor.extractPrettify(req.params)
    println("****** Read group never ending ?")
    readGroup.getNodeGroup(NodeGroupId(id)).toBox match {
      case Full((group,_)) =>
        val jsonGroup = List(serializeGroup(group,None,apiVersion))
        toJsonResponse(Some(id),("groups" -> JArray(jsonGroup)))
      case eb:EmptyBox =>
        val fail = eb ?~!(s"Could not find Group ${id}" )
        val message=  s"Could not get Group ${id} details cause is: ${fail.msg}."
        toJsonError(Some(id), message)
    }
  }

  def reloadGroup(id:String, req:Req, apiVersion: ApiVersion) = {
    implicit val action = "reloadGroup"
    implicit val prettify = restExtractor.extractPrettify(req.params)
    val actor = RestUtils.getActor(req)

    readGroup.getNodeGroup(NodeGroupId(id)).toBox match {
      case Full((group,_)) =>
        group.query match {
          case Some(query) => queryProcessor.process(query) match {
            case Full(nodeList) =>
              val updatedGroup = group.copy(serverList = nodeList.map(_.id).toSet)
              val reloadGroupDiff = ModifyToNodeGroupDiff(updatedGroup)
              createChangeRequestAndAnswer(id, reloadGroupDiff, group, Some(group), actor, req, DGModAction.Update, apiVersion)
            case eb:EmptyBox =>
              val fail = eb ?~(s"Could not fetch Nodes" )
              val message=  s"Could not reload Group ${id} details cause is: ${fail.msg}."
              toJsonError(Some(id), message)
          }
          case None =>
            val jsonGroup = List(serializeGroup(group,None, apiVersion))
            toJsonResponse(Some(id),("groups" -> JArray(jsonGroup)))
        }
        val jsonGroup = List(serializeGroup(group,None, apiVersion))
        toJsonResponse(Some(id),("groups" -> JArray(jsonGroup)))
      case eb:EmptyBox =>
        val fail = eb ?~ (s"Could not find Group ${id}" )
        val message=  s"Could not reload Group ${id} details cause is: ${fail.msg}."
        toJsonError(Some(id), message)
    }
  }

  def deleteGroup(id:String, req:Req, apiVersion: ApiVersion) = {
    implicit val action = "deleteGroup"
    implicit val prettify = restExtractor.extractPrettify(req.params)
    val actor = RestUtils.getActor(req)
    val groupId = NodeGroupId(id)

    readGroup.getNodeGroup(groupId).toBox match {
      case Full((group,_)) =>
        val deleteGroupDiff = DeleteNodeGroupDiff(group)
        createChangeRequestAndAnswer(id, deleteGroupDiff, group, Some(group), actor, req, DGModAction.Delete, apiVersion)

      case eb:EmptyBox =>
        val fail = eb ?~ (s"Could not find Group ${groupId.value}" )
        val message = s"Could not delete Group ${groupId.value} cause is: ${fail.msg}."
        toJsonError(Some(groupId.value), message)
    }
  }

  def updateGroup(id: String, req: Req, restValues : Box[RestGroup], apiVersion: ApiVersion) = {
    implicit val action = "updateGroup"
    implicit val prettify = restExtractor.extractPrettify(req.params)
    val actor = getActor(req)
    val groupId = NodeGroupId(id)

    readGroup.getNodeGroup(groupId).toBox match {
      case Full((group,_)) =>
        restValues match {
          case Full(restGroup) =>
            val updatedGroup = restGroup.updateGroup(group)
            val diff = ModifyToNodeGroupDiff(updatedGroup)
            createChangeRequestAndAnswer(id, diff, updatedGroup, Some(group), actor, req, DGModAction.Update, apiVersion)

          case eb : EmptyBox =>
            val fail = eb ?~ (s"Could extract values from request" )
            val message = s"Could not modify Group ${groupId.value} cause is: ${fail.msg}."
            toJsonError(Some(groupId.value), message)
        }

      case eb:EmptyBox =>
        val fail = eb ?~ (s"Could not find Group ${groupId.value}" )
        val message = s"Could not modify Group ${groupId.value} cause is: ${fail.msg}."
        toJsonError(Some(groupId.value), message)
    }
  }

}

class GroupApiService5 (
    apiService2            : GroupApiService2
  ) extends Loggable {

  def createGroup(restGroup: Box[RestGroup], req:Req, apiVersion: ApiVersion) = {
    val restGroupChecked =
      restGroup match {
          case Full(RestGroup(_,_,None,_,_,_)) => Failure("Cannot create a group with an empty query")
          case a => a
      }
    apiService2.createGroup(restGroupChecked, req, apiVersion)
  }
}

class GroupApiService6 (
    readGroup         : RoNodeGroupRepository
  , writeGroup        : WoNodeGroupRepository
  , restDataSerializer: RestDataSerializer
) extends Loggable {

  def getCategoryTree(apiVersion: ApiVersion) = {
    for {
        root <- readGroup.getFullGroupLibrary
    } yield {
      restDataSerializer.serializeGroupCategory(root, root.id, FullDetails, apiVersion)
    }
  }

  def getCategoryDetails(id : NodeGroupCategoryId, apiVersion: ApiVersion) = {
    for {
      root     <- readGroup.getFullGroupLibrary.toBox
      category <- Box(root.allCategories.get(id)) ?~! s"Cannot find Group category '${id.value}'"
      parent   <- Box(root.parentCategories.get(id)) ?~! s"Cannot find Group category '${id.value}' parent"
    } yield {
      restDataSerializer.serializeGroupCategory(category, parent.id, MinimalDetails, apiVersion)
    }
  }

  def deleteCategory(id : NodeGroupCategoryId, apiVersion: ApiVersion)(actor : EventActor, modId : ModificationId, reason : Option[String]) = {
    for {
      root     <- readGroup.getFullGroupLibrary.toBox
      category <- Box(root.allCategories.get(id)) ?~! s"Cannot find Group category '${id.value}'"
      parent   <- Box(root.parentCategories.get(id)) ?~! s"Cannot find Groupl category '${id.value}' parent"
      _        <- writeGroup.delete(id, modId, actor, reason).toBox
    } yield {
      restDataSerializer.serializeGroupCategory(category, parent.id, MinimalDetails, apiVersion)
    }
  }

  def updateCategory(id : NodeGroupCategoryId, restData: RestGroupCategory, apiVersion: ApiVersion)(actor : EventActor, modId : ModificationId, reason : Option[String]) = {
    for {
      root      <- readGroup.getFullGroupLibrary.toBox
      category  <- Box(root.allCategories.get(id)) ?~! s"Cannot find Group category '${id.value}'"
      oldParent <- Box(root.parentCategories.get(id)) ?~! s"Cannot find Group category '${id.value}' parent"
      parent    =  restData.parent.getOrElse(oldParent.id)
      update    =  restData.update(category)
      _         <- writeGroup.saveGroupCategory(update.toNodeGroupCategory,parent, modId, actor, reason).toBox
    } yield {
      restDataSerializer.serializeGroupCategory(update, parent, MinimalDetails, apiVersion)
    }
  }

  def createCategory(id : NodeGroupCategoryId, restData: RestGroupCategory, apiVersion: ApiVersion)(actor : EventActor, modId : ModificationId, reason : Option[String]) = {
    for {
      update   <- restData.create(id)
      category =  update.toNodeGroupCategory
      parent   =  restData.parent.getOrElse(NodeGroupCategoryId("GroupRoot"))
      _        <- writeGroup.addGroupCategorytoCategory(category,parent, modId, actor, reason).toBox
    } yield {
      restDataSerializer.serializeGroupCategory(update, parent, MinimalDetails, apiVersion)
    }
  }

}

