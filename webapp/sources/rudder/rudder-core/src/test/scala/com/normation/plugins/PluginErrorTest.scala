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

import com.normation.plugins.PluginError.RudderLicenseError
import com.normation.plugins.cli.RudderPackagePlugin
import com.normation.utils.ParseVersion
import java.time.ZonedDateTime
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class PluginErrorTest extends Specification {

  "PluginError" should {
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

        val errors = PluginError.fromRudderPackagePlugin(plugin)
        errors must beEmpty
      }

      "plugin with expired license" in {
        val expiration = ZonedDateTime.now().minusMonths(1)
        val plugin     = fakePlugin(
          requiresLicense = false,
          license = Some(
            RudderPackagePlugin.LicenseInfo(
              ZonedDateTime.now().minusMonths(2),
              expiration
            )
          )
        )

        val errors = PluginError.fromRudderPackagePlugin(plugin)
        errors must containTheSameElementsAs(
          List(
            PluginError.LicenseExpiredError(expiration)
          )
        )
      }

      "plugin with license near expiration" in {
        val now        = ZonedDateTime.now
        val expiration = now.plusDays(2)
        val plugin     = fakePlugin(
          requiresLicense = false,
          license = Some(
            RudderPackagePlugin.LicenseInfo(
              now.minusMonths(2),
              expiration
            )
          )
        )

        val errors = PluginError.fromRudderPackagePlugin(plugin)
        errors must haveLength(1)
        errors.head must beLikeA {
          case e: PluginError.LicenseNearExpirationError =>
            e.daysLeft must beGreaterThan(0)
            e.expirationDate.toLocalDate must beEqualTo(expiration.toLocalDate)
        }
      }

      "plugin with ABI version error" in {
        val plugin = fakePlugin(
          requiresLicense = false,
          license = None
        )

        val errors = PluginError.fromRudderPackagePlugin(plugin)(
          rudderVersion,
          AbiVersion(ParseVersion.parse("9.9.9").getOrElse(throw new Exception("bad version in test")))
        )
        errors must containTheSameElementsAs(
          List(
            PluginError.RudderAbiVersionError(rudderVersion)
          )
        )
      }

      "plugin with ABI version but SNAPSHOT" in {
        val plugin = fakePlugin(
          requiresLicense = false,
          license = None
        )

        val v      = s"${rudderVersion}-SNAPSHOT"
        val errors = PluginError.fromRudderPackagePlugin(plugin)(
          v,
          AbiVersion(ParseVersion.parse(v).getOrElse(throw new Exception("bad version in test")))
        )
        errors must beEmpty
      }

      "plugin missing needed license" in {
        val plugin = fakePlugin(
          requiresLicense = true,
          license = None
        )

        val errors = PluginError.fromRudderPackagePlugin(plugin)
        errors must containTheSameElementsAs(
          List(
            PluginError.LicenseNeededError
          )
        )
      }

      "plugin with multiple errors" in {
        val plugin = fakePlugin(
          requiresLicense = true,
          license = None
        )

        val errors = PluginError.fromRudderPackagePlugin(plugin)(
          rudderVersion,
          AbiVersion(ParseVersion.parse("9.9.9").getOrElse(throw new Exception("bad version in test")))
        )
        errors must containTheSameElementsAs(
          List[PluginError](
            PluginError.LicenseNeededError,
            PluginError.RudderAbiVersionError(rudderVersion)
          )
        )
      }
    }

    "list errors from rudder webapp plugin" in {

      "plugin enabled, without need for license" in {
        val now     = ZonedDateTime.now
        val license = fakeLicense(now, now.plusMonths(2))

        val errors = PluginError.fromRudderLicensedPlugin(pluginAbiVersion.value, pluginAbiVersion, license)
        errors must beEmpty
      }

      "plugin enabled, with license near expiration" in {
        val now        = ZonedDateTime.now
        val expiration = now.plusDays(2)
        val license    = fakeLicense(now.minusMonths(2), expiration)

        val errors = PluginError.fromRudderDisabledPlugin(
          pluginAbiVersion.value,
          pluginAbiVersion,
          "",
          Some(license)
        )
        errors must haveLength(1)
        errors.head must beLikeA {
          case e: PluginError.LicenseNearExpirationError =>
            e.expirationDate.toLocalDate must beEqualTo(expiration.toLocalDate)
        }
      }

      "plugin disabled, with expired license" in {
        val expiration = ZonedDateTime.now().minusMonths(1)
        val license    = fakeLicense(ZonedDateTime.now().minusMonths(2), expiration)

        val errors = PluginError.fromRudderDisabledPlugin(
          pluginAbiVersion.value,
          pluginAbiVersion,
          "",
          Some(license)
        )
        errors must containTheSameElementsAs(
          List(
            PluginError.LicenseExpiredError(expiration)
          )
        )
      }

      "plugin enabled, with ABI version error" in {
        val badAbiVersion = AbiVersion(ParseVersion.parse("9.9.9").getOrElse(throw new Exception("bad version in test")))
        val now           = ZonedDateTime.now
        val license       = fakeLicense(now, now.plusMonths(2))

        val errors = PluginError.fromRudderLicensedPlugin(
          pluginAbiVersion.value,
          badAbiVersion,
          license
        )
        errors must containTheSameElementsAs(
          List(
            PluginError.RudderAbiVersionError(rudderVersion)
          )
        )
      }

      "plugin with license error" in {
        val errors = PluginError.fromRudderDisabledPlugin(
          pluginAbiVersion.value,
          pluginAbiVersion,
          "license invalid",
          None
        )
        errors must containTheSameElementsAs(
          List(
            RudderLicenseError("license invalid")
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

  private def fakeLicense(
      startDate: ZonedDateTime,
      endDate:   ZonedDateTime
  ) = PluginLicense(
    Licensee(""),
    SoftwareId(""),
    MinVersion(""),
    MaxVersion(""),
    startDate,
    endDate,
    MaxNodes.unlimited,
    Map.empty
  )
}
