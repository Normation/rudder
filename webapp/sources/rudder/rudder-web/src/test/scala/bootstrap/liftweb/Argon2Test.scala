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
      val hash          = Argon2Hash(
        Argon2HashParams(encoderParams, salt = Chunk.fromArray("azertyuiop".getBytes)),
        Chunk.fromArray("bobmaurane".getBytes)
      )

      test("stores as shadow string") {
        assert(Argon2Hash.toShadowString(hash))(equalTo(shadowString))
      }
      test("parses shadow string") {
        val parsedHash = Argon2Hash.parseShadowString(shadowString)
        assert(parsedHash)(isRight(equalTo(hash)))
      }
      test("fails to parse malformed shadow string") {
        val invalid    = "$argon2id$invalid=3v=19$m=19,t=3,p=1$YXplcnR5dWlvcA$Ym9ibWF1cmFuZQ"
        val parsedHash = Argon2Hash.parseShadowString(invalid)
        assert(parsedHash)(isLeft(equalTo(s"Could not parse argon2id hash string: $invalid")))
      }
      test("fails to parse invalid shadow string") {
        val invalid    = "$argon2id$v=19$m=19,t=3,p=1$invalid&base64$Ym9ibWF1cmFuZQ"
        val parsedHash = Argon2Hash.parseShadowString(invalid)
        assert(parsedHash)(isLeft(equalTo(s"Invalid password hash format $invalid: Illegal base64 character 26")))
      }
    }
  }
}
