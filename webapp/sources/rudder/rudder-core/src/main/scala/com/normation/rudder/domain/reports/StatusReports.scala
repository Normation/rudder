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
import com.normation.cfclerk.domain.ReportingLogic.*
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies.*
import com.softwaremill.quicklens.*
import enumeratum.Enum
import enumeratum.EnumEntry
import net.liftweb.common.Loggable
import org.joda.time.DateTime
import zio.Chunk

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
    val overrides: List[OverriddenPolicy]
) extends StatusReport {
  lazy val compliance = report.compliance
  lazy val byNodes: Map[NodeId, AggregatedStatusReport] =
    report.reports.groupBy(_.nodeId).view.mapValues(AggregatedStatusReport(_)).toMap
}

object RuleStatusReport {
  def apply(ruleId: RuleId, reports: Iterable[RuleNodeStatusReport], overrides: List[OverriddenPolicy]): RuleStatusReport = {
    new RuleStatusReport(ruleId, AggregatedStatusReport(reports.toSet.filter(_.ruleId == ruleId)), overrides)
  }
}

/*
 * meta information about a run compliance analysis -
 * in particular about policy mode errors (agent aborted,
 * mixed mode in directives from the same techniques, etc)
 */
sealed trait RunComplianceInfo
object RunComplianceInfo {
  sealed trait PolicyModeError
  object PolicyModeError {
    final case class TechniqueMixedMode(message: String)               extends PolicyModeError
    final case class AgentAbortMessage(cause: String, message: String) extends PolicyModeError
  }

  case object OK                                                            extends RunComplianceInfo
  final case class PolicyModeInconsistency(problems: List[PolicyModeError]) extends RunComplianceInfo
}

sealed trait RunAnalysisKind extends EnumEntry
object RunAnalysisKind       extends Enum[RunAnalysisKind] {

  case object NoRunNoExpectedReport     extends RunAnalysisKind
  case object NoExpectedReport          extends RunAnalysisKind
  case object NoUserRulesDefined        extends RunAnalysisKind
  case object NoReportInInterval        extends RunAnalysisKind
  case object KeepLastCompliance        extends RunAnalysisKind
  case object ReportsDisabledInInterval extends RunAnalysisKind
  case object Pending                   extends RunAnalysisKind
  case object UnexpectedVersion         extends RunAnalysisKind
  case object UnexpectedNoVersion       extends RunAnalysisKind
  case object UnexpectedUnknownVersion  extends RunAnalysisKind
  case object ComputeCompliance         extends RunAnalysisKind

  override def values: IndexedSeq[RunAnalysisKind] = findValues
}

final case class RunAnalysis(
    kind:                RunAnalysisKind,
    expectedConfigId:    Option[NodeConfigId],
    expectedConfigStart: Option[DateTime],
    expirationDateTime:  Option[DateTime],
    expiredSince:        Option[DateTime],
    lastRunDateTime:     Option[DateTime],
    lastRunConfigId:     Option[NodeConfigId],
    lastRunExpiration:   Option[DateTime]
)

final case class NodeStatusReport(
    nodeId:     NodeId,
    runInfo:    RunAnalysis,
    statusInfo: RunComplianceInfo,
    overrides:  List[OverriddenPolicy],
    reports:    Map[PolicyTypeName, AggregatedStatusReport]
) extends StatusReport {
  // for compat reason, node compliance is the sum of all aspects
  lazy val compliance: ComplianceLevel = ComplianceLevel.sum(reports.values.map(_.compliance))

  // get the compliance level for a given type, or compliance 0 is that type is missing
  def getCompliance(t: PolicyTypeName): ComplianceLevel = {
    reports.get(t) match {
      case Some(x) => x.compliance
      case None    => ComplianceLevel()
    }
  }

  def systemCompliance: ComplianceLevel = getCompliance(PolicyTypeName.rudderSystem)
  def baseCompliance:   ComplianceLevel = getCompliance(PolicyTypeName.rudderBase)

  def forPolicyType(t: PolicyTypeName): NodeStatusReport = {
    val r = reports.get(t) match {
      case Some(r) => Map((t, r))
      case None    => Map.empty[PolicyTypeName, AggregatedStatusReport]
    }
    NodeStatusReport(nodeId, runInfo, statusInfo, overrides, r)
  }
}

object NodeStatusReport {

  /*
   * This method filters an NodeStatusReport by Rules.
   * The goal is to have cheaper way to truncate data from NodeStatusReport that rebuilding it all from top-down
   * With this approach, we don't need to to the RuleNodeStatusReport merge, and performance are about 20 times better than
   * recreating and computing all data
   * it take a NodeStatusReport, and returns it with only the relevant rules
   */
  def filterByRules(nodeStatusReport: NodeStatusReport, ruleIds: Set[RuleId]): NodeStatusReport = {

    NodeStatusReport(
      nodeStatusReport.nodeId,
      nodeStatusReport.runInfo,
      nodeStatusReport.statusInfo,
      nodeStatusReport.overrides,
      nodeStatusReport.reports.map { case (tag, r) => (tag, r.filterByRules(ruleIds)) }
    )
  }

  def filterByDirectives(nodeStatusReport: NodeStatusReport, directiveIds: Set[DirectiveId]): NodeStatusReport = {

    NodeStatusReport(
      nodeStatusReport.nodeId,
      nodeStatusReport.runInfo,
      nodeStatusReport.statusInfo,
      nodeStatusReport.overrides,
      nodeStatusReport.reports.map { case (tag, r) => (tag, r.filterByDirectives(directiveIds)) }
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

  lazy val compliance: ComplianceLevel = ComplianceLevel.sum(directives.map(_._2.compliance))

  /*
   * This method filters an AggregatedStatusReport by Rules.
   * The goal is to have cheaper way to truncate data from NodeStatusReport that rebuilding it all from top-down
   * With this approach, we don't need to to the RuleNodeStatusReport merge, and performance are about 20 times better than
   * recreating and computing all data
   */
  def filterByRules(ruleIds: Set[RuleId]): AggregatedStatusReport = {
    new AggregatedStatusReport(reports.filter(r => ruleIds.contains(r.ruleId)))
  }

  def filterByDirectives(directiveIds: Set[DirectiveId]): AggregatedStatusReport = {
    new AggregatedStatusReport(
      reports.map(r => {
        r.modify(_.directives)
          .setTo(
            r.directives.flatMap { case (id, d) => if (directiveIds.contains(id)) Some((id, d)) else None }
          )
      })
    )
  }
}

object AggregatedStatusReport {

  def apply(reports: Iterable[RuleNodeStatusReport]): AggregatedStatusReport = {
    val merged = RuleNodeStatusReport.merge(reports)
    new AggregatedStatusReport(merged.values.toSet)
  }
}

/*
 * For a given node, a rule status report gives the rule compliance for JUST ONE compliance tag.
 */
final case class RuleNodeStatusReport(
    nodeId:         NodeId,
    ruleId:         RuleId,
    complianceTag:  PolicyTypeName,
    agentRunTime:   Option[DateTime],
    configId:       Option[NodeConfigId], // only one DirectiveStatusReport by directiveId
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
}

object RuleNodeStatusReport {
  def merge(
      reports: Iterable[RuleNodeStatusReport]
  ): Map[(NodeId, RuleId, PolicyTypeName, Option[DateTime], Option[NodeConfigId]), RuleNodeStatusReport] = {
    reports.groupBy(r => (r.nodeId, r.ruleId, r.complianceTag, r.agentRunTime, r.configId)).map {
      case (id, reports) =>
        val newDirectives = DirectiveStatusReport.merge(reports.toList.flatMap(_.directives.values))

        // the merge of two reports expire when the first one expire
        val expire = new DateTime(reports.map(_.expirationDate.getMillis).min)
        (id, RuleNodeStatusReport(id._1, id._2, id._3, id._4, id._5, newDirectives, expire))
    }
  }
}

/*
 * A directive status report is directly linked to a technique.
 * It is colored with the same PolicyTypes than its technique
 */
final case class DirectiveStatusReport(
    directiveId: DirectiveId, // only one component status report by component name
    policyTypes: PolicyTypes,
    components:  List[ComponentStatusReport]
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
}

object DirectiveStatusReport {

  def merge(directives: List[DirectiveStatusReport]): Map[DirectiveId, DirectiveStatusReport] = {
    directives.groupBy(_.directiveId).map {
      case (directiveId, reports) =>
        val tags          = {
          reports.flatMap(_.policyTypes.types) match {
            case Nil          => PolicyTypes.rudderBase
            case c @ ::(_, _) => PolicyTypes.fromCons(c)
          }
        }
        val newComponents = ComponentStatusReport.merge(reports.flatMap(_.components))
        (directiveId, DirectiveStatusReport(directiveId, tags, newComponents))
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
    import ReportingLogic.*
    reportingLogic match {
      // simple weighted compliance, as usual
      case WeightedReport => ComplianceLevel.sum(subComponents.map(_.compliance))
      // worst case bubble up, and its weight can be either 1 or the sum of sub-component weight
      case worst: WorstReportWeightedReportingLogic =>
        val worstReport = ReportType.getWorseType(subComponents.map(_.status))
        val allReports  = getValues(_ => true).flatMap(_.messages.map(_ => worstReport))
        val kept        = worst match {
          case WorstReportWeightedOne => allReports.take(1)
          case WorstReportWeightedSum => allReports
        }
        ComplianceLevel.compute(kept)
      case FocusWorst =>
        // Get reports of sub-components to find the worst by percent
        val allReports = subComponents.map {
          case b: BlockStatusReport =>
            // Convert block compliance to percent, take the worst
            b.compliance
          case o: ValueStatusReport =>
            // Value has 1 count
            ComplianceLevel.compute(
              Iterable(ReportType.getWorseType(o.getValues(_ => true).flatMap(_.messages.map(_ => o.status))))
            )
        }
        // Find worst by percent: compute numeric compliance with default precision
        val worst      = allReports.minBy(_.computePercent().compliance)
        worst
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
      case FocusWorst | WorstReportWeightedOne | WorstReportWeightedSum | WeightedReport =>
        ReportType.getWorseType(subComponents.map(_.status))
      case FocusReport(component)                                                        =>
        ReportType.getWorseType(findChildren(component).map(_.status))
    }
  }
}
final case class ValueStatusReport(
    componentName:         String,
    expectedComponentName: String, // only one ComponentValueStatusReport by values.

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
 * WorstReportWeightedOne > WorstReportWeightedSum > FocusWorst > WeightedReport > FocusReport
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
            import ReportingLogic.*
            val reportingLogic = r
              .map(_.reportingLogic)
              .reduce((a, b) => {
                (a, b) match {
                  case (WorstReportWeightedOne, _) | (_, WorstReportWeightedOne) => WorstReportWeightedOne
                  case (WorstReportWeightedSum, _) | (_, WorstReportWeightedSum) => WorstReportWeightedSum
                  case (FocusWorst, _) | (_, FocusWorst)                         => FocusWorst
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

  import net.liftweb.json.*
  import net.liftweb.json.JsonDSL.*

  def jsonRunInfo(runInfo: RunAnalysis): JValue = {
    (("type"              -> runInfo.kind.entryName)
    ~ ("expectedConfigId" -> runInfo.expectedConfigId.map(_.value))
    ~ ("runConfigId"      -> runInfo.lastRunConfigId.map(_.value)))
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

  implicit class RunComplianceInfoToJs(val x: (RunAnalysis, RunComplianceInfo)) extends AnyVal {
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
    import ComplianceLevelSerialisation.*

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

/*
 * NodeStatusReport to persist into PostgreSQL.
 * The structure is a leaner copy of NodeStatusReport easily mappable to/from JSON and to/from the original with chimney
 *
 * Since the whole goal is to make the serialization/deserialisation as simple as possible, we won't try to change the
 * json layout (only name to save space).
 * Still, since that will be saved in base, we will need unit test to enforce that we don't introduce unwanted non
 * backward compatible changes: we must be able to always read previous version (even if the rewrite is different).
 *
 * For name, we get first letter while trying:
 * - to have unique identifier for each kind of things. It simplifies debugging and future changes
 * - to append a 's' for collections
 * - xxxxId becomes xid, easier when debugging to identify an id.
 */
object JsonPostgresqlSerialization {

  import com.normation.cfclerk.domain.TechniqueVersion
  import com.normation.rudder.apidata.implicits.*
  import com.normation.rudder.domain.reports.ExpectedReportsSerialisation.*
  import com.normation.rudder.domain.reports.RunComplianceInfo.PolicyModeError
  import com.normation.rudder.services.policies.PolicyId
  import com.normation.utils.DateFormaterService.json.*
  import io.scalaland.chimney.*
  import io.scalaland.chimney.syntax.*
  import zio.json.*

  implicit lazy val codecNodeConfigId:      JsonCodec[NodeConfigId]      = JsonCodec.string.transform(NodeConfigId.apply, _.value)
  implicit lazy val codecReportType:        JsonCodec[ReportType]        = DeriveJsonCodec.gen
  implicit lazy val encoderReportingLogic:  JsonEncoder[ReportingLogic]  = JsonEncoder.string.contramap(_.value)
  implicit lazy val decoderReportingLogic:  JsonDecoder[ReportingLogic]  =
    JsonDecoder.string.mapOrFail(s => ReportingLogic.parse(s).left.map(_.fullMsg))
  implicit lazy val codecRunComplianceInfo: JsonCodec[RunComplianceInfo] = DeriveJsonCodec.gen
  implicit lazy val codecPolicyModeError:   JsonCodec[PolicyModeError]   = DeriveJsonCodec.gen
  implicit lazy val codecPolicyId:          JsonCodec[PolicyId]          = new JsonCodec(
    JsonEncoder.chunk[String].contramap(x => Chunk(x.ruleId.serialize, x.directiveId.serialize, x.techniqueVersion.serialize)),
    JsonDecoder.chunk[String].mapOrFail {
      case Chunk(a, b, c) =>
        for {
          r <- RuleId.parse(a)
          d <- DirectiveId.parse(b)
          t <- TechniqueVersion.parse(c)
        } yield PolicyId(r, d, t)
      case x              => Left(s"Can not deserialize '${x}' into a policy ID.")
    }
  )
  implicit lazy val codecOverriddenPolicy:  JsonCodec[OverriddenPolicy]  = DeriveJsonCodec.gen
  implicit lazy val codecTechniqueVersion:  JsonCodec[TechniqueVersion]  = new JsonCodec(
    JsonEncoder.string.contramap(_.serialize),
    JsonDecoder.string.mapOrFail(TechniqueVersion.parse)
  )

  final case class JRunAnalysis(
      @jsonField("k")
      kind:                RunAnalysisKind,
      @jsonField("ecid")
      expectedConfigId:    Option[NodeConfigId],
      @jsonField("ecs")
      expectedConfigStart: Option[DateTime],
      @jsonField("exp")
      expirationDateTime:  Option[DateTime],
      @jsonField("expSince")
      expiredSince:        Option[DateTime],
      @jsonField("rt")
      lastRunDateTime:     Option[DateTime],
      @jsonField("rid")
      lastRunConfigId:     Option[NodeConfigId],
      @jsonField("rexp")
      lastRunExpiration:   Option[DateTime]
  ) {
    def to: RunAnalysis = this.transformInto[RunAnalysis]
  }

  object JRunAnalysis {
    implicit lazy val i: Iso[JRunAnalysis, RunAnalysis] = Iso.derive

    implicit lazy val encoderRunAnalysisKind: JsonEncoder[RunAnalysisKind] = JsonEncoder.string.contramap(_.entryName)
    implicit lazy val decoderRunAnalysisKind: JsonDecoder[RunAnalysisKind] =
      JsonDecoder.string.mapOrFail(x => RunAnalysisKind.withNameEither(x).left.map(_.getMessage()))

    implicit lazy val codecRunAnalysis: JsonCodec[JRunAnalysis] = DeriveJsonCodec.gen

    def from(r: RunAnalysis): JRunAnalysis = {
      r.transformInto[JRunAnalysis]
    }
  }

  @jsonHint("nsr")
  final case class JNodeStatusReport(
      @jsonField("nid")
      nodeId:     NodeId,
      @jsonField("ri")
      runInfo:    JRunAnalysis,
      @jsonField("si")
      statusInfo: RunComplianceInfo,
      @jsonField("os")
      overrides:  List[OverriddenPolicy],
      @jsonField("rs")
      reports:    Seq[(PolicyTypeName, JAggregatedStatusReport)] // a seq so that we can enforce sorting
  ) {
    def to: NodeStatusReport = this.transformInto[NodeStatusReport]
  }

  object JNodeStatusReport {
    implicit val a: Transformer[JNodeStatusReport, NodeStatusReport] = {
      Transformer
        .define[JNodeStatusReport, NodeStatusReport]
        .withFieldComputed(_.reports, _.reports.map { case (a, b) => (a, b.transformInto[AggregatedStatusReport]) }.toMap)
        .buildTransformer
    }
    implicit val b: Transformer[NodeStatusReport, JNodeStatusReport] = {
      Transformer
        .define[NodeStatusReport, JNodeStatusReport]
        .withFieldComputed(_.reports, _.reports.map { case (a, b) => (a, b.transformInto[JAggregatedStatusReport]) }.toSeq)
        .buildTransformer
    }

    implicit val codecJNodeStatusReport: JsonCodec[JNodeStatusReport] = DeriveJsonCodec.gen

    def from(r: NodeStatusReport): JNodeStatusReport = {
      r.transformInto[JNodeStatusReport]
    }
  }

  /**
 * build an aggregated view of a set of reports,
 * allowing to access compound compliance for rules,
 * directives and so on
 */
  @jsonHint("asr")
  final case class JAggregatedStatusReport(
      @jsonField("rnsrs")
      val reports: Seq[JRuleNodeStatusReport]
  ) {
    def to: AggregatedStatusReport = this.transformInto[AggregatedStatusReport]
  }

  object JAggregatedStatusReport {
    implicit val a: Transformer[JAggregatedStatusReport, AggregatedStatusReport] = (x: JAggregatedStatusReport) =>
      AggregatedStatusReport(x.reports.map(_.transformInto[RuleNodeStatusReport]))

    implicit val b: Transformer[AggregatedStatusReport, JAggregatedStatusReport] = {
      Transformer
        .define[AggregatedStatusReport, JAggregatedStatusReport]
        .withFieldComputed(_.reports, _.reports.toSeq.map(_.transformInto[JRuleNodeStatusReport]).sortBy(_.ruleId.uid.value))
        .buildTransformer
    }

    implicit val codecJAggregatedStatusReport: JsonCodec[JAggregatedStatusReport] = DeriveJsonCodec.gen

    def from(r: RuleNodeStatusReport): JRuleNodeStatusReport = {
      r.transformInto[JRuleNodeStatusReport]
    }
  }

  @jsonHint("rnsr")
  final case class JRuleNodeStatusReport(
      @jsonField("nid")
      nodeId:         NodeId,
      @jsonField("rid")
      ruleId:         RuleId,
      @jsonField("ct")
      complianceTag:  PolicyTypeName,
      @jsonField("art")
      agentRunTime:   Option[DateTime],
      @jsonField("cid")
      configId:       Option[NodeConfigId],
      @jsonField("exp")
      expirationDate: DateTime,
      @jsonField("dsrs")
      directives:     Seq[JDirectiveStatusReport]
  ) {
    def to: RuleNodeStatusReport = this.transformInto[RuleNodeStatusReport]
  }

  object JRuleNodeStatusReport {
    implicit val a: Transformer[JRuleNodeStatusReport, RuleNodeStatusReport] = {
      Transformer
        .define[JRuleNodeStatusReport, RuleNodeStatusReport]
        .withFieldComputed(_.directives, _.directives.map(d => (d.directiveId, d.transformInto[DirectiveStatusReport])).toMap)
        .buildTransformer
    }
    implicit val b: Transformer[RuleNodeStatusReport, JRuleNodeStatusReport] = {
      Transformer
        .define[RuleNodeStatusReport, JRuleNodeStatusReport]
        .withFieldComputed(
          _.directives,
          _.directives.map { case (_, d) => d.transformInto[JDirectiveStatusReport] }.toSeq.sortBy(_.directiveId.uid.value)
        )
        .buildTransformer
    }

    implicit val codecJRuleNodeStatusReport: JsonCodec[JRuleNodeStatusReport] = DeriveJsonCodec.gen

    def from(r: RuleNodeStatusReport): JRuleNodeStatusReport = {
      r.transformInto[JRuleNodeStatusReport]
    }
  }

  @jsonHint("dsr")
  final case class JDirectiveStatusReport(
      @jsonField("did")
      directiveId: DirectiveId,
      @jsonField("pts")
      policyTypes: PolicyTypes,
      @jsonField("csrs")
      components:  List[JComponentStatusReport]
  ) {
    def to: DirectiveStatusReport = this.transformInto[DirectiveStatusReport]
  }

  object JDirectiveStatusReport {
    implicit lazy val i: Iso[JDirectiveStatusReport, DirectiveStatusReport] = Iso.derive

    implicit val codecJDirectiveStatusReport: JsonCodec[JDirectiveStatusReport] = DeriveJsonCodec.gen

    def from(r: DirectiveStatusReport): JDirectiveStatusReport = {
      r.transformInto[JDirectiveStatusReport]
    }
  }

  sealed trait JComponentStatusReport {
    def componentName: String
    def to:            ComponentStatusReport = this.transformInto[ComponentStatusReport]
  }

  object JComponentStatusReport {
    // can't use Iso and need to teach chimey about recursive sealed coproduct
    implicit lazy val a1: Transformer[JBlockStatusReport, BlockStatusReport]         = Transformer.derive
    implicit lazy val b1: Transformer[JValueStatusReport, ValueStatusReport]         = Transformer.derive
    implicit lazy val c1: Transformer[JComponentStatusReport, ComponentStatusReport] = {
      case x: JBlockStatusReport => x.transformInto[BlockStatusReport]
      case x: JValueStatusReport => x.transformInto[ValueStatusReport]
    }
    implicit lazy val a2: Transformer[BlockStatusReport, JBlockStatusReport]         = Transformer.derive
    implicit lazy val b2: Transformer[ValueStatusReport, JValueStatusReport]         = Transformer.derive
    implicit lazy val c2: Transformer[ComponentStatusReport, JComponentStatusReport] = Transformer
      .define[ComponentStatusReport, JComponentStatusReport]
      .withSealedSubtypeHandled[BlockStatusReport](_.transformInto[JBlockStatusReport])
      .withSealedSubtypeHandled[ValueStatusReport](_.transformInto[JValueStatusReport])
      .buildTransformer

    implicit lazy val codecJComponentStatusReport: JsonCodec[JComponentStatusReport] = DeriveJsonCodec.gen
    implicit lazy val codecJBlockStatusReport:     JsonCodec[JBlockStatusReport]     = DeriveJsonCodec.gen
    implicit lazy val codecJValueStatusReport:     JsonCodec[JValueStatusReport]     = DeriveJsonCodec.gen

    def from(r: ComponentStatusReport): JComponentStatusReport = {
      r.transformInto[JComponentStatusReport]
    }

    def from(r: BlockStatusReport): JBlockStatusReport = {
      r.transformInto[JBlockStatusReport]
    }

    def from(r: ValueStatusReport): JValueStatusReport = {
      r.transformInto[JValueStatusReport]
    }

    @jsonHint("bsr")
    final case class JBlockStatusReport(
        @jsonField("cn")
        componentName:  String,
        @jsonField("rl")
        reportingLogic: ReportingLogic,
        @jsonField("csrs")
        subComponents:  List[JComponentStatusReport]
    ) extends JComponentStatusReport {
      override def to: ComponentStatusReport = this.transformInto[BlockStatusReport]
    }

    @jsonHint("vsr")
    final case class JValueStatusReport(
        @jsonField("cn")
        componentName:         String,
        @jsonField("ecn")
        expectedComponentName: String,
        @jsonField("cvsrs")
        componentValues:       List[JComponentValueStatusReport]
    ) extends JComponentStatusReport {
      override def to: ComponentStatusReport = this.transformInto[ValueStatusReport]
    }
  }

  @jsonHint("cvsr")
  final case class JComponentValueStatusReport(
      @jsonField("cn")
      componentValue:         String,
      @jsonField("ecn")
      expectedComponentValue: String,
      @jsonField("rtid")
      reportId:               String,
      @jsonField("msrs")
      messages:               List[JMessageStatusReport]
  ) {
    def to: ComponentValueStatusReport = this.transformInto[ComponentValueStatusReport]
  }

  object JComponentValueStatusReport {
    implicit lazy val i: Iso[JComponentValueStatusReport, ComponentValueStatusReport] = Iso.derive

    implicit val codecJComponentValueStatusReport: JsonCodec[JComponentValueStatusReport] = DeriveJsonCodec.gen

    def from(r: ComponentValueStatusReport): JComponentValueStatusReport = {
      r.transformInto[JComponentValueStatusReport]
    }
  }

  @jsonHint("msr")
  final case class JMessageStatusReport(
      @jsonField("rt")
      reportType: ReportType,
      @jsonField("m")
      message:    Option[String]
  ) {
    def to: MessageStatusReport = this.transformInto[MessageStatusReport]
  }

  object JMessageStatusReport {
    implicit val i: Iso[JMessageStatusReport, MessageStatusReport] = Iso.derive

    implicit val codecJMessageStatusReport: JsonCodec[JMessageStatusReport] = DeriveJsonCodec.gen

    def from(r: MessageStatusReport): JMessageStatusReport = {
      r.transformInto[JMessageStatusReport]
    }

  }
}
