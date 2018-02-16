/*
*************************************************************************************
* Copyright 2011 Normation SAS
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

package com.normation.rudder.domain.nodes

import java.io.StringReader
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec

import com.normation.inventory.domain._
import com.normation.utils.HashcodeCaching

import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.util.encoders.Hex
import org.joda.time.DateTime

import net.liftweb.common._
import net.liftweb.util.Helpers.tryo

final case class MachineInfo(
    id          : MachineUuid
  , machineType : MachineType
  , systemSerial: Option[String]
  , manufacturer: Option[Manufacturer]
)

/**
 * A NodeInfo is a read only object containing the information that will be
 * always useful about a node
 */
final case class NodeInfo(
    node           : Node
  , hostname       : String
  , machine        : Option[MachineInfo]
  , osDetails      : OsDetails
  , ips            : List[String]
  , inventoryDate  : DateTime
  , keyStatus      : KeyStatus
  , agentsName     : Seq[AgentInfo]
  , policyServerId : NodeId
  , localAdministratorAccountName: String
  //for now, isPolicyServer and server role ARE NOT
  //dependant. So EXPECTS inconsistencies.
  //TODO: remove isPolicyServer, and pattern match on
  //      on role everywhere.
  , serverRoles    : Set[ServerRole]
  , archDescription: Option[String]
  , ram            : Option[MemorySize]
  , timezone       : Option[NodeTimezone]
) extends HashcodeCaching with Loggable{

  val id                         = node.id
  val name                       = node.name
  val description                = node.description
  val isBroken                   = node.isBroken
  val isSystem                   = node.isSystem
  val isPolicyServer             = node.isPolicyServer
  val creationDate               = node.creationDate
  val nodeReportingConfiguration = node.nodeReportingConfiguration
  val properties                 = node.properties
  val policyMode                 = node.policyMode

  /**
   * Get a digest of the key in the proprietary CFEngine
   * digest format.
   */
  lazy val securityTokenHash: String = {
    agentsName.headOption.map(_.securityToken) match {
      case Some(key : PublicKey) =>
        CFEngineKey.getCfengineDigest(key) match {
          case Full(hash) =>
            hash
          case eb:EmptyBox =>
            val e = eb ?~! s"Error when trying to get the CFEngine-MD5 digest of CFEngine public key for node '${hostname}' (${id.value})"
            logger.error(e.messageChain)
            ""
        }
      case Some(cert : Certificate) =>
        logger.info(s"Node '${hostname}' (${id.value}) is a Dsc node and a we do not know how to generate a hash yet")
        ""
      case None =>
        logger.info(s"Node '${hostname}' (${id.value}) doesn't have a registered public key")
        ""
    }
  }

  /**
   * Get a sha-256 digest (of the DER byte sequence) of the key.
   *
   * This method never fails, and if we are not able to parse
   * the store key, or if no key is store, it return an empty
   * string. Logs are used to track problems.
   *
   */
  lazy val sha256KeyHash: String = {
    agentsName.headOption.map(_.securityToken) match {
      case Some(publicKey : PublicKey) =>
        CFEngineKey.getSha256Digest(publicKey) match {
        case Full(hash) =>
          hash
        case eb:EmptyBox =>
          val e = eb ?~! s"Error when trying to get the sha-256 digest of CFEngine public key for node '${hostname}' (${id.value})"
          logger.error(e.messageChain)
          ""
        }
      case Some(cert : Certificate) =>
        logger.info(s"Node '${hostname}' (${id.value}) is a Dsc node and a we do not know how to generate a hash yet")
        ""
      case None =>
        logger.info(s"Node '${hostname}' (${id.value}) doesn't have a registered public key")
        ""

    }

  }
}

/*
 * An object to deal with the specificities of CFEngine keys.
 */
object CFEngineKey {

  /*
   * CFengine public keys are store on the server in
   * /var/rudder/cfengine-community/ppkeys/node-MD5-hash.pub .
   *
   * The content of these file is an RSA encoded key, something
   * looking like:
   *
   *   -----BEGIN RSA PUBLIC KEY-----
   *   MIIBCAKCAQEAv76gYG9OaFpc0eBeUXDM3WsRWyuHco3DpWnKrrpqQwylpEH26gRb
   *   cu/L5KWc1ihj1Rv/AU3dkQL5KdXatSrWOLUMmYcQc5DYSnZacbdHIGLn11w1PHsw
   *   9P2pivwQyIF3k4zqANtlZ3iZN4AXZpURI4VVhiBYPwZ4XgHPibcuJHiyNyymiHpT
   *   HX9H0iaEIwyJMPjzRH+piFRmSeUylHfQLqb6AkD3Dg3Nxe9pbxNbk1saqgHFF4kd
   *   Yh3O5rVto12XqisGWIbsmsT0XFr6V9+/sde/lpjI4AEcHR8oFYX5JP9/SXPuRJfQ
   *   lEl8vn5PHTY0mMrNAcM7+rzpkOW2c7b8bwIBIw==
   *   -----END RSA PUBLIC KEY-----
   *
   * You can see details with the following command:
   *
   * openssl rsa -RSAPublicKey_in -in /var/rudder/cfengine-community/ppkeys/node-MD5-hash.pub -text
   *
   * The hash by itself is the MD5 sum of the byte array
   * of the modulus concatenated with the byte array of
   * the exponent, both taken in big endian binary form.
   * The corresponding openssl C code used in CFEngine for
   * that is:
   *   parse the key then
   *   EVP_DigestInit(&context, md); # extract modulus in big endian binary form
   *   actlen = BN_bn2bin(key->n, buffer);
   *   EVP_DigestUpdate(&context, buffer, actlen); # extract exponent in big endian binary form
   *   actlen = BN_bn2bin(key->e, buffer);
   *   EVP_DigestUpdate(&context, buffer, actlen);
   *   EVP_DigestFinal(&context, digest, &md_len);
   *
   * In Scala, we need to use bouncy castle, because nothing
   * native read PEM / RSA file, and I don't like parsing
   * ASN.1 that much.
   *
   * Oh, and Java Security API force a lot of casting.
   * This is insane.
   *
   * Caution: to use the complete key, with header and footer, you need to use key.key
   */
  def getCfengineDigest(key: PublicKey): Box[String] = {
    for {
                    // the parser able to read PEM files
                    // Parser may be null is the key is invalid
      parser     <- Box(Option(new PEMParser(new StringReader(key.key))))
                    // read the PEM b64 pubkey string
      pubkeyInfo <- tryo { parser.readObject.asInstanceOf[SubjectPublicKeyInfo] }
                    // check that the pubkey info is the one of an RSA key,
                    // retrieve corresponding Key factory.
      keyFactory <- pubkeyInfo.getAlgorithm.getAlgorithm match {
                      case PKCSObjectIdentifiers.rsaEncryption =>
                        Full(KeyFactory.getInstance("RSA"))
                      case algo => //not supported
                        Failure(s"The CFEngine public key used an unsupported algorithm '${algo}'. Only RSA is supported")
                    }
                    // actually decode the key...
      keyspec    <- tryo { new X509EncodedKeySpec(pubkeyInfo.getEncoded) }
                    // into an RSA public key.
      rsaPubkey  <- tryo { keyFactory.generatePublic(keyspec).asInstanceOf[RSAPublicKey] }
      md5        <- tryo { MessageDigest.getInstance("MD5") }
    } yield {
      // here, we must use the hexa-string representation,
      // because if we directly use ".toByteArray", a leading
      // 0x00 is happened if the modulus is positif. It should
      // be, so we could also just take the tail. And it is node
      // if we directly hex-dump it. Strange, but better be sure.
      // We can note that the leading 0x00 is displayed in the
      // openssl command above, but that if we use it in the
      // md5 hash, we don't get the same result than CFEngine.
      md5.update(Hex.decode(rsaPubkey.getModulus.toString(16)))
      md5.update(rsaPubkey.getPublicExponent.toByteArray)
      Hex.toHexString(md5.digest)
    }
  }

  /**
   * Get the sha256 digest of the public get.
   * This is a direct digest of the encoded key in binary
   * format, i.e neither modulus nor exponent is extracted
   * contrary to CFEngine dedicated format.
   *
   * The return digest is given in sha256. If the sha-256
   * engine is not available (extremly unlikly, it's a
   * standard JCA algo), an error is returned.
   *
   * The result is a hex string of the digest.
   */
  def getSha256Digest(key: PublicKey): Box[String] = {
    for {
                    // the parser able to read PEM files
                    // Parser may be null is the key is invalid
      parser     <- Box(Option(new PEMParser(new StringReader(key.key))))
                    // read the PEM b64 pubkey string
      pubkeyInfo <- tryo { parser.readObject.asInstanceOf[SubjectPublicKeyInfo] }
      sha256     <- tryo { MessageDigest.getInstance("SHA-256") }
    } yield {
      sha256.update(pubkeyInfo.getEncoded)
      Hex.toHexString(sha256.digest)
    }
  }
}
