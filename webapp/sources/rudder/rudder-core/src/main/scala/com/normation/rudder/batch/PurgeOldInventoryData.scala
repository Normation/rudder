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
import com.normation.rudder.services.nodes.history.impl.InventoryHistoryJdbcRepository
import com.normation.utils.CronParser.*
import com.normation.zio.*
import cron4s.CronExpr
import java.time.Instant
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import zio.*

/**
 * A scheduler which deletes old inventory data:
 * - files under /var/rudder/inventories/{received, failed}
 * - old accept/refuse inventories
 * It uses a cron-like config parsed by cron4s.
 */
class PurgeOldInventoryData(
    optCron:           Option[CronExpr],
    maxAge:            Duration,
    cleanDirectories:  List[File],
    inventoryHistory:  InventoryHistoryJdbcRepository,
    deleteLogAccepted: Duration,
    deleteLogDeleted:  Duration
) {

  val logger = InventoryProcessingLogger

  // check is a file is older than max age (true) or not. Only true for regular file.
  // maxAge in seconds.
  def isOlderThanMaxAge(f: File, maxAge: Long, now: Instant): IOResult[Boolean] = {
    IOResult.attempt(f.isRegularFile && f.attributes().creationTime().toInstant.isBefore(now.minusSeconds(maxAge)))
  }

  // must not fail, will be in a cron
  val cleanOldFiles: UIO[Unit] = for {
    now <- Clock.instant
    _   <- ZIO
             .foreachDiscard(cleanDirectories) { dir =>
               ZIO.foreachDiscard(dir.list.to(Iterable)) { f =>
                 isOlderThanMaxAge(f, maxAge.toSeconds, now).flatMap { older =>
                   if (older) {
                     InventoryProcessingLogger.info(
                       s"Deleting inventory file '${f.pathAsString}' (older than ${maxAge.toString})"
                     ) *>
                     IOResult
                       .attempt(f.delete())
                       .unit
                       .catchAll(err => {
                         InventoryProcessingLogger
                           .error(s"Error while trying to clean old inventory file '${f.pathAsString}': ${err.fullMsg}")
                       })
                   } else {
                     InventoryProcessingLogger.trace(
                       s"Keeping inventory file '${f.pathAsString}' (younger than ${maxAge.toString})"
                     )
                   }
                 }
               }
             }
             .catchAll(err => logger.error(s"Error when cleaning-up old inventory files: ${err.fullMsg}"))

    after <- Clock.instant
    _     <- logger.debug(s"Cleaned-up old inventory files in ${Duration.fromInterval(now, after).toString}")
  } yield ()

  // the part for inventory data in jdbc
  val cleanHistoricalInventories: ZIO[Any, Nothing, Unit] = {
    val now            = DateTime.now(DateTimeZone.UTC)
    val deleteAccepted = (for {
      ids <- inventoryHistory.deleteFactCreatedBefore(now.minus(deleteLogAccepted.toMillis))
      _   <- InventoryProcessingLogger.info(
               s"Deleted historical pending inventory information of nodes: '${ids.map(_.value).mkString("', '")}'"
             )
    } yield ()).catchAll { err =>
      InventoryProcessingLogger.error(
        s"Error when deleting historical pending inventory information for nodes: ${err.fullMsg}"
      )
    }

    val deleteDeleted = (for {
      ids <- inventoryHistory.deleteFactIfDeleteEventBefore(now.minus(deleteLogDeleted.toMillis))
      _   <- InventoryProcessingLogger
               .info(
                 s"Deleted historical pending inventory information of nodes: '${ids.map(_.value).mkString("', '")}'"
               )
               .unless(ids.isEmpty)
    } yield ()).catchAll { err =>
      InventoryProcessingLogger.error(
        s"Error when deleting historical pending inventory information for deleted nodes: ${err.fullMsg}"
      )
    }

    for {
      // delete facts for refused / deleted nodes
      _ <- deleteDeleted
      // delete facts for accepted nodes only if interval value is > 0 ('0' means 'keeps forever')
      _ <- ZIO.when(deleteLogAccepted.toSeconds != 0)(deleteAccepted)
    } yield ()
  }

  // create the schedule cron or nothing if disabled.
  // Must not fail.
  val prog: URIO[Any, Unit] = optCron match {
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
      (cleanOldFiles *> cleanHistoricalInventories).schedule(schedule).unit
  }

  // start cron
  def start(): Fiber.Runtime[Nothing, Unit] = {
    ZioRuntime.unsafeRun(prog.forkDaemon)
  }
}
