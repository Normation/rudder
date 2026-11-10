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
 * Related Module is not considered as a Modified Source Version, nor
 * as a Non-Source Form. You can redistribute it and/or modify that
 * Related Module under the terms of the GNU General Public License
 * version 3 as published by the Free Software Foundation.
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

import com.normation.cfclerk.domain.TechniqueId
import com.normation.eventlog.EventActor
import com.normation.rudder.MockNodes
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.nodes.NodeGroupUid
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.DirectiveUid
import com.normation.rudder.domain.policies.GroupTarget
import com.normation.rudder.domain.policies.Rule
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.RuleUid
import com.normation.rudder.domain.policies.Tags
import com.normation.rudder.git.ZipUtils.Zippable
import com.normation.rudder.rest.lift.ArchiveScope
import com.normation.rudder.rest.lift.SpecialExportAll
import com.normation.rudder.rest.lift.ZipArchiveBuilderService
import com.normation.rudder.rule.category.RuleCategoryId
import com.normation.rudder.tenants.ChangeContext
import com.normation.rudder.tenants.QueryContext
import com.normation.rudder.tenants.SecurityTag
import com.normation.rudder.tenants.TenantAccess
import com.normation.rudder.tenants.TenantAccessGrant
import com.normation.rudder.tenants.TenantId
import com.normation.zio.*
import com.softwaremill.quicklens.*
import java.io.InputStream
import java.nio.charset.StandardCharsets
import org.junit.runner.RunWith
import zio.*
import zio.test.*
import zio.test.junit.ZTestJUnitRunner

/*
 * The archive export only contains what the query context can read: an object requested by id that it can
 * not read is "not found", a dependency it can not read is left out.
 */
@RunWith(classOf[ZTestJUnitRunner])
class ArchiveTenantTest extends ZIOSpecDefault {

  val restTestSetUp: RestTestSetUp            = RestTestSetUp.newEnv
  val builder:       ZipArchiveBuilderService = restTestSetUp.archiveAPIModule.archiveBuilderService

  val zoneA: QueryContext =
    QueryContext(EventActor("zoneA user"), TenantAccessGrant.ByTenants(Chunk(TenantAccess(TenantId("zoneA")))))
  val admin: QueryContext = QueryContext.testQC

  val zoneATag: SecurityTag = SecurityTag.ByTenants(Chunk(TenantId("zoneA")))

  // a zoneA static group holding admin-only nodes
  val zoneAGroup: NodeGroup = restTestSetUp.mockNodeGroups.g0
    .modify(_.id)
    .setTo(NodeGroupId(NodeGroupUid("zoneA-group")))
    .modify(_.name)
    .setTo("zoneA group")
    .modify(_.security)
    .setTo(Some(zoneATag))

  // a zoneA rule, in the admin-only `category1`, using an admin-only directive and targeting both the
  // zoneA group and the admin-only group g1
  val zoneARule: Rule = Rule(
    RuleId(RuleUid("zoneA-rule")),
    "zoneA rule",
    RuleCategoryId("category1"),
    Set(GroupTarget(zoneAGroup.id), GroupTarget(restTestSetUp.mockNodeGroups.g1.id)),
    Set(DirectiveId(DirectiveUid("directive1"))),
    "",
    "",
    isEnabledStatus = true,
    isSystem = false,
    Tags(Set()),
    security = Some(zoneATag)
  )

  val allScopes: Set[ArchiveScope] = Set(ArchiveScope.AllDep)

  def build(
      techniques: Seq[TechniqueId] = Nil,
      directives: Seq[DirectiveId] = Nil,
      groups:     Seq[NodeGroupId] = Nil,
      rules:      Seq[RuleId] = Nil,
      scopes:     Set[ArchiveScope] = allScopes
  )(using qc: QueryContext): IO[Any, Chunk[Zippable]] = {
    builder.buildArchive("archive", techniques, directives, groups, rules, scopes)
  }

  def paths(zips: Chunk[Zippable], dir: String): Set[String] = {
    zips.map(_.path).filter(_.startsWith(s"archive/${dir}/")).toSet
  }

  def content(zips: Chunk[Zippable], path: String): IO[Any, String] = {
    zips.find(_.path == path) match {
      case Some(Zippable(_, Some(use))) =>
        use((is: InputStream) => com.normation.errors.IOResult.attempt(new String(is.readAllBytes(), StandardCharsets.UTF_8)))
          .map(_.asInstanceOf[String])
      case _                            => ZIO.fail(s"no content for '${path}'")
    }
  }

  // seeded once: tests run in parallel
  (for {
    _ <- restTestSetUp.mockRules.ruleRepo.rulesMap.update(_ + (zoneARule.id -> zoneARule))
    e <- restTestSetUp.mockNodeGroups.groupsRepoImpl.categories.get.map(_.allGroups.contains(zoneAGroup.id))
    _ <- ZIO.unless(e) {
           restTestSetUp.mockNodeGroups.groupsRepoImpl
             .create(zoneAGroup, com.normation.rudder.domain.Constants.ROOT_GROUP_CATEGORY)(using
               ChangeContext.newForRudder(Some("seed zoneA group"))
             )
         }
  } yield ()).runNow

  override def spec: Spec[TestEnvironment & Scope, Any] = {
    suite("archive export under a tenant query context")(
      test("a readable rule is exported without its unreadable dependencies") {
        for {
          zips <- build(rules = List(zoneARule.id))(using zoneA)
        } yield {
          assertTrue(paths(zips, "rules") == Set("archive/rules/zoneA_rule.json")) &&
          assertTrue(paths(zips, "directives").isEmpty) &&
          assertTrue(paths(zips, "groups") == Set("archive/groups/zoneA_group.json")) &&
          assertTrue(paths(zips, "techniques").isEmpty)
        }
      },
      test("the same export by an administrator contains the dependencies") {
        for {
          zips <- build(rules = List(zoneARule.id))(using admin)
        } yield {
          assertTrue(paths(zips, "directives").nonEmpty) &&
          assertTrue(paths(zips, "groups") == Set("archive/groups/zoneA_group.json", "archive/groups/Empty_group.json")) &&
          assertTrue(paths(zips, "techniques").nonEmpty)
        }
      },
      test("rule categories are those the reader can see") {
        for {
          zoneZ <- build(rules = List(zoneARule.id))(using zoneA)
          adm   <- build(rules = List(zoneARule.id))(using admin)
          catsZ <- content(zoneZ, s"archive/${ZipArchiveBuilderService.RULE_CATS}")
          catsA <- content(adm, s"archive/${ZipArchiveBuilderService.RULE_CATS}")
        } yield {
          assertTrue(!catsZ.contains("category1")) && assertTrue(catsA.contains("category1"))
        }
      },
      test("the nodes of an exported group are those the reader can see") {
        for {
          zoneZ <- build(groups = List(zoneAGroup.id), scopes = Set(ArchiveScope.NoDep))(using zoneA)
          adm   <- build(groups = List(zoneAGroup.id), scopes = Set(ArchiveScope.NoDep))(using admin)
          gZ    <- content(zoneZ, "archive/groups/zoneA_group.json")
          gA    <- content(adm, "archive/groups/zoneA_group.json")
        } yield {
          assertTrue(!gZ.contains(MockNodes.node1.id.value)) && assertTrue(gA.contains(MockNodes.node1.id.value))
        }
      },
      test("an object requested by id that the reader can not see is not found") {
        for {
          r <- build(rules = List(RuleId(RuleUid("rule1"))))(using zoneA).either
          d <- build(directives = List(DirectiveId(DirectiveUid("directive1"))))(using zoneA).either
          g <- build(groups = List(restTestSetUp.mockNodeGroups.g1.id))(using zoneA).either
          t <- build(techniques = List(TechniqueId.parse("technique_any/1.0").getOrElse(???)))(using zoneA).either
        } yield {
          assertTrue(r.isLeft, d.isLeft, g.isLeft, t.isLeft)
        }
      },
      test("exporting all techniques only gives the readable ones") {
        for {
          zZ <- build(techniques = List(SpecialExportAll.allTechniques), scopes = Set(ArchiveScope.NoDep))(using zoneA)
          zA <- build(techniques = List(SpecialExportAll.allTechniques), scopes = Set(ArchiveScope.NoDep))(using admin)
          ofZ = paths(zZ, "techniques")
          ofA = paths(zA, "techniques")
        } yield {
          assertTrue(ofA.exists(_.contains("/technique_any/"))) &&
          assertTrue(!ofZ.exists(_.contains("/technique_any/"))) &&
          assertTrue(ofZ.subsetOf(ofA))
        }
      }
    )
  }
}
