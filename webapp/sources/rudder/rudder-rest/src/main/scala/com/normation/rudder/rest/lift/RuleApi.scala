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
import com.normation.rudder.batch.AsyncDeploymentAgent
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
import com.normation.rudder.services.workflows.WorkflowService
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
  val dataName = "ruleCategories"

  def response ( function : Box[JValue], req : Req, errorMessage : String)(implicit action : String) : LiftResponse = {
    RestUtils.response(restExtractorService, dataName,None)(function, req, errorMessage)
  }

  def actionResponse ( function: Box[ActionType], req: Req, errorMessage: String, actor: EventActor)(implicit action : String) : LiftResponse = {
    RestUtils.actionResponse2(restExtractorService, dataName, uuidGen, None)(function, req, errorMessage)(action, actor)
  }

  def schemas = API

  def getLiftEndpoints(): List[LiftApiModule] = {
    API.endpoints.map(e => e match {
      case API.ListRules              => ListRules
      case API.CreateRule             => CreateRule
      case API.RuleDetails            => RuleDetails
      case API.DeleteRule             => DeleteRule
      case API.UpdateRule             => UpdateRule
      case API.GetRuleTree            => GetRuleTree
      case API.GetRuleCategoryDetails => GetRuleCategoryDetails
      case API.DeleteRuleCategory     => DeleteRuleCategory
      case API.UpdateRuleCategory     => UpdateRuleCategory
      case API.CreateRuleCategory     => CreateRuleCategory
    }).toList
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
      if(req.json_?) {
        req.json match {
          case Full(arg) =>
            val restRule = restExtractor.extractRuleFromJSON(arg)
            apiV2.createRule(restRule,req)
          case eb:EmptyBox=>
            toJsonError(None, JString("No Json data sent"))("createRule",restExtractor.extractPrettify(req.params))
        }
      } else {
        val restRule = restExtractor.extractRule(req.params)
        apiV2.createRule(restRule, req)
      }
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
        , authzToken.actor
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
        , authzToken.actor
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
        , authzToken.actor
      ) ("createRuleCategory")
    }
  }
}

class RuleApiService2 (
    readRule             : RoRuleRepository
  , writeRule            : WoRuleRepository
  , uuidGen              : StringUuidGenerator
  , asyncDeploymentAgent : AsyncDeploymentAgent
  , changeRequestService : ChangeRequestService
  , workflowService      : WorkflowService
  , restExtractor        : RestExtractorService
  , workflowEnabled      : () => Box[Boolean]
  , restDataSerializer   : RestDataSerializer
) ( implicit userService : UserService ) {

  import restDataSerializer.{serializeRule => serialize}

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
        reason <- restExtractor.extractReason(req)
        crName <- restExtractor.extractChangeRequestName(req).map(_.getOrElse(s"${act} Rule ${rule.name} from API"))
        crDescription = restExtractor.extractChangeRequestDescription(req)
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
        workflowEnabled() match {
          case Full(enabled) =>
            val optCrId = if (enabled) Some(crId) else None
            val jsonRule = List(serialize(rule,optCrId))
            toJsonResponse(Some(id), ("rules" -> JArray(jsonRule)))
          case eb : EmptyBox =>
            val fail = eb ?~ (s"Could not check workflow property" )
            val msg = s"Change request creation failed, cause is: ${fail.msg}."
            toJsonError(Some(id), msg)
        }
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
        reason   <- restExtractor.extractReason(req)
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

                workflowEnabled() match {
                  case Full(enabled) =>
                    val enableCheck = restRule.onlyName || (!enabled && defaultEnabled)
                    val category = restRule.category.getOrElse(RuleCategoryId("rootRuleCategory"))
                    val baseRule = Rule(ruleId,name,category)
                    // The enabled value in restRule will be used in the saved Rule
                    actualRuleCreation(restRule.copy(enabled = Some(enableCheck)),baseRule)

                  case eb : EmptyBox =>
                    val fail = eb ?~ (s"Could not check workflow property" )
                    val msg = s"Change request creation failed, cause is: ${fail.msg}."
                    toJsonError(Some(ruleId.value), msg)
                }

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
