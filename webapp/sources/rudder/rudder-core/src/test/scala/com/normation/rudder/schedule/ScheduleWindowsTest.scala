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

package com.normation.rudder.schedule

import com.normation.rudder.campaigns.*
import com.normation.rudder.schedule.ScheduleWindows.Windows
import com.normation.utils.DateFormaterService.toJodaDateTime
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import org.junit.runner.RunWith
import org.specs2.mutable.*
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class ScheduleWindowsTest extends Specification {

  val utc: Option[ScheduleTimeZone] = Some(ScheduleTimeZone("UTC"))

  def date(day: Int, hour: Int, minute: Int = 0): Instant = {
    OffsetDateTime.of(2026, 6, day, hour, minute, 0, 0, ZoneOffset.UTC).toInstant
  }

  def window(startDay: Int, startHour: Int, endDay: Int, endHour: Int): ScheduleWindow = {
    ScheduleWindow(date(startDay, startHour), date(endDay, endHour))
  }

  "a daily schedule on 4:00-6:00 UTC" should {
    val daily = Daily(Time(4, 0), Time(6, 0), utc)

    "have a current window when t is inside it" in {
      ScheduleWindows.findWindows(daily, date(10, 5)) must beRight(
        Windows(Some(window(9, 4, 9, 6)), Some(window(10, 4, 10, 6)))
      )
    }

    "have a current window when t is exactly at start" in {
      ScheduleWindows.findWindows(daily, date(10, 4)) must beRight(
        Windows(Some(window(9, 4, 9, 6)), Some(window(10, 4, 10, 6)))
      )
    }

    "have no current window when t is exactly at end" in {
      ScheduleWindows.findWindows(daily, date(10, 6)) must beRight(
        Windows(Some(window(10, 4, 10, 6)), None)
      )
    }

    "have yesterday's window as last closed when t is before today's window" in {
      ScheduleWindows.findWindows(daily, date(10, 3)) must beRight(
        Windows(Some(window(9, 4, 9, 6)), None)
      )
    }

    "have today's window as last closed when t is after it" in {
      ScheduleWindows.findWindows(daily, date(10, 7)) must beRight(
        Windows(Some(window(10, 4, 10, 6)), None)
      )
    }
  }

  "a daily schedule crossing midnight (22:00-02:00 UTC)" should {
    val daily = Daily(Time(22, 0), Time(2, 0), utc)

    "have a current window when t is before midnight" in {
      ScheduleWindows.findWindows(daily, date(10, 23)) must beRight(
        Windows(Some(window(9, 22, 10, 2)), Some(window(10, 22, 11, 2)))
      )
    }

    "have a current window when t is after midnight" in {
      ScheduleWindows.findWindows(daily, date(10, 1)) must beRight(
        Windows(Some(window(8, 22, 9, 2)), Some(window(9, 22, 10, 2)))
      )
    }

    "have the night's window as last closed when in the morning" in {
      ScheduleWindows.findWindows(daily, date(10, 3)) must beRight(
        Windows(Some(window(9, 22, 10, 2)), None)
      )
    }
  }

  "a weekly schedule on Monday 9:00 - Monday 13:00 UTC" should {
    // 2026-06-01 is a Monday
    val weekly = WeeklySchedule(DayTime(Monday, 9, 0), DayTime(Monday, 13, 0), utc)

    "have last closed on previous Monday when t is Wednesday" in {
      ScheduleWindows.findWindows(weekly, date(10, 12)) must beRight(
        Windows(Some(window(8, 9, 8, 13)), None)
      )
    }

    "have a current window on Monday noon" in {
      ScheduleWindows.findWindows(weekly, date(8, 12)) must beRight(
        Windows(Some(window(1, 9, 1, 13)), Some(window(8, 9, 8, 13)))
      )
    }
  }

  "a one shot schedule" should {
    // OneShot is still a Joda-Time API
    val oneShot = OneShot(date(10, 4).toJodaDateTime, date(10, 6).toJodaDateTime)

    "have no window before it" in {
      ScheduleWindows.findWindows(oneShot, date(9, 12)) must beRight(Windows(None, None))
    }

    "be current during it" in {
      ScheduleWindows.findWindows(oneShot, date(10, 5)) must beRight(
        Windows(None, Some(window(10, 4, 10, 6)))
      )
    }

    "be last closed after it" in {
      ScheduleWindows.findWindows(oneShot, date(11, 12)) must beRight(
        Windows(Some(window(10, 4, 10, 6)), None)
      )
    }

    "be an error when end is before start" in {
      ScheduleWindows.findWindows(OneShot(date(10, 6).toJodaDateTime, date(10, 4).toJodaDateTime), date(10, 5)) must beLeft
    }
  }

  "a daily schedule on a timezone with DST" should {
    // Europe/Paris switches to summer time on 2026-03-29 (02:00 CET -> 03:00 CEST)
    // and back to winter time on 2026-10-25 (03:00 CEST -> 02:00 CET)
    val paris = Daily(Time(4, 0), Time(6, 0), Some(ScheduleTimeZone("Europe/Paris")))

    def utcDate(month: Int, day: Int, hour: Int, minute: Int = 0): Instant = {
      OffsetDateTime.of(2026, month, day, hour, minute, 0, 0, ZoneOffset.UTC).toInstant
    }

    "run at 4:00 local both before and after the spring transition (UTC instant shifts)" in {
      // 2026-03-28: 4:00 CET (UTC+1) is 3:00 UTC ; 2026-03-30: 4:00 CEST (UTC+2) is 2:00 UTC
      val before = ScheduleWindows.findWindows(paris, utcDate(3, 28, 4)).map(_.current)
      val after  = ScheduleWindows.findWindows(paris, utcDate(3, 30, 3)).map(_.current)

      (before must beRight(Some(ScheduleWindow(utcDate(3, 28, 3), utcDate(3, 28, 5))))) and
      (after must beRight(Some(ScheduleWindow(utcDate(3, 30, 2), utcDate(3, 30, 4)))))
    }

    "run at 4:00 local both before and after the autumn transition (UTC instant shifts)" in {
      // 2026-10-24: 4:00 CEST (UTC+2) is 2:00 UTC ; 2026-10-26: 4:00 CET (UTC+1) is 3:00 UTC
      val before = ScheduleWindows.findWindows(paris, utcDate(10, 24, 3)).map(_.current)
      val after  = ScheduleWindows.findWindows(paris, utcDate(10, 26, 4)).map(_.current)

      (before must beRight(Some(ScheduleWindow(utcDate(10, 24, 2), utcDate(10, 24, 4))))) and
      (after must beRight(Some(ScheduleWindow(utcDate(10, 26, 3), utcDate(10, 26, 5)))))
    }

    "skip the empty window on the day the clock jumps over the schedule time" in {
      // a 2:00-3:00 local schedule on 2026-03-29: the schedule falls in the DST gap (clocks
      // jump from 2:00 CET to 3:00 CEST) and both bounds resolve to 01:00 UTC. That empty
      // window can never contain a run: it is skipped, and the last closed window is the one
      // of the previous day (so no false 'missing' can be reported for the gap day).
      val skipped = Daily(Time(2, 0), Time(3, 0), Some(ScheduleTimeZone("Europe/Paris")))

      ScheduleWindows.findWindows(skipped, utcDate(3, 29, 1, 30)) must beRight(
        Windows(Some(ScheduleWindow(utcDate(3, 28, 1), utcDate(3, 28, 2))), None)
      )
    }
  }
}
