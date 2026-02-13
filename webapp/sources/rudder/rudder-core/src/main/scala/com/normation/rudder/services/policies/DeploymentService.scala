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
import cats.implicits.*
import com.normation.box.*
import com.normation.cfclerk.domain.ReportingLogic.FocusReport
import com.normation.cfclerk.domain.SectionSpec
import com.normation.cfclerk.domain.TechniqueName
import com.normation.errors.*
import com.normation.inventory.domain.NodeId
import com.normation.rudder.batch.UpdateDynamicGroups
import com.normation.rudder.domain.Constants
import com.normation.rudder.domain.appconfig.FeatureSwitch
import com.normation.rudder.domain.logger.ComplianceDebugLogger
import com.normation.rudder.domain.logger.PolicyGenerationLogger
import com.normation.rudder.domain.logger.PolicyGenerationLoggerPure
import com.normation.rudder.domain.logger.TimingDebugLogger
import com.normation.rudder.domain.nodes.NodeState
import com.normation.rudder.domain.policies.*
import com.normation.rudder.domain.properties.*
import com.normation.rudder.domain.reports.BlockExpectedReport
import com.normation.rudder.domain.reports.ComponentExpectedReport
import com.normation.rudder.domain.reports.DirectiveExpectedReports
import com.normation.rudder.domain.reports.ExpectedValueId
import com.normation.rudder.domain.reports.ExpectedValueMatch
import com.normation.rudder.domain.reports.NodeConfigId
import com.normation.rudder.domain.reports.NodeExpectedReports
import com.normation.rudder.domain.reports.NodeModeConfig
import com.normation.rudder.domain.reports.OverriddenPolicy
import com.normation.rudder.domain.reports.RuleExpectedReports
import com.normation.rudder.domain.reports.ValueExpectedReport
import com.normation.rudder.facts.nodes.CoreNodeFact
import com.normation.rudder.hooks.HookEnvPairs
import com.normation.rudder.hooks.HookReturnCode
import com.normation.rudder.hooks.Hooks
import com.normation.rudder.hooks.HooksLogger
import com.normation.rudder.hooks.RunHooks
import com.normation.rudder.repository.*
import com.normation.rudder.services.policies.fetchinfo.FetchAllInfoService
import com.normation.rudder.services.policies.nodeconfig.FileBasedNodeConfigurationHashRepository
import com.normation.rudder.services.policies.nodeconfig.NodeConfigurationHash
import com.normation.rudder.services.policies.nodeconfig.NodeConfigurationHashRepository
import com.normation.rudder.services.policies.write.PolicyWriterService
import com.normation.rudder.services.policies.write.RuleValGeneratedHookService
import com.normation.rudder.services.reports.CachedNodeConfigurationService
import com.normation.rudder.services.reports.CacheExpectedReportAction
import com.normation.rudder.services.reports.FindNewNodeStatusReports
import com.normation.utils.Control.*
import com.normation.zio.ZioRuntime
import java.nio.charset.StandardCharsets
import net.liftweb.common.*
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.joda.time.Period
import org.joda.time.format.ISODateTimeFormat
import org.joda.time.format.PeriodFormatterBuilder
import scala.concurrent.duration.FiniteDuration
import zio.{System as _, *}
import zio.syntax.*

/**
 * A deployment hook is a class that accept callbacks.
 */
trait PromiseGenerationHooks {

  /*
   * Hooks to call before deployment start.
   * This one is synchronised, so that deployment will
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
  def apply(nodeId: NodeId, isPolicyServer: Boolean, policyServerId: NodeId): Int = {
    if (nodeId == Constants.ROOT_POLICY_SERVER_ID) 0
    else if (isPolicyServer) {
      if (policyServerId == Constants.ROOT_POLICY_SERVER_ID) 1 else 2
    } else 3
  }
}

/**
 * The main service which deploy modified rules and
 * their dependencies.
 */
trait PolicyGenerationService {

  /**
   * All mighty method that take all modified rules, find their
   * dependencies, process ${vars}, build the list of node to update,
   * update nodes.
   *
   * Return the list of node IDs actually updated.
   *
   */
  def deploy(): Box[Set[NodeId]]

}

/*
 * A data structure that represent all information needed to perform a policy generation.
 * Once we have that, the world can restart ticking, we are good and can do the whole
 * policy generation without any other data fetch.
 */
case class FetchAllInfo(
    activeNodeIds:             Set[NodeId],
    ruleVals:                  Seq[RuleVal],
    nodeContexts:              Map[NodeId, InterpolationContext],
    allNodeModes:              Map[NodeId, NodeModeConfig],
    scriptEngineEnabled:       FeatureSwitch,
    globalPolicyMode:          GlobalPolicyMode,
    nodeConfigCaches:          Map[NodeId, NodeConfigurationHash],
    timeFetchAll:              Long,
    timeRuleVal:               Long,
    errors:                    Map[NodeId, String],
    maxParallelism:            Int,
    jsTimeout:                 FiniteDuration,
    generationContinueOnError: Boolean
)

///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//  Implémentation
///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

class PolicyGenerationServiceImpl(
    override val woRuleRepo:                     WoRuleRepository,
    override val nodeConfigurationService:       NodeConfigurationHashRepository,
    override val confExpectedRepo:               UpdateExpectedReportsRepository,
    override val complianceCache:                FindNewNodeStatusReports,
    override val promisesFileWriterService:      PolicyWriterService,
    override val cachedNodeConfigurationService: CachedNodeConfigurationService,
    val buildNodeConfigurationService:           BuildNodeConfigurationService,
    val ruleValGeneratedHookService:             RuleValGeneratedHookService,
    val policyGenerationUpdateDynGroup:          PolicyGenerationUpdateDynGroup,
    val fetchAllInfoService:                     FetchAllInfoService,
    val promiseGenerationHookService:            PolicyGenerationHookService,
    UPDATED_NODE_IDS_PATH:                       String,
    GENERATION_FAILURE_MSG_PATH:                 String,
    override val isPostgresqlLocal:              Boolean
) extends PolicyGenerationService with PolicyGeneration_localPostgresql with PolicyGeneration_updateAndWriteRule
    with PolicyGeneration_setExpectedReports {

  /**
   * All mighty method that take all modified rules, find their
   * dependencies, process ${vars}, build the list of node to update,
   * update nodes.
   *
   * Return the list of node IDs actually updated.
   *
   */
  override def deploy(): Box[Set[NodeId]] = {
    PolicyGenerationLogger.info("Start policy generation, checking updated rules")

    val initialTime = System.currentTimeMillis

    val generationTime = new DateTime(initialTime, DateTimeZone.UTC)
    val rootNodeId     = Constants.ROOT_POLICY_SERVER_ID
    // we need to add the current environment variables to the script context
    // plus the script environment variables used as script parameters
    import scala.jdk.CollectionConverters.*
    val systemEnv      = HookEnvPairs.build(System.getenv.asScala.toSeq*)

    val result = for {

      _                 <- promiseGenerationHookService.runPreHooks(generationTime, systemEnv)
      timeRunPreGenHooks = (System.currentTimeMillis - initialTime)
      _                  = PolicyGenerationLogger.timing.debug(s"Pre-policy-generation scripts hooks ran in ${timeRunPreGenHooks} ms")

      _ <- policyGenerationUpdateDynGroup.checkUpdate().toBox

      startedGenHooksTime    = System.currentTimeMillis
      _                     <- promiseGenerationHookService.runStartedHooks(generationTime, systemEnv)
      timeRunStartedGenHooks = (System.currentTimeMillis - startedGenHooksTime)
      _                      = PolicyGenerationLogger.timing.debug(s"Pre-policy-generation scripts hooks ran in ${timeRunStartedGenHooks} ms")

      codePreGenHooksTime = System.currentTimeMillis
      _                  <- promiseGenerationHookService.beforeDeploymentSync(generationTime)
      timeCodePreGenHooks = (System.currentTimeMillis - codePreGenHooksTime)
      _                   = PolicyGenerationLogger.timing.debug(
                              s"Pre-policy-generation modules hooks in ${timeCodePreGenHooks} ms, start getting all generation related data."
                            )

      // Objects need to be created in separate for {} yield to be garbage collectable,
      // otherwise they remain in the scope and are not freed.
      // We need to limit scope like that as necessary to free memory during policy generation

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
                             timeBuildConfig,
                             maxParallelism
                           ) <- for {
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
                                    generationContinueOnError
                                  )                    <- fetchAllInfoService.fetchAll().toBox
                                  buildConfigTime       = System.currentTimeMillis
                                  /// here, we still have directive by directive info
                                  filteredTechniques    = getFilteredTechnique()
                                  configsAndErrors     <- buildNodeConfigurationService
                                                            .buildNodeConfigurations(
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
                                                            .toBox ?~! "Cannot build target configuration node"
                                  /// only keep successfully node config. We will keep the failed one to fail the whole process in the end if needed
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
                                    timeBuildConfig,
                                    maxParallelism
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
      _                  <- promiseGenerationHookService.runPostHooks(
                              generationTime,
                              new DateTime(postHooksTime, DateTimeZone.UTC),
                              updatedNodeInfo,
                              systemEnv,
                              UPDATED_NODE_IDS_PATH
                            )
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
        promiseGenerationHookService.runFailureHooks(
          generationTime,
          new DateTime(failureHooksTime, DateTimeZone.UTC),
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
  private val periodFormatter = {
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

}

///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
// Follows: traits implementing each part of the deployment service
///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

/*
 * Facade that manage dynamic group update before policy generation if needed.
 */
trait PolicyGenerationUpdateDynGroup {
  def checkUpdate(): IOResult[Unit]
}

/*
 * Default implementation
 */
class PolicyGenerationUpdateDynGroupImpl(getComputeDynGroups: () => IOResult[Boolean]) extends PolicyGenerationUpdateDynGroup {
  private val dynamicsGroupsUpdate: Ref[Option[UpdateDynamicGroups]] = ZioRuntime.unsafeRun(Ref.make(None))

  // We need to register the update dynamic group after instantiation of the class
  // as the deployment service, the async deployment and the dynamic group update have cyclic dependencies
  def setDynamicsGroupsService(updateDynamicGroups: UpdateDynamicGroups): Unit = {
    ZioRuntime.unsafeRun(dynamicsGroupsUpdate.set(Some(updateDynamicGroups)))
  }

  private def groupUpdate(updateDynamicGroups: UpdateDynamicGroups): IOResult[Unit] = {

    def awaitEndGroup: IOResult[Unit] = for {
      isIdle <- IOResult.attempt(updateDynamicGroups.isIdle())
      _      <- ZIO.when(isIdle)(ZIO.sleep(50.millis) *> awaitEndGroup)
    } yield ()

    // Trigger a manual update if one is not pending (otherwise it goes in infinite loop)
    // It doesn't expose anything about its ending, so we need to wait for the update to be idle
    ZIO.whenZIO(IOResult.attempt(updateDynamicGroups.isIdle())) {
      IOResult.attempt(updateDynamicGroups.startManualUpdate) *>
      // wait for it to finish. We unfortunately cannot do much more than waiting
      // we do need a timeout though
      // Leave some time for actor to kick in
      ZIO.sleep(50.millis)
    } *> awaitEndGroup
  }

  /*
   * The computation of dynamic group is a workaround inconsistencies after importing definition
   * from LDAP, see: https://issues.rudder.io/issues/14758
   * But that computation may lead to a huge delay in generation (tens of minutes). We want to be
   * able to unset their computation in some case through an environment variable.
   *
   * To disable it, set environment variable "rudder.generation.computeDynGroup" to "disabled"
   */
  override def checkUpdate(): IOResult[Unit] = {
    for {
      // trigger a dynamic group update
      doCompute              <- getComputeDynGroups().catchAll(_ => true.succeed)
      (timeComputeGroups, _) <- (if (doCompute) {
                                   dynamicsGroupsUpdate.get.flatMap {
                                     case Some(service) => groupUpdate(service)
                                     case None          => Inconsistency("Dynamic group update is not registered, this is an error").fail
                                   }
                                 } else {
                                   PolicyGenerationLoggerPure.warn(
                                     s"Computing dynamic groups disable by REST API settings 'rudder_generation_compute_dyngroups'"
                                   )
                                 }).timed
      _                      <- PolicyGenerationLoggerPure.timing.debug(s"Computing dynamic groups finished in ${timeComputeGroups.toMillis} ms")
    } yield ()
  }
}

trait PolicyGeneration_localPostgresql {

  def isPostgresqlLocal: Boolean

  def getFilteredTechnique(): Map[NodeId, List[TechniqueName]] = {
    if (isPostgresqlLocal) {
      Map()
    } else {
      Map((Constants.ROOT_POLICY_SERVER_ID -> List(TechniqueName("rudder-service-postgresql"))))
    }
  }
}

final case class NodeConfigurations(
    ok:     List[NodeConfiguration],
    errors: List[RudderError]
)

trait BuildNodeConfigurationService {
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
  ): IOResult[NodeConfigurations]
}

object BuildNodeConfiguration extends BuildNodeConfigurationService {

  /*
   * Utility class that helps deduplicate same failures in a chain
   * of failure when using bestEffort.
   */
  implicit class DedupFailure[T](box: Box[T]) {
    def dedupFailures(failure: String, transform: String => String = identity): Box[T] = {
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
   * nodeFacts *must* contains the nodes info of every nodes
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
  ): IOResult[NodeConfigurations] = {

    // step 1: from RuleVals to expanded rules vals

    val t0 = System.nanoTime()
    // group by nodes
    // no consistency / unicity check is done here, it will be done
    // in another phase. We are just switching to a node-first view.
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
                            // if a node is in state "empty policies", we only keep system policies + log
                            filteredDrafts = if (context.nodeInfo.rudderSettings.state == NodeState.EmptyPolicies) {
                                               PolicyGenerationLogger.debug(
                                                 s"Node '${context.nodeInfo.fqdn}' (${context.nodeInfo.id.value}) is in '${context.nodeInfo.rudderSettings.state.name}' state, keeping only system policies for it"
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
                                                                                   val jsLib = JsRudderLibBinding.Crypt

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
                                // used to be !isSystem
                                policies.filter(_.technique.policyTypes.isBase),
                                nodeModes.nodePolicyMode,
                                nodeModes.globalPolicyMode
                              ),
                              policies = policies,
                              nodeContext = context.nodeContext,
                              parameters = context.parameters.map {
                                case (k, v) => ParameterForConfiguration(k, GenericProperty.serializeToHocon(v))
                              }.toSet
                            )
                            nodeConfig
                          }).chainError(
                            s"Error with parameters expansion for node '${context.nodeInfo.fqdn}' (${context.nodeInfo.id.value})"
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
    evalJsProg
  }

  // we need to remove all nodes whose parent are failed, recursively
  // we don't want to have zillions of "node x failed b/c parent failed", so we just say "children node failed b/c parent failed"
  @scala.annotation.tailrec
  def recFailNodes(failed: Set[NodeId], maybeSuccess: List[NodeConfiguration], failures: Set[String]): NodeConfigurations = {
    // filter all nodes whose parent is in failed
    val newFailed = maybeSuccess.collect {
      case cfg if (failed.contains(cfg.nodeInfo.rudderSettings.policyServerId)) =>
        (
          cfg.nodeInfo.id,
          s"Can not configure '${cfg.nodeInfo.rudderSettings.policyServerId.value}' children node because '${cfg.nodeInfo.rudderSettings.policyServerId.value}' is a policy server whose configuration is in error"
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

trait PolicyGeneration_updateAndWriteRule extends PolicyGenerationService {

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
    val notUsedTime    = {
      new DateTime(
        0,
        DateTimeZone.UTC
      ) // this seems to tell us the nodeConfigurationHash should be refactor to split time frome other properties
    }
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
      h.writtenDate
        .toString("YYYYMMdd-HHmmss") + "-" + h.copy(writtenDate = new DateTime(0, DateTimeZone.UTC)).hashCode.toHexString
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
      nodeInfos:        Map[NodeId, CoreNodeFact],
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
                     nodeInfos,
                     updated,
                     globalPolicyMode,
                     generationTime,
                     maxParallelism
                   )
      ldapWrite0 = DateTime.now(DateTimeZone.UTC).getMillis
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

trait PolicyGeneration_setExpectedReports extends PolicyGenerationService {
  def complianceCache:                FindNewNodeStatusReports
  def confExpectedRepo:               UpdateExpectedReportsRepository
  def cachedNodeConfigurationService: CachedNodeConfigurationService

  def computeExpectedReports(
      allNodeConfigurations: Map[NodeId, NodeConfiguration],
      updatedNodes:          Map[NodeId, NodeConfigId],
      generationTime:        DateTime,
      allNodeModes:          Map[NodeId, NodeModeConfig]
  ): List[NodeExpectedReports] = {

    val updatedNodeIds = updatedNodes.keySet
    allNodeConfigurations.collect {
      case (nodeId, nodeConfig) if (updatedNodeIds.contains(nodeId)) =>
        // overrides are in the reverse way, we need to transform them into OverriddenPolicy
        val overrides = nodeConfig.policies.flatMap(p => p.overrides.map(overridden => OverriddenPolicy(overridden, p.id)))

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

  def invalidateComplianceCache(actions: Seq[(NodeId, CacheExpectedReportAction)]): IOResult[Unit] = {
    cachedNodeConfigurationService.invalidateWithAction(actions)
  }

  def saveExpectedReports(
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
              policy.technique.policyTypes,
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
          PolicyGenerationLogger.debug(
            s"Somewhere in the expansion of variables, the bounded variable ${boundingVar} for ${vars.trackerVariable.spec.name} in Directive ${directiveId.serialize} appeared, but was not originally there"
          )
          (variables.values.flatMap(_.values).toSeq, variables.values.flatMap(_.values).toSeq) // this is an autobounding policy
        case (variables, originalVariables)                                                                               =>
          PolicyGenerationLogger.debug(
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
                        innerExpandedVars.zip(innerUnexpandedVars).map(ExpectedValueMatch.apply.tupled)
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
          trackingVarCard._1.toList.zip(trackingVarCard._2.toList).map(ExpectedValueMatch.apply.tupled)
        )
      )
    } else {
      allComponents
    }

  }

}

// utility case class where we store the sorted list of node ids to be passed to hooks
final case class SortedNodeIds(servers: Seq[String], nodes: Seq[String])

trait PolicyGenerationHookService {
  def beforeDeploymentSync(generationTime: DateTime): Box[Unit]
  def runPreHooks(generationTime:          DateTime, systemEnv: HookEnvPairs): Box[Unit]
  def runStartedHooks(generationTime:      DateTime, systemEnv: HookEnvPairs): Box[Unit]
  /*
   * Post generation hooks
   */
  def runPostHooks(
      generationTime:   DateTime,
      endTime:          DateTime,
      updatedNodeInfos: Map[NodeId, CoreNodeFact],
      systemEnv:        HookEnvPairs,
      nodeIdsPath:      String
  ): Box[Unit]

  def runFailureHooks(
      generationTime:   DateTime,
      endTime:          DateTime,
      systemEnv:        HookEnvPairs,
      errorMessage:     String,
      errorMessagePath: String
  ): Box[Unit]

  def appendPreGenCodeHook(hook: PromiseGenerationHooks): Unit
}

class PolicyGenerationHookServiceImpl(
    // base folder for hooks. It's a string because there is no need to get it from config
    // file, it's just a constant.
    HOOKS_D:                           String,
    HOOKS_IGNORE_SUFFIXES:             List[String],
    postGenerationHookCompabilityMode: Option[Boolean]
) extends PolicyGenerationHookService {

  /*
   * Plugin hooks
   */
  private val codeHooks = collection.mutable.Buffer[PromiseGenerationHooks]()

  override def appendPreGenCodeHook(hook: PromiseGenerationHooks): Unit = {
    this.codeHooks.append(hook)
  }

  /*
   * plugins hooks
   */
  override def beforeDeploymentSync(generationTime: DateTime): Box[Unit] = {
    traverse(codeHooks.toSeq)(_.beforeDeploymentSync(generationTime)).map(_ => ())
  }

  /*
   * Pre generation hooks
   */
  def runPreHooks(generationTime: DateTime, systemEnv: HookEnvPairs):     Box[Unit] = {
    val name = "policy-generation-pre-start"
    (for {
      preHooks <- RunHooks.getHooksPure(HOOKS_D + "/" + name, HOOKS_IGNORE_SUFFIXES)
      res      <-
        RunHooks.syncRun(
          name,
          preHooks,
          HookEnvPairs.build(("RUDDER_GENERATION_DATETIME", generationTime.toString)),
          systemEnv
        ) match {
          case _: HookReturnCode.Success => ().succeed
          case x: HookReturnCode.Error   =>
            Inconsistency(
              s"Policy generation pre hook failed, Interrupting policy generation now.\nError is: ${x.msg}\n stdout: ${x.stdout}\n stderr: '${x.stderr}'"
            ).fail
        }
    } yield {
      res
    }).toBox
  }
  /*
   * Pre generation hooks
   */
  def runStartedHooks(generationTime: DateTime, systemEnv: HookEnvPairs): Box[Unit] = {
    val name = "policy-generation-started"
    (for {
      // fetch all
      preHooks <- RunHooks.getHooksPure(HOOKS_D + "/" + name, HOOKS_IGNORE_SUFFIXES)
      _        <-
        RunHooks.asyncRun(name, preHooks, HookEnvPairs.build(("RUDDER_GENERATION_DATETIME", generationTime.toString)), systemEnv)
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

  def getSortedNodeIds(updatedNodeConfigsInfo: Map[NodeId, CoreNodeFact]): SortedNodeIds = {
    val (policyServers, simpleNodes) = {
      val (a, b) = updatedNodeConfigsInfo.values.toSeq.partition(_.rudderSettings.isPolicyServer)
      (
        // policy servers are sorted by their proximity with root, root first
        a.sortBy(x => NodePriority(x.id, x.rudderSettings.isPolicyServer, x.rudderSettings.policyServerId))
          .map(_.id.value), // simple nodes are sorted alpha-num

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
    def date(d:        DateTime)    = d.toString(ISODateTimeFormat.basicDateTime())
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
               file.moveTo(savedOld)(using File.CopyOptions(overwrite = true))
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
  def runPostHooks(
      generationTime:   DateTime,
      endTime:          DateTime,
      updatedNodeInfos: Map[NodeId, CoreNodeFact],
      systemEnv:        HookEnvPairs,
      nodeIdsPath:      String
  ): Box[Unit] = {
    val sortedNodeIds = getSortedNodeIds(updatedNodeInfos)
    val name          = "policy-generation-finished"
    for {
      written         <- writeNodeIdsForHook(nodeIdsPath, sortedNodeIds, generationTime, endTime)
      postHooks       <- RunHooks.getHooksPure(HOOKS_D + "/" + name, HOOKS_IGNORE_SUFFIXES)
      // we want to sort node with root first, then relay, then other nodes for hooks
      updatedNodeIds   = updatedNodeInfos.toList.map {
                           case (k, v) =>
                             (
                               k,
                               NodePriority(v.id, v.rudderSettings.isPolicyServer, v.rudderSettings.policyServerId)
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
                           name,
                           postHooks,
                           HookEnvPairs.build(envParams*),
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
               file.moveTo(savedOld)(using File.CopyOptions(overwrite = true))
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
  def runFailureHooks(
      generationTime:   DateTime,
      endTime:          DateTime,
      systemEnv:        HookEnvPairs,
      errorMessage:     String,
      errorMessagePath: String
  ): Box[Unit] = {
    val name = "policy-generation-failed"
    for {
      written      <- writeErrorMessageForHook(errorMessagePath, errorMessage, generationTime, endTime)
      failureHooks <- RunHooks.getHooksPure(HOOKS_D + "/" + name, HOOKS_IGNORE_SUFFIXES)
      // we want to sort node with root first, then relay, then other nodes for hooks
      envParams     = (("RUDDER_GENERATION_DATETIME", generationTime.toString())
                        :: ("RUDDER_END_GENERATION_DATETIME", endTime.toString) // what is the most alike a end time
                        :: ("RUDDER_ERROR_MESSAGE_PATH", errorMessagePath)
                        :: Nil)
      _            <- RunHooks.asyncRun(
                        name,
                        failureHooks,
                        HookEnvPairs.build(envParams*),
                        systemEnv
                      )
    } yield ()
  }.toBox

}

trait WriteCertificatesPemService {
  def writeCertificatesPem(nodeFacts: Map[NodeId, CoreNodeFact]): Unit
}

class WriteCertificatesPemServiceImpl(
    writeNodeCertificatesPem:   WriteNodeCertificatesPem,
    allNodeCertificatesPemFile: File
) extends WriteCertificatesPemService {
  override def writeCertificatesPem(nodeFacts: Map[NodeId, CoreNodeFact]): Unit = {
    writeNodeCertificatesPem.writeCerticatesAsync(allNodeCertificatesPemFile, nodeFacts.toMap)
  }
}
