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
import com.normation.rudder.domain.policies.PolicyMode



/**
 * Kind of reports that we can get as result of
 * a merge/compare with expected reports.
 */
sealed trait ReportType {
  val severity :String
}


object ReportType {
  case object EnforceNotApplicable extends ReportType {
    val severity = "NotApplicable"
  }
  case object EnforceSuccess extends ReportType {
    val severity = "Success"
  }
  case object EnforceRepaired extends ReportType{
    val severity = "Repaired"
  }
  case object EnforceError extends ReportType{
    val severity = "Error"
  }

  case object AuditNotApplicable extends ReportType {
    val severity = "AuditNotApplicable"
  }
  case object AuditCompliant extends ReportType {
    val severity = "Compliant"
  }
  case object AuditNonCompliant extends ReportType{
    val severity = "NonCompliant"
  }
  case object AuditError extends ReportType{
    val severity = "AuditError"
  }
  case object BadPolicyMode extends ReportType{
    val severity = "BadPolicyMode"
  }
  case object Unexpected extends ReportType{
    val severity = "Unexpected"
  }
  case object NoAnswer extends ReportType{
    val severity = "NoAnswer"
  }
  case object Disabled extends ReportType{
    val severity = "ReportsDisabled"
  }
  case object Pending extends ReportType{
    val severity = "Applying"
  }
  case object Missing extends ReportType{
    val severity = "Missing"
  }

  def getWorseType(reportTypes : Iterable[ReportType]) : ReportType = {
    if (reportTypes.isEmpty) {
      NoAnswer
    } else {
      ( reportTypes :\ (EnforceNotApplicable : ReportType) ) {
        case (_, BadPolicyMode)       | (BadPolicyMode, _)        => BadPolicyMode
        case (_, Unexpected)          | (Unexpected, _)           => Unexpected
        case (_, EnforceError)        | (EnforceError, _)         => EnforceError
        case (_, AuditError)          | (AuditError, _)           => AuditError
        case (_, AuditNonCompliant)   | (AuditNonCompliant, _)    => AuditNonCompliant
        case (_, EnforceRepaired)     | (EnforceRepaired, _)      => EnforceRepaired
        case (_, Missing)             | (Missing, _)              => Missing
        case (_, NoAnswer)            | (NoAnswer, _)             => NoAnswer
        case (_, Disabled)            | (Disabled, _)             => Disabled
        case (_, Pending)             | (Pending, _)              => Pending
        case (_, EnforceSuccess)      | (EnforceSuccess, _)       => EnforceSuccess
        case (_, AuditCompliant)      | (AuditCompliant, _)       => AuditCompliant
        case (_, AuditNotApplicable)  | (AuditNotApplicable, _)   => AuditNotApplicable
        case (_, EnforceNotApplicable)| (EnforceNotApplicable, _) => EnforceNotApplicable
        case _ => Unexpected
      }
    }
  }

  def apply(report : Reports, policyMode: PolicyMode) : ReportType = {
    import PolicyMode._
    (report, policyMode) match {
      case (_ : AuditReports             , Enforce) => BadPolicyMode
      case (_ : EnforceReports           , Audit  ) => BadPolicyMode
      case (_ : ResultErrorReport        , _      ) => EnforceError
      case (_ : ResultRepairedReport     , _      ) => EnforceRepaired
      case (_ : ResultNotApplicableReport, _      ) => EnforceNotApplicable
      case (_ : ResultSuccessReport      , _      ) => EnforceSuccess
      case (_ : AuditErrorReport         , _      ) => AuditError
      case (_ : AuditCompliantReport     , _      ) => AuditCompliant
      case (_ : AuditNotApplicableReport , _      ) => AuditNotApplicable
      case (_ : AuditNonCompliantReport  , _      ) => AuditNonCompliant
      case (_                            ,_       ) => Unexpected
    }
  }
}

/**
 * Case class to store the previous (last received) configid  and the expected NodeConfigId in the Pending case
 */
final case class PreviousAndExpectedNodeConfigId(previous: NodeAndConfigId, expected: NodeAndConfigId)
