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

import com.normation.cfclerk.domain.SectionSpec
import com.normation.cfclerk.domain.Technique
import com.normation.cfclerk.domain.TechniqueId
import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.domain.TechniqueVersion
import com.normation.cfclerk.domain.TrackerVariableSpec
import com.normation.cfclerk.domain.Variable
import com.normation.inventory.domain.AcceptedInventory
import com.normation.inventory.domain.AgentInfo
import com.normation.inventory.domain.AgentVersion
import com.normation.inventory.domain.AgentType.CfeCommunity
import com.normation.inventory.domain.Debian
import com.normation.inventory.domain.EnvironmentVariable
import com.normation.inventory.domain.Linux
import com.normation.inventory.domain.MachineUuid
import com.normation.inventory.domain.MemorySize
import com.normation.inventory.domain.NodeId
import com.normation.inventory.domain.NodeInventory
import com.normation.inventory.domain.NodeSummary
import com.normation.inventory.domain.NodeTimezone
import com.normation.inventory.domain.PublicKey
import com.normation.inventory.domain.ServerRole
import com.normation.inventory.domain.UndefinedKey
import com.normation.inventory.domain.Version
import com.normation.inventory.domain.VirtualBox
import com.normation.inventory.domain.VirtualMachineType
import com.normation.rudder.domain.nodes.MachineInfo
import com.normation.rudder.domain.nodes.Node
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.nodes.NodeGroupCategoryId
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.domain.policies.ActiveTechniqueCategoryId
import com.normation.rudder.domain.policies.ActiveTechniqueId
import com.normation.rudder.domain.policies.Directive
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.FullGroupTarget
import com.normation.rudder.domain.policies.FullRuleTargetInfo
import com.normation.rudder.domain.policies.GlobalPolicyMode
import com.normation.rudder.domain.policies.GroupTarget
import com.normation.rudder.domain.policies.PolicyMode
import com.normation.rudder.domain.policies.PolicyMode.Enforce
import com.normation.rudder.domain.policies.PolicyModeOverrides
import com.normation.rudder.domain.policies.Rule
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.reports.NodeModeConfig
import com.normation.rudder.reports.AgentRunInterval
import com.normation.rudder.reports.FullCompliance
import com.normation.rudder.reports.GlobalComplianceMode
import com.normation.rudder.reports.ReportingConfiguration
import com.normation.rudder.repository.FullActiveTechnique
import com.normation.rudder.repository.FullActiveTechniqueCategory
import com.normation.rudder.repository.FullNodeGroupCategory
import com.normation.rudder.rule.category.RuleCategoryId
import com.normation.rudder.services.policies.nodeconfig.NodeConfiguration
import com.normation.rudder.services.policies.nodeconfig.ParameterForConfiguration
import com.normation.rudder.services.policies.write.Cf3PolicyDraft
import org.joda.time.DateTime
import scala.collection.SortedMap
import scala.language.implicitConversions
import com.normation.inventory.domain.Windows
import com.normation.inventory.domain.Windows2012
import com.normation.inventory.domain.AgentType
import com.normation.inventory.domain.Certificate

/*
 * This file is a container for testing data that are a little boring to
 * define, like node info, node config, etc. so that their declaration
 * can be share among tests.
 */
object NodeConfigData {

  //a valid, not used pub key
  //cfengine key hash is: 081cf3aac62624ebbc83be7e23cb104d
  val PUBKEY =
"""-----BEGIN RSA PUBLIC KEY-----
MIIBCAKCAQEAlntroa72gD50MehPoyp6mRS5fzZpsZEHu42vq9KKxbqSsjfUmxnT
Rsi8CDvBt7DApIc7W1g0eJ6AsOfV7CEh3ooiyL/fC9SGATyDg5TjYPJZn3MPUktg
YBzTd1MMyZL6zcLmIpQBH6XHkH7Do/RxFRtaSyicLxiO3H3wapH20TnkUvEpV5Qh
zUkNM8vHZuu3m1FgLrK5NCN7BtoGWgeyVJvBMbWww5hS15IkCRuBkAOK/+h8xe2f
hMQjrt9gW2qJpxZyFoPuMsWFIaX4wrN7Y8ZiN37U2q1G11tv2oQlJTQeiYaUnTX4
z5VEb9yx2KikbWyChM1Akp82AV5BzqE80QIBIw==
-----END RSA PUBLIC KEY-----"""

  val emptyNodeReportingConfiguration = ReportingConfiguration(None,None)

  val id1 = NodeId("node1")
  val hostname1 = "node1.localhost"
  val admin1 = "root"
  val id2 = NodeId("node2")
  val hostname2 = "node2.localhost"
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
    , Some(Enforce)
  )
  val root = NodeInfo (
      rootNode
    , rootHostname
    , Some(MachineInfo(MachineUuid("machine1"), VirtualMachineType(VirtualBox), None, None))
    , Linux(Debian, "Jessie", new Version("7.0"), None, new Version("3.2"))
    , List("127.0.0.1", "192.168.0.100")
    , DateTime.now
    , UndefinedKey
    , Seq(AgentInfo(CfeCommunity, Some(AgentVersion("4.0.0")), PublicKey(PUBKEY)))
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
    , Some(NodeTimezone("UTC", "+00"))
  )

  val node1Node = Node (
      id1
    , "node1"
    , ""
    , false
    , false
    , true //is policy server
    , DateTime.now
    , emptyNodeReportingConfiguration
    , Seq()
    , None
  )

  val node1 = NodeInfo (
      node1Node
    , hostname1
    , Some(MachineInfo(MachineUuid("machine1"), VirtualMachineType(VirtualBox), None, None))
    , Linux(Debian, "Jessie", new Version("7.0"), None, new Version("3.2"))
    , List("192.168.0.10")
    , DateTime.now
    , UndefinedKey
    , Seq(AgentInfo(CfeCommunity, Some(AgentVersion("4.0.0")), PublicKey(PUBKEY)))
    , rootId
    , admin1
    , Set()
    , None
    , Some(MemorySize(1460132))
    , None
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
    , agents               = Seq()
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
  val node2 = node1.copy(node = node2Node, hostname = hostname2, policyServerId = node1.id )

  val dscNode1Node = Node (
      NodeId("node-dsc")
    , "node-dsc"
    , ""
    , false
    , false
    , true //is policy server
    , DateTime.now
    , emptyNodeReportingConfiguration
    , Seq()
    , None
  )

  val dscNode1 = NodeInfo (
      dscNode1Node
    , "node-dsc.localhost"
    , Some(MachineInfo(MachineUuid("machine1"), VirtualMachineType(VirtualBox), None, None))
    , Windows(Windows2012, "Windows 2012 youpla boom", new Version("2012"), Some("sp1"), new Version("win-kernel-2012"))
    , List("192.168.0.5")
    , DateTime.now
    , UndefinedKey
    , Seq(AgentInfo(AgentType.Dsc, Some(AgentVersion("5.0.0")), Certificate("windows-node-dsc-certificate")))
    , rootId
    , admin1
    , Set()
    , None
    , Some(MemorySize(1460132))
    , None
  )

  val dscInventory1: NodeInventory = NodeInventory(
      NodeSummary(
          dscNode1.id
        , AcceptedInventory
        , dscNode1.localAdministratorAccountName
        , dscNode1.hostname
        , dscNode1.osDetails
        , dscNode1.policyServerId
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
    , agents               = Seq()
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


  val allNodesInfo = Map( rootId -> root, node1.id -> node1, node2.id -> node2)

  val defaultModesConfig = NodeModeConfig(
      globalComplianceMode = GlobalComplianceMode(FullCompliance, 30)
    , nodeHeartbeatPeriod  = None
    , globalAgentRun       = AgentRunInterval(None, 5, 0, 0, 0)
    , nodeAgentRun         = None
    , globalPolicyMode     = GlobalPolicyMode(Enforce, PolicyModeOverrides.Always)
    , nodePolicyMode       = None
  )

  val rootNodeConfig = NodeConfiguration(
      nodeInfo    = root
    , modesConfig = defaultModesConfig
    , policyDrafts= Set[Cf3PolicyDraft]()
    , nodeContext = Map[String, Variable]()
    , parameters  = Set[ParameterForConfiguration]()
    , isRootServer= true
  )

  val node1NodeConfig = NodeConfiguration(
      nodeInfo    = node1
    , modesConfig = defaultModesConfig
    , policyDrafts= Set[Cf3PolicyDraft]()
    , nodeContext = Map[String, Variable]()
    , parameters  = Set[ParameterForConfiguration]()
    , isRootServer= false
  )

  val node2NodeConfig = NodeConfiguration(
      nodeInfo    = node2
    , modesConfig = defaultModesConfig
    , policyDrafts= Set[Cf3PolicyDraft]()
    , nodeContext = Map[String, Variable]()
    , parameters  = Set[ParameterForConfiguration]()
    , isRootServer= false
  )

  /**
   * Some more nodes
   */
  val nodeIds = (for {
    i <- 0 to 10
  } yield {
    NodeId(s"${i}")
  }).toSet

  def newNode(id : NodeId) = Node(id,"" ,"", false, false, false, DateTime.now, ReportingConfiguration(None,None), Seq(), None)

  val nodes = (Set(root, node1, node2) ++ nodeIds.map {
    id =>
      NodeInfo (
            newNode(id)
          , s"Node-${id}"
          , None
          , Linux(Debian, "Jessie", new Version("7.0"), None, new Version("3.2"))
          , Nil, DateTime.now
          , UndefinedKey, Seq(AgentInfo(CfeCommunity, None, PublicKey("rsa public key"))), NodeId("root")
          , "" , Set(), None, None, None
    )
  }).map(n => (n.id, n)).toMap

  /**
   *   ************************************************************************
   *                         Some groups
   *   ************************************************************************
   */

  val g1 = NodeGroup (NodeGroupId("1"), "Empty group", "", None, false, Set(), true)
  val g2 = NodeGroup (NodeGroupId("2"), "only root", "", None, false, Set(NodeId("root")), true)
  val g3 = NodeGroup (NodeGroupId("3"), "Even nodes", "", None, false, nodeIds.filter(_.value.toInt == 2), true)
  val g4 = NodeGroup (NodeGroupId("4"), "Odd nodes", "", None, false, nodeIds.filter(_.value.toInt != 2), true)
  val g5 = NodeGroup (NodeGroupId("5"), "Nodes id divided by 3", "", None, false, nodeIds.filter(_.value.toInt == 3), true)
  val g6 = NodeGroup (NodeGroupId("6"), "Nodes id divided by 5", "", None, false, nodeIds.filter(_.value.toInt == 5), true)
  val groups = Set(g1, g2, g3, g4, g5, g6 ).map(g => (g.id, g))

  val groupTargets = groups.map{ case (id, g) => (GroupTarget(g.id), g) }

  val fullRuleTargetInfos = (groupTargets.map(gt =>
    ( gt._1.groupId
    , FullRuleTargetInfo(
          FullGroupTarget(gt._1,gt._2)
        , ""
        , ""
        , true
        , false
      )
    )
  )).toMap

  val fngc = FullNodeGroupCategory (
      NodeGroupCategoryId("test_root")
    , ""
    , ""
    , Nil
    , fullRuleTargetInfos.values.toList
  )

  /**
   *   ************************************************************************
   *                         Some directives
   *   ************************************************************************
   */
  implicit def toATID(s: String) = ActiveTechniqueId(s)
  implicit def toTV(s: String) = TechniqueVersion(s)
  implicit def toTN(s: String) = TechniqueName(s)
  implicit def toTID(id: (String, String)) = TechniqueId(id._1, id._2)
  implicit def toDID(id: String) = DirectiveId(id)
  implicit def toRID(id: String) = RuleId(id)
  implicit def toRCID(id: String) = RuleCategoryId(id)
  val t1 = Technique(("t1", "1.0"), "t1", "t1", Nil, TrackerVariableSpec(), SectionSpec("root"), None)
  val d1 = Directive("d1", "1.0", Map("foo1" -> Seq("bar1")), "d1", "d1", None)
  val d2 = Directive("d2", "1.0", Map("foo2" -> Seq("bar2")), "d2", "d2", Some(PolicyMode.Enforce))
  val d3 = Directive("d3", "1.0", Map("foo3" -> Seq("bar3")), "d3", "d3", Some(PolicyMode.Audit))
  val fat1 = FullActiveTechnique("d1", "t1"
    , SortedMap(toTV("1.0") -> DateTime.parse("2016-01-01T12:00:00.000+00:00") )
    , SortedMap(toTV("1.0") -> t1)
    , d1 :: d2 :: Nil
  )

  val directives = FullActiveTechniqueCategory(ActiveTechniqueCategoryId("root"), "root", "root", Nil, fat1 :: Nil)

    /**
   *   ************************************************************************
   *                         Some rules
   *   ************************************************************************
   */

   val r1 = Rule("r1", "r1", 1, "cat1")
   val r2 = Rule("r2", "r2", 1, "cat1")

}
