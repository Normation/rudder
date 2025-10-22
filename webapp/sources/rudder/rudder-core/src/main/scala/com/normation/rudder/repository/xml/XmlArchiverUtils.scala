/*
 *************************************************************************************
 * Copyright 2011 Normation SAS
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

package com.normation.rudder.repository.xml

import com.normation.errors.*
import com.normation.rudder.domain.logger.GitArchiveLogger
import java.io.File
import java.nio.charset.Charset
import java.nio.file.attribute.PosixFilePermission
import scala.xml.Elem

trait XmlArchiverUtils {

  object GET {
    def apply(reason: Option[String]): String = reason match {
      case None    => ""
      case Some(m) => "\n\nReason provided by user:\n" + m
    }
  }

  def xmlPrettyPrinter: RudderPrettyPrinter

  /*
   * Group owner for XML files and directories created in /var/rudder/configuration-repository/
   */
  def groupOwner: String
  def encoding:   String

  /**
   * Write the given Elem (prettified) into given file, log the message.
   * File are written with the following rights:
   * - for directories, rwxrwx---
   * - for files: rw-rw----
   * - for all, group owner: rudder
   * Perms are defined in /opt/rudder/bin/rudder-fix-repository-configuration
   */
  def writeXml(fileName: File, elem: Elem, logMessage: String): IOResult[File] = {
    import better.files.*

    import java.nio.file.StandardOpenOption.*
    import java.nio.file.attribute.PosixFilePermission.*
    val filePerms      = Set[PosixFilePermission](OWNER_READ, OWNER_WRITE, /* no exec, */ GROUP_READ, GROUP_WRITE /* no exec, */ )
    val directoryPerms = Set[PosixFilePermission](OWNER_READ, OWNER_WRITE, OWNER_EXECUTE, GROUP_READ, GROUP_WRITE, GROUP_EXECUTE)

    // an utility that write text in a file and create file parents if needed
    // open file mode for create or overwrite mode
    IOResult.attempt {
      val file = fileName.toScala
      file.parent.createDirectoryIfNotExists(true).setPermissions(directoryPerms).setGroup(groupOwner)
      file
        .writeText(xmlPrettyPrinter.format(elem))(using Seq(WRITE, TRUNCATE_EXISTING, CREATE), Charset.forName(encoding))
        .setPermissions(filePerms)
        .setGroup(groupOwner)
      GitArchiveLogger.debug(logMessage)
      fileName
    }
  }
}
