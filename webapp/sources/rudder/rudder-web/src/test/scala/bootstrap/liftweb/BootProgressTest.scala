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

package bootstrap.liftweb

import java.time.Duration
import org.junit.runner.RunWith
import scala.annotation.targetName
import zio.test.*
import zio.test.junit.ZTestJUnitRunner

/*
 * The point of the boot watchdog is to tell a slow boot from a dead one. Getting that wrong in
 * either direction is bad: claiming progress that did not happen hides a deadlock forever, and
 * declaring a stall too eagerly kills a boot that was legitimately doing a long migration.
 * So the decision itself is checked here.
 */
@RunWith(classOf[ZTestJUnitRunner])
class BootProgressTest extends ZIOSpecDefault {

  private val oneMinute  = Duration.ofMinutes(1)
  private val tenMinutes = Duration.ofMinutes(10)

  extension (a: (String, String)) {
    def asStep: BootStep = BootStep(BootPhase.BootChecks(a._1), a._2)
  }
  extension (a: (BootPhase, String)) {
    @targetName("phaseAsStep")
    def asStep: BootStep = BootStep(a._1, a._2)
  }

  // sequential: the last test ends the boot, and BootProgress is a global - there is no way back
  def spec: Spec[Any, Nothing] = {
    suiteAll("Boot progress watchdog verdict") {

      test("a step counter that moved is progress, whatever the time spent on the new step") {
        assertTrue(BootProgress.verdict(42, 41, tenMinutes.multipliedBy(10), tenMinutes) == BootWatchdogVerdict.Progressing)
      }

      test("the same step within the stall timeout is not progress, but not a stall either") {
        assertTrue(BootProgress.verdict(42, 42, oneMinute, tenMinutes) == BootWatchdogVerdict.SameStep)
      }

      test("the same step for longer than the stall timeout is a stall") {
        assertTrue(BootProgress.verdict(42, 42, tenMinutes.plusMinutes(1), tenMinutes) == BootWatchdogVerdict.Stalled)
      }

      test("exactly at the stall timeout is not yet a stall") {
        assertTrue(BootProgress.verdict(42, 42, tenMinutes, tenMinutes) == BootWatchdogVerdict.SameStep)
      }

      test("a zero stall timeout disables stall detection, however long boot stays on a step") {
        assertTrue(
          BootProgress.verdict(42, 42, tenMinutes.multipliedBy(100), Duration.ofMillis(0)) == BootWatchdogVerdict.SameStep
        )
      }

      test("the very first look, with no step done yet, is not a stall") {
        // steps == 0 and lastSeen == -1: the watchdog has never looked before
        assertTrue(BootProgress.verdict(0, -1, tenMinutes.multipliedBy(10), tenMinutes) == BootWatchdogVerdict.Progressing)
      }

      // one single test for the accounting: BootProgress is a global (there is only ever one boot),
      // so its recorded steps are shared state and must be checked in one place
      test("advance reports progress but never produces a duration") {
        // timing a step as "until the next advance" charges it with everything that happens in
        // between, which is how a trivial LDAP check got reported as taking 1m10s
        BootProgress.advance(("test-advance", "first").asStep)
        Thread.sleep(30)
        BootProgress.advance(("test-advance", "second").asStep)

        val recorded = BootProgress.slowestSteps(1000)._1.filter(_.step.phase.name == "test-advance")

        assertTrue(recorded.isEmpty)
      }

      test("a step nested in another one is timed on its own without stealing its parent's time") {
        val outer = "outer-step"
        val inner = "inner-step"

        BootProgress.step(BootPhase.BootChecks("test"), outer) {
          Thread.sleep(60)
          BootProgress.step(BootPhase.BootChecks("test"), inner) {
            Thread.sleep(120)
          }
        }

        val recorded = {
          BootProgress
            .slowestSteps(100)
            ._1
            .collect { case TimedStep(BootStep(BootPhase.BootChecks("test"), detail), d) => (detail, d) }
            .toMap
        }

        assertTrue(recorded.contains(outer)) &&
        assertTrue(recorded.contains(inner)) &&
        // the inner step is timed for itself...
        assertTrue(recorded(inner).toMillis >= 120) &&
        // ...and the outer one still accounts for everything it did, inner step included
        assertTrue(recorded(outer).toMillis >= recorded(inner).toMillis + 60L)
      }

      // last of the suite on purpose: it ends the boot, and there is no way back from that
      test("a step that runs once boot is over is not kept for the report") {
        val phase        = BootPhase.BootChecks("test-after-boot")
        // the instrumented code can run again at every policy generation, and keeping those steps
        // would grow a queue that nothing ever drains
        BootProgress.record((phase, "while booting").asStep, Duration.ofMillis(10))
        val whileBooting = BootProgress.slowestSteps(1000)._1.count(_.step.phase.name == phase.name)

        BootProgress.finished()
        BootProgress.record((phase, "after boot").asStep, Duration.ofMillis(10))

        assertTrue(whileBooting == 1) &&
        assertTrue(BootProgress.slowestSteps(1000)._1.count(_.step.phase.name == phase.name) == 1)
      }
    } @@ TestAspect.sequential
  }
}
