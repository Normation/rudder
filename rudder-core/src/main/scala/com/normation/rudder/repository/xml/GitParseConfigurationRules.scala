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

import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.revwalk.RevTag

import com.normation.cfclerk.services.GitRepositoryProvider
import com.normation.cfclerk.services.GitRevisionProvider
import com.normation.rudder.domain.policies.ConfigurationRule
import com.normation.rudder.repository.ParseConfigurationRules
import com.normation.rudder.services.marshalling.ConfigurationRuleUnserialisation
import com.normation.utils.Control._
import com.normation.utils.UuidRegex
import com.normation.utils.XmlUtils

import net.liftweb.common.Box
import net.liftweb.common.Loggable

class GitParseConfigurationRules(
    configurationRuleUnserialisation: ConfigurationRuleUnserialisation
  , revisionProvider                : GitRevisionProvider
  , repo                            : GitRepositoryProvider
  , configurationRulesRootDirectory : String //relative name to git root file
) extends ParseConfigurationRules with Loggable {
  
  
  
  /**
   * Parse the group library. 
   * The structure is purely recursive:
   * - a directory must contains a category.xml or is ignored
   * - a directory with a category.xml may contain UUID.xml files and sub-directories (ignore other files)
   */
  def getLastArchive : Box[Seq[ConfigurationRule]] = {
    getArchiveForRevTreeId(revisionProvider.getAvailableRevTreeId)
  }
  
  def getArchive(archiveId:RevTag) = {
    for {
      treeId  <- GitFindUtils.findRevTreeFromPath(repo.db, archiveId.getName)
      archive <- getArchiveForRevTreeId(treeId)
    } yield {
      archive
    }
  }

  private[this] def getArchiveForRevTreeId(revTreeId:ObjectId) = {
    
    val root = {
      val p = configurationRulesRootDirectory.trim
      if(p.size == 0) ""
      else if(p.endsWith("/")) p.substring(0, p.size-1)
      else p
    }
    
    val directoryPath = root + "/"

    //// BE CAREFUL: GIT DOES NOT LIST DIRECTORIES
    val paths = GitFindUtils.listFiles(repo.db, revTreeId, Some(root), Some(".xml")).filter { p =>
                   p.size > directoryPath.size &&
                   p.startsWith(directoryPath) &&
                   p.endsWith(".xml") &&
                   UuidRegex.isValid(p.substring(directoryPath.size,p.size - 4))
                 }

    
    for {
      xmls    <- sequence(paths.toSeq) { crPath =>
                   GitFindUtils.getFileContent(repo.db, revTreeId, crPath){ inputStream =>
                     XmlUtils.parseXml(inputStream, Some(crPath))
                   }
                 }
      crs     <- sequence(xmls) { xml =>
                   configurationRuleUnserialisation.unserialise(xml)
                 }
    } yield {
      crs
    }
  }        
}
