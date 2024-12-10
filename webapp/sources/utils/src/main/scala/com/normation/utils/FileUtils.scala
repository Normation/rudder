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

package com.normation.utils

import better.files.File
import com.normation.errors.IOResult
import com.normation.errors.RudderError
import scala.annotation.tailrec

object FileUtils {

  sealed trait FileAccessError extends RudderError
  object FileAccessError {
    case class OutsideBaseDir(filePath: File, realPath: File) extends FileAccessError {
      def msg: String = s"Unauthorized access to file ${filePath.name} (real path: ${realPath})"
    }
  }

  def sanitizePath(baseFolder: File, child: String): IOResult[Either[FileAccessError, File]] = {
    sanitizePath(baseFolder, List(child))
  }

  def sanitizePath(baseFolder: File, path: List[String]): IOResult[Either[FileAccessError, File]] = {
    import FileAccessError.*

    @tailrec def recPath(file: File, children: List[String]): File = {
      // Actually canonifies the path
      children match {
        case Nil           =>
          file
        case child :: next =>
          recPath(file / child.dropWhile(_.equals('/')), next)
      }
    }
    val filePath = recPath(baseFolder, path)
    // We also want to resolve symlinks before checking, let's resort to Java's `toRealPath`
    for {
      fileExists <- IOResult.attempt(filePath.exists())
      realPath    = if (fileExists) File(filePath.toJava.toPath.toRealPath()) else filePath
    } yield {
      if (baseFolder.contains(realPath, strict = false)) { // `false` means we allow access to the base directory itself
        Right(realPath)
      } else {
        Left(OutsideBaseDir(filePath, realPath))
      }
    }
  }
}
