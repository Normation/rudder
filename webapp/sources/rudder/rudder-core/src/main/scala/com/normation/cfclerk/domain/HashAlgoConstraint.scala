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
import enumeratum.*
import java.security.MessageDigest
import org.apache.commons.codec.binary.Hex
import org.apache.commons.codec.digest.Md5Crypt
import org.apache.commons.codec.digest.Sha2Crypt
import org.apache.commons.codec.digest.UnixCrypt

sealed abstract class HashAlgoConstraint(override val entryName: String) extends EnumEntry {
  def prefix: String = entryName
  def hash(input: Array[Byte]): String

  /**
   * Serialize an input to the storage format:
   * algotype:hash
   */
  def serialize(input: Array[Byte]): String = s"${prefix}:${hash(input)}"
  def unserialize(value: String): PureResult[String] = HashAlgoConstraint.unserialize(value) match {
    case Right((algo, v)) if algo == this => Right(v)
    case Right((algo, _))                 => Left(Inconsistency(s"Bad algorithm prefix: found ${algo.prefix}, was expecting ${this.prefix}"))
    case Left(eb)                         => Left(eb)
  }
}

object HashAlgoConstraint extends Enum[HashAlgoConstraint] {

  /////
  ///// Algos types
  /////

  /*
   * Actually do not hash the result
   */
  object PLAIN extends HashAlgoConstraint("plain") {
    override def hash(input: Array[Byte]): String = new String(input, "UTF-8")

  }

  object PreHashed extends HashAlgoConstraint("pre-hashed") {
    override def hash(input: Array[Byte]): String = new String(input, "UTF-8")

  }

  /*
   * Simple standard hash: MD5, SHA-1,256,512
   */
  object MD5 extends HashAlgoConstraint("md5") {
    private val md = MessageDigest.getInstance("MD5")
    override def hash(input: Array[Byte]): String = Hex.encodeHexString(md.digest(input))

  }

  object SHA1 extends HashAlgoConstraint("sha1") {
    private val md = MessageDigest.getInstance("SHA-1")
    override def hash(input: Array[Byte]): String = Hex.encodeHexString(md.digest(input))

  }

  object SHA256 extends HashAlgoConstraint("sha256") {
    private val md = MessageDigest.getInstance("SHA-256")
    override def hash(input: Array[Byte]): String = Hex.encodeHexString(md.digest(input))

  }

  object SHA512 extends HashAlgoConstraint("sha512") {
    private val md = MessageDigest.getInstance("SHA-512")
    override def hash(input: Array[Byte]): String = Hex.encodeHexString(md.digest(input))

  }

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
  object LinuxShadowMD5 extends HashAlgoConstraint("linux-shadow-md5") {
    override def hash(input: Array[Byte]): String = Md5Crypt.md5Crypt(input)

  }

  object LinuxShadowSHA256 extends HashAlgoConstraint("linux-shadow-sha256") {
    override def hash(input: Array[Byte]): String = Sha2Crypt.sha256Crypt(input)

  }

  object LinuxShadowSHA512 extends HashAlgoConstraint("linux-shadow-sha512") {
    override def hash(input: Array[Byte]): String = Sha2Crypt.sha512Crypt(input)

  }

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
  object AixMD5 extends HashAlgoConstraint("aix-smd5") {
    override def hash(input: Array[Byte]): String = AixPasswordHashAlgo.smd5(new String(input, "UTF-8"))

  }

  object AixSHA256 extends HashAlgoConstraint("aix-ssha256") {
    override def hash(input: Array[Byte]): String = AixPasswordHashAlgo.ssha256(new String(input, "UTF-8"))

  }

  object AixSHA512 extends HashAlgoConstraint("aix-ssha512") {
    override def hash(input: Array[Byte]): String = AixPasswordHashAlgo.ssha512(new String(input, "UTF-8"))

  }

  /**
   * Unix historical crypt algo (based on 56 bits DES)
   * Used in historical Unix systems.
   */
  object UnixCryptDES extends HashAlgoConstraint("unix-crypt-des") {
    override def hash(input: Array[Byte]): String = UnixCrypt.crypt(input)

  }

  sealed trait DerivedPasswordType {
    // name, for ex for unserialisation
    def name: String
    // the total mapping from a hashalgo to another
    def hash(h: HashAlgoConstraint): HashAlgoConstraint
  }

  object DerivedPasswordType {

    case object AIX extends DerivedPasswordType {
      final val name = "AIX"

      def hash(h: HashAlgoConstraint): HashAlgoConstraint = h match {
        case LinuxShadowMD5    => AixMD5
        case LinuxShadowSHA256 => AixSHA256
        case LinuxShadowSHA512 => AixSHA512
        case x                 => x
      }
    }

    case object Linux extends DerivedPasswordType {
      final val name = "Unix"

      def hash(h: HashAlgoConstraint): HashAlgoConstraint = h match {
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

  def values: IndexedSeq[HashAlgoConstraint] = findValues

  /**
   * A default order between hash algo constraints, for example
   * to always display them in consistant way between
   * dropdown/info/etc.
   * The order is totally arbitrary.
   */
  def sort(algos: Seq[HashAlgoConstraint]): Seq[HashAlgoConstraint] = {
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
    algos.sortBy(order)
  }

  private def algoNames(algos: Seq[HashAlgoConstraint]): String = {
    sort(algos).map(_.prefix.toUpperCase).mkString(", ")
  }

  def parse(algo: String): Either[String, HashAlgoConstraint] = withNameInsensitiveOption(algo)
    .toRight(s"Unknown algorithm ${algo}. List of know algorithm: ${algoNames(values)}")

  /*
   * Hash will be store with the format: algo:hash
   * So there is the regex to read them back.
   */

  private val format = """([\w-]+):(.*)""".r

  def unserialize(value: String): PureResult[(HashAlgoConstraint, String)] = {
    value match {
      case format(algo, h) =>
        HashAlgoConstraint.parse(algo) match {
          case Left(msg) => Left(Inconsistency(msg))
          case Right(a)  => Right((a, h))
        }
      case _               =>
        Left(
          Inconsistency(
            s"Bad format of serialized hashed value, expected format is: 'algorithm:hash', with algorithm among: ${algoNames(values.toSet.toSeq)}"
          )
        )
    }
  }
}
