/*
 *************************************************************************************
 * Copyright 2017 Normation SAS
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

package com.normation.rudder.rest

import cats.syntax.all.*
import com.normation.rudder.AuthorizationType
import com.normation.rudder.Rights
import com.normation.rudder.Role
import com.normation.rudder.api.ApiAclElement
import com.normation.rudder.api.ApiAuthorization as ApiAuthz

/*
 * The goal of that class is to map Authorization to what API
 * they give access.
 * At some point, we will need AuthorizationType to be extensible, so
 * that a new module can provide new authorization. For now, a plugin
 * need to map its authorizations on the existing ones.
 */
trait AuthorizationApiMapping {
  def mapAuthorization(authz: AuthorizationType): List[ApiAclElement]
}

class AuthorizationMappingListEndpoint(endpoints: List[EndpointSchema]) extends AuthorizationApiMapping {
  private val acls: Map[AuthorizationType, List[ApiAclElement]] =
    endpoints.flatMap(e => e.authz.map(a => (a, AuthzForApi(e)))).groupMap(_._1)(_._2)

  private val otherAcls: Map[AuthorizationType, List[ApiAclElement]] =
    endpoints.foldMap(_.otherAcls)

  // merge maps using List monoid to add acls for the same authz
  private val allAcls: Map[AuthorizationType, List[ApiAclElement]] = acls |+| otherAcls

  override def mapAuthorization(authz: AuthorizationType): List[ApiAclElement] = {
    allAcls.get(authz).getOrElse(Nil)

  }
}

/*
 * An extensible mapper that allows for plugins to contribute to
 * its mapper
 */
class ExtensibleAuthorizationApiMapping(base: List[AuthorizationApiMapping]) extends AuthorizationApiMapping {

  private var mappers: List[AuthorizationApiMapping] = base

  def addMapper(mapper: AuthorizationApiMapping): Unit = {
    mappers = mappers :+ mapper
  }

  override def mapAuthorization(authz: AuthorizationType): List[ApiAclElement] = {
    import AuthorizationType.*
    authz match {
      case NoRights  => Nil
      case AnyRights => ApiAuthz.allAuthz.acl
      case _         =>
        mappers.flatMap(_.mapAuthorization(authz))
    }
  }
}

object AuthorizationApiMapping {
  implicit class ToAuthz(val api: EndpointSchema) extends AnyVal {
    def x: ApiAclElement = AuthzForApi(api)
  }

}

/*
 * This class keep the mapping between a role and the list
 * of authorization it gets on all endpoints
 */
class RoleApiMapping(mapper: AuthorizationApiMapping) {

  // get the access control list from the user rights.
  // Always succeeds,
  def getApiAclFromRights(rights: Rights): List[ApiAclElement] = {
    // we have two shortbreakers, no rights and all rights
    if (rights.authorizationTypes.contains(AuthorizationType.NoRights)) {
      Nil
    } else if (rights.authorizationTypes.contains(AuthorizationType.AnyRights)) {
      ApiAuthz.allAuthz.acl
    } else {
      import cats.implicits.*
      // problem: here, rights.authorizationTypes is a set, so not ordered. Acl ARE
      // ordered. But this is OK **IF** we don't user any double joker (exhaustive match)
      mergeToAcl(rights.authorizationTypes.toList.foldMap(mapAuthorization))
    }
  }

  def getApiAclFromRoles(roles: Seq[Role]): List[ApiAclElement] = {
    getApiAclFromRights(Rights(roles.flatMap(_.rights.authorizationTypes)))
  }

  // a merge function that groups action for identical path
  def mergeToAcl(authz: List[ApiAclElement]): List[ApiAclElement] = {
    authz
      .groupBy(_.path)
      .map {
        case (path, seq) =>
          ApiAclElement(path, seq.flatMap(_.actions).toSet)
      }
      .toList
  }

  def mapAuthorization(authz: AuthorizationType): List[ApiAclElement] = {
    mapper.mapAuthorization(authz)
  }
}

/**
  * A role extension that can be used to define the priority of how roles should be composed from different role providers.
  */
sealed trait ProviderRoleExtension {
  def name:     String
  def priority: Int = this match {
    case ProviderRoleExtension.None         => 0
    case ProviderRoleExtension.NoOverride   => 1
    case ProviderRoleExtension.WithOverride => 2
  }
}
object ProviderRoleExtension       {
  case object None         extends ProviderRoleExtension { override val name: String = "none"        }
  case object NoOverride   extends ProviderRoleExtension { override val name: String = "no-override" }
  case object WithOverride extends ProviderRoleExtension { override val name: String = "override"    }

  implicit val ordering: Ordering[ProviderRoleExtension] = Ordering.by(_.priority)
}
