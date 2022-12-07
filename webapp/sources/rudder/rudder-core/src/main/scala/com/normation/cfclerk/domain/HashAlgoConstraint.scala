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

package com.normation.cfclerk.domain

import com.normation.errors.Inconsistency
import com.normation.errors.PureResult
import java.security.MessageDigest
import org.apache.commons.codec.binary.Hex
import org.apache.commons.codec.digest.Md5Crypt
import org.apache.commons.codec.digest.Sha2Crypt
import org.apache.commons.codec.digest.UnixCrypt

private[domain] object HashAlgoConstraintDigests {
  lazy val md5    = MessageDigest.getInstance("MD5")
  lazy val sha1   = MessageDigest.getInstance("SHA-1")
  lazy val sha256 = MessageDigest.getInstance("SHA-256")
  lazy val sha512 = MessageDigest.getInstance("SHA-512")
}
import HashAlgoConstraintDigests.*

enum HashAlgoConstraint(val prefix: String)(val hash: Array[Byte] => String) {

  /**
   * Serialize an input to the storage format:
   * algotype:hash
   */
  def serialize(input: Array[Byte]): String             = s"${prefix}:${hash(input)}"
  def unserialize(value: String):    PureResult[String] = HashAlgoConstraint.unserialize(value) match {
    case Right((algo, v)) if algo == this => Right(v)
    case Right((algo, _))                 => Left(Inconsistency(s"Bad algorithm prefix: found ${algo.prefix}, was expecting ${this.prefix}"))
    case Left(eb)                         => Left(eb)
  }

  /////
  ///// Algos types
  /////

  /*
   * Actually do not hash the result
   */
  case PLAIN extends HashAlgoConstraint("plain")(new String(_, "UTF-8"))

  case PreHashed extends HashAlgoConstraint("pre-hashed")(new String(_, "UTF-8"))

  /*
   * Simple standard hash: MD5, SHA-1,256,512
   */
  case MD5 extends HashAlgoConstraint("md5")(input => Hex.encodeHexString(md5.digest(input)))

  case SHA1 extends HashAlgoConstraint("sha1")(input => Hex.encodeHexString(sha1.digest(input)))

  case SHA256 extends HashAlgoConstraint("sha256")(input => Hex.encodeHexString(sha256.digest(input)))

  case SHA512 extends HashAlgoConstraint("sha512")(input => Hex.encodeHexString(sha512.digest(input)))

  /*
   * Linux shadow hash, as explained here:
   * http://serverfault.com/questions/259722/how-to-generate-a-etc-shadow-compatible-password-for-ubuntu-10-04
   *
   * =>> $id$salt$encrypted
   *
   *    ID  | Method
   *    ---------------------------------------------------------
   *    1   | MD5
   *    2a  | Blowfish (not in mainline glibc; added in some Linux distributions)
   *    5   | SHA-256 (since glibc 2.7)
   *    6   | SHA-512 (since glibc 2.7)
   *
   * "salt" stands for the up to 16 characters following "$id$" in the salt.
   *
   * shadow-md5 / shadow-sha-(level)
   */
  case LinuxShadowMD5 extends HashAlgoConstraint("linux-shadow-md5")(Md5Crypt.md5Crypt(_))

  case LinuxShadowSHA256 extends HashAlgoConstraint("linux-shadow-sha256")(Sha2Crypt.sha256Crypt(_))

  case LinuxShadowSHA512 extends HashAlgoConstraint("linux-shadow-sha512")(Sha2Crypt.sha512Crypt(_))

  /*
   * AIX /etc/security/user hash, as explained here:
   *
   * =>> $id$salt$encrypted
   *
   *    ID  | Method
   *    ---------------------------------------------------------
   *    1   | MD5
   *    2a  | Blowfish (not in mainline glibc; added in some Linux distributions)
   *    5   | SHA-256 (since glibc 2.7)
   *    6   | SHA-512 (since glibc 2.7)
   *
   * "salt" stands for the up to 16 characters following "$id$" in the salt.
   *
   * shadow-md5 / shadow-sha-(level)
   */
  case AixMD5 extends HashAlgoConstraint("aix-smd5")(input => AixPasswordHashAlgo.smd5(new String(input, "UTF-8")))

  case AixSHA256 extends HashAlgoConstraint("aix-ssha256")(input => AixPasswordHashAlgo.ssha256(new String(input, "UTF-8")))

  case AixSHA512 extends HashAlgoConstraint("aix-ssha512")(input => AixPasswordHashAlgo.ssha512(new String(input, "UTF-8")))

  /**
   * Unix historical crypt algo (based on 56 bits DES)
   * Used in historical Unix systems.
   */
  case UnixCryptDES extends HashAlgoConstraint("unix-crypt-des")(UnixCrypt.crypt(_))
}

object HashAlgoConstraint {

  sealed trait DerivedPasswordType {
    // name, for ex for unserialisation
    def name: String
    // the total mapping from a hashalgo to another
    def hash(h: HashAlgoConstraint): HashAlgoConstraint
  }

  object DerivedPasswordType {

    case object AIX extends DerivedPasswordType {
      final val name = "AIX"

      def hash(h: HashAlgoConstraint) = h match {
        case LinuxShadowMD5    => AixMD5
        case LinuxShadowSHA256 => AixSHA256
        case LinuxShadowSHA512 => AixSHA512
        case x                 => x
      }
    }

    case object Linux extends DerivedPasswordType {
      final val name = "Unix"

      def hash(h: HashAlgoConstraint) = h match {
        case AixMD5    => LinuxShadowMD5
        case AixSHA256 => LinuxShadowSHA256
        case AixSHA512 => LinuxShadowSHA512
        case x         => x
      }
    }
    // solaris, etc: todo
  }

  /////
  ///// Generic methods on algos
  /////

  def algorithms = HashAlgoConstraint.values.toSet

  /**
   * A default order between hash algo constraints, for example
   * to always display them in consistant way between
   * dropdown/info/etc.
   * The order is totally arbitrary.
   */
  def sort(algos: Set[HashAlgoConstraint]): Seq[HashAlgoConstraint] = {
    def order(algo: HashAlgoConstraint): Int = algo match {
      case PLAIN             => 1
      case PreHashed         => 1
      case LinuxShadowMD5    => 21
      case LinuxShadowSHA256 => 22
      case LinuxShadowSHA512 => 23
      case AixMD5            => 31
      case AixSHA256         => 32
      case AixSHA512         => 33
      case UnixCryptDES      => 70
      case MD5               => 81
      case SHA1              => 82
      case SHA256            => 83
      case SHA512            => 84
    }
    algos.toSeq.sortBy(order)
  }

  def algoNames(algos: Set[HashAlgoConstraint]) = {
    sort(algos).map(_.prefix.toUpperCase).mkString(", ")
  }

  def fromStringIn(algos: Set[HashAlgoConstraint], algoName: String) = {
    algos.find(a => a.prefix == algoName.toLowerCase)
  }

  def fromString(algo: String): Option[HashAlgoConstraint] = {
    fromStringIn(algorithms, algo)
  }

  /*
   * Hash will be store with the format: algo:hash
   * So there is the regex to read them back.
   */

  private[this] val format = """([\w-]+):(.*)""".r
  def unserializeIn(algos: Set[HashAlgoConstraint], value: String): PureResult[(HashAlgoConstraint, String)] = value match {
    case format(algo, h) =>
      HashAlgoConstraint.fromStringIn(algos, algo) match {
        case None    => Left(Inconsistency(s"Unknown algorithm ${algo}. List of know algorithm: ${algoNames(algos)}"))
        case Some(a) => Right((a, h))
      }
    case _               =>
      Left(
        Inconsistency(
          s"Bad format of serialized hashed value, expected format is: 'algorithm:hash', with algorithm among: ${algoNames(algos)}"
        )
      )
  }

  def unserialize(value: String): PureResult[(HashAlgoConstraint, String)] = {
    unserializeIn(algorithms, value)
  }
}
