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

import com.normation.inventory.domain.AcceptedInventory
import com.normation.inventory.domain.NodeInventory
import com.normation.inventory.domain.NodeSummary
import com.normation.rudder.NodeDetails
import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner
import net.liftweb.common.Full
import net.liftweb.http.InMemoryResponse
import net.liftweb.mocks.MockHttpServletRequest
import org.specs2.specification.BeforeAfterAll
import zio.syntax._
import com.normation.zio._

// test that the "+" in path is correctly kept as a "+", not changed into " "
// See: https://issues.rudder.io/issues/20943

@RunWith(classOf[JUnitRunner])
class TestRestPlusInPath extends Specification with BeforeAfterAll {

  // we are testing error cases, so we don't want to output error log for them
  org.slf4j.LoggerFactory.getLogger("com.normation.rudder.rest.RestUtils").asInstanceOf[ch.qos.logback.classic.Logger].setLevel(ch.qos.logback.classic.Level.OFF)
  val env = RestTestSetUp.newEnv
  import com.softwaremill.quicklens._
  val myNode = env.mockNodes.node1.modify(_.node.id.value).setTo("my+node").modify(_.hostname).setTo("my+node.rudder.local")
  override def beforeAll(): Unit = {
    val inventory = NodeInventory(NodeSummary(myNode.id, AcceptedInventory, "root", myNode.hostname, myNode.osDetails, myNode.policyServerId, myNode.keyStatus))
    val details = NodeDetails(myNode, inventory, None)
    ZioRuntime.unsafeRun(env.mockNodes.nodeInfoService.nodeBase.update(nodes => (nodes+(myNode.id -> details)).succeed))
  }

  override def afterAll(): Unit = {
    ZioRuntime.unsafeRun(env.mockNodes.nodeInfoService.nodeBase.update(nodes => (nodes-(myNode.id)).succeed))
  }

  val test = new RestTest(env.liftRules)

  sequential

  ///// tests ////

  "A plus in the path part of the url should be kept as a plus" >> {
    val mockReq = new MockHttpServletRequest("http://localhost:8080")
    mockReq.method = "GET"
    mockReq.path = "/api/latest/nodes/my+node" // should be kept
    mockReq.queryString = "include=minimal"
    mockReq.body   = ""
    mockReq.headers = Map()
    mockReq.contentType = "text/plain"

    // authorize space in response formating
    val expected =
      """{"action":"nodeDetails","id":"my+node","result":"success","data":"""+
      """{"nodes":[{"id":"my+node","hostname":"my+node.rudder.local","status":"accepted"}]}}"""


    test.execRequestResponse(mockReq)(response =>
      response.map { r =>
        val rr = r.toResponse.asInstanceOf[InMemoryResponse]
        (rr.code, new String(rr.data, "UTF-8"))
      } must beEqualTo(Full((200, expected)))
    )

  }

}
