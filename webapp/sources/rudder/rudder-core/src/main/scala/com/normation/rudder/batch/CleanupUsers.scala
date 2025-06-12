/*
 *************************************************************************************
 * Copyright 2023 Normation SAS
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

import com.normation.errors.IOResult
import com.normation.rudder.domain.eventlog.RudderEventActor
import com.normation.rudder.domain.logger.ApplicationLoggerPure
import com.normation.rudder.users.*
import com.normation.utils.CronParser.*
import com.normation.utils.DateFormaterService
import com.normation.zio.*
import cron4s.CronExpr
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import zio.*

/**
 * A scheduler which runs user accounts and user sessions clean-up:
 * - disable/delete inactive accounts,
 * - purge old deleted accounts,
 * - delete user sessions
 *
 * It use a cron-like config parsed by cron4s.
 *
 * Code derived from https://github.com/centic9/jgit-cookbook/blob/master/src/main/java/org/dstadler/jgit/porcelain/CollectGarbage.java
 */
class CleanupUsers(
    userRepository:     UserRepository,
    getNonAdminUserIds: IOResult[List[String]],
    optCron:            Option[CronExpr],
    disableInactive:    Duration,
    deleteInactive:     Duration,
    purgeDeleted:       Duration,
    purgeSessions:      Duration,
    localBackends:      List[String]
) {

  private val logger = ApplicationLoggerPure.Auth

  /*
   * Clean-up has different steps:
   * - disable accounts inactive since `disableInactive`
   * - delete accounts inactive since `deleteInactive`
   * - purge account deleted since `purgeDeleted`
   * - purge sessions older than `purgeSessions`
   *
   * For local backend, we don't want to "delete" accounts, since they would be recreated
   * at next rudder reboot. They need to be removed from the local param (`rudder-user.xml` or `rudder-web.properties`)
   * or via the plugin and then purged like others.
   *
   */
  val cleanup: UIO[Unit] = for {
    t0   <- currentTimeMillis
    d     = new DateTime(t0, DateTimeZone.UTC)
    trace = EventTrace(RudderEventActor, d, _)
    _    <-
      // disable current users known not to be admin
      getNonAdminUserIds
        .flatMap(
          userRepository
            .disable(
              _,
              Some(d.minus(disableInactive.toMillis)),
              Nil,
              trace(s"User was inactive since last '${disableInactive.render}'")
            )
        )
        .foldZIO(
          err => logger.error(s"Error when disabling user accounts inactive since '${disableInactive.render}': ${err.fullMsg}"),
          disabledUsers => {
            ZIO
              .unless(disabledUsers.isEmpty)(
                logger.warn(
                  s"Following users status changed to 'disabled' because they were inactive since '${disableInactive.render}': '${disabledUsers
                      .mkString("', '")}'"
                )
              )
              .unit
          }
        )
    _    <- userRepository
              .delete(
                Nil,
                Some(d.minus(deleteInactive.toMillis)),
                localBackends,
                Some(UserStatus.Disabled),
                trace(s"User was inactive since last '${deleteInactive.render}")
              )
              .foldZIO(
                err => logger.error(s"Error when deleting user accounts inactive since '${deleteInactive.render}': ${err.fullMsg}"),
                deletedUsers => {
                  ZIO
                    .unless(deletedUsers.isEmpty)(
                      logger.info(s"Following users status changed from 'disabled' to 'deleted': '${deletedUsers.mkString("', '")}'")
                    )
                    .unit
                }
              )
    _    <- userRepository
              .purge(
                Nil,
                Some(d.minus(purgeDeleted.toMillis)),
                Nil,
                trace(s"User is purged definitively '${purgeDeleted.render}' after being deleted")
              )
              .foldZIO(
                err => logger.error(s"Error when purging user accounts deleted since '${purgeDeleted.render}': ${err.fullMsg}"),
                purgedUsers => {
                  ZIO
                    .unless(purgedUsers.isEmpty)(
                      logger.info(
                        s"Users were purged from the database because they were configured to be purged ${purgeDeleted.render} after deletion. Users list is : '${purgedUsers
                            .mkString("', '")}'"
                      )
                    )
                    .unit
                }
              )
    dos   = d.minus(purgeSessions.toMillis)
    _    <-
      userRepository
        .deleteOldSessions(dos) // success is already logged
        .catchAll(err => {
          logger.error(
            s"Error when purging user sessions older than '${DateFormaterService.serialize(d)}': ${err.fullMsg}"
          )
        })
    t1   <- currentTimeMillis
    _    <- logger.info(s"Cleaning user accounts and sessions performed in ${Duration.fromMillis(t1 - t0).render}")
  } yield ()

  // Must not fail.
  val prog: UIO[Unit] = optCron match {
    case None       =>
      logger.info(s"Disable automatic cleaning of user accounts and user sessions (schedule: '${DISABLED}')")
    case Some(cron) =>
      val schedule = cron.toSchedule
      logger.info(
        s"Automatic cleaning of user accounts and sessions started (schedule 'sec min h dayMonth month DayWeek': '${cron.toString}')"
      ) *>
      cleanup.schedule(schedule).unit
  }

  // start cron
  def start(): Fiber.Runtime[Nothing, Unit] = {
    ZioRuntime.unsafeRun(prog.forkDaemon)
  }
}
