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

package com.normation.rudder.domain.reports.bean

import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies.ConfigurationRuleId
import com.normation.rudder.domain.policies.PolicyInstanceId
import scala.collection._
import org.joda.time._
import org.joda.time.format._
import com.normation.rudder.domain.Constants
import com.normation.cfclerk.domain.{CFCPolicyInstanceId}
import com.normation.rudder.domain.reports.ComponentCard
import com.normation.rudder.domain.reports.PolicyExpectedReports
import com.normation.utils.HashcodeCaching
import scala.collection.mutable.Buffer
import com.normation.rudder.domain.reports.bean.ConfigurationExecutionBatch._
/**
 * An execution batch contains the servers reports for a given CR/PI at a given date
 * An execution Batch is at a given time <- Is it relevant when we have several node ?
 * @author Nicolas CHARLES
 */
trait ExecutionBatch {
  val configurationRuleId : ConfigurationRuleId
  val serial : Int // the serial of the configuration rule
  
  val executionTime : DateTime // this is the time of the batch
  
  val policies : Seq[PolicyExpectedReports] // the list of policies, list of component and cardinality
  
  val executionReports : Seq[Reports]
  
  val allExpectedServer : Seq[NodeId]
  
  def getSuccessReports() : Seq[Reports] = {
    executionReports.filter(x => x.isInstanceOf[ResultSuccessReport])
  }

  def getRepairedReports() : Seq[Reports] = {
    executionReports.filter(x => x.isInstanceOf[ResultRepairedReport])
  }
  
  /* Warn is temporarly unused*/
  /*
  def getWarnReports() : Seq[Reports] = {
    executionReports.filter(x => x.isInstanceOf[WarnReport])
  }*/
  
  def getErrorReports() : Seq[Reports] = {
    executionReports.filter(x => x.isInstanceOf[ResultErrorReport])
  }
  
  /**
   * Returns all the server that have only success reports
   * @return
   */
  def getSuccessServer() : Seq[NodeId] 

  /**
   * Returns all the server that have repaired reports
   * @return
   */
  def getRepairedServer() : Seq[NodeId]
  
  /**
   * Returns all the server that have  success reports, and some war/error
   * @return
   */
 // def getWarnServer() : Seq[NodeId] 
  
  /**
   * Returns all the server that don't have enough success
   * @return
   */
  def getErrorServer() : Seq[NodeId]
  
  /**
   * A pending server is a server that was just configured, and we don't 
   * have answer yet
   */
  def getPendingServer() : Seq[NodeId]
  /**
   * return the server that did not send reports
   * @return
   */
  def getServerWithNoReports() : Seq[NodeId]
  
  def getUnknownNodes() : Seq[NodeId]
}


object ConfigurationExecutionBatch {
  final val matchCFEngineVars = """.*\$(\{.+\}|\(.+\)).*""".r
  final val replaceCFEngineVars = """\$\{.+\}|\$\(.+\)"""
}

/**
 * The execution batch for a configuration, still a lot of intelligence to add within
 * 
 */
class ConfigurationExecutionBatch( 
    val configurationRuleId : ConfigurationRuleId, 
    val policies : Seq[PolicyExpectedReports], 
    val serial : Int,
    val executionTime : DateTime,
    val executionReports : Seq[Reports],
    val allExpectedServer : Seq[NodeId],
    val beginDate : DateTime, 
    val endDate : Option[DateTime]) extends ExecutionBatch {  
  
  val cache = scala.collection.mutable.Map[String, Seq[NodeId]]()
   
  /**
   * a success server have all the expected success report, 
   * for each component, and no warn nor error nor repaired
   */
  def getSuccessServer() : Seq[NodeId] = {
    cache.getOrElseUpdate("Success", {
	    (for {server <- allExpectedServer;
	    	 val nodeFilteredReports = executionReports.filter(x => (x.nodeId==server))
	       if (nodeFilteredReports.filter(x => (( x.isInstanceOf[ResultErrorReport] || x.isInstanceOf[ResultRepairedReport] ) )).size == 0)
	       if (policies.forall { policy => 
	         val linearised = linearisePolicyExpectedReports(policy)
	         val filteredReports = nodeFilteredReports.filter(x => x.policyInstanceId == policy.policyInstanceId && x.isInstanceOf[ResultSuccessReport])

	         // we expect exactly as much reports (success that is) that the one we are having, or else it's wrong
	         filteredReports.size == linearised.size &&
	         linearised.forall( expected =>
	             expected.componentValue match {
	               case "None" =>
	                 // each non defined component key must have the right cardinality, no more, no less
	                 filteredReports.filter( x => 
	                     x.component == expected.componentName).size == expected.cardinality
	               case matchCFEngineVars(someValue) =>
	                 // this is a case when we have a CFEngine Variable
	                 val matchableExpected = expected.componentValue.replaceAll(replaceCFEngineVars, ".*")
	                 // We've converted the string into a regexp, by replacing ${} and $() by .*
	                 filteredReports.filter( x => 
                     x.component == expected.componentName &&
                     x.keyValue.matches(matchableExpected)).size >= 1 // >= 1 for we accept at least one, but we check that we dont have too much by summing

	               case string:String =>
	                 // for a given component, if the key is not "None", then we are 
	                 // checking that what is have is what we wish
	                 // we can have more reports that what we expected, because of
	                 // name collision, but it would be resolved by the total number 
	                 filteredReports.filter( x => 
	                   x.component == expected.componentName &&
	                   x.keyValue == expected.componentValue).size >= 1 // >= 1 for we accept at least one, but we check that we dont have too much by summing
	             }
	         )
	    	 })
	    } yield server).distinct
    })
    
  }
  
  /**
   * a success server have at least one repaired, and no error, but must have
   * the EXACT number of success or repaired per component
   */
  def getRepairedServer() : Seq[NodeId] = {
    cache.getOrElseUpdate("Repaired", {
	    (for {server <- allExpectedServer;
	    	val nodeFilteredReports = executionReports.filter(x => (x.nodeId==server))
		    if (nodeFilteredReports.filter(x => ( x.isInstanceOf[ResultErrorReport]  ) ).size == 0)
		    if (nodeFilteredReports.filter(x => ( x.isInstanceOf[ResultRepairedReport]  ) ).size > 0)
		    if (policies.forall { policy =>

		      val linearised = linearisePolicyExpectedReports(policy)
          val filteredReports = nodeFilteredReports.filter(x => 
            x.policyInstanceId == policy.policyInstanceId && 
            ( x.isInstanceOf[ResultSuccessReport] ||  x.isInstanceOf[ResultRepairedReport])
          )

          // we can have more reports that those we really expected (repaired and success)
          filteredReports.size >= linearised.size &&
          linearised.forall( expected =>
               expected.componentValue match {
                 case "None" =>
                   filteredReports.filter( x=> 
                       x.component == expected.componentName).size >= expected.cardinality
                 case matchCFEngineVars(someValue) =>
                   // this is a case when we have a CFEngine Variable
                   val matchableExpected = expected.componentValue.replaceAll(replaceCFEngineVars, ".*")
                   // We've converted the string into a regexp, by replacing ${} and $() by .*
                   filteredReports.filter( x => 
                     x.component == expected.componentName &&
                     x.keyValue.matches(matchableExpected)).size >= 1 // >= 1 for we accept at least one, but we check that we dont have too much by summing

                 case string:String =>
                   filteredReports.filter( x=> 
                     x.component == expected.componentName &&
                     x.keyValue == expected.componentValue).size >= 1 // >= 1 for we accept at least one, but we check that we dont have too much by summing                   
               }
           )
	       })
	    } yield server).distinct
   })  
     
  }
  
  /**
   * a warn server have all the expected success report, and warn or error 
   *//*
  def getWarnServer() : Seq[NodeId] = {
    (for {server <- allExpectedServer;
      policy <- policies
      component <- policy.components
    	if (executionReports.filter(x => 
         	(x.nodeId==server && x.component == component.componentName && x.isInstanceOf[SuccessReport])).size >= component.cardinality)
    	if (executionReports.filter(x => 
         	(x.nodeId==server && x.component == component.componentName && (x.isInstanceOf[WarnReport]  || x.isInstanceOf[ErrorReport]))).size > 0)
    } yield server).distinct
  }*/
  
  /**
   * a error server have not all the expected success report, and/or error 
   */
  def getErrorServer() : Seq[NodeId] = {
    cache.getOrElseUpdate("Error", {
	    (for {server <- allExpectedServer;
	       val nodeFilteredReports = executionReports.filter(x => (x.nodeId==server))
	       // if there is an error report, then it's an error
	       if ( (nodeFilteredReports.filter( x => x.isInstanceOf[ResultErrorReport] ).size > 0 ) ||
	         // or if there is at least a policy instance that is not valid
	         (policies.exists { policy =>
	           val linearised = linearisePolicyExpectedReports(policy)
	           val filteredReports = nodeFilteredReports.filter(x => 
                x.policyInstanceId == policy.policyInstanceId && 
                ( x.isInstanceOf[ResultSuccessReport] ||  x.isInstanceOf[ResultRepairedReport])
              )
              // we shouldn't have less reports that those we really expected (repaired and success)
              filteredReports.size < linearised.size ||
              linearised.exists(expected =>
                expected.componentValue match {
                 case "None" =>
                   filteredReports.filter( x=> 
                       x.component == expected.componentName).size < expected.cardinality
                 case matchCFEngineVars(someValue) =>
                   // this is a case when we have a CFEngine Variable
                   val matchableExpected = expected.componentValue.replaceAll(replaceCFEngineVars, ".*")
                   // We've converted the string into a regexp, by replacing ${} and $() by .*
                   filteredReports.filter( x => 
                     x.component == expected.componentName &&
                     x.keyValue.matches(matchableExpected)).size == 0 // no match

                 case string:String =>
                   filteredReports.filter( x=> 
                     x.component == expected.componentName &&
                     x.keyValue == expected.componentValue).size == 0 // no match                   
               }
                  
              )
	         }) && // must have results (otherwise it's a no answer)
	         /*
	       
	       policy <- policies
	       if (nodeFilteredReports.filter( x => x.isInstanceOf[ResultErrorReport] ).size > 0 ) || 
	         ( (policy.components.forall { component => // must be true for each component
	       			component.componentsValues.forall { value => // for each value  			    	
		       			if (value == "None") {
		       			    nodeFilteredReports.filter( x => 
		       			  		x.component == component.componentName &&
		       			    	(x.isInstanceOf[ResultSuccessReport] ||  x.isInstanceOf[ResultRepairedReport] )).size < component.cardinality
		       			} else {
		       			  	nodeFilteredReports.filter( x => 
		       			  		x.component == component.componentName &&
		       			    	x.keyValue == value &&
		       			    	(x.isInstanceOf[ResultSuccessReport] ||  x.isInstanceOf[ResultRepairedReport] )).size < 1
		       			  
	       			  }
	       			}
	         }) && // must have results (otherwise it's a no answer) */
	         nodeFilteredReports.filter ( x =>  x.isInstanceOf[ResultSuccessReport] || 
	           																x.isInstanceOf[ResultRepairedReport] || 
	           																x.isInstanceOf[ResultErrorReport]
	           													).size > 0 ) 
	         
	       /*component <- policy.components
	       
	       val filtered = executionReports.filter(x =>	(x.nodeId==server && x.component == component.componentName))
	      
	       if ( ( filtered.filter(x => x.isInstanceOf[ResultSuccessReport] || x.isInstanceOf[ResultRepairedReport] ).size > 0 ) && 
	          ( filtered.filter(x => x.isInstanceOf[ResultSuccessReport]).size < component.cardinality ) &&
	       		( filtered.filter(x => x.isInstanceOf[ResultRepairedReport]).size < component.cardinality ) ) ||
	       		( filtered.filter(x => x.isInstanceOf[ResultErrorReport]).size > 0 )
	       
	*/
	    } yield server).distinct
	   }) 
  }
  
  /**
   * A pending server is a server that was just configured, and we don't 
   * have answer yet
   */
  def getPendingServer() : Seq[NodeId] = {
  	if (beginDate.plus(Constants.pendingDuration).isAfter(new DateTime())) {
  	  cache.getOrElseUpdate("Pending", {
	  		(for {server <- allExpectedServer;
	       	if (executionReports.filter(x => (x.nodeId==server)).size == 0)
	  		} yield server).distinct
  	  })
  	} else {
  		Seq()
  	}
  }
  
  /**
   * A server with no reports should have send reports, but didn't
   */
  def getServerWithNoReports() : Seq[NodeId] = {
  	if (beginDate.plus(Constants.pendingDuration).isBefore(new DateTime())) {
  	  cache.getOrElseUpdate("NoAnswer", {
	  		(for {server <- allExpectedServer;
	       	if (executionReports.filter(x => (x.nodeId==server)).size == 0)
	  		} yield server).distinct
  	  })
  	} else {
  		Seq()
  	}
  }
  
  
  /**
   * An unknown node isn't success, repaired, error, pending nor no reports
   */
  def getUnknownNodes() : Seq[NodeId] = {
    allExpectedServer.filter(node => !(getSuccessServer().contains(node)))
    								.filter(node => !(getRepairedServer().contains(node)))
    								.filter(node => !(getErrorServer().contains(node)))
    								.filter(node => !(getPendingServer().contains(node)))
    								.filter(node => !(getServerWithNoReports().contains(node)))
    
  }
  
  private[this] def linearisePolicyExpectedReports(policy : PolicyExpectedReports) : Buffer[LinearisedExpectedReport] = {
    val result = Buffer[LinearisedExpectedReport]()
    
    for (component <- policy.components) {
      for (value <- component.componentsValues) {
        result += LinearisedExpectedReport(
              policy.policyInstanceId
            , component.componentName
            , component.cardinality
            , value
        )
      }
    }
    result
  }
}

case class LinearisedExpectedReport(
    policyInstanceId  : PolicyInstanceId
  , componentName     : String
  , cardinality       : Int
  , componentValue    : String
) extends HashcodeCaching 
