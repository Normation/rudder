/*
 *************************************************************************************
 * Copyright 2016 Normation SAS
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

package com.normation.rudder.services.reports

import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.DirectiveUid
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.RuleUid
import com.normation.rudder.domain.reports.ComplianceLevel
import com.normation.rudder.domain.reports.ExpectedReportsSerialisation
import com.normation.rudder.domain.reports.NodeConfigId
import com.normation.rudder.domain.reports.NodeExpectedReports
import com.normation.rudder.domain.reports.Reports
import net.liftweb.common.Box
import net.liftweb.common.Failure
import net.liftweb.common.Full
import org.joda.time.format.DateTimeFormatter
import org.joda.time.format.DateTimeFormatterBuilder
import org.junit.runner.*
import org.specs2.mutable.*
import org.specs2.runner.*
import scala.io.Source

/**
 *
 * This file allows to read expected node configuration from a databased export,
 * the corresponding reports, compute the resulting node status, and compare it
 * to an expected one.
 *
 */

/**
 * A date/time parser that understands the PostgreSQL date format (printed as after a select...),
 * with optional millisecond and lenient on timezone format (because for some reason, depending of some
 * print configuration, they are not always displayed the same way)
 *
 * So you can parse both:
 *     2016-11-15 17:12:34.333+00
 * And:
 *     2016-11-15 17:12:34+00
 * Or:
 *     2016-11-15 17:12:34+0150
 */
object PgOptMillisDateTimeParser {

  val pgDateTimeFormater: DateTimeFormatter = (
    (new DateTimeFormatterBuilder())
      .appendYear(4, 4)
      .appendLiteral("-")
      .appendMonthOfYear(2)
      .appendLiteral("-")
      .appendDayOfMonth(2)
      .appendLiteral(" ")
      .appendHourOfDay(2)
      .appendLiteral(":")
      .appendMinuteOfHour(2)
      .appendLiteral(":")
      .appendSecondOfMinute(2)
      .appendOptional((new DateTimeFormatterBuilder()).appendLiteral(".").appendMillisOfSecond(3).toParser())
      .appendTimeZoneOffset(
        "",
        true,
        2,
        4
      )
    )
    .toFormatter()

}

@RunWith(classOf[JUnitRunner])
class ComplianceTest extends Specification {

  val dateParser = PgOptMillisDateTimeParser.pgDateTimeFormater

  /*
   * read a pg request file, with column header:
   *
   * column1 | column2 | column3
   * --------+---------+--------
   *  xxxx   | xxxx    |  xxxx
   *
   * Path relative to src/test/resources.
   * The first line will give the number of column separated by "|"
   * Check that the list of headers match the one provided, in the same order.
   * Then, we only keep lines with that number.
   *
   * Hypothesis: the first column is the key, so can't be empty for a new result.
   * If it is empty, it is considered that the line must be merged with the
   * previous one (one of the column is a multiline one)
   * (normally we should base the test in the presence of "+" at the end of column
   * field, but we would need a real parser to do that, not a by-line-regex-tokenizer).
   *
   */
  def read(filename: String, headers: List[String]): Box[List[Array[String]]] = {
    val is       = this.getClass().getClassLoader().getResourceAsStream(filename)
    val sqlLines = Source.fromInputStream(is).getLines().toList
    sqlLines match {
      case Nil | _ :: Nil | _ :: _ :: Nil =>
        Failure(s"The file ${filename} is empty or contains only headers")
      case h :: lines                     =>
        val fileHeaders: Array[String] = h.split("""\|""").map(_.trim.toLowerCase)
        val headerPairs = headers.map(_.trim.toLowerCase).zip(fileHeaders)

        if (fileHeaders.size == headers.size && headerPairs.forall { case (h1, h2) => h1 == h2 }) {
          def splitLine(line: String): Array[String] =
            line.split("""\|""").map(l => if (l.endsWith("+")) l.substring(0, l.size - 2).trim else l.trim)

          val cleaned = lines
            .map(splitLine)
            .filter(_.size == headers.size)

          // merged line which need to be
          Full(
            cleaned
              .foldLeft(Vector.empty[Array[String]]) {
                case (previous, nextLine) =>
                  nextLine(0) match {
                    case "" =>
                      if (previous.isEmpty) {
                        Vector(nextLine)
                      } else { // merge with previous
                        val last = previous.last
                        // side effects !
                        for (i <- 0 until last.size) { last(i) = last(i) + nextLine(i) }
                        previous
                      }
                    case x  =>
                      previous :+ nextLine
                  }
              }
              .toList
          )
        } else {
          Failure(s"The provided headers does not match the one in the files: ${headerPairs}")
        }
    }
  }

  def readReports(filename: String): Box[List[Reports]] = {
    for {
      lines <- read(
                 filename,
                 List(
                   "id",
                   "executiondate",
                   "nodeid",
                   "directiveid",
                   "ruleid",
                   "reportid",
                   "component",
                   "keyvalue",
                   "executiontimestamp",
                   "eventtype",
                   "policy",
                   "msg"
                 )
               )
    } yield {
      lines.map(l => {
        Reports(
          dateParser.parseDateTime(l(1)),
          RuleId(RuleUid(l(4))),
          DirectiveId(DirectiveUid(l(3))),
          NodeId(l(2)),
          l(5),
          l(6),
          l(7),
          dateParser.parseDateTime(l(8)),
          l(9),
          l(10)
        )
      })
    }
  }

  def readNodeConfig(filename: String): Box[NodeExpectedReports] = {
    for {
      lines <- read(filename, List("nodeid", "nodeconfigid", "begindate", "enddate", "configuration"))
      ok    <- if (lines.size != 1) Failure(s"We should have only one expected node configuration") else Full("ok")
      l      = lines.head
      json  <- ExpectedReportsSerialisation.parseJsonNodeExpectedReports(l(4))
    } yield {
      val end = l(3) match {
        case "" => None
        case d  => Some(dateParser.parseDateTime(d))
      }
      NodeExpectedReports(
        NodeId(l(0)),
        NodeConfigId(l(1)),
        dateParser.parseDateTime(l(3)),
        end,
        json.modes,
        json.schedules,
        json.ruleExpectedReports,
        Nil
      )
    }
  }

  "A directive applied to two rules" should {

    val path = "test-compliance/test-same-directive-several-rules/"

    "lead to correct reporting" in {
      val (reports, config) = (for {
        reports <- readReports(path + "ruddersysevent")
        config  <- readNodeConfig(path + "nodeconfig")
      } yield {
        (reports, config)
      }).openOrThrowException("the test should not throw an exception")

      val runTime = reports.head.executionTimestamp
      // here, we assume "compute compliance", i.e we are only testing the compliance engine, not
      // the meta-analysis on run consistency (correct run, at the correct time, etc)
      val runinfo = ComputeCompliance(runTime, config, runTime)
      val status  = ExecutionBatch.getNodeStatusReports(config.nodeId, runinfo, reports)

      // we really have 26 (ie 18+8) values
      status.compliance must beEqualTo(ComplianceLevel(success = 18, notApplicable = 8))
    }
  }

  "A directive with two identical component, but one parameterized" should {
    val path = "test-compliance/test-same-component/"

    "lead to correct reporting (only success" in {
      val (reports, config) = (for {
        reports <- readReports(path + "ruddersysevent")
        config  <- readNodeConfig(path + "nodeconfig")
      } yield {
        (reports, config)
      }).openOrThrowException("the test should not throw an exception")

      val runTime = reports.head.executionTimestamp
      // here, we assume "compute compliance", i.e we are only testing the compliance engine, not
      // the meta-analysis on run consistancy (correct run, at the correct time, etc)
      val runinfo = ComputeCompliance(runTime, config, runTime)
      val status  = ExecutionBatch.getNodeStatusReports(config.nodeId, runinfo, reports)

      // we really have 39 values in total
      status.compliance must beEqualTo(ComplianceLevel(success = 34, notApplicable = 5))
    }
  }
}
