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
package bootstrap.liftweb.checks.earlyconfig.db

import com.normation.rudder.MockUserManagement
import com.normation.utils.XmlSafe
import com.normation.zio.UnsafeRun
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import org.specs2.specification.core.AsExecution
import scala.annotation.nowarn
import scala.xml.Elem

@RunWith(classOf[JUnitRunner])
class TestCheckUsersFile extends Specification {
  sequential

  val shaLegacyFile:           String = "test-users.xml"
  val unknownHashFile:         String = "test-users-wrong-hash.xml"
  val unknownUnsafeHashesFile: String = "test-users-wrong-unsafe-hashes.xml"

  "CheckUsersFile" should {

    def haveHash(hash: String) = beEqualTo(hash) ^^ ((_: Elem).attribute("hash").get.head.text)

    def haveUnsafeHashes(unsafeHashes: Option[String]) =
      beEqualTo(unsafeHashes) ^^ ((_: Elem).attribute("unsafe-hashes").flatMap(_.headOption).map(_.text))

    "start with a bcrypt hash and unsafe-hashes" in withMigrationCtx(shaLegacyFile) {
      case (initialFile, _) =>
        initialFile() must (haveHash("bcrypt") and haveUnsafeHashes(Some("true")))
    }

    "migrate legacy hash to argon2id and remove unsafe-hashes" in withMigrationCtx(shaLegacyFile) {
      case (getFile, checkUsersFile) =>
        checkUsersFile.prog.runNow

        getFile() must (haveHash("argon2id") and haveUnsafeHashes(None))
    }

    "keep hash unchanged" in withMigrationCtx(shaLegacyFile) {
      case (getFile, checkUsersFile) =>
        checkUsersFile.prog.runNow
        getFile() must (haveHash("argon2id") and haveUnsafeHashes(None))

        // idempotent check
        checkUsersFile.prog.runNow
        getFile() must (haveHash("argon2id") and haveUnsafeHashes(None))
    }

    "migrate unknown hash to argon2id" in withMigrationCtx(unknownHashFile) {
      case (getFile, checkUsersFile) =>
        checkUsersFile.prog.runNow
        getFile() must (haveHash("argon2id") and haveUnsafeHashes(None))
    }

    "remove non-boolean unsafe-hashes" in withMigrationCtx(unknownUnsafeHashesFile) {
      case (getFile, checkUsersFile) =>
        checkUsersFile.prog.runNow
        getFile() must (haveHash("argon2id") and haveUnsafeHashes(None))
    }
  }

  @nowarn("any")
  private def withMigrationCtx[A: AsExecution](
      resourceFile: String
  )(block: (() => Elem, CheckUsersFile) => A): A = {

    val (mockUserManagementTmpDir, mockUserManagement) = MockUserManagement(resourceFile = resourceFile)
    val migration                                      = mockUserManagement.userService
    val checkUsersFile                                 = new CheckUsersFile(migration)

    val elem = () => XmlSafe.load(migration.userFile.inputStream())
    val res  = block(elem, checkUsersFile)

    mockUserManagementTmpDir.delete()
    res
  }
}
