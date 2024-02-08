/*
 *************************************************************************************
 * Copyright 2023 Normation SAS
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

package com.normation.rudder.rest

import better.files._
import com.normation.rudder.rest.internal.SharedFilesAPI._
import com.normation.zio.ZioRuntime
import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat
import org.junit.runner._
import org.specs2.mutable._
import org.specs2.runner._

@RunWith(classOf[JUnitRunner])
class SharedFilesApiTest extends Specification {

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

  "sanitize valid path" >> {
    val tail = "/file2"
    val res  = ZioRuntime.unsafeRun(sanitizePath(tail, tmp))
    res.toString() must beEqualTo(tmp.toString() + "/file2")
  }

  "sanitize valid path with .." >> {
    val tail = "/dir/../file2"
    val res  = ZioRuntime.unsafeRun(sanitizePath(tail, tmp))
    res.toString() must beEqualTo(tmp.toString() + "/file2")
  }

  "sanitize non-existing valid path" >> {
    val tail = "/file42"
    val res  = ZioRuntime.unsafeRun(sanitizePath(tail, tmp))
    res.toString() must beEqualTo(tmp.toString() + "/file42")
  }

  "sanitize does prevent for traversal" >> {
    val tail = "/../../.."
    val res  = ZioRuntime.unsafeRun(sanitizePath(tail, tmp).isFailure)
    res must beTrue
  }

  "sanitize does not follow symlinks" >> {
    val tail = "/symlink1"
    val res  = ZioRuntime.unsafeRun(sanitizePath(tail, tmp).isFailure)
    res must beTrue
  }
}
