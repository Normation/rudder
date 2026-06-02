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

package com.normation.rudder.repository.ldap

import com.normation.GitVersion
import com.normation.eventlog.EventActor
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.nodes.NodeGroupCategoryId
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.nodes.NodeGroupUid
import com.normation.rudder.domain.policies.ActiveTechniqueCategory
import com.normation.rudder.domain.policies.ActiveTechniqueCategoryId
import com.normation.rudder.domain.policies.DirectiveUid
import com.normation.rudder.domain.policies.Rule
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.RuleUid
import com.normation.rudder.domain.properties.GlobalParameter
import com.normation.rudder.domain.properties.Visibility
import com.normation.rudder.rule.category.RuleCategoryId
import com.normation.rudder.tenants.ChangeContext
import com.normation.rudder.tenants.QueryContext
import com.normation.rudder.tenants.SecurityTag
import com.normation.rudder.tenants.TenantAccessGrant
import com.normation.rudder.tenants.TenantId
import com.normation.zio.*
import org.junit.runner.*
import org.specs2.mutable.*
import org.specs2.runner.*
import zio.Chunk

/**
 * Test import of a new technique library
 */
@RunWith(classOf[JUnitRunner])
class LdapRepositoryTenantTest extends Specification with SetupLdapRepositories {

  org.slf4j.LoggerFactory
    .getLogger("application.tenants")
    .asInstanceOf[ch.qos.logback.classic.Logger]
    .setLevel(ch.qos.logback.classic.Level.DEBUG)

  val zoneA   = QueryContext(EventActor("zoneA user"), TenantAccessGrant.ByTenants(Chunk(TenantId("zoneA"))))
  val zoneB   = QueryContext(EventActor("zoneB user"), TenantAccessGrant.ByTenants(Chunk(TenantId("zoneB"))))
  val zoneC   = QueryContext(EventActor("zoneC user"), TenantAccessGrant.ByTenants(Chunk(TenantId("zoneC"))))
  val zoneABC = QueryContext(
    EventActor("zoneA+B+C user"),
    TenantAccessGrant.ByTenants(Chunk(TenantId("zoneA"), TenantId("zoneB"), TenantId("zoneC")))
  )

  val groupWithTenantId = NodeGroupId(NodeGroupUid("test-group-node1"))

  val rootCat = NodeGroupCategoryId("GroupRoot")

  sequential

  // the tenant feature status does not matter for query. Perhaps it should?
  "[Groups] Whichever tenant plugin status, we" should {

    val targetWithNoTenants = {
      Set(
        "special:all_exceptPolicyServers",
        "special:all_policyServers",
        "special:all",
        "group:all-nodes-with-cfengine-agent",
        "group:test-group-node2",
        "group:test-group-node12",
        "group:test-group-node23",
        "group:AIXSystems"
      )
    }
    val targetWithTenantsA  = Set("group:test-group-node1")

    "have an admin with tenant=* able to see everything" in {
      implicit val qc = QueryContext.systemQC
      roGroupRepo.getFullGroupLibrary().runNow.allTargets.values.map(_.debugId) must containTheSameElementsAs(
        targetWithNoTenants.toList ++ targetWithTenantsA
      )
    }
  }

  // read-time filtering on the other read APIs (filtering depends only on the access grant,
  // not on the plugin status, so these are independent of the tenant feature being enabled)
  "[Groups] Reading groups" should {
    "have admin (tenant=*) see the zoneA group in getAll" in {
      implicit val qc = QueryContext.systemQC
      roGroupRepo.getAll().runNow.map(_.id).contains(groupWithTenantId) must beTrue
    }
    "have a zoneA user see the zoneA group in getAll" in {
      implicit val qc = zoneA
      roGroupRepo.getAll().runNow.map(_.id).contains(groupWithTenantId) must beTrue
    }
    "have a zoneB user NOT see the zoneA group in getAll" in {
      implicit val qc = zoneB
      roGroupRepo.getAll().runNow.map(_.id).contains(groupWithTenantId) must beFalse
    }
    "have a zoneA user see the zoneA group as a single read (getNodeGroupOpt)" in {
      implicit val qc = zoneA
      roGroupRepo.getNodeGroupOpt(groupWithTenantId).runNow.map(_._1.id) must beSome(groupWithTenantId)
    }
    "have a zoneB user NOT see the zoneA group as a single read (getNodeGroupOpt)" in {
      implicit val qc = zoneB
      roGroupRepo.getNodeGroupOpt(groupWithTenantId).runNow must beNone
    }
    "have a zoneA user see the zoneA group in getAllNodeIds" in {
      implicit val qc = zoneA
      roGroupRepo.getAllNodeIds().runNow.keySet.contains(groupWithTenantId) must beTrue
    }
    "have a zoneB user NOT see the zoneA group in getAllNodeIds" in {
      implicit val qc = zoneB
      roGroupRepo.getAllNodeIds().runNow.keySet.contains(groupWithTenantId) must beFalse
    }
    "have a zoneB user NOT see the zoneA group in findGroupWithAnyMember" in {
      implicit val qc    = zoneB
      // the zoneA group's members, if any, must not leak to a zoneB user
      val membersOfZoneA =
        roGroupRepo.getNodeGroupOpt(groupWithTenantId)(using QueryContext.systemQC).runNow.map(_._1.serverList).getOrElse(Set())
      roGroupRepo.findGroupWithAnyMember(membersOfZoneA.toSeq).runNow.contains(groupWithTenantId) must beFalse
    }
  }

  // write operations on a group must be refused for a user that can't see it, whatever the plugin status
  "[Groups] Writing a group a user can't see" should {
    "refuse to delete it" in {
      implicit val cc = zoneB.newCC()
      woGroupRepo.delete(groupWithTenantId).either.runNow must beLeft
    }
    "refuse to move it" in {
      implicit val cc = zoneB.newCC()
      woGroupRepo.move(groupWithTenantId, rootCat).either.runNow must beLeft
    }
    "refuse to update its node list" in {
      implicit val cc = zoneB.newCC()
      woGroupRepo.updateDiffNodes(groupWithTenantId, Nil, Nil).either.runNow must beLeft
    }
    "still be visible to admin afterwards (it was not modified)" in {
      implicit val qc = QueryContext.systemQC
      roGroupRepo.getNodeGroupOpt(groupWithTenantId).runNow.map(_._1.id) must beSome(groupWithTenantId)
    }
  }

  def newGroup(id: String, tenant: Option[SecurityTag]): NodeGroup =
    NodeGroup(NodeGroupId(NodeGroupUid(id)), id, id, Nil, None, serverList = Set(), _isEnabled = true, security = tenant)

  "[Groups] Creating a group" should {
    "put no security tag if it's a Grant '*', even if plugin disabled" in {
      implicit val cc = ChangeContext.newForRudder()
      val group       = newGroup("grantAll-feature-disabled", None)
      (woGroupRepo.create(group, rootCat) *> roGroupRepo.getNodeGroup(group.id)(using cc.toQC)).runNow._1.security must beNone
    }
    "lead to an error if the user has a tenant and the plugin is disabled" in {
      implicit val cc = zoneA.newCC()
      val group       = newGroup("grantZoneA-feature-disabled", None)
      (woGroupRepo.create(group, rootCat) *> roGroupRepo.getNodeGroup(group.id)(using cc.toQC)).either.runNow.left
        .map(_.msg) must beLeft(
        beEqualTo("Object 'grantZoneA-feature-disabled' [*] can't be modified by 'zoneA user' (perm:tags:[zoneA])")
      )
    }
    "automatically get the correct tenant when the plugin is enabled" in {
      implicit val cc = zoneA.newCC()

      val group = newGroup("grantZoneA-feature-enabled", None)
      (tenantRepo.setTenantEnabled(true) *> woGroupRepo.create(group, rootCat) *> roGroupRepo.getNodeGroup(group.id)(using
        cc.toQC
      )).runNow._1.security must beEqualTo(zoneA.accessGrant.toSecurityTag)
    }
  }
  "[Groups] Updating a group" should {
    "doesn't change the tag if the plugin is disabled" in {
      implicit val cc = ChangeContext.newForRudder()

      val res = for {
        _ <- tenantRepo.setTenantEnabled(false)
        g <- roGroupRepo.getNodeGroup(groupWithTenantId)(using cc.toQC).map(_._1)
        h  = g.copy(security = None)
        _ <- woGroupRepo.update(h)
        i <- roGroupRepo.getNodeGroup(groupWithTenantId)(using cc.toQC).map(_._1)
      } yield i.security

      res.runNow must beEqualTo(zoneA.accessGrant.toSecurityTag)
    }

    "lead to an error if the user has a tenant and the plugin is disabled" in {
      implicit val cc = zoneA.newCC()
      val group       = newGroup(groupWithTenantId.uid.value, None)
      woGroupRepo
        .update(group)
        .either
        .runNow
        .left
        .map(_.msg) must beLeft(
        beEqualTo("Object 'test-group-node1' [*] can't be modified by 'zoneA user' (perm:tags:[zoneA])")
      )
    }

    "allow tenant update if the plugin is enabled and user as correct rights" in {
      implicit val cc = zoneABC.newCC()

      // groupWithTenantId is in zoneA
      val res = for {
        _ <- tenantRepo.setTenantEnabled(true)
        g <- roGroupRepo.getNodeGroup(groupWithTenantId)(using cc.toQC).map(_._1)
        h  = g.copy(security = zoneB.accessGrant.toSecurityTag)
        _ <- woGroupRepo.update(h)
        i <- roGroupRepo.getNodeGroup(groupWithTenantId)(using cc.toQC).map(_._1)
      } yield i.security

      res.runNow must beEqualTo(zoneB.accessGrant.toSecurityTag)
    }
  }
  "get an error if the destination tenant ID does not exist" in {
    implicit val cc = zoneABC.newCC()

    // groupWithTenantId is in zoneA
    val res = for {
      _ <- tenantRepo.setTenantEnabled(true)
      g <- roGroupRepo.getNodeGroup(groupWithTenantId)(using cc.toQC).map(_._1)
      h  = g.copy(security = zoneC.accessGrant.toSecurityTag)
      _ <- woGroupRepo.update(h)
    } yield ()

    res.either.runNow.left.map(_.msg) must beLeft(
      beEqualTo("Object 'test-group-node1' security tag's tenant can not be updated to 'zoneC' because it does not exist")
    )
  }

  // in the test technique library, the active technique `user_defined_tech1` and its directive
  // `ce8aec6f-...` (and the parent category `ncf_techniques`) are tagged with tenant `zoneA`.
  val directiveWithTenantA = DirectiveUid("ce8aec6f-d371-4047-96d1-6b69ccdef9ae")
  val atRootCat            = ActiveTechniqueCategoryId("Active Techniques")

  "[Directives] Reading the full directive library" should {
    "let an admin (tenant=*) see the zoneA active technique and directive" in {
      given qc: QueryContext = QueryContext.systemQC
      val lib = roDirectiveRepo.getFullDirectiveLibrary().runNow
      (lib.allActiveTechniques.keySet.map(_.value).contains("user_defined_tech1") must beTrue) and
      (lib.allDirectives.keySet.map(_.uid.value).contains(directiveWithTenantA.value) must beTrue)
    }
    "let a zoneA user see the zoneA active technique and directive" in {
      given qc: QueryContext = zoneA
      val lib = roDirectiveRepo.getFullDirectiveLibrary().runNow
      (lib.allActiveTechniques.keySet.map(_.value).contains("user_defined_tech1") must beTrue) and
      (lib.allDirectives.keySet.map(_.uid.value).contains(directiveWithTenantA.value) must beTrue)
    }
    "hide the zoneA active technique and directive from a zoneB user" in {
      given qc: QueryContext = zoneB
      val lib = roDirectiveRepo.getFullDirectiveLibrary().runNow
      (lib.allActiveTechniques.keySet.map(_.value).contains("user_defined_tech1") must beFalse) and
      (lib.allDirectives.keySet.map(_.uid.value).contains(directiveWithTenantA.value) must beFalse)
    }
  }

  "[Directives] Reading a single directive" should {
    "be visible to a zoneA user" in {
      given qc: QueryContext = zoneA
      roDirectiveRepo.getDirective(directiveWithTenantA).runNow.map(_.id.uid.value) must beSome(directiveWithTenantA.value)
    }
    "be hidden from a zoneB user" in {
      given qc: QueryContext = zoneB
      roDirectiveRepo.getDirective(directiveWithTenantA).runNow must beNone
    }
  }

  "[Directives] Deleting a directive" should {
    "be refused for a user who can not see it" in {
      given cc: ChangeContext = zoneB.newCC()
      woDirectiveRepo.delete(directiveWithTenantA).either.runNow.left.map(_.msg) must beLeft(
        beEqualTo(s"Object '${directiveWithTenantA.value}' can't be deleted by zoneB user")
      )
    }
  }

  def newActiveTechniqueCategory(id: String, tenant: Option[SecurityTag]): ActiveTechniqueCategory =
    ActiveTechniqueCategory(ActiveTechniqueCategoryId(id), id, id, Nil, Nil, isSystem = false, security = tenant)

  "[ActiveTechniqueCategories] Creating a category" should {
    "lead to an error if the user has a tenant and the plugin is disabled" in {
      given cc: ChangeContext = zoneA.newCC()
      val cat = newActiveTechniqueCategory("cat-zoneA-feature-disabled", None)
      (tenantRepo.setTenantEnabled(false) *> woDirectiveRepo.addActiveTechniqueCategory(cat, atRootCat)).either.runNow.left
        .map(_.msg) must beLeft(
        beEqualTo("Object 'cat-zoneA-feature-disabled' [*] can't be modified by 'zoneA user' (perm:tags:[zoneA])")
      )
    }
    "automatically get the correct tenant when the plugin is enabled" in {
      given cc: ChangeContext = zoneA.newCC()
      val cat = newActiveTechniqueCategory("cat-zoneA-feature-enabled", None)
      (tenantRepo.setTenantEnabled(true) *>
      woDirectiveRepo.addActiveTechniqueCategory(cat, atRootCat) *>
      roDirectiveRepo.getActiveTechniqueCategory(cat.id)(using QueryContext.systemQC)).runNow
        .flatMap(_.security) must beEqualTo(zoneA.accessGrant.toSecurityTag)
    }
  }

  // in the test data, the rule `34323555-...` is tagged with tenant `zoneA`.
  val ruleWithTenantA = RuleId(RuleUid("34323555-6b6b-4d07-b3bd-043df1239797"))

  "[Rules] Reading all rules" should {
    "let an admin (tenant=*) see the zoneA rule" in {
      given qc: QueryContext = QueryContext.systemQC
      roRuleRepo.getAll().runNow.map(_.id.serialize).contains(ruleWithTenantA.serialize) must beTrue
    }
    "let a zoneA user see the zoneA rule" in {
      given qc: QueryContext = zoneA
      roRuleRepo.getAll().runNow.map(_.id.serialize).contains(ruleWithTenantA.serialize) must beTrue
    }
    "hide the zoneA rule from a zoneB user" in {
      given qc: QueryContext = zoneB
      roRuleRepo.getAll().runNow.map(_.id.serialize).contains(ruleWithTenantA.serialize) must beFalse
    }
  }

  "[Rules] Reading a single rule" should {
    "be visible to a zoneA user" in {
      given qc: QueryContext = zoneA
      roRuleRepo.getOpt(ruleWithTenantA).runNow.map(_.id.serialize) must beSome(ruleWithTenantA.serialize)
    }
    "be hidden from a zoneB user" in {
      given qc: QueryContext = zoneB
      roRuleRepo.getOpt(ruleWithTenantA).runNow must beNone
    }
  }

  "[Rules] Deleting a rule" should {
    "be refused for a user who can not see it" in {
      given cc: ChangeContext = zoneB.newCC()
      woRuleRepo.delete(ruleWithTenantA).either.runNow.left.map(_.msg) must beLeft(
        beEqualTo(s"Object '${ruleWithTenantA.serialize}' can't be deleted by zoneB user")
      )
    }
  }

  def newRule(id: String, tenant: Option[SecurityTag]): Rule =
    Rule(RuleId(RuleUid(id)), id, RuleCategoryId("rootRuleCategory"), security = tenant)

  "[Rules] Creating a rule" should {
    "lead to an error if the user has a tenant and the plugin is disabled" in {
      given cc: ChangeContext = zoneA.newCC()
      val rule = newRule("rule-zoneA-feature-disabled", None)
      (tenantRepo.setTenantEnabled(false) *> woRuleRepo.create(rule)).either.runNow.left.map(_.msg) must beLeft(
        beEqualTo("Object 'rule-zoneA-feature-disabled' [*] can't be modified by 'zoneA user' (perm:tags:[zoneA])")
      )
    }
    "automatically get the correct tenant when the plugin is enabled" in {
      given cc: ChangeContext = zoneA.newCC()
      val rule = newRule("rule-zoneA-feature-enabled", None)
      (tenantRepo.setTenantEnabled(true) *> woRuleRepo.create(rule) *>
      roRuleRepo.getOpt(rule.id)(using QueryContext.systemQC)).runNow
        .flatMap(_.security) must beEqualTo(zoneA.accessGrant.toSecurityTag)
    }
  }

  // in the test data, the global parameter `param-zoneA` is tagged with tenant `zoneA`.
  val parameterWithTenantA = "param-zoneA"

  "[Parameters] Reading all parameters" should {
    "let an admin (tenant=*) see the zoneA parameter" in {
      given qc: QueryContext = QueryContext.systemQC
      roGlobalPropertyRepo.getAllGlobalParameters().runNow.map(_.name).contains(parameterWithTenantA) must beTrue
    }
    "let a zoneA user see the zoneA parameter" in {
      given qc: QueryContext = zoneA
      roGlobalPropertyRepo.getAllGlobalParameters().runNow.map(_.name).contains(parameterWithTenantA) must beTrue
    }
    "hide the zoneA parameter from a zoneB user" in {
      given qc: QueryContext = zoneB
      roGlobalPropertyRepo.getAllGlobalParameters().runNow.map(_.name).contains(parameterWithTenantA) must beFalse
    }
  }

  "[Parameters] Reading a single parameter" should {
    "be visible to a zoneA user" in {
      given qc: QueryContext = zoneA
      roGlobalPropertyRepo.getGlobalParameter(parameterWithTenantA).runNow.map(_.name) must beSome(parameterWithTenantA)
    }
    "be hidden from a zoneB user" in {
      given qc: QueryContext = zoneB
      roGlobalPropertyRepo.getGlobalParameter(parameterWithTenantA).runNow must beNone
    }
  }

  "[Parameters] Deleting a parameter" should {
    "be refused for a user who can not see it" in {
      given cc: ChangeContext = zoneB.newCC()
      woGlobalPropertyRepo.delete(parameterWithTenantA, None).either.runNow.left.map(_.msg) must beLeft(
        beEqualTo(s"Object '$parameterWithTenantA' can't be deleted by zoneB user")
      )
    }
  }

  def newParameter(name: String, tenant: Option[SecurityTag]): GlobalParameter = {
    GlobalParameter
      .parse(name, GitVersion.DEFAULT_REV, "\"" + name + "\"", None, "", None, Visibility.default, tenant)
      .getOrElse(throw new RuntimeException(s"Error in test: can not build parameter '$name'"))
  }

  "[Parameters] Creating a parameter" should {
    "lead to an error if the user has a tenant and the plugin is disabled" in {
      given cc: ChangeContext = zoneA.newCC()
      val param = newParameter("param-zoneA-feature-disabled", None)
      (tenantRepo.setTenantEnabled(false) *> woGlobalPropertyRepo.saveParameter(param)).either.runNow.left
        .map(_.msg) must beLeft(
        beEqualTo("Object 'param-zoneA-feature-disabled' [*] can't be modified by 'zoneA user' (perm:tags:[zoneA])")
      )
    }
    "automatically get the correct tenant when the plugin is enabled" in {
      given cc: ChangeContext = zoneA.newCC()
      val param = newParameter("param-zoneA-feature-enabled", None)
      (tenantRepo.setTenantEnabled(true) *> woGlobalPropertyRepo.saveParameter(param) *>
      roGlobalPropertyRepo.getGlobalParameter(param.name)(using QueryContext.systemQC)).runNow
        .flatMap(_.security) must beEqualTo(zoneA.accessGrant.toSecurityTag)
    }
  }
}
