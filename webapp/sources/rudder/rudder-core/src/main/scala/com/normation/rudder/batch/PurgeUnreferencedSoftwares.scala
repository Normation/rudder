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


import com.normation.rudder.domain.logger.ScheduledJobLogger
import com.normation.inventory.ldap.core.SoftwareService

import scala.concurrent.duration._
import com.normation.zio._
import com.normation.errors._
import com.normation.rudder.domain.logger.ScheduledJobLoggerPure
import zio._

/**
 * A naive scheduler which checks every updateInterval if software needs to be deleted
 */
class PurgeUnreferencedSoftwares(
     softwareService : SoftwareService
   , updateInterval  : FiniteDuration
) {

  val logger = ScheduledJobLogger

  if (updateInterval < 1.hour) {
    logger.info(s"Disable automatic purge of unreferenced softwares (update interval cannot be less than 1 hour)")
  } else {
    logger.debug(s"***** starting batch that purge unreferenced softwares, every ${updateInterval.toString()} *****")
    val prog = softwareService.deleteUnreferencedSoftware().either.flatMap { _ match {
      case Right(softwares) =>
        ScheduledJobLoggerPure.info(s"Purged ${softwares.length} unreferenced softwares") *>
        ZIO.when(softwares.length > 0)  {
          ScheduledJobLoggerPure.ifDebugEnabled(ScheduledJobLoggerPure.debug(s"Purged following software: ${softwares.mkString(",")}"))
        }
      case Left(err) =>
        ScheduledJobLoggerPure.error(Chained(s"Error when deleting unreferenced softwares", err).fullMsg)
    }}

    import zio.duration.Duration.{fromScala => zduration}
    ZioRuntime.unsafeRun(prog.delay(zduration(1.hour)).repeat(ZSchedule.spaced(zduration(updateInterval))).provide(ZioRuntime.environment).fork)
  }
}

