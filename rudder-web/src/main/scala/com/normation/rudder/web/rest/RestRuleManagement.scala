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
import com.normation.rudder.web.rest._
import com.normation.rudder.web.rest.RestUtils._
import scala.util.parsing.json.JSONObject
import net.liftweb.common.Failure
import com.normation.rudder.services.policies.RuleTargetServiceImpl
import net.liftweb.common.Loggable
import net.liftweb.common.Box
import com.normation.rudder.domain.policies.RuleTarget
import net.liftweb.common.Empty
import net.liftweb.http.Req
import net.liftweb.util.Props
import net.liftweb.http.LiftRules
import com.normation.rudder.services.workflows.ChangeRequestService
import com.normation.rudder.services.workflows.WorkflowService

class RestRuleManagement (
    readRule             : RoRuleRepository
  , writeRule            : WoRuleRepository
  , readDirective        : RoDirectiveRepository
  , targetInfoService    : RuleTargetService
  , uuidGen              : StringUuidGenerator
  , asyncDeploymentAgent : AsyncDeploymentAgent
  , changeRequestService : ChangeRequestService
  , workflowService      : WorkflowService
) extends RestHelper with Loggable{

  val latestAPI = RuleAPIV1_0


  implicit val prettify = true
  serve( "api" / "1.0" / "rules" prefix {


    case Get(Nil, _) => RuleAPIV1_0.listRules

    case Put(Nil, req) => {
      val restRule = RuleAPIV1_0.extractRuleFromParams(req.params)
      RuleAPIV1_0.createRule(restRule, req)
    }

    case Get(id :: Nil, _) => RuleAPIV1_0.ruleDetails(id)

    case Delete(id :: Nil, req) =>  RuleAPIV1_0.deleteRule(id,req)

    case Post(id:: Nil, req) => {
      val restRule = RuleAPIV1_0.extractRuleFromParams(req.params)
      RuleAPIV1_0.updateRule(id,req,restRule)
    }

    case id :: Nil JsonPost body -> req => {
      req.json match {
        case Full(arg) =>
          val restRule = RuleAPIV1_0.extractRuleFromJSON(arg)
          RuleAPIV1_0.updateRule(id,req,Full(restRule))
        case eb:EmptyBox=>    toJsonResponse(id, "no args arg", RestError)("Empty",true)
      }
    }

    case content => println(content)
         toJsonResponse("nothing", "rien", RestError)("error",true)

  })

  serve ( "api" / "rules" prefix {

    case Get(Nil, req) => {
      req.header("X-API-VERSION") match {
        case Full("1.0") => RuleAPIV1_0.listRules
        case _ => notValidVersionResponse("listRules")
      }
    }

    case Put(Nil, req) => {
      req.header("X-API-VERSION") match {
        case Full("1.0") =>
          val restRule = RuleAPIV1_0.extractRuleFromParams(req.params)
          RuleAPIV1_0.createRule(restRule, req)
        case _ => notValidVersionResponse("createRule")
      }
    }

    case Get(id :: Nil, req) => {
      req.header("X-API-VERSION") match {
        case Full("1.0") => RuleAPIV1_0.ruleDetails(id)
        case _ => notValidVersionResponse("listRules")
      }
    }

    case Delete(id :: Nil, req) => {
      req.header("X-API-VERSION") match {
        case Full("1.0") => RuleAPIV1_0.deleteRule(id,req)
        case _ => notValidVersionResponse("listRules")
      }
    }

    case Post(id:: Nil, req) => {
      req.header("X-API-VERSION") match {
        case Full("1.0") =>
          val restRule = RuleAPIV1_0.extractRuleFromParams(req.params)
          RuleAPIV1_0.updateRule(id,req,restRule)
        case _ => notValidVersionResponse("listRules")
      }
    }

    case id :: Nil JsonPost body -> req => {
      req.header("X-API-VERSION") match {
        case Full("1.0") =>
      req.json match {
        case Full(arg) =>
          val restRule = RuleAPIV1_0.extractRuleFromJSON(arg)
          RuleAPIV1_0.updateRule(id,req,Full(restRule))
        case eb:EmptyBox=>    toJsonResponse(id, "no args arg", RestError)("Empty",true)
      }
        case _ => notValidVersionResponse("listRules")
      }

    }

    case content => println(content)
         toJsonResponse("nothing", "rien", RestError)("error",true)

  })

  def notValidVersionResponse(action:String) = {
    toJsonResponse("badversion", "version x does not exists", RestError)(action,true)
   }

  case object RuleAPIV1_0 {


  def listRules = {
    implicit val action = "listRules"
    implicit val prettify = true
    readRule.getAll(false) match {
      case Full(rules) =>
        toJsonResponse("N/A", ( "rules" -> JArray(rules.map(toJSON(_)).toList)))
      case eb: EmptyBox =>
        val message = (eb ?~ ("Could not fetch Rules")).msg
        toJsonResponse("N/A", message, RestError)
    }
  }

  def createRule(restRule: Box[RestRule], req:Req) = {

    implicit val action = "createRule"
    val modId = ModificationId(uuidGen.newUuid)
    val actor = RestUtils.getActor(req)
    val ruleId = RuleId(req.param("id").getOrElse(uuidGen.newUuid))

    def actualRuleCreation(restRule : RestRule, baseRule : Rule) = {
      val newRule = restRule.updateRule( baseRule )
        writeRule.create(newRule, modId, actor, None) match {
          case Full(x) =>
            asyncDeploymentAgent ! AutomaticStartDeployment(modId,actor)
            val jsonRule = List(toJSON(newRule))
            toJsonResponse(ruleId.value, ("rules" -> JArray(jsonRule)), RestOk)

          case eb:EmptyBox =>
            val fail = eb ?~ (s"Could not find Rule ${ruleId.value}" )
            val message = s"Could not create Rule ${newRule.name} (id:${ruleId.value}) cause is: ${fail.msg}."
            toJsonResponse(ruleId.value, message, RestError)
      }
    }

    restRule match {
      case Full(restRule) =>
        restRule.name match {
          case Some(name) =>
            req.params.get("source") match {
              // Cloning
              case Some(sourceId :: Nil) =>
                readRule.get(RuleId(sourceId)) match {
                  case Full(sourceRule) =>
                    actualRuleCreation(restRule,sourceRule.copy(id=ruleId))
                  case eb:EmptyBox =>
                    val fail = eb ?~ (s"Could not find Rule ${sourceId}" )
                    val message = s"Could not create Rule ${name} (id:${ruleId.value}) based on Rule ${sourceId} : cause is: ${fail.msg}."
                    toJsonResponse(ruleId.value, message, RestError)
                }

              // Create a new Rule
              case None =>
                val baseRule = Rule(ruleId,name,0)
                actualRuleCreation(restRule,baseRule)

              // More than one source, make an error
              case _ =>
                val message = s"Could not create Rule ${name} (id:${ruleId.value}) based on an already existing Rule, cause is : too many values for source parameter."
                toJsonResponse(ruleId.value, message, RestError)
            }

          case None =>
            val message =  s"Could not get create a Rule details because there is no value as display name."
            toJsonResponse(ruleId.value, message, RestError)
        }

      case eb : EmptyBox =>
        val fail = eb ?~ (s"Could extract values from request" )
        val message = s"Could not create Rule ${ruleId.value} cause is: ${fail.msg}."
        toJsonResponse(ruleId.value, message, RestError)
    }
  }

  def ruleDetails(id:String) = {
    implicit val action = "ruleDetails"
      readRule.get(RuleId(id)) match {
        case Full(x) =>
          val jsonRule = List(toJSON(x))
          toJsonResponse(id,("rules" -> JArray(jsonRule)))
        case eb:EmptyBox =>
          val fail = eb ?~!(s"Could not find Rule ${id}" )
          val message=  s"Could not get Rule ${id} details cause is: ${fail.msg}."
          toJsonResponse(id, message, RestError)
      }
  }

  def deleteRule(id:String, req:Req) = {
    implicit val action = "deleteRule"
    val modId = ModificationId(uuidGen.newUuid)
    val actor = RestUtils.getActor(req)
    val ruleId = RuleId(id)
    readRule.get(ruleId) match {
      case Full(rule) =>
        val deleteRuleDiff = DeleteRuleDiff(rule)
        ( for {
          cr <- changeRequestService.createChangeRequestFromRule(
                         s"Delete rule ${id.toUpperCase}) with API "
                       , s"Delete rule ${id.toUpperCase}) "
                       , rule
                       , Some(rule)
                       , deleteRuleDiff
                       , actor
                       , None
                       )
          wfStarted <- workflowService.startWorkflow(cr.id, actor, None)
        } yield {
          cr.id
        } ) match {
          case Full(x) =>
            asyncDeploymentAgent ! AutomaticStartDeployment(modId,actor)
            val jsonRule = List(toJSON(rule))
            toJsonResponse(ruleId.value, ("rules" -> JArray(jsonRule)), RestOk)
          case eb:EmptyBox =>
            val fail = eb ?~ (s"Could not find Rule ${id}" )
            val message = s"Could not delete Rule ${id} cause is: ${fail.msg}"
            toJsonResponse(id, message, RestError)
        }

      case eb:EmptyBox =>
        val fail = eb ?~ (s"Could not find Rule ${ruleId.value}" )
        val message = s"Could not delete Rule ${ruleId.value} cause is: ${fail.msg}."
        toJsonResponse(ruleId.value, message, RestError)
    }
  }
  def updateRule(id: String, req: Req, restValues : Box[RestRule]) = {
    implicit val action = "updateRule"
    val modId = ModificationId(uuidGen.newUuid)
    val actor = getActor(req)
    val ruleId = RuleId(id)
    readRule.get(ruleId) match {
      case Full(rule) =>
        restValues match {
          case Full(restRule) =>
            val updatedRule = restRule.updateRule(rule)
            writeRule.update(
                updatedRule
              , modId
              , actor
              , Some(s"Modify Rule ${rule.name} (id:${rule.id.value}) ")
            ) match {
              case Full(x) =>  asyncDeploymentAgent ! AutomaticStartDeployment(modId,actor)
                  val jsonRule = List(toJSON(updatedRule))
                  toJsonResponse(ruleId.value, ("rules" -> JArray(jsonRule)), RestOk)

              case eb:EmptyBox => val fail = eb ?~ (s"Could not find Rule ${ruleId.value}" )
                  val message = s"Could not modify Rule ${rule.name} (id:${ruleId.value}) cause is: ${fail.msg}."
                 toJsonResponse(ruleId.value, message, RestError)
              }

          case eb : EmptyBox =>
            val fail = eb ?~ (s"Could extract values from request" )
            val message = s"Could not modify Rule ${ruleId.value} cause is: ${fail.msg}."
            toJsonResponse(ruleId.value, message, RestError)
        }

      case eb:EmptyBox =>
        val fail = eb ?~ (s"Could not find Rule ${ruleId.value}" )
        val message = s"Could not modify Rule ${ruleId.value} cause is: ${fail.msg}."
        toJsonResponse(ruleId.value, message, RestError)
    }
  }

  def extractRuleFromParams (params : Map[String,List[String]]) : Box[RestRule] = {

    def extractOneValue[T] (key : String, convertTo : (String,String) => Box[T] = ( (value:String,key:String) => Full(value))) = {
      params.get(key) match {
        case None               => Full(None)
        case Some(value :: Nil) => convertTo(value,key).map(Some(_))
        case _                  => Failure(s"updateRule should contain only one value for $key")
      }
    }

    def extractList[T] (key : String, convertTo : (List[String],String) => Box[T] = ( (values:List[String],key:String) => Full(values))) : Box[Option[T]] = {
      params.get(key) match {
        case None       => Full(None)
        case Some(list) => convertTo(list,key).map(Some(_))
      }
    }

    def convertToBoolean (value : String, key : String) : Box[Boolean] = {
      value match {
        case "true"  => Full(true)
        case "false" => Full(false)
        case _       => Failure(s"value for $key should be true or false")
      }
    }

    def convertListToDirectiveId (values : List[String], key : String) : Box[Set[DirectiveId]] = {
      val directives = values.filter(_.size != 0).map{
                         value =>
                           readDirective.getDirective(DirectiveId(value)) ?~ s"Directive '$value' not found"
                       }
      val failure = directives.collectFirst{case fail:EmptyBox => fail ?~ "There was an error with a Directive" }
      logger.info(directives)
      failure match {
        case Some(fail) => fail
        case None => Full(directives.collect{case Full(rt) => rt.id}.toSet)
      }

    }

    def convertListToRuleTarget (values : List[String], key : String) : Box[Set[RuleTarget]] = {
      val targets : Set[Box[RuleTarget]] = values.map(value => (value,RuleTarget.unser(value)) match {
        case (_,Some(rt)) => Full(rt)
        case (wrong,None) => Failure(s"$wrong is not a valid RuleTarget")
      }).toSet
      val failure = targets.collectFirst{case fail:Failure => fail }
      failure match {
        case Some(fail) => fail
        case None => Full(targets.collect{case Full(rt) => rt})
      }
    }

    for {
      name             <- extractOneValue("displayName")
      shortDescription <- extractOneValue("shortDescription")
      longDescription  <- extractOneValue("longDescription")
      enabled          <- extractOneValue("enabled", convertToBoolean)
      directives       <- extractList("directives", convertListToDirectiveId)
      targets          <- extractList("ruleTarget",convertListToRuleTarget)
    } yield {
      RestRule(name,shortDescription,longDescription,directives,targets,enabled)
    }

  }

  def extractRuleFromJSON (json : JValue) : RestRule = {


    val name = json \\ "displayName" match{
      case JString(name) => Some(name)
      case _             => None
    }

    val shortDescription = json \\ "shortDescription" match{
      case JString(short) => Some(short)
      case a => logger.info(a)
      None
    }
    val longDescription = json \\ "longDescription" match{
      case JString(long) => Some(long)
      case _ => None
    }
   val directives = json \\ "directives" match{
      case JArray(list) => println(list)
      Some(list.flatMap{
        case JString(directiveId) => Some(DirectiveId(directiveId))
        case _ => None
      }.toSet)
      case _ => None
    }

   val targets:Option[Set[RuleTarget]] = json \\ "targets" match{
      case JArray(list) => Some(list.flatMap{
        case JString(target) => RuleTarget.unser(target)
        case _ => None
      }.toSet)
      case _ => None
    }
    val isEnabled = json \\ "enabled" match{
      case JBool(bool) => Some(bool)
      case _ => None
    }
    RestRule(name,shortDescription,longDescription,directives,targets,isEnabled)
  }
  def toJSON (rule : Rule) : JObject = {

    ( "id"               -> rule.id.value ) ~
    ( "displayName"      -> rule.name ) ~
    ( "shortDescription" -> rule.shortDescription ) ~
    ( "longDescription"  -> rule.longDescription ) ~
    ( "directives"       -> rule.directiveIds.map(_.value) ) ~
    ( "targets"          -> rule.targets.map(_.target) ) ~
    ( "enabled"          -> rule.isEnabledStatus ) ~
    ( "system"           -> rule.isSystem )
  }

  def toJSONshort (rule : Rule) : JValue ={
    ("id" -> rule.id.value) ~
    ("displayName" -> rule.name)
  }
  }
  case class RestRule(
      name             : Option[String] = None
    , shortDescription : Option[String] = None
    , longDescription  : Option[String] = None
    , directives       : Option[Set[DirectiveId]] = None
    , targets          : Option[Set[RuleTarget]] = None
    , enabled        : Option[Boolean]     = None
  ) {
    def updateRule(rule:Rule) = {
      val updateName = name.getOrElse(rule.name)
      val updateShort = shortDescription.getOrElse(rule.shortDescription)
      val updateLong = longDescription.getOrElse(rule.longDescription)
      val updateDirectives = directives.getOrElse(rule.directiveIds)
      val updateTargets = targets.getOrElse(rule.targets)
      val updateEnabled = enabled.getOrElse(rule.isEnabledStatus)
      rule.copy(
          name             = updateName
        , shortDescription = updateShort
        , longDescription  = updateLong
        , directiveIds     = updateDirectives
        , targets          = updateTargets
        , isEnabledStatus  = updateEnabled
      )

    }
  }


}