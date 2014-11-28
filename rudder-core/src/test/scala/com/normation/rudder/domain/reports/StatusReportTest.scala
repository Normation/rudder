/*
*************************************************************************************
* Copyright 2011 Normation SAS
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


import org.junit.runner._
import org.specs2.mutable._
import org.specs2.runner._
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.DirectiveId
import org.joda.time.DateTime
import com.normation.rudder.domain.reports._
import com.normation.rudder.reports.ComplianceMode
import com.normation.rudder.reports.FullCompliance
import com.normation.rudder.services.reports.ExecutionBatch
import com.normation.rudder.domain.policies.DirectiveId
import scala.io.Source
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.DirectiveId

/**
 * Test properties about status reports,
 * especially Compliance computation
 */

@RunWith(classOf[JUnitRunner])
class StatusReportTest extends Specification {
  private[this] implicit def s2n(s: String): NodeId = NodeId(s)
  private[this] implicit def r2n(s: String): RuleId = RuleId(s)
  private[this] implicit def d2n(s: String): DirectiveId = DirectiveId(s)


  "Compliance" should {

    "correctly be sumed" in {

      ComplianceLevel.sum(List(
          ComplianceLevel(success = 1)
        , ComplianceLevel(error = 1, repaired = 1)
        , ComplianceLevel(error = 1, pending = 1)
        , ComplianceLevel(repaired = 12)
        , ComplianceLevel(repaired = 12)
        , ComplianceLevel(repaired = 12)
        , ComplianceLevel(repaired = 12)
        , ComplianceLevel(repaired = 12)
        , ComplianceLevel(repaired = 12)
      )) === ComplianceLevel(error = 2, repaired = 73, success = 1, pending = 1)


    }

  }

  "A rule/node compliance report, with 3 messages" should {

    val all = aggregate(parse("""
       n1, r1, 12, d1, c0  , v0  , "", pending   , pending msg
       n1, r1, 12, d1, c1  , v1  , "", success   , success msg
       n1, r1, 12, d1, c2  , v2  , "", repaired  , repaired msg
       n1, r1, 12, d1, c3  , v3  , "", error     , error msg
       n1, r1, 12, d1, c4  , v4  , "", unexpected, ""
       n1, r1, 12, d1, c4_2, v4_2, "", unexpected, unexpected with message
       n1, r1, 12, d1, c5  , v5  , "", noanswer  , no answer msg
       n1, r1, 12, d1, c6  , v6  , "", n/a       , not applicable msg

    """))


    val d1c1v1_s  = parse("n1, r1, 12, d1, c1, v1, , success, success msg")
    val d1c2v21_s = parse("n1, r1, 12, d1, c2, v2, , success, success msg")
    val d2c2v21_e = parse("n1, r1, 12, d2, c2, v2, , error  , error msg")



    "correctly compute compliance" in {
      all.compliance === ComplianceLevel(1,1,1,1,2,0,1,1)
    }

    "correctly add them" in {
      aggregate(d1c1v1_s ++ d1c2v21_s).compliance.pc_success === 100
    }

    "correctly add them by directive" in {
      val a = aggregate(d1c1v1_s ++ d1c2v21_s ++ d2c2v21_e)

      (a.compliance.pc_success === 66.67.toFloat) and
      (a.directives("d1").compliance.pc_success === 100) and
      (a.directives("d2").compliance.pc_error === 100)
    }
  }

  "Aggregates" should {

    "Merge reports for same directive" in {
      val a = aggregate(parse("""
         n1, r1, 12, d1, c0  , v0  , "", pending   , pending msg
         n1, r1, 12, d1, c1  , v0  , "", pending   , pending msg
      """))

      a.directives.size === 1
    }

  }

  "Managing duplication" should {

    "Consolidate duplicate in aggregate" in {
      val duplicate = aggregate(parse("""
         n1, r1, 12, d1, c0  , v0  , "", pending   , pending msg
         n1, r1, 12, d1, c0  , v0  , "", pending   , pending msg
      """))
      duplicate.compliance === ComplianceLevel(pending = 2)
    }

    "consolidate when merging" in {
      val duplicate = parse("""
         n1, r1, 12, d1, c0  , v0  , "", pending   , pending msg
         n1, r1, 12, d1, c0  , v0  , "", pending   , pending msg
      """)
      RuleNodeStatusReport.merge(duplicate).values.head.compliance === ComplianceLevel(pending = 2)
    }

    "authorize reports with report type differences" in {
      val duplicate = aggregate(parse("""
         n1, r1, 12, d1, c0  , v0  , "", pending   , pending msg
         n1, r1, 12, d1, c0  , v1  , "", success   , success msg
      """))
      duplicate.compliance === ComplianceLevel(pending = 1, success = 1)
    }
    "authorize reports with message differences" in {
      val duplicate = aggregate(parse("""
         n1, r1, 12, d1, c0  , v0  , "", pending   , pending msg
         n1, r1, 12, d1, c0  , v1  , "", pending   , other pending
      """))
      duplicate.compliance === ComplianceLevel(pending = 2)
    }
    "authorize reports with value differences" in {
      val duplicate = aggregate(parse("""
         n1, r1, 12, d1, c0  , v0  , "", pending   , pending msg
         n1, r1, 12, d1, c0  , v1  , "", pending   , pending msg
      """))
      duplicate.compliance === ComplianceLevel(pending = 2)
    }
    "authorize reports with component differences" in {
      val duplicate = aggregate(parse("""
         n1, r1, 12, d1, c0  , v0  , "", pending   , pending msg
         n1, r1, 12, d1, c1  , v0  , "", pending   , pending msg
      """))
      duplicate.compliance === ComplianceLevel(pending = 2)
    }
    "authorize reports with directive differences" in {
      val duplicate = aggregate(parse("""
         n1, r1, 12, d1, c0  , v0  , "", pending   , pending msg
         n1, r1, 12, d2, c0  , v0  , "", pending   , pending msg
      """))
      duplicate.compliance === ComplianceLevel(pending = 2)
    }
    "authorize reports with serial differences" in {
      val duplicate = aggregate(parse("""
         n1, r1, 12, d1, c0  , v0  , "", pending   , pending msg
         n1, r1, 13, d1, c0  , v0  , "", pending   , pending msg
      """))
      duplicate.compliance === ComplianceLevel(pending = 2)
    }
    "authorize reports with rule differences" in {
      val duplicate = aggregate(parse("""
         n1, r1, 12, d1, c0  , v0  , "", pending   , pending msg
         n1, r2, 12, d1, c0  , v0  , "", pending   , pending msg
      """))
      duplicate.compliance === ComplianceLevel(pending = 2)
    }
    "authorize reports with node differences" in {
      val duplicate = aggregate(parse("""
         n1, r1, 12, d1, c0  , v0  , "", pending   , pending msg
         n2, r1, 12, d1, c0  , v0  , "", pending   , pending msg
      """))
      duplicate.compliance === ComplianceLevel(pending = 2)
    }
    "authorize reports with value differences" in {
      val duplicate = aggregate(parse("""
         n1, r1, 12, d1, c0  , v0  , "", pending   , pending msg
         n1, r1, 12, d1, c0  , v1  , "", pending   , pending msg
      """))
      duplicate.compliance === ComplianceLevel(pending = 2)
    }
  }

  "Node status reports" should {
    val report = NodeStatusReport(NodeId("n1"), parse("""
       n1, r1, 12, d1, c0  , v0  , "", pending   , pending msg
       n1, r1, 12, d1, c1  , v1  , "", pending   , pending msg
       n1, r2, 12, d1, c0  , v0  , "", success   , pending msg
       n1, r3, 12, d1, c1  , v1  , "", error     , pending msg
       n2, r4, 12, d1, c0  , v0  , "", pending   , pending msg
    """))

    "Filter out n2" in {
      report.report.reports.map( _.nodeId).toSet === Set(NodeId("n1"))
    }

    "Correctly compute the compliance" in {
      report.compliance === ComplianceLevel(pending = 2, success = 1, error = 1)
    }

    "Correctly compute the by rule compliance" in {
      report.byRules(RuleId("r1")).compliance === ComplianceLevel(pending = 2) and
      report.byRules(RuleId("r2")).compliance === ComplianceLevel(success = 1) and
      report.byRules(RuleId("r3")).compliance === ComplianceLevel(error = 1)
    }

  }

  "Rule status reports" should {
    val report = RuleStatusReport(RuleId("r1"), parse("""
       n1, r1, 12, d1, c0  , v0  , "", pending   , pending msg
       n1, r1, 12, d1, c1  , v1  , "", pending   , pending msg
       n2, r1, 12, d1, c0  , v0  , "", success   , pending msg
       n3, r1, 12, d1, c1  , v1  , "", error     , pending msg
       n4, r2, 12, d1, c0  , v0  , "", pending   , pending msg
    """))

    "Filter out r2" in {
      report.report.reports.map( _.ruleId).toSet === Set(RuleId("r1"))
    }

    "Correctly compute the compliance" in {
      report.compliance === ComplianceLevel(pending = 2, success = 1, error = 1)
    }

    "Correctly compute the by rule compliance" in {
      report.byNodes(NodeId("n1")).compliance === ComplianceLevel(pending = 2) and
      report.byNodes(NodeId("n2")).compliance === ComplianceLevel(success = 1) and
      report.byNodes(NodeId("n3")).compliance === ComplianceLevel(error = 1)
    }

  }

  private[this] def parse(s: String): Seq[RuleNodeStatusReport] = {


    def ?(s: String): Option[String] = s.trim match {
      case "" | "\"\"" => None
      case x => Some(x)
    }

    Source.fromString(s).getLines.zipWithIndex.map { case(l,i) =>
      val parsed = l.split(",").map( _.trim).toList
      parsed match {
        case n :: r :: s :: d :: c :: v :: uv :: t :: m :: Nil =>
          Some(RuleNodeStatusReport(n, r, s.toInt, None, None, Map(DirectiveId(d) ->
            DirectiveStatusReport(d, Map(c ->
              ComponentStatusReport(c, Map(v ->
                ComponentValueStatusReport(v, ?(uv), List(
                    MessageStatusReport(toRT(t), ?(m))
                ))
              ))
            ))
          )))
        case "" :: Nil | Nil => None
        case _ => throw new IllegalArgumentException(s"Can not parse line ${i}: '${l}'")
      }
    }.flatten.toList
  }


  private[this] def toRT(s: String): ReportType = s.toLowerCase match {
    case "success"    => SuccessReportType
    case "error"      => ErrorReportType
    case "noanswer"   => NoAnswerReportType
    case "unexpected" => UnexpectedReportType
    case "missing"    => MissingReportType
    case "n/a" | "na" => NotApplicableReportType
    case "repaired"   => RepairedReportType
    case "applying" |
          "pending"   => PendingReportType
    case s => throw new IllegalArgumentException(s)
  }

  private[this] def aggregate(nr: Seq[RuleNodeStatusReport]): AggregatedStatusReport = {
    AggregatedStatusReport(nr)
  }


}