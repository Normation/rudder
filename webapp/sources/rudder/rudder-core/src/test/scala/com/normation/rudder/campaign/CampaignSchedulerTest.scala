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

import com.normation.rudder.campaigns.DayTime
import com.normation.rudder.campaigns.First
import com.normation.rudder.campaigns.Last
import com.normation.rudder.campaigns.MainCampaignScheduler
import com.normation.rudder.campaigns.Monday
import com.normation.rudder.campaigns.MonthlySchedule
import com.normation.rudder.campaigns.Second
import com.normation.rudder.campaigns.SecondLast
import com.normation.rudder.campaigns.Sunday
import com.normation.rudder.campaigns.Third
import com.normation.zio._
import org.joda.time.DateTime
import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class CampaignSchedulerTest extends Specification {

  val now = DateTime.now()
  "A monthly campaign schedule set during december" should {

    // With bug, it was 2023/01/05 12:00 and end on 2023/01/09 18:00 instead of both date on 02/01/2023
    "Give a correct date in january for first occurrence" in {
      val s = MainCampaignScheduler
        .nextCampaignDate(
          MonthlySchedule(First, DayTime(Monday, 9, 27), DayTime(Monday, 13, 37)),
          now.withYear(2022).withMonthOfYear(12).withDayOfMonth(7)
        )
        .runNow
      s must beSome(
        (
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
      )
    }

    "Give a correct date in january for Second occurrence" in {
      val s = MainCampaignScheduler
        .nextCampaignDate(
          MonthlySchedule(Second, DayTime(Monday, 9, 27), DayTime(Monday, 13, 37)),
          now.withYear(2022).withMonthOfYear(12).withDayOfMonth(14)
        )
        .runNow
      s must beSome(
        (
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
      )
    }

    "Give a correct date in january for third occurrence" in {
      val s = MainCampaignScheduler
        .nextCampaignDate(
          MonthlySchedule(Third, DayTime(Monday, 9, 27), DayTime(Monday, 13, 37)),
          now.withYear(2022).withMonthOfYear(12).withDayOfMonth(21)
        )
        .runNow
      s must beSome(
        (
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
      )
    }

    // With bug, it was 01/01/23 instead of 25/12
    "Give a correct date in December for last occurrence" in {
      val s = MainCampaignScheduler
        .nextCampaignDate(
          MonthlySchedule(Last, DayTime(Sunday, 9, 27), DayTime(Sunday, 13, 37)),
          now.withYear(2022).withMonthOfYear(12).withDayOfMonth(7)
        )
        .runNow
      s must beSome(
        (
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
      )
    }

    // With bug, it was 25/12/22 instead of 18/12
    "Give a correct date in December for second last occurrence" in {
      val s = MainCampaignScheduler
        .nextCampaignDate(
          MonthlySchedule(SecondLast, DayTime(Sunday, 9, 27), DayTime(Sunday, 13, 37)),
          now.withYear(2022).withMonthOfYear(12).withDayOfMonth(7)
        )
        .runNow
      s must beSome(
        (
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
      )
    }
  }
}
