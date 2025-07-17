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

package com.normation.rudder.rest.lift

import com.normation.errors.*
import com.normation.rudder.AuthorizationType
import com.normation.rudder.Role
import com.normation.rudder.Role.Custom
import com.normation.rudder.RudderRoles
import com.normation.rudder.api.ApiAuthorization
import com.normation.rudder.api.ApiVersion
import com.normation.rudder.api.HttpAction.DELETE
import com.normation.rudder.api.HttpAction.GET
import com.normation.rudder.api.HttpAction.POST
import com.normation.rudder.api.HttpAction.PUT
import com.normation.rudder.apidata.ZioJsonExtractor
import com.normation.rudder.facts.nodes.NodeSecurityContext
import com.normation.rudder.rest.*
import com.normation.rudder.rest.EndpointSchema.syntax.*
import com.normation.rudder.rest.implicits.*
import com.normation.rudder.tenants.TenantService
import com.normation.rudder.users.EventTrace
import com.normation.rudder.users.FileUserDetailListProvider
import com.normation.rudder.users.JsonAddedUser
import com.normation.rudder.users.JsonAuthConfig
import com.normation.rudder.users.JsonCoverage
import com.normation.rudder.users.JsonDeletedUser
import com.normation.rudder.users.JsonProviderInfo
import com.normation.rudder.users.JsonProviderProperty
import com.normation.rudder.users.JsonReloadResult
import com.normation.rudder.users.JsonRights
import com.normation.rudder.users.JsonRole
import com.normation.rudder.users.JsonRoleAuthorizations
import com.normation.rudder.users.JsonRoles
import com.normation.rudder.users.JsonStatus
import com.normation.rudder.users.JsonUpdatedUser
import com.normation.rudder.users.JsonUpdatedUserInfo
import com.normation.rudder.users.JsonUser
import com.normation.rudder.users.JsonUserFormData
import com.normation.rudder.users.RudderAccount
import com.normation.rudder.users.RudderPasswordEncoder.SecurityLevel
import com.normation.rudder.users.RudderUserDetail
import com.normation.rudder.users.Serialisation.*
import com.normation.rudder.users.UpdateUserInfo
import com.normation.rudder.users.User
import com.normation.rudder.users.UserInfo
import com.normation.rudder.users.UserManagementService
import com.normation.rudder.users.UserRepository
import com.normation.rudder.users.UserSession
import com.normation.rudder.users.UserStatus
import enumeratum.*
import io.scalaland.chimney.Transformer
import io.scalaland.chimney.dsl.*
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import org.joda.time.DateTime
import sourcecode.Line
import zio.ZIO
import zio.syntax.*

/*
 * This file contains the public API for user management, which also serves for the user management page
 */
sealed trait UserManagementApi extends EnumEntry with EndpointSchema with GeneralApi with SortIndex

object UserManagementApi extends Enum[UserManagementApi] with ApiModuleProvider[UserManagementApi] {

  final case object GetUserInfo extends UserManagementApi with ZeroParam with StartsAtVersion10 {
    val z              = implicitly[Line].value
    val description    = "Get information about registered users in Rudder"
    val (action, path) = GET / "usermanagement" / "users"

    override def authz:         List[AuthorizationType] = List(AuthorizationType.Administration.Read)
    override def dataContainer: Option[String]          = None
  }

  final case object GetRoles extends UserManagementApi with ZeroParam with StartsAtVersion10 {
    val z              = implicitly[Line].value
    val description    = "Get roles and their authorizations"
    val (action, path) = GET / "usermanagement" / "roles"

    override def authz:         List[AuthorizationType] = List(AuthorizationType.Administration.Read)
    override def dataContainer: Option[String]          = None
  }

  /*
   * This one does not return the list of users so that it can allow script integration
   * but without revealing the actual list of users.
   */
  final case object ReloadUsersConf extends UserManagementApi with ZeroParam with StartsAtVersion10 {
    val z              = implicitly[Line].value
    val description    = "Reload (read again rudder-users.xml and process result) information about registered users in Rudder"
    val (action, path) = POST / "usermanagement" / "users" / "reload"

    override def dataContainer: Option[String] = None
  }

  final case object DeleteUser extends UserManagementApi with OneParam with StartsAtVersion10 {
    val z              = implicitly[Line].value
    val description    = "Delete a user from the system"
    val (action, path) = DELETE / "usermanagement" / "{username}"

    override def dataContainer: Option[String] = None
  }

  final case object AddUser extends UserManagementApi with ZeroParam with StartsAtVersion10 {
    val z              = implicitly[Line].value
    val description    = "Add a user with his information and privileges"
    val (action, path) = POST / "usermanagement"

    override def dataContainer: Option[String] = None
  }

  final case object UpdateUser extends UserManagementApi with OneParam with StartsAtVersion10 {
    val z              = implicitly[Line].value
    val description    = "Update user's administration fields"
    val (action, path) = POST / "usermanagement" / "update" / "{username}"

    override def dataContainer: Option[String] = None
  }

  final case object UpdateUserInfo extends UserManagementApi with OneParam with StartsAtVersion10 {
    val z              = implicitly[Line].value
    val description    = "Update user's information"
    val (action, path) = POST / "usermanagement" / "update" / "info" / "{username}"

    override def dataContainer: Option[String] = None
  }

  final case object ActivateUser extends UserManagementApi with OneParam with StartsAtVersion10 {
    val z              = implicitly[Line].value
    val description    = "Activate a user"
    val (action, path) = PUT / "usermanagement" / "status" / "activate" / "{username}"

    override def dataContainer: Option[String] = None
  }

  final case object DisableUser extends UserManagementApi with OneParam with StartsAtVersion10 {
    val z              = implicitly[Line].value
    val description    = "Disable a user"
    val (action, path) = PUT / "usermanagement" / "status" / "disable" / "{username}"

    override def dataContainer: Option[String] = None
  }

  final case object RoleCoverage extends UserManagementApi with OneParam with StartsAtVersion10 {
    val z              = implicitly[Line].value
    val description    = "Get the coverage of roles over rights"
    val (action, path) = POST / "usermanagement" / "coverage" / "{username}"

    override def dataContainer: Option[String] = None
  }

  def endpoints = values.toList.sortBy(_.z)
  def values    = findValues
}

class UserManagementApiImpl(
    userRepo:                  UserRepository,
    userService:               FileUserDetailListProvider,
    userManagementService:     UserManagementService,
    roleApiMapping:            RoleApiMapping,
    tenantsService:            TenantService,
    getProviderRoleExtensions: () => Map[String, ProviderRoleExtension],
    getAuthBackendsProviders:  () => Set[String]
) extends LiftApiModuleProvider[UserManagementApi] {
  api =>
  import UserManagementApiImpl.*

  override def schemas: ApiModuleProvider[UserManagementApi] = UserManagementApi

  override def getLiftEndpoints(): List[LiftApiModule] = {
    UserManagementApi.endpoints.map {
      case UserManagementApi.GetUserInfo     => GetUserInfo
      case UserManagementApi.ReloadUsersConf => ReloadUsersConf
      case UserManagementApi.AddUser         => AddUser
      case UserManagementApi.DeleteUser      => DeleteUser
      case UserManagementApi.UpdateUser      => UpdateUser
      case UserManagementApi.UpdateUserInfo  => UpdateUserInfo
      case UserManagementApi.ActivateUser    => ActivateUser
      case UserManagementApi.DisableUser     => DisableUser
      case UserManagementApi.RoleCoverage    => RoleCoverage
      case UserManagementApi.GetRoles        => GetRoles
    }.toList
  }

  /*
   * Return a Json Object that list users with their authorizations
   */
  object GetUserInfo extends LiftApiModule0 {
    override val schema: UserManagementApi.GetUserInfo.type = UserManagementApi.GetUserInfo

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {

      (for {
        users     <- userRepo.getAll()
        allRoles  <- RudderRoles.getAllRoles
        file       = userService.authConfig
        roles      = allRoles.values.toSet
        jsonUsers <-
          ZIO
            .foreach(users)(u => {
              implicit val currentRoles: Set[Role] = roles
              // we take last session to get last known roles and authz of the user
              // we need to merge at the level of JsonUser because last session roles are just String

              userRepo
                .getLastPreviousLogin(u.id, closedSessionsOnly = false)
                .flatMap { // Query may return both closed and opened sessions: get "lastClosedSession" when last session is still opened
                  case last @ Some(lastSession) => {
                    if (lastSession.isOpen) {
                      userRepo
                        .getLastPreviousLogin(u.id, closedSessionsOnly = true)
                        .map((_, last))
                    } else {
                      (last, last).succeed
                    }
                  }
                  case None                     =>
                    (None, None).succeed
                }
                .map {
                  case (lastClosedSession, lastSession) =>
                    implicit val previousLogin: Option[DateTime] = lastClosedSession.map(_.creationDate)

                    // depending on provider property configuration, we should merge or override roles
                    val mainProviderRoleExtension = getProviderRoleExtensions().get(u.managedBy)

                    val defaultUser            = {
                      RudderUserDetail(
                        RudderAccount.User(u.id, ""),
                        u.status,
                        Set(),
                        ApiAuthorization.None,
                        NodeSecurityContext.None
                      )
                    }
                    val userWithoutPermissions = transformUser(
                      defaultUser,
                      u,
                      Map(u.managedBy -> JsonProviderInfo.fromUser(defaultUser, u.managedBy)),
                      lastSession.map(_.creationDate)
                    )

                    file.users.get(u.id) match {
                      case None    => {
                        // we still need to consider one role extension case : if provider cannot define roles, user cannot have any role
                        mainProviderRoleExtension match {
                          case Some(ProviderRoleExtension.None) => userWithoutPermissions
                          case _                                =>
                            transformProvidedUser(
                              u,
                              NodeSecurityContext.All,
                              lastSession
                            ) // default value for tenants is "all" if not in file
                        }
                      }
                      case Some(x) => {
                        // we need to update the status to the latest one from database
                        val currentUserDetails = x.copy(status = u.status)

                        // since file definition does not depend on session use the file user as base for file-managed users
                        val fileProviderInfo = JsonProviderInfo.fromUser(x, "file")

                        if (u.managedBy == "file") {
                          transformUser(
                            currentUserDetails,
                            u,
                            Map(fileProviderInfo.provider -> fileProviderInfo),
                            lastSession.map(_.creationDate)
                          ).withRoleCoverage(currentUserDetails)
                        } else {
                          // we need to merge the two users, the one from the file and the one from the session
                          mainProviderRoleExtension match {
                            case Some(ProviderRoleExtension.WithOverride) =>
                              // Do not recompute roles nor roles coverage, because file roles are overridden by provider roles
                              transformProvidedUser(u, currentUserDetails.nodePerms, lastSession)
                                .addProviderInfo(fileProviderInfo)
                            case Some(ProviderRoleExtension.NoOverride)   =>
                              // Merge the previous session roles with the file roles and recompute role coverage over the merge result
                              transformProvidedUser(u, currentUserDetails.nodePerms, lastSession)
                                .merge(fileProviderInfo)
                                .withRoleCoverage(currentUserDetails)
                            case Some(ProviderRoleExtension.None)         =>
                              // Ignore the session roles which may have previously been saved with another role extension mode
                              userWithoutPermissions.merge(fileProviderInfo).withRoleCoverage(currentUserDetails)
                            case None                                     =>
                              // Provider no longer known, fallback to file provider
                              transformUser(
                                currentUserDetails,
                                u,
                                Map(fileProviderInfo.provider -> fileProviderInfo),
                                lastSession.map(_.creationDate)
                              ).withRoleCoverage(currentUserDetails)
                          }
                        }
                      }
                    }
                }
            })
      } yield {
        serialize(jsonUsers.sortBy(_.id))
      }).chainError("Error when retrieving user list").toLiftResponseOne(params, schema, _ => None)
    }
  }

  object GetRoles extends LiftApiModule0 {
    val schema: UserManagementApi.GetRoles.type = UserManagementApi.GetRoles

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      (for {
        allRoles <- RudderRoles.getAllRoles
        roles     = allRoles.values.toList

        json = roles.map(role => {
                 val displayAuthz = role.rights.authorizationTypes.map(_.id).toList.sorted
                 val authz_all    = displayAuthz
                   .map(_.split("_").head)
                   .map(authz => if (displayAuthz.count(_.split("_").head == authz) == 3) s"${authz}_all" else authz)
                   .filter(_.contains("_"))
                   .distinct
                 val authz_type   = displayAuthz.filter(x => !authz_all.map(_.split("_").head).contains(x.split("_").head))
                 JsonRole(role.name, authz_type ++ authz_all)
               })

      } yield {
        json
      }).toLiftResponseOne(params, schema, _ => None)
    }
  }

  object ReloadUsersConf extends LiftApiModule0 {
    override val schema: UserManagementApi.ReloadUsersConf.type = UserManagementApi.ReloadUsersConf

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      (for {
        _ <- reload()
      } yield {
        JsonReloadResult.Done
      }).chainError("Could not reload user's configuration")
        .toLiftResponseOne(params, schema, _ => None)
    }
  }

  private def reload(): IOResult[Unit] = {
    userService.reloadPure().chainError("Error when trying to reload the list of users from 'rudder-users.xml' file")
  }

  object AddUser extends LiftApiModule0 {
    val schema: UserManagementApi.AddUser.type = UserManagementApi.AddUser

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {

      (for {
        user <- ZioJsonExtractor.parseJson[JsonUserFormData](req).toIO
        _    <- ZIO.when(userService.authConfig.users.keySet contains user.username) {
                  Inconsistency(s"User '${user.username}' already exists").fail
                }
        _    <-
          userManagementService
            .add(user.transformInto[User], user.isPreHashed)
        _    <- userManagementService.updateInfo(user.username, user.transformInto[UpdateUserInfo])
      } yield {
        user.transformInto[JsonAddedUser]
      }).chainError("Could not add user").toLiftResponseOne(params, schema, _ => None)
    }
  }

  object DeleteUser extends LiftApiModule {
    val schema: UserManagementApi.DeleteUser.type = UserManagementApi.DeleteUser

    def process(
        version:    ApiVersion,
        path:       ApiPath,
        id:         String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {

      (for {
        _ <- userManagementService.remove(id, authzToken.qc.actor, "User deleted by user management API")
      } yield {
        id.transformInto[JsonDeletedUser]
      }).chainError(s"Could not delete user ${id}").toLiftResponseOne(params, schema, _ => None)
    }
  }

  object UpdateUser extends LiftApiModule {
    val schema: UserManagementApi.UpdateUser.type = UserManagementApi.UpdateUser

    def process(
        version:    ApiVersion,
        path:       ApiPath,
        id:         String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      (for {
        u          <- ZioJsonExtractor.parseJson[JsonUserFormData](req).toIO
        permissions = u.permissions.map(_.filter(_ != AuthorizationType.NoRights.id))
        allRoles   <- RudderRoles.getAllRoles
        // We ignore the "user info" part of the request when updating
        _          <-
          userManagementService.update(id, u.username, u.password, permissions, u.isPreHashed)(
            allRoles
          )
      } yield {
        u.copy(permissions = permissions).transformInto[User].transformInto[JsonUpdatedUser]
      }).chainError(s"Could not update user '${id}'").toLiftResponseOne(params, schema, _ => None)
    }
  }

  object UpdateUserInfo extends LiftApiModule {
    val schema: UserManagementApi.UpdateUserInfo.type = UserManagementApi.UpdateUserInfo

    def process(
        version:    ApiVersion,
        path:       ApiPath,
        id:         String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      (for {
        u <- ZioJsonExtractor.parseJson[UpdateUserInfo](req).toIO
        _ <- userManagementService.updateInfo(id, u)
      } yield {
        u.transformInto[JsonUpdatedUserInfo]
      }).chainError(s"Could not update user '${id}' information").toLiftResponseOne(params, schema, _ => Some(id))
    }
  }

  object ActivateUser extends LiftApiModule {
    val schema: UserManagementApi.ActivateUser.type = UserManagementApi.ActivateUser

    def process(
        version:    ApiVersion,
        path:       ApiPath,
        id:         String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      (for {
        user       <- userRepo.get(id).notOptional(s"User '$id' does not exist therefore cannot be activated")
        jsonStatus <- user.status match {
                        case UserStatus.Active   => JsonStatus(UserStatus.Active).succeed
                        case UserStatus.Disabled => {
                          val eventTrace = EventTrace(
                            authzToken.qc.actor,
                            DateTime.now,
                            "User current disabled status set to 'active' by user management API"
                          )
                          (userRepo.setActive(List(user.id), eventTrace) *> userService.reloadPure())
                            .as(JsonStatus(UserStatus.Active))
                        }
                        case UserStatus.Deleted  =>
                          Inconsistency(s"User '$id' cannot be activated because the user is currently deleted").fail
                      }
      } yield {
        jsonStatus
      }).chainError(s"Could not activate user '$id'").toLiftResponseOne(params, schema, _ => Some(id))
    }
  }

  object DisableUser extends LiftApiModule {
    val schema: UserManagementApi.DisableUser.type = UserManagementApi.DisableUser

    def process(
        version:    ApiVersion,
        path:       ApiPath,
        id:         String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      (for {
        user       <- userRepo.get(id).notOptional(s"User '$id' does not exist therefore cannot be disabled")
        jsonStatus <- user.status match {
                        case UserStatus.Disabled => JsonStatus(UserStatus.Disabled).succeed
                        case UserStatus.Active   => {
                          val eventTrace = EventTrace(
                            authzToken.qc.actor,
                            DateTime.now,
                            "User current active status set to 'disabled' by user management API"
                          )
                          (userRepo.disable(List(user.id), None, List.empty, eventTrace) *> userService.reloadPure())
                            .as(JsonStatus(UserStatus.Disabled))
                        }
                        case UserStatus.Deleted  =>
                          Inconsistency(s"User '$id' cannot be disabled because the user is currently deleted").fail
                      }
      } yield {
        jsonStatus
      }).chainError(s"Could not disable user '$id'").toLiftResponseOne(params, schema, _ => Some(id))
    }
  }

  object RoleCoverage extends LiftApiModule {
    val schema: UserManagementApi.RoleCoverage.type = UserManagementApi.RoleCoverage

    def process(
        version:    ApiVersion,
        path:       ApiPath,
        id:         String,
        req:        Req,
        params:     DefaultParams,
        authzToken: AuthzToken
    ): LiftResponse = {
      (for {
        data          <- ZioJsonExtractor.parseJson[JsonRoleAuthorizations](req).toIO
        parsed        <- RudderRoles.parseRoles(data.permissions)
        coverage      <- UserManagementService
                           .computeRoleCoverage(
                             parsed.toSet,
                             data.authz.flatMap(a => AuthorizationType.parseRight(a).getOrElse(Set())).toSet ++ Role.ua
                           )
                           .notOptional("Could not compute role's coverage")
        roleAndCustoms = coverage.partitionMap {
                           case c: Custom => Right(c)
                           case r => Left(r)
                         }
      } yield {
        roleAndCustoms.transformInto[JsonCoverage]
      }).chainError(s"Could not get role's coverage user from request").toLiftResponseOne(params, schema, _ => None)
    }
  }

  def serialize(
      users: List[JsonUser]
  ): JsonAuthConfig = {
    // Aggregate all provider properties, if any provider can override user roles then it means there is a global override
    val providerRoleExtensions = getProviderRoleExtensions()
    val roleListOverride       = providerRoleExtensions.values.max
    val providersProperties    = providerRoleExtensions.view.mapValues(_.transformInto[JsonProviderProperty]).toMap

    JsonAuthConfig(roleListOverride, getAuthBackendsProviders(), providersProperties, users, tenantsService.tenantsEnabled)
  }

  private def transformUser(
      u:             RudderUserDetail,
      info:          UserInfo,
      providersInfo: Map[String, JsonProviderInfo],
      lastLogin:     Option[DateTime]
  )(implicit previousLogin: Option[DateTime]): JsonUser = {
    // NoRights and AnyRights directly map to known user permissions. AnyRights takes precedence over NoRights.
    if (u.authz.authorizationTypes.contains(AuthorizationType.AnyRights)) {
      JsonUser.anyRights(
        u.getUsername,
        info.name,
        info.email,
        info.otherInfo,
        u.status,
        providersInfo,
        getDisplayTenants(u),
        lastLogin = lastLogin,
        previousLogin = previousLogin
      )
    } else if (u.authz.authorizationTypes.isEmpty || u.authz.authorizationTypes.contains(AuthorizationType.NoRights)) {
      JsonUser.noRights(
        u.getUsername,
        info.name,
        info.email,
        info.otherInfo,
        u.status,
        providersInfo,
        getDisplayTenants(u),
        lastLogin = lastLogin,
        previousLogin = previousLogin
      )
    } else {
      JsonUser(
        u.getUsername,
        info.name,
        info.email,
        info.otherInfo,
        u.status,
        providersInfo,
        getDisplayTenants(u),
        lastLogin = lastLogin,
        previousLogin = previousLogin
      )
    }
  }

  implicit def transformDbUserToJsonUser(implicit
      userInfo:      UserInfo,
      nodePerms:     NodeSecurityContext,
      previousLogin: Option[DateTime]
  ): Transformer[UserSession, JsonUser] = {
    def getDisplayPermissions(userSession: UserSession): JsonRoles = {
      JsonRoles(userSession.permissions.flatMap {
        case customPermissionRegex(perm) => None
        case aliasedPermissionRegex(r)   => Some(r)
        case perm                        => Some(perm)
      }.toSet)
    }
    Transformer
      .define[UserSession, JsonUser]
      .withFieldConst(_.id, userInfo.id)
      .withFieldComputed(_.authz, s => JsonRights(s.authz.toSet))
      .withFieldComputed(_.roles, getDisplayPermissions(_))
      .withFieldComputed(_.rolesCoverage, getDisplayPermissions(_))
      .withFieldConst(_.name, userInfo.name)
      .withFieldConst(_.email, userInfo.email)
      .withFieldConst(_.otherInfo, userInfo.otherInfo)
      .withFieldConst(_.status, userInfo.status)
      .withFieldConst(_.providers, List(userInfo.managedBy))
      .withFieldComputed(
        _.providersInfo,
        s => {
          // we don't store "permissions given by provider" in sessions, so we assume that all permissions are given by the provider the user is managed by
          Map(
            userInfo.managedBy -> JsonProviderInfo(
              userInfo.managedBy,
              JsonRights(s.authz.toSet),
              getDisplayPermissions(s),
              JsonRights.empty
            )
          )
        }
      )
      .withFieldComputed(_.lastLogin, s => Some(s.creationDate))
      .withFieldConst(_.tenants, getDisplayTenants(nodePerms))
      .withFieldConst(_.previousLogin, previousLogin)
      .withFieldConst(_.customRights, JsonRights.empty)
      .buildTransformer
  }

  /**
   * Use the last session information as user permissions and authz.
   * The resulting user has the exact same permissions and authz as provided in the last user session.
   *
   * Current user permissions may be different if they have changed since we saved the user info, roles may also no longer exist,
   * so we do not attempt to parse as roles, but we still need to transform roles that are aliases or that are unnamed.
   */
  private def transformProvidedUser(userInfo: UserInfo, nodePerms: NodeSecurityContext, lastSession: Option[UserSession])(implicit
      allRoles:      Set[Role],
      previousLogin: Option[DateTime]
  ): JsonUser = {
    lastSession match {
      case None              => {
        val defaultUser = {
          RudderUserDetail(
            RudderAccount.User(userInfo.id, ""),
            userInfo.status,
            Set(),
            ApiAuthorization.None,
            NodeSecurityContext.None
          )
        }
        transformUser(
          defaultUser,
          userInfo,
          Map(userInfo.managedBy -> JsonProviderInfo.fromUser(defaultUser, userInfo.managedBy)),
          lastSession.map(_.creationDate)
        )
      }
      case Some(userSession) => {
        implicit val user:    UserInfo            = userInfo
        implicit val tenants: NodeSecurityContext = nodePerms
        userSession.transformInto[JsonUser]
      }
    }
  }

  private def getDisplayTenants(user: RudderUserDetail):         String = {
    getDisplayTenants(user.nodePerms)
  }
  private def getDisplayTenants(nodePerms: NodeSecurityContext): String = {
    nodePerms match {
      case NodeSecurityContext.All                => "all"
      case NodeSecurityContext.None               => "none"
      case NodeSecurityContext.ByTenants(tenants) => tenants.map(_.value).mkString(",")
    }
  }
}

object UserManagementApiImpl {
  // To filter out custom permissions of form "anon[..]" and take the aliased roles of form "alias(role)" (see toDisplayNames)
  private val customPermissionRegex  = """^anon\[(.*)\]$""".r
  private val aliasedPermissionRegex = """^.*\((.*)\)$""".r
}

/**
 * The internal API
 */
sealed trait UserManagementInternalApi extends EnumEntry with EndpointSchema with InternalApi with SortIndex

object UserManagementInternalApi extends Enum[UserManagementInternalApi] with ApiModuleProvider[UserManagementInternalApi] {

  final case object SafeHashes extends UserManagementInternalApi with ZeroParam with StartsAtVersion20 {
    val z              = implicitly[Line].value
    val description    = "Get the status of password encoder being used"
    val (action, path) = GET / "usermanagementinternal" / "safeHashes"

    override def dataContainer: Option[String] = None
  }

  def endpoints = values.toList.sortBy(_.z)
  def values    = findValues

}

class UserManagementInternalApiImpl(
    fileUserDetailListProvider: FileUserDetailListProvider
) extends LiftApiModuleProvider[UserManagementInternalApi] {

  override def schemas: ApiModuleProvider[UserManagementInternalApi] = UserManagementInternalApi

  override def getLiftEndpoints(): List[LiftApiModule] = {
    UserManagementInternalApi.endpoints.map { case UserManagementInternalApi.SafeHashes => SafeHashes }
  }

  object SafeHashes extends LiftApiModule0 {
    override val schema: UserManagementInternalApi.SafeHashes.type = UserManagementInternalApi.SafeHashes

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      implicit val prettify: Boolean = params.prettify

      val value = fileUserDetailListProvider.authConfig.encoder.securityLevel match {
        case SecurityLevel.Modern => true
        case SecurityLevel.Legacy => false
      }
      RudderJsonResponse.generic.success(value)
    }
  }
}
