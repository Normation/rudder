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
import java.util.regex.Pattern
import com.normation.utils.UuidRegex
import scala.collection.JavaConversions._
import com.normation.rudder.services.marshalling.ConfigurationRuleUnserialisation
import scala.xml.XML
import scala.xml.Elem
import org.xml.sax.SAXParseException
import com.normation.cfclerk.exceptions.ParsingException
import java.io.InputStream
import java.io.FileInputStream
import com.normation.utils.XmlUtils
import com.normation.rudder.repository.GitConfigurationRuleArchiver
import com.normation.rudder.domain.policies.ConfigurationRuleId
import com.normation.utils.Utils
import com.normation.rudder.domain.policies.ConfigurationRule

class GitConfigurationRuleArchiverImpl(
    gitRepo                         : GitRepositoryProvider
  , gitRootDirectory                : File
  , configurationRuleSerialisation  : ConfigurationRuleSerialisation
  , configurationRuleRootDir        : String //relative path !
  , xmlPrettyPrinter                : PrettyPrinter
  , encoding                        : String = "UTF-8"
) extends GitConfigurationRuleArchiver with Loggable {

  override lazy val getRootDirectory : File = { Utils.createDirectory(new File(gitRootDirectory, configurationRuleRootDir)) match {
    case Full(dir) => dir
    case eb:EmptyBox =>
      val e = eb ?~! "Error when checking required directories to archive items:"
      logger.error(e.messageChain)
      throw new TechnicalException(e.messageChain)
  } }

  private[this] def newCrFile(crId:ConfigurationRuleId) = new File(getRootDirectory, crId.value + ".xml")
  private[this] def getCrGitPath(crId:ConfigurationRuleId) = newCrFile(crId).getPath.replace(gitRootDirectory.getPath +"/","")
  
  def archiveConfigurationRule(cr:ConfigurationRule, gitCommitCr:Boolean = true) : Box[File] = {
    val crFile = newCrFile(cr.id)
      
    for {   
      archive <- tryo { 
                   FileUtils.writeStringToFile(
                       crFile
                     , xmlPrettyPrinter.format(configurationRuleSerialisation.serialise(cr))
                     , encoding
                   )
                   logger.debug("Archived Configuration rule: " + crFile.getPath)
                   crFile
                 }
      commit  <- if(gitCommitCr) {
                    val git = new Git(gitRepo.db)
                    val archiveId = ArchiveId((DateTime.now()).toString(ISODateTimeFormat.dateTime))
                    val crGitPath = getCrGitPath(cr.id)
                    tryo {
                      git.add.addFilepattern(crGitPath).call
                      val status = git.status.call
                      if(status.getAdded.contains(crGitPath)||status.getChanged.contains(crGitPath)) {
                        git.commit.setMessage("Archive configuration rule with ID '%s' on %s ".format(cr.id.value,archiveId.value)).call
                        archiveId
                      } else throw new Exception("Auto-archive git failure: not found in git added files: " + crGitPath)
                    }
                 } else {
                   Full("ok")
                 }
    } yield {
      archive
    }
  }

  def commitConfigurationRules() : Box[String] = {
    val git = new Git(gitRepo.db)
    val archiveId = ArchiveId((DateTime.now()).toString(ISODateTimeFormat.dateTime))

    tryo {
      //remove existing and add modified
      git.add.setUpdate(true).addFilepattern(configurationRuleRootDir).call
      //also add new one
      git.add.addFilepattern(configurationRuleRootDir).call
      git.commit.setMessage("Archive configuration rules on %s ".format(archiveId.value)).call.name
    }
  }
  
  def deleteConfigurationRule(crId:ConfigurationRuleId, gitCommitCr:Boolean = true) : Box[File] = {
    val crFile = newCrFile(crId)
    if(crFile.exists) {
      for {
        deleted  <- tryo { FileUtils.forceDelete(crFile) }
        commited <- if(gitCommitCr) {
                      val git = new Git(gitRepo.db)
                      val archiveId = ArchiveId((DateTime.now()).toString(ISODateTimeFormat.dateTime))
                      val crGitPath = getCrGitPath(crId)
                      tryo {
                        git.rm.addFilepattern(crGitPath).call
                        val status = git.status.call
                        if(status.getRemoved.contains(crGitPath)) {
                          git.commit.setMessage("Delete archive of configuration rule with ID '%s' on %s ".format(crId.value,archiveId.value)).call
                          archiveId
                        } else throw new Exception("Auto-archive git failure: not found in git removed files: " + crGitPath)
                      }
                    } else {
                      Full("OK")
                    }
      } yield {
        crFile
      }
    } else {
      Full(crFile)
    }
  }
  
}

class ItemArchiveManagerImpl(
    configurationRuleRepository     : ConfigurationRuleRepository
  , configurationRuleUnserialisation: ConfigurationRuleUnserialisation
  , gitConfigurationRuleArchiver    : GitConfigurationRuleArchiver
) extends ItemArchiveManager with Loggable {

  private[this] val prettyPrinter = new PrettyPrinter(120, 2)
  
  ///// implementation /////
  
  def saveAll(includeSystem:Boolean = false): Box[ArchiveId] = { 
    for {
      crs         <- configurationRuleRepository.getAll(false)
      cleanedRoot <- tryo { FileUtils.cleanDirectory(gitConfigurationRuleArchiver.getRootDirectory) }
      saved       <- sequence(crs) { cr => 
                       gitConfigurationRuleArchiver.archiveConfigurationRule(cr,false)
                     }
      commitId    <- gitConfigurationRuleArchiver.commitConfigurationRules
    } yield {
      ArchiveId(commitId)
    }
  }
  
  
  def importLastArchive(includeSystem:Boolean = false) : Box[Unit] = {
    
    for {
      files <- tryo { FileUtils.listFiles(gitConfigurationRuleArchiver.getRootDirectory,null,false).filter { f => isXmlUuid(f.getName) } }
      xmls  <- sequence(files.toSeq) { file =>
                 XmlUtils.parseXml(new FileInputStream(file), Some(file.getPath))
               }
      crs   <- sequence(xmls) { xml =>
                 configurationRuleUnserialisation.unserialise(xml)
               }
      swap  <- configurationRuleRepository.swapConfigurationRules(crs)
    } yield {
      //try to clean
      configurationRuleRepository.deleteSavedCr(swap) match {
        case eb:EmptyBox =>
          val e = eb ?~! ("Error when trying to delete saved archive of old cr: " + swap)
          logger.error(e)
        case _ => //ok
      }
      crs
    }
  }
  
  ///// utility methods /////
    
  private[this] val xmlUuidPattern = Pattern.compile(UuidRegex.stringPattern + ".xml")
  private[this] def isXmlUuid(candidate:String) = xmlUuidPattern.matcher(candidate).matches

}