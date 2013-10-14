/*
*************************************************************************************
* Copyright 2012 Normation SAS
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


package com.normation.rudder.domain.reports.bean


import com.normation.rudder.domain.reports.bean._


/**
 * List of all type of report we are expecting, as a result
 * So far :
 * Success
 * Repaired
 * Error
 * Unknown
 * No Answer
 */


trait ReportType {

  val severity :String

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
case object UnknownReportType extends ReportType{
  val severity = "Unknown"
}
case object NoAnswerReportType extends ReportType{
  val severity = "No answer"
}
case object PendingReportType extends ReportType{
  val severity = "Applying"
}

object ReportType {

  def getWorseType(reportTypes : Seq[ReportType]) : ReportType = {
    if (reportTypes.isEmpty) {
      NoAnswerReportType
    } else {
      ( reportTypes :\ (SuccessReportType : ReportType) ) {
        case (_, UnknownReportType) | (UnknownReportType, _) => UnknownReportType
        case (_, ErrorReportType) | (ErrorReportType, _) => ErrorReportType
        case (_, RepairedReportType) | (RepairedReportType, _) => RepairedReportType
        case (_, NoAnswerReportType) | (NoAnswerReportType, _) => NoAnswerReportType
        case (_, PendingReportType) | (PendingReportType, _) => PendingReportType
        case (_, SuccessReportType) | (SuccessReportType, _) => SuccessReportType
        case _ => UnknownReportType
      }
    }
  }

  def getSeverityFromStatus(status : ReportType) : String = {
      status.severity
  }

  def apply(report : Reports) : ReportType = {
    report match {
      case _ : ResultSuccessReport  => SuccessReportType
      case _ : ResultErrorReport    => ErrorReportType
      case _ : ResultRepairedReport => RepairedReportType
      case _                        => UnknownReportType
    }
  }

  def apply(status : String):ReportType = { status match {
    case "Success" => SuccessReportType
    case "Repaired" => RepairedReportType
    case "Error" => ErrorReportType
    case "No answer" => NoAnswerReportType
    case "Applying" => PendingReportType
    case _ => UnknownReportType
  }
  }

  implicit def reportTypeSeverity(reportType:ReportType):String = ReportType.getSeverityFromStatus(reportType)
}