/*
 *************************************************************************************
 * Copyright 2022 Normation SAS
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

import com.normation.GitVersion.Revision
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.RuleUid
import com.normation.zio._
import net.liftweb.common.Full
import net.liftweb.http.InMemoryResponse
import net.liftweb.mocks.MockHttpServletRequest
import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner
import org.specs2.specification.BeforeAfterAll

// test that the "+" in path is correctly kept as a "+", not changed into " "
// See: https://issues.rudder.io/issues/20943

@RunWith(classOf[JUnitRunner])
class TestRestPlusInPath extends Specification with BeforeAfterAll {

  // we are testing error cases, so we don't want to output error log for them
  org.slf4j.LoggerFactory
    .getLogger("com.normation.rudder.rest.RestUtils")
    .asInstanceOf[ch.qos.logback.classic.Logger]
    .setLevel(ch.qos.logback.classic.Level.OFF)
  val env  = RestTestSetUp.newEnv
  import com.softwaremill.quicklens._
  val rule = env.mockRules.ruleRepo
    .get(RuleId(RuleUid("ff44fb97-b65e-43c4-b8c2-0df8d5e8549f")))
    .runNow
    .modify(_.id.rev)
    .setTo(Revision("gitrevision"))
  val test = new RestTest(env.liftRules)

  override def beforeAll(): Unit = {
    ZioRuntime.unsafeRun(env.mockRules.ruleRepo.rulesMap.update(_ + (rule.id -> rule)))
  }

  override def afterAll(): Unit = {}

  sequential

  ///// tests ////

  "A plus in the path part of the url should be kept as a plus" >> {
    val mockReq = new MockHttpServletRequest("http://localhost:8080")
    mockReq.method = "GET"
    mockReq.path = "/api/latest/rules/ff44fb97-b65e-43c4-b8c2-0df8d5e8549f+gitrevision" // should be kept
    mockReq.body = ""
    mockReq.headers = Map()
    mockReq.contentType = "text/plain"

    // authorize space in response formatting
    val expected = {
      """{"action":"ruleDetails","id":"ff44fb97-b65e-43c4-b8c2-0df8d5e8549f+gitrevision",
        |"result":"success","data":{"rules":[{
        |"id":"ff44fb97-b65e-43c4-b8c2-0df8d5e8549f+gitrevision",
        |"displayName":"60-rule-technique-std-lib",
        |"categoryId":"rootRuleCategory",
        |"shortDescription":"default rule",
        |"longDescription":"",
        |"directives":["16617aa8-1f02-4e4a-87b6-d0bcdfb4019f","99f4ef91-537b-4e03-97bc-e65b447514cc",
        |"e9a1a909-2490-4fc9-95c3-9d0aa01717c9"],
        |"targets":["special:all"],
        |"enabled":true,"system":false,"tags":[],"policyMode":"enforce",
        |"status":{"value":"Partially applied",
        |"details":"Directive 'directive 16617aa8-1f02-4e4a-87b6-d0bcdfb4019f' disabled, Directive 'directive e9a1a909-2490-4fc9-95c3-9d0aa01717c9' disabled"
        |}}]}}""".stripMargin.replaceAll("\n", "")
    }

    test.execRequestResponse(mockReq)(response => {
      response.map { r =>
        val rr = r.toResponse.asInstanceOf[InMemoryResponse]
        (rr.code, new String(rr.data, "UTF-8"))
      } must beEqualTo(Full((200, expected)))
    })

  }

}
