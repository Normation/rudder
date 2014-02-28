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
import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.domain.Variable
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.Constants
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.domain.parameters.GlobalParameter
import com.normation.rudder.domain.parameters.Parameter
import com.normation.rudder.domain.parameters.ParameterName
import com.normation.rudder.domain.policies.AppliedStatus
import com.normation.rudder.domain.policies.ExpandedRuleVal
import com.normation.rudder.domain.policies.PolicyDraft
import com.normation.rudder.domain.policies.Rule
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.RuleVal
import com.normation.rudder.domain.reports.RuleExpectedReports
import com.normation.rudder.repository.FullActiveTechniqueCategory
import com.normation.rudder.repository.FullNodeGroupCategory
import com.normation.rudder.repository.RoDirectiveRepository
import com.normation.rudder.repository.RoNodeGroupRepository
import com.normation.rudder.repository.RoRuleRepository
import com.normation.rudder.repository.WoRuleRepository
import com.normation.rudder.services.eventlog.HistorizationService
import com.normation.rudder.services.nodes.NodeInfoService
import com.normation.rudder.services.policies.nodeconfig.NodeConfigurationService
import com.normation.rudder.services.reports.ReportingService
import com.normation.utils.Control._
import com.normation.utils.HashcodeCaching
import net.liftweb.common._
import com.normation.rudder.services.policies.nodeconfig.ParameterForConfiguration
import com.normation.rudder.services.policies.nodeconfig.NodeConfiguration
import com.normation.rudder.services.policies.nodeconfig.NodeConfigurationCache




/**
 * The main service which deploy modified rules and
 * their dependencies.
 */
trait DeploymentService extends Loggable {

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

      allRules        <- findDependantRules() ?~! "Could not find dependant rules"
      allRulesMap     =  allRules.map(x => (x.id, x)).toMap
      //a version of the rule map, with id in lower case for parameter search
      lowerIdRulesMap =  allRules.map(x => (RuleId(x.id.value.toLowerCase) , x)).toMap
      allNodeInfos    <- getAllNodeInfos ?~! "Could not get Node Infos"
      directiveLib    <- getDirectiveLibrary() ?~! "Could not get the directive library"
      groupLib        <- getGroupLibrary() ?~! "Could not get the group library"
      allParameters   <- getAllGlobalParameters ?~! "Could not get global parameters"
      timeFetchAll    =  (System.currentTimeMillis - initialTime)
      _               =  logger.debug(s"All relevant information fetched in ${timeFetchAll}ms, start names historization.")

      historizeTime =  System.currentTimeMillis
      historize     <- historizeData(allRules, directiveLib, groupLib, allNodeInfos)
      timeHistorize =  (System.currentTimeMillis - historizeTime)
      _             =  logger.debug(s"Historization of names done in ${timeHistorize}ms, start to build rule values.")

      ruleValTime   =  System.currentTimeMillis
      rawRuleVals <- buildRuleVals(allRules, directiveLib, groupLib, allNodeInfos) ?~! "Cannot build Rule vals"
      timeRuleVal   =  (System.currentTimeMillis - ruleValTime)
      _           =  logger.debug(s"RuleVals built in ${timeRuleVal}ms, start to expand their values.")

      expandRuleTime =  System.currentTimeMillis
      ruleVals       <- expandRuleVal(rawRuleVals, allNodeInfos, groupLib, directiveLib, lowerIdRulesMap) ?~! "Cannot expand Rule vals values"
      timeExpandRule =  (System.currentTimeMillis - expandRuleTime)
      _              =  logger.debug(s"RuleVals expanded in ${timeExpandRule}ms, start to build new node configurations.")

      buildConfigTime  =  System.currentTimeMillis
      (config, rules) <- buildNodeConfigurations(ruleVals, allNodeInfos, groupLib, directiveLib, lowerIdRulesMap, allParameters) ?~! "Cannot build target configuration node"
      timeBuildConfig =  (System.currentTimeMillis - buildConfigTime)
      _               =  logger.debug(s"Node's target configuration built in ${timeBuildConfig}, start to update rule values.")

      sanitizeTime        =  System.currentTimeMillis
      _                   <- forgetOtherNodeConfigurationState(config.map(_.nodeInfo.id).toSet) ?~! "Cannot clean the configuration cache"
      sanitizedNodeConfig <- sanitize(config) ?~! "Cannot set target configuration node"
      timeSanitize        =  (System.currentTimeMillis - sanitizeTime)
      _                   =  logger.debug(s"RuleVals updated in ${timeSanitize} millisec, start to detect changes in node configuration.")

      beginTime                =  System.currentTimeMillis
      //that's the first time we actually write something in repos: new serial for updated rules


      nodeConfigCache          <- getNodeConfigurationCache() ?~! "Cannot get the Configuration Cache"
      (updatedCrs, deletedCrs) <- detectUpdatesAndIncrementRuleSerial(sanitizedNodeConfig.values.toSeq, nodeConfigCache, directiveLib, allRulesMap)?~! "Cannot detect the updates in the NodeConfiguration"
      serialedNodes            =  updateSerialNumber(sanitizedNodeConfig, updatedCrs.toMap)
      // Update the serial of ruleVals when there were modifications on Rules values
      // replace variables with what is really applied
      updatedRuleVals          =  updateRuleVal(rules, updatedCrs)
      timeIncrementRuleSerial  =  (System.currentTimeMillis - beginTime)
      _                        = logger.debug(s"Checked node configuration updates leading to rules serial number updates and serial number updated in ${timeIncrementRuleSerial}ms")

      writeTime = System.currentTimeMillis
      //second time we write something in repos: updated node configuration
      writtenNodeConfigs <- writeNodeConfigurations(rootNodeId, serialedNodes, nodeConfigCache) ?~! "Cannot write configuration node"
      timeWriteNodeConfig = (System.currentTimeMillis - writeTime)
      _ = logger.debug(s"Node configuration written in ${timeWriteNodeConfig}ms, start to update expected reports.")

      reportTime = System.currentTimeMillis
      // need to update this part as well
      expectedReports <- setExpectedReports(updatedRuleVals, deletedCrs)  ?~! "Cannot build expected reports"
      timeSetExpectedReport = (System.currentTimeMillis - reportTime)
      _ = logger.debug(s"Reports updated in ${timeSetExpectedReport}ms")

    } yield {
      logger.debug("Timing summary:")
      logger.debug("Fetch all information     : %10s ms".format(timeFetchAll))
      logger.debug("Historize names           : %10s ms".format(timeHistorize))
      logger.debug("Build current rule values : %10s ms".format(timeRuleVal))
      logger.debug("Expand rule parameters    : %10s ms".format(timeExpandRule))
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
  def getAllNodeInfos(): Box[Set[NodeInfo]]
  def getDirectiveLibrary(): Box[FullActiveTechniqueCategory]
  def getGroupLibrary(): Box[FullNodeGroupCategory]
  def getAllGlobalParameters: Box[Seq[GlobalParameter]]

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
   * Build the list of "CFclerkRuleVal" from a list of
   * rules.
   * These objects are a cache of all rules
   */
  def buildRuleVals(rules:Seq[Rule], directiveLib: FullActiveTechniqueCategory, groupLib: FullNodeGroupCategory, allNodeInfos: Set[NodeInfo]) : Box[Seq[RuleVal]]

  /**
   * Expand the ${rudder.confRule.varName} in ruleVals
   */
   def expandRuleVal(rawRuleVals:Seq[RuleVal], allNodeInfos: Set[NodeInfo], groupLib: FullNodeGroupCategory, directiveLib: FullActiveTechniqueCategory, rules: Map[RuleId, Rule]) : Box[Seq[RuleVal]]

  /**
   * From a list of ruleVal, find the list of all impacted nodes
   * with the actual Cf3PolicyDraftBean they will have.
   * Replace all ${node.varName} vars.
   */
  def buildNodeConfigurations(
      ruleVals:Seq[RuleVal]
    , allNodeInfos: Set[NodeInfo]
    , groupLib: FullNodeGroupCategory
    , directiveLib: FullActiveTechniqueCategory
    , allRulesCaseInsensitive: Map[RuleId, Rule]
    , parameters: Seq[GlobalParameter]
  ) : Box[(Seq[NodeConfiguration], Seq[ExpandedRuleVal])]

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
  def detectUpdatesAndIncrementRuleSerial(nodes : Seq[NodeConfiguration], cache: Map[NodeId, NodeConfigurationCache], directiveLib: FullActiveTechniqueCategory, rules: Map[RuleId, Rule]) : Box[(Seq[(RuleId,Int)], Seq[RuleId])]

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
  def writeNodeConfigurations(rootNodeId: NodeId, allNodeConfig: Map[NodeId, NodeConfiguration], cache: Map[NodeId, NodeConfigurationCache]) : Box[Set[NodeConfiguration]]


  /**
   * Update the serials in the rule vals based on the updated rule
   * Goal : actually set the right serial in them, to have an easy setExpectedReports
   */
  def updateRuleVal(ruleVal : Seq[ExpandedRuleVal], rules : Seq[(RuleId,Int)]) : Seq[ExpandedRuleVal]

  /**
   * Set the exepcted reports for the rule
   * Caution : we can't handle deletion with this
   * @param ruleVal
   * @return
   */
  def setExpectedReports(ruleVal : Seq[ExpandedRuleVal], deletedCrs : Seq[RuleId]) : Box[Seq[RuleExpectedReports]]

  /**
   * Store groups and directive in the database
   */
  def historizeData(rules:Seq[Rule], directiveLib: FullActiveTechniqueCategory, groupLib: FullNodeGroupCategory, allNodeInfos: Set[NodeInfo]) : Box[Unit]

}

///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//  Implémentation
///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

class DeploymentServiceImpl (
    override val roRuleRepo: RoRuleRepository
  , override val woRuleRepo: WoRuleRepository
  , override val ruleValService : RuleValService
  , override val parameterizedValueLookupService : ParameterizedValueLookupService
  , override val systemVarService: SystemVariableService
  , override val nodeConfigurationService : NodeConfigurationService
  , override val nodeInfoService : NodeInfoService
  , override val reportingService : ReportingService
  , override val historizationService : HistorizationService
  , override val roNodeGroupRepository: RoNodeGroupRepository
  , override val roDirectiveRepository: RoDirectiveRepository
  , override val ruleApplicationStatusService: RuleApplicationStatusService
  , override val parameterService : RoParameterService
) extends DeploymentService with
  DeploymentService_findDependantRules_bruteForce with
  DeploymentService_buildRuleVals with
  DeploymentService_buildNodeConfigurations with
  DeploymentService_updateAndWriteRule with
  DeploymentService_setExpectedReports with
  DeploymentService_historization
{}


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
trait DeploymentService_findDependantRules_bruteForce extends DeploymentService {
  def roRuleRepo : RoRuleRepository
  def nodeInfoService: NodeInfoService
  def roNodeGroupRepository: RoNodeGroupRepository
  def roDirectiveRepository: RoDirectiveRepository
  def parameterService : RoParameterService

  override def findDependantRules() : Box[Seq[Rule]] = roRuleRepo.getAll(true)
  override def getAllNodeInfos(): Box[Set[NodeInfo]] = nodeInfoService.getAll
  override def getDirectiveLibrary(): Box[FullActiveTechniqueCategory] = roDirectiveRepository.getFullDirectiveLibrary()
  override def getGroupLibrary(): Box[FullNodeGroupCategory] = roNodeGroupRepository.getFullGroupLibrary()
  override def getAllGlobalParameters: Box[Seq[GlobalParameter]] = parameterService.getAllGlobalParameters()
}

///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

trait DeploymentService_buildRuleVals extends DeploymentService {

  def ruleApplicationStatusService: RuleApplicationStatusService
  def ruleValService : RuleValService
  def parameterizedValueLookupService : ParameterizedValueLookupService


  /**
   * Build the list of "CFclerkRuleVal" from a list of
   * rules.
   * These objects are a cache of all rules
   */
  override def buildRuleVals(rules:Seq[Rule], directiveLib: FullActiveTechniqueCategory, groupLib: FullNodeGroupCategory, allNodeInfos: Set[NodeInfo]) : Box[Seq[RuleVal]] = {
    val appliedRules = rules.filter(r => ruleApplicationStatusService.isApplied(r, groupLib, directiveLib, allNodeInfos) match {
      case _:AppliedStatus => true
      case _ => false
    })

    for {
      rawRuleVals <- sequence(appliedRules) { rule =>
                       ruleValService.buildRuleVal(rule, directiveLib)
                     } ?~! "Could not find configuration vals"
    } yield rawRuleVals
  }

  /**
   * Expand the ${rudder.confRule.varName} in ruleVals
   */
   override def expandRuleVal(rawRuleVals:Seq[RuleVal], allNodeInfos: Set[NodeInfo], groupLib: FullNodeGroupCategory, directiveLib: FullActiveTechniqueCategory, allRulesCaseInsensitive: Map[RuleId, Rule]) : Box[Seq[RuleVal]] = {
     for {
       replacedConfigurationVals <- replaceVariable(rawRuleVals, allNodeInfos, groupLib, directiveLib, allRulesCaseInsensitive) ?~! "Could not replace variables"
     } yield replacedConfigurationVals
   }

   /**
    *
    * Replace all variable of the for ${rudder.ruleId.varName} by the seq of values for
    * the varName in RuleVal with id ruleId.
    *
    *
    * @param rules
    * @return
    */
   private[this] def replaceVariable(ruleVals:Seq[RuleVal], allNodeInfos: Set[NodeInfo], groupLib: FullNodeGroupCategory, directiveLib: FullActiveTechniqueCategory, allRulesCaseInsensitive: Map[RuleId, Rule]) : Box[Seq[RuleVal]] = {
     sequence(ruleVals) { crv => {
       for {
         updatedPolicies <- { sequence(crv.directiveVals) {
           policy =>
             for {
               replacedVariables <- parameterizedValueLookupService.lookupRuleParameterization(policy.variables.values.toSeq, allNodeInfos, groupLib, directiveLib, allRulesCaseInsensitive) ?~! (
                       s"Error when processing rule with id: ${crv.ruleId.value} (variables for ${policy.technique.id.name.value}/${policy.technique.id.version.toString}/${policy.directiveId.value}) "
                     + s"with variables: ${policy.variables.values.toSeq.map(v => s"${v.spec.name}:${v.values.map( _.take(20)).mkString("; ")}").mkString("[","][","]")}"
                   )
             } yield {
               policy.copy(variables = replacedVariables.map(v => (v.spec.name -> v)).toMap)
             }
           }
         }
       } yield {
         crv.copy(directiveVals = updatedPolicies)
       }
     }
     }
   }


   /**
   * Update the serials in the rule vals based on the updated rule (which may be empty if nothing is updated)
   * Goal : actually set the right serial in them, as well as the correct variable
   * So we can have several rule with different subset of values
   */
  def updateRuleVal(
      rulesVal : Seq[ExpandedRuleVal]
    , rules : Seq[(RuleId,Int)]
  ) : Seq[ExpandedRuleVal] = {
    rulesVal.map(ruleVal => {
      rules.find { case(id,serial) => id == ruleVal.ruleId } match {
        case Some((id,serial)) =>
          ruleVal.copy(serial = serial)
        case _ => ruleVal
      }
    })
  }
}

///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

trait DeploymentService_buildNodeConfigurations extends DeploymentService with Loggable {
  def systemVarService: SystemVariableService
  def parameterizedValueLookupService : ParameterizedValueLookupService
  def roNodeGroupRepository: RoNodeGroupRepository

  /**
   * This object allows to construct the target node configuration
   */
  private[this] case class MutableNodeConfiguration(
        nodeInfo:NodeInfo,
        //environment variable for that server
        nodeContext: Map[String, Variable],
        // parameters for this node
        parameters : Set[ParameterForConfiguration]
      , isRoot            : Boolean = false
      , writtenDate       : Option[DateTime] = None
  ) {
    val identifiablePolicyDrafts = scala.collection.mutable.Buffer[PolicyDraft]()

    def immutable = NodeConfiguration(nodeInfo, identifiablePolicyDrafts.map(_.toRuleWithCf3PolicyDraft).toSet, nodeContext, parameters, writtenDate, isRoot)
  }


  /**
   * From a list of ruleVal, find the list of all impacted nodes
   * with the actual Cf3PolicyDraftBean they will have.
   * Replace all ${rudder.node.varName} vars, returns the nodes ready to be configured, and expanded RuleVal
   * allNodeInfos *must* contains the nodes info of every nodes
   */
  override def buildNodeConfigurations(
      ruleVals:Seq[RuleVal]
    , allNodeInfos: Set[NodeInfo]
    , groupLib: FullNodeGroupCategory
    , directiveLib: FullActiveTechniqueCategory
    , allRulesCaseInsensitive: Map[RuleId, Rule]
    , parameters: Seq[GlobalParameter]
  ) : Box[(Seq[NodeConfiguration], Seq[ExpandedRuleVal])] = {
    val targetNodeConfigMap = scala.collection.mutable.Map[NodeId, MutableNodeConfiguration]()

    ruleVals.foreach { ruleVal =>

      val wantedNodeIds = groupLib.getNodeIds(ruleVal.targets, allNodeInfos)

      val nodeIds = wantedNodeIds.intersect(allNodeInfos.map( _.id ))
      if(nodeIds.size != wantedNodeIds.size) {
        logger.error(s"Some nodes are in the target of rule ${ruleVal.ruleId.value} but are not present " +
            s"in the system. It looks like an inconsistency error. Ignored nodes: ${(wantedNodeIds -- nodeIds).map( _.value).mkString(", ")}")
      }

      nodeIds.foreach { nodeId =>
        targetNodeConfigMap.get(nodeId) match {
          case None => //init nodeConfig for that id
            (for {
              nodeInfo <- Box(allNodeInfos.find( _.id == nodeId)) ?~! s"Node with ID ${nodeId.value} was not found"
              nodeContext <- systemVarService.getSystemVariables(nodeInfo, allNodeInfos, groupLib, directiveLib, allRulesCaseInsensitive)
            } yield {
              val nodeConfig = MutableNodeConfiguration(
                                   nodeInfo
                                 , nodeContext.toMap
                                 , parameters.map(ParameterForConfiguration.fromParameter(_)).toSet
                               )
              nodeConfig.identifiablePolicyDrafts ++= ruleVal.toPolicyDrafts

              nodeConfig
            }) match {
              case eb:EmptyBox =>
                val e = eb ?~! s"Error while building target configuration node for node ${nodeId.value} which is one of the target of rule ${ruleVal.ruleId.value}."
                logger.debug(e.messageChain)
                return e
              case Full(nodeConfig) => targetNodeConfigMap(nodeConfig.nodeInfo.id) = nodeConfig
            }

          case Some(nodeConfig) => //add DirectiveVal to the list of policies for that node
               nodeConfig.identifiablePolicyDrafts ++= ruleVal.toPolicyDrafts
        }
      }
    }

    val duplicates = checkDuplicateTechniquesVersion(targetNodeConfigMap.toMap)
    if(duplicates.isEmpty) {
      //replace variable of the form ${rudder.node.XXX} in both context and variable beans
      val nodes = sequence(targetNodeConfigMap.values.toSeq) { x =>
        replaceNodeVars(x, allNodeInfos)
      }

      for {
        nodeConfigs <- nodes
      } yield {
        (nodeConfigs.map { _.immutable}, getExpandedRuleVal(ruleVals, nodeConfigs))
      }

    } else {
      Failure("There are directives based on techniques with different versions applied to the same node, please correct the version for the following directive(s): %s".format(duplicates.mkString(", ")))
    }
  }


  private[this] def getExpandedRuleVal(ruleVals:Seq[RuleVal], nodeConfigs : Seq[MutableNodeConfiguration]) : Seq[ExpandedRuleVal]= {
    ruleVals map { rule =>
      ExpandedRuleVal(
          rule.ruleId
        , nodeConfigs.flatMap { nodeConfig =>
              nodeConfig.identifiablePolicyDrafts.filter( x => x.ruleId == rule.ruleId) match {
                case drafts if drafts.size > 0 => Some((nodeConfig.nodeInfo.id -> drafts.map(_.toDirectiveVal)))
                case _ => None
              }
          }.toMap
        , rule.serial
      )
    }
  }
  /**
   * Check is there are nodes with directives based on two separate version of technique.
   * An empty returned set means that everything is ok.
   */
  private[this] def checkDuplicateTechniquesVersion(nodesConfigs : Map[NodeId, MutableNodeConfiguration]) : Set[TechniqueName] = {
    nodesConfigs.values.flatMap { config =>
      // Group the CFCPI of a node by technique name
      val group = config.identifiablePolicyDrafts.groupBy(x => x.technique.id.name)
      // Filter this grouping by technique having two different version
      group.filter(x => x._2.groupBy(x => x.technique.id.version).size > 1).map(x => x._1)
    }.toSet
  }


  /**
   * Replace variables in a node configuration
   */
  private[this] def replaceNodeVars(targetNodeConfig:MutableNodeConfiguration, allNodeInfos: Set[NodeInfo]) : Box[MutableNodeConfiguration] = {
    val nodeId = targetNodeConfig.nodeInfo.id
    //replace in system vars
    def replaceNodeContext() : Box[Map[String, Variable]] = {
      (sequence(targetNodeConfig.nodeContext.toSeq) { case (k, variable) =>
        for {
          replacedVar <- parameterizedValueLookupService.lookupNodeParameterization(nodeId, Seq(variable), targetNodeConfig.parameters, allNodeInfos)
        } yield {
          (k, replacedVar(0))
        }
      }).map( _.toMap)
    }

    /**
     * In a RuleWithCf3PolicyDraft, replace the parametrized node value by the fetched values
     */
    def replaceDirective(policy:PolicyDraft) : Box[PolicyDraft] = {
      ( for {
        variables <- Full(policy.variableMap.values.toSeq)
        replacedVars <- parameterizedValueLookupService.lookupNodeParameterization(nodeId, variables, targetNodeConfig.parameters, allNodeInfos)
      } yield {
        policy.copy(
            variableMap = Map[String, Variable]() ++ policy.variableMap ++ replacedVars.map(v => (v.spec.name, v) )
        )
      } ) match {
        case e:EmptyBox => e
        case Full(x) => Full(x)
      }
    }

    def identifyModifiedVariable(originalVars : Seq[Variable], replacedVars : Seq[Variable]) : Seq[Variable] = {
      replacedVars.map { variable =>
        { originalVars.filter( x => x.spec == variable.spec ) match {
          case seq if seq.size == 0 => None
          case seq if seq.size > 1 => logger.debug("too many replaced variable for one variable " + seq); None
          case seq =>
            val original = seq.head
            if (original.values == variable.values)
              Some(original)
	        else
	          None
	        }
        }
      }.flatten
    }

    for {
      replacedNodeContext <- replaceNodeContext()
      replacedDirective <- sequence(targetNodeConfig.identifiablePolicyDrafts) { case(pib) =>
        replaceDirective(pib)
      }
    } yield {
      val mutableNodeConfig = MutableNodeConfiguration(
          targetNodeConfig.nodeInfo,
          replacedNodeContext,
          targetNodeConfig.parameters
      )
      mutableNodeConfig.identifiablePolicyDrafts ++= replacedDirective
      mutableNodeConfig
    }

  }


}


///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

trait DeploymentService_updateAndWriteRule extends DeploymentService {

  def nodeConfigurationService : NodeConfigurationService


//  def roRuleRepo: RoRuleRepository

  def woRuleRepo: WoRuleRepository

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
  def detectUpdatesAndIncrementRuleSerial(nodes : Seq[NodeConfiguration], cache: Map[NodeId, NodeConfigurationCache], directiveLib: FullActiveTechniqueCategory, allRules: Map[RuleId, Rule]) : Box[(Seq[(RuleId,Int)], Seq[RuleId])] = {
    val firstElt = (Seq[(RuleId,Int)](), Seq[RuleId]())
    // First, fetch the updated CRs (which are either updated or deleted)
    (( Full(firstElt) )/:(nodeConfigurationService.detectChangeInNodes(nodes, cache, directiveLib)) ) { case (Full((updated, deleted)), ruleId) => {
      allRules.get(ruleId) match {
        case Some(rule) =>
          woRuleRepo.incrementSerial(rule.id) match {
            case Full(newSerial) =>
              logger.trace("Updating rule %s to serial %d".format(rule.id.value, newSerial))
              Full( (updated :+ (rule.id -> newSerial), deleted) )
            case f : EmptyBox =>
              //early stop
              return f
          }
        case None =>
          Full((updated, (deleted :+ ruleId)))
      }
    } }
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
  def writeNodeConfigurations(rootNodeId: NodeId, allNodeConfigs: Map[NodeId, NodeConfiguration], cache: Map[NodeId, NodeConfigurationCache]) : Box[Set[NodeConfiguration]] = {
    /*
     * Several steps heres:
     * - look what node configuration are updated (based on their cache ?)
     * - write these node configuration
     * - update caches
     */
    val updated = nodeConfigurationService.selectUpdatedNodeConfiguration(allNodeConfigs, cache)
    val fsWrite0   =  DateTime.now.getMillis

    for {
      written    <- nodeConfigurationService.writeTemplate(rootNodeId, updated, allNodeConfigs)
      ldapWrite0 =  DateTime.now.getMillis
      fsWrite1   =  (ldapWrite0 - fsWrite0)
      _          =  logger.debug(s"Node configuration written on filesystem in ${fsWrite1} millisec.")
      cached     <- nodeConfigurationService.cacheNodeConfiguration(allNodeConfigs.filterKeys(updated.contains(_)).values.toSet)
      ldapWrite1 =  (DateTime.now.getMillis - ldapWrite0)
      _          =  logger.debug(s"Node configuration cached in LDAP in ${ldapWrite1} millisec.")
    } yield {
      written.toSet
    }
  }

}

///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////


trait DeploymentService_setExpectedReports extends DeploymentService {
  def reportingService : ReportingService

  def setExpectedReports(ruleVal : Seq[ExpandedRuleVal], deletedCrs : Seq[RuleId]) : Box[Seq[RuleExpectedReports]] = {
    reportingService.updateExpectedReports(ruleVal, deletedCrs)
  }
}


trait DeploymentService_historization extends DeploymentService {
  def historizationService : HistorizationService

  def historizeData(rules:Seq[Rule], directiveLib: FullActiveTechniqueCategory, groupLib: FullNodeGroupCategory, allNodeInfos: Set[NodeInfo]) : Box[Unit] = {
    for {
      _ <- historizationService.updateNodes(allNodeInfos)
      _ <- historizationService.updateGroups(groupLib)
      _ <- historizationService.updateDirectiveNames(directiveLib)
      _ <- historizationService.updatesRuleNames(rules)
    } yield {
      () // unit is expected
    }
  }



}

