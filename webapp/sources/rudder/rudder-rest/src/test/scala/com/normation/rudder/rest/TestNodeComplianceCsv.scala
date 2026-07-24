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
          id = RuleId(RuleUid("dummy")),
          name = "dummy",
          compliance = ComplianceLevel(),
          policyMode = policyMode,
          directives = Seq(
            ByNodeDirectiveCompliance(
              id = DirectiveId(DirectiveUid("dummy")),
              name = "dummy",
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
    ComponentValueStatusReport("", "", "", MessageStatusReport(ReportType.AuditCompliant, s"") :: Nil)
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
      id = NodeId("dummy"),
      name = "dummy",
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

  val nodesTree = {
    """+- component1
      |+- component2
      |\- component3""".stripMargin
  }

  val nodesCsv = """"Rule","Directive","Block","Component","Value","Status","Message"
                   |"dummy","dummy","","component1","","auditCompliant",""
                   |"dummy","dummy","","component2","","auditCompliant",""
                   |"dummy","dummy","","component3","","auditCompliant",""
                   |""".stripMargin

  val nestedNodesTree = {
    """+- component1
      ||\- block1
      ||  +- component2
      ||  \- block2
      ||     +- component3
      ||     +- block3
      ||     |  \- component4
      ||     \- block4
      ||        +- component5
      ||        \- component6
      ||""".stripMargin
  }

  val nestedNodesCsv = {
    """"Rule","Directive","Block","Component","Value","Status","Message"
      |"dummy","dummy","","component1","","auditCompliant",""
      |"dummy","dummy","block1","component2","","auditCompliant",""
      |"dummy","dummy","block1,block2","component3","","auditCompliant",""
      |"dummy","dummy","block1,block2,block3","component4","","auditCompliant",""
      |"dummy","dummy","block1,block2,block4","component5","","auditCompliant",""
      |"dummy","dummy","block1,block2,block4","component6","","auditCompliant",""
      |""".stripMargin
  }

  "Node compliance " in {
    "with empty compliance information should produce a CSV file that only contains the column names when exported" in {

      val compliance = emptyNodeCompliance.nodeCompliances.transformInto[Seq[NodeComplianceByRuleCsv]]
      compliance.toCsv.mustEqual(""""Rule","Directive","Block","Component","Value","Status","Message"
                                   |""".stripMargin)
    }
    (s"""with compliance tree
        |
        |${nodesTree}
        |
        |should produce the following CSV :
        |
        |${nodesCsv}""".stripMargin) in {
      val compliance = nodeCompliance.nodeCompliances.transformInto[Seq[NodeComplianceByRuleCsv]]
      compliance.toCsv.mustEqual(nodesCsv)
    }
    (s"""with compliance tree with nested blocks
        |
        |${nestedNodesTree}
        |
        |should produce the following CSV :
        |
        |${nestedNodesCsv}""".stripMargin) in {
      val compliance = nestedNodeCompliance.nodeCompliances.transformInto[Seq[NodeComplianceByRuleCsv]]
      compliance.toCsv.mustEqual(nestedNodesCsv)
    }
  }

}
