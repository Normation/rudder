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
import com.normation.cfclerk.domain.Technique
class RestDirectiveManagement (
    readDirective : RoDirectiveRepository
  , writeDirective : WoDirectiveRepository
  , uuidGen           : StringUuidGenerator
  , asyncDeploymentAgent : AsyncDeploymentAgent
) extends RestHelper {
  serve( "api" / "directives" prefix {

    case Nil JsonGet _ =>
      implicit val action = "listDirectives"
      readDirective.getAll(false) match {
        case Full(directives) => toJsonResponse("N/A", JArray(directives.map(toJSONshort(_)).toList))
        case eb: EmptyBox => val message = (eb ?~ ("Could not fetch Directives")).msg
          toJsonResponse("N/A", message, RestError)

    }

   // case Get("list" :: Nil,_) => JArray(readDirective.getAll(false).getOrElse(Seq()).map(directive => JString(directive.id.value.toUpperCase)).toList)
  /*  case Get("add" :: name :: Nil, req) => { val directive = Directive(DirectiveId(uuidGen.newUuid),name,0)
      val modId = ModificationId(uuidGen.newUuid)
      val actor = RestUtils.getActor(req)
      writeDirective.create(directive, modId, actor, Some(s"Create directive ${directive.name} (id:${directive.id.value.toUpperCase}) ")) match {
      case Full(x) => asyncDeploymentAgent ! AutomaticStartDeployment(modId,actor)
      PlainTextResponse("ok")
      case eb:EmptyBox => PlainTextResponse("ko")
    }
    }*/

    case "delete" :: id :: Nil JsonDelete req => {
      implicit val action = "deleteDirective"
      val modId = ModificationId(uuidGen.newUuid)
      val actor = RestUtils.getActor(req)
      val directiveId = DirectiveId(id)
      writeDirective.delete(directiveId, modId, actor, Some(s"Delete directive ${id.toUpperCase}) ")) match {
      case Full(x) => asyncDeploymentAgent ! AutomaticStartDeployment(modId,actor)
        val message = s"Directive ${id} deleted"
        toJsonResponse(id, message)
      case eb:EmptyBox => val fail = eb ?~ (s"Could not find Directive ${id}" )
        val message = s"Could not delete Directive ${id} cause is: ${fail.msg}"
        toJsonResponse(id, message, RestError)
    }
    }

    case Put("enable" :: id :: Nil, req) => {
      val modId = ModificationId(uuidGen.newUuid)
      val actor = RestUtils.getActor(req)
      ChangeDirectiveStatus(DirectiveId(id),modId,actor,true)
    }

    case Put("disable" :: id :: Nil, req) => {
      val modId = ModificationId(uuidGen.newUuid)
      val actor = RestUtils.getActor(req)
      ChangeDirectiveStatus(DirectiveId(id),modId,actor,false)
    }


    case Put("clone" :: id :: Nil, req) => {
      implicit val action = "cloneDirective"
      val modId = ModificationId(uuidGen.newUuid)
      val actor = RestUtils.getActor(req)
      val directiveId = DirectiveId(id)
      readDirective.getDirectiveWithContext(directiveId) match {
        case Full((technique,activeTechnique,directive)) =>
          val clone = directive.copy(id= DirectiveId(uuidGen.newUuid), name = s"Copy of <${directive.name}> ${Random.nextInt(10000)}",isEnabled = false)
          writeDirective.saveDirective(
              activeTechnique.id
            , clone
            , modId
            , actor
            , Some(s"clone directive ${directive.name} (id:${directive.id.value.toUpperCase}) to directive ${clone.id.value.toUpperCase} ")
            ) match {
              case Full(x) =>  asyncDeploymentAgent ! AutomaticStartDeployment(modId,actor)
                toJsonResponse(id,toJSON(technique,activeTechnique,clone))
              case eb:EmptyBox => val fail = eb ?~ (s"Could not find Directive ${id}" )
                val message = s"Could not clone Directive ${directive.name} (id:${directive.id.value}) cause is: ${fail.msg}."
                toJsonResponse(id, message, RestError)
          }
        case eb:EmptyBox => val fail = eb ?~ (s"Could not find Directive ${id}" )
          val message = s"Could not clone Directive ${id} cause is: ${fail.msg}."
          toJsonResponse(id, message, RestError)
      }
    }


    case Get(id :: Nil, req) => {
      implicit val action = "directiveDetails"
      readDirective.getDirectiveWithContext(DirectiveId(id)) match {
        case Full((technique,activeTechnique,directive)) =>
          toJsonResponse(id,toJSON(technique,activeTechnique,directive))
        case eb:EmptyBox => val fail = eb ?~!(s"Could not find Directive ${id}" )
          val message=  s"Could not get Directive ${id} details cause is: ${fail.msg}."
          toJsonResponse(id, message, RestError)
      }
    }

  })

  def ChangeDirectiveStatus(id : DirectiveId, modId : ModificationId, actor : EventActor, status : Boolean)={

    val past   = if (status) "enabled" else "disabled"
    val act    = if (status) "enable" else "disable"
    implicit val action = if (status) "enableDirective" else "disableDirective"
    readDirective.getDirectiveWithContext(id) match {
      case Full((technique,activetechnique,directive)) =>
        if (directive.isEnabled==status){
          val message = s"Directive ${id.value} already ${past}"
          toJsonResponse(id.value, message)
        }
        else

          writeDirective.saveDirective(
              activetechnique.id,
              directive.copy(isEnabled = status)
            , modId
            , actor
            , Some(s"${act} Directive ${directive.name} (id:${directive.id.value}) ")
          ) match {
            case Full(x) =>  asyncDeploymentAgent ! AutomaticStartDeployment(modId,actor)
              val message = s"Directive ${directive.name} (id:${directive.id.value}) ${past}"
             toJsonResponse(id.value, message)
            case eb:EmptyBox => val fail = eb ?~ (s"Could not find Directive ${id}" )
              val message = s"Could not ${act} Directive ${directive.name} (id:${directive.id.value}) cause is: ${fail.msg}."
             toJsonResponse(id.value, message, RestError)
          }
      case eb:EmptyBox => val fail = eb ?~ (s"Could not find Directive ${id}" )
        val message = s"Could not ${act} Directive ${id.value} cause is: ${fail.msg}."
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

  def toJSON (technique:Technique, activeTechnique:ActiveTechnique , directive : Directive): JValue = {
  ("directive" ->
    ("id" -> directive.id.value) ~
    ("displayName" -> directive.name) ~
    ("shortDescription" -> directive.shortDescription) ~
    ("longDescription" -> directive.longDescription) ~
    ("techniqueName" -> technique.id.name.value) ~
    ("techniqueVersion" -> directive.techniqueVersion.toString) ~
    ("parameters" -> SectionVal.toJSON(SectionVal.directiveValToSectionVal(technique.rootSection, directive.parameters)) )~
    ("priority" -> directive.priority) ~
    ("isEnabled" -> directive.isEnabled ) ~
    ("isSystem" -> directive.isSystem ))
  }


    def toJSONshort (directive : Directive) : JValue ={
  import net.liftweb.json.JsonDSL._
  ("directive" ->
    ("id" -> directive.id.value) ~
    ("displayName" -> directive.name)
  )
  }


sealed trait RestStatus {
  def code : Int
  def status : String
}

object RestOk extends RestStatus {
  val code = 200
  val status = "Ok"
}

object RestError extends RestStatus {
  val code = 500
  val status = "Error"
}

}