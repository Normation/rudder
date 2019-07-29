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

import com.normation.utils.Utils._
import com.normation.utils.HashcodeCaching
import org.bouncycastle.openssl.PEMParser
import java.io.StringReader

import com.normation.NamedZioLogger
import com.normation.errors._
import com.normation.inventory.domain.InventoryError.CryptoEx
import com.normation.inventory.services.provisioning.ParsedSecurityToken
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import zio._
import zio.syntax._

/**
 * A file that contains all the simple data types, like Version,
 * MemorySize, Manufacturer, etc.
 */

/**
 * A simple class to denote a manufacturer
 * TODO : Should be merge with SoftwareEditor
 */
final case class Manufacturer(name:String) extends HashcodeCaching { assert(!isEmpty(name)) }

/**
 * A simple class to denote a software editor
 */
final case class SoftwareEditor(val name:String) extends HashcodeCaching { assert(!isEmpty(name)) }

sealed trait SecurityToken {
  def key : String
}

object SecurityToken {
  def kind(token : SecurityToken) = {
    token match {
      case _: PublicKey   => PublicKey.kind
      case _: Certificate => Certificate.kind
    }
  }

  def parseCertificate(cert: Certificate): IO[InventoryError, (java.security.PublicKey, List[(String, String)])] = {
    cert.cert.flatMap { ch =>
      IO.effect {
        val c = new JcaX509CertificateConverter().getCertificate( ch )
        val dn = ch.getSubject.getRDNs.flatMap(_.getTypesAndValues.flatMap(tv => (tv.getType.toString, tv.getValue.toString) :: Nil)).toList
        (c.getPublicKey, dn)
      }.mapError { ex =>
        CryptoEx(s"Error when trying to parse agent certificate information", ex)
      }
    }
  }

  def checkCertificateSubject(nodeId: NodeId, subject: List[(String, String)]): IO[InventoryError, Unit] = {
    //format subject
    def formatSubject(list: List[(String, String)]) = list.map(kv => s"${kv._1}=${kv._2}").mkString(",")

    // in rudder, we ensure that at list one (k,v) pair is "UID = the node id". If missing, it's an error
    subject.find { case (k,v) => k == ParsedSecurityToken.nodeidOID } match {
      case None        => InventoryError.SecurityToken(s"Certificate subject doesn't contain node ID in 'UID' attribute: ${formatSubject(subject)}").fail
      case Some((k,v)) =>
        if(v.trim.toLowerCase == nodeId.value.toLowerCase) {
          UIO.unit
        } else {
          InventoryError.SecurityToken(s"Certificate subject doesn't contain same node ID in 'UID' attribute as inventory node ID: ${formatSubject(subject)}").fail
        }
    }
  }

  def checkCertificateForNode(nodeId: NodeId, certificate: Certificate): IO[InventoryError, Unit] = {
    for {
      parsed <- parseCertificate(certificate)
      _      <- checkCertificateSubject(nodeId, parsed._2)
    } yield ()
  }
}

object PublicKey {
  val kind = "publicKey"
}
object Certificate {
  val kind = "certificate"
}
/**
 * A simple class to denote a software cryptographic public key
 */
final case class PublicKey(value : String) extends SecurityToken with HashcodeCaching { assert(!isEmpty(value))

  // Value of the key may be stored (with old fusion inventory version) as one line and without rsa header and footer, we should add them if missing and format the key
  val key = {
    if (value.startsWith("-----BEGIN RSA PUBLIC KEY-----")) {
      value
    } else {
      s"""-----BEGIN RSA PUBLIC KEY-----
      |${value.grouped(80).mkString("\n")}
      |-----END RSA PUBLIC KEY-----""".stripMargin
    }
  }
  def publicKey : IOResult[java.security.PublicKey] = {
    IO.effect {
      (new PEMParser(new StringReader(key))).readObject()
    }.mapError { ex =>
      InventoryError.CryptoEx(s"Key '${key}' cannot be parsed as a public key", ex)
    }.flatMap { obj =>
      obj match {
        case a : SubjectPublicKeyInfo =>
          (new JcaPEMKeyConverter().getPublicKey(a)).succeed
        case _ => InventoryError.Crypto(s"Key '${key}' cannot be parsed as a public key").fail
      }
    }
  }
}

final case class Certificate(value : String) extends SecurityToken with HashcodeCaching { assert(!isEmpty(value))

  // Value of the key may be stored (with old fusion inventory version) as one line and without rsa header and footer, we should add them if missing and format the key
  val key = {
    if (value.startsWith("-----BEGIN CERTIFICATE-----")) {
      value
    } else {
      s"""-----BEGIN CERTIFICATE-----
      |${value.grouped(80).mkString("\n")}
      |-----END CERTIFICATE-----""".stripMargin
    }
  }
  def cert : IO[InventoryError, X509CertificateHolder] = {
    for {
      reader <- IO.effect {
                  new PEMParser(new StringReader(key))
                } mapError { e =>
                  InventoryError.CryptoEx(s"Key '${key}' cannot be parsed as a valid certificate", e)
                }
      obj    <- IO.effect(reader.readObject()).mapError { e =>
                  InventoryError.CryptoEx(s"Key '${key}' cannot be parsed as a valid certificate", e)
                }
      res    <- obj match {
                  case a : X509CertificateHolder =>
                    a.succeed
                  case _ => InventoryError.Crypto(s"Key '${key}' cannot be parsed as a valid certificate").fail
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
final class Version(val value:String) extends Comparable[Version] {
  require(!isEmpty(value))

  override def compareTo(other:Version) = this.value.compareTo(other.value)
  override def toString() = "[%s]".format(value)

  //subclass have to override that
  def canEqual(other:Any) = other.isInstanceOf[Version]

  override def hashCode() = 31 * value.hashCode

  override def equals(other:Any) = other match {
    case that:Version => (that canEqual this) && this.value == that.value
    case _ => false
  }

}


object InventoryLogger extends NamedZioLogger(){ def loggerName = "inventory-logger"}


sealed trait InventoryError extends RudderError

object InventoryError {

  final case class Crypto(msg: String) extends InventoryError
  final case class CryptoEx(hint: String, ex: Throwable) extends InventoryError {
    def msg = hint + "; root exception was: " + ex.getMessage()
  }
  final case class AgentType(msg: String) extends InventoryError
  final case class SecurityToken(msg: String) extends InventoryError
  final case class Deserialisation(msg: String, ex: Throwable) extends InventoryError
  final case class Inconsistency(msg: String) extends InventoryError
  final case class System(msg: String) extends InventoryError
}


