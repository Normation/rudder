/*
 *************************************************************************************
 * Copyright 2025 Normation SAS
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
package com.normation.rudder.git

import better.files.File
import better.files.Resource
import com.normation.errors.IOResult
import com.normation.errors.effectUioUnit
import com.normation.utils.DateFormaterService
import com.normation.zio.ZioRuntime
import org.apache.commons.io.FileUtils
import org.joda.time.DateTime
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import org.specs2.specification.BeforeAfterAll
import zio.ZIO

@RunWith(classOf[JUnitRunner])
class ZipUtilsTest extends Specification with BeforeAfterAll {

  val basePath: File = File(s"/tmp/test-rudder-zip/${DateFormaterService.gitTagFormat.print(DateTime.now())}")

  override def beforeAll(): Unit = {}

  override def afterAll(): Unit = {
    if (java.lang.System.getProperty("tests.clean.tmp") != "false") {
      FileUtils.deleteDirectory(basePath.toJava)
    }
  }

  "Check that we correctly see path traverse in entry" >> {

    ZioRuntime.unsafeRun(
      ZIO
        .acquireReleaseWith(IOResult.attempt(Resource.getAsStream("zip/ZipSlip.zip")))(is => effectUioUnit(is.close())) { is =>
          for {
            entries <- ZipUtils.getZipEntries("test", is)
            _       <- ZIO.foreach(entries) { case (e, _) => ZipUtils.checkForZipSlip(e) }
          } yield ()
        }
        .either
    ) must beLeft
  }

  /*
   * As of Java 24, java.util.zip doesn't create symlink even when the archive contains one.
   * This is good for security reason, and we want to ensure it remains like that.
   */
  "Check that symlinks are not created" >> {
    // zip dir-with-symlink.zip contains a symlink from dir-with-symlink/symfoo.txt to dir-with-symlink/foo.txt
    ZioRuntime.unsafeRun(
      ZIO
        .acquireReleaseWith(IOResult.attempt(Resource.getAsStream("zip/dir-with-symlink.zip")))(is => effectUioUnit(is.close())) {
          is =>
            for {
              entries <- ZipUtils.getZipEntries("test", is)
              _       <- ZIO.foreach(entries) {
                           case (e, b) =>
                             IOResult.attempt {
                               val x = basePath / e.getName
                               if (e.isDirectory) x.createDirectories() else x.writeByteArray(b.getOrElse(Array()))
                             }
                         }
              // check
              s       <- IOResult.attempt {
                           val s = basePath / "dir-with-symlink/symfoo.txt"
                           s.isSymbolicLink
                         }
            } yield s
        }
    ) must beFalse
  }
}
