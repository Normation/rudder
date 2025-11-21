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
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.openssl.PEMParser
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

sealed abstract class SecurityToken(val kind: String) {
  def key:   String
  def value: String
}

object SecurityToken {
  implicit val securityTokenEncoder: JsonEncoder[SecurityToken] =
    DeriveJsonEncoder.gen[JsonEncSecurityToken].contramap(t => JsonEncSecurityToken(t.kind, t.value))
  implicit val securityTokenDecoder: JsonDecoder[SecurityToken] =
    DeriveJsonDecoder.gen[JsonDecSecurityToken].map(t => Certificate(t.value))

  def kind(token: SecurityToken): String = {
    token.kind
  }

  def token(kind: String, value: String): Either[String, SecurityToken] = {
    kind match {
      case Certificate.kind => Right(Certificate(value))
      case _                =>
        Left(
          s"Value '${kind}' is not recognized as a valid security token, expecting '${Certificate.kind}'"
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

  def checkCertificateForNode(nodeId: NodeId, certificate: Certificate): IO[InventoryError, Unit] = {
    for {
      parsed <- parseCertificate(certificate)
      _      <- checkCertificateSubject(nodeId, parsed._2)
    } yield ()
  }

  // Use bouncy castle to parse the key and check if it's a public key or a certificate
  def parseValidate(key: String): Either[InventoryError, SecurityToken] = {
    PureResult
      .attempt(
        new PEMParser(new StringReader(key))
          .readObject()
      )
      .left
      .map(ex => InventoryError.CryptoEx(s"Key '${key}' cannot be parsed as a PEM certificate", ex.cause): InventoryError)
      .flatMap { obj =>
        obj match {
          case _: X509CertificateHolder => Right(Certificate(key))
          case null =>
            Left(
              InventoryError
                .Crypto(
                  s"Provided agent key cannot be parsed a certificate. Please use a certificate in PEM format"
                )
            )
          case _    =>
            Left(
              InventoryError
                .Crypto(
                  s"Provided agent key is in an unknown format. Please use a certificate in PEM format"
                )
            )
        }
      }
  }
}

object Certificate {
  val kind = "certificate"
}

final case class Certificate(value: String) extends SecurityToken(Certificate.kind) {

  // Value of the key may be stored (with old fusion inventory version) as one line and without rsa header and footer, we should add them if missing and format the key
  val key:  String                                    = {
    if (value.startsWith("-----BEGIN CERTIFICATE-----")) {
      value
    } else {
      s"""-----BEGIN CERTIFICATE-----
         |${value.grouped(80).mkString("\n")}
         |-----END CERTIFICATE-----""".stripMargin
    }
  }
  def cert: IO[InventoryError, X509CertificateHolder] = {
    for {
      reader <- ZIO.attempt {
                  new PEMParser(new StringReader(key))
                } mapError { e => InventoryError.CryptoEx(s"Key '${key}' cannot be parsed as a valid certificate (PEM)", e) }
      obj    <- ZIO.attempt(reader.readObject()).mapError { e =>
                  InventoryError.CryptoEx(s"Key '${key}' cannot be parsed as a valid certificate (read)", e)
                }
      res    <- obj match {
                  case a: X509CertificateHolder =>
                    a.succeed
                  case null =>
                    InventoryError.Crypto(s"Key '${key}' cannot be parsed as a valid certificate (not a valid PEM object)").fail
                  case _    => InventoryError.Crypto(s"Key '${key}' cannot be parsed as a valid certificate (unexpected type )").fail
                }
    } yield {
      res
    }
  }
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
