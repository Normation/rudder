package com.normation.rudder.facts.nodes

import better.files.Resource
import com.normation.JsonSpecMatcher
import com.normation.inventory.domain.AgentType
import com.normation.inventory.domain.AgentVersion
import com.normation.inventory.domain.Centos
import com.normation.inventory.domain.Certificate
import com.normation.inventory.domain.CertifiedKey
import com.normation.inventory.domain.Linux
import com.normation.inventory.domain.MachineType
import com.normation.inventory.domain.MachineUuid
import com.normation.inventory.domain.NodeId
import com.normation.inventory.domain.PendingInventory
import com.normation.inventory.domain.PhysicalMachineType
import com.normation.inventory.domain.UnknownMachineType
import com.normation.inventory.domain.Version
import com.normation.inventory.domain.VirtualMachineType
import com.normation.inventory.domain.VmType
import com.normation.rudder.domain.nodes.MachineInfo
import com.normation.rudder.domain.nodes.NodeKind
import com.normation.rudder.domain.nodes.NodeState
import com.normation.rudder.reports.ReportingConfiguration
import com.normation.utils.DateFormaterService
import org.junit.runner.RunWith
import org.specs2.matcher.Matcher
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import zio.Chunk
import zio.json.*

@RunWith(classOf[JUnitRunner])
class TestNodeFactSerialisation extends Specification with JsonSpecMatcher {
  import NodeFactSerialisation.*

  "NodeFactSerialisation" should {

    "work for machine type for node-001" in {
      def nodeFact(machineType: MachineType) = NodeFact(
        NodeId("00100000-0000-0000-0000-000000000000"),
        None,
        "node-001.localhost",
        Linux(
          Centos,
          "CentOS Stream release 8",
          new Version("8"),
          None,
          new Version("4.18.0-365.el8.x86_64")
        ),
        MachineInfo(
          MachineUuid("22222222-2222-2222-2222-222222222222"),
          machineType
        ),
        RudderSettings(
          CertifiedKey,
          ReportingConfiguration(None, None, None),
          NodeKind.Node,
          PendingInventory,
          NodeState.Ignored,
          None,
          NodeId("00000000-0000-0000-0000-000000000000"),
          None
        ),
        RudderAgent(
          AgentType.CfeCommunity,
          "machine-user",
          AgentVersion("3.15.3"),
          Certificate(
            """-----BEGIN CERTIFICATE-----
              |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
              |RlYSlie1YMo1Yy2jzZhP
              |-----END CERTIFICATE-----""".stripMargin
          ),
          Chunk.empty
        ),
        Chunk.empty,
        DateFormaterService.parseInstant("2024-05-16T15:20:12Z").getOrElse(throw new IllegalArgumentException("error")),
        DateFormaterService.parseInstant("2024-05-16T15:20:12Z").getOrElse(throw new IllegalArgumentException("error"))
      )

      // we have to match machineType specifically because nodefact does not seem to be exactly equal
      "physical machine type" in {
        "serialize in V2" in {
          nodeFact(PhysicalMachineType).toJson must equalsJsonSemantic(
            Resource.getAsString("node-facts/node-001-machine-physical-v2.json")
          )
        }

        "deserialize initial version" in {
          Resource.getAsString("node-facts/node-001-machine-physical.json").fromJson[NodeFact] must beRight(
            ((nf: NodeFact) => {
              (
                nf.machine.machineType == PhysicalMachineType,
                nf.machine.machineType.toString + " was not PhysicalMachineType"
              )
            }): Matcher[NodeFact]
          )
        }

        "deserialize v2" in {
          Resource.getAsString("node-facts/node-001-machine-physical-v2.json").fromJson[NodeFact] must beRight(
            ((nf: NodeFact) => {
              (
                nf.machine.machineType == PhysicalMachineType,
                nf.machine.machineType.toString + " was not PhysicalMachineType"
              )
            }): Matcher[NodeFact]
          )
        }
      }

      "unknown machine type" in {
        "serialize in V2" in {
          nodeFact(UnknownMachineType).toJson must equalsJsonSemantic(
            Resource.getAsString("node-facts/node-001-machine-unknown-v2.json")
          )
        }

        "deserialize initial version" in {
          Resource.getAsString("node-facts/node-001-machine-unknown.json").fromJson[NodeFact] must beRight(
            ((nf: NodeFact) => {
              (
                nf.machine.machineType == UnknownMachineType,
                nf.machine.machineType.toString + " was not UnknownMachineType"
              )
            }): Matcher[NodeFact]
          )
        }

        "deserialize v2" in {
          Resource.getAsString("node-facts/node-001-machine-unknown-v2.json").fromJson[NodeFact] must beRight(
            ((nf: NodeFact) => {
              (
                nf.machine.machineType == UnknownMachineType,
                nf.machine.machineType.toString + " was not UnknownMachineType"
              )
            }): Matcher[NodeFact]
          )
        }
      }

      // unknown virtual machine is the default type machine type when it cannot be parsed. Use "unknown" value to test the encoder
      "virtual machine type (unknown)" in {
        "serialize in V2" in {
          nodeFact(VirtualMachineType(VmType.UnknownVmType)).toJson must equalsJsonSemantic(
            Resource.getAsString("node-facts/node-001-machine-unknown-vm-v2.json")
          )
        }

        "deserialize initial version" in {
          Resource.getAsString("node-facts/node-001-machine-unknown-vm.json").fromJson[NodeFact] must beRight(
            ((nf: NodeFact) => {
              (
                nf.machine.machineType == VirtualMachineType(VmType.UnknownVmType),
                nf.machine.machineType.toString + " was not VirtualMachineType(unknown)"
              )
            }): Matcher[NodeFact]
          )
        }

        "deserialize v2" in {
          Resource.getAsString("node-facts/node-001-machine-unknown-vm-v2.json").fromJson[NodeFact] must beRight(
            ((nf: NodeFact) => {
              (
                nf.machine.machineType == VirtualMachineType(VmType.UnknownVmType),
                nf.machine.machineType.toString + " was not VirtualMachineType(unknown)"
              )
            }): Matcher[NodeFact]
          )
        }
      }
    }

    "deserialize NodeFact for node-930dccb0-5b06-4385-ab7a-80a7e56eb969" in {
      "initial version" in {
        Resource.getAsString("node-facts/node-930dccb0-5b06-4385-ab7a-80a7e56eb969.json").fromJson[NodeFact] must beRight
      }

      "v2 version that should equal initial version" in {
        Resource.getAsString("node-facts/node-930dccb0-5b06-4385-ab7a-80a7e56eb969-v2.json").fromJson[NodeFact] must beEqualTo(
          Resource.getAsString("node-facts/node-930dccb0-5b06-4385-ab7a-80a7e56eb969.json").fromJson[NodeFact]
        )
      }
    }

  }

}
