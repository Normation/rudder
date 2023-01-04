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

package com.normation.rudder.batch

import better.files.File
import com.normation.errors.IOResult
import com.normation.inventory.domain.InventoryProcessingLogger
import com.normation.utils.CronParser._
import com.normation.zio._
import cron4s.CronExpr
import java.time.Instant
import zio._
import zio.Clock
import zio.Duration._

/**
 * A scheduler which delete old inventory files under /var/rudder/inventories/{received, failed}
 * It use a cron-like config parsed by cron4s.
 */
class PurgeOldInventoryFiles(
    optCron:          Option[CronExpr],
    maxAge:           Duration,
    cleanDirectories: List[File]
) {

  val logger = InventoryProcessingLogger

  // check is a file is older than max age (true) or not. Only true for regular file.
  // maxAge in seconds.
  def isOlderThanMaxAge(f: File, maxAge: Long, now: Instant): IOResult[Boolean] = {
    IOResult.attempt(f.isRegularFile && f.attributes().creationTime().toInstant.isBefore(now.minusSeconds(maxAge)))
  }

  // must not fail, will be in a cron
  val cleanOldFiles: UIO[Unit] = for {
    t0 <- currentTimeMillis
    now = Instant.ofEpochMilli(t0)
    _  <- ZIO
            .foreach(cleanDirectories) { dir =>
              ZIO.foreach(dir.list.to(Iterable)) { f =>
                isOlderThanMaxAge(f, maxAge.toSeconds, now).flatMap { older =>
                  if (older) {
                    InventoryProcessingLogger.info(
                      s"Deleting inventory file '${f.pathAsString}' (older than ${maxAge.toString})"
                    ) *>
                    IOResult
                      .attempt(f.delete())
                      .catchAll(err =>
                        InventoryProcessingLogger.error(s"Error when trying to clean old inventory file '${f.pathAsString}'")
                      )
                  } else {
                    InventoryProcessingLogger.trace(
                      s"Keeping inventory file '${f.pathAsString}' (younger than ${maxAge.toString})"
                    )
                  }
                }
              }
            }
            .catchAll(err => logger.error(s"Error when cleaning-up old inventory files: ${err.fullMsg}"))

    t1 <- currentTimeMillis
    _  <- logger.debug(s"Cleaned-up old inventory files in ${Duration.fromMillis(t1 - t0).toString}")
  } yield ()

  // create the schedule cron or nothing if disabled.
  // Must not fail.
  val prog: URIO[Any with Clock, Unit] = optCron match {
    case None       =>
      logger.info(
        s"Disable automatic cleaning of old inventories in ${cleanDirectories.map(_.pathAsString).mkString(", ")} (schedule: '${DISABLED}')"
      )
    case Some(cron) =>
      val schedule = cron.toSchedule
      logger.info(
        s"Automatic cleaning of file older than ${maxAge.toString} starts for directories: '${cleanDirectories
            .map(_.pathAsString)
            .mkString("', '")}' (schedule 'sec min h dayMonth month DayWeek': '${cron.toString}')"
      ) *>
      cleanOldFiles.schedule(schedule).unit
  }

  // start cron
  def start() = {
    ZioRuntime.unsafeRun(prog.provide(ZioRuntime.layers).forkDaemon)
  }
}
