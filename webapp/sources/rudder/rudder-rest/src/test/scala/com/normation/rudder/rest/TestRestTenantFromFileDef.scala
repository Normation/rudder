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
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.nodes.NodeGroupCategoryId
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.nodes.NodeGroupUid
import com.normation.rudder.domain.policies.ActiveTechniqueId
import com.normation.rudder.domain.policies.AllTarget
import com.normation.rudder.domain.policies.Directive
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.DirectiveUid
import com.normation.rudder.domain.policies.Rule
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.RuleUid
import com.normation.rudder.domain.policies.Tags
import com.normation.rudder.domain.properties.GenericProperty.*
import com.normation.rudder.domain.properties.GlobalParameter
import com.normation.rudder.domain.properties.Visibility
import com.normation.rudder.repository.FullActiveTechniqueCategory
import com.normation.rudder.rule.category.RuleCategoryId
import com.normation.rudder.tenants.ChangeContext
import com.normation.rudder.tenants.QueryContext
import com.normation.rudder.tenants.SecurityTag
import com.normation.rudder.tenants.TenantId
import com.normation.zio.*
import com.softwaremill.quicklens.*
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

  // A second zoneA+zoneB rule, never deleted by the test files, used to probe the create/update laws
  // (law 7/8) with the single-tenant `zoneA` user.
  val lawRule: Rule = tenantRule
    .modify(_.id)
    .setTo(RuleId(RuleUid("law-rule-shared")))
    .modify(_.name)
    .setTo("law shared rule")

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

  // the security tag shared by all tenant-restricted test objects
  val tenantTag: SecurityTag = SecurityTag.ByTenants(zio.Chunk(TenantId("zoneA"), TenantId("zoneB")))

  // Node group restricted to zoneA + zoneB
  val tenantGroup: NodeGroup = NodeGroup(
    NodeGroupId(NodeGroupUid("tenant-group-1")),
    name = "Tenant test group",
    description = "a tenant-restricted group",
    properties = Nil,
    query = None,
    isDynamic = false,
    serverList = Set(),
    _isEnabled = true,
    security = Some(tenantTag)
  )

  // We restrict an *existing* active technique + directive to zoneA + zoneB rather than the ones
  // used by `tenantRule` (clockConfiguration / directive1), so that the rule application status
  // assertions stay stable. We tag `rpmPackageInstallation` and its `directive2`.
  // The active-technique listing filters on the *category* visibility, so we also tag the category
  // that directly holds the technique, otherwise tenant users would not see it at all.
  def tagTenantTree(c: FullActiveTechniqueCategory): FullActiveTechniqueCategory = {
    val hasRpm = c.activeTechniques.exists(_.techniqueName.value == "rpmPackageInstallation")
    val tagged = c
      .modify(_.subCategories)
      .using(_.map(tagTenantTree))
      .modify(_.activeTechniques)
      .using(_.map { at =>
        if (at.techniqueName.value == "rpmPackageInstallation") {
          at.modify(_.security)
            .setTo(Some(tenantTag))
            .modify(_.directives)
            .using(_.map(d => if (d.id.uid.value == "directive2") d.modify(_.security).setTo(Some(tenantTag)) else d))
        } else at
      })
    if (hasRpm) tagged.modify(_.security).setTo(Some(tenantTag)) else tagged
  }

  // snapshot of the untagged `directive2` taken once, before any test mutates it, so that the
  // destructive delete tests can be restored on a subsequent (idempotent) evaluation of `spec`.
  val origDirective2: Option[(ActiveTechniqueId, Directive)] = restTestSetUp.mockDirectives.directiveRepoImpl
    .getActiveTechniqueAndDirective(DirectiveId(DirectiveUid("directive2")))(using QueryContext.systemQC)
    .runNow
    .map { case (at, d) => (at.id, d) }

  val tmpTenantTemplate: File = restTestSetUp.baseTempDirectory / "tenantApiTemplates"
  tmpTenantTemplate.createDirectories()

  override def spec: Spec[TestEnvironment & Scope, Any] = {
    suite("Tenant-aware REST API tests (multi-user)") {
      for {
        _      <- restTestSetUp.mockParameters.paramsRepo.paramsMap.update(_ + (tenantParam.name -> tenantParam))
        _      <- restTestSetUp.mockRules.ruleRepo.rulesMap.update(_ + (tenantRule.id -> tenantRule))
        _      <- restTestSetUp.mockRules.ruleRepo.rulesMap.update(_ + (lawRule.id -> lawRule))
        // seeding must be idempotent: `spec` may be evaluated more than once by the test runner
        exists <- restTestSetUp.mockNodeGroups.groupsRepoImpl.categories.get.map(_.allGroups.contains(tenantGroup.id))
        _      <-
          ZIO.unless(exists)(
            restTestSetUp.mockNodeGroups.groupsRepoImpl
              .create(tenantGroup, NodeGroupCategoryId("GroupRoot"))(using ChangeContext.newForRudder(Some("seed tenant group")))
          )
        // restore directive2 if a previous evaluation deleted it, then (re)apply the tenant tags
        dirOk  <- restTestSetUp.mockDirectives.directiveRepoImpl
                    .getActiveTechniqueAndDirective(DirectiveId(DirectiveUid("directive2")))(using QueryContext.systemQC)
                    .map(_.isDefined)
        _      <- ZIO.unless(dirOk)(
                    ZIO.foreachDiscard(origDirective2.toList) {
                      case (atId, d) =>
                        restTestSetUp.mockDirectives.directiveRepoImpl
                          .saveDirective(atId, d)(using ChangeContext.newForRudder(Some("restore directive2")))
                    }
                  )
        _      <- restTestSetUp.mockDirectives.rootActiveTechniqueCategory.update(tagTenantTree)
        _      <- restTestSetUp.mockTenants.tenantRepo.setTenantEnabled(true)
        s      <- TraitTestApiFromYamlFiles.doTest(
                    "api-tenant",
                    tmpTenantTemplate,
                    restTestSetUp.liftRules,
                    restTestSetUp.userService,
                    Nil,
                    Map.empty
                  )
        _      <- effectUioUnit(
                    if (java.lang.System.getProperty("tests.clean.tmp") != "false") IOResult.attempt(restTestSetUp.cleanup())
                    else ZIO.unit
                  )
      } yield s
    } @@ TestAspect.sequential
  }
}
