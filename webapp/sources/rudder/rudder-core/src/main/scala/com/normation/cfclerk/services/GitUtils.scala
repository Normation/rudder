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

package com.normation.cfclerk.services

import com.normation.NamedZioLogger
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.ObjectId
import com.normation.errors._

/**
 * A service that gives access to the Git
 * porcelain API of the repository.
 */
trait GitRepositoryProvider {
  /**
   * Obtain access to JGit porcelain API.
   */
  def git: IOResult[Git]

  def db: IOResult[Repository]
}

object GitRepositoryLogger extends NamedZioLogger() { val loggerName = "git-repository" }

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
  def setCurrentRevTreeId(id:ObjectId): IOResult[Unit]

}
