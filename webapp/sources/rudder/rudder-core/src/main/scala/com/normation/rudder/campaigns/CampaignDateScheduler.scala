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
import com.normation.utils.DateFormaterService.JavaTimeToJoda
import com.normation.utils.DateFormaterService.toOffsetDateTime
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.time.temporal.TemporalAdjusters.dayOfWeekInMonth
import org.joda.time.DateTime
import scala.math.Ordered.orderingToOrdered

/*
 * This object contains the logic to compute campaign event dates.
 * It takes care of time zone and other date shiny things.
 */
object CampaignDateScheduler {

  extension (date: OffsetDateTime) {
    def adjustScheduleTimeZone(optTimeZone: Option[ScheduleTimeZone])(implicit defaultTz: ZoneId): ZonedDateTime = {
      optTimeZone.flatMap(_.toZoneId) match {
        // no schedule time zone (or invalid one) means the default one should be used
        case None             => date.atZoneSameInstant(defaultTz)
        case Some(scheduleTz) => date.atZoneSameInstant(scheduleTz)
      }
    }
  }

  extension (self: ZonedDateTime)
    def withTime(time: Time): ZonedDateTime = {
      self
        .withHour(time.realHour)
        .withMinute(time.realMinute)
        .truncatedTo(ChronoUnit.MINUTES)
    }

  private def nextDateFromDayTime(date: ZonedDateTime, start: DayTime): ZonedDateTime = {
    if (
      date.getDayOfWeek > start.day.toJavaTime
      || (date.getDayOfWeek == start.day.toJavaTime && date.getHour > start.realHour)
      || (date.getDayOfWeek == start.day.toJavaTime && date.getHour == start.realHour && date.getMinute > start.realMinute)
    ) {
      date
        .plusWeeks(1)
        .`with`(TemporalAdjusters.previousOrSame(start.day.toJavaTime))
        .withTime(start.asTime)
    } else {
      date
        .`with`(TemporalAdjusters.nextOrSame(start.day.toJavaTime))
        .withTime(start.asTime)
    }

  }

  def nextCampaignDate(
      schedule: CampaignSchedule,
      date:     DateTime
  ): PureResult[Option[(DateTime, DateTime)]] = {
    // Schedule needs to be adjusted to current server timezone, not to the one from the base schedule date
    given currentTz: ZoneId = ZoneId.systemDefault()
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
        val scheduleInitialDate = date.toOffsetDateTime.adjustScheduleTimeZone(tz)
        val startDate           = {
          if (
            scheduleInitialDate.getHour > start.realHour ||
            (scheduleInitialDate.getHour == start.realHour && scheduleInitialDate.getMinute > start.realMinute)
          ) {
            scheduleInitialDate.plusDays(1).withTime(start)
          } else {
            scheduleInitialDate.withTime(start)
          }
        }

        val endDate = {
          if (end.realHour < start.realHour || (end.realHour == start.realHour && end.realMinute < start.realMinute)) {
            startDate.plusDays(1).withTime(end)
          } else {
            startDate.withTime(end)
          }
        }

        Some((startDate.toJoda, endDate.toJoda)).asRight

      case WeeklySchedule(start, end, tz) =>
        val scheduleInitialDate = date.toOffsetDateTime.adjustScheduleTimeZone(tz)
        val startDate           = nextDateFromDayTime(scheduleInitialDate, start)
        val endDate             = nextDateFromDayTime(startDate, end)

        Some((startDate.toJoda, endDate.toJoda)).asRight

      case MonthlySchedule(position, start, end, tz) =>
        val scheduleInitialDate = date.toOffsetDateTime.adjustScheduleTimeZone(tz)

        val ordinalForDayOfWeekInMonth = position match {
          case First      => 1
          case Second     => 2
          case Third      => 3
          case Last       => -1
          case SecondLast => -2
        }

        def computeMonthStart(date: ZonedDateTime) = date
          .`with`(dayOfWeekInMonth(ordinalForDayOfWeekInMonth, start.day.toJavaTime))
          .withTime(start.asTime)

        val currentMonthStart = computeMonthStart(scheduleInitialDate)
        val startDate         = if (scheduleInitialDate.isAfter(currentMonthStart)) {
          computeMonthStart(scheduleInitialDate.plusMonths(1))
        } else {
          currentMonthStart
        }
        val endDate           = nextDateFromDayTime(startDate, end)
        Some((startDate.toJoda, endDate.toJoda)).asRight
    }
  }
}
