/*
 *************************************************************************************
 * Copyright 2026 Normation SAS
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
package com.normation.rudder.rest

import com.normation.cfclerk.domain.ReportingLogic.FocusWorst
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.DirectiveUid
import com.normation.rudder.domain.policies.GlobalPolicyMode
import com.normation.rudder.domain.policies.PolicyMode.Enforce
import com.normation.rudder.domain.policies.PolicyModeOverrides
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.RuleUid
import com.normation.rudder.domain.reports.BlockStatusReport
import com.normation.rudder.domain.reports.ComplianceLevel
import com.normation.rudder.domain.reports.ComponentStatusReport
import com.normation.rudder.domain.reports.ComponentValueStatusReport
import com.normation.rudder.domain.reports.MessageStatusReport
import com.normation.rudder.domain.reports.ReportType
import com.normation.rudder.domain.reports.ValueStatusReport
import com.normation.rudder.reports.ComplianceModeName.FullCompliance
import com.normation.rudder.rest.data.ByNodeDirectiveCompliance
import com.normation.rudder.rest.data.ByNodeNodeCompliance
import com.normation.rudder.rest.data.ByNodeRuleCompliance
import com.normation.rudder.rest.data.CsvCompliance
import com.normation.rudder.rest.data.CsvCompliance.NodeComplianceByRuleCsv
import com.normation.rudder.web.services.ComputePolicyMode
import com.normation.rudder.web.services.ComputePolicyMode.ComputedPolicyMode
import com.normation.utils.Csv.toCsv
import io.scalaland.chimney.syntax.*
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class TestNodeComplianceCsv extends Specification {

  val globalMode: GlobalPolicyMode   = GlobalPolicyMode(Enforce, PolicyModeOverrides.Unoverridable)
  val policyMode: ComputedPolicyMode = ComputePolicyMode.nodeMode(globalMode, None)

  // Utils

  private def buildNodeCompliance(reports: List[ComponentStatusReport]): ByNodeNodeCompliance = {
    ByNodeNodeCompliance(
      id = NodeId("nodeId"),
      name = "nodeName",
      compliance = ComplianceLevel(),
      mode = FullCompliance,
      policyMode = policyMode,
      nodeCompliances = Seq(
        ByNodeRuleCompliance(
          id = RuleId(RuleUid("ruleId")),
          name = "ruleName",
          compliance = ComplianceLevel(),
          policyMode = policyMode,
          directives = Seq(
            ByNodeDirectiveCompliance(
              id = DirectiveId(DirectiveUid("directiveId")),
              name = "directiveName",
              compliance = ComplianceLevel(),
              skippedDetails = None,
              policyMode = policyMode,
              components = reports
            )
          )
        )
      )
    )
  }

  private def componentValueStatusReport(n: Int): ComponentValueStatusReport = {
    ComponentValueStatusReport(
      s"component${n}-value",
      s"component${n}-value",
      s"component${n}-report",
      MessageStatusReport(ReportType.AuditCompliant, s"component${n} compliant") :: Nil
    )
  }

  private def valueStatusReport(n: Int): ValueStatusReport = {
    ValueStatusReport(
      s"component${n}",
      s"component${n}",
      componentValueStatusReport(n) :: Nil
    )
  }

  sequential

  ///// tests ////

  val emptyNodeCompliance:  ByNodeNodeCompliance = {
    ByNodeNodeCompliance(
      id = NodeId("nodeId"),
      name = "nodeName",
      compliance = ComplianceLevel(),
      mode = FullCompliance,
      policyMode = policyMode,
      nodeCompliances = Seq()
    )
  }
  val nodeCompliance:       ByNodeNodeCompliance = {
    buildNodeCompliance(
      reports = List(
        valueStatusReport(1),
        valueStatusReport(2),
        valueStatusReport(3)
      )
    )
  }
  val nestedNodeCompliance: ByNodeNodeCompliance = {
    buildNodeCompliance(
      reports = List(
        valueStatusReport(1),
        BlockStatusReport(
          "block1",
          FocusWorst,
          List(
            valueStatusReport(2),
            BlockStatusReport(
              "block2",
              FocusWorst,
              List(
                valueStatusReport(3),
                BlockStatusReport(
                  "block3",
                  FocusWorst,
                  List(valueStatusReport(4))
                ),
                BlockStatusReport(
                  "block4",
                  FocusWorst,
                  List(valueStatusReport(5), valueStatusReport(6))
                )
              )
            )
          )
        )
      )
    )
  }

  "Node compliance " in {
    "with empty compliance information should result in a CSV file that only contains the column names when exported" in {

      val compliance = emptyNodeCompliance.nodeCompliances.transformInto[Seq[NodeComplianceByRuleCsv]]
      compliance.toCsv.mustEqual(""""Rule","Directive","Block","Component","Value","Status","Message"
                                   |""".stripMargin)
    }
    "with non-empty compliance information should produce the expected CSV file when exported" in {
      val compliance = nodeCompliance.nodeCompliances.transformInto[Seq[NodeComplianceByRuleCsv]]
      compliance.toCsv.mustEqual(
        """"Rule","Directive","Block","Component","Value","Status","Message"
          |"ruleName","directiveName","","component1","component1-value","auditCompliant","component1 compliant"
          |"ruleName","directiveName","","component2","component2-value","auditCompliant","component2 compliant"
          |"ruleName","directiveName","","component3","component3-value","auditCompliant","component3 compliant"
          |""".stripMargin
      )
    }
    "with non-empty compliance information and nested blocks should produce the expected CSV file when exported" in {
      val compliance = nestedNodeCompliance.nodeCompliances.transformInto[Seq[NodeComplianceByRuleCsv]]
      compliance.toCsv.mustEqual(
        """"Rule","Directive","Block","Component","Value","Status","Message"
          |"ruleName","directiveName","","component1","component1-value","auditCompliant","component1 compliant"
          |"ruleName","directiveName","block1","component2","component2-value","auditCompliant","component2 compliant"
          |"ruleName","directiveName","block1,block2","component3","component3-value","auditCompliant","component3 compliant"
          |"ruleName","directiveName","block1,block2,block3","component4","component4-value","auditCompliant","component4 compliant"
          |"ruleName","directiveName","block1,block2,block4","component5","component5-value","auditCompliant","component5 compliant"
          |"ruleName","directiveName","block1,block2,block4","component6","component6-value","auditCompliant","component6 compliant"
          |""".stripMargin
      )
    }
  }

}
