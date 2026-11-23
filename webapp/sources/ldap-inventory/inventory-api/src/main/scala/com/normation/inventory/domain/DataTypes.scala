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

package com.normation.inventory.domain

import com.normation.NamedZioLogger
import com.normation.errors.*
import com.normation.inventory.domain.InventoryError.CryptoEx
import com.normation.inventory.services.provisioning.ParsedSecurityToken
import java.io.StringReader
import java.security.MessageDigest
import java.util.Base64
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.pkcs.RSAPublicKey
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.util.encoders.Hex
import zio.*
import zio.json.*
import zio.syntax.*

/**
 * A file that contains all the simple data types, like Version,
 * MemorySize, Manufacturer, etc.
 */

/**
 * A simple class to denote a manufacturer
 * TODO : Should be merge with SoftwareEditor
 */
final case class Manufacturer(name: String) extends AnyVal

/**
 * A simple class to denote a software editor
 */
final case class SoftwareEditor(val name: String) extends AnyVal

// this class is used to decode JSON "securityToken" before key validation and typing
// (the alias is because at some point, it was named "token" in NodeFact)
final case class JsonDecSecurityToken(@jsonAliases("token") value: String)
final case class JsonEncSecurityToken(@jsonField("type") @jsonAliases("kind") kind: String, value: String)

/*
 * Agent security token: a `Certificate`, checked, or an `UnverifiedToken`, a text received or stored that is not one
 * certificate. Only certificates are published to Apache, relayd and agents, and a node is saved only with one.
 */
sealed trait SecurityToken {
  // text stored, and returned by the API
  def value: String
}

object SecurityToken {
  // both cases are stored as a certificate text, so a stored text becomes a certificate as soon as it has one
  implicit val securityTokenEncoder: JsonEncoder[SecurityToken] =
    DeriveJsonEncoder.gen[JsonEncSecurityToken].contramap(t => JsonEncSecurityToken(Certificate.kind, t.value))
  implicit val securityTokenDecoder: JsonDecoder[SecurityToken] =
    DeriveJsonDecoder.gen[JsonDecSecurityToken].map(t => fromStored(t.value))

  // Text received by Rudder (inventory): a certificate when it is exactly one.
  def fromReceived(text: String): SecurityToken = {
    Certificate.parse(text).getOrElse(UnverifiedToken(text))
  }

  /*
   * Text stored by Rudder: its first certificate. Tokens saved before #30193 are the received text, where only the
   * first PEM object was checked; other objects were sent by the node, so they are logged.
   */
  def fromStored(text: String): SecurityToken = {
    Certificate.stored(text) match {
      case Left(_)     => UnverifiedToken(text)
      case Right(cert) =>
        if (text.indexOf("-----BEGIN") != text.lastIndexOf("-----BEGIN")) {
          InventoryDataLogger.logEffect.error(
            s"Security: stored certificate '${cert.subject}' holds more than one PEM object, only the first one is kept. Stored text: ${text}"
          )
        }
        cert
    }
  }

  def certificate(token: SecurityToken): Either[InventoryError, Certificate] = {
    token match {
      case cert: Certificate     => Right(cert)
      case u:    UnverifiedToken =>
        Left(
          InventoryError.SecurityToken(
            s"Agent key is not one certificate in PEM format: ${Certificate.parse(u.value).fold(_.fullMsg, _ => "it was not checked")}"
          )
        )
    }
  }

  def parseCertificate(cert: Certificate): IO[InventoryError, (java.security.PublicKey, List[(String, String)])] = {
    cert.cert.flatMap { ch =>
      ZIO.attempt {
        val c  = new JcaX509CertificateConverter().getCertificate(ch)
        val dn = ch.getSubject.getRDNs
          .flatMap(_.getTypesAndValues.flatMap(tv => (tv.getType.toString, tv.getValue.toString) :: Nil))
          .toList
        (c.getPublicKey, dn)
      }.mapError(ex => CryptoEx(s"Error when trying to parse agent certificate information", ex))
    }
  }

  def checkCertificateSubject(nodeId: NodeId, subject: List[(String, String)]): IO[InventoryError, Unit] = {
    // format subject
    def formatSubject(list: List[(String, String)]) = list.map(kv => s"${kv._1}=${kv._2}").mkString(",")

    // in rudder, we ensure that at list one (k,v) pair is "UID = the node id". If missing, it's an error
    subject.find { case (k, v) => k == ParsedSecurityToken.nodeidOID } match {
      case None         =>
        InventoryError
          .SecurityToken(
            s"Certificate subject for node '${nodeId.value}' doesn't contain node ID in 'UID' attribute: ${formatSubject(subject)}"
          )
          .fail
      case Some((k, v)) =>
        if (v.trim.equalsIgnoreCase(nodeId.value)) {
          ZIO.unit
        } else {
          InventoryError
            .SecurityToken(
              s"Certificate subject for node '${nodeId.value}' doesn't contain same node ID in 'UID' attribute as inventory node ID: ${formatSubject(subject)}"
            )
            .fail
        }
    }
  }

  def checkCertificateForNode(nodeId: NodeId, token: SecurityToken): IO[InventoryError, Unit] = {
    for {
      cert   <- ZIO.fromEither(certificate(token))
      parsed <- parseCertificate(cert)
      _      <- checkCertificateSubject(nodeId, parsed._2)
    } yield ()
  }

  def parseValidate(key: String): Either[InventoryError, SecurityToken] = {
    Certificate.parse(key)
  }
}

// Text received or stored that is not one certificate in PEM format.
final case class UnverifiedToken(value: String) extends SecurityToken

/*
 * Data for one X509 certificate, in standard PEM, with what Rudder derives from it.
 * The format is normalized on read (and so the private constructor) so that Apache, relayd, etc all get the exact same
 * string representation. This is needed because BouncyCastle and OpenSSL do not accept the same text as valid:
 * BouncyCastle stops at a malformed BEGIN line, OpenSSL skips it and reads on. See https://issues.rudder.io/issues/30193
 *
 * Equality is on `pem`: the other fields derive from it.
 */
final class Certificate private (
    val pem:               String,
    val subject:           String,
    val firstRdnValue:     String,
    // SHA-256 of the DER public key, in base64 (relayd and curl pinning)
    val sha256Base64:      String,
    // MD5 of the RSA modulus and exponent, in hexadecimal (CFEngine key hash); None when the key is not RSA
    val cfengineMd5Digest: Option[String]
) extends SecurityToken {
  def value: String = pem

  def cert: IO[InventoryError, X509CertificateHolder] = ZIO.fromEither(Certificate.parseSingle(pem))

  override def equals(other: Any): Boolean = other match {
    case c: Certificate => c.pem == pem
    case _ => false
  }
  override def hashCode:           Int     = pem.hashCode
  override def toString:           String  = s"Certificate(${subject}, sha256=${sha256Base64})"
}

object Certificate {
  val kind = "certificate"
  val HEADER: String = "-----BEGIN CERTIFICATE-----"
  val FOOTER: String = "-----END CERTIFICATE-----"

  // Text received by Rudder (inventory, API): exactly one certificate.
  def parse(text: String): Either[InventoryError, Certificate] = {
    parseSingle(withHeader(text)).flatMap(build)
  }

  // Text stored by Rudder: its first PEM object, see SecurityToken.fromStored.
  def stored(text: String): Either[InventoryError, Certificate] = {
    readPem(withHeader(text), 1).flatMap {
      case (cert: X509CertificateHolder) :: Nil => build(cert)
      case Nil                                  => Left(notPem(text))
      case _                                    => Left(notCertificate(text))
    }
  }

  // A security token holds exactly one certificate: every check (subject UID, inventory signature, key hashes) reads it.
  private[domain] def parseSingle(pem: String): Either[InventoryError, X509CertificateHolder] = {
    readPem(pem, 2).flatMap {
      case (cert: X509CertificateHolder) :: Nil => Right(cert)
      case Nil                                  => Left(notPem(pem))
      case _ :: Nil                             => Left(notCertificate(pem))
      case _                                    =>
        Left(
          InventoryError.SecurityToken(
            s"Key '${pem}' contains more than one PEM object. Please use exactly one certificate in PEM format"
          )
        )
    }
  }

  private def build(cert: X509CertificateHolder): Either[InventoryError, Certificate] = {
    for {
      pem    <- toPem(cert)
      sha256 <- sha256Base64(cert)
    } yield {
      new Certificate(
        pem,
        cert.getSubject.toString,
        cert.getSubject.getRDNs().headOption.map(_.getFirst().getValue().toString).getOrElse(""),
        sha256,
        cfengineMd5Digest(cert)
      )
    }
  }

  // Standard PEM of the certificate: DER in base64, 64 characters per line, `\n` line ends.
  private def toPem(cert: X509CertificateHolder): Either[InventoryError, String] = {
    PureResult
      .attempt(s"Certificate '${cert.getSubject}' cannot be encoded") {
        Base64.getMimeEncoder(64, Array('\n'.toByte)).encodeToString(cert.getEncoded)
      }
      .left
      .map(ex => InventoryError.CryptoEx(ex.msg, ex.cause): InventoryError)
      .map(b64 => s"${HEADER}\n${b64}\n${FOOTER}")
  }

  private def sha256Base64(cert: X509CertificateHolder): Either[InventoryError, String] = {
    PureResult
      .attempt(s"Public key of certificate '${cert.getSubject}' cannot be hashed") {
        Base64.getEncoder.encodeToString(MessageDigest.getInstance("SHA-256").digest(cert.getSubjectPublicKeyInfo.getEncoded))
      }
      .left
      .map(ex => InventoryError.CryptoEx(ex.msg, ex.cause): InventoryError)
  }

  /*
   * CFEngine hashes the RSA modulus, from its hexadecimal form (BigInteger.toByteArray would add a leading 0x00 that
   * CFEngine does not hash), then the exponent.
   */
  private def cfengineMd5Digest(cert: X509CertificateHolder): Option[String] = {
    val keyInfo = cert.getSubjectPublicKeyInfo
    if (keyInfo.getAlgorithm.getAlgorithm != PKCSObjectIdentifiers.rsaEncryption) None
    else {
      PureResult
        .attempt("CFEngine digest") {
          val rsa    = RSAPublicKey.getInstance(keyInfo.parsePublicKey())
          val digest = MessageDigest.getInstance("MD5")
          // here, we must use the hexa-string representation,
          // because if we directly use ".toByteArray", a leading
          // 0x00 is happened if the modulus is positif. It should
          // be, so we could also just take the tail. And it is node
          // if we directly hex-dump it. Strange, but better be sure.
          // We can note that the leading 0x00 is displayed in the
          // openssl command above, but that if we use it in the
          // md5 hash, we don't get the same result than CFEngine.
          digest.update(Hex.decode(rsa.getModulus.toString(16)))
          digest.update(rsa.getPublicExponent.toByteArray)
          Hex.toHexString(digest.digest)
        }
        .toOption
    }
  }

  // Tokens of old inventories may be stored as one line, without header and footer.
  private def withHeader(text: String): String = {
    if (text.startsWith(HEADER)) text
    else {
      s"""${HEADER}
         |${text.grouped(80).mkString("\n")}
         |${FOOTER}""".stripMargin
    }
  }

  // At most `max` PEM objects, so the cost does not depend on the received text.
  private def readPem(pem: String, max: Int): Either[InventoryError, List[AnyRef]] = {
    PureResult.attempt {
      val parser = new PEMParser(new StringReader(pem))
      Iterator.continually(parser.readObject()).takeWhile(_ != null).take(max).toList
    }.left.map(ex => InventoryError.CryptoEx(s"Key '${pem}' cannot be parsed as a PEM certificate", ex.cause))
  }

  private def notPem(pem: String):         InventoryError =
    InventoryError.Crypto(s"Key '${pem}' is not a PEM object. Please use a certificate in PEM format")
  private def notCertificate(pem: String): InventoryError =
    InventoryError.Crypto(s"Key '${pem}' is not a certificate. Please use a certificate in PEM format")
}

/**
 * A simple class to denote version
 * Sub-class may be done to be specialized, like for
 * example "linux kernel version", "debian package version",
 * "ms patch version", etc.
 *
 * Comparison are really important in Version
 */
final class Version(val value: String) extends AnyVal {
  override def toString(): String = "[%s]".format(value)
}
object Version {
  implicit val ord: Ordering[Version] = Ordering.by(_.value)
}

object InventoryProcessingLogger extends NamedZioLogger {
  override def loggerName: String = "inventory-processing"
  object timing extends NamedZioLogger() { def loggerName = "inventory-processing.timing" }
}

object InventoryDataLogger extends NamedZioLogger {
  override def loggerName: String = "inventory-data"
}

sealed trait InventoryError extends RudderError

object InventoryError {

  final case class Crypto(msg: String)                         extends InventoryError
  final case class CryptoEx(hint: String, ex: Throwable)       extends InventoryError {
    def msg: String = hint + "; root exception was: " + ex.getMessage()
  }
  final case class AgentType(msg: String)                      extends InventoryError
  final case class SecurityToken(msg: String)                  extends InventoryError
  final case class Deserialisation(msg: String, ex: Throwable) extends InventoryError
  final case class Inconsistency(msg: String)                  extends InventoryError
  final case class System(msg: String)                         extends InventoryError
}
