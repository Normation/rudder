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

import com.normation.NamedZioLogger
import com.normation.errors.*
import com.normation.eventlog.ModificationId
import com.normation.rudder.domain.logger.GitArchiveLogger
import com.normation.rudder.domain.logger.GitArchiveLoggerPure
import com.normation.rudder.repository.*
import com.normation.rudder.repository.xml.ArchiveMode
import com.normation.utils.DateFormaterService
import java.io.File
import java.time.Instant
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.revwalk.RevTag
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal
import zio.*

/**
 * Utility trait that factor out file commits.
 */
trait GitItemRepository {

  // the underlying git repository where items are saved
  def gitRepo:      GitRepositoryProvider
  // relative path from git root directory where items for that implementation
  // are saved. For example, rules are saved in `/var/rudder/config-repo/rules`,
  // where `/var/rudder/config-repos` is the root directory provided by gitRepos,
  // the relativePath is `rules`
  def relativePath: String

  lazy val getItemDirectory: File = {

    /**
     * Create directory given in argument if does not exist, checking
     * that it is writable.
     */
    def createDirectory(directory: File): Either[RudderError, File] = {
      try {
        if (directory.exists) {
          if (directory.isDirectory) {
            if (directory.canWrite) {
              Right(directory)
            } else {
              Left(Inconsistency(s"The directory '${directory.getPath}' has no write permission, please use another directory"))
            }
          } else Left(Inconsistency("File at '%s' is not a directory, please change configuration".format(directory.getPath)))
        } else if (directory.mkdirs) {
          GitArchiveLogger.debug(s"Creating missing directory '${directory.getPath}'")
          Right(directory)
        } else {
          Left(
            Inconsistency(
              s"Directory '${directory.getPath}' does not exist and can not be created, please use another directory"
            )
          )
        }
      } catch {
        case NonFatal(ex) => Left(SystemError(s"Exception when checking directory '${directory.getPath}'", ex))
      }
    }

    val file = new File(gitRepo.rootDirectory.toJava, relativePath)
    createDirectory(file) match {
      case Right(dir) => dir
      case Left(err)  =>
        val msg = s"Error when checking required directories '${file.getPath}' to archive in gitRepo.git: ${err.fullMsg}"
        GitArchiveLogger.error(msg)
        throw new IllegalArgumentException(msg)
    }
  }

  // better.files.File.pathAsString is normalized without an ending slash, an Git path are relative to "/"
  // *without* the leading slash.
  def toGitPath(fsPath: File): String = fsPath.getPath.replace(gitRepo.rootDirectory.pathAsString + "/", "")

  /**
   * Files in gitPath are added.
   * commitMessage is used for the message of the commit.
   */
  def commitAddFile(commiter: PersonIdent, gitPath: String, commitMessage: String): IOResult[GitCommitId] = {
    gitRepo.semaphore.withPermit(
      for {
        _      <- GitArchiveLoggerPure.debug(s"Add file '${gitPath}' from configuration repository")
        add    <- IOResult.attempt(gitRepo.git.add.addFilepattern(gitPath).call)
        status <- IOResult.attempt(gitRepo.git.status.call)
        // for debugging
        _      <- if (!(status.getAdded.contains(gitPath) || status.getChanged.contains(gitPath))) {
                    GitArchiveLoggerPure.debug(
                      s"Auto-archive gitRepo.git failure: not found in gitRepo.git added files: '${gitPath}'. You can safely ignore that warning if the file was already existing in gitRepo.git and was not modified by that archive."
                    )
                  } else ZIO.unit
        rev    <- IOResult.attempt(gitRepo.git.commit.setCommitter(commiter).setMessage(commitMessage).call)
        commit <- IOResult.attempt(GitCommitId(rev.getName))
        _      <- GitArchiveLoggerPure.debug(s"file '${gitPath}' was added in commit '${commit.value}'")
      } yield {
        commit
      }
    )
  }

  /**
   * Files in gitPath are removed.
   * commitMessage is used for the message of the commit.
   */
  def commitRmFile(commiter: PersonIdent, gitPath: String, commitMessage: String): IOResult[GitCommitId] = {
    gitRepo.semaphore.withPermit(
      for {
        _      <- GitArchiveLoggerPure.debug(s"remove file '${gitPath}' from configuration repository")
        rm     <- IOResult.attempt(gitRepo.git.rm.addFilepattern(gitPath).call)
        status <- IOResult.attempt(gitRepo.git.status.call)
        _      <- if (!status.getRemoved.contains(gitPath)) {
                    GitArchiveLoggerPure.debug(
                      s"Auto-archive gitRepo.git failure: not found in gitRepo.git removed files: '${gitPath}'. You can safely ignore that warning if the file was already existing in gitRepo.git and was not modified by that archive."
                    )
                  } else ZIO.unit
        rev    <- IOResult.attempt(gitRepo.git.commit.setCommitter(commiter).setMessage(commitMessage).call)
        commit <- IOResult.attempt(GitCommitId(rev.getName))
        _      <- GitArchiveLoggerPure.debug(s"file '${gitPath}' was removed in commit '${commit.value}'")
      } yield {
        commit
      }
    )
  }

  /**
   * Commit files in oldGitPath and newGitPath, trying to commit them so that
   * gitRepo.git is aware of moved from old files to new ones.
   * More preciselly, files in oldGitPath are 'gitRepo.git rm', files in newGitPath are
   * 'gitRepo.git added' (with and without the 'update' mode).
   * commitMessage is used for the message of the commit.
   */
  def commitMvDirectory(
      commiter:      PersonIdent,
      oldGitPath:    String,
      newGitPath:    String,
      commitMessage: String
  ): IOResult[GitCommitId] = {
    gitRepo.semaphore.withPermit(
      for {
        _      <- GitArchiveLoggerPure.debug(s"move file '${oldGitPath}' from configuration repository to '${newGitPath}'")
        update <- IOResult.attempt {
                    gitRepo.git.rm.addFilepattern(oldGitPath).call
                    gitRepo.git.add.addFilepattern(newGitPath).call
                    gitRepo.git.add.setUpdate(true).addFilepattern(newGitPath).call // if some files were removed from dest dir
                  }
        status <- IOResult.attempt(gitRepo.git.status.call)
        _      <- if (!status.getAdded.asScala.exists(path => path.startsWith(newGitPath))) {
                    GitArchiveLoggerPure.debug(
                      s"Auto-archive gitRepo.git failure when moving directory (not found in added file): '${newGitPath}'. You can safely ignore that warning if the file was already existing in gitRepo.git and was not modified by that archive."
                    )
                  } else ZIO.unit
        rev    <- IOResult.attempt(gitRepo.git.commit.setCommitter(commiter).setMessage(commitMessage).call)
        commit <- IOResult.attempt(GitCommitId(rev.getName))
        _      <- GitArchiveLoggerPure.debug(s"file '${oldGitPath}' was moved to '${newGitPath}' in commit '${commit.value}'")
      } yield {
        commit
      }
    )
  }

}

/*
 * An extension of simple GitItemRepositoty that in addition knows how to link commitId and modId together.
 * Used for all configuration objects, but not for facts.
 */
trait GitConfigItemRepository extends GitItemRepository {

  def gitModificationRepository: GitModificationRepository

  /**
   * Files in gitPath are added.
   * commitMessage is used for the message of the commit.
   */
  def commitAddFileWithModId(
      modId:         ModificationId,
      commiter:      PersonIdent,
      gitPath:       String,
      commitMessage: String
  ): IOResult[GitCommitId] = {
    for {
      commit <- commitAddFile(commiter, gitPath, commitMessage)
      mod    <- gitModificationRepository.addCommit(commit, modId)
    } yield {
      commit
    }
  }

  /**
   * Files in gitPath are removed.
   * commitMessage is used for the message of the commit.
   */
  def commitRmFileWithModId(
      modId:         ModificationId,
      commiter:      PersonIdent,
      gitPath:       String,
      commitMessage: String
  ): IOResult[GitCommitId] = {
    for {
      commit <- commitRmFile(commiter, gitPath, commitMessage)
      mod    <- gitModificationRepository.addCommit(commit, modId)
    } yield {
      commit
    }
  }

  /**
   * Commit files in oldGitPath and newGitPath, trying to commit them so that
   * gitRepo.git is aware of moved from old files to new ones.
   * More preciselly, files in oldGitPath are 'gitRepo.git rm', files in newGitPath are
   * 'gitRepo.git added' (with and without the 'update' mode).
   * commitMessage is used for the message of the commit.
   */
  def commitMvDirectoryWithModId(
      modId:         ModificationId,
      commiter:      PersonIdent,
      oldGitPath:    String,
      newGitPath:    String,
      commitMessage: String
  ): IOResult[GitCommitId] = {
    for {
      commit <- commitMvDirectory(commiter, oldGitPath, newGitPath, commitMessage)
      mod    <- gitModificationRepository.addCommit(commit, modId)
    } yield {
      commit
    }
  }

}

/**
 * Utility trait that factor global commit and tags.
 */
trait GitArchiverFullCommitUtils extends NamedZioLogger {

  def gitRepo:                   GitRepositoryProvider
  def gitModificationRepository: GitModificationRepository
  // where goes tags, something like archives/groups/ (with a final "/") is awaited
  def tagPrefix:                 String
  def relativePath:              String

  /**
   * Commit all the modifications for files under the given path.
   * The commitMessage is used in the commit.
   */
  def commitFullGitPathContentAndTag(commiter: PersonIdent, commitMessage: String): IOResult[GitArchiveId] = {
    IOResult.attempt {
      // remove existing and add modified
      gitRepo.git.add.setUpdate(true).addFilepattern(relativePath).call
      // also add new one
      gitRepo.git.add.addFilepattern(relativePath).call
      val commit = gitRepo.git.commit.setCommitter(commiter).setMessage(commitMessage).call
      val path   = GitPath(tagPrefix + DateFormaterService.formatAsGitTag(Instant.now))
      logEffect.info("Create a new archive: " + path.value)
      gitRepo.git.tag.setMessage(commitMessage).setName(path.value).setTagger(commiter).setObjectId(commit).call
      GitArchiveId(path, GitCommitId(commit.getName), commiter)
    }
  }

  def restoreCommitAtHead(
      commiter:      PersonIdent,
      commitMessage: String,
      commit:        GitCommitId,
      archiveMode:   ArchiveMode,
      modId:         ModificationId
  ): IOResult[GitCommitId] = {
    IOResult.attempt {
      // We don't want any commit when we are restoring HEAD
      val head   = gitRepo.db.resolve("HEAD")
      val target = gitRepo.db.resolve(commit.value)
      if (target == head) {
        // we are restoring HEAD
        commit
      } else {
        /* Configure rm with archive mode and call it
         *this will delete latest (HEAD) configuration files from the repository
         */
        archiveMode.configureRm(gitRepo.git.rm).call

        /* Configure checkout with archive mode, set reference commit to target commit,
         * set master as branches to update, and finally call checkout on it
         *This will add the content from the commit to be restored on the HEAD of branch master
         */
        archiveMode.configureCheckout(gitRepo.git.checkout).setStartPoint(commit.value).setName("master").call

        // The commit will actually delete old files and replace them with those from the checkout
        val newCommit   = gitRepo.git.commit.setCommitter(commiter).setMessage(commitMessage).call
        val newCommitId = GitCommitId(newCommit.getName)
        // Store the commit the modification repository
        gitModificationRepository.addCommit(newCommitId, modId)

        GitArchiveLogger.debug("Restored commit %s at HEAD (commit %s)".format(commit.value, newCommitId.value))
        newCommitId
      }
    }
  }

  /**
   * List tags and their date for that use of commitFullGitPathContentAndTag
   * The DateTime is the one from the name, which may differ from the
   * date of the tag.
   */
  def getTags(): IOResult[Map[Instant, GitArchiveId]] = {
    for {
      revTags <- listTagWorkaround
      res     <- IOResult.attempt {
                   revTags.flatMap { revTag =>
                     val name = revTag.getTagName
                     if (name.startsWith(tagPrefix)) {
                       val t = {
                         try {
                           Some(
                             (
                               DateFormaterService.parseAsGitTag(name.substring(tagPrefix.size, name.size)),
                               GitArchiveId(GitPath(name), GitCommitId(revTag.getName), revTag.getTaggerIdent)
                             )
                           )
                         } catch {
                           case ex: IllegalArgumentException =>
                             logEffect.info(
                               "Error when parsing tag with name '%s' as a valid archive tag name, ignoring it.".format(name)
                             )
                             None
                         }
                       }
                       t
                     } else None
                   }.toMap
                 }
    } yield {
      res
    }
  }

  /**
   * There is a bug in tag resolution of JGit 1.2, see:
   * https://bugs.eclipse.org/bugs/show_bug.cgi?id=360650
   *
   * They used a workaround here:
   * http://gitRepo.git.eclipse.org/c/orion/org.eclipse.orion.server.gitRepo.git/commit/?id=5fca49ced7f0c220472c724678884ee84d13e09d
   *
   * But the correction with `gitRepo.tagList.call` does not provide a
   * much easier access, since it returns a REF that need to be parsed..
   * So just keep the working workaround.
   */
  private def listTagWorkaround: IOResult[Seq[RevTag]] = {
    import org.eclipse.jgit.errors.IncorrectObjectTypeException
    import org.eclipse.jgit.lib.*
    import org.eclipse.jgit.revwalk.*

    import scala.collection.mutable.ArrayBuffer

    ZIO.acquireReleaseWith(IOResult.attempt(new RevWalk(gitRepo.db)))(rw => effectUioUnit(rw.close)) { revWalk =>
      IOResult.attempt {
        val tags    = ArrayBuffer[RevTag]()
        val refList = gitRepo.db.getRefDatabase().getRefsByPrefix(Constants.R_TAGS).asScala
        refList.foreach { ref =>
          try {
            val tag = revWalk.parseTag(ref.getObjectId())
            tags.append(tag)
          } catch {
            case e: IncorrectObjectTypeException =>
              GitArchiveLogger.debug("Ignoring object due to JGit bug: " + ref.getName, e)
          }
        }
        tags.sortWith((o1, o2) => o1.getTagName().compareTo(o2.getTagName()) <= 0).toSeq
      }
    }
  }
}
