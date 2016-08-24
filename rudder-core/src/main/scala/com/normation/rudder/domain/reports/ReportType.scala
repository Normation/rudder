/*
*************************************************************************************
* Copyright 2012 Normation SAS
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
import scala.language.implicitConversions



/**
 * Kind of reports that we can get as result of
 * a merge/compare with expected reports.
 */
sealed trait ReportType {
  val severity :String
}

case object NotApplicableReportType extends ReportType {
  val severity = "NotApplicable"
}
case object SuccessReportType extends ReportType {
  val severity = "Success"
}
case object RepairedReportType extends ReportType{
  val severity = "Repaired"
}
case object ErrorReportType extends ReportType{
  val severity = "Error"
}
case object UnexpectedReportType extends ReportType{
  val severity = "Unexpected"
}
case object NoAnswerReportType extends ReportType{
  val severity = "NoAnswer"
}
case object DisabledReportType extends ReportType{
  val severity = "ReportsDisabled"
}
case object PendingReportType extends ReportType{
  val severity = "Applying"
}
case object MissingReportType extends ReportType{
  val severity = "Missing"
}

object ReportType {

  def getWorseType(reportTypes : Iterable[ReportType]) : ReportType = {
    if (reportTypes.isEmpty) {
      NoAnswerReportType
    } else {
      ( reportTypes :\ (NotApplicableReportType : ReportType) ) {
        case (_, UnexpectedReportType)     | (UnexpectedReportType, _)    => UnexpectedReportType
        case (_, ErrorReportType)          | (ErrorReportType, _)         => ErrorReportType
        case (_, RepairedReportType)       | (RepairedReportType, _)      => RepairedReportType
        case (_, MissingReportType)        | (MissingReportType, _)       => MissingReportType
        case (_, NoAnswerReportType)       | (NoAnswerReportType, _)      => NoAnswerReportType
        case (_, DisabledReportType)| (DisabledReportType, _)      => DisabledReportType
        case (_, PendingReportType)        | (PendingReportType, _)       => PendingReportType
        case (_, SuccessReportType)        | (SuccessReportType, _)       => SuccessReportType
        case (_, NotApplicableReportType)  | (NotApplicableReportType, _) => NotApplicableReportType
        case _ => UnexpectedReportType
      }
    }
  }

  def apply(report : Reports) : ReportType = {
    report match {
      case _ : ResultSuccessReport       => SuccessReportType
      case _ : ResultErrorReport         => ErrorReportType
      case _ : ResultRepairedReport      => RepairedReportType
      case _ : ResultNotApplicableReport => NotApplicableReportType
      case _                             => UnexpectedReportType
    }
  }

  implicit def reportTypeSeverity(reportType:ReportType):String = reportType.severity
}

/**
 * Case class to store the previous (last received) configid  and the expected NodeConfigId in the Pending case
 */
final case class PreviousAndExpectedNodeConfigId(previous: NodeAndConfigId, expected: NodeAndConfigId)
