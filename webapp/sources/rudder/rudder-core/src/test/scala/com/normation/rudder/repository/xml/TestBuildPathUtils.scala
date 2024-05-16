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

package com.normation.rudder.repository.xml

import com.normation.errors.Inconsistency
import com.normation.zio.UnsafeRun
import java.io.File
import java.nio.file.Files
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class TestCategoryPathUtils extends Specification with BuildCategoryPathName[String] {

  override val getItemDirectory: File = Files.createTempDirectory("rudder-test-").toFile()

  override def getCategoryName(categoryId: String): String = categoryId

  val buildCategoryPathName = (parent: String) => newCategoryDirectory("foo", List(parent)).runNow

  "BuildCategoryPathName" should {
    val fooDir = new File(getItemDirectory, "foo").getAbsolutePath
    "build a path with a single category" in {
      val path = buildCategoryPathName("foo")
      path.getAbsolutePath must beEqualTo(fooDir)
    }

    "build a path with a single category with a trailing slash" in {
      val path = buildCategoryPathName("foo/")
      path.getAbsolutePath must beEqualTo(fooDir)
    }

    "build a path with a single category with a leading slash" in {
      val path = buildCategoryPathName("/foo")
      path.getAbsolutePath must beEqualTo(fooDir)
    }

    "throw an exception when building an illegal path outside of the root directory" in {
      newCategoryDirectory("../foo", List("some-parent")).either.runNow must beEqualTo(
        Left(
          Inconsistency(
            s"Error when checking required directories '${getItemDirectory}/../foo' to archive in gitRepo.git: relative path must not allow access to parent directories, " +
            s"only to directories under directory ${getItemDirectory}"
          )
        )
      )
    }
  }
}

@RunWith(classOf[JUnitRunner])
class TestBuildFilePathsUtils extends Specification with BuildFilePaths[String] {

  val tmpDir = Files.createTempDirectory("rudder-test-").toFile()

  override val getItemDirectory: File = tmpDir

  override def getFileName(name: String): String = name + ".txt"

  "BuildPath" should {
    "build a path with a name" in {
      newFile("foo") must beRight(new File(getItemDirectory, "foo.txt"))
    }

    "build a path with an illegal path outside of the root directory" in {
      newFile("../foo") must beLeft(
        Inconsistency(
          s"Error when checking required directories '${getItemDirectory}/../foo.txt' to archive in gitRepo.git: relative path must not allow access to parent directories, " +
          s"only to directories under directory ${getItemDirectory}"
        )
      )
    }

  }
}
