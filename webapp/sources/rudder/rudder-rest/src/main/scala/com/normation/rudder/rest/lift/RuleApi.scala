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
import com.normation.eventlog._
import com.normation.rudder.UserService
import com.normation.rudder.batch.AsyncDeploymentActor
import com.normation.rudder.batch.AutomaticStartDeployment
import com.normation.rudder.domain.policies.ChangeRequestRuleDiff
import com.normation.rudder.domain.policies.DeleteRuleDiff
import com.normation.rudder.domain.policies.ModifyToRuleDiff
import com.normation.rudder.domain.policies.Rule
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.repository.RoRuleRepository
import com.normation.rudder.repository.WoRuleRepository
import com.normation.rudder.rest.ApiPath
import com.normation.rudder.rest.ApiVersion
import com.normation.rudder.rest.AuthzToken
import com.normation.rudder.rest.RestDataSerializer
import com.normation.rudder.rest.RestExtractorService
import com.normation.rudder.rest.RestUtils
import com.normation.rudder.rest.RestUtils.getActor
import com.normation.rudder.rest.RestUtils.toJsonError
import com.normation.rudder.rest.RestUtils.toJsonResponse
import com.normation.rudder.rest.data._
import com.normation.rudder.rest.{RuleApi => API}
import com.normation.rudder.rule.category.RuleCategoryId
import com.normation.rudder.rule.category._
import com.normation.rudder.services.workflows.ChangeRequestService
import com.normation.rudder.services.workflows.RuleChangeRequest
import com.normation.rudder.services.workflows.RuleModAction
import com.normation.rudder.services.workflows.WorkflowLevelService
import com.normation.utils.StringUuidGenerator
import net.liftweb.common.Box
import net.liftweb.common.EmptyBox
import net.liftweb.common.Full
import net.liftweb.common.Loggable
import net.liftweb.common._
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import net.liftweb.json.JArray
import net.liftweb.json.JsonDSL._
import net.liftweb.json._

class RuleApi(
    restExtractorService : RestExtractorService
  , apiV2                : RuleApiService2
  , serviceV6            : RuleApiService6
  , uuidGen              : StringUuidGenerator
) extends LiftApiModuleProvider[API] {

  import RestUtils._

  def response ( function : Box[JValue], req : Req, errorMessage : String, dataName : String)(implicit action : String) : LiftResponse = {
    RestUtils.response(restExtractorService, dataName,None)(function, req, errorMessage)
  }

  def actionResponse ( function : Box[ActionType], req : Req, errorMessage : String, id : Option[String], actor: EventActor, dataName : String)(implicit action : String) : LiftResponse = {
    RestUtils.actionResponse2(restExtractorService, dataName, uuidGen, id)(function, req, errorMessage)(action, actor)
  }

  def schemas = API

  def getLiftEndpoints(): List[LiftApiModule] = {
    API.endpoints.map(e => e match {
      case API.ListRules              => ListRules
      case API.CreateRule             => CreateRule
      case API.GetRuleTree            => GetRuleTree
      case API.RuleDetails            => RuleDetails
      case API.DeleteRule             => DeleteRule
      case API.UpdateRule             => UpdateRule
      case API.GetRuleCategoryDetails => GetRuleCategoryDetails
      case API.DeleteRuleCategory     => DeleteRuleCategory
      case API.UpdateRuleCategory     => UpdateRuleCategory
      case API.CreateRuleCategory     => CreateRuleCategory
    })
  }

  object ListRules extends LiftApiModule0 {
    val schema = API.ListRules
    val restExtractor = restExtractorService
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      apiV2.listRules(req)
    }
  }


  object CreateRule extends LiftApiModule0 {
    val schema = API.CreateRule
    val restExtractor = restExtractorService
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      var action = "createRule"
      val id = restExtractor.extractId(req)(x => Full(RuleId(x))).map(_.getOrElse(RuleId(uuidGen.newUuid)))

      val response = for {
        ruleId <- id
        restRule <- restExtractor.extractRule(req) ?~! s"Could not extract Rule parameters from request"
        optCloneId <- restExtractor.extractString("source")(req)(x => Full(RuleId(x)))
        result <- apiV2.createRule(restRule, ruleId, optCloneId, authzToken.actor)
      } yield {
        if (optCloneId.nonEmpty)
          action = "cloneRule"
        JArray(result :: Nil)
      }

      actionResponse(response, req, "Could not create Rule", id.map(_.value), authzToken.actor, "rules")(action)
    }
  }

  object RuleDetails extends LiftApiModule {
    val schema = API.RuleDetails
    val restExtractor = restExtractorService
    def process(version: ApiVersion, path: ApiPath, id: String, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      apiV2.ruleDetails(id, req)
    }
  }

  object DeleteRule extends LiftApiModule {
    val schema = API.DeleteRule
    val restExtractor = restExtractorService
    def process(version: ApiVersion, path: ApiPath, id: String, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      apiV2.deleteRule(id,req)
    }
  }

  object UpdateRule extends LiftApiModule {
    val schema = API.UpdateRule
    val restExtractor = restExtractorService
    def process(version: ApiVersion, path: ApiPath, id: String, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      if(req.json_?) {
        req.json match {
          case Full(arg) =>
            val restRule = restExtractor.extractRuleFromJSON(arg)
            apiV2.updateRule(id,req,restRule)
          case eb:EmptyBox=>
            toJsonError(None, JString("No Json data sent"))("updateRule",restExtractor.extractPrettify(req.params))
        }
      } else {
        val restRule = restExtractor.extractRule(req.params)
        apiV2.updateRule(id,req,restRule)
      }
    }
  }

  object GetRuleTree extends LiftApiModule0 {
    val schema = API.GetRuleTree
    val restExtractor = restExtractorService
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      response(
          serviceV6.getCategoryTree
        , req
        , s"Could not fetch Rule category tree"
        , "ruleCategories"
      ) ("GetRuleTree")
    }
  }


  object GetRuleCategoryDetails extends LiftApiModule {
    val schema = API.GetRuleCategoryDetails
    val restExtractor = restExtractorService
    def process(version: ApiVersion, path: ApiPath, id: String, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      response (
          serviceV6.getCategoryDetails(RuleCategoryId(id))
        , req
        , s"Could not fetch Rule category '${id}' details"
        , "ruleCategories"
     ) ("getRuleCategoryDetails")
    }
  }

  object DeleteRuleCategory extends LiftApiModule {
    val schema = API.DeleteRuleCategory
    val restExtractor = restExtractorService
    def process(version: ApiVersion, path: ApiPath, id: String, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      actionResponse(
          Full(serviceV6.deleteCategory(RuleCategoryId(id)))
        , req
        , s"Could not delete Rule category '${id}'"
        , Some(id)
        , authzToken.actor
        , "ruleCategories"
      ) ("deleteRuleCategory")
    }
  }

  object UpdateRuleCategory extends LiftApiModule {
    val schema = API.UpdateRuleCategory
    val restExtractor = restExtractorService
    def process(version: ApiVersion, path: ApiPath, id: String, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      val action =
      if(req.json_?) {
        for {
          json <- req.json ?~! "No JSON data sent"
          cat <- restExtractor.extractRuleCategory(json)
        } yield {
           serviceV6.updateCategory(RuleCategoryId(id), cat) _
        }
      } else {
        for {
          restCategory <- restExtractor.extractRuleCategory(req.params)
        } yield {
          serviceV6.updateCategory(RuleCategoryId(id), restCategory) _
        }
      }
      actionResponse(
          action
        , req
        , s"Could not update Rule category '${id}'"
        , Some(id)
        , authzToken.actor
        , "ruleCategories"
      ) ("updateRuleCategory")
    }
  }

  object CreateRuleCategory extends LiftApiModule0 {
    val schema = API.CreateRuleCategory
    val restExtractor = restExtractorService
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      val id = RuleCategoryId(uuidGen.newUuid)
      val action = if(req.json_?) {
        for {
          json <- req.json
          cat  <- restExtractor.extractRuleCategory(json)
        } yield {
          serviceV6.createCategory(id, cat) _
        }
      } else {
        for {
          restCategory <- restExtractor.extractRuleCategory(req.params)
        } yield {
          serviceV6.createCategory(id, restCategory) _
        }
      }
      actionResponse(
          action
        , req
        , s"Could not create Rule category"
        , Some(id.value)
        , authzToken.actor
        , "ruleCategories"
      ) ("createRuleCategory")
    }
  }
}

class RuleApiService2 (
    readRule             : RoRuleRepository
  , writeRule            : WoRuleRepository
  , uuidGen              : StringUuidGenerator
  , asyncDeploymentAgent : AsyncDeploymentActor
  , workflowLevelService : WorkflowLevelService
  , restExtractor        : RestExtractorService
  , restDataSerializer   : RestDataSerializer
) ( implicit userService : UserService ) {

  import restDataSerializer.{serializeRule => serialize}

  private[this] def createChangeRequestAndAnswer(
    id: String
    , diff: ChangeRequestRuleDiff
    , change: RuleChangeRequest
    , actor: EventActor
    , req: Req
  )(implicit action: String, prettify: Boolean) = {
    (for {
      workflow <- workflowLevelService.getForRule(actor, change)
      reason <- restExtractor.extractReason(req)
      crName <- restExtractor.extractChangeRequestName(req).map(_.getOrElse(s"${change.action.name} rule '${change.newRule.name}' by API request"))
      crDesc = restExtractor.extractChangeRequestDescription(req)
      cr = ChangeRequestService.createChangeRequestFromRule(
        crName
        , crDesc
        , change.newRule
        , change.previousRule
        , diff
        , actor
        , reason
      )
      id <- workflowLevelService.getWorkflowService().startWorkflow(cr, actor, None)
    } yield {
      (workflow.needExternalValidation(), id)
    }
      ) match {
      case Full((needValidation, crId)) =>
        val optCrId = if (needValidation) Some(crId) else None
        val jsonRule = List(serialize(change.newRule, optCrId))
        toJsonResponse(Some(id), ("rules" -> JArray(jsonRule)))
      case eb: EmptyBox =>
        val fail = eb ?~ (s"Could not save changes on Rule ${id}")
        val msg = s"${change.action.name} failed, cause is: ${fail.msg}."
        toJsonError(Some(id), msg)
    }
  }

  def listRules(req: Req) = {
    implicit val action = "listRules"
    implicit val prettify = restExtractor.extractPrettify(req.params)
    readRule.getAll(false) match {
      case Full(rules) =>
        toJsonResponse(None, ("rules" -> JArray(rules.map(serialize(_, None)).toList)))
      case eb: EmptyBox =>
        val message = (eb ?~ ("Could not fetch Rules")).msg
        toJsonError(None, message)
    }
  }

  def actualRuleCreation(change: RuleChangeRequest)(actor: EventActor, modId: ModificationId, reason: Option[String]) = {
    (for {
      _ <- writeRule.create(change.newRule, modId, actor, reason)
    } yield {
      asyncDeploymentAgent ! AutomaticStartDeployment(modId, actor)
      serialize(change.newRule, None)
    }) ?~ (s"Could not save Rule ${change.newRule.id.value}")
  }

  def createRule(restRule: RestRule, ruleId: RuleId, clone: Option[RuleId], actor : EventActor): Box[(EventActor, ModificationId, Option[String]) =>  Box[JValue] ] = {
    // decide if we should create a new rule or clone an existing one
    // Return the source rule to use in each case.
    def createOrClone(name: String): Box[RuleChangeRequest] = {
      clone match {
        case Some(sourceId) =>
          // clone existing rule
          for {
            rule <- readRule.get(sourceId) ?~!
              s"Could not create rule ${name} (id:${ruleId.value}) by cloning rule '${sourceId.value}')"
          } yield {
            RuleChangeRequest(RuleModAction.Create, restRule.updateRule(rule), Some(rule))
          }

        case None =>
          // create from scratch - base rule is the same with default values
          val category = restRule.category.getOrElse(RuleCategoryId("rootRuleCategory"))
          val baseRule = Rule(ruleId, name, category)
          // If enable is missing in parameter consider it to true
          val defaultEnabled = restRule.enabled.getOrElse(true)

          val change = RuleChangeRequest(RuleModAction.Create, restRule.updateRule(baseRule), Some(baseRule))
          // if only the name parameter is set, consider it to be enabled
          // if not if workflow are enabled, consider it to be disabled
          // if there is no workflow, use the value used as parameter (default to true)
          // code extract :
          /*
           * if (restRule.onlyName) true
           * else if (workflowEnabled) false
           * else defaultEnabled
           */
          for {
            workflow <- workflowLevelService.getForRule(actor, change) ?~! "Could not find workflow status for that rule creation"
          } yield {
            // we don't actually start a workflow, we only disable the rule if a workflow should be
            // started. Update rule "enable" status accordingly.
            val enableCheck = restRule.onlyName || (!workflow.needExternalValidation() && defaultEnabled)
            // Then enabled value in restRule will be used in the saved Rule
            change.copy(newRule = change.newRule.copy(isEnabledStatus = enableCheck))
          }
      }
    }

    (for {
      name <- Box(restRule.name) ?~! "Missing manadatory parameter Name"
      change <- createOrClone(name)
    } yield {
      (actualRuleCreation(change) _)
    }) ?~ (s"Error when creating new rule")
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
    val actor = RestUtils.getActor(req)
    val ruleId = RuleId(id)

    readRule.get(ruleId) match {
      case Full(rule) =>
        val deleteRuleDiff = DeleteRuleDiff(rule)
        val change = RuleChangeRequest(RuleModAction.Delete, rule, Some(rule))
        createChangeRequestAndAnswer(id, deleteRuleDiff, change, actor, req)

      case eb:EmptyBox =>
        val fail = eb ?~ (s"Could not find Rule ${ruleId.value}" )
        val message = s"Could not delete Rule ${ruleId.value} cause is: ${fail.msg}."
        toJsonError(Some(ruleId.value), message)
    }
  }

  def updateRule(id: String, req: Req, restValues : Box[RestRule]) = {
    implicit val action = "updateRule"
    implicit val prettify = restExtractor.extractPrettify(req.params)
    val actor = getActor(req)
    val ruleId = RuleId(id)

    readRule.get(ruleId) match {
      case Full(rule) =>
        restValues match {
          case Full(restRule) =>
            val updatedRule = restRule.updateRule(rule)
            val diff = ModifyToRuleDiff(updatedRule)
            val change = RuleChangeRequest(RuleModAction.Update, updatedRule, Some(rule))
            createChangeRequestAndAnswer(id, diff, change, actor, req)

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

class RuleApiService6 (
    readRuleCategory     : RoRuleCategoryRepository
  , readRule             : RoRuleRepository
  , writeRuleCategory    : WoRuleCategoryRepository
  , categoryService      : RuleCategoryService
  , restDataSerializer   : RestDataSerializer
) extends Loggable {

  def getCategoryInformations(category: RuleCategory, parent: RuleCategoryId, detail : DetailLevel) = {
    for {
      rules <- readRule.getAll()
    } yield {
      restDataSerializer.serializeRuleCategory(category, parent, rules.groupBy(_.categoryId), detail)
    }
  }

  def getCategoryTree = {
    for {
        root <- readRuleCategory.getRootCategory()
        categories <- getCategoryInformations(root,root.id,FullDetails)
    } yield {
      categories
    }
  }

  def getCategoryDetails(id : RuleCategoryId) = {
    for {
      root <- readRuleCategory.getRootCategory()
      (category,parent) <- root.find(id)
      categories <- getCategoryInformations(category,parent,MinimalDetails)
    } yield {
      categories
    }
  }

  def deleteCategory(id : RuleCategoryId)(actor : EventActor, modId : ModificationId, reason : Option[String]) = {
    for {
      root <- readRuleCategory.getRootCategory()
      (category,parent) <- root.find(id)
      rules <- readRule.getAll()
      ok <- if (category.canBeDeleted(rules.toList)) {
              Full("ok")
            } else {
              Failure(s"Cannot delete category '${category.name}' since that category is not empty")
            }
      _ <- writeRuleCategory.delete(id, modId, actor, reason)
      category <- getCategoryInformations(category,parent,MinimalDetails)
    } yield {
      category
    }
  }

  def updateCategory(id : RuleCategoryId, restData: RestRuleCategory)(actor : EventActor, modId : ModificationId, reason : Option[String]) = {
    logger.info(restData)
    for {
      root <- readRuleCategory.getRootCategory()
      (category,parent) <- root.find(id)
      rules <- readRule.getAll()
      update = restData.update(category)
      updatedParent = restData.parent.getOrElse(parent)

      _ <- restData.parent match {
        case Some(parent) =>
          writeRuleCategory.updateAndMove(update, parent, modId, actor, reason)
        case None =>
          writeRuleCategory.updateAndMove(update, parent, modId, actor, reason)
      }
      category <- getCategoryInformations(update,updatedParent,MinimalDetails)
    } yield {
      category
    }
  }

  def createCategory(id : RuleCategoryId, restData: RestRuleCategory)(actor : EventActor, modId : ModificationId, reason : Option[String]) = {
    for {
      update <- restData.create(id)
      parent = restData.parent.getOrElse(RuleCategoryId("rootRuleCategory"))
      _ <-writeRuleCategory.create(update,parent, modId, actor, reason)
      category <- getCategoryInformations(update,parent,MinimalDetails)
    } yield {
      category
    }
  }

}
