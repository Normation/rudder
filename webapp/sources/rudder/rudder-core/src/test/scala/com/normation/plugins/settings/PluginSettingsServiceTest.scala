/*
 *************************************************************************************
 * Copyright 2021 Normation SAS
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

package com.normation.plugins.settings

import better.files.File
import better.files.Resource
import com.normation.errors.IOResult
import com.normation.errors.Unexpected
import com.normation.zio.UnsafeRun
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import org.specs2.specification.core.AsExecution
import scala.annotation.nowarn
import zio.Ref
import zio.syntax.*

@RunWith(classOf[JUnitRunner])
class PluginSettingsServiceTest extends Specification {

  "FilePluginSettingsService" should {
    "read default settings" in withPluginSettingsService(
      "plugins/rudder-pkg-default.conf",
      false.succeed,
      null
    ) { service =>
      service.readPluginSettings().runNow must beEqualTo(
        PluginSettings(Some("https://download.rudder.io/plugins"), Some("username"), Some("password"), None, None, None)
      )
    }

    "read empty settings" in withPluginSettingsService("plugins/rudder-pkg-empty.conf", false.succeed, null) { service =>
      service.readPluginSettings().runNow must beEqualTo(
        PluginSettings(None, None, None, None, None, None)
      )
    }

    "checkIsSetup returns true when read is true" in withPluginSettingsService(
      "plugins/rudder-pkg-default.conf",
      true.succeed,
      null
    )(service => service.checkIsSetup().runNow must beTrue)

    val writeRef = Ref.make(false).runNow
    "checkIsSetup calls write when read is false and setting is defined" in withPluginSettingsService(
      "plugins/rudder-pkg-example.conf",
      false.succeed,
      b => writeRef.set(b)
    )(service => {
      (service.checkIsSetup().runNow.aka("the checkIsSetup return value") must beTrue) and (writeRef.get.runNow
        .aka("the written config value") must beTrue)
    })

    "checkIsSetup returns false when read is true but setting is empty" in withPluginSettingsService(
      "plugins/rudder-pkg-empty.conf",
      false.succeed,
      _ => Unexpected("this should not be called").fail
    )(service => {
      (service.checkIsSetup().runNow must beFalse)
    })

    "checkIsSetup returns false when read is true but setting is default" in withPluginSettingsService(
      "plugins/rudder-pkg-default.conf",
      false.succeed,
      _ => Unexpected("this should not be called").fail
    )(service => {
      (service.checkIsSetup().runNow must beFalse)
    })
  }

  @nowarn
  private def withPluginSettingsService[A: AsExecution](
      resourceName: String,
      read:         IOResult[Boolean],
      write:        Boolean => IOResult[Unit]
  )(
      block:        PluginSettingsService => A
  ): A = {
    val tmpDir   = File.temporaryDirectory("rudder-tests-plugin-settings-")
    val resource = Resource.getAsStream(resourceName).readAllBytes()
    val withFile =
      tmpDir.map(f => (f / "file.conf").createIfNotExists().writeByteArray(resource))
    withFile { f =>
      block {
        new FilePluginSettingsService(
          f,
          read,
          write
        )
      }
    }
  }
}
