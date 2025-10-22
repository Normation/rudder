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
import com.normation.rudder.web.services.EscapeHtml.*
import io.scalaland.chimney.*
import io.scalaland.chimney.syntax.*
import net.liftweb.common.*
import net.liftweb.http.*
import org.apache.commons.text.StringEscapeUtils
import zio.json.*
import zio.json.internal.Write

object EscapeHtml {
  implicit class DoEscapeHtml(s: String) {
    // this is needed because DataTable doesn't escape HTML element when using table.rows.add
    def escapeHTML: String = StringEscapeUtils.escapeHtml4(s)
  }
}

/*
 * This is used to provide the jsid: nextFuncName in main code, a decidable pseudo-random in tests.
 */
trait ProvideNextName {
  def nextName: String
}

object LiftProvideNextName extends ProvideNextName {
  override def nextName: String = net.liftweb.util.Helpers.nextFuncName
}

object ProvideNextName {
  implicit val encoderProvideNextName: JsonEncoder[ProvideNextName] = JsonEncoder.string.contramap(_.nextName)
}

final case class RuleComplianceLines(rules: List[RuleComplianceLine])

object RuleComplianceLines {
  /*
   * This is the main encoder that under the hood will transform to the JsonXXXLine corresponding object to encode them.
   * It will need a `ProvideNextName` implicit in context to be used.
   */
  implicit def encoderRuleComplianceLines(implicit next: ProvideNextName): JsonEncoder[RuleComplianceLines] =
    JsonEncoder.list[JsonRuleComplianceLine].contramap[RuleComplianceLines](_.rules.map(_.transformInto[JsonRuleComplianceLine]))
}

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
    details:         List[DirectiveComplianceLine],
    policyMode:      String,
    modeExplanation: String,
    tags:            Tags
)

object RuleComplianceLine {

  implicit def transformRuleComplianceLine(implicit
      next: ProvideNextName
  ): Transformer[RuleComplianceLine, JsonRuleComplianceLine] = {
    Transformer
      .define[RuleComplianceLine, JsonRuleComplianceLine]
      .withFieldComputed(_.rule, _.rule.name.escapeHTML)
      .withFieldComputed(_.compliancePercent, _.compliance.computePercent().compliance)
      .withFieldComputed(_.id, _.rule.id.serialize.escapeHTML)
      .withFieldComputed(_.details, _.details.map(_.transformInto[JsonDirectiveComplianceLine]))
      .withFieldConst(_.jsid, next)
      .withFieldComputed(_.isSystem, _.rule.isSystem)
      .withFieldRenamed(_.modeExplanation, _.explanation)
      .buildTransformer
  }
}

final case class JsonRuleComplianceLine(
    rule:              String,
    compliance:        ComplianceLevel,
    compliancePercent: Double,
    id:                String,
    details:           List[JsonDirectiveComplianceLine],
    jsid:              ProvideNextName,
    isSystem:          Boolean,
    policyMode:        String,
    explanation:       String,
    tags:              Tags
)

object JsonRuleComplianceLine {
  import com.normation.rudder.domain.reports.ComplianceLevelSerialisation.array.*
  implicit val encoderJsonRuleComplianceLine: JsonEncoder[JsonRuleComplianceLine] = DeriveJsonEncoder.gen
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
    details:          List[ComponentComplianceLine],
    policyMode:       String,
    explanation:      String,
    tags:             Tags
)

object DirectiveComplianceLine {
  implicit def transformDirectiveComplianceLine(implicit
      next: ProvideNextName
  ): Transformer[DirectiveComplianceLine, JsonDirectiveComplianceLine] = {
    Transformer
      .define[DirectiveComplianceLine, JsonDirectiveComplianceLine]
      .withFieldComputed(_.directive, _.directive.name.escapeHTML)
      .withFieldComputed(_.id, _.directive.id.uid.value.escapeHTML)
      .withFieldComputed(_.techniqueName, _.techniqueName.escapeHTML)
      .withFieldComputed(_.techniqueVersion, _.techniqueVersion.serialize.escapeHTML)
      .withFieldComputed(_.compliancePercent, _.compliance.computePercent().compliance)
      .withFieldComputed(_.details, _.details.map(_.transformInto[JsonComponentComplianceLine]))
      .withFieldConst(_.jsid, next)
      .withFieldComputed(_.isSystem, _.directive.isSystem)
      .buildTransformer
  }
}

final case class JsonDirectiveComplianceLine(
    directive:         String,
    id:                String,
    techniqueName:     String,
    techniqueVersion:  String,
    compliance:        ComplianceLevel,
    compliancePercent: Double,
    details:           List[JsonComponentComplianceLine],
    jsid:              ProvideNextName,
    isSystem:          Boolean,
    policyMode:        String,
    explanation:       String,
    tags:              Tags
)

object JsonDirectiveComplianceLine {
  import com.normation.rudder.domain.reports.ComplianceLevelSerialisation.array.*
  implicit val encoderJsonDirectiveComplianceLine: JsonEncoder[JsonDirectiveComplianceLine] = DeriveJsonEncoder.gen
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

sealed trait ComponentComplianceLine {
  def component:  String
  def compliance: ComplianceLevel
}

object ComponentComplianceLine {
  implicit def transformComponentComplianceLine(implicit
      next: ProvideNextName
  ): Transformer[ComponentComplianceLine, JsonComponentComplianceLine] = {
    case x: BlockComplianceLine => x.transformInto[JsonBlockComplianceLine]
    case x: ValueComplianceLine => x.transformInto[JsonValueComplianceLine]
  }
}

final case class BlockComplianceLine(
    component:      String,
    compliance:     ComplianceLevel,
    details:        List[ComponentComplianceLine],
    reportingLogic: ReportingLogic
) extends ComponentComplianceLine

object BlockComplianceLine {
  implicit def transformBlockComplianceLine(implicit
      next: ProvideNextName
  ): Transformer[BlockComplianceLine, JsonBlockComplianceLine] = {
    Transformer
      .define[BlockComplianceLine, JsonBlockComplianceLine]
      .withFieldComputed(_.component, _.component.escapeHTML)
      .withFieldComputed(_.compliancePercent, _.compliance.computePercent().compliance)
      .withFieldComputed(_.details, _.details.map(_.transformInto[JsonComponentComplianceLine]))
      .withFieldConst(_.jsid, next)
      .withFieldComputed(_.composition, _.reportingLogic.toString)
      .buildTransformer
  }

}

final case class ValueComplianceLine(
    component:  String,
    unexpanded: String,
    compliance: ComplianceLevel,
    details:    List[ComponentValueComplianceLine],
    noExpand:   Boolean
) extends ComponentComplianceLine

object ValueComplianceLine {
  implicit def transformValueComplianceLine(implicit
      next: ProvideNextName
  ): Transformer[ValueComplianceLine, JsonValueComplianceLine] = {
    Transformer
      .define[ValueComplianceLine, JsonValueComplianceLine]
      .withFieldComputed(_.component, _.component.escapeHTML)
      .withFieldComputed(_.unexpanded, _.unexpanded.escapeHTML)
      .withFieldComputed(_.compliancePercent, _.compliance.computePercent().compliance)
      .withFieldComputed(_.details, _.details.map(_.transformInto[JsonComponentValueComplianceLine]))
      .withFieldConst(_.jsid, next)
      .buildTransformer
  }

}

sealed trait JsonComponentComplianceLine

object JsonComponentComplianceLine {
  implicit val encoderJsonComponentComplianceLine: JsonEncoder[JsonComponentComplianceLine] = {
    new JsonEncoder[JsonComponentComplianceLine] {
      override def unsafeEncode(a: JsonComponentComplianceLine, indent: Option[Int], out: Write): Unit = {
        a match {
          case x: JsonBlockComplianceLine => JsonEncoder[JsonBlockComplianceLine].unsafeEncode(x, indent, out)
          case x: JsonValueComplianceLine => JsonEncoder[JsonValueComplianceLine].unsafeEncode(x, indent, out)
        }
      }
    }
  }
}

final case class JsonBlockComplianceLine(
    component:         String,
    compliance:        ComplianceLevel,
    compliancePercent: Double,
    details:           List[JsonComponentComplianceLine],
    jsid:              ProvideNextName,
    composition:       String
) extends JsonComponentComplianceLine

object JsonBlockComplianceLine {
  import com.normation.rudder.domain.reports.ComplianceLevelSerialisation.array.*
  implicit val encoderJsonBlockComplianceLine: JsonEncoder[JsonBlockComplianceLine] = DeriveJsonEncoder.gen
}

final case class JsonValueComplianceLine(
    component:         String,
    unexpanded:        String,
    compliance:        ComplianceLevel,
    compliancePercent: Double,
    details:           List[JsonComponentValueComplianceLine],
    noExpand:          Boolean,
    jsid:              ProvideNextName
) extends JsonComponentComplianceLine

object JsonValueComplianceLine {
  import com.normation.rudder.domain.reports.ComplianceLevelSerialisation.array.*
  implicit val encoderJsonValueComplianceLine: JsonEncoder[JsonValueComplianceLine] = DeriveJsonEncoder.gen
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
)

object ComponentValueComplianceLine {
  implicit def transformComponentValueComplianceLine(implicit
      next: ProvideNextName
  ): Transformer[ComponentValueComplianceLine, JsonComponentValueComplianceLine] = {
    Transformer
      .define[ComponentValueComplianceLine, JsonComponentValueComplianceLine]
      .withFieldComputed(_.value, _.value.escapeHTML)
      .withFieldComputed(_.unexpanded, _.unexpandedValue.escapeHTML)
      .withFieldComputed(_.messages, _.messages.map { case (s, v) => Map("status" -> s, "value" -> v.escapeHTML) })
      .withFieldComputed(_.compliancePercent, _.compliance.computePercent().compliance)
      .withFieldConst(_.jsid, next)
      .buildTransformer
  }

}

final case class JsonComponentValueComplianceLine(
    value:             String,
    unexpanded:        String,
    status:            String,
    statusClass:       String,
    messages:          List[Map[String, String]],
    compliance:        ComplianceLevel,
    compliancePercent: Double,
    jsid:              ProvideNextName
)

object JsonComponentValueComplianceLine {
  import com.normation.rudder.domain.reports.ComplianceLevelSerialisation.array.*
  implicit val encoderJsonComponentValueComplianceLine: JsonEncoder[JsonComponentValueComplianceLine] = DeriveJsonEncoder.gen
}

object ComplianceData extends Loggable {

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
  ): RuleComplianceLines = {

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
        ComputePolicyMode.directiveModeOnNode(nodeMode, globalMode)
      )

      val directivesMode            = aggregate.directives.keys.map(x => directiveLib.allDirectives.get(x).flatMap(_._2.policyMode)).toList
      val (policyMode, explanation) = ComputePolicyMode.ruleModeOnNode(nodeMode, globalMode)(directivesMode.toSet).tuple
      RuleComplianceLine(
        rule,
        rule.id,
        aggregate.compliance,
        details,
        policyMode,
        explanation,
        rule.tags
      )
    }
    RuleComplianceLines(ruleComplianceLine.toList.sortBy(_.id.serialize))
  }

  //////////////// Directive Report ///////////////

  // From Node Point of view
  private def getDirectivesComplianceDetails(
      directivesReport: List[DirectiveStatusReport],
      directiveLib:     FullActiveTechniqueCategory,
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
      DirectiveComplianceLine(
        directive,
        techniqueName,
        techniqueVersion,
        directiveStatus.compliance,
        components,
        policyMode,
        explanation,
        directive.tags
      )
    }

    directivesComplianceData
  }
  //////////////// Component Report ///////////////

  // From Node Point of view
  private def getComponentsComplianceDetails(
      components:     List[ComponentStatusReport],
      includeMessage: Boolean
  ): List[ComponentComplianceLine] = {
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

    componentsComplianceData
  }

  //////////////// Value Report ///////////////

  // From Node Point of view
  private def getValuesComplianceDetails(
      values: List[ComponentValueStatusReport]
  ): List[ComponentValueComplianceLine] = {
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
    valuesComplianceData
  }

  private def getDisplayStatusFromSeverity(severity: String): String = {
    try {
      S.?(s"reports.severity.${severity}")
    } catch { // S can only be used in a Lift session context, so for tests, we need that workaround
      case _: IllegalStateException => severity
    }
  }

}
