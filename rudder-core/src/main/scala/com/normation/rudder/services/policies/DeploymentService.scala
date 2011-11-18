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

import com.normation.rudder.domain.reports.ConfigurationExpectedReports
import com.normation.rudder.domain.servers.NodeConfiguration
import com.normation.rudder.services.nodes.NodeInfoService
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.cfclerk.domain.VariableSpec
import com.normation.cfclerk.domain.Variable
import com.normation.cfclerk.services.PolicyPackageService
import com.normation.rudder.repository.UserPolicyTemplateRepository
import com.normation.rudder.repository.ConfigurationRuleRepository
import net.liftweb.common._
import com.normation.rudder.domain.policies._
import com.normation.inventory.domain.NodeId
import com.normation.utils.Control.sequence
import com.normation.rudder.services.nodes.NodeInfoService
import com.normation.rudder.services.servers.NodeConfigurationService
import com.normation.rudder.services.reports.ReportingService
import com.normation.cfclerk.domain.CFCPolicyInstance
import com.normation.rudder.services.servers.NodeConfigurationChangeDetectService
import org.joda.time.DateTime
import com.normation.rudder.repository.jdbc.HistorizationJdbcRepository
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.services.log.HistorizationService
import com.normation.utils.HashcodeCaching

/**
 * TODO: ca devrait être un "target node configuration", ie
 * tout ce qui va changer dans le node configuration
 *
 */
case class TargetNodeConfiguration(
    nodeInfo:NodeInfo,
    identifiableCFCPIs: Seq[IdentifiableCFCPI],
    //environment variable for that server
    nodeContext:Map[String, Variable]
) extends HashcodeCaching 



/**
 * The main service which deploy modified Configuration Rules and
 * their dependencies.
 */
trait DeploymentService extends Loggable {

  
  /**
   * All mighy method that take all modified configuration rules, find their
   * dependencies, proccess ${vars}, build the list of node to update, 
   * update nodes. 
   * 
   * Return the list of node IDs actually updated. 
   * 
   */
  def deploy() : Box[Seq[NodeConfiguration]] = {
    val initialTime = new DateTime().getMillis
    val result = for {
      configurationRules <- findDependantConfigurationRules ?~! "Could not find dependant configuration rules"
      val log1 = logger.debug("Configuration rules dependency solved in %d millisec, start to build ConfigurationRuleVals".format((new DateTime().getMillis - initialTime)))
      
      val historizeTime = new DateTime().getMillis
      
      val historize = historizeData()
      val log1_5 = logger.debug("Historization of name done in %d millisec".format((new DateTime().getMillis - historizeTime)))
      
      val crValTime = new DateTime().getMillis
      configurationRuleVals <- buildConfigurationRuleVals(configurationRules) ?~! "Cannot build configuration rule vals"
      val log2 = logger.debug("ConfigurationRuleVals built in %d millisec, start to build TargetNodeConfiguration".format((new DateTime().getMillis - crValTime)))

      val targetNodeTime = new DateTime().getMillis
      targetNodeConfigurations <- buildTargetNodeConfigurations(configurationRuleVals) ?~! "Cannot build target configuration node"
      val log3 = logger.debug("TargetNodeConfiguration built in %d millisec, start to update whose needed to be updated.".format((new DateTime().getMillis - targetNodeTime)))

      val updateConfNodeTime = new DateTime().getMillis
      updatedNodeConfigs <- updateTargetNodeConfigurations(targetNodeConfigurations) ?~! "Cannot set target configuration node"
      val log4 = logger.debug("ConfigurationRuleVals updated in %d millisec, detect changes.".format((new DateTime().getMillis - updateConfNodeTime)))
      
      val beginTime = new DateTime().getMillis
      
      (updatedCrs, deletedCrs) <- detectUpdates(updatedNodeConfigs)?~! "Cannot detect the updates in the NodeConfiguration"
      val log6 = logger.debug("Detected the changes in the NodeConfiguration to trigger change in CR in %d millisec. Update the SN in the nodes".format((new DateTime().getMillis - beginTime)))
      
      val updateTime = new DateTime().getMillis
      
      serialedNodes <- updateSerialNumber(updatedNodeConfigs, updatedCrs) ?~! "Cannot update the serial number of the CR in the nodes"
      val log7 = logger.debug("Serial number updated in the nodes in %d millisec. Update information in crval.".format((new DateTime().getMillis - updateTime)))
      
      val updateTime2 = new DateTime().getMillis
      
      updatedConfigurationRuleVals <- updateConfigurationRuleVal(configurationRuleVals, updatedCrs) ?~! "Cannot update the serials in the CRVal" 
      val log8 = logger.debug("Updated serial in crval in %d millisec. Write promisses.".format((new DateTime().getMillis - updateTime2)))
      
      val writeTime = new DateTime().getMillis
      
      writtenNodeConfigs <- writeNodeConfigurations(serialedNodes.map(config => NodeId( config.id ) )) ?~! "Cannot write  configuration node"
      val log9 = logger.debug("Configuration rules deployed in %d millisec, process report information".format((new DateTime().getMillis - writeTime)))
      
      val reportTime = new DateTime().getMillis
      expectedReports <- setExpectedReports(updatedConfigurationRuleVals, deletedCrs)  ?~! "Cannot build expected reports"
      val log10 =logger.debug("Reports updated in %d millisec".format((new DateTime().getMillis - reportTime)))
      
    } yield writtenNodeConfigs
    logger.debug("Deployment completed in %d millisec".format((new DateTime().getMillis - initialTime)))
    result
  }
  
  
  /**
   * Find all modified configuration rules.
   * For them, find all policy instances with variables
   * referencing these configuration rules. 
   * Add them to the set of configuration rules to return, and
   * recurse.
   * Stop when convergence is reached
   * 
   * No modification on back-end are performed 
   * (perhaps safe setting the "isModified" value to "true" for
   * all dependent CR).
   * 
   */
  def findDependantConfigurationRules() : Box[Seq[ConfigurationRule]]
  
  
  /**
   * Build the list of "CFclerkConfigurationRuleVal" from a list of
   * configuration rules. 
   * These objects are a cache of all configuration rules, with for
   * each of them the ${confRule.varName} replaced
   */
   def buildConfigurationRuleVals(configurationRules:Seq[ConfigurationRule]) : Box[Seq[ConfigurationRuleVal]]
  
  /**
   * From a list of configurationRuleVal, find the list of all impacted nodes
   * with the actual CFCPolicyInstanceBean they will have. 
   * Replace all ${node.varName} vars.
   */
  def buildTargetNodeConfigurations(configurationRuleVals:Seq[ConfigurationRuleVal]) : Box[Seq[TargetNodeConfiguration]]

  /**
   * For each CFCNodeConfiguration, look if the target node is already configured or
   * will be modified. 
   * For each modified node, set its target objects to CFCNodeConfiguration.
   * Return the actually modified nodes.
   */
  def updateTargetNodeConfigurations(configurations:Seq[TargetNodeConfiguration]) : Box[Seq[NodeConfiguration]]
  
  
  
  /**
   * Detect changes in the NodeConfiguration, to trigger an increment in the related CR
   * The CR are updated in the LDAP
   * Must have all the NodeConfiguration in nodes
   * Returns two seq : the updated cr, and the deleted cr 
   */
  def detectUpdates(nodes : Seq[NodeConfiguration]) : Box[(Seq[(ConfigurationRuleId,Int)], Seq[ConfigurationRuleId])]  
  
  /**
   * Set all the serial number when needed (a change in CR)
   * Must have all the NodeConfiguration in nodes
   */
  def updateSerialNumber(nodes : Seq[NodeConfiguration], crs : Seq[(ConfigurationRuleId,Int)]) :  Box[Seq[NodeConfiguration]]
  
  /**
   * Actually  write the new configuration for the list of given node.
   * If the node target configuration is the same as the actual, nothing is done.
   * Else, promises are generated; 
   * Return the list of configuration successfully written. 
   */
  def writeNodeConfigurations(nodeConfigurations:Seq[NodeId]) : Box[Seq[NodeConfiguration]]
  
  
  /**
   * Update the serials in the configuration rule vals based on the updated configurationrule
   * Goal : actually set the right serial in them, to have an easy setExpectedReports
   */
  def updateConfigurationRuleVal(configurationRuleVal : Seq[ConfigurationRuleVal], crs : Seq[(ConfigurationRuleId,Int)]) : Box[Seq[ConfigurationRuleVal]]
  
  /**
   * Set the exepcted reports for the configuration rule
   * Caution : we can't handle deletion with this
   * @param configurationRuleVal
   * @return
   */
  def setExpectedReports(configurationRuleVal : Seq[ConfigurationRuleVal], deletedCrs : Seq[ConfigurationRuleId]) : Box[Seq[ConfigurationExpectedReports]]
  
  /**
   * Store groups and pi in the database
   */
  def historizeData() : Unit

}

///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//  Implémentation
///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

class DeploymentServiceImpl (
    override val configurationRuleRepo: ConfigurationRuleRepository,
    override val configurationRuleValService : ConfigurationRuleValService,
    override val parameterizedValueLookupService : ParameterizedValueLookupService,
    override val systemVarService: SystemVariableService,
    override val targetToNodeService : PolicyInstanceTargetService,
    override val nodeConfigurationService : NodeConfigurationService,
    override val nodeInfoService : NodeInfoService,
    override val nodeConfigurationChangeDetectService : NodeConfigurationChangeDetectService,
    override val reportingService : ReportingService,
    override val historizationService : HistorizationService
) extends DeploymentService with
  DeploymentService_findDependantConfigurationRules_bruteForce with
  DeploymentService_buildConfigurationRuleVals with
  DeploymentService_buildTargetNodeConfigurations with
  DeploymentService_updateAndWriteConfigurationRule with
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
 * so for now, we just return *ALL* configuration rule.
 * 
 * It might not scale very well. 
 *
 */
trait DeploymentService_findDependantConfigurationRules_bruteForce extends DeploymentService {
  def configurationRuleRepo : ConfigurationRuleRepository
  
  override def findDependantConfigurationRules() : Box[Seq[ConfigurationRule]] = {
    configurationRuleRepo.getAllActivated
  }
}

///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

trait DeploymentService_buildConfigurationRuleVals extends DeploymentService {
  

  def configurationRuleValService : ConfigurationRuleValService
  def parameterizedValueLookupService : ParameterizedValueLookupService
  
  
  /**
   * Build the list of "CFclerkConfigurationRuleVal" from a list of
   * configuration rules. 
   * These objects are a cache of all configuration rules, with for
   * each of them the ${confRule.varName} replaced
   */
   override def buildConfigurationRuleVals(configurationRules:Seq[ConfigurationRule]) : Box[Seq[ConfigurationRuleVal]] = {
     for {
       rawConfigurationRuleVals <- findConfigurationRuleVals(configurationRules) ?~! "Could not find configuration vals"
       replacedConfigurationVals <- replaceVariable(rawConfigurationRuleVals) ?~! "Could not replace variables"
     } yield replacedConfigurationVals
   }
   
   /**
    * For each configuraiton rule, find its policy instance and policy package, and
    * store all variables / values
    * @param configurationRules
    * @return
    */
   private[this] def findConfigurationRuleVals(configurationRules:Seq[ConfigurationRule]) : Box[Seq[ConfigurationRuleVal]] = {
     sequence(configurationRules) {  cr =>
       configurationRuleValService.findConfigurationRuleVal(cr.id)
     }
   }

   

   /**
    * 
    * Replace all variable of the for ${configurationRuleId.varName} by the seq of values for
    * the varName in ConfigurationRuleVal with id configurationRuleId.
    * 
    * Replacement rules are:
    * 
    * 
    * @param configurationRules
    * @return
    */
   private[this] def replaceVariable(configurationRuleVals:Seq[ConfigurationRuleVal]) : Box[Seq[ConfigurationRuleVal]] = {
     sequence(configurationRuleVals) { crv => {
       for {
         updatedPolicies <- { sequence(crv.policies) {
        	 policy => 
           	for {
           		replacedVariables <- parameterizedValueLookupService.lookupConfigurationRuleParameterization(policy.variables.values.toSeq) ?~! 
           				"Error when processing configuration rule (%s/%s:%s/%s) with variables: %s".format(
           				    crv.configurationRuleId.value, 
           				    policy.policyPackageId.name.value, 
           				    policy.policyPackageId.version.toString, 
           				    policy.policyInstanceId.value, 
           				    policy.variables.values.toSeq
           				)
           	} yield {
          	 policy.copy(variables = replacedVariables.map(v => (v.spec.name -> v)).toMap)
           	}
         	}
         }
       } yield {
      	 crv.copy(policies = updatedPolicies)
       }
     }
     }
   }
   

   /**
   * Update the serials in the configuration rule vals based on the updated configurationrule (which may be empty if nothing is updated)
   * Goal : actually set the right serial in them, to have an easy setExpectedReports
   */
  def updateConfigurationRuleVal(configurationRuleVal : Seq[ConfigurationRuleVal], crs : Seq[(ConfigurationRuleId,Int)]) : Box[Seq[ConfigurationRuleVal]] = {
    Full(configurationRuleVal.map(crVal => {
      crs.find { case(id,serial) => id == crVal.configurationRuleId } match {
        case Some((id,serial)) => crVal.copy(serial = serial)
        case _ => crVal
      }
    }))
    
  }
  

   
}

///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

trait DeploymentService_buildTargetNodeConfigurations extends DeploymentService with Loggable {
  def systemVarService: SystemVariableService
  def targetToNodeService : PolicyInstanceTargetService
  def nodeInfoService : NodeInfoService 
  def parameterizedValueLookupService : ParameterizedValueLookupService
  
  /**
   * This object allows to construct the target node configuration
   */
  private[this] case class MutableTargetNodeConfiguration(
        nodeInfo:NodeInfo,
        //environment variable for that server
        nodeContext: Map[String, Variable]
  ) {
    val identifiableCFCPIs = scala.collection.mutable.Buffer[IdentifiableCFCPI]()
    
    def immutable = TargetNodeConfiguration(nodeInfo, identifiableCFCPIs, nodeContext)
  }
    
    
  /**
   * From a list of configurationRuleVal, find the list of all impacted nodes
   * with the actual CFCPolicyInstanceBean they will have. 
   * Replace all ${node.varName} vars.
   */
  override def buildTargetNodeConfigurations(configurationRuleVals:Seq[ConfigurationRuleVal]) : Box[Seq[TargetNodeConfiguration]] = {
    val targetNodeConfigMap = scala.collection.mutable.Map[NodeId, MutableTargetNodeConfiguration]()
    
    configurationRuleVals.foreach { crv =>
      targetToNodeService.getNodeIds(crv.target) match {
        case e:EmptyBox => return e
        case Full(nodeIds) => nodeIds.foreach { nodeId =>
          targetNodeConfigMap.get(nodeId) match {
            case None => //init nodeConfig for that id
              (for {
                nodeInfo <- nodeInfoService.getNodeInfo(nodeId)
                nodeContext <- systemVarService.getSystemVariables(nodeInfo)
              } yield {
                val nodeConfig = MutableTargetNodeConfiguration(nodeInfo, nodeContext.toMap)
                nodeConfig.identifiableCFCPIs ++= crv.toIdentifiableCFCPI
                nodeConfig
              }) match {
                case e:EmptyBox => logger.debug("Error while building taget conf node " + e);e
                case Full(nodeConfig) => targetNodeConfigMap(nodeConfig.nodeInfo.id) = nodeConfig
              }
            case Some(nodeConfig) => //add PolicyInstanceContainer to the list of policies for that node
 	              nodeConfig.identifiableCFCPIs ++= crv.toIdentifiableCFCPI
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
  private[this] def replaceNodeVars(targetNodeConfig:MutableTargetNodeConfiguration) : Box[TargetNodeConfiguration] = {
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
     * In a IdentifiableCFCPI, replace the parametrized node value by the fetched values
     */
    def replacePolicyInstance(policy:IdentifiableCFCPI) : Box[IdentifiableCFCPI] = {
      ( for {
        variables <- Full(policy.policyInstance.getVariables().values.toSeq)
        replacedVars <- parameterizedValueLookupService.lookupNodeParameterization(nodeId, variables)
      } yield {
        policy.copy(
            policyInstance = new CFCPolicyInstance(policy.policyInstance.id,
                	policy.policyInstance.policyId,
                	__variableMap = policy.policyInstance.getVariables ++ replacedVars.map(v => (v.spec.name, v)),
                	policy.policyInstance.TrackerVariable,
                	priority = policy.policyInstance.priority,
                	serial = policy.policyInstance.serial)
            )
      } ) match {
        case e:EmptyBox => e
        case Full(x) => Full(x) 
      }
    }
    
    for {
      replacedNodeContext <- replaceNodeContext()
      replacedPolicyInstance <- sequence(targetNodeConfig.identifiableCFCPIs) { case(pib) =>
        replacePolicyInstance(pib)
      }
    } yield {
      TargetNodeConfiguration(
          nodeInfo = targetNodeConfig.nodeInfo,
          identifiableCFCPIs = replacedPolicyInstance,
          nodeContext = replacedNodeContext
      )
    }
  }

  
}


///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

trait DeploymentService_updateAndWriteConfigurationRule extends DeploymentService {
  
  def nodeConfigurationService : NodeConfigurationService
  
  def nodeConfigurationChangeDetectService : NodeConfigurationChangeDetectService

  def configurationRuleRepo: ConfigurationRuleRepository
  
   /**
   * For each CFCNodeConfiguration, look if the target node is already configured or
   * will be modified. 
   * For each modified node, set its target objects to CFCNodeConfiguration.
   * Return the actually modified nodes.
   */
  def updateTargetNodeConfigurations(configurations:Seq[TargetNodeConfiguration]) : Box[Seq[NodeConfiguration]] = {
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
   * Detect changes in crs and update their serial
   * Returns two seq : the updated crs, and the deleted crs
   */
  def detectUpdates(nodes : Seq[NodeConfiguration]) : Box[(Seq[(ConfigurationRuleId,Int)], Seq[ConfigurationRuleId])] = {
   // First, fetch the updated CRs (which are either updated or deleted)
   (( Full(Seq[(ConfigurationRuleId,Int)](), Seq[ConfigurationRuleId]()) )/:(nodeConfigurationChangeDetectService.detectChangeInNodes(nodes)) ) { case (Full((updated, deleted)), crId) => {
     configurationRuleRepo.get(crId) match {
       case Full(cr) => 
         configurationRuleRepo.incrementSerial(cr.id) match {
           case Full(newSerial) => logger.trace("Updating cr %s to serial %d".format(cr.id.value, newSerial))
           												Full((updated :+ (cr.id,newSerial), deleted))
           case f : EmptyBox => return f
         }
       case Empty => 
         Full((updated, (deleted :+ crId)))
       case f : EmptyBox => 
         logger.error("Could not process cr %s : message is %s".format(crId.value, f.toString))
         return f
     }
     }
     case f : EmptyBox => return f
   }
   
   }

  /**
   * Increment the serial number of the CR. Must have ALL NODES as inputes
   */
  def updateSerialNumber(nodes : Seq[NodeConfiguration], crs  :Seq[(ConfigurationRuleId,Int)]) : Box[Seq[NodeConfiguration]] = {
    nodeConfigurationService.incrementSerials(crs, nodes)    
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
	
	def setExpectedReports(configurationRuleVal : Seq[ConfigurationRuleVal], deletedCrs : Seq[ConfigurationRuleId]) : Box[Seq[ConfigurationExpectedReports]] = {
		reportingService.updateExpectedReports(configurationRuleVal, deletedCrs)
	}
}


trait DeploymentService_historization extends DeploymentService {
  def historizationService : HistorizationService
  
  def historizeData() : Unit = {
    historizationService.updateNodes()
    historizationService.updateGroups()
    historizationService.updatePINames()
    historizationService.updatesConfigurationRuleNames()
  }
  
  
  
}

