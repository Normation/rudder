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

package com.normation.inventory.services.provisioning

import java.io.InputStream
import net.liftweb.common._
import java.util.Properties
import java.security.Signature
import java.security.PublicKey
import org.apache.commons.io.IOUtils
import javax.xml.bind.DatatypeConverter
import com.normation.inventory.services.core.ReadOnlyFullInventoryRepository
import com.normation.inventory.domain._



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
  def check(pubKey: PublicKey, digest: InventoryDigest, inventoryStream: InputStream): Box[Boolean] = {
    try {
      val signature = Signature.getInstance("SHA512withRSA", "BC");
      signature.initVerify(pubKey);
      val data = IOUtils.toByteArray(inventoryStream)
      signature.update(data);
      digest match {
        case InventoryDigestV1(_,digest) =>
        val sig = DatatypeConverter.parseHexBinary(digest)

        Full(signature.verify(sig))
      }
    } catch {
      case e : Exception =>
        Failure(e.getMessage())
    }
  }

}

/**
 * How we get the key from the inventory received
 */
trait GetKey {

  def getKey (receivedInventory  : InventoryReport) : Box[(PublicKey, KeyStatus)]

}

class InventoryDigestServiceV1(
    repo : ReadOnlyFullInventoryRepository
) extends ParseInventoryDigestFileV1 with GetKey with CheckInventoryDigest {

  /**
   * Get key in V1 will get the key from inventory data.
   * either an inventory has already been treated before, it will look into ldap repository
   * or if there was no inventory before, it will look for the key in the received inventory
   */
  def getKey (receivedInventory  : InventoryReport) : Box[(PublicKey, KeyStatus)] = {
    for {
      nodeInventory <- repo.get(receivedInventory.node.main.id) match {
        case Full(storedInventory) =>
          Full(storedInventory.node)
        case _ =>
          Full(receivedInventory.node)
      }
      cfengineKey <- Box(nodeInventory.publicKeys.headOption) ?~! "There is no public key in inventory"
      publicKey <- cfengineKey.publicKey
      keyStatus = nodeInventory.main.keyStatus
    } yield {
      (publicKey,keyStatus)
    }
  }
}
