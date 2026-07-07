/*
 *************************************************************************************
 * Copyright 2017 Normation SAS
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

package com.normation.rudder.rest.data

import cats.Order
import cats.data.NonEmptyList
import cats.syntax.list.*
import com.normation.cfclerk.domain.ReportingLogic
import com.normation.errors.Inconsistency
import com.normation.errors.IOResult
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.reports.*
import com.normation.rudder.domain.reports.ReportType.*
import com.normation.rudder.reports.ComplianceModeName
import com.normation.rudder.rest.data.CsvCompliance.*
import com.normation.rudder.rest.data.CsvCompliance.CsvComplianceOpaqueTypes.*
import com.normation.rudder.web.services.ComputePolicyMode.ComputedPolicyMode
import com.normation.utils.Csv
import enumeratum.*
import fs2.Chunk
import io.scalaland.chimney.*
import io.scalaland.chimney.syntax.*
import java.lang
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.QuoteMode
import scala.collection.immutable
import zio.json.JsonCodec
import zio.json.JsonEncoder
import zio.syntax.ToZio

/**
 * Here, we want to present two views of compliance:
 * - by node
 * - by rule
 *
 * For "by node", we want to have a list of node, then rules,
 * then directives, then component and then values.
 *
 * For "by rule", we want to have a list of rules, then
 * directives, then components, THEN NODEs, then values.
 * Because we want to be able to analyse at the component level
 * if we have problems on several nodes, but values are only
 * meaningful in the context of a node.
 *
 * So we define two hierarchy of classes, with the name convention
 * starting by "ByRule" or "ByNode".
 */

final case class AllByRuleRuleCompliance(rules: Chunk[Seq[ByRuleRuleCompliance]])

/**
 * Compliance for a rules.
 * It lists:
 * - id: the rule id
 * - compliance: the compliance of the rule by node
 *   (total number of node, repartition of node by status)
 * - nodeCompliance: the list of compliance for each node, for that that rule
 */
final case class ByRuleRuleCompliance(
    id:   RuleId,
    name: String, // compliance by nodes

    compliance: ComplianceLevel,
    mode:       ComplianceModeName,
    policyMode: ComputedPolicyMode,
    directives: Seq[ByRuleDirectiveCompliance]
) {
  lazy val nodes: Seq[GroupComponentCompliance] = GroupComponentCompliance.fromDirective(directives).toSeq
}

final case class ByDirectiveCompliance(
    id:         DirectiveId,
    name:       String,
    compliance: ComplianceLevel,
    mode:       ComplianceModeName,
    policyMode: ComputedPolicyMode,
    rules:      Seq[ByDirectiveByRuleCompliance]
) {
  lazy val nodes: Seq[ByDirectiveNodeCompliance] = GroupComponentCompliance.fromRules(rules).toSeq
}

final case class ByDirectiveByRuleCompliance(
    id:             RuleId,
    name:           String,
    compliance:     ComplianceLevel,
    // this one is a rule and has the skipped details because it is on an reversed hierarchy with directives
    skippedDetails: Option[SkippedDetails],
    policyMode:     ComputedPolicyMode,
    components:     Seq[ByRuleComponentCompliance]
)

final case class ByDirectiveNodeCompliance(
    id:         NodeId,
    name:       NodeName,
    policyMode: ComputedPolicyMode,
    compliance: ComplianceLevel,
    rules:      Seq[ByDirectiveByNodeRuleCompliance]
)

final case class ByDirectiveByNodeRuleCompliance(
    id:         RuleId,
    name:       String,
    compliance: ComplianceLevel,
    policyMode: ComputedPolicyMode,
    components: Seq[ByRuleByNodeByDirectiveByComponentCompliance]
)

final case class ByNodeGroupCompliance(
    id:         String,
    name:       String,
    compliance: ComplianceLevel,
    mode:       ComplianceModeName,
    rules:      Seq[ByNodeGroupRuleCompliance],
    nodes:      Seq[ByNodeGroupNodeCompliance]
)

final case class ByNodeGroupNodeCompliance(
    id:         NodeId,
    name:       String,
    mode:       ComplianceModeName,
    compliance: ComplianceLevel,
    policyMode: ComputedPolicyMode,
    rules:      Seq[ByNodeRuleCompliance]
)

final case class ByNodeGroupRuleCompliance(
    id:         RuleId,
    name:       String,
    compliance: ComplianceLevel,
    policyMode: ComputedPolicyMode,
    directives: Seq[ByNodeGroupByRuleDirectiveCompliance]
)

final case class ByNodeGroupByRuleDirectiveCompliance(
    id:             DirectiveId,
    name:           String,
    compliance:     ComplianceLevel,
    skippedDetails: Option[SkippedDetails],
    policyMode:     ComputedPolicyMode,
    components:     Seq[ByRuleComponentCompliance]
)

opaque type DirectiveName = String
object DirectiveName extends CsvField[DirectiveName] {

  given jsonCodec: JsonCodec[DirectiveName] = JsonCodec.string

  def apply(name: String): DirectiveName = name
}

final case class ByRuleDirectiveCompliance(
    id:             DirectiveId,
    name:           DirectiveName,
    compliance:     ComplianceLevel,
    skippedDetails: Option[SkippedDetails],
    policyMode:     ComputedPolicyMode,
    components:     Seq[ByRuleComponentCompliance]
)

sealed trait ByRuleComponentCompliance extends ComponentComplianceByNode {
  def name:       ComponentName
  def compliance: ComplianceLevel
}

final case class ByRuleBlockCompliance(
    name:           BlockName,
    reportingLogic: ReportingLogic,
    subComponents:  Seq[ByRuleComponentCompliance]
) extends ByRuleComponentCompliance with BlockComplianceByNode[ByRuleComponentCompliance] {
  override def subs: List[ByRuleComponentCompliance & ComponentCompliance] = subComponents.toList

  override def componentName: String = name.value
}

final case class ByRuleValueCompliance(
    name:       ValueName,
    compliance: ComplianceLevel,
    nodes:      List[ByRuleNodeCompliance]
) extends ByRuleComponentCompliance {
  override def componentName: String = name.value

  override def allReports: List[ReportType] = reportsByNode.values.flatten.toList

  override def reportsByNode: Map[NodeId, Seq[ReportType]] =
    nodes.map(n => (n.id, n.values.flatMap(c => c.messages.map(_ => c.status)))).toMap
}

final case class ByRuleNodeCompliance(
    id:         NodeId,
    name:       NodeName,
    policyMode: ComputedPolicyMode,
    compliance: ComplianceLevel,
    values:     Seq[ComponentValueStatusReport]
)

/* This is the same compliance structure than ByRuleByDirectiveCompliance except that the entry point is a Node
   The full hierarchy is
 * Node
   |> * Directive
      |> * Component Blocks ( There may be no blocks and directly a Value , but the leaf og the tree is a Value)
         |> * Value
            |> Reports
 */

final case class GroupComponentCompliance(
    id:         NodeId,
    name:       NodeName,
    compliance: ComplianceLevel,
    policyMode: ComputedPolicyMode,
    directives: Seq[ByRuleByNodeByDirectiveCompliance]
)

final case class ByRuleByNodeByDirectiveCompliance(
    id:             DirectiveId,
    name:           DirectiveName,
    compliance:     ComplianceLevel,
    skippedDetails: Option[SkippedDetails],
    policyMode:     ComputedPolicyMode,
    components:     Seq[ByRuleByNodeByDirectiveByComponentCompliance]
)

sealed trait ByRuleByNodeByDirectiveByComponentCompliance extends ComponentCompliance {
  def name:       ComponentName
  def compliance: ComplianceLevel
}

final case class ByRuleByNodeByDirectiveByBlockCompliance(
    name:           BlockName,
    reportingLogic: ReportingLogic,
    subComponents:  Seq[ByRuleByNodeByDirectiveByComponentCompliance]
) extends ByRuleByNodeByDirectiveByComponentCompliance with BlockCompliance[ByRuleByNodeByDirectiveByComponentCompliance] {
  override def subs: List[ByRuleByNodeByDirectiveByComponentCompliance & ComponentCompliance] = subComponents.toList

  override def componentName: String = name.value
}

final case class ByRuleByNodeByDirectiveByValueCompliance(
    name:       ValueName,
    compliance: ComplianceLevel,
    values:     Seq[ComponentValueStatusReport]
) extends ByRuleByNodeByDirectiveByComponentCompliance {
  override def componentName: String = name.value

  override def allReports: List[ReportType] = values.flatMap(c => c.messages.map(_ => c.status)).toList
}

final case class SkippedDetails(
    overridingRuleId:   RuleId,
    overridingRuleName: String
)

object GroupComponentCompliance {
  // ordering that is required when creating sorted maps from cats strict groupByNel
  implicit private val ordNodeId: Order[NodeId] = Order.by[NodeId, String](_.value)

  // This function do the recursive treatment of components, we will have each time a pair of Sequence of tuple (NodeId , component compliance structure)
  def recurseComponent(
      byRuleComponentCompliance: ByRuleComponentCompliance
  ): Seq[((NodeId, NodeName, ComputedPolicyMode), ByRuleByNodeByDirectiveByComponentCompliance)] = {
    byRuleComponentCompliance match {
      // Block case
      case b: ByRuleBlockCompliance =>
        (for {
          // Treat sub components
          subComponent <- b.subComponents
          s            <- recurseComponent(subComponent)
        } yield {
          s
        }).groupBy(_._1)
          .map {
            // All subComponents are regrouped by Node, rebuild our block for each node
            case (nodeId, s) =>
              val subs = s.map(_._2)
              (nodeId, ByRuleByNodeByDirectiveByBlockCompliance(b.name, b.reportingLogic, subs))
          }
          .toSeq
      // Value case
      case v: ByRuleValueCompliance =>
        // Regroup Node Compliance by Node id
        v.nodes
          .groupByNel(_.id)
          .unsorted
          .map {
            case (nodeId, data) =>
              // You get all reports that were for a Node matching our value, regroup these report for our node in the structure)
              // Node name should be the same for all items, take the first one. We need to send it to upper structure, link it with id
              val (name, policyMode) = data match {
                case NonEmptyList(head, _) => head.name -> head.policyMode
              }
              (
                (nodeId, name, policyMode),
                ByRuleByNodeByDirectiveByValueCompliance(
                  v.name,
                  ComplianceLevel.sum(data.map(_.compliance).toList),
                  data.toList.flatMap(_.values)
                )
              )
          }
          .toSeq
    }
  }

  // The goal here is to build a Compliance structure based on Nodes from a Compliance Structure Based on Directives
  // That contains Node reference as leaf
  // So we will go deep in the data structure to take reference to node then reconstruct the tree from the leaf
  def fromDirective(directives: Seq[ByRuleDirectiveCompliance]): immutable.Iterable[GroupComponentCompliance] = {
    for {
      // Regroup all Directive by node getting Nodes values from components
      ((nodeId, nodeName, policyMode), data) <- directives.flatMap { d =>
                                                  (for {
                                                    // Treat all directives deeply
                                                    subs <- d.components
                                                    s    <- recurseComponent(subs)
                                                  } yield {
                                                    s
                                                  }).groupBy(_._1).map {
                                                    // All components were regrouped by nodes
                                                    case (nodeId, s) =>
                                                      val subs = s.map(_._2)
                                                      // Rebuild a Directive compliance for a Node
                                                      (
                                                        nodeId,
                                                        ByRuleByNodeByDirectiveCompliance(
                                                          d.id,
                                                          d.name,
                                                          ComplianceLevel.sum(subs.map(_.compliance)),
                                                          d.skippedDetails,
                                                          d.policyMode,
                                                          subs
                                                        )
                                                      )
                                                  }

                                                }.groupBy(_._1)
    } yield {
      // All Directive were regrouped by Nodes (_._1), rebuild a strucutre containing all Directives
      val subs = data.map(_._2)
      GroupComponentCompliance(nodeId, nodeName, ComplianceLevel.sum(subs.map(_.compliance)), policyMode, subs)
    }
  }

  // The goal here is to build a Compliance structure based on Nodes from a Compliance Structure Based on Directives
  // That contains Node reference as leaf
  // So we will go deep in the data structure to take reference to node then reconstruct the tree from the leaf
  def fromRules(directives: Seq[ByDirectiveByRuleCompliance]): immutable.Iterable[ByDirectiveNodeCompliance] = {
    for {
      // Regroup all Directive by node getting Nodes values from components
      ((nodeId, nodeName, policyMode), data) <- directives.flatMap { d =>
                                                  (for {
                                                    // Treat all directives deeply
                                                    subs <- d.components
                                                    s    <- recurseComponent(subs)
                                                  } yield {
                                                    s
                                                  }).groupBy(_._1).map {
                                                    // All components were regrouped by nodes
                                                    case (nodeId, s) =>
                                                      val subs = s.map(_._2)
                                                      // Rebuild a Directtive compliance for a Node
                                                      (
                                                        nodeId,
                                                        ByDirectiveByNodeRuleCompliance(
                                                          d.id,
                                                          d.name,
                                                          ComplianceLevel.sum(subs.map(_.compliance)),
                                                          d.policyMode,
                                                          subs
                                                        )
                                                      )
                                                  }

                                                }.groupBy(_._1)
    } yield {
      // All Directive were regrouped by Nodes (_._1), rebuild a strucutre containing all Directives
      val subs = data.map(_._2)
      ByDirectiveNodeCompliance(nodeId, nodeName, policyMode, ComplianceLevel.sum(subs.map(_.compliance)), subs)
    }
  }

}

/**
 * Compliance for a node.
 * It lists:
 * - id: the node id
 * - compliance: the compliance of the node by rule
 *   (total number of rules, repartition of rules by status)
 * - ruleUnderNodeCompliance: the list of compliance for each rule, for that node
 */
final case class ByNodeNodeCompliance(
    id:              NodeId, // compliance by nodes
    name:            String,
    compliance:      ComplianceLevel,
    mode:            ComplianceModeName,
    policyMode:      ComputedPolicyMode,
    nodeCompliances: Seq[ByNodeRuleCompliance]
)

/**
 * Compliance for rule, for a given set of directives
 * (normally, all the directive for a given rule)
 * It lists:
 * - id: the rule id
 * - compliance: total number of directives and
 *   repartition of directive compliance by status
 * - directiveCompliances: status for each directive, for that rule.
 */
final case class ByNodeRuleCompliance(
    id:         RuleId,
    name:       String, // compliance by directive (by nodes)
    compliance: ComplianceLevel,
    policyMode: ComputedPolicyMode,
    directives: Seq[ByNodeDirectiveCompliance]
)

final case class ByNodeDirectiveCompliance(
    id:             DirectiveId,
    name:           String,
    compliance:     ComplianceLevel,
    skippedDetails: Option[SkippedDetails],
    policyMode:     ComputedPolicyMode,
    components:     List[ComponentStatusReport]
)

/*
 * These objects are only used to export compliance in CSV format, for example in directive screen.
 */
object CsvCompliance {
  import ComponentName.given

  // use "," , quote everything with ", line separator is \n
  val csvFormat: CSVFormat = CSVFormat.DEFAULT.builder().setQuoteMode(QuoteMode.ALL).setRecordSeparator("\n").get()

  def recurseComponent(
      component: ByRuleComponentCompliance,
      block:     List[ComponentName]
  ): Seq[RuleComponentResult] = {

    component match {
      case component: ByRuleValueCompliance =>
        component.nodes.flatMap { node =>
          node.values.flatMap { value =>
            value.messages.map { report =>
              (
                BlockName(block.mkString(",")),
                component.name,
                node.name,
                ValueName(value.componentValue),
                Status(report.reportType),
                Message(report.message)
              )
            }
          }
        }
      case component: ByRuleBlockCompliance =>
        component.subComponents.flatMap(c => recurseComponent(c, block ::: (component.name :: Nil)))
    }
  }

  implicit class CsvDirectiveCompliance(val directive: ByDirectiveCompliance) extends AnyVal {
    def toCsv: String = {
      val csvLines = directive.rules.flatMap { r =>
        for {
          c                                            <- r.components
          (block, component, node, value, status, msg) <- recurseComponent(c, Nil)
        } yield {
          List(r.name, block, component, node, value, status, msg)
        }
      }
      // csvFormat takes care of line separators
      val out      = new lang.StringBuilder()
      csvFormat.printRecord(out, "Rule", "Block", "Component", "Node", "Value", "Status", "Message")
      csvLines.foreach(l => csvFormat.printRecord(out, l*))
      out.toString
    }
  }

  object CsvComplianceOpaqueTypes {

    /** Trait that contains Csv[A] and Csv.Header.One[A] instances
     * that are common to all values that can be converted to CSV */
    sealed trait CsvField[A <: String] {
      given Csv[A] = Csv.instance(a => a)
      given Csv.Header.One[A] with {}
    }

    type ComponentName = BlockName | ValueName

    object ComponentName extends CsvField[ComponentName] {
      given JsonEncoder[ComponentName] = JsonEncoder[String]
    }

    opaque type BlockName = String

    object BlockName extends CsvField[BlockName] {
      def apply(name: String): BlockName = name

      extension (blockName: BlockName) {
        def value: String = blockName
      }

      given jsonCodec: JsonCodec[BlockName] = JsonCodec.string
    }

    opaque type ValueName = String
    object ValueName extends CsvField[ValueName] {
      def apply(value: String): ValueName = value

      extension (valueName: ValueName) {
        def value: String = valueName
      }

      given jsonCodec: JsonCodec[ValueName] = JsonCodec.string
    }

    opaque type Status = String
    object Status extends CsvField[Status] {
      import com.normation.rudder.rest.data.ComplianceApiData.serialize
      def apply(reportType: ReportType): Status = reportType.serialize
    }

    opaque type Message = String
    object Message extends CsvField[Message] {
      def apply(reportMessage: Option[String]): Message = reportMessage.getOrElse("")
    }

    opaque type NodeName = Either[NodeId, String]

    object NodeName {
      def apply(name:  String): NodeName = Right(name)
      def from(nodeId: NodeId): NodeName = Left(nodeId)
      given Csv[NodeName]         = Csv.instance {
        case Left(nodeId) => nodeId.value
        case Right(name)  => name
      }
      given Csv.Header.One[NodeName] with {}
      given JsonEncoder[NodeName] = JsonEncoder.string.contramap(nodeName => nodeName.getOrElse("Unknown node"))
    }
  }

  type RuleComponentResult =
    (block: BlockName, component: ComponentName, node: NodeName, value: ValueName, status: Status, message: Message)

  case class RuleComplianceByDirectiveCsv(
      directive: DirectiveName,
      block:     BlockName,
      component: ComponentName,
      node:      NodeName,
      value:     ValueName,
      status:    Status,
      message:   Message
  ) derives Csv
  object RuleComplianceByDirectiveCsv {
    import com.normation.rudder.rest.data.CsvCompliance.RuleComponentResult

    given (using
        directive: ByRuleDirectiveCompliance
    ): Transformer[RuleComponentResult, RuleComplianceByDirectiveCsv] = {
      Transformer
        .define[RuleComponentResult, RuleComplianceByDirectiveCsv]
        .withFieldConst(_.directive, directive.name)
        .buildTransformer
    }

    given Transformer[ByRuleRuleCompliance, Seq[RuleComplianceByDirectiveCsv]] = (rule: ByRuleRuleCompliance) => {
      rule.directives.flatMap(d => {
        for {
          c   <- d.components
          res <- recurseComponent(c, Nil)
        } yield {
          given ByRuleDirectiveCompliance = d
          res.transformInto[RuleComplianceByDirectiveCsv]
        }
      })
    }
  }

  case class RuleComplianceByNodeCsv(
      node:      NodeName,
      directive: DirectiveName,
      block:     BlockName,
      component: ComponentName,
      value:     ValueName,
      status:    Status,
      message:   Message
  ) derives Csv

  object RuleComplianceByNodeCsv {

    import com.normation.rudder.rest.data.CsvCompliance.RuleComponentResult

    given (using directive: ByRuleDirectiveCompliance): Transformer[RuleComponentResult, RuleComplianceByNodeCsv] = {
      Transformer
        .define[RuleComponentResult, RuleComplianceByNodeCsv]
        .withFieldConst(_.directive, directive.name)
        .buildTransformer
    }

    given Transformer[ByRuleRuleCompliance, Seq[RuleComplianceByNodeCsv]] = (rule: ByRuleRuleCompliance) => {
      rule.directives.flatMap(d => {
        for {
          c   <- d.components
          res <- recurseComponent(c, Nil)
        } yield {
          given ByRuleDirectiveCompliance = d

          res.transformInto[RuleComplianceByNodeCsv]
        }
      })
    }
  }
}

sealed trait ComplianceFormat extends EnumEntry {
  def value: String
}

object ComplianceFormat extends Enum[ComplianceFormat] {
  case object CSV  extends ComplianceFormat { val value = "csv"  }
  case object JSON extends ComplianceFormat { val value = "json" }
  val values:                   IndexedSeq[ComplianceFormat]     = findValues
  def fromValue(value: String): Either[String, ComplianceFormat] = {
    values.find(_.value == value) match {
      case None         =>
        Left(
          s"Wrong type of value for compliance format '${value}', expected : ${values.map(_.value).mkString("[", ", ", "]")}"
        )
      case Some(action) => Right(action)
    }
  }
}

/*
 * This is used for serialization of compliance API object. These are pure DTO
 */
object ComplianceApiData {

  import ComponentName.given
  // generic transformers
  private given complianceLevelTransformer(using precision: CompliancePrecision): Transformer[ComplianceLevel, Double] =
    _.complianceWithoutPending(precision)

  private given complianceSerializableTransformer(using
      precision: CompliancePrecision
  ): Transformer[ComplianceLevel, ComplianceSerializable] = x =>
    CompliancePercent.fromLevels(x, precision).transformInto[ComplianceSerializable]

  private given JsonEncoder[ComputedPolicyMode] = JsonEncoder.string.contramap(_.name)

  final case class SkippedDetailsApi(
      overridingRuleId:   RuleId,
      overridingRuleName: String
  ) derives JsonEncoder

  private given Transformer[SkippedDetails, SkippedDetailsApi] =
    Transformer.define[SkippedDetails, SkippedDetailsApi].buildTransformer

  final case class ReportMessageApi(
      status:  ReportType,
      message: Option[String]
  ) derives JsonEncoder

  private given Transformer[MessageStatusReport, ReportMessageApi] =
    Transformer.define[MessageStatusReport, ReportMessageApi].withFieldRenamed(_.reportType, _.status).buildTransformer

  given JsonEncoder[ReportType] = JsonEncoder.string.contramap(_.serialize)

  extension (rt: ReportType) {
    def serialize: String = rt match {
      case EnforceNotApplicable => "successNotApplicable"
      case EnforceSuccess       => "successAlreadyOK"
      case EnforceRepaired      => "successRepaired"
      case EnforceError         => "error"
      case AuditCompliant       => "auditCompliant"
      case AuditNonCompliant    => "auditNonCompliant"
      case AuditError           => "auditError"
      case AuditNotApplicable   => "auditNotApplicable"
      case Unexpected           => "unexpectedUnknownComponent"
      case Missing              => "unexpectedMissingComponent"
      case NoAnswer             => "noReport"
      case Disabled             => "reportsDisabled"
      case Pending              => "applying"
      case BadPolicyMode        => "badPolicyMode"
    }
  }

  final case class ComponentValueStatusReportApi(
      value:   String,
      reports: Seq[ReportMessageApi]
  ) derives JsonEncoder

  private given Transformer[ComponentValueStatusReport, ComponentValueStatusReportApi] = {
    Transformer
      .define[ComponentValueStatusReport, ComponentValueStatusReportApi]
      .withFieldRenamed(_.componentValue, _.value)
      .withFieldComputed(_.reports, x => x.messages.map(_.transformInto[ReportMessageApi]))
      .buildTransformer
  }

  final case class ByRuleNodeComplianceApi(
      id:                NodeId,
      name:              NodeName,
      compliance:        Double,
      policyMode:        ComputedPolicyMode,
      complianceDetails: ComplianceSerializable,
      values:            Option[Seq[ComponentValueStatusReportApi]]
  ) derives JsonEncoder

  private given byRuleNodeComplianceApi(using
      precision: CompliancePrecision,
      level:     Int
  ): Transformer[ByRuleNodeCompliance, ByRuleNodeComplianceApi] = {
    Transformer
      .define[ByRuleNodeCompliance, ByRuleNodeComplianceApi]
      .withFieldComputed(
        _.values,
        x => if (level < 5) None else Some(x.values.map(_.transformInto[ComponentValueStatusReportApi]))
      )
      .withFieldRenamed(_.compliance, _.complianceDetails)
      .buildTransformer
  }

  final case class ByRuleByNodeByDirectiveByComponentComplianceApi(
      name:              ComponentName,
      compliance:        Double,
      complianceDetails: ComplianceSerializable,
      components:        Option[Seq[ByRuleByNodeByDirectiveByComponentComplianceApi]],
      values:            Option[Seq[ComponentValueStatusReportApi]]
  ) derives JsonEncoder

  given byRuleByNodeByDirectiveByComponentComplianceApi(using
      precision: CompliancePrecision,
      level:     Int
  ): Transformer[ByRuleByNodeByDirectiveByComponentCompliance, ByRuleByNodeByDirectiveByComponentComplianceApi] = {
    (src: ByRuleByNodeByDirectiveByComponentCompliance) =>
      {
        val (subComponents, values) = src match {
          case ByRuleByNodeByDirectiveByBlockCompliance(_, _, scs) =>
            (Some(scs.map(_.transformInto[ByRuleByNodeByDirectiveByComponentComplianceApi])), None)
          case ByRuleByNodeByDirectiveByValueCompliance(_, _, vs)  =>
            if (level < 5) (None, None)
            else (None, Some(vs.map(_.transformInto[ComponentValueStatusReportApi])))
        }
        ByRuleByNodeByDirectiveByComponentComplianceApi(
          src.name,
          src.compliance.transformInto[Double],
          src.compliance.transformInto[ComplianceSerializable],
          subComponents,
          values
        )
      }
  }

  // GLOBAL
  object JsonGlobalCompliance {

    // we need that extra layer because we must not have a data container to avoid [] and keep compat
    final case class GlobalComplianceContainer(globalCompliance: GlobalComplianceApi) derives JsonEncoder

    final case class GlobalComplianceApi(compliance: Long, complianceDetails: Option[ComplianceSerializable])
        derives JsonEncoder {
      def withContainer: GlobalComplianceContainer = GlobalComplianceContainer(this)
    }

    given globalComplianceApi(using
        precision: CompliancePrecision
    ): Transformer[Option[(ComplianceLevel, Long)], GlobalComplianceApi] = {
      case None         => GlobalComplianceApi(-1, None)
      case Some((l, v)) => GlobalComplianceApi(v, Some(l.transformInto[ComplianceSerializable]))
    }
  }

  // DIRECTIVES
  object JsonByDirectiveCompliance {

    // this is needed to keep compat with pre-9.2 format:  { "data" : { "directiveCompliance" : { "id"....
    final case class ByDirectiveComplianceContainer(directiveCompliance: ByDirectiveComplianceApi) derives JsonEncoder

    final case class ByDirectiveComplianceApi(
        id:                DirectiveId,
        name:              String,
        compliance:        Double,
        mode:              ComplianceModeName,
        policyMode:        ComputedPolicyMode,
        complianceDetails: ComplianceSerializable,
        rules:             Option[Seq[ByDirectiveByRuleComplianceApi]],
        nodes:             Option[Seq[ByDirectiveNodeComplianceApi]]
    ) derives JsonEncoder

    given byDirectiveCompliance(using
        precision: CompliancePrecision,
        level:     Int
    ): Transformer[ByDirectiveCompliance, ByDirectiveComplianceApi] = {
      Transformer
        .define[ByDirectiveCompliance, ByDirectiveComplianceApi]
        .withFieldRenamed(_.compliance, _.complianceDetails)
        .withFieldComputed(
          _.rules,
          x => if (level < 2) None else Some(x.rules.map(_.transformInto[ByDirectiveByRuleComplianceApi]))
        )
        .withFieldComputed(
          _.nodes,
          x => if (level < 2) None else Some(x.nodes.map(_.transformInto[ByDirectiveNodeComplianceApi]))
        )
        .buildTransformer
    }

    final case class ByDirectiveByRuleComplianceApi(
        id:                RuleId,
        name:              String,
        compliance:        Double,
        policyMode:        ComputedPolicyMode,
        complianceDetails: ComplianceSerializable,
        skippedDetails:    Option[SkippedDetailsApi],
        components:        Option[Seq[ByRuleComponentComplianceApi]]
    ) derives JsonEncoder

    given byDirectiveByRuleComplianceApi(using
        precision: CompliancePrecision,
        level:     Int
    ): Transformer[ByDirectiveByRuleCompliance, ByDirectiveByRuleComplianceApi] = {
      Transformer
        .define[ByDirectiveByRuleCompliance, ByDirectiveByRuleComplianceApi]
        .withFieldRenamed(_.compliance, _.complianceDetails)
        .withFieldComputed(
          _.components,
          x => if (level < 3) None else Some(x.components.map(_.transformInto[ByRuleComponentComplianceApi]))
        )
        .buildTransformer
    }

    final case class ByRuleComponentComplianceApi(
        name:              ComponentName,
        compliance:        Double,
        complianceDetails: ComplianceSerializable,
        components:        Option[Seq[ByRuleComponentComplianceApi]],
        nodes:             Option[Seq[ByRuleNodeComplianceApi]]
    ) derives JsonEncoder

    given byRuleComponentComplianceApi(using
        precision: CompliancePrecision,
        level:     Int
    ): Transformer[ByRuleComponentCompliance, ByRuleComponentComplianceApi] = { (src: ByRuleComponentCompliance) =>
      {
        val (subComponents, nodes) = src match {
          case ByRuleBlockCompliance(_, _, scs) =>
            (Some(scs.map(_.transformInto[ByRuleComponentComplianceApi])), None)

          case ByRuleValueCompliance(_, _, ns) =>
            if (level < 4) (None, None)
            else (None, Some(ns.map(_.transformInto[ByRuleNodeComplianceApi])))
        }
        ByRuleComponentComplianceApi(
          src.name,
          src.compliance.transformInto[Double],
          src.compliance.transformInto[ComplianceSerializable],
          subComponents,
          nodes
        )
      }
    }

    final case class ByDirectiveNodeComplianceApi(
        id:                NodeId,
        name:              NodeName,
        compliance:        Double,
        policyMode:        ComputedPolicyMode,
        complianceDetails: ComplianceSerializable,
        rules:             Option[Seq[ByDirectiveByNodeRuleComplianceApi]]
    ) derives JsonEncoder

    given byDirectiveNodeComplianceApi(using
        precision: CompliancePrecision,
        level:     Int
    ): Transformer[ByDirectiveNodeCompliance, ByDirectiveNodeComplianceApi] = {
      Transformer
        .define[ByDirectiveNodeCompliance, ByDirectiveNodeComplianceApi]
        .withFieldComputed(
          _.rules,
          x => if (level < 3) None else Some(x.rules.map(_.transformInto[ByDirectiveByNodeRuleComplianceApi]))
        )
        .withFieldRenamed(_.compliance, _.complianceDetails)
        .buildTransformer
    }

    final case class ByDirectiveByNodeRuleComplianceApi(
        id:                RuleId,
        name:              String,
        compliance:        Double,
        policyMode:        ComputedPolicyMode,
        complianceDetails: ComplianceSerializable,
        components:        Option[Seq[ByRuleByNodeByDirectiveByComponentComplianceApi]]
    ) derives JsonEncoder

    given byDirectiveByNodeRuleComplianceApi(using
        precision: CompliancePrecision,
        level:     Int
    ): Transformer[ByDirectiveByNodeRuleCompliance, ByDirectiveByNodeRuleComplianceApi] = {
      Transformer
        .define[ByDirectiveByNodeRuleCompliance, ByDirectiveByNodeRuleComplianceApi]
        .withFieldRenamed(_.compliance, _.complianceDetails)
        .withFieldComputed(
          _.components,
          x => if (level < 4) None else Some(x.components.map(_.transformInto[ByRuleByNodeByDirectiveByComponentComplianceApi]))
        )
        .buildTransformer
    }
  }

  // RULES
  object JsonByRuleCompliance {

    final case class ByRuleRuleComplianceApi(
        id:                RuleId,
        name:              String,
        compliance:        Double,
        mode:              ComplianceModeName,
        policyMode:        ComputedPolicyMode,
        complianceDetails: ComplianceSerializable,
        directives:        Option[Seq[ByRuleDirectiveComplianceApi]],
        nodes:             Option[Seq[GroupComponentComplianceApi]]
    ) derives JsonEncoder

    given byRuleRuleCompliance(using
        precision: CompliancePrecision,
        level:     Int
    ): Transformer[ByRuleRuleCompliance, ByRuleRuleComplianceApi] = {
      Transformer
        .define[ByRuleRuleCompliance, ByRuleRuleComplianceApi]
        .withFieldRenamed(_.compliance, _.complianceDetails)
        .withFieldComputed(
          _.directives,
          x => if (level < 2) None else Some(x.directives.map(_.transformInto[ByRuleDirectiveComplianceApi]))
        )
        .withFieldComputed(_.nodes, x => if (level < 2) None else Some(x.nodes.map(_.transformInto[GroupComponentComplianceApi])))
        .buildTransformer
    }

    final case class ByRuleDirectiveComplianceApi(
        id:                DirectiveId,
        name:              DirectiveName,
        compliance:        Double,
        policyMode:        ComputedPolicyMode,
        complianceDetails: ComplianceSerializable,
        skippedDetails:    Option[SkippedDetailsApi],
        components:        Option[Seq[ByRuleComponentComplianceApi]]
    ) derives JsonEncoder

    given byRuleDirectiveComplianceApi(using
        precision: CompliancePrecision,
        level:     Int
    ): Transformer[ByRuleDirectiveCompliance, ByRuleDirectiveComplianceApi] = {
      Transformer
        .define[ByRuleDirectiveCompliance, ByRuleDirectiveComplianceApi]
        .withFieldComputed(
          _.components,
          x => if (level < 3) None else Some(x.components.map(_.transformInto[ByRuleComponentComplianceApi]))
        )
        .withFieldRenamed(_.compliance, _.complianceDetails)
        .buildTransformer
    }

    final case class ByRuleComponentComplianceApi(
        name:              ComponentName,
        compliance:        Double,
        complianceDetails: ComplianceSerializable,
        components:        Option[Seq[ByRuleComponentComplianceApi]],
        nodes:             Option[Seq[ByRuleNodeComplianceApi]]
    ) derives JsonEncoder

    given byRuleComponentComplianceApi(using
        precision: CompliancePrecision,
        level:     Int
    ): Transformer[ByRuleComponentCompliance, ByRuleComponentComplianceApi] = { (src: ByRuleComponentCompliance) =>
      {
        val (subComponents, nodes) = src match {
          case ByRuleBlockCompliance(_, _, scs) =>
            (Some(scs.map(_.transformInto[ByRuleComponentComplianceApi])), None)

          case ByRuleValueCompliance(_, _, ns) =>
            if (level < 4) (None, None)
            else (None, Some(ns.map(_.transformInto[ByRuleNodeComplianceApi])))
        }
        ByRuleComponentComplianceApi(
          src.name,
          src.compliance.transformInto[Double],
          src.compliance.transformInto[ComplianceSerializable],
          subComponents,
          nodes
        )
      }
    }

    final case class GroupComponentComplianceApi(
        id:                NodeId,
        name:              NodeName,
        compliance:        Double,
        policyMode:        ComputedPolicyMode,
        complianceDetails: ComplianceSerializable,
        directives:        Option[Seq[ByRuleByNodeByDirectiveComplianceApi]]
    ) derives JsonEncoder

    given groupComponentComplianceApi(using
        precision: CompliancePrecision,
        level:     Int
    ): Transformer[GroupComponentCompliance, GroupComponentComplianceApi] = {
      Transformer
        .define[GroupComponentCompliance, GroupComponentComplianceApi]
        .withFieldRenamed(_.compliance, _.complianceDetails)
        .withFieldComputed(
          _.directives,
          x => if (level < 3) None else Some(x.directives.map(_.transformInto[ByRuleByNodeByDirectiveComplianceApi]))
        )
        .buildTransformer
    }

    final case class ByRuleByNodeByDirectiveComplianceApi(
        id:                DirectiveId,
        name:              DirectiveName,
        compliance:        Double,
        policyMode:        ComputedPolicyMode,
        complianceDetails: ComplianceSerializable,
        components:        Option[Seq[ByRuleByNodeByDirectiveByComponentComplianceApi]]
    ) derives JsonEncoder

    given byRuleByNodeByDirectiveComplianceApi(using
        precision: CompliancePrecision,
        level:     Int
    ): Transformer[ByRuleByNodeByDirectiveCompliance, ByRuleByNodeByDirectiveComplianceApi] = {
      Transformer
        .define[ByRuleByNodeByDirectiveCompliance, ByRuleByNodeByDirectiveComplianceApi]
        .withFieldComputed(
          _.components,
          x => if (level < 4) None else Some(x.components.map(_.transformInto[ByRuleByNodeByDirectiveByComponentComplianceApi]))
        )
        .withFieldRenamed(_.compliance, _.complianceDetails)
        .buildTransformer
    }

    final case class RuleComplianceByDirectiveJson(
        id:                RuleId,
        name:              String,
        compliance:        Double,
        mode:              ComplianceModeName,
        policyMode:        ComputedPolicyMode,
        complianceDetails: ComplianceSerializable,
        directives:        Option[Seq[ByRuleDirectiveComplianceApi]]
    ) derives JsonEncoder

    given ruleComplianceByDirectiveJson(using
        precision: CompliancePrecision,
        level:     Int
    ): Transformer[ByRuleRuleCompliance, RuleComplianceByDirectiveJson] = {
      Transformer
        .define[ByRuleRuleCompliance, RuleComplianceByDirectiveJson]
        .withFieldRenamed(_.compliance, _.complianceDetails)
        .withFieldComputed(
          _.directives,
          x => if (level < 2) None else Some(x.directives.map(_.transformInto[ByRuleDirectiveComplianceApi]))
        )
        .buildTransformer
    }

    final case class RuleComplianceByNodeJson(
        id:                RuleId,
        name:              String,
        compliance:        Double,
        mode:              ComplianceModeName,
        policyMode:        ComputedPolicyMode,
        complianceDetails: ComplianceSerializable,
        nodes:             Option[Seq[GroupComponentComplianceApi]]
    ) derives JsonEncoder

    given ruleComplianceByNodeJson(using
        precision: CompliancePrecision,
        level:     Int
    ): Transformer[ByRuleRuleCompliance, RuleComplianceByNodeJson] = {
      Transformer
        .define[ByRuleRuleCompliance, RuleComplianceByNodeJson]
        .withFieldRenamed(_.compliance, _.complianceDetails)
        .withFieldComputed(_.nodes, x => if (level < 2) None else Some(x.nodes.map(_.transformInto[GroupComponentComplianceApi])))
        .buildTransformer
    }
  }

  // GROUPS
  object JsonByNodeGroupCompliance {

    final case class ByNodeGroupSummaryApi(
        id:       String,
        targeted: ByNodeGroupComplianceApi,
        global:   ByNodeGroupComplianceApi
    ) derives JsonEncoder

    final case class ByNodeGroupComplianceApi(
        // id is actually a target name, ie serialized node group id or special target name without "group:" or other prefix
        id:                String,
        name:              String,
        compliance:        Double,
        mode:              ComplianceModeName,
        complianceDetails: ComplianceSerializable,
        rules:             Option[Seq[ByNodeGroupRuleComplianceApi]],
        nodes:             Option[Seq[ByNodeGroupNodeComplianceApi]]
    ) derives JsonEncoder

    given byNodeGroupComplianceApi(using
        precision: CompliancePrecision,
        level:     Int
    ): Transformer[ByNodeGroupCompliance, ByNodeGroupComplianceApi] = {
      Transformer
        .define[ByNodeGroupCompliance, ByNodeGroupComplianceApi]
        .withFieldRenamed(_.compliance, _.complianceDetails)
        .withFieldComputed(
          _.rules,
          x => if (level < 2) None else Some(x.rules.map(_.transformInto[ByNodeGroupRuleComplianceApi]))
        )
        .withFieldComputed(
          _.nodes,
          x => if (level < 2) None else Some(x.nodes.map(_.transformInto[ByNodeGroupNodeComplianceApi]))
        )
        .buildTransformer
    }

    final case class ByNodeGroupRuleComplianceApi(
        id:                RuleId,
        name:              String,
        compliance:        Double,
        policyMode:        ComputedPolicyMode,
        complianceDetails: ComplianceSerializable,
        directives:        Option[Seq[ByNodeGroupByRuleDirectiveComplianceApi]]
    ) derives JsonEncoder

    given byDirectiveByRuleComplianceApi(using
        precision: CompliancePrecision,
        level:     Int
    ): Transformer[ByNodeGroupRuleCompliance, ByNodeGroupRuleComplianceApi] = {
      Transformer
        .define[ByNodeGroupRuleCompliance, ByNodeGroupRuleComplianceApi]
        .withFieldRenamed(_.compliance, _.complianceDetails)
        .withFieldComputed(
          _.directives,
          x => if (level < 3) None else Some(x.directives.map(_.transformInto[ByNodeGroupByRuleDirectiveComplianceApi]))
        )
        .buildTransformer
    }

    final case class ByNodeGroupByRuleDirectiveComplianceApi(
        id:                DirectiveId,
        name:              String,
        compliance:        Double,
        policyMode:        ComputedPolicyMode,
        complianceDetails: ComplianceSerializable,
        skippedDetails:    Option[SkippedDetailsApi],
        components:        Option[Seq[ByRuleComponentComplianceApi]]
    ) derives JsonEncoder

    given byNodeGroupByRuleDirectiveComplianceApi(using
        precision: CompliancePrecision,
        level:     Int
    ): Transformer[ByNodeGroupByRuleDirectiveCompliance, ByNodeGroupByRuleDirectiveComplianceApi] = {
      Transformer
        .define[ByNodeGroupByRuleDirectiveCompliance, ByNodeGroupByRuleDirectiveComplianceApi]
        .withFieldComputed(
          _.components,
          x => if (level < 4) None else Some(x.components.map(_.transformInto[ByRuleComponentComplianceApi]))
        )
        .withFieldRenamed(_.compliance, _.complianceDetails)
        .buildTransformer
    }

    final case class ByRuleComponentComplianceApi(
        name:              ComponentName,
        compliance:        Double,
        complianceDetails: ComplianceSerializable,
        components:        Option[Seq[ByRuleComponentComplianceApi]],
        nodes:             Option[Seq[ByRuleNodeComplianceApi]]
    ) derives JsonEncoder

    given byRuleComponentComplianceApi(using
        precision: CompliancePrecision,
        level:     Int
    ): Transformer[ByRuleComponentCompliance, ByRuleComponentComplianceApi] = { (src: ByRuleComponentCompliance) =>
      {
        val (subComponents, nodes) = src match {
          case ByRuleBlockCompliance(_, _, scs) =>
            (Some(scs.map(_.transformInto[ByRuleComponentComplianceApi])), None)

          case ByRuleValueCompliance(_, _, ns) =>
            if (level < 5) (None, None)
            else (None, Some(ns.map(_.transformInto[ByRuleNodeComplianceApi])))
        }
        ByRuleComponentComplianceApi(
          src.name,
          src.compliance.transformInto[Double],
          src.compliance.transformInto[ComplianceSerializable],
          subComponents,
          nodes
        )
      }
    }

    final case class ByNodeGroupNodeComplianceApi(
        id:                NodeId,
        name:              String,
        mode:              ComplianceModeName,
        compliance:        Double,
        policyMode:        ComputedPolicyMode,
        complianceDetails: ComplianceSerializable,
        rules:             Option[Seq[ByNodeRuleComplianceApi]]
    ) derives JsonEncoder

    given byNodeGroupNodeCompliance(using
        precision: CompliancePrecision,
        level:     Int
    ): Transformer[ByNodeGroupNodeCompliance, ByNodeGroupNodeComplianceApi] = {
      Transformer
        .define[ByNodeGroupNodeCompliance, ByNodeGroupNodeComplianceApi]
        .withFieldComputed(
          _.rules,
          x => if (level < 3) None else Some(x.rules.map(_.transformInto[ByNodeRuleComplianceApi]))
        )
        .withFieldRenamed(_.compliance, _.complianceDetails)
        .buildTransformer
    }

    final case class ByNodeRuleComplianceApi(
        id:                RuleId,
        name:              String,
        compliance:        Double,
        policyMode:        ComputedPolicyMode,
        complianceDetails: ComplianceSerializable,
        directives:        Option[Seq[ByNodeDirectiveComplianceApi]]
    ) derives JsonEncoder

    given byNodeRuleComplianceApi(using
        precision: CompliancePrecision,
        level:     Int
    ): Transformer[ByNodeRuleCompliance, ByNodeRuleComplianceApi] = {
      Transformer
        .define[ByNodeRuleCompliance, ByNodeRuleComplianceApi]
        .withFieldRenamed(_.compliance, _.complianceDetails)
        .withFieldComputed(
          _.directives,
          x => if (level < 4) None else Some(x.directives.map(_.transformInto[ByNodeDirectiveComplianceApi]))
        )
        .buildTransformer
    }
  }

  final case class ByNodeDirectiveComplianceApi(
      id:                DirectiveId,
      name:              String,
      compliance:        Double,
      policyMode:        ComputedPolicyMode,
      complianceDetails: ComplianceSerializable,
      components:        Option[Seq[ComponentStatusReportApi]]
  ) derives JsonEncoder

  given byNodeDirectiveComplianceApi(using
      precision: CompliancePrecision,
      level:     Int
  ): Transformer[ByNodeDirectiveCompliance, ByNodeDirectiveComplianceApi] = {
    Transformer
      .define[ByNodeDirectiveCompliance, ByNodeDirectiveComplianceApi]
      .withFieldRenamed(_.compliance, _.complianceDetails)
      .withFieldComputed(
        _.components,
        x => if (level < 5) None else Some(x.components.map(_.transformInto[ComponentStatusReportApi]))
      )
      .buildTransformer
  }

  final case class ComponentStatusReportApi(
      name:              String,
      compliance:        Double,
      complianceDetails: ComplianceSerializable,
      components:        Option[Seq[ComponentStatusReportApi]],
      values:            Option[Seq[ComponentValueStatusReportApi]]
  ) derives JsonEncoder

  given byRuleComponentComplianceApi(using
      precision: CompliancePrecision,
      level:     Int
  ): Transformer[ComponentStatusReport, ComponentStatusReportApi] = { (src: ComponentStatusReport) =>
    {
      val (subComponents, values) = src match {
        case BlockStatusReport(_, _, scs) =>
          (Some(scs.map(_.transformInto[ComponentStatusReportApi])), None)

        case ValueStatusReport(_, _, vs) =>
          if (level < 4) (None, None)
          else (None, Some(vs.map(_.transformInto[ComponentValueStatusReportApi])))
      }
      ComponentStatusReportApi(
        src.componentName,
        src.compliance.transformInto[Double],
        src.compliance.transformInto[ComplianceSerializable],
        subComponents,
        values
      )
    }
  }

  // NODES
  object JsonByNodeCompliance {
    final case class ByNodeNodeComplianceApi(
        id:                NodeId, // compliance by nodes
        name:              String,
        compliance:        Double,
        mode:              ComplianceModeName,
        policyMode:        ComputedPolicyMode,
        complianceDetails: ComplianceSerializable,
        rules:             Option[Seq[JsonByNodeGroupCompliance.ByNodeRuleComplianceApi]]
    ) derives JsonEncoder

    given byNodeNodeComplianceApi(using
        precision: CompliancePrecision,
        level:     Int
    ): Transformer[ByNodeNodeCompliance, ByNodeNodeComplianceApi] = {
      Transformer
        .define[ByNodeNodeCompliance, ByNodeNodeComplianceApi]
        .withFieldComputed(
          _.rules,
          x => {
            if (level < 2) None
            else Some(x.nodeCompliances.map(_.transformInto[JsonByNodeGroupCompliance.ByNodeRuleComplianceApi]))
          }
        )
        .withFieldRenamed(_.compliance, _.complianceDetails)
        .buildTransformer
    }

  }

}

object ComplianceUtils {
  def extractComplianceLevel(params: Map[String, List[String]]): IOResult[Option[Int]] = {
    params.get("level") match {
      case None | Some(Nil) => None.succeed
      case Some(h :: tail)  => // only take into account the first level param is several are passed
        try {
          Some(h.toInt).succeed
        } catch {
          case ex: NumberFormatException =>
            Inconsistency(s"level (displayed level of compliance details) must be an integer, was: '${h}'").fail
        }
    }
  }

  def extractPercentPrecision(params: Map[String, List[String]]): IOResult[Option[CompliancePrecision]] = {
    params.get("precision") match {
      case None | Some(Nil) => None.succeed
      case Some(h :: tail)  => // only take into account the first level param is several are passed
        for {
          extracted <- try {
                         h.toInt.succeed
                       } catch {
                         case ex: NumberFormatException =>
                           Inconsistency(s"percent precision must be an integer, was: '${h}'").fail
                       }
          level     <- CompliancePrecision.fromPrecision(extracted).toIO
        } yield {
          Some(level)
        }

    }
  }

  def extractComplianceFormat(params: Map[String, List[String]]): IOResult[ComplianceFormat] = {
    params.get("format") match {
      case None | Some(Nil) | Some("" :: Nil) =>
        ComplianceFormat.JSON.succeed // by default if no there is no format, should I choose the only one available ?
      case Some(format :: _)                  =>
        ComplianceFormat.fromValue(format).left.map(Inconsistency.apply).toIO
    }
  }
}
