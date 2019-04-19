/*
*************************************************************************************
* Copyright 2011 Normation SAS
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

package com.normation.inventory.provisioning.fusion

import org.junit.runner._
import org.specs2.mutable._
import org.specs2.runner._
import com.normation.utils.StringUuidGeneratorImpl
import net.liftweb.common._
import java.io.File

import com.normation.errors._
import com.normation.inventory.domain._
import com.normation.inventory.domain.AgentType._
import com.normation.zio.ZioRuntime
import net.liftweb.json.JsonAST.{JBool, JInt, JString}
import scalaz.zio._
import scalaz.zio.syntax._

/**
 * A simple test class to check that the demo data file is up to date
 * with the schema (there may still be a desynchronization if both
 * demo-data, test data and test schema for UnboundID are not synchronized
 * with OpenLDAP Schema).
 */
@RunWith(classOf[JUnitRunner])
class TestReportParsing extends Specification with Loggable {

  private[this] implicit class TestParser(parser: FusionReportUnmarshaller) {
    def parse(reportRelativePath: String): IOResult[InventoryReport] = {
      val url = this.getClass.getClassLoader.getResource(reportRelativePath)
      if(null == url) throw new NullPointerException(s"Resource with relative path '${reportRelativePath}' is null (missing resource? Spelling? Permissions?)")

      ZIO.bracket(Task.effect(url.openStream()).mapError(e => SystemError(s"error opening ${url.toString}", e)))(is => Task.effect(is.close).run){ is =>
        parser.fromXml(reportRelativePath, is).chainError(s"Parsing error with file ${reportRelativePath}")
      }
    }
  }


  val parser = new FusionReportUnmarshaller(new StringUuidGeneratorImpl)
  def parseRun(report: String) = ZioRuntime.unsafeRun(parser.parse(report))

  "All inventories in the fusion-report directory" should {
    "be correctly parsed and give a non generic OS type" in {

      val dir = new File(this.getClass.getClassLoader.getResource("fusion-report").getFile)
      val fileNames = dir.listFiles().collect { case f if(f.getName.endsWith(".ocs") || f.getName.endsWith(".xml")) => f.getName }.toList

      fileNames must contain { (f:String) =>
        val name = "fusion-report/"+f
        val report = parseRun(name)
        report.node.main.osDetails match {
          case _:UnknownOS =>
            logger.error(s"Inventory '${name}' has an unknown OS type")
            failure
          case _ =>
            success
        }
      }.foreach

    }
  }

  "Machine with four ips (two v4, two v6) for one interfaces" should {

    val report = parseRun("fusion-report/centos-with-two-ip-for-one-interface.ocs")

    "lead to a node with 4 ips for eth0" in {
      report.node.networks.find( _.name == "eth0").get.ifAddresses.size must beEqualTo(4)
    }
  }

  "Machine with one ipv4 and one ipv6 network for one interface" should {

    val report = parseRun("fusion-report/centos-with-two-ip-for-one-interface.ocs")

    "have correct gateway, mask, network defined" in {
      val net = report.node.networks.find( _.name == "ens3")
      (net.get.ifAddresses.size must beEqualTo(2)) and
      (net.get.ifAddresses.size must beEqualTo(2)) and
      (net.get.ifAddresses.size must beEqualTo(2)) and
      (net.get.ifAddresses.size must beEqualTo(2))
    }

  }


  "A node with Rudder roles" should {

    val report = parseRun("fusion-report/node-with-server-role-attribute.ocs")

    "correctly add roles"in {
      report.node.serverRoles must contain(exactly(ServerRole("magikal_node")))
    }
  }

  "A node with a timezone" should {

    val report = parseRun("fusion-report/sles-10-64-sp3-2011-08-23-16-06-17.ocs")

    "correctly parse the timezone" in {
      report.node.timezone must beEqualTo(Some(NodeTimezone("CEST", "+0200")))
    }
  }

  "Custom properties" should {
    "correctly be parsed" in {
      import net.liftweb.json.parse

      val expected = List(
          CustomProperty("hook1_k1", JInt(42))
        , CustomProperty("hook1_k2", JBool(true))
        , CustomProperty("hook1_k3", JString("a string"))
        , CustomProperty("hook2"   , parse("""
            { "some": "more"
            , "json": [1, 2, 3]
            , "deep": { "and": "deeper"}
            }"""
          )
        )
      )

      val report = parseRun("fusion-report/sles-10-64-sp3-2011-08-23-16-06-17.ocs")
      report.node.customProperties must beEqualTo(expected)
    }
  }

  "Arch in Inventory" should {

    "be defined for windows 2012" in {
      val arch = parseRun("fusion-report/WIN-AI8CLNPLOV5-2014-06-20-18-15-49.ocs").node.archDescription
      arch must beEqualTo(Some("x86_64"))
    }

    "correctly get the arch when OPERATINGSYSTEM/ARCH is defined" in {
      val arch = parseRun("fusion-report/signed_inventory.ocs").node.archDescription
      arch must beEqualTo(Some("x86_64"))
    }

    "correctly get the arch even if OPERATINGSYSTEM/ARCH is missing" in {
      val arch = parseRun("fusion-report/sles-10-64-sp3-2011-08-23-16-06-17.ocs").node.archDescription
      arch must beEqualTo(Some("x86_64"))
    }

    "be 'ppc64' on AIX" in {
      val arch = parseRun("fusion-report/sovma136-2014-02-10-07-13-43.ocs").node.archDescription
      arch must beEqualTo(Some("ppc64"))
    }
  }

  "Agent in Inventory" should {

    "be empty when there is no agent" in {
      val agents = parseRun("fusion-report/rudder-tag/minimal-zero-agent.ocs").node.agents.map(_.agentType).toList
      agents must be empty
    }

    "have one agent when using community" in {
    val agents = parseRun("fusion-report/rudder-tag/minimal-one-agent.ocs")

      agents.node.agents.map(_.agentType).toList === (CfeCommunity :: Nil)
    }

    "have two agent when using community and nova" in {
      val agents = parseRun("fusion-report/rudder-tag/minimal-two-agents.ocs").node.agents.map(_.agentType).toList
      agents === (CfeCommunity :: CfeEnterprise :: Nil)
    }

    "be empty when there is two agents, using two different policy servers" in {
      val agents = parseRun("fusion-report/rudder-tag/minimal-two-agents-fails.ocs").node.agents.map(_.agentType).toList
      agents must be empty
    }

    "have dsc agent agent when using rudder-agent based on dsc" in {
      val agents = parseRun("fusion-report/dsc-agent.ocs").node.agents.map(_.agentType).toList
      agents === (Dsc :: Nil)
    }

  }

  "Parsing Windows 2012" should {
    "parse as windows 2012" in {
      val os = parseRun("fusion-report/WIN-AI8CLNPLOV5-2014-06-20-18-15-49.ocs").node.main.osDetails.os
      os === Windows2012
    }
    "parse as windows 2012" in {
      val os = parseRun("fusion-report/windows2012r2.ocs").node.main.osDetails.os
      os === Windows2012R2
    }
  }

  "Parsing Windows 2016" should {
    "parse as windows 2016" in {
      val os = parseRun("fusion-report/windows2016.ocs").node.main.osDetails.os
      os === Windows2016
    }
  }

  "Parsing Windows 2019" should {
    "parse as windows 2019" in {
      val os = parseRun("fusion-report/windows2019.ocs").node.main.osDetails.os
      os === Windows2019
    }
  }

  "Hostname should be correctly detected" should {
     "get node1 when it is defined as this" in {
        val hostname = parseRun("fusion-report/signed_inventory.ocs").node.main.hostname
        hostname === "node1"
     }

    "get WIN-AI8CLNPLOV5.eu-west-1.compute.internal as the hostname" in {
      val hostname = parseRun("fusion-report/WIN-AI8CLNPLOV5-2014-06-20-18-15-49.ocs").node.main.hostname
      hostname === "WIN-AI8CLNPLOV5.eu-west-1.compute.internal"
    }
  }

  "Agent version" should {
    "be found for community agent" in {
      val version = parseRun("fusion-report/sles-11-sp1-64-2011-09-02-12-00-43.ocs").node.agents(0).version
      version must beEqualTo(Some(AgentVersion("2.3.0.beta1~git-1")))
    }

    "be found for nova agent" in {
      val version = parseRun("fusion-report/WIN-2017-rudder-4-1.ocs").node.agents(0).version
      version must beEqualTo(Some(AgentVersion("cfe-3.7.4.65534")))
    }

    "be unknown if rudder agent is missing" in {
      val version = parseRun("fusion-report/rudder-tag/minimal-two-agents.ocs").node.agents(0).version
      version must beEqualTo(None)
    }

  }


  "Parsing Slackware" should {
    "parse as slackware" in {
      val os = parseRun("fusion-report/slackinv.ocs").node.main.osDetails.os
      os === Slackware
    }
  }

  "Parsing Mint" should {
    "parse as mint" in {
      val os = parseRun("fusion-report/mint.ocs").node.main.osDetails.os
      os == Mint
    }
  }

  "Parsing inventory with only KERNEL_NAME in OPERATING SYSTEM" should {
    "parse as a unknown linux when it's a linux" in {
      val os = parseRun("fusion-report/only-kernel-name-0034fbbe-4b52-4212-9535-1f1a952c6f36.ocs").node.main.osDetails.os
      os === UnknownLinuxType
    }
    "parse as a unknown windows when its windows" in {
      val os = parseRun("fusion-report/windows2016-incomplete.ocs").node.main.osDetails.os
      os === UnknownWindowsType
    }
  }
}
