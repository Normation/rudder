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
import zio.json.*
import zio.test.*
import zio.test.junit.ZTestJUnitRunner

@RunWith(classOf[ZTestJUnitRunner])
class CertificateTest extends ZIOSpecDefault {

  def read(name: String): String = {
    val source = Source.fromResource(s"certificates/${name}")
    try source.mkString.trim + "\n"
    finally source.close()
  }

  // standard PEM, as built by Certificate
  val linux:     String = read("cert-nodeid-ok-linux.pem")
  // self-signed with the UID of another node
  val forged:    String = read("cert-forged-uid-windows.pem")
  val publicKey: String = read("public-key.pem")
  val nodeId:    NodeId = NodeId("67e00959-ccda-430d-b6c2-ad1f3e89276a")

  // BouncyCastle stops at the malformed BEGIN line, OpenSSL skips it and reads the forged certificate
  val hiddenAfterMalformedBegin: String = linux + "-----BEGIN CERTIFICATE\n" + forged
  val twoCertificates:           String = linux + forged

  def isSecurityToken(res: Either[InventoryError, Any]): Boolean = res match {
    case Left(_: InventoryError.SecurityToken) => true
    case _                                     => false
  }

  def pem(res: Either[InventoryError, Certificate]): Either[InventoryError, String] = res.map(_.pem)

  override def spec: Spec[Any, Any] = suite("Security token")(
    suite("received by Rudder")(
      test("is a certificate when it is exactly one") {
        assertTrue(
          pem(Certificate.parse(linux)) == Right(linux),
          pem(Certificate.parse(linux.stripSuffix("\n"))) == Right(linux),
          pem(Certificate.parse(linux.replace("\n", "\r\n"))) == Right(linux),
          isSecurityToken(Certificate.parse(twoCertificates)),
          isSecurityToken(Certificate.parse(linux + publicKey)),
          Certificate.parse(publicKey).isLeft,
          Certificate.parse("not a certificate").isLeft
        )
      },
      test("is the checked certificate, without what BouncyCastle does not read") {
        assertTrue(pem(Certificate.parse(hiddenAfterMalformedBegin)) == Right(linux))
      },
      test("is kept unverified by the inventory parser, and refused when saved") {
        for {
          refused <- SecurityToken.checkCertificateForNode(nodeId, SecurityToken.fromReceived(twoCertificates)).either
          ok      <- SecurityToken.checkCertificateForNode(nodeId, SecurityToken.fromReceived(linux)).either
        } yield assertTrue(
          SecurityToken.fromReceived(twoCertificates) == UnverifiedToken(twoCertificates),
          isSecurityToken(refused),
          ok.isRight
        )
      },
      test("follows the same rule from the API") {
        assertTrue(
          SecurityToken.parseValidate(linux).map(_.value) == Right(linux),
          isSecurityToken(SecurityToken.parseValidate(twoCertificates))
        )
      }
    ),
    suite("stored by Rudder")(
      test("is its first certificate") {
        assertTrue(
          SecurityToken.fromStored(twoCertificates).value == linux,
          SecurityToken.fromStored(hiddenAfterMalformedBegin).value == linux,
          SecurityToken.fromStored(publicKey + linux) == UnverifiedToken(publicKey + linux)
        )
      },
      test("is read with its first certificate from JSON, in the same format") {
        val json = AgentInfo(AgentType.CfeCommunity, None, UnverifiedToken(twoCertificates), Set()).toJson
        assertTrue(
          json.fromJson[AgentInfo].map(_.securityToken.value) == Right(linux),
          json.contains("\"type\":\"certificate\"")
        )
      }
    ),
    suite("certificate")(
      test("is equal to another one with the same PEM") {
        val a = Certificate.parse(linux)
        val b = Certificate.parse(linux.stripSuffix("\n"))
        assertTrue(
          a == b,
          a.map(_.hashCode) == b.map(_.hashCode),
          a != Certificate.parse(forged),
          a.map { case Certificate(pem) => pem } == Right(linux)
        )
      },
      test("has the subject and key digests computed by openssl") {
        val cert = Certificate.parse(linux)
        assertTrue(
          cert.map(_.subject) == Right(s"UID=${nodeId.value}"),
          cert.map(_.firstRdnValue) == Right(nodeId.value),
          // openssl x509 -pubkey -noout | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | base64
          cert.map(_.sha256Base64) == Right("hT8Eme1WclKhxW2J9npkysfIZJhyW+xLcYdpDDTZHCo="),
          // MD5 of the modulus bytes then of the exponent bytes, from `openssl x509 -noout -modulus` and `-text`
          cert.map(_.cfengineMd5Digest) == Right(Some("b879ed3382a3a7a2630eaac57b5e8812"))
        )
      }
    )
  )
}
