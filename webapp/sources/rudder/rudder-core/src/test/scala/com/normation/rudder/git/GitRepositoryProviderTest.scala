/*
 *************************************************************************************
 * Copyright 2026 Normation SAS
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
import com.normation.zio.*
import org.apache.commons.io.FileUtils
import org.eclipse.jgit.lib.ConfigConstants
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import org.specs2.specification.AfterAll

/*
 * Test disabling of multipack option by adding the corresponding git config option.
 */
@RunWith(classOf[JUnitRunner])
class GitRepositoryProviderTest extends Specification with AfterAll {

  sequential

  val testRoot: File = File("/tmp/test-git-repository-provider-" + java.lang.System.currentTimeMillis().toString)

  override def afterAll(): Unit = {
    if (java.lang.System.getProperty("tests.clean.tmp") != "false") {
      FileUtils.deleteDirectory(testRoot.toJava)
    }
  }

  def isMultiPackIndexEnabled(repo: GitRepositoryProvider): Boolean = {
    repo.db.getConfig.getBoolean(ConfigConstants.CONFIG_CORE_SECTION, ConfigConstants.CONFIG_KEY_MULTIPACKINDEX, true)
  }

  def newRepoDir(name: String): File = {
    val dir = testRoot / name
    dir.createDirectories()
    (dir / "some-file.txt").writeText("some content")
    dir
  }

  "a git repository created by Rudder" should {
    "have the multi-pack-index disabled" in {
      val dir  = newRepoDir("created")
      val repo = GitRepositoryProviderImpl.make(dir.pathAsString).runNow
      isMultiPackIndexEnabled(repo) must beFalse
    }
  }

  "an existing git repository whose multi-pack-index was (re)enabled" should {
    "get it disabled again when Rudder opens it, and persist the change" in {
      val dir = newRepoDir("existing")

      // create the repository, then put the option back to true, as a `git gc` would
      val created = GitRepositoryProviderImpl.make(dir.pathAsString).runNow
      created.db.getConfig
        .setBoolean(ConfigConstants.CONFIG_CORE_SECTION, null, ConfigConstants.CONFIG_KEY_MULTIPACKINDEX, true)
      created.db.getConfig.save()
      created.db.close()

      val reopened = GitRepositoryProviderImpl.make(dir.pathAsString).runNow

      (isMultiPackIndexEnabled(reopened) must beFalse) and
      ((dir / ".git" / "config").contentAsString must contain("multiPackIndex"))
    }
  }
}
