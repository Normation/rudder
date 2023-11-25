/*
 *************************************************************************************
 * Copyright 2020 Normation SAS
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
package com.normation.rudder.services.servers

import better.files._
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.inventory.domain.NodeId
import com.normation.rudder.facts.nodes.ChangeContext
import com.normation.rudder.facts.nodes.QueryContext
import com.normation.zio._
import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat
import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner
import org.specs2.specification.AfterAll

@RunWith(classOf[JUnitRunner])
class TestRemoveNodeService extends Specification with AfterAll {

  // let's say that's /var/rudder/share
  val varRudderShare = File(s"/tmp/rudder-test-delete-node-${DateTime.now().toString(ISODateTimeFormat.dateTime())}")

  // nodeXX appears at seleral places

  override def afterAll(): Unit = {
    if (System.getProperty("tests.clean.tmp") != "false") {
      varRudderShare.delete()
    }
  }
  val expected = List(
    varRudderShare,
    varRudderShare / "node1",
    varRudderShare / "node1" / "rules",
    varRudderShare / "node2",
    varRudderShare / "node2" / "rules",
    varRudderShare / "relay1",
    varRudderShare / "relay1" / "rules",
    varRudderShare / "relay1" / "share",
    varRudderShare / "relay1" / "share" / "node11",
    varRudderShare / "relay1" / "share" / "node11" / "rules",
    varRudderShare / "relay1" / "share" / "node12",
    varRudderShare / "relay1" / "share" / "node12" / "rules",
    varRudderShare / "relay1" / "share" / "relay2",
    varRudderShare / "relay1" / "share" / "relay2" / "rules",
    varRudderShare / "relay1" / "share" / "relay2" / "share",
    varRudderShare / "relay1" / "share" / "relay2" / "share" / "node21",
    varRudderShare / "relay1" / "share" / "relay2" / "share" / "node21" / "rules",
    varRudderShare / "relay1" / "share" / "relay2" / "share" / "node22",
    varRudderShare / "relay1" / "share" / "relay2" / "share" / "node22" / "rules"
  )

  val startFS = expected ::: List(
    varRudderShare / "nodeXX" / "rules",
    varRudderShare / "relay1" / "share" / "nodeXX" / "rules", // if it was a relay at some point, it's deleted all the same

    varRudderShare / "relay1" / "share" / "nodeXX" / "share",
    varRudderShare / "relay1" / "share" / "relay2" / "share" / "nodeXX" / "rules"
  )

  val cleanUp = new CleanUpNodePolicyFiles(varRudderShare.pathAsString)
  implicit val testChangeContext: ChangeContext =
    ChangeContext(ModificationId("test-mod-id"), EventActor("test"), DateTime.now(), None, None, QueryContext.testQC.nodePerms)

  /*
   *
   */
  "Policy directory should be cleaned" >> {
    startFS.foreach(_.createDirectories())
    cleanUp.run(NodeId("nodeXX"), DeleteMode.Erase, None, Set()).runNow
    val files = varRudderShare.collectChildren(_ => true).toList.map(_.pathAsString)

    files must containTheSameElementsAs(expected.map(_.pathAsString))
  }
}
