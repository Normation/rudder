package com.normation.rudder.users

import org.junit.runner.RunWith
import org.specs2.matcher.XmlMatchers
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class UserManagementServiceTest extends Specification with XmlMatchers {

  // content of the XML file
  val userXml = <authentication hash="sha-1" case-sensitivity="true">
    <user name="user1" password="1234" permissions="user" tenants="zoneA" />
    <user name="user2" password="a94a8fe5ccb19ba61c4c0873d391e987982fbbd3" permissions="read_only" />
  </authentication>

  // super secure password encoder
  val passwordEncoder = (data: PasswordEncoderData) => data.hashName + data.password.toString.reverse

  "checking that users exists" should {
    "be true for user1" in {
      UserManagementService.userExists("user1", userXml) must beTrue
    }

    "be false for alice" in {
      UserManagementService.userExists("alice", userXml) must beFalse
    }
  }

  "Adding an user" should {
    "add it in last position with the correct parameters with it does exists yet" in {
      val newUser = User("alice", "secret", Set("permA", "permB"), Some("tenantA"))
      val exec    = UserManagementIO.replaceXml(
        userXml,
        UserManagementService.addUserXmlRewriteRule(
          newUser,
          isPreHashed = false,
          passwordEncoder
        ),
        "testFile.xml"
      )

      exec must beRight(
        beEqualToIgnoringSpace(
          <authentication hash="sha-1" case-sensitivity="true">
          <user name="user1" password="1234" permissions="user" tenants="zoneA" />
          <user name="user2" password="a94a8fe5ccb19ba61c4c0873d391e987982fbbd3" permissions="read_only" />
          <user name="alice" password="sha-1terces" permissions="permA,permB" tenants="tenantA" />
        </authentication>
        )
      )
    }

    "lead to an error if user exists" in {
      val newUser = User("user1", "secret", Set("permA", "permB"), Some("tenantA"))
      val exec    = UserManagementIO.replaceXml(
        userXml,
        UserManagementService.addUserXmlRewriteRule(
          newUser,
          isPreHashed = false,
          passwordEncoder
        ),
        "testFile.xml"
      )

      exec must beLeft()
    }
  }

  "Deleting an user" should {
    "correctly delete it if it exist" in {
      val exec = UserManagementIO.replaceXml(userXml, UserManagementService.deleteUserXmlRewriteRule("user1"), "test")
      exec must beRight(
        beEqualToIgnoringSpace(
          <authentication hash="sha-1" case-sensitivity="true">
            <user name="user2" password="a94a8fe5ccb19ba61c4c0873d391e987982fbbd3" permissions="read_only" />
          </authentication>
        )
      )
    }

    "let the file unchanged if the user is not present" in {
      val exec = UserManagementIO.replaceXml(userXml, UserManagementService.deleteUserXmlRewriteRule("mallory"), "test")
      exec must beRight(
        beEqualToIgnoringSpace(
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
          "user3",
          "secret",
          Some(Set("perm1", "perm2")),
          isPreHashed = false,
          data => data.hashName + data.password.toString.reverse
        ),
        "test"
      )
      exec must beRight(
        beEqualToIgnoringSpace(
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
          "whatever",
          "whatever",
          None,
          isPreHashed = true,
          _ => ""
        ),
        "test"
      )
      exec must beLeft
    }
  }
}
