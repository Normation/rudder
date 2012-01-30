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

import com.normation.rudder.domain.nodes._
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.queries.Query
import net.liftweb.common._
import com.normation.eventlog.EventActor
import com.normation.utils.HashcodeCaching
import scala.collection.SortedMap
import com.normation.rudder.repository.UptContent
import com.normation.rudder.services.marshalling.PolicyInstanceUnserialisation
import com.normation.rudder.services.marshalling.UserPolicyTemplateUnserialisation
import com.normation.rudder.services.marshalling.UserPolicyTemplateCategoryUnserialisation
import com.normation.rudder.repository.ParsePolicyLibrary
import com.normation.utils.XmlUtils
import com.normation.rudder.repository.UptCategoryContent
import java.io.File
import java.io.FileInputStream
import com.normation.utils.UuidRegex
import com.normation.utils.Control.sequence
import com.normation.rudder.repository.NodeGroupCategoryContent
import com.normation.rudder.services.marshalling.NodeGroupUnserialisation
import com.normation.rudder.services.marshalling.NodeGroupCategoryUnserialisation
import com.normation.rudder.repository.ParseGroupLibrary
import com.normation.rudder.domain.policies.{ GroupTarget, PolicyInstanceTargetInfo }
import com.normation.rudder.repository.ArchiveId
import org.eclipse.jgit.revwalk.RevTag

class FSParseGroupLibrary(
    categoryUnserialiser: NodeGroupCategoryUnserialisation
  , groupUnserialiser   : NodeGroupUnserialisation
  , libRootDirectory    : File
  , categoryFileName    : String = "category.xml"
) extends ParseGroupLibrary {
  
  /**
   * Parse the group library. 
   * The structure is purely recursive:
   * - a directory must contains a category.xml or is ignored
   * - a directory with a category.xml may contain UUID.xml files and sub-directories (ignore other files)
   */
  def getLastArchive : Box[NodeGroupCategoryContent] = {
    //precondition: directory/category.xml exists
    def recParseDirectory(directory:File) : Box[NodeGroupCategoryContent] = {
      val categoryFile = new File(directory, categoryFileName)
      // that's the directory of an NodeGroupCategory. 
      // ignore files other than NodeGroup (UUID.xml) and directories
      // don't forget to recurse sub-categories 
      for {
        categoryXml  <- XmlUtils.parseXml(new FileInputStream(categoryFile), Some(categoryFile.getPath)) ?~! "Error when parsing file '%s' as a category".format(categoryFile.getPath)
        category     <- categoryUnserialiser.unserialise(categoryXml) ?~! "Error when unserializing category for file '%s'".format(categoryFile.getPath)
        groupFiles   =  {
                          val files = directory.listFiles
                          if(null != files) files.filter( f => 
                            f != null && 
                            f.isFile &&
                            f.getName.endsWith(".xml") &&
                            UuidRegex.isValid(f.getName.substring(0,f.getName.size - 4))).toSeq
                          else Seq()
                        }
        groups       <- sequence(groupFiles) { groupFile =>
                          for {
                            groupXml <-  XmlUtils.parseXml(new FileInputStream(groupFile), Some(groupFile.getPath)) ?~! "Error when parsing file '%s' as a policy instance".format(groupFile.getPath)
                            group    <-  groupUnserialiser.unserialise(groupXml) ?~! "Error when unserializing group for file '%s'".format(groupFile.getPath)
                          } yield {
                            group
                          }
                        }
        subDirs      =  {
                          val files = directory.listFiles
                          if(null != files) 
                            files.filter( f => f != null && 
                                          f.isDirectory && 
                                          new File(f, categoryFileName).exists //only sub-categories
                            ).toSeq
                          else Seq()
                        }
        subCats      <- sequence(subDirs) { dir =>
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
    
    val rootCategoryFile = new File(libRootDirectory, categoryFileName)
    
    if(rootCategoryFile.exists) {
      recParseDirectory(libRootDirectory)
    } else {
      Failure("Error when parsing the root directory for group library '%s'. Perhaps the '%s' file is missing in that directory, or the saved group library was not correctly exported".format(
          libRootDirectory.getPath, rootCategoryFile 
      ) )
    }
  }
  
  def getArchive(archiveId:RevTag) = getLastArchive
}
