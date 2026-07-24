/*
 *************************************************************************************
 * Copyright 2023 Normation SAS
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

import com.normation.cfclerk.domain.ReportingLogic
import com.normation.cfclerk.domain.ReportingLogic.FocusWorst
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.DirectiveUid
import com.normation.rudder.domain.policies.GlobalPolicyMode
import com.normation.rudder.domain.policies.PolicyMode
import com.normation.rudder.domain.policies.PolicyModeOverrides
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.RuleUid
import com.normation.rudder.domain.reports.ComplianceLevel
import com.normation.rudder.domain.reports.ComponentValueStatusReport
import com.normation.rudder.domain.reports.MessageStatusReport
import com.normation.rudder.domain.reports.ReportType
import com.normation.rudder.reports.ComplianceModeName.FullCompliance
import com.normation.rudder.rest.data.ByDirectiveByRuleCompliance
import com.normation.rudder.rest.data.ByDirectiveCompliance
import com.normation.rudder.rest.data.ByRuleBlockCompliance
import com.normation.rudder.rest.data.ByRuleNodeCompliance
import com.normation.rudder.rest.data.ByRuleValueCompliance
import com.normation.rudder.rest.data.CsvCompliance
import com.normation.rudder.rest.data.CsvCompliance.CsvDirectiveCompliance
import com.normation.rudder.rest.data.CsvCompliance.DirectiveComplianceByNodeCsv
import com.normation.rudder.web.services.ComputePolicyMode
import com.normation.utils.Csv.toCsv
import io.scalaland.chimney.syntax.*
import org.junit.runner.RunWith
import org.specs2.mutable.*
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class TestDirectiveComplianceCsv extends Specification {

  sequential

  ///// tests ////

  /*
"Basic hardening on all systems", "Check Cipher TLS_RSA_WITH_DES_CBC_SHA", "Command execution", "prod-www-01.lab.rudder.io", "Disable-TlsCipherSuite -Name "TLS_RSA_WITH_DES_CBC_SHA"", "auditNotApplicable", "Skipping method 'Command execution' with key parameter 'Disable-TlsCipherSuite -Name "TLS_RSA_WITH_DES_CBC_SHA"' since condition 'windows.audit_from_powershell_execution__Get_TlsCipherSuite__Name__TLS_RSA_WITH_DES_CBC_SHA___Count_error' is not reached was not applicable"
"Basic hardening on all systems", "Check Cipher TLS_RSA_WITH_DES_CBC_SHA", "Command execution", "prod-infra-01.lab.rudder.io", "Disable-TlsCipherSuite -Name "TLS_RSA_WITH_DES_CBC_SHA"", "auditNotApplicable", "Skipping method 'Command execution' with key parameter 'Disable-TlsCipherSuite -Name "TLS_RSA_WITH_DES_CBC_SHA"' since condition 'windows.audit_from_powershell_execution__Get_TlsCipherSuite__Name__TLS_RSA_WITH_DES_CBC_SHA___Count_error' is not reached was not applicable"
"Basic hardening on all systems", "Check Cipher TLS_RSA_WITH_DES_CBC_SHA", "Command execution", "dev-www-01.lab.rudder.io", "Disable-TlsCipherSuite -Name "TLS_RSA_WITH_DES_CBC_SHA"", "auditNotApplicable", "Skipping method 'Command execution' with key parameter 'Disable-TlsCipherSuite -Name "TLS_RSA_WITH_DES_CBC_SHA"' since condition 'windows.audit_from_powershell_execution__Get_TlsCipherSuite__Name__TLS_RSA_WITH_DES_CBC_SHA___Count_error' is not reached was not applicable"
"Basic hardening on all systems", "Check Cipher TLS_RSA_WITH_DES_CBC_SHA", "Command execution", "prod-db-01.lab.rudder.io", "Disable-TlsCipherSuite -Name "TLS_RSA_WITH_DES_CBC_SHA"", "auditNotApplicable", "Skipping method 'Command execution' with key parameter 'Disable-TlsCipherSuite -Name "TLS_RSA_WITH_DES_CBC_SHA"' since condition 'windows.audit_from_powershell_execution__Get_TlsCipherSuite__Name__TLS_RSA_WITH_DES_CBC_SHA___Count_error' is not reached was not applicable"
"Basic hardening on all systems", "Check Cipher TLS_RSA_WITH_DES_CBC_SHA", "Command execution", "prod-zabbix-01.lab.rudder.io", "Disable-TlsCipherSuite -Name "TLS_RSA_WITH_DES_CBC_SHA"", "auditNotApplicable", "Skipping method 'Command execution' with key parameter 'Disable-TlsCipherSuite -Name "TLS_RSA_WITH_DES_CBC_SHA"' since condition 'windows.audit_from_powershell_execution__Get_TlsCipherSuite__Name__TLS_RSA_WITH_DES_CBC_SHA___Count_error' is not reached was not applicable"
"Basic hardening on all systems", "Check Cipher TLS_RSA_WITH_DES_CBC_SHA", "Command execution", "prod-app-02.lab.rudder.io", "Disable-TlsCipherSuite -Name "TLS_RSA_WITH_DES_CBC_SHA"", "auditNotApplicable", "Skipping method 'Command execution' with key parameter 'Disable-TlsCipherSuite -Name "TLS_RSA_WITH_DES_CBC_SHA"' since condition 'windows.audit_from_powershell_execution__Get_TlsCipherSuite__Name__TLS_RSA_WITH_DES_CBC_SHA___Count_error' is not reached was not applicable"
"Basic hardening on all systems", "Check Cipher TLS_RSA_WITH_DES_CBC_SHA", "Command execution", "prod-windows-2016.demo.normation.com", "Disable-TlsCipherSuite -Name "TLS_RSA_WITH_DES_CBC_SHA"", "auditNotApplicable", "Not applicable"
"Basic hardening on all systems", "Check Cipher TLS_RSA_WITH_DES_CBC_SHA", "Command execution", "prod-itop-01.lab.rudder.io", "Disable-TlsCipherSuite -Name "TLS_RSA_WITH_DES_CBC_SHA"", "auditNotApplicable", "Skipping method 'Command execution' with key parameter 'Disable-TlsCipherSuite -Name "TLS_RSA_WITH_DES_CBC_SHA"' since condition 'windows.audit_from_powershell_execution__Get_TlsCipherSuite__Name__TLS_RSA_WITH_DES_CBC_SHA___Count_error' is not reached was not applicable"
"Basic hardening on all systems", "Check Cipher TLS_RSA_WITH_DES_CBC_SHA", "Audit from Powershell execution", "prod-app-01.lab.rudder.io", "(Get-TlsCipherSuite -Name "TLS_RSA_WITH_DES_CBC_SHA").Count", "auditNotApplicable", "'Audit from Powershell execution' method is not available on Linux Rudder agent, skip was not applicable"
"Basic hardening on all systems", "Check Cipher TLS_RSA_WITH_DES_CBC_SHA", "Audit from Powershell execution", "prod-www-02.lab.rudder.io", "(Get-TlsCipherSuite -Name "TLS_RSA_WITH_DES_CBC_SHA").Count", "auditNotApplicable", "'Audit from Powershell execution' method is not available on Linux Rudder agent, skip was not applicable"
"Basic hardening on all systems", "Check Cipher TLS_RSA_WITH_DES_CBC_SHA", "Audit from Powershell execution", "rudder.demo.normation.com", "(Get-TlsCipherSuite -Name "TLS_RSA_WITH_DES_CBC_SHA").Count", "auditNotApplicable", "'Audit from Powershell execution' method is not available on Linux Rudder agent, skip was not applicable"
"Basic hardening on all systems", "Check Cipher TLS_RSA_WITH_DES_CBC_SHA", "Audit from Powershell execution", "prod-www-01.lab.rudder.io", "(Get-TlsCipherSuite -Name "TLS_RSA_WITH_DES_CBC_SHA").Count", "auditNotApplicable", "'Audit from Powershell execution' method is not available on Linux Rudder agent, skip was not applicable"
"Basic hardening on all systems", "Check Cipher TLS_RSA_WITH_DES_CBC_SHA", "Audit from Powershell execution", "prod-infra-01.lab.rudder.io", "(Get-TlsCipherSuite -Name "TLS_RSA_WITH_DES_CBC_SHA").Count", "auditNotApplicable", "'Audit from Powershell execution' method is not available on Linux Rudder agent, skip was not applicable"
"Basic hardening on all systems", "Check Cipher TLS_RSA_WITH_DES_CBC_SHA", "Audit from Powershell execution", "dev-www-01.lab.rudder.io", "(Get-TlsCipherSuite -Name "TLS_RSA_WITH_DES_CBC_SHA").Count", "auditNotApplicable", "'Audit from Powershell execution' method is not available on Linux Rudder agent, skip was not applicable"
"Basic hardening on all systems", "Check Cipher TLS_RSA_WITH_DES_CBC_SHA", "Audit from Powershell execution", "prod-db-01.lab.rudder.io", "(Get-TlsCipherSuite -Name "TLS_RSA_WITH_DES_CBC_SHA").Count", "auditNotApplicable", "'Audit from Powershell execution' method is not available on Linux Rudder agent, skip was not applicable"
"Basic hardening on all systems", "Check Cipher TLS_RSA_WITH_DES_CBC_SHA", "Audit from Powershell execution", "prod-zabbix-01.lab.rudder.io", "(Get-TlsCipherSuite -Name "TLS_RSA_WITH_DES_CBC_SHA").Count", "auditNotApplicable", "'Audit from Powershell execution' method is not available on Linux Rudder agent, skip was not applicable"
"Basic hardening on all systems", "Check Cipher TLS_RSA_WITH_DES_CBC_SHA", "Audit from Powershell execution", "prod-app-02.lab.rudder.io", "(Get-TlsCipherSuite -Name "TLS_RSA_WITH_DES_CBC_SHA").Count", "auditNotApplicable", "'Audit from Powershell execution' method is not available on Linux Rudder agent, skip was not applicable"
"Basic hardening on all systems", "Check Cipher TLS_RSA_WITH_DES_CBC_SHA", "Audit from Powershell execution", "prod-windows-2016.demo.normation.com", "(Get-TlsCipherSuite -Name "TLS_RSA_WITH_DES_CBC_SHA").Count", "auditCompliant", "Command '(Get-TlsCipherSuite -Name "TLS_RSA_WITH_DES_CBC_SHA").Count' was executed successfully and matched the success string '0'"
"Basic hardening on all systems", "Check Cipher TLS_RSA_WITH_DES_CBC_SHA", "Audit from Powershell execution", "prod-itop-01.lab.rudder.io", "(Get-TlsCipherSuite -Name "TLS_RSA_WITH_DES_CBC_SHA").Count", "auditNotApplicable", "'Audit from Powershell execution' method is not available on Linux Rudder agent, skip was not applicable"
"Basic hardening on all systems", "Check Cipher TLS_PSK_WITH_NULL_SHA384 ", "Command execution", "prod-app-01.lab.rudder.io", "Disable-TlsCipherSuite -Name "TLS_PSK_WITH_NULL_SHA384"", "auditNotApplicable", "Skipping method 'Command execution' with key parameter 'Disable-TlsCipherSuite -Name "TLS_PSK_WITH_NULL_SHA384"' since condition 'windows.audit_from_powershell_execution__Get_TlsCipherSuite__Name__TLS_PSK_WITH_NULL_SHA384___Count_error' is not reached was not applicable"
"Basic hardening on all systems", "Check Cipher TLS_PSK_WITH_NULL_SHA384 ", "Command execution", "prod-www-02.lab.rudder.io", "Disable-TlsCipherSuite -Name "TLS_PSK_WITH_NULL_SHA384"", "auditNotApplicable", "Skipping method 'Command execution' with key parameter 'Disable-TlsCipherSuite -Name "TLS_PSK_WITH_NULL_SHA384"' since condition 'windows.audit_from_powershell_execution__Get_TlsCipherSuite__Name__TLS_PSK_WITH_NULL_SHA384___Count_error' is not reached was not applicable"
   */

  val enforce = ComputePolicyMode.global(GlobalPolicyMode(PolicyMode.Enforce, PolicyModeOverrides.Always))
  val notUsed = ComplianceLevel()

  // Utils

  private def componentValueStatusReport(n: Int): ComponentValueStatusReport = {
    ComponentValueStatusReport("", "", "", MessageStatusReport(ReportType.AuditCompliant, "") :: Nil)
  }

  // compliance by rule
  private def byRuleValueCompliance(n: Int): ByRuleValueCompliance = {
    ByRuleValueCompliance(
      s"component${n}",
      ComplianceLevel(),
      ByRuleNodeCompliance(
        NodeId("dummy"),
        "dummy",
        enforce,
        ComplianceLevel(),
        componentValueStatusReport(n) :: Nil
      ) :: Nil
    )
  }

  val emptyDirectiveCompliance = ByDirectiveCompliance(
    DirectiveId(DirectiveUid("dummy")),
    "dummy",
    notUsed,
    FullCompliance,
    enforce,
    Seq()
  )

  val directiveCompliance = ByDirectiveCompliance(
    DirectiveId(DirectiveUid("dummy")),
    "dummy",
    notUsed,
    FullCompliance,
    enforce,
    Seq(
      ByDirectiveByRuleCompliance(
        id = RuleId(RuleUid("dummy")),
        name = "dummy",
        compliance = notUsed,
        skippedDetails = None,
        policyMode = enforce,
        components = Seq(
          byRuleValueCompliance(1),
          byRuleValueCompliance(2),
          byRuleValueCompliance(3),
          byRuleValueCompliance(4)
        )
      )
    )
  )

  val nestedDirectiveCompliance = ByDirectiveCompliance(
    DirectiveId(DirectiveUid("dummy")),
    "dummy",
    notUsed,
    FullCompliance,
    enforce,
    Seq(
      ByDirectiveByRuleCompliance(
        id = RuleId(RuleUid("dummy")),
        name = "dummy",
        compliance = notUsed,
        skippedDetails = None,
        policyMode = enforce,
        components = Seq(
          ByRuleBlockCompliance(
            "block1",
            FocusWorst,
            Seq(
              ByRuleBlockCompliance(
                "block2",
                FocusWorst,
                Seq(
                  ByRuleBlockCompliance("block3", FocusWorst, Seq(byRuleValueCompliance(1))),
                  ByRuleBlockCompliance("block4", FocusWorst, Seq(byRuleValueCompliance(2))),
                  byRuleValueCompliance(3)
                )
              ),
              byRuleValueCompliance(4)
            )
          ),
          byRuleValueCompliance(5),
          ByRuleBlockCompliance(
            "block5",
            FocusWorst,
            Seq(
              byRuleValueCompliance(6),
              ByRuleBlockCompliance("block6", FocusWorst, Seq(byRuleValueCompliance(7))),
              ByRuleBlockCompliance("block7", FocusWorst, Seq(byRuleValueCompliance(8))),
              byRuleValueCompliance(9)
            )
          ),
          byRuleValueCompliance(10)
        )
      )
    )
  )

  val directiveComplianceSpecialChars = ByDirectiveCompliance(
    DirectiveId(DirectiveUid("d1")),
    "directive 1",
    notUsed,
    FullCompliance,
    enforce,
    Seq(
      ByDirectiveByRuleCompliance(
        RuleId(RuleUid("r1")),
        "Basic hardening on all systems",
        notUsed,
        None,
        enforce,
        Seq(
          ByRuleBlockCompliance(
            "Check Cipher TLS_RSA_WITH_DES_CBC_SHA",
            ReportingLogic.WeightedReport,
            Seq(
              ByRuleValueCompliance(
                "Command execution",
                notUsed,
                List(
                  ByRuleNodeCompliance(
                    NodeId("n1"),
                    "prod-www-01.lab.rudder.io",
                    enforce,
                    notUsed,
                    Seq(
                      ComponentValueStatusReport(
                        "Disable-TlsCipherSuite -Name \"TLS_RSA_WITH_DES_CBC_SHA\" ",
                        "",
                        "0",
                        List(
                          MessageStatusReport(
                            ReportType.AuditNotApplicable,
                            Some(
                              "Skipping method 'Command execution' with key parameter 'Disable-TlsCipherSuite -Name \"TLS_RSA_WITH_DES_CBC_SHA\"' since condition 'windows.audit_from_powershell_execution__Get_TlsCipherSuite__Name__TLS_RSA_WITH_DES_CBC_SHA___Count_error' is not reached was not applicable"
                            )
                          )
                        )
                      )
                    )
                  ),
                  ByRuleNodeCompliance(
                    NodeId("n1"),
                    "prod-windows-2016.demo.normation.com",
                    enforce,
                    notUsed,
                    Seq(
                      ComponentValueStatusReport(
                        "Disable-TlsCipherSuite -Name \"TLS_RSA_WITH_DES_CBC_SHA\" ",
                        "",
                        "0",
                        List(
                          MessageStatusReport(
                            ReportType.AuditNotApplicable,
                            Some(
                              "Not applicable"
                            )
                          )
                        )
                      )
                    )
                  )
                )
              ),
              ByRuleValueCompliance(
                "Audit from Powershell execution",
                notUsed,
                List(
                  ByRuleNodeCompliance(
                    NodeId("n1"),
                    "prod-app-01.lab.rudder.io",
                    enforce,
                    notUsed,
                    Seq(
                      ComponentValueStatusReport(
                        "(Get-TlsCipherSuite -Name \"TLS_RSA_WITH_DES_CBC_SHA\").Count",
                        "",
                        "0",
                        List(
                          MessageStatusReport(
                            ReportType.AuditNotApplicable,
                            Some(
                              "'Audit from Powershell execution' method is not available on Linux Rudder agent, skip was not applicable"
                            )
                          )
                        )
                      )
                    )
                  ),
                  ByRuleNodeCompliance(
                    NodeId("n1"),
                    "prod-windows-2016.demo.normation.com",
                    enforce,
                    notUsed,
                    Seq(
                      ComponentValueStatusReport(
                        "(Get-TlsCipherSuite -Name \"TLS_RSA_WITH_DES_CBC_SHA\").Count",
                        "",
                        "0",
                        List(
                          MessageStatusReport(
                            ReportType.AuditCompliant,
                            Some(
                              "Command '(Get-TlsCipherSuite -Name \"TLS_RSA_WITH_DES_CBC_SHA\").Count' was executed successfully and matched the success string '0'"
                            )
                          )
                        )
                      )
                    )
                  )
                )
              )
            )
          )
        )
      )
    )
  )

  val nodesCsv = """"Node","Rule","Block","Component","Value","Status","Message"
                   |"dummy","dummy","","component1","","auditCompliant",""
                   |"dummy","dummy","","component2","","auditCompliant",""
                   |"dummy","dummy","","component3","","auditCompliant",""
                   |"dummy","dummy","","component4","","auditCompliant",""
                   |""".stripMargin

  val nestedNodesCsv = """"Node","Rule","Block","Component","Value","Status","Message"
                         |"dummy","dummy","block1,block2,block3","component1","","auditCompliant",""
                         |"dummy","dummy","block1,block2,block4","component2","","auditCompliant",""
                         |"dummy","dummy","block1,block2","component3","","auditCompliant",""
                         |"dummy","dummy","block1","component4","","auditCompliant",""
                         |"dummy","dummy","","component5","","auditCompliant",""
                         |"dummy","dummy","block5","component6","","auditCompliant",""
                         |"dummy","dummy","block5,block6","component7","","auditCompliant",""
                         |"dummy","dummy","block5,block7","component8","","auditCompliant",""
                         |"dummy","dummy","block5","component9","","auditCompliant",""
                         |"dummy","dummy","","component10","","auditCompliant",""
                         |""".stripMargin

  val rulesTree = {
    """+- component1
      |+- component2
      |+- component3
      |\- component4""".stripMargin
  }

  val rulesCsv = """"Rule","Block","Component","Node","Value","Status","Message"
                   |"dummy","","component1","dummy","","auditCompliant",""
                   |"dummy","","component2","dummy","","auditCompliant",""
                   |"dummy","","component3","dummy","","auditCompliant",""
                   |"dummy","","component4","dummy","","auditCompliant",""
                   |""".stripMargin

  val nestedRulesTree = {
    """+- block1
      |||  +- block2
      |||  |  +- block3
      |||  |  |  \- component1
      |||  |  +- block4
      |||  |  |  \- component2
      |||  |  \- component3
      |||  \- component4
      ||+- component5
      ||+- block5
      |||  +- component6
      |||  +- block6
      |||  |  \- component7
      |||  +- block7
      |||  |  \- component8
      |||  \- component9
      ||\- component10
      ||""".stripMargin
  }

  val nestedRulesCsv = """"Rule","Block","Component","Node","Value","Status","Message"
                         |"dummy","block1,block2,block3","component1","dummy","","auditCompliant",""
                         |"dummy","block1,block2,block4","component2","dummy","","auditCompliant",""
                         |"dummy","block1,block2","component3","dummy","","auditCompliant",""
                         |"dummy","block1","component4","dummy","","auditCompliant",""
                         |"dummy","","component5","dummy","","auditCompliant",""
                         |"dummy","block5","component6","dummy","","auditCompliant",""
                         |"dummy","block5,block6","component7","dummy","","auditCompliant",""
                         |"dummy","block5,block7","component8","dummy","","auditCompliant",""
                         |"dummy","block5","component9","dummy","","auditCompliant",""
                         |"dummy","","component10","dummy","","auditCompliant",""
                         |""".stripMargin

  "Directive compliance " in {
    "by rule " in {
      "with empty rule compliance should produce a CSV that only contains the column names" in {
        emptyDirectiveCompliance.toCsv.mustEqual(
          """"Rule","Block","Component","Node","Value","Status","Message"
            |""".stripMargin
        )
      }
      (s"""with rule compliance tree
          |
          |${rulesTree}
          |
          |should produce the following CSV : 
          |
          |${rulesCsv}""".stripMargin) in {
        val compliance = directiveCompliance

        compliance.toCsv.mustEqual(rulesCsv)
      }
      (s"""with rule compliance tree with nested blocks
          |
          |${nestedRulesTree}
          |
          |should produce the following CSV : 
          |
          |${nestedRulesCsv}""".stripMargin) in {
        val compliance = nestedDirectiveCompliance

        compliance.toCsv.mustEqual(nestedRulesCsv)
      }

      "should correctly escape special characters" in {
        directiveComplianceSpecialChars.toCsv must beEqualTo(
          """"Rule","Block","Component","Node","Value","Status","Message"
            |"Basic hardening on all systems","Check Cipher TLS_RSA_WITH_DES_CBC_SHA","Command execution","prod-www-01.lab.rudder.io","Disable-TlsCipherSuite -Name ""TLS_RSA_WITH_DES_CBC_SHA"" ","auditNotApplicable","Skipping method 'Command execution' with key parameter 'Disable-TlsCipherSuite -Name ""TLS_RSA_WITH_DES_CBC_SHA""' since condition 'windows.audit_from_powershell_execution__Get_TlsCipherSuite__Name__TLS_RSA_WITH_DES_CBC_SHA___Count_error' is not reached was not applicable"
            |"Basic hardening on all systems","Check Cipher TLS_RSA_WITH_DES_CBC_SHA","Command execution","prod-windows-2016.demo.normation.com","Disable-TlsCipherSuite -Name ""TLS_RSA_WITH_DES_CBC_SHA"" ","auditNotApplicable","Not applicable"
            |"Basic hardening on all systems","Check Cipher TLS_RSA_WITH_DES_CBC_SHA","Audit from Powershell execution","prod-app-01.lab.rudder.io","(Get-TlsCipherSuite -Name ""TLS_RSA_WITH_DES_CBC_SHA"").Count","auditNotApplicable","'Audit from Powershell execution' method is not available on Linux Rudder agent, skip was not applicable"
            |"Basic hardening on all systems","Check Cipher TLS_RSA_WITH_DES_CBC_SHA","Audit from Powershell execution","prod-windows-2016.demo.normation.com","(Get-TlsCipherSuite -Name ""TLS_RSA_WITH_DES_CBC_SHA"").Count","auditCompliant","Command '(Get-TlsCipherSuite -Name ""TLS_RSA_WITH_DES_CBC_SHA"").Count' was executed successfully and matched the success string '0'"
            |""".stripMargin
        )
      }
    }
    "by node " in {
      "with empty node compliance should produce a CSV that only contains the column names" in {
        val compliance = emptyDirectiveCompliance.nodes.transformInto[Seq[DirectiveComplianceByNodeCsv]]

        compliance.toCsv.mustEqual(
          """"Node","Rule","Block","Component","Value","Status","Message"
            |""".stripMargin
        )
      }
      (s"""with rule compliance tree
          |
          |${rulesTree}
          |
          |should produce the following CSV :
          |
          |${nodesCsv}""".stripMargin) in {
        val compliance = directiveCompliance.nodes.transformInto[Seq[DirectiveComplianceByNodeCsv]]

        compliance.toCsv.mustEqual(nodesCsv)
      }
      (s"""with rule compliance tree with nested blocks
          |
          |${nestedRulesTree}
          |
          |should produce the following CSV :
          |
          |${nestedNodesCsv}""".stripMargin) in {
        val compliance = nestedDirectiveCompliance.nodes.transformInto[Seq[DirectiveComplianceByNodeCsv]]

        compliance.toCsv.mustEqual(nestedNodesCsv)
      }
    }
  }

}
