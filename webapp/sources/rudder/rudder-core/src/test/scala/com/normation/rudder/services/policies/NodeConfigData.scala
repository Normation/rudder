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

package com.normation.rudder.services.policies

import com.normation.GitVersion
import com.normation.cfclerk.domain.PredefinedValuesVariableSpec
import com.normation.cfclerk.domain.SectionSpec
import com.normation.cfclerk.domain.Technique
import com.normation.cfclerk.domain.TechniqueId
import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.domain.TechniqueVersion
import com.normation.cfclerk.domain.TechniqueVersionHelper
import com.normation.cfclerk.domain.TrackerVariableSpec
import com.normation.cfclerk.domain.Variable
import com.normation.cfclerk.domain.VariableSpec
import com.normation.cfclerk.services.impl.GitTechniqueReader
import com.normation.cfclerk.services.impl.SystemVariableSpecServiceImpl
import com.normation.cfclerk.services.impl.TechniqueRepositoryImpl
import com.normation.cfclerk.xmlparsers.SectionSpecParser
import com.normation.cfclerk.xmlparsers.TechniqueParser
import com.normation.cfclerk.xmlparsers.VariableSpecParser
import com.normation.errors.IOResult
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.inventory.domain.AcceptedInventory
import com.normation.inventory.domain.AgentInfo
import com.normation.inventory.domain.AgentType
import com.normation.inventory.domain.AgentType.CfeCommunity
import com.normation.inventory.domain.AgentVersion
import com.normation.inventory.domain.Certificate
import com.normation.inventory.domain.Debian
import com.normation.inventory.domain.EnvironmentVariable
import com.normation.inventory.domain.Linux
import com.normation.inventory.domain.MachineInventory
import com.normation.inventory.domain.MachineUuid
import com.normation.inventory.domain.MemorySize
import com.normation.inventory.domain.NodeId
import com.normation.inventory.domain.NodeInventory
import com.normation.inventory.domain.NodeSummary
import com.normation.inventory.domain.NodeTimezone
import com.normation.inventory.domain.PendingInventory
import com.normation.inventory.domain.PublicKey
import com.normation.inventory.domain.UndefinedKey
import com.normation.inventory.domain.Version
import com.normation.inventory.domain.VirtualMachineType
import com.normation.inventory.domain.VmType._
import com.normation.inventory.domain.Windows
import com.normation.inventory.domain.Windows2012
import com.normation.rudder.domain.Constants
import com.normation.rudder.domain.nodes.MachineInfo
import com.normation.rudder.domain.nodes.Node
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.nodes.NodeGroupCategoryId
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.nodes.NodeGroupUid
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.domain.nodes.NodeState
import com.normation.rudder.domain.policies.ActiveTechniqueCategoryId
import com.normation.rudder.domain.policies.ActiveTechniqueId
import com.normation.rudder.domain.policies.AllTarget
import com.normation.rudder.domain.policies.AllTargetExceptPolicyServers
import com.normation.rudder.domain.policies.Directive
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.DirectiveUid
import com.normation.rudder.domain.policies.FullGroupTarget
import com.normation.rudder.domain.policies.FullOtherTarget
import com.normation.rudder.domain.policies.FullRuleTargetInfo
import com.normation.rudder.domain.policies.GlobalPolicyMode
import com.normation.rudder.domain.policies.GroupTarget
import com.normation.rudder.domain.policies.PolicyMode
import com.normation.rudder.domain.policies.PolicyMode.Enforce
import com.normation.rudder.domain.policies.PolicyModeOverrides
import com.normation.rudder.domain.policies.PolicyServerTarget
import com.normation.rudder.domain.policies.Rule
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.RuleUid
import com.normation.rudder.domain.reports.NodeModeConfig
import com.normation.rudder.git.GitRepositoryProviderImpl
import com.normation.rudder.git.GitRevisionProvider
import com.normation.rudder.git.SimpleGitRevisionProvider
import com.normation.rudder.reports._
import com.normation.rudder.repository.FullActiveTechnique
import com.normation.rudder.repository.FullActiveTechniqueCategory
import com.normation.rudder.repository.FullNodeGroupCategory
import com.normation.rudder.rule.category.RuleCategoryId
import com.normation.rudder.services.servers.AllowedNetwork
import com.normation.rudder.services.servers.PolicyServer
import com.normation.rudder.services.servers.PolicyServerManagementService
import com.normation.rudder.services.servers.PolicyServers
import com.normation.rudder.services.servers.PolicyServersUpdateCommand
import com.normation.rudder.services.servers.RelaySynchronizationMethod.Classic
import com.normation.utils.StringUuidGeneratorImpl
import com.normation.zio._
import java.io.File
import java.nio.file.Files
import java.nio.file.FileSystemNotFoundException
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import net.liftweb.common.Full
import org.apache.commons.io.FileUtils
import org.joda.time.DateTime
import scala.collection.SortedMap
import zio.syntax._

/*
 * This file is a container for testing data that are a little boring to
 * define, like node info, node config, etc. so that their declaration
 * can be share among tests.
 */
object NodeConfigData {

  // recursively copy a directory from classpath to target
  def copyFromClasspath(classpathSource: Path, target: Path):  Unit = {
    try {
      Files.walkFileTree(
        classpathSource,
        new SimpleFileVisitor[Path]() {
          private var currentTarget:                                             Path            = null
          override def preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult = {
            currentTarget = target.resolve(classpathSource.relativize(dir).toString)
            Files.createDirectories(currentTarget)
            FileVisitResult.CONTINUE
          }

          override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
            Files.copy(file, target.resolve(classpathSource.relativize(file).toString), StandardCopyOption.REPLACE_EXISTING)
            FileVisitResult.CONTINUE
          }
        }
      )
    } catch {
      case ex: Exception =>
        println(s"ERROR when trying to copy from classpath '${classpathSource}' into '${target.toString}'")
        throw ex
    }
  }
  /*
   * An utility method to be able to copy `configuration-repository` from any project, either directly when
   * tests are from rudder-core, or by looking in the classpath when they use the test jar `rudder-core-test`
   */
  def copyConfigurationRepository(source: String, dest: File): Unit = {
    // copy from classpath. We assume that le test sources are loaded into classpath here
    val name = source.replaceAll(""".*src/test/resources/""", "")
    // we need to get the reference to the classloader that holds config-repo source. It's
    // the same that the one that holds this class, so start with that.
    val uri  = this.getClass.getClassLoader.getResource(name).toURI
    val path = uri.getScheme match {
      case "jar"  =>
        import scala.jdk.CollectionConverters._
        // yes, pur side effecting
        try {
          FileSystems.getFileSystem(uri)
        } catch {
          case _: FileSystemNotFoundException =>
            FileSystems.newFileSystem(uri, Map(("create", "true")).asJava)
        }
        Paths.get(uri)
      case "file" =>
        Paths.get(uri)
      case other  =>
        throw new RuntimeException(s"Unsupported URI scheme for configuration-repository at: ${uri.toString}")
    }
    copyFromClasspath(path, Paths.get(dest.getAbsolutePath))
  }

  // a logger for timing information
  val logger = org.slf4j.LoggerFactory.getLogger("timing-test").asInstanceOf[ch.qos.logback.classic.Logger]
  // set to trace to see timing
  logger.setLevel(ch.qos.logback.classic.Level.OFF)

  val machine1Accepted =
    MachineInventory(MachineUuid("machine1"), AcceptedInventory, VirtualMachineType(VMWare), Some("machine1"))
  val machine2Pending  = MachineInventory(MachineUuid("machine2"), PendingInventory, VirtualMachineType(VMWare), Some("machine2"))

  // a valid, not used pub key
  // cfengine key hash is: 081cf3aac62624ebbc83be7e23cb104d
  val PUBKEY =
    """-----BEGIN RSA PUBLIC KEY-----
MIIBCAKCAQEAlntroa72gD50MehPoyp6mRS5fzZpsZEHu42vq9KKxbqSsjfUmxnT
Rsi8CDvBt7DApIc7W1g0eJ6AsOfV7CEh3ooiyL/fC9SGATyDg5TjYPJZn3MPUktg
YBzTd1MMyZL6zcLmIpQBH6XHkH7Do/RxFRtaSyicLxiO3H3wapH20TnkUvEpV5Qh
zUkNM8vHZuu3m1FgLrK5NCN7BtoGWgeyVJvBMbWww5hS15IkCRuBkAOK/+h8xe2f
hMQjrt9gW2qJpxZyFoPuMsWFIaX4wrN7Y8ZiN37U2q1G11tv2oQlJTQeiYaUnTX4
z5VEb9yx2KikbWyChM1Akp82AV5BzqE80QIBIw==
-----END RSA PUBLIC KEY-----"""

  // a valid, not used certificate
  // cfengine has is: eec3a3c2cf41b2736b7a0a9dece02142
  // sha256 in hexa: 41d8180612444dc6c280ac9189b8c95594029ba9a0697cd72b1af388b4b94844
  val CERT =
    """-----BEGIN CERTIFICATE-----
MIIFgTCCA2mgAwIBAgIUXpY2lv7l+hkx4mVP324d9O1qJh0wDQYJKoZIhvcNAQEL
BQAwUDEYMBYGA1UEAwwPV0lOLUdOR0RIUFZIVlROMTQwMgYKCZImiZPyLGQBAQwk
YjczZWE0NTEtYzQyYS00MjBkLWE1NDAtNDdiNDQ1ZTU4MzEzMB4XDTE5MDcxMjE2
MTYxMloXDTI3MDkyODE2MTYxMlowUDEYMBYGA1UEAwwPV0lOLUdOR0RIUFZIVlRO
MTQwMgYKCZImiZPyLGQBAQwkYjczZWE0NTEtYzQyYS00MjBkLWE1NDAtNDdiNDQ1
ZTU4MzEzMIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEApW5up//FLSHr
J14iIX7aSTGiVvJ5XTXHXxmx3O1MyFIrNoWoonmR7Wkii+FIcxk8LVajjMaBVP32
ZbfEr1BqljV/XULTO4ivQoqJCfoq/2O5O2Apyh1XJmp8q82CZRz/ZzxKmFAeYgYE
KPbzr/SeLkNvo9zaYZLMGT1Zle8pu7gBWF8DPFg1r77Y1zfSSRTRMSXQk0BVN5uR
2Ru8A53ZI7yDOB73pNXbtV++XdBzbwzBDG24NY80o+bbGSCRgizeDqNBeVjzOzyf
wRp6KFuLrwfksnUcWcwMBz3af6d5uh5hrDII63t30u3eVdmGYUb9oi5JjCOtcJta
r3EhwoeEoeioAxpJebe0Q0OEbEICh4Z/oxGYaG/rn9UZ3Hhw9sdngihiTx/sQ8yg
CGURXr/tQSw1knrmU7Fe1TytfcEhaGhnfjRXhUHXP75ycp4mdp3uRsHSKT7VN95H
lCVxZGUMkE9w8CZQTH2RmL6E5r0VqilktViWmuf31h2DPzg9rvBj+rQpBvgQzUiv
1TzuFzsuLKBp3KMpxHrnIxEMS2ERj1Kr7mAxW3xZVt3dYrw8SdbfozJ4x/d8ciKu
ovN0BBrPIn0wS6v7hT2mMtneEG/xbXZFjL8XqVwIooRCDOhw4UfWb71CdpBNZ8ln
tje4Ri0/C7l5ZJGYJNOpZFBlpDXmMTkCAwEAAaNTMFEwHQYDVR0OBBYEFHJaeKBJ
FcPOMwPGxt8uNESLRJ2YMB8GA1UdIwQYMBaAFHJaeKBJFcPOMwPGxt8uNESLRJ2Y
MA8GA1UdEwEB/wQFMAMBAf8wDQYJKoZIhvcNAQELBQADggIBAAjUW4YmUjYz6K50
uuN/WT+vRtPAKjTcKPi397O0sa1EZDq7gJt2gbYBMyqDFyoivKeec2umXzm7n0o5
yDJ1jwgl0ORxqtCmjzPuwbY9EL8dBycACsr8KorXct2vseC+uxNWnsLbUVs3iTbI
AG5dtXpytZJXioVvR/Hi6DnJ8hP6wQLKJYw3E91jjIdfUBWT1GRzjTHo6VBxlvQd
KFS8JeHMaUJjWiXeI8ZYPjLCDL2Fxs6hlgySBaZSbGySraFwt9l4RDVnUxexMloc
ZECALfJg4fISgZodHXRxVBKEUv71ebSqYfJt8f8LeyfLVK/MY9rmpdV8DGQieaaV
YdhslUYx6vTnk/0Q/LbeHXI2cm2qBP1oyPusydTWWc6TowCLhHqTJ+eAB2X/RjT/
MTe/B3GGKgn1lgB37qF2hVDWtrDvNzE4OGQCNBR/iJDHz5+8MV+4FDT0/7ruTP0B
iMDtuT7Jrk9O/UhAZyG4uyUm+kpcPIevGy2ZVQUgk/zIqLH+R4QrRebXRLrNsKuP
o07htJltXDGDSekSDgK3OnZwLOyTUrz1zMmGqGbqRCwOQAWcZBWLrIjUjM0k9vPy
qYUqf4FphVwX4JqDhm8JSS/et/0431MjMfQC/qauAhPBITgRjlDVEVvGB40aiNLk
ootapja6lKOaIpqp0kmmYN7gFIhp
-----END CERTIFICATE-----"""

  val emptyNodeReportingConfiguration = ReportingConfiguration(None, None, None)

  val id1          = NodeId("node1")
  val hostname1    = "node1.localhost"
  val admin1       = "root"
  val id2          = NodeId("node2")
  val hostname2    = "node2.localhost"
  val rootId       = NodeId("root")
  val rootHostname = "server.rudder.local"
  val rootAdmin    = "root"

  val rootNode = Node(
    rootId,
    "root",
    "",
    NodeState.Enabled,
    false,
    true,
    DateTime.now,
    emptyNodeReportingConfiguration,
    Nil,
    Some(Enforce),
    None
  )
  val root     = NodeInfo(
    rootNode,
    rootHostname,
    Some(MachineInfo(MachineUuid("machine1"), VirtualMachineType(VirtualBox), None, None)),
    Linux(Debian, "Jessie", new Version("7.0"), None, new Version("3.2")),
    List("127.0.0.1", "192.168.0.100"),
    DateTime.now,
    UndefinedKey,
    Seq(AgentInfo(CfeCommunity, Some(AgentVersion("7.0.0")), Certificate(CERT), Set())),
    rootId,
    rootAdmin,
    None,
    None,
    Some(NodeTimezone("UTC", "+00"))
  )

  val node1Node = Node(
    id1,
    "node1",
    "",
    NodeState.Enabled,
    false,
    false,
    DateTime.now,
    emptyNodeReportingConfiguration,
    Nil,
    None,
    None
  )

  val node1 = NodeInfo(
    node1Node,
    hostname1,
    Some(MachineInfo(MachineUuid("machine1"), VirtualMachineType(VirtualBox), None, None)),
    Linux(Debian, "Jessie", new Version("7.0"), None, new Version("3.2")),
    List("192.168.0.10"),
    DateTime.now,
    UndefinedKey,
    Seq(AgentInfo(CfeCommunity, Some(AgentVersion("6.0.0")), PublicKey(PUBKEY), Set())),
    rootId,
    admin1,
    None,
    Some(MemorySize(1460132)),
    None
  )

  val nodeInventory1: NodeInventory = NodeInventory(
    NodeSummary(
      node1.id,
      AcceptedInventory,
      node1.localAdministratorAccountName,
      node1.hostname,
      Linux(Debian, "test machine", new Version("1.0"), None, new Version("3.42")),
      root.id,
      UndefinedKey
    ),
    name = None,
    description = None,
    ram = None,
    swap = None,
    inventoryDate = None,
    receiveDate = None,
    archDescription = None,
    lastLoggedUser = None,
    lastLoggedUserTime = None,
    agents = Seq(),
    serverIps = Seq(),
    machineId = None, // if we want several ids, we would have to ass an "alternate machine" field

    softwareIds = Seq(),
    accounts = Seq(),
    environmentVariables = Seq(EnvironmentVariable("THE_VAR", Some("THE_VAR value!"))),
    processes = Seq(),
    vms = Seq(),
    networks = Seq(),
    fileSystems = Seq()
  )

  // node1 us a relay
  val node2Node = node1Node.copy(id = id2, name = id2.value)
  val node2     = node1.copy(node = node2Node, hostname = hostname2, policyServerId = root.id)

  val dscNode1Node = Node(
    NodeId("node-dsc"),
    "node-dsc",
    "",
    NodeState.Enabled,
    false,
    true, // is draft server

    DateTime.now,
    emptyNodeReportingConfiguration,
    Nil,
    None,
    None
  )

  val dscNode1 = NodeInfo(
    dscNode1Node,
    "node-dsc.localhost",
    Some(MachineInfo(MachineUuid("machine1"), VirtualMachineType(VirtualBox), None, None)),
    Windows(Windows2012, "Windows 2012 youpla boom", new Version("2012"), Some("sp1"), new Version("win-kernel-2012")),
    List("192.168.0.5"),
    DateTime.now,
    UndefinedKey,
    Seq(AgentInfo(AgentType.Dsc, Some(AgentVersion("7.0.0")), Certificate("windows-node-dsc-certificate"), Set())),
    rootId,
    admin1,
    None,
    Some(MemorySize(1460132)),
    None
  )

  val dscInventory1: NodeInventory = NodeInventory(
    NodeSummary(
      dscNode1.id,
      AcceptedInventory,
      dscNode1.localAdministratorAccountName,
      dscNode1.hostname,
      dscNode1.osDetails,
      dscNode1.policyServerId,
      UndefinedKey
    ),
    name = None,
    description = None,
    ram = None,
    swap = None,
    inventoryDate = None,
    receiveDate = None,
    archDescription = None,
    lastLoggedUser = None,
    lastLoggedUserTime = None,
    agents = Seq(),
    serverIps = Seq(),
    machineId = None, // if we want several ids, we would have to ass an "alternate machine" field

    softwareIds = Seq(),
    accounts = Seq(),
    environmentVariables = Seq(EnvironmentVariable("THE_VAR", Some("THE_VAR value!"))),
    processes = Seq(),
    vms = Seq(),
    networks = Seq(),
    fileSystems = Seq()
  )

  val allNodesInfo = Map(rootId -> root, node1.id -> node1, node2.id -> node2)

  val defaultModesConfig = NodeModeConfig(
    globalComplianceMode = GlobalComplianceMode(FullCompliance, 30),
    nodeHeartbeatPeriod = None,
    globalAgentRun = AgentRunInterval(None, 5, 0, 0, 0),
    nodeAgentRun = None,
    globalPolicyMode = GlobalPolicyMode(Enforce, PolicyModeOverrides.Always),
    nodePolicyMode = None
  )

  val rootNodeConfig = NodeConfiguration(
    nodeInfo = root,
    modesConfig = defaultModesConfig,
    runHooks = List(),
    policies = List[Policy](),
    nodeContext = Map[String, Variable](),
    parameters = Set[ParameterForConfiguration](),
    isRootServer = true
  )

  val node1NodeConfig = NodeConfiguration(
    nodeInfo = node1,
    modesConfig = defaultModesConfig,
    runHooks = List(),
    policies = List[Policy](),
    nodeContext = Map[String, Variable](),
    parameters = Set[ParameterForConfiguration](),
    isRootServer = false
  )

  val node2NodeConfig = NodeConfiguration(
    nodeInfo = node2,
    modesConfig = defaultModesConfig,
    runHooks = List(),
    policies = List[Policy](),
    nodeContext = Map[String, Variable](),
    parameters = Set[ParameterForConfiguration](),
    isRootServer = false
  )

  /**
   * Some more nodes
   */
  val nodeIds = (for {
    i <- 0 to 10
  } yield {
    NodeId(s"${i}")
  }).toSet

  def newNode(id: NodeId) =
    Node(id, "", "", NodeState.Enabled, false, false, DateTime.now, ReportingConfiguration(None, None, None), Nil, None, None)

  val nodes = (Set(root, node1, node2) ++ nodeIds.map { id =>
    NodeInfo(
      newNode(id),
      s"Node-${id}",
      None,
      Linux(Debian, "Jessie", new Version("7.0"), None, new Version("3.2")),
      Nil,
      DateTime.now,
      UndefinedKey,
      Seq(AgentInfo(CfeCommunity, None, PublicKey("rsa public key"), Set())),
      NodeId("root"),
      "",
      None,
      None,
      None
    )
  }).map(n => (n.id, n)).toMap

  /**
   *   ************************************************************************
   *                         Some groups
   *   ************************************************************************
   */

  val g0id   = NodeGroupId(NodeGroupUid("0"))
  val g0     = NodeGroup(g0id, "Real nodes", "", Nil, None, false, Set(rootId, node1.id, node2.id), true)
  val g1     = NodeGroup(NodeGroupId(NodeGroupUid("1")), "Empty group", "", Nil, None, false, Set(), true)
  val g2     = NodeGroup(NodeGroupId(NodeGroupUid("2")), "only root", "", Nil, None, false, Set(NodeId("root")), true)
  val g3     = NodeGroup(NodeGroupId(NodeGroupUid("3")), "Even nodes", "", Nil, None, false, nodeIds.filter(_.value.toInt == 2), true)
  val g4     = NodeGroup(NodeGroupId(NodeGroupUid("4")), "Odd nodes", "", Nil, None, false, nodeIds.filter(_.value.toInt != 2), true)
  val g5     = NodeGroup(
    NodeGroupId(NodeGroupUid("5")),
    "Nodes id divided by 3",
    "",
    Nil,
    None,
    false,
    nodeIds.filter(_.value.toInt == 3),
    true
  )
  val g6     = NodeGroup(
    NodeGroupId(NodeGroupUid("6")),
    "Nodes id divided by 5",
    "",
    Nil,
    None,
    false,
    nodeIds.filter(_.value.toInt == 5),
    true
  )
  val groups = Set(g0, g1, g2, g3, g4, g5, g6).map(g => (g.id, g))

  val groupTargets = groups.map { case (id, g) => (GroupTarget(g.id), g) }

  val fullRuleTargetInfos = (groupTargets
    .map(gt => {
      (
        gt._1.groupId,
        FullRuleTargetInfo(
          FullGroupTarget(gt._1, gt._2),
          "",
          "",
          true,
          false
        )
      )
    }))
    .toMap

  val groupLib = FullNodeGroupCategory(
    NodeGroupCategoryId("test_root"),
    "",
    "",
    Nil,
    fullRuleTargetInfos.values.toList
  )

  /**
   *   ************************************************************************
   *                         Some directives
   *   ************************************************************************
   */
  implicit def toATID(s: String):           ActiveTechniqueId = ActiveTechniqueId(s)
  implicit def toTV(s: String):             TechniqueVersion  = TechniqueVersionHelper(s)
  implicit def toTN(s: String):             TechniqueName     = TechniqueName(s)
  implicit def toTID(id: (String, String)): TechniqueId       = TechniqueId(id._1, id._2)
  implicit def toDID(id: String):           DirectiveId       = DirectiveId(DirectiveUid(id), GitVersion.DEFAULT_REV)
  implicit def toRID(id: String):           RuleId            = RuleId(RuleUid(id))
  implicit def toRCID(id: String):          RuleCategoryId    = RuleCategoryId(id)
  val t1   = Technique(("t1", "1.0"), "t1", "t1", Nil, TrackerVariableSpec(None, None), SectionSpec("root"), None)
  val d1   = Directive("d1", "1.0", Map("foo1" -> Seq("bar1")), "d1", "d1", None)
  val d2   = Directive("d2", "1.0", Map("foo2" -> Seq("bar2")), "d2", "d2", Some(PolicyMode.Enforce))
  val d3   = Directive("d3", "1.0", Map("foo3" -> Seq("bar3")), "d3", "d3", Some(PolicyMode.Audit))
  val fat1 = FullActiveTechnique(
    "d1",
    "t1",
    SortedMap(toTV("1.0") -> DateTime.parse("2016-01-01T12:00:00.000+00:00")),
    SortedMap(toTV("1.0") -> t1),
    d1 :: d2 :: Nil
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

class TestTechniqueRepo(
    prefixTestResources: String = "",
    configRepoName:      String, // service you want to override with other

    optGitRevisionProvider: Option[GitRepositoryProviderImpl => GitRevisionProvider] = None
) {

  implicit class PathString(root: String) {
    def /(child: String) = new File(root, child)
  }
  implicit class PathString2(root: File)  {
    def /(child: String) = new File(root, child)
  }

  implicit def stringToRuleUid(s: String): RuleUid = RuleUid(s)

  val t0 = System.currentTimeMillis()

  val abstractRoot = new File("/tmp/test-rudder-config-repo-" + DateTime.now.toString())
  abstractRoot.mkdirs()
  if (System.getProperty("tests.clean.tmp") != "false") {
    Runtime.getRuntime.addShutdownHook(new Thread(new Runnable {
      override def run(): Unit = FileUtils.deleteDirectory(abstractRoot)
    }))
  }

  // config-repo will also be the git root, as a normal rudder
  val configurationRepositoryRoot = abstractRoot / "configuration-repository"
  // initialize config-repo content from our test/resources source

  // Since we want to have only one `configuration-repository`, and it's in `rudder-core`, the source can be in the
  // FS (for tests in rudder-core) or in the test jar for rudder-core.
  NodeConfigData.copyConfigurationRepository(
    prefixTestResources + "src/test/resources/" + configRepoName,
    configurationRepositoryRoot
  )

  val EXPECTED_SHARE = configurationRepositoryRoot / "expected-share"
  val t1             = System.currentTimeMillis()
  NodeConfigData.logger.trace(s"Paths inits             : ${t1 - t0} ms")

  val repo = GitRepositoryProviderImpl.make(configurationRepositoryRoot.getAbsolutePath).runNow
  val t2   = System.currentTimeMillis()
  NodeConfigData.logger.trace(s"Git repo provider       : ${t2 - t1} ms")

  val variableSpecParser        = new VariableSpecParser
  val t2bis                     = System.currentTimeMillis()
  NodeConfigData.logger.trace(s"var Spec Parser        : ${t2bis - t2} ms")
  val systemVariableServiceSpec = new SystemVariableSpecServiceImpl()
  val t3                        = System.currentTimeMillis()
  NodeConfigData.logger.trace(s"System Var Spec service : ${t3 - t2bis} ms")

  val draftParser: TechniqueParser = new TechniqueParser(
    variableSpecParser,
    new SectionSpecParser(variableSpecParser),
    systemVariableServiceSpec
  )
  val t4 = System.currentTimeMillis()
  NodeConfigData.logger.trace(s"Technique parser        : ${t4 - t3} ms")

  val gitRevisionProvider =
    optGitRevisionProvider.map(x => x(repo)).getOrElse(new SimpleGitRevisionProvider("refs/heads/master", repo))
  val reader              = new GitTechniqueReader(
    draftParser,
    gitRevisionProvider,
    repo,
    "metadata.xml",
    "category.xml",
    Some("techniques"),
    "default-directive-names.conf"
  )
  val t5                  = System.currentTimeMillis()
  NodeConfigData.logger.trace(s"Git tech reader         : ${t5 - t4} ms")

  val techniqueRepository = new TechniqueRepositoryImpl(reader, Seq(), new StringUuidGeneratorImpl())
  val t6                  = System.currentTimeMillis()
}

class TestNodeConfiguration(
    prefixTestResources: String = "", // service you want to override with other

    gitRevisionProvider: Option[GitRepositoryProviderImpl => GitRevisionProvider] = None
) {
  implicit class PathString(root: String) {
    def /(child: String) = new File(root, child)
  }
  implicit class PathString2(root: File)  {
    def /(child: String) = new File(root, child)
  }
  implicit def stringToRuleUid(s: String): RuleUid = RuleUid(s)

  // technique repository + expose services & vars

  val testTechRepoEnv = new TestTechniqueRepo(prefixTestResources, "configuration-repository", gitRevisionProvider)
  val abstractRoot    = testTechRepoEnv.abstractRoot

  val configurationRepositoryRoot = testTechRepoEnv.configurationRepositoryRoot
  val EXPECTED_SHARE              = testTechRepoEnv.EXPECTED_SHARE
  val repo                        = testTechRepoEnv.repo
  val variableSpecParser          = testTechRepoEnv.variableSpecParser
  val systemVariableServiceSpec   = testTechRepoEnv.systemVariableServiceSpec
  val draftParser                 = testTechRepoEnv.draftParser
  val reader                      = testTechRepoEnv.reader
  val techniqueRepository         = testTechRepoEnv.techniqueRepository

  import com.normation.rudder.services.policies.NodeConfigData.node1
  import com.normation.rudder.services.policies.NodeConfigData.root
  /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // set up root node configuration
  /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  val t6                     = System.currentTimeMillis()
  val policyServerManagement = new PolicyServerManagementService() {
    override def getPolicyServers(): IOResult[PolicyServers] = {
      PolicyServers(
        PolicyServer(
          Constants.ROOT_POLICY_SERVER_ID,
          List("192.168.12.0/24", "192.168.49.0/24", "127.0.0.1/24").map(s => AllowedNetwork(s, s"name for " + s))
        ),
        Nil
      ).succeed
    }

    override def savePolicyServers(policyServers: PolicyServers):  IOResult[PolicyServers] = ???
    override def updatePolicyServers(
        commands: List[PolicyServersUpdateCommand],
        modId:    ModificationId,
        actor:    EventActor
    ): IOResult[PolicyServers] = ???
    override def deleteRelaySystemObjects(policyServerId: NodeId): IOResult[Unit]          = ???
  }
  val t7                     = System.currentTimeMillis()
  NodeConfigData.logger.trace(s"Policy Server Management: ${t7 - t6} ms")

  val systemVariableService = new SystemVariableServiceImpl(
    systemVariableServiceSpec,
    policyServerManagement,
    toolsFolder = "tools_folder",
    policyDistribCfenginePort = 5309,
    policyDistribHttpsPort = 443,
    sharedFilesFolder = "/var/rudder/configuration-repository/shared-files",
    webdavUser = "rudder",
    webdavPassword = "rudder",
    reportsDbUri = "jdbc:postgresql://localhost:5432/rudder",
    reportsDbUser = "rudder",
    reportsDbPassword = "secret",
    configurationRepository = configurationRepositoryRoot.getAbsolutePath,
    serverVersion = "7.0.0", // denybadclocks is runtime properties

    getDenyBadClocks = () => Full(true),
    getSyncMethod = () => Full(Classic),
    getSyncPromises = () => Full(false),
    getSyncSharedFiles = () => Full(false), // TTLs are runtime properties too

    getModifiedFilesTtl = () => Full(30),
    getCfengineOutputsTtl = () => Full(7),
    getSendMetrics = () => Full(None),
    getReportProtocolDefault = () => Full(AgentReportingHTTPS)
  )

  val t8 = System.currentTimeMillis()
  NodeConfigData.logger.trace(s"System variable Service: ${t8 - t7} ms")

  // a test node - CFEngine
  val nodeId  = NodeId("c8813416-316f-4307-9b6a-ca9c109a9fb0")
  val cfeNode = node1.copy(node = node1.node.copy(id = nodeId, name = nodeId.value))

  val allNodesInfo_rootOnly = Map(root.id -> root)
  val allNodesInfo_cfeNode  = Map(root.id -> root, cfeNode.id -> cfeNode)

  // the group lib
  val emptyGroupLib = FullNodeGroupCategory(
    NodeGroupCategoryId("/"),
    "/",
    "root of group categories",
    List(),
    List(),
    true
  )

  val groupLib = emptyGroupLib.copy(
    targetInfos = List(
      FullRuleTargetInfo(
        FullGroupTarget(
          GroupTarget(NodeGroupId(NodeGroupUid("a-group-for-root-only"))),
          NodeGroup(
            NodeGroupId(NodeGroupUid("a-group-for-root-only")),
            "Serveurs [€ðŋ] cassés",
            "Liste de l'ensemble de serveurs cassés à réparer",
            Nil,
            None,
            true,
            Set(NodeId("root")),
            true,
            false
          )
        ),
        "Serveurs [€ðŋ] cassés",
        "Liste de l'ensemble de serveurs cassés à réparer",
        true,
        false
      ),
      FullRuleTargetInfo(
        FullOtherTarget(PolicyServerTarget(NodeId("root"))),
        "special:policyServer_root",
        "The root policy server",
        true,
        true
      ),
      FullRuleTargetInfo(
        FullOtherTarget(AllTargetExceptPolicyServers),
        "special:all_exceptPolicyServers",
        "All groups without policy servers",
        true,
        true
      ),
      FullRuleTargetInfo(
        FullOtherTarget(AllTarget),
        "special:all",
        "All nodes",
        true,
        true
      )
    )
  )

  val globalAgentRun       = AgentRunInterval(None, 5, 1, 0, 4)
  val globalComplianceMode = GlobalComplianceMode(FullCompliance, 15)

  val globalSystemVariables = systemVariableService
    .getGlobalSystemVariables(globalAgentRun)
    .openOrThrowException("I should get global system variable in test!")

  val t9 = System.currentTimeMillis()
  NodeConfigData.logger.trace(s"Nodes & groupes         : ${t9 - t8} ms")

  //
  // root has 4 system directive, let give them some variables
  //
  implicit class UnsafeGet(repo: TechniqueRepositoryImpl) {
    def unsafeGet(id: TechniqueId) =
      repo.get(id).getOrElse(throw new RuntimeException(s"Bad init for test: technique '${id.serialize}' not found"))
  }

  def draft(
      id:            PolicyId,
      ruleName:      String,
      directiveName: String,
      technique:     Technique,
      variableMap:   Map[ComponentId, Variable],
      system:        Boolean = true,
      policyMode:    Option[PolicyMode] = None
  ) = {
    BoundPolicyDraft(
      id,
      ruleName,
      directiveName,
      technique,
      DateTime.now,
      variableMap,
      variableMap,
      technique.trackerVariableSpec.toVariable(Seq(id.getReportId)),
      0,
      system,
      policyMode,
      BundleOrder(ruleName),
      BundleOrder(directiveName),
      Set()
    )
  }

  /*
   * NOTICE:
   * Changes here are likely to need to be replicated in rudder-rest: MockService.scala,
   * in class MockDirectives
   */

  val commonTechnique = techniqueRepository.unsafeGet(TechniqueId(TechniqueName("common"), TechniqueVersionHelper("1.0")))
  def commonVariables(nodeId: NodeId, allNodeInfos: Map[NodeId, NodeInfo]): Map[ComponentId, Variable] = {
    val spec = commonTechnique.getAllVariableSpecs.map(s => (s.name, s)).toMap
    Seq(
      spec("OWNER").toVariable(Seq(allNodeInfos(nodeId).localAdministratorAccountName)),
      spec("UUID").toVariable(Seq(nodeId.value)),
      spec("POLICYSERVER_ID").toVariable(Seq(allNodeInfos(nodeId).policyServerId.value)),
      spec("POLICYSERVER_ADMIN").toVariable(Seq(allNodeInfos(allNodeInfos(nodeId).policyServerId).localAdministratorAccountName)),
      spec("ALLOWEDNETWORK").toVariable(Seq(""))
    ).map(v => (ComponentId(v.spec.name, Nil, None), v)).toMap // None because no reportId for old var
  }

  val commonDirective = Directive(
    DirectiveId(DirectiveUid("common-hasPolicyServer-root"), GitVersion.DEFAULT_REV),
    TechniqueVersionHelper("1.0"),
    Map(
      ("OWNER", Seq("${rudder.node.admin}")),
      ("UUID", Seq("${rudder.node.id}")),
      ("POLICYSERVER_ID", Seq("${rudder.node.id}")),
      ("POLICYSERVER_ADMIN", Seq("${rudder.node.admin}"))
    ),
    "common-root",
    "",
    None,
    "",
    5,
    true,
    true
  )

  def common(nodeId: NodeId, allNodeInfos: Map[NodeId, NodeInfo]) = {
    val id = PolicyId(RuleId("hasPolicyServer-root"), commonDirective.id, TechniqueVersionHelper("1.0"))
    draft(
      id,
      "Rudder system policy: basic setup (common)",
      "Common",
      commonTechnique,
      commonVariables(nodeId, allNodeInfos)
    )
  }

  val archiveTechnique =
    techniqueRepository.unsafeGet(TechniqueId(TechniqueName("test_import_export_archive"), TechniqueVersionHelper("1.0")))
  val archiveDirective = Directive(
    DirectiveId(DirectiveUid("test_import_export_archive_directive"), GitVersion.DEFAULT_REV),
    TechniqueVersionHelper("1.0"),
    Map(),
    "test_import_export_archive_directive",
    "",
    None,
    "",
    5,
    true,
    false
  )

  // we have one rule with several system technique for root server config

  // get variable's values based on the kind of spec for that: if the values are provided, use them.
  def getVariables(
      techiqueDebugId: String,
      spec:            Map[String, VariableSpec],
      variables:       List[String]
  ): Map[ComponentId, Variable] = {
    variables
      .map(name => {
        spec
          .get(name)
          .getOrElse(
            throw new RuntimeException(s"Missing variable spec '${name}' in technique ${techiqueDebugId} in test")
          ) match {
          case p: PredefinedValuesVariableSpec => p.toVariable(p.providedValues._1 :: p.providedValues._2.toList)
          case s => s.toVariable(Seq(s"value for '${name}'"))
        }
      })
      .map(v => (ComponentId(v.spec.name, Nil, None), v))
      .toMap
  }

  def simpleServerPolicy(name: String, variables: List[String] = List()) = {
    val technique = techniqueRepository.unsafeGet(TechniqueId(TechniqueName(s"${name}"), TechniqueVersionHelper("1.0")))
    val spec      = technique.getAllVariableSpecs.map(s => (s.name, s)).toMap
    val vars      = getVariables(technique.id.serialize, spec, variables)
    val policy    = {
      val id = PolicyId(RuleId("policy-server-root"), DirectiveId(DirectiveUid(s"${name}-root")), TechniqueVersionHelper("1.0"))
      draft(
        id,
        "Rule for policy server root",
        s"Server ${name} - root",
        technique,
        vars
      )
    }
    (technique, policy)
  }

  val (serverCommonTechnique, serverCommon)         = simpleServerPolicy("server-common")
  val apacheVariables                               = List(
    "expectedReportKey Apache service",
    "expectedReportKey Apache configuration",
    "expectedReportKey Configure apache certificate"
  )
  val (serverApacheTechnique, serverApache)         = simpleServerPolicy("rudder-service-apache", apacheVariables)
  val postgresqlVariables                           = List(
    "expectedReportKey Postgresql service",
    "expectedReportKey Postgresql configuration"
  )
  val (serverPostgresqlTechnique, serverPostgresql) = simpleServerPolicy("rudder-service-postgresql", postgresqlVariables)
  val relaydVariables                               = List(
    "expectedReportKey Rudder-relayd service"
  )
  val (serverRelaydTechnique, serverRelayd)         = simpleServerPolicy("rudder-service-relayd", relaydVariables)
  val slapdVariables                                = List(
    "expectedReportKey Rudder slapd service",
    "expectedReportKey Rudder slapd configuration"
  )
  val (serverSlapdTechnique, serverSlapd)           = simpleServerPolicy("rudder-service-slapd", slapdVariables)
  val webappVariables                               = List(
    "expectedReportKey Rudder-jetty service",
    "expectedReportKey Check configuration-repository",
    "expectedReportKey Check webapp configuration"
  )
  val (serverWebappTechnique, serverWebapp)         = simpleServerPolicy("rudder-service-webapp", webappVariables)

  val inventoryTechnique = techniqueRepository.unsafeGet(TechniqueId(TechniqueName("inventory"), TechniqueVersionHelper("1.0")))
  val inventoryVariables: Map[ComponentId, Variable] = {
    val spec = inventoryTechnique.getAllVariableSpecs.map(s => (s.name, s)).toMap
    getVariables(inventoryTechnique.id.serialize, spec, List("expectedReportKey Inventory"))
  }

  val inventoryAll = {
    val id = PolicyId(RuleId("inventory-all"), DirectiveId(DirectiveUid("inventory-all")), TechniqueVersionHelper("1.0"))
    draft(
      id,
      "Rudder system policy: daily inventory",
      "Inventory",
      inventoryTechnique,
      inventoryVariables
    )
  }

  val allRootPolicies = List(serverCommon, serverApache, serverPostgresql, serverRelayd, serverSlapd, serverWebapp, inventoryAll)

  //
  // 4 user directives: clock management, rpm, package, a multi-policiy: fileTemplate, and a ncf one: Create_file
  //
  lazy val clockTechnique =
    techniqueRepository.unsafeGet(TechniqueId(TechniqueName("clockConfiguration"), TechniqueVersionHelper("3.0")))
  lazy val clockVariables: Map[ComponentId, Variable] = {
    val spec = clockTechnique.getAllVariableSpecs.map(s => (s.name, s)).toMap
    Seq(
      spec("CLOCK_FQDNNTP").toVariable(Seq("true")),
      spec("CLOCK_HWSYNC_ENABLE").toVariable(Seq("true")),
      spec("CLOCK_NTPSERVERS").toVariable(Seq("${rudder.param.ntpserver}")),
      spec("CLOCK_SYNCSCHED").toVariable(Seq("240")),
      spec("CLOCK_TIMEZONE").toVariable(Seq("dontchange"))
    ).map(v => (ComponentId(v.spec.name, Nil, None), v)).toMap
  }
  lazy val clock = {
    val id = PolicyId(RuleId("rule1"), DirectiveId(DirectiveUid("directive1+rev1")), TechniqueVersionHelper("1.0+rev2"))
    draft(
      id,
      "10. Global configuration for all nodes",
      "10. Clock Configuration",
      clockTechnique,
      clockVariables,
      false,
      Some(PolicyMode.Enforce)
    )
  }
  /*
   * A RPM Policy, which comes from 2 directives.
   * The second one contributes two packages.
   * It had a different value for the CHECK_INTERVAL, but
   * that variable is unique, so it get the first draft value all along.
   */

  lazy val rpmTechnique =
    techniqueRepository.unsafeGet(TechniqueId(TechniqueName("rpmPackageInstallation"), TechniqueVersionHelper("7.0")))
  lazy val rpmVariables: Map[ComponentId, Variable] = {
    val spec = rpmTechnique.getAllVariableSpecs.map(s => (s.name, s)).toMap
    Seq(
      spec("RPM_PACKAGE_CHECK_INTERVAL").toVariable(Seq("5")),
      spec("RPM_PACKAGE_POST_HOOK_COMMAND").toVariable(Seq("", "", "")),
      spec("RPM_PACKAGE_POST_HOOK_RUN").toVariable(Seq("false", "false", "false")),
      spec("RPM_PACKAGE_REDACTION").toVariable(Seq("add", "add", "add")),
      spec("RPM_PACKAGE_REDLIST").toVariable(Seq("plop", "foo", "bar")),
      spec("RPM_PACKAGE_VERSION").toVariable(Seq("", "", "")),
      spec("RPM_PACKAGE_VERSION_CRITERION").toVariable(Seq("==", "==", "==")),
      spec("RPM_PACKAGE_VERSION_DEFINITION").toVariable(Seq("default", "default", "default"))
    ).map(v => (ComponentId(v.spec.name, Nil, None), v)).toMap
  }
  def rpmDirective(id: String, pkg: String) = Directive(
    DirectiveId(DirectiveUid(id), GitVersion.DEFAULT_REV),
    TechniqueVersionHelper("7.0"),
    Map(
      ("RPM_PACKAGE_CHECK_INTERVAL", Seq("5")),
      ("RPM_PACKAGE_POST_HOOK_COMMAND", Seq("")),
      ("RPM_PACKAGE_POST_HOOK_RUN", Seq("false")),
      ("RPM_PACKAGE_REDACTION", Seq("add")),
      ("RPM_PACKAGE_REDLIST", Seq(pkg)),
      ("RPM_PACKAGE_VERSION", Seq("")),
      ("RPM_PACKAGE_VERSION_CRITERION", Seq("==")),
      ("RPM_PACKAGE_VERSION_DEFINITION", Seq("default"))
    ),
    id,
    "",
    None,
    ""
  )
  lazy val rpm = {
    val id = PolicyId(RuleId("rule2"), DirectiveId(DirectiveUid("directive2")), TechniqueVersionHelper("1.0"))
    draft(
      id,
      "50. Deploy PLOP STACK",
      "20. Install PLOP STACK main rpm",
      rpmTechnique,
      rpmVariables,
      false,
      Some(PolicyMode.Audit)
    )
  }

  lazy val pkgTechnique =
    techniqueRepository.unsafeGet(TechniqueId(TechniqueName("packageManagement"), TechniqueVersionHelper("1.0")))
  lazy val pkgVariables: Map[ComponentId, Variable] = {
    val spec = pkgTechnique.getAllVariableSpecs.map(s => (s.name, s)).toMap
    Seq(
      spec("PACKAGE_LIST").toVariable(Seq("htop")),
      spec("PACKAGE_STATE").toVariable(Seq("present")),
      spec("PACKAGE_VERSION").toVariable(Seq("latest")),
      spec("PACKAGE_VERSION_SPECIFIC").toVariable(Seq("")),
      spec("PACKAGE_ARCHITECTURE").toVariable(Seq("default")),
      spec("PACKAGE_ARCHITECTURE_SPECIFIC").toVariable(Seq("")),
      spec("PACKAGE_MANAGER").toVariable(Seq("default")),
      spec("PACKAGE_POST_HOOK_COMMAND").toVariable(Seq(""))
    ).map(v => (ComponentId(v.spec.name, Nil, None), v)).toMap
  }
  lazy val pkg = {
    val id = PolicyId(
      RuleId("ff44fb97-b65e-43c4-b8c2-0df8d5e8549f"),
      DirectiveId(DirectiveUid("16617aa8-1f02-4e4a-87b6-d0bcdfb4019f")),
      TechniqueVersionHelper("1.0")
    )
    draft(
      id,
      "60-rule-technique-std-lib",
      "Package management.",
      pkgTechnique,
      pkgVariables,
      false,
      Some(PolicyMode.Enforce)
    )
  }

  lazy val fileTemplateTechnique =
    techniqueRepository.unsafeGet(TechniqueId(TechniqueName("fileTemplate"), TechniqueVersionHelper("1.0")))
  lazy val fileTemplateVariables1: Map[ComponentId, Variable] = {
    val spec = fileTemplateTechnique.getAllVariableSpecs.map(s => (s.name, s)).toMap
    Seq(
      spec("FILE_TEMPLATE_RAW_OR_NOT").toVariable(Seq("Raw")),
      spec("FILE_TEMPLATE_TEMPLATE").toVariable(Seq("")),
      spec("FILE_TEMPLATE_RAW_TEMPLATE").toVariable(Seq("some content")),
      spec("FILE_TEMPLATE_AGENT_DESTINATION_PATH").toVariable(Seq("/tmp/destination.txt")),
      spec("FILE_TEMPLATE_TEMPLATE_TYPE").toVariable(Seq("mustache")),
      spec("FILE_TEMPLATE_OWNER").toVariable(Seq("root")),
      spec("FILE_TEMPLATE_GROUP_OWNER").toVariable(Seq("root")),
      spec("FILE_TEMPLATE_PERMISSIONS").toVariable(Seq("700")),
      spec("FILE_TEMPLATE_PERSISTENT_POST_HOOK").toVariable(Seq("false")),
      spec("FILE_TEMPLATE_TEMPLATE_POST_HOOK_COMMAND").toVariable(Seq(""))
    ).map(v => (ComponentId(v.spec.name, Nil, None), v)).toMap
  }
  lazy val fileTemplate1 = {
    val id = PolicyId(
      RuleId("ff44fb97-b65e-43c4-b8c2-0df8d5e8549f"),
      DirectiveId(DirectiveUid("e9a1a909-2490-4fc9-95c3-9d0aa01717c9")),
      TechniqueVersionHelper("1.0")
    )
    draft(
      id,
      "60-rule-technique-std-lib",
      "10-File template 1",
      fileTemplateTechnique,
      fileTemplateVariables1,
      false,
      Some(PolicyMode.Enforce)
    )
  }
  lazy val fileTemplateVariables2 = {
    val spec = fileTemplateTechnique.getAllVariableSpecs.map(s => (s.name, s)).toMap
    Seq(
      spec("FILE_TEMPLATE_RAW_OR_NOT").toVariable(Seq("Raw")),
      spec("FILE_TEMPLATE_TEMPLATE").toVariable(Seq("")),
      spec("FILE_TEMPLATE_RAW_TEMPLATE").toVariable(Seq("some content")),
      spec("FILE_TEMPLATE_AGENT_DESTINATION_PATH").toVariable(Seq("/tmp/other-destination.txt")),
      spec("FILE_TEMPLATE_TEMPLATE_TYPE").toVariable(Seq("mustache")),
      spec("FILE_TEMPLATE_OWNER").toVariable(Seq("root")),
      spec("FILE_TEMPLATE_GROUP_OWNER").toVariable(Seq("root")),
      spec("FILE_TEMPLATE_PERMISSIONS").toVariable(Seq("777")),
      spec("FILE_TEMPLATE_PERSISTENT_POST_HOOK").toVariable(Seq("true")),
      spec("FILE_TEMPLATE_TEMPLATE_POST_HOOK_COMMAND").toVariable(Seq("/bin/true"))
    ).map(v => (v.spec.name, v)).toMap
  }
  lazy val fileTemplate2 = {
    val id = PolicyId(
      RuleId("ff44fb97-b65e-43c4-b8c2-0df8d5e8549f"),
      DirectiveId(DirectiveUid("99f4ef91-537b-4e03-97bc-e65b447514cc")),
      TechniqueVersionHelper("1.0")
    )
    draft(
      id,
      "60-rule-technique-std-lib",
      "20-File template 2",
      fileTemplateTechnique,
      fileTemplateVariables2.map(a => (ComponentId(a._1, Nil, None), a._2)),
      false,
      Some(PolicyMode.Enforce)
    )
  }

  // fileTemplate3 is a copy of fileTemplate2 but provided by an other rule
  lazy val fileTemplate3 = {
    val id = PolicyId(
      RuleId("ff44fb97-b65e-43c4-b8c2-000000000000"),
      DirectiveId(DirectiveUid("99f4ef91-537b-4e03-97bc-e65b447514cc")),
      TechniqueVersionHelper("1.0")
    )
    draft(
      id,
      "99-rule-technique-std-lib",
      "20-File template 2",
      fileTemplateTechnique,
      fileTemplateVariables2.map(a => (ComponentId(a._1, Nil, None), a._2)),
      false,
      Some(PolicyMode.Enforce)
    )
  }

  val ncf1Technique = techniqueRepository.unsafeGet(TechniqueId(TechniqueName("Create_file"), TechniqueVersionHelper("1.0")))
  val ncf1Variables = {
    val spec = ncf1Technique.getAllVariableSpecs.map(s => (s.name, s)).toMap
    Seq(
      spec("expectedReportKey Directory create").toVariable(Seq("directory_create_/tmp/foo")),
      spec("expectedReportKey File create").toVariable(Seq("file_create_/tmp/foo/bar")),
      spec("1AAACD71-C2D5-482C-BCFF-5EEE6F8DA9C2").toVariable(Seq("\"foo"))
    ).map(v => (v.spec.name, v)).toMap
  }
  val ncf1          = {
    val id = PolicyId(
      RuleId("208716db-2675-43b9-ab57-bfbab84346aa"),
      DirectiveId(DirectiveUid("16d86a56-93ef-49aa-86b7-0d10102e4ea9")),
      TechniqueVersionHelper("1.0")
    )
    draft(
      id,
      "50-rule-technique-ncf",
      "Create a file",
      ncf1Technique,
      ncf1Variables.map(a => (ComponentId(a._1, Nil, Some(s"reportId_${a._1}")), a._2)),
      false,
      Some(PolicyMode.Enforce)
    )
  }

  // test ticket 18205
  lazy val test18205Technique =
    techniqueRepository.unsafeGet(TechniqueId(TechniqueName("test_18205"), TechniqueVersionHelper("1.0")))
  lazy val test18205          = {
    val id = PolicyId(RuleId("rule1"), DirectiveId(DirectiveUid("directive1")), TechniqueVersionHelper("1.0"))
    draft(
      id,
      "10. Global configuration for all nodes",
      "10. test18205",
      test18205Technique,
      Map(),
      false,
      Some(PolicyMode.Enforce)
    )
  }

  /**
    * test for multiple generation
    */
  val DIRECTIVE_NAME_COPY_GIT_FILE = "directive-copyGitFile"
  lazy val copyGitFileTechnique    =
    techniqueRepository.unsafeGet(TechniqueId(TechniqueName("copyGitFile"), TechniqueVersionHelper("2.3")))
  def copyGitFileVariable(i: Int)  = {
    val spec = copyGitFileTechnique.getAllVariableSpecs.map(s => (s.name, s)).toMap
    Seq(
      spec("COPYFILE_NAME").toVariable(Seq("file_name_" + i + ".json")),
      spec("COPYFILE_EXCLUDE_INCLUDE_OPTION").toVariable(Seq("none")),
      spec("COPYFILE_EXCLUDE_INCLUDE").toVariable(Seq("")),
      spec("COPYFILE_DESTINATION").toVariable(Seq("/tmp/destination_" + i + ".json")),
      spec("COPYFILE_RECURSION").toVariable(Seq(s"${i % 2}")),
      spec("COPYFILE_PURGE").toVariable(Seq("false")),
      spec("COPYFILE_COMPARE_METHOD").toVariable(Seq("mtime")),
      spec("COPYFILE_OWNER").toVariable(Seq("root")),
      spec("COPYFILE_GROUP").toVariable(Seq("root")),
      spec("COPYFILE_PERM").toVariable(Seq("644")),
      spec("COPYFILE_SUID").toVariable(Seq("false")),
      spec("COPYFILE_SGID").toVariable(Seq("false")),
      spec("COPYFILE_STICKY_FOLDER").toVariable(Seq("false")),
      spec("COPYFILE_POST_HOOK_RUN").toVariable(Seq("true")),
      spec("COPYFILE_POST_HOOK_COMMAND").toVariable(Seq("/bin/echo Value_" + i + ".json"))
    ).map(v => (v.spec.name, v)).toMap
  }

  def copyGitFileDirectives(i: Int) = {
    val id = PolicyId(
      RuleId("rulecopyGitFile"),
      DirectiveId(DirectiveUid(DIRECTIVE_NAME_COPY_GIT_FILE + i)),
      TechniqueVersionHelper("2.3")
    )
    draft(
      id,
      "90-copy-git-file",
      "Copy git file",
      copyGitFileTechnique,
      copyGitFileVariable(i).map(a => (ComponentId(a._1, Nil, None), a._2)),
      false,
      Some(PolicyMode.Enforce)
    )
  }

  val t10 = System.currentTimeMillis()
  NodeConfigData.logger.trace(s"Get techniques & directives: ${t10 - t9} ms")

  /**
    * create the 500 expected directives files for copygitfile
    */
  def createCopyGitFileDirectories(nodeName: String, listIds: Seq[Int]): Unit = {
    val dest_root_path = EXPECTED_SHARE.getPath + "/" + nodeName + "/rules/cfengine-community/copyGitFile"
    val source_tml     = EXPECTED_SHARE.getPath + "/" + "copyFileFromSharedFolder.cf"

    val directiveBasePath = dest_root_path + "/2_3_" + DIRECTIVE_NAME_COPY_GIT_FILE.replace("-", "_")

    Files.createDirectory(Paths.get(dest_root_path))

    // read the source template
    val tml = Files.lines(Paths.get(source_tml)).toArray.mkString("\n")

    // replace all tokens
    for {
      id       <- listIds
      replaced  = tml.replaceAll("TOKEN", id.toString)
      recursion = replaced.replaceAll("RECURSION", (id % 2).toString)
    } yield {
      Files.createDirectory(Paths.get(directiveBasePath + id))
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
  lazy val gvdTechnique  =
    techniqueRepository.unsafeGet(TechniqueId(TechniqueName("genericVariableDefinition"), TechniqueVersionHelper("2.0")))
  lazy val gvdVariables1 = {
    val spec = gvdTechnique.getAllVariableSpecs.map(s => (s.name, s)).toMap
    Seq(
      spec("GENERIC_VARIABLE_NAME").toVariable(Seq("var1")),
      spec("GENERIC_VARIABLE_CONTENT").toVariable(Seq("value from gvd #1 should be first")) // the one to override
    ).map(v => (v.spec.name, v)).toMap
  }
  lazy val gvd1          = {
    val id = PolicyId(RuleId("rule1"), DirectiveId(DirectiveUid("directive1")), TechniqueVersionHelper("1.0"))
    draft(
      id,
      "10. Global configuration for all nodes",
      "99. Generic Variable Def #1",
      gvdTechnique,
      gvdVariables1.map(a => (ComponentId(a._1, Nil, None), a._2)),
      false,
      Some(PolicyMode.Enforce)
    ).copy(
      priority = 0 // we want to make sure this one will be merged in first position
    )
  }
  lazy val gvdVariables2 = {
    val spec = gvdTechnique.getAllVariableSpecs.map(s => (s.name, s)).toMap
    Seq(
      spec("GENERIC_VARIABLE_NAME").toVariable(Seq("var1")),
      spec("GENERIC_VARIABLE_CONTENT").toVariable(Seq("value from gvd #2 should be last")) // the one to use for override
    ).map(v => (v.spec.name, v)).toMap
  }
  lazy val gvd2          = {
    val id = PolicyId(RuleId("rule1"), DirectiveId(DirectiveUid("directive2")), TechniqueVersionHelper("1.0"))
    draft(
      id,
      "10. Global configuration for all nodes",
      "00. Generic Variable Def #2", // sort name comes before sort name of directive 1

      gvdTechnique,
      gvdVariables2.map(a => (ComponentId(a._1, Nil, None), a._2)),
      false,
      Some(PolicyMode.Enforce)
    ).copy(
      priority = 10 // we want to make sure this one will be merged in last position
    )
  }
}
