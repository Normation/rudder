/*
*************************************************************************************
* Copyright 2014 Normation SAS
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

package com.normation.rudder.web.services

import com.normation.rudder.domain.policies._
import com.normation.rudder.repository.FullActiveTechniqueCategory
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.nodes.NodeInfo
import scala.xml._
import net.liftweb.http._
import net.liftweb.common._
import com.normation.rudder.domain.reports._
import net.liftweb.util.Helpers._
import net.liftweb.util.Helpers
import net.liftweb.http.js.JsCmds._
import net.liftweb.http.js.JE._
import net.liftweb.http.js.JsCmd
import net.liftweb.http.js.JsExp
import bootstrap.liftweb.RudderConfig
import com.normation.rudder.web.components.popup.RuleCompliancePopup
import com.normation.cfclerk.domain.TechniqueVersion



/*
 * That files contains all the datastructures related to
 * compliance of different level of rules/nodes, and
 * that will be mapped to JSON
 *
 */

/*
 *   Javascript object containing all data to create a line in the DataTable
 *   { "rule" : Rule name [String]
 *   , "id" : Rule id [String]
 *   , "compliance" : compliance percent as String, not used in message popup [List[String]]
 *   , "details" : Details of components contained in the Directive [Array of Component values ]
 *   }
 */
case class RuleComplianceLine (
    name        : String
  , id          : RuleId
  , compliance  : ComplianceLevel
  , details     : JsTableData[DirectiveComplianceLine]
) extends JsTableLine {
  val json = {
    JsObj (
        ( "rule"       ->  name )
      , ( "compliance" -> jsCompliance(compliance) )
      , ( "id"         -> id.value )
      , ( "details"    -> details.json )
      //unique id, usable as DOM id - rules, directives, etc can
      //appear several time in a page
      , ( "jsid"       -> nextFuncName )
    )
  }
}

/*
 *   Javascript object containing all data to create a line in the DataTable
 *   { "directive" : Directive name [String]
 *   , "id" : Rule id [String]
 *   , "techniqueName": Name of the technique the Directive is based upon [String]
 *   , "techniqueVersion" : Version of the technique the Directive is based upon  [String]
 *   , "compliance" : compliance percent as String, not used in message popup [List[String]]
 *   , "details" : Details of components contained in the Directive [Array of Directive values ]
 *   , "callback" : Function to when clicking on compliance percent, not used in message popup [ Function ]
 *   }
 */
case class DirectiveComplianceLine (
    directive        : String
  , id               : DirectiveId
  , techniqueName    : String
  , techniqueVersion : TechniqueVersion
  , compliance       : ComplianceLevel
  , details          : JsTableData[ComponentComplianceLine]
  , callback         : Option[AnonFunc]
) extends JsTableLine {


  val callbackField = callback.map(cb => ( "callback" -> cb))

  val optFields : Seq[(String,JsExp)]= callbackField.toSeq

  val baseFields =  {
    JsObj (
        ( "directive"        -> directive )
      , ( "id"               -> id.value )
      , ( "techniqueName"    -> techniqueName )
      , ( "techniqueVersion" -> techniqueVersion.toString )
      , ( "compliance"       -> jsCompliance(compliance))
      , ( "details"          -> details.json )
      //unique id, usable as DOM id - rules, directives, etc can
      //appear several time in a page
      , ( "jsid"             -> nextFuncName )
    )
  }

  val json = baseFields +* JsObj(optFields:_*)

}


/*
 *   Javascript object containing all data to create a line in the DataTable
 *   { "node" : Directive name [String]
 *   , "id" : Rule id [String]
 *   , "compliance" : compliance percent as String, not used in message popup [List[String]]
 *   , "details" : Details of components contained in the Directive [Array of Component values ]
 *   }
 */
case class NodeComplianceLine (
    nodeInfo   : NodeInfo
  , compliance : ComplianceLevel
  , details    : JsTableData[ComponentComplianceLine]
) extends JsTableLine {
  val json = {
    JsObj (
        ( "node"       ->  nodeInfo.hostname )
      , ( "compliance" -> jsCompliance(compliance))
      , ( "id"         -> nodeInfo.id.value )
      , ( "details"    -> details.json )
      //unique id, usable as DOM id - rules, directives, etc can
      //appear several time in a page
      , ( "jsid"       -> nextFuncName )
    )
  }
}

/*
 *   Javascript object containing all data to create a line in the DataTable
 *   { "component" : component name [String]
 *   , "id" : id generated about that component [String]
 *   , "compliance" : compliance percent as String, not used in message popup [List[String]]
 *   , "details" : Details of values contained in the component [ Array of Component values ]
 *   , "noExpand" : The line should not be expanded if all values are "None", not used in message popup [Boolean]
 *   , "callback" : Function to when clicking on compliance percent, not used in message popup [ Function ]
 *   }
 */
case class ComponentComplianceLine (
    component   : String
  , id          : String
  , compliance  : ComplianceLevel
  , details     : JsTableData[ValueComplianceLine]
  , noExpand    : Boolean
  , callback    : Option[AnonFunc]
) extends JsTableLine {

  val callbackField = callback.map(cb => ( "callback" -> cb))
  val optFields : Seq[(String,JsExp)]= callbackField.toSeq

  val baseFields = {
    JsObj (
        ( "component"   -> component )
      , ( "id"          -> id )
      , ( "compliance"  -> jsCompliance(compliance))
      , ( "details"     -> details.json )
      , ( "noExpand"    -> noExpand )
      //unique id, usable as DOM id - rules, directives, etc can
      //appear several time in a page
      , ( "jsid"        -> nextFuncName )
    )
  }

  val json = baseFields +* JsObj(optFields:_*)
}

/*
 *   Javascript object containing all data to create a line in the DataTable
 *   { "value" : value of the key [String]
 *   , "compliance" : compliance percent as String, not used in message popup [String]
 *   , "status" : Worst status of the Directive [String]
 *   , "statusClass" : Class to use on status cell [String]
 *   , "callback" : Function to when clicking on compliance percent, not used in message popup [ Function ]
 *   , "message" : Message linked to that value, only used in message popup [ Array[String] ]
 *   }
 */
case class ValueComplianceLine (
    value       : String
  , messages    : List[String]
  , compliance  : ComplianceLevel
  , status      : String
  , statusClass : String
  , callback    : Option[AnonFunc]
) extends JsTableLine {

  val callbackField = callback.map(cb => ( "callback" -> cb))

  val optFields : Seq[(String,JsExp)]= callbackField.toSeq

  val baseFields = {
    JsObj (
        ( "value"       -> value )
      , ( "status"      -> status )
      , ( "statusClass" -> statusClass )
      , ( "messages"    -> JsArray(messages.map(Str)))
      , ( "compliance"  -> jsCompliance(compliance))
      //unique id, usable as DOM id - rules, directives, etc can
      //appear several time in a page
      , ( "jsid"        -> nextFuncName )
    )
  }

  val json = baseFields +* JsObj(optFields:_*)
}


object ComplianceData {

  /*
   * For a given rule, display compliance by nodes.
   * For each node, elements displayed are restraint
   *
   *
   */
  def getRuleByNodeComplianceDetails(
      directiveId : DirectiveId
    , reports     : Seq[RuleNodeStatusReport]
    , allNodeInfos: Map[NodeId, NodeInfo]
  ) : JsTableData[NodeComplianceLine]= {

    //sort reports by node, aggregate result
    val aggregates = reports.groupBy( _.nodeId).map { case(nodeId, seq) => (nodeId, AggregatedStatusReport(seq.toSet)) }

    // Compute node compliance detail
    val nodeComplianceLine = for {
      (nodeId, aggregate) <- aggregates
      nodeInfo            <- allNodeInfos.get(nodeId)
      //here, we are only interested on the report for directiveId
      directiveReport     <- aggregate.directives.get(directiveId)
    } yield {
      val details = getComponentsComplianceDetails(directiveReport.components.values.toSet, None, true)
      NodeComplianceLine(
          nodeInfo
        , directiveReport.compliance
        , details
      )
    }

    JsTableData(nodeComplianceLine.toList)
  }


  /*
   * For a given unique node, create the "by rule"
   * tree structure of compliance elements.
   * (rule -> directives -> components -> value with messages and status)
   */
  def getNodeByRuleComplianceDetails (
      nodeId      : NodeId
    , reports     : Seq[RuleNodeStatusReport]
    , allNodeInfos: Map[NodeId, NodeInfo]
    , directiveLib: FullActiveTechniqueCategory
    , rules       : Seq[Rule]
  ) : JsTableData[RuleComplianceLine] = {

    //aggregate by rules
    val aggregates = reports.filter( _.nodeId == nodeId).groupBy( _.ruleId).map { case(ruleId, seq) => (ruleId, AggregatedStatusReport(seq.toSet)) }

    val ruleComplianceLine = for {
      (ruleId, aggregate) <- aggregates
      rule                <- rules.find( _.id == ruleId )
    } yield {
      val details = getDirectivesComplianceDetails(aggregate.directives.values.toSet, directiveLib, None)

      RuleComplianceLine (
          rule.name
        , rule.id
        , aggregate.compliance
        , details
      )

    }
      JsTableData(ruleComplianceLine.toList)
  }


  //////////////// Directive Report ///////////////

  type DirCallback = DirectiveId => Option[String] => Option[String] => AnonFunc
  type CptCallback = Option[String] => Option[String] => AnonFunc
  type ValCallback = Option[String] => AnonFunc

  private[this] def buildCallback(
      allNodeInfos: Map[NodeId, NodeInfo]
    , directiveLib: FullActiveTechniqueCategory
    , reports     : Seq[RuleNodeStatusReport]
    , ruleName    : String
  ): DirCallback = {
    (directiveId: DirectiveId) => (componentName: Option[String]) => (valueName: Option[String]) =>
      AnonFunc("", SHtml.ajaxCall(JsNull, (s) => (new RuleCompliancePopup(directiveLib, allNodeInfos)).
          showPopup(reports, ruleName, directiveId, componentName, valueName))
      )
  }


  // From Rule Point of view
  def getRuleByDirectivesComplianceDetails (
      directivesReport: Seq[RuleNodeStatusReport]
    , rule            : Rule
    , allNodeInfos    : Map[NodeId, NodeInfo]
    , directiveLib    : FullActiveTechniqueCategory
  ) : JsTableData[DirectiveComplianceLine] = {

    val callback = buildCallback(allNodeInfos, directiveLib, directivesReport, rule.name)

    //we want to provide an aggregated view for the rule
    val aggregated = AggregatedStatusReport(directivesReport.toSet)

    getDirectivesComplianceDetails(aggregated.directives.values.toSet, directiveLib, Some(callback))
  }

  // From Node Point of view
  private[this] def getDirectivesComplianceDetails (
      directivesReport: Set[DirectiveStatusReport]
    , directiveLib    : FullActiveTechniqueCategory
    , optCallback     : Option[DirCallback]
  ) : JsTableData[DirectiveComplianceLine] = {
    val directivesComplianceData = for {
      directiveStatus <- directivesReport
      (fullActiveTechnique, directive) <- directiveLib.allDirectives.get(directiveStatus.directiveId)
    } yield {
      val techniqueName    = fullActiveTechnique.techniques.get(directive.techniqueVersion).map(_.name).getOrElse("Unknown technique")
      val techniqueVersion = directive.techniqueVersion;

      val (components, cb) = optCallback match {
        case Some(callback) =>
          val cptCallback = callback(directiveStatus.directiveId)
          (
              getComponentsComplianceDetails(directiveStatus.components.values.toSet, Some(cptCallback), false)
            , Some(cptCallback(None)(None))
          )
        case None =>
          (
              getComponentsComplianceDetails(directiveStatus.components.values.toSet, None, true)
            , None
          )
      }

      DirectiveComplianceLine (
          directive.name
        , directive.id
        , techniqueName
        , techniqueVersion : TechniqueVersion
        , directiveStatus.compliance
        , components
        , cb
      )
    }

    JsTableData(directivesComplianceData.toList)
  }
  //////////////// Component Report ///////////////


  // From Node Point of view
  private[this] def getComponentsComplianceDetails (
      components    : Set[ComponentStatusReport]
    , callback      : Option[CptCallback]
    , includeMessage: Boolean
  ) : JsTableData[ComponentComplianceLine] = {

    val componentsComplianceData = components.map { component =>

      val (optCallback, noExpand, values) = if(!includeMessage) {
        (None, true, getValuesComplianceDetails(component.componentValues.values.toSet, None))
      } else {
        val noExpand  = component.componentValues.forall( x => x._1 == "None")
        val valCallback = callback.map(c => c(Some(component.componentName)))

        (valCallback, noExpand, getValuesComplianceDetails(component.componentValues.values.toSet, valCallback))
      }

      ComponentComplianceLine(
          component.componentName
        , Helpers.nextFuncName
        , component.compliance
        , values
        , noExpand
        , optCallback.map(c => c(None))
      )
    }

    JsTableData(componentsComplianceData.toList)
  }

  //////////////// Value Report ///////////////


  // From Node Point of view
  private[this] def getValuesComplianceDetails (
      values  : Set[ComponentValueStatusReport]
    , callback: Option[ValCallback]
  ) : JsTableData[ValueComplianceLine] = {
    val valuesComplianceData = for {
      value <- values
    } yield {
      val severity = ReportType.getWorseType(value.messages.map( _.reportType))
      val status = getDisplayStatusFromSeverity(severity)
      val key = value.unexpandedComponentValue.getOrElse(value.componentValue)
      val messages = value.messages.flatMap( _.message)

      ValueComplianceLine(
          key
        , messages
        , value.compliance
        , status
        , severity
        , callback.map(cb => cb(Some(value.componentValue)))
      )
    }
    JsTableData(valuesComplianceData.toList)
  }

   private[this] def getDisplayStatusFromSeverity(severity: String) : String = {
    S.?(s"reports.severity.${severity}")
  }

}

