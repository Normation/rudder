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

import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.domain.TechniqueVersion
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.nodes.*
import com.normation.rudder.domain.policies.*
import com.normation.rudder.domain.queries.*
import com.normation.rudder.rule.category.RuleCategoryId
import com.normation.rudder.tenants.*
import com.normation.zio.*
import java.time.Instant
import org.junit.runner.*
import org.specs2.mutable.*
import org.specs2.runner.*
import zio.Chunk

/**
 * Test read/write basic LDAP entities
 */
@RunWith(classOf[JUnitRunner])
class BasicLdapPersistenceTest extends Specification with SetupLdapRepositories {

  sequential

  "reading a group with tenants should correctly deserialize it" >> {
    val id          = NodeGroupId(NodeGroupUid("test-group-node1"))
    val nodeCrit    = ditQueryDataImpl.criteriaMap("node")
    val expected    = NodeGroup(
      NodeGroupId(NodeGroupUid("test-group-node1")),
      "Only contains node1",
      "",
      properties = List(),
      query = Some(
        Query(
          QueryReturnType.NodeReturnType,
          CriterionComposition.And,
          ResultTransformation.Identity,
          List(
            CriterionLine(nodeCrit, nodeCrit.criteria(1), Equals, "node1")
          )
        )
      ),
      isDynamic = true,
      serverList = Set(NodeId("node1")),
      _isEnabled = true,
      isSystem = false,
      security = Some(SecurityTag.ByTenants(Chunk(TenantId("zoneA"))))
    )
    implicit val qc = QueryContext.testQC
    val res         = roGroupRepo.getNodeGroupOpt(id).runNow.map(_._1)

    res must beSome(beEqualTo(expected))
  }

  "reading a group category with a tenant should correctly deserialize it" >> {

    val catId    = NodeGroupCategoryId("category1")
    val res      = roGroupRepo.getGroupCategory(catId).runNow
    val expected = {
      NodeGroupCategory(
        catId,
        "A group category",
        "",
        List(),
        List(),
        false,
        Some(SecurityTag.ByTenants(Chunk(TenantId("zoneA"))))
      )
    }

    res must beEqualTo(expected)
  }

  "reading an active technique with a tenant should correctly deserialize it" >> {

    val tech     = TechniqueName("user_defined_tech1")
    val res      = roDirectiveRepo.getActiveTechnique(tech).runNow
    val expected = ActiveTechnique(
      ActiveTechniqueId(tech.value),
      tech,
      AcceptationDateTime(Map(TechniqueVersion.V1_0 -> Instant.parse("2023-12-02T17:49:01.013Z"))),
      List(DirectiveUid("ce8aec6f-d371-4047-96d1-6b69ccdef9ae")),
      security = Some(SecurityTag.ByTenants(Chunk(TenantId("zoneA"))))
    )

    res must beSome(beEqualTo(expected))

  }

  "reading a directive with a tenant should correctly deserialize it" >> {

    val did = DirectiveUid("ce8aec6f-d371-4047-96d1-6b69ccdef9ae")

    val res      = roDirectiveRepo.getDirective(did).runNow
    val expected = Directive(
      DirectiveId(did),
      TechniqueVersion.V1_0,
      Map("BD16C074-DEBC-433A-ADFC-3CB74FFFAD3F" -> Seq("")),
      "user_defined_tech1",
      "",
      policyMode = None,
      longDescription = "",
      priority = 5,
      _isEnabled = true,
      isSystem = false,
      security = Some(SecurityTag.ByTenants(Chunk(TenantId("zoneA"))))
    )

    res must beSome(beEqualTo(expected))
  }

  "reading a rule with a tenant should correctly deserialize it" >> {

    val did    = DirectiveId(DirectiveUid("ce8aec6f-d371-4047-96d1-6b69ccdef9ae"))
    val ruleId = RuleId((RuleUid("34323555-6b6b-4d07-b3bd-043df1239797")))

    val res      = roRuleRepo.get(ruleId).runNow
    val expected = Rule(
      ruleId,
      "User rule",
      RuleCategoryId("rootRuleCategory"),
      Set(TargetExclusion(TargetUnion(Set(AllTarget)), TargetUnion(Set()))),
      Set(did),
      "",
      "",
      true,
      security = Some(SecurityTag.ByTenants(Chunk(TenantId("zoneA"))))
    )

    res must beEqualTo(expected)
  }

}
