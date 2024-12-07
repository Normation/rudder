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
package bootstrap.liftweb.checks.action

import better.files.File
import com.normation.utils.StringUuidGenerator
import com.normation.utils.StringUuidGeneratorImpl
import org.specs2.mutable.Specification

class TestCreateInstanceUuid extends Specification {

  "When creating instance ID, we" should {
    val uuid: String = "00000000-0000-0000-0000-000000000000"
    val uuidGen = new StringUuidGenerator {
      val newUuid: String = uuid
    }

    "initialize file if it does not exist" in File.temporaryDirectory("rudder-test-instance-id-") { tmpDir =>
      val file  = tmpDir / "instance-id"
      val check = new CreateInstanceUuid(file, uuidGen)
      file.exists must beFalse
      check.checks()

      file.contentAsString must beEqualTo(uuid)
    }

    "renew ID when file content is not an UUID" in File.temporaryDirectory("rudder-test-instance-id-") { tmpDir =>
      val file  = tmpDir / "instance-id"
      file.write("root-id")
      val check = new CreateInstanceUuid(file, uuidGen)
      check.checks()

      file.contentAsString must beEqualTo(uuid)
    }

    "check for existing ID and not overwrite it" in File.temporaryDirectory("rudder-test-instance-id-") { tmpDir =>
      val file          = tmpDir / "instance-id"
      val randomUuidGen = new StringUuidGeneratorImpl()
      file.write(uuid)
      val check         = new CreateInstanceUuid(file, randomUuidGen)
      check.checks()

      file.contentAsString must beEqualTo(uuid)
    }
  }

}
