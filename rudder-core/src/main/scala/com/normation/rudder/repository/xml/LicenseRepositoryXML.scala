/*
*************************************************************************************
* Copyright 2011 Normation SAS
*************************************************************************************
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Affero General Public License as
* published by the Free Software Foundation, either version 3 of the
* License, or (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU Affero GPL v3, the copyright holders add the following
* Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU Affero GPL v3
* licence, when you create a Related Module, this Related Module is
* not considered as a part of the work and may be distributed under the
* license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Affero General Public License for more details.
*
* You should have received a copy of the GNU Affero General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/agpl.html>.
*
*************************************************************************************
*/

package com.normation.rudder.repository.xml

import java.io.FileNotFoundException
import scala.xml.Elem
import scala.xml.XML
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.licenses.NovaLicense
import com.normation.rudder.exceptions.ParsingException
import com.normation.rudder.repository.LicenseRepository
import org.xml.sax.SAXParseException
import net.liftweb.common.Box
import net.liftweb.common.Empty
import net.liftweb.common.Failure
import net.liftweb.common.Full
import net.liftweb.common.Loggable

class LicenseRepositoryXML(licenseFile : String) extends LicenseRepository with Loggable {

  override def getAllLicense(): Box[Map[NodeId, NovaLicense]] = {
    logger.debug(s"Loading document ${licenseFile}")
    try {
      val doc = loadLicenseFile()

      val licenses = (for {
        elt <- (doc \\"licenses" \ "license")
      } yield {
        NovaLicense.parseXml(elt)
      })
      Full(licenses.map(x => (x.uuid, x)).toMap)
    } catch {
      case ex: Exception => Failure(s"Failed to load licenses from file: ${licenseFile}", Full(ex), Empty)
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
        logger.error("Cannot parse license file")
        throw new ParsingException("Unexpected issue (unvalid xml?) with the config file " )
      case e : java.net.MalformedURLException =>
        logger.error(s"Cannot read license file ${licenseFile}")
        throw new FileNotFoundException("License file not found : " + licenseFile )
      case e : java.io.FileNotFoundException =>
        logger.debug(s"License file ${licenseFile} not found, this may be a problem if using the Windows Plugin")
        <licenses/>
    }
  }
}
