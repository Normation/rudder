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

import com.normation.GitVersion
import com.normation.errors.*
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.inventory.domain.NodeId
import com.normation.rudder.api.ApiVersion
import com.normation.rudder.apidata.JsonQueryObjects.*
import com.normation.rudder.apidata.JsonResponseObjects.*
import com.normation.rudder.apidata.ZioJsonExtractor
import com.normation.rudder.apidata.implicits.*
import com.normation.rudder.batch.AsyncDeploymentActor
import com.normation.rudder.batch.AutomaticStartDeployment
import com.normation.rudder.configuration.ConfigurationRepository
import com.normation.rudder.domain.logger.ConfigurationLoggerPure
import com.normation.rudder.domain.policies.*
import com.normation.rudder.domain.policies.ApplicationStatus
import com.normation.rudder.domain.policies.ChangeRequestRuleDiff
import com.normation.rudder.facts.nodes.*
import com.normation.rudder.repository.*
import com.normation.rudder.rest.*
import com.normation.rudder.rest.ApiPath
import com.normation.rudder.rest.AuthzToken
import com.normation.rudder.rest.RuleApi as API
import com.normation.rudder.rest.implicits.*
import com.normation.rudder.rule.category.*
import com.normation.rudder.rule.category.RuleCategoryId
import com.normation.rudder.services.policies.RuleApplicationStatusService
import com.normation.rudder.services.workflows.*
import com.normation.rudder.web.services.ComputePolicyMode
import com.normation.rudder.web.services.ComputePolicyMode.ComputedPolicyMode
import com.normation.utils.StringUuidGenerator
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import org.joda.time.DateTime
import scala.collection.MapView
import zio.*
import zio.syntax.*

class RuleApi(
    zioJsonExtractor: ZioJsonExtractor,
    service:          RuleApiService14,
    uuidGen:          StringUuidGenerator
) extends LiftApiModuleProvider[API] {

  def schemas: ApiModuleProvider[API] = API

  def getLiftEndpoints(): List[LiftApiModule] = {
    API.endpoints.map {
      case API.ListRules                       => ListRules
      case API.RuleDetails                     => RuleDetails
      case API.CreateRule                      => CreateRule
      case API.UpdateRule                      => UpdateRule
      case API.DeleteRule                      => DeleteRule
      case API.GetRuleTree                     => GetRuleTree
      case API.GetRuleCategoryDetails          => GetRuleCategoryDetails
      case API.CreateRuleCategory              => CreateRuleCategory
      case API.UpdateRuleCategory              => UpdateRuleCategory
      case API.DeleteRuleCategory              => DeleteRuleCategory
      case API.LoadRuleRevisionForGeneration   => LoadRuleRevisionForGeneration
      case API.UnloadRuleRevisionForGeneration => UnloadRuleRevisionForGeneration
    }
  }

  //////////////////// new API using only zio_json ////////////////////

  object ListRules extends LiftApiModule0 {
    val schema:                                                                                                API.ListRules.type = API.ListRules
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse       = {
      implicit val qc: QueryContext = authzToken.qc
      service.listRules().toLiftResponseList(params, schema)
    }
  }

  object RuleDetails extends LiftApiModuleString {
    val schema: API.RuleDetails.type = API.RuleDetails
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        sid:        String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val qc: QueryContext = authzToken.qc
      (for {
        id <- RuleId.parse(sid).toIO
        r  <- service.getRule(id)
      } yield r).toLiftResponseOne(params, schema, s => Some(s.id))
    }
  }

  object CreateRule extends LiftApiModule0 {
    val schema: API.CreateRule.type = API.CreateRule

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      implicit val qc: QueryContext = authzToken.qc
      (for {
        restRule <- zioJsonExtractor.extractRule(req).chainError(s"Could not extract rule parameters from request").toIO
        result   <- service.createRule(
                      restRule,
                      restRule.id.getOrElse(RuleId(RuleUid(uuidGen.newUuid))),
                      restRule.source,
                      params,
                      authzToken.qc.actor
                    )
      } yield {
        val action = if (restRule.source.nonEmpty) "cloneRule" else schema.name
        (RudderJsonResponse.ResponseSchema(action, schema.dataContainer), result)
      }).toLiftResponseOneMap(params, RudderJsonResponse.ResponseSchema.fromSchema(schema), x => (x._1, x._2, Some(x._2.id)))
    }
  }

  object UpdateRule extends LiftApiModuleString {
    val schema: API.UpdateRule.type = API.UpdateRule
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        sid:        String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val qc: QueryContext = authzToken.qc
      (for {
        id       <- RuleId.parse(sid).toIO
        restRule <- zioJsonExtractor.extractRule(req).chainError(s"Could not extract a rule from request.").toIO
        res      <- service.updateRule(restRule.copy(id = Some(id)), params, authzToken.qc.actor)
      } yield {
        res
      }).toLiftResponseOne(params, schema, s => Some(s.id))
    }
  }

  object DeleteRule extends LiftApiModuleString {
    val schema: API.DeleteRule.type = API.DeleteRule
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        sid:        String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val qc: QueryContext = authzToken.qc
      (for {
        id <- RuleId.parse(sid).toIO
        r  <- service.deleteRule(id, params, authzToken.qc.actor)
      } yield r).toLiftResponseOne(params, schema, s => Some(s.id))

    }
  }

  object GetRuleTree extends LiftApiModule0 {
    val schema: API.GetRuleTree.type = API.GetRuleTree

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      implicit val qc: QueryContext = authzToken.qc
      service.getCategoryTree().toLiftResponseOne(params, schema, s => Some(s.ruleCategories.id))
    }
  }

  object GetRuleCategoryDetails extends LiftApiModuleString {
    val schema: API.GetRuleCategoryDetails.type = API.GetRuleCategoryDetails
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        id:         String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      service.getCategoryDetails(RuleCategoryId(id)).toLiftResponseOne(params, schema, s => Some(s.ruleCategories.id))
    }
  }

  object CreateRuleCategory extends LiftApiModule0 {
    val schema:                                                                                                API.CreateRuleCategory.type = API.CreateRuleCategory
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse                = {
      (for {
        cat <- zioJsonExtractor.extractRuleCategory(req).toIO
        res <- service.createCategory(cat, () => uuidGen.newUuid, params, authzToken.qc.actor)
      } yield {
        res
      }).toLiftResponseOne(params, schema, s => Some(s.ruleCategories.id))
    }
  }

  object UpdateRuleCategory extends LiftApiModuleString {
    val schema: API.UpdateRuleCategory.type = API.UpdateRuleCategory
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        id:         String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      (for {
        cat <- zioJsonExtractor.extractRuleCategory(req).toIO
        res <- service.updateCategory(RuleCategoryId(id), cat, params, authzToken.qc.actor)
      } yield {
        res
      }).toLiftResponseOne(params, schema, s => Some(s.ruleCategories.id))
    }
  }

  object DeleteRuleCategory extends LiftApiModuleString {
    val schema: API.DeleteRuleCategory.type = API.DeleteRuleCategory
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        id:         String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      service
        .deleteCategory(RuleCategoryId(id), params, authzToken.qc.actor)
        .toLiftResponseOne(params, schema, s => Some(s.ruleCategories.id))
    }
  }

  object LoadRuleRevisionForGeneration extends LiftApiModuleString {
    val schema: API.LoadRuleRevisionForGeneration.type = API.LoadRuleRevisionForGeneration
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        id:         String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val qc: QueryContext = authzToken.qc

      (for {
        rid <- RuleId.parse(id).toIO
        res <- service.loadRule(rid, params, authzToken.qc.actor)
      } yield res).toLiftResponseOne(params, schema, s => Some(s.id))
    }
  }

  object UnloadRuleRevisionForGeneration extends LiftApiModuleString {
    val schema: API.UnloadRuleRevisionForGeneration.type = API.UnloadRuleRevisionForGeneration
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        id:         String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      (for {
        rid <- RuleId.parse(id).toIO
        res <- service.unloadRule(rid, params, authzToken.qc.actor)
      } yield res).toLiftResponseOne(params, schema, s => Some(s.serialize))
    }
  }

}

final case class RuleApplicationStatus(policyMode: ComputedPolicyMode, applicationStatusDetails: (String, Option[String]))

class RuleApiService14(
    readRule:             RoRuleRepository,
    writeRule:            WoRuleRepository,
    configRepository:     ConfigurationRepository,
    uuidGen:              StringUuidGenerator,
    asyncDeploymentAgent: AsyncDeploymentActor,
    workflowLevelService: WorkflowLevelService,
    readRuleCategory:     RoRuleCategoryRepository,
    writeRuleCategory:    WoRuleCategoryRepository,
    readDirectives:       RoDirectiveRepository,
    readGroup:            RoNodeGroupRepository,
    nodeFactRepos:        CoreNodeFactRepository,
    getGlobalPolicyMode:  () => IOResult[GlobalPolicyMode],
    applicationService:   RuleApplicationStatusService
) {

  // this Id is special and use client side to identify missing rules
  val MISSING_RULE_CAT_ID: RuleCategoryId = RuleCategoryId("ui-missing-rule-category")

  private def createChangeRequest(
      diff:   ChangeRequestRuleDiff,
      change: RuleChangeRequest,
      params: DefaultParams,
      actor:  EventActor
  )(implicit qc: QueryContext) = {
    for {
      workflow     <- workflowLevelService.getForRule(actor, change).toIO
      cr            = ChangeRequestService.createChangeRequestFromRule(
                        params.changeRequestName.getOrElse(
                          s"${change.action.name} rule '${change.newRule.name}' (${change.newRule.id.serialize}) by API request"
                        ),
                        params.changeRequestDescription.getOrElse(""),
                        change.newRule,
                        change.previousRule,
                        diff,
                        actor,
                        params.reason
                      )
      id           <- workflow
                        .startWorkflow(cr)(
                          ChangeContext(ModificationId(uuidGen.newUuid), actor, new DateTime(), params.reason, None, qc.nodePerms)
                        )
                        .toIO
      directiveLib <- readDirectives.getFullDirectiveLibrary()
      groupLib     <- readGroup.getFullGroupLibrary()
      nodesLib     <- nodeFactRepos.getAll()
      globalMode   <- getGlobalPolicyMode()

    } yield {
      val status = getRuleApplicationStatus(change.newRule, groupLib, directiveLib, nodesLib, globalMode)

      val optCrId = if (workflow.needExternalValidation()) Some(id) else None
      JRRule.fromRule(change.newRule, optCrId, Some(status.policyMode.name), Some(status.applicationStatusDetails))
    }
  }

  def getRuleApplicationStatus(
      rule:         Rule,
      groupLib:     FullNodeGroupCategory,
      directiveLib: FullActiveTechniqueCategory,
      nodesLib:     MapView[NodeId, CoreNodeFact],
      globalMode:   GlobalPolicyMode
  ): RuleApplicationStatus = {
    val directives               =
      rule.directiveIds.flatMap(directiveLib.allDirectives.get(_)).map { case (a, d) => (a.toActiveTechnique(), d) }
    val arePolicyServers         = nodesLib.mapValues(_.rudderSettings.isPolicyServer)
    val nodesIds                 = groupLib.getNodeIds(rule.targets, arePolicyServers)
    // for performance reason, it's necessary to keep the .view.filterKeys, as it is 10 times
    // faster than traditional groupLib.getNodeIds(rule.targets, nodesLib).flatMap(nodesLib.get)
    val nodes                    = nodesLib.filterKeys(x => nodesIds.contains(x)).values
    val allTargets               = rule.targets.flatMap(groupLib.allTargets.get).map(_.toTargetInfo)
    val policyMode               = ComputePolicyMode.ruleMode(globalMode, directives.map(_._2), nodes.map(_.rudderSettings.policyMode))
    val applicationStatus        = applicationService.isApplied(rule, groupLib, directiveLib, arePolicyServers, Some(nodesIds))
    val applicationStatusDetails = ApplicationStatus.details(rule, applicationStatus, allTargets, directives, nodes.isEmpty)
    RuleApplicationStatus(policyMode, applicationStatusDetails)
  }

  def listRules()(implicit qc: QueryContext): IOResult[Seq[JRRule]] = {
    for {
      rules        <- readRule.getAll(false).chainError("Could not fetch Rules")
      directiveLib <- readDirectives.getFullDirectiveLibrary()
      groupLib     <- readGroup.getFullGroupLibrary()
      nodesLib     <- nodeFactRepos.getAll()
      globalMode   <- getGlobalPolicyMode()

    } yield {
      for {
        rule <- rules.sortBy(_.id.serialize)
      } yield {
        val status = getRuleApplicationStatus(rule, groupLib, directiveLib, nodesLib, globalMode)
        JRRule.fromRule(rule, None, Some(status.policyMode.name), Some(status.applicationStatusDetails))
      }
    }

  }

  def createRule(
      restRule: JQRule,
      ruleId:   RuleId,
      clone:    Option[RuleId],
      params:   DefaultParams,
      actor:    EventActor
  )(implicit qc: QueryContext): IOResult[JRRule] = {
    // decide if we should create a new rule or clone an existing one
    // Return the source rule to use in each case.
    def createOrClone(
        name:     String,
        restRule: JQRule,
        ruleId:   RuleId,
        clone:    Option[RuleId],
        params:   DefaultParams,
        actor:    EventActor
    ): IOResult[RuleChangeRequest] = {
      clone match {
        case Some(sourceId) =>
          // clone existing rule
          for {
            rule <-
              readRule
                .get(sourceId)
                .chainError(s"Could not create rule '${name}' (id:${ruleId.serialize}) by cloning rule '${sourceId.serialize}')")
          } yield {
            RuleChangeRequest(RuleModAction.Create, restRule.updateRule(rule).copy(id = ruleId), Some(rule))
          }

        case None =>
          // create from scratch - base rule is the same with default values
          val category       = restRule.categoryId.getOrElse("rootRuleCategory")
          val baseRule       = Rule(ruleId, name, RuleCategoryId(category))
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
            workflow <- workflowLevelService
                          .getForRule(actor, change)
                          .toIO
                          .chainError("Could not find workflow status for that rule creation")
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
      name   <- restRule.displayName.notOptional("Missing manadatory parameter 'displayName'")
      change <- createOrClone(name, restRule, ruleId, clone, params, actor)
      modId   = ModificationId(uuidGen.newUuid)
      _      <- writeRule.create(change.newRule, modId, actor, params.reason)

      directiveLib <- readDirectives.getFullDirectiveLibrary()
      groupLib     <- readGroup.getFullGroupLibrary()
      nodesLib     <- nodeFactRepos.getAll()
      globalMode   <- getGlobalPolicyMode()
    } yield {
      val status = getRuleApplicationStatus(change.newRule, groupLib, directiveLib, nodesLib, globalMode)
      asyncDeploymentAgent ! AutomaticStartDeployment(modId, actor)
      JRRule.fromRule(change.newRule, None, Some(status.policyMode.name), Some(status.applicationStatusDetails))
    }).chainError(s"Error when creating new rule")
  }

  def getRule(id: RuleId)(implicit qc: QueryContext): IOResult[JRRule] = {
    for {
      rule <- readRule.get(id)

      directiveLib <- readDirectives.getFullDirectiveLibrary()
      groupLib     <- readGroup.getFullGroupLibrary()
      nodesLib     <- nodeFactRepos.getAll()
      globalMode   <- getGlobalPolicyMode()
    } yield {
      val status = getRuleApplicationStatus(rule, groupLib, directiveLib, nodesLib, globalMode)
      JRRule.fromRule(rule, None, Some(status.policyMode.name), Some(status.applicationStatusDetails))
    }

  }

  /*
   * Loading a rule with `revision == a branch name` won't follow that branch name, but just load the current
   * configuration for the rule at that branch. If the rule for that branch was already loaded, the last available
   * version will be used.
   * Loading a rule a `revision == commit id` will look for that commit id.
   * You can't load a rule with default revision (should it be a noop instead?)
   */
  def loadRule(id: RuleId, params: DefaultParams, actor: EventActor)(implicit qc: QueryContext): IOResult[JRRule] = {
    (if (id.rev == GitVersion.DEFAULT_REV) {
       Inconsistency(s"The default revision can not be specifically loaded for generation (it is always loaded)").fail
     } else {
       for {
         rule         <-
           configRepository
             .getRule(id)
             .notOptional(
               (s"Could not get rule with id '${id.uid.serialize}' and revision '${id.rev.value}' from configuration repository")
             )
         // perhaps that will need to go throught change requests
         modId         = ModificationId(uuidGen.newUuid)
         ldap         <- writeRule.load(rule, modId, actor, params.reason)
         _            <-
           ConfigurationLoggerPure.info(
             s"Revision '${id.rev.value}' for rule with id '${id.uid.serialize}' loaded. It will be used in comming policy generations."
           )
         directiveLib <- readDirectives.getFullDirectiveLibrary()
         groupLib     <- readGroup.getFullGroupLibrary()
         nodesLib     <- nodeFactRepos.getAll()
         globalMode   <- getGlobalPolicyMode()
       } yield {
         val status = getRuleApplicationStatus(rule, groupLib, directiveLib, nodesLib, globalMode)
         asyncDeploymentAgent ! AutomaticStartDeployment(modId, actor)
         JRRule.fromRule(rule, None, Some(status.policyMode.name), Some(status.applicationStatusDetails))
       }
     })
  }

  def unloadRule(id: RuleId, params: DefaultParams, actor: EventActor): IOResult[RuleId] = {
    (if (id.rev == GitVersion.DEFAULT_REV) {
       Inconsistency(s"The default revision can not be specifically loaded for generation (it is always loaded)").fail
     } else {
       val modId = ModificationId(uuidGen.newUuid)
       for {
         // perhaps that will need to go throught change requests
         ldap <- writeRule.unload(id, modId, actor, params.reason)
         _    <-
           ConfigurationLoggerPure.info(
             s"Revision '${id.rev.value}' for rule with id '${id.uid.serialize}' unloaded. It will not be used anymore in comming policy generations."
           )
       } yield {
         asyncDeploymentAgent ! AutomaticStartDeployment(modId, actor)
         id
       }
     })
  }

  def updateRule(restRule: JQRule, params: DefaultParams, actor: EventActor)(implicit qc: QueryContext): IOResult[JRRule] = {
    for {
      id         <- restRule.id.notOptional(s"Rule id is mandatory in update")
      rule       <- readRule.get(id)
      updatedRule = restRule.updateRule(rule)
      diff        = ModifyToRuleDiff(updatedRule)
      change      = RuleChangeRequest(RuleModAction.Update, updatedRule, Some(rule))
      res        <- createChangeRequest(diff, change, params, actor)
    } yield {
      res
    }
  }

  def deleteRule(id: RuleId, params: DefaultParams, actor: EventActor)(implicit qc: QueryContext): IOResult[JRRule] = {
    // if the rule is already missing, we report a success
    id.rev match {
      case GitVersion.DEFAULT_REV =>
        readRule.getOpt(id).flatMap {
          case Some(rule) =>
            val change = RuleChangeRequest(RuleModAction.Delete, rule, Some(rule))
            createChangeRequest(DeleteRuleDiff(rule), change, params, actor)
          case None       =>
            JRRule.empty(id.serialize).succeed
        }
      case _                      => Inconsistency(s"You can't delete a past revision of a rule, only current version can be deleted.").fail
    }
  }

  def listCategoriesId(cat: RuleCategory): Set[RuleCategoryId] = {
    def listCatAcc(categories: List[RuleCategory], acc: List[RuleCategoryId]): List[RuleCategoryId] = {
      categories match {
        case Nil    => acc
        case c :: t =>
          val children = listCatAcc(c.childs, c.id :: acc)
          listCatAcc(t, children)
      }
    }
    listCatAcc(cat.childs, List(cat.id)).toSet
  }

  // List all categories mentioned in categoryId in rules who are not in database
  def getMissingCategories(existingCat: RuleCategory, rules: List[Rule]): Set[RuleCategory] = {
    val catIds   = listCategoriesId(existingCat)
    val rulesCat = rules.map(_.categoryId).toSet
    rulesCat
      .diff(catIds)
      .map(rId => {
        RuleCategory(
          rId,
          s"<${rId.value}>",
          s"Category ${rId.value} has been deleted, please move rules to available categories",
          List(),
          isSystem = false
        )
      })
  }

  def getCategoryTree()(implicit qc: QueryContext): IOResult[JRCategoriesRootEntryFull] = {
    for {
      root             <- readRuleCategory.getRootCategory()
      rules            <- readRule.getAll()
      directiveLib     <- readDirectives.getFullDirectiveLibrary()
      groupLib         <- readGroup.getFullGroupLibrary()
      nodesLib         <- nodeFactRepos.getAll()
      globalMode       <- getGlobalPolicyMode()
      rulesMap          = (for {
                            rule <- rules.sortBy(_.id.serialize)
                          } yield {
                            val status = getRuleApplicationStatus(rule, groupLib, directiveLib, nodesLib, globalMode)
                            (rule, Some(status.policyMode.name), Some(status.applicationStatusDetails))
                          }).groupBy(_._1.categoryId.value)
      missingCatContent = getMissingCategories(root, rules.toList)
      missingCategory   = RuleCategory(
                            MISSING_RULE_CAT_ID,
                            "Rules with a missing/deleted category",
                            "Category that regroup all the missing categories",
                            missingCatContent.toList
                          )
      newChilds         = if (missingCatContent.isEmpty) root.childs else root.childs :+ missingCategory
      rootAndMissingCat = root.copy(childs = newChilds)
    } yield {
      // root category is given itself as a parent, which looks like a bug
      JRCategoriesRootEntryFull(JRFullRuleCategory.fromCategory(rootAndMissingCat, rulesMap, Some(rootAndMissingCat.id.value)))
    }
  }

  def getCategoryDetails(id: RuleCategoryId): IOResult[JRCategoriesRootEntrySimple] = {
    // returns (parent, child)
    def recFind(root: RuleCategory, id: RuleCategoryId): Option[(RuleCategory, RuleCategory)] = {
      root.childs.foldLeft(Option.empty[(RuleCategory, RuleCategory)]) {
        case (None, cat)     => if (cat.id == id) Some((root, cat)) else recFind(cat, id)
        case (x: Some[?], _) => x
      }
    }
    for {
      root  <- readRuleCategory.getRootCategory()
      rules <- readRule.getAll()
      found <- (
                 if (root.id == id) {
                   Some((root, root))
                 } else {
                   recFind(root, id) match {
                     case None =>
                       // try to find if a rule has a category that is no longer available but still mentioned in a rule
                       getMissingCategories(root, rules.toList).find(id.value == _.id.value) match {
                         case Some(cat) => Some((root, cat))
                         case _         =>
                           // The root category for missing/deleted categories
                           if (id == MISSING_RULE_CAT_ID) {
                             val missingCategory = RuleCategory(
                               MISSING_RULE_CAT_ID,
                               "Rules with a missing/deleted category",
                               "Category that regroup all the missing categories",
                               List.empty
                             )
                             Some((root, missingCategory))
                           } else {
                             None
                           }
                       }
                     case c    => c
                   }
                 }
               ).notOptional(s"Error: rule category with id '${id.value}' was not found")
      rules <- readRule.getAll().map(_.groupBy(_.categoryId).get(id))
    } yield {
      JRCategoriesRootEntrySimple(
        JRSimpleRuleCategory.fromCategory(
          found._2,
          found._1.id.value,
          rules.map(_.map(_.id.serialize).toList.sorted).getOrElse(Nil)
        )
      )
    }
  }

  def deleteCategory(id: RuleCategoryId, params: DefaultParams, actor: EventActor): IOResult[JRCategoriesRootEntrySimple] = {
    for {
      root              <- readRuleCategory.getRootCategory()
      found             <- root.find(id).toIO
      (category, parent) = found
      rules             <- readRule.getAll()
      ok                <- ZIO.when(!category.canBeDeleted(rules.toList)) {
                             Inconsistency(s"Cannot delete category '${category.name}' since that category is not empty").fail
                           }
      category          <- getCategoryDetails(id)
      _                 <- writeRuleCategory.delete(id, ModificationId(uuidGen.newUuid), actor, params.reason)
    } yield {
      category
    }
  }

  def updateCategory(
      id:       RuleCategoryId,
      restData: JQRuleCategory,
      params:   DefaultParams,
      actor:    EventActor
  ): IOResult[JRCategoriesRootEntrySimple] = {
    for {
      root                <- readRuleCategory.getRootCategory()
      found               <- root.find(id).toIO
      (category, parentId) = found
      rules               <- readRule.getAll()
      update               = restData.update(category)
      modId                = ModificationId(uuidGen.newUuid)
      _                   <- restData.parent match {
                               case Some(parent) =>
                                 writeRuleCategory.updateAndMove(update, RuleCategoryId(parent), modId, actor, params.reason)
                               case None         =>
                                 writeRuleCategory.updateAndMove(update, parentId, modId, actor, params.reason)
                             }
      category            <- getCategoryDetails(id)
    } yield {
      category
    }
  }

  def createCategory(
      restData:  JQRuleCategory,
      defaultId: () => String,
      params:    DefaultParams,
      actor:     EventActor
  ): IOResult[JRCategoriesRootEntrySimple] = {
    for {
      name     <- restData.name.checkMandatory(_.size > 3, v => "'displayName' is mandatory and must be at least 3 char long")
      update    = RuleCategory(RuleCategoryId(restData.id.getOrElse(defaultId())), name, restData.description.getOrElse(""), Nil)
      parent    = restData.parent.getOrElse("rootRuleCategory")
      modId     = ModificationId(uuidGen.newUuid)
      _        <- writeRuleCategory.create(update, RuleCategoryId(parent), modId, actor, params.reason)
      category <- getCategoryDetails(update.id)
    } yield {
      category
    }
  }

}
