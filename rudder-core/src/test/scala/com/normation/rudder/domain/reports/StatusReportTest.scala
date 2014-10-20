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

/**
 * Test properties about status reports,
 * especially Compliance computation
 */

@RunWith(classOf[JUnitRunner])
class StatusReportTest extends Specification {

  private[this] implicit def s2n(s: String): NodeId = NodeId(s)
  private[this] implicit def r2n(s: String): RuleId = RuleId(s)
  private[this] implicit def d2n(s: String): DirectiveId = DirectiveId(s)

  private[this] implicit def csr(cpts: (String, List[(String, List[(String, String)])])*): List[ComponentStatusReport] = {
    cpts.map(c =>
      ComponentStatusReport(c._1, c._2.map(v =>
          (
            v._1
          , ComponentValueStatusReport(v._1, Some(v._1), v._2.map(msg => MessageStatusReport(toRT(msg._1), msg._2)).toList)
          )
        ).toMap)
    ).toList
  }
  private[this] implicit def dsr(sources: (String, List[ComponentStatusReport])*): Map[DirectiveId, DirectiveStatusReport] = {
    sources.map(source =>
      (DirectiveId(source._1), DirectiveStatusReport(source._1, source._2.map(x => (x.componentName, x)).toMap))
    ).toMap
  }


  val all = RuleNodeStatusReport( "n1", "r1", 12, None, None, dsr(
    ("d1", csr(
        ("c0", ("v0", ("pending","pending msg")::Nil)::Nil)
      , ("c1", ("v1", ("success","success msg")::Nil)::Nil)
      , ("c2", ("v2", ("repaired","repaired msg")::Nil)::Nil)
      , ("c3", ("v3", ("error","error msg")::Nil)::Nil)
      , ("c4", ("v4", ("unknown","")::Nil)::Nil)
      , ("c4_2", ("v4_2", ("unknown","unknown with message")::Nil)::Nil)
      , ("c5", ("v5", ("noanswer","no answer msg")::Nil)::Nil)
      , ("c6", ("v6", ("n/a","not applicable msg")::Nil)::Nil)
    ))
  ) )

  val d1c1v1_s = RuleNodeStatusReport( "n1", "r1", 12, None, None, dsr(
    ("d1", csr(
        ("c1", ("v1", ("success","success msg")::Nil)::Nil)
    ))
  ) )
  val d1c2v21_s = RuleNodeStatusReport( "n1", "r1", 12, None, None, dsr(
    ("d1", csr(
        ("c2", ("v2", ("success","success msg")::Nil)::Nil)
    ))
  ) )
  val d2c2v21_e = RuleNodeStatusReport( "n1", "r1", 12, None, None, dsr(
    ("d2", csr(
        ("c2", ("v2", ("error","error msg")::Nil)::Nil)
    ))
  ) )

  "A rule/node compliance report, with 3 messages" should {


    "correctly compute compliance" in {
      all.compliance === ComplianceLevel(1,1,1,1,2,1,1)
    }

    "correctly add them" in {
      aggregate(d1c1v1_s, d1c2v21_s).compliance.pc_success === 100
    }

    "correctly add them by directive" in {
      val a = aggregate(d1c1v1_s, d1c2v21_s, d2c2v21_e)

      (a.compliance.pc_success === 66) and
      (a.directives("d1").compliance.pc_success === 100) and
      (a.directives("d2").compliance.pc_error === 100)
    }
  }



  private[this] def toRT(s: String): ReportType = s.toLowerCase match {
    case "success"    => SuccessReportType
    case "error"      => ErrorReportType
    case "noanswer"   => NoAnswerReportType
    case "unknown"    => UnexpectedReportType
    case "n/a" | "na" => NotApplicableReportType
    case "repaired"   => RepairedReportType
    case "applying" |
          "pending"   => PendingReportType
    case s => throw new IllegalArgumentException(s)
  }

  private[this] def aggregate(nr: RuleNodeStatusReport*): AggregatedStatusReport = {
    AggregatedStatusReport(nr.toSet)
  }


}