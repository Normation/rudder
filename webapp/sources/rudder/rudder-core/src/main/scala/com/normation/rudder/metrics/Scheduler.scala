/*
*************************************************************************************
* Copyright 2020 Normation SAS
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

package com.normation.rudder.metrics

import java.util.concurrent.TimeUnit

import com.normation.errors._
import zio._
import zio.clock.Clock
import zio.duration._
import zio.syntax._


/*
 * Scheduler to write logs in file. We want to log a new status line when interesting
 * events happens, like a node is added or deleted, but not too often (once every
 * 10min is well enought: you can't really use rudder below that timespan), and
 * if nothing interesting happens, we still want to have a log line every once and
 * then (1h).
 * So we define a scheduler that calls a consumer (the actual logger) at most every
 * `min` time and at least every `max` time, with:
 * - min must be smaller than max strictly.
 * Consumer must handle its errors.
 * It uses a queue with one element only to notice not yet processed element.
 *
 * It should be created with `Scheduler.make` which check for parameters consistancy.
 */
object Scheduler {
  /**
   * Create a scheduler
   * @param min: time to wait AT LEAST between action
   * @param max: time to wait AT MOST between action
   * @param action: action to execute periodically
   * @param default: default value to use as parameter for `action` if it's not started by an event
   * @param zclock: clock to use in that cron
   */
  def make[A](min: Duration, max: Duration, action: A => UIO[Unit], default: UIO[A], zclock: Clock): IOResult[Scheduler[A]] = {
    if(min >= max) {
      Inconsistency(s"Scheduler maximum period (${max.render}) cannot be shorter than its minimum period (${min.render})").fail
    } else {
      Queue.dropping[A](1).map(q => new Scheduler(min, max, q, action, default, zclock))
    }
  }
}

/*
 * The actual class. Assume all parameters are OK. You should use `Scheduler.make` to create it.
 */
class Scheduler[A](
    min    : Duration
  , max    : Duration
  , queue  : Queue[A]
  , action : A => UIO[Unit]
  , default: UIO[A] // the default value to used for consumer started by cron, not event
  , zclock : Clock
) {

  /**
   * External API to use when an event happens.
   */
  def triggerSchedule(a: A): UIO[Unit] = {
    for {
      n <- clock.currentTime(TimeUnit.MINUTES)
      - <- queue.offer(a).unit
    } yield ()
  }.provide(zclock)

  // time between min and max time, ie time to wait before looking for events.
  val delta = Duration.fromNanos(max.toNanos-min.toNanos)

  /*
   * The loop logic: wait for an event or the max duration and then sleep for
   * minimum time between action.
   * We need to special case the first iteration, because for it the max time is
   * really max time, while for next iteration, it's only (max - min).
   */
  val loop = {
    def oneStep(d: Duration) = {
      for {
        a <- queue.take.race(UIO.unit.delay(d) *> default)
        n <- clock.currentTime(TimeUnit.MINUTES)
        _ <- action(a)
        _ <- clock.sleep(min)
      } yield ()
    }
    oneStep(max) *> oneStep(delta).forever
  }

  /*
   *  Start a forever running fiber.
   */
  def start = loop.forkDaemon.provide(zclock)
}


