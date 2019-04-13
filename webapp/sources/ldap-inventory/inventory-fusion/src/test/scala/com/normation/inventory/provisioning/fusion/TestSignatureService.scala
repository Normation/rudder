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

package com.normation.inventory.provisioning.fusion

import org.junit.runner._
import org.specs2.mutable._
import org.specs2.runner._
import com.normation.utils.StringUuidGeneratorImpl
import net.liftweb.common._
import com.normation.inventory.domain.InventoryReport
import com.normation.inventory.services.provisioning._
import com.normation.inventory.domain.KeyStatus
import java.security.Security

import org.bouncycastle.jce.provider.BouncyCastleProvider
import com.normation.inventory.domain.SecurityToken
import scalaz.zio._
import scalaz.zio.syntax._
import com.normation.errors._
import com.normation.inventory.domain.InventoryError
import com.normation.inventory.domain.InventoryResult._
import com.normation.zio.ZioRuntime

import scala.tools.nsc.interpreter.InputStream

@RunWith(classOf[JUnitRunner])
class TestSignatureService extends Specification with Loggable {

  Security.addProvider(new BouncyCastleProvider());

  private[this] def getInputStream (path : String) : IOResult[InputStream] = {
    Task.effect {
      val url = this.getClass.getClassLoader.getResource(path)
      if(null == url) throw new NullPointerException(s"Resource with relative path '${path}' is null (missing resource? Spelling? Permissions?)")
      url.openStream()
    }.mapError(e => SystemError(s"Error opening '${path}'", e))
  }

  private[this] implicit class TestParser(parser: FusionReportUnmarshaller) {
    def parse(reportRelativePath: String): InventoryResult[InventoryReport] = {
      ZIO.bracket(getInputStream(reportRelativePath))(is => Task.effect(is.close).run) { is =>
        parser.fromXml("report", is)
      }
    }
  }

  private[this] object TestInventoryDigestServiceV1 extends ParseInventoryDigestFileV1 with GetKey with CheckInventoryDigest {

    /**
     * Get key in V1 will get the key from inventory data.
     * either an inventory has already been treated before, it will look into ldap repository
     * or if there was no inventory before, it will look for the key in the received inventory
     */
    def getKey (receivedInventory  : InventoryReport) : IOResult[(SecurityToken, KeyStatus)] = {
      for {
        cfengineKey <- ZIO.fromOption(receivedInventory.node.agents.headOption).mapError(_ => InventoryError.Inconsistency("There is no public key in inventory"))
        keyStatus = receivedInventory.node.main.keyStatus
        publicKey = cfengineKey.securityToken//.publicKey
      } yield {
        (publicKey,keyStatus)
      }
    }
  }

  val keyNorm = new PrintedKeyNormalizer

  val parser = new FusionReportUnmarshaller(new StringUuidGeneratorImpl)

  def parseSignature(path: String) = {
     ZIO.bracket(getInputStream(path))(is => Task(is.close).run)(TestInventoryDigestServiceV1.parse)
  }

  val boxedSignature = parseSignature("fusion-report/signed_inventory.ocs.sign")

  "Signature file" should {
    "contain use sha512 algorithm" in {
      ZioRuntime.unsafeRun(boxedSignature.either) match {
        case Left(_) => ko("not a signature :(")
        case Right(signature : InventoryDigestV1) =>
          signature.algorithm  must beEqualTo("sha512")
      }
    }
  }

  "a signed report" should {
    "Be ok if checked with correct signature" in {
      ZioRuntime.unsafeRun(for {
        signature     <- boxedSignature
        signed_report <- parser.parse("fusion-report/signed_inventory.ocs")
        pubKey        <- TestInventoryDigestServiceV1.getKey(signed_report)
        check         <- ZIO.bracket(getInputStream("fusion-report/signed_inventory.ocs"))(is => Task.effect(is.close).run)(TestInventoryDigestServiceV1.check(pubKey._1, signature, _))
      } yield {
        check
      }) === true
    }

    "Be wrong if checked with wrong signature" in {
      ZioRuntime.unsafeRun(for {
        signature       <- boxedSignature
        unsigned_report <- parser.parse("fusion-report/node-with-server-role-attribute.ocs")
        pubKey          <- TestInventoryDigestServiceV1.getKey(unsigned_report)
        check           <- ZIO.bracket(getInputStream("fusion-report/node-with-server-role-attribute.ocs"))(is => Task.effect(is.close).run)(TestInventoryDigestServiceV1.check(pubKey._1, signature, _))
      } yield {
        check
      }) === false
    }
  }

}
