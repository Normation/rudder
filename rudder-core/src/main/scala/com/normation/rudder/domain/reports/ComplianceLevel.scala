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
import com.normation.rudder.reports.ReportsDisabled
import net.liftweb.http.js.JE
import net.liftweb.http.js.JE.JsArray

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
    pending           : Int = 0
  , success           : Int = 0
  , repaired          : Int = 0
  , error             : Int = 0
  , unexpected        : Int = 0
  , missing           : Int = 0
  , noAnswer          : Int = 0
  , notApplicable     : Int = 0
  , reportsDisabled   : Int = 0
  , compliant         : Int = 0
  , auditNotApplicable: Int = 0
  , nonCompliant      : Int = 0
  , auditError        : Int = 0
  , badPolicyMode     : Int = 0
) {

  override def toString() = s"[p:${pending} s:${success} r:${repaired} e:${error} u:${unexpected} m:${missing} nr:${noAnswer} na:${notApplicable
                              } rd:${reportsDisabled} c:${compliant} ana:${auditNotApplicable} nc:${nonCompliant} ae:${auditError} bpm:${badPolicyMode}]"

  lazy val total = pending+success+repaired+error+unexpected+missing+noAnswer+notApplicable+reportsDisabled+compliant+auditNotApplicable+nonCompliant+auditError+badPolicyMode

  lazy val total_ok = success+repaired+notApplicable+compliant+auditNotApplicable
  lazy val complianceWithoutPending = pc_for(total_ok, total-pending-reportsDisabled)
  lazy val compliance = pc_for(total_ok, total)

  private[this] def pc_for(i:Int, total:Int) : Double = if(total == 0) 0 else (i * 100 / BigDecimal(total)).setScale(2, BigDecimal.RoundingMode.HALF_UP).toDouble
  private[this] def pc(i:Int) : Double = pc_for(i, total)

  lazy val pc_pending             = pc(pending)
  lazy val pc_success             = pc(success)
  lazy val pc_repaired            = pc(repaired)
  lazy val pc_error               = pc(error)
  lazy val pc_unexpected          = pc(unexpected)
  lazy val pc_missing             = pc(missing)
  lazy val pc_reportsDisabled     = pc(reportsDisabled)
  lazy val pc_noAnswer            = pc(noAnswer)
  lazy val pc_notApplicable       = pc(notApplicable)
  lazy val pc_compliant           = pc(compliant)
  lazy val pc_auditNotApplicable  = pc(auditNotApplicable)
  lazy val pc_nonCompliant        = pc(nonCompliant)
  lazy val pc_auditError          = pc(auditError)
  lazy val pc_badPolicyMode       = pc(badPolicyMode)

  def +(compliance: ComplianceLevel): ComplianceLevel = {
    ComplianceLevel(
        pending            = this.pending + compliance.pending
      , success            = this.success + compliance.success
      , repaired           = this.repaired + compliance.repaired
      , error              = this.error + compliance.error
      , unexpected         = this.unexpected + compliance.unexpected
      , missing            = this.missing + compliance.missing
      , noAnswer           = this.noAnswer + compliance.noAnswer
      , notApplicable      = this.notApplicable + compliance.notApplicable
      , reportsDisabled    = this.reportsDisabled + compliance.reportsDisabled
      , compliant          = this.compliant + compliance.compliant
      , auditNotApplicable = this.auditNotApplicable + compliance.auditNotApplicable
      , nonCompliant       = this.nonCompliant + compliance.nonCompliant
      , auditError         = this.auditError + compliance.auditError
      , badPolicyMode      = this.badPolicyMode + compliance.badPolicyMode
    )
  }

  def +(report: ReportType): ComplianceLevel = this+ComplianceLevel.compute(Seq(report))
  def +(reports: Iterable[ReportType]): ComplianceLevel = this+ComplianceLevel.compute(reports)
}

object ComplianceLevel {
  def compute(reports: Iterable[ReportType]): ComplianceLevel = {
    import ReportType._
    if(reports.isEmpty) { ComplianceLevel(notApplicable = 1)}
    else reports.foldLeft(ComplianceLevel()) { case (compliance, report) =>
      report match {
        case EnforceNotApplicable => compliance.copy(notApplicable = compliance.notApplicable + 1)
        case EnforceSuccess       => compliance.copy(success = compliance.success + 1)
        case EnforceRepaired      => compliance.copy(repaired = compliance.repaired + 1)
        case EnforceError         => compliance.copy(error = compliance.error + 1)
        case Unexpected           => compliance.copy(unexpected = compliance.unexpected + 1)
        case Missing              => compliance.copy(missing = compliance.missing + 1)
        case NoAnswer             => compliance.copy(noAnswer = compliance.noAnswer + 1)
        case Pending              => compliance.copy(pending = compliance.pending + 1)
        case Disabled             => compliance.copy(reportsDisabled = compliance.reportsDisabled + 1)
        case AuditCompliant       => compliance.copy(compliant = compliance.compliant + 1)
        case AuditNotApplicable   => compliance.copy(auditNotApplicable = compliance.auditNotApplicable + 1)
        case AuditNonCompliant    => compliance.copy(nonCompliant = compliance.nonCompliant + 1)
        case AuditError           => compliance.copy(auditError = compliance.auditError + 1)
        case BadPolicyMode        => compliance.copy(badPolicyMode = compliance.badPolicyMode + 1)
      }
    }
  }

 def sum(compliances: Iterable[ComplianceLevel]): ComplianceLevel = {
   if(compliances.isEmpty) ComplianceLevel()
   else compliances.reduce( _ + _)
 }
}


object ComplianceLevelSerialisation {

  //transform the compliance percent to a list with a given order:
  // pc_reportDisabled, pc_notapplicable, pc_success, pc_repaired,
  // pc_error, pc_pending, pc_noAnswer, pc_missing, pc_unknown
  implicit class ComplianceLevelToJs(compliance: ComplianceLevel) {
    def toJsArray(): JsArray = JsArray (
        JE.Num(compliance.pc_reportsDisabled)     //  0
      , JE.Num(compliance.pc_notApplicable)       //  1
      , JE.Num(compliance.pc_success)             //  2
      , JE.Num(compliance.pc_repaired)            //  3
      , JE.Num(compliance.pc_error)               //  4
      , JE.Num(compliance.pc_pending)             //  5
      , JE.Num(compliance.pc_noAnswer)            //  6
      , JE.Num(compliance.pc_missing)             //  7
      , JE.Num(compliance.pc_unexpected)          //  8
      , JE.Num(compliance.pc_auditNotApplicable)  //  9
      , JE.Num(compliance.pc_compliant)           // 10
      , JE.Num(compliance.pc_nonCompliant)        // 11
      , JE.Num(compliance.pc_auditError)          // 12
      , JE.Num(compliance.pc_badPolicyMode)       // 13
    )
  }
}
