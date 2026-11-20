/*
 * *************************************************************************************
 * Copyright 2026 Normation SAS
 * *************************************************************************************
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
 * along with Rudder.  If not, see <http://www.gnu.org/licenses/>
 *
 * *************************************************************************************
 */
package com.normation.rudder.users

import com.bastiaanjansen.otp.TOTPGenerator
import com.normation.rudder.users.OtpJavaTotpService.OtpJavaTotpVerificator
import com.normation.rudder.users.TotpError.*
import java.time.Instant
import java.time.ZoneOffset
import org.junit.runner.RunWith
import zio.*
import zio.test.*
import zio.test.junit.ZTestJUnitRunner

@RunWith(classOf[ZTestJUnitRunner])
class TotpVerificatorTest extends ZIOSpecDefault {

  type UsedCodes = Map[UserId, Chunk[(String, Instant)]]

  private val period      = 30.seconds
  private val delayWindow = 1

  private val secret = TotpSecret("JBSWY3DPEHPK3PXP")
  private val alice  = UserId("alice")

  private val t0 = Instant.parse("2026-01-01T00:00:00Z")

  private val codeErrorMsg = "TOTP code is invalid or may be too old"
  private val replayMsg    = "TOTP code is invalid because it was already used in the last 1m30s"

  private def codeAt(at: Instant): String = {
    TOTPGenerator
      .Builder(secret.exposeSecret())
      .withClock(java.time.Clock.fixed(at, ZoneOffset.UTC))
      .withPeriod(period)
      .build
      .now()
  }

  private def makeVerificator: UIO[(TotpVerificator, Ref[UsedCodes])] = {
    Ref
      .make(Map.empty: UsedCodes)
      .map(usedCodes => (new OtpJavaTotpVerificator(period, delayWindow)(usedCodes) {}, usedCodes))
  }

  override def spec = suite("TotpVerificator replay resistance")(
    test("a code is accepted once, then rejected") {
      val code = codeAt(t0)
      for {
        (verificator, _) <- makeVerificator
        first            <- verificator.verify(secret, alice, t0, code).either
        replay           <- verificator.verify(secret, alice, t0.plusSeconds(1), code).either
      } yield assertTrue(
        first.isRight,
        replay == Left(ReplayError(replayMsg, t0, alice))
      )
    },
    test("an older code still in its window is rejected after a newer code was accepted") {
      val older = codeAt(t0)
      val newer = codeAt(t0.plusSeconds(40))
      for {
        (verificator, _) <- makeVerificator
        _                <- verificator.verify(secret, alice, t0, older)
        _                <- verificator.verify(secret, alice, t0.plusSeconds(40), newer)
        replay           <- verificator.verify(secret, alice, t0.plusSeconds(50), older).either
      } yield assertTrue(replay == Left(ReplayError(replayMsg, t0, alice)))
    },
    test("a code accepted at the start of its window is still rejected as a replay at the last instant of its window") {
      val code        = codeAt(t0)
      val windowStart = t0.minus(period.multipliedBy(delayWindow))
      val windowEnd   = t0.plus(period.multipliedBy(delayWindow + 1)).minusMillis(1)
      for {
        (verificator, _) <- makeVerificator
        first            <- verificator.verify(secret, alice, windowStart, code).either
        replay           <- verificator.verify(secret, alice, windowEnd, code).either
        // worst case at limits from https://issues.rudder.io/issues/30145: the code must not be accepted right after its window
        // otp-java has an exclusive window end
        afterWindow      <- verificator.verify(secret, alice, windowEnd.plusMillis(1), code).either
      } yield assertTrue(
        first.isRight,
        replay == Left(ReplayError(replayMsg, windowStart, alice)),
        afterWindow == Left(CodeError(codeErrorMsg, windowEnd.plusMillis(1), alice))
      )
    },
    test("a wrong code is rejected and not recorded") {
      val validCodes = Set(t0.minus(period), t0, t0.plus(period)).map(codeAt)
      check(Gen.stringN(6)(Gen.numericChar).filterNot(validCodes.contains)) { wrong =>
        for {
          (verificator, usedCodes) <- makeVerificator
          result                   <- verificator.verify(secret, alice, t0, wrong).either
          used                     <- usedCodes.get
        } yield assertTrue(
          result == Left(CodeError(codeErrorMsg, t0, alice)),
          used.isEmpty
        )
      }
    },
    test("expired used codes are pruned") {
      val expired = codeAt(t0)
      val later   = t0.plusSeconds(200)
      val current = codeAt(later)
      for {
        (verificator, usedCodes) <- makeVerificator
        _                        <- verificator.verify(secret, alice, t0, expired)
        _                        <- verificator.verify(secret, alice, later, current)
        used                     <- usedCodes.get
      } yield assertTrue(used.get(alice).map(_.map(_._1)) == Some(Chunk(current)))
    }
  )
}
