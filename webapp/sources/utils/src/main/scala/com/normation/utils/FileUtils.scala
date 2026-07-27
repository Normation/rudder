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
import com.normation.errors.SecurityError
import scala.annotation.tailrec
import zio.ZIO
import zio.syntax.*

object FileUtils {

  /**
    * Our representation of error related to file handling and I/O
    */
  sealed trait FileError

  object FileError {

    /**
      * Subcase of error for the logic of handling a file, which should be within a contained path to avoid path traversal.
      * It is an important security error.
      */
    case class OutsideBaseDir(filename: Option[String], realPath: File) extends FileError with SecurityError {
      override def msg: String = s"Unauthorized access to file ${filename.getOrElse("without name")} (real path: ${realPath})"
    }

  }

  import FileError.*

   /**
   * Resolve symlinks on the deepest *existing* ancestor of `file` with `toRealPath`, then re-append
   * (and lexically normalize) the still-non-existing trailing components.
   *
   * `toRealPath` only resolves paths that exist on disk. On a create/write path the target itself does
   * not exist yet, so a naive `file.exists()`-guarded resolution leaves a symlinked *ancestor* (e.g.
   * `<base>/link -> /etc`, then `<base>/link/newfile`) unresolved and the containment check becomes a
   * purely lexical `startsWith` that a symlink defeats. Resolving the deepest existing ancestor closes
   * that hole while still returning the real, symlink-free path for the containment test below.
   */
  private def resolveDeepestExisting(file: File): File = {
    @tailrec
    def rec(current: File, tail: List[String]): File = {
      if (current.exists) {
        // symlinks in the existing prefix are resolved by `toRealPath`; the non-existing tail is
        // re-appended and `/` normalizes away any remaining `.`/`..` lexically.
        File(tail.foldLeft(File(current.path.toRealPath()).path)((p, c) => p.resolve(c)).normalize())
      } else {
        current.parentOption match {
          case Some(parent) => rec(parent, current.name :: tail)
          case None         => File(tail.foldLeft(current.path)((p, c) => p.resolve(c)).normalize())
        }
      }
    }
    rec(file, Nil)
  }

  /**
   * Check that `file` is contained into `baseFolder` after normalization. Return the normalized File.
   */
  def checkSanitizedIsIn(baseFolder: File, file: File): IOResult[File] = {
    // We also want to resolve symlinks before checking, let's resort to Java's `toRealPath`
    for {
      baseExists   <- IOResult.attempt(baseFolder.exists())
      realBasePath  = if (baseExists) File(baseFolder.path.toRealPath()) else baseFolder
      // resolve symlinks even when the target does not exist yet, so a symlinked ancestor cannot
      // escape the jail on the create/write path (see `resolveDeepestExisting`).
      realFilePath <- IOResult.attempt(resolveDeepestExisting(file))
      // `false` means we allow access to the base directory itself
      withinBase   <- IOResult.attempt(realBasePath.contains(realFilePath, strict = false))
      _            <- ZIO.when(!withinBase)(OutsideBaseDir(file.nameOption, realFilePath).fail)
    } yield {
      realFilePath
    }
  }

  /**
   * Returned a normalized-path file and also check that it's in given `baseFolder` after path normalization
   */
  def sanitizePath(baseFolder: File, subpath: String): IOResult[File] = {
    sanitizePath(baseFolder, List(subpath))
  }

  /**
   * Returned a normalized-path file and also check that it's in given `baseFolder` after path normalization
   */
  def sanitizePath(baseFolder: File, path: List[String]): IOResult[File] = {

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
    checkSanitizedIsIn(baseFolder, filePath)
  }
}
