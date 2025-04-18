/*
 *************************************************************************************
 * Copyright 2014 Normation SAS
 *************************************************************************************
 *
 * This file is part of Rudder.
 *
 * Rudder is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * In accordance with the terms of section 7 (7. Additional Terms.) of
 * the GNU General Public License version 3, the copyright holders add
 * the following Additional permissions:
 * Notwithstanding to the terms of section 5 (5. Conveying Modified Source
 * Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU General
 * Public License version 3, when you create a Related Module, this
 * Related Module is not considered as a part of the work and may be
 * distributed under the license agreement of your choice.
 * A "Related Module" means a set of sources files including their
 * documentation that, without modification of the Source Code, enables
 * supplementary functions or services in addition to those offered by
 * the Software.
 *
 * Rudder is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Rudder.  If not, see <http://www.gnu.org/licenses/>.

 *
 *************************************************************************************
 */

package com.normation.rudder.web.services

import com.normation.cfclerk.domain.ReportingLogic
import com.normation.cfclerk.domain.TechniqueVersion
import com.normation.cfclerk.xmlparsers.CfclerkXmlConstants.DEFAULT_COMPONENT_KEY
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies.*
import com.normation.rudder.domain.reports.*
import com.normation.rudder.facts.nodes.CoreNodeFact
import com.normation.rudder.repository.FullActiveTechniqueCategory
import com.normation.rudder.repository.FullNodeGroupCategory
import com.normation.utils.DateFormaterService
import net.liftweb.common.*
import net.liftweb.http.*
import net.liftweb.http.js.JE.*
import net.liftweb.http.js.JsExp
import net.liftweb.json.JsonAST.JValue
import net.liftweb.util.Helpers.*
import org.joda.time.Interval
import scala.collection.MapView

/*
 * That files contains all the datastructures related to
 * compliance of different level of rules/nodes, and
 * that will be mapped to JSON
 *
 */

/*
 *   Javascript object containing all data to create a line about a change in the DataTable
 *   { "nodeName" : Name of the node [String]
 *   , "message" : Messages linked to that change [String]
 *   , "directiveName" : Name of the directive [String]
 *   , "component" : Component name [String]
 *   , "value": Value of the change [String]
 *   , "executionDate" : date the report was run on the Node [String]
 *   }
 */
final case class ChangeLine(
    report:        ResultReports,
    nodeName:      Option[String] = None,
    ruleName:      Option[String] = None,
    directiveName: Option[String] = None
) extends JsTableLine {
  val json: js.JsObj = {
    JsObj(
      ("nodeName"      -> JsExp.strToJsExp(nodeName.getOrElse(report.nodeId.value))),
      ("message"       -> escapeHTML(report.message)),
      ("directiveName" -> escapeHTML(directiveName.getOrElse(report.directiveId.serialize))),
      ("component"     -> escapeHTML(report.component)),
      ("value"         -> escapeHTML(report.keyValue)),
      ("executionDate" -> DateFormaterService.getDisplayDate(report.executionTimestamp))
    )
  }
}

object ChangeLine {
  def jsonByInterval(
      changes:      Map[Interval, Seq[ResultReports]],
      ruleName:     Option[String] = None,
      directiveLib: FullActiveTechniqueCategory,
      allNodeInfos: Map[NodeId, CoreNodeFact]
  ): JsArray = {

    val jsonChanges = {
      for {
        // Sort changes by interval so we can use index to select changes
        (interval, changesOnInterval) <- changes.toList.sortWith {
                                           case ((i1, _), (i2, _)) => i1.getStart() isBefore i2.getStart()
                                         }

      } yield {
        val lines = for {
          change       <- changesOnInterval
          nodeName      = allNodeInfos.get(change.nodeId).map(_.fqdn)
          directiveName = directiveLib.allDirectives.get(change.directiveId).map(_._2.name)
        } yield {
          ChangeLine(change, nodeName, ruleName, directiveName)
        }

        JsArray(lines.toList.map(_.json))
      }
    }

    JsArray(jsonChanges.toSeq*)
  }
}

/*
 *   Javascript object containing all data to create a line in the DataTable
 *   { "rule" : Rule name [String]
 *   , "id" : Rule id [String]
 *   , "compliance" : array of number of reports by compliance status [Array[Float]]
 *   , "details" : Details of Directives contained in the Rule [Array of Directive values]
 *   , "jsid"    : unique identifier for the line [String]
 *   , "isSystem" : Is it a system Rule? [Boolean]
 *   , "policyMode" : Directive policy mode [String]
 *   , "explanation" : Policy mode explanation [String]
 *   }
 */
final case class RuleComplianceLine(
    rule:            Rule,
    id:              RuleId,
    compliance:      ComplianceLevel,
    details:         JsTableData[DirectiveComplianceLine],
    policyMode:      String,
    modeExplanation: String,
    tags:            JValue
) extends JsTableLine {
  val json: js.JsObj = {
    JsObj(
      ("rule"              -> escapeHTML(rule.name)),
      ("compliance"        -> jsCompliance(compliance)),
      ("compliancePercent" -> compliance.computePercent().compliance),
      ("id"                -> escapeHTML(rule.id.serialize)),
      ("details"           -> details.json), // unique id, usable as DOM id - rules, directives, etc can
      // appear several time in a page

      ("jsid"        -> nextFuncName),
      ("isSystem"    -> rule.isSystem),
      ("policyMode"  -> policyMode),
      ("explanation" -> modeExplanation),
      ("tags"        -> tags)
    )
  }
}

/*
 *   Javascript object containing all data to create a line in the DataTable
 *   { "directive" : Directive name [String]
 *   , "id" : Directive id [String]
 *   , "techniqueName": Name of the technique the Directive is based upon [String]
 *   , "techniqueVersion" : Version of the technique the Directive is based upon  [String]
 *   , "compliance" : array of number of reports by compliance status [Array[Float]]
 *   , "compliancePercent" : Compliance percentage [Float]
 *   , "details" : Details of components contained in the Directive [Array of Component values]
 *   , "jsid"    : unique identifier for the line [String]
 *   , "isSystem" : Is it a system Directive? [Boolean]
 *   , "policyMode" : Directive policy mode [String]
 *   , "explanation" : Policy mode explanation [String]
 *   }
 */
final case class DirectiveComplianceLine(
    directive:        Directive,
    techniqueName:    String,
    techniqueVersion: TechniqueVersion,
    compliance:       ComplianceLevel,
    details:          JsTableData[ComponentComplianceLine],
    policyMode:       String,
    modeExplanation:  String,
    tags:             JValue
) extends JsTableLine {
  val json: js.JsObj = {
    JsObj(
      ("directive"         -> escapeHTML(directive.name)),
      ("id"                -> escapeHTML(directive.id.uid.value)),
      ("techniqueName"     -> escapeHTML(techniqueName)),
      ("techniqueVersion"  -> escapeHTML(techniqueVersion.serialize)),
      ("compliance"        -> jsCompliance(compliance)),
      ("compliancePercent" -> compliance.computePercent().compliance),
      ("details"           -> details.json), // unique id, usable as DOM id - rules, directives, etc can
      // appear several time in a page

      ("jsid"        -> nextFuncName),
      ("isSystem"    -> directive.isSystem),
      ("policyMode"  -> policyMode),
      ("explanation" -> modeExplanation),
      ("tags"        -> tags)
    )
  }
}

/*
 *   Javascript object containing all data to create a line in the DataTable
 *   { "node" : Node name [String]
 *   , "id" : Node id [String]
 *   , "compliance" : array of number of reports by compliance status [Array[Float]]
 *   , "compliancePercent" : Compliance percentage [Float]
 *   , "details" : Details of Directive applied by the Node [Array of Directive values ]
 *   , "jsid"    : unique identifier for the line [String]
 *   }
 */
final case class NodeComplianceLine(
    nodeInfo:        CoreNodeFact,
    compliance:      ComplianceLevel,
    details:         JsTableData[DirectiveComplianceLine],
    policyMode:      String,
    modeExplanation: String
) extends JsTableLine {
  val json: js.JsObj = {
    JsObj(
      ("node"              -> escapeHTML(nodeInfo.fqdn)),
      ("compliance"        -> jsCompliance(compliance)),
      ("compliancePercent" -> compliance.computePercent().compliance),
      ("id"                -> escapeHTML(nodeInfo.id.value)),
      ("details"           -> details.json), // unique id, usable as DOM id - rules, directives, etc can
      // appear several time in a page

      ("jsid"        -> nextFuncName),
      ("policyMode"  -> policyMode),
      ("explanation" -> modeExplanation)
    )
  }
}

/*
 *   Javascript object containing all data to create a line in the DataTable
 *   { "component" : component name [String]
 *   , "id" : id generated about that component [String]
 *   , "compliance" : array of number of reports by compliance status [Array[Float]]
 *   , "compliancePercent" : Compliance percentage [Float]
 *   , "details" : Details of values contained in the component [ Array of Component values ]
 *   , "noExpand" : The line should not be expanded if all values are "None" [Boolean]
 *   , "jsid"    : unique identifier for the line [String]
 *   }
 */

sealed trait ComponentComplianceLine extends JsTableLine {
  def component:  String
  def compliance: ComplianceLevel
}

final case class BlockComplianceLine(
    component:      String,
    compliance:     ComplianceLevel,
    details:        JsTableData[ComponentComplianceLine],
    reportingLogic: ReportingLogic
) extends ComponentComplianceLine {

  val json: js.JsObj = {
    JsObj(
      ("component"         -> escapeHTML(component)),
      ("compliance"        -> jsCompliance(compliance)),
      ("compliancePercent" -> compliance.computePercent().compliance),
      ("details"           -> details.json),
      ("jsid"              -> nextFuncName),
      ("composition"       -> reportingLogic.toString)
    )
  }
}

final case class ValueComplianceLine(
    component:  String,
    unexpanded: String,
    compliance: ComplianceLevel,
    details:    JsTableData[ComponentValueComplianceLine],
    noExpand:   Boolean
) extends ComponentComplianceLine {

  val json: js.JsObj = {
    JsObj(
      ("component"         -> escapeHTML(component)),
      ("unexpanded"        -> escapeHTML(unexpanded)),
      ("compliance"        -> jsCompliance(compliance)),
      ("compliancePercent" -> compliance.computePercent().compliance),
      ("details"           -> details.json),
      ("noExpand"          -> noExpand),
      ("jsid"              -> nextFuncName)
    )
  }

}

/*
 *   Javascript object containing all data to create a line in the DataTable
 *   { "value" : value of the key [String]
 *   , "compliance" : array of number of reports by compliance status [Array[Float]]
 *   , "compliancePercent" : Compliance percentage [Float]
 *   , "status" : Worst status of the Directive [String]
 *   , "statusClass" : Class to use on status cell [String]
 *   , "messages" : (Status, Message) linked to that value, only used in message popup [ Array[(status:String,value:String)] ]
 *   , "jsid"    : unique identifier for the line [String]
 *   }
 */
final case class ComponentValueComplianceLine(
    value:           String,
    unexpandedValue: String,
    messages:        List[(String, String)],
    compliance:      ComplianceLevel,
    status:          String,
    statusClass:     String
) extends JsTableLine {

  val json: js.JsObj = {
    JsObj(
      ("value"             -> escapeHTML(value)),
      ("unexpanded"        -> escapeHTML(unexpandedValue)),
      ("status"            -> status),
      ("statusClass"       -> statusClass),
      ("messages"          -> JsArray(messages.map { case (s, m) => JsObj(("status" -> s), ("value" -> escapeHTML(m))) })),
      ("compliance"        -> jsCompliance(compliance)),
      ("compliancePercent" -> compliance.computePercent().compliance), // unique id, usable as DOM id - rules, directives, etc can
      // appear several time in a page

      ("jsid" -> nextFuncName)
    )
  }

}

object ComplianceData extends Loggable {

  /*
   * For a given rule, display compliance by nodes.
   * For each node, elements displayed are restraint
   */
  def getRuleByNodeComplianceDetails(
      directiveLib: FullActiveTechniqueCategory,
      ruleId:       RuleId,
      nodeReports:  Map[NodeId, NodeStatusReport],
      allNodeInfos: Map[NodeId, CoreNodeFact],
      globalMode:   GlobalPolicyMode,
      allRules:     Seq[Rule]
  ): JsTableData[NodeComplianceLine] = {

    // Compute node compliance detail
    val nodeComplianceLines = nodeReports.flatMap {
      case (nodeId, reports) =>
        val aggregate =
          reports.reports.getOrElse(PolicyTypeName.rudderBase, AggregatedStatusReport(Nil)).filterByRules(Set(ruleId))
        for {
          nodeInfo <- allNodeInfos.get(nodeId)
        } yield {

          val directivesMode            = aggregate.directives.keys.map(x => directiveLib.allDirectives.get(x).flatMap(_._2.policyMode))
          val (policyMode, explanation) =
            ComputePolicyMode.nodeModeOnRule(nodeInfo.rudderSettings.policyMode, globalMode)(directivesMode.toSet).tuple

          val details = getDirectivesComplianceDetails(
            aggregate.directives.values.toList,
            directiveLib,
            globalMode,
            ComputePolicyMode.directiveModeOnNode(nodeInfo.rudderSettings.policyMode, globalMode)
          )
          NodeComplianceLine(
            nodeInfo,
            aggregate.compliance,
            JsTableData(details),
            policyMode,
            explanation
          )
        }
    }

    JsTableData(nodeComplianceLines.toList)
  }

  /*
   * For a given unique node, create the "by rule"
   * tree structure of compliance elements.
   * (rule -> directives -> components -> value with messages and status)
   * addOverridden decides if we add overridden policies in the result (policy tab) or not (policy tab)
   */
  def getNodeByRuleComplianceDetails(
      nodeId:       NodeId,
      report:       NodeStatusReport,
      tag:          PolicyTypeName,
      allNodeInfos: Map[NodeId, CoreNodeFact],
      directiveLib: FullActiveTechniqueCategory,
      rules:        Seq[Rule],
      globalMode:   GlobalPolicyMode
  ): JsTableData[RuleComplianceLine] = {

    val ruleComplianceLine = for {
      (ruleId, aggregate) <- (report.reports
                               .getOrElse(tag, AggregatedStatusReport(Nil))
                               .reports
                               .groupBy(_.ruleId)
                               .map { case (id, rules) => (id, AggregatedStatusReport(rules)) })
      rule                <- rules.find(_.id == ruleId)
    } yield {
      val nodeMode = allNodeInfos.get(nodeId).flatMap(_.rudderSettings.policyMode)
      val details  = getDirectivesComplianceDetails(
        aggregate.directives.values.toList,
        directiveLib,
        globalMode,
        ComputePolicyMode.directiveModeOnNode(nodeMode, globalMode)
      )

      val directivesMode            = aggregate.directives.keys.map(x => directiveLib.allDirectives.get(x).flatMap(_._2.policyMode)).toList
      val (policyMode, explanation) = ComputePolicyMode.ruleModeOnNode(nodeMode, globalMode)(directivesMode.toSet).tuple
      RuleComplianceLine(
        rule,
        rule.id,
        aggregate.compliance,
        JsTableData(details),
        policyMode,
        explanation,
        JsonTagSerialisation.serializeTags(rule.tags)
      )
    }
    JsTableData(ruleComplianceLine.toList.sortBy(_.id.serialize))
  }

  //////////////// Directive Report ///////////////

  // From Rule Point of view
  def getRuleByDirectivesComplianceDetails(
      report:       RuleStatusReport,
      rule:         Rule,
      nodeFacts:    MapView[NodeId, CoreNodeFact],
      directiveLib: FullActiveTechniqueCategory,
      groupLib:     FullNodeGroupCategory,
      globalMode:   GlobalPolicyMode
  ): JsTableData[DirectiveComplianceLine] = {
    // restrict mode computing to nodes really targeted by that rule
    val appliedNodes = groupLib.getNodeIds(rule.targets, nodeFacts.mapValues(_.rudderSettings.isPolicyServer))
    val nodeModes    = appliedNodes.flatMap(id => nodeFacts.get(id).map(_.rudderSettings.policyMode))
    val lines        = getDirectivesComplianceDetails(
      report.report.directives.values.toList,
      directiveLib,
      globalMode,
      ComputePolicyMode.directiveModeOnRule(nodeModes, globalMode)
    )

    JsTableData(lines)
  }

  // From Node Point of view
  private def getDirectivesComplianceDetails(
      directivesReport: List[DirectiveStatusReport],
      directiveLib:     FullActiveTechniqueCategory,
      globalPolicyMode: GlobalPolicyMode,
      computeMode:      Option[PolicyMode] => ComputePolicyMode.ComputedPolicyMode
  ): List[DirectiveComplianceLine] = {
    val directivesComplianceData = for {
      directiveStatus                  <- directivesReport
      (fullActiveTechnique, directive) <- directiveLib.allDirectives.get(directiveStatus.directiveId)
    } yield {
      val techniqueName             =
        fullActiveTechnique.techniques.get(directive.techniqueVersion).map(_.name).getOrElse("Unknown technique")
      val techniqueVersion          = directive.techniqueVersion
      val components                = getComponentsComplianceDetails(directiveStatus.components, includeMessage = true)
      val (policyMode, explanation) = computeMode(directive.policyMode).tuple
      val directiveTags             = JsonTagSerialisation.serializeTags(directive.tags)
      DirectiveComplianceLine(
        directive,
        techniqueName,
        techniqueVersion,
        directiveStatus.compliance,
        components,
        policyMode,
        explanation,
        directiveTags
      )
    }

    directivesComplianceData
  }
  //////////////// Component Report ///////////////

  // From Node Point of view
  private def getComponentsComplianceDetails(
      components:     List[ComponentStatusReport],
      includeMessage: Boolean
  ): JsTableData[ComponentComplianceLine] = {
    val componentsComplianceData = components.map {
      case component: BlockStatusReport =>
        BlockComplianceLine(
          component.componentName,
          component.compliance,
          getComponentsComplianceDetails(component.subComponents, includeMessage),
          component.reportingLogic
        )
      case component: ValueStatusReport =>
        val (noExpand, values) = if (!includeMessage) {
          (true, getValuesComplianceDetails(component.componentValues))
        } else {
          val noExpand = component.componentValues.forall(x => x.componentValue == DEFAULT_COMPONENT_KEY)

          (noExpand, getValuesComplianceDetails(component.componentValues))
        }

        ValueComplianceLine(
          component.componentName,
          component.expectedComponentName,
          component.compliance,
          values,
          noExpand
        )
    }

    JsTableData(componentsComplianceData)
  }

  //////////////// Value Report ///////////////

  // From Node Point of view
  private def getValuesComplianceDetails(
      values: List[ComponentValueStatusReport]
  ): JsTableData[ComponentValueComplianceLine] = {
    val valuesComplianceData = for {
      value <- values
    } yield {
      val severity = ReportType.getWorseType(value.messages.map(_.reportType)).severity
      val status   = getDisplayStatusFromSeverity(severity)
      val key      = value.componentValue
      val messages = value.messages.map(x => (x.reportType.severity, x.message.getOrElse("")))

      ComponentValueComplianceLine(
        key,
        value.expectedComponentValue,
        messages,
        value.compliance,
        status,
        severity
      )
    }
    JsTableData(valuesComplianceData)
  }

  private def getDisplayStatusFromSeverity(severity: String): String = {
    S.?(s"reports.severity.${severity}")
  }

}
