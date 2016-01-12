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

package com.normation.rudder.services.path

import scala.collection.immutable.SortedMap
import org.junit.runner._
import org.specs2.mutable._
import org.specs2.runner._
import org.joda.time.DateTime
import com.normation.cfclerk.domain._
import com.normation.inventory.domain.COMMUNITY_AGENT
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.domain.policies.ActiveTechniqueId
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.RuleWithCf3PolicyDraft
import com.normation.rudder.repository.FullActiveTechnique
import com.normation.rudder.repository.FullActiveTechniqueCategory
import com.normation.rudder.services.policies.nodeconfig._
import com.normation.rudder.domain.policies.DirectiveId
import net.liftweb.common.Box
import net.liftweb.common.Full
import com.normation.rudder.reports.ReportingConfiguration

@RunWith(classOf[JUnitRunner])
class PathComputerTest extends Specification {
  import com.normation.rudder.services.policies.NodeConfigData._

  val allNodeConfig = Map(root.id -> rootNodeConfig, node1.id -> node1NodeConfig, node2.id -> node2NodeConfig)

  val pathComputer = new PathComputerImpl("/var/rudder/backup/")
  ////////////////////////// test //////////////////////////

  "The paths for " should {
    "the nodeConfig should be " in {

      pathComputer.computeBaseNodePath(node1.id, root.id, allNodeConfig) must
      beEqualTo(Full((s"/var/rudder/share/${id1.value}/rules", s"/var/rudder/share/${id1.value}/rules.new", s"/var/rudder/backup/${id1.value}/rules")))
    }

    "the nodeConfig2, behind a relay should be " in {

      pathComputer.computeBaseNodePath(node2.id, root.id, allNodeConfig) must
      beEqualTo(Full((s"/var/rudder/share/${id1.value}/share/${id2.value}/rules", s"/var/rudder/share/${id1.value}/share/${id2.value}/rules.new", s"/var/rudder/backup/${id1.value}/share/${id2.value}/rules")))
    }

  }

}
