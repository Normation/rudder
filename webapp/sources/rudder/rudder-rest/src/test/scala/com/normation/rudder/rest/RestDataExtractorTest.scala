/*
 *************************************************************************************
 * Copyright 2016 Normation SAS
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

import com.normation.inventory.domain.NodeId
import com.normation.rudder.MockDirectives
import com.normation.rudder.MockGitConfigRepo
import com.normation.rudder.MockRules
import com.normation.rudder.MockTechniques
import com.normation.rudder.apidata.JsonQueryObjects.*
import com.normation.rudder.apidata.JsonResponseObjects.*
import com.normation.rudder.apidata.JsonResponseObjects.JRRuleTarget.*
import com.normation.rudder.apidata.implicits.*
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.nodes.NodeGroupUid
import com.normation.rudder.domain.policies.*
import com.normation.utils.StringUuidGeneratorImpl
import net.liftweb.json.JValue
import org.junit.runner.RunWith
import zio.{Tag as _, *}
import zio.syntax.*
import zio.test.*
import zio.test.junit.ZTestJUnitRunner

@RunWith(classOf[ZTestJUnitRunner])
class RestDataExtractorTest extends ZIOSpecDefault {

  val mockGitRepo = new MockGitConfigRepo("")
  val mockTechniques: MockTechniques = MockTechniques(mockGitRepo)
  val mockDirectives = new MockDirectives(mockTechniques)
  val mockRules      = new MockRules()
  val extract        = new RestExtractorService(
    mockRules.ruleRepo,
    mockDirectives.directiveRepo,
    null,
    mockTechniques.techniqueRepo,
    null,
    null,
    null,
    new StringUuidGeneratorImpl(),
    null
  )
  val jparse: String => JValue = net.liftweb.json.parse _

  override def spec: Spec[TestEnvironment with Scope, Any] = {
    suite(s"Extracting data from rules")(
      suite("extract RuleTarget") {
        val tests = List(
          (
            """group:3d5d1d6c-4ba5-4ffc-b5a4-12ce02336f52""",
            JRRuleTarget(GroupTarget(NodeGroupId(NodeGroupUid("3d5d1d6c-4ba5-4ffc-b5a4-12ce02336f52"))))
          ),
          ("""group:hasPolicyServer-root""", JRRuleTarget(GroupTarget(NodeGroupId(NodeGroupUid("hasPolicyServer-root"))))),
          ("""policyServer:root""", JRRuleTarget(PolicyServerTarget(NodeId("root")))),
          ("""special:all""", JRRuleTarget(AllTarget)),
          (
            """{"include":{"or":["special:all"]},"exclude":{"or":["group:all-nodes-with-dsc-agent"]}}""",
            JRRuleTarget(
              TargetExclusion(
                TargetUnion(Set(AllTarget)),
                TargetUnion(Set(GroupTarget(NodeGroupId(NodeGroupUid("all-nodes-with-dsc-agent")))))
              )
            )
          ),
          (
            """{"include":{"or":[]},"exclude":{"or":[]}}""",
            JRRuleTarget(TargetExclusion(TargetUnion(Set()), TargetUnion(Set())))
          ),
          ("""{"or":["special:all"]}""", JRRuleTarget(TargetUnion(Set(AllTarget))))
        )

        ZIO.foreach(tests) {
          case (json, expected) =>
            test(json)(assertTrue(extractRuleTargetJson(json) == Right(expected))).succeed
        }
      },
      suite("extract JsonRule") {

        val tests = List(
          (
            """{
            "source": "b9f6d98a-28bc-4d80-90f7-d2f14269e215",
            "id": "0c1713ae-cb9d-4f7b-abda-ca38c5d643ea",
            "displayName": "Security policy",
            "shortDescription": "Baseline applying CIS guidelines",
            "longDescription": "This rules should be applied to all Linux nodes required basic hardening",
            "category": "38e0c6ea-917f-47b8-82e0-e6a1d3dd62ca",
            "directives": [
              "16617aa8-1f02-4e4a-87b6-d0bcdfb4019f"
            ],
            "targets": [
              "special:all"
            ],
            "enabled": true,
            "tags": [
              {
                "customer": "MyCompany"
              }
            ]
         }""",
            JQRule(
              RuleId.parse("0c1713ae-cb9d-4f7b-abda-ca38c5d643ea").toOption,
              Some("Security policy"),
              Some("38e0c6ea-917f-47b8-82e0-e6a1d3dd62ca"),
              Some("Baseline applying CIS guidelines"),
              Some("This rules should be applied to all Linux nodes required basic hardening"),
              Some(Set(DirectiveId(DirectiveUid("16617aa8-1f02-4e4a-87b6-d0bcdfb4019f")))),
              Some(Set(JRRuleTargetString(AllTarget))),
              Some(true),
              Some(Tags(Set(Tag(TagName("customer"), TagValue("MyCompany"))))),
              RuleId.parse("b9f6d98a-28bc-4d80-90f7-d2f14269e215").toOption
            )
          ),
          (
            """{
            "source": "b9f6d98a-28bc-4d80-90f7-d2f14269e215"
         }""",
            JQRule(source = RuleId.parse("b9f6d98a-28bc-4d80-90f7-d2f14269e215").toOption)
          ),
          (
            """{
            "category": "38e0c6ea-917f-47b8-82e0-e6a1d3dd62ca"
         }""",
            JQRule(category = Some("38e0c6ea-917f-47b8-82e0-e6a1d3dd62ca"))
          ),
          (
            """{
            "tags": []
         }""",
            JQRule(tags = Some(Tags(Set())))
          )
        )

        ZIO.foreach(tests) {
          case (json, expected) =>
            test(json)(assert(ruleDecoder.decodeJson(json))(Assertion.equalTo(Right(expected)))).succeed
        }
      }
    )
  }
}
