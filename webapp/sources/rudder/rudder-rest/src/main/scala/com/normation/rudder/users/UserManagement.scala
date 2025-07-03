/*
 *************************************************************************************
 * Copyright 2018 Normation SAS
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

import com.normation.rudder.Rights
import com.normation.rudder.Role
import com.normation.rudder.Role.Custom
import com.normation.rudder.rest.ProviderRoleExtension
import com.normation.utils.DateFormaterService
import io.scalaland.chimney.Transformer
import io.scalaland.chimney.dsl.*
import net.liftweb.common.Logger
import org.joda.time.DateTime
import org.slf4j.LoggerFactory
import scala.xml.Node
import zio.json.*
import zio.json.ast.Json

case class UserFileInfo(userOrigin: List[User], digest: String)

case class User(username: String, password: String, permissions: Set[String], tenants: Option[String]) {
  def toNode: Node = <user name={username} password={password} permissions={permissions.mkString(",")} tenants={tenants.orNull}/>
}
object User                                                                                            {
  def make(username: String, password: String, permissions: Set[String], tenants: String): User = {
    User(username, password, permissions, if (tenants.isEmpty) None else Some(tenants))
  }
}

/**
 * Applicative log of interest for Rudder ops.
 */
object UserManagementLogger extends Logger {
  override protected def _logger = LoggerFactory.getLogger("usermanagement")
}

final case class UpdateUserFile(
    username:    String,
    password:    String,
    permissions: Set[String]
)

object UpdateUserFile {
  implicit val transformer: Transformer[UpdateUserFile, User] =
    Transformer.define[UpdateUserFile, User].enableOptionDefaultsToNone.buildTransformer
}

final case class UpdateUserInfo(
    name:      Option[String],
    email:     Option[String],
    otherInfo: Option[Json.Obj]
) {
  def isEmpty: Boolean = name.isEmpty && email.isEmpty && otherInfo.isEmpty
}

object Serialisation {

  implicit val dateTime:                     JsonEncoder[DateTime]              = JsonEncoder[String].contramap(DateFormaterService.serialize)
  implicit val userStatusEncoder:            JsonEncoder[UserStatus]            = JsonEncoder[String].contramap(_.value)
  implicit val providerRoleExtensionEncoder: JsonEncoder[ProviderRoleExtension] =
    JsonEncoder[String].contramap(_.name)
  implicit val passwordEncoderTypeEncoder:   JsonEncoder[PasswordEncoderType]   = JsonEncoder[String].contramap(_.name)

  implicit val updateUserInfoDecoder: JsonDecoder[UpdateUserInfo] = DeriveJsonDecoder.gen[UpdateUserInfo]
  implicit val updateUserInfoEncoder: JsonEncoder[UpdateUserInfo] = DeriveJsonEncoder.gen[UpdateUserInfo]

  implicit val jsonUserFormDataDecoder:       JsonDecoder[JsonUserFormData]       = DeriveJsonDecoder.gen[JsonUserFormData]
  implicit val jsonRoleAuthorizationsDecoder: JsonDecoder[JsonRoleAuthorizations] = DeriveJsonDecoder.gen[JsonRoleAuthorizations]

  implicit val jsonRightsEncoder:           JsonEncoder[JsonRights]           =
    JsonEncoder[List[String]].contramap(_.authorizationTypes.toList.sorted)
  implicit val jsonRolesEncoder:            JsonEncoder[JsonRoles]            = JsonEncoder[Set[String]].contramap(_.roles)
  implicit val jsonProviderInfoEncoder:     JsonEncoder[JsonProviderInfo]     = DeriveJsonEncoder.gen[JsonProviderInfo]
  implicit val jsonUserEncoder:             JsonEncoder[JsonUser]             = DeriveJsonEncoder.gen[JsonUser]
  implicit val jsonUpdatedUserInfoEncoder:  JsonEncoder[JsonUpdatedUserInfo]  = DeriveJsonEncoder.gen[JsonUpdatedUserInfo]
  implicit val jsonStatusEncoder:           JsonEncoder[JsonStatus]           = DeriveJsonEncoder.gen[JsonStatus]
  implicit val jsonProviderPropertyEncoder: JsonEncoder[JsonProviderProperty] = DeriveJsonEncoder.gen[JsonProviderProperty]
  implicit val jsonAuthConfigEncoder:       JsonEncoder[JsonAuthConfig]       = DeriveJsonEncoder.gen[JsonAuthConfig]
  implicit val jsonRoleEncoder:             JsonEncoder[JsonRole]             = DeriveJsonEncoder.gen[JsonRole]
  implicit val jsonInternalUserDataEncoder: JsonEncoder[JsonInternalUserData] = DeriveJsonEncoder.gen[JsonInternalUserData]
  implicit val jsonAddedUserDataEncoder:    JsonEncoder[JsonAddedUserData]    = DeriveJsonEncoder.gen[JsonAddedUserData]
  implicit val jsonAddedUserEncoder:        JsonEncoder[JsonAddedUser]        = DeriveJsonEncoder.gen[JsonAddedUser]
  implicit val jsonUpdatedUserEncoder:      JsonEncoder[JsonUpdatedUser]      = DeriveJsonEncoder.gen[JsonUpdatedUser]
  implicit val jsonUsernameEncoder:         JsonEncoder[JsonUsername]         = DeriveJsonEncoder.gen[JsonUsername]
  implicit val jsonDeletedUserEncoder:      JsonEncoder[JsonDeletedUser]      = DeriveJsonEncoder.gen[JsonDeletedUser]
  implicit val jsonReloadStatusEncoder:     JsonEncoder[JsonReloadStatus]     = DeriveJsonEncoder.gen[JsonReloadStatus]
  implicit val jsonReloadResultEncoder:     JsonEncoder[JsonReloadResult]     = DeriveJsonEncoder.gen[JsonReloadResult]
  implicit val jsonRoleCoverageEncoder:     JsonEncoder[JsonRoleCoverage]     = DeriveJsonEncoder.gen[JsonRoleCoverage]
  implicit val jsonCoverageEncoder:         JsonEncoder[JsonCoverage]         = DeriveJsonEncoder.gen[JsonCoverage]
}

final case class JsonAuthConfig(
    roleListOverride:       ProviderRoleExtension,
    authenticationBackends: Set[String],
    providerProperties:     Map[String, JsonProviderProperty],
    users:                  List[JsonUser],
    tenantsEnabled:         Boolean,
    digest:                 PasswordEncoderType = PasswordEncoderType.DEFAULT // default value
)

final case class JsonProviderProperty(
    @jsonField("roleListOverride") providerRoleExtension: ProviderRoleExtension
)
object JsonProviderProperty {
  implicit val transformer: Transformer[ProviderRoleExtension, JsonProviderProperty] = roleExtension =>
    JsonProviderProperty(providerRoleExtension = roleExtension)
}

final case class JsonRoles(
    roles: Set[String]
) extends AnyVal {
  def ++(other: JsonRoles): JsonRoles = JsonRoles(roles ++ other.roles)
}

object JsonRoles {
  val empty: JsonRoles = JsonRoles(Set.empty)
}

// Mapping of Rights
final case class JsonRights(
    authorizationTypes: Set[String]
) extends AnyVal {
  def ++(other: JsonRights): JsonRights = JsonRights(authorizationTypes ++ other.authorizationTypes)
}

object JsonRights {
  implicit val transformer: Transformer[Rights, JsonRights] = {
    case rights if rights == Rights.NoRights => JsonRights.empty
    case rights                              => JsonRights(rights.authorizationTypes.map(_.id))
  }

  // We don't want to send "no_rights" for now, as it is not yet handled back as an empty set of rights when updating a user
  val empty:     JsonRights = JsonRights(Set.empty)
  val AnyRights: JsonRights = Rights.AnyRights.transformInto[JsonRights]
}

final case class JsonProviderInfo(
    provider:     String,
    authz:        JsonRights,
    roles:        JsonRoles,
    customRights: JsonRights
)

object JsonProviderInfo {
  def fromUser(u: RudderUserDetail, provider: String)(implicit allRoles: Set[Role]): JsonProviderInfo = {
    val (_, customUserRights) = {
      UserManagementService
        .computeRoleCoverage(allRoles, u.authz.authorizationTypes)
        .getOrElse(Set.empty)
        .partitionMap {
          case Custom(customRights) => Right(customRights.authorizationTypes)
          case r                    => Left(r)
        }
    }

    // custom anonymous roles and permissions are already inside roleCoverage and customRights fields
    val roles = u.roles.filter {
      case _: Custom => false
      case _ => true
    }.map(_.name)

    JsonProviderInfo(
      provider,
      u.authz.transformInto[JsonRights],
      JsonRoles(roles),
      Rights(customUserRights.flatten).transformInto[JsonRights]
    )
  }
}

final case class JsonUser(
    @jsonField("login") id:          String,
    name:                            Option[String],
    email:                           Option[String],
    otherInfo:                       Json.Obj,
    status:                          UserStatus,
    authz:                           JsonRights,
    @jsonField("permissions") roles: JsonRoles,
    rolesCoverage:                   JsonRoles,
    customRights:                    JsonRights,
    providers:                       List[String],
    providersInfo:                   Map[String, JsonProviderInfo],
    tenants:                         String,
    lastLogin:                       Option[DateTime],
    previousLogin:                   Option[DateTime]
) {
  def merge(providerInfo: JsonProviderInfo): JsonUser = {
    JsonUser(
      id,
      name,
      email,
      otherInfo,
      status,
      providersInfo + (providerInfo.provider -> providerInfo),
      tenants,
      lastLogin,
      previousLogin
    )
  }

  /**
    * Only add the provider info but do not take it into account in roles and rights
    */
  def addProviderInfo(providerInfo: JsonProviderInfo): JsonUser = {
    // TODO: The list is not ordered so we can just append
    copy(providers = providers :+ providerInfo.provider, providersInfo = providersInfo + (providerInfo.provider -> providerInfo))
  }

  /**
    * Compute the role coverage, provided a current user and all known roles.
    * Roles will not be changed so it should be computed on a JsonUser where all users roles are already there.
    */
  def withRoleCoverage(u: RudderUserDetail)(implicit allRoles: Set[Role]): JsonUser = {
    val (allUserRoles, customUserRights) = {
      UserManagementService
        .computeRoleCoverage(allRoles, u.authz.authorizationTypes)
        .getOrElse(Set.empty)
        .partitionMap {
          case Custom(customRights) => Right(customRights.authorizationTypes)
          case r                    => Left(r)
        }
    }

    copy(
      rolesCoverage = JsonRoles(allUserRoles.map(_.name)),
      customRights = Rights(customUserRights.flatten).transformInto[JsonRights]
    )
  }
}

object JsonUser {
  implicit private[JsonUser] val roleTransformer: Transformer[Role, String] = _.name

  def noRights(
      username:      String,
      name:          Option[String],
      email:         Option[String],
      otherInfo:     Json.Obj,
      status:        UserStatus,
      providersInfo: Map[String, JsonProviderInfo],
      tenants:       String,
      lastLogin:     Option[DateTime],
      previousLogin: Option[DateTime]
  ): JsonUser = {
    JsonUser(
      username,
      name,
      email,
      otherInfo,
      status,
      JsonRights.empty,
      JsonRoles.empty,
      JsonRoles.empty,
      JsonRights.empty,
      providersInfo.keys.toList,
      providersInfo,
      tenants,
      lastLogin,
      previousLogin
    )
  }
  def anyRights(
      username:      String,
      name:          Option[String],
      email:         Option[String],
      otherInfo:     Json.Obj,
      status:        UserStatus,
      providersInfo: Map[String, JsonProviderInfo],
      tenants:       String,
      lastLogin:     Option[DateTime],
      previousLogin: Option[DateTime]
  ): JsonUser = {
    JsonUser(
      username,
      name,
      email,
      otherInfo,
      status,
      JsonRights.AnyRights,
      JsonRoles(Set(Role.Administrator.name)),
      JsonRoles(Set(Role.Administrator.name)),
      JsonRights.empty,
      providersInfo.keys.toList,
      providersInfo,
      tenants,
      lastLogin,
      previousLogin
    )
  }

  // Main constructor which aggregates providers info to merge all serialized roles and authz
  def apply(
      id:            String,
      name:          Option[String],
      email:         Option[String],
      otherInfo:     Json.Obj,
      status:        UserStatus,
      providersInfo: Map[String, JsonProviderInfo],
      tenants:       String,
      lastLogin:     Option[DateTime],
      previousLogin: Option[DateTime]
  ): JsonUser = {
    val authz        = providersInfo.values.map(_.authz).foldLeft(JsonRights.empty)(_ ++ _)
    val roles        = providersInfo.values.map(_.roles).foldLeft(JsonRoles.empty)(_ ++ _)
    val customRights = providersInfo.values.map(_.customRights).foldLeft(JsonRights.empty)(_ ++ _)

    JsonUser(
      id,
      name,
      email,
      otherInfo,
      status,
      authz,
      roles,
      roles,
      customRights,
      providersInfo.keys.toList,
      providersInfo,
      tenants,
      lastLogin,
      previousLogin
    )
  }
}

final case class JsonRole(
    @jsonField("id") name: String,
    rights:                List[String]
)

final case class JsonReloadResult(reload: JsonReloadStatus)

object JsonReloadResult  {
  val Done = JsonReloadResult(JsonReloadStatus("Done"))
}
final case class JsonReloadStatus(status: String)

final case class JsonAddedUserData(
    username:    String,
    password:    String,
    permissions: List[String],
    name:        Option[String],
    email:       Option[String],
    otherInfo:   Option[Json.Obj]
)
object JsonAddedUserData {
  implicit val transformer: Transformer[JsonUserFormData, JsonAddedUserData] =
    Transformer.derive[JsonUserFormData, JsonAddedUserData]
}

final case class JsonInternalUserData(
    username:    String,
    password:    String,
    permissions: List[String]
)

object JsonInternalUserData {
  implicit val transformer: Transformer[User, JsonInternalUserData] = Transformer.derive[User, JsonInternalUserData]
}

final case class JsonAddedUser(
    addedUser: JsonAddedUserData
)
object JsonAddedUser        {
  implicit val transformer: Transformer[JsonUserFormData, JsonAddedUser] = (u: JsonUserFormData) =>
    JsonAddedUser(u.transformInto[JsonAddedUserData])
}

final case class JsonUpdatedUser(
    updatedUser: JsonInternalUserData
)
object JsonUpdatedUser      {
  implicit val transformer: Transformer[User, JsonUpdatedUser] = (u: User) =>
    JsonUpdatedUser(u.transformInto[JsonInternalUserData])
}

final case class JsonUpdatedUserInfo(
    updatedUser: UpdateUserInfo
)
object JsonUpdatedUserInfo  {
  implicit val transformer: Transformer[UpdateUserInfo, JsonUpdatedUserInfo] =
    JsonUpdatedUserInfo(_)
}

final case class JsonUsername(
    username: String
)

final case class JsonDeletedUser(
    deletedUser: JsonUsername
)
object JsonDeletedUser      {
  implicit val usernameTransformer: Transformer[String, JsonUsername]    = JsonUsername(_)
  implicit val transformer:         Transformer[String, JsonDeletedUser] = (s: String) => JsonDeletedUser(s.transformInto[JsonUsername])
}

final case class JsonStatus(
    status: UserStatus
)

final case class JsonUserFormData(
    username:    String,
    password:    String,
    permissions: List[String],
    isPreHashed: Boolean,
    name:        Option[String],
    email:       Option[String],
    otherInfo:   Option[Json.Obj]
)

object JsonUserFormData {
  implicit val transformer:           Transformer[JsonUserFormData, User]           =
    Transformer.define[JsonUserFormData, User].enableOptionDefaultsToNone.buildTransformer
  implicit val transformerUpdateUser: Transformer[JsonUserFormData, UpdateUserFile] =
    Transformer.derive[JsonUserFormData, UpdateUserFile]
}

final case class JsonCoverage(
    coverage: JsonRoleCoverage
)
object JsonCoverage     {
  implicit val transformer: Transformer[(Set[Role], Set[Custom]), JsonCoverage] = (x: (Set[Role], Set[Custom])) =>
    x.transformInto[JsonRoleCoverage].transformInto[JsonCoverage](coverage => JsonCoverage(coverage))
}

final case class JsonRoleCoverage(
    permissions: Set[String],
    custom:      List[String]
)

object JsonRoleCoverage {
  implicit private[JsonRoleCoverage] val roleTransformer:        Transformer[Role, String]              = _.name
  implicit private[JsonRoleCoverage] val customRolesTransformer: Transformer[Set[Custom], List[String]] =
    _.flatMap(_.rights.authorizationTypes.map(_.id)).toList.sorted

  implicit val transformer: Transformer[(Set[Role], Set[Custom]), JsonRoleCoverage] =
    Transformer.derive[(Set[Role], Set[Custom]), JsonRoleCoverage]
}

final case class JsonRoleAuthorizations(
    permissions: List[String],
    authz:       List[String]
)
