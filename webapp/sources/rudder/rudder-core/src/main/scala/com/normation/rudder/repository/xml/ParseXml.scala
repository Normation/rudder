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

package com.normation.rudder.repository.xml

import java.io.FileNotFoundException

import com.normation.NamedZioLogger

import scala.xml.Elem
import scala.xml.XML
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.licenses.CfeEnterpriseLicense
import com.normation.rudder.exceptions.ParsingException
import com.normation.rudder.repository.LicenseRepository
import org.xml.sax.SAXParseException
import com.normation.errors._
import scalaz.zio._
import scalaz.zio.syntax._

class LicenseRepositoryXML(licenseFile : String) extends LicenseRepository with NamedZioLogger {

  override def loggerName: String = this.getClass.getName

  override def getAllLicense(): IOResult[Map[NodeId, CfeEnterpriseLicense]] = {
    logEffect.debug(s"Loading document ${licenseFile}")
    IOResult.effect(s"Failed to load licenses from file: ${licenseFile}") {
      val doc = loadLicenseFile()

      val licenses = (for {
        elt <- (doc \\"licenses" \ "license")
      } yield {
        CfeEnterpriseLicense.parseXml(elt)
      })
      licenses.map(x => (x.uuid, x)).toMap
    }
  }

  /**
   * Load the license file
   * @return The xml element representation of the file
   */
  private[this] def loadLicenseFile() : Elem = {
    try {
      XML.loadFile(licenseFile)
    } catch {
      case e : SAXParseException =>
        logEffect.error("Cannot parse license file")
        throw new ParsingException("Unexpected issue (unvalid xml?) with the config file " )
      case e : java.net.MalformedURLException =>
        logEffect.error(s"Cannot read license file ${licenseFile}")
        throw new FileNotFoundException("License file not found : " + licenseFile )
      case e : java.io.FileNotFoundException =>
        logEffect.debug(s"License file ${licenseFile} not found, this may be a problem if using the Windows Plugin")
        <licenses/>
    }
  }
}
