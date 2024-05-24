package com.normation.inventory.domain

import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import zio.json.*

@RunWith(classOf[JUnitRunner])
class InventorySerialisationTest extends Specification {
  import com.normation.inventory.domain.JsonSerializers.implicits.*

  "Controller" should {
    val controller = Controller("c1", Some("A controller"), Some(Manufacturer("1")), Some("controller"))
    "serialize in v2" in {
      controller.toJson must beEqualTo(
        """{"name":"c1","description":"A controller","manufacturer":"1","type":"controller","quantity":1}"""
      )
    }

    "deserialize initial version" in {
      """{"name":"c1","description":"A controller","manufacturer":"1","cType":"controller"}""".fromJson[Controller] must beRight(
        controller
      )
    }

    "deserialize v2" in {
      """{"name":"c1","description":"A controller","manufacturer":"1","type":"controller"}""".fromJson[Controller] must beRight(
        controller
      )
    }
  }

  "MemorySlot" should {
    val memorySlot = MemorySlot(
      "RAM slot #0",
      Some("SingleSlot"),
      Some("A memory slot"),
      Some(MemorySize(1024)),
      Some("caption"),
      Some("speed"),
      Some("memType"),
      Some("serial")
    )

    "serialize in v2" in {
      memorySlot.toJson must beEqualTo(
        """{"slotNumber":"RAM slot #0","name":"SingleSlot","description":"A memory slot","capacity":1024,"caption":"caption","speed":"speed","type":"memType","serialNumber":"serial","quantity":1}"""
      )
    }

    "deserialize initial version" in {
      """{"slotNumber":"RAM slot #0","name":"SingleSlot","description":"A memory slot","capacity":1024,"caption":"caption","speed":"speed","memType":"memType","serialNumber":"serial"}"""
        .fromJson[MemorySlot] must beRight(
        memorySlot
      )
    }

    "deserialize v2" in {
      """{"slotNumber":"RAM slot #0","name":"SingleSlot","description":"A memory slot","capacity":1024,"caption":"caption","speed":"speed","type":"memType","serialNumber":"serial"}"""
        .fromJson[MemorySlot] must beRight(
        memorySlot
      )
    }
  }

  "Process" should {
    val process = Process(
      1024,
      Some("ls"),
      Some(0.1f),
      Some(1.2f),
      Some("2024-05-21 09:50:14+02"),
      Some("tty1"),
      Some("root"),
      Some(0.0),
      Some("list")
    )

    "serialize in v2" in {
      process.toJson must beEqualTo(
        """{"pid":1024,"name":"ls","cpuUsage":0.1,"memory":1.2,"started":"2024-05-21 09:50:14+02","tty":"tty1","user":"root","virtualMemory":0.0,"description":"list"}"""
      )
    }
    "deserialize initial version" in {
      """{"pid":1024,"name":"ls","cpuUsage":0.1,"memory":1.2,"started":"2024-05-21 09:50:14+02","tty":"tty1","user":"root","virtualMemory":0.0,"description":"list"}"""
        .fromJson[Process] must beRight(process)
    }
    "deserialize v2" in {
      """{"pid":1024,"name":"ls","cpuUsage":0.1,"memory":1.2,"started":"2024-05-21 09:50:14+02","tty":"tty1","user":"root","virtualMemory":0.0,"description":"list"}"""
        .fromJson[Process] must beRight(process)
    }
  }

  "Port" should {
    val port = Port("COM1", Some("communication port (COM1)"), Some("Serial"))

    "serialize in v2" in {
      port.toJson must beEqualTo(
        """{"name":"COM1","description":"communication port (COM1)","type":"Serial","quantity":1}"""
      )
    }
    "deserialize initial version" in {
      """{"name":"COM1","description":"communication port (COM1)","pType":"Serial","quantity":1}"""
        .fromJson[Port] must beRight(port)
    }
    "deserialize v2" in {
      """{"name":"COM1","description":"communication port (COM1)","type":"Serial","quantity":1}"""
        .fromJson[Port] must beRight(port)
    }
  }

  "VirtualMachine" should {
    val virtualMachine = VirtualMachine(
      Some("vbox"),
      Some("vbox"),
      Some("root"),
      Some("virtual machine 1"),
      Some("active"),
      Some(1),
      Some("8GB"),
      MachineUuid("00000000-0000-0000-0000-000000000000"),
      Some("a virtual machine")
    )

    "serialize in v2" in {
      virtualMachine.toJson must beEqualTo(
        """{"type":"vbox","subsystem":"vbox","owner":"root","name":"virtual machine 1","status":"active","vcpu":1,"memory":"8GB","uuid":"00000000-0000-0000-0000-000000000000","description":"a virtual machine"}"""
      )
    }
    "deserialize initial version" in {
      """{"vmtype":"vbox","subsystem":"vbox","owner":"root","name":"virtual machine 1","status":"active","vcpu":1,"memory":"8GB","uuid":"00000000-0000-0000-0000-000000000000","description":"a virtual machine"}"""
        .fromJson[VirtualMachine] must beRight(virtualMachine)
    }
    "deserialize v2" in {
      """{"type":"vbox","subsystem":"vbox","owner":"root","name":"virtual machine 1","status":"active","vcpu":1,"memory":"8GB","uuid":"00000000-0000-0000-0000-000000000000","description":"a virtual machine"}"""
        .fromJson[VirtualMachine] must beRight(virtualMachine)
    }
  }

  "Storage" should {
    val storage = Storage(
      "vda",
      Some("Virtual"),
      Some(MemorySize(1024)),
      Some("firmware"),
      Some(Manufacturer("1")),
      Some("model"),
      Some("serial"),
      Some("disk")
    )

    "serialize in v2" in {
      storage.toJson must beEqualTo(
        """{"name":"vda","description":"Virtual","size":1024,"firmware":"firmware","manufacturer":"1","model":"model","serialNumber":"serial","type":"disk","quantity":1}"""
      )
    }
    "deserialize initial version" in {
      """{"name":"vda","description":"Virtual","size":1024,"firmware":"firmware","manufacturer":"1","model":"model","serialNumber":"serial","sType":"disk","quantity":1}"""
        .fromJson[Storage] must beRight(storage)
    }
    "deserialize v2" in {
      """{"name":"vda","description":"Virtual","size":1024,"firmware":"firmware","manufacturer":"1","model":"model","serialNumber":"serial","type":"disk","quantity":1}"""
        .fromJson[Storage] must beRight(storage)
    }
  }

  "Network" should {
    val network = Network(
      "eth0",
      Some("lan"),
      com.comcast.ip4s.IpAddress.fromString("0.0.0.0").map(_.toInetAddress).toList,
      com.comcast.ip4s.IpAddress.fromString("10.1.255.254").map(_.toInetAddress),
      com.comcast.ip4s.IpAddress.fromString("0.0.0.0").map(_.toInetAddress).toList,
      com.comcast.ip4s.IpAddress.fromString("192.168.210.1").map(_.toInetAddress).toList,
      com.comcast.ip4s.IpAddress.fromString("255.255.255.128").map(_.toInetAddress).toList,
      Some("mac address"),
      Some("status"),
      Some("ifType"),
      Some("speed"),
      Some("typeMib")
    )

    "serialize in v2" in {
      network.toJson must beEqualTo(
        """{"name":"eth0","description":"lan","ipAddresses":["0.0.0.0"],"dhcpServer":"10.1.255.254","gateway":["0.0.0.0"],"mask":["192.168.210.1"],"subnet":["255.255.255.128"],"macAddress":"mac address","status":"status","ifType":"ifType","speed":"speed","typeMib":"typeMib"}"""
      )
    }
    "deserialize initial version" in {
      """{"name":"eth0","description":"lan","ifAddresses":["0.0.0.0"],"ifDhcp":"10.1.255.254","ifGateway":["0.0.0.0"],"ifMask":["192.168.210.1"],"ifSubnet":["255.255.255.128"],"macAddress":"mac address","status":"status","ifType":"ifType","speed":"speed","typeMib":"typeMib"}"""
        .fromJson[Network] must beRight(network)
    }
    "deserialize v2" in {
      """{"name":"eth0","description":"lan","ipAddresses":["0.0.0.0"],"dhcpServer":"10.1.255.254","gateway":["0.0.0.0"],"mask":["192.168.210.1"],"subnet":["255.255.255.128"],"macAddress":"mac address","status":"status","ifType":"ifType","speed":"speed","typeMib":"typeMib"}"""
        .fromJson[Network] must beRight(network)
    }
  }
}
