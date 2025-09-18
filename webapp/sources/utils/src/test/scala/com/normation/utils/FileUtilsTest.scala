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
import better.files.File.root
import com.normation.errors.SecurityError
import com.normation.utils.FileUtils.*
import com.normation.zio.ZioRuntime
import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class FileUtilsTest extends Specification {
  import FileError.*

  // Setup temporary test files
  val tmp:      File = File(s"/tmp/rudder-test-shared-files/${DateTime.now.toString(ISODateTimeFormat.dateTime())}")
  tmp.createDirectoryIfNotExists(createParents = true)
  val folder1:  File = tmp / "folder1"
  folder1.createDirectoryIfNotExists()
  val file1:    File = folder1 / "file1"
  file1.createFile()
  val file2:    File = tmp / "file2"
  file2.createFile()
  val symlink1: File = tmp / "symlink1"
  symlink1.symbolicLinkTo(File("/etc"))

  // we want to allow when both jail dir is a symlinked dir and target actually remains in it
  val folder2: File = tmp / "folder2"
  folder2.symbolicLinkTo(folder1)
  val file3:   File = folder2 / "file3"
  file3.createFile()

  "sanitize valid path" >> {
    val tail = "/file2"
    val res  = ZioRuntime.unsafeRun(sanitizePath(tmp, tail).either)
    res.map(_.pathAsString) must beRight(beEqualTo(tmp.toString() + "/file2"))
  }

  "sanitize valid path with .." >> {
    val tail = "/dir/../file2"
    val res  = ZioRuntime.unsafeRun(sanitizePath(tmp, tail).either)
    res.map(_.pathAsString) must beRight(beEqualTo(tmp.toString() + "/file2"))
  }

  "sanitize a split path" >> {
    val tail = "/dir" :: ".." :: "file2" :: Nil
    val res  = ZioRuntime.unsafeRun(sanitizePath(tmp, tail).either)
    res.map(_.pathAsString) must beRight(beEqualTo(tmp.toString() + "/file2"))
  }

  "sanitize non-existing valid path" >> {
    val tail = "/file42"
    val res  = ZioRuntime.unsafeRun(sanitizePath(tmp, tail).either)
    res.map(_.pathAsString) must beRight(beEqualTo(tmp.toString() + "/file42"))
  }

  "sanitize does prevent for traversal" >> {
    val tail = "/../../.."
    val res  = ZioRuntime.unsafeRun(sanitizePath(tmp, tail).either)
    (res must beLeft(beAnInstanceOf[SecurityError])) and (res must beLeft(OutsideBaseDir(None, root)))
  }

  "sanitize also prevents a split path" >> {
    val tail = ".." :: ".." :: ".." :: Nil
    val res  = ZioRuntime.unsafeRun(sanitizePath(tmp, tail).either)
    (res must beLeft(beAnInstanceOf[SecurityError])) and (res must beLeft(OutsideBaseDir(None, root)))
  }

  "sanitize does not follow symlinks in the target" >> {
    val tail = "/symlink1"
    val res  = ZioRuntime.unsafeRun(sanitizePath(tmp, tail).either)
    (res must beLeft(beAnInstanceOf[SecurityError])) and (res must beLeft(OutsideBaseDir(Some("symlink1"), root / "etc")))
  }

  "sanitize resolve jail dir and correctly check existing real path" >> {
    val tail = "/file1"
    val res  = ZioRuntime.unsafeRun(sanitizePath(folder2, tail).either)
    res.map(_.pathAsString) must beRight(beEqualTo(tmp.toString + "/folder1/file1"))
  }

  "sanitize resolve jail dir and correctly allow a file in created in it" >> {
    val tail = "/file3"
    val res  = ZioRuntime.unsafeRun(sanitizePath(folder2, tail).either)
    res.map(_.pathAsString) must beRight(beEqualTo(tmp.toString + "/folder1/file3"))
  }
}
