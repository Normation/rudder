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

import java.io.InputStream
import org.eclipse.jgit.lib.{Constants => JConstants}
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.filter.PathFilter
import org.eclipse.jgit.treewalk.filter.TreeFilter
import org.eclipse.jgit.treewalk.TreeWalk
import com.normation.utils.HashcodeCaching
import net.liftweb.common._
import com.normation.utils.ZipUtils
import java.io.File
import java.io.ByteArrayOutputStream


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
  def listFiles(db:Repository, revTreeId:ObjectId, rootDirectories:List[String], endPaths: List[String]) : Set[String] = {
      //a first walk to find categories
      val tw = new TreeWalk(db)
      tw.setFilter(new FileTreeFilter(rootDirectories, endPaths))
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
  def getFileContent[T](db:Repository, revTreeId:ObjectId, path:String)(useIt : InputStream => Box[T]) : Box[T] = {
    var is : InputStream = null

    val filePath = {
      var p = path
      while (path.endsWith("/")) p = p.substring(0, p.length - 1)
      p
    }
    
    try {
        //now, the treeWalk
        val tw = new TreeWalk(db)
        
        if(filePath.size > 0) tw.setFilter(PathFilter.create(filePath))
        
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
  
  /**
   * Retrieve the commit tree from a path name.
   * The path may be any one of {@code org.eclipse.jgit.lib.Repository#resolve}
   */
  def findRevTreeFromRevString(db:Repository, revString:String) : Box[ObjectId] = {
    val tree = db.resolve(revString)
    if (null == tree) {
      Failure("The reference branch '%s' is not found in the Active Techniques Library's git repository".format(revString))
    } else {
      val rw = new RevWalk(db)
      val id = rw.parseTree(tree).getId
      rw.dispose
      Full(id)
    }
  }
  
  
 /**
  * Get a zip file containing files for commit "revTreeId". 
  * You can filter files only some directory by giving 
  * a root path. 
  */
  def getZip(db:Repository, revTreeId:ObjectId, onlyUnderPaths: List[String] = Nil) : Box[Array[Byte]] = {
    import scala.collection.mutable.{Set,Buffer}
    import com.normation.utils.ZipUtils.Zippable

    
    val directories = scala.collection.mutable.Set[String]()
    
    val zipEntries = scala.collection.mutable.Buffer[Zippable]()
    try {
      val tw = new TreeWalk(db)
      //create a filter with a OR of all filters
      tw.setFilter(new FileTreeFilter(onlyUnderPaths, Nil))
      tw.setRecursive(true)
      tw.reset(revTreeId)
  
      while(tw.next) {
        val path = tw.getPathString
        directories += (new File(path)).getParent
        zipEntries += Zippable(path, Some(GitFindUtils.getFileContent(db,revTreeId,path) _))
      }
       
      //start by creating all directories, then all content
      val all = directories.map(p => Zippable(p, None)).toSeq ++ zipEntries
      val out = new ByteArrayOutputStream()
      
      ZipUtils.zip(out, all)
      Full(out.toByteArray())
    } catch {
      case e:Exception => Failure("Error when creating a zip from files in commit with id: '%s'".format(revTreeId))
    }
  }  
}

/**
 * A Git filter that allows to find files in a tree, optionally looking only
 * for file under given paths, and optionally looking only for files with a 
 * given end of paths (for example, a given extension name)
 * Empty path or extension are ignored (they are removed from the list)
 */
class FileTreeFilter(rootDirectories:List[String], endPaths: List[String]) extends TreeFilter {
  
  //paths must not end with "/"
  
  private[this] val startRawPaths = rootDirectories.filter( _ != "").map { p =>
    val pp = p.reverse.dropWhile( _ == '/' ).reverse
    JConstants.encode(pp)
  }
  
  private[this] val endRawPaths = endPaths.filter( _ != "").map { e =>
    JConstants.encode(e)
  }

  /**
   * create the comparator that filter for given paths. An empty list is
   * an accept ALL filter (no filtering). 
   */
  private[this] def pathComparator(walker:TreeWalk, paths:List[Array[Byte]], test: Array[Byte] => Boolean) = {
    paths.size == 0 || paths.exists(p => test(p))
  }
  
  
  override def include(walker:TreeWalk) : Boolean = {
    //in an authorized path
    pathComparator(walker, startRawPaths, x => walker.isPathPrefix(x,x.size) == 0) &&
    (
      walker.isSubtree || 
      pathComparator(walker, endRawPaths, x =>  walker.isPathSuffix(x,x.size) )
    )
  }

  override val shouldBeRecursive = true
  override def clone = this
  override lazy val toString = {
    val start = rootDirectories match {
      case Nil => ""
      case l   => l.mkString("(", ",", ")/")
    }
    val end = endPaths match {
      case Nil => ""
      case l   => l.mkString("(", ",", ")")
    }
  
    start + ".*/" + end
  }
}