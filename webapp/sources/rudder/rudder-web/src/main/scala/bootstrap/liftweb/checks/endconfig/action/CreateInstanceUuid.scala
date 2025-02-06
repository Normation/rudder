/*
 *************************************************************************************
 * Copyright 2024 Normation SAS
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

package bootstrap.liftweb.checks.endconfig.action

import better.files.File
import bootstrap.liftweb.BootstrapChecks
import bootstrap.liftweb.BootstrapLogger
import com.normation.errors.IOResult
import com.normation.rudder.domain.logger.ApplicationLoggerPure
import com.normation.rudder.services.servers.InstanceIdService
import com.normation.zio.UnsafeRun
import zio.ZIO

class CreateInstanceUuid(file: File, instanceIdService: InstanceIdService) extends BootstrapChecks {

  private val filePath = file.pathAsString

  override def description: String = "Check if server has the instance ID file, create it if does not exist"

  override def checks(): Unit = {
    ZIO
      .unlessZIO(IOResult.attempt("Could not access file to check if it exists")(file.exists))(
        for {
          _ <-
            IOResult.attempt("Could not create or write into file")(file.createFile().write(instanceIdService.instanceId.value))
          _ <- ApplicationLoggerPure.info(s"Server instance ID has been written into ${file.pathAsString}")
        } yield ()
      )
      .chainError(s"An error occurred when creating instance ID file at ${filePath}")
      .tapError(err => BootstrapLogger.error(err.fullMsg))
      .runNow
  }
}
