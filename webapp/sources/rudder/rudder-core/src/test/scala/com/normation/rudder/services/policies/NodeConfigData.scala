/* 6.0
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
import com.normation.rudder.reports._
import com.normation.rudder.repository.FullActiveTechnique
import com.normation.rudder.repository.FullActiveTechniqueCategory
import com.normation.rudder.rule.category.RuleCategoryId
import org.joda.time.DateTime

import scala.collection.SortedMap
import scala.language.implicitConversions
import com.normation.inventory.domain.Windows
import com.normation.inventory.domain.Windows2012
import com.normation.inventory.domain.AgentType
import com.normation.inventory.domain.Certificate
import com.normation.rudder.domain.nodes.NodeState
import com.normation.cfclerk.domain.TrackerVariable
import com.normation.cfclerk.services.impl.GitTechniqueReader
import com.normation.cfclerk.xmlparsers.TechniqueParser
import com.normation.cfclerk.xmlparsers.SectionSpecParser
import com.normation.utils.StringUuidGeneratorImpl
import com.normation.cfclerk.services.impl.SimpleGitRevisionProvider
import com.normation.cfclerk.services.impl.TechniqueRepositoryImpl
import com.normation.cfclerk.services.impl.SystemVariableSpecServiceImpl
import com.normation.cfclerk.xmlparsers.VariableSpecParser
import java.io.File
import java.nio.file.{Files, Paths}

import com.normation.cfclerk.services.impl.GitRepositoryProviderImpl
import com.normation.rudder.domain.policies.FullOtherTarget
import com.normation.rudder.domain.policies.PolicyServerTarget
import com.normation.rudder.domain.policies.AllTargetExceptPolicyServers
import com.normation.rudder.domain.policies.AllTarget
import com.normation.eventlog.EventActor
import net.liftweb.common.Full
import net.liftweb.common.Box
import com.normation.eventlog.ModificationId
import com.normation.rudder.services.servers.PolicyServerManagementService
import com.normation.rudder.repository.FullNodeGroupCategory
import org.apache.commons.io.FileUtils
import com.normation.rudder.services.servers.RelaySynchronizationMethod.Classic

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

  val emptyNodeReportingConfiguration = ReportingConfiguration(None,None, None)

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
    , NodeState.Enabled
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
    , NodeState.Enabled
    , false
    , false
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
  val node2 = node1.copy(node = node2Node, hostname = hostname2, policyServerId = root.id )

  val dscNode1Node = Node (
      NodeId("node-dsc")
    , "node-dsc"
    , ""
    , NodeState.Enabled
    , false
    , true //is draft server
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
    , runHooks    = List()
    , policies    = List[Policy]()
    , nodeContext = Map[String, Variable]()
    , parameters  = Set[ParameterForConfiguration]()
    , isRootServer= true
  )

  val node1NodeConfig = NodeConfiguration(
      nodeInfo    = node1
    , modesConfig = defaultModesConfig
    , runHooks    = List()
    , policies    = List[Policy]()
    , nodeContext = Map[String, Variable]()
    , parameters  = Set[ParameterForConfiguration]()
    , isRootServer= false
  )

  val node2NodeConfig = NodeConfiguration(
      nodeInfo    = node2
    , modesConfig = defaultModesConfig
    , runHooks    = List()
    , policies    = List[Policy]()
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

  def newNode(id : NodeId) = Node(id,"" ,"", NodeState.Enabled, false, false, DateTime.now, ReportingConfiguration(None,None, None), Seq(), None)

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

  val g0id = NodeGroupId("0")
  val g0 = NodeGroup (g0id, "Real nodes", "", None, false, Set(rootId, node1.id, node2.id), true)
  val g1 = NodeGroup (NodeGroupId("1"), "Empty group", "", None, false, Set(), true)
  val g2 = NodeGroup (NodeGroupId("2"), "only root", "", None, false, Set(NodeId("root")), true)
  val g3 = NodeGroup (NodeGroupId("3"), "Even nodes", "", None, false, nodeIds.filter(_.value.toInt == 2), true)
  val g4 = NodeGroup (NodeGroupId("4"), "Odd nodes", "", None, false, nodeIds.filter(_.value.toInt != 2), true)
  val g5 = NodeGroup (NodeGroupId("5"), "Nodes id divided by 3", "", None, false, nodeIds.filter(_.value.toInt == 3), true)
  val g6 = NodeGroup (NodeGroupId("6"), "Nodes id divided by 5", "", None, false, nodeIds.filter(_.value.toInt == 5), true)
  val groups = Set(g0, g1, g2, g3, g4, g5, g6).map(g => (g.id, g))

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

  val groupLib = FullNodeGroupCategory (
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

   val r1 = Rule("r1", "r1", "cat1")
   val r2 = Rule("r2", "r2", "cat1")

}

/* =============================================================
 *   Some real data that can be used to test the write process
 * =============================================================
 *
 */

class TestNodeConfiguration() {

  import org.joda.time.DateTime

  import com.normation.rudder.services.policies.NodeConfigData.{root, node1}
  /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // set up root node configuration
  /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  //just a little sugar to stop hurting my eyes with new File(blablab, plop)
  implicit class PathString(root: String) {
    def /(child: String) = new File(root, child)
  }
  implicit class PathString2(root: File) {
    def /(child: String) = new File(root, child)
  }
  val t0 = System.currentTimeMillis()

  val abstractRoot = new File("/tmp/test-rudder-config-repo-" + DateTime.now.toString())
  abstractRoot.mkdirs()

  // config-repo will also be the git root, as a normal rudder
  val configurationRepositoryRoot = abstractRoot/"configuration-repository"
  //initialize config-repo content from our test/resources source

  FileUtils.copyDirectory( new File("src/test/resources/configuration-repository") , configurationRepositoryRoot)

  val EXPECTED_SHARE = configurationRepositoryRoot/"expected-share"
  val t1 = System.currentTimeMillis()
  println(s"Paths inits             : ${t1-t0} ms")

  val repo = new GitRepositoryProviderImpl(configurationRepositoryRoot.getAbsolutePath)
  val t2 = System.currentTimeMillis()
  println(s"Git repo provider       : ${t2-t1} ms")


  val variableSpecParser = new VariableSpecParser
  val t2bis = System.currentTimeMillis()
  println(s"var Spec Parser        : ${t2bis-t2} ms")
  val systemVariableServiceSpec = new SystemVariableSpecServiceImpl()
  val t3 = System.currentTimeMillis()
  println(s"System Var Spec service : ${t3-t2bis} ms")

  val draftParser: TechniqueParser = new TechniqueParser(
      variableSpecParser
    , new SectionSpecParser(variableSpecParser)
    , systemVariableServiceSpec
  )
  val t4 = System.currentTimeMillis()
  println(s"Technique parser        : ${t4-t3} ms")

  val reader = new GitTechniqueReader(
                draftParser
              , new SimpleGitRevisionProvider("refs/heads/master", repo)
              , repo
              , "metadata.xml"
              , "category.xml"
              , Some("techniques")
              , "default-directive-names.conf"
            )
  val t5 = System.currentTimeMillis()
  println(s"Git tech reader         : ${t5-t4} ms")

  val techniqueRepository = new TechniqueRepositoryImpl(reader, Seq(), new StringUuidGeneratorImpl())
  val t6 = System.currentTimeMillis()
  println(s"Technique repository    : ${t6-t5} ms")

  val draftServerManagement = new PolicyServerManagementService() {
    override def setAuthorizedNetworks(policyServerId:NodeId, networks:Seq[String], modId: ModificationId, actor:EventActor) = ???
    override def getAuthorizedNetworks(policyServerId:NodeId) : Box[Seq[String]] = Full(List("192.168.49.0/24"))
  }
  val t7 = System.currentTimeMillis()
  println(s"Policy Server Management: ${t7-t6} ms")

  val systemVariableService = new SystemVariableServiceImpl(
      systemVariableServiceSpec
    , draftServerManagement
    , toolsFolder              = "tools_folder"
    , communityPort            = 5309
    , sharedFilesFolder        = "/var/rudder/configuration-repository/shared-files"
    , webdavUser               = "rudder"
    , webdavPassword           = "rudder"
    , reportsDbUri             = "rudder"
    , reportsDbUser            = "rudder"
    , syslogPort               = 514
    , configurationRepository  = configurationRepositoryRoot.getAbsolutePath
    , serverRoles              = Seq(
                                     RudderServerRole("rudder-ldap"                   , "rudder.server-roles.ldap")
                                   , RudderServerRole("rudder-inventory-endpoint"     , "rudder.server-roles.inventory-endpoint")
                                   , RudderServerRole("rudder-db"                     , "rudder.server-roles.db")
                                   , RudderServerRole("rudder-relay-top"              , "rudder.server-roles.relay-top")
                                   , RudderServerRole("rudder-web"                    , "rudder.server-roles.web")
                                   , RudderServerRole("rudder-relay-promises-only"    , "rudder.server-roles.relay-promises-only")
                                   , RudderServerRole("rudder-cfengine-mission-portal", "rudder.server-roles.cfengine-mission-portal")
                                 )
    , serverVersion            = "5.1.0"

    //denybadclocks is runtime properties
    , getDenyBadClocks         = () => Full(true)
    , getSyncMethod            = () => Full(Classic)
    , getSyncPromises          = () => Full(false)
    , getSyncSharedFiles       = () => Full(false)
    // TTLs are runtime properties too
    , getModifiedFilesTtl             = () => Full(30)
    , getCfengineOutputsTtl           = () => Full(7)
    , getStoreAllCentralizedLogsInFile= () => Full(true)
    , getSendMetrics                  = () => Full(None)
    , getSyslogProtocol               = () => Full(SyslogUDP)
    , getSyslogProtocolDisabled       = () => Full(false)
    , getReportProtocolDefault        = () => Full(AgentReportingSyslog)
    , getRudderVerifyCertificates     = () => Full(false)
  )

  val t8 = System.currentTimeMillis()
  println(s"System variable Service: ${t8-t7} ms")

  //a test node - CFEngine
  val nodeId = NodeId("c8813416-316f-4307-9b6a-ca9c109a9fb0")
  val cfeNode = node1.copy(node = node1.node.copy(id = nodeId, name = nodeId.value))

  val allNodesInfo_rootOnly = Map(root.id -> root)
  val allNodesInfo_cfeNode = Map(root.id -> root, cfeNode.id -> cfeNode)

  //the group lib
  val emptyGroupLib = FullNodeGroupCategory(
      NodeGroupCategoryId("/")
    , "/"
    , "root of group categories"
    , List()
    , List()
    , true
  )

  val groupLib = emptyGroupLib.copy(
      targetInfos = List(
          FullRuleTargetInfo(
              FullGroupTarget(
                  GroupTarget(NodeGroupId("a-group-for-root-only"))
                , NodeGroup(NodeGroupId("a-group-for-root-only")
                    , "Serveurs [€ðŋ] cassés"
                    , "Liste de l'ensemble de serveurs cassés à réparer"
                    , None
                    , true
                    , Set(NodeId("root"))
                    , true
                    , false
                  )
              )
              , "Serveurs [€ðŋ] cassés"
              , "Liste de l'ensemble de serveurs cassés à réparer"
              , true
              , false
            )
        , FullRuleTargetInfo(
              FullOtherTarget(PolicyServerTarget(NodeId("root")))
            , "special:policyServer_root"
            , "The root policy server"
            , true
            , true
          )
        , FullRuleTargetInfo(
            FullOtherTarget(AllTargetExceptPolicyServers)
            , "special:all_exceptPolicyServers"
            , "All groups without policy servers"
            , true
            , true
          )
        , FullRuleTargetInfo(
            FullOtherTarget(AllTarget)
            , "special:all"
            , "All nodes"
            , true
            , true
          )
      )
  )

  val globalAgentRun = AgentRunInterval(None, 5, 1, 0, 4)
  val globalComplianceMode = GlobalComplianceMode(FullCompliance, 15)

  val globalSystemVariables = systemVariableService.getGlobalSystemVariables(globalAgentRun).openOrThrowException("I should get global system variable in test!")

  val t9 = System.currentTimeMillis()
  println(s"Nodes & groupes         : ${t9-t8} ms")


  //
  //root has 4 system directive, let give them some variables
  //
  implicit class UnsafeGet(t: Option[Technique]) {
    def unsafeGet = t.getOrElse(throw new RuntimeException("Bad init for test"))
  }

  val commonTechnique = techniqueRepository.get(TechniqueId(TechniqueName("common"), TechniqueVersion("1.0"))).unsafeGet
  def commonVariables(nodeId: NodeId, allNodeInfos: Map[NodeId, NodeInfo]) = {
     val spec = commonTechnique.getAllVariableSpecs.map(s => (s.name, s)).toMap
     Seq(
       spec("ALLOWEDNETWORK").toVariable(Seq("192.168.0.0/16"))
     , spec("OWNER").toVariable(Seq(allNodeInfos(nodeId).localAdministratorAccountName))
     , spec("UUID").toVariable(Seq(nodeId.value))
     , spec("POLICYSERVER_ID").toVariable(Seq(allNodeInfos(nodeId).policyServerId.value))
     , spec("POLICYSERVER").toVariable(Seq(allNodeInfos(allNodeInfos(nodeId).policyServerId).hostname))
     , spec("POLICYSERVER_ADMIN").toVariable(Seq(allNodeInfos(allNodeInfos(nodeId).policyServerId).localAdministratorAccountName))
     ).map(v => (v.spec.name, v)).toMap
  }

  def draft (
      id            : PolicyId
    , ruleName      : String
    , directiveName : String
    , technique     : Technique
    , variableMap   : Map[String, Variable]
    , tracker       : TrackerVariable
    , ruleOrder     : BundleOrder
    , directiveOrder: BundleOrder
    , system        : Boolean = true
    , policyMode    : Option[PolicyMode] = None
  ) = {
    BoundPolicyDraft(
        id
      , ruleName
      , directiveName
      , technique
      , DateTime.now
      , variableMap
      , variableMap
      , tracker
      , 0
      , system
      , policyMode
      , ruleOrder
      , directiveOrder
      , Set()
    )
  }

  val commonDirective = Directive(
      DirectiveId("common-root")
    , TechniqueVersion("1.0")
    , Map(
        ("ALLOWEDNETWORK", Seq("192.168.0.0/16"))
      , ("OWNER", Seq("${rudder.node.admin}"))
      , ("UUID", Seq("${rudder.node.id}"))
      , ("POLICYSERVER_ID", Seq("${rudder.node.policyserver.id}"))
      , ("POLICYSERVER", Seq("${rudder.node.policyserver.hostname}"))
      , ("POLICYSERVER_ADMIN", Seq("${rudder.node.policyserver.admin}"))
      )
    , "common-root"
    , "", None, "", 5, true, true
  )

  def common(nodeId: NodeId, allNodeInfos: Map[NodeId, NodeInfo]) = {
    val id = PolicyId(RuleId("hasPolicyServer-root"), DirectiveId("common-root"), TechniqueVersion("1.0"))
    draft(
        id
      , "Rudder system policy: basic setup (common)"
      , "Common"
      , commonTechnique
      , commonVariables(nodeId, allNodeInfos)
      , commonTechnique.trackerVariableSpec.toVariable(Seq(id.getReportId)) //card = 1 because unique
      , BundleOrder("Rudder system policy: basic setup (common)")
      , BundleOrder("Common")
    )
  }

  val rolesTechnique = techniqueRepository.get(TechniqueId(TechniqueName("server-roles"), TechniqueVersion("1.0"))).unsafeGet
  val rolesVariables = {
     val spec = commonTechnique.getAllVariableSpecs.map(s => (s.name, s)).toMap
     Seq(
       spec("ALLOWEDNETWORK").toVariable(Seq("192.168.0.0/16"))
     ).map(v => (v.spec.name, v)).toMap
  }

  val serverRole = {
    val id = PolicyId(RuleId("server-roles"), DirectiveId("server-roles-directive"), TechniqueVersion("1.0"))
    draft(
        id
      , "Rudder system policy: Server roles"
      , "Server Roles"
      , rolesTechnique
      , rolesVariables
      , rolesTechnique.trackerVariableSpec.toVariable(Seq(id.getReportId))
      , BundleOrder("Rudder system policy: Server roles")
      , BundleOrder("Server Roles")
    )
  }

  val distributeTechnique = techniqueRepository.get(TechniqueId(TechniqueName("distributePolicy"), TechniqueVersion("1.0"))).unsafeGet
  val distributeVariables = {
     val spec = commonTechnique.getAllVariableSpecs.map(s => (s.name, s)).toMap
     Seq(
       spec("ALLOWEDNETWORK").toVariable(Seq("192.168.0.0/16"))
     ).map(v => (v.spec.name, v)).toMap
  }

  val distributePolicy = {
    val id = PolicyId(RuleId("root-DP"), DirectiveId("root-distributePolicy"), TechniqueVersion("1.0"))
    draft(
        id
      , "distributePolicy"
      , "Distribute Policy"
      , distributeTechnique
      , distributeVariables
      , distributeTechnique.trackerVariableSpec.toVariable(Seq(id.getReportId))
      , BundleOrder("distributePolicy")
      , BundleOrder("Distribute Policy")
    )
  }

  val inventoryTechnique = techniqueRepository.get(TechniqueId(TechniqueName("inventory"), TechniqueVersion("1.0"))).unsafeGet
  val inventoryVariables = {
     val spec = commonTechnique.getAllVariableSpecs.map(s => (s.name, s)).toMap
     Seq(
       spec("ALLOWEDNETWORK").toVariable(Seq("192.168.0.0/16"))
     ).map(v => (v.spec.name, v)).toMap
  }
  val inventoryAll = {
    val id = PolicyId(RuleId("inventory-all"), DirectiveId("inventory-all"), TechniqueVersion("1.0"))
      draft(
        id
      , "Rudder system policy: daily inventory"
      , "Inventory"
      , inventoryTechnique
      , inventoryVariables
      , inventoryTechnique.trackerVariableSpec.toVariable(Seq(id.getReportId))
      , BundleOrder("Rudder system policy: daily inventory")
      , BundleOrder("Inventory")
    )
  }

  //
  // 4 user directives: clock management, rpm, package, a multi-policiy: fileTemplate, and a ncf one: Create_file
  //
  lazy val clockTechnique = techniqueRepository.get(TechniqueId(TechniqueName("clockConfiguration"), TechniqueVersion("3.0"))).unsafeGet
  lazy val clockVariables = {
     val spec = clockTechnique.getAllVariableSpecs.map(s => (s.name, s)).toMap
     Seq(
         spec("CLOCK_FQDNNTP").toVariable(Seq("true"))
       , spec("CLOCK_HWSYNC_ENABLE").toVariable(Seq("true"))
       , spec("CLOCK_NTPSERVERS").toVariable(Seq("${rudder.param.ntpserver}"))
       , spec("CLOCK_SYNCSCHED").toVariable(Seq("240"))
       , spec("CLOCK_TIMEZONE").toVariable(Seq("dontchange"))
     ).map(v => (v.spec.name, v)).toMap
  }
  lazy val clock = {
    val id = PolicyId(RuleId("rule1"), DirectiveId("directive1"), TechniqueVersion("1.0"))
    draft(
        id
      , "10. Global configuration for all nodes"
      , "10. Clock Configuration"
      , clockTechnique
      , clockVariables
      , clockTechnique.trackerVariableSpec.toVariable(Seq(id.getReportId))
      , BundleOrder("10. Global configuration for all nodes")
      , BundleOrder("10. Clock Configuration")
      , false
      , Some(PolicyMode.Enforce)
    )
  }
  /*
   * A RPM Policy, which comes from 2 directives.
   * The second one contributes two packages.
   * It had a different value for the CHECK_INTERVAL, but
   * that variable is unique, so it get the first draft value all along.
   */

  lazy val rpmTechnique = techniqueRepository.get(TechniqueId(TechniqueName("rpmPackageInstallation"), TechniqueVersion("7.0"))).unsafeGet
  lazy val rpmVariables = {
     val spec = rpmTechnique.getAllVariableSpecs.map(s => (s.name, s)).toMap
     Seq(
         spec("RPM_PACKAGE_CHECK_INTERVAL").toVariable(Seq("5"))
       , spec("RPM_PACKAGE_POST_HOOK_COMMAND").toVariable(Seq("","",""))
       , spec("RPM_PACKAGE_POST_HOOK_RUN").toVariable(Seq("false","false","false"))
       , spec("RPM_PACKAGE_REDACTION").toVariable(Seq("add","add","add"))
       , spec("RPM_PACKAGE_REDLIST").toVariable(Seq("plop","foo","bar"))
       , spec("RPM_PACKAGE_VERSION").toVariable(Seq("","",""))
       , spec("RPM_PACKAGE_VERSION_CRITERION").toVariable(Seq("==","==","=="))
       , spec("RPM_PACKAGE_VERSION_DEFINITION").toVariable(Seq("default","default","default"))
     ).map(v => (v.spec.name, v)).toMap
  }
  def rpmDirective(id: String, pkg: String) = Directive(
      DirectiveId(id)
    , TechniqueVersion("7.0")
    , Map(
         ("RPM_PACKAGE_CHECK_INTERVAL", Seq("5"))
       , ("RPM_PACKAGE_POST_HOOK_COMMAND", Seq(""))
       , ("RPM_PACKAGE_POST_HOOK_RUN", Seq("false"))
       , ("RPM_PACKAGE_REDACTION", Seq("add"))
       , ("RPM_PACKAGE_REDLIST", Seq(pkg))
       , ("RPM_PACKAGE_VERSION", Seq(""))
       , ("RPM_PACKAGE_VERSION_CRITERION", Seq("=="))
       , ("RPM_PACKAGE_VERSION_DEFINITION", Seq("default"))
      )
    , id, "", None, ""
  )
  lazy val rpm = {
    val id = PolicyId(RuleId("rule2"), DirectiveId("directive2"), TechniqueVersion("1.0"))
    draft(
        id
      , "50. Deploy PLOP STACK"
      , "20. Install PLOP STACK main rpm"
      , rpmTechnique
      , rpmVariables
      , rpmTechnique.trackerVariableSpec.toVariable(Seq(id.getReportId))
      , BundleOrder("50. Deploy PLOP STACK")
      , BundleOrder("20. Install PLOP STACK main rpm")
      , false
      , Some(PolicyMode.Audit)
    )
  }

  lazy val pkgTechnique = techniqueRepository.get(TechniqueId(TechniqueName("packageManagement"), TechniqueVersion("1.0"))).unsafeGet
  lazy val pkgVariables = {
     val spec = pkgTechnique.getAllVariableSpecs.map(s => (s.name, s)).toMap
     Seq(
         spec("PACKAGE_LIST").toVariable(Seq("htop"))
       , spec("PACKAGE_STATE").toVariable(Seq("present"))
       , spec("PACKAGE_VERSION").toVariable(Seq("latest"))
       , spec("PACKAGE_VERSION_SPECIFIC").toVariable(Seq(""))
       , spec("PACKAGE_ARCHITECTURE").toVariable(Seq("default"))
       , spec("PACKAGE_ARCHITECTURE_SPECIFIC").toVariable(Seq(""))
       , spec("PACKAGE_MANAGER").toVariable(Seq("default"))
       , spec("PACKAGE_POST_HOOK_COMMAND").toVariable(Seq(""))
     ).map(v => (v.spec.name, v)).toMap
  }
  lazy val pkg = {
    val id = PolicyId(RuleId("ff44fb97-b65e-43c4-b8c2-0df8d5e8549f"), DirectiveId("16617aa8-1f02-4e4a-87b6-d0bcdfb4019f"), TechniqueVersion("1.0"))
    draft(
        id
      , "60-rule-technique-std-lib"
      , "Package management."
      , pkgTechnique
      , pkgVariables
      , pkgTechnique.trackerVariableSpec.toVariable(Seq(id.getReportId))
      , BundleOrder("60-rule-technique-std-lib")
      , BundleOrder("Package management.")
      , false
      , Some(PolicyMode.Enforce)
    )
  }

  lazy val fileTemplateTechnique = techniqueRepository.get(TechniqueId(TechniqueName("fileTemplate"), TechniqueVersion("1.0"))).unsafeGet
  lazy val fileTemplateVariables1 = {
     val spec = fileTemplateTechnique.getAllVariableSpecs.map(s => (s.name, s)).toMap
     Seq(
         spec("FILE_TEMPLATE_RAW_OR_NOT").toVariable(Seq("Raw"))
       , spec("FILE_TEMPLATE_TEMPLATE").toVariable(Seq(""))
       , spec("FILE_TEMPLATE_RAW_TEMPLATE").toVariable(Seq("some content"))
       , spec("FILE_TEMPLATE_AGENT_DESTINATION_PATH").toVariable(Seq("/tmp/destination.txt"))
       , spec("FILE_TEMPLATE_TEMPLATE_TYPE").toVariable(Seq("mustache"))
       , spec("FILE_TEMPLATE_OWNER").toVariable(Seq("root"))
       , spec("FILE_TEMPLATE_GROUP_OWNER").toVariable(Seq("root"))
       , spec("FILE_TEMPLATE_PERMISSIONS").toVariable(Seq("700"))
       , spec("FILE_TEMPLATE_PERSISTENT_POST_HOOK").toVariable(Seq("false"))
       , spec("FILE_TEMPLATE_TEMPLATE_POST_HOOK_COMMAND").toVariable(Seq(""))
     ).map(v => (v.spec.name, v)).toMap
  }
  lazy val fileTemplate1 = {
    val id = PolicyId(RuleId("ff44fb97-b65e-43c4-b8c2-0df8d5e8549f"), DirectiveId("e9a1a909-2490-4fc9-95c3-9d0aa01717c9"), TechniqueVersion("1.0"))
    draft(
        id
      , "60-rule-technique-std-lib"
      , "10-File template 1"
      , fileTemplateTechnique
      , fileTemplateVariables1
      , fileTemplateTechnique.trackerVariableSpec.toVariable(Seq(id.getReportId))
      , BundleOrder("60-rule-technique-std-lib")
      , BundleOrder("10-File template 1")
      , false
      , Some(PolicyMode.Enforce)
    )
  }
  lazy val fileTemplateVariables2 = {
     val spec = fileTemplateTechnique.getAllVariableSpecs.map(s => (s.name, s)).toMap
     Seq(
         spec("FILE_TEMPLATE_RAW_OR_NOT").toVariable(Seq("Raw"))
       , spec("FILE_TEMPLATE_TEMPLATE").toVariable(Seq(""))
       , spec("FILE_TEMPLATE_RAW_TEMPLATE").toVariable(Seq("some content"))
       , spec("FILE_TEMPLATE_AGENT_DESTINATION_PATH").toVariable(Seq("/tmp/other-destination.txt"))
       , spec("FILE_TEMPLATE_TEMPLATE_TYPE").toVariable(Seq("mustache"))
       , spec("FILE_TEMPLATE_OWNER").toVariable(Seq("root"))
       , spec("FILE_TEMPLATE_GROUP_OWNER").toVariable(Seq("root"))
       , spec("FILE_TEMPLATE_PERMISSIONS").toVariable(Seq("777"))
       , spec("FILE_TEMPLATE_PERSISTENT_POST_HOOK").toVariable(Seq("true"))
       , spec("FILE_TEMPLATE_TEMPLATE_POST_HOOK_COMMAND").toVariable(Seq("/bin/true"))
     ).map(v => (v.spec.name, v)).toMap
  }
  lazy val fileTemplate2 = {
    val id = PolicyId(RuleId("ff44fb97-b65e-43c4-b8c2-0df8d5e8549f"), DirectiveId("99f4ef91-537b-4e03-97bc-e65b447514cc"), TechniqueVersion("1.0"))
    draft(
        id
      , "60-rule-technique-std-lib"
      , "20-File template 2"
      , fileTemplateTechnique
      , fileTemplateVariables2
      , fileTemplateTechnique.trackerVariableSpec.toVariable(Seq(id.getReportId))
      , BundleOrder("60-rule-technique-std-lib")
      , BundleOrder("20-File template 2")
      , false
      , Some(PolicyMode.Enforce)
    )
  }

  // fileTemplate3 is a copy of fileTemplate2 but provided by an other rule
  lazy val fileTemplate3 = {
    val id = PolicyId(RuleId("ff44fb97-b65e-43c4-b8c2-000000000000"), DirectiveId("99f4ef91-537b-4e03-97bc-e65b447514cc"), TechniqueVersion("1.0"))
    draft(
        id
      , "99-rule-technique-std-lib"
      , "20-File template 2"
      , fileTemplateTechnique
      , fileTemplateVariables2
      , fileTemplateTechnique.trackerVariableSpec.toVariable(Seq(id.getReportId))
      , BundleOrder("99-rule-technique-std-lib")
      , BundleOrder("20-File template 2")
      , false
      , Some(PolicyMode.Enforce)
    )
  }


  val ncf1Technique = techniqueRepository.get(TechniqueId(TechniqueName("Create_file"), TechniqueVersion("1.0"))).unsafeGet
  val ncf1Variables = {
     val spec = ncf1Technique.getAllVariableSpecs.map(s => (s.name, s)).toMap
     Seq(
         spec("expectedReportKey Directory create").toVariable(Seq("directory_create_/tmp/foo"))
       , spec("expectedReportKey File create").toVariable(Seq("file_create_/tmp/foo/bar"))
       , spec("1AAACD71-C2D5-482C-BCFF-5EEE6F8DA9C2").toVariable(Seq("\"foo"))
     ).map(v => (v.spec.name, v)).toMap
  }
  val ncf1 = {
    val id = PolicyId(RuleId("208716db-2675-43b9-ab57-bfbab84346aa"), DirectiveId("16d86a56-93ef-49aa-86b7-0d10102e4ea9"), TechniqueVersion("1.0"))
    draft(
        id
      , "50-rule-technique-ncf"
      , "Create a file"
      , ncf1Technique
      , ncf1Variables
      , ncf1Technique.trackerVariableSpec.toVariable(Seq(id.getReportId))
      , BundleOrder("50-rule-technique-ncf")
      , BundleOrder("Create a file")
      , false
      , Some(PolicyMode.Enforce)
    )
  }

  /**
    * test for multiple generation
    */
  val DIRECTIVE_NAME_COPY_GIT_FILE="directive-copyGitFile"
  lazy val copyGitFileTechnique = techniqueRepository.get(TechniqueId(TechniqueName("copyGitFile"), TechniqueVersion("2.3"))).unsafeGet
  def copyGitFileVariable(i: Int) = {
    val spec = copyGitFileTechnique.getAllVariableSpecs.map(s => (s.name, s)).toMap
    Seq(
      spec("COPYFILE_NAME").toVariable(Seq("file_name_"+i+".json"))
      , spec("COPYFILE_EXCLUDE_INCLUDE_OPTION").toVariable(Seq("none"))
      , spec("COPYFILE_EXCLUDE_INCLUDE").toVariable(Seq(""))
      , spec("COPYFILE_DESTINATION").toVariable(Seq("/tmp/destination_"+i+".json"))
      , spec("COPYFILE_RECURSION").toVariable(Seq(s"${i%2}"))
      , spec("COPYFILE_PURGE").toVariable(Seq("false"))
      , spec("COPYFILE_COMPARE_METHOD").toVariable(Seq("mtime"))
      , spec("COPYFILE_OWNER").toVariable(Seq("root"))
      , spec("COPYFILE_GROUP").toVariable(Seq("root"))
      , spec("COPYFILE_PERM").toVariable(Seq("644"))
      , spec("COPYFILE_SUID").toVariable(Seq("false"))
      , spec("COPYFILE_SGID").toVariable(Seq("false"))
      , spec("COPYFILE_STICKY_FOLDER").toVariable(Seq("false"))
      , spec("COPYFILE_POST_HOOK_RUN").toVariable(Seq("true"))
      , spec("COPYFILE_POST_HOOK_COMMAND").toVariable(Seq("/bin/echo Value_"+i+".json"))
    ).map(v => (v.spec.name, v)).toMap
  }

  def copyGitFileDirectives(i:Int) = {
    val id = PolicyId(RuleId("rulecopyGitFile"), DirectiveId(DIRECTIVE_NAME_COPY_GIT_FILE+i), TechniqueVersion("2.3"))
    draft(
        id
      , "90-copy-git-file"
      , "Copy git file"
      , copyGitFileTechnique
      , copyGitFileVariable(i)
      , copyGitFileTechnique.trackerVariableSpec.toVariable(Seq(id.getReportId))
      , BundleOrder("90-copy-git-file")
      , BundleOrder("Copy git file")
      , false
      , Some(PolicyMode.Enforce)
    )
  }

  val t10 = System.currentTimeMillis()
  println(s"Get techniques & directives: ${t10-t9} ms")



  /**
    * create the 500 expected directives files for copygitfile
    */
    def createCopyGitFileDirectories(nodeName: String, listIds: Seq[Int]): Unit = {
      val dest_root_path = EXPECTED_SHARE + "/" + nodeName + "/rules/cfengine-community/copyGitFile"
      val source_tml = EXPECTED_SHARE + "/" + "copyFileFromSharedFolder.cf"

      val directiveBasePath = dest_root_path + "/2_3_"+DIRECTIVE_NAME_COPY_GIT_FILE.replace("-", "_")

      Files.createDirectory(Paths.get(dest_root_path))

      // read the source template
      val tml = Files.lines(Paths.get(source_tml)).toArray.mkString("\n")

      // replace all tokens
      for {
        id       <- listIds
        replaced = tml.replaceAll("TOKEN", id.toString)
        recursion = replaced.replaceAll("RECURSION", (id%2).toString)
      } yield {
        Files.createDirectory(Paths.get(directiveBasePath + id ))
        val dest = Files.createFile(Paths.get(directiveBasePath + id + "/copyFileFromSharedFolder.cf"))

        Files.write(dest, recursion.getBytes)
      }

    }


  /*
   * Test override order of generic-variable-definition.
   * We want to have to directive, directive1 and directive2.
   * directive1 is the default value and must be overriden by value in directive 2, which means that directive2 value must be
   * define after directive 1 value in generated "genericVariableDefinition.cf".
   * The semantic to achieve that is to use Priority: directive 1 has a higher (ie smaller int number) priority than directive 2.
   *
   * To be sure that we don't use rule/directive name order, we will make directive 2 sort name come before directive 1 sort name.
   *
   * BUT added subtilities: the final bundle name order that will be used is the most prioritary one, so that we keep the
   * global sorting logic between rules / directives.
   *
   * In summary: sorting directives that are merged into one is a different problem than sorting directives for the bundle sequence.
   */
  lazy val gvdTechnique  = techniqueRepository.get(TechniqueId(TechniqueName("genericVariableDefinition"), TechniqueVersion("2.0"))).getOrElse(throw new RuntimeException("Bad init for test"))
  lazy val gvdVariables1 = {
     val spec = gvdTechnique.getAllVariableSpecs.map(s => (s.name, s)).toMap
     Seq(
         spec("GENERIC_VARIABLE_NAME").toVariable(Seq("var1"))
       , spec("GENERIC_VARIABLE_CONTENT").toVariable(Seq("value from gvd #1 should be first")) // the one to override
     ).map(v => (v.spec.name, v)).toMap
  }
  lazy val gvd1 = {
    val id = PolicyId(RuleId("rule1"), DirectiveId("directive1"), TechniqueVersion("1.0"))
    draft(
        id
      , "10. Global configuration for all nodes"
      , "99. Generic Variable Def #1"
      , gvdTechnique
      , gvdVariables1
      , gvdTechnique.trackerVariableSpec.toVariable(Seq(id.getReportId))
      , BundleOrder("10. Global configuration for all nodes")
      , BundleOrder("99. Generic Variable Def #1") // the sort name tell that it comes after directive 2
      , false
      , Some(PolicyMode.Enforce)
    ).copy(
        priority = 0 // we want to make sure this one will be merged in first position
    )
  }
  lazy val gvdVariables2 = {
     val spec = gvdTechnique.getAllVariableSpecs.map(s => (s.name, s)).toMap
     Seq(
         spec("GENERIC_VARIABLE_NAME").toVariable(Seq("var1"))
       , spec("GENERIC_VARIABLE_CONTENT").toVariable(Seq("value from gvd #2 should be last")) // the one to use for override
     ).map(v => (v.spec.name, v)).toMap
  }
  lazy val gvd2 = {
    val id = PolicyId(RuleId("rule1"), DirectiveId("directive2"), TechniqueVersion("1.0"))
    draft(
        id
      , "10. Global configuration for all nodes"
      , "00. Generic Variable Def #2"
      , gvdTechnique
      , gvdVariables2
      , gvdTechnique.trackerVariableSpec.toVariable(Seq(id.getReportId))
      , BundleOrder("10. Global configuration for all nodes")
      , BundleOrder("00. Generic Variable Def #2") // sort name comes before sort name of directive 1
      , false
      , Some(PolicyMode.Enforce)
    ).copy (
        priority = 10 // we want to make sure this one will be merged in last position
    )
  }
 }
