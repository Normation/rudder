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

import com.normation.cfclerk.domain.Variable
import com.normation.inventory.domain.COMMUNITY_AGENT
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.reports.ReportingConfiguration
import com.normation.rudder.services.policies.nodeconfig.NodeConfiguration
import com.normation.rudder.services.policies.nodeconfig.ParameterForConfiguration
import org.joda.time.DateTime
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import net.liftweb.common.Full
import com.normation.rudder.domain.Constants
import net.liftweb.common.Failure



@RunWith(classOf[JUnitRunner])
class PathComputerTest extends Specification {

  import com.normation.rudder.services.policies.NodeConfigData._

  val allNodeConfig = Map(root.id -> rootNodeConfig, node1.id -> node1NodeConfig, node2.id -> node2NodeConfig)

  val pathComputer = new PathComputerImpl(
      Constants.NODE_PROMISES_PARENT_DIR_BASE
    , Constants.NODE_PROMISES_PARENT_DIR
    , "/var/rudder/backup/"
    , Constants.CFENGINE_COMMUNITY_PROMISES_PATH
    , Constants.CFENGINE_NOVA_PROMISES_PATH
  )

  ////////////////////////// test //////////////////////////

  "The paths for " should {
    "the root node should raise an error" in {
      pathComputer.computeBaseNodePath(root.id, root.id, allNodeConfig.mapValues(_.nodeInfo)) must beAnInstanceOf[Failure]
    }

    "the nodeConfig should be " in {
      pathComputer.computeBaseNodePath(node1.id, root.id, allNodeConfig.mapValues(_.nodeInfo)) must
      beEqualTo(Full(NodePromisesPaths(node1.id,"/var/rudder/share/node1/rules", "/var/rudder/share/node1/rules.new", "/var/rudder/backup/node1/rules")))
    }

    "the nodeConfig2, behind a relay should be " in {
      pathComputer.computeBaseNodePath(node2.id, root.id, allNodeConfig.mapValues(_.nodeInfo)) must
      beEqualTo(Full(NodePromisesPaths(node2.id, "/var/rudder/share/node1/share/node2/rules", "/var/rudder/share/node1/share/node2/rules.new", "/var/rudder/backup/node1/share/node2/rules")))
    }
  }
}
