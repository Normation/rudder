/*
 *************************************************************************************
 * Copyright 2025 Normation SAS
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

// Provides an API similar to BouncyCastle's Bcrypt implementation, as the Argon2 implementation
// provides a lower level.

package com.normation.rudder.users

import java.security.MessageDigest
import java.util.Base64
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import zio.Chunk

// References
// * RFC9106: https://www.rfc-editor.org/rfc/rfc9106.html#name-parameter-choice
// * OWASP Cheat Sheet: https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html#argon2id
// * Argon2 1.3 specs: https://www.cryptolux.org/images/0/0d/Argon2.pdf

/// Argon2 hash type. Use "id", the recommended variant in most cases.
private val variant = Argon2Variant(Argon2Parameters.ARGON2_id)

/// Latest version
private val defaultVersion = Argon2Version(Argon2Parameters.ARGON2_VERSION_13)

// 32 bytes
// Used by most implementations.
private val hashSize = Argon2HashSize(32)

// 16 bytes = 128 bits
// The recommended value in the RFC, deemed "sufficient for all applications" in the specs.
private val saltSize = Argon2SaltSize(16)

opaque type Argon2Variant = Int

object Argon2Variant {
  def apply(v: Int): Argon2Variant = v

  extension(v: Argon2Variant) {
    def toInt: Int = v
  }
}

opaque type Argon2Version = Int

object Argon2Version {
  def apply(v: Int): Argon2Version = v

  extension(v: Argon2Version) {
    def toInt: Int = v
  }
}

opaque type Argon2Memory = Int

object Argon2Memory {
  def apply(v: Int): Argon2Memory = v

  extension(v: Argon2Memory) {
    def toInt: Int = v
  }
}

opaque type Argon2Parallelism = Int

object Argon2Parallelism {
  def apply(v: Int): Argon2Parallelism = v

  extension(v: Argon2Parallelism) {
    def toInt: Int = v
  }
}

opaque type Argon2Iterations = Int

object Argon2Iterations {
  def apply(v: Int): Argon2Iterations = v

  extension(v: Argon2Iterations) {
    def toInt: Int = v
  }
}

opaque type Argon2HashSize = Int

object Argon2HashSize {
  def apply(v: Int): Argon2HashSize = v

  extension(v: Argon2HashSize) {
    def toInt: Int = v
  }
}

opaque type Argon2SaltSize = Int

object Argon2SaltSize {
  def apply(v: Int): Argon2SaltSize = v

  extension(v: Argon2SaltSize) {
    def toInt: Int = v
  }
}

type Argon2HashString = String

// All parameters required to create the encoder.
case class Argon2EncoderParams(
    variant:     Argon2Variant,
    version:     Argon2Version,
    memory:      Argon2Memory,
    iterations:  Argon2Iterations,
    parallelism: Argon2Parallelism,
    hashSize:    Argon2HashSize,
    saltSize:    Argon2SaltSize
)

object Argon2EncoderParams {
  private[users] def apply(
      memory:      Argon2Memory,
      iterations:  Argon2Iterations,
      parallelism: Argon2Parallelism,
      version:     Argon2Version
  ): Argon2EncoderParams = {
    Argon2EncoderParams(
      memory = memory,
      iterations = iterations,
      parallelism = parallelism,
      version = version,
      variant = variant,
      hashSize = hashSize,
      saltSize = saltSize
    )
  }

  def apply(memory: Argon2Memory, iterations: Argon2Iterations, parallelism: Argon2Parallelism): Argon2EncoderParams = {
    Argon2EncoderParams(
      memory = memory,
      iterations = iterations,
      parallelism = parallelism,
      version = defaultVersion
    )
  }

  // Build parameters for Bouncy Castle
  def buildParams(param: Argon2EncoderParams, salt: Array[Byte]): Argon2Parameters = {
    new Argon2Parameters.Builder(variant.toInt)
      .withVersion(param.version.toInt)
      .withIterations(param.iterations.toInt)
      .withMemoryAsKB(param.memory.toInt)
      .withParallelism(param.parallelism.toInt)
      .withSalt(salt)
      .build()
  }
}

case class Argon2HashParams(
    encoderParams: Argon2EncoderParams,
    salt:          Chunk[Byte]
)

object Argon2HashParams {
  def computeHash(params: Argon2HashParams, password: Array[Byte]): Array[Byte] = {
    val buildParamsBC = Argon2EncoderParams.buildParams(params.encoderParams, params.salt.toArray)
    val generator     = new Argon2BytesGenerator
    generator.init(buildParamsBC)
    val hashValue: Array[Byte] = new Array(params.encoderParams.hashSize.toInt)
    generator.generateBytes(password, hashValue)
    hashValue
  }
}

case class Argon2Hash(
    params: Argon2HashParams,
    value:  Chunk[Byte]
)

object Argon2Hash {
  private val pattern = """\$argon2id\$v=(\d+)\$m=(\d+),t=(\d+),p=(\d+)\$([^$]+)\$([^$]+)""".r

  def toShadowString(hash: Argon2Hash): Argon2HashString = {
    val encoder     = Base64.getEncoder.withoutPadding
    val encodedSalt = encoder.encodeToString(hash.params.salt.toArray)
    val encodedHash = encoder.encodeToString(hash.value.toArray)
    s"$$argon2id$$v=${hash.params.encoderParams.version}$$m=${hash.params.encoderParams.memory},t=${hash.params.encoderParams.iterations},p=${hash.params.encoderParams.parallelism}$$$encodedSalt$$$encodedHash"
  }

  def parseShadowString(hashString: Argon2HashString): Either[String, Argon2Hash] = {
    val decoder = Base64.getDecoder

    hashString match {
      case pattern(version, memory, iterations, parallelism, encodedSalt, encodedHash) =>
        try {
          val salt = decoder.decode(encodedSalt)
          val hash = decoder.decode(encodedHash)
          Right(
            Argon2Hash(
              Argon2HashParams(
                Argon2EncoderParams(
                  memory = Argon2Memory(memory.toInt),
                  iterations = Argon2Iterations(iterations.toInt),
                  parallelism = Argon2Parallelism(parallelism.toInt),
                  version = Argon2Version(version.toInt)
                ),
                Chunk.fromArray(salt)
              ),
              Chunk.fromArray(hash)
            )
          )
        } catch {
          case e: Exception =>
            Left(s"Invalid password hash format $hashString: ${e.getMessage}")
        }
      case _                                                                           => Left(s"Could not parse argon2id hash string: $hashString")
    }
  }

  def generate(params: Argon2HashParams, password: Array[Byte]): Argon2HashString = {
    val hashValue = Argon2HashParams.computeHash(params, password)
    val hash      = Argon2Hash(params, Chunk.fromArray(hashValue))
    Argon2Hash.toShadowString(hash)
  }

  private def comparePassword(rawPassword: Array[Byte], storedHash: Argon2Hash): Boolean = {
    val presentedHash = Argon2HashParams.computeHash(storedHash.params, rawPassword)
    MessageDigest.isEqual(storedHash.value.toArray, presentedHash)
  }

  def checkPassword(rawPassword: Array[Byte], argon2String: Argon2HashString): Either[String, Boolean] = {
    for {
      storedHash <- parseShadowString(argon2String)
    } yield comparePassword(rawPassword, storedHash)
  }
}
