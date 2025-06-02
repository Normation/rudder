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

import com.normation.box.*
import com.normation.errors.*
import com.normation.inventory.domain.NodeId
import com.normation.rudder.api.ApiVersion
import com.normation.rudder.domain.logger.TimingDebugLogger
import com.normation.rudder.domain.logger.TimingDebugLoggerPure
import com.normation.rudder.domain.nodes.NodeGroupCategoryId
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.policies.AllPolicyServers
import com.normation.rudder.domain.policies.AllTarget
import com.normation.rudder.domain.policies.AllTargetExceptPolicyServers
import com.normation.rudder.domain.policies.Directive
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.GlobalPolicyMode
import com.normation.rudder.domain.policies.GroupTarget
import com.normation.rudder.domain.policies.NonGroupRuleTarget
import com.normation.rudder.domain.policies.PolicyServerTarget
import com.normation.rudder.domain.policies.PolicyTypeName
import com.normation.rudder.domain.policies.Rule
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.RuleTarget
import com.normation.rudder.domain.policies.SimpleTarget
import com.normation.rudder.domain.reports.BlockStatusReport
import com.normation.rudder.domain.reports.ComplianceLevel
import com.normation.rudder.domain.reports.CompliancePrecision
import com.normation.rudder.domain.reports.ComponentStatusReport
import com.normation.rudder.domain.reports.DirectiveStatusReport
import com.normation.rudder.domain.reports.ValueStatusReport
import com.normation.rudder.facts.nodes.CoreNodeFact
import com.normation.rudder.facts.nodes.NodeFactRepository
import com.normation.rudder.facts.nodes.QueryContext
import com.normation.rudder.facts.nodes.RudderSettings
import com.normation.rudder.reports.GlobalComplianceMode
import com.normation.rudder.repository.FullActiveTechnique
import com.normation.rudder.repository.RoDirectiveRepository
import com.normation.rudder.repository.RoNodeGroupRepository
import com.normation.rudder.repository.RoRuleRepository
import com.normation.rudder.rest.{ComplianceApi as API, *}
import com.normation.rudder.rest.RestUtils.*
import com.normation.rudder.rest.data.*
import com.normation.rudder.rest.data.CsvCompliance.*
import com.normation.rudder.rest.data.JsonCompliance.*
import com.normation.rudder.services.reports.ReportingService
import com.normation.rudder.web.services.ComputePolicyMode
import com.normation.rudder.web.services.ComputePolicyMode.ComputedPolicyMode
import com.normation.zio.currentTimeMillis
import net.liftweb.common.*
import net.liftweb.http.LiftResponse
import net.liftweb.http.PlainTextResponse
import net.liftweb.http.Req
import net.liftweb.json.*
import net.liftweb.json.JsonDSL.*
import scala.collection.MapView
import scala.collection.immutable
import zio.Chunk
import zio.ZIO
import zio.syntax.*

class ComplianceApi(
    complianceService: ComplianceAPIService,
    readDirective:     RoDirectiveRepository
) extends LiftApiModuleProvider[API] {

  def extractComplianceLevel(params: Map[String, List[String]]): Box[Option[Int]] = {
    params.get("level") match {
      case None | Some(Nil) => Full(None)
      case Some(h :: tail)  => // only take into account the first level param is several are passed
        try { Full(Some(h.toInt)) }
        catch {
          case ex: NumberFormatException =>
            Failure(s"level (displayed level of compliance details) must be an integer, was: '${h}'")
        }
    }
  }

  def extractPercentPrecision(params: Map[String, List[String]]): Box[Option[CompliancePrecision]] = {
    params.get("precision") match {
      case None | Some(Nil) => Full(None)
      case Some(h :: tail)  => // only take into account the first level param is several are passed
        for {
          extracted <- try { Full(h.toInt) }
                       catch {
                         case ex: NumberFormatException => Failure(s"percent precison must be an integer, was: '${h}'")
                       }
          level     <- CompliancePrecision.fromPrecision(extracted)
        } yield {
          Some(level)
        }

    }
  }

  def extractComplianceFormat(params: Map[String, List[String]]): Box[ComplianceFormat] = {
    params.get("format") match {
      case None | Some(Nil) | Some("" :: Nil) =>
        Full(ComplianceFormat.JSON) // by default if no there is no format, should I choose the only one available ?
      case Some(format :: _)                  =>
        ComplianceFormat.fromValue(format).toBox
    }
  }

  def schemas: ApiModuleProvider[API] = API

  /*
   * The actual builder for the compliance API.
   * Depends on authz method and supported version.
   *
   * It's quite verbose, but it's the only way I found to
   * get the exhaustivity check and be sure that ALL
   * endpoints are processed.
   */
  def getLiftEndpoints(): List[LiftApiModule] = {
    API.endpoints.map {
      case API.GetRulesCompliance             => GetRules
      case API.GetRulesComplianceId           => GetRuleId
      case API.GetNodesCompliance             => GetNodes
      case API.GetNodeSystemCompliance        => GetNodeSystemCompliance
      case API.GetNodeComplianceId            => GetNodeId
      case API.GetGlobalCompliance            => GetGlobal
      case API.GetDirectiveComplianceId       => GetDirectiveId
      case API.GetDirectivesCompliance        => GetDirectives
      case API.GetNodeGroupComplianceSummary  => GetNodeGroupSummary
      case API.GetNodeGroupComplianceId       => GetNodeGroupId
      case API.GetNodeGroupComplianceTargetId => GetNodeGroupTargetId
    }
  }

  object GetRules extends LiftApiModule0 {
    val schema:                                                                                                API.GetRulesCompliance.type = API.GetRulesCompliance
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse                = {
      implicit val action   = schema.name
      implicit val prettify = params.prettify

      implicit val qc: QueryContext = authzToken.qc

      (for {
        level        <- extractComplianceLevel(req.params)
        precision    <- extractPercentPrecision(req.params)
        computeLevel <- Full(if (version.value <= 6) {
                          None
                        } else {
                          level
                        })
        rules        <- complianceService.getRulesCompliance(computeLevel)
      } yield {
        if (version.value <= 6) {
          rules.map(_.toJsonV6)
        } else {
          rules.map(
            _.toJson(level.getOrElse(10), precision.getOrElse(CompliancePrecision.Level2))
          ) // by default, all details are displayed
        }
      }) match {
        case Full(rules) =>
          toJsonResponse(None, ("rules" -> rules))

        case eb: EmptyBox =>
          val message = (eb ?~ (s"Could not get compliance for all rules")).messageChain
          toJsonError(None, JString(message))
      }
    }
  }

  object GetRuleId extends LiftApiModule {
    val schema: OneParam = API.GetRulesComplianceId
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        ruleId:     String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val action   = schema.name
      implicit val prettify = params.prettify
      implicit val qc: QueryContext = authzToken.qc

      (for {
        level     <- extractComplianceLevel(req.params)
        t1         = System.currentTimeMillis
        precision <- extractPercentPrecision(req.params)
        id        <- RuleId.parse(ruleId).toBox
        t2         = System.currentTimeMillis

        rule <- complianceService.getRuleCompliance(id, level)
        t3    = System.currentTimeMillis
        _     = TimingDebugLogger.trace(s"API GetRuleId - getting query param in ${t2 - t1} ms")
        _     = TimingDebugLogger.trace(s"API GetRuleId - getting rule compliance in ${t3 - t2} ms")

      } yield {
        if (version.value <= 6) {
          rule.toJsonV6
        } else {
          val json = rule.toJson(
            level.getOrElse(10),
            precision.getOrElse(CompliancePrecision.Level2)
          ) // by default, all details are displayed
          val t4 = System.currentTimeMillis
          TimingDebugLogger.trace(s"API GetRuleId - serialize to json in ${t4 - t3} ms")
          json
        }
      }) match {
        case Full(rule) =>
          toJsonResponse(None, ("rules" -> List(rule)))

        case eb: EmptyBox =>
          val message = (eb ?~ (s"Could not get compliance for rule '${ruleId}'")).messageChain
          toJsonError(None, JString(message))
      }
    }
  }

  object GetDirectiveId extends LiftApiModule {
    val schema: API.GetDirectiveComplianceId.type = API.GetDirectiveComplianceId

    def process(
        version:     ApiVersion,
        path:        ApiPath,
        directiveId: String,
        req:         Req,
        params:      DefaultParams,
        authzToken:  AuthzToken
    ): LiftResponse = {
      implicit val action   = schema.name
      implicit val prettify = params.prettify
      implicit val qc: QueryContext = authzToken.qc
      (for {
        level     <- extractComplianceLevel(req.params)
        t1         = System.currentTimeMillis
        precision <- extractPercentPrecision(req.params)
        format    <- extractComplianceFormat(req.params)
        id        <- DirectiveId.parse(directiveId).toBox
        d         <- readDirective.getDirective(id.uid).notOptional(s"Directive with id '${id.serialize}' not found'").toBox
        t2         = System.currentTimeMillis
        _          = TimingDebugLogger.trace(s"API DirectiveCompliance - getting query param in ${t2 - t1} ms")
        directive <- complianceService.getDirectiveCompliance(d, level)
        t3         = System.currentTimeMillis
        _          = TimingDebugLogger.trace(s"API DirectiveCompliance - getting directive compliance '${id.uid.value}' in ${t3 - t2} ms")
      } yield {
        format match {
          case ComplianceFormat.CSV  =>
            PlainTextResponse(directive.toCsv) // CSVFormat take cares of line separator
          case ComplianceFormat.JSON =>
            val json = directive.toJson(
              level.getOrElse(10),
              precision.getOrElse(CompliancePrecision.Level2)
            ) // by default, all details are displayed
            val t4 = System.currentTimeMillis
            TimingDebugLogger.trace(s"API DirectiveCompliance - serialize to json in ${t4 - t3} ms")
            toJsonResponse(None, ("directiveCompliance" -> json))
        }
      }) match {
        case Full(compliance) => compliance
        case eb: EmptyBox =>
          val message = (eb ?~ (s"Could not get compliance for directive '${directiveId}'")).messageChain
          toJsonError(None, JString(message))
      }
    }
  }

  object GetDirectives extends LiftApiModule0 {
    val schema: API.GetDirectivesCompliance.type = API.GetDirectivesCompliance

    def process0(
        version:    ApiVersion,
        path:       ApiPath,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val action   = schema.name
      implicit val prettify = params.prettify
      implicit val qc: QueryContext = authzToken.qc

      (for {
        level      <- extractComplianceLevel(req.params)
        t1          = System.currentTimeMillis
        precision  <- extractPercentPrecision(req.params)
        t2          = System.currentTimeMillis
        _           = TimingDebugLogger.trace(s"API DirectivesCompliance - getting query param in ${t2 - t1} ms")
        t4          = System.currentTimeMillis
        directives <- complianceService.getDirectivesCompliance(level)
        t5          = System.currentTimeMillis
        _           = TimingDebugLogger.trace(s"API DirectivesCompliance - getting directives compliance in ${t5 - t4} ms")

      } yield {
        val json = directives.map(
          _.toJson(
            level.getOrElse(10),
            precision.getOrElse(CompliancePrecision.Level2)
          )
        ) // by default, all details are displayed
        val t6 = System.currentTimeMillis
        TimingDebugLogger.trace(s"API DirectivesCompliance - serialize to json in ${t6 - t5} ms")
        json
      }) match {
        case Full(rule) =>
          toJsonResponse(None, ("directivesCompliance" -> rule))

        case eb: EmptyBox =>
          val message = (eb ?~ (s"Could not get compliance for directives'")).messageChain
          toJsonError(None, JString(message))
      }
    }
  }

  object GetNodeGroupSummary extends LiftApiModule0 {
    val schema: API.GetNodeGroupComplianceSummary.type = API.GetNodeGroupComplianceSummary
    def process0(
        version:    ApiVersion,
        path:       ApiPath,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val action   = schema.name
      implicit val prettify = params.prettify
      implicit val qc: QueryContext = authzToken.qc

      (for {
        precision <- extractPercentPrecision(req.params)
        targets    = req.params.getOrElse("groups", List.empty).flatMap { nodeGroups =>
                       nodeGroups.split(",").toList.flatMap(parseSimpleTargetOrNodeGroupId(_).toOption)
                     }

        group <- complianceService.getNodeGroupComplianceSummary(targets, precision)
      } yield {
        JArray(group.toList.map {
          case (id, (global, targeted)) =>
            (("id"      -> id) ~
            ("targeted" -> targeted.toJson(1, precision.getOrElse(CompliancePrecision.Level2))) ~
            ("global"   -> global.toJson(1, precision.getOrElse(CompliancePrecision.Level2))))
        })
      }) match {
        case Full(groups) =>
          toJsonResponse(None, ("nodeGroups" -> groups))

        case eb: EmptyBox =>
          val message = (eb ?~ (s"Could not get compliance summary")).messageChain
          toJsonError(None, JString(message))
      }
    }
  }

  object GetNodeGroupId extends LiftApiModule {
    val schema: OneParam = API.GetNodeGroupComplianceId
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        groupId:    String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val action   = schema.name
      implicit val prettify = params.prettify
      implicit val qc: QueryContext = authzToken.qc

      (for {
        level     <- extractComplianceLevel(req.params)
        precision <- extractPercentPrecision(req.params)
        target    <- parseSimpleTargetOrNodeGroupId(groupId).chainError("Could not parse the node group id or group target").toBox
        group     <- complianceService.getNodeGroupCompliance(target, level)
      } yield {
        group.toJson(level.getOrElse(10), precision.getOrElse(CompliancePrecision.Level2))
      }) match {
        case Full(group) =>
          toJsonResponse(None, ("nodeGroups" -> List(group)))

        case eb: EmptyBox =>
          val message = (eb ?~ (s"Could not get compliance for node group '${groupId}'")).messageChain
          toJsonError(None, JString(message))
      }
    }
  }

  object GetNodeGroupTargetId extends LiftApiModule {
    val schema: OneParam = API.GetNodeGroupComplianceTargetId
    def process(
        version:    ApiVersion,
        path:       ApiPath,
        groupId:    String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val action   = schema.name
      implicit val prettify = params.prettify
      implicit val qc: QueryContext = authzToken.qc

      (for {
        level     <- extractComplianceLevel(req.params)
        precision <- extractPercentPrecision(req.params)
        target    <- parseSimpleTargetOrNodeGroupId(groupId).chainError("Could not parse the node group id or group target").toBox
        group     <- complianceService.getNodeGroupCompliance(target, level, isGlobalCompliance = false)
      } yield {
        group.toJson(level.getOrElse(10), precision.getOrElse(CompliancePrecision.Level2))
      }) match {
        case Full(group) =>
          toJsonResponse(None, ("nodeGroups" -> List(group)))

        case eb: EmptyBox =>
          val message = (eb ?~ (s"Could not get targeted compliance for node group '${groupId}'")).messageChain
          toJsonError(None, JString(message))
      }
    }
  }

  object GetNodes extends LiftApiModule0 {
    val schema:                                                                                                API.GetNodesCompliance.type = API.GetNodesCompliance
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse                = {
      implicit val action   = schema.name
      implicit val prettify = params.prettify
      implicit val qc: QueryContext = authzToken.qc

      (for {
        level     <- extractComplianceLevel(req.params)
        precision <- extractPercentPrecision(req.params)
        nodes     <- complianceService.getNodesCompliance(PolicyTypeName.rudderBase).toBox
      } yield {
        if (version.value <= 6) {
          nodes.map(_.toJsonV6)
        } else {
          nodes.map(_.toJson(level.getOrElse(10), precision.getOrElse(CompliancePrecision.Level2)))
        }
      }) match {
        case Full(nodes) =>
          toJsonResponse(None, ("nodes" -> nodes))

        case eb: EmptyBox =>
          val message = (eb ?~ ("Could not get compliances for nodes")).messageChain
          toJsonError(None, JString(message))
      }
    }
  }

  object GetNodeId extends LiftApiModule {
    val schema: OneParam = API.GetNodeComplianceId

    def process(
        version:    ApiVersion,
        path:       ApiPath,
        nodeId:     String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val action   = schema.name
      implicit val prettify = params.prettify
      implicit val qc: QueryContext = authzToken.qc

      (for {
        level     <- extractComplianceLevel(req.params)
        precision <- extractPercentPrecision(req.params)
        node      <- complianceService.getNodeCompliance(NodeId(nodeId), PolicyTypeName.rudderBase).toBox
      } yield {
        if (version.value <= 6) {
          node.toJsonV6
        } else {
          node.toJson(level.getOrElse(10), precision.getOrElse(CompliancePrecision.Level2))
        }
      }) match {
        case Full(node) =>
          toJsonResponse(None, ("nodes" -> List(node)))

        case eb: EmptyBox =>
          val message = (eb ?~ (s"Could not get compliance for node '${nodeId}'")).messageChain
          toJsonError(None, JString(message))
      }
    }
  }

  object GetNodeSystemCompliance extends LiftApiModule {
    val schema: OneParam = API.GetNodeSystemCompliance

    def process(
        version:    ApiVersion,
        path:       ApiPath,
        nodeId:     String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val action   = schema.name
      implicit val prettify = params.prettify
      implicit val qc       = authzToken.qc

      (for {
        level     <- extractComplianceLevel(req.params)
        precision <- extractPercentPrecision(req.params)
        node      <- complianceService.getNodeCompliance(NodeId(nodeId), PolicyTypeName.rudderSystem).toBox
      } yield {
        node.toJson(level.getOrElse(10), precision.getOrElse(CompliancePrecision.Level2))
      }) match {
        case Full(node) =>
          toJsonResponse(None, ("nodes" -> List(node)))

        case eb: EmptyBox =>
          val message = (eb ?~ (s"Could not get compliance for node '${nodeId}'")).messageChain
          toJsonError(None, JString(message))
      }
    }
  }

  object GetGlobal extends LiftApiModule0 {
    val schema:                                                                                                API.GetGlobalCompliance.type = API.GetGlobalCompliance
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse                 = {
      implicit val action   = schema.name
      implicit val prettify = params.prettify
      implicit val qc: QueryContext = authzToken.qc

      (for {
        precision     <- extractPercentPrecision(req.params)
        optCompliance <- complianceService.getGlobalCompliance().toBox
      } yield {
        optCompliance.toJson(precision.getOrElse(CompliancePrecision.Level2))
      }) match {
        case Full(json) =>
          toJsonResponse(None, json)

        case eb: EmptyBox =>
          val message = (eb ?~ (s"Could not get global compliance (for non system rules)")).messageChain
          toJsonError(None, JString(message))
      }
    }
  }

  private[this] def parseSimpleTargetOrNodeGroupId(str: String): PureResult[SimpleTarget] = {
    // attempt to parse a "target" first because format is more specific
    RuleTarget.unserOne(str) match {
      case None        => NodeGroupId.parse(str).map(GroupTarget(_)).left.map(Inconsistency(_))
      case Some(value) => Right(value)
    }
  }
}

/**
 * The class in charge of getting and calculating
 * compliance for all rules/nodes/directives.
 */
class ComplianceAPIService(
    rulesRepo:               RoRuleRepository,
    nodeFactRepos:           NodeFactRepository,
    nodeGroupRepo:           RoNodeGroupRepository,
    reportingService:        ReportingService,
    directiveRepo:           RoDirectiveRepository,
    getGlobalComplianceMode: IOResult[GlobalComplianceMode],
    getGlobalPolicyMode:     IOResult[GlobalPolicyMode]
) {

  private def components(
      nodeFacts:  MapView[NodeId, CoreNodeFact],
      globalMode: GlobalPolicyMode
  )(name: String, nodeComponents: List[(NodeId, ComponentStatusReport)]): List[ByRuleComponentCompliance] = {

    val (groupsComponents, uniqueComponents) = nodeComponents.partitionMap {
      case (a, b: BlockStatusReport) => Left((a, b))
      case (a, b: ValueStatusReport) => Right((a, b))
    }

    (if (groupsComponents.isEmpty) {
       Nil
     } else {
       val bidule = groupsComponents.flatMap { case (nodeId, c) => c.subComponents.map(sub => (nodeId, sub)) }
         .groupBy(_._2.componentName)
       ByRuleBlockCompliance(
         name,
         groupsComponents.map(_._2.reportingLogic).head,
         bidule.flatMap(c => components(nodeFacts, globalMode)(c._1, c._2)).toList
       ) :: Nil
     }) ::: (if (uniqueComponents.isEmpty) {
               Nil
             } else {
               ByRuleValueCompliance(
                 name,
                 ComplianceLevel.sum(
                   uniqueComponents.map(_._2.compliance)
                 ), // here, we finally group by nodes for each components !
                 {
                   val byNode = uniqueComponents.groupBy(_._1)
                   byNode.map {
                     case (nodeId, components) =>
                       val optNodeInfo = nodeFacts.get(nodeId)
                       val policyMode  = ComputePolicyMode.nodeMode(globalMode, optNodeInfo.flatMap(_.rudderSettings.policyMode))
                       ByRuleNodeCompliance(
                         nodeId,
                         optNodeInfo.map(_.fqdn).getOrElse("Unknown node"),
                         policyMode,
                         ComplianceLevel.sum(components.map(_._2.compliance)),
                         components.sortBy(_._2.componentName).flatMap(_._2.componentValues)
                       )
                   }.toList
                 }
               ) :: Nil
             })
  }

  // this method only get userCompliance (system rule not included in rulesRepo.getAll)
  private def getByDirectivesCompliance(
      directives:    Seq[Directive],
      allDirectives: Map[DirectiveId, (FullActiveTechnique, Directive)], // to compute policy mode for each rule
      level:         Option[Int]
  )(implicit qc: QueryContext): IOResult[Seq[ByDirectiveCompliance]] = {
    val computedLevel = level.getOrElse(10)

    for {
      t1            <- currentTimeMillis
      // this can be optimized, as directive only happen for level=2
      rules         <- if (computedLevel >= 2) {
                         rulesRepo.getAll()
                       } else {
                         Seq().succeed
                       }
      ruleIds        = rules.map(_.id).toSet
      t2            <- currentTimeMillis
      _             <- TimingDebugLoggerPure.trace(s"getByDirectivesCompliance - getAllRules in ${t2 - t1} ms")
      nodeFacts     <- nodeFactRepos.getAll()
      t3            <- currentTimeMillis
      _             <- TimingDebugLoggerPure.trace(s"getByDirectivesCompliance - nodeFactRepo.getAll() in ${t3 - t2} ms")
      compliance    <- getGlobalComplianceMode
      t4            <- currentTimeMillis
      _             <- TimingDebugLoggerPure.trace(s"getByDirectivesCompliance - getGlobalComplianceMode in ${t4 - t3} ms")
      reportsByNode <- reportingService
                         .findDirectiveNodeStatusReports(
                           nodeFacts.keySet.toSet,
                           directives.map(_.id).toSet
                         )
      t5            <- currentTimeMillis
      _             <- TimingDebugLoggerPure.trace(s"getByDirectivesCompliance - findRuleNodeStatusReports in ${t5 - t4} ms")

      allGroups <- nodeGroupRepo.getAllNodeIdsChunk()
      t6        <- currentTimeMillis
      _         <- TimingDebugLoggerPure.trace(s"getByDirectivesCompliance - nodeGroupRepo.getAllNodeIdsChunk in ${t6 - t5} ms")

      globalPolicyMode <- getGlobalPolicyMode

      reportsByRule = reportsByNode.flatMap {
                        case (_, status) =>
                          status.reports
                            .get(PolicyTypeName.rudderBase)
                            .map(_.reports)
                            .getOrElse(Set.empty)
                            .filter(r => ruleIds.contains(r.ruleId))
                      }.groupBy(_.ruleId)
      t7           <- currentTimeMillis
      _            <- TimingDebugLoggerPure.trace(s"getByRulesCompliance - group reports by rules in ${t7 - t6} ms")

    } yield {

      for {
        // We will now compute compliance for each directive we want
        directive <- directives
      } yield {
        val policyMode      = ComputePolicyMode.directiveMode(globalPolicyMode, directive.policyMode)
        // We will compute compliance for each rule for the current Directive
        val rulesCompliance = reportsByRule
          .flatMap[ByDirectiveByRuleCompliance] {
            case (ruleId, ruleReports) =>
              // We will filter rules that truly have directive reports
              ruleReports.flatMap(ruleReport => ruleReport.directives.get(directive.id).map(ruleReport -> _)) match {
                case Nil                  => Option.empty[ByDirectiveByRuleCompliance]
                case ruleDirectiveReports =>
                  // We will now gather our report by component for the current Directive
                  val reportsByComponents = (for {
                    ruleDirectiveReport          <- ruleDirectiveReports
                    (ruleReport, directiveReport) = ruleDirectiveReport
                    nodeId                        = ruleReport.nodeId
                    component                    <- directiveReport.components
                  } yield {
                    (nodeId, component)
                  }).groupBy(_._2.componentName).toSeq

                  val componentsCompliance = reportsByComponents.flatMap {
                    case (compName, reports) => components(nodeFacts, globalPolicyMode)(compName, reports.toList)
                  }
                  val componentsDetails    = if (computedLevel <= 3) Seq() else componentsCompliance

                  // if rule cannot be found in "rules" it means the level prevent from returning rule details, so : no compliance
                  rules.find(_.id == ruleId).map { rule =>
                    val nodeIds = RoNodeGroupRepository
                      .getNodeIdsChunk(allGroups, rule.targets, nodeFacts.mapValues(_.rudderSettings.isPolicyServer))
                      .toSet

                    def defaultMode                   = (
                      None,
                      getRulePolicyMode(
                        rule,
                        allDirectives,
                        nodeIds,
                        nodeFacts.mapValues(_.rudderSettings).toMap,
                        globalPolicyMode
                      )
                    )
                    // if all directive on that rules are skipped, the rule is skipped too
                    val (overrideDetails, policyMode) = if (ruleDirectiveReports.forall(_._2.overridden.nonEmpty)) {
                      ruleDirectiveReports.collectFirst {
                        case (_, DirectiveStatusReport(_, _, Some(rid), _)) =>
                          val rname = rules.collectFirst { case r if r.id == rid => r.name }.getOrElse("unknown")
                          (Some(SkippedDetails(rid, rname)), ComputePolicyMode.skippedBy(rid, rname))
                      }.getOrElse(defaultMode)
                    } else defaultMode

                    ByDirectiveByRuleCompliance(
                      ruleId,
                      rule.name,
                      ComplianceLevel.sum(componentsCompliance.map(_.compliance)),
                      overrideDetails,
                      policyMode,
                      componentsDetails
                    )
                  }
              }
          }
          .toSeq

        ByDirectiveCompliance(
          directive.id,
          directive.name,
          ComplianceLevel.sum(rulesCompliance.map(_.compliance)),
          compliance.mode,
          policyMode,
          rulesCompliance
        )
      }
    }
  }

  /**
   * Get the compliance for everything
   * level is optionally the selected level.
   * level 1 includes rules but not directives
   * level 2 includes directives, but not component
   * level 3 includes components, but not nodes
   * level 4 includes the nodes
   */
  private def getByRulesCompliance(rules: Seq[Rule], level: Option[Int])(implicit
      qc: QueryContext
  ): IOResult[Seq[ByRuleRuleCompliance]] = {
    val computedLevel = level.getOrElse(10)

    for {
      t1        <- currentTimeMillis
      allGroups <- nodeGroupRepo.getAllNodeIdsChunk()
      allRules  <- rulesRepo.getAll()
      t2        <- currentTimeMillis
      _         <- TimingDebugLoggerPure.trace(s"getByRulesCompliance - nodeGroupRepo.getAllNodeIdsChunk in ${t2 - t1} ms")

      // this can be optimized, as directive only happen for level=2
      directives    <- if (computedLevel >= 2) {
                         directiveRepo.getFullDirectiveLibrary().map(_.allDirectives)
                       } else {
                         Map[DirectiveId, (FullActiveTechnique, Directive)]().succeed
                       }
      t3            <- currentTimeMillis
      _             <- TimingDebugLoggerPure.trace(s"getByRulesCompliance - getFullDirectiveLibrary in ${t3 - t2} ms")
      nodeFacts     <- nodeFactRepos.getAll()
      t4            <- currentTimeMillis
      _             <- TimingDebugLoggerPure.trace(s"getByRulesCompliance - nodeFactRepo.getAll() in ${t4 - t3} ms")
      compliance    <- getGlobalComplianceMode
      t5            <- currentTimeMillis
      _             <- TimingDebugLoggerPure.trace(s"getByRulesCompliance - getGlobalComplianceMode in ${t5 - t4} ms")
      ruleObjects   <- rules.map { case x => (x.id, x) }.toMap.succeed
      reportsByNode <- reportingService
                         .findRuleNodeStatusReports(
                           nodeFacts.keySet.toSet,
                           ruleObjects.keySet
                         )
      t6            <- currentTimeMillis
      _             <- TimingDebugLoggerPure.trace(s"getByRulesCompliance - findRuleNodeStatusReports in ${t6 - t5} ms")

      reportsByRule = reportsByNode.flatMap { case (_, status) => status.reports.flatMap(_._2.reports) }.groupBy(_.ruleId)
      t7            = System.currentTimeMillis()
      _            <- TimingDebugLoggerPure.trace(s"getByRulesCompliance - group reports by rules in ${t7 - t6} ms")

      t8               <- currentTimeMillis
      _                <- TimingDebugLoggerPure.trace(s"getByRulesCompliance - get directive overrides and rules infos in ${t8 - t7} ms")
      globalPolicyMode <- getGlobalPolicyMode

      nodeAndPolicyModeByRules = rules.map { rule =>
                                   val nodeIds = RoNodeGroupRepository.getNodeIdsChunk(
                                     allGroups,
                                     rule.targets,
                                     nodeFacts.mapValues(_.rudderSettings.isPolicyServer)
                                   )
                                   (
                                     rule.id,
                                     (
                                       nodeIds,
                                       getRulePolicyMode(
                                         rule,
                                         directives,
                                         nodeIds.toSet,
                                         nodeFacts.mapValues(_.rudderSettings).toMap,
                                         globalPolicyMode
                                       )
                                     )
                                   )
                                 }.toMap[RuleId, (Chunk[NodeId], ComputedPolicyMode)]

    } yield {

      // for each rule for each node, we want to have a
      // directiveId -> reporttype map
      val nonEmptyRules = reportsByRule.toSeq.map {
        case (ruleId, reports) =>
          // Rule is top-level so default policy mode is global one if not found (very unlikely)
          val (_, policyMode) =
            nodeAndPolicyModeByRules.get(ruleId).getOrElse((Chunk.empty, ComputePolicyMode.global(globalPolicyMode)))
          // aggregate by directives, if level is at least 2
          val byDirectives: Map[DirectiveId, immutable.Iterable[(NodeId, DirectiveStatusReport)]] = if (computedLevel < 2) {
            Map()
          } else {
            reports.flatMap(r => r.directives.values.map(d => (r.nodeId, d)).toSeq).groupBy(_._2.directiveId)
          }

          ByRuleRuleCompliance(
            ruleId,
            ruleObjects.get(ruleId).map(_.name).getOrElse("Unknown rule"),
            ComplianceLevel.sum(reports.map(_.compliance)),
            compliance.mode,
            policyMode,
            byDirectives.map {
              case (directiveId, nodeDirectives) =>
                val directive     = directives.get(directiveId)
                val nodeModes     = nodeDirectives.map(_._1).flatMap(nodeFacts.get).map(_.rudderSettings.policyMode).toSet
                val overridden    = computeOverridingMode(directiveId, allRules, nodeDirectives)
                val directiveMode = overridden match {
                  case Some(x) => ComputePolicyMode.skippedBy(x.overridingRuleId, x.overridingRuleName)
                  case None    =>
                    ComputePolicyMode.directiveModeOnRule(nodeModes, globalPolicyMode)(directive.flatMap(_._2.policyMode))
                }
                ByRuleDirectiveCompliance(
                  directiveId,
                  directive.map(_._2.name).getOrElse("Unknown directive"),
                  ComplianceLevel.sum(
                    nodeDirectives.map(_._2.compliance)
                  ),
                  overridden,
                  directiveMode, {
                    // here we want the compliance by components of the directive.
                    // if level is high enough, get all components and group by their name
                    val byComponents: Map[String, immutable.Iterable[(NodeId, ComponentStatusReport)]] = if (computedLevel < 3) {
                      Map()
                    } else {
                      nodeDirectives.flatMap { case (nodeId, d) => d.components.map(c => (nodeId, c)).toSeq }
                        .groupBy(_._2.componentName)
                    }
                    byComponents.flatMap {
                      case (name, nodeComponents) => components(nodeFacts, globalPolicyMode)(name, nodeComponents.toList)
                    }.toSeq
                  }
                )
            }.toSeq
          )
      }
      val t8            = System.currentTimeMillis()
      TimingDebugLoggerPure.logEffect.trace(s"getByRulesCompliance - Compute non empty rules in ${t8 - t7} ms")

      // if any rules is in the list in parameter and not in the nonEmptyRules, then it means
      // there's no compliance for it, so it's empty
      // we need to set the ByRuleCompliance with a compliance of NoAnswer
      val rulesWithoutCompliance = ruleObjects.keySet -- reportsByRule.keySet

      val initializedCompliances: Seq[ByRuleRuleCompliance] = {
        if (rulesWithoutCompliance.isEmpty) {
          Seq[ByRuleRuleCompliance]()
        } else {
          rulesWithoutCompliance.toSeq.map {
            case ruleId =>
              val rule                  = ruleObjects(ruleId) // we know by construct that it exists
              val (nodeIds, policyMode) =
                nodeAndPolicyModeByRules.getOrElse(ruleId, (Chunk.empty, ComputePolicyMode.global(globalPolicyMode)))
              ByRuleRuleCompliance(
                rule.id,
                rule.name,
                ComplianceLevel(noAnswer = nodeIds.size),
                compliance.mode,
                policyMode,
                Seq()
              )
          }
        }
      }

      val t9 = System.currentTimeMillis()
      TimingDebugLoggerPure.logEffect.trace(
        s"getByRulesCompliance - Compute ${initializedCompliances.size} empty rules in ${t9 - t8} ms"
      )

      // return the full list
      val singleRuleCompliance = nonEmptyRules ++ initializedCompliances

      val t10 = System.currentTimeMillis()
      TimingDebugLoggerPure.logEffect.trace(s"getByRulesCompliance - Compute result in ${t10 - t9} ms")
      singleRuleCompliance
    }
  }

  /*
   * For a set of directive status reports for the same directiveId, compute an aggregated "SkippedDetails" value.
   * The rule is that you put it only if it's one ALL nodeId (meaning they are likely from the same target, and so
   * the directive is skipped everywhere).
   */
  def computeOverridingMode(
      id1:      DirectiveId,
      allRules: Iterable[Rule],
      reports:  Iterable[(NodeId, DirectiveStatusReport)]
  ): Option[SkippedDetails] = {
    if (reports.forall(_._2.overridden.isDefined)) {
      // here, we COULD keep the list of all overriding rules/directives, but it's not don't
      // to avoid risking duplicating "skipped" instance.
      reports.collectFirst {
        case (_, DirectiveStatusReport(id2, _, Some(ruleId), _)) if (id1 == id2) =>
          SkippedDetails(ruleId, allRules.collectFirst { case r if r.id == ruleId => r.name }.getOrElse("unknown"))
      }
    } else None
  }

  private def getByNodeGroupCompliance(
      nodeGroupComplianceId: String,
      nodeGroupName:         String,
      serverList:            Set[NodeId],
      allDirectives:         Map[DirectiveId, (FullActiveTechnique, Directive)],
      nodeFacts:             MapView[NodeId, CoreNodeFact],
      nodeSettings:          Map[NodeId, RudderSettings],
      rules:                 Map[RuleId, (Rule, Chunk[NodeId], ComputedPolicyMode)],
      level:                 Option[Int],
      isGlobalCompliance:    Boolean
  )(implicit qc: QueryContext): IOResult[ByNodeGroupCompliance] = {
    for {
      compliance <- getGlobalComplianceMode
      globalMode <- getGlobalPolicyMode

      currentGroupNodeIds = serverList

      t1            <- currentTimeMillis
      reportsByNode <- reportingService
                         .findRuleNodeStatusReports(
                           nodeSettings.keySet,
                           rules.keySet
                         )
      t2            <- currentTimeMillis
      _             <- TimingDebugLoggerPure.trace(s"getByNodeGroupCompliance - findRuleNodeStatusReports in ${t2 - t1} ms")

      reportsByRule = reportsByNode.flatMap {
                        case (_, status) =>
                          // TODO: separate reports that have 'overridden policies' here (skipped)
                          status.reports
                            .get(PolicyTypeName.rudderBase)
                            .map(_.reports)
                            .getOrElse(Set.empty)
                            .filter(r =>
                              (isGlobalCompliance || rules.keySet.contains(r.ruleId)) && currentGroupNodeIds.contains(r.nodeId)
                            )
                      }.groupBy(_.ruleId)
      //
      t3           <- currentTimeMillis
      _            <- TimingDebugLoggerPure.trace(s"getByNodeGroupCompliance - group reports by rules in ${t3 - t2} ms")

    } yield {
      val computedLevel = level.getOrElse(10)

      // Same as 'getByRuleCompliance' implementation, but nodes in the group have been prefiltered, and the result is a list of ByNodeGroupRuleCompliance
      val nonEmptyRules = reportsByRule.toSeq.flatMap {
        case (ruleId, reports) =>
          // aggregate by directives, if level is at least 2
          val byDirectives: Map[DirectiveId, immutable.Iterable[(NodeId, DirectiveStatusReport)]] = if (computedLevel < 2) {
            Map()
          } else {
            reports.flatMap(r => r.directives.values.map(d => (r.nodeId, d)).toSeq).groupBy(_._2.directiveId)
          }

          rules.get(ruleId) match {
            case None               => None
            case Some((r, _, mode)) =>
              Some(
                ByNodeGroupRuleCompliance(
                  ruleId,
                  r.name,
                  ComplianceLevel.sum(reports.map(_.compliance)),
                  mode,
                  byDirectives.map {
                    case (directiveId, nodeDirectives) =>
                      val directive                     = allDirectives.get(directiveId).map(_._2)
                      def defaultMode                   = (
                        None,
                        ComputePolicyMode.directiveModeOnRule(
                          nodeDirectives.map(_._1).toSet.map((n: NodeId) => nodeSettings.get(n).flatMap(_.policyMode)),
                          globalMode
                        )(
                          directive.flatMap(_.policyMode)
                        )
                      )
                      val (overrideDetails, policyMode) = if (nodeDirectives.forall(_._2.overridden.nonEmpty)) {
                        nodeDirectives.collectFirst {
                          case (_, DirectiveStatusReport(_, _, Some(rid), _)) =>
                            val rname = rules.get(rid).map(_._1.name)
                            (
                              Some(SkippedDetails(rid, rname.getOrElse("unknown"))),
                              ComputePolicyMode.skippedBy(rid, r.name)
                            )
                        }.getOrElse(defaultMode)
                      } else defaultMode

                      ByNodeGroupByRuleDirectiveCompliance(
                        directiveId,
                        directive.map(_.name).getOrElse("Unknown directive"),
                        ComplianceLevel.sum(nodeDirectives.map(_._2.compliance)),
                        overrideDetails,
                        policyMode,
                        // here we want the compliance by components of the directive.
                        // if level is high enough, get all components and group by their name
                        {
                          val byComponents: Map[String, immutable.Iterable[(NodeId, ComponentStatusReport)]] = {
                            if (computedLevel < 3) {
                              Map()
                            } else {
                              nodeDirectives.flatMap { case (nodeId, d) => d.components.map(c => (nodeId, c)).toSeq }
                                .groupBy(_._2.componentName)
                            }
                          }
                          byComponents.flatMap {
                            case (name, nodeComponents) => components(nodeFacts, globalMode)(name, nodeComponents.toList)
                          }.toSeq
                        }
                      )
                  }.toSeq
                )
              )
          }
      }
      val t3            = System.currentTimeMillis()
      TimingDebugLoggerPure.logEffect.trace(s"getByRulesCompliance - Compute non empty rules in ${t3 - t2} ms")

      // if any rules is in the list in parameter an not in the nonEmptyRules, then it means
      // there's no compliance for it, so it's empty
      // we need to set the ByRuleCompliance with a compliance of NoAnswer
      val rulesWithoutCompliance = rules.keySet -- reportsByRule.keySet

      val initializedCompliances: Seq[ByNodeGroupRuleCompliance] = {
        rulesWithoutCompliance.toSeq.map { ruleId =>
          val (rule, nodeIds, policyMode) = rules(ruleId) // we know by construct that it exists
          ByNodeGroupRuleCompliance(
            rule.id,
            rule.name,
            ComplianceLevel(noAnswer = nodeIds.size),
            policyMode,
            Seq.empty
          )
        }
      }

      val t4 = System.currentTimeMillis()
      TimingDebugLoggerPure.logEffect.trace(
        s"getByNodeGroupCompliance - Compute ${initializedCompliances.size} empty rules in ${t4 - t3} ms"
      )

      // return the full list
      val byRuleCompliance = nonEmptyRules ++ initializedCompliances

      val t5 = System.currentTimeMillis()
      TimingDebugLoggerPure.logEffect.trace(s"getByNodeGroupCompliance - Compute result in ${t5 - t4} ms")

      val byNodeCompliance = reportsByNode.toList.collect {
        case (nodeId, status) if currentGroupNodeIds.contains(nodeId) =>
          // For non global compliance, we only want the compliance for the rules in the group
          val reports        = status.reports
            .get(PolicyTypeName.rudderBase)
            .map(_.reports)
            .getOrElse(Set.empty)
            .filter(r => isGlobalCompliance || rules.keySet.contains(r.ruleId))
            .toSeq
            .sortBy(_.ruleId.serialize)
          val nodePolicyMode = nodeFacts.get(nodeId).flatMap(_.rudderSettings.policyMode)
          val nodeMode       = ComputePolicyMode.nodeMode(globalMode, nodePolicyMode)
          ByNodeGroupNodeCompliance(
            nodeId,
            nodeFacts.get(nodeId).map(_.fqdn).getOrElse("Unknown node"),
            compliance.mode,
            ComplianceLevel.sum(reports.map(_.compliance)),
            nodeMode,
            reports.map(r => {
              val directives = r.directives.toSeq.map {
                case (_, directiveReport) =>
                  val d      = allDirectives.get(directiveReport.directiveId)
                  val (p, o) = directiveReport.overridden match {
                    case Some(overridingRuleId) =>
                      val ruleName = rules.get(overridingRuleId).map(_._1.name).getOrElse("unknown")
                      (
                        ComputePolicyMode.skippedBy(overridingRuleId, ruleName),
                        Some(SkippedDetails(overridingRuleId, ruleName))
                      )
                    case None                   =>
                      (
                        ComputePolicyMode
                          .directiveModeOnNode(nodePolicyMode, globalMode)(
                            d.flatMap(_._2.policyMode)
                          ),
                        None
                      )
                  }
                  ByNodeDirectiveCompliance(
                    directiveReport.directiveId,
                    d.map(_._2.name).getOrElse("Unknown Directive"),
                    directiveReport.compliance,
                    o,
                    p,
                    directiveReport.components
                  )
              }
              ByNodeRuleCompliance(
                r.ruleId,
                rules.get(r.ruleId).map(_._1.name).getOrElse("Unknown rule"),
                r.compliance,
                if (directives.forall(_.policyMode.isSkipped)) ComputePolicyMode.skipped(s"All directives on rule are skipped")
                else
                  ComputePolicyMode
                    .ruleModeOnNode(nodePolicyMode, globalMode)(
                      r.directives.flatMap(d => allDirectives.get(d._1).map(_._2.policyMode)).toSet
                    ),
                directives
              )
            })
          )
      }
      ByNodeGroupCompliance(
        nodeGroupComplianceId,
        nodeGroupName,
        ComplianceLevel.sum(byNodeCompliance.map(_.compliance)),
        compliance.mode,
        byRuleCompliance.sortBy(_.id.serialize),
        byNodeCompliance.sortBy(_.id.value)
      )
    }
  }

  def getRuleCompliance(ruleId: RuleId, level: Option[Int])(implicit qc: QueryContext): Box[ByRuleRuleCompliance] = {
    for {
      rule    <- rulesRepo.get(ruleId)
      reports <- getByRulesCompliance(Seq(rule), level)
      report  <- reports.find(_.id == ruleId).notOptional(s"No reports were found for rule with ID '${ruleId.serialize}'")
    } yield {
      report
    }
  }.toBox

  def getDirectiveCompliance(directive: Directive, level: Option[Int])(implicit qc: QueryContext): Box[ByDirectiveCompliance] = {
    for {
      directiveLib <- directiveRepo.getFullDirectiveLibrary()
      reports      <- getByDirectivesCompliance(Seq(directive), directiveLib.allDirectives, level)
      report       <-
        reports.find(_.id == directive.id).notOptional(s"No reports were found for directive with ID '${directive.id.serialize}'")
    } yield {
      report
    }
  }.toBox

  def getNodeGroupCompliance(
      target:             SimpleTarget,
      level:              Option[Int],
      isGlobalCompliance: Boolean = true
  )(implicit qc: QueryContext): Box[ByNodeGroupCompliance] = {
    for {
      t1          <- currentTimeMillis
      nodeFacts   <- nodeFactRepos.getAll()
      nodeSettings = nodeFacts.mapValues(_.rudderSettings).toMap
      t2          <- currentTimeMillis
      _           <- TimingDebugLoggerPure.trace(s"getByNodeGroupCompliance - nodeFactRepo.getAll() in ${t2 - t1} ms")

      directiveLib <- directiveRepo.getFullDirectiveLibrary()
      t3           <- currentTimeMillis
      _            <- TimingDebugLoggerPure.trace(s"getByNodeGroupCompliance - getFullDirectiveLibrary in ${t3 - t2} ms")

      groupInfo <-
        target match {
          case GroupTarget(nodeGroupId) =>
            nodeGroupRepo
              .getNodeGroupOpt(nodeGroupId)
              .notOptional(s"Node group with id '${nodeGroupId.serialize}' not found'")
              .map(g => (g._1.id.serialize, g._1.name, g._1.serverList))
          case t: NonGroupRuleTarget =>
            nodeGroupRepo
              .getGroupCategory(NodeGroupCategoryId("SystemGroups"))
              .map(g => g.items.find(_.target == target).map(i => (i.target.target, i.name, targetServerList(t)(nodeSettings))))
              .notOptional(s"Unexpected error which is likely a programming error, system group target should be in SystemGroups")
        }

      (nodeGroupComplianceId, nodeGroupName, serverList) = groupInfo

      t4 <- currentTimeMillis
      _  <- TimingDebugLoggerPure.trace(s"getByNodeGroupCompliance - nodeGroupRepo.getNodeGroupOpt in ${t4 - t3} ms")

      compliance <- getGlobalComplianceMode
      t5         <- currentTimeMillis
      _          <- TimingDebugLoggerPure.trace(s"getByNodeGroupCompliance - getGlobalComplianceMode in ${t5 - t4} ms")

      // this only user group, we must be consistent and never ask for system group anywhere else
      rules <- rulesRepo.getAll()
      t6    <- currentTimeMillis
      _     <- TimingDebugLoggerPure.trace(s"getByNodeGroupCompliance - getAllRules in ${t6 - t5} ms")

      allGroups <- nodeGroupRepo.getAllNodeIdsChunk()
      t7        <- currentTimeMillis
      _         <- TimingDebugLoggerPure.trace(s"getByNodeGroupCompliance - nodeGroupRepo.getAllNodeIdsChunk in ${t7 - t6} ms")

      globalPolicyMode <- getGlobalPolicyMode

      // A map for constant time access for the set of targeted nodes and policy mode:  a Map[RuleId, (Chunk[NodeId], Option[PolicyMode])]
      // The set is reused to directly compute the policy mode of the rule
      allRuleInfos = {
        rules
          .map(r => {
            val targetedNodeIds =
              RoNodeGroupRepository.getNodeIdsChunk(allGroups, r.targets, nodeSettings.view.mapValues(_.isPolicyServer))
            val policyMode      =
              getRulePolicyMode(r, directiveLib.allDirectives, targetedNodeIds.toSet, nodeSettings, globalPolicyMode)
            (r.id, (targetedNodeIds, policyMode))
          })
          .toMap
      }

      filteredRules = rules.flatMap { rule =>
                        // we must filter out rule not in allRuleInfo, else latter on, we will try to get info on them and get https://issues.rudder.io/issues/24945
                        allRuleInfos.get(rule.id) match {
                          case None                        => None
                          case Some((nodeIds, policyMode)) =>
                            val targetedNodeIds = {
                              // TODO: allGroups CAN be empty when parsed is "non-group target". We don't even need the call to getAllNodeIdsChunk()
                              RoNodeGroupRepository.getNodeIdsChunk(
                                allGroups,
                                rule.targets,
                                nodeSettings.view.mapValues(_.isPolicyServer)
                              )
                            }

                            val isRuleTargetingGroup =
                              RuleTarget.merge(rule.targets).includes(target)

                            if ((isGlobalCompliance || isRuleTargetingGroup) && targetedNodeIds.exists(serverList.contains))
                              Some((rule.id, (rule, nodeIds, policyMode)))
                            else None
                        }
                      }.toMap[RuleId, (Rule, Chunk[NodeId], ComputedPolicyMode)]

      t8 <- currentTimeMillis
      _  <- TimingDebugLoggerPure.trace(s"getByNodeGroupCompliance - filter rules in ${t8 - t7} ms")

      res <-
        getByNodeGroupCompliance(
          nodeGroupComplianceId,
          nodeGroupName,
          serverList,
          directiveLib.allDirectives,
          nodeFacts,
          nodeSettings,
          filteredRules,
          level,
          isGlobalCompliance
        )

    } yield {
      res
    }
  }.toBox

  /**
   * Get global and targeted compliance at level 1 (without any details) with global compliance at left and targeted at right
   */
  def getNodeGroupComplianceSummary(
      targets:   Seq[SimpleTarget],
      precision: Option[CompliancePrecision]
  )(implicit qc: QueryContext): Box[Map[String, (ByNodeGroupCompliance, ByNodeGroupCompliance)]] = {
    // container class to hold information for global targets
    final case class GlobalTargetInfo(
        name:                 String,
        nodeIds:              Set[NodeId],
        globalRulesByGroup:   Map[RuleId, (Rule, Chunk[NodeId], ComputedPolicyMode)],
        targetedRulesByGroup: Map[RuleId, (Rule, Chunk[NodeId], ComputedPolicyMode)]
    )

    {
      for {
        t1          <- currentTimeMillis
        nodeFacts   <- nodeFactRepos.getAll()
        nodeSettings = nodeFacts.mapValues(_.rudderSettings).toMap
        t2          <- currentTimeMillis
        _           <- TimingDebugLoggerPure.trace(s"getByNodeGroupCompliance - nodeFactRepo.getAll() in ${t2 - t1} ms")

        directiveLib <- directiveRepo.getFullDirectiveLibrary()
        t3           <- currentTimeMillis
        _            <- TimingDebugLoggerPure.trace(s"getByNodeGroupCompliance - getFullDirectiveLibrary in ${t3 - t2} ms")

        (nonGroupTargets, nodeGroupIds) = targets.partitionMap {
                                            case GroupTarget(groupId) => Right(groupId)
                                            case t: NonGroupRuleTarget => Left(t)
                                          }
        nodeGroupsInfo                 <- {
          for {
            nodeGroups    <- nodeGroupRepo.getAllByIds(nodeGroupIds)
            nodeGroupInfos = nodeGroups.map(g => g.id.serialize -> (g.name, g.serverList, GroupTarget(g.id))).toMap

            systemCategory <- nodeGroupRepo.getGroupCategory(NodeGroupCategoryId("SystemGroups"))
            targetsInfos    = {
              nonGroupTargets
                .flatMap(t => {
                  systemCategory.items
                    .find(_.target == t)
                    .map(i => i.target.target -> (i.name, targetServerList(t)(nodeSettings), t))
                })
                .toMap
            }
          } yield nodeGroupInfos ++ targetsInfos
        }
        t4                             <- currentTimeMillis
        _                              <- TimingDebugLoggerPure.trace(s"getByNodeGroupCompliance - nodeGroupRepo.getAllByIds in ${t4 - t3} ms")

        t5 <- currentTimeMillis
        _  <- TimingDebugLoggerPure.trace(s"getByNodeGroupCompliance - getGlobalComplianceMode in ${t5 - t4} ms")

        rules <- rulesRepo.getAll()
        t6    <- currentTimeMillis
        _     <- TimingDebugLoggerPure.trace(s"getByNodeGroupCompliance - getAllRules in ${t6 - t5} ms")

        allGroups <- nodeGroupRepo.getAllNodeIdsChunk()
        t7        <- currentTimeMillis
        _         <- TimingDebugLoggerPure.trace(s"getByNodeGroupCompliance - nodeGroupRepo.getAllNodeIdsChunk in ${t7 - t6} ms")

        globalPolicyMode <- getGlobalPolicyMode

        // A map for constant time access for the set of targeted nodes and policy mode:  a Map[RuleId, (Chunk[NodeId], Option[PolicyMode])]
        // The set is reused to directly compute the policy mode of the rule
        allRuleInfos      = {
          rules
            .map(r => {
              val targetedNodeIds = {
                RoNodeGroupRepository.getNodeIdsChunk(
                  allGroups,
                  r.targets,
                  nodeFacts.mapValues(_.rudderSettings.isPolicyServer)
                )
              }
              val policyMode      =
                getRulePolicyMode(r, directiveLib.allDirectives, targetedNodeIds.toSet, nodeSettings, globalPolicyMode)

              (r.id, (targetedNodeIds, policyMode))
            })
            .toMap[RuleId, (Chunk[NodeId], ComputedPolicyMode)]
        }

        // global compliance : filter our rules that are applicable to any node in this group
        globalTargetInfos = nodeGroupsInfo.map {
                              case (g, (name, serverList, _)) =>
                                val globalRulesByGroup   = rules.flatMap { rule =>
                                  allRuleInfos.get(rule.id) match {
                                    case None                        => None
                                    case Some((nodeIds, policyMode)) =>
                                      if (nodeIds.exists(serverList.contains)) Some((rule.id, (rule, nodeIds, policyMode)))
                                      else None
                                  }
                                }.toMap[RuleId, (Rule, Chunk[NodeId], ComputedPolicyMode)]
                                // targeted compliance : filter rules that only include this group in its targets
                                val targetedRulesByGroup = globalRulesByGroup.filter {
                                  case (_, (rule, _, _)) => RuleTarget.merge(rule.targets).includes(nodeGroupsInfo(g)._3)
                                }

                                (g, GlobalTargetInfo(name, serverList, globalRulesByGroup, targetedRulesByGroup))
                            }

        level = Some(1)

        bothGlobalTargeted <-
          ZIO.foreach(globalTargetInfos) {
            case (id, info) =>
              (
                getByNodeGroupCompliance(
                  id,
                  info.name,
                  info.nodeIds,
                  directiveLib.allDirectives,
                  nodeFacts,
                  nodeSettings,
                  info.globalRulesByGroup,
                  level,
                  isGlobalCompliance = true
                ) <&> getByNodeGroupCompliance(
                  id,
                  info.name,
                  info.nodeIds,
                  directiveLib.allDirectives,
                  nodeFacts,
                  nodeSettings,
                  info.targetedRulesByGroup,
                  level,
                  isGlobalCompliance = false
                )
              ).map(
                (id, _)
              )
          }
      } yield {
        bothGlobalTargeted.toMap
      }
    }.toBox
  }

  def getDirectivesCompliance(level: Option[Int])(implicit qc: QueryContext): Box[Seq[ByDirectiveCompliance]] = {
    for {
      directiveLib <- directiveRepo.getFullDirectiveLibrary()
      directives    = directiveLib.allDirectives.values.map(_._2).toSeq
      reports      <- getByDirectivesCompliance(directives, directiveLib.allDirectives, level)
    } yield {
      reports
    }
  }.toBox

  def getRulesCompliance(level: Option[Int])(implicit qc: QueryContext): Box[Seq[ByRuleRuleCompliance]] = {
    getRulesCompliancePure(level).toBox
  }

  def getRulesCompliancePure(level: Option[Int])(implicit qc: QueryContext): IOResult[Seq[ByRuleRuleCompliance]] = {
    for {
      rules   <- rulesRepo.getAll()
      reports <- getByRulesCompliance(rules, level)
    } yield {
      reports
    }
  }

  /**
   * Get the compliance for everything
   */
  private def getSystemRules()  = {
    for {
      allRules  <- rulesRepo.getAll(true)
      userRules <- rulesRepo.getAll()
    } yield {
      allRules.diff(userRules)
    }
  }
  private def getAllUserRules() = {
    rulesRepo.getAll()
  }
  private def getByNodesCompliance(
      onlyNode:   Option[NodeId],
      policyType: PolicyTypeName
  )(implicit qc: QueryContext): IOResult[Seq[ByNodeNodeCompliance]] = {
    for {
      rules        <- if (PolicyTypeName.rudderSystem == policyType) getSystemRules() else getAllUserRules()
      ruleMap       = rules.map { case x => (x.id, x) }.toMap
      allGroups    <- nodeGroupRepo.getAllNodeIdsChunk()
      directiveLib <- directiveRepo.getFullDirectiveLibrary().map(_.allDirectives)
      allNodeFacts <- nodeFactRepos.getAll()
      nodeFacts    <- onlyNode match {
                        case None     => allNodeFacts.succeed
                        case Some(id) =>
                          allNodeFacts
                            .get(id)
                            .map(info => MapView(id -> info))
                            .notOptional(s"The node with ID '${id.value}' is not known on Rudder")
                      }
      globalMode   <- getGlobalPolicyMode
      compliance   <- getGlobalComplianceMode
      reports      <- reportingService.findRuleNodeStatusReports(nodeFacts.keySet.toSet, ruleMap.keySet)

    } yield {
      // A map to access the node fqdn and settings
      val nodeInfos: Map[NodeId, (String, RudderSettings)] =
        nodeFacts.view.mapValues(info => (info.fqdn, info.rudderSettings)).toMap

      val nodeAndPolicyModeByRules = rules
        .map(rule => {
          val nodeIds    =
            RoNodeGroupRepository.getNodeIdsChunk(allGroups, rule.targets, nodeInfos.view.mapValues(_._2.isPolicyServer)).toSet
          val policyMode = {
            getRulePolicyMode(
              rule,
              directiveLib,
              nodeIds,
              nodeInfos.map { case (id, (_, settings)) => (id, settings) },
              globalMode
            )
          }
          (rule, nodeIds -> policyMode)
        })
        .toMap
      // get an empty-initialized array of compliances to be used
      // as defaults

      val initializedCompliances: Map[NodeId, ByNodeNodeCompliance] = {
        nodeInfos.map {
          case (nodeId, (fqdn, nodeSettings)) =>
            val rulesForNode   = nodeAndPolicyModeByRules.collect {
              case (rule, (nodeIds, policyMode)) if (nodeIds.contains(nodeId)) => (rule, policyMode)
            }.toList
            val nodePolicyMode = ComputePolicyMode.nodeMode(globalMode, nodeSettings.policyMode)

            (
              nodeId,
              ByNodeNodeCompliance(
                nodeId,
                fqdn,
                ComplianceLevel(noAnswer = rulesForNode.size),
                compliance.mode,
                nodePolicyMode,
                (rulesForNode.map {
                  case (rule, policyMode) =>
                    ByNodeRuleCompliance(
                      rule.id,
                      rule.name,
                      ComplianceLevel(noAnswer = rule.directiveIds.size),
                      policyMode,
                      rule.directiveIds.map { id =>
                        val directive     = directiveLib.get(id)
                        val directiveMode = {
                          ComputePolicyMode.directiveModeOnNode(nodeSettings.policyMode, globalMode)(
                            directive.flatMap(_._2.policyMode)
                          )
                        }

                        ByNodeDirectiveCompliance(
                          id,
                          directive.map(_._2.name).getOrElse("Unknown Directive"),
                          ComplianceLevel(noAnswer = 1),
                          None,
                          directiveMode,
                          Nil
                        )
                      }.toSeq
                    )
                })
              )
            )
        }.toMap
      }

      // for each rule for each node, we want to have a
      // directiveId -> reporttype map
      val nonEmptyNodes = reports.map {
        case (nodeId, status) =>
          val nodeSettings   = nodeInfos.get(nodeId).map { case (_, settings) => settings }
          val nodePolicyMode = ComputePolicyMode.nodeMode(globalMode, nodeSettings.flatMap(_.policyMode))
          (
            nodeId,
            ByNodeNodeCompliance(
              nodeId,
              nodeInfos.get(nodeId).map(_._1).getOrElse("Unknown node"),
              ComplianceLevel.sum(status.reports.map(_._2.compliance)),
              compliance.mode, // Add this line to include no
              nodePolicyMode,
              status.reports
                .get(policyType)
                .toSeq
                .flatMap(_.reports)
                .flatMap(r => ruleMap.get(r.ruleId).map(r -> _))
                .map {
                  case (r, rule) =>
                    val (_, rulePolicyMode) = {
                      nodeAndPolicyModeByRules
                        .get(rule)
                        .getOrElse(
                          (
                            Chunk.empty,
                            ComputePolicyMode
                              .ruleModeOnNode(nodeSettings.flatMap(_.policyMode), globalMode)(
                                rule.directiveIds.map(directiveLib.get(_).flatMap(_._2.policyMode))
                              )
                          )
                        )
                    }
                    ByNodeRuleCompliance(
                      r.ruleId,
                      rule.name,
                      r.compliance,
                      rulePolicyMode,
                      r.directives.toSeq.map {
                        case (_, directiveReport) =>
                          val (p, o) = directiveReport.overridden match {
                            case Some(overridingRuleId) =>
                              val ruleName = ruleMap.get(overridingRuleId).map(_.name).getOrElse("unknown")
                              (
                                ComputePolicyMode.skippedBy(overridingRuleId, ruleName),
                                Some(SkippedDetails(overridingRuleId, ruleName))
                              )
                            case None                   =>
                              (
                                ComputePolicyMode
                                  .directiveModeOnNode(nodeInfos.get(nodeId).flatMap(_._2.policyMode), globalMode)(
                                    directiveLib.get(directiveReport.directiveId).flatMap(_._2.policyMode)
                                  ),
                                None
                              )
                          }
                          ByNodeDirectiveCompliance(
                            directiveReport.directiveId,
                            directiveLib.get(directiveReport.directiveId).map(_._2.name).getOrElse("Unknown Directive"),
                            directiveReport.compliance,
                            o,
                            p,
                            directiveReport.components
                          )
                      }
                    )
                }
            )
          )
      }

      // return the full list, even for non responding nodes/directives
      // but override with values when available.
      (initializedCompliances ++ nonEmptyNodes).values.toSeq

    }
  }

  private def getRulePolicyMode(
      rule:          Rule,
      allDirectives: Map[DirectiveId, (FullActiveTechnique, Directive)],
      nodesIds:      Set[NodeId],
      nodeSettings:  Map[NodeId, RudderSettings],
      globalMode:    GlobalPolicyMode
  ): ComputedPolicyMode = {
    val directives = rule.directiveIds.flatMap(allDirectives.get(_)).map(_._2)
    val nodeModes  = nodeSettings.collect { case (id, rudderSettings) if (nodesIds.contains(id)) => rudderSettings.policyMode }
    ComputePolicyMode.ruleMode(globalMode, directives, nodeModes)
  }

  def getNodeCompliance(nodeId: NodeId, policyTypeName: PolicyTypeName)(implicit
      qc: QueryContext
  ): IOResult[ByNodeNodeCompliance] = {
    for {
      reports <- this.getByNodesCompliance(Some(nodeId), policyTypeName)
      report  <- reports.find(_.id == nodeId).notOptional(s"No reports were found for node with ID '${nodeId.value}'")
    } yield {
      report
    }
  }

  def getNodesCompliance(policyTypeName: PolicyTypeName)(implicit qc: QueryContext): IOResult[Seq[ByNodeNodeCompliance]] = {
    this.getByNodesCompliance(None, policyTypeName)
  }

  def getGlobalCompliance()(implicit qc: QueryContext): IOResult[Option[(ComplianceLevel, Long)]] = {
    this.reportingService.getGlobalUserCompliance()
  }

  private[this] def targetServerList(target: NonGroupRuleTarget)(nodeSettings: Map[NodeId, RudderSettings]) = {

    target match {
      case AllTarget                    => nodeSettings.keySet
      case AllPolicyServers             =>
        nodeSettings.filter(_._2.isPolicyServer).keySet
      case AllTargetExceptPolicyServers =>
        nodeSettings.filter(!_._2.isPolicyServer).keySet
      case PolicyServerTarget(nodeId)   => Set(nodeId)
    }
  }
}
