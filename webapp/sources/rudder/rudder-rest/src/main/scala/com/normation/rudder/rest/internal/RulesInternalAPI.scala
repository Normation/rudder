package com.normation.rudder.rest.internal

import com.normation.errors.EitherToIoResult
import com.normation.errors.IOResult
import com.normation.rudder.api.ApiVersion
import com.normation.rudder.apidata.JsonResponseObjects.JRRuleNodesDirectives
import com.normation.rudder.apidata.implicits._
import com.normation.rudder.domain.logger.TimingDebugLoggerPure
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.repository.RoNodeGroupRepository
import com.normation.rudder.repository.RoRuleRepository
import com.normation.rudder.rest.{RuleInternalApi => API}
import com.normation.rudder.rest.ApiModuleProvider
import com.normation.rudder.rest.ApiPath
import com.normation.rudder.rest.AuthzToken
import com.normation.rudder.rest.RestExtractorService
import com.normation.rudder.rest.implicits._
import com.normation.rudder.rest.lift.DefaultParams
import com.normation.rudder.rest.lift.LiftApiModule
import com.normation.rudder.rest.lift.LiftApiModuleProvider
import com.normation.rudder.rest.lift.LiftApiModuleString
import com.normation.rudder.services.nodes.NodeInfoService
import com.normation.zio.currentTimeMillis
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import zio.syntax._

class RulesInternalApi(
    restExtractorService:   RestExtractorService,
    ruleInternalApiService: RuleInternalApiService
) extends LiftApiModuleProvider[API] {

  def schemas: ApiModuleProvider[API] = API

  def getLiftEndpoints(): List[LiftApiModule] = {
    API.endpoints.map(e => {
      e match {
        case API.GetRuleNodesAndDirectives => GetRuleNodesAndDirectives
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
      (for {
        id <- RuleId.parse(sid).toIO
        r  <- ruleInternalApiService.GetRuleNodesAndDirectives(id)
      } yield r).toLiftResponseOne(params, schema, s => Some(s.id))
    }
  }
}

class RuleInternalApiService(
    readRule:  RoRuleRepository,
    readGroup: RoNodeGroupRepository,
    readNodes: NodeInfoService
) {
  def GetRuleNodesAndDirectives(id: RuleId): IOResult[JRRuleNodesDirectives] = {
    for {
      t1   <- currentTimeMillis
      rule <- readRule.get(id)

      t2        <- currentTimeMillis
      allGroups <- readGroup.getAllNodeIdsChunk()
      t3        <- currentTimeMillis
      nodeInfos <- readNodes.getAll()
      t4        <- currentTimeMillis
      nodesIds  <- RoNodeGroupRepository.getNodeIdsChunk(allGroups, rule.targets, nodeInfos).size.succeed
      t5        <- currentTimeMillis

      _ <- TimingDebugLoggerPure.trace(s"GetRuleNodesAndDirectives - readRule in ${t2 - t1} ms")
      _ <- TimingDebugLoggerPure.trace(s"GetRuleNodesAndDirectives - getAllNodeIdsChunk in ${t3 - t2} ms")
      _ <- TimingDebugLoggerPure.trace(s"GetRuleNodesAndDirectives - readNodes.getAll() in ${t4 - t3} ms")
      _ <- TimingDebugLoggerPure.trace(s"GetRuleNodesAndDirectives - getNodeIdsChunk in ${t5 - t4} ms")

    } yield {
      JRRuleNodesDirectives.fromData(id, nodesIds, rule.directiveIds.size)
    }
  }
}
