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
import com.normation.rudder.domain.logger.ReportLogger

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
  
  def getNodeStatus() : Seq[NodeStatusReport]
  
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
   
  val nodeStatus = scala.collection.mutable.Map[String, Seq[NodeStatusReport]]()
  
  /**
   * This is the main entry point to get the detailed reporting
   * It returns a Sequence of NodeStatusReport which gives, for
   * each node, the status and all the directives associated
   *
   */
  def getNodeStatus() : Seq[NodeStatusReport] = {
    nodeStatus.getOrElseUpdate("nodeStatus", 
      (for {
        nodeId <- expectedNodeIds
        val nodeFilteredReports = executionReports.filter(x => (x.nodeId==nodeId))
       
        val directiveStatusReports  = for {
          expectedDirective <- directiveExpectedReports
          val directiveFilteredReports = nodeFilteredReports.filter(x => x.directiveId == expectedDirective.directiveId)
          
          // look for each component
          val componentsStatus = for {
            expectedComponent <- expectedDirective.components
            val componentFilteredReports = directiveFilteredReports.filter(x => x.component == expectedComponent.componentName)
            
            val componentStatusReport = checkExpectedComponentWithReports(
                    expectedComponent
                  , componentFilteredReports
                  , nodeId
            )
          } yield {
            componentStatusReport
          }
          
          val directiveStatusReport = DirectiveStatusReport(
              expectedDirective.directiveId
            , componentsStatus
            , ReportType.getWorseType(componentsStatus.map(x => x.componentReportType))
            , Seq()
          )
          
        } yield {
          directiveStatusReport
        }
        
        val nodeStatusReport = NodeStatusReport(
            nodeId
          , ruleId
          , directiveStatusReports
          , ReportType.getWorseType(directiveStatusReports.map(x => x.directiveReportType))
          , Seq()
        )
      } yield {
        nodeStatusReport
      })
    )
  }
  
  protected[bean] def checkExpectedComponentWithReports(
      expectedComponent : ReportComponent
    , filteredReports   : Seq[Reports]
    , nodeId            : NodeId
  ) : ComponentStatusReport = {
    
    // easy case : No Reports mean no answer
    filteredReports.size match {
      case 0 => 
        val components = for {
          component <- expectedComponent.componentsValues
        } yield {
          ComponentValueStatusReport(
              component
            , getNoAnswerOrPending()
            , nodeId
          ) 
        }
        ComponentStatusReport(
            expectedComponent.componentName
          , components
          , getNoAnswerOrPending()
          , Seq()
        )
      case _ => 
        // First, filter out all the not interesting reports
        val purgedReports = filteredReports.filter(x => x.isInstanceOf[ResultErrorReport] 
                               || x.isInstanceOf[ResultRepairedReport]  
                               || x.isInstanceOf[ResultSuccessReport]
                               || x.isInstanceOf[UnknownReport])
      
                               
        val components = for {
            componentValue <- expectedComponent.componentsValues
            val status = checkExpectedComponentStatus(
                              expectedComponent
                            , componentValue
                            , purgedReports)
        } yield {
          ComponentValueStatusReport(
                componentValue
              , status
              , nodeId)
        }
        
        // must fetch extra entries
        val unexpectedReports = getUnexpectedReports(
            expectedComponent.componentsValues.toList
          , purgedReports
        )

        unexpectedReports.size match {
          case 0 =>
            ComponentStatusReport(
                expectedComponent.componentName
              , components
              , ReportType.getWorseType(components.map(x => x.cptValueReportType))
              , Seq() 
            )

          case _ => // some bad report
            unexpectedReports.foreach{ invalidReport =>
              ReportLogger.warn("Unexpected report for Directive %s, Rule %s generated on %s on node %s, Component is %s, keyValue is %s. The associated message is : %s".format(
                  invalidReport.directiveId
                , invalidReport.ruleId
                , invalidReport.executionTimestamp
                , invalidReport.nodeId
                , invalidReport.component
                , invalidReport.keyValue
                , invalidReport.message))
            }
             ComponentStatusReport(
                expectedComponent.componentName
              , components
              , UnknownReportType
              , Seq() // TODO : handle unexpected
            )
        }
      
    }
  }
  

  private[this] def returnWorseStatus(
      reports : Seq[Reports]
  ) : ReportType = {
    if (reports.exists(x => x.isInstanceOf[ResultErrorReport])) {
      ErrorReportType
    } else {
      if (reports.exists(x => x.isInstanceOf[UnknownReport])) {
        UnknownReportType
      } else {
        if (reports.exists(x => x.isInstanceOf[ResultRepairedReport])) {
          RepairedReportType
        } else {
          if (reports.exists(x => x.isInstanceOf[ResultSuccessReport])) {
            SuccessReportType
          } else {
            getNoAnswerOrPending()
          }
        }
      }
    }
    
  }
  /*
   * An utility method that check for the size of result compared to
   * expected one. 
   * We let three condition parameters:
   */
  private[this] def checkExpectedComponentValueSize(
      linearised             : Seq[LinearisedExpectedReport]
    , filteredReports        : Seq[Reports]
      // define what will be the condition on the linearised expected reports : forall or exists
    , linearisedTestType     : (Seq[LinearisedExpectedReport],LinearisedExpectedReport=>Boolean) => Boolean 
      // define OP so that: filteredReportsCard:Int OP expectedCard:Int
    , whenNoneCaseCondition  : (Int,Int) => Boolean 
      // define OP so that: 'filteredReportsCard' OP card:Int
    , whenCfeVarCaseCondition: Int => Boolean
      // define OP so that: 'filteredReportsCard' OP card:Int
    , whenStringCaseCondition: Int => Boolean
  ) : Boolean = {
    
    linearisedTestType(linearised, expected =>
      expected.componentValue match {
        case "None" =>
          // each non defined component key must have the right cardinality, no more, no less
          whenNoneCaseCondition(
              filteredReports.filter( x => x.component == expected.componentName).size 
            , expected.cardinality
          )
        case matchCFEngineVars(_) =>
          // this is a case when we have a CFEngine Variable
          val matchableExpected = expected.componentValue.replaceAll(replaceCFEngineVars, ".*")
          // We've converted the string into a regexp, by replacing ${} and $() by .*
          whenCfeVarCaseCondition(filteredReports.filter( x =>  
               x.component == expected.componentName 
            && x.keyValue.matches(matchableExpected)
          ).size)
        case _:String =>
          // for a given component, if the key is not "None", then we are 
          // checking that what is have is what we wish
          // we can have more reports that what we expected, because of
          // name collision, but it would be resolved by the total number 
          whenStringCaseCondition(filteredReports.filter( x => 
               x.component == expected.componentName 
            && x.keyValue == expected.componentValue
          ).size)
      }
    )
  }
  
  
  /*
   * An utility method that fetch the proper status
   * of a component key. 
   * Parameters :
   * expectedComponent : the expected component (obviously)
   * currentValue : the current keyValue processes
   * filteredReports : the report for that component (but including all keys)
   */
  protected def checkExpectedComponentStatus(
      expectedComponent      : ReportComponent
    , currentValue           : String
    , filteredReports        : Seq[Reports]
  ) : ReportType = {
    currentValue match {
      case "None" =>
        filteredReports.filter( x => x.keyValue == currentValue)
               .filter( x => x.isInstanceOf[ResultErrorReport]).size match {
          case i if i > 0 => ErrorReportType
          case _ => {
            filteredReports.size match {
              case 0 => getNoAnswerOrPending()
              case x if x == expectedComponent.cardinality => 
                returnWorseStatus(filteredReports)
              case _ => UnknownReportType
            }
          }
        }
            
      case matchCFEngineVars(_) => 
        // convert the entry to regexp, and match what can be matched
         val matchableExpected = currentValue.replaceAll(replaceCFEngineVars, ".*")
         val matchedReports = filteredReports.filter( x => x.keyValue.matches(matchableExpected))
         
         matchedReports.filter( x => x.isInstanceOf[ResultErrorReport]).size match {
           case i if i > 0 => ErrorReportType
           case _ => {
            matchedReports.size match {
              case 0 => getNoAnswerOrPending()
              case x if x == expectedComponent.componentsValues.filter( x => x.matches(matchableExpected)).size => 
                returnWorseStatus(filteredReports)
                // all similar cfengine variable are undistinguable
              case _ => UnknownReportType
            }
          }
         }
         
      case _: String => 
          // for a given component, if the key is not "None", then we are 
          // checking that what is have is what we wish
          // we can have more reports that what we expected, because of
          // name collision, but it would be resolved by the total number
        val keyReports =  filteredReports.filter( x => x.keyValue == currentValue)
        keyReports.filter( x => x.isInstanceOf[ResultErrorReport]).size match {
          case i if i > 0 => ErrorReportType
          case _ => {
            keyReports.size match {
              case 0 => getNoAnswerOrPending()
              case x if x == expectedComponent.componentsValues.filter( x => x == currentValue).size => 
                returnWorseStatus(keyReports)
              case _ => UnknownReportType
            }
          }
        }
    }
    
  }
  
  /**
   * Retrieve all the reports that should not be there (due to
   * keyValue not present)
   */
  private[this] def getUnexpectedReports(
      keyValues      : List[String]
    , reports        : Seq[Reports]
    ) : Seq[Reports] = {
    keyValues match {
      case Nil => reports
      case head :: tail =>
        head match {
          case matchCFEngineVars(_) =>
            val matchableExpected = head.replaceAll(replaceCFEngineVars, ".*")
            getUnexpectedReports(
                tail
              , reports.filterNot(x => x.keyValue.matches(matchableExpected)) )
          case s: String =>
            getUnexpectedReports(
                tail
              , reports.filterNot(x => x.keyValue == s ) )
        }
    }
  }
  
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
           checkExpectedComponentValueSize(
              linearised
            , filteredReports
            , linearisedTestType      = (seq,cond) => seq.forall(cond)
            , whenNoneCaseCondition   = (x,y) => x == y
            , whenCfeVarCaseCondition =  x    => x >= 1 // >= 1 for we accept at least one, but we check that we dont have too much by summing
            , whenStringCaseCondition =  x    => x >= 1 // >= 1 for we accept at least one, but we check that we dont have too much by summing
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
           checkExpectedComponentValueSize(
              linearised
            , filteredReports
            , linearisedTestType      = (seq,cond) => seq.forall(cond)
            , whenNoneCaseCondition   = (x,y) => x >= y
            , whenCfeVarCaseCondition =  x    => x >= 1 // >= 1 for we accept at least one, but we check that we dont have too much by summing
            , whenStringCaseCondition =  x    => x >= 1 // >= 1 for we accept at least one, but we check that we dont have too much by summing
           )
         })
      } yield server).distinct
   })  
     
  }
  
  /**
   * a warn node have all the expected success report, and warn or error 
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
   * a error node have not all the expected success report, and/or error 
   */
  def getErrorNodeIds() : Seq[NodeId] = {
    cache.getOrElseUpdate("Error", {
	    (for {server <- expectedNodeIds;
	       val nodeFilteredReports = executionReports.filter(x => (x.nodeId==server))
	       // if there is an error report, then it's an error
	       if ( (nodeFilteredReports.filter( x => x.isInstanceOf[ResultErrorReport] ).size > 0 ) ||
	         // or if there is at least a directive that is not valid
	         (directiveExpectedReports.exists { directive =>
	           val linearised = linearisePolicyExpectedReports(directive)
	           val filteredReports = nodeFilteredReports.filter(x => 
                x.directiveId == directive.directiveId && 
                ( x.isInstanceOf[ResultSuccessReport] ||  x.isInstanceOf[ResultRepairedReport])
              )
              // we shouldn't have less reports that those we really expected (repaired and success)
              filteredReports.size < linearised.size ||
               checkExpectedComponentValueSize(
                  linearised
                , filteredReports
                , linearisedTestType      = (seq,cond) => seq.exists(cond)
                , whenNoneCaseCondition   = (x,y) => x < y
                , whenCfeVarCaseCondition =  x    => x == 0 // no match 
                , whenStringCaseCondition =  x    => x == 0 // no match 
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
    expectedNodeIds.filter(node => 
                           !(getSuccessNodeIds().contains(node))
                        && !(getRepairedNodeIds().contains(node))
                        && !(getErrorNodeIds().contains(node))
                        && !(getPendingNodeIds().contains(node))
                        && !(getNoReportNodeIds().contains(node))
                      )
    
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
  
  private[this] def getNoAnswerOrPending() : ReportType = {
    if (beginDate.plus(Constants.pendingDuration).isAfter(DateTime.now())) {
      PendingReportType
    } else {
      NoAnswerReportType
    }
  }
}

case class LinearisedExpectedReport(
    directiveId     : DirectiveId
  , componentName   : String
  , cardinality     : Int
  , componentValue  : String
) extends HashcodeCaching 


/**
 * For a component value, store the report status
 */
case class ComponentValueStatusReport(
    componentValue 		: String
  , cptValueReportType: ReportType
  , nodeId				    : NodeId
)

/**
 * For a component, store the report status, as the worse status of the component
 * Or error if there is an unexpected component value
 */
case class ComponentStatusReport(
    component           : String
  , componentValues		  : Seq[ComponentValueStatusReport]
  , componentReportType : ReportType
  , unexpectedCptValues : Seq[ComponentValueStatusReport]
)


case class DirectiveStatusReport(
    directiveId          : DirectiveId
  , components			     : Seq[ComponentStatusReport]
  , directiveReportType  : ReportType
  , unexpectedComponents : Seq[ComponentStatusReport]
)

case class NodeStatusReport(
    nodeId               : NodeId
  , ruleId               : RuleId
  , directives			     : Seq[DirectiveStatusReport]
  , nodeReportType		   : ReportType
  , unexpectedDirectives : Seq[DirectiveStatusReport]
)
