/*
 *************************************************************************************
 * Copyright 2011 Normation SAS
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
package com.normation.rudder.services.servers

import better.files.File
import better.files.File.Attributes
import com.normation.errors.RudderError
import com.normation.zio.UnsafeRun
import java.nio.file.attribute.PosixFilePermissions
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class TestInstanceIdService extends Specification {

  "InstanceIdService" should {
    val uuid: String = "00000000-0000-0000-0000-000000000000"

    val instanceIdGenerator = new InstanceIdGenerator {
      val newInstanceId: InstanceId = InstanceId(uuid)
    }

    "make instance" in {
      "with non-existing file without creating the file" in {
        val file = File("/tmp/rudder-test-instance-id-not-exists")
        InstanceIdService.make(file, instanceIdGenerator).either.runNow must beRight(
          beLike[InstanceIdService](_.instanceId must beEqualTo(instanceIdGenerator.newInstanceId))
        )
        file.exists must beFalse
      }

      "existing file" in File.temporaryFile("rudder-test-instance-id-", "", None, Attributes.default) { tmpFile =>
        tmpFile.write(uuid)
        (InstanceIdService.make(tmpFile, null).either.runNow must beRight(
          beLike[InstanceIdService](_.instanceId must beEqualTo(instanceIdGenerator.newInstanceId))
        )) and (
          tmpFile.contentAsString must beEqualTo(uuid)
        )
      }

      "non-readable file" in File.temporaryFile(
        "rudder-test-instance-id-",
        "",
        None,
        List(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("---------")))
      ) { tmpFile =>
        InstanceIdService
          .make(tmpFile, null)
          .either
          .runNow must beLeft(
          like[RudderError](_.fullMsg must startWith(s"Could not use file ${tmpFile.pathAsString} to read instance ID"))
        )

      }
    }
  }
}
