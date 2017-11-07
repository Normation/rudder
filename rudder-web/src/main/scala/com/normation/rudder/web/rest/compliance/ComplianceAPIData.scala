/*
*************************************************************************************
* Copyright 2015 Normation SAS
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

package com.normation.rudder.web.rest.compliance

import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.reports._
import net.liftweb.json.JsonDSL._
import com.normation.rudder.domain.policies.DirectiveId
import net.liftweb.json.JsonAST
import com.normation.rudder.reports.ComplianceModeName

/**
 * Here, we want to present two view of compliance:
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
case class ByRuleRuleCompliance(

    id             : RuleId
  , name           : String
    //compliance by nodes
  , compliance     : ComplianceLevel
  , mode           : ComplianceModeName
  , directives     : Seq[ByRuleDirectiveCompliance]
)

case class ByRuleDirectiveCompliance(
    id        : DirectiveId
  , name      : String
  , compliance: ComplianceLevel
  , components: Seq[ByRuleComponentCompliance]
)

case class ByRuleComponentCompliance(
    name      : String
  , compliance: ComplianceLevel
  , nodes     : Seq[ByRuleNodeCompliance]
)

case class ByRuleNodeCompliance(
    id    : NodeId
  , name  : String
  , values: Seq[ComponentValueStatusReport]
)

/**
 * Compliance for a node.
 * It lists:
 * - id: the node id
 * - compliance: the compliance of the node by rule
 *   (total number of rules, repartition of rules by status)
 * - ruleUnderNodeCompliance: the list of compliance for each rule, for that node
 */
case class ByNodeNodeCompliance(
    id             : NodeId
    //compliance by nodes
  , name  : String
  , compliance     : ComplianceLevel
  , mode           : ComplianceModeName
  , nodeCompliances: Seq[ByNodeRuleCompliance]
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
case class ByNodeRuleCompliance(
    id        : RuleId
  , name      : String
    //compliance by directive (by nodes)
  , compliance: ComplianceLevel
  , directives: Seq[ByNodeDirectiveCompliance]
)

case class ByNodeDirectiveCompliance(
    id        : DirectiveId
  , name      : String
  , compliance: ComplianceLevel
  , components: Map[String, ComponentStatusReport]
)

object ByNodeDirectiveCompliance {

  def apply(d: DirectiveStatusReport, directiveName : String): ByNodeDirectiveCompliance = {
    new ByNodeDirectiveCompliance(d.directiveId, directiveName, d.compliance, d.components)
  }
}

object JsonCompliance {

  implicit class JsonbyRuleCompliance(rule: ByRuleRuleCompliance) {
    def toJsonV6 = (
        ("id" -> rule.id.value)
      ~ ("name" -> rule.name)
      ~ ("compliance" -> rule.compliance.complianceWithoutPending)
      ~ ("complianceDetails" -> percents(rule.compliance))
      ~ ("directives" -> directives(rule.directives, 10) )
    )

    /*
     * level:
     * - up to 1 : rules,
     * - 2: rules & directives
     * - 3: rules, directives, components
     * - 4 and up: rules, directives, components, node and component values
     */

    def toJson(level: Int) = (
        ("id" -> rule.id.value)
      ~ ("compliance" -> rule.compliance.complianceWithoutPending)
      ~ ("mode" -> rule.mode.name)
      ~ ("complianceDetails" -> percents(rule.compliance))
      ~ ("directives" -> directives(rule.directives, level) )
    )

    private[this] def directives(directives: Seq[ByRuleDirectiveCompliance], level: Int): Option[JsonAST.JValue] = {
      if(level < 2) None
      else Some( directives.map { directive =>
        (
            ("id" -> directive.id.value)
          ~ ("name" -> directive.name)
          ~ ("compliance" -> directive.compliance.complianceWithoutPending)
          ~ ("complianceDetails" -> percents(directive.compliance))
          ~ ("components" -> components(directive.components, level))
        )
       })
    }

    private[this] def components(components: Seq[ByRuleComponentCompliance], level: Int): Option[JsonAST.JValue] = {
      if(level < 3) None
      else Some(components.map { component =>
        (
            ("name" -> component.name)
          ~ ("compliance" -> component.compliance.complianceWithoutPending)
          ~ ("complianceDetails" -> percents(component.compliance))
          ~ ("nodes" -> nodes(component.nodes, level))
        )
      })
    }

    private[this] def nodes(nodes: Seq[ByRuleNodeCompliance], level: Int): Option[JsonAST.JValue] = {
      if(level < 4) None
      else Some(nodes.map { node =>
        (
            ("id" -> node.id.value)
          ~ ("name" -> node.name)
          ~ ("values" -> node.values.map { value =>
              (
                ("value" -> value.componentValue)
                  ~ ("reports" -> value.messages.map { report =>
                      (
                          ("status" -> statusDisplayName(report.reportType))
                        ~ ("message" -> report.message)
                      )
                  })
              )
          })
        )
      })
    }

  }

  implicit class JsonByNodeCompliance(n: ByNodeNodeCompliance) {
    def toJsonV6 = (
        ("id" -> n.id.value)
      ~ ("compliance" -> n.compliance.complianceWithoutPending)
      ~ ("complianceDetails" -> percents(n.compliance))
      ~ ("rules" -> rules(n.nodeCompliances, 10))
    )

    /*
     * level:
     * - up to 1 : nodes,
     * - 2: nodes & rules
     * - 3: nodes, rules, directives
     * - 4: nodes, rules, directives, components
     * - 5 and up: nodes, rules, directives, components and component values
     */

    def toJson(level: Int) = (
        ("id" -> n.id.value)
      ~ ("name" -> n.name)
      ~ ("compliance" -> n.compliance.complianceWithoutPending)
      ~ ("mode" -> n.mode.name)
      ~ ("complianceDetails" -> percents(n.compliance))
      ~ ("rules" -> rules(n.nodeCompliances, level))
    )

    private[this] def rules(rules: Seq[ByNodeRuleCompliance], level: Int): Option[JsonAST.JValue] = {
      if(level < 2) None
      else Some(rules.map { rule =>
        (
            ("id" -> rule.id.value)
          ~ ("name" -> rule.name)
          ~ ("compliance" -> rule.compliance.complianceWithoutPending)
          ~ ("complianceDetails" -> percents(rule.compliance))
          ~ ("directives" -> directives(rule.directives, level))
       )
      })
    }

    private[this] def directives(directives: Seq[ByNodeDirectiveCompliance], level: Int): Option[JsonAST.JValue] = {
      if(level < 3) None
      else Some(directives.map { directive =>
        (
            ("id" -> directive.id.value)
          ~ ("name" -> directive.name)
          ~ ("compliance" -> directive.compliance.complianceWithoutPending)
          ~ ("complianceDetails" -> percents(directive.compliance))
          ~ ("components" -> components(directive.components, level))
        )
      })
    }

    private[this] def components(components: Map[String, ComponentStatusReport], level: Int): Option[JsonAST.JValue] = {
      if(level < 4) None
      else Some(components.map { case (_, component) =>
        (
            ("name" -> component.componentName)
          ~ ("compliance" -> component.compliance.complianceWithoutPending)
          ~ ("complianceDetails" -> percents(component.compliance))
          ~ ("values" -> values(component.componentValues, level))
        )
      })
    }

    private[this] def values(componentValues: Map[String, ComponentValueStatusReport], level: Int): Option[JsonAST.JValue] = {
      if(level < 5) None
      else Some(componentValues.map { case (_, value) =>
          (
            ("value" -> value.componentValue)
              ~ ("reports" -> value.messages.map { report =>
                  (
                      ("status" -> statusDisplayName(report.reportType))
                    ~ ("message" -> report.message)
                  )
              })
          )
      })
    }

  }

  private[this] def statusDisplayName(r: ReportType): String = {
    import ReportType._

    r match {
      case EnforceNotApplicable => "successNotApplicable"
      case EnforceSuccess => "successAlreadyOK"
      case EnforceRepaired => "successRepaired"
      case EnforceError => "error"
      case AuditCompliant => "auditCompliant"
      case AuditNonCompliant => "auditNonCompliant"
      case AuditError => "auditError"
      case AuditNotApplicable => "auditNotApplicable"
      case Unexpected => "unexpectedUnknownComponent"
      case Missing => "unexpectedMissingComponent"
      case NoAnswer => "noReport"
      case Disabled => "reportsDisabled"
      case Pending => "applying"
      case BadPolicyMode => "badPolicyMode"
    }
  }

  /**
   * By default, we want to only display non 0 compliance percent
   *
   * We also want to define clear user facing severity levels, in particular,
   * the semantic of unexpected / missing and no answer is not clear at all.
   *
   */
  private[this] def percents(c: ComplianceLevel): Map[String, Double] = {
    import ReportType._

    //we want at most two decimals
    Map(
        statusDisplayName(EnforceNotApplicable) -> c.pc.notApplicable
      , statusDisplayName(EnforceSuccess) -> c.pc.success
      , statusDisplayName(EnforceRepaired) -> c.pc.repaired
      , statusDisplayName(EnforceError) -> c.pc.error
      , statusDisplayName(Unexpected) -> c.pc.unexpected
      , statusDisplayName(Missing) -> c.pc.missing
      , statusDisplayName(NoAnswer) -> c.pc.noAnswer
      , statusDisplayName(Disabled) -> c.pc.reportsDisabled
      , statusDisplayName(Pending) -> c.pc.pending
    ).filter { case(k, v) => v > 0 }.mapValues(percent => percent )
  }
}
