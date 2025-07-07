/*
 *************************************************************************************
 * Copyright 2011 Normation SAS
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

import com.normation.GitVersion
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.DirectiveUid
import com.normation.rudder.domain.policies.PolicyTypeName
import com.normation.rudder.domain.policies.PolicyTypes
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.RuleUid
import com.normation.rudder.domain.reports.ReportType.*
import com.normation.rudder.services.policies.NodeConfigData
import com.normation.rudder.services.reports.NodeStatusReportInternal
import com.normation.rudder.services.reports.Pending
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import scala.io.Source

/**
 * Test properties about status reports,
 * especially Compliance computation
 */

@RunWith(classOf[JUnitRunner])
class StatusReportTest extends Specification {
  implicit private def s2n(s: String): NodeId      = NodeId(s)
  implicit private def r2n(s: String): RuleId      = RuleId(RuleUid(s))
  implicit private def d2n(s: String): DirectiveId = DirectiveId(DirectiveUid(s), GitVersion.DEFAULT_REV)

  sequential

  "Compliance" should {

    "correctly be sumed" in {

      ComplianceLevel.sum(
        List(
          ComplianceLevel(success = 1),
          ComplianceLevel(error = 1, repaired = 1),
          ComplianceLevel(error = 1, pending = 1),
          ComplianceLevel(repaired = 12),
          ComplianceLevel(repaired = 12),
          ComplianceLevel(repaired = 12),
          ComplianceLevel(repaired = 12),
          ComplianceLevel(repaired = 12),
          ComplianceLevel(repaired = 12)
        )
      ) === ComplianceLevel(error = 2, repaired = 73, success = 1, pending = 1)

    }

  }

  "A rule/node compliance report, with 3 messages" should {

    val all = aggregate(parse("""
       n1, r1, 0, d1, c0  , v0  , "", pending   , pending msg
       n1, r1, 0, d1, c1  , v1  , "", success   , success msg
       n1, r1, 0, d1, c2  , v2  , "", repaired  , repaired msg
       n1, r1, 0, d1, c3  , v3  , "", error     , error msg
       n1, r1, 0, d1, c4  , v4  , "", unexpected, ""
       n1, r1, 0, d1, c4_2, v4_2, "", unexpected, unexpected with message
       n1, r1, 0, d1, c5  , v5  , "", noanswer  , no answer msg
       n1, r1, 0, d1, c6  , v6  , "", n/a       , not applicable msg

    """))

    val d1c1v1_s  = parse("n1, r1, 0, d1, c1, v1, , success, success msg")
    val d1c2v21_s = parse("n1, r1, 0, d1, c2, v2, , success, success msg")
    val d2c2v21_e = parse("n1, r1, 0, d2, c2, v2, , error  , error msg")

    "correctly compute compliance" in {
      all.compliance === ComplianceLevel(1, 1, 1, 1, 2, 0, 1, 1)
    }

    "correctly add them" in {
      aggregate(d1c1v1_s ++ d1c2v21_s).compliance.computePercent().success === 100d
    }

    "correctly add them by directive" in {
      val a = aggregate(d1c1v1_s ++ d1c2v21_s ++ d2c2v21_e)

      (a.compliance.computePercent().success === 66.67d) and
      (a.directives("d1").compliance.computePercent().success === 100d) and
      (a.directives("d2").compliance.computePercent().error === 100d)
    }
  }

  "Aggregates" should {

    "Merge reports for same directive" in {
      val a = aggregate(parse("""
         n1, r1, 0, d1, c0  , v0  , "", pending   , pending msg
         n1, r1, 0, d1, c1  , v0  , "", pending   , pending msg
      """))

      a.directives.size === 1
    }

  }

  "Managing duplication" should {

    "Consolidate duplicate in aggregate" in {
      val duplicate = aggregate(parse("""
         n1, r1, 0, d1, c0  , v0  , "", pending   , pending msg
         n1, r1, 0, d1, c0  , v0  , "", pending   , pending msg
      """))
      duplicate.compliance === ComplianceLevel(pending = 2)
    }

    "consolidate when merging" in {
      val duplicate = parse("""
         n1, r1, 0, d1, c0  , v0  , "", pending   , pending msg
         n1, r1, 0, d1, c0  , v0  , "", pending   , pending msg
      """)
      RuleNodeStatusReport.merge(duplicate).values.head.compliance === ComplianceLevel(pending = 2)
    }

    "authorize reports with report type differences" in {
      val duplicate = aggregate(parse("""
         n1, r1, 0, d1, c0  , v0  , "", pending   , pending msg
         n1, r1, 0, d1, c0  , v1  , "", success   , success msg
      """))
      duplicate.compliance === ComplianceLevel(pending = 1, success = 1)
    }
    "authorize reports with message differences" in {
      val duplicate = aggregate(parse("""
         n1, r1, 0, d1, c0  , v0  , "", pending   , pending msg
         n1, r1, 0, d1, c0  , v1  , "", pending   , other pending
      """))
      duplicate.compliance === ComplianceLevel(pending = 2)
    }
    "authorize reports with value differences" in {
      val duplicate = aggregate(parse("""
         n1, r1, 0, d1, c0  , v0  , "", pending   , pending msg
         n1, r1, 0, d1, c0  , v1  , "", pending   , pending msg
      """))
      duplicate.compliance === ComplianceLevel(pending = 2)
    }
    "authorize reports with component differences" in {
      val duplicate = aggregate(parse("""
         n1, r1, 0, d1, c0  , v0  , "", pending   , pending msg
         n1, r1, 0, d1, c1  , v0  , "", pending   , pending msg
      """))
      duplicate.compliance === ComplianceLevel(pending = 2)
    }
    "authorize reports with directive differences" in {
      val duplicate = aggregate(parse("""
         n1, r1, 0, d1, c0  , v0  , "", pending   , pending msg
         n1, r1, 0, d2, c0  , v0  , "", pending   , pending msg
      """))
      duplicate.compliance === ComplianceLevel(pending = 2)
    }
    "authorize reports with rule differences" in {
      val duplicate = aggregate(parse("""
         n1, r1, 0, d1, c0  , v0  , "", pending   , pending msg
         n1, r2, 0, d1, c0  , v0  , "", pending   , pending msg
      """))
      duplicate.compliance === ComplianceLevel(pending = 2)
    }
    "authorize reports with node differences" in {
      val duplicate = aggregate(parse("""
         n1, r1, 0, d1, c0  , v0  , "", pending   , pending msg
         n2, r1, 0, d1, c0  , v0  , "", pending   , pending msg
      """))
      duplicate.compliance === ComplianceLevel(pending = 2)
    }
    "authorize reports with value differences" in {
      val duplicate = aggregate(parse("""
         n1, r1, 0, d1, c0  , v0  , "", pending   , pending msg
         n1, r1, 0, d1, c0  , v1  , "", pending   , pending msg
      """))
      duplicate.compliance === ComplianceLevel(pending = 2)
    }
  }

  "Node status reports" should {
    val modesConfig = NodeConfigData.defaultModesConfig
    val report      = NodeStatusReportInternal
      .buildWith(
        NodeId("n1"),
        Pending(
          NodeExpectedReports(
            NodeId("n1"),
            NodeConfigId("plop"),
            DateTime.now(DateTimeZone.UTC),
            None,
            modesConfig,
            Nil,
            List()
          ), // TODO : correct that test

          None,
          DateTime.now.plusMinutes(15)
        ),
        RunComplianceInfo.OK,
        Nil,
        parse("""
       n1, r1, 0, d1, c0  , v0  , "", pending   , pending msg
       n1, r1, 0, d1, c1  , v1  , "", pending   , pending msg
       n1, r2, 0, d1, c0  , v0  , "", success   , pending msg
       n1, r3, 0, d1, c1  , v1  , "", error     , pending msg
    """).toSet
      )
      .toNodeStatusReport()

    "Correctly compute the compliance" in {
      report.compliance === ComplianceLevel(pending = 2, success = 1, error = 1)
    }

    "Correctly compute the by rule compliance" in {
      report
        .reports(PolicyTypeName.rudderBase)
        .filterByRules(Set(RuleId(RuleUid("r1"))))
        .compliance === ComplianceLevel(pending = 2) and
      report
        .reports(PolicyTypeName.rudderBase)
        .filterByRules(Set(RuleId(RuleUid("r2"))))
        .compliance === ComplianceLevel(success = 1) and
      report
        .reports(PolicyTypeName.rudderBase)
        .filterByRules(Set(RuleId(RuleUid("r3"))))
        .compliance === ComplianceLevel(error = 1)
    }

  }

  "Rule status reports" should {
    val report = RuleStatusReport(
      RuleId(RuleUid("r1")),
      parse("""
       n1, r1, 0, d1, c0  , v0  , "", pending   , pending msg
       n1, r1, 0, d1, c1  , v1  , "", pending   , pending msg
       n2, r1, 0, d1, c0  , v0  , "", success   , pending msg
       n3, r1, 0, d1, c1  , v1  , "", error     , pending msg
       n4, r2, 0, d1, c0  , v0  , "", pending   , pending msg
    """)
    )

    "Filter out r2" in {
      report.report.reports.map(_.ruleId).toSet === Set(RuleId(RuleUid("r1")))
    }

    "Correctly compute the compliance" in {
      report.compliance === ComplianceLevel(pending = 2, success = 1, error = 1)
    }

    "Correctly compute the by rule compliance" in {
      report.byNodes(NodeId("n1")).compliance === ComplianceLevel(pending = 2) and
      report.byNodes(NodeId("n2")).compliance === ComplianceLevel(success = 1) and
      report.byNodes(NodeId("n3")).compliance === ComplianceLevel(error = 1)
    }

    "performance for compute ReportType" should {

      val logger = org.slf4j.LoggerFactory.getLogger("performance-test").asInstanceOf[ch.qos.logback.classic.Logger]
      // you can look at individual results by setting log level to "TRACE" here:
      logger.setLevel(ch.qos.logback.classic.Level.OFF)

      val nbSet   = 90
      val sizeSet = 100

      // force tests to be sequentials

      val initData = buildComplianceLevelSets(nbSet, sizeSet)

      val runTest = System.getProperty("tests.run.perf", "false") == "true"
      "init correctly" in {
        val result = initData.headOption.map(x => ComplianceLevel.compute(x._2))

        // used to warm up
        val _ = ComplianceLevel.sum(Seq(result.get))
        result.size === 1
      }

      "run fast enough to compute" in {
        initData.map(x => ComplianceLevel.compute(x._2))
        val t0 = System.nanoTime

        for (i <- 1 to 100) {
          val t0_0 = System.nanoTime
          initData.map(x => ComplianceLevel.compute(x._2))
          val t1_1 = System.nanoTime
          logger.trace(s"${i}th call to compute for ${nbSet} sets took ${(t1_1 - t0_0) / 1000} µs")
        }
        val t1 = System.nanoTime
        logger.debug(s"Time to run test is ${(t1 - t0) / 1000} µs")
        ((t1 - t0) must be lessThan (200000 * 1000)).when(runTest) // tests show 60030µs
      }

      "run fast enough to sum" in {
        ComplianceLevel.sum(initData.map(x => ComplianceLevel.compute(x._2)))

        val source = initData.map(x => (x._1, ComplianceLevel.compute(x._2))).map(_._2)
        val t0     = System.nanoTime

        for (i <- 1 to 100) {
          val t0_0 = System.nanoTime
          // use to warm up
          val _    = ComplianceLevel.sum(source)
          val t1_1 = System.nanoTime
          logger.trace(s"${i}th call to sum for ${nbSet} sets took ${(t1_1 - t0_0) / 1000} µs")
        }
        val t1 = System.nanoTime
        logger.debug(s"Time to run test for sum is ${(t1 - t0) / 1000} µs")
        ((t1 - t0) must be lessThan (50000 * 1000)).when(runTest) // tests show 3159µs on recent XPS but 30795 µs on XPS 15 9650
      }
    }
  }

  private def parse(s: String): List[RuleNodeStatusReport] = {

    def ?(s: String): Option[String] = s.trim match {
      case "" | "\"\"" => None
      case x           => Some(x)
    }

    Source
      .fromString(s)
      .getLines()
      .zipWithIndex
      .map {
        case (l, i) =>
          val parsed = l.split(",").map(_.trim).toList
          parsed match {
            case n :: r :: id :: d :: c :: v :: uv :: t :: m :: Nil =>
              Some(
                RuleNodeStatusReport(
                  n,
                  r,
                  PolicyTypeName.rudderBase,
                  None,
                  None,
                  Map(
                    DirectiveId(DirectiveUid(d)) ->
                    DirectiveStatusReport(
                      d,
                      PolicyTypes.rudderBase,
                      None,
                      List(
                        ValueStatusReport(
                          c,
                          c,
                          List(
                            ComponentValueStatusReport(
                              v,
                              uv,
                              id,
                              List(
                                MessageStatusReport(toRT(t), ?(m))
                              )
                            )
                          )
                        )
                      )
                    )
                  ),
                  DateTime.now.plusMinutes(5)
                )
              )
            case "" :: Nil | Nil                                    => None
            case _                                                  => throw new IllegalArgumentException(s"Can not parse line ${i}: '${l}'")
          }
      }
      .flatten
      .toList
  }

  private def toRT(s: String): ReportType = s.toLowerCase match {
    case "success"              => EnforceSuccess
    case "error"                => EnforceError
    case "noanswer"             => NoAnswer
    case "unexpected"           => Unexpected
    case "missing"              => Missing
    case "n/a" | "na"           => EnforceNotApplicable
    case "repaired"             => EnforceRepaired
    case "applying" | "pending" => ReportType.Pending
    case s                      => throw new IllegalArgumentException(s)
  }

  private def aggregate(nr: Iterable[RuleNodeStatusReport]): AggregatedStatusReport = {
    AggregatedStatusReport(nr)
  }

  def buildComplianceLevelSets(
      nbSet:   Int,
      sizeSet: Int
  ): IndexedSeq[(Int, Seq[ReportType])] = {
    val sets = (1 to nbSet).toSeq
    val reportTypes: Seq[ReportType] =
      (1 to (sizeSet / 2)).map(_ => EnforceSuccess).toSeq ++ ((1 to (sizeSet / 2)).map(_ => EnforceRepaired).toSeq)

    sets.map(x => (x, reportTypes ++ (1 to x).map(_ => EnforceNotApplicable)))
  }

}
