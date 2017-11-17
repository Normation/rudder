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
import com.normation.rudder.services.policies.nodeconfig.NodeConfigurationHash
import com.normation.utils.Control._
import net.liftweb.common._
import com.normation.rudder.domain.parameters.GlobalParameter
import com.normation.inventory.services.core.ReadOnlyFullInventoryRepository
import com.normation.inventory.domain.NodeInventory
import com.normation.inventory.domain.AcceptedInventory
import com.normation.inventory.domain.NodeInventory
import com.normation.rudder.domain.parameters.GlobalParameter
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
import com.normation.rudder.services.policies.write.PolicyWriterService
import com.normation.rudder.reports.GlobalComplianceMode
import com.normation.rudder.domain.licenses.CfeEnterpriseLicense
import com.normation.rudder.domain.appconfig.FeatureSwitch
import com.normation.inventory.domain.AixOS
import com.normation.rudder.domain.reports.NodeModeConfig
import com.normation.rudder.reports.HeartbeatConfiguration
import com.normation.rudder.hooks.RunHooks
import com.normation.rudder.hooks.HookEnvPairs
import scala.concurrent.Future
import com.normation.rudder.hooks.HooksImplicits
import com.normation.rudder.domain.nodes.NodeState
import com.normation.rudder.services.policies.nodeconfig.NodeConfigurationHashRepository
import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.domain.TechniqueGenerationMode
import com.normation.inventory.domain.AgentType
import com.normation.rudder.domain.reports.DirectiveExpectedReports
import com.normation.rudder.domain.reports.ComponentExpectedReport
import com.normation.rudder.domain.reports.RuleExpectedReports
import com.normation.rudder.domain.logger.TimingDebugLogger

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
      //fetch all
      preHooks             <- RunHooks.getHooks(HOOKS_D + "/policy-generation-started", HOOKS_IGNORE_SUFFIXES)
      _                    <- RunHooks.syncRun(preHooks, HookEnvPairs.build( ("RUDDER_GENERATION_DATETIME", generationTime.toString) ), systemEnv)
      timeRunPreGenHooks   =  (System.currentTimeMillis - initialTime)
      _                    =  logger.debug(s"Pre-policy-generation scripts hooks ran in ${timeRunPreGenHooks} ms")

      codePreGenHooksTime  =  System.currentTimeMillis
      _                    <- beforeDeploymentSync(generationTime)
      timeCodePreGenHooks  =  (System.currentTimeMillis - codePreGenHooksTime)
      _                    =  logger.debug(s"Pre-policy-generation modules hooks in ${timeCodePreGenHooks} ms, start getting all generation related data.")

      fetch0Time           =  System.currentTimeMillis
      allRules             <- findDependantRules() ?~! "Could not find dependant rules"
      fetch1Time           =  System.currentTimeMillis
      _                    =  logger.trace(s"Fetched rules in ${fetch1Time-fetch0Time} ms")
      allNodeInfos         <- getAllNodeInfos.map( _.filter { case(_,n) =>
                                if(n.state == NodeState.Disabled) {
                                  logger.debug(s"Skipping node '${n.id.value}' because the node is in state '${n.state.name}'")
                                  false
                                } else true
                              })  ?~! "Could not get Node Infos" //disabled node don't get new policies
      fetch2Time           =  System.currentTimeMillis
      _                    =  logger.trace(s"Fetched node infos in ${fetch2Time-fetch1Time} ms")
      directiveLib         <- getDirectiveLibrary() ?~! "Could not get the directive library"
      fetch3Time           =  System.currentTimeMillis
      _                    =  logger.trace(s"Fetched directives in ${fetch3Time-fetch2Time} ms")
      groupLib             <- getGroupLibrary() ?~! "Could not get the group library"
      fetch4Time           =  System.currentTimeMillis
      _                    =  logger.trace(s"Fetched groups in ${fetch4Time-fetch3Time} ms")
      allParameters        <- getAllGlobalParameters ?~! "Could not get global parameters"
      fetch5Time           =  System.currentTimeMillis
      _                    =  logger.trace(s"Fetched global parameters in ${fetch5Time-fetch4Time} ms")
      globalAgentRun       <- getGlobalAgentRun
      fetch6Time           =  System.currentTimeMillis
      _                    =  logger.trace(s"Fetched run infos in ${fetch6Time-fetch5Time} ms")
      scriptEngineEnabled  <- getScriptEngineEnabled() ?~! "Could not get if we should use the script engine to evaluate directive parameters"
      globalComplianceMode <- getGlobalComplianceMode
      globalPolicyMode     <- getGlobalPolicyMode() ?~! "Cannot get the Global Policy Mode (Enforce or Verify)"
      nodeConfigCaches     <- getNodeConfigurationHash() ?~! "Cannot get the Configuration Cache"
      allLicenses          <- getAllLicenses() ?~! "Cannont get licenses information"
      allNodeModes         =  buildNodeModes(allNodeInfos, globalComplianceMode, globalAgentRun, globalPolicyMode)
      timeFetchAll         =  (System.currentTimeMillis - fetch0Time)
      _                    =  logger.debug(s"All relevant information fetched in ${timeFetchAll} ms, start names historization.")

      /////
      ///// end of inputs, all information gathered for promise generation.
      /////

      ///// this thing has nothing to do with promise generation and should be
      ///// else where. You can ignore it if you want to understand generation process.
      historizeTime =  System.currentTimeMillis
      historize     <- historizeData(allRules, directiveLib, groupLib, allNodeInfos, globalAgentRun)
      timeHistorize =  (System.currentTimeMillis - historizeTime)
      _             =  logger.debug(s"Historization of names done in ${timeHistorize} ms, start to build rule values.")
      ///// end ignoring

      ///// parse rule for directive parameters and build node context that will be used for them
      ///// also restrict generation to only active rules & nodes:
      ///// - number of nodes: only node somehow targetted by a rule have to be considered.
      ///// - number of rules: any rule without target or with only target with no node can be skipped

      ruleValTime           =  System.currentTimeMillis
      activeRuleIds         =  getAppliedRuleIds(allRules, groupLib, directiveLib, allNodeInfos)
      ruleVals              <- buildRuleVals(activeRuleIds, allRules, directiveLib, groupLib, allNodeInfos) ?~! "Cannot build Rule vals"
      timeRuleVal           =  (System.currentTimeMillis - ruleValTime)
      _                     =  logger.debug(s"RuleVals built in ${timeRuleVal} ms, start to expand their values.")

      nodeContextsTime      =  System.currentTimeMillis
      activeNodeIds         =  (Set[NodeId]()/:ruleVals){case(s,r) => s ++ r.nodeIds}
      nodeContexts          <- getNodeContexts(activeNodeIds, allNodeInfos, groupLib, allLicenses, allParameters, globalAgentRun, globalComplianceMode) ?~! "Could not get node interpolation context"
      timeNodeContexts      =  (System.currentTimeMillis - nodeContextsTime)
      _                     =  logger.debug(s"Node contexts built in ${timeNodeContexts} ms, start to build new node configurations.")

      buildConfigTime       =  System.currentTimeMillis
      /// here, we still have directive by directive info
      configs               <- buildNodeConfigurations(activeNodeIds, ruleVals, nodeContexts, allNodeModes, scriptEngineEnabled, globalPolicyMode) ?~! "Cannot build target configuration node"
      nodeConfigs           =  configs.map(c => (c.nodeInfo.id, c)).toMap
      timeBuildConfig       =  (System.currentTimeMillis - buildConfigTime)
      _                     =  logger.debug(s"Node's target configuration built in ${timeBuildConfig} ms, start to update rule values.")

      updatedNodeConfigIds  =  getNodesConfigVersion(nodeConfigs, nodeConfigCaches, generationTime)
      updatedNodeConfigs    =  nodeConfigs.filterKeys(id => updatedNodeConfigIds.keySet.contains(id))

      reportTime            =  System.currentTimeMillis
      expectedReports       =  computeExpectedReports(ruleVals, updatedNodeConfigs.values.toSeq, updatedNodeConfigIds, generationTime, allNodeModes)
      timeSetExpectedReport =  (System.currentTimeMillis - reportTime)
      _                     =  logger.debug(s"Reports updated in ${timeSetExpectedReport} ms")


      ///// so now we have everything for each updated nodes, we can start writing node policies and then expected reports

      // WHY DO WE NEED TO FORGET OTHER NODES CACHE INFO HERE ?
      _                     <- forgetOtherNodeConfigurationState(nodeConfigs.keySet) ?~! "Cannot clean the configuration cache"

      writeTime             =  System.currentTimeMillis
      writtenNodeConfigs    <- writeNodeConfigurations(rootNodeId, updatedNodeConfigIds, nodeConfigs, allLicenses, globalPolicyMode, generationTime) ?~!"Cannot write configuration node"
      timeWriteNodeConfig   =  (System.currentTimeMillis - writeTime)
      _                     =  logger.debug(s"Node configuration written in ${timeWriteNodeConfig} ms, start to update expected reports.")

      saveExpectedTime      =  System.currentTimeMillis
      savedExpectedReports  <- saveExpectedReports(expectedReports) ?~! "Error when saving expected reports"
      timeSaveExpected      =  (System.currentTimeMillis - saveExpectedTime)
      _                     =  logger.debug(s"Node expected reports saved in base in ${timeWriteNodeConfig} ms.")


      // finally, run post-generation hooks. They can lead to an error message for build, but node policies are updated
      postHooksTime         =  System.currentTimeMillis
      postHooks             <- RunHooks.getHooks(HOOKS_D + "/policy-generation-finished", HOOKS_IGNORE_SUFFIXES)
      updatedNodeIds        =  updatedNodeConfigs.keySet.map( _.value )
      _                     <- RunHooks.syncRun(
                                   postHooks
                                 , HookEnvPairs.build(
                                                         ("RUDDER_GENERATION_DATETIME", generationTime.toString())
                                                       , ("RUDDER_END_GENERATION_DATETIME", new DateTime(postHooksTime).toString) //what is the most alike a end time
                                                       , ("RUDDER_NODE_IDS", updatedNodeIds.mkString(" "))
                                                       , ("RUDDER_NUMBER_NODES_UPDATED", updatedNodeIds.size.toString)
                                                       , ("RUDDER_ROOT_POLICY_SERVER_UPDATED", if(updatedNodeIds.contains("root")) "0" else "1" )
                                                         //for compat in 4.1. Remove in 4.2 or up.
                                                       , ("RUDDER_NODEIDS", updatedNodeIds.mkString(" "))
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
  def getAllLicenses(): Box[Map[NodeId, CfeEnterpriseLicense]]
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
  ) : Box[(Seq[NodeConfiguration])]

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
   * For each nodeConfiguration, check if the config is updated.
   * If so, return the new configId.
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
    , allLicenses     : Map[NodeId, CfeEnterpriseLicense]
    , globalPolicyMode: GlobalPolicyMode
    , generationTime  : DateTime
  ) : Box[Set[NodeConfiguration]]

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
  override def getAllLicenses(): Box[Map[NodeId, CfeEnterpriseLicense]] = licenseRepository.getAllLicense()
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
  ) : Box[Seq[NodeConfiguration]] = BuildNodeConfiguration.buildNodeConfigurations(activeNodeIds, ruleVals, nodeContexts, allNodeModes, scriptEngineEnabled, globalPolicyMode)

}

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
   */
  def buildNodeConfigurations(
      activeNodeIds      : Set[NodeId]
    , ruleVals           : Seq[RuleVal]
    , nodeContexts       : Map[NodeId, InterpolationContext]
    , allNodeModes       : Map[NodeId, NodeModeConfig]
    , scriptEngineEnabled: FeatureSwitch
    , globalPolicyMode   : GlobalPolicyMode
  ) : Box[Seq[NodeConfiguration]] = {

    //step 1: from RuleVals to expanded rules vals

    //group by nodes
    //no consistancy / unicity check is done here, it will be done
    //in an other phase. We are just switching to a node-first view.
    val policyDraftByNode: Map[NodeId, Seq[ParsedPolicyDraft]] = {
      val byNodeDrafts = scala.collection.mutable.Map.empty[NodeId, Vector[ParsedPolicyDraft]]
      ruleVals.foreach { rule =>
        rule.nodeIds.foreach { nodeId =>
          byNodeDrafts.update(nodeId, byNodeDrafts.getOrElse(nodeId, Vector[ParsedPolicyDraft]()) ++ rule.parsedPolicyDraft)
        }
      }
      byNodeDrafts.toMap
    }

    // 1.3: build node config, binding ${rudder./node.properties} parameters
    // open a scope for the JsEngine, because its init is long.
    JsEngineProvider.withNewEngine(scriptEngineEnabled) { jsEngine =>

      val nodeConfigs = bestEffort(nodeContexts.toSeq) { case (nodeId, context) =>

          (for {
            parsedDrafts  <- Box(policyDraftByNode.get(nodeId)) ?~! "Promise generation algorithm error: cannot find back the configuration information for a node"
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
            parameters    <- bestEffort(context.parameters.toSeq) { case (name, param) =>
                               for {
                                 p <- param(context)
                               } yield {
                                 (name, p)
                               }
                             }
            boundedDrafts <- bestEffort(parsedDrafts) { draft =>
                                (for {
                                  //bind variables with interpolated context
                                  expandedVariables <- draft.variables(context)
                                  // And now, for each variable, eval - if needed - the result
                                  expandedVars      <- bestEffort(expandedVariables.toSeq) { case (k, v) =>
                                                          //js lib is specific to the node os, bind here to not leak eval between vars
                                                          val jsLib = context.nodeInfo.osDetails.os match {
                                                           case AixOS => JsRudderLibBinding.Aix
                                                           case _     => JsRudderLibBinding.Crypt
                                                         }
                                                         jsEngine.eval(v, jsLib).map( x => (k, x) )
                                                       }
                                  } yield {
                                    draft.toBoundedPolicyDraft(expandedVars.toMap)
                                  }).dedupFailures(s"When processing directive '${draft.directiveOrder.value}'")
                                }
            // from policy draft, check and build the ordered seq of policy
            policies   <- buildPolicy(context.nodeInfo, globalPolicyMode, boundedDrafts)
          } yield {
            NodeConfiguration(
                nodeInfo     = context.nodeInfo
              , modesConfig  = allNodeModes(context.nodeInfo.id)
              , policies     = policies
              , nodeContext  = context.nodeContext
              , parameters   = parameters.map { case (k,v) => ParameterForConfiguration(k, v) }.toSet
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

  /*
   * merge an ordered seq of variables.
   *
   */
  def mergeVars(vars: Seq[Variable]): Map[String, Variable] = {
    val mergedVars = scala.collection.mutable.Map[String, Variable]()
    for (variable <- vars) {
      variable match {
        case _     : TrackerVariable => // nothing, it's been dealt with already
        case newVar: Variable        =>
          // TODO: #10625 : checked is not used anymore
          if ((!newVar.spec.checked) || (newVar.spec.isSystem)) {
            // Only user defined variables should need to be agregated
          } else {
            val variable = mergedVars.get(newVar.spec.name) match {
              case None =>
                Variable.matchCopy(newVar, setMultivalued = true) //asIntance is ok here, I believe
              case Some(existingVariable) => // value is already there
                // hope it is multivalued, otherwise BAD THINGS will happen
                if (!existingVariable.spec.multivalued) {
                  logger.warn("Attempt to append value into a non multivalued variable, bad things may happen")
                }
                // deals with unique values: in that case, only keep one value.
                // merge is done in order, so if value is already set, does nothing
                if(existingVariable.spec.isUniqueVariable) {
                  if(existingVariable.values.isEmpty) {
                    newVar.values.headOption match {
                      case None    => existingVariable //ok, does nothing more
                      case Some(v) => existingVariable.copyWithSavedValue(v)
                    }
                  } else { //just use already defined unique
                    existingVariable
                  }
                } else { //actually merge variables
                  existingVariable.copyWithAppendedValues(newVar.values)
                }
            }
            mergedVars.put(newVar.spec.name, variable)
          }
      }
    }
    mergedVars.toMap
  }

  /*
   * That methods takes all the policy draft of a node and does what need to be done
   * to have a list of policies that can be translated into agent specific config files.
   * That includes:
   * - merge directives that need to be merged,
   * - split system and user policy,
   * - chose policy for unique technique,
   * - retrieve last update time for techniques,
   * - sanity check policies:
   *   - agent target is ok
   *   - for merged directives:
   *     - same policy mode
   *     - same technique version
   * - sort everything
   */
  def buildPolicy(nodeInfo: NodeInfo, mode: GlobalPolicyMode, boundedPolicyDrafts: Seq[BoundPolicyDraft]): Box[List[Policy]] = {

    // now manage merge of mutli-instance mono-policy techniques
    // each merge can fails because of a consistency error, so we are grouping merge and checks
    // for a technique in a failable function.
    // must give at least one element in parameter
    def merge(nodeInfo: NodeInfo, agentType: AgentType, globalPolicyMode: GlobalPolicyMode, drafts: List[BoundPolicyDraft]): Box[Policy] = {
      drafts match  {
        case Nil =>
          Failure(s"Policy Generation is trying to merge a 0 directive set, which is a development bug. Please report")
        case draft :: Nil => //no merge actually needed
          Full(draft.toPolicy(agentType))
        case _ => //merging needed
          for {
             // check same technique (name and version), policy mode
            sameTechniqueName <- drafts.map( _.technique.id.name ).distinct match {
                                   case name :: Nil => Full(name)
                                   case list        => Failure(s"Policy Generation is trying to merge several directives from different techniques for " +
                                                         s"node ${nodeInfo.hostname} '${nodeInfo.id.value}'. This i likely a bug, please report it. "+
                                                         s"Techniques: ${drafts.map(_.technique.id.toString).mkString(", ")}"
                                                       )
                                 }
            // check that all drafts are system or none are
            sameIsSystem      <- if(drafts.map( _.isSystem ).distinct.size == 1) {
                                   Full("ok")
                                 } else {
                                   Failure(s"Policy Generation is trying to merge several directives with some being systems and other not for " +
                                     s"node ${nodeInfo.hostname} '${nodeInfo.id.value}'. This is likely a bug, please report it. "+
                                     s"Techniques: ${drafts.map(_.technique.id.toString).mkString(", ")}" //not sure if we want techniques or directives or rules here.
                                   )                             }
            // check that all drafts are bound to
            sameVersion       <- drafts.map( _.technique.id.version ).distinct match {
                                   case v :: Nil => Full(v)
                                   case list     => Failure(s"Node ${nodeInfo.hostname} '${nodeInfo.id.value}' get directives from different versions of technique '${sameTechniqueName}', but " +
                                                            s"that technique does not support multi-policy generation. Problematic rules/directives: " +
                                                            drafts.map(d => d.id.ruleId.value + " / " + d.id.directiveId.value).mkString(" ; ")
                                                    )
                                 }
            /*
             * Here, we must go to some lengh to try to keep the "directive" policy mode (for the merge). So we need to distinguish
             * four cases:
             * - all drafts have None policy mode => the result is none.
             * - all drafts have Some(policy mode) and all the same => the directive get that Some(policy mode)
             * - at least two draft have Some(policy mode) which are differents => it's an error, report it
             * - we have some homogeneous Some(policy mode) with at least one None => we must compute the resulting mode with default (global, node) values.
             * The last two can be computed with PolicyMode facility .
             */
            samePolicyMode    <- drafts.map( _.policyMode ).distinct match {
                                   case Nil         => Full(None) //should not happen
                                   case mode :: Nil => Full(mode) //either None or Some(mode), that's ok
                                   case modes       => PolicyMode.computeMode(globalPolicyMode, nodeInfo.node.policyMode, modes).map(Some(_)) ?~! (s"Node ${nodeInfo.hostname} "+
                                                          s"'${nodeInfo.id.value}' get directives with incompatible different policy mode but technique " +
                                                          s"'${sameTechniqueName}/${sameVersion}' does not support multi-policy generation. Problematic rules/directives: " +
                                                          drafts.map(d => d.id.ruleId.value + " / " + d.id.directiveId.value).mkString(" ; "))
                                 }
          } yield {
            // actually merge. Before that, we need to sort drafts, first by priority, then by rule order, then by directive order.
            val sorted = drafts.sortWith { case (d1, d2) =>
              (d1.priority - d2.priority) match {
                case 0 => //sort by bundle order
                  // the bundle full order in the couple (rule name, directive name)
                  BundleOrder.compareList(d1.ruleOrder :: d1.directiveOrder :: Nil, d2.ruleOrder :: d2.directiveOrder :: Nil) <= 0
                case i => i < 0
              }
            }

            // now the first draft is the mode prioritary. We keep it as "main" draft
            // and then we merge all other draft values.
            // we need to do it for expanded and original values and tracking key values


            // one more thing: we need to take care of unique variables.
            // unique variable values have the same size as if they were not unique,
            // but only ONE value is repeated.
            val base = sorted.head.toPolicy(agentType)
            base.copy(
                expandedVars   = mergeVars(sorted.flatMap(_.expandedVars.values))
              , originalVars   = mergeVars(sorted.flatMap(_.originalVars.values))
              , trackerVariable= base.trackerVariable.spec.cloneSetMultivalued.toVariable(sorted.flatMap(_.trackerVariable.values))
            )
          }
        }
    }

    /*
     * We need to split draft in 3 categories:
     * - draft from non-multi instance techniques: we must choose which one to take
     * - draft from multi-instance, non multi-directive-gen technique: we need to
     *   merge directive and check specific consistancy
     * - draft from multi-instance, multi-directive-gen technique (nothing special for them)
     */
    final case class GroupedDrafts(
        uniqueInstance : Map[TechniqueName, Seq[BoundPolicyDraft]]
      , toMerge        : Map[TechniqueName, Seq[BoundPolicyDraft]]
      , multiDirectives: Set[BoundPolicyDraft]
    ) {
      // return a copy of that group with on more toMerge at the correct place
      def addToMerge(draft: BoundPolicyDraft): GroupedDrafts = {
        val name = draft.technique.id.name
        val thatTechnique = toMerge.getOrElse(name, Seq()) :+ draft
        this.copy(toMerge = toMerge + (name -> thatTechnique))
      }
      // return a copy of that group with on more toMerge at the correct place
      def addUniqueInstance(draft: BoundPolicyDraft): GroupedDrafts = {
        val name = draft.technique.id.name
        val thatTechnique = uniqueInstance.getOrElse(draft.technique.id.name, Seq()) :+ draft
        this.copy(uniqueInstance = uniqueInstance + (name -> thatTechnique))
      }
    }



    //
    // Actually do stuff !
    //


    /*
     * First: we must be sure that each BoundPolicyDraft has a unique id.
     * It seems to be a sequel of the past, and we most likely can get ride of it.
     */
    val deduplicateDrafts = boundedPolicyDrafts.groupBy(x => (x.id.ruleId, x.id.directiveId)).map { case (draftId, seq) =>
      val main = seq.head //can not fail because of groupBy
      //compare policy draft
      //Following parameter are not relevant in that comparison (we compare directive, not rule, here:)
      if(seq.size > 1) {
        logger.error(s"The directive '${seq.head.id.directiveId.value}' on rule '${seq.head.id.ruleId.value}' was added several times on node "+
                     s"'${nodeInfo.id.value}' WITH DIFFERENT PARAMETERS VALUE. It's a bug, please report it. Taking one set of parameter "+
                     s"at random for the promise generation.")
        import net.liftweb.json._
        implicit val formats = Serialization.formats(NoTypeHints)
        def r(j:JValue) = if(j == JNothing) "{}" else prettyRender(j)

        val jmain = Extraction.decompose(main)
        logger.error("First directivedraft: " + prettyRender(jmain))
        seq.tail.foreach{ x =>
          val diff  = jmain.diff(Extraction.decompose(x))
          logger.error(s"Diff with other draft: \nadded:${r(diff.added)} \nchanged:${r(diff.changed)} \ndeleted:${r(diff.deleted)}")
        }
      }
      main
    }

    // set trackingKeyVariable to the correct values
    // remark: now that trackingKeyVariable is independant from serial, it could just
    // be given as a method from the draft (no need for more info)
    val updatedTrackingKeyValues = deduplicateDrafts.map { d =>
      val (trackingKeyVariable, trackedVariable) = d.getDirectiveVariable

      val values = {
        // Only multi-instance policy may have a trackingKeyVariable with high cardinal
        // Because if the technique is Unique, then we can't have several directive ID on the same
        // rule, and we just always use the same cf3PolicyId
        val size = if (d.technique.isMultiInstance) { trackedVariable.values.size } else { 1 }
        Seq.fill(size)(d.id.getReportId)
      }
      d.copy(trackerVariable = trackingKeyVariable.copyWithSavedValues(values))
    }

    // group directives by non-multi-instance, multi-instance non-multi-policy, multi-instance-multi-policy
    val groupedDrafts = (GroupedDrafts(Map(),Map(),Set()) /: updatedTrackingKeyValues) { case(grouped, draft) =>
      if(draft.technique.isMultiInstance) {
        draft.technique.generationMode match {
          case TechniqueGenerationMode.MultipleDirectives =>
            grouped.copy( multiDirectives = grouped.multiDirectives + draft )
          case TechniqueGenerationMode.MergeDirectives =>
            //look is there is already directives for the technique
            grouped.addToMerge(draft)
        }
      } else {
        grouped.addUniqueInstance(draft)
      }
    }

    // choose among directives from non-multi-instance technique which one to keep
    val keptUniqueDraft = groupedDrafts.uniqueInstance.map { case (techniqueName, drafts) =>

      val withSameTechnique = drafts.sortBy( _.priority )
      //we know that the size is at least one, so keep the head, and log discard tails

      //two part here: discard less priorized directive,
      //and for same priority, take the first in rule/directive order
      //and add a big warning

      val priority = withSameTechnique.head.priority

      val lesserPriority = withSameTechnique.dropWhile( _.priority == priority )

      //keep the directive with
      val samePriority = withSameTechnique.takeWhile( _.priority == priority).sortWith{ case (d1, d2) =>
        BundleOrder.compareList(List(d1.ruleOrder, d1.directiveOrder), List(d2.ruleOrder, d2.directiveOrder)) <= 0
      }

      val keep = samePriority.head

      //only one log for all discared draft
      if(samePriority.size > 1) {
        logger.warn(s"Unicity check: NON STABLE POLICY ON NODE '${nodeInfo.hostname}' for mono-instance (unique) technique "+
            s"'${keep.technique.id}'. Several directives with same priority '${keep.priority}' are applied. "+
            s"Keeping (ruleId@@directiveId) '${keep.id.ruleId.value}@@${keep.id.directiveId.value}' (order: ${keep.ruleOrder.value}/"+
            s"${keep.directiveOrder.value}, discarding: ${samePriority.tail.map(x => s"${x.id.ruleId.value}@@${x.id.directiveId.value}:"+
            s"${x.ruleOrder.value}/${x.directiveOrder.value}").mkString("'", "', ", "'")}")
      }
      logger.trace(s"Unicity check: on node '${nodeInfo.id.value}' for mono-instance (unique) technique '${keep.technique.id}': "+
          s"keeping (ruleId@@directiveId) '${keep.id.ruleId.value}@@${keep.id.directiveId.value}', discarding less priorize: "+
          s"${lesserPriority.map(x => x.id.ruleId.value+"@@"+x.id.directiveId.value).mkString("'", "', ", "'")}")

      val o = (samePriority.tail.map(x => (x.id.ruleId,x.id.directiveId)) ++ lesserPriority.map(x => (x.id.ruleId,x.id.directiveId))).toSet

      keep.copy(overrides = o)
    }

    // now proceed the policies that need to be merged
    for {
      agent  <- Box(nodeInfo.agentsName.headOption) ?~! s"No agent defined for Node ${nodeInfo.hostname}, (id ${nodeInfo.id.value}), at least one should be defined"
      merged <- {
                val drafts = groupedDrafts.toMerge.toSeq
                sequence(drafts) { case (name, seq) =>
                  merge(nodeInfo, agent.agentType, mode, seq.toList)
                }
      }
      //now change remaining BoundPolicyDraft to Policy, managing tracking variable values
      drafts =  merged ++ (keptUniqueDraft ++ groupedDrafts.multiDirectives).map { d =>
                  d.toPolicy(agent.agentType)
                }
      // check that agent is supported by all techniques
      _      <- sequence(drafts) { d =>
                  if(d.technique.agentConfigs.exists( _.agentType == agent.agentType)) {
                    Full("ok")
                  } else {
                    Failure(s"Technique '${d.technique.id}' does not support agent '${agent.agentType}' which is needed for node '${nodeInfo.hostname}' (${nodeInfo.id.value}).")
                  }
                }
    } yield {
      // we are sorting several things in that method, and I'm not sure we want to sort them in the same way (and so
      // factorize out sorting core) or not. We sort:
      // - policies based on non-multi-instance technique, by priority and then rule/directive order, to choose the correct one
      // - in a multi-instance, mono-policy case, we sort directives in the same fashion (but does priority make sense here?)
      // - and finally, here we sort all drafts (in that case, only by bundle order)

      drafts.sortWith { case(d1, d2) =>
        BundleOrder.compareList(List(d1.ruleOrder, d1.directiveOrder), List(d2.ruleOrder, d2.directiveOrder)) <= 0
      }.toList
    }
  }
}

///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

trait PromiseGeneration_updateAndWriteRule extends PromiseGenerationService {

  def nodeConfigurationService : NodeConfigurationHashRepository
  def woRuleRepo: WoRuleRepository
  def promisesFileWriterService: PolicyWriterService

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

  def forgetOtherNodeConfigurationState(keep: Set[NodeId]) : Box[Set[NodeId]] = {
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
      logger.debug(s"Not updating non-modified node configuration: [${notUpdatedConfig.map( _.id.value).mkString(", ")}]")
    }

    if(updatedConfig.size == 0) {
      logger.info("No node configuration was updated, no promises to write")
      Set()
    } else {
      val nodeToKeep = updatedConfig.map( _.id ).toSet
      logger.info(s"Configuration of following nodes were updated, their promises are going to be written: [${updatedConfig.map(_.id.value).mkString(", ")}]")
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
    , allLicenses     : Map[NodeId, CfeEnterpriseLicense]
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

      // #10625 : that should be one logic-level up (in the main generation for loop)

      toCache    =  allNodeConfigs.filterKeys(updated.contains(_)).values.toSet
      cached     <- nodeConfigurationService.save(toCache.map(x => NodeConfigurationHash(x, generationTime)))
      ldapWrite1 =  (DateTime.now.getMillis - ldapWrite0)
      _          =  logger.debug(s"Node configuration cached in LDAP in ${ldapWrite1} ms")
    } yield {
      written.toSet
    }
  }

}

///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

trait PromiseGeneration_setExpectedReports extends PromiseGenerationService {
  def complianceCache  : CachedFindRuleNodeStatusReports
  def confExpectedRepo : UpdateExpectedReportsRepository

  private[this] def getExpandedRuleVal(
      ruleVals   : Seq[RuleVal]
    , nodeConfigs: Seq[NodeConfiguration]
    , versions   : Map[NodeId, NodeConfigId]
  ) : Seq[ExpandedRuleVal]= {
    ruleVals map { rule =>

      val directives = (nodeConfigs.flatMap { nodeConfig =>
        val expectedUnboundBoundedPolicyDraftsForNode = nodeConfig.policies.filter( x => x.id.ruleId == rule.ruleId) match {
            case drafts if drafts.size > 0 => Some(drafts.map { _.toUnboundBoundedPolicyDraft() })
            case _                         => None
        }

        expectedUnboundBoundedPolicyDraftsForNode.map(d => NodeAndConfigId(nodeConfig.nodeInfo.id, versions(nodeConfig.nodeInfo.id)) -> d.toSeq)
      })

      ExpandedRuleVal(rule.ruleId, directives.toMap)
    }
  }

  /**
   * Container class to store that for a given
   * node, for a rule and directive, all values
   * are overriden by an other directive derived from the
   * same unique technique
   */
  case class UniqueOverrides(
      nodeId: NodeId
    , ruleId: RuleId
    , directiveId: DirectiveId
    , overridenBy: PolicyId
  )

  override def computeExpectedReports(
      ruleVal          : Seq[RuleVal]
    , configs          : Seq[NodeConfiguration]
    , updatedNodes     : Map[NodeId, NodeConfigId]
    , generationTime   : DateTime
    , allNodeModes     : Map[NodeId, NodeModeConfig]
  ) : List[NodeExpectedReports] = {

    val expandedRuleVal = getExpandedRuleVal(ruleVal, configs, updatedNodes)

    //we also want to build the list of overriden directive based on unique techniques.
    val overriden = configs.flatMap { nodeConfig =>
      nodeConfig.policies.flatMap( x => x.overrides.map { case (ruleId, directiveId) =>
        UniqueOverrides(nodeConfig.nodeInfo.id, ruleId, directiveId, x.id)
      })
    }.toSet

    updateExpectedReports(expandedRuleVal, updatedNodes, generationTime, overriden, allNodeModes)
  }

  /**
   * Utility method that filters overriden directive by node
   * in expandedruleval
   */
  private[this] def filterOverridenDirectives(
      expandedRuleVals  : Seq[ExpandedRuleVal]
    , overrides         : Set[UniqueOverrides]
  ): Seq[ExpandedRuleVal] = {
    /*
     * Start by filtering expandedRuleVals to remove overriden
     * directives from them.
     */
    val byRulesOverride = overrides.groupBy { _.ruleId }
    expandedRuleVals.map { case rule@ExpandedRuleVal(ruleId, configs) =>
      byRulesOverride.get(ruleId) match {
        case None => rule
        case Some(overridenDirectives) =>
          val byNodeOverride = overridenDirectives.groupBy { _.nodeId }
          val filteredConfig = configs.map { case(i@NodeAndConfigId(nodeId, c), directives) =>
            val toFilter = byNodeOverride.getOrElse(nodeId, Seq()).map( _.directiveId).toSet
            (i, directives.filterNot { directive => toFilter.contains(directive.directiveId) })
          }
          ExpandedRuleVal(ruleId, filteredConfig)
      }
    }
  }

  /*
   * Update the list of expected reports when we do a deployment
   */
  def updateExpectedReports(
      expandedRuleVals  : Seq[ExpandedRuleVal]
    , updatedNodeConfigs: Map[NodeId, NodeConfigId]
    , generationTime    : DateTime
    , overrides         : Set[UniqueOverrides]
    , allNodeModes      : Map[NodeId, NodeModeConfig]
  ) : List[NodeExpectedReports] = {
    val filteredExpandedRuleVals = filterOverridenDirectives(expandedRuleVals, overrides)


    // transform to rule expected reports by node
    val directivesExpectedReports = filteredExpandedRuleVals.map { case ExpandedRuleVal(ruleId, configs) =>
      configs.toSeq.map { case (nodeConfigId, directives) =>
        // each directive is converted into Seq[DirectiveExpectedReports]
        val directiveExpected = directives.map { directive =>


               val seq = ComputeCardinalityOfUnboundBoundedPolicyDraft.getTrackingKeyLinearisation(directive)

          // #10625: it seems that that can be kept in D-by-D.

               seq.map { case(componentName, componentsValues, unexpandedCompValues) =>
                          DirectiveExpectedReports(
                              directive.directiveId
                            , directive.policyMode
                            , directive.isSystem
                            , List(
                                ComponentExpectedReport(
                                    componentName
          // <== #10625: that can be deleted because we are using ComponentValues.size in place of it
                                  , componentsValues.size
          // ==>
                                  , componentsValues.toList
                                  , unexpandedCompValues.toList
                                )
                              )
                          )
               }
        }

        (nodeConfigId, ruleId, directiveExpected.flatten)
      }
    }.flatten.toList

    //now group back by nodeConfigId and transform to ruleExpectedReport
    val ruleExepectedByNode = directivesExpectedReports.groupBy( _._1 ).mapValues { seq =>
      // build ruleExpected
      // we're grouping by (ruleId) even if we should have an homogeneous set here
      seq.groupBy( t => t._2 ).map { case ( ruleId, directives ) =>
        RuleExpectedReports(ruleId, directives.flatMap(_._3).toList)
      }
    }

    // and finally add what is missing for NodeExpectedReports

    ruleExepectedByNode.map { case (nodeAndConfigId, rules) =>
      val nodeId = nodeAndConfigId.nodeId
      NodeExpectedReports(
          nodeId
        , nodeAndConfigId.version
        , generationTime
        , None
        , allNodeModes(nodeId) //that shall not throw, because we have all nodes here
        , rules.toList
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
   * Utility object to calculate cardinality of components
   * Component, ComponentValues(expanded), ComponentValues (unexpanded))
   */
object ComputeCardinalityOfUnboundBoundedPolicyDraft extends Loggable {
  import com.normation.cfclerk.xmlparsers.CfclerkXmlConstants.DEFAULT_COMPONENT_KEY

  def getTrackingKeyLinearisation(container : ExpandedUnboundBoundedPolicyDraft) : Seq[(String, Seq[String], Seq[String])] = {

    // Computes the components values, and the unexpanded component values
    val getTrackingVariableCardinality : (Seq[String], Seq[String]) = {
      val boundingVar = container.trackerVariable.spec.boundingVariable.getOrElse(container.trackerVariable.spec.name)
      // now the cardinality is the length of the boundingVariable
      (container.variables.get(boundingVar), container.originalVariables.get(boundingVar)) match {
        case (None, None) =>
          logger.debug("Could not find the bounded variable %s for %s in ParsedPolicyDraft %s".format(
              boundingVar, container.trackerVariable.spec.name, container.directiveId.value))
          (Seq(DEFAULT_COMPONENT_KEY),Seq()) // this is an autobounding policy
        case (Some(variable), Some(originalVariables)) if (variable.values.size==originalVariables.values.size) =>
          (variable.values, originalVariables.values)
        case (Some(variable), Some(originalVariables)) =>
          logger.warn("Expanded and unexpanded values for bounded variable %s for %s in ParsedPolicyDraft %s have not the same size : %s and %s".format(
              boundingVar, container.trackerVariable.spec.name, container.directiveId.value,variable.values, originalVariables.values ))
          (variable.values, originalVariables.values)
        case (None, Some(originalVariables)) =>
          (Seq(DEFAULT_COMPONENT_KEY),originalVariables.values) // this is an autobounding policy
        case (Some(variable), None) =>
          logger.warn("Somewhere in the expansion of variables, the bounded variable %s for %s in ParsedPolicyDraft %s appeared, but was not originally there".format(
              boundingVar, container.trackerVariable.spec.name, container.directiveId.value))
          (variable.values,Seq()) // this is an autobounding policy

      }
    }

    /*
     * We have two separate paths:
     * - if the technique is standart, we keep the complex old path
     * - if it is a meta technique, we take the easy paths
     */
    container.technique.providesExpectedReports match {
      case false =>
        // this is the old path
            /*
             * We can have several components, one by section.
             * If there is no component for that policy, the policy is autobounded to DEFAULT_COMPONENT_KEY
             */
            val allComponents = container.technique.rootSection.getAllSections.flatMap { section =>
              if(section.isComponent) {
                section.componentKey match {
                  case None =>
                    //a section that is a component without componentKey variable: card=1, value="None"
                    Some((section.name, Seq(DEFAULT_COMPONENT_KEY), Seq(DEFAULT_COMPONENT_KEY)))
                  case Some(varName) =>
                    //a section with a componentKey variable: card=variable card
                    val values = container.variables.get(varName).map( _.values).getOrElse(Seq())
                    val unexpandedValues = container.originalVariables.get(varName).map( _.values).getOrElse(Seq())
                    if (values.size != unexpandedValues.size)
                      logger.warn("Caution, the size of unexpanded and expanded variables for autobounding variable in section %s for directive %s are not the same : %s and %s".format(
                          section.componentKey, container.directiveId.value, values, unexpandedValues ))
                    Some((section.name, values, unexpandedValues))
                }
              } else {
                None
              }
            }

            if(allComponents.size < 1) {
              //that log is outputed one time for each directive for each node using a technique, it's far too
              //verbose on debug.
              logger.trace("Technique '%s' does not define any components, assigning default component with expected report = 1 for Directive %s".format(
                container.technique.id, container.directiveId))

              val trackingVarCard = getTrackingVariableCardinality
              Seq((container.technique.id.name.value, trackingVarCard._1, trackingVarCard._2))
            } else {
              allComponents
            }
      case true =>
        // this is easy, everything is in the ParsedPolicyDraft; the components contains only one value, the
        // one that we want
        val allComponents = container.technique.rootSection.getAllSections.flatMap { section =>
          if (section.isComponent) {
            section.componentKey match {
              case None =>
                logger.error(s"We don't have defined reports keys for section ${section.name} that should provide predefined values")
                None
              case Some(name) =>
                (container.variables.get(name), container.originalVariables.get(name)) match {
                  case (Some(expandedValues), Some(originalValues)) if expandedValues.values.size == originalValues.values.size =>
                    Some((section.name, expandedValues.values, originalValues.values))

                  case (Some(expandedValues), Some(originalValues)) if expandedValues.values.size != originalValues.values.size =>
                    logger.error(s"In section ${section.name}, the original values and the expanded values don't have the same size. Orignal values are ${originalValues.values}, expanded are ${expandedValues.values}")
                    None

                  case _ =>
                    logger.error(s"The reports keys for section ${section.name} do not exist")
                    None
                }
            }
          } else {
            None
          }
        }
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
