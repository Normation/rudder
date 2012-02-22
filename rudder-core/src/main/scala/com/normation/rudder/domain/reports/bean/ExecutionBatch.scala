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

/**
 * An execution batch contains the servers reports for a given CR/PI at a given date
 * An execution Batch is at a given time <- Is it relevant when we have several node ?
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
   * Returns all the server that have only success reports
   * @return
   */
  def getSuccessNodeIds() : Seq[NodeId] 

  /**
   * Returns all the server that have repaired reports
   * @return
   */
  def getRepairedNodeIds() : Seq[NodeId]
  
  /**
   * Returns all the server that have  success reports, and some war/error
   * @return
   */
 // def getWarnNodeIds() : Seq[NodeId] 
  
  /**
   * Returns all the server that don't have enough success
   * @return
   */
  def getErrorNodeIds() : Seq[NodeId]
  
  /**
   * A pending server is a server that was just configured, and we don't 
   * have answer yet
   */
  def getPendingNodeIds() : Seq[NodeId]
  /**
   * return the server that did not send reports
   * @return
   */
  def getNoReportNodeIds() : Seq[NodeId]
  
  def getUnknownNodeIds() : Seq[NodeId]
}



/**
 * The execution batch for a configuration, still a lot of intelligence to add within
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
  
  val cache = scala.collection.mutable.Map[String, Seq[NodeId]]()
  
  
  /**
   * a success server have all the expected success report, 
   * for each component, and no warn nor error nor repaired
   */
  def getSuccessNodeIds() : Seq[NodeId] = {
    cache.getOrElseUpdate("Success", {
      (for {nodeId <- expectedNodeIds;
         val nodeFilteredReports = executionReports.filter(x => (x.nodeId==nodeId))
         if (nodeFilteredReports.filter(x => (( x.isInstanceOf[ResultErrorReport] || x.isInstanceOf[ResultRepairedReport] ) )).size == 0)
         if (directiveExpectedReports.forall { policy => 
           policy.components.forall { component => // must be true for each component
                 component.componentsValues.forall { value => // for each value
                   if (value == "None") {
                     nodeFilteredReports.filter( x => 
                       x.component == component.componentName &&
                       x.isInstanceOf[ResultSuccessReport]).size == component.cardinality
                   } else {
                     nodeFilteredReports.filter( x => 
                       x.component == component.componentName &&
                       x.keyValue == value &&
                       x.isInstanceOf[ResultSuccessReport]).size == 1
                   }
                 }
           }
         })
         //component <- policy.components
         //if (executionReports.filter(x => 
         //    (x.nodeId==server && x.component == component.componentName && x.isInstanceOf[ResultSuccessReport])).size >= component.cardinality)  
      } yield nodeId).distinct
    })
    
  }
  
  /**
   * a success server have at least one repaired, and no error, but must have
   * the EXACT number of success or repaired per component
   */
  def getRepairedNodeIds() : Seq[NodeId] = {
    cache.getOrElseUpdate("Repaired", {
      (for {nodeId <- expectedNodeIds;
        val nodeFilteredReports = executionReports.filter(x => (x.nodeId==nodeId))
        if (nodeFilteredReports.filter(x => ( x.isInstanceOf[ResultErrorReport]  ) ).size == 0)
        if (nodeFilteredReports.filter(x => ( x.isInstanceOf[ResultRepairedReport]  ) ).size > 0)
        if (directiveExpectedReports.forall { policy => 
          policy.components.forall { component => // must be true for each component
               component.componentsValues.forall { value => // for each value
                 if (value == "None") {
                   nodeFilteredReports.filter( x => 
                     x.component == component.componentName &&
                     (x.isInstanceOf[ResultSuccessReport] ||  x.isInstanceOf[ResultRepairedReport] )).size == component.cardinality
                 } else {
                   nodeFilteredReports.filter( x => 
                     x.component == component.componentName &&
                     x.keyValue == value &&
                     (x.isInstanceOf[ResultSuccessReport] ||  x.isInstanceOf[ResultRepairedReport] )).size == 1
                 }
               }
          }
         })
      } yield nodeId).distinct
   })  
     
  }
  
  /**
   * a warn server have all the expected success report, and warn or error 
   *//*
  def getWarnNodeIds() : Seq[NodeId] = {
    (for {server <- allExpectedNode;
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
  def getErrorNodeIds() : Seq[NodeId] = {
    cache.getOrElseUpdate("Error", {
      (for {nodeId <- expectedNodeIds;
         val nodeFilteredReports = executionReports.filter(x => (x.nodeId==nodeId))
  
         policy <- directiveExpectedReports
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
           }) && // must have results (otherwise it's a no answer)
           nodeFilteredReports.filter ( x =>  x.isInstanceOf[ResultSuccessReport] || 
                                             x.isInstanceOf[ResultRepairedReport] || 
                                             x.isInstanceOf[ResultErrorReport]
                                       ).size > 0 ) 
           
         /*component <- policy.components
         
         val filtered = executionReports.filter(x =>  (x.nodeId==server && x.component == component.componentName))
        
         if ( ( filtered.filter(x => x.isInstanceOf[ResultSuccessReport] || x.isInstanceOf[ResultRepairedReport] ).size > 0 ) && 
            ( filtered.filter(x => x.isInstanceOf[ResultSuccessReport]).size < component.cardinality ) &&
             ( filtered.filter(x => x.isInstanceOf[ResultRepairedReport]).size < component.cardinality ) ) ||
             ( filtered.filter(x => x.isInstanceOf[ResultErrorReport]).size > 0 )
         
  */
      } yield nodeId).distinct
     }) 
  }
  
  /**
   * A pending server is a server that was just configured, and we don't 
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
   * A server with no reports should have send reports, but didn't
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
}
