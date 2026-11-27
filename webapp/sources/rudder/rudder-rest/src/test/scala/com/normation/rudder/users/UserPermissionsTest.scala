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

package com.normation.rudder.users

import com.normation.rudder.AuthorizationType
import com.normation.rudder.Role
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class UserPermissionsTest extends Specification {

  given Set[Role] = Role.allBuiltInRoles.values.toSet

  "Parsing user permissions" should {
    "split permissions in roles and authz" in {
      val parsed = UserPermissions.Unparsed(Set("node_write", "node_read", "read_only", "some_unknown_permission")).parsed
      (parsed.roles.toList.collect { case Role.Builtin(name, _) => name } must beEqualTo(List(Role.BuiltinName.ReadOnly))) and
      (parsed.authz must containTheSameElementsAs(List(AuthorizationType.Node.Write))) and
      (parsed.unknown must containTheSameElementsAs(List("some_unknown_permission")))
    }

    "ignore any_rights along with administrator" in {
      UserPermissions.Unparsed(Set("administrator", "any_rights")).parsed must beEqualTo(
        UserPermissions.Parsed(Set(Role.Administrator), Set.empty, Set.empty)
      )
    }

    "ignore no_rights along with another role" in {
      val parsed = UserPermissions.Unparsed(Set("no_rights", "user")).parsed
      (parsed.roles.toList.collect { case Role.Builtin(name, _) => name } must beEqualTo(List(Role.BuiltinName.User))) and
      (parsed.authz must beEmpty) and
      (parsed.unknown must beEmpty)
    }

    "ignore no_rights alone" in {
      UserPermissions.Unparsed(Set("no_rights")).parsed must beEqualTo(
        UserPermissions.Parsed(Set.empty, Set.empty, Set.empty)
      )
    }
  }

}
