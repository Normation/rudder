/*
 *************************************************************************************
 * Copyright 2024 Normation SAS
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

package com.normation.rudder.api

import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

final class TestTokenGenerator extends TokenGenerator {
  override def newToken(size: Int): String = "a" * size
}

@RunWith(classOf[JUnitRunner])
class TestApiToken extends Specification {

  "API tokens" should {
    "be generated from generator" in {
      val token = ApiTokenSecret.generate(new TestTokenGenerator)
      token.exposeSecret() must beEqualTo("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
    }
    "be generated with suffix" in {
      val token = ApiTokenSecret.generate(new TestTokenGenerator, "end")
      token.exposeSecret() must beEqualTo("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-end")
    }
    "be hidden in strings" in {
      val token = ApiTokenSecret("UBeJJbm1tPDwILWVHXqBdgmIm3s4xjtY")
      token.toString() must beEqualTo("[REDACTED ApiTokenSecret]")
    }
    "be partly hidden in controlled exposure" in {
      val token          = ApiTokenSecret("UBeJJbm1tPDwILWVHXqBdgmIm3s4xjtY")
      token.exposeSecretBeginning must beEqualTo("UBeJ[SHORTENED ApiTokenSecret]")
      val shortToken     = ApiTokenSecret("test")
      shortToken.exposeSecretBeginning must beEqualTo("test[SHORTENED ApiTokenSecret]")
      val veryShortToken = ApiTokenSecret("t")
      veryShortToken.exposeSecretBeginning must beEqualTo("t[SHORTENED ApiTokenSecret]")
    }
    "be hashed" in {
      val token = ApiTokenSecret("UBeJJbm1tPDwILWVHXqBdgmIm3s4xjtY")
      token.toHash() must beEqualTo(
        ApiTokenHash.fromHashValue(
          "v2:100caab9f3996edb04119ad4b2647b45150b10f75007b86bd82cdd0b7a9b009e2d5327115b3153bc4dc31bbbc775c6257f63f64a31f3c2d3924f11e8d24855bc"
        )
      )
    }
  }

  "Hashed API tokens" should {
    "be hidden in strings" in {
      val token = ApiTokenSecret.generate(new TestTokenGenerator)
      val hash  = token.toHash()
      hash.toString() must beEqualTo("[REDACTED ApiTokenHash]")
    }

    "be compared correctly when identical" in {
      val hash   = "v2:UBeJJbm1tPDwILWVHXqBdgmIm3s4xjtY"
      val token1 = ApiTokenHash.fromHashValue(hash)
      val token2 = ApiTokenHash.fromHashValue(hash)
      token1.equalsToken(token1) must beTrue
      token1.equalsToken(token2) must beTrue
    }

    "be compared correctly when different" in {
      val hash   = "v2:UBeJJbm1tPDwILWVHXqBdgmIm3s4xjtY"
      val token1 = ApiTokenHash.fromHashValue(hash)
      val token2 = ApiTokenHash.fromHashValue(hash + "z")
      token1.equalsToken(token2) must beFalse
    }

    "be compared correctly when different and empty" in {
      val hash   = "v2:UBeJJbm1tPDwILWVHXqBdgmIm3s4xjtY"
      val token1 = ApiTokenHash.fromHashValue(hash)
      val token2 = ApiTokenHash.fromHashValue("")
      token1.equalsToken(token2) must beFalse
    }

    "have correct version 1" in {
      val token = ApiTokenHash.fromHashValue("UBeJJbm1tPDwILWVHXqBdgmIm3s4xjtY")
      token.version() must beEqualTo(1)
    }

    "have correct version 2" in {
      val token = ApiTokenHash.fromHashValue("v2:UBeJJbm1tPDwILWVHXqBdgmIm3s4xjtY")
      token.version() must beEqualTo(2)
    }

    "have correct version 2 with empty hash" in {
      val token = ApiTokenHash.fromHashValue("v2:")
      token.version() must beEqualTo(2)
    }

    "not recognize version 3" in {
      val token = ApiTokenHash.fromHashValue("v3:UBeJJbm1tPDwILWVHXqBdgmIm3s4xjtY")
      token.version() must beEqualTo(1)
    }
  }
}
