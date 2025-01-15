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
package com.normation.plugins

import com.normation.plugins.RudderPackagePlugin.AbiVersion
import com.normation.utils.ParseVersion
import org.joda.time.DateTime
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class PluginSettingTest extends Specification {

  "PluginManagementError" should {
    implicit val rudderVersion:    String     = "8.3.0"
    // a valid one is the rudder version, override implicit in needed tests cases
    implicit val pluginAbiVersion: AbiVersion =
      AbiVersion(ParseVersion.parse(rudderVersion).getOrElse(throw new Exception("bad version in test")))

    "list errors from rudder package plugin" in {

      "plugin without license" in {
        val plugin = fakePlugin(
          requiresLicense = false,
          license = None
        )

        val errors = PluginManagementError.fromRudderPackagePlugin(plugin)
        errors must beEmpty
      }

      "plugin with expired license" in {
        val plugin = fakePlugin(
          requiresLicense = false,
          license = Some(
            RudderPackagePlugin.LicenseInfo(
              DateTime.now.minusMonths(2),
              DateTime.now.minusMonths(1)
            )
          )
        )

        val errors = PluginManagementError.fromRudderPackagePlugin(plugin)
        errors must containTheSameElementsAs(
          List(
            PluginManagementError.LicenseExpiredError
          )
        )
      }

      "plugin with license near expiration" in {
        val plugin = fakePlugin(
          requiresLicense = false,
          license = Some(
            RudderPackagePlugin.LicenseInfo(
              DateTime.now.minusMonths(2),
              DateTime.now.plusDays(1)
            )
          )
        )

        val errors = PluginManagementError.fromRudderPackagePlugin(plugin)
        errors must containTheSameElementsAs(
          List(
            PluginManagementError.LicenseNearExpirationError
          )
        )
      }

      "plugin with ABI version error" in {
        val plugin = fakePlugin(
          requiresLicense = false,
          license = None
        )

        val errors = PluginManagementError.fromRudderPackagePlugin(plugin)(
          rudderVersion,
          AbiVersion(ParseVersion.parse("9.9.9").getOrElse(throw new Exception("bad version in test")))
        )
        errors must containTheSameElementsAs(
          List(
            PluginManagementError.RudderAbiVersionError(rudderVersion)
          )
        )
      }

      "plugin missing needed license" in {
        val plugin = fakePlugin(
          requiresLicense = true,
          license = None
        )

        val errors = PluginManagementError.fromRudderPackagePlugin(plugin)
        errors must containTheSameElementsAs(
          List(
            PluginManagementError.LicenseNeededError
          )
        )
      }

      "plugin with multiple errors" in {
        val plugin = fakePlugin(
          requiresLicense = true,
          license = None
        )

        val errors = PluginManagementError.fromRudderPackagePlugin(plugin)(
          rudderVersion,
          AbiVersion(ParseVersion.parse("9.9.9").getOrElse(throw new Exception("bad version in test")))
        )
        errors must containTheSameElementsAs(
          List(
            PluginManagementError.LicenseNeededError,
            PluginManagementError.RudderAbiVersionError(rudderVersion)
          )
        )
      }
    }
  }

  private def fakePlugin(
      requiresLicense: Boolean,
      license:         Option[RudderPackagePlugin.LicenseInfo]
  ) = RudderPackagePlugin(
    "test",
    version = None,
    latestVersion = None,
    installed = true,
    enabled = true,
    webappPlugin = true,
    description = "Test plugin",
    requiresLicense = requiresLicense,
    license = license
  )
}
