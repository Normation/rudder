/*
*************************************************************************************
* Copyright 2011 Normation SAS
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
import com.normation.rudder.domain.reports.RuleExpectedReports
import com.normation.rudder.repository._
import com.normation.rudder.services.eventlog.HistorizationService
import com.normation.rudder.services.nodes.NodeInfoService
import com.normation.rudder.services.policies.nodeconfig.NodeConfiguration
import com.normation.rudder.services.policies.nodeconfig.NodeConfigurationCache
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
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.reports.NodeConfigId
import com.normation.rudder.reports.ComplianceMode
import com.normation.rudder.reports.ComplianceModeService
import com.normation.rudder.reports.AgentRunIntervalService
import com.normation.rudder.reports.AgentRunInterval
import com.normation.rudder.domain.logger.ComplianceDebugLogger
import com.normation.rudder.services.reports.CachedFindRuleNodeStatusReports
import com.normation.rudder.services.policies.write.Cf3PromisesFileWriterService
import com.normation.rudder.services.policies.write.Cf3PromisesFileWriterService
import com.normation.rudder.services.policies.write.Cf3PolicyDraft
import com.normation.rudder.services.policies.write.Cf3PolicyDraftId


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
    val rootNodeId = Constants.ROOT_POLICY_SERVER_ID

    val result = for {
      //fetch all - yep, memory is cheap... (TODO: size of that for 1000 nodes, 100 rules, 100 directives, 100 groups => ~ 100MB)
      allRules        <- findDependantRules() ?~! "Could not find dependant rules"
      allNodeInfos    <- getAllNodeInfos ?~! "Could not get Node Infos"
      allInventories  <- getAllInventories ?~! "Could not get Node inventories"
      directiveLib    <- getDirectiveLibrary() ?~! "Could not get the directive library"
      groupLib        <- getGroupLibrary() ?~! "Could not get the group library"
      globalParameters<- getAllGlobalParameters ?~! "Could not get global parameters"
      globalAgentRun  <- getGlobalAgentRun
      agentRunInterval    =  getAgentRunInterval()
      agentRunSplaytime   <- getAgentRunSplaytime() ?~! "Could not get agent run splaytime"
      agentRunStartMinute <- getAgentRunStartMinute() ?~! "Could not get agent run start time (minute)"
      agentRunStartHour   <- getAgentRunStartHour() ?~! "Could not get agent run start time (hour)"
      globalComplianceMode <- getGlobalComplianceMode
      nodeConfigCaches     <- getNodeConfigurationCache() ?~! "Cannot get the Configuration Cache"

      //from here, we can restrain the calcul on two axis:
      // - number of nodes: only node somehow targetted by a rule have to be considered.
      // - number of rules: any rule without target or with only target with no node can be skipped
      activeRuleIds = getAppliedRuleIds(allRules, groupLib, directiveLib, allNodeInfos)
      activeNodeIds = groupLib.getNodeIds(allRules.flatMap(_.targets).toSet, allNodeInfos)
      timeFetchAll    =  (System.currentTimeMillis - initialTime)
      _               =  logger.debug(s"All relevant information fetched in ${timeFetchAll}ms, start names historization.")


      nodeContextsTime =  System.currentTimeMillis
      nodeContexts     <- getNodeContexts(activeNodeIds, allNodeInfos, allInventories, globalParameters, globalAgentRun, globalComplianceMode) ?~! "Could not get node interpolation context"
      timeNodeContexts =  (System.currentTimeMillis - nodeContextsTime)
      _                =  logger.debug(s"Node contexts built in ${timeNodeContexts}ms, start to build new node configurations.")

      /// end of inputs, all information gathered for promise generation.

      ///// this thing has nothing to do with promise generation and should be
      ///// else where. You can ignore it if you want to understand generation process.
      historizeTime =  System.currentTimeMillis
      historize     <- historizeData(allRules, directiveLib, groupLib, allNodeInfos, agentRunInterval, agentRunSplaytime, agentRunStartHour, agentRunStartMinute)
      timeHistorize =  (System.currentTimeMillis - historizeTime)
      _             =  logger.debug(s"Historization of names done in ${timeHistorize}ms, start to build rule values.")
      ///// end ignoring


      ruleValTime   =  System.currentTimeMillis
                       //only keep actually applied rules in a format where parameter analysis on directive is done.
      ruleVals      <- buildRuleVals(activeRuleIds, allRules, directiveLib, groupLib, allNodeInfos) ?~! "Cannot build Rule vals"
      timeRuleVal   =  (System.currentTimeMillis - ruleValTime)
      _             =  logger.debug(s"RuleVals built in ${timeRuleVal}ms, start to expand their values.")

      buildConfigTime =  System.currentTimeMillis
      config          <- buildNodeConfigurations(activeNodeIds, ruleVals, nodeContexts, groupLib, allNodeInfos) ?~! "Cannot build target configuration node"
      timeBuildConfig =  (System.currentTimeMillis - buildConfigTime)
      _               =  logger.debug(s"Node's target configuration built in ${timeBuildConfig}, start to update rule values.")


      sanitizeTime        =  System.currentTimeMillis
      _                   <- forgetOtherNodeConfigurationState(config.map(_.nodeInfo.id).toSet) ?~! "Cannot clean the configuration cache"
      sanitizedNodeConfig <- sanitize(config) ?~! "Cannot set target configuration node"
      timeSanitize        =  (System.currentTimeMillis - sanitizeTime)
      _                   =  logger.debug(s"RuleVals updated in ${timeSanitize} millisec, start to detect changes in node configuration.")

      beginTime                =  System.currentTimeMillis
      //that's the first time we actually output something : new serial for updated rules
      (updatedCrs, deletedCrs) <- detectUpdatesAndIncrementRuleSerial(sanitizedNodeConfig.values.toSeq, nodeConfigCaches, directiveLib, allRules.map(x => (x.id, x)).toMap)?~! "Cannot detect the updates in the NodeConfiguration"
      uptodateSerialNodeconfig =  updateSerialNumber(sanitizedNodeConfig, updatedCrs.toMap)
      // Update the serial of ruleVals when there were modifications on Rules values
      // replace variables with what is really applied
      timeIncrementRuleSerial  =  (System.currentTimeMillis - beginTime)
      _                        =  logger.debug(s"Checked node configuration updates leading to rules serial number updates and serial number updated in ${timeIncrementRuleSerial}ms")

      writeTime           =  System.currentTimeMillis
      nodeConfigVersions  =  calculateNodeConfigVersions(uptodateSerialNodeconfig.values.toSeq)
      //second time we write something in repos: updated node configuration
      writtenNodeConfigs  <- writeNodeConfigurations(rootNodeId, uptodateSerialNodeconfig, nodeConfigVersions, nodeConfigCaches) ?~! "Cannot write configuration node"
      timeWriteNodeConfig =  (System.currentTimeMillis - writeTime)
      _                   =  logger.debug(s"Node configuration written in ${timeWriteNodeConfig}ms, start to update expected reports.")

      reportTime            =  System.currentTimeMillis
      // need to update this part as well
      updatedNodeConfig     =  writtenNodeConfigs.map( _.nodeInfo.id )
      expectedReports       <- setExpectedReports(ruleVals, sanitizedNodeConfig.values.toSeq, nodeConfigVersions, updatedCrs.toMap, deletedCrs, updatedNodeConfig, new DateTime())  ?~! "Cannot build expected reports"
      // now, invalidate cache
      _                     =  invalidateComplianceCache(updatedNodeConfig)
      timeSetExpectedReport =  (System.currentTimeMillis - reportTime)
      _                     =  logger.debug(s"Reports updated in ${timeSetExpectedReport}ms")

    } yield {
      logger.debug("Timing summary:")
      logger.debug("Fetch all information     : %10s ms".format(timeFetchAll))
      logger.debug("Historize names           : %10s ms".format(timeHistorize))
      logger.debug("Build current rule values : %10s ms".format(timeRuleVal))
      logger.debug("Build target configuration: %10s ms".format(timeBuildConfig))
      logger.debug("Update rule vals          : %10s ms".format(timeSanitize))
      logger.debug("Increment rule serials    : %10s ms".format(timeIncrementRuleSerial))
      logger.debug("Write node configurations : %10s ms".format(timeWriteNodeConfig))
      logger.debug("Save expected reports     : %10s ms".format(timeSetExpectedReport))

      writtenNodeConfigs.map( _.nodeInfo.id )
    }

    logger.debug("Policy generation completed in %d millisec".format((System.currentTimeMillis - initialTime)))
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
  def getGlobalComplianceMode(): Box[ComplianceMode]
  def getGlobalAgentRun() : Box[AgentRunInterval]
  def getAgentRunInterval    : () => Int
  def getAgentRunSplaytime   : () => Box[Int]
  def getAgentRunStartHour   : () => Box[Int]
  def getAgentRunStartMinute : () => Box[Int]

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
    , allInventories        : Map[NodeId, NodeInventory]
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
    , allNodeInfos : Map[NodeId, NodeInfo]
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
  def getNodeConfigurationCache(): Box[Map[NodeId, NodeConfigurationCache]]

  /**
   * Detect changes in the NodeConfiguration, to trigger an increment in the related CR
   * The CR are updated in the LDAP
   * Must have all the NodeConfiguration in nodes
   * Returns two seq : the updated rule, and the deleted rule
   */
  def detectUpdatesAndIncrementRuleSerial(nodes : Seq[NodeConfiguration], cache: Map[NodeId, NodeConfigurationCache], directiveLib: FullActiveTechniqueCategory, rules: Map[RuleId, Rule]) : Box[(Map[RuleId,Int], Seq[RuleId])]

  /**
   * Set all the serial number when needed (a change in CR)
   * Must have all the NodeConfiguration in nodes
   */
  def updateSerialNumber(nodes : Map[NodeId, NodeConfiguration], rules : Map[RuleId, Int]) :  Map[NodeId, NodeConfiguration]

  /**
   * Actually  write the new configuration for the list of given node.
   * If the node target configuration is the same as the actual, nothing is done.
   * Else, promises are generated;
   * Return the list of configuration successfully written.
   */
  def writeNodeConfigurations(rootNodeId: NodeId, allNodeConfig: Map[NodeId, NodeConfiguration], versions: Map[NodeId, NodeConfigId], cache: Map[NodeId, NodeConfigurationCache]) : Box[Set[NodeConfiguration]]


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
    , updatedNodeConfig: Set[NodeId]
    , generationTime   : DateTime
  ) : Box[Seq[RuleExpectedReports]]

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
    , globalInterval   : Int
    , globalSplaytime  : Int
    , globalStartHour  : Int
    , globalStartMinute: Int
  ) : Box[Unit]


  protected def computeNodeConfigIdFromCache(config: NodeConfigurationCache): NodeConfigId = {
    NodeConfigId(config.hashCode.toString)
  }
  def calculateNodeConfigVersions(configs: Seq[NodeConfiguration]): Map[NodeId, NodeConfigId] = {
    configs.map(x => (x.nodeInfo.id, computeNodeConfigIdFromCache(NodeConfigurationCache(x)))).toMap
  }
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
  , override val getAgentRunInterval: () => Int
  , override val getAgentRunSplaytime: () => Box[Int]
  , override val getAgentRunStartHour: () => Box[Int]
  , override val getAgentRunStartMinute: () => Box[Int]
) extends PromiseGenerationService with
  PromiseGeneration_performeIO with
  PromiseGeneration_buildRuleVals with
  PromiseGeneration_buildNodeConfigurations with
  PromiseGeneration_updateAndWriteRule with
  PromiseGeneration_setExpectedReports with
  PromiseGeneration_historization

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

  override def findDependantRules() : Box[Seq[Rule]] = roRuleRepo.getAll(true)
  override def getAllNodeInfos(): Box[Map[NodeId, NodeInfo]] = nodeInfoService.getAll
  override def getDirectiveLibrary(): Box[FullActiveTechniqueCategory] = roDirectiveRepository.getFullDirectiveLibrary()
  override def getGroupLibrary(): Box[FullNodeGroupCategory] = roNodeGroupRepository.getFullGroupLibrary()
  override def getAllGlobalParameters: Box[Seq[GlobalParameter]] = parameterService.getAllGlobalParameters()
  override def getAllInventories(): Box[Map[NodeId, NodeInventory]] = roInventoryRepository.getAllNodeInventories(AcceptedInventory)
  override def getGlobalComplianceMode(): Box[ComplianceMode] = complianceModeService.getComplianceMode
  override def getGlobalAgentRun(): Box[AgentRunInterval] = agentRunService.getGlobalAgentRun()
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
    , allInventories        : Map[NodeId, NodeInventory]
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
      sequence(parameters) { param =>
        for {
          p <- interpolatedValueCompiler.compile(param.value) ?~! s"Error when looking for interpolation variable in global parameter '${param.name}'"
        } yield {
          (param.name, p)
        }
      }.map( _.toMap)
    }


    for {
      globalSystemVariables <- systemVarService.getGlobalSystemVariables()
      parameters            <-  buildParams(globalParameters) ?~! "Can not parsed global parameter (looking for interpolated variables)"
    } yield {
      (nodeIds.flatMap { nodeId:NodeId =>
        (for {
          nodeInfo     <- Box(allNodeInfos.get(nodeId)) ?~! s"Node with ID ${nodeId.value} was not found"
          inventory    <- Box(allInventories.get(nodeId)) ?~! s"Inventory for node with ID ${nodeId.value} was not found"
          policyServer <- Box(allNodeInfos.get(nodeInfo.policyServerId)) ?~! s"Node with ID ${nodeId.value} was not found"

          nodeContext  <- systemVarService.getSystemVariables(nodeInfo, allNodeInfos, globalSystemVariables, globalAgentRun, globalComplianceMode  : ComplianceMode)
        } yield {
          (nodeId, InterpolationContext(
                        nodeInfo
                      , policyServer
                      , inventory
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
      rawRuleVals <- sequence(appliedRules) { rule => ruleValService.buildRuleVal(rule, directiveLib) } ?~! "Could not find configuration vals"
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
    , allNodeInfos : Map[NodeId, NodeInfo]
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

    //1.3: build node config, binding ${rudder.parameters} parameters

    val nodeConfigs = sequence(nodeContexts.toSeq) { case (nodeId, context) =>

      for {
        drafts <- Box(policyDraftByNode.get(nodeId)) ?~! "Promise generation algorithme error: cannot find back the configuration information for a node"
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
        parameters <- sequence(context.parameters.toSeq) { case (name, param) =>
                        for {
                          p <- param(context)
                        } yield {
                          (name, p)
                        }
                      }
        cf3PolicyDrafts <- sequence(drafts) { draft =>
                            //bind variables
                            draft.variableMap(context).map{ expandedVariables =>

                              Cf3PolicyDraft(
                                  id = Cf3PolicyDraftId(draft.ruleId, draft.directiveId)
                                , technique = draft.technique
                                , variableMap = expandedVariables
                                , trackerVariable = draft.trackerVariable
                                , priority = draft.priority
                                , serial = draft.serial
                                , ruleOrder = draft.ruleOrder
                                , directiveOrder = draft.directiveOrder
                                , overrides = Set()
                              )
                            }
                          }
      } yield {
        NodeConfiguration(
            nodeInfo = context.nodeInfo
          , policyDrafts = cf3PolicyDrafts.toSet
          , nodeContext = context.nodeContext
          , parameters = parameters.map { case (k,v) => ParameterForConfiguration(k, v) }.toSet
          , isRootServer = context.nodeInfo.id == context.policyServerInfo.id
        )
      }
    }

    nodeConfigs
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

  def getNodeConfigurationCache(): Box[Map[NodeId, NodeConfigurationCache]] = nodeConfigurationService.getNodeConfigurationCache()


  /**
   * Detect changes in rules and update their serial
   * Returns two seq : the updated rules, and the deleted rules
   */
  def detectUpdatesAndIncrementRuleSerial(
      nodes       : Seq[NodeConfiguration]
    , cache       : Map[NodeId, NodeConfigurationCache]
    , directiveLib: FullActiveTechniqueCategory
    , allRules    : Map[RuleId, Rule]
  ) : Box[(Map[RuleId,Int], Seq[RuleId])] = {
    val firstElt = (Map[RuleId,Int](), Seq[RuleId]())
    // First, fetch the updated CRs (which are either updated or deleted)
    val res = (( Full(firstElt) )/:(nodeConfigurationService.detectChangeInNodes(nodes, cache, directiveLib)) ) { case (Full((updated, deleted)), ruleId) => {
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
   * Actually  write the new configuration for the list of given node.
   * If the node target configuration is the same as the actual, nothing is done.
   * Else, promises are generated;
   * Return the list of configuration successfully written.
   */
  def writeNodeConfigurations(rootNodeId: NodeId, allNodeConfigs: Map[NodeId, NodeConfiguration], versions: Map[NodeId, NodeConfigId], cache: Map[NodeId, NodeConfigurationCache]) : Box[Set[NodeConfiguration]] = {
    /*
     * Several steps heres:
     * - look what node configuration are updated (based on their cache ?)
     * - write these node configuration
     * - update caches
     */
    val updated = nodeConfigurationService.selectUpdatedNodeConfiguration(allNodeConfigs, cache)

    ComplianceDebugLogger.debug(s"Updated node configuration ids: ${updated.map {id =>
      s"[${id.value}:${cache.get(id).fold("???")(x => computeNodeConfigIdFromCache(x).value)}->${computeNodeConfigIdFromCache(NodeConfigurationCache(allNodeConfigs(id))).value}]"
    }.mkString("") }")

    val writtingTime = Some(DateTime.now)
    val fsWrite0   =  writtingTime.get.getMillis

    for {
      written    <- promisesFileWriterService.writeTemplate(rootNodeId, updated, allNodeConfigs, versions)
      ldapWrite0 =  DateTime.now.getMillis
      fsWrite1   =  (ldapWrite0 - fsWrite0)
      _          =  logger.debug(s"Node configuration written on filesystem in ${fsWrite1} millisec.")
      //before caching, update the timestamp for last written time
      toCache    =  allNodeConfigs.filterKeys(updated.contains(_)).values.toSet.map( (x:NodeConfiguration) => x.copy(writtenDate = writtingTime))
      cached     <- nodeConfigurationService.cacheNodeConfiguration(toCache)
      ldapWrite1 =  (DateTime.now.getMillis - ldapWrite0)
      _          =  logger.debug(s"Node configuration cached in LDAP in ${ldapWrite1} millisec.")
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
    , versions         : Map[NodeId, NodeConfigId]
    , updatedCrs       : Map[RuleId, Int]
    , deletedCrs       : Seq[RuleId]
    , updatedNodeConfig: Set[NodeId]
    , generationTime   : DateTime
  ) : Box[Seq[RuleExpectedReports]] = {
    val expandedRuleVal = getExpandedRuleVal(ruleVal, configs, versions)
    val updatedRuleVal = updateRuleVal(expandedRuleVal, updatedCrs)
    val updatedConfigIds = updatedNodeConfig.flatMap(id =>
      //we should have all the nodeConfig for the nodeIds, but if it isn't
      //the case, it seems safer to not try to save a new version of the nodeConfigId
      //for that node and just ignore it.
      configs.find( _.nodeInfo.id == id).map { x =>
        (x.nodeInfo.id, versions(x.nodeInfo.id))
      }
    ).toMap


    //we also want to build the list of overriden directive based on unique techniques.
    val overriden = configs.flatMap { nodeConfig =>
      nodeConfig.policyDrafts.flatMap( x => x.overrides.map { case (ruleId, directiveId) =>
        UniqueOverrides(nodeConfig.nodeInfo.id, ruleId, directiveId, x.id)
      })
    }.toSet

    reportingService.updateExpectedReports(updatedRuleVal, deletedCrs, updatedConfigIds, generationTime, overriden)
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
      , globalInterval   : Int
      , globalSplaytime  : Int
      , globalStartHour  : Int
      , globalStartMinute: Int
    ) : Box[Unit] = {
    for {
      _ <- historizationService.updateNodes(allNodeInfos.values.toSet)
      _ <- historizationService.updateGroups(groupLib)
      _ <- historizationService.updateDirectiveNames(directiveLib)
      _ <- historizationService.updatesRuleNames(rules)
      _ <- historizationService.updateGlobalSchedule(globalInterval, globalSplaytime, globalStartHour, globalStartMinute)
    } yield {
      () // unit is expected
    }
  }



}

