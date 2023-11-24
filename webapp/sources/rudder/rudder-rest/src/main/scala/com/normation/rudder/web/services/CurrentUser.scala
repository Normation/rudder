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

package com.normation.rudder.web.services

import com.normation.rudder.AuthorizationType
import com.normation.rudder.Rights
import com.normation.rudder.RudderAccount
import com.normation.rudder.User
import com.normation.rudder.api.ApiAuthorization
import com.normation.rudder.facts.nodes.NodeSecurityContext
import com.normation.rudder.facts.nodes.QueryContext
import net.liftweb.http.SessionVar
import org.springframework.security.core.context.SecurityContextHolder

/**
 * An utility class that get the currently logged user
 * (if any)
 *
 */
object CurrentUser extends SessionVar[Option[RudderUserDetail]]({
      SecurityContextHolder.getContext.getAuthentication match {
        case null => None
        case auth =>
          auth.getPrincipal match {
            case u: RudderUserDetail => Some(u)
            case _ => None
          }
      }

    }) with User {

  def getRights: Rights = this.get match {
    case Some(u) => u.authz
    case None    => Rights.forAuthzs(AuthorizationType.NoRights)
  }

  def account: RudderAccount = this.get match {
    case None    => RudderAccount.User("unknown", "")
    case Some(u) => u.account
  }

  def checkRights(auth: AuthorizationType): Boolean = {
    val authz = getRights.authorizationTypes
    if (authz.contains(AuthorizationType.NoRights)) false
    else if (authz.contains(AuthorizationType.AnyRights)) true
    else {
      auth match {
        case AuthorizationType.NoRights => false
        case _                          => authz.contains(auth)
      }
    }
  }

  def getApiAuthz: ApiAuthorization = {
    this.get match {
      case None    => ApiAuthorization.None
      case Some(u) => u.apiAuthz
    }
  }

  def nodePerms: NodeSecurityContext = this.get match {
    case Some(u) => u.nodePerms
    case None    => NodeSecurityContext.None
  }

  def queryContext: QueryContext = QueryContext(actor, nodePerms)
}
