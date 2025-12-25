/*
 *************************************************************************************
 * Copyright 2024 Normation SAS
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

package com.normation.rudder.web.services

import com.normation.JsonSpecMatcher
import com.normation.inventory.domain.NodeId
import com.normation.rudder.MockCompliance
import com.normation.rudder.MockDirectives
import com.normation.rudder.MockGitConfigRepo
import com.normation.rudder.MockTechniques
import com.normation.rudder.domain.policies.GlobalPolicyMode
import com.normation.rudder.domain.policies.PolicyMode.Enforce
import com.normation.rudder.domain.policies.PolicyModeOverrides
import com.normation.rudder.domain.policies.PolicyTypeName
import com.normation.rudder.facts.nodes.CoreNodeFact
import com.normation.rudder.repository.FullActiveTechniqueCategory
import com.normation.rudder.tenants.QueryContext
import com.normation.zio.*
import org.junit.runner.RunWith
import org.specs2.mutable.*
import org.specs2.runner.JUnitRunner
import scala.collection.MapView
import scala.util.Random
import zio.json.*

@RunWith(classOf[JUnitRunner])
class ComplianceLineTest extends Specification with JsonSpecMatcher {

  val mockDirectives = new MockDirectives(MockTechniques(new MockGitConfigRepo("")))
  val mockCompliance = new MockCompliance(mockDirectives)
  implicit val qc: QueryContext                  = QueryContext.testQC
  val nodes:       MapView[NodeId, CoreNodeFact] = mockCompliance.nodeFactRepo.getAll().runNow
  val directives:  FullActiveTechniqueCategory   = mockDirectives.directiveRepo.getFullDirectiveLibrary().runNow

  implicit object StableRandom extends ProvideNextName {
    val stableRandom = new Random(42)
    override def nextName: String = stableRandom.nextLong().toString
  }

  "compliance lines serialisation" >> {
    val nodeId = NodeId("n1")
    val report = mockCompliance.simpleExample.simpleStatusReports(nodeId)
    val lines  = ComplianceData
      .getNodeByRuleComplianceDetails(
        nodeId,
        report,
        PolicyTypeName.rudderBase,
        nodes.toMap,
        directives,
        mockCompliance.simpleExample.simpleCustomRules,
        GlobalPolicyMode(Enforce, PolicyModeOverrides.Always)
      )
      .toJson

    lines must equalsJsonSemantic(
      """[
        |  {
        |    "rule":"R1",
        |    "compliance":[[0,0.0],[0,0.0], [1, 100.0], [0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0]
        |    ],
        |    "compliancePercent":100.0,
        |    "id":"r1",
        |    "details":[
        |      {
        |        "directive":"25. Testing blocks",
        |        "id":"directive-techniqueWithBlocks",
        |        "techniqueName":"technique with blocks",
        |        "techniqueVersion":"1.0",
        |        "compliance":[[0,0.0],[0,0.0], [1, 100.0], [0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0]
        |        ],
        |        "compliancePercent":100.0,
        |        "details":[
        |          {
        |            "component":"directive-techniqueWithBlocks-component-r1-n1",
        |            "unexpanded":"directive-techniqueWithBlocks-component-r1-n1",
        |            "compliance":[[0,0.0],[0,0.0], [1, 100.0], [0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0]
        |            ],
        |            "compliancePercent":100.0,
        |            "details":[
        |              {
        |                "value":"directive-techniqueWithBlocks-component-value-r1-n1",
        |                "unexpanded":"directive-techniqueWithBlocks-component-value-r1-n1",
        |                "status":"Success",
        |                "statusClass":"Success",
        |                "messages":[
        |                  {
        |                    "status":"Success",
        |                    "value":""
        |                  }
        |                ],
        |                "compliance":[[0,0.0],[0,0.0], [1, 100.0], [0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0]
        |                ],
        |                "compliancePercent":100.0,
        |                "jsid":"-5843495416241995736"
        |              }
        |            ],
        |            "noExpand":false,
        |            "jsid":"5111195811822994797"
        |          }
        |        ],
        |        "jsid":"-1782466964123969572",
        |        "isSystem":false,
        |        "policyMode":"audit",
        |        "explanation":"The <i><b>Node</b></i> is configured to <b class=\"text-Enforce\">enforce</b> but is overridden to <b>audit</b> by this <i><b>Directive</b></i>. ",
        |        "tags":[
        |          {
        |            "key":"aTagName",
        |            "value":"the tagName value"
        |          }
        |        ]
        |      }
        |    ],
        |    "jsid":"5086654115216342560",
        |    "isSystem":false,
        |    "policyMode":"audit",
        |    "explanation":"The <i><b>Node</b></i> is configured to <b class=\"text-Enforce\">enforce</b> but is overridden to <b>audit</b> by all <i><b>Directives</b></i>. ",
        |    "tags":[]
        |  },
        |  {
        |    "rule":"R2",
        |    "compliance":[[0,0.0],[0,0.0],[0,0.0], [1, 100.0], [0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0]
        |    ],
        |    "compliancePercent":100.0,
        |    "id":"r2",
        |    "details":[
        |      {
        |        "directive":"directive 99f4ef91-537b-4e03-97bc-e65b447514cc",
        |        "id":"99f4ef91-537b-4e03-97bc-e65b447514cc",
        |        "techniqueName":"File content (from remote template)",
        |        "techniqueVersion":"1.0",
        |        "compliance":[[0,0.0],[0,0.0],[0,0.0], [1, 100.0], [0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0]
        |        ],
        |        "compliancePercent":100.0,
        |        "details":[
        |          {
        |            "component":"99f4ef91-537b-4e03-97bc-e65b447514cc-component-r2-n1",
        |            "unexpanded":"99f4ef91-537b-4e03-97bc-e65b447514cc-component-r2-n1",
        |            "compliance":[[0,0.0],[0,0.0],[0,0.0], [1, 100.0], [0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0]
        |            ],
        |            "compliancePercent":100.0,
        |            "details":[
        |              {
        |                "value":"99f4ef91-537b-4e03-97bc-e65b447514cc-component-value-r2-n1",
        |                "unexpanded":"99f4ef91-537b-4e03-97bc-e65b447514cc-component-value-r2-n1",
        |                "status":"Repaired",
        |                "statusClass":"Repaired",
        |                "messages":[
        |                  {
        |                    "status":"Repaired",
        |                    "value":""
        |                  }
        |                ],
        |                "compliance":[[0,0.0],[0,0.0],[0,0.0], [1, 100.0], [0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0]
        |                ],
        |                "compliancePercent":100.0,
        |                "jsid":"-4004755535478349341"
        |              }
        |            ],
        |            "noExpand":false,
        |            "jsid":"8051837266862454915"
        |          }
        |        ],
        |        "jsid":"7130900098642117381",
        |        "isSystem":false,
        |        "policyMode":"enforce",
        |        "explanation":"<b>Enforce</b> is forced by this <i><b>Node</b></i> mode",
        |        "tags":[]
        |      }
        |    ],
        |    "jsid":"-7482923245497525943",
        |    "isSystem":false,
        |    "policyMode":"enforce",
        |    "explanation":"<b>enforce</b> mode is forced by this <i><b>Node</b></i>",
        |    "tags":[]
        |  },
        |  {
        |    "rule":"R3",
        |    "compliance":[[0,0.0],[0,0.0],[0,0.0],[0,0.0], [1, 100.0], [0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0]
        |    ],
        |    "compliancePercent":0.0,
        |    "id":"r3",
        |    "details":[
        |      {
        |        "directive":"directive2",
        |        "id":"directive2",
        |        "techniqueName":"Packages (RHEL/CentOS/SuSE/RPM)",
        |        "techniqueVersion":"7.0",
        |        "compliance":[[0,0.0],[0,0.0],[0,0.0],[0,0.0], [1, 100.0], [0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0]
        |        ],
        |        "compliancePercent":0.0,
        |        "details":[
        |          {
        |            "component":"directive2-component-r3-n1",
        |            "unexpanded":"directive2-component-r3-n1",
        |            "compliance":[[0,0.0],[0,0.0],[0,0.0],[0,0.0], [1, 100.0], [0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0]
        |            ],
        |            "compliancePercent":0.0,
        |            "details":[
        |              {
        |                "value":"directive2-component-value-r3-n1",
        |                "unexpanded":"directive2-component-value-r3-n1",
        |                "status":"Error",
        |                "statusClass":"Error",
        |                "messages":[
        |                  {
        |                    "status":"Error",
        |                    "value":""
        |                  }
        |                ],
        |                "compliance":[[0,0.0],[0,0.0],[0,0.0],[0,0.0], [1, 100.0], [0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0],[0,0.0]
        |                ],
        |                "compliancePercent":0.0,
        |                "jsid":"-3210362905434573697"
        |              }
        |            ],
        |            "noExpand":false,
        |            "jsid":"-7610621359446545191"
        |          }
        |        ],
        |        "jsid":"-7912908803613548926",
        |        "isSystem":false,
        |        "policyMode":"enforce",
        |        "explanation":"<b>Enforce</b> is forced by this <i><b>Node</b></i> mode",
        |        "tags":[]
        |      }
        |    ],
        |    "jsid":"-4565385657661118002",
        |    "isSystem":false,
        |    "policyMode":"enforce",
        |    "explanation":"<b>enforce</b> mode is forced by this <i><b>Node</b></i>",
        |    "tags":[]
        |  }
        |]""".stripMargin
    )
  }
}
