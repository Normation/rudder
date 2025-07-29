/*
 *************************************************************************************
 * Copyright 2023 Normation SAS
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

package com.normation.rudder.users

import com.normation.eventlog.EventActor
import com.normation.rudder.AuthorizationType
import com.normation.rudder.Rights
import com.normation.rudder.Role
import com.normation.rudder.api.ApiAccount
import com.normation.rudder.api.ApiAuthorization
import com.normation.rudder.facts.nodes.NodeSecurityContext
import com.normation.rudder.facts.nodes.QueryContext
import com.normation.rudder.users.UserPassword.HashedUserPassword
import java.util.Collection
import net.liftweb.http.RequestVar
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.UserDetails
import scala.jdk.CollectionConverters.*

/*
 * User related data structures related to authentication and bridging with Spring-security.
 * Base UserDetails and UserSession structure are defined in rudder-core.
 */

/**
 * Rudder user details must know if the account is for a
 * rudder user or an api account, and in the case of an
 * api account, what sub-case of it.
 */
sealed trait RudderAccount
object RudderAccount {
  final case class User(login: String, password: UserPassword) extends RudderAccount
  final case class Api(api: ApiAccount)                        extends RudderAccount
}

/**
 * We don't use at all Spring Authority to implements
 * our authorizations.
 * That because we want something more typed than String for
 * authority, and as a bonus, that allows to be able to switch
 * from Spring more easily
 *
 * So we have one Authority type known by Spring Security for
 * authenticated user: ROLE_USER
 * And one other for API accounts: ROLE_REMOTE
 */
sealed trait RudderAuthType {
  def grantedAuthorities: Collection[GrantedAuthority]
}

object RudderAuthType {
  // build a GrantedAuthority from the string
  private def buildAuthority(s: String): Collection[GrantedAuthority] = {
    Seq(new SimpleGrantedAuthority(s): GrantedAuthority).asJavaCollection
  }

  case object User extends RudderAuthType {
    override val grantedAuthorities: Collection[GrantedAuthority] = buildAuthority("ROLE_USER")
  }
  case object Api  extends RudderAuthType {
    override val grantedAuthorities: Collection[GrantedAuthority] = buildAuthority("ROLE_REMOTE")

    val apiRudderRights = Rights.NoRights
    val apiRudderRole: Set[Role] = Set(Role.NoRights)
  }
}

/**
 * Our model for user authentication and authorizations based on SpringSecurity UserDetails.
 * Used only during authentication and in authentication-backends plugins.
 * Note that authorizations are not managed by spring, but by the
 * 'authz' token of RudderUserDetail.
 * Don't make it final as SSO kind of authentication may need to extend it.
 */
case class RudderUserDetail(
    account:   RudderAccount,
    status:    UserStatus,
    roles:     Set[Role],
    apiAuthz:  ApiAuthorization,
    nodePerms: NodeSecurityContext
) extends UserDetails {
  // merge roles rights
  val authz: Rights = Rights(roles.flatMap(_.rights.authorizationTypes))

  val isAdmin: Boolean = {
    (AuthorizationType.AnyRights +: AuthorizationType.Administration.values).exists(authz.authorizationTypes.contains)
  }

  override val (getUsername, getPassword, getAuthorities) = account match {
    case RudderAccount.User(login, h: HashedUserPassword) => (login, h.exposeValue(), RudderAuthType.User.grantedAuthorities)
    case RudderAccount.User(login, _)                     => (login, "", RudderAuthType.User.grantedAuthorities)
    // We can default to "" as this value is ot used for authentication.
    case RudderAccount.Api(api)                           =>
      (api.name.value, api.token.flatMap(_.exposeHash()).getOrElse(""), RudderAuthType.Api.grantedAuthorities)
  }
  override val isAccountNonExpired                        = true
  override val isAccountNonLocked                         = true
  override val isCredentialsNonExpired                    = true
  override val isEnabled:                   Boolean = status == UserStatus.Active
  def checkRights(auth: AuthorizationType): Boolean = {
    if (authz.authorizationTypes.contains(AuthorizationType.NoRights)) false
    else if (authz.authorizationTypes.contains(AuthorizationType.AnyRights)) true
    else {
      auth match {
        case AuthorizationType.NoRights => false
        case _                          => authz.authorizationTypes.contains(auth)
      }
    }
  }
}

/**
 * An authenticated user with the relevant authentication information. That structure
 * will be kept in session (or for API, in the request processing).
 */
trait AuthenticatedUser {
  def user:    Option[RudderAccount.User]
  def account: Option[ApiAccount]
  def checkRights(auth: AuthorizationType): Boolean
  def getApiAuthz: ApiAuthorization
  final def actor: EventActor = EventActor((user, account) match {
    case (Some(user), _)    => user.login
    case (_, Some(account)) => account.name.value
    case (None, None)       => "unknown"
  })
  def nodePerms:   NodeSecurityContext

  def queryContext: QueryContext = {
    QueryContext(actor, nodePerms)
  }
}

/**
 * An utility class that stores the currently logged user (if any).
 * We can't rely only on SecurityContextHolder because Lift async (comet, at least)
 * uses a different thread local scope than the one used by spring/container to store
 * that info.
 * We can't use SessionVar because we sometimes need the info for stateless (before lift session
 * exits) requests.
 * We can't use ContainerVar because spring migrates jetty session and we don't want
 * to impose a MigratingSession to everything just to that variable.
 */
object CurrentUser extends RequestVar[Option[RudderUserDetail]](None) with AuthenticatedUser {
  // it's ok if that request var is not read in all/most request - but it must be
  // set in case it's needed.
  override def logUnreadVal = false

  def getRights: Rights = this.get match {
    case Some(u) => u.authz
    case None    => Rights.forAuthzs(AuthorizationType.NoRights)
  }

  def account: Option[ApiAccount] = this.get match {
    case Some(RudderUserDetail(RudderAccount.Api(apiAccount), _, _, _, _)) => Some(apiAccount)
    case _                                                                 => None
  }

  def user: Option[RudderAccount.User] = this.get match {
    case Some(RudderUserDetail(user: RudderAccount.User, _, _, _, _)) => Some(user)
    case _                                                            => None
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

  override def checkRights(auth: AuthorizationType): Boolean = {
    this.get match {
      case Some(u) => u.checkRights(auth)
      case None    => false
    }
  }
}
