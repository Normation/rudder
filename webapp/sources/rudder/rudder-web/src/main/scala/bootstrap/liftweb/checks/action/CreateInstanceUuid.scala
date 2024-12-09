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

package bootstrap.liftweb.checks.action

import better.files.File
import bootstrap.liftweb.BootstrapChecks
import bootstrap.liftweb.BootstrapLogger
import com.normation.errors.IOResult
import com.normation.errors.PureResult
import com.normation.utils.StringUuidGenerator
import com.normation.zio.UnsafeRun
import java.util.UUID
import zio.ZIO

class CreateInstanceUuid(file: File, uuidGen: StringUuidGenerator) extends BootstrapChecks {

  private val filePath = file.pathAsString

  override def description: String = "Check if server has instance UUID, if not, create it"

  override def checks(): Unit = {
    (for {
      content <- getOrCreateFile()
      _       <- ZIO.unless(isValidUUID(content)) {
                   writeFile(uuidGen.newUuid)
                 }
    } yield ())
      .chainError(s"An error occured when creating instance UUID in ${filePath} for server")
      .tapError(err => BootstrapLogger.error(err.fullMsg))
      .runNow
  }

  private def getOrCreateFile(): IOResult[String] = {
    IOResult.attempt("Could not create or access file")(file.createIfNotExists().contentAsString)
  }

  private def writeFile(uuid: String): IOResult[Unit] = {
    IOResult.attempt("Could not write to file")(file.write(uuid))
  }

  /**
    * Valid UUID is a parsable one
    */
  private def isValidUUID(content: String): Boolean = {
    PureResult.attempt(UUID.fromString(content)).isRight
  }
}
