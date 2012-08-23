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

/**
 * List of all type of report we are expecting, as a result
 * So far :
 * Success
 * Repaired
 * Error
 * Unknown
 * No Answer
 */


trait ReportType {}

case object SuccessReportType extends ReportType
case object RepairedReportType extends ReportType
case object ErrorReportType extends ReportType
case object UnknownReportType extends ReportType
case object NoAnswerReportType extends ReportType
case object PendingReportType extends ReportType

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
    status match {
      case SuccessReportType => "Success"
      case RepairedReportType => "Repaired"
      case ErrorReportType => "Error"
      case NoAnswerReportType => "No answer"
      case PendingReportType => "Applying"
      case _ => "Unknown"
    }
  }
}