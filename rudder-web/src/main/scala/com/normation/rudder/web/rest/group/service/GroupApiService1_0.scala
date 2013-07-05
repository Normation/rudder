package com.normation.rudder.web.rest.group.service

import com.normation.rudder.repository._
import com.normation.utils.StringUuidGenerator
import com.normation.rudder.batch.AsyncDeploymentAgent
import com.normation.rudder.services.workflows._
import com.normation.rudder.web.services.rest.RestExtractorService
import com.normation.rudder.domain.policies._
import com.normation.eventlog.EventActor
import net.liftweb.common._
import com.normation.rudder.web.rest.RestUtils._
import net.liftweb.json._
import net.liftweb.json.JsonDSL._
import com.normation.rudder.web.rest._
import net.liftweb.http.Req
import com.normation.rudder.web.rest.group.RestGroup
import com.normation.eventlog.ModificationId
import com.normation.rudder.batch.AutomaticStartDeployment
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.services.queries.QueryProcessor
import com.normation.rudder.domain.nodes._

case class GroupApiService1_0 (
    readGroup             : RoNodeGroupRepository
  , writeGroup            : WoNodeGroupRepository
  , uuidGen              : StringUuidGenerator
  , asyncDeploymentAgent : AsyncDeploymentAgent
  , changeRequestService : ChangeRequestService
  , workflowService      : WorkflowService
  , restExtractor        : RestExtractorService
  , queryProcessor : QueryProcessor
  , workflowEnabled      : Boolean
  ) {


  private[this] def createChangeRequestAndAnswer (
      id           : String
    , diff         : ChangeRequestNodeGroupDiff
    , group        : NodeGroup
    , initialState : Option[NodeGroup]
    , actor        : EventActor
    , message      : String
  ) (implicit action : String, prettify : Boolean) = {
    ( for {
        cr <- changeRequestService.createChangeRequestFromNodeGroup(
                  message
                , message
                , group
                , initialState
                , diff
                , actor
                , None
              )
        wfStarted <- workflowService.startWorkflow(cr.id, actor, None)
      } yield {
        cr.id
      }
    ) match {
      case Full(x) =>
        val jsonGroup = List(toJSON(group))
        toJsonResponse(id, ("groups" -> JArray(jsonGroup)), RestOk)
      case eb:EmptyBox =>
        val fail = eb ?~ (s"Could not save changes on Group ${id}" )
        val msg = s"${message} failed, cause is: ${fail.msg}."
        toJsonResponse(id, msg, RestError)
    }
  }

  def listGroups(req : Req) = {
    implicit val action = "listGroups"
    implicit val prettify = restExtractor.extractPrettify(req.params)
    readGroup.getAll match {
      case Full(groups) =>
        toJsonResponse("N/A", ( "groups" -> JArray(groups.map(toJSON(_)).toList)))
      case eb: EmptyBox =>
        val message = (eb ?~ ("Could not fetch Groups")).msg
        toJsonResponse("N/A", message, RestError)
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
      writeGroup.create(newGroup, nodeGroupCategoryId, modId, actor, None) match {
        case Full(x) =>
          asyncDeploymentAgent ! AutomaticStartDeployment(modId,actor)
          val jsonGroup = List(toJSON(newGroup))
          toJsonResponse(groupId.value, ("groups" -> JArray(jsonGroup)), RestOk)

        case eb:EmptyBox =>
          val fail = eb ?~ (s"Could not save Group ${groupId.value}" )
          val message = s"Could not create Group ${newGroup.name} (id:${groupId.value}) cause is: ${fail.msg}."
          toJsonResponse(groupId.value, message, RestError)
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
                    toJsonResponse(groupId.value, message, RestError)
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
                val enableCheck = restGroup.onlyName || (!workflowEnabled && defaultEnabled)
                val baseGroup = NodeGroup(groupId,name,"",None,true,Set(),enableCheck)
                restExtractor.extractNodeGroupCategoryId(req.params) match {
                  case Full(category) =>
                    // The enabled value in restGroup will be used in the saved Group
                    actualGroupCreation(restGroup.copy(enabled = Some(enableCheck)),baseGroup,category)
                  case eb:EmptyBox =>
                    val fail = eb ?~ (s"Could not node group category" )
                    val message = s"Could not create Group ${name} (id:${groupId.value}): cause is: ${fail.msg}."
                    toJsonResponse(groupId.value, message, RestError)
                }

              // More than one source, make an error
              case _ =>
                val message = s"Could not create Group ${name} (id:${groupId.value}) based on an already existing Group, cause is : too many values for source parameter."
                toJsonResponse(groupId.value, message, RestError)
            }

          case None =>
            val message =  s"Could not get create a Group details because there is no value as display name."
            toJsonResponse(groupId.value, message, RestError)
        }

      case eb : EmptyBox =>
        val fail = eb ?~ (s"Could extract values from request" )
        val message = s"Could not create Group ${groupId.value} cause is: ${fail.msg}."
        toJsonResponse(groupId.value, message, RestError)
    }
  }

  def groupDetails(id:String, req:Req) = {
    implicit val action = "groupDetails"
    implicit val prettify = restExtractor.extractPrettify(req.params)

    readGroup.getNodeGroup(NodeGroupId(id)) match {
      case Full((group,_)) =>
        val jsonGroup = List(toJSON(group))
        toJsonResponse(id,("groups" -> JArray(jsonGroup)))
      case eb:EmptyBox =>
        val fail = eb ?~!(s"Could not find Group ${id}" )
        val message=  s"Could not get Group ${id} details cause is: ${fail.msg}."
        toJsonResponse(id, message, RestError)
    }
  }

    def groupReload(id:String, req:Req) = {
    implicit val action = "groupReload"
    implicit val prettify = restExtractor.extractPrettify(req.params)
    val actor = RestUtils.getActor(req)

    readGroup.getNodeGroup(NodeGroupId(id)) match {
      case Full((group,_)) =>
        group.query match {
          case Some(query) => queryProcessor.process(query) match {
            case Full(nodeList) =>
              val updatedGroup = group.copy(serverList = nodeList.map(_.id).toSet)
              val deleteGroupDiff = DeleteNodeGroupDiff(updatedGroup)
              val message = s"Reload Group ${group.name} ${id} from API "
              createChangeRequestAndAnswer(id, deleteGroupDiff, group, Some(group), actor, message)
            case eb:EmptyBox =>
              val fail = eb ?~(s"Could not fetch Nodes" )
              val message=  s"Could not reload Group ${id} details cause is: ${fail.msg}."
              toJsonResponse(id, message, RestError)
          }
        }
        val jsonGroup = List(toJSON(group))
        toJsonResponse(id,("groups" -> JArray(jsonGroup)))
      case eb:EmptyBox =>
        val fail = eb ?~ (s"Could not find Group ${id}" )
        val message=  s"Could not reload Group ${id} details cause is: ${fail.msg}."
        toJsonResponse(id, message, RestError)
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
        createChangeRequestAndAnswer(id, deleteGroupDiff, group, Some(group), actor, message)

      case eb:EmptyBox =>
        val fail = eb ?~ (s"Could not find Group ${groupId.value}" )
        val message = s"Could not delete Group ${groupId.value} cause is: ${fail.msg}."
        toJsonResponse(groupId.value, message, RestError)
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
            createChangeRequestAndAnswer(id, diff, updatedGroup, Some(group), actor, message)

          case eb : EmptyBox =>
            val fail = eb ?~ (s"Could extract values from request" )
            val message = s"Could not modify Group ${groupId.value} cause is: ${fail.msg}."
            toJsonResponse(groupId.value, message, RestError)
        }

      case eb:EmptyBox =>
        val fail = eb ?~ (s"Could not find Group ${groupId.value}" )
        val message = s"Could not modify Group ${groupId.value} cause is: ${fail.msg}."
        toJsonResponse(groupId.value, message, RestError)
    }
  }

  def toJSON (group : NodeGroup) : JValue = {
  val query = group.query.map(query => query.toJSON)
    ("id" -> group.id.value) ~
    ("displayName" -> group.name) ~
    ("description" -> group.description) ~
    ("query" -> query) ~
    ("nodeIds" -> group.serverList.map(_.value)) ~
    ("isDynamic" -> group.isDynamic) ~
    ("isEnabled" -> group.isEnabled )
    }
  }