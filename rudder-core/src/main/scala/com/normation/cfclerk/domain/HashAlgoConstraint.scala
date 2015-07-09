/*
*************************************************************************************
* Copyright 2011 Normation SAS
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

package com.normation.cfclerk.domain

import com.normation.utils.HashcodeCaching
import net.liftweb.common.{ Box, Failure, Full }
import java.security.MessageDigest
import net.liftweb.common.EmptyBox
import org.apache.commons.codec.binary.Base64
import org.apache.commons.codec.binary.Hex
import java.security.SecureRandom
import scala.collection.mutable.StringBuilder
import java.lang.StringBuilder
import org.apache.commons.codec.binary.BaseNCodec
import org.eclipse.jgit.errors.NotSupportedException
import org.apache.commons.codec.digest.Md5Crypt
import org.apache.commons.codec.digest.Sha2Crypt
import org.apache.commons.codec.digest.UnixCrypt

object HashAlgoConstraint {

  def algorithmes = (
       PLAIN
    :: MD5 :: SHA1 :: SHA256 :: SHA512
    :: LinuxShadowMD5 :: LinuxShadowSHA256 :: LinuxShadowSHA512
    :: UnixCryptDES
    :: Nil
  )

  def algoNames(algos:Seq[HashAlgoConstraint]) =  algos.map( _.prefix.toUpperCase ).mkString(", ")

  def fromStringIn(algos:Seq[HashAlgoConstraint], algoName:String) = {
    algos.find( a => a.prefix == algoName.toLowerCase)
  }

  def fromString(algo:String) : Option[HashAlgoConstraint] = {
    fromStringIn(algorithmes, algo)
  }

  /*
   * Hash will be store with the format: algo:hash
   * So there is the regex to read them back.
   */

  private[this] val format = """([\w-]+):(.*)""".r
  def unserializeIn(algos: Seq[HashAlgoConstraint], value:String): Box[(HashAlgoConstraint, String)] = value match {
    case format(algo,h) => HashAlgoConstraint.fromStringIn(algos, algo) match {
      case None => Failure(s"Unknown algorithm ${algo}. List of know algorithme: ${algoNames(algos)}")
      case Some(a) => Full((a,h))
    }
    case _ => Failure(s"Bad format of serialized hashed value, expected format is: 'algorithme:hash', with algorithm among: ${algoNames(algos)}")
  }

  def unserialize(value:String): Box[(HashAlgoConstraint, String)] = {
    unserializeIn(algorithmes, value)
  }

}

sealed trait HashAlgoConstraint {
  def prefix: String
  def hash(input:Array[Byte]): String

  /**
   * Serialize an input to the storage format:
   * algotype:hash
   */
  def serialize(input:Array[Byte]): String = s"${prefix}:${hash(input)}"
  def unserialize(value:String) : Box[String] = HashAlgoConstraint.unserialize(value) match {
    case Full((algo, v)) if algo == this => Full(v)
    case Full((algo,_)) => Failure(s"Bad algorithm prefix: found ${algo.prefix}, was expecting ${this.prefix}")
    case eb: EmptyBox => eb
  }

}

/**
 * Actually do not hash the result
 */
object PLAIN  extends HashAlgoConstraint {
  override def hash(input:Array[Byte]) : String = new String(input, "UTF-8")
  override val prefix = "plain"
}

/**
 * Simple standard hash: MD5, SHA-1,256,512
 */
object MD5  extends HashAlgoConstraint {
  private[this] val md = MessageDigest.getInstance("MD5")
  override def hash(input:Array[Byte]) : String = Hex.encodeHexString(md.digest(input))
  override val prefix = "md5"
}

object SHA1 extends HashAlgoConstraint {
  private[this] val md = MessageDigest.getInstance("SHA-1")
  override def hash(input:Array[Byte]) : String = Hex.encodeHexString(md.digest(input))
  override val prefix = "sha1"
}

object SHA256 extends HashAlgoConstraint {
  private[this] val md = MessageDigest.getInstance("SHA-256")
  override def hash(input:Array[Byte]) : String = Hex.encodeHexString(md.digest(input))
  override val prefix = "sha256"
}

object SHA512 extends HashAlgoConstraint {
  private[this] val md = MessageDigest.getInstance("SHA-512")
  override def hash(input:Array[Byte]) : String = Hex.encodeHexString(md.digest(input))
  override val prefix = "sha512"
}

/**
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
object LinuxShadowMD5 extends HashAlgoConstraint {
  override def hash(input:Array[Byte]) : String = Md5Crypt.md5Crypt(input)
  override val prefix = "linux-shadow-md5"
}

object LinuxShadowSHA256 extends HashAlgoConstraint {
  override def hash(input:Array[Byte]) : String = Sha2Crypt.sha256Crypt(input)
  override val prefix = "linux-shadow-sha256"
}

object LinuxShadowSHA512 extends HashAlgoConstraint {
  override def hash(input:Array[Byte]) : String = Sha2Crypt.sha512Crypt(input)
  override val prefix = "linux-shadow-sha512"
}

/**
 * Unix historical crypt algo (based on 56 bits DES)
 * Used in historical Unix systems.
 */
object UnixCryptDES extends HashAlgoConstraint {
  override def hash(input:Array[Byte]) : String = UnixCrypt.crypt(input)
  override val prefix = "unix-crypt-des"
}