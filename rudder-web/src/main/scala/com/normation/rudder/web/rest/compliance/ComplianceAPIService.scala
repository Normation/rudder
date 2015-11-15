/*
*************************************************************************************
* Copyright 2015 Normation SAS
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

package com.normation.rudder.web.rest.compliance

import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.reports.ComplianceLevel
import com.normation.rudder.repository.RoNodeGroupRepository
import com.normation.rudder.repository.RoRuleRepository
import com.normation.rudder.services.nodes.NodeInfoService
import com.normation.rudder.services.reports.ReportingService
import net.liftweb.common.Box
import com.normation.rudder.domain.policies.Rule
import com.normation.rudder.domain.nodes.NodeInfo
import net.liftweb.common.Full
import com.normation.rudder.reports.GlobalComplianceMode



/**
 * The class in charge of getting and calculating
 * compliance for all rules/nodes/directives.
 */
class ComplianceAPIService(
    rulesRepo       : RoRuleRepository
  , nodeInfoService : NodeInfoService
  , nodeGroupRepo   : RoNodeGroupRepository
  , reportingService: ReportingService
  , val getGlobalComplianceMode: () => Box[GlobalComplianceMode]
) {



  /**
   * Get the compliance for everything
   */
 private[this] def getByRulesCompliance(rules: Set[Rule]) : Box[Seq[ByRuleRuleCompliance]] = {

    for {
      groupLib      <- nodeGroupRepo.getFullGroupLibrary()
      nodeInfos     <- nodeInfoService.getAll()
      compliance    <- getGlobalComplianceMode()
      reportsByRule <- reportingService.findRuleNodeStatusReports(
                        nodeInfos.keySet, rules.map(_.id).toSet
                      ).map( _.groupBy(_.ruleId))
    } yield {


      // get an empty-initialized array of compliances to be used
      // as defaults
      val initializedCompliances : Map[RuleId, ByRuleRuleCompliance] = {
        (rules.map { rule =>
          val nodeIds = groupLib.getNodeIds(rule.targets, nodeInfos)

          (rule.id, ByRuleRuleCompliance(
              rule.id
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
            , ComplianceLevel.sum(reports.map(_.compliance))
            , compliance.mode
            , byDirectives.map{ case (directiveId, nodeDirectives) =>
                ByRuleDirectiveCompliance(
                    directiveId
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
      rule    <- rulesRepo.get(ruleId)
      reports <- getByRulesCompliance(Set(rule))
      report  <- Box(reports.find( _.id == ruleId)) ?~! s"No reports were found for rule with ID '${ruleId.value}'"
    } yield {
      report
    }
  }

  def getRulesCompliance(): Box[Seq[ByRuleRuleCompliance]] = {
    for {
      rules   <- rulesRepo.getAll()
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
      rules        <- rulesRepo.getAll()
      groupLib     <- nodeGroupRepo.getFullGroupLibrary()
      allNodeInfos <- nodeInfoService.getAll()
      nodeInfos    <- onlyNode match {
                        case None => Full(allNodeInfos)
                        case Some(id) => Box(allNodeInfos.get(id)).map(info => Map(id -> info)) ?~! s"The node with ID '${id.value}' is not known on Rudder"
                      }
      compliance   <- getGlobalComplianceMode()
      reports      <- reportingService.findRuleNodeStatusReports(
                        nodeInfos.keySet, rules.map(_.id).toSet
                      ).map( _.groupBy( _.nodeId ) )
    } yield {

      //get nodeIds by rules
      val nodeByRules = rules.map { rule =>
        (rule, groupLib.getNodeIds(rule.targets, allNodeInfos) )
      }

      // get an empty-initialized array of compliances to be used
      // as defaults
      val initializedCompliances : Map[NodeId, ByNodeNodeCompliance] = {
        nodeInfos.map { case (nodeId, nodeInfo) =>
          val rulesForNode = nodeByRules.collect { case (rule, nodeIds) if(nodeIds.contains(nodeId)) => rule }

          (nodeId, ByNodeNodeCompliance(
              nodeId
            , ComplianceLevel(noAnswer = rulesForNode.size)
            , compliance.mode
            , (rulesForNode.map { rule =>
                ByNodeRuleCompliance(
                    rule.id
                  , ComplianceLevel(noAnswer = rule.directiveIds.size)
                  , rule.directiveIds.map { id => ByNodeDirectiveCompliance(id, ComplianceLevel(noAnswer = 1), Map())}.toSeq
                )
              }).toSeq
          ))
        }.toMap
      }

      //for each rule for each node, we want to have a
      //directiveId -> reporttype map
      val nonEmptyNodes = reports.map { case (ruleId, ruleNodeStatusReports) =>
        (
          ruleId,
          ByNodeNodeCompliance(
              ruleId
            , ComplianceLevel.sum(ruleNodeStatusReports.map(_.compliance))
            , compliance.mode
            , ruleNodeStatusReports.toSeq.map(r =>
               ByNodeRuleCompliance(
                    r.ruleId
                  , r.compliance
                  , r.directives.toSeq.map { case (_, directiveReport) => ByNodeDirectiveCompliance(directiveReport) }
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
}
