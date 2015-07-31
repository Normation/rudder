/*
*************************************************************************************
* Copyright 2015 Normation SAS
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

package com.normation.rudder.services.nodes

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
import com.normation.rudder.services.path.PathComputerImpl
import net.liftweb.common.Box
import net.liftweb.common.Full
import com.normation.rudder.reports.ReportingConfiguration



@RunWith(classOf[JUnitRunner])
class PathComputerTest extends Specification {

  private val root = NodeInfo(
    id            = NodeId("root")
  , name          = "root"
  , description   = ""
  , hostname      = "hostname"
  , machineType   = "vm"
  , osName        = "debian"
  , osVersion     = "5.4"
  , servicePack   = None
  , ips           = List("127.0.0.1")
  , inventoryDate = DateTime.now()
  , publicKey     = ""
  , agentsName    = Seq(COMMUNITY_AGENT)
  , policyServerId= NodeId("root")
  , localAdministratorAccountName= "root"
  , creationDate  = DateTime.now()
  , isBroken      = false
  , isSystem      = false
  , isPolicyServer= false
  , serverRoles = Set()
  , nodeReportingConfiguration = ReportingConfiguration(None,None)
  )

  private val nodeInfo = NodeInfo(
    id            = NodeId("name")
  , name          = "name"
  , description   = ""
  , hostname      = "hostname"
  , machineType   = "vm"
  , osName        = "debian"
  , osVersion     = "5.4"
  , servicePack   = None
  , ips           = List("127.0.0.1")
  , inventoryDate = DateTime.now()
  , publicKey     = ""
  , agentsName    = Seq(COMMUNITY_AGENT)
  , policyServerId= root.id
  , localAdministratorAccountName= "root"
  , creationDate  = DateTime.now()
  , isBroken      = false
  , isSystem      = false
  , isPolicyServer= false
  , serverRoles = Set()
  , nodeReportingConfiguration = ReportingConfiguration(None,None)
  )

  private val nodeInfo2 = nodeInfo.copy(id = NodeId("name2"), name = "name2", policyServerId = nodeInfo.id )

  val rootNodeConfig = NodeConfiguration(
    nodeInfo    = root
  , policyDrafts= Set[RuleWithCf3PolicyDraft]()
  , nodeContext = Map[String, Variable]()
  , parameters  = Set[ParameterForConfiguration]()
  , writtenDate = None
  , isRootServer= false
  )

  val nodeConfig = NodeConfiguration(
    nodeInfo    = nodeInfo
  , policyDrafts= Set[RuleWithCf3PolicyDraft]()
  , nodeContext = Map[String, Variable]()
  , parameters  = Set[ParameterForConfiguration]()
  , writtenDate = None
  , isRootServer= false
  )

  val nodeConfig2 = NodeConfiguration(
    nodeInfo    = nodeInfo2
  , policyDrafts= Set[RuleWithCf3PolicyDraft]()
  , nodeContext = Map[String, Variable]()
  , parameters  = Set[ParameterForConfiguration]()
  , writtenDate = None
  , isRootServer= false
  )


  val allNodeConfig = Map(root.id -> rootNodeConfig, nodeInfo.id -> nodeConfig, nodeInfo2.id -> nodeConfig2)

  val pathComputer = new PathComputerImpl("/var/rudder/backup/")
  ////////////////////////// test //////////////////////////

  "The paths for " should {
    "the nodeConfig should be " in {

      pathComputer.computeBaseNodePath(nodeInfo.id, root.id, allNodeConfig) must
      beEqualTo(Full(("/var/rudder/share/name/rules", "/var/rudder/share/name/rules.new", "/var/rudder/backup/name/rules")))
    }

    "the nodeConfig2, behind a relay should be " in {

      pathComputer.computeBaseNodePath(nodeInfo2.id, root.id, allNodeConfig) must
      beEqualTo(Full(("/var/rudder/share/name/share/name2/rules", "/var/rudder/share/name/share/name2/rules.new", "/var/rudder/backup/name/share/name2/rules")))
    }

  }


}
