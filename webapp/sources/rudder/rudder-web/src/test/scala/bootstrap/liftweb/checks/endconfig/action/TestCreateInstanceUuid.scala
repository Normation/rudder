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
package bootstrap.liftweb.checks.endconfig.action

import better.files.File
import com.normation.rudder.services.servers.InstanceId
import com.normation.rudder.services.servers.InstanceIdService
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class TestCreateInstanceUuid extends Specification {

  "When creating instance ID, we" should {
    val uuid: String = "00000000-0000-0000-0000-000000000000"
    val instanceIdService = new InstanceIdService(InstanceId(uuid))

    "initialize file if it does not exist" in File.temporaryDirectory("rudder-test-instance-id-") { tmpDir =>
      val file  = tmpDir / "instance-id"
      val check = new CreateInstanceUuid(file, instanceIdService)
      file.exists must beFalse
      check.checks()

      file.contentAsString must beEqualTo(uuid)
    }

    "check for existing ID and not overwrite it" in File.temporaryDirectory("rudder-test-instance-id-") { tmpDir =>
      val file  = tmpDir / "instance-id"
      file.write(uuid)
      val check = new CreateInstanceUuid(file, instanceIdService)
      check.checks()

      file.contentAsString must beEqualTo(uuid)
    }

    "check for existing ID and not overwrite it even for a different value" in File.temporaryDirectory(
      "rudder-test-instance-id-"
    ) { tmpDir =>
      val file      = tmpDir / "instance-id"
      val otherUuid = "11111111-1111-1111-1111-111111111111"
      file.write(otherUuid)
      val check     = new CreateInstanceUuid(file, instanceIdService)
      check.checks()

      file.contentAsString must beEqualTo(otherUuid)
    }
  }

}
