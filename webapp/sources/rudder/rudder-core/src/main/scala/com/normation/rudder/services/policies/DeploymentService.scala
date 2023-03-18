/*
 *************************************************************************************
 * Copyright 2011 Normation SAS
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

package com.normation.rudder.services.policies

import better.files.File
import cats.data.NonEmptyList
import cats.implicits._
import com.normation.box._
import com.normation.cfclerk.domain.ReportingLogic.FocusReport
import com.normation.cfclerk.domain.SectionSpec
import com.normation.cfclerk.domain.TechniqueName
import com.normation.errors._
import com.normation.inventory.domain.AixOS
import com.normation.inventory.domain.MemorySize
import com.normation.inventory.domain.NodeId
import com.normation.inventory.services.core.ReadOnlyFullInventoryRepository
import com.normation.rudder.batch.UpdateDynamicGroups
import com.normation.rudder.configuration.ConfigurationRepository
import com.normation.rudder.domain.Constants
import com.normation.rudder.domain.appconfig.FeatureSwitch
import com.normation.rudder.domain.logger.ComplianceDebugLogger
import com.normation.rudder.domain.logger.PolicyGenerationLogger
import com.normation.rudder.domain.logger.PolicyGenerationLoggerPure
import com.normation.rudder.domain.logger.TimingDebugLogger
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.domain.nodes.NodeState
import com.normation.rudder.domain.policies._
import com.normation.rudder.domain.properties._
import com.normation.rudder.domain.properties.GenericProperty._
import com.normation.rudder.domain.reports.BlockExpectedReport
import com.normation.rudder.domain.reports.ComponentExpectedReport
import com.normation.rudder.domain.reports.DirectiveExpectedReports
import com.normation.rudder.domain.reports.ExpectedValueId
import com.normation.rudder.domain.reports.ExpectedValueMatch
import com.normation.rudder.domain.reports.NodeConfigId
import com.normation.rudder.domain.reports.NodeExpectedReports
import com.normation.rudder.domain.reports.NodeModeConfig
import com.normation.rudder.domain.reports.OverridenPolicy
import com.normation.rudder.domain.reports.RuleExpectedReports
import com.normation.rudder.domain.reports.ValueExpectedReport
import com.normation.rudder.hooks.HookEnvPairs
import com.normation.rudder.hooks.Hooks
import com.normation.rudder.hooks.HooksLogger
import com.normation.rudder.hooks.RunHooks
import com.normation.rudder.reports.AgentRunInterval
import com.normation.rudder.reports.AgentRunIntervalService
import com.normation.rudder.reports.ComplianceMode
import com.normation.rudder.reports.ComplianceModeService
import com.normation.rudder.reports.GlobalComplianceMode
import com.normation.rudder.reports.HeartbeatConfiguration
import com.normation.rudder.repository._
import com.normation.rudder.services.nodes.MergeNodeProperties
import com.normation.rudder.services.nodes.NodeInfoService
import com.normation.rudder.services.policies.nodeconfig.FileBasedNodeConfigurationHashRepository
import com.normation.rudder.services.policies.nodeconfig.NodeConfigurationHash
import com.normation.rudder.services.policies.nodeconfig.NodeConfigurationHashRepository
import com.normation.rudder.services.policies.write.PolicyWriterService
import com.normation.rudder.services.reports.CachedFindRuleNodeStatusReports
import com.normation.rudder.services.reports.CachedNodeConfigurationService
import com.normation.rudder.services.reports.CacheExpectedReportAction
import com.normation.rudder.utils.ParseMaxParallelism
import com.normation.utils.Control._
import com.softwaremill.quicklens._
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import net.liftweb.common._
import net.liftweb.json.JsonAST.JArray
import net.liftweb.json.JsonAST.JObject
import net.liftweb.json.JsonAST.JString
import net.liftweb.json.JsonAST.JValue
import org.joda.time.DateTime
import org.joda.time.Period
import org.joda.time.format.ISODateTimeFormat
import org.joda.time.format.PeriodFormatterBuilder
import scala.collection.immutable.Map
import scala.concurrent.duration.FiniteDuration
import zio.{System => _, _}
import zio.syntax._

/**
 * A deployment hook is a class that accept callbacks.
 */
trait PromiseGenerationHooks {

  /*
   * Hooks to call before deployment start.
   * This one is synchronise, so that deployment will
   * wait for its completion AND success.
   * So
   */
  def beforeDeploymentSync(generationTime: DateTime): Box[Unit]
}

/**
 * Define how a node should be sorted in the list of nodes.
 * We want root first, then relay with node just above, then relay, then nodes
 */
object NodePriority {
  def apply(nodeInfo: NodeInfo): Int = {
    if (nodeInfo.id.value == "root") 0
    else if (nodeInfo.isPolicyServer) {
      if (nodeInfo.policyServerId.value == "root") 1 else 2
    } else 3
  }
}

/**
 * The main service which deploy modified rules and
 * their dependencies.
 */
trait PromiseGenerationService {

  /**
   * All mighy method that take all modified rules, find their
   * dependencies, proccess ${vars}, build the list of node to update,
   * update nodes.
   *
   * Return the list of node IDs actually updated.
   *
   */
  def deploy(): Box[Set[NodeId]] = {
    PolicyGenerationLogger.info("Start policy generation, checking updated rules")

    val initialTime = System.currentTimeMillis

    val generationTime = new DateTime(initialTime)
    val rootNodeId     = Constants.ROOT_POLICY_SERVER_ID
    // we need to add the current environment variables to the script context
    // plus the script environment variables used as script parameters
    import scala.jdk.CollectionConverters._
    val systemEnv      = HookEnvPairs.build(System.getenv.asScala.toSeq: _*)

    /*
     * The computation of dynamic group is a workaround inconsistencies after importing definition
     * from LDAP, see: https://issues.rudder.io/issues/14758
     * But that computation may lead to a huge delay in generation (tens of minutes). We want to be
     * able to unset their computation in some case through an environement variable.
     *
     * To disable it, set environment variable "rudder.generation.computeDynGroup" to "disabled"
     */
    val computeDynGroupsEnabled = getComputeDynGroups().getOrElse(true)

    val maxParallelism = ParseMaxParallelism(
      getMaxParallelism().getOrElse("x0.5"),
      1,
      "rudder_generation_max_parallelism",
      (s: String) => PolicyGenerationLogger.warn(s)
    )

    val jsTimeout = {
      // by default 5s but can be overrided
      val t = getJsTimeout().getOrElse(5)
      FiniteDuration(
        try {
          Math.max(1, t.toLong) // must be a positive number
        } catch {
          case ex: NumberFormatException =>
            PolicyGenerationLogger.error(s"Impossible to user property '${t}' for js engine timeout: not a number")
            5L // default
        },
        TimeUnit.SECONDS
      )
    }

    val generationContinueOnError = getGenerationContinueOnError().getOrElse(false)

    PolicyGenerationLogger.debug(
      s"Policy generation parallelism set to: ${maxParallelism} (change with REST API settings parameter 'rudder_generation_max_parallelism')"
    )
    PolicyGenerationLogger.debug(
      s"Policy generation JS evaluation of directive parameter timeout: ${jsTimeout} s (change with REST API settings parameter 'rudder_generation_jsTimeout')"
    )
    PolicyGenerationLogger.debug(
      s"Policy generation continues on NodeConfigurations evaluation: ${generationContinueOnError} (change with REST API settings parameter 'rudder_generation_continue_on_error')"
    )

    val result = for {
      // trigger a dynamic group update
      _                 <- if (computeDynGroupsEnabled) {
                             triggerNodeGroupUpdate()
                           } else {
                             PolicyGenerationLogger.warn(
                               s"Computing dynamic groups disable by REST API settings 'rudder_generation_compute_dyngroups'"
                             )
                             Full(())
                           }
      timeComputeGroups  = (System.currentTimeMillis - initialTime)
      _                  = PolicyGenerationLogger.timing.debug(s"Computing dynamic groups finished in ${timeComputeGroups} ms")
      preGenHooksTime    = System.currentTimeMillis
      _                 <- runPreHooks(generationTime, systemEnv)
      timeRunPreGenHooks = (System.currentTimeMillis - preGenHooksTime)
      _                  = PolicyGenerationLogger.timing.debug(s"Pre-policy-generation scripts hooks ran in ${timeRunPreGenHooks} ms")

      codePreGenHooksTime = System.currentTimeMillis
      _                  <- beforeDeploymentSync(generationTime)
      timeCodePreGenHooks = (System.currentTimeMillis - codePreGenHooksTime)
      _                   = PolicyGenerationLogger.timing.debug(
                              s"Pre-policy-generation modules hooks in ${timeCodePreGenHooks} ms, start getting all generation related data."
                            )

      // Objects need to be created in separate for {} yield to be garbage collectable,
      // otherwise they remain in the scope and are not freed.
      // We need to do that as many time as necessary to free memory during policy generation

      fetch0Time       = System.currentTimeMillis
      (
        updatedNodesId,
        updatedNodeInfo,
        expectedReports,
        allErrors,
        errorNodes,
        timeFetchAll,
        timeRuleVal,
        timeBuildConfig,
        timeWriteNodeConfig,
        timeSetExpectedReport
      )               <- for {
                           (
                             updatedNodeConfigIds,
                             allNodeConfigurations,
                             allNodeConfigsInfos,
                             updatedNodesId,
                             updatedNodeInfo,
                             globalPolicyMode,
                             allNodeModes,
                             allErrors,
                             errorNodes,
                             timeFetchAll,
                             timeRuleVal,
                             timeBuildConfig
                           ) <- for {
                                  (
                                    activeNodeIds,
                                    ruleVals,
                                    nodeContexts,
                                    allNodeModes,
                                    scriptEngineEnabled,
                                    globalPolicyMode,
                                    nodeConfigCaches,
                                    timeFetchAll,
                                    timeRuleVal,
                                    errors
                                  )                    <- for {
                                                            allRules             <- findDependantRules() ?~! "Could not find dependant rules"
                                                            fetch1Time            = System.currentTimeMillis
                                                            _                     = PolicyGenerationLogger.timing.trace(s"Fetched rules in ${fetch1Time - fetch0Time} ms")
                                                            allNodeInfos         <- getAllNodeInfos().map(_.filter {
                                                                                      case (_, n) =>
                                                                                        if (n.state == NodeState.Ignored) {
                                                                                          PolicyGenerationLogger.debug(
                                                                                            s"Skipping node '${n.id.value}' because the node is in state '${n.state.name}'"
                                                                                          )
                                                                                          false
                                                                                        } else true
                                                                                    }) ?~! "Could not get Node Infos" // disabled node don't get new policies
                                                            fetch2Time            = System.currentTimeMillis
                                                            _                     = PolicyGenerationLogger.timing.trace(s"Fetched node infos in ${fetch2Time - fetch1Time} ms")
                                                            directiveLib         <-
                                                              getDirectiveLibrary(allRules.flatMap(_.directiveIds).toSet) ?~! "Could not get the directive library"
                                                            fetch3Time            = System.currentTimeMillis
                                                            _                     = PolicyGenerationLogger.timing.trace(s"Fetched directives in ${fetch3Time - fetch2Time} ms")
                                                            groupLib             <- getGroupLibrary() ?~! "Could not get the group library"
                                                            fetch4Time            = System.currentTimeMillis
                                                            _                     = PolicyGenerationLogger.timing.trace(s"Fetched groups in ${fetch4Time - fetch3Time} ms")
                                                            allParameters        <- getAllGlobalParameters ?~! "Could not get global parameters"
                                                            fetch5Time            = System.currentTimeMillis
                                                            _                     = PolicyGenerationLogger.timing.trace(s"Fetched global parameters in ${fetch5Time - fetch4Time} ms")
                                                            globalAgentRun       <- getGlobalAgentRun()
                                                            fetch6Time            = System.currentTimeMillis
                                                            _                     = PolicyGenerationLogger.timing.trace(s"Fetched run infos in ${fetch6Time - fetch5Time} ms")
                                                            scriptEngineEnabled  <-
                                                              getScriptEngineEnabled() ?~! "Could not get if we should use the script engine to evaluate directive parameters"
                                                            globalComplianceMode <- getGlobalComplianceMode()
                                                            globalPolicyMode     <- getGlobalPolicyMode() ?~! "Cannot get the Global Policy Mode (Enforce or Verify)"
                                                            nodeConfigCaches     <- getNodeConfigurationHash() ?~! "Cannot get the Configuration Cache"
                                                            allNodeModes          = buildNodeModes(allNodeInfos, globalComplianceMode, globalAgentRun, globalPolicyMode)
                                                            timeFetchAll          = (System.currentTimeMillis - fetch0Time)
                                                            _                     = PolicyGenerationLogger.timing.debug(s"All relevant information fetched in ${timeFetchAll} ms.")

                                                            _ = logMetrics(allNodeInfos, allRules, directiveLib, groupLib, allParameters, nodeConfigCaches)
                                                            /////
                                                            ///// end of inputs, all information gathered for promise generation.
                                                            /////

                                                            /////
                                                            ///// Generate the root file with all certificate. This could be done in the node lifecycle management.
                                                            ///// For now, it's just a trigger: the generation is async and doesn't fail policy generation.
                                                            ///// File is: /var/rudder/lib/ssl/allnodescerts.pem
                                                            _ = writeCertificatesPem(allNodeInfos)

                                                            ///// parse rule for directive parameters and build node context that will be used for them
                                                            ///// also restrict generation to only active rules & nodes:
                                                            ///// - number of nodes: only node somehow targetted by a rule have to be considered.
                                                            ///// - number of rules: any rule without target or with only target with no node can be skipped

                                                            ruleValTime   = System.currentTimeMillis
                                                            activeRuleIds = getAppliedRuleIds(allRules, groupLib, directiveLib, allNodeInfos)
                                                            ruleVals     <- buildRuleVals(
                                                                              activeRuleIds,
                                                                              allRules,
                                                                              directiveLib,
                                                                              groupLib,
                                                                              allNodeInfos
                                                                            ) ?~! "Cannot build Rule vals"
                                                            timeRuleVal   = (System.currentTimeMillis - ruleValTime)
                                                            _             = PolicyGenerationLogger.timing.debug(
                                                                              s"RuleVals built in ${timeRuleVal} ms, start to expand their values."
                                                                            )

                                                            nodeContextsTime                          = System.currentTimeMillis
                                                            activeNodeIds                             = ruleVals.foldLeft(Set[NodeId]()) { case (s, r) => s ++ r.nodeIds }
                                                            NodesContextResult(nodeContexts, errors) <-
                                                              getNodeContexts(
                                                                activeNodeIds,
                                                                allNodeInfos,
                                                                groupLib,
                                                                allParameters.toList,
                                                                globalAgentRun,
                                                                globalComplianceMode,
                                                                globalPolicyMode
                                                              ) ?~! "Could not get node interpolation context"
                                                            timeNodeContexts                          = (System.currentTimeMillis - nodeContextsTime)
                                                            _                                         = PolicyGenerationLogger.timing.debug(
                                                                                                          s"Node contexts built in ${timeNodeContexts} ms, start to build new node configurations."
                                                                                                        )
                                                          } yield {
                                                            (
                                                              activeNodeIds,
                                                              ruleVals,
                                                              nodeContexts,
                                                              allNodeModes,
                                                              scriptEngineEnabled,
                                                              globalPolicyMode,
                                                              nodeConfigCaches,
                                                              timeFetchAll,
                                                              timeRuleVal,
                                                              errors
                                                            )
                                                          }
                                  buildConfigTime       = System.currentTimeMillis
                                  /// here, we still have directive by directive info
                                  filteredTechniques    = getFilteredTechnique()
                                  configsAndErrors     <- buildNodeConfigurations(
                                                            activeNodeIds,
                                                            ruleVals,
                                                            nodeContexts,
                                                            allNodeModes,
                                                            filteredTechniques,
                                                            scriptEngineEnabled,
                                                            globalPolicyMode,
                                                            maxParallelism,
                                                            jsTimeout,
                                                            generationContinueOnError
                                                          ) ?~! "Cannot build target configuration node"
                                  /// only keep successfull node config. We will keep the failed one to fail the whole process in the end if needed
                                  allNodeConfigurations = configsAndErrors.ok.map(c => (c.nodeInfo.id, c)).toMap
                                  allErrors             = configsAndErrors.errors.map(_.fullMsg) ++ errors.values
                                  errorNodes            = activeNodeIds -- allNodeConfigurations.keySet

                                  timeBuildConfig = (System.currentTimeMillis - buildConfigTime)
                                  _               = PolicyGenerationLogger.timing.debug(
                                                      s"Node's target configuration built in ${timeBuildConfig} ms, start to update rule values."
                                                    )

                                  allNodeConfigsInfos = allNodeConfigurations.map { case (nodeid, nodeconfig) => (nodeid, nodeconfig.nodeInfo) }
                                  allNodeConfigsId    = allNodeConfigsInfos.keysIterator.toSet

                                  updatedNodeConfigIds = getNodesConfigVersion(allNodeConfigurations, nodeConfigCaches, generationTime)
                                  updatedNodesId       = updatedNodeConfigIds.keySet.toSeq.toSet
                                  updatedNodeInfo      = allNodeConfigurations.collect {
                                                           case (nodeId, nodeconfig) if (updatedNodesId.contains(nodeId)) =>
                                                             (nodeId, nodeconfig.nodeInfo)
                                                         }

                                  // WHY DO WE NEED TO FORGET OTHER NODES CACHE INFO HERE ?
                                  _                   <- forgetOtherNodeConfigurationState(allNodeConfigsId) ?~! "Cannot clean the configuration cache"

                                } yield {
                                  (
                                    updatedNodeConfigIds,
                                    allNodeConfigurations,
                                    allNodeConfigsInfos,
                                    updatedNodesId,
                                    updatedNodeInfo,
                                    globalPolicyMode,
                                    allNodeModes,
                                    allErrors,
                                    errorNodes,
                                    timeFetchAll,
                                    timeRuleVal,
                                    timeBuildConfig
                                  )
                                }
                           ///// so now we have everything for each updated nodes, we can start writing node policies and then expected reports

                           writeTime           = System.currentTimeMillis
                           _                  <- writeNodeConfigurations(
                                                   rootNodeId,
                                                   updatedNodeConfigIds,
                                                   allNodeConfigurations,
                                                   allNodeConfigsInfos,
                                                   globalPolicyMode,
                                                   generationTime,
                                                   maxParallelism
                                                 ) ?~! "Cannot write nodes configuration"
                           timeWriteNodeConfig = (System.currentTimeMillis - writeTime)
                           _                   = PolicyGenerationLogger.timing.debug(
                                                   s"Node configuration written in ${timeWriteNodeConfig} ms, start to update expected reports."
                                                 )

                           reportTime            = System.currentTimeMillis
                           expectedReports       = computeExpectedReports(allNodeConfigurations, updatedNodeConfigIds, generationTime, allNodeModes)
                           timeSetExpectedReport = (System.currentTimeMillis - reportTime)
                           _                     = PolicyGenerationLogger.timing.debug(s"Reports computed in ${timeSetExpectedReport} ms")
                         } yield {
                           (
                             updatedNodesId,
                             updatedNodeInfo,
                             expectedReports,
                             allErrors,
                             errorNodes,
                             timeFetchAll,
                             timeRuleVal,
                             timeBuildConfig,
                             timeWriteNodeConfig,
                             timeSetExpectedReport
                           )
                         }
      saveExpectedTime = System.currentTimeMillis
      _               <- saveExpectedReports(expectedReports) ?~! "Error when saving expected reports"
      timeSaveExpected = (System.currentTimeMillis - saveExpectedTime)
      _                = PolicyGenerationLogger.timing.debug(s"Node expected reports saved in base in ${timeSaveExpected} ms.")

      // invalidate compliance caches
      invalidationActions = expectedReports.map(x => (x.nodeId, CacheExpectedReportAction.UpdateNodeConfiguration(x.nodeId, x)))
      _                  <- invalidateComplianceCache(invalidationActions).toBox

      // finally, run post-generation hooks. They can lead to an error message for build, but node policies are updated
      postHooksTime       = System.currentTimeMillis
      _                  <- runPostHooks(generationTime, new DateTime(postHooksTime), updatedNodeInfo, systemEnv, UPDATED_NODE_IDS_PATH)
      timeRunPostGenHooks = (System.currentTimeMillis - postHooksTime)
      _                   = PolicyGenerationLogger.timing.debug(s"Post-policy-generation hooks ran in ${timeRunPostGenHooks} ms")

      /// now, if there was failed config or failed write, time to show them

      _       = {
        PolicyGenerationLogger.timing.info("Timing summary:")
        PolicyGenerationLogger.timing.info("Run pre-gen scripts hooks     : %10s ms".format(timeRunPreGenHooks))
        PolicyGenerationLogger.timing.info("Run pre-gen modules hooks     : %10s ms".format(timeCodePreGenHooks))
        PolicyGenerationLogger.timing.info("Fetch all information         : %10s ms".format(timeFetchAll))
        PolicyGenerationLogger.timing.info("Build current rule values     : %10s ms".format(timeRuleVal))
        PolicyGenerationLogger.timing.info("Build target configuration    : %10s ms".format(timeBuildConfig))
        PolicyGenerationLogger.timing.info("Write node configurations     : %10s ms".format(timeWriteNodeConfig))
        PolicyGenerationLogger.timing.info("Save expected reports         : %10s ms".format(timeSetExpectedReport))
        PolicyGenerationLogger.timing.info("Run post generation hooks     : %10s ms".format(timeRunPostGenHooks))
        PolicyGenerationLogger.timing.info("Number of nodes updated       : %10s   ".format(updatedNodesId.size))
        if (errorNodes.nonEmpty) {
          PolicyGenerationLogger.warn(s"Nodes in errors (${errorNodes.size}): '${errorNodes.map(_.value).mkString("','")}'")
        }
      }
      result <- if (allErrors.isEmpty) {
                  Full(updatedNodesId)
                } else {
                  Failure(s"Generation ended but some nodes were in errors: ${allErrors.mkString(" ; ")}")
                }
    } yield {
      result
    }
    // if result is a failure, execute policy generation failure hooks
    result match {
      case f: Failure =>
        // in case of a failed generation, run corresponding hooks
        val failureHooksTime       = System.currentTimeMillis
        val exceptionInfo          = f.rootExceptionCause
          .map(ex => s"\n\nException linked to failure: ${ex.getMessage}\n  ${ex.getStackTrace.map(_.toString).mkString("\n  ")}")
          .getOrElse("")
        runFailureHooks(
          generationTime,
          new DateTime(failureHooksTime),
          systemEnv,
          f.messageChain + exceptionInfo,
          GENERATION_FAILURE_MSG_PATH
        ) match {
          case f: Failure =>
            PolicyGenerationLogger.error(s"Failure when executing policy generation failure hooks: ${f.messageChain}")
          case _ => //
        }
        val timeRunFailureGenHooks = (System.currentTimeMillis - failureHooksTime)
        PolicyGenerationLogger.timing.debug(s"Generation-failure hooks ran in ${timeRunFailureGenHooks} ms")
      case _ => //
    }
    val completion = if (result.isDefined) "succeeded in" else "failed after"
    PolicyGenerationLogger.timing.info(
      s"Policy generation ${completion}: %10s".format(periodFormatter.print(new Period(System.currentTimeMillis - initialTime)))
    )
    result
  }
  private[this] val periodFormatter = {
    new PeriodFormatterBuilder()
      .appendDays()
      .appendSuffix(" day", " days")
      .appendSeparator(" ")
      .appendHours()
      .appendSuffix(" hour", " hours")
      .appendSeparator(" ")
      .appendMinutes()
      .appendSuffix(" min", " min")
      .appendSeparator(" ")
      .appendSeconds()
      .appendSuffix(" s", " s")
      .toFormatter()
  }

  /**
   * Snapshot all information needed:
   * - node infos
   * - rules
   * - directives library
   * - groups library
   */
  def getAllNodeInfos(): Box[Map[NodeId, NodeInfo]]
  // get full active technique category, checking that:
  // - all ids in parameter are in it,
  // - filtering out other directives (and pruning relevant branches).
  def getDirectiveLibrary(ids: Set[DirectiveId]): Box[FullActiveTechniqueCategory]
  def getGroupLibrary():            Box[FullNodeGroupCategory]
  def getAllGlobalParameters:       Box[Seq[GlobalParameter]]
  def getGlobalComplianceMode():    Box[GlobalComplianceMode]
  def getGlobalAgentRun():          Box[AgentRunInterval]
  def getScriptEngineEnabled:       () => Box[FeatureSwitch]
  def getGlobalPolicyMode:          () => Box[GlobalPolicyMode]
  def getComputeDynGroups:          () => Box[Boolean]
  def getMaxParallelism:            () => Box[String]
  def getJsTimeout:                 () => Box[Int]
  def getGenerationContinueOnError: () => Box[Boolean]
  def writeCertificatesPem(allNodeInfos: Map[NodeId, NodeInfo]): Unit

  /**
   * This method logs interesting metrics that can be use to assess performance problem.
   */
  def logMetrics(
      allNodeInfos:     Map[NodeId, NodeInfo],
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
    val n   = allNodeInfos.size
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

  // Trigger dynamic group update
  def triggerNodeGroupUpdate(): Box[Unit]

  // code hooks
  def beforeDeploymentSync(generationTime: DateTime): Box[Unit]

  // base folder for hooks. It's a string because there is no need to get it from config
  // file, it's just a constant.
  def HOOKS_D:                     String
  def HOOKS_IGNORE_SUFFIXES:       List[String]
  def UPDATED_NODE_IDS_PATH:       String
  def GENERATION_FAILURE_MSG_PATH: String

  /*
   * From global configuration and node modes, build node modes
   */
  def buildNodeModes(
      nodes:                Map[NodeId, NodeInfo],
      globalComplianceMode: GlobalComplianceMode,
      globalAgentRun:       AgentRunInterval,
      globalPolicyMode:     GlobalPolicyMode
  ) = {
    nodes.map {
      case (id, info) =>
        (
          id,
          NodeModeConfig(
            globalComplianceMode,
            info.nodeReportingConfiguration.heartbeatConfiguration match {
              case Some(HeartbeatConfiguration(true, i)) => Some(i)
              case _                                     => None
            },
            globalAgentRun,
            info.nodeReportingConfiguration.agentRunInterval,
            globalPolicyMode,
            info.policyMode
          )
        )
    }.toMap
  }

  def getAppliedRuleIds(
      rules:        Seq[Rule],
      groupLib:     FullNodeGroupCategory,
      directiveLib: FullActiveTechniqueCategory,
      allNodeInfos: Map[NodeId, NodeInfo]
  ): Set[RuleId]

  /**
   * Get all rules, including system ones
   */
  def findDependantRules(): Box[Seq[Rule]]

  /**
   * Rule vals are just rules with a analysis of parameter
   * on directive done, so that we will be able to bind them
   * to a context latter.
   */
  def buildRuleVals(
      activesRules: Set[RuleId],
      rules:        Seq[Rule],
      directiveLib: FullActiveTechniqueCategory,
      groupLib:     FullNodeGroupCategory,
      allNodeInfos: Map[NodeId, NodeInfo]
  ): Box[Seq[RuleVal]]

  def getNodeContexts(
      nodeIds:              Set[NodeId],
      allNodeInfos:         Map[NodeId, NodeInfo],
      allGroups:            FullNodeGroupCategory,
      globalParameters:     List[GlobalParameter],
      globalAgentRun:       AgentRunInterval,
      globalComplianceMode: ComplianceMode,
      globalPolicyMode:     GlobalPolicyMode
  ): Box[NodesContextResult]

  /*
   * Get a list of filtered out technique by node, for special cases
   */
  def getFilteredTechnique(): Map[NodeId, List[TechniqueName]]

  /*
   * From a list of ruleVal, find the list of all impacted nodes
   * with the actual PolicyBean they will have.
   * Replace all ${node.varName} vars.
   */
  def buildNodeConfigurations(
      activeNodeIds:             Set[NodeId],
      ruleVals:                  Seq[RuleVal],
      nodeContexts:              Map[NodeId, InterpolationContext],
      allNodeModes:              Map[NodeId, NodeModeConfig],
      filteredTechniques:        Map[NodeId, List[TechniqueName]],
      scriptEngineEnabled:       FeatureSwitch,
      globalPolicyMode:          GlobalPolicyMode,
      maxParallelism:            Int,
      jsTimeout:                 FiniteDuration,
      generationContinueOnError: Boolean
  ): Box[NodeConfigurations]

  /**
   * Forget all other node configuration state.
   * If passed with an empty set, actually forget all node configuration.
   */
  def forgetOtherNodeConfigurationState(keep: Set[NodeId]): Box[Set[NodeId]]

  /**
   * Get the actual cached values for NodeConfiguration
   */
  def getNodeConfigurationHash(): Box[Map[NodeId, NodeConfigurationHash]]

  /**
   * For each nodeConfiguration, check if the config is updated.
   * If so, return the new configId.
   */
  def getNodesConfigVersion(
      allNodeConfigs: Map[NodeId, NodeConfiguration],
      hashes:         Map[NodeId, NodeConfigurationHash],
      generationTime: DateTime
  ): Map[NodeId, NodeConfigId]

  /**
   * Actually  write the new configuration for the list of given node.
   * If the node target configuration is the same as the actual, nothing is done.
   * Else, promises are generated;
   * Return the list of configuration successfully written.
   */
  def writeNodeConfigurations(
      rootNodeId:       NodeId,
      updated:          Map[NodeId, NodeConfigId],
      allNodeConfig:    Map[NodeId, NodeConfiguration],
      allNodeInfos:     Map[NodeId, NodeInfo],
      globalPolicyMode: GlobalPolicyMode,
      generationTime:   DateTime,
      maxParallelism:   Int
  ): Box[Set[NodeId]]

  /**
   * Compute expected reports for each node
   * (that does not save them in base)
   */
  def computeExpectedReports(
      allNodeConfigurations: Map[NodeId, NodeConfiguration],
      updatedId:             Map[NodeId, NodeConfigId],
      generationTime:        DateTime,
      allNodeModes:          Map[NodeId, NodeModeConfig]
  ): List[NodeExpectedReports]

  /**
   * Save expected reports in database
   */
  def saveExpectedReports(
      expectedReports: List[NodeExpectedReports]
  ): Box[Seq[NodeExpectedReports]]

  /**
   * After updates of everything, notify expected reports and compliance cache
   * that it should forget what it knows about the updated nodes
   */
  def invalidateComplianceCache(actions: Seq[(NodeId, CacheExpectedReportAction)]): IOResult[Unit]

  /**
   * Run pre generation hooks
   */
  def runPreHooks(generationTime: DateTime, systemEnv: HookEnvPairs): Box[Unit]

  /**
   * Run post generation hooks
   */
  def runPostHooks(
      generationTime:    DateTime,
      endTime:           DateTime,
      idToConfiguration: Map[NodeId, NodeInfo],
      systemEnv:         HookEnvPairs,
      nodeIdsPath:       String
  ): Box[Unit]

  /**
   * Run failure hook
   */
  def runFailureHooks(
      generationTime:   DateTime,
      endTime:          DateTime,
      systemEnv:        HookEnvPairs,
      errorMessage:     String,
      errorMessagePath: String
  ): Box[Unit]
}

///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//  Implémentation
///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

class PromiseGenerationServiceImpl(
    override val roRuleRepo:                        RoRuleRepository,
    override val woRuleRepo:                        WoRuleRepository,
    override val ruleValService:                    RuleValService,
    override val systemVarService:                  SystemVariableService,
    override val nodeConfigurationService:          NodeConfigurationHashRepository,
    override val nodeInfoService:                   NodeInfoService,
    override val confExpectedRepo:                  UpdateExpectedReportsRepository,
    override val roNodeGroupRepository:             RoNodeGroupRepository,
    override val roDirectiveRepository:             RoDirectiveRepository,
    override val configurationRepository:           ConfigurationRepository,
    override val ruleApplicationStatusService:      RuleApplicationStatusService,
    override val parameterService:                  RoParameterService,
    override val interpolatedValueCompiler:         InterpolatedValueCompiler,
    override val roInventoryRepository:             ReadOnlyFullInventoryRepository,
    override val complianceModeService:             ComplianceModeService,
    override val agentRunService:                   AgentRunIntervalService,
    override val complianceCache:                   CachedFindRuleNodeStatusReports,
    override val promisesFileWriterService:         PolicyWriterService,
    override val writeNodeCertificatesPem:          WriteNodeCertificatesPem,
    override val cachedNodeConfigurationService:    CachedNodeConfigurationService,
    override val getScriptEngineEnabled:            () => Box[FeatureSwitch],
    override val getGlobalPolicyMode:               () => Box[GlobalPolicyMode],
    override val getComputeDynGroups:               () => Box[Boolean],
    override val getMaxParallelism:                 () => Box[String],
    override val getJsTimeout:                      () => Box[Int],
    override val getGenerationContinueOnError:      () => Box[Boolean],
    override val HOOKS_D:                           String,
    override val HOOKS_IGNORE_SUFFIXES:             List[String],
    override val UPDATED_NODE_IDS_PATH:             String,
    override val postGenerationHookCompabilityMode: Option[Boolean],
    override val GENERATION_FAILURE_MSG_PATH:       String,
    override val allNodeCertificatesPemFile:        File,
    override val isPostgresqlLocal:                 Boolean
) extends PromiseGenerationService with PromiseGeneration_performeIO with PromiseGeneration_NodeCertificates
    with PromiseGeneration_BuildNodeContext with PromiseGeneration_buildRuleVals with PromiseGeneration_buildNodeConfigurations
    with PromiseGeneration_updateAndWriteRule with PromiseGeneration_setExpectedReports with PromiseGeneration_Hooks {

  private[this] var dynamicsGroupsUpdate: Option[UpdateDynamicGroups] = None

  // We need to register the update dynamic group after instantiation of the class
  // as the deployment service, the async deployment and the dynamic group update have cyclic dependencies
  def setDynamicsGroupsService(updateDynamicGroups: UpdateDynamicGroups): Unit = {
    this.dynamicsGroupsUpdate = Some(updateDynamicGroups)
  }

  override def triggerNodeGroupUpdate(): Box[Unit] = {
    dynamicsGroupsUpdate.map(groupUpdate(_)).getOrElse(Failure("Dynamic group update is not registered, this is an error"))

  }
  private[this] def groupUpdate(updateDynamicGroups: UpdateDynamicGroups): Box[Unit] = {
    // Trigger a manual update if one is not pending (otherwise it goes in infinit loop)
    // It doesn't expose anything about its ending, so we need to wait for the update to be idle
    if (updateDynamicGroups.isIdle()) {
      updateDynamicGroups.startManualUpdate
      // wait for it to finish. We unfortunately cannot do much more than waiting
      // we do need a timeout though
      // Leave some time for actor to kick in
      Thread.sleep(50)
    }

    // wait for dyn group update to finish
    while (!updateDynamicGroups.isIdle()) {
      Thread.sleep(50)
    }

    Full(())
  }
}

///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
// Follows: traits implementing each part of the deployment service
///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

/**
 * So. There is a lot of "hidden" dependencies,
 * so for now, we just return *ALL* rule.
 *
 * It might not scale very well.
 *
 * Latter (3 years): in fact, perhaps most of the
 * time, being too smart is much more slow.
 *
 */
trait PromiseGeneration_performeIO extends PromiseGenerationService {
  def roRuleRepo:              RoRuleRepository
  def nodeInfoService:         NodeInfoService
  def roNodeGroupRepository:   RoNodeGroupRepository
  def roDirectiveRepository:   RoDirectiveRepository
  def configurationRepository: ConfigurationRepository
  def parameterService:        RoParameterService
  def roInventoryRepository:   ReadOnlyFullInventoryRepository
  def complianceModeService:   ComplianceModeService
  def agentRunService:         AgentRunIntervalService

  def interpolatedValueCompiler:    InterpolatedValueCompiler
  def systemVarService:             SystemVariableService
  def ruleApplicationStatusService: RuleApplicationStatusService
  def getGlobalPolicyMode:          () => Box[GlobalPolicyMode]

  override def findDependantRules():                       Box[Seq[Rule]]                   = roRuleRepo.getAll(true).toBox
  override def getAllNodeInfos():                          Box[Map[NodeId, NodeInfo]]       = nodeInfoService.getAll().toBox
  override def getDirectiveLibrary(ids: Set[DirectiveId]): Box[FullActiveTechniqueCategory] = {
    configurationRepository.getDirectiveLibrary(ids).toBox
  }
  override def getGroupLibrary():                          Box[FullNodeGroupCategory]       = roNodeGroupRepository.getFullGroupLibrary().toBox
  override def getAllGlobalParameters:                     Box[Seq[GlobalParameter]]        = parameterService.getAllGlobalParameters()
  override def getGlobalComplianceMode():                  Box[GlobalComplianceMode]        = complianceModeService.getGlobalComplianceMode
  override def getGlobalAgentRun():                        Box[AgentRunInterval]            = agentRunService.getGlobalAgentRun()
  override def getAppliedRuleIds(
      rules:        Seq[Rule],
      groupLib:     FullNodeGroupCategory,
      directiveLib: FullActiveTechniqueCategory,
      allNodeInfos: Map[NodeId, NodeInfo]
  ): Set[RuleId] = {
    rules
      .filter(r => {
        ruleApplicationStatusService.isApplied(r, groupLib, directiveLib, allNodeInfos) match {
          case _: AppliedStatus => true
          case _ => false
        }
      })
      .map(_.id)
      .toSet

  }
}

final case class NodesContextResult(
    ok:    Map[NodeId, InterpolationContext],
    error: Map[NodeId, String]
)

trait PromiseGeneration_BuildNodeContext {

  def interpolatedValueCompiler: InterpolatedValueCompiler
  def systemVarService:          SystemVariableService

  // we should get the context to replace the value (PureResult[String] to String)
  def parseJValue(value: JValue, context: InterpolationContext): IOResult[JValue] = {
    def rec(v: JValue): IOResult[JValue] = v match {
      case JObject(l) => ZIO.foreach(l)(field => rec(field.value).map(x => field.copy(value = x))).map(JObject(_))
      case JArray(l)  => ZIO.foreach(l)(v => rec(v)).map(JArray(_))
      case JString(s) =>
        for {
          v <- interpolatedValueCompiler
                 .compile(s)
                 .toIO
                 .chainError(s"Error when looking for interpolation variable '${s}' in node property")
          s <- v(context)
        } yield {
          JString(s)
        }
      case x          => x.succeed
    }
    rec(value)
  }

  /**
   * Build interpolation contexts.
   *
   * An interpolation context is a node-dependant
   * context for resolving ("expdanding", "binding")
   * interpolation variable in directive values.
   *
   * It's also the place where parameters are looked for
   * local overrides.
   */
  def getNodeContexts(
      nodeIds:              Set[NodeId],
      allNodeInfos:         Map[NodeId, NodeInfo],
      allGroups:            FullNodeGroupCategory,
      globalParameters:     List[GlobalParameter],
      globalAgentRun:       AgentRunInterval,
      globalComplianceMode: ComplianceMode,
      globalPolicyMode:     GlobalPolicyMode
  ): Box[NodesContextResult] = {

    /*
     * parameters have to be taken appart:
     *
     * - they can be overriden by node - not handled here, it will be in the resolution of node
     *   when implemented. Most likelly, we will have the information in the node info. And
     *   in that case, we could just use an interpolation variable
     *
     * - they can be plain string => nothing to do
     * - they can contains interpolated strings:
     *   - to node info parameters: ok
     *   - to parameters : hello loops!
     */
    def buildParams(
        parameters: List[GlobalParameter]
    ): PureResult[Map[GlobalParameter, ParamInterpolationContext => IOResult[String]]] = {
      parameters.accumulatePure { param =>
        for {
          p <- interpolatedValueCompiler
                 .compileParam(param.valueAsString)
                 .chainError(s"Error when looking for interpolation variable in global parameter '${param.name}'")
        } yield {
          (param, p)
        }
      }.map(_.toMap)
    }

    var timeNanoMergeProp = 0L
    for {
      globalSystemVariables <- systemVarService.getGlobalSystemVariables(globalAgentRun)
      parameters            <- buildParams(globalParameters).toBox ?~! "Can not parsed global parameter (looking for interpolated variables)"
    } yield {
      val all = nodeIds.foldLeft(NodesContextResult(Map(), Map())) {
        case (res, nodeId) =>
          (for {
            info              <- Box(allNodeInfos.get(nodeId)) ?~! s"Node with ID ${nodeId.value} was not found"
            policyServer      <- Box(
                                   allNodeInfos.get(info.policyServerId)
                                 ) ?~! s"Policy server '${info.policyServerId.value}' of Node '${nodeId.value}' was not found"
            context            = ParamInterpolationContext(
                                   info,
                                   policyServer,
                                   globalPolicyMode,
                                   parameters.map { case (p, i) => (p.name, i) }
                                 )
            nodeParam         <- ZIO
                                   .foreach(parameters.toList) {
                                     case (param, interpol) =>
                                       for {
                                         i <- interpol(context)
                                         v <- GenericProperty.parseValue(i).toIO
                                         p  = param.withValue(v)
                                       } yield {
                                         (p.name, p)
                                       }
                                   }
                                   .toBox
            nodeTargets        = allGroups.getTarget(info).map(_._2).toList
            timeMerge          = System.nanoTime
            mergedProps       <- MergeNodeProperties.forNode(info, nodeTargets, nodeParam.map { case (k, v) => (k, v) }.toMap).toBox
            nodeContextBefore <- systemVarService.getSystemVariables(
                                   info,
                                   allNodeInfos,
                                   nodeTargets,
                                   globalSystemVariables,
                                   globalAgentRun,
                                   globalComplianceMode: ComplianceMode
                                 )
            // Not sure if I should InterpolationContext or create a "EngineInterpolationContext
            contextEngine      = InterpolationContext(
                                   info,
                                   policyServer,
                                   globalPolicyMode,
                                   nodeContextBefore,
                                   nodeParam.map { case (k, g) => (k, g.value) }.toMap
                                 )
            _                  = { timeNanoMergeProp = timeNanoMergeProp + System.nanoTime - timeMerge }
            propsCompiled     <- ZIO
                                   .foreach(mergedProps) { p =>
                                     for {
                                       x     <- parseJValue(p.prop.toJson, contextEngine)
                                       // we need to fetch only the value, and nothing else, for the property
                                       value  = GenericProperty.fromJsonValue(x.\("value"))
                                       result = NodeProperty(p.prop.config.getString("name"), value, None, None)
                                     } yield {
                                       p.copy(prop = result)
                                     }
                                   }
                                   .toBox
            nodeInfo           = info.modify(_.node.properties).setTo(propsCompiled.map(_.prop))
            nodeContext       <- systemVarService.getSystemVariables(
                                   nodeInfo,
                                   allNodeInfos,
                                   nodeTargets,
                                   globalSystemVariables,
                                   globalAgentRun,
                                   globalComplianceMode: ComplianceMode
                                 )
            // now we set defaults global parameters to all nodes
            withDefautls      <- CompareProperties
                                   .updateProperties(
                                     nodeParam.toList.map { case (k, v) => NodeProperty(k, v.value, v.inheritMode, None) },
                                     Some(nodeInfo.properties)
                                   )
                                   .map(p => nodeInfo.modify(_.node.properties).setTo(p))
                                   .toBox
          } yield {
            (
              nodeId,
              InterpolationContext(
                withDefautls,
                policyServer,
                globalPolicyMode,
                nodeContext,
                nodeParam.map { case (k, g) => (k, g.value) }.toMap
              )
            )
          }) match {
            case eb: EmptyBox =>
              val e =
                eb ?~! s"Error while building target configuration node for node '${nodeId.value}' which is one of the target of rules. Ignoring it for the rest of the process"
              PolicyGenerationLogger.error(e.messageChain)
              res.copy(error = res.error + ((nodeId, e.messageChain)))

            case Full(x) => res.copy(ok = res.ok + x)
          }
      }
      PolicyGenerationLogger.timing.debug(
        s"Merge group properties took ${timeNanoMergeProp / (1_000_000)} ms for ${nodeIds.size} nodes"
      )
      all
    }
  }

}

///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

trait PromiseGeneration_buildRuleVals extends PromiseGenerationService {

  def ruleValService: RuleValService

  override def buildRuleVals(
      activeRuleIds: Set[RuleId],
      rules:         Seq[Rule],
      directiveLib:  FullActiveTechniqueCategory,
      allGroups:     FullNodeGroupCategory,
      allNodeInfos:  Map[NodeId, NodeInfo]
  ): Box[Seq[RuleVal]] = {

    val appliedRules = rules.filter(r => activeRuleIds.contains(r.id))
    for {
      rawRuleVals <- bestEffort(appliedRules) { rule =>
                       ruleValService.buildRuleVal(rule, directiveLib, allGroups, allNodeInfos)
                     } ?~! "Could not find configuration vals"
    } yield rawRuleVals
  }

}

///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

trait PromiseGeneration_buildNodeConfigurations extends PromiseGenerationService {

  def isPostgresqlLocal: Boolean

  override def getFilteredTechnique(): Map[NodeId, List[TechniqueName]] = {
    if (isPostgresqlLocal) {
      Map()
    } else {
      Map((Constants.ROOT_POLICY_SERVER_ID -> List(TechniqueName("rudder-service-postgresql"))))
    }
  }

  override def buildNodeConfigurations(
      activeNodeIds:             Set[NodeId],
      ruleVals:                  Seq[RuleVal],
      nodeContexts:              Map[NodeId, InterpolationContext],
      allNodeModes:              Map[NodeId, NodeModeConfig],
      filteredTechniques:        Map[NodeId, List[TechniqueName]],
      scriptEngineEnabled:       FeatureSwitch,
      globalPolicyMode:          GlobalPolicyMode,
      maxParallelism:            Int,
      jsTimeout:                 FiniteDuration,
      generationContinueOnError: Boolean
  ): Box[NodeConfigurations] = BuildNodeConfiguration.buildNodeConfigurations(
    activeNodeIds,
    ruleVals,
    nodeContexts,
    allNodeModes,
    filteredTechniques,
    scriptEngineEnabled,
    globalPolicyMode,
    maxParallelism,
    jsTimeout,
    generationContinueOnError
  )

}

final case class NodeConfigurations(
    ok:     List[NodeConfiguration],
    errors: List[RudderError]
)

object BuildNodeConfiguration extends Loggable {

  /*
   * Utility class that helps deduplicate same failures in a chain
   * of failure when using bestEffort.
   */
  implicit class DedupFailure[T](box: Box[T]) {
    def dedupFailures(failure: String, transform: String => String = identity) = {
      box match { // dedup error messages
        case Full(res) => Full(res)
        case eb: EmptyBox =>
          val msg = eb match {
            case Empty => ""
            case f: Failure => // here, dedup
              ": " + f.failureChain.map(m => transform(m.msg).trim).toSet.mkString("; ")
          }
          Failure(failure + msg)
      }
    }
  }

  final case class Counters(
      sumTimeFilter:     Ref[Long],
      sumTimeParameter:  Ref[Long],
      nbBoundDraft:      Ref[Long],
      sumTimeExpandeVar: Ref[Long],
      sumTimeEvalJs:     Ref[Long],
      nbEvalJs:          Ref[Long],
      sumTimeBoundDraft: Ref[Long],
      sumTimeMerge:      Ref[Long]
  )

  object Counters {
    def make(): UIO[Counters] = for {
      a <- Ref.make(0L)
      b <- Ref.make(0L)
      c <- Ref.make(0L)
      d <- Ref.make(0L)
      e <- Ref.make(0L)
      f <- Ref.make(0L)
      g <- Ref.make(0L)
      h <- Ref.make(0L)
    } yield Counters(a, b, c, d, e, f, g, h)

    def log(counters: Counters): UIO[Unit] = {
      for {
        a <- counters.sumTimeFilter.get.map(_ / 1000000)
        b <- counters.sumTimeParameter.get.map(_ / 1000000)
        c <- counters.nbBoundDraft.get
        d <- counters.sumTimeExpandeVar.get.map(_ / 1000000)
        e <- counters.sumTimeEvalJs.get.map(_ / 1000000)
        f <- counters.nbEvalJs.get
        g <- counters.sumTimeBoundDraft.get.map(_ / 1000000)
        h <- counters.sumTimeMerge.get.map(_ / 1000000)
        _ <- PolicyGenerationLoggerPure.timing.buildNodeConfig.trace(s" - create filtered policy drafts: $a ms")
        _ <- PolicyGenerationLoggerPure.timing.buildNodeConfig.trace(s" - compile global and nodes paramaters: $b ms")
        _ <- PolicyGenerationLoggerPure.timing.buildNodeConfig.trace(
               s" - created bounded policy drafts: $g ms (number of drafts: $c)"
             )
        _ <- PolicyGenerationLoggerPure.timing.buildNodeConfig.trace(s" - - expand parameter: $d ms")
        _ <- PolicyGenerationLoggerPure.timing.buildNodeConfig.trace(s" - - evaluate JS: $e  (number of evaluation: $f)")
        _ <- PolicyGenerationLoggerPure.timing.buildNodeConfig.trace(s" - merge policy draft to get policies: $h ms")
      } yield ()
    }
  }

  /*
   * From a list of ruleVal, find the list of all impacted nodes
   * with the actual Policy they will have.
   * Replace all ${rudder.node.varName} vars, returns the nodes ready to be configured, and expanded RuleVal
   * allNodeInfos *must* contains the nodes info of every nodes
   *
   * Building configuration may fail for some nodes. In that case, the whole process is not in error but only
   * the given node error fails.
   *
   * That process should be mostly a computing one (but for JS part). We use a bounded thread pool of the size
   * of maxParallelism, and the JsEngine will also use such a pool.
   */
  def buildNodeConfigurations(
      activeNodeIds:             Set[NodeId],
      ruleVals:                  Seq[RuleVal],
      nodeContexts:              Map[NodeId, InterpolationContext],
      allNodeModes:              Map[NodeId, NodeModeConfig],
      filteredTechniques:        Map[NodeId, List[TechniqueName]],
      scriptEngineEnabled:       FeatureSwitch,
      globalPolicyMode:          GlobalPolicyMode,
      maxParallelism:            Int,
      jsTimeout:                 FiniteDuration,
      generationContinueOnError: Boolean
  ): Box[NodeConfigurations] = {

    // step 1: from RuleVals to expanded rules vals

    val t0 = System.nanoTime()
    // group by nodes
    // no consistancy / unicity check is done here, it will be done
    // in an other phase. We are just switching to a node-first view.
    val policyDraftByNode: Map[NodeId, Seq[ParsedPolicyDraft]] = {
      val byNodeDrafts = scala.collection.mutable.Map.empty[NodeId, Vector[ParsedPolicyDraft]]
      ruleVals.foreach { rule =>
        rule.nodeIds.foreach { nodeId =>
          byNodeDrafts.update(nodeId, byNodeDrafts.getOrElse(nodeId, Vector[ParsedPolicyDraft]()) ++ rule.parsedPolicyDrafts)
        }
      }
      byNodeDrafts.toMap
    }
    val t1 = System.nanoTime()
    PolicyGenerationLoggerPure.timing.buildNodeConfig.debug(s"Policy draft for nodes built in: ${(t1 - t0) / 1000000} ms")

    // 1.3: build node config, binding ${rudder./node.properties} parameters
    // open a scope for the JsEngine, because its init is long.

    val nanoTime = ZIO.succeed(System.nanoTime())

    val evalJsProg = JsEngineProvider.withNewEngine(scriptEngineEnabled, maxParallelism, jsTimeout) { jsEngine =>
      val nodeConfigsProg = for {
        counters <- Counters.make()
        ncp      <- ZIO
                      .foreachPar(nodeContexts.toSeq) {
                        case (nodeId, context) =>
                          (for {
                            t1_0          <- nanoTime
                            parsedDrafts  <-
                              policyDraftByNode
                                .get(nodeId)
                                .notOptional(
                                  "Promise generation algorithm error: cannot find back the configuration information for a node"
                                )
                            filtered       = {
                              val toRemove = filteredTechniques.getOrElse(nodeId, Nil)
                              parsedDrafts.filterNot(d => toRemove.contains(d.technique.id.name))
                            }
                            // if a node is in state "emtpy policies", we only keep system policies + log
                            filteredDrafts = if (context.nodeInfo.state == NodeState.EmptyPolicies) {
                                               PolicyGenerationLogger.info(
                                                 s"Node '${context.nodeInfo.hostname}' (${context.nodeInfo.id.value}) is in '${context.nodeInfo.state.name}' state, keeping only system policies for it"
                                               )
                                               filtered.flatMap(d => {
                                                 if (d.isSystem) {
                                                   Some(d)
                                                 } else {
                                                   PolicyGenerationLogger.trace(
                                                     s"Node '${context.nodeInfo.id.value}': skipping policy '${d.id.value}'"
                                                   )
                                                   None
                                                 }
                                               })
                                             } else {
                                               filtered
                                             }
                            t1_1          <- nanoTime
                            _             <- counters.sumTimeFilter.update(_ + t1_1 - t1_0)

                            ////////////////////////////////////////////  fboundedDraft ////////////////////////////////////////////

                            t1_2          <- nanoTime
                            _             <- counters.sumTimeParameter.update(_ + t1_2 - t1_1)
                            boundedDrafts <- filteredDrafts.accumulate { draft =>
                                               (for {
                                                 _   <- counters.nbBoundDraft.update(_ + 1)
                                                 ret <- (for {
                                                          // bind variables with interpolated context
                                                          t2_0              <- nanoTime
                                                          expandedVariables <- draft.variables(context)
                                                          t2_1              <- nanoTime
                                                          _                 <- counters.sumTimeExpandeVar.update(_ + t2_1 - t2_0)
                                                          // And now, for each variable, eval - if needed - the result
                                                          expandedVars      <- expandedVariables.accumulate {
                                                                                 case (k, v) =>
                                                                                   // js lib is specific to the node os, bind here to not leak eval between vars
                                                                                   val jsLib = context.nodeInfo.osDetails.os match {
                                                                                     case AixOS => JsRudderLibBinding.Aix
                                                                                     case _     => JsRudderLibBinding.Crypt
                                                                                   }

                                                                                   for {
                                                                                     _    <- counters.nbEvalJs.update(_ + 1)
                                                                                     t3_0 <- nanoTime
                                                                                     t    <- jsEngine.eval(v, jsLib).map(x => (k, x))
                                                                                     t3_1 <- nanoTime
                                                                                     _    <- counters.sumTimeEvalJs.update(_ + t3_1 - t3_0)
                                                                                   } yield t
                                                                               }.mapError(_.deduplicate)
                                                        } yield {
                                                          draft.toBoundedPolicyDraft(expandedVars.toMap)
                                                        }).chainError(s"When processing directive '${draft.directiveName}'")
                                               } yield {
                                                 ret
                                               })
                                             }.mapError(_.deduplicate)
                            t1_3          <- nanoTime
                            _             <- counters.sumTimeBoundDraft.update(_ + t1_3 - t1_2)

                            ////////////////////////////////////////////  fboundedDraft ////////////////////////////////////////////

                            // from policy draft, check and build the ordered seq of policy
                            policies <- MergePolicyService.buildPolicy(context.nodeInfo, globalPolicyMode, boundedDrafts).toIO
                            t1_4     <- nanoTime
                            _        <- counters.sumTimeMerge.update(_ + t1_4 - t1_3)
                          } yield {
                            // we have the node mode
                            val nodeModes  = allNodeModes(context.nodeInfo.id)
                            val nodeConfig = NodeConfiguration(
                              nodeInfo = context.nodeInfo,
                              modesConfig = nodeModes, // system technique should not have hooks, and at least it is not supported.

                              runHooks = MergePolicyService.mergeRunHooks(
                                policies.filter(!_.technique.isSystem),
                                nodeModes.nodePolicyMode,
                                nodeModes.globalPolicyMode
                              ),
                              policies = policies,
                              nodeContext = context.nodeContext,
                              parameters = context.parameters.map {
                                case (k, v) => ParameterForConfiguration(k, GenericProperty.serializeToHocon(v))
                              }.toSet,
                              isRootServer = context.nodeInfo.id == context.policyServerInfo.id
                            )
                            nodeConfig
                          }).chainError(
                            s"Error with parameters expansion for node '${context.nodeInfo.hostname}' (${context.nodeInfo.id.value})"
                          ).either
                      }
                      .withParallelism(maxParallelism)
        _        <- Counters.log(counters)
      } yield ncp

      // now the program that builds NodeConfigs, log time, and check result for errors/successes
      for {
        t0       <- nanoTime
        res      <- nodeConfigsProg
        t1       <- nanoTime
        _        <- PolicyGenerationLoggerPure.timing.buildNodeConfig.debug(s"Total run config: ${(t1 - t0) / 1000000} ms")
        success   = res.collect { case Right(c) => c }.toList
        failures  = res.collect { case Left(f) => f.fullMsg }.toSet
        failedIds = nodeContexts.keySet -- success.map(_.nodeInfo.id)
        result    = recFailNodes(failedIds, success, failures)
        finalRes <- failures.size match {
                      case 0                              => result.succeed
                      case _ if generationContinueOnError =>
                        PolicyGenerationLoggerPure.error(s"Error while computing Node Configuration for nodes") *>
                        PolicyGenerationLoggerPure.error(s"Cause is ${failures.mkString(",")}") *>
                        result.succeed
                      case _                              =>
                        val allErrors = Chained(
                          s"Error while computing Node Configuration for nodes: ",
                          Accumulated(NonEmptyList.fromListUnsafe(result.errors))
                        )
                        PolicyGenerationLoggerPure.error(allErrors.fullMsg) *> allErrors.fail
                    }
      } yield finalRes
    }

    // do evaluation
    evalJsProg.toBox
  }

  // we need to remove all nodes whose parent are failed, recursively
  // we don't want to have zillions of "node x failed b/c parent failed", so we just say "children node failed b/c parent failed"
  @scala.annotation.tailrec
  def recFailNodes(failed: Set[NodeId], maybeSuccess: List[NodeConfiguration], failures: Set[String]): NodeConfigurations = {
    // filter all nodes whose parent is in failed
    val newFailed = maybeSuccess.collect {
      case cfg if (failed.contains(cfg.nodeInfo.policyServerId)) =>
        (
          cfg.nodeInfo.id,
          s"Can not configure '${cfg.nodeInfo.policyServerId.value}' children node because '${cfg.nodeInfo.policyServerId.value}' is a policy server whose configuration is in error"
        )
    }.toMap

    if (newFailed.isEmpty) { // ok, returns
      NodeConfigurations(maybeSuccess, failures.toList.map(Unexpected(_)))
    } else { // recurse
      val allFailed = failed ++ newFailed.keySet
      recFailNodes(allFailed, maybeSuccess.filter(cfg => !allFailed.contains(cfg.nodeInfo.id)), failures ++ newFailed.values)
    }
  }
}

///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

trait PromiseGeneration_updateAndWriteRule extends PromiseGenerationService {

  def nodeConfigurationService:  NodeConfigurationHashRepository
  def woRuleRepo:                WoRuleRepository
  def promisesFileWriterService: PolicyWriterService

  /**
   * That methode remove node configurations for nodes not in allNodes.
   * Corresponding nodes are deleted from the repository of node configurations.
   * Return the updated map of all node configurations (really present).
   */
  def purgeDeletedNodes(
      allNodes:       Set[NodeId],
      allNodeConfigs: Map[NodeId, NodeConfiguration]
  ): Box[Map[NodeId, NodeConfiguration]] = {
    val nodesToDelete = allNodeConfigs.keySet -- allNodes
    for {
      deleted <- nodeConfigurationService.deleteNodeConfigurations(nodesToDelete)
    } yield {
      allNodeConfigs -- nodesToDelete
    }
  }

  def forgetOtherNodeConfigurationState(keep: Set[NodeId]): Box[Set[NodeId]] = {
    nodeConfigurationService.onlyKeepNodeConfiguration(keep)
  }

  def getNodeConfigurationHash(): Box[Map[NodeId, NodeConfigurationHash]] = nodeConfigurationService.getAll()

  /**
   * Look what are the node configuration updated compared to information in cache
   */
  def selectUpdatedNodeConfiguration(
      nodeConfigurations: Map[NodeId, NodeConfiguration],
      cache:              Map[NodeId, NodeConfigurationHash]
  ): Set[NodeId] = {
    val notUsedTime =
      new DateTime(0) // this seems to tell us the nodeConfigurationHash should be refactor to split time frome other properties
    val newConfigCache = nodeConfigurations.map { case (_, conf) => NodeConfigurationHash(conf, notUsedTime) }

    val (updatedConfig, notUpdatedConfig) = newConfigCache.toSeq.partition { p =>
      cache.get(p.id) match {
        case None    => true
        case Some(e) => !e.equalWithoutWrittenDate(p)
      }
    }

    if (notUpdatedConfig.nonEmpty) {
      PolicyGenerationLogger.update.debug(s"Configuration of ${notUpdatedConfig.size} nodes were already OK, not updating them")
      PolicyGenerationLogger.update.trace(s" -> not updating nodes: [${notUpdatedConfig.map(_.id.value).mkString(", ")}]")
    }

    if (updatedConfig.isEmpty) {
      PolicyGenerationLogger.info("No node configuration was updated, no policies to write")
      Set()
    } else {
      val nodeToKeep = updatedConfig.map(_.id).toSet
      PolicyGenerationLogger.info(
        s"Configuration of ${updatedConfig.size} nodes were updated, their policies are going to be written"
      )
      PolicyGenerationLogger.update.debug(s" -> updating nodes: [${updatedConfig.map(_.id.value).mkString(", ")}]")
      nodeConfigurations.keySet.intersect(nodeToKeep)
    }
  }

  /**
   * For each nodeConfiguration, get the corresponding node config version.
   * Either get it from cache or create a new one depending if the node configuration was updated
   * or not.
   */
  def getNodesConfigVersion(
      allNodeConfigs: Map[NodeId, NodeConfiguration],
      hashes:         Map[NodeId, NodeConfigurationHash],
      generationTime: DateTime
  ): Map[NodeId, NodeConfigId] = {

    /*
     * Several steps heres:
     * - look what node configuration are updated (based on their cache ?)
     * - write these node configuration
     * - update caches
     */
    val updatedNodes = selectUpdatedNodeConfiguration(allNodeConfigs, hashes)

    /*
     * The hash is directly the NodeConfigHash.hashCode, because we want it to be
     * unique to a given generation and the "writtenDate" is part of NodeConfigurationHash.
     * IE, even if we have the same nodeConfig than a
     * previous one, but the expected node config was closed, we want to get a new
     * node config id.
     */
    def hash(h: NodeConfigurationHash): String = {
      // we always set date = 0, so we have the possibility to see with
      // our eyes (and perhaps some SQL) two identicals node config diverging
      // only by the date of generation
      h.writtenDate.toString("YYYYMMdd-HHmmss") + "-" + h.copy(writtenDate = new DateTime(0)).hashCode.toHexString
    }

    /*
     * calcul new nodeConfigId for the updated configuration
     * The filterKey in place of a updatedNode.map(id => allNodeConfigs.get(id)
     * is to be sure to have the NodeConfiguration. There is 0 reason
     * to not have it, but it simplifie case.
     *
     */
    val nodeConfigIds = allNodeConfigs.view
      .filterKeys(updatedNodes.contains)
      .values
      .map(nodeConfig => (nodeConfig.nodeInfo.id, NodeConfigId(hash(NodeConfigurationHash(nodeConfig, generationTime)))))
      .toMap

    ComplianceDebugLogger.debug(s"Updated node configuration ids: ${nodeConfigIds.map {
        case (id, nodeConfigId) =>
          s"[${id.value}:${hashes.get(id).fold("???")(x => hash(x))}->${nodeConfigId.value}]"
      }.mkString("")}")

    // return update nodeId with their config
    nodeConfigIds
  }

  /**
   * Actually  write the new configuration for the list of given node.
   * If the node target configuration is the same as the actual, nothing is done.
   * Else, promises are generated;
   * Return the list of configuration successfully written.
   */
  def writeNodeConfigurations(
      rootNodeId:       NodeId,
      updated:          Map[NodeId, NodeConfigId],
      allNodeConfigs:   Map[NodeId, NodeConfiguration],
      allNodeInfos:     Map[NodeId, NodeInfo],
      globalPolicyMode: GlobalPolicyMode,
      generationTime:   DateTime,
      maxParallelism:   Int
  ): Box[Set[NodeId]] = {

    val fsWrite0 = System.currentTimeMillis

    for {
      written   <- promisesFileWriterService.writeTemplate(
                     rootNodeId,
                     updated.keySet,
                     allNodeConfigs,
                     allNodeInfos,
                     updated,
                     globalPolicyMode,
                     generationTime,
                     maxParallelism
                   )
      ldapWrite0 = DateTime.now.getMillis
      fsWrite1   = (ldapWrite0 - fsWrite0)
      _          = PolicyGenerationLogger.timing.debug(s"Node configuration written on filesystem in ${fsWrite1} ms")
      // update the hash for the updated node configuration for that generation

      // #10625 : that should be one logic-level up (in the main generation for loop)

      toCache    = allNodeConfigs.view.filterKeys(updated.contains(_)).values.toSet
      _         <- nodeConfigurationService.save(toCache.map(x => NodeConfigurationHash(x, generationTime)))
      ldapWrite1 = (DateTime.now.getMillis - ldapWrite0)
      _          = {
        PolicyGenerationLogger.timing.debug(
          s"Node configuration hashes stored in '${FileBasedNodeConfigurationHashRepository.defaultHashesPath}' in ${ldapWrite1} ms"
        )
      }
    } yield {
      written.toSet
    }
  }

}

///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

trait PromiseGeneration_setExpectedReports extends PromiseGenerationService {
  def complianceCache:                CachedFindRuleNodeStatusReports
  def confExpectedRepo:               UpdateExpectedReportsRepository
  def cachedNodeConfigurationService: CachedNodeConfigurationService

  override def computeExpectedReports(
      allNodeConfigurations: Map[NodeId, NodeConfiguration],
      updatedNodes:          Map[NodeId, NodeConfigId],
      generationTime:        DateTime,
      allNodeModes:          Map[NodeId, NodeModeConfig]
  ): List[NodeExpectedReports] = {

    val updatedNodeIds = updatedNodes.keySet
    allNodeConfigurations.collect {
      case (nodeId, nodeConfig) if (updatedNodeIds.contains(nodeId)) =>
        // overrides are in the reverse way, we need to transform them into OverridenPolicy
        val overrides = nodeConfig.policies.flatMap(p => p.overrides.map(overriden => OverridenPolicy(overriden, p.id)))

        NodeExpectedReports(
          nodeId,
          updatedNodes(nodeId),
          generationTime,
          None,
          allNodeModes(nodeId), // that shall not throw, because we have all nodes here

          RuleExpectedReportBuilder(nodeConfig.policies),
          overrides
        )
    }.toList
  }

  override def invalidateComplianceCache(actions: Seq[(NodeId, CacheExpectedReportAction)]): IOResult[Unit] = {
    cachedNodeConfigurationService.invalidateWithAction(actions)
  }

  override def saveExpectedReports(
      expectedReports: List[NodeExpectedReports]
  ): Box[Seq[NodeExpectedReports]] = {
    // now, we just need to go node by node, and for each:
    val time_0 = System.currentTimeMillis
    val res    = confExpectedRepo.saveNodeExpectedReports(expectedReports)
    TimingDebugLogger.trace(s"updating expected node configuration in base took: ${System.currentTimeMillis - time_0}ms")
    res
  }
}

/*
 * Utility object that take a flat list of Variable and structure them back into a
 * tree corresponding to Technique components hierarchy.
 *
 * We could avoid that method by directly saving user directives into a
 * tree-like data structure (yes, JSON) in place of the flat PolicyVars
 * format.
 */
object RuleExpectedReportBuilder extends Loggable {
  import com.normation.cfclerk.xmlparsers.CfclerkXmlConstants.DEFAULT_COMPONENT_KEY

  // get Rules expected configs back from a list of Policies
  def apply(policies: List[Policy]): List[RuleExpectedReports] = {
    // before anything else, we need to "flatten" rule/directive by policy vars
    // (i.e one for each PolicyVar, and we only have more than one of them in the
    // case where several directives from the same technique where merged.
    val flatten = policies.flatMap { p =>
      p.policyVars.toList.map(v => p.copy(id = v.policyId, policyVars = NonEmptyList.one(v)))
    }

    // now, group by rule id and map to expected reports
    flatten
      .groupBy(_.id.ruleId)
      .map {
        case (ruleId, seq) =>
          val directives = seq.map { policy =>
            // from a policy, get one "directive expected reports" by directive.
            // As we flattened previously, we only need/want "head"
            val pvar = policy.policyVars.head
            DirectiveExpectedReports(
              pvar.policyId.directiveId,
              pvar.policyMode,
              policy.technique.isSystem,
              componentsFromVariables(policy.technique, policy.id.directiveId, pvar)
            )
          }
          RuleExpectedReports(ruleId, directives)
      }
      .toList
  }

  def componentsFromVariables(
      technique:   PolicyTechnique,
      directiveId: DirectiveId,
      vars:        PolicyVars
  ): List[ComponentExpectedReport] = {

    // Computes the components values, and the unexpanded component values
    val getTrackingVariableCardinality: (Seq[String], Seq[String]) = {
      val boundingVar = vars.trackerVariable.spec.boundingVariable.getOrElse(vars.trackerVariable.spec.name)
      // now the cardinality is the length of the boundingVariable
      (vars.expandedVars.filter(_._1.value == boundingVar), vars.originalVars.filter(_._1.value == boundingVar)) match {
        case (m, n) if m.isEmpty && n.isEmpty || m.values.flatMap(_.values).isEmpty && n.values.flatMap(_.values).isEmpty =>
          PolicyGenerationLogger.debug(
            s"Could not find the bounded variable ${boundingVar} for ${vars.trackerVariable.spec.name} in Directive ${directiveId.serialize}"
          )
          (Seq(DEFAULT_COMPONENT_KEY), Seq(DEFAULT_COMPONENT_KEY)) // this is an autobounding policy
        case (variables, originalVariables)
            if (variables.values.flatMap(_.values).size == originalVariables.values.flatMap(_.values).size) =>
          (variables.values.flatMap(_.values).toSeq, originalVariables.values.flatMap(_.values).toSeq)
        case (m, originalVariables) if (m.isEmpty && originalVariables.nonEmpty)                                          =>
          (Seq(DEFAULT_COMPONENT_KEY), originalVariables.values.flatMap(_.values).toSeq) // this is an autobounding policy
        case (variables, m) if (m.isEmpty)                                                                                =>
          PolicyGenerationLogger.warn(
            s"Somewhere in the expansion of variables, the bounded variable ${boundingVar} for ${vars.trackerVariable.spec.name} in Directive ${directiveId.serialize} appeared, but was not originally there"
          )
          (variables.values.flatMap(_.values).toSeq, variables.values.flatMap(_.values).toSeq) // this is an autobounding policy
        case (variables, originalVariables)                                                                               =>
          PolicyGenerationLogger.warn(
            s"Expanded and unexpanded values for bounded variable ${boundingVar} for ${vars.trackerVariable.spec.name} in Directive ${directiveId.serialize} have not the same size : ${variables.values} and ${originalVariables.values}"
          )
          (variables.values.flatMap(_.values).toSeq, originalVariables.values.flatMap(_.values).toSeq)

      }
    }

    /*
     * We can have several components, one by section.
     * If there is no component for that policy, the policy is autobounded to DEFAULT_COMPONENT_KEY
     *
     */
    def sectionToExpectedReports(path: List[String])(section: SectionSpec): List[ComponentExpectedReport] = {
      if (section.isComponent) {
        section.reportingLogic match {
          case None       =>
            (section.id, section.componentKey) match {
              case (None, None)                     =>
                // a section that is a component without componentKey variable: card=1, value="None"
                ValueExpectedReport(section.name, List(ExpectedValueMatch(DEFAULT_COMPONENT_KEY, DEFAULT_COMPONENT_KEY))) :: Nil
              case (Some(id), None)                 =>
                ValueExpectedReport(section.name, List(ExpectedValueId(DEFAULT_COMPONENT_KEY, id))) :: Nil
              case (sectionReportId, Some(varName)) =>
                // a section with a componentKey variable: card=variable card
                // we are maybe not in a block, but we should only take the values matching the current parent path
                val currentPath    = section.name :: path // (varName is not in parent, but in value)
                val refComponentId = ComponentId(varName, currentPath, sectionReportId)

                // There are many cases were the variable component is not in the sections
                // In historical techniques, we have only one componentKey for all technique, and all sections
                // so we cannot search it in a specific path, and must rather look for it everywhere if it doesn't match

                val (varReportId, innerExpandedVars, innerUnexpandedVars) = vars.expandedVars.get(refComponentId) match {
                  // ok, the componentKey is in the current path, all is great
                  // this is a technique from technique editor, or an historical technique with the right section
                  case Some(innerExpandedVars) =>
                    (
                      innerExpandedVars.spec.id,
                      innerExpandedVars.values.toList,
                      vars.originalVars.get(refComponentId).map(_.values.toList).getOrElse(Nil)
                    )
                  case None                    =>
                    // get the first variable that matches if it is not in the section
                    // it is only from historical techniques
                    vars.expandedVars.find { case (_, variable) => variable.spec.name == varName } match {
                      case None                            => (None, Nil, Nil)
                      case Some((comp, innerExpandedVars)) =>
                        (
                          innerExpandedVars.spec.id,
                          innerExpandedVars.values.toList,
                          vars.originalVars.get(comp).map(_.values.toList).getOrElse(Nil)
                        )
                    }
                }

                if (innerExpandedVars.size != innerUnexpandedVars.size) {
                  PolicyGenerationLogger.warn(
                    "Caution, the size of unexpanded and expanded variables for autobounding variable in section %s for directive %s are not the same : %s and %s"
                      .format(section.componentKey, directiveId.serialize, innerExpandedVars, innerUnexpandedVars)
                  )
                }

                val values = sectionReportId match {
                  case None     =>
                    varReportId match {
                      case None     =>
                        innerExpandedVars.zip(innerUnexpandedVars).map(ExpectedValueMatch.tupled)
                      case Some(id) =>
                        innerExpandedVars.map(v => ExpectedValueId(v, id))
                    }
                  case Some(id) =>
                    innerExpandedVars.map(v => ExpectedValueId(v, id))

                }
                val children =
                  section.children.collect { case c: SectionSpec => c }.flatMap(c => sectionToExpectedReports(path)(c)).toList

                ValueExpectedReport(section.name, values) :: children
            }
          case Some(rule) =>
            // here, every values in the child will match because of the contains.
            // structure of componentId is "Component Name", List("Component Name", "current block", "parent block", "great parent block")
            val currentPath = section.name :: path
            val children    =
              section.children.collect { case c: SectionSpec => c }.flatMap(c => sectionToExpectedReports(currentPath)(c)).toList
            val correctRule = rule match {
              case FocusReport(reportId) =>
                val newComponent = (children
                  .find(c => {
                    c match {
                      case block: BlockExpectedReport => block.id.map(_ == reportId).getOrElse(false)
                      case v:     ValueExpectedReport =>
                        v.componentsValues.exists { v =>
                          v match {
                            case ExpectedValueMatch(_, _) => false
                            case ExpectedValueId(_, id)   => id == reportId
                          }
                        }
                    }
                  }))
                  .map(_.componentName)
                  .getOrElse(reportId)
                FocusReport(newComponent)
              case r                     => r
            }

            BlockExpectedReport(section.name, correctRule, children, section.id) :: Nil

        }
      } else {
        section.children.collect { case c: SectionSpec => c }.flatMap(c => sectionToExpectedReports(path)(c)).toList

      }
    }

    val initPath      = technique.rootSection.name :: Nil
    val allComponents = technique.rootSection.children.collect { case c: SectionSpec => c }
      .flatMap(c => sectionToExpectedReports(initPath)(c))
      .toList

    if (allComponents.isEmpty) {
      // that log is outputed one time for each directive for each node using a technique, it's far too
      // verbose on debug.
      PolicyGenerationLogger.trace(
        s"Technique '${technique.id.debugString}' does not define any components, assigning default component with " +
        s"expected report = 1 for directive ${directiveId.debugString}"
      )

      val trackingVarCard = getTrackingVariableCardinality
      List(
        ValueExpectedReport(
          technique.id.name.value,
          trackingVarCard._1.toList.zip(trackingVarCard._2.toList).map(ExpectedValueMatch.tupled)
        )
      )
    } else {
      allComponents
    }

  }

}

// utility case class where we store the sorted list of node ids to be passed to hooks
final case class SortedNodeIds(servers: Seq[String], nodes: Seq[String])

trait PromiseGeneration_Hooks extends PromiseGenerationService with PromiseGenerationHooks {

  def postGenerationHookCompabilityMode: Option[Boolean]

  /*
   * Plugin hooks
   */
  private[this] val codeHooks = collection.mutable.Buffer[PromiseGenerationHooks]()

  def appendPreGenCodeHook(hook: PromiseGenerationHooks): Unit = {
    this.codeHooks.append(hook)
  }

  /*
   * plugins hooks
   */
  override def beforeDeploymentSync(generationTime: DateTime): Box[Unit] = {
    sequence(codeHooks.toSeq)(_.beforeDeploymentSync(generationTime)).map(_ => ())
  }

  /*
   * Pre generation hooks
   */
  override def runPreHooks(generationTime: DateTime, systemEnv: HookEnvPairs): Box[Unit] = {
    (for {
      // fetch all
      preHooks <- RunHooks.getHooksPure(HOOKS_D + "/policy-generation-started", HOOKS_IGNORE_SUFFIXES)
      _        <- RunHooks.asyncRun(preHooks, HookEnvPairs.build(("RUDDER_GENERATION_DATETIME", generationTime.toString)), systemEnv)
    } yield ()).toBox
  }

  /**
   * Mitigation for ticket https://issues.rudder.io/issues/15011
   * If we have too many nodes (~3500, see ticket for details), we hit the Linux/JVM
   * plateform MAX_ARG limit. This will fail the generation with a return code of
   * Int.MIN_VALUE.
   * So, the correct solution is to always write node IDS in a file, and give hooks
   * the path to the file.
   * But we don't want to break user hooks because most likely, they don't have
   * enough nodes to be impacted.
   * So, we continue to set the RUDDER_NODE_IDS parameter if:
   * - there is user hooks for post policy generation,
   * - there is less than 3000 updated nodes,
   * - the rudder configuration parameter for compability mode is not set (default).
   *
   * If the configuration parameter is set to false, we never write it (to take care of
   * cases where the limit would be lower for unknown reasons).
   * if the configuration parameter is set to true, we always write it (to take care of
   * cases where the user knows what they are doing).
   */
  def getNodeIdsEnv(
      setNodeIdsParameter: Option[Boolean],
      hooks:               Hooks,
      sortedNodeIds:       SortedNodeIds
  ): Option[(String, String)] = {
    def formatEnvPair(sortedNodeIds: SortedNodeIds): (String, String) = {
      ("RUDDER_NODE_IDS", (sortedNodeIds.servers ++ sortedNodeIds.nodes).mkString(" "))
    }
    setNodeIdsParameter match {
      case Some(true)  =>
        HooksLogger.trace(
          "'rudder.hooks.policy-generation-finished.nodeids.compability' set to 'true': set 'RUDDER_NODE_IDS' parameter"
        )
        Some(formatEnvPair(sortedNodeIds))
      case Some(false) =>
        HooksLogger.trace(
          "'rudder.hooks.policy-generation-finished.nodeids.compability' set to 'false': unset 'RUDDER_NODE_IDS' parameter"
        )
        None
      case None        =>
        // user node exists ?
        if ((hooks.hooksFile.toSet -- Set("50-reload-policy-file-server", "60-trigger-node-update")).isEmpty) {
          // only system hooks, do not set parameter
          HooksLogger.trace(
            "'rudder.hooks.policy-generation-finished.nodeids.compability' not set and no user hooks: unset 'RUDDER_NODE_IDS' parameter"
          )
          None
        } else {
          if (sortedNodeIds.servers.size + sortedNodeIds.nodes.size > 3000) {
            // do not set parameter to avoid error described in ticket
            HooksLogger.trace(
              "'rudder.hooks.policy-generation-finished.nodeids.compability' not set, user hooks present but more than 3000 nodes updated: " +
              "unset 'RUDDER_NODE_IDS' parameter"
            )
            HooksLogger.warn(
              s"More than 3000 nodes where updated and 'policy-generation-finished' user hooks are present. The parameter 'RUDDER_NODE_IDS'" +
              s" will be unset due to https://issues.rudder.io/issues/15011. Update your hooks accordinbly to the ticket workaround, then set rudder" +
              s" configuration parameter 'rudder.hooks.policy-generation-finished.nodeids.compability' to false and restart Rudder."
            )
            None
          } else {
            // compability mode
            HooksLogger.trace(
              "'rudder.hooks.policy-generation-finished.nodeids.compability' not set, user hooks present and less than 3000 nodes " +
              "updated: set 'RUDDER_NODE_IDS' parameter for compatibility"
            )
            Some(formatEnvPair(sortedNodeIds))
          }
        }
    }
  }

  def getSortedNodeIds(updatedNodeConfigsInfo: Map[NodeId, NodeInfo]): SortedNodeIds = {
    val (policyServers, simpleNodes) = {
      val (a, b) = updatedNodeConfigsInfo.values.toSeq.partition(_.isPolicyServer)
      (
        // policy servers are sorted by their promiximity with root, root first
        a.sortBy(x => NodePriority(x)).map(_.id.value), // simple nodes are sorted alphanum

        b.map(_.id.value).sorted
      )
    }
    SortedNodeIds(policyServers, simpleNodes)
  }

  /*
   * Write a sourcable file with the sorted list of updated NodeIds in RUDDER_NODE_IDS variable.
   * This file contains:
   * - comments with the generation time
   * - updated policy server / relay
   * - updated normal nodes
   * - all update nodes
   *
   * We keep one generation back in a "${path}.old"
   */
  def writeNodeIdsForHook(path: String, sortedNodeIds: SortedNodeIds, start: DateTime, end: DateTime): IOResult[Unit] = {
    // format of date in the file
    def date(d: DateTime)           = d.toString(ISODateTimeFormat.basicDateTime())
    // how to format a list of ids in the file
    def formatIds(ids: Seq[String]) = "(" + ids.mkString("\n", "\n", "\n") + ")"

    implicit val openOptions = File.OpenOptions.append
    implicit val charset     = StandardCharsets.UTF_8

    val file     = File(path)
    val savedOld = File(path + ".old")

    for {
      _ <- IOResult.attempt(s"Can not move previous updated node IDs file to '${savedOld.pathAsString}'") {
             file.parent.createDirectoryIfNotExists(true)
             if (file.exists) {
               file.moveTo(savedOld)(File.CopyOptions(overwrite = true))
             }
           }
      _ <- IOResult.attempt(s"Can not write updated node IDs file '${file.pathAsString}'") {
             // header
             file.writeText(
               s"# This file contains IDs of nodes updated by policy generation started at '${date(start)}' ended at '${date(end)}'\n\n"
             )
             // policy servers
             file.writeText(s"\nRUDDER_UPDATED_POLICY_SERVER_IDS=${formatIds(sortedNodeIds.servers)}\n")
             file.writeText(s"\nRUDDER_UPDATED_NODE_IDS=${formatIds(sortedNodeIds.nodes)}\n")
             file.writeText(s"\nRUDDER_NODE_IDS=${formatIds(sortedNodeIds.servers ++ sortedNodeIds.nodes)}\n")
           }
    } yield ()
  }

  /*
   * Post generation hooks
   */
  override def runPostHooks(
      generationTime:   DateTime,
      endTime:          DateTime,
      updatedNodeInfos: Map[NodeId, NodeInfo],
      systemEnv:        HookEnvPairs,
      nodeIdsPath:      String
  ): Box[Unit] = {
    val sortedNodeIds = getSortedNodeIds(updatedNodeInfos)
    for {
      written         <- writeNodeIdsForHook(nodeIdsPath, sortedNodeIds, generationTime, endTime)
      postHooks       <- RunHooks.getHooksPure(HOOKS_D + "/policy-generation-finished", HOOKS_IGNORE_SUFFIXES)
      // we want to sort node with root first, then relay, then other nodes for hooks
      updatedNodeIds   = updatedNodeInfos.toList.map {
                           case (k, v) =>
                             (
                               k,
                               NodePriority(v)
                             )
                         }.sortBy(_._2).map(_._1)
      defaultEnvParams = (("RUDDER_GENERATION_DATETIME", generationTime.toString())
                           :: ("RUDDER_END_GENERATION_DATETIME", endTime.toString) // what is the most alike a end time
                           :: ("RUDDER_NODE_IDS_PATH", nodeIdsPath)
                           :: ("RUDDER_NUMBER_NODES_UPDATED", updatedNodeIds.size.toString)
                           :: ("RUDDER_ROOT_POLICY_SERVER_UPDATED", if (updatedNodeIds.contains("root")) "0" else "1")
                           :: Nil)
      envParams        = getNodeIdsEnv(postGenerationHookCompabilityMode, postHooks, sortedNodeIds) match {
                           case None    => defaultEnvParams
                           case Some(p) => p :: defaultEnvParams
                         }
      _               <- RunHooks.asyncRun(
                           postHooks,
                           HookEnvPairs.build(envParams: _*),
                           systemEnv
                         )

    } yield ()
  }.toBox

  /*
   * Write a sourcable file with the sorted list of updated NodeIds in RUDDER_NODE_IDS variable.
   * This file contains:
   * - comments with the generation time
   * - updated policy server / relay
   * - updated normal nodes
   * - all update nodes
   *
   * We keep one generation back in a "${path}.old"
   */
  def writeErrorMessageForHook(path: String, error: String, start: DateTime, end: DateTime): IOResult[Unit] = {
    // format of date in the file
    def date(d: DateTime) = d.toString(ISODateTimeFormat.basicDateTime())

    implicit val openOptions = File.OpenOptions.append
    implicit val charset     = StandardCharsets.UTF_8

    val file     = File(path)
    val savedOld = File(path + ".old")

    for {
      _ <- IOResult.attempt(s"Can not move previous updated node IDs file to '${savedOld.pathAsString}'") {
             file.parent.createDirectoryIfNotExists(true)
             if (file.exists) {
               file.moveTo(savedOld)(File.CopyOptions(overwrite = true))
             }
           }
      _ <- IOResult.attempt(s"Can not write error message file '${file.pathAsString}'") {
             // header
             file.writeText(
               s"# This file contains error message for the failed policy generation started at '${date(start)}' ended at '${date(end)}'\n\n"
             )
             file.writeText(error)
           }
    } yield ()
  }

  /*
   * Post generation hooks
   */
  override def runFailureHooks(
      generationTime:   DateTime,
      endTime:          DateTime,
      systemEnv:        HookEnvPairs,
      errorMessage:     String,
      errorMessagePath: String
  ): Box[Unit] = {
    for {
      written      <- writeErrorMessageForHook(errorMessagePath, errorMessage, generationTime, endTime)
      failureHooks <- RunHooks.getHooksPure(HOOKS_D + "/policy-generation-failed", HOOKS_IGNORE_SUFFIXES)
      // we want to sort node with root first, then relay, then other nodes for hooks
      envParams     = (("RUDDER_GENERATION_DATETIME", generationTime.toString())
                        :: ("RUDDER_END_GENERATION_DATETIME", endTime.toString) // what is the most alike a end time
                        :: ("RUDDER_ERROR_MESSAGE_PATH", errorMessagePath)
                        :: Nil)
      _            <- RunHooks.asyncRun(
                        failureHooks,
                        HookEnvPairs.build(envParams: _*),
                        systemEnv
                      )
    } yield ()
  }.toBox

}

trait PromiseGeneration_NodeCertificates extends PromiseGenerationService {

  def allNodeCertificatesPemFile: File
  def writeNodeCertificatesPem:   WriteNodeCertificatesPem

  override def writeCertificatesPem(allNodeInfos: Map[NodeId, NodeInfo]): Unit = {
    writeNodeCertificatesPem.writeCerticatesAsync(allNodeCertificatesPemFile, allNodeInfos)
  }
}
