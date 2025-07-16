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

import ch.qos.logback.classic.Logger
import com.normation.GitVersion
import com.normation.cfclerk.domain.*
import com.normation.cfclerk.services.impl.GitTechniqueReader
import com.normation.cfclerk.services.impl.SystemVariableSpecServiceImpl
import com.normation.cfclerk.services.impl.TechniqueRepositoryImpl
import com.normation.cfclerk.xmlparsers.SectionSpecParser
import com.normation.cfclerk.xmlparsers.TechniqueParser
import com.normation.cfclerk.xmlparsers.VariableSpecParser
import com.normation.errors.IOResult
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.inventory.domain.*
import com.normation.inventory.domain.AgentType.*
import com.normation.inventory.domain.VmType.*
import com.normation.rudder.domain.Constants
import com.normation.rudder.domain.nodes.*
import com.normation.rudder.domain.policies.*
import com.normation.rudder.domain.policies.PolicyMode.*
import com.normation.rudder.domain.reports.NodeModeConfig
import com.normation.rudder.facts.nodes.CoreNodeFact
import com.normation.rudder.facts.nodes.IpAddress
import com.normation.rudder.facts.nodes.MinimalNodeFactInterface.toNode
import com.normation.rudder.facts.nodes.RudderAgent
import com.normation.rudder.facts.nodes.RudderSettings
import com.normation.rudder.git.GitRepositoryProviderImpl
import com.normation.rudder.git.GitRevisionProvider
import com.normation.rudder.git.SimpleGitRevisionProvider
import com.normation.rudder.reports.*
import com.normation.rudder.repository.FullActiveTechnique
import com.normation.rudder.repository.FullActiveTechniqueCategory
import com.normation.rudder.repository.FullNodeGroupCategory
import com.normation.rudder.rule.category.RuleCategoryId
import com.normation.rudder.services.policies.NodeConfigData.fact1
import com.normation.rudder.services.policies.NodeConfigData.factRoot
import com.normation.rudder.services.servers.AllowedNetwork
import com.normation.rudder.services.servers.InstanceId
import com.normation.rudder.services.servers.InstanceIdService
import com.normation.rudder.services.servers.PolicyServer
import com.normation.rudder.services.servers.PolicyServerManagementService
import com.normation.rudder.services.servers.PolicyServers
import com.normation.rudder.services.servers.PolicyServersUpdateCommand
import com.normation.rudder.services.servers.RelaySynchronizationMethod.Classic
import com.normation.utils.StringUuidGeneratorImpl
import com.normation.zio.*
import com.softwaremill.quicklens.*
import java.io.File
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import net.liftweb.common.Full
import org.apache.commons.io.FileUtils
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import scala.collection.MapView
import scala.collection.SortedMap
import zio.Chunk
import zio.syntax.*

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
        import scala.jdk.CollectionConverters.*
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
  val logger: Logger = org.slf4j.LoggerFactory.getLogger("timing-test").asInstanceOf[ch.qos.logback.classic.Logger]
  // set to trace to see timing
  logger.setLevel(ch.qos.logback.classic.Level.OFF)

  val machine1Accepted: MachineInventory =
    MachineInventory(MachineUuid("machine1"), AcceptedInventory, VirtualMachineType(VMWare), Some("machine1"))
  val machine2Pending:  MachineInventory =
    MachineInventory(MachineUuid("machine2"), PendingInventory, VirtualMachineType(VMWare), Some("machine2"))

  // certificate with subject "root"
  val ROOT_CERT = """-----BEGIN CERTIFICATE-----
                    |MIIFSzCCAzOgAwIBAgIUQyKUmUkU8F/ua6sp8CV9d1UMT60wDQYJKoZIhvcNAQEL
                    |BQAwFjEUMBIGCgmSJomT8ixkAQEMBHJvb3QwHhcNMjQxMTAyMTYyMDI1WhcNMzQx
                    |MDMxMTYyMDI1WjAWMRQwEgYKCZImiZPyLGQBAQwEcm9vdDCCAiIwDQYJKoZIhvcN
                    |AQEBBQADggIPADCCAgoCggIBANe05osdzZ4McYtyAe320mexC+chYPEcQ0j6Jt4g
                    |YnHK0PUch/2Rrjn0NBhcP+VeQK1mLCorSknNuGQdnzMBpLvXrB5o79EULJWtqvEZ
                    |TVCTxYfab+JTtK7NakkqPd8qeZHu3pJtDCpyonbKL8uRems/e0Xt0u9I/euT9Gwr
                    |7A/cH7VJc2XheotK/cIW9xKSlTwqakeGxywdjJ7Sf7Yt55hBRLRQowL2uqrl45hq
                    |389TvMJt91o+iPLRaln8xHa45COI0c2NeJ+JB+0dRpLDXCYJj+4GjV8T5tGn6hw0
                    |35p7P1g0QWzcGS5Grw7f0punLBAld0+5XqHjjIAvTinB9q5KlVCXCq5k0V224BRL
                    |VyfEhIIhHwZhtL4pNyZbITNdQb6ICWdDXtrl0AcyuaKboOE7wVcIYj5dDA/HHjTE
                    |JjI1i5GnYT3s6L0KiPmuf2aAedcM6ZVAGFw8VKtQ+D5eZgljMK5u+Exw+S01D5Hs
                    |rMPtrzJQzyDRLyxOHdnTpQ2G957eJtH988HQ2a/e1Bw9+PiSIi0MPP6XUbtsaHh8
                    |VOTPDqJDKzjOXrB8A6tSHVcX3PZGCcYztuta4HxCsqt+Dw2zWw0gV3VcEh3Cm7vM
                    |YUqHSKGVqP7Hrx7bma6xud9lh3nSBlPvKp6SgPGSZmg3Repz+2REk+cX9MM+Q+SS
                    |8fFvAgMBAAGjgZAwgY0wDAYDVR0TBAUwAwEB/zAdBgNVHQ4EFgQUBALyYy06Lx6I
                    |WmPx6LtWgdti9BswUQYDVR0jBEowSIAUBALyYy06Lx6IWmPx6LtWgdti9BuhGqQY
                    |MBYxFDASBgoJkiaJk/IsZAEBDARyb290ghRDIpSZSRTwX+5rqynwJX13VQxPrTAL
                    |BgNVHQ8EBAMCArwwDQYJKoZIhvcNAQELBQADggIBAGPSzHyD3R0CpbGLZT8v4kgq
                    |VmOnh9QAwX4HyjyGRJknYGZF29Ko6I1HEt8QZ6D8GVPFlmRFBB77VKD95ytXX/ec
                    |jKPV3RWPTGrOCbfP95Z0EBKS/wA0gUOVcKRVQls7G6MQs/XagPtD5YgI7fzUBjeC
                    |mJprmw+RbS46IVAZL6JF3mSIdTo9TskFyhCGwbZ40jZLASDKRl352GCFi1u2SDTV
                    |gWr+Td52hkYYzS33OxrKwXskoXlZzlRcI9/Ra+8Ue+2TVMeY8eGqYrS0dgCcOsRB
                    |GtWAhBhkSYHXO+rOG1RCpkJGM5Yzn1MKaI49EJmd3L/9Pm7QBl3ebgPJTwksYCGS
                    |xMTSzbctettF1Ua3+y5ba2p7pvUH62nX+s4w2L22wis1twMdJaL9oSi9wh2+XKut
                    |aHx1uSPohedHA5xe8dP6OLF/2IjIrqQL+g6+R1sa+EFeyAUddk117FubvpKFy4UH
                    |Xa62iiprZMRrS+IY8IW/bzpVPz+m3boHaLthV4DdJfz17V6WFZdyNoUJIwYrtePe
                    |eMZ/1amw7aItLh0djfMhqmJsuP83ZZi+I10i3l01BboRRS9bQpeSS+EUrh1fkl40
                    |ymRUWzJv4vvtQXYuPrgpOtGRP2Ns4BQ7o4QavZk39F5pSxRTmpqAss0sOXN+ipOU
                    |mvKlpCQ0VrI+Q3/ET3TV
                    |-----END CERTIFICATE-----""".stripMargin

  // a valid, not used certificate
  // cfengine has is: eec3a3c2cf41b2736b7a0a9dece02142
  // sha256 in hexa: 41d8180612444dc6c280ac9189b8c95594029ba9a0697cd72b1af388b4b94844
  val CERT = """-----BEGIN CERTIFICATE-----
               |MIIFgTCCA2mgAwIBAgIUXpY2lv7l+hkx4mVP324d9O1qJh0wDQYJKoZIhvcNAQEL
               |BQAwUDEYMBYGA1UEAwwPV0lOLUdOR0RIUFZIVlROMTQwMgYKCZImiZPyLGQBAQwk
               |YjczZWE0NTEtYzQyYS00MjBkLWE1NDAtNDdiNDQ1ZTU4MzEzMB4XDTE5MDcxMjE2
               |MTYxMloXDTI3MDkyODE2MTYxMlowUDEYMBYGA1UEAwwPV0lOLUdOR0RIUFZIVlRO
               |MTQwMgYKCZImiZPyLGQBAQwkYjczZWE0NTEtYzQyYS00MjBkLWE1NDAtNDdiNDQ1
               |ZTU4MzEzMIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEApW5up//FLSHr
               |J14iIX7aSTGiVvJ5XTXHXxmx3O1MyFIrNoWoonmR7Wkii+FIcxk8LVajjMaBVP32
               |ZbfEr1BqljV/XULTO4ivQoqJCfoq/2O5O2Apyh1XJmp8q82CZRz/ZzxKmFAeYgYE
               |KPbzr/SeLkNvo9zaYZLMGT1Zle8pu7gBWF8DPFg1r77Y1zfSSRTRMSXQk0BVN5uR
               |2Ru8A53ZI7yDOB73pNXbtV++XdBzbwzBDG24NY80o+bbGSCRgizeDqNBeVjzOzyf
               |wRp6KFuLrwfksnUcWcwMBz3af6d5uh5hrDII63t30u3eVdmGYUb9oi5JjCOtcJta
               |r3EhwoeEoeioAxpJebe0Q0OEbEICh4Z/oxGYaG/rn9UZ3Hhw9sdngihiTx/sQ8yg
               |CGURXr/tQSw1knrmU7Fe1TytfcEhaGhnfjRXhUHXP75ycp4mdp3uRsHSKT7VN95H
               |lCVxZGUMkE9w8CZQTH2RmL6E5r0VqilktViWmuf31h2DPzg9rvBj+rQpBvgQzUiv
               |1TzuFzsuLKBp3KMpxHrnIxEMS2ERj1Kr7mAxW3xZVt3dYrw8SdbfozJ4x/d8ciKu
               |ovN0BBrPIn0wS6v7hT2mMtneEG/xbXZFjL8XqVwIooRCDOhw4UfWb71CdpBNZ8ln
               |tje4Ri0/C7l5ZJGYJNOpZFBlpDXmMTkCAwEAAaNTMFEwHQYDVR0OBBYEFHJaeKBJ
               |FcPOMwPGxt8uNESLRJ2YMB8GA1UdIwQYMBaAFHJaeKBJFcPOMwPGxt8uNESLRJ2Y
               |MA8GA1UdEwEB/wQFMAMBAf8wDQYJKoZIhvcNAQELBQADggIBAAjUW4YmUjYz6K50
               |uuN/WT+vRtPAKjTcKPi397O0sa1EZDq7gJt2gbYBMyqDFyoivKeec2umXzm7n0o5
               |yDJ1jwgl0ORxqtCmjzPuwbY9EL8dBycACsr8KorXct2vseC+uxNWnsLbUVs3iTbI
               |AG5dtXpytZJXioVvR/Hi6DnJ8hP6wQLKJYw3E91jjIdfUBWT1GRzjTHo6VBxlvQd
               |KFS8JeHMaUJjWiXeI8ZYPjLCDL2Fxs6hlgySBaZSbGySraFwt9l4RDVnUxexMloc
               |ZECALfJg4fISgZodHXRxVBKEUv71ebSqYfJt8f8LeyfLVK/MY9rmpdV8DGQieaaV
               |YdhslUYx6vTnk/0Q/LbeHXI2cm2qBP1oyPusydTWWc6TowCLhHqTJ+eAB2X/RjT/
               |MTe/B3GGKgn1lgB37qF2hVDWtrDvNzE4OGQCNBR/iJDHz5+8MV+4FDT0/7ruTP0B
               |iMDtuT7Jrk9O/UhAZyG4uyUm+kpcPIevGy2ZVQUgk/zIqLH+R4QrRebXRLrNsKuP
               |o07htJltXDGDSekSDgK3OnZwLOyTUrz1zMmGqGbqRCwOQAWcZBWLrIjUjM0k9vPy
               |qYUqf4FphVwX4JqDhm8JSS/et/0431MjMfQC/qauAhPBITgRjlDVEVvGB40aiNLk
               |ootapja6lKOaIpqp0kmmYN7gFIhp
               |-----END CERTIFICATE-----""".stripMargin

  val emptyNodeReportingConfiguration: ReportingConfiguration = ReportingConfiguration(None, None, None)

  val id1: NodeId = NodeId("node1")
  val hostname1 = "node1.localhost"
  val admin1    = "root"
  val id2: NodeId = NodeId("node2")
  val hostname2 = "node2.localhost"
  val rootId: NodeId = NodeId("root")
  val rootHostname = "server.rudder.local"
  val rootAdmin    = "root"

  val factRoot: CoreNodeFact = CoreNodeFact(
    rootId,
    None,
    rootHostname,
    Linux(Debian, "Jessie", new Version("7.0"), None, new Version("3.2")),
    MachineInfo(MachineUuid("machine1"), VirtualMachineType(VirtualBox), None, None),
    RudderSettings(
      UndefinedKey,
      emptyNodeReportingConfiguration,
      NodeKind.Root,
      AcceptedInventory,
      NodeState.Enabled,
      Some(Enforce),
      rootId,
      None
    ),
    RudderAgent(CfeCommunity, rootAdmin, AgentVersion("7.0.0"), Certificate(CERT), Chunk.empty),
    Chunk.empty,
    Instant.now(),
    Instant.now(),
    None,
    Chunk(IpAddress("127.0.0.1"), IpAddress("192.168.0.100")),
    Some(NodeTimezone("UTC", "+00")),
    None,
    None
  )

  val rootNode = factRoot.toNode
  val root     = factRoot.toNodeInfo

  val fact1: CoreNodeFact = CoreNodeFact(
    id1,
    None,
    hostname1,
    Linux(Debian, "Jessie", new Version("7.0"), None, new Version("3.2")),
    MachineInfo(MachineUuid("machine1"), VirtualMachineType(VirtualBox), None, None),
    RudderSettings(
      UndefinedKey,
      emptyNodeReportingConfiguration,
      NodeKind.Node,
      AcceptedInventory,
      NodeState.Enabled,
      None,
      rootId,
      None
    ),
    RudderAgent(CfeCommunity, admin1, AgentVersion("6.0.0"), Certificate(CERT), Chunk.empty),
    Chunk.empty,
    Instant.now(),
    Instant.now(),
    None,
    Chunk(IpAddress("192.168.0.10")),
    None,
    None,
    Some(MemorySize(1460132))
  )

  val node1Node: Node = toNode(fact1)
  val node1 = fact1.toNodeInfo

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

  val fact2: CoreNodeFact = fact1.modify(_.id).setTo(id2).modify(_.fqdn).setTo(hostname2)
  val node2Node = fact2.toNode
  val node2     = fact2.toNodeInfo

  val factDsc: CoreNodeFact = CoreNodeFact(
    NodeId("node-dsc"),
    None,
    "node-dsc.localhost",
    Windows(Windows2012, "Windows 2012 youpla boom", new Version("2012"), Some("sp1"), new Version("win-kernel-2012")),
    MachineInfo(MachineUuid("machine1"), VirtualMachineType(VirtualBox), None, None),
    RudderSettings(
      UndefinedKey,
      emptyNodeReportingConfiguration,
      NodeKind.Node,
      AcceptedInventory,
      NodeState.Enabled,
      None,
      rootId,
      None
    ),
    RudderAgent(Dsc, admin1, AgentVersion("7.0.0"), Certificate(CERT), Chunk.empty),
    Chunk.empty,
    Instant.now(),
    Instant.now(),
    None,
    Chunk(IpAddress("192.168.0.5")),
    None,
    None,
    Some(MemorySize(1460132))
  )

  val dscNode1Node = factDsc.toNode
  val dscNode1     = factDsc.toNodeInfo

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

  val allNodeFacts: Map[NodeId, CoreNodeFact] = Map(rootId -> factRoot, node1.id -> fact1, node2.id -> fact2)
  val allNodesInfo: Map[NodeId, NodeInfo]     = Map(rootId -> root, node1.id -> node1, node2.id -> node2)

  val defaultModesConfig: NodeModeConfig = NodeModeConfig(
    globalComplianceMode = GlobalComplianceMode(FullCompliance, 30),
    nodeHeartbeatPeriod = None,
    globalAgentRun = AgentRunInterval(None, 5, 0, 0, 0),
    nodeAgentRun = None,
    globalPolicyMode = GlobalPolicyMode(Enforce, PolicyModeOverrides.Always),
    nodePolicyMode = None
  )

  val rootNodeConfig: NodeConfiguration = NodeConfiguration(
    nodeInfo = factRoot,
    modesConfig = defaultModesConfig,
    runHooks = List(),
    policies = List[Policy](),
    nodeContext = Map[String, Variable](),
    parameters = Set[ParameterForConfiguration]()
  )

  val node1NodeConfig: NodeConfiguration = NodeConfiguration(
    nodeInfo = fact1,
    modesConfig = defaultModesConfig,
    runHooks = List(),
    policies = List[Policy](),
    nodeContext = Map[String, Variable](),
    parameters = Set[ParameterForConfiguration]()
  )

  val node2NodeConfig: NodeConfiguration = NodeConfiguration(
    nodeInfo = fact2,
    modesConfig = defaultModesConfig,
    runHooks = List(),
    policies = List[Policy](),
    nodeContext = Map[String, Variable](),
    parameters = Set[ParameterForConfiguration]()
  )

  /**
   * Some more nodes
   */
  val nodeIds: Set[NodeId] = (for {
    i <- 0 to 10
  } yield {
    NodeId(s"${i}")
  }).toSet

  val nodes: Map[NodeId, CoreNodeFact] = (Set(factRoot, fact1, fact2) ++ nodeIds.map { id =>
    CoreNodeFact(
      id,
      None,
      s"Node-${id}",
      Linux(Debian, "Jessie", new Version("7.0"), None, new Version("3.2")),
      MachineInfo(MachineUuid("machine1"), VirtualMachineType(VirtualBox), None, None),
      RudderSettings(
        UndefinedKey,
        emptyNodeReportingConfiguration,
        NodeKind.Node,
        AcceptedInventory,
        NodeState.Enabled,
        None,
        rootId,
        None
      ),
      RudderAgent(CfeCommunity, admin1, AgentVersion("6.0.0"), Certificate("node certificate"), Chunk.empty),
      Chunk.empty,
      Instant.now(),
      Instant.now(),
      None,
      Chunk(),
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

  val g0id:   NodeGroupId                   = NodeGroupId(NodeGroupUid("0"))
  val g0:     NodeGroup                     = {
    NodeGroup(
      g0id,
      name = "Real nodes",
      description = "",
      properties = Nil,
      query = None,
      isDynamic = false,
      serverList = Set(rootId, node1.id, node2.id),
      _isEnabled = true
    )
  }
  val g1:     NodeGroup                     = NodeGroup(
    NodeGroupId(NodeGroupUid("1")),
    name = "Empty group",
    description = "",
    properties = Nil,
    query = None,
    isDynamic = false,
    serverList = Set(),
    _isEnabled = true
  )
  val g2:     NodeGroup                     = {
    NodeGroup(
      NodeGroupId(NodeGroupUid("2")),
      name = "only root",
      description = "",
      properties = Nil,
      query = None,
      isDynamic = false,
      serverList = Set(NodeId("root")),
      _isEnabled = true
    )
  }
  val g3:     NodeGroup                     = {
    NodeGroup(
      NodeGroupId(NodeGroupUid("3")),
      name = "Even nodes",
      description = "",
      properties = Nil,
      query = None,
      isDynamic = false,
      serverList = nodeIds.filter(_.value.toInt == 2),
      _isEnabled = true
    )
  }
  val g4:     NodeGroup                     = {
    NodeGroup(
      NodeGroupId(NodeGroupUid("4")),
      name = "Odd nodes",
      description = "",
      properties = Nil,
      query = None,
      isDynamic = false,
      serverList = nodeIds.filter(_.value.toInt != 2),
      _isEnabled = true
    )
  }
  val g5:     NodeGroup                     = NodeGroup(
    NodeGroupId(NodeGroupUid("5")),
    name = "Nodes id divided by 3",
    description = "",
    properties = Nil,
    query = None,
    isDynamic = false,
    serverList = nodeIds.filter(_.value.toInt == 3),
    _isEnabled = true
  )
  val g6:     NodeGroup                     = NodeGroup(
    NodeGroupId(NodeGroupUid("6")),
    name = "Nodes id divided by 5",
    description = "",
    properties = Nil,
    query = None,
    isDynamic = false,
    serverList = nodeIds.filter(_.value.toInt == 5),
    _isEnabled = true
  )
  val groups: Set[(NodeGroupId, NodeGroup)] = Set(g0, g1, g2, g3, g4, g5, g6).map(g => (g.id, g))

  val groupTargets: Set[(GroupTarget, NodeGroup)] = groups.map { case (id, g) => (GroupTarget(g.id), g) }

  val fullRuleTargetInfos: Map[NodeGroupId, FullRuleTargetInfo] = (groupTargets
    .map(gt => {
      (
        gt._1.groupId,
        FullRuleTargetInfo(
          FullGroupTarget(gt._1, gt._2),
          name = "",
          description = "",
          isEnabled = true,
          isSystem = false
        )
      )
    }))
    .toMap

  val groupLib: FullNodeGroupCategory = FullNodeGroupCategory(
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
  implicit def toATID(s:  String):           ActiveTechniqueId = ActiveTechniqueId(s)
  implicit def toTV(s:    String):           TechniqueVersion  = TechniqueVersionHelper(s)
  implicit def toTN(s:    String):           TechniqueName     = TechniqueName(s)
  implicit def toTID(id:  (String, String)): TechniqueId       = TechniqueId(id._1, id._2)
  implicit def toDID(id:  String):           DirectiveId       = DirectiveId(DirectiveUid(id), GitVersion.DEFAULT_REV)
  implicit def toRID(id:  String):           RuleId            = RuleId(RuleUid(id))
  implicit def toRCID(id: String):           RuleCategoryId    = RuleCategoryId(id)
  val t1:   Technique           = Technique(("t1", "1.0"), "t1", "t1", Nil, TrackerVariableSpec(None, None), SectionSpec("root"), None)
  val d1:   Directive           = Directive("d1", "1.0", Map("foo1" -> Seq("bar1")), "d1", "d1", None, _isEnabled = true)
  val d2:   Directive           = Directive("d2", "1.0", Map("foo2" -> Seq("bar2")), "d2", "d2", Some(PolicyMode.Enforce), _isEnabled = true)
  val d3:   Directive           = Directive("d3", "1.0", Map("foo3" -> Seq("bar3")), "d3", "d3", Some(PolicyMode.Audit), _isEnabled = true)
  val fat1: FullActiveTechnique = FullActiveTechnique(
    "d1",
    "t1",
    SortedMap(toTV("1.0") -> DateTime.parse("2016-01-01T12:00:00.000+00:00")),
    SortedMap(toTV("1.0") -> t1),
    d1 :: d2 :: Nil
  )

  val directives: FullActiveTechniqueCategory =
    FullActiveTechniqueCategory(ActiveTechniqueCategoryId("root"), "root", "root", Nil, fat1 :: Nil)

  /**
   *   ************************************************************************
   *                         Some rules
   *   ************************************************************************
   */

  val r1: Rule = Rule("r1", "r1", "cat1")
  val r2: Rule = Rule("r2", "r2", "cat1")

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

  val t0: Long = System.currentTimeMillis()

  val abstractRoot = new File("/tmp/test-rudder-config-repo-" + DateTime.now.toString())
  abstractRoot.mkdirs()
  if (System.getProperty("tests.clean.tmp") != "false") {
    Runtime.getRuntime.addShutdownHook(new Thread(new Runnable {
      override def run(): Unit = FileUtils.deleteDirectory(abstractRoot)
    }))
  }

  // config-repo will also be the git root, as a normal rudder
  val configurationRepositoryRoot: File = abstractRoot / "configuration-repository"
  // initialize config-repo content from our test/resources source

  // Since we want to have only one `configuration-repository`, and it's in `rudder-core`, the source can be in the
  // FS (for tests in rudder-core) or in the test jar for rudder-core.
  NodeConfigData.copyConfigurationRepository(
    prefixTestResources + "src/test/resources/" + configRepoName,
    configurationRepositoryRoot
  )

  val EXPECTED_SHARE: File = configurationRepositoryRoot / "expected-share"
  val t1:             Long = System.currentTimeMillis()
  NodeConfigData.logger.trace(s"Paths inits             : ${t1 - t0} ms")

  val repo: GitRepositoryProviderImpl = GitRepositoryProviderImpl.make(configurationRepositoryRoot.getAbsolutePath).runNow
  val t2:   Long                      = System.currentTimeMillis()
  NodeConfigData.logger.trace(s"Git repo provider       : ${t2 - t1} ms")

  val variableSpecParser = new VariableSpecParser
  val t2bis: Long = System.currentTimeMillis()
  NodeConfigData.logger.trace(s"var Spec Parser        : ${t2bis - t2} ms")
  val systemVariableServiceSpec = new SystemVariableSpecServiceImpl()
  val t3: Long = System.currentTimeMillis()
  NodeConfigData.logger.trace(s"System Var Spec service : ${t3 - t2bis} ms")

  val draftParser: TechniqueParser = new TechniqueParser(
    variableSpecParser,
    new SectionSpecParser(variableSpecParser),
    systemVariableServiceSpec
  )
  val t4:          Long            = System.currentTimeMillis()
  NodeConfigData.logger.trace(s"Technique parser        : ${t4 - t3} ms")

  val gitRevisionProvider: GitRevisionProvider =
    optGitRevisionProvider.map(x => x(repo)).getOrElse(new SimpleGitRevisionProvider("refs/heads/master", repo))
  val reader = new GitTechniqueReader(
    draftParser,
    gitRevisionProvider,
    repo,
    "metadata.xml",
    "category.xml",
    Some("techniques"),
    "default-directive-names.conf"
  )
  val t5: Long = System.currentTimeMillis()
  NodeConfigData.logger.trace(s"Git tech reader         : ${t5 - t4} ms")

  val techniqueRepository = new TechniqueRepositoryImpl(reader, Seq(), new StringUuidGeneratorImpl())
  val t6: Long = System.currentTimeMillis()
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

  import com.normation.rudder.services.policies.NodeConfigData.root
  /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // set up root node configuration
  /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  val t6:                     Long                          = System.currentTimeMillis()
  val policyServerManagement: PolicyServerManagementService = new PolicyServerManagementService() {
    override def getPolicyServers(): IOResult[PolicyServers] = {
      PolicyServers(
        PolicyServer(
          Constants.ROOT_POLICY_SERVER_ID,
          List("192.168.12.0/24", "192.168.49.0/24", "127.0.0.1/24").map(s => AllowedNetwork(s, s"name for " + s))
        ),
        Nil
      ).succeed
    }

    override def savePolicyServers(policyServers:         PolicyServers): IOResult[PolicyServers] = ???
    override def updatePolicyServers(
        commands: List[PolicyServersUpdateCommand],
        modId:    ModificationId,
        actor:    EventActor
    ): IOResult[PolicyServers] = ???
    override def deleteRelaySystemObjects(policyServerId: NodeId):        IOResult[Unit]          = ???
  }
  val t7:                     Long                          = System.currentTimeMillis()
  NodeConfigData.logger.trace(s"Policy Server Management: ${t7 - t6} ms")

  val instanceIdService     = new InstanceIdService(InstanceId("test-instance-id"))
  val systemVariableService = new SystemVariableServiceImpl(
    systemVariableServiceSpec,
    policyServerManagement,
    instanceIdService,
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
    serverVersion = "7.0.0",                // denybadclocks is runtime properties
    PolicyServerCertificateConfig("sha256//Pxjkq/Qlp02j8Q3ti3M1khEaUTL7Dxcz8sLOfGcg5rQ=" :: Nil, "", false),
    getDenyBadClocks = () => Full(true),
    getSyncMethod = () => Full(Classic),
    getSyncPromises = () => Full(false),
    getSyncSharedFiles = () => Full(false), // TTLs are runtime properties too

    getModifiedFilesTtl = () => Full(30),
    getCfengineOutputsTtl = () => Full(7),
    getReportProtocolDefault = () => Full(AgentReportingHTTPS)
  )

  // another system variable to test other variable configs
  val systemVariableServiceAltConfig = new SystemVariableServiceImpl(
    systemVariableServiceSpec,
    policyServerManagement,
    instanceIdService,
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
    serverVersion = "7.0.0",                // denybadclocks is runtime properties
    PolicyServerCertificateConfig(Nil, "", false),
    getDenyBadClocks = () => Full(true),
    getSyncMethod = () => Full(Classic),
    getSyncPromises = () => Full(false),
    getSyncSharedFiles = () => Full(false), // TTLs are runtime properties too

    getModifiedFilesTtl = () => Full(30),
    getCfengineOutputsTtl = () => Full(7),
    getReportProtocolDefault = () => Full(AgentReportingHTTPS)
  )

  val t8: Long = System.currentTimeMillis()
  NodeConfigData.logger.trace(s"System variable Service: ${t8 - t7} ms")

  // a test node - CFEngine
  val nodeId:  NodeId       = NodeId("c8813416-316f-4307-9b6a-ca9c109a9fb0")
  val factCfe: CoreNodeFact = fact1.modify(_.id).setTo(nodeId)
  val cfeNode = factCfe.toNodeInfo

  val allNodeFacts_rootOnly: MapView[NodeId, CoreNodeFact] = MapView(root.id -> factRoot)
  val allNodesInfo_rootOnly: Map[NodeId, NodeInfo]         = allNodeFacts_rootOnly.mapValues(_.toNodeInfo).toMap
  val allNodeFacts_cfeNode:  MapView[NodeId, CoreNodeFact] = MapView(root.id -> factRoot, cfeNode.id -> factCfe)
  val allNodesInfo_cfeNode:  Map[NodeId, NodeInfo]         = allNodeFacts_cfeNode.mapValues(_.toNodeInfo).toMap

  // the group lib
  val emptyGroupLib: FullNodeGroupCategory = FullNodeGroupCategory(
    NodeGroupCategoryId("/"),
    name = "/",
    description = "root of group categories",
    subCategories = List(),
    targetInfos = List(),
    isSystem = true
  )

  val groupLib: FullNodeGroupCategory = emptyGroupLib.copy(
    targetInfos = List(
      FullRuleTargetInfo(
        FullGroupTarget(
          GroupTarget(NodeGroupId(NodeGroupUid("a-group-for-root-only"))),
          NodeGroup(
            NodeGroupId(NodeGroupUid("a-group-for-root-only")),
            name = "Serveurs [€ðŋ] cassés",
            description = "Liste de l'ensemble de serveurs cassés à réparer",
            properties = Nil,
            query = None,
            isDynamic = true,
            serverList = Set(NodeId("root")),
            _isEnabled = true,
            isSystem = false
          )
        ),
        name = "Serveurs [€ðŋ] cassés",
        description = "Liste de l'ensemble de serveurs cassés à réparer",
        isEnabled = true,
        isSystem = false
      ),
      FullRuleTargetInfo(
        FullOtherTarget(PolicyServerTarget(NodeId("root"))),
        name = "special:policyServer_root",
        description = "The root policy server",
        isEnabled = true,
        isSystem = true
      ),
      FullRuleTargetInfo(
        FullOtherTarget(AllTargetExceptPolicyServers),
        name = "special:all_exceptPolicyServers",
        description = "All groups without policy servers",
        isEnabled = true,
        isSystem = true
      ),
      FullRuleTargetInfo(
        FullOtherTarget(AllTarget),
        name = "special:all",
        description = "All nodes",
        isEnabled = true,
        isSystem = true
      )
    )
  )

  val globalAgentRun:       AgentRunInterval     = AgentRunInterval(None, 5, 1, 0, 4)
  val globalComplianceMode: GlobalComplianceMode = GlobalComplianceMode(FullCompliance, 15)

  val globalSystemVariables: Map[String, Variable] = systemVariableService
    .getGlobalSystemVariables(globalAgentRun)
    .openOrThrowException("I should get global system variable in test!")

  val t9: Long = System.currentTimeMillis()
  NodeConfigData.logger.trace(s"Nodes & groups         : ${t9 - t8} ms")

  //
  // root has 4 system directive, let give them some variables
  //
  implicit class UnsafeGet(repo: TechniqueRepositoryImpl) {
    def unsafeGet(id: TechniqueId): Technique =
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
  ): BoundPolicyDraft = {
    BoundPolicyDraft(
      id,
      ruleName,
      directiveName,
      technique,
      DateTime.now(DateTimeZone.UTC),
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

  val commonTechnique:                                                      Technique                  =
    techniqueRepository.unsafeGet(TechniqueId(TechniqueName("common"), TechniqueVersionHelper("1.0")))
  def commonVariables(nodeId: NodeId, allNodeInfos: Map[NodeId, NodeInfo]): Map[ComponentId, Variable] = {
    getVariablesFromSpec(
      commonTechnique,
      List(
        ("OWNER", Seq(allNodeInfos(nodeId).localAdministratorAccountName)),
        ("UUID", Seq(nodeId.value)),
        ("POLICYSERVER_ID", Seq(allNodeInfos(nodeId).policyServerId.value)),
        ("POLICYSERVER_ADMIN", Seq(allNodeInfos(allNodeInfos(nodeId).policyServerId).localAdministratorAccountName)),
        ("ALLOWEDNETWORK", Seq(""))
      )
    )
  }

  val commonDirective: Directive = Directive(
    DirectiveId(DirectiveUid("common-hasPolicyServer-root"), GitVersion.DEFAULT_REV),
    TechniqueVersionHelper("1.0"),
    Map(
      ("OWNER", Seq("${rudder.node.admin}")),
      ("UUID", Seq("${rudder.node.id}")),
      ("POLICYSERVER_ID", Seq("${rudder.node.id}")),
      ("POLICYSERVER_ADMIN", Seq("${rudder.node.admin}"))
    ),
    name = "common-root",
    shortDescription = "",
    policyMode = None,
    longDescription = "",
    priority = 5,
    _isEnabled = true,
    isSystem = true
  )

  def common(nodeId: NodeId, allNodeInfos: Map[NodeId, NodeInfo]): BoundPolicyDraft = {
    val id = PolicyId(RuleId("hasPolicyServer-root"), commonDirective.id, TechniqueVersionHelper("1.0"))
    draft(
      id,
      "Rudder system policy: basic setup (common)",
      "Common",
      commonTechnique,
      commonVariables(nodeId, allNodeInfos)
    )
  }

  val archiveTechnique: Technique =
    techniqueRepository.unsafeGet(TechniqueId(TechniqueName("test_import_export_archive"), TechniqueVersionHelper("1.0")))
  val archiveDirective: Directive = Directive(
    DirectiveId(DirectiveUid("test_import_export_archive_directive"), GitVersion.DEFAULT_REV),
    TechniqueVersionHelper("1.0"),
    parameters = Map(),
    name = "test_import_export_archive_directive",
    shortDescription = "",
    policyMode = None,
    longDescription = "",
    priority = 5,
    _isEnabled = true,
    isSystem = false
  )

  // we have one rule with several system technique for root server config

  // a version of get with a default value "value for ..."
  def getVariablesFromSpec(
      technique: Technique,
      variables: List[(String, Seq[String])]
  ): Map[ComponentId, Variable] = {
    val specs = technique.getAllVariableSpecs
    // add system variable as a base, we don't build them correctly because we skip NodeConfiguration set-up
    // in that test, see TestBuildNodeConfiguration to see how it should be done
    specs.collect { case (cid, s) if (s.isSystem) => (cid, s.toVariable(Seq(s"dummy_system_${s.name}"))) } ++
    variables.map {
      case (name, values) =>
        specs
          .find(s => s._1.value == name)
          .getOrElse(
            throw new RuntimeException(s"Missing variable spec '${name}' in technique ${technique.id.debugString} in test")
          ) match {
          case (cid, p: PredefinedValuesVariableSpec) => (cid, p.toVariable(p.providedValues._1 :: p.providedValues._2.toList))
          case (cid, s)                               => (cid, s.toVariable(values))
        }
    }.toMap
  }

  // get variable's values based on the kind of spec for that: if the values are provided, use them.
  def getVariables(
      technique: Technique,
      variables: List[String]
  ): Map[ComponentId, Variable] = {
    getVariablesFromSpec(technique, variables.map(name => (name, Seq(s"value for '${name}'"))))
  }

  def simpleServerPolicy(name: String, variables: List[String] = List()): (Technique, BoundPolicyDraft) = {
    val technique = techniqueRepository.unsafeGet(TechniqueId(TechniqueName(s"${name}"), TechniqueVersionHelper("1.0")))
    val vars      = getVariables(technique, variables)
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

  val (serverCommonTechnique, serverCommon) = simpleServerPolicy("server-common")
  val apacheVariables: List[String] = List(
    "expectedReportKey Apache service",
    "expectedReportKey Apache configuration",
    "expectedReportKey Configure apache certificate"
  )
  val (serverApacheTechnique, serverApache) = simpleServerPolicy("rudder-service-apache", apacheVariables)
  val postgresqlVariables: List[String] = List(
    "expectedReportKey Postgresql service",
    "expectedReportKey Postgresql configuration"
  )
  val (serverPostgresqlTechnique, serverPostgresql) = simpleServerPolicy("rudder-service-postgresql", postgresqlVariables)
  val relaydVariables: List[String] = List(
    "expectedReportKey Rudder-relayd service"
  )
  val (serverRelaydTechnique, serverRelayd) = simpleServerPolicy("rudder-service-relayd", relaydVariables)
  val slapdVariables: List[String] = List(
    "expectedReportKey Rudder slapd service",
    "expectedReportKey Rudder slapd configuration"
  )
  val (serverSlapdTechnique, serverSlapd) = simpleServerPolicy("rudder-service-slapd", slapdVariables)
  val webappVariables: List[String] = List(
    "expectedReportKey Rudder-jetty service",
    "expectedReportKey Check configuration-repository",
    "expectedReportKey Check webapp configuration"
  )
  val (serverWebappTechnique, serverWebapp) = simpleServerPolicy("rudder-service-webapp", webappVariables)

  val inventoryTechnique = techniqueRepository.unsafeGet(TechniqueId(TechniqueName("inventory"), TechniqueVersionHelper("1.0")))
  val inventoryVariables: Map[ComponentId, Variable] = {
    getVariables(inventoryTechnique, List("expectedReportKey Inventory"))
  }

  val inventoryAll: BoundPolicyDraft = {
    val id = PolicyId(RuleId("inventory-all"), DirectiveId(DirectiveUid("inventory-all")), TechniqueVersionHelper("1.0"))
    draft(
      id,
      "Rudder system policy: daily inventory",
      "Inventory",
      inventoryTechnique,
      inventoryVariables
    )
  }

  val allRootPolicies: List[BoundPolicyDraft] =
    List(serverCommon, serverApache, serverPostgresql, serverRelayd, serverSlapd, serverWebapp, inventoryAll)

  //
  // 4 user directives: clock management, rpm, package, a multi-policiy: fileTemplate, and a ncf one: Create_file
  //
  lazy val clockTechnique =
    techniqueRepository.unsafeGet(TechniqueId(TechniqueName("clockConfiguration"), TechniqueVersionHelper("3.0")))
  lazy val clockVariables: Map[ComponentId, Variable] = {
    getVariablesFromSpec(
      clockTechnique,
      List(
        ("CLOCK_FQDNNTP", Seq("")), // testing mandatory value with default "true"
        ("CLOCK_HWSYNC_ENABLE", Seq("true")),
        ("CLOCK_NTPSERVERS", Seq("${rudder.param.ntpserver}")),
        ("CLOCK_SYNCSCHED", Seq("240")),
        ("CLOCK_TIMEZONE", Seq("dontchange"))
      )
    )
  }
  lazy val clock:          BoundPolicyDraft           = {
    val id = PolicyId(RuleId("rule1"), DirectiveId(DirectiveUid("directive1+rev1")), TechniqueVersionHelper("1.0+rev2"))
    draft(
      id,
      ruleName = "10. Global configuration for all nodes",
      directiveName = "10. Clock Configuration",
      technique = clockTechnique,
      variableMap = clockVariables,
      system = false,
      policyMode = Some(PolicyMode.Enforce)
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
  lazy val rpmVariables:                     Map[ComponentId, Variable] = {
    getVariablesFromSpec(
      rpmTechnique,
      List(
        ("RPM_PACKAGE_CHECK_INTERVAL", Seq("5")),
        ("RPM_PACKAGE_POST_HOOK_COMMAND", Seq("", "", "")),
        ("RPM_PACKAGE_POST_HOOK_RUN", Seq("false", "false", "false")),
        ("RPM_PACKAGE_REDACTION", Seq("add", "add", "add")),
        ("RPM_PACKAGE_REDLIST", Seq("plop", "foo", "bar")),
        ("RPM_PACKAGE_VERSION", Seq("", "", "")),
        ("RPM_PACKAGE_VERSION_CRITERION", Seq("==", "==", "==")),
        ("RPM_PACKAGE_VERSION_DEFINITION", Seq("default", "default", "default"))
      )
    )
  }
  def rpmDirective(id: String, pkg: String): Directive                  = Directive(
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
    "",
    _isEnabled = true
  )
  lazy val rpm:                              BoundPolicyDraft           = {
    val id = PolicyId(RuleId("rule2"), DirectiveId(DirectiveUid("directive2")), TechniqueVersionHelper("1.0"))
    draft(
      id,
      ruleName = "50. Deploy PLOP STACK",
      directiveName = "20. Install PLOP STACK main rpm",
      technique = rpmTechnique,
      variableMap = rpmVariables,
      system = false,
      policyMode = Some(PolicyMode.Audit)
    )
  }

  lazy val pkgTechnique =
    techniqueRepository.unsafeGet(TechniqueId(TechniqueName("packageManagement"), TechniqueVersionHelper("1.0")))
  lazy val pkgVariables: Map[ComponentId, Variable] = {
    getVariablesFromSpec(
      pkgTechnique,
      List(
        ("PACKAGE_LIST", Seq("htop")),
        ("PACKAGE_STATE", Seq("present")),
        ("PACKAGE_VERSION", Seq("latest")),
        ("PACKAGE_VERSION_SPECIFIC", Seq("")),
        ("PACKAGE_ARCHITECTURE", Seq("default")),
        ("PACKAGE_ARCHITECTURE_SPECIFIC", Seq("")),
        ("PACKAGE_MANAGER", Seq("default")),
        ("PACKAGE_POST_HOOK_COMMAND", Seq(""))
      )
    )
  }
  lazy val pkg:          BoundPolicyDraft           = {
    val id = PolicyId(
      RuleId("ff44fb97-b65e-43c4-b8c2-0df8d5e8549f"),
      DirectiveId(DirectiveUid("16617aa8-1f02-4e4a-87b6-d0bcdfb4019f")),
      TechniqueVersionHelper("1.0")
    )
    draft(
      id,
      ruleName = "60-rule-technique-std-lib",
      directiveName = "Package management.",
      technique = pkgTechnique,
      variableMap = pkgVariables,
      system = false,
      policyMode = Some(PolicyMode.Enforce)
    )
  }

  lazy val fileTemplateTechnique =
    techniqueRepository.unsafeGet(TechniqueId(TechniqueName("fileTemplate"), TechniqueVersionHelper("1.0")))
  lazy val fileTemplateVariables1: Map[ComponentId, Variable] = {
    getVariablesFromSpec(
      fileTemplateTechnique,
      List(
        ("FILE_TEMPLATE_RAW_OR_NOT", Seq("Raw")),
        ("FILE_TEMPLATE_TEMPLATE", Seq("")),
        ("FILE_TEMPLATE_RAW_TEMPLATE", Seq("some content")),
        ("FILE_TEMPLATE_AGENT_DESTINATION_PATH", Seq("/tmp/destination.txt")),
        ("FILE_TEMPLATE_TEMPLATE_TYPE", Seq("mustache")),
        ("FILE_TEMPLATE_OWNER", Seq("root")),
        ("FILE_TEMPLATE_GROUP_OWNER", Seq("root")),
        ("FILE_TEMPLATE_PERMISSIONS", Seq("700")),
        ("FILE_TEMPLATE_PERSISTENT_POST_HOOK", Seq("false")),
        ("FILE_TEMPLATE_TEMPLATE_POST_HOOK_COMMAND", Seq(""))
      )
    )
  }
  lazy val fileTemplate1:          BoundPolicyDraft           = {
    val id = PolicyId(
      RuleId("ff44fb97-b65e-43c4-b8c2-0df8d5e8549f"),
      DirectiveId(DirectiveUid("e9a1a909-2490-4fc9-95c3-9d0aa01717c9")),
      TechniqueVersionHelper("1.0")
    )
    draft(
      id,
      ruleName = "60-rule-technique-std-lib",
      directiveName = "10-File template 1",
      technique = fileTemplateTechnique,
      variableMap = fileTemplateVariables1,
      system = false,
      policyMode = Some(PolicyMode.Enforce)
    )
  }
  lazy val fileTemplateVariables2 = {
    getVariablesFromSpec(
      fileTemplateTechnique,
      List(
        ("FILE_TEMPLATE_RAW_OR_NOT", Seq("Raw")),
        ("FILE_TEMPLATE_TEMPLATE", Seq("")),
        ("FILE_TEMPLATE_RAW_TEMPLATE", Seq("some content")),
        ("FILE_TEMPLATE_AGENT_DESTINATION_PATH", Seq("/tmp/other-destination.txt")),
        ("FILE_TEMPLATE_TEMPLATE_TYPE", Seq("mustache")),
        ("FILE_TEMPLATE_OWNER", Seq("root")),
        ("FILE_TEMPLATE_GROUP_OWNER", Seq("root")),
        ("FILE_TEMPLATE_PERMISSIONS", Seq("777")),
        ("FILE_TEMPLATE_PERSISTENT_POST_HOOK", Seq("true")),
        ("FILE_TEMPLATE_TEMPLATE_POST_HOOK_COMMAND", Seq("/bin/true"))
      )
    )
  }
  lazy val fileTemplate2:          BoundPolicyDraft           = {
    val id = PolicyId(
      RuleId("ff44fb97-b65e-43c4-b8c2-0df8d5e8549f"),
      DirectiveId(DirectiveUid("99f4ef91-537b-4e03-97bc-e65b447514cc")),
      TechniqueVersionHelper("1.0")
    )
    draft(
      id,
      ruleName = "60-rule-technique-std-lib",
      directiveName = "20-File template 2",
      technique = fileTemplateTechnique,
      variableMap = fileTemplateVariables2,
      system = false,
      policyMode = Some(PolicyMode.Enforce)
    )
  }

  // fileTemplate3 is a copy of fileTemplate2 but provided by an other rule
  lazy val fileTemplate3: BoundPolicyDraft = {
    val id = PolicyId(
      RuleId("ff44fb97-b65e-43c4-b8c2-000000000000"),
      DirectiveId(DirectiveUid("99f4ef91-537b-4e03-97bc-e65b447514cc")),
      TechniqueVersionHelper("1.0")
    )
    draft(
      id,
      ruleName = "99-rule-technique-std-lib",
      directiveName = "20-File template 2",
      technique = fileTemplateTechnique,
      variableMap = fileTemplateVariables2,
      system = false,
      policyMode = Some(PolicyMode.Enforce)
    )
  }

  val ncf1Technique = techniqueRepository.unsafeGet(TechniqueId(TechniqueName("Create_file"), TechniqueVersionHelper("1.0")))
  val ncf1Variables = {
    getVariablesFromSpec(
      ncf1Technique,
      List(
        ("expectedReportKey Directory create", Seq("directory_create_/tmp/foo")),
        ("expectedReportKey File create", Seq("file_create_/tmp/foo/bar")),
        ("1AAACD71-C2D5-482C-BCFF-5EEE6F8DA9C2", Seq("\"foo"))
        // here we don't define 3021FC4F-DA33-4D84-8991-C42EBAB2335F to check that the default " " is well used
      )
    )
  }
  val ncf1: BoundPolicyDraft = {
    val id = PolicyId(
      RuleId("208716db-2675-43b9-ab57-bfbab84346aa"),
      DirectiveId(DirectiveUid("16d86a56-93ef-49aa-86b7-0d10102e4ea9")),
      TechniqueVersionHelper("1.0")
    )
    draft(
      id,
      ruleName = "50-rule-technique-ncf",
      directiveName = "Create a file",
      technique = ncf1Technique,
      variableMap = ncf1Variables,
      system = false,
      policyMode = Some(PolicyMode.Enforce)
    )
  }

  // test ticket 18205
  lazy val test18205Technique =
    techniqueRepository.unsafeGet(TechniqueId(TechniqueName("test_18205"), TechniqueVersionHelper("1.0")))
  lazy val test18205: BoundPolicyDraft = {
    val id = PolicyId(RuleId("rule1"), DirectiveId(DirectiveUid("directive1")), TechniqueVersionHelper("1.0"))
    draft(
      id,
      ruleName = "10. Global configuration for all nodes",
      directiveName = "10. test18205",
      technique = test18205Technique,
      variableMap = Map(),
      system = false,
      policyMode = Some(PolicyMode.Enforce)
    )
  }

  /**
    * test for multiple generation
    */
  val DIRECTIVE_NAME_COPY_GIT_FILE = "directive-copyGitFile"
  lazy val copyGitFileTechnique    =
    techniqueRepository.unsafeGet(TechniqueId(TechniqueName("copyGitFile"), TechniqueVersionHelper("2.3")))
  def copyGitFileVariable(i: Int)  = {
    getVariablesFromSpec(
      copyGitFileTechnique,
      List(
        ("COPYFILE_NAME", Seq("file_name_" + i + ".json")),
        ("COPYFILE_EXCLUDE_INCLUDE_OPTION", Seq("none")),
        ("COPYFILE_EXCLUDE_INCLUDE", Seq("")),
        ("COPYFILE_DESTINATION", Seq("/tmp/destination_" + i + ".json")),
        ("COPYFILE_RECURSION", Seq(s"${i % 2}")),
        ("COPYFILE_PURGE", Seq("false")),
        ("COPYFILE_COMPARE_METHOD", Seq("mtime")),
        ("COPYFILE_OWNER", Seq("root")),
        ("COPYFILE_GROUP", Seq("root")),
        ("COPYFILE_PERM", Seq("644")),
        ("COPYFILE_SUID", Seq("false")),
        ("COPYFILE_SGID", Seq("false")),
        ("COPYFILE_STICKY_FOLDER", Seq("false")),
        ("COPYFILE_POST_HOOK_RUN", Seq("true")),
        ("COPYFILE_POST_HOOK_COMMAND", Seq("/bin/echo Value_" + i + ".json"))
      )
    )
  }

  def copyGitFileDirectives(i: Int): BoundPolicyDraft = {
    val id = PolicyId(
      RuleId("rulecopyGitFile"),
      DirectiveId(DirectiveUid(DIRECTIVE_NAME_COPY_GIT_FILE + i)),
      TechniqueVersionHelper("2.3")
    )
    draft(
      id,
      ruleName = "90-copy-git-file",
      directiveName = "Copy git file",
      technique = copyGitFileTechnique,
      variableMap = copyGitFileVariable(i),
      system = false,
      policyMode = Some(PolicyMode.Enforce)
    )
  }

  val t10: Long = System.currentTimeMillis()
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
   * directive1 is the default value and must be overridden by value in directive 2, which means that directive2 value must be
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
    getVariablesFromSpec(
      gvdTechnique,
      List(
        ("GENERIC_VARIABLE_NAME", Seq("var1")),
        ("GENERIC_VARIABLE_CONTENT", Seq("value from gvd #1 should be first")) // the one to override
      )
    )
  }
  lazy val gvd1: BoundPolicyDraft = {
    val id = PolicyId(RuleId("rule1"), DirectiveId(DirectiveUid("directive1")), TechniqueVersionHelper("1.0"))
    draft(
      id,
      ruleName = "10. Global configuration for all nodes",
      directiveName = "99. Generic Variable Def #1",
      technique = gvdTechnique,
      variableMap = gvdVariables1,
      system = false,
      policyMode = Some(PolicyMode.Enforce)
    ).copy(
      priority = 0 // we want to make sure this one will be merged in first position
    )
  }
  lazy val gvdVariables2 = {
    getVariablesFromSpec(
      gvdTechnique,
      List(
        ("GENERIC_VARIABLE_NAME", Seq("var1")),
        ("GENERIC_VARIABLE_CONTENT", Seq("value from gvd #2 should be last")) // the one to use for override
      )
    )
  }
  lazy val gvd2: BoundPolicyDraft = {
    val id = PolicyId(RuleId("rule1"), DirectiveId(DirectiveUid("directive2")), TechniqueVersionHelper("1.0"))
    draft(
      id,
      ruleName = "10. Global configuration for all nodes",
      directiveName = "00. Generic Variable Def #2", // sort name comes before sort name of directive 1

      technique = gvdTechnique,
      variableMap = gvdVariables2,
      system = false,
      policyMode = Some(PolicyMode.Enforce)
    ).copy(
      priority = 10 // we want to make sure this one will be merged in last position
    )
  }
}
