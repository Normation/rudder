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

import com.github.ghik.silencer.silent
import com.normation.errors._
import com.normation.inventory.domain._
import com.normation.inventory.domain.AgentType._
import com.normation.utils.StringUuidGeneratorImpl
import com.normation.zio.ZioRuntime
import java.io.File
import net.liftweb.common._
import net.liftweb.json.JsonAST._
import org.junit.runner._
import org.specs2.mutable._
import org.specs2.runner._
import zio._

/**
 * A simple test class to check that the demo data file is up to date
 * with the schema (there may still be a desynchronization if both
 * demo-data, test data and test schema for UnboundID are not synchronized
 * with OpenLDAP Schema).
 */
@silent("a type was inferred to be `\\w+`; this may indicate a programming error.")
@RunWith(classOf[JUnitRunner])
class TestInventoryParsing extends Specification with Loggable {

  implicit private[this] class TestParser(parser: FusionInventoryParser) {
    def parse(inventoryRelativePath: String): IOResult[Inventory] = {
      val url = this.getClass.getClassLoader.getResource(inventoryRelativePath)
      if (null == url) {
        throw new NullPointerException(
          s"Resource with relative path '${inventoryRelativePath}' is null (missing resource? Spelling? Permissions?)"
        )
      }

      ZIO.bracket(Task.effect(url.openStream()).mapError(e => SystemError(s"error opening ${url.toString}", e)))(is =>
        Task.effect(is.close).run
      )(is => parser.fromXml(inventoryRelativePath, is).chainError(s"Parsing error with file ${inventoryRelativePath}"))
    }
  }

  val parser                            = new FusionInventoryParser(new StringUuidGeneratorImpl)
  def parseRun(inventory: String)       = ZioRuntime.unsafeRun(parser.parse(inventory))
  def parseRunEither(inventory: String) = ZioRuntime.unsafeRun(parser.parse(inventory).either)

  // we are testing some error etc, so make the standard output cleaner:
  org.slf4j.LoggerFactory
    .getLogger("inventory-processing")
    .asInstanceOf[ch.qos.logback.classic.Logger]
    .setLevel(ch.qos.logback.classic.Level.ERROR)

  sequential

  "All inventories in the fusion-inventories directory" should {
    "be correctly parsed and give a non generic OS type" in {

      // toURI is needed for https://issues.rudder.io/issues/19186
      val dir       = new File(this.getClass.getClassLoader.getResource("fusion-inventories").toURI.getPath)
      val fileNames =
        dir.listFiles().collect { case f if (f.getName.endsWith(".ocs") || f.getName.endsWith(".xml")) => f.getName }.toList

      fileNames must contain { (f: String) =>
        val name      = "fusion-inventories/" + f
        val inventory = parseRun(name)
        inventory.node.main.osDetails match {
          case _: UnknownOS =>
            logger.error(s"Inventory '${name}' has an unknown OS type")
            failure
          case _ =>
            success
        }
      }.foreach

    }
  }

  "Machine with four ips (two v4, two v6) for one interfaces" should {

    val inventory = parseRun("fusion-inventories/centos-with-two-ip-for-one-interface.ocs")

    "lead to a node with 4 ips for eth0" in {
      inventory.node.networks.find(_.name == "eth0").get.ifAddresses.size must beEqualTo(4)
    }
  }

  "Machine with one ipv4 and one ipv6 network for one interface" should {

    val inventory = parseRun("fusion-inventories/centos-with-two-ip-for-one-interface.ocs")

    "have correct gateway, mask, network defined" in {
      val net = inventory.node.networks.find(_.name == "ens3")
      (net.get.ifAddresses.size must beEqualTo(2)) and
      (net.get.ifAddresses.size must beEqualTo(2)) and
      (net.get.ifAddresses.size must beEqualTo(2)) and
      (net.get.ifAddresses.size must beEqualTo(2))
    }

  }

  "A node with a timezone" should {

    val inventory = parseRun("fusion-inventories/sles-10-64-sp3-2011-08-23-16-06-17.ocs")

    "correctly parse the timezone" in {
      inventory.node.timezone must beEqualTo(Some(NodeTimezone("CEST", "+0200")))
    }
  }

  "Custom properties" should {
    "correctly be parsed" in {
      import net.liftweb.json.parse

      val expected = List(
        CustomProperty("hook1_k1", JInt(42)),
        CustomProperty("hook1_k2", JBool(true)),
        CustomProperty("hook1_k3", JString("a string")),
        CustomProperty(
          "hook2",
          parse("""
            { "some": "more"
            , "json": [1, 2, 3]
            , "deep": { "and": "deeper"}
            }""")
        )
      )

      val inventory = parseRun("fusion-inventories/sles-10-64-sp3-2011-08-23-16-06-17.ocs")
      inventory.node.customProperties must beEqualTo(expected)
    }
  }

  "We can override hostname with custom property 'rudder_override_hostname'" >> {
    // after parsing, we also have custom property for original hostname
    val expected = List(
      CustomProperty("rudder_override_hostname", JString("node1-overridden.rudder.local.override")),
      CustomProperty("rudder_original_hostname", JString("node1.rudder.local"))
    )

    val inventory = parseRun("fusion-inventories/7.1/node1-4d3a43bc-8508-46a2-92d7-cfe7320309a5.ocs")
    (inventory.node.customProperties must containTheSameElementsAs(expected)) and
    (inventory.node.main.hostname must beEqualTo("node1-overridden.rudder.local.override"))
  }

  "Arch in Inventory" should {

    "be defined for windows 2012" in {
      val arch = parseRun("fusion-inventories/WIN-AI8CLNPLOV5-2014-06-20-18-15-49.ocs").node.archDescription
      arch must beEqualTo(Some("x86_64"))
    }

    "correctly get the arch when OPERATINGSYSTEM/ARCH is defined" in {
      val arch = parseRun("fusion-inventories/signed_inventory.ocs").node.archDescription
      arch must beEqualTo(Some("x86_64"))
    }

    "correctly get the arch even if OPERATINGSYSTEM/ARCH is missing" in {
      val arch = parseRun("fusion-inventories/sles-10-64-sp3-2011-08-23-16-06-17.ocs").node.archDescription
      arch must beEqualTo(Some("x86_64"))
    }

    "be 'ppc64' on AIX" in {
      val arch = parseRun("fusion-inventories/sovma136-2014-02-10-07-13-43.ocs").node.archDescription
      arch must beEqualTo(Some("ppc64"))
    }
  }

  "Rudder tag in inventory" should {

    "ok-ish when not present (have an eror log)" in {
      val res = parseRunEither("fusion-inventories/rudder-tag/minimal-no-rudder.ocs")
      res must beRight
    }

    "can't be two" in {
      val res = parseRunEither("fusion-inventories/rudder-tag/minimal-one-agent-two-rudder-fails.ocs")
      res must beLeft
    }
  }

  "Agent in Inventory" should {

    "be empty when there is no agent" in {
      val agents = parseRun("fusion-inventories/rudder-tag/minimal-zero-agent.ocs").node.agents.map(_.agentType).toList
      agents must beEmpty
    }

    "have one agent when using community" in {
      val agents = parseRun("fusion-inventories/rudder-tag/minimal-one-agent.ocs")

      agents.node.agents.map(_.agentType).toList === (CfeCommunity :: Nil)
    }

    "have two agent when using community and nova" in {
      val agents = parseRun("fusion-inventories/rudder-tag/minimal-two-agents.ocs").node.agents.map(_.agentType).toList
      agents === (CfeCommunity :: CfeEnterprise :: Nil)
    }

    "be empty when there is two agents, using two different policy servers" in {
      val agents = parseRun("fusion-inventories/rudder-tag/minimal-two-agents-fails.ocs").node.agents.map(_.agentType).toList
      agents must beEmpty
    }

    "have dsc agent agent when using rudder-agent based on dsc" in {
      val agents = parseRun("fusion-inventories/dsc-agent.ocs").node.agents.map(_.agentType).toList
      agents === (Dsc :: Nil)
    }

    "correctly parse a 6.1 agent declaration with cert, capabilities and custom propeties" in {
      val inventory = parseRun("fusion-inventories/rudder-tag/one-agent-full.ocs")

      (inventory.node.agents.head === AgentInfo(
        AgentType.CfeCommunity,
        Some(AgentVersion("6.1.0.release-1.EL.7")),
        Certificate("-----BEGIN CERTIFICATE----\ncertificate\n-----END CERTIFICATE-----"),
        Set("cfengine", "yaml", "xml", "curl", "http_reporting", "acl").map(AgentCapability)
      )) and (
        inventory.node.customProperties === List(
          CustomProperty(
            "cpu_vulnerabilities",
            JObject(
              List(
                JField(
                  "spectre_v2",
                  JObject(List(JField("status", JString("vulnerable")), JField("details", JString("Retpoline without IBPB"))))
                ),
                JField(
                  "spectre_v1",
                  JObject(List(JField("status", JString("mitigated")), JField("details", JString("Load fences"))))
                ),
                JField("meltdown", JObject(List(JField("status", JString("mitigated")), JField("details", JString("PTI")))))
              )
            )
          )
        )
      ) and (
        inventory.node.softwareUpdates === List(
          SoftwareUpdate(
            name = "rudder-agent",
            version = Some("7.0.1.release.EL.7"),
            arch = Some("x86_64"),
            from = Some("yum"),
            kind = SoftwareUpdateKind.None,
            source = Some("security-backport"),
            Some("Local privilege escalation in pkexec due to incorrect handling of argument vector (CVE-2021-4034)"),
            Some(SoftwareUpdateSeverity.Low),
            JsonSerializers.parseSoftwareUpdateDateTime("2022-01-26T00:00:00Z").toOption,
            Some(List("RHSA-2020-4566", "CVE-2021-4034"))
          )
        )
      )
    }

  }

  "Parsing Processors" should {
    "count 6 CPU in total" in {
      val cpus  = parseRun("fusion-inventories/cpus-count.ocs").machine.processors
      val count = cpus.map(_.quantity).sum
      count === 6
    }
    "count 2 different CPU models" in {
      val cpus  = parseRun("fusion-inventories/cpus-count.ocs").machine.processors
      val count = cpus.length
      count === 2
    }
    "count 5 same CPU models" in {
      val cpus  = parseRun("fusion-inventories/cpus-count.ocs").machine.processors
      val count = cpus.filter(_.name == "Intel(R) Xeon(R) Gold 6254 CPU @ 3.10GHz").head.quantity
      count === 5
    }
  }

  "Parsing Windows 2012" should {
    "parse as windows 2012" in {
      val os = parseRun("fusion-inventories/WIN-AI8CLNPLOV5-2014-06-20-18-15-49.ocs").node.main.osDetails
      os match {
        case Windows(osType, _, _, _, _, ud, rc, pk, pid) =>
          (osType === Windows2012) and
          (ud === None) and
          (rc === Some("Amazon.com")) and
          (pk === Some("T3VD8-82QFK-QCFB9-WV3TF-QGJ3F")) and
          (pid === Some("00184-30000-00001-AA420"))
        case x                                            => ko(s"I was expecting a windows 2012, got: ${x}")
      }
    }
    "parse as windows 2012" in {
      val os = parseRun("fusion-inventories/windows2012r2.ocs").node.main.osDetails.os
      os === Windows2012R2
    }
  }

  "Parsing Windows 2016" should {
    "parse as windows 2016" in {
      val os = parseRun("fusion-inventories/windows2016.ocs").node.main.osDetails.os
      os === Windows2016
    }
  }

  "Parsing Windows 2019" should {
    "parse as windows 2019" in {
      val os = parseRun("fusion-inventories/windows2019.ocs").node.main.osDetails.os
      os === Windows2019
    }
  }

  "Hostname should be correctly detected" should {
    "get node1 when it is defined as this" in {
      val hostname = parseRun("fusion-inventories/signed_inventory.ocs").node.main.hostname
      hostname === "node1"
    }

    "get WIN-AI8CLNPLOV5.eu-west-1.compute.internal as the hostname" in {
      val hostname = parseRun("fusion-inventories/WIN-AI8CLNPLOV5-2014-06-20-18-15-49.ocs").node.main.hostname
      hostname === "WIN-AI8CLNPLOV5.eu-west-1.compute.internal"
    }
  }

  "Agent version" should {
    "be found for community agent" in {
      val version = parseRun("fusion-inventories/sles-11-sp1-64-2011-09-02-12-00-43.ocs").node.agents(0).version
      version must beEqualTo(Some(AgentVersion("2.3.0.beta1~git-1")))
    }

    "be found for nova agent" in {
      val version = parseRun("fusion-inventories/WIN-2017-rudder-4-1.ocs").node.agents(0).version
      version must beEqualTo(Some(AgentVersion("cfe-3.7.4.65534")))
    }

    "be unknown if rudder agent is missing" in {
      val version = parseRun("fusion-inventories/rudder-tag/minimal-two-agents.ocs").node.agents(0).version
      version must beEqualTo(None)
    }

  }

  "Parsing Slackware" should {
    "parse as slackware" in {
      val os = parseRun("fusion-inventories/slackinv.ocs").node.main.osDetails.os
      os === Slackware
    }
  }

  "Parsing Mint" should {
    "parse as mint" in {
      val os = parseRun("fusion-inventories/mint.ocs").node.main.osDetails.os
      os == Mint
    }
  }

  "Parsing RockyLinux" should {
    "parse as rocky linux" in {
      val os = parseRun("fusion-inventories/rocky.ocs").node.main.osDetails.os
      os == RockyLinux
    }
  }

  "Parsing AlmaLinux" should {
    "parse as almalinux" in {
      val os = parseRun("fusion-inventories/alma.ocs").node.main.osDetails.os
      os == AlmaLinux
    }
  }

  "Parsing inventory with only KERNEL_NAME in OPERATING SYSTEM" should {
    "parse as a unknown linux when it's a linux" in {
      val os = parseRun("fusion-inventories/only-kernel-name-0034fbbe-4b52-4212-9535-1f1a952c6f36.ocs").node.main.osDetails.os
      os === UnknownLinuxType
    }
    "parse as a unknown windows when its windows" in {
      val os = parseRun("fusion-inventories/windows2016-incomplete.ocs").node.main.osDetails.os
      os === UnknownWindowsType
    }
  }
  "Virtuozzo VM should be correctly parsed" in {
    val inventory = parseRun("fusion-inventories/virtuozzo.ocs")

    (inventory.machine.machineType must beEqualTo(VirtualMachineType(Virtuozzo))) and
    (inventory.node.main.osDetails must beEqualTo(
      Linux(Debian, "Debian GNU/Linux 9.5 (stretch)", new Version("9.5"), None, new Version("4.9.0-7-amd64"))
    ))
  }
}
