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

import com.normation.rudder.users.FileUserDetailListProvider
import com.normation.rudder.users.RudderAuthorizationFileReloadCallback
import com.normation.rudder.users.RudderUserDetail
import com.normation.rudder.users.UserRepository
import com.normation.rudder.users.UserStatus
import com.normation.rudder.users.ValidatedUserList
import com.normation.zio.UnsafeRun
import java.io.IOException
import javax.servlet.FilterChain
import javax.servlet.ServletException
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import zio.Ref

/**
 * A filter that invalidates sessions of non-active users.
 * It has a local cache of user statuses that are updated from the actual database values, 
 * only when the users file is reloaded (using the RudderAuthorizationFileReloadCallback).
 */
class UserSessionInvalidationFilter(userRepository: UserRepository, userDetailListProvider: FileUserDetailListProvider)
    extends OncePerRequestFilter {
  private val userStatuses = Ref
    .make(Map.empty[String, UserStatus])
    .map(ref => {

      userDetailListProvider.registerCallback(
        RudderAuthorizationFileReloadCallback(
          "user-session-invalidation",
          (users: ValidatedUserList) => {
            userRepository
              .getStatuses(users.users.keys.toList)
              .flatMap(ref.set)
          }
        )
      )

      ref
    })
    .runNow

  @throws[ServletException]
  @throws[IOException]
  override def doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain): Unit = {
    val session = request.getSession(false)
    val auth    = SecurityContextHolder.getContext.getAuthentication
    if (auth != null) {
      val userDetails = auth.getPrincipal
      userDetails match { // we should only do session invalidation in specific cases : status is disabled/deleted, user is unknown
        case user: RudderUserDetail =>
          val status =
            userStatuses.get.map(_.getOrElse(user.getUsername, UserStatus.Deleted)) // unknown user : falls back to deleted

          status.runNow match {
            case UserStatus.Disabled | UserStatus.Deleted => session.invalidate()
            case _                                        => ()
          }
        case _ => ()
      }
    } // else do nothing : not logged in

    filterChain.doFilter(request, response)
  }

  override protected def shouldNotFilterAsyncDispatch = false

  override protected def shouldNotFilterErrorDispatch = false

}
