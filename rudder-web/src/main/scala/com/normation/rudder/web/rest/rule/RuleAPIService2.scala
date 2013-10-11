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

package com.normation.rudder.web.rest.rule

import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.rudder.batch.AsyncDeploymentAgent
import com.normation.rudder.batch.AutomaticStartDeployment
import com.normation.rudder.domain.policies.ChangeRequestRuleDiff
import com.normation.rudder.domain.policies.DeleteRuleDiff
import com.normation.rudder.domain.policies.ModifyToRuleDiff
import com.normation.rudder.domain.policies.Rule
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.workflows.ChangeRequestId
import com.normation.rudder.repository.RoRuleRepository
import com.normation.rudder.repository.WoRuleRepository
import com.normation.rudder.services.workflows.ChangeRequestService
import com.normation.rudder.services.workflows.WorkflowService
import com.normation.rudder.web.rest.RestUtils
import com.normation.rudder.web.rest.RestUtils.getActor
import com.normation.rudder.web.rest.RestUtils.toJsonError
import com.normation.rudder.web.rest.RestUtils.toJsonResponse
import com.normation.rudder.web.rest.RestExtractorService
import com.normation.utils.StringUuidGenerator
import net.liftweb.common.Box
import net.liftweb.common.Box.box2Option
import net.liftweb.common.EmptyBox
import net.liftweb.common.Full
import net.liftweb.http.Req
import net.liftweb.json.JArray
import net.liftweb.json.JValue
import net.liftweb.json.JsonDSL._
import com.normation.rudder.web.rest.RestDataSerializer

case class RuleApiService2 (
    readRule             : RoRuleRepository
  , writeRule            : WoRuleRepository
  , uuidGen              : StringUuidGenerator
  , asyncDeploymentAgent : AsyncDeploymentAgent
  , changeRequestService : ChangeRequestService
  , workflowService      : WorkflowService
  , restExtractor        : RestExtractorService
  , workflowEnabled      : Boolean
  , restDataSerializer   : RestDataSerializer
  ) {

  import restDataSerializer.{ serializeRule => serialize}

  private[this] def createChangeRequestAndAnswer (
      id           : String
    , diff         : ChangeRequestRuleDiff
    , rule         : Rule
    , initialState : Option[Rule]
    , actor        : EventActor
    , req          : Req
    , act          : String
  ) (implicit action : String, prettify : Boolean) = {


    ( for {
        reason <- restExtractor.extractReason(req.params)
        crName <- restExtractor.extractChangeRequestName(req.params).map(_.getOrElse(s"${act} Rule ${rule.name} from API"))
        crDescription = restExtractor.extractChangeRequestDescription(req.params)
        cr <- changeRequestService.createChangeRequestFromRule(
                  crName
                , crDescription
                , rule
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
        val optCrId = if (workflowEnabled) Some(crId) else None
        val jsonRule = List(serialize(rule,optCrId))
        toJsonResponse(Some(id), ("rules" -> JArray(jsonRule)))
      case eb:EmptyBox =>
        val fail = eb ?~ (s"Could not save changes on Rule ${id}" )
        val msg = s"${act} failed, cause is: ${fail.msg}."
        toJsonError(Some(id), msg)
    }
  }

  def listRules(req : Req) = {
    implicit val action = "listRules"
    implicit val prettify = restExtractor.extractPrettify(req.params)
    readRule.getAll(false) match {
      case Full(rules) =>
        toJsonResponse(None, ( "rules" -> JArray(rules.map(serialize(_,None)).toList)))
      case eb: EmptyBox =>
        val message = (eb ?~ ("Could not fetch Rules")).msg
        toJsonError(None, message)
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
      ( for {
        reason   <- restExtractor.extractReason(req.params)
        saveDiff <-  writeRule.create(newRule, modId, actor, reason)
      } yield {
        saveDiff
      } ) match {
        case Full(x) =>
          asyncDeploymentAgent ! AutomaticStartDeployment(modId,actor)
          val jsonRule = List(serialize(newRule,None))
          toJsonResponse(Some(ruleId.value), ("rules" -> JArray(jsonRule)))

        case eb:EmptyBox =>
          val fail = eb ?~ (s"Could not save Rule ${ruleId.value}" )
          val message = s"Could not create Rule ${newRule.name} (id:${ruleId.value}) cause is: ${fail.msg}."
          toJsonError(Some(ruleId.value), message)
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
                    toJsonError(Some(ruleId.value), message)
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
                toJsonError(Some(ruleId.value), message)
            }

          case None =>
            val message =  s"Could not get create a Rule details because there is no value as display name."
            toJsonError(Some(ruleId.value), message)
        }

      case eb : EmptyBox =>
        val fail = eb ?~ (s"Could extract values from request" )
        val message = s"Could not create Rule ${ruleId.value} cause is: ${fail.msg}."
        toJsonError(Some(ruleId.value), message)
    }
  }

  def ruleDetails(id:String, req:Req) = {
    implicit val action = "ruleDetails"
    implicit val prettify = restExtractor.extractPrettify(req.params)

    readRule.get(RuleId(id)) match {
      case Full(rule) =>
        val jsonRule = List(serialize(rule,None))
        toJsonResponse(Some(id),("rules" -> JArray(jsonRule)))
      case eb:EmptyBox =>
        val fail = eb ?~!(s"Could not find Rule ${id}" )
        val message=  s"Could not get Rule ${id} details cause is: ${fail.msg}."
        toJsonError(Some(id), message)
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
        createChangeRequestAndAnswer(id, deleteRuleDiff, rule, Some(rule), actor, req, "Delete")

      case eb:EmptyBox =>
        val fail = eb ?~ (s"Could not find Rule ${ruleId.value}" )
        val message = s"Could not delete Rule ${ruleId.value} cause is: ${fail.msg}."
        toJsonError(Some(ruleId.value), message)
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
            createChangeRequestAndAnswer(id, diff, updatedRule, Some(rule), actor, req, "Update")

          case eb : EmptyBox =>
            val fail = eb ?~ (s"Could extract values from request" )
            val message = s"Could not modify Rule ${ruleId.value} cause is: ${fail.msg}."
            toJsonError(Some(ruleId.value), message)
        }

      case eb:EmptyBox =>
        val fail = eb ?~ (s"Could not find Rule ${ruleId.value}" )
        val message = s"Could not modify Rule ${ruleId.value} cause is: ${fail.msg}."
        toJsonError(Some(ruleId.value), message)
    }
  }


}