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
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.domain.policies.Directive
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.Rule
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.reports.BlockStatusReport
import com.normation.rudder.domain.reports.ComplianceLevel
import com.normation.rudder.domain.reports.CompliancePrecision
import com.normation.rudder.domain.reports.ComponentStatusReport
import com.normation.rudder.domain.reports.DirectiveStatusReport
import com.normation.rudder.domain.reports.ValueStatusReport
import com.normation.rudder.reports.GlobalComplianceMode
import com.normation.rudder.repository.FullActiveTechnique
import com.normation.rudder.repository.RoDirectiveRepository
import com.normation.rudder.repository.RoNodeGroupRepository
import com.normation.rudder.repository.RoRuleRepository
import com.normation.rudder.rest.*
import com.normation.rudder.rest.ComplianceApi as API
import com.normation.rudder.rest.RestExtractorService
import com.normation.rudder.rest.RestUtils.*
import com.normation.rudder.rest.data.*
import com.normation.rudder.services.nodes.NodeInfoService
import com.normation.rudder.services.reports.ReportingService
import com.normation.zio.currentTimeMillis
import net.liftweb.common.*
import net.liftweb.http.LiftResponse
import net.liftweb.http.PlainTextResponse
import net.liftweb.http.Req
import net.liftweb.json.*
import net.liftweb.json.JsonDSL.*
import scala.collection.immutable
import zio.syntax.*

class ComplianceApi(
    restExtractorService: RestExtractorService,
    complianceService:    ComplianceAPIService,
    readDirective:        RoDirectiveRepository
) extends LiftApiModuleProvider[API] {

  import CsvCompliance.*
  import JsonCompliance.*

  def schemas: ApiModuleProvider[API] = API

  /*
   * The actual builder for the compliance API.
   * Depends of authz method and supported version.
   *
   * It's quite verbose, but it's the only way I found to
   * get the exhaustivity check and be sure that ALL
   * endpoints are processed.
   */
  def getLiftEndpoints(): List[LiftApiModule] = {
    API.endpoints
      .map(e => {
        e match {
          case API.GetRulesCompliance       => GetRules
          case API.GetRulesComplianceId     => GetRuleId
          case API.GetNodesCompliance       => GetNodes
          case API.GetNodeComplianceId      => GetNodeId
          case API.GetGlobalCompliance      => GetGlobal
          case API.GetDirectiveComplianceId => GetDirectiveId
          case API.GetDirectivesCompliance  => GetDirectives
        }
      })
      .toList
  }

  object GetRules extends LiftApiModule0 {
    val schema: API.GetRulesCompliance.type = API.GetRulesCompliance
    val restExtractor = restExtractorService
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      implicit val action   = schema.name
      implicit val prettify = params.prettify

      (for {
        level        <- restExtractor.extractComplianceLevel(req.params)
        precision    <- restExtractor.extractPercentPrecision(req.params)
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
    val restExtractor = restExtractorService
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

      (for {
        level     <- restExtractor.extractComplianceLevel(req.params)
        t1         = System.currentTimeMillis
        precision <- restExtractor.extractPercentPrecision(req.params)
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
    val schema:        API.GetDirectiveComplianceId.type = API.GetDirectiveComplianceId
    val restExtractor: RestExtractorService              = restExtractorService
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
      (for {
        level     <- restExtractor.extractComplianceLevel(req.params)
        t1         = System.currentTimeMillis
        precision <- restExtractor.extractPercentPrecision(req.params)
        format    <- restExtractorService.extractComplianceFormat(req.params)
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
    val restExtractor = restExtractorService
    def process0(
        version:    ApiVersion,
        path:       ApiPath,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      implicit val action   = schema.name
      implicit val prettify = params.prettify

      (for {
        level      <- restExtractor.extractComplianceLevel(req.params)
        t1          = System.currentTimeMillis
        precision  <- restExtractor.extractPercentPrecision(req.params)
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

  object GetNodes extends LiftApiModule0 {
    val schema: API.GetNodesCompliance.type = API.GetNodesCompliance
    val restExtractor = restExtractorService
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      implicit val action   = schema.name
      implicit val prettify = params.prettify

      (for {
        level     <- restExtractor.extractComplianceLevel(req.params)
        precision <- restExtractor.extractPercentPrecision(req.params)
        nodes     <- complianceService.getNodesCompliance()
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
    val restExtractor = restExtractorService
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

      (for {
        level     <- restExtractor.extractComplianceLevel(req.params)
        precision <- restExtractor.extractPercentPrecision(req.params)
        node      <- complianceService.getNodeCompliance(NodeId(nodeId))
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

  object GetGlobal extends LiftApiModule0 {
    val schema: API.GetGlobalCompliance.type = API.GetGlobalCompliance
    val restExtractor = restExtractorService
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      implicit val action   = schema.name
      implicit val prettify = params.prettify

      (for {
        precision     <- restExtractor.extractPercentPrecision(req.params)
        optCompliance <- complianceService.getGlobalCompliance()
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
}

/**
 * The class in charge of getting and calculating
 * compliance for all rules/nodes/directives.
 */
class ComplianceAPIService(
    rulesRepo:                   RoRuleRepository,
    nodeInfoService:             NodeInfoService,
    nodeGroupRepo:               RoNodeGroupRepository,
    reportingService:            ReportingService,
    directiveRepo:               RoDirectiveRepository,
    val getGlobalComplianceMode: () => Box[GlobalComplianceMode]
) {

  private[this] def components(
      nodeInfos: Map[NodeId, NodeInfo]
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
         ComplianceLevel.sum(groupsComponents.map(_._2.compliance)),
         bidule.flatMap(c => components(nodeInfos)(c._1, c._2)).toList
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
                       val optNodeInfo = nodeInfos.get(nodeId)
                       ByRuleNodeCompliance(
                         nodeId,
                         optNodeInfo.map(_.hostname).getOrElse("Unknown node"),
                         optNodeInfo.map(_.policyMode).getOrElse(None),
                         ComplianceLevel.sum(components.map(_._2.compliance)),
                         components.sortBy(_._2.componentName).flatMap(_._2.componentValues)
                       )
                   }.toSeq
                 }
               ) :: Nil
             })
  }

  private[this] def getByDirectivesCompliance(
      directives: Seq[Directive],
      level:      Option[Int]
  ): IOResult[Seq[ByDirectiveCompliance]] = {
    val computedLevel = level.getOrElse(10)

    for {
      t1 <- currentTimeMillis

      // this can be optimized, as directive only happen for level=2
      rules <- if (computedLevel >= 2) {
                 rulesRepo.getAll()
               } else {
                 Seq().succeed
               }
      t2    <- currentTimeMillis
      _     <- TimingDebugLoggerPure.trace(s"getByDirectivesCompliance - getAllRules in ${t2 - t1} ms")

      nodeInfos <- nodeInfoService.getAll()
      t3        <- currentTimeMillis
      _         <- TimingDebugLoggerPure.trace(s"getByDirectivesCompliance - nodeInfoService.getAll() in ${t3 - t2} ms")

      compliance    <- getGlobalComplianceMode().toIO
      t4            <- currentTimeMillis
      _             <- TimingDebugLoggerPure.trace(s"getByDirectivesCompliance - getGlobalComplianceMode in ${t4 - t3} ms")
      reportsByNode <- reportingService
                         .findDirectiveNodeStatusReports(
                           nodeInfos.keySet,
                           directives.map(_.id).toSet
                         )
                         .toIO
      t5            <- currentTimeMillis
      _             <- TimingDebugLoggerPure.trace(s"getByDirectivesCompliance - findRuleNodeStatusReports in ${t5 - t4} ms")

    } yield {

      val reportsByRule = reportsByNode.flatMap { case (_, status) => status.reports }.groupBy(_.ruleId)
      val t6            = System.currentTimeMillis()
      TimingDebugLoggerPure.logEffect.trace(s"getByRulesCompliance - group reports by rules in ${t6 - t5} ms")
      for {
        // We will now compute compliance for each directive we want
        directive <- directives
      } yield {
        // We will compute compliance for each rule for the current Directive
        val rulesCompliance = for {
          (ruleId, ruleReports) <- reportsByRule.toSeq

          // We will now gather our report by component for the current Directive
          reportsByComponents = (for {
                                  ruleReport       <- ruleReports
                                  nodeId            = ruleReport.nodeId
                                  directiveReports <- ruleReport.directives.get(directive.id).toList
                                  component        <- directiveReports.components
                                } yield {
                                  (nodeId, component)
                                }).groupBy(_._2.componentName).toSeq

        } yield {
          val componentsCompliance = reportsByComponents.flatMap(c => components(nodeInfos)(c._1, c._2.toList))

          val ruleName          = rules.find(_.id == ruleId).map(_.name).getOrElse("")
          val componentsDetails = if (computedLevel <= 3) Seq() else componentsCompliance

          ByDirectiveByRuleCompliance(
            ruleId,
            ruleName,
            ComplianceLevel.sum(componentsCompliance.map(_.compliance)),
            componentsDetails
          )
        }
        // level = ComplianceLevel.sum(reportsByDir.map(_.compliance))
        ByDirectiveCompliance(
          directive.id,
          directive.name,
          ComplianceLevel.sum(rulesCompliance.map(_.compliance)),
          compliance.mode,
          directive.policyMode,
          rulesCompliance
        )
      }
    }
  }

  /**
   * Get the compliance for everything
   * level is optionnally the selected level.
   * level 1 includes rules but not directives
   * level 2 includes directives, but not component
   * level 3 includes components, but not nodes
   * level 4 includes the nodes
   */
  private[this] def getByRulesCompliance(rules: Seq[Rule], level: Option[Int]): IOResult[Seq[ByRuleRuleCompliance]] = {
    val computedLevel = level.getOrElse(10)

    for {
      t1        <- currentTimeMillis
      allGroups <- nodeGroupRepo.getAllNodeIds()
      t2        <- currentTimeMillis
      _         <- TimingDebugLoggerPure.trace(s"getByRulesCompliance - nodeGroupRepo.getAllNodeIds in ${t2 - t1} ms")

      // this can be optimized, as directive only happen for level=2
      directives <- if (computedLevel >= 2) {
                      directiveRepo.getFullDirectiveLibrary().map(_.allDirectives)
                    } else {
                      Map[DirectiveId, (FullActiveTechnique, Directive)]().succeed
                    }
      t3         <- currentTimeMillis
      _          <- TimingDebugLoggerPure.trace(s"getByRulesCompliance - getFullDirectiveLibrary in ${t3 - t2} ms")

      nodeInfos <- nodeInfoService.getAll()
      t4        <- currentTimeMillis
      _         <- TimingDebugLoggerPure.trace(s"getByRulesCompliance - nodeInfoService.getAll() in ${t4 - t3} ms")

      compliance <- getGlobalComplianceMode().toIO
      t5         <- currentTimeMillis
      _          <- TimingDebugLoggerPure.trace(s"getByRulesCompliance - getGlobalComplianceMode in ${t5 - t4} ms")

      ruleObjects   <- rules.map { case x => (x.id, x) }.toMap.succeed
      reportsByNode <- reportingService
                         .findRuleNodeStatusReports(
                           nodeInfos.keySet,
                           ruleObjects.keySet
                         )
                         .toIO
      t6            <- currentTimeMillis
      _             <- TimingDebugLoggerPure.trace(s"getByRulesCompliance - findRuleNodeStatusReports in ${t6 - t5} ms")

    } yield {

      val reportsByRule = reportsByNode.flatMap { case (_, status) => status.reports }.groupBy(_.ruleId)
      val t7            = System.currentTimeMillis()
      TimingDebugLoggerPure.logEffect.trace(s"getByRulesCompliance - group reports by rules in ${t7 - t6} ms")

      // for each rule for each node, we want to have a
      // directiveId -> reporttype map
      val nonEmptyRules = reportsByRule.toSeq.map {
        case (ruleId, reports) =>
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
            byDirectives.map {
              case (directiveId, nodeDirectives) =>
                ByRuleDirectiveCompliance(
                  directiveId,
                  directives.get(directiveId).map(_._2.name).getOrElse("Unknown directive"),
                  ComplianceLevel.sum(
                    nodeDirectives.map(_._2.compliance)
                  ), // here we want the compliance by components of the directive.
                  // if level is high enough, get all components and group by their name
                  {
                    val byComponents: Map[String, immutable.Iterable[(NodeId, ComponentStatusReport)]] = if (computedLevel < 3) {
                      Map()
                    } else {
                      nodeDirectives.flatMap { case (nodeId, d) => d.components.map(c => (nodeId, c)).toSeq }
                        .groupBy(_._2.componentName)
                    }
                    byComponents.flatMap {
                      case (name, nodeComponents) => components(nodeInfos)(name, nodeComponents.toList)
                    }.toSeq
                  }
                )
            }.toSeq
          )
      }
      val t8            = System.currentTimeMillis()
      TimingDebugLoggerPure.logEffect.trace(s"getByRulesCompliance - Compute non empty rules in ${t8 - t7} ms")

      // if any rules is in the list in parameter an not in the nonEmptyRules, then it means
      // there's no compliance for it, so it's empty
      // we need to set the ByRuleCompliance with a compliance of NoAnswer
      val rulesWithoutCompliance = ruleObjects.keySet -- reportsByRule.keySet

      val initializedCompliances: Seq[ByRuleRuleCompliance] = {
        if (rulesWithoutCompliance.isEmpty) {
          Seq[ByRuleRuleCompliance]()
        } else {
          rulesWithoutCompliance.toSeq.map {
            case ruleId =>
              val rule    = ruleObjects(ruleId) // we know by construct that it exists
              val nodeIds = RoNodeGroupRepository.getNodeIds(allGroups, rule.targets, nodeInfos)
              ByRuleRuleCompliance(
                rule.id,
                rule.name,
                ComplianceLevel(noAnswer = nodeIds.size),
                compliance.mode,
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
      val result = nonEmptyRules ++ initializedCompliances

      val t10 = System.currentTimeMillis()
      TimingDebugLoggerPure.logEffect.trace(s"getByRulesCompliance - Compute result in ${t10 - t9} ms")
      result
    }
  }

  def getRuleCompliance(ruleId: RuleId, level: Option[Int]): Box[ByRuleRuleCompliance] = {
    for {
      rule    <- rulesRepo.get(ruleId)
      reports <- getByRulesCompliance(Seq(rule), level)
      report  <- reports.find(_.id == ruleId).notOptional(s"No reports were found for rule with ID '${ruleId.serialize}'")
    } yield {
      report
    }
  }.toBox

  def getDirectiveCompliance(directive: Directive, level: Option[Int]): Box[ByDirectiveCompliance] = {
    getByDirectivesCompliance(Seq(directive), level)
      .flatMap(
        _.find(_.id == directive.id).notOptional(s"No reports were found for directive with ID '${directive.id.serialize}'")
      )
      .toBox
  }

  def getDirectivesCompliance(level: Option[Int]): Box[Seq[ByDirectiveCompliance]] = {
    for {
      directives <- directiveRepo.getFullDirectiveLibrary().map(_.allDirectives.values.map(_._2))
      reports    <- getByDirectivesCompliance(directives.toSeq, level)
    } yield {
      reports
    }
  }.toBox

  def getRulesCompliance(level: Option[Int]): Box[Seq[ByRuleRuleCompliance]] = {
    for {
      rules   <- rulesRepo.getAll()
      reports <- getByRulesCompliance(rules, level)
    } yield {
      reports
    }
  }.toBox

  /**
   * Get the compliance for everything
   */
  private[this] def getByNodesCompliance(onlyNode: Option[NodeId]): IOResult[Seq[ByNodeNodeCompliance]] = {

    for {
      rules        <- rulesRepo.getAll()
      allGroups    <- nodeGroupRepo.getAllNodeIds()
      directiveLib <- directiveRepo.getFullDirectiveLibrary().map(_.allDirectives)
      allNodeInfos <- nodeInfoService.getAll()
      nodeInfos    <- onlyNode match {
                        case None     => allNodeInfos.succeed
                        case Some(id) =>
                          allNodeInfos
                            .get(id)
                            .map(info => Map(id -> info))
                            .notOptional(s"The node with ID '${id.value}' is not known on Rudder")
                      }
      compliance   <- getGlobalComplianceMode().toIO
      reports      <- reportingService
                        .findRuleNodeStatusReports(
                          nodeInfos.keySet,
                          rules.map(_.id).toSet
                        )
                        .toIO
    } yield {

      // get nodeIds by rules
      val nodeByRules = rules.map(rule => (rule, RoNodeGroupRepository.getNodeIds(allGroups, rule.targets, allNodeInfos)))

      val ruleMap = rules.map(r => (r.id, r)).toMap
      // get an empty-initialized array of compliances to be used
      // as defaults
      val initializedCompliances: Map[NodeId, ByNodeNodeCompliance] = {
        nodeInfos.map {
          case (nodeId, nodeInfo) =>
            val rulesForNode = nodeByRules.collect { case (rule, nodeIds) if (nodeIds.contains(nodeId)) => rule }

            (
              nodeId,
              ByNodeNodeCompliance(
                nodeId,
                nodeInfos.get(nodeId).map(_.hostname).getOrElse("Unknown node"),
                ComplianceLevel(noAnswer = rulesForNode.size),
                compliance.mode,
                (rulesForNode.map { rule =>
                  ByNodeRuleCompliance(
                    rule.id,
                    rule.name,
                    ComplianceLevel(noAnswer = rule.directiveIds.size),
                    rule.directiveIds.map { rid =>
                      ByNodeDirectiveCompliance(
                        rid,
                        directiveLib.get(rid).map(_._2.name).getOrElse("Unknown Directive"),
                        ComplianceLevel(noAnswer = 1),
                        Nil
                      )
                    }.toSeq
                  )
                })
              )
            )
        }
      }

      // for each rule for each node, we want to have a
      // directiveId -> reporttype map
      val nonEmptyNodes = reports.map {
        case (nodeId, status) =>
          (
            nodeId,
            ByNodeNodeCompliance(
              nodeId,
              nodeInfos.get(nodeId).map(_.hostname).getOrElse("Unknown node"),
              ComplianceLevel.sum(status.reports.map(_.compliance)),
              compliance.mode,
              status.reports.toSeq.map(r => {
                ByNodeRuleCompliance(
                  r.ruleId,
                  ruleMap.get(r.ruleId).map(_.name).getOrElse("Unknown rule"),
                  r.compliance,
                  r.directives.toSeq.map {
                    case (_, directiveReport) =>
                      ByNodeDirectiveCompliance(
                        directiveReport,
                        directiveLib.get(directiveReport.directiveId).map(_._2.name).getOrElse("Unknown Directive")
                      )
                  }
                )
              })
            )
          )
      }

      // return the full list, even for non responding nodes/directives
      // but override with values when available.
      (initializedCompliances ++ nonEmptyNodes).values.toSeq

    }
  }

  def getNodeCompliance(nodeId: NodeId): Box[ByNodeNodeCompliance] = {
    for {
      reports <- this.getByNodesCompliance(Some(nodeId)).toBox
      report  <- Box(reports.find(_.id == nodeId)) ?~! s"No reports were found for node with ID '${nodeId.value}'"
    } yield {
      report
    }

  }

  def getNodesCompliance(): Box[Seq[ByNodeNodeCompliance]] = {
    this.getByNodesCompliance(None).toBox
  }

  def getGlobalCompliance(): Box[Option[(ComplianceLevel, Long)]] = {
    this.reportingService.getGlobalUserCompliance()
  }
}
