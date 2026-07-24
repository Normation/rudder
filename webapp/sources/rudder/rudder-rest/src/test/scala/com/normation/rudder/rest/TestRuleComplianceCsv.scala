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
import com.normation.rudder.domain.reports.ComplianceLevel
import com.normation.rudder.domain.reports.ComponentValueStatusReport
import com.normation.rudder.domain.reports.MessageStatusReport
import com.normation.rudder.domain.reports.ReportType
import com.normation.rudder.reports.ComplianceModeName.FullCompliance
import com.normation.rudder.rest.data.ByRuleBlockCompliance
import com.normation.rudder.rest.data.ByRuleDirectiveCompliance
import com.normation.rudder.rest.data.ByRuleNodeCompliance
import com.normation.rudder.rest.data.ByRuleRuleCompliance
import com.normation.rudder.rest.data.ByRuleValueCompliance
import com.normation.rudder.rest.data.CsvCompliance
import com.normation.rudder.rest.data.CsvCompliance.RuleComplianceByDirectiveCsv
import com.normation.rudder.rest.data.CsvCompliance.RuleComplianceByNodeCsv
import com.normation.rudder.web.services.ComputePolicyMode
import com.normation.rudder.web.services.ComputePolicyMode.ComputedPolicyMode
import com.normation.utils.Csv.toCsv
import io.scalaland.chimney.syntax.*
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class TestRuleComplianceCsv extends Specification {

  private def componentValueStatusReport(n: Int): ComponentValueStatusReport = {
    ComponentValueStatusReport("", "", "", MessageStatusReport(ReportType.AuditCompliant, "") :: Nil)
  }

  private def byRuleValueCompliance(n: Int): ByRuleValueCompliance = {
    ByRuleValueCompliance(
      s"component${n}",
      ComplianceLevel(),
      ByRuleNodeCompliance(
        NodeId("dummy"),
        "dummy",
        policyMode,
        ComplianceLevel(),
        componentValueStatusReport(n) :: Nil
      ) :: Nil
    )
  }

  sequential

  ///// tests ////

  val globalMode: GlobalPolicyMode   = GlobalPolicyMode(Enforce, PolicyModeOverrides.Unoverridable)
  val policyMode: ComputedPolicyMode = ComputePolicyMode.nodeMode(globalMode, None)

  val emptyRuleCompliance:  ByRuleRuleCompliance = {
    ByRuleRuleCompliance(
      id = RuleId(RuleUid("dummy")),
      name = "dummy",
      compliance = ComplianceLevel(),
      mode = FullCompliance,
      policyMode = policyMode,
      directives = Seq()
    )
  }
  val ruleCompliance:       ByRuleRuleCompliance = ByRuleRuleCompliance(
    id = RuleId(RuleUid("dummy")),
    name = "dummy",
    compliance = ComplianceLevel(),
    mode = FullCompliance,
    policyMode = policyMode,
    directives = Seq(
      ByRuleDirectiveCompliance(
        id = DirectiveId(DirectiveUid("dummy")),
        name = "dummy",
        compliance = ComplianceLevel(),
        skippedDetails = None,
        policyMode = policyMode,
        components = Seq(
          byRuleValueCompliance(1),
          byRuleValueCompliance(2),
          byRuleValueCompliance(3)
        )
      )
    )
  )
  val nestedRuleCompliance: ByRuleRuleCompliance = ByRuleRuleCompliance(
    id = RuleId(RuleUid("dummy")),
    name = "dummy",
    compliance = ComplianceLevel(),
    mode = FullCompliance,
    policyMode = policyMode,
    directives = Seq(
      ByRuleDirectiveCompliance(
        id = DirectiveId(DirectiveUid("dummy")),
        name = "dummy",
        compliance = ComplianceLevel(),
        skippedDetails = None,
        policyMode = policyMode,
        components = Seq(
          ByRuleBlockCompliance(
            "block1",
            FocusWorst,
            Seq(
              ByRuleBlockCompliance(
                "block2",
                FocusWorst,
                Seq(
                  ByRuleBlockCompliance(
                    "block4",
                    FocusWorst,
                    Seq(byRuleValueCompliance(1))
                  ),
                  ByRuleBlockCompliance(
                    "block5",
                    FocusWorst,
                    Seq(byRuleValueCompliance(2))
                  )
                )
              ),
              ByRuleBlockCompliance(
                "block3",
                FocusWorst,
                Seq(
                  ByRuleBlockCompliance(
                    "block6",
                    FocusWorst,
                    Seq(
                      byRuleValueCompliance(3),
                      byRuleValueCompliance(4)
                    )
                  )
                )
              ),
              byRuleValueCompliance(5)
            )
          )
        )
      )
    )
  )

  val nodesCsv = """"Node","Directive","Block","Component","Value","Status","Message"
                   |"dummy","dummy","","component1","","auditCompliant",""
                   |"dummy","dummy","","component2","","auditCompliant",""
                   |"dummy","dummy","","component3","","auditCompliant",""
                   |""".stripMargin

  val nestedNodesCsv = """"Node","Directive","Block","Component","Value","Status","Message"
                         |"dummy","dummy","block1,block2,block4","component1","","auditCompliant",""
                         |"dummy","dummy","block1,block2,block5","component2","","auditCompliant",""
                         |"dummy","dummy","block1,block3,block6","component3","","auditCompliant",""
                         |"dummy","dummy","block1,block3,block6","component4","","auditCompliant",""
                         |"dummy","dummy","block1","component5","","auditCompliant",""
                         |""".stripMargin

  val directivesTree = {
    """+- component1
      |+- component2
      |\- component3""".stripMargin
  }

  val directivesCsv = """"Directive","Block","Component","Node","Value","Status","Message"
                        |"dummy","","component1","dummy","","auditCompliant",""
                        |"dummy","","component2","dummy","","auditCompliant",""
                        |"dummy","","component3","dummy","","auditCompliant",""
                        |""".stripMargin

  val nestedDirectivesTree = {
    """+- block1
      ||  +- block2
      ||  |  +- block4
      ||  |  |  \- component1
      ||  |  \- block5
      ||  |     \- component2
      ||  \- block3
      ||  |  \- block6
      ||  |     +- component3
      ||  |     \- component4
      ||  \- component5
      ||""".stripMargin
  }

  val nestedDirectivesCsv = """"Directive","Block","Component","Node","Value","Status","Message"
                              |"dummy","block1,block2,block4","component1","dummy","","auditCompliant",""
                              |"dummy","block1,block2,block5","component2","dummy","","auditCompliant",""
                              |"dummy","block1,block3,block6","component3","dummy","","auditCompliant",""
                              |"dummy","block1,block3,block6","component4","dummy","","auditCompliant",""
                              |"dummy","block1","component5","dummy","","auditCompliant",""
                              |""".stripMargin

  "Rule compliance " in {
    "by node " in {
      "with empty compliance information should produce a CSV file that only contains the column names when exported" in {
        val compliance = emptyRuleCompliance.transformInto[Seq[RuleComplianceByNodeCsv]]
        compliance.toCsv.mustEqual(""""Node","Directive","Block","Component","Value","Status","Message"
                                     |""".stripMargin)
      }
      (s"""with directive compliance tree
          |
          |${directivesTree}
          |
          |should produce the following CSV :
          |
          |${nodesCsv}""".stripMargin) in {
        val compliance = ruleCompliance.transformInto[Seq[RuleComplianceByNodeCsv]]
        compliance.toCsv.mustEqual(nodesCsv)
      }
      (s"""with directive compliance tree with nested blocks
          |
          |${nestedDirectivesTree}
          |
          |should produce the following CSV :
          |
          |${nestedNodesCsv}""".stripMargin) in {
        val compliance = nestedRuleCompliance.transformInto[Seq[RuleComplianceByNodeCsv]]
        compliance.toCsv.mustEqual(nestedNodesCsv)
      }
    }
    "by directive " in {
      "with empty compliance information should produce a CSV file that only contains the column names when exported" in {
        val compliance = emptyRuleCompliance.transformInto[Seq[RuleComplianceByDirectiveCsv]]
        compliance.toCsv.mustEqual(""""Directive","Block","Component","Node","Value","Status","Message"
                                     |""".stripMargin)
      }
      (s"""with directive compliance tree
          |
          |${directivesTree}
          |
          |should produce the following CSV :
          |
          |${directivesCsv}""".stripMargin) in {
        val compliance = ruleCompliance.transformInto[Seq[RuleComplianceByDirectiveCsv]]
        compliance.toCsv.mustEqual(directivesCsv)
      }
      (s"""with directive compliance tree with nested blocks
          |
          |${nestedDirectivesTree}
          |
          |should produce the following CSV :
          |
          |${nestedDirectivesCsv}""".stripMargin) in {
        val compliance = nestedRuleCompliance.transformInto[Seq[RuleComplianceByDirectiveCsv]]
        compliance.toCsv.mustEqual(nestedDirectivesCsv)
      }
    }
  }

}
