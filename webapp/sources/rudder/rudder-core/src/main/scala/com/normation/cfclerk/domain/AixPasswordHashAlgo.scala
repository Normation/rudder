/*
*************************************************************************************
* Copyright 2016 Normation SAS
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

import javax.crypto.spec.PBEKeySpec
import org.apache.commons.codec.digest.Md5Crypt
import javax.crypto.SecretKeyFactory
import net.liftweb.common.Loggable
import java.security.NoSuchAlgorithmException
import scala.util.Random
import net.liftweb.common.Full
import net.liftweb.common.Box
import net.liftweb.common.Failure
import net.liftweb.common.EmptyBox


/*
 * This file contains the implementation of the 4 supported algorithms in
 * AIX /etc/security/passwd file (with example hash string result for
 * the password "secret"):
 * - salted md5     : {smd5}tyiOfoE4$r5HleyKHVdL3dg9ouzcZ80
 * - salted sha1    : {ssha1}12$tyiOfoE4WXucUfh/$1olYn48enIIKGOOs0ve/GE.k.sF
 * - salted ssha256: {ssha256}12$tyiOfoE4WXucUfh/$YDkcqbY5oKk4lwQ4pVKPy8o4MqcfVpp1ZxxvSfP0.wS
 * - salted ssha512: {ssha512}10$tyiOfoE4WXucUfh/$qaLbOhKx3fwIu93Hkh4Z89Vr.otLYEhRGN3b3SAZFD3mtxhqWZmY2iJKf0KB/5fuwlERv14pIN9h4XRAZtWH..
 *
 * Appart for md5, which is the standard unix implementation and differs only for the
 * prefix ({smd5} in place of "$1", the other implementations differ SIGNIFICANTLY from
 * standard Unix crypt described at https://www.akkadia.org/drepper/SHA-crypt.txt. In fact,
 * they only kept:
 * - the number of bytes (and so chars) for the hash: 20 for ssha1, 32 for ssha256, 64 for ssh512
 * - the base64 encoding table (which is not the standard one but starts with "./012" etc
 *
 * What changed is:
 * - they use PBKDF2 HMAC-(sha1, sha256, sha512) in place of Sha-Crypt,
 * - they use a different padding table
 * - the number of iterations, named "rounds" in Unix crypt vocabulary, is not the number N
 *   found at the begining of the hash string (after the algo name). The number of iteration
 *   is actually  2^N, and N is called in /etc/security/pwdalg.cfg the "cost"
 *
 * Hope this decription may help other people find there way to generate AIX hash string.
 */
object AixPasswordHashAlgo extends Loggable {
  import java.lang.{ StringBuilder => JStringBuilder }

  /**
   * Table with characters for Sha-Crypt Base64 transformation,
   * and used by Aix even if they DON'T use sha-crypt.
   */
  final val SCB64Table = "./0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

  /*
   * Sha-Crypt Base64 byte encoding into chars.
   *
   * Convert 3 bytes into 0 to 3 chars from the B64Tables.
   * Output is written into the buffer given in input.
   *
   * This code is a Scala adaptation from org.apache.commons.codec.digest.B64
   *
   */
  def b64from24bit(b2: Byte, b1: Byte, b0: Byte, outNumChars: Int, buffer: JStringBuilder) {
    // The bit masking is necessary because the JVM byte type is signed!
    var w = ((b2 << 16) & 0x00ffffff) | ((b1 << 8) & 0x00ffff) | (b0 & 0xff)
    // It's effectively a "for" loop but kept to resemble the original C code.
    val n = outNumChars
    for(i <- 0 until n) {
      buffer.append(SCB64Table.charAt(w & 0x3f))
      w >>= 6
    }
  }

  /*
   * AIX {smd5} implementation.
   * @parameter pwd: the string password, in UTF-8, to hash
   * @parameter salt: a
   */
  def smd5(pwd: String, salt: Option[String] = None): String = {

    val s    = salt.getOrElse(getRandomSalt(8))
    val hash = Md5Crypt.md5Crypt(pwd.getBytes("UTF-8"), s, "")

    val sb = new java.lang.StringBuilder()
    sb.append("{smd5}")
    sb.append(hash)

    sb.toString
  }


  /*
   * Generic implementation of AIX {ssha*} hash scheme.
   * The hash format is {ssha*}NN$saltsalt$sha.crypt.base.64.encoded.hash
   * where NN is the cost (cost_num in /etc/security/pwdalg.cfg vocabulary),
   * a 0 padded int between 1 and 31, and saltsalt is the slat string,
   * by default 16 chars long.
   *
   */
  def ssha(pwd: String, salt: Option[String], cost: Int, sha: ShaSpec): Box[String] = {
    for {
      skf <- getSecretKeFactory(sha)
    } yield {
      doSsha(sha, skf)(pwd, salt, cost)
    }
  }

  /*
   * Create an AIX {ssha1} hash string from pwd. Optionaly, specify the
   * salt string to use (default to a random 16 chars string), and the "num_cost"
   * (defaults to 10, i.e 1024 iterations).
   *
   * If PBKDF2WithHmacSHA1 crypto algorithm is not available in the current JVM,
   * revert back to {smd5}.
   */
  def ssha1(pwd: String, salt: Option[String] = None, cost: Int = 10): String = {
    ssha1impl(pwd, salt, cost)
  }

  /*
   * Create an AIX {ssha256} hash string from pwd. Optionaly, specify the
   * salt string to use (default to a random 16 chars string), and the "num_cost"
   * (defaults to 10, i.e 1024 iterations).
   *
   * If PBKDF2WithHmacSHA256 crypto algorithm is not available in the current JVM,
   * revert back to {ssha1}.
   */
  def ssha256(pwd: String, salt: Option[String] = None, cost: Int = 10): String = {
    ssha256impl(pwd, salt, cost)
  }

  /*
   * Create an AIX {ssha256} hash string from pwd. Optionaly, specify the
   * salt string to use (default to a random 16 chars string), and the "num_cost"
   * (defaults to 10, i.e 1024 iterations).
   *
   * If PBKDF2WithHmacSHA512 crypto algorithm is not available in the current JVM,
   * revert back to {ssha1}.
   */
  def ssha512(pwd: String, salt: Option[String] = None, cost: Int = 10): String = {
    ssha512impl(pwd, salt, cost)
  }


  /////
  /////          Implementation details
  ///// (if you are looking to understand how AIX hashes its
  /////  passwords, that the part of interest)
  /////

  protected[domain] final def getSecretKeFactory(sha: ShaSpec): Box[SecretKeyFactory] = {
    try {
      Full(SecretKeyFactory.getInstance(s"PBKDF2WithHmac${sha.name}"))
    } catch {
      case ex: NoSuchAlgorithmException =>
        Failure(s"Your current Java installation does not support PBKDF2WithHmac${sha.name} algorithm, " +
            "which is necessary for {ssha256} hash")
    }
  }

  /////
  ///// Initialize the correct instance of hashing algo
  ///// based on the available secret key factory on the
  ///// jvm. The check is done at most one time by jvm session.
  /////

  /// ssha1 revert to smd5 - but all post-java6 JVM should be ok
  private[this] final lazy val ssha1impl = getSecretKeFactory(ShaSpec.SHA1) match {
    case Full(skf)  => doSsha(ShaSpec.SHA1, skf) _
    case e:EmptyBox =>
      // this should not happen, because PBKDF2WithHmacSHA1 is
      // in standard Java since Java6. But who knows..
      // Fallback to md5 hash.
      logger.error("Your current Java installation does not support PBKDF2WithHmacSHA1 algorithm, " +
          "which is necessary for {ssha1} hash. Falling back to {smd5} hashing scheme")

      (pwd: String, salt: Option[String], cost: Int) => smd5(pwd, salt)
  }


  /// ssha256 reverts to ssha1 - not so bad
  private[this] final lazy val ssha256impl = getSecretKeFactory(ShaSpec.SHA256) match {
    case Full(skf) => doSsha(ShaSpec.SHA256, skf) _
    case e:EmptyBox =>
      // this may happen on Java 7 and older version, because PBKDF2WithHmacSHA256
      // was introduced in Java 8.
      // Fallback to ssha1 hash.
      logger.error("Your current Java installation does not support PBKDF2WithHmacSHA256 algorithm, " +
          "which is necessary for {ssha256} hash. Falling back to {ssha1} hashing scheme")

      ssha1impl
  }

  /// ssha512 reverts to ssha1 - no so bad
  private[this] final lazy val ssha512impl = getSecretKeFactory(ShaSpec.SHA512) match {
    case Full(skf) => doSsha(ShaSpec.SHA512, skf) _
    case e:EmptyBox =>
      // this may happen on Java 7 and older version, because PBKDF2WithHmacSHA512
      // was introduced in Java 8.
      // Fallback to ssha1 hash.
      logger.error("Your current Java installation does not support PBKDF2WithHmacSHA512 algorithm, " +
          "which is necessary for {ssha256} hash. Falling back to {ssha1} hashing scheme")

      ssha1impl
  }

  /*
   * This one is not public - the caller must ensure that sha and SecretKeyFactory are compatible
   */
  protected[domain] final def doSsha(sha: ShaSpec, skf: SecretKeyFactory)(pwd: String, salt: Option[String], cost: Int): String = {
    val rounds = 2 << (cost-1)
    val s = salt.getOrElse(getRandomSalt(16)).getBytes("UTF-8")
    val spec: PBEKeySpec = new PBEKeySpec(pwd.toCharArray, s, rounds, 8*sha.byteNumber)

    val sb = new java.lang.StringBuilder()
    sb.append(sha.prefix)
    sb.append("%02d".format(cost))
    sb.append("$").append(new String(s)).append("$")
    sha.scb64Encode(skf.generateSecret(spec).getEncoded, sb)

    (sb.toString)
  }


  /*
   * Generic trait denoting the specificities of each
   * SHA variant in term of name, number of output
   * bytes, and way to encode them into a string.
   */
  sealed trait ShaSpec {
    // SHA version: SHA1, SHA256, SHA512
    def name      : String

    // algo prefix prepended in the final hash string.
    final lazy val prefix = s"{s${name.toLowerCase}}"

    // number of bytes of the hash before encoding
    def byteNumber: Int

    // encode the byte array resulting from the hash
    // into a Sha-Crypt Base64 string, with correct
    // byte switching and padding.
    // the input byte array must have byteNumber elements.
    def scb64Encode(bytes: Array[Byte], buffer: JStringBuilder): JStringBuilder

  }

  final object ShaSpec {
    // specific implementation for SHA1, SHA256 and SHA512

    final case object SHA1 extends ShaSpec {
      val name   = "SHA1"
      val byteNumber = 20
      def scb64Encode(bytes: Array[Byte], buffer: JStringBuilder): JStringBuilder = {
        b64from24bit(bytes( 0), bytes( 1), bytes( 2), 4, buffer)
        b64from24bit(bytes( 3), bytes( 4), bytes( 5), 4, buffer)
        b64from24bit(bytes( 6), bytes( 7), bytes( 8), 4, buffer)
        b64from24bit(bytes( 9), bytes(10), bytes(11), 4, buffer)
        b64from24bit(bytes(12), bytes(13), bytes(14), 4, buffer)
        b64from24bit(bytes(15), bytes(16), bytes(17), 4, buffer)
        b64from24bit(bytes(18), bytes(19), 0        , 3, buffer)

        buffer
      }
    }

    final case object SHA256 extends ShaSpec {
      val name   = "SHA256"
      val byteNumber = 32
      def scb64Encode(bytes: Array[Byte], buffer: JStringBuilder): JStringBuilder = {
        b64from24bit(bytes( 0), bytes( 1), bytes( 2), 4, buffer)
        b64from24bit(bytes( 3), bytes( 4), bytes( 5), 4, buffer)
        b64from24bit(bytes( 6), bytes( 7), bytes( 8), 4, buffer)
        b64from24bit(bytes( 9), bytes(10), bytes(11), 4, buffer)
        b64from24bit(bytes(12), bytes(13), bytes(14), 4, buffer)
        b64from24bit(bytes(15), bytes(16), bytes(17), 4, buffer)
        b64from24bit(bytes(18), bytes(19), bytes(20), 4, buffer)
        b64from24bit(bytes(21), bytes(22), bytes(23), 4, buffer)
        b64from24bit(bytes(24), bytes(25), bytes(26), 4, buffer)
        b64from24bit(bytes(27), bytes(28), bytes(29), 4, buffer)
        b64from24bit(bytes(30), bytes(31), 0        , 3, buffer)

        buffer
      }
    }


    final case object SHA512 extends ShaSpec {
      val name   = "SHA512"
      val byteNumber = 64
      def scb64Encode(bytes: Array[Byte], buffer: JStringBuilder): JStringBuilder = {
        b64from24bit(bytes( 0), bytes( 1), bytes( 2), 4, buffer)
        b64from24bit(bytes( 3), bytes( 4), bytes( 5), 4, buffer)
        b64from24bit(bytes( 6), bytes( 7), bytes( 8), 4, buffer)
        b64from24bit(bytes( 9), bytes(10), bytes(11), 4, buffer)
        b64from24bit(bytes(12), bytes(13), bytes(14), 4, buffer)
        b64from24bit(bytes(15), bytes(16), bytes(17), 4, buffer)
        b64from24bit(bytes(18), bytes(19), bytes(20), 4, buffer)
        b64from24bit(bytes(21), bytes(22), bytes(23), 4, buffer)
        b64from24bit(bytes(24), bytes(25), bytes(26), 4, buffer)
        b64from24bit(bytes(27), bytes(28), bytes(29), 4, buffer)
        b64from24bit(bytes(30), bytes(31), bytes(32), 4, buffer)
        b64from24bit(bytes(33), bytes(34), bytes(35), 4, buffer)
        b64from24bit(bytes(36), bytes(37), bytes(38), 4, buffer)
        b64from24bit(bytes(39), bytes(40), bytes(41), 4, buffer)
        b64from24bit(bytes(42), bytes(43), bytes(44), 4, buffer)
        b64from24bit(bytes(45), bytes(46), bytes(47), 4, buffer)
        b64from24bit(bytes(48), bytes(49), bytes(50), 4, buffer)
        b64from24bit(bytes(51), bytes(52), bytes(53), 4, buffer)
        b64from24bit(bytes(54), bytes(55), bytes(56), 4, buffer)
        b64from24bit(bytes(57), bytes(58), bytes(59), 4, buffer)
        b64from24bit(bytes(60), bytes(61), bytes(62), 4, buffer)
        b64from24bit(bytes(63), 0        , 0        , 2, buffer)

        buffer
      }
    }
  }

  /**
   * Generate a random string with the size given
   * as a parameter and chars taken from the Sha-Crypt
   * Base 64 table.
   */
  private[this] def getRandomSalt(size: Int): String = {
    val chars = for {
      i <- (0 until size).toArray
    } yield {
      SCB64Table.charAt(Random.nextInt(SCB64Table.length))
    }
    new String(chars)
  }

}

