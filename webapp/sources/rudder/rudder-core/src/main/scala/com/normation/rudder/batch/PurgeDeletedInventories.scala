/*
 *************************************************************************************
 * Copyright 2017 Normation SAS
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

import com.normation.errors.Chained
import com.normation.rudder.domain.logger.ScheduledJobLogger
import com.normation.rudder.domain.logger.ScheduledJobLoggerPure
import com.normation.rudder.services.servers.PurgeDeletedNodes
import com.normation.zio.ZioRuntime
import org.joda.time.DateTime
import scala.concurrent.duration.FiniteDuration
import zio._

/**
 * A naive scheduler which checks every N days if old inventories are purged from LDAP "removed" tree.
 * If so, they will be purged.
 * This batch can be removed once the property to keep inventories in LDAP when deleted is suppressed.
 */
class PurgeDeletedInventories(
    purgeDeletedNodes: PurgeDeletedNodes,
    updateInterval:    FiniteDuration,
    TTL:               Int
) {

  val logger = ScheduledJobLogger

  if (TTL < 0) {
    logger.info(s"Disable automatic purge for delete nodes inventories (TTL cannot be negative: ${TTL})")
  } else {
    if (updateInterval < 1.hour.asScala) {
      logger.info(s"Disable automatic purge for delete nodes inventories (update interval cannot be less than 1 hour)")
    } else {
      logger.debug(
        s"***** starting batch that purge deleted inventories older than ${TTL} days, every ${updateInterval.toString()} *****"
      )
      val prog = purgeDeletedNodes
        .purgeDeletedNodesPreviousDate(DateTime.now().withTimeAtStartOfDay().minusDays(TTL))
        .either
        .flatMap(_ match {
          case Right(nodes) =>
            ScheduledJobLoggerPure.info(s"Purged ${nodes.length} inventories from the removed inventory tree") *>
            ZIO.when(nodes.length > 0) {
              ScheduledJobLoggerPure.ifDebugEnabled(
                ScheduledJobLoggerPure.debug(
                  s"Purged following inventories from the removed inventory tree: ${nodes.map(x => x.value).mkString(",")}"
                )
              )
            }
          case Left(err)    =>
            val error = Chained(s"Error when purging deleted nodes inventories older than ${TTL} days", err)
            ScheduledJobLoggerPure.error(error.fullMsg)
        })
      import zio.Duration.{fromScala => zduration}
      ZioRuntime.unsafeRun(prog.delay(zduration(updateInterval)).repeat(Schedule.spaced(zduration(updateInterval))).forkDaemon)
    }
  }
}
