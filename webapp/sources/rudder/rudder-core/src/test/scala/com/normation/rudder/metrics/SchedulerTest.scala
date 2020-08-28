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

package com.normation.rudder.metrics

import java.util.concurrent.TimeUnit

import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner
import zio._
import zio.clock._
import zio.duration._
import zio.test.environment._


@RunWith(classOf[JUnitRunner])
class SchedulerTest extends Specification {

  "A complicated schedule" should {
/*
 * with min = 10 and max = 20
 * in :        1 min 5 min        11 min 19 min         32 min          56 min
 *             +1    +4           +6     +8             +13             +24
 * out: 0 min              10 min               20 min  32 min   52 min      62 min  82 min ...
 *
 */

    "yield execution at the correct time" in  {
      val prog = ZIO.accessM[Clock with TestClock] { testClock =>
        // in RC18-2, we need to sleep as long as we adjusted
        def adjust(d: Duration) = {
          testClock.get[TestClock.Service].adjust(d)
        }
        def take(q: Queue[Long], r: Ref[List[Long]]): UIO[Unit] = {
          for {
            x <- q.take
            _ <- r.update( x :: _ )
          } yield ()
        }

        for {

          q <- Queue.unbounded[Long]
          r <- Ref.make(List.empty[Long])
          c =  testClock.get[Clock.Service].currentTime(TimeUnit.MINUTES).flatMap(t => q.offer(t)).unit
          s <- Scheduler.make[Unit](10.minutes, 20.minutes, Unit => c, UIO.unit, testClock)
          // start prog and trigger event
          f <- s.start
          _ <- s.triggerSchedule(())
          _ <- take(q,r) // 0 min
          _ <- adjust( 1.minutes) *> s.triggerSchedule(()) //  1 min
          _ <- adjust( 4.minutes) *> s.triggerSchedule(()) //  5 min
          _ <- adjust( 5.minutes) // 10 min
          _ <- take(q,r) // 10 min
          _ <- adjust( 1.minutes) *> s.triggerSchedule(()) // 11 min
          _ <- adjust( 8.minutes) *> s.triggerSchedule(()) // 19 min
          _ <- adjust( 1.minutes) // 20 min
          _ <- take(q,r) // 20 min
          _ <- adjust(12.minutes) *> s.triggerSchedule(()) // 32 min
          // Due to https://github.com/zio/zio/issues/3395 this part is broken in `RC18-2`
//          _ <- take(q,r) // 32 min
//          _ <- adjust(20.minutes) // 52 min
//          _ <- take(q,r) // 52 min
//          _ <- s.triggerSchedule(()) // 52 min
//          _ <- adjust(10.minutes) // 62 min
//          _ <- take(q,r) // 62 min
//          _ <- adjust(20.minutes) // 82 min
//          _ <- take(q,r) // 82 min
//          _ <- adjust(20.minutes) // 102 min
//          _ <- take(q,r) // 102 min
          l <- r.get
        } yield l.reverse
      }.provideLayer(testEnvironment)
      val l = Runtime.default.unsafeRun(prog)
//      l must containTheSameElementsAs(List(0L, 10L, 20L, 32L, 52L, 62L, 82L, 102L))
      l must containTheSameElementsAs(List(0L, 10L, 20L))
    }
  }
}
