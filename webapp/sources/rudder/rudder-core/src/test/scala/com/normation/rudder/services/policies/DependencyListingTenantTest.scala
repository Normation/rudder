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

package com.normation.rudder.services.policies

import com.normation.errors.*
import com.normation.eventlog.EventActor
import com.normation.rudder.MockDirectives
import com.normation.rudder.MockGitConfigRepo
import com.normation.rudder.MockGlobalParam
import com.normation.rudder.MockNodeGroups
import com.normation.rudder.MockNodes
import com.normation.rudder.MockRules
import com.normation.rudder.MockTechniques
import com.normation.rudder.MockTenants
import com.normation.rudder.domain.Constants
import com.normation.rudder.domain.nodes.NodeGroupCategoryId
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.nodes.NodeGroupUid
import com.normation.rudder.domain.policies.ActiveTechniqueId
import com.normation.rudder.domain.policies.DirectiveUid
import com.normation.rudder.domain.policies.GroupTarget
import com.normation.rudder.domain.policies.Rule
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.RuleTarget
import com.normation.rudder.domain.policies.RuleUid
import com.normation.rudder.repository.FullNodeGroupCategory
import com.normation.rudder.tenants.*
import com.normation.zio.*
import net.liftweb.common.Full
import org.junit.runner.*
import org.specs2.mutable.*
import org.specs2.runner.*
import zio.Chunk
import zio.syntax.*

@RunWith(classOf[JUnitRunner])
class DependencyListingTenantTest extends Specification {

  private val mockTenants    = {
    val m = new MockTenants()
    m.tenantRepo.setTenantEnabled(true).runNow
    m
  }
  private val mockGitRepo    = new MockGitConfigRepo("")
  private val mockTechniques = MockTechniques(mockGitRepo)
  private val mockDirectives = new MockDirectives(mockTechniques, mockTenants)
  private val mockRules      = new MockRules(mockTenants)
  private val mockNodes      = new MockNodes(mockTenants)
  private val mockParams     = new MockGlobalParam(mockTenants)
  private val mockNodeGroups = new MockNodeGroups(mockNodes, mockParams, mockTenants)

  private def tenantTag(ids: String*): Option[SecurityTag] =
    Some(SecurityTag.ByTenants(Chunk.fromIterable(ids.map(TenantId(_)))))

  private def rule(id: String, security: Option[SecurityTag]): Rule =
    Rule(RuleId(RuleUid(id)), id, Constants.ROOT_RULE_CATEGORY, security = security)

  // one rule per tenant, plus one untagged (which only an administrator sees)
  private val ruleA     = rule("rule-zoneA", tenantTag("zoneA"))
  private val ruleB     = rule("rule-zoneB", tenantTag("zoneB"))
  private val ruleAdmin = rule("rule-admin", None)
  private val allRules  = Seq(ruleA, ruleB, ruleAdmin)

  private val findDependencies = new FindDependencies {
    // the raw look-up is complete: it is the service that decides what is shown
    override def findRulesForDirective(id:  DirectiveUid): IOResult[Seq[Rule]] = allRules.succeed
    override def findRulesForTarget(target: RuleTarget):   IOResult[Seq[Rule]] = allRules.succeed
  }

  private val service = new DependencyAndDeletionServiceImpl(
    findDependencies,
    mockTenants.checkTenant,
    mockDirectives.directiveRepo,
    mockDirectives.directiveRepo,
    mockRules.ruleRepo,
    mockNodeGroups.groupsRepo
  )

  private val emptyGroupLib =
    FullNodeGroupCategory(NodeGroupCategoryId("GroupRoot"), "root", "", Nil, Nil, isSystem = true, security = None)

  private def grant(accesses: TenantAccess*): QueryContext =
    QueryContext(EventActor("u"), TenantAccessGrant.ByTenants(Chunk.fromIterable(accesses)))

  private val admin = QueryContext.systemQC
  private val zoneA = grant(TenantAccess(TenantId("zoneA")))

  private val someDirective = DirectiveUid("some-directive")
  private val someTarget    = GroupTarget(NodeGroupId(NodeGroupUid("some-group")))

  "[directive] a tenant user only sees the rules of their tenants" >> {
    val dep = service.directiveDependencies(someDirective, emptyGroupLib.succeed)(using zoneA).runNow

    dep.rules.map(_.id.uid.value) must beEqualTo(Set("rule-zoneA"))
  }

  "[directive] an administrator sees every dependency" >> {
    val dep = service.directiveDependencies(someDirective, emptyGroupLib.succeed)(using admin).runNow

    dep.rules must haveSize(3)
  }

  "[target] a tenant user only sees the rules of their tenants" >> {
    val dep = service.targetDependencies(someTarget, onlyEnableable = false)(using zoneA).runNow

    dep.rules.map(_.id.uid.value) must beEqualTo(Set("rule-zoneA"))
  }

  "[target] an administrator sees every dependency" >> {
    val dep = service.targetDependencies(someTarget, onlyEnableable = false)(using admin).runNow

    dep.rules must haveSize(3)
  }

  // the look-up runs as Rudder so that the set is complete; what is returned is the viewer's share of it
  "[technique] a tenant user only sees the rules of their tenants" >> {
    val dep = service
      .techniqueDependencies(ActiveTechniqueId("some-at"), Full(emptyGroupLib))(using zoneA)
      .openOrThrowException("test")

    dep.rules.keySet.map(_.uid.value) must beEqualTo(Set("rule-zoneA"))
  }

  "[technique] an administrator sees every dependency" >> {
    val dep = service
      .techniqueDependencies(ActiveTechniqueId("some-at"), Full(emptyGroupLib))(using admin)
      .openOrThrowException("test")

    dep.rules must haveSize(3)
  }
}
