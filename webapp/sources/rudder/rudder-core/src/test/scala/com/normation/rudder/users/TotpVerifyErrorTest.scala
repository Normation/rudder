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

import com.normation.NamedZioLogger
import com.normation.errors.*
import com.normation.rudder.users.TotpError.*
import com.normation.rudder.users.TotpError.EnrollmentError.*
import com.normation.rudder.users.TotpError.VerifyError.*
import com.normation.rudder.users.TotpVerifyError.*
import java.time.Instant
import org.junit.runner.RunWith
import org.slf4j.event.Level
import zio.*
import zio.syntax.*
import zio.test.*
import zio.test.junit.ZTestJUnitRunner

@RunWith(classOf[ZTestJUnitRunner])
class TotpVerifyErrorTest extends ZIOSpecDefault {

  private class LoggerMock(logs: Ref[List[Level]]) extends NamedZioLogger {
    def loggerName: String = "test"

    override def trace(msg: => String): UIO[Unit] = logs.update(_ :+ Level.TRACE)
    override def debug(msg: => String): UIO[Unit] = logs.update(_ :+ Level.DEBUG)
    override def info(msg:  => String): UIO[Unit] = logs.update(_ :+ Level.INFO)
    override def warn(msg:  => String): UIO[Unit] = logs.update(_ :+ Level.WARN)
    override def error(msg: => String): UIO[Unit] = logs.update(_ :+ Level.ERROR)
  }

  private val alice = UserId("alice")
  private val t0    = Instant.parse("2026-01-01T00:00:00Z")

  private val codeError        = CodeError("invalid code", t0, alice)
  private val replayError      = ReplayError("replayed code", t0, alice)
  private val noEnrolledSecret = NoEnrolledSecret("no generated secret", alice)
  private val database         = Database(Unexpected("database is down"), alice)
  private val unknownUser      = UnknownUser(None, alice)
  private val unknownUserDb    = UnknownUser(Some(Unexpected("database is down")), alice)

  /**
   * Returns the verification result, whether the verification of a known user was run, and the logged levels
   */
  private def handle(
      enrollment:   ZIO[Any, TotpError | EnrollmentError, Unit],
      verification: ZIO[Any, TotpError | VerifyError, Unit]
  ): UIO[(PureResult[Unit], Boolean, List[Level])] = {
    for {
      logs            <- Ref.make(List.empty[Level])
      verificationRan <- Ref.make(false)
      result          <- {
        given NamedZioLogger = LoggerMock(logs)
        enrollment.handleVerify(verificationRan.set(true) *> verification)
      }
      ran             <- verificationRan.get
      logged          <- logs.get
    } yield (result, ran, logged)
  }

  override def spec = suite("TOTP verification error handling")(
    test("a database error upon enrollment fails fast without verifying a known user") {
      for {
        (result, ran, logged) <- handle(database.fail, ZIO.unit)
      } yield assertTrue(
        result.left.exists(_.isInstanceOf[Unexpected]),
        !ran,
        logged == List(Level.ERROR)
      )
    },
    test("a successful enrollment does not verify a known user") {
      for {
        (result, ran, logged) <- handle(ZIO.unit, ZIO.unit)
      } yield assertTrue(result.isRight, !ran, logged.isEmpty)
    },
    test("a successful verification of a known user succeeds when no secret was generated") {
      for {
        (result, ran, logged) <- handle(noEnrolledSecret.fail, ZIO.unit)
      } yield assertTrue(result.isRight, ran, logged.isEmpty)
    },
    test("a replay upon enrollment is a security warning") {
      for {
        (result, _, logged) <- handle(replayError.fail, unknownUser.fail)
      } yield assertTrue(
        result.left.exists(_.isInstanceOf[Inconsistency]),
        logged == List(Level.WARN)
      )
    },
    test("a replay upon verification has precedence over an invalid code upon enrollment") {
      for {
        (result, _, logged) <- handle(codeError.fail, replayError.fail)
      } yield assertTrue(
        result.left.exists(_.isInstanceOf[Inconsistency]),
        logged == List(Level.WARN)
      )
    },
    test("an invalid code upon enrollment has precedence over an unknown user") {
      for {
        (result, _, logged) <- handle(codeError.fail, unknownUser.fail)
      } yield assertTrue(
        result.left.exists(_.isInstanceOf[Inconsistency]),
        logged == List(Level.DEBUG)
      )
    },
    test("an invalid code upon verification has precedence over no generated secret") {
      for {
        (result, _, logged) <- handle(noEnrolledSecret.fail, codeError.fail)
      } yield assertTrue(
        result.left.exists(_.isInstanceOf[Inconsistency]),
        logged == List(Level.DEBUG)
      )
    },
    test("a database error upon verification is a system error") {
      for {
        (result, _, logged) <- handle(noEnrolledSecret.fail, unknownUserDb.fail)
      } yield assertTrue(
        result.left.exists(_.isInstanceOf[Unexpected]),
        logged == List(Level.ERROR)
      )
    },
    test("an unknown user without generated secret is a security warning") {
      for {
        (result, _, logged) <- handle(noEnrolledSecret.fail, unknownUser.fail)
      } yield assertTrue(
        result.left.exists(_.isInstanceOf[Unexpected]),
        logged == List(Level.WARN)
      )
    }
  )
}
