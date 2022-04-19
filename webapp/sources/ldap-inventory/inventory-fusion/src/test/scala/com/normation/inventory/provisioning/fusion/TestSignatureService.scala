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

import java.io.InputStream
import java.security.Security

import com.normation.inventory.domain.InventoryError
import com.normation.inventory.domain.Inventory
import com.normation.errors._
import com.normation.inventory.domain.KeyStatus
import com.normation.inventory.domain.SecurityToken
import com.normation.inventory.services.provisioning._
import com.normation.utils.StringUuidGeneratorImpl
import com.normation.zio.ZioRuntime
import net.liftweb.common._
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.runner._
import org.specs2.mutable._
import org.specs2.runner._
import zio._

@RunWith(classOf[JUnitRunner])
class TestSignatureService extends Specification with Loggable {

  Security.addProvider(new BouncyCastleProvider())

  private[this] def getInputStream (path : String) : IOResult[InputStream] = {
    ZIO.attempt {
      val url = this.getClass.getClassLoader.getResource(path)
      if(null == url) throw new NullPointerException(s"Resource with relative path '${path}' is null (missing resource? Spelling? Permissions?)")
      url.openStream()
    }.mapError(e => SystemError(s"Error opening '${path}'", e))
  }

  private[this] implicit class TestParser(parser: FusionInventoryParser) {
    def parse(inventoryRelativePath: String): IOResult[Inventory] = {
      ZIO.acquireReleaseWith(getInputStream(inventoryRelativePath))(is => effectUioUnit(is.close)) { is =>
        parser.fromXml("inventory", is)
      }
    }
  }

  private[this] object TestInventoryDigestServiceV1 extends ParseInventoryDigestFileV1 with GetKey with CheckInventoryDigest {

    /**
     * Get key in V1 will get the key from inventory data.
     * either an inventory has already been treated before, it will look into ldap repository
     * or if there was no inventory before, it will look for the key in the received inventory
     */
    def getKey (receivedInventory: Inventory) : IOResult[(SecurityToken, KeyStatus)] = {
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

  val parser = new FusionInventoryParser(new StringUuidGeneratorImpl)

  def parseSignature(path: String): IOResult[InventoryDigest] = {
     ZIO.acquireReleaseWith(getInputStream(path))(is => effectUioUnit(is.close))(TestInventoryDigestServiceV1.parse)
  }

  val boxedSignature = parseSignature("fusion-inventories/signed_inventory.ocs.sign")

  "Signature file" should {
    "contain use sha512 algorithm" in {
      ZioRuntime.unsafeRun(boxedSignature.either) match {
        case Left(_) => ko("not a signature :(")
        case Right(signature : InventoryDigestV1) =>
          signature.algorithm  must beEqualTo("sha512")
      }
    }
  }

  "a signed inventory" should {
    "Be ok if checked with correct signature" in {
      ZioRuntime.unsafeRun(for {
        signature  <- boxedSignature
        signed_inv <- parser.parse("fusion-inventories/signed_inventory.ocs")
        token      <- TestInventoryDigestServiceV1.getKey(signed_inv)
        check      <- ZIO.acquireReleaseWith(getInputStream("fusion-inventories/signed_inventory.ocs"))(is => effectUioUnit(is.close))(is =>
          for {
            parsed <- TestInventoryDigestServiceV1.parseSecurityToken(token._1)
            check  <- TestInventoryDigestServiceV1.check(parsed.publicKey, signature, is)
          } yield check)
      } yield {
        check
      }) === true
    }

    "Be wrong if checked with wrong signature" in {
      ZioRuntime.unsafeRun(for {
        signature    <- boxedSignature
        unsigned_inv <- parser.parse("fusion-inventories/node-with-server-role-attribute.ocs")
        token        <- TestInventoryDigestServiceV1.getKey(unsigned_inv)
        check        <- ZIO.acquireReleaseWith(getInputStream("fusion-inventories/node-with-server-role-attribute.ocs"))(is => effectUioUnit(is.close))(is =>
          for {
            parsed <- TestInventoryDigestServiceV1.parseSecurityToken(token._1)
            check  <- TestInventoryDigestServiceV1.check(parsed.publicKey, signature, is)
          } yield check)
      } yield {
        check
      }) === false
    }
  }

}
