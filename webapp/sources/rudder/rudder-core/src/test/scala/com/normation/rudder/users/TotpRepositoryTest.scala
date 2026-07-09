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
 * along with Rudder.  If not, see <http://www.gnu.org/licenses/>.
 *
 *
 * *************************************************************************************
 */

package com.normation.rudder.users

import TotpRepositoryTest.given
import com.normation.errors.IOResult
import com.normation.rudder.TestActor
import com.normation.rudder.db.DBCommon
import com.normation.rudder.db.Doobie
import com.normation.zio.*
import doobie.*
import doobie.specs2.analysisspec.IOChecker
import java.time.Instant
import java.time.ZoneOffset
import net.liftweb.common.Loggable
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

// Shared trait with test logic — runs in both InMemory and Jdbc modes
trait TotpRepositoryTest extends Specification with Loggable {
  def doobie: Doobie
  def doJdbcTest = false

  sequential

  def repo: TotpRepository

  val secretAlice = TotpSecret("alice-totp-secret")
  val secretBob   = TotpSecret("bob-totp-secret")
  val totpAlice   = Totp(secretAlice, Instant.parse("2024-01-01T00:00:00Z"))
  val totpBob     = Totp(secretBob, Instant.parse("2024-02-01T00:00:00Z"))

  "create" >> {
    "store a new TOTP for a user" in {
      repo.create("alice", totpAlice).runNow
      repo.getByUserId("alice").runNow must beSome(totpAlice)
    }

    "overwrite an existing TOTP for the same user (upsert semantics)" in {
      val newSecret = TotpSecret("alice-new-secret")
      val newTotp   = Totp(newSecret, Instant.parse("2024-03-01T00:00:00Z"))
      repo.create("alice", newTotp).runNow
      repo.getByUserId("alice").runNow must beSome(newTotp)
    }
  }

  "getByUserId" >> {
    "return None for a user without TOTP" in {
      repo.getByUserId("unknown").runNow must beNone
    }

    "return Some(Totp) for an enrolled user" in {
      // Ensure alice is enrolled for this test
      repo.create("alice", totpAlice).runNow
      repo.getByUserId("alice").runNow must beSome(totpAlice)
    }
  }

  "delete" >> {
    "remove a user's TOTP" in {
      repo.create("bob", totpBob).runNow
      repo.delete("bob").runNow
      repo.getByUserId("bob").runNow must beNone
    }

    "succeed silently when deleting a non-existent TOTP" in {
      repo.delete("nonexistent").runNow // should not throw
      repo.getByUserId("nonexistent").runNow must beNone
    }
  }

  "getEnabledUsers" >> {
    "return all user IDs that have a TOTP" in {
      repo.create("alice", totpAlice).runNow
      repo.create("bob", totpBob).runNow
      repo.getEnabledUsers().runNow must containTheSameElementsAs(List("alice", "bob"))
    }

    "return empty set when no users have TOTP" in {
      repo.delete("alice").runNow
      repo.delete("bob").runNow
      repo.getEnabledUsers().runNow must beEmpty
    }
  }
}

// This one uses postgres and runs only with -Dtest.postgres="true"
@RunWith(classOf[JUnitRunner])
class JdbcTotpRepositoryTest extends TotpRepositoryTest with IOChecker with DBCommon {
  sequential

  def transactor: Transactor[cats.effect.IO] = doobie.xaio

  override def doJdbcTest = doDatabaseConnection

  // format: off
  org.slf4j.LoggerFactory.getLogger("sql").asInstanceOf[ch.qos.logback.classic.Logger].setLevel(ch.qos.logback.classic.Level.TRACE)
  org.slf4j.LoggerFactory.getLogger("application.user").asInstanceOf[ch.qos.logback.classic.Logger].setLevel(ch.qos.logback.classic.Level.TRACE)
  // format: on

  override def afterAll(): Unit = {
    cleanDb()
  }

  if (!doJdbcTest) skipAll

  private val TEST_ORIGIN = "test-totp"

  private lazy val userRepo = JdbcUserRepository(doobie)
  lazy val repo: TotpRepository = new JdbcTotpRepository(doobie) {
    // need to init users repo too
    override def create(userId: UserId, totp: Totp): IOResult[Unit] = {
      userRepo.addUser(TEST_ORIGIN, userId.value, EventTrace(TestActor.get, totp.created.atOffset(ZoneOffset.UTC))) *>
      super.create(userId, totp)
    }
  }

  check(JdbcTotpRepository.getByUserIdSQL("alice"))
  check(JdbcTotpRepository.upsertQuerySQL("alice", Totp(TotpSecret("secret"), Instant.now())))
  check(JdbcTotpRepository.deleteQuerySQL("alice"))
  check(JdbcTotpRepository.getAllByUserSQL())

  "repository interface" >> {
    "getByUserId returns Some(Totp) after create" in {
      val secret = TotpSecret("test-secret")
      val totp   = Totp(secret, Instant.parse("2024-04-01T12:00:00Z"))
      repo.create("alice", totp).runNow
      repo.getByUserId("alice").runNow must beSome(totp)
    }

    "getByUserId returns None before create" in {
      repo.getByUserId("nonexistent").runNow must beNone
    }

    "delete removes the TOTP" in {
      val secret = TotpSecret("test-secret")
      val totp   = Totp(secret, Instant.parse("2024-05-01T00:00:00Z"))
      repo.create("bob", totp).runNow
      repo.delete("bob").runNow
      repo.getByUserId("bob").runNow must beNone
    }

    "getEnabledUsers returns all users with TOTP" in {
      val totp1 = Totp(TotpSecret("user1-secret"), Instant.parse("2024-06-01T00:00:00Z"))
      val totp2 = Totp(TotpSecret("user2-secret"), Instant.parse("2024-07-01T00:00:00Z"))
      repo.create("charlie", totp1).runNow
      repo.create("dave", totp2).runNow
      // bob has been deleted :(
      repo.getEnabledUsers().runNow must containTheSameElementsAs(List("alice", "charlie", "dave"))
    }
  }
}

private object TotpRepositoryTest {
  // helper to avoid readability hurt
  given Conversion[String, UserId] = UserId(_)
}
