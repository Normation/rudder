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

import com.normation.rudder.domain.reports.ComplianceLevel.PERCENT_PRECISION
import com.normation.rudder.domain.reports.CompliancePrecision.Level0
import com.normation.rudder.domain.reports.CompliancePrecision.Level2
import io.scalaland.chimney.*
import net.liftweb.common.*
import zio.Chunk
import zio.json.*
import zio.json.ast.*
import zio.json.ast.Json.*

/**
 * That file defines a "compliance level" object, which store all the kind of reports we can get and
 * compute percentage on them.
 *
 * Percent are stored a double from 0 to 100 with two relevant digits so 12.34% is actually stored
 * as Double(12.34) (not 0.1234)
 *
 * Since use only two digits in percent, we only have a 10e-4 precision, but we nonetheless NEVER EVER
 * want to make 1 error among 20000 success reports disappear by being rounded to 0.
 * So we accept that we have a minimum for all percent, `MIN_PC`, and whatever the real number, it
 * will be returned as that minimum.
 *
 * For precise computation, you of course MUST use the level numbers, not the percent.
 * It also mean that any transformation or computation on compliance must always be done on level, never
 * compliance percent, which are just a model for human convenience, but is false.
 *
 * This class should always be instantiated with CompliancePercent.fromLevels` to ensure sum is 100%
 * The rounding algorithm is:
 * - sort levels by number of reports, less first, sum them to get total number of reports
 * - for each level except the last one (most number):
 *   - if there is 0 reports for that level, it's 0
 *   - if the percent is LESS THAN `MIN_PC`, then it's MN, and add MIN_PC to the percentage divider
 *   - then compute percent by dividing with the percentage divider
 * - the last level is computed by: 100 - sum(other percent)
 *
 * In case of equality, `ReportType.level` is used for comparison.
 */
final case class CompliancePercent(
    pending:            Double = 0,
    success:            Double = 0,
    repaired:           Double = 0,
    error:              Double = 0,
    unexpected:         Double = 0,
    missing:            Double = 0,
    noAnswer:           Double = 0,
    notApplicable:      Double = 0,
    reportsDisabled:    Double = 0,
    compliant:          Double = 0,
    auditNotApplicable: Double = 0,
    nonCompliant:       Double = 0,
    auditError:         Double = 0,
    badPolicyMode:      Double = 0
)(val precision: CompliancePrecision = Level0) {
  val compliance: Double = success + repaired + notApplicable + compliant + auditNotApplicable
}

object CompliancePercent {

  implicit val transformComplianceSerializable: Transformer[CompliancePercent, ComplianceSerializable] = {
    Transformer
      .define[CompliancePercent, ComplianceSerializable]
      .withFieldRenamed(_.pending, _.applying)
      .withFieldRenamed(_.notApplicable, _.successNotApplicable)
      .withFieldRenamed(_.success, _.successAlreadyOK)
      .withFieldRenamed(_.repaired, _.successRepaired)
      .withFieldRenamed(_.compliant, _.auditCompliant)
      .withFieldRenamed(_.nonCompliant, _.auditNonCompliant)
      .withFieldRenamed(_.unexpected, _.unexpectedUnknownComponent)
      .withFieldRenamed(_.missing, _.unexpectedMissingComponent)
      .withFieldRenamed(_.noAnswer, _.noReport)
      .buildTransformer
  }

  // a mapping array between worse order in `ReportType` and the order of fields in `ComplianceLevel`
  val WORSE_ORDER: Array[Int] = {
    import ReportType.*
    Array(
      Pending,
      EnforceSuccess,
      EnforceRepaired,
      EnforceError,
      Unexpected,
      Missing,
      NoAnswer,
      EnforceNotApplicable,
      Disabled,
      AuditCompliant,
      AuditNotApplicable,
      AuditNonCompliant,
      AuditError,
      BadPolicyMode
    ).map(_.level)
  }
  { // maintenance sanity check between dimension
    List(ComplianceLevel(), CompliancePercent()()).foreach { inst =>
      // product arity only compare first arg list
      if (inst.productArity != WORSE_ORDER.length) {
        throw new IllegalArgumentException(
          s"Inconsistency in ${inst.getClass.getSimpleName} code (checking consistency of" +
          s" fields for WORSE_ORDER). Please report to a developer, this is a coding error"
        )
      }
    }
  }

  // hardcoding the precision levels
  val divisers: Seq[Long] = Seq(1, 10, 100, 1000, 10000, 100000)
  val hundreds: Seq[Long] = Seq(100, 1000, 10000, 100000, 1000000, 10000000)

  /*
   * Computes the compliance with precision from 0 to 5 (max)
   * It enforces that:
   * * always keeps at least 10e(-precision)% for any case
   *   (given that we have 14 categories, it means that at worst, if 13 are at the mean in place of ~0%;
   * * the sum sums to 100, by having the largest one computed with 100 - sum of the others
   * * best effort to keep the ordering in percentage
   *   it does that by adding the extra 10e(-precision)% to the total, and computing the percentage
   *   by dividing the remaining percent with this
   * All computation is in Long by multiplying the values with the power of ten
   * and then truncated by converting to double and dividing after by the power of ten
   */
  def fromLevels(c: ComplianceLevel, precision: CompliancePrecision): CompliancePercent = {
    val total = c.total
    if (total == 0) { // special case: let it be 0
      CompliancePercent()(precision)
    } else {
      // these depend on the precision
      val diviser = divisers(precision.precision)
      val hundred = hundreds(precision.precision)

      val levels = CompliancePercent.sortLevels(c)

      // when a value is too small and rounded to 1, we add one percent to extraPercent
      var extraPercent = 1.00
      // computed on the go to avoid mapping & traversing again the list
      var total_pc     = 0L

      @inline
      def pc_for(i: Int): Long = {
        if (i == 0) {
          0
        } else {
          val pc = i.toDouble * hundred / total / extraPercent
          if (pc < 1) {
            extraPercent = extraPercent + 0.01
            total_pc = total_pc + 1
            1
          } else {
            val result = pc.toLong
            total_pc = total_pc + result
            result
          }
        }
      }

      // Compute the percents for the smallest values
      val pc      = levels.init.map { case (l, index) => (pc_for(l), index) }
      val pc_last = hundred - total_pc

      val correct_percent = ((pc_last, levels.last._2) :: pc).sortBy(_._2).map(_._1.toDouble / diviser)
      CompliancePercent.fromSeq(correct_percent, precision)
    }
  }

  // Directly computes the compliance without taking into account the pending level
  // It is made to skip the extra step of creating a new CompliancePercent, and save a bit
  // of perf
  def complianceWithoutPending(c: ComplianceLevel, precision: CompliancePrecision): Double           = {
    val total =
      c.success + c.repaired + c.error + c.unexpected + c.missing + c.noAnswer + c.notApplicable + c.reportsDisabled + c.compliant + c.auditNotApplicable + c.nonCompliant + c.auditError + c.badPolicyMode

    if (total == 0) { // special case: let it be 0
      0
    } else {
      // these depend on the precision
      val diviser = divisers(precision.precision)
      val hundred = hundreds(precision.precision)

      val levels = CompliancePercent.sortLevelsWithoutPending(c)

      // when a value is too small and rounded to 1, we add one percent to extraPercent
      var extraPercent = 1.00
      // computed on the go to avoid mapping & traversing again the list
      var total_pc     = 0L

      @inline
      def pc_for(i: Int): Long = {
        if (i == 0) {
          0
        } else {
          val pc = i.toDouble * hundred / total / extraPercent
          if (pc < 1) {
            extraPercent = extraPercent + 0.01
            total_pc = total_pc + 1
            1
          } else {
            val result = pc.toLong
            total_pc = total_pc + result
            result
          }
        }
      }

      val pc              = levels.init.map { case (l, index) => (pc_for(l), index) }
      //  println(s" pc is $pc")
      val pc_last         = hundred - total_pc
      val correct_percent = ((pc_last, levels.last._2) :: pc).sortBy(_._2).map(_._1.toDouble / diviser)
      // there is no pending, so all numbers are one step to the left
      correct_percent(0) + correct_percent(1) + correct_percent(6) + correct_percent(8) + correct_percent(9)
    }
  }
  /*
   * returned a list of (level, index) where index correspond to the index of the
   * level in the compliance array. The list is sorted in the relevant order so that
   * the smaller level are at head, and in case of equality, the "worse order" order is
   * kept (worse order last)
   */
  def sortLevels(c: ComplianceLevel):                                               List[(Int, Int)] = {
    // we want to compare accordingly to `ReportType.getWorsteType` but I don't see any
    // way to do it directly since we don't use the same order in compliance.
    // So we map index of a compliance element to it's worse type order and compare by index

    val levels = List(
      (c.pending, 0),
      (c.success, 1),
      (c.repaired, 2),
      (c.error, 3),
      (c.unexpected, 4),
      (c.missing, 5),
      (c.noAnswer, 6),
      (c.notApplicable, 7),
      (c.reportsDisabled, 8),
      (c.compliant, 9),
      (c.auditNotApplicable, 10),
      (c.nonCompliant, 11),
      (c.auditError, 12),
      (c.badPolicyMode, 13)
    )

    // sort smallest first, and then by worst type
    levels.sortWith {
      case ((l1, i1), (l2, i2)) =>
        if (l1 == l2) {
          // index in WORSE_ORDER is the same as index in levels, so just compare WORSE_INDEX(index)
          WORSE_ORDER(i1) < WORSE_ORDER(i2)
        } else {
          l1 < l2
        }
    }
  }

  def sortLevelsWithoutPending(c: ComplianceLevel): List[(Int, Int)] = {
    // we want to compare accordingly to `ReportType.getWorstType` but I don't see any
    // way to do it directly since we don't use the same order in compliance.
    // So we map index of a compliance element to it's worse type order and compare by index

    val levels = List(
      (c.success, 1),
      (c.repaired, 2),
      (c.error, 3),
      (c.unexpected, 4),
      (c.missing, 5),
      (c.noAnswer, 6),
      (c.notApplicable, 7),
      (c.reportsDisabled, 8),
      (c.compliant, 9),
      (c.auditNotApplicable, 10),
      (c.nonCompliant, 11),
      (c.auditError, 12),
      (c.badPolicyMode, 13)
    )

    // sort smallest first, and then by worst type
    levels.sortWith {
      case ((l1, i1), (l2, i2)) =>
        if (l1 == l2) {
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
  protected def fromSeq(pc: Seq[Double], precision: CompliancePrecision): CompliancePercent = {
    val expected = WORSE_ORDER.length
    if (pc.length != expected) {
      throw new IllegalArgumentException(
        s"We are trying to build a compliance bar from a sequence of double that has" +
        s" not the expected length of ${expected}, this is a code bug, please report it: + ${pc}"
      )
    } else {
      CompliancePercent(pc(0), pc(1), pc(2), pc(3), pc(4), pc(5), pc(6), pc(7), pc(8), pc(9), pc(10), pc(11), pc(12), pc(13))(
        precision
      )
    }
  }
}

//simple data structure to hold percentages of different compliance
//the ints are actual numbers, percents are computed with the pc_ variants
final case class ComplianceLevel(
    pending:            Int = 0,
    success:            Int = 0,
    repaired:           Int = 0,
    error:              Int = 0,
    unexpected:         Int = 0,
    missing:            Int = 0,
    noAnswer:           Int = 0,
    notApplicable:      Int = 0,
    reportsDisabled:    Int = 0,
    compliant:          Int = 0,
    auditNotApplicable: Int = 0,
    nonCompliant:       Int = 0,
    auditError:         Int = 0,
    badPolicyMode:      Int = 0
) {

  override def toString(): String =
    s"[p:${pending} s:${success} r:${repaired} e:${error} u:${unexpected} m:${missing} nr:${noAnswer} na:${notApplicable} rd:${reportsDisabled} c:${compliant} ana:${auditNotApplicable} nc:${nonCompliant} ae:${auditError} bpm:${badPolicyMode}]"

  lazy val total:    Int =
    pending + success + repaired + error + unexpected + missing + noAnswer + notApplicable + reportsDisabled + compliant + auditNotApplicable + nonCompliant + auditError + badPolicyMode
  lazy val total_ok: Int = success + repaired + notApplicable + compliant + auditNotApplicable

  def withoutPending: ComplianceLevel = this.copy(pending = 0, reportsDisabled = 0)

  def computePercent(precision: CompliancePrecision = PERCENT_PRECISION): CompliancePercent =
    CompliancePercent.fromLevels(this, precision)

  def complianceWithoutPending(precision: CompliancePrecision = PERCENT_PRECISION): Double =
    CompliancePercent.complianceWithoutPending(this, precision)

  def +(compliance: ComplianceLevel): ComplianceLevel = {
    ComplianceLevel(
      pending = this.pending + compliance.pending,
      success = this.success + compliance.success,
      repaired = this.repaired + compliance.repaired,
      error = this.error + compliance.error,
      unexpected = this.unexpected + compliance.unexpected,
      missing = this.missing + compliance.missing,
      noAnswer = this.noAnswer + compliance.noAnswer,
      notApplicable = this.notApplicable + compliance.notApplicable,
      reportsDisabled = this.reportsDisabled + compliance.reportsDisabled,
      compliant = this.compliant + compliance.compliant,
      auditNotApplicable = this.auditNotApplicable + compliance.auditNotApplicable,
      nonCompliant = this.nonCompliant + compliance.nonCompliant,
      auditError = this.auditError + compliance.auditError,
      badPolicyMode = this.badPolicyMode + compliance.badPolicyMode
    )
  }

  def +(report:  ReportType):           ComplianceLevel = this + ComplianceLevel.compute(Seq(report))
  def +(reports: Iterable[ReportType]): ComplianceLevel = this + ComplianceLevel.compute(reports)
}

sealed trait CompliancePrecision {
  def precision: Int
}
object CompliancePrecision       {
  case object Level0 extends CompliancePrecision { val precision = 0 }
  case object Level1 extends CompliancePrecision { val precision = 1 }
  case object Level2 extends CompliancePrecision { val precision = 2 }
  case object Level3 extends CompliancePrecision { val precision = 3 }
  case object Level4 extends CompliancePrecision { val precision = 4 }
  case object Level5 extends CompliancePrecision { val precision = 5 }

  def fromPrecision(i: Int): Box[CompliancePrecision] = {
    i match {
      case Level0.precision => Full(Level0)
      case Level1.precision => Full(Level1)
      case Level2.precision => Full(Level2)
      case Level3.precision => Full(Level3)
      case Level4.precision => Full(Level4)
      case Level5.precision => Full(Level5)
      case _                => Failure(s"Invalid level for compliance precision ${i}, valid values are 0 to 5")
    }
  }
}
object ComplianceLevel           {

  implicit val transformComplianceSerializable: Transformer[ComplianceLevel, ComplianceLevelSerialisation] = {
    Transformer
      .define[ComplianceLevel, ComplianceLevelSerialisation]
      .withFieldRenamed(_.pending, _.applying)
      .withFieldRenamed(_.notApplicable, _.successNotApplicable)
      .withFieldRenamed(_.success, _.successAlreadyOK)
      .withFieldRenamed(_.repaired, _.successRepaired)
      .withFieldRenamed(_.compliant, _.auditCompliant)
      .withFieldRenamed(_.nonCompliant, _.auditNonCompliant)
      .withFieldRenamed(_.unexpected, _.unexpectedUnknownComponent)
      .withFieldRenamed(_.missing, _.unexpectedMissingComponent)
      .withFieldRenamed(_.noAnswer, _.noReport)
      .buildTransformer
  }

  def PERCENT_PRECISION = Level2

  def compute(reports: Iterable[ReportType]): ComplianceLevel = {
    import ReportType.*
    if (reports.isEmpty) {
      ComplianceLevel(notApplicable = 1)
    } else {
      var pending            = 0
      var success            = 0
      var repaired           = 0
      var error              = 0
      var unexpected         = 0
      var missing            = 0
      var noAnswer           = 0
      var notApplicable      = 0
      var reportsDisabled    = 0
      var compliant          = 0
      var auditNotApplicable = 0
      var nonCompliant       = 0
      var auditError         = 0
      var badPolicyMode      = 0

      reports.foreach {
        case EnforceNotApplicable => notApplicable += 1
        case EnforceSuccess       => success += 1
        case EnforceRepaired      => repaired += 1
        case EnforceError         => error += 1
        case Unexpected           => unexpected += 1
        case Missing              => missing += 1
        case NoAnswer             => noAnswer += 1
        case Pending              => pending += 1
        case Disabled             => reportsDisabled += 1
        case AuditCompliant       => compliant += 1
        case AuditNotApplicable   => auditNotApplicable += 1
        case AuditNonCompliant    => nonCompliant += 1
        case AuditError           => auditError += 1
        case BadPolicyMode        => badPolicyMode += 1
      }
      ComplianceLevel(
        pending = pending,
        success = success,
        repaired = repaired,
        error = error,
        unexpected = unexpected,
        missing = missing,
        noAnswer = noAnswer,
        notApplicable = notApplicable,
        reportsDisabled = reportsDisabled,
        compliant = compliant,
        auditNotApplicable = auditNotApplicable,
        nonCompliant = nonCompliant,
        auditError = auditError,
        badPolicyMode = badPolicyMode
      )
    }
  }

  def sum(compliances: Iterable[ComplianceLevel]): ComplianceLevel = {
    if (compliances.isEmpty) {
      ComplianceLevel()
    } else {
      var pending:            Int = 0
      var success:            Int = 0
      var repaired:           Int = 0
      var error:              Int = 0
      var unexpected:         Int = 0
      var missing:            Int = 0
      var noAnswer:           Int = 0
      var notApplicable:      Int = 0
      var reportsDisabled:    Int = 0
      var compliant:          Int = 0
      var auditNotApplicable: Int = 0
      var nonCompliant:       Int = 0
      var auditError:         Int = 0
      var badPolicyMode:      Int = 0

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
        pending = pending,
        success = success,
        repaired = repaired,
        error = error,
        unexpected = unexpected,
        missing = missing,
        noAnswer = noAnswer,
        notApplicable = notApplicable,
        reportsDisabled = reportsDisabled,
        compliant = compliant,
        auditNotApplicable = auditNotApplicable,
        nonCompliant = nonCompliant,
        auditError = auditError,
        badPolicyMode = badPolicyMode
      )
    }
  }
}

// for serialization

// utility class to always have the same names in JSON,
// even if we are refactoring ComplianceLevel at some point

// Remove 0 (and neg values) by changing them into None
// Ensure that only some are written. All intermediary objects are removed (ie it's the same as mapping int/double)
final class OptPosNum(val value: Option[Num])
object OptPosNum {
  val none = new OptPosNum(None)

  def apply(i: Int):                  OptPosNum = if (i <= 0) none else new OptPosNum(Some(Num(i)))
  def apply(i: Double):               OptPosNum = if (i <= 0) none else new OptPosNum(Some(Num(i)))
  def apply(i: java.math.BigDecimal): OptPosNum = if (i.signum() <= 0) none else new OptPosNum(Some(Num(i)))

  implicit def encoderOptPosNum: JsonEncoder[OptPosNum] = JsonEncoder.option[Num].contramap(_.value)
  implicit def decoderOptPosNum: JsonDecoder[OptPosNum] =
    JsonDecoder.option[Num].map(x => OptPosNum.apply(x.map(_.value).getOrElse(java.math.BigDecimal.ZERO)))

  implicit val transformDouble: Iso[Double, OptPosNum] = Iso[Double, OptPosNum](
    (src: Double) => OptPosNum(src),
    (src: OptPosNum) => src.value.map(_.value.doubleValue()).getOrElse(0)
  )

  implicit val transformInt: Iso[Int, OptPosNum] = Iso[Int, OptPosNum](
    (src: Int) => OptPosNum(src),
    (src: OptPosNum) => src.value.map(_.value.intValue()).getOrElse(0)
  )

}

/*
 * This one is the one that is serialized to JSON when we don't use the Array[Array[Int]].
 * The field names must be the one expected by API/client side. Order matters. Name matters.
 */
final case class ComplianceSerializable(
    applying:                   OptPosNum,
    successNotApplicable:       OptPosNum,
    successAlreadyOK:           OptPosNum,
    successRepaired:            OptPosNum,
    error:                      OptPosNum,
    auditCompliant:             OptPosNum,
    auditNonCompliant:          OptPosNum,
    auditError:                 OptPosNum,
    auditNotApplicable:         OptPosNum,
    unexpectedUnknownComponent: OptPosNum,
    unexpectedMissingComponent: OptPosNum,
    noReport:                   OptPosNum,
    reportsDisabled:            OptPosNum,
    badPolicyMode:              OptPosNum
)

object ComplianceSerializable {

  val empty: ComplianceSerializable = ComplianceSerializable(
    applying = OptPosNum.none,
    successNotApplicable = OptPosNum.none,
    successAlreadyOK = OptPosNum.none,
    successRepaired = OptPosNum.none,
    error = OptPosNum.none,
    auditCompliant = OptPosNum.none,
    auditNonCompliant = OptPosNum.none,
    auditError = OptPosNum.none,
    auditNotApplicable = OptPosNum.none,
    unexpectedUnknownComponent = OptPosNum.none,
    unexpectedMissingComponent = OptPosNum.none,
    noReport = OptPosNum.none,
    reportsDisabled = OptPosNum.none,
    badPolicyMode = OptPosNum.none
  )

  implicit val codecComplianceSerializable:     JsonCodec[ComplianceSerializable]                      = DeriveJsonCodec.gen
  implicit val transformComplianceSerializable: Transformer[ComplianceSerializable, CompliancePercent] = {
    Transformer
      .define[ComplianceSerializable, CompliancePercent]
      .withFieldConst(_.precision, Level0)
      .withFieldRenamed(_.applying, _.pending)
      .withFieldRenamed(_.successNotApplicable, _.notApplicable)
      .withFieldRenamed(_.successAlreadyOK, _.success)
      .withFieldRenamed(_.successRepaired, _.repaired)
      .withFieldRenamed(_.auditCompliant, _.compliant)
      .withFieldRenamed(_.auditNonCompliant, _.nonCompliant)
      .withFieldRenamed(_.unexpectedUnknownComponent, _.unexpected)
      .withFieldRenamed(_.unexpectedMissingComponent, _.missing)
      .withFieldRenamed(_.noReport, _.noAnswer)
      .buildTransformer
  }
}

final case class ComplianceLevelSerialisation(
    applying:                   OptPosNum,
    successNotApplicable:       OptPosNum,
    successAlreadyOK:           OptPosNum,
    successRepaired:            OptPosNum,
    error:                      OptPosNum,
    auditCompliant:             OptPosNum,
    auditNonCompliant:          OptPosNum,
    auditError:                 OptPosNum,
    auditNotApplicable:         OptPosNum,
    unexpectedUnknownComponent: OptPosNum,
    unexpectedMissingComponent: OptPosNum,
    noReport:                   OptPosNum,
    reportsDisabled:            OptPosNum,
    badPolicyMode:              OptPosNum
)

object ComplianceLevelSerialisation {

  implicit val codecComplianceLevelSerialisation: JsonCodec[ComplianceLevelSerialisation] = DeriveJsonCodec.gen

  implicit val transformComplianceLevelSerialisation: Transformer[ComplianceLevelSerialisation, ComplianceLevel] = {
    Transformer
      .define[ComplianceLevelSerialisation, ComplianceLevel]
      .withFieldRenamed(_.applying, _.pending)
      .withFieldRenamed(_.successNotApplicable, _.notApplicable)
      .withFieldRenamed(_.successAlreadyOK, _.success)
      .withFieldRenamed(_.successRepaired, _.repaired)
      .withFieldRenamed(_.auditCompliant, _.compliant)
      .withFieldRenamed(_.auditNonCompliant, _.nonCompliant)
      .withFieldRenamed(_.unexpectedUnknownComponent, _.unexpected)
      .withFieldRenamed(_.unexpectedMissingComponent, _.missing)
      .withFieldRenamed(_.noReport, _.noAnswer)
      .buildTransformer
  }

  // transform the compliance percent to an array with a given order:
  // reportDisabled, notapplicable, success, repaired,
  // error, pending, noAnswer, missing, unexpected,
  // auditNotApplicable, compliant, nonCompliant, auditError,
  // badPolicyMode
  object array {
    implicit val complianceLevelArrayEncoder: JsonEncoder[ComplianceLevel] = {
      JsonEncoder[Chunk[(Int, Double)]].contramap(compliance => {
        val pc = compliance.computePercent()
        Chunk(
          (compliance.reportsDisabled, pc.reportsDisabled), //  0

          (compliance.notApplicable, pc.notApplicable), //  1

          (compliance.success, pc.success), //  2

          (compliance.repaired, pc.repaired), //  3

          (compliance.error, pc.error), //  4

          (compliance.pending, pc.pending), //  5

          (compliance.noAnswer, pc.noAnswer), //  6

          (compliance.missing, pc.missing), //  7

          (compliance.unexpected, pc.unexpected), //  8

          (compliance.auditNotApplicable, pc.auditNotApplicable), //  9

          (compliance.compliant, pc.compliant), // 10

          (compliance.nonCompliant, pc.nonCompliant), // 11

          (compliance.auditError, pc.auditError), // 12

          (compliance.badPolicyMode, pc.badPolicyMode) // 13
        )
      })
    }
  }

  // same as in "array" but in old lift-json AST, should be removed soon
  implicit class ComplianceLevelToJs(val compliance: ComplianceLevel) extends AnyVal {

    def toJsArray: Json.Arr = {
      val pc = compliance.computePercent()
      Arr(
        Arr(Num(compliance.reportsDisabled), Num(pc.reportsDisabled)), //  0

        Arr(Num(compliance.notApplicable), Num(pc.notApplicable)), //  1

        Arr(Num(compliance.success), Num(pc.success)), //  2

        Arr(Num(compliance.repaired), Num(pc.repaired)), //  3

        Arr(Num(compliance.error), Num(pc.error)), //  4

        Arr(Num(compliance.pending), Num(pc.pending)), //  5

        Arr(Num(compliance.noAnswer), Num(pc.noAnswer)), //  6

        Arr(Num(compliance.missing), Num(pc.missing)), //  7

        Arr(Num(compliance.unexpected), Num(pc.unexpected)), //  8

        Arr(Num(compliance.auditNotApplicable), Num(pc.auditNotApplicable)), //  9

        Arr(Num(compliance.compliant), Num(pc.compliant)), // 10

        Arr(Num(compliance.nonCompliant), Num(pc.nonCompliant)), // 11

        Arr(Num(compliance.auditError), Num(pc.auditError)), // 12

        Arr(Num(compliance.badPolicyMode), Num(pc.badPolicyMode)) // 13
      )
    }
  }

}
