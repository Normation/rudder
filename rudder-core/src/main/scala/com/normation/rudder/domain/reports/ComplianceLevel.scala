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
 * That file define a "compliance level" object, which store
 * all the kind of reports we can get and compute percentage
 * on them.
 *
 * This file also define simple addition on such compliance level.
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

  val compliance = pc(success+repaired+notApplicable)

  private[this] def pc(i:Int) : Float = if(total == 0) 0 else (i * 100 / BigDecimal(total)).setScale(2, BigDecimal.RoundingMode.HALF_UP).toFloat

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
    if(reports.isEmpty) { ComplianceLevel(notApplicable = 1)}
    else reports.foldLeft(ComplianceLevel()) { case (compliance, report) =>
      report match {
        case NotApplicableReportType => compliance.copy(notApplicable = compliance.notApplicable + 1)
        case SuccessReportType       => compliance.copy(success = compliance.success + 1)
        case RepairedReportType      => compliance.copy(repaired = compliance.repaired + 1)
        case ErrorReportType         => compliance.copy(error = compliance.error + 1)
        case UnexpectedReportType    => compliance.copy(unexpected = compliance.unexpected + 1)
        case MissingReportType       => compliance.copy(missing = compliance.missing + 1)
        case NoAnswerReportType      => compliance.copy(noAnswer = compliance.noAnswer + 1)
        case PendingReportType       => compliance.copy(pending = compliance.pending + 1)
      }
    }
  }

 def sum(compliances: Iterable[ComplianceLevel]): ComplianceLevel = {
   if(compliances.isEmpty) ComplianceLevel()
   else compliances.reduce( _ + _)
 }
}
