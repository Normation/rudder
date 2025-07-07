/*
 *************************************************************************************
 * Copyright 2011 Normation SAS
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

package com.normation.rudder.git

import com.normation.GitVersion
import com.normation.GitVersion.Revision
import com.normation.GitVersion.RevisionInfo
import com.normation.NamedZioLogger
import com.normation.box.IOManaged
import com.normation.errors.*
import com.normation.rudder.git.ZipUtils.Zippable
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.Status
import org.eclipse.jgit.lib.Constants as JConstants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectStream
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.filter.PathFilter
import org.eclipse.jgit.treewalk.filter.TreeFilter
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import zio.*
import zio.syntax.*

/**
 * Utility trait to find/list/get content
 * of Git repository by commit id
 */
object GitFindUtils extends NamedZioLogger {

  override def loggerName: String = this.getClass.getName

  /**
   * List of files available in the given commit.
   * You can specify the start and/or the end of the file path in the search.
   * For example, if you want only file under directory "foo/", where foo parent is
   * the git root, and ending by ".xml", use:
   * - rootPath = Some("foo")
   * - endPath  = Some(".xml")
   * //// BE CAREFUL: GIT DOES NOT LIST DIRECTORIES
   */
  def listFiles(
      db:              Repository,
      revTreeId:       ObjectId,
      rootDirectories: List[String],
      endPaths:        List[String]
  ): IOResult[Set[String]] = {
    IOResult.attempt {
      // a first walk to find categories
      val tw = new TreeWalk(db)
      tw.setFilter(new FileTreeFilter(rootDirectories, endPaths))
      tw.setRecursive(true)
      tw.reset(revTreeId)

      val paths = scala.collection.mutable.Set[String]()

      while (tw.next) {
        paths += tw.getPathString
      }

      paths.toSet
    }
  }

  /**
   * Get the content of the file at the given path (as seen by git, so
   * relative to git root)
   */
  def getFileContent[T](db: Repository, revTreeId: ObjectId, path: String)(useIt: InputStream => IOResult[T]): IOResult[T] = {
    ZIO.scoped(getManagedFileContent(db, revTreeId, path).flatMap(useIt))
  }

  def getManagedFileContent(db: Repository, revTreeId: ObjectId, path: String): IOManaged[ObjectStream] = {
    val filePath = {
      var p = path
      while (path.endsWith("/")) p = p.substring(0, p.length - 1)
      p
    }

    IOManaged.makeM(IOResult.attemptZIO(s"Exception caught when trying to access file '${filePath}'") {
      // now, the treeWalk
      val tw = new TreeWalk(db)

      if (filePath.size > 0) tw.setFilter(PathFilter.create(filePath))

      tw.setRecursive(true)
      tw.reset(revTreeId)
      var ids = List.empty[ObjectId]
      while (tw.next) {
        ids = tw.getObjectId(0) :: ids
      }
      ids match {
        case Nil      =>
          Inconsistency(s"No file were found at path '${filePath}}'").fail
        case h :: Nil =>
          IOResult.attempt(db.open(h).openStream())
        case _        =>
          Inconsistency(
            s"More than exactly one matching file were found in the git tree for path '${filePath}', I can not know which one to choose. IDs: ${ids}}"
          ).fail
      }
    })(s => effectUioUnit(s.close()))
  }

  /**
   * Retrieve revisions for the given path
   */
  def findRevFromPath(git: Git, path: String): IOResult[Iterable[RevisionInfo]] = {
    import scala.jdk.CollectionConverters.*
    IOResult.attemptZIO(s"Error when looking for revisions changes in '${path}'") {
      ZIO.foreach(git.log().addPath(path).call().asScala) { commit =>
        RevisionInfo(
          Revision(commit.getId.getName),
          new DateTime(commit.getCommitTime.toLong * 1000, DateTimeZone.UTC),
          commit.getAuthorIdent.getName,
          commit.getFullMessage
        ).succeed
      }
    }
  }

  /**
   * Retrieve the revision tree id from a revision.
   * A default revision must be provided (because default in rudder does not mean the
   * same as for git)
   */
  def findRevTreeFromRevision(db: Repository, rev: Revision, defaultRev: IOResult[ObjectId]): IOResult[ObjectId] = {
    rev match {
      case GitVersion.DEFAULT_REV => defaultRev
      case Revision(r)            => findRevTreeFromRevString(db, r)
    }
  }

  /**
   * Retrieve the revision tree id from a Git object id
   */
  def findRevTreeFromRevString(db: Repository, revString: String): IOResult[ObjectId] = {
    IOResult.attemptZIO {
      val tree = db.resolve(revString)
      if (null == tree) {
        Thread.dumpStack()
        Inconsistency(s"The reference branch '${revString}' is not found in the Active Techniques Library's git repository").fail
      } else {
        val rw = new RevWalk(db)
        val id = rw.parseTree(tree).getId
        rw.dispose
        id.succeed
      }
    }
  }

  /**
  * Get a zip file containing files for commit "revTreeId".
  * You can filter files only some directory by giving
  * a root path.
  */
  def getZip(db: Repository, revTreeId: ObjectId, onlyUnderPaths: List[String] = Nil): IOResult[Array[Byte]] = {
    for {
      all     <- getStreamForFiles(db, revTreeId, onlyUnderPaths)
      zippable = all.map { case (p, opt) => Zippable.make(p, opt) }
      zip     <- ZIO.acquireReleaseWith(IOResult.attempt(new ByteArrayOutputStream()))(os => effectUioUnit(os.close())) { os =>
                   ZipUtils.zip(os, zippable) *> IOResult.attempt(os.toByteArray)
                 }
    } yield zip
  }

  def getStreamForFiles(
      db:             Repository,
      revTreeId:      ObjectId,
      onlyUnderPaths: List[String] = Nil
  ): IOResult[Seq[(String, Option[IOManaged[InputStream]])]] = {
    IOResult.attempt(
      s"Error when creating the list of files under ${onlyUnderPaths.mkString(", ")} in commit with id: '${revTreeId}'"
    ) {
      val directories = scala.collection.mutable.Set[String]()
      val entries     = scala.collection.mutable.Buffer.empty[(String, Option[IOManaged[InputStream]])]
      val tw          = new TreeWalk(db)
      // create a filter with a OR of all filters
      tw.setFilter(new FileTreeFilter(onlyUnderPaths, Nil))
      tw.setRecursive(true)
      tw.reset(revTreeId)

      while (tw.next) {
        val path   = tw.getPathString
        val parent = (new File(path)).getParent
        if (null != parent) {
          directories += parent
        }
        entries += ((path, Some(GitFindUtils.getManagedFileContent(db, revTreeId, path))))
      }

      // start by listing all directories, then all content
      directories.toSeq.map(p => (p, None)) ++ entries
    }
  }

  /**
   * Get git status (ie modified, added, removed, etc) for given repository, limiting
   * result to given paths relative to git base (let list empty for no filtering).
   *
   * BE CAREFULL: there's a bug in jgit library so having a subrepository breaks:
   * file in it are never filtered correctly: https://bugs.eclipse.org/bugs/show_bug.cgi?id=565251)
   */
  def getStatus(git: Git, onlyUnderPaths: List[String]): IOResult[Status] = {
    val s = git.status()
    onlyUnderPaths.foreach(p => s.addPath(p))
    IOResult.attempt(s.call())
  }
}

/**
 * A Git filter that allows to find files in a tree, optionally looking only
 * for file under given paths, and optionally looking only for files with a
 * given end of paths (for example, a given extension name)
 * Empty path or extension are ignored (they are removed from the list)
 */
class FileTreeFilter(rootDirectories: List[String], endPaths: List[String]) extends TreeFilter {

  // paths must not end with "/"

  private val startRawPaths = rootDirectories.filter(_ != "").map { p =>
    val pp = p.reverse.dropWhile(_ == '/').reverse
    JConstants.encode(pp)
  }

  private val endRawPaths = endPaths.filter(_ != "").map(e => JConstants.encode(e))

  /**
   * create the comparator that filter for given paths. An empty list is
   * an accept ALL filter (no filtering).
   */
  private def pathComparator(walker: TreeWalk, paths: List[Array[Byte]], test: Array[Byte] => Boolean) = {
    paths.size == 0 || paths.exists(p => test(p))
  }

  override def include(walker: TreeWalk): Boolean = {
    // in an authorized path
    pathComparator(walker, startRawPaths, x => walker.isPathPrefix(x, x.size) == 0) &&
    (
      walker.isSubtree ||
      pathComparator(walker, endRawPaths, x => walker.isPathSuffix(x, x.size))
    )
  }

  override val shouldBeRecursive = true
  override def clone:         FileTreeFilter = this
  override lazy val toString: String         = {
    val start = rootDirectories match {
      case Nil => ""
      case l   => l.mkString("(", ",", ")/")
    }
    val end   = endPaths match {
      case Nil => ""
      case l   => l.mkString("(", ",", ")")
    }

    start + ".*/" + end
  }
}

/**
 * A git filter that choose only file with the exact given name,
 * even if the file is in a sub-directory (the filter is
 * recursive).
 *
 * If given, the rootDirectory value must NOT start nor end with a
 * slash ("/").
 */
class ExactFileTreeFilter(rootDirectory: Option[String], fileName: String) extends TreeFilter {
  private val fileRawPath     = JConstants.encode("/" + fileName)
  private val rootFileRawPath = JConstants.encode(fileName)

  private val rawRootPath = {
    rootDirectory match {
      case None       => JConstants.encode("")
      case Some(path) => JConstants.encode(path)
    }
  }

  override def include(walker: TreeWalk): Boolean = {
    // root files does not start with "/"
    (walker.getPathLength == rootFileRawPath.size && walker.isPathSuffix(rootFileRawPath, rootFileRawPath.size)) ||
    (rawRootPath.size == 0 || (walker.isPathPrefix(rawRootPath, rawRootPath.size) == 0)) && // same root
    (walker.isSubtree || walker.isPathSuffix(fileRawPath, fileRawPath.size))
  }

  override val shouldBeRecursive = true
  override def clone:         ExactFileTreeFilter = this
  override lazy val toString: String              = "[.*/%s]".format(fileName)
}
