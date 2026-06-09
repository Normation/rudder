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
import com.normation.rudder.AuthorizationType
import com.normation.rudder.Rights
import com.normation.rudder.api.ApiAuthorization as ApiAuthz
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
import com.normation.rudder.tenants.TenantAccess
import com.normation.rudder.tenants.TenantAccessGrant
import com.normation.rudder.tenants.TenantId
import com.normation.rudder.tenants.TenantPermission
import com.normation.rudder.users.AuthenticatedUser
import com.normation.rudder.users.RudderAccount
import com.normation.rudder.users.UserPassword
import com.normation.rudder.users.UserService
import com.normation.zio.*
import net.liftweb.http.LiftRules
import org.junit.runner.RunWith
import zio.*
import zio.test.*
import zio.test.junit.ZTestJUnitRunner

/*
 * A UserService whose tenant grant is configurable at construction time.
 * Used to simulate users with different tenant access in API tests.
 */
class TestUserServiceWithGrant(grant: TenantAccessGrant) extends UserService {
  val user:                    AuthenticatedUser         = new AuthenticatedUser {
    override val account:     RudderAccount     = RudderAccount.User("test-tenant-user", UserPassword.unsafeHashed("pass"))
    override val authz:       Rights            = Rights.AnyRights
    override val apiAuthz:    ApiAuthz          = ApiAuthz.allAuthz
    override val accessGrant: TenantAccessGrant = grant
    override def actorIp:     Option[String]    = None
    override def checkRights(auth: AuthorizationType): Boolean = true
  }
  override val getCurrentUser: Option[AuthenticatedUser] = Some(user)
}

@RunWith(classOf[ZTestJUnitRunner])
class TestRestTenantFromFileDef extends ZIOSpecDefault {

  // suppress expected error logs from REST utils
  org.slf4j.LoggerFactory
    .getLogger("com.normation.rudder.rest.RestUtils")
    .asInstanceOf[ch.qos.logback.classic.Logger]
    .setLevel(ch.qos.logback.classic.Level.OFF)

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
  restTestSetUp.mockRules.ruleRepo.rulesMap.update(_ + (tenantRule.id -> tenantRule)).runNow

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
  restTestSetUp.mockParameters.paramsRepo.paramsMap.update(_ + (tenantParam.name -> tenantParam)).runNow

  // ─── build one LiftRules per user type ────────────────────────────────────

  private def makeRules(grant: TenantAccessGrant): LiftRules = {
    val us = new TestUserServiceWithGrant(grant)
    TraitTestApiFromYamlFiles.buildLiftRules(restTestSetUp.apiModules, restTestSetUp.apiVersions, Some(us))._2
  }

  val users: Map[String, LiftRules] = Map(
    "admin" -> makeRules(TenantAccessGrant.All),
    "zoneA" -> makeRules(
      TenantAccessGrant.ByTenants(zio.Chunk(TenantAccess(TenantId("zoneA"), TenantPermission.ReadWrite)))
    ),
    "zoneB" -> makeRules(
      TenantAccessGrant.ByTenants(zio.Chunk(TenantAccess(TenantId("zoneB"), TenantPermission.ReadWrite)))
    ),
    "none"  -> makeRules(TenantAccessGrant.None)
  )

  // ─── test spec ────────────────────────────────────────────────────────────

  val tmpTenantTemplate: File = restTestSetUp.baseTempDirectory / "tenantApiTemplates"
  tmpTenantTemplate.createDirectories()

  override def spec: Spec[TestEnvironment & Scope, Any] = {
    suite("Tenant-aware REST API tests (multi-user)") {
      for {
        s <- TraitTestApiFromYamlFiles.doTestMultiUser(
               "api-tenant",
               tmpTenantTemplate,
               users,
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
