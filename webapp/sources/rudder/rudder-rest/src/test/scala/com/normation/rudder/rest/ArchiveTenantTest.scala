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

import com.normation.cfclerk.domain.TechniqueCategoryId
import com.normation.cfclerk.domain.TechniqueCategoryMetadata
import com.normation.cfclerk.domain.TechniqueId
import com.normation.errors.*
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
import com.normation.rudder.rest.lift.GroupArchive
import com.normation.rudder.rest.lift.MergePolicy
import com.normation.rudder.rest.lift.PolicyArchive
import com.normation.rudder.rest.lift.SaveArchiveServicebyRepo
import com.normation.rudder.rest.lift.SpecialExportAll
import com.normation.rudder.rest.lift.TechniqueArchive
import com.normation.rudder.rest.lift.TechniqueCategoryArchive
import com.normation.rudder.rest.lift.TechniqueInfo
import com.normation.rudder.rest.lift.TechniqueType
import com.normation.rudder.rest.lift.ZipArchiveBuilderService
import com.normation.rudder.rule.category.RuleCategoryId
import com.normation.rudder.tenants.ChangeContext
import com.normation.rudder.tenants.QueryContext
import com.normation.rudder.tenants.SecurityTag
import com.normation.rudder.tenants.TenantAccess
import com.normation.rudder.tenants.TenantAccessGrant
import com.normation.rudder.tenants.TenantId
import com.normation.rudder.tenants.TenantPermission
import com.normation.utils.StringUuidGeneratorImpl
import com.normation.zio.*
import com.softwaremill.quicklens.*
import java.io.InputStream
import java.nio.charset.StandardCharsets
import net.liftweb.actor.MockLiftActor
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

  val zoneA:   QueryContext =
    QueryContext(EventActor("zoneA user"), TenantAccessGrant.ByTenants(Chunk(TenantAccess(TenantId("zoneA")))))
  val zoneAro: QueryContext = QueryContext(
    EventActor("zoneA read-only user"),
    TenantAccessGrant.ByTenants(Chunk(TenantAccess(TenantId("zoneA"), TenantPermission.Read)))
  )
  val admin:   QueryContext = QueryContext.testQC

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

  // the import tests each work on their own zoneA rule, in the open root category
  def importRule(id: String): Rule       = zoneARule
    .modify(_.id)
    .setTo(RuleId(RuleUid(id)))
    .modify(_.name)
    .setTo(id)
    .modify(_.categoryId)
    .setTo(com.normation.rudder.domain.Constants.ROOT_RULE_CATEGORY)
  val importRules:            List[Rule] = List("imp-unchanged", "imp-update", "imp-refused").map(importRule)

  // seeded once: tests run in parallel
  (for {
    _ <- restTestSetUp.mockTenants.tenantRepo.setTenantEnabled(true)
    _ <- restTestSetUp.mockRules.ruleRepo.rulesMap.update(_ ++ importRules.map(r => (r.id, r)))
    _ <- restTestSetUp.mockRules.ruleRepo.rulesMap.update(_ + (zoneARule.id -> zoneARule))
    e <- restTestSetUp.mockNodeGroups.groupsRepoImpl.categories.get.map(_.allGroups.contains(zoneAGroup.id))
    _ <- ZIO.unless(e) {
           restTestSetUp.mockNodeGroups.groupsRepoImpl
             .create(zoneAGroup, com.normation.rudder.domain.Constants.ROOT_GROUP_CATEGORY)(using
               ChangeContext.newForRudder(Some("seed zoneA group"))
             )
         }
  } yield ()).runNow

  // the import service on the tenant-filtering mock repositories; the archives of these tests hold no technique
  val saver: SaveArchiveServicebyRepo = new SaveArchiveServicebyRepo(
    null,
    null,
    restTestSetUp.mockTechniques.techniqueRepo,
    restTestSetUp.mockDirectives.directiveRepo,
    restTestSetUp.mockDirectives.directiveRepo,
    restTestSetUp.mockNodeGroups.groupsRepo,
    restTestSetUp.mockNodeGroups.groupsRepo,
    restTestSetUp.mockRules.ruleRepo,
    restTestSetUp.mockRules.ruleRepo,
    restTestSetUp.mockRules.ruleCategoryRepo,
    restTestSetUp.mockRules.ruleCategoryRepo,
    null,
    new MockLiftActor,
    new StringUuidGeneratorImpl(),
    restTestSetUp.mockTenants.checkTenant,
    restTestSetUp.mockNodes.nodeFactRepo.getVisibleNodes()
  )

  def withRules(rules: Rule*): PolicyArchive = PolicyArchive.empty("test").modify(_.rules).setTo(Chunk.fromIterable(rules))

  def stored(id: String): IO[Any, Option[Rule]] = restTestSetUp.mockRules.ruleRepo.getOpt(RuleId(RuleUid(id)))(using admin)

  def techniqueCategory(path: String): IO[Any, TechniqueCategoryArchive] = {
    for {
      id  <- TechniqueCategoryId.parse(path).toIO
      cat <- restTestSetUp.mockTechniques.techniqueRepo.getTechniqueCategory(id)
    } yield TechniqueCategoryArchive(
      TechniqueCategoryMetadata(cat.name, cat.description, cat.isSystem, cat.security),
      NonEmptyChunk.fromIterableOption(path.split("/")).getOrElse(throw new IllegalArgumentException(path))
    )
  }

  // a YAML technique with no tag, so administrator-only, as stored, plus one new file
  val changedTechnique: TechniqueArchive = {
    val dir   = restTestSetUp.mockGitRepo.configurationRepositoryRoot / "techniques" / "ncf_techniques" / "technique_any" / "1.0"
    val files = Chunk.fromIterable(dir.collectChildren(!_.isDirectory).map(f => (dir.relativize(f).toString, f.byteArray)).toList)
    TechniqueArchive(
      TechniqueInfo(
        TechniqueId.parse("technique_any/1.0").getOrElse(throw new IllegalArgumentException("id")),
        "any",
        TechniqueType.Yaml,
        None
      ),
      Chunk("ncf_techniques"),
      files :+ ("resources/new.txt", "new".getBytes(StandardCharsets.UTF_8))
    )
  }

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
      },
      test("what a tenant reads is not a write: a read-only tenant reimports it") {
        for {
          r  <- stored("imp-unchanged").someOrFail("seed")
          // what a zoneA export gives: the tag reduced to zoneA, and the group without the nodes zoneA can not see
          g   = zoneAGroup.modify(_.serverList).setTo(Set())
          gc <- restTestSetUp.mockNodeGroups.groupsRepo.getNodeGroupOpt(zoneAGroup.id)(using admin).someOrFail("seed").map(_._2)
          a   = withRules(r).modify(_.groups).setTo(Chunk(GroupArchive(g, gc)))
          _  <- saver.save(a, MergePolicy.OverrideAll)(using zoneAro)
          g2 <- restTestSetUp.mockNodeGroups.groupsRepo.getNodeGroupOpt(zoneAGroup.id)(using admin).someOrFail("seed")
          ch <-
            saver.save(withRules(r.modify(_.shortDescription).setTo("changed")), MergePolicy.OverrideAll)(using zoneAro).either
        } yield assertTrue(g2._1.serverList == zoneAGroup.serverList) && assertTrue(ch.isLeft)
      },
      test("a tenant imports what it may write") {
        for {
          r <- stored("imp-update").someOrFail("seed")
          _ <- saver.save(withRules(r.modify(_.shortDescription).setTo("updated")), MergePolicy.OverrideAll)(using zoneA)
          u <- stored("imp-update").someOrFail("updated")
        } yield assertTrue(u.shortDescription == "updated")
      },
      test("an archive holding an object the tenant can not write is refused, and nothing is written") {
        for {
          mine  <- stored("imp-refused").someOrFail("seed")
          other <- stored("rule1").someOrFail("seed")
          res   <-
            saver
              .save(
                withRules(mine.modify(_.shortDescription).setTo("changed"), other.modify(_.shortDescription).setTo("changed")),
                MergePolicy.OverrideAll
              )(using zoneA)
              .either
          after <- stored("imp-refused").someOrFail("seed")
        } yield {
          assertTrue(res.left.exists(_.fullMsg.contains("rule1"))) &&
          assertTrue(after.shortDescription == mine.shortDescription)
        }
      },
      test("a technique category the tenant reads is not written; a change to it is refused") {
        for {
          cat <- techniqueCategory("systemSettings/misc")
          ok  <- saver
                   .save(PolicyArchive.empty("t").modify(_.techniqueCats).setTo(Chunk(cat)), MergePolicy.OverrideAll)(using zoneA)
                   .either
          ko  <- saver
                   .save(
                     PolicyArchive
                       .empty("t")
                       .modify(_.techniqueCats)
                       .setTo(Chunk(cat.modify(_.metadata.description).setTo("changed"))),
                     MergePolicy.OverrideAll
                   )(using zoneA)
                   .either
        } yield assertTrue(ok.isRight, ko.isLeft)
      },
      test("a technique the tenant can not write is refused before anything is written") {
        for {
          res <-
            saver
              .save(PolicyArchive.empty("t").modify(_.techniques).setTo(Chunk(changedTechnique)), MergePolicy.OverrideAll)(using
                zoneA
              )
              .either
        } yield assertTrue(res.left.exists(_.fullMsg.contains("technique_any")))
      },
      test("an unchanged object the tenant can not read is refused too") {
        for {
          other <- stored("rule1").someOrFail("seed")
          res   <- saver.save(withRules(other), MergePolicy.OverrideAll)(using zoneA).either
        } yield assertTrue(res.left.exists(_.fullMsg.contains("rule1")))
      }
    )
  }
}
