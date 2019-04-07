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

import java.io.InputStream
import java.util.Properties
import java.security.Signature

import com.normation.inventory.domain.InventoryResult._
import com.normation.inventory.services.core.ReadOnlyFullInventoryRepository
import com.normation.inventory.domain.{PublicKey => AgentKey, _}
import org.apache.commons.io.IOUtils
import org.bouncycastle.util.encoders.Hex
import scalaz.zio._
import scalaz.zio.syntax._

/**
 * We are using a simple date structure that handle the digest file
 * version and content
 */
sealed trait InventoryDigest

final case class InventoryDigestV1(
    algorithm: String
  , digest   : String
) extends InventoryDigest

/**
 * This trait allow to check digest file for an inventory.
 * It handles the parsing of the .sig file.
 * The actual checking is done in CheckInventoryDigest
 */
trait ParseInventoryDigestFile {
  def parse(is: InputStream): InventoryResult[InventoryDigest]
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
  def parse(is: InputStream): InventoryResult[InventoryDigest] = {

    val properties = new Properties()

    for {
      loaded  <- Task.effect {
                   import scala.collection.JavaConverters._
                   properties.load(is)
                   properties.asInstanceOf[java.util.Map[String, String]].asScala.toMap
                 } mapError  { ex =>
                    InventoryError.Deserialisation(s"Failed to load properties for the signature file: ${ex.getMessage}", ex)
                 }
      //check version

      v_ok    <- loaded.get("header").filter( _.trim.toLowerCase == "rudder-signature-v1" ).notOptional("could not read 'header'")
      algo    <- loaded.get("algorithm").map( _.trim.toLowerCase).notOptional("could not read 'algorithm'")
      algo_ok <- if(algo == "sha512") {  // in v1, we only accept sha512
                   UIO.unit
                 } else {
                   InventoryError.Crypto(s"The algorithm '${algo}' contains in the digest file is not authorized, only 'sha512' is.").fail
                 }
      digest  <- loaded.get("digest").map( _.trim).notOptional("could not read 'digest'")
    } yield {
      InventoryDigestV1(algo,digest)
    }
  }
}

trait CheckInventoryDigest {

  /**
   * Here, we want to calculate the digest. The good library for that is most likely
   * bouncy castle: https://www.bouncycastle.org/
   */
  def check(securityToken: SecurityToken, digest: InventoryDigest, inventoryStream: InputStream): InventoryResult[Boolean] = {
    securityToken match {
      case rudderKey : com.normation.inventory.domain.PublicKey =>
        rudderKey.publicKey.flatMap { pubKey =>
          Task.effect {
            val signature = Signature.getInstance("SHA512withRSA", "BC");
            signature.initVerify(pubKey);
            val data = IOUtils.toByteArray(inventoryStream)
            signature.update(data);
            digest match {
              case InventoryDigestV1(_,digest) =>
                val sig = Hex.decode(digest)
                signature.verify(sig)
            }
          } mapError { e =>
                InventoryError.CryptoEx("Error when trying to check crypto-signature of security token", e)
            }
        }

      // We don't sign with certificate for now
      case _ : Certificate => true.succeed
    }
  }

}

/**
 * How we get the key from the inventory received
 */
trait GetKey {

  def getKey (receivedInventory  : InventoryReport) : InventoryResult[(SecurityToken, KeyStatus)]

}

class InventoryDigestServiceV1(
    repo : ReadOnlyFullInventoryRepository
) extends ParseInventoryDigestFileV1 with GetKey with CheckInventoryDigest {

  /**
   * Get key in V1 will get the key from inventory data.
   * either an inventory has already been treated before, it will look into ldap repository
   * or if there was no inventory before, it will look for the key in the received inventory
   */
  def getKey (receivedInventory  : InventoryReport) : InventoryResult[(SecurityToken, KeyStatus)] = {

    def extractKey (node : NodeInventory) : InventoryResult[SecurityToken]= {
      for {
        agent <- node.agents.headOption.notOptional("There is no public key in inventory")
      } yield {
        agent.securityToken
      }
    }

    repo.get(receivedInventory.node.main.id).flatMap {
      case Some(storedInventory) =>
        val status = storedInventory.node.main.keyStatus
        val inventory  : InventoryResult[NodeInventory] = status match {
          case UndefinedKey =>
            storedInventory.node.agents.map(_.securityToken).headOption match {
              case None =>
                // There is no key and status is undefined, use received key
                receivedInventory.node.succeed
              case Some(securityToken) =>
                securityToken match {
                  case key : AgentKey =>
                    key.publicKey.foldM (
                      // Key stored is not valid and status is undefined try received key,
                      // There treat the case of the bootstrapped key for rudder root server
                      _ => receivedInventory.node.succeed
                    , // Stored key is valid, use it !
                      _ =>  storedInventory.node.succeed
                    )
                  case cert : Certificate =>
                    // We don't sign inventory with cert for now, use sorted one
                    storedInventory.node.succeed
                }
            }
          // Certified node always use stored inventory key
          case CertifiedKey => storedInventory.node.succeed
        }
        inventory.flatMap(i => extractKey(i).map((_,status)))
      case _ =>
        val status = receivedInventory.node.main.keyStatus
        extractKey(receivedInventory.node).map((_,status))
    }
  }
}
