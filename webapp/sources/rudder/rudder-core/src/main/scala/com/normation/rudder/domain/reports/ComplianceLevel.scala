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

import com.normation.rudder.domain.logger.ComplianceLogger
import com.normation.rudder.domain.reports.ComplianceLevel.PERCENT_PRECISION

import net.liftweb.http.js.JE
import net.liftweb.http.js.JE.JsArray
import net.liftweb.json.JsonAST.JObject
import net.liftweb.json.JsonAST.JValue
import net.liftweb.json.JsonAST.JInt

/**
 * That file define a "compliance level" object, which store all the kind of reports we can get and
 * compute percentage on them.
 *
 * Percent are stored a double from 0 to 100 with two relevant digits so 12.34% is actually stored
 * as Double(12.34) (not 0.1234)
 *
 * Since use only two digits in percent, we only have a 10e-4 precision, but we nonetheless NEVER EVER
 * want to make 1 error among 20000 success reports disapear by being rounded to 0.
 * So we accept that we have a minimum for all percent, `MIN_PC`, and whatever the real number, it
 * will be returned as that minimum.
 *
 * For precise computation, you of course MUST use the level numbers, not the percent.
 * It also mean that any transformation or computation on compliance must alway be done on level, never
 * compliance percent, which are just a model for human convenience, but is false.
 *
 * This class should always be instanciated with  CompliancePercent.fromLevels` to ensure sum is 100%
 * The rounding algorithm is:
 * - sort levels by number of reports, less first, sum them to get total number of reports
 * - for each level except the last one (most number):
 *   - if there is 0 reports for that level, it's 0
 *   - if the percent is LESS THAN `MIN_PC`, then it's MN
 *   - else compute percent rounded to two digits
 * - the last level is computed by: 100 - sum(other percent)
 *
 * In case of equality, `ReportType.level` is used for comparison.
 */
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
)(val precision: Int = 0) {
  val compliance = success+repaired+notApplicable+compliant+auditNotApplicable
}

object CompliancePercent {

  // a correspondance array between worse order in `ReportType` and the order of fields in `ComplianceLevel`
  val WORSE_ORDER = {
    import ReportType._
    Array(Pending, EnforceSuccess, EnforceRepaired, EnforceError, Unexpected, Missing, NoAnswer, EnforceNotApplicable
        , Disabled, AuditCompliant, AuditNotApplicable, AuditNonCompliant, AuditError, BadPolicyMode).map(_.level)
  }
  { // maintenance sanity check between dimension
    List(ComplianceLevel(), CompliancePercent()()).foreach  { inst =>
      // product arity only compare first arg list
      if(inst.productArity != WORSE_ORDER.length) {
        throw new IllegalArgumentException(s"Inconsistency in ${inst.getClass.getSimpleName} code (checking consistency of" +
                                           s" fields for WORSE_ORDER). Please report to a developer, this is a coding error")
      }
    }
  }


  /*
   * Ensure that the sum is 100% and that no case disapear because it is
   * less that 0.01%.
   *
   * Rules are:
   * - always keeps at least 0.01% for any case
   *   (given that we have 14 categories, it means that at worst, if 13 are at 0.1 in place of ~0%;
   *   the last one will be false by 0.13%, which is ok)
   * - the biggest value is rounded to compensate
   * - in case of equality (ex: 1/3 success, 1/3 NA, 1/3 error), the biggest is
   *   chosen accordingly to "ReportType.level" logic.
   */
  def fromLevels(c: ComplianceLevel, precision: Int): CompliancePercent = {
    // the value for the minimum percent reported even if less (see class description)
    val MIN_PC = BigDecimal(1) * BigDecimal(10).pow(- precision) // since we keep precision digits in percents

    if(c.total == 0) {  // special case: let it be 0
      CompliancePercent()(precision)
    } else {
      val total = c.total // not zero
      def pc_for(i:Int) : Double = {
        if(i == 0) {
          0
        } else {
          val pc = (i * 100 / BigDecimal(total)).setScale(precision, BigDecimal.RoundingMode.HALF_UP)
          (if(pc < MIN_PC) MIN_PC else pc).toDouble
        }
      }

      // default percent calculation
      val levels = CompliancePercent.sortLevels(c)
      // round the first ones keeping at least MIN_PC
      val pc = levels.init.map { case (l, index) => (pc_for(l), index) }
      // the last one is rounded by "100 - sum of other"
      val pc_last = 100 - pc.map(_._1).sum

      // check that the rounding error is below what is expected (0.15%) else warn
      val error = Math.abs((pc_for(levels.last._1)-pc_last)/pc_last)
      if( error > (levels.size+1) * MIN_PC) {
        ComplianceLogger.info(s"Rounding error in compliances is above expected threshold: error is ${error} for ${c}")
      }

      // sort back percents by index
      val all = ((pc_last, levels.last._2) :: pc).sortBy(_._2).map(_._1)

      // create array of pecents
      CompliancePercent.fromSeq(all, precision)
    }
  }

  /*
   * returned a list of (level, index) where index correspond to the index of the
   * level in the compliance array. The list is sorted in the relevant order so that
   * the smaller level are at head, and in case of equality, the "worse order" order is
   * kept (worse order last)
   */
  def sortLevels(c: ComplianceLevel): List[(Int, Int)] = {
    // we want to compare accordingly to `ReportType.getWorsteType` but I don't see any
    // way to do it directly since we don't use the same order in compliance.
    // So we map index of a compliance element to it's worse type order and compare by index

    val levels = List(
        c.pending
      , c.success
      , c.repaired
      , c.error
      , c.unexpected
      , c.missing
      , c.noAnswer
      , c.notApplicable
      , c.reportsDisabled
      , c.compliant
      , c.auditNotApplicable
      , c.nonCompliant
      , c.auditError
      , c.badPolicyMode
    ).zipWithIndex

    // sort smallest first, and then by worst type
    levels.sortWith { case ((l1, i1), (l2, i2)) =>
      if(l1 == l2)  {
        // index in WORSE_ORDER is the same as index in levels, so just compare WORSE_INDEX(index)
        WORSE_ORDER(i1) < WORSE_ORDER(i2)
      } else {
        l1 < l2
      }
    }

  }

  /**
   *  Init compliance percent from a Seq. Order is of course extremely important here.
   *  This is a dangerous internal only method: if the seq is too short or too big, throw an error.
   */
  protected def fromSeq(pc: Seq[Double], precision: Int) = {
    val expected = WORSE_ORDER.length
    if(pc.length != expected) {
      throw new IllegalArgumentException(s"We are trying to build a compliance bar from a sequence of double that has" +
                                         s" not the expected length of ${expected}, this is a code bug, please report it: + ${pc}")
    } else {
      CompliancePercent(pc(0), pc(1), pc(2), pc(3), pc(4), pc(5), pc(6), pc(7), pc(8), pc(9), pc(10), pc(11), pc(12), pc(13))(precision)
    }
  }
}


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

  override def toString() = s"[p:${pending} s:${success} r:${repaired} e:${error} u:${unexpected} m:${missing} nr:${noAnswer} na:${notApplicable
                              } rd:${reportsDisabled} c:${compliant} ana:${auditNotApplicable} nc:${nonCompliant} ae:${auditError} bpm:${badPolicyMode}]"

  lazy val total = pending+success+repaired+error+unexpected+missing+noAnswer+notApplicable+reportsDisabled+compliant+auditNotApplicable+nonCompliant+auditError+badPolicyMode
  lazy val total_ok = success+repaired+notApplicable+compliant+auditNotApplicable

  def withoutPending = this.copy(pending = 0, reportsDisabled = 0)
  def computePercent(precision: Int = PERCENT_PRECISION) = CompliancePercent.fromLevels(this, precision)

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

  def PERCENT_PRECISION = 2

  def compute(reports: Iterable[ReportType]): ComplianceLevel = {
    import ReportType._
    if(reports.isEmpty) {
      ComplianceLevel(notApplicable = 1)
    } else {
      var pending = 0
      var success = 0
      var repaired = 0
      var error = 0
      var unexpected = 0
      var missing = 0
      var noAnswer = 0
      var notApplicable = 0
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
        , error = error
        , unexpected = unexpected
        , missing = missing
        , noAnswer = noAnswer
        , notApplicable = notApplicable
        , reportsDisabled = reportsDisabled
        , compliant  = compliant
        , auditNotApplicable = auditNotApplicable
        , nonCompliant = nonCompliant
        , auditError = auditError
        , badPolicyMode = badPolicyMode
      )
    }
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

  //transform the compliance percent to a list with a given order:
  // pc_reportDisabled, pc_notapplicable, pc_success, pc_repaired,
  // pc_error, pc_pending, pc_noAnswer, pc_missing, pc_unknown
  implicit class ComplianceLevelToJs(val compliance: ComplianceLevel) extends AnyVal {

    def toJsArray: JsArray = {
      val pc = compliance.computePercent()
      JsArray (
        JsArray(compliance.reportsDisabled,JE.Num(pc.reportsDisabled))    //  0
        , JsArray(compliance.notApplicable , JE.Num(pc.notApplicable))      //  1
        , JsArray(compliance.success , JE.Num(pc.success))            //  2
        , JsArray(compliance.repaired , JE.Num(pc.repaired))           //  3
        , JsArray(compliance.error , JE.Num(pc.error))              //  4
        , JsArray(compliance.pending , JE.Num(pc.pending))            //  5
        , JsArray(compliance.noAnswer , JE.Num(pc.noAnswer))           //  6
        , JsArray(compliance.missing , JE.Num(pc.missing))            //  7
        , JsArray(compliance.unexpected , JE.Num(pc.unexpected))         //  8
        , JsArray(compliance.auditNotApplicable , JE.Num(pc.auditNotApplicable)) //  9
        , JsArray(compliance.compliant , JE.Num(pc.compliant))          // 10
        , JsArray(compliance.nonCompliant , JE.Num(pc.nonCompliant))       // 11
        , JsArray(compliance.auditError , JE.Num(pc.auditError))         // 12
        , JsArray(compliance.badPolicyMode , JE.Num(pc.badPolicyMode))      // 13
      )
    }

    def toJson: JObject = {
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
  implicit class CompliancePercentToJs(val c: CompliancePercent) extends AnyVal {
    def toJson: JObject = {
      import c._
      toJObject(pending, success, repaired, error, unexpected, missing, noAnswer
                 , notApplicable, reportsDisabled, compliant, auditNotApplicable
                 , nonCompliant, auditError, badPolicyMode
               )
    }
  }

}
