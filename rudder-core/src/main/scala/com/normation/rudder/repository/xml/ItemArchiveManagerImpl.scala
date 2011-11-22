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

import net.liftweb.common._
import net.liftweb.util.Helpers.tryo
import com.normation.rudder.repository.ItemArchiveManager
import com.normation.rudder.repository.ArchiveId
import com.normation.rudder.services.marshalling.ConfigurationRuleSerialisation
import com.normation.rudder.repository.ConfigurationRuleRepository
import com.normation.utils.Control._
import java.io.File
import java.io.IOException
import com.normation.exceptions.TechnicalException
import org.apache.commons.io.FileUtils
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import org.joda.time.format.ISODateTimeFormat
import com.normation.rudder.domain.policies.ConfigurationRule
import scala.xml.PrettyPrinter
import com.normation.cfclerk.services.GitRepositoryProvider
import org.eclipse.jgit.api.Git

class ItemArchiveManagerImpl(
    gitRepo                       : GitRepositoryProvider
  , gitRootDirectory              : File
  , configurationRuleRepository   : ConfigurationRuleRepository
  , configurationRuleSerialisation: ConfigurationRuleSerialisation
  , configurationRuleRootDir      : String //relative path !
  , encoding                      : String = "UTF-8"
) extends ItemArchiveManager with Loggable {

  private[this] val prettyPrinter = new PrettyPrinter(120, 2)
  
  //return the directory for path, create it if needed
  private[this] def createDirectory(relativePaht:String):Box[File] = {
    val root = new File(gitRootDirectory, relativePaht)
    try {
      if(root.exists) {
        if(root.isDirectory) {
          if(root.canWrite) {
            Full(root)
          } else Failure("The directory '%s' has no write permission, please use another directory".format(root.getPath))
        } else Failure("File at '%s' is not a directory, please change configuration".format(root.getPath))
      } else if(root.mkdirs) {
        logger.debug("Creating missing directory '%s'".format(root.getPath))
        Full(root)
      } else Failure("Directory '%s' does not exists and can not be created, please use another directory".format(root.getPath))
    } catch {
      case ioe:IOException => Failure("Exception when cheching directory '%s': '%s'".format(root.getPath,ioe.getMessage))
    }
  }
  
  ///// initialization ////
  /*
   * check that root directories exist or create them
   */
  val crRoot :: Nil = bestEffort(Seq(configurationRuleRootDir)) { path =>
    createDirectory(path)
  }.map( _.toList) match {
    case Full(list) => list
    case eb:EmptyBox =>
      val e = eb ?~! "Error when checking required directories to archive items:"
      logger.error(e.messageChain)
      throw new TechnicalException(e.messageChain)
  }
  
  
  ///// implementation /////
  
  def saveAll(): Box[ArchiveId] = { 
    
    
    for {
      crs         <- configurationRuleRepository.getAll(false)
      cleanedRoot <- tryo { FileUtils.cleanDirectory(crRoot) }
      saved       <- sequence(crs) { cr => 
                       archiveCr(cr)
                     }
      archiveId    <- commit
    } yield {
      archiveId
    }
  }
  
  
  ///// utility methods /////
  
  
  //add and commit interesting directories
  private[this] def commit() : Box[ArchiveId] = {
    val git = new Git(gitRepo.db)
    val id = ArchiveId((new DateTime()).toString(ISODateTimeFormat.time))
    
    tryo {
      git.add.addFilepattern(configurationRuleRootDir).call
      git.commit.setMessage("Archive on: " + id.value).call
      id
    }
  }
  
  // archive a cr and returned the updated file
  private[this] def archiveCr(cr:ConfigurationRule) : Box[File] = {
    val crFile = new File(crRoot, cr.id.value + ".xml")
    tryo { 
      FileUtils.writeStringToFile(
          crFile
        , prettyPrinter.format(configurationRuleSerialisation.serialise(cr))
        , encoding
      )
      logger.debug("Archived Configuration rule: " + crFile.getPath)
      crFile
    }
  }
}