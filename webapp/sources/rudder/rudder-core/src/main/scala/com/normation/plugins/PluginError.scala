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

import Ordering.Implicits.*
import com.normation.plugins.cli.*
import com.normation.utils.ParseVersion
import com.normation.utils.PartType
import com.normation.utils.Version
import enumeratum.*
import enumeratum.EnumEntry.*
import java.time.ZonedDateTime

/**
 * An error enumeration to identify plugin management errors and the associated messages
 */
sealed trait PluginError {
  def kind:       PluginError.Kind
  def displayMsg: String
}
object PluginError       {
  sealed trait Kind extends EnumEntry with Dotcase
  object Kind       extends Enum[Kind] {
    case object LicenseNeededError         extends Kind
    case object LicenseExpiredError        extends Kind
    case object LicenseNearExpirationError extends Kind
    case object AbiVersionError            extends Kind
    case object WebappLicenseError         extends Kind
    override def values: IndexedSeq[Kind] = findValues
  }

  case object LicenseNeededError extends PluginError {
    override def kind:       Kind.LicenseNeededError.type = Kind.LicenseNeededError
    override def displayMsg: String                       = "A license is needed for the plugin"
  }

  /**
   * Sum type for license expiration error with case disjunction
   */
  sealed trait LicenseExpirationError                                                 extends PluginError
  case class LicenseExpiredError(expirationDate: ZonedDateTime)                       extends LicenseExpirationError {
    override def kind:       Kind.LicenseExpiredError.type = Kind.LicenseExpiredError
    override def displayMsg: String                        = s"Plugin license has expired on ${expirationDate.toLocalDate().toString()}"
  }
  case class LicenseNearExpirationError(daysLeft: Int, expirationDate: ZonedDateTime) extends LicenseExpirationError {
    override def kind:       Kind.LicenseNearExpirationError.type = Kind.LicenseNearExpirationError
    override def displayMsg: String                               =
      s"Plugin license near expiration (${daysLeft} days left until ${expirationDate.toLocalDate().toString()})"
  }

  final case class RudderAbiVersionError(rudderFullVersion: String) extends PluginError {
    override def kind:       Kind.AbiVersionError.type = Kind.AbiVersionError
    override def displayMsg: String                    =
      s"This plugin was not built for current Rudder ABI version ${rudderFullVersion}. You should update it to avoid code incompatibilities."
  }

  // An error from Webapp license check on runtime plugin (not from rudder-package)
  final case class RudderLicenseError(reason: String) extends PluginError {
    override def kind:       Kind.WebappLicenseError.type = Kind.WebappLicenseError
    override def displayMsg: String                       =
      s"Plugin license error leads to disabling it in the webapp: ${reason}"
  }

  def fromRudderPackagePlugin(
      plugin: RudderPackagePlugin
  )(implicit rudderFullVersion: String, abiVersion: AbiVersion): List[PluginError] = {
    List(
      validateAbiVersion(rudderFullVersion, abiVersion),
      validateLicenseNeeded(plugin.requiresLicense, plugin.license),
      plugin.license.flatMap(l => validateLicenseExpiration(l.endDate))
    ).flatten
  }

  def fromRudderLicensedPlugin(
      rudderFullVersion: Version,
      abiVersion:        AbiVersion,
      license:           PluginLicense
  ): List[PluginError] = {
    List(
      validateAbiVersion(rudderFullVersion.toVersionStringNoEpoch, abiVersion),
      validateLicenseExpiration(license.endDate)
    ).flatten
  }

  def fromRudderDisabledPlugin(
      rudderFullVersion: Version,
      abiVersion:        AbiVersion,
      reason:            String,
      details:           Option[PluginLicense]
  ): List[PluginError] = {
    validateAbiVersion(rudderFullVersion.toVersionStringNoEpoch, abiVersion).toList
      .appended(
        details
          .flatMap(lic => validateLicenseExpiration(lic.endDate))
          .getOrElse(RudderLicenseError(reason))
      )
  }

  private def validateAbiVersion(
      rudderFullVersion: String,
      abiVersion:        AbiVersion
  ): Option[RudderAbiVersionError] = {
    val isError = ParseVersion.parse(rudderFullVersion) match {
      // Rudder full version should be the same, wihout the SNAPSHOT part type
      case Right(value) if (abiVersion.value.equiv(value.copy(parts = value.parts.collect {
            case v if !v.value.isInstanceOf[PartType.Snapshot] => v
          }))) =>
        false
      case _ =>
        // compare on string
        rudderFullVersion != abiVersion.value.toVersionString
    }
    if (isError) Some(RudderAbiVersionError(rudderFullVersion))
    else None
  }

  private def validateLicenseNeeded(
      requiresLicense: Boolean,
      license:         Option[RudderPackagePlugin.LicenseInfo]
  ): Option[LicenseNeededError.type] = {
    if (requiresLicense && license.isEmpty)
      Some(LicenseNeededError)
    else
      None
  }

  /**
   * license near expiration : 1 month before now.
   */
  private def validateLicenseExpiration(endDate: ZonedDateTime): Option[LicenseExpirationError] = {
    val now = ZonedDateTime.now()
    if (endDate.isBefore(now)) {
      Some(LicenseExpiredError(endDate))
    } else if (endDate.minusMonths(1).isBefore(now)) {
      val daysLeft = java.time.Duration.between(now, endDate).toDays.toInt
      Some(LicenseNearExpirationError(daysLeft, endDate))
    } else {
      None
    }
  }

}
