/*
 *************************************************************************************
 * Copyright 2019 Normation SAS
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

import com.normation.rudder.Rights
import com.normation.rudder.Role
import com.normation.rudder.RudderAccount
import com.normation.rudder.api.ApiAuthorization
import com.normation.rudder.facts.nodes.NodeSecurityContext
import java.util.Collection
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.UserDetails
import scala.jdk.CollectionConverters._

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
    override val grantedAuthorities = buildAuthority("ROLE_USER")
  }
  case object Api  extends RudderAuthType {
    override val grantedAuthorities = buildAuthority("ROLE_REMOTE")

    val apiRudderRights = Rights.NoRights
    val apiRudderRole: Set[Role] = Set(Role.NoRights)
  }
}

/**
 * Our simple model for for user authentication and authorizations.
 * Note that authorizations are not managed by spring, but by the
 * 'authz' token of RudderUserDetail.
 * Don't make it final as SSO kind of authentication may need to extend it.
 */
case class RudderUserDetail(
    account:   RudderAccount,
    roles:     Set[Role],
    apiAuthz:  ApiAuthorization,
    nodePerms: NodeSecurityContext
) extends UserDetails {
  // merge roles rights
  val authz = Rights(roles.flatMap(_.rights.authorizationTypes))

  override val (getUsername, getPassword, getAuthorities) = account match {
    case RudderAccount.User(login, password) => (login, password, RudderAuthType.User.grantedAuthorities)
    case RudderAccount.Api(api)              => (api.name.value, api.token.value, RudderAuthType.Api.grantedAuthorities)
  }
  override val isAccountNonExpired                        = true
  override val isAccountNonLocked                         = true
  override val isCredentialsNonExpired                    = true
  override val isEnabled                                  = true
}
