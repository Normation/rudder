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

import java.util.concurrent.TimeUnit

import scala.Option.option2Iterable
import org.joda.time.DateTime
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.Constants
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.domain.parameters.GlobalParameter
import com.normation.rudder.domain.parameters.ParameterName
import com.normation.rudder.domain.policies._
import com.normation.rudder.repository._
import com.normation.rudder.services.eventlog.HistorizationService
import com.normation.rudder.services.nodes.NodeInfoService
import com.normation.rudder.services.policies.nodeconfig.NodeConfigurationHash
import com.normation.utils.Control._
import net.liftweb.common._
import com.normation.rudder.domain.parameters.GlobalParameter
import com.normation.inventory.services.core.ReadOnlyFullInventoryRepository
import com.normation.inventory.domain.NodeInventory
import com.normation.inventory.domain.AcceptedInventory
import com.normation.inventory.domain.NodeInventory
import com.normation.rudder.domain.parameters.GlobalParameter
import com.normation.rudder.domain.reports.NodeExpectedReports
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.reports.NodeConfigId
import com.normation.rudder.reports.ComplianceMode
import com.normation.rudder.reports.ComplianceModeService
import com.normation.rudder.reports.AgentRunIntervalService
import com.normation.rudder.reports.AgentRunInterval
import com.normation.rudder.domain.logger.ComplianceDebugLogger
import com.normation.rudder.services.reports.CachedFindRuleNodeStatusReports
import com.normation.rudder.services.policies.write.PolicyWriterService
import com.normation.rudder.reports.GlobalComplianceMode
import com.normation.rudder.domain.licenses.CfeEnterpriseLicense
import com.normation.rudder.domain.appconfig.FeatureSwitch
import com.normation.inventory.domain.AixOS
import com.normation.rudder.batch.UpdateDynamicGroups
import com.normation.rudder.domain.reports.NodeModeConfig
import com.normation.rudder.reports.HeartbeatConfiguration
import com.normation.rudder.hooks.RunHooks
import com.normation.rudder.hooks.HookEnvPairs

import scala.concurrent.Future
import com.normation.rudder.hooks.HooksImplicits
import com.normation.rudder.domain.nodes.NodeState
import com.normation.rudder.services.policies.nodeconfig.NodeConfigurationHashRepository
import com.normation.rudder.domain.reports.DirectiveExpectedReports
import com.normation.rudder.domain.reports.ComponentExpectedReport
import com.normation.rudder.domain.reports.RuleExpectedReports
import com.normation.rudder.domain.logger.TimingDebugLogger
import com.normation.rudder.domain.logger.PolicyLogger
import cats.data.NonEmptyList
import com.normation.rudder.domain.reports.OverridenPolicy
import com.normation.rudder.services.policies.write.TechniqueResources
import com.normation.templates.FillTemplatesService
import monix.execution.ExecutionModel
import monix.execution.Scheduler
import org.joda.time.Period

import scala.concurrent.duration.FiniteDuration
import com.normation.box._
import com.normation.errors._
import com.normation.zio._
import com.normation.rudder.domain.logger.PolicyLoggerPure
import scalaz.zio._
import scalaz.zio.syntax._

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
 * The main service which deploy modified rules and
 * their dependencies.
 */
trait PromiseGenerationService {

  /**
   * Define how a node should be sorted in the list of nodes.
   * We want root first, then relay with node just above, then relay, then nodes
   */
  def nodePriority(nodeInfo: NodeInfo): Int = {
    if(nodeInfo.id.value == "root") 0
    else if(nodeInfo.isPolicyServer) {
      if(nodeInfo.policyServerId.value == "root") 1 else 2
    } else 3
  }

  /**
   * All mighy method that take all modified rules, find their
   * dependencies, proccess ${vars}, build the list of node to update,
   * update nodes.
   *
   * Return the list of node IDs actually updated.
   *
   */
  def deploy() : Box[Set[NodeId]] = {
    PolicyLogger.info("Start policy generation, checking updated rules")

    val initialTime = System.currentTimeMillis

    val generationTime = new DateTime(initialTime)
    val rootNodeId = Constants.ROOT_POLICY_SERVER_ID
    //we need to add the current environment variables to the script context
    //plus the script environment variables used as script parameters
    import scala.collection.JavaConverters._
    val systemEnv = HookEnvPairs.build(System.getenv.asScala.toSeq:_*)

    import HooksImplicits._

    def buildFullyOneNode(
        nodeId            : NodeId
      , nodeConfigId      : NodeConfigId
      , rootNodeId        : NodeId
      , nodeConfigs       : Map[NodeId, NodeConfiguration]
      , existingHash      : Option[String]
      , allLicenses       : Map[NodeId, CfeEnterpriseLicense]
      , techniqueResources: TechniqueResources
      , globalPolicyMode  : GlobalPolicyMode
      , generationTime    : DateTime
      , expectedReports   : NodeExpectedReports
      , remainingNodes    : Ref[Int]
    ): ZIO[blocking.Blocking, RudderError, NodeExpectedReports] = {

    val traceId = "[" + nodeId.value + "]"

      blocking.blocking(for {
        writeTime             <- IOResult.effect(System.currentTimeMillis)
        writtenNodeConfigs    <- writeOneNodeConfigurations(rootNodeId, nodeId, nodeConfigId, nodeConfigs, existingHash, allLicenses, techniqueResources, globalPolicyMode, generationTime).chainError(s"Cannot write nodes configuration for node '${nodeId.value}'")
        timeWriteNodeConfig   <- IOResult.effect(System.currentTimeMillis - writeTime)
        _                     <- PolicyLoggerPure.debug(s"${traceId} Node configuration for node '${nodeId.value}' written in ${timeWriteNodeConfig} ms.")

        saveExpectedTime      <- IOResult.effect(System.currentTimeMillis)
        savedExpectedReports  <- saveOneExpectedReports(expectedReports).toIO.chainError("Error when saving expected reports for node '${nodeId.value}'")
        endTime               <- IOResult.effect(System.currentTimeMillis)
        timeSaveExpected      <- IOResult.effect(endTime - saveExpectedTime)
        _                     <- PolicyLoggerPure.debug(s"${traceId} Node expected reports for node '${nodeId.value}' saved in base in ${timeSaveExpected} ms.")
        r                     <- remainingNodes.update(_ - 1)
        _                     <- PolicyLoggerPure.info(s"Configuration for node ${nodeId.value} updated in ${endTime - writeTime} ms (remains ${r} nodes to update)")
      } yield {
        savedExpectedReports
      })
    }

    /*
     * Write nodes is the methods that write all nodes and convert to the list of success, never fails
     */
    def writeConfigs(
        rootNodeId        : NodeId
      , nodeConfigs       : Map[NodeId, NodeConfiguration]
      , nodeConfigIds     : Map[NodeId, NodeConfigId]
      , existingHashed    : Map[NodeId, Option[String]]
      , allLicenses       : Map[NodeId, CfeEnterpriseLicense]
      , techniqueResources: TechniqueResources
      , globalPolicyMode  : GlobalPolicyMode
      , generationTime    : DateTime
      , expectedReports   : List[NodeExpectedReports]
      , maxParallelism    : Int
    ): List[Either[RudderError, NodeExpectedReports]] = {
      val allTrySavedReports = for {
        remainingNodes        <- Ref.make[Int](nodeConfigs.size)
        /// we need to start with policy servers ///
        sortedNodeIds         =  nodeConfigs.toSeq.map { case (id, v) =>
                                   (
                                     id
                                   , nodePriority(v.nodeInfo)
                                   )
                                 }.sortBy( _._2 ).map( _._1 )
        allTrySavedReports    <- ZIO.collectAllParN(maxParallelism.toLong)(sortedNodeIds.map { nodeId =>
                                   (for {
                                     expectedReport <- expectedReports.find(_.nodeId == nodeId).succeed.notOptional(s"Can not find expected report for node '${nodeId.value}'")
                                     built          <- buildFullyOneNode(nodeId, nodeConfigIds(nodeId), rootNodeId, nodeConfigs, existingHashed(nodeId), allLicenses, techniqueResources, globalPolicyMode, generationTime, expectedReport, remainingNodes)
                                   } yield {
                                     built
                                   }).either
                                 })
      } yield {
        allTrySavedReports
      }

      allTrySavedReports.provide(ZioRuntime.Environment).runNow
    }

    /*
     * The computation of dynamic group is a workaround inconsistencies after importing definition
     * from LDAP, see: https://issues.rudder.io/issues/14758
     * But that computation may lead to a huge delay in generation (tens of minutes). We want to be
     * able to unset their computation in some case through an environement variable.
     *
     * To disable it, set environment variable "rudder.generation.computeDynGroup" to "disabled"
     */
    val computeDynGroupsEnabled = getComputeDynGroups().getOrElse(true)

    val maxParallelism = {
      // We want to limit the number of parallel execution and threads to the number of core/2 (minimum 1) by default.
      // This is taken from the system environment variable because we really want to be able to change it at runtime.
      def threadForProc(mult: Int): Int = {
        Math.max(1, (java.lang.Runtime.getRuntime.availableProcessors * mult / 2).toDouble.ceil.toInt)
      }
      val t = try {
        getMaxParallelism().getOrElse("x1") match {
          case s if s.charAt(0) == 'x' => threadForProc(s.substring(1).toInt)
          case other => other.toInt
        }
      } catch {
        case ex: IllegalArgumentException => threadForProc(1)
      }
      if(t < 1) {
        PolicyLogger.warn(s"You can't set 'rudder_generation_max_parallelism' so that there is less than 1 thread for generation")
        1
      } else {
        t
      }
    }
    val jsTimeout = {
      // by default 5s but can be overrided
      val t = getJsTimeout().getOrElse(5)
      FiniteDuration(try {
        Math.max(1, t.toLong) // must be a positive number
      } catch {
        case ex: NumberFormatException =>
          PolicyLogger.error(s"Impossible to user property '${t}' for js engine timeout: not a number")
          5L // default
      }, TimeUnit.SECONDS)
    }

    PolicyLogger.debug(s"Policy generation parrallelism set to: ${maxParallelism} (change with REST API settings parameter 'rudder_generation_max_parallelism')")
    PolicyLogger.debug(s"Policy generation JS eveluation of directive parameter timeout: ${jsTimeout} s (change with REST API settings parameter 'rudder_generation_jsTimeout')")

    implicit val ioscheduler = Scheduler.io(executionModel = ExecutionModel.AlwaysAsyncExecution)

    val result = for {
      // trigger a dynamic group update
      _                    <- if(computeDynGroupsEnabled) {
                                triggerNodeGroupUpdate()
                              } else {
                                PolicyLogger.warn(s"Computing dynamic groups disable by REST API settings 'rudder_generation_compute_dyngroups'")
                                Full(())
                              }
      timeComputeGroups    =  (System.currentTimeMillis - initialTime)
      _                    =  PolicyLogger.debug(s"Computing dynamic groups finished in ${timeComputeGroups} ms")
      preGenHooksTime      =  System.currentTimeMillis

      //fetch all
      preHooks             <- RunHooks.getHooks(HOOKS_D + "/policy-generation-started", HOOKS_IGNORE_SUFFIXES)
      _                    <- RunHooks.syncRun(preHooks, HookEnvPairs.build( ("RUDDER_GENERATION_DATETIME", generationTime.toString) ), systemEnv)
      timeRunPreGenHooks   =  (System.currentTimeMillis - preGenHooksTime)
      _                    =  PolicyLogger.debug(s"Pre-policy-generation scripts hooks ran in ${timeRunPreGenHooks} ms")

      codePreGenHooksTime  =  System.currentTimeMillis
      _                    <- beforeDeploymentSync(generationTime)
      timeCodePreGenHooks  =  (System.currentTimeMillis - codePreGenHooksTime)
      _                    =  PolicyLogger.debug(s"Pre-policy-generation modules hooks in ${timeCodePreGenHooks} ms, start getting all generation related data.")

      fetch0Time           =  System.currentTimeMillis
      allRules             <- findDependantRules() ?~! "Could not find dependant rules"
      fetch1Time           =  System.currentTimeMillis
      _                    =  PolicyLogger.trace(s"Fetched rules in ${fetch1Time-fetch0Time} ms")
      allNodeInfos         <- getAllNodeInfos.map( _.filter { case(_,n) =>
                                if(n.state == NodeState.Ignored) {
                                  PolicyLogger.debug(s"Skipping node '${n.id.value}' because the node is in state '${n.state.name}'")
                                  false
                                } else true
                              })  ?~! "Could not get Node Infos" //disabled node don't get new policies
      fetch2Time           =  System.currentTimeMillis
      _                    =  PolicyLogger.trace(s"Fetched node infos in ${fetch2Time-fetch1Time} ms")
      directiveLib         <- getDirectiveLibrary() ?~! "Could not get the directive library"
      fetch3Time           =  System.currentTimeMillis
      _                    =  PolicyLogger.trace(s"Fetched directives in ${fetch3Time-fetch2Time} ms")
      groupLib             <- getGroupLibrary() ?~! "Could not get the group library"
      fetch4Time           =  System.currentTimeMillis
      _                    =  PolicyLogger.trace(s"Fetched groups in ${fetch4Time-fetch3Time} ms")
      allParameters        <- getAllGlobalParameters ?~! "Could not get global parameters"
      fetch5Time           =  System.currentTimeMillis
      _                    =  PolicyLogger.trace(s"Fetched global parameters in ${fetch5Time-fetch4Time} ms")
      globalAgentRun       <- getGlobalAgentRun
      fetch6Time           =  System.currentTimeMillis
      _                    =  PolicyLogger.trace(s"Fetched run infos in ${fetch6Time-fetch5Time} ms")
      scriptEngineEnabled  <- getScriptEngineEnabled() ?~! "Could not get if we should use the script engine to evaluate directive parameters"
      globalComplianceMode <- getGlobalComplianceMode
      globalPolicyMode     <- getGlobalPolicyMode() ?~! "Cannot get the Global Policy Mode (Enforce or Verify)"
      nodeConfigCaches     <- getNodeConfigurationHash() ?~! "Cannot get the Configuration Cache"
      allLicenses          <- getAllLicenses() ?~! "Cannont get licenses information"
      allNodeModes         =  buildNodeModes(allNodeInfos, globalComplianceMode, globalAgentRun, globalPolicyMode)
      timeFetchAll         =  (System.currentTimeMillis - fetch0Time)
      _                    =  PolicyLogger.debug(s"All relevant information fetched in ${timeFetchAll} ms, start names historization.")

      /////
      ///// end of inputs, all information gathered for promise generation.
      /////

      ///// this thing has nothing to do with promise generation and should be
      ///// else where. You can ignore it if you want to understand generation process.
      historizeTime =  System.currentTimeMillis
      historize     <- historizeData(allRules, directiveLib, groupLib, allNodeInfos, globalAgentRun)
      timeHistorize =  (System.currentTimeMillis - historizeTime)
      _             =  PolicyLogger.debug(s"Historization of names done in ${timeHistorize} ms, start to build rule values.")
      ///// end ignoring

      ///// parse rule for directive parameters and build node context that will be used for them
      ///// also restrict generation to only active rules & nodes:
      ///// - number of nodes: only node somehow targetted by a rule have to be considered.
      ///// - number of rules: any rule without target or with only target with no node can be skipped

      ruleValTime           =  System.currentTimeMillis
      activeRuleIds         =  getAppliedRuleIds(allRules, groupLib, directiveLib, allNodeInfos)
      ruleVals              <- buildRuleVals(activeRuleIds, allRules, directiveLib, groupLib, allNodeInfos) ?~! "Cannot build Rule vals"
      timeRuleVal           =  (System.currentTimeMillis - ruleValTime)
      _                     =  PolicyLogger.debug(s"RuleVals built in ${timeRuleVal} ms, start to expand their values.")

      nodeContextsTime      =  System.currentTimeMillis
      activeNodeIds         =  (Set[NodeId]()/:ruleVals){case(s,r) => s ++ r.nodeIds}
      nodeContexts          <- getNodeContexts(activeNodeIds, allNodeInfos, groupLib, allLicenses, allParameters, globalAgentRun, globalComplianceMode, globalPolicyMode) ?~! "Could not get node interpolation context"
      timeNodeContexts      =  (System.currentTimeMillis - nodeContextsTime)
      _                     =  PolicyLogger.debug(s"Node contexts built in ${timeNodeContexts} ms, start to build new node configurations.")

      buildConfigTime       =  System.currentTimeMillis
      /// here, we still have directive by directive info
      allNodeConfigs        <- buildNodeConfigurations(activeNodeIds, ruleVals, nodeContexts, allNodeModes, scriptEngineEnabled, globalPolicyMode, maxParallelism, jsTimeout) ?~! "Cannot build target configuration node"
      /// only keep successfull node config. We will keep the failed one to fail the whole process in the end if needed
      nodeConfigs           =  allNodeConfigs.ok.map(c => (c.nodeInfo.id, c)).toMap
      timeBuildConfig       =  (System.currentTimeMillis - buildConfigTime)
      _                     =  PolicyLogger.debug(s"Node's target configuration built in ${timeBuildConfig} ms, start to update rule values.")

      updatedNodeConfigIds  =  getNodesConfigVersion(nodeConfigs, nodeConfigCaches, generationTime)
      updatedNodeConfigs    =  nodeConfigs.filterKeys(id => updatedNodeConfigIds.keySet.contains(id))

      reportTime            =  System.currentTimeMillis
      expectedReports       =  computeExpectedReports(ruleVals, updatedNodeConfigs.values.toSeq, updatedNodeConfigIds, generationTime, allNodeModes)
      timeSetExpectedReport =  (System.currentTimeMillis - reportTime)
      _                     =  PolicyLogger.debug(s"Reports updated in ${timeSetExpectedReport} ms")

      ///// so now we have everything for each updated nodes, we can start writing node policies and then expected reports

      // we want to remove cache for nodes for which we didn't successfully get a config (redo next time)
      // and also remove all non active nodes (deleted, etc) => only keep the one for which we have a node configuration
      existingHashes        <- forgetOtherNodeConfigurationState(allNodeConfigs.ok.map(_.nodeInfo.id).toSet) ?~! "Cannot clean the configuration cache"

      // clear cache of string template - both before and after writing them
      techniqueResources    <- getTechniquesResources(updatedNodeConfigIds.keySet, nodeConfigs, maxParallelism).toBox
      _                     =  clearCacheOfTemplate()


      //////  write new configurations
      writeTime             =  System.currentTimeMillis
      allTrySavedReports    =  writeConfigs(rootNodeId, nodeConfigs, updatedNodeConfigIds, existingHashes, allLicenses, techniqueResources, globalPolicyMode, generationTime, expectedReports, maxParallelism)
      savedExpectedReports  =  allTrySavedReports.collect { case Right(x) => x }

      timeWriteNodeConfig   =  (System.currentTimeMillis - writeTime)
      _                     =  clearCacheOfTemplate()


      // finally, run post-generation hooks. They can lead to an error message for build, but node policies are updated
      postHooksTime         =  System.currentTimeMillis
      postHooks             <- RunHooks.getHooks(HOOKS_D + "/policy-generation-finished", HOOKS_IGNORE_SUFFIXES)

      // we want to sort node with root first, then relay, then other nodes for hooks
      updatedNodeIds        =  savedExpectedReports.map { r =>
                               val id = r.nodeId
                                 (
                                   id
                                 , nodePriority(updatedNodeConfigs(id).nodeInfo)
                                 )
                               }.sortBy( _._2 ).map( _._1 )

      stringIds             =  updatedNodeIds.map(_.value)
      _                     <- RunHooks.syncRun(
                                   postHooks
                                 , HookEnvPairs.build(
                                                         ("RUDDER_GENERATION_DATETIME", generationTime.toString())
                                                       , ("RUDDER_END_GENERATION_DATETIME", new DateTime(postHooksTime).toString) //what is the most alike a end time
                                                       , ("RUDDER_NODE_IDS", stringIds.mkString(" "))
                                                       , ("RUDDER_NUMBER_NODES_UPDATED", stringIds.size.toString)
                                                       , ("RUDDER_ROOT_POLICY_SERVER_UPDATED", if(stringIds.contains("root")) "0" else "1" )
                                                         //for compat in 4.1. Remove in 4.2 or up.
                                                       , ("RUDDER_NODEIDS", stringIds.mkString(" "))
                                                     )
                                 , systemEnv
                               )
      timeRunPostGenHooks   =  (System.currentTimeMillis - postHooksTime)
      _                     =  PolicyLogger.debug(s"Post-policy-generation hooks ran in ${timeRunPostGenHooks} ms")

      /// now, if there was failed config or failed write, time to show them
      //invalidate compliance may be very very long - make it async
      updatedNodes          = updatedNodeIds.toSet
      errorNodes            = activeNodeIds -- updatedNodes
      _                     = Future { invalidateComplianceCache(updatedNodeConfigs.keySet) }
      _                     = {
                                PolicyLogger.info("Timing summary:")
                                PolicyLogger.info("Run pre-gen scripts hooks     : %10s ms".format(timeRunPreGenHooks))
                                PolicyLogger.info("Run pre-gen modules hooks     : %10s ms".format(timeCodePreGenHooks))
                                PolicyLogger.info("Fetch all information         : %10s ms".format(timeFetchAll))
                                PolicyLogger.info("Historize names               : %10s ms".format(timeHistorize))
                                PolicyLogger.info("Build current rule values     : %10s ms".format(timeRuleVal))
                                PolicyLogger.info("Build target configuration    : %10s ms".format(timeBuildConfig))
                                PolicyLogger.info("Write all node configurations : %10s ms".format(timeWriteNodeConfig))
                                PolicyLogger.info("Run post generation hooks     : %10s ms".format(timeRunPostGenHooks))
                                PolicyLogger.info("Number of nodes updated       : %10s   ".format(updatedNodeIds.size))
                                if(errorNodes.nonEmpty) {
                                  PolicyLogger.warn(s"Nodes in errors (${errorNodes.size}): '${errorNodes.map(_.value).mkString("','")}'")
                                }
      }
      allErrors             =  allNodeConfigs.errors.map(_.failure.messageChain) ++ allTrySavedReports.collect { case Left(x) => x }
      result                <- if (allErrors.isEmpty) {
                                 Full(updatedNodes)
                               } else {
                                 Failure(s"Generation ended but some nodes were in errors: ${allErrors.mkString(" ; ")}")
                               }
    } yield {
      result
    }
    PolicyLogger.info("Policy generation completed in: %10s".format(new Period(System.currentTimeMillis - initialTime).toString()))
    result
  }

  /**
   * Snapshot all information needed:
   * - node infos
   * - rules
   * - directives library
   * - groups library
   */
  def getAllNodeInfos(): Box[Map[NodeId, NodeInfo]]
  def getDirectiveLibrary(): Box[FullActiveTechniqueCategory]
  def getGroupLibrary(): Box[FullNodeGroupCategory]
  def getAllGlobalParameters: Box[Seq[GlobalParameter]]
  def getAllInventories(): Box[Map[NodeId, NodeInventory]]
  def getGlobalComplianceMode(): Box[GlobalComplianceMode]
  def getGlobalAgentRun() : Box[AgentRunInterval]
  def getAllLicenses(): Box[Map[NodeId, CfeEnterpriseLicense]]
  def getAgentRunInterval    : () => Box[Int]
  def getAgentRunSplaytime   : () => Box[Int]
  def getAgentRunStartHour   : () => Box[Int]
  def getAgentRunStartMinute : () => Box[Int]
  def getScriptEngineEnabled : () => Box[FeatureSwitch]
  def getGlobalPolicyMode    : () => Box[GlobalPolicyMode]
  def getComputeDynGroups        : () => Box[Boolean]
  def getMaxParallelism      : () => Box[String]
  def getJsTimeout           : () => Box[Int]

  // Trigger dynamic group update
  def triggerNodeGroupUpdate(): Box[Unit]

  // code hooks
  def beforeDeploymentSync(generationTime: DateTime): Box[Unit]

  // base folder for hooks. It's a string because there is no need to get it from config
  // file, it's just a constant.
  def HOOKS_D                : String
  def HOOKS_IGNORE_SUFFIXES  : List[String]

  /*
   * From global configuration and node modes, build node modes
   */
  def buildNodeModes(nodes: Map[NodeId, NodeInfo], globalComplianceMode: GlobalComplianceMode, globalAgentRun: AgentRunInterval, globalPolicyMode: GlobalPolicyMode) = {
    nodes.map { case (id, info) =>
      (id, NodeModeConfig(
          globalComplianceMode
        , info.nodeReportingConfiguration.heartbeatConfiguration match {
            case Some(HeartbeatConfiguration(true, i)) => Some(i)
            case _                                     => None
          }
        , globalAgentRun
        , info.nodeReportingConfiguration.agentRunInterval
        , globalPolicyMode
        , info.policyMode
      ) )
    }.toMap
  }

  /*
   * That method clear the cache of parsed StringTemplate
   */
  def clearCacheOfTemplate(): Unit

  def getAppliedRuleIds(rules:Seq[Rule], groupLib: FullNodeGroupCategory, directiveLib: FullActiveTechniqueCategory, allNodeInfos: Map[NodeId, NodeInfo]): Set[RuleId]

  /**
   * Find all modified rules.
   * For them, find all directives with variables
   * referencing these rules.
   * Add them to the set of rules to return, and
   * recurse.
   * Stop when convergence is reached
   *
   * No modification on back-end are performed
   * (perhaps safe setting the "isModified" value to "true" for
   * all dependent CR).
   *
   */
  def findDependantRules() : Box[Seq[Rule]]

  /**
   * Rule vals are just rules with a analysis of parameter
   * on directive done, so that we will be able to bind them
   * to a context latter.
   */
  def buildRuleVals(
      activesRules: Set[RuleId]
    , rules       : Seq[Rule]
    , directiveLib: FullActiveTechniqueCategory
    , groupLib    : FullNodeGroupCategory
    , allNodeInfos: Map[NodeId, NodeInfo]
  ) : Box[Seq[RuleVal]]

  def getNodeContexts(
      nodeIds               : Set[NodeId]
    , allNodeInfos          : Map[NodeId, NodeInfo]
    , allGroups             : FullNodeGroupCategory
    , allLicenses           : Map[NodeId, CfeEnterpriseLicense]
    , globalParameters      : Seq[GlobalParameter]
    , globalAgentRun        : AgentRunInterval
    , globalComplianceMode  : ComplianceMode
    , globalPolicyMode      : GlobalPolicyMode
  ): Box[Map[NodeId, InterpolationContext]]

  /*
   * From a list of ruleVal, find the list of all impacted nodes
   * with the actual PolicyBean they will have.
   * Replace all ${node.varName} vars.
   */
  def buildNodeConfigurations(
      activeNodeIds      : Set[NodeId]
    , ruleVals           : Seq[RuleVal]
    , nodeContexts       : Map[NodeId, InterpolationContext]
    , allNodeModes       : Map[NodeId, NodeModeConfig]
    , scriptEngineEnabled: FeatureSwitch
    , globalPolicyMode   : GlobalPolicyMode
    , maxParallelism     : Int
    , jsTimeout          : FiniteDuration
  ) : Box[NodeConfigurations]

  /**
   * Forget all other node configuration state.
   * If passed with an empty set, actually forget all node configuration.
   * Return the map of existing serialized hashes.
   * Invariant: keep == returnedMap.keySet
   */
  def forgetOtherNodeConfigurationState(keep: Set[NodeId]) : Box[Map[NodeId, Option[String]]]

  /**
   * Get the actual cached values for NodeConfiguration
   */
  def getNodeConfigurationHash(): Box[Map[NodeId, NodeConfigurationHash]]

  /**
   * For each nodeConfiguration, check if the config is updated.
   * If so, return the new configId.
   */
  def getNodesConfigVersion(allNodeConfigs: Map[NodeId, NodeConfiguration], hashes: Map[NodeId, NodeConfigurationHash], generationTime: DateTime): Map[NodeId, NodeConfigId]


  def getTechniquesResources(
      nodesToWrite    : Set[NodeId]
    , allNodeConfigs  : Map[NodeId, NodeConfiguration]
    , maxParallelist  : Int
  ): IOResult[TechniqueResources]

  /**
   * Actually  write the new configuration for the list of given node.
   * If the node target configuration is the same as the actual, nothing is done.
   * Else, promises are generated;
   * Return the list of configuration successfully written.
   */
  def writeNodeConfigurations(
      rootNodeId        : NodeId
    , updated           : Map[NodeId, NodeConfigId]
    , allNodeConfig     : Map[NodeId, NodeConfiguration]
    , allLicenses       : Map[NodeId, CfeEnterpriseLicense]
    , techniqueResources: TechniqueResources
    , globalPolicyMode  : GlobalPolicyMode
    , generationTime    : DateTime
    , maxParallelism    : Int
  ) : IOResult[Set[NodeConfiguration]]

  def writeOneNodeConfigurations(
      rootNodeId        : NodeId
    , updatedNodeId     : NodeId
    , updatedConfigId   : NodeConfigId
    , allNodeConfig     : Map[NodeId, NodeConfiguration]
    , existingHash      : Option[String]
    , allLicenses       : Map[NodeId, CfeEnterpriseLicense]
    , techniqueResources: TechniqueResources
    , globalPolicyMode  : GlobalPolicyMode
    , generationTime    : DateTime
  ) : IOResult[Set[NodeConfiguration]]

  /**
   * Compute expected reports for each node
   * (that does not save them in base)
   */
  def computeExpectedReports(
      ruleVal          : Seq[RuleVal]
    , nodeConfigs      : Seq[NodeConfiguration]
    , versions         : Map[NodeId, NodeConfigId]
    , generationTime   : DateTime
    , allNodeModes     : Map[NodeId, NodeModeConfig]
  ) : List[NodeExpectedReports]

  /**
   * Save expected reports in database
   */
  def saveExpectedReports(
      expectedReports  : List[NodeExpectedReports]
  ) : Box[Seq[NodeExpectedReports]]

  def saveOneExpectedReports(expectedReports: NodeExpectedReports): Box[NodeExpectedReports] = {
    saveExpectedReports(List(expectedReports)).flatMap {
      case Nil => Failure(s"Error when saving expected repot in base for node '${expectedReports.nodeId.value}' (no error info)")
      case x::Nil => Full(x)
      case x::y =>
        PolicyLogger.warn(s"More than on reports returned when saving expected reports for node '${expectedReports.nodeId.value}'")
        Full(x)
    }
  }

  /**
   * After updates of everything, notify compliace cache
   * that it should forbid what it knows about the updated nodes
   */
  def invalidateComplianceCache(nodeIds: Set[NodeId]): Unit

  /**
   * Store groups and directive in the database
   */
  def historizeData(
      rules            : Seq[Rule]
    , directiveLib     : FullActiveTechniqueCategory
    , groupLib         : FullNodeGroupCategory
    , allNodeInfos     : Map[NodeId, NodeInfo]
    , globalAgentRun   : AgentRunInterval
  ) : Box[Unit]

}

///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//  Implémentation
///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

class PromiseGenerationServiceImpl (
    override val roRuleRepo: RoRuleRepository
  , override val woRuleRepo: WoRuleRepository
  , override val ruleValService : RuleValService
  , override val systemVarService: SystemVariableService
  , override val nodeConfigurationService : NodeConfigurationHashRepository
  , override val nodeInfoService : NodeInfoService
  , override val licenseRepository: LicenseRepository
  , override val confExpectedRepo : UpdateExpectedReportsRepository
  , override val historizationService : HistorizationService
  , override val roNodeGroupRepository: RoNodeGroupRepository
  , override val roDirectiveRepository: RoDirectiveRepository
  , override val ruleApplicationStatusService: RuleApplicationStatusService
  , override val parameterService : RoParameterService
  , override val interpolatedValueCompiler:InterpolatedValueCompiler
  , override val roInventoryRepository: ReadOnlyFullInventoryRepository
  , override val complianceModeService : ComplianceModeService
  , override val agentRunService : AgentRunIntervalService
  , override val complianceCache  : CachedFindRuleNodeStatusReports
  , override val promisesFileWriterService: PolicyWriterService
  , override val fillTemplateService: FillTemplatesService
  , override val getAgentRunInterval: () => Box[Int]
  , override val getAgentRunSplaytime: () => Box[Int]
  , override val getAgentRunStartHour: () => Box[Int]
  , override val getAgentRunStartMinute: () => Box[Int]
  , override val getScriptEngineEnabled: () => Box[FeatureSwitch]
  , override val getGlobalPolicyMode: () => Box[GlobalPolicyMode]
  , override val getComputeDynGroups: () => Box[Boolean]
  , override val getMaxParallelism  : () => Box[String]
  , override val getJsTimeout       : () => Box[Int]
  , override val HOOKS_D: String
  , override val HOOKS_IGNORE_SUFFIXES: List[String]
) extends PromiseGenerationService with
  PromiseGeneration_performeIO with
  PromiseGeneration_buildRuleVals with
  PromiseGeneration_buildNodeConfigurations with
  PromiseGeneration_updateAndWriteRule with
  PromiseGeneration_setExpectedReports with
  PromiseGeneration_historization with
  PromiseGenerationHooks {

  private[this] val codeHooks = collection.mutable.Buffer[PromiseGenerationHooks]()

  private[this] var dynamicsGroupsUpdate : Option[UpdateDynamicGroups] = None

  override def beforeDeploymentSync(generationTime: DateTime): Box[Unit] = {
    sequence(codeHooks) { _.beforeDeploymentSync(generationTime) }.map( _ => () )
  }

  def appendPreGenCodeHook(hook: PromiseGenerationHooks): Unit = {
    this.codeHooks.append(hook)
  }

  // We need to register the update dynamic group after instantiation of the class
  // as the deployment service, the async deployment and the dynamic group update have cyclic dependencies
  def setDynamicsGroupsService(updateDynamicGroups : UpdateDynamicGroups): Unit = {
    this.dynamicsGroupsUpdate = Some(updateDynamicGroups)
  }

  override def triggerNodeGroupUpdate() : Box[Unit] = {
    dynamicsGroupsUpdate.map(groupUpdate(_)).getOrElse(Failure("Dynamic group update is not registered, this is an error"))

  }
  private[this] def groupUpdate(updateDynamicGroups : UpdateDynamicGroups): Box[Unit] = {
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

    Full(Unit)
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
  def roRuleRepo : RoRuleRepository
  def nodeInfoService: NodeInfoService
  def roNodeGroupRepository: RoNodeGroupRepository
  def roDirectiveRepository: RoDirectiveRepository
  def parameterService : RoParameterService
  def roInventoryRepository: ReadOnlyFullInventoryRepository
  def complianceModeService : ComplianceModeService
  def agentRunService : AgentRunIntervalService

  def interpolatedValueCompiler:InterpolatedValueCompiler
  def systemVarService: SystemVariableService
  def ruleApplicationStatusService: RuleApplicationStatusService

  def licenseRepository: LicenseRepository

  def getGlobalPolicyMode: () => Box[GlobalPolicyMode]

  override def findDependantRules() : Box[Seq[Rule]] = roRuleRepo.getAll(true).toBox
  override def getAllNodeInfos(): Box[Map[NodeId, NodeInfo]] = nodeInfoService.getAll
  override def getDirectiveLibrary(): Box[FullActiveTechniqueCategory] = roDirectiveRepository.getFullDirectiveLibrary().toBox
  override def getGroupLibrary(): Box[FullNodeGroupCategory] = roNodeGroupRepository.getFullGroupLibrary().toBox
  override def getAllGlobalParameters: Box[Seq[GlobalParameter]] = parameterService.getAllGlobalParameters()
  override def getAllInventories(): Box[Map[NodeId, NodeInventory]] = roInventoryRepository.getAllNodeInventories(AcceptedInventory).toBox
  override def getGlobalComplianceMode(): Box[GlobalComplianceMode] = complianceModeService.getGlobalComplianceMode
  override def getGlobalAgentRun(): Box[AgentRunInterval] = agentRunService.getGlobalAgentRun()
  override def getAllLicenses(): Box[Map[NodeId, CfeEnterpriseLicense]] = licenseRepository.getAllLicense().toBox
  override def getAppliedRuleIds(rules:Seq[Rule], groupLib: FullNodeGroupCategory, directiveLib: FullActiveTechniqueCategory, allNodeInfos: Map[NodeId, NodeInfo]): Set[RuleId] = {
     rules.filter(r => ruleApplicationStatusService.isApplied(r, groupLib, directiveLib, allNodeInfos) match {
      case _:AppliedStatus => true
      case _ => false
    }).map(_.id).toSet

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
  override def getNodeContexts(
      nodeIds               : Set[NodeId]
    , allNodeInfos          : Map[NodeId, NodeInfo]
    , allGroups             : FullNodeGroupCategory
    , allLicenses           : Map[NodeId, CfeEnterpriseLicense]
    , globalParameters      : Seq[GlobalParameter]
    , globalAgentRun        : AgentRunInterval
    , globalComplianceMode  : ComplianceMode
    , globalPolicyMode      : GlobalPolicyMode
  ): Box[Map[NodeId, InterpolationContext]] = {

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
    def buildParams(parameters: Seq[GlobalParameter]): Box[Map[ParameterName, InterpolationContext => Box[String]]] = {
      bestEffort(parameters) { param =>
        for {
          p <- interpolatedValueCompiler.compile(param.value) ?~! s"Error when looking for interpolation variable in global parameter '${param.name}'"
        } yield {
          (param.name, p)
        }
      }.map( _.toMap)
    }

    for {
      globalSystemVariables <- systemVarService.getGlobalSystemVariables(globalAgentRun)
      parameters            <- buildParams(globalParameters) ?~! "Can not parsed global parameter (looking for interpolated variables)"
    } yield {
      (nodeIds.flatMap { nodeId:NodeId =>
        (for {
          nodeInfo     <- Box(allNodeInfos.get(nodeId)) ?~! s"Node with ID ${nodeId.value} was not found"
          policyServer <- Box(allNodeInfos.get(nodeInfo.policyServerId)) ?~! s"Node with ID ${nodeId.value} was not found"

          nodeContext  <- systemVarService.getSystemVariables(nodeInfo, allNodeInfos, allGroups, allLicenses, globalSystemVariables, globalAgentRun, globalComplianceMode  : ComplianceMode)
        } yield {
          (nodeId, InterpolationContext(
                        nodeInfo
                      , policyServer
                      , globalPolicyMode
                      , nodeContext
                      , parameters
                    )
          )
        }) match {
          case eb:EmptyBox =>
            val e = eb ?~! s"Error while building target configuration node for node '${nodeId.value}' which is one of the target of rules. Ignoring it for the rest of the process"
            PolicyLogger.error(e.messageChain)
            None

          case x => x
        }
      }).toMap
    }
  }

}

///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

trait PromiseGeneration_buildRuleVals extends PromiseGenerationService {

  def ruleValService: RuleValService

  override def buildRuleVals(
      activeRuleIds: Set[RuleId]
    , rules        : Seq[Rule]
    , directiveLib : FullActiveTechniqueCategory
    , allGroups    : FullNodeGroupCategory
    , allNodeInfos : Map[NodeId, NodeInfo]
  ) : Box[Seq[RuleVal]] = {

    val appliedRules = rules.filter(r => activeRuleIds.contains(r.id))
    for {
      rawRuleVals <- bestEffort(appliedRules) { rule => ruleValService.buildRuleVal(rule, directiveLib, allGroups, allNodeInfos) } ?~! "Could not find configuration vals"
    } yield rawRuleVals
  }

}

///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

trait PromiseGeneration_buildNodeConfigurations extends PromiseGenerationService {
  override def buildNodeConfigurations(
      activeNodeIds      : Set[NodeId]
    , ruleVals           : Seq[RuleVal]
    , nodeContexts       : Map[NodeId, InterpolationContext]
    , allNodeModes       : Map[NodeId, NodeModeConfig]
    , scriptEngineEnabled: FeatureSwitch
    , globalPolicyMode   : GlobalPolicyMode
    , maxParallelism     : Int
    , jsTimeout          : FiniteDuration
  ) : Box[NodeConfigurations] = BuildNodeConfiguration.buildNodeConfigurations(activeNodeIds, ruleVals, nodeContexts, allNodeModes, scriptEngineEnabled, globalPolicyMode, maxParallelism, jsTimeout)

}

final case class NodeConfigurationError(failure: Failure)
final case class NodeConfigurations(
    ok    : List[NodeConfiguration]
  , errors: List[NodeConfigurationError]
)

object BuildNodeConfiguration extends Loggable {

  /*
   * Utility class that helps deduplicate same failures in a chain
   * of failure when using bestEffort.
   */
  implicit class DedupFailure[T](box: Box[T]) {
    def dedupFailures(failure: String, transform: String => String = identity) = {
      box match { //dedup error messages
          case Full(res)   => Full(res)
          case eb:EmptyBox =>
            val msg = eb match {
              case Empty      => ""
              case f: Failure => //here, dedup
                ": " + f.failureChain.map(m => transform(m.msg).trim).toSet.mkString("; ")
            }
            Failure(failure + msg)
      }
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
      activeNodeIds      : Set[NodeId]
    , ruleVals           : Seq[RuleVal]
    , nodeContexts       : Map[NodeId, InterpolationContext]
    , allNodeModes       : Map[NodeId, NodeModeConfig]
    , scriptEngineEnabled: FeatureSwitch
    , globalPolicyMode   : GlobalPolicyMode
    , maxParallelism     : Int
    , jsTimeout          : FiniteDuration
  ) : Box[NodeConfigurations] = {

    //step 1: from RuleVals to expanded rules vals

    //group by nodes
    //no consistancy / unicity check is done here, it will be done
    //in an other phase. We are just switching to a node-first view.
    val policyDraftByNode: Map[NodeId, Seq[ParsedPolicyDraft]] = {
      val byNodeDrafts = scala.collection.mutable.Map.empty[NodeId, Vector[ParsedPolicyDraft]]
      ruleVals.foreach { rule =>
        rule.nodeIds.foreach { nodeId =>
          byNodeDrafts.update(nodeId, byNodeDrafts.getOrElse(nodeId, Vector[ParsedPolicyDraft]()) ++ rule.parsedPolicyDrafts)
        }
      }
      byNodeDrafts.toMap
    }


    // 1.3: build node config, binding ${rudder./node.properties} parameters
    // open a scope for the JsEngine, because its init is long.
    // Hardcoded 4 thread for test case.
    JsEngineProvider.withNewEngine(scriptEngineEnabled, maxParallelism, jsTimeout) { jsEngine =>

      val nodeConfigs = ZIO.foreachParN(maxParallelism.toLong)(nodeContexts.toSeq) { case (nodeId, context) =>

          (for {
            parsedDrafts  <- policyDraftByNode.get(nodeId).succeed.notOptional("Promise generation algorithm error: cannot find back the configuration information for a node")
            // if a node is in state "emtpy policies", we only keep system policies + log
            filteredDrafts<- if(context.nodeInfo.state == NodeState.EmptyPolicies) {
                                PolicyLoggerPure.info(s"Node '${context.nodeInfo.hostname}' (${context.nodeInfo.id.value}) is in '${context.nodeInfo.state.name}' state, keeping only system policies for it") *>
                                ZIO.foreach(parsedDrafts)(d =>
                                  if(d.isSystem) {
                                    Some(d).succeed
                                  } else {
                                    PolicyLoggerPure.trace(s"Node '${context.nodeInfo.id.value}': skipping policy '${d.id.value}'") *>
                                    None.succeed
                                  }
                                ).map( _.flatten )
                              } else {
                                parsedDrafts.succeed
                              }

            /*
             * Clearly, here, we are evaluating parameters, and we are not using that just after in the
             * variable expansion, which mean that we are doing the same work again and again and again.
             * Moreover, we also are evaluating again and again parameters whose context ONLY depends
             * on other parameter, and not node config at all. Bad bad bad bad.
             * TODO: two stages parameter evaluation
             *  - global
             *  - by node
             *  + use them in variable expansion (the variable expansion should have a fully evaluated InterpolationContext)
             */
            parameters    <- context.parameters.accumulate { case (name, param) =>
                               for {
                                 p <- param(context).toIO
                               } yield {
                                 (name, p)
                               }
                             }
            boundedDrafts <- filteredDrafts.accumulate { draft =>
                                (for {
                                  //bind variables with interpolated context
                                  expandedVariables <- draft.variables(context).toIO
                                  // And now, for each variable, eval - if needed - the result
                                  expandedVars      <- expandedVariables.accumulate { case (k, v) =>
                                                          //js lib is specific to the node os, bind here to not leak eval between vars
                                                          val jsLib = context.nodeInfo.osDetails.os match {
                                                           case AixOS => JsRudderLibBinding.Aix
                                                           case _     => JsRudderLibBinding.Crypt
                                                         }
                                                         jsEngine.eval(v, jsLib).map( x => (k, x) ).toIO
                                                       }
                                  } yield {
                                    draft.toBoundedPolicyDraft(expandedVars.toMap)
                                  }).chainError(s"When processing directive '${draft.directiveOrder.value}'")
                                }
            // from policy draft, check and build the ordered seq of policy
            policies      <- MergePolicyService.buildPolicy(context.nodeInfo, globalPolicyMode, boundedDrafts).toIO
          } yield {
            // we have the node mode
            val nodeModes = allNodeModes(context.nodeInfo.id)

            NodeConfiguration(
                nodeInfo     = context.nodeInfo
              , modesConfig  = nodeModes
                //system technique should not have hooks, and at least it is not supported.
              , runHooks     = MergePolicyService.mergeRunHooks(policies.filter( ! _.technique.isSystem), nodeModes.nodePolicyMode, nodeModes.globalPolicyMode)
              , policies     = policies
              , nodeContext  = context.nodeContext
              , parameters   = parameters.map { case (k,v) => ParameterForConfiguration(k, v) }.toSet
              , isRootServer = context.nodeInfo.id == context.policyServerInfo.id
            )
          }).chainError(s"Error with parameters expansion for node '${context.nodeInfo.hostname}' (${context.nodeInfo.id.value})").either
      }.runNow

      Full(nodeConfigs.foldLeft(NodeConfigurations(Nil,Nil)) {
        case (cfgs, Right(x)) => cfgs.copy(ok = x :: cfgs.ok)
        case (cfgs, Left(x))  => cfgs.copy(errors = NodeConfigurationError(Failure(x.msg)) :: cfgs.errors)
      })
    }
  }
}

///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

trait PromiseGeneration_updateAndWriteRule extends PromiseGenerationService {

  def nodeConfigurationService : NodeConfigurationHashRepository
  def woRuleRepo: WoRuleRepository
  def promisesFileWriterService: PolicyWriterService
  def fillTemplateService: FillTemplatesService

  override def clearCacheOfTemplate(): Unit = fillTemplateService.clearCache()

  /**
   * That methode remove node configurations for nodes not in allNodes.
   * Corresponding nodes are deleted from the repository of node configurations.
   * Return the updated map of all node configurations (really present).
   */
  def purgeDeletedNodes(allNodes: Set[NodeId], allNodeConfigs: Map[NodeId, NodeConfiguration]) : Box[Map[NodeId, NodeConfiguration]] = {
    val nodesToDelete = allNodeConfigs.keySet -- allNodes
    for {
      deleted <- nodeConfigurationService.deleteNodeConfigurations(nodesToDelete)
    } yield {
      allNodeConfigs -- nodesToDelete
    }
  }

  /**
   * Delete cache that are node in the list.
   * Returns the list of optionnal existing cache hash serialized string.
   */
  def forgetOtherNodeConfigurationState(keep: Set[NodeId]) : Box[Map[NodeId, Option[String]]] = {
    nodeConfigurationService.onlyKeepNodeConfiguration(keep)
  }

  def getNodeConfigurationHash(): Box[Map[NodeId, NodeConfigurationHash]] = nodeConfigurationService.getAll()

  /**
   * Look what are the node configuration updated compared to information in cache
   */
  def selectUpdatedNodeConfiguration(nodeConfigurations: Map[NodeId, NodeConfiguration], cache: Map[NodeId, NodeConfigurationHash]): Set[NodeId] = {
    val notUsedTime = new DateTime(0) //this seems to tell us the nodeConfigurationHash should be refactor to split time frome other properties
    val newConfigCache = nodeConfigurations.map{ case (_, conf) => NodeConfigurationHash(conf, notUsedTime) }

    val (updatedConfig, notUpdatedConfig) = newConfigCache.toSeq.partition{ p =>
      cache.get(p.id) match {
        case None    => true
        case Some(e) => !e.equalWithoutWrittenDate(p)
      }
    }

    if(notUpdatedConfig.size > 0) {
      PolicyLogger.debug(s"Not updating non-modified node configuration: [${notUpdatedConfig.map( _.id.value).mkString(", ")}]")
    }

    if(updatedConfig.size == 0) {
      PolicyLogger.info("No node configuration was updated, no promises to write")
      Set()
    } else {
      val nodeToKeep = updatedConfig.map( _.id ).toSet
      PolicyLogger.info(s"Configuration of following ${updatedConfig.size} nodes were updated, their promises are going to be written: [${updatedConfig.map(_.id.value).mkString(", ")}]")
      nodeConfigurations.keySet.intersect(nodeToKeep)
    }
  }

  /**
   * For each nodeConfiguration, get the corresponding node config version.
   * Either get it from cache or create a new one depending if the node configuration was updated
   * or not.
   */
  def getNodesConfigVersion(allNodeConfigs: Map[NodeId, NodeConfiguration], hashes: Map[NodeId, NodeConfigurationHash], generationTime: DateTime): Map[NodeId, NodeConfigId] = {

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
      //we always set date = 0, so we have the possibility to see with
      //our eyes (and perhaps some SQL) two identicals node config diverging
      //only by the date of generation
      h.writtenDate.toString("YYYYMMdd-HHmmss")+"-"+h.copy(writtenDate = new DateTime(0)).hashCode.toHexString
    }

    /*
     * calcul new nodeConfigId for the updated configuration
     * The filterKey in place of a updatedNode.map(id => allNodeConfigs.get(id)
     * is to be sure to have the NodeConfiguration. There is 0 reason
     * to not have it, but it simplifie case.
     *
     */
    val nodeConfigIds = allNodeConfigs.filterKeys(updatedNodes.contains).values.map { nodeConfig =>
      (nodeConfig.nodeInfo.id, NodeConfigId(hash(NodeConfigurationHash(nodeConfig, generationTime))))
    }.toMap

    ComplianceDebugLogger.debug(s"Updated node configuration ids: ${nodeConfigIds.map {case (id, nodeConfigId) =>
      s"[${id.value}:${ hashes.get(id).fold("???")(x => hash(x)) }->${ nodeConfigId.value }]"
    }.mkString("") }")

    //return update nodeId with their config
    nodeConfigIds
  }


  def getTechniquesResources(
      nodesToWrite    : Set[NodeId]
    , allNodeConfigs  : Map[NodeId, NodeConfiguration]
    , maxParallelism  : Int
  ): IOResult[TechniqueResources] = {
    promisesFileWriterService.getTechniquesResources(nodesToWrite, allNodeConfigs, maxParallelism)
  }

  /**
   * Actually  write the new configuration for the list of given node.
   * If the node target configuration is the same as the actual, nothing is done.
   * Else, promises are generated;
   * Return the list of configuration successfully written.
   */
  def writeNodeConfigurations(
      rootNodeId        : NodeId
    , updated           : Map[NodeId, NodeConfigId]
    , allNodeConfigs    : Map[NodeId, NodeConfiguration]
    , allLicenses       : Map[NodeId, CfeEnterpriseLicense]
    , techniqueResources: TechniqueResources
    , globalPolicyMode  : GlobalPolicyMode
    , generationTime    : DateTime
    , maxParallelism    : Int
  ) : IOResult[Set[NodeConfiguration]] = {

    val traceId = {
      val id = if(updated.size == 1) updated.head._1.value
               else updated.keySet.hashCode().toString
      "[" + id + "]"
    }
    val fsWrite0   =  System.currentTimeMillis

    for {
      written    <- promisesFileWriterService.writeTemplate(rootNodeId, updated.keySet, allNodeConfigs, updated, allLicenses, techniqueResources, globalPolicyMode, generationTime, maxParallelism.toLong)
      ldapWrite0 =  DateTime.now.getMillis
      fsWrite1   =  (ldapWrite0 - fsWrite0)
      _          =  PolicyLogger.trace(s"${traceId} Node configuration written on filesystem in ${fsWrite1} ms")
      //update the hash for the updated node configuration for that generation

      // #10625 : that should be one logic-level up (in the main generation for loop)

      toCache    =  allNodeConfigs.filterKeys(updated.contains(_)).values.toSet
      cached     <- nodeConfigurationService.save(toCache.map(x => NodeConfigurationHash(x, generationTime))).toIO
      ldapWrite1 =  (DateTime.now.getMillis - ldapWrite0)
      _          =  PolicyLogger.trace(s"${traceId} Node configuration cached in LDAP in ${ldapWrite1} ms")
    } yield {
      written.toSet
    }
  }

  def writeOneNodeConfigurations(
      rootNodeId        : NodeId
    , updatedNodeId     : NodeId
    , updatedConfigId   : NodeConfigId
    , allNodeConfigs    : Map[NodeId, NodeConfiguration]
    , existingHash      : Option[String]
    , allLicenses       : Map[NodeId, CfeEnterpriseLicense]
    , techniqueResources: TechniqueResources
    , globalPolicyMode  : GlobalPolicyMode
    , generationTime    : DateTime
  ) : IOResult[Set[NodeConfiguration]] = {

    val traceId = "[" + updatedNodeId.value + "]"

    val fsWrite0   =  System.currentTimeMillis

    for {
      toCache    <- allNodeConfigs.get(updatedNodeId).succeed.notOptional(s"Node configuration was not found in all computed configuration - this is likely a bug")
      written    <- promisesFileWriterService.writeTemplate(
                        rootNodeId
                      , Set(updatedNodeId)
                      , allNodeConfigs
                      , Map(updatedNodeId -> updatedConfigId)
                      , allLicenses
                      , techniqueResources
                      , globalPolicyMode
                      , generationTime
                      , 1
                    )
      ldapWrite0 =  DateTime.now.getMillis
      fsWrite1   =  (ldapWrite0 - fsWrite0)
      _          =  PolicyLogger.trace(s"${traceId} Node configuration written on filesystem in ${fsWrite1} ms")
      //update the hash for the updated node configuration for that generation

      // #10625 : that should be one logic-level up (in the main generation for loop)

      cached     <- nodeConfigurationService.saveOne(NodeConfigurationHash(toCache, generationTime), existingHash)
      ldapWrite1 =  (DateTime.now.getMillis - ldapWrite0)
      _          =  PolicyLogger.trace(s"${traceId} Node configuration cached in LDAP in ${ldapWrite1} ms")
    } yield {
      written.toSet
    }
  }


}

///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

trait PromiseGeneration_setExpectedReports extends PromiseGenerationService {
  def complianceCache  : CachedFindRuleNodeStatusReports
  def confExpectedRepo : UpdateExpectedReportsRepository

  override def computeExpectedReports(
      ruleVal          : Seq[RuleVal]
    , configs          : Seq[NodeConfiguration]
    , updatedNodes     : Map[NodeId, NodeConfigId]
    , generationTime   : DateTime
    , allNodeModes     : Map[NodeId, NodeModeConfig]
  ) : List[NodeExpectedReports] = {

    configs.map { nodeConfig =>
      val nodeId = nodeConfig.nodeInfo.id
      // overrides are in the reverse way, we need to transform them into OverridenPolicy
      val overrides = nodeConfig.policies.flatMap { p =>
        p.overrides.map(overriden => OverridenPolicy(overriden, p.id) )
      }

      NodeExpectedReports(
          nodeId
        , updatedNodes(nodeId)
        , generationTime
        , None
        , allNodeModes(nodeId) //that shall not throw, because we have all nodes here
        , RuleExpectedReportBuilder(nodeConfig.policies)
        , overrides
      )
    }.toList
  }

  override def invalidateComplianceCache(nodeIds: Set[NodeId]): Unit = {
    complianceCache.invalidate(nodeIds)
  }

  override def saveExpectedReports(
      expectedReports: List[NodeExpectedReports]
  ) : Box[Seq[NodeExpectedReports]] = {
    // now, we just need to go node by node, and for each:
    val time_0 = System.currentTimeMillis
    val res = confExpectedRepo.saveNodeExpectedReports(expectedReports)
    TimingDebugLogger.trace(s"updating expected node configuration in base took: ${System.currentTimeMillis-time_0}ms")
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
      p.policyVars.toList.map { v => p.copy(id = v.policyId, policyVars = NonEmptyList.one(v)) }
    }

    // now, group by rule id and map to expected reports
    flatten.groupBy( _.id.ruleId ).map { case (ruleId, seq) =>
      val directives = seq.map { policy =>
        // from a policy, get one "directive expected reports" by directive.
        // As we flattened previously, we only need/want "head"
        val pvar = policy.policyVars.head
        DirectiveExpectedReports(
            pvar.policyId.directiveId
          , pvar.policyMode
          , policy.technique.isSystem
          , componentsFromVariables(policy.technique, policy.id.directiveId, pvar)
        )
      }

      RuleExpectedReports(ruleId, directives)
    }.toList
  }

  def componentsFromVariables(technique: PolicyTechnique, directiveId: DirectiveId, vars: PolicyVars) : List[ComponentExpectedReport] = {

    // Computes the components values, and the unexpanded component values
    val getTrackingVariableCardinality : (Seq[String], Seq[String]) = {
      val boundingVar = vars.trackerVariable.spec.boundingVariable.getOrElse(vars.trackerVariable.spec.name)
      // now the cardinality is the length of the boundingVariable
      (vars.expandedVars.get(boundingVar), vars.originalVars.get(boundingVar)) match {
        case (None, None) =>
          PolicyLogger.debug("Could not find the bounded variable %s for %s in ParsedPolicyDraft %s".format(
              boundingVar, vars.trackerVariable.spec.name, directiveId.value))
          (Seq(DEFAULT_COMPONENT_KEY),Seq()) // this is an autobounding policy
        case (Some(variable), Some(originalVariables)) if (variable.values.size==originalVariables.values.size) =>
          (variable.values, originalVariables.values)
        case (Some(variable), Some(originalVariables)) =>
          PolicyLogger.warn("Expanded and unexpanded values for bounded variable %s for %s in ParsedPolicyDraft %s have not the same size : %s and %s".format(
              boundingVar, vars.trackerVariable.spec.name, directiveId.value,variable.values, originalVariables.values ))
          (variable.values, originalVariables.values)
        case (None, Some(originalVariables)) =>
          (Seq(DEFAULT_COMPONENT_KEY),originalVariables.values) // this is an autobounding policy
        case (Some(variable), None) =>
          PolicyLogger.warn("Somewhere in the expansion of variables, the bounded variable %s for %s in ParsedPolicyDraft %s appeared, but was not originally there".format(
              boundingVar, vars.trackerVariable.spec.name, directiveId.value))
          (variable.values,Seq()) // this is an autobounding policy

      }
    }

    /*
     * We can have several components, one by section.
     * If there is no component for that policy, the policy is autobounded to DEFAULT_COMPONENT_KEY
     */
    val allComponents = technique.rootSection.getAllSections.flatMap { section =>
      if(section.isComponent) {
        section.componentKey match {
          case None =>
            //a section that is a component without componentKey variable: card=1, value="None"
            Some(ComponentExpectedReport(section.name, List(DEFAULT_COMPONENT_KEY), List(DEFAULT_COMPONENT_KEY)))
          case Some(varName) =>
            //a section with a componentKey variable: card=variable card
            val values           = vars.expandedVars.get(varName).map( _.values.toList).getOrElse(Nil)
            val unexpandedValues = vars.originalVars.get(varName).map( _.values.toList).getOrElse(Nil)
            if (values.size != unexpandedValues.size)
              PolicyLogger.warn("Caution, the size of unexpanded and expanded variables for autobounding variable in section %s for directive %s are not the same : %s and %s".format(
                  section.componentKey, directiveId.value, values, unexpandedValues ))
            Some(ComponentExpectedReport(section.name, values, unexpandedValues))
        }
      } else {
        None
      }
    }.toList

    if(allComponents.size < 1) {
      //that log is outputed one time for each directive for each node using a technique, it's far too
      //verbose on debug.
      PolicyLogger.trace("Technique '%s' does not define any components, assigning default component with expected report = 1 for Directive %s".format(
        technique.id, directiveId))

      val trackingVarCard = getTrackingVariableCardinality
      List(ComponentExpectedReport(technique.id.name.value, trackingVarCard._1.toList, trackingVarCard._2.toList))
    } else {
      allComponents
    }

  }

}

trait PromiseGeneration_historization extends PromiseGenerationService {
  def historizationService : HistorizationService

  def historizeData(
        rules            : Seq[Rule]
      , directiveLib     : FullActiveTechniqueCategory
      , groupLib         : FullNodeGroupCategory
      , allNodeInfos     : Map[NodeId, NodeInfo]
      , globalAgentRun   : AgentRunInterval
    ) : Box[Unit] = {
    for {
      _ <- historizationService.updateNodes(allNodeInfos.values.toSet)
      _ <- historizationService.updateGroups(groupLib)
      _ <- historizationService.updateDirectiveNames(directiveLib)
      _ <- historizationService.updatesRuleNames(rules)
      _ <- historizationService.updateGlobalSchedule(globalAgentRun.interval, globalAgentRun.splaytime, globalAgentRun.startHour, globalAgentRun.startMinute)
    } yield {
      () // unit is expected
    }
  }

}
