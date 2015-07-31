/*
*************************************************************************************
* Copyright 2015 Normation SAS
*************************************************************************************
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Affero General Public License as
* published by the Free Software Foundation, either version 3 of the
* License, or (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU Affero GPL v3, the copyright holders add the following
* Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU Affero GPL v3
* licence, when you create a Related Module, this Related Module is
* not considered as a part of the work and may be distributed under the
* license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Affero General Public License for more details.
*
* You should have received a copy of the GNU Affero General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/agpl.html>.
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
import java.security.PublicKey
import com.normation.inventory.domain.KeyStatus
import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider


@RunWith(classOf[JUnitRunner])
class TestSignatureService extends Specification with Loggable {

  Security.addProvider(new BouncyCastleProvider());

  private[this] def getInputStream (path : String) = {
    import java.net.URL
    val url = this.getClass.getClassLoader.getResource(path)
    if(null == url) throw new NullPointerException(s"Resource with relative path '${path}' is null (missing resource? Spelling? Permissions?)")
    url.openStream()
  }

  private[this] implicit class TestParser(parser: FusionReportUnmarshaller) {
    def parse(reportRelativePath: String): InventoryReport = {
      val is = getInputStream(reportRelativePath)
      val report = parser.fromXml("report", is) match {
        case Full(e) => e
        case eb:EmptyBox =>
          logger.error(eb)
          val e = eb ?~! "Parsing error"
          e.rootExceptionCause match {
            case Full(ex) => throw new Exception(e.messageChain, ex)
            case _ => throw new Exception(e.messageChain)
          }
      }
      is.close()
      report
    }
  }

  private[this] object TestInventoryDigestServiceV1 extends ParseInventoryDigestFileV1 with GetKey with CheckInventoryDigest {

    /**
     * Get key in V1 will get the key from inventory data.
     * either an inventory has already been treated before, it will look into ldap repository
     * or if there was no inventory before, it will look for the key in the received inventory
     */
    def getKey (receivedInventory  : InventoryReport) : Box[(PublicKey, KeyStatus)] = {
      for {
        cfengineKey <- Box(receivedInventory.node.publicKeys.headOption) ?~! "There is no public key in inventory"
        keyStatus = receivedInventory.node.main.keyStatus
        publicKey <- cfengineKey.publicKey
      } yield {
      (publicKey,keyStatus)
      }
    }
  }

  val keyNorm = new PrintedKeyNormalizer
  val extension = new RudderPublicKeyParsing(keyNorm)

  val parser = new FusionReportUnmarshaller(
      new StringUuidGeneratorImpl
    , rootParsingExtensions = extension:: Nil
  )

  val signed_report = parser.parse("fusion-report/signed_inventory.ocs")
  val unsigned_report = parser.parse("fusion-report/node-with-server-role-attribute.ocs")

  def parseSignature(path: String) = {
     val is = getInputStream(path)
    TestInventoryDigestServiceV1.parse(is)
  }

  val boxedSignature = parseSignature("fusion-report/signed_inventory.ocs.sign")

  "Signature file" should {
    "contain use sha512 algorithm" in {
      boxedSignature match {
        case eb:EmptyBox => ko("not a signature :(")
        case Full(signature : InventoryDigestV1) =>
          signature.algorithm  must beEqualTo("sha512")
      }
    }
  }


  "a signed report" should {
    "Be ok if checked with correct signature" in {
      (for {
        signature <- boxedSignature
        (pubKey,_) <- TestInventoryDigestServiceV1.getKey(signed_report)
        inventoryStream = getInputStream("fusion-report/signed_inventory.ocs")
        check <- TestInventoryDigestServiceV1.check(pubKey, signature, inventoryStream)
      } yield {
        check
      }) match {
        case eb:EmptyBox =>
          val fail = eb ?~! "Could not check right inventory signature"
          ko(fail.messageChain)
        case Full(signature) =>
          signature === true

      }
    }

    "Be wrong if checked with wrong signature" in {
      (for {
        signature <- boxedSignature
        (pubKey,_) <- TestInventoryDigestServiceV1.getKey(unsigned_report)
        inventoryStream = getInputStream("fusion-report/node-with-server-role-attribute.ocs")
        check <- TestInventoryDigestServiceV1.check(pubKey, signature, inventoryStream)
      } yield {
        check
      }) match {
        case eb:EmptyBox =>
          val fail = eb ?~! "Could not check wrong inventory signature"
          ko(fail.messageChain)
        case Full(signature) =>
          signature === false

      }
    }
  }

}