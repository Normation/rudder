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

package com.normation.rudder.services.licenses

import com.normation.inventory.domain.NodeId
import net.liftweb.common._
import com.normation.rudder.exceptions.NotFoundException
import com.normation.rudder.repository.NodeConfigurationRepository
import com.normation.rudder.domain.licenses.NovaLicense
import com.normation.rudder.repository.LicenseRepository
import org.joda.time.DateTime
import java.io._
import org.apache.commons.io._
import org.slf4j.{Logger,LoggerFactory}
import com.normation.exceptions.BusinessException

class NovaLicenseServiceImpl(licenseRepository : LicenseRepository, nodeConfigurationRepository : NodeConfigurationRepository, licensesPath : String)  extends NovaLicenseService {

  val logger = LoggerFactory.getLogger(classOf[NovaLicenseServiceImpl])

  def findLicenseForNode(server: String): Option[NovaLicense] = { 
    licenseRepository.findLicense(server)
  }

  def saveLicenseFile(uuid: String, licenseNumber: Int, expirationDate: DateTime, file: String): Unit = {  
    
    val sourceFile = new File(file)
    if (!sourceFile.exists) {
      logger.error("Trying to add a non-existing license file: {}", file)
      throw new FileNotFoundException("Cannot find the license file " + file)
    }
    
    nodeConfigurationRepository.findNodeConfiguration(NodeId(uuid)) match {
      case Full(server) =>
        val destFile = FilenameUtils.normalize(licensesPath + "/" + uuid +"/" + "license.dat")
        FileUtils.copyFile(sourceFile, new File(destFile))
        val novaLicense = new NovaLicense(uuid, licenseNumber, expirationDate, destFile)
        licenseRepository.addLicense(novaLicense)
        () // unit is expected
      case e:EmptyBox => 
        val msg = "Error when trying to add a license to server with uuid %s.".format(uuid)
        logger.error(msg,e)
        throw new BusinessException(msg)
    }
  }
}