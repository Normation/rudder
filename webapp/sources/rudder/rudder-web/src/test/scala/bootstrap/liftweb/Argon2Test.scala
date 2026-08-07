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

package bootstrap.liftweb

import com.normation.rudder.users.*
import java.nio.charset.StandardCharsets
import org.junit.runner.RunWith
import zio.*
import zio.test.*
import zio.test.Assertion.*
import zio.test.junit.ZTestJUnitRunner

@RunWith(classOf[ZTestJUnitRunner])
class Argon2Test extends ZIOSpecDefault {
  def spec = {
    suiteAll("Local password hash algorithms") {
      val encoderParams = Argon2EncoderParams(Argon2Memory(19), Argon2Iterations(3), Argon2Parallelism(1))
      val shadowString  = "$argon2id$v=19$m=19,t=3,p=1$YXplcnR5dWlvcA$Ym9ibWF1cmFuZQ"
      val salt          = "azertyuiop".getBytes(StandardCharsets.UTF_8)
      val tag           = "bobmaurane".getBytes(StandardCharsets.UTF_8)
      val hash          = Argon2Hash(Argon2HashParams(encoderParams, salt = Chunk.fromArray(salt)), Chunk.fromArray(tag))
      // parsing takes the sizes from the string being parsed, so they are the ones of this salt and tag,
      // not the defaults carried by `encoderParams`
      val parsedHash    = Argon2Hash(
        Argon2HashParams(
          encoderParams.copy(hashSize = Argon2HashSize(tag.length), saltSize = Argon2SaltSize(salt.length)),
          salt = Chunk.fromArray(salt)
        ),
        Chunk.fromArray(tag)
      )

      test("stores as shadow string") {
        assert(Argon2Hash.toShadowString(hash))(equalTo(shadowString))
      }
      test("parses shadow string") {
        assert(Argon2Hash.parseShadowString(shadowString))(isRight(equalTo(parsedHash)))
      }
      // the tag length has to be read from the stored hash: assuming our own default would make any
      // hash created with another size impossible to verify
      test("verifies a hash whose tag is not the default 32 bytes") {
        val params = Argon2HashParams(
          encoderParams.copy(hashSize = Argon2HashSize(64)),
          salt = Chunk.fromArray("azertyuiop123456".getBytes(StandardCharsets.UTF_8))
        )
        val shadow = Argon2Hash.generate(params, "secret")
        assert(Argon2Hash.checkPassword("secret", shadow))(isRight(isTrue)) &&
        assert(Argon2Hash.checkPassword("wrong", shadow))(isRight(isFalse))
      }
      // `generate` owns the password encoding, so pin it: the bytes it hashes must be the UTF-8 ones,
      // not whatever the platform default charset would produce for a non-ASCII password.
      test("hashes the password as UTF-8") {
        val nonAscii   = "pâßwörd-日本語"
        val hashParams = Argon2HashParams(
          encoderParams,
          salt = Chunk.fromArray("azertyuiop123456".getBytes(StandardCharsets.UTF_8))
        )
        val expected   = Argon2Hash.toShadowString(
          Argon2Hash(
            hashParams,
            Chunk.fromArray(Argon2HashParams.computeHash(hashParams, nonAscii.getBytes(StandardCharsets.UTF_8)))
          )
        )
        assert(Argon2Hash.generate(hashParams, nonAscii))(equalTo(expected)) &&
        assert(Argon2Hash.checkPassword(nonAscii, expected))(isRight(isTrue))
      }
      // a password hash is a secret and these messages are logged, so they must not carry the value
      test("fails to parse malformed shadow string, without echoing it") {
        val invalid = "$argon2id$invalid=3v=19$m=19,t=3,p=1$YXplcnR5dWlvcA$Ym9ibWF1cmFuZQ"
        assert(Argon2Hash.parseShadowString(invalid))(isLeft(equalTo("Could not parse argon2id hash string")))
      }
      test("fails to parse invalid shadow string, without echoing it") {
        val invalid = "$argon2id$v=19$m=19,t=3,p=1$invalid&base64$Ym9ibWF1cmFuZQ"
        assert(Argon2Hash.parseShadowString(invalid))(
          isLeft(equalTo("Invalid password hash format: Illegal base64 character 26"))
        )
      }
      // these parse, but BouncyCastle refuses to compute with them: we must get a Left, not an exception
      test("fails on a well-formed shadow string with cost parameters BouncyCastle refuses") {
        val unusable = List(
          "$argon2id$v=19$m=19,t=3,p=0$YXplcnR5dWlvcA$Ym9ibWF1cmFuZQ", // no lane
          "$argon2id$v=19$m=19,t=0,p=1$YXplcnR5dWlvcA$Ym9ibWF1cmFuZQ"  // no iteration
        )
        assert(unusable.map(Argon2Hash.checkPassword("secret", _)))(forall(isLeft(anything)))
      }
      // BouncyCastle clamps a memory below its minimum instead of refusing it, so this one computes
      // normally. We accept whatever cost parameters the stored hash carries: see finding M2.
      test("computes a hash whose memory is below the BouncyCastle minimum") {
        val weak = "$argon2id$v=19$m=1,t=3,p=1$YXplcnR5dWlvcA$Ym9ibWF1cmFuZQ"
        assert(Argon2Hash.checkPassword("secret", weak))(isRight(isFalse))
      }
    }
  }
}
