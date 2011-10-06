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

package com.normation.rudder.repository.ldap

import org.eclipse.jgit.lib.ObjectId
import com.normation.rudder.domain.RudderDit
import net.liftweb.common._
import org.eclipse.jgit.revwalk.RevWalk
import com.normation.exceptions.TechnicalException
import com.normation.rudder.domain.RudderLDAPConstants.{ A_REF_LIB_VERSION, OC_USER_LIB_VERSION }
import com.normation.ldap.sdk.LDAPConnectionProvider
import com.normation.inventory.ldap.core.LDAPConstants.A_OC
import com.normation.cfclerk.services.GitRevisionProvider
import com.normation.cfclerk.services.GitRepositoryProvider

/**
 *
 * A git revision provider which persists the current
 * commit into LDAP
 */
class LDAPGitRevisionProvider(
  ldap: LDAPConnectionProvider, rudderDit: RudderDit, gitRepo: GitRepositoryProvider, refPath: String) extends GitRevisionProvider with Loggable {

  if (!refPath.startsWith("refs/")) {
    logger.warn("The configured reference path for the Git repository of Policy Template User Library does " +
      "not start with 'refs/'. Are you sure you don't mistype something ?")
  }

  private[this] var currentId = {
    //try to read from LDAP, and if we can't, returned the last available from git 
    (for {
      con <- ldap
      entry <- con.get(rudderDit.POLICY_TEMPLATE_LIB.dn, A_REF_LIB_VERSION)
      refLib <- entry(A_REF_LIB_VERSION)
    } yield {
      refLib
    }) match {
      case Full(id) => ObjectId.fromString(id)
      case Empty =>
        logger.info("No persisted version of the current policy template reference library revision " +
          "to use where found, init to last available from Git repository")
        val id = getAvailableRevTreeId
        setCurrentRevTreeId(id)
        id
      case f: Failure =>
        logger.error("Error when trying to read persisted version of the current policy template " +
          "reference library revision to use. Use the last available from Git.", f)
        val id = getAvailableRevTreeId
        setCurrentRevTreeId(id)
        id
    }
  }

  override def getAvailableRevTreeId: ObjectId = {
    val tree = gitRepo.db.resolve(refPath)
    if (null == tree) {
      val message = "The reference branch '%s' is not found in the Policy Templates User Library's git repository".format(refPath)
      logger.error(message)
      throw new TechnicalException(message)
    }

    (new RevWalk(gitRepo.db)).parseTree(tree).getId
  }

  override def currentRevTreeId = currentId

  override def setCurrentRevTreeId(id: ObjectId): Unit = {
    ldap.foreach { con =>
      con.get(rudderDit.POLICY_TEMPLATE_LIB.dn, A_OC) match {
        case e: EmptyBox => logger.error("The root entry of the user template library was not found, the current revision won't be persisted")
        case Full(root) =>
          root += (A_OC, OC_USER_LIB_VERSION)
          root +=! (A_REF_LIB_VERSION, id.getName)
          con.save(root)
      }
    }
    currentId = id
  }
}