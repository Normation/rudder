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

package com.normation.rudder.services.policies

import com.normation.rudder.services.policies.nodeconfig.NodeConfiguration
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.inventory.domain.NodeId
import com.normation.rudder.reports.ReportingConfiguration
import com.normation.rudder.services.policies.write.Cf3PolicyDraft
import com.normation.rudder.services.policies.nodeconfig.ParameterForConfiguration
import com.normation.inventory.domain.COMMUNITY_AGENT
import com.normation.cfclerk.domain.Variable
import org.joda.time.DateTime
import com.normation.inventory.domain.NodeSummary
import com.normation.inventory.domain.Linux
import com.normation.inventory.domain.UndefinedKey
import com.normation.inventory.domain.AcceptedInventory
import com.normation.inventory.domain.Debian
import com.normation.inventory.domain.NodeInventory
import com.normation.inventory.domain.Version
import com.normation.inventory.domain.EnvironmentVariable
import com.normation.inventory.domain.ServerRole
import com.normation.rudder.domain.nodes.Node
import com.normation.inventory.domain.VirtualBox
import com.normation.inventory.domain.VirtualMachineType
import com.normation.rudder.domain.nodes.MachineInfo
import com.normation.inventory.domain.MemorySize
import com.normation.inventory.domain.MachineUuid

/*
 * This file is a container for testing data that are a little boring to
 * define, like node info, node config, etc. so that their declaration
 * can be share among tests.
 */
object NodeConfigData {

  val emptyNodeReportingConfiguration = ReportingConfiguration(None,None)

  val id1 = NodeId("node1")
  val hostname1 = "node1.localhost"
  val admin1 = "root"
  val id2 = NodeId("node2")
  val rootId = NodeId("root")
  val rootHostname = "server.rudder.local"
  val rootAdmin = "root"

  val rootNode = Node (
      rootId
    , "root"
    , ""
    , false
    , false
    , true
    , DateTime.now
    , emptyNodeReportingConfiguration
    , Seq()
  )
  val root = NodeInfo (
      rootNode
    , rootHostname
    , Some(MachineInfo(MachineUuid("machine1"), VirtualMachineType(VirtualBox), None, None))
    , Linux(Debian, "Jessie", new Version("7.0"), None, new Version("3.2"))
    , List("127.0.0.1", "192.168.0.100")
    , DateTime.now
    , "", UndefinedKey
    , Seq(COMMUNITY_AGENT)
    , rootId
    , rootAdmin
    , Set( //by default server roles for root
          "rudder-db"
        , "rudder-inventory-endpoint"
        , "rudder-inventory-ldap"
        , "rudder-jetty"
        , "rudder-ldap"
        , "rudder-reports"
        , "rudder-server-root"
        , "rudder-webapp"
      ).map(ServerRole(_))
    , None
    , None
  )

  val node1Node = Node (
      id1
    , "node1"
    , ""
    , false
    , false
    , false
    , DateTime.now
    , emptyNodeReportingConfiguration
    , Seq()
  )

  val node1 = NodeInfo (
      node1Node
    , hostname1
    , Some(MachineInfo(MachineUuid("machine1"), VirtualMachineType(VirtualBox), None, None))
    , Linux(Debian, "Jessie", new Version("7.0"), None, new Version("3.2"))
    , List("192.168.0.10")
    , DateTime.now
    , "", UndefinedKey
    , Seq(COMMUNITY_AGENT)
    , rootId
    , admin1
    , Set()
    , None
    , Some(MemorySize(1460132))
  )

  val nodeInventory1: NodeInventory = NodeInventory(
      NodeSummary(
          node1.id
        , AcceptedInventory
        , node1.localAdministratorAccountName
        , node1.hostname
        , Linux(Debian, "test machine", new Version("1.0"), None, new Version("3.42"))
        , root.id
        , UndefinedKey
      )
    , name                 = None
    , description          = None
    , ram                  = None
    , swap                 = None
    , inventoryDate        = None
    , receiveDate          = None
    , archDescription      = None
    , lastLoggedUser       = None
    , lastLoggedUserTime   = None
    , agentNames           = Seq()
    , publicKeys           = Seq()
    , serverIps            = Seq()
    , machineId            = None //if we want several ids, we would have to ass an "alternate machine" field
    , softwareIds          = Seq()
    , accounts             = Seq()
    , environmentVariables = Seq(EnvironmentVariable("THE_VAR", Some("THE_VAR value!")))
    , processes            = Seq()
    , vms                  = Seq()
    , networks             = Seq()
    , fileSystems          = Seq()
    , serverRoles          = Set()
  )

  //node1 us a relay
  val node2Node = node1Node.copy(id = id2, name = id2.value)
  val node2 = node1.copy(node = node2Node, policyServerId = node1.id )

  val allNodesInfo = Map( rootId -> root, node1.id -> node1, node2.id -> node2)

  val rootNodeConfig = NodeConfiguration(
      nodeInfo    = root
    , policyDrafts= Set[Cf3PolicyDraft]()
    , nodeContext = Map[String, Variable]()
    , parameters  = Set[ParameterForConfiguration]()
    , writtenDate = None
    , isRootServer= true
  )

  val node1NodeConfig = NodeConfiguration(
    nodeInfo    = node1
  , policyDrafts= Set[Cf3PolicyDraft]()
  , nodeContext = Map[String, Variable]()
  , parameters  = Set[ParameterForConfiguration]()
  , writtenDate = None
  , isRootServer= false
  )

  val node2NodeConfig = NodeConfiguration(
    nodeInfo    = node2
  , policyDrafts= Set[Cf3PolicyDraft]()
  , nodeContext = Map[String, Variable]()
  , parameters  = Set[ParameterForConfiguration]()
  , writtenDate = None
  , isRootServer= false
  )
}
