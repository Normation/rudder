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

  // value from linux command lines: echo -n 'pass' | md5sum (or other hashes)

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

  "hash algo for 'admin' password" should {
    val pass1          = "admin"
    val pass1_md5      = "21232f297a57a5a743894a0e4a801fc3"
    val pass1_sha1     = "d033e22ae348aeb5660fc2140aec35850c4da997"
    val pass1_sha256   = "8c6976e5b5410415bde908bd4dee15dfb167a9c873fc4bb8a81f6f2ab448a918"
    val pass1_sha512   =
      "c7ad44cbad762a5da0a452f9e854fdc1e0e7a52a38015f23f3eab1d80b931dd472634dfac71cd34ebc35d16ab7fb8a90c81f975113d6c7538dc69dd8de9077ec"
    val pass1_bcrypt_a = "$2a$12$mgAVHJ2/312Q.hdWT0EzjOZHrGicXV/2K.1CLsnM3gOYqi5twcwtW"
    val pass1_bcrypt_y = "$2y$10$QA4RucUAlhOofuPMAnonk.Mvnq4GPSHaq757Hwj7C/pLb9cmZBHdW"

    "be ok for md5" in {
      RudderPasswordEncoder.MD5.matches(pass1, pass1_md5) must beTrue
    }
    "be ok for sha1" in {
      RudderPasswordEncoder.SHA1.matches(pass1, pass1_sha1) must beTrue
    }
    "be ok for sha256" in {
      RudderPasswordEncoder.SHA256.matches(pass1, pass1_sha256) must beTrue
    }
    "be ok for sha512" in {
      RudderPasswordEncoder.SHA512.matches(pass1, pass1_sha512) must beTrue
    }
    "be ok for bcrypt a" in {
      RudderPasswordEncoder.BCRYPT(RudderConfig.RUDDER_BCRYPT_COST).matches(pass1, pass1_bcrypt_a) must beTrue
    }
    "be ok for bcrypt y" in {
      RudderPasswordEncoder.BCRYPT(RudderConfig.RUDDER_BCRYPT_COST).matches(pass1, pass1_bcrypt_y) must beTrue
    }

  }

  "hash algo for ';axG42!' password" should {
    val pass1        = ";axG42!"
    val pass1_md5    = "897b4148edc1184686f95afd18ab125f"
    val pass1_sha1   = "c1193f9a893e816ef90a84a48a4085d2d9a39664"
    val pass1_sha256 = "ccc3e8f4851e4a3211c083074077ce4484db8dd806fac8360fae438298e07ee0"
    val pass1_sha512 =
      "ae5e7cdad947b6d2325d336868d86feb5abf3c66a111c124b0d66366db3db8a757b1d96f1c03c18cffd14fa3cf6a204701615c49b9f0961e13b363b46d88bdb2"

    "be ok for md5" in {
      RudderPasswordEncoder.MD5.matches(pass1, pass1_md5) must beTrue
    }
    "be ok for sha1" in {
      RudderPasswordEncoder.SHA1.matches(pass1, pass1_sha1) must beTrue
    }
    "be ok for sha256" in {
      RudderPasswordEncoder.SHA256.matches(pass1, pass1_sha256) must beTrue
    }
    "be ok for sha512" in {
      RudderPasswordEncoder.SHA512.matches(pass1, pass1_sha512) must beTrue
    }

  }

}
