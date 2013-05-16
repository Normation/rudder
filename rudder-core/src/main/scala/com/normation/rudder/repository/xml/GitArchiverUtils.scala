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
import scala.collection.JavaConversions.asScalaSet
import scala.xml.Elem
import scala.xml.PrettyPrinter
import org.apache.commons.io.FileUtils
import org.eclipse.jgit.api.Git
import org.joda.time.format.ISODateTimeFormat
import org.joda.time.DateTime
import com.normation.cfclerk.services.GitRepositoryProvider
import com.normation.exceptions.TechnicalException
import com.normation.utils.Utils
import net.liftweb.common._
import net.liftweb.util.Helpers.tryo
import org.joda.time.format.DateTimeFormatterBuilder
import org.joda.time.DateTimeFieldType
import org.eclipse.jgit.revwalk.RevTag
import org.eclipse.jgit.api.errors.JGitInternalException
import org.eclipse.jgit.lib.PersonIdent
import com.normation.rudder.repository._
import com.normation.eventlog.ModificationId


/**
 * Utility trait that factor out file commits.
 */
trait GitArchiverUtils extends Loggable {

  def gitRepo : GitRepositoryProvider
  def gitRootDirectory : File
  def relativePath : String
  def xmlPrettyPrinter : PrettyPrinter
  def encoding : String
  def gitModificationRepository : GitModificationRepository

  def newDateTimeTagString = (DateTime.now()).toString(ISODateTimeFormat.dateTime)

  lazy val getRootDirectory : File = {
    val file = new File(gitRootDirectory, relativePath)
    Utils.createDirectory(file) match {
      case Full(dir) => dir
      case eb:EmptyBox =>
        val e = eb ?~! "Error when checking required directories '%s' to archive in git:".format(file.getPath)
        logger.error(e.messageChain)
        throw new TechnicalException(e.messageChain)
    }
  }

  /**
   * Files in gitPath are added.
   * commitMessage is used for the message of the commit.
   */
  def commitAddFile(modId : ModificationId, commiter:PersonIdent, gitPath:String, commitMessage:String) : Box[GitCommitId] = synchronized {
    tryo {
      logger.debug("Add file %s from configuration repository".format(gitPath))
      gitRepo.git.add.addFilepattern(gitPath).call
      val status = gitRepo.git.status.call
      //for debugging
      if(!(status.getAdded.contains(gitPath) || status.getChanged.contains(gitPath))) {
        logger.warn("Auto-archive git failure: not found in git added files: '%s'. You can safelly ignore that warning if the file was already existing in Git and was not modified by that archive.".format(gitPath))
      }
      val rev = gitRepo.git.commit.setCommitter(commiter).setMessage(commitMessage).call
      val commit = GitCommitId(rev.getName)
      logger.debug("file %s was added in commit %s".format(gitPath,rev.getName))
      gitModificationRepository.addCommit(commit, modId)
      commit
    }
  }

  /**
   * Files in gitPath are removed.
   * commitMessage is used for the message of the commit.
   */
  def commitRmFile(modId : ModificationId, commiter:PersonIdent, gitPath:String, commitMessage:String) : Box[GitCommitId] = synchronized {
    tryo {
      logger.debug("remove file %s from configuration repository".format(gitPath))
      gitRepo.git.rm.addFilepattern(gitPath).call
      val status = gitRepo.git.status.call
      if(!status.getRemoved.contains(gitPath)) {
        logger.warn("Auto-archive git failure: not found in git removed files: '%s'. You can safelly ignore that warning if the file was already existing in Git and was not modified by that archive.".format(gitPath))
      }
      val rev = gitRepo.git.commit.setCommitter(commiter).setMessage(commitMessage).call
      val commit = GitCommitId(rev.getName)
      logger.debug("file %s was removed in commit %s".format(gitPath,rev.getName))
      gitModificationRepository.addCommit(commit, modId)
      commit
    }
  }

  /**
   * Commit files in oldGitPath and newGitPath, trying to commit them so that
   * git is aware of moved from old files to new ones.
   * More preciselly, files in oldGitPath are 'git rm', files in newGitPath are
   * 'git added' (with and without the 'update' mode).
   * commitMessage is used for the message of the commit.
   */
  def commitMvDirectory(modId : ModificationId, commiter:PersonIdent, oldGitPath:String, newGitPath:String, commitMessage:String) : Box[GitCommitId] = synchronized {

    tryo {
      logger.debug("move file %s from configuration repository to %s".format(oldGitPath,newGitPath))
      gitRepo.git.rm.addFilepattern(oldGitPath).call
      gitRepo.git.add.addFilepattern(newGitPath).call
      gitRepo.git.add.setUpdate(true).addFilepattern(newGitPath).call //if some files were removed from dest dir
      val status = gitRepo.git.status.call
      if(!status.getAdded.exists( path => path.startsWith(newGitPath) ) ) {
        logger.warn("Auto-archive git failure when moving directory (not found in added file): '%s'. You can safelly ignore that warning if the file was already existing in Git and was not modified by that archive.".format(newGitPath))
      }
      val rev = gitRepo.git.commit.setCommitter(commiter).setMessage(commitMessage).call
      val commit = GitCommitId(rev.getName)
      logger.debug("file %s was moved to %s in commit %s".format(oldGitPath,newGitPath,rev.getName))
      gitModificationRepository.addCommit(commit, modId)
      commit
    }
  }

  def toGitPath(fsPath:File) = fsPath.getPath.replace(gitRootDirectory.getPath +"/","")

  /**
   * Write the given Elem (prettified) into given file, log the message
   */
  def writeXml(fileName:File, elem:Elem, logMessage:String) : Box[File] = {
    tryo {
      FileUtils.writeStringToFile(fileName, xmlPrettyPrinter.format(elem), encoding)
      logger.debug(logMessage)
      fileName
    }
  }
}

/**
 * Utility trait that factor global commit and tags.
 */
trait GitArchiverFullCommitUtils extends Loggable {

  def gitRepo : GitRepositoryProvider
  def gitModificationRepository : GitModificationRepository
  //where goes tags, something like archives/groups/ (with a final "/") is awaited
  def tagPrefix : String
  def relativePath : String

  /**
   * Commit all the modifications for files under the given path.
   * The commitMessage is used in the commit.
   */
  def commitFullGitPathContentAndTag(commiter:PersonIdent, commitMessage:String) : Box[GitArchiveId] = {
    tryo {
      //remove existing and add modified
      gitRepo.git.add.setUpdate(true).addFilepattern(relativePath).call
      //also add new one
      gitRepo.git.add.addFilepattern(relativePath).call
      val commit = gitRepo.git.commit.setCommitter(commiter).setMessage(commitMessage).call
      val path = GitPath(tagPrefix+DateTime.now.toString(GitTagDateTimeFormatter))
      logger.info("Create a new archive: " + path)
      val tagCommit = gitRepo.git.tag.setMessage(commitMessage).
        setName(path.value).
        setTagger(commiter).
        setObjectId(commit).call
      GitArchiveId(path, GitCommitId(commit.getName), commiter)
    }
  }

  def restoreCommitAtHead(commiter:PersonIdent, commitMessage:String, commit:GitCommitId, archiveMode:ArchiveMode,modId:ModificationId) = {
    tryo {

      // We don't want any commit when we are restoring HEAD
      val head = gitRepo.db.resolve("HEAD")
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
        val newCommit = gitRepo.git.commit.setCommitter(commiter).setMessage(commitMessage).call
        val newCommitId = GitCommitId(newCommit.getName)
        // Store the commit the modification repository
        gitModificationRepository.addCommit(newCommitId, modId)

        logger.debug("Restored commit %s at HEAD (commit %s)".format(commit.value,newCommitId.value))
        newCommitId
      }
    }
  }

  /**
   * List tags and their date for that use of commitFullGitPathContentAndTag
   * The DateTime is the one from the name, which may differ from the
   * date of the tag.
   */
  def getTags() : Box[Map[DateTime, GitArchiveId]] = {
    import scala.collection.JavaConversions._
    tryo {
//      TODO: use that when JGit version > 1.2
//      gitRepo.git.tagList.call.flatMap { revTag =>
      listTagWorkaround.flatMap { revTag =>
          val name = revTag.getTagName
          if(name.startsWith(tagPrefix)) {
            val t = try {
              Some((
                  GitTagDateTimeFormatter.parseDateTime(name.substring(tagPrefix.size, name.size))
                , GitArchiveId(GitPath(name), GitCommitId(revTag.getName), revTag.getTaggerIdent)
              ))
            } catch {
              case ex:IllegalArgumentException =>
                logger.info("Error when parsing tag with name '%s' as a valid archive tag name, ignoring it.".format(name))
                None
            }
            t
          } else None
      }.toMap
    }
  }

  /**
   * There is a bug in tag resolution of JGit 1.2, see:
   * https://bugs.eclipse.org/bugs/show_bug.cgi?id=360650
   *
   * They used a workaround here:
   * http://git.eclipse.org/c/orion/org.eclipse.orion.server.git/commit/?id=5fca49ced7f0c220472c724678884ee84d13e09d
   *
   * For now, we will just ignore tag which are no real tags
   *
   * When the upgrade is done to JGit > 1.2, replace the call to
   * listTagWorkaround in getTags by "gitRepo.git.tagList.call"
   */
  private[this] def listTagWorkaround = {
    import java.io.IOException
    import org.eclipse.jgit.api.errors.JGitInternalException
    import org.eclipse.jgit.errors.IncorrectObjectTypeException
    import org.eclipse.jgit.lib._
    import org.eclipse.jgit.revwalk._
    import scala.collection.mutable.{Map => MutMap, ArrayBuffer}
    import scala.collection.JavaConversions._

    var refList = MutMap[String,Ref]()
    val revWalk = new RevWalk(gitRepo.db)
    val tags = ArrayBuffer[RevTag]()

    try {
      refList = gitRepo.db.getRefDatabase().getRefs(Constants.R_TAGS)
      refList.values().foreach { ref =>
        try {
          val tag = revWalk.parseTag(ref.getObjectId())
          tags.add(tag)
        } catch {
          case e:IncorrectObjectTypeException =>
            logger.debug("Ignoring object due to JGit bug: " + ref.getName)
            logger.debug(e)
        }
      }
    } catch {
      case e:IOException => throw new JGitInternalException(e.getMessage(), e)
    } finally {
      revWalk.release();
    }
    tags.sortWith( (o1, o2) => o1.getTagName().compareTo(o2.getTagName()) <= 0 )
  }
}
