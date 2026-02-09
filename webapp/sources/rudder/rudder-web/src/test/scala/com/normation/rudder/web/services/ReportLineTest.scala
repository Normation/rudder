/*
 *************************************************************************************
 * Copyright 2024 Normation SAS
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

package com.normation.rudder.web.services

import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies.*
import com.normation.rudder.domain.reports.*
import com.normation.rudder.web.services.ReportLineTest.*
import com.normation.utils.DateFormaterService
import net.liftweb.common.Full
import org.joda.time.DateTime
import org.junit.runner.RunWith
import org.specs2.mutable.*
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class ReportLineTest extends Specification {

  "report lines serialisation" >> {

    val executionTimestamp = DateTime.parse("2024-12-14T14:47:53Z", DateFormaterService.rfcDateformat)

    val reports = Seq[ResultReports](
      TestResultReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component",
        "foo",
        "message"
      ).toRE,
      TestResultReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component",
        "foo",
        "message"
      ).toAC,
      TestResultReport(
        executionTimestamp,
        "cr",
        "policy",
        "nodeId",
        "report_id12",
        "component",
        "bar",
        "message"
      ).toRS
    )

    def dirName(id: DirectiveId): Full[String] = id.serialize match {
      case "policy" => Full("Directive name")
    }

    def ruleName(id: RuleId): Full[String] = id.serialize match {
      case "cr" => Full("Rule name")
    }

    LogDisplayer.getReportsLineForNode(reports, dirName, ruleName).toJson.toJsCmd.strip() must beEqualTo(
      """[{"executionDate": "2024-12-14 14:47:53+0000", "runDate": "2024-12-14 14:47:53+0000", "kind": "result", "status": "error", "ruleName": "Rule name", "directiveName": "Directive name", "component": "component", "value": "foo", "message": "message"},
        | {"executionDate": "2024-12-14 14:47:53+0000", "runDate": "2024-12-14 14:47:53+0000", "kind": "audit", "status": "compliant", "ruleName": "Rule name", "directiveName": "Directive name", "component": "component", "value": "foo", "message": "message"},
        | {"executionDate": "2024-12-14 14:47:53+0000", "runDate": "2024-12-14 14:47:53+0000", "kind": "result", "status": "success", "ruleName": "Rule name", "directiveName": "Directive name", "component": "component", "value": "bar", "message": "message"}
        |]""".stripMargin.replaceAll("\n", "").strip()
    )
  }
}

object ReportLineTest {

  private case class TestResultReport(
      executionDate: DateTime,
      ruleId:        String,
      directiveId:   String,
      nodeId:        String,
      reportId:      String,
      component:     String,
      keyValue:      String,
      message:       String
  ) {
    def toT: (DateTime, RuleId, DirectiveId, NodeId, String, String, String, DateTime, String) = (
      executionDate,
      RuleId(RuleUid(ruleId)),
      DirectiveId(DirectiveUid(directiveId)),
      NodeId(nodeId),
      reportId,
      component,
      keyValue,
      executionDate,
      message
    )

    def toRS: ResultSuccessReport  = (ResultSuccessReport.apply _).tupled.apply(toT)
    def toRE: ResultErrorReport    = (ResultErrorReport.apply _).tupled.apply(toT)
    def toAC: AuditCompliantReport = (AuditCompliantReport.apply _).tupled.apply(toT)
  }
}
