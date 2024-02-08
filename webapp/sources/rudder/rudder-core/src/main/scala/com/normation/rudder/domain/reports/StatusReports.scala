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

package com.normation.rudder.domain.reports

import com.normation.cfclerk.domain.ReportingLogic
import com.normation.cfclerk.domain.ReportingLogic._
import com.normation.cfclerk.domain.WorstReportReportingLogic
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.services.reports._
import net.liftweb.common.Loggable
import org.joda.time.DateTime

/**
 * That file contains all the kind of status reports for:
 * - a message
 * - a value
 * - a component
 * - a directive
 * - a rule/node
 *
 * Plus aggregated version which allows to define compliance on a set
 * of rule/node status report, with the two predefined aggregate:
 * - by rule (for a given node)
 * - by node (for a given rule)
 *
 */

sealed trait StatusReport {
  def compliance: ComplianceLevel
}

/**
 * Define two aggregated view of compliance: by node (for
 * a given rule) and by rules (for a given node).
 */
final class RuleStatusReport private (
    val forRule:   RuleId,
    val report:    AggregatedStatusReport,
    val overrides: List[OverridenPolicy]
) extends StatusReport {
  lazy val compliance = report.compliance
  lazy val byNodes: Map[NodeId, AggregatedStatusReport] =
    report.reports.groupBy(_.nodeId).view.mapValues(AggregatedStatusReport(_)).toMap
}

object RuleStatusReport {
  def apply(ruleId: RuleId, reports: Iterable[RuleNodeStatusReport], overrides: List[OverridenPolicy]): RuleStatusReport = {
    new RuleStatusReport(ruleId, AggregatedStatusReport(reports.toSet.filter(_.ruleId == ruleId)), overrides)
  }
}

/*
 * meta information about a run compliance analysis -
 * in particular abour policy mode errors (agent aborted,
 * mixed mode in directives from the same techniques, etc)
 */
sealed trait RunComplianceInfo
object RunComplianceInfo {
  sealed trait PolicyModeError
  object PolicyModeError {
    final case class TechniqueMixedMode(message: String)               extends PolicyModeError
    final case class AgentAbortMessage(cause: String, message: String) extends PolicyModeError
  }

  object OK                                                                 extends RunComplianceInfo
  final case class PolicyModeInconsistency(problems: List[PolicyModeError]) extends RunComplianceInfo
}

final class NodeStatusReport private (
    val nodeId:     NodeId,
    val runInfo:    RunAndConfigInfo,
    val statusInfo: RunComplianceInfo,
    val overrides:  List[OverridenPolicy],
    val reports:    Set[RuleNodeStatusReport]
) extends StatusReport {
  // here, reports is a set. Be careful to not loose compliance with map (b/c scala make a set of the result)
  lazy val compliance: ComplianceLevel                     = ComplianceLevel.sum(reports.iterator.map(_.compliance).iterator.to(Iterable))
  lazy val byRules:    Map[RuleId, AggregatedStatusReport] =
    reports.groupBy(_.ruleId).view.mapValues(AggregatedStatusReport(_)).toMap
}

object NodeStatusReport {
  // To use when you are sure that all reports are indeed for the designated node. RuleNodeStatusReports must be merged
  // Only used in `getNodeStatusReports`
  def apply(
      nodeId:     NodeId,
      runInfo:    RunAndConfigInfo,
      statusInfo: RunComplianceInfo,
      overrides:  List[OverridenPolicy],
      reports:    Set[RuleNodeStatusReport]
  ): NodeStatusReport = {
    assert(
      reports.forall(_.nodeId == nodeId),
      s"You can't build a NodeStatusReport with reports for other node than itself. Current node id: ${nodeId.value}; Wrong reports: ${reports
          .filter(_.nodeId != nodeId)
          .map(r => s"${r.nodeId.value}:${r.ruleId.serialize}")
          .mkString("|")}"
    )
    new NodeStatusReport(nodeId, runInfo, statusInfo, overrides, reports)
  }

  /*
   * This method filters an NodeStatusReport by Rules.
   * The goal is to have cheaper way to truncate data from NodeStatusReport that rebuilding it all from top-down
   * With this approach, we don't need to to the RuleNodeStatusReport merge, and performance are about 20 times better than
   * recreating and computing all data
   * it take a NodeStatusReport, and returns it with only the relevant rules
   */
  def filterByRules(nodeStatusReport: NodeStatusReport, ruleIds: Set[RuleId]): NodeStatusReport = {

    new NodeStatusReport(
      nodeStatusReport.nodeId,
      nodeStatusReport.runInfo,
      nodeStatusReport.statusInfo,
      nodeStatusReport.overrides,
      nodeStatusReport.reports.filter(r => ruleIds.contains(r.ruleId))
    )
  }

  def filterByDirectives(nodeStatusReport: NodeStatusReport, directiveIds: Set[DirectiveId]): NodeStatusReport = {

    new NodeStatusReport(
      nodeStatusReport.nodeId,
      nodeStatusReport.runInfo,
      nodeStatusReport.statusInfo,
      nodeStatusReport.overrides,
      nodeStatusReport.reports.flatMap { r =>
        val filterRule = r.copy(directives = r.directives.filter(d => directiveIds.contains(d._1)))
        if (filterRule.directives.isEmpty) None else Some(filterRule)
      }
    )
  }
}

/**
 * build an aggregated view of a set of reports,
 * allowing to access compound compliance for rules,
 * directives and so on
 */
final class AggregatedStatusReport private (
    val reports: Set[RuleNodeStatusReport]
) {

  /**
   * rebuild all the trees from the available status
   */
  lazy val directives: Map[DirectiveId, DirectiveStatusReport] = {
    // toSeq is mandatory; here, we may have some directive only different
    // by rule or node and we don't want to loose the weight
    DirectiveStatusReport.merge(reports.toList.flatMap(_.directives.values))
  }
  lazy val compliance: ComplianceLevel                         = ComplianceLevel.sum(directives.map(_._2.compliance))
}

object AggregatedStatusReport {

  def apply(reports: Iterable[RuleNodeStatusReport]): AggregatedStatusReport = {
    val merged = RuleNodeStatusReport.merge(reports)
    new AggregatedStatusReport(merged.values.toSet)
  }

  def applyFromUniqueNode(reports: Set[RuleNodeStatusReport]): AggregatedStatusReport = {
    new AggregatedStatusReport(reports)
  }

  /*
   * This method filters an AggragatedStatusReport by Rules.
   * The goal is to have cheaper way to truncate data from NodeStatusReport that rebuilding it all from top-down
   * With this approach, we don't need to to the RuleNodeStatusReport merge, and performance are about 20 times better than
   * recreating and computing all data
   */
  def applyFromAggregatedStatusReport(statusReport: AggregatedStatusReport, ruleIds: Set[RuleId]): AggregatedStatusReport = {
    new AggregatedStatusReport(statusReport.reports.filter(r => ruleIds.contains(r.ruleId)))
  }

}

final case class RuleNodeStatusReport(
    nodeId:       NodeId,
    ruleId:       RuleId,
    agentRunTime: Option[DateTime],
    configId:     Option[NodeConfigId], // only one DirectiveStatusReport by directiveId

    directives:     Map[DirectiveId, DirectiveStatusReport],
    expirationDate: DateTime
) extends StatusReport {

  override lazy val compliance: ComplianceLevel = ComplianceLevel.sum(directives.map(_._2.compliance))

  override def toString(): String = {
    s"""[[${nodeId.value}: ${ruleId.serialize}; run: ${agentRunTime
        .getOrElse("no time")};${configId.map(_.value).getOrElse("no config id")}->${expirationDate}]
       |  compliance:${compliance}
       |  ${directives.values.toSeq.sortBy(_.directiveId.serialize).map(x => s"${x}").mkString("\n  ")}]
       |""".stripMargin('|')
  }

  def getValues(predicate: ComponentValueStatusReport => Boolean): Seq[(DirectiveId, String, ComponentValueStatusReport)] = {
    directives.values.flatMap(_.getValues(predicate)).toSeq
  }

  def withFilteredElements(
      directive: DirectiveStatusReport => Boolean,
      component: ComponentStatusReport => Boolean,
      values:    ComponentValueStatusReport => Boolean
  ): Option[RuleNodeStatusReport] = {
    val dirs = (
      directives.values
        .filter(directive(_))
        .flatMap(_.withFilteredElements(component, values))
        .map(x => (x.directiveId, x))
        .toMap
    )
    if (dirs.isEmpty) None
    else Some(this.copy(directives = dirs))
  }
}

object RuleNodeStatusReport {
  def merge(
      reports: Iterable[RuleNodeStatusReport]
  ): Map[(NodeId, RuleId, Option[DateTime], Option[NodeConfigId]), RuleNodeStatusReport] = {
    reports.groupBy(r => (r.nodeId, r.ruleId, r.agentRunTime, r.configId)).map {
      case (id, reports) =>
        val newDirectives = DirectiveStatusReport.merge(reports.toList.flatMap(_.directives.values))

        // the merge of two reports expire when the first one expire
        val expire = new DateTime(reports.map(_.expirationDate.getMillis).min)
        (id, RuleNodeStatusReport(id._1, id._2, id._3, id._4, newDirectives, expire))
    }
  }
}

final case class DirectiveStatusReport(
    directiveId: DirectiveId, // only one component status report by component name

    components: List[ComponentStatusReport]
) extends StatusReport {
  override lazy val compliance:                                    ComplianceLevel                                        = ComplianceLevel.sum(components.map(_.compliance))
  def getValues(predicate: ComponentValueStatusReport => Boolean): Seq[(DirectiveId, String, ComponentValueStatusReport)] = {
    components.flatMap(_.getValues(predicate)).map { case v => (directiveId, v.componentValue, v) }
  }

  override def toString(): String = s"""[${directiveId.serialize} =>
                               |    ${components.sortBy(_.componentName).mkString("\n    ")}
                               |]"""

  def getByReportId(reportId: String): List[ComponentValueStatusReport] = {
    components.collect { case c => c.componentValues.collect { case cv if (cv.reportId == reportId) => cv } }.flatten
  }

  def withFilteredElements(
      component: ComponentStatusReport => Boolean,
      values:    ComponentValueStatusReport => Boolean
  ): Option[DirectiveStatusReport] = {
    val cpts = (
      components
        .filter(component(_))
        .flatMap(_.withFilteredElement(values))
    )

    if (cpts.isEmpty) None
    else Some(this.copy(components = cpts))
  }
}

object DirectiveStatusReport {

  def merge(directives: List[DirectiveStatusReport]): Map[DirectiveId, DirectiveStatusReport] = {
    directives.groupBy(_.directiveId).map {
      case (directiveId, reports) =>
        val newComponents = ComponentStatusReport.merge(reports.flatMap(_.components))
        (directiveId, DirectiveStatusReport(directiveId, newComponents))
    }
  }
}

/**
 * For a component, store the report status, as the worse status of the component
 * Or error if there is an unexpected component value
 */
sealed trait ComponentStatusReport extends StatusReport {
  def componentName: String
  def getValues(predicate:           ComponentValueStatusReport => Boolean): Seq[ComponentValueStatusReport]
  def withFilteredElement(predicate: ComponentValueStatusReport => Boolean): Option[ComponentStatusReport]
  def componentValues: List[ComponentValueStatusReport]
  def componentValues(v: String): List[ComponentValueStatusReport] = componentValues.filter(_.componentValue == v)
  def status: ReportType
}

final case class BlockStatusReport(
    componentName:  String,
    reportingLogic: ReportingLogic,
    subComponents:  List[ComponentStatusReport]
) extends ComponentStatusReport {
  def findChildren(componentName: String): List[ComponentStatusReport] = {
    subComponents.find(_.componentName == componentName).toList :::
    subComponents.collect { case g: BlockStatusReport => g }.flatMap(_.findChildren(componentName))
  }
  def compliance:                          ComplianceLevel             = {
    import ReportingLogic._
    reportingLogic match {
      // simple weighted compliance, as usual
      case WeightedReport => ComplianceLevel.sum(subComponents.map(_.compliance))
      // worst case bubble up, and its weight can be either 1 or the sum of sub-component weight
      case worst: WorstReportReportingLogic =>
        val worstReport = ReportType.getWorseType(subComponents.map(_.status))
        val allReports  = getValues(_ => true).flatMap(_.messages.map(_ => worstReport))
        val kept        = worst match {
          case WorstReportWeightedOne => allReports.take(1)
          case WorstReportWeightedSum => allReports
        }
        ComplianceLevel.compute(kept)
      // focus on a given sub-component name (can be present several time, or 0 which leads to N/A)
      case FocusReport(component) => ComplianceLevel.sum(findChildren(component).map(_.compliance))
    }
  }

  def getValues(predicate: ComponentValueStatusReport => Boolean): Seq[ComponentValueStatusReport] = {
    subComponents.flatMap(_.getValues(predicate))
  }

  def componentValues:                                                       List[ComponentValueStatusReport] = ComponentValueStatusReport.merge(getValues(_ => true))
  def withFilteredElement(predicate: ComponentValueStatusReport => Boolean): Option[ComponentStatusReport]    = {
    subComponents.flatMap(_.withFilteredElement(predicate)) match {
      case Nil => None
      case l   => Some(this.copy(subComponents = l))
    }
  }

  def status: ReportType = {
    reportingLogic match {
      case WorstReportWeightedOne | WorstReportWeightedSum | WeightedReport =>
        ReportType.getWorseType(subComponents.map(_.status))
      case FocusReport(component)                                           =>
        ReportType.getWorseType(findChildren(component).map(_.status))
    }
  }
}
final case class ValueStatusReport(
    componentName:         String,
    expectedComponentName: String, // only one ComponentValueStatusReport by valuex.

    componentValues: List[ComponentValueStatusReport]
) extends ComponentStatusReport {

  override def toString(): String = s"${componentName}:${componentValues.toSeq.sortBy(_.componentValue).mkString("[", ",", "]")}"

  override lazy val compliance:                                    ComplianceLevel                 = ComplianceLevel.sum(componentValues.map(_.compliance))
  /*
   * Get all values matching the predicate
   */
  def getValues(predicate: ComponentValueStatusReport => Boolean): Seq[ComponentValueStatusReport] = {
    componentValues.filter(v => predicate(v)).toSeq
  }

  def status:                                                                ReportType                    = ReportType.getWorseType(getValues(_ => true).map(_.status))
  /*
   * Rebuild a componentStatusReport, keeping only values matching the predicate
   */
  def withFilteredElement(predicate: ComponentValueStatusReport => Boolean): Option[ComponentStatusReport] = {
    val values = componentValues.filter { case v => predicate(v) }
    if (values.isEmpty) None
    else Some(this.copy(componentValues = values))
  }
}

/**
 * Merge component status reports.
 * We assign a arbitrary preponderance order for reporting logic mode:
 * WorstReportWeightedOne > WorstReportWeightedSum > WeightedReport > FocusReport
 * In the case of two focus, the focust for first component is kept.
 */
object ComponentStatusReport extends Loggable {

  def merge(components: Iterable[ComponentStatusReport]): List[ComponentStatusReport] = {
    components.groupBy(_.componentName).flatMap {
      case (cptName, reports) =>
        val valueComponents = reports.collect { case c: ValueStatusReport => c }.toList match {
          case Nil => Nil
          case r   =>
            r.groupBy(_.expectedComponentName).toList.map {
              case (unexpanded, r) =>
                val newValues = ComponentValueStatusReport.merge(r.flatMap(_.componentValues))
                ValueStatusReport(cptName, unexpanded, newValues)

            }
        }
        val groupComponent  = reports.collect { case c: BlockStatusReport => c }.toList match {
          case Nil => Nil
          case r   =>
            import ReportingLogic._
            val reportingLogic = r
              .map(_.reportingLogic)
              .reduce((a, b) => {
                (a, b) match {
                  case (WorstReportWeightedOne, _) | (_, WorstReportWeightedOne) => WorstReportWeightedOne
                  case (WorstReportWeightedSum, _) | (_, WorstReportWeightedSum) => WorstReportWeightedSum
                  case (WeightedReport, _) | (_, WeightedReport)                 => WeightedReport
                  case (FocusReport(a), _)                                       => FocusReport(a)
                }
              })
            BlockStatusReport(cptName, reportingLogic, ComponentStatusReport.merge(r.flatMap(_.subComponents)).toList) :: Nil
        }
        groupComponent ::: valueComponents

    }
  }.toList
}

/**
 * For a component value, store the report status
 *
 * In fact, we only keep unexpanded component values.
 * Values are lost. They are not really used today
 */
final case class ComponentValueStatusReport(
    componentValue:         String,
    expectedComponentValue: String,
    reportId:               String,
    messages:               List[MessageStatusReport]
) extends StatusReport {

  override def toString(): String = s"${componentValue}(<-> ${expectedComponentValue}):${messages.mkString("[", ";", "]")}"

  override lazy val compliance: ComplianceLevel = ComplianceLevel.compute(messages.map(_.reportType))

  /*
   * It can be argued that a status for a value may make sense,
   * even in the case where several nodes contributed to it
   */
  lazy val status: ReportType = ReportType.getWorseType(messages.map(_.reportType))
}

object ComponentValueStatusReport extends Loggable {

  /**
   * Merge a set of ComponentValueStatusReport, grouping
   * them by component reportId and then actual value and then expected value
   */
  def merge(values: Iterable[ComponentValueStatusReport]): List[ComponentValueStatusReport] = {
    values.groupBy(_.reportId).toList.flatMap {
      case (id, groupedById) =>
        groupedById.groupBy(_.componentValue).toList.flatMap {
          case (component, groupedByActualValue) =>
            groupedByActualValue.groupBy(_.expectedComponentValue).toList.map {
              case (unexpanded, groupedByExpectedValue) =>
                ComponentValueStatusReport(component, unexpanded, id, groupedByActualValue.toList.flatMap(_.messages))
            }
        }
    }
  }
}

final case class MessageStatusReport(
    reportType: ReportType,
    message:    Option[String]
) {
  override def toString(): String = reportType.severity + message.fold(":\"\"")(":\"" + _ + "\"")
  def debugString:         String = toString()
}

object MessageStatusReport {

  def apply(status: ReportType, message: String): MessageStatusReport = {
    val msg = message.trim match {
      case "" => None
      case s  => Some(s)
    }
    new MessageStatusReport(status, msg)
  }

}

object NodeStatusReportSerialization {

  import net.liftweb.json._
  import net.liftweb.json.JsonDSL._

  def jsonRunInfo(runInfo: RunAndConfigInfo): JValue = {

    runInfo match {
      case NoRunNoExpectedReport                    =>
        ("type" -> "NoRunNoExpectedReport")
      case NoExpectedReport(t, id)                  =>
        (("type"             -> "NoExpectedReport")
        ~ ("lastRunConfigId" -> id.map(_.value)))
      case NoReportInInterval(e, _)                 =>
        (("type"              -> "NoReportInInterval")
        ~ ("expectedConfigId" -> e.nodeConfigId.value))
      case ReportsDisabledInInterval(e, _)          =>
        (("type"              -> "ReportsDisabled")
        ~ ("expectedConfigId" -> e.nodeConfigId.value))
      case UnexpectedVersion(t, id, _, e, _, _)     =>
        (("type"              -> "UnexpectedVersion")
        ~ ("expectedConfigId" -> e.nodeConfigId.value)
        ~ ("runConfigId"      -> id.get.nodeConfigId.value))
      case UnexpectedNoVersion(_, id, _, e, _, _)   =>
        (("type"              -> "UnexpectedNoVersion")
        ~ ("expectedConfigId" -> e.nodeConfigId.value)
        ~ ("runConfigId"      -> id.value))
      case UnexpectedUnknownVersion(_, id, e, _, _) =>
        (("type"              -> "UnexpectedUnknownVersion")
        ~ ("expectedConfigId" -> e.nodeConfigId.value)
        ~ ("runConfigId"      -> id.value))
      case Pending(e, r, _)                         =>
        (("type"              -> "Pending")
        ~ ("expectedConfigId" -> e.nodeConfigId.value)
        ~ ("runConfigId"      -> r.map(_._2.nodeConfigId.value)))
      case ComputeCompliance(_, e, _)               =>
        (("type"              -> "ComputeCompliance")
        ~ ("expectedConfigId" -> e.nodeConfigId.value)
        ~ ("runConfigId"      -> e.nodeConfigId.value))
      case NoUserRulesDefined(_, e, _, _, _)        =>
        (("type"              -> "NoUserRulesDefined")
        ~ ("expectedConfigId" -> e.nodeConfigId.value)
        ~ ("runConfigId"      -> e.nodeConfigId.value))
    }
  }
  def jsonStatusInfo(statusInfo: RunComplianceInfo): JValue = {
    (
      ("status"  -> (statusInfo match {
        case RunComplianceInfo.OK => "success"
        case _                    => "error"
      })) ~ (
        "errors" -> (statusInfo match {
          case RunComplianceInfo.OK                              => None
          case RunComplianceInfo.PolicyModeInconsistency(errors) =>
            Some(errors.map {
              case RunComplianceInfo.PolicyModeError.TechniqueMixedMode(msg)       =>
                ("policyModeError" -> ("message" -> msg)): JObject
              case RunComplianceInfo.PolicyModeError.AgentAbortMessage(cause, msg) =>
                ("policyModeInconsistency" -> (("cause" -> cause) ~ ("message" -> msg))): JObject
            })
        })
      )
    )
  }

  implicit class RunComplianceInfoToJs(val x: (RunAndConfigInfo, RunComplianceInfo)) extends AnyVal {
    def toJValue: JObject = {
      (
        ("run"      -> jsonRunInfo(x._1))
        ~ ("status" -> jsonStatusInfo(x._2))
      )
    }

    def toJson:        String = prettyRender(toJValue)
    def toCompactJson: String = compactRender(toJValue)
  }

  implicit class AggregatedStatusReportToJs(val x: AggregatedStatusReport) extends AnyVal {
    def toJValue:      JValue = x.reports.toJValue
    def toJson:        String = prettyRender(toJValue)
    def toCompactJson: String = compactRender(toJValue)
  }

  implicit class SetRuleNodeStatusReportToJs(reports: Set[RuleNodeStatusReport]) {
    import ComplianceLevelSerialisation._

    def componentValueToJson(c: ComponentStatusReport): JValue = {
      c match {
        case c: ValueStatusReport =>
          (("componentName"  -> c.componentName)
          ~ ("compliance"    -> c.compliance.computePercent().toJson)
          ~ ("numberReports" -> c.compliance.total)
          ~ ("values"        -> c.componentValues.map { v =>
            (("value"          -> v.componentValue)
            ~ ("compliance"    -> v.compliance.computePercent().toJson)
            ~ ("numberReports" -> v.compliance.total)
            ~ ("unexpanded"    -> v.expectedComponentValue)
            ~ ("messages"      -> v.messages.map { m =>
              (("message" -> m.message)
              ~ ("type"   -> m.reportType.severity))
            }))
          }))
        case c: BlockStatusReport =>
          (("componentName"   -> c.componentName)
          ~ ("compliance"     -> c.compliance.computePercent().toJson)
          ~ ("numberReports"  -> c.compliance.total)
          ~ ("subComponents"  -> c.subComponents.map(componentValueToJson))
          ~ ("reportingLogic" -> c.reportingLogic.toString))
      }
    }
    def toJValue:                                       JValue = {

      // here, I'm not sure that we want compliance or
      // compliance percents. Having a normalized value
      // seems far better for queries in the futur.
      // but in that case, we should also keep the total
      // number of events to be able to rebuild raw data

      "rules" -> reports.map { r =>
        (("ruleId"         -> r.ruleId.serialize)
        ~ ("compliance"    -> r.compliance.computePercent().toJson)
        ~ ("numberReports" -> r.compliance.total)
        ~ ("directives"    -> r.directives.values.map { d =>
          (("directiveId"    -> d.directiveId.serialize)
          ~ ("compliance"    -> d.compliance.computePercent().toJson)
          ~ ("numberReports" -> d.compliance.total)
          ~ ("components"    -> d.components.map(componentValueToJson)))
        }))
      }
    }

    def toJson:        String = prettyRender(toJValue)
    def toCompactJson: String = compactRender(toJValue)
  }
}
