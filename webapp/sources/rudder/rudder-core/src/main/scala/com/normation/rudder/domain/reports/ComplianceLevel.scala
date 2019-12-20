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

import net.liftweb.http.js.JE
import net.liftweb.http.js.JE.JsArray
import net.liftweb.json.JsonAST.JObject
import net.liftweb.json.JsonAST.JValue
import net.liftweb.json.JsonAST.JInt

/**
 * That file define a "compliance level" object, which store
 * all the kind of reports we can get and compute percentage
 * on them.
 *
 * This file also define simple addition on such compliance level.
 */


//simple summary that only store percents - no intelligence at all
//here, see ComplianceLevel to understand how percent are calculated.
final case class CompliancePercent(
    pending           : Double = 0
  , success           : Double = 0
  , repaired          : Double = 0
  , error             : Double = 0
  , unexpected        : Double = 0
  , missing           : Double = 0
  , noAnswer          : Double = 0
  , notApplicable     : Double = 0
  , reportsDisabled   : Double = 0
  , compliant         : Double = 0
  , auditNotApplicable: Double = 0
  , nonCompliant      : Double = 0
  , auditError        : Double = 0
  , badPolicyMode     : Double = 0
)


//simple data structure to hold percentages of different compliance
//the ints are actual numbers, percents are computed with the pc_ variants
final case class ComplianceLevel(
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
  import ComplianceLevel.pc_for
  override def toString() = s"[p:${pending} s:${success} r:${repaired} e:${error} u:${unexpected} m:${missing} nr:${noAnswer} na:${notApplicable
                              } rd:${reportsDisabled} c:${compliant} ana:${auditNotApplicable} nc:${nonCompliant} ae:${auditError} bpm:${badPolicyMode}]"

  lazy val total = pending+success+repaired+error+unexpected+missing+noAnswer+notApplicable+reportsDisabled+compliant+auditNotApplicable+nonCompliant+auditError+badPolicyMode

  lazy val total_ok = success+repaired+notApplicable+compliant+auditNotApplicable
  lazy val complianceWithoutPending = pc_for(total_ok, total-pending-reportsDisabled)
  lazy val compliance = pc_for(total_ok, total)

  lazy val pc = CompliancePercent(
      pc_for(pending           , total)
    , pc_for(success           , total)
    , pc_for(repaired          , total)
    , pc_for(error             , total)
    , pc_for(unexpected        , total)
    , pc_for(missing           , total)
    , pc_for(noAnswer          , total)
    , pc_for(notApplicable     , total)
    , pc_for(reportsDisabled   , total)
    , pc_for(compliant         , total)
    , pc_for(auditNotApplicable, total)
    , pc_for(nonCompliant      , total)
    , pc_for(auditError        , total)
    , pc_for(badPolicyMode     , total)
  )

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
  private def pc_for(i:Int, total:Int) : Double = if(total == 0) 0 else (i * 100 / BigDecimal(total)).setScale(2, BigDecimal.RoundingMode.HALF_UP).toDouble


  def compute2(reports: Iterable[ReportType]): ComplianceLevel = {
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

  def compute(reports: Iterable[ReportType]): ComplianceLevel = {
    import ReportType._
    if(reports.isEmpty) {
      ComplianceLevel(notApplicable = 1)
    } else {
      var notApplicable = 0
      var success = 0
      var repaired = 0
      var error = 0
      var unexpected = 0
      var missing = 0
      var noAnswer = 0
      var pending = 0
      var reportsDisabled = 0
      var compliant = 0
      var auditNotApplicable = 0
      var nonCompliant = 0
      var auditError = 0
      var badPolicyMode = 0

      reports.foreach { report =>
        report match {
          case EnforceNotApplicable => notApplicable += 1
          case EnforceSuccess => success += 1
          case EnforceRepaired => repaired += 1
          case EnforceError => error += 1
          case Unexpected => unexpected += 1
          case Missing => missing += 1
          case NoAnswer => noAnswer += 1
          case Pending => pending += 1
          case Disabled => reportsDisabled += 1
          case AuditCompliant => compliant += 1
          case AuditNotApplicable => auditNotApplicable += 1
          case AuditNonCompliant => nonCompliant += 1
          case AuditError => auditError += 1
          case BadPolicyMode => badPolicyMode += 1
        }
      }
      ComplianceLevel(
        pending = pending
      , success = success
      , repaired = repaired
      , error =error
      , unexpected =unexpected
      , missing = missing
      , noAnswer=noAnswer
      , notApplicable=notApplicable
      , reportsDisabled=reportsDisabled
      , compliant  = compliant
      , auditNotApplicable= auditNotApplicable
      , nonCompliant  =nonCompliant
      , auditError = auditError
      , badPolicyMode =badPolicyMode
      )
    }
  }

 def sum2(compliances: Iterable[ComplianceLevel]): ComplianceLevel = {
   if(compliances.isEmpty) ComplianceLevel()
   else compliances.reduce( _ + _)
 }

  def sum(compliances: Iterable[ComplianceLevel]): ComplianceLevel = {
    if (compliances.isEmpty) {
      ComplianceLevel()
    } else {
      var pending: Int = 0
      var success: Int = 0
      var repaired: Int = 0
      var error: Int = 0
      var unexpected: Int = 0
      var missing: Int = 0
      var noAnswer: Int = 0
      var notApplicable: Int = 0
      var reportsDisabled: Int = 0
      var compliant: Int = 0
      var auditNotApplicable: Int = 0
      var nonCompliant: Int = 0
      var auditError: Int = 0
      var badPolicyMode: Int = 0


      compliances.foreach { compliance =>
        pending += compliance.pending
        success += compliance.success
        repaired += compliance.repaired
        error += compliance.error
        unexpected += compliance.unexpected
        missing += compliance.missing
        noAnswer += compliance.noAnswer
        notApplicable += compliance.notApplicable
        reportsDisabled += compliance.reportsDisabled
        compliant += compliance.compliant
        auditNotApplicable += compliance.auditNotApplicable
        nonCompliant += compliance.nonCompliant
        auditError += compliance.auditError
        badPolicyMode += compliance.badPolicyMode
      }
      ComplianceLevel(
        pending = pending
        , success = success
        , repaired = repaired
        , error = error
        , unexpected = unexpected
        , missing = missing
        , noAnswer = noAnswer
        , notApplicable = notApplicable
        , reportsDisabled = reportsDisabled
        , compliant = compliant
        , auditNotApplicable = auditNotApplicable
        , nonCompliant = nonCompliant
        , auditError = auditError
        , badPolicyMode = badPolicyMode
      )
    }
  }
}


object ComplianceLevelSerialisation {
  import net.liftweb.json.JsonDSL._

  //utility class to alway have the same names in JSON,
  //even if we are refactoring ComplianceLevel at some point
  //also remove 0
  private def toJObject(
      pending           : Number
    , success           : Number
    , repaired          : Number
    , error             : Number
    , unexpected        : Number
    , missing           : Number
    , noAnswer          : Number
    , notApplicable     : Number
    , reportsDisabled   : Number
    , compliant         : Number
    , auditNotApplicable: Number
    , nonCompliant      : Number
    , auditError        : Number
    , badPolicyMode     : Number
  ) = {
    def POS(n: Number) = if(n.doubleValue <= 0) None else Some(JE.Num(n))

    (
        ( "pending"           -> POS(pending            ) )
      ~ ( "success"           -> POS(success            ) )
      ~ ( "repaired"          -> POS(repaired           ) )
      ~ ( "error"             -> POS(error              ) )
      ~ ( "unexpected"        -> POS(unexpected         ) )
      ~ ( "missing"           -> POS(missing            ) )
      ~ ( "noAnswer"          -> POS(noAnswer           ) )
      ~ ( "notApplicable"     -> POS(notApplicable      ) )
      ~ ( "reportsDisabled"   -> POS(reportsDisabled    ) )
      ~ ( "compliant"         -> POS(compliant          ) )
      ~ ( "auditNotApplicable"-> POS(auditNotApplicable ) )
      ~ ( "nonCompliant"      -> POS(nonCompliant       ) )
      ~ ( "auditError"        -> POS(auditError         ) )
      ~ ( "badPolicyMode"     -> POS(badPolicyMode      ) )
    )
  }

  private[this] def parse[T](json: JValue, convert: BigInt => T) = {
    def N(n:JValue): T = convert(n match {
      case JInt(i) => i
      case _       => 0
    })

    (
        N(json \ "pending")
      , N(json \ "success")
      , N(json \ "repaired")
      , N(json \ "error")
      , N(json \ "unexpected")
      , N(json \ "missing")
      , N(json \ "noAnswer")
      , N(json \ "notApplicable")
      , N(json \ "reportsDisabled")
      , N(json \ "compliant")
      , N(json \ "auditNotApplicable")
      , N(json \ "nonCompliant")
      , N(json \ "auditError")
      , N(json \ "badPolicyMode")
    )
  }

  def parseLevel(json: JValue) = {
    (ComplianceLevel.apply _).tupled(parse(json, (i:BigInt) => i.intValue))
  }

  def parsePercent(json: JValue) = {
    (CompliancePercent.apply _).tupled(parse(json, (i:BigInt) => i.doubleValue))
  }

  //transform the compliance percent to a list with a given order:
  // pc_reportDisabled, pc_notapplicable, pc_success, pc_repaired,
  // pc_error, pc_pending, pc_noAnswer, pc_missing, pc_unknown
  implicit class ComplianceLevelToJs(compliance: ComplianceLevel) {
    
    def toJsArray(): JsArray = JsArray (
        JE.JsObj(("number",compliance.reportsDisabled)   , ("percent",JE.Num(compliance.pc.reportsDisabled)))    //  0
      , JE.JsObj(("number",compliance.notApplicable)     , ("percent",JE.Num(compliance.pc.notApplicable)))      //  1
      , JE.JsObj(("number",compliance.success)           , ("percent",JE.Num(compliance.pc.success)))            //  2
      , JE.JsObj(("number",compliance.repaired)          , ("percent",JE.Num(compliance.pc.repaired)))           //  3
      , JE.JsObj(("number",compliance.error)             , ("percent",JE.Num(compliance.pc.error)))              //  4
      , JE.JsObj(("number",compliance.pending)           , ("percent",JE.Num(compliance.pc.pending)))            //  5
      , JE.JsObj(("number",compliance.noAnswer)          , ("percent",JE.Num(compliance.pc.noAnswer)))           //  6
      , JE.JsObj(("number",compliance.missing)           , ("percent",JE.Num(compliance.pc.missing)))            //  7
      , JE.JsObj(("number",compliance.unexpected)        , ("percent",JE.Num(compliance.pc.unexpected)))         //  8
      , JE.JsObj(("number",compliance.auditNotApplicable), ("percent",JE.Num(compliance.pc.auditNotApplicable))) //  9
      , JE.JsObj(("number",compliance.compliant)         , ("percent",JE.Num(compliance.pc.compliant)))          // 10
      , JE.JsObj(("number",compliance.nonCompliant)      , ("percent",JE.Num(compliance.pc.nonCompliant)))       // 11
      , JE.JsObj(("number",compliance.auditError)        , ("percent",JE.Num(compliance.pc.auditError)))         // 12
      , JE.JsObj(("number",compliance.badPolicyMode)     , ("percent",JE.Num(compliance.pc.badPolicyMode)))      // 13
    )

    def toJson(): JObject = {
      import compliance._
      toJObject(pending, success, repaired, error, unexpected, missing, noAnswer
                 , notApplicable, reportsDisabled, compliant, auditNotApplicable
                 , nonCompliant, auditError, badPolicyMode
               )
    }
  }

  // transform a compliace percent to JSON.
  // here, we are using attributes contrary to compliance level,
  // and we only keep the one > 0 (we want the result to be
  // human-readable and to aknolewdge the fact that there may be
  // new fields.
  implicit class CompliancePercentToJs(c: CompliancePercent) {
    def toJson(): JObject = {
      import c._
      toJObject(pending, success, repaired, error, unexpected, missing, noAnswer
                 , notApplicable, reportsDisabled, compliant, auditNotApplicable
                 , nonCompliant, auditError, badPolicyMode
               )
    }
  }

}
