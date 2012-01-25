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

import java.io.File
import scala.xml.PrettyPrinter
import org.apache.commons.io.FileUtils
import org.eclipse.jgit.api.Git
import org.joda.time.format.ISODateTimeFormat
import org.joda.time.DateTime
import com.normation.cfclerk.domain.PolicyPackageName
import com.normation.cfclerk.domain.SectionSpec
import com.normation.cfclerk.services.GitRepositoryProvider
import com.normation.exceptions.TechnicalException
import com.normation.rudder.domain.policies.ConfigurationRule
import com.normation.rudder.domain.policies.ConfigurationRuleId
import com.normation.rudder.domain.policies.PolicyInstance
import com.normation.rudder.domain.policies.PolicyInstanceId
import com.normation.rudder.domain.policies.UserPolicyTemplate
import com.normation.rudder.domain.policies.UserPolicyTemplateCategory
import com.normation.rudder.domain.policies.UserPolicyTemplateCategoryId
import com.normation.rudder.domain.policies.UserPolicyTemplateId
import com.normation.rudder.repository.ArchiveId
import com.normation.rudder.repository.GitConfigurationRuleArchiver
import com.normation.rudder.repository.GitPolicyInstanceArchiver
import com.normation.rudder.repository.GitUserPolicyTemplateArchiver
import com.normation.rudder.repository.GitUserPolicyTemplateCategoryArchiver
import com.normation.rudder.services.marshalling.ConfigurationRuleSerialisation
import com.normation.rudder.services.marshalling.PolicyInstanceSerialisation
import com.normation.rudder.services.marshalling.UserPolicyTemplateCategorySerialisation
import com.normation.rudder.services.marshalling.UserPolicyTemplateSerialisation
import com.normation.utils.Utils
import com.normation.utils.Control.sequence
import net.liftweb.common._
import net.liftweb.util.Helpers.tryo
import com.normation.cfclerk.domain.PolicyPackage
import com.normation.cfclerk.services.PolicyPackageService
import com.normation.rudder.repository.PolicyInstanceRepository
import scala.collection.mutable.Buffer
import scala.collection.JavaConversions._
import com.normation.rudder.domain.nodes.NodeGroupCategoryId
import com.normation.rudder.repository.GitNodeGroupCategoryArchiver
import com.normation.rudder.services.marshalling.NodeGroupCategorySerialisation
import com.normation.rudder.domain.nodes.NodeGroupCategory
import com.normation.rudder.repository.GitNodeGroupArchiver
import com.normation.rudder.services.marshalling.NodeGroupSerialisation
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.nodes.NodeGroup
import scala.xml.Elem
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.filter.TreeFilter
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.{Constants => JConstants}
import java.io.InputStream
import org.eclipse.jgit.treewalk.filter.PathFilter
import java.io.FileNotFoundException
import org.eclipse.jgit.lib.ObjectId

/**
 * Utility trait to find/list/get content
 * of Git repository by commit id
 */
object GitFindUtils extends Loggable {
  
  /**
   * List of files available in the given commit. 
   * You can specify the start and/or the end of the file path in the search. 
   * For example, if you want only file under directory "foo/", where foo parent is
   * the git root, and ending by ".xml", use:
   * - rootPath = Some("foo")
   * - endPath  = Some(".xml")
   * //// BE CAREFUL: GIT DOES NOT LIST DIRECTORIES
   */
  def listFiles(db:Repository, revTreeId:ObjectId, rootDirectory:Option[String], endPath: Option[String]) : Set[String] = {
      //a first walk to find categories
      val tw = new TreeWalk(db)
      tw.setFilter(new FileTreeFilter(rootDirectory, endPath))
      tw.setRecursive(true)
      tw.reset(revTreeId)
      
      val paths = scala.collection.mutable.Set[String]()

      while(tw.next) {
        paths += tw.getPathString
      }
      
      paths.toSet
  }
  
  
  /**
   * Get the content of the file at the given path (as seen by git, so 
   * relative to git root)
   */
  def getFileContent[T](db:Repository, revTreeId:ObjectId, filePath:String)(useIt : InputStream => Box[T]) : Box[T] = {
    var is : InputStream = null
    try {
        //now, the treeWalk
        val tw = new TreeWalk(db)
        tw.setFilter(PathFilter.create(filePath))
        tw.setRecursive(true)
        tw.reset(revTreeId)
        var ids = List.empty[ObjectId]
        while(tw.next) {
          ids = tw.getObjectId(0) :: ids
        }
        ids match {
          case Nil => 
            Failure("No file were found at path '%s'".format(filePath))
          case h :: Nil =>
            useIt(db.open(h).openStream)
          case _ => 
            Failure("More than exactly one matching file were found in the git tree for path %s, I can not know which one to choose. IDs: %s".format(filePath))
      }
    } catch {
      case ex:Exception =>
        Failure("Exception caught when trying to acces file '%s'".format(filePath),Full(ex),Empty)
    } finally {
      if(null != is) {
        is.close
      }
    }    
  }

}

class FileTreeFilter(rootDirectory:Option[String], endPath: Option[String]) extends TreeFilter {
  private[this] val endRawPath = {
    endPath match {
      case None => JConstants.encode("")
      case Some(path) => JConstants.encode(path)
    }
  }
  
  private[this] val startRawPath = {
    rootDirectory match {
      case None => JConstants.encode("")
      case Some(path) => JConstants.encode(path)
    }
  }
  
  override def include(walker:TreeWalk) : Boolean = {
    (startRawPath.size == 0 || (walker.isPathPrefix(startRawPath,startRawPath.size) == 0)) && //same root
    ( walker.isSubtree || endRawPath.size == 0 || walker.isPathSuffix(endRawPath, endRawPath.size) )
  }

  override val shouldBeRecursive = true
  override def clone = this
  override lazy val toString = "[%s.*%s]".format(rootDirectory.map( _ + "/").getOrElse(""), endPath.map( "/" + _).getOrElse(""))
}