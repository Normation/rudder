/*
 *************************************************************************************
 * Copyright 2026 Normation SAS
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

package com.normation.rudder.services.workflows

import com.normation.rudder.AuthorizationType
import com.normation.rudder.Rights
import com.normation.rudder.api.ApiAuthorization
import com.normation.rudder.domain.workflows.ChangeRequestId
import com.normation.rudder.domain.workflows.ChangeRequestInfo
import com.normation.rudder.domain.workflows.RollbackChangeRequest
import com.normation.rudder.facts.nodes.NodeSecurityContext
import com.normation.rudder.users.AuthenticatedUser
import com.normation.rudder.users.RudderAccount
import com.normation.rudder.users.UserPassword
import org.junit.runner.RunWith
import zio.*
import zio.test.*
import zio.test.junit.ZTestJUnitRunner

/*
 * Test the logic for single owner-vs-user mapping → Author/NotAuthor
 * https://issues.rudder.io/issues/29241 and https://issues.rudder.io/issues/29240
 */
@RunWith(classOf[ZTestJUnitRunner])
class SelfValidationAuthorTest extends ZIOSpecDefault {

  // an authenticated user granted exactly `granted`; `name` is what a change request `owner` is matched against
  private def userWith(granted: Set[AuthorizationType], userLogin: String): AuthenticatedUser = {
    new AuthenticatedUser {
      override val account:   RudderAccount       = RudderAccount.User(userLogin, UserPassword.fromSecret("pwd"))
      override val authz:     Rights              = Rights.AnyRights
      override val apiAuthz:  ApiAuthorization    = ApiAuthorization.RW
      override val nodePerms: NodeSecurityContext = NodeSecurityContext.All

      override def checkRights(auth: AuthorizationType): Boolean = granted.contains(auth)
    }
  }

  override def spec: Spec[TestEnvironment & Scope, Any] = suite("self-validation / self-deployment control")(
    // ---------------------------------------------------------------------------------------
    // how authorship is derived: ChangeRequestAuthorship.of compares the change
    // request owner to the acting user's name (the single place holding that logic).
    // ---------------------------------------------------------------------------------------
    suite("F - ChangeRequestAuthorship.of maps (change request, user) to authorship")(
      test("the change request owner is the acting user -> Author") {
        val cr = RollbackChangeRequest(ChangeRequestId(1), None, ChangeRequestInfo("cr", "desc"), owner = "alice")
        assertTrue(ChangeRequestAuthorship.of(cr, userWith(Set.empty, userLogin = "alice")) == ChangeRequestAuthorship.Author)
      },
      test("the change request owner is someone else -> NotAuthor") {
        val cr = RollbackChangeRequest(ChangeRequestId(1), None, ChangeRequestInfo("cr", "desc"), owner = "alice")
        assertTrue(ChangeRequestAuthorship.of(cr, userWith(Set.empty, userLogin = "bob")) == ChangeRequestAuthorship.NotAuthor)
      }
    )
  )
}
