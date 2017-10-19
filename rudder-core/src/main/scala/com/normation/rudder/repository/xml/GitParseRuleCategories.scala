/*
*************************************************************************************
* Copyright 2013 Normation SAS
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

import org.eclipse.jgit.lib.ObjectId
import com.normation.cfclerk.services.GitRepositoryProvider
import com.normation.rudder.repository._
import com.normation.utils.Control._
import net.liftweb.common.Box
import net.liftweb.common.Loggable
import com.normation.rudder.migration.XmlEntityMigration
import com.normation.rudder.services.marshalling.RuleCategoryUnserialisation
import com.normation.rudder.rule.category.RuleCategory

class GitParseRuleCategories(
    unserialiser       : RuleCategoryUnserialisation
  , repo               : GitRepositoryProvider
  , xmlMigration       : XmlEntityMigration
  , rulesRootDirectory : String //relative name to git root file
  , categoryFileName   : String = "category.xml"
) extends ParseRuleCategories with Loggable {

  def getArchive(archiveId:GitCommitId) = {
    for {
      treeId  <- GitFindUtils.findRevTreeFromRevString(repo.db, archiveId.value)
      archive <- getArchiveForRevTreeId(treeId)
    } yield {
      archive
    }
  }

  private[this] def getArchiveForRevTreeId(revTreeId:ObjectId) = {

    val root = {
      val p = rulesRootDirectory.trim
      if(p.size == 0) ""
      else if(p.endsWith("/")) p.substring(0, p.size-1)
      else p
    }

    val directoryPath = root + "/"

    //// BE CAREFUL: GIT DOES NOT LIST DIRECTORIES
    val paths = GitFindUtils.listFiles(repo.db, revTreeId, List(root), List(".xml"))
    logger.info(paths)
    //directoryPath must end with "/"
    def recParseDirectory(directoryPath:String) : Box[RuleCategory] = {

      val categoryPath = directoryPath + categoryFileName
      // that's the directory of a RuleCategory.
      // don't forget to recurse sub-categories
      for {
        xml          <- GitFindUtils.getFileContent(repo.db, revTreeId, categoryPath){ inputStream =>
                          ParseXml(inputStream, Some(categoryPath)) ?~! s"Error when parsing file '${categoryPath}' as a category"
                        }
        categoryXml  <- xmlMigration.getUpToDateXml(xml)
        category     <- unserialiser.unserialise(categoryXml) ?~! s"Error when unserializing category for file '${categoryPath}'"
        subDirs      =  {
                          //we only wants to keep paths that are non-empty directories with a rulecategory filename (category.xml)
                          paths.flatMap { p =>
                            if(p.size > directoryPath.size && p.startsWith(directoryPath)) {
                              val split = p.substring(directoryPath.size).split("/")
                              if(split.size == 2 && (split(1) == categoryFileName) ) {
                                Some(directoryPath + split(0) + "/")
                              } else None
                            } else None
                          }
                        }
        subCats      <- sequence(subDirs.toSeq) { dir =>
                          recParseDirectory(dir)
                        }
      } yield {
        val subCategories = subCats.toList

        category.copy(
            childs = subCategories
        )

      }
    }

    recParseDirectory(directoryPath)
  }

}
