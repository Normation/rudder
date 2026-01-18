/*
 *************************************************************************************
 * Copyright 2026 Normation SAS
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

package com.normation.rudder.services.policies.fetchinfo

import com.normation.errors.*
import com.normation.inventory.domain.MemorySize
import com.normation.inventory.domain.NodeId
import com.normation.rudder.campaigns.*
import com.normation.rudder.configuration.ConfigurationRepository
import com.normation.rudder.domain.appconfig.FeatureSwitch
import com.normation.rudder.domain.logger.PolicyGenerationLogger
import com.normation.rudder.domain.logger.PolicyGenerationLoggerPure
import com.normation.rudder.domain.policies.AppliedStatus
import com.normation.rudder.domain.policies.GlobalPolicyMode
import com.normation.rudder.domain.policies.Rule
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.properties.GlobalParameter
import com.normation.rudder.domain.reports.NodeModeConfig
import com.normation.rudder.facts.nodes.CoreNodeFact
import com.normation.rudder.facts.nodes.NodeFactRepository
import com.normation.rudder.properties.PropertiesRepository
import com.normation.rudder.reports.AgentRunInterval
import com.normation.rudder.reports.AgentRunIntervalService
import com.normation.rudder.reports.ComplianceModeService
import com.normation.rudder.reports.GlobalComplianceMode
import com.normation.rudder.repository.FullActiveTechniqueCategory
import com.normation.rudder.repository.FullNodeGroupCategory
import com.normation.rudder.repository.RoNodeGroupRepository
import com.normation.rudder.repository.RoParameterRepository
import com.normation.rudder.repository.RoRuleRepository
import com.normation.rudder.schedule.DirectiveSchedule
import com.normation.rudder.services.policies.FetchAllInfo
import com.normation.rudder.services.policies.RuleApplicationStatusService
import com.normation.rudder.services.policies.RuleVal
import com.normation.rudder.services.policies.RuleValService
import com.normation.rudder.services.policies.WriteCertificatesPemService
import com.normation.rudder.services.policies.nodeconfig.NodeConfigurationHash
import com.normation.rudder.services.policies.nodeconfig.NodeConfigurationHashRepository
import com.normation.rudder.services.policies.write.RuleValGeneratedHookService
import com.normation.rudder.tenants.QueryContext
import com.normation.rudder.utils.ParseMaxParallelism
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration
import zio.*
import zio.System as _
import zio.syntax.*

/**
 * A service that fetch all information needed for further computation of node configuration.
 */
trait FetchAllInfoService {
  def fetchAll(): IOResult[FetchAllInfo]
}

/*
 * Default implementation of the service that:
 * - get all information needed for a policy generation
 * - build node interpolation context
 * - build first level of node expeted configuration (rule vals)
 */
class FetchAllInfoServiceImpl(
    roRuleRepo:                   RoRuleRepository,
    nodeFactRepository:           NodeFactRepository,
    configurationRepository:      ConfigurationRepository,
    roNodeGroupRepository:        RoNodeGroupRepository,
    roParameterRepository:        RoParameterRepository,
    agentRunService:              AgentRunIntervalService,
    propertiesRepository:         PropertiesRepository,
    complianceModeService:        ComplianceModeService,
    nodeConfigurationService:     NodeConfigurationHashRepository,
    ruleValService:               RuleValService,
    ruleApplicationStatusService: RuleApplicationStatusService,
    nodeContextService:           NodeContextBuilder,
    writeCertificatesPemService:  WriteCertificatesPemService,
    ruleValGeneratedHookService:  RuleValGeneratedHookService,
    // this need to be function to avoid circular definition in RudderConfig
    getScriptEngineEnabled:       () => IOResult[FeatureSwitch],
    getGlobalPolicyMode:          () => IOResult[GlobalPolicyMode],
    getMaxParallelism:            () => IOResult[String],
    getJsTimeout:                 () => IOResult[Int],
    getGenerationContinueOnError: () => IOResult[Boolean]
) extends FetchAllInfoService {

  /*
   * From global configuration and node modes, build node modes
   */
  def buildNodeModes(
      nodes:                Map[NodeId, CoreNodeFact],
      globalComplianceMode: GlobalComplianceMode,
      globalAgentRun:       AgentRunInterval,
      globalPolicyMode:     GlobalPolicyMode
  ): Map[NodeId, NodeModeConfig] = {
    nodes.map {
      case (id, info) =>
        (
          id,
          NodeModeConfig(
            globalComplianceMode,
            globalAgentRun,
            info.rudderSettings.reportingConfiguration.agentRunInterval,
            globalPolicyMode,
            info.rudderSettings.policyMode
          )
        )
    }.toMap
  }

  def getAppliedRuleIds(
      rules:            Seq[Rule],
      groupLib:         FullNodeGroupCategory,
      directiveLib:     FullActiveTechniqueCategory,
      arePolicyServers: Map[NodeId, Boolean]
  ): Set[RuleId] = {
    rules
      .filter(r => {
        ruleApplicationStatusService
          .isApplied(r, groupLib, directiveLib, arePolicyServers) match {
          case _: AppliedStatus => true
          case _ => false
        }
      })
      .map(_.id)
      .toSet

  }

  /**
   * This method logs interesting metrics that can be use to assess performance problem.
   */
  def logMetrics(
      nodeFacts:        Map[NodeId, CoreNodeFact],
      allRules:         Seq[Rule],
      directiveLib:     FullActiveTechniqueCategory,
      groupLib:         FullNodeGroupCategory,
      allParameters:    Seq[GlobalParameter],
      nodeConfigCaches: Map[NodeId, NodeConfigurationHash]
  ): Unit = {
    /*
     * log:
     * - number of nodes (total, number with cache)
     * - number of rules (total, enable)
     * - number of directives (total, enable)
     * - number of groups (total, enable, and for enable: number of nodes)
     * - number of parameters
     */
    val ram = MemorySize(java.lang.Runtime.getRuntime().maxMemory()).toStringMo
    val n   = nodeFacts.size
    val nc  = nodeConfigCaches.size
    val r   = allRules.size
    val re  = allRules.filter(_.isEnabled).size
    val t   = directiveLib.allActiveTechniques.size
    val te  = directiveLib.allActiveTechniques.filter(_._2.isEnabled).size
    val d   = directiveLib.allDirectives.size
    val de  = directiveLib.allDirectives.filter(_._2._2.isEnabled).size
    val g   = groupLib.allGroups.size
    val gd  = groupLib.allGroups.filter(_._2.nodeGroup.isDynamic).size
    val p   = allParameters.size
    PolicyGenerationLogger.info(
      s"[metrics] Xmx:$ram nodes:$n (cached:$nc) rules:$r (enabled:$re) techniques:$t (enabled:$te) directives:$d (enabled:$de) groups:$g (dynamic:$gd) parameters:$p"
    )
  }

  /**
   * Rule vals are just rules with a analysis of parameter
   * on directive done, so that we will be able to bind them
   * to a context latter.
   */
  def buildRuleVals(
      activeRuleIds:    Set[RuleId],
      rules:            List[Rule],
      directiveLib:     FullActiveTechniqueCategory,
      allGroups:        FullNodeGroupCategory,
      arePolicyServers: Map[NodeId, Boolean]
  ): IOResult[Seq[RuleVal]] = {

    val appliedRules = rules.filter(r => activeRuleIds.contains(r.id))
    for {
      rawRuleVals <- appliedRules.accumulate { rule =>
                       ruleValService.buildRuleVal(rule, directiveLib, allGroups, arePolicyServers)
                     }.chainError("Could not find configuration vals")
    } yield rawRuleVals
  }

  def fetchAll(): IOResult[FetchAllInfo] = {
    implicit val qc: QueryContext = QueryContext.systemQC

    def currentTimeMillis = ZIO.clock.flatMap(_.currentTime(TimeUnit.MILLISECONDS))

    for {
      fetch0Time                <- currentTimeMillis
      getMaxPar                 <- getMaxParallelism().catchAll(_ => "x0.5".succeed)
      maxParallelism             =
        ParseMaxParallelism(getMaxPar, 1, "rudder_generation_max_parallelism", (s: String) => PolicyGenerationLogger.warn(s))
      getJsTimeout              <- getJsTimeout().catchAll(_ => 5.succeed)
      jsTimeout                  = FiniteDuration(Math.max(1L, getJsTimeout.toLong), TimeUnit.SECONDS)
      generationContinueOnError <- getGenerationContinueOnError().catchAll(_ => false.succeed)
      _                         <-
        PolicyGenerationLoggerPure.debug(
          s"Policy generation parallelism set to: ${maxParallelism} (change with REST API settings parameter 'rudder_generation_max_parallelism')"
        )
      _                         <-
        PolicyGenerationLoggerPure.debug(
          s"Policy generation JS evaluation of directive parameter timeout: ${jsTimeout} s (change with REST API settings parameter 'rudder_generation_jsTimeout')"
        )
      _                         <-
        PolicyGenerationLoggerPure.debug(
          s"Policy generation continues on NodeConfigurations evaluation: ${generationContinueOnError} (change with REST API settings parameter 'rudder_generation_continue_on_error')"
        )

      (t1, allRules)              <- roRuleRepo.getAll(includeSystem = true).chainError("Could not find dependant rules").timed
      _                           <- PolicyGenerationLoggerPure.timing.trace(s"Fetched rules in ${t1.toMillis} ms")
      (t2, nf)                    <- nodeFactRepository.getAll().chainError("Could not get Node Infos").timed // disabled node don't get new policies
      _                           <- PolicyGenerationLoggerPure.timing.trace(s"Fetched node infos in ${t2.toMillis} ms")
      (t3, directiveLib)          <-
        configurationRepository
          .getDirectiveLibrary(allRules.flatMap(_.directiveIds).toSet)
          .chainError("Could not get the directive library")
          .timed
      _                           <- PolicyGenerationLoggerPure.timing.trace(s"Fetched directives in ${t3.toMillis} ms")
      gl                          <- roNodeGroupRepository.getFullGroupLibrary().chainError("Could not get the group library")
      (t4, (nodeFacts, groupLib)) <- GetConsistentNodesAndGroups(nf, gl).timed
      _                           <- PolicyGenerationLoggerPure.timing.trace(s"Fetched groups in ${t4.toMillis} ms")
      (t5, allParameters)         <- roParameterRepository.getAllGlobalParameters().chainError("Could not get global parameters").timed
      _                           <- PolicyGenerationLoggerPure.timing.trace(s"Fetched global parameters in ${t5.toMillis} ms")
      (t6, globalAgentRun)        <- agentRunService.getGlobalAgentRun().toIO.timed
      _                           <- PolicyGenerationLoggerPure.timing.trace(s"Fetched run infos in ${t6.toMillis} ms")
      (t7, mergedProps)           <- propertiesRepository.getAllNodeProps().timed
      _                           <- PolicyGenerationLoggerPure.timing.trace(s"Fetched merged properties ${t7.toMillis} ms")
      scriptEngineEnabled         <-
        getScriptEngineEnabled().chainError("Could not get if we should use the script engine to evaluate directive parameters")
      globalComplianceMode        <- complianceModeService.getGlobalComplianceMode
      globalPolicyMode            <- getGlobalPolicyMode().chainError("Cannot get the Global Policy Mode (Enforce or Verify)")
      nodeConfigCaches            <- nodeConfigurationService.getAll().chainError("Cannot get the Configuration Cache")
      allNodeModes                 = buildNodeModes(nodeFacts, globalComplianceMode, globalAgentRun, globalPolicyMode)
      // for now, schedules are not configurable, so we only have one, hardcoded.
      schedules                    = Map(
                                       SystemDirectiveSchedule.dailyOn4UTC.info.id -> SystemDirectiveSchedule.dailyOn4UTC
                                     )

      fetchAllTime <- currentTimeMillis
      timeFetchAll  = fetchAllTime - fetch0Time
      _            <- PolicyGenerationLoggerPure.timing.debug(s"All relevant information fetched in ${timeFetchAll} ms.")

      _ = logMetrics(nodeFacts, allRules, directiveLib, groupLib, allParameters, nodeConfigCaches)
      /////
      ///// end of inputs, all information gathered for promise generation.
      /////

      /////
      ///// Generate the root file with all certificate. This could be done in the node lifecycle management.
      ///// For now, it's just a trigger: the generation is async and doesn't fail policy generation.
      ///// File is: /var/rudder/lib/ssl/allnodescerts.pem
      _ = writeCertificatesPemService.writeCertificatesPem(nodeFacts)

      ///// parse rule for directive parameters and build node context that will be used for them
      ///// also restrict generation to only active rules & nodes:
      ///// - number of nodes: only node somehow targeted by a rule have to be considered.
      ///// - number of rules: any rule without target or with only target with no node can be skipped

      ruleValTime0                             <- currentTimeMillis
      arePolicyServers                          = nodeFacts.view.mapValues(_.rudderSettings.isPolicyServer).toMap
      activeRuleIds                             = getAppliedRuleIds(allRules, groupLib, directiveLib, arePolicyServers)
      ruleVals                                 <- buildRuleVals(
                                                    activeRuleIds,
                                                    allRules.toList,
                                                    directiveLib,
                                                    groupLib,
                                                    arePolicyServers
                                                  ).chainError("Cannot build Rule vals")
      ruleValTime1                             <- currentTimeMillis
      timeRuleVal                               = ruleValTime1 - ruleValTime0
      _                                        <- PolicyGenerationLoggerPure.timing.debug(
                                                    s"RuleVals built in ${timeRuleVal} ms, run rule vals post hooks"
                                                  )
      (th, hookRes)                            <- ruleValGeneratedHookService.runHooks(ruleVals).timed
      _                                        <- PolicyGenerationLoggerPure.timing.debug(
                                                    s"RuleVals post hook in ${th.toMillis} ms, start to expand their values."
                                                  )
      nodeContextsTime                         <- currentTimeMillis
      activeNodeIds                             = ruleVals.foldLeft(Set[NodeId]()) { case (s, r) => s ++ r.nodeIds }
      NodesContextResult(nodeContexts, errors) <-
        nodeContextService
          .getNodeContexts(
            activeNodeIds,
            nodeFacts,
            mergedProps,
            groupLib,
            allParameters.toList,
            globalAgentRun,
            globalComplianceMode,
            globalPolicyMode
          )
          .chainError("Could not get node interpolation context")
      timeNodeContexts                         <- currentTimeMillis
      _                                        <- PolicyGenerationLoggerPure.timing.debug(
                                                    s"Node contexts built in ${timeNodeContexts - nodeContextsTime} ms, start to build new node configurations."
                                                  )
    } yield {
      FetchAllInfo(
        activeNodeIds,
        ruleVals,
        nodeContexts,
        allNodeModes,
        scriptEngineEnabled,
        globalPolicyMode,
        nodeConfigCaches,
        timeFetchAll,
        timeRuleVal,
        errors,
        maxParallelism,
        jsTimeout,
        generationContinueOnError,
        schedules
      )
    }
  }

}

/*
 * For now, we only have ONE directive schedule, and it's a daily one during the night.
 */
object SystemDirectiveSchedule {

  val dailyOn4UTC = DirectiveSchedule(
    CampaignInfo(
      CampaignId("rudder-daily-on-4-utc"),
      "Rudder system daily directive schedule",
      "A daily schedule used by Rudder infrequent checks",
      com.normation.rudder.campaigns.Enabled,
      Daily(Time(4, 0), Time(6, 0), Some(ScheduleTimeZone("UTC")))
    )
  )
}
