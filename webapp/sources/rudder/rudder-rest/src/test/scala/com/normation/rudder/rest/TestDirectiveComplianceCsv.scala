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
import com.normation.rudder.web.services.ComputePolicyMode
import org.junit.runner.RunWith
import org.specs2.mutable.*
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class TestDirectiveComplianceCsv extends Specification {

  sequential

  ///// tests ////

  "CSV in directive should correctly escape things" >> {

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
    val enforce    = ComputePolicyMode.global(GlobalPolicyMode(PolicyMode.Enforce, PolicyModeOverrides.Always))
    val notUsed    = ComplianceLevel()
    val compliance = ByDirectiveCompliance(
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

    CsvCompliance.CsvDirectiveCompliance(compliance).toCsv must beEqualTo(
      """"Rule","Block","Component","Node","Value","Status","Message"
        |"Basic hardening on all systems","Check Cipher TLS_RSA_WITH_DES_CBC_SHA","Command execution","prod-www-01.lab.rudder.io","Disable-TlsCipherSuite -Name ""TLS_RSA_WITH_DES_CBC_SHA"" ","auditNotApplicable","Skipping method 'Command execution' with key parameter 'Disable-TlsCipherSuite -Name ""TLS_RSA_WITH_DES_CBC_SHA""' since condition 'windows.audit_from_powershell_execution__Get_TlsCipherSuite__Name__TLS_RSA_WITH_DES_CBC_SHA___Count_error' is not reached was not applicable"
        |"Basic hardening on all systems","Check Cipher TLS_RSA_WITH_DES_CBC_SHA","Command execution","prod-windows-2016.demo.normation.com","Disable-TlsCipherSuite -Name ""TLS_RSA_WITH_DES_CBC_SHA"" ","auditNotApplicable","Not applicable"
        |"Basic hardening on all systems","Check Cipher TLS_RSA_WITH_DES_CBC_SHA","Audit from Powershell execution","prod-app-01.lab.rudder.io","(Get-TlsCipherSuite -Name ""TLS_RSA_WITH_DES_CBC_SHA"").Count","auditNotApplicable","'Audit from Powershell execution' method is not available on Linux Rudder agent, skip was not applicable"
        |"Basic hardening on all systems","Check Cipher TLS_RSA_WITH_DES_CBC_SHA","Audit from Powershell execution","prod-windows-2016.demo.normation.com","(Get-TlsCipherSuite -Name ""TLS_RSA_WITH_DES_CBC_SHA"").Count","auditCompliant","Command '(Get-TlsCipherSuite -Name ""TLS_RSA_WITH_DES_CBC_SHA"").Count' was executed successfully and matched the success string '0'"
        |""".stripMargin
    )

  }

}
