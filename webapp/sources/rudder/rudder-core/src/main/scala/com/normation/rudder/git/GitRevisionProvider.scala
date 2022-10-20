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

import com.normation.errors._
import com.normation.rudder.domain.logger.GitRepositoryLogger
import com.normation.zio._
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.revwalk.RevWalk
import zio._

/**
 * A service that allows to know what is the
 * commit used as "the current one", and what
 * is the last available in the repository.
 *
 * A method also allowed to change the commit
 * to use as reference.
 *
 * All object Ids in that service are RevTree ids,
 * and can be used for example in:
 * <pre>
 * val tw = new TreeWalk(repository)
 * tw.reset(objectId)
 * </pre>
 */
trait GitRevisionProvider {

  /**
   * Return the last RevTree objectId that
   * is accessible in the repository.
   */
  def getAvailableRevTreeId: IOResult[ObjectId]

  /**
   * Return the commit currently used as the
   * "current version".
   */
  def currentRevTreeId: IOResult[ObjectId]

  /**
   * Update the reference to current commit
   * to the provided one.
   */
  def setCurrentRevTreeId(id: ObjectId): IOResult[Unit]

}

// note: for configuration repo, we also have a revision provider that stores revision in LDAP and
// update the value on specific moment (when the tech lib is reloaded)

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
class SimpleGitRevisionProvider(refPath: String, repo: GitRepositoryProvider) extends GitRevisionProvider {

  if (!refPath.startsWith("refs/")) {
    GitRepositoryLogger.logEffect.warn(
      "The configured reference path for the Git repository of Policy Template User Library does " +
      "not start with 'refs/'. Are you sure you don't mistype something ?"
    )
  }

  private[this] val currentId = Ref.make[ObjectId](getAvailableRevTreeId.runNow).runNow

  override def getAvailableRevTreeId: IOResult[ObjectId] = {
    IOResult.effect {
      val treeId = repo.db.resolve(refPath)

      if (null == treeId) {
        val message = s"The reference branch '${refPath}' is not found in the Policy Templates User Library's git repository"
        GitRepositoryLogger.logEffect.error(message)
        throw new IllegalArgumentException(message)
      }

      val rw = new RevWalk(repo.db)
      val id = rw.parseTree(treeId).getId
      rw.dispose
      id
    }
  }

  override def currentRevTreeId = currentId.get

  override def setCurrentRevTreeId(id: ObjectId): IOResult[Unit] = {
    currentId.set(id)
  }
}
