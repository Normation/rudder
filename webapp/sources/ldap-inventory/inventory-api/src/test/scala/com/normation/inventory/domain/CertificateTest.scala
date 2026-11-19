/*
 *************************************************************************************
 * Copyright 2026 Normation SAS
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

package com.normation.inventory.domain

import org.junit.runner.RunWith
import scala.io.Source
import zio.test.*
import zio.test.junit.ZTestJUnitRunner

@RunWith(classOf[ZTestJUnitRunner])
class CertificateTest extends ZIOSpecDefault {

  def read(name: String): String = {
    val source = Source.fromResource(s"certificates/${name}")
    try source.mkString
    finally source.close()
  }

  val linux:     String = read("cert-nodeid-ok-linux.pem")
  val forged:    String = read("cert-forged-uid-windows.pem")
  val publicKey: String = read("public-key.pem")
  val nodeId:    NodeId = NodeId("67e00959-ccda-430d-b6c2-ad1f3e89276a")

  def isRefusedAsSecurityToken(res: Either[InventoryError, Any]): Boolean = res match {
    case Left(_: InventoryError.SecurityToken) => true
    case _                                     => false
  }

  def isRefusedAsCrypto(res: Either[InventoryError, Any]): Boolean = res match {
    case Left(_: InventoryError.Crypto) => true
    case _                              => false
  }

  override def spec: Spec[Any, Any] = suite("A security token")(
    test("accepts exactly one certificate") {
      assertTrue(Certificate.parseSingle(linux).map(_.getSubject.toString) == Right(s"UID=${nodeId.value}"))
    },
    test("refuses two certificates in the same text") {
      assertTrue(isRefusedAsSecurityToken(Certificate.parseSingle(linux + "\n" + forged)))
    },
    test("refuses a certificate followed by another PEM object") {
      assertTrue(isRefusedAsSecurityToken(Certificate.parseSingle(linux + "\n" + publicKey)))
    },
    test("refuses a PEM object that is not a certificate") {
      assertTrue(isRefusedAsCrypto(Certificate.parseSingle(publicKey)))
    },
    test("refuses a text without PEM object") {
      assertTrue(isRefusedAsCrypto(Certificate.parseSingle("not a certificate")))
    },
    test("applies the same rule when parsed from the API") {
      assertTrue(
        SecurityToken.parseValidate(linux) == Right(Certificate(linux)),
        isRefusedAsSecurityToken(SecurityToken.parseValidate(linux + "\n" + forged))
      )
    },
    test("applies the same rule when checked against the node id") {
      for {
        ok      <- SecurityToken.checkCertificateForNode(nodeId, Certificate(linux)).either
        refused <- SecurityToken.checkCertificateForNode(nodeId, Certificate(linux + "\n" + forged)).either
      } yield assertTrue(ok.isRight, isRefusedAsSecurityToken(refused))
    }
  )
}
