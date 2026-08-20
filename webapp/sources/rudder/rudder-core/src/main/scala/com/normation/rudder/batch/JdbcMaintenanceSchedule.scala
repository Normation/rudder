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

import com.normation.rudder.domain.logger.ScheduledJobLoggerPure
import com.normation.rudder.repository.jdbc.JdbcVacuum
import com.normation.utils.CronParser.*
import com.normation.zio.*
import zio.*

/**
 * A scheduler which runs database maintenance operations (vacuum)
 * on the tables listed in [[JdbcVacuum.all]]
 */
class JdbcMaintenanceSchedule(
    vacuums:        List[JdbcVacuum],
    schedule:       Schedule[Any, Any, Any],
    scheduleString: String
) {

  private val progAction: UIO[Unit] = {
    for {
      _ <- ScheduledJobLoggerPure.debug("Starting database maintenance (vacuum)")
      _ <- ZIO.foreach(vacuums) { vacuum =>
             vacuum
               .vacuum()
               .catchAll(err => ScheduledJobLoggerPure.error(s"Error when vacuuming database table: ${err.fullMsg}"))
           }
      _ <- ScheduledJobLoggerPure.debug("Database maintenance (vacuum) completed")
    } yield ()
  }

  // create the schedule vacuum cron or nothing if disabled.
  // Must not fail.
  val prog: UIO[Unit] = {
    ScheduledJobLoggerPure.info(
      s"Automatic database maintenance (vacuum) is ${scheduleString}"
    ) *>
    progAction.schedule(schedule).unit
  }

  // start cron
  def start(): Fiber.Runtime[Nothing, Unit] = {
    ZioRuntime.unsafeRun(prog.forkDaemon)
  }
}

object JdbcMaintenanceSchedule {
  /*
   * It is assumed to run daily, and take hour and minute
   */
  def make(vacuums: List[JdbcVacuum], hour: Int, minute: Int): JdbcMaintenanceSchedule = {
    val dailyCronString            = s"0 ${minute} ${hour} * * ?"
    val cron                       = dailyCronString.toCron
    // never schedule if it fails
    val (schedule, scheduleString) = cron
      .map(c => (c.toSchedule, s"scheduled every day at hour ${hour} and minute ${minute}"))
      .getOrElse((Schedule.stop, "not scheduled"))
    new JdbcMaintenanceSchedule(vacuums, schedule, scheduleString)
  }
}
