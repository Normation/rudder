/*
 *************************************************************************************
 * Copyright 2025 Normation SAS
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

package com.normation.rudder.campaigns

import cats.implicits.*
import com.normation.errors.*
import com.normation.utils.DateFormaterService
import org.joda.time.*

/*
 * This object contains the logic to compute campaign event dates.
 * It takes care of time zone and other date shiny things.
 */
object CampaignDateScheduler {

  implicit class DateTimeTzOps(date: DateTime) {
    def adjustScheduleTimeZone(optTimeZone: Option[ScheduleTimeZone])(implicit defaultTz: DateTimeZone): DateTime = {
      optTimeZone.flatMap(_.toDateTimeZone) match {
        // no schedule time zone (or invalid one) means the default one should be used
        case None             => date.withZone(defaultTz)
        case Some(scheduleTz) => date.withZone(scheduleTz)
      }
    }
  }

  private def nextDateFromDayTime(date: DateTime, start: DayTime): DateTime = {
    (if (
       date.getDayOfWeek > start.day.value
       || (date.getDayOfWeek == start.day.value && date.getHourOfDay > start.realHour)
       || (date.getDayOfWeek == start.day.value && date.getHourOfDay == start.realHour && date.getMinuteOfHour > start.realMinute)
     ) {
       date.plusWeeks(1)
     } else {
       date
     })
      .withDayOfWeek(start.day.value)
      .withHourOfDay(start.realHour)
      .withMinuteOfHour(start.realMinute)
      .withSecondOfMinute(0)
      .withMillisOfSecond(0)
  }

  def nextCampaignDate(
      schedule: CampaignSchedule,
      date:     DateTime
  ): PureResult[Option[(DateTime, DateTime)]] = {
    // Schedule needs to be adjusted to current server timezone, not to the one from the base schedule date
    implicit val currentTz: DateTimeZone = DateTimeZone.getDefault()
    schedule match {
      case OneShot(start, end) =>
        if (start.isBefore(end)) {
          if (end.isAfter(date)) {
            Some((start, end)).asRight
          } else {
            None.asRight
          }
        } else {

          Inconsistency(s"Cannot schedule a one shot event if end (${DateFormaterService
              .getDisplayDate(end)}) date is before start date (${DateFormaterService.getDisplayDate(start)})").asLeft
        }

      case Daily(start, end, tz) =>
        val scheduleInitialDate = date.adjustScheduleTimeZone(tz)
        val startDate           = {
          (if (
             scheduleInitialDate
               .getHourOfDay() > start.realHour || (scheduleInitialDate.getHourOfDay() == start.realHour && scheduleInitialDate
               .getMinuteOfHour() > start.realMinute)
           ) {
             scheduleInitialDate.plusDays(1)
           } else {
             scheduleInitialDate
           })
            .withHourOfDay(start.realHour)
            .withMinuteOfHour(start.realMinute)
            .withSecondOfMinute(0)
            .withMillisOfSecond(0)
        }

        val endDate = {
          (if (end.realHour < start.realHour || (end.realHour == start.realHour && end.realMinute < start.realMinute)) {
             startDate.plusDays(1)
           } else {
             startDate
           }).withHourOfDay(end.realHour).withMinuteOfHour(end.realMinute).withSecondOfMinute(0).withMillisOfSecond(0)
        }

        Some((startDate, endDate)).asRight

      case WeeklySchedule(start, end, tz) =>
        val scheduleInitialDate = date.adjustScheduleTimeZone(tz)
        val startDate           = nextDateFromDayTime(scheduleInitialDate, start)
        val endDate             = nextDateFromDayTime(startDate, end)

        Some((startDate, endDate)).asRight

      case MonthlySchedule(position, start, end, tz) =>
        val scheduleInitialDate  = date.adjustScheduleTimeZone(tz)
        val realHour             = start.realHour
        val realMinutes          = start.realMinute
        val day                  = start.day
        def base(date: DateTime) = (position match {
          case First      =>
            val t = date.withDayOfMonth(1).withDayOfWeek(day.value)
            if ((t.getYear == date.getYear && t.getMonthOfYear < date.getMonthOfYear) || t.getYear + 1 == date.getYear) {
              t.plusWeeks(1)
            } else {
              t
            }
          case Second     =>
            val t = date.withDayOfMonth(1).withDayOfWeek(day.value)
            if ((t.getYear == date.getYear && t.getMonthOfYear < date.getMonthOfYear) || t.getYear + 1 == date.getYear) {
              t.plusWeeks(2)
            } else {
              t.plusWeeks(1)
            }
          case Third      =>
            val t = date.withDayOfMonth(1).withDayOfWeek(day.value)
            if ((t.getYear == date.getYear && t.getMonthOfYear < date.getMonthOfYear) || t.getYear + 1 == date.getYear) {
              t.plusWeeks(3)
            } else {
              t.plusWeeks(2)
            }
          case Last       =>
            val t = date.plusMonths(1).withDayOfMonth(1).withDayOfWeek(day.value)
            if ((t.getYear == date.getYear && t.getMonthOfYear > date.getMonthOfYear) || t.getYear == date.getYear + 1) {
              t.minusWeeks(1)
            } else {
              t
            }
          case SecondLast =>
            val t = date.plusMonths(1).withDayOfMonth(1).withDayOfWeek(day.value)
            if ((t.getYear == date.getYear && t.getMonthOfYear > date.getMonthOfYear) || t.getYear == date.getYear + 1) {
              t.minusWeeks(2)
            } else {
              t.minusWeeks(1)
            }
        }).withHourOfDay(realHour).withMinuteOfHour(realMinutes).withSecondOfMinute(0).withMillisOfSecond(0)
        val currentMonthStart    = base(scheduleInitialDate)
        val startDate            = {
          if (scheduleInitialDate.isAfter(currentMonthStart)) {
            base(scheduleInitialDate.plusMonths(1))
          } else {
            currentMonthStart
          }
        }
        val endDate              = nextDateFromDayTime(startDate, end)
        Some((startDate, endDate)).asRight
    }
  }
}
