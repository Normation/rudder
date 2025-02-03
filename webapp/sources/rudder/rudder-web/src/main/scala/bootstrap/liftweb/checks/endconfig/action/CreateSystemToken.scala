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

package bootstrap.liftweb.checks.endconfig.action

import better.files.File
import bootstrap.liftweb.BootstrapChecks
import bootstrap.liftweb.BootstrapLogger
import com.normation.errors.Inconsistency
import com.normation.errors.IOResult
import com.normation.rudder.api.ApiToken
import com.normation.zio.UnsafeRun
import java.nio.file.attribute.PosixFilePermissions
import scala.jdk.CollectionConverters.*
import zio.syntax.*

/**
 * Create an API token file at webapp startup to use for internal use.
 */
class CreateSystemToken(systemToken: Option[ApiToken], runDir: File, apiTokenHeaderName: String) extends BootstrapChecks {
  import CreateSystemToken.*

  override val description = "Create system API token files"

  override def checks(): Unit = {
    (for {
      value  <- systemToken.map(_.value).getOrElse("").strip() match {
                  case s if (s.length < tokenMinSize) =>
                    Inconsistency(s"Error: system token can't be less than ${tokenMinSize} long").fail
                  case s                              => s.succeed
                }
      token  <- restrictedPermissionsWrite(runDir / tokenFile, value)
      // Allows easier usage in scripts, and in particular prevents making the token value visible in
      // process list by using the "--header @file" syntax in curl to read the file.
      header <- restrictedPermissionsWrite(runDir / tokenHeaderFile, curlTokenHeader(value))
      _      <- BootstrapLogger.info(s"System API token files created in ${token.pathAsString} and ${header.pathAsString}")
    } yield ())
      .chainError(s"An error occurred while creating system API token files in ${runDir.pathAsString}")
      .tapError(err => BootstrapLogger.error(err.fullMsg))
      .runNow
  }

  private def restrictedPermissionsWrite(file: File, content: String): IOResult[File] = {
    for {
      f   <- withSystemTokenPermissions(file)
      res <- IOResult.attempt(f.writeText(content))
    } yield {
      res
    }
  }

  private def curlTokenHeader(token: String): String = {
    s"${apiTokenHeaderName}: ${token}"
  }
}

object CreateSystemToken {
  // minimum size of the system API token to allow to write it
  val tokenMinSize:    Int    = 10
  val tokenFile:       String = "api-token"
  val tokenHeaderFile: String = "api-token-header"

  def withSystemTokenPermissions(file: File): IOResult[File] = {
    IOResult.attempt(s"Could not chmod 600 '${file.pathAsString}'")(
      file.createIfNotExists().setPermissions(PosixFilePermissions.fromString("rw-------").asScala.toSet)
    )
  }
}
