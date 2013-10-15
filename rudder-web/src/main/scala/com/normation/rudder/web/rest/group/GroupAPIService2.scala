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

package com.normation.rudder.web.rest.group

import com.normation.rudder.repository._
import com.normation.utils.StringUuidGenerator
import com.normation.rudder.batch.AsyncDeploymentAgent
import com.normation.rudder.services.workflows._
import com.normation.rudder.web.rest.RestExtractorService
import com.normation.rudder.domain.policies._
import com.normation.eventlog.EventActor
import net.liftweb.common._
import com.normation.rudder.web.rest.RestUtils._
import net.liftweb.json._
import net.liftweb.json.JsonDSL._
import com.normation.rudder.web.rest._
import net.liftweb.http.Req
import com.normation.eventlog.ModificationId
import com.normation.rudder.batch.AutomaticStartDeployment
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.services.queries.QueryProcessor
import com.normation.rudder.domain.nodes._
import com.normation.rudder.domain.workflows.ChangeRequestId

case class GroupApiService2 (
    readGroup            : RoNodeGroupRepository
  , writeGroup           : WoNodeGroupRepository
  , uuidGen              : StringUuidGenerator
  , asyncDeploymentAgent : AsyncDeploymentAgent
  , changeRequestService : ChangeRequestService
  , workflowService      : WorkflowService
  , restExtractor        : RestExtractorService
  , queryProcessor       : QueryProcessor
  , workflowEnabled      : () => Box[Boolean]
  , restDataSerializer   : RestDataSerializer
  ) extends Loggable {

  import restDataSerializer.{ serializeGroup => serialize }

  private[this] def createChangeRequestAndAnswer (
      id           : String
    , diff         : ChangeRequestNodeGroupDiff
    , group        : NodeGroup
    , initialState : Option[NodeGroup]
    , actor        : EventActor
    , req          : Req
    , act          : String
  ) (implicit action : String, prettify : Boolean) = {

    ( for {
        reason <- restExtractor.extractReason(req.params)
        crName <- restExtractor.extractChangeRequestName(req.params).map(_.getOrElse(s"${act} Group ${group.name} from API"))
        crDescription = restExtractor.extractChangeRequestDescription(req.params)
        cr <- changeRequestService.createChangeRequestFromNodeGroup(
                  crName
                , crDescription
                , group
                , initialState
                , diff
                , actor
                , reason
              )
        wfStarted <- workflowService.startWorkflow(cr.id, actor, None)
      } yield {
        cr.id
      }
    ) match {
      case Full(crId) =>
        workflowEnabled() match {
          case Full(enabled) =>
            val optCrId = if (enabled) Some(crId) else None
            val jsonGroup = List(serialize(group,optCrId))
            toJsonResponse(Some(id), ("groups" -> JArray(jsonGroup)))
          case eb : EmptyBox =>
            val fail = eb ?~ (s"Could not check workflow property" )
            val msg = s"Change request creation failed, cause is: ${fail.msg}."
            toJsonError(Some(id), msg)
        }
      case eb:EmptyBox =>
        val fail = eb ?~ (s"Could not save changes on Group ${id}" )
        val msg = s"${act} failed, cause is: ${fail.msg}."
        toJsonError(Some(id), msg)
    }
  }

  def listGroups(req : Req) = {
    implicit val action = "listGroups"
    implicit val prettify = restExtractor.extractPrettify(req.params)
    readGroup.getAll match {
      case Full(groups) =>
        toJsonResponse(None, ( "groups" -> JArray(groups.map(g => serialize(g,None)).toList)))
      case eb: EmptyBox =>
        val message = (eb ?~ ("Could not fetch Groups")).msg
        toJsonError(None, message)
    }
  }

  def createGroup(restGroup: Box[RestGroup], req:Req) = {
    implicit val action = "createGroup"
    implicit val prettify = restExtractor.extractPrettify(req.params)
    val modId = ModificationId(uuidGen.newUuid)
    val actor = RestUtils.getActor(req)
    val groupId = NodeGroupId(req.param("id").getOrElse(uuidGen.newUuid))

    def actualGroupCreation(restGroup : RestGroup, baseGroup : NodeGroup, nodeGroupCategoryId: NodeGroupCategoryId) = {
      val newGroup = restGroup.updateGroup( baseGroup )
      ( for {
        reason   <- restExtractor.extractReason(req.params)
        saveDiff <- writeGroup.create(newGroup, nodeGroupCategoryId, modId, actor, reason)
      } yield {
        saveDiff
      } ) match {
        case Full(x) =>
          asyncDeploymentAgent ! AutomaticStartDeployment(modId,actor)
          val jsonGroup = List(serialize(newGroup,None))
          toJsonResponse(Some(groupId.value), ("groups" -> JArray(jsonGroup)))

        case eb:EmptyBox =>
          val fail = eb ?~ (s"Could not save Group ${groupId.value}" )
          val message = s"Could not create Group ${newGroup.name} (id:${groupId.value}) cause is: ${fail.msg}."
          toJsonError(Some(groupId.value), message)
      }
    }

    restGroup match {
      case Full(restGroup) =>
        restGroup.name match {
          case Some(name) =>
            req.params.get("source") match {
              // Cloning
              case Some(sourceId :: Nil) =>
                readGroup.getNodeGroup(NodeGroupId(sourceId)) match {
                  case Full((sourceGroup,category)) =>
                    // disable rest Group if cloning
                    actualGroupCreation(restGroup.copy(enabled = Some(false)),sourceGroup.copy(id=groupId),category)
                  case eb:EmptyBox =>
                    val fail = eb ?~ (s"Could not find Group ${sourceId}" )
                    val message = s"Could not create Group ${name} (id:${groupId.value}) based on Group ${sourceId} : cause is: ${fail.msg}."
                    toJsonError(Some(groupId.value), message)
                }

              // Create a new Group
              case None =>
                // If enable is missing in parameter consider it to true
                val defaultEnabled = restGroup.enabled.getOrElse(true)

                // if only the name parameter is set, consider it to be enabled
                // if not if workflow are enabled, consider it to be disabled
                // if there is no workflow, use the value used as parameter (default to true)
                // code extract :
                /*re
                 * if (restGroup.onlyName) true
                 * else if (workflowEnabled) false
                 * else defaultEnabled
                 */


                workflowEnabled() match {
                  case Full(enabled) =>
                    val enableCheck = restGroup.onlyName || (!enabled && defaultEnabled)
                    val baseGroup = NodeGroup(groupId,name,"",None,true,Set(),enableCheck)
                    restExtractor.extractNodeGroupCategoryId(req.params) match {
                      case Full(category) =>
                        // The enabled value in restGroup will be used in the saved Group
                        actualGroupCreation(restGroup.copy(enabled = Some(enableCheck)),baseGroup,category)
                      case eb:EmptyBox =>
                        val fail = eb ?~ (s"Could not node group category" )
                        val message = s"Could not create Group ${name} (id:${groupId.value}): cause is: ${fail.msg}."
                        toJsonError(Some(groupId.value), message)
                    }
                  case eb : EmptyBox =>
                    val fail = eb ?~ (s"Could not check workflow property" )
                    val msg = s"Change request creation failed, cause is: ${fail.msg}."
                    toJsonError(Some(groupId.value), msg)
                }
              // More than one source, make an error
              case _ =>
                val message = s"Could not create Group ${name} (id:${groupId.value}) based on an already existing Group, cause is : too many values for source parameter."
                toJsonError(Some(groupId.value), message)
            }

          case None =>
            val message =  s"Could not get create a Group details because there is no value as display name."
            toJsonError(Some(groupId.value), message)
        }

      case eb : EmptyBox =>
        val fail = eb ?~ (s"Could extract values from request" )
        val message = s"Could not create Group ${groupId.value} cause is: ${fail.msg}."
        toJsonError(Some(groupId.value), message)
    }
  }

  def groupDetails(id:String, req:Req) = {
    implicit val action = "groupDetails"
    implicit val prettify = restExtractor.extractPrettify(req.params)

    readGroup.getNodeGroup(NodeGroupId(id)) match {
      case Full((group,_)) =>
        val jsonGroup = List(serialize(group,None))
        toJsonResponse(Some(id),("groups" -> JArray(jsonGroup)))
      case eb:EmptyBox =>
        val fail = eb ?~!(s"Could not find Group ${id}" )
        val message=  s"Could not get Group ${id} details cause is: ${fail.msg}."
        toJsonError(Some(id), message)
    }
  }

    def reloadGroup(id:String, req:Req) = {
    implicit val action = "reloadGroup"
    implicit val prettify = restExtractor.extractPrettify(req.params)
    val actor = RestUtils.getActor(req)

    readGroup.getNodeGroup(NodeGroupId(id)) match {
      case Full((group,_)) =>
        group.query match {
          case Some(query) => queryProcessor.process(query) match {
            case Full(nodeList) =>
              val updatedGroup = group.copy(serverList = nodeList.map(_.id).toSet)
              val reloadGroupDiff = ModifyToNodeGroupDiff(updatedGroup)
              val message = s"Reload Group ${group.name} ${id} from API "
              createChangeRequestAndAnswer(id, reloadGroupDiff, group, Some(group), actor, req, "Reload")
            case eb:EmptyBox =>
              val fail = eb ?~(s"Could not fetch Nodes" )
              val message=  s"Could not reload Group ${id} details cause is: ${fail.msg}."
              toJsonError(Some(id), message)
          }
          case None =>
            val jsonGroup = List(serialize(group,None))
            toJsonResponse(Some(id),("groups" -> JArray(jsonGroup)))
        }
        val jsonGroup = List(serialize(group,None))
        toJsonResponse(Some(id),("groups" -> JArray(jsonGroup)))
      case eb:EmptyBox =>
        val fail = eb ?~ (s"Could not find Group ${id}" )
        val message=  s"Could not reload Group ${id} details cause is: ${fail.msg}."
        toJsonError(Some(id), message)
    }
  }

  def deleteGroup(id:String, req:Req) = {
    implicit val action = "deleteGroup"
    implicit val prettify = restExtractor.extractPrettify(req.params)
    val actor = RestUtils.getActor(req)
    val groupId = NodeGroupId(id)

    readGroup.getNodeGroup(groupId) match {
      case Full((group,_)) =>
        val deleteGroupDiff = DeleteNodeGroupDiff(group)
        val message = s"Delete Group ${group.name} ${id} from API "
        createChangeRequestAndAnswer(id, deleteGroupDiff, group, Some(group), actor, req, "Delete")

      case eb:EmptyBox =>
        val fail = eb ?~ (s"Could not find Group ${groupId.value}" )
        val message = s"Could not delete Group ${groupId.value} cause is: ${fail.msg}."
        toJsonError(Some(groupId.value), message)
    }
  }

  def updateGroup(id: String, req: Req, restValues : Box[RestGroup]) = {
    implicit val action = "updateGroup"
    implicit val prettify = restExtractor.extractPrettify(req.params)
    val actor = getActor(req)
    val groupId = NodeGroupId(id)

    readGroup.getNodeGroup(groupId) match {
      case Full((group,_)) =>
        restValues match {
          case Full(restGroup) =>
            val updatedGroup = restGroup.updateGroup(group)
            val diff = ModifyToNodeGroupDiff(updatedGroup)
            val message = s"Modify Group ${group.name} ${id} from API "
            createChangeRequestAndAnswer(id, diff, updatedGroup, Some(group), actor, req, "Update")

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