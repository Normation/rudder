/*
 *************************************************************************************
 * Copyright 2025 Normation SAS
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

import com.normation.XmlSpecMatcher
import com.normation.rudder.users.UserPassword.SecretUserPassword
import com.normation.rudder.users.UserPassword.UnknownPassword
import com.normation.rudder.users.UserPassword.UserPasswordEncoder
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class UserManagementServiceTest extends Specification with XmlSpecMatcher {

  // content of the XML file
  val userXml = <authentication hash="sha-1" case-sensitivity="true">
    <user name="user1" password="1234" permissions="user" tenants="zoneA" />
    <user name="user2" password="a94a8fe5ccb19ba61c4c0873d391e987982fbbd3" permissions="read_only" />
  </authentication>

  // super secure password encoder
  val passwordEncoder = (data: PasswordEncoderData) => {
    new UserPasswordEncoder[SecretUserPassword] {
      override protected[users] def encode(t: CharSequence): Argon2HashString = {
        data.hashName + t.toString.reverse
      }
    }
  }

  "checking that users exists" should {
    "be true for user1" in {
      UserManagementService.userExists("user1", userXml) must beTrue
    }

    "be false for alice" in {
      UserManagementService.userExists("alice", userXml) must beFalse
    }
  }

  "Adding an user" should {
    "add it in last position with the correct parameters when it does not exists yet" in {
      val newUser = User("alice", UnknownPassword("secret"), Set("permA", "permB"), Some("tenantA"))
      val exec    = UserManagementIO.replaceXml(
        userXml,
        UserManagementService.addUserXmlRewriteRule(newUser),
        "testFile.xml"
      )

      exec must beRight(
        equalsIgnoringSpace(
          <authentication hash="sha-1" case-sensitivity="true">
          <user name="user1" password="1234" permissions="user" tenants="zoneA" />
          <user name="user2" password="a94a8fe5ccb19ba61c4c0873d391e987982fbbd3" permissions="read_only" />
          <user name="alice" password="secret" permissions="permA,permB" tenants="tenantA" />
        </authentication>
        )
      )
    }

    "lead to an error if user exists" in {
      val newUser = User("user1", UnknownPassword("secret"), Set("permA", "permB"), Some("tenantA"))
      val exec    = UserManagementIO.replaceXml(
        userXml,
        UserManagementService.addUserXmlRewriteRule(newUser),
        "testFile.xml"
      )

      exec must beLeft
    }
  }

  "Deleting an user" should {
    "correctly delete it if it exist" in {
      val exec = UserManagementIO.replaceXml(userXml, UserManagementService.deleteUserXmlRewriteRule("user1"), "test")
      exec must beRight(
        equalsIgnoringSpace(
          <authentication hash="sha-1" case-sensitivity="true">
            <user name="user2" password="a94a8fe5ccb19ba61c4c0873d391e987982fbbd3" permissions="read_only" />
          </authentication>
        )
      )
    }

    "let the file unchanged if the user is not present" in {
      val exec = UserManagementIO.replaceXml(userXml, UserManagementService.deleteUserXmlRewriteRule("mallory"), "test")
      exec must beRight(
        equalsIgnoringSpace(
          <authentication hash="sha-1" case-sensitivity="true">
            <user name="user1" password="1234" permissions="user" tenants="zoneA" />
            <user name="user2" password="a94a8fe5ccb19ba61c4c0873d391e987982fbbd3" permissions="read_only" />
          </authentication>
        )
      )
    }
  }

  "Updating an user" should {

    "update it as wanted when it exists" in {
      val exec = UserManagementIO.replaceXml(
        userXml,
        UserManagementService.updateUserXmlRewriteRule(
          "user1",
          JsonUserFormData("user3", "secret", Some(List("perm1", "perm2")), isPreHashed = false, None, None, None),
          passwordEncoder
        ),
        "test"
      )
      exec must beRight(
        equalsIgnoringSpace(
          <authentication hash="sha-1" case-sensitivity="true">
            <user name="user3" password="sha-1terces" permissions="perm1,perm2" tenants="zoneA" />
            <user name="user2" password="a94a8fe5ccb19ba61c4c0873d391e987982fbbd3" permissions="read_only" />
          </authentication>
        )
      )
    }

    "lead to an error is user doesn't exists" in {
      val exec = UserManagementIO.replaceXml(
        userXml,
        UserManagementService.updateUserXmlRewriteRule(
          "alice",
          JsonUserFormData("whatever", "whatever", None, isPreHashed = true, None, None, None),
          passwordEncoder
        ),
        "test"
      )
      exec must beLeft
    }
  }
}
