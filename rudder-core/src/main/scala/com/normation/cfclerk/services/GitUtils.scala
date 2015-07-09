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

package com.normation.cfclerk.services

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.ObjectId

/**
 * A service that gives access to the Git
 * porcelain API of the repository.
 */
trait GitRepositoryProvider {
  /**
   * Obtain access to JGit porcelain API.
   */
  def git : Git

  def db : Repository
}



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
  def getAvailableRevTreeId : ObjectId

  /**
   * Return the commit currently used as the
   * "current version".
   */
  def currentRevTreeId : ObjectId

  /**
   * Update the reference to current commit
   * to the provided one.
   */
  def setCurrentRevTreeId(id:ObjectId) : Unit

}
