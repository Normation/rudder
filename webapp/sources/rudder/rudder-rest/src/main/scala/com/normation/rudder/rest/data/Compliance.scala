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
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.reports.*
import com.normation.rudder.reports.ComplianceModeName
import com.normation.rudder.web.services.ComputePolicyMode.ComputedPolicyMode
import enumeratum.*
import java.lang
import net.liftweb.json.*
import net.liftweb.json.JsonDSL.*
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.QuoteMode
import scala.collection.immutable

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
    name:       String,
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

final case class ByRuleDirectiveCompliance(
    id:             DirectiveId,
    name:           String,
    compliance:     ComplianceLevel,
    skippedDetails: Option[SkippedDetails],
    policyMode:     ComputedPolicyMode,
    components:     Seq[ByRuleComponentCompliance]
)

sealed trait ByRuleComponentCompliance extends ComponentComplianceByNode {
  def name:       String
  def compliance: ComplianceLevel
}

final case class ByRuleBlockCompliance(
    name:           String,
    reportingLogic: ReportingLogic,
    subComponents:  Seq[ByRuleComponentCompliance]
) extends ByRuleComponentCompliance with BlockComplianceByNode[ByRuleComponentCompliance] {
  override def subs: List[ByRuleComponentCompliance & ComponentCompliance] = subComponents.toList

  override def componentName: String = name
}

final case class ByRuleValueCompliance(
    name:       String,
    compliance: ComplianceLevel,
    nodes:      List[ByRuleNodeCompliance]
) extends ByRuleComponentCompliance {
  override def componentName: String = name

  override def allReports: List[ReportType] = reportsByNode.values.flatten.toList

  override def reportsByNode: Map[NodeId, Seq[ReportType]] =
    nodes.map(n => (n.id, n.values.flatMap(c => c.messages.map(_ => c.status)))).toMap
}

final case class ByRuleNodeCompliance(
    id:         NodeId,
    name:       String,
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
    name:       String,
    compliance: ComplianceLevel,
    policyMode: ComputedPolicyMode,
    directives: Seq[ByRuleByNodeByDirectiveCompliance]
)

final case class ByRuleByNodeByDirectiveCompliance(
    id:             DirectiveId,
    name:           String,
    compliance:     ComplianceLevel,
    skippedDetails: Option[SkippedDetails],
    policyMode:     ComputedPolicyMode,
    components:     Seq[ByRuleByNodeByDirectiveByComponentCompliance]
)

sealed trait ByRuleByNodeByDirectiveByComponentCompliance extends ComponentCompliance {
  def name:       String
  def compliance: ComplianceLevel
}

final case class ByRuleByNodeByDirectiveByBlockCompliance(
    name:           String,
    reportingLogic: ReportingLogic,
    subComponents:  Seq[ByRuleByNodeByDirectiveByComponentCompliance]
) extends ByRuleByNodeByDirectiveByComponentCompliance with BlockCompliance[ByRuleByNodeByDirectiveByComponentCompliance] {
  override def subs: List[ByRuleByNodeByDirectiveByComponentCompliance & ComponentCompliance] = subComponents.toList

  override def componentName: String = name
}

final case class ByRuleByNodeByDirectiveByValueCompliance(
    name:       String,
    compliance: ComplianceLevel,
    values:     Seq[ComponentValueStatusReport]
) extends ByRuleByNodeByDirectiveByComponentCompliance {
  override def componentName: String = name

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
  ): Seq[((NodeId, String, ComputedPolicyMode), ByRuleByNodeByDirectiveByComponentCompliance)] = {
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
      (nodeId, data) <- directives.flatMap { d =>
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
      GroupComponentCompliance(nodeId._1, nodeId._2, ComplianceLevel.sum(subs.map(_.compliance)), nodeId._3, subs)
    }
  }

  // The goal here is to build a Compliance structure based on Nodes from a Compliance Structure Based on Directives
  // That contains Node reference as leaf
  // So we will go deep in the data structure to take reference to node then reconstruct the tree from the leaf
  def fromRules(directives: Seq[ByDirectiveByRuleCompliance]): immutable.Iterable[ByDirectiveNodeCompliance] = {
    for {
      // Regroup all Directive by node getting Nodes values from components
      (nodeId, data) <- directives.flatMap { d =>
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
      ByDirectiveNodeCompliance(nodeId._1, nodeId._2, nodeId._3, ComplianceLevel.sum(subs.map(_.compliance)), subs)
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

  // use "," , quote everything with ", line separator is \n
  val csvFormat: CSVFormat = CSVFormat.DEFAULT.builder().setQuoteMode(QuoteMode.ALL).setRecordSeparator("\n").get()

  def recurseComponent(
      component: ByRuleComponentCompliance,
      block:     List[String]
  ): Seq[(List[String], String, String, String, String, String)] = {
    component match {
      case component: ByRuleValueCompliance =>
        component.nodes.flatMap { node =>
          node.values.flatMap { value =>
            value.messages.map { report =>
              (
                block,
                component.name,
                node.name,
                value.componentValue,
                JsonCompliance.statusDisplayName(report.reportType),
                report.message.getOrElse("")
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
          List(r.name, block.mkString(","), component, node, value, status, msg)
        }
      }
      // csvFormat take care of the line separator
      val out      = new lang.StringBuilder()
      csvFormat.printRecord(out, "Rule", "Block", "Component", "Node", "Value", "Status", "Message")
      csvLines.foreach(l => csvFormat.printRecord(out, l*))
      out.toString
    }
  }
}

object JsonCompliance {

  // global compliance
  implicit class JsonGlobalCompliance(val optCompliance: Option[(ComplianceLevel, Long)]) extends AnyVal {
    def toJson(precision: CompliancePrecision): JValue = {
      optCompliance match {
        case Some((details, value)) =>
          ("globalCompliance"      -> (
            ("compliance"          -> value)
            ~ ("complianceDetails" -> percents(details, precision))
          ))

        case None =>
          ("globalCompliance" -> (
            ("compliance"     -> -1)
          ))
      }
    }
  }

  implicit class JsonbyDirectiveCompliance(val directive: ByDirectiveCompliance) extends AnyVal {
    def toJsonV6: JObject = (
      ("id"                    -> directive.id.serialize)
        ~ ("name"              -> directive.name)
        ~ ("compliance"        -> directive.compliance.complianceWithoutPending())
        ~ ("policyMode"        -> directive.policyMode.name)
        ~ ("complianceDetails" -> percents(directive.compliance, CompliancePrecision.Level2))
        ~ ("rules"             -> rules(directive.rules, 10, CompliancePrecision.Level2))
        ~ ("nodes"             -> byNodes(directive.nodes, 10, CompliancePrecision.Level2))
    )
    def toJson(level: Int, precision: CompliancePrecision): JObject = (
      ("id"                    -> directive.id.serialize)
        ~ ("name"              -> directive.name)
        ~ ("compliance"        -> directive.compliance.complianceWithoutPending(precision))
        ~ ("mode"              -> directive.mode.name)
        ~ ("policyMode"        -> directive.policyMode.name)
        ~ ("complianceDetails" -> percents(directive.compliance, precision))
        ~ ("rules"             -> rules(directive.rules, level, precision))
        ~ ("nodes"             -> byNodes(directive.nodes, level, precision))
    )

    private def byNodes(
        nodes:     Seq[ByDirectiveNodeCompliance],
        level:     Int,
        precision: CompliancePrecision
    ): Option[JsonAST.JValue] = {
      if (level < 2) None
      else {
        Some(nodes.map { node =>
          (
            ("id"                  -> node.id.value)
            ~ ("name"              -> node.name)
            ~ ("policyMode"        -> node.policyMode.name)
            ~ ("compliance"        -> node.compliance.complianceWithoutPending(precision))
            ~ ("complianceDetails" -> percents(node.compliance, precision))
            ~ ("rules"             -> byRule(node.rules, level, precision))
          )
        })
      }
    }

    private def byRule(
        rules:     Seq[ByDirectiveByNodeRuleCompliance],
        level:     Int,
        precision: CompliancePrecision
    ): Option[JsonAST.JValue] = {
      if (level < 3) None
      else {
        Some(rules.map { rule =>
          (
            ("id"                  -> rule.id.uid.value)
            ~ ("name"              -> rule.name)
            ~ ("compliance"        -> rule.compliance.complianceWithoutPending(precision))
            ~ ("policyMode"        -> rule.policyMode.name)
            ~ ("complianceDetails" -> percents(rule.compliance, precision))
            ~ ("components"        -> byNodeByDirectiveByComponents(rule.components, level, precision))
          )
        })
      }
    }

    private def byNodeByDirectiveByComponents(
        comps:     Seq[ByRuleByNodeByDirectiveByComponentCompliance],
        level:     Int,
        precision: CompliancePrecision
    ): Option[JsonAST.JValue] = {
      if (level < 4) None
      else {
        Some(comps.map { component =>
          (
            ("name"                -> component.name)
            ~ ("compliance"        -> component.compliance.complianceWithoutPending(precision))
            ~ ("complianceDetails" -> percents(component.compliance, precision))
            ~ (component match {
              case component: ByRuleByNodeByDirectiveByBlockCompliance =>
                ("components" -> byNodeByDirectiveByComponents(component.subComponents, level, precision))
              case component: ByRuleByNodeByDirectiveByValueCompliance =>
                ("values" -> values(component.values, level))
            })
          )
        })
      }
    }

    private def rules(
        rules:     Seq[ByDirectiveByRuleCompliance],
        level:     Int,
        precision: CompliancePrecision
    ): Option[JsonAST.JValue] = {
      if (level < 2) None
      else {
        Some(rules.map { rule =>
          (
            ("id"                  -> rule.id.uid.value)
            ~ ("name"              -> rule.name)
            ~ ("compliance"        -> rule.compliance.complianceWithoutPending(precision))
            ~ ("policyMode"        -> rule.policyMode.name)
            ~ ("complianceDetails" -> percents(rule.compliance, precision))
            ~ ("skippedDetails"    -> rule.skippedDetails.map(s =>
              ("overridingRuleId" -> s.overridingRuleId.serialize) ~ ("overridingRuleName" -> s.overridingRuleName)
            ))
            ~ ("components"        -> components(rule.components, level, precision))
          )
        })
      }
    }

    private def components(
        comps:     Seq[ByRuleComponentCompliance],
        level:     Int,
        precision: CompliancePrecision
    ): Option[JsonAST.JValue] = {
      if (level < 3) None
      else {
        Some(comps.map { component =>
          (
            ("name"                -> component.name)
            ~ ("compliance"        -> component.compliance.complianceWithoutPending(precision))
            ~ ("complianceDetails" -> percents(component.compliance, precision))
            ~ (component match {
              case component: ByRuleBlockCompliance => // this case should not happened because we are only get nodes compliance here
                ("components" -> components(component.subComponents, level, precision))
              case component: ByRuleValueCompliance =>
                ("nodes" -> nodes(component.nodes, level, precision))
            })
          )
        })
      }
    }

    def values(values: Seq[ComponentValueStatusReport], level: Int): Option[JsonAST.JValue] = {
      if (level < 5) None
      else {
        Some(values.map { value =>
          (
            ("value"     -> value.componentValue)
            ~ ("reports" -> value.messages.map { report =>
              (
                ("status"    -> statusDisplayName(report.reportType))
                ~ ("message" -> report.message)
              )
            })
          )
        })
      }
    }
    private def nodes(
        nodes:     Seq[ByRuleNodeCompliance],
        level:     Int,
        precision: CompliancePrecision
    ): Option[JsonAST.JValue] = {
      if (level < 4) None
      else {
        Some(nodes.map { node =>
          (
            ("id"                  -> node.id.value)
            ~ ("name"              -> node.name)
            ~ ("policyMode"        -> node.policyMode.name)
            ~ ("compliance"        -> node.compliance.complianceWithoutPending(precision))
            ~ ("complianceDetails" -> percents(node.compliance, precision))
            ~ ("values"            -> values(node.values, level))
          )
        })
      }

    }

  }

  implicit class JsonByRuleCompliance(val rule: ByRuleRuleCompliance) extends AnyVal {
    def toJsonV6: JObject = (
      ("id"                    -> rule.id.serialize)
        ~ ("name"              -> rule.name)
        ~ ("compliance"        -> rule.compliance.complianceWithoutPending())
        ~ ("policyMode"        -> rule.policyMode.name)
        ~ ("complianceDetails" -> percents(rule.compliance, CompliancePrecision.Level2))
        ~ ("directives"        -> directives(rule.directives, 10, CompliancePrecision.Level2))
    )

    /*
     * level:
     * - up to 1 : rules,
     * - 2: rules & directives
     * - 3: rules, directives, components
     * - 4 and up: rules, directives, components, node and component values
     */

    def toJson(level: Int, precision: CompliancePrecision): JObject = (
      ("id"                    -> rule.id.serialize)
        ~ ("name"              -> rule.name)
        ~ ("compliance"        -> rule.compliance.complianceWithoutPending(precision))
        ~ ("mode"              -> rule.mode.name)
        ~ ("policyMode"        -> rule.policyMode.name)
        ~ ("complianceDetails" -> percents(rule.compliance, precision))
        ~ ("directives"        -> directives(rule.directives, level, precision))
        ~ ("nodes"             -> byNodes(rule.nodes, level, precision))
    )

    private def directives(
        directives: Seq[ByRuleDirectiveCompliance],
        level:      Int,
        precision:  CompliancePrecision
    ): Option[JsonAST.JValue] = {
      if (level < 2) None
      else {
        Some(directives.map { directive =>
          (
            ("id"                  -> directive.id.serialize)
            ~ ("name"              -> directive.name)
            ~ ("compliance"        -> directive.compliance.complianceWithoutPending(precision))
            ~ ("policyMode"        -> directive.policyMode.name)
            ~ ("complianceDetails" -> percents(directive.compliance, precision))
            ~ ("skippedDetails"    -> directive.skippedDetails.map(s =>
              ("overridingRuleId" -> s.overridingRuleId.serialize) ~ ("overridingRuleName" -> s.overridingRuleName)
            ))
            ~ ("components"        -> components(directive.components, level, precision))
          )
        })
      }
    }
    private def byNodes(
        nodes:     Seq[GroupComponentCompliance],
        level:     Int,
        precision: CompliancePrecision
    ): Option[JsonAST.JValue] = {
      if (level < 2) None
      else {
        Some(nodes.map { node =>
          (
            ("id"                  -> node.id.value)
            ~ ("name"              -> node.name)
            ~ ("compliance"        -> node.compliance.complianceWithoutPending(precision))
            ~ ("policyMode"        -> node.policyMode.name)
            ~ ("complianceDetails" -> percents(node.compliance, precision))
            ~ ("directives"        -> byNodesByDirectives(node.directives, level, precision))
          )
        })
      }
    }

    private def byNodesByDirectives(
        directives: Seq[ByRuleByNodeByDirectiveCompliance],
        level:      Int,
        precision:  CompliancePrecision
    ): Option[JsonAST.JValue] = {
      if (level < 3) None
      else {
        Some(directives.map { directive =>
          (
            ("id"                  -> directive.id.serialize)
            ~ ("name"              -> directive.name)
            ~ ("compliance"        -> directive.compliance.complianceWithoutPending(precision))
            ~ ("policyMode"        -> directive.policyMode.name)
            ~ ("complianceDetails" -> percents(directive.compliance, precision))
            ~ ("components"        -> byNodeByDirectiveByComponents(directive.components, level, precision))
          )
        })
      }
    }
    private def components(
        comps:     Seq[ByRuleComponentCompliance],
        level:     Int,
        precision: CompliancePrecision
    ): Option[JsonAST.JValue] = {
      if (level < 3) None
      else {
        Some(comps.map { component =>
          (
            ("name"                -> component.name)
            ~ ("compliance"        -> component.compliance.complianceWithoutPending(precision))
            ~ ("complianceDetails" -> percents(component.compliance, precision))
            ~ (component match {
              case component: ByRuleBlockCompliance =>
                ("components" -> components(component.subComponents, level, precision))
              case component: ByRuleValueCompliance =>
                ("nodes" -> nodes(component.nodes, level, precision))
            })
          )
        })
      }
    }

    private def byNodeByDirectiveByComponents(
        comps:     Seq[ByRuleByNodeByDirectiveByComponentCompliance],
        level:     Int,
        precision: CompliancePrecision
    ): Option[JsonAST.JValue] = {
      if (level < 4) None
      else {
        Some(comps.map { component =>
          (
            ("name"                -> component.name)
            ~ ("compliance"        -> component.compliance.complianceWithoutPending(precision))
            ~ ("complianceDetails" -> percents(component.compliance, precision))
            ~ (component match {
              case component: ByRuleByNodeByDirectiveByBlockCompliance =>
                ("components" -> byNodeByDirectiveByComponents(component.subComponents, level, precision))
              case component: ByRuleByNodeByDirectiveByValueCompliance =>
                ("values" -> values(component.values, level))
            })
          )
        })
      }
    }

    def values(values: Seq[ComponentValueStatusReport], level: Int): Option[JsonAST.JValue] = {
      if (level < 5) None
      else {
        Some(values.map { value =>
          (
            ("value"     -> value.componentValue)
            ~ ("reports" -> value.messages.map { report =>
              (
                ("status"    -> statusDisplayName(report.reportType))
                ~ ("message" -> report.message)
              )
            })
          )
        })
      }
    }
    private def nodes(
        nodes:     Seq[ByRuleNodeCompliance],
        level:     Int,
        precision: CompliancePrecision
    ): Option[JsonAST.JValue] = {
      if (level < 4) None
      else {
        Some(nodes.map { node =>
          (
            ("id"                  -> node.id.value)
            ~ ("name"              -> node.name)
            ~ ("compliance"        -> node.compliance.complianceWithoutPending(precision))
            ~ ("policyMode"        -> node.policyMode.name)
            ~ ("complianceDetails" -> percents(node.compliance, precision))
            ~ ("values"            -> values(node.values, level))
          )
        })
      }

    }

  }

  implicit class JsonByNodeGroupCompliance(val nodeGroup: ByNodeGroupCompliance) extends AnyVal {

    def toJson(level: Int, precision: CompliancePrecision): JObject = {
      (("id"                 -> nodeGroup.id)
      ~ ("name"              -> nodeGroup.name)
      ~ ("compliance"        -> nodeGroup.compliance.complianceWithoutPending(precision))
      ~ ("mode"              -> nodeGroup.mode.name)
      ~ ("complianceDetails" -> percents(nodeGroup.compliance, precision))
      ~ ("rules"             -> byRule(nodeGroup.rules, level, precision))
      ~ ("nodes"             -> byNode(nodeGroup.nodes, level, precision)))
    }

    private def byRule(
        rules:     Seq[ByNodeGroupRuleCompliance],
        level:     Int,
        precision: CompliancePrecision
    ): Option[JsonAST.JValue] = {
      if (level < 2) None
      else {
        Some(rules.map { rule =>
          (("id"                 -> rule.id.serialize)
          ~ ("name"              -> rule.name)
          ~ ("compliance"        -> rule.compliance.complianceWithoutPending(precision))
          ~ ("policyMode"        -> rule.policyMode.name)
          ~ ("complianceDetails" -> percents(rule.compliance, precision))
          ~ ("directives"        -> directives(rule.directives, level, precision)))
        })
      }
    }

    private def byNode(
        nodes:     Seq[ByNodeGroupNodeCompliance],
        level:     Int,
        precision: CompliancePrecision
    ): Option[JsonAST.JValue] = {
      if (level < 2) None
      else {
        Some(nodes.map { node =>
          (("id"                 -> node.id.value)
          ~ ("name"              -> node.name)
          ~ ("mode"              -> node.mode.name)
          ~ ("compliance"        -> node.compliance.complianceWithoutPending(precision))
          ~ ("policyMode"        -> node.policyMode.name)
          ~ ("complianceDetails" -> percents(node.compliance, precision))
          ~ ("rules"             -> byNodeRules(node.rules, level, precision)))
        })
      }
    }

    private def byNodeRules(
        rules:     Seq[ByNodeRuleCompliance],
        level:     Int,
        precision: CompliancePrecision
    ): Option[JsonAST.JValue] = {
      if (level < 2) None
      else {
        Some(rules.map { rule =>
          (
            ("id"                  -> rule.id.serialize)
            ~ ("name"              -> rule.name)
            ~ ("compliance"        -> rule.compliance.complianceWithoutPending(precision))
            ~ ("policyMode"        -> rule.policyMode.name)
            ~ ("complianceDetails" -> percents(rule.compliance, precision))
            ~ ("directives"        -> byNodeDirectives(rule.directives, level, precision))
          )
        })
      }
    }

    private def byNodeDirectives(
        directives: Seq[ByNodeDirectiveCompliance],
        level:      Int,
        precision:  CompliancePrecision
    ): Option[JsonAST.JValue] = {
      if (level < 2) None
      else {
        Some(directives.map { directive =>
          (
            ("id"                  -> directive.id.serialize)
            ~ ("name"              -> directive.name)
            ~ ("compliance"        -> directive.compliance.complianceWithoutPending(precision))
            ~ ("policyMode"        -> directive.policyMode.name)
            ~ ("complianceDetails" -> percents(directive.compliance, precision))
            ~ ("components"        -> byNodeComponents(directive.components, level, precision))
          )
        })
      }
    }

    private def byNodeComponents(
        comps:     List[ComponentStatusReport],
        level:     Int,
        precision: CompliancePrecision
    ): Option[JsonAST.JValue] = {
      if (level < 3) None
      else {
        Some(comps.map {
          case component =>
            (
              ("name"                -> component.componentName)
              ~ ("compliance"        -> component.compliance.complianceWithoutPending(precision))
              ~ ("complianceDetails" -> percents(component.compliance, precision))
              ~ (component match {
                case component: BlockStatusReport =>
                  ("components" -> byNodeComponents(component.subComponents, level, precision))
                case component: ValueStatusReport => ("values" -> values(component.componentValues, level))
              })
            )
        })
      }
    }

    private def directives(
        directives: Seq[ByNodeGroupByRuleDirectiveCompliance],
        level:      Int,
        precision:  CompliancePrecision
    ): Option[JsonAST.JValue] = {
      if (level < 2) None
      else {
        Some(directives.map { directive =>
          (
            ("id"                  -> directive.id.serialize)
            ~ ("name"              -> directive.name)
            ~ ("compliance"        -> directive.compliance.complianceWithoutPending(precision))
            ~ ("policyMode"        -> directive.policyMode.name)
            ~ ("complianceDetails" -> percents(directive.compliance, precision))
            ~ ("skippedDetails"    -> directive.skippedDetails.map(s =>
              ("overridingRuleId" -> s.overridingRuleId.serialize) ~ ("overridingRuleName" -> s.overridingRuleName)
            ))
            ~ ("components"        -> components(directive.components, level, precision))
          )
        })
      }
    }

    private def components(
        comps:     Seq[ByRuleComponentCompliance],
        level:     Int,
        precision: CompliancePrecision
    ): Option[JsonAST.JValue] = {
      if (level < 3) None
      else {
        Some(comps.map { component =>
          (
            ("name"                -> component.name)
            ~ ("compliance"        -> component.compliance.complianceWithoutPending(precision))
            ~ ("complianceDetails" -> percents(component.compliance, precision))
            ~ (component match {
              case component: ByRuleBlockCompliance =>
                ("components" -> components(component.subComponents, level, precision))
              case component: ByRuleValueCompliance =>
                ("nodes" -> nodes(component.nodes, level, precision))
            })
          )
        })
      }
    }

    private def nodes(
        nodes:     Seq[ByRuleNodeCompliance],
        level:     Int,
        precision: CompliancePrecision
    ): Option[JsonAST.JValue] = {
      if (level < 4) None
      else {
        Some(nodes.map { node =>
          (
            ("id"                  -> node.id.value)
            ~ ("name"              -> node.name)
            ~ ("compliance"        -> node.compliance.complianceWithoutPending(precision))
            ~ ("policyMode"        -> node.policyMode.name)
            ~ ("complianceDetails" -> percents(node.compliance, precision))
            ~ ("values"            -> values(node.values, level))
          )
        })
      }
    }

    private def values(values: Seq[ComponentValueStatusReport], level: Int): Option[JsonAST.JValue] = {
      if (level < 5) None
      else {
        Some(values.map { value =>
          (
            ("value"     -> value.componentValue)
            ~ ("reports" -> value.messages.map { report =>
              (
                ("status"    -> statusDisplayName(report.reportType))
                ~ ("message" -> report.message)
              )
            })
          )
        })
      }
    }

  }

  implicit class JsonByNodeCompliance(val n: ByNodeNodeCompliance) extends AnyVal {
    def toJsonV6: JObject = (
      ("id"                    -> n.id.value)
        ~ ("compliance"        -> n.compliance.complianceWithoutPending())
        ~ ("policyMode"        -> n.policyMode.name)
        ~ ("complianceDetails" -> percents(n.compliance, CompliancePrecision.Level2))
        ~ ("rules"             -> rules(n.nodeCompliances, 10, CompliancePrecision.Level2))
    )

    /*
     * level:
     * - up to 1 : nodes,
     * - 2: nodes & rules
     * - 3: nodes, rules, directives
     * - 4: nodes, rules, directives, components
     * - 5 and up: nodes, rules, directives, components and component values
     */

    def toJson(level: Int, precision: CompliancePrecision): JObject = (
      ("id"                    -> n.id.value)
        ~ ("name"              -> n.name)
        ~ ("compliance"        -> n.compliance.complianceWithoutPending(precision))
        ~ ("mode"              -> n.mode.name)
        ~ ("policyMode"        -> n.policyMode.name)
        ~ ("complianceDetails" -> percents(n.compliance, precision))
        ~ ("rules"             -> rules(n.nodeCompliances, level, precision))
    )

    private def rules(
        rules:     Seq[ByNodeRuleCompliance],
        level:     Int,
        precision: CompliancePrecision
    ): Option[JsonAST.JValue] = {
      if (level < 2) None
      else {
        Some(rules.map { rule =>
          (
            ("id"                  -> rule.id.serialize)
            ~ ("name"              -> rule.name)
            ~ ("compliance"        -> rule.compliance.complianceWithoutPending(precision))
            ~ ("policyMode"        -> rule.policyMode.name)
            ~ ("complianceDetails" -> percents(rule.compliance, precision))
            ~ ("directives"        -> directives(rule.directives, level, precision))
          )
        })
      }
    }

    private def directives(
        directives: Seq[ByNodeDirectiveCompliance],
        level:      Int,
        precision:  CompliancePrecision
    ): Option[JsonAST.JValue] = {
      if (level < 3) None
      else {
        Some(directives.map { directive =>
          (
            ("id"                  -> directive.id.serialize)
            ~ ("name"              -> directive.name)
            ~ ("compliance"        -> directive.compliance.complianceWithoutPending(precision))
            ~ ("policyMode"        -> directive.policyMode.name)
            ~ ("complianceDetails" -> percents(directive.compliance, precision))
            ~ ("skippedDetails"    -> directive.skippedDetails.map(s =>
              ("overridingRuleId" -> s.overridingRuleId.serialize) ~ ("overridingRuleName" -> s.overridingRuleName)
            ))
            ~ ("components"        -> components(directive.components, level, precision))
          )
        })
      }
    }

    private def components(
        comps:     List[ComponentStatusReport],
        level:     Int,
        precision: CompliancePrecision
    ): Option[JsonAST.JValue] = {
      if (level < 4) None
      else {
        Some(comps.map {
          case component =>
            (
              ("name"                -> component.componentName)
              ~ ("compliance"        -> component.compliance.complianceWithoutPending(precision))
              ~ ("complianceDetails" -> percents(component.compliance, precision))
              ~ (component match {
                case component: BlockStatusReport =>
                  ("components" -> components(component.subComponents, level, precision))
                case component: ValueStatusReport => ("values" -> values(component.componentValues, level))
              })
            )
        })
      }
    }

    private def values(componentValues: List[ComponentValueStatusReport], level: Int): Option[JsonAST.JValue] = {
      if (level < 5) None
      else {
        Some(componentValues.map {
          case value =>
            (
              ("value"     -> value.componentValue)
              ~ ("reports" -> value.messages.map { report =>
                (
                  ("status"    -> statusDisplayName(report.reportType))
                  ~ ("message" -> report.message)
                )
              })
            )
        })
      }
    }

  }

  def statusDisplayName(r: ReportType): String = {
    import ReportType.*

    r match {
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

  /**
   * By default, we want to only display non 0 compliance percent
   *
   * We also want to define clear user facing severity levels, in particular,
   * the semantic of unexpected / missing and no answer is not clear at all.
   *
   */
  private def percents(c: ComplianceLevel, precision: CompliancePrecision): Map[String, Double] = {
    import ReportType.*

    // we want at most `precision` decimals
    val pc = CompliancePercent.fromLevels(c, precision)
    Map(
      statusDisplayName(EnforceNotApplicable) -> pc.notApplicable,
      statusDisplayName(EnforceSuccess)       -> pc.success,
      statusDisplayName(EnforceRepaired)      -> pc.repaired,
      statusDisplayName(EnforceError)         -> pc.error,
      statusDisplayName(Unexpected)           -> pc.unexpected,
      statusDisplayName(Missing)              -> pc.missing,
      statusDisplayName(NoAnswer)             -> pc.noAnswer,
      statusDisplayName(Disabled)             -> pc.reportsDisabled,
      statusDisplayName(Pending)              -> pc.pending,
      statusDisplayName(AuditCompliant)       -> pc.compliant,
      statusDisplayName(AuditNotApplicable)   -> pc.auditNotApplicable,
      statusDisplayName(AuditError)           -> pc.auditError,
      statusDisplayName(AuditNonCompliant)    -> pc.nonCompliant,
      statusDisplayName(BadPolicyMode)        -> pc.badPolicyMode
    ).filter { case (k, v) => v > 0 }.view.mapValues(percent => percent).toMap
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
