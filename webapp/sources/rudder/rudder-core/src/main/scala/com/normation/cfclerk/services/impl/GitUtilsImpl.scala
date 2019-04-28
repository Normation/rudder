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

package com.normation.cfclerk.services.impl


import java.io.File

import com.normation.cfclerk.services._
import com.normation.errors._
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.internal.storage.file.FileRepository
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.{Constants => JConstants}
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.filter.TreeFilter
import scalaz.zio._
import scalaz.zio.syntax._

/**
 * A default implementation that uses the given root directory
 * as the directory to control for revision.
 * If a .git repository is found in it, it is considered as the repository
 * to use, else if none is found, one is created.
 */
class GitRepositoryProviderImpl(techniqueDirectoryPath: String) extends GitRepositoryProvider { //we expect to have a .git here
  /**
   * Check for root package existence
   */
  private def checkPackageDirectory(dir: File): IOResult[Unit] = {
    if (!dir.exists) {
      Unconsistancy("Directory %s does not exist, how do you want that I read policy package in it?".format(dir)).fail
    } else if (!dir.canRead) {
      Unconsistancy("Directory %s is not readable, how do you want that I read policy package in it?".format(dir)).fail
    } else UIO.unit
  }

  /**
   * Ckeck git repos existence.
   * If no git repos is found, create one.
   */
  private def checkGitRepos(root:File) : IOResult[Repository] = {
    IOResult.effect {
      val db = (new FileRepositoryBuilder().setWorkTree(root).build).asInstanceOf[FileRepository]
      if(!db.getConfig.getFile.exists) {
        GitRepositoryLogger.logEffect.info(s"Git directory was not initialised: create a new git repository into folder '${root.getAbsolutePath}' and add all its content as initial release")
        db.create()
        val git = new Git(db)
        git.add.addFilepattern(".").call
        git.commit.setMessage("initial commit").call
      }
      db
    }
  }

  override val db = {
    val dir = new File(techniqueDirectoryPath)
    checkPackageDirectory(dir) *>
    checkGitRepos(dir)
  }

  override val git = db.map(x => new Git(x))
}



/**
 * A Git revision provider that always return the RevTree matching the
 * configured revPath.
 * It checks the path existence, but does not do anything special if
 * the reference does not exist (safe a error message).
 *
 * TODO: reading policy packages should be a Box method,
 * so that it can  fails in a knowable way.
 *
 * WARNING : the current revision is not persisted between creation of that class !
 */
class SimpleGitRevisionProvider(refPath:String,repo:GitRepositoryProvider) extends GitRevisionProvider {

  if(!refPath.startsWith("refs/")) {
    GitRepositoryLogger.logEffect.warn("The configured reference path for the Git repository of Policy Template User Library does "+
        "not start with 'refs/'. Are you sure you don't mistype something ?")
  }

  private[this] var currentId = getAvailableRevTreeId

  override def getAvailableRevTreeId : IOResult[ObjectId] = {
    repo.db.flatMap(db =>
      IOResult.effect {
        val treeId = db.resolve(refPath)

        if(null == treeId) {
          val message = s"The reference branch '${refPath}' is not found in the Policy Templates User Library's git repository"
          GitRepositoryLogger.logEffect.error(message)
          throw new IllegalArgumentException(message)
        }

        val rw = new RevWalk(db)
        val id = rw.parseTree(treeId).getId
        rw.dispose
        id
      }
    )
  }

  override def currentRevTreeId = currentId

  override def setCurrentRevTreeId(id: ObjectId) : IOResult[Unit] = {
    currentId = id.succeed
    UIO.unit
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
class FileTreeFilter(rootDirectory:Option[String], fileName: String) extends TreeFilter {
  private[this] val fileRawPath = JConstants.encode("/" + fileName)
  private[this] val rootFileRawPath = JConstants.encode(fileName)

  private[this] val rawRootPath = {
    rootDirectory match {
      case None => JConstants.encode("")
      case Some(path) => JConstants.encode(path)
    }
  }

  override def include(walker:TreeWalk) : Boolean = {
    //root files does not start with "/"
    (walker.getPathLength == rootFileRawPath.size && walker.isPathSuffix(rootFileRawPath, rootFileRawPath.size)) ||
    (rawRootPath.size == 0 || (walker.isPathPrefix(rawRootPath,rawRootPath.size) == 0)) && //same root
    ( walker.isSubtree || walker.isPathSuffix(fileRawPath, fileRawPath.size) )
  }

  override val shouldBeRecursive = true
  override def clone = this
  override lazy val toString = "[.*/%s]".format(fileName)
}

