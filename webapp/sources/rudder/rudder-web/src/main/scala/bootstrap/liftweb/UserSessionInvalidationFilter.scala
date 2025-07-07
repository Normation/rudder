/*
 *************************************************************************************
 * Copyright 2024 Normation SAS
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
package bootstrap.liftweb

import com.normation.errors.*
import com.normation.rudder.domain.logger.ApplicationLoggerPure
import com.normation.rudder.users.FileUserDetailListProvider
import com.normation.rudder.users.RudderAuthorizationFileReloadCallback
import com.normation.rudder.users.RudderUserDetail
import com.normation.rudder.users.UserRepository
import com.normation.rudder.users.UserStatus
import com.normation.rudder.users.ValidatedUserList
import com.normation.zio.UnsafeRun
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.io.IOException
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import zio.*
import zio.syntax.*

/**
 * A filter that invalidates sessions of non-active users.
 * It has a local cache of user statuses that are updated from the actual database values,
 * only when the users file is reloaded (using the RudderAuthorizationFileReloadCallback).
 */
class UserSessionInvalidationFilter(userRepository: UserRepository, userDetailListProvider: FileUserDetailListProvider)
    extends OncePerRequestFilter {
  import UserSessionInvalidationFilter.*

  private val userCache = {
    Ref
      .make(Map.empty[String, Int])
      .map { ref =>
        userDetailListProvider.registerCallback(
          RudderAuthorizationFileReloadCallback(
            "user-session-invalidation",
            (users: ValidatedUserList) => {
              userRepository.getAllStatuses().flatMap(updateUsers(ref, _)(users.users))
            }
          )
        )

        ref
      }
      .runNow
  }

  @throws[ServletException]
  @throws[IOException]
  override def doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain): Unit = {
    val session = request.getSession(false)
    if (session != null) { // else do nothing : not logged in
      val auth = SecurityContextHolder.getContext.getAuthentication
      if (auth != null) { // else not logged in
        val userDetails = auth.getPrincipal
        userDetails match { // we should only do session invalidation in specific cases : status is disabled/deleted, user is unknown
          case user: RudderUserDetail =>
            val username = user.getUsername
            implicit val userDetail: RudderUserDetail = user
            (userCache.get
              .map(_.get(username))
              .flatMap {
                case checkUser(reason) =>
                  val endSessionReason = s"Session invalidated because ${reason}"
                  IOResult.attempt {
                    session.invalidate()
                    response.sendRedirect(request.getContextPath)
                  }
                    .foldZIO(
                      {
                        case SystemError(_, _: IllegalStateException) =>
                          ApplicationLoggerPure.info(
                            s"User session for user '${username}' is already invalidated because : ${reason}"
                          )
                        case err                                      =>
                          err
                            .copy(msg = {
                              s"User session for user '${username}' could not be invalidated for reason : ${reason}. " +
                              s"Please contact Rudder developers with the following explanation : ${err.fullMsg}"
                            })
                            .fail
                      },
                      _ => {
                        userRepository.logCloseSession(
                          user.getUsername,
                          DateTime.now(DateTimeZone.UTC),
                          endSessionReason
                        ) *>
                        ApplicationLoggerPure.info(
                          s"User session for user '${username}' is invalidated because : ${reason}"
                        )
                      }
                    )
                case _                 =>
                  // here, we absolutely need nonBlocking, else the with a blocking IO, we yield a new thread and
                  // at least in ZIO 2.1.12, the thread local context is lost.
                  IOResult.nonBlocking(filterChain.doFilter(request, response))

              })
              .runNow
          case _ => ()
        }
      } else {
        filterChain.doFilter(request, response)
      }
    } else {
      filterChain.doFilter(request, response)
    }
  }

  override protected def shouldNotFilterAsyncDispatch = false

  override protected def shouldNotFilterErrorDispatch = false

  /**
   * Get current status of user session invalidation in the cache.
   * Returns None if no reason for invalidation is found (ex: normal logout).
   */
  def getUserSessionStatus(): Option[String] = {
    Option(SecurityContextHolder.getContext.getAuthentication).flatMap { auth =>
      val userDetails = auth.getPrincipal
      userDetails match {
        case user: RudderUserDetail =>
          implicit val userDetail: RudderUserDetail = user
          val cached = userCache.get.map(_.get(user.getUsername)).runNow
          cached match {
            case checkUser(reason) => Some(reason)
            case _                 => None
          }
        case _ => None
      }
    }
  }

  /**
    * New user that is logging in from an external provider (other than file) needs
    * to be added or updated in the cache independently of the cache refresh workflow :
    * the user roles are not known yet and need to be updated.
    */
  def updateUser(user: RudderUserDetail): UIO[Unit] = {
    userCache.update(_ + (user.getUsername -> hashRudderUserDetail(user)))
  }
}

private[liftweb] object UserSessionInvalidationFilter {

  /**
   * Match an user in cache : if no user, it means its cache has changed, with a known or unknown cause of invalidity
   * if some user, it's ok, unless the last known status is invalid
   */
  object checkUser {
    def unapply(user: Option[Int])(implicit userDetail: RudderUserDetail): Option[String] = {
      user match {
        case None                                                           => Some("user is unknown")
        // admin session should not be invalidated, neither the cached nor current one
        case Some(HASH_USER_ADMIN)                                          => None
        case Some(_) if hashRudderUserDetail(userDetail) == HASH_USER_ADMIN => None
        // invalid user status should be invalidated
        case Some(HASH_USER_INVALID_STATUS)                                 => Some("user status has been updated to an invalid one")
        // user hash has changed
        case Some(hash) if hashRudderUserDetail(userDetail) != hash         => Some("user access to Rudder has been updated")
        // user hash is in cache
        case Some(_)                                                        => None
      }
    }
  }

  // special hash values are negative
  val HASH_USER_ADMIN:          Int = -1
  val HASH_USER_INVALID_STATUS: Int = -2

  // syntax that is reused in the context of checking user for "invalid status"
  implicit class UserStatusOps(status: UserStatus) {
    def isInvalid: Boolean = status.in(UserStatus.Disabled, UserStatus.Deleted)
  }

  // ensure the hash is positive, to be able to use special negative values
  private def simpleHash(username: String, password: String, authz: Set[String]): Int = {
    (username, password, authz).hashCode() & 0x7fffffff
  }

  // to this method should be only used to hash active users
  def hashRudderUserDetail(user: RudderUserDetail): Int = {
    if (user.isAdmin) {
      HASH_USER_ADMIN
    } else {
      simpleHash(user.getUsername, user.getPassword, user.authz.authorizationTypes.map(_.id))
    }
  }

  // special case of user that is not logged-in : hashed with known values
  // admin check needs to be the first one because it is more important than status check
  private[this] def hashRudderUserDetail(user: RudderUserDetail, status: UserStatus): Int = {
    if (user.isAdmin) {
      HASH_USER_ADMIN
    } else if (status.isInvalid) {
      HASH_USER_INVALID_STATUS
    } else {
      simpleHash(user.getUsername, user.getPassword, user.authz.authorizationTypes.map(_.id))
    }
  }

  // used on a user that has no known password and roles, not a RudderUserDetail
  private[this] def hashUser(username: String, status: UserStatus): Int = {
    if (status.isInvalid) {
      HASH_USER_INVALID_STATUS
    } else {
      // a user with empty roles is a safe fallback regarding security
      simpleHash(username, "", Set.empty)
    }
  }

  /**
   * User status have to be updated from the database.
   * This also adds new users to the Ref, since their password is known.
   * Externally provided users may be updated independently in the cache since
   * their password and roles are to be resolved at a specific time.
   */
  def updateUsers(
      ref:     Ref[Map[String, Int]],
      dbUsers: Map[String, UserStatus]
  ): Map[String, RudderUserDetail] => IOResult[Unit] = (users) => {
    // the source of users is the database, which contains all current users (and their status only)
    // user still in Ref but not in new users list should be removed
    val newUsers = dbUsers.map {
      case (id, status) =>
        users.get(id) match {
          case Some(user) =>
            // user is in file : prioritize file definition (even if for provisioned users, roles/password may be different)
            // cache will be updated with latest user at login anyway
            id -> hashRudderUserDetail(user, status)
          case None       =>
            id -> hashUser(id, status)
        }
    }

    ref.set(newUsers)
  }
}
