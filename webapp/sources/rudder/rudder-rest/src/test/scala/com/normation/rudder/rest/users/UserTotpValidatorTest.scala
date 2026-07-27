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
package com.normation.rudder.rest.users

import com.normation.errors.*
import com.normation.rudder.TestActor
import com.normation.rudder.users.*
import java.time.Instant
import java.time.OffsetDateTime
import org.junit.runner.RunWith
import zio.*
import zio.test.*
import zio.test.junit.ZTestJUnitRunner

/**
 * Tests for UserTotpValidator which has main logic for user TOTP unit of work
 */
@RunWith(classOf[ZTestJUnitRunner])
class UserTotpValidatorTest extends ZIOSpecDefault {
  import UserTotpValidatorTest.given

  val spec: Spec[Any, Nothing] = suite("UserTotpValidator")(
    test("should succeed when user exists and has no TOTP") {
      withCtx { validator =>
        for {
          _      <- addUser("alice")
          result <- validator.validateUserCanCreateTotp("alice").either
        } yield {
          assertTrue(result.isRight)
        }
      }
    },
    test("should fail with Inconsistency when user does not exist") {
      withCtx { validator =>
        for {
          result <- validator.validateUserCanCreateTotp("nonexistent").either
        } yield {
          assertTrue(
            result.isLeft,
            result match {
              case Left(msg: Inconsistency) => msg.fullMsg.contains(s"User 'nonexistent' is not known")
              case _                        => false
            }
          )
        }
      }
    },
    test("should fail when user already has a TOTP") {
      withCtx { validator =>
        for {
          _      <- addUser("bob")
          _      <- addTotp("bob", "secret123")
          result <- validator.validateUserCanCreateTotp("bob").either
        } yield {
          assertTrue(
            result.isLeft,
            result match {
              case Left(msg: Inconsistency) => msg.fullMsg.contains(s"User 'bob' already has a TOTP")
              case _                        => false
            }
          )
        }
      }
    }
  )

  /***  BUILDING CONTEXT FOR TESTS: HELPERS TO HELP READABILITY OF GIVEN PART OF TESTS ***/

  private type Ctx[A] = UserTotpValidator => InMemoryUserRepository ?=> InMemoryTotpRepository ?=> UIO[A]

  private def withCtx[A](block: Ctx[A]): UIO[A] = {
    (InMemoryUserRepository.make() <*> InMemoryTotpRepository.make()).flatMap((u, t) =>
      block(UserTotpValidator(u, t))(using u)(using t)
    )
  }

  private def addUser(str: String)(using repo: InMemoryUserRepository):                  UIO[Unit] = {
    val origin    = "test-totp"
    val traceDate = OffsetDateTime.now()
    val trace     = EventTrace(TestActor.get, traceDate)
    repo.addUser(origin, str, trace).unit
  }
  private def addTotp(user: String, secret: String)(using repo: InMemoryTotpRepository): UIO[Unit] = {
    repo.create(UserId(user), Totp(TotpSecret(secret), Instant.now()))
  }
}

private object UserTotpValidatorTest {
  // helper to avoid readability hurt
  given Conversion[String, UserId] = UserId(_)
}
