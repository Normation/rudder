/*
 *************************************************************************************
 * Copyright 2021 Normation SAS
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
package com.normation.rudder.services.policies.nodeconfig

import better.files.*
import com.normation.GitVersion
import com.normation.cfclerk.domain.*
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.DirectiveUid
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.RuleUid
import com.normation.rudder.services.policies.PolicyId
import net.liftweb.common.Box
import net.liftweb.common.Full
import net.liftweb.common.Loggable
import org.apache.commons.io.FileUtils
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.joda.time.format.ISODateTimeFormat
import org.junit.runner.*
import org.specs2.mutable.*
import org.specs2.runner.*
import org.specs2.specification.AfterAll

@RunWith(classOf[JUnitRunner])
class NodeConfigurationCacheRepositoryTest extends Specification with AfterAll with Loggable {

  implicit class ParseTechniqueVerison(s: String) {
    def toTV: TechniqueVersion = {
      TechniqueVersion.parse(s) match {
        case Right(v) => v
        case Left(e)  => throw new IllegalArgumentException(s"Error when parsing '${s}' as a technique version: ${e}")
      }
    }
  }

  implicit class ForceGet[A](b: Box[A]) {
    def forceGet: A = b match {
      case Full(a) => a
      case eb      => throw new IllegalArgumentException(s"Error in text, expected in a Full(x), got: " + eb)
    }
  }

  val root: File = File(
    s"/tmp/rudder-test-config-hashes/${new DateTime(DateTimeZone.UTC).toString(ISODateTimeFormat.dateTimeNoMillis())}"
  )

  root.createDirectories()

  //////////// set-up auto test cleaning ////////////
  override def afterAll(): Unit = {
    if (System.getProperty("tests.clean.tmp") != "false") {
      logger.info("Deleting directory " + root.pathAsString)
      FileUtils.deleteDirectory(root.toJava)
    }
  }

  val configHashesRepo = new FileBasedNodeConfigurationHashRepository((root / "node-config-hashes.json").pathAsString)

  val d0 = new DateTime("1970-01-01T00:00:00.100Z", DateTimeZone.UTC)
  val d1 = new DateTime("1970-01-01T00:00:00.500Z", DateTimeZone.UTC)

  val h0_0: NodeConfigurationHash = NodeConfigurationHash(NodeId("node0"), d0, 0, 0, 0, Set())

  val h1_0: NodeConfigurationHash = NodeConfigurationHash(
    NodeId("node1"),
    d0,
    0,
    0,
    0,
    Set(
      PolicyHash(
        PolicyId(RuleId(RuleUid("r0")), DirectiveId(DirectiveUid("d0"), GitVersion.DEFAULT_REV), TechniqueVersionHelper("1.0")),
        0
      ),
      PolicyHash(
        PolicyId(RuleId(RuleUid("r1")), DirectiveId(DirectiveUid("d1"), GitVersion.DEFAULT_REV), TechniqueVersionHelper("1.0")),
        0
      )
    )
  )

  val h2_0: NodeConfigurationHash = NodeConfigurationHash(
    NodeId("node2"),
    d0,
    0,
    0,
    0,
    Set(
      PolicyHash(
        PolicyId(RuleId(RuleUid("r0")), DirectiveId(DirectiveUid("d0"), GitVersion.DEFAULT_REV), TechniqueVersionHelper("1.0")),
        0
      )
    )
  )

  val h1_1: NodeConfigurationHash = NodeConfigurationHash(
    NodeId("node1"),
    d1,
    0,
    0,
    0,
    Set(
      PolicyHash(
        PolicyId(RuleId(RuleUid("r0")), DirectiveId(DirectiveUid("d0"), GitVersion.DEFAULT_REV), TechniqueVersionHelper("1.0")),
        0
      ),
      PolicyHash(
        PolicyId(RuleId(RuleUid("r2")), DirectiveId(DirectiveUid("d2"), GitVersion.DEFAULT_REV), TechniqueVersionHelper("1.0")),
        0
      )
    )
  )

  val h3_0: NodeConfigurationHash = NodeConfigurationHash(
    NodeId("node3"),
    d1,
    0,
    0,
    0,
    Set(
      PolicyHash(
        PolicyId(RuleId(RuleUid("r3")), DirectiveId(DirectiveUid("d3"), GitVersion.DEFAULT_REV), TechniqueVersionHelper("1.0")),
        0
      )
    )
  )

  val s1: Set[NodeConfigurationHash] = Set(h1_0, h0_0, h2_0)

  sequential

  "when nothing was written, we should get an empty success" >> {
    configHashesRepo.getAll() must beEqualTo(Full(Map()))
  }

  "write hashes" should {
    "get them back" in {
      configHashesRepo.save(s1)
      configHashesRepo.getAll().map(_.values.toSet).forceGet must containTheSameElementsAs(s1.toList)
    }

    "be written sorted" in {
      val json = {
        """{"hashes": [
          |  {"i":["node0","1970-01-01T00:00:00.100Z",0,0,0],"p":[]},
          |  {"i":["node1","1970-01-01T00:00:00.100Z",0,0,0],"p":[["r0","d0","1.0",0],["r1","d1","1.0",0]]},
          |  {"i":["node2","1970-01-01T00:00:00.100Z",0,0,0],"p":[["r0","d0","1.0",0]]}
          |] }""".stripMargin
      }

      configHashesRepo.hashesFile.contentAsString must beEqualTo(json)
    }

    "keep existing and update given ones" in {
      configHashesRepo.save(Set(h1_1, h3_0))
      configHashesRepo.getAll().map(_.values).forceGet must containTheSameElementsAs(List(h1_1, h0_0, h2_0, h3_0))
    }

    "does nothing if nothing saved" in {
      configHashesRepo.save(Set())
      configHashesRepo.getAll().map(_.values).forceGet must containTheSameElementsAs(List(h1_1, h0_0, h2_0, h3_0))
    }

    "still be written sorted" in {
      val json = {
        """{"hashes": [
          |  {"i":["node0","1970-01-01T00:00:00.100Z",0,0,0],"p":[]},
          |  {"i":["node1","1970-01-01T00:00:00.500Z",0,0,0],"p":[["r0","d0","1.0",0],["r2","d2","1.0",0]]},
          |  {"i":["node2","1970-01-01T00:00:00.100Z",0,0,0],"p":[["r0","d0","1.0",0]]},
          |  {"i":["node3","1970-01-01T00:00:00.500Z",0,0,0],"p":[["r3","d3","1.0",0]]}
          |] }""".stripMargin
      }

      configHashesRepo.hashesFile.contentAsString must beEqualTo(json)
    }

    "correctly delete selected" in {
      configHashesRepo.deleteNodeConfigurations(Set(h2_0.id))
      configHashesRepo.getAll().map(_.values).forceGet must containTheSameElementsAs(List(h1_1, h0_0, h3_0))
    }

    "correctly keep only selected" in {
      configHashesRepo.onlyKeepNodeConfiguration(Set(h0_0.id))
      configHashesRepo.getAll().map(_.values).forceGet must containTheSameElementsAs(List(h0_0))
    }
    "correctly delete all" in {
      configHashesRepo.deleteAllNodeConfigurations()
      configHashesRepo.getAll().map(_.values).forceGet must containTheSameElementsAs(Nil)
    }
  }

}
