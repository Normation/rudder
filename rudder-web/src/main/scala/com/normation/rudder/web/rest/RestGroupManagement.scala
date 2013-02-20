package com.normation.rudder.web.rest

import net.liftweb.http.rest.RestHelper
import com.normation.rudder.repository._
import net.liftweb.json.JValue
import net.liftweb.json.JsonAST._
import com.normation.utils.StringUuidGenerator
import com.normation.rudder.domain.policies._
import com.normation.eventlog.ModificationId
import net.liftweb.common.Full
import net.liftweb.http.PlainTextResponse
import net.liftweb.common.EmptyBox
import com.normation.rudder.batch._
import com.normation.eventlog.EventActor
import net.liftweb.json.JsonDSL._
import net.liftweb.http.JsonResponse
import scala.util.Random
import net.liftweb.json.JValue
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.nodes.NodeGroup
class RestGroupManagement (
    readGroup : RoNodeGroupRepository
  , writeGroup : WoNodeGroupRepository
  , uuidGen           : StringUuidGenerator
  , asyncDeploymentAgent : AsyncDeploymentAgent
) extends RestHelper {
  serve( "api" / "groups" prefix {

    case Nil JsonGet _ =>
      implicit val action = "listGroups"
      readGroup.getAll match {
        case Full(groups) => toJsonResponse("N/A", JArray(groups.map(toJSONshort(_)).toList))
        case eb: EmptyBox => val message = (eb ?~ ("Could not fetch Groups")).msg
          toJsonResponse("N/A", message, RestError)

    }

   // case Get("list" :: Nil,_) => JArray(readGroup.getAll(false).getOrElse(Seq()).map(group => JString(group.id.value.toUpperCase)).toList)
  /*  case Get("add" :: name :: Nil, req) => { val group = Group(GroupId(uuidGen.newUuid),name,0)
      val modId = ModificationId(uuidGen.newUuid)
      val actor = RestUtils.getActor(req)
      writeGroup.create(group, modId, actor, Some(s"Create group ${group.name} (id:${group.id.value.toUpperCase}) ")) match {
      case Full(x) => asyncDeploymentAgent ! AutomaticStartDeployment(modId,actor)
      PlainTextResponse("ok")
      case eb:EmptyBox => PlainTextResponse("ko")
    }
    }*/

    case "delete" :: id :: Nil JsonDelete req => {
      implicit val action = "deleteGroup"
      val modId = ModificationId(uuidGen.newUuid)
      val actor = RestUtils.getActor(req)
      val groupId = NodeGroupId(id)
      writeGroup.delete(groupId, modId, actor, Some(s"Delete group ${id.toUpperCase}) ")) match {
      case Full(x) => asyncDeploymentAgent ! AutomaticStartDeployment(modId,actor)
        val message = s"Group ${id} deleted"
        toJsonResponse(id, message)
      case eb:EmptyBox => val fail = eb ?~ (s"Could not find Group ${id}" )
        val message = s"Could not delete Group ${id} cause is: ${fail.msg}"
        toJsonResponse(id, message, RestError)
    }
    }

    case Put("enable" :: id :: Nil, req) => {
      val modId = ModificationId(uuidGen.newUuid)
      val actor = RestUtils.getActor(req)
      ChangeGroupStatus(NodeGroupId(id),modId,actor,true)
    }

    case Put("disable" :: id :: Nil, req) => {
      val modId = ModificationId(uuidGen.newUuid)
      val actor = RestUtils.getActor(req)
      ChangeGroupStatus(NodeGroupId(id),modId,actor,false)
    }


    case Put("clone" :: id :: Nil, req) => {
      implicit val action = "cloneGroup"
      val modId = ModificationId(uuidGen.newUuid)
      val actor = RestUtils.getActor(req)
      val groupId = NodeGroupId(id)
      readGroup.getNodeGroup(groupId) match {
        case Full(group) =>
          val parent = readGroup.getParentGroupCategory(groupId).get
          writeGroup.createNodeGroup(
              s"Copy of <${group.name}> ${Random.nextInt(10000)}"
            , group.description
            , group.query
            , group.isDynamic
            , group.serverList
            , parent.id
            , false
            , modId
            , actor
            ,  Some(s"clone group ${group.name} (id:${group.id.value.toUpperCase}"
          )
            ) match {
              case Full(x) =>  asyncDeploymentAgent ! AutomaticStartDeployment(modId,actor)
                toJsonResponse(id,toJSON(x.group))
              case eb:EmptyBox => val fail = eb ?~ (s"Could not find Group ${id}" )
                val message = s"Could not clone Group ${group.name} (id:${group.id.value}) cause is: ${fail.msg}."
                toJsonResponse(id, message, RestError)
          }
        case eb:EmptyBox => val fail = eb ?~ (s"Could not find Group ${id}" )
          val message = s"Could not clone Group ${id} cause is: ${fail.msg}."
          toJsonResponse(id, message, RestError)
      }
    }


    case Get(id :: Nil, req) => {
      implicit val action = "groupDetails"
      readGroup.getNodeGroup(NodeGroupId(id)) match {
        case Full(x) =>
          toJsonResponse(id,toJSON(x))
        case eb:EmptyBox => val fail = eb ?~!(s"Could not find Group ${id}" )
          val message=  s"Could not get Group ${id} details cause is: ${fail.msg}."
          toJsonResponse(id, message, RestError)
      }
    }

  })

  def ChangeGroupStatus(id : NodeGroupId, modId : ModificationId, actor : EventActor, status : Boolean)={

    val past   = if (status) "enabled" else "disabled"
    val act    = if (status) "enable" else "disable"
    implicit val action = if (status) "enableGroup" else "disableGroup"
    readGroup.getNodeGroup(id) match {
      case Full(group) =>
        if (group.isEnabled==status){
          val message = s"Group ${id.value} already ${past}"
          toJsonResponse(id.value, message)
        }
        else
          writeGroup.update(
              group.copy(isEnabled = status)
            , modId
            , actor
            , Some(s"${act} Group ${group.name} (id:${group.id.value}) ")
          ) match {
            case Full(x) =>  asyncDeploymentAgent ! AutomaticStartDeployment(modId,actor)
              val message = s"Group ${group.name} (id:${group.id.value}) ${past}"
             toJsonResponse(id.value, message)
            case eb:EmptyBox => val fail = eb ?~ (s"Could not find Group ${id}" )
              val message = s"Could not ${act} Group ${group.name} (id:${group.id.value}) cause is: ${fail.msg}."
             toJsonResponse(id.value, message, RestError)
          }
      case eb:EmptyBox => val fail = eb ?~ (s"Could not find Group ${id}" )
        val message = s"Could not ${act} Group ${id.value} cause is: ${fail.msg}."
        toJsonResponse(id.value, message, RestError)
    }
  }


  def toJsonResponse(id:String, message:JValue, status:RestStatus = RestOk)(implicit action : String = "rest") = {

  JsonResponse((action ->
    ("id" -> id) ~
    ("status" -> status.status) ~
    ("message" -> message)
  ), status.code)

  }

  def toJSON (group : NodeGroup) : JValue = {
  val query = group.query.map(query => query.toJSON)
  ("group" ->
    ("id" -> group.id.value) ~
    ("displayName" -> group.name) ~
    ("description" -> group.description) ~
    ("query" -> query) ~
    ("nodeIds" -> group.serverList.map(_.value)) ~
    ("isDynamic" -> group.isDynamic) ~
    ("isEnabled" -> group.isEnabled ) ~
    ("isSystem" -> group.isSystem ))
  }
    def toJSONshort (group : NodeGroup) : JValue ={
  import net.liftweb.json.JsonDSL._
  ("group" ->
    ("id" -> group.id.value) ~
    ("displayName" -> group.name)
  )
  }


sealed trait RestStatus {
  def code : Int
  def status : String
}

object RestOk extends RestStatus{
  val code = 200
  val status = "Ok"
}

object RestError extends RestStatus{
  val code = 500
  val status = "Error"
}
}