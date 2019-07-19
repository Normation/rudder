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

package com.normation.inventory.provisioning.endpoint

import java.security.Security

import better.files.Resource
import com.normation.inventory.domain.CertifiedKey
import com.normation.inventory.domain.FullInventory
import com.normation.inventory.domain.InventoryReport
import com.normation.inventory.domain.InventoryStatus
import com.normation.inventory.domain.MachineUuid
import com.normation.inventory.domain.NodeId
import com.normation.inventory.domain.NodeInventory
import com.normation.inventory.domain.UndefinedKey
import com.normation.inventory.ldap.core.InventoryDit
import com.normation.inventory.provisioning.fusion.FusionReportUnmarshaller
import com.normation.inventory.services.core.FullInventoryRepository
import com.normation.inventory.services.provisioning._
import com.normation.utils.StringUuidGeneratorImpl
import com.unboundid.ldap.sdk.DN
import com.unboundid.ldif.LDIFChangeRecord
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.runner._
import org.specs2.mutable._
import org.specs2.runner._
import zio._
import zio.syntax._
import com.normation.errors._
import com.normation.zio._

@RunWith(classOf[JUnitRunner])
class TestCertificate extends Specification {

  Security.addProvider(new BouncyCastleProvider())

  val repository = scala.collection.mutable.Map[NodeId, FullInventory]()

  val parser = new FusionReportUnmarshaller(new StringUuidGeneratorImpl)

  val reportSaver = new ReportSaver[Seq[LDIFChangeRecord]] {
    override def save(report: InventoryReport): IOResult[Seq[LDIFChangeRecord]] = {
      repository += ((report.node.main.id, FullInventory(report.node, Some(report.machine))))
      Nil.succeed
    }
  }

  val fullInventoryRepo = new FullInventoryRepository[Seq[LDIFChangeRecord]] {
    override def get(id: NodeId, inventoryStatus: InventoryStatus): IOResult[Option[FullInventory]] = get(id)

    override def get(id: NodeId): IOResult[Option[FullInventory]] = repository.get(id).succeed

    override def save(serverAndMachine: FullInventory): IOResult[Seq[LDIFChangeRecord]] = {
      repository += ((serverAndMachine.node.main.id, serverAndMachine))
      Nil.succeed
    }

    override def getMachineId(id: NodeId, inventoryStatus: InventoryStatus): IOResult[Option[(MachineUuid, InventoryStatus)]] = ???
    override def getAllInventories(inventoryStatus: InventoryStatus): IOResult[Map[NodeId, FullInventory]] = ???
    override def getAllNodeInventories(inventoryStatus: InventoryStatus): IOResult[Map[NodeId, NodeInventory]] = ???
    override def delete(id: NodeId, inventoryStatus: InventoryStatus): IOResult[Seq[LDIFChangeRecord]] = ???
    override def move(id: NodeId, from: InventoryStatus, into: InventoryStatus): IOResult[Seq[LDIFChangeRecord]] = ???
    override def moveNode(id: NodeId, from: InventoryStatus, into: InventoryStatus): IOResult[Seq[LDIFChangeRecord]] = ???
  }

  val processor = new InventoryProcessor(
    parser
  , reportSaver
  , 1000
  , fullInventoryRepo
  , new InventoryDigestServiceV1(fullInventoryRepo)
  , () => UIO.unit
  , new InventoryDit(new DN("cn=test"), new DN("cn=soft"),"Pending Servers")
  )

  sequential


  val windows = NodeId("b73ea451-c42a-420d-a540-47b445e58313")
  val linux = NodeId("baded9c8-902e-4404-96c1-278acca64e3a")

  // LINUX
  "when a node is not in repository, it is ok to not have a signature with cfkey" in {
    repository.remove(linux)

    val res = processor.saveInventory(SaveInventoryInfo(
        "linux-cfe-sign"
      , () => Resource.getAsStream("linux-cfe-sign.ocs")
      , None
    ))

    (res.runNow must beAnInstanceOf[InventoryProcessStatus.Accepted]) and
    (repository.get(linux) must beSome) and
    (repository(linux).node.agents.head.securityToken.key === Cert.CFE) and
    (repository(linux).node.main.keyStatus === UndefinedKey)
  }

  "when a node is not in repository, it is ok to have a signature with cfe-key" in {
    repository.remove(linux)

    val res = processor.saveInventory(SaveInventoryInfo(
        "linux-cfe-sign"
      , () => Resource.getAsStream("linux-cfe-sign.ocs")
      , Some(() => Resource.getAsStream("linux-cfe-sign.ocs.sign"))
    ))

    (res.runNow must beAnInstanceOf[InventoryProcessStatus.Accepted]) and
    (repository.get(linux) must beSome) and
    (repository(linux).node.agents.head.securityToken.key === Cert.CFE) and
    (repository(linux).node.main.keyStatus == CertifiedKey)

  }

  // WINDOWS

  "when a node is not in repository, it is ok to not have a signature with certificate" in {
    repository.remove(windows)

    val res = processor.saveInventory(SaveInventoryInfo(
        "windows-same-certificate"
      , () => Resource.getAsStream("windows-same-certificate.ocs")
      , None
    ))

    (res.runNow must beAnInstanceOf[InventoryProcessStatus.Accepted]) and
    (repository.get(windows) must beSome) and
    (repository(windows).node.agents.head.securityToken.key === Cert.OK) and
    (repository(windows).node.main.keyStatus === UndefinedKey)

  }

  "when a node is not in repository, it is ok to have a signature with certificate" in {
    repository.remove(windows)

    val res = processor.saveInventory(SaveInventoryInfo(
        "windows-same-certificate"
      , () => Resource.getAsStream("windows-same-certificate.ocs")
      , Some(() => Resource.getAsStream("windows-same-certificate.ocs.sign"))
    ))

    (res.runNow must beAnInstanceOf[InventoryProcessStatus.Accepted]) and
    (repository.get(windows) must beSome) and
    (repository(windows).node.agents.head.securityToken.key === Cert.OK) and
    (repository(windows).node.main.keyStatus == CertifiedKey)

  }

  "when a node is in repository with a registered key, it is ok to add it again with a signature" in {
    val start = repository(windows).node.agents.head.securityToken.key === Cert.OK

    val res = processor.saveInventory(SaveInventoryInfo(
        "windows-same-certificate"
      , () => Resource.getAsStream("windows-same-certificate.ocs")
      , Some(() => Resource.getAsStream("windows-same-certificate.ocs.sign"))
    ))

    (res.runNow must beAnInstanceOf[InventoryProcessStatus.Accepted]) and
    (repository.get(windows) must beSome) and
    (start) and
    (repository(windows).node.main.keyStatus == CertifiedKey)
  }

  "when a node is in repository with a registered key, it is NOK to miss signature" in {
    val start = repository(windows).node.agents.head.securityToken.key === Cert.OK

    val res = processor.saveInventory(SaveInventoryInfo(
        "windows-same-certificate"
      , () => Resource.getAsStream("windows-same-certificate.ocs")
      , None
    ))

    (repository.get(windows) must beSome) and
    (start) and
    (repository(windows).node.main.keyStatus == CertifiedKey) and
    (res.runNow must beAnInstanceOf[InventoryProcessStatus.MissingSignature])
  }

  // this one will be used to update certificate: sign new inventory with old key
  "when a node is in repository with a registered key; signature must match existing certificate, not the one in inventory" in {
    val start = repository(windows).node.agents.head.securityToken.key === Cert.OK

    val res = processor.saveInventory(SaveInventoryInfo(
        "windows-new-certificate"
      , () => Resource.getAsStream("windows-new-certificate.ocs")
      , Some(() => Resource.getAsStream("windows-new-certificate.ocs.sign"))
    ))

    (res.runNow must beAnInstanceOf[InventoryProcessStatus.Accepted]) and
    (repository.get(windows) must beSome) and
    (start) and
    (repository(windows).node.main.keyStatus == CertifiedKey) and
    (repository(windows).node.agents.head.securityToken.key === Cert.NEW)
  }

  "when certificate 'subject' doesn't match node ID, we got an error" in {
    repository.remove(windows)
    val res = processor.saveInventory(SaveInventoryInfo(
        "windows-bad-certificate"
      , () => Resource.getAsStream("windows-bad-certificate.ocs")
      , Some(() => Resource.getAsStream("windows-bad-certificate.ocs.sign"))
    ))

    (res.either.runNow must beLike {
      case Left(e) => e.fullMsg must beMatching(".*subject doesn't contain same node ID in 'UID' attribute as inventory node ID.*")
    })
  }

}

object Cert {

  val CFE = """-----BEGIN RSA PUBLIC KEY-----
              |MIICCgKCAgEAtG20P906HK1MV0nSA2eWKqC+29tX8/TnHd0YGAVgg5+ODr+tbXXj
              |WgtEr0XaLxN12/Usu+DSQWcEAMAn6O3iUmxsIYLWRAU1mqqK0q2rxov3+jg/7sK9
              |sYo9dg9OGOjbFeoJVg66P1zlZt4YWczBP+zM+tLvh+Zn65IcLAc6ASm6imWjStNf
              |9rmpfupsd85y29qpdxbS7acb3WOzelz2EVVU5NBhQ3VB94n3O5+9UjpHSSOkR3bA
              |oB9UsrnbXpDLV0NfEBUtiQsgcARO94QjRaaN2kgvGAIryZJxgDOmNGNzJTNV7p1W
              |geN5L2Bal98C4vbR6dnln2RTOnE8uQb0gd36gHZF/Zyh45Odlx/XZc1P5UtV/BUU
              |PxUhYYxth5P6u2C2YHtyb6ws7lKtT/W9ECiK4i/gyEIVDxu7stR17HvRR0BL1D2u
              |wHl/TkpkTHRIHl7G6zt79s7ujFFWn+GRXPXLFfUy45e0HkGeUVJUoW62NZTp1bFI
              |BaErpPMRwXmvD6v3ox3XGI46wSjfIG0c8W3nYHM1q9JrwE9+0jLBb8bumsgS7KpO
              |0VKjPXX7rzH+2l8AweQR3JzGifUyl/DsHiPSaOc3nAWJy0k7yizhHkebw6rcM/SD
              |lz4F5ONicuBwYgjvmgjA7o2COh7s+dIoYXp01xMb60OUfjKeG8BUKucCAwEAAQ==
              |-----END RSA PUBLIC KEY-----""".stripMargin

  val OK = """-----BEGIN CERTIFICATE-----
             |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
             |ootapja6lKOaIpqp0kmmYN7gFIhp
             |-----END CERTIFICATE-----""".stripMargin

  val NEW = """-----BEGIN CERTIFICATE-----
              |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
              |TZEW7+Ri43DsMyRwYiCafuVThL+J
              |-----END CERTIFICATE-----""".stripMargin

  val BAD = """-----BEGIN CERTIFICATE-----
              |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
              |-----END CERTIFICATE-----""".stripMargin
}
