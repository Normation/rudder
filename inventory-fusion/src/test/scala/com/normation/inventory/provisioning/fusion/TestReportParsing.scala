/*
*************************************************************************************
* Copyright 2011 Normation SAS
*************************************************************************************
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Affero General Public License as
* published by the Free Software Foundation, either version 3 of the
* License, or (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU Affero GPL v3, the copyright holders add the following
* Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU Affero GPL v3
* licence, when you create a Related Module, this Related Module is
* not considered as a part of the work and may be distributed under the
* license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Affero General Public License for more details.
*
* You should have received a copy of the GNU Affero General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/agpl.html>.
*
*************************************************************************************
*/

package com.normation.inventory.provisioning
package fusion

import org.junit._
import org.junit.Assert._
import org.junit.runner.RunWith
import org.junit.runners.BlockJUnit4ClassRunner
import com.normation.utils.StringUuidGeneratorImpl
import scala.xml.XML
import net.liftweb.common.EmptyBox
import net.liftweb.common.Full
import java.io.File
import com.normation.inventory.domain.InventoryReport
import com.normation.inventory.domain.COMMUNITY_AGENT
import com.normation.inventory.domain.NOVA_AGENT

@RunWith(classOf[BlockJUnit4ClassRunner])
class TestReportParsing {

  val parser = new FusionReportUnmarshaller(
      new StringUuidGeneratorImpl()
  )

  //utility method to handle IS boilerplate
  def parse(relativePath: String): InventoryReport = {
    val is = this.getClass.getClassLoader.getResourceAsStream(relativePath)
    assertNotNull(is)

    parser.fromXml("report", is) match {
      case Full(e) => e
      case eb:EmptyBox =>
        val e = eb ?~! "Parsing error"
        e.rootExceptionCause match {
          case Full(ex) => throw new Exception(e.messageChain, ex)
          case _ => throw new Exception(e.messageChain)
        }
    }
  }



  @Test
  def testTwoIpsForEth0() {
    val report = parse("fusion-report/centos-with-two-ip-for-one-interface.ocs")
    assertTrue("We should have two IPs for eth0",
      report.node.networks.find( _.name == "eth0").get.ifAddresses.size == 2
    )
  }


  /**
   * Test the different cases for agent (0, 1, 2, 2 non coherent)
   */
  @Test
  def test0Agent() {
    val agents = parse("fusion-report/rudder-tag/minimal-zero-agent.ocs").node.agentNames.toList
    assertEquals("We were expecting 0 agent", Nil, agents)
  }

  @Test
  def test0bisAgent() {
    val agents = parse("fusion-report/rudder-tag/minimal-zero-bis-agent.ocs").node.agentNames.toList
    assertEquals("We were expecting 0 agent", Nil, agents)
  }

  @Test
  def test1Agent() {
    val agents = parse("fusion-report/rudder-tag/minimal-one-agent.ocs").node.agentNames.toList
    agents match {
      case agent :: Nil =>
        assertTrue("It's a community agent", agent == COMMUNITY_AGENT)
      case _ => fail("Bad number of agent, expecting 1 and got " + agents.size)
    }
  }

  @Test
  def test2Agents() {
    val agents = parse("fusion-report/rudder-tag/minimal-two-agents.ocs").node.agentNames.toList
    agents match {
      case a1 :: a2 :: Nil =>
        assertTrue("First agent is a community", a1 == COMMUNITY_AGENT)
        assertTrue("Second agent is a nova", a2 == NOVA_AGENT)
      case _ => fail("Bad number of agent, expecting 2 and got " + agents.size)
    }
  }

  @Test
  def test2AgentsFails() {
    val agents = parse("fusion-report/rudder-tag/minimal-two-agents-fails.ocs").node.agentNames.toList
    assertEquals("We were expecting 0 agent because they have different policy server", Nil, agents)
  }
}
