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
import net.liftweb.common.*
import net.liftweb.http.*
import net.liftweb.http.js.JE.*
import net.liftweb.json.JsonAST.JValue

/*
 * That file contains all the datastructures related to
 * compliance of different level of rules/nodes, and
 * that will be mapped to JSON
 *
 */

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
  def json(freshName: () => String): js.JsObj = {
    JsObj(
      ("rule"              -> escapeHTML(rule.name)),
      ("compliance"        -> jsCompliance(compliance)),
      ("compliancePercent" -> compliance.computePercent().compliance),
      ("id"                -> escapeHTML(rule.id.serialize)),
      ("details"           -> details.json(freshName)), // unique id, usable as DOM id - rules, directives, etc can
      // appear several time in a page

      ("jsid"        -> freshName()),
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
  override def json(freshName: () => String): js.JsObj = {
    JsObj(
      ("directive"         -> escapeHTML(directive.name)),
      ("id"                -> escapeHTML(directive.id.uid.value)),
      ("techniqueName"     -> escapeHTML(techniqueName)),
      ("techniqueVersion"  -> escapeHTML(techniqueVersion.serialize)),
      ("compliance"        -> jsCompliance(compliance)),
      ("compliancePercent" -> compliance.computePercent().compliance),
      ("details"           -> details.json(freshName)), // unique id, usable as DOM id - rules, directives, etc can
      // appear several time in a page

      ("jsid"        -> freshName()),
      ("isSystem"    -> directive.isSystem),
      ("policyMode"  -> policyMode),
      ("explanation" -> modeExplanation),
      ("tags"        -> tags)
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
  def json(freshName: () => String): js.JsObj = {
    JsObj(
      ("component"         -> escapeHTML(component)),
      ("compliance"        -> jsCompliance(compliance)),
      ("compliancePercent" -> compliance.computePercent().compliance),
      ("details"           -> details.json(freshName)),
      ("jsid"              -> freshName()),
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

  def json(freshName: () => String): js.JsObj = {
    JsObj(
      ("component"         -> escapeHTML(component)),
      ("unexpanded"        -> escapeHTML(unexpanded)),
      ("compliance"        -> jsCompliance(compliance)),
      ("compliancePercent" -> compliance.computePercent().compliance),
      ("details"           -> details.json(freshName)),
      ("noExpand"          -> noExpand),
      ("jsid"              -> freshName())
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

  def json(freshName: () => String): js.JsObj = {
    JsObj(
      ("value"             -> escapeHTML(value)),
      ("unexpanded"        -> escapeHTML(unexpandedValue)),
      ("status"            -> status),
      ("statusClass"       -> statusClass),
      ("messages"          -> JsArray(messages.map { case (s, m) => JsObj(("status" -> s), ("value" -> escapeHTML(m))) })),
      ("compliance"        -> jsCompliance(compliance)),
      ("compliancePercent" -> compliance.computePercent().compliance), // unique id, usable as DOM id - rules, directives, etc can
      // appear several time in a page

      ("jsid" -> freshName())
    )
  }

}

object ComplianceData extends Loggable {

  /*
   * For a given unique node, create the "by rule"
   * tree structure of compliance elements.
   * (rule -> directives -> components -> value with messages and status)
   * addOverridden decides if we add overridden policies in the result (policy tab) or not (policy tab)
   */
  def getNodeByRuleComplianceDetails(
      nodeId:        NodeId,
      report:        NodeStatusReport,
      tag:           PolicyTypeName,
      allNodeInfos:  Map[NodeId, CoreNodeFact],
      directiveLib:  FullActiveTechniqueCategory,
      rules:         Seq[Rule],
      globalMode:    GlobalPolicyMode,
      addOverridden: Boolean
  ): JsTableData[RuleComplianceLine] = {

    // add overridden directive in the list under there rule
    val overridesByRules = if (addOverridden) {
      report.overrides.groupBy(_.policy.ruleId)
    } else {
      Map[RuleId, List[OverriddenPolicy]]()
    }

    // we can have rules with only overridden reports, so we just prepend them. When
    // a rule is defined for that id, it will override that default.
    val overridesRules = overridesByRules.view.mapValues(_ => AggregatedStatusReport(Nil)).toMap

    val ruleComplianceLine = for {
      (ruleId, aggregate) <- (overridesRules ++ report.reports
                               .getOrElse(tag, AggregatedStatusReport(Nil))
                               .reports
                               .groupBy(_.ruleId)
                               .map { case (id, rules) => (id, AggregatedStatusReport(rules)) })
      rule                <- rules.find(_.id == ruleId)
    } yield {
      val nodeMode = allNodeInfos.get(nodeId).flatMap(_.rudderSettings.policyMode)
      val details  = getOverriddenDirectiveDetails(overridesByRules.getOrElse(ruleId, Nil), directiveLib, rules, None) ++
        getDirectivesComplianceDetails(
          aggregate.directives.values.toList,
          directiveLib,
          ComputePolicyMode.directiveModeOnNode(nodeMode, globalMode)
        )

      val directivesMode            = aggregate.directives.keys.map(x => directiveLib.allDirectives.get(x).flatMap(_._2.policyMode)).toList
      val (policyMode, explanation) = ComputePolicyMode.ruleModeOnNode(nodeMode, globalMode)(directivesMode.toSet)
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

  private def getOverriddenDirectiveDetails(
      overrides:    List[OverriddenPolicy],
      directiveLib: FullActiveTechniqueCategory,
      rules:        Seq[Rule],
      onRuleScreen: Option[Rule] // if we are on a rule, we want to adapt message
  ): List[DirectiveComplianceLine] = {
    val overridesData = for {
      // we don't want to write an overridden directive several time for the same overriding rule/directive.
      over                            <- overrides
      (overriddenTech, overriddenDir) <- directiveLib.allDirectives.get(over.policy.directiveId)
      overridingRule                  <- rules.find(_.id == over.overriddenBy.ruleId)
      (overridingTech, overridingDir) <- directiveLib.allDirectives.get(over.overriddenBy.directiveId)
    } yield {
      val overriddenTechName    =
        overriddenTech.techniques.get(overriddenDir.techniqueVersion).map(_.name).getOrElse("Unknown technique")
      val overriddenTechVersion = overriddenDir.techniqueVersion

      val policyMode  = "overridden"
      val explanation = "This directive is unique: only one directive derived from its technique can be set on a given node " +
        s"at the same time. This one is overridden by directive '<i><b>${overridingDir.name}</b></i>'" +
        (onRuleScreen match {
          case None                                   =>
            s" in rule '<i><b>${overridingRule.name}</b></i>' on that node."
          case Some(r) if (r.id == overridingRule.id) =>
            s" in that rule."
          case Some(r)                                => // it means that that directive is skipped on all nodes on that rule
            s" in rule '<i><b>${overridingRule.name}</b></i>' on all nodes."
        })

      DirectiveComplianceLine(
        overriddenDir,
        overriddenTechName,
        overriddenTechVersion,
        ComplianceLevel(),
        JsTableData(Nil),
        policyMode,
        explanation,
        JsonTagSerialisation.serializeTags(overriddenDir.tags)
      )
    }

    overridesData
  }

  //////////////// Directive Report ///////////////

  // From Node Point of view
  private def getDirectivesComplianceDetails(
      directivesReport: List[DirectiveStatusReport],
      directiveLib:     FullActiveTechniqueCategory,
      computeMode:      Option[PolicyMode] => (String, String)
  ): List[DirectiveComplianceLine] = {
    val directivesComplianceData = for {
      directiveStatus                  <- directivesReport
      (fullActiveTechnique, directive) <- directiveLib.allDirectives.get(directiveStatus.directiveId)
    } yield {
      val techniqueName             =
        fullActiveTechnique.techniques.get(directive.techniqueVersion).map(_.name).getOrElse("Unknown technique")
      val techniqueVersion          = directive.techniqueVersion
      val components                = getComponentsComplianceDetails(directiveStatus.components, includeMessage = true)
      val (policyMode, explanation) = computeMode(directive.policyMode)
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
    try {
      S.?(s"reports.severity.${severity}")
    } catch { // S can only be used in a Lift session context, so for tests, we need that workaround
      case _: IllegalStateException => severity
    }
  }

}
