/*
*************************************************************************************
* Copyright 2013 Normation SAS
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

import com.normation.rudder.services.marshalling.GlobalParameterUnserialisation
import com.normation.rudder.migration.XmlEntityMigration
import com.normation.cfclerk.services.GitRepositoryProvider
import com.normation.rudder.repository.ParseGlobalParameters
import com.normation.rudder.repository.GitCommitId
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.revwalk.RevTag
import com.normation.utils.Control._
import com.normation.utils.XmlUtils
import net.liftweb.common.Box
import net.liftweb.common.Loggable
import com.normation.rudder.domain.parameters.GlobalParameter

class GitParseGlobalParameters(
    paramUnserialisation    : GlobalParameterUnserialisation
  , repo                    : GitRepositoryProvider
  , xmlMigration            : XmlEntityMigration
  , parametersRootDirectory : String //relative name to git root file
) extends ParseGlobalParameters with Loggable {

  def getArchive(archiveId:GitCommitId) : Box[Seq[GlobalParameter]] = {
    for {
      treeId  <- GitFindUtils.findRevTreeFromRevString(repo.db, archiveId.value)
      archive <- getArchiveForRevTreeId(treeId)
    } yield {
      archive
    }
  }
  
  private[this] def getArchiveForRevTreeId(revTreeId:ObjectId) = {

    val root = {
      val p = parametersRootDirectory.trim
      if(p.size == 0) ""
      else if(p.endsWith("/")) p.substring(0, p.size-1)
      else p
    }

    val directoryPath = root + "/"

    //// BE CAREFUL: GIT DOES NOT LIST DIRECTORIES
    val paths = GitFindUtils.listFiles(repo.db, revTreeId, List(root), List(".xml")).filter { p =>
                   p.size > directoryPath.size &&
                   p.startsWith(directoryPath) &&
                   p.endsWith(".xml")
                 }


    for {
      xmls    <- sequence(paths.toSeq) { paramPath =>
                   GitFindUtils.getFileContent(repo.db, revTreeId, paramPath){ inputStream =>
                     XmlUtils.parseXml(inputStream, Some(paramPath))
                   }
                 }
      params  <- sequence(xmls) { xml =>
                     for {
                       paramXml <- xmlMigration.getUpToDateXml(xml)
                       param    <- paramUnserialisation.unserialise(xml)
                     } yield {
                       param
                     }
                 }
    } yield {
      params
    }
  }
}