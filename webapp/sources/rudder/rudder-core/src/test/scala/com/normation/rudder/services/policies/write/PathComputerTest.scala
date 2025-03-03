/*
 *************************************************************************************
 * Copyright 2015 Normation SAS
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

package com.normation.rudder.services.policies.write

import com.normation.errors.Inconsistency
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.Constants
import com.normation.rudder.services.policies.NodeConfiguration
import com.softwaremill.quicklens.*
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class PathComputerTest extends Specification {

  import com.normation.rudder.services.policies.NodeConfigData.*

  val allNodeConfig: Map[NodeId, NodeConfiguration] =
    Map(root.id -> rootNodeConfig, node1.id -> node1NodeConfig, node2.id -> node2NodeConfig)

  val pathComputer = new PathComputerImpl(
    Constants.NODE_PROMISES_PARENT_DIR_BASE,
    Constants.NODE_PROMISES_PARENT_DIR,
    Some("/var/rudder/backup/"),
    Constants.CFENGINE_COMMUNITY_PROMISES_PATH
  )

  ////////////////////////// test //////////////////////////

  "The paths for " should {
    "the root node should raise an error" in {
      pathComputer.computeBaseNodePath(root.id, root.id, allNodeConfig.view.mapValues(_.nodeInfo).toMap) must beAnInstanceOf[
        Left[Inconsistency, ?]
      ]
    }

    "the nodeConfig should be " in {
      pathComputer.computeBaseNodePath(node1.id, root.id, allNodeConfig.view.mapValues(_.nodeInfo).toMap) must
      beEqualTo(
        Right(
          NodePoliciesPaths(
            node1.id,
            "/var/rudder/share/node1/rules",
            "/var/rudder/share/node1/rules.new",
            Some("/var/rudder/backup/node1/rules")
          )
        )
      )
    }

    // #TODO: migrate in scale-out relay plugin
//    "the nodeConfig2, behind a relay should be " in {
//      pathComputer.computeBaseNodePath(node2.id, root.id, allNodeConfig.mapValues(_.nodeInfo)) must
//      beEqualTo(Full(NodePromisesPaths(node2.id, "/var/rudder/share/node1/share/node2/rules", "/var/rudder/share/node1/share/node2/rules.new", "/var/rudder/backup/node1/share/node2/rules")))
//    }
  }

  "When there is a loop in the policy server parent chain, the algo" should {
    "raise an error" in {
      val badNode1  = node1NodeConfig.modify(_.nodeInfo.rudderSettings.policyServerId).setTo(node2.id)
      val badNode2  = node2NodeConfig.modify(_.nodeInfo.rudderSettings.policyServerId).setTo(node1.id)
      val badConfig = allNodeConfig + (node1.id -> badNode1) + (node2.id -> badNode2)
      pathComputer.computeBaseNodePath(node1.id, root.id, badConfig.view.mapValues(_.nodeInfo).toMap) must beAnInstanceOf[
        Left[Inconsistency, ?]
      ]
    }
  }

}
