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

import org.joda.time.DateTime

import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.RuleId

import net.liftweb.common.Loggable
import com.normation.rudder.services.reports._

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
  def compliance : ComplianceLevel
}

/**
 * Define two aggregated view of compliance: by node (for
 * a given rule) and by rules (for a given node).
 */
final class RuleStatusReport private (
    val forRule: RuleId
  , val report : AggregatedStatusReport
) extends StatusReport {
  val compliance = report.compliance
  val byNodes: Map[NodeId, AggregatedStatusReport] = report.reports.groupBy(_.nodeId).mapValues(AggregatedStatusReport(_))
}

object RuleStatusReport {
  def apply(ruleId: RuleId, reports: Iterable[RuleNodeStatusReport]) = {
    new RuleStatusReport(ruleId, AggregatedStatusReport(reports.toSet.filter( _.ruleId == ruleId)))
  }
}

/*
 * meta information about a run compliance analysis -
 * in particular abour policy mode errors (agent aborted,
 * mixed mode in directives from the same techniques, etc)
 */
sealed trait RunComplianceInfo
final object RunComplianceInfo {
  sealed trait PolicyModeError
  object PolicyModeError {
    final case class TechniqueMixedMode(message: String) extends PolicyModeError
    final case class AgentAbortMessage(cause: String, message: String) extends PolicyModeError
  }

  final object OK extends RunComplianceInfo
  final case class PolicyModeInconsistency(problems: List[PolicyModeError]) extends RunComplianceInfo
}


final class NodeStatusReport private (
    val nodeId    : NodeId
  , val runInfo   : RunAndConfigInfo
  , val statusInfo: RunComplianceInfo
  , val overrides : List[OverridenPolicy]
  , val report    : AggregatedStatusReport
) extends StatusReport {
  lazy val compliance = report.compliance
  lazy val byRules: Map[RuleId, AggregatedStatusReport] = report.reports.groupBy(_.ruleId).mapValues(AggregatedStatusReport(_))
}

object NodeStatusReport {
  def apply(nodeId: NodeId, runInfo:  RunAndConfigInfo, statusInfo: RunComplianceInfo, overrides : List[OverridenPolicy], reports: Iterable[RuleNodeStatusReport]) = {
    new NodeStatusReport(nodeId, runInfo, statusInfo, overrides, AggregatedStatusReport(reports.toSet.filter( _.nodeId == nodeId)))
  }

  // To use when you are sure that all reports are indeed for the designated node.
  def applyByNode(nodeId: NodeId, runInfo:  RunAndConfigInfo, statusInfo: RunComplianceInfo, overrides : List[OverridenPolicy], reports: Iterable[RuleNodeStatusReport]) = {
    new NodeStatusReport(nodeId, runInfo, statusInfo, overrides, AggregatedStatusReport(reports.toSet))
  }

  /*
   * This method filters an NodeStatusReport by Rules.
   * The goal is to have cheaper way to truncate data from NodeStatusReport that rebuilding it all from top-down
   * With this approach, we don't need to to the RuleNodeStatusReport merge, and performance are about 20 times better than
   * recreating and computing all data
   * it take a NodeStatusReport, and returns it with only the relevant rules
   */
  def filterByRules(nodeStatusReport: NodeStatusReport, ruleIds: Set[RuleId]) : NodeStatusReport = {
    new NodeStatusReport(
        nodeStatusReport.nodeId
      , nodeStatusReport.runInfo
      , nodeStatusReport.statusInfo
      , nodeStatusReport.overrides
      , AggregatedStatusReport.applyFromAggregatedStatusReport(nodeStatusReport.report, ruleIds)
    )


  }
}

/**
 * build an aggregated view of a set of reports,
 * allowing to access compound compliance for rules,
 * directives and so on
 */
final class AggregatedStatusReport private(
    val reports: Set[RuleNodeStatusReport]
) {
  /**
   * rebuild all the trees from the available status
   */
  lazy val directives: Map[DirectiveId, DirectiveStatusReport] = {
    //toSeq is mandatory; here, we may have some directive only different
    //by rule or node and we don't want to loose the weight
    DirectiveStatusReport.merge(reports.toSeq.flatMap( _.directives.values))
  }
  lazy val compliance = ComplianceLevel.sum(directives.map(_._2.compliance))
}

object AggregatedStatusReport {

  def apply(reports: Iterable[RuleNodeStatusReport]) = {
    val merged = RuleNodeStatusReport.merge(reports)
    new AggregatedStatusReport(merged.values.toSet)
  }

  /*
   * This method filters an AggragatedStatusReport by Rules.
   * The goal is to have cheaper way to truncate data from NodeStatusReport that rebuilding it all from top-down
   * With this approach, we don't need to to the RuleNodeStatusReport merge, and performance are about 20 times better than
   * recreating and computing all data
   */
  def applyFromAggregatedStatusReport(statusReport: AggregatedStatusReport, ruleIds: Set[RuleId]) = {
    new AggregatedStatusReport(statusReport.reports.filter(r => ruleIds.contains(r.ruleId)))
  }

}


final case class RuleNodeStatusReport(
    nodeId        : NodeId
  , ruleId        : RuleId
  , agentRunTime  : Option[DateTime]
  , configId      : Option[NodeConfigId]
    //only one DirectiveStatusReport by directiveId
  , directives    : Map[DirectiveId, DirectiveStatusReport]
  , expirationDate: DateTime
) extends StatusReport {

  override lazy val compliance = ComplianceLevel.sum(directives.map(_._2.compliance) )

  override def toString() = s"""[[${nodeId.value}: ${ruleId.value}; run: ${agentRunTime.getOrElse("no time")};${configId.map(_.value).getOrElse("no config id")}->${expirationDate}]
  |  compliance:${compliance}
  |  ${directives.values.toSeq.sortBy( _.directiveId.value ).map { x => s"${x}" }.mkString("\n  ")}]
  |""".stripMargin('|')


  def getValues(predicate: ComponentValueStatusReport => Boolean): Seq[(DirectiveId, String, ComponentValueStatusReport)] = {
      directives.values.flatMap( _.getValues(predicate)).toSeq
  }

  def withFilteredElements(
      directive: DirectiveStatusReport => Boolean
    , component: ComponentStatusReport => Boolean
    , values   : ComponentValueStatusReport => Boolean
  ): Option[RuleNodeStatusReport] = {
    val dirs = (
        directives.values.filter(directive(_))
        .flatMap(_.withFilteredElements(component, values))
        .map(x => (x.directiveId, x)).toMap
    )
    if(dirs.isEmpty) None
    else Some(this.copy(directives = dirs))
  }
}

object RuleNodeStatusReport {
 def merge(reports: Iterable[RuleNodeStatusReport]): Map[(NodeId, RuleId, Option[DateTime], Option[NodeConfigId]), RuleNodeStatusReport] = {
    reports.groupBy(r => (r.nodeId, r.ruleId, r.agentRunTime, r.configId)).map { case (id, reports) =>
      val newDirectives = DirectiveStatusReport.merge(reports.flatMap( _.directives.values))

      //the merge of two reports expire when the first one expire
      val expire = new DateTime( reports.map( _.expirationDate.getMillis).min )
      (id, RuleNodeStatusReport(id._1, id._2, id._3, id._4, newDirectives, expire))
    }.toMap
  }
}

final case class DirectiveStatusReport(
    directiveId: DirectiveId
    //only one component status report by component name
  , components : Map[String, ComponentStatusReport]
) extends StatusReport {
  override lazy val compliance = ComplianceLevel.sum(components.map(_._2.compliance) )
  def getValues(predicate: ComponentValueStatusReport => Boolean): Seq[(DirectiveId, String, ComponentValueStatusReport)] = {
      components.values.flatMap( _.getValues(predicate) ).toSeq.map { case(s,v) => (directiveId,s,v) }
  }

  override def toString() = s"""[${directiveId.value} =>
                               |    ${components.values.toSeq.sortBy(_.componentName).mkString("\n    ")}
                               |]"""

  def withFilteredElements(
      component: ComponentStatusReport => Boolean
    , values   : ComponentValueStatusReport => Boolean
  ): Option[DirectiveStatusReport] = {
    val cpts = (
        components.values.filter(component(_))
        .flatMap( _.withFilteredElement(values))
        .map(x => (x.componentName, x)).toMap
    )

    if(cpts.isEmpty) None
    else Some(this.copy(components = cpts))
  }
}

object DirectiveStatusReport {

  def merge(directives: Iterable[DirectiveStatusReport]): Map[DirectiveId, DirectiveStatusReport] = {
    directives.groupBy( _.directiveId).map { case (directiveId, reports) =>
      val newComponents = ComponentStatusReport.merge(reports.flatMap( _.components.values))
      (directiveId, DirectiveStatusReport(directiveId, newComponents))
    }.toMap
  }
}

/**
 * For a component, store the report status, as the worse status of the component
 * Or error if there is an unexpected component value
 */
final case class ComponentStatusReport(
    componentName      : String
    //only one ComponentValueStatusReport by value
  , componentValues    : Map[String, ComponentValueStatusReport]
) extends StatusReport {

  override def toString() = s"${componentName}:${componentValues.values.toSeq.sortBy(_.componentValue).mkString("[", ",", "]")}"

  override lazy val compliance = ComplianceLevel.sum(componentValues.map(_._2.compliance) )
  /*
   * Get all values matching the predicate
   */
  def getValues(predicate: ComponentValueStatusReport => Boolean): Seq[(String, ComponentValueStatusReport)] = {
      componentValues.values.filter(predicate(_)).toSeq.map(x => (componentName, x))
  }

  /*
   * Rebuild a componentStatusReport, keeping only values matching the predicate
   */
  def withFilteredElement(predicate: ComponentValueStatusReport => Boolean): Option[ComponentStatusReport] = {
    val values = componentValues.filter { case (k,v) => predicate(v) }
    if(values.isEmpty) None
    else Some(this.copy(componentValues = values))
  }
}

object ComponentStatusReport extends Loggable {

  def merge(components: Iterable[ComponentStatusReport]): Map[String, ComponentStatusReport] = {
    components.groupBy( _.componentName).map { case (cptName, reports) =>
      val newValues = ComponentValueStatusReport.merge(reports.flatMap( _.componentValues.values))
      (cptName, ComponentStatusReport(cptName, newValues))
    }.toMap
  }
}

/**
 * For a component value, store the report status
 *
 * In fact, we only keep unexpanded component values.
 * Values are lost. They are not really used today
 */
final case class ComponentValueStatusReport(
    componentValue          : String
  , unexpandedComponentValue: String
  , messages                : List[MessageStatusReport]
) extends StatusReport {

  override def toString() = s"${componentValue}(<-> ${unexpandedComponentValue}):${messages.mkString("[", ";", "]")}"

  override lazy val compliance = ComplianceLevel.compute(messages.map( _.reportType))

  /*
   * It can be argued that a status for a value may make sense,
   * even in the case where several nodes contributed to it
   */
  lazy val status = ReportType.getWorseType(messages.map(_.reportType))
}

object ComponentValueStatusReport extends Loggable {
  /**
   * Merge a set of ComponentValueStatusReport, grouping
   * them by component *unexpanded* value
   */
  def merge(values: Iterable[ComponentValueStatusReport]): Map[String, ComponentValueStatusReport] = {
    val pairs = values.groupBy(_.unexpandedComponentValue).map { case (unexpanded, values) =>
      //the unexpanded value should be the same on all values.
      //if not, report an error for devs
      (
          unexpanded,
          ComponentValueStatusReport(unexpanded, unexpanded, values.toList.flatMap(_.messages))
      )

    }
    pairs.toMap
  }
}


final case class MessageStatusReport(
    reportType: ReportType
  , message   : Option[String]
) {
  override def toString() = reportType.severity + message.fold(":\"\"")(":\""+ _ + "\"")
}

object MessageStatusReport {

  def apply(status: ReportType, message: String) = {
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
      case NoRunNoExpectedReport   =>
        ( "type" -> "NoRunNoExpectedReport" )
      case NoExpectedReport(t, id) =>
        ( ( "type"           -> "NoExpectedReport" )
        ~ ("lastRunConfigId" -> id.map( _.value )  )
        )
      case NoReportInInterval(e) =>
        ( ( "type"             -> "NoReportInInterval" )
        ~ ( "expectedConfigId" -> e.nodeConfigId.value )
        )
      case ReportsDisabledInInterval(e) =>
        ( ( "type"             -> "ReportsDisabled"   )
        ~ ( "expectedConfigId" -> e.nodeConfigId.value)
        )
      case UnexpectedVersion(t, id, _, e, _) =>
        ( ( "type"             -> "UnexpectedVersion"       )
        ~ ( "expectedConfigId" -> e.nodeConfigId.value      )
        ~ ( "runConfigId"      -> id.get.nodeConfigId.value )
        )
      case UnexpectedNoVersion(_, id, _, e, _) =>
        ( ( "type"             -> "UnexpectedNoVersion" )
        ~ ( "expectedConfigId" -> e.nodeConfigId.value  )
        ~ ( "runConfigId"      -> id.value              )
        )
      case UnexpectedUnknowVersion(_, id, e, _) =>
        ( ( "type"             -> "UnexpectedUnknownVersion" )
        ~ ( "expectedConfigId" -> e.nodeConfigId.value       )
        ~ ( "runConfigId"      -> id.value                   )
        )
      case Pending(e, r, _) =>
        ( ( "type"             -> "Pending"               )
        ~ ( "expectedConfigId" -> e.nodeConfigId.value    )
        ~ ("runConfigId"       -> r.map( _._2.nodeConfigId.value ) )
        )
      case ComputeCompliance(_, e, _) =>
        ( ( "type"             -> "ComputeCompliance" )
        ~ ( "expectedConfigId" -> e.nodeConfigId.value       )
        ~ ( "runConfigId"      -> e.nodeConfigId.value       )
        )
    }
  }
  def jsonStatusInfo(statusInfo: RunComplianceInfo): JValue = {
    (
      ( "status" -> (statusInfo match {
                      case RunComplianceInfo.OK => "success"
                      case _                    => "error"
                    })
      ) ~ (
        "errors" -> (statusInfo match {
                      case RunComplianceInfo.OK => None
                      case RunComplianceInfo.PolicyModeInconsistency(errors) => Some(errors.map {
                        case RunComplianceInfo.PolicyModeError.TechniqueMixedMode(msg) =>
                          ( "policyModeError" -> ( "message" -> msg ) ):JObject
                        case RunComplianceInfo.PolicyModeError.AgentAbortMessage(cause, msg) =>
                          ( "policyModeInconsistency" -> ( ( "cause" -> cause ) ~ ( "message" -> msg ) ) ):JObject
                      })
                    })
      )
    )
  }

  implicit class RunComplianceInfoToJs(val x: (RunAndConfigInfo, RunComplianceInfo)) extends AnyVal {
    def toJValue() = {
      (
        ( "run"    -> jsonRunInfo(x._1)    )
      ~ ( "status" -> jsonStatusInfo(x._2) )
      )
    }

    def toJson() = prettyRender(toJValue)
    def toCompactJson = compactRender(toJValue)
  }

  implicit class AggregatedStatusReportToJs(val x: AggregatedStatusReport) extends AnyVal {
    import ComplianceLevelSerialisation._

    def toJValue(): JValue = {

      //here, I'm not sure that we want compliance or
      //compliance percents. Having a normalized value
      //seems far better for queries in the futur.
      //but in that case, we should also keep the total
      //number of events to be able to rebuild raw data

      ("rules" -> (x.reports.map { r =>
        (
          ("ruleId"        -> r.ruleId.value)
        ~ ("compliance"    -> r.compliance.pc.toJson)
        ~ ("numberReports" -> r.compliance.total)
        ~ ("directives"    -> (r.directives.values.map { d =>
          (
            ("directiveId"   -> d.directiveId.value)
          ~ ("compliance"    -> d.compliance.pc.toJson)
          ~ ("numberReports" -> d.compliance.total)
          ~ ("components"    -> (d.components.values.map { c =>
              (
                ("componentName" -> c.componentName)
              ~ ("compliance"    -> c.compliance.pc.toJson)
              ~ ("numberReports" -> c.compliance.total)
              ~ ("values"        -> (c.componentValues.values.map { v =>
                  (
                    ("value"         -> v.componentValue)
                  ~ ("compliance"    -> v.compliance.pc.toJson)
                  ~ ("numberReports" -> v.compliance.total)
                  ~ ("unexpanded"    -> v.unexpandedComponentValue)
                  ~ ("messages"      -> (v.messages.map { m =>
                      (
                        ("message" -> m.message )
                      ~ ("type"    -> m.reportType.severity)
                      )
                    }))
                  )
                }))
              )
            }))
          )
          }))
        )
      }))
    }

    def toJson() = prettyRender(toJValue)
    def toCompactJson = compactRender(toJValue)
  }
}

