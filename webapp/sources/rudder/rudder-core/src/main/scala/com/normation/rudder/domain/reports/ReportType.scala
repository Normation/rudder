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
import enumeratum.Enum
import enumeratum.EnumEntry

/**
 * Kind of reports that we can get as result of
 * a merge/compare with expected reports.
 */
sealed trait ReportType extends EnumEntry {

  def severity: String

  // a "worsiness" level for report type relative order.
  // A bigger number means "worse than other", as in "'error' is worse than 'repaired'"
  // level is a positive integer starting at zero.
  def level: Int
}

object ReportType extends Enum[ReportType] {
  // report type are declared sorted in level to ease maintenance

  case object EnforceNotApplicable extends ReportType { val level = 0; val severity = "NotApplicable"      }
  case object AuditNotApplicable   extends ReportType { val level = 1; val severity = "AuditNotApplicable" }
  case object AuditCompliant       extends ReportType { val level = 2; val severity = "Compliant"          }
  case object EnforceSuccess       extends ReportType { val level = 3; val severity = "Success"            }
  case object Pending              extends ReportType { val level = 4; val severity = "Applying"           }
  case object Disabled             extends ReportType { val level = 5; val severity = "ReportsDisabled"    }
  case object NoAnswer             extends ReportType { val level = 6; val severity = "NoAnswer"           }
  case object Missing              extends ReportType { val level = 7; val severity = "Missing"            }
  case object EnforceRepaired      extends ReportType { val level = 8; val severity = "Repaired"           }
  case object AuditNonCompliant    extends ReportType { val level = 9; val severity = "NonCompliant"       }
  case object AuditError           extends ReportType { val level = 10; val severity = "AuditError"        }
  case object EnforceError         extends ReportType { val level = 11; val severity = "Error"             }
  case object Unexpected           extends ReportType { val level = 12; val severity = "Unexpected"        }
  case object BadPolicyMode        extends ReportType { val level = 13; val severity = "BadPolicyMode"     }

  def getWorseType(reportTypes: Iterable[ReportType]): ReportType = {
    if (reportTypes.isEmpty) {
      NoAnswer
    } else {
      reportTypes.maxBy(_.level)
    }
  }

  def apply(report: Reports, policyMode: PolicyMode): ReportType = {
    (report, policyMode) match {
      case (_: ResultErrorReport, _)         => EnforceError
      case (_: ResultRepairedReport, _)      => EnforceRepaired
      case (_: ResultNotApplicableReport, _) => EnforceNotApplicable
      case (_: ResultSuccessReport, _)       => EnforceSuccess
      case (_: AuditErrorReport, _)          => AuditError
      case (_: AuditCompliantReport, _)      => AuditCompliant
      case (_: AuditNotApplicableReport, _)  => AuditNotApplicable
      case (_: AuditNonCompliantReport, _)   => AuditNonCompliant
      case (_, _)                            => Unexpected
    }
  }

  override def values: IndexedSeq[ReportType] = findValues
}

/**
 * Case class to store the previous (last received) configid  and the expected NodeConfigId in the Pending case
 */
final case class PreviousAndExpectedNodeConfigId(previous: NodeAndConfigId, expected: NodeAndConfigId)
