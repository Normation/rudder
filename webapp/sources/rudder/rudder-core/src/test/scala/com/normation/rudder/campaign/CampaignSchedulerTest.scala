/*
 *************************************************************************************
 * Copyright 2022 Normation SAS
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

package com.normation.rudder.campaign

import com.normation.errors.*
import com.normation.rudder.campaigns.*
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.joda.time.format.ISODateTimeFormat
import org.junit.runner.RunWith
import org.specs2.matcher.Matcher
import org.specs2.mutable.*
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class CampaignSchedulerTest extends Specification {

  val now:       DateTime     = DateTime.now(DateTimeZone.UTC)
  val defaultTz: DateTimeZone = DateTimeZone.getDefault()

  "A monthly campaign schedule set during december" should {

    // With bug, it was 2023/01/05 12:00 and end on 2023/01/09 18:00 instead of both date on 02/01/2023
    "Give a correct date in january for first occurrence" in {
      val s = CampaignDateScheduler
        .nextCampaignDate(
          MonthlySchedule(First, DayTime(Monday, 9, 27), DayTime(Monday, 13, 37), Some(ScheduleTimeZone("UTC"))),
          now.withYear(2022).withMonthOfYear(12).withDayOfMonth(7)
        )

      s must haveSchedule(
        now
          .withYear(2023)
          .withMonthOfYear(1)
          .withDayOfMonth(2)
          .withHourOfDay(9)
          .withMinuteOfHour(27)
          .withSecondOfMinute(0)
          .withMillisOfSecond(0),
        now
          .withYear(2023)
          .withMonthOfYear(1)
          .withDayOfMonth(2)
          .withHourOfDay(13)
          .withMinuteOfHour(37)
          .withSecondOfMinute(0)
          .withMillisOfSecond(0)
      )

    }

    "Give a correct date in january for Second occurrence" in {
      val s = CampaignDateScheduler
        .nextCampaignDate(
          MonthlySchedule(Second, DayTime(Monday, 9, 27), DayTime(Monday, 13, 37), Some(ScheduleTimeZone("UTC"))),
          now.withYear(2022).withMonthOfYear(12).withDayOfMonth(14)
        )

      s must haveSchedule(
        now
          .withYear(2023)
          .withMonthOfYear(1)
          .withDayOfMonth(9)
          .withHourOfDay(9)
          .withMinuteOfHour(27)
          .withSecondOfMinute(0)
          .withMillisOfSecond(0),
        now
          .withYear(2023)
          .withMonthOfYear(1)
          .withDayOfMonth(9)
          .withHourOfDay(13)
          .withMinuteOfHour(37)
          .withSecondOfMinute(0)
          .withMillisOfSecond(0)
      )
    }

    "Give a correct date in january for third occurrence" in {
      val s = CampaignDateScheduler
        .nextCampaignDate(
          MonthlySchedule(Third, DayTime(Monday, 9, 27), DayTime(Monday, 13, 37), Some(ScheduleTimeZone("UTC"))),
          now.withYear(2022).withMonthOfYear(12).withDayOfMonth(21)
        )

      s must haveSchedule(
        now
          .withYear(2023)
          .withMonthOfYear(1)
          .withDayOfMonth(16)
          .withHourOfDay(9)
          .withMinuteOfHour(27)
          .withSecondOfMinute(0)
          .withMillisOfSecond(0),
        now
          .withYear(2023)
          .withMonthOfYear(1)
          .withDayOfMonth(16)
          .withHourOfDay(13)
          .withMinuteOfHour(37)
          .withSecondOfMinute(0)
          .withMillisOfSecond(0)
      )
    }

    // With bug, it was 01/01/23 instead of 25/12
    "Give a correct date in December for last occurrence" in {
      val s = CampaignDateScheduler
        .nextCampaignDate(
          MonthlySchedule(Last, DayTime(Sunday, 9, 27), DayTime(Sunday, 13, 37), Some(ScheduleTimeZone("UTC"))),
          now.withYear(2022).withMonthOfYear(12).withDayOfMonth(7)
        )

      s must haveSchedule(
        now
          .withYear(2022)
          .withMonthOfYear(12)
          .withDayOfMonth(25)
          .withHourOfDay(9)
          .withMinuteOfHour(27)
          .withSecondOfMinute(0)
          .withMillisOfSecond(0),
        now
          .withYear(2022)
          .withMonthOfYear(12)
          .withDayOfMonth(25)
          .withHourOfDay(13)
          .withMinuteOfHour(37)
          .withSecondOfMinute(0)
          .withMillisOfSecond(0)
      )
    }

    // With bug, it was 25/12/22 instead of 18/12
    "Give a correct date in December for second last occurrence" in {
      val s = CampaignDateScheduler
        .nextCampaignDate(
          MonthlySchedule(SecondLast, DayTime(Sunday, 9, 27), DayTime(Sunday, 13, 37), Some(ScheduleTimeZone("UTC"))),
          now.withYear(2022).withMonthOfYear(12).withDayOfMonth(7)
        )

      s must haveSchedule(
        now
          .withYear(2022)
          .withMonthOfYear(12)
          .withDayOfMonth(18)
          .withHourOfDay(9)
          .withMinuteOfHour(27)
          .withSecondOfMinute(0)
          .withMillisOfSecond(0),
        now
          .withYear(2022)
          .withMonthOfYear(12)
          .withDayOfMonth(18)
          .withHourOfDay(13)
          .withMinuteOfHour(37)
          .withSecondOfMinute(0)
          .withMillisOfSecond(0)
      )
    }
  }

  "A one shot schedule" should {
    "correctly schedule" in {
      val start = now.plusDays(1)
      val end   = now.plusDays(2)

      val res = CampaignDateScheduler.nextCampaignDate(OneShot(start, end), now)

      res must haveSchedule(start, end)
    }

    "correctly schedule a run that ended in the past" in {
      val start = now.minusDays(2)
      val end   = now.minusDays(1)

      val res = CampaignDateScheduler.nextCampaignDate(OneShot(start, end), now)

      res must haveNoSchedule
    }

    "return error if start is equal or after end" in {
      val start = now.plusDays(2)
      val end   = now.plusDays(1)

      val res      = CampaignDateScheduler.nextCampaignDate(OneShot(start, end), now)
      val sameDate = CampaignDateScheduler.nextCampaignDate(OneShot(start, start), now)

      val beError = beLeft[RudderError].like {
        case Inconsistency(msg) => msg must startWith(s"Cannot schedule a one shot event")
      }
      (res must beError) and (sameDate must beError)
    }

    "correctly schedule a run with a different timezone from the server one" in {
      val zone  = DateTimeZone.forOffsetHours(-7)
      val start = now.plusDays(1).withZone(zone)
      val end   = now.plusDays(2).withZone(zone)

      val res = CampaignDateScheduler.nextCampaignDate(OneShot(start, end), now)

      res must haveSchedule(start, end)
    }

    "correctly schedule a run by translating timezone" in {
      // adding an negative offset that makes the start, end dates both be in the past
      val zone  = DateTimeZone.forOffsetHours(7)
      val start = now.plusHours(5).withZoneRetainFields(zone) // 2h before now
      val end   = now.plusHours(6).withZoneRetainFields(zone) // 1h before now

      val res =
        CampaignDateScheduler.nextCampaignDate(OneShot(start, end), now)

      res must haveNoSchedule
    }
  }

  "A daily schedule" should {
    val currentDate = DateTime.parse("2024-11-08T12:34:00Z")

    // we would expect schedule with tz=None to be equivalent to default tz=+01:00
    "correctly schedule" in {
      "current day" in {
        val start = Time(16, 30)
        val end   = Time(20, 30)

        val res = CampaignDateScheduler.nextCampaignDate(Daily(start, end, tz = None), currentDate)

        val nextDate = currentDate.withHourOfDay(start.hour).withMinuteOfHour(start.minute).withZoneRetainFields(defaultTz)
        res must haveSchedule(nextDate, nextDate.plusHours(4))
      }

      "next day when start day has already passed" in {
        val start = Time(10, 30)
        val end   = Time(14, 30)

        val res = CampaignDateScheduler.nextCampaignDate(Daily(start, end, tz = None), currentDate)

        val nextDay =
          currentDate.plusDays(1).withHourOfDay(start.hour).withMinuteOfHour(start.minute).withZoneRetainFields(defaultTz)
        res must haveSchedule(nextDay, nextDay.plusHours(4))
      }

      "next day when start day has already passed within the same hour" in {
        val start = Time(12, 30)
        val end   = Time(16, 30)

        val res = CampaignDateScheduler.nextCampaignDate(Daily(start, end, tz = None), currentDate)

        val nextDay =
          currentDate.plusDays(1).withHourOfDay(start.hour).withMinuteOfHour(start.minute).withZoneRetainFields(defaultTz)
        res must haveSchedule(nextDay, nextDay.plusHours(4))
      }

      "with overlap" in {
        // overlap (start is passed, but end is not and is on the same day)
        val start    = Time(20, 30)
        val end      = Time(10, 30)
        val schedule = Daily(start, end, tz = None)

        val res = CampaignDateScheduler.nextCampaignDate(
          schedule,
          currentDate
        )

        val scheduleDate = currentDate
          .withHourOfDay(start.hour)
          .withMinuteOfHour(start.minute)
          .withSecondOfMinute(0)
          .withMillisOfSecond(0)
          .withZoneRetainFields(defaultTz)
        res must haveSchedule(scheduleDate, scheduleDate.plusHours(14))
      }

      "with specific schedule timezone" in {
        val scheduleTimeZone = DateTimeZone.forOffsetHours(6)
        val start            = Time(19, 30)
        val end              = Time(23, 30)
        // 19h30 at +06:00 is 13h30 UTC so it's scheduled on same day
        val schedule         = Daily(start, end, Some(ScheduleTimeZone(scheduleTimeZone.getID)))

        val res = CampaignDateScheduler.nextCampaignDate(
          schedule,
          currentDate
        )

        val scheduleDate = currentDate
          .withHourOfDay(start.hour)
          .withMinuteOfHour(start.minute)
          .withSecondOfMinute(0)
          .withMillisOfSecond(0)
          .withZoneRetainFields(scheduleTimeZone)
        res must haveSchedule(scheduleDate, scheduleDate.plusHours(4))
      }

      "with specific schedule timezone for next day" in {
        // special case where the time zone being not the same as the default one makes it theoretically run on next day
        // 10h30 at +06:00 is 04h30 UTC so it's scheduled on next day
        val scheduleTimeZone = DateTimeZone.forOffsetHours(6)
        val start            = Time(10, 30)
        val end              = Time(14, 30)
        val schedule         = Daily(start, end, Some(ScheduleTimeZone(scheduleTimeZone.getID)))

        val res = CampaignDateScheduler.nextCampaignDate(
          schedule,
          currentDate
        )

        val scheduleDate = currentDate
          .plusDays(1)
          .withHourOfDay(start.hour)
          .withMinuteOfHour(start.minute)
          .withSecondOfMinute(0)
          .withMillisOfSecond(0)
          .withZoneRetainFields(scheduleTimeZone)
        res must haveSchedule(scheduleDate, scheduleDate.plusHours(4))
      }

      "with specific schedule timezone with overlap" in {
        // overlap (start is passed, but end is not and is on the same day) : it needs to be scheduled on next day
        // 16h30 at -06:00 is 22h30 UTC and 20h30 at -06:00 is 02h30 UTC on the next day
        val scheduleTimeZone = DateTimeZone.forOffsetHours(-6)
        val start            = Time(16, 30)
        val end              = Time(20, 30)
        val schedule         = Daily(start, end, Some(ScheduleTimeZone(scheduleTimeZone.getID)))

        val res = CampaignDateScheduler.nextCampaignDate(
          schedule,
          currentDate
        )

        val scheduleDate = currentDate
          .withHourOfDay(start.hour)
          .withMinuteOfHour(start.minute)
          .withSecondOfMinute(0)
          .withMillisOfSecond(0)
          .withZoneRetainFields(scheduleTimeZone)
        res must haveSchedule(scheduleDate, scheduleDate.plusHours(4))
      }
    }

  }

  "A weekly schedule" should {
    // 2024-11-08 is a Friday
    val currentDate = DateTime.parse("2024-11-08T12:34:00Z")

    "correctly schedule" in {
      "current week" in {
        val start = DayTime(Friday, 16, 0)
        val end   = DayTime(Saturday, 16, 0)

        val res = CampaignDateScheduler.nextCampaignDate(WeeklySchedule(start, end, tz = None), currentDate)

        val nextDate = currentDate.withHourOfDay(start.hour).withMinuteOfHour(start.minute).withZoneRetainFields(defaultTz)
        res must haveSchedule(nextDate, nextDate.plusDays(1))
      }

      "next week when time in week has already passed" in {
        val start = DayTime(Monday, 16, 0)
        val end   = DayTime(Tuesday, 16, 0)

        val res = CampaignDateScheduler.nextCampaignDate(WeeklySchedule(start, end, tz = None), currentDate)

        val nextWeek = currentDate
          .plusDays(3)
          .withHourOfDay(start.hour)
          .withMinuteOfHour(start.minute)
          .withZoneRetainFields(defaultTz)
        res must haveSchedule(nextWeek, nextWeek.plusDays(1))
      }

      "next week when time in week has already passed within the same hour" in {
        val start = DayTime(Monday, 12, 0)
        val end   = DayTime(Tuesday, 12, 0)

        val res = CampaignDateScheduler.nextCampaignDate(WeeklySchedule(start, end, tz = None), currentDate)

        val nextWeek = currentDate
          .plusDays(3)
          .withHourOfDay(start.hour)
          .withMinuteOfHour(start.minute)
          .withZoneRetainFields(defaultTz)
        res must haveSchedule(nextWeek, nextWeek.plusDays(1))
      }

      "with week overlap" in {
        // week starts with Monday : the schedule should still be possible from previous week to the next one
        val start = DayTime(Sunday, 12, 0)
        val end   = DayTime(Monday, 12, 0)

        val res = CampaignDateScheduler.nextCampaignDate(WeeklySchedule(start, end, tz = None), currentDate)

        val nextWeek = currentDate
          .plusDays(2)
          .withHourOfDay(start.hour)
          .withMinuteOfHour(start.minute)
          .withZoneRetainFields(defaultTz)
        res must haveSchedule(nextWeek, nextWeek.plusDays(1))
      }

      "with specific schedule timezone" in {
        val scheduleTimeZone = DateTimeZone.forOffsetHours(3)
        val start            = DayTime(Friday, 16, 0)
        val end              = DayTime(Saturday, 16, 0)
        // 16h00 at +03:00 is 13h00 UTC so it's scheduled on same week (in fact on same day)
        val schedule         = WeeklySchedule(start, end, Some(ScheduleTimeZone(scheduleTimeZone.getID)))

        val res = CampaignDateScheduler.nextCampaignDate(
          schedule,
          currentDate
        )

        val scheduleDate = currentDate
          .withHourOfDay(start.hour)
          .withMinuteOfHour(start.minute)
          .withSecondOfMinute(0)
          .withMillisOfSecond(0)
          .withZoneRetainFields(scheduleTimeZone)
        res must haveSchedule(scheduleDate, scheduleDate.plusDays(1))
      }

      "with specific schedule timezone for next week" in {
        // special case where the time zone being not the same as the default one makes it theoretically run on next week (start is before current day)
        val scheduleTimeZone = DateTimeZone.forOffsetHours(6)
        val start            = DayTime(Friday, 16, 0)
        val end              = DayTime(Saturday, 16, 0)
        val schedule         = WeeklySchedule(start, end, Some(ScheduleTimeZone(scheduleTimeZone.getID)))

        val res = CampaignDateScheduler.nextCampaignDate(
          schedule,
          currentDate
        )

        val scheduleDate = currentDate
          .plusWeeks(1)
          .withHourOfDay(start.hour)
          .withMinuteOfHour(start.minute)
          .withSecondOfMinute(0)
          .withMillisOfSecond(0)
          .withZoneRetainFields(scheduleTimeZone)
        res must haveSchedule(scheduleDate, scheduleDate.plusDays(1))
      }

      "with specific schedule timezone for the end of the week" in {
        // special case where the time zone being not the same as the default one makes it theoretically run starting from next week
        val scheduleTimeZone = DateTimeZone.forOffsetHours(-6)
        val start            = DayTime(Sunday, 20, 0)
        val end              = DayTime(Monday, 20, 0)
        val schedule         = WeeklySchedule(start, end, Some(ScheduleTimeZone(scheduleTimeZone.getID)))

        val res = CampaignDateScheduler.nextCampaignDate(
          schedule,
          currentDate
        )

        val scheduleDate = currentDate
          .plusDays(2)
          .withHourOfDay(start.hour)
          .withMinuteOfHour(start.minute)
          .withSecondOfMinute(0)
          .withMillisOfSecond(0)
          .withZoneRetainFields(scheduleTimeZone)
        res must haveSchedule(scheduleDate, scheduleDate.plusDays(1))
      }
    }

  }

  "A monthly schedule" should {
    "correctly schedule first week" in {
      "first week in current week" in {
        val currentDate = DateTime.parse("2024-11-04T11:11:00Z")
        // monday 4th november, schedule for same week (same day)
        val start       = DayTime(Monday, 16, 0)
        val end         = DayTime(Wednesday, 16, 0)

        val res = CampaignDateScheduler.nextCampaignDate(MonthlySchedule(First, start, end, tz = None), currentDate)

        val nextDate = {
          currentDate
            .withHourOfDay(start.hour)
            .withMinuteOfHour(start.minute)
            .withZoneRetainFields(defaultTz)
        }
        res must haveSchedule(nextDate, nextDate.plusDays(2))
      }

      "first week in next month" in {
        val currentDate = DateTime.parse("2024-11-04T11:11:00Z")
        // monday 4th november, schedule for same day but in the past : it should be next month
        val start       = DayTime(Monday, 10, 0)
        val end         = DayTime(Wednesday, 10, 0)

        val res = CampaignDateScheduler.nextCampaignDate(MonthlySchedule(First, start, end, tz = None), currentDate)

        val nextDate = {
          currentDate
            .plusMonths(1)
            .withDayOfWeek(start.day.value)
            .withHourOfDay(start.hour)
            .withMinuteOfHour(start.minute)
            .withZoneRetainFields(defaultTz)
        }
        res must haveSchedule(nextDate, nextDate.plusDays(2))
      }

      "first week of the year from the previous year" in {
        // edge case when year is not the same
        val currentDate = DateTime.parse("2024-12-31T12:34:00Z")
        // first of january is wednesday
        val start       = DayTime(Wednesday, 16, 0)
        val end         = DayTime(Friday, 16, 0)

        val res = CampaignDateScheduler.nextCampaignDate(MonthlySchedule(First, start, end, tz = None), currentDate)

        val nextDate =
          currentDate.plusDays(1).withHourOfDay(start.hour).withMinuteOfHour(start.minute).withZoneRetainFields(defaultTz)
        res must haveSchedule(nextDate, nextDate.plusDays(2))
      }
    }

    "correctly schedule second week" in {
      "second week in current week" in {
        val currentDate = DateTime.parse("2024-11-11T11:11:00Z")
        // monday 11th november, schedule for same week (same day)
        val start       = DayTime(Monday, 16, 0)
        val end         = DayTime(Wednesday, 16, 0)

        val res = CampaignDateScheduler.nextCampaignDate(MonthlySchedule(Second, start, end, tz = None), currentDate)

        val nextDate = {
          currentDate
            .withHourOfDay(start.hour)
            .withMinuteOfHour(start.minute)
            .withZoneRetainFields(defaultTz)
        }
        res must haveSchedule(nextDate, nextDate.plusDays(2))
      }

      "second week in next month" in {
        val currentDate = DateTime.parse("2024-11-11T11:11:00Z")
        // monday 11th november, schedule for same day but in the past : it should be next month
        val start       = DayTime(Monday, 10, 0)
        val end         = DayTime(Wednesday, 10, 0)

        val res = CampaignDateScheduler.nextCampaignDate(MonthlySchedule(Second, start, end, tz = None), currentDate)

        val nextDate = {
          currentDate
            .plusMonths(1)
            .withDayOfWeek(start.day.value)
            .withHourOfDay(start.hour)
            .withMinuteOfHour(start.minute)
            .withZoneRetainFields(defaultTz)
        }
        res must haveSchedule(nextDate, nextDate.plusDays(2))
      }

      "second week of the year from the previous year" in {
        val currentDate = DateTime.parse("2024-12-31T12:34:00Z")
        // 8th of january is wednesday
        val start       = DayTime(Wednesday, 16, 0)
        val end         = DayTime(Friday, 16, 0)

        val res = CampaignDateScheduler.nextCampaignDate(MonthlySchedule(Second, start, end, tz = None), currentDate)

        val nextDate =
          currentDate.plusDays(8).withHourOfDay(start.hour).withMinuteOfHour(start.minute).withZoneRetainFields(defaultTz)
        res must haveSchedule(nextDate, nextDate.plusDays(2))
      }
    }

    "correctly schedule third week" in {
      "third week in current week" in {
        val currentDate = DateTime.parse("2024-11-18T11:11:00Z")
        // monday 18th november, schedule for same week (same day)
        val start       = DayTime(Monday, 16, 0)
        val end         = DayTime(Wednesday, 16, 0)

        val res = CampaignDateScheduler.nextCampaignDate(MonthlySchedule(Third, start, end, tz = None), currentDate)

        val nextDate = {
          currentDate
            .withHourOfDay(start.hour)
            .withMinuteOfHour(start.minute)
            .withZoneRetainFields(defaultTz)
        }
        res must haveSchedule(nextDate, nextDate.plusDays(2))
      }

      "third week in next month" in {
        val currentDate = DateTime.parse("2024-11-18T11:11:00Z")
        // monday 18th november, schedule for same day but in the past : it should be next month
        val start       = DayTime(Monday, 10, 0)
        val end         = DayTime(Wednesday, 10, 0)

        val res = CampaignDateScheduler.nextCampaignDate(MonthlySchedule(Third, start, end, tz = None), currentDate)

        val nextDate = {
          currentDate
            .plusMonths(1)
            .withDayOfWeek(start.day.value)
            .withHourOfDay(start.hour)
            .withMinuteOfHour(start.minute)
            .withZoneRetainFields(defaultTz)
        }
        res must haveSchedule(nextDate, nextDate.plusDays(2))
      }

      "third week of the year from the previous year" in {
        val currentDate = DateTime.parse("2024-12-31T12:34:00Z")
        // 15th of january is wednesday
        val start       = DayTime(Wednesday, 16, 0)
        val end         = DayTime(Friday, 16, 0)

        val res = CampaignDateScheduler.nextCampaignDate(MonthlySchedule(Third, start, end, tz = None), currentDate)

        val nextDate =
          currentDate.plusDays(15).withHourOfDay(start.hour).withMinuteOfHour(start.minute).withZoneRetainFields(defaultTz)
        res must haveSchedule(nextDate, nextDate.plusDays(2))
      }
    }

    "correctly schedule second last week" in {
      "second last week in current week" in {
        val currentDate = DateTime.parse("2024-11-18T11:11:00Z")
        // monday 18th november, schedule for second last week : it's on the same week so it's on the same day
        val start       = DayTime(Monday, 16, 0)
        val end         = DayTime(Wednesday, 16, 0)

        val res = CampaignDateScheduler.nextCampaignDate(MonthlySchedule(SecondLast, start, end, tz = None), currentDate)

        val nextDate = {
          currentDate
            .withDayOfWeek(start.day.value)
            .withHourOfDay(start.hour)
            .withMinuteOfHour(start.minute)
            .withZoneRetainFields(defaultTz)
        }
        res must haveSchedule(nextDate, nextDate.plusDays(2))
      }

      "second last week in next month" in {
        val currentDate = DateTime.parse("2024-11-18T11:11:00Z")
        // monday 18th november, schedule for same day but in the past : it should be next month
        val start       = DayTime(Monday, 10, 0)
        val end         = DayTime(Wednesday, 10, 0)

        val res = CampaignDateScheduler.nextCampaignDate(MonthlySchedule(SecondLast, start, end, tz = None), currentDate)

        val nextDate = {
          currentDate
            .plusMonths(1)
            // in december 2024 the second last monday of the month is yet one week after
            .plusWeeks(1)
            .withDayOfWeek(start.day.value)
            .withHourOfDay(start.hour)
            .withMinuteOfHour(start.minute)
            .withZoneRetainFields(defaultTz)
        }
        res must haveSchedule(nextDate, nextDate.plusDays(2))
      }

      "second last week of the year from the previous year" in {
        val currentDate = DateTime.parse("2024-12-31T12:34:00Z")
        // 22nd of january is wednesday
        val start       = DayTime(Wednesday, 16, 0)
        val end         = DayTime(Friday, 16, 0)

        val res = CampaignDateScheduler.nextCampaignDate(MonthlySchedule(SecondLast, start, end, tz = None), currentDate)

        val nextDate =
          currentDate.plusDays(22).withHourOfDay(start.hour).withMinuteOfHour(start.minute).withZoneRetainFields(defaultTz)
        res must haveSchedule(nextDate, nextDate.plusDays(2))
      }
    }

    "correctly schedule last week" in {
      "last week in current week" in {
        val currentDate = DateTime.parse("2024-11-25T11:11:00Z")
        // monday 25th november, schedule for last week
        val start       = DayTime(Monday, 16, 0)
        val end         = DayTime(Wednesday, 16, 0)

        val res = CampaignDateScheduler.nextCampaignDate(MonthlySchedule(Last, start, end, tz = None), currentDate)

        val nextDate = {
          currentDate
            .withDayOfWeek(start.day.value)
            .withHourOfDay(start.hour)
            .withMinuteOfHour(start.minute)
            .withZoneRetainFields(defaultTz)
        }
        res must haveSchedule(nextDate, nextDate.plusDays(2))
      }

      "last week in next month" in {
        val currentDate = DateTime.parse("2024-11-25T11:11:00Z")
        // monday 25th november, schedule for same day but in the past : it should be next month
        val start       = DayTime(Monday, 10, 0)
        val end         = DayTime(Wednesday, 10, 0)

        val res = CampaignDateScheduler.nextCampaignDate(MonthlySchedule(Last, start, end, tz = None), currentDate)

        val nextDate = {
          currentDate
            .plusMonths(1)
            // in december 2024 the last monday of the month is yet one week after
            .plusWeeks(1)
            .withDayOfWeek(start.day.value)
            .withHourOfDay(start.hour)
            .withMinuteOfHour(start.minute)
            .withZoneRetainFields(defaultTz)
        }
        res must haveSchedule(nextDate, nextDate.plusDays(2))
      }

      "last week of the year from the previous year" in {
        val currentDate = DateTime.parse("2024-12-31T12:34:00Z")
        // 29th of january is wednesday
        val start       = DayTime(Wednesday, 16, 0)
        val end         = DayTime(Friday, 16, 0)

        val res = CampaignDateScheduler.nextCampaignDate(MonthlySchedule(Last, start, end, tz = None), currentDate)

        val nextDate =
          currentDate.plusDays(29).withHourOfDay(start.hour).withMinuteOfHour(start.minute).withZoneRetainFields(defaultTz)
        res must haveSchedule(nextDate, nextDate.plusDays(2))
      }
    }
  }

  // we need to compare ISO date time strings, specs2 seems to not detect equality with the timezone setup for each test
  private val format = ISODateTimeFormat.dateTime()

  private def haveScheduleStart(date: DateTime)            = {
    beRight(beSome(be_===(date.toString(format)))) ^^ ((t: PureResult[Option[(DateTime, DateTime)]]) =>
      t.map(_.map { case (start, _) => start.toString(format) })
    )
  }
  private def haveScheduleEnd(date: DateTime)              = {
    beRight(beSome(be_===(date.toString(format)))) ^^ ((t: PureResult[Option[(DateTime, DateTime)]]) =>
      t.map(_.map { case (_, end) => end.toString(format) })
    )
  }
  private def haveSchedule(start: DateTime, end: DateTime) = {
    haveScheduleStart(start) and haveScheduleEnd(end)
  }

  private def haveNoSchedule: Matcher[PureResult[Option[(DateTime, DateTime)]]] = beRight(beNone)
}
