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

import com.normation.rudder.domain.reports.RuleExpectedReports
import com.normation.rudder.domain.servers.NodeConfiguration
import com.normation.rudder.services.nodes.NodeInfoService
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.cfclerk.domain.VariableSpec
import com.normation.cfclerk.domain.Variable
import com.normation.cfclerk.services.TechniqueRepository
import com.normation.rudder.repository.ActiveTechniqueRepository
import com.normation.rudder.repository.RuleRepository
import net.liftweb.common._
import com.normation.rudder.domain.policies._
import com.normation.inventory.domain.NodeId
import com.normation.utils.Control.sequence
import com.normation.rudder.services.nodes.NodeInfoService
import com.normation.rudder.services.servers.NodeConfigurationService
import com.normation.rudder.services.reports.ReportingService
import com.normation.cfclerk.domain.Cf3PolicyDraft
import com.normation.rudder.services.servers.NodeConfigurationChangeDetectService
import org.joda.time.DateTime
import com.normation.rudder.repository.jdbc.HistorizationJdbcRepository
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.services.eventlog.HistorizationService
import com.normation.utils.HashcodeCaching

/**
 * TODO: ca devrait être un "target node configuration", ie
 * tout ce qui va changer dans le node configuration
 *
 */
case class targetNodeConfiguration(
    nodeInfo:NodeInfo,
    identifiableCFCPIs: Seq[RuleWithCf3PolicyDraft],
    //environment variable for that server
    nodeContext:Map[String, Variable]
) extends HashcodeCaching 



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
  def deploy() : Box[Seq[NodeConfiguration]] = {
    val initialTime = DateTime.now().getMillis
    val result = for {
      rules <- findDependantRules ?~! "Could not find dependant rules"
      val log1 = logger.debug("rules dependency solved in %d millisec, start to build RuleVals".format((DateTime.now().getMillis - initialTime)))
      
      val historizeTime = DateTime.now().getMillis
      
      val historize = historizeData()
      val log1_5 = logger.debug("Historization of name done in %d millisec".format((DateTime.now().getMillis - historizeTime)))
      
      val crValTime = DateTime.now().getMillis
      ruleVals <- buildRuleVals(rules) ?~! "Cannot build Rule vals"
      val log2 = logger.debug("RuleVals built in %d millisec, start to build targetNodeConfiguration".format((DateTime.now().getMillis - crValTime)))

      val targetNodeTime = DateTime.now().getMillis
      targetNodeConfigurations <- buildtargetNodeConfigurations(ruleVals) ?~! "Cannot build target configuration node"
      val log3 = logger.debug("targetNodeConfiguration built in %d millisec, start to update whose needed to be updated.".format((DateTime.now().getMillis - targetNodeTime)))

      val updateConfNodeTime = DateTime.now().getMillis
      updatedNodeConfigs <- updatetargetNodeConfigurations(targetNodeConfigurations) ?~! "Cannot set target configuration node"
      val log4 = logger.debug("RuleVals updated in %d millisec, detect changes.".format((DateTime.now().getMillis - updateConfNodeTime)))
      
      val beginTime = DateTime.now().getMillis
      
      (updatedCrs, deletedCrs) <- detectUpdates(updatedNodeConfigs)?~! "Cannot detect the updates in the NodeConfiguration"
      val log6 = logger.debug("Detected the changes in the NodeConfiguration to trigger change in CR in %d millisec. Update the SN in the nodes".format((DateTime.now().getMillis - beginTime)))
      
      val updateTime = DateTime.now().getMillis
      
      serialedNodes <- updateSerialNumber(updatedNodeConfigs, updatedCrs) ?~! "Cannot update the serial number of the CR in the nodes"
      val log7 = logger.debug("Serial number updated in the nodes in %d millisec. Update information in crval.".format((DateTime.now().getMillis - updateTime)))
      
      val updateTime2 = DateTime.now().getMillis
      
      updatedRuleVals <- updateRuleVal(ruleVals, updatedCrs) ?~! "Cannot update the serials in the CRVal" 
      val log8 = logger.debug("Updated serial in crval in %d millisec. Write promisses.".format((DateTime.now().getMillis - updateTime2)))
      
      val writeTime = DateTime.now().getMillis
      
      writtenNodeConfigs <- writeNodeConfigurations(serialedNodes.map(config => NodeId( config.id ) )) ?~! "Cannot write  configuration node"
      val log9 = logger.debug("rules deployed in %d millisec, process report information".format((DateTime.now().getMillis - writeTime)))
      
      val reportTime = DateTime.now().getMillis
      expectedReports <- setExpectedReports(updatedRuleVals, deletedCrs)  ?~! "Cannot build expected reports"
      val log10 =logger.debug("Reports updated in %d millisec".format((DateTime.now().getMillis - reportTime)))
      
    } yield writtenNodeConfigs
    logger.debug("Deployment completed in %d millisec".format((DateTime.now().getMillis - initialTime)))
    result
  }
  
  
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
   * These objects are a cache of all rules, with for
   * each of them the ${confRule.varName} replaced
   */
   def buildRuleVals(rules:Seq[Rule]) : Box[Seq[RuleVal]]
  
  /**
   * From a list of ruleVal, find the list of all impacted nodes
   * with the actual Cf3PolicyDraftBean they will have. 
   * Replace all ${node.varName} vars.
   */
  def buildtargetNodeConfigurations(ruleVals:Seq[RuleVal]) : Box[Seq[targetNodeConfiguration]]

  /**
   * For each CFCNodeConfiguration, look if the target node is already configured or
   * will be modified. 
   * For each modified node, set its target objects to CFCNodeConfiguration.
   * Return the actually modified nodes.
   */
  def updatetargetNodeConfigurations(configurations:Seq[targetNodeConfiguration]) : Box[Seq[NodeConfiguration]]
  
  
  
  /**
   * Detect changes in the NodeConfiguration, to trigger an increment in the related CR
   * The CR are updated in the LDAP
   * Must have all the NodeConfiguration in nodes
   * Returns two seq : the updated rule, and the deleted rule 
   */
  def detectUpdates(nodes : Seq[NodeConfiguration]) : Box[(Seq[(RuleId,Int)], Seq[RuleId])]  
  
  /**
   * Set all the serial number when needed (a change in CR)
   * Must have all the NodeConfiguration in nodes
   */
  def updateSerialNumber(nodes : Seq[NodeConfiguration], rules : Seq[(RuleId,Int)]) :  Box[Seq[NodeConfiguration]]
  
  /**
   * Actually  write the new configuration for the list of given node.
   * If the node target configuration is the same as the actual, nothing is done.
   * Else, promises are generated; 
   * Return the list of configuration successfully written. 
   */
  def writeNodeConfigurations(nodeConfigurations:Seq[NodeId]) : Box[Seq[NodeConfiguration]]
  
  
  /**
   * Update the serials in the rule vals based on the updated rule
   * Goal : actually set the right serial in them, to have an easy setExpectedReports
   */
  def updateRuleVal(ruleVal : Seq[RuleVal], rules : Seq[(RuleId,Int)]) : Box[Seq[RuleVal]]
  
  /**
   * Set the exepcted reports for the rule
   * Caution : we can't handle deletion with this
   * @param ruleVal
   * @return
   */
  def setExpectedReports(ruleVal : Seq[RuleVal], deletedCrs : Seq[RuleId]) : Box[Seq[RuleExpectedReports]]
  
  /**
   * Store groups and directive in the database
   */
  def historizeData() : Unit

}

///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//  Implémentation
///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

class DeploymentServiceImpl (
    override val ruleRepo: RuleRepository,
    override val ruleValService : RuleValService,
    override val parameterizedValueLookupService : ParameterizedValueLookupService,
    override val systemVarService: SystemVariableService,
    override val targetToNodeService : RuleTargetService,
    override val nodeConfigurationService : NodeConfigurationService,
    override val nodeInfoService : NodeInfoService,
    override val nodeConfigurationChangeDetectService : NodeConfigurationChangeDetectService,
    override val reportingService : ReportingService,
    override val historizationService : HistorizationService
) extends DeploymentService with
  DeploymentService_findDependantRules_bruteForce with
  DeploymentService_buildRuleVals with
  DeploymentService_buildtargetNodeConfigurations with
  DeploymentService_updateAndWriteRule with
  DeploymentService_setExpectedReports with
  DeploymentService_historization
{}


// TODO : make a cached version of all the repos like service 
// FOR THE DURATION of a deploy ONLY.
// That will be intersting, but its almost sur that that will avoid thousand of request to database.
  
  


///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
// Follows: traits implementing each part of the deployment service
///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

/**
 * So. There is a lot of "hidden" dependencies, 
 * so for now, we just return *ALL* rule.
 * 
 * It might not scale very well. 
 *
 */
trait DeploymentService_findDependantRules_bruteForce extends DeploymentService {
  def ruleRepo : RuleRepository
  
  override def findDependantRules() : Box[Seq[Rule]] = {
    ruleRepo.getAllEnabled
  }
}

///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

trait DeploymentService_buildRuleVals extends DeploymentService {
  

  def ruleValService : RuleValService
  def parameterizedValueLookupService : ParameterizedValueLookupService
  
  
  /**
   * Build the list of "CFclerkRuleVal" from a list of
   * rules. 
   * These objects are a cache of all rules, with for
   * each of them the ${confRule.varName} replaced
   */
   override def buildRuleVals(rules:Seq[Rule]) : Box[Seq[RuleVal]] = {
     for {
       rawRuleVals <- findRuleVals(rules) ?~! "Could not find configuration vals"
       replacedConfigurationVals <- replaceVariable(rawRuleVals) ?~! "Could not replace variables"
     } yield replacedConfigurationVals
   }
   
   /**
    * For each configuraiton rule, find its directive and policy package, and
    * store all variables / values
    * @param rules
    * @return
    */
   private[this] def findRuleVals(rules:Seq[Rule]) : Box[Seq[RuleVal]] = {
     sequence(rules) {  rule =>
       ruleValService.findRuleVal(rule.id)
     }
   }

   

   /**
    * 
    * Replace all variable of the for ${ruleId.varName} by the seq of values for
    * the varName in RuleVal with id ruleId.
    * 
    * Replacement rules are:
    * 
    * 
    * @param rules
    * @return
    */
   private[this] def replaceVariable(ruleVals:Seq[RuleVal]) : Box[Seq[RuleVal]] = {
     sequence(ruleVals) { crv => {
       for {
         updatedPolicies <- { sequence(crv.directiveVals) {
           policy => 
             for {
               replacedVariables <- parameterizedValueLookupService.lookupRuleParameterization(policy.variables.values.toSeq) ?~! 
                   "Error when processing rule (%s/%s:%s/%s) with variables: %s".format(
                       crv.ruleId.value, 
                       policy.techniqueId.name.value, 
                       policy.techniqueId.version.toString, 
                       policy.directiveId.value, 
                       policy.variables.values.toSeq
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
   * Goal : actually set the right serial in them, to have an easy setExpectedReports
   */
  def updateRuleVal(ruleVal : Seq[RuleVal], rules : Seq[(RuleId,Int)]) : Box[Seq[RuleVal]] = {
    Full(ruleVal.map(crVal => {
      rules.find { case(id,serial) => id == crVal.ruleId } match {
        case Some((id,serial)) => crVal.copy(serial = serial)
        case _ => crVal
      }
    }))
    
  }
  

   
}

///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

trait DeploymentService_buildtargetNodeConfigurations extends DeploymentService with Loggable {
  def systemVarService: SystemVariableService
  def targetToNodeService : RuleTargetService
  def nodeInfoService : NodeInfoService 
  def parameterizedValueLookupService : ParameterizedValueLookupService
  
  /**
   * This object allows to construct the target node configuration
   */
  private[this] case class MutabletargetNodeConfiguration(
        nodeInfo:NodeInfo,
        //environment variable for that server
        nodeContext: Map[String, Variable]
  ) {
    val identifiableCFCPIs = scala.collection.mutable.Buffer[RuleWithCf3PolicyDraft]()
    
    def immutable = targetNodeConfiguration(nodeInfo, identifiableCFCPIs, nodeContext)
  }
    
    
  /**
   * From a list of ruleVal, find the list of all impacted nodes
   * with the actual Cf3PolicyDraftBean they will have. 
   * Replace all ${node.varName} vars.
   */
  override def buildtargetNodeConfigurations(ruleVals:Seq[RuleVal]) : Box[Seq[targetNodeConfiguration]] = {
    val targetNodeConfigMap = scala.collection.mutable.Map[NodeId, MutabletargetNodeConfiguration]()
    
    ruleVals.foreach { crv =>
      targetToNodeService.getNodeIds(crv.target) match {
        case e:EmptyBox => return e
        case Full(nodeIds) => nodeIds.foreach { nodeId =>
          targetNodeConfigMap.get(nodeId) match {
            case None => //init nodeConfig for that id
              (for {
                nodeInfo <- nodeInfoService.getNodeInfo(nodeId)
                nodeContext <- systemVarService.getSystemVariables(nodeInfo)
              } yield {
                val nodeConfig = MutabletargetNodeConfiguration(nodeInfo, nodeContext.toMap)
                nodeConfig.identifiableCFCPIs ++= crv.toRuleWithCf3PolicyDraft
                nodeConfig
              }) match {
                case e:EmptyBox => logger.debug("Error while building taget conf node " + e);e
                case Full(nodeConfig) => targetNodeConfigMap(nodeConfig.nodeInfo.id) = nodeConfig
              }
            case Some(nodeConfig) => //add DirectiveVal to the list of policies for that node
                 nodeConfig.identifiableCFCPIs ++= crv.toRuleWithCf3PolicyDraft
          }
        }
      }    
    }
    
    //replace variable of the form ${node.XXX} in both context and variable beans
    val s = sequence(targetNodeConfigMap.values.toSeq) { x => 
      replaceNodeVars(x) 
    }
    s
  }


 
  /**
   * Replace variables in a node
   */
  private[this] def replaceNodeVars(targetNodeConfig:MutabletargetNodeConfiguration) : Box[targetNodeConfiguration] = {
    val nodeId = targetNodeConfig.nodeInfo.id 
    //replace in system vars
    def replaceNodeContext() : Box[Map[String, Variable]] = {
      (sequence(targetNodeConfig.nodeContext.toSeq) { case (k, variable) => 
        for {
          replacedVar <- parameterizedValueLookupService.lookupNodeParameterization(nodeId, Seq(variable))
        } yield {
          (k, replacedVar(0))
        }
      }).map( _.toMap)
    }
    
    /**
     * In a RuleWithCf3PolicyDraft, replace the parametrized node value by the fetched values
     */
    def replaceDirective(policy:RuleWithCf3PolicyDraft) : Box[RuleWithCf3PolicyDraft] = {
      ( for {
        variables <- Full(policy.cf3PolicyDraft.getVariables().values.toSeq)
        replacedVars <- parameterizedValueLookupService.lookupNodeParameterization(nodeId, variables)
      } yield {
        policy.copy(
            cf3PolicyDraft = new Cf3PolicyDraft(policy.cf3PolicyDraft.id,
                  policy.cf3PolicyDraft.techniqueId,
                  __variableMap = policy.cf3PolicyDraft.getVariables ++ replacedVars.map(v => (v.spec.name, v)),
                  policy.cf3PolicyDraft.trackerVariable,
                  priority = policy.cf3PolicyDraft.priority,
                  serial = policy.cf3PolicyDraft.serial)
            )
      } ) match {
        case e:EmptyBox => e
        case Full(x) => Full(x) 
      }
    }
    
    for {
      replacedNodeContext <- replaceNodeContext()
      replacedDirective <- sequence(targetNodeConfig.identifiableCFCPIs) { case(pib) =>
        replaceDirective(pib)
      }
    } yield {
      targetNodeConfiguration(
          nodeInfo = targetNodeConfig.nodeInfo,
          identifiableCFCPIs = replacedDirective,
          nodeContext = replacedNodeContext
      )
    }
  }

  
}


///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

trait DeploymentService_updateAndWriteRule extends DeploymentService {
  
  def nodeConfigurationService : NodeConfigurationService
  
  def nodeConfigurationChangeDetectService : NodeConfigurationChangeDetectService

  def ruleRepo: RuleRepository
  
   /**
   * For each CFCNodeConfiguration, look if the target node is already configured or
   * will be modified. 
   * For each modified node, set its target objects to CFCNodeConfiguration.
   * Return the actually modified nodes.
   */
  def updatetargetNodeConfigurations(configurations:Seq[targetNodeConfiguration]) : Box[Seq[NodeConfiguration]] = {
    for {
      rollback <- nodeConfigurationService.rollbackNodeConfigurations(nodeConfigurationService.getUpdatedNodeConfigurations.map( c => NodeId(c.id)))
      update <- sequence(configurations) { nodeConfig =>
        nodeConfigurationService.updateNodeConfiguration(nodeConfig)
      }
    } yield {
      update
    }
  }
  
  /**
   * Detect changes in rules and update their serial
   * Returns two seq : the updated rules, and the deleted rules
   */
  def detectUpdates(nodes : Seq[NodeConfiguration]) : Box[(Seq[(RuleId,Int)], Seq[RuleId])] = {
   // First, fetch the updated CRs (which are either updated or deleted)
   (( Full(Seq[(RuleId,Int)](), Seq[RuleId]()) )/:(nodeConfigurationChangeDetectService.detectChangeInNodes(nodes)) ) { case (Full((updated, deleted)), ruleId) => {
     ruleRepo.get(ruleId) match {
       case Full(rule) => 
         ruleRepo.incrementSerial(rule.id) match {
           case Full(newSerial) => logger.trace("Updating rule %s to serial %d".format(rule.id.value, newSerial))
                                   Full((updated :+ (rule.id,newSerial), deleted))
           case f : EmptyBox => return f
         }
       case Empty => 
         Full((updated, (deleted :+ ruleId)))
       case f : EmptyBox => 
         logger.error("Could not process rule %s : message is %s".format(ruleId.value, f.toString))
         return f
     }
     }
     case f : EmptyBox => return f
   }
   
   }

  /**
   * Increment the serial number of the CR. Must have ALL NODES as inputes
   */
  def updateSerialNumber(nodes : Seq[NodeConfiguration], rules  :Seq[(RuleId,Int)]) : Box[Seq[NodeConfiguration]] = {
    nodeConfigurationService.incrementSerials(rules, nodes)    
  }
  
  /**
   * Actually  write the new configuration for the list of given node.
   * If the node target configuration is the same as the actual, nothing is done.
   * Else, promises are generated; 
   * Return the list of configuration successfully written. 
   */
  def writeNodeConfigurations(nodeIds:Seq[NodeId]) : Box[Seq[NodeConfiguration]] = {
    nodeConfigurationService.writeTemplateForUpdatedNodeConfigurations(nodeIds)
  }
  
}

///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////


trait DeploymentService_setExpectedReports extends DeploymentService {
  def reportingService : ReportingService
  
  def setExpectedReports(ruleVal : Seq[RuleVal], deletedCrs : Seq[RuleId]) : Box[Seq[RuleExpectedReports]] = {
    reportingService.updateExpectedReports(ruleVal, deletedCrs)
  }
}


trait DeploymentService_historization extends DeploymentService {
  def historizationService : HistorizationService
  
  def historizeData() : Unit = {
    historizationService.updateNodes()
    historizationService.updateGroups()
    historizationService.updatePINames()
    historizationService.updatesRuleNames()
  }
  
  
  
}

