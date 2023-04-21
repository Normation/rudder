/*
 *************************************************************************************
 * Copyright 2011 Normation SAS
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

package com.normation.rudder.services.queries

import com.github.ghik.silencer.silent
import com.normation.NamedZioLogger
import com.normation.errors._
import com.normation.inventory.domain.AcceptedInventory
import com.normation.inventory.domain.AgentType.CfeCommunity
import com.normation.inventory.domain.AgentType.CfeEnterprise
import com.normation.inventory.domain.AgentType.Dsc
import com.normation.inventory.domain.AgentVersion
import com.normation.inventory.domain.Bios
import com.normation.inventory.domain.Certificate
import com.normation.inventory.domain.CertifiedKey
import com.normation.inventory.domain.FileSystem
import com.normation.inventory.domain.InventoryStatus
import com.normation.inventory.domain.Linux
import com.normation.inventory.domain.MachineUuid
import com.normation.inventory.domain.MemorySize
import com.normation.inventory.domain.Network
import com.normation.inventory.domain.NodeId
import com.normation.inventory.domain.PhysicalMachineType
import com.normation.inventory.domain.Process
import com.normation.inventory.domain.PublicKey
import com.normation.inventory.domain.SoftwareEditor
import com.normation.inventory.domain.Ubuntu
import com.normation.inventory.domain.Version
import com.normation.inventory.domain.VirtualMachine
import com.normation.inventory.domain.VirtualMachineType
import com.normation.inventory.domain.VmType.VirtualBox
import com.normation.rudder.domain.nodes.MachineInfo
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.nodes.NodeGroupUid
import com.normation.rudder.domain.nodes.NodeKind
import com.normation.rudder.domain.nodes.NodeState
import com.normation.rudder.domain.properties.NodeProperty
import com.normation.rudder.domain.queries._
import com.normation.rudder.facts.nodes.CoreNodeFactRepository
import com.normation.rudder.facts.nodes.IpAddress
import com.normation.rudder.facts.nodes.LocalUser
import com.normation.rudder.facts.nodes.NodeFact
import com.normation.rudder.facts.nodes.NodeFactStorage
import com.normation.rudder.facts.nodes.RudderAgent
import com.normation.rudder.facts.nodes.SoftwareFact
import com.normation.rudder.reports.ReportingConfiguration
import com.normation.utils.DateFormaterService
import com.normation.zio._
import com.softwaremill.quicklens._
import net.liftweb.common._
import org.joda.time.format.DateTimeFormat
import org.junit._
import org.junit.Assert._
import org.junit.runner.RunWith
import org.junit.runners.BlockJUnit4ClassRunner
import scala.util.Try
import zio._
import zio.syntax._

/*
 * Test query parsing.
 *
 * These test doesn't test JSON syntax error, as we rely on
 * a JSON parser for that part.
 */

@RunWith(classOf[BlockJUnit4ClassRunner])
class TestNodeFactQueryProcessor {
  implicit def StringToNodeId(s: String)  = NodeId(s)
  implicit def StringToGroupId(s: String) = NodeGroupId(NodeGroupUid(s))

  val logger = NamedZioLogger(this.getClass.getPackageName + "." + this.getClass.getSimpleName)

  object subGroupComparatorRepo extends SubGroupComparatorRepository {
    val groups = Map(
      (SubGroupChoice("test-group-node1", "Only contains node1"), Chunk[NodeId]("node1")),
      (SubGroupChoice("test-group-node2", "Only contains node2"), Chunk[NodeId]("node2")),
      (SubGroupChoice("test-group-node12", "Only contains node1 and node2"), Chunk[NodeId]("node1", "node2")),
      (SubGroupChoice("test-group-node23", "Only contains node2 and node3"), Chunk[NodeId]("node2", "node3")),
      (SubGroupChoice("AIXSystems", "AIXSystems"), Chunk[NodeId]())
    )

    override def getNodeIds(groupId: NodeGroupId): IOResult[Chunk[NodeId]] = {
      (groups.find(_._1.id == groupId) match {
        case Some(kv) => kv._2
        case None     => Chunk.empty
      }).succeed
    }

    override def getGroups: IOResult[Chunk[SubGroupChoice]] = Chunk.fromIterable(groups.keys).succeed
  }
  val queryData = new NodeQueryCriteriaData(() => subGroupComparatorRepo)

  // load all nodes that in resources: node-facts/*.json
//  java.lang.Runtime.getRuntime.gc()
//  println(s"free memory before: " + java.lang.Runtime.getRuntime.freeMemory())
//  val nodes = File(Resource.getUrl("node-facts").getPath).children.toList.flatMap { f =>
//    if (f.extension != Some(".json")) None
//    else {
//      f.contentAsString(StandardCharsets.UTF_8).fromJson[NodeFact] match {
//        case Left(err) => throw new IllegalArgumentException(s"Unable to read node from file '${f.pathAsString}': ${err}")
//        case Right(n)  => Some(n)
//      }
//    }
//  }

  val nodeRepository = {

    implicit def StringToNodeProp(s: String): NodeProperty = {
      NodeProperty.unserializeLdapNodeProperty(s) match {
        case Left(err)    => throw new IllegalArgumentException(s"Error in test init node property: ${err}")
        case Right(value) => value
      }
    }

    implicit def StringToVersion(s: String) = new Version(s)

    implicit def StringToIp(s: String) = IpAddress(s)

    implicit def LongToMemory(l: Long) = MemorySize(l)

    implicit def StringToSoftwareEditor(s: String) = SoftwareEditor(s)

    implicit def StringToInetAddress(s: String) = {
      com.comcast.ip4s.IpAddress.fromString(s) match {
        case Some(value) => value.toInetAddress
        case None        => throw new IllegalArgumentException(s"Error in test init ip address: ${s}")
      }
    }

    @silent("a type was inferred to be `Object`")
    implicit def StringToDate(s: String) = {
      // we have 3 potentials date format: the common one, '20130515 123456.948Z', and "2015-01-21 17:2"
      val p1 = DateTimeFormat.forPattern("YYYYMMddHHmmss.SSSZ")
      val p2 = DateTimeFormat.forPattern("YYYY-MM-dd HH:mm")
      DateFormaterService.parseDate(s).orElse(Try(p1.parseDateTime(s)).toEither).orElse(Try(p2.parseDateTime(s)).toEither) match {
        case Left(err)    => throw new IllegalArgumentException(s"Error in test init date: ${s}")
        case Right(value) => value
      }
    }

    val emptyReportConf    = ReportingConfiguration(None, None, None)
    val cfe                = RudderAgent(CfeCommunity, "root", AgentVersion("4.1.8"), PublicKey("test"), Chunk.empty)
    val nova               = cfe.copy(agentType = CfeEnterprise)
    val dsc                = RudderAgent(
      Dsc,
      "root",
      AgentVersion("4.2-1.0"),
      Certificate(
        "-----BEGIN CERTIFICATE-----\nMIIFTTCCAzWgAwIBAgIJAL4vbx1mfhs5MA0GCSqGSIb3DQEBCwUAMEcxDzANBgNV\nBAMMBkFHRU5UMjE0MDIGCgmSJomT8ixkAQEMJDM1NWVhY2QxLWU4YjAtNDg4OC1i\nZTFkLTUxMDQ3MTdkZTZjZjAeFw0xNzEwMDQxNjUyMTJaFw0yNzEwMDIxNjUyMTJa\nMEcxDzANBgNVBAMMBkFHRU5UMjE0MDIGCgmSJomT8ixkAQEMJDM1NWVhY2QxLWU4\nYjAtNDg4OC1iZTFkLTUxMDQ3MTdkZTZjZjCCAiIwDQYJKoZIhvcNAQEBBQADggIP\nADCCAgoCggIBALv6AoYF59+F/jGPQq074gf8HZwowysuT4uxFbFpkYBc6FvLuRrZ\nnz0lJZKZOSjWbhndKLFkKgsHBi35ESfblBf4lqBguyCPWKRyGApSJP0cElSNKJsi\nzd0qXorTKV0aEQod2TUjz35Vbl2rYPbt+vIGX0zK0cBhTiSJ8ONLMoCUxeqVXmqL\nSisD6LfR9NH+0+LZ4g0ueQzC+DJncb0wbH66vbg6soykQ2c1XljRgdJHrEgPy43x\nL6WvL6Sb4hlPs7yBHwWTGXsAKjs8kMBON9ijPnS30gQm5flqd8lFd/s1/7yYrXBl\n+e8cOVTorgL1biQb250MRaPp4PL3NvbpLtMaSK8aAdxQSFhMxvCHq+41VbjAkIPG\n9yekMqsbs22BZn4XoTbn5F71FGh39j6cI/BJYOI6sDmnVfWuMTwdGnzh43fNDpch\n7FeuDUTomFkJXFNMZuvuEhLtf39OIknzhszxXrsSG8VSHAS4GdRXSFu9bbtuLm2Q\ngiiVLkE69NUgM+XHM9XKiNY2oDNtVpVrRte4hdH7NgG2LgBs+bYNPI44po4AfGnc\n2ICzC1UXEnNpH0WGVZ4OtBKZlmHLC7RhCXOkTBOX29yBag4jIfDaIYNthDmSX3By\nVoh1/hLrXcTnIzMn31Ku3CKVbYeMBEzmZGLtDSQvoedAgv0VCgf8fRhZAgMBAAGj\nPDA6MAwGA1UdEwQFMAMBAf8wCwYDVR0PBAQDAgK0MB0GA1UdDgQWBBSaV2KqEPqU\n2xWF2ajE/h0a5fB7LzANBgkqhkiG9w0BAQsFAAOCAgEAV7/A+nlL/bWOd906t3QR\nt57hQfgBkNralQyNvsspFaDJM19G+Xi2yhW/Vq9tZNJ0FzbMwp2OwcADmDtnFG/Y\nBa83jU8Cxa/wvQO86KCNK4NlzGjcWNtwGM4135r7M8t3dvx+uXu+AYa96QrVLTUX\n4XsRJWTdf4Qe6zgKuaDfsEr0eDAo6UNe+ZQyPNJoqPERKTTcv8BDimAGCdO0ZAUy\nMd6Cu6WTpMrWvhr/YzvwQm9tJ7GvQoVd3HAyO/+6dZOqFoJmoI6NXB2thSEMJMQ7\nAiniqRO04opf56Z1K0RO8/ECsr81OL4R0j7Bx+SNVGQP+FDDUdiJPZp1SeQgrgSb\nihCQr8zWZtmXZE0UKIAHXsfCFCNr/t4yPgOsAlD1Cs0QRglXr/M15jmpmWlD7IiB\nbx0aOdwH99a2HK7d41v1yoZn4bKdgtbEaPHXViAPnFdcJPQ1+1hm8G2vbhYJgv1d\no+ZgEyZNfamAPCKKyy79JVPeas4alSnBw+RRRKxH4ZAr7E+Urml7JFmoiab0jjGY\nOjgEzRQUOiTdSNvpzJUz71KrQPgR0gIlsjnyu3QOoFXdVtg+MzLyOb4bCmo3mFL2\nsAhdducYbLhNS/IOunspkZJzfgRodgzOj1ZRlTJztP+sdd5M2rJy6awpWL4AwUMP\nyDa4p7g4y2ju9vIh+t4C8qk=\n-----END CERTIFICATE-----"
      ),
      Chunk.empty
    )
    val defaultNodeSetting = com.normation.rudder.facts.nodes.RudderSettings(
      CertifiedKey,
      emptyReportConf,
      NodeKind.Node,
      AcceptedInventory,
      NodeState.Enabled,
      None,
      "root-policy-server"
    )
    val software           = Chunk(SoftwareFact("Software 0", "1.0.0"), SoftwareFact("Software 1", "2.0-rc"))

    def machine(nodeId: String) = MachineInfo(NodeFact.toMachineId(nodeId), VirtualMachineType(VirtualBox), None, None)

    val allAcceptedNodes: Map[NodeId, NodeFact] = {
      Chunk(
        NodeFact(
          "root",
          None,
          "root.normation.com",
          Linux(Ubuntu, "", "nothing", None, "nothing"),
          machine("root"),
          com.normation.rudder.facts.nodes.RudderSettings(
            CertifiedKey,
            emptyReportConf,
            NodeKind.Root,
            AcceptedInventory,
            NodeState.Enabled,
            None,
            "root"
          ),
          cfe,
          Chunk.empty,
          "20130515123456.948Z",
          "20120515123456.948Z",
          Some("20120515123456.948Z"),
          software = Chunk(software(0)),
          fileSystems = Chunk(FileSystem("/", Some("ext3"), None, None, Some(10), Some(803838361699L)))
        ),
        NodeFact(
          "node0",
          Some("matchOnMe"),
          "node0.normation.com",
          Linux(Ubuntu, "", "nothing", None, "nothing"),
          machine("node0"),
          defaultNodeSetting,
          nova,
          Chunk.empty,
          "20130515123456.948Z",
          "20130515123456.948Z",
          Some("20130515123456.948Z"),
          ipAddresses = Chunk("192.168.56.100"),
          software = Chunk()
        ),
        NodeFact(
          "node1",
          Some("#54-Ubuntu SMP Thu Dec 10 17:23:29 UTC 2009"),
          "hasAttributes.normation.com",
          Linux(Ubuntu, "", "Ubuntu 9.10", None, "2.6.18-17-generic"),
          machine("node1"),
          defaultNodeSetting,
          cfe,
          Chunk(
            """{"name":"foo","value":"bar"}""",
            """{"name":"datacenter","value":"Paris", "provider":"inventory"}""",
            """{"name":"from_inv","value":{ "key1":"custom prop value", "key2":"some more json"}, "provider":"inventory"}"""
          ),
          "20130515123456.948Z",
          "20140515123456.948Z",
          Some("20140515123456.948Z"),
          swap = Some(2878000000L),
          ram = Some(100000000L),
          ipAddresses = Chunk("192.168.56.101", "127.0.0.1"),
          localUsers = Chunk(
            LocalUser(0, "root", "root", "/", "/bin/sh"),
            LocalUser(1000, "francois.armand", "francois.armand", "/home/far", "/bin/zsh"),
            LocalUser(1001, "nicolas.charles", "nicolas.charles", "/home/nch", "/bin/bash"),
            LocalUser(1002, "jonathan.clarke", "jonathan.clarke", "/home/jcl", "/bin/bash")
          ),
          software = Chunk(),
          environmentVariables = Chunk(("SHELL", "/bin/sh")),
          processes = Chunk(
            Process(
              1,
              Some("init [2]"),
              Some(0f),
              Some(0.2f),
              Some("2015-01-21 17:24"),
              Some("?"),
              Some("root"),
              Some(0),
              None
            ),
            Process(
              10,
              Some("[kdevtmpfs]"),
              Some(0f),
              Some(0f),
              Some("2015-01-21 17:24"),
              Some("?"),
              Some("root"),
              Some(0),
              None
            )
          ),
          networks = Chunk(
            Network(
              "eth0",
              ifAddresses = Seq("192.168.1.1"),
              ifGateway = Seq("192.168.1.254"),
              macAddress = Some("08:00:27:42:37:be"),
              status = Some("Up")
            )
          )
        ),
        NodeFact(
          "node2",
          None,
          "node2.normation.com",
          Linux(Ubuntu, "", "nothing", None, "nothing"),
          machine("node2"),
          defaultNodeSetting,
          nova,
          Chunk(
            """{"name":"datacenter","value":{"country":"France","id":1234,"replicated":true},"provider":"datasources"}"""
          ),
          "20130515123456.948Z",
          "20150515123456.948Z",
          Some("20150515123456.948Z"),
          Chunk("192.168.56.102", "127.0.0.1"),
          ram = Some(1L),
          localUsers = Chunk(),
          software = Chunk(software(0), software(1)),
          environmentVariables = Chunk(("PWD", "/var/rudder"), ("SUDO_GID", "1000")),
          networks = Chunk(
            Network(
              "eth0",
              ifAddresses = Seq("192.168.1.2"),
              ifGateway = Seq("192.168.1.254"),
              macAddress = Some("08:00:27:42:37:be"),
              status = Some("Up")
            )
          ),
          vms = Chunk(VirtualMachine(uuid = MachineUuid("vm1"), vmtype = Some("vmware"), memory = Some("10000")))
        ),
        NodeFact(
          "node3",
          None,
          "node3.normation.com",
          Linux(Ubuntu, "", "nothing", None, "nothing"),
          machine("node3"),
          defaultNodeSetting,
          cfe,
          Chunk(
            """{"name":"datacenter","value":{"country":"Germany","id":12345,"replicated":true,"provider":"user value"}}""",
            """{"name":"number","value":42}"""
          ),
          "20130515123456.948Z",
          "20160515123456.948Z",
          Some("20160515123456.948Z"),
          Chunk("192.168.56.103", "127.0.0.1"),
          localUsers = Chunk(),
          environmentVariables = Chunk(
            (
              "PATH",
              """/usr/local/sbin:/usr/local/bin:
                | /usr/sbin:/usr/bin:/sbin:/bin:/var/rudder/cfengine-community/bin"""".stripMargin
            )
          ),
          fileSystems = Chunk(
            FileSystem(
              "/",
              Some("ext3"),
              Some("matchOnMe"),
              None,
              Some(6718226432L),
              Some(8038383616L)
            )
          ),
          networks = Chunk(
            Network(
              "eth0",
              ifAddresses = Seq("192.168.1.3")
            )
          ),
          vms = Chunk(VirtualMachine(uuid = MachineUuid("vm2"), vmtype = Some("vmware"), memory = Some("10000000")))
        ),
        NodeFact(
          "node4",
          None,
          "node4.normation.com",
          Linux(Ubuntu, "", "nothing", None, "nothing"),
          MachineInfo(MachineUuid("machine0"), PhysicalMachineType),
          defaultNodeSetting,
          cfe,
          Chunk(
            """{"name":"foo","value":""}""",
            """{"name":"liar","value":{"k":"v","name":"datacenter","value":"I'm not a datacenter!"}}""",
            """{"name":"number","value":42,"provider":"datasources"}""",
            """{"name":"user","value": {
                  "id": "xxxxxx",
                  "accepted": true
                }
               }"""
          ),
          "20130515123456.948Z",
          "20170515123456.948Z",
          Some("20170515123456.948Z"),
          ipAddresses = Chunk("127.0.0.1"),
          environmentVariables = Chunk(("SHELL", "/bin/sh"))
        ),
        NodeFact(
          "node5",
          None,
          "node5.normation.com",
          Linux(Ubuntu, "", "nothing", None, "nothing"),
          MachineInfo(
            MachineUuid("machine1"),
            PhysicalMachineType,
            systemSerial = Some("f47ac10b-58cc-4372-a567-0e02b2c3d479")
          ),
          defaultNodeSetting,
          dsc,
          Chunk(
            """{"name":"user","value": {
              |    "id": "xxxxxx",
              |    "accepted": true,
              |    "personal": {
              |      "name": "Smith Jones",
              |        "address": {
              |            "streetaddress": "7 24th Street",
              |            "city": "New York",
              |            "state": "NY",
              |            "postalcode": 10038
              |        },
              |        "phones": [
              |          {"type":"home","number":"(541) 754-3010"},
              |          {"type":"mobile","number":"(541) 754-9999"}
              |        ]
              |    }
              |  }
              | }""".stripMargin
          ),
          "20130515123456.948Z",
          "20180515123456.948Z",
          Some("20180515123456.948Z"),
          ipAddresses = Chunk()
        ),
        NodeFact(
          "node6",
          None,
          "node6.normation.com",
          Linux(Ubuntu, "", "nothing", None, "nothing"),
          MachineInfo(
            MachineUuid("machine2"),
            PhysicalMachineType
          ),
          defaultNodeSetting,
          cfe,
          Chunk(
            """{"name":"user","value": {
              |    "id": "yyyyy",
              |    "accepted": false,
              |    "personal": {
              |      "name": "Alice All",
              |        "address": {
              |            "streetaddress": "10th on the big Street",
              |            "city": "Los Angeles",
              |            "state": "CA",
              |            "postalcode": 90003
              |        },
              |        "phones": [
              |          {"type":"home","number":"(111) 123-3010"},
              |          {"type":"mobile","number":"(111) 256-9999"}
              |        ]
              |    }
              |  }
              | }""".stripMargin
          ),
          "20130515123456.948Z",
          "20190515123456.948Z",
          Some("20190515123456.948Z"),
          ipAddresses = Chunk(),
          bios = Chunk(Bios("bios1", version = Some("6.00"), editor = Some("Phoenix Technologies LTD")))
        ),
        NodeFact(
          "node7",
          None,
          "node7.normation.com",
          Linux(Ubuntu, "", "nothing", None, "nothing"),
          MachineInfo(
            MachineUuid("machine2"),
            PhysicalMachineType
          ),
          defaultNodeSetting.copy(state = NodeState.Initializing),
          cfe,
          Chunk(),
          "20130515123456.948Z",
          "20200515123456.948Z",
          Some("20200515123456.948Z"),
          ipAddresses = Chunk(),
          fileSystems = Chunk(FileSystem("/", Some("ext3"), None, None, Some(10), Some(803838361699L))),
          software = Chunk(software(0)),
          bios = Chunk(Bios("bios1", version = Some("6.00"), editor = Some("Phoenix Technologies LTD")))
        )
      ).map(n => (n.id, n)).toMap
    }
    object NoopStorage extends NodeFactStorage {
      override def save(nodeFact: NodeFact):                              IOResult[Unit] = ZIO.unit
      override def changeStatus(nodeId: NodeId, status: InventoryStatus): IOResult[Unit] = ZIO.unit
      override def delete(nodeId: NodeId):                                IOResult[Unit] = ZIO.unit
    }
    CoreNodeFactRepository.make(NoopStorage, Map(), allAcceptedNodes, Chunk.empty).runNow
  }

  val queryProcessor = new NodeFactQueryProcessor(nodeRepository, subGroupComparatorRepo)

  val parser = new CmdbQueryParser with DefaultStringQueryParser with JsonQueryLexer {
    override val criterionObjects = queryData.criteriaMap.toMap
  }

  case class TestQuery(name: String, query: Query, awaited: Seq[NodeId])

  // when one need to debug search, you can just uncomment that to set log-level to trace
  org.slf4j.LoggerFactory
    .getLogger("query.node-fact")
    .asInstanceOf[ch.qos.logback.classic.Logger]
    .setLevel(ch.qos.logback.classic.Level.TRACE)

  val s = Seq(
    new NodeId("node0"),
    new NodeId("node1"),
    new NodeId("node2"),
    new NodeId("node3"),
    new NodeId("node4"),
    new NodeId("node5"),
    new NodeId("node6"),
    new NodeId("node7")
  )

  val root = NodeId("root")
  val sr   = root +: s

  @Test def basicQueriesOnId(): Unit = {

    /* find back all server */
    val q0 = TestQuery(
      "q0",
      parser("""
      {  "select":"node", "where":[
        { "objectType":"node"   , "attribute":"nodeId"  , "comparator":"exists" }
      ] }
      """).openOrThrowException("For tests"),
      s
    )

    /* find back server 1 and 5 by id */
    val q1 = TestQuery(
      "q1",
      parser("""
      { "select":"node", "composition":"or", "where":[
         { "objectType":"node"   , "attribute":"nodeId"  , "comparator":"eq", "value":"node1" }
       , { "objectType":"node"   , "attribute":"nodeId"  , "comparator":"eq", "value":"node5" }
      ] }
      """).openOrThrowException("For tests"),
      s(1) :: s(5) :: Nil
    )

    /* find back neither server 1 and 5 by id because of the and */
    val q2 = TestQuery(
      "q2",
      parser("""
      {  "select":"node", "composition":"and", "where":[
        { "objectType":"node"   , "attribute":"nodeId"  , "comparator":"eq", "value":"node1" }
        { "objectType":"node"   , "attribute":"nodeId"  , "comparator":"eq", "value":"node5" }
      ] }
      """).openOrThrowException("For tests"),
      Nil
    )

    testQueries(q0 :: q1 :: q2 :: Nil, false)
  }

  @Test def basicQueriesOnOneNodeParameter(): Unit = {
    // only two servers have RAM: server1(RAM) = 10000000, server2(RAM) = 1

    val q2_0 = TestQuery(
      "q2_0",
      parser("""
      {  "select":"node", "where":[
        { "objectType":"node", "attribute":"ram", "comparator":"gt", "value":"1" }
      ] }
      """).openOrThrowException("For tests"),
      s(1) :: Nil
    )

    val q2_0_ = TestQuery(
      "q2_0_",
      query = q2_0.query.copy(composition = Or),
      q2_0.awaited
    )

    val q2_1 = TestQuery(
      "q2_1",
      parser("""
      {  "select":"node", "where":[
        { "objectType":"node", "attribute":"ram", "comparator":"gteq", "value":"1" }
      ] }
      """).openOrThrowException("For tests"),
      s(1) :: s(2) :: Nil
    )

    val q2_1_ = TestQuery(
      "q2_1_",
      query = q2_1.query.copy(composition = Or),
      q2_1.awaited
    )

    val q2_2 = TestQuery(
      "q2_2",
      parser("""
      {  "select":"node", "where":[
        { "objectType":"node", "attribute":"ram", "comparator":"lteq", "value":"1" }
      ] }
      """).openOrThrowException("For tests"),
      s(2) :: Nil
    )

    val q2_2_ = TestQuery(
      "q2_2_",
      query = q2_2.query.copy(composition = Or),
      q2_2.awaited
    )

    testQueries(q2_0 :: q2_0_ :: q2_1 :: q2_1_ :: q2_2 :: q2_2_ :: Nil, true)
  }

  // group of group, with or/and composition
  @Test def groupOfgroups(): Unit = {
    val q1 = TestQuery(
      "q1",
      parser("""
      {  "select":"node", "where":[
        { "objectType":"group", "attribute":"nodeGroupId", "comparator":"eq", "value":"test-group-node1" }
      ] }
      """).openOrThrowException("For tests"),
      s(1) :: Nil
    )

    val q2 = TestQuery(
      "q2",
      parser("""
      {  "select":"node", "composition":"or", "where":[
        { "objectType":"group", "attribute":"nodeGroupId", "comparator":"eq", "value":"test-group-node1" }
      , { "objectType":"group", "attribute":"nodeGroupId", "comparator":"eq", "value":"test-group-node2" }
      ] }
      """).openOrThrowException("For tests"),
      s(1) :: s(2) :: Nil
    )

    val q3 = TestQuery(
      "q3",
      parser("""
      {  "select":"node", "where":[
        { "objectType":"group", "attribute":"nodeGroupId", "comparator":"eq", "value":"test-group-node1" }
      , { "objectType":"node"   , "attribute":"nodeId"  , "comparator":"eq", "value":"node1" }
      ] }
      """).openOrThrowException("For tests"),
      s(1) :: Nil
    )

    val q4 = TestQuery(
      "q4",
      parser("""
      {  "select":"node", "where":[
        { "objectType":"group", "attribute":"nodeGroupId", "comparator":"eq", "value":"test-group-node1" }
      , { "objectType":"group", "attribute":"nodeGroupId", "comparator":"eq", "value":"test-group-node12" }
      ] }
      """).openOrThrowException("For tests"),
      s(1) :: Nil
    )

    val q5 = TestQuery(
      "q5",
      parser("""
      {  "select":"node", "where":[
        { "objectType":"group", "attribute":"nodeGroupId", "comparator":"eq", "value":"test-group-node12" }
      , { "objectType":"group", "attribute":"nodeGroupId", "comparator":"eq", "value":"test-group-node23" }
      ] }
      """).openOrThrowException("For tests"),
      s(2) :: Nil
    )

    val q6 = TestQuery(
      "q6",
      parser("""
      {  "select":"node", "composition":"And", "where":[
        { "objectType":"group", "attribute":"nodeGroupId", "comparator":"eq", "value":"AIXSystems" }
      , { "objectType":"node", "attribute":"OS", "comparator":"eq", "value":"Linux"}
      ] }
      """).openOrThrowException("For tests"),
      Nil
    )

    val q7 = TestQuery(
      "q7",
      parser("""
      {  "select":"node", "composition":"Or", "where":[
        { "objectType":"group", "attribute":"nodeGroupId", "comparator":"eq", "value":"AIXSystems" }
      , { "objectType":"node", "attribute":"OS", "comparator":"eq", "value":"Linux"}
      ] }
      """).openOrThrowException("For tests"),
      s(0) :: s(1) :: s(2) :: s(3) :: s(4) :: s(5) :: s(6) :: s(7) :: Nil
    )

    /*
     * Testing groups and physical query
     */
    val q8 = TestQuery(
      "q8",
      parser("""
      {  "select":"node", "composition":"Or", "where":[
        { "objectType":"group", "attribute":"nodeGroupId", "comparator":"eq", "value":"test-group-node12" }
      , { "objectType":"networkInterfaceLogicalElement", "attribute":"networkInterfaceGateway", "comparator":"regex", "value":".*192.168.*" }
      ] }
      """).openOrThrowException("For tests"),
      s(1) :: s(2) :: Nil
    )

    val q9 = TestQuery(
      "q9",
      parser("""
      {  "select":"node", "composition":"And", "where":[
        { "objectType":"group", "attribute":"nodeGroupId", "comparator":"eq", "value":"test-group-node12" }
      , { "objectType":"networkInterfaceLogicalElement", "attribute":"networkInterfaceGateway", "comparator":"regex", "value":".*192.168.*" }
      ] }
      """).openOrThrowException("For tests"),
      s(1) :: s(2) :: Nil
    )

    val q10 = TestQuery(
      "q10",
      parser("""
      {  "select":"node", "composition":"And", "where":[
        { "objectType":"group", "attribute":"nodeGroupId", "comparator":"eq", "value":"test-group-node1" }
      , { "objectType":"networkInterfaceLogicalElement", "attribute":"networkInterfaceGateway", "comparator":"regex", "value":".*192.168.*" }
      , { "objectType":"node"   , "attribute":"nodeId"  , "comparator":"eq", "value":"node1" }
      ] }
      """).openOrThrowException("For tests"),
      s(1) :: Nil
    )

    testQueries(q1 :: q2 :: q3 :: q4 :: q5 :: q6 :: q7 :: q8 :: q9 :: q10 :: Nil, false)
  }

  // group of group, with or/and composition
  @Test def groupOfgroupsDoIntenalQueryTest(): Unit = {
    val q1 = TestQuery(
      "q1",
      parser("""
      {  "select":"node", "where":[
        { "objectType":"group", "attribute":"nodeGroupId", "comparator":"eq", "value":"test-group-node1" }
      ] }
      """).openOrThrowException("For tests"),
      s(1) :: Nil
    )

    val q2 = TestQuery(
      "q2",
      parser("""
      {  "select":"node", "composition":"or", "where":[
        { "objectType":"group", "attribute":"nodeGroupId", "comparator":"eq", "value":"test-group-node1" }
      , { "objectType":"group", "attribute":"nodeGroupId", "comparator":"eq", "value":"test-group-node2" }
      ] }
      """).openOrThrowException("For tests"),
      s(1) :: s(2) :: Nil
    )

    val q3 = TestQuery(
      "q3",
      parser("""
      {  "select":"node", "where":[
        { "objectType":"group", "attribute":"nodeGroupId", "comparator":"eq", "value":"test-group-node1" }
      , { "objectType":"node", "attribute":"ram", "comparator":"gt", "value":"1" }
      ] }
      """).openOrThrowException("For tests"),
      s(1) :: Nil
    )

    val q4 = TestQuery(
      "q4",
      parser("""
      {  "select":"node", "where":[
        { "objectType":"group", "attribute":"nodeGroupId", "comparator":"eq", "value":"test-group-node1" }
      , { "objectType":"group", "attribute":"nodeGroupId", "comparator":"eq", "value":"test-group-node12" }
      ] }
      """).openOrThrowException("For tests"),
      s(1) :: Nil
    )

    val q5 = TestQuery(
      "q5",
      parser("""
      {  "select":"node", "where":[
        { "objectType":"group", "attribute":"nodeGroupId", "comparator":"eq", "value":"test-group-node12" }
      , { "objectType":"group", "attribute":"nodeGroupId", "comparator":"eq", "value":"test-group-node23" }
      ] }
      """).openOrThrowException("For tests"),
      s(2) :: Nil
    )

    testQueries(q1 :: q2 :: q3 :: q4 :: q5 :: Nil, true)
  }

  @Test def machineComponentQueries(): Unit = {
    val q3 = TestQuery(
      "q3",
      parser("""
      { "select":"node", "where":[
      { "objectType":"biosPhysicalElement", "attribute":"softwareVersion", "comparator":"eq", "value":"6.00" }
      ] }
      """).openOrThrowException("For tests"),
      s(6) :: s(7) :: Nil
    )

    testQueries(q3 :: Nil, true)
  }

  @Test def softwareQueries(): Unit = {

    val q1 = TestQuery(
      "q1",
      parser("""
      { "select":"node", "where":[
        { "objectType":"software", "attribute":"softwareVersion", "comparator":"eq", "value":"1.0.0" }
      ] }
      """).openOrThrowException("For tests"),
      s(2) :: s(7) :: Nil
    )

    val q2 = TestQuery(
      "q2",
      parser("""
      { "select":"node", "where":[
        { "objectType":"software", "attribute":"softwareVersion", "comparator":"eq", "value":"2.0-rc" }
      ] }
      """).openOrThrowException("For tests"),
      s(2) :: Nil
    )

    testQueries(q1 :: q2 :: Nil, true)
  }

  @Test def logicalElementQueries(): Unit = {

    val q1 = TestQuery(
      "q1",
      parser("""
      { "select":"node", "where":[
        { "objectType":"fileSystemLogicalElement", "attribute":"fileSystemFreeSpace", "comparator":"gteq", "value":"1" }
      ] }
      """).openOrThrowException("For tests"),
      s(3) :: s(7) :: Nil
    )

    val q2 = TestQuery(
      "q2",
      parser("""
      { "select":"node", "where":[
        { "objectType":"fileSystemLogicalElement", "attribute":"fileSystemFreeSpace", "comparator":"gteq", "value":"100" }
      ] }
      """).openOrThrowException("For tests"),
      s(3) :: Nil
    )

    val q3 = TestQuery(
      "q3",
      parser("""
      { "select":"node", "where":[
        { "objectType":"virtualMachineLogicalElement", "attribute":"vmMemory", "comparator":"gteq", "value":"100" }
      ] }
      """).openOrThrowException("For tests"),
      s(2) :: s(3) :: Nil
    )

    val q3bis = TestQuery(
      "q3bis",
      parser("""
      { "select":"node", "where":[
        { "objectType":"virtualMachineLogicalElement", "attribute":"vmMemory", "comparator":"lteq", "value":"10000" }
      ] }
      """).openOrThrowException("For tests"),
      s(2) :: Nil
    )

    testQueries(q1 :: q2 :: q3 :: q3bis :: Nil, true)
  }

  @Test def networkInterfaceElementQueries(): Unit = {

    val q1 = TestQuery(
      "q1",
      parser("""
      { "select":"node", "where":[
        { "objectType":"networkInterfaceLogicalElement", "attribute":"networkInterfaceGateway", "comparator":"regex", "value":".*192.168.*" }
      ] }
      """).openOrThrowException("For tests"),
      s(1) :: s(2) :: Nil
    )

    val q2 = TestQuery(
      "q2",
      parser("""
      { "select":"node", "where":[
        { "objectType":"networkInterfaceLogicalElement", "attribute":"ipHostNumber", "comparator":"regex", "value":".*192.168.*" }
      ] }
      """).openOrThrowException("For tests"),
      s(1) :: s(2) :: s(3) :: Nil
    )

    testQueries(q1 :: q2 :: Nil, true)
  }

  @Test def regexQueries(): Unit = {

    // regex and "subqueries" for logical elements should not be contradictory
    // here, we have to *only* search for logical elements with the regex
    // and cn is both on node and logical elements
    val q0 = TestQuery(
      "q0",
      parser("""
      {  "select":"node", "composition":"or" , "where":[
        , { "objectType":"fileSystemLogicalElement", "attribute":"description", "comparator":"regex", "value":"matchOnM[e]" }
      ] }
      """).openOrThrowException("For tests"),
      s(3) :: Nil
    )

    // on node
    val q1 = TestQuery(
      "q1",
      parser("""
      {  "select":"node", "where":[
          { "objectType":"node" , "attribute":"ram"    , "comparator":"regex", "value":"[0-9]{9}" }
        , { "objectType":"node" , "attribute":"osKernelVersion" , "comparator":"regex", "value":"[0-9.-]+-(gen)eric" }
        , { "objectType":"node" , "attribute":"nodeId" , "comparator":"regex", "value":"[nN]ode[01]" }
      ] }
      """).openOrThrowException("For tests"),
      s(1) :: Nil
    )

    val q1_ = TestQuery(
      "q1_",
      query = q1.query.copy(composition = Or),
      s(0) :: s(1) :: Nil
    )

    // on node software, machine, machine element, node element
    val q2 = TestQuery(
      "q2",
      parser("""
      {  "select":"nodeAndPolicyServer", "where":[
          { "objectType":"node" , "attribute":"nodeId" , "comparator":"regex", "value":"[nN]ode[017]" }
        , { "objectType":"software", "attribute":"cn", "comparator":"regex"   , "value":"Software [0-9]" }
        , { "objectType":"machine", "attribute":"machineId", "comparator":"regex" , "value":"machine[0-2]"  }
        , { "objectType":"fileSystemLogicalElement", "attribute":"fileSystemFreeSpace", "comparator":"regex", "value":"[01]{2}" }
        , { "objectType":"biosPhysicalElement", "attribute":"softwareVersion", "comparator":"regex", "value":"[6.0]+" }
      ] }
      """).openOrThrowException("For tests"),
      s(7) :: Nil
    )

    val q2_ = TestQuery(
      "q2_",
      query = q2.query.copy(composition = Or),
      (s(0) :: s(1) :: s(7) ::        // nodeId
      s(2) :: s(7) ::                 // software
      s(4) :: s(5) :: s(6) :: s(7) :: // machine
      s(2) :: root ::                 // free space
      s(2) ::                         // bios
      Nil).distinct
    )

    // on node and or for regex
    val q3 = TestQuery(
      "q3",
      parser("""
      {  "select":"node",  "composition":"or", "where":[
          { "objectType":"node" , "attribute":"nodeId" , "comparator":"regex", "value":"[nN]ode[01]" }
        , { "objectType":"node" , "attribute":"nodeId" , "comparator":"regex", "value":"[nN]ode[12]" }
      ] }
      """).openOrThrowException("For tests"),
      s(0) :: s(1) :: s(2) :: Nil
    )

    // on node and or for regex, testing #3340
    val q3_2 = TestQuery(
      "q3_2",
      parser("""
      {  "select":"node",  "composition":"or", "where":[
          { "objectType":"node" , "attribute":"nodeId" , "comparator":"regex", "value":"[nN]ode[01]" }
        , { "objectType":"node" , "attribute":"nodeId" , "comparator":"eq"   , "value":"node5" }
        , { "objectType":"software", "attribute":"softwareVersion", "comparator":"eq", "value":"1.0.0" }
      ] }
      """).openOrThrowException("For tests"),
      s(0) :: s(1) :: s(2) :: s(5) :: s(7) :: Nil
    )

    // same as q3 with and
    val q4 = TestQuery(
      "q4",
      parser("""
      {  "select":"node",  "composition":"and", "where":[
          { "objectType":"node" , "attribute":"nodeId" , "comparator":"regex", "value":"[nN]ode[01]" }
        , { "objectType":"node" , "attribute":"nodeId" , "comparator":"regex", "value":"[nN]ode[12]" }
      ] }
      """).openOrThrowException("For tests"),
      s(1) :: Nil
    )

    val q5 = TestQuery(
      "q5",
      parser("""
      {  "select":"nodeAndPolicyServer","composition":"or",  "where":[
        , { "objectType":"fileSystemLogicalElement" , "attribute":"mountPoint" , "comparator":"regex", "value":"[/]" }
      ] }
      """).openOrThrowException("For tests"),
      s(3) :: s(7) :: root :: Nil
    )

    // test regex for "not containing word", see http://stackoverflow.com/questions/406230/regular-expression-to-match-string-not-containing-a-word
    // here, we don't want to have node0 or node1
    val q6 = TestQuery(
      "q6",
      parser("""
      {  "select":"node", "where":[
          { "objectType":"node" , "attribute":"nodeId" , "comparator":"regex", "value":"((?!node0|node1).)*" }
      ] }
      """).openOrThrowException("For tests"),
      s.tail.tail
    )

    // same as q5, but with "not regex"
    val q7 = TestQuery(
      "q7",
      parser("""
      {  "select":"node", "where":[
          { "objectType":"node" , "attribute":"nodeId" , "comparator":"notRegex", "value":"node0" }
      ] }
      """).openOrThrowException("For tests"),
      s.tail
    )

    // same as q5 on IP, to test with escaping
    // 192.168.56.101 is for node3
    val q8 = TestQuery(
      "q8",
      parser("""
      {  "select":"node", "where":[
          { "objectType":"node" , "attribute":"ipHostNumber" , "comparator":"notRegex", "value":"192.168.56.101" }
      ] }
      """).openOrThrowException("For tests"),
      s.filterNot(_ == s(1))
    )

    // typical use case for server on internal/dmz/both: want intenal (but not both)
    // that test a match regex and not regex
    val q9 = TestQuery(
      "q9",
      parser("""
      {  "select":"node", "where":[
          { "objectType":"node" , "attribute":"ipHostNumber" , "comparator":"regex", "value":"127.0.0.*" }
        , { "objectType":"node" , "attribute":"ipHostNumber" , "comparator":"notRegex", "value":"192.168.56.10[23]" }
      ] }
      """).openOrThrowException("For tests"),
      Seq(s(1), s(4))
    )
    // s0,5,6,7,8 not ok because no 127.0.0.1
    // s1 ok because not in "not regex" pattern
    // s2,s3 not ok because in the "not regex" pattern
    // s4 ok because only 127.0.0.1

    // test query that matches a software version
    val q10 = TestQuery(
      "q10",
      parser("""
      { "select":"node", "where":[
        { "objectType":"software", "attribute":"softwareVersion", "comparator":"regex", "value":"1\\.0.*" }
      ] }
      """).openOrThrowException("For tests"),
      Seq(s(2), s(7))
    )

    // test "notRegex" query: "I want node for which ram is not "100000000" (ie not node1)
    val q11 = TestQuery(
      "q11",
      parser("""
      { "select":"node", "where":[
        { "objectType":"node", "attribute":"ram", "comparator":"notRegex", "value":"100000000" }
      ] }
      """).openOrThrowException("For tests"),
      s.filterNot(n => n == s(1))
    )

    testQueries(q0 :: q1 :: q1_ :: q2 :: q2_ :: q3 :: q3_2 :: q4 :: q5 :: q6 :: q7 :: q8 :: q9 :: q10 :: q11 :: Nil, false)
  }

  @Test def regexQueriesInventories(): Unit = {
    // this test if for the queries that can be performed using only LDAP
    // regex and "subqueries" for logical elements should not be contradictory
    // here, we have to *only* search for logical elements with the regex
    // and cn is both on node and logical elements
    val q0 = TestQuery(
      "q0",
      parser("""
      {  "select":"node", "composition":"or" , "where":[
        , { "objectType":"fileSystemLogicalElement", "attribute":"description", "comparator":"regex", "value":"matchOnM[e]" }
      ] }
      """).openOrThrowException("For tests"),
      s(3) :: Nil
    )

    // on software, machine, machine element, node element
    val q2 = TestQuery(
      "q2",
      parser("""
      {  "select":"nodeAndPolicyServer", "where":[
          { "objectType":"software", "attribute":"cn", "comparator":"regex"   , "value":"Software [0-9]" }
        , { "objectType":"machine", "attribute":"machineId", "comparator":"regex" , "value":"machine[0-2]"  }
        , { "objectType":"fileSystemLogicalElement", "attribute":"fileSystemFreeSpace", "comparator":"regex", "value":"[01]{2}" }
        , { "objectType":"biosPhysicalElement", "attribute":"softwareVersion", "comparator":"regex", "value":"[6.0]+" }
      ] }
      """).openOrThrowException("For tests"),
      s(7) :: Nil
    )

    val q2_ = TestQuery(
      "q2_",
      query = q2.query.copy(composition = Or),
      (s(2) :: s(7) ::                // software
      s(4) :: s(5) :: s(6) :: s(7) :: // machine
      s(7) :: root ::                 // free space
      s(2) ::                         // bios
      Nil).distinct
    )

    val q5 = TestQuery(
      "q5",
      parser("""
      {  "select":"nodeAndPolicyServer","composition":"or",  "where":[
        , { "objectType":"fileSystemLogicalElement" , "attribute":"mountPoint" , "comparator":"regex", "value":"[/]" }
      ] }
      """).openOrThrowException("For tests"),
      s(3) :: s(7) :: root :: Nil
    )

    // same as q5 on IP, to test with escaping
    // 192.168.56.101 is for node3
    val q8 = TestQuery(
      "q8",
      parser("""
      {  "select":"node", "where":[
          { "objectType":"node" , "attribute":"ipHostNumber" , "comparator":"notRegex", "value":"192.168.56.101" }
      ] }
      """).openOrThrowException("For tests"),
      s.filterNot(_ == s(1))
    )

    // typical use case for server on internal/dmz/both: want intenal (but not both)
    // that test a match regex and not regex
    val q9 = TestQuery(
      "q9",
      parser("""
      {  "select":"node", "where":[
          { "objectType":"node" , "attribute":"ipHostNumber" , "comparator":"regex", "value":"127.0.0.*" }
        , { "objectType":"node" , "attribute":"ipHostNumber" , "comparator":"notRegex", "value":"192.168.56.10[23]" }
      ] }
      """).openOrThrowException("For tests"),
      Seq(s(1), s(4))
    )
    // s0,5,6,7,8 not ok because no 127.0.0.1
    // s1 ok because not in "not regex" pattern
    // s2,s3 not ok because in the "not regex" pattern
    // s4 ok because only 127.0.0.1

    // test query that matches a software version
    val q10 = TestQuery(
      "q10",
      parser("""
      { "select":"node", "where":[
        { "objectType":"software", "attribute":"softwareVersion", "comparator":"regex", "value":"1\\.0.*" }
      ] }
      """).openOrThrowException("For tests"),
      Seq(s(2), s(7))
    )

    // test "notRegex" query: "I want node for which ram is not "100000000" (ie not node1)
    val q11 = TestQuery(
      "q11",
      parser("""
      { "select":"node", "where":[
        { "objectType":"node", "attribute":"ram", "comparator":"notRegex", "value":"100000000" }
      ] }
      """).openOrThrowException("For tests"),
      s.filterNot(n => n == s(1))
    )

    // test query that doesn't match a software name, ie we want all nodes on which "software 1" is not
    // installed (we don't care if there is 0 or 1000 other software)
    // THIS DOES NOT WORK DUE TO: https://issues.rudder.io/issues/19137
    //    val q12 = TestQuery(
    //      "q12",
    //      parser("""
    //      { "select":"node", "composition":"or", "where":[
    //        { "objectType":"software", "attribute":"cn", "comparator":"notRegex", "value":"Software 1" }
    //      ] }
    //      """).openOrThrowException("For tests"),
    //      s.filterNot(n => n == s(2)) )

    testQueries(q0 :: q2 :: q2_ :: q5 :: q8 :: q9 :: q10 :: q11 :: Nil, true)
  }

  @Test def invertQueries(): Unit = {
    // soft0: root, node2, node7
    // soft1: node2

    // test inverting queries
    // try workaround for https://issues.rudder.io/issues/19137
    val q0 = TestQuery(
      "q0",
      parser("""
      { "select":"node", "composition":"or", "transform":"invert", "where":[
        { "objectType":"software", "attribute":"cn", "comparator":"regex", "value":"Software 1" }
      ] }
      """).openOrThrowException("For tests"),
      s.filterNot(n => n == s(2))
    )

    val q1 = TestQuery(
      "q1",
      parser("""
      { "select":"nodeAndPolicyServer", "composition":"or", "transform":"invert", "where":[
        { "objectType":"software", "attribute":"cn", "comparator":"regex", "value":"Software 1" }
      ] }
      """).openOrThrowException("For tests"),
      sr.filterNot(n => n == s(2))
    )

    // invert works ok for include system or not
    val q2 = TestQuery(
      "q2",
      parser("""
      { "select":"node", "composition":"or", "transform":"invert", "where":[
        { "objectType":"software", "attribute":"cn", "comparator":"regex", "value":"Software 0" }
      ] }
      """).openOrThrowException("For tests"),
      s.filterNot(n => Set(s(2), s(7)).contains(n))
    )

    // invert works ok for include system or not
    val q3 = TestQuery(
      "q3",
      parser("""
      { "select":"nodeAndPolicyServer", "composition":"or", "transform":"invert", "where":[
        { "objectType":"software", "attribute":"cn", "comparator":"regex", "value":"Software 0" }
      ] }
      """).openOrThrowException("For tests"),
      sr.filterNot(n => Set(s(2), s(7), root).contains(n))
    )

    // nothing (no software has that name) is inverted to all
    val q4 = TestQuery(
      "q4",
      parser("""
      { "select":"nodeAndPolicyServer", "composition":"or", "transform":"invert", "where":[
        { "objectType":"software", "attribute":"cn", "comparator":"regex", "value":"Software XXX" }
      ] }
      """).openOrThrowException("For tests"),
      sr
    )

    testQueries(q0 :: q1 :: q2 :: q3 :: q4 :: Nil, true)
  }

  @Test def dateQueries(): Unit = {
    // the node inventory date is 15/05/2013

    def q(name: String, comp: String, day: Int, expects: Seq[NodeId]) = TestQuery(
      name,
      parser("""
          {  "select":"nodeAndPolicyServer", "where":[
            { "objectType":"node", "attribute":"inventoryDate", "comparator":"%s"   , "value":"%s/05/2013" }
          ] }
          """.format(comp, day)).openOrThrowException("For tests"),
      expects
    )

    // nodes are going year by year [root=2012-05-15, s0=2013-05-15 <- select date, s1=2014-05-15 etc]
    def query(name: String, comp: String, day: Int, nodes: Seq[NodeId]) = q(name, comp, day, nodes)

    // root is not part of 's", no need to filter it out
    testQueries(
      query("q1", "eq", 15, s(0) :: Nil)
      :: query("q2", "eq", 14, Nil)
      :: query("q3", "eq", 16, Nil)
      :: query("q4", "gteq", 15, s)
      :: query("q5", "gteq", 16, s.filterNot(x => x == s(0)))
      :: query("q6", "lteq", 15, root :: s(0) :: Nil)
      :: query("q7", "lteq", 14, root :: Nil)
      :: query("q8", "lt", 15, root :: Nil)
      :: query("q9", "lt", 16, root :: s(0) :: Nil)
      :: query("q10", "gt", 15, s.filterNot(x => x == s(0)))
      :: query("q11", "gt", 14, s)
      :: q("q12", "notEq", 15, root +: s.filterNot(_ == s(0)))
      :: q("q13", "notEq", 14, root +: s)
      :: q("q14", "notEq", 16, root +: s)
      :: Nil,
      true
    )
  }

  @Test def policyServerQueriesOnId(): Unit = {

    val q0 = TestQuery(
      "q0",
      parser("""
      {  "select":"nodeAndPolicyServer", "where":[
        { "objectType":"node"   , "attribute":"nodeId"  , "comparator":"exists" }
      ] }
      """).openOrThrowException("For tests"),
      sr
    )

    val q1 = TestQuery(
      "q1",
      parser("""
      {  "select":"nodeAndPolicyServer", "composition":"or", "where":[
        { "objectType":"node"   , "attribute":"nodeId"  , "comparator":"exists" }
      ] }
      """).openOrThrowException("For tests"),
      sr
    )

    testQueries(q0 :: q1 :: Nil, false)
  }

  @Test def agentTypeQueries: Unit = {

    val allCfengine = TestQuery(
      "allCfengine",
      parser("""
      {  "select":"nodeAndPolicyServer", "where":[
        { "objectType":"node" , "attribute":"agentName"  , "comparator":"eq", "value":"cfengine" }
      ] }
      """).openOrThrowException("For tests"),
      root :: sr(1) :: sr(2) :: sr(3) :: sr(4) :: sr(5) :: sr(7) :: sr(8) :: Nil
    )

    val community = TestQuery(
      "community",
      parser("""
      {  "select":"nodeAndPolicyServer", "composition":"or", "where":[
        { "objectType":"node"   , "attribute":"agentName"  , "comparator":"eq", "value":"community" }
      ] }
      """).openOrThrowException("For tests"),
      root :: sr(2) :: sr(4) :: sr(5) :: sr(7) :: sr(8) :: Nil
    )

    val nova = TestQuery(
      "nova",
      parser("""
      {  "select":"nodeAndPolicyServer", "composition":"or", "where":[
        { "objectType":"node" , "attribute":"agentName"  , "comparator":"eq", "value":"nova" }
      ] }
      """).openOrThrowException("For tests"),
      sr(1) :: sr(3) :: Nil
    )

    val dsc = TestQuery(
      "dsc",
      parser("""
      {  "select":"nodeAndPolicyServer", "composition":"or", "where":[
        { "objectType":"node", "attribute":"agentName"  , "comparator":"eq", "value":"dsc" }
      ] }
      """).openOrThrowException("For tests"),
      sr(6) :: Nil
    )

    val notCfengine = TestQuery(
      "notCfengine",
      parser("""
      {  "select":"nodeAndPolicyServer", "composition":"or", "where":[
        { "objectType":"node", "attribute":"agentName"  , "comparator":"notEq", "value":"cfengine" }
      ] }
      """).openOrThrowException("For tests"),
      sr(6) :: Nil
    )

    testQueries(allCfengine :: community :: nova :: dsc :: notCfengine :: Nil, true)
  }

  /**
   * Test environment variable
   */
  @Test def nodeJsonFixedKeyQueries(): Unit = {

    val q1 = TestQuery(
      "q1",
      parser("""
      {"select":"node","composition":"And","where":[
        {"objectType":"process","attribute":"started","comparator":"eq","value":"2015-01-21 17:24"}
      ]}
      """).openOrThrowException("For tests"),
      s(1) :: Nil
    )

    val q2 = TestQuery(
      "q2",
      parser("""
      {"select":"node","composition":"And","where":[
        {"objectType":"process","attribute":"commandName","comparator":"regex","value":".*vtmp.*"}
      ]}
      """).openOrThrowException("For tests"),
      s(1) :: Nil
    )

    testQueries(q1 :: q2 :: Nil, true)
  }

  /**
   * Test environment variable and nodeProperty
   */
  @Test def nodeNameValueQueries(): Unit = {

    val q1 = TestQuery(
      "q1",
      parser("""
      {"select":"node","composition":"And","where":[
        {"objectType":"environmentVariable","attribute":"name.value","comparator":"eq","value":"SHELL=/bin/sh"}
      ]}
      """).openOrThrowException("For tests"),
      s(1) :: s(4) :: Nil
    )

    val q2 = TestQuery(
      "q2",
      parser("""
      {"select":"node","composition":"And","where":[
        {"objectType":"environmentVariable","attribute":"name.value","comparator":"regex","value":".+=/.*/rudder.*"}
      ]}
      """).openOrThrowException("For tests"),
      s(2) :: s(3) :: Nil
    )

    testQueries(q1 :: q2 :: Nil, true)
  }

  @Test def nodeStateQueries(): Unit = {

    val q1 = TestQuery(
      "q1",
      parser("""
      {  "select":"node", "where":[
        { "objectType":"node", "attribute":"state", "comparator":"eq", "value":"initializing" }
      ] }
      """).openOrThrowException("For tests"),
      s(7) :: Nil
    )

    val q2 = TestQuery(
      "q2",
      parser("""
      {  "select":"node", "where":[
        { "objectType":"node", "attribute":"state", "comparator":"eq", "value":"enabled" }
      ] }
      """).openOrThrowException("For tests"),
      s(0) :: s(1) :: s(2) :: s(3) :: s(4) :: s(5) :: s(6) :: Nil
    )

    testQueries(q1 :: q2 :: Nil, true)
  }

  @Test def nodeProperties(): Unit = {
    val q1 = TestQuery(
      "q1",
      parser("""
      { "select":"node", "where":[
        { "objectType":"serializedNodeProperty", "attribute":"name.value", "comparator":"eq", "value":"foo=bar" }
      ] }
      """).openOrThrowException("For tests"),
      s(1) :: Nil
    )

    val q2 = TestQuery(
      "q2",
      parser("""
      { "select":"node", "where":[
        { "objectType":"serializedNodeProperty", "attribute":"name.value", "comparator":"regex", "value":"foo?=.*ar" }
      ] }
      """).openOrThrowException("For tests"),
      s(1) :: Nil
    )

    val q3 = TestQuery(
      "q3",
      parser("""
      { "select":"node", "where":[
        { "objectType":"serializedNodeProperty", "attribute":"name.value", "comparator":"hasKey", "value":"datacenter" }
      ] }
      """).openOrThrowException("For tests"),
      s(1) :: s(2) :: s(3) :: Nil
    ) // s1 is in inventory custom property, s2 & s3 in node properties

    // same as "haskey"
    val q4 = TestQuery(
      "q4",
      parser("""
      { "select":"node", "where":[
        { "objectType":"serializedNodeProperty", "attribute":"name.value", "comparator":"regex", "value":"datacenter=.*" }
      ] }
      """).openOrThrowException("For tests"),
      s(1) :: s(2) :: s(3) :: Nil
    )

    // kind of matching sub-keys
    val q5 = TestQuery(
      "q5",
      parser("""
      { "select":"node", "where":[
        { "objectType":"serializedNodeProperty", "attribute":"name.value", "comparator":"regex", "value":"datacenter=.*\"id\":1234,.*" }
      ] }
      """).openOrThrowException("For tests"),
      s(2) :: Nil
    )

    // matching unquoted number
    val q6 = TestQuery(
      "q6",
      parser("""
      { "select":"node", "where":[
        { "objectType":"serializedNodeProperty", "attribute":"name.value", "comparator":"regex", "value":"number=42" }
      ] }
      """).openOrThrowException("For tests"),
      s(3) :: s(4) :: Nil
    )

    // matching provider, but the user data only
    val q7 = TestQuery(
      "q7",
      parser("""
      { "select":"node", "where":[
        { "objectType":"serializedNodeProperty", "attribute":"name.value", "comparator":"regex", "value":"datacenter=.*provider.*" }
      ] }
      """).openOrThrowException("For tests"),
      s(3) :: Nil
    )

    // the properties are in inventory
    val q8 = TestQuery(
      "q8",
      parser("""
      { "select":"node", "where":[
        { "objectType":"serializedNodeProperty", "attribute":"name.value", "comparator":"regex", "value":"datacenter=.*Paris.*" }
      ] }
      """).openOrThrowException("For tests"),
      s(1) :: Nil
    )

    val andQueries = q1 :: q2 :: q3 :: q4 :: q5 :: q6 :: q7 :: q8 :: Nil
    // And and Or must yield same results when there is only one criteria for node prop, see: #19538
    val orQueries  = andQueries.map(_.modify(_.query.composition).setTo(Or))
    testQueries(andQueries ::: orQueries, false)
  }

  /*
   * When using JsonPath on node properties to know if the node should be return or not, what we are
   * actually looking for is is the resulting JSON select query is empty or not. If empty, the node
   * does not have the property and is not in the group.
   * It means that we are not forced to matches *leaves* or only one elements.
   *
   * Test will be focused on node 5 and 6.
   *
   */
  @Test def nodePropertiesJsonPath(): Unit = {
    val q1 = TestQuery(
      "q1", // select nodes with user.accepted = true
      parser("""
      { "select":"node", "where":[
        { "objectType":"serializedNodeProperty", "attribute":"name.value", "comparator":"jsonSelect", "value":"user:$.[?(@.accepted==true)]" }
      ] }
      """).openOrThrowException("For tests"),
      s(4) :: s(5) :: Nil
    )

    val q2 = TestQuery(
      "q2", // city is exactly New York
      parser("""
      { "select":"node", "where":[
        { "objectType":"serializedNodeProperty", "attribute":"name.value", "comparator":"jsonSelect", "value":"user:$.personal.address[?(@.city=='New York')]" }
      ] }
      """).openOrThrowException("For tests"),
      s(5) :: Nil
    )

    val q3 = TestQuery(
      "q3", // state has a value (whatever it is)
      parser("""
      { "select":"node", "where":[
        { "objectType":"serializedNodeProperty", "attribute":"name.value", "comparator":"jsonSelect", "value":"user:$.personal.address.city" }
      ] }
      """).openOrThrowException("For tests"),
      s(5) :: s(6) :: Nil
    )

    val q4 = TestQuery(
      "q4", // phone number like .*256-.*
      parser("""
      { "select":"node", "where":[
        { "objectType":"serializedNodeProperty", "attribute":"name.value", "comparator":"jsonSelect", "value":"user:$.personal.phones[?(@.number=~/.*123.*/)]" }
      ] }
      """).openOrThrowException("For tests"),
      s(6) :: Nil
    )

    val q5 = TestQuery(
      "q5", // state is in [NY, CA, LA, TX]
      parser("""
      { "select":"node", "where":[
        { "objectType":"serializedNodeProperty", "attribute":"name.value", "comparator":"jsonSelect", "value":"user:$.personal.address[?(@.state in ['NY', 'CA', 'LA', 'TX'])]" }
      ] }
      """).openOrThrowException("For tests"),
      s(5) :: s(6) :: Nil
    )

    testQueries(q1 :: q2 :: q3 :: q4 :: q5 :: Nil, true)
  }

  @Test def testLdapAndNodeInfoQuery(): Unit = {
    val q1 = TestQuery(
      "q1", // select nodes with user.accepted = true and environment variable SHELL=/bin/sh
      parser("""
      { "select":"node", "where":[
        { "objectType":"serializedNodeProperty", "attribute":"name.value", "comparator":"jsonSelect", "value":"user:$.[?(@.accepted==true)]" }
        ,  {"objectType":"environmentVariable","attribute":"name.value","comparator":"eq","value":"SHELL=/bin/sh"}
      ] }
      """).openOrThrowException("For tests"),
      s(4) :: Nil
    )
    val q2 = TestQuery(
      "q2", // select nodes with user.accepted = true OR environment variable SHELL=/bin/sh
      parser("""
      { "select":"node", "composition" : "Or", "where":[
        { "objectType":"serializedNodeProperty", "attribute":"name.value", "comparator":"jsonSelect", "value":"user:$.[?(@.accepted==true)]" }
        ,  {"objectType":"environmentVariable","attribute":"name.value","comparator":"eq","value":"SHELL=/bin/sh"}
      ] }
      """).openOrThrowException("For tests"),
      s(1) :: s(4) :: s(5) :: Nil
    )

    val q3 = TestQuery(
      "q3", // select no nodes because that property name doesn't exists
      parser("""
      { "select":"node", "composition" : "Or", "where":[
        { "objectType":"serializedNodeProperty", "attribute":"name.value", "comparator":"hasKey", "value":"No node with that prop" }
      ] }
      """).openOrThrowException("For tests"),
      Nil
    )

    testQueries(q1 :: q2 :: q3 :: Nil, false)
  }

  @Test def nodePropertiesFailingReq(): Unit = {
    def forceParse(q: String) = parser(q).openOrThrowException("Parsing the request must be ok for that test")
    // Failing request, see #10570
    val failingRegexRequests  = {
      """
      { "select":"node", "where":[
        { "objectType":"serializedNodeProperty", "attribute":"name.value", "comparator":"regex", "value":"f{o}o" }
      ] }""" ::
      // if there is no "=", we fails
      """
      { "select":"node", "where":[
        { "objectType":"serializedNodeProperty", "attribute":"name.value", "comparator":"eq", "value":"foo" }
      ] }
      """ :: Nil
    }

    val results = failingRegexRequests.map(q => (q, queryProcessor.process(forceParse(q))))
    results.foreach { r =>
      assertTrue(s"Regex Query with wrong data for node properties should fail: ${r._1}", r._2.isInstanceOf[Failure])
    }
  }

  @Test def unsortedQueries(): Unit = {
    val q1 = TestQuery(
      "q1",
      parser("""
      {  "select":"node", "where":[
        { "objectType":"software", "attribute":"cn", "comparator":"eq"   , "value":"aalib-libs.i586" },
        { "objectType":"machine", "attribute":"cn", "comparator":"exists"  },
        { "objectType":"node"   , "attribute":"ram"  , "comparator":"gt", "value":"1000" }
      ] }
      """).openOrThrowException("For tests"),
      Nil
    )

    testQueries(q1 :: Nil, true)
  }

  private def testQueries(queries: Seq[TestQuery], doInternalQueryTest: Boolean): Unit = {
    queries foreach { q =>
      logger.logEffect.debug("Processing: " + q.name)
      testQueryResultProcessor(q.name, q.query, q.awaited, doInternalQueryTest)
    }

  }

  private def testQueryResultProcessor(name: String, query: Query, nodes: Seq[NodeId], doInternalQueryTest: Boolean) = {
    val ids   = nodes.sortBy(_.value)
    val found = queryProcessor.process(query).openOrThrowException("For tests").sortBy(_.value)
    // also test with requiring only the expected node to check consistency
    // (that should not change anything)

    assertEquals(
      s"[$name] Duplicate entries in result: $found",
      found.size.toLong,
      found.distinct.size.toLong
    )
    assertEquals(
      s"[$name] Size differs between expected and found entries (process method)\n Found: $found \n Expected: ${ids}",
      ids.size.toLong,
      found.size.toLong
    )
    assertTrue(
      s"[$name] Nodes found are different from expected Nodes (process method)\n Found: ${found}\n Expected: ${ids}",
      found.forall(f => ids.exists(f == _))
    )

  }

  @After def after(): Unit = {}
}
