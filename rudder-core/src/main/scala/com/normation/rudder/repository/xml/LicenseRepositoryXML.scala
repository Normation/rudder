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

import scala.collection._
import com.normation.rudder.repository.LicenseRepository
import com.normation.rudder.domain.licenses.NovaLicense
import org.xml.sax.SAXParseException
import org.slf4j.{Logger,LoggerFactory}
import org.apache.commons.io.FileUtils
import java.io.File
import scala.xml._
import com.normation.rudder.exceptions._
import java.io.FileNotFoundException

class LicenseRepositoryXML(licenseFile : String) extends LicenseRepository {

  val logger = LoggerFactory.getLogger(classOf[LicenseRepositoryXML])
  
  val licenseMap = mutable.Map[String, NovaLicense]()
  
    
  def findLicense(uuid: String): Option[NovaLicense] = { 
    licenseMap.get(uuid)
  }

  def getAllLicense(): Seq[NovaLicense] = {
    licenseMap.map(x => x._2).toSeq
  }

    def addLicense(license: NovaLicense): Option[NovaLicense] = { 
      logger.debug("Adding a license {}", license)
      licenseMap.put(license.uuid, license)
      saveLicenseFile()
      findLicense(license.uuid)
    }
    

  def loadLicenses(): Unit = {  
    licenseMap.clear
      
    logger.debug("Loading document {}", licenseFile)
    val doc = loadLicenseFile()
    
    for (elt <- (doc \\"licenses" \ "license")) {
      logger.debug("Loading License")
      val license = NovaLicense.parseXml(elt)
      licenseMap.put(license.uuid, license)
    }
  }

    /**
   * Load the license file
   * @return The xml element representation of the file
   */
  
  private def loadLicenseFile() : Elem = {
    val doc = 
      try {
        XML.loadFile(licenseFile)
      } catch {
        case e:SAXParseException => logger.error("Cannot parse license file"); throw new ParsingException("Unexpected issue (unvalid xml?) with the config file " )
        case e : java.net.MalformedURLException =>  logger.error("Cannot read license file {}" + licenseFile); throw new FileNotFoundException("License file not found : " + licenseFile )
      }

    if(doc.isEmpty) {
      logger.error("Empty license file : {} ", licenseFile)
      throw new ParsingException("Empty license file " )
    }

    doc
  }
  
  
  private def saveLicenseFile() = {
    FileUtils.writeStringToFile(new File(licenseFile), this.toXml.toString)
  }
  
  
  def toXml = 
    <licenses> 
      {licenseMap.iterator.map( license => license._2 .toXml)} 
    </licenses>
}