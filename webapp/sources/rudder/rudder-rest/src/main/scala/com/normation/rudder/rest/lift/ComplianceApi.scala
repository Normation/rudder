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

import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies.Rule
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.reports.ComplianceLevel
import com.normation.rudder.reports.GlobalComplianceMode
import com.normation.rudder.repository.RoDirectiveRepository
import com.normation.rudder.repository.RoNodeGroupRepository
import com.normation.rudder.repository.RoRuleRepository
import com.normation.rudder.rest.ApiVersion
import com.normation.rudder.rest.RestExtractorService
import com.normation.rudder.rest.RestUtils._
import com.normation.rudder.rest._
import com.normation.rudder.rest.data._
import com.normation.rudder.rest.{ComplianceApi => API}
import com.normation.rudder.services.nodes.NodeInfoService
import com.normation.rudder.services.reports.ReportingService
import net.liftweb.common._
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import net.liftweb.json.JsonDSL._
import net.liftweb.json._

import com.normation.box._

class ComplianceApi(
    restExtractorService: RestExtractorService
  , complianceService   : ComplianceAPIService
) extends LiftApiModuleProvider[API] {

  import JsonCompliance._

  def schemas = API

  /*
   * The actual builder for the compliance API.
   * Depends of authz method and supported version.
   *
   * It's quite verbose, but it's the only way I found to
   * get the exhaustivity check and be sure that ALL
   * endpoints are processed.
   */
  def getLiftEndpoints(): List[LiftApiModule] = {
    API.endpoints.map(e => e match {
        case API.GetRulesCompliance   => GetRules
        case API.GetRulesComplianceId => GetRuleId
        case API.GetNodesCompliance   => GetNodes
        case API.GetNodeComplianceId  => GetNodeId
        case API.GetGlobalCompliance  => GetGlobal
    }).toList
  }


  object GetRules extends LiftApiModule0 {
    val schema = API.GetRulesCompliance
    val restExtractor = restExtractorService
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      implicit val action = schema.name
      implicit val prettify = params.prettify

      (for {
        level <- restExtractor.extractComplianceLevel(req.params)
        rules <- complianceService.getRulesCompliance()
      } yield {
        if(version.value <= 6) {
          rules.map( _.toJsonV6 )
        } else {
          rules.map( _.toJson(level.getOrElse(10) ) ) //by default, all details are displayed
        }
      }) match {
        case Full(rules) =>
          toJsonResponse(None, ( "rules" -> rules ) )

        case eb: EmptyBox =>
          val message = (eb ?~ (s"Could not get compliance for all rules")).messageChain
          toJsonError(None, JString(message))
      }
    }
  }

  object GetRuleId extends LiftApiModule {
    val schema = API.GetRulesComplianceId
    val restExtractor = restExtractorService
    def process(version: ApiVersion, path: ApiPath, ruleId: String, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      implicit val action = schema.name
      implicit val prettify = params.prettify

      (for {
        level <- restExtractor.extractComplianceLevel(req.params)
        rule  <- complianceService.getRuleCompliance(RuleId(ruleId))
      } yield {
        if(version.value <= 6) {
          rule.toJsonV6
        } else {
          rule.toJson(level.getOrElse(10) ) //by default, all details are displayed
        }
      }) match {
        case Full(rule) =>
          toJsonResponse(None,( "rules" -> List(rule) ) )

        case eb: EmptyBox =>
          val message = (eb ?~ (s"Could not get compliance for rule '${ruleId}'")).messageChain
          toJsonError(None, JString(message))
      }
    }
  }

  object GetNodes extends LiftApiModule0 {
    val schema = API.GetNodesCompliance
    val restExtractor = restExtractorService
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      implicit val action = schema.name
      implicit val prettify = params.prettify

      (for {
        level <- restExtractor.extractComplianceLevel(req.params)
        nodes <- complianceService.getNodesCompliance()
      } yield {
        if(version.value <= 6) {
          nodes.map( _.toJsonV6 )
        } else {
          nodes.map( _.toJson(level.getOrElse(10)) )
        }
      })match {
        case Full(nodes) =>
          toJsonResponse(None, ("nodes" -> nodes ) )

        case eb: EmptyBox =>
          val message = (eb ?~ ("Could not get compliances for nodes")).messageChain
          toJsonError(None, JString(message))
      }
    }
  }

  object GetNodeId extends LiftApiModule {
    val schema = API.GetNodeComplianceId
    val restExtractor = restExtractorService
    def process(version: ApiVersion, path: ApiPath, nodeId: String, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      implicit val action = schema.name
      implicit val prettify = params.prettify

      (for {
        level <- restExtractor.extractComplianceLevel(req.params)
        node  <- complianceService.getNodeCompliance(NodeId(nodeId))
      } yield {
        if(version.value <= 6) {
          node.toJsonV6
        } else {
          node.toJson(level.getOrElse(10))
        }
      })match {
        case Full(node) =>
          toJsonResponse(None, ("nodes" -> List(node) ))

        case eb: EmptyBox =>
          val message = (eb ?~ (s"Could not get compliance for node '${nodeId}'")).messageChain
          toJsonError(None, JString(message))
      }
    }
  }

  object GetGlobal extends LiftApiModule0 {
    val schema = API.GetGlobalCompliance
    val restExtractor = restExtractorService
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      implicit val action = schema.name
      implicit val prettify = params.prettify

      (for {
        optCompliance <- complianceService.getGlobalCompliance()
      } yield {
        optCompliance.toJson
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
    rulesRepo       : RoRuleRepository
  , nodeInfoService : NodeInfoService
  , nodeGroupRepo   : RoNodeGroupRepository
  , reportingService: ReportingService
  , directiveRepo   : RoDirectiveRepository
  , val getGlobalComplianceMode: () => Box[GlobalComplianceMode]
) {

  /**
   * Get the compliance for everything
   */
 private[this] def getByRulesCompliance(rules: Set[Rule]) : Box[Seq[ByRuleRuleCompliance]] = {

    for {
      groupLib      <- nodeGroupRepo.getFullGroupLibrary().toBox
      directivelib  <- directiveRepo.getFullDirectiveLibrary().toBox
      nodeInfos     <- nodeInfoService.getAll()
      compliance    <- getGlobalComplianceMode()
      reportsByNode <- reportingService.findRuleNodeStatusReports(
                         nodeInfos.keySet, rules.map(_.id).toSet
                       )
    } yield {

      //flatMap of Set is ok, since nodeRuleStatusReport are different for different nodeIds
      val reportsByRule = reportsByNode.flatMap { case(nodeId, status) => status.report.reports }.groupBy( _.ruleId)

      // get an empty-initialized array of compliances to be used
      // as defaults
      val initializedCompliances : Map[RuleId, ByRuleRuleCompliance] = {
        (rules.map { rule =>
          val nodeIds = groupLib.getNodeIds(rule.targets, nodeInfos)

          (rule.id, ByRuleRuleCompliance(
              rule.id
            , rule.name
            , ComplianceLevel(noAnswer = nodeIds.size)
            , compliance.mode
            , Seq()
          ))
        }).toMap
      }

      //for each rule for each node, we want to have a
      //directiveId -> reporttype map
      val nonEmptyRules = reportsByRule.map { case (ruleId, reports) =>

        //aggregate by directives
        val byDirectives = reports.flatMap { r => r.directives.values.map(d => (r.nodeId, d)).toSeq }.groupBy( _._2.directiveId)

        (
          ruleId,
          ByRuleRuleCompliance(
              ruleId
            , initializedCompliances.get(ruleId).map(_.name).getOrElse("Unknown rule")
            , ComplianceLevel.sum(reports.map(_.compliance))
            , compliance.mode
            , byDirectives.map{ case (directiveId, nodeDirectives) =>
                ByRuleDirectiveCompliance(
                    directiveId
                  , directivelib.allDirectives.get(directiveId).map(_._2.name).getOrElse("Unknown directive")
                  , ComplianceLevel.sum(nodeDirectives.map( _._2.compliance) )
                  , //here we want the compliance by components of the directive. Get all components and group by their name
                    {
                      val byComponents = nodeDirectives.flatMap { case (nodeId, d) => d.components.values.map(c => (nodeId, c)).toSeq }.groupBy( _._2.componentName )
                      byComponents.map { case (name, nodeComponents) =>
                        ByRuleComponentCompliance(
                            name
                          , ComplianceLevel.sum( nodeComponents.map(_._2.compliance))
                          , //here, we finally group by nodes for each components !
                            {
                              val byNode = nodeComponents.groupBy(_._1)
                              byNode.map { case (nodeId, components) =>
                                ByRuleNodeCompliance(
                                    nodeId
                                  , nodeInfos.get(nodeId).map(_.hostname).getOrElse("Unknown node")
                                  , components.toSeq.sortBy(_._2.componentName).flatMap(_._2.componentValues.values)
                                )
                              }.toSeq
                            }
                        )
                      }.toSeq
                    }
                )
              }.toSeq
          )
        )
      }.toMap

      //return the full list, even for non responding nodes/directives
      //but override with values when available.
      (initializedCompliances ++ nonEmptyRules).values.toSeq

    }
  }

  def getRuleCompliance(ruleId: RuleId): Box[ByRuleRuleCompliance] = {
    for {
      rule    <- rulesRepo.get(ruleId).toBox
      reports <- getByRulesCompliance(Set(rule))
      report  <- Box(reports.find( _.id == ruleId)) ?~! s"No reports were found for rule with ID '${ruleId.value}'"
    } yield {
      report
    }
  }

  def getRulesCompliance(): Box[Seq[ByRuleRuleCompliance]] = {
    for {
      rules   <- rulesRepo.getAll().toBox
      reports <- getByRulesCompliance(rules.toSet)
    } yield {
      reports
    }
  }

  /**
   * Get the compliance for everything
   */
  private[this] def getByNodesCompliance(onlyNode: Option[NodeId]): Box[Seq[ByNodeNodeCompliance]] = {

    for {
      rules        <- rulesRepo.getAll().toBox
      groupLib     <- nodeGroupRepo.getFullGroupLibrary().toBox
      directiveLib <- directiveRepo.getFullDirectiveLibrary().map(_.allDirectives).toBox
      allNodeInfos <- nodeInfoService.getAll()
      nodeInfos    <- onlyNode match {
                        case None => Full(allNodeInfos)
                        case Some(id) => Box(allNodeInfos.get(id)).map(info => Map(id -> info)) ?~! s"The node with ID '${id.value}' is not known on Rudder"
                      }
      compliance   <- getGlobalComplianceMode()
      reports      <- reportingService.findRuleNodeStatusReports(
                        nodeInfos.keySet, rules.map(_.id).toSet
                      )
    } yield {

      //get nodeIds by rules
      val nodeByRules = rules.map { rule =>
        (rule, groupLib.getNodeIds(rule.targets, allNodeInfos) )
      }

      val ruleMap = rules.map(r => (r.id,r)).toMap
      // get an empty-initialized array of compliances to be used
      // as defaults
      val initializedCompliances : Map[NodeId, ByNodeNodeCompliance] = {
        nodeInfos.map { case (nodeId, nodeInfo) =>
          val rulesForNode = nodeByRules.collect { case (rule, nodeIds) if(nodeIds.contains(nodeId)) => rule }

          (nodeId, ByNodeNodeCompliance(
              nodeId
            , nodeInfos.get(nodeId).map(_.hostname).getOrElse("Unknown node")
            , ComplianceLevel(noAnswer = rulesForNode.size)
            , compliance.mode
            , (rulesForNode.map { rule =>
                ByNodeRuleCompliance(
                    rule.id
                  , rule.name
                  , ComplianceLevel(noAnswer = rule.directiveIds.size)
                  , rule.directiveIds.map { id => ByNodeDirectiveCompliance(id, directiveLib.get(id).map(_._2.name).getOrElse("Unknown Directive"), ComplianceLevel(noAnswer = 1), Map())}.toSeq
                )
              }).toSeq
          ))
        }.toMap
      }

      //for each rule for each node, we want to have a
      //directiveId -> reporttype map
      val nonEmptyNodes = reports.map { case (nodeId, status) =>
        (
          nodeId,
          ByNodeNodeCompliance(
              nodeId
            , nodeInfos.get(nodeId).map(_.hostname).getOrElse("Unknown node")
            , ComplianceLevel.sum(status.report.reports.map(_.compliance))
            , compliance.mode
            , status.report.reports.toSeq.map(r =>
               ByNodeRuleCompliance(
                    r.ruleId
                  , ruleMap.get(r.ruleId).map(_.name).getOrElse("Unknown rule")
                  , r.compliance
                  , r.directives.toSeq.map { case (_, directiveReport) => ByNodeDirectiveCompliance(directiveReport,directiveLib.get(directiveReport.directiveId).map(_._2.name).getOrElse("Unknown Directive")) }
                )
              )
          )
        )
      }.toMap

      //return the full list, even for non responding nodes/directives
      //but override with values when available.
      (initializedCompliances ++ nonEmptyNodes).values.toSeq

    }
  }

  def getNodeCompliance(nodeId: NodeId): Box[ByNodeNodeCompliance] = {
    for {
      reports <- this.getByNodesCompliance(Some(nodeId))
      report  <- Box(reports.find( _.id == nodeId)) ?~! s"No reports were found for node with ID '${nodeId.value}'"
    } yield {
      report
    }

  }

  def getNodesCompliance(): Box[Seq[ByNodeNodeCompliance]] = {
    this.getByNodesCompliance(None)
  }

  def getGlobalCompliance(): Box[Option[(ComplianceLevel, Long)]] = {
    this.reportingService.getGlobalUserCompliance()
  }
}

