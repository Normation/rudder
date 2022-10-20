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

import better.files._
import com.normation.errors._
import com.normation.rudder.domain.logger.GitRepositoryLogger
import com.normation.zio._
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.internal.storage.file.FileRepository
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import zio._
import zio.syntax._

/**
 * A service that gives access to the Git
 * porcelain API of the repository.
 *
 * For reference about how to use JGit: https://github.com/centic9/jgit-cookbook
 */
trait GitRepositoryProvider {

  /**
   * Obtain access to JGit porcelain API.
   */
  def git: Git

  def db: Repository

  def rootDirectory: File

  /*
   * Git (and JGit) are not thread safe. When there is two concurrent write operations, we can get a
   * `JGitInternalException: Exception caught during execution of add command` (which is not very
   * informative - see https://issues.rudder.io/issues/19398).
   * So we need to protect at the repository level write operation with a semaphore.
   */
  def semaphore: Semaphore
}

/**
 * A default implementation that uses the given root directory
 * as the directory to control for revision.
 * If a .git repository is found in it, it is considered as the repository
 * to use, else if none is found, one is created.
 */
class GitRepositoryProviderImpl(override val db: Repository, override val rootDirectory: File) extends GitRepositoryProvider { // we expect to have a .git here
  override val git       = new Git(db)
  override val semaphore = Semaphore.make(1).runNow
}

object GitRepositoryProviderImpl {

  /**
   * Use the provided full path as the root directory for given git provider.
   */
  def make(gitRootPath: String): IOResult[GitRepositoryProviderImpl] = {

    /**
     * Check that we can read/write in root directory
     */
    def checkRootDirectory(dir: File): IOResult[Unit] = {
      if (!dir.exists) {
        IOResult
          .attempt(dir.createDirectories())
          .chainError(
            s"Directory '${dir.pathAsString}' was missing and we were " +
            s"unable to create it to init git repository"
          )
          .unit
      } else if (!dir.isOwnerWritable) {
        Inconsistency(
          s"Directory '${dir.pathAsString}' exists but it not writable and so it can't be use as a git repository root " +
          s"directory. Please check that it's really a directory and that rights are correct"
        ).fail
      } else ZIO.unit
    }

    /**
     * Ckeck git repos existence.
     * If no git repos is found, create one.
     */
    def checkGitRepos(root: File): IOResult[Repository] = {
      IOResult.attempt {
        val db = (new FileRepositoryBuilder().setWorkTree(root.toJava).build).asInstanceOf[FileRepository]
        if (!db.getConfig.getFile.exists) {
          GitRepositoryLogger.logEffect.info(
            s"Git directory was not initialised: create a new git repository into folder '${root.pathAsString}' and add all its content as initial release"
          )
          db.create()
          val git = new Git(db)
          git.add.addFilepattern(".").call
          git.commit.setMessage("initial commit").call
        }
        db
      }
    }

    val dir = File(gitRootPath)
    checkRootDirectory(dir) *>
    checkGitRepos(dir).flatMap(db => IOResult.attempt(new GitRepositoryProviderImpl(db, dir)))
  }
}
