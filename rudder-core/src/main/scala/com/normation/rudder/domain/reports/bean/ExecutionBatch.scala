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
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.DirectiveId
import scala.collection._
import org.joda.time._
import org.joda.time.format._
import com.normation.rudder.domain.Constants
import com.normation.cfclerk.domain.{Cf3PolicyDraftId}
import com.normation.rudder.domain.reports.ReportComponent
import com.normation.rudder.domain.reports.DirectiveExpectedReports
import com.normation.utils.HashcodeCaching
import scala.collection.mutable.Buffer
import ConfigurationExecutionBatch._

/**
 * An execution batch contains the node reports for a given Rule / Directive at a given date
 * An execution Batch is at a given time <- TODO : Is it relevant when we have several node ?
 * @author Nicolas CHARLES
 */
trait ExecutionBatch {
  val ruleId : RuleId
  val serial : Int // the serial of the rule
  
  val executionTime : DateTime // this is the time of the batch
  
  val directiveExpectedReports : Seq[DirectiveExpectedReports] // the list of policies, list of component and cardinality
  
  val executionReports : Seq[Reports]
  
  val expectedNodeIds : Seq[NodeId]
  
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
   * Returns all the nodes that have only success reports
   * @return
   */
  def getSuccessNodeIds() : Seq[NodeId] 

  /**
   * Returns all the nodes that have repaired reports
   * @return
   */
  def getRepairedNodeIds() : Seq[NodeId]
  
  /**
   * Returns all the nodes that have success reports, and some warn/error
   * @return
   */
 // def getWarnNodeIds() : Seq[NodeId] 
  
  /**
   * Returns all the nodes that don't have enough success
   * @return
   */
  def getErrorNodeIds() : Seq[NodeId]
  
  /**
   * A pending node is a node that was just configured, and we don't 
   * have answer yet from it
   */
  def getPendingNodeIds() : Seq[NodeId]
  /**
   * return the nodes that did not send reports
   * @return
   */
  def getNoReportNodeIds() : Seq[NodeId]
  
  def getUnknownNodeIds() : Seq[NodeId]
}


object ConfigurationExecutionBatch {
  final val matchCFEngineVars = """.*\$(\{.+\}|\(.+\)).*""".r
  final val replaceCFEngineVars = """\$\{.+\}|\$\(.+\)"""
}

/**
 * The execution batch for a rule, still a lot of intelligence to add within
 * 
 */
class ConfigurationExecutionBatch( 
    val ruleId                  : RuleId
  , val directiveExpectedReports: Seq[DirectiveExpectedReports]
  , val serial                  : Int
  , val executionTime           : DateTime
  , val executionReports        : Seq[Reports]
  , val expectedNodeIds         : Seq[NodeId]
  , val beginDate               : DateTime
  , val endDate                 : Option[DateTime]
) extends ExecutionBatch {  
  
  // A cache of the already computed values
  val cache = scala.collection.mutable.Map[String, Seq[NodeId]]()
   
  /**
   * a success node has all the expected success report, 
   * for each component, and no warn nor error nor repaired
   */
  def getSuccessNodeIds() : Seq[NodeId] = {
    cache.getOrElseUpdate("Success", {
	    (for {server <- expectedNodeIds;
	    	 val nodeFilteredReports = executionReports.filter(x => (x.nodeId==server))
	       if (nodeFilteredReports.filter(x => (( x.isInstanceOf[ResultErrorReport] || x.isInstanceOf[ResultRepairedReport] ) )).size == 0)
	       if (directiveExpectedReports.forall { directive => 
	         val linearised = linearisePolicyExpectedReports(directive)
	         val filteredReports = nodeFilteredReports.filter(x => x.directiveId == directive.directiveId && x.isInstanceOf[ResultSuccessReport])

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
   * a success node has at least one repaired, and no error, but must have
   * the EXACT number of success or repaired per component
   */
  def getRepairedNodeIds() : Seq[NodeId] = {
    cache.getOrElseUpdate("Repaired", {
	    (for {server <- expectedNodeIds;
	    	val nodeFilteredReports = executionReports.filter(x => (x.nodeId==server))
		    if (nodeFilteredReports.filter(x => ( x.isInstanceOf[ResultErrorReport]  ) ).size == 0)
		    if (nodeFilteredReports.filter(x => ( x.isInstanceOf[ResultRepairedReport]  ) ).size > 0)
		    if (directiveExpectedReports.forall { directive =>

		      val linearised = linearisePolicyExpectedReports(directive)
          val filteredReports = nodeFilteredReports.filter(x => 
            x.directiveId == directive.directiveId && 
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
   * a warn node have all the expected success report, and warn or error 
   */
 
  
  /**
   * a error node have not all the expected success report, and/or error 
   */
  def getErrorNodeIds() : Seq[NodeId] = {
    cache.getOrElseUpdate("Error", {
	    (for {server <- expectedNodeIds;
	       val nodeFilteredReports = executionReports.filter(x => (x.nodeId==server))
	       // if there is an error report, then it's an error
	       if ( (nodeFilteredReports.filter( x => x.isInstanceOf[ResultErrorReport] ).size > 0 ) ||
	         // or if there is at least a policy instance that is not valid
	         (directiveExpectedReports.exists { directive =>
	           val linearised = linearisePolicyExpectedReports(directive)
	           val filteredReports = nodeFilteredReports.filter(x => 
                x.directiveId == directive.directiveId && 
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
	         nodeFilteredReports.filter ( x =>  x.isInstanceOf[ResultSuccessReport] || 
	           																x.isInstanceOf[ResultRepairedReport] || 
	           																x.isInstanceOf[ResultErrorReport]
	           													).size > 0 ) 

	    } yield server).distinct
	   }) 
  }
  
  /**
   * A pending node is a node that was just configured, and we don't 
   * have answer yet
   */
  def getPendingNodeIds() : Seq[NodeId] = {
    if (beginDate.plus(Constants.pendingDuration).isAfter(DateTime.now())) {
      cache.getOrElseUpdate("Pending", {
        (for {nodeId <- expectedNodeIds;
           if (executionReports.filter(x => (x.nodeId==nodeId)).size == 0)
        } yield nodeId).distinct
      })
    } else {
      Seq()
    }
  }
  
  /**
   * A node with no reports should have send reports, but didn't
   */
  def getNoReportNodeIds() : Seq[NodeId] = {
    if (beginDate.plus(Constants.pendingDuration).isBefore(DateTime.now())) {
      cache.getOrElseUpdate("NoAnswer", {
        (for {nodeId <- expectedNodeIds;
           if (executionReports.filter(x => (x.nodeId==nodeId)).size == 0)
        } yield nodeId).distinct
      })
    } else {
      Seq()
    }
  }
  
  
  /**
   * An unknown node isn't success, repaired, error, pending nor no reports
   */
  def getUnknownNodeIds() : Seq[NodeId] = {
    expectedNodeIds.filter(nodeId => !(getSuccessNodeIds().contains(nodeId)))
                   .filter(nodeId => !(getRepairedNodeIds().contains(nodeId)))
                   .filter(nodeId => !(getErrorNodeIds().contains(nodeId)))
                   .filter(nodeId => !(getPendingNodeIds().contains(nodeId)))
                   .filter(nodeId => !(getNoReportNodeIds().contains(nodeId)))
    
  }
  
  private[this] def linearisePolicyExpectedReports(directive : DirectiveExpectedReports) : Buffer[LinearisedExpectedReport] = {
    val result = Buffer[LinearisedExpectedReport]()
    
    for (component <- directive.components) {
      for (value <- component.componentsValues) {
        result += LinearisedExpectedReport(
              directive.directiveId
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
    directiveId     : DirectiveId
  , componentName   : String
  , cardinality     : Int
  , componentValue  : String
) extends HashcodeCaching 
