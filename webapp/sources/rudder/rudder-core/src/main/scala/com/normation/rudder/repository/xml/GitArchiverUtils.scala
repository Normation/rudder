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

package com.normation.rudder.repository.xml

import com.normation.cfclerk.services.GitRepositoryProvider
import com.normation.eventlog.ModificationId
import com.normation.rudder.repository._
import java.io.File
import java.io.IOException

import com.normation.NamedZioLogger
import net.liftweb.util.Helpers.tryo
import org.eclipse.jgit.revwalk.RevTag
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.revwalk.RevTag
import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat

import scala.collection.JavaConverters._
import scala.xml.Elem
import org.apache.commons.io.FileUtils
import com.normation.errors._
import com.normation.zio.ZioRuntime
import scalaz.zio._
import scalaz.zio.syntax._

/**
 * Utility trait that factor out file commits.
 */
trait GitArchiverUtils extends NamedZioLogger {

  object GET {
    def apply(reason:Option[String]) = reason match {
      case None => ""
      case Some(m) => "\n\nReason provided by user:\n" + m
    }
  }

  // semaphores to replace `synchronized`
  val semaphoreAdd    = Semaphore.make(1)
  val semaphoreMove   = Semaphore.make(1)
  val semaphoreDelete = Semaphore.make(1)


  def gitRepo : GitRepositoryProvider
  def gitRootDirectory : File
  def relativePath : String
  def xmlPrettyPrinter : RudderPrettyPrinter
  def encoding : String
  def gitModificationRepository : GitModificationRepository

  def newDateTimeTagString = (DateTime.now()).toString(ISODateTimeFormat.dateTime)

  /**
   * Create directory given in argument if does not exists, checking
   * that it is writable.
   */
  def createDirectory(directory:File): IOResult[File] = {
    IOResult.effectM(s"Exception when checking directory '${directory.getPath}'") {
      if(directory.exists) {
        if(directory.isDirectory) {
          if(directory.canWrite) {
            directory.succeed
          } else Unconsistancy(s"The directory '${directory.getPath}' has no write permission, please use another directory").fail
        } else Unconsistancy("File at '%s' is not a directory, please change configuration".format(directory.getPath)).fail
      } else if(directory.mkdirs) {
        logPure.debug(s"Creating missing directory '${directory.getPath}'") *>
        directory.succeed
      } else Unconsistancy(s"Directory '${directory.getPath}' does not exists and can not be created, please use another directory").fail
    }
  }

  lazy val getRootDirectory : File = {
    val file = new File(gitRootDirectory, relativePath)
    ZioRuntime.runNow(createDirectory(file).either) match {
      case Right(dir) => dir
      case Left(err) =>
        val msg = s"Error when checking required directories '${file.getPath}' to archive in git: ${err.fullMsg}"
        logEffect.error(msg)
        throw new IllegalArgumentException(msg)
    }
  }

  /**
   * Files in gitPath are added.
   * commitMessage is used for the message of the commit.
   */
  def commitAddFile(modId : ModificationId, commiter:PersonIdent, gitPath:String, commitMessage:String) : IOResult[GitCommitId] = {
    semaphoreAdd.flatMap(lock =>
      ZIO.bracket(lock.acquire)(_ => lock.release) { _ =>
        for {
          _      <- logPure.debug(s"Add file '${gitPath}' from configuration repository")
          git    <- gitRepo.git
          add    <- IOResult.effect(git.add.addFilepattern(gitPath).call)
          status <- IOResult.effect(git.status.call)
         //for debugging
          _      <- if(!(status.getAdded.contains(gitPath) || status.getChanged.contains(gitPath))) {
                      logPure.warn(s"Auto-archive git failure: not found in git added files: '${gitPath}'. You can safely ignore that warning if the file was already existing in Git and was not modified by that archive.")
                    } else UIO.unit
          rev    <- IOResult.effect(git.commit.setCommitter(commiter).setMessage(commitMessage).call)
          commit <- IOResult.effect(GitCommitId(rev.getName))
          _      <- logPure.debug(s"file '${gitPath}' was added in commit '${commit.value}'")
          mod    <- gitModificationRepository.addCommit(commit, modId)
        } yield {
          commit
        }
    })
  }

  /**
   * Files in gitPath are removed.
   * commitMessage is used for the message of the commit.
   */
  def commitRmFile(modId : ModificationId, commiter:PersonIdent, gitPath:String, commitMessage:String) : IOResult[GitCommitId] = {
    semaphoreDelete.flatMap(lock =>
      ZIO.bracket(lock.acquire)(_ => lock.release) { _ =>
        for {
          _      <- logPure.debug(s"remove file '${gitPath}' from configuration repository")
          git    <- gitRepo.git
          rm     <- IOResult.effect(git.rm.addFilepattern(gitPath).call)
          status <- IOResult.effect(git.status.call)
          _      <- if(!status.getRemoved.contains(gitPath)) {
                      logPure.warn(s"Auto-archive git failure: not found in git removed files: '${gitPath}'. You can safely ignore that warning if the file was already existing in Git and was not modified by that archive.")
                    } else UIO.unit
          rev    <- IOResult.effect(git.commit.setCommitter(commiter).setMessage(commitMessage).call)
          commit <- IOResult.effect(GitCommitId(rev.getName))
          _      <- logPure.debug(s"file '${gitPath}' was removed in commit '${commit.value}'")
          mod    <- gitModificationRepository.addCommit(commit, modId)
        } yield {
          commit
        }
      })
  }

  /**
   * Commit files in oldGitPath and newGitPath, trying to commit them so that
   * git is aware of moved from old files to new ones.
   * More preciselly, files in oldGitPath are 'git rm', files in newGitPath are
   * 'git added' (with and without the 'update' mode).
   * commitMessage is used for the message of the commit.
   */
  def commitMvDirectory(modId : ModificationId, commiter:PersonIdent, oldGitPath:String, newGitPath:String, commitMessage:String) : IOResult[GitCommitId] = {
    semaphoreMove.flatMap(lock =>
      ZIO.bracket(lock.acquire)(_ => lock.release) { _ =>
        for {
          _      <- logPure.debug(s"move file '${oldGitPath}' from configuration repository to '${newGitPath}'")
          git    <- gitRepo.git
          update <- IOResult.effect {
                      git.rm.addFilepattern(oldGitPath).call
                      git.add.addFilepattern(newGitPath).call
                      git.add.setUpdate(true).addFilepattern(newGitPath).call //if some files were removed from dest dir
                    }
          status <- IOResult.effect(git.status.call)
          _      <- if(!status.getAdded.asScala.exists( path => path.startsWith(newGitPath) ) ) {
                      logPure.warn(s"Auto-archive git failure when moving directory (not found in added file): '${newGitPath}'. You can safely ignore that warning if the file was already existing in Git and was not modified by that archive.")
                    } else UIO.unit
          rev    <- IOResult.effect(git.commit.setCommitter(commiter).setMessage(commitMessage).call)
          commit <- IOResult.effect(GitCommitId(rev.getName))
          _      <- logPure.debug(s"file '${oldGitPath}' was moved to '${newGitPath}' in commit '${commit.value}'")
          mod    <- gitModificationRepository.addCommit(commit, modId)
        } yield {
          commit
        }
    })
  }

  def toGitPath(fsPath:File) = fsPath.getPath.replace(gitRootDirectory.getPath +"/","")

  /**
   * Write the given Elem (prettified) into given file, log the message
   */
  def writeXml(fileName:File, elem:Elem, logMessage:String) : IOResult[File] = {
    IOResult.effect {
      FileUtils.writeStringToFile(fileName, xmlPrettyPrinter.format(elem), encoding)
      logEffect.debug(logMessage)
      fileName
    }
  }
}

/**
 * Utility trait that factor global commit and tags.
 */
trait GitArchiverFullCommitUtils extends NamedZioLogger {

  def gitRepo : GitRepositoryProvider
  def gitModificationRepository : GitModificationRepository
  //where goes tags, something like archives/groups/ (with a final "/") is awaited
  def tagPrefix : String
  def relativePath : String

  /**
   * Commit all the modifications for files under the given path.
   * The commitMessage is used in the commit.
   */
  def commitFullGitPathContentAndTag(commiter:PersonIdent, commitMessage:String) : IOResult[GitArchiveId] = {
    gitRepo.git.flatMap(git =>
      IOResult.effect {
        //remove existing and add modified
        git.add.setUpdate(true).addFilepattern(relativePath).call
        //also add new one
        git.add.addFilepattern(relativePath).call
        val commit = git.commit.setCommitter(commiter).setMessage(commitMessage).call
        val path = GitPath(tagPrefix+DateTime.now.toString(GitTagDateTimeFormatter))
        logEffect.info("Create a new archive: " + path)
        git.tag.setMessage(commitMessage).
          setName(path.value).
          setTagger(commiter).
          setObjectId(commit).call
        GitArchiveId(path, GitCommitId(commit.getName), commiter)
      }
    )
  }

  def restoreCommitAtHead(commiter:PersonIdent, commitMessage:String, commit:GitCommitId, archiveMode:ArchiveMode,modId:ModificationId) = {
    gitRepo.git.flatMap(git => gitRepo.db.flatMap(db =>
      IOResult.effect {
        // We don't want any commit when we are restoring HEAD
        val head = db.resolve("HEAD")
        val target = db.resolve(commit.value)
        if (target == head) {
          // we are restoring HEAD
          commit
        } else {
          /* Configure rm with archive mode and call it
           *this will delete latest (HEAD) configuration files from the repository
           */
          archiveMode.configureRm(git.rm).call

          /* Configure checkout with archive mode, set reference commit to target commit,
           * set master as branches to update, and finally call checkout on it
           *This will add the content from the commit to be restored on the HEAD of branch master
           */
          archiveMode.configureCheckout(git.checkout).setStartPoint(commit.value).setName("master").call

          // The commit will actually delete old files and replace them with those from the checkout
          val newCommit = git.commit.setCommitter(commiter).setMessage(commitMessage).call
          val newCommitId = GitCommitId(newCommit.getName)
          // Store the commit the modification repository
          gitModificationRepository.addCommit(newCommitId, modId)

          logPure.debug("Restored commit %s at HEAD (commit %s)".format(commit.value,newCommitId.value))
          newCommitId
        }
      }
    ))
  }

  /**
   * List tags and their date for that use of commitFullGitPathContentAndTag
   * The DateTime is the one from the name, which may differ from the
   * date of the tag.
   */
  def getTags() : IOResult[Map[DateTime, GitArchiveId]] = {
    for {
      revTags <- listTagWorkaround
      res     <- IOResult.effect {
                   revTags.flatMap { revTag =>
                     val name = revTag.getTagName
                     if(name.startsWith(tagPrefix)) {
                       val t = try {
                         Some((
                             GitTagDateTimeFormatter.parseDateTime(name.substring(tagPrefix.size, name.size))
                           , GitArchiveId(GitPath(name), GitCommitId(revTag.getName), revTag.getTaggerIdent)
                         ))
                       } catch {
                         case ex:IllegalArgumentException =>
                           logEffect.info("Error when parsing tag with name '%s' as a valid archive tag name, ignoring it.".format(name))
                           None
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
   * http://git.eclipse.org/c/orion/org.eclipse.orion.server.git/commit/?id=5fca49ced7f0c220472c724678884ee84d13e09d
   *
   * But the correction with `gitRepo.git.tagList.call` does not provide a
   * much easier access, since it returns a REF that need to be parsed..
   * So just keep the working workaround.
   */
  private[this] def listTagWorkaround: IOResult[Seq[RevTag]] = {
    import org.eclipse.jgit.errors.IncorrectObjectTypeException
    import org.eclipse.jgit.lib._
    import org.eclipse.jgit.revwalk._
    import scala.collection.mutable.ArrayBuffer

    gitRepo.db.flatMap(db =>
      ZIO.bracket(IOResult.effect(new RevWalk(db)))(db => IOResult.effect(db.close).run.void){ revWalk =>
        IOResult.effect {
          val tags = ArrayBuffer[RevTag]()
          val refList = db.getRefDatabase().getRefsByPrefix(Constants.R_TAGS).asScala
          refList.foreach { ref =>
            try {
              val tag = revWalk.parseTag(ref.getObjectId())
              tags.append(tag)
            } catch {
              case e:IncorrectObjectTypeException =>
                logEffect.debug("Ignoring object due to JGit bug: " + ref.getName)
                logEffect.debug(e)
            }
          }
          tags.sortWith( (o1, o2) => o1.getTagName().compareTo(o2.getTagName()) <= 0 )
        }
      })
  }
}
