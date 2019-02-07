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

import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner

import com.normation.cfclerk.domain.AixPasswordHashAlgo.ShaSpec


@RunWith(classOf[JUnitRunner])
class HashAlgoTest extends Specification {

  /*
   * As we are relying on some JCE hashing algo, we may be in case where
   * the algo is not present. We must account of that to not have spurious
   * falling tests.
   */
  private[this] def isAvailable(sha: ShaSpec) = {
    AixPasswordHashAlgo.getSecretKeFactory(sha).isDefined
  }

  val sha1Available   = isAvailable(ShaSpec.SHA1  )
  val sha256Available = isAvailable(ShaSpec.SHA256)
  val sha512Available = isAvailable(ShaSpec.SHA512)

  "Aix hashing" should {

    "calculate the expected smd5 hash" in {

      val pwd = "secret"
      val salt = "tyiOfoE4" // max 8 chars in md5_crypt

      AixPasswordHashAlgo.smd5(pwd, Some(salt)) must be_==("{smd5}tyiOfoE4$r5HleyKHVdL3dg9ouzcZ80")
    }

    "calculate the expected ssha1 hash" in {

      val pwd = "secret"
      val salt = "tyiOfoE4WXucUfh/"
      val expected = if(sha1Available) {
        "{ssha1}12$tyiOfoE4WXucUfh/$1olYn48enIIKGOOs0ve/GE.k.sF"
      } else {
        "{smd5}tyiOfoE4$r5HleyKHVdL3dg9ouzcZ80"
      }

      AixPasswordHashAlgo.ssha1(pwd, Some(salt), 12) must be_==(expected)
    }

    "calculate the expected ssha256 hash" in {

      val pwd = "secret"
      val salt = "tyiOfoE4WXucUfh/"
      val expected = if(sha256Available) {
        "{ssha256}12$tyiOfoE4WXucUfh/$YDkcqbY5oKk4lwQ4pVKPy8o4MqcfVpp1ZxxvSfP0.wS"
      } else if(sha1Available) {
        "{ssha1}12$tyiOfoE4WXucUfh/$1olYn48enIIKGOOs0ve/GE.k.sF"
      } else {
        "{smd5}tyiOfoE4$r5HleyKHVdL3dg9ouzcZ80"
      }

      AixPasswordHashAlgo.ssha256(pwd, Some(salt), 12) must be_==(expected)
    }

    "calculate the expected ssha512 hash" in {

      val pwd = "secret"
      val salt = "tyiOfoE4WXucUfh/"
      val expected = if(sha512Available) {
        "{ssha512}10$tyiOfoE4WXucUfh/$qaLbOhKx3fwIu93Hkh4Z89Vr.otLYEhRGN3b3SAZFD3mtxhqWZmY2iJKf0KB/5fuwlERv14pIN9h4XRAZtWH.."
      } else if(sha1Available) {
        "{ssha1}10$tyiOfoE4WXucUfh/$WoHzlMSRBEZW4wygWnlWMxDn..3"
      } else {
        "{smd5}tyiOfoE4$r5HleyKHVdL3dg9ouzcZ80"
      }


      AixPasswordHashAlgo.ssha512(pwd, Some(salt), 10) must be_==(expected)
    }

  }

}

