/*
 *************************************************************************************
 * Copyright 2025 Normation SAS
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

import better.files.*
import com.normation.GitVersion
import com.normation.errors.*
import com.normation.rudder.domain.policies.AllTarget
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.DirectiveUid
import com.normation.rudder.domain.policies.Rule
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.RuleUid
import com.normation.rudder.domain.policies.Tags
import com.normation.rudder.domain.properties.GenericProperty.*
import com.normation.rudder.domain.properties.GlobalParameter
import com.normation.rudder.domain.properties.Visibility
import com.normation.rudder.rule.category.RuleCategoryId
import com.normation.rudder.tenants.SecurityTag
import com.normation.rudder.tenants.TenantId
import org.junit.runner.RunWith
import zio.*
import zio.test.*
import zio.test.junit.ZTestJUnitRunner

@RunWith(classOf[ZTestJUnitRunner])
class TestRestTenantFromFileDef extends ZIOSpecDefault {

  // suppress expected error logs from REST utils
  org.slf4j.LoggerFactory
    .getLogger("com.normation.rudder.rest.RestUtils")
    .asInstanceOf[ch.qos.logback.classic.Logger]
    .setLevel(ch.qos.logback.classic.Level.OFF)
  org.slf4j.LoggerFactory
    .getLogger("tenants")
    .asInstanceOf[ch.qos.logback.classic.Logger]
    .setLevel(ch.qos.logback.classic.Level.TRACE)

  // A single shared test setup — all user variants read from the same mock repos
  val restTestSetUp: RestTestSetUp = RestTestSetUp.newEnv

  // ─── seed tenant-tagged objects ───────────────────────────────────────────

  // Rule restricted to zoneA + zoneB, using the enabled clockDirective so that
  // getRuleApplicationStatus returns "In application"
  val tenantRule: Rule = Rule(
    RuleId(RuleUid("tenant-rule-1")),
    "Tenant test rule",
    RuleCategoryId("rootRuleCategory"),
    Set(AllTarget),
    Set(DirectiveId(DirectiveUid("directive1"))),
    "A rule for tenant testing",
    "",
    isEnabledStatus = true,
    isSystem = false,
    Tags(Set()),
    security = Some(SecurityTag.ByTenants(zio.Chunk(TenantId("zoneA"), TenantId("zoneB"))))
  )

  // Global parameter restricted to zoneA + zoneB
  val tenantParam: GlobalParameter = GlobalParameter(
    "tenantParam",
    GitVersion.DEFAULT_REV,
    "tenant-value".toConfigValue,
    None,
    "a tenant-restricted param",
    None,
    Visibility.default,
    security = Some(SecurityTag.ByTenants(zio.Chunk(TenantId("zoneA"), TenantId("zoneB"))))
  )

  val tmpTenantTemplate: File = restTestSetUp.baseTempDirectory / "tenantApiTemplates"
  tmpTenantTemplate.createDirectories()

  override def spec: Spec[TestEnvironment & Scope, Any] = {
    suite("Tenant-aware REST API tests (multi-user)") {
      for {
        _ <- restTestSetUp.mockParameters.paramsRepo.paramsMap.update(_ + (tenantParam.name -> tenantParam))
        _ <- restTestSetUp.mockRules.ruleRepo.rulesMap.update(_ + (tenantRule.id -> tenantRule))
        _ <- restTestSetUp.mockTenants.tenantRepo.setTenantEnabled(true)
        s <- TraitTestApiFromYamlFiles.doTest(
               "api-tenant",
               tmpTenantTemplate,
               restTestSetUp.liftRules,
               restTestSetUp.userService,
               Nil,
               Map.empty
             )
        _ <- effectUioUnit(
               if (java.lang.System.getProperty("tests.clean.tmp") != "false") IOResult.attempt(restTestSetUp.cleanup())
               else ZIO.unit
             )
      } yield s
    } @@ TestAspect.sequential
  }
}
