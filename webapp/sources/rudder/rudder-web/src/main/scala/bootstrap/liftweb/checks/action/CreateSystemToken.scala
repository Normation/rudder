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

package bootstrap.liftweb.checks.action

import better.files.File
import better.files.File.OpenOptions
import better.files.File.apply
import bootstrap.liftweb.BootstrapChecks
import bootstrap.liftweb.BootstrapLogger
import com.normation.rudder.api.ApiAccount
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import net.liftweb.common.Box
import net.liftweb.common.EmptyBox
import net.liftweb.common.Full
import net.liftweb.util.ControlHelpers.tryo

/**
 * Create an API token file at webapp startup to use for internal use.
 */
class CreateSystemToken(systemAccount: ApiAccount, runDir: Path) extends BootstrapChecks {

  override val description = "Create system API token files"

  def tokenHeader(): String = {
    s"X-API-Token: ${systemAccount.token.value}"
  }

  private def restrictedPermissionsWrite(path: File, content: String) = {
    for {
      perms       <- PosixFilePermissions.fromString("rw-------")
      openOptions <- Seq(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
      // attr <-
      file        <- path.writeText(content)
    } yield {
      file
    }
  }

  override def checks(): Unit = {
    val tokenFile       = "api-token"
    // Allows easier usage in scripts, and in particular prevents making the token value visible in
    // process list by using the "--header @file" syntax in curl to read the file.
    val headerTokenFile = "api-token-header"

    (for {
      file <- restrictedPermissionsWrite(runDir / tokenFile, systemAccount.token.value)
      file <- restrictedPermissionsWrite(runDir / headerTokenFile, tokenHeader())
    } yield {}) match {
      case Full(_) =>
        BootstrapLogger.logEffect.info(s"System API token file created in ${tokenPath}")
      case eb: EmptyBox =>
        val fail = eb ?~! s"An error occurred while creating system API token file in ${tokenPath}"
        BootstrapLogger.logEffect.error(fail.messageChain)
    }

  }

}
