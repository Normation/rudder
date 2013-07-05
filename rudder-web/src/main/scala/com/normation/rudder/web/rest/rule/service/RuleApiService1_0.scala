package com.normation.rudder.web.rest.rule.service

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
import com.normation.rudder.web.rest.rule.RestRule
import com.normation.eventlog.ModificationId
import com.normation.rudder.batch.AutomaticStartDeployment

case class RuleApiService1_0 (
    readRule             : RoRuleRepository
  , writeRule            : WoRuleRepository
  , uuidGen              : StringUuidGenerator
  , asyncDeploymentAgent : AsyncDeploymentAgent
  , changeRequestService : ChangeRequestService
  , workflowService      : WorkflowService
  , restExtractor        : RestExtractorService
  , workflowEnabled      : Boolean
  ) {


  private[this] def createChangeRequestAndAnswer (
      id            : String
    , diff          : ChangeRequestRuleDiff
    , rule          : Rule
    , initialtState : Option[Rule]
    , actor         : EventActor
    , message       : String
  ) (implicit action : String, prettify : Boolean) = {
    ( for {
        cr <- changeRequestService.createChangeRequestFromRule(
                  message
                , message
                , rule
                , Some(rule)
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
        val jsonRule = List(rule.toJSON)
        toJsonResponse(id, ("rules" -> JArray(jsonRule)), RestOk)
      case eb:EmptyBox =>
        val fail = eb ?~ (s"Could not save changes on Rule ${id}" )
        val msg = s"${message} failed, cause is: ${fail.msg}."
        toJsonResponse(id, msg, RestError)
    }
  }

  def listRules(req : Req) = {
    implicit val action = "listRules"
    implicit val prettify = restExtractor.extractPrettify(req.params)
    readRule.getAll(false) match {
      case Full(rules) =>
        toJsonResponse("N/A", ( "rules" -> JArray(rules.map(_.toJSON).toList)))
      case eb: EmptyBox =>
        val message = (eb ?~ ("Could not fetch Rules")).msg
        toJsonResponse("N/A", message, RestError)
    }
  }

  def createRule(restRule: Box[RestRule], req:Req) = {
    implicit val action = "createRule"
    implicit val prettify = restExtractor.extractPrettify(req.params)
    val modId = ModificationId(uuidGen.newUuid)
    val actor = RestUtils.getActor(req)
    val ruleId = RuleId(req.param("id").getOrElse(uuidGen.newUuid))

    def actualRuleCreation(restRule : RestRule, baseRule : Rule) = {
      val newRule = restRule.updateRule( baseRule )
      writeRule.create(newRule, modId, actor, None) match {
        case Full(x) =>
          asyncDeploymentAgent ! AutomaticStartDeployment(modId,actor)
          val jsonRule = List(newRule.toJSON)
          toJsonResponse(ruleId.value, ("rules" -> JArray(jsonRule)), RestOk)

        case eb:EmptyBox =>
          val fail = eb ?~ (s"Could not save Rule ${ruleId.value}" )
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
                    // disable rest Rule if cloning
                    actualRuleCreation(restRule.copy(enabled = Some(false)),sourceRule.copy(id=ruleId))
                  case eb:EmptyBox =>
                    val fail = eb ?~ (s"Could not find Rule ${sourceId}" )
                    val message = s"Could not create Rule ${name} (id:${ruleId.value}) based on Rule ${sourceId} : cause is: ${fail.msg}."
                    toJsonResponse(ruleId.value, message, RestError)
                }

              // Create a new Rule
              case None =>
                // If enable is missing in parameter consider it to true
                val defaultEnabled = restRule.enabled.getOrElse(true)

                // if only the name parameter is set, consider it to be enabled
                // if not if workflow are enabled, consider it to be disabled
                // if there is no workflow, use the value used as parameter (default to true)
                // code extract :
                /*re
                 * if (restRule.onlyName) true
                 * else if (workflowEnabled) false
                 * else defaultEnabled
                 */
                val enableCheck = restRule.onlyName || (!workflowEnabled && defaultEnabled)
                val baseRule = Rule(ruleId,name,0)

                // The enabled value in restRule will be used in the saved Rule
                actualRuleCreation(restRule.copy(enabled = Some(enableCheck)),baseRule)

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

  def ruleDetails(id:String, req:Req) = {
    implicit val action = "ruleDetails"
    implicit val prettify = restExtractor.extractPrettify(req.params)

    readRule.get(RuleId(id)) match {
      case Full(rule) =>
        val jsonRule = List(rule.toJSON)
        toJsonResponse(id,("rules" -> JArray(jsonRule)))
      case eb:EmptyBox =>
        val fail = eb ?~!(s"Could not find Rule ${id}" )
        val message=  s"Could not get Rule ${id} details cause is: ${fail.msg}."
        toJsonResponse(id, message, RestError)
    }
  }

  def deleteRule(id:String, req:Req) = {
    implicit val action = "deleteRule"
    implicit val prettify = restExtractor.extractPrettify(req.params)
    val modId = ModificationId(uuidGen.newUuid)
    val actor = RestUtils.getActor(req)
    val ruleId = RuleId(id)

    readRule.get(ruleId) match {
      case Full(rule) =>
        val deleteRuleDiff = DeleteRuleDiff(rule)
        val message = s"Delete Rule ${rule.name} ${id} from API "
        createChangeRequestAndAnswer(id, deleteRuleDiff, rule, Some(rule), actor, message)

      case eb:EmptyBox =>
        val fail = eb ?~ (s"Could not find Rule ${ruleId.value}" )
        val message = s"Could not delete Rule ${ruleId.value} cause is: ${fail.msg}."
        toJsonResponse(ruleId.value, message, RestError)
    }
  }

  def updateRule(id: String, req: Req, restValues : Box[RestRule]) = {
    implicit val action = "updateRule"
    implicit val prettify = restExtractor.extractPrettify(req.params)
    val modId = ModificationId(uuidGen.newUuid)
    val actor = getActor(req)
    val ruleId = RuleId(id)

    readRule.get(ruleId) match {
      case Full(rule) =>
        restValues match {
          case Full(restRule) =>
            val updatedRule = restRule.updateRule(rule)
            val diff = ModifyToRuleDiff(updatedRule)
            val message = s"Modify Rule ${rule.name} ${id} from API "
            createChangeRequestAndAnswer(id, diff, updatedRule, Some(rule), actor, message)

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
  }