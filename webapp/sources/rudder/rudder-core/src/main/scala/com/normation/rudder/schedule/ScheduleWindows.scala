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

import com.normation.errors.*
import com.normation.rudder.campaigns.*
import com.normation.utils.DateFormaterService.toJavaInstant
import com.normation.utils.DateFormaterService.toJodaDateTime
import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.annotation.tailrec

/*
 * An occurrence window of a schedule: the directive is allowed to run
 * on the node between start (inclusive) and end (exclusive).
 */
final case class ScheduleWindow(start: Instant, end: Instant) {
  def contains(t:   Instant): Boolean = !t.isBefore(start) && t.isBefore(end)
  def isClosedAt(t: Instant): Boolean = !end.isAfter(t)
}

/*
 * `CampaignDateScheduler.nextCampaignDate` only looks forward: given a reference date, it
 * returns the next occurrence window starting at or after it.
 * For compliance computation we need to look at a run time `t` and know:
 * - the window containing `t`, if any (the directive may run during this run or a close one),
 * - the most recent window fully closed before `t` (the last time the directive should have run).
 */
object ScheduleWindows {

  // guard against a nextCampaignDate that would not move forward (degenerate schedules)
  private val maxIterations = 100

  final case class Windows(
      lastClosed: Option[ScheduleWindow],
      current:    Option[ScheduleWindow]
  )

  def findWindows(schedule: CampaignSchedule, t: Instant): PureResult[Windows] = {
    schedule match {
      case OneShot(start, end) =>
        val w = ScheduleWindow(start.toJavaInstant, end.toJavaInstant)
        if (w.start.isBefore(w.end)) {
          if (w.isClosedAt(t)) Right(Windows(Some(w), None))
          else if (w.contains(t)) Right(Windows(None, Some(w)))
          else Right(Windows(None, None))
        } else {
          Left(
            Inconsistency(s"Cannot compute schedule windows for a one shot event whose end (${end}) is before start (${start})")
          )
        }

      case _ =>
        // a date far enough in the past so that iterating forward from it crosses at least
        // the last closed occurrence before t (one period plus margin). Margins are counted
        // in days to keep exact `Instant` arithmetic (a month is at most 31 days).
        val lookbackDays = schedule match {
          case _: Daily            => 3
          case _: WeeklySchedule   => 15
          case _: MonthlySchedule  => 40
          case s: NMonthlySchedule => s.frequency * 31 + 40
          case _: OneShot          => 0 // unreachable, handled above
        }
        val lookback     = t.minus(lookbackDays.toLong, ChronoUnit.DAYS)

        @tailrec
        def loop(from: Instant, lastClosed: Option[ScheduleWindow], remaining: Int): PureResult[Windows] = {
          if (remaining <= 0) {
            Left(Inconsistency(s"Cannot compute schedule windows at ${t} for schedule ${schedule}: no convergence"))
          } else {
            // nextCampaignDate is still a Joda-Time API: bridge at that boundary only
            CampaignDateScheduler.nextCampaignDate(schedule, from.toJodaDateTime) match {
              case Left(err)                 => Left(err)
              case Right(None)               => Right(Windows(lastClosed, None))
              case Right(Some((start, end))) =>
                val w = ScheduleWindow(start.toJavaInstant, end.toJavaInstant)
                // a window can be empty when the schedule falls in a DST gap (e.g. a 2:00-3:00
                // schedule on the day clocks jump from 2:00 to 3:00: both bounds resolve to the
                // same instant). It can never contain a run: it must be neither current nor
                // last closed (that would report a false 'missing'), just skipped.
                if (w.start == w.end) {
                  if (w.end.isAfter(from)) loop(w.end, lastClosed, remaining - 1)
                  else {
                    Left(Inconsistency(s"Cannot compute schedule windows at ${t} for schedule ${schedule}: not moving forward"))
                  }
                } else if (t.isBefore(w.start)) Right(Windows(lastClosed, None))
                else if (w.contains(t)) Right(Windows(lastClosed, Some(w)))
                else if (w.end.isAfter(from)) loop(w.end, Some(w), remaining - 1)
                else Left(Inconsistency(s"Cannot compute schedule windows at ${t} for schedule ${schedule}: not moving forward"))
            }
          }
        }

        loop(lookback, None, maxIterations)
    }
  }
}
