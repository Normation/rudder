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

import scala.Option.option2Iterable
import org.joda.time.DateTime
import com.normation.cfclerk.domain.Technique
import com.normation.cfclerk.domain.TrackerVariable
import com.normation.cfclerk.domain.Variable
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.Constants
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.domain.parameters.GlobalParameter
import com.normation.rudder.domain.parameters.ParameterName
import com.normation.rudder.domain.policies._
import com.normation.rudder.repository._
import com.normation.rudder.services.eventlog.HistorizationService
import com.normation.rudder.services.nodes.NodeInfoService
import com.normation.rudder.services.policies.nodeconfig.NodeConfiguration
import com.normation.rudder.services.policies.nodeconfig.NodeConfigurationHash
import com.normation.rudder.services.policies.nodeconfig.NodeConfigurationService
import com.normation.rudder.services.policies.nodeconfig.ParameterForConfiguration
import com.normation.rudder.services.reports.ReportingService
import com.normation.utils.Control._
import com.normation.utils.HashcodeCaching
import net.liftweb.common._
import com.normation.rudder.domain.parameters.GlobalParameter
import scala.collection.immutable.TreeMap
import com.normation.inventory.services.core.ReadOnlyFullInventoryRepository
import com.normation.inventory.domain.NodeInventory
import com.normation.inventory.domain.AcceptedInventory
import com.normation.inventory.domain.NodeInventory
import com.normation.rudder.domain.parameters.GlobalParameter
import com.normation.rudder.services.policies.nodeconfig.NodeConfiguration
import com.normation.rudder.domain.reports.NodeAndConfigId
import com.normation.rudder.domain.reports.NodeExpectedReports
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.reports.NodeConfigId
import com.normation.rudder.reports.ComplianceMode
import com.normation.rudder.reports.ComplianceModeService
import com.normation.rudder.reports.AgentRunIntervalService
import com.normation.rudder.reports.AgentRunInterval
import com.normation.rudder.domain.logger.ComplianceDebugLogger
import com.normation.rudder.services.reports.CachedFindRuleNodeStatusReports
import com.normation.rudder.services.policies.write.Cf3PromisesFileWriterService
import com.normation.rudder.services.policies.write.Cf3PolicyDraft
import com.normation.rudder.services.policies.write.Cf3PolicyDraftId
import com.normation.rudder.reports.GlobalComplianceMode
import com.normation.rudder.domain.licenses.NovaLicense
import javax.script.ScriptEngine
import javax.script.ScriptEngineManager
import com.normation.rudder.domain.appconfig.FeatureSwitch
import com.normation.inventory.domain.AixOS
import com.normation.rudder.domain.reports.NodeModeConfig
import com.normation.rudder.reports.HeartbeatConfiguration
import org.joda.time.format.DateTimeFormatter
import com.normation.rudder.hooks.RunHooks
import com.normation.rudder.hooks.HookEnvPairs
import com.normation.rudder.hooks.HookEnvPair
import ch.qos.logback.core.db.DataSourceConnectionSource
import scala.concurrent.Future
import com.normation.rudder.hooks.HooksImplicits

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
trait PromiseGenerationService extends Loggable {

  /**
   * All mighy method that take all modified rules, find their
   * dependencies, proccess ${vars}, build the list of node to update,
   * update nodes.
   *
   * Return the list of node IDs actually updated.
   *
   */
  def deploy() : Box[Set[NodeId]] = {
    logger.info("Start policy generation, checking updated rules")

    val initialTime = System.currentTimeMillis

    val generationTime = new DateTime(initialTime)
    val rootNodeId = Constants.ROOT_POLICY_SERVER_ID
    //we need to add the current environment variables to the script context
    //plus the script environment variables used as script parameters
    import scala.collection.JavaConverters._
    val systemEnv = HookEnvPairs.build(System.getenv.asScala.toSeq:_*)

    import HooksImplicits._

    val result = for {
      //fetch all - yep, memory is cheap... (TODO: size of that for 1000 nodes, 100 rules, 100 directives, 100 groups ?)
      preHooks            <- RunHooks.getHooks(HOOKS_D + "/policy-generation-started", HOOKS_IGNORE_SUFFIXES)
      _                   <- RunHooks.syncRun(preHooks, HookEnvPairs.build( ("RUDDER_GENERATION_DATETIME", generationTime.toString) ), systemEnv)
      timeRunPreGenHooks  =  (System.currentTimeMillis - initialTime)
      _                   =  logger.debug(s"Pre-policy-generation scripts hooks ran in ${timeRunPreGenHooks} ms")

      codePreGenHooksTime =  System.currentTimeMillis
      _                   <- beforeDeploymentSync(generationTime)
      timeCodePreGenHooks =  (System.currentTimeMillis - codePreGenHooksTime)
      _                   =  logger.debug(s"Pre-policy-generation modules hooks in ${timeCodePreGenHooks} ms, start getting all generation related data.")

      fetch0Time          =  System.currentTimeMillis
      allRules            <- findDependantRules() ?~! "Could not find dependant rules"
      fetch1Time          =  System.currentTimeMillis
      _                   =  logger.trace(s"Fetched rules in ${fetch1Time-fetch0Time} ms")
      allNodeInfos        <- getAllNodeInfos ?~! "Could not get Node Infos"
      fetch2Time          =  System.currentTimeMillis
      _                   =  logger.trace(s"Fetched node infos in ${fetch2Time-fetch1Time} ms")
      directiveLib        <- getDirectiveLibrary() ?~! "Could not get the directive library"
      fetch3Time          =  System.currentTimeMillis
      _                   =  logger.trace(s"Fetched directives in ${fetch3Time-fetch2Time} ms")
      groupLib            <- getGroupLibrary() ?~! "Could not get the group library"
      fetch4Time          =  System.currentTimeMillis
      _                   =  logger.trace(s"Fetched groups in ${fetch4Time-fetch3Time} ms")
      allParameters       <- getAllGlobalParameters ?~! "Could not get global parameters"
      fetch5Time          =  System.currentTimeMillis
      _                   =  logger.trace(s"Fetched global parameters in ${fetch5Time-fetch4Time} ms")
      globalAgentRun      <- getGlobalAgentRun
      fetch6Time          =  System.currentTimeMillis
      _                   =  logger.trace(s"Fetched run infos in ${fetch6Time-fetch5Time} ms")
      scriptEngineEnabled <- getScriptEngineEnabled() ?~! "Could not get if we should use the script engine to evaluate directive parameters"
      globalComplianceMode <- getGlobalComplianceMode
      globalPolicyMode     <- getGlobalPolicyMode() ?~! "Cannot get the Global Policy Mode (Enforce or Verify)"
      nodeConfigCaches     <- getNodeConfigurationHash() ?~! "Cannot get the Configuration Cache"
      allLicenses          <- getAllLicenses() ?~! "Cannont get licenses information"

      //from here, we can restrain the calcul on two axis:
      // - number of nodes: only node somehow targetted by a rule have to be considered.
      // - number of rules: any rule without target or with only target with no node can be skipped
      activeRuleIds = getAppliedRuleIds(allRules, groupLib, directiveLib, allNodeInfos)
      activeNodeIds = groupLib.getNodeIds(allRules.flatMap(_.targets).toSet, allNodeInfos)

      allNodeModes     =  buildNodeModes(allNodeInfos, globalComplianceMode, globalAgentRun, globalPolicyMode)

      timeFetchAll    =  (System.currentTimeMillis - fetch0Time)
      _               =  logger.debug(s"All relevant information fetched in ${timeFetchAll} ms, start names historization.")

      nodeContextsTime =  System.currentTimeMillis
      nodeContexts     <- getNodeContexts(activeNodeIds, allNodeInfos, groupLib, allLicenses, allParameters, globalAgentRun, globalComplianceMode) ?~! "Could not get node interpolation context"
      timeNodeContexts =  (System.currentTimeMillis - nodeContextsTime)
      _                =  logger.debug(s"Node contexts built in ${timeNodeContexts} ms, start to build new node configurations.")

      /// end of inputs, all information gathered for promise generation.

      ///// this thing has nothing to do with promise generation and should be
      ///// else where. You can ignore it if you want to understand generation process.
      historizeTime =  System.currentTimeMillis
      historize     <- historizeData(allRules, directiveLib, groupLib, allNodeInfos, globalAgentRun)
      timeHistorize =  (System.currentTimeMillis - historizeTime)
      _             =  logger.debug(s"Historization of names done in ${timeHistorize} ms, start to build rule values.")
      ///// end ignoring

      ruleValTime   =  System.currentTimeMillis
                       //only keep actually applied rules in a format where parameter analysis on directive is done.
      ruleVals      <- buildRuleVals(activeRuleIds, allRules, directiveLib, groupLib, allNodeInfos) ?~! "Cannot build Rule vals"
      timeRuleVal   =  (System.currentTimeMillis - ruleValTime)
      _             =  logger.debug(s"RuleVals built in ${timeRuleVal} ms, start to expand their values.")

      buildConfigTime =  System.currentTimeMillis
      config          <- buildNodeConfigurations(activeNodeIds, ruleVals, nodeContexts, groupLib, directiveLib, allNodeInfos, allNodeModes, scriptEngineEnabled) ?~! "Cannot build target configuration node"
      timeBuildConfig =  (System.currentTimeMillis - buildConfigTime)
      _               =  logger.debug(s"Node's target configuration built in ${timeBuildConfig} ms, start to update rule values.")

      sanitizeTime        =  System.currentTimeMillis
      _                   <- forgetOtherNodeConfigurationState(config.map(_.nodeInfo.id).toSet) ?~! "Cannot clean the configuration cache"
      sanitizedNodeConfig <- sanitize(config) ?~! "Cannot set target configuration node"
      timeSanitize        =  (System.currentTimeMillis - sanitizeTime)
      _                   =  logger.debug(s"RuleVals updated in ${timeSanitize} ms, start to detect changes in node configuration.")

      beginTime                =  System.currentTimeMillis
      //that's the first time we actually output something : new serial for updated rules
      (updatedCrs, deletedCrs) <- detectUpdatesAndIncrementRuleSerial(sanitizedNodeConfig.values.toSeq, nodeConfigCaches, allRules.map(x => (x.id, x)).toMap)?~!
                                  "Cannot detect the updates in the NodeConfiguration"
      uptodateSerialNodeconfig =  updateSerialNumber(sanitizedNodeConfig, updatedCrs.toMap)
      // Update the serial of ruleVals when there were modifications on Rules values
      // replace variables with what is really applied
      timeIncrementRuleSerial  =  (System.currentTimeMillis - beginTime)
      _                        =  logger.debug(s"Checked node configuration updates leading to rules serial number updates and serial number updated in ${timeIncrementRuleSerial} ms")

      writeTime             =  System.currentTimeMillis
      updatedNodeConfigs    =  getNodesConfigVersion(uptodateSerialNodeconfig, nodeConfigCaches, generationTime)
      //second time we write something in repos: updated node configuration
      writtenNodeConfigs    <- writeNodeConfigurations(rootNodeId, updatedNodeConfigs, uptodateSerialNodeconfig, allLicenses, globalPolicyMode, generationTime) ?~!
                               "Cannot write configuration node"
      timeWriteNodeConfig   =  (System.currentTimeMillis - writeTime)
      _                     =  logger.debug(s"Node configuration written in ${timeWriteNodeConfig} ms, start to update expected reports.")

      reportTime            =  System.currentTimeMillis
      // need to update this part as well
      expectedReports       <- setExpectedReports(ruleVals, writtenNodeConfigs.toSeq, updatedNodeConfigs, updatedCrs.toMap, deletedCrs, generationTime, allNodeModes)  ?~!
                               "Cannot build expected reports"
      // now, invalidate cache
      timeSetExpectedReport =  (System.currentTimeMillis - reportTime)
      _                     =  logger.debug(s"Reports updated in ${timeSetExpectedReport} ms")
      // finally, run post-generation hooks. They can lead to an error message for build, but node policies are updated
      postHooksTime         =  System.currentTimeMillis
      postHooks             <- RunHooks.getHooks(HOOKS_D + "/policy-generation-finished", HOOKS_IGNORE_SUFFIXES)
      updatedNodeIds        =  updatedNodeConfigs.keySet.map( _.value )
      _                     <- RunHooks.syncRun(
                                   postHooks
                                 , HookEnvPairs.build(
                                                         ("RUDDER_GENERATION_DATETIME", generationTime.toString())
                                                       , ("RUDDER_END_GENERATION_DATETIME", new DateTime(postHooksTime).toString) //what is the most alike a end time
                                                       , ("RUDDER_NODEIDS", updatedNodeIds.mkString(" "))
                                                       , ("RUDDER_NUMBER_NODES_UPDATED", updatedNodeIds.size.toString)
                                                       , ("RUDDER_ROOT_POLICY_SERVER_UPDATED", if(updatedNodeIds.contains("root")) "0" else "1" )
                                                     )
                                 , systemEnv
                               )
      timeRunPostGenHooks   =  (System.currentTimeMillis - postHooksTime)
      _                     =  logger.debug(s"Post-policy-generation hooks ran in ${timeRunPostGenHooks} ms")

    } yield {
      //invalidate compliance may be very very long - make it async
      import scala.concurrent.ExecutionContext.Implicits.global
      Future { invalidateComplianceCache(updatedNodeConfigs.keySet) }

      logger.debug("Timing summary:")
      logger.debug("Run pre-gen scripts hooks : %10s ms".format(timeRunPreGenHooks))
      logger.debug("Run pre-gen modules hooks : %10s ms".format(timeCodePreGenHooks))
      logger.debug("Fetch all information     : %10s ms".format(timeFetchAll))
      logger.debug("Historize names           : %10s ms".format(timeHistorize))
      logger.debug("Build current rule values : %10s ms".format(timeRuleVal))
      logger.debug("Build target configuration: %10s ms".format(timeBuildConfig))
      logger.debug("Update rule vals          : %10s ms".format(timeSanitize))
      logger.debug("Increment rule serials    : %10s ms".format(timeIncrementRuleSerial))
      logger.debug("Write node configurations : %10s ms".format(timeWriteNodeConfig))
      logger.debug("Save expected reports     : %10s ms".format(timeSetExpectedReport))
      logger.debug("Run post generation hooks : %10s ms".format(timeRunPostGenHooks))
      logger.debug("Number of nodes updated   : %10s   ".format(updatedNodeIds.size))


      writtenNodeConfigs.map( _.nodeInfo.id )
    }

    logger.debug("Policy generation completed in %d ms".format((System.currentTimeMillis - initialTime)))
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
  def getAllLicenses(): Box[Map[NodeId, NovaLicense]]
  def getAgentRunInterval    : () => Box[Int]
  def getAgentRunSplaytime   : () => Box[Int]
  def getAgentRunStartHour   : () => Box[Int]
  def getAgentRunStartMinute : () => Box[Int]
  def getScriptEngineEnabled : () => Box[FeatureSwitch]
  def getGlobalPolicyMode    : () => Box[GlobalPolicyMode]

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
  def buildRuleVals(activesRules: Set[RuleId], rules: Seq[Rule], directiveLib: FullActiveTechniqueCategory, groupLib: FullNodeGroupCategory, allNodeInfos: Map[NodeId, NodeInfo]) : Box[Seq[RuleVal]]

  def getNodeContexts(
      nodeIds               : Set[NodeId]
    , allNodeInfos          : Map[NodeId, NodeInfo]
    , allGroups             : FullNodeGroupCategory
    , allLicenses           : Map[NodeId, NovaLicense]
    , globalParameters      : Seq[GlobalParameter]
    , globalAgentRun        : AgentRunInterval
    , globalComplianceMode  : ComplianceMode
  ): Box[Map[NodeId, InterpolationContext]]

  /**
   * From a list of ruleVal, find the list of all impacted nodes
   * with the actual Cf3PolicyDraftBean they will have.
   * Replace all ${node.varName} vars.
   */
  def buildNodeConfigurations(
      activeNodeIds: Set[NodeId]
    , ruleVals     : Seq[RuleVal]
    , nodeContexts : Map[NodeId, InterpolationContext]
    , groupLib     : FullNodeGroupCategory
    , directiveLib : FullActiveTechniqueCategory
    , allNodeInfos : Map[NodeId, NodeInfo]
    , allNodeModes : Map[NodeId, NodeModeConfig]
    , scriptEngineEnabled : FeatureSwitch
  ) : Box[(Seq[NodeConfiguration])]

  /**
   * Check the consistency of each NodeConfiguration.
   */
  def sanitize(configurations:Seq[NodeConfiguration]) : Box[Map[NodeId, NodeConfiguration]]

  /**
   * Forget all other node configuration state.
   * If passed with an empty set, actually forget all node configuration.
   */
  def forgetOtherNodeConfigurationState(keep: Set[NodeId]) : Box[Set[NodeId]]

  /**
   * Get the actual cached values for NodeConfiguration
   */
  def getNodeConfigurationHash(): Box[Map[NodeId, NodeConfigurationHash]]

  /**
   * Detect changes in the NodeConfiguration, to trigger an increment in the related CR
   * The CR are updated in the LDAP
   * Must have all the NodeConfiguration in nodes
   * Returns two seq : the updated rule, and the deleted rule
   */
  def detectUpdatesAndIncrementRuleSerial(nodes : Seq[NodeConfiguration], cache: Map[NodeId, NodeConfigurationHash], rules: Map[RuleId, Rule]) : Box[(Map[RuleId,Int], Seq[RuleId])]

  /**
   * Set all the serial number when needed (a change in CR)
   * Must have all the NodeConfiguration in nodes
   */
  def updateSerialNumber(nodes : Map[NodeId, NodeConfiguration], rules : Map[RuleId, Int]) :  Map[NodeId, NodeConfiguration]


  /**
   * For each nodeConfiguration, check if the config is updated.
   * If so, update the return the new configId.
   */
  def getNodesConfigVersion(allNodeConfigs: Map[NodeId, NodeConfiguration], hashes: Map[NodeId, NodeConfigurationHash], generationTime: DateTime): Map[NodeId, NodeConfigId]

  /**
   * Actually  write the new configuration for the list of given node.
   * If the node target configuration is the same as the actual, nothing is done.
   * Else, promises are generated;
   * Return the list of configuration successfully written.
   */
  def writeNodeConfigurations(
      rootNodeId      : NodeId
    , updated         : Map[NodeId, NodeConfigId]
    , allNodeConfig   : Map[NodeId, NodeConfiguration]
    , allLicenses     : Map[NodeId, NovaLicense]
    , globalPolicyMode: GlobalPolicyMode
    , generationTime  : DateTime
  ) : Box[Set[NodeConfiguration]]

  /**
   * Set the expected reports for the rule
   * Caution : we can't handle deletion with this
   * @param ruleVal
   * @return
   */
  def setExpectedReports(
      ruleVal          : Seq[RuleVal]
    , nodeConfigs      : Seq[NodeConfiguration]
    , versions         : Map[NodeId, NodeConfigId]
    , updateCrs        : Map[RuleId, Int]
    , deletedCrs       : Seq[RuleId]
    , generationTime   : DateTime
    , allNodeModes     : Map[NodeId, NodeModeConfig]
  ) : Box[Seq[NodeExpectedReports]]

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
  , override val nodeConfigurationService : NodeConfigurationService
  , override val nodeInfoService : NodeInfoService
  , override val licenseRepository: LicenseRepository
  , override val reportingService : ExpectedReportsUpdate
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
  , override val promisesFileWriterService: Cf3PromisesFileWriterService
  , override val getAgentRunInterval: () => Box[Int]
  , override val getAgentRunSplaytime: () => Box[Int]
  , override val getAgentRunStartHour: () => Box[Int]
  , override val getAgentRunStartMinute: () => Box[Int]
  , override val getScriptEngineEnabled: () => Box[FeatureSwitch]
  , override val getGlobalPolicyMode: () => Box[GlobalPolicyMode]
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

  override def beforeDeploymentSync(generationTime: DateTime): Box[Unit] = {
    sequence(codeHooks) { _.beforeDeploymentSync(generationTime) }.map( _ => () )
  }

  def appendPreGenCodeHook(hook: PromiseGenerationHooks): Unit = {
    this.codeHooks.append(hook)
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

  override def findDependantRules() : Box[Seq[Rule]] = roRuleRepo.getAll(true)
  override def getAllNodeInfos(): Box[Map[NodeId, NodeInfo]] = nodeInfoService.getAll
  override def getDirectiveLibrary(): Box[FullActiveTechniqueCategory] = roDirectiveRepository.getFullDirectiveLibrary()
  override def getGroupLibrary(): Box[FullNodeGroupCategory] = roNodeGroupRepository.getFullGroupLibrary()
  override def getAllGlobalParameters: Box[Seq[GlobalParameter]] = parameterService.getAllGlobalParameters()
  override def getAllInventories(): Box[Map[NodeId, NodeInventory]] = roInventoryRepository.getAllNodeInventories(AcceptedInventory)
  override def getGlobalComplianceMode(): Box[GlobalComplianceMode] = complianceModeService.getGlobalComplianceMode
  override def getGlobalAgentRun(): Box[AgentRunInterval] = agentRunService.getGlobalAgentRun()
  override def getAllLicenses(): Box[Map[NodeId, NovaLicense]] = licenseRepository.getAllLicense()
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
    , allLicenses           : Map[NodeId, NovaLicense]
    , globalParameters      : Seq[GlobalParameter]
    , globalAgentRun        : AgentRunInterval
    , globalComplianceMode  : ComplianceMode
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
      parameters            <-  buildParams(globalParameters) ?~! "Can not parsed global parameter (looking for interpolated variables)"
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
                      , nodeContext
                      , parameters
                    )
          )
        }) match {
          case eb:EmptyBox =>
            val e = eb ?~! s"Error while building target configuration node for node ${nodeId.value} which is one of the target of rules. Ignoring it for the rest of the process"
            logger.error(e.messageChain)
            None

          case x => x
        }
      }).toMap
    }
  }

}

///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

trait PromiseGeneration_buildRuleVals extends PromiseGenerationService {

  def ruleValService              : RuleValService

  override def buildRuleVals(activeRuleIds: Set[RuleId], rules:Seq[Rule], directiveLib: FullActiveTechniqueCategory, groupLib: FullNodeGroupCategory, allNodeInfos: Map[NodeId, NodeInfo]) : Box[Seq[RuleVal]] = {
    val appliedRules = rules.filter(r => activeRuleIds.contains(r.id))

    for {
      rawRuleVals <- bestEffort(appliedRules) { rule => ruleValService.buildRuleVal(rule, directiveLib) } ?~! "Could not find configuration vals"
    } yield rawRuleVals
  }

}

///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

trait PromiseGeneration_buildNodeConfigurations extends PromiseGenerationService with Loggable {
  def roNodeGroupRepository: RoNodeGroupRepository

  /**
   * This is the draft of the policy, not yet a cfengine policy, but a level of abstraction between both
   */
   private[this] case class PolicyDraft(
      ruleId         : RuleId
    , directiveId    : DirectiveId
    , technique      : Technique
    , variableMap    : InterpolationContext => Box[Map[String, Variable]]
    , trackerVariable: TrackerVariable
    , priority       : Int
    , isSystem       : Boolean
    , policyMode     : Option[PolicyMode]
    , serial         : Int
    , ruleOrder      : BundleOrder
    , directiveOrder : BundleOrder
  ) extends HashcodeCaching

   /**
   * From a list of ruleVal, find the list of all impacted nodes
   * with the actual Cf3PolicyDraft they will have.
   * Replace all ${rudder.node.varName} vars, returns the nodes ready to be configured, and expanded RuleVal
   * allNodeInfos *must* contains the nodes info of every nodes
   */
  override def buildNodeConfigurations(
      activeNodeIds: Set[NodeId]
    , ruleVals     : Seq[RuleVal]
    , nodeContexts : Map[NodeId, InterpolationContext]
    , groupLib     : FullNodeGroupCategory
    , directiveLib : FullActiveTechniqueCategory
    , allNodeInfos : Map[NodeId, NodeInfo]
    , allNodeModes : Map[NodeId, NodeModeConfig]
    , scriptEngineEnabled  : FeatureSwitch
  ) : Box[Seq[NodeConfiguration]] = {

    //step 1: from RuleVals to expanded rules vals
    //1.1: group by nodes (because parameter expansion is node sensitive
    //1.3: build node config, binding ${rudder.parameters} parameters

    //1.1: group by nodes

    val seqOfMapOfPolicyDraftByNodeId = ruleVals.map { ruleVal =>

      val wantedNodeIds = groupLib.getNodeIds(ruleVal.targets, allNodeInfos)

      val nodeIds = wantedNodeIds.intersect(allNodeInfos.keySet)
      if(nodeIds.size != wantedNodeIds.size) {
        logger.error(s"Some nodes are in the target of rule ${ruleVal.ruleId.value} but are not present " +
            s"in the system. It looks like an inconsistency error. Ignored nodes: ${(wantedNodeIds -- nodeIds).map( _.value).mkString(", ")}")
      }

      val drafts: Seq[PolicyDraft] = ruleVal.directiveVals.map { directive =>
        PolicyDraft(
            ruleId         = ruleVal.ruleId
          , directiveId    = directive.directiveId
          , technique      = directive.technique
          , variableMap    = directive.variables
          , trackerVariable= directive.trackerVariable
          , priority       = directive.priority
          , isSystem       = directive.isSystem
          , policyMode     = directive.policyMode
          , serial         = ruleVal.serial
          , ruleOrder      = ruleVal.ruleOrder
          , directiveOrder = directive.directiveOrder
        )
      }

      nodeIds.map(id => (id, drafts)).toMap
    }

    //now, actually group by node
    //no consistancy / unicity check is done here, it will be done
    //in an other phase. We are just switching to a node-first view.
    val policyDraftByNode: Map[NodeId, Seq[PolicyDraft]] = {

      (Map.empty[NodeId, Seq[PolicyDraft]]/:seqOfMapOfPolicyDraftByNodeId){ case (global, newMap) =>
        val g = global.map{ case (nodeId, seq) => (nodeId, seq ++ newMap.getOrElse(nodeId, Seq()))}
        //add node not yet in global
        val keys = newMap.keySet -- global.keySet
        val missing = newMap.filterKeys(k => keys.contains(k))
        g ++ missing
      }

    }

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

    //1.3: build node config, binding ${rudder./node.properties} parameters
    // open a scope for the JsEngine, because its init is long.
    JsEngineProvider.withNewEngine(scriptEngineEnabled) { jsEngine =>

      val nodeConfigs = bestEffort(nodeContexts.toSeq) { case (nodeId, context) =>

          (for {
            drafts          <- Box(policyDraftByNode.get(nodeId)) ?~! "Promise generation algorithme error: cannot find back the configuration information for a node"
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
            parameters      <- bestEffort(context.parameters.toSeq) { case (name, param) =>
                                 for {
                                   p <- param(context)
                                 } yield {
                                   (name, p)
                                 }
                               }
            cf3PolicyDrafts <- bestEffort(drafts) { draft =>
                                  (for {
                                    //bind variables with interpolated context
                                    expandedVariables <- draft.variableMap(context)
                                    // And now, for each variable, eval - if needed - the result
                                    evaluatedVars     <- bestEffort(expandedVariables.toSeq) { case (k, v) =>
                                                            //js lib is specific to the node os, bind here to not leak eval between vars
                                                            val jsLib = context.nodeInfo.osDetails.os match {
                                                             case AixOS => JsRudderLibBinding.Aix
                                                             case _     => JsRudderLibBinding.Crypt
                                                           }
                                                           jsEngine.eval(v, jsLib).map( x => (k, x) )
                                                         }
                                  } yield {

                                    Cf3PolicyDraft(
                                        id = Cf3PolicyDraftId(draft.ruleId, draft.directiveId)
                                      , technique = draft.technique
                                        // if the technique don't have an acceptation date time, this is bad. Use "now",
                                        // which mean that it will be considered as new every time.
                                      , techniqueUpdateTime = directiveLib.allTechniques.get(draft.technique.id).flatMap( _._2 ).getOrElse(DateTime.now)
                                      , variableMap = evaluatedVars.toMap
                                      , trackerVariable = draft.trackerVariable
                                      , priority = draft.priority
                                      , isSystem = draft.isSystem
                                      , policyMode = draft.policyMode
                                      , serial = draft.serial
                                      , ruleOrder = draft.ruleOrder
                                      , directiveOrder = draft.directiveOrder
                                      , overrides = Set()
                                    )
                                  }).dedupFailures(s"When processing directive '${draft.directiveOrder.value}'")
                                }
          } yield {
            NodeConfiguration(
                nodeInfo = context.nodeInfo
              , modesConfig = allNodeModes(context.nodeInfo.id)
              , policyDrafts = cf3PolicyDrafts.toSet
              , nodeContext = context.nodeContext
              , parameters = parameters.map { case (k,v) => ParameterForConfiguration(k, v) }.toSet
              , isRootServer = context.nodeInfo.id == context.policyServerInfo.id
            )
          }).dedupFailures(
                s"Error with parameters expansion for node '${context.nodeInfo.hostname}' (${context.nodeInfo.id.value})"
              , _.replaceAll("on node .*", "")
            )
      }
    nodeConfigs
    }
  }

}

///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

trait PromiseGeneration_updateAndWriteRule extends PromiseGenerationService {

  def nodeConfigurationService : NodeConfigurationService
  def woRuleRepo: WoRuleRepository
  def promisesFileWriterService: Cf3PromisesFileWriterService

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
   * Check the consistency of each NodeConfiguration.
   */
  def sanitize(targetConfigurations:Seq[NodeConfiguration]) : Box[Map[NodeId, NodeConfiguration]] = {
    nodeConfigurationService.sanitize(targetConfigurations)
  }

  def forgetOtherNodeConfigurationState(keep: Set[NodeId]) : Box[Set[NodeId]] = {
    nodeConfigurationService.onlyKeepNodeConfiguration(keep)
  }

  def getNodeConfigurationHash(): Box[Map[NodeId, NodeConfigurationHash]] = nodeConfigurationService.getNodeConfigurationHash()

  /**
   * Detect changes in rules and update their serial
   * Returns two seq : the updated rules, and the deleted rules
   */
  def detectUpdatesAndIncrementRuleSerial(
      nodes       : Seq[NodeConfiguration]
    , cache       : Map[NodeId, NodeConfigurationHash]
    , allRules    : Map[RuleId, Rule]
  ) : Box[(Map[RuleId,Int], Seq[RuleId])] = {
    val firstElt = (Map[RuleId,Int](), Seq[RuleId]())
    // First, fetch the updated CRs (which are either updated or deleted)
    val res = (( Full(firstElt) )/:(nodeConfigurationService.detectSerialIncrementRequest(nodes, cache.isEmpty)) ) { case (Full((updated, deleted)), ruleId) => {
      allRules.get(ruleId) match {
        case Some(rule) =>
          woRuleRepo.incrementSerial(rule.id) match {
            case Full(newSerial) =>
              logger.trace("Updating rule %s to serial %d".format(rule.id.value, newSerial))
              Full( (updated + (rule.id -> newSerial), deleted) )
            case f : EmptyBox =>
              //early stop
              return f
          }
        case None =>
          Full((updated.toMap, (deleted :+ ruleId)))
      }
    } }

    res.foreach { case (updated, deleted) =>
      if(updated.nonEmpty) {
        ComplianceDebugLogger.debug(s"Updated rules:${ updated.map { case (id, serial) =>
          s"[${id.value}->${serial}]"
        }.mkString("") }")
      }
      if(deleted.nonEmpty) {
        ComplianceDebugLogger.debug(s"Deleted rules:${ deleted.map { id =>
          s"[${id.value}]"
        }.mkString("") }")
      }
    }
    res
   }

  /**
   * Increment the serial number of the CR. Must have ALL NODES as inputs
   */
  def updateSerialNumber(allConfigs : Map[NodeId, NodeConfiguration], rules: Map[RuleId, Int]) : Map[NodeId, NodeConfiguration] = {
    allConfigs.map { case (id, config) => (id, config.setSerial(rules)) }.toMap
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
    val updatedNodes = nodeConfigurationService.selectUpdatedNodeConfiguration(allNodeConfigs, hashes)

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


  /**
   * Actually  write the new configuration for the list of given node.
   * If the node target configuration is the same as the actual, nothing is done.
   * Else, promises are generated;
   * Return the list of configuration successfully written.
   */
  def writeNodeConfigurations(
      rootNodeId      : NodeId
    , updated         : Map[NodeId, NodeConfigId]
    , allNodeConfigs  : Map[NodeId, NodeConfiguration]
    , allLicenses     : Map[NodeId, NovaLicense]
    , globalPolicyMode: GlobalPolicyMode
    , generationTime  : DateTime
  ) : Box[Set[NodeConfiguration]] = {

    val fsWrite0   =  System.currentTimeMillis

    for {
      written    <- promisesFileWriterService.writeTemplate(rootNodeId, updated.keySet, allNodeConfigs, updated, allLicenses, globalPolicyMode, generationTime)
      ldapWrite0 =  DateTime.now.getMillis
      fsWrite1   =  (ldapWrite0 - fsWrite0)
      _          =  logger.debug(s"Node configuration written on filesystem in ${fsWrite1} ms")
      //update the hash for the updated node configuration for that generation
      toCache    =  allNodeConfigs.filterKeys(updated.contains(_)).values.toSet
      cached     <- nodeConfigurationService.cacheNodeConfiguration(toCache, generationTime)
      ldapWrite1 =  (DateTime.now.getMillis - ldapWrite0)
      _          =  logger.debug(s"Node configuration cached in LDAP in ${ldapWrite1} ms")
    } yield {
      written.toSet
    }
  }

}

///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

trait PromiseGeneration_setExpectedReports extends PromiseGenerationService {
  def reportingService : ExpectedReportsUpdate
  def complianceCache  : CachedFindRuleNodeStatusReports

   /**
   * Update the serials in the rule vals based on the updated rule (which may be empty if nothing is updated)
   * Goal : actually set the right serial in them, as well as the correct variable
   * So we can have several rule with different subset of values
   */
  private[this] def updateRuleVal(
      rulesVal: Seq[ExpandedRuleVal]
    , rules   : Map[RuleId,Int]
  ) : Seq[ExpandedRuleVal] = {
    rulesVal.map(ruleVal => {
      rules.find { case(id,serial) => id == ruleVal.ruleId } match {
        case Some((id,serial)) =>
          ruleVal.copy(serial = serial)
        case _ => ruleVal
      }
    })
  }

  private[this] def getExpandedRuleVal(
      ruleVals   :Seq[RuleVal]
    , nodeConfigs: Seq[NodeConfiguration]
    , versions   : Map[NodeId, NodeConfigId]
  ) : Seq[ExpandedRuleVal]= {
    ruleVals map { rule =>

      val directives = (nodeConfigs.flatMap { nodeConfig =>
        val expectedDirectiveValsForNode = nodeConfig.policyDrafts.filter( x => x.id.ruleId == rule.ruleId) match {
            case drafts if drafts.size > 0 =>
              val directives = drafts.map { draft =>
                rule.directiveVals.find( _.directiveId == draft.id.directiveId) match {
                  case None =>
                    logger.error("Inconsistency in promise generation algorithme: missing original directive for a node configuration,"+
                        s"please report this message. Directive with id '${draft.id.directiveId.value}' in rule '${rule.ruleId.value}' will be ignored")
                    None
                  case Some(directiveVal) =>
                    Some(draft.toDirectiveVal(directiveVal.originalVariables))
                }
              }.flatten

              if(directives.size > 0) Some(directives) else None

            case _ => None
        }

        expectedDirectiveValsForNode.map(d => NodeAndConfigId(nodeConfig.nodeInfo.id, versions(nodeConfig.nodeInfo.id)) -> d.toSeq)
      })

      ExpandedRuleVal(
          rule.ruleId
        , rule.serial
        , directives.toMap
      )
    }
  }

  override def setExpectedReports(
      ruleVal          : Seq[RuleVal]
    , configs          : Seq[NodeConfiguration]
    , updatedNodes     : Map[NodeId, NodeConfigId]
    , updatedCrs       : Map[RuleId, Int]
    , deletedCrs       : Seq[RuleId]
    , generationTime   : DateTime
    , allNodeModes     : Map[NodeId, NodeModeConfig]
  ) : Box[Seq[NodeExpectedReports]] = {

    val expandedRuleVal = getExpandedRuleVal(ruleVal, configs, updatedNodes)
    val updatedRuleVal = updateRuleVal(expandedRuleVal, updatedCrs)

    //we also want to build the list of overriden directive based on unique techniques.
    val overriden = configs.flatMap { nodeConfig =>
      nodeConfig.policyDrafts.flatMap( x => x.overrides.map { case (ruleId, directiveId) =>
        UniqueOverrides(nodeConfig.nodeInfo.id, ruleId, directiveId, x.id)
      })
    }.toSet

    reportingService.updateExpectedReports(updatedRuleVal, deletedCrs, updatedNodes, generationTime, overriden, allNodeModes)
  }

  override def invalidateComplianceCache(nodeIds: Set[NodeId]): Unit = {
    complianceCache.invalidate(nodeIds)
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
