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

package com.normation.rudder.services.policies

import com.normation.rudder.domain.transporter.UpdateBatch
import net.liftweb.common._
import com.normation.rudder.domain.servers.NodeConfiguration
import org.apache.commons.io.FilenameUtils
import org.apache.commons.io.FileUtils
import java.io.File
import java.io.IOException
import com.normation.rudder.repository.LicenseRepository
import com.normation.cfclerk.domain.PromisesFinalMoveInfo

trait TemplateWriter extends Loggable {
  val licenseRepository : LicenseRepository
  def getSharesFolder() : String

  /**
   * Write the promises of all the nodes
   * @param updateBatch : the container for the server to be updated
   */
  def writePromisesForMachines(updateBatch : UpdateBatch) : Box[Seq[PromisesFinalMoveInfo]]

  /**
   * Write data specific from the roles
   */ 
  def writeSpecificsData(nodeConfiguration : NodeConfiguration, newMachineFolder:String) : Unit = {
    /*nodeConfiguration match {
        case CopyFile(x) => 
          for (fileName <- x.fileNames) {
            createSymLink(fileName, newMachineFolder)
          }
        case _ => ;
      }*/
  }
  
  
  def writeLicense(nodeConfiguration : NodeConfiguration, newMachineFolder:String) : Unit = {
    logger.debug("Writing licence for nodeConfiguration  " + nodeConfiguration.id);
    nodeConfiguration.isPolicyServer match {
      case true =>  copyLicenseFile(nodeConfiguration.id, newMachineFolder)
                
      
      case false => copyLicenseFile(nodeConfiguration.targetMinimalNodeConfig.policyServerId, newMachineFolder)
    }
  }
  
  private def copyLicenseFile(nodeConfigurationid: String, newMachineFolder:String) : Unit = {
    licenseRepository.findLicense(nodeConfigurationid) match {
      case None => throw new Exception("Could not find license file")
      case Some(license) => 
        val licenseFile = new File(license.file)
        if (licenseFile.exists) {
          val destFile = FilenameUtils.normalize(newMachineFolder + "/license.dat")
          FileUtils.copyFile(licenseFile, new File(destFile) )
        } else {
          logger.error("Could not find the license file %s for server %s".format(license.file, nodeConfigurationid))
          throw new Exception("Could not find license file " +license.file) 
        }
    }
  }
  
  
  /**
   * Create a sym link from a file to the folder of a nodeConfiguration
   * The goal is when we want to deploy a file on several nodeConfigurations, we simply create a link 
   * on the server, that will get followed
   */
  private def createSymLink(fileName :  String, newMachineFolder:String) : Unit = {
    val source = FilenameUtils.normalize(getSharesFolder() + "/" + fileName)
    val dest = FilenameUtils.normalize(newMachineFolder + "/shares/" + fileName)
    FileUtils.forceMkdir( new File(FilenameUtils.normalize(newMachineFolder + "/shares/")))
    
    val command = Array[String]("ln", "-fs", "source", "dest")

    //val exec ="ln"+" -fs "+source+" " +dest
    
    val process = Runtime.getRuntime().exec(command);

    if (process.waitFor() != 0) {
      logger.error("Couldn't successfully create the link exec")
      throw new IOException("Couldn't link file " + source)
    }
          
    logger.info("Making a link from " + source + " to " + newMachineFolder  + "/shares/" +fileName );
    
  }  
}