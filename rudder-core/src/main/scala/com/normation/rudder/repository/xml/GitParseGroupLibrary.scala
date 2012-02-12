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

import scala.Option.option2Iterable

import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.revwalk.RevTag

import com.normation.cfclerk.services.GitRepositoryProvider
import com.normation.cfclerk.services.GitRevisionProvider
import com.normation.rudder.domain.policies.GroupTarget
import com.normation.rudder.domain.policies.PolicyInstanceTargetInfo
import com.normation.rudder.repository._
import com.normation.rudder.services.marshalling.NodeGroupCategoryUnserialisation
import com.normation.rudder.services.marshalling.NodeGroupUnserialisation
import com.normation.utils.Control._
import com.normation.utils.UuidRegex
import com.normation.utils.XmlUtils

import net.liftweb.common.Box

class GitParseGroupLibrary(
    categoryUnserialiser: NodeGroupCategoryUnserialisation
  , groupUnserialiser   : NodeGroupUnserialisation
  , repo                : GitRepositoryProvider
  , libRootDirectory    : String //relative name to git root file
  , categoryFileName    : String = "category.xml"
) extends ParseGroupLibrary {
  
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
      val p = libRootDirectory.trim
      if(p.size == 0) ""
      else if(p.endsWith("/")) p 
      else p + "/" 
    }
        
    //// BE CAREFUL: GIT DOES NOT LIST DIRECTORIES
    val paths = GitFindUtils.listFiles(repo.db, revTreeId, Some(root.substring(0, root.size-1)), None)
    
    //directoryPath must end with "/"
    def recParseDirectory(directoryPath:String) : Box[NodeGroupCategoryContent] = {
      
      val categoryPath = directoryPath + categoryFileName

      // that's the directory of an NodeGroupCategory. 
      // ignore files other than NodeGroup (UUID.xml) and directories
      // don't forget to recurse sub-categories 
      for {
        categoryXml  <- GitFindUtils.getFileContent(repo.db, revTreeId, categoryPath){ inputStream =>
                          XmlUtils.parseXml(inputStream, Some(categoryPath)) ?~! "Error when parsing file '%s' as a category".format(categoryPath)
                        }
        category     <- categoryUnserialiser.unserialise(categoryXml) ?~! "Error when unserializing category for file '%s'".format(categoryPath)
        groupFiles   =  {
                          paths.filter { p =>
                            p.size > directoryPath.size &&
                            p.startsWith(directoryPath) &&
                            p.endsWith(".xml") &&
                            UuidRegex.isValid(p.substring(directoryPath.size,p.size - 4))
                          }
                        }
        groups       <- sequence(groupFiles.toSeq) { groupPath =>
                          for {
                            groupXml <- GitFindUtils.getFileContent(repo.db, revTreeId, groupPath){ inputStream =>
                              XmlUtils.parseXml(inputStream, Some(groupPath)) ?~! "Error when parsing file '%s' as a policy instance".format(groupPath)
                            }
                            group    <-  groupUnserialiser.unserialise(groupXml) ?~! "Error when unserializing group for file '%s'".format(groupPath)
                          } yield {
                            group
                          }
                        }
        subDirs      =  {
                          //we only wants to keep paths that are non-empty directories with a uptcFileName/uptFileName in them
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
        val s = subCats.toSet
        val g = groups.toSet
        
        val cat = category.copy(
            children = s.map { _.category.id }.toList
          , items = g.map { x => 
                      PolicyInstanceTargetInfo(
                          target      = GroupTarget(x.id)
                        , name        = x.name
                        , description = x.description
                        , isActivated = x.isActivated
                        , isSystem    = x.isSystem
                      )
                    }.toList
        )
        
        NodeGroupCategoryContent(cat, s, g)
      }
    }
    
    recParseDirectory(root)
  }
}
