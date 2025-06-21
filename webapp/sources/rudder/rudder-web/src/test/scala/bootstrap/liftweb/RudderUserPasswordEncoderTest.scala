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

import com.normation.rudder.users.Argon2IDHashString
import com.normation.rudder.users.PasswordEncoderType
import com.normation.rudder.users.RudderPasswordEncoder
import org.junit.runner.RunWith
import org.specs2.mutable.*
import org.specs2.runner.JUnitRunner

/*
 * Test hash algo for user password.
 */

@RunWith(classOf[JUnitRunner])
class RudderUserPasswordEncoderTest extends Specification {

  "decode special values" should {
    "be ok for sha1" in {
      PasswordEncoderType.withNameInsensitiveOption("sha") must beSome(beEqualTo(PasswordEncoderType.SHA1))
      PasswordEncoderType.withNameInsensitiveOption("sha1") must beSome(beEqualTo(PasswordEncoderType.SHA1))
      PasswordEncoderType.withNameInsensitiveOption("sha-1") must beSome(beEqualTo(PasswordEncoderType.SHA1))
    }

    "be ok for sha256" in {
      PasswordEncoderType.withNameInsensitiveOption("sha256") must beSome(beEqualTo(PasswordEncoderType.SHA256))
      PasswordEncoderType.withNameInsensitiveOption("sha-256") must beSome(beEqualTo(PasswordEncoderType.SHA256))
    }

    "be ok for sha512" in {
      PasswordEncoderType.withNameInsensitiveOption("sha512") must beSome(beEqualTo(PasswordEncoderType.SHA512))
      PasswordEncoderType.withNameInsensitiveOption("sha-512") must beSome(beEqualTo(PasswordEncoderType.SHA512))
    }
  }

  "passwords" should {
    val pass1          = "admin"
    val wrong_pass1    = "not_good"
    val empty_pass1    = ""
    val pass1_md5      = "21232f297a57a5a743894a0e4a801fc3"
    val pass1_sha1     = "d033e22ae348aeb5660fc2140aec35850c4da997"
    val pass1_sha256   = "8c6976e5b5410415bde908bd4dee15dfb167a9c873fc4bb8a81f6f2ab448a918"
    val pass1_sha512   =
      "c7ad44cbad762a5da0a452f9e854fdc1e0e7a52a38015f23f3eab1d80b931dd472634dfac71cd34ebc35d16ab7fb8a90c81f975113d6c7538dc69dd8de9077ec"
    val pass1_bcrypt_a = "$2a$12$mgAVHJ2/312Q.hdWT0EzjOZHrGicXV/2K.1CLsnM3gOYqi5twcwtW"
    val pass1_bcrypt_y = "$2y$10$QA4RucUAlhOofuPMAnonk.Mvnq4GPSHaq757Hwj7C/pLb9cmZBHdW"
    val pass1_argon2   = "$argon2id$v=19$m=19000,t=2,p=1$VU9VRmpORnlJZFlxZUFVYg$WyLWXe4yYQbFwUQbaGkBzw"

    "be correctly checked with md5" in {
      RudderPasswordEncoder.MD5.matches(pass1, pass1_md5) must beTrue
    }
    "be correctly checked with sha1" in {
      RudderPasswordEncoder.SHA1.matches(pass1, pass1_sha1) must beTrue
    }
    "be correctly checked with sha256" in {
      RudderPasswordEncoder.SHA256.matches(pass1, pass1_sha256) must beTrue
    }
    "be correctly checked with sha512" in {
      RudderPasswordEncoder.SHA512.matches(pass1, pass1_sha512) must beTrue
    }
    "be correctly encoded with bcrypt" in {
      val encoder = RudderPasswordEncoder.BCRYPT(12)
      val hash    = encoder.encode(pass1)
      encoder.matches(pass1, hash) must beTrue
    }
    "be correctly checked with bcrypt a" in {
      RudderPasswordEncoder.BCRYPT(12).matches(pass1, pass1_bcrypt_a) must beTrue
    }
    "be correctly checked with bcrypt y" in {
      RudderPasswordEncoder.BCRYPT(12).matches(pass1, pass1_bcrypt_y) must beTrue
    }
    "fail when password is incorrect with bcrypt y" in {
      RudderPasswordEncoder.BCRYPT(12).matches(wrong_pass1, pass1_bcrypt_y) must beFalse
    }
    "fail when password is empty with bcrypt y" in {
      RudderPasswordEncoder.BCRYPT(12).matches(empty_pass1, pass1_bcrypt_y) must beFalse
    }
    "be correctly checked when cost is different with bcrypt" in {
      RudderPasswordEncoder.BCRYPT(10).matches(pass1, pass1_bcrypt_y) must beTrue
    }
    "be correctly encoded with argon2id" in {
      val encoder = RudderPasswordEncoder.ARGON2ID(19000, 1, 2)
      val hash    = encoder.encode(pass1)
      encoder.matches(pass1, hash) must beTrue
    }
    "succeed when password is valid with argon2id" in {
      RudderPasswordEncoder.ARGON2ID(19000, 1, 2).matches(pass1, pass1_argon2) must beTrue
    }
    "fail when password is incorrect with argon2id" in {
      RudderPasswordEncoder.ARGON2ID(19000, 1, 2).matches(wrong_pass1, pass1_argon2) must beFalse
    }
    "fail when password is empty with argon2id" in {
      RudderPasswordEncoder.ARGON2ID(19000, 1, 2).matches(empty_pass1, pass1_argon2) must beFalse
    }
    "succeed when password is valid and encoder parameters are different with argon2id" in {
      RudderPasswordEncoder.ARGON2ID(18000, 2, 1).matches(pass1, pass1_argon2) must beTrue
    }
  }

  "hash algo recognition" should {
    val legacy = RudderPasswordEncoder.SecurityLevel.Legacy
    val modern = RudderPasswordEncoder.SecurityLevel.Modern

    val pass1_md5         = "21232f297a57a5a743894a0e4a801fc3"
    val pass1_sha1        = "d033e22ae348aeb5660fc2140aec35850c4da997"
    val pass1_sha256      = "8c6976e5b5410415bde908bd4dee15dfb167a9c873fc4bb8a81f6f2ab448a918"
    val pass1_sha512      =
      "c7ad44cbad762a5da0a452f9e854fdc1e0e7a52a38015f23f3eab1d80b931dd472634dfac71cd34ebc35d16ab7fb8a90c81f975113d6c7538dc69dd8de9077ec"
    val pass1_bcrypt_a    = "$2a$12$mgAVHJ2/312Q.hdWT0EzjOZHrGicXV/2K.1CLsnM3gOYqi5twcwtW"
    val pass1_bcrypt_y    = "$2y$10$QA4RucUAlhOofuPMAnonk.Mvnq4GPSHaq757Hwj7C/pLb9cmZBHdW"
    val pass1_argon2id    = "$argon2id$v=19$m=16,t=2,p=1$Y3NkY2RzY2RzY3M$AAAkKtLERhIhmS714tnQdw"
    val pass1_invalid_hex = "87d033e22ae348aeb5660fc2140aec35850c4da997"
    val pass1_invalid_z   = "z033e22ae348aeb5660fc2140aec35850c4da997"

    "be ok for md5" in {
      RudderPasswordEncoder.getFromEncoded(pass1_md5, legacy) must beRight(beEqualTo(PasswordEncoderType.MD5))
    }
    "be ok for sha1" in {
      RudderPasswordEncoder.getFromEncoded(pass1_sha1, legacy) must beRight(beEqualTo(PasswordEncoderType.SHA1))
    }
    "be ok for sha256" in {
      RudderPasswordEncoder.getFromEncoded(pass1_sha256, legacy) must beRight(beEqualTo(PasswordEncoderType.SHA256))
    }
    "be ok for sha512" in {
      RudderPasswordEncoder.getFromEncoded(pass1_sha512, legacy) must beRight(beEqualTo(PasswordEncoderType.SHA512))
    }
    "be ok for bcrypt a" in {
      RudderPasswordEncoder.getFromEncoded(pass1_bcrypt_a, legacy) must beRight(beEqualTo(PasswordEncoderType.BCRYPT))
    }
    "be ok for bcrypt y" in {
      RudderPasswordEncoder.getFromEncoded(pass1_bcrypt_y, legacy) must beRight(beEqualTo(PasswordEncoderType.BCRYPT))
    }
    "be ok for argon2id" in {
      RudderPasswordEncoder.getFromEncoded(pass1_argon2id, legacy) must beRight(beEqualTo(PasswordEncoderType.ARGON2ID))
    }
    "reject invalid hex hash" in {
      RudderPasswordEncoder.getFromEncoded(pass1_invalid_hex, legacy) must beLeft(
        "Could not recognize a known hash format from hexadecimal encoded string of length 42"
      )
    }
    "reject invalid char in hash" in {
      RudderPasswordEncoder.getFromEncoded(pass1_invalid_z, legacy) must beLeft(
        "Could not recognize a known hash format from encoded password"
      )
    }
    "reject unsafe md5 hash in modern mode" in {
      RudderPasswordEncoder.getFromEncoded(pass1_md5, modern) must beLeft(
        "Could not recognize a known hash format from encoded password"
      )
    }
    "allow safe bcrypt hash in modern mode" in {
      RudderPasswordEncoder.getFromEncoded(pass1_bcrypt_y, modern) must beRight(beEqualTo(PasswordEncoderType.BCRYPT))
    }
    "allow safe argon2id hash in modern mode" in {
      RudderPasswordEncoder.getFromEncoded(pass1_argon2id, modern) must beRight(beEqualTo(PasswordEncoderType.ARGON2ID))
    }
  }

  "Argon2id hash string" should {
    "be correctly written" in {
      Argon2IDHashString(
        version = 3,
        memory = 64,
        iterations = 1,
        parallelism = 2,
        salt = "azertyuiop".getBytes,
        hash = "bobmaurane".getBytes
      ).toShadowString must beEqualTo("$argon2id$v=3$m=64,t=1,p=2$YXplcnR5dWlvcA$Ym9ibWF1cmFuZQ")
    }

    "be correctly read" in {
      val hash = Argon2IDHashString.parseShadowString("$argon2id$v=3$m=64,t=1,p=2$YXplcnR5dWlvcA$Ym9ibWF1cmFuZQ")

      hash must beRight(
        beEqualTo(
          Argon2IDHashString(
            version = 3,
            memory = 64,
            iterations = 1,
            parallelism = 2,
            salt = "azertyuiop".getBytes,
            hash = "bobmaurane".getBytes
          )
        )
      )
    }

    "fail on invalid value" in {
      val hash = Argon2IDHashString.parseShadowString("$argon2id$v=3$m=64,t=1,p=2$YXplcnR5dWlvcA")
      hash must beLeft
    }
  }
}
