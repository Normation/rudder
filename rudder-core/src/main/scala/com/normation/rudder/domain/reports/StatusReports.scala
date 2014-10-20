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

package com.normation.rudder.domain.reports

import org.joda.time.DateTime

import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.RuleId
import net.liftweb.common.Loggable

/**
 * That file contains all the kind of status reports:
 *
 * for a component, a directive, a node, etc.
 *
 */

//simple data structure to hold percentages of different compliance
//the ints are actual numbers, percents are computed with the pc_ variants
case class ComplianceLevel(
    pending      : Int = 0
  , success      : Int = 0
  , repaired     : Int = 0
  , error        : Int = 0
  , unexpected   : Int = 0
  , missing      : Int = 0
  , noAnswer     : Int = 0
  , notApplicable: Int = 0
) {

  override def toString() = s"[p:${pending} s:${success} r:${repaired} e:${error} u:${unexpected} m:${missing} nr:${noAnswer} na:${notApplicable}]"

  val total = pending+success+repaired+error+unexpected+missing+noAnswer+notApplicable

  private[this] def pc(i:Int) = if(total == 0) 0 else i * 100 / total

  val pc_pending       = pc(pending)
  val pc_success       = pc(success)
  val pc_repaired      = pc(repaired)
  val pc_error         = pc(error)
  val pc_unexpected    = pc(unexpected)
  val pc_missing       = pc(missing)
  val pc_noAnswer      = pc(noAnswer)
  val pc_notApplicable = pc(notApplicable)

  def +(compliance: ComplianceLevel): ComplianceLevel = {
    ComplianceLevel(
        pending       = this.pending + compliance.pending
      , success       = this.success + compliance.success
      , repaired      = this.repaired + compliance.repaired
      , error         = this.error + compliance.error
      , unexpected    = this.unexpected + compliance.unexpected
      , missing       = this.missing + compliance.missing
      , noAnswer      = this.noAnswer + compliance.noAnswer
      , notApplicable = this.notApplicable + compliance.notApplicable
    )
  }

  def +(report: ReportType): ComplianceLevel = this+ComplianceLevel.compute(Seq(report))
  def +(reports: Iterable[ReportType]): ComplianceLevel = this+ComplianceLevel.compute(reports)
}

object ComplianceLevel {
 def compute(reports: Iterable[ReportType]): ComplianceLevel = {
    if(reports.isEmpty) { ComplianceLevel(notApplicable = 100)}
    else reports.foldLeft(ComplianceLevel()) { case (compliance, report) =>
      report match {
        case NotApplicableReportType => compliance.copy(notApplicable = compliance.notApplicable + 1)
        case SuccessReportType => compliance.copy(success = compliance.success + 1)
        case RepairedReportType => compliance.copy(repaired = compliance.repaired + 1)
        case ErrorReportType => compliance.copy(error = compliance.error + 1)
        case UnexpectedReportType => compliance.copy(unexpected = compliance.unexpected + 1)
        case MissingReportType => compliance.copy(missing = compliance.missing + 1)
        case NoAnswerReportType => compliance.copy(noAnswer = compliance.noAnswer + 1)
        case PendingReportType => compliance.copy(pending = compliance.pending + 1)
      }
    }
  }

 def sum(compliances: Iterable[ComplianceLevel]): ComplianceLevel = {
   if(compliances.isEmpty) ComplianceLevel()
   else compliances.reduce( _ + _)
 }
}

sealed trait StatusReport {
  def compliance : ComplianceLevel
}

/**
 * build an aggregated view of a set of reports,
 * allowing to access compound compliance for rules,
 * directives and so on
 */
final case class AggregatedStatusReport(
    reports: Set[RuleNodeStatusReport]
) {
  /**
   * rebuild all the trees from the available status
   */
  val directives: Map[DirectiveId, DirectiveStatusReport] = {
    //toSeq is mandatory; here, we may have some directive only different
    //by rule or node and we don't want to loose the weight
    DirectiveStatusReport.merge(reports.toSeq.flatMap( _.directives.values))
  }
  val compliance = ComplianceLevel.sum(directives.map(_._2.compliance))
}


final case class RuleNodeStatusReport(
    nodeId      : NodeId
  , ruleId      : RuleId
  , serial      : Int
  , agentRunTime: Option[DateTime]
  , configId    : Option[NodeConfigId]
    //only one DirectiveStatusReport by directiveId
  , directives  : Map[DirectiveId, DirectiveStatusReport]
) extends StatusReport {

  override val compliance = ComplianceLevel.sum(directives.map(_._2.compliance) )

  override def toString() = s"""[[${nodeId.value}: ${ruleId.value}/${serial}; run: ${agentRunTime.getOrElse("no time")};${configId.map(_.value).getOrElse("no config id")}]
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

final case class DirectiveStatusReport(
    directiveId: DirectiveId
    //only one component status report by component name
  , components : Map[String, ComponentStatusReport]
) extends StatusReport {
  override val compliance = ComplianceLevel.sum(components.map(_._2.compliance) )
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

  override val compliance = ComplianceLevel.sum(componentValues.map(_._2.compliance) )
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
 */
final case class ComponentValueStatusReport(
    componentValue 		   : String
  , unexpandedComponentValue: Option[String]
  , messages                : List[MessageStatusReport]
) extends StatusReport {

  override def toString() = s"${componentValue}${unexpandedComponentValue.fold("")(x => "(<->"+x+")")}:${messages.mkString("[", ";", "]")}"

  override val compliance = ComplianceLevel.compute(messages.map( _.reportType))

  /*
   * It can be argued that a status for a value may make sense,
   * even in the case where several nodes contributed to it
   */
  val status = ReportType.getWorseType(messages.map(_.reportType))
}

object ComponentValueStatusReport extends Loggable {
  /**
   * Merge a set of ComponentValueStatusReport, grouping
   * them by component value
   */
  def merge(values: Iterable[ComponentValueStatusReport]): Map[String, ComponentValueStatusReport] = {
    val pairs = values.groupBy(_.componentValue).map { case (valueName, values) =>
      //the unexpanded value should be the same on all values.
      //if not, report an error for devs
      val (unexpanded, messages) = ((Option.empty[String],List.empty[MessageStatusReport])/:values) { case(acc,next) =>

        val unex = (acc._1, next.unexpandedComponentValue) match {
          case (None, Some(x)) => Some(x)
          case (Some(x), None) => Some(x)
          case (None, None) => None
          case (Some(x), Some(y)) =>
            if(x != y) {
              logger.debug(s"Report for a key values where bounded to several unexpanded value. Keeping '${x}', discarding '${y}'")
            }
            Some(x)
        }
        (unex, acc._2 ++ next.messages)
      }

      (
          valueName,
          ComponentValueStatusReport(valueName, unexpanded, messages)
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

