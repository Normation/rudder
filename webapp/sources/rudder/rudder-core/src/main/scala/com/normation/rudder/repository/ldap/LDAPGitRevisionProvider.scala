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

package com.normation.rudder.repository.ldap

import com.normation.NamedZioLogger
import com.normation.cfclerk.services.GitRepositoryProvider
import com.normation.cfclerk.services.GitRevisionProvider
import com.normation.inventory.ldap.core.LDAPConstants.A_OC
import com.normation.ldap.sdk.LDAPConnectionProvider
import com.normation.ldap.sdk.RwLDAPConnection
import com.normation.rudder.domain.RudderDit
import com.normation.rudder.domain.RudderLDAPConstants.A_TECHNIQUE_LIB_VERSION
import com.normation.rudder.domain.RudderLDAPConstants.OC_ACTIVE_TECHNIQUE_LIB_VERSION
import com.normation.rudder.repository.xml.GitFindUtils
import net.liftweb.common._
import org.eclipse.jgit.lib.ObjectId

import scalaz.zio._
import scalaz.zio.syntax._
import com.normation.errors._

/**
 *
 * A git revision provider which persists the current
 * commit into LDAP
 */
class LDAPGitRevisionProvider(
  ldap: LDAPConnectionProvider[RwLDAPConnection], rudderDit: RudderDit, gitRepo: GitRepositoryProvider, refPath: String) extends GitRevisionProvider with NamedZioLogger {

  if (!refPath.startsWith("refs/")) {
    logEffect.warn("The configured reference path for the Git repository of Active Technique Library does " +
      "not start with 'refs/'. Are you sure you don't mistype something ?")
  }

  private[this] var currentId = {
    val setID = for {
      id <- getAvailableRevTreeId
      _  <- setCurrentRevTreeId(id)
    } yield {
      id
    }

    //try to read from LDAP, and if we can't, returned the last available from git
    (for {
      con   <- ldap
      entry <- con.get(rudderDit.ACTIVE_TECHNIQUES_LIB.dn, A_TECHNIQUE_LIB_VERSION)
    } yield {
      entry.flatMap(_(A_TECHNIQUE_LIB_VERSION))
    }) foldM(
      err =>
        logPure.error(s"Error when trying to read persisted version of the current technique " +
          s"reference library revision to use. Using the last available from Git. Error was: ${err.fullMsg}") *> setID
    , res => res match {
      case Some(id) => IOResult.effect(ObjectId.fromString(id))
      case None =>
        logPure.info("No persisted version of the current technique reference library revision " +
          "to use where found, init to last available from Git repository") *> setID
    })
  }

  override def getAvailableRevTreeId: IOResult[ObjectId] = {
    GitFindUtils.findRevTreeFromRevString(gitRepo.db, refPath).catchAll(err =>
      logPure.error(err.fullMsg) *> Chained("Error when looking for a commit tree in git", err).fail
    )
  }

  override def currentRevTreeId = currentId

  override def setCurrentRevTreeId(id: ObjectId): IOResult[Unit] = {
    ldap.foreach { con =>
      con.get(rudderDit.ACTIVE_TECHNIQUES_LIB.dn, A_OC) match {
        case Left(_)| Right(None) => logEffect.error("The root entry of the user template library was not found, the current revision won't be persisted")
        case Right(Some(root)) =>
          root += (A_OC, OC_ACTIVE_TECHNIQUE_LIB_VERSION)
          root +=! (A_TECHNIQUE_LIB_VERSION, id.getName)
          con.save(root)
          () // unit is expected
      }
    }
    currentId = id
  }
}
