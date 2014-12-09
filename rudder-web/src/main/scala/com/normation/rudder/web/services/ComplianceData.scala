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
import com.normation.rudder.domain.reports.bean._
import net.liftweb.util.Helpers._
import net.liftweb.util.Helpers
import net.liftweb.http.js.JsCmds._
import net.liftweb.http.js.JE._
import net.liftweb.http.js.JsCmd
import net.liftweb.http.js.JsExp
import bootstrap.liftweb.RudderConfig
import com.normation.rudder.web.components.popup.RuleCompliancePopup
import com.normation.cfclerk.domain.TechniqueVersion

case class ComplianceData(
   directiveLib : FullActiveTechniqueCategory
  , allNodeInfos : Map[NodeId, NodeInfo]
) {

  private[this] val roRuleRepository = RudderConfig.roRuleRepository

  def popup(rule : Rule) = new RuleCompliancePopup(rule, directiveLib, allNodeInfos)

  /**
   * From a report:
   * - Transform it into components reports we can use
   * - Find all Nodes concerned in it
   * - Regroup those components reports by Node
   * - Compute node compliance detail
   */
  def getNodeComplianceDetails(
      report: RuleStatusReport
    , rule : Rule
  ) : JsTableData[NodeComplianceLine]= {

    // Transform report into components reports we can use
    val components : Seq[ComponentRuleStatusReport] = {
      report match {
        case DirectiveRuleStatusReport(_, components) => {
          components
        }
        case component : ComponentRuleStatusReport => {
          Seq(component)
        }
        case value: ComponentValueRuleStatusReport => {
          val component = ComponentRuleStatusReport(value.directiveId, value.component, Seq(value))
          Seq(component)
        }
      }
    }

    // Find all Nodes concerned in it
    val nodes = components.flatMap(_.componentValues.flatMap(_.nodesReport.map(_.node)))

    // Regroup those components reports by Node
    val componentsByNode : Map[NodeId, Seq[ComponentRuleStatusReport]] = {
      ( for {
        node <- nodes
      } yield {
        val componentsByNode =
          for {
            component <- components
          } yield {
            val valuesByNode =
              for {
                value <- component.componentValues
                nodeReports = value.nodesReport.filter(_.node == node)
              } yield {
                value.copy(nodesReport = nodeReports)
              }
            component.copy(componentValues = valuesByNode)
          }
        (node,componentsByNode)
      } ).toMap
    }

    // Compute node compliance detail
    val nodeComplianceLine = for {
      (nodeId,componentReports) <- componentsByNode
      nodeInfo <- allNodeInfos.get(nodeId)
    } yield {
      val severity = ReportType.getSeverityFromStatus(ReportType.getWorseType(componentReports.map(_.reportType)))
      val details = getComponentsComplianceDetails(componentReports, rule, true)
      val status = getDisplayStatusFromSeverity(severity)
      NodeComplianceLine(
          nodeInfo
        , status
        , severity
        , details
      )
    }

    JsTableData(nodeComplianceLine.toList)
  }


  ////////////// Rule compliance ///////////

  /*
   * Get compliance details of Rule
   */
  def getRuleComplianceDetails (
      reports : Seq[NodeStatusReport]
  ) : JsTableData[RuleComplianceLine] = {

    val ruleComplianceLine = for {
      report <- reports
      rule   <- roRuleRepository.get(report.ruleId)
    } yield {
      val severity = ReportType.getSeverityFromStatus(report.reportType)
      val status = getDisplayStatusFromSeverity(severity)
      val details = getDirectivesComplianceDetails(report.directives)

      RuleComplianceLine (
          rule
        , status
        , severity
        , details
      )

    }
      JsTableData(ruleComplianceLine.toList)
  }


  //////////////// Directive Report ///////////////


  // From Rule Point of view
  def getDirectivesComplianceDetails (
      directivesReport: Seq[DirectiveRuleStatusReport]
    , rule : Rule
    , includeMessage : Boolean
  ) : JsTableData[DirectiveComplianceLine] = {
    val directivesComplianceData = for {
      directiveStatus <- directivesReport
      (fullActiveTechnique, directive) <- directiveLib.allDirectives.get(directiveStatus.directiveId)
      techniqueName    = fullActiveTechnique.techniques.get(directive.techniqueVersion).map(_.name).getOrElse("Unknown technique")
      techniqueVersion = directive.techniqueVersion;
      severity   = ReportType.getSeverityFromStatus(directiveStatus.reportType)
      status     = getDisplayStatusFromSeverity(directiveStatus.reportType)
      components = getComponentsComplianceDetails (directiveStatus.components, rule,includeMessage)
    } yield {
      val ajaxCall = SHtml.ajaxCall(JsNull, (s) => popup(rule).showPopup(directiveStatus))
      val callback = AnonFunc("",ajaxCall)

      DirectiveComplianceLine (
          directive
        , techniqueName
        , techniqueVersion
        , Some(buildComplianceChart(directiveStatus))
        , status
        , severity
        , components
        , Some(callback)
      )
    }

    JsTableData(directivesComplianceData.toList)
  }

  // From Node Point of view
  def getDirectivesComplianceDetails (
    directivesReport: Seq[DirectiveStatusReport]
  ) : JsTableData[DirectiveComplianceLine] = {
    val directivesComplianceData = for {
      directiveStatus <- directivesReport
      (fullActiveTechnique, directive) <- directiveLib.allDirectives.get(directiveStatus.directiveId)
      techniqueName    = fullActiveTechnique.techniques.get(directive.techniqueVersion).map(_.name).getOrElse("Unknown technique")
      techniqueVersion = directive.techniqueVersion;
      severity   = ReportType.getSeverityFromStatus(directiveStatus.reportType)
      status     = getDisplayStatusFromSeverity(directiveStatus.reportType)
      components = getComponentsComplianceDetails (directiveStatus.components)
    } yield {

      DirectiveComplianceLine (
          directive
        , techniqueName
        , techniqueVersion
        , None
        , status
        , severity
        , components
        , None
      )
    }

    JsTableData(directivesComplianceData.toList)
  }
  //////////////// Component Report ///////////////


  // From Rule Point of view
  /*
   * Get compliance details of Components of a Directive
   */
  def getComponentsComplianceDetails (
      components : Seq[ComponentRuleStatusReport]
    , rule : Rule
    , includeMessage : Boolean
  ) : JsTableData[ComponentComplianceLine] = {

    val componentsComplianceData = for {
      component <- components
      severity  = ReportType.getSeverityFromStatus(component.reportType)
      id        = Helpers.nextFuncName
      status    = getDisplayStatusFromSeverity(severity)
      values    = getValuesComplianceDetails (component.componentValues, rule,includeMessage)
    } yield {
      val (optCallback, optNoExpand, optCompliance) = {
        if (includeMessage) {
          (None,None,None)
        } else {
          val ajaxCall = SHtml.ajaxCall(JsNull, (s) => popup(rule).showPopup(component))
          val compliance =  buildComplianceChart(component)
          val callback = AnonFunc("",ajaxCall)
          // do not display details of the components if there is no value to display (all equals to None)
          val noExpand  = component.componentValues.forall( x => x.componentValue =="None")
          (Some(callback),Some(noExpand),Some(compliance))
        }
      }

      ComponentComplianceLine (
          component.component
        , id
        , optCompliance
        , status
        , severity
        , values
        , optNoExpand
        , optCallback
      )

    }
      JsTableData(componentsComplianceData.toList)
  }

  // From Node Point of view
  def getComponentsComplianceDetails (
      components : Seq[ComponentStatusReport]
  ) : JsTableData[ComponentComplianceLine] = {

    val componentsComplianceData = for {
      component <- components
      severity  = ReportType.getSeverityFromStatus(component.reportType)
      id        = Helpers.nextFuncName
      status    = getDisplayStatusFromSeverity(severity)
      values    = getValuesComplianceDetails (component.componentValues)
    } yield {

      ComponentComplianceLine (
          component.component
        , id
        , None
        , status
        , severity
        , values
        , None
        , None
      )

    }
      JsTableData(componentsComplianceData.toList)
  }

  //////////////// Value Report ///////////////


  // From Rule Point of view
  /*
   * Get compliance details of Value of a Component
   */
  def getValuesComplianceDetails (
      values : Seq[ComponentValueRuleStatusReport]
    , rule : Rule
    , includeMessage : Boolean
  ) : JsTableData[ValueComplianceLine] = {
    val valuesComplianceData = for {
      // we need to group all reports by their key ( if there is several reports with the same key)
      (key,entries) <- values.groupBy { entry => entry.key }
      component =
        ComponentRuleStatusReport (
            entries.head.directiveId // can't fail because we are in a groupBy
          , entries.head.component  // can't fail because we are in a groupBy
          , entries
        )
      severity = ReportType.getSeverityFromStatus(component.reportType)
      status = getDisplayStatusFromSeverity(severity)
    } yield {      val (optCallback, optCompliance, optMessage) = {
        if (includeMessage) {
          val messages = component.componentValues.flatMap(_.nodesReport.flatMap(_.message))
          (None, None, Some(messages.toList))
        } else {
          val ajaxCall = SHtml.ajaxCall(JsNull, (s) => popup(rule).showPopup(component))
          val compliance =  buildComplianceChart(component)
          val callback = AnonFunc("",ajaxCall)
          // do not display details of the components if there is no value to display (all equals to None)
          val noExpand  = component.componentValues.forall( x => x.componentValue =="None")
          (Some(callback), Some(compliance), None)
        }
      }
      ValueComplianceLine(
          key
        , optCompliance
        , status
        , severity
        , optCallback
        , optMessage
      )
    }

    JsTableData(valuesComplianceData.toList)
  }

  // From Node Point of view
  def getValuesComplianceDetails (
      values : Seq[ComponentValueStatusReport]
  ) : JsTableData[ValueComplianceLine] = {
    val valuesComplianceData = for {
      value <- values
      severity = ReportType.getSeverityFromStatus(value.reportType)
      status = getDisplayStatusFromSeverity(severity)
    } yield {
      val key = value.unexpandedComponentValue.getOrElse(value.componentValue)
      val message = Some(value.message)
      ValueComplianceLine(
          key
        , None
        , status
        , severity
        , None
        , message
      )
    }

    JsTableData(valuesComplianceData.toList)
  }

  // Helpers function

  def buildComplianceChart(rulestatusreport:RuleStatusReport) = {
    rulestatusreport.computeCompliance match {
      case Some(percent) => s"${percent}%"
      case None => "Not Applied"
    }
  }

  def getDisplayStatusFromSeverity(severity: String) : String = {
    S.?(s"reports.severity.${severity}")
  }

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
  , compliance  : Option[String]
  , status      : String
  , statusClass : String
  , callback    : Option[AnonFunc]
  , messages    : Option[List[String]]
) extends JsTableLine {

  val complianceField =  compliance.map(r => ( "compliance" -> Str(r)))

  val messageField = messages.map(msgs => ( "message" -> JsArray(msgs.map(Str))))

  val callbackField = callback.map(cb => ( "callback" -> cb))

  val optFields : Seq[(String,JsExp)]= complianceField.toSeq ++ messageField ++ callbackField

  val baseFields = {
    JsObj (
        ( "value"       -> value )
      , ( "status"      -> status )
      , ( "statusClass" -> statusClass )
    )
  }

  val json = baseFields +* JsObj(optFields:_*)
}

/*
 *   Javascript object containing all data to create a line in the DataTable
 *   { "component" : component name [String]
 *   , "id" : id generated about that component [String]
 *   , "compliance" : compliance percent as String, not used in message popup [String]
 *   , "status" : Worst status of the Directive [String]
 *   , "statusClass" : Class to use on status cell [String]
 *   , "details" : Details of values contained in the component [ Array of Component values ]
 *   , "noExpand" : The line should not be expanded if all values are "None", not used in message popup [Boolean]
 *   , "callback" : Function to when clicking on compliance percent, not used in message popup [ Function ]
 *   }
 */
case class ComponentComplianceLine (
    component   : String
  , id          : String
  , compliance  : Option[String]
  , status      : String
  , statusClass : String
  , details     : JsTableData[ValueComplianceLine]
  , noExpand    : Option[Boolean]
  , callback    : Option[AnonFunc]
) extends JsTableLine {

  val complianceField =  compliance.map(r => ( "compliance" -> Str(r)))

  val noExpandField = noExpand.map(cb => ( "noExpand" -> boolToJsExp(cb)))

  val callbackField = callback.map(cb => ( "callback" -> cb))

  val optFields : Seq[(String,JsExp)]= complianceField.toSeq ++ noExpandField ++ callbackField


  val baseFields = {
    JsObj (
        ( "component"   -> component )
      , ( "id"          -> id )
      , ( "status"      -> status )
      , ( "statusClass" -> statusClass )
      , ( "details"     -> details.json )
    )
  }

   val json = baseFields +* JsObj(optFields:_*)
}

/*
 *   Javascript object containing all data to create a line in the DataTable
 *   { "directive" : Directive name [String]
 *   , "id" : Directive id [String]
 *   , "compliance" : compliance percent as String [String]
 *   , "techniqueName": Name of the technique the Directive is based upon [String]
 *   , "techniqueVersion" : Version of the technique the Directive is based upon  [String]
 *   , "status" : Worst status of the Directive [String]
 *   , "statusClass" : Class to use on status cell [String]
 *   , "details" : Details of components contained in the Directive [Array of Directive values ]
 *   , "callback" : Function to when clicking on compliance percent [ Function ]
 *   , "isSystem" : Is it a system Directive? [Boolean]
 *   }
 */
case class DirectiveComplianceLine (
    directive        : Directive
  , techniqueName    : String
  , techniqueVersion : TechniqueVersion
  , compliance       : Option[String]
  , status           : String
  , statusClass      : String
  , details          : JsTableData[ComponentComplianceLine]
  , callback         : Option[AnonFunc]
) extends JsTableLine {

  val complianceField =  compliance.map(r => ( "compliance" -> Str(r)))

  val callbackField = callback.map(cb => ( "callback" -> cb))

  val optFields : Seq[(String,JsExp)]= complianceField.toSeq  ++ callbackField

  val baseFields =  {
    JsObj (
        ( "directive"        -> directive.name )
      , ( "id"               -> directive.id.value )
      , ( "techniqueName"    -> techniqueName )
      , ( "techniqueVersion" -> techniqueVersion.toString )
      , ( "status"           -> status )
      , ( "statusClass"      -> statusClass )
      , ( "details"          -> details.json )
      , ( "isSystem"         -> directive.isSystem )
    )
  }

  val json = baseFields +* JsObj(optFields:_*)

}

/*
 *   Javascript object containing all data to create a line in the DataTable
 *   { "node" : Directive name [String]
 *   , "id" : Rule id [String]
 *   , "status" : Worst status of the Directive [String]
 *   , "statusClass" : Class to use on status cell [String]
 *   , "details" : Details of components contained in the Directive [Array of Component values ]
 *   }
 */
case class NodeComplianceLine (
    nodeInfo         : NodeInfo
  , status           : String
  , statusClass      : String
  , details          : JsTableData[ComponentComplianceLine]
) extends JsTableLine {
  val json = {
    JsObj (
        ( "node" ->  nodeInfo.hostname )
      , ( "status" -> status )
      , ( "statusClass" -> statusClass )
      , ( "id" -> nodeInfo.id.value )
      , ( "details" -> details.json )
    )
  }
}

/*
 *   Javascript object containing all data to create a line in the DataTable
 *   { "rule" : Rule name [String]
 *   , "id" : Rule id [String]
 *   , "status" : Worst status of the Rule [String]
 *   , "statusClass" : Class to use on status cell [String]
 *   , "details" : Details of directives contained in the Rule [Array of Directives ]
 *   , "isSystem" : is it a system Rule? [Boolean]
 *   }
 */
case class RuleComplianceLine (
    rule        : Rule
  , status      : String
  , statusClass : String
  , details     : JsTableData[DirectiveComplianceLine]
) extends JsTableLine {
  val json = {
    JsObj (
        ( "rule" ->  rule.name )
      , ( "status" -> status )
      , ( "statusClass" -> statusClass )
      , ( "id" -> rule.id.value )
      , ( "details" -> details.json )
      , ( "isSystem" -> rule.isSystem )
    )
  }
}
