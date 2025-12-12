/*
 *************************************************************************************
 * Copyright 2022 Normation SAS
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

import better.files.*
import com.normation.GitVersion
import com.normation.cfclerk.domain.ReportingLogic
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.DirectiveUid
import com.normation.rudder.domain.policies.GlobalPolicyMode
import com.normation.rudder.domain.policies.PolicyMode
import com.normation.rudder.domain.policies.PolicyModeOverrides
import com.normation.rudder.domain.policies.PolicyTypes
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.RuleUid
import com.normation.rudder.domain.reports.ExpectedReportsSerialisation.*
import com.normation.rudder.reports.AgentRunInterval
import com.normation.rudder.reports.ComplianceModeName.FullCompliance
import com.normation.rudder.reports.GlobalComplianceMode
import net.liftweb.common.Box
import net.liftweb.common.Full
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

/**
 * Test parsing and serialization of expected
 * reports according to 7.1 format:
 * - must be able to read old (pre 7.0) and new (7.1) expected reports
 * - must be able to write new expected reports
 * - for 7.1 reports, we can have two kind of values: with report Id or not
 */

@RunWith(classOf[JUnitRunner])
class ExpectedReportTest extends Specification {
  implicit private def r2n(s: String): RuleId      = RuleId(RuleUid(s))
  implicit private def d2n(s: String): DirectiveId = DirectiveId(DirectiveUid(s), GitVersion.DEFAULT_REV)

  val parse: String => Box[JsonNodeExpectedReports] = ExpectedReportsSerialisation.parseJsonNodeExpectedReports
  def serialize(e: ExpectedReportsSerialisation.JsonNodeExpectedReports) = {
    e.toJson
  }
  sequential

  "Just a block" >> {
    import zio.json.*
    import ExpectedReportsSerialisation.Version7_1.*
    val jsonMin  = {
      """
      {
        "vid": "Command execution",
        "vs": [
          [ "/bin/echo \"restore\"" ]
         ]
      }
      """.stripMargin
    }
    val expected = ValueExpectedReport(
      "Command execution",
      List(
        ExpectedValueMatch("/bin/echo \"restore\"", "/bin/echo \"restore\"")
      )
    )

    (jsonMin.fromJson[JsonValueExpectedReport7_1].map(_.transform)) must beEqualTo(Right(expected))
  }

  "Reading 7.1 expected reports" should {
    "correctly read root" in {
      val jsonMin     = Resource.getAsString("expectedReports/7.1/root_min.json")
      val expected    = JsonNodeExpectedReports(
        NodeModeConfig(
          GlobalComplianceMode(FullCompliance),
          AgentRunInterval(None, 5, 0, 0, 4),
          None,
          GlobalPolicyMode(PolicyMode.Enforce, PolicyModeOverrides.Always),
          None
        ),
        List(
          RuleExpectedReports(
            "hasPolicyServer-root",
            List(
              DirectiveExpectedReports(
                "common-hasPolicyServer-root",
                None,
                PolicyTypes.rudderSystem,
                components = List(
                  ValueExpectedReport(
                    "Update",
                    List(
                      ExpectedValueMatch("None", "None")
                    )
                  ),
                  ValueExpectedReport(
                    "ncf Initialization",
                    List(
                      ExpectedValueMatch("None", "None")
                    )
                  ),
                  ValueExpectedReport(
                    "Security parameters",
                    List(
                      ExpectedValueMatch("None", "None")
                    )
                  ),
                  ValueExpectedReport(
                    "Log system for reports",
                    List(
                      ExpectedValueMatch("None", "None")
                    )
                  ),
                  ValueExpectedReport(
                    "CRON Daemon",
                    List(
                      ExpectedValueMatch("None", "None")
                    )
                  ),
                  ValueExpectedReport(
                    "Monitoring",
                    List(
                      ExpectedValueMatch("None", "None")
                    )
                  )
                )
              )
            )
          ),
          RuleExpectedReports(
            "32377fd7-02fd-43d0-aab7-28460a91347b",
            List(
              DirectiveExpectedReports(
                "dc0eaf47-356a-4a44-877d-e3873f75385b",
                None,
                PolicyTypes.rudderBase,
                components = List(
                  ValueExpectedReport(
                    "Package",
                    List(
                      ExpectedValueMatch("vim", "vim")
                    )
                  ),
                  ValueExpectedReport(
                    "Post-modification script",
                    List(
                      ExpectedValueMatch("vim", "vim")
                    )
                  )
                )
              ),
              DirectiveExpectedReports(
                "cbc2377f-ce6d-47fe-902b-8d92a484b184",
                None,
                PolicyTypes.rudderBase,
                components = List(
                  BlockExpectedReport(
                    "my main component",
                    ReportingLogic.WeightedReport,
                    List(
                      ValueExpectedReport(
                        "touch file",
                        List(
                          ExpectedValueId("cat /tmp/some/file", "eff6dfff-d966-4d05-a92d-77a44f14b83c")
                        )
                      ),
                      ValueExpectedReport(
                        "file exists",
                        List(
                          ExpectedValueId("/tmp/${node.properties[filename]}", "87cad41f-ec88-4b32-a13c-136f15f7bf17")
                        )
                      )
                    ),
                    None
                  )
                )
              )
            )
          ),
          RuleExpectedReports(
            "policy-server-root",
            List(
              DirectiveExpectedReports(
                "rudder-service-apache-root",
                None,
                PolicyTypes.rudderSystem,
                components = List(
                  ValueExpectedReport(
                    "Apache service",
                    List(
                      ExpectedValueMatch("Enabled", "Enabled"),
                      ExpectedValueMatch("Started", "Started")
                    )
                  ),
                  ValueExpectedReport(
                    "Apache configuration",
                    List(
                      ExpectedValueMatch("Allowed networks permissions", "Allowed networks permissions"),
                      ExpectedValueMatch("Allowed networks configuration", "Allowed networks configuration"),
                      ExpectedValueMatch("Remote run permissions", "Remote run permissions"),
                      ExpectedValueMatch("Remote run configuration", "Remote run configuration"),
                      ExpectedValueMatch("Webdav configuration", "Webdav configuration"),
                      ExpectedValueMatch("Webdav permissions", "Webdav permissions"),
                      ExpectedValueMatch("Logrotate", "Logrotate")
                    )
                  ),
                  ValueExpectedReport(
                    "Configure apache certificate",
                    List(
                      ExpectedValueMatch("Permissions", "Permissions"),
                      ExpectedValueMatch("Apache certificate", "Apache certificate")
                    )
                  )
                )
              )
            )
          ),
          RuleExpectedReports(
            "inventory-all",
            List(
              DirectiveExpectedReports(
                "inventory-all",
                None,
                PolicyTypes.rudderSystem,
                components = List(
                  ValueExpectedReport(
                    "Inventory",
                    List(
                      ExpectedValueMatch("None", "None")
                    )
                  )
                )
              )
            )
          )
        ),
        Nil
      )
      val idempotence = parse(serialize(expected))
      (parse(jsonMin) must beEqualTo(Full(expected))) and
      (idempotence must beEqualTo(Full(expected)))
    }
  }
}
