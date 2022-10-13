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

import com.normation.GitVersion
import com.normation.cfclerk.domain.ReportingLogic
import com.normation.cfclerk.domain.TechniqueVersion
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.DirectiveUid
import com.normation.rudder.domain.policies.GlobalPolicyMode
import com.normation.rudder.domain.policies.PolicyMode
import com.normation.rudder.domain.policies.PolicyModeOverrides
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.RuleUid
import com.normation.rudder.domain.reports.ExpectedReportsSerialisation._
import com.normation.rudder.reports.AgentRunInterval
import com.normation.rudder.reports.FullCompliance
import com.normation.rudder.reports.GlobalComplianceMode
import com.normation.rudder.services.policies.PolicyId

import better.files._
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
  private[this] implicit def r2n(s: String): RuleId = RuleId(RuleUid(s))
  private[this] implicit def d2n(s: String): DirectiveId = DirectiveId(DirectiveUid(s), GitVersion.DEFAULT_REV)
  private[this] implicit def s2tv(s: String): TechniqueVersion = TechniqueVersion.parse(s).getOrElse(
    throw new IllegalArgumentException(s"Not a technique version: ${s}")
  )

  val parse = ExpectedReportsSerialisation.parseJsonNodeExpectedReports _
  def serialize(e: ExpectedReportsSerialisation.JsonNodeExpectedReports) = {
    e.toJson
  }
  sequential

  "Just a block" >> {
    import zio.json._
    import ExpectedReportsSerialisation.Version7_1._
    val jsonMin =
      """
      {
        "vid": "Command execution",
        "vs": [
          [ "/bin/echo \"restore\"" ]
         ]
      }
      """.stripMargin
    val expected = ValueExpectedReport("Command execution", List(
                  ExpectedValueMatch("/bin/echo \"restore\"","/bin/echo \"restore\"")
               ))

    (jsonMin.fromJson[JsonValueExpectedReport7_1].map(_.transform)) must beEqualTo(Right(expected))
  }


  "Reading old expected reports" should {

    "correctly read node0" in {
      val json = Resource.getAsString("expectedReports/7.0/node0.json")
      val jsonMin = Resource.getAsString("expectedReports/7.0/node0_min.json")
      val expected = JsonNodeExpectedReports(
        NodeModeConfig(
            GlobalComplianceMode(FullCompliance, 1)
          , None
          , AgentRunInterval(None, 15, 0, 0, 4)
          , None
          , GlobalPolicyMode(PolicyMode.Enforce, PolicyModeOverrides.Always)
          , None
        )
      , List(
            RuleExpectedReports("4bb75daa-a82f-445a-8e8e-af3e99608ffe", List(
               DirectiveExpectedReports("73e069ea-de00-4b5d-a00e-012709b7b462", None, false, List(
                 ValueExpectedReport("Command execution", List(
                    ExpectedValueMatch("/bin/echo \"restore\"","/bin/echo \"restore\"")
                 ))
               , ValueExpectedReport("File absent", List(
                    ExpectedValueMatch("/tmp/pasla","/tmp/pasla")
                 ))
               ))
            ))
          , RuleExpectedReports("hasPolicyServer-root", List(
               DirectiveExpectedReports("common-hasPolicyServer-root", None, true, List(
                 ValueExpectedReport("Update", List(
                    ExpectedValueMatch("None","None")
                 ))
               , ValueExpectedReport("ncf Initialization", List(
                    ExpectedValueMatch("None","None")
                 ))
               , ValueExpectedReport("Security parameters", List(
                    ExpectedValueMatch("None","None")
                 ))
               , ValueExpectedReport("Log system for reports", List(
                    ExpectedValueMatch("None","None")
                 ))
               , ValueExpectedReport("CRON Daemon", List(
                    ExpectedValueMatch("None","None")
                 ))
               , ValueExpectedReport("Monitoring", List(
                    ExpectedValueMatch("None","None")
                 ))
               ))
            ))
          , RuleExpectedReports("inventory-all", List(
               DirectiveExpectedReports("inventory-all", None, true, List(
                 ValueExpectedReport("Inventory", List(
                    ExpectedValueMatch("None","None")
                 ))
               ))
            ))
        )
      , Nil
      )
      val idempotence = parse(serialize(expected))
      (parse(json) must beEqualTo(Full(expected))) and
      (parse(jsonMin) must beEqualTo(Full(expected))) and
      (idempotence must beEqualTo(Full(expected)))
    }
    "correctly read node1" in {
      val json = Resource.getAsString("expectedReports/7.0/node1.json")
      val jsonMin = Resource.getAsString("expectedReports/7.0/node1_min.json")
      val expected = JsonNodeExpectedReports(
        NodeModeConfig(
            GlobalComplianceMode(FullCompliance, 1)
          , None
          , AgentRunInterval(None, 5, 0, 0, 4)
          , Some(AgentRunInterval(Some(true), 240, 17, 2, 234))
          , GlobalPolicyMode(PolicyMode.Audit, PolicyModeOverrides.Always)
          , Some(PolicyMode.Enforce)
        )
      , List(
            RuleExpectedReports("inventory-all", List(
               DirectiveExpectedReports("inventory-all", None, true, List(
                 ValueExpectedReport("inventory", List(
                    ExpectedValueMatch("None","None")
                 ))
               ))
            ))
          , RuleExpectedReports("hasPolicyServer-root", List(
               DirectiveExpectedReports("common-root", None, true, List(
                 ValueExpectedReport("Update", List(
                    ExpectedValueMatch("None","None")
                 ))
               , ValueExpectedReport("ncf Initialization", List(
                    ExpectedValueMatch("None","None")
                 ))
               , ValueExpectedReport("Security parameters", List(
                    ExpectedValueMatch("None","None")
                 ))
               , ValueExpectedReport("Log system for reports", List(
                    ExpectedValueMatch("None","None")
                 ))
               , ValueExpectedReport("CRON Daemon", List(
                    ExpectedValueMatch("None","None")
                 ))
               , ValueExpectedReport("Monitoring", List(
                    ExpectedValueMatch("None","None")
                 ))
               ))
            ))
          , RuleExpectedReports("10023ce3-0775-4178-bd9f-1f3a61d81c7c", List(
               DirectiveExpectedReports("b6478fd4-f2de-43b0-9f04-812ec0680e4d", None, false, List(
                 ValueExpectedReport("File from local source", List(
                    ExpectedValueMatch("/tmp","/tmp")
                 ))
               ))
             , DirectiveExpectedReports("37c57e98-328d-4cd2-8a71-33f2e449ba51", None, false, List(
                 ValueExpectedReport("Restart ${service}", List(
                    ExpectedValueMatch("${service}","${service}")
                 ))
               ))
            ))
          , RuleExpectedReports("32377fd7-02fd-43d0-aab7-28460a91347b", List(
               DirectiveExpectedReports("d2471d9b-5c7f-404e-9a03-970c36924f5c", None, false, List(
                 ValueExpectedReport("File content", List(
                    ExpectedValueMatch("/tmp/audit/audit.log","/tmp/audit/audit.log")
                 ))
               , ValueExpectedReport("Permissions (non recursive)", List(
                    ExpectedValueMatch("/tmp/audit/audit.log","/tmp/audit/audit.log")
                 ))
               , ValueExpectedReport("Permissions dirs", List(
                    ExpectedValueMatch("/var/log/audit","/var/log/audit")
                 ))
               ))
             , DirectiveExpectedReports("86a0ef5b-1b98-4bda-8dc3-2940ad53beaa", None, false, List(
                 ValueExpectedReport("File", List(
                    ExpectedValueMatch("/tmp/${sys.fqhost}","/tmp/${sys.fqhost}")
                 ))
               , ValueExpectedReport("Enforce content by section", List(
                    ExpectedValueMatch("/tmp/${sys.fqhost}","/tmp/${sys.fqhost}")
                 ))
               , ValueExpectedReport("Line deletion regular expressions", List(
                    ExpectedValueMatch("/tmp/${sys.fqhost}","/tmp/${sys.fqhost}")
                 ))
               , ValueExpectedReport("Line replacement regular expressions", List(
                    ExpectedValueMatch("/tmp/${sys.fqhost}","/tmp/${sys.fqhost}")
                 ))
               , ValueExpectedReport("Permission adjustment", List(
                    ExpectedValueMatch("/tmp/${sys.fqhost}","/tmp/${sys.fqhost}")
                 ))
               , ValueExpectedReport("Post-modification hook", List(
                    ExpectedValueMatch("/tmp/${sys.fqhost}","/tmp/${sys.fqhost}")
                 ))
               ))
             , DirectiveExpectedReports("88e99dc6-c211-40fd-85a2-b1e8381fe0cb", None, false, List(
                 ValueExpectedReport("Command execution", List(
                    ExpectedValueMatch("/bin/true","/bin/true")
                 ))
               ))
             , DirectiveExpectedReports("6eae3c13-1d34-4643-b136-f82c143d8aa5", None, false, List(
                 ValueExpectedReport("File replace lines", List(
                    ExpectedValueMatch("/tmp/toto.txt","/tmp/${node.properties[filename]}")
                 ))
               ))
            ))
        )
      , Nil
      )
      val idempotence = parse(serialize(expected))
      (parse(json) must beEqualTo(Full(expected))) and
      (parse(jsonMin) must beEqualTo(Full(expected))) and
      (idempotence must beEqualTo(Full(expected)))
    }

    "correctly read node2" in {
      val json = Resource.getAsString("expectedReports/7.0/node2.json")
      val jsonMin = Resource.getAsString("expectedReports/7.0/node2_min.json")
      val expected = JsonNodeExpectedReports(
        NodeModeConfig(
            GlobalComplianceMode(FullCompliance, 1)
          , None
          , AgentRunInterval(None, 120, 45, 1, 81)
          , None
          , GlobalPolicyMode(PolicyMode.Enforce, PolicyModeOverrides.Always)
          , None
        )
      , List(
            RuleExpectedReports("server-roles", List(
               DirectiveExpectedReports("server-roles-directive", None, true, List(
                 ValueExpectedReport("Check postgresql process", List(
                    ExpectedValueMatch("None","None")
                 ))
               , ValueExpectedReport("Check jetty process", List(
                    ExpectedValueMatch("None","None")
                 ))
               , ValueExpectedReport("Check slapd process", List(
                    ExpectedValueMatch("None","None")
                 ))
               , ValueExpectedReport("Check apache process", List(
                    ExpectedValueMatch("None","None")
                 ))
               , ValueExpectedReport("Check relayd process", List(
                    ExpectedValueMatch("None","None")
                 ))
               , ValueExpectedReport("Check postgresql boot script", List(
                    ExpectedValueMatch("None","None")
                 ))
               , ValueExpectedReport("Check jetty boot script", List(
                    ExpectedValueMatch("None","None")
                 ))
               , ValueExpectedReport("Check slapd boot script", List(
                    ExpectedValueMatch("None","None")
                 ))
               , ValueExpectedReport("Check apache boot script", List(
                    ExpectedValueMatch("None","None")
                 ))
               , ValueExpectedReport("Check relayd boot script", List(
                    ExpectedValueMatch("None","None")
                 ))
               , ValueExpectedReport("Check logrotate configuration", List(
                    ExpectedValueMatch("None","None")
                 ))
               , ValueExpectedReport("Check configuration-repository folder", List(
                    ExpectedValueMatch("None","None")
                 ))
               , ValueExpectedReport("Check configuration-repository GIT lock", List(
                    ExpectedValueMatch("None","None")
                 ))
               , ValueExpectedReport("Check allowed networks configuration", List(
                    ExpectedValueMatch("None","None")
                 ))
               , ValueExpectedReport("Check rudder status", List(
                    ExpectedValueMatch("None","None")
                 ))
               , ValueExpectedReport("Check rudder-passwords.conf", List(
                    ExpectedValueMatch("None","None")
                 ))
               , ValueExpectedReport("Check pgpass file", List(
                    ExpectedValueMatch("None","None")
                 ))
               , ValueExpectedReport("Check LDAP credentials", List(
                    ExpectedValueMatch("None","None")
                 ))
               , ValueExpectedReport("Check LDAP in rudder-webapp.properties", List(
                    ExpectedValueMatch("None","None")
                 ))
               , ValueExpectedReport("Check SQL credentials", List(
                    ExpectedValueMatch("None","None")
                 ))
               , ValueExpectedReport("Check SQL in rudder-webapp.properties", List(
                    ExpectedValueMatch("None","None")
                 ))
               , ValueExpectedReport("Check WebDAV credentials", List(
                    ExpectedValueMatch("None","None")
                 ))
               , ValueExpectedReport("Check WebDAV properties", List(
                    ExpectedValueMatch("None","None")
                 ))
               , ValueExpectedReport("Relayd service configuration", List(
                    ExpectedValueMatch("None","None")
                 ))
               , ValueExpectedReport("Send metrics to rudder-project", List(
                    ExpectedValueMatch("None","None")
                 ))
               ))
            ))
          , RuleExpectedReports("a3a796b9-8499-4e0b-86c5-975fc5a13505", List(
               DirectiveExpectedReports("a73b40e0-d83c-474f-b617-7f634d232b8b", Some(PolicyMode.Enforce), false, List(
                 ValueExpectedReport("Copy file", List(
                    ExpectedValueMatch("foo/bar1","foo/bar1")
                 ))
               , ValueExpectedReport("Post-modification hook", List(
                    ExpectedValueMatch("foo/bar1","foo/bar1")
                 ))
               ))
            ))
          , RuleExpectedReports("eff9e40d-5ccf-450a-801a-d4432a7592e4", List(
               DirectiveExpectedReports("dd4a5ee8-080d-452b-bfa8-9e46b459a804", None, false, List(
                 ValueExpectedReport("File", List(
                    ExpectedValueMatch("/tmp/simple-val1","/tmp/simple-val1")
                  , ExpectedValueMatch("/tmp/simple-val-2","/tmp/simple-val-2")
                  , ExpectedValueMatch("/tmp/server.rudder.local","/tmp/${rudder.node.hostname}")
                  , ExpectedValueMatch("/tmp/cfe1/${sys.hostname}","/tmp/cfe1/${sys.hostname}")
                  , ExpectedValueMatch("/tmp/cfe2/${sys.hostname}","/tmp/cfe2/${sys.hostname}")
                 ))
               , ValueExpectedReport("Enforce content by section", List(
                    ExpectedValueMatch("/tmp/simple-val1","/tmp/simple-val1")
                  , ExpectedValueMatch("/tmp/simple-val-2","/tmp/simple-val-2")
                  , ExpectedValueMatch("/tmp/server.rudder.local","/tmp/${rudder.node.hostname}")
                  , ExpectedValueMatch("/tmp/cfe1/${sys.hostname}","/tmp/cfe1/${sys.hostname}")
                  , ExpectedValueMatch("/tmp/cfe2/${sys.hostname}","/tmp/cfe2/${sys.hostname}")
                 ))
               , ValueExpectedReport("Line deletion regular expressions", List(
                    ExpectedValueMatch("/tmp/simple-val1","/tmp/simple-val1")
                  , ExpectedValueMatch("/tmp/simple-val-2","/tmp/simple-val-2")
                  , ExpectedValueMatch("/tmp/server.rudder.local","/tmp/${rudder.node.hostname}")
                  , ExpectedValueMatch("/tmp/cfe1/${sys.hostname}","/tmp/cfe1/${sys.hostname}")
                  , ExpectedValueMatch("/tmp/cfe2/${sys.hostname}","/tmp/cfe2/${sys.hostname}")
                 ))
               , ValueExpectedReport("Line replacement regular expressions", List(
                    ExpectedValueMatch("/tmp/simple-val1","/tmp/simple-val1")
                  , ExpectedValueMatch("/tmp/simple-val-2","/tmp/simple-val-2")
                  , ExpectedValueMatch("/tmp/server.rudder.local","/tmp/${rudder.node.hostname}")
                  , ExpectedValueMatch("/tmp/cfe1/${sys.hostname}","/tmp/cfe1/${sys.hostname}")
                  , ExpectedValueMatch("/tmp/cfe2/${sys.hostname}","/tmp/cfe2/${sys.hostname}")
                 ))
               , ValueExpectedReport("Permission adjustment", List(
                    ExpectedValueMatch("/tmp/simple-val1","/tmp/simple-val1")
                  , ExpectedValueMatch("/tmp/simple-val-2","/tmp/simple-val-2")
                  , ExpectedValueMatch("/tmp/server.rudder.local","/tmp/${rudder.node.hostname}")
                  , ExpectedValueMatch("/tmp/cfe1/${sys.hostname}","/tmp/cfe1/${sys.hostname}")
                  , ExpectedValueMatch("/tmp/cfe2/${sys.hostname}","/tmp/cfe2/${sys.hostname}")
                 ))
               , ValueExpectedReport("Post-modification hook", List(
                    ExpectedValueMatch("/tmp/simple-val1","/tmp/simple-val1")
                  , ExpectedValueMatch("/tmp/simple-val-2","/tmp/simple-val-2")
                  , ExpectedValueMatch("/tmp/server.rudder.local","/tmp/${rudder.node.hostname}")
                  , ExpectedValueMatch("/tmp/cfe1/${sys.hostname}","/tmp/cfe1/${sys.hostname}")
                  , ExpectedValueMatch("/tmp/cfe2/${sys.hostname}","/tmp/cfe2/${sys.hostname}")
                 ))
               ))
            ))
        )
      , List(
          OverridenPolicy(
            PolicyId("a3a796b9-8499-4e0b-86c5-975fc5a13505","9b0dc972-f4dc-4aaa-bae6-1189cf9074b6","0.0")
          , PolicyId("2278f76f-28d3-4326-8199-99561dd8c785","093a494b-1073-49e9-bb1e-3c128c7f6a42","0.0")
          )
        , OverridenPolicy(
            PolicyId("32377fd7-02fd-43d0-aab7-28460a91347b","093a494b-1073-49e9-bb1e-3c128c7f6a42","0.0")
          , PolicyId("2278f76f-28d3-4326-8199-99561dd8c785","093a494b-1073-49e9-bb1e-3c128c7f6a42","0.0")
          )
        )
      )
      def idempotence = parse(serialize(expected))
      (parse(json) must beEqualTo(Full(expected))) and
      (parse(jsonMin) must beEqualTo(Full(expected))) and
      (idempotence must beEqualTo(Full(expected)))
    }
  }
  "Reading 7.1 expected reports" should {
    "correctly read root" in {
      val json = Resource.getAsString("expectedReports/7.1/root.json")
      val jsonMin = Resource.getAsString("expectedReports/7.1/root_min.json")
      val expected = JsonNodeExpectedReports(
        NodeModeConfig(
            GlobalComplianceMode(FullCompliance, 1)
          , None
          , AgentRunInterval(None, 5, 0, 0, 4)
          , None
          , GlobalPolicyMode(PolicyMode.Enforce, PolicyModeOverrides.Always)
          , None
        )
      , List(
            RuleExpectedReports("hasPolicyServer-root", List(
               DirectiveExpectedReports("common-hasPolicyServer-root", None, true, List(
                 ValueExpectedReport("Update", List(
                    ExpectedValueMatch("None","None")
                 ))
               , ValueExpectedReport("ncf Initialization", List(
                    ExpectedValueMatch("None","None")
                 ))
               , ValueExpectedReport("Security parameters", List(
                    ExpectedValueMatch("None","None")
                 ))
               , ValueExpectedReport("Log system for reports", List(
                    ExpectedValueMatch("None","None")
                 ))
               , ValueExpectedReport("CRON Daemon", List(
                    ExpectedValueMatch("None","None")
                 ))
               , ValueExpectedReport("Monitoring", List(
                    ExpectedValueMatch("None","None")
                 ))
               ))
            ))
          , RuleExpectedReports("32377fd7-02fd-43d0-aab7-28460a91347b", List(
               DirectiveExpectedReports("dc0eaf47-356a-4a44-877d-e3873f75385b", None, false, List(
                 ValueExpectedReport("Package", List(
                    ExpectedValueMatch("vim","vim")
                 ))
               , ValueExpectedReport("Post-modification script", List(
                    ExpectedValueMatch("vim","vim")
                 ))
               ))
             , DirectiveExpectedReports("cbc2377f-ce6d-47fe-902b-8d92a484b184", None, false, List(
                 BlockExpectedReport("my main component", ReportingLogic.WeightedReport, List(
                   ValueExpectedReport("touch file", List(
                      ExpectedValueId("cat /tmp/some/file","eff6dfff-d966-4d05-a92d-77a44f14b83c")
                   ))
                 , ValueExpectedReport("file exists", List(
                      ExpectedValueId("/tmp/${node.properties[filename]}","87cad41f-ec88-4b32-a13c-136f15f7bf17")
                   ))
                 ))
               ))
            ))
          , RuleExpectedReports("policy-server-root", List(
               DirectiveExpectedReports("rudder-service-apache-root", None, true, List(
                 ValueExpectedReport("Apache service", List(
                    ExpectedValueMatch("Enabled","Enabled")
                  , ExpectedValueMatch("Started","Started")
                 ))
               , ValueExpectedReport("Apache configuration", List(
                    ExpectedValueMatch("Allowed networks permissions","Allowed networks permissions")
                  , ExpectedValueMatch("Allowed networks configuration","Allowed networks configuration")
                  , ExpectedValueMatch("Remote run permissions","Remote run permissions")
                  , ExpectedValueMatch("Remote run configuration","Remote run configuration")
                  , ExpectedValueMatch("Webdav configuration","Webdav configuration")
                  , ExpectedValueMatch("Webdav permissions","Webdav permissions")
                  , ExpectedValueMatch("Logrotate","Logrotate")
                 ))
               , ValueExpectedReport("Configure apache certificate", List(
                    ExpectedValueMatch("Permissions","Permissions")
                  , ExpectedValueMatch("Apache certificate","Apache certificate")
                 ))
               ))
            ))
          , RuleExpectedReports("inventory-all", List(
               DirectiveExpectedReports("inventory-all", None, true, List(
                 ValueExpectedReport("Inventory", List(
                    ExpectedValueMatch("None","None")
                 ))
               ))
            ))
        )
      , Nil
      )
      val idempotence = parse(serialize(expected))
      (parse(json) must beEqualTo(Full(expected))) and
      (parse(jsonMin) must beEqualTo(Full(expected))) and
      (idempotence must beEqualTo(Full(expected)))
    }
  }
}
