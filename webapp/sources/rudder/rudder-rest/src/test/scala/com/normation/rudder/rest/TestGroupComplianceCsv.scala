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
import com.normation.rudder.rest.data.ByNodeGroupByRuleDirectiveCompliance
import com.normation.rudder.rest.data.ByNodeGroupCompliance
import com.normation.rudder.rest.data.ByNodeGroupNodeCompliance
import com.normation.rudder.rest.data.ByNodeGroupRuleCompliance
import com.normation.rudder.rest.data.ByNodeRuleCompliance
import com.normation.rudder.rest.data.ByRuleBlockCompliance
import com.normation.rudder.rest.data.ByRuleComponentCompliance
import com.normation.rudder.rest.data.ByRuleNodeCompliance
import com.normation.rudder.rest.data.ByRuleValueCompliance
import com.normation.rudder.rest.data.CsvCompliance
import com.normation.rudder.rest.data.CsvCompliance.NodeGroupComplianceByNodeCsv
import com.normation.rudder.rest.data.CsvCompliance.NodeGroupComplianceByRuleCsv
import com.normation.rudder.tenants.QueryContext
import com.normation.rudder.web.services.ComputePolicyMode
import com.normation.rudder.web.services.ComputePolicyMode.ComputedPolicyMode
import com.normation.utils.Csv.toCsv
import io.scalaland.chimney.syntax.*
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class TestGroupComplianceCsv extends Specification {

  // Utils

  private def buildGroupComplianceByNode(reportsList: List[ComponentStatusReport]): Seq[ByNodeGroupNodeCompliance] = {
    Seq(
      ByNodeGroupNodeCompliance(
        id = NodeId("node1"),
        name = "node1",
        mode = FullCompliance,
        compliance = ComplianceLevel(),
        policyMode = policyMode,
        rules = Seq(
          ByNodeRuleCompliance(
            id = RuleId(RuleUid("rule1")),
            name = "rule1",
            compliance = ComplianceLevel(),
            policyMode = policyMode,
            directives = Seq(
              ByNodeDirectiveCompliance(
                id = DirectiveId(DirectiveUid("directive1")),
                name = "directive1",
                compliance = ComplianceLevel(),
                skippedDetails = None,
                policyMode = policyMode,
                components = reportsList
              )
            )
          )
        )
      )
    )
  }

  private def buildGroupComplianceByRule(components: Seq[ByRuleComponentCompliance]): Seq[ByNodeGroupRuleCompliance] = {
    Seq(
      ByNodeGroupRuleCompliance(
        id = RuleId(RuleUid("ruleId")),
        name = "ruleName",
        compliance = ComplianceLevel(),
        policyMode = policyMode,
        directives = Seq(
          ByNodeGroupByRuleDirectiveCompliance(
            id = DirectiveId(DirectiveUid("directiveId")),
            name = "directiveName",
            compliance = ComplianceLevel(),
            skippedDetails = None,
            policyMode = policyMode,
            components = components
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

  // compliance by node
  private def valueStatusReport(n: Int): ValueStatusReport = {
    ValueStatusReport(
      s"component${n}",
      s"component${n}",
      componentValueStatusReport(n) :: Nil
    )
  }

  // compliance by rule
  private def byRuleValueCompliance(n: Int): ByRuleValueCompliance = {
    ByRuleValueCompliance(
      s"component${n}",
      ComplianceLevel(),
      ByRuleNodeCompliance(
        NodeId("nodeId"),
        "nodeName",
        policyMode,
        ComplianceLevel(),
        componentValueStatusReport(n) :: Nil
      ) :: Nil
    )
  }

  implicit val qc: QueryContext = QueryContext.testQC

  val globalMode: GlobalPolicyMode   = GlobalPolicyMode(Enforce, PolicyModeOverrides.Unoverridable)
  val policyMode: ComputedPolicyMode = ComputePolicyMode.nodeMode(globalMode, None)

  val emptyGroupCompliance: ByNodeGroupCompliance = {
    ByNodeGroupCompliance(
      id = "groupId",
      name = "groupName",
      compliance = ComplianceLevel(),
      mode = FullCompliance,
      rules = Seq.empty,
      nodes = Seq.empty
    )
  }

  val groupCompliance: ByNodeGroupCompliance = {
    ByNodeGroupCompliance(
      id = "groupId",
      name = "groupName",
      compliance = ComplianceLevel(),
      mode = FullCompliance,
      rules = buildGroupComplianceByRule(
        components = Seq(
          byRuleValueCompliance(1),
          byRuleValueCompliance(2)
        )
      ),
      nodes = buildGroupComplianceByNode(
        reportsList = List(
          valueStatusReport(1),
          valueStatusReport(2)
        )
      )
    )
  }

  val nestedBlocksGroupCompliance: ByNodeGroupCompliance = {
    ByNodeGroupCompliance(
      id = "groupId",
      name = "groupName",
      compliance = ComplianceLevel(),
      mode = FullCompliance,
      rules = buildGroupComplianceByRule(
        components = Seq(
          byRuleValueCompliance(1),
          ByRuleBlockCompliance(
            "block1",
            FocusWorst,
            Seq(
              ByRuleBlockCompliance("block2", FocusWorst, Seq(byRuleValueCompliance(2), byRuleValueCompliance(3))),
              ByRuleBlockCompliance("block3", FocusWorst, Seq(byRuleValueCompliance(4), byRuleValueCompliance(5)))
            )
          )
        )
      ),
      nodes = buildGroupComplianceByNode(
        reportsList = List(
          valueStatusReport(1),
          BlockStatusReport(
            "block1",
            FocusWorst,
            List(
              BlockStatusReport(
                "block2",
                FocusWorst,
                List(
                  BlockStatusReport(
                    "block3",
                    FocusWorst,
                    List(valueStatusReport(4))
                  ),
                  valueStatusReport(3)
                )
              ),
              valueStatusReport(2)
            )
          )
        )
      )
    )
  }

  sequential

  ///// tests ////

  "Group compliance " in {
    "by node " in {
      "with empty node compliance should return a CSV that only contains the column names" in {
        val compliance = emptyGroupCompliance.nodes.transformInto[Seq[NodeGroupComplianceByNodeCsv]]

        compliance.toCsv.mustEqual(
          """"Node","Rule","Directive","Block","Component","Value","Status","Message"
            |""".stripMargin
        )
      }
      "with non-empty node compliance should return the expected compliance CSV" in {
        val compliance = groupCompliance.nodes.transformInto[Seq[NodeGroupComplianceByNodeCsv]]

        compliance.toCsv.mustEqual(
          """"Node","Rule","Directive","Block","Component","Value","Status","Message"
            |"node1","rule1","directive1","","component1","component1-value","auditCompliant","component1 compliant"
            |"node1","rule1","directive1","","component2","component2-value","auditCompliant","component2 compliant"
            |""".stripMargin
        )
      }
      "with non-empty node compliance with nested component blocks should return the expected compliance CSV" in {
        val compliance = nestedBlocksGroupCompliance.nodes.transformInto[Seq[NodeGroupComplianceByNodeCsv]]

        compliance.toCsv.mustEqual(
          """"Node","Rule","Directive","Block","Component","Value","Status","Message"
            |"node1","rule1","directive1","","component1","component1-value","auditCompliant","component1 compliant"
            |"node1","rule1","directive1","block1,block2,block3","component4","component4-value","auditCompliant","component4 compliant"
            |"node1","rule1","directive1","block1,block2","component3","component3-value","auditCompliant","component3 compliant"
            |"node1","rule1","directive1","block1","component2","component2-value","auditCompliant","component2 compliant"
            |""".stripMargin
        )
      }
    }
    "by rule " in {
      "with empty rule compliance should return a CSV that only contains the column names" in {
        val compliance = emptyGroupCompliance.rules.transformInto[Seq[NodeGroupComplianceByRuleCsv]]

        compliance.toCsv.mustEqual(
          """"Rule","Directive","Block","Component","Node","Value","Status","Message"
            |""".stripMargin
        )
      }
      "with non-empty rule compliance should return the expected compliance CSV" in {
        val compliance = groupCompliance.rules.transformInto[Seq[NodeGroupComplianceByRuleCsv]]

        compliance.toCsv.mustEqual(
          """"Rule","Directive","Block","Component","Node","Value","Status","Message"
            |"ruleName","directiveName","","component1","nodeName","component1-value","auditCompliant","component1 compliant"
            |"ruleName","directiveName","","component2","nodeName","component2-value","auditCompliant","component2 compliant"
            |""".stripMargin
        )
      }
      "with non-empty rule compliance with nested component blocks should return the expected compliance CSV" in {
        val compliance = nestedBlocksGroupCompliance.rules.transformInto[Seq[NodeGroupComplianceByRuleCsv]]

        compliance.toCsv.mustEqual(
          """"Rule","Directive","Block","Component","Node","Value","Status","Message"
            |"ruleName","directiveName","","component1","nodeName","component1-value","auditCompliant","component1 compliant"
            |"ruleName","directiveName","block1,block2","component2","nodeName","component2-value","auditCompliant","component2 compliant"
            |"ruleName","directiveName","block1,block2","component3","nodeName","component3-value","auditCompliant","component3 compliant"
            |"ruleName","directiveName","block1,block3","component4","nodeName","component4-value","auditCompliant","component4 compliant"
            |"ruleName","directiveName","block1,block3","component5","nodeName","component5-value","auditCompliant","component5 compliant"
            |""".stripMargin
        )
      }
    }
  }

}
