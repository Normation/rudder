/*
 *************************************************************************************
 * Copyright 2015 Normation SAS
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
package com.normation.rudder.services.policies.write

import com.normation.cfclerk.domain.BundleName
import com.normation.cfclerk.domain.RunHook
import com.normation.cfclerk.domain.TechniqueId
import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.domain.TechniqueVersionHelper
import com.normation.rudder.campaigns.CampaignId
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.DirectiveUid
import com.normation.rudder.domain.policies.PolicyMode
import com.normation.rudder.domain.policies.PolicyTypes
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.RuleUid
import com.normation.rudder.services.policies.IfVarClass
import com.normation.rudder.services.policies.NodeRunHook
import com.normation.rudder.services.policies.PolicyId
import com.normation.rudder.services.policies.write.BuildBundleSequence.*
import com.normation.templates.FillTemplatesService
import org.junit.runner.*
import org.specs2.mutable.*
import org.specs2.runner.*

@RunWith(classOf[JUnitRunner])
class PrepareTemplateVariableTest extends Specification {

  def TID(s: String): TechniqueId = TechniqueId(TechniqueName(s), TechniqueVersionHelper("1.0"))

  val bundles: List[TechniqueBundles] = List(
    (
      "Global configuration for all nodes/20. Install jdk version 1.0",
      "directive1",
      Bundle(None, BundleName("Install_jdk_rudder_reporting"), Nil, None)
    ),
    (
      "Global configuration for all nodes/RUG / YaST package manager configuration (ZMD)",
      "directive2",
      Bundle(None, BundleName("check_zmd_settings"), Nil, None)
    ),
    (
      """Nodes only/Name resolution version "3.0" and counting""",
      "directive3",
      Bundle(
        None,
        BundleName("check_dns_configuration"),
        Nil,
        Some(IfVarClass.fromScheduleId(CampaignId("da687546-d546-4a06-8153-eb5cd28c0b0e")))
      )
    ),
    (
      raw"""Nodes only/Package \"management\" for Debian""",
      "directive4",
      Bundle(None, BundleName("check_apt_package_installation"), Nil, None)
    ),
    (
      raw"""Nodes only/Package \\"management\\" for Debian - again""",
      "directive5",
      Bundle(None, BundleName("check_apt_package_installation2"), Nil, None)
    )
  ).map {
    case (x, directiveId, y) =>
      TechniqueBundles(
        Promiser(x),
        DirectiveId(DirectiveUid(directiveId)),
        TID("not-used-here"),
        pre = Nil,
        main = y :: Nil,
        post = Nil,
        PolicyTypes.rudderBase,
        policyMode = PolicyMode.Enforce,
        enableMethodReporting = false,
        y.ifvarclass
      )
  }

  val fillTemplate = new FillTemplatesService()

  // Ok, now I can test
  "Preparing the string for writing usebundle of directives" should {

    "correctly write nothing at all when the list of bundle is empty" in {
      CfengineBundleVariables.formatMethodsUsebundle(
        CFEngineAgentSpecificGeneration.escape,
        Nil,
        Nil,
        cleanDryRunEnd = false
      ) === List("")
    }

    "write exactly - including escaped quotes" in {
      val actual   = CfengineBundleVariables.formatMethodsUsebundle(
        CFEngineAgentSpecificGeneration.escape,
        bundles,
        Nil,
        cleanDryRunEnd = false
      )
      val expected = {
        List(
          raw"""      "Global configuration for all nodes/20. Install jdk version 1.0"                    usebundle => set_dry_run_mode("false");
      "Global configuration for all nodes/20. Install jdk version 1.0"                    usebundle => run_directive1;
      "Global configuration for all nodes/RUG / YaST package manager configuration (ZMD)" usebundle => set_dry_run_mode("false");
      "Global configuration for all nodes/RUG / YaST package manager configuration (ZMD)" usebundle => run_directive2;
      "Nodes only/Name resolution version \"3.0\" and counting"                           usebundle => set_dry_run_mode("false");
      "Nodes only/Name resolution version \"3.0\" and counting"                           usebundle => run_directive3,
                                                                                                 if => schedule_da687546_d546_4a06_8153_eb5cd28c0b0e;
      "Nodes only/Package \\\"management\\\" for Debian"                                  usebundle => set_dry_run_mode("false");
      "Nodes only/Package \\\"management\\\" for Debian"                                  usebundle => run_directive4;
      "Nodes only/Package \\\\\"management\\\\\" for Debian - again"                      usebundle => set_dry_run_mode("false");
      "Nodes only/Package \\\\\"management\\\\\" for Debian - again"                      usebundle => run_directive5;""", // HERE WE HAVE A SECOND ELEMENT

          raw"""
}
bundle agent run_directive1
{
  methods:
      "Global configuration for all nodes/20. Install jdk version 1.0" usebundle => disable_reporting;
      "Global configuration for all nodes/20. Install jdk version 1.0" usebundle => Install_jdk_rudder_reporting;
      "Global configuration for all nodes/20. Install jdk version 1.0" usebundle => clean_reporting_context;
}
bundle agent run_directive2
{
  methods:
      "Global configuration for all nodes/RUG / YaST package manager configuration (ZMD)" usebundle => disable_reporting;
      "Global configuration for all nodes/RUG / YaST package manager configuration (ZMD)" usebundle => check_zmd_settings;
      "Global configuration for all nodes/RUG / YaST package manager configuration (ZMD)" usebundle => clean_reporting_context;
}
bundle agent run_directive3
{
  methods:
      "Nodes only/Name resolution version \"3.0\" and counting" usebundle => disable_reporting;
      "Nodes only/Name resolution version \"3.0\" and counting" usebundle => check_dns_configuration,
                                                                       if => schedule_da687546_d546_4a06_8153_eb5cd28c0b0e;
      "Nodes only/Name resolution version \"3.0\" and counting" usebundle => clean_reporting_context;
}
bundle agent run_directive4
{
  methods:
      "Nodes only/Package \\\"management\\\" for Debian" usebundle => disable_reporting;
      "Nodes only/Package \\\"management\\\" for Debian" usebundle => check_apt_package_installation;
      "Nodes only/Package \\\"management\\\" for Debian" usebundle => clean_reporting_context;
}
bundle agent run_directive5
{
  methods:
      "Nodes only/Package \\\\\"management\\\\\" for Debian - again" usebundle => disable_reporting;
      "Nodes only/Package \\\\\"management\\\\\" for Debian - again" usebundle => check_apt_package_installation2;
      "Nodes only/Package \\\\\"management\\\\\" for Debian - again" usebundle => clean_reporting_context;"""
        )
      }

      actual must containTheSameElementsAs(expected)
    }

    "write exactly - including escaped quotes and hooks" in {
      val hooks = {
        NodeRunHook(
          "package-install",
          RunHook.Kind.Pre,
          NodeRunHook.ReportOn(
            PolicyId(RuleId(RuleUid("r1")), DirectiveId(DirectiveUid("d1")), TechniqueVersionHelper("1.0")),
            PolicyMode.Enforce,
            "tech1",
            RunHook.Report("cmpt1", Some("val1"))
          ) ::
          NodeRunHook.ReportOn(
            PolicyId(RuleId(RuleUid("r1")), DirectiveId(DirectiveUid("d1")), TechniqueVersionHelper("1.0")),
            PolicyMode.Enforce,
            "tech1",
            RunHook.Report("cmpt1", Some("val1"))
          ) :: Nil,
          RunHook.Parameter("package", "vim") :: RunHook.Parameter("action", "update-only") :: Nil
        ) ::
        NodeRunHook(
          "service-restart",
          RunHook.Kind.Post,
          NodeRunHook.ReportOn(
            PolicyId(RuleId(RuleUid("r1")), DirectiveId(DirectiveUid("d1")), TechniqueVersionHelper("1.0")),
            PolicyMode.Enforce,
            "tech1",
            RunHook.Report("cmpt2", None)
          ) ::
          NodeRunHook.ReportOn(
            PolicyId(RuleId(RuleUid("r1")), DirectiveId(DirectiveUid("d1")), TechniqueVersionHelper("1.0")),
            PolicyMode.Enforce,
            "tech1",
            RunHook.Report("cmpt2", None)
          ) :: Nil,
          RunHook.Parameter("service", "syslog") :: Nil
        ) :: Nil
      }

      // spaces inserted at the begining of promises in rudder_directives.cf are due to string template, not the formated string - strange

      val actual   = CfengineBundleVariables.formatMethodsUsebundle(
        CFEngineAgentSpecificGeneration.escape,
        bundles,
        hooks,
        cleanDryRunEnd = false
      )
      val expected = {
        List(
          raw"""      "pre-run-hook"                                                                      usebundle => package_install('{"parameters":{"package":"vim","action":"update-only"},"reports":[{"id":"r1@@d1@@0","mode":"enforce","technique":"tech1","name":"cmpt1","value":"val1"},{"id":"r1@@d1@@0","mode":"enforce","technique":"tech1","name":"cmpt1","value":"val1"}]}');
      "Global configuration for all nodes/20. Install jdk version 1.0"                    usebundle => set_dry_run_mode("false");
      "Global configuration for all nodes/20. Install jdk version 1.0"                    usebundle => run_directive1;
      "Global configuration for all nodes/RUG / YaST package manager configuration (ZMD)" usebundle => set_dry_run_mode("false");
      "Global configuration for all nodes/RUG / YaST package manager configuration (ZMD)" usebundle => run_directive2;
      "Nodes only/Name resolution version \"3.0\" and counting"                           usebundle => set_dry_run_mode("false");
      "Nodes only/Name resolution version \"3.0\" and counting"                           usebundle => run_directive3,
                                                                                                 if => schedule_da687546_d546_4a06_8153_eb5cd28c0b0e;
      "Nodes only/Package \\\"management\\\" for Debian"                                  usebundle => set_dry_run_mode("false");
      "Nodes only/Package \\\"management\\\" for Debian"                                  usebundle => run_directive4;
      "Nodes only/Package \\\\\"management\\\\\" for Debian - again"                      usebundle => set_dry_run_mode("false");
      "Nodes only/Package \\\\\"management\\\\\" for Debian - again"                      usebundle => run_directive5;
      "post-run-hook"                                                                     usebundle => service_restart('{"parameters":{"service":"syslog"},"reports":[{"id":"r1@@d1@@0","mode":"enforce","technique":"tech1","name":"cmpt2","value":"None"},{"id":"r1@@d1@@0","mode":"enforce","technique":"tech1","name":"cmpt2","value":"None"}]}');""", // HERE WE HAVE A SECOND ELEMENT
          raw"""
}
bundle agent run_directive1
{
  methods:
      "Global configuration for all nodes/20. Install jdk version 1.0" usebundle => disable_reporting;
      "Global configuration for all nodes/20. Install jdk version 1.0" usebundle => Install_jdk_rudder_reporting;
      "Global configuration for all nodes/20. Install jdk version 1.0" usebundle => clean_reporting_context;
}
bundle agent run_directive2
{
  methods:
      "Global configuration for all nodes/RUG / YaST package manager configuration (ZMD)" usebundle => disable_reporting;
      "Global configuration for all nodes/RUG / YaST package manager configuration (ZMD)" usebundle => check_zmd_settings;
      "Global configuration for all nodes/RUG / YaST package manager configuration (ZMD)" usebundle => clean_reporting_context;
}
bundle agent run_directive3
{
  methods:
      "Nodes only/Name resolution version \"3.0\" and counting" usebundle => disable_reporting;
      "Nodes only/Name resolution version \"3.0\" and counting" usebundle => check_dns_configuration,
                                                                       if => schedule_da687546_d546_4a06_8153_eb5cd28c0b0e;
      "Nodes only/Name resolution version \"3.0\" and counting" usebundle => clean_reporting_context;
}
bundle agent run_directive4
{
  methods:
      "Nodes only/Package \\\"management\\\" for Debian" usebundle => disable_reporting;
      "Nodes only/Package \\\"management\\\" for Debian" usebundle => check_apt_package_installation;
      "Nodes only/Package \\\"management\\\" for Debian" usebundle => clean_reporting_context;
}
bundle agent run_directive5
{
  methods:
      "Nodes only/Package \\\\\"management\\\\\" for Debian - again" usebundle => disable_reporting;
      "Nodes only/Package \\\\\"management\\\\\" for Debian - again" usebundle => check_apt_package_installation2;
      "Nodes only/Package \\\\\"management\\\\\" for Debian - again" usebundle => clean_reporting_context;"""
        )
      }

      actual must containTheSameElementsAs(expected)
    }

  }
}
