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

package com.normation.rudder.services.reports

import com.normation.cfclerk.domain.TrackerVariableSpec
import com.normation.inventory.domain.NodeId
import net.liftweb.common._
import scala.collection._
import org.joda.time._
import org.slf4j.{Logger,LoggerFactory}
import com.normation.cfclerk.domain.{
  TrackerVariable,Variable,
  CFCPolicyInstance,CFCPolicyInstanceId
}
import com.normation.rudder.domain._
import com.normation.rudder.domain.reports.ConfigurationExpectedReports
import com.normation.rudder.domain.policies.ConfigurationRuleId
import com.normation.rudder.domain.reports.bean._
import com.normation.rudder.domain.transporter._
import com.normation.rudder.services.reports._
import com.normation.rudder.repository._
import com.normation.rudder.domain.policies.ConfigurationRuleVal
import com.normation.rudder.services.policies.PolicyInstanceTargetService
import com.normation.utils.Control._
import com.normation.rudder.domain.policies.PolicyInstanceContainer
import com.normation.rudder.domain.reports.PolicyExpectedReports
import com.normation.rudder.domain.reports.ComponentCard
import com.normation.cfclerk.xmlparsers.CfclerkXmlConstants._
import com.normation.cfclerk.services.PolicyPackageService

class ReportingServiceImpl(
    policyInstanceTargetService: PolicyInstanceTargetService
  , confExpectedRepo: ConfigurationExpectedReportsRepository
  , reportsRepository: ReportsRepository
  , policyPackageService: PolicyPackageService
) extends ReportingService {

  val logger = LoggerFactory.getLogger(classOf[ReportingServiceImpl])

  /**
	 * Update the list of expected reports when we do a deployment
	 * For each ConfigurationRuleVal, we check if it was present or it serial changed
	 *   
	 * Note : deletedCrs is not really used (maybe it will in the future)
	 * @param configurationRuleVal
	 * @return
	 */
	def updateExpectedReports(configurationRuleVal : Seq[ConfigurationRuleVal], deletedCrs : Seq[ConfigurationRuleId]) : Box[Seq[ConfigurationExpectedReports]] = {
  	
  	// First we need to get the targets of each configuration rule
  	val confAndNodes = configurationRuleVal.map(x => (x -> (policyInstanceTargetService.getNodeIds(x.target)).openOr(Seq())))
 	
  	// All the rule and serial. Used to know which one are to be removed
    val currentConfigurationsToRemove =  mutable.Map[ConfigurationRuleId, Int]() ++ confExpectedRepo.findAllCurrentExpectedReportsAndSerial()

  	val confToClose = mutable.Set[ConfigurationRuleId]() 
  	val confToAdd = mutable.Map[ConfigurationRuleId, (ConfigurationRuleVal,Seq[NodeId])]()
  	
  	// Then we need to compare each of them with the one stored
  	for (conf@(crVal, _) <- confAndNodes) {
  	  currentConfigurationsToRemove.get(crVal.configurationRuleId) match {
  									// non existant, add it
  	  	case None => confToAdd += (  crVal.configurationRuleId -> conf)
  	  	
  			case Some(serial) if (serial == crVal.serial) => // no change if same serial
  			  	currentConfigurationsToRemove.remove(crVal.configurationRuleId)
  			  	
  			case Some(serial) => // not the same serial
  			  	confToAdd += (  crVal.configurationRuleId -> conf)
  			  	confToClose += crVal.configurationRuleId
  			  	
  			  	currentConfigurationsToRemove.remove(crVal.configurationRuleId)
  		}
  	}
  	
  	
  	// close the expected reports that don't exist anymore
  	for (closable <- currentConfigurationsToRemove.keys) {
  		confExpectedRepo.closeExpectedReport(closable)
  	}
  	// close the expected reports that need to be changed
  	for (closable <- confToClose) {
  		confExpectedRepo.closeExpectedReport(closable)
  	}
	
  	// compute the cardinality and save them
  	sequence(confToAdd.values.toSeq) { case(crVal, nodeIds) => 
      ( for {
  	    policyExpectedReports <- sequence(crVal.policies) { policy => 
  	                               ( for {
                          		       seq <- getCardinality(policy) ?~! "Can not get cardinality for configuration rule %s".format(crVal.configurationRuleId)
                          		      } yield {
                          		        seq.map { case(componentName, componentsValues) =>
                          		          PolicyExpectedReports(policy.policyInstanceId, Seq(ComponentCard(componentName, componentsValues.size, componentsValues)))
                          		        }
                          		     } )  
  	                             }
  		 	expectedReport <- confExpectedRepo.saveExpectedReports(
  		 	                      crVal.configurationRuleId
  		 	                    , crVal.serial
  		 	                    , policyExpectedReports.flatten
  		 	                    , nodeIds 
  		 	                  )
  		} yield {
  		  expectedReport
  		} ) 
  	}
  }
	
  
  /**
   * Returns the reports for a configuration rule
   */
  def findReportsByConfigurationRule(configurationRuleId : ConfigurationRuleId, beginDate : Option[DateTime], endDate : Option[DateTime]) : Seq[ExecutionBatch] = {
    val result = mutable.Buffer[ExecutionBatch]()
    
    // look in the configuration
    var expectedConfigurationReports = confExpectedRepo.findExpectedReports(configurationRuleId, beginDate, endDate)
        
    for (expected <- expectedConfigurationReports) {
      result ++= createBatchesFromConfigurationReports(expected, expected.beginDate, expected.endDate)
    }

    result
    
  }
  
  
  /**
   * Returns the reports for a node (for all policy instance/CR) (and hides result from other servers)
   */
  def findReportsByNode(nodeId : NodeId, beginDate : Option[DateTime], endDate : Option[DateTime]) : Seq[ExecutionBatch] = {
    val result = mutable.Buffer[ExecutionBatch]()
    
    // look in the configuration
    val expectedConfigurationReports = confExpectedRepo.
      findExpectedReportsByServer(nodeId, beginDate, endDate).
      map(x => x.copy(nodesList = Seq[NodeId](nodeId)))

    for (expected <- expectedConfigurationReports) {
      result ++= createBatchesFromConfigurationReports(expected, expected.beginDate, expected.endDate)
    }

    result
  }
  


  /**
   * Find the latest reports for a given configuration rule (for all servers)
   * Note : if there is an expected report, and that we don't have it, we should say that it is empty
   */
  def findImmediateReportsByConfigurationRule(configurationRuleId : ConfigurationRuleId) : Option[ExecutionBatch] = {
     // look in the configuration
    confExpectedRepo.findCurrentExpectedReports(configurationRuleId).map(
    		expected => createLastBatchFromConfigurationReports(expected) )
  }
  
  /**
   * Find the latest (15 minutes) reports for a given node (all CR)
   * Note : if there is an expected report, and that we don't have it, we should say that it is empty
   */
  def findImmediateReportsByNode(nodeId : NodeId) :  Seq[ExecutionBatch] = {
    // look in the configuration
    confExpectedRepo.findCurrentExpectedReportsByServer(nodeId).
    	map(x => x.copy(nodesList = Seq[NodeId](nodeId))).
    	map(expected => createLastBatchFromConfigurationReports(expected, Some(nodeId)) )
      
  }
  
  /**
   *  find the last reports for a given node, for a sequence of configuration rules
   *  look for each CR for the current report 
   */
  def findImmediateReportsByNodeAndCrs(nodeId : NodeId, configurationRuleIds : Seq[ConfigurationRuleId]) : Seq[ExecutionBatch] = {
  
    //  fetch the current expected configuration report
    confExpectedRepo.findCurrentExpectedReportsByServer(nodeId).
    				filter(x =>	configurationRuleIds.contains(x.configurationRuleId)).
    				map(x => x.copy(nodesList = Seq[NodeId](nodeId))).
    				map(expected => createLastBatchFromConfigurationReports(expected, Some(nodeId)) )
        
  }
  
  /**
   *  find the reports for a given server, for the whole period of the last application 
   */
  def findCurrentReportsByServer(nodeId : NodeId) : Seq[ExecutionBatch] = {
  	//  fetch the current expected configuration report
    var expectedConfigurationReports = confExpectedRepo.findCurrentExpectedReportsByServer(nodeId)
    val configuration = expectedConfigurationReports.map(x => x.copy(nodesList = Seq[NodeId](nodeId)))
    
    val result = mutable.Buffer[ExecutionBatch]()

    for (conf <- configuration) {
      result ++= createBatchesFromConfigurationReports(conf,conf.beginDate, conf.endDate)
    }
    
    result
  }
  
  
  /************************ Helpers functions **************************************/
  
 
   /**
   * From a ConfigurationExpectedReports, create batch synthetizing these information by
   * searching reports in the database from the beginDate to the endDate
   * @param expectedOperationReports
   * @param reports
   * @return
   */
  private def createBatchesFromConfigurationReports(expectedConfigurationReports : ConfigurationExpectedReports, beginDate : DateTime, endDate : Option[DateTime]) : Seq[ExecutionBatch] = {
    val batches = mutable.Buffer[ExecutionBatch]()
    
    // Fetch the reports corresponding to this configuration rule, and filter them by nodes
    val reports = reportsRepository.findReportsByConfigurationRule(expectedConfigurationReports.configurationRuleId, Some(expectedConfigurationReports.serial), Some(beginDate), endDate).filter( x => 
            expectedConfigurationReports.nodesList.contains(x.nodeId)  )  

    for {
      (cr,date) <- reports.map(x => (x.configurationRuleId ->  x.executionTimestamp)).toSet[(ConfigurationRuleId, DateTime)].toSeq
    } yield {
      new ConfigurationExecutionBatch(
          cr,
          expectedConfigurationReports.policyExpectedReports,
          expectedConfigurationReports.serial,
          date,
          reports.filter(x => x.executionTimestamp == date), // we want only those of this run
          expectedConfigurationReports.nodesList,
          expectedConfigurationReports.beginDate, 
          expectedConfigurationReports.endDate)
    }
  }
  
  
  
  /**
   * From a ConfigurationExpectedReports, create batch synthetizing the last run
   * @param expectedOperationReports
   * @param reports
   * @return
   */
  private def createLastBatchFromConfigurationReports(expectedConfigurationReports : ConfigurationExpectedReports,
      					nodeId : Option[NodeId] = None) : ExecutionBatch = {
  
    // Fetch the reports corresponding to this configuration rule, and filter them by nodes
    val reports = reportsRepository.findLastReportByConfigurationRule(
        		expectedConfigurationReports.configurationRuleId, 
        		expectedConfigurationReports.serial, nodeId) 

    new ConfigurationExecutionBatch(
          expectedConfigurationReports.configurationRuleId,
          expectedConfigurationReports.policyExpectedReports,
          expectedConfigurationReports.serial,
          reports.headOption.map(x => x.executionTimestamp).getOrElse(new DateTime()), // this is a dummy date !
          reports, 
          expectedConfigurationReports.nodesList,
          expectedConfigurationReports.beginDate, 
          expectedConfigurationReports.endDate)
  }
  

  private def getCardinality(container : PolicyInstanceContainer) : Box[Seq[(String, Seq[String])]] = {
    val getTrackingVariableCardinality : Seq[String] = {
      val boundingVar = container.TrackerVariable.spec.boundingVariable.getOrElse(container.TrackerVariable.spec.name)  
      // now the cardinality is the length of the boundingVariable
      container.variables.get(boundingVar) match {
        case None => 
          logger.debug("Could not find the bounded variable %s for %s in PolicyInstanceContainer %s".format(
              boundingVar, container.TrackerVariable.spec.name, container.policyInstanceId.value))
          Seq(DEFAULT_COMPONENT_KEY) // this is an autobounding policy
        case Some(variable) => 
          variable.values
      }
    }
    
    /*
     * We can have several components, one by section. 
     * If there is no component for that policy, the policy is autobounded to DEFAULT_COMPONENT_KEY
     */
    for {
      policyTemplate <- Box(policyPackageService.getPolicy(container.policyPackageId)) ?~! "Can not find policy template %s".format(container.policyPackageId)
    } yield {
      val allComponents = policyTemplate.rootSection.getAllSections.flatMap { section =>
        if(section.isComponent) {
          section.componentKey match {
            case None => 
              //a section that is a component without componentKey variable: card=1, value="None"
              Some((section.name, Seq(DEFAULT_COMPONENT_KEY)))
            case Some(varName) =>
              //a section with a componentKey variable: card=variable card
              val values = container.variables.get(varName).map( _.values).getOrElse(Seq())
              Some((section.name, values))
          }
        } else {
          None
        }
      }
      
      if(allComponents.size < 1) {
        logger.debug("Policy '%s' does not define any components, assigning default component with expected report = 1 for instance %s".format(
          container.policyPackageId, container.policyInstanceId))
        Seq((container.policyPackageId.name.value, getTrackingVariableCardinality))
      } else {
        allComponents
      }
    }
  }
  
}

