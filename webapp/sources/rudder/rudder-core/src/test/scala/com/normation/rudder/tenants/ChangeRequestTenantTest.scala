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

package com.normation.rudder.tenants

import com.normation.GitVersion
import com.normation.eventlog.EventActor
import com.normation.rudder.domain.policies.ModifyToRuleDiff
import com.normation.rudder.domain.policies.Rule
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.RuleUid
import com.normation.rudder.domain.properties.ChangeRequestGlobalParameterDiff
import com.normation.rudder.domain.properties.GenericProperty.*
import com.normation.rudder.domain.properties.GlobalParameter
import com.normation.rudder.domain.properties.ModifyToGlobalParameterDiff
import com.normation.rudder.domain.properties.Visibility
import com.normation.rudder.domain.workflows.*
import com.normation.rudder.rule.category.RuleCategoryId
import com.normation.zio.*
import org.joda.time.DateTime
import org.junit.runner.*
import org.specs2.mutable.*
import org.specs2.runner.*
import zio.Chunk

/*
 * A change request is tenant-wise a compound MODIFY over every object it touches: it is visible/writable
 * only if the actor may see/modify EVERY one of them (AND, fail closed). Enforced in TenantCheckLogic.
 */
@RunWith(classOf[JUnitRunner])
class ChangeRequestTenantTest extends Specification {

  val checkTenant: TenantCheckLogic = new DefaultTenantCheckLogic()

  private def tenantTag(ids: String*): Option[SecurityTag] = Some(SecurityTag.ByTenants(Chunk.fromIterable(ids.map(TenantId(_)))))

  private def grant(accesses: TenantAccess*): QueryContext =
    QueryContext(EventActor("u"), TenantAccessGrant.ByTenants(Chunk.fromIterable(accesses)))

  val admin   = QueryContext.systemQC
  val zoneA   = grant(TenantAccess(TenantId("zoneA")))
  val zoneB   = grant(TenantAccess(TenantId("zoneB")))
  val zoneAB  = grant(TenantAccess(TenantId("zoneA")), TenantAccess(TenantId("zoneB")))
  // read-only access on zoneA: can see, but not modify
  val zoneAro = grant(TenantAccess(TenantId("zoneA"), TenantPermission.Read))

  private def rule(id: String, security: Option[SecurityTag]): Rule =
    Rule(RuleId(RuleUid(id)), id, RuleCategoryId("rootRuleCategory"), security = security)

  private def ruleChanges(r: Rule): RuleChanges =
    RuleChanges(RuleChange(Some(r), RuleChangeItem(EventActor("u"), DateTime.now(), None, ModifyToRuleDiff(r)), Seq()), Seq())

  private def param(name: String, security: Option[SecurityTag]): GlobalParameter =
    GlobalParameter(name, GitVersion.DEFAULT_REV, "v".toConfigValue, None, "", None, Visibility.default, security)

  private def paramChanges(p: GlobalParameter): GlobalParameterChanges = {
    val diff: ChangeRequestGlobalParameterDiff = ModifyToGlobalParameterDiff(p)
    GlobalParameterChanges(
      GlobalParameterChange(Some(p), GlobalParameterChangeItem(EventActor("u"), DateTime.now(), None, diff), Seq()),
      Seq()
    )
  }

  private def cr(rules: Map[RuleId, RuleChanges], params: Map[String, GlobalParameterChanges]): ConfigurationChangeRequest =
    ConfigurationChangeRequest(ChangeRequestId(1), None, ChangeRequestInfo("cr", ""), Map(), Map(), rules, params)

  // a change request touching a zoneA rule only
  val crZoneA     = cr(Map(RuleId(RuleUid("r1")) -> ruleChanges(rule("r1", tenantTag("zoneA")))), Map())
  // a change request touching a zoneA rule AND a zoneB parameter
  val crMixed     = cr(
    Map(RuleId(RuleUid("r1")) -> ruleChanges(rule("r1", tenantTag("zoneA")))),
    Map("p1"                  -> paramChanges(param("p1", tenantTag("zoneB"))))
  )
  // a change request touching an admin-only (untagged) rule
  val crAdminOnly = cr(Map(RuleId(RuleUid("r2")) -> ruleChanges(rule("r2", None))), Map())

  "[ChangeRequest] visibility (AND over touched objects)" should {
    "let admin see any change request" in {
      checkTenant.isChangeRequestVisible(crMixed)(using admin) must beTrue
    }
    "let a zoneA user see a zoneA-only change request" in {
      checkTenant.isChangeRequestVisible(crZoneA)(using zoneA) must beTrue
    }
    "hide a zoneA-only change request from a zoneB user" in {
      checkTenant.isChangeRequestVisible(crZoneA)(using zoneB) must beFalse
    }
    "hide a mixed zoneA+zoneB change request from a zoneA-only user (can not see the zoneB object)" in {
      checkTenant.isChangeRequestVisible(crMixed)(using zoneA) must beFalse
    }
    "show a mixed zoneA+zoneB change request to a zoneA+zoneB user" in {
      checkTenant.isChangeRequestVisible(crMixed)(using zoneAB) must beTrue
    }
    "hide an admin-only (untagged) change request from a tenant user" in {
      checkTenant.isChangeRequestVisible(crAdminOnly)(using zoneA) must beFalse
    }
  }

  "[ChangeRequest] write authorization (AND over touched objects)" should {
    "let admin act on any change request" in {
      checkTenant.checkChangeRequestModify(crMixed, admin.newCC()).either.runNow must beRight
    }
    "let a zoneA:rw user act on a zoneA-only change request" in {
      checkTenant.checkChangeRequestModify(crZoneA, zoneA.newCC()).either.runNow must beRight
    }
    "refuse a zoneA:rw user acting on a mixed zoneA+zoneB change request" in {
      checkTenant.checkChangeRequestModify(crMixed, zoneA.newCC()).either.runNow must beLeft
    }
    "refuse a read-only zoneA user acting even on a zoneA-only change request" in {
      checkTenant.checkChangeRequestModify(crZoneA, zoneAro.newCC()).either.runNow must beLeft
    }
  }

  // B4: on create, the tenant tag an admin chooses must reference only EXISTING tenants (like the update path),
  // otherwise a dangling/phantom tenant tag is created.
  "[create] admin-chosen tenant tag must reference existing tenants" should {
    val existing = TenantStatus.Enabled(Set(TenantId("zoneA"), TenantId("zoneB")))
    def create(r: Rule): Either[?, ?] =
      checkTenant.manageCreate(r, admin.newCC(), existing)(x => zio.ZIO.succeed(x)).either.runNow

    "accept an admin creating an object tagged with an existing tenant" in {
      create(rule("r", tenantTag("zoneA"))) must beRight
    }
    "reject an admin creating an object tagged with a non-existent tenant" in {
      create(rule("r", tenantTag("zoneX"))) must beLeft
    }
    "accept an admin creating an admin-only (untagged) object" in {
      create(rule("r", None)) must beRight
    }
  }
}
