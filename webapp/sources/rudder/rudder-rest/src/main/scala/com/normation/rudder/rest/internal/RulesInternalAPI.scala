package com.normation.rudder.rest.internal

import com.normation.errors.EitherToIoResult
import com.normation.errors.IOResult
import com.normation.rudder.api.ApiVersion
import com.normation.rudder.apidata.JsonResponseObjects.*
import com.normation.rudder.apidata.implicits.*
import com.normation.rudder.domain.logger.TimingDebugLoggerPure
import com.normation.rudder.domain.policies.Rule
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.facts.nodes.NodeFactRepository
import com.normation.rudder.facts.nodes.QueryContext
import com.normation.rudder.repository.RoNodeGroupRepository
import com.normation.rudder.repository.RoRuleRepository
import com.normation.rudder.rest.ApiModuleProvider
import com.normation.rudder.rest.ApiPath
import com.normation.rudder.rest.AuthzToken
import com.normation.rudder.rest.RuleInternalApi as API
import com.normation.rudder.rest.implicits.*
import com.normation.rudder.rest.lift.*
import com.normation.rudder.rule.category.RoRuleCategoryRepository
import com.normation.rudder.rule.category.RuleCategory
import com.normation.rudder.rule.category.RuleCategoryId
import com.normation.zio.currentTimeMillis
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import zio.syntax.*

class RulesInternalApi(
    ruleInternalApiService: RuleInternalApiService,
    ruleApiService:         RuleApiService14
) extends LiftApiModuleProvider[API] {

  def schemas: ApiModuleProvider[API] = API

  def getLiftEndpoints(): List[LiftApiModule] = {
    API.endpoints.map(e => {
      e match {
        case API.GetRuleNodesAndDirectives => GetRuleNodesAndDirectives
        case API.GetGroupRelatedRules      => GetGroupRelatedRules
      }
    })
  }

  object GetRuleNodesAndDirectives extends LiftApiModuleString {
    val schema: API.GetRuleNodesAndDirectives.type = API.GetRuleNodesAndDirectives

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
        r  <- ruleInternalApiService.GetRuleNodesAndDirectives(id)
      } yield r).toLiftResponseOne(params, schema, s => Some(s.id))
    }
  }

  object GetGroupRelatedRules extends LiftApiModule0 {
    val schema: API.GetGroupRelatedRules.type = API.GetGroupRelatedRules

    /**
      * Request takes an optional query parameter list to filter rules in the tree
      * If the parameter is not present, all rules are returned.
      * All passed invalid or non existing rule ids are ignored.
      */
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      val ruleIdsFilter      = req.params
        .get("rules")
        .map(
          _.flatMap(seq => seq.split(',').map(_.strip()))
            .flatMap(RuleId.parse(_).toOption)
        )
      val getMissingCategory = ruleApiService.getMissingCategories _
      (for {
        r <- ruleInternalApiService
               .getGroupRelatedRules(ruleIdsFilter, getMissingCategory, ruleApiService.MISSING_RULE_CAT_ID)
      } yield r).toLiftResponseOne(params, schema, _ => None)
    }
  }

}

class RuleInternalApiService(
    readRule:         RoRuleRepository,
    readGroup:        RoNodeGroupRepository,
    readRuleCategory: RoRuleCategoryRepository,
    readNodes:        NodeFactRepository
) {
  def GetRuleNodesAndDirectives(id: RuleId)(implicit qc: QueryContext): IOResult[JRRuleNodesDirectives] = {
    for {
      t1   <- currentTimeMillis
      rule <- readRule.get(id)

      t2        <- currentTimeMillis
      allGroups <- readGroup.getAllNodeIdsChunk()
      t3        <- currentTimeMillis
      nodes     <- readNodes.getAll()
      t4        <- currentTimeMillis
      nodesIds  <- RoNodeGroupRepository
                     .getNodeIdsChunk(allGroups, rule.targets, nodes.mapValues(_.rudderSettings.isPolicyServer))
                     .size
                     .succeed
      t5        <- currentTimeMillis

      _ <- TimingDebugLoggerPure.trace(s"GetRuleNodesAndDirectives - readRule in ${t2 - t1} ms")
      _ <- TimingDebugLoggerPure.trace(s"GetRuleNodesAndDirectives - getAllNodeIdsChunk in ${t3 - t2} ms")
      _ <- TimingDebugLoggerPure.trace(s"GetRuleNodesAndDirectives - readNodes.getAll() in ${t4 - t3} ms")
      _ <- TimingDebugLoggerPure.trace(s"GetRuleNodesAndDirectives - getNodeIdsChunk in ${t5 - t4} ms")

    } yield {
      JRRuleNodesDirectives.fromData(id, nodesIds, rule.directiveIds.size)
    }
  }

  def getGroupRelatedRules(
      ruleIdsFilter:        Option[Seq[RuleId]], // optional list of rule ids to filter the tree with
      getMissingCategories: (RuleCategory, List[Rule]) => Set[RuleCategory],
      missingCatId:         RuleCategoryId
  ): IOResult[JRCategoriesRootEntryInfo] = {
    for {
      root              <- readRuleCategory.getRootCategory()
      rules             <- ruleIdsFilter match {
                             case None      => readRule.getAll()
                             case Some(ids) => readRule.getAll().map(_.filter(r => ids.contains(r.id)))
                           }
      missingCatContent  = getMissingCategories(root, rules.toList)
      missingCategory    = RuleCategory(
                             missingCatId,
                             "Rules with a missing/deleted category",
                             "Category that regroup all the missing categories",
                             missingCatContent.toList
                           )
      newChilds          = if (missingCatContent.isEmpty) root.childs else root.childs :+ missingCategory
      rootAndMissingCat  = root.copy(childs = newChilds)
      rulesMapByCategory = rules.groupBy(_.categoryId)
    } yield {
      JRCategoriesRootEntryInfo(
        JRRuleCategoryInfo.fromCategory(rootAndMissingCat, rulesMapByCategory, Some(rootAndMissingCat.id.value))
      )
    }
  }

}
