/*
*************************************************************************************
* Copyright 2017 Normation SAS
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

package com.normation.plugins.aix

import com.normation.license._
import java.io.InputStreamReader
import org.joda.time.DateTime
import com.normation.rudder.domain.logger.PluginLogger
import com.normation.plugins.PluginStatus
import com.normation.plugins.PluginStatusInfo
import com.normation.plugins.PluginLicenseInfo


/*
 * This template file will processed at build time to choose
 * the correct immplementation to use for the interface.
 * The default implementation is to always enable status.
 *
 * The class will be loaded by ServiceLoader, it needs an empty constructor.
 */

final class CheckRudderPluginAixEnableImpl() extends PluginStatus {
  // here are processed variables
  val CLASSPATH_KEYFILE = "${plugin-resource-publickey}"
  val FS_SIGNED_LICENSE = "${plugin-resource-license}"
  val VERSION           = "${plugin-declared-version}"


  val maybeLicense = LicenseReader.readLicense(FS_SIGNED_LICENSE)

  // for now, we only read license info at load, because it's time consuming
  val maybeInfo = {
    for {
      unchecked <- maybeLicense
      publicKey <- {
                     val key = this.getClass.getClassLoader.getResourceAsStream(CLASSPATH_KEYFILE)
                     if(key == null) {
                       Left(LicenseError.IO(s"The classpath resources '${CLASSPATH_KEYFILE}' was not found"))
                     } else {
                       RSAKeyManagement.readPKCS8PublicKey(new InputStreamReader(key), None) //don't give to much info about path
                     }
                   }
      checked   <- LicenseChecker.checkSignature(unchecked, publicKey)
      version   <- Version.from(VERSION) match {
                     case None    => Left(LicenseError.Parsing(s"Version is not valid: '${VERSION}'."))
                     case Some(v) => Right(v)
                   }
    } yield {
      (checked, version)
    }
  }

  //log at that point is we read the license information for the plugin
  maybeInfo.fold( error => PluginLogger.error(error) , ok =>  PluginLogger.info("Plugin DSC has a license and the license signature is valid.") )

  def current: PluginStatusInfo = {
    (for {
      info               <- maybeInfo
      (license, version) = info
      check              <- LicenseChecker.checkLicense(license, DateTime.now, version)
    } yield {
      check
    }) match {
      case Right(x) => PluginStatusInfo.EnabledWithLicense(licenseInformation(x))
      case Left (y) => PluginStatusInfo.Disabled(y.msg, maybeLicense.toOption.map(licenseInformation))
    }
  }

  private[this] def licenseInformation(l: License): PluginLicenseInfo = {
    PluginLicenseInfo(
        licensee   = l.content.licensee.value
      , softwareId = l.content.softwareId.value
      , minVersion = l.content.minVersion.value.toString
      , maxVersion = l.content.maxVersion.value.toString
      , startDate  = l.content.startDate.value
      , endDate    = l.content.endDate.value
    )
  }
}
