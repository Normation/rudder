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
import com.normation.rudder.services.policies.RuleTargetService
import com.normation.rudder.batch._
import com.normation.eventlog.EventActor
import net.liftweb.json.JsonDSL._
import net.liftweb.http.JsonResponse
import scala.util.Random
import net.liftweb.json.JValue
class RestRuleManagement (
    readRule : RoRuleRepository
  , writeRule : WoRuleRepository
  , targetInfoService : RuleTargetService
  , uuidGen           : StringUuidGenerator
  , asyncDeploymentAgent : AsyncDeploymentAgent
) extends RestHelper {
  serve( "api" / "rules" prefix {

    case Nil JsonGet _ =>
      implicit val action = "listRules"
      readRule.getAll(false) match {
        case Full(rules) => toJsonResponse("N/A", JArray(rules.map(toJSONshort(_)).toList))
        case eb: EmptyBox => val message = (eb ?~ ("Could not fetch Rules")).msg
          toJsonResponse("N/A", message, RestError)
      
    }
    
   // case Get("list" :: Nil,_) => JArray(readRule.getAll(false).getOrElse(Seq()).map(rule => JString(rule.id.value.toUpperCase)).toList)
  /*  case Get("add" :: name :: Nil, req) => { val rule = Rule(RuleId(uuidGen.newUuid),name,0)
      val modId = ModificationId(uuidGen.newUuid)
      val actor = RestUtils.getActor(req)
      writeRule.create(rule, modId, actor, Some(s"Create rule ${rule.name} (id:${rule.id.value.toUpperCase}) ")) match {
      case Full(x) => asyncDeploymentAgent ! AutomaticStartDeployment(modId,actor)
      PlainTextResponse("ok")
      case eb:EmptyBox => PlainTextResponse("ko")
    }
    }*/
    
    case "delete" :: id :: Nil JsonDelete req => {
      implicit val action = "deleteRule"
      val modId = ModificationId(uuidGen.newUuid)
      val actor = RestUtils.getActor(req)
      val ruleId = RuleId(id)
      writeRule.delete(ruleId, modId, actor, Some(s"Delete rule ${id.toUpperCase}) ")) match {
      case Full(x) => asyncDeploymentAgent ! AutomaticStartDeployment(modId,actor) 
        val message = s"Rule ${id} deleted"
        toJsonResponse(id, message)
      case eb:EmptyBox => val fail = eb ?~ (s"Could not find Rule ${id}" )
        val message = s"Could not delete Rule ${id} cause is: ${fail.msg}"
        toJsonResponse(id, message, RestError)
    }
    }
    
    case Put("enable" :: id :: Nil, req) => {
      val modId = ModificationId(uuidGen.newUuid)
      val actor = RestUtils.getActor(req)
      ChangeRuleStatus(RuleId(id),modId,actor,true)
    }
    
    case Put("disable" :: id :: Nil, req) => {
      val modId = ModificationId(uuidGen.newUuid)
      val actor = RestUtils.getActor(req)
      ChangeRuleStatus(RuleId(id),modId,actor,false)
    }
    
    
    case Put("clone" :: id :: Nil, req) => {
      implicit val action = "cloneRule"
      val modId = ModificationId(uuidGen.newUuid)
      val actor = RestUtils.getActor(req)
      val ruleId = RuleId(id)
      readRule.get(ruleId) match {
        case Full(rule) => 
          val clone = rule.copy(id= RuleId(uuidGen.newUuid), name = s"Copy of <${rule.name}> ${Random.nextInt(10000)}",isEnabledStatus = false)
          writeRule.
          create(
              clone
            , modId
            , actor
            , Some(s"clone rule ${rule.name} (id:${rule.id.value.toUpperCase}) to rule ${clone.id.value.toUpperCase} ")
            ) match {
              case Full(x) =>  asyncDeploymentAgent ! AutomaticStartDeployment(modId,actor)
                toJsonResponse(id,toJSON(clone))
              case eb:EmptyBox => val fail = eb ?~ (s"Could not find Rule ${id}" ) 
                val message = s"Could not clone Rule ${rule.name} (id:${rule.id.value}) cause is: ${fail.msg}."
                toJsonResponse(id, message, RestError)
          }
        case eb:EmptyBox => val fail = eb ?~ (s"Could not find Rule ${id}" ) 
          val message = s"Could not clone Rule ${id} cause is: ${fail.msg}."
          toJsonResponse(id, message, RestError)
      }
    }
    
    
    case Get(id :: Nil, req) => {
      implicit val action = "ruleDetails"
      readRule.get(RuleId(id)) match {
        case Full(x) =>
          toJsonResponse(id,toJSON(x))
        case eb:EmptyBox => val fail = eb ?~!(s"Could not find Rule ${id}" ) 
          val message=  s"Could not get Rule ${id} details cause is: ${fail.msg}."
          toJsonResponse(id, message, RestError)
      }
    }
    
  })
  
  def ChangeRuleStatus(id : RuleId, modId : ModificationId, actor : EventActor, status : Boolean)={

    val past   = if (status) "enabled" else "disabled"
    val act    = if (status) "enable" else "disable"
    implicit val action = if (status) "enableRule" else "disableRule"
    readRule.get(id) match {
      case Full(rule) =>
        if (rule.isEnabled==status){
          val message = s"Rule ${id.value} already ${past}"
          toJsonResponse(id.value, message)
        }
        else 
          writeRule.update(
              rule.copy(isEnabledStatus = status)
            , modId
            , actor
            , Some(s"${act} Rule ${rule.name} (id:${rule.id.value}) ")
          ) match {
            case Full(x) =>  asyncDeploymentAgent ! AutomaticStartDeployment(modId,actor)
              val message = s"Rule ${rule.name} (id:${rule.id.value}) ${past}"
             toJsonResponse(id.value, message)
            case eb:EmptyBox => val fail = eb ?~ (s"Could not find Rule ${id}" ) 
              val message = s"Could not ${act} Rule ${rule.name} (id:${rule.id.value}) cause is: ${fail.msg}."
             toJsonResponse(id.value, message, RestError)
          }
      case eb:EmptyBox => val fail = eb ?~ (s"Could not find Rule ${id}" )
        val message = s"Could not ${act} Rule ${id.value} cause is: ${fail.msg}."
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

  def toJSON (rule : Rule) : JValue = {

  ("rule" ->
    ("id" -> rule.id.value) ~
    ("displayName" -> rule.name) ~
    ("serial" -> rule.serial) ~
    ("shortDescription" -> rule.shortDescription) ~
    ("longDescription" -> rule.longDescription) ~
    ("directiveIds" -> rule.directiveIds.map(_.value)) ~
    ("targets" -> rule.targets.map(_.target)) ~
    ("isEnabled" -> rule.isEnabledStatus ) ~
    ("isSystem" -> rule.isSystem ))
  }
    def toJSONshort (rule : Rule) : JValue ={
  import net.liftweb.json.JsonDSL._
  ("rule" ->
    ("id" -> rule.id.value) ~
    ("displayName" -> rule.name) 
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