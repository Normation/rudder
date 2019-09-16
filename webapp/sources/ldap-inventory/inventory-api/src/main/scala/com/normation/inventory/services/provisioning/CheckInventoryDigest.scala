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
import java.security.PublicKey

import net.liftweb.common._
import java.util.Properties
import java.security.Signature

import com.normation.inventory.services.core.ReadOnlyFullInventoryRepository
import com.normation.inventory.domain.{PublicKey => AgentKey, _}
import org.apache.commons.io.IOUtils
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.util.encoders.Hex

import scala.util.control.NonFatal

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
  def parse(is: InputStream): Box[InventoryDigest]
}

/**
 * Parse a V1 file format:
 * -------
 * header=rudder-signature-v1
 * algorithm=${HASH}
 * digest=${SIGNATURE}
 * -------
 */
class ParseInventoryDigestFileV1 extends ParseInventoryDigestFile with Loggable {
  def parse(is: InputStream): Box[InventoryDigest] = {

    val properties = new Properties()

    for {
      loaded  <- try {
                   import scala.collection.JavaConverters._
                   properties.load(is)

                   Full(properties.asInstanceOf[java.util.Map[String, String]].asScala.toMap)
                 } catch {
                   case ex: Exception => Failure("Failed to load properties for the signature file", Full(ex), Empty)
                 }
      //check version

      v_ok    <- Box((loaded.get("header").filter( _.trim.toLowerCase == "rudder-signature-v1" ))) ?~! "could not read 'header'"
      algo    <- Box(loaded.get("algorithm").map( _.trim.toLowerCase)) ?~! "could not read 'algorithm'"
      algo_ok <- if(algo == "sha512") {  // in v1, we only accept sha512
                   Full("ok")
                 } else {
                   Failure(s"The algorithm '${algo}' contains in the digest file is not authorized, only 'sha512' is.")
                 }
      digest  <- Box(loaded.get("digest").map( _.trim)) ?~! "could not read 'digest'"
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
  def check(publicKey: PublicKey, digest: InventoryDigest, inventoryStream: InputStream): Box[Boolean] = {
    try {
      val signature = Signature.getInstance("SHA512withRSA", "BC");
      signature.initVerify(publicKey);
      val data = IOUtils.toByteArray(inventoryStream)
      signature.update(data);
      digest match {
        case InventoryDigestV1(_,digest) =>
          val sig = Hex.decode(digest)
          Full(signature.verify(sig))
      }
    } catch {
      case e : Exception =>
        Failure(e.getMessage())
    }
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
    publicKey: PublicKey
  , subject  : Option[List[(String, String)]]
)

object ParsedSecurityToken {
  val nodeidOID   = "0.9.2342.19200300.100.1.1"
  val hostnameOID = "2.5.4.3"
}

/**
 * How we get the key from the inventory received
 */
trait GetKey {

  def getKey (receivedInventory  : InventoryReport) : Box[(SecurityToken, KeyStatus)]

   def parseSecurityToken(token: SecurityToken): Box[ParsedSecurityToken] = {
      token match {
        case x:AgentKey    => x.publicKey.map(pk => ParsedSecurityToken(pk, None))
        case x:Certificate =>
          x.cert.flatMap { ch =>
            try {
              val c = new JcaX509CertificateConverter().getCertificate( ch )
              val dn = ch.getSubject.getRDNs.flatMap(_.getTypesAndValues.flatMap(tv => (tv.getType.toString, tv.getValue.toString) :: Nil)).toList
              Full(ParsedSecurityToken(c.getPublicKey, Some(dn)))
            } catch {
              case NonFatal(ex) => Failure(s"Error when trying to parse agent certificate information: ${ex.getMessage}")
            }
          }
      }
    }
}

class InventoryDigestServiceV1(
    repo : ReadOnlyFullInventoryRepository
) extends ParseInventoryDigestFileV1 with GetKey with CheckInventoryDigest {

  /**
   * Get key in V1 will get the key from inventory data.
   * either an inventory has already been treated before, it will look into ldap repository
   * or if there was no inventory before, it will look for the key in the received inventory
   */
  def getKey (receivedInventory  : InventoryReport) : Box[(SecurityToken, KeyStatus)] = {

    def extractKey (node : NodeInventory) : Box[SecurityToken]= {
      for {
        agent <- Box(node.agents.headOption) ?~! "There is no public key in inventory"
      } yield {
        agent.securityToken
      }
    }

    repo.get(receivedInventory.node.main.id) match {
      case Full(storedInventory) =>
        val keyStatus = storedInventory.node.main.keyStatus
        val inventory  : NodeInventory =
          //if inventory was deleted, don't care, use new one
          storedInventory.node.main.status match {
            case RemovedInventory => receivedInventory.node
            case _                => //in other case, check is the key update is valid
              keyStatus match {
              case UndefinedKey => // Trust On First Use (TOFU) (user may have reseted, etc)
                receivedInventory.node
              // Certified node always use stored inventory key
              case CertifiedKey => storedInventory.node
            }
          }
        extractKey(inventory).map((_, keyStatus))
      case eb:EmptyBox =>
        repo.exists(receivedInventory.node.main.id) match {
          case Full(false) =>
            val status = receivedInventory.node.main.keyStatus
            extractKey(receivedInventory.node).map((_,status))
          case _ =>
            eb ?~! s"Error when trying to check for node '${receivedInventory.node.main.id.value}' existence"
        }
    }
  }
}
