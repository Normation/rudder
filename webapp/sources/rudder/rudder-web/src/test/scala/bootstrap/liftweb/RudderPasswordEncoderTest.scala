/*
 *************************************************************************************
 * Copyright 2018 Normation SAS
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
import com.normation.rudder.users.RudderPasswordEncoder.SecurityLevel
import org.junit.*
import org.junit.runner.RunWith
import zio.*
import zio.test.*
import zio.test.Assertion.*
import zio.test.junit.ZTestJUnitRunner

/*
 * Test hash algo for local user authentication.
 */

@RunWith(classOf[ZTestJUnitRunner])
class RudderPasswordEncoderTest extends ZIOSpecDefault {

  // Check basic properties of a password hash
  def testEncoder(dispatcher: PasswordEncoderDispatcher, encoderType: PasswordEncoderType) = {
    val encoder       = dispatcher.dispatch(encoderType)
    val securityLevel = SecurityLevel.fromPasswordEncoderType(encoderType)
    val name          = encoderType.name

    suite(s"$name test")(
      test(s"$name hash matches password") {
        check(Gen.string)(pass => {
          val hash1 = encoder.encode(pass)
          val hash2 = encoder.encode(pass)
          assert(encoder.matches(pass, hash1))(isTrue) &&
          assert(encoder.matches(pass, hash2))(isTrue) &&
          (if (securityLevel == SecurityLevel.Legacy) {
             // hashes include a random part
             assert(hash1)(equalTo(hash2))
           } else {
             assert(hash1)(not(equalTo("hash2")))
           })
        })
      },
      test(s"$name hash does not accept wrong password") {
        val rightPassword = "ue4Eep1oth3mie0aev7fi4oop.aef1eNa4AiDoh2"
        val hash          = encoder.encode(rightPassword)
        check(Gen.string)(randomPassword => {
          assert(encoder.matches(randomPassword, hash))(isFalse)
        })
      }
    )
  }

  def spec = {
    // Here cost factors are voluntarily low.
    val dispatcher = PasswordEncoderDispatcher(4, Argon2EncoderParams(Argon2Memory(1), Argon2Iterations(1), Argon2Parallelism(1)))
    suite("Password encoder")(
      suite("matches encoded values")(
        for {
          encoderType <- PasswordEncoderType.values
        } yield testEncoder(dispatcher, encoderType)
      ),
      suiteAll("BCRYPT specific") {
        val encoder       = RudderPasswordEncoder.bcryptEncoder(10)
        val pass          = "admin"
        val wrong_pass    = "not_good"
        val pass_bcrypt_a = "$2a$12$mgAVHJ2/312Q.hdWT0EzjOZHrGicXV/2K.1CLsnM3gOYqi5twcwtW"
        val pass_bcrypt_y = "$2y$10$QA4RucUAlhOofuPMAnonk.Mvnq4GPSHaq757Hwj7C/pLb9cmZBHdW"

        test("matches known a version value") {
          assert(encoder.matches(pass, pass_bcrypt_a))(isTrue)
        }
        test("matches known y version value") {
          assert(encoder.matches(pass, pass_bcrypt_y))(isTrue)
        }
        test("fails on invalid password with a type") {
          assert(encoder.matches(wrong_pass, pass_bcrypt_a))(isFalse)
        }
        test("fails on invalid password with y type") {
          assert(encoder.matches(wrong_pass, pass_bcrypt_y))(isFalse)
        }
        test("fails on empty password with y type") {
          assert(encoder.matches("", pass_bcrypt_y))(isFalse)
        }
        test("matches a known password with different encoder settings") {
          val altEncoder = RudderPasswordEncoder.bcryptEncoder(8)
          assert(altEncoder.matches(pass, pass_bcrypt_a))(isTrue)
        }
      },
      suiteAll("ARGON2ID specific") {
        val encoderParams = Argon2EncoderParams(Argon2Memory(19), Argon2Iterations(3), Argon2Parallelism(1))
        val encoder       = RudderPasswordEncoder.argon2Encoder(encoderParams)

        val pass        = "admin"
        val wrong_pass  = "not_good"
        val pass_argon2 = "$argon2id$v=19$m=16,t=3,p=1$EG2F4LP54934rOuUabw5gQ$6TYloo60wMGrHzfB75UULeryOngp9GyBk54GGFM1uZw"

        test("matches known value") {
          assert(encoder.matches(pass, pass_argon2))(isTrue)
        }
        test("fails on invalid password") {
          assert(encoder.matches(wrong_pass, pass_argon2))(isFalse)
        }
        test("fails on empty password") {
          assert(encoder.matches("", pass_argon2))(isFalse)
        }
        test("matches a known password with different encoder settings") {
          val altEncoderParams = Argon2EncoderParams(Argon2Memory(18), Argon2Iterations(1), Argon2Parallelism(2))
          val altEncoder       = RudderPasswordEncoder.argon2Encoder(altEncoderParams)
          assert(altEncoder.matches(pass, pass_argon2))(isTrue)
        }
      },
      suiteAll("DIGEST specific") {
        val pass        = "admin"
        val pass_md5    = "21232f297a57a5a743894a0e4a801fc3"
        val pass_sha1   = "d033e22ae348aeb5660fc2140aec35850c4da997"
        val pass_sha256 = "8c6976e5b5410415bde908bd4dee15dfb167a9c873fc4bb8a81f6f2ab448a918"
        val pass_sha512 =
          "c7ad44cbad762a5da0a452f9e854fdc1e0e7a52a38015f23f3eab1d80b931dd472634dfac71cd34ebc35d16ab7fb8a90c81f975113d6c7538dc69dd8de9077ec"

        test("matches MD5 value") {
          assert(RudderPasswordEncoder.MD5.matches(pass, pass_md5))(isTrue)
        }
        test("matches SHA1 value") {
          assert(RudderPasswordEncoder.SHA1.matches(pass, pass_sha1))(isTrue)
        }
        test("matches SHA256 value") {
          assert(RudderPasswordEncoder.SHA256.matches(pass, pass_sha256))(isTrue)
        }
        test("matches SHA512 value") {
          assert(RudderPasswordEncoder.SHA512.matches(pass, pass_sha512))(isTrue)
        }
      }
    )
  }
}
