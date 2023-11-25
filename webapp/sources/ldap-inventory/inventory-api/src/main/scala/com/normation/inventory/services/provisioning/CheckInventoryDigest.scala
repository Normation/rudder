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

package com.normation.inventory.services.provisioning

import com.normation.errors._
import com.normation.errors.IOResult
import com.normation.inventory.domain.{PublicKey => AgentKey, _}
import java.io.InputStream
import java.security.PublicKey
import java.security.Signature
import java.util.Properties
import org.apache.commons.io.IOUtils
import org.bouncycastle.util.encoders.Hex
import zio._
import zio.syntax._

/**
 * We are using a simple date structure that handle the digest file
 * version and content
 */
sealed trait InventoryDigest

final case class InventoryDigestV1(
    algorithm: String,
    digest:    String
) extends InventoryDigest

/**
 * This trait allow to check digest file for an inventory.
 * It handles the parsing of the .sig file.
 * The actual checking is done in CheckInventoryDigest
 */
trait ParseInventoryDigestFile {
  def parse(is: InputStream): IOResult[InventoryDigest]
}

/**
 * Parse a V1 file format:
 * -------
 * header=rudder-signature-v1
 * algorithm=${HASH}
 * digest=${SIGNATURE}
 * -------
 */
class ParseInventoryDigestFileV1 extends ParseInventoryDigestFile {
  def parse(is: InputStream): IOResult[InventoryDigest] = {

    val properties = new Properties()

    for {
      loaded <- ZIO.attempt {
                  import scala.jdk.CollectionConverters._
                  properties.load(is)
                  properties.asInstanceOf[java.util.Map[String, String]].asScala.toMap
                } mapError { ex =>
                  InventoryError.Deserialisation(s"Failed to load properties for the signature file: ${ex.getMessage}", ex)
                }
      // check version

      v_ok    <- loaded.get("header").filter(_.trim.equalsIgnoreCase("rudder-signature-v1")).notOptional("could not read 'header'")
      algo    <- loaded.get("algorithm").map(_.trim.toLowerCase).notOptional("could not read 'algorithm'")
      algo_ok <- ZIO.when(algo != "sha512") { // in v1, we only accept sha512
                   InventoryError
                     .Crypto(s"The algorithm '${algo}' contains in the digest file is not authorized, only 'sha512' is.")
                     .fail
                 }
      digest  <- loaded.get("digest").map(_.trim).notOptional("could not read 'digest'")
    } yield {
      InventoryDigestV1(algo, digest)
    }
  }
}

trait CheckInventoryDigest {

  /**
   * Here, we want to calculate the digest. The good library for that is most likely
   * bouncy castle: https://www.bouncycastle.org/
   */
  def check(publicKey: PublicKey, digest: InventoryDigest, inventoryStream: InputStream): IOResult[Boolean] = {
    ZIO.attempt {
      val signature = Signature.getInstance("SHA512withRSA", "BC");
      signature.initVerify(publicKey);
      val data      = IOUtils.toByteArray(inventoryStream)
      signature.update(data);
      digest match {
        case InventoryDigestV1(_, digest) =>
          val sig = Hex.decode(digest)
          signature.verify(sig)
      }
    } mapError { e => InventoryError.CryptoEx("Error when trying to check crypto-signature of security token", e) }
  }

}

/*
 * A data class to store parsed information about a Rudder SecurityToken
 * (either just a public key and optionnaly a subject, if coming from a certificate)
 *
 * In suject, the first element of the pair is an OID, and the second the value.
 * We only use (for now):
 * - 0.9.2342.19200300.100.1.1 : userid (UID) for the node ID
 * - 2.5.4.3: commonName for the hostname
 */
final case class ParsedSecurityToken(
    publicKey: PublicKey,
    subject:   Option[List[(String, String)]]
)

object ParsedSecurityToken {
  val nodeidOID   = "0.9.2342.19200300.100.1.1"
  val hostnameOID = "2.5.4.3"
}

/**
 * How we get the key from the inventory received
 */
trait GetKey {

  def getKey(receivedInventory: Inventory): IOResult[(SecurityToken, KeyStatus)]

  def parseSecurityToken(token: SecurityToken): IOResult[ParsedSecurityToken] = {
    token match {
      case x: AgentKey    => x.publicKey.map(pk => ParsedSecurityToken(pk, None))
      case x: Certificate =>
        SecurityToken.parseCertificate(x).map { case (p, s) => ParsedSecurityToken(p, Some(s)) }
    }
  }
}
