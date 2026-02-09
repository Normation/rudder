/*
 *************************************************************************************
 * Copyright 2026 Normation SAS
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
package com.normation.rudder.batch

import com.normation.rudder.MockGitConfigRepo
import com.normation.utils.CronParser.CronParser
import com.normation.zio.ZioRuntime
import java.time.Instant
import org.junit.runner.RunWith
import zio.*
import zio.test.*
import zio.test.Assertion.*
import zio.test.junit.ZTestJUnitRunner

@RunWith(classOf[ZTestJUnitRunner])
class GitGCTest extends ZIOSpecDefault {

  org.slf4j.LoggerFactory
    .getLogger("git-repository")
    .asInstanceOf[ch.qos.logback.classic.Logger]
    .setLevel(ch.qos.logback.classic.Level.DEBUG)

  val mock = MockGitConfigRepo()

  val count: Ref[Int] = ZioRuntime.unsafeRun(Ref.make(0))
  val testMonitor = new GitGC.LogProgressMonitor(mock.gitRepo.rootDirectory.pathAsString) {

    override def start(totalTasks: RuntimeFlags): Unit = {
      super.start(totalTasks)
      ZioRuntime.unsafeRun(count.update(_ + 1))
    }
  }
  val schedule = "0 42 3 * * ?".toOptCron.getOrElse(throw new RuntimeException("Test cron init error"))
  val gc       = new GitGC(mock.gitRepo, schedule, () => testMonitor)

  override def spec: Spec[TestEnvironment & Scope, Any] = suite("Test collision of GC") {
    test("A GC should not start again immediately, it must wait for the next event even if still in the same second") {
      for {
        _  <- TestClock.setTime(Instant.parse("2026-02-25T01:00:00.000Z"))
        _  <- gc.prog.forkDaemon
        _  <- TestClock.adjust(1.minute)
        c1 <- count.get
        _  <- TestClock.adjust(161.minutes)
        _  <- TestClock.adjust(100.millis)
        _  <- TestClock.adjust(100.millis)
        _  <- TestClock.adjust(100.millis)
        _  <- TestClock.adjust(100.millis)
        _  <- TestClock.adjust(100.millis)
        c2 <- count.get
      } yield assert((c1, c2))(equalTo((0, 1)))
    }
  }

}
