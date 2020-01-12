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
import com.normation.rudder.domain.logger.PolicyGenerationLogger
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.util.encoders.Hex
import org.joda.time.DateTime
import net.liftweb.common._
import net.liftweb.util.Helpers.tryo
import com.normation.box._

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
) {

  val id                         = node.id
  val name                       = node.name
  val description                = node.description
  val state                      = node.state
  val isSystem                   = node.isSystem
  val isPolicyServer             = node.isPolicyServer
  val creationDate               = node.creationDate
  val nodeReportingConfiguration = node.nodeReportingConfiguration
  val properties                 = node.properties
  val policyMode                 = node.policyMode
  /**
   * Get a digest of the key in the proprietary CFEngine digest format. It is
   * formated as expected by CFEngine authentication module, i.e with the
   * "MD5=" prefix for community agent (resp. "SHA=") prefix for enterprise agent).
   */
  lazy val securityTokenHash: String = {

    def formatDigest(digest: Box[String], algo: String, tokenType: SecurityToken): String = {
      digest match {
        case Full(hash) => s"${algo}=${hash}"
        case eb:EmptyBox =>
          val msgForToken = tokenType match {
            case _: PublicKey   => "of CFEngine public key for"
            case _: Certificate => "for certificate of"
          }
          val e = eb ?~! s"Error when trying to get the CFEngine-${algo} digest ${msgForToken} node '${hostname}' (${id.value})"
          PolicyGenerationLogger.error(e.messageChain)
          ""
      }
    }

    agentsName.headOption.map(a => (a.agentType, a.securityToken)) match {

      case Some((AgentType.CfeCommunity, key:PublicKey)) =>
        formatDigest(CFEngineKey.getCfengineMD5Digest(key), "MD5", key)

      case Some((AgentType.CfeEnterprise, key:PublicKey)) =>
        formatDigest(CFEngineKey.getCfengineSHA256Digest(key), "SHA", key)

      case Some((AgentType.CfeCommunity, cert:Certificate)) =>
        formatDigest(CFEngineKey.getCfengineMD5CertDigest(cert), "MD5", cert)

      case Some((AgentType.CfeEnterprise, cert:Certificate)) =>
        formatDigest(CFEngineKey.getCfengineSHA256CertDigest(cert), "SHA", cert)

      case Some((AgentType.Dsc, _)) =>
        PolicyGenerationLogger.info(s"Node '${hostname}' (${id.value}) is a DSC node and a we do not know how to generate a hash yet")
        ""

      case Some((_, _)) =>
        PolicyGenerationLogger.info(s"Node '${hostname}' (${id.value}) has an unsuported key type (CFEngine agent with certificate?) and a we do not know how to generate a hash yet")
        ""

      case None =>
        PolicyGenerationLogger.info(s"Node '${hostname}' (${id.value}) doesn't have a registered public key")
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
          PolicyGenerationLogger.error(e.messageChain)
          ""
        }
      case Some(cert : Certificate) =>
        CFEngineKey.getSha256Digest(cert) match {
          case Full(hash) => hash
          case eb:EmptyBox =>
            val e = eb ?~! s"Error when trying to get the sha-256 digest of Certificate for node '${hostname}' (${id.value})"
            PolicyGenerationLogger.error(e.messageChain)
            ""
        }
      case None =>
        PolicyGenerationLogger.info(s"Node '${hostname}' (${id.value}) doesn't have a registered public key")
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
   * The hash by itself is the MD5 (for CFEngine community) or SHA-256 (for CFEngine enterprise)
   * sum of the byte array of the modulus concatenated with the byte array of
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
  def getCfengineMD5Digest(key: PublicKey): Box[String] = {
    getCfengineDigestFromCfeKey(key, "MD5")
  }

  def getCfengineSHA256Digest(key: PublicKey): Box[String] = {
    getCfengineDigestFromCfeKey(key, "SHA-256")
  }

  /*
   * A version of the digest that works on a certificate
   */
  def getCfengineMD5CertDigest(cert: Certificate): Box[String] = {
    for {
      c      <- cert.cert.toBox
      digest <- getCfengineDigest(c.getSubjectPublicKeyInfo, "MD5")
    } yield {
      digest
    }
  }

  /*
   * A version of the digest that works on a certificate
   */
  def getCfengineSHA256CertDigest(cert: Certificate): Box[String] = {
    for {
      c      <- cert.cert.toBox
      digest <- getCfengineDigest(c.getSubjectPublicKeyInfo, "SHA-256")
    } yield {
      digest
    }
  }

  protected def getCfengineDigestFromCfeKey(key: PublicKey, algo: String): Box[String] = {
    for {
                    // the parser able to read PEM files
                    // Parser may be null is the key is invalid
      parser     <- Box(Option(new PEMParser(new StringReader(key.key))))
                    // read the PEM b64 pubkey string
      pubkeyInfo <- tryo { parser.readObject.asInstanceOf[SubjectPublicKeyInfo] }
      digest     <- getCfengineDigest(pubkeyInfo, algo)
    } yield {
      digest
    }
  }


  /*
   * The actual implementation that can use either
   * "MD5" or "SHA-256" digest.
   */
  protected def getCfengineDigest(pubkeyInfo: SubjectPublicKeyInfo, algo: String): Box[String] = {
    for {
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
      digest     <- tryo { MessageDigest.getInstance(algo) }
    } yield {
      // here, we must use the hexa-string representation,
      // because if we directly use ".toByteArray", a leading
      // 0x00 is happened if the modulus is positif. It should
      // be, so we could also just take the tail. And it is node
      // if we directly hex-dump it. Strange, but better be sure.
      // We can note that the leading 0x00 is displayed in the
      // openssl command above, but that if we use it in the
      // md5 hash, we don't get the same result than CFEngine.
      digest.update(Hex.decode(rsaPubkey.getModulus.toString(16)))
      digest.update(rsaPubkey.getPublicExponent.toByteArray)
      Hex.toHexString(digest.digest)
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

  def getSha256Digest(cert: Certificate): Box[String] = {
    for {
      (pubkeyInfo,_) <- SecurityToken.parseCertificate(cert).toBox
      sha256         <- tryo { MessageDigest.getInstance("SHA-256") }
    } yield {
      sha256.update(pubkeyInfo.getEncoded)
      Hex.toHexString(sha256.digest)
    }
  }
}
